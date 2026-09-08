package com.dddgn.alice.client;

import com.dddgn.alice.network.BotInventoryPacket;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Client-side last authoritative bot inventory snapshot (network thread → main thread via
 * enqueueWork; the rendering thread reads it read-only). The bot inventory screen renders purely
 * from this snapshot and never mutates local inventory; all writes go back to the server.
 * Opening the {@link com.dddgn.alice.client.gui.BotInventoryScreen} is isolated here so the
 * network-packet handler never loads a client-only {@code Screen} on the dedicated server.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientBotInventoryState {

    private static volatile BotInventoryPacket latest;

    private ClientBotInventoryState() {
    }

    public static void update(BotInventoryPacket packet) {
        latest = packet;
    }

    public static BotInventoryPacket get() {
        return latest;
    }

    public static boolean hasSnapshot() {
        return latest != null;
    }

    /** Opens the bot inventory screen on the render thread (client-only). */
    public static void openScreen() {
        net.minecraft.client.Minecraft.getInstance().setScreen(
                new com.dddgn.alice.client.gui.BotInventoryScreen());
    }
}
