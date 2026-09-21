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
public class WalkToTask implements Task {   // 非 final：S-1 的逃生任务 SurvivalExitTask 复用同一套执行
    private final BotPlayer bot;
    private final BlockPos goalFoot;
    private PathRetryRunner runner;
    private String failure = "";

    public WalkToTask(BotPlayer bot, BlockPos goalFoot) {
        this.bot = bot;
        this.goalFoot = goalFoot.immutable();
    }

    /**
     * **本次走路用哪个规划请求**（D-241 加的钩子）。默认 = 纯通行（`PathRequest.of`，D-076 红线）；
     * 只有逃生这类**显式登记过**的入口才覆写它（见 {@code SurvivalExitTask}），
     * 且必须给出自己的理由码 + 预算上限（不许把"允许改世界"泄漏到普通通行的所有子请求上）。
     */
    protected PathRequest buildRequest(BotPlayer walker, BlockPos goal) {
        return PathRequest.of(walker.getUUID().toString(),
                MovementHelper.footCell(walker.serverLevel(), walker), goal, "walk-to");
    }

    /**
     * 本任务驱动的 bot（**只读**；给子类的 tick 钩子用 —— 例如 `SurvivalExitTask` 的 `D-383` 空气告警
     * 需要在走位**之后**读"眼在水里/空气"并覆盖跳跃输入）。
     */
    protected final BotPlayer bot() {
        return bot;
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(goalFoot);
    }

    @Override
    public Status tick() {
        ServerLevel level = bot.serverLevel();
        if (runner == null) {
            // ⚠️ D-331（2026-09-19 实测）：**必须先问加载状态，再读方块**。
            // `Level.getBlockState` 对未加载区块会**同步加载**（`getChunkAt`）—— 于是"目标安全预检"会在
            // 服务端 tick 线程上、在**任何预算/超时之外**把几百格外的区块拉进来，把内核
            // `AStarMovementSearch:64` 的 `GOAL_NOT_LOADED` 守卫**整个绕过**
            //（实测：规划前 `hasChunkAt(goal)=false` → 首 tick（含一次 plan）之后 = `true`；
            //  表现为"远距离一规划就卡"，而搜索本身只要 1~16 ms）。
            // 语义与内核保持一致：未加载 ≠ 不安全 ≠ 不可达 ⇒ 复用既有 `walk_goal_unloaded`
            //（`failureCode()` 里 `PLAN_GOAL_NOT_LOADED` 映射的就是它），调用方可稍后重试/先靠近。
            if (!level.hasChunkAt(goalFoot)) {
                failure = "walk_goal_unloaded:" + goalFoot.toShortString();
                BotLog.warn("[WalkToTask] goal_chunk_not_loaded bot={} goal={} code=walk_goal_unloaded"
                                + "（D-331：不读方块 ⇒ 不触发同步加载；由上层粗目标/分段接近后再走）",
                        bot.getName().getString(), goalFoot.toShortString());
                return Status.FAILED;
            }
            if (!MovementHelper.canWalkOn(level, goalFoot)
                    || !MovementHelper.canWalkThrough(level, goalFoot)
                    || !MovementHelper.canWalkThrough(level, goalFoot.above())) {
                failure = "walk_target_not_safe:" + goalFoot.toShortString();
                BotLog.warn("[WalkToTask] target_not_safe bot={} goal={}",
                        bot.getName().getString(), goalFoot.toShortString());
                return Status.FAILED;
            }
            PathRequest request = buildRequest(bot, goalFoot);
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
            // ⭐ `D-347`（运行账）：走路任务的"到达"就是它**自己的到达**（路径执行器报 DONE）
            // —— 这是唯一能证明"到位"的那一刻（不是"发出了请求"）。
            com.dddgn.alice.bot.TaskMetrics.arrived(taskName());
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
        if (code.startsWith("PLAN_GOAL_NOT_LOADED")) {
            // S-2：目标区块没加载 ≠ 不可达 ⇒ 独立码，调用方可以稍后重试/先靠近
            return "walk_goal_unloaded";
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

    /** K-3：把"当前寻路段是否安全"透传给取消方（`/alice stop` 会据此延后到安全点）。 */
    @Override
    public boolean safeToCancel() {
        return runner == null || runner.safeToCancel();
    }
}
