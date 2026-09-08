package com.dddgn.alice.pathing.core;

import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.core.BlockPos;

/** R2-C Diagonal 的执行工厂；只接受同高度对角 MovementSpec。 */
public final class DiagonalExecutionFactory implements MovementExecutionFactory {
    public static final String KEY = "alice.diagonal.v1";

    @Override
    public String factoryKey() {
        return KEY;
    }

    @Override
    public boolean supports(MovementSpec spec) {
        return spec != null
                && spec.movementType() == MovementType.DIAGONAL
                && KEY.equals(spec.executionFactoryKey());
    }

    @Override
    public ValidationResult validate(MovementSpec spec, LiveExecutionContext context) {
        if (!supports(spec)) return ValidationResult.invalid("DIAGONAL_UNSUPPORTED_SPEC");
        if (context == null) return ValidationResult.invalid("DIAGONAL_MISSING_CONTEXT");
        
        BlockPos from = spec.fromFoot();
        BlockPos to = spec.toFoot();
        
        // 验证几何约束：dy=0, abs(dx)=1, abs(dz)=1
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        if (dy != 0 || Math.abs(dx) != 1 || Math.abs(dz) != 1) {
            return ValidationResult.invalid("DIAGONAL_INVALID_GEOMETRY");
        }
        
        if (!context.bot().blockPosition().equals(from)) {
            return ValidationResult.invalid("DIAGONAL_STALE_START");
        }
        
        // 验证目标方块可通行
        if (!MovementHelper.canWalkThrough(context.level(), to)
                || !MovementHelper.canWalkThrough(context.level(), to.above())
                || !MovementHelper.canWalkOn(context.level(), to)) {
            return ValidationResult.invalid("DIAGONAL_INVALID_PRECONDITION");
        }
        
        // 验证两侧方块可通行（避免穿墙）
        BlockPos sideX = new BlockPos(to.getX(), from.getY(), from.getZ());
        BlockPos sideZ = new BlockPos(from.getX(), from.getY(), to.getZ());
        if (!MovementHelper.canWalkThrough(context.level(), sideX)
                || !MovementHelper.canWalkThrough(context.level(), sideX.above())
                || !MovementHelper.canWalkThrough(context.level(), sideZ)
                || !MovementHelper.canWalkThrough(context.level(), sideZ.above())) {
            return ValidationResult.invalid("DIAGONAL_SIDE_COLLISION");
        }
        
        return ValidationResult.accepted();
    }

    @Override
    public MovementExecution create(MovementSpec spec, LiveExecutionContext context) {
        ValidationResult result = validate(spec, context);
        if (!result.valid()) {
            throw new IllegalArgumentException("Cannot create DiagonalExecution: " + result.failureCode());
        }
        return new DiagonalExecution(spec, context);
    }
}
