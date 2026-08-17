package com.dddgn.alice;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotSelftest;
import com.dddgn.alice.perception.ScopeBuffer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;

/**
 * AI Bot —— 游戏内 AI 助手(M0 技术验证阶段)。
 * <p>
 * M0 范围:服务端假人玩家 + 命令触发挖掘,验收标准「不存在隔空挖」(挖掘前视线无遮挡检查)。
 * M1 感知层骨架已先行:ScopeBuffer 任务作用域缓冲区(掉落物/方块变化监听)。
 * SELFTEST:服务器启动自动跑验收用例并关服(headless 验收,审查点 R8)。
 */
@Mod(AliceMod.MOD_ID)
public class AliceMod {

    public static final String MOD_ID = "alice";

    public AliceMod() {
        // BotManager 静态注册 FORGE 总线(tick 驱动 + 命令注册在 BotCommand 中)
        MinecraftForge.EVENT_BUS.register(BotManager.class);
        // 感知作用域的事件监听(掉落物/方块变化)
        MinecraftForge.EVENT_BUS.register(ScopeBuffer.class);
        // 自动化验收(headless selftest)
        MinecraftForge.EVENT_BUS.register(BotSelftest.class);
        // 测试工具:钻石铲右键扫描
        MinecraftForge.EVENT_BUS.register(com.dddgn.alice.tool.ScanWand.class);
    }
}
