package com.dddgn.alice.road;

import com.dddgn.alice.protection.BlockBreakSafety;
import com.dddgn.alice.protection.SafeZoneData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 道路蓝图统一禁区策略。
 * <p>实体禁区仅禁自身；所有流体（含模组 FluidState）禁自身加三维一格 clearance。
 * 后续道路禁区方块可经 #alice:road_forbidden 数据标签扩展，不把规则散在路线原语中。</p>
 */
public final class RoadObstaclePolicy {
    public static final TagKey<Block> ROAD_FORBIDDEN = TagKey.create(Registries.BLOCK,
            new ResourceLocation("alice", "road_forbidden"));

    private RoadObstaclePolicy() {}

    public static boolean forbidsCorridor(ServerLevel level, BlockPos support, int headroom) {
        for (int dy = 0; dy <= headroom; dy++) {
            BlockPos cell = support.above(dy);
            if (exactForbidden(level, cell)) return true;
            // 流体: 本体加三维一格膨胀；适用于原版与模组 FluidState。
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int fluidY = -1; fluidY <= 1; fluidY++) {
                        if (!level.getFluidState(cell.offset(dx, fluidY, dz)).isEmpty()) return true;
                    }
                }
            }
        }
        return false;
    }

    /** 安全区、负硬度、道路禁用标签、高代价清障块只禁本体，不向外扩张。 */
    public static boolean exactForbidden(ServerLevel level, BlockPos pos) {
        if (SafeZoneData.get(level.getServer()).protectionReason(level, pos) != null) return true;
        BlockState state = level.getBlockState(pos);
        return BlockBreakSafety.isUnbreakable(level, pos)
                || BlockBreakSafety.isExpensiveToClear(state)
                || state.is(ROAD_FORBIDDEN);
    }
}
