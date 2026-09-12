package com.dddgn.alice.pathing.core;

/**
 * **可回收性准入策略**（D-036 差异① 的落地处）：每种 Movement 至少要求什么等级。
 *
 * <p>它和 {@link RecoverabilityEvaluator} 是**一对**：评估器说"这条边**提供了**什么保证"，
 * 策略表说"这种 Movement **要求**什么保证"；`MovementSpec` 构造时比较两者，不够就**抛异常**
 * （不是静默降级 —— 规划期就该炸，别把不安全的路带进执行期）。
 *
 * <p><b>顺序纪律（P0-B 复核 §3，违反会崩规划器）</b>：必须**先**让评估器能产出真实等级、
 * **后**才抬 required。否则 `LOCAL_STEP < required` ⇒ 现有合法边也会抛。
 *
 * <p>今天的策略表：
 * <ul>
 *   <li>{@code FALL} → {@code PATH_REVERSIBLE}：只有**带 `fall_return_verified` 事实**的 FALL 边才允许
 *       （它必然过了 PILLAR 返回守卫）；**没带事实的 FALL 边一律拒绝** —— 这是本表唯一"能真的拒东西"的一条，
 *       也是它存在的意义（此前 `0<0` 恒假，等于没有校验）。</li>
 *   <li>其余类型 → {@code LOCAL_STEP}：维持现状（它们的保证由类型固有性质给出，抬上去需要新的产出者）。</li>
 * </ul>
 *
 * <p><b>尚未使用的等级</b>：{@code SAFE_EXIT_REQUIRED} / {@code EMERGENCY_EXIT_REQUIRED} 目前
 * **没有任何产出者**。它们是"逃逸场景"（生存出口：进入时可保守、逃离时可用更激进手段）的预留语义 ——
 * 在没有真实产出者之前，本表**不写死它们**，以免再造一个空抽象。
 */
public final class RecoverabilityPolicy {

    private RecoverabilityPolicy() {
    }

    /** 该 Movement 类型的**最低要求**。 */
    public static RecoverabilityLevel requiredFor(MovementType type) {
        return switch (type) {
            case FALL -> RecoverabilityLevel.PATH_REVERSIBLE;
            default -> RecoverabilityLevel.LOCAL_STEP;
        };
    }

    /** 策略表快照（自检/文档用）。 */
    public static String describe() {
        StringBuilder builder = new StringBuilder();
        for (MovementType type : MovementType.values()) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(type).append(">=").append(requiredFor(type));
        }
        return builder.toString();
    }
}
