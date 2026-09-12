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

    /**
     * 统一自检起点（脚位）：距橡树最近、远离云杉与高大云杉，且**头位为空**
     * （2026-09-10 实测：原定 (21,64,206) 的头位被橡树树叶占据 → 树叶有碰撞，bot 站不进去）。
     *
     * <p>距离：橡树 3.16 / 云杉 5.10 / 高大云杉 11.05 → 最近者无歧义。
     */
    public static final BlockPos START_FOOT = new BlockPos(23, 64, 207);

    /** 期望被选中的树：普通橡树（4 原木，1 现在可见 + 3 掏空仰望可达，无需清障）。 */
    public static final BlockPos EXPECTED_TREE = new BlockPos(20, 64, 208);

    /** 期望被拒的对照：2x2 高大云杉（77 原木、20 高，含硬遮挡 → trunk_too_tall）。 */
    public static final BlockPos REJECTED_TALL_TREE = new BlockPos(22, 64, 218);

    /**
     * **可持续伐木区**（J8 / §13.2）的测试区域：**只划水平范围**（夹具帮忙划好），垂直自适应。
     * 与 `lumber_course_terrain` 注释里的场景盒水平范围一致（x 17..37 / z 203..231）；
     * `baseY` 取场景地板（58），高度上限 48（够覆盖本场景的树；实际生效上界由巡查按实测树高收紧）。
     */
    public static final int REGION_MIN_X = 17;
    public static final int REGION_MAX_X = 37;
    public static final int REGION_MIN_Z = 203;
    public static final int REGION_MAX_Z = 231;
    public static final int REGION_BASE_Y = 58;
    public static final int REGION_MAX_HEIGHT = 48;

    /** 夹具用的区域定义（玩家自定义走 {@code /alice region set <pos1> <pos2>}）。 */
    public static com.dddgn.alice.job.lumber.LumberRegionState.Region region() {
        return new com.dddgn.alice.job.lumber.LumberRegionState.Region(
                REGION_MIN_X, REGION_MIN_Z, REGION_MAX_X, REGION_MAX_Z, REGION_BASE_Y,
                REGION_MAX_HEIGHT);
    }

    private LumberCourseAnchor() {
    }
}
