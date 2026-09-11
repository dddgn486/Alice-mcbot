package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.WriteBudgetCheckTask;
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
 * 写入预算自检启动器（{@code alice:write_budget_check}，D-106）：普通右键，零参数。
 *
 * <p>跑一次真实带破坏的通行（`break_course`：两格高石墙挡住去路），但把**本次任务的破坏上限
 * 压到 1 格**（夹具专用 {@code WriteBudget.setCaps}，不接玩家命令），断言"只拆 1 格 → 用满即停
 * → 如实失败 → 绝不继续拆"。
 */
public class WriteBudgetCheckItem extends Item {

    public WriteBudgetCheckItem(Properties properties) {
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
        bot.teleportTo(level, WriteBudgetCheckTask.START_FOOT.getX() + 0.5D,
                WriteBudgetCheckTask.START_FOOT.getY(), WriteBudgetCheckTask.START_FOOT.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        ServerPlayer observer = player instanceof ServerPlayer sp ? sp : null;
        if (!BotManager.assignWriteBudgetCheck(bot, observer)) {
            say(player, "[alice] bot 正忙，稍后再试");
            return;
        }
        say(player, "[alice] 写入预算自检已启动（破坏上限压到 1 格；判据：只拆 1 格后如实失败）");
    }

    private static void say(net.minecraft.world.entity.player.Player player, String message) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(message));
        }
    }
}
