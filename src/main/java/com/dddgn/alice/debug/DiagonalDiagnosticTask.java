package com.dddgn.alice.debug;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.movement.DiagonalExecutionFactory;
import com.dddgn.alice.pathing.calc.CompletionTolerance;
import com.dddgn.alice.pathing.calc.IntrinsicReversibility;
import com.dddgn.alice.pathing.movement.LiveExecutionContext;
import com.dddgn.alice.pathing.calc.MovementCapabilities;
import com.dddgn.alice.pathing.movement.MovementExecution;
import com.dddgn.alice.pathing.calc.MovementSpec;
import com.dddgn.alice.pathing.calc.MovementType;
import com.dddgn.alice.pathing.calc.RecoverabilityLevel;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;
import com.dddgn.alice.task.Task;
import com.dddgn.alice.task.TaskTarget;

/** R2-C fixture for exactly one same-level diagonal movement. */
public final class DiagonalDiagnosticTask implements Task {
    private static final int MAX_EXECUTION_TICKS = 100;
    private final BotPlayer bot;
    private final BlockPos fromFoot;
    private final BlockPos toFoot;
    private final String sessionId = "r2c-diagonal-" + UUID.randomUUID();
    private MovementExecution execution;
    private String failure = "";
    private int executionTicks;
    private boolean initialized;

    public DiagonalDiagnosticTask(BotPlayer bot, BlockPos toFoot) {
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
            failure = "DIAGONAL_TIMEOUT";
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
            failure = execution.failureCode() == null ? "DIAGONAL_MOVEMENT_FAILED" : execution.failureCode();
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
            failure = "DIAGONAL_INVALID_SPEC";
            BotLog.warn("[R2-C Diagonal] rejected session={} from={} to={} reason={} detail={}",
                    sessionId, fromFoot.toShortString(), toFoot.toShortString(), failure, exception.getMessage());
            bot.controller().stopMovement();
            return false;
        }
        LiveExecutionContext context = new LiveExecutionContext(bot, bot.serverLevel(), sessionId, 0L,
                CompletionTolerance.EXACT, "diagonal-diagnostic");
        DiagonalExecutionFactory factory = new DiagonalExecutionFactory();
        DiagonalExecutionFactory.ValidationResult validation = factory.validate(spec, context);
        if (!validation.valid()) {
            failure = validation.failureCode();
            BotLog.warn("[R2-C Diagonal] rejected session={} from={} to={} reason={}",
                    sessionId, fromFoot.toShortString(), toFoot.toShortString(), failure);
            bot.controller().stopMovement();
            return false;
        }
        execution = factory.create(spec, context);
        BotLog.info("[R2-C Diagonal] started session={} bot={} from={} to={} support={} onGround={}",
                sessionId, bot.getName().getString(), fromFoot.toShortString(), toFoot.toShortString(),
                MovementHelper.isStandingOnSupport(bot.serverLevel(), bot), bot.onGround());
        return true;
    }

    private MovementSpec createSpec() {
        MovementCapabilities capabilities = MovementCapabilities.pureTraversal(
                RecoverabilityLevel.PATH_REVERSIBLE, IntrinsicReversibility.REVERSIBLE);
        return new MovementSpec(MovementType.DIAGONAL, fromFoot, toFoot, 1.414D, capabilities,
                List.of(), List.of(),
                List.of("same_level_diagonal_step", "target_support", "target_body_clear", "target_head_clear", "side_clear"),
                RecoverabilityLevel.PATH_REVERSIBLE, DiagonalExecutionFactory.KEY);
    }

    private void logTerminal(String result, String reason) {
        BotLog.info("[R2-C Diagonal] {} session={} from={} to={} actualFoot={} support={} onGround={} controllerActive={} ticks={} reason={}",
                result, sessionId, fromFoot.toShortString(), toFoot.toShortString(),
                bot.blockPosition().toShortString(), MovementHelper.isStandingOnSupport(bot.serverLevel(), bot),
                bot.onGround(), bot.controller().hasActiveMovement(), executionTicks, reason);
    }
}
