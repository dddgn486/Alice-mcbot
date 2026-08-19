package com.dddgn.alice.task;

import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.road.RoadPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 道路蓝图的 bot 施工演示任务。
 * <p>RoadPlan 已经保证道路的禁区与通行几何。本任务不使用普通寻路、视线或放置面判定：
 * 它逐个清理蓝图净空、无限提供圆石支撑、等待稳定，然后平滑强制移动到已完成的下一单元。
 * 这让施工过程可见，同时产出的道路仍是玩家可通过的真实方块结构。</p>
 */
public final class RoadBuildTask implements Task {
    private enum Phase { BUILD_UNIT, WAIT_STABLE, MOVE_TO_NEXT_UNIT, MINE_TARGET, DONE }

    private static final int STABLE_WAIT_TICKS = 5;
    private static final int MAX_STABILITY_RETRIES = 3;
    private static final double MOVE_SPEED = 0.25D;
    private static final double ARRIVE_DISTANCE = 0.3D;

    private final ServerPlayer bot;
    private final RoadPlan plan;
    private final ServerLevel level;
    private final ScopeBuffer scope;
    private Phase phase = Phase.BUILD_UNIT;
    private MineTask targetTask;
    private int unitIndex;
    private int waitTicks;
    private int stabilityRetries;
    private boolean positionedAtFirstUnit;
    private int moveDestinationIndex;
    private List<BlockPos> pendingClearance = List.of();
    private int clearanceIndex;
    private boolean supportPlaced;
    private String failure = "";

    public RoadBuildTask(ServerPlayer bot, RoadPlan plan, ScopeBuffer scope) {
        this.bot = bot;
        this.plan = plan;
        this.level = (ServerLevel) bot.level();
        this.scope = scope;
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(plan.second());
    }

    @Override
    public String failureReason() {
        return failure;
    }

    @Override
    public Status tick() {
        if (!plan.isComplete() || plan.level() != level || plan.units().isEmpty()) {
            failure = "road_plan_invalid";
            return Status.FAILED;
        }
        return switch (phase) {
            case BUILD_UNIT -> tickBuildUnit();
            case WAIT_STABLE -> tickWaitStable();
            case MOVE_TO_NEXT_UNIT -> tickMoveToNextUnit();
            case MINE_TARGET -> tickMineTarget();
            case DONE -> Status.DONE;
        };
    }

    /**
     * 一个 tick 只处理一个净空方块，随后再放置支撑；首个单元也走这条链，
     * 因而起点方块会作为首单元空腔的一部分被清掉，而不是先单独挖起点。
     */
    private Status tickBuildUnit() {
        RoadPlan.Unit unit = plan.units().get(unitIndex);
        if (pendingClearance.isEmpty()) {
            pendingClearance = collectClearance(unit);
            clearanceIndex = 0;
            supportPlaced = false;
        }
        while (clearanceIndex < pendingClearance.size()) {
            BlockPos pos = pendingClearance.get(clearanceIndex++);
            // 终点目标保留到整个通道已完工后，用正常 MineTask 在可挖站位执行。
            if (pos.equals(plan.second()) || level.getBlockState(pos).isAir()) continue;
            if (!forceBreak(pos)) return Status.FAILED;
            return Status.RUNNING;
        }
        if (!supportPlaced) {
            if (level.getBlockState(unit.support()).isAir()) {
                faceTarget(unit.support());
                level.setBlock(unit.support(), Blocks.COBBLESTONE.defaultBlockState(), 3);
                bot.swing(InteractionHand.MAIN_HAND);
            }
            supportPlaced = true;
            return Status.RUNNING;
        }
        pendingClearance = List.of();
        waitTicks = STABLE_WAIT_TICKS;
        phase = Phase.WAIT_STABLE;
        return Status.RUNNING;
    }

    /**
     * 施工动画不做距离、视线或原版交互面检查；仅在当前主手确实无法破坏该方块时失败。
     */
    private boolean forceBreak(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getDestroyProgress(bot, level, pos) <= 0.0F) {
            failure = "road_tool_cannot_break_" + pos.toShortString();
            return false;
        }
        faceTarget(pos);
        bot.swing(InteractionHand.MAIN_HAND);
        level.destroyBlock(pos, true, bot);
        return true;
    }

    private Status tickWaitStable() {
        if (--waitTicks > 0) return Status.RUNNING;
        RoadPlan.Unit unit = plan.units().get(unitIndex);
        if (hasUnstableMaterial(unit)) {
            if (stabilityRetries++ >= MAX_STABILITY_RETRIES) {
                failure = "road_unstable_unit_" + unitIndex;
                return Status.FAILED;
            }
            removeUnstableMaterial(unit);
            phase = Phase.BUILD_UNIT;
            return Status.RUNNING;
        }
        stabilityRetries = 0;
        // 第一个单元建好后先站到其脚位，再开始向第二单元施工；之后严格逐单元推进。
        if (!positionedAtFirstUnit) {
            positionedAtFirstUnit = true;
            moveDestinationIndex = 0;
            phase = Phase.MOVE_TO_NEXT_UNIT;
        } else if (unitIndex + 1 >= plan.units().size()) {
            // 最终支撑格的上方就是仍存在的目标方块，不能强制把 bot 移入该格。
            // bot 此时已在倒数第二个缓冲单元，直接交给 MineTask 从当前可挖站位处理目标。
            targetTask = new MineTask(bot, plan.second(), scope);
            phase = Phase.MINE_TARGET;
        } else {
            moveDestinationIndex = unitIndex + 1;
            phase = Phase.MOVE_TO_NEXT_UNIT;
        }
        return Status.RUNNING;
    }

    /** 已完成单元之间使用和 PathExecutor 相同的平滑位置步进，但不重做碰撞/寻路判定。 */
    private Status tickMoveToNextUnit() {
        int destinationIndex = moveDestinationIndex;
        RoadPlan.Unit next = plan.units().get(destinationIndex);
        BlockPos foot = next.support().above();
        double goalX = foot.getX() + 0.5D;
        double goalZ = foot.getZ() + 0.5D;
        double dx = goalX - bot.getX();
        double dz = goalZ - bot.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal <= ARRIVE_DISTANCE) {
            bot.setPos(goalX, foot.getY(), goalZ);
            bot.setOnGround(true);
            bot.fallDistance = 0.0F;
            if (destinationIndex == unitIndex && unitIndex + 1 >= plan.units().size()) {
                targetTask = new MineTask(bot, plan.second(), scope);
                phase = Phase.MINE_TARGET;
            } else {
                unitIndex++;
                phase = Phase.BUILD_UNIT;
            }
            return Status.RUNNING;
        }
        double step = Math.min(MOVE_SPEED, horizontal);
        bot.setPos(bot.getX() + dx / horizontal * step, bot.getY(), bot.getZ() + dz / horizontal * step);
        return Status.RUNNING;
    }

    private Status tickMineTarget() {
        Task.Status status = targetTask.tick();
        if (status == Task.Status.FAILED) {
            failure = "road_target_" + targetTask.failureReason();
            return Status.FAILED;
        }
        if (status == Task.Status.DONE) {
            phase = Phase.DONE;
            return Status.DONE;
        }
        return Status.RUNNING;
    }

    private List<BlockPos> collectClearance(RoadPlan.Unit unit) {
        Set<BlockPos> positions = new LinkedHashSet<>();
        for (RoadPlan.Cell cell : unit.cells()) {
            if (!cell.pos().equals(unit.support())) positions.add(cell.pos());
        }
        return new ArrayList<>(positions);
    }

    private boolean hasUnstableMaterial(RoadPlan.Unit unit) {
        BlockPos support = unit.support();
        int upperScan = unit.headroom() + 6;
        for (int dy = -1; dy <= upperScan; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (isUnstableFallingBlock(support.offset(dx, dy, dz))) return true;
                }
            }
        }
        var box = new net.minecraft.world.phys.AABB(support).inflate(1.5D, upperScan, 1.5D);
        return !level.getEntitiesOfClass(FallingBlockEntity.class, box).isEmpty();
    }

    private boolean isUnstableFallingBlock(BlockPos pos) {
        if (!(level.getBlockState(pos).getBlock() instanceof FallingBlock)) return false;
        BlockState below = level.getBlockState(pos.below());
        return below.isAir() || below.getCollisionShape(level, pos.below()).isEmpty()
                || !below.getFluidState().isEmpty();
    }

    private void removeUnstableMaterial(RoadPlan.Unit unit) {
        BlockPos support = unit.support();
        int upperScan = unit.headroom() + 6;
        for (int dy = -1; dy <= upperScan; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos pos = support.offset(dx, dy, dz);
                    if (isUnstableFallingBlock(pos)) level.destroyBlock(pos, true, bot);
                }
            }
        }
        var box = new net.minecraft.world.phys.AABB(support).inflate(1.5D, upperScan, 1.5D);
        for (FallingBlockEntity entity : level.getEntitiesOfClass(FallingBlockEntity.class, box)) entity.discard();
    }

    private void faceTarget(BlockPos pos) {
        Vec3 eye = bot.getEyePosition();
        Vec3 center = pos.getCenter();
        double dx = center.x - eye.x;
        double dy = center.y - eye.y;
        double dz = center.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(Math.atan2(-dy, Math.sqrt(dx * dx + dz * dz)));
        bot.setYRot(yaw);
        bot.setXRot(pitch);
        bot.setYHeadRot(yaw);
        bot.connection.send(new net.minecraft.network.protocol.game.ClientboundRotateHeadPacket(
                bot, (byte) (yaw * 256.0F / 360.0F)));
        bot.connection.send(new net.minecraft.network.protocol.game.ClientboundMoveEntityPacket.Rot(
                bot.getId(), (byte) (yaw * 256.0F / 360.0F),
                (byte) (pitch * 256.0F / 360.0F), bot.onGround()));
    }
}
