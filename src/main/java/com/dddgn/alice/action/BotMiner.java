package com.dddgn.alice.action;

import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.AStarPathfinder;
import com.dddgn.alice.pathing.Goal;
import com.dddgn.alice.pathing.PathExecutor;
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
import java.util.List;

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
    private PathExecutor executor;
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

        // 1) 选站位(只选一次) + A* 寻路
        if (standGoal == null) {
            // 0) 当前站位能否直接挖?(距离 + 视线无遮挡)——免去换站位/寻路,
            //    解决「bot 已在可挖位置却去找站位失败」的场景(如坑里挖坑壁)
            if (lineOfSightClear()
                    && bot.getEyePosition().distanceTo(target.getCenter()) <= MAX_REACH) {
                standGoal = bot.blockPosition().immutable();
                BotLog.info("当前站位即可挖掘: target={} stand={}",
                        target.toShortString(), standGoal.toShortString());
            } else {
                standGoal = pickStandPos(level);
                if (standGoal == null) {
                    failureReason = "no_stand_pos";
                    BotLog.warn("mine 失败: target={} reason={}", target.toShortString(), failureReason);
                    return Status.FAILED;
                }
                BotLog.info("站位已选: target={} stand={}", target.toShortString(), standGoal.toShortString());

                // 已在站位上(选中的站位就是当前脚位)则无需寻路,直接进入视线检查与挖掘
                if (!bot.blockPosition().equals(standGoal)) {
                    List<BlockPos> path = AStarPathfinder.computePath(level,
                            bot.blockPosition(), new Goal.GoalBlock(standGoal));
                    if (path.isEmpty()) {
                        failureReason = "no_path";
                        BotLog.warn("mine 失败: target={} reason={}(站位不可达)",
                                target.toShortString(), failureReason);
                        return Status.FAILED;
                    }
                    executor = new PathExecutor(bot, path);
                    BotLog.info("寻路成功: 路径 {} 段 → {}", path.size(), standGoal.toShortString());
                }
            }
        }

        // 2) 沿路径走向站位
        if (executor != null) {
            PathExecutor.Status pathStatus = executor.tick();
            if (pathStatus == PathExecutor.Status.FAILED) {
                failureReason = "path_failed";
                BotLog.warn("mine 失败: target={} reason={}", target.toShortString(), failureReason);
                return Status.FAILED;
            }
            if (pathStatus == PathExecutor.Status.MOVING) {
                return Status.MOVING;
            }
            executor = null;
        }

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

        faceTarget(); // 挖掘时持续朝向目标(视线行为,同步给客户端)

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

    /**
     * 选站位:第一轮目标周围 2 格(紧贴),无果则第二轮扩大到挖掘距离 4.5 内(远挖,
     * 目标被围/悬空时站远处挖)。每轮优先「视线无遮挡」候选(R4 决策 A)。
     * 全部失败时输出诊断统计后返回 null(no_stand_pos)。
     */
    private BlockPos pickStandPos(ServerLevel level) {
        BlockPos near = findStandPos(level, 2);
        if (near != null) {
            return near;
        }
        BlockPos far = findStandPos(level, 4);
        if (far != null) {
            BotLog.info("站位: 目标周围无紧贴站位,采用远挖站位 {}", far.toShortString());
            return far;
        }
        logStandDiagnostics(level);
        return null;
    }

    /**
     * 在目标周围 hRadius 水平半径、y±2 内找可站立空气格(脚位空+头位空+下方实心支撑,
     * 且从该格眼睛位置到目标中心 ≤ 挖掘距离,留 0.3 余量防抖动)。
     * 优先视线无遮挡候选,退回距离最近者。
     */
    private BlockPos findStandPos(ServerLevel level, int hRadius) {
        BlockPos bestClear = null;
        double bestClearDist = Double.MAX_VALUE;
        BlockPos bestAny = null;
        double bestAnyDist = Double.MAX_VALUE;
        for (int dx = -hRadius; dx <= hRadius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -hRadius; dz <= hRadius; dz++) {
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
                        // 挖掘距离过滤(眼睛到目标中心),防远挖站位站过去又 out_of_reach
                        Vec3 eyeAt = new Vec3(p.getX() + 0.5D, p.getY() + 1.62D, p.getZ() + 0.5D);
                        if (eyeAt.distanceTo(target.getCenter()) > MAX_REACH - 0.3D) {
                            continue;
                        }
                        double dist = bot.getEyePosition().distanceTo(p.getCenter());
                        if (dist < bestAnyDist) {
                            bestAnyDist = dist;
                            bestAny = p;
                        }
                        if (lineOfSightClearFrom(level, p) && dist < bestClearDist) {
                            bestClearDist = dist;
                            bestClear = p;
                        }
                    }
                }
            }
        }
        return bestClear != null ? bestClear : bestAny;
    }

    /** no_stand_pos 诊断:输出目标周围 2 格的候选统计与最近失败格,定位根因。 */
    private void logStandDiagnostics(ServerLevel level) {
        int footOk = 0;
        int headOk = 0;
        int belowOk = 0;
        int totalOk = 0;
        BlockPos nearestFail = null;
        double nearestFailDist = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos p = target.offset(dx, dy, dz);
                    if (p.equals(target) || p.equals(target.above())) {
                        continue;
                    }
                    BlockState foot = level.getBlockState(p);
                    BlockState head = level.getBlockState(p.above());
                    BlockState below = level.getBlockState(p.below());
                    boolean f = foot.isAir() && foot.getFluidState().isEmpty();
                    boolean h = head.isAir();
                    boolean b = !below.isAir() && below.getFluidState().isEmpty();
                    if (f) {
                        footOk++;
                    }
                    if (h) {
                        headOk++;
                    }
                    if (b) {
                        belowOk++;
                    }
                    if (f && h && b) {
                        totalOk++;
                    } else {
                        double d = bot.getEyePosition().distanceTo(p.getCenter());
                        if (d < nearestFailDist) {
                            nearestFailDist = d;
                            nearestFail = p;
                        }
                    }
                }
            }
        }
        BlockState ts = level.getBlockState(target);
        BotLog.warn("站位诊断: 目标={} 方块={} 流体={} 上方={}",
                target.toShortString(), ts.getBlock(), ts.getFluidState().getType(),
                level.getBlockState(target.above()).getBlock());
        BotLog.warn("站位诊断: 周围2格 foot空={} head空={} below实心={} 全满足={} 最近失败格={}(dist={})",
                footOk, headOk, belowOk, totalOk,
                nearestFail == null ? "-" : nearestFail.toShortString(),
                String.format(java.util.Locale.ROOT, "%.1f", nearestFailDist));
    }

    /** 从候选站位(眼睛位置)到目标中心视线是否无遮挡。 */
    private boolean lineOfSightClearFrom(ServerLevel level, BlockPos standPos) {
        Vec3 eye = new Vec3(standPos.getX() + 0.5D, standPos.getY() + 1.62D, standPos.getZ() + 0.5D);
        Vec3 center = target.getCenter();
        ClipContext context = new ClipContext(eye, center,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot);
        BlockHitResult hit = level.clip(context);
        return hit.getType() != HitResult.Type.BLOCK || hit.getBlockPos().equals(target);
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

    /** 视线朝向目标:让 bot 转身面向目标方块中心,并同步朝向给客户端。
     * 注意:MC 玩家模型「身体朝向(yRot)」与「头部朝向(yHeadRot)」相互独立,
     * 必须同时设置,否则会出现「身子转过来了头却不朝目标」的诡异视角。 */
    private void faceTarget() {
        Vec3 eye = bot.getEyePosition();
        Vec3 center = target.getCenter();
        double dx = center.x - eye.x;
        double dy = center.y - eye.y;
        double dz = center.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(
                Math.atan2(-dy, Math.sqrt(dx * dx + dz * dz)));
        bot.setYRot(yaw);       // 身体朝向
        bot.setXRot(pitch);     // 俯仰
        bot.setYHeadRot(yaw);   // 头部朝向(关键:与身体独立)
        byte yawByte = (byte) (yaw * 256.0F / 360.0F);
        byte pitchByte = (byte) (pitch * 256.0F / 360.0F);
        bot.connection.send(new net.minecraft.network.protocol.game.ClientboundRotateHeadPacket(
                bot, (byte) (bot.getYHeadRot() * 256.0F / 360.0F)));
        bot.connection.send(new net.minecraft.network.protocol.game.ClientboundMoveEntityPacket.Rot(
                bot.getId(), yawByte, pitchByte, bot.onGround()));
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
