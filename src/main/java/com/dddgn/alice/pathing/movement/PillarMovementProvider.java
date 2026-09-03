package com.dddgn.alice.pathing.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Pillar Movement 提供者（Phase 3.2）。
 * 
 * <p>枚举搭柱子上升的 Movement：
 * - 垂直向上 1 格
 * - 需要在脚下放置方块
 * 
 * <p>注意：PillarMovement 尚未完全实现，此提供者暂时返回空列表。
 */
public class PillarMovementProvider implements MovementProvider {
    
    @Override
    public List<Movement> generateMovements(ServerPlayer bot, BlockPos from, ServerLevel level) {
        List<Movement> movements = new ArrayList<>();
        
        // Phase 3.2: PillarMovement 尚未启用，返回空列表
        // TODO: 启用后，枚举垂直上升的 Movement
        /*
        BlockPos to = from.above();
        Movement pillar = PillarMovement.create(bot, from, to, level);
        if (pillar != null) {
            movements.add(pillar);
        }
        */
        
        return movements;
    }
    
    @Override
    public int getPriority() {
        return 200;  // 低优先级（复杂移动，成本高）
    }
}
