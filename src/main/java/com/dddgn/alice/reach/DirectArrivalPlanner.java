package com.dddgn.alice.reach;

import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.CostModel;
import com.dddgn.alice.pathing.core.search.GoalFoot;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.search.SearchConclusion;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiFunction;

/**
 * ⭐⭐ <b>A 腿：「站在现成可站的格子上，直接走到能挖到目标的位置」</b>
 * （`plans §4.2`① 逐字：「CURRENT 快路径 ＋ 候选枚举 ＋ LOS/触及过滤 ＋ 排序 ＋ 最优」）。
 *
 * <p>⭐ <b>2026-09-29 `1-3`（批次 1 改革 ① 主体 · 甲「成员级退役」）—— 本文件是搬家的落点</b>：
 * 它逐字来自 `reach/StandingPlanSelector`（后者又逐字来自 `task/mining/MiningPlanner` 的
 * `DS-5` 解体，`①-2b`）。搬家的**理由**（而不是"删掉它"）：
 * {@link ReachPlan.Arrival} 的 `IN_PLACE` / `DIRECT_PURE_PASSAGE` / `DIRECT_PLACEMENT_ALLOWED`
 * **三个取值就是它生产的**，而它是 `MiningPlanner.plan()` 的**第一条腿**（成功即返回）
 * ⇒ 它是**新框架的一部分**，只是旧名字（`StandingPlanSelector` = "站位挖掘的『选』"）
 * 把它说成了待退休的东西。开工前侦察（施工设计单 `§14`）实测：**按文件删会立刻坏**
 * （③ `ReachPlan.Arrival` 的三个取值失去唯一生产点）。
 *
 * <p>⚠️ <b>本刀**只搬家 ＋ 两处退役**，逻辑逐字未改</b>：
 * <ol>
 *   <li>{@code planPath} 小工具**内联**（`§14.4` 甲①）：它体内只把第 3 个实参
 *       （`standingFoot`）丢给 `new CorePathPlanner().plan(bot, level, request)` ——
 *       **那个形参从来没被读过** ⇒ 死参数与死包装一起删（`1-1b₂` 的注释已点过它）；</li>
 *   <li>“评分载体”退役（`§4e` 甲）：{@link StandingPointEvaluator} 的 `StandingPointScore`
 *       不再被构造，本类就地用两个局部量（`bestFoot` / `bestCost`）做 argmin 与扩张判据 ——
 *       `bestCost` 就是 `path.totalCost() + extraCost`，与旧 `score` **同一个数**；
 *       ⛔ 它不是新载体（没有跨方法传递、没有进 {@link ReachOutcome}）。</li>
 * </ol>
 * ⚠️ 顺带被删掉的还有 {@code losByFoot} 那张 map —— 它的**唯一**读者是评分载体
 * （`score.lineOfSightResult`）与已退役的 `ReachPlan.visibility`（`§4e` 甲）⇒ 零读者。
 *
 * <p>⚠️ <b>两条逐字保留的纪律</b>（改革不许碰）：
 * <ol>
 *   <li>{@code P1-b}/{@code P1-d}：{@link SearchConclusion} 判出的"本轮没评价完"
 *       （`SEARCH_LIMIT` / `PARTIAL` / `GOAL_NOT_LOADED`）⛔ **不许**在这一层被改写成"站不住"
 *       —— 见 {@code exactTopK} 的 {@code searchLimited} 与 {@code selectDirect} 结尾那条"原样上抛"；</li>
 *   <li>接近能力由**调用方声明**（`D-443` 裁定 1a）：{@link ApproachCapability} 决定
 *       "到位形状"与请求工厂，⛔ 执行期不许由别的值反推（`D-520`）。</li>
 * </ol>
 *
 * <p>⚠️ <b>日志前缀仍是 {@code [MiningPlanner]}</b>（逐字未改）：真机记录与 `docs/` 大量按此前缀检索
 * ⇒ 它的去留是 `1-4`（`R1` 收口）的议题，⛔ 不在本刀里顺手改（否则等于把可检索面在搬家途中改掉）。
 *
 * <p>⚠️ <b>搬出去的那一半**没有**跟着走</b>（`§14.4` 甲⑤，已登记）：本类仍然引用
 * {@link StandingCostEstimator}（成本场估算）与 {@link MiningTuning} 的 top-K 两个旋钮 ——
 * 它们只在**本腿**里有消费者，把它们一并重建 = 改作业级选择成本（`PlanRefinedCostProvider`
 * 那条链）⇒ **移出本刀**，随"真正退休 A 腿/成本场"的那把刀。
 * ⛔ 也别把本类读成"站位枚举被保留了"：{@link StandingPointSelector#tunnelCandidates}（固定几何集
 * ＋ 竖井预算）**已删**，{@link StandingPointSelector#generateCandidates}（现成可站候选）留着 ——
 * 它有三个活生产消费者。
 */
public final class DirectArrivalPlanner {

    private DirectArrivalPlanner() {
    }

    /**
     * A 腿的入口：先看**当前站位**能不能挖（CURRENT 快路径），否则枚举现成可站候选 → 成本估算 →
     * top-K 精算 → 择优。
     *
     * @param collectDrops     掉落物承接的口径由调用方声明（`MiningBudget.collectDrops()`；
     *                         ⛔ `reach/` 不许 import `task/` 的预算 record）
     * @param canPlaceSupport  "手上有没有可放置方块"也是**调用方声明**（`BlockInteraction.findPlaceableSlot`
     *                         是 `action/` 的库存查询，`reach/` 不许自己去问库存）
     */
    public static ReachOutcome selectDirect(ServerPlayer bot, ServerLevel level, BlockPos target,
                                            BlockPos startFoot, double reach, boolean collectDrops,
                                            boolean canPlaceSupport, ApproachCapability approach,
                                            String requester) {
        if (StandingPointSelector.isCurrentPositionGoodEnough(level, target, startFoot, reach)) {
            PathPlan path = new CorePathPlanner().plan(bot, level, PathRequest.of(
                    bot.getUUID().toString(), startFoot, startFoot, "mining-planner"));
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
            return new ReachOutcome(new ReachPlan(target, startFoot, startFoot, path,
                    ReachPlan.Arrival.IN_PLACE, supportPos), "");
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
            return new ReachOutcome(null, StandingPointRefusal.STANDING_NO_VALID);
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
            ReachOutcome withSupport = selectBest(bot, level, target, startFoot, side,
                    target.below(), CostModel.PLACE_ONE_BLOCK_COST, approach, requester);
            ReachOutcome fromBelow = selectBest(bot, level, target, startFoot, below,
                    null, 0.0D, approach, requester);
            ReachOutcome chosen = cheaper(withSupport, fromBelow);
            if (chosen != null) {
                BotLog.info("[MiningPlanner] support_needed target={} supportOption={} belowOption={} chosen={}",
                        target.toShortString(), withSupport.failureReason().isEmpty() ? "ok" : "-",
                        fromBelow.failureReason().isEmpty() ? "ok" : "-",
                        chosen.plan() == null ? "-" : chosen.plan().arrival());
                return chosen;
            }
        }
        ReachOutcome best = selectBest(bot, level, target, startFoot, candidates,
                null, 0.0D, approach, requester);
        // ⭐ `P1-d`：`exactTopK` 已经如实判过"本轮没评价完"（`SEARCH_LIMIT` / `PARTIAL` / `GOAL_NOT_LOADED`）
        // ⇒ **不许**在这一层被改写成"站不住"。原来这里无条件改写 ⇒ `P1-b`/`P1-d` 的信号在模式 A 上全丢。
        if (best.plan() != null || SearchConclusion.SEARCH_INCOMPLETE.equals(best.failureReason())) {
            return best;
        }
        return new ReachOutcome(null, StandingPointRefusal.STANDING_NO_REACHABLE);
    }

    /** A 腿的候选组：候选 → 估算 → top-K 精确规划（纯通行请求）。 */
    private static ReachOutcome selectBest(ServerPlayer bot, ServerLevel level, BlockPos target, BlockPos startFoot,
                                           List<StandingPointSelector.Candidate> candidates,
                                           BlockPos supportPos, double extraCost, ApproachCapability approach,
                                           String requester) {
        if (candidates.isEmpty()) {
            return new ReachOutcome(null, "no_candidate");
        }
        List<BlockPos> feet = new ArrayList<>(candidates.size());
        for (StandingPointSelector.Candidate candidate : candidates) {
            feet.add(candidate.foot());
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
        ReachPlan.Arrival arrival = placementAllowed
                ? ReachPlan.Arrival.DIRECT_PLACEMENT_ALLOWED
                : ReachPlan.Arrival.DIRECT_PURE_PASSAGE;
        boolean includeUnestimated = placementAllowed;
        return exactTopK(bot, level, target, startFoot, feet, arrival, supportPos, extraCost,
                placementAllowed
                        ? (from, to) -> PathRequest.withPlacement(bot.getUUID().toString(), from, to,
                                requesterForApproach)
                        : (from, to) -> PathRequest.of(bot.getUUID().toString(), from, to,
                                requesterForApproach), includeUnestimated);
    }

    private static ReachOutcome exactTopK(ServerPlayer bot, ServerLevel level, BlockPos target, BlockPos startFoot,
                                          List<BlockPos> feet,
                                          ReachPlan.Arrival arrival, BlockPos supportPos, double extraCost,
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
            unestimated.sort(Comparator.comparingDouble(foot -> new GoalFoot(foot).heuristic(startFoot)));
            ranked.addAll(unestimated);
        }

        // ⭐ `§4e` 甲：`bestFoot` / `bestCost` 是**局部量**，不是载体 —— 旧 `StandingPointScore`
        // 的另三个字段（position/los/estimate）都是冗余或零读者，见 {@link ReachOutcome} 类注释。
        BlockPos bestFoot = null;
        double bestCost = 0.0D;
        PathPlan bestPath = null;
        int planned = 0;
        // ⭐ `P1-b`：是否出现过"本轮没评价完"（`SEARCH_LIMIT` 等）。有它 ⇒ 结尾**不许**报 `no_reachable_candidate`。
        boolean searchLimited = false;
        int k = Math.min(MiningTuning.exactTopK(), ranked.size());
        while (true) {
            for (int i = planned; i < k; i++) {
                BlockPos foot = ranked.get(i);
                PathPlan path = new CorePathPlanner().plan(bot, level, requestFactory.apply(startFoot, foot));
                if (!path.reached()) {
                    // ⭐ `P1-d`：`SEARCH_LIMIT`（没跑）与 `PARTIAL`（跑了没算完）**都不许**变成"没有路"。
                    if (SearchConclusion.inconclusive(path.status())) {
                        searchLimited = true;
                    }
                    continue;
                }
                double cost = path.totalCost() + extraCost;
                if (bestFoot == null || cost < bestCost) {
                    bestFoot = foot;
                    bestCost = cost;
                    bestPath = path;
                }
            }
            planned = k;
            boolean canExpand = k < ranked.size() && k < MiningTuning.exactTopKMax();
            if (bestFoot != null) {
                Double nextRaw = k < ranked.size() ? estimate.costs().get(ranked.get(k)) : null;
                double nextEstimate = nextRaw == null
                        ? Double.POSITIVE_INFINITY
                        : nextRaw + extraCost;
                if (bestCost <= nextEstimate || !canExpand) {
                    break;
                }
            } else if (!canExpand) {
                break;
            }
            k = Math.min(k + 2, ranked.size());
        }

        if (bestFoot == null) {
            if (searchLimited) {
                BotLog.warn("[MiningPlanner] arrival={} target={} startFoot={} candidates={} planned={}"
                                + " reason=search_incomplete searchLimited=true"
                                + "（本轮没评价完，不是「不可达」：`SEARCH_LIMIT ≠ UNREACHABLE`）",
                        arrival, target.toShortString(), startFoot.toShortString(), feet.size(), planned);
                return new ReachOutcome(null, SearchConclusion.SEARCH_INCOMPLETE);
            }
            return new ReachOutcome(null, "no_reachable_candidate");
        }
        BotLog.info("[MiningPlanner] arrival={} target={} startFoot={} candidates={} estimate={} nodes={} ms={}"
                        + " planned={} chosen={} cost={} pathSize={} support={}",
                arrival, target.toShortString(), startFoot.toShortString(), feet.size(), estimate.mode(),
                estimate.nodesExpanded(), estimate.elapsedMillis(), planned,
                bestFoot.toShortString(),
                String.format(java.util.Locale.ROOT, "%.3f", bestCost),
                bestPath.movements().size(),
                supportPos == null ? "-" : supportPos.toShortString());
        ReachPlan plan = new ReachPlan(target, startFoot, bestFoot, bestPath, arrival, supportPos);
        return new ReachOutcome(plan, "");
    }

    /**
     * 两组候选的择优：**比的是计划自己的成本**（{@link ReachPlan#totalCost()}），
     * ⛔ 不是那个已退役的"评分载体"。
     *
     * <p>⚠️ 两组各自由 {@code selectBest} 带着自己的 `extraCost`（垫支撑块那一组 = 一次放置计价）
     * 产出，而该计价已经**织进** `totalCost()` ⇒ 这里逐字复现旧 `score.getScore()` 的比较。
     */
    private static ReachOutcome cheaper(ReachOutcome first, ReachOutcome second) {
        boolean firstOk = first.plan() != null;
        boolean secondOk = second.plan() != null;
        if (firstOk && secondOk) {
            return first.plan().totalCost() <= second.plan().totalCost() ? first : second;
        }
        if (firstOk) {
            return first;
        }
        return secondOk ? second : null;
    }
}
