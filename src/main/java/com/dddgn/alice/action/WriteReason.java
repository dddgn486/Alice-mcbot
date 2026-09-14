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

    /**
     * **区域补种**（J8 / §13.2）：可持续伐木区里"欠树则补种"，属**计划内永久修改**。
     *
     * <p>与脚手架的 {@code STEP_PLACEMENT}（`TEMP`，必须配对拆除）**刻意区分**：
     * 补种是区域不变量的一部分，不受建拆同权约束 ⇒ 账本记为 {@code KEEP}（`temporary()==false`）。
     */
    REGION_REPLANT(Policy.EXPLICIT_TARGET, Action.PLACE, "区域补种（计划内永久）"),

    // ---- 容器写入（2026-09-13 用户裁定：**容器写入算"世界改动"** ⇒ 与方块写入同规格登记/受预算约束）----

    /**
     * 容器传输：从源容器取出 / 写入目标容器。
     *
     * <p>为什么单列：它既不是"破坏方块"也不是"放置方块"，但对世界的改动**同样是永久性的**
     * （物品换了位置）。此前只做审计（G5 留痕）、**没有任何授权与预算**——
     * 这是传输模块彻查（`docs/TRANSFER_MODULE_AUDIT.md` F4）点出的缺口。
     */
    // 注：这里用 `Action.BOTH` 而不是新增 `Action.CONTAINER` —— 因为 `Action` 字段**目前无读者**
    //（只有 `policy()`/`temporary()` 被读）⇒ 新增值只会再造一个死值（K-5 同族，已登记）。
    CONTAINER_TRANSFER(Policy.EXPLICIT_TARGET, Action.BOTH, "容器传输：取出/写入容器"),

    // ---- 合成工作站（阶段 3-A / A3b，D-190：**世界写入**，与方块写入同规格登记/受预算约束）----

    /**
     * **放置合成工作站**（工作台/熔炉这类"用一下就走"的机器）。
     *
     * <p>为什么单列：它是**没有上层任务显式请求**的写世界动作（bot 只是想合成），
     * 因此必须 ① 有专门的 reason（进 A 表、可审计）② 走 `WriteBudget` 的放置预算
     * ③ 属 {@code TEMP}：**用完即拆**（建拆同权），账本 `remaining` 必须回到 0。
     */
    CRAFT_STATION_PLACE(Policy.EXPLICIT_TARGET, Action.PLACE, "放置合成工作站（用完即拆）"),

    // ---- 外部触发 ----

    /** 玩家命令直接写入（管理/调试入口）。 */
    /**
     * **工作站装配**（L2 / D-194）：把"升级物品"放进容器的**升级槽**、以及用完再取出来。
     *
     * <p>语义 = 用户裁定："**装上升级本身算一种配置行为**" ⇒ 它不属于合成任务，
     * 而是独立的一层（`StationProvisionTask`）；走**容器写入**维度（`WriteBudget.consumeContainerWrite`，
     * 与 A11 的 `CONTAINER_TRANSFER` 同源），并且**建拆同权**：装进去的东西必须能原样取回。
     */
    STATION_PROVISION(Policy.EXPLICIT_TARGET, Action.BOTH, "装配/拆除工作站升级（用完即拆）"),
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
        return this == STEP_PLACEMENT || this == SUPPORT_PLACEMENT
                || this == CRAFT_STATION_PLACE;
    }

    /**
     * **本理由是否属于"容器写入"**（T1 / R-5，2026-09-14）—— 唯一真源。
     *
     * <p>容器写入是写入的**第三个维度**（G5）：它不进保护区/地形破坏那套判据，但必须过
     * `WriteBudget.consumeContainerWrite` + 在策略表里声明。原先没有任何"哪些理由是容器写入"的
     * 程序化判据 ⇒ 写入原语**无法自查**自己的调用方有没有记账（三路审计 §3.1 R-5：
     * 4 处散抄、3 个原语自身无闸门）⇒ 新增模组适配默认无记账。
     * 给出这条唯一真源后，原语可以做**编译期/运行期强制**（见 `FurnaceStation.click`）。
     */
    public boolean container() {
        return this == CONTAINER_TRANSFER || this == STATION_PROVISION;
    }
}
