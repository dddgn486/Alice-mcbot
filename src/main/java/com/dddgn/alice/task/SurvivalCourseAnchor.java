package com.dddgn.alice.task;

import net.minecraft.core.BlockPos;

/**
 * 维生场景的固定锚点 —— 与 {@link OreCourseAnchor} / {@link LumberCourseAnchor} 同构：
 * **场景是孤立长方体区域，夹具必须把 bot 传送进来**（否则可达性/落点查询都从真实地形起算）。
 *
 * <p>两个场景各管一半（S-5，2026-09-15）：
 * <ul>
 *   <li>{@code alice_test:survival_course}：**有出口**的平台 —— 中央那格头顶压石（`SUFFOCATING`），
 *       四周同层可站 ⇒ 最近安全落点就是相邻格；</li>
 *   <li>{@code alice_test:survival_sealed_course}：**无出口**的封闭体 —— 17³ 实心石壳里唯一的 1×2 空腔，
 *       半径 {@code SurvivalSystem.REFUGE_RADIUS} 内**一个落点都没有**（用来验"软危险 + 无出口 ⇒ 不否决"）。</li>
 * </ul>
 */
public final class SurvivalCourseAnchor {

    /** 平台上的**安全**起点脚位（角上、头顶无遮挡）——夹具从这里开始，避免一进场就真处在危险里。 */
    public static final BlockPos PLATFORM_FOOT = new BlockPos(64, 64, 102);

    /** 平台中央的危险格（头顶 y=65 压石 ⇒ `SUFFOCATING`）；与 `survival_course` 注释对齐。 */
    public static final BlockPos HAZARD_FOOT = new BlockPos(66, 64, 104);

    /** dummy 任务的目标（同层相邻格；逃生会在它之前发生，所以它其实走不到）。 */
    public static final BlockPos DUMMY_GOAL = new BlockPos(68, 64, 106);

    /**
     * 封闭体（`survival_sealed_course`）里空腔的脚位：**半径 8 内所有格子都是石头**（除这一格与它头顶）。
     *
     * <p>为什么要把空腔做在实心体里而不是"围一圈墙"：`nearestSafeRefuge` 搜的是**整个 ±8 立方体**，
     * 围墙挡不住"站在墙上"的格子（墙顶上方两格空气就是合法落点）⇒ 只有"整块实体 + 唯一空腔"
     * 才能保证**它确实找不到落点**（这条判据本身就是被断言的对象，不能靠运气）。
     */
    public static final BlockPos SEALED_FOOT = new BlockPos(206, 64, 306);

    private SurvivalCourseAnchor() {
    }
}
