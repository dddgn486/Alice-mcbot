package com.dddgn.alice.task;

import com.dddgn.alice.pathing.AStarPathfinder;
import com.dddgn.alice.pathing.Goal;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.PathExecutor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** 独立方块放置测试任务；不调用 RoadBuilder 或 MineTask。 */
public final class PlaceTask implements Task {
    private static final double MAX_REACH = 4.5D;
    private final ServerPlayer bot;
    private final BlockPos target;
    private PathExecutor executor;
    private List<BlockPos> candidates;
    private BlockPos selected;
    private BlockPos stand;
    private String failure = "";

    public PlaceTask(ServerPlayer bot, BlockPos target) {
        this.bot = bot;
        this.target = target.immutable();
    }

    @Override public TaskTarget target() { return TaskTarget.block(target); }
    @Override public String failureReason() { return failure; }

    @Override
    public Status tick() {
        if (!bot.level().getBlockState(target).isAir()) return Status.DONE;
        if (selected == null && candidates == null) {
            candidates = pickCandidates();
            if (candidates.isEmpty()) {
                failure = "place_no_stand";
                return Status.FAILED;
            }
        }
        if (selected == null) {
            while (!candidates.isEmpty()) {
                BlockPos candidate = candidates.remove(0);
                if (candidate.equals(bot.blockPosition())) {
                    selected = candidate;
                    stand = candidate;
                    break;
                }
                List<BlockPos> path = AStarPathfinder.computePath(
                        (net.minecraft.server.level.ServerLevel) bot.level(), bot.blockPosition(),
                        new Goal.GoalBlock(candidate));
                if (!path.isEmpty()) {
                    selected = candidate;
                    stand = candidate;
                    executor = new PathExecutor(bot, path);
                    break;
                }
            }
            if (selected == null) {
                failure = "place_no_path";
                return Status.FAILED;
            }
        }
        if (executor != null) {
            PathExecutor.Status status = executor.tick();
            if (status == PathExecutor.Status.FAILED) {
                failure = "place_path_failed";
                return Status.FAILED;
            }
            if (status == PathExecutor.Status.MOVING) return Status.RUNNING;
            executor = null;
        }
        if (!canReachAndSee()) {
            failure = "place_line_of_sight";
            return Status.FAILED;
        }
        faceTarget();
        bot.level().setBlock(target, Blocks.COBBLESTONE.defaultBlockState(), 3);
        return Status.DONE;
    }

    /** 生成可站位候选；放置动作只在最终阶段做目标空气中心视线检查。 */
    private List<BlockPos> pickCandidates() {
        List<BlockPos> result = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -4; dy <= 3; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos standPos = target.offset(dx, dy, dz);
                    if (standPos.equals(target) || standPos.above().equals(target)) continue;
                    if (!MovementHelper.canWalkOn((net.minecraft.server.level.ServerLevel) bot.level(), standPos)
                            || !MovementHelper.canWalkThrough((net.minecraft.server.level.ServerLevel) bot.level(), standPos)
                            || !MovementHelper.canWalkThrough((net.minecraft.server.level.ServerLevel) bot.level(), standPos.above())) {
                        continue;
                    }
                    Vec3 eye = new Vec3(standPos.getX() + 0.5D, standPos.getY() + 1.62D,
                            standPos.getZ() + 0.5D);
                    if (eye.distanceTo(target.getCenter()) <= MAX_REACH - 0.3D) {
                        result.add(standPos.immutable());
                    }
                }
            }
        }
        result.sort(java.util.Comparator.comparingDouble(p -> p.distSqr(target)));
        return result.size() > 16 ? result.subList(0, 16) : result;
    }

    private boolean canReachAndSee() {
        if (selected == null) return false;
        Vec3 eye = bot.getEyePosition();
        if (eye.distanceTo(target.getCenter()) > MAX_REACH) return false;
        BlockHitResult hit = bot.level().clip(new ClipContext(eye, target.getCenter(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot));
        return hit.getType() == HitResult.Type.MISS;
    }

    /** 与 BotMiner 一致：放置前同步身体、头部和俯仰朝向目标中心。 */
    private void faceTarget() {
        Vec3 eye = bot.getEyePosition();
        Vec3 center = target.getCenter();
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
