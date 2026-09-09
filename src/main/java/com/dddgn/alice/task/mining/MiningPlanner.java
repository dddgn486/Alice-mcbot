package com.dddgn.alice.task.mining;

import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 挖掘领域规划器（D-067 批次 2 重写）：目标方块 → 站位候选 → 成本估算 → top-K 精算 → MiningPlan。
 *
 * <p>流程（`docs/MINING_STAND_SELECTION_DESIGN.md` v7 §2.2）：
 * <ol>
 *   <li>当前站位"能挖到" → 直接采用（成本 0）；</li>
 *   <li>否则 {@link StandingPointSelector} 生成模式 A 候选（现成可站 + 可挖掘面前提）；</li>
 *   <li>{@link StandingCostEstimator} 估算各候选的到达成本（S1 下界 / S2 Dijkstra 成本场，可切换）；</li>
 *   <li>按估算排序取 top-K，逐个用 `CorePathPlanner`（`PathRequest.of` 纯通行）**精确规划**；</li>
 *   <li>取精确成本最小者；若"最优精确成本 > 第 K+1 名估算"，K 递增重试（上限见 {@link MiningTuning}）。</li>
 * </ol>
 *
 * <p>不负责任务生命周期、运行时重规划、清障或掉落物拾取。
 */
public final class MiningPlanner {

    /** 规划结果：plan 为空时仅表示当前规划阶段未产生可用计划。 */
    public record Result(MiningPlan plan, StandingPointEvaluator.StandingPointScore score,
                         String failureReason) {
        public boolean success() {
            return plan != null;
        }
    }

    public Result plan(ServerPlayer bot, BlockPos target) {
        ServerLevel level = bot.serverLevel();
        BlockPos immutableTarget = target.immutable();
        BlockPos startFoot = bot.blockPosition().immutable();
        double reach = bot.getBlockReach();

        // 1) 当前站位即可挖掘
        LineOfSightChecker.LineOfSightResult currentLos = StandingPointSelector.isValidStandingPoint(
                level, immutableTarget, startFoot, reach);
        if (currentLos != null) {
            PathPlan path = planPath(bot, startFoot, startFoot);
            StandingPointEvaluator.StandingPointScore score =
                    StandingPointEvaluator.of(startFoot, 0.0D, 0.0D, currentLos);
            BotLog.info("[MiningPlanner] mode=current target={} stand={} cost=0",
                    immutableTarget.toShortString(), startFoot.toShortString());
            return new Result(new MiningPlan(immutableTarget, startFoot, startFoot, path, currentLos),
                    score, "");
        }

        // 2) 生成候选
        List<StandingPointSelector.Candidate> candidates =
                StandingPointSelector.generateCandidates(level, immutableTarget, startFoot, reach);
        if (candidates.isEmpty()) {
            BotLog.warn("[MiningPlanner] no_valid_standing_point target={} startFoot={} reach={}",
                    immutableTarget.toShortString(), startFoot.toShortString(), reach);
            return new Result(null, null, "no_valid_standing_point");
        }
        Map<BlockPos, LineOfSightChecker.LineOfSightResult> losByFoot = new HashMap<>();
        List<BlockPos> feet = new ArrayList<>(candidates.size());
        for (StandingPointSelector.Candidate candidate : candidates) {
            feet.add(candidate.foot());
            losByFoot.put(candidate.foot(), candidate.los());
        }

        // 3) 成本估算 + 排序
        StandingCostEstimator.Result estimate = StandingCostEstimator.estimate(bot, level, feet);
        List<BlockPos> ranked = new ArrayList<>(estimate.costs().keySet());
        ranked.sort(Comparator.comparingDouble(estimate.costs()::get));

        // 4) top-K 精算（K 递增直到"最优精确成本 ≤ 第 K+1 名估算"或触顶）
        StandingPointEvaluator.StandingPointScore best = null;
        PathPlan bestPath = null;
        int planned = 0;
        int k = Math.min(MiningTuning.exactTopK(), ranked.size());
        while (true) {
            for (int i = planned; i < k; i++) {
                BlockPos foot = ranked.get(i);
                PathPlan path = planPath(bot, startFoot, foot);
                if (!path.reached()) {
                    continue;
                }
                double cost = path.totalCost();
                if (best == null || cost < best.getScore()) {
                    best = StandingPointEvaluator.of(foot, cost, estimate.costs().get(foot),
                            losByFoot.get(foot));
                    bestPath = path;
                }
            }
            planned = k;
            boolean canExpand = k < ranked.size() && k < MiningTuning.exactTopKMax();
            if (best != null) {
                double nextEstimate = k < ranked.size()
                        ? estimate.costs().get(ranked.get(k))
                        : Double.POSITIVE_INFINITY;
                if (best.getScore() <= nextEstimate || !canExpand) {
                    break;
                }
            } else if (!canExpand) {
                break;
            }
            k = Math.min(k + 2, ranked.size());
        }

        if (best == null) {
            BotLog.warn("[MiningPlanner] no_reachable_standing_point target={} candidates={} estimate={} planned={}",
                    immutableTarget.toShortString(), candidates.size(), estimate.mode(), planned);
            return new Result(null, null, "no_reachable_standing_point");
        }

        BotLog.info("[MiningPlanner] target={} startFoot={} candidates={} estimate={} nodes={} ms={}"
                        + " planned={} chosen={} cost={} pathSize={} los={}",
                immutableTarget.toShortString(), startFoot.toShortString(), candidates.size(),
                estimate.mode(), estimate.nodesExpanded(), estimate.elapsedMillis(), planned,
                best.getPosition().toShortString(),
                String.format(java.util.Locale.ROOT, "%.3f", best.getScore()),
                bestPath.movements().size(), best.getLineOfSightResult().isClear());
        MiningPlan plan = new MiningPlan(immutableTarget, startFoot, best.getPosition(), bestPath,
                best.getLineOfSightResult());
        return new Result(plan, best, "");
    }

    private static PathPlan planPath(ServerPlayer bot, BlockPos startFoot, BlockPos standingFoot) {
        PathRequest request = PathRequest.of(bot.getUUID().toString(), startFoot, standingFoot);
        return new CorePathPlanner().plan(bot, bot.serverLevel(), request);
    }
}
