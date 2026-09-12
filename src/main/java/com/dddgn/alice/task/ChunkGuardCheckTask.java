package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.search.PlanningStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * **未加载区块 / 世界边界准入自检**（{@code alice:chunk_guard_check}，S-2 / P1-A）：一次右键跑三个用例。
 *
 * <p>背景（`RISK_SYSTEM_REVIEW_20260910.md` §2，反编译证据）：服务端在**未加载区块**上
 * `getBlockState` 会**同步加载/生成区块并阻塞主线程** —— 所以搜索层既不该把"没加载"错报成
 * "到不了"，也不该为了看一眼而把区块拉起来。对照 Baritone：`worldContainsLoadedChunk` /
 * `isLoaded`（`getChunk(..., FULL, false)` 从不加载）+ `AStarPathFinder:105-112` 跨区块门控。
 *
 * <pre>
 * 0 前置：先把 bot 放到**干净的落点**（脚/头可穿 + 有支撑 + 无流体）——否则起点自身非法，
 *   连一条边都生成不出来，会污染 A/B/C 全部用例（2026-09-12 首测教训：bot 站在树冠里，
 *   B/C 都是 `UNREACHABLE best=0.0`）
 * A 目标在未加载区块 → status=GOAL_NOT_LOADED（独立状态，≠UNREACHABLE/SEARCH_LIMIT）
 *                     且**规划前后那一格区块都仍未加载**（证明没有同步加载副作用）
 * B 正对照：**紧邻**已加载可站格 → REACHED（证明门控没有把正常寻路掐死）
 * C 世界边界：临时把边界缩小到 8 格，目标放在边界外**已加载**区块 → 不可 REACHED
 *             且诊断里出现 skipped_border（随后**立刻还原边界**）
 * </pre>
 */
public class ChunkGuardCheckTask implements Task {

    /** 用例 A 的候选距离（格）：逐个试，取第一个**未加载**的（视距可调，不能写死一个数）。 */
    private static final int[] FAR_CANDIDATE_DISTANCES = {512, 1024, 2048, 4096, 8192};
    /**
     * 用例 C 用的临时边界尺寸（以 bot 为中心 3 格 ⇒ 中心 ±1.5）。
     *
     * <p>为什么必须这么小：首测用 8 格边界，而当时 bot 站在 **5×5** 的场地上 ⇒
     * 卡住搜索的是**地板边缘**（`best=4.0` 正好走到地板尽头）而不是边界，
     * 于是 `skipped_border=0`、用例假失败（2026-09-12 14:47）。取 ±1.5 后，
     * "距离 2 的格子"必在界外，而任何 ≥3 格宽的可走面都必然存在这样的格子 ⇒ 门控一定会被触发。
     */
    private static final double TEST_BORDER_SIZE = 3.0D;
    /** 用例 C 的目标距离：在已加载区块内、但在缩小后的边界之外。 */
    private static final int BORDER_GOAL_DISTANCE = 24;

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> lines = new ArrayList<>();
    private boolean done;
    private boolean preconditionFailed;

    public ChunkGuardCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(bot.blockPosition());
    }

    @Override
    public String failureReason() {
        return done && !allPassed() ? "chunk_guard_check_failed" : "";
    }

    @Override
    public Status tick() {
        if (done) {
            return Status.DONE;
        }
        done = true;
        ServerLevel level = bot.serverLevel();
        BlockPos cleanStart = findCleanStart(level);
        if (cleanStart == null) {
            lines.add("clean_start=NONE FAIL");
            finish();
            return Status.FAILED;
        }
        teleport(level, cleanStart);
        BlockPos startFoot = cleanStart;

        caseFarGoal(level, startFoot);
        caseNearGoal(level, startFoot);
        caseWorldBorder(level, startFoot);

        boolean pass = allPassed();
        finish();
        return pass ? Status.DONE : Status.FAILED;
    }

    // ==================== 前置：干净的起点 ====================

    /**
     * 找一个**合法落点**并把 bot 放过去。为什么必须先做：起点自身非法（头被堵 / 泡流体）时，
     * `canTraverse` 的扫掠包含起点体积 ⇒ 一条边都生成不出来，A/B/C 会全部退化成
     * `UNREACHABLE best=0.0`（2026-09-12 首测就是这么废掉的）。
     */
    private BlockPos findCleanStart(ServerLevel level) {
        return findCleanStandNear(level, bot);
    }

    /** 在 bot 附近（±4 水平 / ±1 垂直）找一个**合法落点**；没有 ⇒ null。物品侧与任务侧共用。 */
    public static BlockPos findCleanStandNear(ServerLevel level, BotPlayer bot) {
        BlockPos origin = MovementHelper.footCell(level, bot);
        for (BlockPos candidate : BlockPos.betweenClosed(origin.offset(-4, -1, -4), origin.offset(4, 1, 4))) {
            BlockPos foot = candidate.immutable();
            if (isCleanStand(level, foot)) {
                return foot;
            }
        }
        return null;
    }

    private static boolean isCleanStand(ServerLevel level, BlockPos foot) {
        return level.hasChunkAt(foot)
                && level.getFluidState(foot).isEmpty()
                && level.getFluidState(foot.above()).isEmpty()
                && MovementHelper.canWalkOn(level, foot)
                && MovementHelper.canWalkThrough(level, foot)
                && MovementHelper.canWalkThrough(level, foot.above());
    }

    private void teleport(ServerLevel level, BlockPos foot) {
        bot.teleportTo(level, foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        BotLog.info("[ChunkGuard] 前置：bot 已放到干净落点 {}", foot.toShortString());
    }

    // ==================== 用例 A：目标在未加载区块 ====================

    private void caseFarGoal(ServerLevel level, BlockPos startFoot) {
        BlockPos far = null;
        for (int distance : FAR_CANDIDATE_DISTANCES) {
            BlockPos candidate = startFoot.offset(distance, 0, distance);
            if (!level.hasChunkAt(candidate)) {
                far = candidate;
                break;
            }
        }
        if (far == null) {
            lines.add("far_goal=NO_UNLOADED_CHUNK_IN_RANGE FAIL");
            BotLog.warn("[ChunkGuard] 用例 A 前提不成立：{} 格内找不到未加载区块（视距异常大？）",
                    FAR_CANDIDATE_DISTANCES[FAR_CANDIDATE_DISTANCES.length - 1]);
            return;
        }
        PathPlan plan = planTo(level, startFoot, far);
        boolean statusOk = plan.status() == PlanningStatus.GOAL_NOT_LOADED;
        boolean noSideEffect = !level.hasChunkAt(far);   // 规划不该把目标区块拉起来
        lines.add("far_goal=" + (statusOk ? "GOAL_NOT_LOADED" : plan.status().name())
                + " no_sync_load=" + noSideEffect
                + (statusOk && noSideEffect ? " PASS" : " FAIL"));
        BotLog.info("[ChunkGuard] A 目标={} status={} 规划后仍未加载={} diagnostics={}",
                far.toShortString(), plan.status(), noSideEffect, plan.diagnostics());
    }

    // ==================== 用例 B：正对照（紧邻一格） ====================

    private void caseNearGoal(ServerLevel level, BlockPos startFoot) {
        BlockPos near = null;
        int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] d : offsets) {
            BlockPos foot = startFoot.offset(d[0], 0, d[1]);
            if (isCleanStand(level, foot) && MovementHelper.canTraverse(level, startFoot, foot)) {
                near = foot;
                break;
            }
        }
        if (near == null) {
            lines.add("near_goal=NO_NEIGHBOR FAIL");
            BotLog.warn("[ChunkGuard] 用例 B 前提不成立：起点四周没有可走一步的邻格");
            return;
        }
        PathPlan plan = planTo(level, startFoot, near);
        boolean ok = plan.reached();
        lines.add("near_goal=" + plan.status().name() + (ok ? " PASS" : " FAIL"));
        BotLog.info("[ChunkGuard] B 正对照 目标={} status={} movements={} nodes={}",
                near.toShortString(), plan.status(), plan.movements().size(), plan.nodesExpanded());
    }

    // ==================== 用例 C：世界边界 ====================

    private void caseWorldBorder(ServerLevel level, BlockPos startFoot) {
        net.minecraft.world.level.border.WorldBorder border = level.getWorldBorder();
        double oldSize = border.getSize();
        double oldX = border.getCenterX();
        double oldZ = border.getCenterZ();
        try {
            border.setCenter(startFoot.getX() + 0.5D, startFoot.getZ() + 0.5D);
            border.setSize(TEST_BORDER_SIZE);
            BlockPos outside = startFoot.offset(BORDER_GOAL_DISTANCE, 0, 0);
            if (!level.hasChunkAt(outside)) {
                lines.add("border_goal=UNLOADED_CHUNK FAIL");
                return;
            }
            // ① **谓词直断**（与地形无关，确定性）：距离 1 在界内、距离 2 在界外
            PathRequest probe = PathRequest.of(bot.getUUID().toString(), startFoot, outside, "chunk-guard-probe");
            var context = com.dddgn.alice.pathing.core.search.MovementContext
                    .live(bot, level, probe);
            BlockPos insideCell = startFoot.offset(1, 0, 0);
            BlockPos borderCell = startFoot.offset(2, 0, 0);
            boolean predicateOk = context.withinWorldBorder(insideCell)
                    && !context.withinWorldBorder(borderCell);
            // ② 搜索层：从起点朝边界方向有可走空间时，越界的边必须被跳过
            boolean localSpace = isCleanStand(level, borderCell)
                    && MovementHelper.canTraverse(level, insideCell, borderCell);
            PathPlan plan = planTo(level, startFoot, outside);
            boolean notReached = !plan.reached();
            boolean sawBorderGate = plan.diagnostics().contains("skipped_border=")
                    && !plan.diagnostics().contains("skipped_border=0");
            boolean pass = predicateOk && notReached && (!localSpace || sawBorderGate);
            lines.add("border_predicate=" + predicateOk + " not_reached=" + notReached
                    + " local_space=" + localSpace + " skipped_border=" + sawBorderGate
                    + (pass ? " PASS" : " FAIL"));
            BotLog.info("[ChunkGuard] C 目标={} status={} 谓词(内/外)={}/{} 本地空间={} diagnostics={}",
                    outside.toShortString(), plan.status(),
                    context.withinWorldBorder(insideCell), context.withinWorldBorder(borderCell),
                    localSpace, plan.diagnostics());
        } finally {
            // **立刻还原**：这是世界级状态，绝不能留在测试模式
            border.setSize(oldSize);
            border.setCenter(oldX, oldZ);
            BotLog.info("[ChunkGuard] 世界边界已还原 size={} center=({}, {})", oldSize, oldX, oldZ);
        }
    }

    private PathPlan planTo(ServerLevel level, BlockPos startFoot, BlockPos goalFoot) {
        PathRequest request = PathRequest.of(bot.getUUID().toString(), startFoot, goalFoot, "chunk-guard-check");
        return new CorePathPlanner().plan(bot, level, request);
    }

    private void finish() {
        boolean pass = allPassed();
        String summary = String.join(" ", lines) + " → " + (pass ? "PASS" : "FAIL");
        BotLog.info("[ChunkGuard] SUMMARY {}", summary);
        if (observer != null) {
            observer.sendSystemMessage(Component.literal("[alice] 区块/边界门控自检 " + summary));
        }
    }

    private boolean allPassed() {
        return !lines.isEmpty() && lines.stream().allMatch(line -> line.endsWith("PASS"));
    }
}
