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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    /**
     * `P3`：本作业签发的**作业级收集授权**（开工签发、`finish()` 撤销）。
     *
     * <p>为什么不靠 TTL 收口：TTL 只是"撤销路径被漏掉"的兜底 —— 权限窗口应当**精确等于**作业时长。
     */
    private com.dddgn.alice.decision.CollectGrants.JobGrant areaGrant;

    /** `P3`：作业级收集授权的**兜底 TTL**（10 分钟；正常路径由 `finish()` 撤销）。 */
    private static final int AREA_GRANT_TTL_TICKS = 20 * 600;

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

    /** ⭐ `Y`（2026-09-22）：`search_incomplete` 冷却到哪个 tick（`-1` = 无冷却）。 */
    private long searchLimitedUntilTick = -1L;
    /** ⭐ `Y`：连续 `search_incomplete` 次数（**任何一次成功**清零）。 */
    private int consecutiveSearchLimited = 0;

    /** ⭐ `D-389`：沿脉传播触发次数 / 被插到队列最前的相邻目标总数（夹具断言 + 真机读数）。 */
    private int veinPropagations = 0;
    private int veinEnqueued = 0;

    /** 一次尝试失败：`pos` 是目标格，`code` 是失败理由码（取自既有词表，见 `toolRefusal` / `MineTask.failureReason`）。 */
    /**
     * 暂时性失败的上限（`P1`）：`search_incomplete` 重试到这么多次仍未成功 ⇒ 如实了结（防空转）。
     * 只认「本轮没评价完」这一类码；真实的不可达码不受影响。
     */
    private static final int MAX_TRANSIENT_RETRIES = 3;

    /**
     * ⭐ `Y`（2026-09-22 真机卡顿根因）：一次 `search_incomplete` 之后，本作业**静默**这么多 tick
     * 再起新目标（同时让`D-371` 的全覆盖扫描让路）。
     *
     * <p>病灶：`P1-b` 把「静默了结」改成「诚实重试」之后，作业变成**每 tick 换一个候选再撞一次**
     * 200 ms 的无望搜索（真机 30 s 内 33 次、平均 196 ms ⇒ 4-5 TPS + `Can't keep up 42 ticks behind`
     * + 追补刷新成「跳帧」）。⇒ 重试必须**跨 tick 摊销**，不许同 tick 连续撞。
     */
    private static final int SEARCH_LIMIT_COOLDOWN_TICKS = 40;

    /**
     * ⭐ `Y`：**连续**（其间没有任何成功）`search_incomplete` 到这个数 ⇒ 本作业如实收工
     * （终态理由沿用既有 `partial_quota`/`search_incomplete`，不新增词表），即「快速、便宜地把
     * 超预算的目标判成本轮做不到，然后走开」——而不是在 1500 个候选上无限撞墙。
     */
    private static final int MAX_CONSECUTIVE_SEARCH_LIMITED = 8;

    /** 「本轮还没评价完」≠「不可达」（`SEARCH_LIMIT ≠ UNREACHABLE`）。 */
    private static boolean transientFailure(String code) {
        return code != null && code.startsWith("search_incomplete");
    }

    // ==================== `PL-1` 切片 1：过期证明的重评（有界） ====================

    /** 同一格最多重评几次（有界：邻域反复变化也不许无限重试）。 */
    static final int MAX_STALE_PROOF_RETRIES = 3;

    /** 失败那一刻的**邻域证明**（见 {@link #neighbourhoodWitness}）；没有条目 = 不许重评。 */
    private final Map<BlockPos, String> proofWitness = new HashMap<>();

    /** 每格尝试了几次（失败时 +1）——重评上限就用它。 */
    private final Map<BlockPos, Integer> attemptCount = new HashMap<>();

    /** 读数：本作业**真的重评过**（选中过）多少次（夹具 / 门禁看它，避免"字段只写不读"）。 */
    private int staleProofRetries;

    /** ⭐ `PL-1`：本作业重评过期证明的次数（>0 = 这条能力真的被触发过，不是死代码）。 */
    public int staleProofRetries() {
        return staleProofRetries;
    }

    /** ⭐ `Y`：本 tick 是否处在「搜索被限流」的冷却里（纯函数，便于夹具红/绿对照）。 */
    public static boolean inSearchLimitCooldown(long gameTime, long cooldownUntilTick) {
        return gameTime < cooldownUntilTick;
    }

    /** ⭐ `Y`：连续 `search_incomplete` 是否已到「本作业该走开」的程度（纯函数）。 */
    public static boolean searchLimitedStorm(int consecutive) {
        return consecutive >= MAX_CONSECUTIVE_SEARCH_LIMITED;
    }

    /** ⭐ `Y`：冷却时长（tick）——夹具断言"不许是 0/1"（那等于没摊销）。 */
    public static int searchLimitCooldownTicks() {
        return SEARCH_LIMIT_COOLDOWN_TICKS;
    }

    /** ⭐ `D-389`：沿脉传播触发次数（夹具：必须 > 0，否则"顺序改了"这件事没被验证）。 */
    public int veinPropagations() {
        return veinPropagations;
    }

    /** ⭐ `D-389`：被插到簇队列最前的相邻目标总数。 */
    public int veinEnqueued() {
        return veinEnqueued;
    }

    /** ⭐ `D-389`：本作业 `search_incomplete`（昂贵搜索被限流）的累计次数 —— 沿脉走的判据读数。 */
    public int transientFailureCount() {
        int n = 0;
        for (AttemptFailure failure : attemptFailures) {
            if (transientFailure(failure.code())) {
                n++;
            }
        }
        return n;
    }

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
    /**
     * **终态闩锁**（`D-175`/`D-178`）：记住**首次**终态并原样回放，使"终态后再 tick"幂等。
     * ⚠️ 修前外层守卫写的是 `if (terminated) return Task.Status.DONE;` —— 守卫在、但**硬编码 DONE**
     * ⇒ 终态是 `FAILED` 时再 tick 返回 `DONE`（违反 `D-178`）。本类有 **4 条** `FAILED` 出口
     * （`:344/370/590/693`）⇒ 是**潜在同类隐患**（当前 CORE 步恰好都 DONE 才没暴露）。
     * 形状照唯一正解 `task/MineTask:87/328`（`D-409`：全仓同形状 6 处统一）。
     */
    private Task.Status terminalStatus;

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
        // ⚠️ `D-362` 的任务保护作用域**不在这里装**（`1.4r`，2026-09-26）：构造器早于
        // `BotSession.beginTask`，而后者会**按 botId 清空**这份作用域 ⇒ 生产侧"装上即被清"、
        // 整条作业保护为空（真机路径逐字取证见台账 `1.4r`）。安装点已移到**首 tick**
        // （见 `tickOnce()` 里的 `scopeStarted` 块）⇒ `beginTask` 的清空天然落在装之前。
        // 结构门禁：`tools/check-protection-install-point.py`（构造器体内出现 `begin*` ⇒ 红）。
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
        // **终态闩锁在外层**（`D-175`/`D-178`）：首次终态被记住后，再 tick **原样回放**。
        // ⚠️ 修前这里是 `if (terminated) return Task.Status.DONE;` —— 守卫在、但硬编码 `DONE`
        // ⇒ 终态为 `FAILED` 时再 tick 返回 `DONE`（违反 `D-178`）。形状照唯一正解 `MineTask:328`。
        if (terminalStatus != null) {
            return terminalStatus;
        }
        Task.Status status = tickOnce();
        if (status != Task.Status.RUNNING) {
            terminalStatus = status;
        }
        return status;
    }

    /** 原 `tick()` 主体 —— 终态闩锁已提到外层 `tick()`（见字段注释 / `D-409`）。 */
    private Task.Status tickOnce() {
        if (++ticks > spec.maxTicks()) {
            terminalReason = "goal_timeout";
            failure = terminalReason;
            return finish(Task.Status.FAILED);
        }
        // 作用域必须在开工前开启：掉落物登记依赖它（否则收集阶段找不到任何掉落物）
        if (!scopeStarted) {
            scopeStarted = true;
            scope.begin(spec.center(), spec.radius(), bot.getUUID());
            // ⭐ `D-362` + `1.4r`（2026-09-26）：把"**这些格是本任务的目标**"登记到唯一的世界写入闸门上
            // （`BlockInteraction`）—— 于是清障（`PATH_ACCESS`）**再也不会吃掉任务矿**：开路时规划器只能绕行，
            // 绕不过去就**如实失败**。真机实测的靶子：第一轮第 8 个目标为了站上 `479,68,104` 把那一格的煤
            // 当障碍挖了（它本身就是同簇候选）。
            // ⚠️ **为什么必须在首 tick、不能回构造器**：`BotSession.beginTask`（`BotManager:1998`）会按 botId
            // **清空**这份作用域，而它跑在 `create() → beginTask()` 之间 ⇒ 构造器装的必然被清（生产侧护栏一直是空的，
            // 而电池"直驱子任务"看不到 = 结构性盲区）。放这里 ⇒ 清空天然在装之前，顺序不可能再错。
            com.dddgn.alice.action.TaskTargetProtection.begin(bot, jobName(), this::protectedFromClearance);
            // ⭐ `P3`（用户 2026-09-22 裁定）：给本作业一条**「本作业声明范围内 + 只认本作业目标产物」**
            // 的收集授权 —— 作业自己挖出来的落物即使**没配上破坏事件**（连锁模组缓冲/延迟生成/窗口错过）
            // 也收得起来；而**范围内的玩家丢的东西**仍是 `FOREIGN`（被动闸门照旧拦）。
            // 期限 = 作业时长（`finish()` 里撤销）；这里给的 TTL 只是"撤销路径被漏掉"时的兜底。
            areaGrant = com.dddgn.alice.decision.CollectGrants.addJobScoped(
                    bot.getServer(), spec.center(), spec.radius(), productFilter.describe(),
                    productFilter::matches, "mine:" + jobName(), AREA_GRANT_TTL_TICKS);
        }
        if (!hasEmptySlot()) {
            terminalReason = "inventory_full";
            BotLog.warn("[Job] mine 背包没有空位，直接结束（未动世界）");
            return finish(Task.Status.DONE);
        }
        // ⭐ `Y`（2026-09-22）：`search_incomplete` 冷却期**刻意不工作** —— 既不选新目标，
        // 也不推 `D-371` 的全覆盖扫描（扫描是可跨 tick 续的）。理由是它把「每 tick 撞一次 200 ms
        // 无望搜索」变成「每 40 tick 撞一次」，同时把同一 tick 的 CPU 让给规划。
        if (phase == Phase.SELECT && inSearchLimitCooldown(bot.serverLevel().getGameTime(),
                searchLimitedUntilTick)) {
            return Task.Status.RUNNING;
        }
        // ⭐ `Y`：连续撞墙到上限 ⇒ 如实收工（不新增终态词表；挖到的部分照记）
        if (searchLimitedStorm(consecutiveSearchLimited)) {
            BotLog.warn("[Job] mine 连续 {} 次 search_incomplete（其间无任何成功）⇒ 如实收工："
                            + "本区域内目标超出单次搜索预算（`SEARCH_LIMIT ≠ UNREACHABLE`，"
                            + "下次可重跑）。progress=mined {}/{}",
                    consecutiveSearchLimited, minedCount, spec.quota());
            terminalReason = deriveTopLevelReason(shortfallReason(true, minedCount));
            return finish(Task.Status.FAILED);
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
        // ⭐ `D-371`（R1 覆盖缺口）：**每次选择至多推进一个分片，无论当前有没有可挖目标**。
        // 原实现把推进放在"`selection.picked() == null`（没得挖）"分支里 ⇒ 只要**第一个分片**里还有能挖的，
        // 候选集就**永久冻结在第一分片**（真机实测整轮只推进 1 次：`visited=8192/117649`，
        // 同一矿脉 y=76 / y=81 的 10 格**从未进入候选**）。分片是**有界**的
        // （每格预算 `MineCandidateSource.CELL_BUDGET_PER_TICK`，实测 13–20 ms），而 `select()` 不是每 tick
        // 都跑 ⇒ 代价可接受；分片扫完后 `done()` 为真 ⇒ 稳态回到零成本。
        if (!session.done() && !session.truncated()) {
            MineCandidateSource.Progress progress = session.advance(bot);
            DecisionTrace.step(jobName(), "SCAN", spec.center().toShortString(),
                    "分片推进 visited=" + session.visited() + "/" + session.volume()
                            + " 本次=" + progress.visitedThisCall() + " 读=" + session.reads()
                            + " 未扫=" + session.unscanned()
                            + "（`D-371`：**不再**只在「没得挖」时才扫）");
        }
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
                // S4：**还有没考察到的格** ⇒ 本 tick 继续（推进已移到本方法开头：每次选择都推一格）。
                // ⚠️ 绝不许把"还没扫到"当成"这里没有"。
                return Task.Status.RUNNING;
            }
            return shortfall(set);
        }
        DecisionTrace.select(jobName(), policy.name(), set, selection);
        current = selection.picked().anchor();
        ServerLevel level = bot.serverLevel();
        if (attempted.contains(current)) {
            // ⭐ `PL-1` 切片 1：这一格**之前已经被了结**，现在又被选中 ⇒ 它的"无解"证明过期了。
            // 读数 + 决策痕迹都留痕（否则"重评"这件事在日志里看不见，判据没法咬它）。
            staleProofRetries++;
            DecisionTrace.step(jobName(), "RETRY", current.toShortString(),
                    "之前的「无解」证明已过期（26 邻域变了，`PL-1`）⇒ 重评"
                            + "（第 " + attemptCount.getOrDefault(current, 1) + "/"
                            + MAX_STALE_PROOF_RETRIES + " 次尝试）");
            BotLog.info("[MineJob] stale_proof_retry target={} attempts={}/{} retries={}",
                    current.toShortString(), attemptCount.getOrDefault(current, 1),
                    MAX_STALE_PROOF_RETRIES, staleProofRetries);
        }
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
        boolean transientFailure = transientFailure(reason);
        if (status == Task.Status.DONE) {
            attempted.add(mined);
            minedCount++;
            // ⭐ `Y`：有成功 ⇒ "连续撞墙"链断掉（否则本作业会在几个难目标上被判「该走开」）
            consecutiveSearchLimited = 0;
            // ⭐⭐ `D-389`（用户 2026-09-22 裁定）：**沿脉传播** —— 把"刚挖掉那格的 26 邻域里仍然是目标
            // 的格"插到簇队列**最前**。理由（用户原话）：*"矿簇每个子矿一定是六面或者对角相连……
            // 理论上除了水下/岩浆旁这些本来就不能挖的情况，都是能够完成挖掘的"*。
            // 病灶：原来消费**列表序**，下一个成员可能离 bot 6+ 格（真机 `target=48,63,140 startFoot=45,68,137`
            // = 6.6 格远）⇒ 每个成员付一次**昂贵** approach 搜索 ⇒ 30 s 内 33 次 × 196 ms ⇒ 卡顿 + 挖不完。
            // 沿脉走之后下一个目标 ≈1 格远 ⇒ `D-365` 就地挖或 1 格隧道 ⇒ 搜索规模 O(1)。
            enqueueVeinNeighbours(bot.serverLevel(), mined);
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
            String code = reason == null || reason.isBlank() ? "mining_failed" : reason;
            attemptFailures.add(new AttemptFailure(mined, code));
            // ⭐ `PL-1` 切片 1：**记下"这一刻的邻域证明"** —— 只有它过期了才允许重评这一格。
            // 硬拒绝（保护区 / 不可破坏 / 流体风险）**不记**：换邻域也改变不了"这格不许碰"（`D-323`）。
            attemptCount.merge(mined, 1, Integer::sum);
            if (!MineTask.isHardTargetRefusal(code)) {
                proofWitness.put(mined, neighbourhoodWitness(bot.serverLevel(), mined));
            } else {
                proofWitness.remove(mined);
            }
            // P1（D-374，2026-09-21）：**暂时性失败不许永久了结这一格**。
            // search_incomplete 的语义是「本轮搜索被限流，还没评价完」——写进 attempted 等于拿
            // **本 tick 的资源状况**当**世界事实**，下 tick 明明能挖却永远轮不到它（真机铁证：
            // 目标 436,82,229 从未被挖却已 already_attempted）。
            // 重试次数从已有的 attemptFailures **派生**（不新增字段），超过上限才如实了结 ⇒ 有界、不空转。
            long transientSoFar = 0;
            for (AttemptFailure failure : attemptFailures) {
                if (failure.pos().equals(mined) && transientFailure(failure.code())) {
                    transientSoFar++;
                }
            }
            if (!transientFailure || transientSoFar > MAX_TRANSIENT_RETRIES) {
                attempted.add(mined);
            } else {
                // ⭐ `Y`（2026-09-22）：**跨 tick 摊销 + 连续计数**
                // ① 冷却：下一次起新目标至少等 `SEARCH_LIMIT_COOLDOWN_TICKS` 个 tick
                //    （原来切目标就把"每目标重试数"清零 ⇒ 1500 个候选能无限每 tick 撞一次）；
                // ② 连续计数由**任何一次成功**清零（见上面 DONE 分支），到上限即如实收工。
                consecutiveSearchLimited++;
                searchLimitedUntilTick = bot.serverLevel().getGameTime() + SEARCH_LIMIT_COOLDOWN_TICKS;
                DecisionTrace.step(jobName(), "RETRY", mined.toShortString(),
                        "暂时性失败（" + code + "，" + transientSoFar + "/" + MAX_TRANSIENT_RETRIES
                                + "，本作业连续 " + consecutiveSearchLimited + "/"
                                + MAX_CONSECUTIVE_SEARCH_LIMITED + "）：**不**永久了结，"
                                + SEARCH_LIMIT_COOLDOWN_TICKS + " tick 后再选（跨 tick 摊销）");
            }
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
        collector = new CollectDropsTask(bot, origin, scope, List.of(), true);
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
            return com.dddgn.alice.action.WriteBudget.EXHAUSTED_CODE;
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
            "WRITE_BUDGET_EXHAUSTED",
            com.dddgn.alice.action.WriteBudget.EXHAUSTED_CODE,   // ← 唯一出处（Z3）
            "prod_budget_exhausted");

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
    /**
     * ⭐ `PL-1` 切片 1（2026-09-24 实测改道）：**"无解"是当时的证明，不是世界的不变量**。
     *
     * <p>病灶（`single:mine_vein_propagation` 无头实跑铁证）：夹具 30 格矿脉只挖到 **12**，
     * 剩下 18 格全是 `found_but_unminable`（尝试时刻邻格还是矿石 ⇒ 没有可站格 / 没有视线），
     * 而作业**继续挖下去改变了邻域**（西列被挖空）之后，把这 18 格**重新规划**一遍：
     * **17 格现在都有方案**（9 格 `CURRENT`：原地就能挖；8 格 `TUNNEL`），只剩 1 格是
     * `search_incomplete`（暂时性）。⇒ 卡点不是"没有便宜的执行器"（先前 `PL-1` 的假设），
     * 而是**过早的永久了结**：`already_attempted` 把"当时无解"当成"永远无解"。
     *
     * <p>与 `P1-b`（`SEARCH_LIMIT ≠ UNREACHABLE`）同族，但判据不同：那边是"本轮没评价完"，
     * 这边是"评价完了、结果是真的，**但前提已经过期**"。⇒ 记下**失败那一刻的邻域证明**
     * （26 邻域通行性位串），只有当前邻域与证明**不一致**时才允许重评，且**有上限**（防空转）。
     *
     * <p>边界（本切片有意不覆盖）：只看**26 邻域**——更远处的世界变化（例如 5 格外的通路被打开）
     * 不改判决；硬拒绝码（保护区 / 不可破坏 / 流体风险）**不记证明**（换邻域也不该重试，
     * 那是 `D-323` 的既有语义）。
     */
    private CandidateSet withoutAttempted(CandidateSet raw) {
        if (attempted.isEmpty()) {
            return raw;
        }
        ServerLevel level = bot.serverLevel();
        List<Candidate> viable = new ArrayList<>();
        List<String> rejected = new ArrayList<>(raw.rejected());
        for (Candidate candidate : raw.viable()) {
            BlockPos anchor = candidate.anchor();
            if (!attempted.contains(anchor)) {
                viable.add(candidate);
            } else if (proofExpired(level, anchor)) {
                viable.add(candidate);
            } else {
                rejected.add(anchor.toShortString() + ":already_attempted");
            }
        }
        return new CandidateSet(viable, rejected);
    }

    /**
     * 这一格的"无解"证明是否已过期（⇒ 允许重评一次）。
     *
     * @return true = 当时记录过证明、当前邻域**变了**、且尝试次数还没到上限
     */
    private boolean proofExpired(ServerLevel level, BlockPos pos) {
        String recorded = proofWitness.get(pos);
        if (recorded == null) {
            return false;   // 没有证明可过期（硬拒绝 / 已挖成 / 暂时性失败各自有自己的路）
        }
        if (attemptCount.getOrDefault(pos, 1) > MAX_STALE_PROOF_RETRIES) {
            return false;   // 有界：同一格最多重评这么多次
        }
        return !recorded.equals(neighbourhoodWitness(level, pos));
    }

    /**
     * **失败那一刻的邻域证明**：26 邻域逐格"能否穿过"的位串（`0` = 能穿过，`1` = 挡路）。
     *
     * <p>为什么是 26 格而不是 6 个面：夹具实跑里改变结论的那一格是**对角**邻格
     * （目标 `3701,101,2601` 的可站格在 `3700,100,2601`，相对偏移 `(-1,-1,0)`）——
     * 只看 6 面**抓不到**这个变化。
     *
     * <p>为什么用 `MovementHelper.canWalkThrough` 而不是 `isAir`：**与规划器同一份通过性定义**
     * （`K-4`：可规划即可执行；自己再造一个"能不能站/能不能穿"的判据就是第二份真相）。
     */
    static String neighbourhoodWitness(ServerLevel level, BlockPos pos) {
        StringBuilder bits = new StringBuilder(26);
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    bits.append(com.dddgn.alice.pathing.MovementHelper
                            .canWalkThrough(level, pos.offset(dx, dy, dz)) ? '0' : '1');
                }
            }
        }
        return bits.toString();
    }

    /**
     * **按种类分配过滤候选**（`D-361`）：满足的种类不再选它、不在分配里的不要。
     *
     * <p>⚠️ 调用点**必须**在 {@link TargetClusters#queueFor} 与 `policy.select` **之前**
     * （`D-361` 口径：簇保持纯几何 —— 让"簇"随配额状态漂移，就没法单独咬簇判据了）。
     * 门禁 `rule_kind_filter_before_cluster` 咬这条顺序。
     */
    /**
     * ⭐ `D-389`：**沿脉传播** —— 把刚挖掉那格的 26 邻域里「仍然是目标、且没被了结」的格
     * **插到簇队列最前**（下一个就试它们）。
     *
     * <p>为什么这是"簇能被挖完"的关键（用户 2026-09-22 的推理）：26 邻接（`TargetClusters.DIAGONAL_26`，
     * 对角相连**已加入判定**）⇒ 进了脉之后每个下一个目标都在 1 格内 ⇒ 只需要**便宜**的规划
     * （`CURRENT`/`DIRECT`/`D-365` 就地挖 / 1 格隧道），而不是一次跨 6 格的昂贵隧道搜索。
     *
     * <p>安全性：**只改消费顺序** —— 下游（种类会计、候选查找、`attempted`、归因码）全部不变；
     * 不在 `set.viable()` 里的位置会被既有消费循环自然跳过；`kindPlan` 的过滤仍在
     * {@link #filterByKind} 先行执行（`D-361`：簇保持纯几何，顺序建议不改变筛选）。
     */
    /** 作业声明范围（水平半径 + 垂直 ±8 格，与扫描口径同量级）。 */
    private boolean inDeclaredRange(BlockPos pos) {
        int dx = Math.abs(pos.getX() - spec.center().getX());
        int dz = Math.abs(pos.getZ() - spec.center().getZ());
        int dy = Math.abs(pos.getY() - spec.center().getY());
        return dx <= spec.radius() && dz <= spec.radius() && dy <= 8;
    }

    private void enqueueVeinNeighbours(ServerLevel level, BlockPos mined) {
        int added = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    cursor.set(mined.getX() + dx, mined.getY() + dy, mined.getZ() + dz);
                    BlockPos pos = cursor.immutable();
                    if (attempted.contains(pos)) {
                        continue;
                    }
                    // ⚠️ **不许越界**：作业声明了 `center + radius`（`ScopeBuffer` 也按它开）
                    // ⇒ 沿脉传播只准在**声明范围内**走；跨边界的部分留给同簇其余成员/下次作业
                    // （`TargetClusters` 有几何口径，`D-329 §3`）。
                    if (!inDeclaredRange(pos)) {
                        continue;
                    }
                    if (!source.matchesTarget(level, pos)) {
                        continue;
                    }
                    // ⚠️ **必须"挪到最前"，不是"不在才加"**：`TargetClusters.queueFor` 返回的队列
                    // **本来就含整簇成员** ⇒ 用 `contains` 判重会把 26 个邻居**全部跳过**、传播变成死代码
                    // （2026-09-22 夹具 `mine_vein_propagation` 第一版实测 `veinPropagations=0` 抓到的就是这个）。
                    // 位置不在候选集里也不会出问题：消费循环在 `set.viable()` 里查不到就跳下一个。
                    clusterQueue.remove(pos);
                    clusterQueue.add(0, pos);
                    added++;
                }
            }
        }
        if (added > 0) {
            veinPropagations++;
            veinEnqueued += added;
            DecisionTrace.step(jobName(), "VEIN", mined.toShortString(),
                    "沿脉传播：把 " + added + " 个相邻目标插到最前（每个 ≈1 格远 ⇒ 不需要昂贵搜索）");
        }
    }

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

    /**
     * **清障不许吃掉的格**（`D-362`）：本作业的目标方块，**但不含"这一次要去挖的那一格"**。
     *
     * <p>为什么必须豁免当前目标：`ENTER_TARGET` 模式就是"破坏进入目标那一格"（路径自己把那格挖开），
     * 一刀切保护会把这条腿打断（`enter_target_unreachable`）⇒ 挖矿整体退化。
     * 真正要拦的是**顺手吃掉别的目标**（第一轮那个 `479,68,104` 就是"别的目标"）。
     *
     * <p>未加载的格返回 false：`getBlockState` 会同步加载区块（`D-331` 纪律），而且搜索本来也走不到未加载处。
     */
    private boolean protectedFromClearance(BlockPos pos) {
        if (pos == null || pos.equals(current)) {
            return false;
        }
        ServerLevel level = bot.serverLevel();
        return level.hasChunkAt(pos) && source.matchesTarget(level, pos);
    }

    private Task.Status finish(Task.Status status) {
        if (!terminated) {
            terminated = true;
            // `D-362`：任务结束必须撤销目标保护（`BotManager` 换任务时也会兜底清一次）
            com.dddgn.alice.action.TaskTargetProtection.end(bot);
            // ⭐ `P3`：**权限窗口 = 作业时长** —— 四条终态路径（配额达成/候选穷尽/背包满/超时）全过这里
            // ⇒ 撤销点只写一处；漏掉的话 TTL 兜底，但那就是权限多活一段时间（不许靠它）。
            if (areaGrant != null) {
                com.dddgn.alice.decision.CollectGrants.revokeJobScoped(areaGrant.id());
                areaGrant = null;
            }
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
