package com.dddgn.alice.decision;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.TaskFailureReport;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.bot.TaskExecutionRecord;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.survival.HazardState;
import com.dddgn.alice.survival.SurvivalSystem;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * **给 LLM 的权威状态快照**（D-135 / ② 决策层契约）。
 *
 * <p>设计纪律（对齐"服务端是真相"）：
 * <ul>
 *   <li>只放**服务端已经知道的事实**：bot 状态、背包摘要、当前任务、**上一条终态记录**（含 D-134 的
 *       `terminalReason` / `botId`）、区域状态、账本待清理数；</li>
 *   <li>**不放**方块级世界细节、不放日志、不放执行器内部状态——那些是确定性层的事，
 *       塞进去只会让 LLM 开始管执行细节（也就越过"只做目标级决策"的边界）；</li>
 *   <li>**不放 key**，也不放任何凭据。</li>
 * </ul>
 */
public final class DecisionSnapshot {

    private DecisionSnapshot() {
    }

    public static JsonObject build(BotPlayer bot) {
        return build(bot, null);
    }

    /** @param menu S2 候选菜单（null = 不附菜单，例如玩家手动报告时按需生成） */
    public static JsonObject build(BotPlayer bot, CandidateMenu menu) {
        return build(bot, menu, "-");
    }

    /**
     * @param trigger **为什么现在问我**（`terminal:…` / `event:…` / `manual` / `operator`）——
     *                2026-09-13 补：原先它只进日志/聊天，**LLM 看不到**，只能从 `task.lastTerminal`
     *                猜意图 ⇒ 它答 `no_op` 时无法判断是"上下文说别做"还是"没告诉它要干什么"。
     */
    public static JsonObject build(BotPlayer bot, CandidateMenu menu, String trigger) {
        JsonObject root = new JsonObject();
        JsonObject botNode = new JsonObject();
        botNode.addProperty("name", bot.getName().getString());
        botNode.addProperty("uuid", bot.getUUID().toString());
        botNode.addProperty("dimension", bot.level().dimension().location().toString());
        botNode.addProperty("pos", bot.blockPosition().toShortString());
        botNode.addProperty("health", bot.getHealth());
        botNode.addProperty("onGround", bot.onGround());
        botNode.addProperty("inLiquid", bot.isInWater() || bot.isInLava());
        HazardState hazard = SurvivalSystem.current(bot);
        botNode.addProperty("hazard", String.valueOf(hazard.type()));
        root.addProperty("trigger", trigger == null || trigger.isBlank() ? "-" : trigger);
        root.add("bot", botNode);

        // 背包摘要：前 8 个非空堆 + 空槽数（够 LLM 判断"要不要继续装/够不够工具"，又不灌爆上下文）
        JsonArray items = new JsonArray();
        var inventory = bot.getInventory();
        int free = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                free++;
            } else if (items.size() < 8) {
                items.add(stack.getCount() + "x"
                        + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()));
            }
        }
        JsonObject inv = new JsonObject();
        inv.add("top", items);
        inv.addProperty("freeSlots", free);
        root.add("inventory", inv);

        BotManager.BotSession session = BotManager.sessionOf(bot);
        JsonObject task = new JsonObject();
        if (session == null || session.currentTaskSummary() == null) {
            task.addProperty("current", "idle");
        } else {
            task.addProperty("current", session.currentTaskSummary());
        }
        task.addProperty("lastResult", session == null ? "-" : session.lastTaskResult());
        TaskExecutionRecord record = session == null ? null : session.lastExecutionRecord();
        if (record != null) {
            task.add("lastTerminal", lastTerminalJson(record));
        }

        // J-7：**结构化拒绝回读** —— LLM 必须能看到"上一轮动作为什么被拒"，
        // 否则它会反复给出同一个非法动作。
        String refusal = GoalDirector.lastRefusal(bot);
        if (!refusal.isBlank()) {
            task.addProperty("lastRefusal", refusal);
        }
        root.add("task", task);

        // 基-9：**工具事实**（手上有什么、还剩多少、有没有更好的在主背包）——
        // 以前 LLM 只能从 `TOOL_LOW` 事件的一行文字里猜，现在它是结构化事实。
        JsonObject tools = new JsonObject();
        tools.addProperty("summary", com.dddgn.alice.bot.ToolSupply.describe(bot));
        JsonArray toolRows = new JsonArray();
        for (var entry : com.dddgn.alice.bot.ToolSupply.inspectAll(bot).entrySet()) {
            com.dddgn.alice.bot.ToolSupply.Snapshot snapshot = entry.getValue();
            JsonObject row = new JsonObject();
            row.addProperty("kind", entry.getKey().name().toLowerCase(java.util.Locale.ROOT));
            row.addProperty("present", snapshot.present());
            if (snapshot.present()) {
                row.addProperty("inHotbar", snapshot.inHotbar());
                row.addProperty("remaining", snapshot.remaining());
                row.addProperty("max", snapshot.max());
                row.addProperty("spareInMain", snapshot.spareInMain());
                row.addProperty("bestMainRemaining", snapshot.bestMainRemaining());
            }
            toolRows.add(row);
        }
        tools.add("kinds", toolRows);
        root.add("tools", tools);

        var regionState = com.dddgn.alice.job.lumber.LumberRegionState.get(bot.getServer());
        var region = regionState.region(bot.getUUID());
        if (region != null) {
            JsonObject regionNode = new JsonObject();
            regionNode.addProperty("region", region.describe());
            regionNode.addProperty("baselineTrees", regionState.baselineTrees(bot.getUUID()));
            regionNode.addProperty("mySaplings", regionState.mySaplingCount(bot.getUUID()));
            regionNode.addProperty("pendingReplant", regionState.pendingReplantCount(bot.getUUID()));
            regionNode.addProperty("chopped", regionState.treesChopped(bot.getUUID()));
            regionNode.addProperty("planted", regionState.saplingsPlanted(bot.getUUID()));
            regionNode.addProperty("autoIdleStop", regionState.autoIdleStop(bot.getUUID()));
            root.add("region", regionNode);
        }

        // S1 事实层：**任务树**（Job → 内嵌子任务 → 阶段）——"汇报当前完整情况"的核心
        JsonArray tree = new JsonArray();
        if (session != null && session.currentTask() != null) {
            var current = session.currentTask();
            String progress = current instanceof com.dddgn.alice.job.Job job
                    ? job.progressSummary() : "";
            var children = current instanceof com.dddgn.alice.job.Job job
                    ? job.subTasks() : java.util.List.<com.dddgn.alice.task.TaskNode>of();
            JsonObject node = new JsonObject();
            node.addProperty("kind", current.taskName());
            node.addProperty("target", session.currentTarget() == null
                    ? "-" : session.currentTarget().describe());
            node.addProperty("ticks", bot.getServer().getTickCount() - session.taskStartTick());
            node.addProperty("progress", progress);
            if (!children.isEmpty()) {
                JsonArray childArray = new JsonArray();
                for (var child : children) {
                    JsonObject childNode = new JsonObject();
                    childNode.addProperty("kind", child.kind());
                    childNode.addProperty("target", child.target());
                    childNode.addProperty("phase", child.phase());
                    childNode.addProperty("ticks", child.ticks());
                    childNode.addProperty("progress", child.progress());
                    childNode.addProperty("lastFailure", child.lastFailure());
                    childArray.add(childNode);
                }
                node.add("children", childArray);
            }
            tree.add(node);
        }
        root.add("tree", tree);

        // S2 选择层：**候选菜单** —— 服务端算好的有界选项，LLM 只能引用其中的 id
        root.add("menu", menu == null ? new JsonArray() : menu.toJson());

        // S1 事实层：**最近事件环**（危险/失败/恢复/达成）——汇报按需读，不推送
        JsonArray events = new JsonArray();
        for (var event : BotEventLog.recent(bot, 12)) {
            JsonObject eventNode = new JsonObject();
            eventNode.addProperty("tick", event.tick());
            eventNode.addProperty("type", event.type());
            eventNode.addProperty("severity", event.severity());
            eventNode.addProperty("summary", event.summary());
            events.add(eventNode);
        }
        root.add("recentEvents", events);

        // S3：**未决请示** —— 决策层要知道"有人在等玩家拍板"，别把它当成"卡住"
        JsonArray pendingRequests = new JsonArray();
        for (var request : PermissionGate.pending(bot)) {
            JsonObject node = new JsonObject();
            node.addProperty("id", request.id());
            node.addProperty("capability", request.capability());
            node.addProperty("reason", request.reason());
            node.addProperty("default", request.defaultOption());
            node.addProperty("deadlineTick", request.deadlineTick());
            pendingRequests.add(node);
        }
        root.add("pendingRequests", pendingRequests);

        int pendingTemp = com.dddgn.alice.ledger.WorldModLedger
                .pendingForOwner(bot.serverLevel().getServer(), bot.getUUID()).size();
        JsonObject world = new JsonObject();
        world.addProperty("pendingTemporaryBlocks", pendingTemp);
        root.add("worldMod", world);

        // **S-9 消费（D-277）**：**伤害按事件观测**的实事交给决策层。
        // 为什么必须这样：`getHealth()` 的净变化会被 Alice 的无条件回血（+1HP/20t）抹平
        // ⇒ "这段时间挨了几下、多重"从血量里读不出来（D-261/D-271 实测）；台账来自 `LivingDamageEvent`，仍然准。
        // 口径：`windowTicks` 窗口内（不是全程累计），只读遥测 —— **不改任何维生/任务行为**。
        int window = 200;
        JsonObject damage = new JsonObject();
        damage.addProperty("windowTicks", window);
        damage.addProperty("hits", com.dddgn.alice.survival.DamageLedger
                .hitsSince(bot, bot.getServer().getTickCount() - window));
        damage.addProperty("total", com.dddgn.alice.survival.DamageLedger
                .totalSince(bot, bot.getServer().getTickCount() - window));
        damage.addProperty("lastSource", com.dddgn.alice.survival.DamageLedger.of(bot).lastSource());
        damage.addProperty("lastTick", com.dddgn.alice.survival.DamageLedger.of(bot).lastTick());
        root.add("damage", damage);
        return root;
    }

    /** 完整 prompt（状态 + 输出契约），并记一行"发了多少字"。 */
    public static String buildPrompt(BotPlayer bot, CandidateMenu menu) {
        return buildPrompt(bot, menu, "-");
    }

    public static String buildPrompt(BotPlayer bot, CandidateMenu menu, String trigger) {
        String state = build(bot, menu, trigger).toString();
        String prompt = """
                当前状态（服务端权威事实，JSON）：
                %s

                其中 `trigger` = **为什么现在问你**（`terminal:<任务>(<终止理由>)` / `event:<事件>` /
                `manual` = 玩家/夹具手动触发 / `operator` = 操作者直连指令）。

                注意：`start_job` 的 `target` **只能引用 menu 里出现过的 id**（例如 `tree@20,64,208`）；
                `craft` 的 `item` **只能引用 menu 中 `kind="craftable"` 且 `can_use=true` 的 id**
                （`craftable_truncated=true` 表示清单因上限被截断，**被截断 ≠ 做不到**）；
                引用菜单里没有的 id 会被**拒绝**，也不要自己编坐标／编物品名。

                请只回**一个** JSON 对象，不要解释、不要 Markdown 围栏。""".formatted(state);
        BotLog.info("[Goal] snapshot chars={} json={}", state.length(),
                state.length() > 1500 ? state.substring(0, 1500) + "…" : state);
        return prompt;
    }

    /**
     * **终态的 JSON 形态**（M4）：把"上一轮为什么失败"从**一段文字**变成**字段**。
     *
     * <p>为什么需要：`survey/08` §5.7 审计 G3 —— 真因（缺镐 / 预算耗尽）**已经存在**，
     * 但只以"无结构文字"的形式落在 `lastResult` 里（`MineJob` 会把逐候选的 `rejected()` 拼进去）
     * ⇒ LLM **看得到、难以可靠分支**。M3/M4 的价值是"**把文字变成字段**"，不是"补回丢失的信息"。
     *
     * <p>**有界**：`details` 可能很长（例如 60 个候选的拒绝理由串）⇒ 截到 {@link #MAX_FAILURE_DETAILS}
     * 并**如实标 `…`**（截断 ≠ 没有更多）。其余字段都是短串，不需要再设预算。
     *
     * <p>`public static` 是**自检接缝**（与 `EventThresholds.resetStuckTracking` 同类）：
     * 夹具可以用一条**构造出来的**终态记录直接断言 JSON 形态，不必先真的把任务跑失败一次。
     */
    public static JsonObject lastTerminalJson(TaskExecutionRecord record) {
        JsonObject last = new JsonObject();
        last.addProperty("kind", record.taskKind());
        last.addProperty("terminal", String.valueOf(record.terminalStatus()));
        last.addProperty("code", record.resultCode());
        last.addProperty("terminalReason", record.terminalReason());
        last.addProperty("botId", record.botId());
        // **F1 地基**：驱动者（llm / fixture / in_game_player / system=未归因）
        last.addProperty("driver", record.driver());
        last.addProperty("durationTicks", record.durationTicks());
        last.addProperty("botPos", record.terminalBotPos().toShortString());
        if (record.outcome() != null && record.outcome().failure() != null) {
            TaskFailureReport failure = record.outcome().failure();
            last.addProperty("failureCode", failure.code());
            last.addProperty("failurePhase", failure.phase());   // M4：相位（原来只有 code）
            String details = failure.details() == null ? "" : failure.details();
            if (details.length() > MAX_FAILURE_DETAILS) {
                details = details.substring(0, MAX_FAILURE_DETAILS) + "…";
            }
            if (!details.isBlank()) {
                last.addProperty("failureDetails", details);      // M4：细节（有界）
            }
        }
        return last;
    }

    /** `failureDetails` 的字符上限（有界预算：细节可能是一长串逐候选拒绝理由）。 */
    public static final int MAX_FAILURE_DETAILS = 240;
}
