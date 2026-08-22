package com.dddgn.alice.client.gui;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Retired (direction B): the bot inventory is now a pure client {@link BotInventoryScreen}
 * driven by server snapshots over {@code BotInventoryPacket}; it is no longer an
 * {@code AbstractContainerScreen} bound to a {@code MenuType}. This class is kept as a no-op
 * for rollback reference and no longer registers anything with {@code MenuScreens}.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = "alice", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientMenuScreens {

    private ClientMenuScreens() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // No-op: the bot inventory is a pure Screen (direction B); no MenuType/MenuScreens.
    }
}
