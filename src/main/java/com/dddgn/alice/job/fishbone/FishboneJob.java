package com.dddgn.alice.job.fishbone;

import com.dddgn.alice.action.MineBlockRunner;
import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.job.Job;
import com.dddgn.alice.job.mine.MineCandidateSource;
import com.dddgn.alice.job.mine.MineProductFilter;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.search.PlanningStatus;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.task.CollectDropsTask;
import com.dddgn.alice.task.MineTask;
import com.dddgn.alice.task.PathRetryRunner;
import com.dddgn.alice.task.Task;
import com.dddgn.alice.task.TaskTarget;
import com.dddgn.alice.task.mining.MiningBudget;
import com.dddgn.alice.task.mining.MiningProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * **鱼骨挖矿作业（`L3`；`D-386` 切片 1 = 主巷 + 返回，`D-437` 切片 2 = 支巷 + 顺手挖 + 收集）**。
 *
 * <p>设计原文 = {@code docs/plans/2026-09-21-鱼骨挖矿计划.md} §3（状态机 + 逐格动作）：
 * <ul>
 *   <li><b>切片 1</b>（计划 §7-1）：{@code PREPARE → EXCAVATE → RETURN → DONE}，
 *       判据 <b>`C1`（模板 = 事实）/ `C3`（搜索规模恒定 + 零 `SEARCH_LIMIT`）/ `C4`（可返回）/
 *       `C6`（诚实失败）</b>；</li>
 *   <li>⭐ <b>切片 2</b>（计划 §7-2）：{@code SPUR_RETURN / IN_PLACE / COLLECT} 三个相位，
 *       判据 <b>`C1`（含支巷）/ `C2`（露头矿进包）/ `C5`（不越界、不连锁）</b>。</li>
 * </ul>
 *
 * <h2>分层（本项目的红线，不是风格问题）</h2>
 * <ul>
 *   <li><b>L3（本类）只决策/记账/终止</b>：取下一个模板单元、把终态理由说清楚；</li>
 *   <li><b>L2 只复用已验收的链路</b>：每格 = 一个 {@link MineTask}（`DIRECT` 模式，站位就在身后
 *       ⇒ **规划距离恒 1 格**），返回 / 支巷退路 = {@link PathRetryRunner}（`PathRequest.of` = **纯通行**，
 *       `D-076`），收集 = {@link CollectDropsTask}；</li>
 *   <li>⚠️ **本类不新增 Movement、不改成本模型、不调 `planTunnel`**（计划 §1「明确不做」——
 *       搜索型规划正是鱼骨要绕开的东西）。</li>
 * </ul>
 *
 * <h2>逐格动作的精确顺序（计划 §3，防"把自己埋了"）</h2>
 * <ol>
 *   <li>先挖**前方脚位格**（站位 = 身后那格）；</li>
 *   <li>再挖**前方头位格**（净高 2 ⇒ 只这两格）；</li>
 *   <li>然后**走进刚挖出来那格** —— 由**下一个单元的 `MineTask`** 承担（它的站位候选恰好包含
 *       "我现在站的格" ⇒ 规划距离 1）；</li>
 *   <li>**脚位格下方不动**（不挖地板）⇒ 不会把自己挖穿。</li>
 * </ol>
 *
 * <h2>⭐ 切片 2 的三条纪律（计划 §10.1 / §10.2，用户 2026-09-21 裁定）</h2>
 * <ol>
 *   <li><b>支巷放弃 ≠ 主巷失败</b>（§10.2）：支巷单元挖不动 ⇒ 记 {@code spur_abandoned:<码>}、
 *       **跳过该支巷剩余单元**、沿支巷**原路退回主巷继续**；主巷单元挖不动 ⇒ {@code main_blocked:<码>}
 *       + 终态 FAILED + **先沿主巷返回起点**。这两档**不许合并**（合并过一次就会被真机归因骗到）。</li>
 *   <li><b>露头矿顺手挖</b>（§10.1）：只在**每个单元挖完的那一 tick** 扫**该单元自己的 6 邻域**
 *       （O(6×净高)/单元，**不全局扫、不周期扫**）；三条同时成立才算：① 是矿（
 *       {@link MineCandidateSource.Target}，不新增第二份矿物清单）② 是**本次作业挖出来的**暴露面
 *       （6 邻域里有一格是模板格**且现在已通行**）③ **顺手**（视线通 + 触及，与 `D-365` 就地挖
 *       **同一对谓词** {@link MineBlockRunner#inPlaceReachable}）。⇒ **不许"走去挖"**：
 *       消费前用同一对谓词复检，不达标就 {@code ore_deferred} 并丢弃（那是"跟随/探洞"的事，不是鱼骨）。</li>
 *   <li><b>收集半径从模板推导</b>（`D-346` 的教训："追取上限 &lt; 作业直径 ⇒ 判据永不成立"）：
 *       `prepares` 里已经把作用域半径设成 {@link FishboneTemplate#scopeRadius()}
 *       （含支巷），而 {@code CollectDropsTask.chaseLimit()} 是 `max(32, 2×作用域半径)` ⇒ **自动覆盖全作业面**。</li>
 * </ol>
 *
 * <h2>终态词表（`terminalReason()` 的取值）</h2>
 * <table border="1">
 *   <tr><th>码</th><th>含义</th><th>状态</th></tr>
 *   <tr><td>{@code template_complete}</td><td>模板全部挖穿并回到起点</td><td>DONE</td></tr>
 *   <tr><td>⭐ {@code template_complete_spurs_abandoned=<n>}</td><td>主巷做完、**有 `<n>` 条支巷被放弃**
 *       （§10.2 的"子巷放弃"档）—— 与 {@code template_complete} **分开**，否则决策层只看终态会以为"全挖完了"</td><td>DONE</td></tr>
 *   <tr><td>{@code start_unreachable}</td><td>起点不可站 <b>或</b> 纯通行规划证明不可达</td><td>FAILED（**零世界改动**）</td></tr>
 *   <tr><td>{@code start_search_incomplete}</td><td>起点可达性**没搜完**（`SEARCH_LIMIT`）—— `SEARCH_LIMIT ≠ UNREACHABLE`（`D-076`）</td><td>FAILED（**零世界改动**）</td></tr>
 *   <tr><td>{@code main_blocked:&lt;码&gt;</td><td>**主巷**前方**硬拒绝**（基岩/保护区/认领区/岩浆…）—— 归因码逐字取 `MineTask.isHardTargetRefusal` 那一族</td><td>FAILED（先返回再失败）</td></tr>
 *   <tr><td>{@code main_unreachable:&lt;码&gt;</td><td>**主巷**前方**挖得到但到不了**（占位/进入方案规划不出）</td><td>FAILED（先返回再失败）</td></tr>
 *   <tr><td>{@code spur_return_failed:&lt;码&gt;</td><td>支巷挖到端点/放弃之后**回不到主巷**</td><td>FAILED（先回家再失败）</td></tr>
 *   <tr><td>{@code product_not_collected}</td><td>`COLLECT` 之后**产物没进包**（&lt; 露头矿数）——
 *       `D-436 §一.2` 的例外条款：落物可能掉出可达范围，此时**如实失败**，不假装完成</td><td>FAILED（先返回再失败）</td></tr>
 *   <tr><td>{@code no_progress}</td><td>`STALL_TICKS` 内一格没推进</td><td>FAILED</td></tr>
 *   <tr><td>{@code goal_timeout}</td><td>`maxTicks` 用尽</td><td>FAILED</td></tr>
 *   <tr><td>{@code return_failed}</td><td>作业本身完成/失败之后**回不到起点**（不许静默留在洞里，`D-327` 同宗）</td><td>FAILED</td></tr>
 * </table>
 *
 * <p>⭐ **为什么"失败也要先回家"**：`§10.2` 逐字裁定「主巷遇不可挖/液体 ⇒ 报告 + 如实失败，
 * **且优先沿已挖通的主巷返回起点**（`D-327` 安全返回的思想：先回家再失败，不许把 bot 留在洞里）」。
 */
public final class FishboneJob implements Job {

    /** 停滞护栏：这么多 tick 一格没推进 ⇒ `no_progress`（不让作业悄悄空转）。 */
    public static final int STALL_TICKS = 400;

    /** 返回段/支巷退路段的段数上限（`PathRetryRunner` 的重规划次数；与其它调用点同口径）。 */
    private static final int RETURN_MAX_REPLANS = 2;

    /** 全模板挖完的终态（切片 1 口径）。 */
    public static final String TEMPLATE_COMPLETE = "template_complete";

    /** 支巷放弃的日志码前缀（`§10.2` 的注册码；终态里出现 `=` 计数版本）。 */
    public static final String SPUR_ABANDONED_PREFIX = "spur_abandoned:";

    /**
     * **"是不是矿"的唯一来源**（计划 §10.1 条件①：**不新增第二份矿物清单**）——
     * 复用生产谓词 {@link MineCandidateSource.Target}，取值 = Forge 的 `#forge:ores` 标签族
     * （模组矿物天然覆盖；与 {@link MineProductFilter} 的"产物"口径配套但**不是**同一件事：
     * 那个认掉落物，这个认世界里的方块）。
     */
    public static final MineCandidateSource.Target DEFAULT_ORE_TARGET =
            MineCandidateSource.Target.ofTag(TagKey.create(Registries.BLOCK,
                    new ResourceLocation("forge", "ores")));

    private enum Phase { PREPARE, EXCAVATE, SPUR_RETURN, IN_PLACE, COLLECT, RETURN, DONE }

    private final BotPlayer bot;
    private final FishboneTemplate template;
    private final ScopeBuffer scope;
    private final WriteGrant grant;
    private final int maxTicks;
    private final List<FishboneTemplate.Unit> units;

    private Phase phase = Phase.PREPARE;
    private MineTask current;
    private CollectDropsTask collector;
    private PathRetryRunner returnRunner;
    private PathRetryRunner spurReturnRunner;

    /** 下一个待处理的**单元**下标（`units` 的口径）。 */
    private int unitIndex;
    /** 当前单元里下一个待处理的格（0..`height-1`）。 */
    private int cellInUnit;
    private int advanced;           // 已完整处理（挖穿/本来就通）的单元数（不含被放弃的支巷单元）
    private int skipped;            // 本来就通行的格（不重复挖）
    private int mined;              // 我方真的挖掉的**模板**格数
    private int ticks;
    private int stallTicks;
    private int lastProgressMark = -1;
    private boolean excavationFailed;
    private String terminalReason = "";
    private Task.Status terminalStatus = Task.Status.FAILED;

    // ---- 切片 2：支巷 ----
    private int spursAbandoned;
    private int spurUnitsSkipped;
    private int spurReturns;
    private String firstSpurAbandonReason = "";
    private BlockPos spurReturnTarget;
    private FishboneTemplate.Unit pendingSpurReturn;

    // ---- 切片 2：露头矿顺手挖 ----
    private final Deque<BlockPos> oreQueue = new ArrayDeque<>();
    private final Set<BlockPos> oreSeen = new LinkedHashSet<>();
    private MineTask oreTask;
    private BlockPos oreStartFeet;
    /** 本颗露头矿的**破坏是否已完成**（用于"位移"只在破坏那一刻量；见 {@link #inPlace()}）。 */
    private boolean oreBroken;
    private int scannedUnits;
    private int oreFound;
    private int oreMined;
    private int oreDeferred;
    private int oreWalkedAway;

    // ---- 切片 2：收集 ----
    private int itemsBefore;
    private int collectedProducts;

    public FishboneJob(BotPlayer bot, FishboneTemplate template, ScopeBuffer scope, int maxTicks) {
        this.bot = Objects.requireNonNull(bot, "bot");
        this.template = Objects.requireNonNull(template, "template");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.maxTicks = maxTicks;
        this.units = template.units();
        // 世界写入授权（`D-082`）：鱼骨挖的是**模板几何列出的格** + **本次作业自己挖出来的暴露面矿**，
        // 不是"随手挖掉的阻挡" ⇒ 一次性授权即可，理由码复用 `BULK_EDIT`
        //（"批量地形编辑（道路施工等非寻路场景）"）—— 计划 §4 明确**不新增枚举值**。
        this.grant = WriteGrant.of(REQUESTER, WriteReason.BULK_EDIT);
        // ⚠️ **产物基线必须在构造时取**（与 `MineJob` 同口径）：掉落物常常在**挖掉那一 tick 就被玩家
        // 捡起**（脚下就是掉落点），等到 `COLLECT` 相位再取基线 ⇒ 增量恒为 0（首版实测：
        // `COLLECT 开始 … 产物=3` ⇒ `产物=+0`，而背包里明明躺着 3 个原铁）。
        this.itemsBefore = countProductItems();
    }

    /** 世界写入的 requester（`WritePolicyMatrix.PREFIX_RULES` 里登记为 `MINING` 类）。 */
    public static final String REQUESTER = "fishbone";

    // ==================== Job 契约 ====================

    @Override
    public String jobName() {
        return REQUESTER;
    }

    @Override
    public String terminalReason() {
        return terminalReason;
    }

    @Override
    public String failureReason() {
        return terminalStatus == Task.Status.FAILED ? terminalReason : "";
    }

    @Override
    public String progressSummary() {
        return "advance=" + advanced + "/" + units.size()
                + " mined=" + mined + " skipped=" + skipped
                + " spurReturns=" + spurReturns + " spursAbandoned=" + spursAbandoned
                + " ore=" + oreMined + "/" + oreFound + " phase=" + phase;
    }

    /** 目标高亮：指向当前正在处理的模板格（没有就指起点）。 */
    @Override
    public TaskTarget target() {
        BlockPos cell = unitIndex < units.size() ? units.get(unitIndex).foot() : template.startFoot();
        return TaskTarget.block(cell);
    }

    @Override
    public Task.Status tick() {
        ticks++;
        if (ticks > maxTicks && phase != Phase.DONE) {
            // 预算用尽也必须**先回家**（同 §10.2 的纪律），除非当前已经在返回段。
            if (phase != Phase.RETURN) {
                terminalReason = "goal_timeout";
                excavationFailed = true;
                beginReturn();
            }
        }
        return switch (phase) {
            case PREPARE -> prepare();
            case EXCAVATE -> excavate();
            case SPUR_RETURN -> spurReturn();
            case IN_PLACE -> inPlace();
            case COLLECT -> collectPhase();
            case RETURN -> returnPhase();
            case DONE -> terminalStatus;
        };
    }

    // ==================== PREPARE ====================

    /**
     * 起点合法性 + 生成模板单元序列 + 开作业作用域（计划 §3 `PREPARE`）。
     *
     * <p>⚠️ **两条判据必须分开**（`D-076`）：`UNREACHABLE`（真到不了）与 `SEARCH_LIMIT`（**没搜完**）
     * 是不同的事实 ⇒ 不许把它们压成同一个终态理由。
     */
    private Task.Status prepare() {
        ServerLevel level = bot.serverLevel();
        BlockPos start = template.startFoot();
        if (!MovementHelper.canStandCentered(level, start)) {
            BotLog.warn("[Fishbone] start_unreachable 起点不可站 start={}（零世界改动）", start.toShortString());
            return finishFail("start_unreachable");
        }
        PathPlan plan = new CorePathPlanner().plan(bot, level,
                PathRequest.of(bot.getUUID().toString(), bot.blockPosition(), start, REQUESTER + "-prepare"));
        if (!plan.reached()) {
            String code = plan.status() == PlanningStatus.SEARCH_LIMIT
                    ? "start_search_incomplete" : "start_unreachable";
            BotLog.warn("[Fishbone] {} 起点可达性判定 status={} feet={} start={}（零世界改动）",
                    code, plan.status(), bot.blockPosition().toShortString(), start.toShortString());
            return finishFail(code);
        }
        // ⭐ 切片 2：作用域半径**从模板推导**（含支巷）—— `CollectDropsTask` 的追取上限
        // 是 `max(32, 2×本半径)` ⇒ 这一行就是 `C2` 能成立的前提（`D-346` 的教训）。
        scope.begin(start, template.scopeRadius(), bot.getUUID());
        BotLog.info("[Fishbone] start template={} scopeRadius={} maxTicks={}",
                template.describe(), template.scopeRadius(), maxTicks);
        phase = Phase.EXCAVATE;
        return Task.Status.RUNNING;
    }

    // ==================== EXCAVATE ====================

    private Task.Status excavate() {
        if (unitIndex >= units.size()) {
            startCollect();
            return Task.Status.RUNNING;
        }
        FishboneTemplate.Unit unit = units.get(unitIndex);
        ServerLevel level = bot.serverLevel();

        if (current == null) {
            if (cellInUnit >= template.height()) {
                finishUnit(unit);
                return Task.Status.RUNNING;
            }
            BlockPos cell = unit.foot().above(cellInUnit);
            if (isAlreadyPassable(level, cell)) {
                skipped++;
                cellInUnit++;
                return Task.Status.RUNNING;
            }
            BotLog.info("[Fishbone] cell unit={}/{} cell={}/{} target={} spur={}",
                    unitIndex + 1, units.size(), cellInUnit + 1, template.height(),
                    cell.toShortString(), unit.isSpur() ? unit.spurDir().getName() + unit.spurStep() : "-");
            current = new MineTask(bot, cell, scope,
                    MiningBudget.forTarget(bot, level, cell, true),
                    // 站位只用**现成可站**的格：鱼骨的站位永远在身后一格 ⇒ 不需要规划器自己挖隧道
                    //（`STANDABLE_ONLY` = 计划 §5 方案 A 的"每格 1 次、距离恒 1 格"）。
                    MiningProfile.STANDABLE_ONLY, grant);
            return Task.Status.RUNNING;
        }

        Task.Status status = current.tick();
        if (status == Task.Status.RUNNING) {
            stallGuard();
            return Task.Status.RUNNING;
        }
        if (status == Task.Status.DONE) {
            mined++;
            current = null;
            cellInUnit++;
            stallTicks = 0;
            return Task.Status.RUNNING;
        }
        // ⚠️ 失败：**先分"主巷 / 支巷"两档**（`§10.2` 用户裁定），再按 `MineTask` 自己的硬拒绝名单归因
        //（`J-6`：拒绝清单只许有一处定义）。
        String reason = current.failureReason();
        current = null;
        if (unit.isSpur()) {
            abandonSpur(unit, reason);
            return Task.Status.RUNNING;
        }
        String code = (MineTask.isHardTargetRefusal(reason)
                ? "main_blocked:" : "main_unreachable:") + reason;
        BotLog.warn("[Fishbone] {} 主巷单元 {} 挖不动 target={} reason={} ⇒ 先沿主巷返回起点再失败",
                code, unitIndex + 1, unit.foot().toShortString(), reason);
        terminalReason = code;
        excavationFailed = true;
        beginReturn();
        return Task.Status.RUNNING;
    }

    /**
     * 一个单元处理完（挖穿 / 本来就通）：推进下标 ⇒ ⭐ 扫露头矿 ⇒ 该退回主巷就先退。
     *
     * <p>顺序**逐字**按计划 §10.1：「扫到就进顺手挖队列，**在下一个推进动作之前**逐个消费」。
     */
    private void finishUnit(FishboneTemplate.Unit unit) {
        advanced++;
        scanForOre(unit);
        boolean lastOfSpur = unit.isSpur() && unit.spurStep() == template.spurLength();
        unitIndex++;
        cellInUnit = 0;
        stallTicks = 0;
        if (!oreQueue.isEmpty()) {
            // 顺手挖排在"下一个推进动作"之前；支巷退路**记着**，等队列空了再做（`IN_PLACE` 出口处理）。
            pendingSpurReturn = lastOfSpur ? unit : null;
            oreTask = null;
            phase = Phase.IN_PLACE;
            return;
        }
        if (lastOfSpur) {
            beginSpurReturn(unit);
        }
    }

    /** 停滞护栏（`no_progress`）：**按"有没有推进"记账**，不按"tick 有没有跑"。 */
    private void stallGuard() {
        int mark = unitIndex * 10000 + cellInUnit * 1000 + mined + skipped;
        if (mark != lastProgressMark) {
            lastProgressMark = mark;
            stallTicks = 0;
            return;
        }
        if (++stallTicks > STALL_TICKS) {
            BotLog.warn("[Fishbone] no_progress 连续 {} tick 没推进（unit={}/{} cell={} mined={}）⇒ 如实失败",
                    stallTicks, unitIndex + 1, units.size(), cellInUnit, mined);
            terminalReason = "no_progress";
            excavationFailed = true;
            beginReturn();
        }
    }

    /**
     * 一格是否**已经可通行**（跳过、不重复挖）。
     *
     * <p>用 `bodyPassable` 而不是"是不是空气"：1 格高的洞、水、草这类**非空气但可通行**的格也算通
     *（判据 C1 只要求"模板格为空气"，但**跳过**的语义是"不用挖"）⇒ 两件事分开表达，别混。
     */
    private boolean isAlreadyPassable(ServerLevel level, BlockPos cell) {
        return MovementHelper.bodyPassable(level, cell);
    }

    // ==================== 切片 2：支巷（§10.2 两档处置） ====================

    /**
     * **支巷放弃**（§10.2 用户裁定）：记 `spur_abandoned:<码>`、**跳过该支巷剩余单元**、
     * 沿支巷**原路退回主巷继续**。
     *
     * <p>⚠️ **不判任务失败**：这正是"子巷放弃 / 主巷如实失败"两档的分界；终态由 {@code spursAbandoned}
     * 决定（见 {@link #returnPhase()}）⇒ 决策层**看得出**"主巷做完了但有支巷被放弃"。
     *
     * <p>⚠️ **已知简化**：本片对**任何**理由都放弃该支巷（含暂时性的 `search_incomplete`）——
     * 保守且如实（理由码原样上报）；若真机出现"支巷因暂时性理由被放弃"，再加"先重试后放弃"。
     */
    private void abandonSpur(FishboneTemplate.Unit unit, String reason) {
        int skippedUnits = skipRestOfSpur(unit);
        spursAbandoned++;
        spurUnitsSkipped += skippedUnits;
        if (firstSpurAbandonReason.isEmpty()) {
            firstSpurAbandonReason = reason;
        }
        BotLog.warn("[Fishbone] {}{} 支巷（主巷单元 {}，方向 {}，第 {} 格）放弃剩余 {} 个单元 ⇒ 退回主巷继续"
                        + "（§10.2：子巷放弃 / 主巷失败两档，不许合并）",
                SPUR_ABANDONED_PREFIX, reason, unit.mainUnit(),
                unit.spurDir() == null ? "-" : unit.spurDir().getName(), unit.spurStep(), skippedUnits);
        cellInUnit = 0;
        oreQueue.clear();       // 这条支巷里扫到的候选矿随支巷一起放弃（不许"走去挖"）
        beginSpurReturn(unit);
    }

    /** 跳过当前支巷的剩余单元（含当前这个没挖完的）；返回跳过个数。 */
    private int skipRestOfSpur(FishboneTemplate.Unit unit) {
        int n = 0;
        while (unitIndex < units.size()) {
            FishboneTemplate.Unit u = units.get(unitIndex);
            if (!u.isSpur() || u.mainUnit() != unit.mainUnit() || u.spurDir() != unit.spurDir()) {
                break;
            }
            unitIndex++;
            n++;
        }
        return n;
    }

    /** 支巷端点（或放弃点）⇒ **原路退回主巷**（纯通行、零破坏，计划 §3 `SPUR`）。 */
    private void beginSpurReturn(FishboneTemplate.Unit unit) {
        pendingSpurReturn = null;
        spurReturnTarget = template.footOf(unit.mainUnit());
        spurReturnRunner = null;
        phase = Phase.SPUR_RETURN;
    }

    private Task.Status spurReturn() {
        ServerLevel level = bot.serverLevel();
        if (spurReturnRunner == null) {
            if (MovementHelper.footCell(level, bot).equals(spurReturnTarget)) {
                spurReturns++;      // 放弃/退回点就是岔口本身 ⇒ 没有可走的段
                phase = Phase.EXCAVATE;
                return Task.Status.RUNNING;
            }
            spurReturnRunner = new PathRetryRunner(bot,
                    PathRequest.of(bot.getUUID().toString(), bot.blockPosition(), spurReturnTarget,
                            REQUESTER + "-spur"),
                    RETURN_MAX_REPLANS, REQUESTER + "-spur");
            BotLog.info("[Fishbone] SPUR_RETURN 原路退回主巷 feet={} → junction={}（纯通行，零破坏）",
                    bot.blockPosition().toShortString(), spurReturnTarget.toShortString());
        }
        PathRetryRunner.State state = spurReturnRunner.tick();
        if (state == PathRetryRunner.State.RUNNING) {
            return Task.Status.RUNNING;
        }
        boolean returned = state == PathRetryRunner.State.DONE && spurReturnRunner.result() != null
                && spurReturnRunner.result().completed();
        String code = spurReturnRunner.result() == null ? "no_result"
                : spurReturnRunner.result().failureCode();
        spurReturnRunner = null;
        if (!returned) {
            BotLog.warn("[Fishbone] spur_return_failed:{} feet={} junction={} ⇒ 如实失败（先回家）",
                    code, bot.blockPosition().toShortString(), spurReturnTarget.toShortString());
            terminalReason = "spur_return_failed:" + code;
            excavationFailed = true;
            beginReturn();
            return Task.Status.RUNNING;
        }
        spurReturns++;
        phase = Phase.EXCAVATE;
        return Task.Status.RUNNING;
    }

    // ==================== 切片 2：露头矿顺手挖（§10.1） ====================

    /**
     * ⭐ **露头矿判定的唯一真源**（计划 §10.1 三条件，全部**复用生产谓词**）。
     *
     * <p>为什么是 `public static`：夹具要能**逐条件隔离**地断言它（否则"①②③任一"只能靠行为臂间接验证）。
     * 它**不读任何 Job 状态** ⇒ 是纯谓词（同输入同输出），夹具可以喂"把 bot 传送走""换一份模板"这类
     * 真实输入把某一条单独打红。
     *
     * @param template 用来判条件②（暴露面是否**本次作业挖出来的**）
     */
    public static boolean opportunisticTarget(ServerLevel level, BotPlayer bot, BlockPos pos,
                                              FishboneTemplate template) {
        // ① 是矿（不新增第二份矿物清单）
        if (!DEFAULT_ORE_TARGET.matches(level.getBlockState(pos))) {
            return false;
        }
        // ② 是本次作业挖出来的暴露面：6 邻域里至少有一格**既是模板格、又已经通行**
        if (!exposedByOurTunnel(level, pos, template)) {
            return false;
        }
        // ③ 顺手 = 不必移动（与 `D-365` 就地挖**同一对谓词**：视线通 + 触及）
        return MineBlockRunner.inPlaceReachable(level, bot, pos);
    }

    /**
     * 条件②：该格 6 邻域里至少有一格是模板格**且现在已通行**（= 我们真的挖出来的面）。
     *
     * <p>⚠️ 判"这一格已经挖开"用的是**单格**通行 `canWalkThrough`，**不是** `bodyPassable`
     * （后者还要求"它上面那格也通"）—— 第一版用 `bodyPassable` 实测当场红：**顶棚矿**
     * （模板头位格的正上方那格）会让头位格的 `bodyPassable` 变 false，于是"我们自己挖出来的暴露面"
     * 反而判不出来（`ore_found=0`，2026-09-25 `fishbone_slice2` 首跑）。语义上正确的是
     * "这一格是空的了"（脚位格上方是矿时，脚位格照样是挖空的）。
     */
    public static boolean exposedByOurTunnel(ServerLevel level, BlockPos pos, FishboneTemplate template) {
        Set<BlockPos> cells = template.cellSet();
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (cells.contains(n) && MovementHelper.canWalkThrough(level, n)) {
                return true;
            }
        }
        return false;
    }

    /**
     * **扫一个单元的 6 邻域**（计划 §10.1：只在单元挖完那一 tick、只扫自己的邻域、不重复扫）。
     *
     * <p>代价 O(6 × 净高)/单元；`oreSeen` 保证每个候选格**只判一次**（跨单元重叠的邻格不再复判）。
     */
    private void scanForOre(FishboneTemplate.Unit unit) {
        ServerLevel level = bot.serverLevel();
        scannedUnits++;
        for (int dy = 0; dy < template.height(); dy++) {
            BlockPos cell = unit.foot().above(dy);
            for (Direction d : Direction.values()) {
                BlockPos candidate = cell.relative(d);
                if (!oreSeen.add(candidate)) {
                    continue;
                }
                if (opportunisticTarget(level, bot, candidate, template)) {
                    oreFound++;
                    oreQueue.add(candidate);
                    BotLog.info("[Fishbone] ore_found pos={}（单元 {}/{} 的暴露面；顺手挖队列 depth={}）",
                            candidate.toShortString(), unitIndex + 1, units.size(), oreQueue.size());
                }
            }
        }
    }

    /**
     * `IN_PLACE`：逐个消费顺手挖队列（计划 §10.1「在下一个推进动作之前」）。
     *
     * <p>⚠️ **不许走去挖**：消费前**再用同一对谓词复检**（站位可能已经变了）—— 不达标就
     * `ore_deferred` 并**丢弃**（那是"跟随/探洞"的事，不是鱼骨）。真的移动了也如实记账
     *（{@link #oreWalkedAway()} ⇒ 夹具断言必须为 0）。
     */
    private Task.Status inPlace() {
        if (oreQueue.isEmpty()) {
            if (pendingSpurReturn != null) {
                beginSpurReturn(pendingSpurReturn);
            } else {
                phase = Phase.EXCAVATE;
            }
            return Task.Status.RUNNING;
        }
        BlockPos ore = oreQueue.peek();
        ServerLevel level = bot.serverLevel();
        if (oreTask == null) {
            if (level.getBlockState(ore).isAir()) {
                oreQueue.poll();    // 还没轮到它就没了（连锁模组/上一格带掉）
                return Task.Status.RUNNING;
            }
            if (!opportunisticTarget(level, bot, ore, template)) {
                BotLog.info("[Fishbone] ore_deferred pos={}（复检不达标：站位/视线变了 ⇒ 不走去挖，丢弃）",
                        ore.toShortString());
                oreDeferred++;
                oreQueue.poll();
                return Task.Status.RUNNING;
            }
            oreStartFeet = MovementHelper.footCell(level, bot);
            oreBroken = false;
            oreTask = new MineTask(bot, ore, scope,
                    MiningBudget.forTarget(bot, level, ore, true),
                    MiningProfile.STANDABLE_ONLY, grant);
            return Task.Status.RUNNING;
        }
        // ⚠️⚠️ **有任务在跑就必须把它跑到终态**，哪怕目标**已经是空气**了 ——
        // `MineTask` 破坏完成后还有一段「收集掉落物」相位，那时目标必然是空气。
        // 首版把"目标已空气 ⇒ 提前出队"放在最前面，实测（2026-09-25 `fishbone_slice2` 首跑）
        // 把 3 个露头矿的 `MineTask` 全部腰斩在收集相位：产物**确实进了包**（夹具独立清点 3 个原铁），
        // 但 `oreMined=0`（账没记）。**"目标空了"不是"任务完了"** —— 这两件事在不同的层。
        Task.Status status = oreTask.tick();
        if (!oreBroken && level.getBlockState(ore).isAir()) {
            oreBroken = true;
            // ⭐ 只在**破坏完成的那一刻**量位移：那之前的位移 = "走去站位"（不合法，"顺手"要求不必移动），
            // 那之后的位移 = 捡自己刚挖下来的掉落物（合法，"顺手挖"本来就带收集）。
            if (!MovementHelper.footCell(level, bot).equals(oreStartFeet)) {
                oreWalkedAway++;
                BotLog.warn("[Fishbone] ore_moved 顺手挖把 bot 带离了原位 {} → {}（本不该发生）",
                        oreStartFeet.toShortString(), MovementHelper.footCell(level, bot).toShortString());
            }
        }
        if (status == Task.Status.RUNNING) {
            return Task.Status.RUNNING;
        }
        if (status == Task.Status.DONE) {
            oreMined++;
        } else {
            BotLog.info("[Fishbone] ore_deferred pos={} reason={}（顺手挖失败 ⇒ 不重试不追）",
                    ore.toShortString(), oreTask.failureReason());
            oreDeferred++;
        }
        oreTask = null;
        oreQueue.poll();
        return Task.Status.RUNNING;
    }

    // ==================== 切片 2：收集（C2） ====================

    /** 全模板处理完 ⇒ 起一次收集（半径已由 `prepare` 里的 `scopeRadius` 决定）。 */
    private void startCollect() {
        collector = new CollectDropsTask(bot, template.startFoot(), scope, List.of(), true);
        BotLog.info("[Fishbone] COLLECT 开始 origin={} scopeRadius={} oreMined={} 产物基线={}"
                        + "（半径从模板推导；追取上限 = max(32, 2×半径)，`D-346`）",
                template.startFoot().toShortString(), scope.currentRadius(), oreMined, itemsBefore);
        phase = Phase.COLLECT;
    }

    private Task.Status collectPhase() {
        Task.Status status = collector.tick();
        if (status == Task.Status.RUNNING) {
            return Task.Status.RUNNING;
        }
        collectedProducts = countProductItems() - itemsBefore;
        if (collectedProducts >= oreMined) {
            BotLog.info("[Fishbone] COLLECT 完成 产物=+{} 露头矿={}（`C2` 满足）", collectedProducts, oreMined);
            beginReturn();
            return Task.Status.RUNNING;
        }
        BotLog.warn("[Fishbone] product_not_collected 产物=+{} < 露头矿={} ⇒ 如实失败（先回家；"
                        + "`D-436 §一.2` 的例外条款：落物可能掉出可达范围）",
                collectedProducts, oreMined);
        terminalReason = "product_not_collected";
        excavationFailed = true;
        beginReturn();
        return Task.Status.RUNNING;
    }

    /** 背包里的**产物**（`forge:ores/*` + `raw_materials/*` + 原版兜底；与 `MineJob` 同口径）。 */
    private static final MineProductFilter PRODUCT_FILTER = MineProductFilter.forTag(null);

    private int countProductItems() {
        int total = 0;
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (PRODUCT_FILTER.matches(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    // ==================== RETURN ====================

    private void beginReturn() {
        phase = Phase.RETURN;
        returnRunner = null;
    }

    /**
     * 沿主巷**纯通行**返回起点（计划 §3 `RETURN`）。
     *
     * <p>主巷 = 自己挖出来的返回通道 ⇒ 天然可返回。⚠️ **这一段不许有任何世界写入**（判据 `C4`）；
     * 所以走 `PathRequest.of`（默认纯通行，`D-076`），不走 `miningApproach`。
     */
    private Task.Status returnPhase() {
        if (returnRunner == null) {
            returnRunner = new PathRetryRunner(bot,
                    PathRequest.of(bot.getUUID().toString(), bot.blockPosition(), template.startFoot(),
                            REQUESTER + "-return"),
                    RETURN_MAX_REPLANS, REQUESTER + "-return");
            BotLog.info("[Fishbone] RETURN 开始 feet={} → start={}（纯通行，零破坏）",
                    bot.blockPosition().toShortString(), template.startFoot().toShortString());
        }
        PathRetryRunner.State state = returnRunner.tick();
        if (state == PathRetryRunner.State.RUNNING) {
            return Task.Status.RUNNING;
        }
        boolean returned = state == PathRetryRunner.State.DONE && returnRunner.result() != null
                && returnRunner.result().completed();
        if (!returned) {
            String code = returnRunner.result() == null ? "no_result"
                    : returnRunner.result().failureCode();
            BotLog.warn("[Fishbone] return_failed code={} feet={}（前一段的结果：{}）",
                    code, bot.blockPosition().toShortString(),
                    terminalReason.isBlank() ? "作业尚未失败" : terminalReason);
            terminalReason = "return_failed";
            return finishFail("return_failed");
        }
        if (excavationFailed) {
            BotLog.warn("[Fishbone] 已安全返回起点，如实失败 reason={} progress={}",
                    terminalReason, progressSummary());
            return finishFail(terminalReason);
        }
        terminalStatus = Task.Status.DONE;
        // ⭐ 支巷被放弃时**换一个终态词**（`§10.2`）：只说 `template_complete` 会让决策层以为
        // "全挖完了" —— 而事实上主巷做完了、有 n 条支巷没挖（`C1` 的那部分不成立）。
        terminalReason = spursAbandoned == 0
                ? TEMPLATE_COMPLETE
                : TEMPLATE_COMPLETE + "_spurs_abandoned=" + spursAbandoned;
        BotLog.info("[Fishbone] {} progress={} ticks={}", terminalReason, progressSummary(), ticks);
        phase = Phase.DONE;
        return Task.Status.DONE;
    }

    // ==================== 收尾 ====================

    private Task.Status finishFail(String reason) {
        terminalReason = reason;
        terminalStatus = Task.Status.FAILED;
        phase = Phase.DONE;
        return Task.Status.FAILED;
    }

    // ==================== 夹具只读（判据 C1/C2/C3/C4/C5） ====================

    /** **夹具只读**：已完整处理的推进单元数（`C1`/`C3` 的分母）。 */
    public int advancedUnits() {
        return advanced;
    }

    /** **夹具只读**：模板**单元**总数（含支巷；= `template.advanceCells()`）。 */
    public int unitCount() {
        return units.size();
    }

    /** **夹具只读**：我方真的挖掉的**模板**格数。 */
    public int minedCells() {
        return mined;
    }

    /** **夹具只读**：本来就通行、被跳过的模板格数。 */
    public int skippedCells() {
        return skipped;
    }

    /** **夹具只读**：作业总 tick。 */
    public int elapsedTicks() {
        return ticks;
    }

    /** **夹具只读**：被放弃的支巷条数（`§10.2` 的"子巷放弃"档）。 */
    public int spursAbandoned() {
        return spursAbandoned;
    }

    /** **夹具只读**：随放弃的支巷一起跳过的单元数。 */
    public int spurUnitsSkipped() {
        return spurUnitsSkipped;
    }

    /** **夹具只读**：成功"原路退回主巷"的次数（判据：每条开了的支巷各一次）。 */
    public int spurReturns() {
        return spurReturns;
    }

    /** **夹具只读**：第一条被放弃支巷的原始理由码（终态词 `=n` 版本不带它 ⇒ 单独暴露）。 */
    public String firstSpurAbandonReason() {
        return firstSpurAbandonReason;
    }

    /** **夹具只读**：扫过 6 邻域的单元数（判据：== 处理完的单元数，不是"每 tick 全区域扫"）。 */
    public int scannedUnits() {
        return scannedUnits;
    }

    /** **夹具只读**：顺手挖队列里**入过队**的露头矿数。 */
    public int oreFound() {
        return oreFound;
    }

    /** **夹具只读**：真的挖掉并进包的露头矿数（`C2` 的分母）。 */
    public int oreMined() {
        return oreMined;
    }

    /** **夹具只读**：复检不达标 / 挖失败而**丢弃**的候选数（"不走去挖"的代价，如实记账）。 */
    public int oreDeferred() {
        return oreDeferred;
    }

    /** **夹具只读**：顺手挖**把 bot 带离原位**的次数 —— 必须为 0（计划 §10.1"顺手 = 不必移动"）。 */
    public int oreWalkedAway() {
        return oreWalkedAway;
    }

    /** **夹具只读**：收集段净增的**产物**件数（`C2` 的读数）。 */
    public int collectedProducts() {
        return collectedProducts;
    }
}
