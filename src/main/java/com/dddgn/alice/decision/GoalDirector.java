package com.dddgn.alice.decision;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * **目标级决策循环**（D-135 / ② 决策层）：事件驱动 + 节流，绝不定时每 tick 调。
 *
 * <p>触发（三选一，且都过同一道节流闸）：
 * <ol>
 *   <li>**任务终态**（`BotSession.complete` 之后）——最自然的"下一步做什么"时机；</li>
 *   <li>**维生中断**（逃生出口已经起好了，但决策层也该知道）；</li>
 *   <li>**空闲**：没有任务且连续空闲超过 `idleTriggerTicks`。</li>
 * </ol>
 *
 * <p>节流：① 两次请求间隔 ≥ `minIntervalTicks`；② 每分钟 ≤ `maxRequestsPerMinute`；③ 同一时刻最多 1 个在飞。
 *
 * <p>执行：解析出的动作**只能**通过既定入口生效 —— `start_job` 走 `BotManager.assignJob`
 * （= `JobRequest` → `JobLauncher`，发料与构造都收在那一处）；`stop_current` 走 `BotManager.stopTask`。
 * 未知动作 ⇒ `Refused`，只记日志，**不动世界、不改任务**。
 */
public final class GoalDirector {

    private static final Map<UUID, State> STATES = new HashMap<>();

    /** 动作词汇表（system prompt 的主体；`config.systemPrompt` 会追加在后面）。 */
    public static final String VOCABULARY = """
            你是 Minecraft 服务端假人（bot）的**目标级决策层**。你只决定"下一步做什么目标"，
            不决定怎么走、怎么挖、怎么放方块（那些由确定性执行器负责，且有预算与安全闸门）。

            可选动作（严格 JSON，单个对象，不要多余文字）：
            1. {"action":"start_job","kind":"lumber","radius":16,"quota":2,"maxTicks":3600}
            2. {"action":"start_job","kind":"mine","radius":16,"quota":8,"maxTicks":3600,"productTag":"#forge:ores/iron"}
            3. {"action":"start_job","kind":"region_lumber","maxTicks":24000}   // 只能用"已保存的区域"（玩家划定）
            4. {"action":"stop_current","reason":"..."}
            5. {"action":"report_status","note":"..."}
            6. {"action":"no_op","note":"..."}

            规则：
            - 未知动作或未知 kind 会被**拒绝**；不要编造坐标（中心一律取 bot 当前位置）。
            - 不要要求"挖穿地形/搭桥/放置方块"——那需要显式授权，不在你的词汇表里。
            - 任务刚失败过（lastTerminal.terminal=FAILED）时，优先考虑换目标或 no_op，而不是立刻重跑同一个。
            """;

    private GoalDirector() {
    }

    private static final class State {
        CompletableFuture<LlmClient.Reply> pending;
        String pendingTrigger = "";
        long lastRequestTick = Long.MIN_VALUE;
        long lastIdleTick = -1L;
        int minuteRequests;
        long minuteStartTick;
        String lastAction = "-";
        ServerPlayer observer;
    }

    private static State state(BotPlayer bot) {
        return STATES.computeIfAbsent(bot.getUUID(), ignored -> new State());
    }

    /** 每 tick（`BotManager` 调度循环里调）：收结果 + 空闲触发。 */
    public static void tick(BotPlayer bot) {
        State state = state(bot);
        pollResult(bot, state);
        BotManager.BotSession session = BotManager.sessionOf(bot);
        long now = bot.getServer().getTickCount();
        if (session == null || session.currentTaskSummary() != null) {
            state.lastIdleTick = -1L;
            return;                       // 有任务在跑 ⇒ 不打扰
        }
        if (state.lastIdleTick < 0) {
            state.lastIdleTick = now;
            return;
        }
        if (now - state.lastIdleTick >= LlmConfig.get().idleTriggerTicks()) {
            maybeTrigger(bot, "idle(" + (now - state.lastIdleTick) + "tick)");
        }
    }

    /** 任务终态后调用（`BotSession.complete` 末尾）。 */
    public static void onTaskTerminal(BotPlayer bot, String kind, String terminalReason) {
        maybeTrigger(bot, "terminal:" + kind + (terminalReason == null || terminalReason.isBlank()
                ? "" : "(" + terminalReason + ")"));
    }

    /** 维生中断后调用（逃生出口已由 `BotSession` 起好）。 */
    public static void onSurvivalInterrupt(BotPlayer bot, String reason) {
        maybeTrigger(bot, "survival:" + reason);
    }

    /** 夹具/玩家手动触发一次（不受空闲条件限制，但仍过节流）。 */
    public static String forceOnce(BotPlayer bot, ServerPlayer observer) {
        State state = state(bot);
        state.observer = observer;
        if (state.pending != null) {
            return "busy:已有一个请求在飞";
        }
        if (!LlmConfig.get().usable()) {
            String text = "LLM 未配置（" + LlmConfig.get().describe() + "）⇒ 只打印快照，不发请求";
            BotLog.warn("[Goal] {}", text);
            return text;
        }
        fire(bot, state, "manual");
        return "sent";
    }

    public static String describe() {
        return LlmConfig.get().describe();
    }

    private static void maybeTrigger(BotPlayer bot, String trigger) {
        State state = state(bot);
        LlmConfig config = LlmConfig.get();
        if (!config.usable() || state.pending != null) {
            return;
        }
        long now = bot.getServer().getTickCount();
        if (state.lastRequestTick != Long.MIN_VALUE && now - state.lastRequestTick < config.minIntervalTicks()) {
            BotLog.info("[Goal] trigger_skipped reason=throttle trigger={} sinceLast={}tick",
                    trigger, now - state.lastRequestTick);
            return;
        }
        if (now - state.minuteStartTick >= 1200L) {
            state.minuteStartTick = now;
            state.minuteRequests = 0;
        }
        if (state.minuteRequests >= config.maxRequestsPerMinute()) {
            BotLog.info("[Goal] trigger_skipped reason=rate_limit trigger={} used={}/min",
                    trigger, state.minuteRequests);
            return;
        }
        fire(bot, state, trigger);
    }

    private static void fire(BotPlayer bot, State state, String trigger) {
        LlmConfig config = LlmConfig.get();
        state.lastRequestTick = bot.getServer().getTickCount();
        state.minuteRequests++;
        state.pendingTrigger = trigger;
        String system = VOCABULARY + (config.systemPrompt().isBlank() ? "" : "\n" + config.systemPrompt());
        String prompt = DecisionSnapshot.buildPrompt(bot);
        BotLog.info("[Goal] decision_request trigger={} model={} calledAtTick={}",
                trigger, config.model(), state.lastRequestTick);
        state.pending = LlmClient.askAsync(system, prompt);
    }

    private static void pollResult(BotPlayer bot, State state) {
        CompletableFuture<LlmClient.Reply> pending = state.pending;
        if (pending == null || !pending.isDone()) {
            return;
        }
        state.pending = null;
        LlmClient.Reply reply;
        try {
            reply = pending.get();
        } catch (Exception ex) {
            BotLog.warn("[Goal] decision_failed trigger={} {}", state.pendingTrigger, ex.toString());
            tell(state, "[alice] 决策层请求失败：" + ex.getClass().getSimpleName());
            return;
        }
        if (!reply.ok()) {
            BotLog.warn("[Goal] decision_failed trigger={} error={} latency={}ms",
                    state.pendingTrigger, reply.error(), reply.latencyMs());
            tell(state, "[alice] 决策层不可用（" + reply.error() + "）⇒ 保持确定性策略");
            return;
        }
        String trimmed = reply.text().length() > LlmConfig.get().maxReplyChars()
                ? reply.text().substring(0, LlmConfig.get().maxReplyChars())
                : reply.text();
        GoalAction action = GoalAction.parse(trimmed, bot);
        state.lastAction = action.getClass().getSimpleName();
        BotLog.info("[Goal] decision_action trigger={} latency={}ms raw={} → {}",
                state.pendingTrigger, reply.latencyMs(),
                trimmed.replace('\n', ' ').trim(), describeAction(action));
        execute(bot, state, action, state.pendingTrigger);
    }

    private static String describeAction(GoalAction action) {
        // Java 17：switch 模式匹配还是预览特性 ⇒ 用 instanceof 链（D-135）
        if (action instanceof GoalAction.StartJob start) {
            return "StartJob(" + start.request().describe()
                    + (start.clamps().isEmpty() ? "" : " clamps=" + start.clamps()) + ")";
        }
        if (action instanceof GoalAction.StopCurrent stop) {
            return "StopCurrent(" + stop.reason() + ")";
        }
        if (action instanceof GoalAction.ReportStatus report) {
            return "ReportStatus(" + report.note() + ")";
        }
        if (action instanceof GoalAction.NoOp noop) {
            return "NoOp(" + noop.note() + ")";
        }
        GoalAction.Refused refused = (GoalAction.Refused) action;
        return "Refused(" + refused.reason() + ")";
    }

    /** 执行动作：**只走既定入口**，未知/拒绝动作不动任何东西。 */
    private static void execute(BotPlayer bot, State state, GoalAction action, String trigger) {
        if (action instanceof GoalAction.StartJob start) {
            boolean ok = BotManager.assignJob(bot, state.observer, start.request());
            BotLog.info("[Goal] execute action=start_job ok={} trigger={}", ok, trigger);
            tell(state, ok ? "[alice] 决策层：已起 Job " + start.request().describe()
                    : "[alice] 决策层：起 Job 失败（bot 正忙？）");
            return;
        }
        if (action instanceof GoalAction.StopCurrent stop) {
            String stopped = BotManager.stopTask(bot, "llm:" + stop.reason());
            BotLog.info("[Goal] execute action=stop_current stopped={} trigger={}", stopped, trigger);
            tell(state, "[alice] 决策层：已停止 " + (stopped == null ? "（当时没有任务）" : stopped));
            return;
        }
        if (action instanceof GoalAction.ReportStatus report) {
            BotLog.info("[Goal] execute action=report_status note={}", report.note());
            tell(state, "[alice] 决策层状态：" + report.note());
            return;
        }
        if (action instanceof GoalAction.NoOp noop) {
            BotLog.info("[Goal] execute action=no_op note={}", noop.note());
            tell(state, "[alice] 决策层：不动（" + noop.note() + "）");
            return;
        }
        GoalAction.Refused refused = (GoalAction.Refused) action;
        BotLog.warn("[Goal] execute action=refused reason={} trigger={}", refused.reason(), trigger);
        tell(state, "[alice] 决策层动作被拒绝：" + refused.reason());
    }

    private static void tell(State state, String text) {
        ServerPlayer observer = state.observer;
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal(text));
        }
    }
}
