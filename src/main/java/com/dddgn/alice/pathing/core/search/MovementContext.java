package com.dddgn.alice.pathing.core.search;

import com.dddgn.alice.pathing.core.MovementType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;

/**
 * 规划上下文：世界读取 + 请求策略 + 成本模型 + 维度边界（架构文档 §6）。
 *
 * <p>R3 为同步主线程搜索，直接持有 {@link ServerLevel} 读取实时世界；
 * 目前直读服务端世界；R7 异步搜索如需快照，将在此接入（空接口 WorldView 已删除，见 D-044）。
 */
public record MovementContext(
        ServerPlayer bot,
        ServerLevel level,
        PathRequest request,
        CostModel costModel,
        int minY,
        int maxY
) {
    public MovementContext {
        level = Objects.requireNonNull(level, "level");
        request = Objects.requireNonNull(request, "request");
        costModel = Objects.requireNonNull(costModel, "costModel");
    }

    public static MovementContext live(ServerPlayer bot, ServerLevel level, PathRequest request) {
        return new MovementContext(bot, level, request, CostModel.TRAVERSAL,
                level.getMinBuildHeight(), level.getMaxBuildHeight());
    }

    public boolean allows(MovementType type) {
        return request.allows(type);
    }

    public boolean yInBounds(int y) {
        return y >= minY && y < maxY;
    }

    public double cost(MovementType type, BlockPos from, BlockPos to) {
        return costModel.cost(type, level, from, to);
    }

    /**
     * 该类 Movement 的**写入下界**（D-106 Slice B）：一条写边至少产生的破坏次数。
     *
     * <p>刻意取下界（不为 0 的写边一律记 1）：计划期剪枝只用它判断"还写不写得动"，
     * 取上界会误剪合法路径（实测教训：把两格高墙的 `BREAK_AND_TRAVERSE` 记成 2，
     * 上限 1 的场景会被整条剪掉）。真正的"绝不超过上限"由执行期闸门保证。
     */
    public static int plannedBreaks(MovementType type) {
        return switch (type) {
            case BREAK_AND_TRAVERSE, BREAK_AND_ENTER, DOWNWARD -> 1;
            default -> 0;
        };
    }

    /** 该类 Movement 的**写入下界**：一条写边至少产生的放置次数。 */
    public static int plannedPlaces(MovementType type) {
        return switch (type) {
            case PLACE_STEP_AND_TRAVERSE, PILLAR -> 1;
            default -> 0;
        };
    }

    /**
     * 本条写边现在还能不能规划（D-106 Slice B）：任一预算桶耗尽 → 一律 {@code false}
     * （"耗尽后降级为纯通行"，不允许换成另一种写入方式继续试）。
     */
    public boolean writesAllowed(MovementType type) {
        int breaks = plannedBreaks(type);
        int places = plannedPlaces(type);
        if (breaks == 0 && places == 0) {
            return true;
        }
        if (bot == null) {
            return true;   // 无 bot 的纯规划（headless 回归）不做预算剪枝
        }
        return com.dddgn.alice.action.WriteBudget.plannedWritesAllowed(bot, breaks, places);
    }
}
