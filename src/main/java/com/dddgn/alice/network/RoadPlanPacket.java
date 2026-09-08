package com.dddgn.alice.network;

import com.dddgn.alice.road.RoadPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** S2C:道路数学模型体素预览。 */
public record RoadPlanPacket(boolean active, List<RoadPlan.Cell> cells) {
    public static void encode(RoadPlanPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.active);
        buf.writeVarInt(packet.cells.size());
        for (RoadPlan.Cell cell : packet.cells) {
            buf.writeBlockPos(cell.pos());
            buf.writeByte(cell.kind().ordinal());
        }
    }
    public static RoadPlanPacket decode(FriendlyByteBuf buf) {
        boolean active = buf.readBoolean();
        int count = Math.min(buf.readVarInt(), 8192);
        List<RoadPlan.Cell> cells = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BlockPos pos = buf.readBlockPos();
            int kind = Math.max(0, Math.min(buf.readByte(), RoadPlan.CellKind.values().length - 1));
            cells.add(new RoadPlan.Cell(pos, RoadPlan.CellKind.values()[kind]));
        }
        return new RoadPlanPacket(active, cells);
    }
    public static void handle(RoadPlanPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get();
        if (ctx.getDirection().getReceptionSide().isClient()) {
            ctx.enqueueWork(() -> com.dddgn.alice.client.ClientRoadState.update(packet));
        }
        ctx.setPacketHandled(true);
    }
}
