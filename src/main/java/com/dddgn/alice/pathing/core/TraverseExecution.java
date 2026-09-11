package com.dddgn.alice.pathing.core;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;

/** R2-B 同高度四向 Traverse 的主线程执行对象。 */
public final class TraverseExecution implements MovementExecution {
    private final MovementSpec spec;
    private final BotPlayer bot;
    private final ServerLevel level;
    private final String botId;
    private final String sessionId;
    private final CompletionTolerance tolerance;
    private Phase phase = Phase.NOT_STARTED;
    private String failureCode;

    TraverseExecution(MovementSpec spec, LiveExecutionContext context) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.bot = requireBot(context.bot());
        this.level = Objects.requireNonNull(context.level(), "level");
        this.botId = bot.getUUID().toString();
        this.sessionId = context.sessionId();
        this.tolerance = context.tolerance();
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
                fail("TRAVERSE_INVALID_PRECONDITION");
                return;
            }
            phase = Phase.EXECUTING;
        }

        if (postconditionHolds()) {
            bot.controller().stopMovement();
            phase = Phase.POSTCONDITION_CHECK;
            phase = Phase.SUCCEEDED;
            return;
        }

        driveTowardTarget();
    }

    @Override
    public void cancel() {
        if (phase == Phase.SUCCEEDED || phase == Phase.FAILED || phase == Phase.CANCELLED) {
            return;
        }
        bot.controller().stopMovement();
        phase = Phase.CANCELLED;
        failureCode = "TRAVERSE_CANCELLED";
    }

    @Override
    public String failureCode() {
        return failureCode;
    }

    private boolean preconditionsHold() {
        BlockPos from = spec.fromFoot();
        BlockPos to = spec.toFoot();
        if (from.getY() != to.getY()
                || Math.abs(to.getX() - from.getX()) + Math.abs(to.getZ() - from.getZ()) != 1) {
            return false;
        }
        BlockPos feet = MovementHelper.footCell(level, bot);
        if (!feet.equals(from) && !feet.equals(to)) {
            return false;
        }
        return MovementHelper.canWalkThrough(level, to)
                && MovementHelper.canWalkThrough(level, to.above())
                && MovementHelper.canWalkOn(level, to);
    }

    private boolean postconditionHolds() {
        // D-026 统一完成契约：脚位正确 + 落地 + 水平到位
        // D-027：容差由会话指定（中间段 COLUMN、最终段 EXACT），执行器不得自行硬编码
        return tolerance == CompletionTolerance.COLUMN
                ? MovementHelper.footCell(level, bot).equals(spec.toFoot()) && bot.getY() - spec.toFoot().getY() < 0.5D
                : MovementHelper.isSettledAtFootPos(level, bot, spec.toFoot(), 0.3D);
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
        // Baritone 式旋转（LookBehavior:99-100）：只写身体 yaw，头/身交给原版 tickHeadTurn 管理。
        // 直接写 yHeadRot 而不同步 yBodyRot 会让客户端把头渲染成扭向一侧。
        bot.setYRot(yaw);
        bot.setYBodyRot(yaw);
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
            throw new IllegalArgumentException("TraverseExecution requires BotPlayer");
        }
        return botPlayer;
    }
}
