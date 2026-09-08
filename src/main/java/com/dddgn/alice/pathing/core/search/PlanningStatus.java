package com.dddgn.alice.pathing.core.search;

/** 规划期状态。`SEARCH_LIMIT` 与 `UNREACHABLE` 必须严格区分（架构文档 §7）。 */
public enum PlanningStatus {
    /** 找到路径。 */
    REACHED,
    /** 搜索空间穷尽，确实不可达。 */
    UNREACHABLE,
    /** 预算耗尽（节点数/时间），可达性未知。 */
    SEARCH_LIMIT,
    /** 被取消。 */
    CANCELLED,
    /** 内部错误。 */
    ERROR
}
