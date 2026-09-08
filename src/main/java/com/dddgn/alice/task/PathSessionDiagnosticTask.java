package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
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
    private final String sessionId = "r4-session-" + UUID.randomUUID();
    private boolean initialized;
    private PathSession session;
    private String failure = "";


    public PathSessionDiagnosticTask(BotPlayer bot, BlockPos goalFoot) {
        this.bot = bot;
        this.goalFoot = goalFoot.immutable();
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
        PathPlan plan = new CorePathPlanner().planTo(bot.serverLevel(), bot.getUUID().toString(),
                startFoot, goalFoot, "r4-session-task");
        BotLog.info("[R4 Session] planned session={} {} from={} to={}",
                sessionId, plan.summary(), startFoot.toShortString(), goalFoot.toShortString());
        if (!plan.reached()) {
            failure = "PLAN_" + plan.status();
            BotLog.warn("[R4 Session] plan_failed session={} status={} diagnostics={}",
                    sessionId, plan.status(), plan.diagnostics());
            return false;
        }
        session = new PathSession(bot, bot.serverLevel(), plan, sessionId);
        return true;
    }
}
