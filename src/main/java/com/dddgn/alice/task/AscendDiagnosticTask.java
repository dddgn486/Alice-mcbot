package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.AscendExecutionFactory;
import com.dddgn.alice.pathing.core.CompletionTolerance;
import com.dddgn.alice.pathing.core.IntrinsicReversibility;
import com.dddgn.alice.pathing.core.LiveExecutionContext;
import com.dddgn.alice.pathing.core.MovementCapabilities;
import com.dddgn.alice.pathing.core.MovementExecution;
import com.dddgn.alice.pathing.core.MovementSpec;
import com.dddgn.alice.pathing.core.MovementType;
import com.dddgn.alice.pathing.core.PlanningDependency;
import com.dddgn.alice.pathing.core.RecoverabilityLevel;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;

/** R2-C fixture for exactly one ascend movement (+1 level). */
public final class AscendDiagnosticTask implements Task {
    private static final int MAX_EXECUTION_TICKS = 100;
    private final BotPlayer bot;
    private final BlockPos fromFoot;
    private final BlockPos toFoot;
    private final String sessionId = "r2c-ascend-" + UUID.randomUUID();
    private MovementExecution execution;
    private String failure = "";
    private int executionTicks;
    private boolean initialized;

    public AscendDiagnosticTask(BotPlayer bot, BlockPos toFoot) {
        this.bot = bot;
        this.fromFoot = MovementHelper.footCell(bot.serverLevel(), bot).immutable();
        this.toFoot = toFoot.immutable();
    }

    @Override
    public TaskTarget target() { return TaskTarget.block(toFoot); }

    @Override
    public Status tick() {
        if (!initialized && !initialize()) return Status.FAILED;
        if (++executionTicks > MAX_EXECUTION_TICKS) {
            execution.cancel();
            failure = "ASCEND_TIMEOUT";
            logTerminal("failed", failure);
            return Status.FAILED;
        }
        execution.tick();
        if (execution.phase() == MovementExecution.Phase.SUCCEEDED) {
            logTerminal("completed", "-");
            return Status.DONE;
        }
        if (execution.phase() == MovementExecution.Phase.FAILED
                || execution.phase() == MovementExecution.Phase.CANCELLED) {
            failure = execution.failureCode() == null ? "ASCEND_MOVEMENT_FAILED" : execution.failureCode();
            logTerminal("failed", failure);
            return Status.FAILED;
        }
        return Status.RUNNING;
    }

    @Override
    public String failureReason() { return failure; }

    private boolean initialize() {
        initialized = true;
        MovementSpec spec;
        try {
            spec = createSpec();
        } catch (IllegalArgumentException exception) {
            failure = "ASCEND_INVALID_SPEC";
            BotLog.warn("[R2-C Ascend] rejected session={} from={} to={} reason={} detail={}",
                    sessionId, fromFoot.toShortString(), toFoot.toShortString(), failure, exception.getMessage());
            bot.controller().stopMovement();
            return false;
        }
        LiveExecutionContext context = new LiveExecutionContext(bot, bot.serverLevel(), sessionId, 0L, 0L,
                CompletionTolerance.EXACT, "ascend-diagnostic");
        AscendExecutionFactory factory = new AscendExecutionFactory();
        AscendExecutionFactory.ValidationResult validation = factory.validate(spec, context);
        if (!validation.valid()) {
            failure = validation.failureCode();
            BotLog.warn("[R2-C Ascend] rejected session={} from={} to={} reason={}",
                    sessionId, fromFoot.toShortString(), toFoot.toShortString(), failure);
            bot.controller().stopMovement();
            return false;
        }
        execution = factory.create(spec, context);
        BotLog.info("[R2-C Ascend] started session={} bot={} from={} to={} support={} onGround={}",
                sessionId, bot.getName().getString(), fromFoot.toShortString(), toFoot.toShortString(),
                MovementHelper.isStandingOnSupport(bot.serverLevel(), bot), bot.onGround());
        return true;
    }

    private MovementSpec createSpec() {
        MovementCapabilities capabilities = MovementCapabilities.pureTraversal(
                RecoverabilityLevel.PATH_REVERSIBLE, IntrinsicReversibility.REVERSIBLE);
        PlanningDependency dependency = new PlanningDependency(
                List.of(fromFoot, toFoot, toFoot.above(), toFoot.below(), fromFoot.above(2)),
                List.of(toFoot, toFoot.above()), List.of(toFoot.below()),
                List.of(toFoot, toFoot.below()), List.of(toFoot, toFoot.below()),
                List.of(), 0L, 0L);
        return new MovementSpec(MovementType.ASCEND, fromFoot, toFoot, 1.5D, capabilities,
                List.of(), List.of(),
                List.of("ascend_one_level", "target_support", "target_body_clear", "target_head_clear", "start_headroom"),
                dependency, RecoverabilityLevel.PATH_REVERSIBLE, AscendExecutionFactory.KEY);
    }

    private void logTerminal(String result, String reason) {
        BotLog.info("[R2-C Ascend] {} session={} from={} to={} actualFoot={} support={} onGround={} controllerActive={} ticks={} reason={}",
                result, sessionId, fromFoot.toShortString(), toFoot.toShortString(),
                bot.blockPosition().toShortString(), MovementHelper.isStandingOnSupport(bot.serverLevel(), bot),
                bot.onGround(), bot.controller().hasActiveMovement(), executionTicks, reason);
    }
}
