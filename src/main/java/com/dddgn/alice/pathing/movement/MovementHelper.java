package com.dddgn.alice.pathing.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Movement 辅助工具类（Phase 3.2 重构）。
 * 
 * <h3>职责</h3>
 * <p>
 * 提供枚举所有可能的 Movement 的方法，供 A* 寻路算法使用。
 * </p>
 * 
 * <h3>Phase 3.2 改进</h3>
 * <p>
 * 不再硬编码 Movement 生成逻辑，改用 {@link MovementRegistry} 注册表模式：
 * - 解耦：添加新 Movement 不需要修改此类
 * - 灵活：可以动态配置启用哪些 Movement
 * - 可测试：每个 Provider 独立测试
 * </p>
 * 
 * @see MovementRegistry
 * @see MovementProvider
 */
public final class MovementHelper {
    
    /**
     * 全局 Movement 注册表。
     * 
     * <p>在 Mod 初始化时注册所有 Movement 提供者。
     */
    private static final MovementRegistry REGISTRY = new MovementRegistry();
    
    static {
        // Phase 3.2: 注册所有 Movement 提供者
        REGISTRY.register(new WalkMovementProvider());           // 优先级 0（最高）
        REGISTRY.register(new DescendMovementProvider());        // 优先级 50
        REGISTRY.register(new BreakAndWalkMovementProvider());   // 优先级 100
        // REGISTRY.register(new PillarMovementProvider());      // 优先级 200（暂未启用）
    }
    
    private MovementHelper() {}
    
    /**
     * 获取全局 Movement 注册表。
     * 
     * <p>用于动态配置（例如：添加/移除 Movement 提供者）。
     * 
     * @return Movement 注册表
     */
    public static MovementRegistry getRegistry() {
        return REGISTRY;
    }
    
    /**
     * 枚举从指定位置出发的所有可能的 Movement。
     * 
     * <p>Phase 3.2：委托给注册表，按优先级生成 Movement。
     * 
     * @param bot ServerPlayer（通常是 BotPlayer）
     * @param from 起点
     * @param level 世界
     * @return 所有可能的 Movement 列表
     */
    public static List<Movement> getPossibleMovements(ServerPlayer bot, BlockPos from, ServerLevel level) {
        return REGISTRY.generateMovements(bot, from, level);
    }
    
    /**
     * 枚举从指定位置到目标方向的所有可能的 Movement。
     * 
     * <p>带方向提示的版本，可以减少搜索空间。
     * 
     * <p>Phase 3.2：暂时委托给无方向版本，未来可以实现方向过滤。
     * 
     * @param bot ServerPlayer（通常是 BotPlayer）
     * @param from 起点
     * @param goal 目标位置（提示方向）
     * @param level 世界
     * @return 朝向目标的 Movement 列表
     */
    public static List<Movement> getPossibleMovementsToward(
            ServerPlayer bot, 
            BlockPos from, 
            BlockPos goal, 
            ServerLevel level) {
        
        // TODO: 实现方向过滤优化
        // 当前简单实现：返回所有 Movement
        return getPossibleMovements(bot, from, level);
    }
}
