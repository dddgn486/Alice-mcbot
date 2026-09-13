package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.craft.CraftStation;
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
 * **合成网格探针**（{@code alice:craft_grid_probe}，阶段 3-A / S1-3，D-192）：零参数、**只读**。
 *
 * <p>先 `/function alice_test:craft_tab_course`（孤立平台 + 一个精妙存储箱子），再右键本物品：
 * 打开**当前选中的合成工作站** → 打印菜单/网格/结果槽/全槽位事实表（含坐标与 active）→ 断言零写入。
 *
 * <p>**不做任何写入**：不装升级、不点页签、不摆料、不改世界。要用哪个站点先
 * `/alice craft station <auto|inventory|table|upgradetab>`。
 */
public class CraftGridProbeItem extends Item {

    public CraftGridProbeItem(Properties properties) {
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
        if (!BotManager.assignCraftGridProbe(bot, player instanceof ServerPlayer sp ? sp : null)) {
            say(player, "[alice] " + BotManager.busyMessage(bot));
            return;
        }
        say(player, "[alice] 合成网格探针（只读）：当前工作站=" + CraftStation.selected(bot)
                + "，候选=" + CraftStation.describe(bot, 6) + "。看 [CraftGridProbe] SUMMARY");
    }

    private static void say(net.minecraft.world.entity.player.Player player, String text) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(text));
        }
    }
}
