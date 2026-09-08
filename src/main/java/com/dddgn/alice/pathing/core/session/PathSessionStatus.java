package com.dddgn.alice.pathing.core.session;

/**
 * PathSession 执行状态（架构文档 §5.3 的 ExecutionStatus）。
 *
 * <p>与 {@code PlanningStatus} 严格分离：规划期"可达性未知"是 `SEARCH_LIMIT`，
 * 执行期"世界变了"是 `STALE`/`BLOCKED`。
 */
public enum PathSessionStatus {
    RUNNING,
    COMPLETED,
    /** 当前段目标被阻塞（世界变化导致目标不可通行/无支撑）。 */
    BLOCKED,
    /** 计划与当前世界/位置不一致（起点漂移、世界版本变化）。 */
    STALE,
    /** 单段超时。 */
    TIMEOUT,
    /** 段前置条件不成立。 */
    INVALID_PRECONDITION,
    /** 段执行失败（非上述分类）。 */
    MOVEMENT_FAILED,
    CANCELLED,
    /** 到达后置条件未满足。 */
    POSTCONDITION_FAILED
}
