package com.dddgn.alice.action;

import com.dddgn.alice.action.BlockInteraction;
import com.dddgn.alice.bot.RecoveryStage;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.search.PlanningStatus;
import com.dddgn.alice.pathing.core.session.PathExecutionResult;
import com.dddgn.alice.pathing.core.session.PathSessionStatus;
import com.dddgn.alice.task.PathRetryRunner;
import com.dddgn.alice.task.mining.LineOfSightChecker;
import com.dddgn.alice.task.mining.MiningPlan;
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
import java.util.Collections;
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

    public enum PlanInvalidation {
        NONE,
        PLAN_START_MISMATCH,
        PLAN_PATH_STALE,
        PLAN_PATH_INCONCLUSIVE,
        PLAN_STAND_INVALID,
        RUNTIME_VISIBILITY_FAILED,
        RUNTIME_OUT_OF_REACH
    }

    public record FailureReport(String reason, PlanInvalidation planInvalidation) {
        public FailureReport {
            reason = reason == null ? "unknown" : reason;
            planInvalidation = planInvalidation == null ? PlanInvalidation.NONE : planInvalidation;
        }
    }

    private static final double MAX_REACH = 4.5D;
    private static final int MINE_TIMEOUT_TICKS = 200;

    private final ServerPlayer bot;
    private final BlockPos target;
    private final BlockPos preferredStandingPoint;
    private PathPlan plannedPath;
    private BlockPos plannedStartFoot;
    private final String executionMode;
    private boolean preferredStandingPointFallback;
    /** 站位移动统一走新内核任务层重试器（D-064：BotMiner 只保留动作层）。 */
    private PathRetryRunner runner;
    /** D-067 批次 3：模式 B/兜底允许破坏+放置到达（PathRequest.miningApproach）。 */
    private boolean allowWorldModification;
    /** D-067 批次 3：目标下方无支撑时，需要先放置的支撑方块位置（null = 不需要）。 */
    private BlockPos supportPlacementPos;
    private boolean supportPlaced;
    private BlockPos standGoal;
    private List<BlockPos> standCandidates;
    private boolean started;
    private float progress;
    private int elapsed;
    private boolean standSearchLimit;
    private String failureReason = "";
    private PlanInvalidation planInvalidation = PlanInvalidation.NONE;
    private RecoveryStage recoveryStage = RecoveryStage.NONE;
    private final List<RecoveryStage> recoveryEvents = new ArrayList<>();
    private Status lastProbeStatus;
    private Direction face;
    /** 挖掘开始时的 bot 位置与眼睛距离(供自动化验收断言「是否隔空挖」)。 */
    private BlockPos mineStartPos;
    private double mineStartEyeDist;

    public BotMiner(ServerPlayer bot, BlockPos target) {
        this(bot, target, null, "LEGACY");
    }

    /** 过渡构造器：直接消费 MiningPlan；旧候选回退仍由原有兼容逻辑负责。 */
    public BotMiner(ServerPlayer bot, MiningPlan plan) {
        this(bot, plan.target(), plan.standingFoot(), "PLAN");
        this.plannedPath = plan.path();
        this.plannedStartFoot = plan.startFoot();
        this.allowWorldModification = plan.mode() == MiningPlan.Mode.TUNNEL
                || plan.mode() == MiningPlan.Mode.ENTER_TARGET;
        this.supportPlacementPos = plan.supportPlacementPos();
        BotLog.info("[MiningPlan探针] consumed executionMode={} mode={} target={} startFoot={} standingFoot={} pathStatus={} pathSize={} visibility={} executable={} support={}",
                executionMode,
                plan.target().toShortString(), plan.startFoot().toShortString(),
                plan.mode(), plan.standingFoot().toShortString(), plan.path().status(),
                plan.path().movements().size(),
                plan.visibility().isClear(), plan.isExecutable(),
                plan.supportPlacementPos() == null ? "-" : plan.supportPlacementPos().toShortString());
    }

    /**
     * 创建带领域首选站位的挖掘器。首选站位只是本次目标的优先方案，
     * 不可达时仍允许旧候选逻辑回退；临时清障目标不应复用原目标站位。
     */
    public BotMiner(ServerPlayer bot, BlockPos target, BlockPos preferredStandingPoint) {
        this(bot, target, preferredStandingPoint, preferredStandingPoint == null ? "LEGACY" : "PREFERRED_STAND");
    }

    private BotMiner(ServerPlayer bot, BlockPos target, BlockPos preferredStandingPoint, String executionMode) {
        this.bot = bot;
        this.target = target;
        this.preferredStandingPoint = preferredStandingPoint == null ? null : preferredStandingPoint.immutable();
        this.executionMode = executionMode;
    }

    public String failureReason() {
        return failureReason;
    }

    public FailureReport failureReport() {
        return new FailureReport(failureReason, planInvalidation);
    }

    public RecoveryStage recoveryStage() {
        return recoveryStage;
    }

    public List<RecoveryStage> recoveryEvents() {
        return Collections.unmodifiableList(recoveryEvents);
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
        BotLog.info("[BotMiner探针] tick: target={} botPos={} standGoal={} runner={} started={} progress={} elapsed={} failure={}",
                target.toShortString(), bot.blockPosition().toShortString(),
                standGoal == null ? "-" : standGoal.toShortString(),
                runner == null ? "null" : "present", started,
                String.format(java.util.Locale.ROOT, "%.3f", progress), elapsed,
                failureReason.isEmpty() ? "-" : failureReason);
        if (state.isAir()) {
            BotLog.info("[BotMiner探针] target 已为空气，返回 DONE: target={}", target.toShortString());
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

        // 1) 优先使用挖掘领域提供的首选站位；不可达时才回退旧候选逻辑。
        if (standGoal == null && standCandidates == null
                && preferredStandingPoint != null && !preferredStandingPointFallback) {
            PathPlan preferredPath = plannedPath != null
                    ? plannedPath
                    : planPath(preferredStandingPoint);
            boolean preferredLos = lineOfSightClearFrom(level, preferredStandingPoint);
            if (plannedPath != null && !bot.blockPosition().equals(plannedStartFoot)) {
                planInvalidation = PlanInvalidation.PLAN_START_MISMATCH;
                BotLog.info("[MiningPlan探针] currentFoot={} differs from planStartFoot={} ; planPathMayBeStale=true invalidation={}",
                        bot.blockPosition().toShortString(), plannedStartFoot.toShortString(), planInvalidation);
            }
            BotLog.info("[首选站位探针] target={} preferred={} pathStatus={} reachable={} inconclusive={} pathSize={} pathCost={} los={}",
                    target.toShortString(), preferredStandingPoint.toShortString(), preferredPath.status(),
                    preferredPath.reached(),
                    preferredPath.status() == PlanningStatus.SEARCH_LIMIT, preferredPath.movements().size(),
                    String.format(java.util.Locale.ROOT, "%.3f", preferredPath.totalCost()), preferredLos);
            if (preferredPath.reached()) {
                standGoal = preferredStandingPoint;
                if (!bot.blockPosition().equals(standGoal)) {
                    startRunner(standGoal);
                }
                BotLog.info("[站位选择探针] target={} selected={} selectionType=PREFERRED executionMode={} pathSize={} los={}",
                        target.toShortString(), standGoal.toShortString(), executionMode,
                        preferredPath.movements().size(), preferredLos);
            } else {
                if (plannedPath != null) {
                    planInvalidation = preferredPath.status() == PlanningStatus.SEARCH_LIMIT
                            ? PlanInvalidation.PLAN_PATH_INCONCLUSIVE
                            : PlanInvalidation.PLAN_PATH_STALE;
                }
                BotLog.warn("[首选站位回退] target={} preferred={} reason={} executionMode={} invalidation={} -> LEGACY_FALLBACK",
                        target.toShortString(), preferredStandingPoint.toShortString(), preferredPath.status(),
                        executionMode, planInvalidation);
                preferredStandingPointFallback = true;
            }
        }

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
                startRunner(standGoal);
            }
            BotLog.info("曲面站位已选: target={} stand={} 路径 {} 段 sight={} (先直通后清障回退)",
                    target.toShortString(), standGoal.toShortString(), choice.pathSize(), choice.lineOfSight());
        }

        // 2) 沿路径走向站位（新内核 PathRetryRunner，D-064）
        if (runner != null) {
            PathRetryRunner.State runnerState = runner.tick();
            BotLog.info("[BotMiner探针] runner: target={} state={} botPos={} standGoal={} replans={}",
                    target.toShortString(), runnerState, bot.blockPosition().toShortString(),
                    standGoal == null ? "-" : standGoal.toShortString(), runner.replans());
            if (runnerState == PathRetryRunner.State.RUNNING) {
                return Status.MOVING;
            }
            if (runnerState == PathRetryRunner.State.FAILED) {
                PathExecutionResult result = runner.result();
                failureReason = mapPathFailure(result);
                if (plannedPath != null) {
                    planInvalidation = result != null && result.status() == PathSessionStatus.TIMEOUT
                            ? PlanInvalidation.PLAN_PATH_INCONCLUSIVE
                            : PlanInvalidation.PLAN_PATH_STALE;
                }
                BotLog.warn("mine 失败: target={} reason={} status={} code={} planInvalidation={}",
                        target.toShortString(), failureReason,
                        result == null ? "-" : result.status(),
                        result == null ? "-" : result.failureCode(), planInvalidation);
                runner = null;
                return Status.FAILED;
            }
            runner = null;
        }

        // 2.5) 目标下方无支撑 → 先在目标下方放支撑块（D-067 批次 3；collectDrops=false 时不会走到这里）
        if (supportPlacementPos != null && !supportPlaced) {
            if (bot instanceof com.dddgn.alice.bot.BotPlayer botPlayer) {
                botPlayer.controller().stopMovement();
            }
            if (BlockInteraction.findPlaceableSlot(bot) < 0) {
                failureReason = "PLACE_RESOURCE_UNAVAILABLE";
                BotLog.warn("[BotMiner] support_place_no_block target={} support={}",
                        target.toShortString(), supportPlacementPos.toShortString());
                return Status.FAILED;
            }
            BlockInteraction.PlaceResult placeResult =
                    BlockInteraction.placeAt(bot, level, supportPlacementPos, false);
            if (placeResult != BlockInteraction.PlaceResult.PLACED) {
                failureReason = "SUPPORT_PLACE_FAILED";
                BotLog.warn("[BotMiner] support_place_failed target={} support={} feet={}",
                        target.toShortString(), supportPlacementPos.toShortString(),
                        bot.blockPosition().toShortString());
                return Status.FAILED;
            }
            supportPlaced = true;
            BotLog.info("[BotMiner] support_placed pos={} feet={}",
                    supportPlacementPos.toShortString(), bot.blockPosition().toShortString());
            return Status.MINING;
        }

        // 3) 视线无遮挡检查(M0 验收核心:根治隔空挖)
        boolean lineOfSightClear = lineOfSightClear();
        double eyeDistance = bot.getEyePosition().distanceTo(target.getCenter());
        BotLog.info("[BotMiner探针] 挖掘前置: target={} botPos={} standGoal={} los={} eyeDistance={} maxReach={} started={}",
                target.toShortString(), bot.blockPosition().toShortString(),
                standGoal == null ? "-" : standGoal.toShortString(), lineOfSightClear,
                String.format(java.util.Locale.ROOT, "%.3f", eyeDistance), MAX_REACH, started);
        if (!lineOfSightClear) {
            abortMining(level);
            // 🔧 修复：到达站位后不再尝试其他候选，保持固定站位
            // 让 Task 层负责清障，清完后再次尝试（Bot 保持在原站位）
            failureReason = "line_of_sight_blocked";
            if (plannedPath != null) {
                planInvalidation = PlanInvalidation.RUNTIME_VISIBILITY_FAILED;
            }
            BotLog.warn("mine 失败(视线受阻): target={} stand={} planInvalidation={} (保持站位，等待清障)",
                    target.toShortString(), standGoal.toShortString(), planInvalidation);
            return Status.FAILED;
        }

        // 4) 距离检查(对齐原版 4.5)
        if (eyeDistance > MAX_REACH) {
            failureReason = "out_of_reach";
            if (plannedPath != null) {
                planInvalidation = PlanInvalidation.RUNTIME_OUT_OF_REACH;
            }
            BotLog.warn("mine 失败: target={} reason={} planInvalidation={}",
                    target.toShortString(), failureReason, planInvalidation);
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
            BlockState oldState = level.getBlockState(target);
            bot.gameMode.destroyBlock(target);
            level.destroyBlockProgress(bot.getId(), target, -1);

            // 手动广播方块更新到客户端
            BlockState newState = level.getBlockState(target);
            level.sendBlockUpdated(target, oldState, newState, 3);
            BotLog.info("挖掘完成: target={}", target.toShortString());

            started = false;
            progress = 0.0F;
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
            PathPlan plan = planPath(candidate);
            if (!plan.reached()) {
                standSearchLimit |= plan.status() == PlanningStatus.SEARCH_LIMIT;
                BotLog.warn("候选站位不可达: {} status={} → 忽略", candidate.toShortString(), plan.status());
                continue;
            }
            boolean lineOfSight = lineOfSightClearFrom(level, candidate);
            BotLog.info("[站位候选探针] target={} candidate={} pathStatus={} pathSize={} pathCost={} nodes={} los={}",
                    target.toShortString(), candidate.toShortString(), plan.status(), plan.movements().size(),
                    String.format(java.util.Locale.ROOT, "%.3f", plan.totalCost()), plan.nodesExpanded(), lineOfSight);
            StandChoice choice = new StandChoice(candidate, plan.movements().size(), lineOfSight);
            if (choice.lineOfSight()) {
                if (direct == null || choice.pathSize() < direct.pathSize()) {
                    direct = choice;
                }
            } else if (blocked == null || choice.pathSize() < blocked.pathSize()) {
                blocked = choice;
            }
        }
        StandChoice selected = direct != null ? direct : blocked;
        if (selected != null) {
            // 保留其他候选：世界在行走期间变化导致视线失效时，仍可重新挑选。
            standCandidates.remove(selected.stand());
            BotLog.info("[站位选择探针] target={} selected={} selectionType={} executionMode={} pathSize={} remainingCandidates={}",
                    target.toShortString(), selected.stand().toShortString(),
                    selected == direct ? "DIRECT" : "BLOCKED_FALLBACK",
                    preferredStandingPointFallback ? "LEGACY_FALLBACK" : executionMode,
                    selected.pathSize(), standCandidates.size());
        } else {
            BotLog.warn("[站位选择探针] target={} selected=none remainingCandidates={}",
                    target.toShortString(), standCandidates.size());
        }
        return selected;
    }

    private record StandChoice(BlockPos stand, int pathSize, boolean lineOfSight) {
    }

    /** 用新内核规划到目标脚位（HARD_PATH：纯通行，不挖不放）。 */
    private PathPlan planPath(BlockPos goalFoot) {
        PathRequest request = PathRequest.of(bot.getUUID().toString(), bot.blockPosition(), goalFoot);
        return new CorePathPlanner().plan(bot, bot.serverLevel(), request);
    }

    /** 启动站位移动（新内核任务层重试器）。 */
    private void startRunner(BlockPos goalFoot) {
        cancelRunner();
        if (!(bot instanceof com.dddgn.alice.bot.BotPlayer botPlayer)) {
            throw new IllegalStateException("BotMiner 站位移动需要 BotPlayer");
        }
        runner = new PathRetryRunner(botPlayer,
                PathRequest.of(bot.getUUID().toString(), bot.blockPosition(), goalFoot),
                PathRetryRunner.DEFAULT_MAX_REPLANS, "miner-" + target.getX() + "_" + target.getY()
                + "_" + target.getZ());
        BotLog.info("[BotMiner探针] 站位移动启动: target={} stand={} feet={}",
                target.toShortString(), goalFoot.toShortString(), bot.blockPosition().toShortString());
    }

    private void cancelRunner() {
        if (runner != null) {
            runner.cancel();
            runner = null;
        }
        if (bot instanceof com.dddgn.alice.bot.BotPlayer botPlayer) {
            botPlayer.controller().stopMovement();
        }
    }

    /** 新内核终态 → BotMiner 失败码（保留 legacy 语义）。 */
    private static String mapPathFailure(PathExecutionResult result) {
        if (result == null) {
            return "path_failed";
        }
        String code = result.failureCode() == null ? "" : result.failureCode();
        if (code.startsWith("PLAN_UNREACHABLE")) {
            return "no_path";
        }
        if (code.startsWith("PLAN_SEARCH_LIMIT")) {
            return "stand_search_limit";
        }
        if (code.startsWith("PLAN_")) {
            return "path_failed";
        }
        return switch (result.status()) {
            case BLOCKED -> "path_blocked";
            case TIMEOUT -> "path_timeout";
            case STALE -> "path_stale";
            case INVALID_PRECONDITION -> "path_invalid_precondition";
            default -> "path_failed";
        };
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
        LineOfSightChecker.LineOfSightResult result = LineOfSightChecker.checkFromEye(level, eye, target);
        BotLog.info("[LOS探针/候选] stand={} eye={} target={} clear={} blocker={} sample={}",
                standPos.toShortString(), formatVec(eye), target.toShortString(), result.isClear(),
                result.getFirstBlocker() == null ? "-" : result.getFirstBlocker().toShortString(),
                result.getSuccessfulSample() == null ? "-" : formatVec(result.getSuccessfulSample()));
        return result.isClear();
    }

    private boolean atStandPos() {
        double dx = bot.getX() - (standGoal.getX() + 0.5D);
        double dz = bot.getZ() - (standGoal.getZ() + 0.5D);
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return horizontal <= 0.6D
                && Math.abs(bot.getY() - standGoal.getY()) <= 1.1D;
    }

    /** 眼睛 → 目标方块多个内部采样点；任一点首个命中目标即视为可挖。 */
    private boolean lineOfSightClear() {
        Vec3 eye = bot.getEyePosition();
        LineOfSightChecker.LineOfSightResult result = LineOfSightChecker.checkFromEye(bot.level(), eye, target);
        BotLog.info("[LOS探针/运行时] botPos={} eye={} target={} clear={} blocker={} sample={}",
                bot.blockPosition().toShortString(), formatVec(eye), target.toShortString(), result.isClear(),
                result.getFirstBlocker() == null ? "-" : result.getFirstBlocker().toShortString(),
                result.getSuccessfulSample() == null ? "-" : formatVec(result.getSuccessfulSample()));
        return result.isClear();
    }

    /**
     * 保留的诊断辅助：记录目标各面内采样点的首个命中结果。
     * 正式 LOS 判定现在由 LineOfSightChecker 统一负责。
     */
    private void logMultiPointVisibility(net.minecraft.world.level.Level level, Vec3 eye, BlockPos target) {
        double x = target.getX();
        double y = target.getY();
        double z = target.getZ();
        double e = 0.08D;
        List<String> samples = new ArrayList<>();
        samples.add(logVisibilitySample(level, eye, target, "CENTER", target.getCenter()));
        samples.add(logVisibilitySample(level, eye, target, "WEST_FACE", new Vec3(x + e, y + 0.5D, z + 0.5D)));
        samples.add(logVisibilitySample(level, eye, target, "EAST_FACE", new Vec3(x + 1.0D - e, y + 0.5D, z + 0.5D)));
        samples.add(logVisibilitySample(level, eye, target, "DOWN_FACE", new Vec3(x + 0.5D, y + e, z + 0.5D)));
        samples.add(logVisibilitySample(level, eye, target, "UP_FACE", new Vec3(x + 0.5D, y + 1.0D - e, z + 0.5D)));
        samples.add(logVisibilitySample(level, eye, target, "NORTH_FACE", new Vec3(x + 0.5D, y + 0.5D, z + e)));
        samples.add(logVisibilitySample(level, eye, target, "SOUTH_FACE", new Vec3(x + 0.5D, y + 0.5D, z + 1.0D - e)));
        BotLog.info("[LOS多点探针] target={} eye={} epsilon={} samples={}",
                target.toShortString(), formatVec(eye), e, String.join("; ", samples));
    }

    private String logVisibilitySample(net.minecraft.world.level.Level level, Vec3 eye,
                                       BlockPos target, String label, Vec3 sample) {
        BlockHitResult hit = level.clip(new ClipContext(eye, sample,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot));
        boolean targetHit = hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
        return label + "=" + formatVec(sample) + ":" + (targetHit ? "TARGET" : hit.getType()
                + "@" + (hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos().toShortString() : "-"));
    }

    private static String formatVec(Vec3 vec) {
        return String.format(java.util.Locale.ROOT, "(%.3f, %.3f, %.3f)", vec.x, vec.y, vec.z);
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
        cancelRunner();
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
