package com.dddgn.alice.pathing.core.search;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 只读统计（D-044 / Q1②）：统计规划期被拒绝的下降候选原因，
 * 用于回答"一格落差红线到底让多少目标变成 UNREACHABLE"。
 *
 * <p>仅服务端主线程写入；每次规划结束时由 {@link CorePathPlanner} 汇总输出并清零。
 */
public final class PathingStats {
    private static final Map<String, Integer> COUNTS = new LinkedHashMap<>();

    private PathingStats() {
    }

    public static void record(String code) {
        COUNTS.merge(code, 1, Integer::sum);
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
}
