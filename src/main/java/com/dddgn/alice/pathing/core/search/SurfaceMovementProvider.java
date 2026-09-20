package com.dddgn.alice.pathing.core.search;

import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.action.BlockInteraction;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.BreakAndTraverseExecution;
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
    /** 放置一个方块的成本（对照 Baritone `blockPlacementPenalty`，见 CostModel）。 */
    private static final double PLACE_ONE_BLOCK_COST = CostModel.PLACE_ONE_BLOCK_COST;
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
        if (context.allows(MovementType.DOWNWARD)) {
            appendDownward(context, level, from, out);
        }
        if (context.allows(MovementType.PILLAR)) {
            appendPillar(context, level, from, out);
        }
        if (context.allows(MovementType.FALL)) {
            for (int[] d : CARDINAL) {
                appendFall(context, level, from, d[0], d[1], out);
            }
        }
        if (context.allows(MovementType.BREAK_AND_ENTER)) {
            for (int[] d : CARDINAL) {
                appendBreakAndEnter(context, level, from, d[0], d[1], out);
            }
        }
        if (context.allows(MovementType.PLACE_STEP_AND_TRAVERSE)) {
            for (int[] d : CARDINAL) {
                // ⭐ **dy 从 -1 扩到 +1**（`D-336` / `survey/22 §4.3`：**斜向上升那一格**是真缺口 ——
                // 平走 `dy=0`、下 1 格 `dy=-1` 都有，只有"斜上方"没有）。
                // 几何用**同一个 helper** 表达：`to` 在斜上方，`target = to.below()` 就是"要在其中放台阶的那一格"
                // （它必须可穿过 ⇒ 是空的；放置后 bot 斜向上踩上去）。
                // ⚠️ 不动 `ASCEND`（`D-334`：`changesWorld()` 是信封分档的唯一静态口径，
                // 让 `ASCEND` 自己放方块会打穿分层）；本边**仍是世界修改类**，信封语义不变。
                // D-366（2026-09-20，用户选 A）：**只允许 dy ∈ {0,-1}**。
                // D-336 曾把这里放开到 `dy = 1`（搭一格上升），但下游 `MovementSpec.validateDisplacement`
                // 与 `PlaceStepAndTraverseExecutionFactory` **都只接受 {0,-1}** ⇒ 搜索会规划出执行端
                // 构造不出来的边 ⇒ `MovementSpec` 构造时**硬抛 ⇒ 崩服**（真机实测 2026-09-20 21:32）。
                for (int dy = 0; dy >= -1; dy--) {
                    appendPlaceStepAndTraverse(context, level, from, d[0], d[1], dy, out);
                }
            }
        }
        if (context.allows(MovementType.DESCEND)) {
            for (int[] d : CARDINAL) {
                BlockPos to = from.offset(d[0], -1, d[1]);
                if (!context.yInBounds(to.getY())) {
                    continue;
                }
                if (MovementHelper.canDescend(level, from, to)) {
                    if (overshootColumnSafe(context.bot(), level, to, d[0], d[1])) {
                        append(context, from, to, MovementType.DESCEND, out);
                    } else {
                        PathingStats.record("descend_overshoot_unsafe");
                    }
                } else {
                    PathingStats.record("descend_precondition");
                }
            }
        }
    }

    /**
     * **起点脱困**（S-1 / D-133）：起点自身非法时，只按**目的地谓词**生成第一跳。
     *
     * <p>谓词与执行器完全一致（`to`/`to.above()` 可穿过 + `to` 可站 + 目的地无流体）⇒ 可规划即可执行。
     * 允许 `dy ∈ {0, +1}`：0 用于"从被堵的格里横着挪出去"，+1 用于"从 1 格深的坑/岩浆里跨上台面"。
     */
    @Override
    public void appendStartEscapeCandidates(MovementContext context, BlockPos from,
                                            List<PlannedMovement> out) {
        ServerLevel level = context.level();
        int[][] dirs = new int[CARDINAL.length + DIAGONAL.length][];
        System.arraycopy(CARDINAL, 0, dirs, 0, CARDINAL.length);
        System.arraycopy(DIAGONAL, 0, dirs, CARDINAL.length, DIAGONAL.length);
        for (int[] d : dirs) {
            boolean diagonal = d[0] != 0 && d[1] != 0;
            MovementType plane = diagonal ? MovementType.DIAGONAL : MovementType.TRAVERSE;
            for (int dy = 0; dy <= 1; dy++) {
                MovementType type = dy == 0 ? plane : MovementType.ASCEND;
                if (!context.allows(type)) {
                    continue;
                }
                BlockPos to = from.offset(d[0], dy, d[1]);
                if (!context.yInBounds(to.getY())) {
                    continue;
                }
                if (!MovementHelper.canStandCentered(level, to)) {
                    continue;   // K-4/D-167：与挖掘站位、目标准入共用同一"可站"谓词
                }
                if (!level.getFluidState(to).isEmpty() || !level.getFluidState(to.above()).isEmpty()) {
                    continue;   // 目的地是流体 ⇒ 不往危险里"脱困"
                }
                if (dy == 1 && !MovementHelper.canWalkThrough(level, from.above(2))) {
                    continue;   // ASCEND 执行器还要求"起点头部空间"（from+2 可穿）⇒ 别造可规划不可执行的边
                }
                append(context, from, to, type, out);
            }
        }
    }

    /** FALL 支持的落差（Baritone `maxFallHeightNoWater = 3`，无水落地，D-058）。 */
    private static final int[] FALL_DROPS = {2, 3};

    /**
     * 破坏目的地格并进入（{@link MovementType#BREAK_AND_ENTER}，D-067 ⑯）。
     *
     * <p>对照 Baritone `MovementTraverse.positionsToBreak = {to.above(), to}`：
     * 目的地被可破坏方块占用时，先破坏目的地躯干 + 头位，再走进该格。
     * <p>生成条件：目的地被阻挡、目的地最终可站（支撑存在）、破坏方块可破坏且成本有限。
     */
    private static void appendBreakAndEnter(MovementContext context, ServerLevel level, BlockPos from,
                                            int dx, int dz, List<PlannedMovement> out) {
        // D-106 Slice B（计划期剪枝）：预算不允许写入时，写边根本不生成——
        // 否则会规划出"执行到一半必然被拒"的路径（实测：破坏预算用满后改规划放置绕行）
        if (!context.writesAllowed(MovementType.BREAK_AND_ENTER)) {
            return;
        }
        BlockPos to = from.offset(dx, 0, dz);
        if (!context.yInBounds(to.getY())) {
            return;
        }
        // 目的地可通行 → 属于 TRAVERSE，不生成
        if (MovementHelper.canWalkThrough(level, to)) {
            return;
        }
        // 目的地最终必须可站（脚下支撑）。
        // **故意比 `MovementHelper.canStandCentered` 宽**（K-4/D-167）：本移动会先破坏目的地的
        // 躯干+头位方块，所以"脚位/头位可通行"在**破坏之后**才成立，规划期查它会自相矛盾
        // （实测：查了就连一条 BREAK_AND_ENTER 都生成不出来）。代价是**破坏类请求的目标格**
        // 在规划期无法被证明可站 ⇒ 这类请求的目标准入只能靠运行期 EXACT 兜底（K-4 遥测在测）。
        if (!MovementHelper.canWalkOn(level, to)) {
            return;
        }
        List<BlockPos> blockers =
                com.dddgn.alice.pathing.core.BreakAndEnterExecution.collectBlockers(level, from, to);
        if (blockers.isEmpty()) {
            return;
        }
        double breakTicks = 0.0D;
        for (BlockPos blocker : blockers) {
            if (context.bot() == null || !BlockInteraction.breakable(context.bot(), level, blocker,
                    WriteGrant.of(context.request().requester(), WriteReason.PATH_ACCESS))) {
                return;
            }
            double ticks = BlockInteraction.estimateBreakTicks(context.bot(), level, blocker);
            if (!Double.isFinite(ticks)) {
                return;
            }
            breakTicks += ticks;
        }
        double cost = context.cost(MovementType.TRAVERSE, from, to)
                + (breakTicks + CostModel.BREAK_PENALTY_TICKS) / CostModel.WALK_ONE_BLOCK_TICKS;
        out.add(new PlannedMovement(MovementType.BREAK_AND_ENTER, from, to, cost,
                com.dddgn.alice.pathing.core.RecoverabilityEvaluator.levelOf(MovementType.BREAK_AND_ENTER)));
    }

    /**
     * 落差 2~3 格（{@link MovementType#FALL}）：走离边缘后自由落到低处的可站平台。
     *
     * <p>对照 Baritone `MovementDescend.cost` → `dynamicFallCost:145-224`（无水落地分支）：
     * 落点必须可站、非流体、非底部半砖；落差上限 `maxFallHeightNoWater + 1 = 3`。
     * <p>**Alice 安全守卫（D-024 补记 Q1-D / D-058）**：落点必须能用 PILLAR 返回
     * （返回列净空 + 有放置面 + 一次性方块数量 ≥ 落差），否则拒绝——保证局部可回收性不变式。
     */
    private static void appendFall(MovementContext context, ServerLevel level, BlockPos from,
                                   int dx, int dz, List<PlannedMovement> out) {
        BlockPos edge = from.offset(dx, 0, dz);
        if (!MovementHelper.canWalkThrough(level, edge)
                || !MovementHelper.canWalkThrough(level, edge.above())) {
            return;   // 走不出边缘
        }
        for (int drop : FALL_DROPS) {
            BlockPos to = from.offset(dx, -drop, dz);
            if (!context.yInBounds(to.getY())) {
                continue;
            }
            // 下落列净空：从边缘下一格到落点上方全部可穿过
            boolean clear = true;
            for (int y = from.getY() - 1; y > to.getY(); y--) {
                if (!MovementHelper.canWalkThrough(level, new BlockPos(to.getX(), y, to.getZ()))) {
                    clear = false;
                    break;
                }
            }
            if (!clear) {
                continue;
            }
            // 落点：可站 + 身体/头部净空 + 非流体（本轮只做无水落地）+ 非底部半砖
            if (!MovementHelper.canStandCentered(level, to)) {
                continue;   // K-4/D-167：共用"可站"谓词
            }
            if (!level.getFluidState(to).isEmpty() || !level.getFluidState(to.above()).isEmpty()) {
                continue;
            }
            if (MovementHelper.isBottomSlab(level.getBlockState(to.below()))) {
                continue;
            }
            if (!fallRecoverable(context, level, to, drop)) {
                continue;
            }
            double cost = drop == 3 ? CostModel.FALL_THREE_BLOCK_COST : CostModel.FALL_TWO_BLOCK_COST;
            // **逐边事实**：本边刚刚通过 `fallRecoverable`（PILLAR 返回守卫）⇒ 带上"已验证回程"。
            // 评估器缺事实会保守降级为 LOCAL_STEP，而策略表要求 FALL ≥ PATH_REVERSIBLE ⇒ 会抛异常。
            out.add(new PlannedMovement(MovementType.FALL, from, to, cost,
                    com.dddgn.alice.pathing.core.RecoverabilityEvaluator.levelOf(
                            MovementType.FALL, com.dddgn.alice.pathing.core.RecoverabilityFacts.FALL_RETURN_VERIFIED),
                    com.dddgn.alice.pathing.core.RecoverabilityFacts.FALL_RETURN_VERIFIED));
        }
    }

    /** 落点可回收守卫：必须能沿落点列 PILLAR 回原高度（净空 + 放置面 + 一次性方块 ≥ 落差）。 */
    private static boolean fallRecoverable(MovementContext context, ServerLevel level, BlockPos to, int drop) {
        if (context.bot() == null) {
            return false;
        }
        for (int k = 1; k <= drop + 1; k++) {
            if (!MovementHelper.canWalkThrough(level, to.above(k))) {
                return false;
            }
        }
        return BlockInteraction.hasPlacementFace(level, to)
                && BlockInteraction.countThrowaway(context.bot()) >= drop;
    }

    /**
     * 垂直上升 1 格（PILLAR）：跳跃中在脚下放置方块，落在上面（对照 Baritone `MovementPillar`）。
     * <p>前置：目标身体+头部净空、脚下可放置、有一次性方块与放置面。
     */
    private static void appendPillar(MovementContext context, ServerLevel level, BlockPos from,
                                     List<PlannedMovement> out) {
        // D-106 Slice B（计划期剪枝）：预算不允许写入时，写边根本不生成——
        // 否则会规划出"执行到一半必然被拒"的路径（实测：破坏预算用满后改规划放置绕行）
        if (!context.writesAllowed(MovementType.PILLAR)) {
            return;
        }
        BlockPos to = from.above();
        if (!context.yInBounds(to.getY())) {
            return;
        }
        if (!MovementHelper.canWalkThrough(level, to)
                || !MovementHelper.canWalkThrough(level, to.above())
                || !MovementHelper.canWalkThrough(level, from)) {
            return;
        }
        if (context.bot() == null || BlockInteraction.findPlaceableSlot(context.bot()) < 0) {
            return;
        }
        if (!BlockInteraction.hasPlacementFace(level, from)) {
            return;
        }
        out.add(new PlannedMovement(MovementType.PILLAR, from, to, CostModel.PILLAR_COST,
                com.dddgn.alice.pathing.core.RecoverabilityEvaluator.levelOf(MovementType.PILLAR)));
    }

    /**
     * 垂直下落 1 格（DOWNWARD）：破坏脚下的方块后掉进 1 格深的洞（**Baritone 原样语义**，D-050）。
     * <p>不做"逃生路线"守卫——安全模式的守卫留给后续的安全 Movement / 任务层风险决策。
     */
    private static void appendDownward(MovementContext context, ServerLevel level, BlockPos from,
                                       List<PlannedMovement> out) {
        // D-106 Slice B（计划期剪枝）：预算不允许写入时，写边根本不生成——
        // 否则会规划出"执行到一半必然被拒"的路径（实测：破坏预算用满后改规划放置绕行）
        if (!context.writesAllowed(MovementType.DOWNWARD)) {
            return;
        }
        BlockPos to = from.below();
        if (!context.yInBounds(to.getY())) {
            return;
        }
        // **故意比 `canStandCentered` 宽**（K-4/D-167）：DOWNWARD 要破坏的正是 `to` 这一格，
        // 规划期 `canWalkThrough(to)` 必然为假（否则不会走 DOWNWARD），加进去等于禁用本移动。
        if (!MovementHelper.canWalkOn(level, to)
                || !MovementHelper.canWalkThrough(level, to.above())) {
            return;
        }
        if (context.bot() == null || !BlockInteraction.breakable(context.bot(), level, to,
                WriteGrant.of(context.request().requester(), WriteReason.DESCEND_FOOT))) {
            return;
        }
        double breakTicks = BlockInteraction.estimateBreakTicks(context.bot(), level, to);
        if (!Double.isFinite(breakTicks)) {
            return;
        }
        double cost = CostModel.DOWNWARD_COST
                + (breakTicks + CostModel.BREAK_PENALTY_TICKS) / CostModel.WALK_ONE_BLOCK_TICKS;
        out.add(new PlannedMovement(MovementType.DOWNWARD, from, to, cost,
                com.dddgn.alice.pathing.core.RecoverabilityEvaluator.levelOf(MovementType.DOWNWARD)));
    }

    private static void appendPlane(MovementContext context, ServerLevel level, BlockPos from,
                                    int dx, int dz, MovementType type, List<PlannedMovement> out) {
        BlockPos to = from.offset(dx, 0, dz);
        if (!context.yInBounds(to.getY())) {
            return;
        }
        if (MovementHelper.canTraverse(level, from, to)) {
            append(context, from, to, type, out);
            return;
        }
        // R5-2：水平被阻挡但可破坏时，生成破坏通行候选（只对基数为轴的 TRAVERSE 生成）
        if (type == MovementType.TRAVERSE && context.allows(MovementType.BREAK_AND_TRAVERSE)) {
            appendBreakAndTraverse(context, level, from, dx, dz, out);
        }
    }

    /**
     * 破坏通行候选（R5-2）：从 {@code from} 沿 (dx,dz) 方向，
     * 中间列被阻挡且可破坏、其后一格可站时，生成"破坏中间列 + 走到其后一格"的候选
     * （位移 2 格直线）。成本 = 水平 2 格 + 破坏 tick / 20。
     */
    private static void appendBreakAndTraverse(MovementContext context, ServerLevel level,
                                               BlockPos from, int dx, int dz,
                                               List<PlannedMovement> out) {
        // D-106 Slice B（计划期剪枝）：预算不允许写入时，写边根本不生成——
        // 否则会规划出"执行到一半必然被拒"的路径（实测：破坏预算用满后改规划放置绕行）
        if (!context.writesAllowed(MovementType.BREAK_AND_TRAVERSE)) {
            return;
        }
        BlockPos mid = from.offset(dx, 0, dz);
        BlockPos to = from.offset(dx * 2, 0, dz * 2);
        if (!context.yInBounds(to.getY())) {
            return;
        }
        // 中间列必须确实被阻挡
        List<BlockPos> blockers = BreakAndTraverseExecution.collectBlockers(level, from, to);
        if (blockers.isEmpty()) {
            return;
        }
        // 目标必须可通行且可站（K-4/D-167：共用"可站"谓词）
        if (!MovementHelper.canStandCentered(level, to)) {
            return;
        }
        double breakTicks = 0.0D;
        for (BlockPos blocker : blockers) {
            if (context.bot() == null || !BlockInteraction.breakable(context.bot(), level, blocker,
                    WriteGrant.of(context.request().requester(), WriteReason.PATH_ACCESS))) {
                return;
            }
            breakTicks += BlockInteraction.estimateBreakTicks(context.bot(), level, blocker);
        }
        if (!Double.isFinite(breakTicks)) {
            return;
        }
        // 破坏成本单位：tick → 走路格数（除以实测走路 tick 数），并加 Baritone 的固定破坏惩罚
        double cost = context.cost(MovementType.TRAVERSE, from, mid)
                + context.cost(MovementType.TRAVERSE, mid, to)
                + (breakTicks + CostModel.BREAK_PENALTY_TICKS) / CostModel.WALK_ONE_BLOCK_TICKS;
        out.add(new PlannedMovement(MovementType.BREAK_AND_TRAVERSE, from, to, cost,
                com.dddgn.alice.pathing.core.RecoverabilityEvaluator.levelOf(MovementType.BREAK_AND_TRAVERSE)));
    }

    /**
     * 放置台阶通行候选（R5-3）：目标列可通行但缺支撑（同层缺口或下 1 格）且
     * 目标下方可放置、bot 有可放置方块、存在支撑面时生成。
     * 成本 = 水平成本 + 放置成本（对照 Baritone `blockPlacementPenalty`）。
     */
    private static void appendPlaceStepAndTraverse(MovementContext context, ServerLevel level,
                                                   BlockPos from, int dx, int dz, int dy,
                                                   List<PlannedMovement> out) {
        // D-106 Slice B（计划期剪枝）：预算不允许写入时，写边根本不生成——
        // 否则会规划出"执行到一半必然被拒"的路径（实测：破坏预算用满后改规划放置绕行）
        if (!context.writesAllowed(MovementType.PLACE_STEP_AND_TRAVERSE)) {
            return;
        }
        BlockPos to = from.offset(dx, dy, dz);
        if (!context.yInBounds(to.getY())) {
            return;
        }
        if (!MovementHelper.canWalkThrough(level, to)
                || !MovementHelper.canWalkThrough(level, to.above())
                || MovementHelper.canWalkOn(level, to)) {
            return;
        }
        BlockPos target = to.below();
        if (!MovementHelper.canWalkThrough(level, target)) {
            return;
        }
        // 守卫：放置位不得是目标脚位（否则放置后目标不可站，计划自相矛盾）
        if (context.request().goal().isInGoal(target)) {
            return;
        }
        if (context.bot() == null || BlockInteraction.findPlaceableSlot(context.bot()) < 0) {
            return;
        }
        if (!BlockInteraction.hasPlacementFace(level, target)) {
            return;
        }
        // 定价基类：同层 = 走；下一格 = 下降（D-366 起不再有 dy=+1 分支）。
        MovementType base = dy == 0 ? MovementType.TRAVERSE : MovementType.DESCEND;
        double cost = context.cost(base, from, to) + PLACE_ONE_BLOCK_COST;
        out.add(new PlannedMovement(MovementType.PLACE_STEP_AND_TRAVERSE, from, to, cost,
                com.dddgn.alice.pathing.core.RecoverabilityEvaluator.levelOf(MovementType.PLACE_STEP_AND_TRAVERSE)));
    }

    private static void append(MovementContext context, BlockPos from, BlockPos to,
                               MovementType type, List<PlannedMovement> out) {
        double cost = context.cost(type, from, to);
        if (!Double.isFinite(cost) || cost <= 0.0D) {
            return;
        }
        out.add(new PlannedMovement(type, from, to, cost, com.dddgn.alice.pathing.core.RecoverabilityEvaluator.levelOf(type)));
    }

    /**
     * D-024（细化）：下降目标的过冲列必须
     * ① 在落点高度不可穿越（实心墙挡住过冲），或
     * ② 与目标同层可站立，或
     * ③ 为下一级台阶（比目标低 1 格）可站立；
     * 且上述任一情况都不得含即死危害。
     */
    static boolean overshootColumnSafe(net.minecraft.server.level.ServerPlayer bot, ServerLevel level, BlockPos to, int dx, int dz) {
        // S-6：读**该 bot 的冻结画像**（不读全局静态开关）
        if (bot == null
                || !com.dddgn.alice.pathing.risk.RiskProfile.of(bot).descendOvershootGuard()) {
            return true;   // D-059：默认关闭（Baritone 原样），低风险模式再打开
        }
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
