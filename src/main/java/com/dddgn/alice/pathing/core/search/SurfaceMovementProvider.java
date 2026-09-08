package com.dddgn.alice.pathing.core.search;

import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.MovementType;
import com.dddgn.alice.pathing.core.RecoverabilityLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * 受限曲面纯通行候选生成器（R3 第一版）。
 *
 * <p>只生成 **基数为轴** 的上升/下降（与 R2-C 执行器一致，避免"可规划不可执行"），
 * 水平移动含四向与四对角。世界谓词复用 legacy {@link MovementHelper}
 * （含 `canSweepPlayer` 连续扫掠），不新造判定。
 *
 * <p>下降额外执行 D-024 细化版过冲列校验：过冲列必须与目标同层或为下一级台阶，
 * 且无即死危害。
 */
public final class SurfaceMovementProvider implements MovementProvider {
    private static final int[][] CARDINAL = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
    private static final int[][] DIAGONAL = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    @Override
    public void appendCandidates(MovementContext context, BlockPos from, List<PlannedMovement> out) {
        ServerLevel level = context.level();

        if (context.allows(MovementType.TRAVERSE)) {
            for (int[] d : CARDINAL) {
                appendPlane(context, level, from, d[0], d[1], MovementType.TRAVERSE, out);
            }
        }
        if (context.allows(MovementType.DIAGONAL)) {
            for (int[] d : DIAGONAL) {
                appendPlane(context, level, from, d[0], d[1], MovementType.DIAGONAL, out);
            }
        }
        if (context.allows(MovementType.ASCEND)) {
            for (int[] d : CARDINAL) {
                BlockPos to = from.offset(d[0], 1, d[1]);
                if (!context.yInBounds(to.getY())) {
                    continue;
                }
                if (MovementHelper.canAscend(level, from, to)) {
                    append(context, from, to, MovementType.ASCEND, out);
                }
            }
        }
        if (context.allows(MovementType.DESCEND)) {
            for (int[] d : CARDINAL) {
                BlockPos to = from.offset(d[0], -1, d[1]);
                if (!context.yInBounds(to.getY())) {
                    continue;
                }
                if (MovementHelper.canDescend(level, from, to) && overshootColumnSafe(level, to, d[0], d[1])) {
                    append(context, from, to, MovementType.DESCEND, out);
                }
            }
        }
    }

    private static void appendPlane(MovementContext context, ServerLevel level, BlockPos from,
                                    int dx, int dz, MovementType type, List<PlannedMovement> out) {
        BlockPos to = from.offset(dx, 0, dz);
        if (!context.yInBounds(to.getY())) {
            return;
        }
        if (MovementHelper.canTraverse(level, from, to)) {
            append(context, from, to, type, out);
        }
    }

    private static void append(MovementContext context, BlockPos from, BlockPos to,
                               MovementType type, List<PlannedMovement> out) {
        double cost = context.cost(type, from, to);
        if (!Double.isFinite(cost) || cost <= 0.0D) {
            return;
        }
        out.add(new PlannedMovement(type, from, to, cost, RecoverabilityLevel.LOCAL_STEP));
    }

    /**
     * D-024（细化）：下降目标的过冲列必须
     * ① 在落点高度不可穿越（实心墙挡住过冲），或
     * ② 与目标同层可站立，或
     * ③ 为下一级台阶（比目标低 1 格）可站立；
     * 且上述任一情况都不得含即死危害。
     */
    static boolean overshootColumnSafe(ServerLevel level, BlockPos to, int dx, int dz) {
        int signX = Integer.signum(dx);
        int signZ = Integer.signum(dz);
        BlockPos beyond = to.offset(signX, 0, signZ);
        if (!MovementHelper.canWalkThrough(level, beyond)) {
            return true;
        }
        if (MovementHelper.avoidWalkingInto(level.getBlockState(beyond))
                || MovementHelper.avoidWalkingInto(level.getBlockState(beyond.below()))
                || MovementHelper.avoidWalkingInto(level.getBlockState(beyond.below(2)))) {
            return false;
        }
        return MovementHelper.canWalkOn(level, beyond) || MovementHelper.canWalkOn(level, beyond.below());
    }
}
