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
    public static final long DEFAULT_MAX_MILLIS = 3_000L;

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
        MovementContext context = MovementContext.live(bot, level, request);
        AStarMovementSearch search = new AStarMovementSearch(provider);
        java.util.Set<SelfWriteConsistency.EdgeKey> forbiddenEdges = new java.util.LinkedHashSet<>();
        PathPlan plan = reportStats(search.search(context, forbiddenEdges), request);
        // ================= D-250/②′：计划自我写入自洽性（校验 + 有界重搜） =================
        // 搜完**回放计划自己的写入**，检查有没有"后面的边踩在前面挖掉的格子上"。发现冲突就
        // **禁掉那条"清空者"边**（按具体边禁，不按 Movement 类禁）重搜，最多 K 次。
        //
        // 为什么不在搜索里按路径过滤（D-250 实测）：A* 用位置做节点键，带挖掘前缀的**更便宜**的路会占住
        // 目标节点，干净前缀因更贵永不重挂 ⇒ 一旦按路径拒绝，那个节点上所有后继边全被掐死 ⇒ 实测把
        // 灌水坑逃生从"可解"变成 UNREACHABLE（`own_write_support=245`）。**按具体边禁是路径无关的**。
        for (int attempt = 0; attempt <= MAX_SELF_WRITE_RETRIES; attempt++) {
            if (!plan.reached()) {
                // 只校验"整条计划"（REACHED）。失败/前缀计划（K-1 PARTIAL）不在本范围：前缀的语义是
                // "先走这段、后面再说"，把它判死反而丢掉已有的进展。
                return plan;
            }
            SelfWriteConsistency.Conflict conflict = SelfWriteConsistency.firstConflict(level, plan.movements());
            if (conflict == null) {
                if (attempt > 0) {
                    com.dddgn.alice.log.BotLog.info("[SelfWrite] 重搜 {} 次后计划自洽 goal={} movements={}",
                            attempt, request.goal().goalFoot().toShortString(), plan.movements().size());
                }
                return plan;
            }
            PathingStats.recordTotal("selfwrite_conflict");
            if (attempt == MAX_SELF_WRITE_RETRIES) {
                break;   // 次数用完：下面按"仍不自洽"交出去并计数
            }
            com.dddgn.alice.log.BotLog.warn("[SelfWrite] 计划不自洽（第 {} 次）：{} 清掉 {}，"
                            + "后面的 {} 却要踩在它上面 ⇒ 禁掉那条边重搜（D-250/②′）",
                    attempt + 1, conflict.clearer().type(), conflict.supportCell().toShortString(),
                    conflict.violatingType());
            forbiddenEdges.add(conflict.clearer());
            PathPlan retry = reportStats(search.search(context, forbiddenEdges), request);
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
