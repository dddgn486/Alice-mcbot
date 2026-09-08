package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.AscendExecutionFactory;
import com.dddgn.alice.pathing.core.CompletionTolerance;
import com.dddgn.alice.pathing.core.DescendExecutionFactory;
import com.dddgn.alice.pathing.core.DiagonalExecutionFactory;
import com.dddgn.alice.pathing.core.IntrinsicReversibility;
import com.dddgn.alice.pathing.core.LiveExecutionContext;
import com.dddgn.alice.pathing.core.MovementCapabilities;
import com.dddgn.alice.pathing.core.MovementExecution;
import com.dddgn.alice.pathing.core.MovementExecutionFactory;
import com.dddgn.alice.pathing.core.MovementSpec;
import com.dddgn.alice.pathing.core.MovementType;
import com.dddgn.alice.pathing.core.PlanningDependency;
import com.dddgn.alice.pathing.core.RecoverabilityLevel;
import com.dddgn.alice.pathing.core.TraverseExecutionFactory;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;

/**
 * R2-C 多段链接诊断夹具（P0 闭环验收用）。
 *
 * <p>按计划坐标依次执行多个 Movement，每段使用新的 {@link MovementExecution}，
 * 段与段之间不重建计划（这正是要验证的：上一段落点是否满足下一段前置）。
 * 用途是验证 D-026 的合法位置集与统一完成契约能否让多段链接不再 STALE_START 断链。
 */
public final class ChainDiagnosticTask implements Task {
    private static final int MAX_TICKS_PER_SEGMENT = 100;

    private final BotPlayer bot;
    private final List<BlockPos> plannedFoot;
    private final String sessionId = "r2c-chain-" + UUID.randomUUID();
    private int segmentIndex;
    private MovementExecution execution;
    private int segmentTicks;
    private boolean initialized;
    private String failure = "";

    public ChainDiagnosticTask(BotPlayer bot, List<BlockPos> plannedFoot) {
        this.bot = bot;
        this.plannedFoot = plannedFoot.stream().map(BlockPos::immutable).toList();
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(plannedFoot.get(plannedFoot.size() - 1));
    }

    @Override
    public Status tick() {
        if (!initialized && !initialize()) {
            return Status.FAILED;
        }
        if (execution == null && !startSegment()) {
            return Status.FAILED;
        }
        if (++segmentTicks > MAX_TICKS_PER_SEGMENT) {
            execution.cancel();
            failure = "CHAIN_SEGMENT_TIMEOUT";
            logSegment("failed", failure);
            return Status.FAILED;
        }
        execution.tick();
        if (execution.phase() == MovementExecution.Phase.SUCCEEDED) {
            logSegment("completed", "-");
            segmentIndex++;
            execution = null;
            segmentTicks = 0;
            if (segmentIndex >= plannedFoot.size() - 1) {
                BotLog.info("[R2-C Chain] all_completed session={} segments={} finalFoot={}",
                        sessionId, plannedFoot.size() - 1, bot.blockPosition().toShortString());
                return Status.DONE;
            }
            return Status.RUNNING;
        }
        if (execution.phase() == MovementExecution.Phase.FAILED
                || execution.phase() == MovementExecution.Phase.CANCELLED) {
            failure = execution.failureCode() == null ? "CHAIN_MOVEMENT_FAILED" : execution.failureCode();
            logSegment("failed", failure);
            return Status.FAILED;
        }
        return Status.RUNNING;
    }

    @Override
    public String failureReason() {
        return failure;
    }

    private boolean initialize() {
        initialized = true;
        if (plannedFoot.size() < 2) {
            failure = "CHAIN_TOO_SHORT";
            return false;
        }
        BotLog.info("[R2-C Chain] started session={} bot={} segments={} from={} to={}",
                sessionId, bot.getName().getString(), plannedFoot.size() - 1,
                plannedFoot.get(0).toShortString(), plannedFoot.get(plannedFoot.size() - 1).toShortString());
        return true;
    }

    /** 用计划坐标创建下一段 Movement；工厂校验失败即整条链失败（这正是断链检测点）。 */
    private boolean startSegment() {
        BlockPos from = plannedFoot.get(segmentIndex);
        BlockPos to = plannedFoot.get(segmentIndex + 1);
        MovementType type = movementTypeFor(from, to);
        if (type == null) {
            failure = "CHAIN_UNSUPPORTED_STEP";
            logSegment("failed", failure);
            return false;
        }
        MovementExecutionFactory factory = factoryFor(type);
        MovementSpec spec = createSpec(type, from, to);
        // D-027：中间段用 COLUMN 容差（对齐 Baritone，消除空中掉头回冲）；
        // 最终段用 EXACT（安全关键站位保持居中）。
        boolean finalSegment = segmentIndex == plannedFoot.size() - 2;
        CompletionTolerance tolerance = finalSegment
                ? CompletionTolerance.EXACT
                : CompletionTolerance.COLUMN;
        LiveExecutionContext context = new LiveExecutionContext(bot, bot.serverLevel(), sessionId,
                0L, 0L, tolerance);
        MovementExecutionFactory.ValidationResult validation = factory.validate(spec, context);
        if (!validation.valid()) {
            failure = validation.failureCode();
            BotLog.warn("[R2-C Chain] segment_rejected session={} index={} from={} to={} reason={} actualFoot={}",
                    sessionId, segmentIndex, from.toShortString(), to.toShortString(), failure,
                    bot.blockPosition().toShortString());
            return false;
        }
        execution = factory.create(spec, context);
        segmentTicks = 0;
        BotLog.info("[R2-C Chain] segment_started session={} index={} type={} from={} to={} actualFoot={}",
                sessionId, segmentIndex, type, from.toShortString(), to.toShortString(),
                bot.blockPosition().toShortString());
        return true;
    }

    private void logSegment(String result, String reason) {
        BlockPos from = plannedFoot.get(segmentIndex);
        BlockPos to = plannedFoot.get(segmentIndex + 1);
        BotLog.info("[R2-C Chain] segment_{} session={} index={} from={} to={} actualFoot={} support={} onGround={} ticks={} reason={}",
                result, sessionId, segmentIndex, from.toShortString(), to.toShortString(),
                bot.blockPosition().toShortString(),
                MovementHelper.isStandingOnSupport(bot.serverLevel(), bot),
                bot.onGround(), segmentTicks, reason);
    }

    private static MovementType movementTypeFor(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        int horizontal = Math.abs(dx) + Math.abs(dz);
        if (dy == 0 && horizontal == 1) return MovementType.TRAVERSE;
        if (dy == 0 && Math.abs(dx) == 1 && Math.abs(dz) == 1) return MovementType.DIAGONAL;
        if (dy == 1 && horizontal == 1) return MovementType.ASCEND;
        if (dy == -1 && horizontal == 1) return MovementType.DESCEND;
        return null;
    }

    private static MovementExecutionFactory factoryFor(MovementType type) {
        return switch (type) {
            case TRAVERSE -> new TraverseExecutionFactory();
            case DIAGONAL -> new DiagonalExecutionFactory();
            case ASCEND -> new AscendExecutionFactory();
            case DESCEND -> new DescendExecutionFactory();
            default -> throw new IllegalArgumentException("unsupported chain movement type: " + type);
        };
    }

    private static MovementSpec createSpec(MovementType type, BlockPos from, BlockPos to) {
        MovementCapabilities capabilities = MovementCapabilities.pureTraversal(
                RecoverabilityLevel.LOCAL_STEP, IntrinsicReversibility.REVERSIBLE);
        PlanningDependency dependency = new PlanningDependency(
                List.of(from, to, to.above(), to.below()),
                List.of(to, to.above()), List.of(to.below()),
                List.of(to, to.below()), List.of(to, to.below()),
                List.of(), 0L, 0L);
        String factoryKey = switch (type) {
            case TRAVERSE -> TraverseExecutionFactory.KEY;
            case DIAGONAL -> DiagonalExecutionFactory.KEY;
            case ASCEND -> AscendExecutionFactory.KEY;
            case DESCEND -> DescendExecutionFactory.KEY;
            default -> throw new IllegalArgumentException("unsupported chain movement type: " + type);
        };
        return new MovementSpec(type, from, to, 1.2D, capabilities,
                List.of(), List.of(),
                List.of("chain_segment", "target_support", "target_body_clear", "target_head_clear"),
                dependency, RecoverabilityLevel.LOCAL_STEP, factoryKey);
    }
}
