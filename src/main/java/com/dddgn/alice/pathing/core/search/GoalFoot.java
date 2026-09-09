package com.dddgn.alice.pathing.core.search;

import net.minecraft.core.BlockPos;

import java.util.Objects;

/** 精确脚位目标。启发式见 {@link #heuristic}（octile 水平 + 非对称竖向，与 CostModel 同标定）。 */
public record GoalFoot(BlockPos foot) implements GoalSpec {
    public GoalFoot {
        foot = Objects.requireNonNull(foot, "foot").immutable();
    }

    @Override
    public boolean isInGoal(BlockPos pos) {
        return foot.equals(pos);
    }

    /**
     * 启发式 = 水平 octile 成本 + 非对称竖向成本（D-040）。
     *
     * <p>水平：与 Movement 集合一致的 octile（直线段 + 对角段），单价取实测值
     * {@link CostModel#TRAVERSE_COST}/{@link CostModel#DIAGONAL_COST}。
     * <p>竖向：方向相关，且**扣除垂直移动同时覆盖的那 1 格水平进度**
     * （ASCEND 成本 1.67 里含 1.0 的水平进度 → 竖向增量 0.67；DESCEND 同理 1.67）。
     * 这样既对齐 Baritone `GoalBlock.calculate = GoalYLevel + GoalXZ` 的结构，
     * 又保证对 Alice 实测成本**可采纳且一致**（旧公式在斜向下降时高估 1.0）。
     */
    @Override
    public double heuristic(BlockPos pos) {
        int dx = Math.abs(foot.getX() - pos.getX());
        int dz = Math.abs(foot.getZ() - pos.getZ());
        int dy = foot.getY() - pos.getY();
        int straight = Math.max(dx, dz) - Math.min(dx, dz);
        int diagonal = Math.min(dx, dz);
        double h = straight * CostModel.TRAVERSE_COST + diagonal * CostModel.DIAGONAL_COST;
        if (dy > 0) {
            h += dy * (CostModel.ASCEND_COST - CostModel.TRAVERSE_COST);
        } else if (dy < 0) {
            h += -dy * (CostModel.DESCEND_COST - CostModel.TRAVERSE_COST);
        }
        return h;
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
