package com.dddgn.alice.pathing.core;

import com.dddgn.alice.action.BlockInteraction;
import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.core.BlockPos;

/** R5-2 BreakAndTraverse 执行工厂；只接受"同层相邻 + 阻挡可破坏"的 MovementSpec。 */
public final class BreakAndTraverseExecutionFactory implements MovementExecutionFactory {
    public static final String KEY = "alice.break_and_traverse.v1";

    @Override
    public String factoryKey() {
        return KEY;
    }

    @Override
    public boolean supports(MovementSpec spec) {
        return spec != null
                && spec.movementType() == MovementType.BREAK_AND_TRAVERSE
                && KEY.equals(spec.executionFactoryKey());
    }

    @Override
    public ValidationResult validate(MovementSpec spec, LiveExecutionContext context) {
        if (!supports(spec)) return ValidationResult.invalid("BREAK_AND_TRAVERSE_UNSUPPORTED_SPEC");
        if (context == null) return ValidationResult.invalid("BREAK_AND_TRAVERSE_MISSING_CONTEXT");

        BlockPos from = spec.fromFoot();
        BlockPos to = spec.toFoot();
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        boolean straightTwo = (Math.abs(dx) == 2 && dz == 0) || (Math.abs(dz) == 2 && dx == 0);
        if (dy != 0 || !straightTwo) {
            return ValidationResult.invalid("BREAK_AND_TRAVERSE_INVALID_GEOMETRY");
        }

        // D-026 合法位置集
        BlockPos feet = context.bot().blockPosition();
        if (!feet.equals(from) && !feet.equals(to)) {
            return ValidationResult.invalid("BREAK_AND_TRAVERSE_STALE_START");
        }

        // 目标必须可通行且可站（支撑已存在）
        if (!MovementHelper.canWalkThrough(context.level(), to)
                || !MovementHelper.canWalkThrough(context.level(), to.above())
                || !MovementHelper.canWalkOn(context.level(), to)) {
            return ValidationResult.invalid("BREAK_AND_TRAVERSE_NO_SUPPORT");
        }

        // 必须至少有一个阻挡方块，且全部可破坏
        var blockers = BreakAndTraverseExecution.collectBlockers(context.level(), from, to);
        if (blockers.isEmpty()) {
            return ValidationResult.invalid("BREAK_AND_TRAVERSE_NOTHING_TO_BREAK");
        }
        for (BlockPos blocker : blockers) {
            String refusal = BlockInteraction.breakRefusal(context.bot(), context.level(), blocker);
            if (refusal != null) {
                if ("unbreakable_block".equals(refusal)) {
                    return ValidationResult.invalid("BREAK_BLOCK_UNBREAKABLE");
                }
                return ValidationResult.invalid("BREAK_BLOCK_PROTECTED");
            }
        }
        return ValidationResult.accepted();
    }

    @Override
    public MovementExecution create(MovementSpec spec, LiveExecutionContext context) {
        ValidationResult result = validate(spec, context);
        if (!result.valid()) {
            throw new IllegalArgumentException(
                    "Cannot create BreakAndTraverseExecution: " + result.failureCode());
        }
        return new BreakAndTraverseExecution(spec, context);
    }
}
