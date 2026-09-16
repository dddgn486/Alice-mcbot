package com.dddgn.alice.pathing.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * **"这个任务的信封里有没有写世界的权利"**（D-241，2026-09-16 用户批准的提案 B / `survey09` §7）。
 *
 * <p>为什么要有它：用户定案的轴是"**这个任务改不改世界**"（`MovementCapabilities.changesWorld()`），
 * 而不是"哪个危险/哪种地形"。逃生的写权必须是**信封的后果**：只有那些**自己就拿到了写授权**的任务
 * （挖掘站位用 `miningApproach`、掉落物收集显式 `withWorldModification` …），才允许在危急时动用准备金。
 *
 * <p>怎么得到事实（**不维护任务清单**）：`PathRequest` 的唯一构造点把"本次请求的移动集里有没有写动作"
 * 记在这里；任务开始时由 `BotSession.beginTask` 清空 ⇒ 于是"本任务期间出现过写请求"= 信封允许改世界。
 * 这是**推导出来的事实**，不是手抄的名单（手抄名单必然漂移）。
 */
public final class WriteEnvelopes {

    // 键用 botId **字符串**（`PathRequest` 原本就是字符串口径）：不做 UUID 解析 —— 策略表/诊断会用
    // 假 id（如 `PROBE`）探测工厂，解析会抛异常，而"信封事实"本来只对真 bot 有意义。
    private static final Map<String, Boolean> HAD_WRITES = new HashMap<>();

    private WriteEnvelopes() {
    }

    /** 记一笔：本 bot 在**当前任务期间**出现过写请求（由 {@code PathRequest} 构造点调用）。 */
    public static void note(String botId, Set<MovementType> movements) {
        if (botId == null) {
            return;
        }
        for (MovementType type : movements) {
            if (type.changesWorld()) {
                HAD_WRITES.put(botId, Boolean.TRUE);
                return;
            }
        }
    }

    /** 本 bot 在当前任务期间是否拿到过写授权（逃生写权的**唯一闸门**）。 */
    public static boolean had(String botId) {
        return botId != null && HAD_WRITES.getOrDefault(botId, Boolean.FALSE);
    }

    /** 任务开始时清空（由 `BotSession.beginTask` 调用）：每个任务重新确立自己的信封。 */
    public static void clear(String botId) {
        if (botId != null) {
            HAD_WRITES.remove(botId);
        }
    }
}
