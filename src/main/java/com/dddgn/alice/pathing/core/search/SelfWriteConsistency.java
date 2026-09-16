package com.dddgn.alice.pathing.core.search;

import com.dddgn.alice.pathing.core.BreakAndEnterExecution;
import com.dddgn.alice.pathing.core.BreakAndTraverseExecution;
import com.dddgn.alice.pathing.core.MovementType;
import com.dddgn.alice.pathing.core.PillarExecution;
import com.dddgn.alice.pathing.core.PlaceStepAndTraverseExecution;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * **计划自我写入自洽性**（D-250/②′）：一条计划**不许踩在自己稍后会挖掉的格子上**。
 *
 * <p>为什么需要：规划期的世界视图是"破坏前"的（provider 只读实时世界、不做写入模拟，与 Baritone
 * {@code CalculationContext} 同款取舍）。搜索因此会生成这种边：`BREAK_AND_ENTER` 会清**脚位 + 头位**
 * 两格，而后面某一段恰好靠那个头位格支撑 —— 执行到那一段时支撑已经没了 ⇒ 会话健康检查判
 * `SEGMENT_FUTURE_BLOCKED`；而重规划从同一起点又会算出同一形状（D-248 → D-249 的实测链路）。
 *
 * <p>为什么**不能**放进搜索里做"生成期规则"（D-250 实测）：出问题的那条边（例：
 * `ASCEND (340,100,306)→(340,101,305)`）**本身是合法的** —— 在 `PILLAR` 前缀之后成立，
 * 在挖掘前缀之后不成立。**同一条边、两种前缀** ⇒ 不存在路径无关的生成期规则。而在搜索里按路径过滤
 * 会被 A\* 的**位置键合并**吃掉（更便宜的挖掘前缀占住该节点、干净前缀因更贵永不重挂）⇒ 实测把
 * 灌水坑逃生从"可解"变成 `UNREACHABLE`（`own_write_support=245`）。所以改成：**搜完校验 + 有界重搜**。
 *
 * <p>口径与执行器**一一对应**（同一套函数，避免"校验说行、执行器说不行"）：
 * 破坏格用 {@link BreakAndEnterExecution#collectBlockers} / {@link BreakAndTraverseExecution#collectBlockers}，
 * 放置格用 {@link PillarExecution#placePos} / {@link PlaceStepAndTraverseExecution#placePos}。
 */
public final class SelfWriteConsistency {

    private SelfWriteConsistency() {
    }

    /** 一条边的最小标识（类型 + 起终点脚位）：重搜时按**具体边**禁，不按 Movement 类禁（不损搜索空间）。 */
    public record EdgeKey(MovementType type, BlockPos fromFoot, BlockPos toFoot) {
    }

    /**
     * 一处冲突：{@code clearer} 清掉的 {@code supportCell}，被后面的 {@code violatingType} 当成了支撑。
     * 修复动作 = 禁掉 {@code clearer} 这条边后重搜。
     */
    public record Conflict(EdgeKey clearer, MovementType violatingType, BlockPos violatingTo,
                           BlockPos supportCell) {
    }

    /** 沿计划回放写入，返回**第一处**冲突（自洽则 {@code null}）。只读世界、无副作用。 */
    public static Conflict firstConflict(ServerLevel level, List<PlannedMovement> movements) {
        // cell → 把它清空的那条边（后写的覆盖先写的；放置会把条目删掉 ⇒ "又放回来了"）
        Map<Long, EdgeKey> cleared = new HashMap<>();
        for (PlannedMovement movement : movements) {
            MovementType type = movement.movementType();
            BlockPos to = movement.toFoot();
            if (requiresExistingSupport(type)) {
                EdgeKey clearer = cleared.get(to.below().asLong());
                if (clearer != null) {
                    return new Conflict(clearer, type, to, to.below());
                }
            }
            for (BlockPos cell : clearedCells(level, type, movement.fromFoot(), to)) {
                cleared.put(cell.asLong(), new EdgeKey(type, movement.fromFoot(), to));
            }
            BlockPos placed = placedCell(type, movement.fromFoot(), to);
            if (placed != null) {
                cleared.remove(placed.asLong());
            }
        }
        return null;
    }

    /**
     * 这条边**要求一格"现存的支撑"**（世界里的方块，而不是本移动自己放下的）。
     *
     * <p>{@code PILLAR}（往上垫一格）与 {@code PLACE_STEP_AND_TRAVERSE}（往前铺一格台阶）的支撑
     * 由本移动**放置**产生 ⇒ 不受"自己的破坏"影响（被挖空的格子反而变成可放置的空气）。
     */
    public static boolean requiresExistingSupport(MovementType type) {
        return type != MovementType.PILLAR && type != MovementType.PLACE_STEP_AND_TRAVERSE;
    }

    /** 这条边执行时会**破坏掉**的格子（与执行器同一口径）。 */
    public static List<BlockPos> clearedCells(ServerLevel level, MovementType type, BlockPos from, BlockPos to) {
        return switch (type) {
            case BREAK_AND_ENTER -> BreakAndEnterExecution.collectBlockers(level, from, to);
            case BREAK_AND_TRAVERSE -> BreakAndTraverseExecution.collectBlockers(level, from, to);
            case DOWNWARD -> List.of(to);
            default -> List.of();
        };
    }

    /** 这条边执行时会**放下**的格子（没有则 {@code null}）。 */
    public static BlockPos placedCell(MovementType type, BlockPos from, BlockPos to) {
        return switch (type) {
            case PILLAR -> PillarExecution.placePos(from);
            case PLACE_STEP_AND_TRAVERSE -> PlaceStepAndTraverseExecution.placePos(to);
            default -> null;
        };
    }

    /** 全计划的破坏格（供诊断/夹具断言用；计数与顺序无意义）。 */
    public static List<BlockPos> allClearedCells(ServerLevel level, List<PlannedMovement> movements) {
        List<BlockPos> cells = new ArrayList<>();
        for (PlannedMovement movement : movements) {
            cells.addAll(clearedCells(level, movement.movementType(), movement.fromFoot(), movement.toFoot()));
        }
        return cells;
    }
}
