package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.SoftMoveProbeTask;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/** 金斧外观的 SOFT_SURFACE 目标选择器，仅启动受限软移动探针。 */
public final class SoftMoveSelector extends Item {
    public SoftMoveSelector(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        Player player = context.getPlayer();
        ServerLevel serverLevel = (ServerLevel) level;
        BotPlayer bot = BotManager.firstInLevel(serverLevel);
        if (bot == null) {
            send(player, "[alice] 请先生成 bot，再指定软移动目的地");
            return InteractionResult.FAIL;
        }
        if (BotManager.isBusy(bot)) {
            send(player, "[alice] bot 当前有任务，未启动软移动探针");
            return InteractionResult.FAIL;
        }
        BlockPos support = context.getClickedPos();
        BlockPos target = support.above();
        SoftMoveProbeTask.ProbeValidation validation =
                SoftMoveProbeTask.validate(serverLevel, bot.blockPosition(), target);
        if (!validation.valid()) {
            send(player, "[alice] 软移动目的地无效: " + validation.reason());
            return InteractionResult.FAIL;
        }
        BotManager.assignSoftMoveProbe(bot, target);
        send(player, "[alice] 已指定 SOFT_SURFACE 目的地 " + target.toShortString()
                + " -> " + bot.getName().getString());
        return InteractionResult.SUCCESS;
    }

    private static void send(Player player, String message) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(message));
        }
    }
}
