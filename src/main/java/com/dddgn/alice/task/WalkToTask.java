package com.dddgn.alice.task;

import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.PathExecutor;
import com.dddgn.alice.pathing.SurfacePathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** 轻量硬路径移动任务：只把 Bot 移动到指定脚位，不挖掘、不放置。 */
public final class WalkToTask implements Task {
    private final ServerPlayer bot;
    private final BlockPos goalFoot;
    private PathExecutor executor;
    private boolean planned;
    private String failure = "";

    public WalkToTask(ServerPlayer bot, BlockPos goalFoot) {
        this.bot = bot;
        this.goalFoot = goalFoot.immutable();
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(goalFoot);
    }

    @Override
    public Status tick() {
        ServerLevel level = (ServerLevel) bot.level();
        if (!planned) {
            planned = true;
            if (!MovementHelper.canWalkOn(level, goalFoot)
                    || !MovementHelper.canWalkThrough(level, goalFoot)
                    || !MovementHelper.canWalkThrough(level, goalFoot.above())) {
                failure = "walk_target_not_safe:" + goalFoot.toShortString();
                return Status.FAILED;
            }
            SurfacePathfinder.Result result = SurfacePathfinder.find(bot, bot.blockPosition(), goalFoot);
            BotLog.info("[WalkToTask] planned from={} to={} status={} pathSize={} expanded={} cost={}",
                    bot.blockPosition().toShortString(), goalFoot.toShortString(), result.status(),
                    result.path().size(), result.expandedNodes(), result.totalCost());
            if (!result.reachable()) {
                failure = result.inconclusive() ? "walk_search_limit" : "walk_no_path";
                return Status.FAILED;
            }
            if (result.path().isEmpty()) {
                return Status.DONE;
            }
            executor = new PathExecutor(bot, result.path());
        }

        PathExecutor.Status status = executor.tick();
        if (status == PathExecutor.Status.DONE) {
            BotLog.info("[WalkToTask] completed bot={} goalFoot={} actualFoot={}",
                    bot.getName().getString(), goalFoot.toShortString(), bot.blockPosition().toShortString());
            return Status.DONE;
        }
        if (status == PathExecutor.Status.FAILED) {
            failure = executor.wasObstructed() ? "walk_blocked" : "walk_execution_failed";
            BotLog.warn("[WalkToTask] failed bot={} goalFoot={} reason={} actualFoot={}",
                    bot.getName().getString(), goalFoot.toShortString(), failure, bot.blockPosition().toShortString());
            return Status.FAILED;
        }
        return Status.RUNNING;
    }

    @Override
    public String failureReason() {
        return failure;
    }
}
