package com.dddgn.alice.decision;

import com.dddgn.alice.bot.BotPlayer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * **bot 事件环**（S1 事实层 / D-136）：只记**可行动的事实**，供汇报与决策层读取。
 *
 * <p>为什么是环而不是推送：LLM 不该每 tick 收到东西（成本 + 噪声）。确定性层把事件按类型塞进定长环，
 * **汇报时读它**、**阈值触发时读它**（S4）。类型沿用设计文档 §3：
 * {@code DANGER}（危险/维生中断）/ {@code FAILURE}（任务失败）/ {@code RECOVERY}（自愈/恢复成功）/
 * {@code MILESTONE}（任务达成、区域补种等）/ {@code COMBAT}（战斗，预留）/ {@code COMMAND}（玩家指令）。
 */
public final class BotEventLog {

    /** 每个 bot 保留最近多少条（够汇报与阈值判断，又不至于灌爆上下文）。 */
    public static final int CAPACITY = 32;

    public record BotEvent(long tick, String type, String severity, String summary, String data) {
        public String describe() {
            return "#" + tick + " " + type + "/" + severity + " " + summary
                    + (data == null || data.isBlank() ? "" : " " + data);
        }
    }

    private static final Map<UUID, Deque<BotEvent>> LOGS = new HashMap<>();

    private BotEventLog() {
    }

    public static void record(BotPlayer bot, String type, String severity, String summary) {
        record(bot, type, severity, summary, "");
    }

    public static synchronized void record(BotPlayer bot, String type, String severity, String summary,
                                          String data) {
        if (bot == null) {
            return;
        }
        Deque<BotEvent> log = LOGS.computeIfAbsent(bot.getUUID(), ignored -> new ArrayDeque<>());
        log.addLast(new BotEvent(bot.getServer().getTickCount(), type, severity, summary, data));
        while (log.size() > CAPACITY) {
            log.removeFirst();
        }
    }

    public static synchronized List<BotEvent> recent(BotPlayer bot, int limit) {
        Deque<BotEvent> log = bot == null ? null : LOGS.get(bot.getUUID());
        if (log == null || log.isEmpty()) {
            return List.of();
        }
        List<BotEvent> all = new ArrayList<>(log);
        int from = Math.max(0, all.size() - Math.max(1, limit));
        return List.copyOf(all.subList(from, all.size()));
    }

    public static synchronized void forget(UUID botId) {
        LOGS.remove(botId);
    }
}
