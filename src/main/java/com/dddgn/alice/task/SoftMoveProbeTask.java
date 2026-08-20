package com.dddgn.alice.task;

import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.survival.HazardState;
import com.dddgn.alice.survival.SurvivalSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MoverType;

/**
 * 软地面移动实验任务。只用于安全平地短距离客户端验证，不接入 MineTask 或 PathExecutor。
 */
public final class SoftMoveProbeTask implements Task {
    private static final double SPEED = 0.12D;
    private static final double ARRIVE = 0.25D;
    private static final int MAX_TICKS = 120;
    private static final int MAX_DISTANCE = 8;

    private final ServerPlayer bot;
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

    public static boolean isSafeFlatProbe(ServerLevel level, BlockPos origin, BlockPos target) {
        return origin.distManhattan(target) <= MAX_DISTANCE
                && origin.getY() == target.getY()
                && com.dddgn.alice.pathing.MovementHelper.canWalkOn(level, origin)
                && com.dddgn.alice.pathing.MovementHelper.canWalkThrough(level, origin)
                && com.dddgn.alice.pathing.MovementHelper.canWalkThrough(level, origin.above())
                && com.dddgn.alice.pathing.MovementHelper.canWalkOn(level, target)
                && com.dddgn.alice.pathing.MovementHelper.canWalkThrough(level, target)
                && com.dddgn.alice.pathing.MovementHelper.canWalkThrough(level, target.above());
    }

    public SoftMoveProbeTask(ServerPlayer bot, BlockPos target) {
        this.bot = bot;
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

        double step = Math.min(SPEED, distance);
        bot.move(MoverType.SELF, new net.minecraft.world.phys.Vec3(
                dx / distance * step, 0.0D, dz / distance * step));
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
            BotLog.info("软移动探针: bot={} target={} pos=({}, {}) onGround={} fall={}",
                    bot.getName().getString(), target.toShortString(),
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
