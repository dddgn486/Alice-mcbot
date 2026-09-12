package com.dddgn.alice.decision;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;

/**
 * **事件统一出口**（S4）：写事件环 + 打一行日志 + **通知决策层**（自检窗口内只记录不通知）。
 *
 * <p>为什么要收敛到一个出口：事件的生产者已经不止阈值检测（`EventThresholds`），还多了
 * **可回收性失败**（`RestoreScopeTask` 的残留）。如果各写各的，就一定会出现"某个生产者忘了
 * 通知决策层 / 忘了尊重自检暂停"的分叉 —— 那正是本项目反复抓到的"同一语义多处实现"病灶。
 */
public final class DecisionEvents {

    private DecisionEvents() {
    }

    /**
     * 报一个事件。
     *
     * @param type     事件类型（`DANGER`/`FAILURE`/`RECOVERY`/`MILESTONE`/`TOOL_LOW`/`STUCK`/`RESIDUE`…）
     * @param severity `info`/`warn`
     */
    public static void emit(BotPlayer bot, String type, String severity, String summary, String data) {
        record(bot, type, severity, summary, data);
        notifyIfAllowed(bot, type, summary);
    }

    /**
     * **只记录、不通知决策层**：用于"信息性事件"（例如重启后的状态报告）。理由：通知会招来一次
     * LLM 调用，而开服时的这类报告是"给人和汇报看的"，不该白花一次调用。
     */
    public static void record(BotPlayer bot, String type, String severity, String summary, String data) {
        BotEventLog.record(bot, type, severity, summary, data);
        BotLog.warn("[Events] {} bot={} {}{}", type, bot.getName().getString(), summary,
                data == null || data.isBlank() ? "" : "（" + data + "）");
    }

    private static void notifyIfAllowed(BotPlayer bot, String type, String summary) {
        // 自检窗口内**只记录不通知**：检具不该在生产侧留下决策痕迹
        //（2026-09-12 实测：夹具造的事件把 LLM 招来，在测试场地起了常驻 Job 把会话占死）。
        if (GoalDirector.isSuspended(bot)) {
            BotLog.info("[Events] {} 已记录（自检暂停：不通知决策层）", type);
        } else {
            GoalDirector.onEvent(bot, type + ":" + summary);
        }
    }
}
