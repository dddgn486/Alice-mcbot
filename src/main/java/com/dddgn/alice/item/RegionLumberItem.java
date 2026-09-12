package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.job.lumber.LumberRegionState;
import com.dddgn.alice.task.LumberCourseAnchor;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 可持续伐木区启动器（{@code alice:region_lumber}，J8 / §13）：普通右键，零参数。
 *
 * <p>把**测试场景的固定区域**（{@link LumberCourseAnchor#REGION_MIN}/{@link LumberCourseAnchor#REGION_MAX}）
 * 写进持久化的 {@code LumberRegionState} 并起 {@code RegionLumberJob}：巡查 → 挑一棵 → 复用一次性
 * 伐木 Job 砍它 → 回来继续巡查；区域里没有可砍的树且连续数次无活 ⇒ `idle_no_work`（如实待机）。
 *
 * <p>玩家停止：下任意 `/alice …` 指令即替换任务（§13.1：停止只由玩家命令触发）。
 */
public class RegionLumberItem extends Item {

    public RegionLumberItem(Properties properties) {
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
            bot = BotManager.firstOrSpawn(level, LumberCourseAnchor.START_FOOT);
        }
        if (bot == null || BotManager.isBusy(bot)) {
            say(player, bot == null ? "[alice] bot 生成失败" : "[alice] bot 正忙，稍后再试");
            return;
        }
        bot.teleportTo(level, LumberCourseAnchor.START_FOOT.getX() + 0.5D,
                LumberCourseAnchor.START_FOOT.getY(), LumberCourseAnchor.START_FOOT.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        var region = new LumberRegionState.Region(LumberCourseAnchor.REGION_MIN,
                LumberCourseAnchor.REGION_MAX);
        if (!BotManager.assignRegionLumber(bot, player instanceof ServerPlayer sp ? sp : null, region)) {
            say(player, "[alice] bot 正忙，稍后再试");
            return;
        }
        say(player, "[alice] 可持续伐木区已启动 bot=" + bot.getName().getString()
                + " 区域 " + region.describe() + "（巡查 → 砍 → 继续巡查；无活则 idle_no_work）");
    }

    private static void say(net.minecraft.world.entity.player.Player player, String text) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(text));
        }
    }
}
