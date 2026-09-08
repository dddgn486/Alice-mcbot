package com.dddgn.alice.pathing.core;

import java.util.Objects;
import java.util.Set;

/** MovementSpec 的静态能力声明；不授予具体世界修改权限。 */
public record MovementCapabilities(
        boolean changesWorld,
        Set<WorldMutationIntent> mutationIntents,
        boolean consumesResources,
        boolean requiresTool,
        IntrinsicReversibility intrinsicReversibility,
        RecoverabilityLevel requiredRecoverabilityLevel,
        int maxNaturalDrop,
        boolean canBreakBlocks,
        boolean canPlaceBlocks,
        boolean canEnterFluid,
        boolean requiresZoneAuthorization,
        boolean supportsMidExecutionRevalidation
) {
    public MovementCapabilities {
        mutationIntents = Set.copyOf(Objects.requireNonNull(mutationIntents, "mutationIntents"));
        intrinsicReversibility = Objects.requireNonNull(intrinsicReversibility, "intrinsicReversibility");
        requiredRecoverabilityLevel = Objects.requireNonNull(requiredRecoverabilityLevel,
                "requiredRecoverabilityLevel");
        if (maxNaturalDrop < 0) {
            throw new IllegalArgumentException("maxNaturalDrop must be non-negative");
        }
        if (!changesWorld && !mutationIntents.isEmpty()) {
            throw new IllegalArgumentException("non-mutating Movement cannot declare mutation intents");
        }
        if (!canBreakBlocks && mutationIntents.contains(WorldMutationIntent.PATH_ACCESS)) {
            throw new IllegalArgumentException("PATH_ACCESS requires canBreakBlocks");
        }
        if (!canPlaceBlocks && (mutationIntents.contains(WorldMutationIntent.TEMPORARY_SUPPORT)
                || mutationIntents.contains(WorldMutationIntent.DOMAIN_ACTION))) {
            throw new IllegalArgumentException("placement intent requires canPlaceBlocks");
        }
    }

    /** R5-3 临时支撑放置（TEMPORARY_SUPPORT）：消耗资源、改变世界、可回收。 */
    public static MovementCapabilities temporarySupport(RecoverabilityLevel level) {
        return new MovementCapabilities(true, Set.of(WorldMutationIntent.TEMPORARY_SUPPORT),
                true, false, IntrinsicReversibility.REVERSIBLE, level, 0,
                false, true, false, false, true);
    }

    public static MovementCapabilities pureTraversal(RecoverabilityLevel level,
                                                       IntrinsicReversibility reversibility) {
        return new MovementCapabilities(false, Set.of(), false, false, reversibility,
                level, 0, false, false, false, false, true);
    }
}
