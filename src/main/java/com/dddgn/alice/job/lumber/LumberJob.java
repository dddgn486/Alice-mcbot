package com.dddgn.alice.job.lumber;

import com.dddgn.alice.bot.BotPlayer;
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
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 伐木 Job（L3 第一消费者，切片 J1：**只砍一棵、不循环**）。
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
 * 做不到就如实报：`partial_tree` / `product_not_collected` / `goal_timeout` / `no_reachable_candidate`。
 */
public final class LumberJob implements Job {

    private enum Phase { SELECT, CHOP, COLLECT, DONE }

    public static final String NAME = "lumber";

    /** 单棵树的清障预算（`JOB_LAYER_DESIGN.md` §9-4：≤8 格/棵）。 */
    private static final int MAX_CLEAR_PER_TREE = 8;

    private final BotPlayer bot;
    private final GoalSpec spec;
    private final ScopeBuffer scope;
    private final LumberCandidateSource source;
    private final SelectionPolicy policy;
    private final int logsBefore;

    private Phase phase = Phase.SELECT;
    private int ticks;
    private Tree tree;
    private List<BlockPos> queue = List.of();
    private int queueIndex;
    private int choppedLogs;
    private final List<String> failedLogs = new ArrayList<>();
    private MineTask miner;
    private MineTask clearTask;
    private int clearedBlocks;
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
        int total = queue.isEmpty() ? 0 : queue.size();
        return "logs " + choppedLogs + "/" + total + " trees " + (terminated && failure.isEmpty() ? 1 : 0)
                + "/" + spec.quota() + (clearedBlocks > 0 ? " cleared=" + clearedBlocks : "");
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
        CandidateSet set = source.candidates(bot, spec);
        Selection selection = policy.select(bot, spec, set);
        DecisionTrace.select(jobName(), policy.name(), set, selection);
        if (selection.picked() == null) {
            terminalReason = "no_reachable_candidate";
            failure = terminalReason;
            return finish(Task.Status.FAILED);
        }
        Tree picked = source.treeAt(selection.picked().anchor());
        if (picked == null) {
            terminalReason = "candidate_lookup_failed";
            failure = terminalReason;
            return finish(Task.Status.FAILED);
        }
        tree = picked;
        queue = picked.logsBottomUp();
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
                    "chopped=" + choppedLogs + "/" + queue.size() + " failed=" + failedLogs.size());
            return Task.Status.RUNNING;
        }
        BlockPos log = queue.get(queueIndex);
        // 清障子任务优先推进（兜底路径：最底那根只能从侧面挖时，树冠可能挡住视线的场景）
        if (clearTask != null) {
            Task.Status clearStatus = clearTask.tick();
            if (clearStatus == Task.Status.RUNNING) {
                return Task.Status.RUNNING;
            }
            clearTask = null;
            if (clearStatus == Task.Status.DONE) {
                clearedBlocks++;
            } else {
                failedLogs.add(log.toShortString() + ":clear_failed");
                miner = null;
                queueIndex++;
            }
            return Task.Status.RUNNING;
        }
        if (miner == null) {
            DecisionTrace.step(jobName(), "CUT", log.toShortString(),
                    "log " + (queueIndex + 1) + "/" + queue.size());
            miner = new MineTask(bot, log, scope,
                    MiningBudget.forTarget(bot, bot.serverLevel(), log, false));
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
        if ("LINE_OF_SIGHT_BLOCKED".equals(miner.failureReason()) && clearedBlocks < MAX_CLEAR_PER_TREE) {
            BlockPos blocker = LineOfSightChecker.checkFromEye(bot.serverLevel(), bot.getEyePosition(), log)
                    .getFirstBlocker();
            if (blocker != null && SoftBlockPolicy.isClearable(bot, bot.serverLevel(), blocker, log)) {
                DecisionTrace.step(jobName(), "CLEAR", blocker.toShortString(),
                        "blocking " + log.toShortString() + " clear=" + (clearedBlocks + 1)
                                + "/" + MAX_CLEAR_PER_TREE);
                clearTask = new MineTask(bot, blocker, scope,
                        MiningBudget.forTarget(bot, bot.serverLevel(), blocker, false));
                miner = null;   // 保留 queueIndex：清完重试同一根
                return Task.Status.RUNNING;
            }
        }
        failedLogs.add(log.toShortString() + ":" + miner.failureReason());
        miner = null;
        queueIndex++;
        return Task.Status.RUNNING;
    }

    private Task.Status collectPhase() {
        Task.Status status = collector.tick();
        if (status == Task.Status.RUNNING) {
            return Task.Status.RUNNING;
        }
        phase = Phase.DONE;
        int gained = countLogs() - logsBefore;
        boolean allChopped = failedLogs.isEmpty() && choppedLogs == queue.size() && !queue.isEmpty();
        if (allChopped && gained >= tree.logCount()) {
            terminalReason = "quota_met";
            return finish(Task.Status.DONE);
        }
        terminalReason = allChopped ? "product_not_collected" : "partial_tree";
        if (!failedLogs.isEmpty()) {
            BotLog.warn("[Job] lumber 未砍完的原木: {}", String.join(",", failedLogs));
        }
        failure = terminalReason;
        return finish(Task.Status.FAILED);
    }

    private Task.Status finish(Task.Status status) {
        if (!terminated) {
            terminated = true;
            bot.controller().stopMovement();
            DecisionTrace.terminal(jobName(), status == Task.Status.DONE ? "DONE" : "FAILED",
                    terminalReason, progressSummary() + " inventoryDelta=" + (countLogs() - logsBefore),
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
