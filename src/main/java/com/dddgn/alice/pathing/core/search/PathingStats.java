package com.dddgn.alice.pathing.core.search;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 只读统计（D-044 / Q1②）：统计规划期被拒绝的下降候选原因，
 * 用于回答"一格落差红线到底让多少目标变成 UNREACHABLE"。
 *
 * <p>仅服务端主线程写入；每次规划结束时由 {@link CorePathPlanner} 汇总输出并清零。
 *
 * <p>{@link #record} 是**单次规划内**的计数（随规划结束清零）；{@link #recordTotal} 是
 * **进程累计**计数（K-4 / D-167：用来回答"谓词不统一到底有没有真实咬到"，
 * 见 {@code alice:bot_report} 的"目标准入"行）。
 */
public final class PathingStats {
    private static final Map<String, Integer> COUNTS = new LinkedHashMap<>();
    private static final Map<String, Integer> TOTALS = new LinkedHashMap<>();

    private PathingStats() {
    }

    public static void record(String code) {
        COUNTS.merge(code, 1, Integer::sum);
    }

    /** 进程累计计数（不清零）。 */
    public static void recordTotal(String code) {
        TOTALS.merge(code, 1, Integer::sum);
    }

    /** 返回摘要并在非空时清空（供规划器输出）。 */
    public static String snapshotAndReset() {
        if (COUNTS.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        COUNTS.forEach((k, v) -> sb.append(k).append('=').append(v).append(' '));
        COUNTS.clear();
        return sb.toString().trim();
    }

    /** 累计计数摘要（空 = 从未发生），供 `bot_report` 输出；不清零。 */
    public static String describeTotals() {
        if (TOTALS.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        TOTALS.forEach((k, v) -> sb.append(k).append('=').append(v).append(' '));
        return sb.toString().trim();
    }

    /** 累计计数快照（副本，供夹具取基线做增量断言）。 */
    public static Map<String, Integer> totalsSnapshot() {
        return new LinkedHashMap<>(TOTALS);
    }

    // ==================== 搜索规模累计（`C3` 的读数口） ====================

    /**
     * **进程累计的搜索规模**：规划次数 / 展开节点总数 / 累计耗时 / `SEARCH_LIMIT` 次数。
     *
     * <p>**为什么必须有它**（2026-09-24，`D-386` 鱼骨切片 1）：鱼骨的卖点是「**每格 1 次、距离恒 1 格**
     * ⇒ 搜索规模与推进格数成正比」，而这条性质此前**没有任何一处可读** —— `PathPlan` 里明明有
     * `nodesExpanded`/`elapsedMillis`，却只有 `PathRetryRunner` 把它**打成一行日志**
     *（`[PathRetry] plan … nodes=… ms=…`）⇒ 判据只能靠 grep 日志，做不成断言。
     * 计划 `§4` 原写"复用 `PathingStats`（`nodesExpanded` 等）"—— 那时它**并不存在**，这里是补上。
     *
     * <p>**唯一写入点 = {@link CorePathPlanner#plan}**（所有规划入口都经过它）⇒ 不产生第二份真相源。
     */
    private static long scalePlans;
    private static long scaleNodes;
    private static long scaleMillis;
    private static long scaleSearchLimits;

    /** 一次规划的规模读数（由 {@link CorePathPlanner#plan} 在唯一漏斗处调用）。 */
    public static void recordScale(int nodesExpanded, long elapsedMillis, boolean searchLimit) {
        scalePlans++;
        scaleNodes += Math.max(0, nodesExpanded);
        scaleMillis += Math.max(0L, elapsedMillis);
        if (searchLimit) {
            scaleSearchLimits++;
        }
    }

    /**
     * 搜索规模快照（不可变）。
     *
     * @param plans        规划次数
     * @param nodes        展开节点总数
     * @param millis       累计耗时（毫秒）
     * @param searchLimits 以 `SEARCH_LIMIT` 收场的规划次数（**"没搜"不是"到不了"**，`D-076`）
     */
    public record Scale(long plans, long nodes, long millis, long searchLimits) {

        /** 两次快照之差（夹具窗口口径：`after.delta(before)`）。 */
        public Scale delta(Scale before) {
            return new Scale(plans - before.plans, nodes - before.nodes,
                    millis - before.millis, searchLimits - before.searchLimits);
        }

        /** 每次规划平均展开节点数（`0` 次规划 ⇒ `0`）。 */
        public double nodesPerPlan() {
            return plans == 0 ? 0.0D : (double) nodes / (double) plans;
        }

        /** 每次规划平均耗时（毫秒；`0` 次规划 ⇒ `0`）。 */
        public double millisPerPlan() {
            return plans == 0 ? 0.0D : (double) millis / (double) plans;
        }

        public String describe() {
            return String.format(java.util.Locale.ROOT,
                    "plans=%d nodes=%d(%.1f/plan) millis=%d(%.1f/plan) searchLimit=%d",
                    plans, nodes, nodesPerPlan(), millis, millisPerPlan(), searchLimits);
        }
    }

    /** 进程累计的搜索规模快照（不清零；夹具取基线做增量断言）。 */
    public static Scale scale() {
        return new Scale(scalePlans, scaleNodes, scaleMillis, scaleSearchLimits);
    }
}
