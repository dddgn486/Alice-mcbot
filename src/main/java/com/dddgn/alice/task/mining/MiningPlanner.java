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
import java.util.function.BiFunction;

/**
 * 挖掘领域规划器（D-067 批次 2/3）：目标方块 → 两模式站位选择 → 成本估算 → top-K 精算 → MiningPlan。
 *
 * <p>流程（`docs/MINING_STAND_SELECTION_DESIGN.md` v7）：
 * <ol>
 *   <li><b>模式 A</b>（{@link MiningPlan.Mode#CURRENT}/{@link MiningPlan.Mode#DIRECT}）：
 *       当前站位能挖 → 直接用；否则现成可站的多角度候选 + 可挖掘面前提 + 路径成本排序；</li>
 *   <li><b>目标下方无支撑</b>：按成本比较"在目标下方放支撑块 + 侧面站位"与"只从正下方挖"（仅当需要收集掉落物）；</li>
 *   <li><b>模式 B</b>（{@link MiningPlan.Mode#TUNNEL}）：A 无解 → 固定几何集（4 面 × {y,y−1} + 正下方），
 *       到达允许破坏/放置（`PathRequest.miningApproach`，禁用 PILLAR/FALL/DOWNWARD）；</li>
 *   <li><b>兜底</b>（{@link MiningPlan.Mode#ENTER_TARGET}）：以目标格为终点破坏进入，
 *       受 {@link MiningBudget#maxExtraBreakTicks()} 限制，超预算即 `found_but_unminable`。</li>
 * </ol>
 */
public final class MiningPlanner {

    /** 规划结果：plan 为空时仅表示当前规划阶段未产生可用计划。 */
    public record Result(MiningPlan plan, StandingPointEvaluator.StandingPointScore score,
                         String failureReason) {
        public boolean success() {
            return plan != null;
        }
    }

    /** 兼容入口（批次 2 调用点）：默认收集掉落物预算。 */
    public Result plan(ServerPlayer bot, BlockPos target) {
        return plan(bot, target, MiningBudget.collecting(bot, bot.serverLevel(), target));
    }

    public Result plan(ServerPlayer bot, BlockPos target, MiningBudget budget) {
        ServerLevel level = bot.serverLevel();
        BlockPos immutableTarget = target.immutable();
        BlockPos startFoot = bot.blockPosition().immutable();
        double reach = bot.getBlockReach();

        Result direct = planDirect(bot, level, immutableTarget, startFoot, reach, budget);
        if (direct.success()) {
            return direct;
        }
        Result tunnel = planTunnel(bot, level, immutableTarget, startFoot, reach, budget);
        if (tunnel.success()) {
            return tunnel;
        }
        Result enter = planEnterTarget(bot, level, immutableTarget, startFoot, budget);
        if (enter.success()) {
            return enter;
        }
        BotLog.warn("[MiningPlanner] found_but_unminable target={} direct={} tunnel={} enter={} budget={}",
                immutableTarget.toShortString(), direct.failureReason(), tunnel.failureReason(),
                enter.failureReason(), budget.describe());
        return new Result(null, null, "found_but_unminable");
    }

    // ==================== 模式 A ====================

    private Result planDirect(ServerPlayer bot, ServerLevel level, BlockPos target, BlockPos startFoot,
                              double reach, MiningBudget budget) {
        LineOfSightChecker.LineOfSightResult currentLos =
                StandingPointSelector.isValidStandingPoint(level, target, startFoot, reach);
        if (currentLos != null) {
            PathPlan path = planPath(bot, startFoot, startFoot, PathRequest.of(
                    bot.getUUID().toString(), startFoot, startFoot));
            StandingPointEvaluator.StandingPointScore score =
                    StandingPointEvaluator.of(startFoot, 0.0D, 0.0D, currentLos);
            BotLog.info("[MiningPlanner] mode=CURRENT target={} stand={} cost=0",
                    target.toShortString(), startFoot.toShortString());
            return new Result(new MiningPlan(target, startFoot, startFoot, path, currentLos,
                    MiningPlan.Mode.CURRENT, null), score, "");
        }

        List<StandingPointSelector.Candidate> candidates =
                StandingPointSelector.generateCandidates(level, target, startFoot, reach);
        if (candidates.isEmpty()) {
            return new Result(null, null, "no_valid_standing_point");
        }

        boolean floating = !hasSupportBelow(level, target);
        boolean needSupportBlock = floating && budget.collectDrops();
        if (needSupportBlock) {
            List<StandingPointSelector.Candidate> side = new ArrayList<>();
            List<StandingPointSelector.Candidate> below = new ArrayList<>();
            for (StandingPointSelector.Candidate candidate : candidates) {
                if (isSameColumn(candidate.foot(), target)) {
                    below.add(candidate);
                } else {
                    side.add(candidate);
                }
            }
            Result withSupport = selectBest(bot, level, target, startFoot, side, MiningPlan.Mode.DIRECT,
                    target.below(), com.dddgn.alice.pathing.core.search.CostModel.PLACE_ONE_BLOCK_COST);
            Result fromBelow = selectBest(bot, level, target, startFoot, below, MiningPlan.Mode.DIRECT,
                    null, 0.0D);
            Result chosen = cheaper(withSupport, fromBelow);
            if (chosen != null) {
                BotLog.info("[MiningPlanner] floating_target target={} supportOption={} belowOption={} chosen={}",
                        target.toShortString(), withSupport.failureReason().isEmpty() ? "ok" : "-",
                        fromBelow.failureReason().isEmpty() ? "ok" : "-",
                        chosen.plan() == null ? "-" : chosen.plan().mode());
                return chosen;
            }
        }
        Result best = selectBest(bot, level, target, startFoot, candidates, MiningPlan.Mode.DIRECT,
                null, 0.0D);
        return best.plan() != null ? best : new Result(null, null, "no_reachable_standing_point");
    }

    // ==================== 模式 B / 兜底 ====================

    private Result planTunnel(ServerPlayer bot, ServerLevel level, BlockPos target, BlockPos startFoot,
                              double reach, MiningBudget budget) {
        List<BlockPos> candidates = StandingPointSelector.tunnelCandidates(
                bot, level, target, reach, budget.maxExtraBreakTicks());
        if (candidates.isEmpty()) {
            return new Result(null, null, "no_tunnel_standing_point");
        }
        Result result = selectBestApproach(bot, level, target, startFoot, candidates,
                MiningPlan.Mode.TUNNEL);
        return result.plan() != null ? result : new Result(null, null, "no_reachable_tunnel_standing_point");
    }

    private Result planEnterTarget(ServerPlayer bot, ServerLevel level, BlockPos target,
                                   BlockPos startFoot, MiningBudget budget) {
        // 兜底：以目标格为终点（破坏进入），破坏成本受预算限制
        PathPlan path = planPath(bot, startFoot, target,
                PathRequest.miningApproach(bot.getUUID().toString(), startFoot, target));
        if (!path.reached()) {
            return new Result(null, null, "enter_target_unreachable");
        }
        double breakCost = path.totalCost();
        double budgetCost = budget.maxExtraBreakTicks()
                / com.dddgn.alice.pathing.core.search.CostModel.WALK_ONE_BLOCK_TICKS;
        if (breakCost > budgetCost) {
            BotLog.warn("[MiningPlanner] enter_target_over_budget target={} cost={} budget={}",
                    target.toShortString(),
                    String.format(java.util.Locale.ROOT, "%.2f", breakCost),
                    String.format(java.util.Locale.ROOT, "%.2f", budgetCost));
            return new Result(null, null, "enter_target_over_budget");
        }
        LineOfSightChecker.LineOfSightResult los = LineOfSightChecker.checkFromEye(
                level, StandingPointSelector.eyeAt(startFoot), target);
        StandingPointEvaluator.StandingPointScore score =
                StandingPointEvaluator.of(startFoot, path.totalCost(), path.totalCost(), los);
        BotLog.info("[MiningPlanner] mode=ENTER_TARGET target={} startFoot={} cost={}",
                target.toShortString(), startFoot.toShortString(),
                String.format(java.util.Locale.ROOT, "%.3f", path.totalCost()));
        return new Result(new MiningPlan(target, startFoot, target, path, los,
                MiningPlan.Mode.ENTER_TARGET, null), score, "");
    }

    // ==================== 选择与精算 ====================

    /** 模式 A：候选 → 估算 → top-K 精确规划（纯通行请求）。 */
    private Result selectBest(ServerPlayer bot, ServerLevel level, BlockPos target, BlockPos startFoot,
                              List<StandingPointSelector.Candidate> candidates, MiningPlan.Mode mode,
                              BlockPos supportPos, double extraCost) {
        if (candidates.isEmpty()) {
            return new Result(null, null, "no_candidate");
        }
        Map<BlockPos, LineOfSightChecker.LineOfSightResult> losByFoot = new HashMap<>();
        List<BlockPos> feet = new ArrayList<>(candidates.size());
        for (StandingPointSelector.Candidate candidate : candidates) {
            feet.add(candidate.foot());
            losByFoot.put(candidate.foot(), candidate.los());
        }
        return exactTopK(bot, level, target, startFoot, feet, losByFoot, mode, supportPos, extraCost,
                (from, to) -> PathRequest.of(bot.getUUID().toString(), from, to));
    }

    /** 模式 B：候选 → 估算 → top-K 精确规划（挖掘到达请求，允许破坏/放置）。 */
    private Result selectBestApproach(ServerPlayer bot, ServerLevel level, BlockPos target,
                                      BlockPos startFoot, List<BlockPos> feet, MiningPlan.Mode mode) {
        Map<BlockPos, LineOfSightChecker.LineOfSightResult> losByFoot = new HashMap<>();
        for (BlockPos foot : feet) {
            losByFoot.put(foot, LineOfSightChecker.checkFromEye(level,
                    StandingPointSelector.eyeAt(foot), target));
        }
        return exactTopK(bot, level, target, startFoot, feet, losByFoot, mode, null, 0.0D,
                (from, to) -> PathRequest.miningApproach(bot.getUUID().toString(), from, to));
    }

    private Result exactTopK(ServerPlayer bot, ServerLevel level, BlockPos target, BlockPos startFoot,
                             List<BlockPos> feet,
                             Map<BlockPos, LineOfSightChecker.LineOfSightResult> losByFoot,
                             MiningPlan.Mode mode, BlockPos supportPos, double extraCost,
                             BiFunction<BlockPos, BlockPos, PathRequest> requestFactory) {
        StandingCostEstimator.Result estimate = StandingCostEstimator.estimate(bot, level, feet);
        List<BlockPos> ranked = new ArrayList<>(estimate.costs().keySet());
        ranked.sort(Comparator.comparingDouble(estimate.costs()::get));

        StandingPointEvaluator.StandingPointScore best = null;
        PathPlan bestPath = null;
        int planned = 0;
        int k = Math.min(MiningTuning.exactTopK(), ranked.size());
        while (true) {
            for (int i = planned; i < k; i++) {
                BlockPos foot = ranked.get(i);
                PathPlan path = planPath(bot, startFoot, foot, requestFactory.apply(startFoot, foot));
                if (!path.reached()) {
                    continue;
                }
                double cost = path.totalCost() + extraCost;
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
                        ? estimate.costs().get(ranked.get(k)) + extraCost
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
            return new Result(null, null, "no_reachable_candidate");
        }
        BotLog.info("[MiningPlanner] mode={} target={} startFoot={} candidates={} estimate={} nodes={} ms={}"
                        + " planned={} chosen={} cost={} pathSize={} support={} los={}",
                mode, target.toShortString(), startFoot.toShortString(), feet.size(), estimate.mode(),
                estimate.nodesExpanded(), estimate.elapsedMillis(), planned,
                best.getPosition().toShortString(),
                String.format(java.util.Locale.ROOT, "%.3f", best.getScore()),
                bestPath.movements().size(),
                supportPos == null ? "-" : supportPos.toShortString(),
                best.getLineOfSightResult().isClear());
        MiningPlan plan = new MiningPlan(target, startFoot, best.getPosition(), bestPath,
                best.getLineOfSightResult(), mode, supportPos);
        return new Result(plan, best, "");
    }

    private static Result cheaper(Result first, Result second) {
        boolean firstOk = first.plan() != null;
        boolean secondOk = second.plan() != null;
        if (firstOk && secondOk) {
            return first.score().getScore() <= second.score().getScore() ? first : second;
        }
        if (firstOk) {
            return first;
        }
        return secondOk ? second : null;
    }

    private static boolean isSameColumn(BlockPos pos, BlockPos target) {
        return pos.getX() == target.getX() && pos.getZ() == target.getZ();
    }

    private static boolean hasSupportBelow(ServerLevel level, BlockPos target) {
        BlockPos below = target.below();
        return !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
    }

    private static PathPlan planPath(ServerPlayer bot, BlockPos startFoot, BlockPos standingFoot,
                                     PathRequest request) {
        return new CorePathPlanner().plan(bot, bot.serverLevel(), request);
    }
}
