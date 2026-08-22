package com.dddgn.alice.client.gui;

import com.dddgn.alice.gui.BotInventoryMenu;
import com.dddgn.alice.gui.ModMenuTypes;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Client-side registration of the bot inventory screen. Bound only on the physical client
 * dist; the server never loads {@link BotInventoryScreen} (an {@code @OnlyIn} class), so this
 * setup runs safely under the single-jar {@code side=BOTH} build.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = "alice", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientMenuScreens {

    private ClientMenuScreens() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(ModMenuTypes.BOT_INVENTORY_MENU.get(),
                (BotInventoryMenu menu, net.minecraft.world.entity.player.Inventory inv,
                 net.minecraft.network.chat.Component title) ->
                        new BotInventoryScreen(menu, inv, title)));
    }
}
