package com.dddgn.alice.pathing;

import com.dddgn.alice.protection.BlockBreakSafety;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 通道规划的保守硬验证。它不生成道路单元，也不执行施工；无法证明安全时拒绝候选。
 * 第一版拒绝流体、保护区、不可破坏方块和高代价清障，复杂液体处理交给显式任务。
 */
public final class TunnelObstaclePolicy {
    private TunnelObstaclePolicy() {
    }

    public static Validation validate(ServerLevel level, ServerPlayer bot, TunnelPlanner.Candidate candidate) {
        List<BlockPos> route = route(candidate);
        if (route.size() < 2) {
            return Validation.rejected("tunnel_geometry_too_short", 0, 0);
        }

        int clears = 0;
        int supports = 0;
        BlockPos previous = null;
        for (BlockPos foot : route) {
            if (previous != null && !isAdjacentSlope(previous, foot)) {
                return Validation.rejected("tunnel_geometry_discontinuous", clears, supports);
            }
            previous = foot;
            for (BlockPos clearance : List.of(foot, foot.above())) {
                BlockState state = level.getBlockState(clearance);
                if (!state.getFluidState().isEmpty()) {
                    return Validation.rejected("tunnel_fluid", clears, supports);
                }
                if (!state.isAir()) {
                    String refusal = BlockBreakSafety.clearingRefusal(bot, clearance);
                    if (refusal != null) {
                        return Validation.rejected("tunnel_" + refusal, clears, supports);
                    }
                    clears++;
                }
            }
            BlockState support = level.getBlockState(foot.below());
            if (!support.getFluidState().isEmpty()) {
                return Validation.rejected("tunnel_fluid", clears, supports);
            }
            if (support.getCollisionShape(level, foot.below()).isEmpty()) {
                supports++;
            }
        }
        return Validation.accepted(clears, supports);
    }

    private static List<BlockPos> route(TunnelPlanner.Candidate candidate) {
        List<BlockPos> route = new ArrayList<>();
        route.add(candidate.entrance());
        route.addAll(candidate.tunnelCurve());
        route.add(candidate.exit());
        return route;
    }

    private static boolean isAdjacentSlope(BlockPos from, BlockPos to) {
        int dx = Math.abs(to.getX() - from.getX());
        int dy = Math.abs(to.getY() - from.getY());
        int dz = Math.abs(to.getZ() - from.getZ());
        return dx <= 1 && dy <= 1 && dz <= 1 && (dx != 0 || dz != 0);
    }

    public record Validation(boolean valid, String reason, int clearCount, int supportCount) {
        private static Validation accepted(int clearCount, int supportCount) {
            return new Validation(true, "", clearCount, supportCount);
        }

        private static Validation rejected(String reason, int clearCount, int supportCount) {
            return new Validation(false, reason, clearCount, supportCount);
        }
    }
}
