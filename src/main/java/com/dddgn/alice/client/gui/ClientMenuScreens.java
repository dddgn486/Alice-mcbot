package com.dddgn.alice.client.gui;

import com.dddgn.alice.gui.ModMenuTypes;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 方案 A：注册 Bot Inventory Menu Screen。
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = "alice", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientMenuScreens {

    private ClientMenuScreens() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(ModMenuTypes.BOT_INVENTORY_MENU.get(), BotInventoryMenuScreen::new);
        });
    }
}
