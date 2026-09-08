package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.session.PathExecutionResult;
import com.dddgn.alice.pathing.core.session.PathSession;
import com.dddgn.alice.pathing.core.session.PathSessionStatus;
import net.minecraft.core.BlockPos;

import java.util.UUID;

/**
 * R4 PathSession 诊断任务：先规划、再按计划逐段执行。
 *
 * <p>用于验证"规划 → 执行会话"闭环：多段执行、分段完成容差（D-027）、
 * 周期健康检查、失败结构化上报。
 */
public final class PathSessionDiagnosticTask implements Task {
    private final BotPlayer bot;
    private final BlockPos goalFoot;
    private final boolean allowWorldModification;
    /** 定向扰动（测试夹具）：在指定 tick 把 bot 平移 (dx,dz) 格，用于验证自愈。 */
    private final int disturbTick;
    private final int disturbDx;
    private final int disturbDz;
    private int ticks;
    private boolean disturbed;
    /** 任务级安全上限：防止会话自愈循环导致任务永不结束。 */
    private static final int MAX_TASK_TICKS = 600;
    private final String sessionId = "r4-session-" + UUID.randomUUID();
    private boolean initialized;
    private PathSession session;
    private String failure = "";


    public PathSessionDiagnosticTask(BotPlayer bot, BlockPos goalFoot) {
        this(bot, goalFoot, false);
    }

    public PathSessionDiagnosticTask(BotPlayer bot, BlockPos goalFoot, boolean allowWorldModification) {
        this(bot, goalFoot, allowWorldModification, 0, 0, 0);
    }

    public PathSessionDiagnosticTask(BotPlayer bot, BlockPos goalFoot, boolean allowWorldModification,
                                     int disturbTick, int disturbDx, int disturbDz) {
        this.bot = bot;
        this.goalFoot = goalFoot.immutable();
        this.allowWorldModification = allowWorldModification;
        this.disturbTick = disturbTick;
        this.disturbDx = disturbDx;
        this.disturbDz = disturbDz;
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(goalFoot);
    }

    @Override
    public Status tick() {
        if (!initialized) {
            initialized = true;
            if (!initialize()) {
                return Status.FAILED;
            }
        }
        if (session == null) {
            return Status.FAILED;
        }
        ticks++;
        if (ticks > MAX_TASK_TICKS) {
            session.cancel();
            failure = "SESSION_TASK_TIMEOUT";
            BotLog.warn("[R4 Session] task_timeout session={} ticks={} actualFoot={}",
                    sessionId, ticks, bot.blockPosition().toShortString());
            return Status.FAILED;
        }
        if (!disturbed && disturbTick > 0 && ticks >= disturbTick) {
            BlockPos from = bot.blockPosition();
            BlockPos to = from.offset(disturbDx, 0, disturbDz);
            // 只在目标位置可站立时扰动（避免把 bot 推进坑里，导致不可恢复）
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
                disturbed = true; // 放弃扰动，避免无限等待
                BotLog.info("[R4 Fixture] disturb_skipped session={} at={} tick={}",
                        sessionId, to.toShortString(), ticks);
            }
        }
        PathSessionStatus status = session.tick();
        if (status == PathSessionStatus.RUNNING) {
            return Status.RUNNING;
        }
        PathExecutionResult result = session.result();
        BotLog.info("[R4 Session] result session={} {} planner={} plannedGoal={}",
                sessionId, result.summary(), "alice.astar.movement.v1", goalFoot.toShortString());
        if (result.completed()) {
            return Status.DONE;
        }
        failure = result.status() + ":" + result.failureCode();
        return Status.FAILED;
    }

    @Override
    public String failureReason() {
        return failure;
    }

    private boolean initialize() {
        BlockPos startFoot = bot.blockPosition().immutable();
        PathRequest base = allowWorldModification
                ? PathRequest.withWorldModification(bot.getUUID().toString(), startFoot, goalFoot)
                : PathRequest.of(bot.getUUID().toString(), startFoot, goalFoot);
        PathRequest request = new PathRequest(base.botId(), startFoot, base.goal(),
                base.allowedMovementTypes(),
                com.dddgn.alice.pathing.core.search.SearchBudget.of(
                        CorePathPlanner.DEFAULT_MAX_NODES, CorePathPlanner.DEFAULT_MAX_MILLIS),
                "r4-session-task");
        PathPlan plan = new CorePathPlanner().plan(bot, bot.serverLevel(), request);
        BotLog.info("[R4 Session] planned session={} {} from={} to={}",
                sessionId, plan.summary(), startFoot.toShortString(), goalFoot.toShortString());
        if (!plan.reached()) {
            failure = "PLAN_" + plan.status();
            BotLog.warn("[R4 Session] plan_failed session={} status={} diagnostics={}",
                    sessionId, plan.status(), plan.diagnostics());
            return false;
        }
        session = new PathSession(bot, bot.serverLevel(), plan, request, sessionId);
        return true;
    }
}
