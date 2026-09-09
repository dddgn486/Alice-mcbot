package com.dddgn.alice.pathing.core;

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
 * {@link MovementType#BREAK_AND_ENTER}：**破坏目的地格（含头位）后走进该格**。
 *
 * <p>对照 Baritone {@code MovementTraverse}（`positionsToBreak = {to.above(), to}`，
 * `Movement.prepared` 在 PREPPING 阶段先破坏再移动）——这是"挡路方块由移动自己清掉"的语义，
 * 也是新挖掘框架（D-067）模式 B 的到达原语。
 *
 * <p>破坏范围（D-067 ⑯）：
 * <ul>
 *   <li>基础：目的地躯干格 {@code to} + 头位格 {@code to.above()}（后者可通行则跳过）；</li>
 *   <li>**扩张一格**：仅当"进入时存在高低差"。在 Alice 的 dy=0 方块对齐模型下，
 *       支撑顶面差异无法产生高低差（源/目的地支撑同层），**唯一实际情形是空中衔接**
 *       （上一段以空中状态结束、进入时头位更高）→ 此时额外破坏 {@code to.above(2)}。</li>
 * </ul>
 *
 * <p>破坏走 {@link BlockInteraction}/{@link BlockBreakSession}（工具选择 + 进度 + 广播 + ABORT），
 * 不使用瞬间销毁；完成判定沿用统一契约（D-026）+ 分段容差（D-027）+ 落地判定（D-056）。
 */
public final class BreakAndEnterExecution implements MovementExecution {
    private final MovementSpec spec;
    private final BotPlayer bot;
    private final ServerLevel level;
    private final String botId;
    private final String sessionId;
    private final CompletionTolerance tolerance;
    private final List<BlockPos> blockers;

    private Phase phase = Phase.NOT_STARTED;
    private String failureCode;
    private int blockerIndex;
    private boolean extraHeadBreakAdded;
    private BlockBreakSession session;

    BreakAndEnterExecution(MovementSpec spec, LiveExecutionContext context) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.bot = requireBot(context.bot());
        this.level = Objects.requireNonNull(context.level(), "level");
        this.botId = bot.getUUID().toString();
        this.sessionId = context.sessionId();
        this.tolerance = context.tolerance();
        this.blockers = new ArrayList<>(collectBlockers(level, spec.fromFoot(), spec.toFoot()));
    }

    /**
     * 目的地列需要破坏的方块（躯干优先，再头位）。
     *
     * <p>规划期与执行期共用：规划期据此计算成本，执行期据此逐格破坏。
     */
    public static List<BlockPos> collectBlockers(ServerLevel level, BlockPos fromFoot, BlockPos toFoot) {
        List<BlockPos> result = new ArrayList<>(2);
        if (!MovementHelper.canWalkThrough(level, toFoot)) {
            result.add(toFoot);
        }
        if (!MovementHelper.canWalkThrough(level, toFoot.above())) {
            result.add(toFoot.above());
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
                fail("BREAK_AND_ENTER_INVALID_PRECONDITION");
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

        // 空中衔接（进入时存在高低差）→ 额外破坏头位上一格，避免撞头
        if (!extraHeadBreakAdded && !bot.onGround()) {
            extraHeadBreakAdded = true;
            BlockPos extra = spec.toFoot().above(2);
            if (!MovementHelper.canWalkThrough(level, extra)
                    && BlockInteraction.breakable(bot, level, extra)) {
                blockers.add(extra);
                BotLog.info("[BreakEnter] extra_head_break pos={} reason=airborne_entry",
                        extra.toShortString());
            }
        }

        // 1) 逐个破坏目的地列（躯干 → 头位 → 可能的头上一格）
        if (blockerIndex < blockers.size()) {
            BlockPos blocker = blockers.get(blockerIndex);
            bot.controller().stopMovement();
            if (level.getBlockState(blocker).isAir()) {
                blockerIndex++;
                session = null;
                return;
            }
            if (session == null) {
                session = BlockInteraction.beginBreak(bot, level, blocker);
            }
            BlockBreakSession.Status status = session.tick();
            if (status == BlockBreakSession.Status.DONE) {
                BotLog.info("[BreakEnter] cleared pos={} index={}/{}",
                        blocker.toShortString(), blockerIndex, blockers.size());
                session = null;
                blockerIndex++;
            } else if (status == BlockBreakSession.Status.FAILED) {
                fail(session.failureCode());
            }
            return;
        }

        // 2) 目的地列已清空 → 走进目的地格
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
        failureCode = "BREAK_AND_ENTER_CANCELLED";
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
        if (dy != 0 || Math.abs(dx) + Math.abs(dz) != 1) {
            return false;
        }
        if (!bot.blockPosition().equals(from) && !bot.blockPosition().equals(to)) {
            return false;
        }
        // 目的地最终必须可站（支撑 + 破坏后身体/头部空间）
        if (!MovementHelper.canWalkOn(level, to)) {
            return false;
        }
        // 目的地必须确实被阻挡（否则应由 TRAVERSE 处理）
        if (blockers.isEmpty()) {
            return false;
        }
        for (BlockPos blocker : blockers) {
            if (!BlockInteraction.breakable(bot, level, blocker)) {
                return false;
            }
        }
        return true;
    }

    private boolean postconditionHolds() {
        return tolerance == CompletionTolerance.COLUMN
                ? MovementHelper.isAtFootColumn(bot, spec.toFoot())
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
        BotLog.warn("[BreakEnter] failed session={} from={} to={} code={} actualFoot={}",
                sessionId, spec.fromFoot().toShortString(), spec.toFoot().toShortString(),
                reason, bot.blockPosition().toShortString());
    }

    private static BotPlayer requireBot(ServerPlayer bot) {
        if (!(bot instanceof BotPlayer botPlayer)) {
            throw new IllegalArgumentException("BreakAndEnterExecution requires BotPlayer");
        }
        return botPlayer;
    }
}
