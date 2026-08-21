package com.dddgn.alice.transfer;

import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.util.UUID;

/** Focused evidence for ephemeral selection drafts and read-only optional submit resolution. */
public final class TransferSelectionFixture {
    private TransferSelectionFixture() { }
    public static boolean run(ServerLevel level, BlockPos base) {
        BlockPos sourcePos = base, destinationPos = base.east(3);
        level.setBlock(sourcePos, Blocks.CHEST.defaultBlockState(), 3);
        level.setBlock(destinationPos, Blocks.CHEST.defaultBlockState(), 3);
        ChestBlockEntity source = chest(level, sourcePos), destination = chest(level, destinationPos);
        if (source == null || destination == null) return report("endpoint_setup", false, TransferCodes.ENDPOINT_NOT_SINGLE_CHEST, 0, 0, 0, 0);
        clear(source, destination);
        source.setItem(0, new ItemStack(Items.IRON_INGOT, 3)); source.setItem(1, new ItemStack(Items.DIAMOND, 2));
        UUID player = UUID.randomUUID();
        ChestEndpointRef sourceRef = new ChestEndpointRef(level.dimension().location(), sourcePos);
        ChestEndpointRef destinationRef = new ChestEndpointRef(level.dimension().location(), destinationPos);
        long tick = level.getGameTime();
        int sourceBefore = count(source), destinationBefore = count(destination), ledgerBefore = ledgerEntryCount(level);

        TransferSelectionData.Result destinationSelected = TransferSelectionData.select(level.getServer(), player, destinationRef, false, tick);
        TransferSelectionData.Result sourceSelected = TransferSelectionData.select(level.getServer(), player, sourceRef, true, tick + 1);
        boolean draftPass = destinationSelected.accepted() && sourceSelected.accepted()
                && TransferSelectionData.status(level.getServer(), player, tick + 1).map(d -> sourceRef.equals(d.source()) && destinationRef.equals(d.destination())).orElse(false);
        TransferSelectionData.Result same = TransferSelectionData.select(level.getServer(), player, sourceRef, false, tick + 2);
        ChestEndpointRef nether = new ChestEndpointRef(ResourceLocation.tryParse("minecraft:the_nether"), destinationPos);
        TransferSelectionData.Result cross = TransferSelectionData.select(level.getServer(), player, nether, false, tick + 2);
        boolean exactRejections = !same.accepted() && TransferCodes.SAME_ENDPOINT_REJECTED.equals(same.code())
                && !cross.accepted() && TransferCodes.CROSS_DIMENSION_REJECTED.equals(cross.code());
        boolean expired = TransferSelectionData.status(level.getServer(), player, tick + TransferSelectionData.EXPIRY_TICKS + 2).isEmpty();
        TransferSelectionData.select(level.getServer(), player, sourceRef, true, tick + 2);
        boolean cleared = TransferSelectionData.clear(level.getServer(), player) && TransferSelectionData.status(level.getServer(), player, tick + 2).isEmpty();
        TransferSelectionData.select(level.getServer(), player, sourceRef, true, tick + 3);
        TransferSelectionData.clearServer(level.getServer());
        boolean restartCleared = TransferSelectionData.status(level.getServer(), player, tick + 3).isEmpty()
                && TransferSelectionData.fixtureEntryCount(level.getServer()) == 0;

        int sourceAfterDraft = count(source), destinationAfterDraft = count(destination), ledgerAfterDraft = ledgerEntryCount(level);
        boolean noDraftWrites = sourceBefore == sourceAfterDraft && destinationBefore == destinationAfterDraft && ledgerBefore == ledgerAfterDraft;
        TransferSelectionSubmission.Resolution defaults = TransferSelectionSubmission.resolve(level, sourceRef, null, null);
        boolean defaultPass = defaults.accepted() && itemId(Items.IRON_INGOT).equals(defaults.itemId()) && defaults.count() == 3;
        TransferSelectionSubmission.Resolution explicit = TransferSelectionSubmission.resolve(level, sourceRef, itemId(Items.DIAMOND), 1);
        boolean explicitPass = explicit.accepted() && explicit.count() == 1;
        boolean modRejected = TransferCodes.INVALID_ITEM_ID.equals(TransferSelectionSubmission.resolve(level, sourceRef,
                ResourceLocation.tryParse("alice:transfer_endpoint_selector"), 1).code());
        clear(source, destination);
        ItemStack tagged = new ItemStack(Items.IRON_INGOT, 1); tagged.getOrCreateTag().putString("fixture", "unsupported"); source.setItem(0, tagged);
        boolean unavailable = TransferCodes.DEFAULT_ITEM_UNAVAILABLE.equals(TransferSelectionSubmission.resolve(level, sourceRef, null, null).code());
        boolean endpointRejected = !new ChestEndpointRef(level.dimension().location(), base.west()).validate(level).accepted();
        final TransferRequest[] captured = new TransferRequest[1];
        TransferSelectionSubmission.Submission admitted = TransferSelectionSubmission.submit(player, UUID.randomUUID(), sourceRef,
                destinationRef, explicit, tick + 4, request -> { captured[0] = request; return "accepted"; });
        boolean admissionPass = admitted.accepted() && captured[0] != null && captured[0].source().equals(sourceRef)
                && captured[0].destination().equals(destinationRef) && captured[0].itemId().equals(itemId(Items.DIAMOND))
                && captured[0].count() == 1;
        TransferSelectionData.select(level.getServer(), player, destinationRef, false, tick + 5);
        TransferSelectionData.select(level.getServer(), player, sourceRef, true, tick + 6);
        TransferSelectionSubmission.Submission rejectedAdmission = TransferSelectionSubmission.submit(player, UUID.randomUUID(), sourceRef,
                destinationRef, explicit, tick + 7, request -> TransferCodes.BOT_UNAVAILABLE);
        boolean failureRetainsDraft = !rejectedAdmission.accepted()
                && TransferSelectionData.status(level.getServer(), player, tick + 7).isPresent();
        if (admitted.accepted()) TransferSelectionData.clear(level.getServer(), player);
        boolean successClearsDraft = TransferSelectionData.status(level.getServer(), player, tick + 7).isEmpty();
        boolean pass = draftPass && exactRejections && expired && cleared && restartCleared && defaultPass && explicitPass
                && modRejected && unavailable && endpointRejected && noDraftWrites && admissionPass && failureRetainsDraft && successClearsDraft;
        return report("draft_selection_default_resolution", pass, pass ? "accepted" : TransferCodes.UNKNOWN_DISCREPANCY,
                sourceAfterDraft - sourceBefore, 0, destinationAfterDraft - destinationBefore, ledgerAfterDraft - ledgerBefore);
    }
    private static ResourceLocation itemId(net.minecraft.world.item.Item item) { return net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item); }
    private static int ledgerEntryCount(ServerLevel level) { return TransferLedgerData.get(level.getServer()).save(new net.minecraft.nbt.CompoundTag()).getList("entries", net.minecraft.nbt.Tag.TAG_COMPOUND).size(); }
    private static int count(ChestBlockEntity chest) { int total = 0; for (int slot = 0; slot < chest.getContainerSize(); slot++) total += chest.getItem(slot).getCount(); return total; }
    private static ChestBlockEntity chest(ServerLevel level, BlockPos pos) { return level.getBlockEntity(pos) instanceof ChestBlockEntity chest ? chest : null; }
    private static void clear(ChestBlockEntity source, ChestBlockEntity destination) { for (int slot = 0; slot < source.getContainerSize(); slot++) { source.setItem(slot, ItemStack.EMPTY); destination.setItem(slot, ItemStack.EMPTY); } source.setChanged(); destination.setChanged(); }
    private static boolean report(String state, boolean pass, String code, int source, int bot, int destination, int ledgerWrites) {
        BotLog.info("TRANSFER_SELECTION_FIXTURE {} request=none state={} code={} location=draft sourceDelta={} botDelta={} destinationDelta={} ledgerWrites={}", pass ? "PASS" : "FAIL", state, code, source, bot, destination, ledgerWrites);
        return pass;
    }
}
