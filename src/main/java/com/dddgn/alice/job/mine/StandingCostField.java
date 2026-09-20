package com.dddgn.alice.job.mine;

import com.dddgn.alice.job.Candidate;
import com.dddgn.alice.job.GoalSpec;
import com.dddgn.alice.task.mining.StandingCostEstimator;
import com.dddgn.alice.task.mining.StandingPointSelector;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * **真·通行成本场**：一次 Dijkstra 覆盖"所有候选的站位点"，而不是每个候选跑一遍规划器。
 *
 * <p>为什么这么设计（`D-329` §2.1 成本契约）：`MiningPlanner.plan` 是**逐目标重**的（实测 `nodes=185` 量级）
 * ⇒ 放进选择循环里会随候选数线性爆炸。`StandingCostEstimator.estimate(bot, level, 站位点集合)` 一次算完
 * 一张成本场，取每个候选**最优站位点**的成本即可 ⇒ 成本**有界**（`estimatedCells` 可测）。
 *
 * <p>⚠️ **一次性**（用户 2026-09-20 裁定其三）：成本场以 **bot 当前位置**为源 ⇒ 选完一个目标 bot 就动了，
 * 这张场**立刻失效**。本类**不缓存**（每次 {@link #estimate} 重算），并把 `estimatedCells` 报出去，
 * 让"每 tick 重算"这种退化在日志里**看得见**。
 *
 * <p>⚠️ **成本 ≠ 可达性判决**：估不出（站位点不可达/超上限）只让该候选排到最后，**不拒绝**它 ——
 * 真正的可达性由 `MineTask` 的规划器说了算（`SEARCH_LIMIT ≠ UNREACHABLE`）。
 */
public final class StandingCostField implements CandidateCostProvider {

    /** 每个候选最多考虑几个站位点（成本上限的另一半：站位点枚举本身也要有界）。 */
    public static final int STANDING_POINTS_PER_CANDIDATE = 8;

    /** 一次成本场最多放多少格（超了就**如实截断**并把 `note` 写清楚）。 */
    public static final int MAX_COST_FIELD_CELLS = 512;

    private final int standingPointsPerCandidate;
    private final int maxCells;

    public StandingCostField() {
        this(STANDING_POINTS_PER_CANDIDATE, MAX_COST_FIELD_CELLS);
    }

    public StandingCostField(int standingPointsPerCandidate, int maxCells) {
        this.standingPointsPerCandidate = standingPointsPerCandidate;
        this.maxCells = maxCells;
    }

    @Override
    public Result estimate(ServerPlayer bot, GoalSpec spec, List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return Result.empty("dijkstra cells=0（无候选）");
        }
        ServerLevel level = bot.serverLevel();
        double reach = bot.getBlockReach();
        Map<Long, List<BlockPos>> standingByAnchor = new HashMap<>();
        Set<BlockPos> field = new LinkedHashSet<>();
        boolean truncated = false;
        for (Candidate candidate : candidates) {
            List<BlockPos> standings = new ArrayList<>();
            for (StandingPointSelector.Candidate point
                    : StandingPointSelector.generateCandidates(level, candidate.anchor(),
                    bot.blockPosition(), reach)) {
                if (standings.size() >= standingPointsPerCandidate) {
                    break;
                }
                if (field.size() >= maxCells) {
                    truncated = true;
                    break;
                }
                if (field.add(point.foot())) {
                    standings.add(point.foot());
                } else {
                    standings.add(point.foot());   // 已被别的候选放进场里，直接复用
                }
            }
            standingByAnchor.put(candidate.anchor().asLong(), standings);
        }
        if (field.isEmpty()) {
            return new Result(Map.of(), 0, "dijkstra cells=0（所有候选都没枚举出站位点）");
        }
        StandingCostEstimator.Result costs = StandingCostEstimator.estimate(bot, level, field);
        Map<Long, Double> travel = new HashMap<>();
        for (Candidate candidate : candidates) {
            List<BlockPos> standings = standingByAnchor.getOrDefault(candidate.anchor().asLong(), List.of());
            double best = Double.POSITIVE_INFINITY;
            for (BlockPos foot : standings) {
                Double cost = costs.costs().get(foot);
                if (cost != null && cost < best) {
                    best = cost;
                }
            }
            if (best != Double.POSITIVE_INFINITY) {
                travel.put(candidate.anchor().asLong(), best);
            }
        }
        return new Result(travel, field.size(),
                "dijkstra mode=" + costs.mode() + " cells=" + field.size()
                        + " candidates=" + candidates.size() + (truncated ? "（截断到格数上限）" : ""));
    }
}
