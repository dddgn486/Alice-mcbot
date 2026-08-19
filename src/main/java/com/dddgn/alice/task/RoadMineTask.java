package com.dddgn.alice.task;

import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.AStarPathfinder;
import com.dddgn.alice.pathing.Goal;
import com.dddgn.alice.pathing.PathExecutor;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.road.RoadPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 目标挖掘专用的道路执行器。
 * 曲面单元使用现有 A* 路径和 PathExecutor；通道单元按真实破坏进度逐格清理，
 * 并严格在已完成单元上施工，避免把 bot 放进尚未完成的脚下方块。
 */
public final class RoadMineTask implements Task {
    private enum Phase { MOVE_SURFACE, BUILD_TUNNEL, MOVE_ONTO_UNIT, MINE_TARGET, DONE }

    private static final int MAX_PATH_RETRIES = 2;
    private static final int MINE_TIMEOUT_TICKS = 400;
    private static final double ARRIVE_DISTANCE = 0.35D;

    private final ServerPlayer bot;
    private final ServerLevel level;
    private final BlockPos target;
    private final ScopeBuffer scope;
    private final List<RoadPlan.Unit> units;
    private final List<BlockPos> pendingClearance = new ArrayList<>();

    private Phase phase = Phase.MOVE_SURFACE;
    private PathExecutor executor;
    private MineTask targetTask;
    private int unitIndex;
    private int surfaceDestinationIndex = -1;
    private int clearIndex;
    private int pathRetries;
    private float breakProgress;
    private int breakElapsed;
    private BlockPos breaking;
    private String failure = "";

    public RoadMineTask(ServerPlayer bot, BlockPos target, ScopeBuffer scope) {
        this.bot = bot;
        this.level = (ServerLevel) bot.level();
        this.target = target.immutable();
        this.scope = scope;
        this.units = new ArrayList<>(RoadPlan.planForMining(level, bot.blockPosition(), target));
        bot.getInventory().setItem(bot.getInventory().selected, new ItemStack(Items.DIAMOND_PICKAXE));
        com.dddgn.alice.bot.BotManager.syncMainHand(bot);
        BotLog.info("道路目标挖掘任务创建: target={} units={}", target.toShortString(), units.size());
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(target);
    }

    @Override
    public String failureReason() {
        return failure;
    }

    /** 终点挖掘开始站位，供现有任务验收接口透传。 */
    public BlockPos mineStartPos() {
        return targetTask == null ? null : targetTask.mineStartPos();
    }

    @Override
    public Status tick() {
        if (units.isEmpty()) {
            failure = "road_no_route";
            return Status.FAILED;
        }
        return switch (phase) {
            case MOVE_SURFACE -> tickMoveSurface();
            case BUILD_TUNNEL -> tickBuildTunnel();
            case MOVE_ONTO_UNIT -> tickMoveOntoUnit();
            case MINE_TARGET -> tickMineTarget();
            case DONE -> Status.DONE;
        };
    }

    private Status tickMoveSurface() {
        if (unitIndex >= units.size()) return startTargetMine();
        if (!isSurface(units.get(unitIndex))) {
            if (unitIndex == 0) {
                failure = "road_tunnel_starts_under_bot";
                return Status.FAILED;
            }
            prepareTunnel(units.get(unitIndex));
            phase = Phase.BUILD_TUNNEL;
            return Status.RUNNING;
        }
        if (surfaceDestinationIndex < unitIndex) {
            surfaceDestinationIndex = unitIndex;
            while (surfaceDestinationIndex + 1 < units.size()
                    && isSurface(units.get(surfaceDestinationIndex + 1))) {
                surfaceDestinationIndex++;
            }
        }
        BlockPos destination = units.get(surfaceDestinationIndex).support().above();
        if (executor == null) {
            if (bot.blockPosition().equals(destination)) {
                unitIndex = surfaceDestinationIndex + 1;
                surfaceDestinationIndex = -1;
                return Status.RUNNING;
            }
            List<BlockPos> path = AStarPathfinder.computePath(level, bot.blockPosition(),
                    new Goal.GoalBlock(destination));
            if (path.isEmpty()) {
                failure = "road_surface_no_path_" + destination.toShortString();
                return Status.FAILED;
            }
            executor = new PathExecutor(bot, path);
            BotLog.info("道路曲面移动: units={}..{} destination={} path={}",
                    unitIndex, surfaceDestinationIndex, destination.toShortString(), path.size());
        }
        PathExecutor.Status status = executor.tick();
        if (status == PathExecutor.Status.FAILED) {
            if (executor.wasObstructed() && pathRetries++ < MAX_PATH_RETRIES) {
                executor = null;
                return Status.RUNNING;
            }
            failure = "road_surface_path_failed";
            return Status.FAILED;
        }
        if (status == PathExecutor.Status.DONE) {
            executor = null;
            pathRetries = 0;
            unitIndex = surfaceDestinationIndex + 1;
            surfaceDestinationIndex = -1;
        }
        return Status.RUNNING;
    }

    private void prepareTunnel(RoadPlan.Unit unit) {
        pendingClearance.clear();
        for (RoadPlan.Cell cell : unit.cells()) {
            if (!cell.pos().equals(unit.support()) && !cell.pos().equals(target)
                    && !level.getBlockState(cell.pos()).isAir()) {
                pendingClearance.add(cell.pos());
            }
        }
        clearIndex = 0;
        breaking = null;
        breakProgress = 0.0F;
        breakElapsed = 0;
        BotLog.info("道路通道施工开始: unit={} support={} clearance={}",
                unitIndex, unit.support().toShortString(), pendingClearance.size());
    }

    private Status tickBuildTunnel() {
        RoadPlan.Unit unit = units.get(unitIndex);
        if (clearIndex < pendingClearance.size()) {
            BlockPos pos = pendingClearance.get(clearIndex);
            if (level.getBlockState(pos).isAir()) {
                clearIndex++;
                resetBreak();
                return Status.RUNNING;
            }
            if (!tickBreak(pos)) return Status.FAILED;
            if (level.getBlockState(pos).isAir()) {
                clearIndex++;
                resetBreak();
            }
            return Status.RUNNING;
        }
        if (level.getBlockState(unit.support()).isAir()) {
            level.setBlock(unit.support(), Blocks.COBBLESTONE.defaultBlockState(), 3);
            bot.swing(InteractionHand.MAIN_HAND);
            BotLog.info("道路通道支撑完成: unit={} support={}", unitIndex, unit.support().toShortString());
        }
        // 最终支撑格上方仍是目标方块，不能把 bot 送进目标；从当前缓冲位置交给
        // MineTask 重新选择真实可挖站位，保持原版距离/视线/挖掘耗时约束。
        if (unitIndex + 1 >= units.size()) return startTargetMine();
        phase = Phase.MOVE_ONTO_UNIT;
        return Status.RUNNING;
    }

    /** 使用原版 destroy progress 累积真实挖掘时间，不瞬挖。 */
    private boolean tickBreak(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return true;
        if (state.getDestroyProgress(bot, level, pos) <= 0.0F) {
            failure = "road_tool_cannot_break_" + pos.toShortString();
            return false;
        }
        if (!pos.equals(breaking)) {
            breaking = pos.immutable();
            breakProgress = 0.0F;
            breakElapsed = 0;
            bot.gameMode.handleBlockBreakAction(pos,
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    pos.getY() >= bot.getY() ? net.minecraft.core.Direction.UP : net.minecraft.core.Direction.DOWN,
                    level.getMaxBuildHeight(), -1);
        }
        breakProgress += state.getDestroyProgress(bot, level, pos);
        breakElapsed++;
        level.destroyBlockProgress(bot.getId(), pos, Math.min(9, (int) (breakProgress * 10.0F)));
        bot.swing(InteractionHand.MAIN_HAND);
        if (breakProgress >= 1.0F) {
            level.destroyBlock(pos, true, bot);
            level.destroyBlockProgress(bot.getId(), pos, -1);
            resetBreak();
        } else if (breakElapsed > MINE_TIMEOUT_TICKS) {
            failure = "road_mine_timeout_" + pos.toShortString();
            return false;
        }
        return true;
    }

    private Status tickMoveOntoUnit() {
        BlockPos destination = units.get(unitIndex).support().above();
        if (executor == null) {
            List<BlockPos> path = AStarPathfinder.computePath(level, bot.blockPosition(),
                    new Goal.GoalBlock(destination));
            if (path.isEmpty()) {
                failure = "road_tunnel_exit_no_path_" + destination.toShortString();
                return Status.FAILED;
            }
            executor = new PathExecutor(bot, path);
        }
        PathExecutor.Status status = executor.tick();
        if (status == PathExecutor.Status.FAILED) {
            failure = "road_tunnel_move_failed";
            return Status.FAILED;
        }
        if (status == PathExecutor.Status.DONE) {
            executor = null;
            unitIndex++;
            phase = Phase.MOVE_SURFACE;
        }
        return Status.RUNNING;
    }

    private Status startTargetMine() {
        targetTask = new MineTask(bot, target, scope);
        phase = Phase.MINE_TARGET;
        return Status.RUNNING;
    }

    private Status tickMineTarget() {
        Task.Status status = targetTask.tick();
        if (status == Status.FAILED) {
            failure = "road_target_" + targetTask.failureReason();
            return Status.FAILED;
        }
        if (status == Status.DONE) {
            phase = Phase.DONE;
            return Status.DONE;
        }
        return Status.RUNNING;
    }

    private boolean isSurface(RoadPlan.Unit unit) {
        BlockPos support = unit.support();
        if (level.getBlockState(support).getCollisionShape(level, support).isEmpty()) return false;
        for (RoadPlan.Cell cell : unit.cells()) {
            if (cell.pos().equals(support)) continue;
            BlockPos pos = cell.pos();
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) return false;
            if (!level.getFluidState(pos).isEmpty()) return false;
        }
        return true;
    }

    private void resetBreak() {
        breaking = null;
        breakProgress = 0.0F;
        breakElapsed = 0;
    }
}
