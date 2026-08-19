package com.dddgn.alice.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * 曲面寻路门面：只委托真实可通行曲面的 A*，不破坏或放置方块。
 *
 * <p>把“无路”保留为结构化结果，调用方可以明确决定是否进入未来的通道规划，
 * 而不是把空列表和各种失败原因混在一起。</p>
 */
public final class SurfacePathfinder {
    private SurfacePathfinder() {
    }

    public static Result find(ServerLevel level, BlockPos start, BlockPos goal) {
        if (start.equals(goal)) {
            return Result.reached(start, List.of());
        }
        List<BlockPos> path = AStarPathfinder.computePath(level, start, new Goal.GoalBlock(goal));
        if (path.isEmpty()) {
            return Result.unreachable(start, goal);
        }
        return Result.reached(goal, path);
    }

    /** 按给定顺序选择第一个存在曲面路径的合法站位。 */
    public static CandidateResult findFirst(ServerLevel level, BlockPos start, List<BlockPos> goals) {
        for (BlockPos goal : goals) {
            Result result = find(level, start, goal);
            if (result.reachable()) {
                return new CandidateResult(result, goals.indexOf(goal));
            }
        }
        return new CandidateResult(Result.unreachable(start, goals.isEmpty() ? start : goals.get(0)), -1);
    }

    public record Result(boolean reachable, BlockPos goal, List<BlockPos> path) {
        public Result {
            goal = goal.immutable();
            path = List.copyOf(path);
        }

        private static Result reached(BlockPos goal, List<BlockPos> path) {
            return new Result(true, goal, path);
        }

        private static Result unreachable(BlockPos start, BlockPos goal) {
            return new Result(false, goal, List.of());
        }
    }

    public record CandidateResult(Result result, int goalIndex) {
    }
}
