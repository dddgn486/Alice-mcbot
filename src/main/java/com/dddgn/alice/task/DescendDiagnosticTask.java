package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.DescendExecutionFactory;
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

/** R2-C fixture for exactly one descend movement (-1 level). */
public final class DescendDiagnosticTask implements Task {
    private static final int MAX_EXECUTION_TICKS = 100;
    private final BotPlayer bot;
    private final BlockPos fromFoot;
    private final BlockPos toFoot;
    private final String sessionId = "r2c-descend-" + UUID.randomUUID();
    private MovementExecution execution;
    private String failure = "";
    private int executionTicks;
    private boolean initialized;

    public DescendDiagnosticTask(BotPlayer bot, BlockPos toFoot) {
        this.bot = bot;
        this.fromFoot = bot.blockPosition().immutable();
        this.toFoot = toFoot.immutable();
    }

    @Override
    public TaskTarget target() { return TaskTarget.block(toFoot); }

    @Override
    public Status tick() {
        if (!initialized && !initialize()) return Status.FAILED;
        if (++executionTicks > MAX_EXECUTION_TICKS) {
            execution.cancel();
            failure = "DESCEND_TIMEOUT";
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
            failure = execution.failureCode() == null ? "DESCEND_MOVEMENT_FAILED" : execution.failureCode();
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
            failure = "DESCEND_INVALID_SPEC";
            BotLog.warn("[R2-C Descend] rejected session={} from={} to={} reason={} detail={}",
                    sessionId, fromFoot.toShortString(), toFoot.toShortString(), failure, exception.getMessage());
            bot.controller().stopMovement();
            return false;
        }
        LiveExecutionContext context = new LiveExecutionContext(bot, bot.serverLevel(), sessionId, 0L, 0L,
                CompletionTolerance.EXACT, "descend-diagnostic");
        DescendExecutionFactory factory = new DescendExecutionFactory();
        DescendExecutionFactory.ValidationResult validation = factory.validate(spec, context);
        if (!validation.valid()) {
            failure = validation.failureCode();
            BotLog.warn("[R2-C Descend] rejected session={} from={} to={} reason={}",
                    sessionId, fromFoot.toShortString(), toFoot.toShortString(), failure);
            bot.controller().stopMovement();
            return false;
        }
        execution = factory.create(spec, context);
        BotLog.info("[R2-C Descend] started session={} bot={} from={} to={} support={} onGround={}",
                sessionId, bot.getName().getString(), fromFoot.toShortString(), toFoot.toShortString(),
                MovementHelper.isStandingOnSupport(bot.serverLevel(), bot), bot.onGround());
        return true;
    }

    private MovementSpec createSpec() {
        MovementCapabilities capabilities = MovementCapabilities.pureTraversal(
                RecoverabilityLevel.LOCAL_STEP, IntrinsicReversibility.REVERSIBLE);
        PlanningDependency dependency = new PlanningDependency(
                List.of(fromFoot, toFoot, toFoot.above(), toFoot.below()),
                List.of(toFoot, toFoot.above()), List.of(toFoot.below()),
                List.of(toFoot, toFoot.below()), List.of(toFoot, toFoot.below()),
                List.of(), 0L, 0L);
        return new MovementSpec(MovementType.DESCEND, fromFoot, toFoot, 1.2D, capabilities,
                List.of(), List.of(),
                List.of("descend_one_level", "target_support", "target_body_clear", "target_head_clear",
                        "landing_column_hazard_clear", "landing_column_safe_step"),
                dependency, RecoverabilityLevel.LOCAL_STEP, DescendExecutionFactory.KEY);
    }

    private void logTerminal(String result, String reason) {
        BotLog.info("[R2-C Descend] {} session={} from={} to={} actualFoot={} support={} onGround={} controllerActive={} ticks={} reason={}",
                result, sessionId, fromFoot.toShortString(), toFoot.toShortString(),
                bot.blockPosition().toShortString(), MovementHelper.isStandingOnSupport(bot.serverLevel(), bot),
                bot.onGround(), bot.controller().hasActiveMovement(), executionTicks, reason);
    }
}
