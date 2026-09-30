package com.dddgn.alice.reach;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * **掉落承接面**（`plans §4.2`⑤ 的 `R6`）：目标挖掉之后，**掉落物会不会丢**（掉进深坑 / 虚空 / 岩浆）。
 *
 * <p>⭐ 2026-09-29「搬空第三批」（改革 ① 主体 · `DS-5` 解体）：本类从
 * `task/mining/MiningPlanner` 搬出，成员**逐字未改**（只把 `private` 放宽成 `public`、
 * 并把两段 javadoc 的**归属摆正**：描述谓词的那段原来被夹在常量上面）。
 * 依据 = `plans §4.2`⑤ 逐字「⭐ **独立出来**」＋ `plans §4.1`「R6 / R7 —— ⭐ **本来就不属于它**」。
 *
 * <p>为什么落 `reach/`：它问的是**目标下方那一列的世界状态**（纯几何查询），
 * 与「站位选优」无关（`plans §4.2`⑤ 逐字），而它算出来的答案要填进
 * {@link ReachPlan#supportPlacementPos()} —— **载体就在本包**（`reach/`，
 * `D-460` 定的「内核侧几何层：触及站位 / 视线 / 计划」）。本类不依赖任何上层包。
 *
 * <p>⚠️ **本类只回答"要不要垫"，不回答"谁去垫"**：`plans §4.2`⑤ 逐字「而"**垫一块**"这个
 * **动作**该由谁做，**是另一个要单独裁的问题**（今天挂在 A 腿里，只有 A 成功才走到）」
 * ⇒ 选哪条腿 + 放支撑块的**动作**仍在 `MiningPlanner`（A 腿 = `R2`）与
 * `action/mining/MineBlockRunner.tickSupportPlacement` 里，**本刀不动它们**。
 *
 * <p>⚠️ **深度口径是用户裁定**（见 {@link #DROP_FALL_SEARCH}）：不许在这里"顺手收紧/放宽"。
 */
public final class DropCatchment {

    private DropCatchment() {
    }

    /**
     * 「掉落承接面」的搜索深度 ⭐ 用户 2026-09-22 裁定（**先 5 后改为 8**，以 8 为准）：
     * **至少要下方悬空 8 格**才算"掉落物会丢"。
     *
     * <p>原来 = 4 ⇒ 真机上"目标下面只空 3~4 格、再往下就是实心"的**普通矿洞**被当成深坑 ⇒
     * 触发了"在目标下方垫方块"这条会写世界的动作（用户看到的是"莫名其妙跑到目标下面垫石头"）。
     * 8 格口径：**只有掉落物真会掉 ≥8 格（或下方是岩浆/虚空）才垫** ⇒ 普通矿洞一律不写世界。
     */
    public static final int DROP_FALL_SEARCH = 8;

    /**
     * **掉落物真的会丢**才需要垫（`D-364`）—— 真机实测（2026-09-20）暴露了原判据与注释的错位：
     * 注释写的是"否则掉落物会掉进**虚空/岩浆/深坑**"，而实现是 `!hasSupportBelow`（下方那格不是实心就垫）
     * ⇒ **挖矿自己挖出来的坑也满足条件**：先挖 y=72、再挖 y=73 时，下方正是刚挖空的空气
     * ⇒ 每个上层矿石都要求垫方块 ⇒ **垫不上就把那个目标判死**（实测 9 次 `SUPPORT_PLACE_FAILED`，
     * 于是整层 y=73 的煤被留下、bot 跑去远处挖），而且垫下去的方块**会挡住相邻矿石的视线**
     * （实测 `LINE_OF_SIGHT_BLOCKED`）。现在按注释的原意判：**N 格内没有可落面**（深坑/虚空）
     * 或**先撞上岩浆**才算"会丢"。
     *
     * <p>⚠️ 顺序即语义：**先看岩浆再看可落面**（沿列自上而下，第一个"可落面"就停）。
     * 未加载区块一律**不判"会丢"**（保守：不写世界；`D-331` —— 内核/规划器不得静默加载区块）。
     *
     * @param target 目标方块（**不含**目标自己那一格，从它下面一格开始往下数）
     * @return {@code true} = {@link #DROP_FALL_SEARCH} 格内都没有可落面，或先撞上岩浆
     */
    public static boolean dropWouldBeLost(ServerLevel level, BlockPos target) {
        BlockPos cursor = target.below();
        for (int depth = 0; depth < DROP_FALL_SEARCH; depth++) {
            if (!level.hasChunkAt(cursor)) {
                return false;       // 未加载 ⇒ 不判"会丢"（保守：不写世界；D-331）
            }
            var state = level.getBlockState(cursor);
            if (state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
                return true;        // 掉落物落到岩浆 = 销毁
            }
            if (!state.getCollisionShape(level, cursor).isEmpty()) {
                return false;       // 找到可落面 ⇒ 捡得回来
            }
            cursor = cursor.below();
        }
        return true;                // N 格内都没有可落面 ⇒ 按"深坑/虚空"处理
    }

    /**
     * 两个位置是否**同一竖列**（X/Z 相同）。
     *
     * <p>`R6` 的两处用点都在"垫一块"的分流里（`MiningPlanner`）：① 当前站位**就在目标正下方**
     * 时属于"从下方挖"策略 ⇒ **不必垫**；② 候选站位按同列/异列分成 `below` / `side` 两组
     * （只有后者才背 {@code PLACE_ONE_BLOCK_COST}）。⇒ 它算 `R6` 的形状，随 `R6` 一起搬（`plans §4.2`⑤）。
     */
    public static boolean isSameColumn(BlockPos pos, BlockPos target) {
        return pos.getX() == target.getX() && pos.getZ() == target.getZ();
    }
}
