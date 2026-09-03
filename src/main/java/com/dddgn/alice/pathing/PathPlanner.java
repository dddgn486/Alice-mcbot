package com.dddgn.alice.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * 路径规划器接口（Phase 3.3）。
 * 
 * <p>统一 HARD/SOFT 路径的接口，支持：
 * - AStarPathPlanner: 完整 A* 寻路（远距离、复杂地形）
 * - LocalPathPlanner: 局部避障（近距离、简单地形）
 * - HybridPathPlanner: 自动选择策略
 * 
 * <p>设计目标：
 * - 统一 API：所有 Task 使用相同接口
 * - 策略模式：可以动态选择寻路策略
 * - 可配置：不同场景使用不同规划器
 */
public interface PathPlanner {
    
    /**
     * 规划从 start 到 goal 的路径。
     * 
     * @param bot Bot 玩家
     * @param start 起点
     * @param goal 目标
     * @return 路径结果
     */
    PathResult plan(ServerPlayer bot, BlockPos start, Goal goal);
    
    /**
     * 规划从 start 到 goal 的路径（带配置）。
     * 
     * @param bot Bot 玩家
     * @param start 起点
     * @param goal 目标
     * @param config 规划配置
     * @return 路径结果
     */
    default PathResult plan(ServerPlayer bot, BlockPos start, Goal goal, PlannerConfig config) {
        return plan(bot, start, goal);
    }
    
    /**
     * 获取规划器的名称（用于调试和日志）。
     * 
     * @return 规划器名称
     */
    default String getName() {
        return this.getClass().getSimpleName();
    }
    
    /**
     * 路径规划结果。
     */
    record PathResult(
            PathStatus status,
            List<BlockPos> path,
            int nodesExpanded,
            double totalCost,
            String plannerName
    ) {
        public PathResult {
            path = List.copyOf(path);
        }
        
        public boolean success() {
            return status == PathStatus.SUCCESS;
        }
        
        public boolean isEmpty() {
            return path.isEmpty();
        }
    }
    
    /**
     * 路径状态。
     */
    enum PathStatus {
        SUCCESS,        // 成功找到路径
        UNREACHABLE,    // 目标不可达
        TIMEOUT,        // 超时
        PARTIAL         // 部分路径（未到达目标，但找到了接近的路径）
    }
    
    /**
     * 规划器配置。
     */
    record PlannerConfig(
            long timeoutMs,           // 时间预算（毫秒）
            int maxNodes,             // 最大节点数
            boolean allowPartial      // 是否允许返回部分路径
    ) {
        public static final PlannerConfig DEFAULT = new PlannerConfig(100, 50_000, false);
        public static final PlannerConfig FAST = new PlannerConfig(50, 10_000, false);
        public static final PlannerConfig THOROUGH = new PlannerConfig(500, 100_000, true);
    }
}
