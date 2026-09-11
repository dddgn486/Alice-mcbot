package com.dddgn.alice.task;

import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.compat.ChainMining;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.task.mining.MiningBudget;
import com.dddgn.alice.task.mining.MiningPlan;
import com.dddgn.alice.task.mining.MiningPlanner;
import com.dddgn.alice.task.mining.MiningTuning;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 挖掘专项串联回归（{@code alice:mine_regression}，批次 5）：一次右键跑完挖掘链路的全部必要复测项。
 *
 * <p>与 {@link PathingRegressionTask} 的分工：寻路回归只覆盖 Movement；本任务覆盖
 * **规划两模式 → 走到站位 → 破坏 → 掉落物捕获 → 簇级收集 → 任务终态**，以及连锁模组兼容档。
 *
 * <p>用例（每个用例前重放地形 + 把 bot 复位到统一起点）：
 * <ul>
 *   <li>规划：`free` / `wall` / `headroom` → 模式 A（DIRECT/CURRENT）；`blocked` → 模式 B（TUNNEL）；
 *       `buried` → 模式 B 或 `found_but_unminable`（预算不足也必须**如实**报，不许静默挖隧道）；</li>
 *   <li>执行：`exec_direct`（露天目标挖+收）、`exec_blocked`（被包围目标走模式 B 挖+收）；</li>
 *   <li>兼容：`exec_chain`（模组在场 → 3x3 铁矿脉连锁挖 + 收 9 件；模组缺失 → `SKIP`，不算 FAIL）。</li>
 * </ul>
 *
 * <p>执行用例的通过条件（四项同时成立）：任务 `DONE`、目标方块已空、`MineTask.collectedItems()`
 * 等于期望件数、作用域内**无剩余存活掉落物**。
 */
public final class MineRegressionTask implements Task {

    private enum Kind {
        /** 只规划：模式断言。 */
        PLAN,
        /** 规划 + 执行 + 收集断言。 */
        EXECUTE,
        /** 模组连锁：执行 + 收集断言；模组缺失时 SKIP。 */
        CHAIN
    }

    /**
     * @param exactCollected true = 件数必须**精确相等**（露天单目标、连锁矿脉）；
     *                       false = 至少这么多（模式 B 沿途会破坏通道方块，其掉落物在走位时被自然拾取，
     *                       不计入收集阶段的 `collected`，只体现在背包增量上）；
     * @param expectSupport  true = 悬空目标：规划必须给出 `supportPlacementPos == target.below()`，
     *                       执行必须先在该处放下支撑块；
     * @param expectedDelta  背包净增量。**D-112 建拆同权后算式变了**：悬空目标
     *                       = 放支撑 −1 ＋ 目标掉落 +1 ＋ **用完即拆后回收支撑 +1** = **净 +1**
     *                       （旧语义是"支撑留在世界里"⇒ 净 0；那条期望随 D-112 一起作废）。
     */
    private record CaseDef(String name, String terrain, BlockPos start, BlockPos target,
                           Kind kind, List<MiningPlan.Mode> expectedModes,
                           int expectedCollected, Item expectedItem, boolean exactCollected,
                           boolean expectSupport, int expectedDelta) {
    }

    private static final BlockPos MINE_START = MineCourseDiagnosticTask.START_FOOT;
    private static final BlockPos CHAIN_START = new BlockPos(23, 64, 170);
    private static final BlockPos CHAIN_TARGET = new BlockPos(23, 64, 172);
    private static final BlockPos FLOAT_START = new BlockPos(21, 64, 190);
    private static final BlockPos FLOAT_TARGET = new BlockPos(23, 65, 190);

    private static CaseDef plan(String name, String terrain, BlockPos start, BlockPos target,
                                MiningPlan.Mode... modes) {
        return new CaseDef(name, terrain, start, target, Kind.PLAN, List.of(modes), 0, null,
                true, false, 0);
    }

    private static CaseDef execute(String name, String terrain, BlockPos start, BlockPos target,
                                   int expectedCollected, Item item, boolean exactCollected) {
        return new CaseDef(name, terrain, start, target, Kind.EXECUTE, List.of(),
                expectedCollected, item, exactCollected, false, expectedCollected);
    }

    private static final List<CaseDef> CASES = List.of(
            plan("free", "mine_course", MINE_START, new BlockPos(23, 64, 140),
                    MiningPlan.Mode.DIRECT, MiningPlan.Mode.CURRENT),
            plan("wall", "mine_course", MINE_START, new BlockPos(23, 64, 137),
                    MiningPlan.Mode.DIRECT, MiningPlan.Mode.CURRENT),
            plan("blocked", "mine_course", MINE_START, new BlockPos(23, 64, 134),
                    MiningPlan.Mode.TUNNEL),
            plan("headroom", "mine_course", MINE_START, new BlockPos(23, 65, 131),
                    MiningPlan.Mode.DIRECT, MiningPlan.Mode.CURRENT),
            plan("buried", "mine_course", MINE_START, new BlockPos(23, 64, 128)),
            execute("exec_direct", "mine_course", MINE_START, new BlockPos(23, 64, 140),
                    1, Items.COBBLESTONE, true),
            // 模式 B 沿途破坏通道方块 → 其掉落物可能在走位时被自然拾取，故只要求"至少 1 件"
            execute("exec_blocked", "mine_course", MINE_START, new BlockPos(23, 64, 134),
                    1, Items.COBBLESTONE, false),
            // 悬空目标：正下方无支撑 → 规划必须给出支撑放置点；执行必须先放支撑块
            // 起点就能触及悬空目标 → 合法模式是 CURRENT（附带支撑放置）；也允许 DIRECT
            new CaseDef("floating_plan", "floating_course", FLOAT_START, FLOAT_TARGET,
                    Kind.PLAN, List.of(MiningPlan.Mode.CURRENT, MiningPlan.Mode.DIRECT),
                    0, null, true, true, 0),
            // D-112：放支撑 −1 ＋ 目标掉落 +1 ＋ 拆回支撑 +1 ⇒ 净增量 +1（支撑"用完即拆"是硬要求）
            new CaseDef("exec_floating", "floating_course", FLOAT_START, FLOAT_TARGET,
                    Kind.EXECUTE, List.of(), 1, Items.COBBLESTONE, true, true, 1),
            new CaseDef("exec_chain", "chain_mine_course", CHAIN_START, CHAIN_TARGET,
                    Kind.CHAIN, List.of(), 9, Items.RAW_IRON, true, false, 9));

    /** 单用例预算与任务总预算（tick）。 */
    private static final int CASE_BUDGET_TICKS = 320;
    private static final int MAX_TASK_TICKS = 2600;

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final ScopeBuffer scope;
    private final MiningPlanner planner = new MiningPlanner();
    private final Map<String, String> results = new LinkedHashMap<>();
    private final Map<String, String> details = new LinkedHashMap<>();

    private int index;
    private int ticks;
    private int caseTicks;
    private boolean prepared;
    private MineTask mineTask;
    private Item expectedItem;
    private int inventoryBefore;
    private String chainModeBefore;
    private String failure = "";

    public MineRegressionTask(BotPlayer bot, ServerPlayer observer, ScopeBuffer scope) {
        this.bot = bot;
        this.observer = observer;
        this.scope = scope;
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(CASES.get(0).target());
    }

    @Override
    public String failureReason() {
        return failure;
    }

    @Override
    public Status tick() {
        if (++ticks > MAX_TASK_TICKS) {
            failure = "MINE_REGRESSION_TIMEOUT";
            BotLog.warn("[MineRegression] task_timeout ticks={} case={}", ticks, currentCase());
            return finish();
        }
        if (index >= CASES.size()) {
            return finish();
        }
        CaseDef current = CASES.get(index);
        if (!prepared) {
            prepare(current);
            prepared = true;
            caseTicks = 0;
            if (current.kind() == Kind.PLAN) {
                runPlanCase(current);
                advance();
                return index >= CASES.size() ? finish() : Status.RUNNING;
            }
            if (current.kind() == Kind.CHAIN) {
                if (!ChainMining.available()) {
                    results.put(current.name(), "SKIP");
                    details.put(current.name(), "chain_mod=absent");
                    BotLog.info("[MineRegression] {} = SKIP（模组不在场）", current.name());
                    advance();
                    return index >= CASES.size() ? finish() : Status.RUNNING;
                }
                chainModeBefore = MiningTuning.chainMode().name();
                MiningTuning.setChainMode("auto");
                BotLog.info("[MineRegression] {} 临时启用 chain=AUTO（原 {}）",
                        current.name(), chainModeBefore);
            }
            scope.begin(current.target(), 16, bot.getUUID());
            expectedItem = current.expectedItem();
            MiningBudget budget = MiningBudget.forTarget(bot, bot.serverLevel(), current.target(), true);
            // D-112：本自检就是"会话所有者" → 断言"用完即拆"
            mineTask = new MineTask(bot, current.target(), scope, budget,
                    com.dddgn.alice.task.mining.MiningProfile.TUNNEL_ALLOWED.withRestore(),
                    WriteGrant.of(taskName(), WriteReason.EXPECTED_TARGET));
            // **顺序（2026-09-11 修正）**：构造 MineTask（夹具补镐，会覆盖选中槽 ✗）→ 补一次性方块
            // → **再采基线**。原实现把补料放在基线之后，于是"补了多少圆石"直接进了 inventoryDelta
            // （bot 手头圆石 <8 时就会漂移）⇒ 精确计数根本不是一个不变量。
            ensureCobblestone();
            // 基线在此采集：之后的净增量只反映"放置消耗 + 回收 + 掉落物"这些真实事件
            inventoryBefore = countInInventory(expectedItem);
            return Status.RUNNING;
        }

        if (++caseTicks > CASE_BUDGET_TICKS) {
            record(current, false, "case_timeout ticks=" + caseTicks);
            finishCase();
            return index >= CASES.size() ? finish() : Status.RUNNING;
        }
        Status status = mineTask.tick();
        if (status == Status.RUNNING) {
            return Status.RUNNING;
        }
        int collected = mineTask.collectedItems();
        boolean targetGone = bot.serverLevel().getBlockState(current.target()).isAir();
        // D-112 建拆同权：悬空目标的支撑块**用完即拆**（旧断言"支撑仍在"是改造前的语义）
        boolean supportOk = !current.expectSupport()
                || bot.serverLevel().getBlockState(current.target().below()).isAir();
        // 支撑类用例的**材料闭环**用账本事实判（比背包净增量稳）：
        //   restoredBlocks ≥ 1（确实拆回了自己放的方块）&& scaffoldLeft == 0（没留残）
        boolean restoredOk = !current.expectSupport()
                || (mineTask.restoredBlocks() >= 1 && mineTask.scaffoldLeft() == 0);
        // 掉落物判据改用**世界事实**：拆除阶段会 scope.end()，缓冲视图会变成空集（假通过）
        int dropsLeft = bot.serverLevel().getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(current.target()).inflate(6)).size();
        boolean noDropsLeft = dropsLeft == 0;
        int delta = countInInventory(expectedItem) - inventoryBefore;
        // 精确净增量只在**不涉及支撑块**的用例上成立（那时净增量=掉落物本身，稳定）；
        // 支撑类用例的净增量混了"放置消耗 + 拆回 + 可能的夹具补料"，**不是不变量**
        // （2026-09-11 三轮实测同一用例出现过 2 / 0 / 2）→ 改判账本事实 restoredOk + 下界。
        boolean countOk = current.expectSupport()
                ? collected >= current.expectedCollected() && delta >= current.expectedCollected()
                : (current.exactCollected()
                        ? collected == current.expectedCollected() && delta == current.expectedDelta()
                        : collected >= current.expectedCollected()
                                && delta >= current.expectedCollected());
        boolean pass = status == Status.DONE && targetGone && noDropsLeft && countOk && supportOk
                && restoredOk;
        record(current, pass, "status=" + status
                + "/targetGone=" + targetGone
                + "/collected=" + collected + "/" + current.expectedCollected()
                + (current.exactCollected() ? "" : "+")
                + "/inventoryDelta=" + delta
                + (current.expectedDelta() != current.expectedCollected()
                        ? "(期望" + current.expectedDelta() + ")" : "")
                + "/dropsLeft=" + dropsLeft
                + (current.expectSupport() ? "/supportRestored=" + supportOk : "")
                + (current.expectSupport() ? "/ledgerRestored=" + mineTask.restoredBlocks()
                        + "/scaffoldLeft=" + mineTask.scaffoldLeft() : "")
                + "/ticks=" + caseTicks
                + (status == Status.DONE ? "" : "/reason=" + mineTask.failureReason()));
        finishCase();
        return index >= CASES.size() ? finish() : Status.RUNNING;
    }

    // ---- 用例执行 ----

    /** 重放地形 + 复位 bot 到统一起点（每个用例独立）。 */
    private void prepare(CaseDef current) {
        var server = bot.serverLevel().getServer();
        var source = server.createCommandSourceStack().withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source,
                "function alice_test:" + current.terrain() + "_terrain");
        bot.teleportTo(bot.serverLevel(), current.start().getX() + 0.5D, current.start().getY(),
                current.start().getZ() + 0.5D, Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        BotLog.info("[MineRegression] case={} kind={} terrain={} start={} target={}",
                current.name(), current.kind(), current.terrain(),
                current.start().toShortString(), current.target().toShortString());
    }

    /** 规划断言（与 `mine_course` 同口径）。 */
    private void runPlanCase(CaseDef current) {
        MiningBudget budget = MiningBudget.forTarget(bot, bot.serverLevel(), current.target(), true);
        MiningPlanner.Result result = planner.plan(bot, current.target(), budget);
        MiningPlan plan = result.plan();
        boolean pass;
        if ("buried".equals(current.name())) {
            pass = (plan != null && plan.mode() == MiningPlan.Mode.TUNNEL)
                    || "found_but_unminable".equals(result.failureReason());
        } else if ("headroom".equals(current.name())) {
            pass = plan != null && plan.standingFoot().getY() == current.target().getY() - 1;
            if (plan != null && !current.expectedModes().isEmpty()) {
                pass = pass && current.expectedModes().contains(plan.mode());
            }
        } else {
            pass = plan != null && current.expectedModes().contains(plan.mode());
        }
        if (current.expectSupport()) {
            pass = pass && plan != null && plan.supportPlacementPos() != null
                    && plan.supportPlacementPos().equals(current.target().below());
        }
        record(current, pass, "mode=" + (plan == null ? "-" : plan.mode())
                + "/stand=" + (plan == null ? "-" : plan.standingFoot().toShortString())
                + "/cost=" + (result.score() == null ? "-"
                        : String.format(java.util.Locale.ROOT, "%.2f", result.score().getScore()))
                + (current.expectSupport() ? "/support=" + (plan == null || plan.supportPlacementPos() == null
                        ? "-" : plan.supportPlacementPos().toShortString()) : "")
                + "/reason=" + result.failureReason());
    }

    /** 结束一个执行用例：恢复连锁档位、清理子任务。 */
    private void finishCase() {
        if (chainModeBefore != null) {
            MiningTuning.setChainMode(chainModeBefore);
            BotLog.info("[MineRegression] 恢复 chain={}", chainModeBefore);
            chainModeBefore = null;
        }
        mineTask = null;
        advance();
    }

    private void record(CaseDef current, boolean pass, String detail) {
        results.put(current.name(), pass ? "PASS" : "FAIL");
        details.put(current.name(), detail);
        BotLog.info("[MineRegression] {}={} {}", current.name(), pass ? "PASS" : "FAIL", detail);
    }

    private void advance() {
        index++;
        prepared = false;
    }

    private String currentCase() {
        return index < CASES.size() ? CASES.get(index).name() : "-";
    }

    /** 快捷栏补 8 个圆石（支撑放置需要一次性方块；只填空格，不动镐）。 */
    /** 保证快捷栏里有一次性方块（挖矿"悬空目标放支撑"要用）；实现见 {@link com.dddgn.alice.item.FixtureToolKit}。 */
    private void ensureCobblestone() {
        com.dddgn.alice.item.FixtureToolKit.ensureHotbarStack(bot,
                () -> new ItemStack(Items.COBBLESTONE),
                stack -> stack.is(Items.COBBLESTONE),
                8, "cobblestone");
    }

    private int countInInventory(Item item) {
        if (item == null) {
            return 0;
        }
        int total = 0;
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private Status finish() {
        boolean allPass = results.values().stream().noneMatch("FAIL"::equals);
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<String, String> entry : results.entrySet()) {
            if (summary.length() > 0) {
                summary.append(' ');
            }
            summary.append(entry.getKey()).append('=').append(entry.getValue());
        }
        BotLog.info("[MineRegression] SUMMARY {} ticks={}", summary, ticks);
        if (observer != null) {
            observer.sendSystemMessage(Component.literal("[alice] 挖掘回归 " + summary
                            + (allPass ? "" : " → FAIL"))
                    .withStyle(allPass ? net.minecraft.ChatFormatting.GREEN
                            : net.minecraft.ChatFormatting.RED));
        }
        if (!allPass) {
            failure = "MINE_REGRESSION_FAILED " + summary;
            return Status.FAILED;
        }
        return Status.DONE;
    }
}
