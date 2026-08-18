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

    /**
     * 三维体素中心线：水平投影只用 4 邻折线，并把高度变化分摊到水平步进上。
     * <p>如果 |dy| 大于原始水平曼哈顿长度，就在侧方增加最小折返：每偏移一格
     * 可增加两格水平路程，直到满足每个相邻中心线点的高度差最多一格。这样不会
     * 在终点前生成竖井；竖直变化被拟合成真正的斜向阶梯。</p>
     */
    private static List<BlockPos> orthogonalLine(BlockPos a, BlockPos b) {
        int horizontal = Math.abs(b.getX() - a.getX()) + Math.abs(b.getZ() - a.getZ());
        int vertical = Math.abs(b.getY() - a.getY());
        int detour = Math.max(0, (vertical - horizontal + 1) / 2);
        List<BlockPos> horizontalLine = horizontalRoute(a, b, detour);
        int direction = Integer.compare(b.getY(), a.getY());
        List<BlockPos> result = new ArrayList<>(horizontalLine.size());
        for (int i = 0; i < horizontalLine.size(); i++) {
            BlockPos p = horizontalLine.get(i);
            int yOffset = direction * (vertical * i / Math.max(1, horizontalLine.size() - 1));
            result.add(new BlockPos(p.getX(), a.getY() + yOffset, p.getZ()));
        }
        return result;
    }

    /** 生成 4 邻水平折线；detour 是侧向偏移量，增加约 2*detour 格长度。 */
    private static List<BlockPos> horizontalRoute(BlockPos a, BlockPos b, int detour) {
        List<BlockPos> result = new ArrayList<>();
        int x = a.getX();
        int z = a.getZ();
        result.add(new BlockPos(x, a.getY(), z));
        if (detour > 0) {
            // 选择 z 方向侧移；随后沿 x/z 主方向走，再回到终点 z。
            int side = z <= b.getZ() ? 1 : -1;
            z += side * detour;
            appendHorizontal(result, x, z, a.getY());
        }
        while (x != b.getX()) {
            x += Integer.compare(b.getX(), x);
            appendHorizontal(result, x, z, a.getY());
        }
        while (z != b.getZ() + (detour > 0 ? (z > b.getZ() ? -detour : detour) : 0)) {
            int targetZ = b.getZ() + (detour > 0 ? (z > b.getZ() ? -detour : detour) : 0);
            if (z == targetZ) break;
            z += Integer.compare(targetZ, z);
            appendHorizontal(result, x, z, a.getY());
        }
        if (detour > 0) {
            z = b.getZ();
            appendHorizontal(result, b.getX(), z, a.getY());
        }
        while (x != b.getX()) {
            x += Integer.compare(b.getX(), x);
            appendHorizontal(result, x, z, a.getY());
        }
        return result;
    }

    private static void appendHorizontal(List<BlockPos> result, int x, int z, int y) {
        BlockPos next = new BlockPos(x, y, z);
        if (!result.get(result.size() - 1).equals(next)) result.add(next);
    }
}
