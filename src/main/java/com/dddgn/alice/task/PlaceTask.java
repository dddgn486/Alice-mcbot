package com.dddgn.alice.task;

import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.action.BlockInteraction;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.session.PathExecutionResult;
import com.dddgn.alice.pathing.core.session.PathSessionStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 独立方块放置任务（**D-063：已迁移到新内核**）：走到目标旁的站位，在目标格放置一个一次性方块。
 *
 * <p>迁移要点（D-045 第三项）：
 * <ul>
 *   <li>站位寻路：legacy `AStarPathfinder` + `PathExecutor` → `CorePathPlanner` + `PathRetryRunner`
 *       + `PathSession`（`PathRequest.of` 纯通行）；</li>
 *   <li>放置动作：legacy 直接 `level.setBlock(...)`（无视库存/支撑面/服务端校验）→
 *       **`BlockInteraction.placeAt`**（快捷栏一次性方块 + 支撑面扫描 + `InteractionResult` 校验 +
 *       服务端世界复核，D-037/D-043 语义）；</li>
 *   <li>失败码：`place_no_stand` / `place_no_path` / `place_path_failed`（保留）+
 *       `place_resource_unavailable` / `place_no_valid_face`（新放置路径）+ 新内核终态映射
 *       `place_blocked` / `place_timeout` / `place_stale` / `place_invalid_precondition` /
 *       `place_execution_failed`；</li>
 *   <li>取消 legacy 的 `place_line_of_sight` 手工视线检查：放置可达性与支撑面由 `placeAt` 统一判定
 *       （与 `PLACE_STEP_AND_TRAVERSE` / `PILLAR` 一致）。</li>
 * </ul>
 */
public final class PlaceTask implements Task {
    private static final double MAX_REACH = 4.5D;
    private static final int MAX_CANDIDATES = 16;

    private final BotPlayer bot;
    private final BlockPos target;
    private List<BlockPos> candidates;
    private BlockPos stand;
    private PathRetryRunner runner;
    private String failure = "";

    public PlaceTask(BotPlayer bot, BlockPos target) {
        this.bot = bot;
        this.target = target.immutable();
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(target);
    }

    @Override
    public String failureReason() {
        return failure;
    }

    @Override
    public Status tick() {
        ServerLevel level = bot.serverLevel();
        // 目标已被放置（或不再可替换）→ 任务达成
        if (!level.getBlockState(target).canBeReplaced()) {
            return Status.DONE;
        }
        if (stand == null && !selectStand(level)) {
            return Status.FAILED;
        }
        if (runner == null && !bot.blockPosition().equals(stand)) {
            runner = new PathRetryRunner(bot, request(stand),
                    PathRetryRunner.DEFAULT_MAX_REPLANS, "place-" + target.getX() + "_" + target.getY()
                    + "_" + target.getZ());
            BotLog.info("[PlaceTask] walk_to_stand target={} stand={} feet={}",
                    target.toShortString(), stand.toShortString(), bot.blockPosition().toShortString());
        }
        if (runner != null) {
            PathRetryRunner.State state = runner.tick();
            if (state == PathRetryRunner.State.RUNNING) {
                return Status.RUNNING;
            }
            if (state == PathRetryRunner.State.FAILED) {
                failure = failureCode(runner.result());
                BotLog.warn("[PlaceTask] walk_failed target={} stand={} reason={} feet={}",
                        target.toShortString(), stand.toShortString(), failure,
                        bot.blockPosition().toShortString());
                return Status.FAILED;
            }
            runner = null;
        }
        // 到位 → 用统一放置路径放置
        if (BlockInteraction.findPlaceableSlot(bot) < 0) {
            failure = "place_resource_unavailable";
            return Status.FAILED;
        }
        BlockInteraction.PlaceResult result = BlockInteraction.placeAt(bot, level, target, false,
                WriteGrant.of(taskName(), WriteReason.STEP_PLACEMENT));
        if (result != BlockInteraction.PlaceResult.PLACED) {
            failure = "place_no_valid_face";
            BotLog.warn("[PlaceTask] place_failed target={} stand={} feet={}",
                    target.toShortString(), stand.toShortString(), bot.blockPosition().toShortString());
            return Status.FAILED;
        }
        BotLog.info("[PlaceTask] completed target={} stand={} feet={}",
                target.toShortString(), stand.toShortString(), bot.blockPosition().toShortString());
        return Status.DONE;
    }

    /** 选择可站位：按到目标的距离排序，取第一个"新内核可规划到达"的脚位。 */
    private boolean selectStand(ServerLevel level) {
        if (candidates == null) {
            candidates = pickCandidates(level);
            if (candidates.isEmpty()) {
                failure = "place_no_stand";
                return false;
            }
        }
        for (BlockPos candidate : candidates) {
            if (candidate.equals(bot.blockPosition())) {
                stand = candidate;
                return true;
            }
            PathPlan plan = new CorePathPlanner().plan(bot, level, request(candidate));
            if (plan.reached()) {
                stand = candidate;
                return true;
            }
        }
        failure = "place_no_path";
        return false;
    }

    private PathRequest request(BlockPos goalFoot) {
        return PathRequest.of(bot.getUUID().toString(), bot.blockPosition(), goalFoot);
    }

    /** 生成可站位候选（可达距离内、可站可通行），按到目标的距离排序并截断。 */
    private List<BlockPos> pickCandidates(ServerLevel level) {
        List<BlockPos> result = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -4; dy <= 3; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos standPos = target.offset(dx, dy, dz);
                    if (standPos.equals(target) || standPos.above().equals(target)) {
                        continue;
                    }
                    if (!MovementHelper.canWalkOn(level, standPos)
                            || !MovementHelper.canWalkThrough(level, standPos)
                            || !MovementHelper.canWalkThrough(level, standPos.above())) {
                        continue;
                    }
                    Vec3 eye = new Vec3(standPos.getX() + 0.5D, standPos.getY() + 1.62D,
                            standPos.getZ() + 0.5D);
                    if (eye.distanceTo(target.getCenter()) <= MAX_REACH - 0.3D) {
                        result.add(standPos.immutable());
                    }
                }
            }
        }
        result.sort(Comparator.comparingDouble(p -> p.distSqr(target)));
        return result.size() > MAX_CANDIDATES ? result.subList(0, MAX_CANDIDATES) : result;
    }

    /** 新内核终态 → legacy 任务失败码。 */
    private static String failureCode(PathExecutionResult result) {
        if (result == null) {
            return "place_path_failed";
        }
        String code = result.failureCode() == null ? "" : result.failureCode();
        if (code.startsWith("PLAN_UNREACHABLE")) {
            return "place_no_path";
        }
        if (code.startsWith("PLAN_SEARCH_LIMIT")) {
            return "place_search_limit";
        }
        if (code.startsWith("PLAN_")) {
            return "place_path_failed";
        }
        PathSessionStatus status = result.status();
        if (status == PathSessionStatus.BLOCKED) {
            return "place_blocked";
        }
        if (status == PathSessionStatus.TIMEOUT) {
            return "place_timeout";
        }
        if (status == PathSessionStatus.STALE) {
            return "place_stale";
        }
        if (status == PathSessionStatus.INVALID_PRECONDITION) {
            return "place_invalid_precondition";
        }
        return "place_path_failed";
    }
}
