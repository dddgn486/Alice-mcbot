package com.dddgn.alice.task;

import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.SoftMovementPrimitive;
import com.dddgn.alice.survival.HazardState;
import com.dddgn.alice.survival.SurvivalSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 软地面移动实验任务。只用于安全平地短距离客户端验证，不接入 MineTask 或 PathExecutor。
 */
public final class SoftMoveProbeTask implements Task {
    private static final double ARRIVE = 0.25D;
    private static final int MAX_TICKS = 120;
    private static final int MAX_DISTANCE = 8;

    private final ServerPlayer bot;
    private final SoftMovementPrimitive.Backend backend;
    private final BlockPos target;
    private final double targetX;
    private final double targetZ;
    private final double startX;
    private final double startZ;
    private int elapsed;
    private int noProgress;
    private double lastX;
    private double lastZ;
    private String failure = "";

    public record ProbeValidation(boolean valid, String reason) {
    }

    /** 目标坐标是 bot 的脚位格；支撑方块坐标应传入其上方一格。 */
    public static ProbeValidation validate(ServerLevel level, BlockPos origin, BlockPos target) {
        if (origin.distManhattan(target) > MAX_DISTANCE) {
            return new ProbeValidation(false, "distance_limit: origin=" + origin.toShortString()
                    + " target=" + target.toShortString());
        }
        if (origin.getY() != target.getY()) {
            return new ProbeValidation(false, "height_mismatch: originY=" + origin.getY()
                    + " targetY=" + target.getY());
        }
        if (!com.dddgn.alice.pathing.MovementHelper.canWalkOn(level, origin)) {
            return new ProbeValidation(false, "origin_no_support:" + origin.below().toShortString());
        }
        if (!com.dddgn.alice.pathing.MovementHelper.canWalkThrough(level, origin)) {
            return new ProbeValidation(false, "origin_foot_blocked:" + origin.toShortString());
        }
        if (!com.dddgn.alice.pathing.MovementHelper.canWalkThrough(level, origin.above())) {
            return new ProbeValidation(false, "origin_head_blocked:" + origin.above().toShortString());
        }
        if (!com.dddgn.alice.pathing.MovementHelper.canWalkOn(level, target)) {
            return new ProbeValidation(false, "target_no_support:" + target.below().toShortString());
        }
        if (!com.dddgn.alice.pathing.MovementHelper.canWalkThrough(level, target)) {
            return new ProbeValidation(false, "target_foot_blocked:" + target.toShortString());
        }
        if (!com.dddgn.alice.pathing.MovementHelper.canWalkThrough(level, target.above())) {
            return new ProbeValidation(false, "target_head_blocked:" + target.above().toShortString());
        }
        return new ProbeValidation(true, "ok");
    }

    public static boolean isSafeFlatProbe(ServerLevel level, BlockPos origin, BlockPos target) {
        return validate(level, origin, target).valid();
    }

    public SoftMoveProbeTask(ServerPlayer bot, BlockPos target) {
        this(bot, target, SoftMovementPrimitive.Backend.SELF_MOVE);
    }

    public SoftMoveProbeTask(ServerPlayer bot, BlockPos target, SoftMovementPrimitive.Backend backend) {
        this.bot = bot;
        this.backend = backend;
        this.target = target.immutable();
        this.targetX = target.getX() + 0.5D;
        this.targetZ = target.getZ() + 0.5D;
        this.startX = bot.getX();
        this.startZ = bot.getZ();
        this.lastX = bot.getX();
        this.lastZ = bot.getZ();
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(target);
    }

    @Override
    public Status tick() {
        HazardState hazard = SurvivalSystem.tick(bot);
        if (SurvivalSystem.shouldInterrupt(hazard)) {
            failure = SurvivalSystem.interruptionReason(hazard);
            return Status.FAILED;
        }
        if (hazard.type() != com.dddgn.alice.survival.HazardType.NONE) {
            failure = "soft_probe_hazard:" + hazard.type();
            return Status.FAILED;
        }
        elapsed++;
        double dx = targetX - bot.getX();
        double dz = targetZ - bot.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= ARRIVE) {
            bot.setOnGround(true);
            bot.fallDistance = 0.0F;
            return Status.DONE;
        }
        double travelled = Math.sqrt(Math.pow(bot.getX() - startX, 2)
                + Math.pow(bot.getZ() - startZ, 2));
        if (travelled > MAX_DISTANCE || elapsed > MAX_TICKS) {
            failure = "soft_probe_limit";
            return Status.FAILED;
        }

        SoftMovementPrimitive.Step movement = SoftMovementPrimitive.applyToward(
                bot, targetX, targetZ, distance, backend);
        double moved = Math.abs(bot.getX() - lastX) + Math.abs(bot.getZ() - lastZ);
        if (moved < 0.0001D) {
            noProgress++;
            if (noProgress > 20) {
                failure = "soft_probe_no_progress";
                return Status.FAILED;
            }
        } else {
            noProgress = 0;
        }
        lastX = bot.getX();
        lastZ = bot.getZ();
        if (elapsed % 20 == 0) {
            BotLog.info("软移动探针: backend={} bot={} target={} step={} yaw={} pos=({}, {}) onGround={} fall={}",
                    backend, bot.getName().getString(), target.toShortString(),
                    String.format(java.util.Locale.ROOT, "%.3f", movement.distance()),
                    String.format(java.util.Locale.ROOT, "%.1f", movement.yaw()),
                    String.format(java.util.Locale.ROOT, "%.2f", bot.getX()),
                    String.format(java.util.Locale.ROOT, "%.2f", bot.getZ()),
                    bot.onGround(), bot.fallDistance);
        }
        return Status.RUNNING;
    }

    @Override
    public String failureReason() {
        return failure;
    }
}
