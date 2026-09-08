package com.dddgn.alice.pathing;

import com.dddgn.alice.pathing.movement.Movement;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Objects;

/**
 * Movement 规范规划快照（M0）。
 *
 * <p>所有坐标均为 Bot 脚位（foot），不是支撑方块、目标方块或实体精确位置。
 * 该对象只描述脚位到脚位的规划结果，不拥有任务生命周期，也不执行 Movement。</p>
 */
public record MovementPlan(
        AStarPathfinder.SearchStatus status,
        BlockPos startFoot,
        BlockPos goalFoot,
        List<Movement> movements,
        List<BlockPos> projectedFootPath,
        int expandedNodes,
        double totalCost,
        String plannerName
) {
    public MovementPlan {
        status = Objects.requireNonNull(status, "status");
        startFoot = Objects.requireNonNull(startFoot, "startFoot").immutable();
        goalFoot = Objects.requireNonNull(goalFoot, "goalFoot").immutable();
        movements = List.copyOf(Objects.requireNonNull(movements, "movements"));
        projectedFootPath = immutablePositions(projectedFootPath);
        plannerName = Objects.requireNonNull(plannerName, "plannerName");

        if (expandedNodes < 0) {
            throw new IllegalArgumentException("expandedNodes must be non-negative");
        }
        if (Double.isNaN(totalCost) || totalCost < 0.0D) {
            throw new IllegalArgumentException("totalCost must be non-negative");
        }
        validateProjectedPath(startFoot, goalFoot, projectedFootPath);
        validateMovementChain(status, startFoot, goalFoot, movements);
    }

    /** 创建已到达且没有移动段的同脚位计划。 */
    public static MovementPlan alreadyAt(BlockPos foot, String plannerName) {
        BlockPos immutableFoot = Objects.requireNonNull(foot, "foot").immutable();
        return new MovementPlan(AStarPathfinder.SearchStatus.REACHED, immutableFoot, immutableFoot,
                List.of(), List.of(immutableFoot), 0, 0.0D, plannerName);
    }

    /** 规划是否找到可执行的完整脚位路线。 */
    public boolean reachable() {
        return status == AStarPathfinder.SearchStatus.REACHED;
    }

    private static void validateProjectedPath(BlockPos startFoot, BlockPos goalFoot, List<BlockPos> path) {
        if (path.isEmpty()) {
            throw new IllegalArgumentException("projectedFootPath must contain startFoot");
        }
        if (!path.get(0).equals(startFoot)) {
            throw new IllegalArgumentException("projectedFootPath must start at startFoot");
        }
        if (!path.get(path.size() - 1).equals(goalFoot)) {
            throw new IllegalArgumentException("projectedFootPath must end at goalFoot");
        }
        for (int i = 1; i < path.size(); i++) {
            if (path.get(i - 1).equals(path.get(i))) {
                throw new IllegalArgumentException("projectedFootPath must not contain duplicate adjacent feet");
            }
        }
    }

    private static void validateMovementChain(AStarPathfinder.SearchStatus status,
                                              BlockPos startFoot, BlockPos goalFoot,
                                              List<Movement> movements) {
        if (status != AStarPathfinder.SearchStatus.REACHED) {
            if (!movements.isEmpty()) {
                throw new IllegalArgumentException("non-reached MovementPlan cannot contain executable movements");
            }
            return;
        }

        if (movements.isEmpty()) {
            if (!startFoot.equals(goalFoot)) {
                throw new IllegalArgumentException("non-empty route requires at least one Movement");
            }
            return;
        }

        Movement first = requireMovement(movements.get(0), 0);
        if (!first.from().equals(startFoot)) {
            throw new IllegalArgumentException("first Movement.from must match startFoot");
        }
        for (int i = 1; i < movements.size(); i++) {
            Movement previous = requireMovement(movements.get(i - 1), i - 1);
            Movement current = requireMovement(movements.get(i), i);
            if (!previous.to().equals(current.from())) {
                throw new IllegalArgumentException("Movement chain is disconnected at index " + i);
            }
        }
        if (!movements.get(movements.size() - 1).to().equals(goalFoot)) {
            throw new IllegalArgumentException("last Movement.to must match goalFoot");
        }
    }

    private static Movement requireMovement(Movement movement, int index) {
        if (movement == null) {
            throw new NullPointerException("movements[" + index + "]");
        }
        if (movement.from() == null || movement.to() == null) {
            throw new IllegalArgumentException("Movement endpoints must not be null at index " + index);
        }
        if (movement.from().equals(movement.to())) {
            throw new IllegalArgumentException("Movement must change foot at index " + index);
        }
        return movement;
    }

    private static List<BlockPos> immutablePositions(List<BlockPos> positions) {
        Objects.requireNonNull(positions, "projectedFootPath");
        return positions.stream()
                .map(position -> Objects.requireNonNull(position, "projectedFootPath position").immutable())
                .toList();
    }
}
