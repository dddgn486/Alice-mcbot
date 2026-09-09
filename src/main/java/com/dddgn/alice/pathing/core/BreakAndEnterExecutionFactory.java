package com.dddgn.alice.pathing.core;

import com.dddgn.alice.action.BlockInteraction;
import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * {@link MovementType#BREAK_AND_ENTER} 的工厂与校验。
 *
 * <p>语义（对照 Baritone `MovementTraverse` 的 `positionsToBreak`）：目的地格被可破坏方块占用时，
 * 破坏目的地躯干/头位（必要时再补头上一格）后走进该格。
 */
public final class BreakAndEnterExecutionFactory implements MovementExecutionFactory {
    public static final String KEY = "alice.movement.break_and_enter";

    @Override
    public String factoryKey() {
        return KEY;
    }

    @Override
    public boolean supports(MovementSpec spec) {
        return spec.movementType() == MovementType.BREAK_AND_ENTER;
    }

    @Override
    public ValidationResult validate(MovementSpec spec, LiveExecutionContext context) {
        if (!supports(spec)) return ValidationResult.invalid("BREAK_AND_ENTER_UNSUPPORTED_SPEC");
        if (context == null) return ValidationResult.invalid("BREAK_AND_ENTER_MISSING_CONTEXT");

        BlockPos from = spec.fromFoot();
        BlockPos to = spec.toFoot();
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        if (dy != 0 || Math.abs(dx) + Math.abs(dz) != 1) {
            return ValidationResult.invalid("BREAK_AND_ENTER_INVALID_GEOMETRY");
        }

        // D-026 合法位置集
        BlockPos feet = context.bot().blockPosition();
        if (!feet.equals(from) && !feet.equals(to)) {
            return ValidationResult.invalid("BREAK_AND_ENTER_STALE_START");
        }

        // 目的地最终必须可站
        if (!MovementHelper.canWalkOn(context.level(), to)) {
            return ValidationResult.invalid("BREAK_AND_ENTER_NO_LANDING_SUPPORT");
        }

        List<BlockPos> blockers =
                BreakAndEnterExecution.collectBlockers(context.level(), from, to);
        if (blockers.isEmpty()) {
            return ValidationResult.invalid("BREAK_AND_ENTER_DESTINATION_CLEAR");
        }
        for (BlockPos blocker : blockers) {
            if (!BlockInteraction.breakable(context.bot(), context.level(), blocker)) {
                return ValidationResult.invalid("BREAK_AND_ENTER_BLOCK_NOT_BREAKABLE");
            }
        }
        return ValidationResult.accepted();
    }

    @Override
    public MovementExecution create(MovementSpec spec, LiveExecutionContext context) {
        ValidationResult result = validate(spec, context);
        if (!result.valid()) {
            throw new IllegalArgumentException("Cannot create BreakAndEnterExecution: " + result.failureCode());
        }
        return new BreakAndEnterExecution(spec, context);
    }
}
