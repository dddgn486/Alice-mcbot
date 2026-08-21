package com.dddgn.alice.transfer;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/** Immutable, single-batch transfer input. Item registry availability is checked by admission code. */
public record TransferRequest(UUID requestId, UUID actorId, UUID botId, ChestEndpointRef source,
                              ChestEndpointRef destination, ResourceLocation itemId, int count,
                              long createdServerTick) {
    public TransferRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(itemId, "itemId");
        if (count <= 0) {
            throw new IllegalArgumentException(TransferCodes.INVALID_COUNT);
        }
        if (!source.dimensionId().equals(destination.dimensionId())) {
            throw new IllegalArgumentException("cross_dimension_rejected");
        }
        if (source.equals(destination)) {
            throw new IllegalArgumentException(TransferCodes.SAME_ENDPOINT_REJECTED);
        }
    }
}
