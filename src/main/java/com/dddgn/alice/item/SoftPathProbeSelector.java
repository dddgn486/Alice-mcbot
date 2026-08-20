package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/** 独立的软路径诊断工具：点击支撑方块，只启动 SoftPathProbeTask。 */
public final class SoftPathProbeSelector extends Item {
    public SoftPathProbeSelector(Properties properties) {
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
            send(player, "[alice] 请先生成 bot，再点击软路径探针支撑方块");
            return InteractionResult.FAIL;
        }
        if (BotManager.isBusy(bot)) {
            send(player, "[alice] bot 当前有任务，未启动软路径探针");
            return InteractionResult.FAIL;
        }
        BlockPos support = context.getClickedPos();
        BlockPos target = support.above();
        if (!MovementHelper.canWalkOn(serverLevel, target)
                || !MovementHelper.canWalkThrough(serverLevel, target)
                || !MovementHelper.canWalkThrough(serverLevel, target.above())) {
            send(player, "[alice] 软路径支撑目标无效: support=" + support.toShortString()
                    + " foot=" + target.toShortString());
            return InteractionResult.FAIL;
        }
        BotManager.assignSoftPathProbe(bot, target);
        send(player, "[alice] soft-path-test: support=" + support.toShortString()
                + " foot=" + target.toShortString() + " -> " + bot.getName().getString());
        com.dddgn.alice.log.BotLog.info("软路径点击探针: player={} support={} foot={} bot={}",
                player == null ? "console" : player.getName().getString(), support.toShortString(),
                target.toShortString(), bot.getName().getString());
        return InteractionResult.SUCCESS;
    }

    private static void send(Player player, String message) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(message));
        }
    }
}
