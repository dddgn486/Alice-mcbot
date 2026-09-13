package com.dddgn.alice.pathing.core;

/** R2 执行对象的生命周期边界；具体物理动作由后续 Movement 实现提供。 */
public interface MovementExecution {
    enum Phase {
        NOT_STARTED,
        PRECONDITION_CHECK,
        EXECUTING,
        SETTLING,
        POSTCONDITION_CHECK,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    MovementSpec spec();
    String botId();
    String sessionId();
    Phase phase();
    void tick();
    void cancel();
    String failureCode();

    /**
     * **此刻取消这条 Movement 是否安全**（K-3，对齐 Baritone `Movement.safeToCancel`）。
     *
     * <p>为什么需要它（Baritone 的答案）："取消"在有些时刻是**危险的** —— 人跳到半空、
     * 刚把方块放下去还没落地、正往一个"需要垫脚才有支撑"的目标走。Baritone 里
     * `PathExecutor:287` 就是"不安全时**不许取消**"，而 `InventoryPauserProcess:53`
     * 用 `safeToCancel && 站定 ≥2 tick` 决定"能不能停下来开背包"（**这条正是 Alice 的 L2 菜单路线要的**：
     * 开容器菜单 = 一次会让物品移动的交互，绝不能在半空中做）。
     *
     * <p>默认 {@code true}（Baritone 默认亦然）；各 Movement 按自己的"承诺点"覆写。
     */
    default boolean safeToCancel() {
        return true;
    }
}
