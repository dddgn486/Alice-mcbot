package com.dddgn.alice.job.lumber;

import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.job.Candidate;
import com.dddgn.alice.job.CandidateSet;
import com.dddgn.alice.job.DecisionTrace;
import com.dddgn.alice.job.GoalSpec;
import com.dddgn.alice.job.Job;
import com.dddgn.alice.job.Selection;
import com.dddgn.alice.job.SelectionPolicy;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.task.CollectDropsTask;
import com.dddgn.alice.task.MineTask;
import com.dddgn.alice.task.Task;
import com.dddgn.alice.task.TaskTarget;
import com.dddgn.alice.task.mining.LineOfSightChecker;
import com.dddgn.alice.task.mining.MiningBudget;
import com.dddgn.alice.task.mining.StandingPointSelector;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 伐木 Job（L3 第一消费者；J1 = 单棵闭环，**J2 = 循环 + 配额 + 终止语义**）。
 *
 * <p>职责只有四件（`docs/JOB_LAYER_DESIGN.md` §3）：**选目标 → 生成子任务 → 记账 → 终止**。
 * 移动/挖掘/收集全部复用已验收的 L2：
 * <ul>
 *   <li>每个原木 = 一个 {@link MineTask}（`collectDrops=false`，D-070 参数）——自下而上；</li>
 *   <li>整棵树砍完 = **一次** {@link CollectDropsTask}（簇级 + 守恒校验），不再"每棵等 40 tick"；</li>
 *   <li>工具由动作层 `BlockBreakSession.switchToBestToolFor` 自动切换（Job 只保证背包里有斧）。</li>
 * </ul>
 *
 * <p>完成判据 = **产物入包**：整棵砍完 **且** 背包中 `ItemTags.LOGS` 增量 ≥ 该树原木数。
 * 做不到就如实报（`docs/JOB_LAYER_DESIGN.md` §6.2c 五条终止路径）：
 * <ul>
 *   <li>① 配额达成 → `DONE quota_met`；</li>
 *   <li>② 无可行候选（且一棵都没砍成）→ `FAILED no_reachable_candidate` + 理由集；</li>
 *   <li>③ 背包放不下 → `DONE inventory_full`（不空转）；</li>
 *   <li>④ 硬超时 → `FAILED goal_timeout`；</li>
 *   <li>⑤ 砍到一半该树作废 → 记入 `attempted` 跳过该树继续下一棵；配额未达且候选用尽 → `FAILED partial_quota` + 逐树失败清单。</li>
 * </ul>
 *
 * <p>**循环不变量（§6.2b）**：`attempted` 保证**不重复砍同一棵**——半成品树若被反复重选会死循环。
 */
public final class LumberJob implements Job {

    private enum Phase { SELECT, CHOP, COLLECT, DONE }

    public static final String NAME = "lumber";

    /** 单棵树的清障预算（`JOB_LAYER_DESIGN.md` §9-4：≤8 格/棵）。 */
    private static final int MAX_CLEAR_PER_TREE = LumberCandidateSource.MAX_CLEAR_PER_TREE;

    private final BotPlayer bot;
    private final GoalSpec spec;
    private final ScopeBuffer scope;
    private final LumberCandidateSource source;
    private final SelectionPolicy policy;
    private final int logsBefore;
    /** 已尝试过的树基座（成功或失败都算）——保证**不重复砍同一棵**（§6.2b 循环不变量）。 */
    private final java.util.Set<BlockPos> attempted = new java.util.HashSet<>();
    /** 逐树的失败清单（Job 级，用于 §6.2c⑤ 的如实上报）。 */
    private final java.util.List<String> attemptFailures = new ArrayList<>();

    private Phase phase = Phase.SELECT;
    private int ticks;
    private Tree tree;
    private List<BlockPos> queue = List.of();
    private int queueIndex;
    private int choppedLogs;
    /** 配额进度：已完成整棵的树数。 */
    private int treesDone;
    /** 报告用累计值（monotone）：已砍原木数 / 计划原木数。 */
    private int choppedTotal;
    private int plannedTotal;
    /** **本棵树**开始前的背包原木数（逐树完成判据的基线）。 */
    private int logsBeforeThisTree;
    private final List<String> failedLogs = new ArrayList<>();
    private MineTask miner;
    private MineTask clearTask;
    /**
     * **本棵树**已清障格数（预算闸门用）。
     *
     * <p>2026-09-10 修正（勘测员 06 §0.2，已只读复核）：原实现只有一个 job 级计数器，
     * 却拿去比 **per-tree** 限额 `MAX_CLEAR_PER_TREE=8` 且**换树不重置** ——
     * 单树测试永远暴露不了，一进多树循环（J2）累计 8 格后**后面每棵树都会 `clear_budget` 失败**。
     */
    private int clearedThisTree;
    /** 整个 Job 累计清障格数（仅用于报告，不参与闸门）。 */
    private int clearedTotal;
    private CollectDropsTask collector;
    private String terminalReason = "";
    private String failure = "";
    private boolean terminated;

    public LumberJob(BotPlayer bot, GoalSpec spec, ScopeBuffer scope,
                     LumberCandidateSource source, SelectionPolicy policy) {
        this.bot = bot;
        this.spec = spec;
        this.scope = scope;
        this.source = source;
        this.policy = policy;
        this.logsBefore = countLogs();
    }

    @Override
    public String jobName() {
        return NAME;
    }

    @Override
    public TaskTarget target() {
        if (miner != null) {
            return miner.target();
        }
        if (tree != null && phase == Phase.COLLECT) {
            return TaskTarget.block(tree.base());
        }
        if (tree != null && queueIndex < queue.size()) {
            return TaskTarget.block(queue.get(queueIndex));
        }
        return TaskTarget.block(spec.center());
    }

    @Override
    public String failureReason() {
        return failure;
    }

    @Override
    public String progressSummary() {
        return "trees " + treesDone + "/" + spec.quota()
                + " logs " + choppedTotal + "/" + plannedTotal
                + (clearedTotal > 0 ? " cleared=" + clearedTotal : "");
    }

    @Override
    public Task.Status tick() {
        if (terminated) {
            return Task.Status.DONE;
        }
        if (++ticks > spec.maxTicks()) {
            terminalReason = "goal_timeout";
            failure = terminalReason;
            return finish(Task.Status.FAILED);
        }
        return switch (phase) {
            case SELECT -> select();
            case CHOP -> chop();
            case COLLECT -> collectPhase();
            case DONE -> finish(Task.Status.DONE);
        };
    }

    // ==================== 阶段 ====================

    private Task.Status select() {
        CandidateSet raw = source.candidates(bot, spec);
        CandidateSet set = withoutAttempted(raw);
        Selection selection = policy.select(bot, spec, set);
        DecisionTrace.select(jobName(), policy.name(), set, selection);
        if (selection.picked() == null) {
            return shortfall(set);
        }
        Tree picked = source.treeAt(selection.picked().anchor());
        if (picked == null) {
            terminalReason = "candidate_lookup_failed";
            failure = terminalReason;
            return finish(Task.Status.FAILED);
        }
        tree = picked;
        clearedThisTree = 0;            // 预算按棵重置（D-080「≤8 格/棵」）
        queueIndex = 0;
        choppedLogs = 0;
        failedLogs.clear();
        logsBeforeThisTree = countLogs();   // 逐树基线（job 级 logsBefore 只用于总报告）
        queue = picked.logsBottomUp();
        plannedTotal += picked.logCount();
        scope.begin(picked.base(), 16, bot.getUUID());
        DecisionTrace.step(jobName(), "SELECT", picked.base().toShortString(),
                "logs=" + picked.logCount() + " height=" + picked.trunkHeight()
                        + " species=" + picked.species() + " canopy=" + picked.hasCanopy());
        phase = Phase.CHOP;
        return Task.Status.RUNNING;
    }

    private Task.Status chop() {
        if (queueIndex >= queue.size()) {
            phase = Phase.COLLECT;
            collector = new CollectDropsTask(bot, tree.base(), scope, List.of(), false);
            DecisionTrace.step(jobName(), "COLLECT", tree.base().toShortString(),
                    "chopped=" + choppedLogs + "/" + queue.size() + " failed=" + failedLogs.size()
                            + (clearedTotal > 0 ? " cleared=" + clearedTotal : ""));
            return Task.Status.RUNNING;
        }
        BlockPos log = queue.get(queueIndex);

        // 清障子任务优先推进（腾站位 / 打通视线；限次 = MAX_CLEAR_PER_TREE）
        if (clearTask != null) {
            Task.Status clearStatus = clearTask.tick();
            if (clearStatus == Task.Status.RUNNING) {
                return Task.Status.RUNNING;
            }
            clearTask = null;
            if (clearStatus == Task.Status.DONE) {
                clearedThisTree++;
                clearedTotal++;
            } else {
                failedLogs.add(log.toShortString() + ":clear_failed");
                queueIndex++;
            }
            return Task.Status.RUNNING;
        }

        if (miner == null) {
            // 没有现成可站站位 → **由 Job 显式清障**，而不是让规划器掉进"挖隧道/挖地站进去"
            if (!hasStandNow(log)) {
                BlockPos step = BlockerClearPlanner.nextClearStep(bot.serverLevel(), bot, log,
                        bot.getBlockReach(), MAX_CLEAR_PER_TREE - clearedThisTree,
                        WriteGrant.of(jobName(), WriteReason.LINE_OF_SIGHT));
                if (step == null) {
                    failedLogs.add(log.toShortString() + (clearedThisTree >= MAX_CLEAR_PER_TREE
                            ? ":clear_budget" : ":no_stand"));
                    queueIndex++;
                    return Task.Status.RUNNING;
                }
                DecisionTrace.step(jobName(), "CLEAR", step.toShortString(),
                        "为 " + log.toShortString() + " 腾站位/通视线 clear="
                                + (clearedThisTree + 1) + "/" + MAX_CLEAR_PER_TREE);
                clearTask = new MineTask(bot, step, scope,
                        MiningBudget.forTarget(bot, bot.serverLevel(), step, false), true,
                        WriteGrant.of(jobName(), WriteReason.LINE_OF_SIGHT));
                return Task.Status.RUNNING;
            }
            DecisionTrace.step(jobName(), "CUT", log.toShortString(),
                    "log " + (queueIndex + 1) + "/" + queue.size());
            // standableOnly=true：**禁止**规划器自己挖隧道或破坏进入（伐木不允许"往地里挖"）
            miner = new MineTask(bot, log, scope,
                    MiningBudget.forTarget(bot, bot.serverLevel(), log, false), true,
                    WriteGrant.of(jobName(), WriteReason.EXPECTED_TARGET));
            return Task.Status.RUNNING;
        }

        Task.Status status = miner.tick();
        if (status == Task.Status.RUNNING) {
            return Task.Status.RUNNING;
        }
        if (status == Task.Status.DONE) {
            choppedLogs++;
            miner = null;
            queueIndex++;
            return Task.Status.RUNNING;
        }
        // 运行期视线被挡（规划期看不见、执行期才暴露）→ 仍走同一套限次清障
        if ("LINE_OF_SIGHT_BLOCKED".equals(miner.failureReason())
                && clearedThisTree < MAX_CLEAR_PER_TREE) {
            BlockPos blocker = LineOfSightChecker.checkFromEye(bot.serverLevel(), bot.getEyePosition(), log)
                    .getFirstBlocker();
            if (blocker != null && BlockerClearPlanner.clearable(bot, bot.serverLevel(), blocker,
                    WriteGrant.of(jobName(), WriteReason.LINE_OF_SIGHT))) {
                DecisionTrace.step(jobName(), "CLEAR", blocker.toShortString(),
                        "blocking " + log.toShortString() + " clear=" + (clearedThisTree + 1)
                                + "/" + MAX_CLEAR_PER_TREE);
                clearTask = new MineTask(bot, blocker, scope,
                        MiningBudget.forTarget(bot, bot.serverLevel(), blocker, false), true,
                        WriteGrant.of(jobName(), WriteReason.LINE_OF_SIGHT));
                miner = null;   // 保留 queueIndex：清完重试同一根
                return Task.Status.RUNNING;
            }
        }
        failedLogs.add(log.toShortString() + ":" + miner.failureReason());
        miner = null;
        queueIndex++;
        return Task.Status.RUNNING;
    }

    /** 当前是否已有"现成可站"的站位能挖到该原木（复用规划器同一口径）。 */
    private boolean hasStandNow(BlockPos log) {
        return !StandingPointSelector.generateCandidates(bot.serverLevel(), log,
                bot.blockPosition(), bot.getBlockReach()).isEmpty();
    }

    private Task.Status collectPhase() {
        Task.Status status = collector.tick();
        if (status == Task.Status.RUNNING) {
            return Task.Status.RUNNING;
        }
        int gained = countLogs() - logsBeforeThisTree;
        boolean allChopped = failedLogs.isEmpty() && choppedLogs == queue.size() && !queue.isEmpty();
        boolean harvested = allChopped && gained >= tree.logCount();
        tried(tree.base(), harvested, gained);

        if (treesDone >= spec.quota()) {
            terminalReason = "quota_met";
            return finish(Task.Status.DONE);
        }
        if (!hasRoomForLogs()) {
            terminalReason = "inventory_full";
            BotLog.warn("[Job] lumber 背包放不下更多原木，提前结束：trees {}/{}",
                    treesDone, spec.quota());
            return finish(Task.Status.DONE);
        }
        // 循环：回到选树（attempted 保证不重复砍同一棵）
        DecisionTrace.step(jobName(), "NEXT", "trees " + treesDone + "/" + spec.quota(),
                "继续选下一棵；已尝试 " + attempted.size() + " 棵");
        phase = Phase.SELECT;
        return Task.Status.RUNNING;
    }

    /** 结算一棵树：成功计进度，失败进清单（§6.2c⑤）。 */
    private void tried(BlockPos base, boolean harvested, int gained) {
        attempted.add(base);
        choppedTotal += choppedLogs;
        if (harvested) {
            treesDone++;
            return;
        }
        String detail = base.toShortString() + ":"
                + (allChoppedNow() ? "product_not_collected" : "partial_tree")
                + " gained=" + gained + "/" + tree.logCount()
                + (failedLogs.isEmpty() ? "" : " failed=" + String.join(",", failedLogs));
        attemptFailures.add(detail);
        BotLog.warn("[Job] lumber 该树未完成 {}", detail);
    }

    private boolean allChoppedNow() {
        return failedLogs.isEmpty() && choppedLogs == queue.size() && !queue.isEmpty();
    }

    /** 配额未达成时的终态：有产出 → `partial_quota`，一棵没成 → `no_reachable_candidate`。 */
    private Task.Status shortfall(CandidateSet set) {
        if (!attemptFailures.isEmpty()) {
            BotLog.warn("[Job] lumber 未能完成的树: {}", String.join(" | ", attemptFailures));
        }
        terminalReason = treesDone > 0 ? "partial_quota" : "no_reachable_candidate";
        failure = terminalReason + (set.rejected().isEmpty() ? "" : " " + String.join(",", set.rejected()));
        return finish(Task.Status.FAILED);
    }

    /** 过滤掉已尝试过的树，并把过滤原因写进 rejected（§6.2a：拒绝必须带理由码）。 */
    private CandidateSet withoutAttempted(CandidateSet raw) {
        if (attempted.isEmpty()) {
            return raw;
        }
        List<Candidate> viable = new ArrayList<>();
        List<String> rejected = new ArrayList<>(raw.rejected());
        for (Candidate candidate : raw.viable()) {
            if (attempted.contains(candidate.anchor())) {
                rejected.add(candidate.anchor().toShortString() + ":already_attempted");
            } else {
                viable.add(candidate);
            }
        }
        return new CandidateSet(viable, rejected);
    }

    /** 背包是否还能装下原木（§6.2c③：放不下就别空转）。 */
    private boolean hasRoomForLogs() {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                return true;
            }
            if (stack.is(ItemTags.LOGS) && stack.getCount() < stack.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    private Task.Status finish(Task.Status status) {
        if (!terminated) {
            terminated = true;
            bot.controller().stopMovement();
            DecisionTrace.terminal(jobName(), status == Task.Status.DONE ? "DONE" : "FAILED",
                    terminalReason, progressSummary() + " inventoryDelta=" + (countLogs() - logsBefore)
                            + " " + com.dddgn.alice.action.WriteAudit.summary(),
                    ticks);
        }
        return status;
    }

    /** 背包中原木（`ItemTags.LOGS`）总数——完成判据的地面真相（原始设计 §4.2 标准 3）。 */
    private int countLogs() {
        int total = 0;
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.is(ItemTags.LOGS)) {
                total += stack.getCount();
            }
        }
        return total;
    }
}
