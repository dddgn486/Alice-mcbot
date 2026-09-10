package com.dddgn.alice.task;

import net.minecraft.core.BlockPos;

/** 伐木场景（{@code alice_test:lumber_course}）的固定坐标常量，与数据包函数注释对齐。 */
public final class LumberCourseAnchor {

    /** 统一自检起点（脚位）。 */
    public static final BlockPos START_FOOT = new BlockPos(23, 64, 207);

    /** 预期被选中的树（B）基座：露天、4 原木、有树冠、最近的可达树。 */
    public static final BlockPos EXPECTED_TREE_B = new BlockPos(23, 64, 213);

    private LumberCourseAnchor() {
    }
}
