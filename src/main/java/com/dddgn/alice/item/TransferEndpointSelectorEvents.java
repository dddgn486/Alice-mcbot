package com.dddgn.alice.item;

import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.transfer.ChestEndpointRef;
import com.dddgn.alice.transfer.TransferCodes;
import com.dddgn.alice.transfer.TransferSelectionData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;

/** Records selector endpoints before blocks consume right-click, without changing vanilla interaction. */
@Mod.EventBusSubscriber(modid = "alice", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TransferEndpointSelectorEvents {
    private TransferEndpointSelectorEvents() { }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getSide() != LogicalSide.SERVER) return;
        ItemStack stack = event.getEntity().getItemInHand(event.getHand());
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ServerPlayer player)) return;
        dispatch(event.getSide() == LogicalSide.SERVER, stack.is(AliceItems.TRANSFER_ENDPOINT_SELECTOR.get()),
                event.getEntity().isSecondaryUseActive(), (source) -> record(player, level, event.getPos(), source));
        // Deliberately do not cancel or set an interaction result: vanilla opens normal chests.
    }

    @FunctionalInterface
    interface Recorder { String record(boolean source); }

    static String dispatch(boolean serverSide, boolean selectorHand, boolean secondaryUse, Recorder recorder) {
        if (!serverSide || !selectorHand) return null;
        return recorder.record(secondaryUse);
    }

    static String roleFor(boolean secondaryUse) { return secondaryUse ? "source" : "destination"; }
    static boolean preservesVanillaInteraction() { return true; }
    static String decision(boolean permission, boolean validEndpoint, boolean selected) {
        if (!permission) return TransferCodes.UNAUTHORIZED_ACTOR;
        if (!validEndpoint) return TransferCodes.ENDPOINT_NOT_SINGLE_CHEST;
        return selected ? "accepted" : TransferCodes.ENDPOINT_NOT_SINGLE_CHEST;
    }

    static String record(ServerPlayer player, ServerLevel level, BlockPos pos, boolean source) {
        String role = roleFor(source);
        if (!player.hasPermissions(2)) {
            feedback(player, TransferCodes.UNAUTHORIZED_ACTOR);
            return TransferCodes.UNAUTHORIZED_ACTOR;
        }
        ChestEndpointRef endpoint = new ChestEndpointRef(level.dimension().location(), pos);
        ChestEndpointRef.Validation validation = endpoint.validate(level);
        if (!validation.accepted()) {
            feedback(player, validation.code());
            log(player, role, endpoint, validation.code());
            return validation.code();
        }
        TransferSelectionData.Result result = TransferSelectionData.select(level.getServer(), player.getUUID(), endpoint, source, level.getGameTime());
        feedback(player, result.code());
        log(player, role, endpoint, result.code());
        return result.code();
    }

    private static void feedback(ServerPlayer player, String code) {
        player.sendSystemMessage(Component.literal("[alice] transfer selection code=" + code));
    }
    private static void log(ServerPlayer player, String role, ChestEndpointRef endpoint, String code) {
        BotLog.info("transfer_selection: player={} role={} pos=<{},{},{},{}> code={}", player.getName().getString(), role,
                endpoint.dimensionId(), endpoint.position().getX(), endpoint.position().getY(), endpoint.position().getZ(), code);
    }
}
