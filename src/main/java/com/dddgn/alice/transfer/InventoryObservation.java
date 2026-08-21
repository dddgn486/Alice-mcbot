package com.dddgn.alice.transfer;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable copied slot facts for delta/freshness comparisons; it never retains an ItemStack. */
public record InventoryObservation(String identity, long observedServerTick, List<Slot> slots, String digest) {
    public static final int BOT_ORDINARY_SLOT_COUNT = 36;

    public InventoryObservation {
        Objects.requireNonNull(identity, "identity");
        slots = List.copyOf(slots);
        Objects.requireNonNull(digest, "digest");
    }

    public static InventoryObservation observeHandler(String identity, ServerLevel level, IItemHandler handler) {
        List<Slot> slots = new ArrayList<>();
        for (int index = 0; index < handler.getSlots(); index++) {
            slots.add(slot(index, handler.getStackInSlot(index), handler.getSlotLimit(index)));
        }
        return create(identity, level.getGameTime(), slots);
    }

    public static InventoryObservation observeBot(String identity, ServerLevel level, Inventory inventory) {
        List<Slot> slots = new ArrayList<>(BOT_ORDINARY_SLOT_COUNT);
        for (int index = 0; index < BOT_ORDINARY_SLOT_COUNT; index++) {
            slots.add(slot(index, inventory.getItem(index), inventory.getMaxStackSize()));
        }
        return create(identity, level.getGameTime(), slots);
    }

    public int count(ResourceLocation itemId) {
        return slots.stream().filter(slot -> itemId.equals(slot.itemId())).mapToInt(Slot::count).sum();
    }

    public boolean hasUnsupportedComponents(ResourceLocation itemId) {
        return slots.stream().anyMatch(slot -> itemId.equals(slot.itemId()) && !slot.defaultStack());
    }

    private static InventoryObservation create(String identity, long tick, List<Slot> slots) {
        StringBuilder digest = new StringBuilder(identity).append('@').append(tick);
        for (Slot slot : slots) {
            digest.append('|').append(slot.index).append(':').append(slot.itemId).append(':')
                    .append(slot.count).append(':').append(slot.slotLimit).append(':').append(slot.defaultStack);
        }
        return new InventoryObservation(identity, tick, slots, Integer.toHexString(digest.toString().hashCode()));
    }

    private static Slot slot(int index, ItemStack stack, int slotLimit) {
        if (stack.isEmpty()) {
            return new Slot(index, null, 0, Math.max(0, slotLimit), 0, true);
        }
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return new Slot(index, itemId, stack.getCount(), Math.max(0, slotLimit), stack.getMaxStackSize(), !stack.hasTag());
    }

    public record Slot(int index, ResourceLocation itemId, int count, int slotLimit, int itemMaxStack, boolean defaultStack) {
        public Slot {
            if (count < 0 || slotLimit < 0 || itemMaxStack < 0) {
                throw new IllegalArgumentException("negative_slot_fact");
            }
        }
    }
}
