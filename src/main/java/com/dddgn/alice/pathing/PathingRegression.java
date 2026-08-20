package com.dddgn.alice.pathing;

import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/** Headless-only deterministic checks for the explicitly documented surface cost model. */
public final class PathingRegression {
    private PathingRegression() {
    }

    public static boolean run(ServerLevel level, BlockPos origin) {
        clearAndFloor(level, origin, 0, 0);
        boolean diagonal = assertPath(level, "diagonal", origin, origin.offset(2, 0, 2), 2.0D, 2);

        clearAndFloor(level, origin, 4, 0);
        BlockPos ascendStart = origin.offset(4, 0, 0);
        BlockPos ascendGoal = ascendStart.offset(1, 1, 0);
        level.setBlock(ascendGoal.below(), Blocks.DIRT.defaultBlockState(), 3);
        boolean ascend = assertPath(level, "ascend", ascendStart, ascendGoal, 2.0D, 1);

        clearAndFloor(level, origin, 8, 0);
        BlockPos descendGoal = origin.offset(9, 0, 0);
        BlockPos descendStart = origin.offset(8, 1, 0);
        level.setBlock(descendStart.below(), Blocks.DIRT.defaultBlockState(), 3);
        boolean descend = assertPath(level, "descend", descendStart, descendGoal, 1.0D, 1);

        // The direct low route is blocked. The only short alternative is one ASCEND then DESCEND (3),
        // while the fenced lower detour costs at least four horizontal moves.
        clearAndFloor(level, origin, 0, 6);
        BlockPos start = origin.offset(0, 0, 6);
        BlockPos goal = start.offset(2, 0, 0);
        level.setBlock(start.offset(1, 0, 0), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(start.offset(1, 0, 1), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(start.offset(1, 0, -1), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(start.offset(0, 0, 1), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(start.offset(0, 0, -1), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(start.offset(1, 1, 0).below(), Blocks.DIRT.defaultBlockState(), 3);
        boolean elevatedAlternative = assertPath(level, "elevated_alternative", start, goal, 3.0D, 2);

        boolean passed = diagonal && ascend && descend && elevatedAlternative;
        BotLog.info("PATHING_REGRESSION {} diagonal={} ascend={} descend={} elevatedAlternative={}",
                passed ? "PASS" : "FAIL", diagonal, ascend, descend, elevatedAlternative);
        return passed;
    }

    private static boolean assertPath(ServerLevel level, String name, BlockPos start, BlockPos goal,
                                      double expectedCost, int expectedSegments) {
        SurfacePathfinder.Result result = SurfacePathfinder.find(level, start, goal);
        boolean valid = result.reachable()
                && Math.abs(result.totalCost() - expectedCost) < 0.0001D
                && result.path().size() == expectedSegments
                && result.goal().equals(goal);
        BotLog.info("PATHING_REGRESSION {} {} cost={} expected={} segments={} expectedSegments={} path={}",
                valid ? "PASS" : "FAIL", name, result.totalCost(), expectedCost,
                result.path().size(), expectedSegments, result.path());
        return valid;
    }

    private static void clearAndFloor(ServerLevel level, BlockPos origin, int offsetX, int offsetZ) {
        BlockPos base = origin.offset(offsetX, 0, offsetZ);
        for (int dx = -3; dx <= 5; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = 0; dy <= 3; dy++) {
                    level.setBlock(base.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
                }
                level.setBlock(base.offset(dx, -1, dz), Blocks.DIRT.defaultBlockState(), 3);
            }
        }
    }
}
