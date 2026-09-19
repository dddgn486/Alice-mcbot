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

    /**
     * **该落点所在区块是否已加载**（S-2 / P1-A，2026-09-12）。
     *
     * <p>用 {@code hasChunkAt}（等价于 `getChunk(..., FULL, **false**)`）**绝不加载区块** ——
     * 对照 Baritone `BlockStateInterface.worldContainsLoadedChunk`。服务端在未加载区块上读方块会
     * **同步加载/生成区块并阻塞主线程**，所以搜索层必须先问这个，再决定要不要看那一格。
     */
    public boolean chunkLoaded(BlockPos pos) {
        return level.hasChunkAt(pos);
    }

    /**
     * **读脚印半径**（`D-337` 附注一）：一次节点扩展可能读到的方块，最远偏离节点脚位**几格**（水平）。
     *
     * <p>为什么要这个数：候选生成/成本计算会读节点周围几格，而这些读**跨过区块边界**时会触发
     * `getChunkAt` 同步加载（红线 `D-132` 禁止）。既然"读的半径"是有限且可枚举的，就把它写下来，
     * 让搜索在**扩展之前**就能判断"这一扩展会不会读到未加载区块"。
     *
     * <p>代码级枚举（当前最大值 = **2**，2026-09-19 查证）：
     * <ul>
     *   <li>`SurfaceMovementProvider`：`from.offset(dx,dy,dz)`（`dx,dz∈{-1,0,1}`）、`to.below()/above()`
     *       以及 `canSweepPlayer` 的 AABB（±0.3 半宽，`floor` 后仍在 `to` 列内）⇒ **≤1**；</li>
     *   <li>`appendDescend` 的过冲列 `beyond = to.offset(dx,0,dz)` + `beyond.below(2)` ⇒ **≤2**；</li>
     *   <li>{@link #hazardAdjacencyPenalty} 的 `to.relative(dir)` / `to.above().relative(dir)` ⇒ **≤2**；</li>
     *   <li>`level.getFluidState(to/to.above())`（`SurfaceMovementProvider:132,234`）⇒ **≤1**。</li>
     * </ul>
     * 取 **3** = 实测最大 + **1 格余量**：保守（宁可少走一格，也不许读到未加载区块）。
     * ⚠️ 谁往扩展路径里加了"读得更远"的判定，**必须同步改这个常量** —— 否则红线会以静默方式回归。
     */
    public static final int READ_FOOTPRINT_RADIUS = 3;

    /**
     * **扩展该节点所需的"读脚印"是否完整落在已加载区块内**（`D-337`：让"读"本身成为屏障）。
     *
     * <p>只问 {@link #chunkLoaded}（= `hasChunkAt`，**绝不加载**）⇒ 本方法自身零副作用。
     * 语义：`false` ⇒ 该节点**不扩展**（保守地当"到边界为止"），而不是"读读看会怎样"。
     *
     * <p>为什么不用"逐格读前都问一次"：读点分散在 provider / `MovementHelper` / 成本模型里，
     * 逐个补检查既漏得掉（今天就是这么漏的）又慢；按**区块粒度**在扩展前一次性判定，
     * 一个节点最多 4 次 `hasChunkAt`（同区块时提前返回 ⇒ 常数极小）。
     */
    public boolean readFootprintLoaded(BlockPos foot) {
        int minCX = (foot.getX() - READ_FOOTPRINT_RADIUS) >> 4;
        int maxCX = (foot.getX() + READ_FOOTPRINT_RADIUS) >> 4;
        int minCZ = (foot.getZ() - READ_FOOTPRINT_RADIUS) >> 4;
        int maxCZ = (foot.getZ() + READ_FOOTPRINT_RADIUS) >> 4;
        if (minCX == maxCX && minCZ == maxCZ) {
            // 脚印全在**本节点自己那一列区块**里；节点能进 openSet 就说明它已加载（不变式，见搜索里的边闸门）
            return true;
        }
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                if (!chunkLoaded(new BlockPos(cx << 4, foot.getY(), cz << 4))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 是否在**世界边界**内（对照 Baritone `AStarPathFinder` 的 `worldBorder.entirelyContains`）。 */
    public boolean withinWorldBorder(BlockPos pos) {
        return level.getWorldBorder().isWithinBounds(pos);
    }

    /** **邻接危险方块的加价**（D-292）：够大以至于规划器倾向绕开，但不至于把路封死 ✗（≈20 格走路）。 */
    public static final double HAZARD_ADJACENCY_PENALTY = 20.0D;
    public double cost(MovementType type, BlockPos from, BlockPos to) {
        return costModel.cost(type, level, from, to) + hazardAdjacencyPenalty(to);
    }

    /**
     * **危险地带厌恶的消费点（D-292，2026-09-17 用户裁定"先加 hazardTolerance"）**。
     *
     * <p>口径：落点**自己**是危险方块由既有安全层管 ✗（不在这里重复）；这里只管"**贴着**危险走"这件事 ——
     * 脚位/头位/支撑的四个水平邻格里有危险方块 ⇒ 加价 ✓（**只是不划算，不是禁止** ✓）。
     * 读的是**按 bot 冻结的画像**（`RiskProfile.of(bot)`）⇒ 一个任务跑到一半别人改开关也不影响本任务 ✓（S-6 语义）。
     * 默认（画像里 `hazardAversion=false`）⇒ **恒返回 0 ⇒ 与今天完全一致** ✓。
     */
    private double hazardAdjacencyPenalty(BlockPos to) {
        var bot = bot();   // ServerPlayer（BotPlayer 是它的子类 ✓）
        if (bot == null || !com.dddgn.alice.pathing.risk.RiskProfile.of(bot).hazardAversion()) {
            return 0.0D;
        }
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            if (com.dddgn.alice.pathing.MovementHelper.avoidWalkingInto(
                    level.getBlockState(to.relative(dir)))) {
                return HAZARD_ADJACENCY_PENALTY;
            }
            if (com.dddgn.alice.pathing.MovementHelper.avoidWalkingInto(
                    level.getBlockState(to.above().relative(dir)))) {
                return HAZARD_ADJACENCY_PENALTY;
            }
        }
        return 0.0D;
    }

    /**
     * 该类 Movement 的**写入下界**（D-106 Slice B）：一条写边至少产生的破坏次数。
     *
     * <p>刻意取下界（不为 0 的写边一律记 1）：计划期剪枝只用它判断"还写不写得动"，
     * 取上界会误剪合法路径（实测教训：把两格高墙的 `BREAK_AND_TRAVERSE` 记成 2，
     * 上限 1 的场景会被整条剪掉）。真正的"绝不超过上限"由执行期闸门保证。
     */
    public static int plannedBreaks(MovementType type) {
        return switch (type) {
            case BREAK_AND_TRAVERSE, BREAK_AND_ENTER, DOWNWARD -> 1;
            default -> 0;
        };
    }

    /** 该类 Movement 的**写入下界**：一条写边至少产生的放置次数。 */
    public static int plannedPlaces(MovementType type) {
        return switch (type) {
            case PLACE_STEP_AND_TRAVERSE, PILLAR -> 1;
            default -> 0;
        };
    }

    /**
     * 本条写边现在还能不能规划（D-106 Slice B）：任一预算桶耗尽 → 一律 {@code false}
     * （"耗尽后降级为纯通行"，不允许换成另一种写入方式继续试）。
     */
    public boolean writesAllowed(MovementType type) {
        int breaks = plannedBreaks(type);
        int places = plannedPlaces(type);
        if (breaks == 0 && places == 0) {
            return true;
        }
        if (bot == null) {
            return true;   // 无 bot 的纯规划（headless 回归）不做预算剪枝
        }
        return com.dddgn.alice.action.WriteBudget.plannedWritesAllowed(bot, breaks, places);
    }
}
