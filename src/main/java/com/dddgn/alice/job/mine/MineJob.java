package com.dddgn.alice.job.mine;

import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.action.WriteReason;
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
import com.dddgn.alice.task.mining.MiningBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.tags.ItemTags;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 挖掘 Job（L3 第二个消费者，切片 J5）：**证明 L3 不是伐木专用**。
 *
 * <p>与 {@code LumberJob} 共用同一套骨架——{@link com.dddgn.alice.job.CandidateSource}
 * 产出 {@link CandidateSet}、{@link SelectionPolicy} 做选择、{@link DecisionTrace} 出可判读决策、
 * {@link GoalSpec} 承载配额与超时、§6.2c 同一组终止理由。
 * 差别只在领域：目标从"一棵树的原木队列"变成"一组目标方块"，子任务仍是已验收的 L2
 * （{@link MineTask} + {@link CollectDropsTask}）。
 *
 * <p>**循环不变量（同伐木）**：`attempted` 保证不重复选同一格——否则挖不动的目标会被反复重选而死循环。
 *
 * <p>**完成判据**：挖到 `quota` 个目标方块 **且** 产物入包（与伐木同口径）。
 *
 * <p>终止理由（§6.2c 同一套词表）：`quota_met` / `no_reachable_candidate` / `partial_quota` /
 * `inventory_full` / `goal_timeout` / `product_not_collected`。
 */
public final class MineJob implements Job {

    public static final String NAME = "mine";

    private enum Phase { SELECT, MINE, COLLECT, DONE }

    private final BotPlayer bot;
    private final GoalSpec spec;
    private final ScopeBuffer scope;
    private final MineCandidateSource source;
    private final SelectionPolicy policy;
    private final int itemsBefore;

    /** 已尝试过的目标格（挖成与挖不动都算）——保证不重复选同一格。 */
    private final Set<BlockPos> attempted = new HashSet<>();
    private final List<String> attemptFailures = new ArrayList<>();

    private Phase phase = Phase.SELECT;
    private int ticks;
    private int minedCount;
    private BlockPos current;
    private BlockPos firstMined;
    private MineTask miner;
    private CollectDropsTask collector;
    private boolean scopeStarted;
    private String terminalReason = "";
    private String failure = "";
    private boolean terminated;

    public MineJob(BotPlayer bot, GoalSpec spec, ScopeBuffer scope,
                   MineCandidateSource source, SelectionPolicy policy) {
        this.bot = bot;
        this.spec = spec;
        this.scope = scope;
        this.source = source;
        this.policy = policy;
        this.itemsBefore = countTargetItems();
    }

    @Override
    public String jobName() {
        return NAME;
    }

    @Override
    public TaskTarget target() {
        if (phase == Phase.MINE && current != null) {
            return TaskTarget.block(current);
        }
        return TaskTarget.block(spec.center());
    }

    @Override
    public String failureReason() {
        return failure;
    }

    @Override
    public String terminalReason() {
        return terminalReason;
    }

    @Override
    public String progressSummary() {
        return "mined " + minedCount + "/" + spec.quota()
                + (attemptFailures.isEmpty() ? "" : " failed=" + attemptFailures.size());
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
        // 作用域必须在开工前开启：掉落物登记依赖它（否则收集阶段找不到任何掉落物）
        if (!scopeStarted) {
            scopeStarted = true;
            scope.begin(spec.center(), spec.radius(), bot.getUUID());
        }
        if (!hasEmptySlot()) {
            terminalReason = "inventory_full";
            BotLog.warn("[Job] mine 背包没有空位，直接结束（未动世界）");
            return finish(Task.Status.DONE);
        }
        return switch (phase) {
            case SELECT -> select();
            case MINE -> mine();
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
        current = selection.picked().anchor();
        ServerLevel level = bot.serverLevel();
        // 身份复检（§6.2c⑤，与伐木同一条纪律）：`candidates` 是**决策时刻的扫描结果**，
        // 执行期世界可能已变——若该格已不是目标方块，挖它就是拿别人的东西。
        if (!source.matchesTarget(level, current)) {
            attempted.add(current);
            attemptFailures.add(current.toShortString() + ":target_replaced");
            DecisionTrace.step(jobName(), "SKIP", current.toShortString(),
                    "该格已不是目标方块（决策后被改动）");
            current = null;
            return Task.Status.RUNNING;
        }
        DecisionTrace.step(jobName(), "MINE", current.toShortString(),
                "block=" + selection.picked().feature("block")
                        + " d=" + selection.picked().feature("d")
                        + " target " + (minedCount + 1) + "/" + spec.quota());
        miner = new MineTask(bot, current, scope,
                MiningBudget.forTarget(bot, level, current, true),
                WriteGrant.of(jobName(), WriteReason.EXPECTED_TARGET));
        phase = Phase.MINE;
        return Task.Status.RUNNING;
    }

    private Task.Status mine() {
        Task.Status status = miner.tick();
        if (status == Task.Status.RUNNING) {
            return Task.Status.RUNNING;
        }
        BlockPos mined = current;
        String reason = miner.failureReason();   // **必须在置空之前取**——否则理由永远为空
        miner = null;
        current = null;
        attempted.add(mined);
        if (status == Task.Status.DONE) {
            minedCount++;
            if (firstMined == null) {
                firstMined = mined;
            }
        } else {
            attemptFailures.add(mined.toShortString() + ":"
                    + (reason == null || reason.isBlank() ? "mining_failed" : reason));
        }
        if (minedCount >= spec.quota()) {
            startCollect();
            phase = Phase.COLLECT;
            return Task.Status.RUNNING;
        }
        phase = Phase.SELECT;
        return Task.Status.RUNNING;
    }

    private void startCollect() {
        BlockPos origin = firstMined != null ? firstMined : spec.center();
        collector = new CollectDropsTask(bot, origin, scope, List.of(), false);
        DecisionTrace.step(jobName(), "COLLECT", origin.toShortString(),
                "mined=" + minedCount + "/" + spec.quota());
    }

    private Task.Status collectPhase() {
        Task.Status status = collector.tick();
        if (status == Task.Status.RUNNING) {
            return Task.Status.RUNNING;
        }
        int gained = countTargetItems() - itemsBefore;
        if (gained >= minedCount) {
            terminalReason = "quota_met";
            return finish(Task.Status.DONE);
        }
        terminalReason = "product_not_collected";
        failure = terminalReason;
        return finish(Task.Status.FAILED);
    }

    /** 配额未达成：有产出 → `partial_quota`，一个没挖成 → `no_reachable_candidate`。 */
    private Task.Status shortfall(CandidateSet set) {
        if (!attemptFailures.isEmpty()) {
            BotLog.warn("[Job] mine 未能完成的目标: {}", String.join(" | ", attemptFailures));
        }
        terminalReason = minedCount > 0 ? "partial_quota" : "no_reachable_candidate";
        failure = terminalReason + (set.rejected().isEmpty() ? "" : " " + String.join(",", set.rejected()));
        return finish(Task.Status.FAILED);
    }

    /** 过滤掉已尝试过的目标，并把过滤原因写进 rejected（§6.2a：拒绝必须带理由码）。 */
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

    private Task.Status finish(Task.Status status) {
        if (!terminated) {
            terminated = true;
            bot.controller().stopMovement();
            DecisionTrace.terminal(jobName(), status == Task.Status.DONE ? "DONE" : "FAILED",
                    terminalReason, progressSummary() + " inventoryDelta=" + (countTargetItems() - itemsBefore)
                            + " " + com.dddgn.alice.action.WriteAudit.summary(),
                    ticks);
        }
        return status;
    }

    /** 背包是否有空位（§6.2c③：放不下就别开工）。 */
    private boolean hasEmptySlot() {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 背包中"矿物类"物品总数——完成判据的地面真相（与伐木用 `ItemTags.LOGS` 同构）。 */
    private int countTargetItems() {
        int total = 0;
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.is(ItemTags.COAL_ORES) || stack.is(ItemTags.IRON_ORES)
                    || stack.is(ItemTags.COPPER_ORES) || stack.is(ItemTags.GOLD_ORES)
                    || stack.is(ItemTags.DIAMOND_ORES) || stack.is(ItemTags.EMERALD_ORES)
                    || stack.is(ItemTags.LAPIS_ORES) || stack.is(ItemTags.REDSTONE_ORES)
                    || stack.is(net.minecraft.world.item.Items.RAW_IRON)
                    || stack.is(net.minecraft.world.item.Items.RAW_COPPER)
                    || stack.is(net.minecraft.world.item.Items.RAW_GOLD)
                    || stack.is(net.minecraft.world.item.Items.COAL)
                    || stack.is(net.minecraft.world.item.Items.DIAMOND)
                    || stack.is(net.minecraft.world.item.Items.EMERALD)
                    || stack.is(net.minecraft.world.item.Items.LAPIS_LAZULI)
                    || stack.is(net.minecraft.world.item.Items.REDSTONE)) {
                total += stack.getCount();
            }
        }
        return total;
    }
}
