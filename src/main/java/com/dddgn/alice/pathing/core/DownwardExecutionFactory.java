package com.dddgn.alice.pathing.core;

import com.dddgn.alice.action.BlockInteraction;
import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.core.BlockPos;

/**
 * {@link MovementType#DOWNWARD}（垂直下落 1 格）的工厂与校验（D-048）。
 *
 * <p>语义（对照 Baritone {@code MovementDownward}）：**破坏脚下的方块后垂直掉 1 格**——
 * 站在方块上时同列不可能凭空下落，所以该 Movement 必然涉及"挖脚下"。
 *
 * <p>G 语义守卫（用户 2026-09-09 确认）：脚下可破坏（非空气/流体/保护区）+ 落点有支撑 +
 * **落点存在逃生路线**（旁边有可站格能 ASCEND 回原层），否则拒绝。
 */
public final class DownwardExecutionFactory implements MovementExecutionFactory {
    public static final String KEY = "alice.movement.downward";

    @Override
    public String factoryKey() {
        return KEY;
    }

    @Override
    public boolean supports(MovementSpec spec) {
        return spec.movementType() == MovementType.DOWNWARD;
    }

    @Override
    public ValidationResult validate(MovementSpec spec, LiveExecutionContext context) {
        if (!supports(spec)) return ValidationResult.invalid("DOWNWARD_UNSUPPORTED_SPEC");
        if (context == null) return ValidationResult.invalid("DOWNWARD_MISSING_CONTEXT");

        BlockPos from = spec.fromFoot();
        BlockPos to = spec.toFoot();
        if (to.getY() != from.getY() - 1 || to.getX() != from.getX() || to.getZ() != from.getZ()) {
            return ValidationResult.invalid("DOWNWARD_INVALID_GEOMETRY");
        }

        // D-026 合法位置集
        BlockPos feet = context.bot().blockPosition();
        if (!feet.equals(from) && !feet.equals(to)) {
            return ValidationResult.invalid("DOWNWARD_STALE_START");
        }

        // 落点（破掉脚下后的脚位）必须可站，且身体/头部可通行
        if (!MovementHelper.canWalkOn(context.level(), to)
                || !MovementHelper.canWalkThrough(context.level(), to.above())) {
            return ValidationResult.invalid("DOWNWARD_INVALID_PRECONDITION");
        }

        // 脚下的方块必须可破坏（已在破坏中/已空则放行，等待下落）
        if (!context.level().getBlockState(to).isAir()
                && !BlockInteraction.breakable(context.bot(), context.level(), to)) {
            return ValidationResult.invalid("DOWNWARD_BLOCK_NOT_BREAKABLE");
        }

        // G 语义守卫：落点必须有逃生路线
        if (!SurfaceMovementProviderAccess.hasEscapeFrom(context.level(), to)) {
            return ValidationResult.invalid("DOWNWARD_NO_ESCAPE_ROUTE");
        }
        return ValidationResult.accepted();
    }

    @Override
    public MovementExecution create(MovementSpec spec, LiveExecutionContext context) {
        ValidationResult result = validate(spec, context);
        if (!result.valid()) {
            throw new IllegalArgumentException("Cannot create DownwardExecution: " + result.failureCode());
        }
        return new DownwardExecution(spec, context);
    }
}
