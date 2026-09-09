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
        MovementCapabilities capabilities = switch (movement.movementType()) {
            case PLACE_STEP_AND_TRAVERSE, PILLAR ->
                    MovementCapabilities.temporarySupport(RecoverabilityLevel.LOCAL_STEP);
            case BREAK_AND_TRAVERSE, DOWNWARD ->
                    MovementCapabilities.pathAccess(RecoverabilityLevel.LOCAL_STEP);
            default -> MovementCapabilities.pureTraversal(
                    RecoverabilityLevel.LOCAL_STEP, IntrinsicReversibility.REVERSIBLE);
        };
        BlockPos to = movement.toFoot();
        PlanningDependency dependency = new PlanningDependency(
                List.of(movement.fromFoot(), to, to.above(), to.below()),
                List.of(to, to.above()), List.of(to.below()),
                List.of(to, to.below()), List.of(to, to.below()),
                List.of(), 0L, 0L);
        return new MovementSpec(movement.movementType(), movement.fromFoot(), to,
                movement.cost(), capabilities, List.of(), List.of(), planningFacts,
                dependency, RecoverabilityLevel.LOCAL_STEP, factoryKey(movement.movementType()));
    }

    public static String factoryKey(MovementType type) {
        return switch (type) {
            case TRAVERSE -> TraverseExecutionFactory.KEY;
            case DIAGONAL -> DiagonalExecutionFactory.KEY;
            case ASCEND -> AscendExecutionFactory.KEY;
            case DESCEND -> DescendExecutionFactory.KEY;
            case DOWNWARD -> com.dddgn.alice.pathing.core.DownwardExecutionFactory.KEY;
            case PILLAR -> com.dddgn.alice.pathing.core.PillarExecutionFactory.KEY;
            case BREAK_AND_TRAVERSE -> BreakAndTraverseExecutionFactory.KEY;
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
            case BREAK_AND_TRAVERSE -> new BreakAndTraverseExecutionFactory();
            case PLACE_STEP_AND_TRAVERSE -> new PlaceStepAndTraverseExecutionFactory();
            default -> throw new IllegalArgumentException("unsupported movement type: " + type);
        };
    }
}
