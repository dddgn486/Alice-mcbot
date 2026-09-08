package com.dddgn.alice.pathing.core.session;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.CompletionTolerance;
import com.dddgn.alice.pathing.core.LiveExecutionContext;
import com.dddgn.alice.pathing.core.MovementExecution;
import com.dddgn.alice.pathing.core.MovementExecutionFactory;
import com.dddgn.alice.pathing.core.MovementSpec;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PlannedMovement;
import com.dddgn.alice.pathing.core.search.PlannedMovementSpecs;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Objects;

/**
 * R4 路径执行会话（对照 Baritone `PathExecutor`）。
 *
 * <p>职责：
 * <ul>
 *   <li>按 {@link PathPlan} 逐段执行 Movement，段间使用 D-026 合法位置集；</li>
 *   <li>分段完成容差（D-027）：中间段 {@code COLUMN}，最终段 {@code EXACT}；</li>
 *   <li>周期性健康检查（世界变化 → {@code BLOCKED}）；</li>
 *   <li>单段超时与取消；</li>
 *   <li>把段失败映射为结构化 {@link PathSessionStatus} 事实，向上层报告（不替任务决策）。</li>
 * </ul>
 */
public final class PathSession {
    public static final int MAX_TICKS_PER_SEGMENT = 100;
    /** 周期性健康检查间隔（tick）；对照 Baritone PathExecutor 的周期性路径检查。 */
    public static final int HEALTH_CHECK_INTERVAL = 5;

    private final BotPlayer bot;
    private final ServerLevel level;
    private final String sessionId;
    private final List<PlannedMovement> movements;

    private PathSessionStatus status = PathSessionStatus.RUNNING;
    private String failureCode = "";
    private int failureSegment = -1;
    private int index;
    private int segmentTicks;
    private int totalTicks;
    private MovementExecution execution;

    public PathSession(BotPlayer bot, ServerLevel level, PathPlan plan, String sessionId) {
        this.bot = Objects.requireNonNull(bot, "bot");
        this.level = Objects.requireNonNull(level, "level");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.movements = Objects.requireNonNull(plan, "plan").movements();
        if (movements.isEmpty()) {
            // 空计划：起点即目标 → 视为已完成；否则计划本身失败，直接上报
            this.status = plan.reached() ? PathSessionStatus.COMPLETED : PathSessionStatus.MOVEMENT_FAILED;
            this.failureCode = plan.reached() ? "" : plan.status().name();
        }
    }

    public PathSessionStatus status() {
        return status;
    }

    public int completedSegments() {
        return index;
    }

    public PathSessionStatus tick() {
        if (status != PathSessionStatus.RUNNING) {
            return status;
        }
        totalTicks++;

        if (execution == null) {
            if (index >= movements.size()) {
                status = PathSessionStatus.COMPLETED;
                BotLog.info("[R4 Session] completed session={} segments={} ticks={} finalFoot={}",
                        sessionId, movements.size(), totalTicks, bot.blockPosition().toShortString());
                return status;
            }
            startSegment();
            return status;
        }

        if (++segmentTicks > MAX_TICKS_PER_SEGMENT) {
            execution.cancel();
            fail(PathSessionStatus.TIMEOUT, "SEGMENT_TIMEOUT");
            return status;
        }
        if (segmentTicks % HEALTH_CHECK_INTERVAL == 0 && !currentTargetStillValid()) {
            execution.cancel();
            fail(PathSessionStatus.BLOCKED, "SEGMENT_TARGET_CHANGED");
            return status;
        }

        execution.tick();
        switch (execution.phase()) {
            case SUCCEEDED -> {
                BotLog.info("[R4 Session] segment_done session={} index={} type={} actualFoot={}",
                        sessionId, index, movements.get(index).movementType(),
                        bot.blockPosition().toShortString());
                index++;
                execution = null;
                segmentTicks = 0;
            }
            case FAILED, CANCELLED -> mapFailure(execution.failureCode());
            default -> {
            }
        }
        return status;
    }

    public void cancel() {
        if (status != PathSessionStatus.RUNNING) {
            return;
        }
        if (execution != null) {
            execution.cancel();
        }
        fail(PathSessionStatus.CANCELLED, "SESSION_CANCELLED");
    }

    public PathExecutionResult result() {
        return new PathExecutionResult(status, movements.size(), index, failureCode, failureSegment,
                bot.blockPosition(), totalTicks,
                "planner=alice.astar.movement.v1");
    }

    private void startSegment() {
        PlannedMovement movement = movements.get(index);
        MovementSpec spec = PlannedMovementSpecs.toSpec(movement,
                List.of("session_segment", "target_support", "target_body_clear", "target_head_clear"));
        boolean finalSegment = index == movements.size() - 1;
        CompletionTolerance tolerance = finalSegment
                ? CompletionTolerance.EXACT
                : CompletionTolerance.COLUMN;
        LiveExecutionContext context = new LiveExecutionContext(bot, level, sessionId, 0L, 0L, tolerance);
        MovementExecutionFactory factory = PlannedMovementSpecs.factoryFor(movement.movementType());
        MovementExecutionFactory.ValidationResult validation = factory.validate(spec, context);
        if (!validation.valid()) {
            String code = validation.failureCode();
            if (code.contains("STALE_START")) {
                fail(PathSessionStatus.STALE, code);
            } else {
                fail(PathSessionStatus.INVALID_PRECONDITION, code);
            }
            return;
        }
        execution = factory.create(spec, context);
        segmentTicks = 0;
        BotLog.info("[R4 Session] segment_start session={} index={}/{} type={} from={} to={} tolerance={} actualFoot={}",
                sessionId, index, movements.size(), movement.movementType(),
                movement.fromFoot().toShortString(), movement.toFoot().toShortString(), tolerance,
                bot.blockPosition().toShortString());
    }

    /** 当前段目标是否仍然可通行/有支撑（世界变化检测）。 */
    private boolean currentTargetStillValid() {
        PlannedMovement movement = movements.get(index);
        BlockPos to = movement.toFoot();
        return MovementHelper.canWalkThrough(level, to)
                && MovementHelper.canWalkThrough(level, to.above())
                && MovementHelper.canWalkOn(level, to);
    }

    private void mapFailure(String code) {
        String failure = code == null ? "MOVEMENT_FAILED" : code;
        PathSessionStatus mapped;
        if (failure.contains("STALE_START")) {
            mapped = PathSessionStatus.STALE;
        } else if (failure.contains("INVALID_PRECONDITION")) {
            mapped = PathSessionStatus.INVALID_PRECONDITION;
        } else if (failure.contains("TIMEOUT")) {
            mapped = PathSessionStatus.TIMEOUT;
        } else if (failure.contains("CANCELLED")) {
            mapped = PathSessionStatus.CANCELLED;
        } else {
            mapped = PathSessionStatus.MOVEMENT_FAILED;
        }
        fail(mapped, failure);
    }

    private void fail(PathSessionStatus mapped, String code) {
        status = mapped;
        failureCode = code;
        failureSegment = index;
        BotLog.warn("[R4 Session] failed session={} status={} code={} index={} actualFoot={}",
                sessionId, mapped, code, index, bot.blockPosition().toShortString());
    }
}
