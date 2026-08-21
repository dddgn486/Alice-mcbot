package com.dddgn.alice.transfer;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Objects;

/**
 * Server-thread-only legs for fixed original single chests and the bot's 36 ordinary inventory slots.
 * Simulation is evidence only, never a reservation or a cross-endpoint transaction.
 */
public final class ChestBotTransferPrimitive {
    private ChestBotTransferPrimitive() {
    }

    public static Result sourceChestToBot(ServerLevel level, TransferRequest request, Inventory botInventory) {
        Context context = validate(level, request, botInventory);
        if (context.code != null) return Result.rejected(context.code, null, null, null);
        InventoryObservation preSource = observeSource(context);
        InventoryObservation preBot = observeBot(context);
        InventoryObservation preDestination = observeDestination(context);
        String preflight = sourcePreflight(context, preSource, preBot, preDestination);
        if (preflight != null) return Result.rejected(preflight, preSource, preBot, preDestination);
        TransferTestHooks.fireBeforeFresh();
        Fresh fresh = fresh(context, preSource, preBot, preDestination);
        if (fresh.code != null) return Result.rejected(fresh.code, fresh.source, fresh.bot, fresh.destination);
        if (sourcePreflight(context, fresh.source, fresh.bot, fresh.destination) != null) {
            return Result.rejected(TransferCodes.SIMULATION_CONFLICT, fresh.source, fresh.bot, fresh.destination);
        }
        int extracted = extract(context.source.handler(), context.request.itemId(), context.request.count());
        TransferTestHooks.fireAfterActual();
        if (extracted != context.request.count()) return post(context, fresh, TransferCodes.SOURCE_DELTA_MISMATCH);
        int inserted = insertBot(context.botInventory, context.item, context.request.count());
        if (inserted != context.request.count()) return post(context, fresh, TransferCodes.SOURCE_DELTA_MISMATCH);
        return prove(context, fresh, -context.request.count(), context.request.count(), 0,
                TransferCodes.SOURCE_DELTA_MISMATCH);
    }

    public static Result botToDestinationChest(ServerLevel level, TransferRequest request, Inventory botInventory) {
        Context context = validate(level, request, botInventory);
        if (context.code != null) return Result.rejected(context.code, null, null, null);
        InventoryObservation preSource = observeSource(context);
        InventoryObservation preBot = observeBot(context);
        InventoryObservation preDestination = observeDestination(context);
        String preflight = destinationPreflight(context, preSource, preBot, preDestination);
        if (preflight != null) return Result.rejected(preflight, preSource, preBot, preDestination);
        TransferTestHooks.fireBeforeFresh();
        Fresh fresh = fresh(context, preSource, preBot, preDestination);
        if (fresh.code != null) return Result.rejected(fresh.code, fresh.source, fresh.bot, fresh.destination);
        if (destinationPreflight(context, fresh.source, fresh.bot, fresh.destination) != null) {
            return Result.rejected(TransferCodes.SIMULATION_CONFLICT, fresh.source, fresh.bot, fresh.destination);
        }

        // Write destination first. A rejected/partial insert leaves every uninserted item in bot inventory.
        int inserted = insert(context.destination.handler(), context.item, context.request.count());
        TransferTestHooks.fireAfterActual();
        if (inserted != context.request.count()) return post(context, fresh, TransferCodes.DESTINATION_DELTA_MISMATCH);
        int removed = removeBot(context.botInventory, context.request.itemId(), context.request.count());
        if (removed != context.request.count()) return post(context, fresh, TransferCodes.DESTINATION_DELTA_MISMATCH);
        return prove(context, fresh, 0, -context.request.count(), context.request.count(),
                TransferCodes.DESTINATION_DELTA_MISMATCH);
    }

    private static String sourcePreflight(Context c, InventoryObservation source, InventoryObservation bot,
                                          InventoryObservation destination) {
        String predicate = predicateCode(c, source, bot, destination);
        if (predicate != null) return predicate;
        if (source.count(c.request.itemId()) < c.request.count()) return TransferCodes.SOURCE_INSUFFICIENT;
        if (!CapacityPreflight.forBot(bot, c.request.itemId(), c.request.count(), c.item.getMaxStackSize()).acceptsFullRequest()
                || !botCanAcceptFull(c.botInventory, c.item, c.request.count())
                || !simulatesFullInsert(c.destination.handler(), c.item, c.request.count())) return TransferCodes.CAPACITY_REJECTED;
        return simulatesFullExtract(c.source.handler(), c.request.itemId(), c.request.count())
                ? null : TransferCodes.SOURCE_INSUFFICIENT;
    }

    private static String destinationPreflight(Context c, InventoryObservation source, InventoryObservation bot,
                                               InventoryObservation destination) {
        String predicate = predicateCode(c, source, bot, destination);
        if (predicate != null) return predicate;
        if (bot.count(c.request.itemId()) < c.request.count()) return TransferCodes.SOURCE_INSUFFICIENT;
        return simulatesFullInsert(c.destination.handler(), c.item, c.request.count())
                ? null : TransferCodes.CAPACITY_REJECTED;
    }

    private static Result prove(Context c, Fresh fresh, int expectedSource, int expectedBot, int expectedDestination,
                                String mismatch) {
        InventoryObservation source = observeSource(c);
        InventoryObservation bot = observeBot(c);
        InventoryObservation destination = observeDestination(c);
        int sourceDelta = source.count(c.request.itemId()) - fresh.source.count(c.request.itemId());
        int botDelta = bot.count(c.request.itemId()) - fresh.bot.count(c.request.itemId());
        int destinationDelta = destination.count(c.request.itemId()) - fresh.destination.count(c.request.itemId());
        if (sourceDelta != expectedSource || botDelta != expectedBot || destinationDelta != expectedDestination) {
            return Result.unknown(mismatch, source, bot, destination, sourceDelta, botDelta, destinationDelta);
        }
        return Result.success(source, bot, destination, sourceDelta, botDelta, destinationDelta);
    }

    private static Result post(Context c, Fresh fresh, String mismatch) {
        InventoryObservation source = observeSource(c);
        InventoryObservation bot = observeBot(c);
        InventoryObservation destination = observeDestination(c);
        int sourceDelta = source.count(c.request.itemId()) - fresh.source.count(c.request.itemId());
        int botDelta = bot.count(c.request.itemId()) - fresh.bot.count(c.request.itemId());
        int destinationDelta = destination.count(c.request.itemId()) - fresh.destination.count(c.request.itemId());
        if (sourceDelta == 0 && botDelta == 0 && destinationDelta == 0) {
            return Result.rejected(mismatch, source, bot, destination);
        }
        return Result.unknown(mismatch, source, bot, destination, sourceDelta, botDelta, destinationDelta);
    }

    private static Fresh fresh(Context c, InventoryObservation expectedSource, InventoryObservation expectedBot,
                               InventoryObservation expectedDestination) {
        Context revalidated = validate(c.level, c.request, c.botInventory);
        if (revalidated.code != null) return new Fresh(TransferCodes.EXTERNAL_INTERFERENCE, expectedSource, expectedBot, expectedDestination);
        InventoryObservation source = observeSource(revalidated);
        InventoryObservation bot = observeBot(revalidated);
        InventoryObservation destination = observeDestination(revalidated);
        if (!sameFacts(expectedSource, source) || !sameFacts(expectedBot, bot) || !sameFacts(expectedDestination, destination)) {
            return new Fresh(TransferCodes.SIMULATION_CONFLICT, source, bot, destination);
        }
        return new Fresh(null, source, bot, destination);
    }

    private static Context validate(ServerLevel level, TransferRequest request, Inventory inventory) {
        Objects.requireNonNull(level); Objects.requireNonNull(request); Objects.requireNonNull(inventory);
        if (!request.source().dimensionId().equals(level.dimension().location())
                || !request.destination().dimensionId().equals(level.dimension().location())) {
            return new Context(null, request, inventory, null, null, null, TransferCodes.ENDPOINT_NOT_LOADED);
        }
        if (request.source().equals(request.destination())) return new Context(null, request, inventory, null, null, null, TransferCodes.SAME_ENDPOINT_REJECTED);
        ChestEndpointRef.Validation source = request.source().validate(level);
        if (!source.accepted()) return new Context(null, request, inventory, null, source, null, source.code());
        ChestEndpointRef.Validation destination = request.destination().validate(level);
        if (!destination.accepted()) return new Context(null, request, inventory, null, source, destination, destination.code());
        Item item = ForgeRegistries.ITEMS.getValue(request.itemId());
        if (item == null) return new Context(null, request, inventory, null, source, destination, TransferCodes.INVALID_ITEM_ID);
        return new Context(level, request, inventory, item, source, destination, null);
    }

    private static String predicateCode(Context c, InventoryObservation source, InventoryObservation bot,
                                        InventoryObservation destination) {
        // Minecraft 1.20.1 represents non-default stack data as NBT; data-components do not exist in this target version.
        return source.hasUnsupportedComponents(c.request.itemId()) || bot.hasUnsupportedComponents(c.request.itemId())
                || destination.hasUnsupportedComponents(c.request.itemId()) ? TransferCodes.UNSUPPORTED_ITEM_COMPONENTS : null;
    }

    private static boolean simulatesFullExtract(IItemHandler handler, ResourceLocation itemId, int count) {
        int remaining = count;
        for (int slot = 0; slot < handler.getSlots() && remaining > 0; slot++) {
            ItemStack current = handler.getStackInSlot(slot);
            if (!matches(current, itemId)) continue;
            ItemStack result = handler.extractItem(slot, remaining, true);
            if (!matches(result, itemId)) return false;
            remaining -= result.getCount();
        }
        return remaining == 0;
    }
    private static boolean simulatesFullInsert(IItemHandler handler, Item item, int count) {
        ItemStack remainder = new ItemStack(item, count);
        for (int slot = 0; slot < handler.getSlots() && !remainder.isEmpty(); slot++) remainder = handler.insertItem(slot, remainder, true);
        return remainder.isEmpty();
    }
    private static int extract(IItemHandler handler, ResourceLocation itemId, int count) {
        int remaining = count;
        for (int slot = 0; slot < handler.getSlots() && remaining > 0; slot++) {
            if (!matches(handler.getStackInSlot(slot), itemId)) continue;
            ItemStack result = handler.extractItem(slot, remaining, false);
            if (!matches(result, itemId)) break;
            remaining -= result.getCount();
        }
        return count - remaining;
    }
    private static int insert(IItemHandler handler, Item item, int count) {
        ItemStack remainder = new ItemStack(item, count);
        for (int slot = 0; slot < handler.getSlots() && !remainder.isEmpty(); slot++) remainder = handler.insertItem(slot, remainder, false);
        return count - remainder.getCount();
    }
    private static boolean botCanAcceptFull(Inventory inventory, Item item, int count) {
        int remaining = count;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        ItemStack requested = new ItemStack(item, 1);
        for (int slot = 0; slot < InventoryObservation.BOT_ORDINARY_SLOT_COUNT && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!inventory.canPlaceItem(slot, requested)) continue;
            if (!stack.isEmpty() && !matches(stack, id)) continue;
            int max = stack.isEmpty() ? item.getMaxStackSize() : Math.min(stack.getMaxStackSize(), item.getMaxStackSize());
            remaining -= stack.isEmpty() ? max : Math.max(0, max - stack.getCount());
        }
        return remaining <= 0;
    }
    private static int insertBot(Inventory inventory, Item item, int count) {
        int remaining = count;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        for (int slot = 0; slot < InventoryObservation.BOT_ORDINARY_SLOT_COUNT && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            ItemStack requested = new ItemStack(item, 1);
            if (!inventory.canPlaceItem(slot, requested)) continue;
            int max = stack.isEmpty() ? item.getMaxStackSize() : Math.min(stack.getMaxStackSize(), item.getMaxStackSize());
            if (stack.isEmpty()) { int accepted = Math.min(max, remaining); inventory.setItem(slot, new ItemStack(item, accepted)); remaining -= accepted; }
            else if (matches(stack, id)) { int accepted = Math.min(Math.max(0, max - stack.getCount()), remaining); stack.grow(accepted); remaining -= accepted; }
        }
        return count - remaining;
    }
    private static int removeBot(Inventory inventory, ResourceLocation itemId, int count) {
        int remaining = count;
        for (int slot = 0; slot < InventoryObservation.BOT_ORDINARY_SLOT_COUNT && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!matches(stack, itemId)) continue;
            int removed = Math.min(stack.getCount(), remaining); stack.shrink(removed);
            if (stack.isEmpty()) inventory.setItem(slot, ItemStack.EMPTY);
            remaining -= removed;
        }
        return count - remaining;
    }
    private static boolean matches(ItemStack stack, ResourceLocation id) {
        return !stack.isEmpty() && id.equals(ForgeRegistries.ITEMS.getKey(stack.getItem())) && !stack.hasTag();
    }
    private static boolean sameFacts(InventoryObservation expected, InventoryObservation actual) {
        return expected.identity().equals(actual.identity()) && expected.slots().equals(actual.slots());
    }
    private static InventoryObservation observeSource(Context c) { return InventoryObservation.observeHandler("source:" + c.request.source().dimensionId() + ":" + c.request.source().position(), c.level, c.source.handler()); }
    private static InventoryObservation observeBot(Context c) { return InventoryObservation.observeBot("bot:" + c.request.botId(), c.level, c.botInventory); }
    private static InventoryObservation observeDestination(Context c) { return InventoryObservation.observeHandler("destination:" + c.request.destination().dimensionId() + ":" + c.request.destination().position(), c.level, c.destination.handler()); }

    private record Context(ServerLevel level, TransferRequest request, Inventory botInventory, Item item,
                           ChestEndpointRef.Validation source, ChestEndpointRef.Validation destination, String code) { }
    private record Fresh(String code, InventoryObservation source, InventoryObservation bot, InventoryObservation destination) { }
    public record Result(boolean wrote, boolean proven, boolean unknownDiscrepancy, String code,
                         InventoryObservation source, InventoryObservation bot, InventoryObservation destination,
                         int sourceDelta, int botDelta, int destinationDelta) {
        private static Result rejected(String code, InventoryObservation source, InventoryObservation bot, InventoryObservation destination) { return new Result(false, false, false, code, source, bot, destination, 0, 0, 0); }
        private static Result success(InventoryObservation source, InventoryObservation bot, InventoryObservation destination, int sourceDelta, int botDelta, int destinationDelta) { return new Result(true, true, false, TransferCodes.TRANSFER_VERIFIED, source, bot, destination, sourceDelta, botDelta, destinationDelta); }
        private static Result unknown(String code, InventoryObservation source, InventoryObservation bot, InventoryObservation destination, int sourceDelta, int botDelta, int destinationDelta) { return new Result(true, false, true, TransferCodes.UNKNOWN_DISCREPANCY + ":" + code, source, bot, destination, sourceDelta, botDelta, destinationDelta); }
    }
}
