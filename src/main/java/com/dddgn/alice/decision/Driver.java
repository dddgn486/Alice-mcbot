package com.dddgn.alice.decision;

import com.dddgn.alice.bot.BotPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * **F1 地基（D-267，2026-09-17 用户裁定「先做地基」）**：**驱动者身份位** —— "谁在为这个 bot 做决定"。
 *
 * <p>为什么要有它：今天的终态记录 / 决策快照 / 日志里**只有 `botId`**，没有"谁驱动的"这一维。
 * 于是"这句话是玩家让做的、还是 LLM 自己决定的、还是夹具跑的"在事后**无法区分**，
 * 而 `survey/16 §3` 的整条线（外部驱动者 / 陪伴 / 桌面 AI）都要先有这一维才能谈归因与降级。
 *
 * <p>**口径（重要，不许说谎）**：**只记"已知的发起者"**，未知一律 {@link #SYSTEM}（= 未归因）。
 * ⚠️ 与 `D-267` 表格里我最初写的"默认 `in_game_player`"**不同** —— 实测发现夹具与未知入口都会被
 * 误标成"玩家驱动"，那是**审计字段在说谎**，比空着更糟。所以默认 = `system`，玩家侧等真正标注到位再说
 * （见台账 **F1-残**）。
 */
public final class Driver {

    /** 玩家（游戏内命令 / 物品右键）发起。 */
    public static final String IN_GAME_PLAYER = "in_game_player";
    /** LLM 决策发起（`GoalDirector` 应用模型回复）。 */
    public static final String LLM = "llm";
    /** 自检夹具 / 电池发起。 */
    public static final String FIXTURE = "fixture";
    /** **未归因**（默认）：已知的发起者还没标注到这个入口。 */
    public static final String SYSTEM = "system";

    private static final Map<UUID, String> CURRENT = new ConcurrentHashMap<>();

    private Driver() {
    }

    /** 标注本 bot 当前决定的发起者（发起入口调用）。 */
    public static void set(BotPlayer bot, String driver) {
        if (bot == null) {
            return;
        }
        CURRENT.put(bot.getUUID(), driver == null || driver.isBlank() ? SYSTEM : driver);
    }

    /** 当前发起者（未标注 ⇒ {@link #SYSTEM}）。 */
    public static String of(BotPlayer bot) {
        if (bot == null) {
            return SYSTEM;
        }
        String value = CURRENT.get(bot.getUUID());
        return value == null || value.isBlank() ? SYSTEM : value;
    }

    /** **夹具/复位用**：清掉标注（回到未归因）。 */
    public static void clear(BotPlayer bot) {
        if (bot != null) {
            CURRENT.remove(bot.getUUID());
        }
    }
}
