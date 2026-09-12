package com.dddgn.alice.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S：**请示答复**（S3b / D-141）—— 弹窗按钮点下去就发这个。
 *
 * <p>服务端走**同一个** {@code PermissionGate.answer(...)}（与 `/alice ask` 完全等价）：
 * **只有玩家能批准**这条铁律不变（包只能由客户端玩家发出）。
 */
public record PermissionAnswerPacket(String id, String option, String scope) {

    public static void encode(PermissionAnswerPacket packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.id, 32);
        buf.writeUtf(packet.option, 32);
        buf.writeUtf(packet.scope, 16);
    }

    public static PermissionAnswerPacket decode(FriendlyByteBuf buf) {
        return new PermissionAnswerPacket(buf.readUtf(32), buf.readUtf(32), buf.readUtf(16));
    }

    public static void handle(PermissionAnswerPacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            if (!context.getDirection().getReceptionSide().isServer()) {
                return;
            }
            var player = context.getSender();
            var server = player == null ? null : player.getServer();
            if (server == null) {
                return;
            }
            com.dddgn.alice.decision.PermissionGate.Scope scope;
            try {
                scope = com.dddgn.alice.decision.PermissionGate.Scope
                        .valueOf(packet.scope().trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                scope = com.dddgn.alice.decision.PermissionGate.Scope.ONCE;
            }
            com.dddgn.alice.decision.PermissionGate.answer(server, packet.id(), packet.option(), scope,
                    "player:" + (player == null ? "?" : player.getName().getString()));
        });
        context.setPacketHandled(true);
    }
}
