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
