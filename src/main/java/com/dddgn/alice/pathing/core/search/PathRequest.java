package com.dddgn.alice.pathing.core.search;

import com.dddgn.alice.pathing.core.MovementType;
import net.minecraft.core.BlockPos;

import java.util.Objects;
import java.util.Set;

/**
 * 路径规划请求（架构文档 §5.1）。
 *
 * <p>显式携带策略与预算，避免"含义不清的 BlockPos target"。
 * 坐标语义统一为**脚位（foot）**。
 */
public record PathRequest(
        String botId,
        BlockPos startFoot,
        GoalSpec goal,
        Set<MovementType> allowedMovementTypes,
        SearchBudget budget,
        String requester
) {
    public PathRequest {
        botId = Objects.requireNonNull(botId, "botId");
        startFoot = Objects.requireNonNull(startFoot, "startFoot").immutable();
        goal = Objects.requireNonNull(goal, "goal");
        allowedMovementTypes = Set.copyOf(Objects.requireNonNull(allowedMovementTypes, "allowedMovementTypes"));
        budget = budget == null ? SearchBudget.UNLIMITED : budget;
        requester = requester == null ? "unknown" : requester;
        if (allowedMovementTypes.isEmpty()) {
            throw new IllegalArgumentException("allowedMovementTypes must not be empty");
        }
    }

    public static PathRequest of(String botId, BlockPos startFoot, BlockPos goalFoot) {
        return new PathRequest(botId, startFoot, new GoalFoot(goalFoot),
                Set.of(MovementType.TRAVERSE, MovementType.DIAGONAL,
                        MovementType.ASCEND, MovementType.DESCEND),
                SearchBudget.UNLIMITED, "unknown");
    }

    /** 纯通行 + 世界修改（PATH_ACCESS 破坏 + TEMPORARY_SUPPORT 放置，R5-2/R5-3）。 */
    public static PathRequest withWorldModification(String botId, BlockPos startFoot, BlockPos goalFoot) {
        return new PathRequest(botId, startFoot, new GoalFoot(goalFoot),
                Set.of(MovementType.TRAVERSE, MovementType.DIAGONAL, MovementType.ASCEND,
                        MovementType.DESCEND, MovementType.DOWNWARD, MovementType.PILLAR,
                        MovementType.FALL,
                        MovementType.BREAK_AND_TRAVERSE,
                        MovementType.BREAK_AND_ENTER,
                        MovementType.PLACE_STEP_AND_TRAVERSE),
                SearchBudget.UNLIMITED, "unknown");
    }

    /**
     * 挖掘到达请求（D-067 ㉘）：允许破坏进入 / 破坏通行 / 放置台阶，**显式禁用** PILLAR / FALL / DOWNWARD，
     * 避免"挖矿时先搭柱子/跳下来"这类奇技。
     */
    public static PathRequest miningApproach(String botId, BlockPos startFoot, BlockPos goalFoot) {
        return new PathRequest(botId, startFoot, new GoalFoot(goalFoot),
                Set.of(MovementType.TRAVERSE, MovementType.DIAGONAL, MovementType.ASCEND,
                        MovementType.DESCEND, MovementType.BREAK_AND_TRAVERSE,
                        MovementType.BREAK_AND_ENTER, MovementType.PLACE_STEP_AND_TRAVERSE),
                SearchBudget.UNLIMITED, "unknown");
    }

    public boolean allows(MovementType type) {
        return allowedMovementTypes.contains(type);
    }
}
