package com.dddgn.alice.task.mining;

import com.dddgn.alice.action.WriteGrant;
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
    public static boolean clearable(ServerPlayer bot, ServerLevel level, BlockPos pos, WriteGrant grant) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.is(BlockTags.LOGS)) {
            return false;   // 原木是目标（可能是自己的，也可能是别的树）→ 不清
        }
        return BlockInteraction.breakable(bot, level, pos, grant);
    }

    /**
     * 找"清障后能从某个站位看见该原木"的方案，返回需要清掉的方块数；不可行返回 -1。
     *
     * @param budget 允许的最大清障数（超出即视为不可行）
     */
    public static int clearPlanCount(ServerLevel level, ServerPlayer bot, BlockPos log,
                                     double reach, int budget, WriteGrant grant) {
        double limit = reach - MiningTuning.reachMargin();
        int minY = log.getY() - 1;
        for (BlockPos stand : lumberStands(log)) {
            if (!MovementHelper.canWalkOn(level, stand.below())) {
                continue;
            }
            int clears = 0;
            boolean standOk = true;
            for (BlockPos cell : List.of(stand, stand.above())) {
                if (MovementHelper.canWalkThrough(level, cell)) {
                    continue;
                }
                if (clearable(bot, level, cell, grant) && cell.getY() >= minY) {
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
                List<BlockPos> blockers = rayBlockers(level, eye, sample, log);
                if (blockers == null) {
                    continue;
                }
                boolean ok = true;
                for (BlockPos blocker : blockers) {
                    if (!clearable(bot, level, blocker, grant) || blocker.getY() < minY) {
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

    /**
     * 伐木专用站位集：**只有贴着树干横向的 8 格**（4 面 × {y, y−1}）。
     *
     * <p>为什么不含"正下方"：那是挖矿模式 B 的策略（从下方挖上去），对树意味着**往地里挖**。
     * 2026-09-10 客户端实测就是这么翻车的：规划器选了 y−1 的几何候选（平台内部）并挖地进去站，
     * 随后爬不出来、2/4 根原木失败。清障只允许在**树干横向邻域**发生。
     */
    public static List<BlockPos> lumberStands(BlockPos log) {
        List<BlockPos> stands = new java.util.ArrayList<>();
        for (int[] face : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            stands.add(log.offset(face[0], 0, face[1]));
            stands.add(log.offset(face[0], -1, face[1]));
        }
        return stands;
    }

    /**
     * 返回"清掉这一格就能推进"的方块；null = 相邻可达区域内已无可行方案。
     *
     * <p>**关键修正（2026-09-10 客户端实测"bot 没动"）**：清障目标必须从**当前就能站**的位置出发选，
     * 且必须是射线上**最外层**那一块。原实现从"清完才能站"的贴树格出发，返回的是树冠**内层**树叶——
     * 那种树叶从任何可站位置都看不见，于是"清这一格"的子任务自己也找不到站位、2 tick 就失败
     * （日志表现：4 根原木全部 `clear_failed`、`cleared=0`、bot 一步没走）。
     *
     * <p>修正后是**由外向内剥离**：从可站位置能看到的最外层树叶先清，露出下一层，逐层推进。
     *
     * <p>**第二处修正（2026-09-10 第二次客户端实测，`no_valid_standing_point`）**：
     * 只按"它是射线上第一块"还不够——**被清的那一格自己也必须能被清掉**。
     * 实测反例：橡树树冠 y=65 是 5×5 叶团，射线从观察位进入树团时第一块是 `(19,65,207)`，
     * 但该格的四个横向邻居与上方**全是树叶**，从任何现成可站位置到它的连线都必经相邻树叶
     * → 清障子任务规划期候选集为空（`no_valid_standing_point`），四次清障全部失败、bot 一步没走。
     * 现在用**清障子任务将要使用的同一判据**（{@link StandingPointSelector#isValidStandingPoint}，
     * 且从同一个观察位出发）校验候选格，保证"可规划即可执行"。
     */
    public static BlockPos nextClearStep(ServerLevel level, ServerPlayer bot, BlockPos log,
                                        double reach, int budgetLeft, WriteGrant grant) {
        if (budgetLeft <= 0) {
            return null;
        }
        double limit = reach - MiningTuning.reachMargin();
        int minY = log.getY() - 1;
        for (BlockPos stand : observationStands(log, reach)) {
            if (!StandingPointSelector.isStandable(level, stand)) {
                continue;   // 只能从"现在就能站"的位置出发（否则清障子任务自己也没站位）
            }
            Vec3 eye = StandingPointSelector.eyeAt(stand);
            for (Vec3 sample : LineOfSightChecker.samples(log)) {
                if (eye.distanceTo(sample) > limit) {
                    continue;
                }
                List<BlockPos> blockers = rayBlockers(level, eye, sample, log);
                if (blockers == null || blockers.isEmpty()) {
                    continue;
                }
                boolean ok = true;
                for (BlockPos blocker : blockers) {
                    if (!clearable(bot, level, blocker, grant) || blocker.getY() < minY) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) {
                    continue;
                }
                // 逐块检查：返回第一块**自己也能被清掉**的阻挡（从当前观察位算，与清障子任务同判据）
                for (BlockPos blocker : blockers) {
                    if (StandingPointSelector.isValidStandingPoint(level, blocker, stand, reach) != null) {
                        return blocker;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 观察位候选：贴树横向 8 格 + 以原木为中心的横向搜索盒（半径 `ceil(reach)`，y−1..y+1），
     * 按到原木的距离升序（近者优先 → 需要清的格数更少）。
     */
    private static List<BlockPos> observationStands(BlockPos log, double reach) {
        java.util.Set<BlockPos> unique = new java.util.LinkedHashSet<>(lumberStands(log));
        int radius = (int) Math.ceil(reach);
        for (int dy = 1; dy >= -1; dy--) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    unique.add(log.offset(dx, dy, dz));
                }
            }
        }
        List<BlockPos> stands = new java.util.ArrayList<>(unique);
        stands.sort(java.util.Comparator.comparingDouble(pos -> pos.distSqr(log)));
        return stands;
    }

    /**
     * 沿射线收集阻挡方块；命中目标返回"目标之前的阻挡集合"，未命中返回 null。
     *
     * <p>用固定步长采样而非解析求交：只为**计数**服务，精度足够，且运行期仍以真实判定为准。
     */
    private static List<BlockPos> rayBlockers(ServerLevel level, Vec3 eye, Vec3 sample, BlockPos target) {
        List<BlockPos> blockers = new java.util.ArrayList<>();
        double distance = eye.distanceTo(sample);
        int steps = Math.max(1, (int) Math.ceil(distance / RAY_STEP));
        for (int i = 1; i <= steps; i++) {
            Vec3 point = eye.lerp(sample, (double) i / steps);
            BlockPos cell = BlockPos.containing(point);
            if (cell.equals(target)) {
                return blockers;
            }
            if (!level.getBlockState(cell).getCollisionShape(level, cell).isEmpty()
                    && !blockers.contains(cell)) {
                blockers.add(cell.immutable());
            }
        }
        return null;
    }
}
