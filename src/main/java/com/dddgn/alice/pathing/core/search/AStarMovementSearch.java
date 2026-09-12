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
    private static final double[] COEFFICIENTS = {1.5D, 2.0D, 2.5D, 3.0D, 4.0D, 5.0D, 10.0D};

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

        // S-2（P1-A / 审计 §3.A:181，2026-09-12）：**目标准入** —— 目标区块没加载就硬拒，
        // 返回**独立状态** `GOAL_NOT_LOADED`（不是 UNREACHABLE、不是 SEARCH_LIMIT）。
        // 理由：服务端读未加载区块会同步加载/生成并阻塞主线程；"没加载"也不等于"到不了"。
        // 对照 Baritone：从不加载区块（`getChunk(..., FULL, false)`），执行期在
        // `PathExecutor:188` "Pausing since destination is at edge of loaded chunks" 等区块。
        if (!context.chunkLoaded(goal.goalFoot())) {
            return PathPlan.failure(PlanningStatus.GOAL_NOT_LOADED, startFoot, goal.goalFoot(),
                    0, 0, elapsed(startMillis), PLANNER_NAME,
                    "goal_chunk_not_loaded goal=" + goal.goalFoot().toShortString()
                            + " chunk=" + (goal.goalFoot().getX() >> 4) + ","
                            + (goal.goalFoot().getZ() >> 4));
        }

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
        // 门控计数（诚实报告用）：跳过多少条"会跨到未加载区块"或"越出世界边界"的边
        int skippedUnloaded = 0;
        int skippedBorder = 0;
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
                BlockPos toFoot = movement.toFoot();
                // S-2 节点级门控（对照 Baritone `AStarPathFinder:105-112`）：
                // **只在跨越区块边界时**才查一次"目的地区块是否已加载"，未加载 ⇒ 跳过这条边
                // （`continue`，不是把整条路径判死）。这样搜索**永远不会去读未加载区块的方块**，
                // 也就不会触发服务端的同步加载/生成（主线程阻塞 + 世界副作用）。
                if ((toFoot.getX() >> 4) != (current.x >> 4)
                        || (toFoot.getZ() >> 4) != (current.z >> 4)) {
                    if (!context.chunkLoaded(toFoot)) {
                        skippedUnloaded++;
                        continue;
                    }
                }
                // 世界边界（对照 Baritone `worldBorder.entirelyContains`）：越界的边一律不生成
                if (!context.withinWorldBorder(toFoot)) {
                    skippedBorder++;
                    continue;
                }
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
                            + ", openSet=" + openSet.size() + ", best=" + bestSoFar[0].cost
                            + ", skipped_unloaded=" + skippedUnloaded + " skipped_border=" + skippedBorder + ")");
        }
        return PathPlan.failure(PlanningStatus.UNREACHABLE, startFoot, goal.goalFoot(),
                expandedNodes, movementsConsidered, elapsed, PLANNER_NAME,
                "open set exhausted; best=" + bestSoFar[0].cost
                        + "; skipped_unloaded=" + skippedUnloaded + " skipped_border=" + skippedBorder);
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
