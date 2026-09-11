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
 */
public record MiningProfile(boolean standableOnly, int maxGainSteps, int gainBlockBudget,
                            int clearBudget, boolean restoreOwnPlacements) {

    /** 加高方块预算默认值（用户 2026-09-11 裁定：12，砍树够用；模组超高树不在范围）。 */
    public static final int DEFAULT_GAIN_BLOCK_BUDGET = 12;

    /** 只用现成可站站位、且**不许加高**（伐木 J1–J5 的原行为）。 */
    public static final MiningProfile STANDABLE_ONLY = new MiningProfile(true, 0, 0, 0, false);

    /** 允许规划器自己挖隧道/挖地进站（挖掘 Job 的原行为）。 */
    public static final MiningProfile TUNNEL_ALLOWED = new MiningProfile(false, 0, 0, 0, false);

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
        return new MiningProfile(standableOnly, steps, blockBudget, clearBudget, restoreOwnPlacements);
    }

    /** 允许"限次清障"（腾站位 / 通视线 / 开立柱），最多破坏 {@code budget} 个阻挡方块。 */
    public MiningProfile withClear(int budget) {
        return new MiningProfile(standableOnly, maxGainSteps, gainBlockBudget, budget,
                restoreOwnPlacements);
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
        return new MiningProfile(standableOnly, maxGainSteps, gainBlockBudget, clearBudget, true);
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
                + (mayGain() ? " gain<=" + maxGainSteps + " blocks<=" + gainBlockBudget : " gain=none")
                + (mayClear() ? " clear<=" + clearBudget : " clear=none")
                + (restoreOwnPlacements ? " restore=own" : "");
    }
}
