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
     * @param expectedDelta  背包净增量：放支撑块会**消耗** 1 个一次性方块，而目标掉落物又是同类时
     *                       净增量应为 0（正好证明"放了 1 个 + 收了 1 个"两件事都发生）。
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
            // 放支撑块消耗 1 圆石 + 目标掉落 1 圆石 → 净增量 0
            new CaseDef("exec_floating", "floating_course", FLOAT_START, FLOAT_TARGET,
                    Kind.EXECUTE, List.of(), 1, Items.COBBLESTONE, true, true, 0),
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
            inventoryBefore = countInInventory(expectedItem);
            MiningBudget budget = MiningBudget.forTarget(bot, bot.serverLevel(), current.target(), true);
            mineTask = new MineTask(bot, current.target(), scope, budget,
                    WriteGrant.of(taskName(), WriteReason.EXPECTED_TARGET));
            // MineTask 构造会占用选中槽放镐 → 之后再补一次性方块，避免被覆盖
            ensureCobblestone();
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
        boolean supportOk = !current.expectSupport()
                || com.dddgn.alice.action.BlockInteraction.isSolidForPlacement(
                        bot.serverLevel(), current.target().below());
        boolean noDropsLeft = scope.liveDrops().isEmpty();
        int delta = countInInventory(expectedItem) - inventoryBefore;
        boolean countOk = current.exactCollected()
                ? collected == current.expectedCollected() && delta == current.expectedDelta()
                : collected >= current.expectedCollected() && delta >= current.expectedCollected();
        boolean pass = status == Status.DONE && targetGone && noDropsLeft && countOk && supportOk;
        record(current, pass, "status=" + status
                + "/targetGone=" + targetGone
                + "/collected=" + collected + "/" + current.expectedCollected()
                + (current.exactCollected() ? "" : "+")
                + "/inventoryDelta=" + delta
                + (current.expectedDelta() != current.expectedCollected()
                        ? "(期望" + current.expectedDelta() + ")" : "")
                + "/dropsLeft=" + scope.liveDrops().size()
                + (current.expectSupport() ? "/supportPlaced=" + supportOk : "")
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
    private void ensureCobblestone() {
        var inventory = bot.getInventory();
        int have = 0;
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.getItem(slot).is(Items.COBBLESTONE)) {
                have += inventory.getItem(slot).getCount();
            }
        }
        if (have >= 8) {
            return;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, 8 - have));
                return;
            }
        }
        inventory.add(new ItemStack(Items.COBBLESTONE, 8 - have));
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
