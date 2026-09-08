package com.dddgn.alice.pathing.core;

/** R2 执行对象的生命周期边界；具体物理动作由后续 Movement 实现提供。 */
public interface MovementExecution {
    enum Phase {
        NOT_STARTED,
        PRECONDITION_CHECK,
        EXECUTING,
        SETTLING,
        POSTCONDITION_CHECK,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    MovementSpec spec();
    String botId();
    String sessionId();
    Phase phase();
    void tick();
    void cancel();
    String failureCode();
}
