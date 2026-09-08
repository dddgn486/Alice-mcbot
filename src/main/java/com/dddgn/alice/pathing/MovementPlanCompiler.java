package com.dddgn.alice.pathing;

import com.dddgn.alice.pathing.movement.Movement;
import com.dddgn.alice.pathing.movement.WalkMovement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * M1 编译器：将已验证的同高度四向脚位路径编译为 WalkMovement 计划。
 *
 * <p>输入路径包含起点和终点，所有坐标均表示脚位。该编译器不接触任务调度，
 * 不执行 Movement，也不提供台阶、下降、跳跃或世界修改 Movement 的隐式降级。</p>
 */
public final class MovementPlanCompiler {
    private MovementPlanCompiler() {
    }

    public static CompilationResult compile(ServerPlayer bot, List<BlockPos> footPath) {
        Objects.requireNonNull(bot, "bot");
        Objects.requireNonNull(footPath, "footPath");

        List<BlockPos> path = footPath.stream()
                .map(position -> Objects.requireNonNull(position, "footPath position").immutable())
                .toList();
        if (path.isEmpty()) {
            return CompilationResult.rejected("EMPTY_FOOT_PATH");
        }

        BlockPos startFoot = path.get(0);
        if (path.size() == 1) {
            return CompilationResult.accepted(MovementPlan.alreadyAt(startFoot, "MovementPlanCompiler"));
        }

        List<Movement> movements = new ArrayList<>(path.size() - 1);
        double totalCost = 0.0D;
        for (int i = 1; i < path.size(); i++) {
            BlockPos from = path.get(i - 1);
            BlockPos to = path.get(i);
            if (from.getY() != to.getY()) {
                return CompilationResult.rejected("NON_HORIZONTAL_WALK_SEGMENT_" + i);
            }
            int horizontalDistance = Math.abs(to.getX() - from.getX()) + Math.abs(to.getZ() - from.getZ());
            if (horizontalDistance != 1) {
                return CompilationResult.rejected("NON_ADJACENT_WALK_SEGMENT_" + i);
            }
            if (!MovementHelper.canTraverse(bot.serverLevel(), from, to)) {
                return CompilationResult.rejected("WALK_PRECONDITION_FAILED_" + i);
            }

            WalkMovement movement = WalkMovement.create(bot, from, to, bot.serverLevel());
            if (movement == null) {
                return CompilationResult.rejected("WALK_MOVEMENT_INVALID_" + i);
            }
            movements.add(movement);
            totalCost += movement.cost();
        }

        try {
            return CompilationResult.accepted(new MovementPlan(
                    AStarPathfinder.SearchStatus.REACHED,
                    startFoot,
                    path.get(path.size() - 1),
                    movements,
                    path,
                    movements.size(),
                    totalCost,
                    "MovementPlanCompiler"));
        } catch (IllegalArgumentException | NullPointerException exception) {
            return CompilationResult.rejected("PLAN_CONTRACT_INVALID_" + exception.getMessage());
        }
    }

    /** M1 编译结果；失败时不产生可执行计划。 */
    public record CompilationResult(MovementPlan plan, String failureReason) {
        public CompilationResult {
            if ((plan == null) == (failureReason == null)) {
                throw new IllegalArgumentException("exactly one of plan and failureReason must be present");
            }
        }

        public boolean accepted() {
            return plan != null;
        }

        private static CompilationResult accepted(MovementPlan plan) {
            return new CompilationResult(Objects.requireNonNull(plan, "plan"), null);
        }

        private static CompilationResult rejected(String reason) {
            return new CompilationResult(null, Objects.requireNonNull(reason, "reason"));
        }
    }
}
