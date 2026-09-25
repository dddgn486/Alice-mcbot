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

import java.util.Objects;

/**
 * {@link MovementType#DOWNWARD}：垂直下落 1 格（破坏脚下 → 掉进 1 格深的洞）。
 *
 * <p>对照 Baritone {@code MovementDownward}。破坏走 {@link BlockInteraction}/{@link BlockBreakSession}
 * （工具选择 + 进度 + 广播），不使用瞬间销毁。
 *
 * <p>Baritone 原样语义（D-050）：脚下可破坏 + 落点有支撑；破坏期间保持原地，
 * 破坏完成后**先把身体挪到目标格中心**（偏移 > 0.2 ⇒ 朝中心走，Baritone `MovementDownward:86-94`），
 * 够靠中心才原地等自然掉落（统一完成契约 + 分段容差 D-027）。
 */
public final class DownwardExecution implements MovementExecution {
    /**
     * "已经够靠格中心了"的阈值：**0.2**（Baritone `MovementDownward:90` 的 `ab < 0.2`）。
     *
     * <p>它不是一个随手取的数：几何上 0.2 = `格半宽 0.5 − AABB 半宽 0.3` ⇒ 到了这个值，
     * AABB 才**不再压到邻列**，"脚下那个洞"才真的接得住 bot。
     */
    private static final double CRAWL_TO_CENTER_EPSILON = 0.2D;

    private final MovementSpec spec;
    private final BotPlayer bot;
    private final ServerLevel level;
    private final String botId;
    private final String sessionId;
    private final CompletionTolerance tolerance;
    /** 授权身份（D-082）。 */
    private final WriteGrant grant;

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
        this.grant = WriteGrant.of(context.requester(), WriteReason.DESCEND_FOOT);
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
                breakSession = BlockInteraction.beginBreak(bot, level, target, grant);
                if (breakSession == null) {
                    fail("WRITE_BUDGET_EXHAUSTED");
                    return;
                }
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

        // 方块已破：**只有"被邻列方块的角托在洞沿上"时才把自己挪到格中心**，其余情况零水平输入。
        // ⭐ 机制 A（`D-445` 裁定三；用户 2026-09-25 目视确认「停在洞沿上像悬空、刚好蹭在边缘上」）：
        // 原来这里只有 `stopMovement()` ⇒ bot 静止在**邻列方块的角上**（离心 > 0.2 ⇒ AABB 压进邻列
        // 0.03~0.1 格）⇒ 原版碰撞判 `onGround=true`、重力每 tick 被清零 ⇒ **永不下落**。
        // 真机形态：两条 145 tick `segment_timeout`、`input=forward 0.00`
        //（取证 `docs/reviews/2026-09-25-真机第三轮-根因取证.md` §4）。
        // 对照 Baritone：`MovementDownward:86-94`（偏移 ≥ 0.2 ⇒ `moveTowards` 朝格中心走，够近才等）、
        // `MovementFall:174-180`（"moving to the 0.5 center **not the edge**"）。
        // ⇒ `FallExecution:106-116` 与 `DescendExecution:107-116` **早就有这一支**，只有本类漏了。
        //
        // ⚠️ **触发条件收窄到"真的被托住"**（`supportedByNeighbourCorner`），不是"离心 > 0.2"：
        // Baritone 可以无条件 `moveTowards`（它有整套 Movement 状态机），而 Alice 这条路径上
        // "离心"是正常状态（`COLUMN` 容差不要求居中）—— 无条件给水平输入会改掉**所有**正常下落的落点。
        // CORE 电池实测（2026-09-25）：伐木步按 `nearest` 选树 ⇒ 落点一动就换一棵树 ⇒ `lumber_job=FAIL`。
        // ⇒ 正常下落**零水平输入**（对既有路径零扰动），只在洞沿上才走过去。
        BlockPos feetCell = MovementHelper.footCell(level, bot);
        if (bot.onGround()
                && MovementHelper.supportedByNeighbourCorner(level, bot, feetCell)
                && MovementHelper.horizontalDistanceToCenter(bot, feetCell) > CRAWL_TO_CENTER_EPSILON) {
            MovementHelper.faceCellCenter(bot, feetCell);
            bot.controller().setForward(1.0F);
            bot.controller().setStrafing(0.0F);
            return;
        }
        // 其余情况：**零水平输入** —— 包括"已离地（正在掉落）"（1 格落差只有几 tick，给输入反而
        // 可能把落点推出目标列；与 Baritone `MovementFall` 的差别在此：那是**多格**长距下落，
        // 沿途必须持续修正，那件事由 `FallExecution:106-116` 负责，本类只负责 1 格）。
        bot.controller().stopMovement();
    }

    /**
     * K-3：向下挖一格会**改变脚位所在的高度**（挖穿后回落）⇒ 空中/未落地时不可取消。
     * 破坏本身不产生"位移承诺"，但"脚下的方块已经被挖掉了"是承诺点。
     */
    @Override
    public boolean safeToCancel() {
        if (phase() != Phase.EXECUTING) {
            return true;
        }
        return bot.onGround();
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
        return level.getBlockState(to).isAir() || BlockInteraction.breakable(bot, level, to, grant);
    }

    private boolean postconditionHolds() {
        BlockPos to = spec.toFoot();
        return tolerance == CompletionTolerance.COLUMN
                ? MovementHelper.isAtFootColumn(level, bot, to)
                : MovementHelper.isSettledAtFootPos(level, bot, to, 0.3D);
    }

    private static BotPlayer requireBot(ServerPlayer bot) {
        if (!(bot instanceof BotPlayer botPlayer)) {
            throw new IllegalArgumentException("DownwardExecution requires BotPlayer");
        }
        return botPlayer;
    }
}
