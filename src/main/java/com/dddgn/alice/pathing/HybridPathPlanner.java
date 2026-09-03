package com.dddgn.alice.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * 混合路径规划器（Phase 3.3）。
 * 
 * <p>自动选择 {@link AStarPathPlanner} 或 {@link LocalPathPlanner}。
 * 
 * <p>策略：
 * - 距离 < 50 格 → LocalPathPlanner（快速）
 * - 距离 >= 50 格 → AStarPathPlanner（完整）
 * 
 * <p>适用场景：
 * - 不确定距离的任务
 * - 需要自动优化的场景
 * - 通用寻路接口
 */
public class HybridPathPlanner implements PathPlanner {
    
    private static final int DISTANCE_THRESHOLD = 50;
    
    private final AStarPathPlanner astarPlanner = new AStarPathPlanner();
    private final LocalPathPlanner localPlanner = new LocalPathPlanner();
    
    @Override
    public PathResult plan(ServerPlayer bot, BlockPos start, Goal goal) {
        // 根据距离选择策略
        double distance = estimateDistance(start, goal);
        
        if (distance < DISTANCE_THRESHOLD) {
            return localPlanner.plan(bot, start, goal);
        } else {
            return astarPlanner.plan(bot, start, goal);
        }
    }
    
    private double estimateDistance(BlockPos start, Goal goal) {
        // 提取目标位置
        BlockPos goalPos = extractGoalPosition(goal);
        
        // 使用曼哈顿距离
        return start.distManhattan(goalPos);
    }
    
    private BlockPos extractGoalPosition(Goal goal) {
        if (goal instanceof Goal.GoalBlock gb) {
            return gb.pos();
        } else if (goal instanceof Goal.GoalNear gn) {
            return gn.pos();
        } else {
            // 无法提取，默认使用 A* 完整搜索
            return BlockPos.ZERO;
        }
    }
}
