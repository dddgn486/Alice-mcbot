package com.dddgn.alice.pathing.core;

import java.util.Objects;

/**
 * 一条 Movement 的**可回收性评估结果**：等级 + **依据**。
 *
 * <p>为什么必须带 `basis`（P0-B 的教训）：可回收性是本项目的**签名能力**（D-036 差异①），
 * 但它此前是一个"两边都写死 `LOCAL_STEP`、校验 `0<0` 恒假"的死抽象 —— 没人能看出某个等级是
 * *算出来的* 还是 *抄来的*。带上"依据"，任何等级都可以被追问"凭什么"，也能在报告里按依据分组统计。
 *
 * @param level 规划期**已验证**的最低保证等级（见 {@link RecoverabilityEvaluator} 的语义说明）
 * @param basis 依据标签（机器可读，snake_case；见 {@link RecoverabilityEvaluator} 的规则表）
 */
public record RecoverabilityAssessment(RecoverabilityLevel level, String basis) {
    public RecoverabilityAssessment {
        level = Objects.requireNonNull(level, "level");
        basis = Objects.requireNonNull(basis, "basis");
        if (basis.isBlank()) {
            throw new IllegalArgumentException("basis must not be blank");
        }
    }

    public String describe() {
        return level + "/" + basis;
    }
}
