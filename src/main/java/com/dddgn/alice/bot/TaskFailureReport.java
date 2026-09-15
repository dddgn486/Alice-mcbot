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

    /** `lastFailure` 里放的一行事实（有界）：`code@phase`，超长截断并如实标 `…`。 */
    public static final int ONE_LINE_MAX = 120;

    public String oneLine() {
        String line = code + "@" + phase;
        return line.length() <= ONE_LINE_MAX ? line : line.substring(0, ONE_LINE_MAX - 1) + "…";
    }

    public TaskFailureReport {
        code = code == null || code.isBlank() ? "unknown_failure" : code;
        phase = phase == null || phase.isBlank() ? "unknown" : phase;
        details = details == null ? "" : details;
        recoveryStage = recoveryStage == null ? RecoveryStage.NONE : recoveryStage;
        recoveryEvents = recoveryEvents == null ? List.of() : List.copyOf(recoveryEvents);
    }
}
