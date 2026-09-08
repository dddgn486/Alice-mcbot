package com.dddgn.alice.pathing.core.search;

import net.minecraft.core.BlockPos;

import java.util.Objects;

/** 精确脚位目标。启发式 = 水平欧氏距离 + 垂直差（两者都是各自成本模型的合法下界）。 */
public record GoalFoot(BlockPos foot) implements GoalSpec {
    public GoalFoot {
        foot = Objects.requireNonNull(foot, "foot").immutable();
    }

    @Override
    public boolean isInGoal(BlockPos pos) {
        return foot.equals(pos);
    }

    @Override
    public double heuristic(BlockPos pos) {
        int dx = foot.getX() - pos.getX();
        int dy = foot.getY() - pos.getY();
        int dz = foot.getZ() - pos.getZ();
        return Math.sqrt((double) dx * dx + (double) dz * dz) + Math.abs(dy);
    }

    @Override
    public BlockPos goalFoot() {
        return foot;
    }

    @Override
    public String describe() {
        return "foot:" + foot.toShortString();
    }
}
