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
 * {@link MovementType#PILLAR}：垂直上升 1 格（跳跃中在脚下放置方块，落在上面）。
 *
 * <p>对照 Baritone {@code MovementPillar.updateState}（baritone-1.20.1 @8c55ad0
 * {@code movements/MovementPillar.java:126-231}）：
 * <ol>
 *   <li>先把身体水平居中到 {@code src} 列（{@code dist > 0.17} → MOVE_FORWARD）；</li>
 *   <li>水平静止后按 JUMP（{@code y < dest.y} 期间按住）；</li>
 *   <li>**只有当 {@code player().position().y > dest.getY() + 0.1} 且正在潜行时才右键放置**
 *       ——脚必须已经升到待放置格顶面之上，否则原版 {@code Level.isUnobstructed}
 *       会因自身碰撞箱与方块相交而拒绝放置（这就是"起跳后放脚下"的物理门槛）；</li>
 *   <li>放好方块后自然落回，稳定在 {@code dest} 完成。</li>
 * </ol>
 *
 * <p>Alice 差异：放置走服务端 {@link BlockInteraction#placeAt}（服务端构造 BlockHitResult，
 * 等价于客户端上报的命中包），因此不需要潜行姿态与视线射线；其余阈值与 Baritone 一致。
 */
public final class PillarExecution implements MovementExecution {
    /** 水平居中阈值（Baritone `MovementPillar:196` 的 0.17）。 */
    private static final double CENTER_TOLERANCE = 0.17D;
    /** 允许放置的脚部高度：目标格顶面 + 0.1（Baritone `MovementPillar:227`）。 */
    private static final double PLACE_HEIGHT_MARGIN = 0.1D;
    /** 段内最大 tick（对齐 PathSession 的 per-movement 超时 100）。 */
    private static final int MAX_TICKS = 100;

    private final MovementSpec spec;
    private final BotPlayer bot;
    private final ServerLevel level;
    private final String botId;
    private final String sessionId;
    private final CompletionTolerance tolerance;

    private Phase phase = Phase.NOT_STARTED;
    private String failureCode;
    private int tickCount;
    private boolean jumped;
    private boolean placed;

    PillarExecution(MovementSpec spec, LiveExecutionContext context) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.bot = requireBot(context.bot());
        this.level = Objects.requireNonNull(context.level(), "level");
        this.botId = bot.getUUID().toString();
        this.sessionId = context.sessionId();
        this.tolerance = context.tolerance();
    }

    /** 需要被放置方块的位置（起跳前所在格 = 目标格下方）。 */
    public static BlockPos placePos(MovementSpec spec) {
        return spec.fromFoot();
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
                fail("PILLAR_INVALID_PRECONDITION");
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

        tickCount++;
        if (tickCount > MAX_TICKS) {
            fail("PILLAR_TIMEOUT");
            return;
        }

        BlockPos from = spec.fromFoot();
        BlockPos to = spec.toFoot();

        // 1) 水平居中到 from 列（Baritone：dist > 0.17 → 前进）
        if (!placed) {
            double dx = from.getX() + 0.5D - bot.getX();
            double dz = from.getZ() + 0.5D - bot.getZ();
            if (Math.sqrt(dx * dx + dz * dz) > CENTER_TOLERANCE) {
                faceTowardsCenter(from);
                bot.controller().setForward(1.0F);
                bot.controller().setStrafing(0.0F);
                return;
            }
        }
        bot.controller().setForward(0.0F);

        // 2) 起跳（水平已静止）
        if (!jumped) {
            if (bot.onGround()) {
                bot.controller().jumpOnce();
                jumped = true;
            }
            return;
        }

        // 3) 脚升到待放置格顶面之上后，在脚下放置方块
        if (!placed) {
            if (bot.getY() >= to.getY() + PLACE_HEIGHT_MARGIN) {
                if (BlockInteraction.findPlaceableSlot(bot) < 0) {
                    fail("PLACE_RESOURCE_UNAVAILABLE");
                    return;
                }
                BlockInteraction.PlaceResult result =
                        BlockInteraction.placeAt(bot, level, from, false);
                if (result != BlockInteraction.PlaceResult.PLACED) {
                    fail("PILLAR_PLACE_FAILED");
                    return;
                }
                placed = true;
                BotLog.info("[Pillar] placed pos={} from={} to={} feetY={}",
                        from.toShortString(), from.toShortString(), to.toShortString(),
                        String.format("%.3f", bot.getY()));
                return;
            }
            if (bot.onGround()) {
                // 跳起失败（撞头/时机错过）→ 重跳一次，仍失败由段超时兜底
                bot.controller().jumpOnce();
            }
            return;
        }

        // 4) 方块已放置：等待自然落回并稳定（完成契约由 postconditionHolds 判定）
        bot.controller().stopMovement();
    }

    @Override
    public void cancel() {
        if (phase == Phase.SUCCEEDED || phase == Phase.FAILED || phase == Phase.CANCELLED) {
            return;
        }
        bot.controller().stopMovement();
        phase = Phase.CANCELLED;
        failureCode = "PILLAR_CANCELLED";
    }

    @Override
    public String failureCode() {
        return failureCode;
    }

    private void fail(String reason) {
        bot.controller().stopMovement();
        phase = Phase.FAILED;
        failureCode = reason;
        BotLog.warn("[Pillar] failed session={} from={} to={} code={} feet={} onGround={}",
                sessionId, spec.fromFoot().toShortString(), spec.toFoot().toShortString(),
                reason, bot.blockPosition().toShortString(), bot.onGround());
    }

    private boolean preconditionsHold() {
        BlockPos from = spec.fromFoot();
        BlockPos to = spec.toFoot();
        if (to.getY() != from.getY() + 1 || to.getX() != from.getX() || to.getZ() != from.getZ()) {
            return false;
        }
        // D-026 合法位置集
        BlockPos feet = bot.blockPosition();
        if (!feet.equals(from) && !feet.equals(to)) {
            return false;
        }
        // 起跳列净空：目标格（身体）+ 头顶格；放置格（当前脚位）必须可替换
        if (!MovementHelper.canWalkThrough(level, to)
                || !MovementHelper.canWalkThrough(level, to.above())
                || !MovementHelper.canWalkThrough(level, from)) {
            return false;
        }
        return BlockInteraction.findPlaceableSlot(bot) >= 0
                && BlockInteraction.hasPlacementFace(level, from);
    }

    private boolean postconditionHolds() {
        BlockPos to = spec.toFoot();
        return tolerance == CompletionTolerance.COLUMN
                ? MovementHelper.isAtFootColumn(bot, to)
                : MovementHelper.isSettledAtFootPos(level, bot, to, 0.3D);
    }

    private void faceTowardsCenter(BlockPos from) {
        double dx = from.getX() + 0.5D - bot.getX();
        double dz = from.getZ() + 0.5D - bot.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        bot.setYRot(yaw);
        bot.setYBodyRot(yaw);
    }

    private static BotPlayer requireBot(ServerPlayer bot) {
        if (!(bot instanceof BotPlayer botPlayer)) {
            throw new IllegalArgumentException("PillarExecution requires BotPlayer");
        }
        return botPlayer;
    }
}
