package com.dddgn.alice.action;

import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 挖掘状态机(M0 核心,四条验收标准第 1/4 条的首次落地)。
 * <p>
 * 流程:选可达站位(目标周围 2 格内、脚位+头位空、下方有支撑)
 * → 直线走到位 → <b>视线无遮挡检查(raycast,根治隔空挖)</b>
 * → 原版 4.5 格距离检查 → 模拟原版挖掘协议包 + 进度累加 → 破坏方块。</p>
 * <p>
 * ⚠️ 审查点 3:挖掘进度用原版模拟(handleBlockBreakAction + getDestroyProgress),
 * 工具速度/时运等原版机制自动生效;不用瞬挖。</p>
 */
public final class BotMiner {

    public enum Status { MOVING, MINING, DONE, FAILED }

    private static final double MAX_REACH = 4.5D;
    private static final int MINE_TIMEOUT_TICKS = 200;

    private final ServerPlayer bot;
    private final BlockPos target;
    private BotWalker walker;
    private BlockPos standGoal;
    private boolean started;
    private float progress;
    private int elapsed;
    private String failureReason = "";
    private Direction face;
    /** 挖掘开始时的 bot 位置与眼睛距离(供自动化验收断言「是否隔空挖」)。 */
    private BlockPos mineStartPos;
    private double mineStartEyeDist;

    public BotMiner(ServerPlayer bot, BlockPos target) {
        this.bot = bot;
        this.target = target;
    }

    public String failureReason() {
        return failureReason;
    }

    /** 挖掘开始时的 bot 位置(隔空挖验收断言用;从未开始挖则为 null)。 */
    public BlockPos mineStartPos() {
        return mineStartPos;
    }

    /** 挖掘开始时眼睛到目标中心的距离(隔空挖验收断言用)。 */
    public double mineStartEyeDist() {
        return mineStartEyeDist;
    }

    public Status tick() {
        ServerLevel level = (ServerLevel) bot.level();
        BlockState state = level.getBlockState(target);
        if (state.isAir()) {
            return Status.DONE;
        }

        // 1) 选站位(只选一次)
        if (standGoal == null) {
            standGoal = pickStandPos(level);
            if (standGoal == null) {
                failureReason = "no_stand_pos";
                BotLog.warn("mine 失败: target={} reason={}", target.toShortString(), failureReason);
                return Status.FAILED;
            }
            BotLog.info("站位已选: target={} stand={}", target.toShortString(), standGoal.toShortString());
        }

        // 2) 走向站位
        if (!atStandPos()) {
            if (walker == null) {
                walker = new BotWalker(bot, standGoal.getCenter());
                BotLog.info("开始移动: from={} to={}",
                        bot.blockPosition().toShortString(), standGoal.toShortString());
            }
            BotWalker.Status walkStatus = walker.tick();
            if (walkStatus == BotWalker.Status.FAILED) {
                failureReason = "walk_failed";
                BotLog.warn("mine 失败: target={} reason={}", target.toShortString(), failureReason);
                return Status.FAILED;
            }
            return Status.MOVING;
        }
        walker = null;

        // 3) 视线无遮挡检查(M0 验收核心:根治隔空挖)
        if (!lineOfSightClear()) {
            failureReason = "line_of_sight_blocked";
            BotLog.warn("mine 失败(隔空挖拦截): target={} reason={}",
                    target.toShortString(), failureReason);
            abortMining(level);
            return Status.FAILED;
        }

        // 4) 距离检查(对齐原版 4.5)
        if (bot.getEyePosition().distanceTo(target.getCenter()) > MAX_REACH) {
            failureReason = "out_of_reach";
            BotLog.warn("mine 失败: target={} reason={}", target.toShortString(), failureReason);
            abortMining(level);
            return Status.FAILED;
        }

        // 5) 挖掘(原版协议包 + 进度模拟)
        if (!started) {
            face = faceToward(target);
            bot.gameMode.handleBlockBreakAction(target,
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    face, level.getMaxBuildHeight(), -1);
            state.attack(level, target, bot);
            started = true;
            mineStartPos = bot.blockPosition().immutable();
            mineStartEyeDist = bot.getEyePosition().distanceTo(target.getCenter());
            BotLog.info("开始挖掘: target={} face={} eye_dist={} bot_pos={}",
                    target.toShortString(), face,
                    String.format(java.util.Locale.ROOT, "%.1f", mineStartEyeDist),
                    mineStartPos.toShortString());
        }

        progress += state.getDestroyProgress(bot, level, target);
        level.destroyBlockProgress(bot.getId(), target, (int) (progress * 10.0F));
        bot.swing(InteractionHand.MAIN_HAND);

        if (progress >= 1.0F) {
            bot.gameMode.destroyBlock(target);
            level.destroyBlockProgress(bot.getId(), target, -1);
            started = false;
            progress = 0.0F;
            BotLog.info("挖掘完成: target={}", target.toShortString());
            return Status.DONE;
        }

        elapsed++;
        if (elapsed > MINE_TIMEOUT_TICKS) {
            failureReason = "mine_timeout";
            abortMining(level);
            return Status.FAILED;
        }
        return Status.MINING;
    }

    /** 目标周围 2 格内的可站立空气格(脚位空+头位空+下方实心支撑),取距离最近者。 */
    private BlockPos pickStandPos(ServerLevel level) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos p = target.offset(dx, dy, dz);
                    if (p.equals(target) || p.equals(target.above())) {
                        continue; // 不站目标方块本身/其正上方(避免与挖脚下方块冲突)
                    }
                    BlockState foot = level.getBlockState(p);
                    BlockState head = level.getBlockState(p.above());
                    BlockState below = level.getBlockState(p.below());
                    if (foot.isAir() && foot.getFluidState().isEmpty()
                            && head.isAir()
                            && !below.isAir() && below.getFluidState().isEmpty()) {
                        double dist = bot.getEyePosition().distanceTo(p.getCenter());
                        if (dist < bestDist) {
                            bestDist = dist;
                            best = p;
                        }
                    }
                }
            }
        }
        return best;
    }

    private boolean atStandPos() {
        double dx = bot.getX() - (standGoal.getX() + 0.5D);
        double dz = bot.getZ() - (standGoal.getZ() + 0.5D);
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return horizontal <= 0.6D
                && Math.abs(bot.getY() - standGoal.getY()) <= 1.1D;
    }

    /** 眼睛 → 目标方块中心 raycast,途中任何方块遮挡即视为不可挖。 */
    private boolean lineOfSightClear() {
        Vec3 eye = bot.getEyePosition();
        Vec3 center = target.getCenter();
        ClipContext context = new ClipContext(eye, center,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot);
        BlockHitResult hit = bot.level().clip(context);
        return hit.getType() != HitResult.Type.BLOCK || hit.getBlockPos().equals(target);
    }

    /** 从眼睛朝方块中心的方向作为破坏面(取主轴)。 */
    private Direction faceToward(BlockPos pos) {
        return Direction.getNearest(
                pos.getX() + 0.5 - bot.getEyePosition().x,
                pos.getY() + 0.5 - bot.getEyePosition().y,
                pos.getZ() + 0.5 - bot.getEyePosition().z);
    }

    private void abortMining(ServerLevel level) {
        if (started) {
            bot.gameMode.handleBlockBreakAction(target,
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                    face, level.getMaxBuildHeight(), -1);
            level.destroyBlockProgress(bot.getId(), target, -1);
            started = false;
            progress = 0.0F;
        }
    }
}
