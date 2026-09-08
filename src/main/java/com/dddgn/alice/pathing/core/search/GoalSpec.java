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
}
