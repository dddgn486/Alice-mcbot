package com.dddgn.alice.task;

import com.dddgn.alice.action.MineBlockRunner;
import com.dddgn.alice.bot.RecoveryStage;
import com.dddgn.alice.bot.TaskFailureReport;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.survival.HazardState;
import com.dddgn.alice.survival.SurvivalSystem;
import com.dddgn.alice.task.mining.MiningBudget;
import com.dddgn.alice.task.mining.MiningPlan;
import com.dddgn.alice.task.mining.MiningPlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

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
    private enum Phase { EVALUATING, MINING, COLLECTING }

    /** 视线失败等可重试情形最多重试 2 次（D-067 Q5）。 */
    private static final int MAX_RECOVERY_ATTEMPTS = 2;

    private final ServerPlayer bot;
    private final BlockPos target;
    private final ScopeBuffer scope;
    private final MiningBudget budget;
    private final MiningPlanner miningPlanner = new MiningPlanner();

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

    public MineTask(ServerPlayer bot, BlockPos target, ScopeBuffer scope) {
        this(bot, target, scope, MiningBudget.forTarget(
                bot, (net.minecraft.server.level.ServerLevel) bot.level(), target, true));
    }

    /** D-067 批次 3：`collectDrops` 为必要参数（false 时跳过放支撑块与收集）。 */
    public MineTask(ServerPlayer bot, BlockPos target, ScopeBuffer scope, MiningBudget budget) {
        this.bot = bot;
        this.target = target.immutable();
        this.scope = scope;
        this.budget = budget;
        bot.getInventory().setItem(bot.getInventory().selected,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE));
        com.dddgn.alice.bot.BotManager.syncMainHand(bot);
        BotLog.info("任务创建: MineTask target={} budget={}", this.target.toShortString(),
                budget.describe());
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

    private boolean tryReplan(MineBlockRunner.FailureReport report) {
        if (currentPlan == null || report == null || recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) {
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
    private Status evaluateStandingPoint() {
        if (standingPointEvaluated) {
            phase = Phase.MINING;
            startMining();
            return Status.RUNNING;
        }

        MiningPlanner.Result result = miningPlanner.plan(bot, target, budget);
        if (!result.success()) {
            BotLog.warn("[MiningPlanner探针] planning failed target={} reason={} budget={}",
                    target.toShortString(), result.failureReason(), budget.describe());
            return escalateFailure(new MineBlockRunner.FailureReport(
                    result.failureReason(), "planning", false));
        }

        currentPlan = result.plan();
        optimalStandingPoint = currentPlan.standingFoot();
        standingPointEvaluated = true;
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
        miner = new MineBlockRunner(botPlayer, currentPlan);
        lastProbeStatus = null;
        BotLog.info("[MineTask探针] 创建 MineBlockRunner: target={} mode={} stand={} botPos={} attempt={}",
                target.toShortString(), currentPlan.mode(),
                currentPlan.standingFoot().toShortString(), bot.blockPosition().toShortString(),
                executionAttempts);
    }
}
