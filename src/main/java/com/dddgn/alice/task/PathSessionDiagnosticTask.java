package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.session.PathExecutionResult;
import com.dddgn.alice.pathing.core.session.PathSession;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.UUID;

/**
 * R4 PathSession 诊断任务：由任务层驱动「规划 → 逐段执行 → 重试」。
 *
 * <p>D-043 分层：{@code PathSession} 只负责执行与重同步；**重规划决策在任务层**
 * （{@link PathRetryRunner}），因此这里可以观察/控制重试预算。
 *
 * <p>夹具（场景专属，用于验证自愈与重规划）：
 * <ul>
 *   <li>定向扰动：在指定 tick 把 bot 平移 (dx,dz) 格（验证重同步）；</li>
 *   <li>前方封路：在指定 tick 在计划路径前方第 2 段放置一块石头（验证任务层重规划）。</li>
 * </ul>
 */
public final class PathSessionDiagnosticTask implements Task {
    private final BotPlayer bot;
    private final BlockPos goalFoot;
    private final boolean allowWorldModification;
    /** 定向扰动（测试夹具）：在指定 tick 把 bot 平移 (dx,dz) 格。 */
    private final int disturbTick;
    private final int disturbDx;
    private final int disturbDz;
    /** 前方封路（测试夹具）：在指定 tick 在计划路径前方第 2 段放置一块石头。 */
    private final int wallTick;
    /** 任务层重试预算（D-043）。 */
    private final int maxReplans;
    private int ticks;
    private boolean disturbed;
    private boolean walled;
    /** 任务级安全上限：防止会话/重试循环导致任务永不结束。 */
    private static final int MAX_TASK_TICKS = 600;
    private final String sessionId = "r4-session-" + UUID.randomUUID();
    private boolean initialized;
    private PathRetryRunner runner;
    private String failure = "";

    public PathSessionDiagnosticTask(BotPlayer bot, BlockPos goalFoot) {
        this(bot, goalFoot, false);
    }

    public PathSessionDiagnosticTask(BotPlayer bot, BlockPos goalFoot, boolean allowWorldModification) {
        this(bot, goalFoot, allowWorldModification, 0, 0, 0, 0, PathRetryRunner.DEFAULT_MAX_REPLANS);
    }

    public PathSessionDiagnosticTask(BotPlayer bot, BlockPos goalFoot, boolean allowWorldModification,
                                     int disturbTick, int disturbDx, int disturbDz) {
        this(bot, goalFoot, allowWorldModification, disturbTick, disturbDx, disturbDz, 0,
                PathRetryRunner.DEFAULT_MAX_REPLANS);
    }

    public PathSessionDiagnosticTask(BotPlayer bot, BlockPos goalFoot, boolean allowWorldModification,
                                     int disturbTick, int disturbDx, int disturbDz, int wallTick,
                                     int maxReplans) {
        this.bot = bot;
        this.goalFoot = goalFoot.immutable();
        this.allowWorldModification = allowWorldModification;
        this.disturbTick = disturbTick;
        this.disturbDx = disturbDx;
        this.disturbDz = disturbDz;
        this.wallTick = wallTick;
        this.maxReplans = maxReplans;
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(goalFoot);
    }

    @Override
    public Status tick() {
        if (!initialized) {
            initialized = true;
            initialize();
        }
        if (runner == null) {
            return Status.FAILED;
        }
        ticks++;
        if (ticks > MAX_TASK_TICKS) {
            runner.cancel();
            failure = "SESSION_TASK_TIMEOUT";
            BotLog.warn("[R4 Session] task_timeout session={} ticks={} actualFoot={}",
                    sessionId, ticks, bot.blockPosition().toShortString());
            return Status.FAILED;
        }
        if (disturbTick > 0) {
            tickDisturbFixture();
        }
        if (wallTick > 0) {
            tickWallFixture();
        }
        PathRetryRunner.State state = runner.tick();
        if (state == PathRetryRunner.State.RUNNING) {
            return Status.RUNNING;
        }
        PathExecutionResult result = runner.result();
        BotLog.info("[R4 Session] result session={} {} replans={} planner={} plannedGoal={}",
                sessionId, result.summary(), runner.replans(), "alice.astar.movement.v1",
                goalFoot.toShortString());
        if (state == PathRetryRunner.State.DONE) {
            return Status.DONE;
        }
        failure = result.status() + ":" + result.failureCode();
        return Status.FAILED;
    }

    @Override
    public String failureReason() {
        return failure;
    }

    /** 定向扰动夹具：把 bot 平移到相邻可站立格（验证重同步；不会把 bot 推进坑里）。 */
    private void tickDisturbFixture() {
        if (disturbed || ticks < disturbTick) {
            return;
        }
        BlockPos from = MovementHelper.footCell(bot.serverLevel(), bot);
        BlockPos to = from.offset(disturbDx, 0, disturbDz);
        if (com.dddgn.alice.pathing.MovementHelper.canWalkOn(bot.serverLevel(), to)
                && com.dddgn.alice.pathing.MovementHelper.canWalkThrough(bot.serverLevel(), to)
                && com.dddgn.alice.pathing.MovementHelper.canWalkThrough(bot.serverLevel(), to.above())) {
            disturbed = true;
            bot.teleportTo(bot.serverLevel(), to.getX() + 0.5D, to.getY(), to.getZ() + 0.5D,
                    java.util.Set.of(), bot.getYRot(), bot.getXRot());
            bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            BotLog.info("[R4 Fixture] disturbed session={} from={} to={} tick={}",
                    sessionId, from.toShortString(), to.toShortString(), ticks);
        } else if (ticks >= disturbTick + 40) {
            disturbed = true;
            BotLog.info("[R4 Fixture] disturb_skipped session={} at={} tick={}",
                    sessionId, to.toShortString(), ticks);
        }
    }

    /** 前方封路夹具：在计划路径前方第 2 段放置石头（验证任务层重规划）。 */
    private void tickWallFixture() {
        if (walled || ticks < wallTick) {
            return;
        }
        PathSession session = runner.session();
        if (session == null) {
            return;
        }
        List<BlockPos> path = session.projectedFootPath();
        int position = path.indexOf(bot.blockPosition());
        int target = position >= 0 ? position + 2 : -1;
        if (target <= 0 || target >= path.size()) {
            return;
        }
        BlockPos wall = path.get(target);
        if (!bot.serverLevel().getBlockState(wall).isAir()) {
            return;
        }
        bot.serverLevel().setBlock(wall, Blocks.STONE.defaultBlockState(), 3);
        walled = true;
        BotLog.info("[R4 Fixture] wall_placed session={} at={} tick={}",
                sessionId, wall.toShortString(), ticks);
    }

    private void initialize() {
        BlockPos startFoot = MovementHelper.footCell(bot.serverLevel(), bot).immutable();
        PathRequest base = allowWorldModification
                ? PathRequest.withWorldModification(bot.getUUID().toString(), startFoot, goalFoot, "path-session-diagnostic")
                : PathRequest.of(bot.getUUID().toString(), startFoot, goalFoot, "path-session-diagnostic");
        PathRequest request = new PathRequest(base.botId(), startFoot, base.goal(),
                base.allowedMovementTypes(),
                com.dddgn.alice.pathing.core.search.SearchBudget.of(
                        CorePathPlanner.DEFAULT_MAX_NODES, CorePathPlanner.DEFAULT_MAX_MILLIS),
                base.requester());
        runner = new PathRetryRunner(bot, request, maxReplans, sessionId);
        BotLog.info("[R4 Session] start session={} from={} to={} worldMod={} maxReplans={}",
                sessionId, startFoot.toShortString(), goalFoot.toShortString(),
                allowWorldModification, maxReplans);
    }
}
