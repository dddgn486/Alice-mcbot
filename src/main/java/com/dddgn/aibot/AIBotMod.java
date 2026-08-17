package com.dddgn.aibot;

import com.dddgn.aibot.bot.BotManager;
import com.dddgn.aibot.perception.ScopeBuffer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;

/**
 * AI Bot —— 游戏内 AI 助手(M0 技术验证阶段)。
 * <p>
 * M0 范围:服务端假人玩家 + 命令触发挖掘,验收标准「不存在隔空挖」(挖掘前视线无遮挡检查)。
 * M1 感知层骨架已先行:ScopeBuffer 任务作用域缓冲区(掉落物/方块变化监听)。
 */
@Mod(AIBotMod.MOD_ID)
public class AIBotMod {

    public static final String MOD_ID = "aibot";

    public AIBotMod() {
        // BotManager 静态注册 FORGE 总线(tick 驱动 + 命令注册在 BotCommand 中)
        MinecraftForge.EVENT_BUS.register(BotManager.class);
        // 感知作用域的事件监听(掉落物/方块变化)
        MinecraftForge.EVENT_BUS.register(ScopeBuffer.class);
    }
}
