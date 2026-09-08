package com.dddgn.alice.transfer;

import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Clears ephemeral selection drafts at the server lifecycle boundary. */
@Mod.EventBusSubscriber(modid = "alice", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TransferSelectionLifecycle {
    private TransferSelectionLifecycle() { }
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        TransferSelectionData.clearServer(event.getServer());
    }
}
