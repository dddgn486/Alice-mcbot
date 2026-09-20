package com.dddgn.alice.job.mine;

import net.minecraft.core.BlockPos;

/**
 * **挖矿作业区 / 意图**（`D-329` §3，阶段 1.5）：回答"**在哪挖、挖到什么程度**"，与执行层（怎么过去、怎么挖）分离。
 *
 * <p>**为什么需要**（`D-329` ④）：向下的偏置不来自加载策略，而来自 ① 成本函数形状（向下最近、挖一格就自我扩张候选）、
 * ② `GoalSpec.center` 固定（候选吃干即 `no_reachable_candidate`，不会自己换地方）、③ 可见性偏差（只扫"已存在"的方块）。
 * 作业区/层位意图正是用来消这个偏置的**声明式**手段：把"在哪片地方挖"从"算法恰好先扫到哪"里拿出来。
 *
 * <p>⚠️⭐ **意图是"搜索偏好"，不是"可挖承诺"**（用户 2026-09-20 点出的陷阱）：一个符合意图的作业区里
 * **完全可能有一部分目标实际不可挖**（被保护、不可破、视线不可达）。⇒ 本类的 {@link #refusalFor} 只负责
 * 回答"**在不在作业区里**"，**绝不**替授权面/破坏面下结论；区内的候选照样要过
 * `ZoneAuthority.candidateRefusal` + `BlockInteraction.breakable`，而且**它们自己的理由码必须保住**
 * （不许被 `outside_work_area` 顶替）——否则"不可挖"就会被伪装成"不在计划里"。
 *
 * @param areaCenter   作业区中心（`null` = 没有意图 = 今天的行为）
 * @param halfExtentXZ **方形**作业区的半边长（0 ⇒ 只认中心那一列）
 * @param yMin         层位下界（含）
 * @param yMax         层位上界（含）
 */
public record MineIntent(BlockPos areaCenter, int halfExtentXZ, int yMin, int yMax) {

    /** 区域外的拒绝理由码（**显式且可数**，不许静默丢弃 —— `D-341` 口径）。 */
    public static final String OUTSIDE = "outside_work_area";

    public MineIntent {
        if (areaCenter != null) {
            areaCenter = areaCenter.immutable();
            if (halfExtentXZ < 0) {
                throw new IllegalArgumentException("halfExtentXZ must be >= 0");
            }
            if (yMin > yMax) {
                throw new IllegalArgumentException("yMin must be <= yMax");
            }
        }
    }

    /** 没有意图（= 今天的行为：以 `GoalSpec.center/radius` 为界，不额外约束）。 */
    public static MineIntent none() {
        return new MineIntent(null, 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /** 方形作业区 + 层位区间。 */
    public static MineIntent area(BlockPos center, int halfExtentXZ, int yMin, int yMax) {
        return new MineIntent(center, halfExtentXZ, yMin, yMax);
    }

    public boolean active() {
        return areaCenter != null;
    }

    /**
     * 这个位置**在不在**作业区里。
     *
     * @return `null` = 在（或没有意图）；否则返回 {@link #OUTSIDE}
     */
    public String refusalFor(BlockPos pos) {
        if (!active()) {
            return null;
        }
        int dx = Math.abs(pos.getX() - areaCenter.getX());
        int dz = Math.abs(pos.getZ() - areaCenter.getZ());
        if (dx > halfExtentXZ || dz > halfExtentXZ) {
            return OUTSIDE;
        }
        if (pos.getY() < yMin || pos.getY() > yMax) {
            return OUTSIDE;
        }
        return null;
    }

    public String describe() {
        if (!active()) {
            return "none";
        }
        return "area@" + areaCenter.toShortString() + " ±" + halfExtentXZ
                + " y=" + (yMin == Integer.MIN_VALUE ? "-inf" : yMin)
                + ".." + (yMax == Integer.MAX_VALUE ? "+inf" : yMax);
    }
}
