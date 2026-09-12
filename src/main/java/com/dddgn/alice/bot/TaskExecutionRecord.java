package com.dddgn.alice.bot;

import net.minecraft.core.BlockPos;

import java.util.List;

/** Immutable read-only snapshot of one task terminal outcome. */
public record TaskExecutionRecord(
        String taskKind,
        String targetDescription,
        long startServerTick,
        long endServerTick,
        TerminalStatus terminalStatus,
        String resultCode,
        BlockPos terminalBotPos,
        String recoveryState,
        RecoveryStage recoveryStage,
        List<RecoveryStage> recoveryEvents,
        TaskOutcome outcome) {

    public TaskExecutionRecord(String taskKind, String targetDescription, long startServerTick,
                               long endServerTick, TerminalStatus terminalStatus, String resultCode,
                               BlockPos terminalBotPos, String recoveryState) {
        this(taskKind, targetDescription, startServerTick, endServerTick, terminalStatus, resultCode,
                terminalBotPos, recoveryState, RecoveryStage.NONE, List.of(), null);
    }

    public TaskExecutionRecord(String taskKind, String targetDescription, long startServerTick,
                               long endServerTick, TerminalStatus terminalStatus, String resultCode,
                               BlockPos terminalBotPos, String recoveryState, RecoveryStage recoveryStage) {
        this(taskKind, targetDescription, startServerTick, endServerTick, terminalStatus, resultCode,
                terminalBotPos, recoveryState, recoveryStage, List.of(), null);
    }

    public TaskExecutionRecord(String taskKind, String targetDescription, long startServerTick,
                               long endServerTick, TerminalStatus terminalStatus, String resultCode,
                               BlockPos terminalBotPos, String recoveryState, RecoveryStage recoveryStage,
                               List<RecoveryStage> recoveryEvents) {
        this(taskKind, targetDescription, startServerTick, endServerTick, terminalStatus, resultCode,
                terminalBotPos, recoveryState, recoveryStage, recoveryEvents, null);
    }

    public TaskExecutionRecord {
        taskKind = taskKind == null ? "unknown" : taskKind;
        targetDescription = targetDescription == null ? "unknown" : targetDescription;
        resultCode = resultCode == null ? "" : resultCode;
        terminalBotPos = terminalBotPos == null ? BlockPos.ZERO : terminalBotPos.immutable();
        recoveryState = recoveryState == null ? "not_started" : recoveryState;
        recoveryStage = recoveryStage == null ? RecoveryStage.NONE : recoveryStage;
        recoveryEvents = recoveryEvents == null ? List.of() : List.copyOf(recoveryEvents);
        outcome = outcome == null ? new TaskOutcome(taskKind, targetDescription, terminalStatus,
                resultCode, terminalBotPos, null) : outcome;
    }

    public long durationTicks() {
        return Math.max(0L, endServerTick - startServerTick);
    }

    public enum TerminalStatus {
        COMPLETED,
        FAILED,
        SURVIVAL_INTERRUPTED,
        CANCELLED_FOLLOW,
        /**
         * **玩家/决策层显式打断**（如常驻任务的 {@code /alice region stop}）——与"被下一条指令替换"
         * ({@link #CANCELLED_REPLACED}) 分开记：常驻任务的正常结束方式就是这条，不该看成像出错。
         * 具体原因在 {@code code=cancelled:<reason>}（如 {@code cancelled:region_stop}）。
         */
        CANCELLED_BY_USER,
        CANCELLED_REPLACED,
        REJECTED_BEFORE_START
    }
}
