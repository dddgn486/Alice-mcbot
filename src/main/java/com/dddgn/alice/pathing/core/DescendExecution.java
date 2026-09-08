package com.dddgn.alice.pathing.core;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;

/** R2-C 一级下降 Descend 的主线程执行对象。 */
public final class DescendExecution implements MovementExecution {
    private final MovementSpec spec;
    private final BotPlayer bot;
    private final ServerLevel level;
    private final String botId;
    private final String sessionId;
    private Phase phase = Phase.NOT_STARTED;
    private String failureCode;
    private int settlingTicks = 0;
    private static final int MAX_SETTLING_TICKS = 20;

    DescendExecution(MovementSpec spec, LiveExecutionContext context) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.bot = requireBot(context.bot());
        this.level = Objects.requireNonNull(context.level(), "level");
        this.botId = bot.getUUID().toString();
        this.sessionId = context.sessionId();
    }

    @Override
    public MovementSpec spec() {
        return spec;
    }

    @Override
    public String botId() {
        return botId;
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public Phase phase() {
        return phase;
    }

    @Override
    public void tick() {
        if (phase == Phase.SUCCEEDED || phase == Phase.FAILED || phase == Phase.CANCELLED) {
            return;
        }
        
        if (phase == Phase.NOT_STARTED) {
            phase = Phase.PRECONDITION_CHECK;
            if (!preconditionsHold()) {
                fail("DESCEND_INVALID_PRECONDITION");
                return;
            }
            phase = Phase.EXECUTING;
        }

        if (phase == Phase.EXECUTING) {
            // 检查 blockPosition 的 Y 是否已经降低（真正开始下降）
            if (bot.blockPosition().getY() < spec.fromFoot().getY()) {
                phase = Phase.SETTLING;
                bot.controller().stopMovement();
                settlingTicks = 0;
                return;
            }
            driveTowardTarget();
            return;
        }

        if (phase == Phase.SETTLING) {
            settlingTicks++;
            if (settlingTicks > MAX_SETTLING_TICKS) {
                // 超时前检查是否至少到达了目标高度和大致位置
                if (bot.blockPosition().getY() <= spec.toFoot().getY() 
                        && horizontalDistanceToTarget() <= 0.6D) {
                    bot.controller().stopMovement();
                    phase = Phase.POSTCONDITION_CHECK;
                    phase = Phase.SUCCEEDED;
                    return;
                }
                fail("DESCEND_SETTLING_TIMEOUT");
                return;
            }
            if (postconditionHolds()) {
                bot.controller().stopMovement();
                phase = Phase.POSTCONDITION_CHECK;
                phase = Phase.SUCCEEDED;
                return;
            }
        }
    }

    @Override
    public void cancel() {
        if (phase == Phase.SUCCEEDED || phase == Phase.FAILED || phase == Phase.CANCELLED) {
            return;
        }
        bot.controller().stopMovement();
        phase = Phase.CANCELLED;
        failureCode = "DESCEND_CANCELLED";
    }

    @Override
    public String failureCode() {
        return failureCode;
    }

    private boolean preconditionsHold() {
        BlockPos from = spec.fromFoot();
        BlockPos to = spec.toFoot();
        
        // 验证几何约束
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        int horizontalDist = Math.abs(dx) + Math.abs(dz);
        if (dy != -1 || horizontalDist != 1) {
            return false;
        }
        
        if (!bot.blockPosition().equals(from)) {
            return false;
        }
        
        // 验证目标可通行
        return MovementHelper.canWalkThrough(level, to)
                && MovementHelper.canWalkThrough(level, to.above())
                && MovementHelper.canWalkOn(level, to);
    }

    private boolean postconditionHolds() {
        return MovementHelper.isStandingAtFootPos(level, bot, spec.toFoot())
                && bot.onGround()
                && horizontalDistanceToTarget() <= 0.3D;
    }

    private double horizontalDistanceToTarget() {
        double targetX = spec.toFoot().getX() + 0.5D;
        double targetZ = spec.toFoot().getZ() + 0.5D;
        double dx = targetX - bot.getX();
        double dz = targetZ - bot.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void driveTowardTarget() {
        double targetX = spec.toFoot().getX() + 0.5D;
        double targetZ = spec.toFoot().getZ() + 0.5D;
        double dx = targetX - bot.getX();
        double dz = targetZ - bot.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        bot.setYRot(yaw);
        bot.setYHeadRot(yaw);
        bot.controller().setForward(1.0F);
        bot.controller().setStrafing(0.0F);
    }

    private void fail(String reason) {
        bot.controller().stopMovement();
        phase = Phase.FAILED;
        failureCode = reason;
    }

    private static BotPlayer requireBot(ServerPlayer bot) {
        if (!(bot instanceof BotPlayer botPlayer)) {
            throw new IllegalArgumentException("DescendExecution requires BotPlayer");
        }
        return botPlayer;
    }
}
