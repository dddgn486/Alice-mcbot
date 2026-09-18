package com.dddgn.alice.network;

import com.dddgn.alice.protection.ProtectionClaimService;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S：**批量认领 / 取消认领**（D-314，保护区 2/2）。
 *
 * <p>形状照 FTB Chunks 的 {@code RequestChunkChangePacket}（`D-307` 事实 4）：**一个动作 + 一组区块**，
 * 界面攒够了一次发一包（关闭界面时最多发两包：认领批 + 取消批）。
 *
 * <p>⚠️ 包里**没有维度字段**（构造只有两个分量）：维度由服务端按"发送者当前所在维度"裁定
 * —— 客户端只表达"我想改哪些区块"，不表达"这块地是谁的"（server-authoritative，`D-307`）。
 *
 * <p>⚠️ 数量校验发生在**解码期且是拒收**：客户端可以撒谎，夹住会让"畸形包"变成"静默少改几块"，
 * 而拒收会按畸形包处理（断开该连接）—— 与 C2S 不可信的定位一致。
 */
public record ProtectionActionPacket(boolean claim, long[] chunkKeys) {

    public static void encode(ProtectionActionPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.claim);
        long[] keys = packet.chunkKeys == null ? new long[0] : packet.chunkKeys;
        buf.writeVarInt(keys.length);
        for (long key : keys) {
            buf.writeLong(key);
        }
    }

    public static ProtectionActionPacket decode(FriendlyByteBuf buf) {
        boolean claim = buf.readBoolean();
        int count = buf.readVarInt();
        if (count < 0 || count > ProtectionClaimService.MAX_BATCH) {
            throw new DecoderException("protection batch size out of range: " + count
                    + " (max " + ProtectionClaimService.MAX_BATCH + ")");
        }
        long[] keys = new long[count];
        for (int i = 0; i < count; i++) {
            keys[i] = buf.readLong();
        }
        return new ProtectionActionPacket(claim, keys);
    }

    public static void handle(ProtectionActionPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        if (context.getDirection().getReceptionSide().isServer()) {
            context.enqueueWork(() -> {
                ServerPlayer actor = context.getSender();
                if (actor != null) {
                    ProtectionClaimService.handleBatch(actor, packet.claim(), packet.chunkKeys());
                }
            });
        }
        context.setPacketHandled(true);
    }
}
