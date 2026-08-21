package com.dddgn.alice.item;

import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.transfer.ChestEndpointRef;
import com.dddgn.alice.transfer.TransferCodes;
import com.dddgn.alice.transfer.TransferSelectionData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/** Test-only endpoint selector. It records no inventory, ledger, NBT, or task state. */
public final class TransferEndpointSelector extends Item {
    public TransferEndpointSelector(Properties properties) { super(properties); }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) return InteractionResult.PASS;
        if (!(context.getPlayer() instanceof ServerPlayer player) || !player.hasPermissions(2)) {
            send(context, TransferCodes.UNAUTHORIZED_ACTOR);
            return InteractionResult.PASS;
        }
        ServerLevel serverLevel = (ServerLevel) level;
        ChestEndpointRef endpoint = new ChestEndpointRef(serverLevel.dimension().location(), context.getClickedPos());
        ChestEndpointRef.Validation validation = endpoint.validate(serverLevel);
        if (!validation.accepted()) {
            send(context, validation.code());
            log(player, context.isSecondaryUseActive() ? "source" : "destination", endpoint, validation.code());
            return InteractionResult.PASS;
        }
        boolean source = context.isSecondaryUseActive();
        TransferSelectionData.Result result = TransferSelectionData.select(serverLevel.getServer(), player.getUUID(), endpoint, source, serverLevel.getGameTime());
        send(context, result.code());
        log(player, source ? "source" : "destination", endpoint, result.code());
        return InteractionResult.PASS;
    }

    private static void send(UseOnContext context, String code) {
        if (context.getPlayer() != null) context.getPlayer().sendSystemMessage(Component.literal("[alice] transfer selection code=" + code));
    }
    private static void log(ServerPlayer player, String role, ChestEndpointRef endpoint, String code) {
        BotLog.info("transfer_selection: player={} role={} pos=<{},{},{},{}> code={}", player.getName().getString(), role,
                endpoint.dimensionId(), endpoint.position().getX(), endpoint.position().getY(), endpoint.position().getZ(), code);
    }
}
