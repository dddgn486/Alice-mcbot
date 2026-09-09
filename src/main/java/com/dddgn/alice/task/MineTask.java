package com.dddgn.alice.task;

import com.dddgn.alice.action.BotMiner;
import com.dddgn.alice.bot.RecoveryStage;
import com.dddgn.alice.bot.TaskFailureReport;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.search.PlanningStatus;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.protection.BlockBreakSafety;
import com.dddgn.alice.survival.HazardState;
import com.dddgn.alice.survival.SurvivalSystem;
import com.dddgn.alice.task.mining.LineOfSightChecker;
import com.dddgn.alice.task.mining.MiningPlan;
import com.dddgn.alice.task.mining.MiningPlanner;
import com.dddgn.alice.task.mining.StandingPointSelector;
import com.dddgn.alice.task.mining.StandingPointEvaluator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单目标挖掘编排任务：智能站位选择 -> 有限局部清障 -> BotMiner 挖掘 -> DropCollectionTask 收集。
 * 普通挖矿不生成道路或隧道，深埋目标明确失败为 target_requires_tunnel。
 * 
 * <h3>优化</h3>
 * <ul>
 *   <li>任务开始前评估站位质量，优先选择视线清晰的站位</li>
 *   <li>减少无效移动和清障次数</li>
 * </ul>
 */
public final class MineTask implements Task {
    private enum Phase { EVALUATING, MINING, COLLECTING }
    private enum FailureHandling { CONTINUE_EXISTING_RECOVERY, ESCALATE }

    /** D-067 批次 3：视线失败等可重试情形最多重试 2 次（Q5 裁定）。 */
    private static final int MAX_RECOVERY_ATTEMPTS = 2;

    private final ServerPlayer bot;
    private final BlockPos target;
    private final ScopeBuffer scope;
    private final com.dddgn.alice.task.mining.MiningBudget budget;
    private final MiningPlanner miningPlanner = new MiningPlanner();
    private BotMiner miner;
    private MiningPlan currentPlan;
    private BlockPos currentMineTarget;
    private Phase phase = Phase.EVALUATING;
    private DropCollectionTask collector;
    private String failureReason = "";
    private BotMiner.FailureReport lastFailureReport;
    private int recoveryAttempts;
    private int executionAttempts;
    private BotMiner.Status lastProbeMinerStatus;
    private RecoveryStage recoveryStage = RecoveryStage.NONE;
    private final List<RecoveryStage> recoveryEvents = new ArrayList<>();
    private BotMiner observedMiner;
    private int observedMinerEventCount;
    
    // 站位优化相关
    private BlockPos optimalStandingPoint;
    private boolean standingPointEvaluated = false;

    public MineTask(ServerPlayer bot, BlockPos target, ScopeBuffer scope) {
        this(bot, target, scope, com.dddgn.alice.task.mining.MiningBudget.forTarget(
                bot, (net.minecraft.server.level.ServerLevel) bot.level(), target, true));
    }

    /** D-067 批次 3：`collectDrops` 为必要参数（false 时跳过放支撑块与收集）。 */
    public MineTask(ServerPlayer bot, BlockPos target, ScopeBuffer scope,
                    com.dddgn.alice.task.mining.MiningBudget budget) {
        this.bot = bot;
        this.target = target.immutable();
        this.scope = scope;
        this.budget = budget;
        this.currentMineTarget = this.target;
        bot.getInventory().setItem(bot.getInventory().selected,
                new ItemStack(Items.DIAMOND_PICKAXE));
        com.dddgn.alice.bot.BotManager.syncMainHand(bot);
        BotLog.info("任务创建: MineTask target={}", this.target.toShortString());
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
        BotMiner.FailureReport report = lastFailureReport;
        String details = report == null ? "" : "planInvalidation=" + report.planInvalidation();
        return new TaskFailureReport(failureReason, phase.name(), details, recoveryStage, recoveryEvents());
    }

    public BotMiner.FailureReport lastFailureReport() {
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
        syncMinerRecoveryEvents();
        return Collections.unmodifiableList(new ArrayList<>(recoveryEvents));
    }

    private void syncMinerRecoveryEvents() {
        if (miner == null) return;
        if (miner != observedMiner) {
            observedMiner = miner;
            observedMinerEventCount = 0;
        }
        List<RecoveryStage> minerEvents = miner.recoveryEvents();
        while (observedMinerEventCount < minerEvents.size()) {
            RecoveryStage event = minerEvents.get(observedMinerEventCount++);
            if (!recoveryEvents.contains(event)) {
                recoveryEvents.add(event);
            }
            recoveryStage = RecoveryStage.highest(recoveryStage, event);
        }
    }

    private void recordRecovery(RecoveryStage stage) {
        syncMinerRecoveryEvents();
        if (stage == null || stage == RecoveryStage.NONE) return;
        if (!recoveryEvents.contains(stage)) {
            recoveryEvents.add(stage);
        }
        recoveryStage = RecoveryStage.highest(recoveryStage, stage);
    }

    public boolean currentPlanRetained() {
        return currentPlan != null;
    }

    public MiningPlan currentPlan() {
        return currentPlan;
    }

    @Override
    public Status tick() {
        HazardState hazard = SurvivalSystem.tick(bot);
        if (SurvivalSystem.shouldInterrupt(hazard)) {
            failureReason = SurvivalSystem.interruptionReason(hazard);
            return Status.FAILED;
        }
        
        // 阶段 1：评估站位
        if (phase == Phase.EVALUATING) {
            return evaluateStandingPoint();
        }
        
        // 阶段 2：挖掘
        if (phase == Phase.COLLECTING) {
            Status status = collector.tick();
            if (status == Status.FAILED) {
                failureReason = collector.failureReason();
            }
            return status;
        }

        BotMiner.Status status = miner.tick();
        if (status != lastProbeMinerStatus) {
            BotLog.info("[MineTask探针] BotMiner状态: target={} mineTarget={} phase={} status={} botPos={} stand={} failure={}",
                    target.toShortString(), currentMineTarget.toShortString(), phase, status,
                    bot.blockPosition().toShortString(),
                    optimalStandingPoint == null ? "-" : optimalStandingPoint.toShortString(),
                    miner.failureReason().isEmpty() ? "-" : miner.failureReason());
            lastProbeMinerStatus = status;
        }
        if (status == BotMiner.Status.MINING || status == BotMiner.Status.MOVING) {
            recoveryStage = RecoveryStage.highest(recoveryStage, miner.recoveryStage());
            return Status.RUNNING;
        }
        if (status == BotMiner.Status.DONE) {
            if (!currentMineTarget.equals(target)) {
                BotLog.info("清障完成: 已挖掉遮挡 {} -> 继续挖原目标 {}",
                        currentMineTarget.toShortString(), target.toShortString());
                startMining(target);
                return Status.RUNNING;
            }
            if (!budget.collectDrops()) {
                BotLog.info("[MineTask] collect_skipped target={} reason=collectDrops=false",
                        target.toShortString());
                return Status.DONE;
            }
            phase = Phase.COLLECTING;
            collector = new DropCollectionTask(bot, target, scope);
            BotLog.info("挖掘阶段完成,进入拾取阶段: target={}", target.toShortString());
            return Status.RUNNING;
        }

        String minerFailure = miner.failureReason();
        BotMiner.FailureReport failureReport = miner.failureReport();
        lastFailureReport = failureReport;
        BotLog.warn("[MineTask计划失败报告] target={} mineTarget={} attempt={} reason={} planInvalidation={} currentPlanRetained={} taskReplanPolicy=ONE_ATTEMPT",
                target.toShortString(), currentMineTarget.toShortString(), executionAttempts,
                failureReport.reason(), failureReport.planInvalidation(), currentPlanRetained());
        FailureHandling handling = classifyFailure(minerFailure, failureReport);
        BotLog.info("[MineTask失败处理探针] target={} reason={} planInvalidation={} handling={} recoveryAttempts={}/{}",
                target.toShortString(), minerFailure, failureReport.planInvalidation(), handling,
                recoveryAttempts, MAX_RECOVERY_ATTEMPTS);
        if (handling == FailureHandling.ESCALATE) {
            return escalateFailure(minerFailure, failureReport);
        }
        // D-067 批次 3：不再有独立清障；失败一律走"重新规划"（规划器会自动从 A 降级到 B），
        // 重试预算用尽后如实上报（深埋目标由 MiningBudget 决定是 TUNNEL 还是 found_but_unminable）。
        if (tryReplan(failureReport)) {
            return Status.RUNNING;
        }
        if ("stand_search_limit".equals(minerFailure)
                || failureReport.planInvalidation() == BotMiner.PlanInvalidation.PLAN_PATH_INCONCLUSIVE) {
            return escalateFailure("stand_search_limit", failureReport);
        }
        return escalateFailure(minerFailure, failureReport);
    }

    private boolean tryReplan(BotMiner.FailureReport report) {
        if (currentPlan == null || report == null || recoveryAttempts >= MAX_RECOVERY_ATTEMPTS
                || !isReplanEligible(report.planInvalidation())) {
            return false;
        }
        recoveryAttempts++;
        MiningPlan previousPlan = currentPlan;
        MiningPlanner.Result result = miningPlanner.plan(bot, target, budget);
        if (!result.success()) {
            BotLog.warn("[MineTask重规划探针] target={} recoveryAttempt={}/{} oldStanding={} result=FAILED reason={}",
                    target.toShortString(), recoveryAttempts, MAX_RECOVERY_ATTEMPTS,
                    previousPlan.standingFoot().toShortString(), result.failureReason());
            return false;
        }
        currentPlan = result.plan();
        recordRecovery(RecoveryStage.MINETASK_REPLAN);
        optimalStandingPoint = currentPlan.standingFoot();
        currentMineTarget = target;
        lastFailureReport = report;
        BotLog.info("[MineTask重规划探针] target={} recoveryAttempt={}/{} oldStanding={} newStanding={} newPathStatus={} newVisibility={} currentPlanReplaced=true",
                target.toShortString(), recoveryAttempts, MAX_RECOVERY_ATTEMPTS,
                previousPlan.standingFoot().toShortString(), currentPlan.standingFoot().toShortString(),
                currentPlan.path().status(), currentPlan.visibility().isClear());
        phase = Phase.MINING;
        startMining(target);
        return true;
    }

    private static boolean isReplanEligible(BotMiner.PlanInvalidation invalidation) {
        return invalidation == BotMiner.PlanInvalidation.PLAN_START_MISMATCH
                || invalidation == BotMiner.PlanInvalidation.PLAN_PATH_STALE
                || invalidation == BotMiner.PlanInvalidation.RUNTIME_VISIBILITY_FAILED
                || invalidation == BotMiner.PlanInvalidation.RUNTIME_OUT_OF_REACH;
    }

    private FailureHandling classifyFailure(String reason, BotMiner.FailureReport report) {
        if (isHardTargetRefusal(reason)) {
            return FailureHandling.ESCALATE;
        }
        return FailureHandling.CONTINUE_EXISTING_RECOVERY;
    }

    private Status escalateFailure(String reason, BotMiner.FailureReport report) {
        recordRecovery(RecoveryStage.ESCALATED_FAILURE);
        failureReason = reason == null || reason.isBlank() ? "unknown_failure" : reason;
        lastFailureReport = report;
        BotLog.warn("[MineTask升级决策探针] target={} reason={} planInvalidation={} attempts={} recoveryAttempts={}/{} currentPlanRetained={} nextAction=ESCALATE",
                target.toShortString(), failureReason,
                report == null ? "NONE" : report.planInvalidation(), executionAttempts,
                recoveryAttempts, MAX_RECOVERY_ATTEMPTS, currentPlanRetained());
        return Status.FAILED;
    }

    /**
     * 请求一次原始目标的挖掘领域规划，并保存计划快照。
     */
    private Status evaluateStandingPoint() {
        if (standingPointEvaluated) {
            phase = Phase.MINING;
            startMining(target);
            return Status.RUNNING;
        }

        BlockPos currentPos = bot.blockPosition().immutable();
        MiningPlanner.Result result = miningPlanner.plan(bot, target, budget);
        if (!result.success()) {
            BotLog.warn("[MiningPlanner探针] planning failed target={} reason={} budget={}",
                    target.toShortString(), result.failureReason());
            return escalateFailure(result.failureReason(), null);
        }

        currentPlan = result.plan();
        if (currentPlan.path().status() == PlanningStatus.SEARCH_LIMIT) {
            BotLog.warn("[MiningPlanner契约] 初始计划不可执行: target={} pathStatus=SEARCH_LIMIT action=FAIL_SAFE",
                    target.toShortString());
            return escalateFailure("stand_search_limit", null);
        }
        if (currentPlan.path().status() == PlanningStatus.UNREACHABLE) {
            BotLog.warn("[MiningPlanner契约] 初始计划不可执行: target={} pathStatus=UNREACHABLE action=FAIL_SAFE",
                    target.toShortString());
            return escalateFailure("no_safe_execution_path", null);
        }
        optimalStandingPoint = currentPlan.standingFoot();
        standingPointEvaluated = true;
        BotLog.info("[MiningPlanner探针] planned target={} startFoot={} standingFoot={} pathStatus={} pathSize={} pathCost={} visibility={} executable={} score={}",
                currentPlan.target().toShortString(), currentPlan.startFoot().toShortString(),
                currentPlan.standingFoot().toShortString(), currentPlan.path().status(),
                currentPlan.path().movements().size(),
                String.format(java.util.Locale.ROOT, "%.3f", currentPlan.path().totalCost()),
                currentPlan.visibility().isClear(), currentPlan.isExecutable(),
                result.score() == null ? "-" : String.format(java.util.Locale.ROOT, "%.3f", result.score().getScore()));
        phase = Phase.MINING;
        startMining(target);
        return Status.RUNNING;
    }

    private static boolean isHardTargetRefusal(String reason) {
        return "unbreakable_block".equals(reason)
                || "fluid_risk_lava".equals(reason)
                || reason.startsWith("protected_");
    }

    private void startMining(BlockPos pos) {
        currentMineTarget = pos.immutable();
        executionAttempts++;
        if (currentMineTarget.equals(target) && currentPlan != null) {
            BotLog.info("[MiningPlan一致性探针] target={} planTarget={} planStartFoot={} planStandingFoot={} optimalStandingPoint={} currentMineTarget={} mode=PLAN",
                    target.toShortString(), currentPlan.target().toShortString(),
                    currentPlan.startFoot().toShortString(), currentPlan.standingFoot().toShortString(),
                    optimalStandingPoint == null ? "-" : optimalStandingPoint.toShortString(),
                    currentMineTarget.toShortString());
            miner = new BotMiner(bot, currentPlan);
        } else {
            BlockPos preferredStand = currentMineTarget.equals(target) ? optimalStandingPoint : null;
            miner = new BotMiner(bot, currentMineTarget, preferredStand);
        }
        lastProbeMinerStatus = null;
        BotLog.info("[MineTask探针] 创建 BotMiner: taskTarget={} mineTarget={} phase={} botPos={} stand={} mode={}",
                target.toShortString(), currentMineTarget.toShortString(), phase,
                bot.blockPosition().toShortString(),
                optimalStandingPoint == null ? "-" : optimalStandingPoint.toShortString(),
                currentMineTarget.equals(target) && currentPlan != null ? "PLAN" : "LEGACY_COMPAT");
    }

}
