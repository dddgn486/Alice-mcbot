package com.dddgn.alice.pathing.core;

/** 世界修改的可审计意图；领域动作与通行修改必须分开。 */
public enum WorldMutationIntent {
    PATH_ACCESS,
    TARGET_ACCESS,
    DOMAIN_ACTION,
    TEMPORARY_SUPPORT,
    RECOVERY_CLEANUP
}
