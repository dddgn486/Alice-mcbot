package com.dddgn.alice.pathing.core;

import com.dddgn.alice.action.BlockInteraction;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;

/**
 * R5-3 放置台阶通行（{@link MovementType#PLACE_STEP_AND_TRAVERSE}，TEMPORARY_SUPPORT）。
 *
 * <p>语义（对照 Baritone {@code MovementTraverse} 的桥接分支 `:125-171`）：
 * 目标列缺少支撑（同层缺口或 2 格落差）→ **在目标下方放置一个方块**，再走上去。
 * 这是"两格高差往下踮一格方块"的显式原语，不允许隐藏在普通下降里。
 *
 * <p>放置走 {@link BlockInteraction#placeAt}（Baritone {@code attemptToPlaceABlock} 语义：
 * 支撑面扫描 + 面中心 + 视线校验 + 快捷栏选块）。
 */
public final class PlaceStepAndTraverseExecution implements MovementExecution {
    private final MovementSpec spec;
    private final BotPlayer bot;
    private final ServerLevel level;
    private final String botId;
    private final String sessionId;
    private final CompletionTolerance tolerance;

    private Phase phase = Phase.NOT_STARTED;
    private String failureCode;
    private int tickCount;
    private boolean placed;

    PlaceStepAndTraverseExecution(MovementSpec spec, LiveExecutionContext context) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.bot = requireBot(context.bot());
        this.level = Objects.requireNonNull(context.level(), "level");
        this.botId = bot.getUUID().toString();
        this.sessionId = context.sessionId();
        this.tolerance = context.tolerance();
    }

    /** 需要被放置支撑的位置（目标列下方）。 */
    public static BlockPos placePos(MovementSpec spec) {
        return spec.toFoot().below();
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
                fail("PLACE_STEP_AND_TRAVERSE_INVALID_PRECONDITION");
                return;
            }
            phase = Phase.EXECUTING;
        }

        BlockPos to = spec.toFoot();
        if (postconditionHolds()) {
            bot.controller().stopMovement();
            phase = Phase.POSTCONDITION_CHECK;
            phase = Phase.SUCCEEDED;
            return;
        }

        // 1) 目标下方没有支撑 → 放置一个方块
        if (!MovementHelper.canWalkOn(level, to)) {
            BlockPos target = placePos(spec);
            if (!MovementHelper.canWalkThrough(level, target)) {
                fail("PLACE_STEP_AND_TRAVERSE_PLACE_OCCUPIED");
                return;
            }
            if (BlockInteraction.findPlaceableSlot(bot) < 0) {
                fail("PLACE_RESOURCE_UNAVAILABLE");
                return;
            }
            BlockInteraction.PlaceResult result =
                    BlockInteraction.placeAt(bot, level, target, false);
            if (result == BlockInteraction.PlaceResult.NO_OPTION) {
                fail("PLACE_NO_VALID_FACE");
                return;
            }
            placed = true;
            BotLog.info("[PlaceStepAndTraverse] placed pos={} from={} to={}",
                    target.toShortString(), spec.fromFoot().toShortString(), to.toShortString());
            return;
        }
        placed = true;

        // 2) 支撑已就位 → 走到目标（统一完成契约）
        driveTowardTarget();
    }

    @Override
    public void cancel() {
        if (phase == Phase.SUCCEEDED || phase == Phase.FAILED || phase == Phase.CANCELLED) {
            return;
        }
        bot.controller().stopMovement();
        phase = Phase.CANCELLED;
        failureCode = "PLACE_STEP_AND_TRAVERSE_CANCELLED";
    }

    @Override
    public String failureCode() {
        return failureCode;
    }

    private boolean preconditionsHold() {
        BlockPos from = spec.fromFoot();
        BlockPos to = spec.toFoot();
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        if ((dy != 0 && dy != -1) || Math.abs(dx) + Math.abs(dz) != 1) {
            return false;
        }
        if (!bot.blockPosition().equals(from) && !bot.blockPosition().equals(to)) {
            return false;
        }
        // 目标列必须可通行，且缺支撑（否则这是普通 traverse/descend）
        if (!MovementHelper.canWalkThrough(level, to)
                || !MovementHelper.canWalkThrough(level, to.above())) {
            return false;
        }
        if (MovementHelper.canWalkOn(level, to)) {
            return false;
        }
        // 放置位必须可替换（不是实心块）
        BlockPos target = placePos(spec);
        if (!MovementHelper.canWalkThrough(level, target)) {
            return false;
        }
        // 必须有可放置方块与可用支撑面
        return BlockInteraction.findPlaceableSlot(bot) >= 0
                && BlockInteraction.hasPlacementFace(level, target);
    }

    private boolean postconditionHolds() {
        return tolerance == CompletionTolerance.COLUMN
                ? MovementHelper.isAtFootColumn(bot, spec.toFoot())
                : MovementHelper.isSettledAtFootPos(level, bot, spec.toFoot(), 0.3D);
    }

    private void driveTowardTarget() {
        BlockPos to = spec.toFoot();
        double dx = to.getX() + 0.5D - bot.getX();
        double dz = to.getZ() + 0.5D - bot.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        bot.setYRot(yaw);
        bot.setYBodyRot(yaw);
        bot.controller().setForward(1.0F);
        bot.controller().setStrafing(0.0F);
    }

    private void fail(String reason) {
        bot.controller().stopMovement();
        phase = Phase.FAILED;
        failureCode = reason;
        BotLog.warn("[PlaceStepAndTraverse] failed session={} from={} to={} code={} actualFoot={}",
                sessionId, spec.fromFoot().toShortString(), spec.toFoot().toShortString(),
                reason, bot.blockPosition().toShortString());
    }

    private static BotPlayer requireBot(ServerPlayer bot) {
        if (!(bot instanceof BotPlayer botPlayer)) {
            throw new IllegalArgumentException("PlaceStepAndTraverseExecution requires BotPlayer");
        }
        return botPlayer;
    }
}
