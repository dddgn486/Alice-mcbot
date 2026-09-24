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
        
        // D-026 合法位置集：接受 from 或 to（含"已在目标"的幂等情形）
        BlockPos feet = MovementHelper.footCell(context.level(), context.bot());
        if (!feet.equals(from) && !feet.equals(to)) {
            return ValidationResult.invalid("DIAGONAL_STALE_START");
        }
        
        // 验证目标方块可通行
        // **K-4（2026-09-16）**：改用规划侧的 `canTraverse`（含两侧格 + **扫掠**，见 `MovementHelper:332-348`），
        // 不再手搓一份"看起来一样"的判定 —— 两份判据迟早会漂移（审计 K-4 的原话）。
        if (!MovementHelper.canTraverse(context.level(), from, to)) {
            return ValidationResult.invalid("DIAGONAL_INVALID_PRECONDITION");
        }
        
        // ⭐ `P2`/`D-425`（2026-09-24 实读码）：这里原先还有**第二次手搓**的两侧格检查
        // （`sideX`/`sideZ` 及其 `above()`）并给出 `DIAGONAL_SIDE_COLLISION` —— 它与 `canTraverse`
        // 内部那段**逐格相同**（`MovementHelper.canTraverse` 的 |dx|=|dz|=1 分支算的是
        // `(from.x+dx, from.y, from.z)` / `(from.x, from.y, from.z+dz)`，与这里的
        // `(to.x, from.y, from.z)` / `(from.x, from.y, to.z)` 是**同一批格子**、同一个 `canWalkThrough`）。
        // 而本方法**先**调 `canTraverse`（上面那一句）⇒ 侧格被堵时早就返回 `DIAGONAL_INVALID_PRECONDITION`
        // ⇒ `DIAGONAL_SIDE_COLLISION` **永远不可达**（死码）；`canTraverse` 还多查了玩家扫掠空间
        // ⇒ 那段重复判定是它的**真子集**。
        // ⇒ 删掉：单一来源（`K4-P1`：执行工厂不许手搓规划侧已有的判据）+ 退役死码
        //   （`K5` 同族：声明了却无人产出的码就是观测盲区）。
        // 判据 = 夹具 `place_step_diagonal` 的 `SIDE` 用例（两侧准入一致、且码必须是**共享谓词**的）
        // + 门禁 `rule_diagonal_side_single_source`（注入即红）。
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
