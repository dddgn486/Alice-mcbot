package com.dddgn.alice.pathing.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Movement 提供者接口（Phase 3.2）。
 * 
 * <p>参考 Baritone 的 IMovement 设计，将 Movement 生成从静态方法改为接口化：
 * - 解耦：添加新 Movement 不需要修改 MovementHelper
 * - 灵活：可以动态注册/注销 Movement 提供者
 * - 可配置：不同模式启用不同 Movement
 * - 可测试：每个 Provider 独立测试
 * 
 * <p>实现者负责枚举从给定位置出发的所有可能 Movement。
 * 
 * @see WalkMovementProvider
 * @see BreakAndWalkMovementProvider
 * @see DescendMovementProvider
 * @see PillarMovementProvider
 */
public interface MovementProvider {
    
    /**
     * 生成从 from 出发的所有可能 Movement。
     * 
     * @param bot Bot 玩家
     * @param from 起点位置
     * @param level 世界
     * @return Movement 列表（只返回有效的 Movement，无效的会被过滤掉）
     */
    List<Movement> generateMovements(ServerPlayer bot, BlockPos from, ServerLevel level);
    
    /**
     * 提供者的名称（用于调试和日志）。
     * 
     * @return 提供者名称
     */
    default String getName() {
        return this.getClass().getSimpleName();
    }
    
    /**
     * 提供者的优先级（数字越小优先级越高）。
     * 
     * <p>用于控制 Movement 枚举顺序：
     * - 0-99: 高优先级（基础移动，如 Walk）
     * - 100-199: 中优先级（破坏性移动，如 BreakAndWalk）
     * - 200+: 低优先级（复杂移动，如 Pillar）
     * 
     * @return 优先级（默认 100）
     */
    default int getPriority() {
        return 100;
    }
}
