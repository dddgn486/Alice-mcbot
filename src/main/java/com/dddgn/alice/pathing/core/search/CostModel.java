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
     * 垂直下落 1 格（DOWNWARD）的**下落与稳定**部分，0.9 走路格。
     *
     * <p>实测标定（2026-09-09 客户端）：`(0,64,45)→(0,63,45)` 整段 11 tick，
     * 其中破坏（钻石镐 5.6 tick，规划期估计值）之后的下落+稳定 ≈5.4 tick → 0.9 走路格。
     * 破坏成本另行按 `estimateBreakTicks` 计入。
     */
    double DOWNWARD_COST = 0.9D;

    /** 破坏方块的额外固定代价（对照 Baritone `blockBreakAdditionalPenalty = 2 tick`）。 */
    double BREAK_PENALTY_TICKS = 2.0D;
    /** 放置方块的固定代价（对照 Baritone `blockPlacementPenalty = 20 tick`）。 */
    double PLACE_ONE_BLOCK_COST = 20.0D / WALK_ONE_BLOCK_TICKS;

    /** 垂直上升 1 格（PILLAR）：跳跃（ASCEND 的竖向分量）+ 放置一个方块。 */
    double PILLAR_COST = ASCEND_COST + PLACE_ONE_BLOCK_COST;

    // ==================== FALL（落差 2~3 格，D-058） ====================

    /**
     * 逐格下落 tick（Baritone `ActionCosts.FALL_N_BLOCKS_COST = distanceToTicks(n)`，
     * 速度曲线 `(0.98^t - 1) * -3.92`）：1 格 4.61 / 2 格 6.79 / 3 格 8.46 tick。
     * 下落物理与假人一致（同为原版玩家实体），因此可直接用该曲线。
     */
    double FALL_ONE_BLOCK_TICKS = 4.61D;
    double FALL_TWO_BLOCKS_TICKS = 6.79D;
    double FALL_THREE_BLOCKS_TICKS = 8.46D;
    /** 走离边缘（0.8 格）与落地后居中（0.2 格）——Baritone `WALK_OFF_BLOCK_COST` / `CENTER_AFTER_FALL_COST` 结构。 */
    double WALK_OFF_EDGE_COST = TRAVERSE_COST * 0.8D;
    double CENTER_AFTER_FALL_COST = TRAVERSE_COST * 0.2D;
    /**
     * 段固定开销：用 Alice 实测 `DESCEND` 16 tick 反推（物理模型只解释 0.8+4.61/6+0.2），
     * 残差 ≈ 5.4 tick 就是 Alice 每段的逼近/落地稳定开销。
     */
    double FALL_SEGMENT_OVERHEAD_COST = DESCEND_COST - WALK_OFF_EDGE_COST
            - FALL_ONE_BLOCK_TICKS / WALK_ONE_BLOCK_TICKS - CENTER_AFTER_FALL_COST;
    /** 落差 2 格：≈3.03 走路格（18.2 tick，待客户端实测校正）。 */
    double FALL_TWO_BLOCK_COST = FALL_SEGMENT_OVERHEAD_COST + WALK_OFF_EDGE_COST
            + FALL_TWO_BLOCKS_TICKS / WALK_ONE_BLOCK_TICKS + CENTER_AFTER_FALL_COST;
    /** 落差 3 格：≈3.31 走路格（19.9 tick，待客户端实测校正）。 */
    double FALL_THREE_BLOCK_COST = FALL_SEGMENT_OVERHEAD_COST + WALK_OFF_EDGE_COST
            + FALL_THREE_BLOCKS_TICKS / WALK_ONE_BLOCK_TICKS + CENTER_AFTER_FALL_COST;

    double cost(MovementType type, ServerLevel level, BlockPos from, BlockPos to);

    CostModel TRAVERSAL = (type, level, from, to) -> switch (type) {
        case TRAVERSE -> TRAVERSE_COST;
        case DIAGONAL -> DIAGONAL_COST;
        case ASCEND -> ASCEND_COST;
        case DESCEND -> DESCEND_COST;
        case DOWNWARD -> DOWNWARD_COST;
        case PILLAR -> PILLAR_COST;
        case BREAK_AND_ENTER -> TRAVERSE_COST;   // 破坏成本由 provider 累加
        case FALL -> (to.getY() - from.getY()) == -3 ? FALL_THREE_BLOCK_COST : FALL_TWO_BLOCK_COST;
        default -> Double.POSITIVE_INFINITY;
    };
}
