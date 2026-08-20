package com.dddgn.alice.action;

import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.SurfacePathfinder;
import com.dddgn.alice.pathing.PathExecutor;
import com.dddgn.alice.protection.BlockBreakSafety;
import com.dddgn.alice.survival.FluidRiskPolicy;
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
import java.util.ArrayList;
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
    private List<BlockPos> standCandidates;
    private boolean started;
    private float progress;
    private int elapsed;
    private int pathRetries;
    private boolean standSearchLimit;
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
        String refusalReason = BlockBreakSafety.explicitTargetRefusal(bot, target);
        if (refusalReason == null) {
            refusalReason = FluidRiskPolicy.miningRefusal(bot, target);
        }
        if (refusalReason != null) {
            failureReason = refusalReason;
            BotLog.warn("mine 被安全策略拦截: target={} reason={}", target.toShortString(), failureReason);
            abortMining(level);
            return Status.FAILED;
        }
        boolean mustReposition = BlockBreakSafety.requiresReposition(bot, target);

        // 1) 选站位(候选逐个尝试, A* 不通试下一个) + A* 寻路
        if (standGoal == null && standCandidates == null) {
            // 0) 当前站位能否直接挖?(距离 + 视线无遮挡)——免去换站位/寻路,
            //    解决「bot 已在可挖位置却去找站位失败」的场景(如坑里挖坑壁)
            if (!mustReposition && lineOfSightClear()
                    && bot.getEyePosition().distanceTo(target.getCenter()) <= MAX_REACH) {
                standGoal = bot.blockPosition().immutable();
                BotLog.info("当前站位即可挖掘: target={} stand={}",
                        target.toShortString(), standGoal.toShortString());
            } else {
                standCandidates = pickStandCandidates(level);
                if (standCandidates.isEmpty()) {
                    logStandDiagnostics(level);
                    failureReason = "no_stand_pos";
                    BotLog.warn("mine 失败: target={} reason={}", target.toShortString(), failureReason);
                    return Status.FAILED;
                }
                BotLog.info("站位候选 {} 个(优先级: 目标下方>同平面>上方), 逐个尝试",
                        standCandidates.size());
            }
        }

        // 先比较所有“直线可挖”的曲面站位，再退回视线受阻候选。不能因为目标下方
        // 分组排在同平面前面，就先走远路到一个最终还要清障的站位。
        if (standGoal == null && standCandidates != null) {
            StandChoice choice = chooseReachableStand(level);
            if (choice == null) {
                failureReason = standSearchLimit ? "stand_search_limit" : "no_path";
                BotLog.warn("mine 失败: target={} reason={}(所有候选站位{} )",
                        target.toShortString(), failureReason,
                        standSearchLimit ? "未完成搜索" : "不可达");
                return Status.FAILED;
            }
            standGoal = choice.stand();
            if (!bot.blockPosition().equals(standGoal)) {
                executor = new PathExecutor(bot, choice.path());
            }
            BotLog.info("曲面站位已选: target={} stand={} 路径 {} 段 sight={} (先直通后清障回退)",
                    target.toShortString(), standGoal.toShortString(), choice.path().size(), choice.lineOfSight());
        }

        // 2) 沿路径走向站位
        if (executor != null) {
            PathExecutor.Status pathStatus = executor.tick();
            if (pathStatus == PathExecutor.Status.FAILED) {
                if (executor.wasObstructed() && pathRetries < 2) {
                    // 路径中方块动态变化 → 从当前位置重新规划(限 2 次)
                    pathRetries++;
                    BotLog.warn("路径受阻,重新规划({}/2): target={}", pathRetries, target.toShortString());
                    SurfacePathfinder.Result surface = SurfacePathfinder.find(level,
                            bot.blockPosition(), standGoal);
                    if (!surface.reachable()) {
                        failureReason = "no_path";
                        BotLog.warn("mine 失败: target={} reason={}", target.toShortString(), failureReason);
                        return Status.FAILED;
                    }
                    executor = new PathExecutor(bot, surface.path());
                    return Status.MOVING;
                }
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
            abortMining(level);
            // 当前站位视线受阻时先尝试剩余候选；这是清障避障的第一层，避免立刻挖穿
            // 安全区/基岩/黑曜石。所有站位均失败后，MineTask 才评估是否允许清障。
            if (standCandidates != null && !standCandidates.isEmpty()) {
                BotLog.info("当前站位视线受阻: stand={}，改试其他候选({} 个剩余)",
                        standGoal.toShortString(), standCandidates.size());
                standGoal = null;
                executor = null;
                return Status.MOVING;
            }
            failureReason = "line_of_sight_blocked";
            BotLog.warn("mine 失败(所有站位视线均受阻): target={} reason={}",
                    target.toShortString(), failureReason);
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

    /** 在所有曲面可达候选中，优先选择视线直通且路径最短的站位。 */
    private StandChoice chooseReachableStand(ServerLevel level) {
        StandChoice direct = null;
        StandChoice blocked = null;
        for (BlockPos candidate : standCandidates) {
            SurfacePathfinder.Result surface = SurfacePathfinder.find(level, bot.blockPosition(), candidate);
            if (!surface.reachable()) {
                standSearchLimit |= surface.inconclusive();
                BotLog.warn("候选站位不可达: {} status={} → 忽略", candidate.toShortString(), surface.status());
                continue;
            }
            StandChoice choice = new StandChoice(candidate, surface.path(),
                    lineOfSightClearFrom(level, candidate));
            if (choice.lineOfSight()) {
                if (direct == null || choice.path().size() < direct.path().size()) {
                    direct = choice;
                }
            } else if (blocked == null || choice.path().size() < blocked.path().size()) {
                blocked = choice;
            }
        }
        StandChoice selected = direct != null ? direct : blocked;
        if (selected != null) {
            // 保留其他候选：世界在行走期间变化导致视线失效时，仍可重新挑选。
            standCandidates.remove(selected.stand());
        }
        return selected;
    }

    private record StandChoice(BlockPos stand, List<BlockPos> path, boolean lineOfSight) {
    }

    /**
     * 选站位候选列表(按优先级排序, BotMiner 逐个尝试可达性):
     * <ul>
     *   <li>分组:目标下方(dy&lt;0, <b>搜索到下方 4 层</b>——悬空目标的正下方地面
     *       可能隔 3-4 格, 搜浅了 below 组会为空, 候选全落在目标上方) /
     *       同平面(dy=0) / 目标上方(dy&gt;0, 到上方 2 层);</li>
     *   <li>目标在 bot 上方 → 下方组优先(站下方抬头挖矿洞顶部矿石),否则同平面优先;</li>
     *   <li>组内排序:视线无遮挡优先 → 距目标中心 3D 距离近优先;</li>
     *   <li>距离过滤:候选格眼睛到目标中心 ≤ 挖掘距离(留 0.3 余量)。</li>
     * </ul>
     */
    private List<BlockPos> pickStandCandidates(ServerLevel level) {
        List<BlockPos> below = new ArrayList<>();
        List<BlockPos> same = new ArrayList<>();
        List<BlockPos> above = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -4; dy <= 2; dy++) { // 下方加深:悬空目标正下方地面可能隔 3-4 格
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos p = target.offset(dx, dy, dz);
                    if (p.equals(target) || p.equals(target.above())) {
                        continue; // 不站目标方块本身/其正上方(避免与挖脚下方块冲突)
                    }
                    BlockState foot = level.getBlockState(p);
                    BlockState head = level.getBlockState(p.above());
                    BlockState belowState = level.getBlockState(p.below());
                    // 可站判定: 脚位格无碰撞(空气/草丛/花), 头位格无碰撞, 下方有支撑
                    if (foot.getCollisionShape(level, p).isEmpty() && foot.getFluidState().isEmpty()
                            && head.getCollisionShape(level, p.above()).isEmpty()
                            && !belowState.getCollisionShape(level, p.below()).isEmpty()
                            && belowState.getFluidState().isEmpty()) {
                        // 挖掘距离过滤(候选格眼睛到目标中心),防站过去又 out_of_reach
                        Vec3 eyeAt = new Vec3(p.getX() + 0.5D, p.getY() + 1.62D, p.getZ() + 0.5D);
                        if (eyeAt.distanceTo(target.getCenter()) > MAX_REACH - 0.3D) {
                            continue;
                        }
                        (dy < 0 ? below : dy > 0 ? above : same).add(p);
                    }
                }
            }
        }
        sortCandidates(level, same);
        sortCandidates(level, below);
        sortCandidates(level, above);
        List<BlockPos> result = new ArrayList<>();
        if (target.getY() > bot.blockPosition().getY() + 1) {
            result.addAll(below); // 目标在上方: 站下方抬头挖优先
            result.addAll(same);
        } else {
            result.addAll(same);
            result.addAll(below);
        }
        result.addAll(above);
        // 截断 A* 候选前，先把所有直通站位提升到最前；否则组优先级可能把同平面
        // 直通站位截掉，迫使任务走向一个需要清障的下方/上方候选。
        result.sort(java.util.Comparator
                .comparing((BlockPos p) -> !lineOfSightClearFrom(level, p))
                .thenComparingDouble(p -> {
                    Vec3 eyeAt = new Vec3(p.getX() + 0.5D, p.getY() + 1.62D, p.getZ() + 0.5D);
                    return eyeAt.distanceToSqr(target.getCenter());
                }));
        BotLog.info("站位候选: 下方{} 同平面{} 上方{} → 全局直通优先后最多试8个",
                below.size(), same.size(), above.size());
        return result.size() > 8 ? result.subList(0, 8) : result; // 最多试 8 个,防 A* 风暴
    }

    /** 组内排序:视线无遮挡优先,其次距目标中心 3D 距离近优先。 */
    private void sortCandidates(ServerLevel level, List<BlockPos> list) {
        list.sort(java.util.Comparator
                .comparing((BlockPos p) -> !lineOfSightClearFrom(level, p))
                .thenComparingDouble(p -> {
                    Vec3 eyeAt = new Vec3(p.getX() + 0.5D, p.getY() + 1.62D, p.getZ() + 0.5D);
                    return eyeAt.distanceToSqr(target.getCenter());
                }));
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
