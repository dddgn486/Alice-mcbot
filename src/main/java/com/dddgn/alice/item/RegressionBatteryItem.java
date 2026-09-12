package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * 串联回归电池启动器（{@code alice:regression_battery}，D-122）：普通右键，零参数。
 *
 * <p>一次跑完"改了生产任务必须复跑"的 23 项常用回归（清单见 {@code RegressionBatteryTask} 与
 * {@code docs/TESTING_GUIDE.md §1.7}），每项自己复位、失败不中断，最后一行
 * {@code [Regression] SUMMARY … (23/23) → PASS|FAIL}。
 */
public class RegressionBatteryItem extends Item {

    public RegressionBatteryItem(Properties properties) {
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

    private void start(net.minecraft.world.entity.player.Player player, ServerLevel level) {
        BotPlayer bot = BotManager.firstInLevel(level);
        if (bot == null) {
            bot = BotManager.firstOrSpawn(level, com.dddgn.alice.task.ClearRetryCheckTask.START_FOOT);
        }
        if (bot == null || BotManager.isBusy(bot)) {
            say(player, bot == null ? "[alice] bot 生成失败" : "[alice] " + BotManager.busyMessage(bot));
            return;
        }
        ServerPlayer observer = player instanceof ServerPlayer sp ? sp : null;
        if (!BotManager.assignRegressionBattery(bot, observer)) {
            say(player, "[alice] " + BotManager.busyMessage(bot));
            return;
        }
        say(player, "[alice] 串联回归电池已启动（23 项，约 4~6 分钟）。"
                + "请站远一点别捡掉落物；结果看日志 [Regression] SUMMARY。");
    }

    private static void say(net.minecraft.world.entity.player.Player player, String text) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(text));
        }
    }
}
