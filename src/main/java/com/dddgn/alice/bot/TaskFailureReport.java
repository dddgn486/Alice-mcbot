package com.dddgn.alice.bot;

import java.util.List;

/**
 * 任务失败时的事实快照，不包含重试、更换目标或其他决策建议。
 */
public record TaskFailureReport(
        String code,
        String phase,
        String details,
        RecoveryStage recoveryStage,
        List<RecoveryStage> recoveryEvents) {

    public TaskFailureReport {
        code = code == null || code.isBlank() ? "unknown_failure" : code;
        phase = phase == null || phase.isBlank() ? "unknown" : phase;
        details = details == null ? "" : details;
        recoveryStage = recoveryStage == null ? RecoveryStage.NONE : recoveryStage;
        recoveryEvents = recoveryEvents == null ? List.of() : List.copyOf(recoveryEvents);
    }
}
