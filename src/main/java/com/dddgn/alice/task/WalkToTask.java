package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.session.PathExecutionResult;
import com.dddgn.alice.pathing.core.session.PathSessionStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 轻量硬路径移动任务（**D-060：已迁移到新内核 R3/R4**）：只把 Bot 移动到指定脚位，不挖掘、不放置。
 *
 * <p>迁移要点（对照 D-045 迁移顺序，WalkTo 第一项）：
 * <ul>
 *   <li>规划：legacy `SurfacePathfinder`（零启发 Dijkstra）→ 新内核 `CorePathPlanner`（真 A*，D-040 标定）；</li>
 *   <li>执行：legacy `PathExecutor` → 新内核 `PathSession`（D-026/D-027 契约、D-047 重同步、D-052 连续推进）；</li>
 *   <li>重试：任务层 `PathRetryRunner`（D-043），每次从当前脚位重规划；</li>
 *   <li>请求类型：`PathRequest.of` = 纯通行（TRAVERSE/DIAGONAL/ASCEND/DESCEND），保持"不挖掘、不放置"语义；</li>
 *   <li>失败码保留 legacy 语义：`walk_target_not_safe` / `walk_no_path` / `walk_search_limit` /
 *       `walk_blocked` / `walk_timeout` / `walk_stale` / `walk_invalid_precondition` / `walk_execution_failed`。</li>
 * </ul>
 */
public final class WalkToTask implements Task {
    private final BotPlayer bot;
    private final BlockPos goalFoot;
    private PathRetryRunner runner;
    private String failure = "";

    public WalkToTask(BotPlayer bot, BlockPos goalFoot) {
        this.bot = bot;
        this.goalFoot = goalFoot.immutable();
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(goalFoot);
    }

    @Override
    public Status tick() {
        ServerLevel level = bot.serverLevel();
        if (runner == null) {
            if (!MovementHelper.canWalkOn(level, goalFoot)
                    || !MovementHelper.canWalkThrough(level, goalFoot)
                    || !MovementHelper.canWalkThrough(level, goalFoot.above())) {
                failure = "walk_target_not_safe:" + goalFoot.toShortString();
                BotLog.warn("[WalkToTask] target_not_safe bot={} goal={}",
                        bot.getName().getString(), goalFoot.toShortString());
                return Status.FAILED;
            }
            PathRequest request = PathRequest.of(bot.getUUID().toString(), bot.blockPosition(), goalFoot);
            runner = new PathRetryRunner(bot, request, PathRetryRunner.DEFAULT_MAX_REPLANS,
                    "walkto-" + goalFoot.getX() + "_" + goalFoot.getY() + "_" + goalFoot.getZ());
        }
        PathRetryRunner.State state = runner.tick();
        if (state == PathRetryRunner.State.RUNNING) {
            return Status.RUNNING;
        }
        if (state == PathRetryRunner.State.DONE) {
            BotLog.info("[WalkToTask] completed bot={} goalFoot={} actualFoot={} replans={}",
                    bot.getName().getString(), goalFoot.toShortString(),
                    bot.blockPosition().toShortString(), runner.replans());
            return Status.DONE;
        }
        PathExecutionResult result = runner.result();
        failure = failureCode(result);
        BotLog.warn("[WalkToTask] failed bot={} goalFoot={} reason={} status={} code={} actualFoot={} replans={}",
                bot.getName().getString(), goalFoot.toShortString(), failure,
                result == null ? "-" : result.status(), result == null ? "-" : result.failureCode(),
                bot.blockPosition().toShortString(), runner.replans());
        return Status.FAILED;
    }

    @Override
    public String failureReason() {
        return failure;
    }

    /** 把新内核终态映射回 legacy 任务失败码（保持既有调用方与日志语义）。 */
    private static String failureCode(PathExecutionResult result) {
        if (result == null) {
            return "walk_execution_failed";
        }
        String code = result.failureCode() == null ? "" : result.failureCode();
        if (code.startsWith("PLAN_UNREACHABLE")) {
            return "walk_no_path";
        }
        if (code.startsWith("PLAN_SEARCH_LIMIT")) {
            return "walk_search_limit";   // SEARCH_LIMIT ≠ UNREACHABLE（架构边界）
        }
        if (code.startsWith("PLAN_")) {
            return "walk_plan_failed";
        }
        PathSessionStatus status = result.status();
        if (status == PathSessionStatus.BLOCKED) {
            return "walk_blocked";
        }
        if (status == PathSessionStatus.TIMEOUT) {
            return "walk_timeout";
        }
        if (status == PathSessionStatus.STALE) {
            return "walk_stale";
        }
        if (status == PathSessionStatus.INVALID_PRECONDITION) {
            return "walk_invalid_precondition";
        }
        return "walk_execution_failed";
    }
}
