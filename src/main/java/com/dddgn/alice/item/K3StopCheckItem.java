package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.K3StopCheckTask;
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
 * **K-3 安全点停止自检**（{@code alice:k3_stop_check}）：零参数，约 5 秒。
 *
 * <ul>
 *   <li>**右键** → {@link K3StopCheckTask.Mode#DEFER}：升空 → 请求停止 → 延后到落地后执行；</li>
 *   <li>**Shift + 右键** → {@link K3StopCheckTask.Mode#FORCED}：升空 → 请求停止 → 一直吊在空中
 *       ⇒ 20 tick 后**强制停**（验"不无限等"）。</li>
 * </ul>
 *
 * <p>D-169：这两种模式**不能**作为回归电池的步骤 —— 本自检观察的是"**顶层任务**被停止"，
 * 而 `stopTask` 停的就是顶层任务（在电池里 = 电池自己；实测会让电池在第 12 步自杀）。
 */
public class K3StopCheckItem extends Item {

    public K3StopCheckItem(Properties properties) {
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
        // 模式选择**零参数**：潜行 = FORCED（原电池步 `k3_stop_forced` 的用例）
        K3StopCheckTask.Mode mode = player != null && player.isShiftKeyDown()
                ? K3StopCheckTask.Mode.FORCED
                : K3StopCheckTask.Mode.DEFER;
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
        if (!BotManager.assignK3StopCheck(bot, player instanceof ServerPlayer sp ? sp : null, mode)) {
            say(player, "[alice] " + BotManager.busyMessage(bot));
            return;
        }
        say(player, mode == K3StopCheckTask.Mode.DEFER
                ? "[alice] K-3 自检（DEFER）：bot 升空 → 请求停止 → **延后到落地后执行**。"
                        + "看 [K3] / 停止请求… / 已到安全点… 三行日志"
                : "[alice] K-3 自检（FORCED）：bot 升空 → 请求停止 → **一直吊在空中** ⇒ 20 tick 后**强制停**。"
                        + "看 [K3] / 不安全时刻被强制停止… 两行日志，并用 alice:bot_report 看 forcedUnsafe=1");
    }

    private static void say(net.minecraft.world.entity.player.Player player, String text) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(text));
        }
    }
}
