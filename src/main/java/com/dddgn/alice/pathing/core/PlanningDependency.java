package com.dddgn.alice.pathing.core;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Objects;

/** 规划阶段实际读取的世界事实范围，供执行期 stale 检查使用。 */
public record PlanningDependency(
        List<BlockPos> checkedPositions,
        List<BlockPos> collisionPositions,
        List<BlockPos> supportPositions,
        List<BlockPos> fluidPositions,
        List<BlockPos> hazardPositions,
        List<BlockPos> protectedPositions,
        long worldRevision,
        long policyVersion
) {
    public PlanningDependency {
        checkedPositions = immutable(checkedPositions);
        collisionPositions = immutable(collisionPositions);
        supportPositions = immutable(supportPositions);
        fluidPositions = immutable(fluidPositions);
        hazardPositions = immutable(hazardPositions);
        protectedPositions = immutable(protectedPositions);
        if (worldRevision < 0 || policyVersion < 0) {
            throw new IllegalArgumentException("revisions must be non-negative");
        }
    }

    private static List<BlockPos> immutable(List<BlockPos> positions) {
        Objects.requireNonNull(positions, "positions");
        return positions.stream().map(pos -> Objects.requireNonNull(pos, "position").immutable()).toList();
    }
}
