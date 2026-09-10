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

    /** 最近快照里的选中槽（主手）；尚无快照时返回 {@code fallback}。 */
    public static int selectedOrDefault(int fallback) {
        BotInventoryPacket packet = latest;
        return packet == null ? fallback : packet.selected();
    }

    public static BotInventoryPacket get() {
        return latest;
    }

    public static boolean hasSnapshot() {
        return latest != null;
    }

    /** Opens the bot inventory screen on the render thread (client-only). */
    /**
     * 打开 bot 背包界面；**已经开着就不重开**（D-091）。
     *
     * <p>面板内的每次动作都会让服务端回推一次快照，而回推处理原本无条件
     * {@code openScreen()} —— 于是自绘界面与菜单界面会互相替换。
     */
    public static void openScreenIfNone() {
        net.minecraft.client.gui.screens.Screen current =
                net.minecraft.client.Minecraft.getInstance().screen;
        if (current instanceof com.dddgn.alice.client.gui.BotInventoryScreen
                || current instanceof com.dddgn.alice.client.gui.BotInventoryMenuScreen) {
            return;
        }
        openScreen();
    }

    public static void openScreen() {
        net.minecraft.client.Minecraft.getInstance().setScreen(
                new com.dddgn.alice.client.gui.BotInventoryScreen());
    }
}
