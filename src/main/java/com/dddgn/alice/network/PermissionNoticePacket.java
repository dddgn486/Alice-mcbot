package com.dddgn.alice.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C：**请示通知**（S3b / D-141）：服务端发起/撤下一条请示时推送，客户端画成屏幕一侧的卡片。
 *
 * <pre>
 *   active=true  → 显示/更新卡片（options 最多两个按钮；deadlineInTicks 用于倒计时）
 *   active=false → 撤下该 id 的卡片（已被答复或已超时）
 * </pre>
 */
public record PermissionNoticePacket(boolean active, String id, String capability, String reason,
                                     String option1, String option2, String defaultOption,
                                     int deadlineInTicks) {

    public static void encode(PermissionNoticePacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.active);
        buf.writeUtf(packet.id, 32);
        buf.writeUtf(packet.capability, 64);
        buf.writeUtf(packet.reason, 256);
        buf.writeUtf(packet.option1 == null ? "" : packet.option1, 32);
        buf.writeUtf(packet.option2 == null ? "" : packet.option2, 32);
        buf.writeUtf(packet.defaultOption == null ? "" : packet.defaultOption, 32);
        buf.writeVarInt(packet.deadlineInTicks);
    }

    public static PermissionNoticePacket decode(FriendlyByteBuf buf) {
        boolean active = buf.readBoolean();
        String id = buf.readUtf(32);
        String capability = buf.readUtf(64);
        String reason = buf.readUtf(256);
        String option1 = buf.readUtf(32);
        String option2 = buf.readUtf(32);
        String defaultOption = buf.readUtf(32);
        int deadline = buf.readVarInt();
        return new PermissionNoticePacket(active, id, capability, reason, option1, option2,
                defaultOption, deadline);
    }

    public static void handle(PermissionNoticePacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> com.dddgn.alice.client.ClientPermissionState.accept(packet));
        }
        context.setPacketHandled(true);
    }
}
