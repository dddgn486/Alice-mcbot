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
}
