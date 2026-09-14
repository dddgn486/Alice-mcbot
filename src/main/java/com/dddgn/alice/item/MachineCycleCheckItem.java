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
 * **单机最小闭环自检**（{@code alice:machine_cycle_check}，阶段 3-B / S4）：零参数、**会写世界**
 * （往机器里放料、把产物取回 —— 走容器写入授权与预算，写入理由 `CONTAINER_TRANSFER`）。
 *
 * <p>先 `/function alice_test:machine_course`（孤立平台 + 富集仓 + 粉碎机 + 创造能量方块），再右键本物品：
 * bot 传送到场景起点 → 认机器（按 {@code MachineMap}）→ 开菜单 → 挑一道**物品进出**的配方 →
 * shift-click 放料 → 等真实进度 → shift-click 取产物 → 断言"产物进背包 / 机器已清空 / 料被消耗" → 回起点。
 *
 * <p>看什么（游戏内）：bot 站到机器旁 → 菜单打开 → 料进机器 → **机器开始转（进度）** → 产物进背包。
 * 判据（日志）：`[MachineCycle] SUMMARY … product_before=0 product_after=1 machine_emptied=true verdict=…`。
 */
public class MachineCycleCheckItem extends Item {

    public MachineCycleCheckItem(Properties properties) {
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
        if (!BotManager.assignMachineCycleCheck(bot, player instanceof ServerPlayer sp ? sp : null)) {
            say(player, "[alice] " + BotManager.busyMessage(bot));
            return;
        }
        say(player, "[alice] 机器闭环自检启动（放料→等→取产物；**会写容器**）："
                + "场景需先 /function alice_test:machine_course。看 [MachineCycle] SUMMARY");
    }

    private static void say(net.minecraft.world.entity.player.Player player, String text) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(text));
        }
    }
}
