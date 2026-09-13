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
}
