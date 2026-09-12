package com.dddgn.alice.task;

import net.minecraft.core.BlockPos;

/**
 * 传输自检场景（{@code alice_test:transfer_check_terrain}）的固定坐标常量，**与数据包函数注释对齐**。
 *
 * <p>为什么要有这个场景（2026-09-13 实测教训）：原先夹具在 Java 里用 `setBlock` 循环"手搓平台"，
 * 结果几何对不上 —— `[Transfer] suspend code=hard_path_failed … phase=TO_SOURCE` 卡在第一段走位，
 * 连 2 格都走不到。**项目规矩本就是"需要特定地形时用数据包函数一键生成"**（见 AGENTS.md），
 * 夹具应该只负责"传送 + 填内容 + 断言"，地形交给场景函数。
 */
public final class TransferCourseAnchor {

    /** bot 起点（脚位）：平台中央偏西，距源箱 2 格 ⇒ 覆盖"走位到可站点"这一段。 */
    public static final BlockPos BASE = new BlockPos(44, 64, 404);

    /** 源箱（场景函数已放置）。 */
    public static final BlockPos SOURCE = new BlockPos(46, 64, 404);

    /** 目标箱（场景函数已放置）。 */
    public static final BlockPos DESTINATION = new BlockPos(46, 64, 407);

    /** 其它夹具（端点选择等）在**同一平台**上的独立起点：与源/目标箱错开，互不干扰。 */
    public static final BlockPos FIXTURE_BASE = new BlockPos(44, 64, 414);

    /** 场景函数名（夹具统一用它重建地形）。 */
    public static final String SCENE_FUNCTION = "alice_test:transfer_check_terrain";

    private TransferCourseAnchor() {
    }
}
