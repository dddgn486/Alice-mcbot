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
 * **L2 菜单协议最小验证探针**（{@code alice:menu_probe}）：普通右键，零参数，约 **8 秒**（故意放慢）。
 *
 * <p>它让 bot **真的开一次箱子菜单**（`gameMode.useItemOn` → `openMenu`）、用**菜单点击**
 * （`menu.clicked` + `ClickType.PICKUP`）把铁锭搬进自己背包、再关掉菜单。
 *
 * <p>看什么（游戏内）：bot 转向箱子 → **箱子开盖 + 开箱音** → 物品移动 → **关盖**；
 * 判据（日志）：`[MenuProbe] SUMMARY menu_opened=… menu_type=ChestMenu/… clicks=… item_moved=… verdict=…`。
 */
public class MenuProbeItem extends Item {

    public MenuProbeItem(Properties properties) {
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
        com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.FIXTURE);
        if (!BotManager.assignMenuProbe(bot, player instanceof ServerPlayer sp ? sp : null)) {
            say(player, "[alice] " + BotManager.busyMessage(bot));
            return;
        }
        say(player, "[alice] 菜单协议探针启动（约 8 秒，故意放慢）：站到箱子旁 → 转向 → 开箱（看开盖+音效）"
                + " → 点两次菜单把铁锭搬进背包 → 关箱。请看 [MenuProbe] SUMMARY");
    }

    private static void say(net.minecraft.world.entity.player.Player player, String text) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(text));
        }
    }
}
