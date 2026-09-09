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

    /** 目标珍贵程度分档：稀有 4× / 普通矿石 2× / 其它 1×。 */
    private static double tierOf(BlockState state) {
        if (state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE)
                || state.is(Blocks.ANCIENT_DEBRIS) || state.is(Blocks.EMERALD_ORE)
                || state.is(Blocks.DEEPSLATE_EMERALD_ORE)) {
            return 4.0D;
        }
        if (state.is(BlockTags.COAL_ORES) || state.is(BlockTags.IRON_ORES)
                || state.is(BlockTags.COPPER_ORES) || state.is(BlockTags.GOLD_ORES)
                || state.is(BlockTags.REDSTONE_ORES) || state.is(BlockTags.LAPIS_ORES)) {
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
