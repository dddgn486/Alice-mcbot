package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * 串联回归启动器（{@code alice:pathing_regression}）：普通右键，一次跑完所有寻路场景。
 *
 * <p>覆盖：pathing_course（规划+执行）、place_course（放置+执行）、break_course（破坏+执行）、
 * fluid_course / lava_course / fence_course（拒绝行为）、dip_course（路线偏好）。
 * 每个场景自带地形函数，机器人会被传送到统一起点并补齐圆石/石镐。
 */
public class PathingRegressionItem extends Item {

    public PathingRegressionItem(Properties properties) {
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
            if (player != null) {
                player.sendSystemMessage(Component.literal("[alice] 没有可用 bot，请先 /alice spawn"));
            }
            return InteractionResult.SUCCESS;
        }
        ServerPlayer observer = player instanceof ServerPlayer sp ? sp : null;
        if (!BotManager.assignPathingRegression(bot, observer)) {
            if (player != null) {
                player.sendSystemMessage(Component.literal("[alice] bot 正忙，稍后再试"));
            }
            return InteractionResult.SUCCESS;
        }
        if (player != null) {
            player.sendSystemMessage(Component.literal("[alice] 串联回归启动 bot="
                    + bot.getName().getString()
                    + "（7 个场景：pathing/place/break/fluid/lava/dip/fence，约 20 秒）"));
        }
        return InteractionResult.SUCCESS;
    }
}
