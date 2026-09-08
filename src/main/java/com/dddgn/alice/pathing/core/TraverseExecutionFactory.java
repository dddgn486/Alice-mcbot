package com.dddgn.alice.pathing.core;

import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.core.BlockPos;

/** R2-B Traverse 的执行工厂；只接受纯数据同高度四向 MovementSpec。 */
public final class TraverseExecutionFactory implements MovementExecutionFactory {
    public static final String KEY = "alice.traverse.v1";

    @Override
    public String factoryKey() {
        return KEY;
    }

    @Override
    public boolean supports(MovementSpec spec) {
        return spec != null
                && spec.movementType() == MovementType.TRAVERSE
                && KEY.equals(spec.executionFactoryKey());
    }

    @Override
    public ValidationResult validate(MovementSpec spec, LiveExecutionContext context) {
        if (!supports(spec)) return ValidationResult.invalid("TRAVERSE_UNSUPPORTED_SPEC");
        if (context == null) return ValidationResult.invalid("TRAVERSE_MISSING_CONTEXT");
        BlockPos from = spec.fromFoot();
        BlockPos to = spec.toFoot();
        // D-026 合法位置集：接受 from 或 to（含"已在目标"的幂等情形）。
        // 上一段允许的落点容忍集保证多段链接不会因微小落点偏差 STALE_START 断链。
        BlockPos feet = context.bot().blockPosition();
        if (!feet.equals(from) && !feet.equals(to)) {
            return ValidationResult.invalid("TRAVERSE_STALE_START");
        }
        if (!MovementHelper.canWalkThrough(context.level(), to)
                || !MovementHelper.canWalkThrough(context.level(), to.above())
                || !MovementHelper.canWalkOn(context.level(), to)) {
            return ValidationResult.invalid("TRAVERSE_INVALID_PRECONDITION");
        }
        return ValidationResult.accepted();
    }

    @Override
    public MovementExecution create(MovementSpec spec, LiveExecutionContext context) {
        ValidationResult result = validate(spec, context);
        if (!result.valid()) {
            throw new IllegalArgumentException("Cannot create TraverseExecution: " + result.failureCode());
        }
        return new TraverseExecution(spec, context);
    }
}
