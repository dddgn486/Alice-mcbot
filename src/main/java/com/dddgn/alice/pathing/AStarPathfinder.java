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

    private static final int MAX_NODES = 40_000;
    private static final int MAX_MOVES = 512;

    private AStarPathfinder() {
    }

    /**
     * 计算从 start 脚位到 goal 的路径(不含 start,含终点)。
     *
     * @return 路径脚位序列;找不到返回空列表
     */
    public static List<BlockPos> computePath(ServerLevel level, BlockPos start, Goal goal) {
        Map<BlockPos, PathNode> closed = new HashMap<>();
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
            expand(level, current, goal, closed, openIndex, openSet);
            if (closed.size() > MAX_NODES) {
                BotLog.warn("寻路中止: 节点超限 {}", closed.size());
                break;
            }
        }
        return List.of();
    }

    private static void expand(ServerLevel level, PathNode current, Goal goal,
                               Map<BlockPos, PathNode> closed,
                               Map<BlockPos, PathNode> openIndex, OpenSet openSet) {
        int x = current.pos.getX();
        int y = current.pos.getY();
        int z = current.pos.getZ();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockPos flat = new BlockPos(x + dx, y, z + dz);
                tryMove(level, current, goal, closed, openIndex, openSet, flat, MovementType.TRAVERSE);

                BlockPos ascend = new BlockPos(x + dx, y + 1, z + dz);
                tryMove(level, current, goal, closed, openIndex, openSet, ascend, MovementType.ASCEND);

                BlockPos descend = new BlockPos(x + dx, y - 1, z + dz);
                tryMove(level, current, goal, closed, openIndex, openSet, descend, MovementType.DESCEND);
            }
        }
        // 向下挖一格(自身脚下)
        BlockPos down = new BlockPos(x, y - 1, z);
        tryMove(level, current, goal, closed, openIndex, openSet, down, MovementType.DOWNWARD);
    }

    private static void tryMove(ServerLevel level, PathNode current, Goal goal,
                                Map<BlockPos, PathNode> closed,
                                Map<BlockPos, PathNode> openIndex, OpenSet openSet,
                                BlockPos to, MovementType type) {
        if (closed.containsKey(to)) {
            return;
        }
        boolean valid = switch (type) {
            case TRAVERSE -> MovementHelper.canTraverse(level, current.pos, to);
            case ASCEND -> MovementHelper.canAscend(level, current.pos, to);
            case DESCEND -> MovementHelper.canDescend(level, current.pos, to);
            case DOWNWARD -> MovementHelper.canDescend(level, current.pos, to)
                    && !level.getBlockState(current.pos.below()).isAir();
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
