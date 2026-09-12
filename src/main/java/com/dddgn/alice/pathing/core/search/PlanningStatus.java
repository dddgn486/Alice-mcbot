package com.dddgn.alice.pathing.core.search;

/** 规划期状态。`SEARCH_LIMIT` 与 `UNREACHABLE` 必须严格区分（架构文档 §7）。 */
public enum PlanningStatus {
    /** 找到路径。 */
    REACHED,
    /** 搜索空间穷尽，确实不可达。 */
    UNREACHABLE,
    /** 预算耗尽（节点数/时间），可达性未知。 */
    SEARCH_LIMIT,
    /**
     * **目标所在的区块尚未加载**（S-2 / P1-A，2026-09-12）。
     *
     * <p>为什么必须与上面三个分开：这既不是"不可达"（加载后就能到）、也不是"预算不够"、
     * 更不是"能不能挖穿"的问题 —— 服务端在**未加载区块上读方块会同步加载/生成区块并阻塞主线程**
     * （反编译证据见 `RISK_SYSTEM_REVIEW_20260910.md` §2），所以这里**硬拒**，让调用方稍后重试
     * 或先靠近，而不是把"没加载"错报成"到不了"。
     *
     * <p>对照 Baritone：`BlockStateInterface.worldContainsLoadedChunk` / `isLoaded`
     * （`provider.getChunk(..., ChunkStatus.FULL, **false**)` —— 从不加载区块）；
     * `AStarPathFinder:105-112` 跨区块时 `if (!calcContext.isLoaded(newX, newZ)) continue;`。
     */
    GOAL_NOT_LOADED,
    /** 被取消。 */
    CANCELLED,
    /** 内部错误。 */
    ERROR
}
