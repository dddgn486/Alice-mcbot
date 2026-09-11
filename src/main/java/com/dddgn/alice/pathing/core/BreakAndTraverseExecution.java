package com.dddgn.alice.pathing.core;

import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.action.BlockBreakSession;
import com.dddgn.alice.action.BlockInteraction;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * R5-2 破坏通行（{@link MovementType#BREAK_AND_TRAVERSE}）。
 *
 * <p>语义：目标脚位/头位被阻挡时，按 Baritone 的 {@code Movement.prepared} 思路
 * **先破坏阻挡方块（PATH_ACCESS），再走到目标脚位**。破坏走
 * {@link BlockInteraction}/{@link BlockBreakSession}（工具选择 + 进度 + 广播），
 * 不使用 {@code level.destroyBlock} 的瞬间销毁。
 *
 * <p>完成判定沿用统一契约（D-026）+ 分段容差（D-027）。
 */
public final class BreakAndTraverseExecution implements MovementExecution {
    private final MovementSpec spec;
    private final BotPlayer bot;
    private final ServerLevel level;
    private final String botId;
    private final String sessionId;
    private final CompletionTolerance tolerance;
    /** 授权身份（D-082）。 */
    private final WriteGrant grant;
    private final List<BlockPos> blockers;

    private Phase phase = Phase.NOT_STARTED;
    private String failureCode;
    private int blockerIndex;
    private int tickCount;
    private BlockBreakSession session;

    BreakAndTraverseExecution(MovementSpec spec, LiveExecutionContext context) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.bot = requireBot(context.bot());
        this.level = Objects.requireNonNull(context.level(), "level");
        this.botId = bot.getUUID().toString();
        this.sessionId = context.sessionId();
        this.tolerance = context.tolerance();
        this.grant = WriteGrant.of(context.requester(), WriteReason.PATH_ACCESS);
        this.blockers = collectBlockers(level, spec.fromFoot(), spec.toFoot());
    }

    /**
     * 中间列（from 与 to 之间那一格）中不可通行的方块，按脚位优先。
     * 语义：破坏它们之后，从 from 穿过中间列走到 to。
     */
    public static List<BlockPos> collectBlockers(ServerLevel level, BlockPos fromFoot, BlockPos toFoot) {
        int dx = Integer.signum(toFoot.getX() - fromFoot.getX());
        int dz = Integer.signum(toFoot.getZ() - fromFoot.getZ());
        BlockPos mid = fromFoot.offset(dx, 0, dz);
        List<BlockPos> result = new ArrayList<>(2);
        if (!MovementHelper.canWalkThrough(level, mid)) {
            result.add(mid);
        }
        if (!MovementHelper.canWalkThrough(level, mid.above())) {
            result.add(mid.above());
        }
        return result;
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
                fail("BREAK_AND_TRAVERSE_INVALID_PRECONDITION");
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

        // 1) 逐个破坏阻挡方块（脚位优先，再头位）
        if (blockerIndex < blockers.size()) {
            BlockPos blocker = blockers.get(blockerIndex);
            if (level.getBlockState(blocker).isAir()) {
                blockerIndex++;
                session = null;
                return;
            }
            if (session == null) {
                session = BlockInteraction.beginBreak(bot, level, blocker, grant);
                if (session == null) {
                    // 执行期写入预算耗尽（D-106）：内核不许再改世界，如实上报
                    fail("WRITE_BUDGET_EXHAUSTED");
                    return;
                }
            }
            BlockBreakSession.Status status = session.tick();
            if (status == BlockBreakSession.Status.DONE) {
                BotLog.info("[BreakAndTraverse] blocked_cleared pos={} index={}",
                        blocker.toShortString(), blockerIndex);
                session = null;
                blockerIndex++;
            } else if (status == BlockBreakSession.Status.FAILED) {
                fail(session.failureCode());
            }
            return;
        }

        // 2) 阻挡已清除，走向目标（复用统一完成契约）
        driveTowardTarget();
    }

    @Override
    public void cancel() {
        if (phase == Phase.SUCCEEDED || phase == Phase.FAILED || phase == Phase.CANCELLED) {
            return;
        }
        bot.controller().stopMovement();
        if (session != null) {
            // 对照 Baritone PathExecutor:603-608：取消时清理客户端裂纹广播
            session.abort();
            session = null;
        }
        phase = Phase.CANCELLED;
        failureCode = "BREAK_AND_TRAVERSE_CANCELLED";
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
        boolean straightTwo = (Math.abs(dx) == 2 && dz == 0) || (Math.abs(dz) == 2 && dx == 0);
        if (dy != 0 || !straightTwo) {
            return false;
        }
        BlockPos feet = MovementHelper.footCell(level, bot);
        if (!feet.equals(from) && !feet.equals(to)) {
            return false;
        }
        // 目标必须最终可通行且可站（造支撑是 PLACE_STEP_AND_TRAVERSE 的职责）
        if (!MovementHelper.canWalkThrough(level, to)
                || !MovementHelper.canWalkThrough(level, to.above())
                || !MovementHelper.canWalkOn(level, to)) {
            return false;
        }
        // 每个阻挡方块必须可破坏
        for (BlockPos blocker : blockers) {
            if (!BlockInteraction.breakable(bot, level, blocker, grant)) {
                return false;
            }
        }
        return true;
    }

    private boolean postconditionHolds() {
        return tolerance == CompletionTolerance.COLUMN
                ? MovementHelper.isAtFootColumn(level, bot, spec.toFoot())
                : MovementHelper.isSettledAtFootPos(level, bot, spec.toFoot(), 0.3D);
    }

    private void driveTowardTarget() {
        BlockPos to = spec.toFoot();
        double targetX = to.getX() + 0.5D;
        double targetZ = to.getZ() + 0.5D;
        double dx = targetX - bot.getX();
        double dz = targetZ - bot.getZ();
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
        BotLog.warn("[BreakAndTraverse] failed session={} from={} to={} code={} actualFoot={}",
                sessionId, spec.fromFoot().toShortString(), spec.toFoot().toShortString(),
                reason, MovementHelper.footCell(level, bot).toShortString());
    }

    private static BotPlayer requireBot(ServerPlayer bot) {
        if (!(bot instanceof BotPlayer botPlayer)) {
            throw new IllegalArgumentException("BreakAndTraverseExecution requires BotPlayer");
        }
        return botPlayer;
    }
}
