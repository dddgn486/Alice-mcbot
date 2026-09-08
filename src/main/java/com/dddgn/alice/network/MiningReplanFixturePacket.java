package com.dddgn.alice.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** S2C：开发期挖掘测试的目标/障碍/Bot起点高亮状态。 */
public record MiningReplanFixturePacket(boolean active, BlockPos target, BlockPos obstacle,
                                         BlockPos botStart, String phase) {
    public static void encode(MiningReplanFixturePacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.active());
        writePos(buf, packet.target());
        writePos(buf, packet.obstacle());
        writePos(buf, packet.botStart());
        buf.writeUtf(packet.phase(), 32);
    }

    private static void writePos(FriendlyByteBuf buf, BlockPos pos) {
        buf.writeBoolean(pos != null);
        if (pos != null) buf.writeBlockPos(pos);
    }

    private static BlockPos readPos(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readBlockPos() : null;
    }

    public static MiningReplanFixturePacket decode(FriendlyByteBuf buf) {
        return new MiningReplanFixturePacket(buf.readBoolean(), readPos(buf), readPos(buf),
                readPos(buf), buf.readUtf(32));
    }

    public static void handle(MiningReplanFixturePacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> com.dddgn.alice.client.ClientMiningReplanState.update(packet));
        }
        context.setPacketHandled(true);
    }
}
