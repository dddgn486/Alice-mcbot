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

    /**
     * 稳定任务标识（J-3/D-265）：Job 一律用自己声明的 `NAME`（不用实现类名）——
     * 换实现类/换版本时，终态记录与决策提示里的 `kind` 不漂（客户端 2026-09-17 实测暴露：
     * `task_execution_terminal kind=LumberJob` 就是"没覆写"的直接后果）。
     */
    @Override
    public String taskName() {
        return NAME;
    }

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
    /**
     * **逐原木的失败事实**（结构化）：`code` 是**字段**，不是拼接串里的一段子串。
     *
     * <p>⭐ `D-329` ⑤.3（`M4` 先于 `M3`）：顶层归因**禁止** `contains("…")` 自由文本匹配 ——
     * 旧版正是在 `"pos:code gained=… failed=code"` 这种拼接串上做子串匹配（勘测 `survey/23` 点出的坑）。
     */
    public record LogFailure(BlockPos pos, String code) {
        public String describe() {
            return pos.toShortString() + ":" + code;
        }
    }

    /**
     * **一棵树的失败事实**（结构化）。
     *
     * @param base     树基座
     * @param code     **树级**码（`product_not_collected` / `climb_incomplete` / `partial_tree`）
     * @param gainedLogs 本棵树开始以来的**背包原木增量**（展示用；判定读 `code` 与 `logCodes`）
     * @param logs     这棵树计划的原木数
     * @param logCodes **逐原木**失败码（`miner.failureReason()` / `log_replaced`）
     */
    public record TreeFailure(BlockPos base, String code, int gainedLogs, int logs, List<String> logCodes) {
        public TreeFailure {
            logCodes = List.copyOf(logCodes);
        }

        /** **只给人看**（日志/决策提示）——判定一律读字段，不读这个串。 */
        public String describe() {
            return base.toShortString() + ":" + code + " gained=" + gainedLogs + "/" + logs
                    + (logCodes.isEmpty() ? "" : " failed=" + String.join(",", logCodes));
        }
    }

    /** 缺工具类码（与 `MineJob.TOOL_CODES` 同口径）。 */
    private static final java.util.Set<String> TOOL_CODES = java.util.Set.of(
            "no_suitable_tool", "tool_missing");

    /** 逐树的失败清单（Job 级，用于 §6.2c⑤ 的如实上报）。 */
    private final java.util.List<TreeFailure> attemptFailures = new ArrayList<>();

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
    private final List<LogFailure> failedLogs = new ArrayList<>();
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
     * ① 就地扫尾的信封与预算**逐树推导**（R3 / D-123），不再写死 `gain<=8` / `200 tick`：
     * <ul>
     *   <li>能力：{@link MiningProfile#sweepGain(int)} —— 由**该树树干高度**推出加高步数
     *       （上界 = 树干高 + 1，且不超过一次性方块预算 12）；</li>
     *   <li>tick：{@link CollectDropsTask#suggestedSweepTicks(int, MiningProfile)} ——
     *       固定开销 + 待收物数×单价 + 加高步数×单价。</li>
     * </ul>
     * ③ 落地扫尾**不给**加高——它在 ② 拆除之后，产生的放置没人收（会留残）。
     */
    private static final int SWEEP_PHASE_SLACK_TICKS = 40;
    /** 本棵树 ① 扫尾的实际预算（逐树在 `sweepUp()` 里推导后写入）。 */
    private int sweepBudgetTicks;
    /**
     * ① 就地扫尾的**阶段局部**状态（专用计数器 + 是否已开工）。
     *
     * <p>2026-09-11 修正（D-116 回归实测）：① 原先用 `collector == null` 判断"还没开工"、
     * 用 job 级全局计数器 `ticks` 当预算 —— 两个都是错的，合起来让 ① **静默跳过**：
     * <ol>
     *   <li>`collector` 在 ③ 收完后不置空，下一棵树进 ① 时它是上一棵的**僵尸任务**，
     *       "没开工"分支（含 `sweep_up_start` 日志）永远进不去；</li>
     *   <li>`ticks` 是 job 全局 tick 计数（第 4 棵树早已 ≫ 240），于是 `++ticks > 预算+40`
     *       立刻成立，走**唯一没有日志**的超时分支 → `sweptUpThisTree = true` → ① 无声作废。</li>
     * </ol>
     * 现象：`gained=1` 却直接 `restore_start`（②），零 `sweep_up_start/end`，顶部原木掉落物没人收。
     */
    private int sweepTicks;
    private boolean sweepStarted;
    /**
     * ⭐ `1.4r`（2026-09-26）：任务保护作用域是否已在**首 tick** 装上。
     * 与 `MineJob.scopeStarted` 同形状（那次是收集授权，这次是保护作用域）—— 两者都必须在首 tick 装，
     * 因为 `BotSession.beginTask` 会清空按 botId 记的所有作用域，而它跑在构造器之后。
     */
    private boolean protectionStarted;
    private boolean sweptUpThisTree;
    private boolean scaffoLeftReported;
    private String terminalReason = "";
    private String failure = "";
    private boolean terminated;
    /**
     * **终态闩锁**（`D-175`/`D-178`）：记住**首次**终态并原样回放，使"终态后再 tick"幂等。
     * ⚠️ 修前外层守卫写的是 `if (terminated) return Task.Status.DONE;` —— 守卫在、但**硬编码 DONE**
     * ⇒ 终态是 `FAILED` 时再 tick 返回 `DONE`（违反 `D-178`；电池的集中断言实测 `idempotent=false`）。
     * 形状照唯一正解 `task/MineTask:87/328`（`D-409`：全仓同形状 6 处统一）。
     */
    private Task.Status terminalStatus;

    public LumberJob(BotPlayer bot, GoalSpec spec, ScopeBuffer scope,
                     LumberCandidateSource source, SelectionPolicy policy) {
        this.bot = bot;
        this.spec = spec;
        this.scope = scope;
        this.source = source;
        this.policy = policy;
        this.logsBefore = countLogs();
        // ⚠️ `D-362` 的任务保护作用域**不在这里装**（`1.4r`，2026-09-26）：构造器早于
        // `BotSession.beginTask`，而后者会**按 botId 清空**这份作用域 ⇒ 生产侧"装上即被清"、
        // 整条作业保护为空（真机路径逐字取证见台账 `1.4r`）。安装点已移到**首 tick**
        // （见 `tickOnce()` 的 `protectionStarted` 块）。结构门禁：`tools/check-protection-install-point.py`。
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

    /** **刚结束的子任务节点**（M4b）：三个子阶段共用一格 —— 树里没有活着的子任务时把它摊出来。 */
    private com.dddgn.alice.task.TaskNode finishedChildNode;

    @Override
    public java.util.List<com.dddgn.alice.task.TaskNode> subTasks() {
        java.util.List<com.dddgn.alice.task.TaskNode> children = new java.util.ArrayList<>();
        if (miner != null) {
            children.add(com.dddgn.alice.task.TaskNode.leaf("MineTask",
                    miner.target().describe(), phase.name(), ticks,
                    "cleared=" + miner.clearedBlocks() + " gained=" + miner.gainedSteps()));
        }
        if (collector != null) {
            children.add(com.dddgn.alice.task.TaskNode.leaf("CollectDropsTask",
                    collector.target().describe(), phase.name(), ticks, ""));
        }
        if (restore != null) {
            children.add(com.dddgn.alice.task.TaskNode.leaf("RestoreScopeTask",
                    restore.target().describe(), phase.name(), ticks, ""));
        }
        if (children.isEmpty() && finishedChildNode != null) {
            children.add(finishedChildNode);
        }
        return children;
    }

    @Override
    public String terminalReason() {
        return terminalReason;
    }

    /**
     * J-4：**Job 也要给决策层一份领域化的失败报告**（默认实现只给 `phase=unknown` + 空 details，
     * 于是 LLM 拿到 `lastTerminal` 也不知道"卡在哪一步、当时什么进度"）。
     */
    @Override
    public com.dddgn.alice.bot.TaskFailureReport failureReport() {
        return new com.dddgn.alice.bot.TaskFailureReport(
                failureReason(), phase.name(), progressSummary()
                + " terminal=" + terminalReason
                + (failure.isBlank() ? "" : " failure=" + failure),
                com.dddgn.alice.bot.RecoveryStage.NONE, java.util.List.of());
    }

    public String progressSummary() {
        return "trees " + treesDone + "/" + spec.quota()
                + " logs " + choppedTotal + "/" + plannedTotal
                + (clearedTotal > 0 ? " cleared=" + clearedTotal : "");
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
        // ⭐ `D-362` + `1.4r`（2026-09-26）：伐木是同一个坑（§清障吃目标）——**所有原木都是本任务的目标**，
        // 清障（`PATH_ACCESS`）一格都不许挖。这里**不需要豁免"当前那一格"**：砍树走的是
        // `EXPECTED_TARGET`（MineTask），从来没有"用清障权限把原木挖开"这条合法路径。
        // ⚠️ **为什么在首 tick 而不是构造器**：`BotSession.beginTask`（`BotManager:1998`）按 botId 清空
        // 作用域，且跑在构造器之后 ⇒ 构造器装的必然被清（生产侧护栏一直是空的）。
        if (!protectionStarted) {
            protectionStarted = true;
            com.dddgn.alice.action.TaskTargetProtection.begin(bot, jobName(),
                    pos -> pos != null && bot.serverLevel().hasChunkAt(pos)
                            && bot.serverLevel().getBlockState(pos).is(net.minecraft.tags.BlockTags.LOGS));
        }
        // 前置检查（§6.2c③）：背包放不下原木时**直接收工**，不要先砍一棵再发现装不下
        if (!hasRoomForLogs(bot)) {
            terminalReason = "inventory_full";
            BotLog.warn("[Job] lumber 背包放不下任何原木，直接结束（未动世界）");
            return finish(Task.Status.DONE);
        }
        // 前置检查（J7 Step 4 / §13.3）：**没有斧头就不开工**。原版允许徒手砍原木，但慢 8 倍
        // （实测 61 tick/根 vs 6~8），拿它去撞 tick 预算只会得到"砍了一半超时"这种噪声失败；
        // 缺工具是**目标级决策**（"先去弄工具"）该知道的事实 ⇒ 如实上抛 `tool_missing`。
        //
        // **顺序即语义**（2026-09-12 夹具实测教训）：必须放在"背包放不下"之后 ——
        // `INVENTORY_FULL` 用例把背包塞满（连快捷栏一起覆盖、斧头也没了），若缺工具检查在前，
        // 就会把"停止收工(DONE inventory_full)"误报成 `FAILED tool_missing`。
        if (!hasChoppingTool(bot)) {
            terminalReason = "tool_missing";
            failure = terminalReason;
            BotLog.warn("[Job] lumber 缺少砍伐工具（快捷栏无斧）⇒ FAILED tool_missing（徒手慢 8 倍，"
                    + "不拿它去撞预算；这是目标级决策该接的事实）");
            return finish(Task.Status.FAILED);
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
        sweepStarted = false;
        sweepTicks = 0;
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
            failedLogs.add(new LogFailure(log, "log_replaced"));
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
            // ⭐ `D-347`（运行账）：**"到达"由任务自己声明** —— 判据 = "第一根原木**真的被砍下来**"
            // （与 `MineJob` 同口径：到达与"作业已开始"同时被证明）。幂等，放"第一根"最准。
            if (choppedLogs == 1) {
                com.dddgn.alice.bot.TaskMetrics.arrived(taskName());
            }
            recordClear(miner);
            recordGain(miner);
            finishedChildNode = com.dddgn.alice.task.TaskNode.finished("MineTask",
                    miner.target().describe(), phase.name(), ticks,
                    "cleared=" + miner.clearedBlocks() + " gained=" + miner.gainedSteps(), miner, status);
            miner = null;
            queueIndex++;
            return Task.Status.RUNNING;
        }
        // 清障（D-115）与加高（D-111）都已在 L2 内按信封尝试过；走到这里说明用尽/不可行 → 如实记账
        String reason = String.valueOf(miner.failureReason());
        recordClear(miner);
        recordGain(miner);
        failedLogs.add(new LogFailure(log, reason));
        finishedChildNode = com.dddgn.alice.task.TaskNode.finished("MineTask",
                miner.target().describe(), phase.name(), ticks,
                "cleared=" + miner.clearedBlocks() + " gained=" + miner.gainedSteps(), miner, status);
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
        collector = new CollectDropsTask(bot, tree.base(), scope, List.of(), true);
        DecisionTrace.step(jobName(), "COLLECT", tree.base().toShortString(),
                "chopped=" + choppedLogs + "/" + queue.size() + " failed=" + failedLogs.size()
                        + (clearedTotal > 0 ? " cleared=" + clearedTotal : "")
                        + (gainedThisTree > 0 ? " gained=" + gainedThisTree : ""));
        return Task.Status.RUNNING;
    }

    /**
     * 本 Job 作用域内仍未拆除的我方临时放置数（建拆同权的账）。
     *
     * <p>⭐ `Z4`（2026-09-23）：**只数保护区内条目**（`D-398` R2：区外一定不恢复 ⇒ 也不欠账）。
     * 用裸视图会把"区外/旧存档遗留"算成"还没拆"，而那个东西**本来就不该去拆**。
     * ⚠️ 因此本读数在野外**恒为 0**，这是设计：判断"这次到底写没写世界"要看
     * {@link com.dddgn.alice.action.WriteBudget#population}（闸门计数，与区无关）。
     */
    private int pendingTemp() {
        String scopeId = com.dddgn.alice.ledger.WorldModLedger
                .currentScope(bot.getServer(), bot.getUUID());
        return scopeId == null ? 0
                : com.dddgn.alice.ledger.WorldModLedger
                        .pendingTemporaryProtected(bot.serverLevel(), scopeId).size();
    }

    /** ① 就地扫尾：仍在架上时收"此刻够得到"的产物（D-107 附注）。 */
    private Task.Status sweepUp() {
        if (!sweepStarted) {
            // D-116：① 就地扫尾允许"原地加高"——高树顶端的原木掉落物常常停在树冠里、正在头顶够不到；
            // 此时 bot 还在自己的脚手架上、一次性方块在手、树干就是放置面 ⇒ 搭 1~N 格上去拿最省。
            // 这些放置落在同一作用域里，紧随其后的 ② 建拆同权会一并收回。
            sweepStarted = true;
            sweepTicks = 0;
            // R3：信封与预算**从这棵树推导**（树干高 → 加高步数；待收物数 + 步数 → tick 预算）
            MiningProfile sweepProfile = MiningProfile.sweepGain(tree.trunkHeight());
            sweepBudgetTicks = CollectDropsTask.suggestedSweepTicks(
                    scope.liveDrops().size(), sweepProfile);
            collector = new CollectDropsTask(bot, bot.blockPosition(), scope, List.of(), false,
                    sweepBudgetTicks, sweepProfile);
            BotLog.info("[Job] lumber sweep_up_start foot={} live_drops={} trunkHeight={}"
                            + " gain<={} blockBudget={} budgetTicks={}（仍在架上）",
                    MovementHelper.footCell(bot.serverLevel(), bot).toShortString(),
                    scope.liveDrops().size(), tree.trunkHeight(),
                    sweepProfile.maxGainSteps(), sweepProfile.gainBlockBudget(), sweepBudgetTicks);
            return Task.Status.RUNNING;
        }
        if (++sweepTicks > sweepBudgetTicks + SWEEP_PHASE_SLACK_TICKS) {
            BotLog.warn("[Job] lumber sweep_up_timeout ticks={}/{}（best-effort：交由 ② 拆除后落地再收）",
                    sweepTicks, sweepBudgetTicks);
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
        finishedChildNode = com.dddgn.alice.task.TaskNode.finished("CollectDropsTask",
                collector.target().describe(), phase.name(), ticks,
                "collected=" + collector.collected(), collector, status);
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
        finishedChildNode = com.dddgn.alice.task.TaskNode.finished("RestoreScopeTask",
                restore.target().describe(), phase.name(), ticks, "", restore, status);
        restore = null;
        restoredThisTree = true;
        return advanceAfterChop();
    }

    private Task.Status collectPhase() {
        Task.Status status = collector.tick();
        if (status == Task.Status.RUNNING) {
            return Task.Status.RUNNING;
        }
        finishedChildNode = com.dddgn.alice.task.TaskNode.finished("CollectDropsTask",
                collector.target().describe(), phase.name(), ticks,
                "collected=" + collector.collected(), collector, status);
        collector = null;   // ③ 收完即弃：留着会让下一棵树的 ① 误判"已经在扫尾"（D-116 修正）
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
        // J7 Step 4（D-128）：把"爬了但没砍完"与"根本没爬上去/没砍完"分开 ——
        // 依据是 **L2 汇报的加高步数**（`gainedThisTree`，D-111 起加高在 L2），不是背包增量。
        // ⭐ `D-329` ⑤.3：**先建结构化事实**（码是字段），展示串由 `describe()` 现拼 —— 判定永不读它。
        TreeFailure failure = new TreeFailure(base,
                allChoppedNow() ? "product_not_collected"
                        : (gainedThisTree > 0 ? "climb_incomplete" : "partial_tree"),
                gained, tree.logCount(),
                failedLogs.stream().map(LogFailure::code).toList());
        attemptFailures.add(failure);
        BotLog.warn("[Job] lumber 该树未完成 {}", failure.describe());
    }

    private boolean allChoppedNow() {
        return failedLogs.isEmpty() && choppedLogs == queue.size() && !queue.isEmpty();
    }

    /** 配额未达成时的终态：有产出 → `partial_quota`，一棵没成 → `no_reachable_candidate`。 */
    private Task.Status shortfall(CandidateSet set) {
        if (!attemptFailures.isEmpty()) {
            BotLog.warn("[Job] lumber 未能完成的树: {}",
                    attemptFailures.stream().map(TreeFailure::describe)
                            .collect(java.util.stream.Collectors.joining(" | ")));
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

    /**
     * **顶层失败码归因**（J7 Step 4 / D-128）：逐树理由里若**所有**失败都指向同一根因，
     * 就把顶层码换成那个根因，让目标级决策（LLM）能直接消费"为什么没干成"：
     * <ul>
     *   <li>全部缺工具（`no_suitable_tool`/`tool_missing`）⇒ `tool_missing`（§13.3）；</li>
     *   <li>全部是"爬了但没砍完"⇒ `climb_incomplete`；</li>
     *   <li>其余 ⇒ 保持原码（`partial_quota` / `no_reachable_candidate` / …）+ 逐树理由。</li>
     * </ul>
     * 只在**没有一棵树成功**时才归因（有成功就说明工具/攀爬本身可用，不能甩锅给它们）。
     */
    private String deriveTopLevelReason(String base) {
        return deriveTopLevelReason(base, attemptFailures, treesDone);
    }

    /**
     * **归因的纯函数形态**（`D-329` ⑤.3 / `M4`）—— 让夹具能直接喂合成事实做判据，不必造世界。
     *
     * <p>⭐ **禁止子串匹配**（旧版 `f.contains("no_suitable_tool")` 干的正是这件事）：现在读的是
     * {@link TreeFailure#code()}（树级）与 {@link TreeFailure#logCodes()}（逐原木级）两个**字段**。
     *
     * <p>⚠️ **与旧版的一处行为差异（有意，已登记）**：旧版是"拼接串里**出现过**工具码"就算工具因 ——
     * 于是"同一棵树里既有 `no_suitable_tool` 又有 `log_replaced`"这种**混合原因**也会被报成
     * `tool_missing`（把玩家改方块的锅甩给工具）。新版要求每棵树的逐原木码**非空且全是**工具码；
     * 混合情形如实保持 `partial_quota`。夹具 `lumber_failure` 的 `TAXONOMY_MIXED` 用例把
     * **旧写法原样实现一遍**做对照，证明它会在同一输入上撒谎（⇒ 这条判据可红）。
     */
    public static String deriveTopLevelReason(String base, List<TreeFailure> failures, int treesDone) {
        // 只对"**树被尝试过、但一棵都没成功**"这个总括码做归因；其它终态（超时/装不下/没候选/缺工具）
        // 本身就是明确原因，不能被逐树理由盖掉。
        if (!"partial_quota".equals(base) || failures.isEmpty() || treesDone > 0) {
            return base;
        }
        // ① 全是工具因：**每棵树**都有逐原木证据，且**所有**证据码都是工具类
        boolean everyTreeAllTool = failures.stream().allMatch(f -> !f.logCodes().isEmpty()
                && f.logCodes().stream().allMatch(TOOL_CODES::contains));
        if (everyTreeAllTool) {
            return "tool_missing";
        }
        // ② 全是"爬了但没砍完"：树级码集合唯一且为 climb_incomplete
        java.util.Set<String> treeCodes = failures.stream().map(TreeFailure::code)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (treeCodes.size() == 1 && treeCodes.contains("climb_incomplete")) {
            return "climb_incomplete";
        }
        return base;
    }

    /** 快捷栏里有没有砍伐工具（斧）。没有 ⇒ §13.3 的 `tool_missing`。 */
    private static boolean hasChoppingTool(ServerPlayer bot) {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < 9 && slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.is(net.minecraft.tags.ItemTags.AXES)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 逐树失败事实（夹具断言用，J7 Step 4）。
     *
     * <p>⭐ 返回的是**结构化记录**（`D-329` ⑤.3）：调用方要展示请用 {@link TreeFailure#describe()}，
     * **不要**再把它们拼成一个串去 `contains`（那正是本次 retrofit 要消灭的写法）。
     */
    public java.util.List<TreeFailure> attemptFailures() {
        return java.util.List.copyOf(attemptFailures);
    }

    private Task.Status finish(Task.Status status) {
        if (!terminated) {
            terminated = true;
            // `D-362`：任务结束撤销目标保护（`BotManager` 换任务时也会兜底清一次）
            com.dddgn.alice.action.TaskTargetProtection.end(bot);
            bot.controller().stopMovement();
            // J7 Step 2：攀爬与"建拆同权"的账一起进终态（爬了几次、花了几块、还剩没拆的）
            // J7 Step 4（D-128）：顶层码优先按"所有失败是否同一根因"上抛（§13.3 的表格口径），
            // 再补"建拆同权未闭合"（§13.3 的 `scaffold_restore_incomplete`）
            terminalReason = deriveTopLevelReason(terminalReason);
            if (scaffoldLeft > 0 && terminalReason != null
                    && !terminalReason.contains("scaffold_restore_incomplete")) {
                terminalReason = terminalReason + "+scaffold_restore_incomplete(" + scaffoldLeft + ")";
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
