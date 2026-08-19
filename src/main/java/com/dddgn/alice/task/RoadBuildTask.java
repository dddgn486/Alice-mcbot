package com.dddgn.alice.task;

import com.dddgn.alice.action.BotMiner;
import com.dddgn.alice.pathing.AStarPathfinder;
import com.dddgn.alice.pathing.Goal;
import com.dddgn.alice.pathing.PathExecutor;
import com.dddgn.alice.protection.BlockBreakSafety;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.road.RoadPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Bot 专用道路执行任务。施工不是普通放置任务：当前单元完成并稳定前，bot 不前进。
 * 圆石由任务直接提供，暂不消耗背包；移动阶段仍使用现有 Baritone 风格 A*。
 */
public final class RoadBuildTask implements Task {
    private enum Phase { MINE_START, MOVE_TO_UNIT, BUILD_UNIT, WAIT_STABLE, MINE_TARGET, DONE }

    private static final int STABLE_WAIT_TICKS = 5;
    private static final int MAX_STABILITY_RETRIES = 3;

    private final ServerPlayer bot;
    private final RoadPlan plan;
    private final ServerLevel level;
    private final ScopeBuffer scope;
    private Phase phase = Phase.MINE_START;
    private BotMiner startMiner;
    private MineTask targetTask;
    private PathExecutor mover;
    private int unitIndex;
    private int waitTicks;
    private int stabilityRetries;
    private String failure = "";

    public RoadBuildTask(ServerPlayer bot, RoadPlan plan, ScopeBuffer scope) {
        this.bot = bot;
        this.plan = plan;
        this.level = (ServerLevel) bot.level();
        this.scope = scope;
        this.startMiner = new BotMiner(bot, plan.first());
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
            case MINE_START -> tickMineStart();
            case MOVE_TO_UNIT -> tickMoveToUnit();
            case BUILD_UNIT -> tickBuildUnit();
            case WAIT_STABLE -> tickWaitStable();
            case MINE_TARGET -> tickMineTarget();
            case DONE -> Status.DONE;
        };
    }

    private Status tickMineStart() {
        if (plan.first().equals(plan.second())) {
            failure = "road_endpoints_equal";
            return Status.FAILED;
        }
        BotMiner.Status status = startMiner.tick();
        if (status == BotMiner.Status.FAILED) {
            failure = "road_start_mine_" + startMiner.failureReason();
            return Status.FAILED;
        }
        if (status != BotMiner.Status.DONE) return Status.RUNNING;
        phase = Phase.MOVE_TO_UNIT;
        return Status.RUNNING;
    }

    private Status tickMoveToUnit() {
        RoadPlan.Unit unit = plan.units().get(unitIndex);
        BlockPos foot = unit.support().above();
        if (bot.blockPosition().equals(foot)) {
            phase = Phase.BUILD_UNIT;
            return Status.RUNNING;
        }
        if (mover == null) {
            List<BlockPos> path = AStarPathfinder.computePath(level, bot.blockPosition(),
                    new Goal.GoalBlock(foot));
            if (path.isEmpty()) {
                failure = "road_no_path_unit_" + unitIndex;
                return Status.FAILED;
            }
            mover = new PathExecutor(bot, path);
        }
        PathExecutor.Status status = mover.tick();
        if (status == PathExecutor.Status.FAILED) {
            failure = mover.wasObstructed() ? "road_path_obstructed_unit_" + unitIndex
                    : "road_path_failed_unit_" + unitIndex;
            return Status.FAILED;
        }
        if (status == PathExecutor.Status.DONE) {
            mover = null;
            phase = Phase.BUILD_UNIT;
        }
        return Status.RUNNING;
    }

    private Status tickBuildUnit() {
        buildUnit(plan.units().get(unitIndex));
        waitTicks = STABLE_WAIT_TICKS;
        phase = Phase.WAIT_STABLE;
        return Status.RUNNING;
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
        unitIndex++;
        if (unitIndex >= plan.units().size()) {
            targetTask = new MineTask(bot, plan.second(), scope);
            phase = Phase.MINE_TARGET;
        } else {
            phase = Phase.MOVE_TO_UNIT;
        }
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

    private void buildUnit(RoadPlan.Unit unit) {
        Set<BlockPos> clearance = new LinkedHashSet<>();
        for (RoadPlan.Cell cell : unit.cells()) {
            if (!cell.pos().equals(unit.support())) clearance.add(cell.pos());
        }
        for (BlockPos pos : clearance) {
            if (pos.equals(plan.first()) || pos.equals(plan.second())) continue;
            if (!level.getBlockState(pos).isAir()
                    && BlockBreakSafety.clearingRefusal(bot, pos) == null) {
                level.destroyBlock(pos, false);
            }
        }
        if (!unit.support().equals(plan.first()) && !unit.support().equals(plan.second())
                && level.getBlockState(unit.support()).isAir()) {
            level.setBlock(unit.support(), Blocks.COBBLESTONE.defaultBlockState(), 3);
        }
    }

    private boolean hasUnstableMaterial(RoadPlan.Unit unit) {
        BlockPos support = unit.support();
        int upperScan = unit.headroom() + 6;
        for (int dy = -1; dy <= upperScan; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos pos = support.offset(dx, dy, dz);
                    if (isUnstableFallingBlock(pos)) return true;
                }
            }
        }
        var box = new net.minecraft.world.phys.AABB(support).inflate(1.5D, upperScan, 1.5D);
        return !level.getEntitiesOfClass(FallingBlockEntity.class, box).isEmpty();
    }

    private boolean isUnstableFallingBlock(BlockPos pos) {
        if (!(level.getBlockState(pos).getBlock() instanceof FallingBlock)) return false;
        var below = level.getBlockState(pos.below());
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
                    if (isUnstableFallingBlock(pos)
                            && BlockBreakSafety.clearingRefusal(bot, pos) == null) {
                        level.destroyBlock(pos, false);
                    }
                }
            }
        }
        var box = new net.minecraft.world.phys.AABB(support).inflate(1.5D, upperScan, 1.5D);
        for (FallingBlockEntity entity : level.getEntitiesOfClass(FallingBlockEntity.class, box)) {
            entity.discard();
        }
    }
}
