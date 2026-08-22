package com.dddgn.alice.network;

import com.dddgn.alice.client.ClientBotInventoryState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * S2C: authoritative bot inventory snapshot pushed to a viewer. Sent over the viewer's real
 * connection ({@code PacketDistributor.PLAYER.with(viewer)}), never {@code bot.connection.send}
 * (a FakeConnection no-op). The client stores it in {@link ClientBotInventoryState} and opens
 * the bot inventory screen; it never mutates local inventory.
 */
public record BotInventoryPacket(UUID botId, String name, List<ItemStack> slots) {

    public static void encode(BotInventoryPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.botId);
        buf.writeUtf(packet.name);
        buf.writeVarInt(packet.slots.size());
        for (ItemStack stack : packet.slots) {
            buf.writeItem(stack);
        }
    }

    public static BotInventoryPacket decode(FriendlyByteBuf buf) {
        UUID botId = buf.readUUID();
        String name = buf.readUtf();
        int size = Math.min(buf.readVarInt(), 256);
        List<ItemStack> slots = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            slots.add(buf.readItem());
        }
        return new BotInventoryPacket(botId, name, slots);
    }

    public static void handle(BotInventoryPacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> {
                    ClientBotInventoryState.update(packet);
                    ClientBotInventoryState.openScreen();
                }));
        context.setPacketHandled(true);
    }
}
