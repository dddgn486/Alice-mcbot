package com.dddgn.alice.road;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 连续道路候选的几何层。它只生成不自交的平滑中心线并体素化，不读取世界；
 * 障碍、液体禁区、净空和单元角色仍由 RoadPlan 的离散验证层裁定。
 */
public final class ContinuousRoadCurve {
    private static final int ARC_SAMPLES = 2048;
    private static final double MAX_NORMAL_LATERAL_OFFSET = 24.0D;

    private ContinuousRoadCurve() {}

    public record Candidate(List<BlockPos> route, double lateralOffset, double horizontalArcLength) {}

    /**
     * 生成直线和两侧平滑二次 Bezier 候选。高度按水平弧长均匀分配，
     * 因而候选必须先具备不少于垂直差的水平弧长，才能满足每步最多一格高差。
     */
    public static List<Candidate> candidates(BlockPos start, BlockPos goal) {
        int horizontalDx = goal.getX() - start.getX();
        int horizontalDz = goal.getZ() - start.getZ();
        double chord = Math.hypot(horizontalDx, horizontalDz);
        if (chord < 0.001D) {
            return List.of(); // 零水平距离的高差由结构化螺旋原语负责。
        }

        double lateralX = -horizontalDz / chord;
        double lateralZ = horizontalDx / chord;
        List<Double> offsets = new ArrayList<>();
        offsets.add(0.0D);
        for (double offset = 2.0D; offset <= MAX_NORMAL_LATERAL_OFFSET; offset += 2.0D) {
            offsets.add(offset);
            offsets.add(-offset);
        }

        List<Candidate> result = new ArrayList<>();
        for (double offset : offsets) {
            Voxelized voxelized = voxelizeQuadratic(start, goal, lateralX * offset, lateralZ * offset);
            if (!voxelized.route().isEmpty()) {
                result.add(new Candidate(voxelized.route(), offset, voxelized.horizontalArcLength()));
            }
        }
        return List.copyOf(result);
    }

    private record Voxelized(List<BlockPos> route, double horizontalArcLength) {}

    private static Voxelized voxelizeQuadratic(BlockPos start, BlockPos goal,
                                                double controlOffsetX, double controlOffsetZ) {
        double controlX = (start.getX() + goal.getX()) * 0.5D + controlOffsetX;
        double controlZ = (start.getZ() + goal.getZ()) * 0.5D + controlOffsetZ;
        double[] lengths = new double[ARC_SAMPLES + 1];
        double previousX = start.getX();
        double previousZ = start.getZ();
        for (int i = 1; i <= ARC_SAMPLES; i++) {
            double t = (double) i / ARC_SAMPLES;
            double x = quadratic(start.getX(), controlX, goal.getX(), t);
            double z = quadratic(start.getZ(), controlZ, goal.getZ(), t);
            lengths[i] = lengths[i - 1] + Math.hypot(x - previousX, z - previousZ);
            previousX = x;
            previousZ = z;
        }
        double totalLength = lengths[ARC_SAMPLES];
        int verticalDistance = Math.abs(goal.getY() - start.getY());
        if (totalLength + 1.0E-6D < verticalDistance) {
            return new Voxelized(List.of(), 0.0D);
        }

        // 先把连续曲线采样为水平折线，再逐段补齐经过的格子；不直接把采样点 round
        // 后相连，避免曲线在方块边界附近产生断步或假性的重复投影失败。
        List<BlockPos> horizontal = new ArrayList<>();
        for (int i = 0; i <= ARC_SAMPLES; i++) {
            double t = (double) i / ARC_SAMPLES;
            int x = (int) Math.round(quadratic(start.getX(), controlX, goal.getX(), t));
            int z = (int) Math.round(quadratic(start.getZ(), controlZ, goal.getZ(), t));
            BlockPos sample = new BlockPos(x, start.getY(), z);
            if (horizontal.isEmpty()) {
                horizontal.add(sample);
            } else {
                appendGridSegment(horizontal, sample);
            }
        }
        if (horizontal.isEmpty() || !horizontal.get(0).equals(start)
                || !horizontal.get(horizontal.size() - 1).equals(goal)) {
            return new Voxelized(List.of(), totalLength);
        }

        List<BlockPos> route = new ArrayList<>();
        Set<String> projections = new LinkedHashSet<>();
        double gridLength = Math.max(1.0D, horizontal.size() - 1.0D);
        int signY = Integer.compare(goal.getY(), start.getY());
        for (int i = 0; i < horizontal.size(); i++) {
            BlockPos flat = horizontal.get(i);
            int y = (int) Math.round(start.getY() + signY * verticalDistance * i / gridLength);
            BlockPos point = new BlockPos(flat.getX(), y, flat.getZ());
            if (!route.isEmpty()) {
                BlockPos previous = route.get(route.size() - 1);
                int dx = Math.abs(point.getX() - previous.getX());
                int dz = Math.abs(point.getZ() - previous.getZ());
                int dy = Math.abs(point.getY() - previous.getY());
                if ((dx == 0 && dz == 0) || dx > 1 || dz > 1 || dy > 1) {
                    return new Voxelized(List.of(), totalLength);
                }
            }
            if (!projections.add(point.getX() + ":" + point.getZ())) {
                return new Voxelized(List.of(), totalLength);
            }
            route.add(point);
        }
        return new Voxelized(List.copyOf(route), totalLength);
    }

    /** Append a continuous 4/8-neighbour grid segment without skipping cells. */
    private static void appendGridSegment(List<BlockPos> route, BlockPos target) {
        BlockPos current = route.get(route.size() - 1);
        while (!current.equals(target)) {
            int dx = Integer.compare(target.getX(), current.getX());
            int dz = Integer.compare(target.getZ(), current.getZ());
            current = current.offset(dx, 0, dz);
            if (!current.equals(route.get(route.size() - 1))) {
                route.add(current);
            }
        }
    }

    private static double quadratic(double start, double control, double goal, double t) {
        double inverse = 1.0D - t;
        return inverse * inverse * start + 2.0D * inverse * t * control + t * t * goal;
    }
}
