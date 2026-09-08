package com.dddgn.alice.pathing.core.search;

import net.minecraft.core.BlockPos;
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
    public PathPlan planTo(ServerLevel level, String botId, BlockPos startFoot, BlockPos goalFoot,
                           String requester) {
        PathRequest request = new PathRequest(botId, startFoot, new GoalFoot(goalFoot),
                PathRequest.of(botId, startFoot, goalFoot).allowedMovementTypes(),
                SearchBudget.of(DEFAULT_MAX_NODES, DEFAULT_MAX_MILLIS), requester);
        return plan(level, request);
    }

    public PathPlan plan(ServerLevel level, PathRequest request) {
        MovementContext context = MovementContext.live(level, request);
        return new AStarMovementSearch(provider).search(context);
    }
}
