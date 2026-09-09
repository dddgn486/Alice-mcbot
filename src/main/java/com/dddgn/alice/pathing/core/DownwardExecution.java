package com.dddgn.alice.pathing.core;

import com.dddgn.alice.action.BlockBreakSession;
import com.dddgn.alice.action.BlockInteraction;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;

/**
 * {@link MovementType#DOWNWARD}：垂直下落 1 格（破坏脚下 → 掉进 1 格深的洞）。
 *
 * <p>对照 Baritone {@code MovementDownward}。破坏走 {@link BlockInteraction}/{@link BlockBreakSession}
 * （工具选择 + 进度 + 广播），不使用瞬间销毁。
 *
 * <p>Baritone 原样语义（D-050）：脚下可破坏 + 落点有支撑；破坏期间保持原地，
 * 破坏完成后等待自然掉落并稳定（统一完成契约 + 分段容差 D-027）。
 */
public final class DownwardExecution implements MovementExecution {
    private final MovementSpec spec;
    private final BotPlayer bot;
    private final ServerLevel level;
    private final String botId;
    private final String sessionId;
    private final CompletionTolerance tolerance;

    private Phase phase = Phase.NOT_STARTED;
    private String failureCode;
    private BlockBreakSession breakSession;

    DownwardExecution(MovementSpec spec, LiveExecutionContext context) {
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
                fail("DOWNWARD_INVALID_PRECONDITION");
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

        BlockPos target = spec.toFoot();
        if (!level.getBlockState(target).isAir()) {
            // 破坏脚下的方块（破坏期间保持原地，避免动量带偏）
            bot.controller().stopMovement();
            if (breakSession == null) {
                breakSession = BlockInteraction.beginBreak(bot, level, target);
            }
            BlockBreakSession.Status status = breakSession.tick();
            if (status == BlockBreakSession.Status.DONE) {
                BotLog.info("[Downward] support_broken pos={} from={}",
                        target.toShortString(), spec.fromFoot().toShortString());
                breakSession = null;
            } else if (status == BlockBreakSession.Status.FAILED) {
                fail(breakSession.failureCode());
            }
            return;
        }

        // 方块已破：等待自然掉落并稳定
        bot.controller().stopMovement();
    }

    @Override
    public void cancel() {
        if (phase == Phase.SUCCEEDED || phase == Phase.FAILED || phase == Phase.CANCELLED) {
            return;
        }
        bot.controller().stopMovement();
        if (breakSession != null) {
            breakSession.abort();
            breakSession = null;
        }
        phase = Phase.CANCELLED;
        failureCode = "DOWNWARD_CANCELLED";
    }

    @Override
    public String failureCode() {
        return failureCode;
    }

    private void fail(String code) {
        failureCode = code;
        phase = Phase.FAILED;
        bot.controller().stopMovement();
    }

    private boolean preconditionsHold() {
        BlockPos to = spec.toFoot();
        if (!MovementHelper.canWalkOn(level, to) || !MovementHelper.canWalkThrough(level, to.above())) {
            return false;
        }
        return level.getBlockState(to).isAir() || BlockInteraction.breakableExplicit(bot, level, to);
    }

    private boolean postconditionHolds() {
        BlockPos to = spec.toFoot();
        return tolerance == CompletionTolerance.COLUMN
                ? MovementHelper.isAtFootColumn(bot, to)
                : MovementHelper.isSettledAtFootPos(level, bot, to, 0.3D);
    }

    private static BotPlayer requireBot(ServerPlayer bot) {
        if (!(bot instanceof BotPlayer botPlayer)) {
            throw new IllegalArgumentException("DownwardExecution requires BotPlayer");
        }
        return botPlayer;
    }
}
