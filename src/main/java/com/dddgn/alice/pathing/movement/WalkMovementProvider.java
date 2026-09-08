package com.dddgn.alice.pathing.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Walk Movement 提供者（Phase 3.2）。
 * 
 * <p>枚举平地行走的 Movement：
 * - 8 个水平方向（前后左右 + 4 个对角）
 * - 上 1 格（上台阶）
 * - 下 1 格（下台阶）
 */
public class WalkMovementProvider implements MovementProvider {
    
    @Override
    public List<Movement> generateMovements(ServerPlayer bot, BlockPos from, ServerLevel level) {
        List<Movement> movements = new ArrayList<>();
        
        // 枚举 8 个水平方向
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                
                BlockPos to = from.offset(dx, 0, dz);
                
                // 同一高度
                Movement walk = WalkMovement.create(bot, from, to, level);
                if (walk != null) {
                    movements.add(walk);
                }
                
                // 上 1 格（上台阶）
                Movement walkUp = WalkMovement.create(bot, from, to.above(), level);
                if (walkUp != null) {
                    movements.add(walkUp);
                }
                
                // 下 1 格（下台阶）
                Movement walkDown = WalkMovement.create(bot, from, to.below(), level);
                if (walkDown != null) {
                    movements.add(walkDown);
                }
            }
        }
        
        return movements;
    }
    
    @Override
    public int getPriority() {
        return 0;  // 最高优先级（基础移动）
    }
}
