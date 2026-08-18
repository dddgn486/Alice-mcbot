package com.dddgn.alice.pathing;

import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A* 寻路器(移植自 Baritone AStarPathFinder 的核心搜索框架,服务端直读版)。
 * <p>
 * 与 Baritone 的差异:不引入其类依赖(CalculationContext/Favoring/BetterWorldBorder 等),
 * 直接用 {@link ServerLevel} + {@link MovementHelper} 判定;性能对 M 阶段足够
 * (节点数万级,毫秒级)。</p>
 */
public final class AStarPathfinder {

    /** 统一搜索硬上限；局部边界优先防止近距离不可达目标扩展整张世界。 */
    private static final int MAX_NODES = 12_000;
    private static final int MAX_MOVES = 256;
    private static final int SEARCH_MARGIN = 12;

    private AStarPathfinder() {
    }

    /**
     * 计算从 start 脚位到 goal 的路径(不含 start,含终点)。
     *
     * @return 路径脚位序列;找不到返回空列表
     */
    public static List<BlockPos> computePath(ServerLevel level, BlockPos start, Goal goal) {
        Map<BlockPos, PathNode> closed = new HashMap<>();
        BlockPos goalHint = goal instanceof Goal.GoalBlock block ? block.pos()
                : goal instanceof Goal.GoalNear near ? near.pos() : start;
        int minX = Math.min(start.getX(), goalHint.getX()) - SEARCH_MARGIN;
        int maxX = Math.max(start.getX(), goalHint.getX()) + SEARCH_MARGIN;
        int minY = Math.max(level.getMinBuildHeight(), Math.min(start.getY(), goalHint.getY()) - SEARCH_MARGIN);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, Math.max(start.getY(), goalHint.getY()) + SEARCH_MARGIN);
        int minZ = Math.min(start.getZ(), goalHint.getZ()) - SEARCH_MARGIN;
        int maxZ = Math.max(start.getZ(), goalHint.getZ()) + SEARCH_MARGIN;
        Map<BlockPos, PathNode> openIndex = new HashMap<>();
        OpenSet openSet = new OpenSet();

        PathNode startNode = new PathNode(start);
        startNode.cost = 0;
        startNode.combinedCost = goal.heuristic(start);
        openSet.insert(startNode);
        openIndex.put(startNode.pos, startNode);

        while (!openSet.isEmpty()) {
            PathNode current = openSet.removeBest();
            openIndex.remove(current.pos);
            if (goal.isInGoal(current.pos)) {
                return reconstruct(current);
            }
            closed.put(current.pos, current);
            if (current.moves >= MAX_MOVES) {
                continue;
            }
            expand(level, current, goal, closed, openIndex, openSet,
                    minX, maxX, minY, maxY, minZ, maxZ);
            if (closed.size() > MAX_NODES) {
                BotLog.warn("寻路中止: 节点超限 {}", closed.size());
                break;
            }
        }
        return List.of();
    }

    private static void expand(ServerLevel level, PathNode current, Goal goal,
                               Map<BlockPos, PathNode> closed,
                               Map<BlockPos, PathNode> openIndex, OpenSet openSet,
                               int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        int x = current.pos.getX();
        int y = current.pos.getY();
        int z = current.pos.getZ();
        // 当前临时 A* 必须局限在起终点附近；成熟 break-aware planner 接管后由毫秒预算替代。
        if (x <= minX || x >= maxX || y <= minY || y >= maxY || z <= minZ || z >= maxZ) {
            return;
        }

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockPos flat = new BlockPos(x + dx, y, z + dz);
                tryMove(level, current, goal, closed, openIndex, openSet, flat, MovementType.TRAVERSE,
                        minX, maxX, minY, maxY, minZ, maxZ);

                BlockPos ascend = new BlockPos(x + dx, y + 1, z + dz);
                tryMove(level, current, goal, closed, openIndex, openSet, ascend, MovementType.ASCEND,
                        minX, maxX, minY, maxY, minZ, maxZ);

                BlockPos descend = new BlockPos(x + dx, y - 1, z + dz);
                tryMove(level, current, goal, closed, openIndex, openSet, descend, MovementType.DESCEND,
                        minX, maxX, minY, maxY, minZ, maxZ);
            }
        }
        // 向下挖一格(自身脚下)
        // 临时寻路器不允许自行挖脚下；正式 break-aware planner 接管后仍保持 allowDownward=false。
        // 这里只保留已有支撑的单格 DESCEND，由侧向阶梯/已开洞提供入口。
        BlockPos down = new BlockPos(x, y - 1, z);
        if (level.getBlockState(down).isAir()) {
            tryMove(level, current, goal, closed, openIndex, openSet, down, MovementType.DOWNWARD,
                    minX, maxX, minY, maxY, minZ, maxZ);
        }
    }

    private static void tryMove(ServerLevel level, PathNode current, Goal goal,
                                Map<BlockPos, PathNode> closed,
                                Map<BlockPos, PathNode> openIndex, OpenSet openSet,
                                BlockPos to, MovementType type,
                                int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        if (to.getX() < minX || to.getX() > maxX || to.getY() < minY || to.getY() > maxY
                || to.getZ() < minZ || to.getZ() > maxZ || closed.containsKey(to)) {
            return;
        }
        boolean valid = switch (type) {
            case TRAVERSE -> MovementHelper.canTraverse(level, current.pos, to);
            case ASCEND -> MovementHelper.canAscend(level, current.pos, to);
            case DESCEND -> MovementHelper.canDescend(level, current.pos, to);
            case DOWNWARD -> MovementHelper.canDescend(level, current.pos, to);
        };
        if (!valid) {
            return;
        }
        double newCost = current.cost + MovementHelper.cost(type);
        PathNode existing = openIndex.get(to);
        if (existing == null) {
            PathNode node = new PathNode(to);
            node.cost = newCost;
            node.combinedCost = newCost + goal.heuristic(to);
            node.previous = current;
            node.moves = current.moves + 1;
            openSet.insert(node);
            openIndex.put(to, node);
        } else if (newCost < existing.cost) {
            existing.cost = newCost;
            existing.combinedCost = newCost + goal.heuristic(to);
            existing.previous = current;
            existing.moves = current.moves + 1;
            openSet.update(existing);
        }
    }

    private static List<BlockPos> reconstruct(PathNode end) {
        List<BlockPos> path = new ArrayList<>();
        for (PathNode node = end; node.previous != null; node = node.previous) {
            path.add(0, node.pos);
        }
        return path;
    }
}
