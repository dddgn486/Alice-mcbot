package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.search.SearchBudget;
import com.dddgn.alice.pathing.core.search.SearchTickBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ⭐ **每 tick 搜索预算的 A/B 台架**（电池步 `tick_budget_bench`，EXTRA）—— `P4′` 的判据仪器。
 *
 * <h2>它回答什么问题</h2>
 * 台账 `P4′` 想把 {@link SearchTickBudget#DEFAULT_MAX_MILLIS_PER_TICK}（**400 ms 的毫秒兜底轴**）
 * 收到 **≤60 ms**，判据原文是「**先红后绿**：注入 400 ⇒ CORE 出现/加重 `Can't keep up`；
 * 收到 ≤60 ⇒ 消失（**需客户端或电池可复现的读数**）」。
 * 本台架就是把那个"可复现的读数"造出来 —— 而且**一次运行同时量三种配置**（`SearchTickBudget` 有
 * 夹具专用口 `setLimits`/`resetForFixture`/`restoreDefaults` ⇒ 不用改源码重编译）。
 *
 * <h2>三种配置 × 两种负载（每格=**一个 tick 内**跑完，读数取该 tick 的账）</h2>
 * <ul>
 *   <li>配置：{@code PROD}（现行 400/1/32）· {@code TIGHTENED}（候选 60/1/32）·
 *       {@code UNGATED}（三条轴全关 = **A1 之前**的状态）；</li>
 *   <li>负载 {@code HEAVY}：**13 次全预算搜索**（`SearchBudget.of(20000, 0)`，镜像
 *       `MiningPlanner.planTunnel` 的 13 个站位候选 —— `SearchTickBudget` 头部记的真机病灶形态）；</li>
 *   <li>负载 {@code CHEAP}：**30 次廉价可达搜索**（`miningApproach` + 默认 50 ms 预算，镜像
 *       `mine_menu` 那 20 次廉价链 = 实测累计 161 ms 的形态）。</li>
 * </ul>
 *
 * <h2>为什么"红臂"只能靠 {@code UNGATED}</h2>
 * 原版 `MinecraftServer.run` 的告警阈值是**单 tick 落后 > 2000 ms** 才打 `Can't keep up!`
 * ⇒ 要复现它，就必须让一个 tick 真的烧掉 2 s 以上，而**闸门开着时做不到**（下面的算术界由读数直接证实）。
 * 所以本台架的 {@code UNGATED} 那格就是**红臂**：它把 A1 之前的形态量出来（真机实测 2.4 s/tick），
 * 并且**预期在电池日志里打出 `Can't keep up!`** —— 那是本台架唯一的"故意制造卡顿"，也是 `P4′`
 * 判据里"红"的唯一可复现来源。
 *
 * <h2>判据（5 条，全部是读数，不是推断）</h2>
 * <ol>
 *   <li>前提：{@code UNGATED} 的 HEAVY 负载每次搜索都 ≥ {@link SearchTickBudget#EXPENSIVE_SEARCH_MILLIS}
 *       （否则"贵搜索"没造出来 ⇒ 本台架无效）；</li>
 *   <li>不变量（闸门真的框住了 tick）：{@code PROD}/{@code TIGHTENED} 的每一格
 *       `tickMillis ≤ limitMillis + 单次最大 ms`（账只做"花到线就不再开新的"⇒ 允许最后一次放行超出）；</li>
 *   <li>⭐ 红臂前提：{@code UNGATED} HEAVY 的单 tick wall ms **≥ 2000**（= 只有无闸门态够触发原版告警）；</li>
 *   <li>⭐ 效益与代价（{@code P4′} 的核心读数）：`TIGHTENED` 的 CHEAP 格**必须比 {@code PROD} 少烧毫秒**
 *       且**已经开始拒绝**（`refused > 0`）—— 反过来 `PROD` 的 CHEAP 拒绝数就是"400 这个值救了谁"的读数
 *       （`mine_menu` 的 161 ms 链正是在这一档被放过）；</li>
 *   <li>夹具纪律：三条轴**必须还原成生产默认** + 自建基岩壳**逐格还原**。</li>
 * </ol>
 *
 * <h2>刻意不做的事</h2>
 * 本台架**不判**"该不该收紧到 60"（那不是台架能定的：它同时是能力决策 —— 收紧会让
 * `mine_menu` 那类正常链条开始被拒）。它只把两侧读数摆出来，让裁定有据。
 */
public final class TickBudgetBenchTask implements Task {

    /** 基岩壳半径（先声明：下面的常量要用它）。 */
    private static final int SHELL_RADIUS = 2;
    /** 专用孤立点（与其它夹具相距 60+ 格）。 */
    private static final BlockPos START = new BlockPos(3200, -60, 3800);
    /** 基岩壳中心（相对 `START`）：目标格在壳内的空气中 ⇒ **不可达**（基岩不可破）⇒ 搜索烧满节点上限。 */
    private static final BlockPos SHELL_CENTER = START.offset(12, 0, 0);
    /** 廉价链的目标（附近的土层里，可达但要走一小段挖入计划）。 */
    private static final BlockPos NEAR_GOAL = START.offset(14, -2, 10);
    /** 台架在上面站住的格子（基岩壳顶面之上）：那里**必然**有支撑 ⇒ 前提可判。 */
    private static final BlockPos PERCH = SHELL_CENTER.offset(0, SHELL_RADIUS + 1, 0);
    /** HEAVY：镜像 `MiningPlanner.planTunnel` 的 13 个站位候选。 */
    private static final int HEAVY_SEARCHES = 13;
    /** CHEAP：镜像 `mine_menu` 的廉价链（那一步实测 20 次 / 累计 161 ms；这里取 30 次，
     *  且**刻意留在次数轴上限 32 以下** ⇒ 拒绝只能来自毫秒/贵搜索两轴）。 */
    private static final int CHEAP_SEARCHES = 30;
    /** 原版告警阈值（`MinecraftServer.run`：单 tick 落后 > 2000 ms 才打 `Can't keep up!`）。 */
    private static final long KEEP_UP_WARN_MILLIS = 2000L;

    private static final int BUDGET_TICKS = 300;
    /** 等 `onGround` 的上限（超了就让前提如实红）。 */
    private static final int MAX_SETTLE_TICKS = 40;

    /** 三种配置（0 = 关掉该轴）。 */
    private enum Case {
        /** 现行生产默认：400 / 1 / 32。 */
        PROD("PROD(400/1/32)", SearchTickBudget.DEFAULT_MAX_MILLIS_PER_TICK, 1, 32),
        /** `P4′` 的候选：60 / 1 / 32。 */
        TIGHTENED("TIGHTENED(60/1/32)", 60L, 1, 32),
        /** 反向对照 / 红臂：闸门全关（= A1 之前，真机实测 2.4 s/tick）。 */
        UNGATED("UNGATED(0/0/0)", 0L, 0, 0);

        final String label;
        final long maxMillis;
        final int maxExpensive;
        final int maxSearches;

        Case(String label, long maxMillis, int maxExpensive, int maxSearches) {
            this.label = label;
            this.maxMillis = maxMillis;
            this.maxExpensive = maxExpensive;
            this.maxSearches = maxSearches;
        }
    }

    /**
     * 三种负载。⚠️ **`MINING` 才是生产形态**（每次搜索受它自己的 50 ms 上限约束 = `DEFAULT_MAX_MILLIS`）；
     * `LEGACY` 是 `D-369` 时代的历史形态（每次 ~185–200 ms，13 次 = 2.4 s/tick，就是真机那三次
     * `Can't keep up` 的来源）⇒ 只有它够触发原版告警，所以它同时是**红臂**。
     */
    private enum Load {
        /** 生产形态：13 次「自己的 50 ms 上限」搜索（镜像 `MiningPlanner.planTunnel` 的 13 个站位候选）。 */
        MINING("MINING(13×50ms上限)", HEAVY_SEARCHES, CorePathPlanner.DEFAULT_MAX_MILLIS, true),
        /** 廉价链：30 次短搜索（镜像 `mine_menu` 的 20 次 / 161 ms）。 */
        CHEAP("CHEAP(30×短搜索)", CHEAP_SEARCHES, CorePathPlanner.DEFAULT_MAX_MILLIS, false),
        /** 历史形态 / 红臂：13 次**无时间上限**的 20000 节点搜索。 */
        LEGACY("LEGACY(13×无时间上限)", HEAVY_SEARCHES, 0L, true);

        final String label;
        final int searches;
        final long budgetMillis;
        final boolean shellGoal;

        Load(String label, int searches, long budgetMillis, boolean shellGoal) {
            this.label = label;
            this.searches = searches;
            this.budgetMillis = budgetMillis;
            this.shellGoal = shellGoal;
        }
    }

    /** 一格读数（配置 × 负载）。 */
    private record Reading(Case config, Load load, long wallMillis, long accountMillis, int searches,
                           int expensive, int refused, long minPerSearch, long maxPerSearch,
                           int reached, String statuses) {
    }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final Map<BlockPos, BlockState> saved = new LinkedHashMap<>();
    private final List<Reading> readings = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private final List<String> findings = new ArrayList<>();

    private int ticks;
    /** 等 `onGround` 立起来的 tick 数（读数：这条实测值本身有用）。 */
    private int settleTicks;
    private int checks;
    private int index;
    private boolean done;

    public TickBudgetBenchTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "TickBudgetBench";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(START);
    }

    @Override
    public String failureReason() {
        return String.join(" | ", failures);
    }

    @Override
    public String terminalReason() {
        return done ? (failures.isEmpty() ? "passed" : "failed") : "";
    }

    @Override
    public Status tick() {
        if (done) {
            return failures.isEmpty() ? Status.DONE : Status.FAILED;
        }
        if (++ticks > BUDGET_TICKS) {
            check("台架必须在预算内跑完（" + BUDGET_TICKS + " tick）", false);
            restoreAll();     // 夹具纪律：失败路径同样复位
            return finish();
        }
        if (index == 0) {
            buildAndTeleport();
            index = 1;
            return Status.RUNNING;
        }
        if (index == 1) {
            // ⚠️ `teleport` 会把 `onGround` 按成 false（`D-427` 的实测教训），而且这里量到它**不止要 2 tick**
            // 才立起来（第一版写死 `ticks < 3` ⇒ 前提假红）⇒ 等到真的站住（或到上限，那时前提会如实红）。
            if (!bot.onGround() && ++settleTicks < MAX_SETTLE_TICKS) {
                return Status.RUNNING;
            }
            BotLog.info("[TickBench] settle onGround={} 用了 {} tick", bot.onGround(), settleTicks);
            setupChecks();
            index = 2;
            return Status.RUNNING;
        }
        int total = Case.values().length * Load.values().length;
        if (index < 2 + total) {
            int flat = index - 2;
            runCell(Case.values()[flat / Load.values().length], Load.values()[flat % Load.values().length]);
            index++;
            return Status.RUNNING;
        }
        if (index == 2 + total) {
            verdicts();
            index++;
            return Status.RUNNING;
        }
        restoreAll();
        return finish();
    }

    // ==================== 场景 ====================

    private void buildAndTeleport() {
        ServerLevel level = bot.serverLevel();
        teleport(PERCH);
        for (int dx = -SHELL_RADIUS; dx <= SHELL_RADIUS; dx++) {
            for (int dy = -SHELL_RADIUS; dy <= SHELL_RADIUS; dy++) {
                for (int dz = -SHELL_RADIUS; dz <= SHELL_RADIUS; dz++) {
                    BlockPos pos = SHELL_CENTER.offset(dx, dy, dz);
                    boolean shell = Math.abs(dx) == SHELL_RADIUS || Math.abs(dy) == SHELL_RADIUS
                            || Math.abs(dz) == SHELL_RADIUS;
                    setFixtureBlock(level, pos, shell ? Blocks.BEDROCK.defaultBlockState()
                            : Blocks.AIR.defaultBlockState());
                }
            }
        }
        BotLog.info("[TickBench] setup start={} shell_center={} perch={} mining={} cheap={} legacy={}",
                START.toShortString(), SHELL_CENTER.toShortString(), PERCH.toShortString(),
                HEAVY_SEARCHES, CHEAP_SEARCHES, HEAVY_SEARCHES);
    }

    private void setupChecks() {
        ServerLevel level = bot.serverLevel();
        int cells = (2 * SHELL_RADIUS + 1) * (2 * SHELL_RADIUS + 1) * (2 * SHELL_RADIUS + 1);
        check("前提：基岩壳建好（中心=" + SHELL_CENTER.toShortString() + " 是空气、壳面 "
                        + SHELL_CENTER.offset(SHELL_RADIUS, 0, 0).toShortString() + " 是基岩，共 "
                        + cells + " 格）",
                level.getBlockState(SHELL_CENTER).isAir()
                        && level.getBlockState(SHELL_CENTER.offset(SHELL_RADIUS, 0, 0))
                        .is(Blocks.BEDROCK));
        // ⚠️ `teleport` 会把 `onGround` 按成 false（`D-427` 实测）⇒ 站到**必然有支撑**的壳顶再等物理
        boolean perched = MovementHelper.footCell(level, bot).equals(PERCH) && bot.onGround();
        check("前提：bot 站在壳顶（脚位=" + MovementHelper.footCell(level, bot).toShortString()
                        + " 期望 " + PERCH.toShortString() + " onGround=" + bot.onGround() + "）", perched);
        check("前提：生产默认的三条轴就是被量的那个值（limitMillis="
                        + SearchTickBudget.limitMillis() + " == " + SearchTickBudget.DEFAULT_MAX_MILLIS_PER_TICK
                        + "）", SearchTickBudget.limitMillis() == SearchTickBudget.DEFAULT_MAX_MILLIS_PER_TICK);
        warmup();
    }

    /** 预热一次（JIT）：否则第一格永远是"冷"读数（第一版实测 PROD/MINING=1141 ms vs 同工作量 403 ms 的假差）。 */
    private void warmup() {
        SearchTickBudget.resetForFixture();
        PathRequest base = PathRequest.miningApproach(bot.getUUID().toString(), START, NEAR_GOAL,
                "tick-budget-bench-warmup");
        PathRequest request = new PathRequest(base.botId(), START, base.goal(), base.allowedMovementTypes(),
                SearchBudget.of(CorePathPlanner.DEFAULT_MAX_NODES, CorePathPlanner.DEFAULT_MAX_MILLIS),
                base.requester());
        PathPlan plan = new CorePathPlanner().plan(bot, bot.serverLevel(), request);
        BotLog.info("[TickBench] warmup status={} ms={}", plan.status(), plan.elapsedMillis());
    }

    private void setFixtureBlock(ServerLevel level, BlockPos pos, BlockState state) {
        saved.putIfAbsent(pos.immutable(), level.getBlockState(pos));
        level.setBlockAndUpdate(pos, state);
    }

    // ==================== 一格 = 一个 tick 内跑完的负载 ====================

    /**
     * 跑一格：**先按配置装上三条轴，再把整个负载在同一个 tick 里打完**，最后读这一 tick 的账。
     *
     * <p>⚠️ 每个负载都要先 {@link SearchTickBudget#resetForFixture()}：它把账清零并"离开当前 tick"
     * ⇒ 下一次 `handleTick` 重新开始计时 ⇒ 得到的是**干净的一 tick**（否则上一格的残留会串味）。
     */
    private void runCell(Case config, Load load) {
        SearchTickBudget.resetForFixture();
        SearchTickBudget.setLimits(config.maxMillis, config.maxExpensive, config.maxSearches);
        BlockPos goal = load.shellGoal ? SHELL_CENTER : NEAR_GOAL;
        long minMs = Long.MAX_VALUE;
        long maxMs = 0L;
        int reached = 0;
        StringBuilder statuses = new StringBuilder();
        long wallStart = System.nanoTime();
        for (int i = 0; i < load.searches; i++) {
            PathRequest base = PathRequest.miningApproach(bot.getUUID().toString(), START, goal,
                    "tick-budget-bench");
            PathRequest request = new PathRequest(base.botId(), START, base.goal(),
                    base.allowedMovementTypes(),
                    SearchBudget.of(CorePathPlanner.DEFAULT_MAX_NODES, load.budgetMillis),
                    base.requester());
            PathPlan plan = new CorePathPlanner().plan(bot, bot.serverLevel(), request);
            long ms = plan.elapsedMillis();
            minMs = Math.min(minMs, ms);
            maxMs = Math.max(maxMs, ms);
            if (plan.reached()) {
                reached++;
            }
            if (statuses.length() < 60) {
                statuses.append(statuses.isEmpty() ? "" : ",").append(plan.status()).append(':').append(ms);
            }
        }
        long wall = (System.nanoTime() - wallStart) / 1_000_000L;
        readings.add(new Reading(config, load, wall, SearchTickBudget.tickMillis(),
                SearchTickBudget.tickSearches(), SearchTickBudget.tickExpensiveSearches(),
                SearchTickBudget.tickRefusals(), minMs == Long.MAX_VALUE ? 0L : minMs, maxMs, reached,
                statuses.toString()));
        BotLog.info("[TickBench] cell config={} load={} wall={}ms account={}ms searches={} expensive={} "
                        + "refused={} per_search={}..{}ms reached={} statuses=[{}]",
                config.label, load.label, wall, SearchTickBudget.tickMillis(), SearchTickBudget.tickSearches(),
                SearchTickBudget.tickExpensiveSearches(), SearchTickBudget.tickRefusals(), minMs, maxMs,
                reached, statuses);
    }

    // ==================== 判据 ====================

    private void verdicts() {
        Reading legacyUngated = find(Case.UNGATED, Load.LEGACY);
        Reading prodMining = find(Case.PROD, Load.MINING);
        Reading tightMining = find(Case.TIGHTENED, Load.MINING);
        Reading prodCheap = find(Case.PROD, Load.CHEAP);
        Reading tightCheap = find(Case.TIGHTENED, Load.CHEAP);
        if (legacyUngated == null || prodMining == null || tightMining == null
                || prodCheap == null || tightCheap == null) {
            check("九格读数必须齐全（实际 " + readings.size() + " 格）", false);
            return;
        }

        // ① 前提：红臂那格的搜索真的"贵"（否则本台架量的是空气）
        check("① 前提：UNGATED 的 LEGACY 每次搜索都 ≥ `EXPENSIVE_SEARCH_MILLIS`（"
                        + SearchTickBudget.EXPENSIVE_SEARCH_MILLIS + " ms；实际 "
                        + legacyUngated.minPerSearch + ".." + legacyUngated.maxPerSearch + " ms）",
                legacyUngated.minPerSearch >= SearchTickBudget.EXPENSIVE_SEARCH_MILLIS);

        // ② 不变量：闸门开着时，一个 tick 的搜索账不许超过「毫秒上限 + 该格单次最大 ms」
        for (Reading reading : readings) {
            if (reading.config == Case.UNGATED) {
                continue;   // 红臂那几格闸门是关的，不适用
            }
            long bound = reading.config.maxMillis + reading.maxPerSearch;
            check("② 不变量：闸门框住了这个 tick（" + reading.config.label + "/" + reading.load.label
                            + " 账=" + reading.accountMillis + "ms ≤ 上限 " + reading.config.maxMillis
                            + " + 单次最大 " + reading.maxPerSearch + " = " + bound + "ms）",
                    reading.accountMillis <= bound);
        }

        // ③ 红臂前提：只有"闸门全关 + 历史形态"那格够触发原版告警（>2000 ms）
        check("③ 红臂前提：UNGATED/LEGACY 单 tick wall ≥ " + KEEP_UP_WARN_MILLIS + " ms（实际 "
                        + legacyUngated.wallMillis + " ms）—— 只有这一态够触发 `Can't keep up!`"
                        + "（原版阈值 = 单 tick 落后 >2000 ms）；闸门开着时它在算术上够不到",
                legacyUngated.wallMillis >= KEEP_UP_WARN_MILLIS);

        // ④ 效益 + 代价（P4′ 的核心读数）：生产形态那两格
        check("④ 效益：收紧到 60 ms 后**生产形态**一个 tick 少烧毫秒（TIGHTENED=" + tightMining.accountMillis
                        + " ms < PROD=" + prodMining.accountMillis + " ms）",
                tightMining.accountMillis < prodMining.accountMillis);
        check("④ 代价：收紧后生产形态的搜索链开始被拒（TIGHTENED refused=" + tightMining.refused
                        + "；PROD refused=" + prodMining.refused + "）", tightMining.refused > 0);
        check("④ 廉价链同向（TIGHTENED " + tightCheap.accountMillis + " ms < PROD "
                        + prodCheap.accountMillis + " ms；拒 " + tightCheap.refused + " vs "
                        + prodCheap.refused + "）", tightCheap.accountMillis < prodCheap.accountMillis);

        for (Reading reading : readings) {
            findings.add(reading.config.label + "/" + reading.load.label + ":wall=" + reading.wallMillis
                    + "ms account=" + reading.accountMillis + "ms searches=" + reading.searches
                    + " expensive=" + reading.expensive + " refused=" + reading.refused
                    + " per_search=" + reading.minPerSearch + ".." + reading.maxPerSearch
                    + "ms reached=" + reading.reached);
        }
        BotLog.info("[TickBench] 读数表 {}", findings);
    }

    private Reading find(Case config, Load load) {
        for (Reading reading : readings) {
            if (reading.config == config && reading.load == load) {
                return reading;
            }
        }
        return null;
    }

    // ==================== 复位 ====================

    /** 夹具纪律：三条轴还原成生产默认 + 自建基岩壳逐格还原（两条都**量**，不靠"应该还原了"）。 */
    private void restoreAll() {
        SearchTickBudget.restoreDefaults();
        check("⑤ 复位：搜索三条轴已还原成生产默认（limitMillis=" + SearchTickBudget.limitMillis()
                        + " == " + SearchTickBudget.DEFAULT_MAX_MILLIS_PER_TICK + "）",
                SearchTickBudget.limitMillis() == SearchTickBudget.DEFAULT_MAX_MILLIS_PER_TICK);
        ServerLevel level = bot.serverLevel();
        int touched = saved.size();
        int mismatched = 0;
        for (Map.Entry<BlockPos, BlockState> entry : saved.entrySet()) {
            level.setBlockAndUpdate(entry.getKey(), entry.getValue());
            if (!level.getBlockState(entry.getKey()).equals(entry.getValue())) {
                mismatched++;
            }
        }
        saved.clear();
        check("⑤ 复位：本台架动过的 " + touched + " 格已按原样还原（不匹配=" + mismatched + "）",
                touched > 0 && mismatched == 0);
    }

    private void teleport(BlockPos foot) {
        bot.teleportTo(bot.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
    }

    private Status finish() {
        boolean pass = failures.isEmpty();
        String summary = "tick_budget_bench=" + (pass ? "PASS" : "FAIL")
                + " checks=" + checks + " cells=" + readings.size()
                + " prod_mining_ms=" + value(Case.PROD, Load.MINING)
                + " tight_mining_ms=" + value(Case.TIGHTENED, Load.MINING)
                + " prod_mining_refused=" + refused(Case.PROD, Load.MINING)
                + " tight_mining_refused=" + refused(Case.TIGHTENED, Load.MINING)
                + " prod_cheap_ms=" + value(Case.PROD, Load.CHEAP)
                + " tight_cheap_ms=" + value(Case.TIGHTENED, Load.CHEAP)
                + " undated_legacy_ms=" + value(Case.UNGATED, Load.LEGACY);
        if (!pass) {
            summary += " failures=" + failures;
        }
        BotLog.info("[TickBench] SUMMARY {}", summary);
        for (String finding : findings) {
            BotLog.info("[TickBench] table {}", finding);
        }
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] tick 预算台架 "
                    + (pass ? "PASS" : "FAIL " + failures)));
        }
        done = true;
        return pass ? Status.DONE : Status.FAILED;
    }

    private long value(Case config, Load load) {
        Reading reading = find(config, load);
        return reading == null ? -1L : reading.accountMillis;
    }

    private int refused(Case config, Load load) {
        Reading reading = find(config, load);
        return reading == null ? -1 : reading.refused;
    }

    private void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures.add(what);
        }
    }
}
