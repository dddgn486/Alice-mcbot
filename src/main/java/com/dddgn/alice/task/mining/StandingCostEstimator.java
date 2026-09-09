package com.dddgn.alice.task.mining;

import com.dddgn.alice.pathing.core.search.GoalFoot;
import com.dddgn.alice.pathing.core.search.MovementContext;
import com.dddgn.alice.pathing.core.search.MovementProvider;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.search.PlannedMovement;
import com.dddgn.alice.pathing.core.search.SurfaceMovementProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * 候选站位成本估算（D-067 批次 2；方案见 {@link MiningTuning.EstimateMode}）。
 *
 * <ul>
 *   <li><b>S1 直线下界</b>：用 A* 的 octile 下界（`GoalFoot.heuristic`）排序——最快，
 *       但存在绕行时近邻候选可能排序翻转；</li>
 *   <li><b>S2 Dijkstra 成本场（默认）</b>：从 bot 脚位做**一次纯通行** Dijkstra
 *       （TRAVERSE/DIAGONAL/ASCEND/DESCEND，复用内核 provider 的谓词），
 *       得到半径内每个可达格的**真实纯通行成本**——现成可站候选零损失。</li>
 * </ul>
 *
 * <p>两者都只做"排序用的估算"；最终对 top-K 候选做精确规划（{@link MiningPlanner}）。
 */
public final class StandingCostEstimator {

    /** 估算结果：候选 → 估算成本（走路格数）。不可达候选不在 map 中。 */
    public record Result(MiningTuning.EstimateMode mode, Map<BlockPos, Double> costs,
                         int nodesExpanded, long elapsedMillis) {
        public boolean reachable(BlockPos pos) {
            return costs.containsKey(pos);
        }
    }

    private StandingCostEstimator() {
    }

    public static Result estimate(ServerPlayer bot, ServerLevel level, Collection<BlockPos> candidates) {
        long start = System.nanoTime();
        if (candidates.isEmpty()) {
            return new Result(MiningTuning.estimateMode(), Map.of(), 0, 0L);
        }
        return switch (MiningTuning.estimateMode()) {
            case LOWER_BOUND -> lowerBound(bot, candidates, start);
            case DIJKSTRA -> dijkstra(bot, level, candidates, start);
        };
    }

    /** S1：octile 下界（不可达无法判断，全部返回成本，由精算阶段淘汰）。 */
    private static Result lowerBound(ServerPlayer bot, Collection<BlockPos> candidates, long start) {
        Map<BlockPos, Double> costs = new HashMap<>();
        for (BlockPos candidate : candidates) {
            costs.put(candidate, new GoalFoot(candidate).heuristic(bot.blockPosition()));
        }
        return new Result(MiningTuning.EstimateMode.LOWER_BOUND, costs, 0, millis(start));
    }

    /** S2：一次纯通行 Dijkstra 成本场（半径与节点上限见 {@link MiningTuning}）。 */
    private static Result dijkstra(ServerPlayer bot, ServerLevel level,
                                   Collection<BlockPos> candidates, long start) {
        BlockPos startFoot = bot.blockPosition().immutable();
        PathRequest request = PathRequest.of(bot.getUUID().toString(), startFoot, startFoot);
        MovementContext context = MovementContext.live(bot, level, request);
        MovementProvider provider = new SurfaceMovementProvider();

        Map<BlockPos, Double> dist = new HashMap<>();
        Set<BlockPos> remaining = new HashSet<>(candidates);
        PriorityQueue<BlockPos> queue = new PriorityQueue<>(Comparator.comparingDouble(
                pos -> dist.getOrDefault(pos, Double.POSITIVE_INFINITY)));
        dist.put(startFoot, 0.0D);
        queue.add(startFoot);

        int expanded = 0;
        double maxCost = MiningTuning.costFieldMaxCost();
        int maxNodes = MiningTuning.costFieldMaxNodes();
        List<PlannedMovement> buffer = new ArrayList<>();
        while (!queue.isEmpty() && expanded < maxNodes && !remaining.isEmpty()) {
            BlockPos pos = queue.poll();
            double cost = dist.getOrDefault(pos, Double.POSITIVE_INFINITY);
            if (!Double.isFinite(cost)) {
                continue;
            }
            expanded++;
            remaining.remove(pos);
            if (cost > maxCost) {
                continue;
            }
            buffer.clear();
            provider.appendCandidates(context, pos, buffer);
            for (PlannedMovement movement : buffer) {
                BlockPos next = movement.toFoot();
                double nextCost = cost + movement.cost();
                if (nextCost > maxCost) {
                    continue;
                }
                Double known = dist.get(next);
                if (known == null || nextCost < known) {
                    dist.put(next, nextCost);
                    queue.add(next);
                }
            }
        }

        Map<BlockPos, Double> costs = new HashMap<>();
        for (BlockPos candidate : candidates) {
            Double value = dist.get(candidate);
            if (value != null) {
                costs.put(candidate, value);
            }
        }
        return new Result(MiningTuning.EstimateMode.DIJKSTRA, costs, expanded, millis(start));
    }

    private static long millis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
