package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.ClearGuardCheckTask;
import com.dddgn.alice.task.TaskTarget;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 容器绕行自检启动器（{@code alice:clear_guard_check}，J6-b2）：普通右键，零参数。
 *
 * <p>跑一次真实挖掘（目标在墙后、唯一通道被箱子堵住），断言**箱子完好**（D-095）。
 */
public class ClearGuardCheckItem extends Item {

    public ClearGuardCheckItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(net.minecraft.world.item.context.UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        start(context.getPlayer(), (ServerLevel) level);
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, net.minecraft.world.entity.player.Player player,
                                                  InteractionHand hand) {
        if (level.isClientSide) {
            return InteractionResultHolder.success(player.getItemInHand(hand));
        }
        start(player, (ServerLevel) level);
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    private void start(net.minecraft.world.entity.player.Player player, ServerLevel level) {
        BotPlayer bot = BotManager.firstInLevel(level);
        if (bot == null) {
            say(player, "[alice] 没有可用 bot");
            return;
        }
        bot.teleportTo(level, ClearGuardCheckTask.START_FOOT.getX() + 0.5D,
                ClearGuardCheckTask.START_FOOT.getY(), ClearGuardCheckTask.START_FOOT.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        ServerPlayer observer = player instanceof ServerPlayer sp ? sp : null;
        if (!BotManager.assignClearGuardCheck(bot, observer)) {
            say(player, "[alice] bot 正忙，稍后再试");
            return;
        }
        say(player, "[alice] 容器绕行自检已启动（判据：箱子必须完好）");
    }

    private static void say(net.minecraft.world.entity.player.Player player, String message) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(message));
        }
    }
}
