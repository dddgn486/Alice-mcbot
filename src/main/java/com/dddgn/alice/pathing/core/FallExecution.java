package com.dddgn.alice.pathing.core;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;

/**
 * {@link MovementType#FALL}：落差 2~3 格（走离边缘后自由落体到低处平台）。
 *
 * <p>对照 Baritone {@code MovementFall.updateState}（baritone-1.20.1 @8c55ad0
 * {@code movements/MovementFall.java:79-190}）：
 * <ol>
 *   <li>朝落点方块中心走（空中偏离中心 > 0.1 才继续给前进输入，避免越过落点列）；</li>
 *   <li>下落途中不跳、不刹；</li>
 *   <li>落地判定 = 落在落点列且基本站稳（Baritone `feet==dest && y-dest.y<0.094`）。</li>
 * </ol>
 *
 * <p>Alice 差异：不移植落水/水桶分支（D-058 决策：本轮只做无水落地 ≤3 格）；
 * 完成判定用 D-056 的 {@link MovementHelper#isAtFootColumn}（脚位列 + onGround）。
 */
public final class FallExecution implements MovementExecution {
    /** 空中重新居中阈值（Baritone `MovementFall:161` 的 0.1）。 */
    private static final double CENTER_TOLERANCE = 0.1D;
    /** 落地后走向落点列的收敛阈值（EXACT 容差 0.3 内即可）。 */
    private static final double GROUND_TOLERANCE = 0.3D;

    private final MovementSpec spec;
    private final BotPlayer bot;
    private final ServerLevel level;
    private final String botId;
    private final String sessionId;
    private final CompletionTolerance tolerance;

    private Phase phase = Phase.NOT_STARTED;
    private String failureCode;
    private boolean airborne;

    FallExecution(MovementSpec spec, LiveExecutionContext context) {
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
                fail("FALL_INVALID_PRECONDITION");
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

        BlockPos to = spec.toFoot();
        boolean grounded = bot.onGround();
        if (grounded && !airborne) {
            // 仍在地面：朝落点方向走出边缘
            faceTowards(to);
            bot.controller().setForward(1.0F);
            bot.controller().setStrafing(0.0F);
            return;
        }
        if (!grounded) {
            airborne = true;
        }

        // 空中：只在偏离落点列中心时给前进输入（Baritone MovementFall:161-166）；
        // 落地后仍朝落点列收敛（可能因惯性落在落点列旁，平台可走）
        double dx = to.getX() + 0.5D - bot.getX();
        double dz = to.getZ() + 0.5D - bot.getZ();
        double tolerance = grounded ? GROUND_TOLERANCE : CENTER_TOLERANCE;
        if (Math.sqrt(dx * dx + dz * dz) > tolerance) {
            faceTowards(to);
            bot.controller().setForward(1.0F);
        } else {
            bot.controller().setForward(0.0F);
        }
    }

    @Override
    public void cancel() {
        if (phase == Phase.SUCCEEDED || phase == Phase.FAILED || phase == Phase.CANCELLED) {
            return;
        }
        bot.controller().stopMovement();
        phase = Phase.CANCELLED;
        failureCode = "FALL_CANCELLED";
    }

    @Override
    public String failureCode() {
        return failureCode;
    }

    private void fail(String reason) {
        bot.controller().stopMovement();
        phase = Phase.FAILED;
        failureCode = reason;
        BotLog.warn("[Fall] failed session={} from={} to={} code={} feet={} onGround={}",
                sessionId, spec.fromFoot().toShortString(), spec.toFoot().toShortString(),
                reason, MovementHelper.footCell(level, bot).toShortString(), bot.onGround());
    }

    private boolean preconditionsHold() {
        BlockPos from = spec.fromFoot();
        BlockPos to = spec.toFoot();
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int dy = to.getY() - from.getY();
        if (Math.abs(dx) + Math.abs(dz) != 1 || dy > -2 || dy < -3) {
            return false;
        }
        BlockPos feet = MovementHelper.footCell(level, bot);
        if (!feet.equals(from) && !feet.equals(to)) {
            return false;
        }
        if (feet.equals(from) && !bot.onGround()) {
            return false;   // 必须站好才能走离边缘
        }
        // 走离边缘 + 下落列净空 + 落点可站
        BlockPos edge = from.offset(dx, 0, dz);
        if (!MovementHelper.canWalkThrough(level, edge)
                || !MovementHelper.canWalkThrough(level, edge.above())) {
            return false;
        }
        for (int y = from.getY() - 1; y > to.getY(); y--) {
            if (!MovementHelper.canWalkThrough(level, new BlockPos(to.getX(), y, to.getZ()))) {
                return false;
            }
        }
        if (!MovementHelper.canWalkOn(level, to)
                || !MovementHelper.canWalkThrough(level, to)
                || !MovementHelper.canWalkThrough(level, to.above())) {
            return false;
        }
        return level.getFluidState(to).isEmpty() && level.getFluidState(to.above()).isEmpty()
                && !MovementHelper.isBottomSlab(level.getBlockState(to.below()));
    }

    private boolean postconditionHolds() {
        BlockPos to = spec.toFoot();
        if (tolerance == CompletionTolerance.COLUMN) {
            return MovementHelper.isAtFootColumn(level, bot, to);
        }
        return MovementHelper.isSettledAtFootPos(level, bot, to, 0.3D);
    }

    private void faceTowards(BlockPos to) {
        double dx = to.getX() + 0.5D - bot.getX();
        double dz = to.getZ() + 0.5D - bot.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        bot.setYRot(yaw);
        bot.setYBodyRot(yaw);
    }

    private static BotPlayer requireBot(ServerPlayer bot) {
        if (!(bot instanceof BotPlayer botPlayer)) {
            throw new IllegalArgumentException("FallExecution requires BotPlayer");
        }
        return botPlayer;
    }
}
