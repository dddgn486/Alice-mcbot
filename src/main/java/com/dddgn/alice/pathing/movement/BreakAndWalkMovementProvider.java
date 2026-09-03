package com.dddgn.alice.pathing.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * BreakAndWalk Movement 提供者（Phase 3.2）。
 * 
 * <p>枚举挖掘障碍物后行走的 Movement：
 * - 8 个水平方向
 * - 破坏挡路的方块
 * - 然后行走
 */
public class BreakAndWalkMovementProvider implements MovementProvider {
    
    @Override
    public List<Movement> generateMovements(ServerPlayer bot, BlockPos from, ServerLevel level) {
        List<Movement> movements = new ArrayList<>();
        
        // 枚举 8 个水平方向
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                
                BlockPos to = from.offset(dx, 0, dz);
                
                // 尝试挖掘障碍后行走
                Movement breakWalk = BreakAndWalkMovement.create(bot, from, to, level);
                if (breakWalk != null) {
                    movements.add(breakWalk);
                }
            }
        }
        
        return movements;
    }
    
    @Override
    public int getPriority() {
        return 100;  // 中优先级（破坏性移动，成本较高）
    }
}
