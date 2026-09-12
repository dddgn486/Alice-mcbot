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

        int pendingTemp = com.dddgn.alice.ledger.WorldModLedger
                .pendingForOwner(bot.serverLevel().getServer(), bot.getUUID()).size();
        JsonObject world = new JsonObject();
        world.addProperty("pendingTemporaryBlocks", pendingTemp);
        root.add("worldMod", world);
        return root;
    }

    /** 完整 prompt（状态 + 输出契约），并记一行"发了多少字"。 */
    public static String buildPrompt(BotPlayer bot) {
        String state = build(bot).toString();
        String prompt = """
                当前状态（服务端权威事实，JSON）：
                %s

                请只回**一个** JSON 对象，不要解释、不要 Markdown 围栏。""".formatted(state);
        BotLog.info("[Goal] snapshot chars={} json={}", state.length(),
                state.length() > 1500 ? state.substring(0, 1500) + "…" : state);
        return prompt;
    }
}
