package com.dddgn.alice.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * 独立通道规划器。第一版只负责连接明确的曲面点，不参与普通 MineTask。
 * 调用方必须证明所有合法挖掘站位的曲面路径均失败，才允许进入本规划器。
 */
public final class TunnelPlanner {
    private TunnelPlanner() {
    }

    public static Result plan(ServerLevel level, BlockPos start, List<BlockPos> miningStands,
                              List<Candidate> candidates, boolean allSurfaceStandsFailed) {
        if (!allSurfaceStandsFailed) {
            return Result.rejected("surface_path_available");
        }
        if (miningStands.isEmpty() || candidates.isEmpty()) {
            return Result.rejected("no_tunnel_plan");
        }

        TunnelPlan best = null;
        for (Candidate candidate : candidates) {
            if (candidate.entrance().equals(candidate.exit())) {
                continue;
            }
            SurfacePathfinder.Result toEntrance = SurfacePathfinder.find(level, start, candidate.entrance());
            if (!toEntrance.reachable()) {
                continue;
            }
            for (BlockPos stand : miningStands) {
                SurfacePathfinder.Result fromExit = SurfacePathfinder.find(level, candidate.exit(), stand);
                if (!fromExit.reachable()) {
                    continue;
                }
                double tunnelLength = TunnelPlan.geometryLength(
                        candidate.entrance(), candidate.tunnelCurve(), candidate.exit());
                double cost = toEntrance.path().size()
                        + tunnelLength * TunnelPlan.TUNNEL_FACTOR
                        + fromExit.path().size();
                TunnelPlan plan = new TunnelPlan(toEntrance.path(), candidate.entrance(),
                        candidate.tunnelCurve(), candidate.exit(), fromExit.path(), cost);
                if (best == null || plan.cost() < best.cost()) {
                    best = plan;
                }
            }
        }
        return best == null ? Result.rejected("no_tunnel_plan") : Result.accepted(best);
    }

    public record Candidate(BlockPos entrance, List<BlockPos> tunnelCurve, BlockPos exit) {
        public Candidate {
            entrance = entrance.immutable();
            tunnelCurve = tunnelCurve.stream().map(BlockPos::immutable).toList();
            exit = exit.immutable();
        }
    }

    public record Result(TunnelPlan plan, String failureReason) {
        public boolean planned() {
            return plan != null;
        }

        private static Result accepted(TunnelPlan plan) {
            return new Result(plan, "");
        }

        private static Result rejected(String reason) {
            return new Result(null, reason);
        }
    }
}
