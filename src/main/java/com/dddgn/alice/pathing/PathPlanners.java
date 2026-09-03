package com.dddgn.alice.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * 路径规划器工具类（Phase 3.3）。
 * 
 * <p>提供便捷的静态访问方法，避免每次都 new 规划器实例。
 * 
 * <p>使用示例：
 * <pre>{@code
 * // 使用混合策略（推荐）
 * PathResult result = PathPlanners.plan(bot, start, goal);
 * 
 * // 强制使用 A*
 * PathResult result = PathPlanners.planAStar(bot, start, goal);
 * 
 * // 强制使用局部
 * PathResult result = PathPlanners.planLocal(bot, start, goal);
 * }</pre>
 */
public class PathPlanners {
    
    private static final HybridPathPlanner HYBRID = new HybridPathPlanner();
    private static final AStarPathPlanner ASTAR = new AStarPathPlanner();
    private static final LocalPathPlanner LOCAL = new LocalPathPlanner();
    
    /**
     * 使用混合策略规划路径（推荐）。
     */
    public static PathPlanner.PathResult plan(ServerPlayer bot, BlockPos start, Goal goal) {
        return HYBRID.plan(bot, start, goal);
    }
    
    /**
     * 强制使用 A* 完整搜索。
     */
    public static PathPlanner.PathResult planAStar(ServerPlayer bot, BlockPos start, Goal goal) {
        return ASTAR.plan(bot, start, goal);
    }
    
    /**
     * 强制使用局部避障。
     */
    public static PathPlanner.PathResult planLocal(ServerPlayer bot, BlockPos start, Goal goal) {
        return LOCAL.plan(bot, start, goal);
    }
    
    /**
     * 获取混合规划器实例。
     */
    public static PathPlanner hybrid() {
        return HYBRID;
    }
    
    /**
     * 获取 A* 规划器实例。
     */
    public static PathPlanner astar() {
        return ASTAR;
    }
    
    /**
     * 获取局部规划器实例。
     */
    public static PathPlanner local() {
        return LOCAL;
    }
}
