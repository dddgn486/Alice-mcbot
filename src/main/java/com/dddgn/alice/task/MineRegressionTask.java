package com.dddgn.alice.task;

import com.dddgn.alice.write.WriteReason;
import com.dddgn.alice.write.WriteGrant;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.task.mining.MineStep;
import com.dddgn.alice.task.mining.MiningBudget;
import com.dddgn.alice.reach.ReachPlan;
import com.dddgn.alice.task.mining.MiningPlanner;
import com.dddgn.alice.reach.ReachOutcome;
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
import com.dddgn.alice.fixture.MineCourseDiagnosticTask;

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
 *   <li>⭐ 原语契约：`step_latch_relaunch`（`D-473`）—— **终态闩锁 + 再开一次作业**：
 *       `walkOnly` 拿一个终态而目标没动 → 再开一次 ⇒ **目标必须真的被挖掉**（见 {@link Kind#LATCH}）；</li>
 *   <li>⚠️ 连锁用例**已撤出**（2026-09-27）—— 它恒 `SKIP` ⇒ 不是覆盖，见下。</li>
 * </ul>
 *
 * <p>⚠️ **连锁（模组兼容档）已于 2026-09-27 从本夹具撤出**（用户裁定）：`exec_chain` /
 * `exec_chain_budget_refused` 两条用例在**模组不在场**时恒为 `SKIP`，而"恒 SKIP 的用例"会把
 * **未验证伪装成已验证**（本仓明令禁止）⇒ 连同它们专用的一套机器（临时 `chain=AUTO`、
 * `CHAIN_STARVED` 的 1 次破坏预算 `setCaps`、`chainModeBefore` 复原）一并删除。
 * ⇒ 连锁改为 **`L1`（单格挖掘原语）交付时的验收测试项**（台账 `O3`）。
 * ⚠️ 数据包场景 `alice_test:chain_mine_course` **保留** —— 它同时是**客户端手工测试入口**，
 * 自带"① `chain off` 只挖 1 格 → ② `chain auto` 整条脉"的对照步骤。
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
         * ⭐ **原语终态闩锁 + `startExecution()` 清闩锁**（`D-473`；台账 `O6` 的**机制半边**）。
         *
         * <p>为什么必须有这一条：`D-469` 给 {@link MineStep} 装了终态闩锁（"返回过终态之后，后续
         * `tick()` 幂等回放同一结论"），而"`startExecution()` **必须清掉它**"这句话当时**只有推理、
         * 没有电池** —— 三条会在拿到终态之后再开一次作业的路（连锁回落 · `tryReplan` · 运行期清障）
         * 在无头电池里**全是零覆盖**（`D-469` §九 自己登记的）。本用例把**机制**钉住：
         *
         * <pre>
         * plan() → startExecution(true)  ⇒ 走 `walkOnly`：**原地即到位 ⇒ DONE**，而目标一根毫毛没动
         *        → startExecution(false) ⇒ 又开一次作业
         *        → tick()                 ⇒ **必须真的把目标挖掉**（不用走位：已在计划站位上）
         * </pre>
         *
         * <p>⚠️ **红臂**（写进本类 javadoc 供手工复核）= 删掉 {@code MineStep.startExecution} 里的
         * `terminal = null;` ⇒ 第二次 `tick()` 回放旧结论（`DONE`）⇒ 编排器/用例以为这一格已经挖完，
         * 而**方块还在世界里** = `D-469` §九 说的「静默假成功」的离线复现。
         *
         * <p>⚠️ 它**不覆盖**编排器的**路由**（`MineTask.tryReplan` 到底有没有被走到）—— 那是 `O6` ②
         * 的另一半，仍缺覆盖；本用例只保证"真走到了也一定是对的"。
         */
        LATCH,
        /**
         * ⭐ **重规划路（`tryReplan`）第一次被真正走到**（`D-474`；台账 `O6` 的**路由半边**）。
         *
         * <p>为什么需要它：`D-473` 量清了"电池里**唯一**的执行段失败是 `WRITE_BUDGET_EXHAUSTED`，
         * 而它是 `retryable=false`"⇒ `tryReplan` 在整轮电池里**结构上到不了**
         * （`[MineTask重规划探针]` **0 行**、`recoveryAttempts` 永远 `0/2`）。本用例造一条**会失败的路**：
         *
         * <pre>
         * 目标放在起点**够不着**处（前提自证：`inPlaceReachable == false` ⇒ 必须走位）
         *   ↓ 计划算完（规划器选中站位 S）
         * ⭐ 夹具把 S 那一列**砌死**（脚位 + 头位）—— **确定性竞态**：计划之后、执行器开始走位之前
         *   ↓ 走到 S 的路径变成 `UNREACHABLE` ⇒ `MOVE_UNREACHABLE`（`retryable=true`，非硬拒绝）
         *   ↓ `tryReplan` ⇒ 重算计划（换一个站位）⇒ `recoveryAttempts` **0 → 1**
         *   ↓ 拆墙（竞态使命完成）⇒ 走到新站位 ⇒ **目标真的被挖掉**
         * </pre>
         *
         * <p>⚠️ **这是合成竞态，必须说清楚**：真实世界里它对应"计划算完之后世界被别人改过"
         * （别的玩家放方块 / 沙砾落下 / 水流改地形）。它**不是**自然地形推出的场景 —— 之所以要这样造，
         * 是因为"规划器选中一个它自己认为可达、而执行器到不了的站位"在离线几何里**没有确定性构造法**。
         *
         * <p>⭐ **判据的承重点是 `recoveryAttempts() >= 1`** —— 全仓**只有** `MineTask.tryReplan` 递增它
         * （`D-473` 已核），所以它是"重规划这条路真的走到了"的**精确**见证，不是相关性证据。
         * 再加"目标真的变空 + 任务 `DONE`" ⇒ 覆盖的是**整条路**（走到 + 走通），不是只走到。
         *
         * <p>⚠️ **反空集断言**：竞态**必须真的开火**（`raceFired`）且真的砌了墙，否则整条用例会以
         * "没跑到"伪装成"重规划没问题"（`Z4` 的空集假绿家族）。
         */
        REPLAN,
        /**
         * ⭐ **运行期清障第一次被走到**（`D-475`；台账 `O6` **③** / `B6` 盲区）。
         *
         * <p>`tickMining()` 的第二条"重新开一次执行"的路：`report.reason() == "LINE_OF_SIGHT_BLOCKED"`
         * ⇒ `tryClearLineOfSight()` ⇒ 起一个**清障子任务** ⇒ 清完回到 `MINING` ⇒ 再 `startExecution()`。
         * `D-473` 实测该路在整轮电池里 **0 行**（`LINE_OF_SIGHT_BLOCKED` 零命中）。
         *
         * <p>造法（与 {@link #REPLAN} 同一套竞态机制，只换"砌哪儿"）：计划算完之后，把
         * **站位格朝目标方向的第一格**（脚位 + 头位）砌死 ⇒ 走到站位时**视线被挡**
         * ⇒ `LINE_OF_SIGHT_BLOCKED`（`retryable=true`，但它在 `retryable` 判断**之前**就被
         * `tryClearLineOfSight` 接走）⇒ 清障 ⇒ 回 `MINING` ⇒ 挖到目标。
         *
         * <p>⭐ **判据的承重点是 `clearedBlocks() >= 1`** —— 全仓只有 `MineTask.tickClear()` 的
         * `DONE` 分支递增它 ⇒ 它是"清障子任务真的跑完了一格"的精确见证。
         *
         * <p>⚠️ **本用例不走标准 EXECUTE 断言**（`collected == N` / `delta == N` / 零残留掉落物）：
         * 清障本身会破坏方块、**产生额外掉落物**（与目标产物同族、无法区分）⇒ 那些精确计数
         * 在这里**不是不变量**。本用例只断言"清障路走到了 + 目标真被挖到"（详见 `assertLosClearCase`）。
         */
        LOS_CLEAR,
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
                           Kind kind, List<ReachPlan.Arrival> expectedModes,
                           int expectedCollected, Item expectedItem, boolean exactCollected,
                           boolean expectSupport, int expectedDelta) {
    }

    private static final BlockPos MINE_START = MineCourseDiagnosticTask.START_FOOT;
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
                                ReachPlan.Arrival... modes) {
        return new CaseDef(name, terrain, start, target, Kind.PLAN, List.of(modes), 0, null,
                true, false, 0);
    }

    private static CaseDef execute(String name, String terrain, BlockPos start, BlockPos target,
                                   int expectedCollected, Item item, boolean exactCollected) {
        return new CaseDef(name, terrain, start, target, Kind.EXECUTE, List.of(),
                expectedCollected, item, exactCollected, false, expectedCollected);
    }

    /**
     * ⭐ `D-474`：**"按 EXECUTE 口径断言"的用例种类**（`EXECUTE` 本身 + `REPLAN`）。
     *
     * <p>为什么用谓词而不是再加一个 `CaseDef` 字段：`REPLAN` 与 `EXECUTE` 的**断言口径完全相同**
     * （任务 `DONE` / 目标变空 / 收集件数 / 无残留掉落物 / 幂等），差别只在"跑的过程中夹具**额外**
     * 把规划器选中的站位砌死一次"⇒ 那是**过程注入**，不该混进"期望值"那张表（`CaseDef` 的字段全是期望值）。
     */
    private static boolean assertsLikeExecute(Kind kind) {
        return kind == Kind.EXECUTE || kind == Kind.REPLAN;
    }

    private static final List<CaseDef> CASES = List.of(
            plan("free", "mine_course", MINE_START, new BlockPos(23, 64, 140),
                    ReachPlan.Arrival.DIRECT_PURE_PASSAGE, ReachPlan.Arrival.IN_PLACE),
            plan("wall", "mine_course", MINE_START, new BlockPos(23, 64, 137),
                    ReachPlan.Arrival.DIRECT_PURE_PASSAGE, ReachPlan.Arrival.IN_PLACE),
            plan("blocked", "mine_course", MINE_START, new BlockPos(23, 64, 134),
                    ReachPlan.Arrival.MINING_APPROACH),
            plan("headroom", "mine_course", MINE_START, new BlockPos(23, 65, 131),
                    ReachPlan.Arrival.DIRECT_PURE_PASSAGE, ReachPlan.Arrival.IN_PLACE),
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
                    Kind.PLAN, List.of(ReachPlan.Arrival.IN_PLACE, ReachPlan.Arrival.DIRECT_PURE_PASSAGE),
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
                    Kind.PLAN, List.of(ReachPlan.Arrival.IN_PLACE, ReachPlan.Arrival.DIRECT_PURE_PASSAGE),
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
            // ⚠️ `exec_chain` / `exec_chain_budget_refused` 已于 2026-09-27 撤出：
            //    它们在模组不在场时恒为 `SKIP` ⇒ **不是覆盖**（恒 SKIP = 把未验证伪装成已验证）。
            //    连锁改为 `L1`（单格挖掘原语）**交付时的验收测试项**，见台账 `O3`。
            // D-119 负例：同一格圆石，但**清空背包**后开工 —— 必须如实失败、不破坏方块、不变出工具
            new CaseDef("no_tool_refuses", "mine_course", MINE_START, new BlockPos(23, 64, 140),
                    Kind.TOOL_REFUSAL, List.of(), 0, Items.COBBLESTONE, true, false, 0),
            // D-124：挖出掉落物 → 重开作用域 → 掉落物必须**仍在账上**（修前 begin() 会清空登记）
            new CaseDef("scope_reopen_keeps_drops", "mine_course", MINE_START,
                    new BlockPos(23, 64, 140), Kind.SCOPE_REOPEN, List.of(), 0,
                    Items.COBBLESTONE, true, false, 0),
            // ⭐ `D-473`（`O6` 机制半边）：**原语的终态闩锁 + 重新开一次作业**。
            // 目标与 `free` 同一格（起点就能触及 ⇒ 规划器给 `CURRENT`/`DIRECT`）—— 选它是为了让
            // 第一次执行走 `walkOnly=true` 时**原地即到位**，从而在"不碰目标"的前提下拿到一个终态结论。
            new CaseDef("step_latch_relaunch", "mine_course", MINE_START, new BlockPos(23, 64, 140),
                    Kind.LATCH, List.of(), 0, null, true, false, 0),
            // ⭐ `D-474`（`O6` 路由半边）：**让 `tryReplan` 第一次真的被走到**。
            // 目标离起点 **7 格**（远超触及 4.5）⇒ 步骤自证"要求走位"；x=21 这一列避开了场景里
            // `wall`/`blocked`/`headroom` 那几簇（它们在 x 22..24、z 131..138）。
            // 期望值：是一格石头 ⇒ 掉落圆石 1 件（与 `exec_direct` 同口径）。
            new CaseDef("exec_replan_race", "mine_course", MINE_START, new BlockPos(21, 64, 133),
                    Kind.REPLAN, List.of(), 1, Items.COBBLESTONE, true, false, 1),
            // ⭐ `D-475`（`O6` ③ / `B6`）：**运行期清障**那条路。同一个目标、另一处竞态
            // （砌"站位朝目标的第一格"⇒ 视线被挡）。⚠️ 本用例**不走标准 EXECUTE 断言**（见 `Kind.LOS_CLEAR`）。
            new CaseDef("exec_runtime_los", "mine_course", MINE_START, new BlockPos(21, 64, 133),
                    Kind.LOS_CLEAR, List.of(), 0, null, true, false, 0));

    /** 单用例预算与任务总预算（tick）。 */
    private static final int CASE_BUDGET_TICKS = 320;
    private static final int MAX_TASK_TICKS = 2600;
    /**
     * ⭐ `D-475`：`LOS_CLEAR` 用例的清障信封。取 **4** 与生产先例同量级
     * （`LumberJob.MAX_CLEAR_PER_TREE` / `clear_retry` 夹具都是 4）；本用例最多需要 2 步
     * （视线可能被脚位、头位两格分别挡住 ⇒ 两次 `tryClearLineOfSight`）。
     */
    private static final int LOS_CLEAR_BUDGET = 4;

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final ScopeBuffer scope;
    private final MiningPlanner planner = new MiningPlanner();
    private final Map<String, String> results = new LinkedHashMap<>();
    private final Map<String, String> details = new LinkedHashMap<>();

    private int index;
    private int ticks;
    private int caseTicks;
    /**
     * ⭐ `D-472`：本用例**逐 tick 采样到的脚位最低 y**（基准见 {@link #caseStartFootY}）。
     *
     * <p>用途 = 把 `D-399` C2 的硬不变量「**回收不许把 bot 摔下去**」搬进 CORE：`exec_support` 的平台
     * 在 y=64、目标正下方是 12 格深坑 ⇒ 脚位**只要低于用例起点 y**，就只可能是**从平台上掉下去了**。
     *
     * <p>⚠️ 为什么不用"单 tick 下落格数"（`RestoreUnderfootSafetyCheckTask` 的 `biggestFall`）：那个量
     * 对**慢速 1~3 格自落是钝的**（脚位格子每 tick 最多差 1）——`D-472` 取证时实测确认过。
     */
    private int lowestFootY;
    /** 本用例起点脚位 y（{@code prepare} 传送之后采一次）—— 掉落判据的基准。 */
    private int caseStartFootY;
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

    // ---- `D-473`：`step_latch_relaunch`（`LATCH`）的状态机 ----
    /**
     * 被直接驱动的原语。⚠️ `LATCH` 用例**不构造 `MineTask`** —— 本条测的就是**原语自己的**闩锁，
     * 走编排器会把"原语是否幂等"和"编排器怎么用它"两件事混在一起（`D-466` 的边界纪律）。
     */
    private MineStep latchStep;
    /** 0=待规划 · 1=待开第一次作业 · 2=第一次执行中 · 3=待开第二次作业 · 4=第二次执行中。 */
    private int latchPhase;
    private String latchFirst = "-";
    private boolean latchTargetKept;
    private String latchSecond = "-";
    private boolean latchMined;
    private boolean latchIdempotent;

    // ---- `D-474`：`exec_replan_race`（`REPLAN`）的确定性竞态 ----
    /** 竞态**是否真的开火**（把规划器选中的站位砌死了）—— 反空集断言（`Z4` 家族）。 */
    private boolean raceFired;
    /** 被砌死的格（**只记原本是空气的格**）—— 收尾按这张表还原。 */
    private final List<BlockPos> raceWall = new java.util.ArrayList<>();
    /** 竞态开火时规划器选的站位（读数用；收尾仍按 {@link #raceWall} 还原）。 */
    private BlockPos raceStanding;
    /** 竞态开火后 `recoveryAttempts` 的首次非零取值（读数用）。 */
    private int raceRecovery;

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
            // ⭐ `D-472`：起点脚位 y = 掉落判据的基准（`prepare` 刚把 bot 传送到统一起点）
            caseStartFootY = (int) Math.floor(bot.getY());
            lowestFootY = caseStartFootY;
            if (current.kind() == Kind.PLAN) {
                runPlanCase(current);
                advance();
                return index >= CASES.size() ? finish() : Status.RUNNING;
            }
            scope.begin(current.target(), 16, bot.getUUID());
            // ⭐ `D-467`：**密封前提必须在 `scope.begin` 之后** —— `TaskZoneRegistry.declare`
            // 的硬约束是"任务区必须挂在**打开的作用域**上"（不许在任务之外造授权封套），
            // 所以 `FixtureZone` 只有在作用域已开时才**借**用现成 scope（不会另开一个、也不会替我们关）。
            if (current.expectSupport() && assertsLikeExecute(current.kind())) {
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
            // ⭐ `D-475`：`TUNNEL_ALLOWED` 的 `clearBudget = 0`（`MiningProfile:66`）⇒ **清障路径结构上
            //   走不到**（`mayClear()` 为假）。生产侧的先例是 `LumberJob:465`（`TARGET_PROFILE.withClear(...)`）
            //   ⇒ 本用例显式给一个清障信封（这是**能力信封**的声明，不是"顺手放宽"）。
            com.dddgn.alice.task.mining.MiningProfile execProfile =
                    com.dddgn.alice.task.mining.MiningProfile.TUNNEL_ALLOWED.withRestore();
            if (current.kind() == Kind.LOS_CLEAR) {
                execProfile = execProfile.withClear(LOS_CLEAR_BUDGET);
            }
            mineTask = new MineTask(bot, current.target(), scope, budget,
                    execProfile,
                    WriteGrant.of(taskName(), WriteReason.EXPECTED_TARGET));
            // **顺序（2026-09-11 修正 / D-119 再修正）**：补镐 → 构造 MineTask → 补一次性方块
            // → **再采基线**。原实现把补料放在基线之后，于是"补了多少圆石"直接进了 inventoryDelta
            // （bot 手头圆石 <8 时就会漂移）⇒ 精确计数根本不是一个不变量。
            ensureCobblestone();
            // 基线在此采集：之后的净增量只反映"放置消耗 + 回收 + 掉落物"这些真实事件
            inventoryBefore = countInInventory(expectedItem);
            return Status.RUNNING;
        }

        // ⭐ `D-472`：逐 tick 采样脚位最低 y（判据在 `expectSupport` 用例上生效 —— 见下面 `noFall`）。
        // 采样点选在"本用例已开跑"之后、任何早退之前，保证每一个 tick 都被看到。
        lowestFootY = Math.min(lowestFootY, (int) Math.floor(bot.getY()));

        if (++caseTicks > CASE_BUDGET_TICKS) {
            record(current, false, "case_timeout ticks=" + caseTicks);
            finishCase();
            return index >= CASES.size() ? finish() : Status.RUNNING;
        }
        if (current.kind() == Kind.LATCH) {
            return tickLatchCase(current);
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
        if (current.kind() == Kind.REPLAN) {
            fireReplanRaceOnce(current);
            // 重规划**已经发生** ⇒ 竞态使命完成，立刻拆墙（免得它挡住后面的收集走位）。
            // `recoveryAttempts` 只由 `MineTask.tryReplan` 递增（`D-473` 已核）⇒ 它是精确见证。
            if (raceFired && !raceWall.isEmpty() && mineTask.recoveryAttempts() >= 1) {
                raceRecovery = mineTask.recoveryAttempts();
                clearRaceWall();
            }
        } else if (current.kind() == Kind.LOS_CLEAR) {
            fireLosRaceOnce(current);
            // ⚠️ **这里绝不拆墙**：那两格正是清障子任务要挖的东西 —— 夹具把它们设成空气会
            //   让 `MineBlockRunner` 第一 tick 就报 DONE（"目标已是空气"）⇒ `clearedBlocks` 变成
            //   **夹具自己造的**，判据当场变成假的。⇒ 只让 bot 挖，`finishCase()` 再兜底还原。
        }
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
        if (current.kind() == Kind.LOS_CLEAR) {
            return assertLosClearCase(current, status);
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
        // ⭐ `D-472`（承接 `D-399` C2「回收不许把 bot 摔下去」）：**收尾期间 bot 的脚位不许低于用例起点**。
        //   为什么这条必须在这里：`exec_support` 的支撑块是**悬空**的（`target.below()` 下方 12 格空气）
        //   ⇒ `MineBlockRunner` 若"就地拆"自己踩着的那一格，bot 必然掉进深坑（实测脚位 64 → 59）。
        //   而旧判据（`supportOk` = 下面空气 + `restoredBlocks≥1` + `scaffoldLeft==0`）**恰好被这次掉落
        //   满足**（方块确实被拆掉了、材料甚至能在下落途中捡到）⇒ 判据把违规判成 PASS。
        //   ⚠️ 只对 `expectSupport` 用例断言（其余用例没有深坑，起点 y 不是"不许低于"的语义）。
        boolean noFall = !current.expectSupport() || lowestFootY >= caseStartFootY;
        // ⭐ `D-474`（`O6` 路由半边）：**重规划这条路必须真的被走到**。
        //   `recoveryAttempts` 全仓只有 `MineTask.tryReplan` 递增 ⇒ 它是"走到了"的精确见证；
        //   `raceFired` 是**反空集**断言（竞态没开火 ⇒ 本用例什么都没测到 ⇒ 如实红，不许假绿）。
        boolean replanOk = current.kind() != Kind.REPLAN
                || (raceFired && mineTask.recoveryAttempts() >= 1);
        boolean pass = status == Status.DONE && targetGone && noDropsLeft && countOk && supportOk
                && idempotent && foreignOk && noFall && replanOk;
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
                + (current.expectSupport() ? "/noFall=" + noFall
                        + "(loweredTo=" + lowestFootY + "/start=" + caseStartFootY + ")" : "")
                // 砌了几格不在这里印：此刻墙已被拆掉（`raceWall` 已清空）⇒ 印 "0 格" 是**误导**。
                // 砌墙细节在开火那条日志里（"已砌死（N 格）"），这里只报判据要用的两个量。
                + (current.kind() == Kind.REPLAN ? "/raceFired=" + raceFired
                        + "/raceStanding=" + (raceStanding == null ? "-" : raceStanding.toShortString())
                        + "/recoveryAttempts=" + mineTask.recoveryAttempts()
                        + "(竞态时=" + raceRecovery + ")" : "")
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
        // 也不会干扰 `inventoryDelta`，只会被当作 `foreignDrops` 排除。EXECUTE 用例断言
        // `foreignDrops >= 1` ⇒ 断言"残留真的被排除了"，而不是"恰好没有残留"。
        expectedForeignDrop = false;
        if (assertsLikeExecute(current.kind())) {
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
        if (current.kind() == Kind.REPLAN || current.kind() == Kind.LOS_CLEAR) {
            raceFired = false;
            raceStanding = null;
            raceRecovery = 0;
            raceWall.clear();
            // ⭐ 目标**由夹具摆**（场景函数只负责地面与周边几何 —— `mine_course` 在 x=21 那一列
            //   本来什么都没有）。不摆的话 `MineBlockRunner` 第一 tick 就因"目标已是空气"报 `DONE`
            //   ⇒ **整条用例退化成空跑**（第一次实测逐字：`ticks=3/targetGone=true/collected=0`；
            //   它照实判了 FAIL，但失败理由会指向"没收到东西"，而不是"前提没摆成"）。
            bot.serverLevel().setBlockAndUpdate(current.target(),
                    net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            if (!bot.serverLevel().getBlockState(current.target())
                    .is(net.minecraft.world.level.block.Blocks.STONE)) {
                record(current, false, "premise_failed 目标方块没摆上（场景函数把它清掉了？）");
                finishCase();
                return;
            }
            // ⭐ 前提自证（`D-474`）：本用例要求"起点够不着目标" ⇒ 执行器**必须走位**
            //   ⇒ 才有"走到那个被砌死的站位"这条路。若起点就能就地挖，竞态永远不开火
            //   ⇒ 整条用例会以"没跑到"伪装成"重规划没问题"（`Z4` 的空集假绿家族）。
            if (com.dddgn.alice.action.MineBlockRunner.inPlaceReachable(
                    bot.serverLevel(), bot, current.target())) {
                record(current, false, "premise_failed 起点就能就地挖（本用例要求够不着 ⇒ 必须走位）");
                finishCase();
                return;
            }
        }
        if (current.kind() == Kind.LATCH) {
            // `D-473`：直接驱动原语（不构造 `MineTask`）。额度按生产口径**构造注入**
            // （`MineStep` 自己不许造额度 —— 判据 D1）；工具与 `MineTask` 用例同源。
            com.dddgn.alice.item.FixtureToolKit.ensurePickaxe(bot);
            latchStep = new MineStep(bot, current.target(),
                    MiningBudget.forTarget(bot, bot.serverLevel(), current.target(), false),
                    com.dddgn.alice.task.mining.MiningProfile.TUNNEL_ALLOWED,
                    WriteGrant.of(taskName(), WriteReason.EXPECTED_TARGET));
            latchPhase = 0;
            latchFirst = "-";
            latchSecond = "-";
            latchTargetKept = false;
            latchMined = false;
            latchIdempotent = false;
        }
    }

    /** 掉落物判据的测量盒（用例起点的基线与结束时的计数**必须同盒**，否则基线无效）。 */
    private static net.minecraft.world.phys.AABB dropsBox(BlockPos target) {
        return new net.minecraft.world.phys.AABB(target).inflate(6);
    }

    /** 规划断言（与 `mine_course` 同口径）。 */
    private void runPlanCase(CaseDef current) {
        MiningBudget budget = MiningBudget.forTarget(bot, bot.serverLevel(), current.target(), true);
        ReachOutcome result = planner.plan(bot, current.target(), budget);
        ReachPlan plan = result.plan();
        boolean pass;
        if ("buried".equals(current.name())) {
            pass = (plan != null && plan.arrival() == ReachPlan.Arrival.MINING_APPROACH)
                    || "found_but_unminable".equals(result.failureReason());
        } else if ("headroom".equals(current.name())) {
            pass = plan != null && plan.standingFoot().getY() == current.target().getY() - 1;
            if (plan != null && !current.expectedModes().isEmpty()) {
                pass = pass && current.expectedModes().contains(plan.arrival());
            }
        } else {
            pass = plan != null && current.expectedModes().contains(plan.arrival());
        }
        if (current.expectSupport()) {
            pass = pass && plan != null && plan.supportPlacementPos() != null
                    && plan.supportPlacementPos().equals(current.target().below());
        }
        record(current, pass, "arrival=" + (plan == null ? "-" : plan.arrival())
                + "/stand=" + (plan == null ? "-" : plan.standingFoot().toShortString())
                + "/cost=" + (result.plan() == null ? "-"
                        : String.format(java.util.Locale.ROOT, "%.2f", result.plan().totalCost()))
                + (current.expectSupport() ? "/support=" + (plan == null || plan.supportPlacementPos() == null
                        ? "-" : plan.supportPlacementPos().toShortString()) : "")
                + "/reason=" + result.failureReason());
    }

    /**
     * ⭐ `D-475`：**竞态之二 —— 砌住"站位朝目标方向的第一格"（脚位 + 头位）**，让视线在**运行期**被挡。
     *
     * <p>与 {@link #fireReplanRaceOnce} 同一套时刻论证（计划与 `startExecution()` 同在
     * `tickEvaluating()` 内，而路径规划在下一个 tick）——**只换"砌哪儿"**：
     * 砌站位 ⇒ 走不过去（`MOVE_*`）；砌"站位与目标之间那一格" ⇒ **走得到、但看不见**（`LINE_OF_SIGHT_BLOCKED`）。
     *
     * <p>⚠️ 为什么砌**脚位 + 头位两格**：`LineOfSightChecker` 对目标的可见面**多点采样**，
     * 到目标上沿的采样走**头位格**、到下沿的走**脚位格** ⇒ 只砌一格可能留下另一条通路
     * （判据就会时红时绿）。这是从"多点采样"这件事直接推出来的，不是试出来的。
     *
     * @return 是否真的开火（同 {@link #raceFired}）
     */
    private boolean fireLosRaceOnce(CaseDef current) {
        if (raceFired) {
            return true;
        }
        ReachPlan plan = mineTask.currentPlan();
        if (plan == null || plan.standingFoot() == null) {
            return false;   // 本 tick 还没算出计划
        }
        BlockPos standing = plan.standingFoot().immutable();
        int dx = Integer.signum(current.target().getX() - standing.getX());
        int dz = Integer.signum(current.target().getZ() - standing.getZ());
        if (dx == 0 && dz == 0) {
            // 站位与目标**同一列**（只差 y）⇒ "朝目标的第一格"没有定义 ⇒ 不猜，如实把前提判红
            BotLog.warn("[MineRegression] case={} 竞态**没开火**：站位 {} 与目标 {} 同列（只差 y）"
                    + "⇒ 本用例的几何前提不成立 ⇒ 断言会如实判红", current.name(),
                    standing.toShortString(), current.target().toShortString());
            return false;
        }
        raceFired = true;
        raceStanding = standing;
        BlockPos step = standing.offset(dx, 0, dz);
        for (BlockPos cell : List.of(step, step.above())) {
            if (cell.equals(current.target())) {
                continue;   // 不砌目标本身（那会变成另一回事）
            }
            if (bot.serverLevel().getBlockState(cell).isAir()) {
                bot.serverLevel().setBlockAndUpdate(cell,
                        net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                raceWall.add(cell);
            }
        }
        BotLog.info("[MineRegression] case={} ⭐ 竞态开火（视线）：站位 {} 朝目标的第一格已砌死"
                        + "（{} 格 = 脚位+头位）⇒ 走得到、看不见 ⇒ `LINE_OF_SIGHT_BLOCKED`"
                        + " ⇒ `tryClearLineOfSight` ⇒ 清障子任务",
                current.name(), standing.toShortString(), raceWall.size());
        return true;
    }

    /**
     * ⭐ `D-475`：`LOS_CLEAR` 用例的判据 —— **不走标准 EXECUTE 断言**（见 {@link Kind#LOS_CLEAR}）。
     *
     * <p>三条（都要，且都是可读的事实）：
     * ① 竞态**真的开火**（`raceFired`；否则整条用例什么都没测到 ⇒ 如实红）；
     * ② ⭐ `clearedBlocks() >= 1` —— 全仓只有 `MineTask.tickClear()` 的 `DONE` 分支递增它
     *    ⇒ 它是"清障子任务真的跑完了一格"的精确见证；
     * ③ 任务 `DONE` **且目标真的变空** —— 覆盖"清障之后**回到挖掘并挖到**"这后半截。
     */
    private Status assertLosClearCase(CaseDef current, Status status) {
        boolean targetGone = bot.serverLevel().getBlockState(current.target()).isAir();
        int cleared = mineTask.clearedBlocks();
        boolean pass = raceFired && cleared >= 1 && status == Status.DONE && targetGone;
        record(current, pass, "status=" + status
                + "/targetGone=" + targetGone
                + "/clearedBlocks=" + cleared
                + "/raceFired=" + raceFired
                + "/raceStanding=" + (raceStanding == null ? "-" : raceStanding.toShortString())
                + "/ticks=" + caseTicks
                + (status == Status.DONE ? "" : "/reason=" + mineTask.failureReason()));
        finishCase();
        return index >= CASES.size() ? finish() : Status.RUNNING;
    }

    /**
     * ⭐ `D-474`：**在计划与执行之间制造一次确定性竞态** —— 把规划器刚选中的站位那一列砌死。
     *
     * <p>时刻是安全的（时序可核）：`MineTask.tickEvaluating()` 在**同一个 tick** 里
     * `step().plan()` + `startExecution()` 然后返回 ⇒ 而执行器要到**下一个 tick** 才会
     * `new PathRetryRunner(...)` 去规划路径（`MineBlockRunner.tickMovement()` 的惰性创建）
     * ⇒ 夹具在本 tick 内砌墙，**一定早于**那条路径被算出来。
     *
     * <p>⚠️ 只砌**原本是空气**的格（`raceWall` 就是还原清单）；若规划器选的站位**就是 bot 自己那格**
     * （`mode=CURRENT`），说明几何不满足"够不着"的前提 ⇒ 不砌（`prepare` 那条自证本该先拦住它）。
     */
    private void fireReplanRaceOnce(CaseDef current) {
        if (raceFired) {
            return;
        }
        ReachPlan plan = mineTask.currentPlan();
        if (plan == null || plan.standingFoot() == null) {
            return;   // 本 tick 还没算出计划（或在计划段就失败了）
        }
        BlockPos standing = plan.standingFoot().immutable();
        if (standing.equals(com.dddgn.alice.pathing.MovementHelper.footCell(bot.serverLevel(), bot))) {
            BotLog.warn("[MineRegression] case={} 竞态**没开火**：规划器选的站位就是 bot 自己那格 {}"
                    + "（几何不满足'够不着'前提）⇒ 本次断言会如实判红", current.name(),
                    standing.toShortString());
            return;
        }
        raceFired = true;
        raceStanding = standing;
        for (BlockPos cell : List.of(standing, standing.above())) {
            if (bot.serverLevel().getBlockState(cell).isAir()) {
                bot.serverLevel().setBlockAndUpdate(cell,
                        net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                raceWall.add(cell);
            }
        }
        BotLog.info("[MineRegression] case={} ⭐ 竞态开火：规划器选中站位 {} 已砌死（{} 格，脚位+头位）"
                        + "⇒ 走到它的路径必然 UNREACHABLE ⇒ 执行段失败（可重试）⇒ `tryReplan`",
                current.name(), standing.toShortString(), raceWall.size());
    }

    /** 拆掉竞态砌的墙（幂等；`finishCase()` 与"重规划已发生"两处都调）。 */
    private void clearRaceWall() {
        if (raceWall.isEmpty()) {
            return;
        }
        for (BlockPos cell : raceWall) {
            bot.serverLevel().setBlockAndUpdate(cell,
                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        }
        BotLog.info("[MineRegression] 竞态拆墙：还原 {} 格 {}", raceWall.size(),
                raceWall.stream().map(BlockPos::toShortString).toList());
        raceWall.clear();
    }

    /**
     * ⭐ `D-473`（`O6` 机制半边）：**终态闩锁 + 再开一次作业** —— `LATCH` 用例的状态机。
     *
     * <p>四个动作、三条判据（每条都带**世界事实**，不是"读它自己的字段"）：
     * <ol>
     *   <li>`plan()` 必须成功（**前提自证**：算不出计划就如实红，绝不静默跳过 —— `Z4` 的空集假绿教训）；</li>
     *   <li>第一次执行用 `walkOnly=true`（**生产形状**：`MineTask` 在连锁回落那条路上就这么传）⇒
     *       终态 `DONE`，而**目标方块还在**（世界事实）；</li>
     *   <li>再 `startExecution(false)` ⇒ 第二次执行必须把**目标真的挖掉**（世界事实）—— 这就是
     *       "闩锁被清掉了"的唯一可信证据；</li>
     *   <li>额外把 `MineStep` 自己的**终态幂等**也断言一次（`D-175`/`D-178` 同形状）。</li>
     * </ol>
     *
     * <p>为什么"目标还在"这条断言不可省：`walkOnly=true` 的语义就是"只走位、不动方块" ——
     * 若不查它，第 ③ 条在"第一次其实已经挖掉了"的情况下也会通过（判据变成空的）。
     */
    private Status tickLatchCase(CaseDef current) {
        if (latchPhase == 0) {
            MineStep.PlanOutcome plan = latchStep.plan();
            if (!plan.ok()) {
                // 前提没摆成 ⇒ 如实判红（否则整条用例会以"没跑到"伪装成"闩锁没问题"）
                record(current, false, "premise_failed 计划不成功 reason=" + plan.reason());
                finishCase();
                return index >= CASES.size() ? finish() : Status.RUNNING;
            }
            latchPhase = 1;
            BotLog.info("[MineRegression] step_latch_relaunch 计划就绪 mode={} stand={}",
                    plan.plan().arrival(), plan.plan().standingFoot().toShortString());
            return Status.RUNNING;
        }
        if (latchPhase == 1) {
            latchStep.startExecution(true);      // walkOnly ⇒ 走到站位即 DONE（不挖）
            latchPhase = 2;
            return Status.RUNNING;
        }
        if (latchPhase == 3) {
            // ⭐ 本用例的全部意义：**拿到终态之后再开一次作业**（= `tryReplan` / 连锁回落 /
            //   运行期清障 三条路共同的那一个动作）。
            latchStep.startExecution(false);
            latchPhase = 4;
            return Status.RUNNING;
        }
        MineStep.Conclusion conclusion = latchStep.tick();
        if (conclusion.isRunning()) {
            return Status.RUNNING;
        }
        if (latchPhase == 2) {
            latchFirst = conclusion.outcome().name();
            latchTargetKept = !bot.serverLevel().getBlockState(current.target()).isAir();
            BotLog.info("[MineRegression] step_latch_relaunch 第一次终态={} 目标仍在={}（walkOnly 不该动方块）",
                    latchFirst, latchTargetKept);
            latchPhase = 3;
            return Status.RUNNING;
        }
        latchSecond = conclusion.outcome().name();
        latchMined = bot.serverLevel().getBlockState(current.target()).isAir();
        MineStep.Conclusion again = latchStep.tick();   // 终态再 tick 必须幂等（不改口、不崩）
        latchIdempotent = again.outcome() == conclusion.outcome();
        boolean pass = "DONE".equals(latchFirst) && latchTargetKept
                && "DONE".equals(latchSecond) && latchMined && latchIdempotent;
        record(current, pass, "first=" + latchFirst + "/targetKeptAfterFirst=" + latchTargetKept
                + "/second=" + latchSecond + "/targetMinedAfterSecond=" + latchMined
                + "/idempotent=" + latchIdempotent + "/ticks=" + caseTicks);
        finishCase();
        return index >= CASES.size() ? finish() : Status.RUNNING;
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
        // ⭐ `D-467`：**密封前提的对称还原**（夹具纪律：每条终态路径都要复位，失败路径同样走）。
        // 放在这里是因为 `finishCase()` 是**所有**用例终态的唯一出口（正常 / 超时 / settle / 连锁 / 前提失败）。
        // `release()` 幂等，且只还"本夹具**自己新认领**的区块"—— 本来就是我们的地不还。
        if (zone != null) {
            zone.release();
            zone = null;
        }
        // ⭐ `D-474`：**竞态砌的墙在每条终态路径上都要还**（正常 / 超时 / 前提失败 / 断言失败）。
        // 与 `zone.release()` 同形 —— `finishCase()` 是用例终态的唯一出口，幂等。
        clearRaceWall();
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
