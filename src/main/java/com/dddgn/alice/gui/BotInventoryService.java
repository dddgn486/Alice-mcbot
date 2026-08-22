package com.dddgn.alice.gui;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.network.AliceNetwork;
import com.dddgn.alice.network.BotInventoryPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-authoritative read/write entry point for a bot's inventory (direction B). The client
 * never mutates local state: every intent is sent as a {@code BotInventoryActionPacket} and
 * handled here, which validates (only server authority), writes the bot inventory, then pushes
 * the authoritative snapshot back to the viewer. Opening is via {@link #open(ServerPlayer, BotPlayer)}.
 * <p>
 * The {@code applyAction} guard: requester is an allowed actor, the bot exists, the bot is not
 * running a task ({@link BotManager#isBusy} → read-only), the slot index is in 0..40, and
 * armor/offhand placement honours canEquip/maxStack=1/binding curse (mirroring the retired
 * {@code BotArmorSlot}/{@code BotOffhandSlot} rules).
 */
public final class BotInventoryService {
    private BotInventoryService() {
    }

    /** Reads an authoritative snapshot of the bot's inventory; empty when the bot is absent. */
    public static Optional<BotInventorySnapshot> snapshot(ServerLevel level, UUID botId) {
        BotPlayer bot = findBot(level, botId);
        if (bot == null) {
            return Optional.empty();
        }
        return Optional.of(buildSnapshot(bot));
    }

    /** Reads a snapshot and opens the bot-inventory screen on the viewer's client. */
    public static void open(ServerPlayer viewer, BotPlayer bot) {
        if (viewer == null || bot == null) {
            return;
        }
        BotInventorySnapshot snap = buildSnapshot(bot);
        AliceNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> viewer),
                new BotInventoryPacket(snap.botId(), snap.name(), snap.slots()));
        BotLog.info("bot_inv: command player={} bot={} slots={} code=accepted",
                viewer.getName().getString(), bot.getName().getString(), snap.slots().size());
    }

    /**
     * Applies a validated action to the bot inventory and returns the outcome code, then
     * pushes the refreshed snapshot to the requester. Never mutates the bot when busy.
     */
    public static String applyAction(ServerLevel level, UUID botId, ServerPlayer actor,
                                     int slotIndex, ActionType action, int playerSlot) {
        if (actor == null) {
            return "unauthorized_actor";
        }
        if (botId == null) {
            return "invalid_bot";
        }
        BotPlayer bot = findBot(level, botId);
        if (bot == null) {
            return "invalid_bot";
        }
        if (BotManager.isBusy(bot)) {
            pushSnapshot(actor, bot);
            return "task_active";
        }
        if (slotIndex < 0 || slotIndex >= BotInventorySnapshot.SLOT_COUNT) {
            pushSnapshot(actor, bot);
            return "invalid_slot";
        }
        Inventory inv = bot.getInventory();
        String code;
        switch (action) {
            case PICKUP, QUICK_MOVE -> code = pickupToPlayer(inv, actor, slotIndex);
            case PLACE -> code = placeFromPlayer(inv, actor, slotIndex, playerSlot);
            default -> code = "invalid_action";
        }
        pushSnapshot(actor, bot);
        return code;
    }

    /** Moves the whole bot slot stack into the actor's inventory (merging stacks where possible). */
    private static String pickupToPlayer(Inventory botInv, ServerPlayer actor, int slotIndex) {
        ItemStack source = botInv.getItem(slotIndex);
        if (source.isEmpty()) {
            return sourceMissing(slotIndex);
        }
        if (slotIndex >= 36 && slotIndex <= 40) {
            // Armor/offhand slot: honour the binding curse for take-out.
            if (EnchantmentHelper.hasBindingCurse(source) && !actor.isCreative()) {
                return "cursed_binding";
            }
        }
        // Try to add to the actor's inventory; spills back nothing (we only move whole stack).
        ItemStack remaining = source.copy();
        boolean moved = mergeIntoInventory(actor.getInventory(), remaining);
        if (!moved) {
            return "player_inventory_full";
        }
        botInv.setItem(slotIndex, ItemStack.EMPTY);
        return "success";
    }

    /** Moves the whole actor-inventory playerSlot stack into the bot slot slotIndex. */
    private static String placeFromPlayer(Inventory botInv, ServerPlayer actor, int slotIndex, int playerSlot) {
        if (playerSlot < 0 || playerSlot >= actor.getInventory().getContainerSize()) {
            return "invalid_player_slot";
        }
        ItemStack source = actor.getInventory().getItem(playerSlot);
        if (source.isEmpty()) {
            return "player_slot_empty";
        }
        ItemStack toPlace = source.copy();
        String routeCode = canPlace(botInv, actor, slotIndex, toPlace);
        if (routeCode != null) {
            return routeCode;
        }
        // Transfer the whole stack.
        actor.getInventory().setItem(playerSlot, ItemStack.EMPTY);
        botInv.setItem(slotIndex, toPlace);
        return "success";
    }

    /** Validates that {@code toPlace} may be placed in bot slot {@code slotIndex}. */
    private static String canPlace(Inventory botInv, ServerPlayer actor, int slotIndex, ItemStack toPlace) {
        if (slotIndex >= 36 && slotIndex < 40) {
            // Armor slot: only the matching armorable item, max stack 1.
            if (toPlace.getCount() > 1) {
                return "armor_mismatch";
            }
            EquipmentSlot eq = armorSlotFor(slotIndex);
            if (!toPlace.canEquip(eq, actor)) {
                return "armor_mismatch";
            }
        } else if (slotIndex == 40) {
            if (toPlace.getCount() > 1) {
                return "armor_mismatch";
            }
            EquipmentSlot eq = LivingEntity.getEquipmentSlotForItem(toPlace);
            if (eq != EquipmentSlot.OFFHAND) {
                return "armor_mismatch";
            }
        }
        return null;
    }

    private static EquipmentSlot armorSlotFor(int inventoryIndex) {
        return switch (inventoryIndex) {
            case 36 -> EquipmentSlot.FEET;
            case 37 -> EquipmentSlot.LEGS;
            case 38 -> EquipmentSlot.CHEST;
            case 39 -> EquipmentSlot.HEAD;
            default -> EquipmentSlot.MAINHAND;
        };
    }

    /** Attempts to merge {@code stack} into {@code inv} (merge into same + empty slots). */
    private static boolean mergeIntoInventory(Inventory inv, ItemStack stack) {
        int size = inv.getContainerSize();
        // Merge into existing same-type stacks first.
        for (int i = 0; i < size && !stack.isEmpty(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !ItemStack.isSameItemSameTags(s, stack)) {
                continue;
            }
            int max = Math.min(s.getMaxStackSize(), stack.getMaxStackSize());
            int add = Math.min(max - s.getCount(), stack.getCount());
            if (add > 0) {
                s.grow(add);
                stack.shrink(add);
            }
        }
        // Then fill empty slots.
        for (int i = 0; i < size && !stack.isEmpty(); i++) {
            if (inv.getItem(i).isEmpty()) {
                int max = stack.getMaxStackSize();
                int put = Math.min(max, stack.getCount());
                inv.setItem(i, stack.split(put));
            }
        }
        return stack.isEmpty();
    }

    private static String sourceMissing(int slotIndex) {
        return "slot_empty";
    }

    private static void pushSnapshot(ServerPlayer viewer, BotPlayer bot) {
        BotInventorySnapshot snap = buildSnapshot(bot);
        AliceNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> viewer),
                new BotInventoryPacket(snap.botId(), snap.name(), snap.slots()));
    }

    private static BotInventorySnapshot buildSnapshot(BotPlayer bot) {
        Inventory inv = bot.getInventory();
        List<ItemStack> slots = new ArrayList<>(BotInventorySnapshot.SLOT_COUNT);
        // 36 ordinary slots (index 0..35).
        for (int i = 0; i < 36; i++) {
            slots.add(inv.getItem(i));
        }
        // 4 armor slots (index 36..39): getArmor(0)=feet... which corresponds to `items` index 39=HEAD.
        // Inventory.getItem(36..39) maps to armor in order feet/legs/chest/head.
        for (int i = 36; i < 40; i++) {
            slots.add(inv.getItem(i));
        }
        // Offhand (index 40).
        slots.add(inv.getItem(40));
        return new BotInventorySnapshot(bot.getUUID(), bot.getName().getString(), slots);
    }

    /** Finds a live bot in the given level by UUID via the player list (placeNewPlayer registers bots). */
    private static BotPlayer findBot(ServerLevel level, UUID botId) {
        for (net.minecraft.server.level.ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player instanceof BotPlayer bot && bot.getUUID().equals(botId)) {
                return bot;
            }
        }
        return null;
    }

    public enum ActionType { PICKUP, PLACE, QUICK_MOVE }
}
