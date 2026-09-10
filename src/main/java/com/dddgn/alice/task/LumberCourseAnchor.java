package com.dddgn.alice.task;

import net.minecraft.core.BlockPos;

/**
 * 伐木场景（{@code alice_test:lumber_course}，真树夹具）的固定坐标常量，与数据包函数注释对齐。
 *
 * <p>夹具来源：玩家在 {@code alice_test:lumber_plant} 种植场催熟真树后，由
 * {@code tools/capture-scene.py} 从存档抓取；预期结果由 {@code tools/analyze-lumber-scene.py}
 * 离线预测（2026-09-10 实测预测值见 {@code docs/AI_TEST_MATRIX.md}）。
 */
public final class LumberCourseAnchor {

    /** 统一自检起点（脚位）：距橡树最近、远离云杉与高大云杉。 */
    public static final BlockPos START_FOOT = new BlockPos(21, 64, 206);

    /** 期望被选中的树：普通橡树（4 原木，1 现在可见 + 3 掏空仰望可达，无需清障）。 */
    public static final BlockPos EXPECTED_TREE = new BlockPos(20, 64, 208);

    /** 期望被拒的对照：2x2 高大云杉（77 原木、20 高，含硬遮挡 → trunk_too_tall）。 */
    public static final BlockPos REJECTED_TALL_TREE = new BlockPos(22, 64, 218);

    private LumberCourseAnchor() {
    }
}
