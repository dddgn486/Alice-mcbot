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
        TaskOutcome outcome,
        /** 所有者（多 bot 预留 / 决策层归因）：bot 的 UUID 字符串。 */
        String botId,
        /**
         * **任务自己报的终止理由**（Job 层 `Job.terminalReason()`）。
         *
         * <p>为什么必须进这里：`resultCode` 只记 `done` / `failed:<reason>`，
         * 于是"配额达成"与"背包满提前收工"在上层看起来一样 —— 决策层（LLM）拿不到
         * "为什么结束"，只能去翻日志（D-134）。非 Job 任务为空串。
         */
        String terminalReason,
        /** **F1 地基**：谁驱动的（见 `Driver`；`system` = 未归因）。 */
        String driver,
        /**
         * ⭐ **刀 4（`D-560` 第 4 条）**：**本次任务有没有被"发料"污染**。
         *
         * <p>用户原话：「**不是不能白送工具，只是不应该被白送工具的行为污染结果**」——
         * 这句判据**唯一**能执行的形式 = **把"这次发过料"写进结果**。
         *
         * <p>取值（由**发料动作本身**打标，⛔ 不是调用方手写）：
         * <ul>
         *   <li>{@code "DEV_CREATE"} —— 用过 {@link com.dddgn.alice.item.FixtureToolKit} **凭空造物**（开发期策略）；</li>
         *   <li>{@code "PROMOTE_ONLY"} —— 用过 {@code ToolSupply.promoteFromMain}（**只搬运**已存在的）；</li>
         *   <li>{@code "none"} —— 本任务期间没有任何发料动作（默认）。</li>
         * </ul>
         * ⚠️ **本维只解决"可见"，不解决"该不该"**：事后能读「这次调试结果是被发过料的」，
         * ⛔ 不必靠人记得。合法性判据见 `tools/check-provision-containment.sh`。
         */
        String provision) {

    public TaskExecutionRecord(String taskKind, String targetDescription, long startServerTick,
                               long endServerTick, TerminalStatus terminalStatus, String resultCode,
                               BlockPos terminalBotPos, String recoveryState) {
        this(taskKind, targetDescription, startServerTick, endServerTick, terminalStatus, resultCode,
                terminalBotPos, recoveryState, RecoveryStage.NONE, List.of(), null, null, null, null, null);
    }

    public TaskExecutionRecord(String taskKind, String targetDescription, long startServerTick,
                               long endServerTick, TerminalStatus terminalStatus, String resultCode,
                               BlockPos terminalBotPos, String recoveryState, RecoveryStage recoveryStage) {
        this(taskKind, targetDescription, startServerTick, endServerTick, terminalStatus, resultCode,
                terminalBotPos, recoveryState, recoveryStage, List.of(), null, null, null, null, null);
    }

    public TaskExecutionRecord(String taskKind, String targetDescription, long startServerTick,
                               long endServerTick, TerminalStatus terminalStatus, String resultCode,
                               BlockPos terminalBotPos, String recoveryState, RecoveryStage recoveryStage,
                               List<RecoveryStage> recoveryEvents) {
        this(taskKind, targetDescription, startServerTick, endServerTick, terminalStatus, resultCode,
                terminalBotPos, recoveryState, recoveryStage, recoveryEvents, null, null, null, null, null);
    }

    public TaskExecutionRecord {
        taskKind = taskKind == null ? "unknown" : taskKind;
        targetDescription = targetDescription == null ? "unknown" : targetDescription;
        resultCode = resultCode == null ? "" : resultCode;
        terminalBotPos = terminalBotPos == null ? BlockPos.ZERO : terminalBotPos.immutable();
        recoveryState = recoveryState == null ? "not_started" : recoveryState;
        recoveryStage = recoveryStage == null ? RecoveryStage.NONE : recoveryStage;
        recoveryEvents = recoveryEvents == null ? List.of() : List.copyOf(recoveryEvents);
        botId = botId == null ? "" : botId;
        terminalReason = terminalReason == null ? "" : terminalReason;
        driver = driver == null || driver.isBlank() ? "system" : driver;
        provision = provision == null || provision.isBlank() ? "none" : provision;
        outcome = outcome == null ? new TaskOutcome(taskKind, targetDescription, terminalStatus,
                resultCode, terminalBotPos, null, botId, terminalReason, driver, provision) : outcome;
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
