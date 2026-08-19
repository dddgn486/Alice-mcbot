package com.dddgn.alice.pathing;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * 不可变的数学通道计划。施工阶段只能消费已选定的入口、出口和曲线，
 * 不得反向修改规划结果或复用手动道路蓝图。
 */
public record TunnelPlan(
        List<BlockPos> surfacePathToEntrance,
        BlockPos entrance,
        List<BlockPos> tunnelCurve,
        BlockPos exit,
        List<BlockPos> surfacePathToStand,
        double cost) {

    public static final double TUNNEL_FACTOR = 10.0D;

    public TunnelPlan {
        surfacePathToEntrance = immutablePositions(surfacePathToEntrance);
        entrance = entrance.immutable();
        tunnelCurve = immutablePositions(tunnelCurve);
        exit = exit.immutable();
        surfacePathToStand = immutablePositions(surfacePathToStand);
        if (!Double.isFinite(cost) || cost < 0.0D) {
            throw new IllegalArgumentException("Tunnel cost must be finite and non-negative");
        }
    }

    public double tunnelGeometryLength() {
        return geometryLength(entrance, tunnelCurve, exit);
    }

    private static List<BlockPos> immutablePositions(List<BlockPos> positions) {
        return positions.stream().map(BlockPos::immutable).toList();
    }

    static double geometryLength(BlockPos entrance, List<BlockPos> curve, BlockPos exit) {
        double length = 0.0D;
        BlockPos previous = entrance;
        for (BlockPos point : curve) {
            length += distance(previous, point);
            previous = point;
        }
        return length + distance(previous, exit);
    }

    private static double distance(BlockPos a, BlockPos b) {
        return Math.sqrt(a.distSqr(b));
    }
}
