package com.dddgn.alice;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotSelftest;
import com.dddgn.alice.task.mining.MiningReplanFixture;
import com.dddgn.alice.task.mining.MiningSceneFixture;
import com.dddgn.alice.gui.ModMenuTypes;
import com.dddgn.alice.item.AliceItems;
import com.dddgn.alice.network.AliceNetwork;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.road.RoadBuilder;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;  // ← 新增

@Mod(AliceMod.MOD_ID)
public class AliceMod {

    public static final String MOD_ID = "alice";

    // ✅ 改成这样（和 TangoMod 一样）
    public AliceMod(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();

        // 物品注册(MOD 总线)
        AliceItems.ITEMS.register(modEventBus);
        // 容器菜单注册(MOD 总线)
        ModMenuTypes.MENUS.register(modEventBus);
        // 网络通道(S2C 任务目标同步 + bot inventory 快照/action)
        AliceNetwork.register();

        // FORGE 总线:任务 tick / 感知事件 / 自检
        MinecraftForge.EVENT_BUS.register(BotManager.class);
        MinecraftForge.EVENT_BUS.register(ScopeBuffer.class);
        MinecraftForge.EVENT_BUS.register(RoadBuilder.class);
        MinecraftForge.EVENT_BUS.register(BotSelftest.class);
        MinecraftForge.EVENT_BUS.register(MiningReplanFixture.class);
        MinecraftForge.EVENT_BUS.register(MiningSceneFixture.class);

        // Bot 遥控器输入处理
        MinecraftForge.EVENT_BUS.register(new com.dddgn.alice.item.BotRemoteControlHandler());
    }
}