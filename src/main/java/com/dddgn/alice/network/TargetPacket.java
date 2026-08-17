package com.dddgn.alice.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C:任务目标同步包(客户端透视高亮用)。
 * <p>服务端在任务分配/结束时广播;客户端 {@code ClientTargetState} 消费,
 * 渲染器据此画线框。</p>
 * <pre>
 *   active=false → 清除高亮
 *   active=true, type=0 → 方块目标(blockPos 有效)
 *   active=true, type=1 → 实体目标(entityId 有效,掉落物/生物)
 * </pre>
 */
public record TargetPacket(boolean active, int type, BlockPos blockPos, int entityId) {

    public static void encode(TargetPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.active);
        buf.writeInt(packet.type);
        buf.writeBoolean(packet.blockPos != null);
        if (packet.blockPos != null) {
            buf.writeBlockPos(packet.blockPos);
        }
        buf.writeInt(packet.entityId);
    }

    public static TargetPacket decode(FriendlyByteBuf buf) {
        boolean active = buf.readBoolean();
        int type = buf.readInt();
        BlockPos pos = buf.readBoolean() ? buf.readBlockPos() : null;
        int entityId = buf.readInt();
        return new TargetPacket(active, type, pos, entityId);
    }

    public static void handle(TargetPacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> com.dddgn.alice.client.ClientTargetState.update(packet));
        }
        context.setPacketHandled(true);
    }
}
