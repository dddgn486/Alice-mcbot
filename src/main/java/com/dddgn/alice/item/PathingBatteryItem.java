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
 * R3 一键自检电池（{@code alice:pathing_battery}）：右键任意方块即开跑。
 *
 * <p>这是**场景专属测试器**：只负责启动测试，测试起点是课程固定起点
 * {@link #COURSE_START_FOOT}（由 `/function alice_test:pathing_course` 生成的场景），
 * 与玩家站在哪里无关。已有 bot 会被传送到固定起点，没有 bot 会自动生成在固定起点。
 *
 * <p>一次动作覆盖：规划检查（平地/上升/预算边界）+ 执行检查（TRAVERSE / DIAGONAL /
 * ASCEND / DESCEND / 2 段 DESCEND 链）。地形不支持的项记为 SKIP。
 */
public class PathingBatteryItem extends Item {

    /** 路径课程固定起点脚位；必须与 `pathing_course.mcfunction` 保持一致。 */
    public static final BlockPos COURSE_START_FOOT = new BlockPos(0, 64, 46);

    public PathingBatteryItem(Properties properties) {
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
            return net.minecraft.world.InteractionResultHolder
                    .success(player.getItemInHand(hand));
        }
        start(player, (ServerLevel) level);
        return net.minecraft.world.InteractionResultHolder.success(player.getItemInHand(hand));
    }

    private InteractionResult start(Player player, ServerLevel level) {
        BlockPos anchor = COURSE_START_FOOT;
        // 已有 bot 则复用（任务会把它锚定到固定起点）；没有 bot 则自动生成在固定起点
        BotPlayer bot = BotManager.firstInLevel(level);
        if (bot == null) {
            bot = BotManager.firstOrSpawn(level, anchor);
        }
        if (bot == null) {
            if (player != null) {
                player.sendSystemMessage(Component.literal("[alice] bot 生成失败，请检查日志"));
            }
            return InteractionResult.SUCCESS;
        }
        if (!BotManager.assignPathingBattery(bot, anchor)) {
            if (player != null) {
                player.sendSystemMessage(Component.literal("[alice] bot 正忙，稍后再试"));
            }
            return InteractionResult.SUCCESS;
        }
        if (player != null) {
            player.sendSystemMessage(Component.literal("[alice] R3 自检电池开跑 bot="
                    + bot.getName().getString() + " 固定起点=" + anchor.toShortString()
                    + "（规划 + 全部 Movement + 链，约 8 秒）"));
        }
        return InteractionResult.SUCCESS;
    }
}
