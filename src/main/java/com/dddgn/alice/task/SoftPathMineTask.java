package com.dddgn.alice.task;

import com.dddgn.alice.action.BotMiner;
import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.SoftMovementPrimitive;
import com.dddgn.alice.pathing.SurfacePathfinder;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.survival.HazardState;
import com.dddgn.alice.survival.SurvivalSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Soft-path test tool专用：先软寻路到目标附近（SOFT_SURFACE + P1 observation/revalidate/replan），
 * 到达后挖掘目标（复用 BotMiner）。不接入普通矿链；MineTask 保持 HARD_PATH 不动。
 */
public final class SoftPathMineTask implements Task {
    private enum Phase { SOFT_NAVIGATE, MINING }
    private enum SegmentAction { HORIZONTAL, ASCEND, DESCEND }

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
    private final BlockPos mineTarget;
    private final ScopeBuffer scope;
    private Phase phase = Phase.SOFT_NAVIGATE;
    private List<BlockPos> path;
    private int index;
    private int elapsed;
    private int settleTicks;
    private SegmentAction settlingAction;
    private BotMiner miner;
    private String failure = "";

    // P1 physics observation fields (复用 SoftPathProbeTask)
    private Vec3 lastDeltaMovement = Vec3.ZERO;
    private Vec3 currentDeltaMovement = Vec3.ZERO;
    private double lastSupportTopY = Double.NaN;
    private double currentSupportTopY = Double.NaN;
    private boolean horizontalCollision;
    private boolean verticalCollisionBelow;
    private int noProgressTicks;
    private int revalidateTicks;
    private int replanCount;
    private boolean velocityDisruption;
    private double lastHorizontalPos;

    public SoftPathMineTask(ServerPlayer bot, BlockPos mineTarget, ScopeBuffer scope) {
        this.bot = bot;
        this.mineTarget = mineTarget.immutable();
        this.scope = scope;
        // Equip pickaxe for mining phase
        bot.getInventory().setItem(bot.getInventory().selected, new ItemStack(Items.DIAMOND_PICKAXE));
        BotManager.syncMainHand(bot);
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(mineTarget);
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
            failure = "soft_mine_hazard:" + hazard.type();
            return Status.FAILED;
        }

        return switch (phase) {
            case SOFT_NAVIGATE -> tickSoftNavigate(level);
            case MINING -> tickMining();
        };
    }

    private Status tickSoftNavigate(ServerLevel level) {
        // Phase 1: Soft navigation to mineTarget (复用 SoftPathProbeTask P1 逻辑)
        if (path == null) {
            BlockPos navTarget = mineTarget.above(); // Navigate to foot position above the mine target
            SurfacePathfinder.Result result = SurfacePathfinder.find(bot, bot.blockPosition(), navTarget);
            if (!result.reachable()) {
                failure = result.inconclusive() ? "soft_mine_search_limit" : "soft_mine_no_path";
                return Status.FAILED;
            }
            path = result.path();
            lastHorizontalPos = Math.sqrt(bot.getX() * bot.getX() + bot.getZ() * bot.getZ());
            BotLog.info("软路径挖掘探针: mineTarget={} navTarget={} cost={} segments={}",
                    mineTarget.toShortString(), navTarget.toShortString(),
                    String.format(java.util.Locale.ROOT, "%.2f", result.totalCost()), path.size());
        }
        if (index >= path.size()) {
            // Navigation complete, switch to mining
            phase = Phase.MINING;
            miner = new BotMiner(bot, mineTarget);
            scope.begin(mineTarget, 8);
            BotLog.info("软路径挖掘: 导航完成，开始挖掘 target={}", mineTarget.toShortString());
            return Status.RUNNING;
        }
        elapsed++;
        if (elapsed > MAX_TICKS) {
            failure = "soft_mine_navigate_timeout";
            return Status.FAILED;
        }

        // P1: Update physics observation every tick
        lastDeltaMovement = currentDeltaMovement;
        currentDeltaMovement = bot.getDeltaMovement();
        lastSupportTopY = currentSupportTopY;
        currentSupportTopY = MovementHelper.supportTopY(level, bot.blockPosition().below());
        horizontalCollision = bot.horizontalCollision;
        verticalCollisionBelow = bot.verticalCollisionBelow;

        // P1: Detect velocity disruption
        double deltaH = currentDeltaMovement.horizontalDistance() - lastDeltaMovement.horizontalDistance();
        double deltaV = currentDeltaMovement.y - lastDeltaMovement.y;
        if (Math.abs(deltaH) > VELOCITY_DISRUPTION_HORIZONTAL || Math.abs(deltaV) > VELOCITY_DISRUPTION_VERTICAL) {
            velocityDisruption = true;
            BotLog.info("soft_mine_disruption: velocityChange=({},{},{}) horizontal={} vertical={}",
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
            if (action == SegmentAction.ASCEND) {
                SoftMovementPrimitive.applyJumpToward(bot, goalX, goalZ, distance);
            } else {
                SoftMovementPrimitive.applyToward(bot, goalX, goalZ, distance,
                        SoftMovementPrimitive.Backend.NATIVE_TRAVEL);
            }

            // P1: No-progress detection
            double currentHPos = Math.sqrt(bot.getX() * bot.getX() + bot.getZ() * bot.getZ());
            if (Math.abs(currentHPos - lastHorizontalPos) < 0.001D) {
                noProgressTicks++;
            } else {
                noProgressTicks = 0;
                lastHorizontalPos = currentHPos;
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

        // Arrived at horizontal distance, settle
        boolean supported = MovementHelper.isStandingAtFootPos(level, bot, segment);
        if (supported && bot.onGround()) {
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
            failure = "soft_mine_navigate_unsettled: segment=" + segment.toShortString();
            return Status.FAILED;
        }
        return Status.RUNNING;
    }

    private Status tickMining() {
        // Phase 2: Mining (复用 BotMiner)
        if (miner == null) {
            failure = "soft_mine_no_miner";
            return Status.FAILED;
        }
        BotMiner.Status minerStatus = miner.tick();
        return switch (minerStatus) {
            case MOVING, MINING -> Status.RUNNING;
            case DONE -> {
                BotLog.info("软路径挖掘完成: target={}", mineTarget.toShortString());
                yield Status.DONE;
            }
            case FAILED -> {
                failure = "soft_mine_dig_failed:" + miner.failureReason();
                yield Status.FAILED;
            }
        };
    }

    private SegmentAction segmentAction(ServerLevel level, BlockPos from, BlockPos to) {
        int dy = to.getY() - from.getY();
        int dx = Math.abs(to.getX() - from.getX());
        int dz = Math.abs(to.getZ() - from.getZ());
        if (dx > 1 || dz > 1 || (dx == 0 && dz == 0 && dy != 0)) {
            failure = "soft_mine_invalid_segment: non_adjacent from=" + from.toShortString() + " to=" + to.toShortString();
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
        failure = "soft_mine_blocked_segment: geometry_mismatch dy=" + dy + " from=" + from.toShortString() + " to=" + to.toShortString();
        return null;
    }

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
        BotLog.info("soft_mine_revalidate: segment={}/{} from={} to={} result={} reason={}",
                index + 1, path.size(), from.toShortString(), to.toShortString(),
                passable ? "passable" : "blocked", reason);
        return passable;
    }

    private Status replanFromCurrent(ServerLevel level, String trigger) {
        if (replanCount >= MAX_REPLAN) {
            failure = "soft_mine_max_replan: count=" + replanCount + " trigger=" + trigger;
            return Status.FAILED;
        }
        BlockPos currentFoot = bot.blockPosition();
        BlockPos navTarget = mineTarget.above();
        SurfacePathfinder.Result result = SurfacePathfinder.find(level, currentFoot, navTarget);
        if (!result.reachable()) {
            failure = result.inconclusive() ? "soft_mine_replan_search_limit" : "soft_mine_replan_no_path";
            failure += ": trigger=" + trigger + " replanCount=" + replanCount;
            return Status.FAILED;
        }
        BotLog.info("soft_mine_replan: trigger={} oldPath={} newPath={} newCost={} replanCount={}",
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

    @Override
    public String failureReason() {
        return failure;
    }
}
