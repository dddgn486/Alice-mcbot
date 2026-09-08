package com.dddgn.alice.network;

import com.dddgn.alice.gui.BotInventoryService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S: a viewer's intent to act on a bot's inventory (PICKUP / PLACE / QUICK_MOVE). The client
 * only sends this intent; it never mutates local state. The server handles it in
 * {@link BotInventoryService#applyAction}, validates (only server authority), writes the bot
 * inventory, then pushes the authoritative snapshot back. Sent via the viewer's real connection
 * ({@code CHANNEL.sendToServer}), never {@code bot.connection.send}.
 */
public record BotInventoryActionPacket(
        java.util.UUID botId,
        BotInventoryService.ActionType action,
        int slotIndex,
        int playerSlot) {

    public static void encode(BotInventoryActionPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.botId);
        buf.writeEnum(packet.action);
        buf.writeVarInt(packet.slotIndex);
        buf.writeVarInt(packet.playerSlot);
    }

    public static BotInventoryActionPacket decode(FriendlyByteBuf buf) {
        java.util.UUID botId = buf.readUUID();
        BotInventoryService.ActionType action = buf.readEnum(BotInventoryService.ActionType.class);
        int slotIndex = buf.readVarInt();
        int playerSlot = buf.readVarInt();
        return new BotInventoryActionPacket(botId, action, slotIndex, playerSlot);
    }

    public static void handle(BotInventoryActionPacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isServer()) {
            context.enqueueWork(() -> {
                ServerPlayer actor = context.getSender();
                if (actor == null) {
                    return;
                }
                String code = BotInventoryService.applyAction(
                        actor.serverLevel(), packet.botId(), actor, packet.slotIndex(),
                        packet.action(), packet.playerSlot());
                com.dddgn.alice.log.BotLog.info(
                        "bot_inv: action player={} bot={} act={} slot={} code={}",
                        actor.getName().getString(), packet.botId(), packet.action(),
                        packet.slotIndex(), code);
            });
        }
        context.setPacketHandled(true);
    }
}
