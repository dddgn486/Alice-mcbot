package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.bot.RecoveryStage;
import com.dddgn.alice.bot.TaskExecutionRecord;
import com.dddgn.alice.bot.TaskFailureReport;
import com.dddgn.alice.bot.TaskOutcome;
import com.dddgn.alice.decision.CandidateMenu;
import com.dddgn.alice.decision.DecisionSnapshot;
import com.dddgn.alice.decision.GoalDirector;
import com.dddgn.alice.job.collect.CollectJob;
import com.dddgn.alice.job.lumber.LumberJob;
import com.dddgn.alice.job.lumber.RegionLumberJob;
import com.dddgn.alice.job.mine.MineJob;
import com.dddgn.alice.job.mine.MineProductFilter;
import com.dddgn.alice.log.BotLog;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * **LLM 上抛契约自检**（基-5）：三件事各自可断言，纯计算，约 1 秒。
 *
 * <ul>
 *   <li><b>J-4</b> 四个 Job **真的覆写了** {@code failureReport()}（没覆写就退化成
 *       `phase=unknown` + 空 details，决策层拿到的 `lastTerminal` 等于没有信息）；</li>
 *   <li><b>J-6</b> 挖掘产物判定是**目标驱动 + 标签族**：指定 `#forge:ores/iron` 时只认铁系
 *       （**且认原矿兄弟标签**），未指定时认标签族/原版兜底，非矿物不认；</li>
 *   <li><b>J-7</b> **结构化拒绝回读**：动作被拒后，① `lastRefusal` 读得到原因、
 *       ② **下一轮 prompt 里真的带上了** `lastRefusal`、③ 动作被接受后清掉（不留 stale 理由）。</li>
 * </ul>
 */
public class LlmContractCheckTask implements Task {

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private int ticks;
    private boolean done;

    public LlmContractCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "LlmContractCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(bot.blockPosition());
    }

    @Override
    public String failureReason() {
        return failures.isEmpty() ? "" : String.join(",", failures);
    }

    @Override
    public String terminalReason() {
        return done ? (failures.isEmpty() ? "passed" : "failed") : "";
    }

    @Override
    public Status tick() {
        if (++ticks > 60) {
            return finish("timeout");
        }
        if (ticks > 1) {
            return done ? (failures.isEmpty() ? Status.DONE : Status.FAILED) : Status.RUNNING;
        }
        runChecks();
        done = true;
        return Status.RUNNING;
    }

    private void runChecks() {
        checkJobFailureReports();
        checkSnapshotTerminalIdentity();
        checkSnapshotFailureFields();
        checkProductFilter();
        checkRefusalReadback();
        checkNotifyTargets();
        checkDroppedTriggerVisibility();
        checkFixtureTerminalSilent();
        checkFixtureEventSilent();
        checkLoopAdmissionControl();

        String summary = "job_failure_reports=" + verdict("job_failure_reports")
                + " notify_targets=" + verdict("notify_targets")
                + " dropped_triggers_visible=" + verdict("dropped_triggers_visible")
                + " fixture_terminal_silent=" + verdict("fixture_terminal_silent")
                + " fixture_event_silent=" + verdict("fixture_event_silent")
                + " loop_admission_control=" + verdict("loop_admission_control")
                + " product_filter_target=" + verdict("product_filter_target")
                + " product_filter_default=" + verdict("product_filter_default")
                + " refusal_readback=" + verdict("refusal_readback")
                + " verdict=" + (failures.isEmpty() ? "PASS" : "FAIL");
        BotLog.info("[LlmContract] SUMMARY {}", summary);
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[LlmContract] " + summary));
        }
    }

    /**
     * ⭐ `D-338` 附注十五：**被闸门丢弃的触发必须如实写进快照**
     * （`droppedTriggers` = "自你上次决策以来有 N 次事件没能叫到你"）。
     *
     * <p>这里只断言"**字段在、且与计数器口径一致、prompt 里解释了它**"；真正走一遍丢弃路径需要
     * 网络/节流（夹具不联网）⇒ 丢弃路径的**留痕**由源码规则 `tools/kernel-predicates.py`
     * 的 `rule_stop_event_ring` + `[Goal] trigger_dropped` 日志在客户端侧验证。
     */
    private void checkDroppedTriggerVisibility() {
        int dropped = GoalDirector.droppedTriggers(bot);
        com.google.gson.JsonObject snapshot =
                com.dddgn.alice.decision.DecisionSnapshot.build(bot, null, "fixture");
        boolean fieldOk = snapshot.has("droppedTriggers")
                && snapshot.get("droppedTriggers").getAsInt() == dropped;
        String prompt = com.dddgn.alice.decision.DecisionSnapshot.buildPrompt(bot, null, "fixture");
        boolean explained = prompt.contains("droppedTriggers");
        check("dropped_triggers_visible", fieldOk && explained,
                "field=" + fieldOk + " explained_in_prompt=" + explained + " dropped=" + dropped);
    }

    /**
     * ⭐ `D-339`：**夹具终态不许交给决策层** —— 阻断"夹具失败 ⇒ LLM 自起一个它无权做的任务 ⇒ 空转"。
     *
     * <p>客户端实测那条链（2026-09-19 19:06）：夹具一次性砍树被如实拒绝 ⇒ LLM 自起
     * `region_lumber` ⇒ 封顶 `L1` 砍不动 ⇒ `viable=0` **空转到 `maxTicks=24000`（20 分钟）**。
     *
     * <p>三件断言：① 夹具驱动 ⇒ **不交**（返回 `false`）+ 记一条 `fixture_driver` 丢弃；
     * ② 非夹具驱动（这里用 `llm`）⇒ **照旧交**（返回 `true`，闸门**不是一刀切**）；
     * ③ 控制组挂 `suspend` ⇒ 不真的发请求（**夹具不联网**，client 上跑电池也不该产生 API 调用）。
     *
     * <p>**反向对照**（已实测）：把 `onTaskTerminal` 里的 `Driver.FIXTURE` 分支删掉
     * ⇒ ① 变红（返回 `true` 且无丢弃记录）。
     */
    private void checkFixtureTerminalSilent() {
        String previousDriver = com.dddgn.alice.decision.Driver.of(bot);
        try {
            // ① 夹具终态 ⇒ 拦下 + 留痕（且原因必须是这道闸门，不是节流/限流）
            com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.FIXTURE);
            int before = GoalDirector.droppedTriggers(bot);
            boolean handed = GoalDirector.onTaskTerminal(bot, "lumber", "no_reachable_candidate");
            boolean blocked = !handed
                    && GoalDirector.droppedTriggers(bot) == before + 1
                    && GoalDirector.FIXTURE_TERMINAL_REASON.equals(GoalDirector.lastDroppedReason(bot));
            // ② 非夹具（llm）⇒ 照旧交（挂 1 tick 暂停保证控制组不产生真实请求）
            GoalDirector.suspend(bot, 1);
            com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.LLM);
            boolean handed2 = GoalDirector.onTaskTerminal(bot, "lumber", "no_reachable_candidate");
            check("fixture_terminal_silent", blocked && handed2,
                    "fixture_blocked=" + blocked + " handed=" + handed
                            + " reason=" + GoalDirector.lastDroppedReason(bot)
                            + " non_fixture_handed=" + handed2);
        } finally {
            com.dddgn.alice.decision.Driver.set(bot, previousDriver);
        }
    }

    /**
     * ⭐ `D-340`：**夹具驱动的事件也不许交给决策层**（`D-339` 只拦了终态，事件通道还开着）。
     *
     * <p>现场（客户端 2026-09-19 19:04–19:05）：物品右键起的 `region_lumber` 一边跑一边把 `PROGRESS`
     * 喂给 LLM，**5 次**触发决策；根因 = `DecisionEvents.notifyIfAllowed` 的通知路径**只看 `isSuspended`**。
     *
     * <p>⚠️ **为什么这里直接驱动 `GoalDirector.onEvent`，而不是走 `DecisionEvents.emit`**：
     * 本夹具的类名含 `check` ⇒ `Task.isSelfCheck()=true` ⇒ 运行期 `selfCheckHold=true`
     * ⇒ `notifyIfAllowed` 会**先**短路（"自检暂停：不通知决策层"），被测的那道闸门根本走不到
     * ⇒ 断言会**假红**。**真实链路由客户端日志验证**（非自检窗口下 `[Events] PROGRESS` 后应当
     * 只多一行 `trigger_dropped reason=fixture_driver（夹具驱动的事件不交给决策层）`，且**没有**
     * `[Goal] decision_action`）。
     *
     * <p>**反向对照**：把 `onEvent` 里的 `Driver.FIXTURE` 分支删掉 ⇒ ① 变红（返回 `true` 且无丢弃记录）。
     */
    private void checkFixtureEventSilent() {
        String previousDriver = com.dddgn.alice.decision.Driver.of(bot);
        try {
            // ① 夹具驱动的事件 ⇒ 拦下 + 留痕（原因必须是这道闸门，不是节流/限流）
            com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.FIXTURE);
            int before = GoalDirector.droppedTriggers(bot);
            boolean handed = GoalDirector.onEvent(bot, "PROGRESS:自检夹具事件");
            boolean blocked = !handed
                    && GoalDirector.droppedTriggers(bot) == before + 1
                    && GoalDirector.FIXTURE_EVENT_REASON.equals(GoalDirector.lastDroppedReason(bot));
            // ② 非夹具（llm）⇒ 照旧交（挂 1 tick 暂停保证控制组不产生真实请求）
            GoalDirector.suspend(bot, 1);
            com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.LLM);
            boolean handed2 = GoalDirector.onEvent(bot, "PROGRESS:控制组事件");
            check("fixture_event_silent", blocked && handed2,
                    "fixture_blocked=" + blocked + " handed=" + handed
                            + " reason=" + GoalDirector.lastDroppedReason(bot)
                            + " non_fixture_handed=" + handed2);
        } finally {
            com.dddgn.alice.decision.Driver.set(bot, previousDriver);
        }
    }

    /**
     * ⭐ `D-342`：**最小跨任务循环检测（受理闸）** —— 同一 `(kind|目标)` 在窗口内反复失败 ⇒ **拒再起**。
     *
     * <p>现场（客户端三次：16:53 / 17:30 / 19:06）：一个目标失败后决策层**换条路重试**（一次性被拒 ⇒
     * 自起常驻区域任务）。用户口径："任务要如实失败，不能继续跑"。
     *
     * <p>五件断言：① 前两次放行；② 第 3 次（窗口内已失败 `LOOP_BLOCK_AT` 次）⇒ **拒**，且理由码 =
     * `repeat_failure`、理由里**带身份与次数**（LLM 要能据此换目标）；③ **玩家显式发起豁免**
     * （`IN_GAME_PLAYER`/`FIXTURE` ⇒ 放行，阶梯对玩家不缩水）；④ **成功一次 ⇒ 复位**；
     * ⑤ **别的目标不受牵连**（闸门按身份，不是全局刹车）。
     *
     * <p>**反向对照**（已实测）：把 `loopRefusal` 的阈值判定注入成恒 `null`
     * ⇒ ② 变红（`blocked=false`）；把 `execute` 里那道受理闸删掉 ⇒ 源码规则 `rule_loop_admission` 变红。
     */
    private void checkLoopAdmissionControl() {
        String previousDriver = com.dddgn.alice.decision.Driver.of(bot);
        String target = "tree@fixture-loop,64,0";
        try {
            com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.LLM);
            // ① 前两次：放行
            boolean first = GoalDirector.loopRefusal(bot, com.dddgn.alice.decision.Driver.LLM,
                    "lumber", target) == null;
            GoalDirector.noteAttempt(bot, "lumber", target);
            GoalDirector.noteTerminalOutcome(bot, "lumber", true);
            boolean second = GoalDirector.loopRefusal(bot, com.dddgn.alice.decision.Driver.LLM,
                    "lumber", target) == null;
            GoalDirector.noteAttempt(bot, "lumber", target);
            GoalDirector.noteTerminalOutcome(bot, "lumber", true);
            // ② 第 3 次：拒（理由码 + 身份 + 次数都要在）
            String refusal = GoalDirector.loopRefusal(bot, com.dddgn.alice.decision.Driver.LLM,
                    "lumber", target);
            boolean blocked = refusal != null
                    && refusal.contains(GoalDirector.LOOP_REFUSAL_CODE)
                    && refusal.contains(target)
                    && refusal.contains(String.valueOf(GoalDirector.LOOP_BLOCK_AT))
                    && GoalDirector.loopFailCount(bot, "lumber", target) == GoalDirector.LOOP_BLOCK_AT;
            // ③ 玩家显式发起 ⇒ 豁免
            boolean exempt = GoalDirector.loopRefusal(bot, com.dddgn.alice.decision.Driver.FIXTURE,
                    "lumber", target) == null
                    && GoalDirector.loopRefusal(bot, com.dddgn.alice.decision.Driver.IN_GAME_PLAYER,
                            "lumber", target) == null;
            // ④ 成功一次 ⇒ 复位
            GoalDirector.noteAttempt(bot, "lumber", target);
            GoalDirector.noteTerminalOutcome(bot, "lumber", false);
            boolean reset = GoalDirector.loopRefusal(bot, com.dddgn.alice.decision.Driver.LLM,
                    "lumber", target) == null
                    && GoalDirector.loopFailCount(bot, "lumber", target) == 0;
            // ⑤ 别的目标不受牵连
            boolean other = GoalDirector.loopRefusal(bot, com.dddgn.alice.decision.Driver.LLM,
                    "lumber", "tree@fixture-loop,64,77") == null;
            // ⑥ ⭐ **跨写法**（2026-09-19 客户端实测漏过的那一条）：生产的 kind 有**两种写法** ——
            //     受理侧 = `JobRequest.Kind.name()`（枚举 ⇒ 大写 `REGION_LUMBER`）；
            //     终态侧 = `Task.taskName()`（实测 ⇒ 小写 `region_lumber`）。
            //     不归一到同一身份 ⇒ 记账静默失败 ⇒ 计数恒 0 ⇒ 闸门永不触发（客户端三次全放行）。
            boolean spellingNormalized = GoalDirector.attemptIdentity("REGION_LUMBER", target)
                    .equals(GoalDirector.attemptIdentity("region_lumber", target));
            GoalDirector.noteAttempt(bot, "REGION_LUMBER", target);            // 受理侧写法（大写）
            GoalDirector.noteTerminalOutcome(bot, "region_lumber", true);      // 终态侧写法（小写）
            boolean crossSpelling = GoalDirector.loopFailCount(bot, "region_lumber", target) == 1
                    && GoalDirector.loopFailCount(bot, "REGION_LUMBER", target) == 1;
            GoalDirector.noteAttempt(bot, "region_lumber", target);            // 清账（不留痕）
            GoalDirector.noteTerminalOutcome(bot, "region_lumber", false);
            check("loop_admission_control",
                    first && second && blocked && exempt && reset && other && spellingNormalized
                            && crossSpelling,
                    "first=" + first + " second=" + second + " third_blocked=" + blocked
                            + " player_exempt=" + exempt + " reset_on_success=" + reset
                            + " other_target_ok=" + other
                            + " spelling_normalized=" + spellingNormalized
                            + " cross_spelling_accounting=" + crossSpelling
                            + " refusal=" + refusal);
        } finally {
            com.dddgn.alice.decision.Driver.set(bot, previousDriver);
        }
    }

    /**
     * ⭐ `D-338` 附注十二：**自动触发（无 observer）时，决策层的回执必须有人收到** —— 通知**创建者**
     * （`BotOwnership`，`D-319`）；未登记 ⇒ 空（不猜）；有 observer ⇒ **只通知它**（不捎带创建者）。
     *
     * <p>现场（2026-09-19 客户端）：用户点的一次性砍树被如实拒绝后，LLM 4 秒内自起 `region_lumber`
     * 砍掉用户保护区里的树 —— 因为自动触发**没有 observer**，回执**一个字都没发**，聊天零提示。
     */
    private void checkNotifyTargets() {
        com.dddgn.alice.bot.BotOwnership.Creator before =
                com.dddgn.alice.bot.BotOwnership.creatorOfBot(bot);
        try {
            // ① 未登记 ⇒ 空（不猜、不静默补）
            com.dddgn.alice.bot.BotOwnership.applyTo(bot, com.dddgn.alice.bot.BotOwnership.NONE);
            boolean unregisteredOk = GoalDirector.notifyTargets(bot, null).isEmpty();
            // ② 登记后：自动触发 ⇒ 通知创建者
            java.util.UUID creatorId = java.util.UUID.nameUUIDFromBytes("alice-notify-fixture".getBytes());
            com.dddgn.alice.bot.BotOwnership.applyTo(bot,
                    new com.dddgn.alice.bot.BotOwnership.Creator(creatorId, "fixture_creator"));
            var automatic = GoalDirector.notifyTargets(bot, null);
            boolean automaticOk = automatic.size() == 1 && creatorId.equals(automatic.get(0));
            // ③ 有 observer（手动触发）⇒ 只通知它，不捎带创建者
            var manual = GoalDirector.notifyTargets(bot, bot);
            boolean manualOk = manual.size() == 1 && bot.getUUID().equals(manual.get(0));
            // ④ 回执计数器（供其它夹具做增量断言）
            GoalDirector.resetNotified(bot);
            int before2 = GoalDirector.notifiedCount(bot);
            check("notify_targets",
                    unregisteredOk && automaticOk && manualOk && before2 == 0,
                    "unregistered_empty=" + unregisteredOk + " automatic=creator:" + automaticOk
                            + " manual=observer_only:" + manualOk
                            + " counter_reset=" + (before2 == 0));
        } finally {
            com.dddgn.alice.bot.BotOwnership.applyTo(bot, before);
            GoalDirector.resetNotified(bot);
        }
    }

    /** J-4：四个 Job 必须自己给失败报告（否则退化默认实现 = 决策层看不到相位/细节）。 */
    private void checkJobFailureReports() {
        StringBuilder detail = new StringBuilder();
        boolean ok = true;
        for (Class<?> type : List.of(LumberJob.class, MineJob.class, RegionLumberJob.class, CollectJob.class)) {
            try {
                Class<?> declaring = type.getMethod("failureReport").getDeclaringClass();
                boolean own = declaring == type;
                ok &= own;
                detail.append(type.getSimpleName()).append('=').append(declaring.getSimpleName()).append(' ');
            } catch (NoSuchMethodException exception) {
                ok = false;
                detail.append(type.getSimpleName()).append("=MISSING ");
            }
        }
        check("job_failure_reports", ok, detail.toString().trim());
    }

    /**
     * **M4**：终态的失败事实必须是**字段**（相位 + 有界细节），而不是只留一段无结构文字。
     *
     * <p>为什么单独断言：`survey/08` §5.7 审计 G3 —— 真因**已经在**（`MineJob` 把逐候选的
     * `rejected()` 拼进 `lastResult`），但 LLM 只能读**一段文字**、难以可靠分支。
     * 判据 = ① 字段齐（code/phase/details）；② 细节**有界**（超长必须截断并如实标 `…`）；
     * ③ **没有失败就不许留 stale 字段**（否则 LLM 会照着上一轮的旧失败做决定）。
     */
    private void checkSnapshotFailureFields() {
        TaskFailureReport report = new TaskFailureReport("no_suitable_tool", "mine:select",
                "block@5,65,67:no_suitable_tool", RecoveryStage.NONE, List.of());
        JsonObject json = DecisionSnapshot.lastTerminalJson(
                syntheticRecord(TaskExecutionRecord.TerminalStatus.FAILED, report));
        boolean fields = "no_suitable_tool".equals(text(json, "failureCode"))
                && "mine:select".equals(text(json, "failurePhase"))
                && text(json, "failureDetails").contains("no_suitable_tool");

        String longDetails = "x".repeat(DecisionSnapshot.MAX_FAILURE_DETAILS + 50);
        JsonObject capped = DecisionSnapshot.lastTerminalJson(
                syntheticRecord(TaskExecutionRecord.TerminalStatus.FAILED,
                        new TaskFailureReport("boom", "phase", longDetails, RecoveryStage.NONE, List.of())));
        String cappedDetails = text(capped, "failureDetails");
        boolean bounded = cappedDetails.endsWith("…")
                && cappedDetails.length() == DecisionSnapshot.MAX_FAILURE_DETAILS + 1;

        JsonObject clean = DecisionSnapshot.lastTerminalJson(
                syntheticRecord(TaskExecutionRecord.TerminalStatus.COMPLETED, null));
        boolean noStale = !clean.has("failureCode") && !clean.has("failurePhase")
                && !clean.has("failureDetails");

        check("snapshot_failure_fields", fields && bounded && noStale,
                "fields=" + fields + " bounded=" + bounded + "(" + cappedDetails.length() + "/"
                        + DecisionSnapshot.MAX_FAILURE_DETAILS + ") noStale=" + noStale);
    }

    /**
     * **J-1 判据缺口（2026-09-16 全表复核发现）**：`terminalReason` 与 `botId` 是 D-134 加的字段，
     * 但 `llm_contract` 只断言了 failure* 三项 ⇒ 「字段进了 prompt」这件事**没有判据**。
     * 这里补上：终态理由与 bot 身份必须原样出现在快照 JSON 里（多 bot 归因 + "为什么结束" 都靠它）。
     */
    private void checkSnapshotTerminalIdentity() {
        TaskExecutionRecord record = syntheticRecord(TaskExecutionRecord.TerminalStatus.COMPLETED, null);
        JsonObject json = DecisionSnapshot.lastTerminalJson(record);
        boolean reason = "no_reachable_candidate".equals(text(json, "terminalReason"));
        boolean identity = record.botId().equals(text(json, "botId"));
        boolean kind = record.taskKind().equals(text(json, "kind"));
        // **F1 地基**：驱动者必须进快照且**非空**（`system` = 未归因，但绝不许是空/缺字段）
        String driver = text(json, "driver");
        boolean driverOk = driver != null && !driver.isBlank();
        check("snapshot_terminal_identity", reason && identity && kind && driverOk,
                "terminalReason=" + text(json, "terminalReason") + " botId=" + text(json, "botId")
                        + " kind=" + text(json, "kind") + " driver=" + driver);
    }

    /** 构造一条终态记录（M4 自检用：不必真把任务跑失败一次）。 */
    private TaskExecutionRecord syntheticRecord(TaskExecutionRecord.TerminalStatus status,
                                               TaskFailureReport failure) {
        String botId = bot.getUUID().toString();
        // F1：夹具合成的终态记录必须**带上驱动者**（且用夹具自己的身份，别谎称玩家/LLM）
        String driver = com.dddgn.alice.decision.Driver.FIXTURE;
        TaskOutcome outcome = new TaskOutcome("MineJob", "block@5,65,67", status, "failed:selfcheck",
                BlockPos.ZERO, failure, botId, "no_reachable_candidate", driver);
        return new TaskExecutionRecord("MineJob", "block@5,65,67", 0L, 10L, status, "failed:selfcheck",
                BlockPos.ZERO, "idle_after_cleanup", RecoveryStage.NONE, List.of(), outcome, botId,
                "no_reachable_candidate", driver);
    }

    private String text(JsonObject json, String key) {
        return json.has(key) ? json.get(key).getAsString() : "";
    }

    /** J-6：产物判定（目标驱动 + 标签族）。 */
    private void checkProductFilter() {
        MineProductFilter iron = MineProductFilter.forTag(TagKey.create(Registries.ITEM,
                new ResourceLocation("forge", "ores/iron")));
        boolean ironOk = iron.matches(new ItemStack(Items.IRON_ORE))
                && iron.matches(new ItemStack(Items.RAW_IRON))
                && !iron.matches(new ItemStack(Items.COAL))
                && !iron.matches(new ItemStack(Items.COBBLESTONE));
        check("product_filter_target", ironOk, "filter=" + iron.describe()
                + " ironOre=" + iron.matches(new ItemStack(Items.IRON_ORE))
                + " rawIron=" + iron.matches(new ItemStack(Items.RAW_IRON))
                + " coal=" + iron.matches(new ItemStack(Items.COAL)));

        MineProductFilter fallback = MineProductFilter.forTag(null);
        boolean fallbackOk = fallback.matches(new ItemStack(Items.RAW_IRON))
                && fallback.matches(new ItemStack(Items.DIAMOND))
                && fallback.matches(new ItemStack(Items.IRON_ORE))
                && !fallback.matches(new ItemStack(Items.COBBLESTONE))
                && !fallback.matches(new ItemStack(Items.OAK_LOG));
        check("product_filter_default", fallbackOk, "filter=" + fallback.describe()
                + " familyTags=" + MineProductFilter.familyTagCount());
    }

    /** J-7：拒绝理由要能被读回、进 prompt、且被接受后清掉。 */
    private void checkRefusalReadback() {
        String marker = "selfcheck_refusal_marker";
        GoalDirector.noteRefusal(bot, marker);
        String read = GoalDirector.lastRefusal(bot);
        String prompt = DecisionSnapshot.buildPrompt(bot, CandidateMenu.build(bot));
        boolean visible = read.contains(marker) && prompt.contains("lastRefusal") && prompt.contains(marker);
        GoalDirector.clearRefusal(bot);
        String afterClear = GoalDirector.lastRefusal(bot);
        boolean cleared = afterClear.isEmpty()
                && !DecisionSnapshot.buildPrompt(bot, CandidateMenu.build(bot)).contains(marker);
        check("refusal_readback", visible && cleared,
                "read=" + read + " promptHasField=" + prompt.contains("lastRefusal")
                        + " cleared=" + cleared);
    }

    private void check(String name, boolean ok, String detail) {
        if (!ok && !failures.contains(name)) {
            failures.add(name);
        }
        BotLog.info("[LlmContract] case={} result={} {}", name, ok ? "PASS" : "FAIL", detail);
    }

    private String verdict(String name) {
        return failures.contains(name) ? "FAIL" : "PASS";
    }

    private Status finish(String reason) {
        done = true;
        BotLog.warn("[LlmContract] 自检未完成 reason={}", reason);
        return Status.FAILED;
    }
}
