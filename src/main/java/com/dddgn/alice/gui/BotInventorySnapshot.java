package com.dddgn.alice.gui;

import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Immutable server-authoritative snapshot of a bot's inventory. Slots are indexed as the
 * vanilla {@code Inventory}: {@code getItem(0..35)} ordinary (hotbar 0..8 + main 9..35),
 * {@code getArmor(0..3)} → index 36..39 (36 feet, 37 legs, 38 chest, 39 head), {@code getItem(40)}
 * offhand. The main hand is the ordinary {@code selected} slot (0..8) and is not duplicated.
 * Produced by {@link BotInventoryService}; sent to a viewer over {@code BotInventoryPacket}.
 * A snapshot never holds a bot reference and never mutates inventory.
 */
public record BotInventorySnapshot(UUID botId, String name, List<ItemStack> slots) {

    /** Total slot count: 36 ordinary + 4 armor + 1 offhand. */
    public static final int SLOT_COUNT = 41;

    public BotInventorySnapshot {
        slots = List.copyOf(slots);
    }

    public ItemStack slot(int index) {
        return index >= 0 && index < slots.size() ? slots.get(index) : ItemStack.EMPTY;
    }

    /** True for armor slot indices (36..39). */
    public static boolean isArmor(int index) {
        return index >= 36 && index < 40;
    }

    /** True for the offhand slot (40). */
    public static boolean isOffhand(int index) {
        return index == 40;
    }
}
