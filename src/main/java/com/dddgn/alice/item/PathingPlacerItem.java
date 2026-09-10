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
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * R5-3 放置台阶通行测试器（{@code alice:pathing_placer}）：场景专属，右键即启动。
 *
 * <p>把 bot 锚定到固定起点，规划到固定目标（路径需要跨越缺口并放置台阶），
 * 由 {@code PathSession} 执行并报告结构化结果。夹具给 bot 8 个圆石。
 */
public class PathingPlacerItem extends Item {

    /** 固定起点（与 place_course.mcfunction 一致）。 */
    public static final BlockPos COURSE_START_FOOT = new BlockPos(0, 64, 66);
    /** 固定目标：跨过缺口并下 2 格后的低平台（下降落点本身）。 */
    public static final BlockPos COURSE_GOAL_FOOT = new BlockPos(8, 62, 66);

    public PathingPlacerItem(Properties properties) {
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
        // 夹具重置：允许传送（带头部同步的重载，避免头身不一致）
        bot.teleportTo(level, COURSE_START_FOOT.getX() + 0.5D, COURSE_START_FOOT.getY(),
                COURSE_START_FOOT.getZ() + 0.5D, java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
        ensureCobblestone(bot, 8);
        if (!BotManager.assignPathSessionDiagnostic(bot, COURSE_GOAL_FOOT, true)) {
            if (player != null) {
                player.sendSystemMessage(Component.literal("[alice] bot 正忙，稍后再试"));
            }
            return InteractionResult.SUCCESS;
        }
        if (player != null) {
            player.sendSystemMessage(Component.literal("[alice] R5-3 放置台阶通行启动 bot="
                    + bot.getName().getString() + " 起点=" + COURSE_START_FOOT.toShortString()
                    + " → 目标=" + COURSE_GOAL_FOOT.toShortString()
                    + "（跨越缺口 + 放置台阶）"));
        }
        return InteractionResult.SUCCESS;
    }

    /** 测试夹具：确保 bot 有至少 {@code count} 个圆石（记录日志，仅测试用）。 */
    private static void ensureCobblestone(BotPlayer bot, int count) {
        com.dddgn.alice.item.FixtureToolKit.ensureHotbarStack(bot,
                () -> new ItemStack(Items.COBBLESTONE),
                stack -> stack.is(Items.COBBLESTONE), count, "cobblestone");
    }
}
