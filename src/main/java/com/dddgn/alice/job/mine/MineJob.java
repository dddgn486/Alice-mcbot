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
     * ⭐ `D-329` §2 S4：**分片扫描会话**（跨 tick 存续；`null` = 还没开扫）。
     *
     * <p>为什么挂在 Job 上而不是每次 `select()` 新建：大范围扫描要"边扫边记、跨 tick 累积"，
     * 而且**已扫过的格不重扫**（游标单调）——这正是 `S4`/`S5` 的分工：会话管"这一次扫到哪"，
     * 持久记忆（`S5`）管"以前扫过哪"。
     */
    private MineCandidateSource.ScanSession session;
    /**
     * ⭐ **簇消费队列**（用户 2026-09-20 定的簇口径）：选中点所在**几何相连簇**的成员，选中的排第一。
     * 每个成员仍要过身份复检 + 规划器；成员失败就 `attempted` 掉继续下一个 ⇒ **部分完成如实**。
     */
    private java.util.List<BlockPos> clusterQueue = new ArrayList<>();

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
    /**
     * ⭐ **被选中的目标序列**（`D-360` 真机实测统计）：顺序 = 尝试顺序。
     * ⚠️ 这是**决策行为**的账，与"世界被改了多少"（`TaskMetrics`/`WriteAudit`）**分开记** ——
     * 合在一起就分不出"是偏置"还是"被拒"。
     */
    private final List<BlockPos> attemptOrder = new ArrayList<>();

    /**
     * ⭐ **种类分配**（`D-361`，用户 2026-09-20）：「挖一组煤炭和一组铁，煤炭多了就不要了」。
     *
     * <p>空 ⇒ 惰性（单一总配额，行为逐字不变）。启用时：**先按种类过滤候选、再做簇/选择**
     * （簇保持纯几何 ⇒ 过滤必须在它之前），且**总数配额仍是硬上限**（不推迟判定）。
     */
    private final MineKindPlan kindPlan;
    /** 与 {@link #kindPlan} 的条目对齐的"已挖数量"（成功挖掉才 +1，与 `minedCount` 同一个时刻）。 */
    private final int[] minedByKind;
    /** 当前选中的目标属于哪条分配（`-1` = 无分配/不属于任何一条）；成功时按**选择时刻**的判定计数。 */
    private int currentKind = -1;

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
        this.kindPlan = MineKindPlan.resolve(bot.serverLevel(), spec.kindQuotas());
        this.minedByKind = new int[kindPlan.entries().size()];
        this.itemsBefore = countTargetItems();
        BotLog.info("[MineJob] productFilter={}（J-6：目标驱动，不再硬编码原版矿物）",
                productFilter.describe());
        if (kindPlan.active()) {
            BotLog.info("[MineJob] {}（用户 D-361：满足的种类不再选它；总配额 {} 仍是硬上限）",
                    kindPlan.describe(), spec.quota());
        }
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
            // **队列第③项（2026-09-17）**：新尝试上场时**也要**带上"上一轮尝试的失败"。
            // 原先这里传空串 ⇒ 一到重试，`tree[].children[].lastFailure` 就空了，
            // 决策层恰好在"最需要知道上次为什么失败"的时刻看不见它（M4b 只修好了 `miner == null` 那一支）。
            children.add(com.dddgn.alice.task.TaskNode.leaf("MineTask",
                    miner.target().describe(), phase.name(), ticks,
                    "cleared=" + miner.clearedBlocks(), lastAttemptFailure()));
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

    /** 上一轮尝试的失败事实（`pos:code`）；没有则空串。`TaskNode.lastFailure` 的唯一口径。 */
    private String lastAttemptFailure() {
        return attemptFailures.isEmpty()
                ? ""
                : attemptFailures.get(attemptFailures.size() - 1).describe();
    }

    /** **夹具只读**（队列第③项判据）：当前是否有**进行中**的尝试（= `subTasks()` 走的是活动分支）。 */
    public boolean hasActiveAttempt() {
        return miner != null;
    }

    /** **夹具只读**：已记录的失败尝试数（上一条 `pos:code` 见 `subTasks()` 的 failure 字段）。 */
    public int attemptFailureCount() {
        return attemptFailures.size();
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
                + (kindPlan.active() ? " · " + kindPlan.progress(minedByKind) : "")
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
        // ⭐ `D-329` §2 S4：**分片扫描**（会话跨 tick 存续，不随每次选目标重置）——
        // 旧版是"一 tick 把 (2r+1)³ 全扫完"（r=24 ⇒ 117,649 次考察全压在 tick 线程上）。
        if (session == null) {
            session = source.newSession(spec);
            // ⭐ `S5`（`D-329` §2）：把**记忆里的历史事实**如实报给决策层——"这片区域以前扫过没有、见过几个"。
            // ⚠️ 它**只当事实**：不参与"选哪一格"（记忆里只有计数、没有位置 ⇒ 结构上就挖不了），
            // 选点仍然只来自**这一次**扫描 + 身份复检（`D-348` 纪律）。
            long[] past = MineScanMemoryData.get(bot.getServer()).nearbySummary(
                    bot.serverLevel(), MineScanMemoryData.targetKey(source.target()),
                    spec.center().getX() >> 4, spec.center().getZ() >> 4,
                    Math.max(1, session.radius() >> 4));
            DecisionTrace.step(jobName(), "SCAN", spec.center().toShortString(),
                    "分片扫描开始 radius=" + session.radius() + " 体积=" + session.volume()
                            + " 单次上限=" + MineCandidateSource.CELL_BUDGET_PER_TICK
                            + " 总预算=" + MineCandidateSource.CELL_BUDGET_TOTAL
                            + " · 记忆：本区域已扫过 " + past[0] + " 区块 / 累计命中 " + past[1]
                            + " / 最新 tick " + past[2] + "（只当事实，不参与选点）");
        }
        // ⭐ **决策前复检**（`D-348` 同一条纪律）：候选**位置**是扫描那一刻的快照，但"还能不能做"
        // （可破坏性 / 授权面）是**当前**的世界事实 —— 旧版每次选择都重扫世界，所以它天然是当前的；
        // 分片之后必须显式补回这一步（实测 `mine_budget`：不补 ⇒ 预算耗尽后仍去试旧候选 ⇒ 归因退化）。
        CandidateSet set = withoutAttempted(session.revalidate(bot));
        // ⭐ **种类分配先过滤**（`D-361`）：满足的种类不再选它（`kind_quota_met`）、不在分配里的不要
        // （`kind_not_wanted`）。**必须在簇之前** —— `TargetClusters` 保持**纯几何**（否则"簇"会随
        // 配额状态漂移，判据就没法单独咬它了）。
        set = filterByKind(set);
        // ⭐ **簇消费**：上一轮选中的目标若还有"同簇且仍然可用"的成员没挖，**先挖它**（顺序建议）。
        // 判据：同一簇的目标**连续**被尝试（`mine_run_metrics`/夹具看尝试序列）。
        // ⚠️ 用户 2026-09-20 裁定：**配额是硬上限** —— 配额到了就在簇中间收口（"当前簇没挖完就放弃"）；
        //    但"成员不可用"**不许**静默跳过、也不许就此放弃整簇：逐个了结、每个都留下**它自己的**理由码，
        //    队列空了才回到全局选择（否则其余成员再也轮不到，第一轮实测就是因为这个把带内 4 格煤留下了）。
        Selection selection = null;
        while (selection == null && !clusterQueue.isEmpty()) {
            BlockPos next = clusterQueue.remove(0);
            for (com.dddgn.alice.job.Candidate candidate : set.viable()) {
                if (candidate.anchor().equals(next)) {
                    selection = new Selection(candidate, "cluster_member",
                            new java.util.ArrayList<>(set.rejected()));
                    break;
                }
            }
            if (selection == null) {
                DecisionTrace.step(jobName(), "SKIP", next.toShortString(),
                        "簇成员不可用（已了结，继续本簇下一个）：" + memberRefusal(set, next));
            }
        }
        if (selection == null) {
            selection = policy.select(bot, spec, set);
            if (selection.picked() != null) {
                clusterQueue = new ArrayList<>(TargetClusters.queueFor(
                        set.viable().stream().map(com.dddgn.alice.job.Candidate::anchor).toList(),
                        selection.picked().anchor()));
            }
        }
        if (selection.picked() == null) {
            if (!session.done() && !session.truncated()) {
                // S4：**还有没考察到的格** ⇒ 本 tick 继续扫。⚠️ 绝不许把"还没扫到"当成"这里没有"。
                MineCandidateSource.Progress progress = session.advance(bot);
                DecisionTrace.step(jobName(), "SCAN", spec.center().toShortString(),
                        "分片推进 visited=" + session.visited() + "/" + session.volume()
                                + " 本次=" + progress.visitedThisCall() + " 读=" + session.reads()
                                + " 未扫=" + session.unscanned());
                return Task.Status.RUNNING;
            }
            return shortfall(set);
        }
        DecisionTrace.select(jobName(), policy.name(), set, selection);
        current = selection.picked().anchor();
        ServerLevel level = bot.serverLevel();
        // 种类分配：按**选择时刻**的世界事实判定它属于哪一条（成功时按这个计数，避免"挖完了再读世界"读到空气）
        currentKind = kindPlan.active() ? kindPlan.indexOf(level.getBlockState(current)) : -1;
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
        attemptOrder.add(current);
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
            if (kindPlan.active() && currentKind >= 0 && currentKind < minedByKind.length) {
                minedByKind[currentKind]++;
                MineKindPlan.Entry entry = kindPlan.entries().get(currentKind);
                if (minedByKind[currentKind] == entry.count()) {
                    DecisionTrace.step(jobName(), "KIND", mined.toShortString(),
                            "种类已满足，不再选它：" + kindPlan.progress(minedByKind));
                }
            }
            if (firstMined == null) {
                firstMined = mined;
                // ⭐ `D-347`（运行账）：**"到达"由任务自己声明** —— 判据是"第一格目标方块**真的被挖掉**"
                // （不是"选出了候选"、更不是"开始跑了"）：到达与作业已开始**同时被证明**。
                // 幂等（一次运行只算一次），所以放在"第一个"这里最准确。
                com.dddgn.alice.bot.TaskMetrics.arrived(taskName());
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
        return attributeFailure(base, attemptFailures.stream().map(AttemptFailure::code).toList());
    }

    /**
     * **顶层码归因**（`D-329` §6 `M4`）：基础码 + 逐次尝试的失败码 ⇒ 顶层码。**纯函数** ⇒ 夹具喂合成码即可。
     *
     * <p>⭐ 为什么 `world_refused` 这一族必须存在（`D-359`，为真机地形实测补）：破坏被**世界侧**拦下
     * （FTB 认领 / 别的保护模组 / 事件层取消 / 冒险模式）时，每个目标都报 `BREAK_REFUSED`；
     * 若顶层只按"有没有挖到"算 ⇒ 退化成 `no_reachable_candidate` ⇒ 真机里看到的是**"这里没矿"**，
     * 而真相是**"世界不许我们改"**（`D-323` 附注一同一个坑的第二层：第一层是"没发生的破坏被记成成功"，
     * 第二层是"被拒绝的破坏被记成没矿"）。两者的处置完全不同：前者换目标，后者换地方/要权限。
     */
    public static String attributeFailure(String base, java.util.List<String> attemptCodes) {
        if (!"no_reachable_candidate".equals(base) || attemptCodes == null || attemptCodes.isEmpty()) {
            return base;
        }
        java.util.Set<String> codes = new java.util.LinkedHashSet<>(attemptCodes);
        if (codes.stream().allMatch(TOOL_CODES::contains)) {
            return "tool_missing";
        }
        if (codes.stream().allMatch(BUDGET_CODES::contains)) {
            return "write_budget_exhausted";
        }
        if (codes.size() == 1 && codes.contains("target_replaced")) {
            return "stale_target";
        }
        if (codes.stream().allMatch(WORLD_REFUSED_CODES::contains)) {
            return "world_refused";
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
    /**
     * **世界侧拒绝**（`D-359`）：破坏被保护层/事件层拦下。`D-323` 附注一那批真机日志里，
     * `BlockBreakSession` 报 `REFUSED`、`MineBlockRunner` 包装成 `BREAK_REFUSED`；
     * `world_unchanged`/`REFUSED` 也收进来，免得换个包装就漏归因。
     */
    private static final java.util.Set<String> WORLD_REFUSED_CODES = java.util.Set.of(
            "BREAK_REFUSED", "world_unchanged", "REFUSED");

    private static final java.util.Set<String> BUDGET_CODES = java.util.Set.of(
            "WRITE_BUDGET_EXHAUSTED", "write_budget_exhausted", "prod_budget_exhausted");

    /** 配额未达成：有产出 → `partial_quota`，一个没挖成 → `no_reachable_candidate`。 */
    private Task.Status shortfall(CandidateSet set) {
        if (!attemptFailures.isEmpty()) {
            BotLog.warn("[Job] mine 未能完成的目标: {}", attemptFailures.stream()
                    .map(AttemptFailure::describe).collect(java.util.stream.Collectors.joining(" | ")));
        }
        // 种类分配：把**没满足的种类**如实报出来（"总配额满了但某类还差"与"哪类都还没够"是两回事）
        if (kindPlan.active() && !kindPlan.allSatisfied(minedByKind)) {
            BotLog.warn("[Job] mine 种类未满足：{}（总 {} / 上限 {}）", kindPlan.shortfall(minedByKind),
                    minedCount, spec.quota());
        }
        // ⭐ `D-329` §2 S3：**`SEARCH_LIMIT ≠ UNREACHABLE`**。
        // 总预算把扫描截断了 ⇒ 我们**不知道**还有没有矿 ⇒ 只能说"搜索受限"。
        // 这里若报 `no_reachable_candidate`（"没有可达候选"）就是在把"没看见"说成"没有"，
        // 决策层会据此**换个地方挖**（错）；更严重的是任何"那就挖过去"的路径都等于拿搜索预算当写入授权（`D-076` 禁止）。
        boolean searchLimited = session != null && session.truncated();
        terminalReason = deriveTopLevelReason(shortfallReason(searchLimited, minedCount));
        if ("world_refused".equals(terminalReason)) {
            BotLog.warn("[Job] mine 世界侧拒绝（{}/{} 次破坏全被拦下：保护层/认领/事件取消）"
                            + " ⇒ 换站位或重试都没有意义；要么换地方、要么拿权限（`D-359`）",
                    attemptFailures.size(), attemptFailures.size());
        }
        if (searchLimited) {
            // 如实报"扫到哪了"，并明确**没有**对世界下"没矿"的结论
            BotLog.warn("[Job] mine 搜索受限（未扫完，不许当成没矿）：visited={}/{} 读={} 未扫={}",
                    session.visited(), session.volume(), session.reads(), session.unscanned());
        }
        // `S5`：失败时也把记忆事实带上（决策层要判断"是换个地方、还是扩大半径、还是等一等"）
        long[] past = MineScanMemoryData.get(bot.getServer()).nearbySummary(
                bot.serverLevel(), MineScanMemoryData.targetKey(source.target()),
                spec.center().getX() >> 4, spec.center().getZ() >> 4,
                Math.max(1, spec.radius() >> 4));
        String memoryNote = " memory[scannedChunks=" + past[0] + " hits=" + past[1]
                + " latestTick=" + past[2] + "]";
        failure = terminalReason + (set.rejected().isEmpty() ? "" : " " + String.join(",", set.rejected()))
                + memoryNote;
        return finish(Task.Status.FAILED);
    }

    /**
     * **配额没达成时的顶层码**（`D-329` §2 S3 的判据点，纯函数 ⇒ 夹具可逐条断言，不必造 24 万格的世界）。
     *
     * <p>三分法：
     * <ul>
     *   <li>**搜索被截断** ⇒ `search_incomplete`。‼️ 这里**绝不能**退化成 `no_reachable_candidate`：
     *       那是在把"我还没看完"说成"这里没有"，决策层会据此换地方挖（错），
     *       而且任何"那就挖过去"的读取都等于把**搜索预算**当成**写入授权**（`D-076` 明令禁止）。</li>
     *   <li>扫完了、但挖到了一些、配额没够 ⇒ `partial_quota`；</li>
     *   <li>扫完了、一个都没挖成 ⇒ `no_reachable_candidate`。</li>
     * </ul>
     */
    public static String shortfallReason(boolean searchLimited, int minedCount) {
        if (searchLimited) {
            return "search_incomplete";
        }
        return minedCount > 0 ? "partial_quota" : "no_reachable_candidate";
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

    /**
     * **按种类分配过滤候选**（`D-361`）：满足的种类不再选它、不在分配里的不要。
     *
     * <p>⚠️ 调用点**必须**在 {@link TargetClusters#queueFor} 与 `policy.select` **之前**
     * （`D-361` 口径：簇保持纯几何 —— 让"簇"随配额状态漂移，就没法单独咬簇判据了）。
     * 门禁 `rule_kind_filter_before_cluster` 咬这条顺序。
     */
    private CandidateSet filterByKind(CandidateSet raw) {
        if (!kindPlan.active()) {
            return raw;
        }
        List<Candidate> viable = new ArrayList<>();
        List<String> rejected = new ArrayList<>(raw.rejected());
        for (Candidate candidate : raw.viable()) {
            String refusal = kindPlan.refusal(
                    kindPlan.indexOf(bot.serverLevel().getBlockState(candidate.anchor())), minedByKind);
            if (refusal == null) {
                viable.add(candidate);
            } else {
                rejected.add(candidate.anchor().toShortString() + ":" + refusal);
            }
        }
        return new CandidateSet(viable, rejected);
    }

    /**
     * **簇成员为什么不可用**（如实归因，`D-361`）：优先用**已有的**理由码（扫描/复检/已尝试过），
     * 都不匹配才落到 `not_selectable`。绝不把"不可用"说成"没有"或静默丢掉这一格。
     */
    private String memberRefusal(CandidateSet set, BlockPos pos) {
        String shortForm = pos.toShortString() + ":";
        String policyForm = "block@" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ":";
        for (String entry : set.rejected()) {
            if (entry.startsWith(shortForm)) {
                return entry.substring(shortForm.length());
            }
            if (entry.startsWith(policyForm)) {
                return entry.substring(policyForm.length());
            }
        }
        return attempted.contains(pos) ? "already_attempted" : "not_selectable";
    }

    private Task.Status finish(Task.Status status) {
        if (!terminated) {
            terminated = true;
            bot.controller().stopMovement();
            DecisionTrace.terminal(jobName(), status == Task.Status.DONE ? "DONE" : "FAILED",
                    terminalReason, progressSummary() + " inventoryDelta=" + (countTargetItems() - itemsBefore)
                            + " " + com.dddgn.alice.action.WriteAudit.summary(),
                    ticks);
            // ⭐ `D-360`：手动实测的采集**收口在这一个地方** —— `MineJob` 的终态有四条路径
            // （配额达成 / 候选穷尽 / 背包满 / 超时），在这里打点才不会出现"某条路径静默无数据"。
            MineSurvey.reportTerminal(jobName(), spec.center(), attemptOrder, minedCount, spec.quota(),
                    ticks, terminalReason, attemptFailures.stream().map(AttemptFailure::code).toList(),
                    kindPlan.active() ? kindPlan.progress(minedByKind) : "");
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
