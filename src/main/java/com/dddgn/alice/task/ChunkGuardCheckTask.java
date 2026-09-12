package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.search.PlanningStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * **未加载区块 / 世界边界准入自检**（{@code alice:chunk_guard_check}，S-2 / P1-A）：一次右键跑三个用例。
 *
 * <p>背景（`RISK_SYSTEM_REVIEW_20260910.md` §2，反编译证据）：服务端在**未加载区块**上
 * `getBlockState` 会**同步加载/生成区块并阻塞主线程** —— 所以搜索层既不该把"没加载"错报成
 * "到不了"，也不该为了看一眼而把区块拉起来。对照 Baritone：`worldContainsLoadedChunk` /
 * `isLoaded`（`getChunk(..., FULL, false)` 从不加载）+ `AStarPathFinder:105-112` 跨区块门控。
 *
 * <pre>
 * A 目标在未加载区块 → status=GOAL_NOT_LOADED（独立状态，≠UNREACHABLE/SEARCH_LIMIT）
 *                     且**规划前后那一格区块都仍未加载**（证明没有同步加载副作用）
 * B 正对照：身边已加载的可站格 → REACHED（证明门控没有把正常寻路掐死）
 * C 世界边界：临时把边界缩小到 8 格，目标放在边界外**已加载**区块 → 不可 REACHED
 *             且诊断里出现 skipped_border（随后**立刻还原边界**）
 * </pre>
 */
public class ChunkGuardCheckTask implements Task {

    /** 用例 A 的远端距离：±512 格必然在未加载区块（远超任何视距/模拟距离）。 */
    private static final int FAR_DISTANCE = 512;
    /** 用例 C 用的临时边界尺寸（以 bot 为中心 8 格）。 */
    private static final double TEST_BORDER_SIZE = 8.0D;
    /** 用例 C 的目标距离：在已加载区块内、但在缩小后的边界之外。 */
    private static final int BORDER_GOAL_DISTANCE = 24;

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> lines = new ArrayList<>();
    private boolean done;

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
        BlockPos startFoot = com.dddgn.alice.pathing.MovementHelper.footCell(level, bot);

        caseFarGoal(level, startFoot);
        caseNearGoal(level, startFoot);
        caseWorldBorder(level, startFoot);

        boolean pass = allPassed();
        String summary = String.join(" ", lines) + " → " + (pass ? "PASS" : "FAIL");
        BotLog.info("[ChunkGuard] SUMMARY {}", summary);
        if (observer != null) {
            observer.sendSystemMessage(Component.literal("[alice] 区块/边界门控自检 " + summary));
        }
        return pass ? Status.DONE : Status.FAILED;
    }

    // ==================== 用例 A：目标在未加载区块 ====================

    private void caseFarGoal(ServerLevel level, BlockPos startFoot) {
        BlockPos far = startFoot.offset(FAR_DISTANCE, 0, FAR_DISTANCE);
        if (level.hasChunkAt(far)) {
            // 极端情况（有人把视距拉得极大）：如实标记前提不成立，而不是硬判失败
            lines.add("far_goal_precondition=NOT_UNLOADED");
            BotLog.warn("[ChunkGuard] 用例 A 前提不成立：{} 竟然已加载（视距过大？）",
                    far.toShortString());
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

    // ==================== 用例 B：正对照（身边已加载） ====================

    private void caseNearGoal(ServerLevel level, BlockPos startFoot) {
        BlockPos near = null;
        for (BlockPos candidate : BlockPos.betweenClosed(startFoot.offset(-3, -1, -3), startFoot.offset(3, 1, 3))) {
            BlockPos foot = candidate.immutable();
            if (foot.equals(startFoot)) {
                continue;
            }
            if (com.dddgn.alice.pathing.MovementHelper.canWalkOn(level, foot)
                    && com.dddgn.alice.pathing.MovementHelper.canWalkThrough(level, foot)
                    && com.dddgn.alice.pathing.MovementHelper.canWalkThrough(level, foot.above())) {
                near = foot;
                break;
            }
        }
        if (near == null) {
            lines.add("near_goal=NO_CANDIDATE FAIL");
            return;
        }
        PathPlan plan = planTo(level, startFoot, near);
        boolean ok = plan.reached();
        lines.add("near_goal=" + plan.status().name() + (ok ? " PASS" : " FAIL"));
        BotLog.info("[ChunkGuard] B 正对照 目标={} status={} movements={}",
                near.toShortString(), plan.status(), plan.movements().size());
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
                lines.add("border_goal_precondition=UNLOADED");   // 目标没加载 ⇒ 会先撞上用例 A 的门控
                return;
            }
            PathPlan plan = planTo(level, startFoot, outside);
            boolean notReached = !plan.reached();
            boolean sawBorderGate = plan.diagnostics().contains("skipped_border=")
                    && !plan.diagnostics().contains("skipped_border=0");
            lines.add("border_goal=" + plan.status().name() + " skipped_border=" + sawBorderGate
                    + (notReached && sawBorderGate ? " PASS" : " FAIL"));
            BotLog.info("[ChunkGuard] C 目标={} status={} diagnostics={}",
                    outside.toShortString(), plan.status(), plan.diagnostics());
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

    private boolean allPassed() {
        return !lines.isEmpty() && lines.stream().allMatch(line -> line.endsWith("PASS"));
    }
}
