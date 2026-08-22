package com.dddgn.alice.item;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

/** Test-only endpoint selector; endpoint recording is handled before block interaction consumption. */
public final class TransferEndpointSelector extends Item {
    public TransferEndpointSelector(Properties properties) { super(properties); }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        // RightClickBlock records exactly once; PASS preserves vanilla block interaction.
        return InteractionResult.PASS;
    }
}
