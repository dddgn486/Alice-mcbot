package com.dddgn.alice.road;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.PriorityQueue;
import java.util.Map;

/**
 * 数学道路预览的服务端状态：路径中心线 + 两格净空 + 一格支撑。
 * 这是独立的可视化/搭路蓝图，不调用旧 MineTask 清障逻辑。
 */
public final class RoadPlan {
    public enum CellKind { SUPPORT_PLACE, CLEAR, OPEN }
    public record Cell(BlockPos pos, CellKind kind) {}
    public record Unit(BlockPos support, List<Cell> cells) {}

    private static final int MAX_AXIS_DELTA = 128;
    private static final RoadPlan INSTANCE = new RoadPlan();

    private BlockPos first;
    private BlockPos second;
    private ServerLevel level;
    private List<Cell> cells = List.of();
    private List<Unit> units = List.of();
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
        units = List.of();
        selected = false;
    }

    public synchronized boolean select(ServerLevel level, BlockPos target) {
        if (!selected || this.level != level) {
            this.level = level;
            this.first = target.immutable();
            this.second = null;
            this.cells = List.of();
            this.units = List.of();
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
        this.units = buildUnits(level, first, second);
        List<Cell> flattened = new ArrayList<>();
        for (Unit unit : units) flattened.addAll(unit.cells());
        this.cells = Collections.unmodifiableList(flattened);
        return true;
    }

    public synchronized boolean isComplete() { return selected && first != null && second != null; }
    public synchronized BlockPos first() { return first; }
    public synchronized BlockPos second() { return second; }
    public synchronized List<Cell> cells() { return cells; }
    public synchronized List<Unit> units() { return units; }
    public synchronized ServerLevel level() { return level; }

    private static List<Unit> buildUnits(ServerLevel level, BlockPos a, BlockPos b) {
        List<BlockPos> centerline = shortestVoxelRoute(level, a.below(), b.below());
        if (centerline.isEmpty()) {
            return List.of();
        }
        List<Unit> result = new ArrayList<>();
        for (int i = 0; i < centerline.size(); i++) {
            BlockPos support = centerline.get(i);
            Set<BlockPos> positions = new LinkedHashSet<>();
            positions.add(support);
            positions.add(support.above());
            positions.add(support.above(2));
            if (i > 0 && centerline.get(i - 1).getY() != support.getY()) {
                BlockPos low = centerline.get(i - 1).getY() < support.getY()
                        ? centerline.get(i - 1) : support;
                positions.add(low.above(2));
                positions.add(low.above(3));
            }
            // 对角拐角额外拓宽两侧净空，避免 8 邻数学连接形成玩家实际过不去的尖角。
            addDiagonalCornerClearance(centerline, i, positions);
            List<Cell> cells = new ArrayList<>();
            for (BlockPos pos : positions) {
                // 支撑格永远属于支撑结构：已有实体方块保留，只有空气才需要搭建。
                CellKind kind = pos.equals(support)
                        ? (level.getBlockState(pos).isAir() ? CellKind.SUPPORT_PLACE : CellKind.OPEN)
                        : (level.getBlockState(pos).isAir() ? CellKind.OPEN : CellKind.CLEAR);
                cells.add(new Cell(pos.immutable(), kind));
            }
            result.add(new Unit(support.immutable(), Collections.unmodifiableList(cells)));
        }
        return Collections.unmodifiableList(result);
    }

    private static void addDiagonalCornerClearance(List<BlockPos> route, int index, Set<BlockPos> positions) {
        if (index <= 0 || index >= route.size() - 1) return;
        BlockPos prev = route.get(index - 1), current = route.get(index), next = route.get(index + 1);
        int inX = Integer.compare(current.getX(), prev.getX());
        int inZ = Integer.compare(current.getZ(), prev.getZ());
        int outX = Integer.compare(next.getX(), current.getX());
        int outZ = Integer.compare(next.getZ(), current.getZ());
        if (inX != 0 && outZ != 0 || inZ != 0 && outX != 0) {
            for (int dy = 1; dy <= 2; dy++) {
                positions.add(current.offset(inX, 0, 0).above(dy));
                positions.add(current.offset(0, 0, inZ).above(dy));
                positions.add(current.above(dy));
            }
        }
    }

    private record SearchNode(BlockPos pos, int direction, double cost, double score) {}

    /** 液体膨胀区外的 3D 体素最短路：4 邻水平、单步高差最多 1、轻微转弯惩罚。 */
    private static List<BlockPos> shortestVoxelRoute(ServerLevel level, BlockPos start, BlockPos goal) {
        if (forbidden(level, start) || forbidden(level, goal)) return List.of();
        PriorityQueue<SearchNode> open = new PriorityQueue<>(java.util.Comparator.comparingDouble(SearchNode::score));
        Map<String, Double> best = new HashMap<>();
        Map<String, String> previous = new HashMap<>();
        SearchNode first = new SearchNode(start, 4, 0, heuristic(start, goal));
        open.add(first);
        best.put(key(start, 4), 0D);
        int expanded = 0;
        while (!open.isEmpty() && expanded++ < 50000) {
            SearchNode current = open.poll();
            if (current.pos().equals(goal)) return reconstruct(previous, key(current.pos(), current.direction()), start);
            if (current.cost() > best.getOrDefault(key(current.pos(), current.direction()), Double.MAX_VALUE)) continue;
            int[][] moves = {
                    {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1},
                    {1, 0, 1}, {1, 0, -1}, {-1, 0, 1}, {-1, 0, -1}
            };
            for (int direction = 0; direction < moves.length; direction++) {
                int dx = moves[direction][0], dy = moves[direction][1], dz = moves[direction][2];
                boolean diagonal = dx != 0 && dz != 0;
                BlockPos next = current.pos().offset(dx, dy, dz);
                boolean sideBlocked = diagonal
                        && (forbidden(level, current.pos().offset(dx, 0, 0))
                        || forbidden(level, current.pos().offset(0, 0, dz)));
                if (forbidden(level, next) || sideBlocked) {
                    continue;
                }
                double nextCost = current.cost() + 1.0D + (current.direction() != 4 && current.direction() != direction ? 0.08D : 0D);
                String nextKey = key(next, direction);
                if (nextCost >= best.getOrDefault(nextKey, Double.MAX_VALUE)) continue;
                best.put(nextKey, nextCost);
                previous.put(nextKey, key(current.pos(), current.direction()));
                open.add(new SearchNode(next, direction, nextCost, nextCost + heuristic(next, goal)));
            }
        }
        return List.of();
    }

    private static boolean forbidden(ServerLevel level, BlockPos support) {
        for (int dy = 0; dy <= 2; dy++) {
            BlockPos cell = support.above(dy);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (!level.getFluidState(cell.offset(dx, 0, dz)).isEmpty()) return true;
                }
            }
        }
        return false;
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ());
    }

    private static String key(BlockPos pos, int direction) {
        return pos.getX() + ":" + pos.getY() + ":" + pos.getZ() + ":" + direction;
    }

    private static List<BlockPos> reconstruct(Map<String, String> previous, String end, BlockPos start) {
        List<BlockPos> result = new ArrayList<>();
        String current = end;
        while (current != null) {
            String[] parts = current.split(":");
            result.add(0, new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
            current = previous.get(current);
        }
        return result;
    }

    /** 旧折线拟合基线已归档；当前道路只使用 shortestVoxelRoute。 */

    /** 已归档：早期局部侧移方案，当前不参与路径计算。 */
    @Deprecated
    private static List<BlockPos> rerouteLiquidClearance(ServerLevel level, List<BlockPos> original) {
        List<BlockPos> route = new ArrayList<>(original);
        for (int attempt = 0; attempt < 8; attempt++) {
            BlockPos hit = firstLiquidConflict(level, route);
            if (hit == null) return route;
            int side = (attempt % 2 == 0) ? 1 : -1;
            List<BlockPos> candidate = new ArrayList<>();
            for (BlockPos p : route) {
                if (Math.abs(p.getX() - hit.getX()) <= 1 && Math.abs(p.getZ() - hit.getZ()) <= 1) {
                    candidate.add(p.offset(0, 0, side * (attempt / 2 + 1)));
                } else {
                    candidate.add(p);
                }
            }
            if (isContinuous(candidate) && firstLiquidConflict(level, candidate) == null) return candidate;
            route = candidate;
        }
        return route;
    }

    private static BlockPos firstLiquidConflict(ServerLevel level, List<BlockPos> route) {
        for (BlockPos support : route) {
            for (int dy = 0; dy <= 2; dy++) {
                BlockPos cell = support.above(dy);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos around = cell.offset(dx, 0, dz);
                        FluidState fluid = level.getFluidState(around);
                        if (!fluid.isEmpty()) return support;
                    }
                }
            }
        }
        return null;
    }

    private static boolean isContinuous(List<BlockPos> route) {
        for (int i = 1; i < route.size(); i++) {
            BlockPos a = route.get(i - 1), b = route.get(i);
            int horizontal = Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ());
            if (horizontal != 1 || Math.abs(a.getY() - b.getY()) > 1) return false;
        }
        return true;
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
