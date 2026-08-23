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
    private static final int MAX_REPLAN = 3;
    private static final double HORIZONTAL_DEVIATION_THRESHOLD = 2.0D;
    private static final double VERTICAL_DEVIATION_THRESHOLD = 1.5D;
    private static final int NO_PROGRESS_THRESHOLD = 20;
    private static final double VELOCITY_DISRUPTION_HORIZONTAL = 0.3D;
    private static final double VELOCITY_DISRUPTION_VERTICAL = 0.5D;
    private static final int MAX_REVALIDATE_TICKS = 20;

    private final ServerPlayer bot;
    private final BlockPos target;
    private List<BlockPos> path;
    private int index;
    private int elapsed;
    private int settleTicks;
    private SegmentAction settlingAction;
    private String failure = "";

    // P1 physics observation fields
    private net.minecraft.world.phys.Vec3 lastDeltaMovement = net.minecraft.world.phys.Vec3.ZERO;
    private net.minecraft.world.phys.Vec3 currentDeltaMovement = net.minecraft.world.phys.Vec3.ZERO;
    private double lastSupportTopY = Double.NaN;
    private double currentSupportTopY = Double.NaN;
    private boolean horizontalCollision;
    private boolean verticalCollisionBelow;
    private int noProgressTicks;
    private int revalidateTicks;
    private int replanCount;
    private boolean velocityDisruption;
    private double lastHorizontalPos;

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
            lastHorizontalPos = Math.sqrt(bot.getX() * bot.getX() + bot.getZ() * bot.getZ());
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

        // P1: Update physics observation every tick
        lastDeltaMovement = currentDeltaMovement;
        currentDeltaMovement = bot.getDeltaMovement();
        lastSupportTopY = currentSupportTopY;
        currentSupportTopY = MovementHelper.supportTopY(level, bot.blockPosition().below());
        horizontalCollision = bot.horizontalCollision;
        verticalCollisionBelow = bot.verticalCollisionBelow;

        // P1: Detect velocity disruption (knockback/damage)
        double deltaH = currentDeltaMovement.horizontalDistance() - lastDeltaMovement.horizontalDistance();
        double deltaV = currentDeltaMovement.y - lastDeltaMovement.y;
        if (Math.abs(deltaH) > VELOCITY_DISRUPTION_HORIZONTAL || Math.abs(deltaV) > VELOCITY_DISRUPTION_VERTICAL) {
            velocityDisruption = true;
            BotLog.info("soft_phys_disruption: velocityChange=({},{},{}) horizontal={} vertical={}",
                    String.format(java.util.Locale.ROOT, "%.3f", currentDeltaMovement.x - lastDeltaMovement.x),
                    String.format(java.util.Locale.ROOT, "%.3f", deltaV),
                    String.format(java.util.Locale.ROOT, "%.3f", currentDeltaMovement.z - lastDeltaMovement.z),
                    String.format(java.util.Locale.ROOT, "%.3f", deltaH),
                    String.format(java.util.Locale.ROOT, "%.3f", deltaV));
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

        // P1: Horizontal/vertical deviation
        double hDev = distance;
        double vDev = Math.abs(bot.getY() - segment.getY());

        if (distance > ARRIVE) {
            settlingAction = null;
            SoftMovementPrimitive.Step step = action == SegmentAction.ASCEND
                    ? SoftMovementPrimitive.applyJumpToward(bot, goalX, goalZ, distance)
                    : SoftMovementPrimitive.applyToward(bot, goalX, goalZ, distance,
                    SoftMovementPrimitive.Backend.NATIVE_TRAVEL);

            // P1: No-progress detection
            double currentHPos = Math.sqrt(bot.getX() * bot.getX() + bot.getZ() * bot.getZ());
            if (Math.abs(currentHPos - lastHorizontalPos) < 0.001D) {
                noProgressTicks++;
            } else {
                noProgressTicks = 0;
                lastHorizontalPos = currentHPos;
            }

            // P1: Log observation every 10 ticks
            if (elapsed % 10 == 0) {
                BotLog.info("soft_phys_obs: segment={}/{} action={} deltaMove=({},{},{}) hDev={} vDev={} support={} onGround={} hColl={} vColl={} noProgress={}",
                        index + 1, path.size(), action,
                        String.format(java.util.Locale.ROOT, "%.3f", currentDeltaMovement.x),
                        String.format(java.util.Locale.ROOT, "%.3f", currentDeltaMovement.y),
                        String.format(java.util.Locale.ROOT, "%.3f", currentDeltaMovement.z),
                        String.format(java.util.Locale.ROOT, "%.2f", hDev),
                        String.format(java.util.Locale.ROOT, "%.2f", vDev),
                        formatY(currentSupportTopY), bot.onGround(),
                        horizontalCollision, verticalCollisionBelow, noProgressTicks);
            }

            // P1: Deviation/disruption triggers revalidate
            if (hDev > HORIZONTAL_DEVIATION_THRESHOLD || vDev > VERTICAL_DEVIATION_THRESHOLD
                    || noProgressTicks > NO_PROGRESS_THRESHOLD || velocityDisruption) {
                String trigger = hDev > HORIZONTAL_DEVIATION_THRESHOLD ? "hDev=" + String.format(java.util.Locale.ROOT, "%.2f", hDev)
                        : vDev > VERTICAL_DEVIATION_THRESHOLD ? "vDev=" + String.format(java.util.Locale.ROOT, "%.2f", vDev)
                        : noProgressTicks > NO_PROGRESS_THRESHOLD ? "noProgress=" + noProgressTicks
                        : "velocityDisruption";
                boolean passable = revalidateSegment(level, from, segment);
                if (!passable) {
                    return replanFromCurrent(level, trigger);
                }
                revalidateTicks++;
                if (revalidateTicks > MAX_REVALIDATE_TICKS) {
                    return replanFromCurrent(level, "revalidate_timeout");
                }
            }

            return Status.RUNNING;
        }

        BlockPos actualFoot = bot.blockPosition();
        boolean supported = MovementHelper.isStandingAtFootPos(level, bot, segment);
        double supportTopY = MovementHelper.supportTopY(level, segment.below());
        if (action == SegmentAction.DESCEND && settlingAction == null) {
            settlingAction = action;
            settleTicks = 0;
            BotLog.info("软路径下降结算开始: {}/{} from={} to={} supportTopY={} actualY={} foot={} onGround={}",
                    index + 1, path.size(), from.toShortString(), segment.toShortString(),
                    formatY(supportTopY), formatY(bot.getY()), actualFoot.toShortString(), bot.onGround());
        }
        if (supported && bot.onGround()) {
            BotLog.info("软路径段完成: {}/{} action={} from={} to={} foot={} actualY={} supportTopY={} onGround={} settleTicks={}",
                    index + 1, path.size(), action, from.toShortString(), segment.toShortString(),
                    actualFoot.toShortString(), formatY(bot.getY()), formatY(supportTopY), bot.onGround(), settleTicks);
            BotLog.info("软路径段完成: {}/{} action={} from={} to={} foot={} onGround={}",
                    index + 1, path.size(), action, from.toShortString(), segment.toShortString(),
                    actualFoot.toShortString(), bot.onGround());
            index++;
            settleTicks = 0;
            noProgressTicks = 0;
            revalidateTicks = 0;
            velocityDisruption = false;
            return Status.RUNNING;
        }
        SoftMovementPrimitive.settle(bot);
        settleTicks++;
        if (settleTicks > MAX_SETTLE_TICKS) {
            // P1: Settle timeout triggers revalidate
            boolean passable = revalidateSegment(level, from, segment);
            if (!passable) {
                return replanFromCurrent(level, "settle_timeout");
            }
            failure = "soft_path_unsettled: segment=" + segment.toShortString()
                    + " foot=" + actualFoot.toShortString() + " actualY=" + formatY(bot.getY())
                    + " supportTopY=" + formatY(supportTopY) + " onGround=" + bot.onGround()
                    + " support=" + supported + " settleTicks=" + settleTicks;
            return Status.FAILED;
        }
        return Status.RUNNING;
    }

    private static String formatY(double value) {
        return Double.isNaN(value) ? "NaN" : String.format(java.util.Locale.ROOT, "%.3f", value);
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
        failure = blockedSegmentFailure(level, from, to, "geometry_mismatch:dy=" + dy);
        return null;
    }

    private String blockedSegmentFailure(ServerLevel level, BlockPos from, BlockPos to, String reason) {
        return segmentFailure("soft_path_blocked_segment", level, from, to, reason);
    }

    private String invalidSegmentFailure(ServerLevel level, BlockPos from, BlockPos to, String reason) {
        return segmentFailure("soft_path_invalid_segment", level, from, to, reason);
    }

    private String segmentFailure(String code, ServerLevel level, BlockPos from, BlockPos to, String reason) {
        BlockPos foot = bot.blockPosition();
        return code + ": " + reason + " from=" + from.toShortString()
                + " to=" + to.toShortString() + " foot=" + foot.toShortString()
                + " onGround=" + bot.onGround()
                + " support=" + MovementHelper.isStandingOnSupport(level, bot);
    }

    @Override
    public String failureReason() {
        return failure;
    }

    /**
     * P1: Revalidate current segment geometry after deviation/disruption.
     * Returns true if segment is still passable, false if blocked.
     */
    private boolean revalidateSegment(ServerLevel level, BlockPos from, BlockPos to) {
        int dy = to.getY() - from.getY();
        boolean passable;
        String reason;
        if (dy == 0) {
            passable = MovementHelper.canTraverse(level, from, to);
            reason = passable ? "traverse_ok" : "traverse_blocked";
        } else if (dy == 1) {
            passable = MovementHelper.canAscend(level, from, to);
            reason = passable ? "ascend_ok" : "ascend_blocked";
        } else if (dy == -1) {
            passable = MovementHelper.canDescend(level, from, to);
            reason = passable ? "descend_ok" : "descend_blocked";
        } else {
            passable = false;
            reason = "invalid_dy=" + dy;
        }
        BotLog.info("soft_phys_revalidate: segment={}/{} from={} to={} result={} reason={}",
                index + 1, path.size(), from.toShortString(), to.toShortString(),
                passable ? "passable" : "blocked", reason);
        return passable;
    }

    /**
     * P1: Replan from current bot position after segment blocked or severe deviation.
     * Returns RUNNING if replan succeeded, FAILED otherwise.
     */
    private Status replanFromCurrent(ServerLevel level, String trigger) {
        if (replanCount >= MAX_REPLAN) {
            failure = "soft_phys_max_replan: count=" + replanCount + " trigger=" + trigger;
            return Status.FAILED;
        }
        BlockPos currentFoot = bot.blockPosition();
        SurfacePathfinder.Result result = SurfacePathfinder.find(level, currentFoot, target);
        if (!result.reachable()) {
            failure = result.inconclusive() ? "soft_phys_replan_search_limit" : "soft_phys_replan_no_path";
            failure += ": trigger=" + trigger + " replanCount=" + replanCount;
            return Status.FAILED;
        }
        BotLog.info("soft_phys_replan: trigger={} oldPath={} newPath={} newCost={} replanCount={}",
                trigger, path.size(), result.path().size(),
                String.format(java.util.Locale.ROOT, "%.2f", result.totalCost()), replanCount + 1);
        path = result.path();
        index = 0;
        replanCount++;
        settleTicks = 0;
        noProgressTicks = 0;
        revalidateTicks = 0;
        velocityDisruption = false;
        return Status.RUNNING;
    }
}
