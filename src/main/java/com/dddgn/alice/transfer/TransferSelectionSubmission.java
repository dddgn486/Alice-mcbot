package com.dddgn.alice.transfer;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.UUID;

/** Read-only resolution of optional selector-submit arguments. */
public final class TransferSelectionSubmission {
    @FunctionalInterface
    public interface Admission { String admit(TransferRequest request); }
    private TransferSelectionSubmission() { }

    /** Shared submit construction path. Production supplies BotManager.assignTransfer as the admission delegate. */
    public static Submission submit(UUID actorId, UUID botId, ChestEndpointRef source, ChestEndpointRef destination,
                             Resolution resolution, long tick, Admission admission) {
        if (!resolution.accepted()) return Submission.failure(resolution.code());
        try {
            TransferRequest request = new TransferRequest(UUID.randomUUID(), actorId, botId, source, destination,
                    resolution.itemId(), resolution.count(), tick);
            return Submission.of(request, admission.admit(request));
        } catch (IllegalArgumentException exception) { return Submission.failure(exception.getMessage()); }
    }

    public static Resolution resolve(ServerLevel level, ChestEndpointRef source, ResourceLocation explicitItem,
                                     Integer explicitCount) {
        ChestEndpointRef.Validation validation = source.validate(level);
        if (!validation.accepted()) return Resolution.failure(validation.code());
        ResourceLocation itemId = explicitItem;
        if (itemId == null) {
            for (int slot = 0; slot < validation.handler().getSlots(); slot++) {
                ItemStack stack = validation.handler().getStackInSlot(slot);
                ResourceLocation candidate = stack.isEmpty() ? null : ForgeRegistries.ITEMS.getKey(stack.getItem());
                if (candidate != null && "minecraft".equals(candidate.getNamespace()) && !stack.hasTag()) {
                    itemId = candidate;
                    break;
                }
            }
            if (itemId == null) return Resolution.failure(TransferCodes.DEFAULT_ITEM_UNAVAILABLE);
        }
        if (!"minecraft".equals(itemId.getNamespace()) || ForgeRegistries.ITEMS.getValue(itemId) == null) {
            return Resolution.failure(TransferCodes.INVALID_ITEM_ID);
        }
        int count = explicitCount == null ? countDefault(validation, itemId) : explicitCount;
        if (count <= 0) return Resolution.failure(explicitCount == null
                ? TransferCodes.DEFAULT_ITEM_UNAVAILABLE : TransferCodes.INVALID_COUNT);
        return Resolution.accepted(itemId, count);
    }

    private static int countDefault(ChestEndpointRef.Validation validation, ResourceLocation itemId) {
        int count = 0;
        for (int slot = 0; slot < validation.handler().getSlots(); slot++) {
            ItemStack stack = validation.handler().getStackInSlot(slot);
            if (!stack.isEmpty() && itemId.equals(ForgeRegistries.ITEMS.getKey(stack.getItem())) && !stack.hasTag()) {
                count = Math.addExact(count, stack.getCount());
            }
        }
        return count;
    }

    public record Resolution(boolean accepted, String code, ResourceLocation itemId, int count) {
        private static Resolution accepted(ResourceLocation itemId, int count) { return new Resolution(true, "accepted", itemId, count); }
        private static Resolution failure(String code) { return new Resolution(false, code, null, 0); }
    }
    public record Submission(TransferRequest request, String code) {
        private static Submission of(TransferRequest request, String code) { return new Submission(request, code); }
        private static Submission failure(String code) { return new Submission(null, code); }
        public boolean accepted() { return "accepted".equals(code); }
    }
}
