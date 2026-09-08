package com.dddgn.alice.pathing.core;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Objects;

/** R2 搜索输出的纯数据 Movement 描述，不绑定实时 Bot 或世界。 */
public record MovementSpec(
        MovementType movementType,
        BlockPos fromFoot,
        BlockPos toFoot,
        double cost,
        MovementCapabilities capabilities,
        List<String> requiredResources,
        List<String> expectedWorldEffects,
        List<String> planningPreconditionFacts,
        PlanningDependency planningDependency,
        RecoverabilityLevel evaluatedRecoverability,
        String executionFactoryKey
) {
    public MovementSpec {
        movementType = Objects.requireNonNull(movementType, "movementType");
        fromFoot = Objects.requireNonNull(fromFoot, "fromFoot").immutable();
        toFoot = Objects.requireNonNull(toFoot, "toFoot").immutable();
        capabilities = Objects.requireNonNull(capabilities, "capabilities");
        requiredResources = immutableStrings(requiredResources, "requiredResources");
        expectedWorldEffects = immutableStrings(expectedWorldEffects, "expectedWorldEffects");
        planningPreconditionFacts = immutableStrings(planningPreconditionFacts, "planningPreconditionFacts");
        planningDependency = Objects.requireNonNull(planningDependency, "planningDependency");
        evaluatedRecoverability = Objects.requireNonNull(evaluatedRecoverability, "evaluatedRecoverability");
        executionFactoryKey = Objects.requireNonNull(executionFactoryKey, "executionFactoryKey");
        if (executionFactoryKey.isBlank()) {
            throw new IllegalArgumentException("executionFactoryKey must not be blank");
        }
        if (fromFoot.equals(toFoot)) {
            throw new IllegalArgumentException("MovementSpec must change foot position");
        }
        if (Double.isNaN(cost) || Double.isInfinite(cost) || cost < 0.0D) {
            throw new IllegalArgumentException("cost must be finite and non-negative");
        }
        if (evaluatedRecoverability.ordinal() < capabilities.requiredRecoverabilityLevel().ordinal()) {
            throw new IllegalArgumentException("evaluated recoverability is below Movement requirement");
        }
        validateDisplacement(movementType, fromFoot, toFoot);
    }

    private static List<String> immutableStrings(List<String> values, String name) {
        Objects.requireNonNull(values, name);
        return values.stream().map(value -> Objects.requireNonNull(value, name + " value")).toList();
    }

    private static void validateDisplacement(MovementType type, BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        switch (type) {
            case TRAVERSE -> {
                if (dy != 0 || Math.abs(dx) + Math.abs(dz) != 1) {
                    throw new IllegalArgumentException("TRAVERSE requires one same-level cardinal step");
                }
            }
            case DIAGONAL -> {
                if (dy != 0 || Math.abs(dx) != 1 || Math.abs(dz) != 1) {
                    throw new IllegalArgumentException("DIAGONAL requires one same-level diagonal step");
                }
            }
            case ASCEND -> {
                if (dy != 1 || Math.abs(dx) > 1 || Math.abs(dz) > 1 || (dx == 0 && dz == 0)) {
                    throw new IllegalArgumentException("ASCEND requires a horizontal step with dy=+1");
                }
            }
            case DESCEND -> {
                if (dy != -1 || Math.abs(dx) > 1 || Math.abs(dz) > 1 || (dx == 0 && dz == 0)) {
                    throw new IllegalArgumentException("DESCEND requires a horizontal step with dy=-1");
                }
            }
            case BREAK_AND_TRAVERSE -> {
                // 语义：破坏"中间列"后走到其后一格 → 同层直线 2 格
                boolean straightTwo = (Math.abs(dx) == 2 && dz == 0) || (Math.abs(dz) == 2 && dx == 0);
                if (dy != 0 || !straightTwo) {
                    throw new IllegalArgumentException(
                            "BREAK_AND_TRAVERSE requires a straight same-level two-block step");
                }
            }
            case PLACE_STEP_AND_TRAVERSE -> {
                // World-modifying displacement rules are movement-specific and remain future work.
            }
        }
    }
}
