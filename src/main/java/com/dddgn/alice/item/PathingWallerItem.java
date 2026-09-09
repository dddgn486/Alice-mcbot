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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * D-043 验证夹具：前方封路器（{@code alice:pathing_waller}）：场景专属，右键即启动。
 *
 * <p>在 {@code place_course} 场景执行到第 30 tick 时，在**计划路径前方第 2 段**放置一块石头，
 * 使计划失效。期望：会话上报结构化失败 → 任务层 {@code PathRetryRunner} 决定重规划 →
 * 日志出现 {@code [PathRetry] replan ...}，最终 {@code COMPLETED} 且 {@code replans=1}。
 */
public class PathingWallerItem extends Item {

    /** 与 place_course 一致。 */
    public static final BlockPos COURSE_START_FOOT = new BlockPos(0, 64, 66);
    public static final BlockPos COURSE_GOAL_FOOT = new BlockPos(8, 62, 66);
    private static final int WALL_TICK = 30;

    public PathingWallerItem(Properties properties) {
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
    public net.minecraft.world.InteractionResultHolder<ItemStack> use(
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
        bot.teleportTo(level, COURSE_START_FOOT.getX() + 0.5D, COURSE_START_FOOT.getY(),
                COURSE_START_FOOT.getZ() + 0.5D, java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
        ensureCobblestone(bot, 8);
        if (!BotManager.assignPathingWaller(bot, COURSE_GOAL_FOOT, WALL_TICK)) {
            if (player != null) {
                player.sendSystemMessage(Component.literal("[alice] bot 正忙，稍后再试"));
            }
            return InteractionResult.SUCCESS;
        }
        if (player != null) {
            player.sendSystemMessage(Component.literal("[alice] D-043 重规划验证启动 bot="
                    + bot.getName().getString() + " 起点=" + COURSE_START_FOOT.toShortString()
                    + " → 目标=" + COURSE_GOAL_FOOT.toShortString()
                    + "（第 " + WALL_TICK + " tick 在计划前方封路，观察是否自动重规划）"));
        }
        return InteractionResult.SUCCESS;
    }

    private static void ensureCobblestone(BotPlayer bot, int count) {
        var inventory = bot.getInventory();
        int have = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(net.minecraft.world.item.Items.COBBLESTONE)) {
                have += inventory.getItem(slot).getCount();
            }
        }
        if (have < count) {
            inventory.add(new ItemStack(net.minecraft.world.item.Items.COBBLESTONE, count - have));
        }
    }
}
