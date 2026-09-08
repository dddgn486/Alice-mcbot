package com.dddgn.alice.bot;

import net.minecraft.core.BlockPos;

/**
 * 一次任务终态的事实快照；不包含决策层策略或下一步动作建议。
 */
public record TaskOutcome(
        String taskKind,
        String targetDescription,
        TaskExecutionRecord.TerminalStatus terminalStatus,
        String resultCode,
        BlockPos terminalBotPos,
        TaskFailureReport failure) {

    public TaskOutcome {
        taskKind = taskKind == null ? "unknown" : taskKind;
        targetDescription = targetDescription == null ? "unknown" : targetDescription;
        terminalStatus = terminalStatus == null
                ? TaskExecutionRecord.TerminalStatus.FAILED : terminalStatus;
        resultCode = resultCode == null ? "" : resultCode;
        terminalBotPos = terminalBotPos == null ? BlockPos.ZERO : terminalBotPos.immutable();
    }

    public boolean succeeded() {
        return terminalStatus == TaskExecutionRecord.TerminalStatus.COMPLETED;
    }
}
