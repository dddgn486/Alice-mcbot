package com.dddgn.alice.survival;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;

/** 挖掘前的保守流体风险检查；只拒绝可预见的岩浆，不自动堵水或挖源头。 */
public final class FluidRiskPolicy {
    private FluidRiskPolicy() {
    }

    public static String miningRefusal(ServerPlayer bot, BlockPos target) {
        ServerLevel level = (ServerLevel) bot.level();
        if (level.getFluidState(target).is(FluidTags.LAVA)) {
            return "fluid_risk_lava";
        }
        for (BlockPos nearby : adjacentRiskCells(target)) {
            if (level.getFluidState(nearby).is(FluidTags.LAVA)) {
                return "fluid_risk_lava";
            }
        }
        return null;
    }

    private static Iterable<BlockPos> adjacentRiskCells(BlockPos target) {
        return java.util.List.of(
                target.north(), target.south(), target.east(), target.west(),
                target.above(), target.below());
    }
}
