package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * **A5 决策层接线自检**（{@code alice:craft_goal_check}，阶段 3-A / A5，D-199）：零参数、**不需要场景**。
 *
 * <p>右键即跑：给 4 块橡木木板 ⇒ 查"可做清单"（必须有工作台且 `can_use=true`）⇒
 * 验越界被拒/在清单里被接受 ⇒ 用**生产路径**的 `CraftJob` 真做一个工作台 ⇒
 * 判据看世界事实（木板 −4、工作台 +1）⇒ 清空背包。
 */
public class CraftGoalCheckItem extends Item {

    public CraftGoalCheckItem(Properties properties) {
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
            say(player, "[alice] 没有可用 bot（先用 /alice spawn）");
            return;
        }
        if (BotManager.isBusy(bot)) {
            say(player, "[alice] " + BotManager.busyMessage(bot));
            return;
        }
        if (!BotManager.assignCraftGoalCheck(bot, player instanceof ServerPlayer sp ? sp : null)) {
            say(player, "[alice] " + BotManager.busyMessage(bot));
            return;
        }
        say(player, "[alice] 决策层合成自检：可做清单 → 越界拒绝 → 真做一个工作台（约 2 秒）。"
                + " 看 [CraftGoalCheck] SUMMARY");
    }

    private static void say(net.minecraft.world.entity.player.Player player, String text) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(text));
        }
    }
}
