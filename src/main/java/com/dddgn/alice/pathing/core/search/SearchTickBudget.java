package com.dddgn.alice.pathing.core.search;

import com.dddgn.alice.log.BotLog;

/**
 * **每 tick 搜索总账**（A1 / 2026-09-21）：一个 server tick 内所有路径搜索的**共享**毫秒账与次数账。
 *
 * <p>为什么必须有（真机第四轮取证 + 日志复算，`docs/reviews/2026-09-21-客户端第四轮-深矿搜索卡顿.md`）：
 * <ul>
 *   <li>{@link CorePathPlanner#DEFAULT_MAX_MILLIS}（`D-369`）框住的是**单次**搜索（200 ms），
 *       **没有框住"一个 tick 的总花费"**；</li>
 *   <li>真机实测：一次 `MiningPlanner.planTunnel` 对 13 个站位候选各跑一次全预算搜索
 *       ⇒ 单个 tick 花掉 ≈ 2.4 s；`[Job] step` 间隔被实测为 2.4 s（≈0.4 TPS，持续 57.6 s）；
 *       `[Search] 超 tick 预算` 共 **376 条**、最大一波 **336 条跨 57.6 s**；</li>
 *   <li>这与项目自己解决过的 {@code WriteBudget} 是**同一类问题**：每个消费者都"以为自己在预算内"，
 *       但预算不在同一个账上 ⇒ 累加爆掉。搜索花费此前处在"`WriteBudget` 出现之前"的那个状态。</li>
 * </ul>
 *
 * <p><b>本类的形状照 {@code WriteBudget}</b>（`action/WriteBudget.java`）：
 * 一个**共享账本** + 一条**超限即拒**的硬闸门 + **口径进日志**（不静默丢弃归因）。
 *
 * <p><b>语义（三条，必须一起看）</b>：
 * <ol>
 *   <li><b>账是 per-tick 的</b>：tick 一换就清零（{@link #handleTick(long)}，键 = `level.getGameTime()`）。
 *       ⚠️ 这也是它能治"掉刻"的原因 —— 一个 tick **被卡住不返回**时 gameTime 不变，
 *       所以"本 tick 已花 2.4 s"会持续累加，后续搜索被拒；tick 返回后才归零。</li>
 *   <li><b>第一个搜索永远放行</b>：判据是"**已累计** ≥ 上限"，累计为 0 时必然放行
 *       ⇒ 不会因为预算把整条路径能力锁死（单次搜索自己的上限仍是
 *       {@link CorePathPlanner#DEFAULT_MAX_MILLIS}）。</li>
 *   <li><b>超限 = 诚实的 {@code SEARCH_LIMIT}</b>（`D-076`：`SEARCH_LIMIT ≠ UNREACHABLE`）
 *       —— 拒绝**不是**"到不了"，也**不是**把请求静默丢掉：`CorePathPlanner` 会带着
 *       `tick_search_budget_exhausted` 诊断字段把它交出去。</li>
 * </ol>
 *
 * <p><b>为什么用毫秒而不是"次数"做主判据</b>：语义不同的请求一次可以合法地连发多个
 * （`[Job] select` 的精算 1 次 → `MineTask` 规划 1..13 次 → 到位行走 1 次），
 * 用次数会误伤正常链条；而**毫秒直接对应"这个 tick 会不会超 50 ms"**。
 * 次数账作为**兜底**（防"很多次极廉价搜索"的固定开销）。
 *
 * <p>⚠️ **线程**：搜索跑在**服务端 tick 线程**上（`PathRetryRunner.tick → PathSession.tick`），
 * 本类只有那一个线程读写 ⇒ 全部字段**非同步**（与 {@code PathingStats} 同口径）。
 * 将来若把搜索搬到独立线程（`D-036` 登记的根治手段），本账必须随之改成每 tick 快照交换。
 */
public final class SearchTickBudget {

    /**
     * ⭐ **主判据：本 tick 允许"烧掉自己预算"的搜索次数**（默认 1）。
     *
     * <p>为什么主判据是**"烧预算的搜索次数"**而不是总毫秒 —— 这是电池实测逼出来的区分
     * （2026-09-21 第一次实现用"总毫秒 ≤150 ms"⇒ `mine_menu` 步 **1 条既有判据变红**：
     * 夹具在一个 tick 里连做了 **20 次极廉价搜索，累计 161 ms** ⇒ 后面的规划被拒 ⇒
     * `就地挖：远处先得到真计划` 拿不到计划）。**那 20 次不是病灶**：
     * <ul>
     *   <li>**病灶**：真机第四轮 13 次搜索**每次都烧满 200 ms**（`nodes=20000 status=SEARCH_LIMIT`
     *       ≈ 185–200 ms/次）⇒ 单 tick 2.4 s；</li>
     *   <li>**廉价搜索**：找到路的搜索普遍 0–2 ms（`[PathRetry] plan … ms=0 nodes=2`）⇒
     *       "次数多"本身不伤 tick，**"每次都搜不动"才伤**。</li>
     * </ul>
     * ⇒ 判据必须落在"**这次搜索有没有真烧掉时间**"上（阈值 {@link #EXPENSIVE_SEARCH_MILLIS}），
     * 否则闸门会误伤"一个 tick 里连做一串廉价搜索"的正常链条。
     */
    public static final int DEFAULT_MAX_EXPENSIVE_SEARCHES_PER_TICK = 1;

    /**
     * 一次搜索花到这个毫秒数以上 ⇒ 记一次"烧预算的搜索"（{@link #DEFAULT_MAX_EXPENSIVE_SEARCHES_PER_TICK} 的计数单位）。
     *
     * <p>为什么是 100 ms：单次搜索上限是 200 ms（`D-369`）⇒ 本阈值 = "**烧掉了自己一半以上的预算**"；
     * 而电池实测的正常搜索 ≤ 9 ms ⇒ 两者相差一个数量级，**不存在"卡在阈值附近"的抖动风险**。
     */
    public static final long EXPENSIVE_SEARCH_MILLIS = 100L;

    /**
     * 总毫秒**兜底**上限（默认 400 ms）：防"很多次廉价搜索加起来也很贵"与
     * `SearchBudget.UNLIMITED`（`maxMillis=0` ⇒ 时间轴上永不超时）这两类。
     *
     * <p>为什么是 400 ms（≈8 倍 tick 预算）而不是贴住 50 ms：主判据已经拦住了病灶，
     * 这一轴只需兜住"量变"；贴太紧会**误伤正常链条**（真机实测选择期成本场单次就有 `ms=69`）。
     */
    public static final long DEFAULT_MAX_MILLIS_PER_TICK = 400L;

    /** 次数兜底上限（默认值）：防"很多次极廉价搜索"的固定开销（每次搜索都有分配/建索引的成本）。 */
    public static final int DEFAULT_MAX_SEARCHES_PER_TICK = 32;

    /** 当前 tick 号（`level.getGameTime()`）；{@code Long.MIN_VALUE} = 还没见过任何 tick。 */
    private static long tick = Long.MIN_VALUE;
    /** 本 tick 已花在搜索上的毫秒（含 {@link #recordExternal} 记入的外部消费者）。 */
    private static long millis;
    /** 本 tick 已**发起**的搜索次数。 */
    private static int searches;
    /** 本 tick 已发生的"烧预算的搜索"次数（见 {@link #EXPENSIVE_SEARCH_MILLIS}）。 */
    private static int expensiveSearches;
    /** 本 tick 被拒的新搜索次数。 */
    private static int refused;
    /** 本 tick 是否已经为"第一次拒绝"打过一条日志（一个 tick 只打一条，防刷屏）。 */
    private static boolean refusalLogged;

    private static long limitMillis = DEFAULT_MAX_MILLIS_PER_TICK;
    private static int limitExpensive = DEFAULT_MAX_EXPENSIVE_SEARCHES_PER_TICK;
    private static int limitSearches = DEFAULT_MAX_SEARCHES_PER_TICK;

    private SearchTickBudget() {
    }

    /**
     * tick 边界：tick 号变了就清零。**所有入口都必须先调它**（否则账会跨 tick 累加，把闸门变成永久关闭）。
     *
     * @param gameTime `ServerLevel.getGameTime()` —— 真机与无头电池都用它；
     *                 ⚠️ 不要用 `System.currentTimeMillis()`（那是墙钟，掉刻时它照样在走，
     *                 会把"一个被卡住的 tick"误判成"很多个 tick"从而放过累积）。
     */
    public static void handleTick(long gameTime) {
        if (gameTime == tick) {
            return;
        }
        tick = gameTime;
        millis = 0L;
        searches = 0;
        expensiveSearches = 0;
        refused = 0;
        refusalLogged = false;
    }

    /**
     * 申请发起一次搜索。{@code false} = **本 tick 的预算已用尽**，调用方必须**如实拒绝**（不许继续搜）。
     *
     * <p>判据用"**已累计**"而不是"累计 + 本次预估"：本类不猜下一次要花多久
     * （`D-369` 的教训就是"估算出来的上限不可信"），只做"花到线就不再开新的"。
     *
     * <p>三条轴**任一**到线即拒（主判据在最前，它是唯一真正对应病灶的那条）：
     * ① 烧预算的搜索次数 ≥ {@link #DEFAULT_MAX_EXPENSIVE_SEARCHES_PER_TICK}；
     * ② 总毫秒 ≥ {@link #DEFAULT_MAX_MILLIS_PER_TICK}；
     * ③ 搜索次数 ≥ {@link #DEFAULT_MAX_SEARCHES_PER_TICK}。
     */
    public static boolean tryAcquire() {
        if (expensiveSearches >= limitExpensive || millis >= limitMillis || searches >= limitSearches) {
            refused++;
            if (!refusalLogged) {
                refusalLogged = true;
                BotLog.warn("[SearchTick] 本 tick 搜索预算已用尽 tick={} 已发起={} 次（其中烧预算 {} 次）"
                                + " · 累计={}ms ≥ {}ms · 上限[烧预算={} 毫秒={} 次数={}]"
                                + " ⇒ 本 tick 不再起新搜索（诚实 SEARCH_LIMIT，不是不可达）"
                                + " —— A1/每 tick 总账，见 survey/24 §1.2",
                        tick, searches, expensiveSearches, millis, limitMillis,
                        limitExpensive, limitMillis, limitSearches);
            }
            return false;
        }
        searches++;
        return true;
    }

    /**
     * 一次搜索结束后记入它实际花掉的毫秒（`PathPlan.elapsedMillis()`）。
     *
     * <p>⭐ 这里是**主判据的计量点**：花到 {@link #EXPENSIVE_SEARCH_MILLIS} 以上就记一次"烧预算的搜索"。
     */
    public static void recordMillis(long elapsedMillis) {
        if (elapsedMillis <= 0L) {
            return;
        }
        millis += elapsedMillis;
        if (elapsedMillis >= EXPENSIVE_SEARCH_MILLIS) {
            expensiveSearches++;
        }
    }

    /**
     * 记入**不受本闸门管辖**的同 tick 搜索类消费者（今天只有选择期的成本场 Dijkstra）。
     *
     * <p>为什么"记但不拦"：成本场有它自己的两个界（`MiningTuning.costFieldMaxCost` /
     * `costFieldMaxNodes`，且在被拒时退化成"估不出成本"而不是失败），**拦它会把目标选择搞死**；
     * 但它和路径搜索**争的是同一个 50 ms**（真机实测一次 `estimate=DIJKSTRA … ms=69`）
     * ⇒ 必须进同一个账，否则下一个"预算不在同一个账上"的坑就是它。
     *
     * <p>⚠️ 它**只累加毫秒、不累加"烧预算的搜索"次数**：主判据管的是"路径搜索"，而成本场有它自己的界；
     * 让成本场去吃掉路径搜索的额度，等于把"选择"和"到达"耦合起来（真机里 `ms=69` 的成本场会直接
     * 把挖矿规划挤掉）—— 那不是止血，是换一种卡法。
     */
    public static void recordExternal(long elapsedMillis) {
        if (elapsedMillis > 0L) {
            millis += elapsedMillis;
        }
    }

    // ==================== 观测口（夹具 / bot_report / 真机调参） ====================

    public static long tickMillis() {
        return millis;
    }

    public static int tickSearches() {
        return searches;
    }

    /** 本 tick"烧掉自己预算"的搜索次数（主判据的计量值）。 */
    public static int tickExpensiveSearches() {
        return expensiveSearches;
    }

    public static int tickRefusals() {
        return refused;
    }

    public static long limitMillis() {
        return limitMillis;
    }

    public static String describe() {
        return "tick=" + tick + " searches=" + searches + "/" + limitSearches
                + " expensive=" + expensiveSearches + "/" + limitExpensive
                + " millis=" + millis + "/" + limitMillis
                + " refused=" + refused;
    }

    // ==================== 夹具专用（生产不调用） ====================

    /**
     * 夹具用：临时改三条轴的上限（{@code <= 0} = 关掉该轴）。**必须在夹具结束时还原**
     * （见 {@link #restoreDefaults}）。
     *
     * <p>夹具需要它是因为**夹具是"一个 tick 里跑一整批断言"的批处理**，而生产是"一个 tick 跑一条链"：
     * 实测 `mine_menu` 步一个 tick 里做 20 次廉价搜索（161 ms）—— 那是测试形态，不是病灶形态。
     */
    public static void setLimits(long maxMillisPerTick, int maxExpensivePerTick, int maxSearchesPerTick) {
        limitMillis = maxMillisPerTick <= 0L ? Long.MAX_VALUE : maxMillisPerTick;
        limitExpensive = maxExpensivePerTick <= 0 ? Integer.MAX_VALUE : maxExpensivePerTick;
        limitSearches = maxSearchesPerTick <= 0 ? Integer.MAX_VALUE : maxSearchesPerTick;
    }

    /** 夹具用：还原生产默认。 */
    public static void restoreDefaults() {
        limitMillis = DEFAULT_MAX_MILLIS_PER_TICK;
        limitExpensive = DEFAULT_MAX_EXPENSIVE_SEARCHES_PER_TICK;
        limitSearches = DEFAULT_MAX_SEARCHES_PER_TICK;
    }

    /** 夹具用：把账清空并**离开**当前 tick（下一次 `handleTick` 会重新开始计时）。 */
    public static void resetForFixture() {
        tick = Long.MIN_VALUE;
        millis = 0L;
        searches = 0;
        expensiveSearches = 0;
        refused = 0;
        refusalLogged = false;
    }
}
