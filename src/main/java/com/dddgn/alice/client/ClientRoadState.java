package com.dddgn.alice.client;

import com.dddgn.alice.network.RoadPlanPacket;
import com.dddgn.alice.road.RoadPlan;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@OnlyIn(Dist.CLIENT)
public final class ClientRoadState {
    private static volatile boolean active;
    private static volatile List<RoadPlan.Cell> cells = List.of();
    private static volatile Set<BlockPos> positions = Set.of();
    private ClientRoadState() {}

    public static void update(RoadPlanPacket packet) {
        active = packet.active();
        cells = List.copyOf(packet.cells());
        Set<BlockPos> next = new HashSet<>();
        for (RoadPlan.Cell cell : cells) next.add(cell.pos());
        positions = Set.copyOf(next);
    }
    public static boolean isActive() { return active; }
    public static List<RoadPlan.Cell> cells() { return cells; }
    public static boolean contains(BlockPos pos) { return positions.contains(pos); }
}
