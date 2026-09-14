package com.dddgn.alice.job.lumber;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.job.Candidate;
import com.dddgn.alice.job.CandidateSet;
import com.dddgn.alice.job.GoalSpec;
import com.dddgn.alice.job.Selection;
import com.dddgn.alice.job.SelectionPolicy;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * **`MAINTAIN` 区域型伐木 Job**（J8 / §13）：不追求"跑完即结束"，而是**持续维持区域不变量**。
 *
 * <p>与一次性伐木的关系（§13 的裁定）：**同一个 Job 家族 + 不同 `GoalSpec`/策略**，
 * 而不是两套任务。落地方式：本 Job 只做"**巡查 → 挑一棵 → 派活 → 回来继续巡查**"的编排，
 * 真正的砍伐**原样复用** {@link LumberJob}（每次给它 {quota=1, center=那棵树}），
 * 因此清障预算、建拆同权、攀爬兜底、失败语义全部沿用已验证的那一套 —— 没有第二份实现。
 *
 * <p>生命周期（§13.1）：
 * <pre>
 * PATROL（每 {@code patrolIntervalTicks} 巡查一次：扫区域 → 过滤掉试过的 → 挑最近的一棵）
 *   ├─ 有树 ⇒ HARVEST（内嵌 LumberJob, quota=1）→ 结算 → 回 PATROL
 *   └─ 无树 ⇒ 连续 {@link #IDLE_PATROLS} 次无活 ⇒ **退避等待**（默认常驻；只有 `idle-stop=true` 才 `idle_no_work` 收工）
 * </pre>
 *
 * <p>注：`idle_no_work` 是 §13.3 的"如实待机，**不算失败**"，属于**可选模式**（默认关）——
 * 常驻时会退避到 {@link #MAX_PATROL_INTERVAL_TICKS} 继续巡查，等树长大/玩家催熟。
 *
 * <p>失败语义（§13.3）：区域里有树但全不可达 ⇒ `no_reachable_candidate` + 逐树理由；
 * 缺工具 ⇒ `tool_missing`（沿用 D-128 的前置检查）；脚手架没拆干净 ⇒ `scaffold_restore_incomplete`。
 *
 * <p>**健康输出**（§13.1：常驻任务不能是黑箱）：每次巡查一行
 * {@code [Job] maintain region=… viable=… mySaplings=… chopped=… actions=… lastPatrol=…}。
 *
 * <p>停止：**只由玩家/决策层显式打断**（§13.1 / 用户 2026-09-12 裁定）——
 * {@code /alice region stop} ⇒ `cancelled:region_stop`（终态 `CANCELLED_BY_USER`），
 * 下任何其它 `/alice …` 指令 ⇒ 被替换 `cancelled:replaced`。`idle-stop` 打开时才退回旧行为
 * （无树无苗无欠 ⇒ `idle_no_work`）。
 */
public final class RegionLumberJob implements com.dddgn.alice.job.Job {

    public static final String NAME = "region_lumber";

    /**
     * 作业区漂移守卫常量（D-179）：常驻 Job 必须知道自己被搬走了。
     *
     * 2026-09-13 实测：转移夹具把 bot 传送到 z≈406，而本 Job 的作业区是 z203..231 ⇒ Job 毫无察觉，
     * 仍在区域内选树（最近的也在 198 格外）并逐个尝试 ⇒ 白白空转（用户最后只能手动 /alice region stop）。
     * 更早一轮还因此就地搭了 12 格圆石（写入位置与目标无因果关系，已由 MiningTuning.gainHorizontallyReachable 拦住）。
     *
     * 语义：距区域过远时**挂起**（不选目标、不消耗候选、不写世界），每 100 tick 如实告警；
     * 连续 MAX_DRIFT_TICKS tick 都在区外 ⇒ **如实失败** outside_region（不无限等、不静默空转；回到区内自动继续）。
     */
    private static final int DRIFT_MARGIN = 8;
    /** 区外挂起上限（20 秒）。 */
    private static final int MAX_DRIFT_TICKS = 400;

    /** 连续多少次"巡查无活"才判定待机（§13.1：巡查周期由配置决定，禁止高频扫描）。 */
    public static final int IDLE_PATROLS = 3;
    /**
     * **等生长时的巡查退避上限**（tick）。§13.1：「树苗生长需要真实时间，**禁止高频扫描**」——
     * 所以一旦"活都干完了、只剩等苗长大"，巡查间隔就逐步翻倍到这个上限（默认 30 s），
     * 有新树/需要补种时立刻恢复成配置的间隔。
     */
    public static final int MAX_PATROL_INTERVAL_TICKS = 600;
    /** 垂直自适应：生效上界 = 区域内最高原木 + 这么多格（够覆盖树冠/掉落物，不把整片天空算进来）。 */
    public static final int VERTICAL_MARGIN = 4;
    /** 垂直自适应下界（即使区域内暂时没树，也至少留这么高，免得刚种下的苗被漏掉）。 */
    public static final int MIN_ADAPTIVE_HEIGHT = 8;

    private final BotPlayer bot;
    private final LumberRegionState.Region region;
    /** 连续在作业区外的 tick 数（D-179 漂移守卫）。 */
    private int driftTicks;
    private final ScopeBuffer scope;
    private final LumberCandidateSource source;
    private final SelectionPolicy policy;
    private final int patrolIntervalTicks;
    private final int maxTicks;
    /**
     * 玩家观察者（可空；夹具/电池里可能没有）。**常驻任务不能是黑箱**（§13.1）：
     * "区域里没有活干、正在等什么、怎么让它收工"必须能在聊天里看到，
     * 否则玩家只会看到 bot 站着不动（2026-09-12 实测：空区域常驻 = 看起来"没有任何反应"）。
     */
    private final net.minecraft.server.level.ServerPlayer observer;

    private final Set<net.minecraft.core.BlockPos> tried = new LinkedHashSet<>();
    private final List<String> failureNotes = new ArrayList<>();

    private LumberJob current;
    private int ticks;
    private int patrolCooldown;
    /** 当前生效的巡查间隔（等生长时退避；发现活就恢复配置值）。 */
    private int currentPatrolInterval;
    /** 上一轮巡查"在等什么"（生长/补种），用于健康输出。 */
    private String waitingFor = "-";
    private int idlePatrols;
    private int treesChopped;
    private int treesFailed;
    private String terminalReason = "";
    private String failure = "";
    private boolean terminated;
    /** 本会话是否已就"没有活干"提示过玩家（只提示一次，别刷屏）。 */
    private boolean toldNoWork;

    public RegionLumberJob(BotPlayer bot, LumberRegionState.Region region, ScopeBuffer scope,
                           LumberCandidateSource source, SelectionPolicy policy,
                           int patrolIntervalTicks, int maxTicks) {
        this(bot, region, scope, source, policy, patrolIntervalTicks, maxTicks, null);
    }

    public RegionLumberJob(BotPlayer bot, LumberRegionState.Region region, ScopeBuffer scope,
                           LumberCandidateSource source, SelectionPolicy policy,
                           int patrolIntervalTicks, int maxTicks,
                           net.minecraft.server.level.ServerPlayer observer) {
        this.bot = bot;
        this.region = region;
        this.scope = scope;
        this.source = source;
        this.policy = policy;
        this.patrolIntervalTicks = Math.max(1, patrolIntervalTicks);
        this.currentPatrolInterval = this.patrolIntervalTicks;
        this.maxTicks = maxTicks;
        this.observer = observer;
    }

    @Override
    public String jobName() {
        return NAME;
    }

    @Override
    public com.dddgn.alice.task.TaskTarget target() {
        if (current != null) {
            return current.target();
        }
        return com.dddgn.alice.task.TaskTarget.block(region.center());
    }

    @Override
    public String failureReason() {
        return failure;
    }

    @Override
    public java.util.List<com.dddgn.alice.task.TaskNode> subTasks() {
        java.util.List<com.dddgn.alice.task.TaskNode> children = new java.util.ArrayList<>();
        if (current != null) {
            children.add(new com.dddgn.alice.task.TaskNode("LumberJob(inner)",
                    current.target().describe(), "HARVEST", ticks, current.progressSummary(),
                    "", current.subTasks()));
        }
        return children;
    }

    @Override
    public String terminalReason() {
        return terminalReason;
    }

    /** 本会话已砍棵数（夹具/电池断言用）。 */
    public int treesChopped() {
        return treesChopped;
    }

    /** 本会话是否已至少补种一棵（夹具/电池断言用）。 */
    public boolean plantedSomething() {
        return LumberRegionState.get(bot.getServer()).saplingsPlanted(bot.getUUID()) > 0;
    }

    /** J-4：常驻 Job 的失败报告要说清"区域状态 + 巡查了多少轮 + 最近一次子任务为什么失败"。 */
    @Override
    public com.dddgn.alice.bot.TaskFailureReport failureReport() {
        return new com.dddgn.alice.bot.TaskFailureReport(
                failureReason(), "maintain", progressSummary()
                + " terminal=" + terminalReason
                + " waitingFor=" + waitingFor
                + " mySaplings=" + com.dddgn.alice.job.lumber.LumberRegionState
                        .get(bot.getServer()).mySaplingCount(bot.getUUID())
                + (failure.isBlank() ? "" : " failure=" + failure),
                com.dddgn.alice.bot.RecoveryStage.NONE, java.util.List.of());
    }

    public String progressSummary() {
        return "region=" + region.describe() + " chopped=" + treesChopped + " failed=" + treesFailed
                + " mySaplings=" + LumberRegionState.get(bot.getServer()).mySaplingCount(bot.getUUID());
    }

    @Override
    public com.dddgn.alice.task.Task.Status tick() {
        if (terminated) {
            return com.dddgn.alice.task.Task.Status.DONE;
        }
        if (++ticks > maxTicks) {
            terminalReason = "goal_timeout";
            failure = terminalReason;
            return finish(com.dddgn.alice.task.Task.Status.FAILED);
        }

        // D-179 漂移守卫：**按真实 tick 计数**。
        // ⚠ 自查修正（2026-09-13 晚）：初版把 `driftTicks++` 放在 `patrol()` 里 ⇒ 计的是"巡查次数"
        // （patrol 每 patrolIntervalTicks 才跑一次，电池里是 20）⇒ `MAX_DRIFT_TICKS=400` 实际是
        // 400×20=8000 tick ≈ 6.7 分钟，与注释/文档声称的"20 秒"不符（用户实测 23 秒没等到终态即因此）。
        // 现在每 tick 计数，语义与文档一致。
        if (!nearRegion()) {
            if (++driftTicks == 1 || driftTicks % 100 == 0) {
                BotLog.warn("[Job] region_drifted foot={} region={} driftTicks={} ⇒ 挂起作业"
                                + "（不选目标/不写世界；连续超过 {} tick 将如实失败 outside_region）",
                        com.dddgn.alice.pathing.MovementHelper
                                .footCell(bot.serverLevel(), bot).toShortString(),
                        region.describe(), driftTicks, MAX_DRIFT_TICKS);
            }
            if (driftTicks > MAX_DRIFT_TICKS) {
                terminalReason = "outside_region";
                failure = terminalReason;
                return finish(com.dddgn.alice.task.Task.Status.FAILED);
            }
        } else if (driftTicks > 0) {
            BotLog.info("[Job] region_returned foot={} region={}（已回到作业区，恢复作业）",
                    com.dddgn.alice.pathing.MovementHelper
                            .footCell(bot.serverLevel(), bot).toShortString(),
                    region.describe());
            driftTicks = 0;
        }
        if (current != null) {
            return harvest();
        }
        if (patrolCooldown > 0) {
            patrolCooldown--;
            return com.dddgn.alice.task.Task.Status.RUNNING;
        }
        patrolCooldown = currentPatrolInterval;
        return patrol();
    }

    // ==================== 巡查 ====================

    /** bot 是否仍在作业区 + 余量内（水平外扩 DRIFT_MARGIN；竖直给 baseY±8 宽容）。 */
    private boolean nearRegion() {
        net.minecraft.core.BlockPos foot = com.dddgn.alice.pathing.MovementHelper
                .footCell(bot.serverLevel(), bot);
        boolean horizontallyNear = foot.getX() >= region.minX() - DRIFT_MARGIN
                && foot.getX() <= region.maxX() + DRIFT_MARGIN
                && foot.getZ() >= region.minZ() - DRIFT_MARGIN
                && foot.getZ() <= region.maxZ() + DRIFT_MARGIN;
        boolean verticallySane = foot.getY() >= region.baseY() - DRIFT_MARGIN
                && foot.getY() <= region.baseY() + region.maxHeight() + DRIFT_MARGIN;
        return horizontallyNear && verticallySane;
    }

    private com.dddgn.alice.task.Task.Status patrol() {
        // D-179：区外就**不选新的作业**（计数/告警/终态由 tick() 统一按真实 tick 处理）。
        if (!nearRegion()) {
            return com.dddgn.alice.task.Task.Status.RUNNING;
        }
        var server = bot.serverLevel().getServer();
        LumberRegionState state = LumberRegionState.get(server);
        var spec = GoalSpec.harvestUnits(region.center(), region.coverRadius(), 1, maxTicks);
        CandidateSet raw = source.candidates(bot, spec);

        state.markPatrol(bot.getUUID(), server.getTickCount());
        // §13.2：先**对账我种的苗**（长成树就销账、被拔掉也销账），否则"欠树"判断会被幽灵条目污染
        reconcileMySaplings(state);
        // **垂直自适应**（用户 2026-09-12 裁定：玩家只划水平范围）：生效上界按**实测树高**收紧，
        // 既不会漏掉刚长高的树，也不会把"整片天空"算进区域。
        int tallestTop = tallestTreeTopY();
        int effectiveTop = Math.max(region.baseY() + MIN_ADAPTIVE_HEIGHT,
                Math.min(region.baseY() + region.maxHeight(), tallestTop + VERTICAL_MARGIN));
        List<Candidate> inRegion = new ArrayList<>();
        for (Candidate candidate : raw.viable()) {
            if (region.containsHorizontal(candidate.anchor())
                    && candidate.anchor().getY() >= region.baseY() - 2
                    && candidate.anchor().getY() <= effectiveTop
                    && !tried.contains(candidate.anchor())) {
                inRegion.add(candidate);
            }
        }
        int viableInRegion = 0;
        for (Candidate candidate : raw.viable()) {
            if (region.containsHorizontal(candidate.anchor())
                    && candidate.anchor().getY() >= region.baseY() - 2
                    && candidate.anchor().getY() <= effectiveTop) {
                viableInRegion++;
            }
        }
        int mySaplings = state.mySaplingCount(bot.getUUID());
        int standing = viableInRegion + mySaplings;
        if (!state.baselineDerived(bot.getUUID())) {
            // 区域目标棵数 = 首次巡查时"站着的可作业树 + 我种的苗"（= standing）。
            // **把我种的苗算进去**（2026-09-12 J8 收尾）：在一片"已经砍完、只剩苗"的地块上启动时，
            // 目标不会退化成 0（否则那一轮之后再也补不回"欠 N 棵"的区域不变量）；
            // 空区域仍然是 0 ⇒ 仍然可以如实待机（`idle_no_work`）。
            // 判据是**持久化的"已推导"标记**（不是 `baseline<=0`）：空区域推出来的结果就是 0，
            // 拿 0 当"没推导"会让常驻空区域每轮重推一次、永久刷日志（2026-09-12 实测）。
            state.setBaselineTrees(bot.getUUID(), standing);
            state.setBaselineDerived(bot.getUUID(), true);
            BotLog.info("[Job] maintain 区域目标棵数 baseline={}"
                            + "（首次巡查确定 = 现场可作业树 {} + 我种的苗 {}；之后按它算欠树）",
                    standing, viableInRegion, mySaplings);
        }
        int deficit = Math.max(0, state.baselineTrees(bot.getUUID()) - standing);
        BotLog.info("[Job] maintain region={} viable={} inRegion={} tried={} mySaplings={}"
                        + " standing={} baseline={} deficit={} chopped={} failed={} planted={}"
                        + " pendingReplant={} waiting={} interval={} lastPatrol={}",
                region.describe() + " adaptiveTop=" + effectiveTop,
                raw.viable().size(), inRegion.size(), tried.size(),
                mySaplings, standing, state.baselineTrees(bot.getUUID()),
                deficit, treesChopped, treesFailed,
                LumberRegionState.get(server).saplingsPlanted(bot.getUUID()),
                state.pendingReplantCount(bot.getUUID()), waitingFor, currentPatrolInterval,
                server.getTickCount());

        // ① 欠树 ⇒ 先补种（§13.1"有空格且欠树 → 补种"）；② 有树 ⇒ 砍；两者都在同一轮里按需做
        if (deficit > 0) {
            var planted = tryPlant(state, deficit);
            if (planted != null) {
                return planted;               // 失败（缺配置/缺苗）⇒ 如实上抛
            }
            idlePatrols = 0;                  // 刚干了活（补种），不算待机
        }

        if (inRegion.isEmpty()) {
            idlePatrols++;
            if (!toldNoWork) {
                // **常驻任务不能是黑箱**（§13.1）：玩家划完区、start 之后如果什么都没发生，
                // 至少要说清"现在是什么状态、在等什么、怎么收工"（2026-09-12 客户端实测：
                // 空区域常驻 ⇒ 玩家只看到 bot 站着不动，以为"没有任何反应"）。
                toldNoWork = true;
                tell("区域 " + region.describe() + " 里没有可作业的树"
                        + "（viable=" + viableInRegion + " mySaplings="
                        + state.mySaplingCount(bot.getUUID()) + " deficit=" + deficit + "）——"
                        + (state.autoIdleStop(bot.getUUID())
                        ? "idle-stop=true：连续 " + IDLE_PATROLS + " 次无活就收工（idle_no_work）"
                        : "常驻巡查中（间隔退避到 " + MAX_PATROL_INTERVAL_TICKS + " tick，等树长大；"
                        + "要它收工用 /alice region stop）"));
            }
            if (idlePatrols >= IDLE_PATROLS) {
                if (!failureNotes.isEmpty()) {
                    // §13.3：区域里有树但全不可达 ⇒ FAILED no_reachable_candidate + 逐树理由
                    terminalReason = "no_reachable_candidate";
                    failure = terminalReason + " " + String.join(" | ", failureNotes);
                    return finish(com.dddgn.alice.task.Task.Status.FAILED);
                }
                // §13.3：只有"连续 N 次巡查无进展**且区域内无树无苗**"才如实待机收工。
                // **苗还在长（mySaplings>0）或还欠树（deficit>0）都不算没活** —— MAINTAIN 是常驻任务
                // （§13.1"都满足 ⇒ 巡查待机"；用户 2026-09-12 实测：原实现收工太早，
                // 手动催熟的树立刻没人管）。
                // 用户 2026-09-12 裁定：**常驻任务本就该只由玩家/决策层显式打断**
                // （`/alice region stop` 或任何 `/alice` 指令）。旧的"连续无活即 IDLE_NO_WORK 收工"
                // 保留为**可选模式**（`/alice region idle-stop on`），默认关闭。
                if (state.autoIdleStop(bot.getUUID())
                        && deficit == 0 && state.mySaplingCount(bot.getUUID()) == 0) {
                    terminalReason = "idle_no_work";
                    return finish(com.dddgn.alice.task.Task.Status.DONE);
                }
                // 常驻等待：**巡查退避**（§13.1「树苗生长需要真实时间，禁止高频扫描」）
                int previous = currentPatrolInterval;
                currentPatrolInterval = Math.min(currentPatrolInterval * 2, MAX_PATROL_INTERVAL_TICKS);
                waitingFor = deficit > 0 ? "deficit(" + deficit + ")" : "saplings("
                        + state.mySaplingCount(bot.getUUID()) + ")";
                if (currentPatrolInterval != previous) {
                    BotLog.info("[Job] maintain 待机巡查：{}，间隔退避 {} → {} tick"
                                    + "（常驻：只由玩家/决策层打断；有新树/要补种立刻恢复 {}）",
                            waitingFor, previous, currentPatrolInterval, patrolIntervalTicks);
                }
            }
            return com.dddgn.alice.task.Task.Status.RUNNING;
        }

        idlePatrols = 0;
        if (currentPatrolInterval != patrolIntervalTicks) {
            BotLog.info("[Job] maintain 发现活 ⇒ 巡查间隔恢复 {} tick", patrolIntervalTicks);
            currentPatrolInterval = patrolIntervalTicks;
        }
        waitingFor = "-";
        int localRadius = localSearchRadius();
        Selection selection = policy.select(bot, spec, new CandidateSet(inRegion, raw.rejected()));
        Candidate picked = selection.picked();
        if (picked == null) {
            // 策略没挑出来（理论上不该发生，因为候选非空）⇒ 如实记一笔，换下一轮
            failureNotes.add("policy_no_pick(" + inRegion.size() + " viable)");
            return com.dddgn.alice.task.Task.Status.RUNNING;
        }
        BotLog.info("[Job] maintain pick tree@{} reason={} candidates={}",
                picked.anchor().toShortString(), selection.reason(), inRegion.size());
        var treeSpec = GoalSpec.harvestUnits(picked.anchor(), localRadius, 1, maxTicks);
        current = new LumberJob(bot, treeSpec, scope, source, policy);
        return com.dddgn.alice.task.Task.Status.RUNNING;
    }

    /**
     * **对账"我种的苗"**（§13.2）：位置已不是树苗（长成树 / 被拔掉）⇒ 从"我种的"里销账。
     * 长成树的那些会在下一轮作为候选出现，所以销账不会丢信息。
     */
    private void reconcileMySaplings(LumberRegionState state) {
        var level = bot.serverLevel();
        for (var pos : state.mySaplings(bot.getUUID())) {
            var block = level.getBlockState(pos).getBlock();
            boolean stillSapling = level.getBlockState(pos).is(net.minecraft.tags.BlockTags.SAPLINGS);
            if (!stillSapling) {
                state.forgetSapling(bot.getUUID(), pos);
                BotLog.info("[Job] maintain 苗 {} 已不是树苗（{}）⇒ 销账", pos.toShortString(),
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block));
            }
        }
    }

    /**
     * **补种一格**（§13.2：`KEEP` 策略的计划内永久修改）。
     *
     * @return null = 本轮补种成功（或本轮不需要补）；非 null = **如实失败**的终态
     */
    private com.dddgn.alice.task.Task.Status tryPlant(LumberRegionState state, int deficit) {
        String itemId = state.saplingItem(bot.getUUID());
        if (itemId == null) {
            terminalReason = "sapling_unavailable";
            failure = terminalReason + "（区域欠树 deficit=" + deficit
                    + "，但未选择树苗：/alice region sapling <item>）";
            BotLog.warn("[Job] maintain {}", failure);
            return finish(com.dddgn.alice.task.Task.Status.FAILED);
        }
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(new net.minecraft.resources.ResourceLocation(itemId));
        if (item == null || !(item instanceof net.minecraft.world.item.BlockItem blockItem)) {
            terminalReason = "sapling_unavailable";
            failure = terminalReason + "（选定的树苗物品无效：" + itemId + "）";
            BotLog.warn("[Job] maintain {}", failure);
            return finish(com.dddgn.alice.task.Task.Status.FAILED);
        }
        // 找一格可补种的位置：优先"自己砍过的树桩"，其下方必须是土/草（树苗的放置前提）
        var level = bot.serverLevel();
        var spots = new java.util.ArrayList<>(state.pendingReplant(bot.getUUID()));
        net.minecraft.core.BlockPos spot = null;
        for (var candidate : spots) {
            var below = level.getBlockState(candidate.below());
            if (level.getBlockState(candidate).isAir() && below.is(net.minecraft.tags.BlockTags.DIRT)) {
                spot = candidate;
                break;
            }
        }
        if (spot == null) {
            BotLog.info("[Job] maintain 欠树 deficit={} 但当前没有可补种的位置（等地形/等树桩空出来）", deficit);
            return null;
        }
        // 手上要有这个树苗（没有 ⇒ §13.3 的 tool_missing，语义是"缺执行这件事的东西"）
        var inventory = bot.getInventory();
        int slot = -1;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            terminalReason = "tool_missing";
            failure = terminalReason + "（区域欠树 deficit=" + deficit + "，但背包里没有 " + itemId + "）";
            BotLog.warn("[Job] maintain {}", failure);
            return finish(com.dddgn.alice.task.Task.Status.FAILED);
        }
        var grant = com.dddgn.alice.action.WriteGrant.of(jobName(),
                com.dddgn.alice.action.WriteReason.REGION_REPLANT);
        var verdict = com.dddgn.alice.action.WriteBudget.consumePlace(bot, level, spot, grant);
        if (verdict == com.dddgn.alice.action.WriteBudget.Verdict.REFUSED) {
            BotLog.warn("[Job] maintain 补种被写入预算拒绝 {}（D-106：超限即硬停，不越界改世界）",
                    spot.toShortString());
            return null;
        }
        var previous = level.getBlockState(spot);
        // ⚠️ T1 / R-4（2026-09-14）：**补种的物理前提 = 触及距离**。
        // 原先这里直接 `level.setBlock`，**没有任何 reach / 朝向 / 放置面校验**（三路审计 §3.1 R-4 实证：
        // 这是四层里唯一的 job→world 直写越界）⇒ bot 理论上可以隔空在 4.5 格外"补种"。
        // 现在补上**触及**这条硬前提（判据与 `action/BlockInteraction` 完全同一份，不另写一套）；
        // 够不着就**不写世界**、保留待补种项，下一轮巡查再说（诚实跳过优于隔空成功）。
        // 已知残留（**登记在案，不是遗忘**）：本方法仍用直接 `setBlock` 而非 `gameMode` 交互路径，
        // 因为选定树苗可能落在**主背包**（而 `BlockInteraction.placeAt` 只认快捷栏 0-8）——
        // 改走原语会同时改变**物品消耗路径**与**放置面语义**，属行为变更，需独立一轮客户端验证。
        if (!com.dddgn.alice.action.BlockInteraction.reachable(bot, spot)) {
            BotLog.warn("[Job] maintain 补种够不着 {}（触及校验未过）⇒ 本轮不写世界、保留待补种",
                    spot.toShortString());
            return null;
        }
        var placed = blockItem.getBlock().defaultBlockState();
        level.setBlock(spot, placed, 3);
        // 账本记 KEEP（`REGION_REPLANT.temporary()==false`）⇒ 不受"建拆同权"约束
        com.dddgn.alice.ledger.WorldModLedger.recordPlacement(level, bot.getUUID(), grant, spot,
                previous, placed);
        inventory.getItem(slot).shrink(1);
        state.addMySapling(bot.getUUID(), spot);
        state.removePendingReplant(bot.getUUID(), spot);
        BotLog.info("[Job] maintain plant sapling@{}（deficit={} → 补种后 standing 上升；KEEP 策略）",
                spot.toShortString(), deficit);
        return null;
    }

    /** 区域内（水平范围内）最高原木的 Y；没有树时返回基准层。 */
    private int tallestTreeTopY() {
        int tallest = region.baseY();
        for (var tree : TreeScanner.scan(bot.serverLevel(), region.center(), region.coverRadius())) {
            if (!region.containsHorizontal(tree.base())) {
                continue;
            }
            tallest = Math.max(tallest, tree.top().getY());
        }
        return tallest;
    }

    /** 单棵树的局部搜索半径：够覆盖该树及其树冠即可，不必整片区域。 */
    private int localSearchRadius() {
        return 8;
    }

    // ==================== 派活（复用一次性伐木 Job）====================

    private com.dddgn.alice.task.Task.Status harvest() {
        var status = current.tick();
        if (status == com.dddgn.alice.task.Task.Status.RUNNING) {
            return com.dddgn.alice.task.Task.Status.RUNNING;
        }
        var base = current.target().blockPos();
        String reason = current.terminalReason();
        if (status == com.dddgn.alice.task.Task.Status.DONE && "quota_met".equals(reason)) {
            treesChopped++;
            LumberRegionState.get(bot.serverLevel().getServer()).addChopped(bot.getUUID());
            // §13.2：树桩记为待补种位置（区域不变量：欠树则补种）
            LumberRegionState.get(bot.serverLevel().getServer())
                    .addPendingReplant(bot.getUUID(), base);
            BotLog.info("[Job] maintain tree@{} 完成 chopped={}", base.toShortString(), treesChopped);
        } else {
            treesFailed++;
            String detail = base.toShortString() + ":" + reason
                    + (current.attemptFailures().isEmpty() ? ""
                            : " " + String.join(" | ", current.attemptFailures()));
            failureNotes.add(detail);
            tried.add(base);
            BotLog.warn("[Job] maintain tree@{} 未完成 reason={}（记入逐树理由，本轮不再挑它）",
                    base.toShortString(), reason);
        }
        current = null;
        return com.dddgn.alice.task.Task.Status.RUNNING;
    }

    private com.dddgn.alice.task.Task.Status finish(com.dddgn.alice.task.Task.Status status) {
        terminated = true;
        bot.controller().stopMovement();
        BotLog.info("[Job] maintain SUMMARY region={} chopped={} failed={} patrols={} mySaplings={}"
                        + " planted={} baseline={} saplingItem={} reason={} → {}",
                region.describe(), treesChopped, treesFailed,
                LumberRegionState.get(bot.getServer()).patrols(bot.getUUID()),
                LumberRegionState.get(bot.getServer()).mySaplingCount(bot.getUUID()),
                LumberRegionState.get(bot.getServer()).saplingsPlanted(bot.getUUID()),
                LumberRegionState.get(bot.getServer()).baselineTrees(bot.getUUID()),
                String.valueOf(LumberRegionState.get(bot.getServer()).saplingItem(bot.getUUID())),
                terminalReason, status);
        // 终态也回聊天（否则"任务悄悄结束/悄悄失败"只有日志里看得到）
        if ("idle_no_work".equals(terminalReason)) {
            tell("区域没有活干了（无树无苗无欠）⇒ idle_no_work 收工；"
                    + "想让它常驻就用 /alice region idle-stop false 再 /alice region start");
        } else if (status == com.dddgn.alice.task.Task.Status.FAILED) {
            tell("区域任务失败：" + failure);
        }
        return status;
    }

    /** 给玩家观察者回一句（没有观察者、或观察者已退出就只留在日志里）。 */
    private void tell(String text) {
        // 常驻任务可能比玩家的在线时间还长：退出/被移除后不要再往那条连接写（安全兜底）
        if (observer == null || observer.hasDisconnected() || observer.isRemoved()) {
            return;
        }
        observer.sendSystemMessage(net.minecraft.network.chat.Component.literal("[alice] " + text));
    }
}
