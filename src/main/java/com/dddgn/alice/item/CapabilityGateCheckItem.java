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
 * **能力闸门自检**（{@code alice:capability_gate_check}，基-8）：普通右键，零参数，约 1 秒（纯逻辑）。
 *
 * <p>验 `MovementCapabilities` 不再是"没人读的元数据"：保护区/资源/工具/预算/声明一致性五类各一条负例。
 * 判据 `[CapabilityGate] SUMMARY`。
 */
public class CapabilityGateCheckItem extends Item {

    public CapabilityGateCheckItem(Properties properties) {
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
        if (!BotManager.assignCapabilityGateCheck(bot, player instanceof ServerPlayer sp ? sp : null)) {
            say(player, "[alice] " + BotManager.busyMessage(bot));
            return;
        }
        say(player, "[alice] 能力闸门自检（约 1 秒）：保护区/资源/工具/预算/声明一致性。看 [CapabilityGate] SUMMARY");
    }

    private static void say(net.minecraft.world.entity.player.Player player, String text) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(text));
        }
    }
}
