package com.dddgn.alice.survival;

import net.minecraft.core.BlockPos;

/** 单 tick 的不可变危险快照；不负责决定逃生路线。 */
public record HazardState(
        HazardType type,
        int durationTicks,
        int airSupply,
        float health,
        float previousHealth,
        BlockPos position) {

    public HazardState {
        position = position.immutable();
    }

    public boolean dangerous() {
        return type == HazardType.LAVA_CONTACT || type == HazardType.SUFFOCATING;
    }
}
