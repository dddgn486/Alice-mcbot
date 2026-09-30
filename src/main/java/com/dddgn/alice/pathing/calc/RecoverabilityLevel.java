package com.dddgn.alice.pathing.calc;

/** 路线完成后对 Bot 可回收能力的最低要求。 */
public enum RecoverabilityLevel {
    LOCAL_STEP,
    PATH_REVERSIBLE,
    SAFE_EXIT_REQUIRED,
    EMERGENCY_EXIT_REQUIRED
}
