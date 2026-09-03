package com.dddgn.alice.pathing;

import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

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

    /** 
     * 统一搜索硬上限。
     * Phase 3.1: 改用时间预算替代空间边界（参考 Baritone）。
     */
    private static final int MAX_NODES = 50_000;  // 提高节点上限，支持远距离寻路
    private static final int MAX_MOVES = 1024;    // 提高移动步数上限
    private static final long DEFAULT_SEARCH_TIME_MS = 100;  // 默认时间预算（100ms）

    private AStarPathfinder() {
    }

    /**
     * 计算从 start 脚位到 goal 的路径(不含 start,含终点)。
     *
     * @return 路径脚位序列;找不到返回空列表
     */
    public enum SearchStatus { REACHED, UNREACHABLE, SEARCH_LIMIT }

    public record SearchResult(SearchStatus status, List<BlockPos> path, int expandedNodes, double totalCost) {
        public SearchResult {
            path = List.copyOf(path);
        }

        public boolean reachable() {
            return status == SearchStatus.REACHED;
        }
    }

    /**
     * Movement 搜索结果（Phase 2B）。
     */
    public record MovementSearchResult(SearchStatus status, 
                                       List<com.dddgn.alice.pathing.movement.Movement> movements,
                                       int expandedNodes, 
                                       double totalCost) {
        public MovementSearchResult {
            movements = List.copyOf(movements);
        }

        public boolean reachable() {
            return status == SearchStatus.REACHED;
        }
    }

    public static List<BlockPos> computePath(ServerPlayer bot, BlockPos start, Goal goal) {
        return computeDetailed(bot, start, goal).path();
    }

    /**
     * 计算 Movement 路径（Phase 2B）。
     * 
     * @param bot Bot 玩家
     * @param start 起点
     * @param goal 目标
     * @return Movement 列表（从起点到终点）
     */
    public static List<com.dddgn.alice.pathing.movement.Movement> computeMovementPath(
            ServerPlayer bot, BlockPos start, Goal goal) {
        MovementSearchResult result = computeMovementDetailed(bot, start, goal);
        return result.movements();
    }

    /**
     * 旧接口：用于诊断命令和测试。
     * 注意：不使用 Movement 系统，使用旧的判定逻辑。
     * 
     * @deprecated 使用 {@link #computePath(ServerPlayer, BlockPos, Goal)} 代替
     */
    @Deprecated
    public static List<BlockPos> computePath(ServerLevel level, BlockPos start, Goal goal) {
        return computeDetailedLegacy(level, start, goal).path();
    }

    public static SearchResult computeDetailed(ServerPlayer bot, BlockPos start, Goal goal) {
        return computeDetailed(bot, start, goal, DEFAULT_SEARCH_TIME_MS);
    }

    /**
     * 计算路径（带时间预算）。
     * 
     * @param bot Bot 玩家
     * @param start 起点
     * @param goal 目标
     * @param timeoutMs 时间预算（毫秒）
     * @return 搜索结果
     */
    public static SearchResult computeDetailed(ServerPlayer bot, BlockPos start, Goal goal, long timeoutMs) {
        ServerLevel level = (ServerLevel) bot.level();
        Map<BlockPos, PathNode> closed = new HashMap<>();
        Map<BlockPos, PathNode> openIndex = new HashMap<>();
        OpenSet openSet = new OpenSet();
        boolean moveLimitReached = false;
        long startTime = System.currentTimeMillis();

        PathNode startNode = new PathNode(start);
        startNode.cost = 0;
        // 当前成本模型将直走和对角均定为 1，曼哈顿 heuristic 会高估对角路线。
        // 使用零 heuristic 的 Dijkstra 顺序，优先证明最短成本而非追求搜索速度。
        startNode.combinedCost = 0;
        openSet.insert(startNode);
        openIndex.put(startNode.pos, startNode);

        while (!openSet.isEmpty()) {
            // 检查时间预算
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                BotLog.warn("寻路超时: {}ms, 已扩展节点={}", timeoutMs, closed.size());
                return new SearchResult(SearchStatus.SEARCH_LIMIT, List.of(), closed.size(), Double.POSITIVE_INFINITY);
            }

            PathNode current = openSet.removeBest();
            openIndex.remove(current.pos);
            if (goal.isInGoal(current.pos)) {
                return new SearchResult(SearchStatus.REACHED, reconstruct(current), closed.size(), current.cost);
            }
            closed.put(current.pos, current);
            if (current.moves >= MAX_MOVES) {
                moveLimitReached = true;
                continue;
            }
            expand(bot, level, current, goal, closed, openIndex, openSet);
            if (closed.size() > MAX_NODES) {
                BotLog.warn("寻路中止: 节点超限 {}", closed.size());
                break;
            }
        }
        SearchStatus status = closed.size() > MAX_NODES || moveLimitReached
                ? SearchStatus.SEARCH_LIMIT : SearchStatus.UNREACHABLE;
        return new SearchResult(status, List.of(), closed.size(), Double.POSITIVE_INFINITY);
    }

    /**
     * 计算 Movement 路径（详细结果，Phase 2B）。
     * 
     * @param bot Bot 玩家
     * @param start 起点
     * @param goal 目标
     * @return MovementSearchResult
     */
    public static MovementSearchResult computeMovementDetailed(ServerPlayer bot, BlockPos start, Goal goal) {
        return computeMovementDetailed(bot, start, goal, DEFAULT_SEARCH_TIME_MS);
    }

    /**
     * 计算 Movement 路径（带时间预算）。
     * 
     * @param bot Bot 玩家
     * @param start 起点
     * @param goal 目标
     * @param timeoutMs 时间预算（毫秒）
     * @return MovementSearchResult
     */
    public static MovementSearchResult computeMovementDetailed(ServerPlayer bot, BlockPos start, Goal goal, long timeoutMs) {
        ServerLevel level = (ServerLevel) bot.level();
        Map<BlockPos, PathNode> closed = new HashMap<>();
        Map<BlockPos, PathNode> openIndex = new HashMap<>();
        OpenSet openSet = new OpenSet();
        boolean moveLimitReached = false;
        long startTime = System.currentTimeMillis();

        PathNode startNode = new PathNode(start);
        startNode.cost = 0;
        startNode.combinedCost = 0;
        openSet.insert(startNode);
        openIndex.put(startNode.pos, startNode);

        while (!openSet.isEmpty()) {
            // 检查时间预算
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                BotLog.warn("Movement寻路超时: {}ms, 已扩展节点={}", timeoutMs, closed.size());
                return new MovementSearchResult(SearchStatus.SEARCH_LIMIT, List.of(), closed.size(), Double.POSITIVE_INFINITY);
            }

            PathNode current = openSet.removeBest();
            openIndex.remove(current.pos);
            if (goal.isInGoal(current.pos)) {
                return new MovementSearchResult(SearchStatus.REACHED, reconstructMovements(current), 
                        closed.size(), current.cost);
            }
            closed.put(current.pos, current);
            if (current.moves >= MAX_MOVES) {
                moveLimitReached = true;
                continue;
            }
            expand(bot, level, current, goal, closed, openIndex, openSet);
            if (closed.size() > MAX_NODES) {
                BotLog.warn("Movement寻路中止: 节点超限 {}", closed.size());
                break;
            }
        }
        SearchStatus status = closed.size() > MAX_NODES || moveLimitReached
                ? SearchStatus.SEARCH_LIMIT : SearchStatus.UNREACHABLE;
        return new MovementSearchResult(status, List.of(), closed.size(), Double.POSITIVE_INFINITY);
    }

    private static void expand(ServerPlayer bot, ServerLevel level, PathNode current, Goal goal,
                               Map<BlockPos, PathNode> closed,
                               Map<BlockPos, PathNode> openIndex, OpenSet openSet) {
        // Phase 3.1: 移除空间边界检查，改用时间预算控制搜索范围

        // Phase 2A: 使用 Movement 系统枚举可能的移动
        var movements = com.dddgn.alice.pathing.movement.MovementHelper.getPossibleMovements(
                bot, current.pos, level);
        
        for (var move : movements) {
            if (move == null) continue;
            
            BlockPos to = move.to();
            
            // Phase 3.1: 移除边界检查，只保留世界高度限制
            if (to.getY() < level.getMinBuildHeight() || to.getY() >= level.getMaxBuildHeight()) {
                continue;
            }
            
            // Movement.create() 已经做了 isValid() 检查，所以这里的 Movement 一定是有效的
            double moveCost = move.cost();
            double newCost = current.cost + moveCost;
            
            // 计算方向（用于转弯惩罚）
            int directionX = Integer.signum(to.getX() - current.pos.getX());
            int directionZ = Integer.signum(to.getZ() - current.pos.getZ());
            int newTurns = current.moves == 0 || (current.directionX == directionX && current.directionZ == directionZ)
                    ? current.turns : current.turns + 1;
            
            // 检查是否已在 open 集合中
            PathNode existing = openIndex.get(to);
            if (existing == null) {
                // 检查是否之前已经访问过（在 closed 集合中）
                PathNode previousBest = closed.get(to);
                if (previousBest != null && (newCost > previousBest.cost
                        || (newCost == previousBest.cost && newTurns >= previousBest.turns))) {
                    continue;
                }
                // 重开节点（如果之前在 closed 中）
                closed.remove(to);
                PathNode node = previousBest == null ? new PathNode(to) : previousBest;
                node.cost = newCost;
                node.combinedCost = newCost;
                node.previous = current;
                node.movementToHere = move;  // 🌟 存储 Movement
                node.moves = current.moves + 1;
                node.turns = newTurns;
                node.directionX = directionX;
                node.directionZ = directionZ;
                openSet.insert(node);
                openIndex.put(to, node);
            } else if (newCost < existing.cost
                    || (newCost == existing.cost && newTurns < existing.turns)) {
                // 更新现有节点
                existing.cost = newCost;
                existing.combinedCost = newCost;
                existing.previous = current;
                existing.movementToHere = move;  // 🌟 存储 Movement
                existing.moves = current.moves + 1;
                existing.turns = newTurns;
                existing.directionX = directionX;
                existing.directionZ = directionZ;
                openSet.update(existing);
            }
        }
    }

    /**
     * 旧版 tryMove：用于 expandLegacy。
     * 
     * @deprecated 新代码使用 Movement 系统，不需要此方法
     */
    @Deprecated
    private static void tryMove(ServerLevel level, PathNode current, Goal goal,
                                Map<BlockPos, PathNode> closed,
                                Map<BlockPos, PathNode> openIndex, OpenSet openSet,
                                BlockPos to, MovementType type,
                                int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        if (to.getX() < minX || to.getX() > maxX || to.getY() < minY || to.getY() > maxY
                || to.getZ() < minZ || to.getZ() > maxZ) {
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
        int directionX = Integer.signum(to.getX() - current.pos.getX());
        int directionZ = Integer.signum(to.getZ() - current.pos.getZ());
        int newTurns = current.moves == 0 || (current.directionX == directionX && current.directionZ == directionZ)
                ? current.turns : current.turns + 1;
        PathNode existing = openIndex.get(to);
        if (existing == null) {
            PathNode previousBest = closed.get(to);
            if (previousBest != null && (newCost > previousBest.cost
                    || (newCost == previousBest.cost && newTurns >= previousBest.turns))) {
                return;
            }
            // 即使未来更换为一致 heuristic，也保留重开逻辑；成本模型变化不会静默破坏最优性。
            closed.remove(to);
            PathNode node = previousBest == null ? new PathNode(to) : previousBest;
            node.cost = newCost;
            node.combinedCost = newCost;
            node.previous = current;
            node.moves = current.moves + 1;
            node.turns = newTurns;
            node.directionX = directionX;
            node.directionZ = directionZ;
            openSet.insert(node);
            openIndex.put(to, node);
        } else if (newCost < existing.cost
                || (newCost == existing.cost && newTurns < existing.turns)) {
            existing.cost = newCost;
            existing.combinedCost = newCost;
            existing.previous = current;
            existing.moves = current.moves + 1;
            existing.turns = newTurns;
            existing.directionX = directionX;
            existing.directionZ = directionZ;
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

    /**
     * 重建 Movement 路径（Phase 2B）。
     * 
     * @param end 目标节点
     * @return Movement 列表（从起点到终点）
     */
    private static List<com.dddgn.alice.pathing.movement.Movement> reconstructMovements(PathNode end) {
        List<com.dddgn.alice.pathing.movement.Movement> movements = new ArrayList<>();
        for (PathNode node = end; node.previous != null; node = node.previous) {
            if (node.movementToHere != null) {
                movements.add(0, node.movementToHere);
            }
        }
        return movements;
    }

    /**
     * 旧版 expand：使用 MovementHelper 的旧判定逻辑（不使用 Movement 系统）。
     * 用于诊断命令和测试。
     * 
     * @deprecated 仅供 computeDetailedLegacy 使用
     */
    @Deprecated
    private static void expandLegacy(ServerLevel level, PathNode current, Goal goal,
                               Map<BlockPos, PathNode> closed,
                               Map<BlockPos, PathNode> openIndex, OpenSet openSet,
                               int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        int x = current.pos.getX();
        int y = current.pos.getY();
        int z = current.pos.getZ();
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
        BlockPos down = new BlockPos(x, y - 1, z);
        if (level.getBlockState(down).isAir()) {
            tryMove(level, current, goal, closed, openIndex, openSet, down, MovementType.DOWNWARD,
                    minX, maxX, minY, maxY, minZ, maxZ);
        }
    }

    /**
     * 旧版 computeDetailed：使用旧的 expand 逻辑和空间边界。
     * 用于诊断命令和测试。
     * 
     * @deprecated 使用 {@link #computeDetailed(ServerPlayer, BlockPos, Goal)} 代替
     */
    @Deprecated
    public static SearchResult computeDetailedLegacy(ServerLevel level, BlockPos start, Goal goal) {
        // Legacy 方法保留旧的边界逻辑
        final int LEGACY_SEARCH_MARGIN = 12;
        
        Map<BlockPos, PathNode> closed = new HashMap<>();
        BlockPos goalHint = goal instanceof Goal.GoalBlock block ? block.pos()
                : goal instanceof Goal.GoalNear near ? near.pos() : start;
        int minX = Math.min(start.getX(), goalHint.getX()) - LEGACY_SEARCH_MARGIN;
        int maxX = Math.max(start.getX(), goalHint.getX()) + LEGACY_SEARCH_MARGIN;
        int minY = Math.max(level.getMinBuildHeight(), Math.min(start.getY(), goalHint.getY()) - LEGACY_SEARCH_MARGIN);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, Math.max(start.getY(), goalHint.getY()) + LEGACY_SEARCH_MARGIN);
        int minZ = Math.min(start.getZ(), goalHint.getZ()) - LEGACY_SEARCH_MARGIN;
        int maxZ = Math.max(start.getZ(), goalHint.getZ()) + LEGACY_SEARCH_MARGIN;
        Map<BlockPos, PathNode> openIndex = new HashMap<>();
        OpenSet openSet = new OpenSet();
        boolean moveLimitReached = false;

        PathNode startNode = new PathNode(start);
        startNode.cost = 0;
        startNode.combinedCost = 0;
        openSet.insert(startNode);
        openIndex.put(startNode.pos, startNode);

        while (!openSet.isEmpty()) {
            PathNode current = openSet.removeBest();
            openIndex.remove(current.pos);
            if (goal.isInGoal(current.pos)) {
                return new SearchResult(SearchStatus.REACHED, reconstruct(current), closed.size(), current.cost);
            }
            closed.put(current.pos, current);
            if (current.moves >= MAX_MOVES) {
                moveLimitReached = true;
                continue;
            }
            expandLegacy(level, current, goal, closed, openIndex, openSet,
                    minX, maxX, minY, maxY, minZ, maxZ);
            if (closed.size() > MAX_NODES) {
                BotLog.warn("寻路中止: 节点超限 {}", closed.size());
                break;
            }
        }
        SearchStatus status = closed.size() > MAX_NODES || moveLimitReached
                ? SearchStatus.SEARCH_LIMIT : SearchStatus.UNREACHABLE;
        return new SearchResult(status, List.of(), closed.size(), Double.POSITIVE_INFINITY);
    }
}
