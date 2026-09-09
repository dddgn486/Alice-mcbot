package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.session.PathExecutionResult;
import com.dddgn.alice.pathing.core.session.PathSessionStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;
import java.util.UUID;

/**
 * 跟随任务（**D-062：已迁移到新内核 R3/R4**）：跟随同维度在线玩家，保持约 2 格距离。
 *
 * <p>迁移要点（对照 D-045 迁移顺序，Follow 第二项）：
 * <ul>
 *   <li>规划/执行：legacy `SurfacePathfinder` + `BasicMovement` 直驱 → 新内核
 *       `CorePathPlanner` + `PathRetryRunner` + `PathSession`；</li>
 *   <li>请求类型：`PathRequest.of`（纯通行，不挖不放）；</li>
 *   <li>**重规划触发**：无路径 / 目标脚位漂移 ≥ {@link #REPLAN_GOAL_DRIFT} 格 / 会话失败。
 *       legacy 的"每 10 tick 无条件重规划"不再需要——世界变化由新内核的会话健康检查
 *       （D-047 目标有效性 + 前瞻封死 + 重同步）覆盖，无条件重规划反而会打断 D-052 的连续推进；</li>
 *   <li>失败码保留 legacy 语义：`follow_target_unavailable` / `follow_distance_limit` /
 *       `follow_target_not_on_safe_surface` / `follow_no_path` / `follow_search_limit` /
 *       `follow_blocked` / `follow_timeout` / `follow_stale` / `follow_execution_failed`；</li>
 *   <li>legacy 的 `follow_unsettled` 分支取消：落点稳定已由新内核完成契约（D-026/D-027/D-056）保证。</li>
 *   <li>**目标锚定修复（用户 2026-09-09 报告的缺陷）**：legacy 直接用 `target.blockPosition()` 当规划目标——
 *       玩家原地一跳，脚位立刻变成空中格（无支撑），bot 会判成"目标不可站/不可达"。
 *       现在改为：① 只在目标**落地且脚位可站**时更新锚点 `lastSafeTargetFoot`（空中保持旧锚点）；
 *       ② 规划目标取锚点**相邻的可站格**（同层优先、最多向下 3 层），即"站到目标旁边"，
 *       语义等价 Baritone `FollowProcess` 的 `GoalNear(pos, followRadius)`，
 *       但保留 Alice 精确脚位目标的可采纳启发式（D-040）。</li>
 * </ul>
 *
 * <p>维生危险中断由 {@code BotSession.tick} 统一处理（本任务不再重复调用 `SurvivalSystem`）。
 */
public final class FollowTask implements Task {
    /** 跟随时保持的距离（格）。 */
    private static final double FOLLOW_DISTANCE = 2.0D;
    /** 目标超过该距离即判定跟随失败（格）。 */
    private static final int MAX_TARGET_DISTANCE = 24;
    /** 目标脚位漂移达到该曼哈顿距离才重新规划（滞回，避免目标每走一格就打断当前路径）。 */
    private static final int REPLAN_GOAL_DRIFT = 2;
    /** 相邻站位的向下搜索层数（目标站在柱子/台阶上时，允许站到它的下方邻格）。 */
    private static final int STANDOFF_DOWN_LEVELS = 3;
    private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final BotPlayer bot;
    private final UUID targetUuid;
    private PathRetryRunner runner;
    private BlockPos runnerGoal;
    private String failure = "";
    private int ticks;
    private int replans;
    private double minDistance = Double.MAX_VALUE;
    /** 目标最后一次"落地且脚位可站"的脚位（空中保持旧值，修复原地跳跃缺陷）。 */
    private BlockPos lastSafeTargetFoot;
    private boolean targetAirborneLogged;

    public FollowTask(BotPlayer bot, ServerPlayer target) {
        this.bot = bot;
        this.targetUuid = target.getUUID();
    }

    @Override
    public TaskTarget target() {
        ServerPlayer target = targetPlayer();
        return target == null ? TaskTarget.block(bot.blockPosition()) : TaskTarget.entity(target.getId());
    }

    @Override
    public Status tick() {
        ticks++;
        ServerLevel level = bot.serverLevel();
        ServerPlayer target = targetPlayer();
        if (target == null || target.level() != level || !target.isAlive()) {
            failure = "follow_target_unavailable";
            cancelRunner();
            BotLog.warn("[Follow] failed code={}", failure);
            return Status.FAILED;
        }
        double distance = Math.sqrt(bot.distanceToSqr(target));
        minDistance = Math.min(minDistance, distance);
        if (distance > MAX_TARGET_DISTANCE) {
            failure = "follow_distance_limit";
            cancelRunner();
            BotLog.warn("[Follow] failed code={} dist={}", failure, fmt(distance));
            return Status.FAILED;
        }
        if (distance <= FOLLOW_DISTANCE) {
            // 已跟上：停止推进待命；目标再次走远时会重新规划
            cancelRunner();
            return Status.RUNNING;
        }
        // 目标锚定：只在目标落地且脚位可站时更新（空中保持旧锚点）
        if (target.onGround()) {
            BlockPos targetFoot = target.blockPosition();
            if (isSafeFoot(level, targetFoot)) {
                lastSafeTargetFoot = targetFoot;
                targetAirborneLogged = false;
            } else if (lastSafeTargetFoot == null) {
                failure = "follow_target_not_on_safe_surface";
                cancelRunner();
                BotLog.warn("[Follow] failed code={} targetFoot={}", failure, targetFoot.toShortString());
                return Status.FAILED;
            }
        } else if (!targetAirborneLogged) {
            targetAirborneLogged = true;
            BotLog.info("[Follow] target_airborne hold_goal={}",
                    lastSafeTargetFoot == null ? "-" : lastSafeTargetFoot.toShortString());
        }
        if (lastSafeTargetFoot == null) {
            return Status.RUNNING;   // 还没拿到可站锚点（刚起跳/刚传送），等下一 tick
        }
        BlockPos goalFoot = chooseStandoff(level, lastSafeTargetFoot);
        if (bot.blockPosition().equals(goalFoot)) {
            // 已经站到目标旁边：待命（避免每 tick 重复规划）
            cancelRunner();
            return Status.RUNNING;
        }
        if (runner == null || runnerGoal == null
                || runnerGoal.distManhattan(goalFoot) >= REPLAN_GOAL_DRIFT) {
            String reason = runner == null ? "initial" : "goal_moved";
            cancelRunner();
            runner = new PathRetryRunner(bot, request(goalFoot),
                    PathRetryRunner.DEFAULT_MAX_REPLANS, "follow");
            runnerGoal = goalFoot;
            replans++;
            BotLog.info("[Follow] replan reason={} goal={} dist={} feet={}",
                    reason, goalFoot.toShortString(), fmt(distance),
                    bot.blockPosition().toShortString());
        }
        PathRetryRunner.State state = runner.tick();
        if (state == PathRetryRunner.State.RUNNING) {
            return Status.RUNNING;
        }
        if (state == PathRetryRunner.State.DONE) {
            // 到达旧目标；目标若已移动，下一 tick 会重新规划
            runner = null;
            runnerGoal = null;
            return Status.RUNNING;
        }
        PathExecutionResult result = runner.result();
        failure = failureCode(result);
        BotLog.warn("[Follow] failed code={} status={} code_detail={} dist={} feet={} replans={}",
                failure, result == null ? "-" : result.status(),
                result == null ? "-" : result.failureCode(), fmt(distance),
                bot.blockPosition().toShortString(), replans);
        cancelRunner();
        return Status.FAILED;
    }

    /** 外部取消（{@code BotManager.stopFollow} 入口）：停止推进并清理。 */
    public void cancel() {
        cancelRunner();
    }

    /** 观测摘要（停止跟随/日志用）。 */
    public String summary() {
        return "ticks=" + ticks + " replans=" + replans
                + " minDist=" + (minDistance == Double.MAX_VALUE ? "-" : fmt(minDistance));
    }

    @Override
    public String failureReason() {
        return failure;
    }

    private PathRequest request(BlockPos goalFoot) {
        return PathRequest.of(bot.getUUID().toString(), bot.blockPosition(), goalFoot);
    }

    /** 脚位是否可站（支撑 + 身体/头部净空）。 */
    private static boolean isSafeFoot(ServerLevel level, BlockPos foot) {
        return MovementHelper.canWalkOn(level, foot)
                && MovementHelper.canWalkThrough(level, foot)
                && MovementHelper.canWalkThrough(level, foot.above());
    }

    /**
     * 站位选择：锚点相邻的可站格（同层优先，最多向下 {@link #STANDOFF_DOWN_LEVELS} 层），
     * 取离 bot 最近的一个；都不行时退回锚点本身。
     */
    private BlockPos chooseStandoff(ServerLevel level, BlockPos anchor) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int[] d : CARDINAL) {
            for (int dy = 0; dy >= -STANDOFF_DOWN_LEVELS; dy--) {
                BlockPos candidate = anchor.offset(d[0], dy, d[1]);
                if (!isSafeFoot(level, candidate)) {
                    continue;
                }
                double distance = bot.blockPosition().distSqr(candidate);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
                break;   // 该方向取最高的可站层
            }
        }
        return best != null ? best : anchor;
    }

    private void cancelRunner() {
        if (runner != null) {
            runner.cancel();
            runner = null;
        }
        runnerGoal = null;
        bot.controller().stopMovement();
    }

    private ServerPlayer targetPlayer() {
        return bot.getServer().getPlayerList().getPlayer(targetUuid);
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** 把新内核终态映射回 legacy 任务失败码。 */
    private static String failureCode(PathExecutionResult result) {
        if (result == null) {
            return "follow_execution_failed";
        }
        String code = result.failureCode() == null ? "" : result.failureCode();
        if (code.startsWith("PLAN_UNREACHABLE")) {
            return "follow_no_path";
        }
        if (code.startsWith("PLAN_SEARCH_LIMIT")) {
            return "follow_search_limit";   // SEARCH_LIMIT ≠ UNREACHABLE（架构边界）
        }
        if (code.startsWith("PLAN_")) {
            return "follow_plan_failed";
        }
        PathSessionStatus status = result.status();
        if (status == PathSessionStatus.BLOCKED) {
            return "follow_blocked";
        }
        if (status == PathSessionStatus.TIMEOUT) {
            return "follow_timeout";
        }
        if (status == PathSessionStatus.STALE) {
            return "follow_stale";
        }
        if (status == PathSessionStatus.INVALID_PRECONDITION) {
            return "follow_invalid_precondition";
        }
        return "follow_execution_failed";
    }
}
