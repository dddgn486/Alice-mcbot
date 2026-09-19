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
            ResourceLocation.fromNamespaceAndPath("alice", "road_forbidden"));

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

    /**
     * 安全区、负硬度、道路禁用标签、高代价清障块只禁本体，不向外扩张。
     *
     * <p>⚠️ **这里读的是裸 `protectionReason`，是有意的，不是漏接权限阶梯**（2026-09-19 用户裁定，见 `D-343`）：
     * 本方法是**规划期的规避启发式** —— 让路线**绕开**保护格，方向是**收紧**（保守），它**不做授权决策**。
     * 真正的**写入闸门**在动作层，且**没有绕过**：{@code RoadBuilder.buildUnit} → {@code BlockInteraction.placeBulkEdit}
     * （在 {@code setBlock} 之前调 {@code ZoneAuthority.regionRefusal(..., Act.PLACE)}）/ {@code breakForBulkEdit}
     * （{@code breakRefusal} → {@code BlockBreakSafety.refusal} → 同一个 {@code regionRefusal}）。
     * ⇒ 两边**结论一致**（保护区里都拒），这里只是**先**拒（更便宜）。
     * 若把它"接上阶梯"，方向是**放松**（路线可穿过被任务区覆盖的保护格），还会造出
     * "规划通过、逐块写入被拒"的**半成品路** ⇒ 故**保持零改动**。
     * 该不变量的可执行断言 = {@code tools/kernel-predicates.py: rule_bulk_write_zone_gate}。
     */
    public static boolean exactForbidden(ServerLevel level, BlockPos pos) {
        if (SafeZoneData.get(level.getServer()).protectionReason(level, pos) != null) return true;
        BlockState state = level.getBlockState(pos);
        return BlockBreakSafety.isUnbreakable(level, pos)
                || BlockBreakSafety.isExpensiveToClear(state)
                || state.is(ROAD_FORBIDDEN);
    }
}
