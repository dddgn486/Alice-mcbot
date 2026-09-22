package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.log.BotLog;
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
 * <h2>两个相位（本夹具的核心设计）</h2>
 * <ol>
 *   <li><b>BURN</b>：同一 tick 内先用公开 API 把本 tick 的搜索额度**占满**
 *       （`recordMillis(EXPENSIVE_SEARCH_MILLIS)` ⇒ 主判据「烧预算的搜索 ≥ 1」到线），
 *       再规划 ⇒ 断言理由是 `search_incomplete`（**瞬时**），**不是** `found_but_unminable` /
 *       `no_reachable_candidate` / `no_reachable_tunnel_standing_point`（**永久**）。</li>
 *   <li><b>REPLAN</b>：等 tick 边界（`handleTick` 自动清零）后**规划同一个目标、同一个世界**
 *       ⇒ 断言**规划成功**。这一步才是 `SEARCH_LIMIT ≠ UNREACHABLE` 的**行为级**证明：
 *       同一个目标、同一个几何，只差"本 tick 还有没有搜索额度"。</li>
 * </ol>
 *
 * <h2>反向对照（红臂）</h2>
 * 把 `MiningPlanner.planTunnel` 结尾的 `search_incomplete` 逐字保留改回无条件
 * `no_reachable_tunnel_standing_point`（或去掉 `planEnterTarget` 的 `PlanningStatus.SEARCH_LIMIT` 分支）
 * ⇒ BURN 相位必须**红**（理由是 `found_but_unminable`）。
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

    private enum Phase { SETUP, BURN, WAIT, REPLAN, DONE }

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
    /** BURN 相位咬到的理由（读数列出）。 */
    private String burnReason = "-";
    private String burnPlan = "-";
    private String replanReason = "-";
    private String replanPlan = "-";

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
                    this.advance(Phase.DONE);
                }
                return Task.Status.RUNNING;
            }
            case DONE -> {
                cleanup(level);
                BotLog.info("[SearchLimitHonesty] SUMMARY checks={} failures={} burnReason={} burnPlan={}"
                                + " replanReason={} replanPlan={} ticks={}",
                        checks, failures.size(), burnReason, burnPlan, replanReason, replanPlan, totalTicks);
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

    // ==================== 收尾 ====================

    private void cleanup(ServerLevel level) {
        for (Map.Entry<BlockPos, BlockState> entry : touched.entrySet()) {
            level.setBlock(entry.getKey(), entry.getValue(), 3);
        }
        touched.clear();
        FixtureToolKit.resetInventory(bot);
        BlockPos home = ORIGIN.offset(BOT_DX, 0, 0);
        bot.teleportTo(level, home.getX() + 0.5D, home.getY(), home.getZ() + 0.5D, 0.0F, 0.0F);
    }
}
