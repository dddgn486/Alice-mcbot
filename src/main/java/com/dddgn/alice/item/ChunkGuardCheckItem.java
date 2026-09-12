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
 * 未加载区块 / 世界边界准入自检启动器（{@code alice:chunk_guard_check}，S-2 / P1-A）：普通右键，零参数。
 *
 * <p>纯无头规划，**不需要场景**（就地取材：bot 站的地方就是起点）。三个用例见
 * {@link com.dddgn.alice.task.ChunkGuardCheckTask}，输出 {@code [ChunkGuard] SUMMARY …}。
 */
public class ChunkGuardCheckItem extends Item {

    public ChunkGuardCheckItem(Properties properties) {
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
            // 就地取材：玩家站的地方就是起点，所以 bot 也生成在玩家脚位
            bot = BotManager.firstOrSpawn(level, player.blockPosition());
        }
        if (bot == null) {
            say(player, "[alice] bot 生成失败");
            return;
        }
        if (BotManager.isBusy(bot)) {
            say(player, "[alice] bot 正忙，稍后再试");
            return;
        }
        // 先离开危险格：否则维生会在任务第 1 tick 就把它中断掉（2026-09-12 实测：
        // 上一轮 survival_exit_check 之后 bot 还卡在压顶格里，chunk_guard 直接被 SURVIVAL_INTERRUPTED）
        var clean = com.dddgn.alice.task.ChunkGuardCheckTask.findCleanStandNear(level, bot);
        if (clean != null) {
            bot.teleportTo(level, clean.getX() + 0.5D, clean.getY(), clean.getZ() + 0.5D,
                    java.util.Set.of(), bot.getYRot(), bot.getXRot());
            bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            bot.controller().stopMovement();
        }
        if (!BotManager.assignChunkGuardCheck(bot, player instanceof ServerPlayer sp ? sp : null)) {
            say(player, "[alice] bot 正忙，稍后再试");
            return;
        }
        say(player, "[alice] 区块/边界门控自检已启动：远目标=未加载区块必须 GOAL_NOT_LOADED 且不加载它；"
                + "身边目标必须 REACHED；边界外目标不得 REACHED。看 [ChunkGuard] SUMMARY");
    }

    private static void say(net.minecraft.world.entity.player.Player player, String text) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(text));
        }
    }
}
