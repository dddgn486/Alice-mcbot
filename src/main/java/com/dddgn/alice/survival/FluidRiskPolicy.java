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
        // ⭐ `1.4z-a`（2026-09-26 真机裁定：「挖到水 ⇒ 停/换格，不挖穿」）。
        // 真机逐字：bot 挖穿一格 ⇒ 瀑布从上方灌进通道 ⇒ 水流持续把它推离站位 ⇒ `PathRetry` 反复
        // `REACHED → STALE`（`DESCEND_STALE_START`/`ASCEND_STALE_START`）⇒ 连续 401 tick 零推进
        // ⇒ 终态只能报 `no_progress`（`SUMMARY main=3/20 collected=0/32 ticks=1920 → FAIL`，整轮作废）。
        //
        // 口径（**只拦"会把水引进来的那一格"**）：
        //   ① 目标格**当前不是**流体（本来就是流体 ⇒ 那不是"挖穿"，交给既有的"已通"判定，不在这里拦 ——
        //      否则"全程泡在水里"的区域会一格都挖不了）；
        //   ② 目标格**上方**是水 ⇒ 挖掉后水直接落下来；
        //   ③ 目标格**水平四邻**任一格是水 ⇒ 水会横着漫进来。
        //   ⚠️ **不含 `below`**：水不会往上流（岩浆那条里的 `below` 是"掉进去"，是另一种危害）。
        if (!level.getFluidState(target).isEmpty()) {
            return null;
        }
        if (level.getFluidState(target.above()).is(FluidTags.WATER)) {
            return "fluid_risk_water";
        }
        for (BlockPos nearby : horizontalRiskCells(target)) {
            if (level.getFluidState(nearby).is(FluidTags.WATER)) {
                return "fluid_risk_water";
            }
        }
        return null;
    }

    /** 水只会**横着漫 / 往下落** ⇒ "会不会流进来"只看水平四邻（与 {@link #adjacentRiskCells} 的区别就在此）。 */
    private static Iterable<BlockPos> horizontalRiskCells(BlockPos target) {
        return java.util.List.of(target.north(), target.south(), target.east(), target.west());
    }

    private static Iterable<BlockPos> adjacentRiskCells(BlockPos target) {
        return java.util.List.of(
                target.north(), target.south(), target.east(), target.west(),
                target.above(), target.below());
    }
}
