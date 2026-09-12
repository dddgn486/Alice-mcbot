package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.FluidMineCheckTask;
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
 * 挖掘前流体风险自检启动器（{@code alice:fluid_mine_check}，S-4 / P0-C）：普通右键，零参数。
 *
 * <p>验两件事（一次跑完，输出 {@code [FluidMineCheck] SUMMARY …}）：
 * 目标下方是岩浆 ⇒ **硬拒**（`fluid_risk_lava`）且**不做清障/加高**；
 * 普通目标 ⇒ 正常挖完并入包。
 */
public class FluidMineCheckItem extends Item {

    public FluidMineCheckItem(Properties properties) {
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
            bot = BotManager.firstOrSpawn(level, FluidMineCheckTask.STAND_FOOT);
        }
        if (bot == null || BotManager.isBusy(bot)) {
            say(player, bot == null ? "[alice] bot 生成失败" : "[alice] bot 正忙，稍后再试");
            return;
        }
        bot.teleportTo(level, FluidMineCheckTask.STAND_FOOT.getX() + 0.5D,
                FluidMineCheckTask.STAND_FOOT.getY(), FluidMineCheckTask.STAND_FOOT.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        if (!BotManager.assignFluidMineCheck(bot, player instanceof ServerPlayer sp ? sp : null)) {
            say(player, "[alice] bot 正忙，稍后再试");
            return;
        }
        say(player, "[alice] 挖掘流体风险自检已启动：目标下方是岩浆的那一格必须被拒（不做清障/加高），"
                + "普通石头必须正常挖完；看 [FluidMineCheck] SUMMARY");
    }

    private static void say(net.minecraft.world.entity.player.Player player, String text) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(text));
        }
    }
}
