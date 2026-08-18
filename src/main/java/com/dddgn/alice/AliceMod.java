package com.dddgn.alice;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotSelftest;
import com.dddgn.alice.item.AliceItems;
import com.dddgn.alice.network.AliceNetwork;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.road.RoadBuilder;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;

/**
 * AI Bot —— 游戏内 AI 助手(执行层框架阶段)。
 * <p>
 * 现阶段:任务框架(Task/MineTask) + 感知联动(ScopeBuffer 掉落物) +
 * 客户端测试效果(目标透视高亮) + 测试工具(目标指定器)。
 * SELFTEST:手动触发验收(headless,审查点 R8)。
 */
@Mod(AliceMod.MOD_ID)
public class AliceMod {

    public static final String MOD_ID = "alice";

    public AliceMod() {
        IEventBus modEventBus = net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus();
        // 物品注册(MOD 总线)
        AliceItems.ITEMS.register(modEventBus);
        // 网络通道(S2C 任务目标同步)
        AliceNetwork.register();

        // FORGE 总线:任务 tick / 感知事件 / 自检 / 扫描铲
        MinecraftForge.EVENT_BUS.register(BotManager.class);
        MinecraftForge.EVENT_BUS.register(ScopeBuffer.class);
        MinecraftForge.EVENT_BUS.register(RoadBuilder.class);
        MinecraftForge.EVENT_BUS.register(BotSelftest.class);
        MinecraftForge.EVENT_BUS.register(com.dddgn.alice.tool.ScanWand.class);
    }
}
