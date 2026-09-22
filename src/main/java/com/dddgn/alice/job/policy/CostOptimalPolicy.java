package com.dddgn.alice.job.policy;

import com.dddgn.alice.job.Candidate;
import com.dddgn.alice.job.CandidateSet;
import com.dddgn.alice.job.GoalSpec;
import com.dddgn.alice.job.Selection;
import com.dddgn.alice.job.SelectionPolicy;
import com.dddgn.alice.job.mine.CandidateCostProvider;
import com.dddgn.alice.job.mine.MineCostConfig;
import com.dddgn.alice.job.mine.MineValueTable;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * **成本最优选择策略**（`D-329` §2.2）：`cost = travelCost − valueWeight × 归一化价值`。
 *
 * <p>用户 2026-09-20 三条裁定（本类必须逐条可判）：
 * <ol>
 *   <li>「矿物价值优先级」**不是独立模型**，是**成本函数里的一个可配置分量** ⇒ 它只以 {@code −w×value}
 *       这一项出现，**没有任何"钻石优先"的分支**；</li>
 *   <li>**只在多目标种类任务里启用**（本次候选里出现 **≥2 种方块**）⇒ 单种类任务里价值项**完全惰性**；</li>
 *   <li>**不许无条件挖最高级矿** ⇒ 权重小 ⇒ 仍选最近的；只有权重压过路程差才愿意绕路
 *       （`valueWeight` 的单位 = "最高档矿最多值多少格额外路程"）。</li>
 * </ol>
 *
 * <p>⚠️ **`valueWeight = 0` 时不是"退化到 `NearestPolicy`"**：成本模型照样按**通行成本**排序（比欧氏距离准），
 * 只是"价值"不参与。判据分两层：① 权重 0 + 成本 == 欧氏距离 ⇒ 与 `NearestPolicy` 选同一个（构造性对照）；
 * ② 权重 0 + 真实成本场 ⇒ 按成本排序（`mine_run_metrics` 那套账看不退化）。
 */
public final class CostOptimalPolicy implements SelectionPolicy {

    /** 打分中间量（`cost` 越小越先选；`travel`/`value` 只为日志与判据留痕）。 */
    private record Scored(Candidate candidate, double cost, double travel, double value) {
    }

    private final CandidateCostProvider provider;
    private final MineCostConfig config;

    /** 最近一次选择的成本读数（决策日志/判据用；`cells` 是"成本有界"的可测证据）。 */
    private CandidateCostProvider.Result lastResult = CandidateCostProvider.Result.empty("未选择过");
    /** 最近一次选择里价值项是否启用（"只在多目标种类启用"的可测证据）。 */
    private boolean lastValueEnabled;
    /** 最近一次选择里认出来的方块种类数（≥2 ⇒ 多目标种类任务）。 */
    private int lastKindCount;

    public CostOptimalPolicy(CandidateCostProvider provider, MineCostConfig config) {
        this.provider = provider;
        this.config = config;
    }

    /**
     * 生产用：**成本场估算 → top-K 精算**（`D-363`：`break` 分量）+ 配置权重。
     *
     * <p>为什么不是裸的成本场：真机实测（A 路线第一轮）里"现成可站"的站位点几乎不存在
     * （矿体嵌在地表）⇒ 所有候选都估不出成本 ⇒ 排序退化成欧氏最近，而执行器其实在用 `TUNNEL`。
     * 精算走 {@code MiningPlanner}，它的路径成本**本来就含破坏 tick 折算** ⇒ 这才是"把 break 算进去"的
     * 既有机制（内核路线：不另造一套破坏估算）。
     */
    public static CostOptimalPolicy production() {
        return new CostOptimalPolicy(com.dddgn.alice.job.mine.PlanRefinedCostProvider.production(),
                MineCostConfig.load());
    }

    @Override
    public String name() {
        return "cost_optimal";
    }

    public CandidateCostProvider.Result lastResult() {
        return lastResult;
    }

    public boolean lastValueEnabled() {
        return lastValueEnabled;
    }

    public int lastKindCount() {
        return lastKindCount;
    }

    @Override
    public Selection select(ServerPlayer bot, GoalSpec spec, CandidateSet candidates) {
        List<String> rejected = new ArrayList<>(candidates.rejected());
        if (candidates.isEmpty()) {
            lastResult = CandidateCostProvider.Result.empty("无候选");
            lastValueEnabled = false;
            lastKindCount = 0;
            return Selection.none(rejected);
        }
        // ⭐ 一次性成本场（用户裁定其三）：每次选择**重算**，不缓存跨选择的结果
        CandidateCostProvider.Result costs = provider.estimate(bot, spec, candidates.viable());
        lastResult = costs;

        Set<String> kinds = new LinkedHashSet<>();
        for (Candidate candidate : candidates.viable()) {
            kinds.add(candidate.feature("block"));
        }
        lastKindCount = kinds.size();
        boolean multiKind = kinds.size() >= 2;
        lastValueEnabled = config.valueEnabled(multiKind);
        double weight = lastValueEnabled ? config.valueWeight() : 0.0D;

        List<Scored> scored = new ArrayList<>();
        for (Candidate candidate : candidates.viable()) {
            double travel = costs.travel(candidate);
            double value = weight == 0.0D ? 0.0D
                    : MineValueTable.normalizedById(candidate.feature("block"));
            double cost = travel - weight * value;
            scored.add(new Scored(candidate, cost, travel, value));
        }
        // 确定性全序：成本 → 欧氏距离 → 锚点键（同成本同距离时不许靠输入顺序）
        scored.sort(Comparator.comparingDouble(Scored::cost)
                .thenComparingDouble(s -> bot.distanceToSqr(s.candidate().anchor().getX() + 0.5D,
                        s.candidate().anchor().getY() + 0.5D, s.candidate().anchor().getZ() + 0.5D))
                .thenComparingLong(s -> s.candidate().anchor().asLong()));
        Scored picked = scored.get(0);
        for (int i = 1; i < scored.size(); i++) {
            rejected.add(scored.get(i).candidate().id() + ":higher_cost");
        }
        return new Selection(picked.candidate(), reason(bot, picked, weight), rejected);
    }

    private String reason(ServerPlayer bot, Scored picked, double weight) {
        // ⭐ `P1-d`（2026-09-22 真机根因）：成本场**一格都没枚举出来**（`cells=0`）时，
        // 每个候选的 `travel` 都是 `∞` ⇒ 排序在数学上已**退化成"成本相等"**，实际由
        // `thenComparingDouble(欧氏距离)` 决定 ⇒ 选出来的是**欧氏最近**，**不是** cost_optimal。
        // 原来这里仍以 `cost_optimal` 打头（真机 `[Job] select … cost=inf（估不出通行成本 ⇒ 排序靠后）`
        // 却仍被选中）⇒ 归因与事实不符。⇒ 显式标注 `UNREFINED`，让"退化"在日志里 grep 得到。
        boolean unrefined = lastResult.estimatedCells() <= 0 || Double.isInfinite(picked.travel());
        String travel = !unrefined
                ? String.format(Locale.ROOT, "cost=%.2f", picked.cost())
                : (lastResult.estimatedCells() <= 0
                        ? "estimate=UNREFINED（成本场 cells=0 ⇒ 本次排序**退化为欧氏最近**，不是 cost_optimal）"
                        : "cost=inf（该候选估不出成本 ⇒ 退化为欧氏最近，**不是**不可达）");
        return String.format(Locale.ROOT,
                "cost_optimal %s travel=%.2f value=%.2f w=%.2f kinds=%d valueOn=%s cells=%d · %s",
                travel, picked.travel(), picked.value(), weight, lastKindCount, lastValueEnabled,
                lastResult.estimatedCells(), lastResult.note());
    }
}
