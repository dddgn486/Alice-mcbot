package com.dddgn.alice.pathing;

import net.minecraft.core.BlockPos;

/**
 * 寻路目标(移植自 Baritone 的 Goal 体系,仅保留 M 阶段需要的子集)。
 */
public interface Goal {

    /** 该脚位是否已达成目标。 */
    boolean isInGoal(BlockPos footPos);

    /** 到达目标的最低代价估算(曼哈顿距离量级,用于 A* 启发式)。 */
    double heuristic(BlockPos footPos);

    /** 目标:到达指定脚位(站在目标格上)。 */
    record GoalBlock(BlockPos pos) implements Goal {
        @Override
        public boolean isInGoal(BlockPos footPos) {
            return footPos.equals(pos);
        }

        @Override
        public double heuristic(BlockPos footPos) {
            return footPos.distManhattan(pos);
        }
    }

    /** 目标:进入目标位置的水平范围(垂直须同层,用于寻路到区域附近)。
     * <p>垂直同层是刻意的:掉落物在坑里时,bot 在坑边水平 1 格内但不同层,
     * 若不算「到位」,A* 会继续生成下坑(DOWNWARD)路径——否则会「水平近
     * 垂直远」死等拾取,永远不尝试下坑。</p>
     */
    record GoalNear(BlockPos pos, int radius) implements Goal {
        @Override
        public boolean isInGoal(BlockPos footPos) {
            return Math.abs(footPos.getX() - pos.getX()) <= radius
                    && Math.abs(footPos.getZ() - pos.getZ()) <= radius
                    && footPos.getY() == pos.getY();
        }

        @Override
        public double heuristic(BlockPos footPos) {
            return Math.max(0,
                    Math.abs(footPos.getX() - pos.getX()) + Math.abs(footPos.getZ() - pos.getZ()) - radius)
                    + Math.abs(footPos.getY() - pos.getY());
        }
    }
}
