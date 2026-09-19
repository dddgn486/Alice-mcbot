package com.dddgn.alice.bot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * **运行账（`survey/22 §5.2③` 的"可测量判据"，`D-347`）**：把
 * 「**到达率 / 返回率 / 平均 tick / 世界改动数**」变成**盘上可读的累计事实**，而不是推断。
 *
 * <p><b>为什么要有它</b>：勘测报告 §5 的问法是「目标已确定时，**几乎能保证到达目标并返回安全区吗**？」——
 * 报告给的验收口径是「连续 N 次任务：到达率 / 返回率 / 平均 tick / 世界改动数」。
 * 2026-09-20 复读时逐项 grep 的结论是：这四个数**没有任何一处被汇总过**（唯一接近的是
 * `[WriteBudget] SUMMARY` 与 `TaskExecutionRecord.durationTicks()`，两者都只散在单次运行里）。
 *
 * <p><b>分工（每一格都由"真的发生"的那一刻写，夹具只读）</b>：
 * <table border="1">
 *   <tr><th>数</th><th>写入点</th><th>为什么在那里</th></tr>
 *   <tr><td>{@code started}</td><td>{@code BotSession.beginTask}</td>
 *       <td>任务的**唯一启动口**（生产与自检步都走它）⇒ 分母不会漏</td></tr>
 *   <tr><td>{@code finished/状态桶/ticks}</td><td>{@code BotSession.recordTerminal}</td>
 *       <td>终态记录的**唯一构造点** —— 不能挂在 {@code complete()}：`immediateStop`（玩家
 *           {@code /alice stop-task}、{@code doneWhen} 步、延后到安全点）**不走它**（`D-338` 附注十五）</td></tr>
 *   <tr><td>{@code arrived}</td><td>**任务自己**（如 {@code MineJob} 挖掉第一格目标时）</td>
 *       <td>只有任务知道"到了"；框架层猜会把"路过"记成"到达"（那就是假判据）</td></tr>
 *   <tr><td>{@code breaks/places/containerWrites/refused*}</td><td>{@code WriteBudget}</td>
 *       <td>那里才是"真的扣了预算 / 真的被拒"的一刻（搜索谓词不算，与既有口径一致）</td></tr>
 *   <tr><td>{@code returns/returnFails}</td><td>同上（终态时 {@code wasReturnTask}）</td>
 *       <td>返程兜底（{@code D-327} 机制 B）自己也是会话任务 ⇒ 复用同一收口</td></tr>
 * </table>
 *
 * <p><b>⚠️ 到达率的分母只有"自报过到达的 kind"</b>：夹具/自检/一次性任务的
 * {@code arrived} 恒 0（它们**根本没有"目标"语义**）⇒ 把它们算进分母会把到达率稀释成噪声。
 * 所以本类记住哪些 kind 自报过到达（{@link #ARRIVAL_KINDS}），比率**只在它们之间算**
 * （分母 = 这些 kind 的**启动数**，见 {@link #arrivalDenominator()}），其余 kind 单列"未自报到达"。
 *
 * <p><b>⚠️ 已知边界（不假装完整）</b>：
 * <ul>
 *   <li>**只统计"走写入预算闸门"的世界改动**：没有作用域时 {@code WriteBudget} 直接放行且不计数
 *       （既有行为，日志里是 {@code [WriteBudget] no_scope}）⇒ 玩家手驱的无作用域写入**不在这里**。
 *       任务运行一律有作用域（{@code beginTask} 开），所以"任务运行的世界改动数"是准的；</li>
 *   <li>进程内累计（不落盘）：跨重启不延续。这是**刻意**的 —— 落盘会引入"哪些旧数据算数"的新问题，
 *       而本项要回答的是"**这一轮/这一段**跑得怎么样"，夹具与电池用**增量**读（{@link Snapshot#delta}）；</li>
 *   <li>只由服务端主线程写（与 {@code PathingStats} 同约定），不做同步。</li>
 * </ul>
 */
public final class TaskMetrics {

    /** 一个 kind 的累计桶（可变，只由主线程改）。 */
    private static final class Bucket {
        int started;
        int finished;
        int completed;
        int failed;
        int arrived;
        long ticks;
    }

    private static final Map<String, Bucket> BY_KIND = new LinkedHashMap<>();
    /** 自报过到达的 kind（到达率的分母只在这些 kind 里算）。 */
    private static final Set<String> ARRIVAL_KINDS = new HashSet<>();

    private static int started;
    private static int finished;
    private static int completed;
    private static int failed;
    private static int arrived;
    private static long ticks;

    private static int returns;
    private static int returnFails;

    private static int breaks;
    private static int exemptBreaks;
    private static int places;
    private static int containerWrites;
    private static int refusedBreaks;
    private static int refusedPlaces;
    private static int refusedContainerWrites;

    private TaskMetrics() {
    }

    // ==================== 写入点（全部由生产代码调用） ====================

    /** **任务启动**（`BotSession.beginTask`，唯一收口）。 */
    public static void noteStart(BotPlayer bot, String kind) {
        Bucket bucket = bucket(kind);
        bucket.started++;
        started++;
    }

    /**
     * **任务自己声明"到达了目标"**（**每次调用都计数**）。
     *
     * <p>调用点的语义必须是「**真的到了作业目标**」，不是"开始跑了" —— 例如
     * {@code MineJob} 在**第一格目标方块被真的挖掉**时声明（到达 + 作业已开始，
     * 两件事同时被证明），而不是在"选出了目标"时。
     *
     * <p>⚠️ **"一次运行只声明一次"是调用点的责任，不是本类的**（`D-347` 首跑实测的修正）：
     * 本类原先按 bot 去重（"同一次运行只算第一次"），但**运行边界只对会话可见**
     * （`noteStart`/`noteTerminal` 都是会话事件）⇒ 夹具**直接 tick** 的子任务边界对账本不可见，
     * 于是第二次及其后的声明被当成"同一次运行"吞掉（实测：连跑 3 次只记到 1 次到达）。
     * 现在改为**照数**：调用点用"第一格"这种**结构性唯一**的位置保证一次一报
     * （`MineJob` = `firstMined == null`、`LumberJob` = `choppedLogs == 1`、`WalkToTask` = 终态分支），
     * 而"每次运行恰好一次"由夹具 `mine_run_metrics` 逐次断言 ⇒ 违规会**当场红**，不会被静默吸收。
     * （重复声明会让 到达数 &gt; 启动数 ⇒ 报告里一眼可见，不是"合理值"。）
     */
    public static void arrived(String kind) {
        Bucket bucket = bucket(kind);
        ARRIVAL_KINDS.add(key(kind));
        bucket.arrived++;
        arrived++;
    }

    /** **任务终态**（`BotSession.complete`，唯一收口）。 */
    public static void noteTerminal(BotPlayer bot, String kind,
                                    TaskExecutionRecord.TerminalStatus status, long durationTicks,
                                    boolean wasReturnTask) {
        Bucket bucket = bucket(kind);
        bucket.finished++;
        finished++;
        if (status == TaskExecutionRecord.TerminalStatus.COMPLETED) {
            bucket.completed++;
            completed++;
        } else {
            bucket.failed++;
            failed++;
        }
        long used = Math.max(0L, durationTicks);
        bucket.ticks += used;
        ticks += used;
        if (wasReturnTask) {
            returns++;
            if (status != TaskExecutionRecord.TerminalStatus.COMPLETED) {
                returnFails++;
            }
        }
    }

    /** 一次**真的发生**的破坏（`WriteBudget.consumeBreak`；`exempt` = 回收我方临时方块）。 */
    public static void noteBreak(boolean exempt) {
        if (exempt) {
            exemptBreaks++;
        } else {
            breaks++;
        }
    }

    /** 一次**真的发生**的放置（`WriteBudget.consumePlace`）。 */
    public static void notePlace() {
        places++;
    }

    /** 一次**真的发生**的容器写入（`WriteBudget.consumeContainerWrite`）。 */
    public static void noteContainerWrite() {
        containerWrites++;
    }

    /** 一次被预算闸门**拒绝**的破坏（`WriteBudget.consumeBreak`）。 */
    public static void noteRefusedBreak() {
        refusedBreaks++;
    }

    /** 一次被预算闸门**拒绝**的放置（`WriteBudget.consumePlace` / `notePlaceRefusal`）。 */
    public static void noteRefusedPlace() {
        refusedPlaces++;
    }

    /** 一次被预算闸门**拒绝**的容器写入（`WriteBudget.consumeContainerWrite`）。 */
    public static void noteRefusedContainerWrite() {
        refusedContainerWrites++;
    }

    // ==================== 只读出口 ====================

    /** 累计快照（夹具/电池做**增量**断言用；抄 `PathingStats.totalsSnapshot` 的形状）。 */
    public record Snapshot(int started, int finished, int completed, int failed, int arrived, long ticks,
                           int returns, int returnFails,
                           int breaks, int exemptBreaks, int places, int containerWrites,
                           int refusedBreaks, int refusedPlaces, int refusedContainerWrites) {

        /** 相对基线（`this - before`）—— 夹具**只许断言增量**（CORE 里同一进程会跑很多步）。 */
        public Snapshot delta(Snapshot before) {
            return new Snapshot(started - before.started, finished - before.finished,
                    completed - before.completed, failed - before.failed,
                    arrived - before.arrived, ticks - before.ticks,
                    returns - before.returns, returnFails - before.returnFails,
                    breaks - before.breaks, exemptBreaks - before.exemptBreaks,
                    places - before.places, containerWrites - before.containerWrites,
                    refusedBreaks - before.refusedBreaks, refusedPlaces - before.refusedPlaces,
                    refusedContainerWrites - before.refusedContainerWrites);
        }

        /** 本段"真的改了世界"的次数（破坏 + 放置 + 容器写入）＝ `survey/22 §5.2③` 的"世界改动数"。 */
        public int worldChanges() {
            return breaks + places + containerWrites;
        }
    }

    /**
     * 累计快照。⚠️ **比率不在这里算**：到达率的分母只有"自报过到达的 kind"才算数
     * （见类注释），而那是**进程级**的知识、不是一段增量的属性 ⇒ 比率只由 {@link #describe()} 提供；
     * 夹具改用**分子/分母的增量**断言（`arrived` 与 `finished` 分开看）。
     */
    public static Snapshot snapshot() {
        return new Snapshot(started, finished, completed, failed, arrived, ticks,
                returns, returnFails, breaks, exemptBreaks, places, containerWrites,
                refusedBreaks, refusedPlaces, refusedContainerWrites);
    }

    /** 累计描述（**`alice:bot_report` 一行 + 终态日志**共用）。 */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("运行账（累计，D-347）: 任务 启动=").append(started).append(" 完成=").append(completed)
                .append(" 失败=").append(failed)
                .append(" 平均tick=").append(finished == 0 ? 0 : ticks / finished);
        int denominator = arrivalDenominator();
        if (ARRIVAL_KINDS.isEmpty()) {
            sb.append(" 到达=未自报");
        } else if (denominator == 0) {
            sb.append(" 到达=").append(arrived).append("（无启动样本）");
        } else if (arrived > denominator) {
            // ⚠️ **诚实标记**：到达数 > 启动数只可能来自**"直驱"运行**（夹具/编排器自己 tick 子任务
            // ⇒ 子任务不经过会话 ⇒ `noteStart` 不会发生，但任务内部的 `arrived` 照写）。这不是"到达率 400%"，
            // 而是**比率在这一段不可算**（实测：`module:mining` 一轮 到达=4 / 启动=1）。
            sb.append(" 到达=").append(arrived).append('/').append(denominator)
                    .append("（⚠️ 到达数 > 启动数：这一段含**直驱**运行（夹具/编排器直接 tick 子任务，"
                            + "不经过会话）⇒ **比率不可算**，只看绝对数）");
        } else {
            sb.append(" 到达=").append(arrived).append('/').append(denominator)
                    .append("（").append(Math.round(100.0D * arrived / denominator)).append("%）");
        }
        sb.append(" 返程=").append(returns).append("（失败 ").append(returnFails).append("）");
        sb.append(" 世界改动 breaks=").append(breaks).append(" places=").append(places)
                .append(" 容器=").append(containerWrites);
        if (exemptBreaks > 0) {
            sb.append("（含回收豁免破坏 ").append(exemptBreaks).append("）");
        }
        sb.append(" 被拒 breaks=").append(refusedBreaks).append(" places=").append(refusedPlaces)
                .append(" 容器=").append(refusedContainerWrites);
        sb.append(" ｜按 kind: ").append(describeKinds());
        return sb.toString();
    }

    /**
     * 到达率的分母：**自报过到达的 kind 的"启动数"** —— 用启动而不是完成，有两个原因：
     * ① 分母必须是"真的跑过的运行"（`REJECTED_BEFORE_START` 那种从未启动的记录不算运行）；
     * ② 夹具/自检任务**根本没有"目标"语义**（`arrived` 恒 0）⇒ 混进分母会把到达率稀释成噪声。
     */
    private static int arrivalDenominator() {
        int sum = 0;
        for (Map.Entry<String, Bucket> entry : BY_KIND.entrySet()) {
            if (ARRIVAL_KINDS.contains(entry.getKey())) {
                sum += entry.getValue().started;
            }
        }
        return sum;
    }

    private static String describeKinds() {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Bucket> entry : BY_KIND.entrySet()) {
            Bucket bucket = entry.getValue();
            parts.add(entry.getKey() + "[启动" + bucket.started + " 完成" + bucket.completed
                    + " 失败" + bucket.failed
                    + (ARRIVAL_KINDS.contains(entry.getKey()) ? " 到达" + bucket.arrived : "")
                    + " tick" + bucket.ticks + "]");
        }
        return parts.isEmpty() ? "无" : String.join(" ", parts);
    }

    private static Bucket bucket(String kind) {
        return BY_KIND.computeIfAbsent(key(kind), ignored -> new Bucket());
    }

    private static String key(String kind) {
        return kind == null || kind.isBlank() ? "unknown" : kind;
    }
}
