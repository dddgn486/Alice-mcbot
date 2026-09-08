package com.dddgn.alice.pathing.core.search;

import com.dddgn.alice.pathing.core.MovementType;
import com.dddgn.alice.pathing.core.RecoverabilityLevel;
import net.minecraft.core.BlockPos;

import java.util.Objects;

/**
 * 规划期的一条 Movement 边（搜索输出）。
 *
 * <p>比 {@code MovementSpec} 更轻：不含能力/依赖明细。执行期由 PathSession 依据
 * 本记录重建 `MovementSpec` 并交给对应工厂校验（架构文档 §5.2/§5.3）。
 */
public record PlannedMovement(
        MovementType movementType,
        BlockPos fromFoot,
        BlockPos toFoot,
        double cost,
        RecoverabilityLevel recoverability
) {
    public PlannedMovement {
        movementType = Objects.requireNonNull(movementType, "movementType");
        fromFoot = Objects.requireNonNull(fromFoot, "fromFoot").immutable();
        toFoot = Objects.requireNonNull(toFoot, "toFoot").immutable();
        recoverability = Objects.requireNonNull(recoverability, "recoverability");
        if (!Double.isFinite(cost) || cost < 0.0D) {
            throw new IllegalArgumentException("cost must be finite and non-negative");
        }
    }
}
