package com.dddgn.alice.task.mining;

/**
 * **挖掘能力信封**（D-111，切片 A）：一个目标"允许用什么手段去够到它"。
 *
 * <p>为什么需要它：在此之前，"允许什么手段"散落在三处形状各异的表达里——
 * `MineTask` 的一个 `boolean standableOnly`、`PathRequest` 里 6 份**硬编码的 Movement 集合**、
 * 以及各任务自己声明的常量（`MAX_CLEAR_PER_TREE=8`、`MAX_GAIN_PER_LOG=3`、`CLIMB_BUDGET=12`
 * ——最后这个还在 `LumberJob` 与 `ScaffoldLifecycleTask` 里各写了一份）。
 * 结果是：**每出现一种新授权需求，调用点就要自己重写一段 L2 逻辑**
 * （J7 的"搭一格柱子加高"就是这么在两个调用点各实现了一遍）。
 *
 * <p>本对象把信封收成一处：**能力（白名单）+ 预算**，由 L3 显式构造、L2 只读消费。
 * 归因（谁/为什么）仍由 {@code WriteGrant} 承担——加高产生的放置用
 * {@link #gainReason()}（默认 {@code STANDING_SPACE}：清障腾站位）派生，不新增凭证类型。
 *
 * <p>**默认即现状**：{@link #STANDABLE_ONLY} 与 {@link #TUNNEL_ALLOWED} 精确对应旧的两个布尔取值，
 * 因此既有调用点在改造后行为不变。
 *
 * @param standableOnly          true = 只用现成可站站位（禁止规划器自己挖隧道/挖地进站）
 * @param maxGainSteps           允许"原地加高"几次（0 = 不允许；每步 1 个方块）
 * @param gainBlockBudget        加高最多消耗几个一次性方块
 * @param clearBudget            **限次清障预算**（D-115）：为了"腾站位 / 通视线 / 开立柱"，最多破坏
 *                               几个阻挡方块（0 = 不允许清障；只统计"非原木、可破坏"的方块）。
 * @param restoreOwnPlacements   **建拆同权**（D-112）：本任务用完自己放的临时方块后，
 *                               在**会话内**自上而下拆掉（`RestoreScopeTask`）。
 *                               嵌套子任务必须为 false —— 否则子任务会把"会话所有者"还要用的
 *                               脚手架拆掉（例如伐木的加高柱）。
 * @param approach               **"走到站位格"这一步允许什么手段**（`D-443` 裁定 1a/7b，2026-09-25）。
 *                               ⭐ 为什么单列一个字段：模式 A 的接近搜索（{@code MiningPlanner.selectBest}）
 *                               原先把请求**硬编码**成 {@code PathRequest.of}（纯通行），于是出现了
 *                               "**同一个 bot、同一 tick，走位说 `REACHED`、挖掘站位说 `unreachable`**"
 *                               的真机读数（`survey/34 §2.1`）—— 能力本该由**调用方**声明，而不是由
 *                               规划器替所有消费者写死。默认 {@link Approach#PURE_PASSAGE} = **精确现状**，
 *                               所以既有调用点一行不改、行为不变。
 */
public record MiningProfile(boolean standableOnly, int maxGainSteps, int gainBlockBudget,
                            int clearBudget, boolean restoreOwnPlacements, Approach approach) {

    /**
     * 模式 A"走到站位格"的**接近能力**（`D-443` 裁定 1a）。
     *
     * <ul>
     *   <li>{@link #PURE_PASSAGE}（默认）= {@code PathRequest.of}：只走，不改世界（`D-076` 默认）；</li>
     *   <li>{@link #PLACEMENT_ALLOWED} = {@code PathRequest.withPlacement}：**只放不拆**
     *       （`PLACE_STEP_AND_TRAVERSE` + `PILLAR` + `FALL`）—— 与鱼骨的 `A14` **同一个集合**，
     *       所以**不扩大任何写入授权面**（`D-443` 裁定 7b：额度用尽 ⇒ 如实放弃，不静默降级）。</li>
     * </ul>
     *
     * ⚠️ 消费方（今天的唯一消费者 = 鱼骨）必须自己持有预算与上限：本枚举只表达"**允许**"，
     * 不表达"**放多少**"（`C8` 的三条上限 + `A14` 的作业累计额度仍在作业侧）。
     */
    public enum Approach {
        PURE_PASSAGE,
        PLACEMENT_ALLOWED
    }

    /** 加高方块预算默认值（用户 2026-09-11 裁定：12，砍树够用；模组超高树不在范围）。 */
    public static final int DEFAULT_GAIN_BLOCK_BUDGET = 12;

    /** 只用现成可站站位、且**不许加高**（伐木 J1–J5 的原行为）。 */
    public static final MiningProfile STANDABLE_ONLY =
            new MiningProfile(true, 0, 0, 0, false, Approach.PURE_PASSAGE);

    /** 允许规划器自己挖隧道/挖地进站（挖掘 Job 的原行为）。 */
    public static final MiningProfile TUNNEL_ALLOWED =
            new MiningProfile(false, 0, 0, 0, false, Approach.PURE_PASSAGE);

    public MiningProfile {
        if (maxGainSteps < 0 || gainBlockBudget < 0 || clearBudget < 0) {
            throw new IllegalArgumentException("gain budgets must be non-negative");
        }
        if (maxGainSteps > 0 && gainBlockBudget <= 0) {
            throw new IllegalArgumentException("gain requires a positive block budget");
        }
    }

    /** 允许原地加高（够不到高处目标时的**最小必要**手段；实测高树只差约 0.36 格 ⇒ 1 次足够）。 */
    public MiningProfile withGain(int steps) {
        return withGain(steps, DEFAULT_GAIN_BLOCK_BUDGET);
    }

    public MiningProfile withGain(int steps, int blockBudget) {
        return new MiningProfile(standableOnly, steps, blockBudget, clearBudget, restoreOwnPlacements, approach);
    }

    /** 允许"限次清障"（腾站位 / 通视线 / 开立柱），最多破坏 {@code budget} 个阻挡方块。 */
    public MiningProfile withClear(int budget) {
        return new MiningProfile(standableOnly, maxGainSteps, gainBlockBudget, budget,
                restoreOwnPlacements, approach);
    }

    /** 是否允许清障。 */
    public boolean mayClear() {
        return clearBudget > 0;
    }

    /**
     * **建拆同权**（D-112）：本任务用完自己放的临时方块后，在会话内自上而下拆掉。
     * 只应由"会话所有者"开启（Job 的每个目标 / standalone MineTask），嵌套子任务保持 false。
     */
    public MiningProfile withRestore() {
        return new MiningProfile(standableOnly, maxGainSteps, gainBlockBudget, clearBudget, true, approach);
    }

    /**
     * ⭐ **接近能力 = 允许"补一块再走"**（`D-443` 裁定 1a，2026-09-25）：把模式 A 的"走到站位格"
     * 从纯通行升到 {@code PathRequest.withPlacement}（与鱼骨 `A14` 同一个集合：只放不拆）。
     *
     * <p>消费者的义务（不是本方法能表达的）：**作业累计额度**（`C8` 的
     * {@code bridgeBlockBudget = max(16, advanceCells/10)}）+ **单段悬空上限** {@code maxGapLength=8}
     * + 额度用尽后**如实放弃**（不许静默退回纯通行继续走 —— 那会留下半成品通道，违反 `I2`）。
     */
    public MiningProfile withPlacementApproach() {
        return new MiningProfile(standableOnly, maxGainSteps, gainBlockBudget, clearBudget,
                restoreOwnPlacements, Approach.PLACEMENT_ALLOWED);
    }

    /** **嵌套子任务的信封**（R2 / D-121）：子任务的能力必须是父信封的**子集**。
     *
     * <p>规则（三条都来自既有裁定）：
     * <ol>
     *   <li>**清障归零** —— 不许"清障里的清障"无限递归（D-115）；</li>
     *   <li>**加高取 `min(父, 1)`** 且沿用父的方块预算 —— 父不允许加高时子也不允许，
     *       父允许时子最多爬 1 格（够到阻挡物即可，实测清障只差 1 格）；</li>
     *   <li>**建拆同权归 false** —— 只归"会话所有者"（D-112），否则子任务会把所有者还要用的脚手架拆掉。</li>
     * </ol>
     */
    public MiningProfile nestedSubTask() {
        int steps = Math.min(maxGainSteps, 1);
        return new MiningProfile(standableOnly, steps, steps > 0 ? gainBlockBudget : 0, 0, false,
                approach);
    }

    /**
     * **① 就地扫尾的能力信封**（R3 / D-123）：加高步数**从树的几何推导**，不再写死 8。
     *
     * <p>推导依据（实测）：扫尾是从"作业脚位"往上够树冠里的掉落物，最坏情况就是**整根树干的高度**
     * （高云杉实测：脚位 65 → 掉落物 70，树干 64..70 共 7 格 ⇒ 上界 = 树干高 + 1 给树冠留余量）。
     * 同时**不允许超过一次性方块预算**（每个加高步恰好消耗 1 个方块）——该预算由用户 2026-09-11
     * 裁定为 {@link #DEFAULT_GAIN_BLOCK_BUDGET}（12）；模组超高树因此如实停在 12 格，
     * 不做"无限加高"（策略：超出预算就让 ② 拆除后落地再收或如实报 `product_not_collected`）。
     *
     * @param trunkHeight 树干高度（格）；来自候选树自身（`Tree.trunkHeight()`）
     */
    public static MiningProfile sweepGain(int trunkHeight) {
        int steps = Math.max(1, Math.min(trunkHeight + 1, DEFAULT_GAIN_BLOCK_BUDGET));
        // 每步 1 个方块 ⇒ 方块预算 = 步数（紧界，不再沿用"默认 12"的宽松值）
        return STANDABLE_ONLY.withGain(steps, steps);
    }

    /** 是否允许加高。 */
    public boolean mayGain() {
        return maxGainSteps > 0;
    }

    /** 加高产生的放置用的理由（归因串里的 requester 仍取自任务的 {@code WriteGrant}）。 */
    public com.dddgn.alice.action.WriteReason gainReason() {
        return com.dddgn.alice.action.WriteReason.STANDING_SPACE;
    }

    public String describe() {
        return "standableOnly=" + standableOnly
                + (approach == Approach.PURE_PASSAGE ? "" : " approach=" + approach)
                + (mayGain() ? " gain<=" + maxGainSteps + " blocks<=" + gainBlockBudget : " gain=none")
                + (mayClear() ? " clear<=" + clearBudget : " clear=none")
                + (restoreOwnPlacements ? " restore=own" : "");
    }
}
