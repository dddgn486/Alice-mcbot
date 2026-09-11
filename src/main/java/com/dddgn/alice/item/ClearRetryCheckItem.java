package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.ClearRetryCheckTask;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 限次清障"换候选"自检启动器（{@code alice:clear_retry_check}，R2 / D-121）：普通右键，零参数。
 *
 * <p>场景由任务在运行时搭建（复用 `break_course` 平台 + 石头壳），断言"一个清障候选失败后
 * **换下一个**而不是放弃整棵树"，以及"清障子任务信封是父信封的子集"。
 */
public class ClearRetryCheckItem extends Item {

    public ClearRetryCheckItem(Properties properties) {
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
            bot = BotManager.firstOrSpawn(level, ClearRetryCheckTask.START_FOOT);
        }
        if (bot == null || BotManager.isBusy(bot)) {
            say(player, bot == null ? "[alice] bot 生成失败" : "[alice] bot 正忙，稍后再试");
            return;
        }
        if (!BotManager.assignClearRetryCheck(bot, player instanceof ServerPlayer sp ? sp : null)) {
            say(player, "[alice] bot 正忙，稍后再试");
            return;
        }
        say(player, "[alice] 清障换候选自检已启动 bot=" + bot.getName().getString()
                + "（约 10 秒，看日志 [ClearRetry] SUMMARY）");
    }

    private static void say(net.minecraft.world.entity.player.Player player, String text) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(text));
        }
    }
}
