package com.dddgn.alice.transfer;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;

import java.util.Objects;

/** Immutable identity for a fixed original, unsided, single-chest endpoint. */
public record ChestEndpointRef(ResourceLocation dimensionId, BlockPos position) {
    public ChestEndpointRef {
        Objects.requireNonNull(dimensionId, "dimensionId");
        position = Objects.requireNonNull(position, "position").immutable();
    }

    public Validation validate(ServerLevel level) {
        if (!dimensionId.equals(level.dimension().location())) {
            return Validation.failure(TransferCodes.ENDPOINT_NOT_SINGLE_CHEST);
        }
        if (!level.hasChunkAt(position)) {
            return Validation.failure(TransferCodes.ENDPOINT_NOT_LOADED);
        }
        var state = level.getBlockState(position);
        if (!state.is(Blocks.CHEST) || state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            return Validation.failure(TransferCodes.ENDPOINT_NOT_SINGLE_CHEST);
        }
        BlockEntity blockEntity = level.getBlockEntity(position);
        if (!(blockEntity instanceof ChestBlockEntity chest)) {
            return Validation.failure(TransferCodes.ENDPOINT_NOT_SINGLE_CHEST);
        }
        return chest.getCapability(ForgeCapabilities.ITEM_HANDLER, null)
                .map(handler -> Validation.success(handler, state.getBlock().builtInRegistryHolder().key().location()))
                .orElseGet(() -> Validation.failure(TransferCodes.ENDPOINT_HANDLER_UNAVAILABLE));
    }

    public record Validation(String code, IItemHandler handler, ResourceLocation blockId) {
        private static Validation failure(String code) {
            return new Validation(code, null, null);
        }

        private static Validation success(IItemHandler handler, ResourceLocation blockId) {
            return new Validation(null, handler, blockId);
        }

        public boolean accepted() {
            return code == null;
        }
    }
}
