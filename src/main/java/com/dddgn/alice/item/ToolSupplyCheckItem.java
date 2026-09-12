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
 * **工具供给自检**（{@code alice:tool_supply_check}，基-9）：普通右键，零参数，约 2 秒。
 *
 * <p>三个用例 + 一个负例（见 {@link com.dddgn.alice.task.ToolSupplyCheckTask}）：换更好的 / 没得换要如实报 /
 * **不能凭空变出工具**。判据 `[ToolSupply] SUMMARY`。
 *
 * <p>注意：它会**临时改写 bot 背包**，收尾会复原成"一把镐 + 一把斧"。
 */
public class ToolSupplyCheckItem extends Item {

    public ToolSupplyCheckItem(Properties properties) {
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
        if (!BotManager.assignToolSupplyCheck(bot, player instanceof ServerPlayer sp ? sp : null)) {
            say(player, "[alice] " + BotManager.busyMessage(bot));
            return;
        }
        say(player, "[alice] 工具供给自检（约 2 秒，会临时改写 bot 背包并在收尾复原）："
                + "换更好的镐 / 没得换要如实报 / 不能凭空变出工具。看 [ToolSupply] SUMMARY");
    }

    private static void say(net.minecraft.world.entity.player.Player player, String text) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(text));
        }
    }
}
