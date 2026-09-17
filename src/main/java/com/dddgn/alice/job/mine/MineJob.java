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

    /**
     * 稳定任务标识（J-3/D-265）：Job 一律用自己声明的 `NAME`（不用实现类名）——
     * 换实现类/换版本时，终态记录与决策提示里的 `kind` 不漂（客户端 2026-09-17 实测暴露：
     * `task_execution_terminal kind=LumberJob` 就是"没覆写"的直接后果）。
     */
    @Override
    public String taskName() {
        return NAME;
    }

    private enum Phase { SELECT, MINE, COLLECT, DONE }

    private final BotPlayer bot;
    private final GoalSpec spec;
    private final ScopeBuffer scope;
    private final MineCandidateSource source;
    private final SelectionPolicy policy;

    /**
     * **夹具专用**：把"身份复检"注入进来（`null` = 走生产路径的 {@link MineCandidateSource#matchesTarget}）。
     *
     * <p>为什么需要它：`stale_target`（决策后被改动）这条归因要求"每个候选的身份复检都失败"，
     * 而扫描与复检发生在**同一次 `select()` 调用**里 ⇒ 外部没有确定性手段在两者之间改世界
     * （夹具也进不来：电池里会话任务就是 Job 本身）。所以夹具只**替掉那一次判定**，
     * 让它稳定返回 false —— 产生的理由码与真实竞态**完全一样**（`target_replaced`），
     * 于是 `deriveTopLevelReason` 的映射被真的走到（M3b）。生产路径不受影响（本字段恒 null）。
     */
    private final java.util.function.Predicate<BlockPos> identityCheckOverride;
    private final int itemsBefore;
    /** 产物判定口径（J-6）：由 `GoalSpec.productTag` 决定，见 {@link MineProductFilter}。 */
    private final MineProductFilter productFilter;

    /** 已尝试过的目标格（挖成与挖不动都算）——保证不重复选同一格。 */
    private final Set<BlockPos> attempted = new HashSet<>();
    /**
     * **逐目标的尝试失败**（M3：存**结构化**的"位置 + 理由码"，不再存"拼好的字符串"）。
     *
     * <p>为什么：`LumberJob.deriveTopLevelReason` 用 `f.contains("no_suitable_tool")` 在**拼接串**上做子串
     * 匹配 —— 那既会把位置串误伤，也禁不起词表演化。M4 把"失败事实"变成字段之后，归因应当**逐码精确比较**。
     */
    private final List<AttemptFailure> attemptFailures = new ArrayList<>();

    /** 一次尝试失败：`pos` 是目标格，`code` 是失败理由码（取自既有词表，见 `toolRefusal` / `MineTask.failureReason`）。 */
    private record AttemptFailure(BlockPos pos, String code) {
        String describe() {
            return pos.toShortString() + ":" + code;
        }
    }

    private Phase phase = Phase.SELECT;
    private int ticks;
    private int minedCount;
    private BlockPos current;
    private BlockPos firstMined;
    private MineTask miner;
    private CollectDropsTask collector;
    private boolean scopeStarted;
    private String terminalReason = "";

    /**
     * **刚结束的子任务节点**（M4b；快照用，含它自己的 `lastFailure`）。
     *
     * <p>为什么必须有它：`mine()` 结束时会立刻把 `miner` 置空（避免用过期状态），
     * 于是失败的子节点在任务树里**整个消失**，`tree[].lastFailure` 永远是空的 ——
     * 决策层只能看到"挖矿没挖到"，看不到"哪个子阶段、以什么理由失败"。
     */
    private com.dddgn.alice.task.TaskNode finishedMinerNode;
    private String failure = "";
    private boolean terminated;

    public MineJob(BotPlayer bot, GoalSpec spec, ScopeBuffer scope,
                   MineCandidateSource source, SelectionPolicy policy) {
        this(bot, spec, scope, source, policy, null);
    }

    /** **夹具专用构造**（M3b）：`identityCheck` 恒 false ⇒ 每个候选都被判成"决策后已被改动"。 */
    public MineJob(BotPlayer bot, GoalSpec spec, ScopeBuffer scope, MineCandidateSource source,
                   SelectionPolicy policy, java.util.function.Predicate<BlockPos> identityCheck) {
        this.identityCheckOverride = identityCheck;
        this.bot = bot;
        this.spec = spec;
        this.scope = scope;
        this.source = source;
        this.policy = policy;
        this.productFilter = MineProductFilter.forTag(spec.productTag());
        this.itemsBefore = countTargetItems();
        BotLog.info("[MineJob] productFilter={}（J-6：目标驱动，不再硬编码原版矿物）",
                productFilter.describe());
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
    public java.util.List<com.dddgn.alice.task.TaskNode> subTasks() {
        java.util.List<com.dddgn.alice.task.TaskNode> children = new java.util.ArrayList<>();
        if (miner != null) {
            children.add(com.dddgn.alice.task.TaskNode.leaf("MineTask",
                    miner.target().describe(), phase.name(), ticks,
                    "cleared=" + miner.clearedBlocks(), ""));
        } else if (finishedMinerNode != null) {
            // 子任务已结束：仍把**刚结束的那个子阶段**（含它的 lastFailure）摊在树里，
            // 否则"哪个子阶段失败"在快照里根本不存在（M4b 的原缺口）。
            children.add(finishedMinerNode);
        }
        if (collector != null) {
            children.add(com.dddgn.alice.task.TaskNode.leaf("CollectDropsTask",
                    collector.target().describe(), phase.name(), ticks, ""));
        }
        return children;
    }

    @Override
    public String terminalReason() {
        return terminalReason;
    }

    /** J-4：决策层要能读到"卡在哪一步、试过哪些格、产物判定口径是什么"。 */
    @Override
    public com.dddgn.alice.bot.TaskFailureReport failureReport() {
        return new com.dddgn.alice.bot.TaskFailureReport(
                failureReason(), phase.name(), progressSummary()
                + " terminal=" + terminalReason
                + " attempted=" + attempted.size()
                + " inventoryDelta=" + (countTargetItems() - itemsBefore)
                + " filter={" + productFilter.describe() + "}"
                + (attemptFailures.isEmpty() ? "" : " lastAttempt=" + attemptFailures.get(attemptFailures.size() - 1)),
                com.dddgn.alice.bot.RecoveryStage.NONE, java.util.List.of());
    }

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
        boolean stillTarget = identityCheckOverride == null
                ? source.matchesTarget(level, current)
                : identityCheckOverride.test(current);
        if (!stillTarget) {
            attempted.add(current);
            attemptFailures.add(new AttemptFailure(current, "target_replaced"));
            DecisionTrace.step(jobName(), "SKIP", current.toShortString(),
                    "该格已不是目标方块（决策后被改动）");
            current = null;
            return Task.Status.RUNNING;
        }
        DecisionTrace.step(jobName(), "MINE", current.toShortString(),
                "block=" + selection.picked().feature("block")
                        + " d=" + selection.picked().feature("d")
                        + " target " + (minedCount + 1) + "/" + spec.quota());
        // D-112 建拆同权：本 Job 是"会话所有者"，每个目标用完就把它自己放的临时方块拆掉
        miner = new MineTask(bot, current, scope,
                MiningBudget.forTarget(bot, level, current, true),
                com.dddgn.alice.task.mining.MiningProfile.TUNNEL_ALLOWED.withRestore(),
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
        // M4b：把子阶段自己的失败事实留下来（成功则清空）——必须在置空之前取。
        finishedMinerNode = com.dddgn.alice.task.TaskNode.finished("MineTask",
                miner.target().describe(), phase.name(), ticks,
                "cleared=" + miner.clearedBlocks(), miner, status);
        miner = null;
        current = null;
        attempted.add(mined);
        if (status == Task.Status.DONE) {
            minedCount++;
            if (firstMined == null) {
                firstMined = mined;
            }
        } else {
            attemptFailures.add(new AttemptFailure(mined,
                    reason == null || reason.isBlank() ? "mining_failed" : reason));
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

    /**
     * **把"总括码"归因成"专有码"**（M3 / `survey/08` §8.2 第 4 条）。
     *
     * <p>为什么需要：总括码会把真因盖掉 —— **缺镐**被报成 `no_reachable_candidate`（"没矿"），
     * 决策层据此选的下一步必然错（它应该去弄工具，而不是换个地方挖）。
     * `LumberJob` 早有这个先例（`tool_missing` / `climb_incomplete`），但它是用
     * `f.contains("no_suitable_tool")` 在**拼接串**上做子串匹配 ⇒ 这里改成**逐码精确比较**
     * （M4 把失败事实变成字段的直接收益）。
     *
     * <p>只对"**目标被尝试过、但一个都没成功**"这个总括码做归因；其它终态（超时 / 装不下 / 缺工具）
     * 本身就是明确原因，**不许被逐目标理由盖掉**（与 `LumberJob` 同一条纪律）。
     */
    private String deriveTopLevelReason(String base) {
        if (!"no_reachable_candidate".equals(base) || attemptFailures.isEmpty()) {
            return base;
        }
        java.util.Set<String> codes = attemptFailures.stream()
                .map(AttemptFailure::code)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (codes.stream().allMatch(TOOL_CODES::contains)) {
            return "tool_missing";
        }
        if (codes.stream().allMatch(BUDGET_CODES::contains)) {
            return "write_budget_exhausted";
        }
        if (codes.size() == 1 && codes.contains("target_replaced")) {
            return "stale_target";
        }
        return base;
    }

    /** **缺工具类**理由码：目标必须某种工具才掉落，而身上没有（`MineTask.toolRefusal`）。 */
    private static final java.util.Set<String> TOOL_CODES = java.util.Set.of(
            "no_suitable_tool", "tool_missing");

    /**
     * **写入预算耗尽类**理由码：这些码来自既有词表（`BreakAndEnterExecution` / `PlaceStepAndTraverseExecution` /
     * `DownwardExecution` / `PlaceTask` / 连锁挖掘），不是新造的。
     */
    private static final java.util.Set<String> BUDGET_CODES = java.util.Set.of(
            "WRITE_BUDGET_EXHAUSTED", "write_budget_exhausted", "prod_budget_exhausted");

    /** 配额未达成：有产出 → `partial_quota`，一个没挖成 → `no_reachable_candidate`。 */
    private Task.Status shortfall(CandidateSet set) {
        if (!attemptFailures.isEmpty()) {
            BotLog.warn("[Job] mine 未能完成的目标: {}", attemptFailures.stream()
                    .map(AttemptFailure::describe).collect(java.util.stream.Collectors.joining(" | ")));
        }
        terminalReason = deriveTopLevelReason(
                minedCount > 0 ? "partial_quota" : "no_reachable_candidate");
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

    /**
     * 背包中"目标产物"总数——完成判据的地面真相。
     *
     * <p>口径在 {@link MineProductFilter}（J-6）：指定了 `productTag` 就**只认它**（矿标签同时认原矿兄弟标签），
     * 没指定就按 `forge:ores/*` + `forge:raw_materials/*` 标签族 + 原版掉落兜底 ——
     * 不再是一份**只认原版**的硬编码清单（那会让装了模组之后配额永远不满足、任务只能超时）。
     */
    private int countTargetItems() {
        int total = 0;
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (productFilter.matches(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }
}
