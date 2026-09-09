package com.dddgn.alice.pathing.core;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;

/** R2-C 一级上升 Ascend 的主线程执行对象。 */
public final class AscendExecution implements MovementExecution {
    private final MovementSpec spec;
    private final BotPlayer bot;
    private final ServerLevel level;
    private final String botId;
    private final String sessionId;
    private final CompletionTolerance tolerance;
    private Phase phase = Phase.NOT_STARTED;
    private String failureCode;
    private int settlingTicks = 0;
    private static final int MAX_SETTLING_TICKS = 20;

    AscendExecution(MovementSpec spec, LiveExecutionContext context) {
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
                fail("ASCEND_INVALID_PRECONDITION");
                return;
            }
            phase = Phase.EXECUTING;
        }

        if (phase == Phase.EXECUTING) {
            // 统一完成契约（D-026）：脚位正确 + 落地 + 水平到位才算完成
            if (postconditionHolds()) {
                bot.controller().stopMovement();
                phase = Phase.POSTCONDITION_CHECK;
                phase = Phase.SUCCEEDED;
                return;
            }
            // 已到达目标高度并落地 → 进入结算：不再跳跃，只做水平居中
            // （修复“Y 到位即停手 → 残余动量造成水平偏移”的旧缺陷）
            if (bot.onGround() && bot.blockPosition().getY() >= spec.toFoot().getY()) {
                phase = Phase.SETTLING;
                settlingTicks = 0;
                return;
            }
            driveTowardTarget();
            if (shouldJump()) {
                bot.controller().jumpOnce();
            }
            return;
        }

        if (phase == Phase.SETTLING) {
            settlingTicks++;
            if (postconditionHolds()) {
                bot.controller().stopMovement();
                phase = Phase.POSTCONDITION_CHECK;
                phase = Phase.SUCCEEDED;
                return;
            }
            if (settlingTicks > MAX_SETTLING_TICKS) {
                fail("ASCEND_SETTLING_TIMEOUT");
                return;
            }
            // 已在目标层但未居中：继续向目标列中心微调，而不是停手等超时
            driveTowardTarget();
        }
    }

    @Override
    public void cancel() {
        if (phase == Phase.SUCCEEDED || phase == Phase.FAILED || phase == Phase.CANCELLED) {
            return;
        }
        bot.controller().stopMovement();
        phase = Phase.CANCELLED;
        failureCode = "ASCEND_CANCELLED";
    }

    @Override
    public String failureCode() {
        return failureCode;
    }

    private boolean preconditionsHold() {
        BlockPos from = spec.fromFoot();
        BlockPos to = spec.toFoot();
        // 运行期守卫（对照 Baritone headBonkClear:233-243 的净空判定）：
        // 头顶被挡时不起跳，避免"跳起来撞天花板"的空跳循环。
        // 注：不采用 Baritone 的"净空即起跳"提前分支——Alice 段间无动量，提前起跳会浪费跳跃（D-041）。
        if (!MovementHelper.canWalkThrough(bot.serverLevel(), from.above(2))) {
            return false;
        }
        
        // 验证几何约束
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        int horizontalDist = Math.abs(dx) + Math.abs(dz);
        if (dy != 1 || horizontalDist != 1) {
            return false;
        }
        
        if (!bot.blockPosition().equals(from) && !bot.blockPosition().equals(to)) {
            return false;
        }
        
        // 验证目标可通行
        if (!MovementHelper.canWalkThrough(level, to)
                || !MovementHelper.canWalkThrough(level, to.above())
                || !MovementHelper.canWalkOn(level, to)) {
            return false;
        }
        
        // 验证起点头部空间
        return MovementHelper.canWalkThrough(level, from.above(2));
    }

    private boolean postconditionHolds() {
        // D-027：容差由会话指定（中间段 COLUMN、最终段 EXACT），执行器不得自行硬编码
        return tolerance == CompletionTolerance.COLUMN
                ? MovementHelper.isAtFootColumn(bot, spec.toFoot())
                : MovementHelper.isSettledAtFootPos(level, bot, spec.toFoot(), 0.3D);
    }

    /**
     * Baritone MovementAscend 式跳跃门控（D-025：假人台阶高度已降为 0.6，
     * 一格方块必须跳跃才能上）：
     * <ul>
     *   <li>必须在地面、且尚未到达目标高度；</li>
     *   <li>侧向速度 &lt;= 0.1（先对准再跳，避免斜着起跳落回原地）；</li>
     *   <li>沿运动轴距离 &lt;= 1.2 且横向偏移 &lt;= 0.2（够近才跳，避免撞头/跳空）。</li>
     * </ul>
     */
    private boolean shouldJump() {
        if (!bot.onGround()) {
            return false;
        }
        if (bot.blockPosition().getY() >= spec.toFoot().getY()) {
            return false;
        }
        BlockPos from = spec.fromFoot();
        BlockPos to = spec.toFoot();
        int xAxis = Math.abs(from.getX() - to.getX());
        int zAxis = Math.abs(from.getZ() - to.getZ());
        double targetX = to.getX() + 0.5D;
        double targetZ = to.getZ() + 0.5D;
        double flatDist = xAxis * Math.abs(targetX - bot.getX()) + zAxis * Math.abs(targetZ - bot.getZ());
        double sideDist = zAxis * Math.abs(targetX - bot.getX()) + xAxis * Math.abs(targetZ - bot.getZ());
        double lateralMotion = xAxis * bot.getDeltaMovement().z + zAxis * bot.getDeltaMovement().x;
        if (Math.abs(lateralMotion) > 0.1D) {
            return false;
        }
        return flatDist <= 1.2D && sideDist <= 0.2D;
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
            throw new IllegalArgumentException("AscendExecution requires BotPlayer");
        }
        return botPlayer;
    }
}
