package com.dddgn.alice.bot;

import net.minecraft.core.BlockPos;

/** Immutable read-only snapshot of one task terminal outcome. */
public record TaskExecutionRecord(
        String taskKind,
        String targetDescription,
        long startServerTick,
        long endServerTick,
        TerminalStatus terminalStatus,
        String resultCode,
        BlockPos terminalBotPos,
        String recoveryState) {

    public TaskExecutionRecord {
        taskKind = taskKind == null ? "unknown" : taskKind;
        targetDescription = targetDescription == null ? "unknown" : targetDescription;
        resultCode = resultCode == null ? "" : resultCode;
        terminalBotPos = terminalBotPos == null ? BlockPos.ZERO : terminalBotPos.immutable();
        recoveryState = recoveryState == null ? "not_started" : recoveryState;
    }

    public long durationTicks() {
        return Math.max(0L, endServerTick - startServerTick);
    }

    public enum TerminalStatus {
        COMPLETED,
        FAILED,
        SURVIVAL_INTERRUPTED,
        CANCELLED_FOLLOW,
        CANCELLED_REPLACED,
        REJECTED_BEFORE_START
    }
}
