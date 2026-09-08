package com.dddgn.alice.pathing.core;

import com.dddgn.alice.action.BlockInteraction;
import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.core.BlockPos;

/** R5-3 PlaceStepAndTraverse 执行工厂；只接受"目标缺支撑 + 可放置"的 MovementSpec。 */
public final class PlaceStepAndTraverseExecutionFactory implements MovementExecutionFactory {
    public static final String KEY = "alice.place_step_and_traverse.v1";

    @Override
    public String factoryKey() {
        return KEY;
    }

    @Override
    public boolean supports(MovementSpec spec) {
        return spec != null
                && spec.movementType() == MovementType.PLACE_STEP_AND_TRAVERSE
                && KEY.equals(spec.executionFactoryKey());
    }

    @Override
    public ValidationResult validate(MovementSpec spec, LiveExecutionContext context) {
        if (!supports(spec)) return ValidationResult.invalid("PLACE_STEP_AND_TRAVERSE_UNSUPPORTED_SPEC");
        if (context == null) return ValidationResult.invalid("PLACE_STEP_AND_TRAVERSE_MISSING_CONTEXT");

        BlockPos from = spec.fromFoot();
        BlockPos to = spec.toFoot();
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        if ((dy != 0 && dy != -1) || Math.abs(dx) + Math.abs(dz) != 1) {
            return ValidationResult.invalid("PLACE_STEP_AND_TRAVERSE_INVALID_GEOMETRY");
        }

        // D-026 合法位置集
        BlockPos feet = context.bot().blockPosition();
        if (!feet.equals(from) && !feet.equals(to)) {
            return ValidationResult.invalid("PLACE_STEP_AND_TRAVERSE_STALE_START");
        }

        // 目标列可通行且确实缺支撑
        if (!MovementHelper.canWalkThrough(context.level(), to)
                || !MovementHelper.canWalkThrough(context.level(), to.above())) {
            return ValidationResult.invalid("PLACE_STEP_AND_TRAVERSE_TARGET_BLOCKED");
        }
        if (MovementHelper.canWalkOn(context.level(), to)) {
            return ValidationResult.invalid("PLACE_STEP_AND_TRAVERSE_SUPPORT_EXISTS");
        }

        // 放置位可替换 + 有可用支撑面 + 有资源
        BlockPos target = PlaceStepAndTraverseExecution.placePos(spec);
        if (!MovementHelper.canWalkThrough(context.level(), target)) {
            return ValidationResult.invalid("PLACE_STEP_AND_TRAVERSE_PLACE_OCCUPIED");
        }
        if (BlockInteraction.findPlaceableSlot(context.bot()) < 0) {
            return ValidationResult.invalid("PLACE_RESOURCE_UNAVAILABLE");
        }
        if (!BlockInteraction.hasPlacementFace(context.level(), target)) {
            return ValidationResult.invalid("PLACE_NO_VALID_FACE");
        }
        return ValidationResult.accepted();
    }

    @Override
    public MovementExecution create(MovementSpec spec, LiveExecutionContext context) {
        ValidationResult result = validate(spec, context);
        if (!result.valid()) {
            throw new IllegalArgumentException(
                    "Cannot create PlaceStepAndTraverseExecution: " + result.failureCode());
        }
        return new PlaceStepAndTraverseExecution(spec, context);
    }
}
