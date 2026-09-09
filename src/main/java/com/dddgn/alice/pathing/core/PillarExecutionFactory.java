package com.dddgn.alice.pathing.core;

import com.dddgn.alice.action.BlockInteraction;
import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.core.BlockPos;

/**
 * {@link MovementType#PILLAR}（垂直上升 1 格）的工厂与校验。
 *
 * <p>对照 Baritone {@code MovementPillar.cost}（baritone-1.20.1 @8c55ad0
 * {@code movements/MovementPillar.java:62-124}）：
 * <ul>
 *   <li>放置格（{@code src}）必须可放置：脚下有支撑面 + 快捷栏有一次性方块；</li>
 *   <li>目标格与头顶格必须净空（Alice 最小闭环不做"破头顶方块"分支，见 D-055 偏离记录）；</li>
 *   <li>脚下支撑不能是底部半砖（Baritone 同款 COST_INF 条件）。</li>
 * </ul>
 */
public final class PillarExecutionFactory implements MovementExecutionFactory {
    public static final String KEY = "alice.movement.pillar";

    @Override
    public String factoryKey() {
        return KEY;
    }

    @Override
    public boolean supports(MovementSpec spec) {
        return spec.movementType() == MovementType.PILLAR;
    }

    @Override
    public ValidationResult validate(MovementSpec spec, LiveExecutionContext context) {
        if (!supports(spec)) return ValidationResult.invalid("PILLAR_UNSUPPORTED_SPEC");
        if (context == null) return ValidationResult.invalid("PILLAR_MISSING_CONTEXT");

        BlockPos from = spec.fromFoot();
        BlockPos to = spec.toFoot();
        if (to.getY() != from.getY() + 1 || to.getX() != from.getX() || to.getZ() != from.getZ()) {
            return ValidationResult.invalid("PILLAR_INVALID_GEOMETRY");
        }

        // D-026 合法位置集
        BlockPos feet = context.bot().blockPosition();
        if (!feet.equals(from) && !feet.equals(to)) {
            return ValidationResult.invalid("PILLAR_STALE_START");
        }

        // 起跳列净空（身体 + 头），放置格可替换
        if (!MovementHelper.canWalkThrough(context.level(), to)
                || !MovementHelper.canWalkThrough(context.level(), to.above())) {
            return ValidationResult.invalid("PILLAR_HEAD_BLOCKED");
        }
        if (!MovementHelper.canWalkThrough(context.level(), from)) {
            return ValidationResult.invalid("PILLAR_PLACE_OCCUPIED");
        }

        // 必须站在地面才能起跳
        if (!context.bot().onGround()) {
            return ValidationResult.invalid("PILLAR_NOT_ON_GROUND");
        }

        // 放置资源与支撑面（Baritone：context.costOfPlacingAt + canPlaceAgainst）
        if (BlockInteraction.findPlaceableSlot(context.bot()) < 0) {
            return ValidationResult.invalid("PLACE_RESOURCE_UNAVAILABLE");
        }
        if (!BlockInteraction.hasPlacementFace(context.level(), from)) {
            return ValidationResult.invalid("PLACE_NO_VALID_FACE");
        }

        return ValidationResult.accepted();
    }

    @Override
    public MovementExecution create(MovementSpec spec, LiveExecutionContext context) {
        ValidationResult result = validate(spec, context);
        if (!result.valid()) {
            throw new IllegalArgumentException("Cannot create PillarExecution: " + result.failureCode());
        }
        return new PillarExecution(spec, context);
    }
}
