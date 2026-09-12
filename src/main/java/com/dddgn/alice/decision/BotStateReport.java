package com.dddgn.alice.decision;

import com.dddgn.alice.bot.BotPlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * **汇报通道的确定性渲染**（S1 事实层 / D-136）：把 {@link DecisionSnapshot} 的同一份事实
 * 渲染成**聊天可读**的多行文本。
 *
 * <p>两条纪律：
 * <ol>
 *   <li>**事实只有一个来源**：报告与"给 LLM 的快照"共用 `DecisionSnapshot.build`，
 *       所以"报告错了"与"LLM 看到的错了"不可能分叉；</li>
 *   <li>**LLM 只负责措辞**（可选）：本类输出的是结构化事实；要"人话版"时再由 LLM 转述，
 *       且必须把事实原文一并给出（标注"以下为转述"），这样出问题能区分"事实错"还是"措辞错"。</li>
 * </ol>
 */
public final class BotStateReport {

    private BotStateReport() {
    }

    /** 同一份事实（给 LLM 与给玩家共用；**含候选菜单** —— 玩家也能看到"现在能做什么"）。 */
    public static JsonObject json(BotPlayer bot) {
        return DecisionSnapshot.build(bot, CandidateMenu.build(bot));
    }

    /** 聊天友好的多行文本。 */
    public static List<String> render(BotPlayer bot) {
        JsonObject snapshot = json(bot);
        List<String> lines = new ArrayList<>();

        JsonObject botNode = snapshot.getAsJsonObject("bot");
        lines.add("bot " + botNode.get("name").getAsString()
                + " @" + botNode.get("pos").getAsString()
                + " dim=" + botNode.get("dimension").getAsString()
                + " hp=" + botNode.get("health").getAsFloat()
                + " hazard=" + botNode.get("hazard").getAsString()
                + (botNode.get("onGround").getAsBoolean() ? "" : " (空中)"));

        JsonObject task = snapshot.getAsJsonObject("task");
        lines.add("任务：" + task.get("current").getAsString()
                + "（lastResult=" + task.get("lastResult").getAsString() + "）");

        JsonArray tree = snapshot.getAsJsonArray("tree");
        for (var element : tree) {
            JsonObject node = element.getAsJsonObject();
            lines.add("  树： " + node.get("kind").getAsString()
                    + "@" + node.get("target").getAsString()
                    + " ticks=" + node.get("ticks").getAsLong()
                    + (node.get("progress").getAsString().isBlank()
                    ? "" : " " + node.get("progress").getAsString()));
            if (node.has("children")) {
                for (var childElement : node.getAsJsonArray("children")) {
                    JsonObject child = childElement.getAsJsonObject();
                    lines.add("      ↳ " + child.get("kind").getAsString()
                            + "@" + child.get("target").getAsString()
                            + "[" + child.get("phase").getAsString() + "]"
                            + (child.get("progress").getAsString().isBlank()
                            ? "" : " " + child.get("progress").getAsString())
                            + (child.get("lastFailure").getAsString().isBlank()
                            ? "" : " lastFailure=" + child.get("lastFailure").getAsString()));
                }
            }
        }

        if (task.has("lastTerminal")) {
            JsonObject last = task.getAsJsonObject("lastTerminal");
            lines.add("上一次终态：" + last.get("kind").getAsString()
                    + " " + last.get("terminal").getAsString()
                    + " code=" + last.get("code").getAsString()
                    + " reason=" + last.get("terminalReason").getAsString()
                    + " 耗时=" + last.get("durationTicks").getAsLong() + "tick"
                    + (last.has("failureCode") ? " failure=" + last.get("failureCode").getAsString() : ""));
        }

        JsonObject inventory = snapshot.getAsJsonObject("inventory");
        lines.add("背包：free=" + inventory.get("freeSlots").getAsInt()
                + " top=" + inventory.getAsJsonArray("top"));

        if (snapshot.has("region")) {
            lines.add("区域：" + snapshot.getAsJsonObject("region"));
        }
        lines.add("账本未闭合临时方块：" + snapshot.getAsJsonObject("worldMod")
                .get("pendingTemporaryBlocks").getAsInt());

        JsonArray menu = snapshot.getAsJsonArray("menu");
        if (menu != null && !menu.isEmpty()) {
            lines.add("可做（候选菜单，LLM 只能从里面挑）：");
            for (var element : menu) {
                JsonObject entry = element.getAsJsonObject();
                lines.add("  " + entry.get("id").getAsString()
                        + " —— " + entry.get("label").getAsString()
                        + (entry.has("amount") ? " 数量=" + entry.get("amount").getAsInt() : "")
                        + (entry.has("extra") ? " " + entry.get("extra").getAsString() : ""));
            }
        } else {
            lines.add("可做（候选菜单）：空");
        }

        JsonArray pendingRequests = snapshot.getAsJsonArray("pendingRequests");
        if (pendingRequests != null && !pendingRequests.isEmpty()) {
            lines.add("⚠ 未决请示（等玩家拍板；超时按默认档）：");
            for (var element : pendingRequests) {
                JsonObject request = element.getAsJsonObject();
                lines.add("  " + request.get("id").getAsString()
                        + " capability=" + request.get("capability").getAsString()
                        + " 默认=" + request.get("default").getAsString()
                        + " —— " + request.get("reason").getAsString()
                        + "（答复：/alice ask <id> allow|deny [once|session|always]）");
            }
        }

        JsonArray events = snapshot.getAsJsonArray("recentEvents");
        if (events.isEmpty()) {
            lines.add("最近事件：无");
        } else {
            lines.add("最近事件（新→旧看倒序，共 " + events.size() + " 条）：");
            for (int i = events.size() - 1; i >= 0; i--) {
                JsonObject event = events.get(i).getAsJsonObject();
                lines.add("  #" + event.get("tick").getAsLong() + " "
                        + event.get("type").getAsString() + "/" + event.get("severity").getAsString()
                        + " " + event.get("summary").getAsString());
            }
        }
        return lines;
    }
}
