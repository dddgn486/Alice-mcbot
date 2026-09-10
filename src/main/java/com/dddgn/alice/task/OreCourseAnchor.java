package com.dddgn.alice.task;

import net.minecraft.core.BlockPos;

/**
 * 挖掘 Job 夹具（`ore_course` 场景）的固定锚点——与 {@link LumberCourseAnchor} 同构。
 *
 * <p>场景是**孤立长方体区域**，与其它地形没有可行走路径，所以夹具必须把 bot 传送进来
 * （否则每个子任务都会在规划期如实报"不可达"）。
 */
public final class OreCourseAnchor {

    /** 场景起点脚位：石体顶面 y=62 之上，位于 6 处铁矿中间。 */
    public static final BlockPos START_FOOT = new BlockPos(56, 63, 132);

    /** 其中一处铁矿（自检/文档引用用）。 */
    public static final BlockPos EXPECTED_ORE = new BlockPos(56, 62, 128);

    private OreCourseAnchor() {
    }
}
