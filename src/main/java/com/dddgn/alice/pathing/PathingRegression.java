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

        boolean footCellRule = assertFootCellRule(level, origin);

        boolean passed = diagonal && ascend && descend && elevatedAlternative
                && diagonalBlocked && descentSweepBlocked && straightTie
                && fullBlockEdge && halfSlab && unsupportedLanding && footCellRule;
        BotLog.info("PATHING_REGRESSION {} diagonal={} ascend={} descend={} elevatedAlternative={}"
                + " diagonalSideBlocked={} descentSweepBlocked={} straightTie={} fullBlockEdge={}"
                + " lowerHalfSlab={} unsupportedLanding={} footCellRule={}",
                passed ? "PASS" : "FAIL", diagonal, ascend, descend, elevatedAlternative,
                diagonalBlocked, descentSweepBlocked, straightTie, fullBlockEdge, halfSlab,
                unsupportedLanding, footCellRule);
        return passed;
    }

    /**
     * 运行期脚位格规则（D-105，无头断言，不依赖 bot 物理）。
     *
     * <p>不变式：**`footCell` 给出的格必须是规划层 {@link MovementHelper#canWalkOn} 认可的格**
     * ——支撑是整格时二者天然一致；支撑不满一格时（箱子/灵魂沙 0.875、底半砖 0.5）原版
     * `blockPosition()` 会把脚留在支撑自己那一格，与规划层差整整一格，完成契约因此死锁。
     *
     * <p>公开给 `PathingRegressionTask` 作为夹具级断言（自建区域，与场景不重叠）。
     */
    public static boolean assertFootCellRule(ServerLevel level, BlockPos origin) {
        clearAndFloor(level, origin, 12, 12);
        BlockPos base = origin.offset(12, 0, 12);
        BlockPos chest = base;
        BlockPos slab = base.offset(1, 0, 0);
        BlockPos soulSand = base.offset(2, 0, 0);
        BlockPos carpet = base.offset(3, 0, 0);
        BlockPos full = base.offset(4, 0, 0);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        level.setBlock(slab, Blocks.SMOOTH_STONE_SLAB.defaultBlockState(), 3);
        level.setBlock(soulSand, Blocks.SOUL_SAND.defaultBlockState(), 3);
        level.setBlock(carpet, Blocks.WHITE_CARPET.defaultBlockState(), 3);
        level.setBlock(full, Blocks.DIRT.defaultBlockState(), 3);

        boolean chestOk = MovementHelper.footCell(level, chest.getX() + 0.5D, chest.getY() + 0.875D,
                chest.getZ() + 0.5D).equals(chest.above())
                && MovementHelper.canWalkOn(level, chest.above());
        boolean slabOk = MovementHelper.footCell(level, slab.getX() + 0.5D, slab.getY() + 0.5D,
                slab.getZ() + 0.5D).equals(slab.above())
                && MovementHelper.canWalkOn(level, slab.above());
        boolean soulSandOk = MovementHelper.footCell(level, soulSand.getX() + 0.5D,
                soulSand.getY() + 0.875D, soulSand.getZ() + 0.5D).equals(soulSand.above())
                && MovementHelper.canWalkOn(level, soulSand.above());
        // 薄面（地毯 0.0625）与整格：脚位格就是脚所在格，不上移
        boolean thinOk = MovementHelper.footCell(level, carpet.getX() + 0.5D, carpet.getY() + 0.0625D,
                carpet.getZ() + 0.5D).equals(carpet);
        boolean fullOk = MovementHelper.footCell(level, full.getX() + 0.5D, full.getY() + 1.0D,
                full.getZ() + 0.5D).equals(full.above());
        boolean ok = chestOk && slabOk && soulSandOk && thinOk && fullOk;
        BotLog.info("PATHING_REGRESSION {} foot_cell_rule chest={} bottomSlab={} soulSand={}"
                        + " thinFace={} fullBlock={}",
                ok ? "PASS" : "FAIL", chestOk, slabOk, soulSandOk, thinOk, fullOk);
        return ok;
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
