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
        TaskFailureReport failure,
        /** 所有者（多 bot 预留）：bot 的 UUID 字符串。 */
        String botId,
        /** 任务自己报的终止理由（Job 层；非 Job 为空串）—— 决策层据此区分"达成"与"提前收工"。 */
        String terminalReason,
        /** **F1 地基**：谁驱动的（`llm` / `fixture` / `in_game_player` / `system`=未归因）。 */
        String driver,
        /** ⭐ 刀 4（`D-560` 第 4 条）：本次任务有没有被发料污染（`DEV_CREATE`/`PROMOTE_ONLY`/`none`）。
         *  取值口径与打标处见 {@link TaskExecutionRecord#provision()}。 */
        String provision) {

    public TaskOutcome {
        taskKind = taskKind == null ? "unknown" : taskKind;
        targetDescription = targetDescription == null ? "unknown" : targetDescription;
        terminalStatus = terminalStatus == null
                ? TaskExecutionRecord.TerminalStatus.FAILED : terminalStatus;
        resultCode = resultCode == null ? "" : resultCode;
        terminalBotPos = terminalBotPos == null ? BlockPos.ZERO : terminalBotPos.immutable();
        botId = botId == null ? "" : botId;
        terminalReason = terminalReason == null ? "" : terminalReason;
        driver = driver == null || driver.isBlank() ? "system" : driver;
        provision = provision == null || provision.isBlank() ? "none" : provision;
    }

    public boolean succeeded() {
        return terminalStatus == TaskExecutionRecord.TerminalStatus.COMPLETED;
    }
}
