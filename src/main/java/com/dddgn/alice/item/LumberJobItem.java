package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.LumberCourseAnchor;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * 伐木 Job 启动器（{@code alice:lumber_job}，L3/D-080，切片 J1）：普通右键，零参数。
 *
 * <p>在场景固定起点附近扫描树木，**按配额循环**砍完并收集入包；决策与终态走 {@code [Job]} 决策日志。
 * 配额取自物品默认值（J2：**2 棵**，覆盖循环 / 逐树记账 / 终止语义，见 {@code JOB_LAYER_DESIGN.md} §6.2c）。
 */
public class LumberJobItem extends Item {

    public LumberJobItem(Properties properties) {
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
    public InteractionResultHolder<ItemStack> use(Level level, net.minecraft.world.entity.player.Player player,
                                                  InteractionHand hand) {
        if (level.isClientSide) {
            return InteractionResultHolder.success(player.getItemInHand(hand));
        }
        start(player, (ServerLevel) level);
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    /**
     * 夹具职责：确保 bot 快捷栏里有一把斧子（原木的正确工具：破坏速度 8 而非 1）。
     *
     * <p>实现委托 {@link FixtureToolKit}（D-089 的教训已收敛为一处共享逻辑：
     * 工具必须进**快捷栏**，因为工具选择只扫 0..8；退化到主背包就永远选不到）。
     */
    private static void ensureAxe(BotPlayer bot) {
        FixtureToolKit.ensureHotbarTool(bot,
                () -> new ItemStack(net.minecraft.world.item.Items.DIAMOND_AXE),
                stack -> stack.is(net.minecraft.tags.ItemTags.AXES),
                "axe");
    }

    /**
     * 夹具职责：一次性方块（J7 Step 2 起，够不到的树上原木要靠**贴着树干搭柱子**爬上去）。
     *
     * <p>没有它，`PILLAR` 的规划前提"快捷栏里有可放置的一次性方块"就不成立，
     * 攀爬兜底会如实回落到 `:no_stand`（不是缺陷，但测不出攀爬）。
     */
    /**
     * 夹具职责：石镐。J7 起伐木会**搭柱子加高**、并在建拆同权阶段**把柱子拆回来**——
     * 拆的是我们自己放的圆石，没有镐就是徒手 200 tick/块（2026-09-11 实测：30 次 ~200 tick 的破块）。
     */
    private static void ensurePickaxe(BotPlayer bot) {
        FixtureToolKit.ensureHotbarTool(bot,
                () -> new ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE),
                stack -> stack.is(net.minecraft.tags.ItemTags.PICKAXES),
                "pickaxe");
    }

    private static void ensureThrowaway(BotPlayer bot, int count) {
        FixtureToolKit.ensureHotbarStack(bot,
                () -> new ItemStack(net.minecraft.world.item.Items.COBBLESTONE),
                stack -> stack.is(net.minecraft.world.item.Items.COBBLESTONE),
                count, "cobblestone");
    }

    private InteractionResult start(net.minecraft.world.entity.player.Player player, ServerLevel level) {
        BotPlayer bot = BotManager.firstInLevel(level);
        if (bot == null) {
            bot = BotManager.firstOrSpawn(level, LumberCourseAnchor.START_FOOT);
        }
        if (bot == null) {
            if (player != null) {
                player.sendSystemMessage(Component.literal("[alice] bot 生成失败，请检查日志"));
            }
            return InteractionResult.SUCCESS;
        }
        // 夹具职责：把 bot 放进场景起点。场景是孤立平台，上一轮的位置到此**没有可行走路径**，
        // 若不传送，每个子任务都会在规划阶段如实报 found_but_unminable（J1 首测即为此故障）。
        bot.teleportTo(level, LumberCourseAnchor.START_FOOT.getX() + 0.5D,
                LumberCourseAnchor.START_FOOT.getY(), LumberCourseAnchor.START_FOOT.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();

        // 夹具职责（用户 2026-09-11 建议）：**先清空背包**再发料 —— ①避免 bot 背包爆满；
        // ②让"原木增量（逐树 harvest 判据）"这类账目干净。清空后一律广播主手（D-110）。
        FixtureToolKit.resetInventory(bot);
        ensureAxe(bot);
        ensurePickaxe(bot);         // 拆自己搭的圆石柱要用镐（2026-09-11 实测教训）
        ensureThrowaway(bot, 12);   // 攀爬兜底的方块预算（D-109）

        ServerPlayer observer = player instanceof ServerPlayer sp ? sp : null;
        if (!BotManager.assignLumberJob(bot, observer)) {
            if (player != null) {
                player.sendSystemMessage(Component.literal("[alice] bot 正忙，稍后再试"));
            }
            return InteractionResult.SUCCESS;
        }
        if (player != null) {
            player.sendSystemMessage(Component.literal("[alice] 伐木 Job 启动 bot="
                    + bot.getName().getString() + "（选一棵树砍完并收集）"));
        }
        return InteractionResult.SUCCESS;
    }
}
