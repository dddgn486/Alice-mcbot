package com.dddgn.alice.job.lumber;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.job.Candidate;
import com.dddgn.alice.job.CandidateSet;
import com.dddgn.alice.job.GoalSpec;
import com.dddgn.alice.job.Selection;
import com.dddgn.alice.job.SelectionPolicy;
import com.dddgn.alice.ledger.WorldModLedger;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.protection.TaskZoneRegistry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
     * 稳定任务标识（J-3/D-265）：Job 一律用自己声明的 `NAME`（不用实现类名）——
     * 换实现类/换版本时，终态记录与决策提示里的 `kind` 不漂（客户端 2026-09-17 实测暴露：
     * `task_execution_terminal kind=LumberJob` 就是"没覆写"的直接后果）。
     */
    @Override
    public String taskName() {
        return NAME;
    }

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

    /**
     * ⭐ `D-344` ①（用户 2026-09-19 裁定）：**扫地面期间的收集授权时长**（tick，2 分钟）。
     *
     * <p>为什么是"短 TTL + 用完就撤"：收集授权（{@code CollectGrant}）会让**范围内**的 `FOREIGN`
     * 落物变成 `GRANTED_AREA`（默认政策 `AUTO` ⇒ 可捡）。裁定是**只在"我扫自己区域地面"的那段时间**
     * 放宽 ⇒ `SESSION`（**纯内存、不持久化**）+ 短 TTL，并在 sweep 结束时**主动撤销**
     * （{@code CollectGrants.revoke}）⇒ 权限窗口**精确等于**扫描时长；TTL 只当崩溃兜底。
     *
     * <p>⚠️ **已知代价（物理不可避）**：授权期内**旁边**的落物会被**范围吸附顺手捡走** ——
     * 清单只能决定"走向谁"，决定不了"只捡谁"（原版拾取是范围触发）。
     */
    private static final int SWEEP_GRANT_TICKS = 20 * 120;

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
    /**
     * ⭐ `D-344` ③：**扫描地面**阶段的子任务。与 {@link #current}（砍树）**串行互斥** ——
     * 同一时刻最多一个非空（`tick()` 先看 `current` 再看它；`patrol()` 只在两者皆空时才会派活）
     * ⇒ 细则④「补种与扫描不许撞车」由**状态机结构**保证，不靠调用方自觉。
     */
    private com.dddgn.alice.task.CollectDropsTask sweepTask;
    /** 本轮扫描的目标件数（日志用）。 */
    private int sweepTargets;
    /** 本轮扫描**签发**的收集授权 id（结束时主动撤销；空 = 没有在飞的授权）。 */
    private String sweepGrantId;
    /**
     * ⭐ `D-344` ③（裁定：`N = 3`）：**连续多少轮扫描"零进展"**。
     *
     * <p>为什么必须有它：细则③说「扫到区域内捡完为止」，但**捡不完**时必须**如实说**
     * —— 否则"区域里有东西、每轮都去扫、每次都没收获"就成了新的 20 分钟空转
     * （`D-341`/`D-342` 同一族教训：**任务要如实失败，不能继续跑**）。
     */
    private int sweepNoProgress;
    /** 上一次的扫描判定（**只在变化时留痕** ⇒ 有状态转移证据，又不刷屏）。 */
    private SweepDecision lastSweepDecision;
    /** 连续多少轮零进展就如实失败（与既有 `IDLE_PATROLS` 同值 ⇒ 同一套"连续 N 次无活"语义）。 */
    public static final int SWEEP_NO_PROGRESS_LIMIT = 3;
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

    /**
     * **任务区解算状态**（`D-338` 附注四②，`§5.12` 第 4 件的几何层）：
     * 本 Job 的**工作区域 = 玩家的林场矩形（方块级）** ⇒ 派生出**区块级最小覆盖** = 任务区。
     *
     * <p>为什么必须解算一次而且只解算一次：任务区是**授权封套**（谁会提权、哪里算目标外），
     * 中途变化会让"同一条任务里两格拿到不同授权"。而且它是**锁定**的 ——
     * 唯一写入者就是本 Job（命令层没有写入口，玩家改不了）。
     */
    private boolean zoneResolved;
    /** 本任务区的 scopeId（收尾用它 release；**解算时抓下来**，不依赖收尾时作用域还开着）。 */
    private String taskZoneScope;
    /** 任务区状态文本（日志/夹具/失败报告可见），如 `DECLARED chunks=6`、`CONFLICT_SUBZONE safe=1`。 */
    private String taskZoneStatus = "-";
    /** 任务区覆盖的区块数（夹具/日志用）。 */
    private int taskZoneChunks;

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
        } else if (finishedChildNode != null) {
            children.add(finishedChildNode);
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
                + " zone=" + taskZoneStatus
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
        if (!zoneResolved) {
            // 首 tick 先解算任务区（**在这一 tick 的最前面** ⇒ "第一 tick 必有定论"是可断言前提）：
            // 有冲突就地如实失败，绝不带着一个无效的授权封套继续跑。
            zoneResolved = true;
            com.dddgn.alice.task.Task.Status zoneVerdict = resolveTaskZone();
            if (zoneVerdict != null) {
                return zoneVerdict;
            }
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
        // ⭐ `D-344` ③：扫描阶段（与 `current` 互斥 —— 两者不会同时非空，见字段注释）
        if (sweepTask != null) {
            return sweep();
        }
        if (patrolCooldown > 0) {
            patrolCooldown--;
            return com.dddgn.alice.task.Task.Status.RUNNING;
        }
        patrolCooldown = currentPatrolInterval;
        return patrol();
    }

    // ==================== 任务区（工作区域 ⇒ 区块级最小覆盖，D-338 附注四②）====================

    /**
     * 解算本任务的**任务区**：工作区域（林场矩形，**方块级**）⇒ 任务区（**区块级最小覆盖**）。
     *
     * <p>三种如实结果：① 声明成功（含幂等/换区）⇒ 记状态、继续跑；② 没有打开的任务作用域
     * ⇒ 如实告警 + **不声明**（保守：没有封套就没有提权，行为与今天一致）；③ 与**安全区**冲突
     * ⇒ `terminalReason=task_zone_conflict` + **如实失败**（用户口径：任务区不得覆盖子类声明，
     * 冲突必须报错，**不裁剪、不静默降级**）。
     *
     * <p>为什么冲突要**停任务**而不是"照旧跑"：任务的授权封套此时**不成立**，而工作区域是
     * **玩家的意图**（不许系统替他改小）⇒ 唯一诚实的做法是把"跑不了"当场说出来，
     * 并把**退路**写进提示（先显式取消那些区块的安全区声明）。
     *
     * @return `null` = 继续跑；非 null = 本 tick 的终态
     */
    private com.dddgn.alice.task.Task.Status resolveTaskZone() {
        var server = bot.getServer();
        if (server == null) {
            return null;
        }
        taskZoneScope = WorldModLedger.currentScope(server, bot.getUUID());
        // 维度取**bot 所在维度**：`LumberRegionState.Region` 只有水平范围（玩家只划水平），
        // 而工作区域必须落在某个维度里 ⇒ 以作业时的维度为准（跨维度作业本来也不成立）。
        var area = new TaskZoneRegistry.WorkArea(bot.serverLevel().dimension().location(),
                region.minX(), region.minZ(), region.maxX(), region.maxZ());
        // ⭐ `D-338` 附注十四：**玩家显式**的判据 = 命令（`IN_GAME_PLAYER`）或物品/夹具（`FIXTURE`）发起；
        // LLM（`LLM`）与未归因（`SYSTEM`）**不算** ⇒ 在保护区内的生效等级封顶 `L1`（拆不了玩家的方块）。
        // 野外/无认领区块不受影响（`ZoneAuthority` 在未认领时即 `NOT_GATED`）。
        String driver = com.dddgn.alice.decision.Driver.of(bot);
        boolean playerDriven = com.dddgn.alice.decision.Driver.IN_GAME_PLAYER.equals(driver)
                || com.dddgn.alice.decision.Driver.FIXTURE.equals(driver);
        TaskZoneRegistry.Result result = TaskZoneRegistry.declare(
                server, bot.getUUID(), NAME, area, playerDriven);
        // 解算结果**逐字留痕一次**（含 `ALREADY`/`REPLACED`/`NO_SCOPE` 这些"没发生事"的分支）——
        // 否则"任务区到底声明没声明、按哪个区域算的"只能靠推断（`Result#describe` 的唯一消费者）。
        BotLog.info("[TaskZone] region_lumber 解算结果：{}", result.describe());
        switch (result.status()) {
            case CONFLICT_SUBZONE -> {
                taskZoneStatus = "CONFLICT_SUBZONE safe_zone_chunks=" + result.conflicts().size();
                terminalReason = "task_zone_conflict";
                failure = "task_zone_conflict[safe_zone " + result.conflicts().size()
                        + " chunks: " + TaskZoneRegistry.describeChunks(result.conflicts()) + "]";
                BotLog.warn("[Job] region_lumber 任务区与**安全区**冲突 ⇒ 如实失败（不裁剪、不继续）："
                                + "area={} 冲突区块={} ⇒ 先 `/alice protect safe unclaim` 那些区块"
                                + "（**显式退化**到保护区父类）再重新启动任务",
                        area.describe(), TaskZoneRegistry.describeChunks(result.conflicts()));
                return finish(com.dddgn.alice.task.Task.Status.FAILED);
            }
            case NO_SCOPE -> taskZoneStatus = "NO_SCOPE";
            case EMPTY_AREA -> taskZoneStatus = "EMPTY_AREA";
            default -> {
                taskZoneChunks = result.zone().chunks().size();
                taskZoneStatus = result.status() + " chunks=" + taskZoneChunks;
            }
        }
        return null;
    }

    /** 任务区状态文本（**夹具/诊断可见**）：`DECLARED chunks=6` / `CONFLICT_SUBZONE …` / `-`。 */
    public String taskZoneStatus() {
        return taskZoneStatus;
    }

    /** 任务区覆盖的区块数（未生效时为 0）。 */
    public int taskZoneChunks() {
        return taskZoneChunks;
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

        // ⭐ `D-341`：**"无权" ≠ "没有"** —— 区域里有树、但全被**永久授权拒绝**（例如保护区里被封顶 `L1`
        // 的非玩家发起任务）⇒ **如实失败**，不许当成"区域里没有树"去待机巡查等生长。
        // 客户端实测（2026-09-19 19:06）：正是这里把"5 棵树全 `zone_break_not_allowed`"当成"没树"，
        // 加上 `欠树 deficit=5` ⇒ 每 ~2 s 一行、空转到 `maxTicks=24000`（20 分钟），期间反复唤醒 LLM。
        // ⚠️ **必须放在补种之前**：否则 `deficit>0` 会先跑去补种、把真正的阻塞原因（没权限）盖成假原因。
        if (inRegion.isEmpty()) {
            String blocked = permissionBlock(region, raw.rejected(), effectiveTop);
            if (blocked != null) {
                terminalReason = "no_permitted_candidate";
                failure = terminalReason + " " + blocked;
                BotLog.warn("[Job] maintain {}", failure);
                tell("区域里有树，但我**没有权限**作业（" + blocked + "）—— 如实收工，不再空转");
                return finish(com.dddgn.alice.task.Task.Status.FAILED);
            }
        }

        // ⭐ `D-344` ③（`D-344` ④ 的互斥保证）：**欠树 + 手里没苗 + 区内地面上有清单内落物** ⇒ 进扫描。
        // 位置**在 `tryPlant` 之前**是有意的：细则⑤「先记账、**捡够再补**」——
        // 手里没苗时跑去 `tryPlant` 只会得到 `tool_missing` 如实失败（旧行为），
        // 而地面上明明有苗可捡 ⇒ 先扫地面才是用户要的语义。
        // ⚠️ 判据必须**放在 `D-341` 永久拒绝闸之后**：否则"没权限"会被"欠树 ⇒ 去扫地面"盖成假原因。
        int saplingInInv = saplingInInventoryCount(state);
        List<net.minecraft.world.entity.item.ItemEntity> groundDrops = listDropsInRegion(state);
        SweepDecision decision = sweepDecision(deficit, saplingInInv, groundDrops.size());
        if (decision != lastSweepDecision) {
            // **断转移**（技能：别断初始状态）——只在判定**变化**时留一行，既有证据又不刷屏
            BotLog.info("[Job] maintain 扫描判定 {} → {}（deficit={} 手里苗={} 区内可捡={} 清单={}）",
                    lastSweepDecision == null ? "-" : lastSweepDecision.name(), decision.name(),
                    deficit, saplingInInv, groundDrops.size(), state.effectivePickupItems(bot.getUUID()));
            lastSweepDecision = decision;
        }
        if (decision == SweepDecision.ENTER) {
            return startSweep(groundDrops);
        }
        if (decision == SweepDecision.NOTHING_TO_SWEEP) {
            // 只留一行**可行动**的提示（不是每轮刷屏）：欠树、没苗、地上也没有 ⇒ 如实走 tool_missing
            BotLog.info("[Job] maintain 欠树 deficit={} 手里没苗，且区内地面上**没有**清单内落物（清单={}）"
                            + "⇒ 不进扫描（绝不空转），按既有语义处理",
                    deficit, state.effectivePickupItems(bot.getUUID()));
        }

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
     * ⭐ `D-344` ③（用户 2026-09-19 裁定）：**进不进"扫地面"阶段**的四态判据。
     *
     * <p><b>为什么做成纯函数</b>：生产路径（{@code patrol()}）与夹具**共用同一份判定**
     * —— 夹具可以**零世界写入、零副作用**地断言整张判定表（技能 `alice-scene-based-testing`
     * §陷阱#6 的硬要求：判据提成纯函数，别让夹具去启动真任务）。
     *
     * <p>四态的语义（**顺序即优先级**，改顺序 = 改行为）：
     * <ol>
     *   <li>{@link #NO_DEFICIT}：不欠树 ⇒ 没有补种需求，扫它没意义；</li>
     *   <li>{@link #HAS_SAPLINGS}：**手里已经有苗** ⇒ 走既有"砍完立刻补"路径（细则⑥），
     *       不必扫地面（有苗还去扫 = 白跑）；</li>
     *   <li>{@link #NOTHING_TO_SWEEP}：欠树、手里没苗，但区内地面上**没有**清单内的落物
     *       ⇒ ⭐ **不进扫描** —— 这就是"**绝不空转**"的保证：「扫到捡完为止」**不等于**
     *       "没东西也扫"（`D-341` 口径：任务要如实失败，不能继续跑）；</li>
     *   <li>{@link #ENTER}：欠树 + 没苗 + 地面上确实有东西可捡 ⇒ 进扫描。</li>
     * </ol>
     *
     * @param deficit             欠树数（`> 0` 才可能要补种）
     * @param saplingInInventory  背包里**选定的那种**树苗件数
     * @param listDropsInRegion   区域内**清单内**的地面落物件数
     */
    public static SweepDecision sweepDecision(int deficit, int saplingInInventory, int listDropsInRegion) {
        if (deficit <= 0) {
            return SweepDecision.NO_DEFICIT;
        }
        if (saplingInInventory > 0) {
            return SweepDecision.HAS_SAPLINGS;
        }
        if (listDropsInRegion <= 0) {
            return SweepDecision.NOTHING_TO_SWEEP;
        }
        return SweepDecision.ENTER;
    }

    /** {@link #sweepDecision} 的四态。 */
    public enum SweepDecision {
        /** 进扫描：欠树 + 手里没苗 + 区内地面上有清单内落物。 */
        ENTER,
        /** 不欠树 ⇒ 不扫。 */
        NO_DEFICIT,
        /** 手里有苗 ⇒ 走既有"立刻补种"路径（细则⑥），不扫。 */
        HAS_SAPLINGS,
        /** 欠树但**地面上没有可捡的** ⇒ **不扫**（绝不空转；照旧走 `tool_missing` 如实失败）。 */
        NOTHING_TO_SWEEP
    }

    /**
     * ⭐ `D-341`：**区域内被"永久授权拒绝"的树**（`null` = 没有这类候选 ⇒ 该等就照旧等）。
     *
     * <p>判据 = `raw.rejected()` 里 ① 锚点**落在区域内**且竖直在有效上界内、② 理由是
     * {@link com.dddgn.alice.protection.ZoneAuthority#permanentDenial(String)}。命中 ⇒ 返回
     * `"<码> <逐树理由>"`（给 `failure` 用），无 ⇒ `null`。
     *
     * <p>**为什么不看 `viable`**：调用方只在 `inRegion.isEmpty()` 时问它 —— **"无权"与"没有"必须分开**：
     * 前者该如实失败，后者该照旧等生长（那是常驻作业的设计语义、也是用户要的"等窗口"）。
     */
    public static String permissionBlock(LumberRegionState.Region region, List<String> rejected,
                                         int effectiveTop) {
        List<String> hits = new ArrayList<>();
        String code = null;
        for (String entry : rejected) {
            if (entry == null) {
                continue;
            }
            int cut = entry.lastIndexOf(':');
            if (cut <= 0) {
                continue;
            }
            String reason = entry.substring(cut + 1);
            if (!com.dddgn.alice.protection.ZoneAuthority.permanentDenial(reason)) {
                continue;
            }
            net.minecraft.core.BlockPos anchor = parseRejectedAnchor(entry.substring(0, cut));
            if (anchor == null || !region.containsHorizontal(anchor)
                    || anchor.getY() < region.baseY() - 2 || anchor.getY() > effectiveTop) {
                continue;
            }
            if (code == null) {
                code = reason;                 // 首个命中的码 = 主因（扫描顺序确定 ⇒ 可复现）
            }
            hits.add(entry);
        }
        return hits.isEmpty() ? null : code + " " + String.join(" | ", hits);
    }

    /** 解析 `tree@12,64,8` 里的锚点（`LumberCandidateSource` 铸造的 `id` 格式）；不可解析 ⇒ `null`。 */
    private static net.minecraft.core.BlockPos parseRejectedAnchor(String id) {
        int at = id.lastIndexOf('@');
        if (at < 0 || at == id.length() - 1) {
            return null;
        }
        String[] parts = id.substring(at + 1).split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new net.minecraft.core.BlockPos(Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
        } catch (NumberFormatException exception) {
            return null;
        }
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
        // D-326：第三方保护层（补种也是世界底层写入 ⇒ FTB 认领看不见它）—— 被拒就**不写世界**、
        // 把待补种项留着（与"够不着"同一个诚实语义：宁可不做，也不越过别人的闸门）。
        String thirdParty = com.dddgn.alice.protection.ThirdPartyProtection.refusalReason(bot, spot);
        if (thirdParty != null) {
            BotLog.warn("[WRITE-REFUSED] plant pos={} by={} reason={}（本轮不写世界、保留待补种）",
                    spot.toShortString(), grant.describe(), thirdParty);
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
        finishedChildNode = com.dddgn.alice.task.TaskNode.finished("LumberJob(inner)",
                base.toShortString(), "HARVEST", ticks, current.progressSummary(), current, status,
                current.subTasks());
        current = null;
        return com.dddgn.alice.task.Task.Status.RUNNING;
    }

    // ==================== 扫描地面（`D-344` ③）====================

    /**
     * **区域内、清单内的地面落物**（{@link #sweepDecision} 的输入，也是扫描的目标集合）。
     *
     * <p><b>几何前提</b>（技能 `alice-scene-based-testing` §6.9.1 ①：测量盒以谁为中心、多大，
     * 必须写下来而不是心里假设）：范围 = 作业区域的**水平范围**（`minX..maxX` × `minZ..maxZ`）
     * + 竖直 `baseY-2 … baseY+maxHeight+1` —— 与 `patrol()` 判"树算不算在区内"用的是**同一个**
     * `region` 对象 ⇒ "扫描范围"与"作业范围"**不会分叉**（不另写一份几何）。
     *
     * <p>清单为空 ⇒ **直接返回空**：清单为空意味着"用户没说要捡什么" ⇒ **不猜**。
     */
    private List<net.minecraft.world.entity.item.ItemEntity> listDropsInRegion(LumberRegionState state) {
        Set<String> wanted = new LinkedHashSet<>(state.effectivePickupItems(bot.getUUID()));
        if (wanted.isEmpty()) {
            return List.of();
        }
        var level = bot.serverLevel();
        var box = new net.minecraft.world.phys.AABB(
                region.minX(), region.baseY() - 2, region.minZ(),
                region.maxX() + 1, region.baseY() + region.maxHeight() + 2, region.maxZ() + 1);
        List<net.minecraft.world.entity.item.ItemEntity> hits = new ArrayList<>();
        for (var item : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box)) {
            if (item.getItem().isEmpty()) {
                continue;
            }
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(item.getItem().getItem()).toString();
            if (wanted.contains(id)) {
                hits.add(item);
            }
        }
        return hits;
    }

    /** 背包里**选定的那种**树苗件数（与 `tryPlant` 找槽位的判据同源：`stack.is(item)`）。 */
    private int saplingInInventoryCount(LumberRegionState state) {
        var item = selectedSaplingItem(state);
        if (item == null) {
            return 0;
        }
        var inventory = bot.getInventory();
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 用户选定的树苗物品（`null` = 未配置或注册名无效）。 */
    private net.minecraft.world.item.Item selectedSaplingItem(LumberRegionState state) {
        String itemId = state.saplingItem(bot.getUUID());
        if (itemId == null) {
            return null;
        }
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(new net.minecraft.resources.ResourceLocation(itemId));
        return item == net.minecraft.world.item.Items.AIR ? null : item;
    }

    /**
     * **开始扫描**：签发**短期**收集授权 + 派一个 `CollectDropsTask`。
     *
     * <p>为什么**复用** `CollectDropsTask` 而不是新写拾取器：它是已验证的"走到落物旁收干净"的扫尾器
     * （建簇 / 重锚 / 等 pickupDelay / 原版拾取），新写一套 = 重演"六份重复"的教训。
     * 传 `allowWorldModification=false` ⇒ **只走"走过去 + 原版拾取"**，不挖不垫（不引入新写入面）。
     */
    private com.dddgn.alice.task.Task.Status startSweep(
            List<net.minecraft.world.entity.item.ItemEntity> targets) {
        if (targets.isEmpty()) {
            // 调用方刚数过 > 0；这里只做**防御**：不让"空目标"变成一次空转
            return com.dddgn.alice.task.Task.Status.RUNNING;
        }
        var level = bot.serverLevel();
        // ⭐ ① 裁定：**只在扫地面期间**放宽（`SESSION` = 纯内存、不持久化；TTL 兜底 + 结束主动撤销）
        var grant = com.dddgn.alice.decision.CollectGrants.add(level.getServer(),
                region.minX(), region.minZ(), region.maxX(), region.maxZ(),
                com.dddgn.alice.decision.PermissionGate.Scope.SESSION, "region_lumber", SWEEP_GRANT_TICKS);
        sweepGrantId = grant.id();
        // 起始锚点 = 离 bot **最近**的那件；其余交给 `CollectDropsTask` 自己建簇/重锚
        net.minecraft.core.BlockPos foot = com.dddgn.alice.pathing.MovementHelper.footCell(level, bot);
        List<UUID> ids = new ArrayList<>();
        net.minecraft.world.entity.item.ItemEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (var item : targets) {
            ids.add(item.getUUID());
            double distance = item.blockPosition().distSqr(foot);
            if (distance < best) {
                best = distance;
                nearest = item;
            }
        }
        // 预算**随落物数缩放**（`suggestedSweepTicks` 是"按实测代价反推"的口径），**不人为封顶**
        // ⇒ 细则③「一次不设上限、扫到区域内捡完为止」。
        int budget = com.dddgn.alice.task.CollectDropsTask.suggestedSweepTicks(targets.size(), null);
        sweepTask = new com.dddgn.alice.task.CollectDropsTask(bot, nearest.blockPosition(), scope,
                ids, false, budget);
        sweepTargets = targets.size();
        BotLog.info("[Job] maintain sweep 开始 目标={} 清单={} 预算={} tick（按落物数缩放，不设人为上限）",
                targets.size(), LumberRegionState.get(bot.getServer()).effectivePickupItems(bot.getUUID()),
                budget);
        return com.dddgn.alice.task.Task.Status.RUNNING;
    }

    /**
     * **扫描阶段**（每 tick）：跑子任务；终态时**记账 + 撤销授权 + 相位前进**。
     *
     * <p>⚠️ "相位前进"是硬要求（技能「夹具相位状态机」：每段处理完必须前进，
     * 否则下一 tick 又进同一分支 ⇒ 表现为静默空转/TIMEOUT 而不是断言失败）。
     */
    private com.dddgn.alice.task.Task.Status sweep() {
        var status = sweepTask.tick();
        if (status == com.dddgn.alice.task.Task.Status.RUNNING) {
            return com.dddgn.alice.task.Task.Status.RUNNING;
        }
        var state = LumberRegionState.get(bot.getServer());
        int collected = sweepTask.collected();
        int left = listDropsInRegion(state).size();
        String reason = sweepTask.terminalReason();
        BotLog.info("[Job] maintain sweep 结束 status={} 目标={} 实际入包={} 区内剩余={} reason={}",
                status, sweepTargets, collected, left, reason);
        // ⚠️ 顺序有意：**先撤销授权、再分类**。分类要回答的是"**在没有放宽权限的世界里**这批东西是什么"
        // ⇒ `foreign=` = 权限问题（放宽了也拿不到），其余 = 是我方登记在册的、但我够不着/没走到。
        dropSweepGrant();
        // ③ 裁定（`N = 3`）：**零进展**计数 —— 有收获、或剩余变少，都算进展。
        boolean progress = collected > 0 || left < sweepTargets;
        if (progress) {
            sweepNoProgress = 0;
        } else if (++sweepNoProgress >= SWEEP_NO_PROGRESS_LIMIT) {
            terminalReason = "sweep_no_progress";
            failure = terminalReason + "(" + classifySweepBlockers(state) + ") 连续 " + sweepNoProgress
                    + " 轮扫描零进展（区内剩余 " + left + " 件，清单="
                    + state.effectivePickupItems(bot.getUUID()) + "）";
            BotLog.warn("[Job] maintain {}", failure);
            tell("区域里有东西可捡、但我**捡不到**（" + failure + "）—— 如实收工，不再空转");
            sweepTask = null;
            sweepTargets = 0;
            return finish(com.dddgn.alice.task.Task.Status.FAILED);
        }
        sweepTask = null;      // 相位前进
        sweepTargets = 0;
        return com.dddgn.alice.task.Task.Status.RUNNING;
    }

    /**
     * **"捡不到"的原因分类**（`D-344` ③ 裁定的 `foreign=` / `unreachable=` 后缀）。
     *
     * <p>⚠️ 必须在**撤销扫描授权之后**调用：它回答的是"**在没有放宽权限的世界里**，这批东西算什么"
     * —— `FOREIGN` = 权限问题（授权了也拿不到），其余（我方登记在册的）= "是我的，但我够不着"。
     */
    private String classifySweepBlockers(LumberRegionState state) {
        int foreign = 0;
        int unreachable = 0;
        for (var item : listDropsInRegion(state)) {
            if (com.dddgn.alice.decision.DropPolicy.effectiveProvenance(bot, item)
                    == com.dddgn.alice.decision.DropPolicy.Provenance.FOREIGN) {
                foreign++;
            } else {
                unreachable++;
            }
        }
        return "foreign=" + foreign + " unreachable=" + unreachable;
    }

    /** 撤销本轮扫描签发的收集授权（没有在飞的授权时是空操作）。 */
    private void dropSweepGrant() {
        if (sweepGrantId == null) {
            return;
        }
        boolean revoked = com.dddgn.alice.decision.CollectGrants.revoke(sweepGrantId);
        BotLog.info("[Job] maintain sweep 撤销收集授权 {}（revoked={}）", sweepGrantId, revoked);
        sweepGrantId = null;
    }

    /** **刚结束的子任务节点**（M4b）：内层 Job 置空后仍让树里看得见"哪个子阶段失败"。 */
    private com.dddgn.alice.task.TaskNode finishedChildNode;

    private com.dddgn.alice.task.Task.Status finish(com.dddgn.alice.task.Task.Status status) {
        terminated = true;
        bot.controller().stopMovement();
        // ⭐ `D-344`：终态/失败路径也要收掉扫描子任务与它签发的收集授权
        // （技能 §6.9.2：**失败必清理** —— 扫到一半失败不许把"区内拾取放宽"留在世上）
        dropSweepGrant();
        sweepTask = null;
        sweepTargets = 0;
        // **取消任务 ⇒ 自动解除任务区**（D-338 附注二第 2 条）：任务区内所有区块的授权都由任务持有，
        // 任务一结束就没有持有者了 ⇒ 就地解除，玩家不需要再点一次。
        // （`/alice region stop` 那种**不经 finish** 的显式打断走的是作用域收尾钩子
        //  `TaskZoneRegistry.release(closedScope)`；两条路都堵住。）
        TaskZoneRegistry.release(taskZoneScope);
        BotLog.info("[Job] maintain SUMMARY region={} chopped={} failed={} patrols={} mySaplings={}"
                        + " planted={} baseline={} saplingItem={} zone={} reason={} → {}",
                region.describe(), treesChopped, treesFailed,
                LumberRegionState.get(bot.getServer()).patrols(bot.getUUID()),
                LumberRegionState.get(bot.getServer()).mySaplingCount(bot.getUUID()),
                LumberRegionState.get(bot.getServer()).saplingsPlanted(bot.getUUID()),
                LumberRegionState.get(bot.getServer()).baselineTrees(bot.getUUID()),
                String.valueOf(LumberRegionState.get(bot.getServer()).saplingItem(bot.getUUID())),
                taskZoneStatus, terminalReason, status);
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
