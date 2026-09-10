package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 夹具共享工具：保证 bot **快捷栏（0..8）**里有工具或一次性方块。
 *
 * <p>**为什么必须是快捷栏**：`BlockInteraction.findPlaceableSlot` / `countThrowaway` /
 * `findBestToolSlot` 与 Baritone 同口径，**都只扫 0..8**。物品一旦落进主背包（9..35）就
 * **永远选不到**——这个病灶已出现三次：D-089（斧子落主背包 → 全程用镐，`ticks=61` vs `8`）、
 * D-099（一次性方块落主背包 → `PILLAR`/`PLACE_STEP`/`FALL` 生成不出来、寻路回归 5 个场景
 * `UNREACHABLE`）。故在此收敛为一处实现。
 *
 * <p>**策略（2026-09-10 用户裁定：夹具就是"创造背包"逻辑，强制替换无妨）**：
 * <ol>
 *   <li>快捷栏已够 → 完事；</li>
 *   <li>否则把**主背包**里已有的同种物品搬进快捷栏（先并进同类栈，再放空格）；</li>
 *   <li>还不足 → 填空格；没有空格就**直接覆盖**第一个非选中格
 *       （被覆盖的栈尽量塞回主背包，塞不下就丢弃并告警）。</li>
 * </ol>
 * 不做"这格有没有用"的讲究——测试阶段保证夹具前置比保全杂物重要。
 */
public final class FixtureToolKit {

    private FixtureToolKit() {
    }

    /** 工具版：保证快捷栏里有至少一件匹配的工具（斧/镐…）。 */
    public static void ensureHotbarTool(BotPlayer bot, Supplier<ItemStack> tool,
                                        Predicate<ItemStack> isTool, String label) {
        ensureHotbarStack(bot, tool, isTool, 1, label);
    }

    /** 保证快捷栏里至少有 {@code minCount} 个匹配物品。 */
    public static void ensureHotbarStack(BotPlayer bot, Supplier<ItemStack> sample,
                                         Predicate<ItemStack> isMatch, int minCount, String label) {
        Inventory inventory = bot.getInventory();
        int have = countInHotbar(inventory, isMatch);
        if (have >= minCount) {
            BotLog.info("[FixtureTool] {} 快捷栏已有 {}（≥{}）", label, have, minCount);
            return;
        }
        // ② 主背包里有同种物品 → 搬进快捷栏
        if (pullFromMain(inventory, isMatch)) {
            have = countInHotbar(inventory, isMatch);
            if (have >= minCount) {
                BotLog.info("[FixtureTool] {} 已从主背包搬入快捷栏（现有 {}）", label, have);
                return;
            }
        }
        // ③ 填空格；没有空格就强制覆盖一个非选中格
        int need = minCount - have;
        int slot = firstEmptyHotbarSlot(inventory);
        if (slot >= 0) {
            inventory.setItem(slot, withCount(sample, need));
            BotLog.info("[FixtureTool] {} 放入快捷栏空格 slot={} ×{}", label, slot, need);
            return;
        }
        slot = firstNonSelectedSlot(inventory);
        ItemStack displaced = inventory.getItem(slot);
        boolean stashed = stashIntoMain(inventory, displaced);
        inventory.setItem(slot, withCount(sample, need));
        BotLog.warn("[FixtureTool] 快捷栏已满：slot={} 的 {} 被 {}×{} **强制覆盖**（{}）",
                slot, displaced.isEmpty() ? "空" : displaced.getHoverName().getString(),
                label, need, stashed ? "旧物已存入主背包" : "主背包也满，旧物丢弃");
    }

    // ==================== 内部 ====================

    private static int countInHotbar(Inventory inventory, Predicate<ItemStack> isMatch) {
        int total = 0;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isMatch.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * 把主背包（9..35）里的同种物品搬进快捷栏：**先并进同类栈，再放空格**。
     *
     * @return 是否搬动了任何东西
     */
    private static boolean pullFromMain(Inventory inventory, Predicate<ItemStack> isMatch) {
        boolean moved = false;
        for (int slot = 9; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!isMatch.test(stack) || stack.isEmpty()) {
                continue;
            }
            // 先并入快捷栏里的同类栈
            for (int hot = 0; hot < 9 && !stack.isEmpty(); hot++) {
                ItemStack target = inventory.getItem(hot);
                if (!isMatch.test(target) || target.getCount() >= target.getMaxStackSize()) {
                    continue;
                }
                int space = target.getMaxStackSize() - target.getCount();
                int move = Math.min(space, stack.getCount());
                target.grow(move);
                stack.shrink(move);
                moved = true;
            }
            if (stack.isEmpty()) {
                inventory.setItem(slot, ItemStack.EMPTY);
                continue;
            }
            // 再找快捷栏空格整叠搬入
            int empty = firstEmptyHotbarSlot(inventory);
            if (empty >= 0) {
                inventory.setItem(empty, stack.copy());
                inventory.setItem(slot, ItemStack.EMPTY);
                moved = true;
            }
        }
        return moved;
    }

    private static int firstEmptyHotbarSlot(Inventory inventory) {
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    private static int firstNonSelectedSlot(Inventory inventory) {
        for (int slot = 0; slot < 9; slot++) {
            if (slot != inventory.selected) {
                return slot;
            }
        }
        return 0;
    }

    /** 把一叠物品塞进主背包（9..35）的第一个空位；无空位返回 false。 */
    private static boolean stashIntoMain(Inventory inventory, ItemStack stack) {
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

    private static ItemStack withCount(Supplier<ItemStack> sample, int count) {
        ItemStack stack = sample.get();
        stack.setCount(count);
        return stack;
    }
}
