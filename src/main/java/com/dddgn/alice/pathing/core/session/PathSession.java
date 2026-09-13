package com.dddgn.alice.pathing.core.session;

import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.CompletionTolerance;
import com.dddgn.alice.pathing.core.LiveExecutionContext;
import com.dddgn.alice.pathing.core.MovementExecution;
import com.dddgn.alice.pathing.core.MovementType;
import com.dddgn.alice.pathing.core.search.PathingStats;
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

    /**
     * 零进展快速失败阈值（D-105）：连续 N 次"起跳后落回同一脚位格"即判定本段无进展。
     *
     * <p>对照 Baritone `PathExecutor.java:242-250`：它用"本段 tick 数 > 原始成本估计 +
     * `movementTimeoutTicks`"兜底；Alice 的段超时是 `cost * 20 + 100`，实测一次卡死要
     * 可见原地弹跳 161 tick（约 8 秒）。这里把"跳了又落回原地"这个物理事实提前判出来，
     * 出口仍是重规划（`TIMEOUT`），只是提前约 120 tick，并给出可诊断的失败码。
     */
    public static final int MAX_WASTED_JUMP_LANDINGS = 3;

    private final BotPlayer bot;
    private final ServerLevel level;
    private final String sessionId;
    private final PathRequest request;
    private List<PlannedMovement> movements;
    private List<BlockPos> projected;

    private PathSessionStatus status = PathSessionStatus.RUNNING;
    private final java.util.List<MovementType> executedTypes = new java.util.ArrayList<>();
    private String failureCode = "";
    private int failureSegment = -1;
    private int index;
    private int segmentTicks;
    /** 段槽位计时：`execution == null`（校验失败/等待落地）期间也计时，避免该路径无超时覆盖（D-042）。 */
    private int startSlotTicks;
    private int settleTicks;
    private int resyncs;
    private int totalTicks;
    /** D-174 段卡死诊断基线：段起点时的实体 tick / travel() 累计值（进程内单调，见 BotPlayer）。 */
    private long stallBaseEntityTicks;
    private long stallBaseTravelCalls;
    private MovementExecution execution;
    /** 零进展检测（D-105）：连续"起跳后落回同一脚位格"的次数。 */
    private int wastedJumpLandings;
    /** 最近一次离地时的脚位格；落地时与之相同即视为无效跳跃。 */
    private BlockPos lastTakeoffFoot;
    private boolean wasOnGround = true;

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
                        sessionId, movements.size(), totalTicks, MovementHelper.footCell(level, bot).toShortString());
                logRecoverability();
                return status;
            }
            if (++startSlotTicks > segmentTimeoutTicks()) {
                BotLog.warn("[R4 Session] segment_stall session={} index={} kind=start_timeout {}",
                        sessionId, index, describeStall(index));
                fail(PathSessionStatus.TIMEOUT, "SEGMENT_START_TIMEOUT");
                return status;
            }
            startSegment();
            return status;
        }

        segmentTicks++;
        if (segmentTicks > segmentTimeoutTicks()) {
            BotLog.warn("[R4 Session] segment_stall session={} index={} kind=segment_timeout {}",
                    sessionId, index, describeStall(index));
            execution.cancel();
            fail(PathSessionStatus.TIMEOUT, "SEGMENT_TIMEOUT");
            return status;
        }

        // ⓪ 零进展快速失败（D-105）：起跳后落回同一脚位格 → 无效跳跃计数；连续 N 次即判本段无进展。
        if (detectNoProgress()) {
            return status;
        }

        // ① 每 tick 合法位置集检查（对照 Baritone PathExecutor:101-124）：
        //    脚位不在本段合法位置集 → 前/后搜索重同步；找不到才走漂移兜底。
        if (!validPositions(movements.get(index)).contains(MovementHelper.footCell(level, bot))) {
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

        // 疾跑门控（对照 Baritone PathExecutor:238-241 + shouldSprintNextTick:345-495；忽略饱食度，D-053）
        bot.setSprinting(shouldSprint());

        execution.tick();
        switch (execution.phase()) {
            case SUCCEEDED -> {
                BotLog.info("[R4 Session] segment_done session={} index={} type={} ticks={} actualFoot={}",
                        sessionId, index, movements.get(index).movementType(), segmentTicks,
                        MovementHelper.footCell(level, bot).toShortString());
                executedTypes.add(movements.get(index).movementType());
                index++;
                execution = null;
                segmentTicks = 0;
                startSlotTicks = 0;
                if (index >= movements.size()) {
                    status = PathSessionStatus.COMPLETED;
                    BotLog.info("[R4 Session] completed session={} segments={} ticks={} finalFoot={}",
                            sessionId, movements.size(), totalTicks, MovementHelper.footCell(level, bot).toShortString());
                    logRecoverability();
                    return status;
                }
                if (needsSettle(index - 1, index)) {
                    settleTicks = MAX_SETTLE_TICKS;
                    return status;
                }
                // 条件 settle 的"否"分支（D-052）：共线同类型段**同一 tick 连续推进**——
                // 对照 Baritone PathExecutor:231-236（SUCCESS 后 pathPosition++ 并立即驱动下一段）。
                BotLog.info("[R4 Session] continuous_advance session={} nextIndex={} type={}",
                        sessionId, index, movements.get(index).movementType());
                startSegment();
                if (execution != null) {
                    execution.tick();
                    if (execution.phase() == MovementExecution.Phase.SUCCEEDED) {
                        BotLog.info("[R4 Session] segment_done session={} index={} type={} ticks={} actualFoot={}",
                                sessionId, index, movements.get(index).movementType(), 0,
                                MovementHelper.footCell(level, bot).toShortString());
                        executedTypes.add(movements.get(index).movementType());
                        index++;
                        execution = null;
                        segmentTicks = 0;
                        startSlotTicks = 0;
                    } else if (execution.phase() == MovementExecution.Phase.FAILED
                            || execution.phase() == MovementExecution.Phase.CANCELLED) {
                        handleFailure(execution.failureCode());
                    }
                }
            }
            case FAILED, CANCELLED -> handleFailure(execution.failureCode());
            default -> {
            }
        }
        return status;
    }

    /** K-3：当前执行段是否**可以安全取消**（无执行 ⇒ 安全）。 */
    public boolean safeToCancel() {
        return execution == null || execution.safeToCancel();
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

    /**
     * 已执行段的 Movement 类型序列（只读遥测，D-061 回归覆盖断言用）。
     *
     * <p>按**段成功**顺序追加；重同步回到更早的段会重复记录（覆盖断言只看集合，不受影响）。
     */
    public java.util.List<MovementType> executedMovementTypes() {
        return java.util.List.copyOf(executedTypes);
    }

    public PathExecutionResult result() {
        return new PathExecutionResult(status, movements.size(), index, failureCode, failureSegment,
                MovementHelper.footCell(level, bot), totalTicks,
                "planner=alice.astar.movement.v1 resyncs=" + resyncs);
    }

    /** 基-1 实测：本会话执行过的 Movement 被评估成什么等级、依据是什么（累计统计）。 */
    private void logRecoverability() {
        BotLog.info("[Recover] session={} {}", sessionId,
                com.dddgn.alice.pathing.core.RecoverabilityReport.describe());
    }

    private void startSegment() {
        resetProgressWatch();
        stallBaseEntityTicks = bot.entityTickCount();
        stallBaseTravelCalls = bot.travelInvocationCount();
        PlannedMovement movement = movements.get(index);
        // 走路视线归位（D-088，修 2026-09-10 用户实测的"走位时仰着头"）：
        // 对照 Baritone `behavior/LookBehavior.java:96-125`——它把"看向某处"当作**逐 tick 瞬时**行为
        // （PRE 写入目标旋转，POST 用 `prevRotation` 恢复），所以走路时不会粘住上一次交互的俯仰角。
        // Alice 的 `BlockInteraction.faceTowards` 是**永久写入**（无人复位）：伐木自下而上挖高处原木时
        // 仰视，破坏结束后那段走位就一路仰头（实测：整段换树走位都仰着，走到站位才恢复）。
        // 归零放在段起点是安全的：需要特定视线的 Movement 会自行设置
        // （`FallExecution`/`PillarExecution` 有 `faceTowards`；破坏/放置在动作层 `faceBlock`/`placeAt`）。
        // TODO(D-088-B)：对齐 Baritone 的完整形态 = "交互期临时写入 + 结束恢复 prevRotation"
        // （给 `BlockBreakSession` 加保存/恢复），届时本处归零可撤。
        if (bot.getXRot() != 0.0F) {
            bot.setXRot(0.0F);
        }
        MovementSpec spec = PlannedMovementSpecs.toSpec(movement,
                List.of("session_segment", "target_support", "target_body_clear", "target_head_clear"));
        boolean finalSegment = index == movements.size() - 1;
        CompletionTolerance tolerance = finalSegment
                ? CompletionTolerance.EXACT
                : CompletionTolerance.COLUMN;
        if (finalSegment && !MovementHelper.canStandCentered(level, movement.toFoot())) {
            // K-4 / D-167：最终段要求 EXACT（脚位 + 落地 + 距中心 ≤0.3），而它的目标格
            // 连**规划期可查的世界前提**都不成立 ⇒ 该段只能在运行期撞 EXACT 判定。
            // **只计数、不改行为**，并按最后一条边是不是写入类分档：
            //  · 写入类（BREAK_AND_ENTER / DOWNWARD 等）：目标格"破坏之后才可站"是**设计如此**
            //    （挖掘 ENTER_TARGET 模式的目标格就是矿块本身）⇒ 信息码；
            //  · 纯通行边：理应被 provider 的 `canStandCentered` 挡掉 ⇒ 真异常。
            // **收口（D-167）**：实测一轮完整电池里真异常码为 0 ⇒ 删掉当时的临时告警行，
            // 只留累计计数（`alice:bot_report`）+ 电池 `K4=` 自断言（真异常会让电池判 FAIL）。
            boolean writeEdge = com.dddgn.alice.pathing.core.search.MovementContext
                    .plannedBreaks(movement.movementType()) > 0
                    || com.dddgn.alice.pathing.core.search.MovementContext
                    .plannedPlaces(movement.movementType()) > 0;
            PathingStats.recordTotal(writeEdge
                    ? "final_segment_target_post_write" : "final_segment_target_not_standable");
        }
        // 归因（D-082/G1）：执行期的破坏/放置记账必须带上"谁发起的这次寻路"，
        // 否则内核写入永远是 unknown（原先这里用 5 参兼容构造器，requester 被填成 UNKNOWN）。
        // 执行期复验授权（R2b，闭合 G2）：搜索期确实只会生成被授权的 Movement，
        // 但 **plan 可能比产生它的请求活得更久**（重规划、plan 复用、上层按 plan.mode() 另建请求），
        // 那时执行器就会执行一条会话请求**从未授权**的 Movement —— 即 D-076 禁止的"隐式授权"。
        // 所以每一步执行前都用**会话自己的请求**复验。未授权一律拒绝，而不是默默执行。
        if (!request.allows(movement.movementType())) {
            BotLog.warn("[R4 Session] unauthorized_movement session={} type={} requester={} allowed={}",
                    sessionId, movement.movementType(), request.requester(),
                    request.allowedMovementTypes());
            mapFailure("UNAUTHORIZED_MOVEMENT");
            return;
        }
        // 基-8 / G8：**能力闸门**（执行期复验）—— 让 MovementCapabilities 真的能拦人。
        // 搜索期检查过的事实（保护区/一次性方块/工具/预算）在真正执行前可能已经变了，
        // 与 D-076「plan 可能比产生它的请求活得更久」同一条理由。
        java.util.Optional<String> capabilityDenied = com.dddgn.alice.pathing.core.CapabilityGate.check(
                spec.capabilities(), movement.movementType(), movement.toFoot(), capabilityFacts());
        if (capabilityDenied.isPresent()) {
            BotLog.warn("[R4 Session] capability_gate_denied session={} type={} code={} pos={} caps={}",
                    sessionId, movement.movementType(), capabilityDenied.get(),
                    movement.toFoot().toShortString(),
                    com.dddgn.alice.pathing.core.CapabilityGate.describe(spec.capabilities()));
            mapFailure(capabilityDenied.get());
            return;
        }
        LiveExecutionContext context = new LiveExecutionContext(bot, level, sessionId, 0L, 0L,
                tolerance, request.requester());
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
                MovementHelper.footCell(level, bot).toShortString());
    }

    /**
     * 疾跑门控（D-053，对照 Baritone `shouldSprintNextTick` 的必要部分）。
     *
     * <p>只在**平地直线/对角**段疾跑；且：
     * <ul>
     *   <li>bot 必须在地面、不在水中、没有潜行；</li>
     *   <li>前方 2 段内出现 DESCEND / DOWNWARD / ASCEND / PLACE_STEP / BREAK 时**提前收力**
     *       （Baritone 的 descend safeMode 同理：疾跑冲下台阶会过冲）。</li>
     * </ul>
     * 忽略饱食度（用户 2026-09-09：影响可忽略）。
     */
    private boolean shouldSprint() {
        MovementType type = movements.get(index).movementType();
        if (type != MovementType.TRAVERSE && type != MovementType.DIAGONAL) {
            return false;
        }
        if (!bot.onGround() || bot.isInWater() || bot.isShiftKeyDown()) {
            return false;
        }
        int lookahead = Math.min(movements.size() - 1, index + 2);
        for (int i = index + 1; i <= lookahead; i++) {
            MovementType next = movements.get(i).movementType();
            if (next == MovementType.DESCEND || next == MovementType.DOWNWARD
                    || next == MovementType.PILLAR || next == MovementType.ASCEND
                    || next == MovementType.FALL
                    || next == MovementType.PLACE_STEP_AND_TRAVERSE
                    || next == MovementType.BREAK_AND_TRAVERSE
                    || next == MovementType.BREAK_AND_ENTER) {
                return false;
            }
        }
        return true;
    }

    /**
     * 段间是否需要"稳定"（条件 settle，D-052）。
     *
     * <p>需要 settle：① bot 在空中（等落地）；② **刚结束的段**是精确落点类型
     * （DESCEND / DOWNWARD / PLACE_STEP_AND_TRAVERSE / BREAK_AND_TRAVERSE）——它的残余动量
     * 会把下一段起点带偏（2026-09-09 实证：DESCEND 后连续推进滑过目标一格）；
     * ③ **下一段**是精确落点类型（起段前需要对准/落地）。
     * 其余（共线同层的 TRAVERSE / DIAGONAL / ASCEND）保持动量连续推进。
     */
    private boolean needsSettle(int finishedIndex, int nextIndex) {
        if (!bot.onGround()) {
            return true;
        }
        return isPrecisionType(movements.get(finishedIndex).movementType())
                || isPrecisionType(movements.get(nextIndex).movementType());
    }

    private static boolean isPrecisionType(com.dddgn.alice.pathing.core.MovementType type) {
        return switch (type) {
            case DESCEND, DOWNWARD, PILLAR, FALL, PLACE_STEP_AND_TRAVERSE,
                    BREAK_AND_TRAVERSE, BREAK_AND_ENTER -> true;
            default -> false;
        };
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
                    || com.dddgn.alice.action.BlockInteraction.breakable(bot, level, to,
                            WriteGrant.of(request.requester(), WriteReason.DESCEND_FOOT));
        }
        if (movement.movementType() == com.dddgn.alice.pathing.core.MovementType.BREAK_AND_ENTER) {
            // 目的地格由本段破坏产生：支撑仍在 + 目的地列仍可破坏（或已空）即有效
            if (!MovementHelper.canWalkOn(level, to)) {
                return false;
            }
            if (!MovementHelper.canWalkThrough(level, to)
                    && !com.dddgn.alice.action.BlockInteraction.breakable(bot, level, to,
                            WriteGrant.of(request.requester(), WriteReason.PATH_ACCESS))) {
                return false;
            }
            return MovementHelper.canWalkThrough(level, to.above())
                    || com.dddgn.alice.action.BlockInteraction.breakable(bot, level, to.above(),
                            WriteGrant.of(request.requester(), WriteReason.PATH_ACCESS));
        }
        if (!MovementHelper.canWalkThrough(level, to)
                || !MovementHelper.canWalkThrough(level, to.above())) {
            return false;
        }
        if (movement.movementType() == com.dddgn.alice.pathing.core.MovementType.PLACE_STEP_AND_TRAVERSE
                || movement.movementType() == com.dddgn.alice.pathing.core.MovementType.PILLAR) {
            BlockPos placePos = to.below();
            return MovementHelper.canWalkThrough(level, placePos)
                    || MovementHelper.canWalkOn(level, to)
                    || com.dddgn.alice.action.BlockInteraction.findPlaceableSlot(bot) >= 0;
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
        BlockPos feet = MovementHelper.footCell(level, bot);
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
     * DESCEND 加 to.above()；BREAK_AND_TRAVERSE 加中间格；PLACE_STEP 加 to.above()；FALL 加整条下落列。
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
            case FALL -> {
                // 对照 Baritone MovementFall.calculateValidPositions：src + 整条下落列
                for (int y = from.getY(); y >= to.getY(); y--) {
                    set.add(new BlockPos(to.getX(), y, to.getZ()));
                }
            }
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
        BlockPos feet = MovementHelper.footCell(level, bot);
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
        resetProgressWatch();
        BotLog.info("[R4 Session] resync session={} feet={} resumeIndex={}",
                sessionId, feet.toShortString(), target);
        return true;
    }

    /**
     * 零进展快速失败（D-105）：把"跳了又落回同一脚位格"这一物理事实提前判出来。
     *
     * <p>判据只依赖 `onGround` 的**离地→落地**翻转与脚位格（{@link MovementHelper#footCell}）：
     * <ul>
     *   <li>离地时记录起飞脚位格；若与上次起飞格不同，说明确实移动过 → 计数清零；</li>
     *   <li>落地脚位格 == 起飞脚位格 → 无效跳跃 +1；换了一格则清零；</li>
     *   <li>连续 {@link #MAX_WASTED_JUMP_LANDINGS} 次 → 本段判 `TIMEOUT / SEGMENT_NO_PROGRESS`。</li>
     * </ul>
     * 不涉及跳跃的卡死（比如贴着墙走不动）不受影响，仍由段超时兜底。
     */
    private boolean detectNoProgress() {
        boolean onGround = bot.onGround();
        BlockPos foot = MovementHelper.footCell(level, bot);
        if (wasOnGround && !onGround) {
            if (lastTakeoffFoot == null || !lastTakeoffFoot.equals(foot)) {
                wastedJumpLandings = 0;   // 换了一格再起跳 → 有进展
            }
            lastTakeoffFoot = foot;
        } else if (!wasOnGround && onGround) {
            if (lastTakeoffFoot != null && lastTakeoffFoot.equals(foot)) {
                wastedJumpLandings++;
                if (wastedJumpLandings >= MAX_WASTED_JUMP_LANDINGS) {
                    BotLog.warn("[R4 Session] no_progress session={} index={} type={} foot={}"
                                    + " wastedJumpLandings={} (起跳后落回同一脚位格)",
                            sessionId, index, movements.get(index).movementType(),
                            foot.toShortString(), wastedJumpLandings);
                    execution.cancel();
                    fail(PathSessionStatus.TIMEOUT, "SEGMENT_NO_PROGRESS");
                    return true;
                }
            } else {
                wastedJumpLandings = 0;
            }
        }
        wasOnGround = onGround;
        return false;
    }

    /** 段切换/重同步后清零零进展计数（换段即重新计时）。 */
    private void resetProgressWatch() {
        wastedJumpLandings = 0;
        lastTakeoffFoot = null;
        wasOnGround = bot.onGround();
    }

    /** 前瞻段是否已被封死（对照 Baritone `costVerificationLookahead`，此处用可达性代理）。 */
    private boolean futureTargetBlocked() {
        int lookahead = Math.min(movements.size() - 1, index + 3);
        for (int i = index + 1; i <= lookahead; i++) {
            PlannedMovement movement = movements.get(i);
            BlockPos to = movement.toFoot();
            if (movement.movementType() == com.dddgn.alice.pathing.core.MovementType.BREAK_AND_ENTER) {
                // 目的地格由该段破坏产生，只要求落点支撑仍在
                if (!MovementHelper.canWalkOn(level, to)) {
                    return true;
                }
                continue;
            }
            if (!MovementHelper.canWalkThrough(level, to.above())) {
                return true;
            }
            if (movement.movementType() == com.dddgn.alice.pathing.core.MovementType.PLACE_STEP_AND_TRAVERSE
                    || movement.movementType() == com.dddgn.alice.pathing.core.MovementType.PILLAR) {
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

    /** 能力闸门需要的世界事实（在这里落地，纯逻辑在 `CapabilityGate`；便于自检喂假事实）。 */
    private com.dddgn.alice.pathing.core.CapabilityGate.Facts capabilityFacts() {
        return new com.dddgn.alice.pathing.core.CapabilityGate.Facts() {
            @Override
            public String protectionReason(BlockPos pos) {
                return com.dddgn.alice.protection.SafeZoneData.get(bot.getServer())
                        .protectionReason((net.minecraft.server.level.ServerLevel) level, pos);
            }

            @Override
            public int throwawayBlocks() {
                return com.dddgn.alice.action.BlockInteraction.countThrowaway(bot);
            }

            @Override
            public boolean hasWriteBudget(boolean breaking) {
                return com.dddgn.alice.action.WriteBudget.plannedWritesAllowed(bot,
                        breaking ? 1 : 0, breaking ? 0 : 1);
            }

            @Override
            public boolean hasRequiredTool(MovementType type) {
                // 破坏类需要手上有挖掘工具（快捷栏没有 = 生产路径选不到 ⇒ 白挖）
                return com.dddgn.alice.bot.ToolSupply.bestInHotbar(bot,
                        com.dddgn.alice.bot.ToolSupply.Kind.PICKAXE).slot() >= 0
                        || com.dddgn.alice.bot.ToolSupply.bestInHotbar(bot,
                        com.dddgn.alice.bot.ToolSupply.Kind.AXE).slot() >= 0;
            }

            @Override
            public boolean pureTraversalRequest() {
                return com.dddgn.alice.pathing.core.CapabilityGate.PURE_TRAVERSAL_TYPES
                        .containsAll(request.allowedMovementTypes());
            }
        };
    }

    /**
     * 段卡死时的**现场事实**（2026-09-13 实测补的日志盲区）。
     *
     * <p>起因：`alice:transfer_check` 的 `end_to_end` 曾出现"1 格 TRAVERSE 跑满整段预算、
     * `actualFoot` 与起点一模一样"，而日志里**只有一行 segment_start 和一行失败** ——
     * 无法区分"输入没生效 / 被什么按住 / 目的地被堵 / bot 实体没在 tick"。
     * 这里把区分这些问题所需的客观量一次性打出来（只在**失败终态**打，不是每 tick 探针）：
     * 控制器输入、真实坐标与速度、`onGround`、目的地/头位/支撑方块、段耗时。
     */
    private String describeStall(int index) {
        BlockPos to = movements.get(index).toFoot();
        net.minecraft.world.phys.Vec3 delta = bot.getDeltaMovement();
        return String.format(java.util.Locale.ROOT,
                "to=%s botFoot=%s pos=%.3f,%.3f,%.3f onGround=%s delta=%.4f,%.4f,%.4f "
                        + "input=%s toBlock=%s headBlock=%s supportBlock=%s segmentTicks=%d totalTicks=%d"
                        + " entityTicksInSegment=%d travelCallsInSegment=%d",
                to.toShortString(),
                MovementHelper.footCell(level, bot).toShortString(),
                bot.getX(), bot.getY(), bot.getZ(),
                bot.onGround(), delta.x, delta.y, delta.z,
                bot.controller().getInputStateString(),
                blockName(to), blockName(to.above()), blockName(to.below()),
                segmentTicks, totalTicks,
                bot.entityTickCount() - stallBaseEntityTicks,
                bot.travelInvocationCount() - stallBaseTravelCalls);
    }

    private String blockName(BlockPos pos) {
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
        String name = state.getBlock().getName().getString();
        return state.getFluidState().isEmpty() ? name : name + "(fluid)";
    }

    private void mapFailure(String code) {        String failure = code == null ? "MOVEMENT_FAILED" : code;
        // K-5：归属规则只有一处定义（`PathSessionStatus.classify`），自检可断言它不产生死值。
        PathSessionStatus mapped = PathSessionStatus.classify(failure);
        fail(mapped, failure);
    }

    private void fail(PathSessionStatus mapped, String code) {
        status = mapped;
        failureCode = code;
        failureSegment = index;
        BotLog.warn("[R4 Session] failed session={} status={} code={} index={} actualFoot={}",
                sessionId, mapped, code, index, MovementHelper.footCell(level, bot).toShortString());
    }
}
