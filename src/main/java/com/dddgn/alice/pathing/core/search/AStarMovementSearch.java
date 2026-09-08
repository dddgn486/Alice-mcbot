package com.dddgn.alice.pathing.core.search;

import com.dddgn.alice.pathing.core.RecoverabilityLevel;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Movement-aware A*（对照 Baritone `AStarPathFinder`）。
 *
 * <p>特性：
 * <ul>
 *   <li>二叉堆开放集 + decrease-key；</li>
 *   <li>加权启发式（多系数 best-so-far，用于预算耗尽时的诊断）；</li>
 *   <li>预算与取消边界：节点数 / 墙钟毫秒 / 取消信号；</li>
 *   <li>状态严格区分 {@code REACHED} / {@code UNREACHABLE} / {@code SEARCH_LIMIT} / {@code CANCELLED}。</li>
 * </ul>
 *
 * <p>R3 为同步主线程搜索；异步（R7）只需把 {@link MovementContext} 的实时世界换成快照。
 */
public final class AStarMovementSearch {
    public static final String PLANNER_NAME = "alice.astar.movement.v1";

    /** 加权 A* 系数（越大越贪心）；第 0 项用于最终路径。 */
    private static final double[] COEFFICIENTS = {1.5D, 2.0D, 2.5D, 4.5D};

    private final MovementProvider provider;

    public AStarMovementSearch(MovementProvider provider) {
        this.provider = provider;
    }

    public PathPlan search(MovementContext context) {
        long startMillis = System.currentTimeMillis();
        PathRequest request = context.request();
        BlockPos startFoot = request.startFoot();
        GoalSpec goal = request.goal();
        SearchBudget budget = request.budget();

        Map<Long, SearchNode> nodes = new HashMap<>();
        BinaryHeapOpenSet openSet = new BinaryHeapOpenSet();
        List<PlannedMovement> candidates = new ArrayList<>(16);

        SearchNode startNode = nodeAt(nodes, startFoot, goal);
        startNode.cost = 0.0D;
        startNode.combinedCost = startNode.estimatedCostToGoal;
        openSet.insert(startNode);

        SearchNode[] bestSoFar = new SearchNode[COEFFICIENTS.length];
        double[] bestHeuristic = new double[COEFFICIENTS.length];
        for (int i = 0; i < COEFFICIENTS.length; i++) {
            bestSoFar[i] = startNode;
            bestHeuristic[i] = startNode.estimatedCostToGoal;
        }

        int expandedNodes = 0;
        int movementsConsidered = 0;
        boolean budgetExhausted = false;

        while (!openSet.isEmpty()) {
            if (budget.isCancelled()) {
                return PathPlan.failure(PlanningStatus.CANCELLED, startFoot, goal.goalFoot(),
                        expandedNodes, movementsConsidered, elapsed(startMillis), PLANNER_NAME,
                        "cancelled");
            }
            long elapsed = elapsed(startMillis);
            if (budget.nodeBudgetExhausted(expandedNodes) || budget.timeBudgetExhausted(elapsed)) {
                budgetExhausted = true;
                break;
            }

            SearchNode current = openSet.removeLowest();
            expandedNodes++;
            BlockPos currentFoot = new BlockPos(current.x, current.y, current.z);

            if (goal.isInGoal(currentFoot)) {
                return reachedPlan(startFoot, goal, current, expandedNodes, movementsConsidered,
                        elapsed(startMillis));
            }

            candidates.clear();
            provider.appendCandidates(context, currentFoot, candidates);
            for (PlannedMovement movement : candidates) {
                movementsConsidered++;
                double tentativeCost = current.cost + movement.cost();
                SearchNode neighbor = nodeAt(nodes, movement.toFoot(), goal);
                if (tentativeCost >= neighbor.cost) {
                    continue;
                }
                neighbor.previous = current;
                neighbor.previousFoot = currentFoot;
                neighbor.previousType = movement.movementType();
                neighbor.previousCost = movement.cost();
                neighbor.cost = tentativeCost;
                neighbor.combinedCost = tentativeCost + neighbor.estimatedCostToGoal;
                if (neighbor.isOpen()) {
                    openSet.update(neighbor);
                } else {
                    openSet.insert(neighbor);
                }
                for (int i = 0; i < COEFFICIENTS.length; i++) {
                    double metric = neighbor.estimatedCostToGoal + neighbor.cost / COEFFICIENTS[i];
                    if (metric < bestHeuristic[i]) {
                        bestHeuristic[i] = metric;
                        bestSoFar[i] = neighbor;
                    }
                }
            }
        }

        long elapsed = elapsed(startMillis);
        if (budgetExhausted) {
            return PathPlan.failure(PlanningStatus.SEARCH_LIMIT, startFoot, goal.goalFoot(),
                    expandedNodes, movementsConsidered, elapsed, PLANNER_NAME,
                    "budget exhausted (maxNodes=" + budget.maxNodes() + ", maxMillis=" + budget.maxMillis()
                            + ", openSet=" + openSet.size() + ", best=" + bestSoFar[0].cost + ")");
        }
        return PathPlan.failure(PlanningStatus.UNREACHABLE, startFoot, goal.goalFoot(),
                expandedNodes, movementsConsidered, elapsed, PLANNER_NAME,
                "open set exhausted; best=" + bestSoFar[0].cost);
    }

    private PathPlan reachedPlan(BlockPos startFoot, GoalSpec goal, SearchNode goalNode,
                                 int expandedNodes, int movementsConsidered, long elapsed) {
        List<PlannedMovement> movements = new ArrayList<>();
        SearchNode node = goalNode;
        while (node.previous != null) {
            movements.add(new PlannedMovement(node.previousType, node.previousFoot,
                    new BlockPos(node.x, node.y, node.z), node.previousCost,
                    RecoverabilityLevel.LOCAL_STEP));
            node = node.previous;
        }
        Collections.reverse(movements);
        // TEMP PROBE（定位 plan 终点与 goal 不一致，验证后删除）
        com.dddgn.alice.log.BotLog.info("[PlanProbe] goal={} goalNode=({},{},{}) edges={} last={}",
                goal.goalFoot().toShortString(), goalNode.x, goalNode.y, goalNode.z, movements.size(),
                movements.isEmpty() ? "-" : movements.get(movements.size() - 1).toFoot().toShortString());

        List<BlockPos> projected = new ArrayList<>(movements.size() + 1);
        projected.add(startFoot);
        for (PlannedMovement movement : movements) {
            projected.add(movement.toFoot());
        }
        return new PathPlan(PlanningStatus.REACHED, startFoot, goal.goalFoot(),
                movements, projected, goalNode.cost, expandedNodes, movementsConsidered,
                elapsed, PLANNER_NAME, "goal reached");
    }

    private static SearchNode nodeAt(Map<Long, SearchNode> nodes, BlockPos pos, GoalSpec goal) {
        return nodes.computeIfAbsent(SearchNode.hash(pos.getX(), pos.getY(), pos.getZ()), key -> {
            SearchNode created = new SearchNode(pos.getX(), pos.getY(), pos.getZ());
            created.estimatedCostToGoal = goal.heuristic(pos);
            return created;
        });
    }

    private static long elapsed(long startMillis) {
        return Math.max(0L, System.currentTimeMillis() - startMillis);
    }
}
