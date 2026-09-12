package com.dddgn.alice.decision;

import com.dddgn.alice.bot.BotManager;
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
            JsonObject last = new JsonObject();
            last.addProperty("kind", record.taskKind());
            last.addProperty("terminal", String.valueOf(record.terminalStatus()));
            last.addProperty("code", record.resultCode());
            last.addProperty("terminalReason", record.terminalReason());
            last.addProperty("botId", record.botId());
            last.addProperty("durationTicks", record.durationTicks());
            last.addProperty("botPos", record.terminalBotPos().toShortString());
            if (record.outcome() != null && record.outcome().failure() != null) {
                last.addProperty("failureCode", record.outcome().failure().code());
            }
            task.add("lastTerminal", last);
        }
        root.add("task", task);

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

        int pendingTemp = com.dddgn.alice.ledger.WorldModLedger
                .pendingForOwner(bot.serverLevel().getServer(), bot.getUUID()).size();
        JsonObject world = new JsonObject();
        world.addProperty("pendingTemporaryBlocks", pendingTemp);
        root.add("worldMod", world);
        return root;
    }

    /** 完整 prompt（状态 + 输出契约），并记一行"发了多少字"。 */
    public static String buildPrompt(BotPlayer bot, CandidateMenu menu) {
        String state = build(bot, menu).toString();
        String prompt = """
                当前状态（服务端权威事实，JSON）：
                %s

                注意：`start_job` 的 `target` **只能引用 menu 里出现过的 id**（例如 `tree@20,64,208`）；
                引用菜单里没有的 target 会被**拒绝**，也不要自己编坐标。

                请只回**一个** JSON 对象，不要解释、不要 Markdown 围栏。""".formatted(state);
        BotLog.info("[Goal] snapshot chars={} json={}", state.length(),
                state.length() > 1500 ? state.substring(0, 1500) + "…" : state);
        return prompt;
    }
}
