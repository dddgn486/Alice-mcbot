package com.dddgn.alice.pathing.core;

/**
 * **一条边在规划期已验证到的可回收性事实**（逐边，不是"按类型假设"）。
 *
 * <p>为什么必须逐边（D-151 附注一的陷阱）：如果评估只看 Movement **类型**，那么"FALL 一定有回程"
 * 就成了一个全局假设 ⇒ `required=PATH_REVERSIBLE` 会**永远成立**，校验照样没有鉴别力
 * （只是把"恒假"换成"恒真"）。只有当"这条边有没有验过"作为**数据**随边流动、
 * 且**缺事实时保守降级**，准入校验才真的能拒东西。
 *
 * <p>今天只有一条事实（FALL 的返回守卫），其余类型的保证是**类型固有**的（不写世界 ⇒ 几何未变），
 * 由 {@link RecoverabilityEvaluator} 自行推导，不需要事实。
 *
 * @param fallReturnVerified 该 FALL 边已通过 PILLAR 返回守卫（`SurfaceMovementProvider.fallRecoverable`）
 */
public record RecoverabilityFacts(boolean fallReturnVerified) {

    /** 什么都没验过（默认值）。**FALL 用这个值会被保守降级为 `LOCAL_STEP`。** */
    public static final RecoverabilityFacts NONE = new RecoverabilityFacts(false);

    /** FALL 专属：落点已通过 PILLAR 返回守卫。 */
    public static final RecoverabilityFacts FALL_RETURN_VERIFIED = new RecoverabilityFacts(true);
}
