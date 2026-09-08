package com.dddgn.alice.pathing.core.search;

import com.dddgn.alice.pathing.core.MovementType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;

/**
 * 规划期成本模型（架构文档 §7）。启发式必须与本模型一致且可采纳。
 *
 * <p>当前模型（受限曲面，纯通行）：
 * <ul>
 *   <li>TRAVERSE 1.0（水平 1 格）</li>
 *   <li>DIAGONAL √2（水平对角）</li>
 *   <li>ASCEND 2.0（上升 1 格，含跳跃/爬升代价）</li>
 *   <li>DESCEND 1.0（下降 1 格）</li>
 * </ul>
 * 该模型下 {@code 水平欧氏距离 + |dy|} 是可采纳下界。
 */
public interface CostModel {
    double cost(MovementType type, ServerLevel level, BlockPos from, BlockPos to);

    CostModel TRAVERSAL = (type, level, from, to) -> switch (type) {
        case TRAVERSE -> 1.0D;
        case DIAGONAL -> Math.sqrt(2.0D);
        case ASCEND -> 2.0D;
        case DESCEND -> 1.0D;
        default -> Double.POSITIVE_INFINITY;
    };
}
