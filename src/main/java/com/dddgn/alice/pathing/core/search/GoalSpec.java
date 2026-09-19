package com.dddgn.alice.pathing.core.search;

import net.minecraft.core.BlockPos;

/**
 * 规划目标（对照 Baritone `Goal` 接口）：既判断是否到达，也提供启发式。
 *
 * <p>启发式必须可采纳（admissible）：不得高估真实剩余成本，否则 A* 失去最优性。
 */
public interface GoalSpec {
    /** 该脚位是否满足目标。 */
    boolean isInGoal(BlockPos foot);

    /** 从该脚位到目标的剩余成本下界。 */
    double heuristic(BlockPos foot);

    /** 目标的规范脚位（用于 PathPlan 记录）。 */
    BlockPos goalFoot();

    /** 人类可读描述。 */
    String describe();

    /**
     * 这个目标是否**钉死在一个脚位**上（默认 `true`）。
     *
     * <p>⭐ **粗目标**（`D-337`，用户 2026-09-19 选 A：`GoalXZ` 型 + 滚动重规划）返回 `false`：
     * 它的"到达"是**纯算术**判断（进某个 XZ 半径内即可），**不读任何方块** ⇒
     * {@code AStarMovementSearch} 的 `GOAL_NOT_LOADED` 前置守卫对它**不适用** ——
     * 那个守卫的立法目的是"**别去读未加载方块**"（会同步加载并阻塞主线程），而粗目标根本不读。
     *
     * <p>⚠️ **红线不变**：内核**仍然从不加载区块** —— 搜索只在已加载区内扩展（`skipped_unloaded`），
     * 撞到加载边界就交出**尽力而为的前缀**（K-1 语义），由执行器走完再规划（滚动重规划）。
     */
    default boolean exactFoot() {
        return true;
    }
}
