package com.dddgn.alice.task;

import com.dddgn.alice.action.WriteBudget;
import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.action.MineBlockRunner;
import com.dddgn.alice.compat.ChainMining;
import com.dddgn.alice.bot.RecoveryStage;
import com.dddgn.alice.bot.TaskFailureReport;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.task.mining.MiningBudget;
import com.dddgn.alice.task.mining.MiningPlan;
import com.dddgn.alice.task.mining.MiningPlanner;
import com.dddgn.alice.task.mining.MiningProfile;
import com.dddgn.alice.task.mining.MiningTuning;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单目标挖掘编排任务（D-071，D-067 批次 4 第 2 步）。
 *
 * <p>分层：
 * <ul>
 *   <li>规划层 `MiningPlanner`：两模式站位选择 + 成本估算 + top-K 精算 + 预算（A→B→兜底）；</li>
 *   <li>动作层 {@link MineBlockRunner}：走到站位 → 放支撑块 → 破坏目标；</li>
 *   <li>任务层（本类）：编排"评估/规划 → 挖掘 → 收集"，失败重试（≤2）与如实上报。</li>
 * </ul>
 *
 * <p>不再有独立清障：挡路方块由规划器的模式 B（`BREAK_AND_ENTER` 等）在到达过程中处理。
 * 深埋目标是否可挖由 `MiningBudget` 决定（超预算 → `found_but_unminable`）。
 */
public final class MineTask implements Task {
    /**
     * 阶段（D-111 切片 A 起含**加高**）：
     * `EVALUATING →（规划失败且 profile 允许时）GAIN_CLEAR / GAIN → EVALUATING` … `MINING → CHAIN → COLLECTING`。
     */
    private enum Phase { EVALUATING, CLEAR, GAIN_CLEAR, GAIN, MINING, CHAIN, COLLECTING, RESTORE }

    /** 视线失败等可重试情形最多重试 2 次（D-067 Q5）。 */
    private static final int MAX_RECOVERY_ATTEMPTS = 2;

    /** 连锁挖掘等待上限（tick）；超时即停止连锁并如实处理（D-077）。 */
    private static final int CHAIN_TIMEOUT_TICKS = 200;

    private final ServerPlayer bot;
    private final BlockPos target;
    private final ScopeBuffer scope;
    private final MiningBudget budget;
    private final MiningPlanner miningPlanner = new MiningPlanner();
    /** true = 只允许"现成可站站位"（伐木用；禁止挖隧道/破坏进入，见 MiningPlanner#plan）。 */
    /** **能力信封**（D-111）：允许什么手段 + 各自预算；由 L3 构造、本层只读。 */
    private final MiningProfile profile;
    private int gainSteps;
    /** 限次清障（D-115）：已发起的清障次数（预算闸门口径）与成功的次数（报告口径）。 */
    private int clearSteps;
    private int clearedBlocks;
    private MineTask clearTask;
    /**
     * 清障已失败过 → 本目标不再尝试清障（D-115 修正）。
     *
     * <p>为什么必须有：客户端实测（2026-09-11 21:54）清障失败后我又去问 `nextClearStep`，
     * 它给出**同一个** blocker → 重试 8 次烧光整棵树的预算（`clear_end status=FAILED` ×8），
     * 而改造前的伐木 Job 是"清障失败 → 直接放弃这根原木"。失败即不再重试，才不会变成长时间打转。
     */
    private boolean clearExhausted;
    /** 建拆同权（D-112）：会话内自上而下拆除本任务放的临时方块。 */
    private RestoreScopeTask restoreTask;
    /**
     * **终态闩锁（D-175）**：一旦本任务返回过终态（DONE/FAILED），后续 `tick()` 必须**幂等**
     * —— 直接返回同一状态，**不碰任何子任务**。
     *
     * <p>为什么必须有：2026-09-13 客户端**服务端崩溃**实证 —— `tickRestore()` 收尾时把
     * `restoreTask = null` 但**没推进 `phase`**；夹具 `MineRegressionTask` 的 SCOPE_REOPEN 用例
     * 为了让 `ScopeBuffer` flush 又多 tick 了内层任务几 tick ⇒ `phase==RESTORE && restoreTask==null`
     * ⇒ NPE（`MineTask.tickRestore:402`）把服务端 tick 循环打死。
     * <p>规矩：**任务终态后再被 tick 不得崩**（调用方是否多 tick 是调用方的事，任务自己必须稳）。
     */
    private int restoredBlocks;
    private int restorePendingBefore;
    /** 见字段区注释：终态闩锁（D-175）。 */
    private Status terminalStatus;
    /** 没拆干净的数量（如实上报，不静默）。 */
    private int scaffoldLeft;
    private com.dddgn.alice.task.mining.GainStepRunner gainRunner;
    private MineTask gainClearer;
    /** 世界写入授权（D-082）。 */
    private final WriteGrant grant;

    private MineBlockRunner miner;
    private MiningPlan currentPlan;
    private Phase phase = Phase.EVALUATING;
    private CollectDropsTask collector;
    /** 工具前置判定结果（D-119）：非 null 时本任务一 tick 内如实失败，不再动世界。 */
    private final String toolRefusal;
    /**
     * **清障失败的候选**（R2 / D-121）：失败过的阻挡格不再重复挑，而是换下一个候选。
     * 旧实现一失败就把 `clearExhausted` 整体置真 ⇒ 8 格预算只用了 1 格就放弃整棵树
     * （2026-09-11 实测 `clear_start used=1/8` → `clear_end exhausted=true`）。
     */
    private final java.util.Set<BlockPos> failedBlockers = new java.util.LinkedHashSet<>();
    /** 本次正在清的阻挡格（`tickClear` 失败时登记进 {@link #failedBlockers}）。 */
    private BlockPos clearingBlocker;
    /** 清障尝试次数（夹具断言用：应当 > 1 = 确实换过候选）。 */
    private int clearAttempts;
    private String failureReason = "";
    private MineBlockRunner.FailureReport lastFailureReport;
    private int recoveryAttempts;
    private int executionAttempts;
    private RecoveryStage recoveryStage = RecoveryStage.NONE;
    private final List<RecoveryStage> recoveryEvents = new ArrayList<>();
    private MineBlockRunner.Status lastProbeStatus;
    /** D-177：配合 `lastProbeStatus` 做 (phase,status) 去重，避免 MOVING↔MINING 跳变刷屏。 */
    private Phase lastProbePhase;
    private BlockPos optimalStandingPoint;
    private boolean standingPointEvaluated;

    // ---- 连锁挖掘（D-077）----
    /** 本任务是否走模组连锁（规划期一次性判定，回落时置 false）。 */
    private boolean useChain;
    private boolean chainTriggered;
    private int chainTicks;
    private int lastChainMined;

    /**
     * 连锁因**破坏预算耗尽**被强制停止（T1 / R-1）。一经置真即表示"这次连锁本可以挖更多，
     * 是 Alice 的闸门把它停住了" —— 用于日志与终态理由，避免把"我们拦住了"混进"模组就是这样"。
     */
    private boolean chainRefusedByBudget;

    /**
     * **连锁破坏是否被写入预算截断**（G3，2026-09-16）。
     *
     * <p>为什么要有消费者：这个字段原先**只写不读**（审计 G3 的"死哨兵"），于是"预算把 3×3 连锁砍短"
     * 与"矿脉本来就挖完了"在下游**长得一模一样**（任务照样走到收集阶段、照样报成功）。
     * 现在它同时进 {@link #terminalReason()} ⇒ 落进 D-134 的 `task_terminal_reason` 日志与决策快照。
     */
    public boolean chainRefusedByBudget() {
        return chainRefusedByBudget;
    }

    /**
     * 终态理由（D-134 通路）：预算截断必须**如实上报**，不许被泛化成"正常完成"。
     */
    @Override
    public String terminalReason() {
        return chainRefusedByBudget ? "chain_budget_refused" : "";
    }
    /** 触发连锁前的目标方块状态（用于判断连锁是否真的把它挖掉了）。 */
    private BlockState chainTargetState;

    public MineTask(ServerPlayer bot, BlockPos target, ScopeBuffer scope, WriteGrant grant) {
        this(bot, target, scope, MiningBudget.forTarget(
                bot, (net.minecraft.server.level.ServerLevel) bot.level(), target, true), false, grant);
    }

    /** D-067 批次 3：`collectDrops` 为必要参数（false 时跳过放支撑块与收集）。 */
    public MineTask(ServerPlayer bot, BlockPos target, ScopeBuffer scope, MiningBudget budget,
                    WriteGrant grant) {
        this(bot, target, scope, budget, false, grant);
    }

    /**
     * @param standableOnly true = 只用现成可站站位（伐木：禁止挖隧道；清障由 Job 显式负责）
     * @param grant        世界写入授权（D-082）：**调用点必须显式声明**"谁、为什么"挖这一格
     */
    public MineTask(ServerPlayer bot, BlockPos target, ScopeBuffer scope, MiningBudget budget,
                    boolean standableOnly, WriteGrant grant) {
        this(bot, target, scope, budget,
                standableOnly ? MiningProfile.STANDABLE_ONLY : MiningProfile.TUNNEL_ALLOWED, grant);
    }

    /**
     * **能力信封入口**（D-111）：调用点显式声明"允许什么手段、各花多少"，
     * 而不是散落的布尔与硬编码 Movement 集合。
     */
    public MineTask(ServerPlayer bot, BlockPos target, ScopeBuffer scope, MiningBudget budget,
                    MiningProfile profile, WriteGrant grant) {
        this.profile = profile;
        this.grant = grant;
        this.bot = bot;
        this.target = target.immutable();
        this.scope = scope;
        this.budget = budget;
        // D-119：只做**只读**工具判定，绝不改背包（详见 toolRefusal 的注释）。
        this.toolRefusal = toolRefusal(bot, this.target);
        if (this.toolRefusal == null) {
            diagnoseTools(bot, this.target);
        }
        BotLog.info("任务创建: MineTask target={} budget={}", this.target.toShortString(),
                budget.describe());
    }

    /**
     * **只读**工具前置判定（D-119 / R1）。生产 `MineTask` **绝不**改背包。
     *
     * <p>为什么改成"判定并如实失败"而不是继续"兜底发一把钻石镐"：
     * <ol>
     *   <li>**原版事实**：{@code requiresCorrectToolForDrops} 的方块（石头/圆石/矿石…）
     *       **徒手破坏不掉落** —— 没有正确工具时挖了也是白挖，这是**上层该知道的约束**
     *       （目标级决策："先去弄工具"），不是任务该偷偷绕过的问题；</li>
     *   <li>**分层边界**：任务凭空变出工具 = 生产语义被测试语义替换（此前"夹具职责"住在生产
     *       `MineTask` 里，还顺手把所有采矿路径都变成了创造模式）。夹具要工具请在**入口**发
     *       （{@link com.dddgn.alice.item.FixtureToolKit}，D-110 唯一写入点）。</li>
     * </ol>
     *
     * @return null = 可以开工（徒手/劣质工具都允许，原版本来就能慢慢挖）；
     *         非 null = 如实失败的失败码（本任务一 tick 内返回 FAILED，不空转）
     */
    private static String toolRefusal(ServerPlayer bot, BlockPos target) {
        BlockState state = bot.serverLevel().getBlockState(target);
        if (!state.requiresCorrectToolForDrops()) {
            return null;   // 徒手也能挖下来（只是慢）⇒ 不拦
        }
        if (com.dddgn.alice.action.BlockInteraction.hasCorrectTool(bot, target)) {
            return null;
        }
        BotLog.warn("[MineTask] no_suitable_tool target={} block={}（该方块必须正确工具才掉落；"
                        + "本任务**不发工具**，请上层/夹具在入口准备）",
                target.toShortString(),
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        return "no_suitable_tool";
    }

    /**
     * 工具状态诊断（**只读**，D-119 附注）：只在"夹具很可能漏发 / 发错位置"时出声。
     *
     * <p>**为什么不按"最佳破坏速度 ≤ 1"报警**：树叶/草这类方块**本来就没有更快的工具**
     * （原版只有剪刀更快，而伐木清障不需要剪刀）⇒ 按速度报警会在每轮伐木刷 8 条噪声
     * （2026-09-12 实测 8/8 全是清障树叶，而 bot 手上明明有镐），反而把真正的病症埋掉。
     * 现在只报两类**可行动**的病症：
     * <ul>
     *   <li>{@code tool_in_main_inventory}：快捷栏（0..8）没有工具，但主背包里有 ⇒ **选不到**
     *       （D-089 斧子、D-099 一次性方块，同一病灶第三次同形）；</li>
     *   <li>{@code no_tool}：身上根本没有工具 ⇒ 徒手继续（夹具应在入口发料）。</li>
     * </ul>
     */
    private static void diagnoseTools(ServerPlayer bot, BlockPos target) {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < 9 && slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).getItem() instanceof net.minecraft.world.item.DiggerItem) {
                return;   // 快捷栏里有工具 ⇒ 正常，不出声
            }
        }
        for (int slot = 9; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).getItem() instanceof net.minecraft.world.item.DiggerItem) {
                BotLog.warn("[MineTask] tool_in_main_inventory target={}"
                                + "（快捷栏 0..8 里没有工具，主背包里有 ⇒ 永远选不到；夹具应发到快捷栏）",
                        target.toShortString());
                return;
            }
        }
        BotLog.warn("[MineTask] no_tool target={}（身上没有工具，徒手继续；夹具应在入口发料）",
                target.toShortString());
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(target);
    }

    public BlockPos mineStartPos() {
        return miner != null ? miner.mineStartPos() : null;
    }

    @Override
    public String failureReason() {
        return failureReason;
    }

    @Override
    public TaskFailureReport failureReport() {
        MineBlockRunner.FailureReport report = lastFailureReport;
        String details = report == null ? "" : "phase=" + report.phase() + " retryable=" + report.retryable();
        return new TaskFailureReport(failureReason, phase.name(), details, recoveryStage, recoveryEvents());
    }

    public MineBlockRunner.FailureReport lastFailureReport() {
        return lastFailureReport;
    }

    public int executionAttempts() {
        return executionAttempts;
    }

    public int recoveryAttempts() {
        return recoveryAttempts;
    }

    public RecoveryStage recoveryStage() {
        return recoveryStage;
    }

    public List<RecoveryStage> recoveryEvents() {
        return Collections.unmodifiableList(new ArrayList<>(recoveryEvents));
    }

    public boolean currentPlanRetained() {
        return currentPlan != null;
    }

    public MiningPlan currentPlan() {
        return currentPlan;
    }

    /** 收集阶段实际进背包的物品数（未进入收集阶段时为 0；D-076 起为**物品个数**口径）。 */
    public int collectedItems() {
        return collector == null ? 0 : collector.collected();
    }

    private void recordRecovery(RecoveryStage stage) {
        if (stage == null || stage == RecoveryStage.NONE) {
            return;
        }
        if (!recoveryEvents.contains(stage)) {
            recoveryEvents.add(stage);
        }
        recoveryStage = RecoveryStage.highest(recoveryStage, stage);
    }

    @Override
    public Status tick() {
        if (terminalStatus != null) {
            return terminalStatus;   // D-175：终态幂等（见字段注释；这是防服务端崩溃的硬要求）
        }
        Status status = tickOnce();
        if (status != Status.RUNNING) {
            terminalStatus = status;
        }
        return status;
    }

    private Status tickOnce() {
        // S-3（P1-B，2026-09-12）：**不再自调维生**。`BotManager` 的调度循环每 tick 已经
        // `SurvivalSystem.tick(...)` 并把 `HazardState` 交给 `BotSession.tick(hazard)`；
        // 这里再调一次会造出**两套终态记录**（任务自己 FAILED vs 会话 SURVIVAL_INTERRUPTED），
        // 且与 `FollowTask` / 新 Job 层（`job/Job.java`：Job 不调用 SurvivalSystem）的做法不一致。
        // 维生否决 ⇒ 由会话统一记 `SURVIVAL_INTERRUPTED` + 逃生出口（S-1）。

        // D-119：工具不满足（方块必须正确工具才掉落）⇒ 如实失败，不空转、不改世界
        if (toolRefusal != null) {
            failureReason = toolRefusal;
            return Status.FAILED;
        }

        if (phase == Phase.EVALUATING) {
            return evaluateStandingPoint();
        }

        if (phase == Phase.CLEAR) {
            return tickClear();
        }
        if (phase == Phase.GAIN_CLEAR) {
            return tickGainClear();
        }
        if (phase == Phase.GAIN) {
            return tickGain();
        }
        if (phase == Phase.CHAIN) {
            return tickChain();
        }

        if (phase == Phase.RESTORE) {
            return tickRestore();
        }
        if (phase == Phase.COLLECTING) {
            Status status = collector.tick();
            if (status == Status.FAILED) {
                failureReason = collector.failureReason();
                return status;
            }
            return status == Status.DONE ? enterRestoreOrDone() : status;
        }

        MineBlockRunner.Status status = miner.tick();
        // D-177（审查结论 · 日志规矩）：原实现按 `status` 变化打点，而 MOVING↔MINING 会来回跳
        // ⇒ 实测**最高 6 行/秒**、单轮电池 212 行（"验证后应删探针"的规矩）。改成
        // **只在 (phase,status) 组合首次出现**时打一行（典型 3~6 行/用例），
        // 保留诊断价值、去掉刷屏；真正的终态信息仍在 `[MineRunner] done` / `restore_*` 等行里。
        if (status != lastProbeStatus || phase != lastProbePhase) {
            BotLog.info("[MineTask] 挖掘状态: target={} phase={} status={} botPos={} stand={} failure={}",
                    target.toShortString(), phase, status, bot.blockPosition().toShortString(),
                    optimalStandingPoint == null ? "-" : optimalStandingPoint.toShortString(),
                    miner.failureReason().isEmpty() ? "-" : miner.failureReason());
            lastProbeStatus = status;
            lastProbePhase = phase;
        }
        if (status == MineBlockRunner.Status.MINING || status == MineBlockRunner.Status.MOVING) {
            return Status.RUNNING;
        }
        if (status == MineBlockRunner.Status.DONE) {
            if (useChain && !chainTriggered) {
                return beginChain();
            }
            return enterCollection();
        }

        MineBlockRunner.FailureReport report = miner.failureReport();
        lastFailureReport = report;
        BotLog.warn("[MineTask计划失败报告] target={} attempt={} reason={} phase={} retryable={} currentPlanRetained={}",
                target.toShortString(), executionAttempts, report.reason(), report.phase(),
                report.retryable(), currentPlanRetained());
        // D-115：运行期"视线被挡"→ 仍走同一套限次清障（清完重试同一目标）
        if ("LINE_OF_SIGHT_BLOCKED".equals(report.reason()) && tryClearLineOfSight()) {
            return Status.RUNNING;
        }
        if (isHardTargetRefusal(report.reason()) || !report.retryable()) {
            return escalateFailure(report);
        }
        if (tryReplan(report)) {
            return Status.RUNNING;
        }
        return escalateFailure(report);
    }

    // ==================== D-112：建拆同权（会话内自上而下拆我方临时方块） ====================

    /**
     * 目标已挖完（掉落物也收完）→ 若 profile 要求**建拆同权**，先把自己放的临时方块拆掉再结束。
     *
     * <p>为什么必须在这个时刻：这些方块（悬空目标下方的支撑块、接近路上的台阶、原地加高的柱子）
     * 只有在"人还在上面"时才够得到。§12.3 的生命周期：**用 → 仍在架上自上而下拆 → 才允许离开**。
     * 复用 {@link RestoreScopeTask}（自上而下 / 只拆账本内我方 TEMP / `placedState` 不匹配即跳过 /
     * 侧拆兜底 / 收尾材料回收）。
     *
     * <p>**嵌套子任务必须保持 {@code restoreOwnPlacements=false}**：否则它会拆掉会话所有者还要用的
     * 脚手架（例如伐木的加高柱）。
     */
    private Status enterRestoreOrDone() {
        if (!profile.restoreOwnPlacements()) {
            return Status.DONE;
        }
        String scopeId = com.dddgn.alice.ledger.WorldModLedger
                .currentScope(bot.getServer(), bot.getUUID());
        int pending = scopeId == null ? 0
                : com.dddgn.alice.ledger.WorldModLedger
                        .pendingTemporary(bot.getServer(), scopeId).size();
        if (pending == 0) {
            return Status.DONE;
        }
        if (!(bot instanceof com.dddgn.alice.bot.BotPlayer botPlayer)) {
            throw new IllegalStateException("MineTask requires BotPlayer");
        }
        restorePendingBefore = pending;
        BotLog.info("[MineTask] restore_start target={} pending={} scope={}（用完即拆）",
                target.toShortString(), pending, scopeId);
        restoreTask = new RestoreScopeTask(botPlayer, scope, scopeId);
        phase = Phase.RESTORE;
        return Status.RUNNING;
    }

    private Status tickRestore() {
        if (restoreTask == null) {
            // 第二层防御（第一层是终态闩锁）：万一还有别的调用路径进来，也如实收尾而不是 NPE。
            BotLog.warn("[MineTask] restoreTask 缺失（不该发生：终态后被再次 tick？）target={} ⇒ 按拆除结束处理",
                    target.toShortString());
            return Status.DONE;
        }
        Status status = restoreTask.tick();
        if (status == Status.RUNNING) {
            return Status.RUNNING;
        }
        String scopeId = com.dddgn.alice.ledger.WorldModLedger
                .currentScope(bot.getServer(), bot.getUUID());
        scaffoldLeft = scopeId == null ? 0
                : com.dddgn.alice.ledger.WorldModLedger
                        .pendingTemporary(bot.getServer(), scopeId).size();
        restoredBlocks = Math.max(0, restorePendingBefore - scaffoldLeft);
        BotLog.info("[MineTask] restore_end target={} status={} restored={} remaining={}",
                target.toShortString(), status, restoredBlocks, scaffoldLeft);
        restoreTask = null;
        return Status.DONE;
    }

    /** 本任务拆除的我方临时方块数（诊断用）。 */
    public int restoredBlocks() {
        return restoredBlocks;
    }

    /** 本任务结束时仍未拆除的数量（如实上报）。 */
    public int scaffoldLeft() {
        return scaffoldLeft;
    }

    /** 破坏阶段结束（或连锁完成）→ 进入收集阶段。 */
    private Status enterCollection() {
        if (!budget.collectDrops()) {
            BotLog.info("[MineTask] collect_skipped target={} reason=collectDrops=false",
                    target.toShortString());
            return enterRestoreOrDone();
        }
        phase = Phase.COLLECTING;
        if (!(bot instanceof com.dddgn.alice.bot.BotPlayer botPlayer)) {
            throw new IllegalStateException("MineTask requires BotPlayer");
        }
        collector = new CollectDropsTask(botPlayer, target, scope, List.of(), true);
        BotLog.info("挖掘阶段完成,进入拾取阶段: target={}", target.toShortString());
        return Status.RUNNING;
    }

    /**
     * 站到站位后触发模组连锁（D-077）。任何失败都**如实回落**到单格挖掘，
     * 不让"模组不在场/被占用"变成任务失败。
     */
    private Status beginChain() {
        chainTriggered = true;
        chainTargetState = bot.level().getBlockState(target);
        ChainMining.StartResult result = ChainMining.start(bot, target);
        if (result != ChainMining.StartResult.OK) {
            BotLog.warn("[ChainMine] prod_fallback target={} reason={} → 回落单格挖掘",
                    target.toShortString(), result);
            useChain = false;
            miner = null;
            startMining();
            phase = Phase.MINING;
            return Status.RUNNING;
        }
        chainTicks = 0;
        lastChainMined = ChainMining.minedCount(bot);
        phase = Phase.CHAIN;
        BotLog.info("[ChainMine] prod_trigger target={} state={} mode={} settings={} mined0={}",
                target.toShortString(), chainTargetState.getBlock(), MiningTuning.chainMode(),
                ChainMining.settingsSummary(), lastChainMined);
        return Status.RUNNING;
    }

    /** 等待连锁结束 → 校验目标真的没了 → 进入收集；没挖掉则回落单格挖掘。 */
    private Status tickChain() {
        chainTicks++;
        if (ChainMining.isRunning(bot)) {
            int mined = ChainMining.minedCount(bot);
            if (mined != lastChainMined) {
                int delta = mined - lastChainMined;
                lastChainMined = mined;
                BotLog.info("[ChainMine] prod_progress target={} mined={} tick={}",
                        target.toShortString(), mined, chainTicks);
                // ⚠️ T1 / R-1（2026-09-14）：**模组连锁的破坏必须进 Alice 的破坏预算**。
                // 为什么必须在这里补：`useChain=true` 时 `MineBlockRunner` 是以 `walkOnly=true` 构造的
                // （见 `startMining()`）⇒ Alice 自己的破坏原语 `BlockInteraction.beginBreak`（唯一闸门
                // `WriteBudget.consumeBreak` 的挂点）**一次都不执行**，破坏由模组自己的调度器
                // （`player.gameMode.destroyBlock`）完成 ⇒ **唯一的模组兼容破坏路径完全无计数上限**
                // （3×3 连锁发生在 `DEFAULT_MAX_BREAKS=64` 之外；三路审计 §3.1 R-1 实证）。
                // 这里按 `minedCount` 的**增量**逐次计账；pos 传连锁起点 `target`（`consumeBreak`
                // 只用它写日志，不做区域判定）—— 预算耗尽就**停止连锁**，把"无界写入"变回"有界写入"。
                for (int i = 0; i < delta; i++) {
                    if (WriteBudget.consumeBreak(bot, bot.serverLevel(), target, grant)
                            == WriteBudget.Verdict.REFUSED) {
                        chainRefusedByBudget = true;
                        BotLog.warn("[ChainMine] prod_budget_exhausted target={} mined={} delta={} {}"
                                        + " → **立即停止连锁**（破坏预算已满，不许继续无界破坏）",
                                target.toShortString(), mined, delta, WriteBudget.describe(bot));
                        ChainMining.stop(bot);
                        break;
                    }
                }
            }
            if (chainTicks <= CHAIN_TIMEOUT_TICKS) {
                return Status.RUNNING;
            }
            BotLog.warn("[ChainMine] prod_timeout target={} ticks={} mined={} → 停止连锁",
                    target.toShortString(), chainTicks, mined);
            ChainMining.stop(bot);
        }
        int mined = ChainMining.minedCount(bot);
        BlockState now = bot.level().getBlockState(target);
        if (chainTargetState != null && now.getBlock() != chainTargetState.getBlock()) {
            BotLog.info("[ChainMine] prod_done target={} mined={} ticks={} → 收集",
                    target.toShortString(), mined, chainTicks);
            return enterCollection();
        }
        BotLog.warn("[ChainMine] prod_target_remains target={} mined={} → 回落单格挖掘",
                target.toShortString(), mined);
        useChain = false;
        miner = null;
        startMining();
        phase = Phase.MINING;
        return Status.RUNNING;
    }

    private boolean tryReplan(MineBlockRunner.FailureReport report) {
        if (currentPlan == null || report == null || recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) {
            return false;
        }
        recoveryAttempts++;
        MiningPlan previousPlan = currentPlan;
        MiningPlanner.Result result = miningPlanner.plan(bot, target, budget, profile.standableOnly());
        if (!result.success()) {
            BotLog.warn("[MineTask重规划探针] target={} recoveryAttempt={}/{} oldStanding={} result=FAILED reason={}",
                    target.toShortString(), recoveryAttempts, MAX_RECOVERY_ATTEMPTS,
                    previousPlan.standingFoot().toShortString(), result.failureReason());
            return false;
        }
        currentPlan = result.plan();
        recordRecovery(RecoveryStage.MINETASK_REPLAN);
        optimalStandingPoint = currentPlan.standingFoot();
        lastFailureReport = report;
        BotLog.info("[MineTask重规划探针] target={} recoveryAttempt={}/{} oldStanding={} newStanding={} mode={} newPathStatus={}",
                target.toShortString(), recoveryAttempts, MAX_RECOVERY_ATTEMPTS,
                previousPlan.standingFoot().toShortString(), currentPlan.standingFoot().toShortString(),
                currentPlan.mode(), currentPlan.path().status());
        phase = Phase.MINING;
        startMining();
        return true;
    }

    private Status escalateFailure(MineBlockRunner.FailureReport report) {
        recordRecovery(RecoveryStage.ESCALATED_FAILURE);
        failureReason = report == null || report.reason().isBlank() ? "unknown_failure" : report.reason();
        lastFailureReport = report;
        BotLog.warn("[MineTask升级决策探针] target={} reason={} phase={} attempts={} recoveryAttempts={}/{} currentPlanRetained={} nextAction=ESCALATE",
                target.toShortString(), failureReason,
                report == null ? "NONE" : report.phase(), executionAttempts,
                recoveryAttempts, MAX_RECOVERY_ATTEMPTS, currentPlanRetained());
        return Status.FAILED;
    }

    private static boolean isHardTargetRefusal(String reason) {
        return "unbreakable_block".equals(reason)
                || "fluid_risk_lava".equals(reason)
                || "TARGET_NOT_BREAKABLE".equals(reason)
                || reason.startsWith("protected_");
    }

    /** 阶段 1：请求一次挖掘领域规划（两模式），并保存计划快照。 */
    // ==================== D-115：限次清障（能力下沉到 L2） ====================

    /**
     * **限次清障**：站位规划失败时，若 profile 给了清障预算，就先清掉"最该清的那一格"再重试。
     *
     * <p>为什么下沉到 L2：伐木 Job 原先自己实现了两套清障（规划期 `nextClearStep` + 运行期
     * `LINE_OF_SIGHT_BLOCKED`），换任何新任务都要重写一遍（正是用户指出的"不同授权就要重写逻辑"）。
     * 现在只有一处实现，预算由 {@link MiningProfile#clearBudget()} 声明、逐目标递减。
     */
    private boolean tryClear(String reason) {
        if (clearExhausted || !profile.mayClear() || clearSteps >= profile.clearBudget()) {
            return false;
        }
        if (reason == null || !(reason.contains("standing_point") || reason.contains("no_valid")
                || reason.contains("no_reachable") || reason.contains("LINE_OF_SIGHT"))) {
            return false;
        }
        BlockPos blocker = com.dddgn.alice.task.mining.BlockerClearPlanner.nextClearStep(
                bot.serverLevel(), bot, target, bot.getBlockReach(),
                profile.clearBudget() - clearSteps,
                grant.with(com.dddgn.alice.action.WriteReason.LINE_OF_SIGHT),
                failedBlockers);
        if (blocker == null) {
            // R2：**候选都用过了**才放弃（而不是"失败一次就放弃"）
            clearExhausted = true;
            BotLog.info("[MineTask] clear_exhausted target={} tried={} used={}/{}（没有更多候选）",
                    target.toShortString(), clearAttempts, clearSteps, profile.clearBudget());
            return false;
        }
        return startClear(blocker, "planning:" + reason);
    }

    /** 运行期视线被挡 → 清掉当前第一个阻挡物。 */
    private boolean tryClearLineOfSight() {
        if (clearExhausted || !profile.mayClear() || clearSteps >= profile.clearBudget()) {
            return false;
        }
        BlockPos blocker = com.dddgn.alice.task.mining.LineOfSightChecker
                .checkFromEye(bot.serverLevel(), bot.getEyePosition(), target).getFirstBlocker();
        if (blocker != null && failedBlockers.contains(blocker)) {
            // R2：这一格刚失败过 ⇒ 不原地重试，走"换下一个候选"的规划器路径
            return tryClear("runtime:line_of_sight_blocked");
        }
        return startClear(blocker, "runtime:line_of_sight_blocked");
    }

    private boolean startClear(BlockPos blocker, String why) {
        if (blocker == null
                || !com.dddgn.alice.task.mining.BlockerClearPlanner.clearable(bot, bot.serverLevel(),
                        blocker, grant.with(com.dddgn.alice.action.WriteReason.LINE_OF_SIGHT))) {
            return false;
        }
        clearSteps++;
        clearAttempts++;
        clearingBlocker = blocker.immutable();
        BotLog.info("[MineTask] clear_start target={} blocker={} used={}/{} attempt={} why={}",
                target.toShortString(), blocker.toShortString(), clearSteps, profile.clearBudget(),
                clearAttempts, why);
        // R2：子任务信封 = **父信封的子集**（清障归零防递归；加高取 min(父,1)；建拆同权归 false）
        MiningProfile subProfile = profile.nestedSubTask();
        BotLog.info("[MineTask] clear_subtask_profile target={} blocker={} profile={}",
                target.toShortString(), blocker.toShortString(), subProfile.describe());
        clearTask = new MineTask(bot, blocker, scope,
                MiningBudget.forTarget(bot, bot.serverLevel(), blocker, false),
                subProfile,
                grant.with(com.dddgn.alice.action.WriteReason.LINE_OF_SIGHT));
        phase = Phase.CLEAR;
        return true;
    }

    /** 现在是否存在"触及范围内"的站位候选（改造前 Job 的 `hasStandNow` 口径，用于二分加高/清障）。 */
    private boolean hasStandingCandidateNow() {
        return !com.dddgn.alice.task.mining.StandingPointSelector
                .generateCandidates(bot.serverLevel(), target,
                        com.dddgn.alice.pathing.MovementHelper.footCell(bot.serverLevel(), bot),
                        bot.getBlockReach())
                .isEmpty();
    }

    private Status tickClear() {
        Status status = clearTask.tick();
        if (status == Status.RUNNING) {
            return Status.RUNNING;
        }
        if (status == Status.DONE) {
            clearedBlocks++;
        } else {
            // R2：只登记**这一格**失败，换下一个候选；整体放弃交给 tryClear（候选用尽）或预算用尽
            if (clearingBlocker != null) {
                failedBlockers.add(clearingBlocker);
                BotLog.warn("[MineTask] clear_skip blocker={} reason={}（换下一个候选，不放弃整棵树）",
                        clearingBlocker.toShortString(),
                        clearTask.failureReason().isEmpty() ? "unknown" : clearTask.failureReason());
            }
        }
        BotLog.info("[MineTask] clear_end target={} status={} cleared={} used={}/{} attempts={}"
                        + " failed={} exhausted={}",
                target.toShortString(), status, clearedBlocks, clearSteps, profile.clearBudget(),
                clearAttempts, failedBlockers.size(), clearExhausted);
        clearingBlocker = null;
        clearTask = null;
        standingPointEvaluated = false;
        phase = Phase.EVALUATING;
        return Status.RUNNING;
    }

    // ==================== D-111 切片 A：最小高度增益（能力下沉到 L2） ====================

    /**
     * 够不到目标时**原地加高 1 格**再重试（用户 2026-09-11 裁定：树越高越省，差多少加多少）。
     *
     * <p>为什么加高 1 格就能成：加高后 **bot 自己脚下的柱子顶面**在目标触及范围内且 0 步可达，
     * 于是 {@code MiningPlanner} 的原班站位选择立刻成功（实测 2026-09-11：`mode=CURRENT cost=0`、
     * `eyeDist 4.46 → 3.88`）。
     *
     * <p>被树冠挡住头顶时：只清**那一格**（嵌套一个不许加高的 `MineTask`），清完再试。
     */
    private boolean tryGainHeight(String reason) {
        if (!profile.mayGain() || gainSteps >= profile.maxGainSteps()) {
            return false;
        }
        if (reason == null || !(reason.contains("standing_point")
                || reason.contains("no_valid") || reason.contains("no_reachable"))) {
            return false;   // 非"站位"类失败不靠加高解决
        }
        BlockPos foot = com.dddgn.alice.pathing.MovementHelper
                .footCell(bot.serverLevel(), bot);
        // D-179 守卫：**加高只能在目标附近用**（加高改善"够不够得着"，不能把 bot 送到远处目标那里）。
        // 缺了它：被传送/漂移到 198 格外的 bot 会就地搭柱子（实测 12 格圆石，位置与目标无因果关系）。
        if (!com.dddgn.alice.task.mining.MiningTuning.gainHorizontallyReachable(bot, target)) {
            BotLog.warn("[MineTask] gain_refused target={} foot={} reason=target_out_of_range"
                            + "（水平超出触及 ⇒ 拒绝异地加高；交由上层换目标/挂起，绝不在无关位置写世界）",
                    target.toShortString(), foot.toShortString());
            failureReason = "gain_target_out_of_range";
            return false;
        }
        BlockPos goal = foot.above();
        for (BlockPos cell : new BlockPos[]{goal, goal.above()}) {
            if (!com.dddgn.alice.pathing.MovementHelper.canWalkThrough(bot.serverLevel(), cell)) {
                WriteGrant clearGrant = grant.with(profile.gainReason());
                if (!clearableForGain(cell, clearGrant)) {
                    return false;
                }
                BotLog.info("[MineTask] gain_clear target={} head={} profile={}",
                        target.toShortString(), cell.toShortString(), profile.describe());
                gainClearer = new MineTask(bot, cell, scope,
                        MiningBudget.forTarget(bot, bot.serverLevel(), cell, false),
                        MiningProfile.STANDABLE_ONLY, clearGrant);
                phase = Phase.GAIN_CLEAR;
                return true;
            }
        }
        if (!(bot instanceof com.dddgn.alice.bot.BotPlayer botPlayer)) {
            throw new IllegalStateException("MineTask requires BotPlayer");
        }
        // D-116：加高动作抽成**共享执行器**（收集侧也要用同一份实现，不再各写一遍）
        gainRunner = new com.dddgn.alice.task.mining.GainStepRunner(botPlayer, profile, grant);
        BotLog.info("[MineTask] gain_start target={} from={} to={} steps={}/{} profile={}",
                target.toShortString(), foot.toShortString(), goal.toShortString(),
                gainSteps + 1, profile.maxGainSteps(), profile.describe());
        phase = Phase.GAIN;
        return true;
    }

    /** 加高时"能不能清掉挡住头顶的这一格"（与清障能力同一口径）。 */
    private boolean clearableForGain(BlockPos pos, WriteGrant clearGrant) {
        return com.dddgn.alice.task.mining.BlockerClearPlanner
                .clearable(bot, bot.serverLevel(), pos, clearGrant);
    }

    private Status tickGainClear() {
        Status status = gainClearer.tick();
        if (status == Status.RUNNING) {
            return Status.RUNNING;
        }
        gainClearer = null;
        standingPointEvaluated = false;
        phase = Phase.EVALUATING;
        return Status.RUNNING;
    }

    private Status tickGain() {
        com.dddgn.alice.task.mining.GainStepRunner.State state = gainRunner.tick();
        if (state == com.dddgn.alice.task.mining.GainStepRunner.State.RUNNING) {
            return Status.RUNNING;
        }
        boolean ok = state == com.dddgn.alice.task.mining.GainStepRunner.State.DONE;
        gainRunner = null;
        if (!ok) {
            gainSteps = profile.maxGainSteps();   // 加高不可行 → 不再重试（避免原地打转）
            BotLog.warn("[MineTask] gain_failed target={} state={} → 如实失败",
                    target.toShortString(), state);
        } else {
            gainSteps++;
            BotLog.info("[MineTask] gain_done target={} foot={} steps={}/{}",
                    target.toShortString(),
                    com.dddgn.alice.pathing.MovementHelper
                            .footCell(bot.serverLevel(), bot).toShortString(),
                    gainSteps, profile.maxGainSteps());
        }
        standingPointEvaluated = false;
        phase = Phase.EVALUATING;
        return Status.RUNNING;
    }

    /** 本任务为够到目标加高了几格（L3 用它决定是否需要"作业点就地扫尾"，D-107 附注）。 */
    public int gainedSteps() {
        return gainSteps;
    }

    /** 本任务为"腾站位/通视线/开立柱"成功清掉的阻挡方块数（L3 用它做逐树/逐目标记账）。 */
    /** 清障尝试次数（夹具断言"失败后确实换过候选"）。 */
    public int clearAttempts() {
        return clearAttempts;
    }

    /** 清障失败过的候选格（不可变副本）。 */
    public List<BlockPos> failedClearBlockers() {
        return List.copyOf(failedBlockers);
    }

    public int clearedBlocks() {
        return clearedBlocks;
    }

    private Status evaluateStandingPoint() {
        if (standingPointEvaluated) {
            phase = Phase.MINING;
            startMining();
            return Status.RUNNING;
        }

        MiningPlanner.Result result = miningPlanner.plan(bot, target, budget, profile.standableOnly());
        if (!result.success()) {
            BotLog.warn("[MiningPlanner探针] planning failed target={} reason={} budget={} profile={}",
                    target.toShortString(), result.failureReason(), budget.describe(), profile.describe());
            // S-4（P0-C，2026-09-12 接线）：**硬拒绝**（流体风险 / 保护 / 不可破坏）不是"站位没找好" ——
            // 绝不允许再去加高或清障：在岩浆旁搭柱子、或把挡路方块清掉，等于**主动把自己送进危险**
            // （清障/加高各自还会起一个嵌套 `MineTask`，而那正是"挖穿后邻格岩浆涌入"的场景）。
            // 判据沿用既有清单 `isHardTargetRefusal`（它本来就列了 `fluid_risk_lava`，只是此前没有生产者）。
            if (isHardTargetRefusal(result.failureReason())) {
                return escalateFailure(new MineBlockRunner.FailureReport(
                        result.failureReason(), "planning", false));
            }
            // **与改造前的伐木行为一致（D-115 修正）**：这不是"先清障后加高"的串联，而是**二选一**——
            //   站位候选**存在**（在触及范围内）但路径不通 ⇒ **加高**（抬高后候选变可达，实测高云杉）；
            //   站位候选**不存在**（超出触及）⇒ **清障**（开一个站位/通视线），清障失败即放弃本目标。
            // 串联会把"该放弃的树"也拿去搭柱子（实测 gainedBlocks=10，改造前只有 1）。
            if (hasStandingCandidateNow()) {
                if (tryGainHeight(result.failureReason())) {
                    return Status.RUNNING;
                }
            } else if (tryClear(result.failureReason())) {
                return Status.RUNNING;
            }
            return escalateFailure(new MineBlockRunner.FailureReport(
                    result.failureReason(), "planning", false));
        }

        currentPlan = result.plan();
        optimalStandingPoint = currentPlan.standingFoot();
        standingPointEvaluated = true;
        BlockState targetState = bot.level().getBlockState(target);
        useChain = ChainMining.shouldChain(MiningTuning.chainMode(), targetState);
        if (useChain) {
            BotLog.info("[ChainMine] prod_armed target={} state={} mode={} chainable={} settings={}",
                    target.toShortString(), targetState.getBlock(), MiningTuning.chainMode(),
                    ChainMining.isChainable(targetState), ChainMining.settingsSummary());
        }
        BotLog.info("[MiningPlanner探针] planned target={} startFoot={} standingFoot={} mode={} pathStatus={} pathSize={} pathCost={} visibility={} executable={} support={} score={}",
                currentPlan.target().toShortString(), currentPlan.startFoot().toShortString(),
                currentPlan.standingFoot().toShortString(), currentPlan.mode(),
                currentPlan.path().status(), currentPlan.path().movements().size(),
                String.format(java.util.Locale.ROOT, "%.3f", currentPlan.path().totalCost()),
                currentPlan.visibility().isClear(), currentPlan.isExecutable(),
                currentPlan.supportPlacementPos() == null ? "-" : currentPlan.supportPlacementPos().toShortString(),
                result.score() == null ? "-"
                        : String.format(java.util.Locale.ROOT, "%.3f", result.score().getScore()));
        phase = Phase.MINING;
        startMining();
        return Status.RUNNING;
    }

    private void startMining() {
        executionAttempts++;
        if (!(bot instanceof com.dddgn.alice.bot.BotPlayer botPlayer)) {
            throw new IllegalStateException("MineTask requires BotPlayer");
        }
        // useChain=true 时只走到站位（walkOnly），破坏由任务层触发模组连锁
        miner = new MineBlockRunner(botPlayer, currentPlan, useChain && !chainTriggered, grant);
        lastProbeStatus = null;
        lastProbePhase = null;
        BotLog.info("[MineTask探针] 创建 MineBlockRunner: target={} mode={} stand={} botPos={} attempt={}",
                target.toShortString(), currentPlan.mode(),
                currentPlan.standingFoot().toShortString(), bot.blockPosition().toShortString(),
                executionAttempts);
    }
}
