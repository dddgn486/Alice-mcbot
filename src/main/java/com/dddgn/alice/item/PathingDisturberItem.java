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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * R4 自愈验证夹具（{@code alice:pathing_disturber}）：场景专属，右键即启动。
 *
 * <p>与 {@link PathingPlacerItem} 相同起终点，但会在执行中途把 bot **平移 1 格**
 * （模拟被推开/世界变化），用于确定性地触发 {@code *_STALE_START} 并验证
 * snipsnap / 重规划自愈（D-034）。
 */
public class PathingDisturberItem extends Item {

    public static final BlockPos COURSE_START_FOOT = new BlockPos(0, 64, 66);
    public static final BlockPos COURSE_GOAL_FOOT = new BlockPos(8, 62, 66);
    /** 扰动时机与位移（向南 1 格，仍在平台上，可被重规划恢复）。 */
    private static final int DISTURB_TICK = 30;
    private static final int DISTURB_DZ = 1;

    public PathingDisturberItem(Properties properties) {
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
        if (bot == null) {
            bot = BotManager.firstOrSpawn(level, COURSE_START_FOOT);
        }
        if (bot == null) {
            if (player != null) {
                player.sendSystemMessage(Component.literal("[alice] bot 生成失败，请检查日志"));
            }
            return InteractionResult.SUCCESS;
        }
        bot.teleportTo(level, COURSE_START_FOOT.getX() + 0.5D, COURSE_START_FOOT.getY(),
                COURSE_START_FOOT.getZ() + 0.5D, java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
        ensureCobblestone(bot, 8);
        if (!BotManager.assignPathSessionDiagnostic(bot, COURSE_GOAL_FOOT, true,
                DISTURB_TICK, 0, DISTURB_DZ)) {
            if (player != null) {
                player.sendSystemMessage(Component.literal("[alice] bot 正忙，稍后再试"));
            }
            return InteractionResult.SUCCESS;
        }
        if (player != null) {
            player.sendSystemMessage(Component.literal("[alice] R4 自愈验证启动 bot="
                    + bot.getName().getString() + " 起点=" + COURSE_START_FOOT.toShortString()
                    + " → 目标=" + COURSE_GOAL_FOOT.toShortString()
                    + "（第 " + DISTURB_TICK + " tick 会被平移 1 格，观察是否自愈）"));
        }
        return InteractionResult.SUCCESS;
    }

    private static void ensureCobblestone(BotPlayer bot, int count) {
        com.dddgn.alice.item.FixtureToolKit.ensureHotbarStack(bot,
                () -> new ItemStack(Items.COBBLESTONE),
                stack -> stack.is(Items.COBBLESTONE), count, "cobblestone");
    }
}
