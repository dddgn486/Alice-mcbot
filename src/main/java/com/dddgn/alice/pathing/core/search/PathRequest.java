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

    public static PathRequest of(String botId, BlockPos startFoot, BlockPos goalFoot, String requester) {
        return new PathRequest(botId, startFoot, new GoalFoot(goalFoot),
                Set.of(MovementType.TRAVERSE, MovementType.DIAGONAL,
                        MovementType.ASCEND, MovementType.DESCEND),
                SearchBudget.UNLIMITED, requester);
    }

    /** 纯通行 + 世界修改（PATH_ACCESS 破坏 + TEMPORARY_SUPPORT 放置，R5-2/R5-3）。 */
    public static PathRequest withWorldModification(String botId, BlockPos startFoot, BlockPos goalFoot,
                                                     String requester) {
        return new PathRequest(botId, startFoot, new GoalFoot(goalFoot),
                Set.of(MovementType.TRAVERSE, MovementType.DIAGONAL, MovementType.ASCEND,
                        MovementType.DESCEND, MovementType.DOWNWARD, MovementType.PILLAR,
                        MovementType.FALL,
                        MovementType.BREAK_AND_TRAVERSE,
                        MovementType.BREAK_AND_ENTER,
                        MovementType.PLACE_STEP_AND_TRAVERSE),
                SearchBudget.UNLIMITED, requester);
    }

    /**
     * 挖掘到达请求（D-067 ㉘）：允许破坏进入 / 破坏通行 / 放置台阶，**显式禁用** PILLAR / FALL / DOWNWARD，
     * 避免"挖矿时先搭柱子/跳下来"这类奇技。
     */
    public static PathRequest miningApproach(String botId, BlockPos startFoot, BlockPos goalFoot,
                                             String requester) {
        return new PathRequest(botId, startFoot, new GoalFoot(goalFoot),
                Set.of(MovementType.TRAVERSE, MovementType.DIAGONAL, MovementType.ASCEND,
                        MovementType.DESCEND, MovementType.BREAK_AND_TRAVERSE,
                        MovementType.BREAK_AND_ENTER, MovementType.PLACE_STEP_AND_TRAVERSE),
                SearchBudget.UNLIMITED, requester);
    }

    /**
     * 脚手架回收请求（J6-b）：**只拆不建、且只允许向下拆脚下那一格**。
     *
     * <p>为什么需要它：拆自己放的垫脚柱/台阶时，目标常常就是**脚下那格**——
     * 而 `MineTask` 的模式 A 显式排除 `target.above()` 作为站位（那是 DOWNWARD 的语义），
     * 于是"用 MineTask 拆脚下"**必然规划失败**（实测：4 块全部 `no_valid/no_reachable_standing_point`）。
     * 设计文档早在 D-081 §12.3 写明正确机制：**挖脚下 → `DOWNWARD` 落 1 格 → 重复**。
     *
     * <p>允许集合刻意**不含** `PILLAR` / `PLACE_STEP_AND_TRAVERSE`（回收阶段不许再建），
     * 也不含 `BREAK_AND_*`（不许沿途挖穿地形）；只允许通行 + `DOWNWARD` + `FALL`。
     * 这是 D-076 意义上**新增的显式授权入口**，已在 `docs/WORLD_WRITE_AUTHORIZATION.md` 登记。
     */
    public static PathRequest scaffoldRemoval(String botId, BlockPos startFoot, BlockPos goalFoot) {
        return new PathRequest(botId, startFoot, new GoalFoot(goalFoot),
                Set.of(MovementType.TRAVERSE, MovementType.DIAGONAL, MovementType.ASCEND,
                        MovementType.DESCEND, MovementType.DOWNWARD, MovementType.FALL),
                SearchBudget.UNLIMITED, "restore");
    }

    public boolean allows(MovementType type) {
        return allowedMovementTypes.contains(type);
    }
}
