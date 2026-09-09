package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.FallDiagnosticTask;
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
 * FALL 验证入口（{@code alice:pathing_fall}）：普通右键一次跑完规划 + 两条守卫 + 执行。
 */
public class PathingFallItem extends Item {

    public PathingFallItem(Properties properties) {
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
            bot = BotManager.firstOrSpawn(level, FallDiagnosticTask.DROP3_START);
        }
        if (bot == null) {
            if (player != null) {
                player.sendSystemMessage(Component.literal("[alice] bot 生成失败，请检查日志"));
            }
            return InteractionResult.SUCCESS;
        }
        ServerPlayer observer = player instanceof ServerPlayer sp ? sp : null;
        if (!BotManager.assignFallDiagnostic(bot, observer)) {
            if (player != null) {
                player.sendSystemMessage(Component.literal("[alice] bot 正忙，稍后再试"));
            }
            return InteractionResult.SUCCESS;
        }
        if (player != null) {
            player.sendSystemMessage(Component.literal("[alice] FALL 自检启动 bot="
                    + bot.getName().getString() + "（2/3 格落差 + 4 格与不可回收反例）"));
        }
        return InteractionResult.SUCCESS;
    }
}
