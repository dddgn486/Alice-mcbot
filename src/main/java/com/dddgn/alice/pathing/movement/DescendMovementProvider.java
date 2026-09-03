package com.dddgn.alice.pathing.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Descend Movement 提供者（Phase 3.2）。
 * 
 * <p>枚举安全下降的 Movement：
 * - 8 个水平方向
 * - 下降 1 格（保证可回收性）
 */
public class DescendMovementProvider implements MovementProvider {
    
    @Override
    public List<Movement> generateMovements(ServerPlayer bot, BlockPos from, ServerLevel level) {
        List<Movement> movements = new ArrayList<>();
        
        // 枚举 8 个水平方向
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                
                BlockPos to = from.offset(dx, -1, dz);
                
                // 尝试安全下降
                Movement descend = DescendMovement.create(bot, from, to, level);
                if (descend != null) {
                    movements.add(descend);
                }
            }
        }
        
        return movements;
    }
    
    @Override
    public int getPriority() {
        return 50;  // 中高优先级（安全移动，但改变高度）
    }
}
