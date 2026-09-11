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
import com.dddgn.alice.task.mining.MiningProfile;
import com.dddgn.alice.task.PathRetryRunner;
import com.dddgn.alice.task.RestoreScopeTask;
import com.dddgn.alice.pathing.core.MovementType;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.task.Task;
import com.dddgn.alice.task.TaskTarget;
import com.dddgn.alice.task.mining.MiningBudget;
import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
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

    /**
     * 每棵树的阶段：选树 → 砍（含**攀爬兜底**）→ ① 就地扫尾 → ② 会话内拆除 → ③ 落地扫尾 → 下一棵。
     *
     * <p>后半段就是 `JOB_LAYER_DESIGN.md` §12.3 的生命周期（2026-09-11 定稿，D-107 附注）：
     * **建 → 爬 → 用 → ① 作业点就地收 → ② 仍在架上自上而下拆 → ③ 落地后收**。
     * ①/② 只在**本棵树真的爬了**时才做（没爬就没有"够不到"的问题，保持原有零打扰路径）。
     */
    private enum Phase { SELECT, CHOP, SWEEP_UP, RESTORE, COLLECT, DONE }

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
    /**
     * 本棵树累计"原地加高"格数（由 {@link MineTask#gainedSteps()} 汇报——D-111 切片 A 起加高在 L2）。
     *
     * <p>用途：决定本棵树结束时是否要 ① 就地扫尾（D-107 附注：爬过高才有"够不到"的问题）。
     */
    private int gainedThisTree;
    /** 整 Job 加高过的树数与总格数（报告用）。 */
    private int gainedTrees;
    private int gainedBlocksTotal;
    /** 每棵树的加高预算（交给 L2 的能力信封；用户 2026-09-11 裁定 3 次/棵）。 */
    private static final int MAX_GAIN_PER_TREE = 3;
    /** 目标原木的能力信封：只用现成可站站位 + 允许原地加高（D-111 切片 A）。 */
    private static final com.dddgn.alice.task.mining.MiningProfile TARGET_PROFILE =
            com.dddgn.alice.task.mining.MiningProfile.STANDABLE_ONLY.withGain(MAX_GAIN_PER_TREE);
    /** ② 会话内拆除（仍在架上时自上而下拆我方 TEMP 放置）。 */
    private RestoreScopeTask restore;
    private boolean restoredThisTree;
    /** 任务结束时仍未拆除的我方临时放置（如实报告，不静默）。 */
    private int scaffoldLeft;
    /**
     * ① 就地扫尾的能力信封（D-116）：允许原地加高最多 8 格（一棵树从脚手架上到树冠的余量），
     * 方块预算沿用默认 12。③ 落地扫尾**不给**加高——它在 ② 拆除之后，产生的放置没人收（会留残）。
     */
    private static final MiningProfile COLLECT_GAIN_PROFILE =
            MiningProfile.STANDABLE_ONLY.withGain(8);
    /** ① 就地扫尾的 tick 预算（best-effort）。 */
    private static final int SWEEP_UP_BUDGET_TICKS = 200;
    private boolean sweptUpThisTree;
    private boolean scaffoLeftReported;
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
    public String terminalReason() {
        return terminalReason;
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
        // 前置检查（§6.2c③）：背包放不下原木时**直接收工**，不要先砍一棵再发现装不下
        if (!hasRoomForLogs(bot)) {
            terminalReason = "inventory_full";
            BotLog.warn("[Job] lumber 背包放不下任何原木，直接结束（未动世界）");
            return finish(Task.Status.DONE);
        }
        return switch (phase) {
            case SELECT -> select();
            case CHOP -> chop();
            case SWEEP_UP -> sweepUp();
            case RESTORE -> restorePhase();
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
        gainedThisTree = 0;
        sweptUpThisTree = false;
        restoredThisTree = false;
        scaffoLeftReported = false;
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
            return advanceAfterChop();
        }
        BlockPos log = queue.get(queueIndex);

        // **身份复检（§6.2c⑤，安全修复）**：`queue` 是**决策时刻的位置快照**，执行期世界可能已变。
        // 若不复查就挖，bot 会去挖玩家放在那一格的**别的方块**（箱子/矿石/机器）——那是拿别人的东西；
        // `MineTask` 只拦"保护区/不可破坏/流体"，不拦"这还是不是原木"。
        // 复查失败即**放弃本树**（计划已失效）并如实记账，交给结算走 partial 路径。
        if (!isStillLog(bot.serverLevel(), log)) {
            failedLogs.add(log.toShortString() + ":log_replaced");
            DecisionTrace.step(jobName(), "SKIP", log.toShortString(),
                    "该格已不是原木（决策后被改动）→ 放弃本树");
            queueIndex = queue.size();
            return Task.Status.RUNNING;
        }

        if (miner == null) {
            DecisionTrace.step(jobName(), "CUT", log.toShortString(),
                    "log " + (queueIndex + 1) + "/" + queue.size()
                            + " clearLeft=" + (MAX_CLEAR_PER_TREE - clearedThisTree));
            // **D-115：清障能力已下沉 L2** —— Job 只声明这份信封（每棵树最多清 8 格、够不到时
            // 可原地加高 3 格、用完即拆），不再自己实现"腾站位/通视线"两套清障逻辑。
            MiningProfile profile = TARGET_PROFILE.withClear(MAX_CLEAR_PER_TREE - clearedThisTree);
            miner = new MineTask(bot, log, scope,
                    MiningBudget.forTarget(bot, bot.serverLevel(), log, false), profile,
                    WriteGrant.of(jobName(), WriteReason.EXPECTED_TARGET));
            return Task.Status.RUNNING;
        }

        Task.Status status = miner.tick();
        if (status == Task.Status.RUNNING) {
            return Task.Status.RUNNING;
        }
        if (status == Task.Status.DONE) {
            choppedLogs++;
            recordClear(miner);
            recordGain(miner);
            miner = null;
            queueIndex++;
            return Task.Status.RUNNING;
        }
        // 清障（D-115）与加高（D-111）都已在 L2 内按信封尝试过；走到这里说明用尽/不可行 → 如实记账
        String reason = String.valueOf(miner.failureReason());
        recordClear(miner);
        recordGain(miner);
        failedLogs.add(log.toShortString() + ":" + reason);
        miner = null;
        queueIndex++;
        return Task.Status.RUNNING;
    }

    /** 累计本棵树的清障格数（L2 汇报，D-115；Job 只做逐树记账与报告）。 */
    private void recordClear(MineTask finished) {
        int cleared = finished == null ? 0 : finished.clearedBlocks();
        if (cleared <= 0) {
            return;
        }
        clearedThisTree += cleared;
        clearedTotal += cleared;
    }

    /** 累计本棵树的加高格数（L2 汇报；同时记账"加高过的树"）。 */
    private void recordGain(MineTask finished) {
        int steps = finished == null ? 0 : finished.gainedSteps();
        if (steps <= 0) {
            return;
        }
        if (gainedThisTree == 0) {
            gainedTrees++;
        }
        gainedThisTree += steps;
        gainedBlocksTotal += steps;
    }

    /**
     * 砍完一棵后的推进（J7 Step 2 + D-107 附注的定稿生命周期）。
     *
     * <pre>
     * CHOP → ① SWEEP_UP（仅当本棵树爬过：作业点就地收）→ ② RESTORE（仅当有我方 TEMP 放置：
     *        仍在架上自上而下拆）→ ③ COLLECT（落地后收）→ 下一棵
     * </pre>
     */
    private Task.Status advanceAfterChop() {
        if (gainedThisTree > 0 && !sweptUpThisTree) {
            phase = Phase.SWEEP_UP;
            return Task.Status.RUNNING;
        }
        if (!restoredThisTree && pendingTemp() > 0) {
            phase = Phase.RESTORE;
            return Task.Status.RUNNING;
        }
        phase = Phase.COLLECT;
        collector = new CollectDropsTask(bot, tree.base(), scope, List.of(), false);
        DecisionTrace.step(jobName(), "COLLECT", tree.base().toShortString(),
                "chopped=" + choppedLogs + "/" + queue.size() + " failed=" + failedLogs.size()
                        + (clearedTotal > 0 ? " cleared=" + clearedTotal : "")
                        + (gainedThisTree > 0 ? " gained=" + gainedThisTree : ""));
        return Task.Status.RUNNING;
    }

    /** 本 Job 作用域内仍未拆除的我方临时放置数（建拆同权的账）。 */
    private int pendingTemp() {
        String scopeId = com.dddgn.alice.ledger.WorldModLedger
                .currentScope(bot.getServer(), bot.getUUID());
        return scopeId == null ? 0
                : com.dddgn.alice.ledger.WorldModLedger
                        .pendingTemporary(bot.getServer(), scopeId).size();
    }

    /** ① 就地扫尾：仍在架上时收"此刻够得到"的产物（D-107 附注）。 */
    private Task.Status sweepUp() {
        if (collector == null) {
            // D-116：① 就地扫尾允许"原地加高"——高树顶端的原木掉落物常常停在树冠里、正在头顶够不到；
            // 此时 bot 还在自己的脚手架上、一次性方块在手、树干就是放置面 ⇒ 搭 1~N 格上去拿最省。
            // 这些放置落在同一作用域里，紧随其后的 ② 建拆同权会一并收回。
            collector = new CollectDropsTask(bot, bot.blockPosition(), scope, List.of(), false,
                    SWEEP_UP_BUDGET_TICKS, COLLECT_GAIN_PROFILE);
            BotLog.info("[Job] lumber sweep_up_start foot={} live_drops={}（仍在架上）",
                    MovementHelper.footCell(bot.serverLevel(), bot).toShortString(),
                    scope.liveDrops().size());
            ticks = 0;
            return Task.Status.RUNNING;
        }
        if (++ticks > SWEEP_UP_BUDGET_TICKS + 40) {
            collector = null;
            sweptUpThisTree = true;
            return advanceAfterChop();
        }
        Task.Status status = collector.tick();
        if (status == Task.Status.RUNNING) {
            return Task.Status.RUNNING;
        }
        BotLog.info("[Job] lumber sweep_up_end swept={} live_drops={}",
                collector.collected(), scope.liveDrops().size());
        collector = null;
        sweptUpThisTree = true;
        return advanceAfterChop();
    }

    /** ② 会话内拆除：**仍在架上**自上而下拆我方 TEMP 放置（§12.3；复用 RestoreScopeTask）。 */
    private Task.Status restorePhase() {
        if (restore == null) {
            String scopeId = com.dddgn.alice.ledger.WorldModLedger
                    .currentScope(bot.getServer(), bot.getUUID());
            BotLog.info("[Job] lumber restore_start foot={} pending={}（仍在架上拆除）",
                    MovementHelper.footCell(bot.serverLevel(), bot).toShortString(), pendingTemp());
            restore = new RestoreScopeTask(bot, scope, scopeId);
            return Task.Status.RUNNING;
        }
        Task.Status status = restore.tick();
        if (status == Task.Status.RUNNING) {
            return Task.Status.RUNNING;
        }
        int left = pendingTemp();
        if (left > 0) {
            scaffoldLeft = left;
            BotLog.warn("[Job] lumber scaffold_left pending={}（建拆同权未闭合，如实报告）", left);
        }
        restore = null;
        restoredThisTree = true;
        return advanceAfterChop();
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
        if (!hasRoomForLogs(bot)) {
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

    /** 该格现在仍是原木吗（`BlockTags.LOGS`）——执行期身份复检用（§6.2c⑤）。 */
    public static boolean isStillLog(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(net.minecraft.tags.BlockTags.LOGS);
    }

    /** 背包是否还能装下原木（§6.2c③：放不下就别开工）。 */
    public static boolean hasRoomForLogs(ServerPlayer player) {
        var inventory = player.getInventory();
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
            // J7 Step 2：攀爬与"建拆同权"的账一起进终态（爬了几次、花了几块、还剩没拆的）
            if (scaffoldLeft > 0 && terminalReason != null && !terminalReason.contains("scaffold")) {
                terminalReason = terminalReason + "+scaffold_left(" + scaffoldLeft + ")";
            }
            DecisionTrace.terminal(jobName(), status == Task.Status.DONE ? "DONE" : "FAILED",
                    terminalReason, progressSummary() + " inventoryDelta=" + (countLogs() - logsBefore)
                            + " gainedTrees=" + gainedTrees + " gainedBlocks=" + gainedBlocksTotal
                            + " scaffoldLeft=" + scaffoldLeft
                            + " " + com.dddgn.alice.action.WriteAudit.summary(),
                    ticks);
            BotLog.info("[Job] lumber SUMMARY gainedTrees={} gainedBlocks={} scaffoldLeft={}",
                    gainedTrees, gainedBlocksTotal, scaffoldLeft);
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
