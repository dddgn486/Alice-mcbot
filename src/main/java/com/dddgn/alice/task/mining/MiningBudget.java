package com.dddgn.alice.task.mining;

import com.dddgn.alice.action.BlockInteraction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 挖掘预算与选项（D-067 批次 3）。
 *
 * <p>用户裁定（v7 ㉔/㉖）：
 * <ul>
 *   <li>`collectDrops` 是**必要参数**：为 false 时跳过"在目标下方放支撑块"与掉落物收集（挖通道 / 清场地）；</li>
 *   <li>兜底"走进目标格挖"的破坏上限 = `N × 普通方块破坏 tick`（N 默认 10），
 *       **不硬编码**：按目标珍贵程度分档（普通 1× / 普通矿石 2× / 稀有 4×）；</li>
 *   <li>标定基线：实测石头 + 石镐 = **6 tick/格**（D-068 客户端）。</li>
 * </ul>
 */
public record MiningBudget(boolean collectDrops, double maxExtraBreakTicks, double ordinaryBreakTicks) {

    /** 默认倍数 N。 */
    public static final double DEFAULT_MULTIPLIER = 10.0D;
    /** 实测基线：石头 + 石镐 6 tick（估算器不可用时回退）。 */
    public static final double FALLBACK_ORDINARY_TICKS = 6.0D;

    /** 按目标分档构造预算。 */
    public static MiningBudget forTarget(ServerPlayer bot, ServerLevel level, BlockPos target,
                                         boolean collectDrops) {
        double ordinary = BlockInteraction.estimateBreakTicks(bot, level, target);
        if (!Double.isFinite(ordinary) || ordinary <= 0.0D) {
            ordinary = FALLBACK_ORDINARY_TICKS;
        }
        double tier = tierOf(level.getBlockState(target));
        return new MiningBudget(collectDrops, ordinary * DEFAULT_MULTIPLIER * tier, ordinary);
    }

    /** 默认（收集掉落物）。 */
    public static MiningBudget collecting(ServerPlayer bot, ServerLevel level, BlockPos target) {
        return forTarget(bot, level, target, true);
    }

    /**
     * **常见矿石标签**（分档 2×）—— **"哪些算常见矿石"的唯一一份定义**。
     *
     * <p>J-6 的规矩是"不许长出第二份矿物清单"：决策层候选菜单（`CandidateMenu` 的 `mine` 条目）
     * **必须**从这里取，不得自己写一份。与 {@link #RARE_ORES} 一起构成菜单的扫描目标集。
     */
    public static final java.util.List<net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block>>
            COMMON_ORE_TAGS = java.util.List.of(BlockTags.COAL_ORES, BlockTags.IRON_ORES,
                    BlockTags.COPPER_ORES, BlockTags.GOLD_ORES, BlockTags.REDSTONE_ORES,
                    BlockTags.LAPIS_ORES);

    /** **稀有矿石方块**（分档 4×）—— "哪些算稀有矿石"的唯一一份定义（含深板岩变体与远古残骸）。 */
    public static final java.util.List<net.minecraft.world.level.block.Block> RARE_ORES =
            java.util.List.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
                    Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE, Blocks.ANCIENT_DEBRIS);

    /** 目标珍贵程度分档：稀有 4× / 普通矿石 2× / 其它 1×。 */
    private static double tierOf(BlockState state) {
        if (RARE_ORES.stream().anyMatch(state::is)) {
            return 4.0D;
        }
        if (COMMON_ORE_TAGS.stream().anyMatch(state::is)) {
            return 2.0D;
        }
        return 1.0D;
    }

    public String describe() {
        return String.format(java.util.Locale.ROOT,
                "collectDrops=%s maxExtraBreakTicks=%.1f ordinaryBreakTicks=%.1f",
                collectDrops, maxExtraBreakTicks, ordinaryBreakTicks);
    }
}
