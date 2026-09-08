package com.dddgn.alice.pathing.core.search;

import java.util.function.BooleanSupplier;

/**
 * 搜索预算与取消边界（架构文档 §7）。
 *
 * @param maxNodes    最大扩展节点数；{@code <= 0} 表示不限制
 * @param maxMillis   最大墙钟毫秒；{@code <= 0} 表示不限制
 * @param cancelled   取消信号
 */
public record SearchBudget(int maxNodes, long maxMillis, BooleanSupplier cancelled) {
    public static final SearchBudget UNLIMITED = new SearchBudget(0, 0L, () -> false);

    public SearchBudget {
        cancelled = cancelled == null ? (() -> false) : cancelled;
    }

    public static SearchBudget of(int maxNodes, long maxMillis) {
        return new SearchBudget(maxNodes, maxMillis, () -> false);
    }

    public boolean nodeBudgetExhausted(int expandedNodes) {
        return maxNodes > 0 && expandedNodes >= maxNodes;
    }

    public boolean timeBudgetExhausted(long elapsedMillis) {
        return maxMillis > 0 && elapsedMillis >= maxMillis;
    }

    public boolean isCancelled() {
        return cancelled.getAsBoolean();
    }
}
