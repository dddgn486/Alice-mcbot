package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.job.mine.MineCandidateSource;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.task.OreCourseAnchor;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 挖掘 Job 启动器（{@code alice:mine_job}，切片 J5）：普通右键，零参数。
 *
 * <p>在 {@code ore_course} 场景里跑一次**真实 {@link com.dddgn.alice.job.mine.MineJob}**：
 * 目标是裸露铁矿、配额 4 处（共 6 处），验证"L3 不只服务伐木"——
 * 决策 trace、选择策略、配额、终止语义与伐木**完全同一套**。
 *
 * <p>与命令入口的分工：`/alice auto-mine <tag|block> [count]` 是通用入口（任意目标、任意世界）；
 * 本物品是**零参数夹具入口**，用于可重复的自动验收。
 */
public class MineJobItem extends Item {

    /** 配额：4 处（场景共 6 处，留 2 处不挖以证明"到配额即停"）。 */
    private static final int QUOTA = 4;

    public MineJobItem(Properties properties) {
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
            bot = BotManager.firstOrSpawn(level, OreCourseAnchor.START_FOOT);
        }
        if (bot == null) {
            say(player, "[alice] bot 生成失败，请检查日志");
            return;
        }
        // 夹具职责：放进场景起点（场景是孤立平台，不传送则一切规划都不可达）
        bot.teleportTo(level, OreCourseAnchor.START_FOOT.getX() + 0.5D,
                OreCourseAnchor.START_FOOT.getY(), OreCourseAnchor.START_FOOT.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        // 夹具职责：保证快捷栏里有镐（D-089 教训：工具必须进快捷栏才选得到）
        FixtureToolKit.ensureHotbarTool(bot,
                () -> new ItemStack(Items.DIAMOND_PICKAXE),
                stack -> stack.is(net.minecraft.tags.ItemTags.PICKAXES),
                "pickaxe");

        ServerPlayer observer = player instanceof ServerPlayer sp ? sp : null;
        var target = MineCandidateSource.Target.ofBlock(net.minecraft.world.level.block.Blocks.IRON_ORE);
        if (!BotManager.assignMineJob(bot, observer, target, QUOTA, MineCandidateSource.SCAN_RADIUS)) {
            say(player, "[alice] bot 正忙，稍后再试");
            return;
        }
        BotLog.info("[MineJobItem] 启动 mine Job target={} quota={}", target.describe(), QUOTA);
        say(player, "[alice] 挖掘 Job 启动 bot=" + bot.getName().getString()
                + "（目标 " + target.describe() + " × " + QUOTA + "）");
    }

    private static void say(net.minecraft.world.entity.player.Player player, String message) {
        if (player != null) {
            player.sendSystemMessage(Component.literal(message));
        }
    }
}
