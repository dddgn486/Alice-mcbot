package com.dddgn.alice.pathing.core.search;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Objects;

/**
 * 规划输出（架构文档 §5.3）。
 *
 * <p>{@code List<BlockPos>} 只是兼容投影；规范输出是 {@link PlannedMovement} 序列。
 */
public record PathPlan(
        PlanningStatus status,
        BlockPos startFoot,
        BlockPos goalFoot,
        List<PlannedMovement> movements,
        List<BlockPos> projectedFootPath,
        double totalCost,
        int nodesExpanded,
        int movementsConsidered,
        long elapsedMillis,
        String plannerName,
        String diagnostics
) {
    public PathPlan {
        status = Objects.requireNonNull(status, "status");
        startFoot = Objects.requireNonNull(startFoot, "startFoot").immutable();
        goalFoot = Objects.requireNonNull(goalFoot, "goalFoot").immutable();
        movements = List.copyOf(Objects.requireNonNull(movements, "movements"));
        projectedFootPath = projectedFootPath == null
                ? List.of()
                : projectedFootPath.stream().map(BlockPos::immutable).toList();
        plannerName = plannerName == null ? "unknown" : plannerName;
        diagnostics = diagnostics == null ? "" : diagnostics;
    }

    public boolean reached() {
        return status == PlanningStatus.REACHED;
    }

    /**
     * ⭐ **路径实际到达的落点脚位**（`D-520`，改革 ① 第一刀）：{@link #projectedFootPath()} 的**末项**。
     *
     * <p><b>为什么必须与 {@link #goalFoot()} 分开</b>：{@code goalFoot()} 是 **{@link GoalSpec} 自报的锚点**，
     * 对精确目标（{@link GoalFoot}）它**恰好**就是落点，于是两者长期被当成一回事；
     * 但对**谓词目标**（{@link GoalAdjacent}）它是 **目标方块本身**，根本不是脚位
     * （`GoalAdjacent.goalFoot()` javadoc 逐字：「返回的是**目标方块**，不是"规范脚位"」）⇒
     * 任何需要"落到哪一格"的消费者（`ReachPlan` 的不变量、执行期的走位终点）**不许**读 {@code goalFoot()}。
     * 改革 ① 把 B 腿换成 {@code GoalAdjacent} 之后，这个区分从"将来时"变成"编译期就会撞上"。
     *
     * <p><b>取值是纯派生，不新增字段</b>：{@link #projectedFootPath()} 的第一项是起点、其后每条边追加一个
     * {@code toFoot()}（`AStarMovementSearch.projectedFootPath`）⇒ **末项 = 落点**，与 `goalFoot()` 无关。
     *
     * @return 落点脚位；{@code projectedFootPath} 为空（失败 / 无计划）⇒ {@code null}
     */
    public BlockPos finalFoot() {
        return projectedFootPath.isEmpty()
                ? null
                : projectedFootPath.get(projectedFootPath.size() - 1);
    }

    /** K-1：**只到前缀**（未到达目标、但有可执行的边）。调用方可用它"先走一段再重规划"。 */
    public boolean partial() {
        return status == PlanningStatus.PARTIAL;
    }

    /** K-1：用 best-so-far 前缀构造部分计划（`movements` 非空；`reached()` = false）。 */
    public static PathPlan partial(BlockPos startFoot, BlockPos goalFoot, List<PlannedMovement> movements,
                                  List<BlockPos> projectedFootPath, double bestCost, int nodesExpanded,
                                  int movementsConsidered, long elapsedMillis, String plannerName,
                                  String diagnostics) {
        return new PathPlan(PlanningStatus.PARTIAL, startFoot, goalFoot, movements, projectedFootPath,
                bestCost, nodesExpanded, movementsConsidered, elapsedMillis, plannerName, diagnostics);
    }

    /** 无路径结果（含不可达、预算耗尽、取消）。 */
    public static PathPlan failure(PlanningStatus status, BlockPos startFoot, BlockPos goalFoot,
                                   int nodesExpanded, int movementsConsidered, long elapsedMillis,
                                   String plannerName, String diagnostics) {
        return new PathPlan(status, startFoot, goalFoot, List.of(), List.of(),
                Double.POSITIVE_INFINITY, nodesExpanded, movementsConsidered,
                elapsedMillis, plannerName, diagnostics);
    }

    public String summary() {
        return "status=" + status
                + " movements=" + movements.size()
                + " cost=" + (Double.isInfinite(totalCost) ? "INF" : String.format("%.2f", totalCost))
                + " nodes=" + nodesExpanded
                + " considered=" + movementsConsidered
                + " elapsedMs=" + elapsedMillis
                + " planner=" + plannerName;
    }
}
