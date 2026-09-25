package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.search.PlanningStatus;
import com.dddgn.alice.pathing.core.search.SearchTickBudget;
import com.dddgn.alice.task.mining.MiningPlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ⭐ `P1-b`（2026-09-22 真机根因）的**行为级红对照**：A1 每 tick 搜索总账拒绝一次搜索时，
 * 挖掘规划器**不许**把「本轮没评价完」写成「不可挖 / 不可达」。
 *
 * <h2>它钉的是哪条链（真机证据）</h2>
 * <pre>
 * 真机 `latest.log`（2026-09-22 09:18-09:21，作业 `mined 19/64` / `6/8`，两次 `FAILED partial_quota`）：
 *   [Search] 超 tick 预算 … ⇒ 该次搜索独占了这个 tick        ×234
 *   [MiningPlanner] mode=TUNNEL … planned=1 capped=true reason=search_incomplete searchLimited=true ×206
 *   [MiningPlanner] found_but_unminable direct=no_valid_standing_point
 *                   tunnel=no_reachable_tunnel_standing_point enter=enter_target_unreachable   ×377
 *   [MineSurvey] 失败=found_but_unminable×49, MOVE_MOVEMENT_FAILED×12
 * </pre>
 * ⇒ `SEARCH_LIMIT`（预算不够，**本轮没评价完**）被 `planTunnel` / `planEnterTarget` / `exactTopK`
 * **覆盖成永久性理由** ⇒ `plan()` 的 P1 合取闸门永不触发 ⇒ `MineJob` 把候选写进 `attempted` **永久了结**
 * ⇒ 矿簇挖不干净。本夹具把这条链**关在小盒子里**复现，并给出可红的判据。
 *
 * <h2>三个臂（本夹具的核心设计）</h2>
 * <ol>
 *   <li><b>BURN</b>：同一 tick 内先用公开 API 把本 tick 的搜索额度**占满**
 *       （`recordMillis(EXPENSIVE_SEARCH_MILLIS)` ⇒ 主判据「烧预算的搜索 ≥ 1」到线），
 *       再规划 ⇒ 断言理由是 `search_incomplete`（**瞬时**），**不是** `found_but_unminable` /
 *       `no_reachable_candidate` / `no_reachable_tunnel_standing_point`（**永久**）。</li>
 *   <li><b>REPLAN</b>：等 tick 边界（`handleTick` 自动清零）后**规划同一个目标、同一个世界**
 *       ⇒ 断言**规划成功**。这一步才是 `SEARCH_LIMIT ≠ UNREACHABLE` 的**行为级**证明：
 *       同一个目标、同一个几何，只差"本 tick 还有没有搜索额度"。</li>
 *   <li>⭐ <b>`P1-d`（2026-09-25 新增）</b>：造**另一种**"没得出可达性结论"的形态 ——
 *       `PARTIAL`（搜索**跑了**、烧光了自己的 50 ms 预算、只交出前缀）。
 *       场景 = 可挖煤块封在基岩壳正中心 + bot 站在壳顶；因为有一条**前提断言**要求同型搜索
 *       必须返回 `PARTIAL`（否则本臂测的是空气），所以这个形态是**确定复现**的，不是碰运气。
 *       判据 = `MiningPlanner` 的理由必须是 `search_incomplete`。
 *       ⚠️ 真机 09-24 客户端日志：撞 50 ms 上限的 502 次搜索里 **480 次是 `PARTIAL`**、
 *       只有 22 次 `SEARCH_LIMIT`（480+22=502 精确闭合）⇒ `P1-b` 那次只修了 `SEARCH_LIMIT`，
 *       这 96% 照样被写成永久理由 ⇒ `MineJob` 的 40-tick 冷却几乎不生效。</li>
 * </ol>
 *
 * <h2>反向对照（红臂）</h2>
 * <ul>
 *   <li>`P1-b` 臂：把 `MiningPlanner.planTunnel` 结尾的 `search_incomplete` 逐字保留改回无条件
 *       `no_reachable_tunnel_standing_point`（或去掉 `planEnterTarget` 的 `SEARCH_LIMIT` 分支）
 *       ⇒ BURN 相位必须**红**（理由是 `found_but_unminable`）。</li>
 *   <li>⭐ `P1-d` 臂：把 `MiningPlanner.inconclusive(...)` 里的 `PlanningStatus.PARTIAL` 去掉
 *       （回到"只认 `SEARCH_LIMIT`"）⇒ `P1-d` 相位必须**红**（理由是 `found_but_unminable`）。</li>
 * </ul>
 *
 * <h2>场景</h2>
 * 孤立空中平台 + 3×3×3 石箱，目标铁矿石**埋在石箱正中心**（六面都是石头）⇒ 模式 A 必然
 * `no_valid_standing_point`、只能走模式 B（挖出站位）⇒ 一定会发起搜索 ⇒ 一定能被 A1 拒。
 */
public final class MiningSearchLimitHonestyCheckTask implements Task {

    /** 孤立原点（其它夹具用 3600/3700 段，这里避开）。 */
    private static final BlockPos ORIGIN = new BlockPos(3800, 100, 2600);
    /** 站位平台（石地板）范围：x ∈ [原点-4, 原点-1]，z ∈ [原点-1, 原点+1]。 */
    private static final int PAD_MIN_DX = -4;
    private static final int PAD_MAX_DX = -1;
    private static final int PAD_HALF_DZ = 1;
    /** 石箱（目标埋在其中）：x/z ∈ [-1, +1]，y ∈ [0, +2]。 */
    private static final int BOX_HALF = 1;
    private static final int BOX_TOP_DY = 2;
    /** bot 脚位（相对原点）：紧贴石箱西面，站在平台上。 */
    private static final int BOT_DX = -2;
    /** 目标格 = 石箱正中心（六面皆石 ⇒ 只能挖进去）。 */
    private static final BlockPos TARGET = ORIGIN;
    private static final int BUDGET_TICKS = 400;
    /** 等 tick 边界让 A1 清零（1 tick 足够；留余量给服务端 tick 号推进）。 */
    private static final int TICK_RESET_WAIT = 3;

    // ==================== ⭐ `P1-d` 臂（2026-09-25）：`PARTIAL` 形态 ====================

    /**
     * `PARTIAL` 臂专用原点。**刻意放到 `y = -60` 深部**：`TickBudgetBenchTask` 在
     * `(3200, -60, 3800)` 实测同样形态（目标封在基岩壳里）的搜索 **183~250 ms**、
     * 打到 20 000 节点上限，且台架日志里 **80 条 `status=PARTIAL`、0 条 `SEARCH_LIMIT`**。
     *
     * <p>⚠️ **必须整场收进单一区块并留 ≥4 格边距**（实测教训，2026-09-25）：`AStarMovementSearch:161`
     * 的读脚印闸门（`D-337`）在**扩展当前节点之前**检查它的**半径 3 读脚印**是否已加载 ——
     * 第一版把平台西缘放到 `x=3215`（**区块 200**，未加载）⇒ 起点直接 `boundary_unloaded
     * blocked_nodes=1`、`nodes=1`、前缀为空 ⇒ 探针返回 `SEARCH_LIMIT` 而不是 `PARTIAL`。
     * 区块 201 = x∈[3216, 3231]，区块 240 = z∈[3840, 3855] ⇒ 起点取 (3220, 3846)，
     * 半径 3 脚印 = x∈[3217, 3223] · z∈[3843, 3849] 全部落在已加载区块内。
     *
     * <p>⚠️ **Z 取 3846 而不是 3798**：`TickBudgetBenchTask` 的基岩壳在 `(3212, -60, 3800)`，
     * 而项目 skill `alice-scene-based-testing` 的**强制**纪律是"场景边界外至少一圈（含上下）为空气，
     * 判定标准 = 站在场景内任意位置，四周/上方/下方一圈都不存在**非本场景**方块" ⇒ 隔开 41 格
     * （跨两列区块）才满足。
     */
    private static final BlockPos PARTIAL_ORIGIN = new BlockPos(3220, -60, 3846);
    /** bot 站的自建石平台半径（**必须自建**：搜索起点要有可走的地面，否则第一条边就出不去 ⇒ 前缀为空）。 */
    private static final int PARTIAL_PAD_RADIUS = 3;
    /** 基岩壳半径（中心是空气 + 目标矿；六面基岩 ⇒ **搜索永远到不了** ⇒ 必然耗尽预算）。 */
    private static final int PARTIAL_SHELL_RADIUS = 2;
    private static final BlockPos PARTIAL_SHELL_CENTER = PARTIAL_ORIGIN.offset(6, 0, 0);
    /** bot 站位 = 自建平台中心。 */
    private static final BlockPos PARTIAL_PERCH = PARTIAL_ORIGIN;
    /** 目标 = 壳正中心，**可挖**（煤）但被基岩六面封死 ⇒ 不是"硬拒绝"，只是到不了。 */
    private static final BlockPos PARTIAL_TARGET = PARTIAL_SHELL_CENTER;

    private enum Phase { SETUP, BURN, WAIT, REPLAN, PARTIAL_BUILD, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final List<String> findings = new ArrayList<>();
    /** 动过的格子（收尾清回空气）。 */
    private final Map<BlockPos, BlockState> touched = new LinkedHashMap<>();

    private Phase phase = Phase.SETUP;
    private int phaseTicks;
    private int totalTicks;
    private int checks;
    /** `PARTIAL` 臂等 `onGround` 的 tick 数。 */
    private int partialSettleTicks;
    /** BURN 相位咬到的理由（读数列出）。 */
    private String burnReason = "-";
    private String burnPlan = "-";
    private String replanReason = "-";
    private String replanPlan = "-";
    /** `P1-d` 臂读数：探针搜索状态 / 节点 / 毫秒 / 前缀长度 / 规划器最终理由。 */
    private String partialProbe = "-";
    private String partialReason = "-";
    private int partialSearchDelta = -1;
    private long partialMillisDelta = -1L;

    public MiningSearchLimitHonestyCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "MiningSearchLimitHonestyCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(TARGET);
    }

    @Override
    public String failureReason() {
        return failures.isEmpty() ? "" : String.join(" | ", failures);
    }

    @Override
    public String terminalReason() {
        return failures.isEmpty() ? "passed" : "failed";
    }

    private void check(String what, boolean ok, String detail) {
        checks++;
        if (ok) {
            findings.add(what + " ✓（" + detail + "）");
        } else {
            failures.add(what + " ✗（" + detail + "）");
            BotLog.warn("[SearchLimitHonesty] FAIL {} —— {}", what, detail);
        }
    }

    @Override
    public Task.Status tick() {
        ServerLevel level = (ServerLevel) bot.level();
        totalTicks++;
        phaseTicks++;
        if (totalTicks > BUDGET_TICKS) {
            failures.add("超时 " + BUDGET_TICKS + " tick 未完成（phase=" + phase + "）");
            return Task.Status.FAILED;
        }
        switch (phase) {
            case SETUP -> {
                if (phaseTicks == 1) {
                    buildScene(level);
                }
                if (phaseTicks >= 3) {
                    this.advance(Phase.BURN);
                }
                return Task.Status.RUNNING;
            }
            case BURN -> {
                if (phaseTicks >= 1) {
                    burnAndPlan(level);
                    this.advance(Phase.WAIT);
                }
                return Task.Status.RUNNING;
            }
            case WAIT -> {
                if (phaseTicks >= TICK_RESET_WAIT) {
                    this.advance(Phase.REPLAN);
                }
                return Task.Status.RUNNING;
            }
            case REPLAN -> {
                if (phaseTicks >= 1) {
                    replan(level);
                    this.advance(Phase.PARTIAL_BUILD);
                }
                return Task.Status.RUNNING;
            }
            case PARTIAL_BUILD -> {
                if (phaseTicks >= 1) {
                    // 建场 + 探针 + 规划**压进同一个 tick**：`D-337` 的读脚印闸门看的是"当前是否已加载"，
                    // 而刚 `setBlock` 过的区块在这一 tick 内**确定**是加载的（跨 tick 后被区块管理器卸载过一次，
                    // 实测就是那样把起点憋死的）⇒ 不留 SETTLE 相位。
                    buildPartialScene(level);
                    partialArm(level);
                    this.advance(Phase.DONE);
                }
                return Task.Status.RUNNING;
            }
            case DONE -> {
                cleanup(level);
                BotLog.info("[SearchLimitHonesty] SUMMARY checks={} failures={} burnReason={} burnPlan={}"
                                + " replanReason={} replanPlan={} p1dProbe=[{}] p1dReason={}"
                                + " p1dSearchDelta={} p1dMillisDelta={} ticks={}",
                        checks, failures.size(), burnReason, burnPlan, replanReason, replanPlan,
                        partialProbe, partialReason, partialSearchDelta, partialMillisDelta, totalTicks);
                for (String line : findings) {
                    BotLog.info("[SearchLimitHonesty]   {}", line);
                }
                for (String line : failures) {
                    BotLog.warn("[SearchLimitHonesty]   {}", line);
                }
                if (observer != null) {
                    observer.sendSystemMessage(Component.literal(
                            "[alice] 搜索限流诚实性夹具：" + (failures.isEmpty() ? "PASS" : "FAIL")
                                    + " checks=" + checks + " failures=" + failures.size()
                                    + "（burn=" + burnReason + " / replan=" + replanPlan + "）"));
                }
                return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
            }
            default -> {
                return Task.Status.FAILED;
            }
        }
    }

    private void advance(Phase next) {
        phase = next;
        phaseTicks = 0;
    }

    // ==================== 场景 ====================

    private void buildScene(ServerLevel level) {
        // 平台地板
        for (int dx = PAD_MIN_DX; dx <= PAD_MAX_DX; dx++) {
            for (int dz = -PAD_HALF_DZ; dz <= PAD_HALF_DZ; dz++) {
                place(level, ORIGIN.offset(dx, -1, dz), Blocks.STONE);
            }
        }
        // 石箱（目标埋正中心）
        for (int dx = -BOX_HALF; dx <= BOX_HALF; dx++) {
            for (int dz = -BOX_HALF; dz <= BOX_HALF; dz++) {
                for (int dy = 0; dy <= BOX_TOP_DY; dy++) {
                    place(level, ORIGIN.offset(dx, dy, dz), Blocks.STONE);
                }
            }
        }
        place(level, TARGET, Blocks.IRON_ORE);
        FixtureToolKit.resetInventory(bot);
        FixtureToolKit.ensurePickaxe(bot);
        BlockPos foot = ORIGIN.offset(BOT_DX, 0, 0);
        bot.teleportTo(level, foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D, -90.0F, 0.0F);
        BotLog.info("[SearchLimitHonesty] SETUP origin={} target={} foot={}（铁矿石埋 3×3×3 石箱正中心）",
                ORIGIN.toShortString(), TARGET.toShortString(), foot.toShortString());
    }

    private void place(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.Block block) {
        BlockPos key = pos.immutable();
        touched.putIfAbsent(key, level.getBlockState(key));
        level.setBlock(key, block.defaultBlockState(), 3);
    }

    // ==================== 两个相位 ====================

    /** BURN：先把本 tick 的搜索额度占满（前提），再规划 ⇒ 理由必须是**瞬时**的 `search_incomplete`。 */
    private void burnAndPlan(ServerLevel level) {
        // 前提①：场景真的建起来了（否则测的是空气）
        boolean targetIsOre = level.getBlockState(TARGET).is(Blocks.IRON_ORE);
        boolean enclosed = !level.getBlockState(TARGET.above()).isAir()
                && !level.getBlockState(TARGET.west()).isAir();
        check("前提：目标铁矿石确实埋在石箱里（上方/西面皆非空气）", targetIsOre && enclosed,
                "target=" + level.getBlockState(TARGET).getBlock()
                        + " above=" + level.getBlockState(TARGET.above()).getBlock()
                        + " west=" + level.getBlockState(TARGET.west()).getBlock());

        // 前提②：本 tick 的搜索额度**确已占满**（否则下面测的是"没被限流"，会静默假绿）
        SearchTickBudget.handleTick(level.getGameTime());
        SearchTickBudget.recordMillis(SearchTickBudget.EXPENSIVE_SEARCH_MILLIS);
        boolean acquired = SearchTickBudget.tryAcquire();
        check("前提：本 tick 搜索额度确已到线（`tryAcquire()` 必须为 false）", !acquired,
                "tryAcquire=" + acquired + " · " + SearchTickBudget.describe());

        MiningPlanner.Result result = new MiningPlanner().plan(bot, TARGET);
        burnReason = String.valueOf(result.failureReason());
        burnPlan = result.plan() == null ? "null" : "有计划";
        check("A1 拒绝 ⇒ 理由必须是**瞬时**的 `search_incomplete`（实测 " + burnReason + "）",
                "search_incomplete".equals(burnReason),
                "不许是永久理由：found_but_unminable / no_reachable_candidate / "
                        + "no_reachable_tunnel_standing_point（真机 377 次 found_but_unminable 就是这么来的）");
        check("A1 拒绝 ⇒ 本次确实没能给出计划（burnPlan=null）", result.plan() == null,
                "plan=" + burnPlan);
    }

    /** REPLAN：等 tick 边界后**同一目标**再规划 ⇒ 必须成功（这才是 `SEARCH_LIMIT ≠ UNREACHABLE`）。 */
    private void replan(ServerLevel level) {
        SearchTickBudget.handleTick(level.getGameTime());
        MiningPlanner.Result result = new MiningPlanner().plan(bot, TARGET);
        replanReason = String.valueOf(result.failureReason());
        replanPlan = result.plan() == null ? "null" : "有计划";
        check("预算恢复后**同一目标**必须能规划出来（实测 " + replanPlan + " / reason=" + replanReason + "）",
                result.plan() != null,
                "⇒ 证明 BURN 相位的拒绝是「本轮没评价完」，不是「这个目标不可挖」");
    }

    // ==================== ⭐ `P1-d` 臂（2026-09-25）：`PARTIAL` 形态 ====================

    /**
     * 建 `P1-d` 臂的场景：**基岩壳 + 壳内可挖目标**。
     *
     * <p>为什么必须是"不可破的壳"：`MiningPlanner` 的三条腿里，模式 A/B 在**没有任何可站候选**时
     * 会**提前返回**（不发起搜索），只有兜底腿 `planEnterTarget` 会拿 `miningApproach` 从 bot 脚位
     * 直冲目标 ⇒ 目标被基岩六面封死 ⇒ 搜索**必然耗尽预算**、且会朝目标方向至少走出一条边 ⇒
     * 交出**前缀** ⇒ `PlanningStatus.PARTIAL`（**不是** `SEARCH_LIMIT`）。
     */
    private void buildPartialScene(ServerLevel level) {
        for (int dx = -PARTIAL_PAD_RADIUS; dx <= PARTIAL_PAD_RADIUS; dx++) {
            for (int dz = -PARTIAL_PAD_RADIUS; dz <= PARTIAL_PAD_RADIUS; dz++) {
                place(level, PARTIAL_ORIGIN.offset(dx, -1, dz), Blocks.STONE);
            }
        }
        for (int dx = -PARTIAL_SHELL_RADIUS; dx <= PARTIAL_SHELL_RADIUS; dx++) {
            for (int dy = -PARTIAL_SHELL_RADIUS; dy <= PARTIAL_SHELL_RADIUS; dy++) {
                for (int dz = -PARTIAL_SHELL_RADIUS; dz <= PARTIAL_SHELL_RADIUS; dz++) {
                    BlockPos pos = PARTIAL_SHELL_CENTER.offset(dx, dy, dz);
                    boolean shell = Math.abs(dx) == PARTIAL_SHELL_RADIUS
                            || Math.abs(dy) == PARTIAL_SHELL_RADIUS
                            || Math.abs(dz) == PARTIAL_SHELL_RADIUS;
                    place(level, pos, shell ? Blocks.BEDROCK : Blocks.AIR);
                }
            }
        }
        place(level, PARTIAL_TARGET, Blocks.COAL_ORE);
        bot.teleportTo(level, PARTIAL_PERCH.getX() + 0.5D, PARTIAL_PERCH.getY(),
                PARTIAL_PERCH.getZ() + 0.5D, 0.0F, 0.0F);
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
        BotLog.info("[SearchLimitHonesty] P1-d SETUP pad={} shellCenter={} target={} perch={}"
                        + "（可挖煤块封在基岩壳正中心 ⇒ 只可能「到不了」，不可能「挖不动」）",
                PARTIAL_ORIGIN.toShortString(), PARTIAL_SHELL_CENTER.toShortString(),
                PARTIAL_TARGET.toShortString(), PARTIAL_PERCH.toShortString());
    }

    /**
     * `P1-d` 臂：把"预算耗尽但只拿到前缀"（`PARTIAL`）这一形态**确定地**造出来，并断言
     * `MiningPlanner` **不许**把判成不可挖。
     *
     * <p>三条判据（含两条**前提**：没有前提的行为断言在"测空气"时照样会绿）：
     * <ol>
     *   <li>前提①：同型搜索（`planEnterTarget` 用的就是 `miningApproach`）必须返回 `PARTIAL`
     *       —— 否则这个几何/深度没有洪泛，本臂测的是空气；</li>
     *   <li>前提②：`MiningPlanner` 这段真的**发起了搜索**（`SearchTickBudget` 搜索数增量 ≥ 1）
     *       —— 增量 0 = 搜索被 A1 拒了 ⇒ 那测的是 BURN 臂那条腿（`SEARCH_LIMIT`），本臂失效；</li>
     *   <li>⭐ 判据：理由必须是 `search_incomplete`。</li>
     * </ol>
     */
    private void partialArm(ServerLevel level) {
        BlockPos foot = MovementHelper.footCell(level, bot);

        // ============ ① 无条件判据（与环境无关 ⇒ 一定咬得住）：真值表 ============
        // 拿**真的 `PathPlan` 对象**喂进唯一出处的判定函数 —— 不是断言方法名/字面量
        // （`D-425` ⑤ 的教训：只咬名字的判据恒真也能过）。
        PathPlan partial = PathPlan.partial(foot, PARTIAL_TARGET, List.of(), List.of(),
                1.0D, 100, 10, 50L, "fixture", "partial-fixture");
        PathPlan limit = PathPlan.failure(PlanningStatus.SEARCH_LIMIT, foot, PARTIAL_TARGET,
                0, 0, 0L, "fixture", "limit-fixture");
        PathPlan unreachable = PathPlan.failure(PlanningStatus.UNREACHABLE, foot, PARTIAL_TARGET,
                500, 10, 3L, "fixture", "unreachable-fixture");
        PathPlan reached = PathPlan.failure(PlanningStatus.REACHED, foot, PARTIAL_TARGET,
                5, 2, 1L, "fixture", "reached-fixture");

        check("⭐ `P1-d`（与环境无关）：`PARTIAL` ⇒ 必须判成 `search_incomplete`（实测 "
                        + MiningPlanner.inconclusiveReason(partial) + "）",
                MiningPlanner.SEARCH_INCOMPLETE.equals(MiningPlanner.inconclusiveReason(partial)),
                "`PARTIAL` = 搜索**跑了**、烧光自己的预算、只交出前缀 ⇒ **没得出可达性结论**，"
                        + "必须与 `SEARCH_LIMIT` 同等对待（真机 09-24：撞限 502 次里 **480 次是 PARTIAL**）");
        check("`P1-d`：`SEARCH_LIMIT` ⇒ `search_incomplete`（`P1-b` 的既有语义，不得回退）",
                MiningPlanner.SEARCH_INCOMPLETE.equals(MiningPlanner.inconclusiveReason(limit)),
                "实测=" + MiningPlanner.inconclusiveReason(limit));
        check("`P1-d`：**真**不可达（`UNREACHABLE`）⇒ 空串（不许被本改动吞掉，如实报）",
                MiningPlanner.inconclusiveReason(unreachable).isEmpty(),
                "实测=" + MiningPlanner.inconclusiveReason(unreachable));
        check("`P1-d`：`REACHED` ⇒ 空串（有结论）",
                MiningPlanner.inconclusiveReason(reached).isEmpty(),
                "实测=" + MiningPlanner.inconclusiveReason(reached));

        // ============ ② 条件式行为判据（环境允许产出 PARTIAL 时才咬） ============
        // ⚠️ `PARTIAL` 能不能被造出来**取决于外部地形/加载状态**（`CoarseGoalPrefixCheckTask` 2026-09-22
        // 记过同一条夹具洁净度坑）⇒ 行为级断言**只能条件式**，不许无条件断言"必须有前缀"（那是假红）。
        // 它的"能红"由 ① 的真值表 + 门禁 `rule_mining_inconclusive_status` 承担（都在本臂之外）。
        SearchTickBudget.resetForFixture();
        SearchTickBudget.handleTick(level.getGameTime());
        // 读脚印（`D-337`，半径 3）四角是否已加载 —— 这一条就是"起点能不能扩出去"的前提
        int fp = 3;
        boolean footprintLoaded = level.hasChunkAt(foot.offset(-fp, 0, -fp))
                && level.hasChunkAt(foot.offset(fp, 0, -fp))
                && level.hasChunkAt(foot.offset(-fp, 0, fp))
                && level.hasChunkAt(foot.offset(fp, 0, fp));
        BotLog.info("[SearchLimitHonesty] P1-d 探针 foot={} onGround={} 读脚印已加载={} "
                        + "hasChunk[start={} shell={}] padBelow={} targetState={}",
                foot.toShortString(), bot.onGround(), footprintLoaded,
                level.hasChunkAt(PARTIAL_ORIGIN), level.hasChunkAt(PARTIAL_SHELL_CENTER),
                level.getBlockState(PARTIAL_ORIGIN.below()).getBlock(),
                level.getBlockState(PARTIAL_TARGET).getBlock());

        PathRequest probeReq = PathRequest.miningApproach(bot.getUUID().toString(), foot, PARTIAL_TARGET,
                "p1d-probe");
        PathPlan probe = new CorePathPlanner().plan(bot, level, probeReq);
        partialProbe = probe.status() + " nodes=" + probe.nodesExpanded() + " ms=" + probe.elapsedMillis()
                + " 前缀=" + probe.movements().size() + " 诊断=[" + probe.diagnostics() + "]";
        if (probe.status() == PlanningStatus.PARTIAL) {
            // ⚠️ 探针只是为了**立前提**，它烧掉的额度不该算进被测链路 ⇒ 清账再测
            // （实测第一版就是被探针占掉额度 ⇒ 规划器那次搜索直接被 A1 拒 ⇒ 判据走的是
            //  `SEARCH_LIMIT` 那条腿 = **假绿**；`p1dSearchDelta=0` 是发现它的读数）。
            SearchTickBudget.resetForFixture();
            SearchTickBudget.handleTick(level.getGameTime());
            // ⭐ 本臂要测的是"**搜索跑完且没得出结论**时的归因"，**不是**闸门 ⇒ 把三条轴临时全关
            //（= 不拒任何搜索）。测闸门是 BURN 臂的事。
            // ⚠️ 实测（2026-09-25）：不关的话，规划器里的**成本场**（`StandingCostEstimator` 的
            // `recordExternal`）一个人就能烧掉 **489 ms** ⇒ 把每 tick 400 ms 的账撑爆 ⇒ 之后所有搜索
            // 全被拒（`refused=9 searches=0`）⇒ 下面那条判据会走 `SEARCH_LIMIT` 那条腿 = **假绿**。
            SearchTickBudget.setLimits(0L, 0, 0);
            int searchesBefore = SearchTickBudget.tickSearches();
            long millisBefore = SearchTickBudget.tickMillis();
            MiningPlanner.Result result = new MiningPlanner().plan(bot, PARTIAL_TARGET);
            partialSearchDelta = SearchTickBudget.tickSearches() - searchesBefore;
            partialMillisDelta = SearchTickBudget.tickMillis() - millisBefore;
            partialReason = String.valueOf(result.failureReason());
            check("前提②（行为臂内）：`MiningPlanner` 那次搜索必须**真的跑起来**（搜索数 +"
                            + partialSearchDelta + " · 记入 " + partialMillisDelta + " ms）",
                    partialSearchDelta >= 1,
                    "= 0 ⇒ 搜索被 A1 拒 ⇒ 下面那条判据测的是 `SEARCH_LIMIT` 那条腿（= 假绿），不是 `PARTIAL`；"
                            + "账目=" + SearchTickBudget.describe());
            check("⭐ `P1-d`（行为级：本环境**确实产出了** `PARTIAL`）：规划器理由必须是 "
                            + "`search_incomplete`（实测 " + partialReason + "）",
                    MiningPlanner.SEARCH_INCOMPLETE.equals(partialReason),
                    "不许是 found_but_unminable / no_reachable_candidate / no_reachable_standing_point"
                            + " / enter_target_unreachable");
        } else {
            BotLog.warn("[SearchLimitHonesty] P1-d 行为臂**本环境不适用**（探针 status={} 不是 PARTIAL；"
                            + "诊断={}）⇒ 该形态是否出现取决于外部地形/加载状态，"
                            + "见 `CoarseGoalPrefixCheckTask` 的夹具洁净度教训 ⇒ 本臂不判红；"
                            + "确定性的那一半由真值表判据 + 门禁 `rule_mining_inconclusive_status` 承担",
                    probe.status(), probe.diagnostics());
        }
    }


    // ==================== 收尾 ====================

    private void cleanup(ServerLevel level) {
        SearchTickBudget.restoreDefaults();
        SearchTickBudget.resetForFixture();
        check("复位：搜索三条轴已还原成生产默认（limitMillis=" + SearchTickBudget.limitMillis()
                        + " == " + SearchTickBudget.DEFAULT_MAX_MILLIS_PER_TICK + "）",
                SearchTickBudget.limitMillis() == SearchTickBudget.DEFAULT_MAX_MILLIS_PER_TICK,
                "本臂用过 `resetForFixture()`/`handleTick` ⇒ 结束时必须还原，否则污染同轮后续步骤");
        for (Map.Entry<BlockPos, BlockState> entry : touched.entrySet()) {
            level.setBlock(entry.getKey(), entry.getValue(), 3);
        }
        touched.clear();
        FixtureToolKit.resetInventory(bot);
        BlockPos home = ORIGIN.offset(BOT_DX, 0, 0);
        bot.teleportTo(level, home.getX() + 0.5D, home.getY(), home.getZ() + 0.5D, 0.0F, 0.0F);
    }
}
