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
import com.dddgn.alice.pathing.core.search.CostModel;
import com.dddgn.alice.pathing.core.search.PathRequest;
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
    /** 段间稳定上限：等 bot 落地并基本停住，避免动量把下一段起点带偏。 */
    public static final int MAX_SETTLE_TICKS = 10;
    /** snipsnap 次数上限：防止"吸附到同一索引 → 同段再失败"的无限循环。 */
    public static final int MAX_SNIPSNAPS = 3;

    private final BotPlayer bot;
    private final ServerLevel level;
    private final String sessionId;
    private final PathRequest request;
    private List<PlannedMovement> movements;
    private List<BlockPos> projected;

    private PathSessionStatus status = PathSessionStatus.RUNNING;
    private String failureCode = "";
    private int failureSegment = -1;
    private int index;
    private int segmentTicks;
    /** 段槽位计时：`execution == null`（校验失败/等待落地）期间也计时，避免该路径无超时覆盖（D-042）。 */
    private int startSlotTicks;
    private int settleTicks;
    private int snipsnaps;
    private int lastSnipsnapIndex = -1;
    private int totalTicks;
    private MovementExecution execution;

    public PathSession(BotPlayer bot, ServerLevel level, PathPlan plan, PathRequest request,
                       String sessionId) {
        this.bot = Objects.requireNonNull(bot, "bot");
        this.level = Objects.requireNonNull(level, "level");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.request = Objects.requireNonNull(request, "request");
        this.movements = Objects.requireNonNull(plan, "plan").movements();
        this.projected = plan.projectedFootPath();
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

        // 段间稳定：松输入并等待落地/减速，避免上一段动量把下一段起点带偏（Baritone settle 语义）
        if (settleTicks > 0) {
            settleTicks--;
            bot.controller().stopMovement();
            double horizontalSpeed = bot.getDeltaMovement().horizontalDistance();
            if (settleTicks == 0 || (bot.onGround() && horizontalSpeed < 0.05D)) {
                settleTicks = 0;
            }
            return status;
        }

        if (execution == null) {
            if (index >= movements.size()) {
                status = PathSessionStatus.COMPLETED;
                BotLog.info("[R4 Session] completed session={} segments={} ticks={} finalFoot={}",
                        sessionId, movements.size(), totalTicks, bot.blockPosition().toShortString());
                return status;
            }
            if (++startSlotTicks > segmentTimeoutTicks()) {
                fail(PathSessionStatus.TIMEOUT, "SEGMENT_START_TIMEOUT");
                return status;
            }
            startSegment();
            return status;
        }

        segmentTicks++;
        if (segmentTicks > segmentTimeoutTicks()) {
            execution.cancel();
            fail(PathSessionStatus.TIMEOUT, "SEGMENT_TIMEOUT");
            return status;
        }
        if (segmentTicks % HEALTH_CHECK_INTERVAL == 0 && !currentTargetStillValid()) {
            execution.cancel();
            execution = null;
            handleFailure("SEGMENT_TARGET_CHANGED");
            return status;
        }
        if (driftedOutOfSegment()) {
            execution.cancel();
            execution = null;
            handleFailure("SEGMENT_STALE_START");
            return status;
        }

        execution.tick();
        switch (execution.phase()) {
            case SUCCEEDED -> {
                BotLog.info("[R4 Session] segment_done session={} index={} type={} ticks={} actualFoot={}",
                        sessionId, index, movements.get(index).movementType(), segmentTicks,
                        bot.blockPosition().toShortString());
                index++;
                execution = null;
                segmentTicks = 0;
                startSlotTicks = 0;
                settleTicks = MAX_SETTLE_TICKS;
            }
            case FAILED, CANCELLED -> handleFailure(execution.failureCode());
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
                "planner=alice.astar.movement.v1 snipsnaps=" + snipsnaps);
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
            handleFailure(validation.failureCode());
            return;
        }
        execution = factory.create(spec, context);
        segmentTicks = 0;
        startSlotTicks = 0;
        BotLog.info("[R4 Session] segment_start session={} index={}/{} type={} from={} to={} tolerance={} actualFoot={}",
                sessionId, index, movements.size(), movement.movementType(),
                movement.fromFoot().toShortString(), movement.toFoot().toShortString(), tolerance,
                bot.blockPosition().toShortString());
    }

    /**
     * 本段超时上限：按规划成本动态放宽（成本单位 = 走路 1 格 ≈ 6 tick，见 {@link CostModel}），
     * 系数 20 是**安全余量**（不是单位换算），至少 {@link #MAX_TICKS_PER_SEGMENT}。
     */
    private int segmentTimeoutTicks() {
        double plannedCost = movements.get(index).cost();
        return Math.max(MAX_TICKS_PER_SEGMENT, (int) Math.ceil(plannedCost * 20.0D) + 100);
    }

    /**
     * 当前段目标是否仍然有效（世界变化检测）。
     *
     * <p>按 Movement 类型区分：普通移动要求目标"现在就可站"；
     * `PLACE_STEP_AND_TRAVERSE` 的目标支撑**由本段放置产生**，
     * 因此只要求空间可通行 + 放置位仍可放置（或支撑已就位）。
     */
    private boolean currentTargetStillValid() {
        PlannedMovement movement = movements.get(index);
        BlockPos to = movement.toFoot();
        if (!MovementHelper.canWalkThrough(level, to)
                || !MovementHelper.canWalkThrough(level, to.above())) {
            return false;
        }
        if (movement.movementType() == com.dddgn.alice.pathing.core.MovementType.PLACE_STEP_AND_TRAVERSE) {
            BlockPos placePos = to.below();
            return MovementHelper.canWalkThrough(level, placePos) || MovementHelper.canWalkOn(level, to);
        }
        return MovementHelper.canWalkOn(level, to);
    }

    /**
     * 漂移检测（对照 Baritone `PathExecutor#playerInValidPosition`）：
     * 本段执行期间 bot 被外力/掉落带出本段包络 —— 脚位低于本段两端最低脚位 1 格以上，
     * 或横向同时离开两个端点 3 格以上 —— 立即判定 STALE_START 交给自愈闭环，
     * 而不是让执行器对着不可达目标空跳直到超时。
     *
     * <p>只在落地后判定：空中位置不稳定，且重规划起点会失真。
     */
    private boolean driftedOutOfSegment() {
        if (!bot.onGround()) {
            return false;
        }
        PlannedMovement movement = movements.get(index);
        BlockPos feet = bot.blockPosition();
        BlockPos from = movement.fromFoot();
        BlockPos to = movement.toFoot();
        if (feet.getY() < Math.min(from.getY(), to.getY()) - 1) {
            return true;
        }
        return feet.distManhattan(from) > 3 && feet.distManhattan(to) > 3;
    }

    /**
     * 段失败路由（D-043 分层）：
     * 1) 位置漂移/世界变化 → 先尝试 snipsnap（对照 Baritone `snipsnapifpossible:324-343`）；
     * 2) 否则按原语义终止并上报事实——**重规划决策属于任务层**（`PathRetryRunner`），
     *    对照 Baritone `PathExecutor` 只 `cancel()`、由 `PathingBehavior` 重新规划。
     */
    private void handleFailure(String code) {
        String failure = code == null ? "MOVEMENT_FAILED" : code;
        // 空中不处理：等落地再吸附，避免从下落中的位置产生失真判断
        if (!bot.onGround() && !failure.contains("TIMEOUT") && !failure.contains("CANCELLED")) {
            return;
        }
        if (trySnipsnap(failure)) {
            return;
        }
        mapFailure(failure);
    }

    /** 规划投影脚位路径（只读，供任务层/夹具观察当前计划）。 */
    public java.util.List<BlockPos> projectedFootPath() {
        return java.util.List.copyOf(projected);
    }

    /**
     * 把当前脚位吸附到计划投影路径上（Baritone snipsnap 语义）：
     * 只在落地时吸附；落在路径上则从该位置继续，落在终点则视为完成。
     */
    private boolean trySnipsnap(String code) {
        if (!code.contains("STALE_START") && !code.contains("TARGET_CHANGED")) {
            return false;
        }
        if (!bot.onGround()) {
            return false;
        }
        if (snipsnaps >= MAX_SNIPSNAPS) {
            return false;
        }
        BlockPos feet = bot.blockPosition();
        int position = projected.indexOf(feet);
        if (position < 0) {
            return false;
        }
        // 防循环：吸附到与上次相同（或当前）索引时不再吸附，交给重规划/失败
        if (position == index || position == lastSnipsnapIndex) {
            return false;
        }
        snipsnaps++;
        lastSnipsnapIndex = position;
        execution = null;
        segmentTicks = 0;
        startSlotTicks = 0;
        settleTicks = 0;
        if (position >= movements.size()) {
            status = PathSessionStatus.COMPLETED;
            BotLog.info("[R4 Session] snipsnap_goal session={} feet={}", sessionId, feet.toShortString());
            return true;
        }
        index = position;
        BotLog.info("[R4 Session] snipsnap session={} feet={} resumeIndex={} code={}",
                sessionId, feet.toShortString(), position, code);
        return true;
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
