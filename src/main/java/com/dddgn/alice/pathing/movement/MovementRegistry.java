package com.dddgn.alice.pathing.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Movement 注册表（Phase 3.2）。
 * 
 * <p>管理所有 MovementProvider，负责：
 * - 注册/注销 Movement 提供者
 * - 按优先级排序
 * - 生成所有可能的 Movement
 * 
 * <p>设计模式：注册表模式（Registry Pattern）
 * 
 * @see MovementProvider
 */
public class MovementRegistry {
    
    private final List<MovementProvider> providers = new ArrayList<>();
    private boolean sorted = false;
    
    /**
     * 注册一个 Movement 提供者。
     * 
     * @param provider 提供者
     */
    public void register(MovementProvider provider) {
        providers.add(provider);
        sorted = false;  // 需要重新排序
    }
    
    /**
     * 注销一个 Movement 提供者。
     * 
     * @param provider 提供者
     */
    public void unregister(MovementProvider provider) {
        providers.remove(provider);
    }
    
    /**
     * 清空所有提供者。
     */
    public void clear() {
        providers.clear();
        sorted = false;
    }
    
    /**
     * 生成从 from 出发的所有可能 Movement。
     * 
     * <p>会遍历所有注册的提供者，按优先级排序后生成 Movement。
     * 
     * @param bot Bot 玩家
     * @param from 起点位置
     * @param level 世界
     * @return Movement 列表
     */
    public List<Movement> generateMovements(ServerPlayer bot, BlockPos from, ServerLevel level) {
        // 按优先级排序（只在需要时排序一次）
        if (!sorted) {
            providers.sort(Comparator.comparingInt(MovementProvider::getPriority));
            sorted = true;
        }
        
        List<Movement> movements = new ArrayList<>();
        
        for (MovementProvider provider : providers) {
            try {
                movements.addAll(provider.generateMovements(bot, from, level));
            } catch (Exception e) {
                // 某个提供者失败不应该影响其他提供者
                com.dddgn.alice.log.BotLog.warn("Movement 提供者 {} 失败: {}", 
                        provider.getName(), e.getMessage());
            }
        }
        
        return movements;
    }
    
    /**
     * 获取所有注册的提供者数量。
     * 
     * @return 提供者数量
     */
    public int getProviderCount() {
        return providers.size();
    }
    
    /**
     * 获取所有注册的提供者名称（用于调试）。
     * 
     * @return 提供者名称列表
     */
    public List<String> getProviderNames() {
        return providers.stream()
                .map(MovementProvider::getName)
                .toList();
    }
}
