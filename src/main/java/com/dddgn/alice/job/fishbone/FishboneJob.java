package com.dddgn.alice.job.fishbone;

import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.job.Job;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.search.PlanningStatus;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.task.MineTask;
import com.dddgn.alice.task.PathRetryRunner;
import com.dddgn.alice.task.Task;
import com.dddgn.alice.task.TaskTarget;
import com.dddgn.alice.task.mining.MiningBudget;
import com.dddgn.alice.task.mining.MiningProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Objects;

/**
 * **鱼骨挖矿作业（`L3`；`D-386` 切片 1 = 主巷 + 返回）**。
 *
 * <p>设计原文 = {@code docs/plans/2026-09-21-鱼骨挖矿计划.md} §3（状态机 + 逐格动作）；
 * 本类只实现**切片 1**（计划 §7-1）：`PREPARE → EXCAVATE → RETURN → DONE`，
 * 判据 **`C1`（模板 = 事实）/ `C3`（搜索规模恒定 + 零 `SEARCH_LIMIT`）/ `C4`（可返回）/
 * `C6`（诚实失败）**。
 *
 * <h2>分层（本项目的红线，不是风格问题）</h2>
 * <ul>
 *   <li><b>L3（本类）只决策/记账/终止</b>：取下一个模板格、把终态理由说清楚；</li>
 *   <li><b>L2 只复用已验收的链路</b>：每格 = 一个 {@link MineTask}（`DIRECT` 模式，站位就在身后
 *       ⇒ **规划距离恒 1 格**），返回 = {@link PathRetryRunner}（`PathRequest.of` = **纯通行**，`D-076`）；</li>
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
 * <h2>终态词表（本片新增，`terminalReason()` 的取值）</h2>
 * <table border="1">
 *   <tr><th>码</th><th>含义</th><th>状态</th></tr>
 *   <tr><td>{@code template_complete}</td><td>模板全部挖穿并回到起点</td><td>DONE</td></tr>
 *   <tr><td>{@code start_unreachable}</td><td>起点不可站 <b>或</b> 纯通行规划证明不可达</td><td>FAILED（**零世界改动**）</td></tr>
 *   <tr><td>{@code start_search_incomplete}</td><td>起点可达性**没搜完**（`SEARCH_LIMIT`）—— `SEARCH_LIMIT ≠ UNREACHABLE`（`D-076`）</td><td>FAILED（**零世界改动**）</td></tr>
 *   <tr><td>{@code main_blocked:&lt;码&gt;</td><td>前方**硬拒绝**（基岩/保护区/认领区/岩浆…）—— 归因码逐字取 `MineTask.isHardTargetRefusal` 那一族</td><td>FAILED（先返回再失败）</td></tr>
 *   <tr><td>{@code main_unreachable:&lt;码&gt;</td><td>前方**挖得到但到不了**（占位/进入方案规划不出）</td><td>FAILED（先返回再失败）</td></tr>
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

    /** 返回段的段数上限（`PathRetryRunner` 的重规划次数；与其它调用点同口径）。 */
    private static final int RETURN_MAX_REPLANS = 2;

    private enum Phase { PREPARE, EXCAVATE, RETURN, DONE }

    private final BotPlayer bot;
    private final FishboneTemplate template;
    private final ScopeBuffer scope;
    private final WriteGrant grant;
    private final int maxTicks;
    private final List<BlockPos> cells;

    private Phase phase = Phase.PREPARE;
    private MineTask current;
    private PathRetryRunner returnRunner;
    private int index;              // 下一个待处理的模板格下标（`cells` 的口径）
    private int unit;               // 1..mainLength：正在/刚处理完的单元
    private int advanced;           // 已挖穿的**推进单元**数（= `C3` 的分母口径）
    private int skipped;            // 本来就通行的格（不重复挖）
    private int mined;              // 我方真的挖掉的格数
    private int ticks;
    private int stallTicks;
    private int lastProgressMark = -1;
    private boolean excavationFailed;
    private String terminalReason = "";
    private Task.Status terminalStatus = Task.Status.FAILED;

    public FishboneJob(BotPlayer bot, FishboneTemplate template, ScopeBuffer scope, int maxTicks) {
        this.bot = Objects.requireNonNull(bot, "bot");
        this.template = Objects.requireNonNull(template, "template");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.maxTicks = maxTicks;
        this.cells = template.cells();
        // 世界写入授权（`D-082`）：鱼骨挖的是**模板几何列出的格**，不是"随手挖掉的阻挡" ⇒ 一次性授权即可，
        // 理由码复用 `BULK_EDIT`（"批量地形编辑（道路施工等非寻路场景）"）——计划 §4 明确**不新增枚举值**。
        this.grant = WriteGrant.of(REQUESTER, WriteReason.BULK_EDIT);
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
        return "advance=" + advanced + "/" + template.mainLength()
                + " mined=" + mined + " skipped=" + skipped + " phase=" + phase;
    }

    /** 目标高亮：指向当前正在处理的模板格（没有就指起点）。 */
    @Override
    public TaskTarget target() {
        BlockPos cell = index < cells.size() ? cells.get(index) : template.startFoot();
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
            case RETURN -> returnPhase();
            case DONE -> terminalStatus;
        };
    }

    // ==================== PREPARE ====================

    /**
     * 起点合法性 + 生成模板格序列 + 开作业作用域（计划 §3 `PREPARE`）。
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
        scope.begin(start, template.scopeRadius(), bot.getUUID());
        BotLog.info("[Fishbone] start template={} scopeRadius={} maxTicks={}",
                template.describe(), template.scopeRadius(), maxTicks);
        phase = Phase.EXCAVATE;
        return Task.Status.RUNNING;
    }

    // ==================== EXCAVATE ====================

    private Task.Status excavate() {
        if (index >= cells.size()) {
            advanced = template.mainLength();
            beginReturn();
            return Task.Status.RUNNING;
        }
        ServerLevel level = bot.serverLevel();

        if (current == null) {
            BlockPos cell = cells.get(index);
            if (isAlreadyPassable(level, cell)) {
                skipped++;
                finishCell();
                return Task.Status.RUNNING;
            }
            BotLog.info("[Fishbone] cell {}/{} target={} unit={}/{}",
                    index + 1, cells.size(), cell.toShortString(), unit + 1, template.mainLength());
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
            finishCell();
            return Task.Status.RUNNING;
        }
        // ⚠️ 失败：归因**必须按 `MineTask` 自己的硬拒绝名单分档**（`J-6`：拒绝清单只许有一处定义）。
        String reason = current.failureReason();
        String code = (MineTask.isHardTargetRefusal(reason)
                ? "main_blocked:" : "main_unreachable:") + reason;
        BotLog.warn("[Fishbone] {} 主巷单元 {} 挖不动 target={} reason={} ⇒ 先沿主巷返回起点再失败",
                code, unit + 1, cells.get(index).toShortString(), reason);
        terminalReason = code;
        excavationFailed = true;
        beginReturn();
        return Task.Status.RUNNING;
    }

    /** 一个模板格处理完（挖穿 / 本来就通）：推进下标，跨过单元边界时给 `advanced` 记账。 */
    private void finishCell() {
        index++;
        current = null;
        // 单元边界 = 每 height 格一个（`cells` 是按单元顺序展开的）
        if (index % template.height() == 0) {
            unit++;
            advanced++;
        }
        stallTicks = 0;
    }

    /** 停滞护栏（`no_progress`）：**按"有没有推进"记账**，不按"tick 有没有跑"。 */
    private void stallGuard() {
        int mark = index * 1000 + mined + skipped;
        if (mark != lastProgressMark) {
            lastProgressMark = mark;
            stallTicks = 0;
            return;
        }
        if (++stallTicks > STALL_TICKS) {
            BotLog.warn("[Fishbone] no_progress 连续 {} tick 没推进（index={}/{} unit={}）⇒ 如实失败",
                    stallTicks, index, cells.size(), unit);
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
        BotLog.info("[Fishbone] template_complete progress={} ticks={}", progressSummary(), ticks);
        terminalStatus = Task.Status.DONE;
        terminalReason = "template_complete";
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

    // ==================== 夹具只读（判据 C1/C3/C4） ====================

    /** **夹具只读**：已挖穿的推进单元数（`C3` 的分母）。 */
    public int advancedUnits() {
        return advanced;
    }

    /** **夹具只读**：我方真的挖掉的模板格数。 */
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
}
