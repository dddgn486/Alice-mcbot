package com.dddgn.alice.road;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import com.dddgn.alice.log.BotLog;

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
    public enum UnitKind { NORMAL, SPIRAL }
    public record Cell(BlockPos pos, CellKind kind) {}
    public record Unit(BlockPos support, UnitKind kind, int headroom, List<Cell> cells) {}

    private static final int MAX_AXIS_DELTA = 128;
    /** 数学线路的轻微高差惩罚，仅用于几何候选排序，不代表施工格耗时。 */
    private static final double HEIGHT_CHANGE_COST = 0.25D;
    /** 螺旋触发的高差裕量：普通弯曲路径优先，只有当普通搜索失败且高差明显超过水平容量时才尝试螺旋。 */
    private static final int SPIRAL_EXTRA = 4;
    private static final RoadPlan INSTANCE = new RoadPlan();

    private BlockPos first;
    private BlockPos second;
    private ServerLevel level;
    private List<Cell> cells = List.of();
    private List<Unit> units = List.of();
    private boolean selected;
    private String lastFailureReason = "";

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
        lastFailureReason = "";
    }

    /** 最近一次 select 的失败原因（起点已选或生成成功时为空串）。 */
    public synchronized String lastFailureReason() {
        return lastFailureReason;
    }

    public synchronized boolean select(ServerLevel level, BlockPos target) {
        if (!selected || this.level != level) {
            this.level = level;
            this.first = target.immutable();
            this.second = null;
            this.cells = List.of();
            this.units = List.of();
            this.selected = true;
            this.lastFailureReason = "";
            return false;
        }
        this.second = target.immutable();
        if (Math.abs(first.getX() - second.getX()) > MAX_AXIS_DELTA
                || Math.abs(first.getY() - second.getY()) > MAX_AXIS_DELTA
                || Math.abs(first.getZ() - second.getZ()) > MAX_AXIS_DELTA) {
            reset();
            this.lastFailureReason = "distance_exceeds_limit";
            return false;
        }
        this.units = buildUnits(level, first, second);
        if (this.units.isEmpty()) {
            BotLog.warn("道路蓝图未发布: 两点之间没有经过验证的可行单元");
            this.cells = List.of();
            this.second = null;
            this.lastFailureReason = "no_walkable_route";
            return false;
        }
        List<Cell> flattened = new ArrayList<>();
        for (Unit unit : units) flattened.addAll(unit.cells());
        this.cells = Collections.unmodifiableList(flattened);
        this.lastFailureReason = "";
        return true;
    }

    public synchronized boolean isComplete() { return selected && first != null && second != null; }
    public synchronized BlockPos first() { return first; }
    public synchronized BlockPos second() { return second; }
    public synchronized List<Cell> cells() { return cells; }
    public synchronized List<Unit> units() { return units; }
    public synchronized ServerLevel level() { return level; }

    /** 为目标挖掘生成一次性道路执行单元：起点是 bot 当前脚位，终点是目标下方支撑格。 */
    public static List<Unit> planForMining(ServerLevel level, BlockPos botFoot, BlockPos target) {
        if (level == null || botFoot == null || target == null) return List.of();
        // botFoot 是 bot 的脚位空气格；buildUnits 取 below() 后正好得到其当前脚下支撑。
        return buildUnits(level, botFoot, target);
    }

    private record Route(List<BlockPos> centerline, Set<BlockPos> spiralSupports,
                         Set<BlockPos> spiralFootprint) {}

    private static List<Unit> buildUnits(ServerLevel level, BlockPos a, BlockPos b) {
        BlockPos start = a.below();
        BlockPos goal = b.below();
        int horizontalDistance = Math.abs(start.getX() - goal.getX()) + Math.abs(start.getZ() - goal.getZ());
        int verticalDistance = Math.abs(start.getY() - goal.getY());
        // 普通弯曲路径优先：水平搜索本身可以向外借步长（有 24 格边界容错）。
        // 只有当普通搜索无解，且高差明显超过水平容量时才启用螺旋，避免中等高差两头落空。
        Route route;
        List<BlockPos> curved = selectContinuousRoute(level, start, goal);
        List<BlockPos> weighted = shortestVoxelRoute(level, start, goal, 2);
        if (!curved.isEmpty() && !weighted.isEmpty()) {
            double curvedCost = geometricRouteCost(curved);
            double weightedCost = geometricRouteCost(weighted);
            route = new Route(curvedCost <= weightedCost ? curved : weighted, Set.of(), Set.of());
            BotLog.info("混合寻路比较(几何线路): curveLength={} voxelLength={} selected={}",
                    format(curvedCost), format(weightedCost), curvedCost <= weightedCost ? "curve" : "voxel");
        } else if (!curved.isEmpty()) {
            route = new Route(curved, Set.of(), Set.of());
        } else if (!weighted.isEmpty()) {
            route = new Route(weighted, Set.of(), Set.of());
            BotLog.info("连续道路候选未通过，使用几何体素 A* 后备: routeUnits={} length={}",
                    weighted.size(), format(geometricRouteCost(weighted)));
        } else if (verticalDistance > 0) {
            // 普通混合搜索无解时，才尝试结构化螺旋补偿高差。
            route = spiralCompensationRoute(level, start, goal);
        } else {
            route = new Route(List.of(), Set.of(), Set.of());
        }
        BotLog.info("道路路线选择: mode={} horizontal={} vertical={} spiralExtra={} geometricLength={} start={} goal={}",
                route.spiralSupports().isEmpty() ? "normal" : "spiral",
                horizontalDistance, verticalDistance, SPIRAL_EXTRA,
                route.centerline().isEmpty() ? "inf" : format(geometricRouteCost(route.centerline())),
                start.toShortString(), goal.toShortString());
        List<BlockPos> centerline = route.centerline();
        if (centerline.isEmpty()) {
            BotLog.warn("道路蓝图生成失败: 体素搜索无路 start={} goal={}",
                    a.below().toShortString(), b.below().toShortString());
            return List.of();
        }
        List<Set<BlockPos>> unitPositions = new ArrayList<>();
        for (int i = 0; i < centerline.size(); i++) {
            BlockPos support = centerline.get(i);
            boolean isSpiral = route.spiralSupports().contains(support);
            boolean nextIsSpiral = i + 1 < centerline.size()
                    && route.spiralSupports().contains(centerline.get(i + 1));
            // 螺旋入口外一格向上扩宽一格：普通单元接入三格净空的螺旋时，前驱也要三格净空，避免顶头。
            int headroom = (isSpiral || nextIsSpiral) ? 3 : 2;
            Set<BlockPos> positions = new LinkedHashSet<>();
            positions.add(support);
            for (int h = 1; h <= headroom; h++) positions.add(support.above(h));
            unitPositions.add(positions);
        }
        // 每条中心线边都必须有可走的体素过渡，首段和末段同样处理。
        for (int i = 1; i < centerline.size(); i++) {
            addEdgeClearance(centerline.get(i - 1), centerline.get(i),
                    unitPositions.get(i - 1), unitPositions.get(i));
        }
        List<Unit> result = new ArrayList<>();
        for (int i = 0; i < centerline.size(); i++) {
            BlockPos support = centerline.get(i);
            List<Cell> cells = new ArrayList<>();
            for (BlockPos pos : unitPositions.get(i)) {
                CellKind kind = pos.equals(support)
                        ? (level.getBlockState(pos).isAir() ? CellKind.SUPPORT_PLACE : CellKind.OPEN)
                        : (level.getBlockState(pos).isAir() ? CellKind.OPEN : CellKind.CLEAR);
                cells.add(new Cell(pos.immutable(), kind));
            }
            UnitKind kind = route.spiralSupports().contains(support) ? UnitKind.SPIRAL : UnitKind.NORMAL;
            boolean nextIsSpiral = i + 1 < centerline.size()
                    && route.spiralSupports().contains(centerline.get(i + 1));
            int headroom = (kind == UnitKind.SPIRAL || nextIsSpiral) ? 3 : 2;
            result.add(new Unit(support.immutable(), kind, headroom, Collections.unmodifiableList(cells)));
        }
        if (!validateRoute(level, centerline, unitPositions, route.spiralSupports(), route.spiralFootprint())) {
            BotLog.warn("道路蓝图生成失败: 单元连通验证拒绝 routeUnits={}", centerline.size());
            return List.of();
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 从连续曲线生成的候选中选取首条通过现有体素硬约束的路线。
     * 曲线层只确定整体形状；障碍、液体净距、对角侧格和高度连续性仍由此处验证。
     */
    private static List<BlockPos> selectContinuousRoute(ServerLevel level, BlockPos start, BlockPos goal) {
        List<ContinuousRoadCurve.Candidate> candidates = ContinuousRoadCurve.candidates(start, goal);
        BotLog.info("连续道路候选生成: count={} start={} goal={}", candidates.size(),
                start.toShortString(), goal.toShortString());
        List<BlockPos> bestRoute = List.of();
        double bestLength = Double.POSITIVE_INFINITY;
        int bestIndex = -1;
        int index = 0;
        for (ContinuousRoadCurve.Candidate candidate : candidates) {
            index++;
            List<BlockPos> route = candidate.route();
            boolean valid = isDiscreteRouteValid(level, route, 2);
            double length = routeLength(route);
            BotLog.info("连续道路候选 {}/{}: offset={} arc={} gridLength={} valid={}", index,
                    candidates.size(), format(candidate.lateralOffset()), format(candidate.horizontalArcLength()),
                    format(length), valid);
            if (valid && length < bestLength) {
                bestRoute = route;
                bestLength = length;
                bestIndex = index;
            }
        }
        if (bestIndex >= 0) {
            BotLog.info("连续道路几何最短路线选择: candidate={} gridLength={} start={} goal={}",
                    bestIndex, format(bestLength), start.toShortString(), goal.toShortString());
        }
        return bestRoute;
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static boolean isDiscreteRouteValid(ServerLevel level, List<BlockPos> route, int headroom) {
        if (route.isEmpty()) return false;
        Set<String> projections = new java.util.HashSet<>();
        for (int i = 0; i < route.size(); i++) {
            BlockPos point = route.get(i);
            if (!projections.add(point.getX() + ":" + point.getZ())
                    || RoadObstaclePolicy.forbidsCorridor(level, point, headroom)) {
                return false;
            }
            if (i == 0) continue;
            BlockPos previous = route.get(i - 1);
            int dx = Math.abs(point.getX() - previous.getX());
            int dz = Math.abs(point.getZ() - previous.getZ());
            int dy = Math.abs(point.getY() - previous.getY());
            if (dy > 1 || (dx == 0 && dz == 0) || dx > 1 || dz > 1) return false;
            if (dx == 1 && dz == 1) {
                BlockPos sideA = new BlockPos(point.getX(), previous.getY(), previous.getZ());
                BlockPos sideB = new BlockPos(previous.getX(), previous.getY(), point.getZ());
                if (forbidden(level, sideA) || forbidden(level, sideB)) return false;
            }
        }
        return true;
    }

    private static void addEdgeClearance(BlockPos from, BlockPos to,
                                         Set<BlockPos> fromPositions, Set<BlockPos> toPositions) {
        int dx = Integer.compare(to.getX(), from.getX());
        int dz = Integer.compare(to.getZ(), from.getZ());
        int dy = Integer.compare(to.getY(), from.getY());
        if (dx == 0 && dz == 0) return;
        if (dx != 0 && dz != 0) {
            // 对角连接桥：两侧正交单元各补两格净空，避免只在角点相接。
            for (int h = 1; h <= 2; h++) {
                fromPositions.add(from.offset(dx, 0, 0).above(h));
                fromPositions.add(from.offset(0, 0, dz).above(h));
                toPositions.add(to.offset(-dx, 0, 0).above(h));
                toPositions.add(to.offset(0, 0, -dz).above(h));
            }
        }
        if (dy != 0) {
            BlockPos low = dy > 0 ? from : to;
            Set<BlockPos> target = dy > 0 ? fromPositions : toPositions;
            for (int h = 2; h <= 3; h++) target.add(low.above(h));
        }
    }

    private static boolean validateRoute(ServerLevel level, List<BlockPos> route,
                                         List<Set<BlockPos>> unitPositions, Set<BlockPos> spiralSupports,
                                         Set<BlockPos> spiralFootprint) {
        for (int i = 0; i < route.size(); i++) {
            BlockPos p = route.get(i);
            boolean nextIsSpiral = i + 1 < route.size() && spiralSupports.contains(route.get(i + 1));
            int headroom = (spiralSupports.contains(p) || nextIsSpiral) ? 3 : 2;
            if (RoadObstaclePolicy.forbidsCorridor(level, p, headroom)) return false;
            if (i == 0) continue;
            BlockPos q = route.get(i - 1);
            int dx = Math.abs(p.getX() - q.getX());
            int dz = Math.abs(p.getZ() - q.getZ());
            int dy = Math.abs(p.getY() - q.getY());
            if (dy > 1 || (dx == 0 && dz == 0) || dx > 1 || dz > 1) return false;
            if (dx == 1 && dz == 1) {
                BlockPos sideA = new BlockPos(p.getX(), q.getY(), q.getZ());
                BlockPos sideB = new BlockPos(q.getX(), q.getY(), p.getZ());
                if (forbidden(level, sideA) || forbidden(level, sideB)) return false;
                // addEdgeClearance 已经生成两侧桥接净空；这里仅验证几何侧格和液体禁区，
                // 不再按前/后单元集合归属拒绝合法的短路径。
            }
            if (dy == 1) {
                // 高差上限由中心线搜索和本方法的 dy <= 1 检查保证；低侧补高由
                // addEdgeClearance 负责生成，不把集合归属当成搜索失败条件。
            }
        }
        return true;
    }

    private record SearchNode(BlockPos pos, int direction, double cost, double score) {}

    /**
     * 短水平、大高度差时的固定 2×2 螺旋补偿。
     * 从目标前一格水平缓冲反向定位出口；螺旋每步均有水平位移且高度变化一格，
     * 其上方使用三格净空验证。
     */
    private static Route spiralCompensationRoute(ServerLevel level, BlockPos start, BlockPos goal) {
        int signY = Integer.compare(goal.getY(), start.getY());
        int vertical = Math.abs(goal.getY() - start.getY());
        if (signY == 0) return new Route(shortestVoxelRoute(level, start, goal, 2), Set.of(), Set.of());
        // 末端预留一格水平缓冲；从四个方向枚举 2×2 螺旋出口，避免侵入目标下方支撑格。
        int[][] exits = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int[] exitDir : exits) {
            BlockPos buffer = goal.offset(exitDir[0], 0, exitDir[1]);
            if (RoadObstaclePolicy.forbidsCorridor(level, buffer, 2)) continue;
            // 令螺旋最后一步落到 buffer 前的相邻格，再由普通水平缓冲走入目标下方支撑。
            BlockPos spiralExit = buffer.offset(exitDir[0], 0, exitDir[1]);
            // 螺旋按完整四步一圈生成；多出的高度由入口普通坡道消化。
            int steps = Math.max(4, ((vertical + 3) / 4) * 4);
            int[][] cycle = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
            for (int phase = 0; phase < 4; phase++) {
                int sx = spiralExit.getX(), sz = spiralExit.getZ();
                for (int i = 0; i < steps; i++) {
                    int[] reverse = cycle[(phase + steps - 1 - i + 8) % 4];
                    sx -= reverse[0];
                    sz -= reverse[1];
                }
                BlockPos spiralStart = new BlockPos(sx, goal.getY() - signY * steps, sz);
                List<BlockPos> approach = shortestVoxelRoute(level, start, spiralStart, 2);
                if (approach.isEmpty()) continue;
                List<BlockPos> route = new ArrayList<>(approach);
                Set<BlockPos> spiral = new LinkedHashSet<>();
                spiral.add(spiralStart);
                BlockPos current = spiralStart;
                boolean valid = true;
                for (int step = 0; step < steps; step++) {
                    int[] move = cycle[(phase + step) % 4];
                    current = current.offset(move[0], signY, move[1]);
                    if (RoadObstaclePolicy.forbidsCorridor(level, current, 3)) { valid = false; break; }
                    route.add(current);
                    spiral.add(current);
                }
                if (!valid || !current.equals(spiralExit) || !validSpiralGeometry(spiral, signY)) continue;
                if (RoadObstaclePolicy.forbidsCorridor(level, goal, 2)) continue;
                route.add(buffer);
                route.add(goal);
                BotLog.info("螺旋路线生成: entrance={} exit={} buffer={} goalSupport={} steps={} phase={}",
                        spiralStart.toShortString(), spiralExit.toShortString(), buffer.toShortString(),
                        goal.toShortString(), steps, phase);
                Set<BlockPos> footprint = spiralFootprint(spiral);
                if (footprint.isEmpty()
                        || !terminalSegmentClear(level, footprint, buffer, goal)) continue;
                return new Route(route, Collections.unmodifiableSet(new LinkedHashSet<>(spiral)),
                        Collections.unmodifiableSet(footprint));
            }
        }
        return new Route(List.of(), Set.of(), Set.of());
    }

    private static Set<BlockPos> spiralFootprint(Set<BlockPos> supports) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        int baseY = Integer.MAX_VALUE;
        for (BlockPos support : supports) {
            minX = Math.min(minX, support.getX()); maxX = Math.max(maxX, support.getX());
            minZ = Math.min(minZ, support.getZ()); maxZ = Math.max(maxZ, support.getZ());
            baseY = Math.min(baseY, support.getY());
        }
        if (maxX - minX != 1 || maxZ - minZ != 1) return Set.of();
        Set<BlockPos> footprint = new LinkedHashSet<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                footprint.add(new BlockPos(x, baseY, z));
            }
        }
        return footprint;
    }

    private static boolean footprintContains(Set<BlockPos> footprint, BlockPos pos) {
        for (BlockPos fp : footprint) {
            if (fp.getX() == pos.getX() && fp.getZ() == pos.getZ()) return true;
        }
        return false;
    }

    private static boolean terminalSegmentClear(ServerLevel level, Set<BlockPos> footprint,
                                                BlockPos buffer, BlockPos goal) {
        // 末端连接只允许 buffer -> goal 的单格水平段，且不能穿过 2×2 螺旋主体。
        if (footprintContains(footprint, buffer) || footprintContains(footprint, goal)) return false;
        int dx = Math.abs(buffer.getX() - goal.getX());
        int dz = Math.abs(buffer.getZ() - goal.getZ());
        if (dx + dz != 1 || buffer.getY() != goal.getY()) return false;
        return !RoadObstaclePolicy.forbidsCorridor(level, buffer, 2)
                && !RoadObstaclePolicy.forbidsCorridor(level, goal, 2);
    }

    private static boolean validSpiralGeometry(Set<BlockPos> supports, int signY) {
        if (supports.size() < 5) return false;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        List<BlockPos> ordered = supports.stream().toList();
        for (BlockPos p : ordered) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        if (maxX - minX != 1 || maxZ - minZ != 1) return false;
        for (int i = 1; i < ordered.size(); i++) {
            BlockPos a = ordered.get(i - 1), b = ordered.get(i);
            int horizontal = Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ());
            if (horizontal != 1 || b.getY() - a.getY() != signY) return false;
        }
        return true;
    }

    /** 液体膨胀区外的 3D 体素最短路：4 邻水平、单步高差最多 1、轻微转弯惩罚。 */
    private static List<BlockPos> shortestVoxelRoute(ServerLevel level, BlockPos start, BlockPos goal, int headroom) {
        if (RoadObstaclePolicy.forbidsCorridor(level, start, headroom)
                || RoadObstaclePolicy.forbidsCorridor(level, goal, headroom)) return List.of();
        PriorityQueue<SearchNode> open = new PriorityQueue<>(java.util.Comparator.comparingDouble(SearchNode::score));
        Map<String, Double> best = new HashMap<>();
        int margin = 24;
        int minX = Math.min(start.getX(), goal.getX()) - margin;
        int maxX = Math.max(start.getX(), goal.getX()) + margin;
        int minY = Math.min(start.getY(), goal.getY()) - margin;
        int maxY = Math.max(start.getY(), goal.getY()) + margin;
        int minZ = Math.min(start.getZ(), goal.getZ()) - margin;
        int maxZ = Math.max(start.getZ(), goal.getZ()) + margin;
        Map<String, String> previous = new HashMap<>();
        SearchNode first = new SearchNode(start, 4, 0, heuristic(start, goal));
        open.add(first);
        best.put(key(start, 4), 0D);
        int expanded = 0;
        while (!open.isEmpty() && expanded++ < 50000) {
            SearchNode current = open.poll();
            if (current.pos().equals(goal)) return reconstruct(previous, key(current.pos(), current.direction()), start);
            if (current.cost() > best.getOrDefault(key(current.pos(), current.direction()), Double.MAX_VALUE)) continue;
            int[][] horizontalMoves = {
                    {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                    {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
            };
            for (int direction = 0; direction < horizontalMoves.length; direction++) {
                int dx = horizontalMoves[direction][0];
                int dz = horizontalMoves[direction][1];
                boolean diagonal = dx != 0 && dz != 0;
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos next = current.pos().offset(dx, dy, dz);
                    if (next.getX() < minX || next.getX() > maxX || next.getY() < minY
                            || next.getY() > maxY || next.getZ() < minZ || next.getZ() > maxZ) continue;
                    boolean sideBlocked = diagonal
                            && (forbidden(level, current.pos().offset(dx, 0, 0))
                            || forbidden(level, current.pos().offset(0, 0, dz))
                            || forbidden(level, next.offset(-dx, 0, 0))
                            || forbidden(level, next.offset(0, 0, -dz)));
                    // 高度变化必须绑定水平移动；此邻域永远有水平位移，不会形成竖井。
                    if (forbidden(level, next) || sideBlocked) continue;
                    double movementCost = diagonal ? 1.414D : 1.0D;
                    double nextCost = current.cost() + movementCost
                            + (dy != 0 ? HEIGHT_CHANGE_COST : 0D)
                            + (current.direction() != 4 && current.direction() != direction ? 0.08D : 0D);
                    String nextKey = key(next, direction);
                    if (nextCost >= best.getOrDefault(nextKey, Double.MAX_VALUE)) continue;
                    best.put(nextKey, nextCost);
                    previous.put(nextKey, key(current.pos(), current.direction()));
                    open.add(new SearchNode(next, direction, nextCost, nextCost + heuristic(next, goal)));
                }
            }
        }
        return List.of();
    }

    /** 数学线路的离散长度；只用于几何候选比较，不包含实际通道施工格成本。 */
    private static double geometricRouteCost(List<BlockPos> route) {
        return routeLength(route) + Math.max(0, route.size() - 1) * HEIGHT_CHANGE_COST;
    }

    private static double routeLength(List<BlockPos> route) {
        if (route.isEmpty()) return Double.POSITIVE_INFINITY;
        double length = 0.0D;
        for (int i = 1; i < route.size(); i++) {
            BlockPos previous = route.get(i - 1);
            BlockPos current = route.get(i);
            int dx = current.getX() - previous.getX();
            int dy = current.getY() - previous.getY();
            int dz = current.getZ() - previous.getZ();
            length += Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        return length;
    }

    private static boolean forbidden(ServerLevel level, BlockPos support) {
        return RoadObstaclePolicy.forbidsCorridor(level, support, 2);
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        int dx = Math.abs(a.getX() - b.getX());
        int dz = Math.abs(a.getZ() - b.getZ());
        int diagonal = Math.min(dx, dz);
        int straight = Math.max(dx, dz) - diagonal;
        return diagonal * 1.414D + straight + Math.abs(a.getY() - b.getY()) * 1.05D;
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
