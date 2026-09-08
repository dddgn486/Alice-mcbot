package com.dddgn.alice.transfer;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure full-capacity calculation for the bot's 36 ordinary inventory slots. */
public record CapacityPreflight(ResourceLocation itemId, int requestedCount, int supportedCapacity,
                                int remainder, List<SlotEvidence> slots) {
    public CapacityPreflight {
        Objects.requireNonNull(itemId, "itemId");
        if (requestedCount <= 0) {
            throw new IllegalArgumentException(TransferCodes.INVALID_COUNT);
        }
        slots = List.copyOf(slots);
        if (supportedCapacity < 0 || remainder < 0) {
            throw new IllegalArgumentException("negative_capacity");
        }
    }

    public static CapacityPreflight forBot(InventoryObservation observation, ResourceLocation itemId,
                                           int requestedCount, int requestedItemMaxStack) {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(itemId, "itemId");
        if (requestedCount <= 0) {
            throw new IllegalArgumentException(TransferCodes.INVALID_COUNT);
        }
        if (requestedItemMaxStack <= 0) {
            throw new IllegalArgumentException("invalid_item_max_stack");
        }
        if (observation.slots().size() != InventoryObservation.BOT_ORDINARY_SLOT_COUNT) {
            throw new IllegalArgumentException("bot_observation_not_36_slots");
        }

        List<SlotEvidence> evidence = new ArrayList<>(InventoryObservation.BOT_ORDINARY_SLOT_COUNT);
        int capacity = 0;
        for (InventoryObservation.Slot slot : observation.slots()) {
            int accepted = acceptedBy(slot, itemId, requestedItemMaxStack);
            capacity = Math.addExact(capacity, accepted);
            evidence.add(new SlotEvidence(slot.index(), slot.itemId(), slot.count(), slot.slotLimit(),
                    slot.itemMaxStack(), slot.defaultStack(), accepted));
        }
        return new CapacityPreflight(itemId, requestedCount, capacity, Math.max(0, requestedCount - capacity), evidence);
    }

    public boolean acceptsFullRequest() {
        return remainder == 0;
    }

    private static int acceptedBy(InventoryObservation.Slot slot, ResourceLocation requestedItem,
                                  int requestedItemMaxStack) {
        int limit = Math.min(slot.slotLimit(), requestedItemMaxStack);
        if (slot.itemId() == null) {
            return limit;
        }
        if (!requestedItem.equals(slot.itemId()) || !slot.defaultStack()) {
            return 0;
        }
        return Math.max(0, Math.min(limit, slot.itemMaxStack()) - slot.count());
    }

    public record SlotEvidence(int index, ResourceLocation observedItemId, int observedCount, int slotLimit,
                               int observedItemMaxStack, boolean observedDefaultStack, int acceptedCount) {
        public SlotEvidence {
            if (acceptedCount < 0) {
                throw new IllegalArgumentException("negative_slot_capacity");
            }
        }
    }
}
