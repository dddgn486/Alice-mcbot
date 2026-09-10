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
     * 夹具职责：确保 bot **快捷栏（0..8）**里有一把斧子（原木的正确工具：破坏速度 8 而非 1）。
     *
     * <p>只填空格，**不动选中槽**——`MineTask` 的夹具补镐写的是选中槽，会覆盖那里。
     * 破坏时 `BlockBreakSession.switchToBestToolFor` 会自动切到最合适的工具。
     *
     * <p>**2026-09-10 修正（客户端实测"bot 还在用镐子挖"）**：原实现在快捷栏 9 格全满时
     * 退化到 `inventory.add(...)` —— 斧子落进**主背包（9..35）**，而工具选择
     * `BlockInteraction.findBestToolSlot` **只扫快捷栏 0..8**（对照 Baritone
     * `MovementHelper.switchToBestToolFor` 同为快捷栏范围）→ 斧子永远选不到，
     * 原木实际用镐破坏：实测 `block_break_done ticks=61`（= 2.0×1.5÷1.0×20，速度 1.0），
     * 正确应为 ~8 tick。bot 跑过多轮回归后快捷栏被圆石塞满，所以这条只在"跑了一阵之后"出现。
     *
     * <p>现在保证斧子**一定进快捷栏**：优先空格 → 否则把最没用的一格（对木材速度 ≤1 且非斧）
     * 挪进主背包再放斧。腾不出主背包空间时如实覆盖并告警，不再静默放进选不到的地方。
     */
    private static void ensureAxe(BotPlayer bot) {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.getItem(slot).is(net.minecraft.tags.ItemTags.AXES)) {
                com.dddgn.alice.log.BotLog.info("[LumberJobItem] axe 已在快捷栏 slot={}（沿用）", slot);
                return;
            }
        }
        ItemStack axe = new ItemStack(net.minecraft.world.item.Items.DIAMOND_AXE);
        for (int slot = 0; slot < 9; slot++) {
            if (slot != inventory.selected && inventory.getItem(slot).isEmpty()) {
                inventory.setItem(slot, axe);
                com.dddgn.alice.log.BotLog.info("[LumberJobItem] axe 放入快捷栏空格 slot={}", slot);
                return;
            }
        }
        // 快捷栏全满：把最没用的一格（非斧、对木材也帮不上忙）挪去主背包，腾位置给斧子
        for (int slot = 0; slot < 9; slot++) {
            if (slot == inventory.selected) {
                continue;
            }
            ItemStack old = inventory.getItem(slot);
            if (old.is(net.minecraft.tags.ItemTags.AXES)) {
                continue;
            }
            if (old.getDestroySpeed(net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState()) > 1.0F) {
                continue;   // 对木材有用（斧类/其它有效工具）→ 不动
            }
            inventory.setItem(slot, axe);
            boolean stashed = stashIntoMain(inventory, old);
            com.dddgn.alice.log.BotLog.warn("[LumberJobItem] 快捷栏已满：slot={} 的 {} 让位给斧（{}）",
                    slot, old.getHoverName().getString(), stashed ? "已存入主背包" : "主背包也满，丢弃");
            return;
        }
        com.dddgn.alice.log.BotLog.warn("[LumberJobItem] 无法腾出快捷栏放斧：所有格都对木材有用，维持原状");
    }

    /** 把一叠物品塞进主背包（9..35）的第一个空位；无空位返回 false。 */
    private static boolean stashIntoMain(net.minecraft.world.entity.player.Inventory inventory, ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        for (int slot = 9; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                inventory.setItem(slot, stack);
                return true;
            }
        }
        return false;
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

        ensureAxe(bot);

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
