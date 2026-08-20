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

    private enum SegmentAction {
        HORIZONTAL,
        ASCEND,
        DESCEND
    }

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
            BotLog.info("软路径探针: target={} cost={} segments={} path={} expanded={}",
                    target.toShortString(), String.format(java.util.Locale.ROOT, "%.2f", result.totalCost()),
                    path.size(), path, result.expandedNodes());
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
        BlockPos from = index == 0 ? bot.blockPosition() : path.get(index - 1);
        SegmentAction action = segmentAction(level, from, segment);
        if (action == null) {
            return Status.FAILED;
        }
        double goalX = segment.getX() + 0.5D;
        double goalZ = segment.getZ() + 0.5D;
        double dx = goalX - bot.getX();
        double dz = goalZ - bot.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance > ARRIVE) {
            SoftMovementPrimitive.Step step = action == SegmentAction.ASCEND
                    ? SoftMovementPrimitive.applyJumpToward(bot, goalX, goalZ, distance)
                    : SoftMovementPrimitive.applyToward(bot, goalX, goalZ, distance,
                    SoftMovementPrimitive.Backend.NATIVE_TRAVEL);
            if (elapsed % 10 == 0) {
                BotLog.info("软路径段动作: {}/{} action={} from={} to={} step={} onGround={}",
                        index + 1, path.size(), action, from.toShortString(), segment.toShortString(),
                        String.format(java.util.Locale.ROOT, "%.3f", step.distance()), bot.onGround());
            }
            return Status.RUNNING;
        }

        BlockPos actualFoot = bot.blockPosition();
        boolean supported = MovementHelper.isStandingAtFootPos(level, bot, segment);
        if (supported && bot.onGround()) {
            BotLog.info("软路径段完成: {}/{} action={} from={} to={} foot={} onGround={}",
                    index + 1, path.size(), action, from.toShortString(), segment.toShortString(),
                    actualFoot.toShortString(), bot.onGround());
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

    /** 仅接受 SurfacePathfinder 允许的相邻段；异常路径绝不由探针自行补救。 */
    private SegmentAction segmentAction(ServerLevel level, BlockPos from, BlockPos to) {
        int dy = to.getY() - from.getY();
        int dx = Math.abs(to.getX() - from.getX());
        int dz = Math.abs(to.getZ() - from.getZ());
        if (dx > 1 || dz > 1 || (dx == 0 && dz == 0 && dy != 0)) {
            failure = invalidSegmentFailure(level, from, to, "non_adjacent");
            return null;
        }
        if (dy == 0 && MovementHelper.canTraverse(level, from, to)) {
            return SegmentAction.HORIZONTAL;
        }
        if (dy == 1 && MovementHelper.canAscend(level, from, to)) {
            return SegmentAction.ASCEND;
        }
        if (dy == -1 && MovementHelper.canDescend(level, from, to)) {
            return SegmentAction.DESCEND;
        }
        failure = invalidSegmentFailure(level, from, to, "geometry_mismatch:dy=" + dy);
        return null;
    }

    private String invalidSegmentFailure(ServerLevel level, BlockPos from, BlockPos to, String reason) {
        BlockPos foot = bot.blockPosition();
        return "soft_path_invalid_segment: " + reason + " from=" + from.toShortString()
                + " to=" + to.toShortString() + " foot=" + foot.toShortString()
                + " onGround=" + bot.onGround()
                + " support=" + MovementHelper.isStandingOnSupport(level, bot);
    }

    @Override
    public String failureReason() {
        return failure;
    }
}
