package com.dddgn.alice.action;

import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.session.PathExecutionResult;
import com.dddgn.alice.task.PathRetryRunner;
import com.dddgn.alice.task.mining.LineOfSightChecker;
import com.dddgn.alice.task.mining.MiningPlan;
import com.dddgn.alice.task.mining.StandingPointSelector;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 挖掘动作原语（D-071，D-067 批次 4 第 1 步）：消费 {@link MiningPlan}，执行
 * **走到站位 → （必要时）在目标下方放支撑块 → 破坏目标**。
 *
 * <p>与 `BotMiner` 的分工：站位选择、视线前提、路径成本、两模式编排都已在规划层完成
 * （`MiningPlanner`），本类只做确定性动作，不再有站位候选/评分逻辑。
 * <ul>
 *   <li>走位：`PathRetryRunner`；到达请求按 mode 选择——`TUNNEL`/`ENTER_TARGET` 用
 *       {@link PathRequest#miningApproach}（允许破坏/放置），其余用 {@link PathRequest#of}（纯通行）；</li>
 *   <li>破坏：统一走 {@link BlockBreakSession}（工具选择 / 进度广播 / ABORT / 超时）；</li>
 *   <li>运行期视线复核：到位后若目标不可见 → `LINE_OF_SIGHT_BLOCKED`（任务层据此重试/换站位）。</li>
 * </ul>
 */
public final class MineBlockRunner {

    public enum Status { MOVING, MINING, DONE, FAILED }

    /** 失败报告：`retryable` 表示任务层应重新规划（换站位/降级模式）。 */
    public record FailureReport(String reason, String phase, boolean retryable) {
        public FailureReport {
            reason = reason == null ? "unknown" : reason;
            phase = phase == null ? "unknown" : phase;
        }
    }

    private final BotPlayer bot;
    private final ServerLevel level;
    private final MiningPlan plan;
    private final BlockPos target;
    /** 只走到站位、不破坏目标（破坏由任务层接管；D-077 连锁兼容）。 */
    private final boolean walkOnly;

    private PathRetryRunner runner;
    private BlockBreakSession breakSession;
    private boolean supportPlaced;
    private Status status = Status.MOVING;
    private String failureReason = "";
    private String failurePhase = "unknown";
    private boolean retryable;
    private BlockPos mineStartPos;
    private double mineStartEyeDist;
    /** 本次挖掘的授权（D-082）：目标破坏用自身理由，放支撑块派生 SUPPORT_PLACEMENT。 */
    private final WriteGrant grant;

    public MineBlockRunner(BotPlayer bot, MiningPlan plan, WriteGrant grant) {
        this(bot, plan, false, grant);
    }

    /**
     * @param walkOnly 只走到站位（不破坏目标），用于任务层接管破坏动作的场景
     *                 （例如连锁挖掘模组兼容，D-077）。到位后返回 {@link Status#DONE}。
     */
    public MineBlockRunner(BotPlayer bot, MiningPlan plan, boolean walkOnly, WriteGrant grant) {
        this.walkOnly = walkOnly;
        this.grant = grant;
        this.bot = bot;
        this.level = bot.serverLevel();
        this.plan = plan;
        this.target = plan.target();
    }

    public Status status() {
        return status;
    }

    public String failureReason() {
        return failureReason;
    }

    public FailureReport failureReport() {
        return new FailureReport(failureReason, failurePhase, retryable);
    }

    public BlockPos mineStartPos() {
        return mineStartPos;
    }

    public double mineStartEyeDist() {
        return mineStartEyeDist;
    }

    public Status tick() {
        if (status == Status.DONE || status == Status.FAILED) {
            return status;
        }
        // 目标已空（被破坏 / 被其它原因清除）→ 完成
        if (level.getBlockState(target).isAir()) {
            status = Status.DONE;
            return status;
        }

        // 1) 走到站位：只要有 runner 就继续推进，直到它报 DONE（会话按 EXACT 容差落定到站位中心）
        if (runner != null) {
            return tickMovement();
        }
        if (!MovementHelper.footCell(bot.serverLevel(), bot).equals(plan.standingFoot())) {
            return tickMovement();
        }

        // 2) 目标下方无支撑 → 先放支撑块
        if (plan.supportPlacementPos() != null && !supportPlaced) {
            return tickSupportPlacement();
        }

        // 3) 只走位模式：到位即完成（破坏由任务层接管）
        if (walkOnly) {
            bot.controller().stopMovement();
            BotLog.info("[MineRunner] at_stand target={} stand={} walkOnly=true",
                    target.toShortString(), plan.standingFoot().toShortString());
            status = Status.DONE;
            return status;
        }

        // 4) 破坏目标
        return tickBreak();
    }

    public void cancel() {
        if (runner != null) {
            runner.cancel();
            runner = null;
        }
        if (breakSession != null) {
            breakSession.abort();
            breakSession = null;
        }
        bot.controller().stopMovement();
        if (status != Status.DONE) {
            status = Status.FAILED;
            failureReason = "MINE_RUNNER_CANCELLED";
            failurePhase = "cancelled";
            retryable = false;
        }
    }

    // ==================== 内部阶段 ====================

    private Status tickMovement() {
        if (runner == null) {
            PathRequest request = plan.mode() == MiningPlan.Mode.TUNNEL
                    || plan.mode() == MiningPlan.Mode.ENTER_TARGET
                    ? PathRequest.miningApproach(bot.getUUID().toString(), MovementHelper.footCell(bot.serverLevel(), bot),
                            plan.standingFoot(), "mine-runner")
                    : PathRequest.of(bot.getUUID().toString(), MovementHelper.footCell(bot.serverLevel(), bot),
                            plan.standingFoot(), "mine-runner");
            runner = new PathRetryRunner(bot, request, PathRetryRunner.DEFAULT_MAX_REPLANS,
                    "mine-" + target.getX() + "_" + target.getY() + "_" + target.getZ());
            BotLog.info("[MineRunner] walk_start target={} stand={} mode={} feet={}",
                    target.toShortString(), plan.standingFoot().toShortString(), plan.mode(),
                    bot.blockPosition().toShortString());
        }
        PathRetryRunner.State state = runner.tick();
        if (state == PathRetryRunner.State.RUNNING) {
            status = Status.MOVING;
            return status;
        }
        if (state == PathRetryRunner.State.FAILED) {
            PathExecutionResult result = runner.result();
            return fail("MOVE_" + (result == null ? "FAILED" : result.status().name()),
                    "movement", true);
        }
        runner = null;
        status = Status.MOVING;
        return status;
    }

    private Status tickSupportPlacement() {
        bot.controller().stopMovement();
        if (BlockInteraction.findPlaceableSlot(bot) < 0) {
            return fail("PLACE_RESOURCE_UNAVAILABLE", "support", false);
        }
        BlockInteraction.PlaceResult result =
                BlockInteraction.placeAt(bot, level, plan.supportPlacementPos(), false,
                    grant.with(WriteReason.SUPPORT_PLACEMENT));
        if (result == BlockInteraction.PlaceResult.BUDGET_EXHAUSTED) {
            return fail("WRITE_BUDGET_EXHAUSTED", "support", false);
        }
        if (result != BlockInteraction.PlaceResult.PLACED) {
            return fail("SUPPORT_PLACE_FAILED", "support", true);
        }
        supportPlaced = true;
        BotLog.info("[MineRunner] support_placed target={} pos={} feet={}",
                target.toShortString(), plan.supportPlacementPos().toShortString(),
                bot.blockPosition().toShortString());
        return status;
    }

    private Status tickBreak() {
        if (breakSession == null) {
            if (!BlockInteraction.breakable(bot, level, target, grant)) {
                return fail("TARGET_NOT_BREAKABLE", "precondition", false);
            }
            LineOfSightChecker.LineOfSightResult los = LineOfSightChecker.checkFromEye(
                    level, bot.getEyePosition(), target);
            if (!los.isClear()) {
                return fail("LINE_OF_SIGHT_BLOCKED", "precondition", true);
            }
            // 触及判定用"眼位 → 可见面采样点"（与规划期同口径）；用方块中心会多算 ~0.87 格
            double eyeDistance = bot.getEyePosition().distanceTo(los.getSuccessfulSample());
            if (eyeDistance > bot.getBlockReach()) {
                BotLog.warn("[MineRunner] out_of_reach target={} feet={} eye={} sample={} dist={} reach={}",
                        target.toShortString(), bot.blockPosition().toShortString(),
                        String.format(java.util.Locale.ROOT, "(%.2f,%.2f,%.2f)", bot.getEyePosition().x,
                                bot.getEyePosition().y, bot.getEyePosition().z),
                        String.format(java.util.Locale.ROOT, "(%.2f,%.2f,%.2f)",
                                los.getSuccessfulSample().x, los.getSuccessfulSample().y,
                                los.getSuccessfulSample().z),
                        String.format(java.util.Locale.ROOT, "%.3f", eyeDistance),
                        String.format(java.util.Locale.ROOT, "%.3f", bot.getBlockReach()));
                return fail("OUT_OF_REACH", "precondition", true);
            }
            breakSession = BlockInteraction.beginBreak(bot, level, target, grant);
            if (breakSession == null) {
                // 执行期写入预算耗尽（D-106）
                return fail("WRITE_BUDGET_EXHAUSTED", "break", false);
            }
            mineStartPos = bot.blockPosition().immutable();
            mineStartEyeDist = eyeDistance;
            BotLog.info("[MineRunner] break_start target={} stand={} eyeDist={} mode={}",
                    target.toShortString(), plan.standingFoot().toShortString(),
                    String.format(java.util.Locale.ROOT, "%.2f", eyeDistance), plan.mode());
        }
        BlockBreakSession.Status result = breakSession.tick();
        if (result == BlockBreakSession.Status.DONE) {
            breakSession = null;
            status = Status.DONE;
            BotLog.info("[MineRunner] done target={} feet={}",
                    target.toShortString(), bot.blockPosition().toShortString());
            return status;
        }
        if (result == BlockBreakSession.Status.FAILED) {
            String code = breakSession.failureCode();
            breakSession = null;
            return fail("BREAK_" + code, "break", true);
        }
        status = Status.MINING;
        return status;
    }

    private Status fail(String reason, String phase, boolean retryable) {
        bot.controller().stopMovement();
        status = Status.FAILED;
        failureReason = reason;
        failurePhase = phase;
        this.retryable = retryable;
        BotLog.warn("[MineRunner] failed target={} reason={} phase={} retryable={} feet={}",
                target.toShortString(), reason, phase, retryable,
                bot.blockPosition().toShortString());
        return status;
    }

    /** 目标当前是否仍是可挖掘的方块（任务层观测用）。 */
    public boolean targetStillPresent() {
        BlockState state = level.getBlockState(target);
        return !state.isAir();
    }

    /** 站位假设眼位（日志/诊断用）。 */
    public static net.minecraft.world.phys.Vec3 eyeAt(BlockPos foot) {
        return StandingPointSelector.eyeAt(foot);
    }
}
