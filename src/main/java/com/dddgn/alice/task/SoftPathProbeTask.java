package com.dddgn.alice.task;

import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.SoftMovementPrimitive;
import com.dddgn.alice.pathing.SurfacePathfinder;
import com.dddgn.alice.survival.HazardState;
import com.dddgn.alice.survival.SurvivalSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * SOFT_SURFACE 连续脚位段实验：复用 SurfacePathfinder，只以 NATIVE_TRAVEL 逐段执行。
 * 不接入 MineTask；每段必须确认真实脚位、支撑和 onGround 后才推进。
 */
public final class SoftPathProbeTask implements Task {
    private static final double ARRIVE = 0.25D;
    private static final int MAX_TICKS = 400;
    private static final int MAX_SETTLE_TICKS = 30;

    private final ServerPlayer bot;
    private final BlockPos target;
    private List<BlockPos> path;
    private int index;
    private int elapsed;
    private int settleTicks;
    private String failure = "";

    public SoftPathProbeTask(ServerPlayer bot, BlockPos target) {
        this.bot = bot;
        this.target = target.immutable();
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(target);
    }

    @Override
    public Status tick() {
        ServerLevel level = (ServerLevel) bot.level();
        HazardState hazard = SurvivalSystem.tick(bot);
        if (SurvivalSystem.shouldInterrupt(hazard)) {
            failure = SurvivalSystem.interruptionReason(hazard);
            return Status.FAILED;
        }
        if (hazard.type() != com.dddgn.alice.survival.HazardType.NONE) {
            failure = "soft_path_hazard:" + hazard.type();
            return Status.FAILED;
        }
        if (path == null) {
            SurfacePathfinder.Result result = SurfacePathfinder.find(level, bot.blockPosition(), target);
            if (!result.reachable()) {
                failure = result.inconclusive() ? "soft_path_search_limit" : "soft_path_no_path";
                return Status.FAILED;
            }
            path = result.path();
            BotLog.info("软路径探针: target={} segments={} expanded={}",
                    target.toShortString(), path.size(), result.expandedNodes());
        }
        if (index >= path.size()) {
            return Status.DONE;
        }
        elapsed++;
        if (elapsed > MAX_TICKS) {
            failure = "soft_path_timeout";
            return Status.FAILED;
        }

        BlockPos segment = path.get(index);
        double goalX = segment.getX() + 0.5D;
        double goalZ = segment.getZ() + 0.5D;
        double dx = goalX - bot.getX();
        double dz = goalZ - bot.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance > ARRIVE) {
            SoftMovementPrimitive.applyToward(bot, goalX, goalZ, distance,
                    SoftMovementPrimitive.Backend.NATIVE_TRAVEL);
            return Status.RUNNING;
        }

        BlockPos actualFoot = bot.blockPosition();
        boolean supported = MovementHelper.canWalkOn(level, actualFoot);
        if (actualFoot.equals(segment) && supported && bot.onGround()) {
            BotLog.info("软路径段完成: {}/{} foot={}", index + 1, path.size(), actualFoot.toShortString());
            index++;
            settleTicks = 0;
            return Status.RUNNING;
        }
        SoftMovementPrimitive.settle(bot);
        settleTicks++;
        if (settleTicks > MAX_SETTLE_TICKS) {
            failure = "soft_path_unsettled: segment=" + segment.toShortString()
                    + " foot=" + actualFoot.toShortString() + " onGround=" + bot.onGround()
                    + " support=" + supported;
            return Status.FAILED;
        }
        return Status.RUNNING;
    }

    @Override
    public String failureReason() {
        return failure;
    }
}
