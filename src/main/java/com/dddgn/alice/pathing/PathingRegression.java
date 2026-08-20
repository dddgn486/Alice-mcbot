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

        clearAndFloor(level, origin, 0, 12);
        BlockPos diagonalStart = origin.offset(0, 0, 12);
        BlockPos diagonalGoal = diagonalStart.offset(1, 0, 1);
        level.setBlock(diagonalStart.offset(1, 0, 0), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(diagonalStart.offset(1, 1, 0), Blocks.STONE.defaultBlockState(), 3);
        boolean diagonalBlocked = !MovementHelper.canTraverse(level, diagonalStart, diagonalGoal);
        BotLog.info("PATHING_REGRESSION {} diagonal_side_blocked={} from={} to={}",
                diagonalBlocked ? "PASS" : "FAIL", diagonalBlocked,
                diagonalStart, diagonalGoal);

        clearAndFloor(level, origin, 4, 12);
        BlockPos sweepStart = origin.offset(4, 1, 12);
        BlockPos sweepGoal = sweepStart.offset(1, -1, 1);
        level.setBlock(sweepStart.below(), Blocks.DIRT.defaultBlockState(), 3);
        level.setBlock(sweepGoal.below(), Blocks.DIRT.defaultBlockState(), 3);
        level.setBlock(sweepStart.offset(1, 0, 0), Blocks.STONE.defaultBlockState(), 3);
        boolean descentSweepBlocked = !MovementHelper.canDescend(level, sweepStart, sweepGoal);
        BotLog.info("PATHING_REGRESSION {} descent_sweep_blocked={} from={} to={}",
                descentSweepBlocked ? "PASS" : "FAIL", descentSweepBlocked,
                sweepStart, sweepGoal);

        clearAndFloor(level, origin, 0, 18);
        BlockPos tieStart = origin.offset(0, 0, 18);
        BlockPos tieGoal = tieStart.offset(3, 0, 0);
        SurfacePathfinder.Result tie = SurfacePathfinder.find(level, tieStart, tieGoal);
        boolean straightTie = tie.reachable() && Math.abs(tie.totalCost() - 3.0D) < 0.0001D
                && tie.path().stream().allMatch(pos -> pos.getZ() == tieStart.getZ());
        BotLog.info("PATHING_REGRESSION {} straight_tie cost={} path={}",
                straightTie ? "PASS" : "FAIL", tie.totalCost(), tie.path());

        clearAndFloor(level, origin, 4, 24);
        BlockPos edgeStart = origin.offset(4, 1, 24);
        BlockPos edgeGoal = edgeStart.offset(1, -1, 0);
        level.setBlock(edgeStart.below(), Blocks.DIRT.defaultBlockState(), 3);
        level.setBlock(edgeGoal.below(), Blocks.DIRT.defaultBlockState(), 3);
        boolean edgeMid = MovementHelper.canWalkThrough(level, new BlockPos(edgeGoal.getX(), edgeStart.getY(), edgeGoal.getZ()))
                && MovementHelper.canWalkThrough(level, new BlockPos(edgeGoal.getX(), edgeStart.getY() + 1, edgeGoal.getZ()));
        boolean edgeTarget = MovementHelper.canWalkThrough(level, edgeGoal) && MovementHelper.canWalkOn(level, edgeGoal);
        boolean edgeSweep = MovementHelper.canSweepPlayer(level, edgeStart, edgeGoal);
        boolean fullBlockEdge = edgeMid && edgeTarget && edgeSweep
                && nearly(MovementHelper.supportTopY(level, edgeGoal.below()), edgeGoal.getY());
        BotLog.info("PATHING_REGRESSION {} full_block_edge from={} to={} mid={} target={} sweep={} supportTopY={}",
                fullBlockEdge ? "PASS" : "FAIL", edgeStart, edgeGoal, edgeMid, edgeTarget, edgeSweep,
                MovementHelper.supportTopY(level, edgeGoal.below()));

        clearAndFloor(level, origin, 8, 24);
        BlockPos slabStart = origin.offset(8, 1, 24);
        BlockPos slabGoal = slabStart.offset(1, -1, 0);
        level.setBlock(slabStart.below(), Blocks.DIRT.defaultBlockState(), 3);
        level.setBlock(slabGoal.below(), Blocks.SMOOTH_STONE_SLAB.defaultBlockState(), 3);
        double slabTop = MovementHelper.supportTopY(level, slabGoal.below());
        boolean halfSlab = MovementHelper.canDescend(level, slabStart, slabGoal)
                && nearly(slabTop, slabGoal.getY() - 0.5D);
        BotLog.info("PATHING_REGRESSION {} lower_half_slab from={} to={} supportTopY={}",
                halfSlab ? "PASS" : "FAIL", slabStart, slabGoal, slabTop);

        level.setBlock(slabGoal.below(), Blocks.AIR.defaultBlockState(), 3);
        boolean unsupportedLanding = !MovementHelper.canDescend(level, slabStart, slabGoal);
        BotLog.info("PATHING_REGRESSION {} unsupported_landing from={} to={}",
                unsupportedLanding ? "PASS" : "FAIL", slabStart, slabGoal);

        boolean passed = diagonal && ascend && descend && elevatedAlternative
                && diagonalBlocked && descentSweepBlocked && straightTie
                && fullBlockEdge && halfSlab && unsupportedLanding;
        BotLog.info("PATHING_REGRESSION {} diagonal={} ascend={} descend={} elevatedAlternative={}"
                + " diagonalSideBlocked={} descentSweepBlocked={} straightTie={} fullBlockEdge={}"
                + " lowerHalfSlab={} unsupportedLanding={}",
                passed ? "PASS" : "FAIL", diagonal, ascend, descend, elevatedAlternative,
                diagonalBlocked, descentSweepBlocked, straightTie, fullBlockEdge, halfSlab, unsupportedLanding);
        return passed;
    }

    private static boolean nearly(double actual, double expected) {
        return !Double.isNaN(actual) && Math.abs(actual - expected) < 0.0001D;
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
