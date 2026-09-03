package com.dddgn.alice.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * 局部路径规划器（Phase 3.3）。
 * 
 * <p>封装 {@link SurfacePathfinder}，适配 {@link PathPlanner} 接口。
 * 
 * <p>适用场景：
 * - 近距离寻路（< 50 格）
 * - 简单地形（平地、缓坡）
 * - 快速响应（挖矿、探测等实时任务）
 * 
 * <p>特点：
 * - 速度快（局部搜索）
 * - 成本低（只考虑表面路径）
 * - 适合高频调用
 */
public class LocalPathPlanner implements PathPlanner {
    
    @Override
    public PathResult plan(ServerPlayer bot, BlockPos start, Goal goal) {
        // 提取目标位置
        BlockPos goalPos = extractGoalPosition(goal);
        
        // 使用 SurfacePathfinder 的局部避障
        SurfacePathfinder.Result result = SurfacePathfinder.find(bot, start, goalPos);
        
        return new PathResult(
            convertStatus(result.status()),
            result.path(),
            result.expandedNodes(),
            result.totalCost(),
            getName()
        );
    }
    
    private BlockPos extractGoalPosition(Goal goal) {
        if (goal instanceof Goal.GoalBlock gb) {
            return gb.pos();
        } else if (goal instanceof Goal.GoalNear gn) {
            return gn.pos();
        } else {
            throw new IllegalArgumentException("Unsupported goal type: " + goal.getClass());
        }
    }
    
    private PathStatus convertStatus(AStarPathfinder.SearchStatus status) {
        return switch (status) {
            case REACHED -> PathStatus.SUCCESS;
            case UNREACHABLE -> PathStatus.UNREACHABLE;
            case SEARCH_LIMIT -> PathStatus.TIMEOUT;
        };
    }
}
