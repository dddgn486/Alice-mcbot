package com.dddgn.alice.pathing.core;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;

/**
 * R2-C 一级下降 Descend 的主线程执行对象。
 *
 * <p>控制策略直接对齐 Baritone MovementDescend（D-023 第 0 号闭环）：
 * 无分阶段状态机，单循环：朝 fakeDest（起点镜像）前进直到越过目标列中心，
 * 之后朝 dest 前进（= 回头看目标列，自然拉回中心）。
 * 成功条件：脚在目标列 + Y 离目标面不超过 0.5。
 */
public final class DescendExecution implements MovementExecution {
    private final MovementSpec spec;
    private final BotPlayer bot;
    private final ServerLevel level;
    private final String botId;
    private final String sessionId;
    private final CompletionTolerance tolerance;
    private Phase phase = Phase.NOT_STARTED;
    private String failureCode;
    private int tickCount = 0;

    /** Baritone 参考：前 20 tick 且距起点中心 1.25 格内朝 fakeDest，之后朝 dest。 */
    private static final double FAKE_DEST_CUTOFF = 1.25D;
    private static final int FAKE_DEST_MAX_TICKS = 20;

    DescendExecution(MovementSpec spec, LiveExecutionContext context) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.bot = requireBot(context.bot());
        this.level = Objects.requireNonNull(context.level(), "level");
        this.botId = bot.getUUID().toString();
        this.sessionId = context.sessionId();
        this.tolerance = context.tolerance();
    }

    @Override
    public MovementSpec spec() { return spec; }

    @Override
    public String botId() { return botId; }

    @Override
    public String sessionId() { return sessionId; }

    @Override
    public Phase phase() { return phase; }

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
            tickCount = 0;
        }

        BlockPos feet = MovementHelper.footCell(level, bot);
        BlockPos from = spec.fromFoot();
        BlockPos to = spec.toFoot();

        // --- 完成判定：容差由上下文指定（D-027） ---
        // COLUMN：脚位方块正确 + Y 达标即完成（对齐 Baritone MovementDescend:235，链式中间段用）；
        // EXACT ：脚位正确 + 落地 + 水平 ≤0.3（安全关键站位用）。
        boolean arrived = tolerance == CompletionTolerance.COLUMN
                ? MovementHelper.isAtFootColumn(level, bot, to)
                : MovementHelper.isSettledAtFootPos(level, bot, to, 0.3D);
        if (arrived) {
            bot.controller().stopMovement();
            phase = Phase.POSTCONDITION_CHECK;
            phase = Phase.SUCCEEDED;
            return;
        }

        // --- Baritone 风格控制循环 ---
        BlockPos fakeDest = new BlockPos(
                to.getX() * 2 - from.getX(),
                to.getY(),
                to.getZ() * 2 - from.getZ());
        double fromStart = horizontalDistanceToCenter(from);
        double ab = horizontalDistanceToTarget();

        // 过冲落得比目标更低（落到下一级台阶/更深）→ 立即诚实失败，
        // 不再白等超时；D-024 细化允许过冲列是下一级台阶，但那属于"未到达目标"。
        if (bot.onGround() && MovementHelper.footCell(level, bot).getY() < to.getY()) {
            fail("DESCEND_OVERSHOT_BELOW_TARGET");
            return;
        }

        if (feet.equals(to)) {
            // 已在目标列：绝不再空中掉头（回冲根因）。COLUMN 直接滑行等完成判定；
            // EXACT 落地后低速向中心微调。
            if (tolerance == CompletionTolerance.EXACT && bot.onGround() && ab > 0.3D) {
                faceToward(to);
                bot.controller().setForward(0.4F);
            } else {
                bot.controller().setForward(0.0F);
            }
            bot.controller().setStrafing(0.0F);
        } else if (tickCount < FAKE_DEST_MAX_TICKS && fromStart < FAKE_DEST_CUTOFF) {
            faceToward(fakeDest);
            bot.controller().setForward(1.0F);
            bot.controller().setStrafing(0.0F);
        } else {
            faceToward(to);
            bot.controller().setForward(1.0F);
            bot.controller().setStrafing(0.0F);
        }

        tickCount++;
    }

    @Override
    public void cancel() {
        if (phase == Phase.SUCCEEDED || phase == Phase.FAILED || phase == Phase.CANCELLED) return;
        bot.controller().stopMovement();
        phase = Phase.CANCELLED;
        failureCode = "DESCEND_CANCELLED";
    }

    @Override
    public String failureCode() { return failureCode; }

    private boolean preconditionsHold() {
        BlockPos from = spec.fromFoot();
        BlockPos to = spec.toFoot();
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        if (dy != -1 || Math.abs(dx) + Math.abs(dz) != 1) return false;
        // 合法起点集对齐 Baritone MovementDescend.calculateValidPositions()：
        // {src, dest.above(), dest} —— 包含"已越过边缘、正在落入目标列"的位置，
        // 否则链式下降上一段带动量结束时，下一段会因起点不匹配而断链。
        BlockPos feet = MovementHelper.footCell(level, bot);
        if (!feet.equals(from)
                && !feet.equals(to)
                && !feet.equals(to.above())) return false;
        return MovementHelper.canWalkThrough(level, to)
                && MovementHelper.canWalkThrough(level, to.above())
                && MovementHelper.canWalkOn(level, to);
    }

    private void faceToward(BlockPos foot) {
        double dx = foot.getX() + 0.5D - bot.getX();
        double dz = foot.getZ() + 0.5D - bot.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        // Baritone 式旋转（LookBehavior:99-100）：只写身体 yaw，头/身交给原版 tickHeadTurn 管理。
        // 直接写 yHeadRot 而不同步 yBodyRot 会让客户端把头渲染成扭向一侧。
        bot.setYRot(yaw);
        bot.setYBodyRot(yaw);
    }

    private double horizontalDistanceToTarget() {
        double dx = bot.getX() - (spec.toFoot().getX() + 0.5D);
        double dz = bot.getZ() - (spec.toFoot().getZ() + 0.5D);
        return Math.sqrt(dx * dx + dz * dz);
    }

    private double horizontalDistanceToCenter(BlockPos foot) {
        double dx = bot.getX() - (foot.getX() + 0.5D);
        double dz = bot.getZ() - (foot.getZ() + 0.5D);
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void fail(String reason) {
        bot.controller().stopMovement();
        phase = Phase.FAILED;
        failureCode = reason;
    }

    private static BotPlayer requireBot(ServerPlayer bot) {
        if (!(bot instanceof BotPlayer botPlayer))
            throw new IllegalArgumentException("DescendExecution requires BotPlayer");
        return botPlayer;
    }

}