package com.dddgn.alice.pathing.core.session;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PathSession 执行状态（架构文档 §5.3 的 ExecutionStatus）。
 *
 * <p>与 {@code PlanningStatus} 严格分离：规划期"可达性未知"是 `SEARCH_LIMIT`，
 * 执行期"世界变了"是 `STALE`/`BLOCKED`。
 *
 * <p><b>K-5（2026-09-13）</b>：本枚举此前有**死值** —— `BLOCKED` 由 D-047 复活，
 * 而 `POSTCONDITION_FAILED` 全仓**无生产者**（审计原话："声明但 `mapFailure` 从不产出"⇒ 观测盲区）。
 * 修法不是删值（§5.3 契约里它必须在），而是**给它一个可测的生产者**：
 * 见 {@link #classify(String)} —— 执行器里真实存在的"到达了但完成契约没满足"两类失败
 * （`*_SETTLING_TIMEOUT` 稳定超时、`*_OVERSHOT_*` 过冲）现在映射到它，
 * 不再被并进泛化的 `TIMEOUT`/`MOVEMENT_FAILED`。
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
    /**
     * **到达后置条件未满足**：位置层面到了，但完成契约（脚位/落地/距中心容差）始终不成立 ——
     * 例如 ASCEND 的稳定等待超时（`ASCEND_SETTLING_TIMEOUT`）、DESCEND 冲过目标
     * （`DESCEND_OVERSHOT_BELOW_TARGET`）。
     * <p>与 {@link #TIMEOUT}/{@link #MOVEMENT_FAILED} 的区别对上层有意义：
     * 这是"**站位/落点不对**"（重试同一目标往往有用），不是"根本走不动"。
     */
    POSTCONDITION_FAILED;

    /** 分类表（**顺序即优先级**：越具体越靠前 —— `ASCEND_SETTLING_TIMEOUT` 也含 "TIMEOUT"，
     *  必须先判后置条件，否则会被误吞成泛化超时）。 */
    private static final Map<String, PathSessionStatus> RULES = new LinkedHashMap<>();

    static {
        RULES.put("STALE_START", STALE);
        RULES.put("BLOCKED", BLOCKED);
        RULES.put("INVALID_PRECONDITION", INVALID_PRECONDITION);
        // 后置条件类必须排在 TIMEOUT 之前（见上面的注释）
        RULES.put("SETTLING_TIMEOUT", POSTCONDITION_FAILED);
        RULES.put("OVERSHOT", POSTCONDITION_FAILED);
        RULES.put("POSTCONDITION", POSTCONDITION_FAILED);
        RULES.put("TIMEOUT", TIMEOUT);
        RULES.put("CANCELLED", CANCELLED);
    }

    /**
     * 把执行器的失败码归属到本状态（**唯一定义**：`PathSession.mapFailure` 与自检共用）。
     *
     * <p>`code == null` 或无法归属 ⇒ {@link #MOVEMENT_FAILED}（"非上述分类"的兜底）。
     * 注意 {@link #RUNNING}/{@link #COMPLETED} **不由失败码产生** —— 它们是会话自身的
     * 初态与终态（见 `PathSession` 的字段初值与段完成分支）。
     */
    public static PathSessionStatus classify(String code) {
        if (code == null) {
            return MOVEMENT_FAILED;
        }
        for (Map.Entry<String, PathSessionStatus> rule : RULES.entrySet()) {
            if (code.contains(rule.getKey())) {
                return rule.getValue();
            }
        }
        return MOVEMENT_FAILED;
    }
}
