package com.dddgn.alice.job.mine;

import com.dddgn.alice.job.Candidate;
import com.dddgn.alice.job.GoalSpec;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.task.mining.MiningPlanner;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

/**
 * **把 `break`（破坏）算进选择成本**（`D-363`）+ **摊销精算**（`D-368`，2026-09-20）。
 *
 * <h3>缺口（真机实测，A 路线第一轮）</h3>
 * 选择成本原本 = `走路的成本 − 权重×价值`，而"走路"来自 {@link StandingCostField} 的**纯通行** Dijkstra
 * ⇒ 只认**现成可站**的站位点。真实地形里矿体嵌在地表、相邻面被同簇方块挡死 ⇒ **一个合格站位点都没有**
 * ⇒ 每个候选都是 `∞`（实测 `cells=0（所有候选都没枚举出站位点）`）⇒ 排序退化成"欧氏最近"。
 * 而执行器随后用的却是 `mode=TUNNEL`（挖出站位点）—— **成本模型只算"走"，执行器在"走+挖"**。
 *
 * <h3>`D-363` 的做法与它的两个缺陷</h3>
 * 补上 `D-329` §2.1 设计过但没实现的那一半：用 {@link MiningPlanner} 精算 —— 规划器的成本**本来就含破坏项**
 * （`MiningPlanner` 走 `PathRequest.miningApproach`，`SurfaceMovementProvider` 把
 * `(breakTicks + BREAK_PENALTY_TICKS) / WALK_ONE_BLOCK_TICKS` 折进移动代价）⇒ **不另造破坏估算器**。
 * 但 `D-363` 是"**每次选择固定精算 top-3**"，真机两轮暴露两个缺陷：
 * <ol>
 *   <li>**① 掉刻**：3 次完整规划器 ≈ 85 ms/选择 ⇒ 与成本场相加 **102→126 ms**，**超 tick 预算（50 ms）2 倍**
 *       （`D-367` 离线实测，见 `docs/reviews/2026-09-20-mine-round3-root-cause.md` §2bis）；</li>
 *   <li>**② 排序退化仍在**：`精算 尝试=3 成功=3（候选 91）` ⇒ **97% 候选永远拿不到真实成本**
 *       ⇒ 只要那 3 个里还有能挖的，脉内其余矿**永远排不上** ⇒ 用户看到的"绕远/来回折返"。</li>
 * </ol>
 *
 * <h3>`D-368` 的做法 = **摊销**（每次选择有界 + 跨选择缓存）</h3>
 * <p>⚠️ 先说清**为什么不用分支限界**（原 `D-368` 设计）：下界弱时"剪枝 + 安全上限"**不保证找到真最优**
 * （反例：下界 5/6/7 而真实代价 50/51/1 ⇒ 上限 3 就会漏掉真最优）；而要让下界对"没有站位点"的候选也可采纳，
 * 得引入"站位点离目标 ≤ reach"的松弛量（新常量 = 自己发明公式，内核路线禁止）。⇒ 改为**摊销**：
 * <ol>
 *   <li>**每次选择最多精算 {@link #REFINE_PER_SELECT} 个**（默认 1 ⇒ 单次 ~28 ms，回到 tick 预算内）；</li>
 *   <li>结果进**缓存**（TTL {@link #CACHE_TTL_TICKS} tick）⇒ 下一次选择直接复用，**不再重复精算同一个候选**；
 *       于是覆盖数**逐次增长**，最终**全部候选都有真实成本** ⇒ ② 的"锁死最近 3 个"被消掉；</li>
 *   <li>**精算失败（∞）也进缓存**（TTL 后重试）—— 否则同一个失败候选会**每次挡住轮转**，
 *       覆盖永远涨不上去（反向对照会红）；</li>
 *   <li>被采走/不再在候选集里的锚点**立刻剔除**；TTL 只用于"世界变了要重算"（估算允许小幅过期，
 *       **能不能挖仍由 `MineTask` 执行期用实时世界判定**）。</li>
 * </ol>
 *
 * <h3>口径（不许漂的三条）</h3>
 * <ol>
 *   <li>**仍然只是排序**：精算失败 ⇒ 该候选**保持"估不出"（∞）**，绝不拒绝它
 *       （`SEARCH_LIMIT ≠ UNREACHABLE`）。</li>
 *   <li>**单位一致**：精算值取规划器的 `score`（走路格数口径，含破坏 tick 折算），与成本场的 Dijkstra 值同尺度
 *       ⇒ 两者可以同表比较。</li>
 *   <li>**有界**：每次选择的规划器调用次数 ≤ {@link #REFINE_PER_SELECT}（常量），
 *       次数与覆盖数写进 `note`（`精算 本tick=… 已覆盖=…/…`）⇒ 退化看得见。</li>
 * </ol>
 *
 * <p>⚠️ 精算用的预算 = 规划器的**默认收集预算**（策略手里没有 Job 的 `MiningBudget`）——
 * 它是"排序用的估算"，与执行期预算允许有小差异；能不能真挖仍由执行期说了算。
 */
public final class PlanRefinedCostProvider implements CandidateCostProvider {

    /**
     * **每次选择最多精算几个候选**（`D-368`：1）。
     *
     * <p>为什么是 1：`D-367` 实测单次 {@link MiningPlanner} ≈ 28 ms，而 tick 预算 50 ms ⇒ 1 次安全、3 次必然超。
     * 覆盖不足由**缓存跨选择累积**补上，而不是靠一次多跑几个。
     */
    public static final int REFINE_PER_SELECT = 1;

    /** 缓存存活 tick 数（过期后重新精算：世界会变，而精算读的是世界）。 */
    public static final long CACHE_TTL_TICKS = 200L;

    /** 缓存条目上限（防御：长期作业里候选可能很多；超出时先丢最旧的）。 */
    private static final int CACHE_MAX_ENTRIES = 512;

    private final CandidateCostProvider base;
    private final int refinePerSelect;
    private final long ttlTicks;
    private final BiFunction<ServerPlayer, Candidate, Double> refineCost;
    private final LongSupplier clock;

    /** 锚点 → (精算值, 写入时刻 tick)；存 `∞` = "精算过但失败"，TTL 后重试。 */
    private final Map<Long, Entry> cache = new HashMap<>();

    private record Entry(double cost, long tick) {
    }

    public PlanRefinedCostProvider(CandidateCostProvider base) {
        this(base, REFINE_PER_SELECT, CACHE_TTL_TICKS, null, null);
    }

    /**
     * @param refineCost 精算函数（注入点：夹具用确定性成本做纯逻辑断言）；`null` ⇒ 走 {@link MiningPlanner}
     * @param clock      时钟（注入点：夹具推进"游戏时间"以测 TTL）；`null` ⇒ 用 `level.getGameTime()`
     */
    public PlanRefinedCostProvider(CandidateCostProvider base, int refinePerSelect, long ttlTicks,
                                   BiFunction<ServerPlayer, Candidate, Double> refineCost,
                                   LongSupplier clock) {
        this.base = java.util.Objects.requireNonNull(base, "base");
        this.refinePerSelect = Math.max(0, refinePerSelect);
        this.ttlTicks = Math.max(1L, ttlTicks);
        this.refineCost = refineCost;
        this.clock = clock;
    }

    /** 生产用的完整成本链：**成本场估算 → 摊销精算**（`D-363` + `D-368`）。 */
    public static PlanRefinedCostProvider production() {
        return new PlanRefinedCostProvider(new StandingCostField());
    }

    @Override
    public Result estimate(ServerPlayer bot, GoalSpec spec, List<Candidate> candidates) {
        Result estimated = base.estimate(bot, spec, candidates);
        if (candidates.isEmpty() || refinePerSelect == 0) {
            return estimated;
        }
        long now = now(bot);
        forgetStale(now, candidates);
        List<Candidate> ranked = rankForRefine(bot, candidates, estimated);
        int newly = 0;
        for (Candidate candidate : ranked) {
            long key = candidate.anchor().asLong();
            if (cache.containsKey(key)) {
                continue;   // 已有读数（TTL 内）⇒ 不重复精算（这正是摊销的来源）
            }
            if (newly >= refinePerSelect) {
                break;      // 本 tick 精算预算用尽（**有界**）
            }
            newly++;
            double cost = refineCost(bot, candidate);
            // 失败也记住：否则同一个失败候选每次都会挡住轮转 ⇒ 覆盖永远涨不上去。
            cache.put(key, new Entry(cost, now));
            if (!Double.isFinite(cost)) {
                continue;   // 保持「估不出」；绝不据此拒绝候选（SEARCH_LIMIT != UNREACHABLE）
            }
        }
        Map<Long, Double> costs = new HashMap<>(estimated.travelByAnchor());
        cache.forEach((key, entry) -> {
            if (Double.isFinite(entry.cost())) {
                costs.put(key, entry.cost());
            }
        });
        String note = String.format(Locale.ROOT, "%s · 精算 本tick=%d 已覆盖=%d/%d（每tick≤%d · TTL=%dtick）",
                estimated.note(), newly, coveredCount(candidates), candidates.size(),
                refinePerSelect, ttlTicks);
        if (newly > 0 && coveredCount(candidates) < candidates.size()) {
            BotLog.info("[MineCost] 摊销精算：本 tick 精算 {} 个，已覆盖 {}/{}（下次选择继续；缓存 TTL={} tick）",
                    newly, coveredCount(candidates), candidates.size(), ttlTicks);
        }
        return new Result(costs, estimated.estimatedCells(), note);
    }

    /** 缓存里已有读数的候选数（含"精算过但失败"）——夹具判据用。 */
    public int coveredCount(List<Candidate> candidates) {
        int covered = 0;
        for (Candidate candidate : candidates) {
            if (cache.containsKey(candidate.anchor().asLong())) {
                covered++;
            }
        }
        return covered;
    }

    /** 缓存里拿到**有限真实成本**的候选数（夹具判据用）。 */
    public int refinedCount() {
        return (int) cache.values().stream().filter(entry -> Double.isFinite(entry.cost())).count();
    }

    /** TTL 过期 + 已不在候选集（被采走/消失）的条目要剔除；超上限时丢最旧的。 */
    private void forgetStale(long now, List<Candidate> candidates) {
        java.util.Set<Long> alive = new java.util.HashSet<>();
        for (Candidate candidate : candidates) {
            alive.add(candidate.anchor().asLong());
        }
        cache.entrySet().removeIf(entry -> !alive.contains(entry.getKey())
                || now - entry.getValue().tick() > ttlTicks);
        if (cache.size() > CACHE_MAX_ENTRIES) {
            cache.entrySet().stream()
                    .sorted(Comparator.comparingLong(entry -> entry.getValue().tick()))
                    .limit(cache.size() - CACHE_MAX_ENTRIES)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(cache::remove);
        }
    }

    private long now(ServerPlayer bot) {
        return clock != null ? clock.getAsLong() : bot.serverLevel().getGameTime();
    }

    private double refineCost(ServerPlayer bot, Candidate candidate) {
        if (refineCost != null) {
            return refineCost.apply(bot, candidate);
        }
        MiningPlanner.Result result = new MiningPlanner().plan(bot, candidate.anchor());
        return result.success() ? result.score().getScore() : Double.POSITIVE_INFINITY;
    }

    /**
     * **精算顺序**（纯函数，夹具可逐条断言）：先按估算成本从便宜到贵（有限成本的排前面），
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
        net.minecraft.core.BlockPos anchor = candidate.anchor();
        return Math.sqrt(bot.distanceToSqr(anchor.getX() + 0.5D, anchor.getY() + 0.5D,
                anchor.getZ() + 0.5D));
    }

    /** 归因用：本提供者的说明（进 `[MineJob]`/决策日志）。 */
    public String describe() {
        return "成本场 + 摊销精算（每tick≤" + refinePerSelect + " 次 · TTL=" + ttlTicks
                + "tick · break 分量见 MiningPlanner 的路径成本）";
    }
}
