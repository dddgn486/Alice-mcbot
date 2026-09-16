package com.dddgn.alice.pathing.core;

import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.action.WriteGrant;
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
 * <p><b>水柱例外（D-244，对照 {@code MovementPillar.java:77-82 / :150-161}）</b>：起点格与目的地格
 * **都是水**时，本段是"**水柱里上浮**"——按住跳跃上浮、**不放方块**，完成口径是 Baritone 的
 * "脚位到格即成功"（水里没有 `onGround`、也没有支撑 ⇒ D-026 的"已落地"口径永不成立）。
 *
 * <p>Alice 差异：放置走服务端 {@link BlockInteraction#placeAt}（服务端构造 BlockHitResult，
 * 等价于客户端上报的命中包），因此不需要潜行姿态与视线射线；其余阈值与 Baritone 一致。
 * 水柱那支还有第二处已知差异：Baritone 靠"朝上看 + 前进"的原版游泳耦合上升（`setTarget` + MOVE_FORWARD），
 * 服务端假人没有这个耦合 ⇒ Alice 显式按住跳跃（D-243 起）。
 */
public final class PillarExecution implements MovementExecution {

    /** 放置授权（D-082）。 */
    private final WriteGrant grant;
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

    /**
     * D-244：本段是不是"**水柱里的上浮**"（起点格与目的地格**都是水**）。
     *
     * <p>对照 Baritone {@code MovementPillar.java:150}（执行）与 {@code :77-82}（成本）：
     * 水柱里上升**不放方块**（`LADDER_UP_ONE_COST`，"allow ascending pillars of water, but only if we're
     * already in one"）；完成口径也随之变成 Baritone 的"脚位到格即成功"（{@code :157}），
     * 因为水里既没有 `onGround` 也没有支撑，D-026 那套"已落地"永远不成立。
     *
     * <p>只在**进入执行**时读一次世界（`from`/`to` 来自 spec，不随执行漂移）：读早了会被上一段的放置改掉。
     */
    private boolean swimColumn;
    private boolean swimLogged;

    PillarExecution(MovementSpec spec, LiveExecutionContext context) {
        this.grant = WriteGrant.of(context.requester(), WriteReason.STEP_PLACEMENT);
        this.spec = Objects.requireNonNull(spec, "spec");
        this.bot = requireBot(context.bot());
        this.level = Objects.requireNonNull(context.level(), "level");
        this.botId = bot.getUUID().toString();
        this.sessionId = context.sessionId();
        this.tolerance = context.tolerance();
    }

    /** 需要被放置方块的位置（起跳前所在格 = 目标格下方）。 */
    public static BlockPos placePos(MovementSpec spec) {
        return placePos(spec.fromFoot());
    }

    /**
     * {@link #placePos(MovementSpec)} 的坐标版本：**规划期校验**（{@code SelfWriteConsistency}，D-250/②′）
     * 要在没有 `MovementSpec` 的情况下算出"这条边会放下哪一格"。**唯一定义仍在本处**，别在别处抄。
     */
    public static BlockPos placePos(BlockPos fromFoot) {
        return fromFoot;
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
            // D-244：水柱判定必须在这里（进入执行时）读世界，不能放进构造函数 —— 构造与执行之间，
            // 上一段可能刚往 from 那一格放过方块（那就不再是水柱了）。
            // 前置（放置资源/放置面）**故意不放开**：规划期 `appendPillar` 用的就是同一组前提
            // （"可规划即可执行"，K-4）—— 水柱省的是**方块本身**，不是"要不要带方块"。
            swimColumn = MovementHelper.isWater(level, spec.fromFoot())
                    && MovementHelper.isWater(level, spec.toFoot());
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
        // D-243：**水里改为持续按住跳跃**（原版：水里按住跳跃即上浮；本仓 D-237 的 `SurvivalFloatTask` 已实测有效）。
        // 为什么不能沿用一次性 `jumpOnce`：水里抬升只有 ~0.5 格，永远到不了放置高度
        // ⇒ 落地回同一格 ⇒ 实测 `wastedJumpLandings=3` ⇒ `SEGMENT_NO_PROGRESS`（灌水竖坑那一档）。
        // 对照 Baritone `MovementPillar.java:150-161` 的水柱分支（居中 + 游上去、靠"朝上看 + 前进"耦合）：
        // 服务端假人没有那个耦合，所以这里**显式**按住跳跃；"整列都是水时不放方块"的省料分支见 D-244。
        boolean inWater = MovementHelper.isWater(level, MovementHelper.footCell(level, bot));
        if (inWater) {
            bot.controller().setJumping(true);
            jumped = true;
            if (swimColumn) {
                // D-244：**水柱上浮不放方块**（Baritone `MovementPillar.java:77-82` 的 `LADDER_UP_ONE_COST`）。
                // 水平居中复用上面第 1 步（from 与 to 同列，`CENTER_TOLERANCE=0.17` 比 Baritone 水柱那支的
                // 0.2 更严）；完成判定走 `postconditionHolds()` 的水柱一支（Baritone：`playerFeet().equals(dest)`）。
                if (!swimLogged) {
                    swimLogged = true;
                    BotLog.info("[Pillar] swim from={} to={} feetY={}（水柱上浮：不放方块，D-244）",
                            from.toShortString(), to.toShortString(), String.format("%.3f", bot.getY()));
                }
                return;
            }
            if (!placed && bot.getY() < to.getY() + PLACE_HEIGHT_MARGIN) {
                return;                              // 还没浮到放置高度：继续按着跳跃
            }
        } else if (!jumped) {
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
                        BlockInteraction.placeAt(bot, level, from, false,
                                grant);
                if (result == BlockInteraction.PlaceResult.BUDGET_EXHAUSTED) {
                    fail("WRITE_BUDGET_EXHAUSTED");
                    return;
                }
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

    /**
     * K-3（对齐 Baritone `MovementAscend.safeToCancel` 的 `ticksWithoutPlacement == 0` 思路）：
     * **一旦放下方块就已经提交** —— 必须完成"落回/站上去"，否则会留一个悬空块且 bot 状态不定。
     */
    @Override
    public boolean safeToCancel() {
        if (phase() != Phase.EXECUTING) {
            return true;
        }
        return !placed;
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
                reason, MovementHelper.footCell(level, bot).toShortString(), bot.onGround());
    }

    private boolean preconditionsHold() {
        BlockPos from = spec.fromFoot();
        BlockPos to = spec.toFoot();
        if (to.getY() != from.getY() + 1 || to.getX() != from.getX() || to.getZ() != from.getZ()) {
            return false;
        }
        // D-026 合法位置集
        BlockPos feet = MovementHelper.footCell(level, bot);
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
        if (swimColumn) {
            // D-244（Baritone `MovementPillar.java:157`：`playerFeet().equals(dest)` → SUCCESS）：
            // 水柱里**没有落地这回事** —— `onGround` 恒假且脚下没有支撑 ⇒ D-026 的"已落地/居中"两套
            // 完成口径都永远不会成立（这就是 D-242 里"规划得到、执行不了"的另一半根因）。
            // 代价（如实登记）：水柱段结束时 bot 是"浮着"的、没有支撑 —— 它必须由**下一段**接着往上走
            // （水柱里每一段都这样接），或由后续落点提供支撑；"浮在水面起不来"（无可站支撑的落点）
            // 仍是**合法位置集**那一档的事（D-243/D-244 未覆盖）。
            return MovementHelper.footCell(level, bot).equals(to);
        }
        return tolerance == CompletionTolerance.COLUMN
                ? MovementHelper.isAtFootColumn(level, bot, to)
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
