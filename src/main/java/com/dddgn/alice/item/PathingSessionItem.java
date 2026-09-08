package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * R4 路径会话测试器（{@code alice:pathing_session}）：场景专属，右键即启动。
 *
 * <p>只负责启动：把 bot 锚定到课程固定起点，规划到课程固定目标（2 段下降链底部），
 * 然后由 {@code PathSession} 逐段执行并报告结构化结果。
 */
public class PathingSessionItem extends Item {

    /** 固定起点，与 pathing_course.mcfunction / PathingBatteryItem 一致。 */
    public static final BlockPos COURSE_START_FOOT = new BlockPos(0, 64, 46);
    /** 固定目标：课程 2 段下降链底部。 */
    public static final BlockPos COURSE_GOAL_FOOT = new BlockPos(0, 62, 44);

    public PathingSessionItem(Properties properties) {
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
    public net.minecraft.world.InteractionResultHolder<net.minecraft.world.item.ItemStack> use(
            Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) {
            return net.minecraft.world.InteractionResultHolder.success(player.getItemInHand(hand));
        }
        start(player, (ServerLevel) level);
        return net.minecraft.world.InteractionResultHolder.success(player.getItemInHand(hand));
    }

    private InteractionResult start(Player player, ServerLevel level) {
        BotPlayer bot = BotManager.firstInLevel(level);
        if (bot == null) {
            bot = BotManager.firstOrSpawn(level, COURSE_START_FOOT);
        }
        if (bot == null) {
            if (player != null) {
                player.sendSystemMessage(Component.literal("[alice] bot 生成失败，请检查日志"));
            }
            return InteractionResult.SUCCESS;
        }
        // 传送重载会同时同步头部朝向（3 参重载不会），避免假人头身不一致
        bot.teleportTo(level, COURSE_START_FOOT.getX() + 0.5D, COURSE_START_FOOT.getY(),
                COURSE_START_FOOT.getZ() + 0.5D, java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
        if (!BotManager.assignPathSessionDiagnostic(bot, COURSE_GOAL_FOOT)) {
            if (player != null) {
                player.sendSystemMessage(Component.literal("[alice] bot 正忙，稍后再试"));
            }
            return InteractionResult.SUCCESS;
        }
        if (player != null) {
            player.sendSystemMessage(Component.literal("[alice] R4 路径会话启动 bot="
                    + bot.getName().getString() + " 起点=" + COURSE_START_FOOT.toShortString()
                    + " → 目标=" + COURSE_GOAL_FOOT.toShortString()
                    + "（先规划、再逐段执行），结果见聊天/日志"));
        }
        return InteractionResult.SUCCESS;
    }
}
