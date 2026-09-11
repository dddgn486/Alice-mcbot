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

/**
 * 任务层重试策略（D-043）：把"计划失效后是否重算"的决策从 {@code PathSession} 移到任务层。
 *
 * <p>分层依据（对照 Baritone）：{@code PathExecutor} 遇到失效只 `cancel()` 并上报，
 * 由 `PathingBehavior:154-193` 重新 `findPathInNewThread`。Alice 里对应的决策层是任务层
 * （未来由 LLM 目标层裁决）：`PathSession` 只保留**重同步**（snipsnap），本类负责
 * **重规划**（从当前脚位重新规划）与重试预算。
 *
 * <p>每次重试都从 bot 的**当前脚位**重新规划，因此上一段的部分进展会被保留。
 */
public final class PathRetryRunner {

    public enum State { RUNNING, DONE, FAILED }

    public static final int DEFAULT_MAX_REPLANS = 2;

    private final BotPlayer bot;
    private final PathRequest template;
    private final int maxReplans;
    private final String sessionPrefix;
    private PathSession session;
    private PathExecutionResult last;
    private int replans;
    private final java.util.Set<com.dddgn.alice.pathing.core.MovementType> executedTypes =
            new java.util.LinkedHashSet<>();
    private int attempts;
    /** D-106 Slice B：本次运行是否已因写入预算不足而降级为纯通行（一旦降级，后续尝试都保持）。 */
    private boolean degradedToPureTraversal;

    public PathRetryRunner(BotPlayer bot, PathRequest template, int maxReplans, String sessionPrefix) {
        this.bot = bot;
        this.template = template;
        this.maxReplans = Math.max(0, maxReplans);
        this.sessionPrefix = sessionPrefix;
    }

    /** 推进一 tick；RUNNING 表示还需继续调用。 */
    public State tick() {
        if (session == null) {
            BlockPos feet = bot.blockPosition().immutable();
            PathRequest base = degradedToPureTraversal ? template.pureTraversal() : template;
            PathRequest request = new PathRequest(base.botId(), feet, base.goal(),
                    base.allowedMovementTypes(), base.budget(),
                    base.requester() + ":attempt" + attempts);
            PathPlan plan = new CorePathPlanner().plan(bot, bot.serverLevel(), request);
            if (!plan.reached()) {
                BotLog.warn("[PathRetry] plan_failed attempt={} status={} feet={} goal={}",
                        attempts, plan.status(), feet.toShortString(),
                        template.goal().goalFoot().toShortString());
                last = new PathExecutionResult(PathSessionStatus.MOVEMENT_FAILED, 0, 0,
                        "PLAN_" + plan.status(), -1, feet, 0, "planner=alice.astar.movement.v1");
                return State.FAILED;
            }
            int plannedBreaks = plan.movements().stream()
                    .mapToInt(m -> com.dddgn.alice.pathing.core.search.MovementContext
                            .plannedBreaks(m.movementType()))
                    .sum();
            int plannedPlaces = plan.movements().stream()
                    .mapToInt(m -> com.dddgn.alice.pathing.core.search.MovementContext
                            .plannedPlaces(m.movementType()))
                    .sum();
            BotLog.info("[PathRetry] planned attempt={} status={} movements={} cost={} writes>={}/{}"
                            + " from={} to={}",
                    attempts, plan.status(), plan.movements().size(),
                    String.format(java.util.Locale.ROOT, "%.2f", plan.totalCost()),
                    plannedBreaks, plannedPlaces,
                    feet.toShortString(), base.goal().goalFoot().toShortString());
            // D-106 Slice B：计划本身超出剩余写入额度 → **本次运行降级为纯通行**重规划一次。
            // 判据用下界（每条写边至少 1 次写入）：不会误剪合法路径，但足以拦住"注定执行不完"的计划。
            if (!degradedToPureTraversal
                    && (plannedBreaks > com.dddgn.alice.action.WriteBudget.remainingBreaks(bot)
                    || plannedPlaces > com.dddgn.alice.action.WriteBudget.remainingPlaces(bot))) {
                degradedToPureTraversal = true;
                attempts++;
                BotLog.warn("[PathRetry] plan_write_budget_insufficient attempt={} planWrites>={}/{}"
                                + " remaining={}/{} → 本次运行降级为纯通行重规划",
                        attempts - 1, plannedBreaks, plannedPlaces,
                        com.dddgn.alice.action.WriteBudget.remainingBreaks(bot),
                        com.dddgn.alice.action.WriteBudget.remainingPlaces(bot));
                return State.RUNNING;
            }
            session = new PathSession(bot, bot.serverLevel(), plan, request,
                    sessionPrefix + "-" + attempts);
        }
        PathSessionStatus status = session.tick();
        if (status == PathSessionStatus.RUNNING) {
            return State.RUNNING;
        }
        executedTypes.addAll(session.executedMovementTypes());
        last = session.result();
        session = null;
        if (status == PathSessionStatus.COMPLETED) {
            return State.DONE;
        }
        if (replans < maxReplans && retryable(status)) {
            replans++;
            attempts++;
            BotLog.info("[PathRetry] replan attempt={} replans={} reason={} code={} feet={}",
                    attempts, replans, status, last.failureCode(),
                    last.finalFoot().toShortString());
            return State.RUNNING;
        }
        return State.FAILED;
    }

    /** 可重试的失败：计划失效类（位置漂移/世界变化/超时/前置不满足）。 */
    private static boolean retryable(PathSessionStatus status) {
        return status == PathSessionStatus.STALE
                || status == PathSessionStatus.TIMEOUT
                || status == PathSessionStatus.MOVEMENT_FAILED
                || status == PathSessionStatus.INVALID_PRECONDITION
                || status == PathSessionStatus.BLOCKED;
    }

    public PathExecutionResult result() {
        return last;
    }

    public int replans() {
        return replans;
    }

    /** 本次任务（含重规划）实际执行过的 Movement 类型集合（D-061 覆盖断言用）。 */
    public java.util.Set<com.dddgn.alice.pathing.core.MovementType> executedTypes() {
        return java.util.Set.copyOf(executedTypes);
    }

    /** 当前会话（RUNNING 时非 null；夹具用于观察/干扰当前计划）。 */
    public PathSession session() {
        return session;
    }

    public void cancel() {
        if (session != null) {
            session.cancel();
        }
    }
}
