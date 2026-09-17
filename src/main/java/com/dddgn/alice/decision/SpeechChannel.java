package com.dddgn.alice.decision;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * **F4 地基（D-267，2026-09-17 用户裁定「先做地基」）**：**说话通道**与**决策通道**分离。
 *
 * <p>为什么要有这个类：今天项目里**不存在**"不碰世界的输出通道" —— 唯一像它的
 * `GoalDirector.tell()` 是 `private` 且**外部调用者 0**，是决策的**副产品**，不是一条通道。
 * 于是"陪玩家说话""汇报看到的东西"（`survey/16 §6`、`survey/17 §1.6` 的**对话线**）无处可落，
 * 而最省事的错误写法就是把文本塞进决策输入（`GoalDirector:296-300` 的 prompt / `instruct`）
 * ⇒ 那就等于让**自然语言绕过 `GoalAction` 白名单**，违反 `survey/05`「NL 只生成草稿」。
 *
 * <p>**结构约束（本类的存在理由）**：
 * <ul>
 *   <li>本类**只出不进**：`say(...)` 把文本送到**聊天与日志**，**不提供**任何"把文本喂回决策"的方法；</li>
 *   <li>本类**不引用** `GoalAction` / `GoalDirector`（由门禁 **F4-P1** 双向断言）；</li>
 *   <li>决策侧**不引用**本类 ⇒ 说话内容在结构上进不了 `GoalAction.parse`（白名单不受影响）。</li>
 * </ul>
 *
 * <p>⚠️ 未做：本条只把**通道**做出来（地基）；**对话线的产品形态**（什么时候说、说什么、
 * 是否要授权）仍是待裁定项（`survey/16 §3–§6`），本类不替它做决定。
 */
public final class SpeechChannel {

    /** 消息种类（可扩展，但**不是**动作集 —— 它永远不进 `GoalAction`）。 */
    public static final String KIND_VISION_REPORT = "vision_report";
    public static final String KIND_COMPANION = "companion";

    /** 一条"说出去"的消息：**只有出方向**。 */
    public record Utterance(String kind, String text, String requester) {
    }

    private static final Map<UUID, Integer> SAID = new ConcurrentHashMap<>();

    private SpeechChannel() {
    }

    /**
     * 说一句话（不碰世界、不产生任何决策输入）。
     *
     * @param bot       说话的 bot
     * @param utterance 内容（`kind` 只用于日志分类）
     */
    public static void say(BotPlayer bot, Utterance utterance) {
        if (bot == null || utterance == null || utterance.text() == null || utterance.text().isBlank()) {
            return;
        }
        SAID.merge(bot.getUUID(), 1, Integer::sum);
        BotLog.info("[Speech] bot={} kind={} requester={} text={}",
                bot.getName().getString(), utterance.kind(),
                utterance.requester() == null ? "-" : utterance.requester(), utterance.text());
        bot.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("[alice] " + bot.getName().getString() + "：" + utterance.text()), false);
    }

    /** **夹具/汇报用**：该 bot 一共说过几句。 */
    public static int saidCount(BotPlayer bot) {
        return SAID.getOrDefault(bot.getUUID(), 0);
    }

    /** **夹具/复位用**：清零计数。 */
    public static void reset(BotPlayer bot) {
        SAID.remove(bot.getUUID());
    }
}
