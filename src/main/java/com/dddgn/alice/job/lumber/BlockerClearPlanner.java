package com.dddgn.alice.job.lumber;

import com.dddgn.alice.action.BlockInteraction;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.task.mining.LineOfSightChecker;
import com.dddgn.alice.task.mining.MiningTuning;
import com.dddgn.alice.task.mining.StandingPointSelector;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 视线清障规划（限次、预算内、显式授权；`docs/JOB_LAYER_DESIGN.md` §5.4）。
 *
 * <p>**不区分"软/硬"方块**（2026-09-10 用户裁定）：树叶本来就有碰撞箱，把它单列一类是多余分类。
 * 统一规则是——
 * > 任何**可破坏**（`BlockInteraction.breakable`：排除保护区/不可破坏/流体）且**不是原木**的阻挡方块，
 * > 都可以作为"限次清障"对象；上限由调用方给出的**预算**兜底。
 *
 * <p>运行时真实存在的两种无解情形：预算用尽、或阻挡物不可破坏。
 *
 * <p>为什么需要它：真实阔叶/针叶树的**最上面 1~2 根原木总被树冠包住**，而**紧邻柱底之上那根**
 * 只能从侧面挖（头位就是它），侧面又被树冠罩住 → 不清几片树叶就无从下手。
 */
public final class BlockerClearPlanner {

    /** 射线采样步长（格）。 */
    private static final double RAY_STEP = 0.05D;

    private BlockerClearPlanner() {
    }

    /** 该方块是否允许作为"限次清障"对象。 */
    public static boolean clearable(ServerPlayer bot, ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.is(BlockTags.LOGS)) {
            return false;   // 原木是目标（可能是自己的，也可能是别的树）→ 不清
        }
        return BlockInteraction.breakable(bot, level, pos);
    }

    /**
     * 找"清障后能从某个站位看见该原木"的方案，返回需要清掉的方块数；不可行返回 -1。
     *
     * @param budget 允许的最大清障数（超出即视为不可行）
     */
    public static int clearPlanCount(ServerLevel level, ServerPlayer bot, BlockPos log,
                                     double reach, int budget) {
        double limit = reach - MiningTuning.reachMargin();
        for (BlockPos stand : geometricStands(log, reach)) {
            if (!MovementHelper.canWalkOn(level, stand.below())) {
                continue;
            }
            int clears = 0;
            boolean standOk = true;
            for (BlockPos cell : List.of(stand, stand.above())) {
                if (MovementHelper.canWalkThrough(level, cell)) {
                    continue;
                }
                if (clearable(bot, level, cell)) {
                    clears++;
                } else {
                    standOk = false;
                    break;
                }
            }
            if (!standOk || clears > budget) {
                continue;
            }
            Vec3 eye = StandingPointSelector.eyeAt(stand);
            for (Vec3 sample : LineOfSightChecker.samples(log)) {
                if (eye.distanceTo(sample) > limit) {
                    continue;
                }
                Set<BlockPos> blockers = rayBlockers(level, eye, sample, log);
                if (blockers == null) {
                    continue;
                }
                boolean ok = true;
                for (BlockPos blocker : blockers) {
                    if (!clearable(bot, level, blocker)) {
                        ok = false;
                        break;
                    }
                }
                int total = clears + blockers.size();
                if (ok && total <= budget) {
                    return total;
                }
            }
        }
        return -1;
    }

    /** 站位几何集：4 面 × {y, y−1} + 正下方（与规划器模式 B 同口径，但不要求当前可站）。 */
    private static List<BlockPos> geometricStands(BlockPos log, double reach) {
        java.util.List<BlockPos> stands = new java.util.ArrayList<>();
        int[][] faces = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] face : faces) {
            stands.add(log.offset(face[0], 0, face[1]));
            stands.add(log.offset(face[0], -1, face[1]));
        }
        int maxDepth = (int) Math.floor(reach + 1.54D);
        for (int k = 2; k <= maxDepth; k++) {
            stands.add(log.below(k));
        }
        return stands;
    }

    /**
     * 沿射线收集阻挡方块；命中目标返回"目标之前的阻挡集合"，未命中返回 null。
     *
     * <p>用固定步长采样而非解析求交：只为**计数**服务，精度足够，且运行期仍以真实判定为准。
     */
    private static Set<BlockPos> rayBlockers(ServerLevel level, Vec3 eye, Vec3 sample, BlockPos target) {
        Set<BlockPos> blockers = new LinkedHashSet<>();
        double distance = eye.distanceTo(sample);
        int steps = Math.max(1, (int) Math.ceil(distance / RAY_STEP));
        for (int i = 1; i <= steps; i++) {
            Vec3 point = eye.lerp(sample, (double) i / steps);
            BlockPos cell = BlockPos.containing(point);
            if (cell.equals(target)) {
                return blockers;
            }
            if (!level.getBlockState(cell).getCollisionShape(level, cell).isEmpty()) {
                blockers.add(cell.immutable());
            }
        }
        return null;
    }
}
