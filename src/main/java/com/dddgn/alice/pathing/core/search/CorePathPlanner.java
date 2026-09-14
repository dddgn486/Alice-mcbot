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
        PathPlan plan = new AStarMovementSearch(provider).search(context);
        String stats = PathingStats.snapshotAndReset();
        if (!stats.isEmpty()) {
            com.dddgn.alice.log.BotLog.info("[PathingStats] {} status={} goal={}",
                    stats, plan.status(), request.goal().goalFoot().toShortString());
        }
        return plan;
    }
}
