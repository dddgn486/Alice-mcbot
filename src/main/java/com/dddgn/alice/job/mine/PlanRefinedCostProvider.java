package com.dddgn.alice.job.mine;

import com.dddgn.alice.job.Candidate;
import com.dddgn.alice.job.GoalSpec;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.task.mining.MiningPlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * **把 `break`（破坏）算进选择成本**（`D-363`，用户 2026-09-20：「`break` 分量我觉得可以马上做」）。
 *
 * <h3>缺口（真机实测，A 路线第一轮）</h3>
 * 选择成本原本 = `走路的成本 − 权重×价值`，而"走路"来自 {@link StandingCostField} 的**纯通行** Dijkstra
 * ⇒ 只认**现成可站**的站位点。真实地形里矿体嵌在地表、相邻面被同簇方块挡死 ⇒ **一个合格站位点都没有**
 * ⇒ 每个候选都是 `∞`（实测 `cells=0（所有候选都没枚举出站位点）`，8 次选择里 7 次）⇒ 排序退化成
 * "欧氏最近"。而执行器随后用的却是 `mode=TUNNEL`（挖出站位点）—— **成本模型只算"走"，执行器在"走+挖"**。
 *
 * <h3>做法 = 补上 `D-329` §2.1 里本就设计好、但一直没实现的那一半</h3>
 * 原设计写着：「两者都只做**排序用的估算**；最终对 top-K 候选做**精确规划**（`MiningPlanner`）」。
 * 本轮就把这句实现掉：先按估算排名取 top-K，再用 {@link MiningPlanner} 精算 —— 而规划器的成本**本来就含破坏项**
 * （`MiningPlanner` 走 `PathRequest.miningApproach`，`SurfaceMovementProvider` 把
 * `(breakTicks + BREAK_PENALTY_TICKS) / WALK_ONE_BLOCK_TICKS` 折进移动代价，`MiningPlanner.java:188` 同款换算）
 * ⇒ **不需要另造一套破坏估算**（内核路线：对齐既有机制，不自己发明）。
 *
 * <h3>口径（不许漂的三条）</h3>
 * <ol>
 *   <li>**仍然只是排序**：精算失败 ⇒ 该候选**保持"估不出"（∞）**，绝不拒绝它
 *       （`SEARCH_LIMIT ≠ UNREACHABLE`：能不能挖由 `MineTask` 的规划器在执行期说）。</li>
 *   <li>**单位一致**：精算值取规划器的 `score`（走路格数口径，含破坏 tick 折算），与成本场的 Dijkstra 值同尺度
 *       ⇒ 两者可以同表比较。</li>
 *   <li>**有界**：每次选择最多跑 {@link #DEFAULT_TOP_K} 次规划（`MiningPlanner` 实测 ~185 节点量级），
 *       次数与结果都写进 `note`（`精算 topK=…/… 成功=…`）⇒ 退化看得见。</li>
 * </ol>
 *
 * <p>⚠️ 精算用的预算 = 规划器的**默认收集预算**（策略手里没有 Job 的 `MiningBudget`）——
 * 它是"排序用的估算"，与执行期预算允许有小差异；能不能真挖仍由执行期说了算。
 */
public final class PlanRefinedCostProvider implements CandidateCostProvider {

    /** 每次选择最多精算几个候选（成本上界；同时也是"选了哪几个"的可测证据）。 */
    public static final int DEFAULT_TOP_K = 3;

    private final CandidateCostProvider base;
    private final int topK;

    public PlanRefinedCostProvider(CandidateCostProvider base) {
        this(base, DEFAULT_TOP_K);
    }

    public PlanRefinedCostProvider(CandidateCostProvider base, int topK) {
        this.base = java.util.Objects.requireNonNull(base, "base");
        this.topK = Math.max(0, topK);
    }

    /** 生产用的完整成本链：**成本场估算 → top-K 精算**（`D-363`）。 */
    public static PlanRefinedCostProvider production() {
        return new PlanRefinedCostProvider(new StandingCostField(), DEFAULT_TOP_K);
    }

    @Override
    public Result estimate(ServerPlayer bot, GoalSpec spec, List<Candidate> candidates) {
        Result estimated = base.estimate(bot, spec, candidates);
        if (candidates.isEmpty() || topK == 0) {
            return estimated;
        }
        List<Candidate> ranked = rankForRefine(bot, candidates, estimated);
        int tried = Math.min(topK, ranked.size());
        Map<Long, Double> refined = new HashMap<>(estimated.travelByAnchor());
        int planned = 0;
        for (int index = 0; index < tried; index++) {
            Candidate candidate = ranked.get(index);
            MiningPlanner.Result result = new MiningPlanner().plan(bot, candidate.anchor());
            if (!result.success()) {
                continue;   // 精算不了 ⇒ 保持"估不出"（**不拒绝**）
            }
            refined.put(candidate.anchor().asLong(), result.score().getScore());
            planned++;
        }
        String note = String.format(Locale.ROOT, "%s · 精算 尝试=%d 成功=%d（候选 %d）",
                estimated.note(), tried, planned, candidates.size());
        if (planned == 0 && tried > 0) {
            BotLog.info("[MineCost] 精算无结果（尝试={} 全部规划失败）⇒ 保持成本场读数（仍不拒绝）", tried);
        }
        return new Result(refined, estimated.estimatedCells(), note);
    }

    /**
     * **取哪几个去精算**（纯函数，夹具可逐条断言）：先按估算成本从便宜到贵（有限成本的排前面），
     * 同成本再按离 bot 的欧氏距离；`∞` 的那些按距离从近到远跟在后面
     * （它们正是"要在真实地形里挖出站位点"的候选 —— 第一轮实测里**全部都是它们**）。
     */
    public static List<Candidate> rankForRefine(ServerPlayer bot, List<Candidate> candidates,
                                                Result estimated) {
        List<Candidate> ranked = new ArrayList<>(candidates);
        ranked.sort(Comparator
                .comparingDouble((Candidate candidate) -> estimated.travel(candidate))
                .thenComparingDouble(candidate -> distance(bot, candidate))
                .thenComparingLong(candidate -> candidate.anchor().asLong()));
        return ranked;
    }

    private static double distance(ServerPlayer bot, Candidate candidate) {
        BlockPos anchor = candidate.anchor();
        return Math.sqrt(bot.distanceToSqr(anchor.getX() + 0.5D, anchor.getY() + 0.5D,
                anchor.getZ() + 0.5D));
    }

    /** 归因用：本提供者的说明（进 `[MineJob]`/决策日志）。 */
    public String describe() {
        return "成本场 + top" + topK + " 精算（break 分量见 MiningPlanner 的路径成本）";
    }
}
