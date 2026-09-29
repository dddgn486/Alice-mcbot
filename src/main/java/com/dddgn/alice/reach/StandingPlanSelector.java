package com.dddgn.alice.reach;

import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.search.SearchConclusion;
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
 * ⭐⭐ <b>站位选优的「选」</b>（`plans §4.2`① 逐字：「CURRENT 快路径 ＋ 候选枚举 ＋ LOS/触及过滤 ＋ 排序 ＋ 最优」）。
 *
 * <p>它是 {@code MiningPlanner} 解体的产物（`DS-5`，`plans §4.3` 甲）：这段逻辑**逐字**从
 * {@code task/mining/MiningPlanner} 搬来，**一行逻辑都没改**，只做了三处**由过层带来的**机械改动：
 * <ol>
 *   <li>{@code MiningBudget budget} 形参 → {@code boolean collectDrops} ＋ {@code boolean canPlaceSupport}
 *       —— ⛔ `reach/` 不许认 `task/` 的预算 record（`MiningBudget` 自己 import
 *       {@code action.BlockInteraction}，进不了 `reach/`），也不许自己去问库存
 *       （{@code findPlaceableSlot} 是 `action/` 的库存查询）；</li>
 *   <li>{@code budget.collectDrops()} → {@code collectDrops}；
 *       {@code BlockInteraction.findPlaceableSlot(bot) >= 0} → {@code canPlaceSupport}；</li>
 *   <li>{@code private} → {@code private static} / {@code public static}（本类**无状态**，
 *       照 {@code reach/} 的 house style：`StandingPointSelector`/`DropCatchment` 都是静态工具类）。</li>
 * </ol>
 *
 * <p>⭐ <b>"往哪个站"这件事只在这里发生一次</b>：候选由 {@link StandingPointSelector} 枚举、
 * 成本由 {@link StandingCostEstimator} 估、top-K 由 {@link CorePathPlanner} 精算、
 * 评分由 {@link StandingPointEvaluator} 给 —— 本类负责**把它们串成一次选择**并交回
 * {@link StandingPlanResult}。
 *
 * <p>⚠️ <b>两条逐字保留的纪律</b>（改革不许碰）：
 * <ol>
 *   <li>{@code P1-b}/{@code P1-d}：{@link SearchConclusion} 判出的"本轮没评价完"（`SEARCH_LIMIT` / `PARTIAL`）
 *       ⛔ **不许**在这一层被改写成"站不住"—— 见 {@code exactTopK} 的 {@code searchLimited}
 *       与 {@code selectDirect} 结尾那条"原样上抛"；</li>
 *   <li>接近能力由**调用方声明**（{@code D-443} 裁定 1a）：{@link ApproachCapability} 决定
 *       "到位形状"与请求工厂，⛔ 执行期不许由别的值反推（{@code D-520}）。</li>
 * </ol>
 *
 * <p>⚠️ <b>日志前缀仍是 {@code [MiningPlanner]}</b>（逐字未改）：真机记录与 `docs/` 大量按此前缀检索
 * ⇒ 它的去留是 `①-3`（`R1` 收口）的议题，⛔ 不在本刀里顺手改（否则等于把可检索面在搬家途中改掉）。
 */
public final class StandingPlanSelector {

    private StandingPlanSelector() {
    }

    public static StandingPlanResult selectDirect(ServerPlayer bot, ServerLevel level, BlockPos target,
                                                  BlockPos startFoot, double reach, boolean collectDrops,
                                                  boolean canPlaceSupport, ApproachCapability approach,
                                                  String requester) {
        LineOfSightChecker.LineOfSightResult currentLos =
                StandingPointSelector.isValidStandingPoint(level, target, startFoot, reach);
        if (currentLos != null) {
            PathPlan path = planPath(bot, startFoot, startFoot, PathRequest.of(
                    bot.getUUID().toString(), startFoot, startFoot, "mining-planner"));
            StandingPointEvaluator.StandingPointScore score =
                    StandingPointEvaluator.of(startFoot, 0.0D, 0.0D, currentLos);
            // 掉落物会丢的目标（D-078 修正，v7 §2.3；判据 `D-364` 收紧为"真会丢"）：
            // 即使当前站位就能挖，也要先在目标下方放支撑块。当前站位**就在目标正下方**时属于"从下方挖"策略，无需支撑。
            // 手上没有一次性方块时不强行要求支撑（避免把"没资源"变成任务失败），维持原行为。
            BlockPos supportPos = null;
            if (DropCatchment.dropWouldBeLost(level, target) && collectDrops
                    && !DropCatchment.isSameColumn(startFoot, target)
                    && canPlaceSupport) {
                supportPos = target.below();
            }
            BotLog.info("[MiningPlanner] arrival=IN_PLACE target={} stand={} cost=0 support={}",
                    target.toShortString(), startFoot.toShortString(),
                    supportPos == null ? "-" : supportPos.toShortString());
            return new StandingPlanResult(new MiningPlan(target, startFoot, startFoot, path, currentLos,
                    MiningPlan.Arrival.IN_PLACE, supportPos), score, "");
        }

        List<StandingPointSelector.Candidate> candidates =
                StandingPointSelector.generateCandidates(level, target, startFoot, reach);
        if (candidates.isEmpty()) {
            /*
             * ⚠️ 2026-09-29「搬空第一批」：这里原来有一个 P5 诊断探针（2026-09-24 加，
             * 注释自称"定位完成后删"，`plans §2.2` 实测它"**今天无预期读者**"）：
             *   `[MiningPlanner探针] no_valid_standing_point target=… faceStandable=n/6
             *    footPassable=… headPassable=… belowSolid=…`
             * **已删** —— 依据 = `plans §4.2`⑦（R9「**删**（与解体选哪条路无关，本来就该删）」）
             * ＋ 诊断探针纪律（用完即删）。
             * ⚠️ 它当年产出过一次**真根因读数**（`faceStandable=0/6 belowSolid=false`）；那份读数**已固化在文档里**，
             * 不随探针删除而丢：`HANDOVER.md:1178` · `AI_DECISIONS.md:19929`/`:19930` ·
             * `plans/2026-09-25-通道施工器草案.md:56`。
             * ⛔ **别把"这条日志消失"读成"那个判据没了"**：`STANDING_NO_VALID` 本身照旧返回。
             * ⛔ 也别把它读成"诊断探针以后都不许加" —— 纪律是**用完即删**，不是不许加。
             */
            return new StandingPlanResult(null, null, StandingPointRefusal.STANDING_NO_VALID);
        }

        boolean dropLost = DropCatchment.dropWouldBeLost(level, target);
        boolean needSupportBlock = dropLost && collectDrops;
        if (needSupportBlock) {
            List<StandingPointSelector.Candidate> side = new ArrayList<>();
            List<StandingPointSelector.Candidate> below = new ArrayList<>();
            for (StandingPointSelector.Candidate candidate : candidates) {
                if (DropCatchment.isSameColumn(candidate.foot(), target)) {
                    below.add(candidate);
                } else {
                    side.add(candidate);
                }
            }
            StandingPlanResult withSupport = selectBest(bot, level, target, startFoot, side,
                    target.below(), com.dddgn.alice.pathing.core.search.CostModel.PLACE_ONE_BLOCK_COST,
                    approach, requester);
            StandingPlanResult fromBelow = selectBest(bot, level, target, startFoot, below,
                    null, 0.0D, approach, requester);
            StandingPlanResult chosen = cheaper(withSupport, fromBelow);
            if (chosen != null) {
                BotLog.info("[MiningPlanner] support_needed target={} supportOption={} belowOption={} chosen={}",
                        target.toShortString(), withSupport.failureReason().isEmpty() ? "ok" : "-",
                        fromBelow.failureReason().isEmpty() ? "ok" : "-",
                        chosen.plan() == null ? "-" : chosen.plan().arrival());
                return chosen;
            }
        }
        StandingPlanResult best = selectBest(bot, level, target, startFoot, candidates,
                null, 0.0D, approach, requester);
        // ⭐ `P1-d`：`exactTopK` 已经如实判过"本轮没评价完"（`SEARCH_LIMIT` 或 `PARTIAL`）
        // ⇒ **不许**在这一层被改写成"站不住"。原来这里无条件改写 ⇒ `P1-b`/`P1-d` 的信号在模式 A 上全丢。
        if (best.plan() != null || SearchConclusion.SEARCH_INCOMPLETE.equals(best.failureReason())) {
            return best;
        }
        return new StandingPlanResult(null, null, StandingPointRefusal.STANDING_NO_REACHABLE);
    }

    /** 模式 A：候选 → 估算 → top-K 精确规划（纯通行请求）。 */
    private static StandingPlanResult selectBest(ServerPlayer bot, ServerLevel level, BlockPos target, BlockPos startFoot,
                              List<StandingPointSelector.Candidate> candidates,
                              BlockPos supportPos, double extraCost, ApproachCapability approach,
                              String requester) {
        if (candidates.isEmpty()) {
            return new StandingPlanResult(null, null, "no_candidate");
        }
        Map<BlockPos, LineOfSightChecker.LineOfSightResult> losByFoot = new HashMap<>();
        List<BlockPos> feet = new ArrayList<>(candidates.size());
        for (StandingPointSelector.Candidate candidate : candidates) {
            feet.add(candidate.foot());
            losByFoot.put(candidate.foot(), candidate.los());
        }
        // ⭐ `D-443` 裁定 1a：接近请求的**能力由调用方声明**；默认 `PURE_PASSAGE` = 原有那一行逐字不变
        //（连归因串 `"mining-planner"` 都保持原样，避免动到既有判据/账本口径）。
        String requesterForApproach = requester == null || requester.isBlank()
                ? "mining-planner" : requester;
        boolean placementAllowed = approach == ApproachCapability.PLACEMENT_ALLOWED;
        // ⭐⭐ `D-520`：**到位形状在这里派生、并在紧邻一行决定请求工厂** —— 两件事同一出处；
        // 执行期 `MineBlockRunner` 只读 `plan.arrival()` 复现同一个工厂（⛔ 不再由任何值反推）。
        // 旧形状的病灶：规划期用 `withPlacement`（`D-443` 裁定 1a，鱼骨「补一块再走」）、
        // 执行期一律用 `of`（纯通行）⇒ 规划说到得了、执行说到不了（同一 tick 两个相反答案）。
        MiningPlan.Arrival arrival = placementAllowed
                ? MiningPlan.Arrival.DIRECT_PLACEMENT_ALLOWED
                : MiningPlan.Arrival.DIRECT_PURE_PASSAGE;
        boolean includeUnestimated = placementAllowed;
        return exactTopK(bot, level, target, startFoot, feet, losByFoot, arrival, supportPos, extraCost,
                placementAllowed
                        ? (from, to) -> PathRequest.withPlacement(bot.getUUID().toString(), from, to,
                                requesterForApproach)
                        : (from, to) -> PathRequest.of(bot.getUUID().toString(), from, to,
                                requesterForApproach), includeUnestimated);
    }

    private static StandingPlanResult exactTopK(ServerPlayer bot, ServerLevel level, BlockPos target, BlockPos startFoot,
                             List<BlockPos> feet,
                             Map<BlockPos, LineOfSightChecker.LineOfSightResult> losByFoot,
                             MiningPlan.Arrival arrival, BlockPos supportPos, double extraCost,
                             BiFunction<BlockPos, BlockPos, PathRequest> requestFactory,
                             boolean includeUnestimated) {
        StandingCostEstimator.Result estimate = StandingCostEstimator.estimate(bot, level, feet);
        List<BlockPos> ranked = new ArrayList<>(estimate.costs().keySet());
        ranked.sort(Comparator.comparingDouble(estimate.costs()::get));
        // ⭐ `D-443` 裁定 1a（2026-09-25）：**排名也不能假设纯通行**。
        // `StandingCostEstimator` 的口径是「**不可达候选不在 map 中**」（它记的是**纯通行**成本场）
        // ⇒ 当接近能力升到 `PLACEMENT_ALLOWED` 时，"只有补一块才到得了"的候选会被**整个丢掉排名**，
        // 于是**永远不会被精算** ⇒ 报 `no_reachable_standing_point`，而同一段的走位工厂明明到得了
        // （夹具实测：走位 `REACHED movements=4` / 挖掘站位 `no_reachable_standing_point`）。
        // ⇒ 把这些"估不出成本"的候选按**几何下界**排在已估出的之后，交给精算阶段裁决（它们正是新能力的目标）。
        if (includeUnestimated) {
            List<BlockPos> unestimated = new ArrayList<>();
            for (BlockPos foot : feet) {
                if (!estimate.costs().containsKey(foot)) {
                    unestimated.add(foot);
                }
            }
            unestimated.sort(Comparator.comparingDouble(f -> new com.dddgn.alice.pathing.core.search.GoalFoot(f).heuristic(startFoot)));
            ranked.addAll(unestimated);
        }

        StandingPointEvaluator.StandingPointScore best = null;
        PathPlan bestPath = null;
        int planned = 0;
        // ⭐ `P1-b`：是否出现过"本轮没评价完"（`SEARCH_LIMIT`）。有它 ⇒ 结尾**不许**报 `no_reachable_candidate`。
        boolean searchLimited = false;
        int k = Math.min(MiningTuning.exactTopK(), ranked.size());
        while (true) {
            for (int i = planned; i < k; i++) {
                BlockPos foot = ranked.get(i);
                PathPlan path = planPath(bot, startFoot, foot, requestFactory.apply(startFoot, foot));
                if (!path.reached()) {
                    // ⭐ `P1-d`：`SEARCH_LIMIT`（没跑）与 `PARTIAL`（跑了没算完）**都不许**变成"没有路"。
                    if (SearchConclusion.inconclusive(path.status())) {
                        searchLimited = true;
                    }
                    continue;
                }
                double cost = path.totalCost() + extraCost;
                if (best == null || cost < best.getScore()) {
                    Double estimated = estimate.costs().get(foot);
                    best = StandingPointEvaluator.of(foot, cost,
                            estimated == null ? new com.dddgn.alice.pathing.core.search.GoalFoot(foot).heuristic(startFoot) : estimated,
                            losByFoot.get(foot));
                    bestPath = path;
                }
            }
            planned = k;
            boolean canExpand = k < ranked.size() && k < MiningTuning.exactTopKMax();
            if (best != null) {
                Double nextRaw = k < ranked.size() ? estimate.costs().get(ranked.get(k)) : null;
                double nextEstimate = nextRaw == null
                        ? Double.POSITIVE_INFINITY
                        : nextRaw + extraCost;
                if (best.getScore() <= nextEstimate || !canExpand) {
                    break;
                }
            } else if (!canExpand) {
                break;
            }
            k = Math.min(k + 2, ranked.size());
        }

        if (best == null) {
            if (searchLimited) {
                BotLog.warn("[MiningPlanner] arrival={} target={} startFoot={} candidates={} planned={}"
                                + " reason=search_incomplete searchLimited=true"
                                + "（本轮没评价完，不是「不可达」：`SEARCH_LIMIT ≠ UNREACHABLE`）",
                        arrival, target.toShortString(), startFoot.toShortString(), feet.size(), planned);
                return new StandingPlanResult(null, null, SearchConclusion.SEARCH_INCOMPLETE);
            }
            return new StandingPlanResult(null, null, "no_reachable_candidate");
        }
        BotLog.info("[MiningPlanner] arrival={} target={} startFoot={} candidates={} estimate={} nodes={} ms={}"
                        + " planned={} chosen={} cost={} pathSize={} support={} los={}",
                arrival, target.toShortString(), startFoot.toShortString(), feet.size(), estimate.mode(),
                estimate.nodesExpanded(), estimate.elapsedMillis(), planned,
                best.getPosition().toShortString(),
                String.format(java.util.Locale.ROOT, "%.3f", best.getScore()),
                bestPath.movements().size(),
                supportPos == null ? "-" : supportPos.toShortString(),
                best.getLineOfSightResult().isClear());
        MiningPlan plan = new MiningPlan(target, startFoot, best.getPosition(), bestPath,
                best.getLineOfSightResult(), arrival, supportPos);
        return new StandingPlanResult(plan, best, "");
    }

    private static StandingPlanResult cheaper(StandingPlanResult first, StandingPlanResult second) {
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

    public static PathPlan planPath(ServerPlayer bot, BlockPos startFoot, BlockPos standingFoot,
                                     PathRequest request) {
        return new CorePathPlanner().plan(bot, bot.serverLevel(), request);
    }
}
