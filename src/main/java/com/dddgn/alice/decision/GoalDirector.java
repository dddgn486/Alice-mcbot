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
    /** 决策层回执送出次数（**只用于夹具断言/汇报**；通知对象判据见 {@link #notifyTargets}）。 */
    private static final Map<UUID, Integer> NOTIFIED = new java.util.concurrent.ConcurrentHashMap<>();

    /** 动作词汇表（system prompt 的主体；`config.systemPrompt` 会追加在后面）。 */
    public static final String VOCABULARY = """
            你是 Minecraft 服务端假人（bot）的**目标级决策层**。你只决定"下一步做什么目标"，
            不决定怎么走、怎么挖、怎么放方块（那些由确定性执行器负责，且有预算与安全闸门）。

            可选动作（严格 JSON，单个对象，不要多余文字）：
            1. {"action":"start_job","kind":"lumber","target":"tree@20,64,208","radius":16,"quota":1,"maxTicks":3600}
               // target **必须**是状态里 menu 出现过的 id（伐木/捡拾必须给；mine/region_lumber 可省）
            2. {"action":"start_job","kind":"mine","radius":16,"quota":8,"maxTicks":3600,"productTag":"#forge:ores/iron"}
            2b. {"action":"start_job","kind":"collect","target":"drops@27,64,207","radius":16,"quota":24,"maxTicks":1200}
                // 掉落物搜索 + 捡拾；**只捡我方造成的掉落物**（不会捡玩家的东西）
            3. {"action":"start_job","kind":"region_lumber","maxTicks":24000}   // 只能用"已保存的区域"（玩家划定）
            4. {"action":"maintain_tool","kind":"pickaxe"}   // 工具耐久见底 / 工具落在主背包选不到时：
                                                            // 搬进快捷栏。kind ∈ pickaxe|axe|shovel|sword；
                                                            // **只动背包，不合成、不挖材料**
            4b. {"action":"stop_current","reason":"..."}
            5. {"action":"craft","item":"minecraft:crafting_table","count":1}
                // 合成/烧炼（真消耗真产物）。**item 必须来自状态里 menu 的 craftable 条目**
                // （那是服务端按"你现在持有的材料 + 当前选中的工作站"算出来的）；
                // 清单里 can_use=false 的项 = 当前工作站做不了（换工作站由玩家决定，你不能指定站点）；
                // 缺料/没配方会被**拒绝**并回读原因（例如 missing_ingredients）。
            6. {"action":"report_status","note":"..."}
            7. {"action":"no_op","note":"..."}

            规则：
            - 未知动作/未知 kind/未在 menu 中的 target 一律被**拒绝**；不要编造坐标。
            - `craft` 的 item 必须是 menu 里 `kind="craftable"` 且 `can_use=true` 的 id；数量别超过手头材料。
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
        /** ⭐ 附注十五：**因闸门被丢弃的触发次数**（自上次真正做出决策起算；会如实写进快照）。 */
        int droppedTriggers;
        /** ⭐ `D-339`：最近一次丢弃的**原因**（判据要能断言"是哪道闸门拦的"）。 */
        String lastDroppedReason = "";
        int minuteRequests;
        long minuteStartTick;
        String lastAction = "-";
        CandidateMenu lastMenu;
        ServerPlayer observer;
        /** 暂停触发的截止 tick（自检期间用；见 {@link #suspend}）。 */
        long suspendedUntilTick = Long.MIN_VALUE;
        /**
         * **自检任务存续期间持续按住**（T1 / R-3，2026-09-14）：`suspendedUntilTick` 是**定长时限**，
         * 而电池要跑 ~3400 tick ⇒ 1200 tick 的窗口会在任务结束前就过期，终态照样招 LLM。
         * 这个标记由会话每 tick 按"当前任务是不是自检"设置，**随任务存续**而不是随计时器。
         */
        boolean selfCheckHold;
        boolean suspendLogged;
        // J-7：**结构化拒绝回读** —— 上一轮动作被拒的理由要能被下一轮读到（而不是只躺在日志里）
        String lastRefusalReason = "";
        long lastRefusalTick = -1L;
        int lastRefusalCount;
        /** 直连模式标记（跨"发请求 → 收回复"存活；`pollResult` 据此放行菜单校验并打 `directed_result`）。 */
        boolean pendingDirected;
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
        // **空闲触发默认关**（D-135）：配置了才启用。2026-09-12 实测我漏了这道闸，
        // 结果空闲时每 ~10 s 反复发请求（全部超时）⇒ 持续烧钱。配置项存在 ≠ 被读。
        if (!LlmConfig.get().idleDecisionEnabled()) {
            return;
        }
        if (now - state.lastIdleTick >= LlmConfig.get().idleTriggerTicks()) {
            maybeTrigger(bot, "idle(" + (now - state.lastIdleTick) + "tick)");
        }
    }

    /**
     * 任务终态后调用（`BotSession.complete` 末尾）。
     *
     * <p>⭐ `D-339`（2026-09-19 用户裁定）：**夹具终态不是"世界事实"，不交给决策层。**
     *
     * <p><b>为什么</b>：测试物品/电池起的任务，结果归**测试者**（聊天 + 日志 + 事件环），不该让
     * 决策层接手。客户端实测两次（2026-09-19 16:53 / 19:06）都是同一条链：
     * 夹具一次性砍树在保护区被**如实拒绝** ⇒ LLM 4 秒内自起一个**它无权做**的 `region_lumber`
     * ⇒ 封顶 `L1` 一棵都砍不动 ⇒ `viable=0 inRegion=0` 空转到 `maxTicks=24000`（**20 分钟**），
     * 期间每 200 tick 唤醒 LLM 一次（限流 3/min）⇒ 你看到的"他没有动"。
     * 与 {@link #suspend} 同一条教训：**自检要的是确定性，不该被生产决策层中途接管**。
     *
     * <p>⚠️ 阻断**不丢账**：事件环那条 `FAILURE`/`MILESTONE` 由 `BotSession.complete`
     * **先于**本方法写入（`BotManager:2288`），所以"夹具跑了什么、成没成"照样留痕；
     * 这里只把"要不要叫 LLM"这一件事掐掉，并记一条 `fixture_driver` 丢弃（进 `droppedTriggers`）。
     *
     * <p>⚠️ 口径边界：**只拦 `FIXTURE`**（明确是夹具）。`SYSTEM`（未归因）**不拦** —— 那是
     * "已知发起者还没标注到位"的兜底，一起拦会顺带改掉未经审计的入口行为。
     *
     * @return 是否**真的交给了决策层**（`false` = 夹具终态被闸门拦下）
     */
    public static boolean onTaskTerminal(BotPlayer bot, String kind, String terminalReason) {
        String trigger = "terminal:" + kind + (terminalReason == null || terminalReason.isBlank()
                ? "" : "(" + terminalReason + ")");
        if (Driver.FIXTURE.equals(Driver.of(bot))) {
            noteDroppedTrigger(bot, state(bot), trigger, FIXTURE_TERMINAL_REASON);
            return false;
        }
        maybeTrigger(bot, trigger);
        return true;
    }

    /** 夹具终态被拦下的丢弃原因（**判据按它断言**；改文案必须同步 `LlmContractCheckTask`）。 */
    public static final String FIXTURE_TERMINAL_REASON = "fixture_driver（夹具终态不交给决策层）";

    /**
     * **暂停触发**（自检/回归用）：`ticks` 内不因空闲/终态/事件自动发起决策。
     *
     * <p>为什么需要：2026-09-12 实测 —— S4 的 `STUCK` 事件触发了真实决策，LLM 选了
     * `stop_current`，**在自检第 522 tick 把任务砍掉**（`CANCELLED_BY_USER code=cancelled:llm:…`），
     * 导致后续用例（"同一病症不重复上报"）没跑完。自检要的是**确定性**，不该被生产决策层中途接管；
     * 手动诊断入口（{@code forceOnce}）**不受**此开关影响，仍然可用。
     */
    public static void suspend(BotPlayer bot, int ticks) {
        State state = state(bot);
        state.suspendedUntilTick = bot.getServer().getTickCount() + Math.max(0, ticks);
        state.suspendLogged = false;
        BotLog.info("[Goal] 决策触发已暂停 {} tick（自检模式）", ticks);
    }

    /** 当前是否处于自检暂停窗口（事件生产者据此决定"只记录不通知"）。 */
    public static boolean isSuspended(BotPlayer bot) {
        State state = STATES.get(bot.getUUID());
        return state != null && (state.selfCheckHold
                || bot.getServer().getTickCount() < state.suspendedUntilTick);
    }

    /**
     * **自检按住开关**（T1 / R-3）：会话每 tick 按"当前任务是不是自检"设置。
     *
     * <p>为什么需要它（而不是只靠 {@link #suspend(int)} 的定长窗口）：2026-09-14 三路审计实测 ——
     * 主回归电池跑 **~3400 tick**，而自检暂停窗口是 **1200 tick** ⇒ 窗口在任务结束前就过期，
     * **终态那一击照样把 LLM 招来**（round-16 日志 `:3951 decision_request trigger=terminal:RegressionBatteryTask`
     * 就是现场）。而且 `Task.isSelfCheck()` 当时只认 `*CheckTask`/`*ProbeTask` ⇒ `RegressionBatteryTask`
     * **根本不在覆盖里**（两套约定漂移 18 个类，见 R-3）。
     * 现在判据统一 + 按住随任务存续 ⇒ 整个自检期间（含终态那一刻）都不会发起决策。
     */
    public static void setSelfCheckHold(BotPlayer bot, boolean hold) {
        State state = state(bot);
        if (state.selfCheckHold == hold) {
            return;
        }
        state.selfCheckHold = hold;
        BotLog.info("[Goal] 自检按住（selfCheckHold）={} —— {}决策触发",
                hold, hold ? "自检期间不发起任何" : "恢复正常");
    }

    /** **事件阈值**触发的决策（S4）：工具见底 / 卡住 —— 有节流，重复事件不会连环调用。 */
    public static void onEvent(BotPlayer bot, String summary) {
        maybeTrigger(bot, "event:" + summary);
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
        // 手动触发 = 诊断模式：把 relay/proxy/direct 三条路都试一遍并逐条登记
        LlmClient.forgetChosenPath();
        fire(bot, state, "manual", null);
        return "sent(诊断模式：三条路径都会试一遍)";
    }

    /**
     * **操作者直连指令**（连通性测试通道，2026-09-13 用户要求）。
     *
     * <p>与正常决策的区别（**只为测试通路而存在**）：
     * <ul>
     *   <li>user prompt = **操作者原话**（"直接执行它"），不是"从菜单里挑一个"；</li>
     *   <li>解析时**放行候选菜单校验**（`craft` 的 item 不在清单里也照做 —— 测试时包里可能没材料，
     *       执行层会如实报 `missing_ingredients`，那本身就是通路证据）；</li>
     *   <li>**仍然只走既定执行入口**（`execute()` → `BotManager.assignJob`），不新增旁路；</li>
     *   <li>不影响生产语义：自动触发（终态/事件/空闲）一律继续按"只能从菜单选"校验。</li>
     * </ul>
     *
     * @return 一行结果（给聊天栏）
     */
    public static String instruct(BotPlayer bot, ServerPlayer observer, String instruction) {
        State state = state(bot);
        state.observer = observer;
        if (state.pending != null) {
            return "busy:已有一个请求在飞";
        }
        if (!LlmConfig.get().usable()) {
            return "LLM 未配置（" + LlmConfig.get().describe() + "）";
        }
        if (instruction == null || instruction.isBlank()) {
            return "指令为空";
        }
        LlmClient.forgetChosenPath();
        String prompt = """
                操作者指令（**直接执行它**；不要做目标选择，不要回 no_op/report_status）：
                %s

                当前状态（服务端权威事实，JSON）：
                %s

                请只回**一个** JSON 对象，表示执行该指令所需的动作（从词汇表里选）。
                """.formatted(instruction, DecisionSnapshot.build(bot, null, "operator").toString());
        fire(bot, state, "operator", prompt);
        return "sent(直连指令，动作结果见聊天/日志 [Goal] directed_result)";
    }

    public static String describe() {
        return LlmConfig.get().describe();
    }

    private static void maybeTrigger(BotPlayer bot, String trigger) {
        State state = state(bot);
        LlmConfig config = LlmConfig.get();
        if (!config.usable()) {
            return;
        }
        if (state.pending != null) {
            // ⭐ `D-338` 附注十五：**这条以前是静默丢弃**（连日志都没有）⇒ 决策层永远不知道自己漏了事件
            noteDroppedTrigger(bot, state, trigger, "in_flight（已有决策在飞）");
            return;
        }
        long now = bot.getServer().getTickCount();
        // 口径合一（T1/R-3）：这里不再直接用 `suspendedUntilTick` 比较，而是走 `isSuspended()`，
        // 否则 `selfCheckHold` 会被漏掉（正是"两处各写一份判据必然漂移"的同一个病）。
        if (isSuspended(bot)) {
            if (!state.suspendLogged) {
                state.suspendLogged = true;
                BotLog.info("[Goal] trigger_skipped reason=suspended trigger={} until={} hold={}",
                        trigger, state.suspendedUntilTick, state.selfCheckHold);
            }
            return;
        }
        if (state.lastRequestTick != Long.MIN_VALUE && now - state.lastRequestTick < config.minIntervalTicks()) {
            noteDroppedTrigger(bot, state, trigger,
                    "throttle（距上次 " + (now - state.lastRequestTick) + "tick < " + config.minIntervalTicks() + "）");
            return;
        }
        if (now - state.minuteStartTick >= 1200L) {
            state.minuteStartTick = now;
            state.minuteRequests = 0;
        }
        if (state.minuteRequests >= config.maxRequestsPerMinute()) {
            noteDroppedTrigger(bot, state, trigger,
                    "rate_limit（已用 " + state.minuteRequests + "/min）");
            return;
        }
        fire(bot, state, trigger, null);
    }

    /**
     * 发一次请求。
     *
     * <p>**为什么把 user prompt 作为参数传**（2026-09-13 事故教训）：原先直连指令靠 `State.directedPrompt`
     * 这个**可变"邮箱"**在 `instruct()` 与 `fire()` 之间传参，而 `fire()` 里"先清空再使用"的顺序错误
     * ⇒ 指令被自己擦掉，LLM 收到普通决策 prompt（"让它做工作台，它却发了伐木指令"）。
     * 现在 prompt 是**显式参数**，`directed` 由"是否传了 prompt"直接推出，无可变状态可踩。
     *
     * @param directedPrompt 非 null = 操作者直连指令（见 {@link #instruct}）；null = 正常决策
     */
    /**
     * ⭐ `D-338` 附注十五：**触发被闸门丢掉时要留痕 + 计数**（以前三条丢弃路径里有一条完全静默）。
     * 计数会写进下一次快照（`droppedTriggers`）⇒ 决策层知道"你上次之后有 N 次事件没能叫到你"。
     */
    private static void noteDroppedTrigger(BotPlayer bot, State state, String trigger, String reason) {
        state.droppedTriggers++;
        state.lastDroppedReason = reason;
        BotLog.info("[Goal] trigger_dropped reason={} trigger={} droppedSinceLastDecision={}",
                reason, trigger, state.droppedTriggers);
    }

    /** **快照/夹具用**：自上次决策以来被丢弃的触发次数。 */
    public static int droppedTriggers(BotPlayer bot) {
        State state = STATES.get(bot == null ? null : bot.getUUID());
        return state == null ? 0 : state.droppedTriggers;
    }

    /** **夹具用**：最近一次被丢弃的触发**原因**（判据据此断言"是哪道闸门拦的"）。 */
    public static String lastDroppedReason(BotPlayer bot) {
        State state = STATES.get(bot == null ? null : bot.getUUID());
        return state == null ? "" : state.lastDroppedReason;
    }

    private static void fire(BotPlayer bot, State state, String trigger, String directedPrompt) {
        LlmConfig config = LlmConfig.get();
        state.lastRequestTick = bot.getServer().getTickCount();
        state.minuteRequests++;
        state.pendingTrigger = trigger;
        state.pendingDirected = directedPrompt != null;
        String system = VOCABULARY + (config.systemPrompt().isBlank() ? "" : "\n" + config.systemPrompt());
        // S2：决策前先由**确定性层**生成候选菜单（有界），prompt 里带上，LLM 只能引用其中的 id
        state.lastMenu = CandidateMenu.build(bot);
        String prompt = directedPrompt != null
                ? directedPrompt
                : DecisionSnapshot.buildPrompt(bot, state.lastMenu, trigger);
        BotLog.info("[Goal] decision_request trigger={} mode={} model={} calledAtTick={}",
                trigger, directedPrompt == null ? "normal" : "directed", config.model(),
                state.lastRequestTick);
        DecisionTrace.request(bot, trigger, state.lastMenu.entries().size());
        state.pending = LlmClient.askAsync(system, prompt);
    }

    private static void pollResult(BotPlayer bot, State state) {
        CompletableFuture<LlmClient.Reply> pending = state.pending;
        if (pending == null) {
            return;
        }
        if (!pending.isDone()) {
            // **看门狗**（2026-09-12 教训）：HttpClient 的超时在某些挂起形态下不会触发，
            // 于是"玩家点一次就永远没有下文"。这里按"请求发出时刻 + timeout + 5s"兜底，
            // 超时就取消并**如实回报**，绝不让决策层静默卡死。
            long waited = bot.getServer().getTickCount() - state.lastRequestTick;
            long limitTicks = (LlmConfig.get().timeoutMs() + 5000L) / 50L;   // tick ≈ 50ms
            if (waited > limitTicks) {
                pending.cancel(true);
                state.pending = null;
                BotLog.warn("[Goal] decision_timeout trigger={} waited={}tick（>{}ms+5s）"
                                + "⇒ 取消并保持确定性策略", state.pendingTrigger, waited,
                        LlmConfig.get().timeoutMs());
                DecisionTrace.failure(bot, state.pendingTrigger, "timeout",
                        "waited=" + waited + "tick");
                tell(bot, state, "[alice] 决策层请求超时（" + (LlmConfig.get().timeoutMs() + 5000)
                        + "ms）⇒ 保持确定性策略");
            }
            return;
        }
        state.pending = null;
        LlmClient.Reply reply;
        try {
            reply = pending.get();
        } catch (Exception ex) {
            BotLog.warn("[Goal] decision_failed trigger={} {}", state.pendingTrigger, ex.toString());
            DecisionTrace.failure(bot, state.pendingTrigger, "exception", ex.toString());
            tell(bot, state, "[alice] 决策层请求失败：" + ex.getClass().getSimpleName());
            return;
        }
        if (!reply.ok()) {
            BotLog.warn("[Goal] decision_failed trigger={} error={} latency={}ms",
                    state.pendingTrigger, reply.error(), reply.latencyMs());
            DecisionTrace.failure(bot, state.pendingTrigger, "llm_error", reply.error());
            tell(bot, state, "[alice] 决策层不可用（" + reply.error() + "）⇒ 保持确定性策略");
            return;
        }
        String trimmed = reply.text().length() > LlmConfig.get().maxReplyChars()
                ? reply.text().substring(0, LlmConfig.get().maxReplyChars())
                : reply.text();
        boolean directed = state.pendingDirected;
        // **F1**：这一条决定来自模型 ⇒ 标注驱动者（供终态/快照归因）
        Driver.set(bot, Driver.LLM);
        GoalAction action = GoalAction.parse(trimmed, bot, state.lastMenu, directed);
        if (directed) {
            // 直连测试通道：**把 raw 与解析结果打成一行终态日志**（操作者测试连通性用）
            BotLog.info("[Goal] directed_result raw={} → {}", trimmed.replace('\n', ' ').trim(),
                    describeAction(action));
        }
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
        if (action instanceof GoalAction.MaintainTool maintain) {
            return "MaintainTool(" + maintain.kind() + ")";
        }
        if (action instanceof GoalAction.Craft craft) {
            return "Craft(" + craft.item() + " x" + craft.count()
                    + (craft.clamps().isEmpty() ? "" : " clamps=" + craft.clamps()) + ")";
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
        clearRefusal(state);
        state.droppedTriggers = 0;   // ⭐ 附注十五：计数语义 = "自**上一次真正做出决策**以来"
        if (action instanceof GoalAction.StartJob start) {
            boolean ok = BotManager.assignJob(bot, state.observer, start.request(), false);
            BotLog.info("[Goal] execute action=start_job ok={} trigger={}", ok, trigger);
            DecisionTrace.result(bot, trigger, "start_job", ok ? "executed" : "refused",
                    start.request().describe(), 0L);
            tell(bot, state, (ok ? "[alice] 决策层：已起 Job " + start.request().describe()
                    : "[alice] 决策层：起 Job 失败（bot 正忙？）") + "（触发=" + trigger + "）");
            return;
        }
        clearRefusal(state);
        if (action instanceof GoalAction.StopCurrent stop) {
            String stopped = BotManager.stopTask(bot, "llm:" + stop.reason());
            BotLog.info("[Goal] execute action=stop_current stopped={} trigger={}", stopped, trigger);
            DecisionTrace.result(bot, trigger, "stop_current", stopped == null ? "no_task" : "executed",
                    stop.reason(), 0L);
            tell(bot, state, "[alice] 决策层：已停止 " + (stopped == null ? "（当时没有任务）" : stopped)
                    + "（触发=" + trigger + "）");
            return;
        }
        clearRefusal(state);
        if (action instanceof GoalAction.Craft craft) {
            // A5：合成/熔炼也走**唯一入口**（JobRequest → JobLauncher → assignJob），
            // 站点由玩家的选择决定（请求里不带站点）；世界写入（按需装配）在 Job 里走预算闸门。
            var request = com.dddgn.alice.job.JobRequest.craft(bot.blockPosition(),
                    craft.item(), craft.count(), 3600);
            boolean ok = BotManager.assignJob(bot, state.observer, request, false);
            BotLog.info("[Goal] execute action=craft ok={} trigger={} raw={}", ok, trigger, craft.note());
            DecisionTrace.result(bot, trigger, "craft", ok ? "executed" : "refused",
                    request.describe(), 0L);
            tell(bot, state, (ok ? "[alice] 决策层：已起合成 Job " + request.describe()
                    : "[alice] 决策层：起合成 Job 失败（bot 正忙？）") + "（触发=" + trigger + "）");
            return;
        }
        clearRefusal(state);
        if (action instanceof GoalAction.MaintainTool maintain) {
            boolean accepted = BotManager.assignToolMaintenance(bot, state.observer, maintain.kind());
            BotLog.info("[Goal] execute action=maintain_tool kind={} ok={} trigger={}",
                    maintain.kind(), accepted, trigger);
            DecisionTrace.result(bot, trigger, "maintain_tool", accepted ? "executed" : "refused",
                    maintain.kind() + " " + maintain.note(), 0L);
            tell(bot, state, (accepted ? "[alice] 决策层：维护工具 " + maintain.kind().label()
                    : "[alice] 决策层：维护工具失败（bot 正忙？）") + "（触发=" + trigger + "）");
            clearRefusal(state);
            return;
        }
        if (action instanceof GoalAction.ReportStatus report) {
            BotLog.info("[Goal] execute action=report_status note={}", report.note());
            DecisionTrace.result(bot, trigger, "report_status", "executed", report.note(), 0L);
            tell(bot, state, "[alice] 决策层状态：" + report.note());
            return;
        }
        clearRefusal(state);
        if (action instanceof GoalAction.NoOp noop) {
            BotLog.info("[Goal] execute action=no_op note={}", noop.note());
            DecisionTrace.result(bot, trigger, "no_op", "executed", noop.note(), 0L);
            tell(bot, state, "[alice] 决策层：不动（" + noop.note() + "）");
            return;
        }
        GoalAction.Refused refused = (GoalAction.Refused) action;
        BotLog.warn("[Goal] execute action=refused reason={} trigger={}", refused.reason(), trigger);
        DecisionTrace.result(bot, trigger, "refused", "refused", refused.reason(), 0L);
        // J-7：记下来 —— 下一轮 prompt 会带上"上次为什么被拒"，避免 LLM 反复撞同一堵墙
        noteRefusal(bot, refused.reason());
        tell(bot, state, "[alice] 决策层动作被拒绝：" + refused.reason());
    }

    /**
     * 登记一次"动作被拒"（**执行路径与自检共用同一条入口**，避免自检另走一条假路径）。
     */
    public static void noteRefusal(BotPlayer bot, String reason) {
        State state = state(bot);
        state.lastRefusalReason = reason == null ? "" : reason;
        state.lastRefusalTick = bot.getServer().getTickCount();
        state.lastRefusalCount++;
    }

    /** 清掉拒绝状态（自检收尾用；执行路径在动作被接受时自行清理）。 */
    public static void clearRefusal(BotPlayer bot) {
        clearRefusal(state(bot));
    }

    /** 动作被接受执行 ⇒ 上一次的拒绝理由过期（下一轮不该再看到它）。 */
    private static void clearRefusal(State state) {
        state.lastRefusalReason = "";
        state.lastRefusalTick = -1L;
        state.lastRefusalCount = 0;
    }

    /**
     * **读取上一次决策被拒的理由**（J-7）：供 prompt 快照与 `alice:bot_report` 使用。
     * 返回空串 = 没有待处理的拒绝。
     */
    public static String lastRefusal(BotPlayer bot) {
        State state = STATES.get(bot.getUUID());
        if (state == null || state.lastRefusalReason.isBlank()) {
            return "";
        }
        return state.lastRefusalReason + "（tick=" + state.lastRefusalTick
                + " 连续=" + state.lastRefusalCount + "）";
    }

    private static void tell(BotPlayer bot, State state, String text) {
        java.util.List<UUID> targets = notifyTargets(bot, state.observer);
        int delivered = 0;
        for (UUID id : targets) {
            ServerPlayer target = bot.getServer().getPlayerList().getPlayer(id);
            if (target == null || target.hasDisconnected() || target.isRemoved()) {
                continue;
            }
            target.sendSystemMessage(Component.literal(text));
            delivered++;
        }
        NOTIFIED.merge(bot.getUUID(), 1, Integer::sum);
        BotLog.info("[Goal] notify bot={} targets={} delivered={} automatic={} text={}",
                bot.getName().getString(), targets.size(), delivered, state.observer == null, text);
    }

    /**
     * ⭐ **通知对象 = 单一出处**（`D-338` 附注十二，夹具据此断言）：
     * 有 observer（手动触发 / `instruct`）⇒ **只通知它**；否则（自动触发 `terminal:*` / `event:*`）
     * ⇒ **通知创建者**（`BotOwnership`，`D-319`）；**未登记 ⇒ 空**（不猜、不静默补）。
     *
     * <p>为什么必须这样（2026-09-19 客户端实测）：自动触发路径**没有 observer**，于是决策层的回执
     * （"已起 Job …"）**一个字都不发** ⇒ 玩家只能翻日志才知道"这活是谁起的"。现场：用户点的
     * 一次性砍树**被如实拒绝**后，LLM 4 秒内自起 `region_lumber` 把用户保护区里的树砍了，
     * 聊天**零提示**，两轮把用户绕懵（详见 `D-338` 附注十一）。
     */
    public static java.util.List<UUID> notifyTargets(BotPlayer bot, ServerPlayer observer) {
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            return java.util.List.of(observer.getUUID());
        }
        com.dddgn.alice.bot.BotOwnership.Creator creator =
                com.dddgn.alice.bot.BotOwnership.creatorOfBot(bot);
        return creator.registered() ? java.util.List.of(creator.uuid()) : java.util.List.of();
    }

    /** **夹具用**：该 bot 的决策层回执一共送出过几次。 */
    public static int notifiedCount(BotPlayer bot) {
        return NOTIFIED.getOrDefault(bot.getUUID(), 0);
    }

    /** **夹具复位用**。 */
    public static void resetNotified(BotPlayer bot) {
        NOTIFIED.remove(bot.getUUID());
    }
}
