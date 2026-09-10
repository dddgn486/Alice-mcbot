package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 夹具共享工具：保证 bot **快捷栏**里有某件工具（D-089 的教训收敛到一处）。
 *
 * <p>为什么必须进快捷栏：工具选择 `BlockInteraction.findBestToolSlot` **只扫快捷栏 0..8**
 * （与 Baritone `MovementHelper.switchToBestToolFor` 同范围）。一旦退化到 `inventory.add(...)`，
 * 物品会落进主背包（9..35）而**永远选不到**——实测症状是"手里看着是镐、实际按空手速度挖"
 * （原木 61 tick vs 应为 8 tick）。
 *
 * <p>策略（三步，均带日志）：① 已有则沿用 → ② 有空格则放入 → ③ 全满则把"对目标也没用"的一格
 * 挪进主背包再放工具。腾不出空间就告警，**不再静默放进选不到的地方**。
 */
public final class FixtureToolKit {

    private FixtureToolKit() {
    }

    /**
     * @param tool     要保证存在的工具（每次调用现造一份新栈，避免复用同一实例）
     * @param isTool   判定"快捷栏里已有的这件算不算同类工具"
     * @param label    日志标签（如 {@code axe}）
     */
    public static void ensureHotbarTool(BotPlayer bot, Supplier<ItemStack> tool,
                                        Predicate<ItemStack> isTool, String label) {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            if (isTool.test(inventory.getItem(slot))) {
                BotLog.info("[FixtureTool] {} 已在快捷栏 slot={}（沿用）", label, slot);
                return;
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            if (slot != inventory.selected && inventory.getItem(slot).isEmpty()) {
                inventory.setItem(slot, tool.get());
                BotLog.info("[FixtureTool] {} 放入快捷栏空格 slot={}", label, slot);
                return;
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            if (slot == inventory.selected) {
                continue;
            }
            ItemStack old = inventory.getItem(slot);
            if (isTool.test(old)) {
                continue;
            }
            // 对目标也帮不上忙（不是任何工具）→ 让位
            if (old.getDestroySpeed(Blocks.OAK_LOG.defaultBlockState()) > 1.0F
                    || old.getDestroySpeed(Blocks.STONE.defaultBlockState()) > 1.0F) {
                continue;
            }
            inventory.setItem(slot, tool.get());
            boolean stashed = stashIntoMain(inventory, old);
            BotLog.warn("[FixtureTool] 快捷栏已满：slot={} 的 {} 让位给 {}（{}）",
                    slot, old.getHoverName().getString(), label,
                    stashed ? "已存入主背包" : "主背包也满，丢弃");
            return;
        }
        BotLog.warn("[FixtureTool] 无法腾出快捷栏放 {}：所有格都对目标有用，维持原状", label);
    }

    /** 把一叠物品塞进主背包（9..35）的第一个空位；无空位返回 false。 */
    private static boolean stashIntoMain(net.minecraft.world.entity.player.Inventory inventory,
                                         ItemStack stack) {
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
}
