package com.dddgn.alice.pathing.core;

import com.dddgn.alice.action.BlockInteraction;
import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.core.BlockPos;

/**
 * {@link MovementType#FALL}（落差 2~3 格）的工厂与校验。
 *
 * <p>对照 Baritone {@code MovementDescend.dynamicFallCost:145-224}（无水落地分支）+ D-058 决策：
 * 落点可站、非流体、非底部半砖、落差 2~3 格；**落点必须能用 PILLAR 返回**（D-024 补记 Q1-D）。
 */
public final class FallExecutionFactory implements MovementExecutionFactory {
    public static final String KEY = "alice.movement.fall";

    @Override
    public String factoryKey() {
        return KEY;
    }

    @Override
    public boolean supports(MovementSpec spec) {
        return spec.movementType() == MovementType.FALL;
    }

    @Override
    public ValidationResult validate(MovementSpec spec, LiveExecutionContext context) {
        if (!supports(spec)) return ValidationResult.invalid("FALL_UNSUPPORTED_SPEC");
        if (context == null) return ValidationResult.invalid("FALL_MISSING_CONTEXT");

        BlockPos from = spec.fromFoot();
        BlockPos to = spec.toFoot();
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int dy = to.getY() - from.getY();
        if (Math.abs(dx) + Math.abs(dz) != 1 || dy > -2 || dy < -3) {
            return ValidationResult.invalid("FALL_INVALID_GEOMETRY");
        }

        // D-026 合法位置集
        BlockPos feet = context.bot().blockPosition();
        if (!feet.equals(from) && !feet.equals(to)) {
            return ValidationResult.invalid("FALL_STALE_START");
        }
        if (feet.equals(from) && !context.bot().onGround()) {
            return ValidationResult.invalid("FALL_NOT_ON_GROUND");
        }

        // 走离边缘
        BlockPos edge = from.offset(dx, 0, dz);
        if (!MovementHelper.canWalkThrough(context.level(), edge)
                || !MovementHelper.canWalkThrough(context.level(), edge.above())) {
            return ValidationResult.invalid("FALL_EDGE_BLOCKED");
        }
        // 下落列净空
        for (int y = from.getY() - 1; y > to.getY(); y--) {
            if (!MovementHelper.canWalkThrough(context.level(),
                    new BlockPos(to.getX(), y, to.getZ()))) {
                return ValidationResult.invalid("FALL_COLUMN_BLOCKED");
            }
        }
        // 落点
        if (!MovementHelper.canWalkOn(context.level(), to)
                || !MovementHelper.canWalkThrough(context.level(), to)
                || !MovementHelper.canWalkThrough(context.level(), to.above())) {
            return ValidationResult.invalid("FALL_LANDING_INVALID");
        }
        if (!context.level().getFluidState(to).isEmpty()
                || !context.level().getFluidState(to.above()).isEmpty()) {
            return ValidationResult.invalid("FALL_LANDING_FLUID");
        }
        if (MovementHelper.isBottomSlab(context.level().getBlockState(to.below()))) {
            return ValidationResult.invalid("FALL_LANDING_BOTTOM_SLAB");
        }

        // 落点可回收守卫（PILLAR 返回）
        int drop = -dy;
        for (int k = 1; k <= drop + 1; k++) {
            if (!MovementHelper.canWalkThrough(context.level(), to.above(k))) {
                return ValidationResult.invalid("FALL_NOT_RECOVERABLE_HEADROOM");
            }
        }
        if (!BlockInteraction.hasPlacementFace(context.level(), to)) {
            return ValidationResult.invalid("FALL_NOT_RECOVERABLE_NO_FACE");
        }
        if (BlockInteraction.countThrowaway(context.bot()) < drop) {
            return ValidationResult.invalid("FALL_NOT_RECOVERABLE_NO_BLOCKS");
        }

        return ValidationResult.accepted();
    }

    @Override
    public MovementExecution create(MovementSpec spec, LiveExecutionContext context) {
        ValidationResult result = validate(spec, context);
        if (!result.valid()) {
            throw new IllegalArgumentException("Cannot create FallExecution: " + result.failureCode());
        }
        return new FallExecution(spec, context);
    }
}
