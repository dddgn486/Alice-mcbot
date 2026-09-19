package com.dddgn.alice.pathing.core.search;

import net.minecraft.core.BlockPos;

import java.util.Objects;

/**
 * ⭐ **粗目标**（`D-337`，用户 2026-09-19 选 A）：**进入某个 XZ 半径内即可**（任意 Y）。
 *
 * <p><b>对照 Baritone</b>（`D-036`）：形状 = `GoalXZ`（只认 XZ 列、任意 y）与 `GoalNear`（半径）的组合 ——
 * 我们取**半径版**（"到某点附近即可"）。Baritone 用它 + 执行器滚动重规划来走远距离；
 * Alice 此前只有 {@link GoalFoot}（精确脚位）⇒ 目标区没加载时直接 `GOAL_NOT_LOADED`（`D-328` 实测：
 * 加载边界 ≈192 格，越界即 0 节点 0 ms 硬拒）。
 *
 * <p><b>为什么它不破坏红线</b>：`isInGoal`/`heuristic` **都是纯算术**（不读世界）⇒
 * 见 {@link GoalSpec#exactFoot()} 的说明；搜索仍只在已加载区内扩展 ⇒ **从不加载区块**。
 * 到边界交出尽力而为的前缀，执行器走完再规划（`PathRetryRunner` 的 partial hop，K-1）。
 *
 * <p><b>启发式（可采纳下界）</b>：把落点投影到区域的**最近格**后按 octile 计（与 {@link GoalFoot} 同单价），
 * 区域内 ⇒ 0；竖向不计（本目标允许任意 Y ⇒ 忽略竖向是下界 ✓）。
 *
 * @param centerX 区域中心的 X 坐标
 * @param centerZ 区域中心的 Z 坐标
 * @param radius  水平半径（Chebyshev：|dx| ≤ r 且 |dz| ≤ r）
 * @param anchor  规范落点（仅用于 `PathPlan` 记录 / 日志 / 写入策略消息，不参与到达判断）
 */
public record GoalNearXZ(int centerX, int centerZ, int radius, BlockPos anchor) implements GoalSpec {

    public GoalNearXZ {
        anchor = Objects.requireNonNull(anchor, "anchor").immutable();
        if (radius < 0) {
            throw new IllegalArgumentException("radius 必须 ≥ 0");
        }
    }

    /** 便捷构造：以某个 XZ 为中心、`anchor` 作为记录落点。 */
    public static GoalNearXZ around(BlockPos anchor, int radius) {
        return new GoalNearXZ(anchor.getX(), anchor.getZ(), radius, anchor);
    }

    @Override
    public boolean isInGoal(BlockPos foot) {
        return Math.abs(foot.getX() - centerX) <= radius && Math.abs(foot.getZ() - centerZ) <= radius;
    }

    @Override
    public double heuristic(BlockPos pos) {
        // 投影到区域最近格（超出部分才算距离）：区域内 ⇒ 0
        int dx = Math.max(0, Math.abs(pos.getX() - centerX) - radius);
        int dz = Math.max(0, Math.abs(pos.getZ() - centerZ) - radius);
        int straight = Math.max(dx, dz) - Math.min(dx, dz);
        int diagonal = Math.min(dx, dz);
        return straight * CostModel.TRAVERSE_COST + diagonal * CostModel.DIAGONAL_COST;
    }

    @Override
    public BlockPos goalFoot() {
        return anchor;
    }

    @Override
    public boolean exactFoot() {
        return false;
    }

    @Override
    public String describe() {
        return "粗目标 XZ(" + centerX + "," + centerZ + ") r=" + radius + " anchor=" + anchor.toShortString();
    }
}
