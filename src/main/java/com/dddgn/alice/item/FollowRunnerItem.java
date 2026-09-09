package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * Follow 迁移入口（{@code alice:follow_runner}，D-062）：
 * 第一次右键启动跟随（目标 = 点击的玩家）；再次右键停止并输出 `[Follow] SUMMARY`。
 */
public class FollowRunnerItem extends Item {
    /** 夹具：bot 距离玩家超过该距离时，启动前把它放到玩家脚位（测试便利，记日志）。 */
    private static final double FIXTURE_TELEPORT_DISTANCE = 6.0D;

    public FollowRunnerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        return start(context.getPlayer(), (ServerLevel) level);
    }

    @Override
    public net.minecraft.world.InteractionResultHolder<ItemStack> use(
            Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) {
            return net.minecraft.world.InteractionResultHolder.success(player.getItemInHand(hand));
        }
        start(player, (ServerLevel) level);
        return net.minecraft.world.InteractionResultHolder.success(player.getItemInHand(hand));
    }

    private InteractionResult start(Player player, ServerLevel level) {
        BotPlayer bot = BotManager.firstInLevel(level);
        if (bot == null && player != null) {
            bot = BotManager.firstOrSpawn(level, player.blockPosition());
        }
        if (bot == null) {
            if (player != null) {
                player.sendSystemMessage(Component.literal("[alice] bot 生成失败，请检查日志"));
            }
            return InteractionResult.SUCCESS;
        }
        // 第二次右键：停止跟随并汇报摘要
        if (BotManager.stopFollow(bot)) {
            if (player != null) {
                player.sendSystemMessage(Component.literal("[alice] 已停止跟随（见日志 [Follow] SUMMARY）"));
            }
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer target)) {
            return InteractionResult.SUCCESS;
        }
        if (bot.distanceToSqr(target) > FIXTURE_TELEPORT_DISTANCE * FIXTURE_TELEPORT_DISTANCE) {
            BlockPos foot = target.blockPosition();
            bot.teleportTo(level, foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                    java.util.Set.of(), bot.getYRot(), bot.getXRot());
            bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            bot.controller().stopMovement();
            BotLog.info("[Follow] fixture_teleport bot={} to={}", bot.getName().getString(),
                    foot.toShortString());
        }
        if (!BotManager.assignFollow(bot, target)) {
            player.sendSystemMessage(Component.literal("[alice] bot 正忙，稍后再试"));
            return InteractionResult.SUCCESS;
        }
        player.sendSystemMessage(Component.literal("[alice] 跟随启动 bot=" + bot.getName().getString()
                + "（再点一次结束并输出 SUMMARY）"));
        return InteractionResult.SUCCESS;
    }
}
