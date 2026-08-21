package com.dddgn.alice.transfer;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.task.TransferTask;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.util.UUID;

/** Isolated server-side transfer checks. Each result records conservation evidence; it never treats a process timeout as a pass. */
public final class TransferFixture {
    private TransferFixture() {
    }

    public static boolean run(ServerLevel level, BotPlayer bot, BlockPos base) {
        Inventory inventory = bot.getInventory();
        BlockPos source = base;
        BlockPos destination = base.east(3);
        level.setBlock(source, Blocks.CHEST.defaultBlockState(), 3);
        level.setBlock(destination, Blocks.CHEST.defaultBlockState(), 3);
        ChestBlockEntity sourceChest = chest(level, source);
        ChestBlockEntity destinationChest = chest(level, destination);
        if (sourceChest == null || destinationChest == null) {
            return report("endpoint_setup", null, false, TransferCodes.ENDPOINT_NOT_SINGLE_CHEST,
                    TransferLedgerData.Location.NOT_MOVED, 0, 0, 0);
        }

        boolean pass = normalLegs(level, inventory, source, destination, sourceChest, destinationChest);
        pass &= capacityRejected(level, inventory, source, destination, sourceChest, destinationChest);
        pass &= sourceInsufficient(level, inventory, source, destination, sourceChest, destinationChest);
        pass &= componentsRejected(level, inventory, source, destination, sourceChest, destinationChest);
        pass &= endpointRejected(level, inventory, source, destination, sourceChest, destinationChest);
        pass &= simulationConflict(level, inventory, source, destination, sourceChest, destinationChest);
        pass &= postMismatches(level, inventory, source, destination, sourceChest, destinationChest);
        pass &= ledgerPolicies(level, source, destination);
        pass &= taskInterruptPolicies(level, bot, source, destination);
        BotLog.info("TRANSFER_FIXTURE_SUITE {} request/state/code/location/source-bot-destination-delta asserted",
                pass ? "PASS" : "FAIL");
        return pass;
    }

    private static boolean normalLegs(ServerLevel level, Inventory inventory, BlockPos source, BlockPos destination,
                                      ChestBlockEntity sourceChest, ChestBlockEntity destinationChest) {
        clear(inventory, sourceChest, destinationChest);
        sourceChest.setItem(0, new ItemStack(Items.IRON_INGOT, 8));
        TransferRequest request = request(level, source, destination, 8);
        ChestBotTransferPrimitive.Result sourceResult = ChestBotTransferPrimitive.sourceChestToBot(level, request, inventory);
        ChestBotTransferPrimitive.Result destinationResult = ChestBotTransferPrimitive.botToDestinationChest(level, request, inventory);
        boolean sourcePass = sourceResult.proven() && sourceResult.sourceDelta() == -8
                && sourceResult.botDelta() == 8 && sourceResult.destinationDelta() == 0;
        boolean destinationPass = destinationResult.proven() && destinationResult.sourceDelta() == 0
                && destinationResult.botDelta() == -8 && destinationResult.destinationDelta() == 8;
        return report("source_leg", request, sourcePass, sourceResult.code(), TransferLedgerData.Location.BOT_INVENTORY,
                sourceResult.sourceDelta(), sourceResult.botDelta(), sourceResult.destinationDelta())
                & report("destination_leg", request, destinationPass, destinationResult.code(),
                TransferLedgerData.Location.DESTINATION_CHEST, destinationResult.sourceDelta(),
                destinationResult.botDelta(), destinationResult.destinationDelta());
    }

    private static boolean capacityRejected(ServerLevel level, Inventory inventory, BlockPos source, BlockPos destination,
                                            ChestBlockEntity sourceChest, ChestBlockEntity destinationChest) {
        clear(inventory, sourceChest, destinationChest);
        sourceChest.setItem(0, new ItemStack(Items.IRON_INGOT, 1));
        for (int slot = 0; slot < InventoryObservation.BOT_ORDINARY_SLOT_COUNT; slot++) inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        TransferRequest request = request(level, source, destination, 1);
        ChestBotTransferPrimitive.Result result = ChestBotTransferPrimitive.sourceChestToBot(level, request, inventory);
        boolean pass = !result.wrote() && TransferCodes.CAPACITY_REJECTED.equals(result.code())
                && result.sourceDelta() == 0 && result.botDelta() == 0 && result.destinationDelta() == 0;
        return report("capacity_rejected_slot_validity", request, pass, result.code(), TransferLedgerData.Location.NOT_MOVED,
                result.sourceDelta(), result.botDelta(), result.destinationDelta());
    }

    private static boolean sourceInsufficient(ServerLevel level, Inventory inventory, BlockPos source, BlockPos destination,
                                              ChestBlockEntity sourceChest, ChestBlockEntity destinationChest) {
        clear(inventory, sourceChest, destinationChest);
        sourceChest.setItem(0, new ItemStack(Items.IRON_INGOT, 1));
        TransferRequest request = request(level, source, destination, 2);
        ChestBotTransferPrimitive.Result result = ChestBotTransferPrimitive.sourceChestToBot(level, request, inventory);
        boolean pass = !result.wrote() && TransferCodes.SOURCE_INSUFFICIENT.equals(result.code())
                && result.sourceDelta() == 0 && result.botDelta() == 0 && result.destinationDelta() == 0;
        return report("source_insufficient", request, pass, result.code(), TransferLedgerData.Location.NOT_MOVED,
                result.sourceDelta(), result.botDelta(), result.destinationDelta());
    }

    private static boolean componentsRejected(ServerLevel level, Inventory inventory, BlockPos source, BlockPos destination,
                                              ChestBlockEntity sourceChest, ChestBlockEntity destinationChest) {
        clear(inventory, sourceChest, destinationChest);
        ItemStack tagged = new ItemStack(Items.IRON_INGOT, 1);
        tagged.getOrCreateTag().putString("fixture", "unsupported");
        sourceChest.setItem(0, tagged);
        TransferRequest request = request(level, source, destination, 1);
        ChestBotTransferPrimitive.Result result = ChestBotTransferPrimitive.sourceChestToBot(level, request, inventory);
        boolean pass = !result.wrote() && TransferCodes.UNSUPPORTED_ITEM_COMPONENTS.equals(result.code())
                && result.sourceDelta() == 0 && result.botDelta() == 0 && result.destinationDelta() == 0;
        return report("unsupported_item_components", request, pass, result.code(), TransferLedgerData.Location.NOT_MOVED,
                result.sourceDelta(), result.botDelta(), result.destinationDelta());
    }

    private static boolean endpointRejected(ServerLevel level, Inventory inventory, BlockPos source, BlockPos destination,
                                            ChestBlockEntity sourceChest, ChestBlockEntity destinationChest) {
        clear(inventory, sourceChest, destinationChest);
        level.setBlock(destination, Blocks.DIRT.defaultBlockState(), 3);
        TransferRequest request = request(level, source, destination, 1);
        ChestBotTransferPrimitive.Result endpoint = ChestBotTransferPrimitive.sourceChestToBot(level, request, inventory);
        boolean sameEndpointPass;
        try {
            request(level, source, source, 1);
            sameEndpointPass = false;
        } catch (IllegalArgumentException exception) {
            sameEndpointPass = TransferCodes.SAME_ENDPOINT_REJECTED.equals(exception.getMessage());
        }
        level.setBlock(destination, Blocks.CHEST.defaultBlockState(), 3);
        boolean endpointPass = !endpoint.wrote() && TransferCodes.ENDPOINT_NOT_SINGLE_CHEST.equals(endpoint.code());
        return report("endpoint_not_single_chest", request, endpointPass, endpoint.code(), TransferLedgerData.Location.NOT_MOVED,
                endpoint.sourceDelta(), endpoint.botDelta(), endpoint.destinationDelta())
                & report("same_endpoint_rejected", request, sameEndpointPass, TransferCodes.SAME_ENDPOINT_REJECTED,
                TransferLedgerData.Location.NOT_MOVED, 0, 0, 0);
    }

    private static boolean simulationConflict(ServerLevel level, Inventory inventory, BlockPos source, BlockPos destination,
                                              ChestBlockEntity sourceChest, ChestBlockEntity destinationChest) {
        clear(inventory, sourceChest, destinationChest);
        sourceChest.setItem(0, new ItemStack(Items.IRON_INGOT, 2));
        TransferRequest request = request(level, source, destination, 1);
        TransferTestHooks.beforeFresh(() -> sourceChest.setItem(1, new ItemStack(Items.IRON_INGOT, 1)));
        ChestBotTransferPrimitive.Result result = ChestBotTransferPrimitive.sourceChestToBot(level, request, inventory);
        boolean pass = !result.wrote() && TransferCodes.SIMULATION_CONFLICT.equals(result.code())
                && result.sourceDelta() == 0 && result.botDelta() == 0 && result.destinationDelta() == 0;
        return report("simulation_conflict", request, pass, result.code(), TransferLedgerData.Location.NOT_MOVED,
                result.sourceDelta(), result.botDelta(), result.destinationDelta());
    }

    private static boolean postMismatches(ServerLevel level, Inventory inventory, BlockPos source, BlockPos destination,
                                          ChestBlockEntity sourceChest, ChestBlockEntity destinationChest) {
        clear(inventory, sourceChest, destinationChest);
        sourceChest.setItem(0, new ItemStack(Items.IRON_INGOT, 2));
        TransferRequest sourceRequest = request(level, source, destination, 1);
        TransferTestHooks.afterActual(() -> inventory.setItem(1, new ItemStack(Items.IRON_INGOT, 1)));
        ChestBotTransferPrimitive.Result sourceResult = ChestBotTransferPrimitive.sourceChestToBot(level, sourceRequest, inventory);
        boolean sourcePass = sourceResult.unknownDiscrepancy() && sourceResult.code().contains(TransferCodes.SOURCE_DELTA_MISMATCH);

        clear(inventory, sourceChest, destinationChest);
        inventory.setItem(0, new ItemStack(Items.IRON_INGOT, 1));
        TransferRequest destinationRequest = request(level, source, destination, 1);
        TransferTestHooks.afterActual(() -> inventory.setItem(0, new ItemStack(Items.IRON_INGOT, 2)));
        ChestBotTransferPrimitive.Result destinationResult = ChestBotTransferPrimitive.botToDestinationChest(level, destinationRequest, inventory);
        boolean destinationPass = destinationResult.unknownDiscrepancy()
                && destinationResult.code().contains(TransferCodes.DESTINATION_DELTA_MISMATCH);
        return report("source_post_mismatch", sourceRequest, sourcePass, sourceResult.code(), TransferLedgerData.Location.UNKNOWN,
                sourceResult.sourceDelta(), sourceResult.botDelta(), sourceResult.destinationDelta())
                & report("destination_post_mismatch", destinationRequest, destinationPass, destinationResult.code(),
                TransferLedgerData.Location.UNKNOWN, destinationResult.sourceDelta(), destinationResult.botDelta(),
                destinationResult.destinationDelta());
    }

    private static boolean ledgerPolicies(ServerLevel level, BlockPos source, BlockPos destination) {
        TransferLedgerData ledger = new TransferLedgerData();
        TransferRequest request = request(level, source, destination, 1);
        boolean firstAdmission = ledger.admit(request);
        boolean duplicateRejected = !ledger.admit(request);
        ledger.transition(request.requestId(), TransferLedgerData.State.IN_TRANSIT_BOT,
                TransferLedgerData.Location.BOT_INVENTORY, "fixture", level.getGameTime(), "fixture", false);
        boolean replacementBlocked = ledger.blocksBot(request.botId());
        ledger.transition(request.requestId(), TransferLedgerData.State.SUSPENDED,
                TransferLedgerData.Location.BOT_INVENTORY, TransferCodes.TIMEOUT, level.getGameTime(), "timeout", true);
        TransferLedgerData.Entry timeout = ledger.find(request.requestId()).orElse(null);
        boolean timeoutSuspended = timeout != null && timeout.state() == TransferLedgerData.State.SUSPENDED
                && timeout.location() == TransferLedgerData.Location.BOT_INVENTORY && timeout.manualTakeoverRequired();
        ledger.suspendUnfinished(TransferCodes.SERVER_RESTART, level.getGameTime());
        TransferLedgerData.Entry restart = ledger.find(request.requestId()).orElse(null);
        boolean restartSuspended = restart != null && restart.state() == TransferLedgerData.State.SUSPENDED
                && restart.location() == TransferLedgerData.Location.BOT_INVENTORY && restart.manualTakeoverRequired();
        ledger.transition(request.requestId(), TransferLedgerData.State.ABORTED, TransferLedgerData.Location.BOT_INVENTORY,
                "aborted", level.getGameTime(), "abort:no_inventory_mutation", true);
        boolean abortTerminal = ledger.find(request.requestId()).map(entry -> entry.state() == TransferLedgerData.State.ABORTED
                && entry.location() == TransferLedgerData.Location.BOT_INVENTORY).orElse(false);
        boolean pass = firstAdmission && duplicateRejected && replacementBlocked && timeoutSuspended && restartSuspended && abortTerminal;
        return report("duplicate_timeout_restart_abort_replacement", request, pass,
                pass ? "aborted" : TransferCodes.UNKNOWN_DISCREPANCY, TransferLedgerData.Location.BOT_INVENTORY, 0, 0, 0);
    }

    private static boolean taskInterruptPolicies(ServerLevel level, BotPlayer bot, BlockPos source, BlockPos destination) {
        TransferLedgerData survivalLedger = new TransferLedgerData();
        TransferRequest survivalRequest = requestForBot(level, bot, source, destination, 1);
        survivalLedger.admit(survivalRequest);
        TransferTask survivalTask = new TransferTask(bot, survivalRequest, survivalLedger);
        survivalTask.survivalInterrupted(TransferCodes.SURVIVAL_LAVA_CONTACT);
        boolean survivalPass = survivalLedger.find(survivalRequest.requestId()).map(entry ->
                entry.state() == TransferLedgerData.State.SUSPENDED
                        && entry.location() == TransferLedgerData.Location.NOT_MOVED
                        && entry.manualTakeoverRequired()).orElse(false);

        TransferLedgerData removalLedger = new TransferLedgerData();
        TransferRequest removalRequest = requestForBot(level, bot, source, destination, 1);
        removalLedger.admit(removalRequest);
        TransferTask removalTask = new TransferTask(bot, removalRequest, removalLedger);
        removalTask.botRemoved();
        boolean removalPass = removalLedger.find(removalRequest.requestId()).map(entry ->
                entry.state() == TransferLedgerData.State.UNKNOWN_DISCREPANCY
                        && entry.location() == TransferLedgerData.Location.UNKNOWN
                        && entry.manualTakeoverRequired()).orElse(false);
        boolean pass = survivalPass && removalPass;
        return report("survival_interrupt_death_removal", removalRequest, pass,
                pass ? TransferCodes.UNKNOWN_DISCREPANCY : TransferCodes.MANUAL_TAKEOVER_REQUIRED,
                removalPass ? TransferLedgerData.Location.UNKNOWN : TransferLedgerData.Location.NOT_MOVED, 0, 0, 0);
    }

    private static TransferRequest requestForBot(ServerLevel level, BotPlayer bot, BlockPos source,
                                                BlockPos destination, int count) {
        ResourceLocation itemId = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(Items.IRON_INGOT);
        return new TransferRequest(UUID.randomUUID(), UUID.randomUUID(), bot.getUUID(),
                new ChestEndpointRef(level.dimension().location(), source),
                new ChestEndpointRef(level.dimension().location(), destination), itemId, count, level.getGameTime());
    }

    private static TransferRequest request(ServerLevel level, BlockPos source, BlockPos destination, int count) {
        ResourceLocation itemId = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(Items.IRON_INGOT);
        return new TransferRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new ChestEndpointRef(level.dimension().location(), source),
                new ChestEndpointRef(level.dimension().location(), destination), itemId, count, level.getGameTime());
    }

    private static ChestBlockEntity chest(ServerLevel level, BlockPos position) {
        return level.getBlockEntity(position) instanceof ChestBlockEntity chest ? chest : null;
    }

    private static void clear(Inventory inventory, ChestBlockEntity source, ChestBlockEntity destination) {
        for (int slot = 0; slot < InventoryObservation.BOT_ORDINARY_SLOT_COUNT; slot++) inventory.setItem(slot, ItemStack.EMPTY);
        for (int slot = 0; slot < source.getContainerSize(); slot++) {
            source.setItem(slot, ItemStack.EMPTY);
            destination.setItem(slot, ItemStack.EMPTY);
        }
        source.setChanged();
        destination.setChanged();
    }

    private static boolean report(String state, TransferRequest request, boolean pass, String code,
                                  TransferLedgerData.Location location, int source, int bot, int destination) {
        BotLog.info("TRANSFER_FIXTURE {} request={} state={} code={} location={} sourceDelta={} botDelta={} destinationDelta={}",
                pass ? "PASS" : "FAIL", request == null ? "none" : request.requestId(), state, code, location,
                source, bot, destination);
        return pass;
    }
}
