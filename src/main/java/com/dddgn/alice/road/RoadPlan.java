package com.dddgn.alice.road;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 数学道路预览的服务端状态：路径中心线 + 两格净空 + 一格支撑。
 * 这是独立的可视化/搭路蓝图，不调用旧 MineTask 清障逻辑。
 */
public final class RoadPlan {
    public enum CellKind { SUPPORT_PLACE, CLEAR, OPEN }
    public record Cell(BlockPos pos, CellKind kind) {}

    private static final int MAX_AXIS_DELTA = 128;
    private static final RoadPlan INSTANCE = new RoadPlan();

    private BlockPos first;
    private BlockPos second;
    private ServerLevel level;
    private List<Cell> cells = List.of();
    private boolean selected;

    private RoadPlan() {}

    public static RoadPlan get() {
        return INSTANCE;
    }

    public synchronized void reset() {
        first = null;
        second = null;
        level = null;
        cells = List.of();
        selected = false;
    }

    public synchronized boolean select(ServerLevel level, BlockPos target) {
        if (!selected || this.level != level) {
            this.level = level;
            this.first = target.immutable();
            this.second = null;
            this.cells = List.of();
            this.selected = true;
            return false;
        }
        this.second = target.immutable();
        if (Math.abs(first.getX() - second.getX()) > MAX_AXIS_DELTA
                || Math.abs(first.getY() - second.getY()) > MAX_AXIS_DELTA
                || Math.abs(first.getZ() - second.getZ()) > MAX_AXIS_DELTA) {
            reset();
            return false;
        }
        this.cells = build(level, first, second);
        return true;
    }

    public synchronized boolean isComplete() { return selected && first != null && second != null; }
    public synchronized BlockPos first() { return first; }
    public synchronized BlockPos second() { return second; }
    public synchronized List<Cell> cells() { return cells; }
    public synchronized ServerLevel level() { return level; }

    private static List<Cell> build(ServerLevel level, BlockPos a, BlockPos b) {
        List<BlockPos> centerline = orthogonalLine(a.below(), b.below());
        Set<BlockPos> path = new LinkedHashSet<>();
        for (BlockPos support : centerline) {
            path.add(support);
            path.add(support.above());
            path.add(support.above(2));
        }
        // 高度变化的低侧额外清出一格，避免两格净空在错位口只剩一格。
        for (int i = 1; i < centerline.size(); i++) {
            BlockPos prev = centerline.get(i - 1);
            BlockPos curr = centerline.get(i);
            if (prev.getY() != curr.getY()) {
                BlockPos low = prev.getY() < curr.getY() ? prev : curr;
                path.add(low.above(2));
                path.add(low.above(3));
            }
        }
        List<Cell> result = new ArrayList<>();
        for (BlockPos pos : path) {
            BlockState state = level.getBlockState(pos);
            CellKind kind;
            if (pos.getY() == supportYFor(pos, centerline)) {
                kind = state.isAir() ? CellKind.SUPPORT_PLACE : CellKind.CLEAR;
            } else {
                kind = state.isAir() ? CellKind.OPEN : CellKind.CLEAR;
            }
            result.add(new Cell(pos.immutable(), kind));
        }
        return Collections.unmodifiableList(result);
    }

    private static int supportYFor(BlockPos pos, List<BlockPos> centerline) {
        for (BlockPos support : centerline) {
            if (support.getX() == pos.getX() && support.getZ() == pos.getZ()
                    && support.getY() == pos.getY()) return support.getY();
        }
        return Integer.MIN_VALUE;
    }

    /** 先沿 X，再沿 Z；所有水平拐点都是 4 邻，避免对角线。Y 每步最多变化 1。 */
    private static List<BlockPos> orthogonalLine(BlockPos a, BlockPos b) {
        List<BlockPos> result = new ArrayList<>();
        int x = a.getX(), y = a.getY(), z = a.getZ();
        result.add(new BlockPos(x, y, z));
        while (x != b.getX()) {
            x += Integer.compare(b.getX(), x);
            result.add(new BlockPos(x, y, z));
        }
        while (z != b.getZ()) {
            z += Integer.compare(b.getZ(), z);
            result.add(new BlockPos(x, y, z));
        }
        while (y != b.getY()) {
            y += Integer.compare(b.getY(), y);
            result.add(new BlockPos(x, y, z));
        }
        return result;
    }
}
