package com.dddgn.alice.pathing.core.search;

import com.dddgn.alice.pathing.core.MovementType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;

/**
 * 规划期成本模型（架构文档 §7）。启发式必须与本模型同标定且可采纳（见 D-040）。
 *
 * <p>标定来源：Alice 自己实测的段耗时（TRAVERSE 6 / DIAGONAL 8 / ASCEND 10 / DESCEND 16 tick），
 * 相对权重即实测比值；公式结构对照 Baritone `ActionCosts` + 各 Movement 的 `cost()`。
 * <p>启发式见 {@link GoalFoot#heuristic}：octile 水平 + 非对称竖向（扣除垂直移动同时覆盖的水平进度）。
 */
public interface CostModel {
    /**
     * 单位基准：走路 1 格的实测 tick 数（R3 电池证据 2026-09-08，
     * `segment_start` → `segment_done`：TRAVERSE 6 / DIAGONAL 8 / ASCEND 10 / DESCEND 16 tick）。
     *
     * <p>标定原则（D-036 规则 3）：**抄 Baritone 的公式结构，代入 Alice 的实测值**——
     * Baritone 的 4.633 tick/格 是按真实玩家 4.317 格/秒标定的，与 Alice 假人不同。
     */
    double WALK_ONE_BLOCK_TICKS = 6.0D;

    /** 同层直线 1 格：实测 6 tick。 */
    double TRAVERSE_COST = 1.0D;
    /** 同层对角 1 格：实测 8 tick。 */
    double DIAGONAL_COST = 1.33D;
    /** 上升 1 格：实测 10 tick（含起跳/落地）。 */
    double ASCEND_COST = 1.67D;
    /** 下降 1 格：实测 16 tick（**旧模型错记为 1.0，实测它是最贵的动作**）。 */
    double DESCEND_COST = 2.67D;

    /**
     * 垂直下落 1 格（DOWNWARD）：只含 DESCEND 的竖向分量（扣掉它同时覆盖的水平 1 格），
     * 初始值 1.67 走路格；客户端实测后按 D-040 的标定方式修正。
     */
    double DOWNWARD_COST = 1.67D;

    /** 破坏方块的额外固定代价（对照 Baritone `blockBreakAdditionalPenalty = 2 tick`）。 */
    double BREAK_PENALTY_TICKS = 2.0D;
    /** 放置方块的固定代价（对照 Baritone `blockPlacementPenalty = 20 tick`）。 */
    double PLACE_ONE_BLOCK_COST = 20.0D / WALK_ONE_BLOCK_TICKS;

    double cost(MovementType type, ServerLevel level, BlockPos from, BlockPos to);

    CostModel TRAVERSAL = (type, level, from, to) -> switch (type) {
        case TRAVERSE -> TRAVERSE_COST;
        case DIAGONAL -> DIAGONAL_COST;
        case ASCEND -> ASCEND_COST;
        case DESCEND -> DESCEND_COST;
        case DOWNWARD -> DOWNWARD_COST;
        default -> Double.POSITIVE_INFINITY;
    };
}
