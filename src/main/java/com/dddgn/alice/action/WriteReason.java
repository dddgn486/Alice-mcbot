package com.dddgn.alice.action;

/**
 * 世界写入理由（结构化词表，D-082）。
 *
 * <p>**为什么要有它**：在这之前，"为什么可以破坏这一格"是由**调用哪个方法**隐式决定的——
 * 调 {@code BlockInteraction.breakable} 走"清障"策略，调 {@code breakableExplicit} 走"明确目标"策略。
 * 结果是同一份授权语义散在两个方法名里，调用点看不出"谁授权了什么"，失败也无法归因。
 * 现在理由成为**数据**：判定策略由 {@link #policy()} 派生，调用点必须显式声明。
 *
 * <p>**为什么不能只用一个布尔开关**：理由同时决定了两件事——
 * ① 走哪套安全策略（{@link Policy}），② 这次写入的归因身份（进 {@link WriteAudit} / J6 账本）。
 * 布尔开关两件都表达不了，而且会把"允许改世界"泄漏到任务的所有子请求上。
 */
public enum WriteReason {

    // ---- 破坏：明确目标（对齐 Baritone "挖脚下/挖目标" 语义）----

    /** 明确的挖掘目标：允许挖脚下承重块，只受保护区/不可破坏/流体约束。 */
    EXPECTED_TARGET(Policy.EXPLICIT_TARGET, Action.BREAK, "明确目标挖掘"),

    /** 回收我方放置的临时方块（垫脚柱/台阶/支撑）：**目标是脚下那格**，
     *  故必须走明确目标策略——清障策略会拒 `underfoot_block`（D-097/J6-b）。 */
    SCAFFOLD_RESTORE(Policy.EXPLICIT_TARGET, Action.BREAK, "回收临时放置"),

    /** 向下挖掘（Baritone {@code MovementDownward} 语义）：目标是脚下那一格。 */
    DESCEND_FOOT(Policy.EXPLICIT_TARGET, Action.BREAK, "向下挖掘"),

    /** 批量地形编辑（道路施工等非寻路场景）。 */
    BULK_EDIT(Policy.EXPLICIT_TARGET, Action.BOTH, "批量地形编辑（道路施工）"),

    // ---- 破坏：清障（比明确目标更保守：回避脚下承重块与高代价方块）----

    /** 为打通视线而清障（伐木：树冠盖住柱底那一根）。 */
    LINE_OF_SIGHT(Policy.CLEARING, Action.BREAK, "清障：打通视线"),

    /** 为腾出站位而清障。 */
    STANDING_SPACE(Policy.CLEARING, Action.BREAK, "清障：腾出站位"),

    /** 寻路破坏通行（{@code BREAK_AND_TRAVERSE} / {@code BREAK_AND_ENTER}）。 */
    PATH_ACCESS(Policy.CLEARING, Action.BREAK, "寻路：破坏通行"),

    // ---- 放置 ----

    /** 在目标下方放临时支撑（挖矿兜底"走进目标格"）。 */
    SUPPORT_PLACEMENT(Policy.EXPLICIT_TARGET, Action.PLACE, "放置临时支撑"),

    /** 放置台阶/垫脚（{@code PLACE_STEP_AND_TRAVERSE}、搭柱子）。 */
    STEP_PLACEMENT(Policy.EXPLICIT_TARGET, Action.PLACE, "放置台阶/垫脚"),

    // ---- 外部触发 ----

    /** 玩家命令直接写入（管理/调试入口）。 */
    MANUAL(Policy.EXPLICIT_TARGET, Action.BOTH, "玩家命令");

    /** 判定策略：决定走 {@code BlockBreakSafety} 的哪一套拒绝规则。 */
    public enum Policy {
        /** 明确目标：保护区 + 不可破坏 + 流体 + 黑曜石等高代价仍允许。 */
        EXPLICIT_TARGET,
        /** 清障：额外回避脚下承重块与高代价方块，优先换站位/路线。 */
        CLEARING
    }

    /** 写入动作类型。 */
    public enum Action {
        BREAK, PLACE, BOTH
    }

    private final Policy policy;
    private final Action action;
    private final String label;

    WriteReason(Policy policy, Action action, String label) {
        this.policy = policy;
        this.action = action;
        this.label = label;
    }

    public Policy policy() {
        return policy;
    }

    public Action action() {
        return action;
    }

    /** 中文说明（日志/诊断用）。 */
    public String label() {
        return label;
    }

    /**
     * 该理由下的放置是否属于**临时**（必须配对拆除，即"建拆同权"约束的那一类）。
     *
     * <p>J6-a：只有临时放置需要、也能够被恢复；道路等永久放置（`BULK_EDIT`）与玩家命令（`MANUAL`）
     * 走独立授权，不受配对约束——两者不可混（D-081 §12.1 / D-095）。
     */
    public boolean temporary() {
        return this == STEP_PLACEMENT || this == SUPPORT_PLACEMENT;
    }
}
