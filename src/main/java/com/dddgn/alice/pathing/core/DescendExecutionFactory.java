package com.dddgn.alice.pathing.core;

import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.core.BlockPos;

/** R2-C Descend 的执行工厂；只接受一级下降 MovementSpec。 */
public final class DescendExecutionFactory implements MovementExecutionFactory {
    public static final String KEY = "alice.descend.v1";

    @Override
    public String factoryKey() {
        return KEY;
    }

    @Override
    public boolean supports(MovementSpec spec) {
        return spec != null
                && spec.movementType() == MovementType.DESCEND
                && KEY.equals(spec.executionFactoryKey());
    }

    @Override
    public ValidationResult validate(MovementSpec spec, LiveExecutionContext context) {
        if (!supports(spec)) return ValidationResult.invalid("DESCEND_UNSUPPORTED_SPEC");
        if (context == null) return ValidationResult.invalid("DESCEND_MISSING_CONTEXT");
        
        BlockPos from = spec.fromFoot();
        BlockPos to = spec.toFoot();
        
        // 验证几何约束：dy=-1, 相邻方块
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        int horizontalDist = Math.abs(dx) + Math.abs(dz);
        if (dy != -1 || horizontalDist != 1) {
            return ValidationResult.invalid("DESCEND_INVALID_GEOMETRY");
        }
        
        if (!context.bot().blockPosition().equals(from)) {
            return ValidationResult.invalid("DESCEND_STALE_START");
        }
        
        // 验证目标方块可通行
        if (!MovementHelper.canWalkThrough(context.level(), to)
                || !MovementHelper.canWalkThrough(context.level(), to.above())
                || !MovementHelper.canWalkOn(context.level(), to)) {
            return ValidationResult.invalid("DESCEND_INVALID_PRECONDITION");
        }
        
        return ValidationResult.accepted();
    }

    @Override
    public MovementExecution create(MovementSpec spec, LiveExecutionContext context) {
        ValidationResult result = validate(spec, context);
        if (!result.valid()) {
            throw new IllegalArgumentException("Cannot create DescendExecution: " + result.failureCode());
        }
        return new DescendExecution(spec, context);
    }
}
