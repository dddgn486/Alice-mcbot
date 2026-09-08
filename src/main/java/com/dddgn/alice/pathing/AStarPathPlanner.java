package com.dddgn.alice.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * A* 路径规划器（Phase 3.3）。
 * 
 * <p>封装 {@link AStarPathfinder}，适配 {@link PathPlanner} 接口。
 * 
 * <p>适用场景：
 * - 远距离寻路（> 50 格）
 * - 复杂地形（需要挖掘、搭桥）
 * - 精确路径（需要最优解）
 * 
 * <p>特点：
 * - 完整搜索（全局最优）
 * - 成本高（考虑挖掘、搭桥等）
 * - 适合低频调用
 */
public class AStarPathPlanner implements PathPlanner {
    
    @Override
    public PathResult plan(ServerPlayer bot, BlockPos start, Goal goal) {
        // 使用 AStarPathfinder 完整搜索
        AStarPathfinder.SearchResult result = AStarPathfinder.computeDetailed(bot, start, goal);
        
        return new PathResult(
            convertStatus(result.status()),
            result.path(),
            result.expandedNodes(),
            result.totalCost(),
            getName()
        );
    }
    
    private PathStatus convertStatus(AStarPathfinder.SearchStatus status) {
        return switch (status) {
            case REACHED -> PathStatus.SUCCESS;
            case UNREACHABLE -> PathStatus.UNREACHABLE;
            case SEARCH_LIMIT -> PathStatus.TIMEOUT;
        };
    }
}
