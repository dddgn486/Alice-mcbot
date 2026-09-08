package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * R5-2 破坏通行测试器（{@code alice:pathing_breaker}）：场景专属，右键即启动。
 *
 * <p>把 bot 锚定到固定起点，规划到墙后固定目标（路径需要破坏阻挡方块），
 * 由 {@code PathSession} 执行并报告结构化结果。
 */
public class PathingBreakerItem extends Item {

    /** 固定起点（与 break_course.mcfunction 一致）。 */
    public static final BlockPos COURSE_START_FOOT = new BlockPos(0, 64, 66);
    /** 固定目标：墙后平台。 */
    public static final BlockPos COURSE_GOAL_FOOT = new BlockPos(7, 64, 66);

    public PathingBreakerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        return start(context.getPlayer(), (ServerLevel) level);
    }

    @Override
    public net.minecraft.world.InteractionResultHolder<net.minecraft.world.item.ItemStack> use(
            Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) {
            return net.minecraft.world.InteractionResultHolder.success(player.getItemInHand(hand));
        }
        start(player, (ServerLevel) level);
        return net.minecraft.world.InteractionResultHolder.success(player.getItemInHand(hand));
    }

    private InteractionResult start(Player player, ServerLevel level) {
        BotPlayer bot = BotManager.firstInLevel(level);
        if (bot == null) {
            bot = BotManager.firstOrSpawn(level, COURSE_START_FOOT);
        }
        if (bot == null) {
            if (player != null) {
                player.sendSystemMessage(Component.literal("[alice] bot 生成失败，请检查日志"));
            }
            return InteractionResult.SUCCESS;
        }
        if (!com.dddgn.alice.action.BlockInteraction.teleportSafely(bot, level, COURSE_START_FOOT,
                bot.getYRot(), bot.getXRot())) {
            if (player != null) {
                player.sendSystemMessage(Component.literal(
                        "[alice] 起点与 bot 之间被方块阻挡，已拒绝传送（不穿墙）。"
                        + "请先把 bot 带到起点附近再试。"));
            }
            return InteractionResult.SUCCESS;
        }
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
        // 夹具：确保 bot 有石镐（否则徒手挖石墙 7.5 秒/块，测试过慢且不体现工具选择）
        ensureStonePickaxe(bot);
        if (!BotManager.assignPathSessionDiagnostic(bot, COURSE_GOAL_FOOT, true)) {
            if (player != null) {
                player.sendSystemMessage(Component.literal("[alice] bot 正忙，稍后再试"));
            }
            return InteractionResult.SUCCESS;
        }
        if (player != null) {
            player.sendSystemMessage(Component.literal("[alice] R5-2 破坏通行启动 bot="
                    + bot.getName().getString() + " 起点=" + COURSE_START_FOOT.toShortString()
                    + " → 目标=" + COURSE_GOAL_FOOT.toShortString()
                    + "（规划穿墙路径 + 破坏 + 通过）"));
        }
        return InteractionResult.SUCCESS;
    }

    /** 测试夹具：确保 bot 快捷栏有石镐（记录日志，仅测试用）。 */
    private static void ensureStonePickaxe(BotPlayer bot) {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(net.minecraft.world.item.Items.STONE_PICKAXE)) {
                inventory.selected = Math.min(slot, 8);
                return;
            }
        }
        var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE_PICKAXE);
        inventory.add(stack);
        com.dddgn.alice.log.BotLog.info("[R5-2 Fixture] gave stone_pickaxe to bot={}",
                bot.getName().getString());
    }
}
