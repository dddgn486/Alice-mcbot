package com.dddgn.alice.task;

import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.action.MineBlockRunner;
import com.dddgn.alice.compat.ChainMining;
import com.dddgn.alice.bot.RecoveryStage;
import com.dddgn.alice.bot.TaskFailureReport;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.survival.HazardState;
import com.dddgn.alice.survival.SurvivalSystem;
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
    private enum Phase { EVALUATING, GAIN_CLEAR, GAIN, MINING, CHAIN, COLLECTING }

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
    private PathRetryRunner gainRunner;
    private MineTask gainClearer;
    /** 世界写入授权（D-082）。 */
    private final WriteGrant grant;

    private MineBlockRunner miner;
    private MiningPlan currentPlan;
    private Phase phase = Phase.EVALUATING;
    private CollectDropsTask collector;
    private String failureReason = "";
    private MineBlockRunner.FailureReport lastFailureReport;
    private int recoveryAttempts;
    private int executionAttempts;
    private RecoveryStage recoveryStage = RecoveryStage.NONE;
    private final List<RecoveryStage> recoveryEvents = new ArrayList<>();
    private MineBlockRunner.Status lastProbeStatus;
    private BlockPos optimalStandingPoint;
    private boolean standingPointEvaluated;

    // ---- 连锁挖掘（D-077）----
    /** 本任务是否走模组连锁（规划期一次性判定，回落时置 false）。 */
    private boolean useChain;
    private boolean chainTriggered;
    private int chainTicks;
    private int lastChainMined;
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
        ensureTool(bot, this.target);
        BotLog.info("任务创建: MineTask target={} budget={}", this.target.toShortString(),
                budget.describe());
    }

    /**
     * 开发夹具：主手**没有对该目标有效的工具**（破坏速度 ≤ 1）时才补一把钻石镐。
     *
     * <p>2026-09-10 修正：原实现无条件把钻石镐写进当前选中槽，会**顶掉夹具放的斧子**——
     * 结果是砍原木用镐（speed 1.0，3.0 s/根）而不是斧（speed 8.0，0.375 s/根），慢 8 倍。
     * 现在已有有效工具就不动；空手或更差时仍补镐（保持既有场景入口行为）。
     */
    private static void ensureTool(ServerPlayer bot, BlockPos target) {
        var inventory = bot.getInventory();
        ItemStack main = inventory.getItem(inventory.selected);
        net.minecraft.world.level.block.state.BlockState state = bot.serverLevel().getBlockState(target);
        if (!main.isEmpty() && main.getDestroySpeed(state) > 1.0F) {
            return;
        }
        ItemStack pickaxe = new ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE);
        if (main.isEmpty() || pickaxe.getDestroySpeed(state) > main.getDestroySpeed(state)) {
            inventory.setItem(inventory.selected, pickaxe);
            com.dddgn.alice.bot.BotManager.syncMainHand(bot);
        }
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
        HazardState hazard = SurvivalSystem.tick(bot);
        if (SurvivalSystem.shouldInterrupt(hazard)) {
            failureReason = SurvivalSystem.interruptionReason(hazard);
            return Status.FAILED;
        }

        if (phase == Phase.EVALUATING) {
            return evaluateStandingPoint();
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

        if (phase == Phase.COLLECTING) {
            Status status = collector.tick();
            if (status == Status.FAILED) {
                failureReason = collector.failureReason();
            }
            return status;
        }

        MineBlockRunner.Status status = miner.tick();
        if (status != lastProbeStatus) {
            BotLog.info("[MineTask探针] 挖掘状态: target={} phase={} status={} botPos={} stand={} failure={}",
                    target.toShortString(), phase, status, bot.blockPosition().toShortString(),
                    optimalStandingPoint == null ? "-" : optimalStandingPoint.toShortString(),
                    miner.failureReason().isEmpty() ? "-" : miner.failureReason());
            lastProbeStatus = status;
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
        if (isHardTargetRefusal(report.reason()) || !report.retryable()) {
            return escalateFailure(report);
        }
        if (tryReplan(report)) {
            return Status.RUNNING;
        }
        return escalateFailure(report);
    }

    /** 破坏阶段结束（或连锁完成）→ 进入收集阶段。 */
    private Status enterCollection() {
        if (!budget.collectDrops()) {
            BotLog.info("[MineTask] collect_skipped target={} reason=collectDrops=false",
                    target.toShortString());
            return Status.DONE;
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
                lastChainMined = mined;
                BotLog.info("[ChainMine] prod_progress target={} mined={} tick={}",
                        target.toShortString(), mined, chainTicks);
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
        com.dddgn.alice.pathing.core.search.PathRequest request =
                com.dddgn.alice.pathing.core.search.PathRequest.climbApproach(
                        bot.getUUID().toString(), foot, goal, grant.requester() + ":gain");
        com.dddgn.alice.pathing.core.search.PathPlan plan =
                new com.dddgn.alice.pathing.core.search.CorePathPlanner()
                        .plan(bot, bot.serverLevel(), request);
        int pillars = (int) plan.movements().stream()
                .filter(m -> m.movementType() == com.dddgn.alice.pathing.core.MovementType.PILLAR)
                .count();
        if (!plan.reached() || pillars != 1 || pillars > profile.gainBlockBudget()) {
            BotLog.info("[MineTask] gain_unavailable target={} status={} pillar={}/{} profile={}",
                    target.toShortString(), plan.status(), pillars, profile.gainBlockBudget(),
                    profile.describe());
            return false;
        }
        if (!(bot instanceof com.dddgn.alice.bot.BotPlayer botPlayer)) {
            throw new IllegalStateException("MineTask requires BotPlayer");
        }
        gainRunner = new PathRetryRunner(botPlayer, request, PathRetryRunner.DEFAULT_MAX_REPLANS,
                grant.requester() + "-gain");
        BotLog.info("[MineTask] gain_start target={} from={} to={} steps={}/{} profile={}",
                target.toShortString(), foot.toShortString(), goal.toShortString(),
                gainSteps + 1, profile.maxGainSteps(), profile.describe());
        phase = Phase.GAIN;
        return true;
    }

    /** 加高时"能不能清掉挡住头顶的这一格"：非空气、非原木（目标物）、且谓词允许破坏。 */
    private boolean clearableForGain(BlockPos pos, WriteGrant clearGrant) {
        var state = bot.serverLevel().getBlockState(pos);
        return !state.isAir()
                && !state.is(net.minecraft.tags.BlockTags.LOGS)
                && com.dddgn.alice.action.BlockInteraction.breakable(bot, bot.serverLevel(), pos, clearGrant);
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
        PathRetryRunner.State state = gainRunner.tick();
        if (state == PathRetryRunner.State.RUNNING) {
            return Status.RUNNING;
        }
        boolean ok = state == PathRetryRunner.State.DONE;
        gainRunner = null;
        if (!ok) {
            gainSteps = profile.maxGainSteps();   // 加高不可行 → 不再重试（避免原地打转）
            BotLog.warn("[MineTask] gain_failed target={} state={} → 如实失败", target.toShortString(), state);
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
            // D-111 切片 A：够不到（站位规划失败）时，若 profile 允许 → **原地加高 1 格再试**
            if (tryGainHeight(result.failureReason())) {
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
        BotLog.info("[MineTask探针] 创建 MineBlockRunner: target={} mode={} stand={} botPos={} attempt={}",
                target.toShortString(), currentPlan.mode(),
                currentPlan.standingFoot().toShortString(), bot.blockPosition().toShortString(),
                executionAttempts);
    }
}
