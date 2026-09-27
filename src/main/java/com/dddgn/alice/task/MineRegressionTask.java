package com.dddgn.alice.task;

import com.dddgn.alice.write.WriteReason;
import com.dddgn.alice.write.WriteGrant;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.compat.ChainMining;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.task.mining.MiningBudget;
import com.dddgn.alice.reach.MiningPlan;
import com.dddgn.alice.task.mining.MiningPlanner;
import com.dddgn.alice.reach.MiningTuning;
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
        CHAIN,
        /**
         * 工具负例（D-119）：**故意不给工具**去挖"必须正确工具才掉落"的方块，
         * 断言任务如实失败 {@code no_suitable_tool}、目标未被破坏、且**没有变出工具**。
         */
        TOOL_REFUSAL,
        /**
         * **作用域重开继承掉落物**（D-124 / T4）：挖出掉落物（本用例 `collectDrops=false`，
         * 先不收集）→ **重开作用域**（同样的中心/半径）→ 断言掉落物**仍在账上**
         * （`liveDrops() ≥ 1`）。修前 `begin()` 会清空登记 ⇒ 这里必然是 0。
         */
        SCOPE_REOPEN,

        /**
         * **G3/R1-残（2026-09-16）**：把破坏预算压低后跑同一条连锁矿脉 —— 断言"预算把连锁截断"
         * 这件事**如实上报**（`chainRefusedByBudget` 原先只写不读，下游分不清"砍短"与"挖完"）。
         */
        CHAIN_STARVED
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
     *                       ⚠️ `D-467`（2026-09-27）修正：那个"回收 +1"**只在材料捡得回来时成立** ——
     *                       真悬空场景（下方 ≥8 格空气）里拆回的方块**掉进竖井**（实测 `recovered=0`）
     *                       ⇒ 净 = **0**。而且**支撑类用例现在根本不看这个字段**（`expectSupport`
     *                       走另一条分支，见 `countOk` 处的注释），它只作为"当时的算法记录"留着。
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
    /** ⭐ `D-464` 的 O1/O2 修复：**真悬空**场景（目标下方 ≥8 格空气 ⇒ 必须放支撑块 + 用完即拆）。 */
    private static final BlockPos SUPPORT_START = new BlockPos(21, 64, 212);
    private static final BlockPos SUPPORT_TARGET = new BlockPos(23, 65, 212);
    /**
     * ⭐ `D-467`：`support_course` 场景的外框（与
     * {@code tools/test-scenes/alice_test/data/alice_test/functions/support_course_terrain.mcfunction}
     * 里那个孤立盒子逐字一致：`x 17..31, y 44..76, z 205..223`）。
     *
     * <p>认领按**区块**生效（`SafeZoneData` 是区块级、**不分高度**，`D-313`）⇒ 这两个角只决定
     * 认领哪几个区块（实测 = `cx=1`、`cz=12..13`），**不会**碰到别的用例（它们的 z 都在 190 以下）。
     */
    private static final BlockPos SUPPORT_MIN = new BlockPos(17, 44, 205);
    private static final BlockPos SUPPORT_MAX = new BlockPos(31, 76, 223);

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
            // ⭐ `D-364`（2026-09-20 真机实测）**口径收紧**：本场景的竖井只有 1 格深
            // （`(23,63,190)` 空气、下面 `y=62` 是实心）⇒ 掉落物**落在坑底、捡得回来**
            // ⇒ 按新判据**不该垫方块**（旧判据「下方那格不是实心就垫」会垫 —— 那正是真机里
            // 9 次 `SUPPORT_PLACE_FAILED` 与「垫了挡住相邻矿视线」的来源）。
            // 所以这两条用例现在断言的是：**不垫** + 照常挖到 + 掉落物收到。
            // 模式仍合法为 CURRENT（起点就能触及）或 DIRECT。
            new CaseDef("floating_plan", "floating_course", FLOAT_START, FLOAT_TARGET,
                    Kind.PLAN, List.of(MiningPlan.Mode.CURRENT, MiningPlan.Mode.DIRECT),
                    0, null, true, false, 0),
            // 不垫方块 ⇒ 净增量 = 掉落物本身 = +1（与旧语义「放支撑 −1 ＋ 掉落 +1 ＋ 拆回 +1」同值
            // ⇒ 这条期望在两种语义下都成立，不用改）
            new CaseDef("exec_floating", "floating_course", FLOAT_START, FLOAT_TARGET,
                    Kind.EXECUTE, List.of(), 1, Items.COBBLESTONE, true, false, 1),
            // ⭐ `D-464` 的 O1/O2 修复（2026-09-27）：**真悬空**（下方 ≥8 格空气）⇒ 断言
            // 规划器必须给出 `supportPlacementPos == target.below()`（`D-078`）。
            // ⛔ 执行侧（`exec_support`）第一次试过并撤回：不认领 ⇒ `D-398` 判"区外"不记账（无回收义务）；
            //    认领 ⇒ `ZoneAuthority` 判 `protected_area` **拒写**（保护区内的写入需要"生效的任务区"覆盖该格）
            // ⭐ `D-467`（2026-09-27）：**两件一起对**之后 EXECUTE 侧落地 —— 用现成的夹具助手
            //    `FixtureZone.protect(...)`（认领区块 + 声明 L2 任务区封套 + **幂等 release**，
            //    仓里已有 6 个夹具在用）⇒ 见下面的 `exec_support`。
            // `support_plan` 只到 PLAN 侧：断言规划器必须给出 `supportPlacementPos == target.below()`（`D-078`）。
            // 与 `exec_floating` 的区别：那条的竖井 **1 格深** ⇒ 掉落物捡得回 ⇒ 断言"**不垫**"（`D-364` 口径）。
            new CaseDef("support_plan", "support_course", SUPPORT_START, SUPPORT_TARGET,
                    Kind.PLAN, List.of(MiningPlan.Mode.CURRENT, MiningPlan.Mode.DIRECT),
                    0, null, true, true, 0),
            // ⭐ `D-467`（2026-09-27）：**EXECUTE 侧的支撑块正例** —— 它同时修掉三个观测项：
            //    ① `O1` 空判据：这是**第一条** `expectSupport=true` 的 EXECUTE 用例 ⇒
            //       `supportOk`/`restoredOk` 第一次真的会咬、`/ledgerRestored=` `/scaffoldLeft=` 第一次打印；
            //    ② `O2` RESTORE 零覆盖：区内真放置 ⇒ 账本有 TEMP ⇒ `enterRestoreOrDone` 走 `pending>0`
            //       ⇒ `to=RESTORE` 第一次非 0（此前 11 次全是 `restore_skip pending=0`）；
            //    ③ `O4` 弱判据：`supportOk` 同刀加强（见本文件 `supportOk` 处的注释）。
            //    期望值：目标是一格石头 ⇒ 掉落圆石 1 件。⚠️ `expectedDelta` 填 **0** 而不是 javadoc 那个
            //    "+1"：实测净增量 = 放支撑 −1 ＋ 目标掉落 +1 ＋ **拆回材料掉进竖井捡不回** +0 = **0**
            //    （`[Restore] … recovered=0／仍有 1 个掉落物没收回`）。支撑类的 `countOk` 今天不看 delta。
            new CaseDef("exec_support", "support_course", SUPPORT_START, SUPPORT_TARGET,
                    Kind.EXECUTE, List.of(), 1, Items.COBBLESTONE, true, true, 0),
            new CaseDef("exec_chain", "chain_mine_course", CHAIN_START, CHAIN_TARGET,
                    Kind.CHAIN, List.of(), 9, Items.RAW_IRON, true, false, 9),
            // G3：同一场景、**预算压到 1 次破坏** ⇒ 连锁必须当场停 + 如实报 `chain_budget_refused`
            new CaseDef("exec_chain_budget_refused", "chain_mine_course", CHAIN_START, CHAIN_TARGET,
                    Kind.CHAIN_STARVED, List.of(), 0, Items.RAW_IRON, false, false, 0),
            // D-119 负例：同一格圆石，但**清空背包**后开工 —— 必须如实失败、不破坏方块、不变出工具
            new CaseDef("no_tool_refuses", "mine_course", MINE_START, new BlockPos(23, 64, 140),
                    Kind.TOOL_REFUSAL, List.of(), 0, Items.COBBLESTONE, true, false, 0),
            // D-124：挖出掉落物 → 重开作用域 → 掉落物必须**仍在账上**（修前 begin() 会清空登记）
            new CaseDef("scope_reopen_keeps_drops", "mine_course", MINE_START,
                    new BlockPos(23, 64, 140), Kind.SCOPE_REOPEN, List.of(), 0,
                    Items.COBBLESTONE, true, false, 0));

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
    /**
     * ⭐ `D-467`（2026-09-27）：**密封的"保护区 + 任务区"前提**（`FixtureZone`），只在
     * {@code expectSupport=true} 的 **EXECUTE** 用例上摆，并在 {@link #finishCase()} 里**幂等还原**。
     *
     * <p>为什么必须摆：`D-398` 把写入责任收窄到保护区 —— **区外不记账 ⇒ 无回收义务**
     * （`MineTask` 走 `restore_skip pending=0` 早退，`RESTORE` 相位一次都到不了；`D-465` 尝试① 实测）。
     * 而**只认领不声明任务区**会被 `ZoneAuthority` 判 `protected_area` 拒写
     * （尝试② 实测 `support_skipped result=ZONE_DENIED`）⇒ **两件一起对**才谈得上"真放支撑 + 用完即拆"。
     */
    private FixtureZone.Handle zone;
    private Item expectedItem;
    private int inventoryBefore;
    /**
     * 用例内"等待掉落物登记确认"的截止 tick（D-124 附注）。
     *
     * <p>为什么必须等：`ScopeBuffer` 在**服务器 tick 的 END** 阶段才 `flushPending()`，
     * 所以"破坏发生的当 tick 内"查 `liveDrops()` **一定是 0**（首版用例就这么假失败了一次：
     * `status=DONE/liveDropsBeforeReopen=0`）。留几 tick 余量再查，且要 **< 掉落物 pickupDelay**
     * （约 10 tick，否则会被 bot 顺手捡走）。
     */
    private int settleUntilTick;
    private String chainModeBefore;
    private String failure = "";
    /**
     * 用例开始时测量盒内的掉落物 UUID（2026-09-13）：判据只数**新增**的掉落物，
     * 世界历史残留单独报 `foreignDrops=` 不计入（修"不清掉落物就 FAIL"的夹具缺陷）。
     */
    private final Set<java.util.UUID> dropsAtCaseStart = new java.util.HashSet<>();
    /** ⑦ 本用例是否**故意**播下了外来掉落物（D-168 分支的活断言）。 */
    private boolean expectedForeignDrop;
    /** 内层任务上一次返回的终态（D-175 幂等断言用）。 */
    private Status lastInnerTerminal;

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
            settleUntilTick = 0;
            if (current.kind() == Kind.PLAN) {
                runPlanCase(current);
                advance();
                return index >= CASES.size() ? finish() : Status.RUNNING;
            }
            if (current.kind() == Kind.CHAIN || current.kind() == Kind.CHAIN_STARVED) {
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
                if (current.kind() == Kind.CHAIN_STARVED) {
                    // **确定性**触发：3×3 矿脉 ≫ 1 次破坏 ⇒ 第二次增量必被拒（不必造 65 格的场景）
                    com.dddgn.alice.write.WriteBudget.setCaps(
                            com.dddgn.alice.write.WriteBudget.scopeOf(bot),
                            new com.dddgn.alice.write.WriteBudget.Caps(1, 0, 0));
                    BotLog.info("[MineRegression] {} 破坏预算压到 1（夹具专用 setCaps）remaining={}",
                            current.name(), com.dddgn.alice.write.WriteBudget.remainingBreaks(bot));
                }
            }
            scope.begin(current.target(), 16, bot.getUUID());
            // ⭐ `D-467`：**密封前提必须在 `scope.begin` 之后** —— `TaskZoneRegistry.declare`
            // 的硬约束是"任务区必须挂在**打开的作用域**上"（不许在任务之外造授权封套），
            // 所以 `FixtureZone` 只有在作用域已开时才**借**用现成 scope（不会另开一个、也不会替我们关）。
            if (current.expectSupport() && current.kind() == Kind.EXECUTE) {
                zone = FixtureZone.protect(bot.serverLevel(), bot.getUUID(),
                        SUPPORT_MIN, SUPPORT_MAX, "region_lumber");
                if (!zone.ok()) {
                    // 前提没摆成 ⇒ **如实判红**，别默默继续（那会把"前提缺失"伪装成"支撑没垫"）
                    record(current, false, "zone_premise_failed " + zone.describe());
                    finishCase();
                    return index >= CASES.size() ? finish() : Status.RUNNING;
                }
                // 场景函数把这块地重铺过 ⇒ 账本里可能留着**上一轮的幽灵条目**；不销掉的话
                // 下面的 `restore_*` 会去拆一个已经不存在的格（`CraftStationCheckTask` 同款处置）。
                int stale = com.dddgn.alice.ledger.WorldModLedger.dropStale(bot.serverLevel());
                if (stale > 0) {
                    BotLog.info("[MineRegression] case={} 起手销掉 {} 条幽灵账目", current.name(), stale);
                }
            }
            expectedItem = current.expectedItem();
            // D-124：作用域重开用例**先不收集**（collectDrops=false）——掉落物留在世界里才谈得上"重开后还在不在账上"
            MiningBudget budget = MiningBudget.forTarget(bot, bot.serverLevel(), current.target(),
                    current.kind() != Kind.SCOPE_REOPEN);
            // D-119：**工具必须在构造 MineTask 之前到手** —— 生产 MineTask 不再兜底发工具，
            // 构造时就做只读工具判定（挖石头/圆石没有正确工具会如实失败 `no_suitable_tool`）。
            if (current.kind() == Kind.TOOL_REFUSAL) {
                // 负例：清空背包（走夹具唯一写入点，D-110）⇒ 没有镐 ⇒ 必须被如实拒绝
                com.dddgn.alice.item.FixtureToolKit.resetInventory(bot);
            } else {
                com.dddgn.alice.item.FixtureToolKit.ensurePickaxe(bot);
            }
            // D-112：本自检就是"会话所有者" → 断言"用完即拆"
            mineTask = new MineTask(bot, current.target(), scope, budget,
                    com.dddgn.alice.task.mining.MiningProfile.TUNNEL_ALLOWED.withRestore(),
                    WriteGrant.of(taskName(), WriteReason.EXPECTED_TARGET));
            // **顺序（2026-09-11 修正 / D-119 再修正）**：补镐 → 构造 MineTask → 补一次性方块
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
        // D-175：SCOPE_REOPEN 用例在"内层任务已终态"之后还要等 5 tick（等 ScopeBuffer flush），
        // 但**那段时间绝不能再 tick 内层任务** —— 旧实现在等待期间每 tick 都先 `mineTask.tick()`，
        // 而 `MineTask` 收尾时已把 `restoreTask = null`（`phase` 仍为 RESTORE）⇒ 第二次 tick 直接
        // NPE 把服务端 tick 循环打死（2026-09-13 客户端崩溃实证）。
        if (settleUntilTick > 0) {
            if (caseTicks < settleUntilTick) {
                return Status.RUNNING;
            }
            int inWorld = bot.serverLevel().getEntitiesOfClass(
                    net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(current.target()).inflate(4)).size();
            int before = scope.liveDrops().size();
            scope.begin(current.target(), 16, bot.getUUID());   // 与 prepare 同一中心/半径
            int after = scope.liveDrops().size();
            // D-175 契约断言：**终态任务再被 tick 必须幂等**（不许崩、不许改状态）
            Status again = tickTwiceAssertIdempotent();
            boolean pass = lastInnerTerminal == Status.DONE && before >= 1 && after >= 1
                    && again == lastInnerTerminal;
            record(current, pass, "status=" + lastInnerTerminal
                    + "/idempotent=" + (again == lastInnerTerminal)
                    + "/dropsInWorld=" + inWorld
                    + "/liveDropsBeforeReopen=" + before + "/liveDropsAfterReopen=" + after
                    + "/ticks=" + caseTicks);
            finishCase();
            return index >= CASES.size() ? finish() : Status.RUNNING;
        }
        Status status = mineTask.tick();
        if (status == Status.RUNNING) {
            return Status.RUNNING;
        }
        lastInnerTerminal = status;
        if (current.kind() == Kind.SCOPE_REOPEN) {
            // D-124 断言：挖出掉落物（未收集）→ 重开作用域 → 账上仍在。
            // **先等 pending 确认**（ScopeBuffer 在 tick END 才 flushPending；当 tick 查必为 0），
            // 等待窗口取 5 tick：≥1（跨过一次 tick END）且 < pickupDelay(~10)（免得被捡走）。
            if (settleUntilTick == 0) {
                settleUntilTick = caseTicks + 5;
            }
            return Status.RUNNING;   // 等待期间不 tick 内层（见上面的 D-175 注释）
        }
        if (current.kind() == Kind.CHAIN_STARVED) {
            // G3 断言四条：① 拒绝被记下（字段终于有读者）② 终态理由如实上报（D-134 通路）
            // ③ 连锁真的停了 ④ 预算确实被打满（证明这个"拒绝"来自预算，不是别的原因）
            boolean flagged = mineTask.chainRefusedByBudget();
            boolean reported = "chain_budget_refused".equals(mineTask.terminalReason());
            boolean stopped = !ChainMining.isRunning(bot);
            int remaining = com.dddgn.alice.write.WriteBudget.remainingBreaks(bot);
            record(current, flagged && reported && stopped && remaining == 0,
                    "refused=" + flagged + "/terminalReason=" + mineTask.terminalReason()
                            + "/chainStopped=" + stopped + "/remainingBreaks=" + remaining
                            + "/status=" + status);
            // 复原上限，**不许污染后续用例/后续电池步**
            com.dddgn.alice.write.WriteBudget.setCaps(
                    com.dddgn.alice.write.WriteBudget.scopeOf(bot),
                    com.dddgn.alice.write.WriteBudget.Caps.DEFAULT);
            finishCase();
            return index >= CASES.size() ? finish() : Status.RUNNING;
        }
        if (current.kind() == Kind.TOOL_REFUSAL) {
            // D-119 负例断言：如实失败 + 目标未动 + **没有变出工具**
            boolean refused = status == Status.FAILED
                    && "no_suitable_tool".equals(mineTask.failureReason());
            boolean targetKept = !bot.serverLevel().getBlockState(current.target()).isAir();
            boolean toolNotGiven = !com.dddgn.alice.action.BlockInteraction
                    .hasCorrectTool(bot, current.target());
            boolean noToolAnywhere = !bot.getInventory().contains(
                    new ItemStack(Items.DIAMOND_PICKAXE));
            boolean pass = refused && targetKept && toolNotGiven && noToolAnywhere;
            record(current, pass, "status=" + status
                    + "/reason=" + (mineTask.failureReason().isEmpty() ? "-" : mineTask.failureReason())
                    + "/targetKept=" + targetKept
                    + "/toolNotGiven=" + toolNotGiven
                    + "/noToolFabricated=" + noToolAnywhere
                    + "/ticks=" + caseTicks);
            // 复原：后续用例照常（发回工具）
            com.dddgn.alice.item.FixtureToolKit.ensurePickaxe(bot);
            finishCase();
            return index >= CASES.size() ? finish() : Status.RUNNING;
        }
        int collected = mineTask.collectedItems();
        boolean targetGone = bot.serverLevel().getBlockState(current.target()).isAir();
        // D-112 建拆同权：悬空目标的支撑块**用完即拆**（旧断言"支撑仍在"是改造前的语义）
        // ⭐ `D-467`（`O4` 修复）：原判据**只**查"目标下方那格是空气" ⇒ **空操作也能满足** ——
        //   `D-465` 尝试② 实测：支撑块**根本没放**（`writes[…] places=0`），而它报了 `true`。
        //   加强 = **世界事实 且 账本闭环**：
        //     ① 下面是空气（没留残）；② `restoredBlocks ≥ 1`（**只有真的放过、且真的拆回了**才会 ≥1
        //     —— "拆回一个没放过的东西"是不可能的，所以这一条同时证明"确实放过"）；
        //     ③ `scaffoldLeft == 0`（没留残架）。
        //   ⚠️ 这三条的合取**恰好等于**原来 `supportOk && restoredOk` 的合取（对 `expectSupport=false`
        //   的用例两边都短路为 true）⇒ 对既有 13 条用例**判决逐字不变**，只对新的 `exec_support` 生效。
        boolean supportOk = !current.expectSupport()
                || (bot.serverLevel().getBlockState(current.target().below()).isAir()
                        && mineTask.restoredBlocks() >= 1
                        && mineTask.scaffoldLeft() == 0);
        // 掉落物判据改用**世界事实**：拆除阶段会 scope.end()，缓冲视图会变成空集（假通过）。
        // 2026-09-13：只数**本用例新增**的掉落物（基线 UUID 见 `prepare`）——历史残留不算数。
        int dropsInBox = 0;
        int foreignDrops = 0;
        for (var item : bot.serverLevel().getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class, dropsBox(current.target()))) {
            dropsInBox++;
            if (dropsAtCaseStart.contains(item.getUUID())) {
                foreignDrops++;
            }
        }
        int dropsLeft = dropsInBox - foreignDrops;
        // ⭐ `D-467`：**"零残留掉落物"对支撑类用例不是正确判据** —— 拆回支撑块必然产生一件材料掉落，
        //   而它发生在**收集阶段结束之后**；真悬空场景里这件材料还会掉进竖井、捡不回来
        //   （实测 `[Restore] 仍有 1 个掉落物没收回（可能落在够不到的地方）` + `recovered=0`）。
        //   ⇒ 支撑类改成**上界**：多余的掉落物**只许**来自"拆回来的支撑块"（`restoredBlocks()`）；
        //   非支撑类仍是**零残留**（严格）。
        boolean noDropsLeft = current.expectSupport()
                ? dropsLeft <= mineTask.restoredBlocks()
                : dropsLeft == 0;
        int delta = countInInventory(expectedItem) - inventoryBefore;
        // 精确净增量只在**不涉及支撑块**的用例上成立（那时净增量=掉落物本身，稳定）；
        // 支撑类用例的净增量混了"放置消耗 + 拆回 + 可能的夹具补料"，**不是不变量**
        // （2026-09-11 三轮实测同一用例出现过 2 / 0 / 2）→ 改判**账本闭环+世界事实**（见上面 `supportOk`）+ 下界。
        // ⭐ `D-467`：**上面这句注释从一开始就与代码矛盾**（代码仍在断言 `delta >= expectedCollected`）——
        //   一直没人发现，是因为 `expectSupport` 从来是 `false`（= `O1` 空判据同族）。
        //   实测（`exec_support`）：放支撑 −1 ＋ 目标掉落 +1 ＋ 拆回材料**掉进竖井捡不回** +0 = **0**
        //   ⇒ 支撑类**去掉 delta 那一条**（保留 `collected` 下界）。
        boolean countOk = current.expectSupport()
                ? collected >= current.expectedCollected()
                : (current.exactCollected()
                        ? collected == current.expectedCollected() && delta == current.expectedDelta()
                        : collected >= current.expectedCollected()
                                && delta >= current.expectedCollected());
        // D-175 契约断言：内层任务已终态 ⇒ 再 tick 两次必须幂等（不许崩、不许改状态）
        boolean idempotent = tickTwiceAssertIdempotent() == status;
        // ⑦ D-168 活断言：夹具播下的外来掉落物必须**真的被排除**（否则 `noDropsLeft` 是靠"恰好没残留"通过的）
        boolean foreignOk = !expectedForeignDrop || foreignDrops >= 1;
        boolean pass = status == Status.DONE && targetGone && noDropsLeft && countOk && supportOk
                && idempotent && foreignOk;
        record(current, pass, "status=" + status
                + "/targetGone=" + targetGone
                + "/collected=" + collected + "/" + current.expectedCollected()
                + (current.exactCollected() ? "" : "+")
                + "/inventoryDelta=" + delta
                + (current.expectedDelta() != current.expectedCollected()
                        ? "(期望" + current.expectedDelta() + ")" : "")
                + "/dropsLeft=" + dropsLeft
                + "/idempotent=" + idempotent
                + (expectedForeignDrop ? "/foreignOk=" + foreignOk : "")
                + (foreignDrops > 0 ? "(另有残留" + foreignDrops + "件不计入)" : "")
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
        // 掉落物判据的**基线**（2026-09-13 用户实测暴露的夹具缺陷）：
        // 原先 `dropsLeft` 直接数"目标周围 ±6 内的全部掉落物实体"⇒ 世界里的**历史残留**
        // （上一轮测试/玩家自己挖的东西）会被算成"本用例留下的掉落物"，
        // 于是 `collected=1/1` 也判 FAIL（run1 实测 `dropsLeft=1`，清掉残留后才 PASS）。
        // 修法：用例开始时把该范围内的掉落物 **UUID** 记下来，结束时只数**新增**的
        // （= 本用例自己产生的），残留单独报 `foreignDrops=` 并且**不计入判据**。
        // **⑦ 让 D-168 的"残留不计入"分支每次都被真实触发**（审查结论）：该分支此前只靠推理成立
        // （从未有残留时跑过）。这里由夹具**主动播下**一件外来掉落物（模拟"上一轮遗留/玩家丢的"），
        // 位置在 bot 起点侧后方、测量盒内：被动拾取闸门会把 FOREIGN 拦下 ⇒ 它不会进背包、
        // 也不会干扰 `inventoryDelta`，只会被当作 `foreignDrops` 排除。EXECUTE/CHAIN 用例断言
        // `foreignDrops >= 1` ⇒ 断言"残留真的被排除了"，而不是"恰好没有残留"。
        expectedForeignDrop = false;
        if (current.kind() == Kind.EXECUTE || current.kind() == Kind.CHAIN) {
            // 播种点必须**同时**满足：① 落在 `dropsBox(target)`（判据用的测量盒，以 target 为中心 ±6）内；
            // ② 该格是空气、下方有支撑（不然掉落物会掉出盒外/穿进方块）。
            // 2026-09-13 实测教训：原先固定用 `start+Z1`，对 target 偏北的用例（exec_blocked，
            // target z=134 ⇒ 盒 z=[128,140]）落在**盒外** ⇒ 播种"成功"但基线里没有它 ⇒ `foreignOk=false` 假失败。
            BlockPos dropAt = null;
            for (BlockPos candidate : List.of(current.start(), current.start().offset(0, 0, 1),
                    current.start().offset(0, 0, -1), current.start().offset(1, 0, 0),
                    current.start().offset(-1, 0, 0))) {
                if (!dropsBox(current.target()).contains(candidate.getCenter())) {
                    continue;
                }
                if (!bot.serverLevel().getBlockState(candidate).isAir()) {
                    continue;
                }
                if (bot.serverLevel().getBlockState(candidate.below())
                        .getCollisionShape(bot.serverLevel(), candidate.below()).isEmpty()) {
                    continue;   // 无支撑 ⇒ 会掉下去，可能掉出测量盒
                }
                dropAt = candidate;
                break;
            }
            if (dropAt == null) {
                BotLog.warn("[MineRegression] case={} 找不到合法的播种点（测量盒内 + 空气 + 有支撑）"
                        + "⇒ 本用例不做 foreignDrops 断言（如实降级，不假失败）", current.name());
            } else {
                server.getCommands().performPrefixedCommand(source, String.format(java.util.Locale.ROOT,
                        "summon minecraft:item %d %d %d {Item:{id:\"minecraft:dirt\",Count:1b},PickupDelay:32767s}",
                        dropAt.getX(), dropAt.getY(), dropAt.getZ()));
                expectedForeignDrop = true;
                BotLog.info("[MineRegression] case={} 播种外来掉落物于 {}（测量盒内，用于验证 D-168 排除逻辑）",
                        current.name(), dropAt.toShortString());
            }
        }
        dropsAtCaseStart.clear();
        for (var item : bot.serverLevel().getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class, dropsBox(current.target()))) {
            dropsAtCaseStart.add(item.getUUID());
        }
        if (expectedForeignDrop && dropsAtCaseStart.isEmpty()) {
            BotLog.warn("[MineRegression] case={} 外来掉落物播种失败（summon 没生效？）"
                    + "⇒ 本用例的 foreignDrops 断言会如实判 FAIL", current.name());
        }
        if (!dropsAtCaseStart.isEmpty()) {
            BotLog.warn("[MineRegression] case={} 起点范围内已有 {} 个掉落物（非本用例产生，"
                            + "判据只数新增；场景函数应已清理，若常有说明世界有残留）",
                    current.name(), dropsAtCaseStart.size());
        }
        BotLog.info("[MineRegression] case={} kind={} terrain={} start={} target={}",
                current.name(), current.kind(), current.terrain(),
                current.start().toShortString(), current.target().toShortString());
    }

    /** 掉落物判据的测量盒（用例起点的基线与结束时的计数**必须同盒**，否则基线无效）。 */
    private static net.minecraft.world.phys.AABB dropsBox(BlockPos target) {
        return new net.minecraft.world.phys.AABB(target).inflate(6);
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
    /**
     * **D-175 契约断言**：终态任务再被 `tick()` 必须幂等（同状态返回、不崩服务端）。
     *
     * <p>为什么要有这条：2026-09-13 客户端崩溃正是"内层任务已终态、又被多 tick 了几次" ——
     * 当时 `MineTask` 收尾把 `restoreTask = null` 而 `phase` 仍是 RESTORE ⇒ NPE 打死服务端 tick 循环。
     * 任务侧现在有终态闩锁兜底，这里把**契约本身**钉进夹具：以后谁破坏幂等，这条用例会红。
     */
    private Status tickTwiceAssertIdempotent() {
        Status first = mineTask.tick();
        Status second = mineTask.tick();
        if (first != second || (lastInnerTerminal != null && first != lastInnerTerminal)) {
            BotLog.warn("[MineRegression] 终态幂等被破坏：terminal={} 再 tick → {} / {}",
                    lastInnerTerminal, first, second);
        }
        return second;
    }

    private void finishCase() {
        if (chainModeBefore != null) {
            MiningTuning.setChainMode(chainModeBefore);
            BotLog.info("[MineRegression] 恢复 chain={}", chainModeBefore);
            chainModeBefore = null;
        }
        // ⭐ `D-467`：**密封前提的对称还原**（夹具纪律：每条终态路径都要复位，失败路径同样走）。
        // 放在这里是因为 `finishCase()` 是**所有**用例终态的唯一出口（正常 / 超时 / settle / 连锁 / 前提失败）。
        // `release()` 幂等，且只还"本夹具**自己新认领**的区块"—— 本来就是我们的地不还。
        if (zone != null) {
            zone.release();
            zone = null;
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
