package com.dddgn.alice.pathing.core.search;

import com.dddgn.alice.pathing.core.AscendExecutionFactory;
import com.dddgn.alice.pathing.core.BreakAndTraverseExecutionFactory;
import com.dddgn.alice.pathing.core.PlaceStepAndTraverseExecutionFactory;
import com.dddgn.alice.pathing.core.DescendExecutionFactory;
import com.dddgn.alice.pathing.core.DiagonalExecutionFactory;
import com.dddgn.alice.pathing.core.IntrinsicReversibility;
import com.dddgn.alice.pathing.core.MovementCapabilities;
import com.dddgn.alice.pathing.core.MovementExecutionFactory;
import com.dddgn.alice.pathing.core.MovementSpec;
import com.dddgn.alice.pathing.core.MovementType;
import com.dddgn.alice.pathing.core.PlanningDependency;
import com.dddgn.alice.pathing.core.RecoverabilityAssessment;
import com.dddgn.alice.pathing.core.RecoverabilityLevel;
import com.dddgn.alice.pathing.core.TraverseExecutionFactory;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * {@link PlannedMovement}（规划输出）→ {@code MovementSpec}（执行契约）的唯一转换点。
 *
 * <p>规划与执行之间只允许通过本类转换，避免各调用方各自拼装能力/依赖字段导致分叉。
 */
public final class PlannedMovementSpecs {
    private PlannedMovementSpecs() {
    }

    public static MovementSpec toSpec(PlannedMovement movement, List<String> planningFacts) {
        // required 来自**策略表**（RecoverabilityPolicy），不再散落写死；FALL 要求 PATH_REVERSIBLE
        // ⇒ **没带 fall_return_verified 事实**的 FALL 边会在 MovementSpec 构造时直接抛异常。
        RecoverabilityLevel required =
                com.dddgn.alice.pathing.core.RecoverabilityPolicy.requiredFor(movement.movementType());
        MovementCapabilities capabilities = switch (movement.movementType()) {
            case PLACE_STEP_AND_TRAVERSE, PILLAR ->
                    MovementCapabilities.temporarySupport(required);
            // FALL 本身不改世界、不耗资源；回程保证由 provider 的 PILLAR 返回守卫（逐边事实）给出
            case FALL -> MovementCapabilities.pureTraversal(required,
                    IntrinsicReversibility.CONDITIONALLY_REVERSIBLE);
            case BREAK_AND_TRAVERSE, BREAK_AND_ENTER, DOWNWARD ->
                    MovementCapabilities.pathAccess(required);
            default -> MovementCapabilities.pureTraversal(required, IntrinsicReversibility.REVERSIBLE);
        };
        BlockPos to = movement.toFoot();
        // 基-1（P0-B）：evaluatedRecoverability 必须来自**真实评估**，不是常量。
        // 规则表在 RecoverabilityEvaluator（唯一裁决点）；这里顺带核对 provider 边上的字段，
        // 两边不一致说明有 provider 漏改 ⇒ 告警而不是静默沿用。
        RecoverabilityAssessment assessment = com.dddgn.alice.pathing.core.RecoverabilityEvaluator
                .evaluate(movement.movementType(), movement.recoverabilityFacts());
        if (movement.recoverability() != assessment.level()) {
            com.dddgn.alice.log.BotLog.warn("[Recover] provider/评估器不一致 type={} provider={} assessed={}（以评估器为准）",
                    movement.movementType(), movement.recoverability(), assessment.level());
        }
        com.dddgn.alice.pathing.core.RecoverabilityReport.record(movement.movementType(), assessment);
        PlanningDependency dependency = new PlanningDependency(
                List.of(movement.fromFoot(), to, to.above(), to.below()),
                List.of(to, to.above()), List.of(to.below()),
                List.of(to, to.below()), List.of(to, to.below()),
                List.of(), 0L);
        return new MovementSpec(movement.movementType(), movement.fromFoot(), to,
                movement.cost(), capabilities, List.of(), List.of(), planningFacts,
                dependency, assessment.level(), factoryKey(movement.movementType()));
    }

    public static String factoryKey(MovementType type) {
        return switch (type) {
            case TRAVERSE -> TraverseExecutionFactory.KEY;
            case DIAGONAL -> DiagonalExecutionFactory.KEY;
            case ASCEND -> AscendExecutionFactory.KEY;
            case DESCEND -> DescendExecutionFactory.KEY;
            case DOWNWARD -> com.dddgn.alice.pathing.core.DownwardExecutionFactory.KEY;
            case PILLAR -> com.dddgn.alice.pathing.core.PillarExecutionFactory.KEY;
            case FALL -> com.dddgn.alice.pathing.core.FallExecutionFactory.KEY;
            case BREAK_AND_TRAVERSE -> BreakAndTraverseExecutionFactory.KEY;
            case BREAK_AND_ENTER -> com.dddgn.alice.pathing.core.BreakAndEnterExecutionFactory.KEY;
            case PLACE_STEP_AND_TRAVERSE -> PlaceStepAndTraverseExecutionFactory.KEY;
            default -> throw new IllegalArgumentException("unsupported movement type: " + type);
        };
    }

    public static MovementExecutionFactory factoryFor(MovementType type) {
        return switch (type) {
            case TRAVERSE -> new TraverseExecutionFactory();
            case DIAGONAL -> new DiagonalExecutionFactory();
            case ASCEND -> new AscendExecutionFactory();
            case DESCEND -> new DescendExecutionFactory();
            case DOWNWARD -> new com.dddgn.alice.pathing.core.DownwardExecutionFactory();
            case PILLAR -> new com.dddgn.alice.pathing.core.PillarExecutionFactory();
            case FALL -> new com.dddgn.alice.pathing.core.FallExecutionFactory();
            case BREAK_AND_TRAVERSE -> new BreakAndTraverseExecutionFactory();
            case BREAK_AND_ENTER -> new com.dddgn.alice.pathing.core.BreakAndEnterExecutionFactory();
            case PLACE_STEP_AND_TRAVERSE -> new PlaceStepAndTraverseExecutionFactory();
            default -> throw new IllegalArgumentException("unsupported movement type: " + type);
        };
    }
}
