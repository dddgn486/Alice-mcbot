package com.dddgn.alice.protection;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Alice 唯一的方块破坏安全入口。
 * <p>明确任务目标与执行器自行选择的清障方块使用不同策略：</p>
 * <ul>
 *   <li>明确目标：安全区和不可破坏方块拒绝；黑曜石等高代价方块仍允许。</li>
 *   <li>清障目标：额外回避脚下承重块与高代价方块，优先换站位/路线。</li>
 * </ul>
 */
public final class BlockBreakSafety {

    private BlockBreakSafety() {
    }

    /** 明确指定目标的硬拒绝原因；返回 null 表示目标本身允许挖。 */
    public static String explicitTargetRefusal(ServerPlayer bot, BlockPos target) {
        ServerLevel level = (ServerLevel) bot.level();
        String worldProtection = SafeZoneData.get(level.getServer()).protectionReason(level, target);
        if (worldProtection != null) {
            return worldProtection;
        }
        if (isUnbreakable(level, target)) {
            return "unbreakable_block";
        }
        return null;
    }

    /**
     * 清障方块的拒绝原因。清障是执行器擅自破坏，因此比明确目标更保守。
     * 返回非 null 时，上层应先尝试其他站位/路线，而不是立即破坏该方块。
     */
    public static String clearingRefusal(ServerPlayer bot, BlockPos target) {
        if (isUnderfoot(bot, target)) {
            return "underfoot_block";
        }
        String hardRefusal = explicitTargetRefusal(bot, target);
        if (hardRefusal != null) {
            return hardRefusal;
        }
        BlockState state = bot.level().getBlockState(target);
        if (isExpensiveToClear(state)) {
            return "expensive_clearing_block";
        }
        return null;
    }

    /** 目标当前在脚下时不能原地开挖，但可以换到侧面站位后作为明确目标挖掘。 */
    public static boolean requiresReposition(ServerPlayer bot, BlockPos target) {
        return isUnderfoot(bot, target);
    }

    private static boolean isUnderfoot(ServerPlayer bot, BlockPos target) {
        return target.equals(bot.blockPosition().below());
    }

    /** 原版负破坏速度表示生存模式不可破坏（如基岩）。 */
    public static boolean isUnbreakable(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getDestroySpeed(level, pos) < 0.0F;
    }

    /**
     * 可作为明确目标、但不应被普通清障路线擅自消耗的高代价方块。
     * 后续可迁移到数据包标签；当前先集中在唯一策略类，避免散落硬编码。
     */
    public static boolean isExpensiveToClear(BlockState state) {
        return state.is(Blocks.OBSIDIAN)
                || state.is(Blocks.CRYING_OBSIDIAN)
                || state.is(Blocks.REINFORCED_DEEPSLATE);
    }
}
