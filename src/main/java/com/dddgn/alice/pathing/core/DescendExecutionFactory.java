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

        // D-026 合法位置集（对齐 Baritone MovementDescend.calculateValidPositions）：
        // 接受 {from, to, to.above()}。to.above() 覆盖"上一段带动量结束、正在落入目标列"的入口位置。
        BlockPos feet = context.bot().blockPosition();
        if (!feet.equals(from) && !feet.equals(to) && !feet.equals(to.above())) {
            return ValidationResult.invalid("DESCEND_STALE_START");
        }

        // 验证目标方块可通行（唯一允许的一格落差）
        if (!MovementHelper.canWalkThrough(context.level(), to)
                || !MovementHelper.canWalkThrough(context.level(), to.above())
                || !MovementHelper.canWalkOn(context.level(), to)) {
            return ValidationResult.invalid("DESCEND_INVALID_PRECONDITION");
        }

        // 过冲落点列（目标沿运动方向再延伸一格）防御校验
        // （D-023 第 0 号闭环 + D-024 细化版）。
        // **D-059：默认关闭（对齐 Baritone 原样）；低风险模式由风险评估打开该开关。**
        // 仅当过冲列在落点高度可穿越（存在坠落通道）时才需要检查：
        // 实心墙会挡住过冲（撞停或踩上），不存在坠落风险。
        if (!com.dddgn.alice.pathing.risk.RiskSwitches.descendOvershootGuard()) {
            return ValidationResult.accepted();
        }
        int signDx = Integer.signum(to.getX() - from.getX());
        int signDz = Integer.signum(to.getZ() - from.getZ());
        BlockPos landingBeyond = to.offset(signDx, 0, signDz);
        if (MovementHelper.canWalkThrough(context.level(), landingBeyond)) {
            // 通道内有即死危害（熔岩/火/岩浆块）→ 拒绝；检查体位、脚下与下一级支撑位
            if (MovementHelper.avoidWalkingInto(context.level().getBlockState(landingBeyond))
                    || MovementHelper.avoidWalkingInto(context.level().getBlockState(landingBeyond.below()))
                    || MovementHelper.avoidWalkingInto(context.level().getBlockState(landingBeyond.below(2)))) {
                return ValidationResult.invalid("DESCEND_REJECTED_LANDING_HAZARD");
            }
            // D-024（细化）：过冲列允许两种落脚面——
            //   ① 与目标同层（同层踉跄，无额外落差）；
            //   ② 下一级台阶（比目标低 1 格，即标准楼梯的下一级，总落差 2 格内）。
            // 两者都要求该落脚面本身可行走；再深（≥2 格）或不可行走一律视为悬崖拒绝。
            boolean sameLevel = MovementHelper.canWalkOn(context.level(), landingBeyond);
            boolean nextStep = MovementHelper.canWalkOn(context.level(), landingBeyond.below());
            if (!sameLevel && !nextStep) {
                return ValidationResult.invalid("DESCEND_REJECTED_OVERSHOOT_CLIFF");
            }
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
