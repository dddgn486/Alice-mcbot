package com.dddgn.alice.pathing.core.search;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;

/**
 * Pathing Core 规划服务入口（R3）。
 *
 * <p>同步主线程执行：采集实时世界 → Movement-aware A* → {@link PathPlan}。
 * 本类不含执行逻辑，也不修改世界；执行交给 R4 的 PathSession。
 */
public final class CorePathPlanner {
    public static final int DEFAULT_MAX_NODES = 20_000;
    /**
     * 搜索的**墙钟毫秒上限**（`D-369`，2026-09-20）。
     *
     * <p>⚠️ **这个值直接决定"服务器会不会卡"**：Alice 的路径搜索**跑在服务器 tick 线程上**
     * （`PathRetryRunner.tick → PathSession.tick`，见 2026-09-20 崩服栈），而 tick 预算是 **50 ms**。
     * 原值 `3_000L` = **60 倍 tick 预算** ⇒ 单次搜索可以合法地独占服务器近 3 秒 ——
     * 真机实测的三次 `Can't keep up! … Running 2035 / 2632 / 2232 ms or 40 / 52 / 44 ticks behind`
     * （2026-09-20 21:30:53 / 21:31:11 / 21:31:30）正落在这个上限之下。
     *
     * <p>**为什么是 200 ms**：① 无头电池（CORE，4800+ tick）里全部搜索实测 ≤ 9 ms
     * （`[PathRetry] plan … ms=` 分布：0 ms ×73、1 ms ×15、3–9 ms ×5）⇒ 正常挖掘/短途路径远用不到 200 ms；
     * ② 200 ms = 4 倍 tick 预算 = **卡顿上限可量化**（从 ~2.6 s 降到 ≤0.2 s）；
     * ③ 超预算的诚实结果是既有的 `SEARCH_LIMIT`/`PARTIAL`（`D-076`：`SEARCH_LIMIT ≠ UNREACHABLE`，
     * 有 `PathRetry` 重试），**不是**把"没算完"谎报成"到不了"。
     *
     * <p>⚠️ **内核对照（`D-036`）**：Baritone **把搜索放到独立线程**
     * （`baritone/behavior/PathingBehavior.java:469 findPathInNewThread`，并断言
     * `context.safeForThreadedUse`），另有 `primaryTimeoutMS` / `failureTimeoutMS`。
     * 线程化才是根治（Alice 目前同步在 tick 线程上读实时 `ServerLevel` ⇒ 线程化需要线程安全的世界视图，
     * 属架构级改动）⇒ 本值只是**把"单 tick 卡顿"限制在可接受范围**，线程化仍登记为待办。
     *
     * <p>调紧调松的唯一依据 = `[Search] 超 tick 预算` 日志（超 50 ms 才打一条，给调参留数据）。
     *
     * <p>⭐ **2026-09-22 调紧 `200 → 50`（= 一个 tick 的量级）**：数据到手了（`D-369 §六` 的原话是
     * 「预算是否还能更紧（如 50 ms）：等 `[Search] 超 tick 预算` 日志积累真实数据再定」）。
     * 真机 `latest.log`（2026-09-22 10:49 窗口）：**87 次**撞上限、平均 **189 ms**、合计 **16.4 s**，
     * 其中 30 s 窗口内 33 次 × 196 ms = **6.5 s**（占该窗口 22%）⇒ 每 tick 一次「注定搜不完」的搜索
     * ⇒ 服务端 `Can't keep up! Running 2114ms or 42 ticks behind` ⇒ **8.3 s 内一次方块都没破**，
     * 紧接着 0.6 s 内连完 10 次 `block_break_done`（追补欠 tick ⇒ 用户看到「卡住 + 跳帧/突然连破几块」）。
     * ⇒ 单次搜索**不许再吃掉多个 tick**；搜不完的诚实结局仍是 `SEARCH_LIMIT`/`PARTIAL`（`D-076`）。
     * 代价与回收条件见 `D-388`。
     */
    public static final long DEFAULT_MAX_MILLIS = 50L;

    /** D-250/②′：计划自洽性重搜上限（每次禁掉一条"清空者"边）。 */
    private static final int MAX_SELF_WRITE_RETRIES = 3;

    private final MovementProvider provider;

    public CorePathPlanner() {
        this(new SurfaceMovementProvider());
    }

    public CorePathPlanner(MovementProvider provider) {
        this.provider = provider;
    }

    /** 用默认预算规划一条到目标脚位的路径。 */
    public PathPlan planTo(ServerPlayer bot, ServerLevel level, String botId, BlockPos startFoot,
                           BlockPos goalFoot, String requester) {
        PathRequest request = new PathRequest(botId, startFoot, new GoalFoot(goalFoot),
                PathRequest.of(botId, startFoot, goalFoot, requester).allowedMovementTypes(),
                SearchBudget.of(DEFAULT_MAX_NODES, DEFAULT_MAX_MILLIS), requester);
        return plan(bot, level, request);
    }

    /** 允许世界修改（破坏 + 放置）的规划（R5-2/R5-3 测试用）。 */
    public PathPlan planToWithWorldModification(ServerPlayer bot, ServerLevel level, String botId,
                                                BlockPos startFoot, BlockPos goalFoot, String requester) {
        PathRequest base = PathRequest.withWorldModification(botId, startFoot, goalFoot, requester);
        PathRequest request = new PathRequest(botId, startFoot, base.goal(), base.allowedMovementTypes(),
                SearchBudget.of(DEFAULT_MAX_NODES, DEFAULT_MAX_MILLIS), requester);
        return plan(bot, level, request);
    }

    public PathPlan plan(ServerPlayer bot, ServerLevel level, PathRequest request) {
        // ⭐ **A1（2026-09-21）：每 tick 搜索总账** —— 所有规划入口都经过本方法 ⇒ 一处管住全部。
        // 先过 tick 边界（tick 一换就清零），再判"本 tick 还能不能起新搜索"。
        SearchTickBudget.handleTick(level.getGameTime());
        // D-207 ①：**规划期**写入授权闸门（所有规划入口都经过这里 ⇒ 一处管住全部）。
        // 越权 = **请求本身**的错误（拿到的授权对不上），不是搜索结果 ⇒ 规划期就该拦，别带进执行期
        //（与 RecoverabilityPolicy "规划期抛、不当场降级" 同一条理由）。
        //
        // 这里**必须**把它转成"如实失败的 plan"：任务 tick 没有兜底 try/catch
        //（`BotManager.java:1809` 直接调 `task.tick()`）⇒ 让异常逃逸会打断服务端 tick。
        // 授权违规要"响亮失败 + 可归因"，不是"崩服务器"。
        try {
            com.dddgn.alice.action.WritePolicyMatrix.requireMovementsGranted(level, request);
        } catch (com.dddgn.alice.action.WritePolicyMatrix.Violation violation) {
            com.dddgn.alice.log.BotLog.warn("[WritePolicy] plan_refused code={} requester={} goal={} detail={}",
                    violation.code(), request.requester(), request.goal().goalFoot().toShortString(),
                    violation.getMessage());
            return PathPlan.failure(PlanningStatus.ERROR, request.startFoot(), request.goal().goalFoot(),
                    0, 0, 0L, "alice.write-policy",
                    violation.code() + " " + violation.getMessage());
        }
        // ⭐ **A1 闸门（2026-09-21）**：本 tick 的搜索预算已用尽 ⇒ **不起新搜索**，如实交出 `SEARCH_LIMIT`。
        // 为什么是 `SEARCH_LIMIT` 而不是别的：`D-076` 的纪律 —— "预算不够"**不等于**"到不了"，
        // 两者必须能被上层区分；这里连"可达性未知"都是准确的（根因是**没搜**，不是没路）。
        // 诊断字段带 `tick_search_budget_exhausted` ⇒ 事后可与"搜索空间真穷尽"区分开
        //（这正是 `survey/24 §2.4` 要的"可行性判据 vs 性能判据"的第一块砖）。
        if (!SearchTickBudget.tryAcquire()) {
            return PathPlan.failure(PlanningStatus.SEARCH_LIMIT, request.startFoot(),
                    request.goal().goalFoot(), 0, 0, 0L, "alice.astar.movement.v1",
                    "tick_search_budget_exhausted " + SearchTickBudget.describe()
                            + " requester=" + request.requester());
        }
        MovementContext context = MovementContext.live(bot, level, request);
        AStarMovementSearch search = new AStarMovementSearch(provider);
        java.util.Set<SelfWriteConsistency.EdgeKey> forbiddenEdges = new java.util.LinkedHashSet<>();
        PathPlan plan = searchAndRecord(search, context, forbiddenEdges, request);
        // ================= D-250/②′：计划自我写入自洽性（校验 + 有界重搜） =================
        // 搜完**回放计划自己的写入**，检查有没有"后面的边踩在前面挖掉的格子上"。发现冲突就
        // **禁掉那条"清空者"边**（按具体边禁，不按 Movement 类禁）重搜，最多 K 次。
        //
        // 为什么不在搜索里按路径过滤（D-250 实测）：A* 用位置做节点键，带挖掘前缀的**更便宜**的路会占住
        // 目标节点，干净前缀因更贵永不重挂 ⇒ 一旦按路径拒绝，那个节点上所有后继边全被掐死 ⇒ 实测把
        // 灌水坑逃生从"可解"变成 UNREACHABLE（`own_write_support=245`）。**按具体边禁是路径无关的**。
        for (int attempt = 0; attempt <= MAX_SELF_WRITE_RETRIES; attempt++) {
            if (!plan.reached() && !plan.partial()) {
                // 真失败（UNREACHABLE / 无前缀的 SEARCH_LIMIT / GOAL_NOT_LOADED / …）：没有可执行的边 ⇒ 直接交出去。
                return plan;
            }
            if (plan.movements().isEmpty()) {
                return plan;   // 带状态但没有边（防御）
            }
            // **K-1 收口（2026-09-16）**：`PARTIAL` 前缀**也会被执行**（`PathRetryRunner` 先走前缀再重规划）
            // ⇒ 它必须和整条计划吃**同一份**自洽校验；否则"踩在自己挖掉的格子上"会在前缀里复发，
            // 执行期健康检查当场 BLOCKED/STALE（D-248/D-251 那类）。
            SelfWriteConsistency.Conflict conflict = SelfWriteConsistency.firstConflict(level, plan.movements());
            if (conflict == null) {
                if (attempt > 0) {
                    com.dddgn.alice.log.BotLog.info("[SelfWrite] 重搜 {} 次后计划自洽 goal={} movements={}",
                            attempt, request.goal().goalFoot().toShortString(), plan.movements().size());
                }
                return plan;
            }
            PathingStats.recordTotal("selfwrite_conflict");
            if (!plan.reached()) {
                // **PARTIAL**：不重搜、也不判死 —— 把前缀**裁到冲突之前**（干净的那一段交出去，随后重规划）。
                java.util.List<PlannedMovement> safe =
                        SelfWriteConsistency.safePrefixBefore(plan.movements(), conflict);
                PathingStats.recordTotal("selfwrite_partial_truncated");
                com.dddgn.alice.log.BotLog.warn("[SelfWrite] 前缀不自洽：{} 清掉 {}，后面的 {} 要踩在它上面"
                                + " ⇒ 前缀裁到 {} 步（保留可安全执行的那段，随后重规划；D-250/D-251 同类）",
                        conflict.clearer().type(), conflict.supportCell().toShortString(),
                        conflict.violatingType(), safe.size());
                if (safe.isEmpty()) {
                    return PathPlan.failure(PlanningStatus.SEARCH_LIMIT, plan.startFoot(), plan.goalFoot(),
                            plan.nodesExpanded(), plan.movementsConsidered(), plan.elapsedMillis(),
                            plan.plannerName(), plan.diagnostics() + " partial_prefix_unsafe");
                }
                java.util.List<BlockPos> projected = new java.util.ArrayList<>();
                projected.add(plan.startFoot());
                double cost = 0.0D;
                for (PlannedMovement movement : safe) {
                    projected.add(movement.toFoot());
                    cost += movement.cost();
                }
                return PathPlan.partial(plan.startFoot(), plan.goalFoot(), safe, projected, cost,
                        plan.nodesExpanded(), plan.movementsConsidered(), plan.elapsedMillis(),
                        plan.plannerName(), plan.diagnostics() + " truncated=" + safe.size());
            }
            if (attempt == MAX_SELF_WRITE_RETRIES) {
                break;   // 次数用完：下面按"仍不自洽"交出去并计数
            }
            com.dddgn.alice.log.BotLog.warn("[SelfWrite] 计划不自洽（第 {} 次）：{} 清掉 {}，"
                            + "后面的 {} 却要踩在它上面 ⇒ 禁掉那条边重搜（D-250/②′）",
                    attempt + 1, conflict.clearer().type(), conflict.supportCell().toShortString(),
                    conflict.violatingType());
            forbiddenEdges.add(conflict.clearer());
            PathPlan retry = searchAndRecord(search, context, forbiddenEdges, request);
            if (!retry.reached()) {
                // 禁掉之后搜不到（含真 UNREACHABLE）：**如实交出去**。原计划已被证明不可执行
                // （执行期健康检查会当场判 BLOCKED，重规划又会重算出同一形状），不能拿它冒充 REACHED。
                PathingStats.recordTotal("selfwrite_unresolved");
                return retry;
            }
            plan = retry;
        }
        PathingStats.recordTotal("selfwrite_unresolved");
        com.dddgn.alice.log.BotLog.warn("[SelfWrite] {} 次重搜后仍不自洽 ⇒ 交出当前计划"
                + "（执行期 `futureTargetBlocked` 仍是兜底）goal={}",
                MAX_SELF_WRITE_RETRIES, request.goal().goalFoot().toShortString());
        return plan;
    }

    /**
     * ⭐ **A1 的唯一搜索出口**：跑一次搜索 → 记账 → 输出单次规划摘要（D-044）。
     *
     * <p>为什么必须收成一个出口：`plan()` 里有两处搜索（首次 + `D-250/②′` 的自洽性重搜）。
     * 只要有一处漏记，{@link SearchTickBudget} 的账就会**偏小**，闸门就会在"实际已经超预算"时放行
     * —— 那正是本轮要修的病灶（"每个消费者都以为自己在预算内"）在代码层的复发。
     * ⇒ 新增加搜索调用点时**必须**走本方法（`tools/kernel-predicates.py` 有对应断言）。
     */
    private static PathPlan searchAndRecord(AStarMovementSearch search, MovementContext context,
                                            java.util.Set<SelfWriteConsistency.EdgeKey> forbiddenEdges,
                                            PathRequest request) {
        PathPlan plan = search.search(context, forbiddenEdges);
        SearchTickBudget.recordMillis(plan.elapsedMillis());
        return reportStats(plan, request);
    }

    /** 每次搜索后输出单次规划摘要（D-044）。 */
    private static PathPlan reportStats(PathPlan plan, PathRequest request) {
        String stats = PathingStats.snapshotAndReset();
        if (!stats.isEmpty()) {
            com.dddgn.alice.log.BotLog.info("[PathingStats] {} status={} goal={}",
                    stats, plan.status(), request.goal().goalFoot().toShortString());
        }
        return plan;
    }
}
