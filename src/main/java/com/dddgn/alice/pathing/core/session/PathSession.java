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
    /** 段内重同步上限：防止"吸附回同一位置 → 反复重同步"的循环。 */
    public static final int MAX_RESYNCS = 5;

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
    private int resyncs;
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

        // ① 每 tick 合法位置集检查（对照 Baritone PathExecutor:101-124）：
        //    脚位不在本段合法位置集 → 前/后搜索重同步；找不到才走漂移兜底。
        if (!validPositions(movements.get(index)).contains(bot.blockPosition())) {
            if (tryResync()) {
                return status;
            }
            if (driftedOutOfSegment()) {
                execution.cancel();
                execution = null;
                handleFailure("SEGMENT_STALE_START");
                return status;
            }
        }

        // ② 周期健康检查：当前段目标 + 前瞻段（对照 Baritone 的 costVerificationLookahead）
        if (segmentTicks % HEALTH_CHECK_INTERVAL == 0) {
            if (!currentTargetStillValid()) {
                execution.cancel();
                execution = null;
                handleFailure("SEGMENT_TARGET_CHANGED");
                return status;
            }
            if (futureTargetBlocked()) {
                execution.cancel();
                execution = null;
                handleFailure("SEGMENT_FUTURE_BLOCKED");
                return status;
            }
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
                "planner=alice.astar.movement.v1 resyncs=" + resyncs);
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
        // 先处理"目标由本段自己产生"的类型，再做通用通行性检查（顺序很关键）
        if (movement.movementType() == com.dddgn.alice.pathing.core.MovementType.DOWNWARD) {
            // 目标方块由本段破坏产生：只要落点支撑仍在、且该方块仍可破坏（或已空）即有效
            if (!MovementHelper.canWalkOn(level, to)
                    || !MovementHelper.canWalkThrough(level, to.above())) {
                return false;
            }
            return level.getBlockState(to).isAir()
                    || com.dddgn.alice.action.BlockInteraction.breakableExplicit(bot, level, to);
        }
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
     * 段失败路由（D-043 分层）：本段失败不再由会话重规划或吸附，直接按语义上报，
     * 由任务层 `PathRetryRunner` 决定是否重规划。
     */
    private void handleFailure(String code) {
        String failure = code == null ? "MOVEMENT_FAILED" : code;
        // 空中不处理：等落地再判定，避免从下落中的位置产生失真判断
        if (!bot.onGround() && !failure.contains("TIMEOUT") && !failure.contains("CANCELLED")) {
            return;
        }
        mapFailure(failure);
    }

    /**
     * 本段合法位置集（对照 Baritone `Movement.getValidPositions`）。
     * <p>TRAVERSE {from,to}；DIAGONAL 加两个角格；ASCEND 加 from.above()；
     * DESCEND 加 to.above()；BREAK_AND_TRAVERSE 加中间格；PLACE_STEP 加 to.above()。
     */
    private static java.util.Set<BlockPos> validPositions(PlannedMovement movement) {
        BlockPos from = movement.fromFoot();
        BlockPos to = movement.toFoot();
        java.util.Set<BlockPos> set = new java.util.HashSet<>();
        set.add(from);
        set.add(to);
        switch (movement.movementType()) {
            case DIAGONAL -> {
                set.add(new BlockPos(to.getX(), from.getY(), from.getZ()));
                set.add(new BlockPos(from.getX(), from.getY(), to.getZ()));
            }
            case ASCEND -> set.add(from.above());
            case DESCEND, PLACE_STEP_AND_TRAVERSE -> set.add(to.above());
            case BREAK_AND_TRAVERSE -> set.add(new BlockPos(
                    (from.getX() + to.getX()) / 2, from.getY(), (from.getZ() + to.getZ()) / 2));
            default -> {
            }
        }
        return set;
    }

    /**
     * Baritone 式段内重同步（`PathExecutor:101-124`）：脚位不在当前段合法位置集时，
     * 先向前（更早的段）找、再向后（跳 1~2 段）找能容纳当前脚位的段，命中则从该段继续。
     */
    private boolean tryResync() {
        if (resyncs >= MAX_RESYNCS) {
            return false;
        }
        BlockPos feet = bot.blockPosition();
        int target = -1;
        for (int i = 0; i < index; i++) {
            if (validPositions(movements.get(i)).contains(feet)) {
                target = i;
                break;
            }
        }
        if (target < 0) {
            for (int i = index + 3; i < movements.size(); i++) {
                if (validPositions(movements.get(i)).contains(feet)) {
                    target = i - 1;
                    break;
                }
            }
        }
        if (target < 0) {
            return false;
        }
        resyncs++;
        execution = null;
        index = target;
        segmentTicks = 0;
        startSlotTicks = 0;
        settleTicks = 0;
        BotLog.info("[R4 Session] resync session={} feet={} resumeIndex={}",
                sessionId, feet.toShortString(), target);
        return true;
    }

    /** 前瞻段是否已被封死（对照 Baritone `costVerificationLookahead`，此处用可达性代理）。 */
    private boolean futureTargetBlocked() {
        int lookahead = Math.min(movements.size() - 1, index + 3);
        for (int i = index + 1; i <= lookahead; i++) {
            PlannedMovement movement = movements.get(i);
            BlockPos to = movement.toFoot();
            if (!MovementHelper.canWalkThrough(level, to.above())) {
                return true;
            }
            if (movement.movementType() == com.dddgn.alice.pathing.core.MovementType.PLACE_STEP_AND_TRAVERSE) {
                continue;
            }
            if (movement.movementType() == com.dddgn.alice.pathing.core.MovementType.DOWNWARD) {
                // 目标方块由该段破坏产生
                if (!MovementHelper.canWalkOn(level, to)) {
                    return true;
                }
                continue;
            }
            if (!MovementHelper.canWalkThrough(level, to) || !MovementHelper.canWalkOn(level, to)) {
                return true;
            }
        }
        return false;
    }

    /** 规划投影脚位路径（只读，供任务层/夹具观察当前计划）。 */
    public java.util.List<BlockPos> projectedFootPath() {
        return java.util.List.copyOf(projected);
    }

    private void mapFailure(String code) {
        String failure = code == null ? "MOVEMENT_FAILED" : code;
        PathSessionStatus mapped;
        if (failure.contains("STALE_START")) {
            mapped = PathSessionStatus.STALE;
        } else if (failure.contains("BLOCKED")) {
            mapped = PathSessionStatus.BLOCKED;
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
