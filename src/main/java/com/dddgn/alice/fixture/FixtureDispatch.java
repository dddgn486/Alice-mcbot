package com.dddgn.alice.fixture;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.debug.AscendDiagnosticTask;
import com.dddgn.alice.debug.ChainDiagnosticTask;
import com.dddgn.alice.debug.DescendDiagnosticTask;
import com.dddgn.alice.debug.DiagonalDiagnosticTask;
import com.dddgn.alice.debug.PathSessionDiagnosticTask;
import com.dddgn.alice.debug.TraverseDiagnosticTask;
import com.dddgn.alice.task.TaskTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * ⭐ **开发期派发器**（刀 2；`D-512` 的落地形状）。
 *
 * <h2>为什么要有它</h2>
 * 这些入口原先住在 `bot/BotManager` 里，形状**高度统一**：
 * <pre>
 *   BotSession session = BOTS.get(bot.getUUID());
 *   if (session == null || session.task != null) return false;
 *   session.beginTask(new XxxTask(...), TaskTarget.block(...));
 *   broadcastTarget(session.target);
 *   return true;
 * </pre>
 * 但**任务类本身**住 `fixture/`／`debug/`（开发期桶）⇒ `bot/` 反向依赖开发期包
 * （刀 2 实测：**40 处 `fixture/` import ＋ 6 处 `debug/` import ＋ 47 个构造点**）。
 *
 * <h2>为什么收成一个桥</h2>
 * `bot/BotManager.beginIdleTask(bot, factory, target)` **逐字**复刻上面那四行的语义
 * （`guard busy ⇒ false` → 起任务 → 广播目标 → `true`），且 `factory` **只在真的空闲时才被调用**
 * ⇒ ⛔ 不会白构造任务对象、⛔ 不会改变"忙时提前返回"的次序。
 * <br>⛔ **不是** `BotSession.assignFixtureTask`：那个走 `replaceTaskIfRunning`
 * （**会顶替在跑的任务**），语义不同（全仓只有 `assignSurvivalFixtureCheck` 用它，已单列）。
 *
 * <h2>谁调它</h2>
 * `item/*Item`（`/give` 物品 = 开发期入口，`D-554`）· `debug/DebugCommands`（`D-560`）·
 * `headless/HeadlessBattery` · `fixture/**` —— 全是开发期侧。
 * ⛔ **生产决策路径**（`decision/GoalDirector`）不走这里。
 */
public final class FixtureDispatch {

    private FixtureDispatch() {
    }


    public static boolean assignWalkToDiagnostic(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.WalkToDiagnosticTask(bot, observer), TaskTarget.block(com.dddgn.alice.fixture.WalkToDiagnosticTask.OVER_WALL_GOAL));
    }


    /** Assigns the focused R2-B one-step Traverse diagnostic. */
    public static boolean assignTraverseDiagnostic(BotPlayer bot, BlockPos goalFoot) {
        return BotManager.beginIdleTask(bot, s -> new TraverseDiagnosticTask(bot, goalFoot), TaskTarget.block(goalFoot));
    }


    /** Assigns the focused R2-C one-step Diagonal diagnostic. */
    public static boolean assignDiagonalDiagnostic(BotPlayer bot, BlockPos goalFoot) {
        return BotManager.beginIdleTask(bot, s -> new DiagonalDiagnosticTask(bot, goalFoot), TaskTarget.block(goalFoot));
    }


    /** Assigns the focused R2-C one-step Ascend diagnostic. */
    public static boolean assignAscendDiagnostic(BotPlayer bot, BlockPos goalFoot) {
        return BotManager.beginIdleTask(bot, s -> new AscendDiagnosticTask(bot, goalFoot), TaskTarget.block(goalFoot));
    }


    /** Assigns the focused R2-C one-step Descend diagnostic. */
    public static boolean assignDescendDiagnostic(BotPlayer bot, BlockPos goalFoot) {
        return BotManager.beginIdleTask(bot, s -> new DescendDiagnosticTask(bot, goalFoot), TaskTarget.block(goalFoot));
    }


    /** Assigns the focused R2-C multi-segment chain diagnostic (P0 链接验收). */
    public static boolean assignChainDiagnostic(BotPlayer bot, java.util.List<BlockPos> plannedFoot) {
        BlockPos goal = plannedFoot.get(plannedFoot.size() - 1);
        return BotManager.beginIdleTask(bot, s -> new ChainDiagnosticTask(bot, plannedFoot), TaskTarget.block(goal));
    }


    /**
     * Assigns the R3 one-action self-test battery (plan + all movements + chain).
     *
     * @param hubFoot 测试起点脚位；任务开始与每项开始前会把 bot 锚定到此处
     */
    public static boolean assignPathingBattery(BotPlayer bot, BlockPos hubFoot) {
        return BotManager.beginIdleTask(bot, s -> new PathingBatteryTask(bot, hubFoot), TaskTarget.block(hubFoot));
    }


    /** Assigns the chained multi-scene regression (one action, all scenes). */
    public static boolean assignPathingRegression(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.PathingRegressionTask(bot, observer), TaskTarget.block(new BlockPos(0, 64, 46)));
    }


    /** Assigns the R4 plan→session diagnostic with a deterministic disturbance (自愈验证夹具). */
    public static boolean assignPathSessionDiagnostic(BotPlayer bot, BlockPos goalFoot,
                                                      boolean allowWorldModification,
                                                      int disturbTick, int disturbDx, int disturbDz) {
        return BotManager.beginIdleTask(bot, s -> new PathSessionDiagnosticTask(bot, goalFoot, allowWorldModification,
                disturbTick, disturbDx, disturbDz), TaskTarget.block(goalFoot));
    }


    /** Assigns the R4 plan→session execution diagnostic. */
    public static boolean assignPathSessionDiagnostic(BotPlayer bot, BlockPos goalFoot) {
        return assignPathSessionDiagnostic(bot, goalFoot, false);
    }


    /** Assigns the R4/R5 plan→session diagnostic；allowWorldModification 授权 PATH_ACCESS 破坏。 */
    public static boolean assignPathSessionDiagnostic(BotPlayer bot, BlockPos goalFoot,
                                                      boolean allowWorldModification) {
        return BotManager.beginIdleTask(bot, s -> new PathSessionDiagnosticTask(bot, goalFoot, allowWorldModification), TaskTarget.block(goalFoot));
    }


    /** Assigns the D-043 replan fixture: block the path 2 segments ahead at a fixed tick. */
    public static boolean assignPathingWaller(BotPlayer bot, BlockPos goalFoot, int wallTick) {
        return BotManager.beginIdleTask(bot, s -> new PathSessionDiagnosticTask(bot, goalFoot, true,
                        0, 0, 0, wallTick, com.dddgn.alice.pathing.path.PathRetryRunner.DEFAULT_MAX_REPLANS), TaskTarget.block(goalFoot));
    }


    /** Assigns the DOWNWARD diagnostic (execute + guard). */
    public static boolean assignMineCourseDiagnostic(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.MineCourseDiagnosticTask(bot, observer), TaskTarget.block(new net.minecraft.core.BlockPos(23, 64, 140)));
    }


    /** J6-b2：容器绕行自检（断言 bot 不为取目标而拆箱子，D-095）。 */
    public static boolean assignClearGuardCheck(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.ClearGuardCheckTask(bot, s.scope()), TaskTarget.block(com.dddgn.alice.fixture.ClearGuardCheckTask.START_FOOT));
    }


    /**
     * 指派回归电池（D-197）。
     *
     * @param full false = **CORE**（必要基础 + 当前主线，默认；用户要求"电池不要太长"）；
     *             true  = **FULL**（额外含已验收/无关/耗时项，`/alice battery full` 用）
     */
    public static boolean assignRegressionBattery(BotPlayer bot, ServerPlayer observer, boolean full) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.RegressionBatteryTask(bot, observer, s.scope(),
                        full ? com.dddgn.alice.fixture.RegressionBatteryTask.Mode.FULL
                             : com.dddgn.alice.fixture.RegressionBatteryTask.Mode.CORE), TaskTarget.block(bot.blockPosition()));
    }


    /** R2 限次清障"换候选"自检：一个候选失败要换下一个，而不是放弃整棵树。 */
    public static boolean assignClearRetryCheck(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.ClearRetryCheckTask(bot, s.scope()), TaskTarget.block(com.dddgn.alice.fixture.ClearRetryCheckTask.START_FOOT));
    }


    /** 写入预算自检（D-106）：任务级破坏上限压到 1 格，断言"用满即停、如实失败"。 */
    public static boolean assignQuotaCheck(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.QuotaCheckTask(bot, s.scope()), TaskTarget.block(com.dddgn.alice.fixture.QuotaCheckTask.START_FOOT));
    }


    /** 伐木失败语义自检（切片 J4）：五条终止路径各一个用例。 */
    public static boolean assignLumberFailureCheck(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.LumberFailureCheckTask(bot, s.scope()), TaskTarget.block(com.dddgn.alice.fixture.LumberCourseAnchor.START_FOOT));
    }


    /** 挖掘专项串联回归（批次 5）。 */
    public static boolean assignMineRegression(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.MineRegressionTask(bot, observer, s.scope()), TaskTarget.block(com.dddgn.alice.fixture.MineCourseDiagnosticTask.START_FOOT));
    }


    /** 模组兼容自检：Ore Excavation 连锁挖掘的掉落物捕获与收集。 */
    public static boolean assignChainMineDiagnostic(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.ChainMineDiagnosticTask(bot, observer, s.scope()), TaskTarget.block(com.dddgn.alice.fixture.ChainMineDiagnosticTask.SEED));
    }

    public static boolean assignBreakEnterDiagnostic(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.BreakEnterDiagnosticTask(bot, observer), TaskTarget.block(com.dddgn.alice.fixture.BreakEnterDiagnosticTask.GOAL_A));
    }

    public static boolean assignFallDiagnostic(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.FallDiagnosticTask(bot, observer), TaskTarget.block(com.dddgn.alice.fixture.FallDiagnosticTask.DROP3_GOAL));
    }

    public static boolean assignPillarDiagnostic(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.PillarDiagnosticTask(bot, observer), TaskTarget.block(com.dddgn.alice.fixture.PillarDiagnosticTask.RIM_GOAL));
    }

    public static boolean assignVerticalDiagnostic(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.VerticalDiagnosticTask(bot, observer), TaskTarget.block(com.dddgn.alice.fixture.VerticalDiagnosticTask.OPEN_GOAL));
    }


    /** **被动拾取闸门自检**（S3.5 / D-143）：我方掉落物应捡、外来掉落物应被拦下。 */
    /** K-3 安全点停止自检（确定性夹具：升空后请求停止）。 */
    public static boolean assignK3StopCheck(BotPlayer bot, ServerPlayer observer,
                                            com.dddgn.alice.fixture.K3StopCheckTask.Mode mode) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.K3StopCheckTask(bot, observer, mode), TaskTarget.block(bot.blockPosition()));
    }


    /** L2 菜单协议最小验证探针（开真菜单 → 菜单点击搬物品 → 关闭）。 */
    /** **现成工作台 3×3 合成自检**（阶段 3-A / A3，D-188）：找台→走位→开菜单→合成 + 零写入断言。 */
    public static boolean assignCraftTableCheck(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.CraftTableCheckTask(bot, observer), TaskTarget.block(bot.blockPosition()));
    }


    // 阶段 3-B / S1／S2／S4 的**临时探针入口**（`alice:machine_probe`、`alice:machine_station_probe`、
    // `alice:machine_cycle_check`）已按 **S5 收口**回收 —— 三支任务全部转为**电池步**
    // （`machine_route` / `machine_station` / `machine_cycle`，见 `RegressionBatteryTask.CURATION`），
    // 对应 `assign*` 方法一并删除（`assignMachineProbe` / `assignMachineStationProbe` 在回收后已无调用点
    // = 死代码，2026-09-14 一并清掉）。要单跑某一步请用电池档位，不要再复活临时物品。

    /** A5：决策层合成自检（`alice:craft_goal_check`）。 */
    public static boolean assignCraftGoalCheck(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.CraftGoalCheckTask(bot, observer), TaskTarget.block(bot.blockPosition()));
    }


    /** **熔炼页签自检**（阶段 3-A / A4b，D-198）：菜单型炉子（装升级→烧→取→拆回）。 */
    public static boolean assignCraftCookingCheck(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.CraftFurnaceCheckTask(bot, observer, true), TaskTarget.block(bot.blockPosition()));
    }


    /** **熔炉自检**（阶段 3-A / A4，D-196）：认炉子→放料→等烧→取产物（另一种执行形状）。 */
    public static boolean assignCraftFurnaceCheck(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.CraftFurnaceCheckTask(bot, observer), TaskTarget.block(bot.blockPosition()));
    }


    /** **模组站点真合成自检**（阶段 3-A / C，D-195）：装升级→用页签合成→拆回。 */
    public static boolean assignCraftStationCraftCheck(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.CraftStationCraftCheckTask(bot, observer), TaskTarget.block(bot.blockPosition()));
    }


    /** **工作站装配自检**（阶段 3-A / L2，D-194）：装升级→能力验证→取回复原。 */
    public static boolean assignCraftStationProvisionCheck(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.CraftStationProvisionCheckTask(bot, observer), TaskTarget.block(bot.blockPosition()));
    }


    /** **合成网格探针**（阶段 3-A / S1-3，D-192）：只读打开当前工作站并打印网格/槽位事实。 */
    public static boolean assignCraftGridProbe(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.CraftGridProbeTask(bot, observer), TaskTarget.block(bot.blockPosition()));
    }


    /** **自放工作站合成自检**（阶段 3-A / A3b，D-190）：放台→合成→**拆回**，验"建拆同权"。 */
    public static boolean assignCraftStationCheck(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.CraftStationCheckTask(bot, observer), TaskTarget.block(bot.blockPosition()));
    }


    /** **随身 2×2 合成自检**（阶段 3-A / A2，D-186）：真消耗真产物 + 缺料如实失败 + 网格清理。 */
    public static boolean assignCraftActionCheck(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.CraftActionCheckTask(bot, observer), TaskTarget.block(bot.blockPosition()));
    }


    /** **只读配方查询自检**（阶段 3-A / A1，D-185）：正例/负例/边界 + "背包未变"硬断言。 */
    public static boolean assignCraftCheck(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.CraftCheckTask(bot, observer), TaskTarget.block(bot.blockPosition()));
    }

    public static boolean assignMenuProbe(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.MenuProbeTask(bot, observer), TaskTarget.block(bot.blockPosition()));
    }


    /** R2 传输模块自检：跑 4 个夹具（主流程/端点选择/选择器事件/命令解析）。 */
    public static boolean assignTransferCheck(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.TransferCheckTask(bot, observer), TaskTarget.block(bot.blockPosition()));
    }


    /** 基-7 前缀搜索自检（K-1，纯规划）：PARTIAL 前缀 / 同目标可达 / 真失败不给前缀。 */
    public static boolean assignPartialSearchCheck(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.PartialSearchCheckTask(bot, observer), TaskTarget.block(bot.blockPosition()));
    }


    /** 基-8 能力闸门自检（D-157，纯逻辑）：保护区/资源/工具/预算/声明一致性。 */
    public static boolean assignCapabilityGateCheck(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.CapabilityGateCheckTask(bot, observer), TaskTarget.block(bot.blockPosition()));
    }


    /** 基-9 工具供给自检：换更好的 / 没得换如实报 / 不能凭空变出工具。 */
    public static boolean assignToolSupplyCheck(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.ToolSupplyCheckTask(bot, observer), TaskTarget.block(bot.blockPosition()));
    }


    /** 基-5 LLM 上抛契约自检（D-155）：Job 失败报告 / 产物判定口径 / 结构化拒绝回读。 */
    public static boolean assignLlmContractCheck(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.LlmContractCheckTask(bot, observer), TaskTarget.block(bot.blockPosition()));
    }


    /** 基-4 决策 trace / 跨重启语义自检（D-154）：落盘 / 内存尾 / NBT 往返 / 重启报告只报一次。 */
    public static boolean assignDecisionTraceCheck(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.DecisionTraceCheckTask(bot, observer), TaskTarget.block(bot.blockPosition()));
    }


    /** 基-1 可回收性自检（D-151，纯计算）：规则表/逐类型/负例/转换点四例。 */
    public static boolean assignRecoverabilityCheck(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.RecoverabilityCheckTask(bot, observer), TaskTarget.block(bot.blockPosition()));
    }


    /** S4 事件阈值自检（D-150）：工具见底 / 卡住 两类病症的"上报 + 只报一次"。 */
    public static boolean assignEventThresholdCheck(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.EventThresholdCheckTask(bot, observer), TaskTarget.block(com.dddgn.alice.fixture.PillarDiagnosticTask.SHAFT_START));
    }

    public static boolean assignPickupGateCheck(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.PickupGateCheckTask(bot, observer), TaskTarget.block(com.dddgn.alice.fixture.PickupGateCheckTask.DROP_B));
    }


    /** **未加载区块/世界边界门控自检**（S-2 / P1-A）：无头规划三个用例，不改世界（边界临时改后立刻还原）。 */
    public static boolean assignChunkGuardCheck(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.ChunkGuardCheckTask(bot, observer), TaskTarget.block(bot.blockPosition()));
    }


    /** **挖掘前流体风险自检**（S-4 / P0-C）：目标下方是岩浆必须硬拒，普通目标必须挖完。 */
    public static boolean assignFluidMineCheck(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new com.dddgn.alice.fixture.FluidMineCheckTask(bot, observer, s.scope()), TaskTarget.block(com.dddgn.alice.fixture.FluidMineCheckTask.TARGET_OVER_LAVA));
    }


    /**
     * **维生全套夹具自检**（`alice:survival_full_check`，D-229 新增）：把
     * {@link com.dddgn.alice.fixture.SurvivalExitCheckTask} 挂成会话任务 —— 它会把 S-5 决策表、
     * 封闭场景（无出口）、真实着火、入水空气消耗、**细雪冻结**全跑一遍，并在聊天里打一行 SUMMARY。
     *
     * <p>为什么要有这个入口：D-229 的冻结相位此前只能靠"疾跑+右键"触发，而**原版站着不动进不了疾跑**
     * ⇒ 那个入口根本点不到（设计错误，2026-09-15 实测发现）。
     *
     * @return 真的派上了活（false ⇒ 调用方必须如实上报，别打印"就位"）
     */
    public static boolean assignSurvivalFixtureCheck(BotPlayer bot, ServerPlayer observer) {
        BotManager.BotSession s = BotManager.sessionOf(bot);
        // ⚠️ 本入口是**唯一**走 `assignFixtureTask`（= `replaceTaskIfRunning`，会**顶替**在跑的任务）
        //     的那个 —— 语义与其他入口**不同**，故单列、⛔ 不许并进 `beginIdleTask`。
        if (s == null || BotManager.isBusy(bot)) {
            return false;
        }
        return s.assignFixtureTask(new com.dddgn.alice.fixture.SurvivalExitCheckTask(bot, observer), TaskTarget.block(bot.blockPosition()));
    }

    /** 回归电池 CORE 档（与 `/alice battery core` 同口径）。 */
    public static boolean assignRegressionBattery(BotPlayer bot, ServerPlayer observer) {
        return assignRegressionBattery(bot, observer, false);
    }

    /**
     * 脚手架生命周期自检（J7 Step 1）：搭柱子爬上去 → 高处干活 → 仍在顶上拆掉 → 落地。
     *
     * <p>⭐ 2026-10-02 从 `bot/BotManager` **原样挪来**（`D-512` 刀 2 的同一形状）：
     * 任务类 `ScaffoldLifecycleTask` 实测是**夹具**（台账 `§D` 第 3 条 / 用户 2026-10-02 裁定），
     * 而旧位置让 `bot/` 代码级 `new` 了 `fixture/` 的类 ⇒ 违反 `R3`（`bot/` 不是注册位置，
     * `check-layer-direction.py:347`）。⇒ 分派跟着任务类走，`bot/` 那一侧改为零类型名。
     * ⚠️ 语义与旧实现**逐字一致**（同样的"忙就 false"、同样的 `CLIMB_GOAL_FOOT`、同样广播目标）。
     */
    public static boolean assignScaffoldCheck(BotPlayer bot, ServerPlayer observer) {
        return BotManager.beginIdleTask(bot, s -> new ScaffoldLifecycleTask(bot, s.scope()),
                TaskTarget.block(ScaffoldLifecycleTask.CLIMB_GOAL_FOOT));
    }

}
