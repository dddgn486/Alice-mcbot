package com.dddgn.alice.capability;

import net.minecraft.core.BlockPos;

import java.util.List;

/** Immutable, versioned C1 readonly observation. */
public record InterfaceSnapshot(
        int schemaVersion,
        String dimensionId,
        BlockPos position,
        String blockId,
        String blockEntityTypeId,
        long observedServerTick,
        ObservationStatus status,
        List<ItemFact> items,
        EnergyFact energy,
        List<FluidFact> fluids,
        String legacyProjection) {

    public InterfaceSnapshot {
        dimensionId = dimensionId == null ? "unknown" : dimensionId;
        position = position == null ? BlockPos.ZERO : position.immutable();
        blockId = blockId == null ? "unknown" : blockId;
        items = items == null ? List.of() : List.copyOf(items);
        fluids = fluids == null ? List.of() : List.copyOf(fluids);
        legacyProjection = legacyProjection == null ? "" : legacyProjection;
    }

    public record ItemFact(int index, String itemId, int count, int damage, String tagSnbt) {
        public ItemFact {
            itemId = itemId == null ? "minecraft:air" : itemId;
            tagSnbt = tagSnbt == null ? "" : tagSnbt;
        }
    }

    public record EnergyFact(int stored, int capacity, boolean canExtract, boolean canReceive) {
    }

    public record FluidFact(int index, String fluidId, int amount, int capacity, String tagSnbt) {
        public FluidFact {
            fluidId = fluidId == null ? "minecraft:empty" : fluidId;
            tagSnbt = tagSnbt == null ? "" : tagSnbt;
        }
    }
}
