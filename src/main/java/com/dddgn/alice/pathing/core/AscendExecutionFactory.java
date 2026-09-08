package com.dddgn.alice.pathing.core;

import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.core.BlockPos;

/** R2-C Ascend 的执行工厂；只接受一级上升 MovementSpec。 */
public final class AscendExecutionFactory implements MovementExecutionFactory {
    public static final String KEY = "alice.ascend.v1";

    @Override
    public String factoryKey() {
        return KEY;
    }

    @Override
    public boolean supports(MovementSpec spec) {
        return spec != null
                && spec.movementType() == MovementType.ASCEND
                && KEY.equals(spec.executionFactoryKey());
    }

    @Override
    public ValidationResult validate(MovementSpec spec, LiveExecutionContext context) {
        if (!supports(spec)) return ValidationResult.invalid("ASCEND_UNSUPPORTED_SPEC");
        if (context == null) return ValidationResult.invalid("ASCEND_MISSING_CONTEXT");
        
        BlockPos from = spec.fromFoot();
        BlockPos to = spec.toFoot();
        
        // 验证几何约束：dy=+1, 相邻方块
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        int horizontalDist = Math.abs(dx) + Math.abs(dz);
        if (dy != 1 || horizontalDist != 1) {
            return ValidationResult.invalid("ASCEND_INVALID_GEOMETRY");
        }
        
        // D-026 合法位置集：接受 from 或 to（含"已在目标"的幂等情形）
        BlockPos feet = context.bot().blockPosition();
        if (!feet.equals(from) && !feet.equals(to)) {
            return ValidationResult.invalid("ASCEND_STALE_START");
        }
        
        // 验证目标方块可通行
        if (!MovementHelper.canWalkThrough(context.level(), to)
                || !MovementHelper.canWalkThrough(context.level(), to.above())
                || !MovementHelper.canWalkOn(context.level(), to)) {
            return ValidationResult.invalid("ASCEND_INVALID_PRECONDITION");
        }
        
        // 验证起点头部空间（跳跃需要）
        if (!MovementHelper.canWalkThrough(context.level(), from.above(2))) {
            return ValidationResult.invalid("ASCEND_NO_HEADROOM");
        }
        
        return ValidationResult.accepted();
    }

    @Override
    public MovementExecution create(MovementSpec spec, LiveExecutionContext context) {
        ValidationResult result = validate(spec, context);
        if (!result.valid()) {
            throw new IllegalArgumentException("Cannot create AscendExecution: " + result.failureCode());
        }
        return new AscendExecution(spec, context);
    }
}
