package com.dddgn.alice.bot;

import com.dddgn.alice.log.BotLog;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * **工具供给的确定性事实 + 安全动作**（基-9）。
 *
 * <p>为什么要有它（现状 2026-09-12 复核）：
 * <ul>
 *   <li>**生产路径完全不读耐久** —— 只有 S4 的 `TOOL_LOW` 事件在看；正在挖/砍的任务既不避险也不换工具，
 *       工具磨没了才失败；</li>
 *   <li>**工具在主背包就永远选不到** —— `MineTask` 只打一行 `tool_in_main_inventory` 警告
 *       （D-089/D-099 同一病灶第三次同形），没人把它搬进快捷栏；</li>
 *   <li>**没有"维护工具"这个动作** —— `TOOL_LOW` 事件把 LLM 招来时，词汇表里没有可做的事，
 *       它只能瞎起一个 Job（实测：`event:TOOL_LOW → start_job region_lumber`）。</li>
 * </ul>
 *
 * <p>本类只做**不写世界、不耗资源**的事：读事实、把已经拥有的工具搬进快捷栏（背包内移动）。
 * "工具从哪来"（合成/取材料）属于 S6 与请示通道，**不在**这里偷偷做。
 */
public final class ToolSupply {

    /** 工具种类（判定用标签，与原版 `ItemTags` 对齐；模组工具只要挂标签就能被认出来）。 */
    public enum Kind {
        PICKAXE("镐", stack -> stack.is(ItemTags.PICKAXES) || stack.getItem() instanceof PickaxeItem),
        AXE("斧", stack -> stack.is(ItemTags.AXES) || stack.getItem() instanceof AxeItem),
        SHOVEL("锹", stack -> stack.is(ItemTags.SHOVELS) || stack.getItem() instanceof ShovelItem),
        SWORD("剑", stack -> stack.is(ItemTags.SWORDS) || stack.getItem() instanceof SwordItem);

        private final String label;
        private final Predicate<ItemStack> matcher;

        Kind(String label, Predicate<ItemStack> matcher) {
            this.label = label;
            this.matcher = matcher;
        }

        public String label() {
            return label;
        }

        public boolean matches(ItemStack stack) {
            return !stack.isEmpty() && matcher.test(stack);
        }
    }

    /** 快照：某类工具"最好的一件"在哪、还剩多少耐久。 */
    public record Snapshot(Kind kind, boolean present, boolean inHotbar, int slot, String name,
                           int remaining, int max, int spareInMain, int bestMainRemaining) {
        /** 剩余耐久比例（无工具 = 0）。 */
        public double ratio() {
            return max <= 0 ? 0.0D : (double) remaining / max;
        }

        public boolean low(float threshold) {
            return present && ratio() <= threshold;
        }

        public String describe() {
            if (!present) {
                return kind.label() + "=无";
            }
            return kind.label() + "=" + name + " " + remaining + "/" + max
                    + String.format(Locale.ROOT, "(%.0f%%)", ratio() * 100)
                    + (inHotbar ? " 快捷栏" : " **在主背包（选不到）**")
                    + (spareInMain > 0 ? " 主背包另有 " + spareInMain + " 件"
                            + (bestMainRemaining > remaining ? "（其中更好）" : "") : "");
        }
    }

    public static final int HOTBAR_SIZE = 9;

    private ToolSupply() {
    }

    /**
     * 取某类工具的**当前可用状况**：快捷栏优先（"现在手上/顺手能用什么"），快捷栏没有才看主背包。
     *
     * <p>为什么快捷栏优先而不是"耐久优先"：生产路径（`MineTask` 等）**只从快捷栏选工具**，
     * 所以"能用什么"的答案必须按这个口径给；"还能换成什么"由 {@link #bestInMain} 单独回答。
     */
    public static Snapshot inspect(BotPlayer bot, Kind kind) {
        var inventory = bot.getInventory();
        Slot bestHotbar = bestInRange(bot, kind, 0, HOTBAR_SIZE);
        Slot bestMain = bestInRange(bot, kind, HOTBAR_SIZE, inventory.getContainerSize());
        // 语义：**主背包里这类工具的总件数**（不去减"最好那件"——那是调用方自己该算的）
        int spareInMain = countInRange(bot, kind, HOTBAR_SIZE, inventory.getContainerSize());
        Slot chosen = bestHotbar.slot() >= 0 ? bestHotbar : bestMain;
        if (chosen.slot() < 0) {
            return new Snapshot(kind, false, false, -1, "", 0, 0, 0, 0);
        }
        ItemStack stack = inventory.getItem(chosen.slot());
        int max = stack.isDamageableItem() ? stack.getMaxDamage() : 0;
        int remaining = max > 0 ? max - stack.getDamageValue() : 0;
        return new Snapshot(kind, true, chosen.slot() < HOTBAR_SIZE, chosen.slot(),
                stack.getHoverName().getString(), remaining, max, spareInMain,
                Math.max(0, bestMain.remaining()));
    }

    /** 主背包（9..35）里最好的一件（"还能换成什么"）。{@code slot < 0} = 没有。 */
    public static Slot bestInMain(BotPlayer bot, Kind kind) {
        return bestInRange(bot, kind, HOTBAR_SIZE, bot.getInventory().getContainerSize());
    }

    /** 快捷栏（0..8）里最好的一件。 */
    public static Slot bestInHotbar(BotPlayer bot, Kind kind) {
        return bestInRange(bot, kind, 0, HOTBAR_SIZE);
    }

    /** 区间内剩余耐久最多的一件（不可损伤的按"完好"计）。 */
    static Slot bestInRange(BotPlayer bot, Kind kind, int from, int to) {
        var inventory = bot.getInventory();
        int bestSlot = -1;
        int bestRemaining = -1;
        for (int slot = Math.max(0, from); slot < Math.min(to, inventory.getContainerSize()); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!kind.matches(stack)) {
                continue;
            }
            int remaining = stack.isDamageableItem()
                    ? stack.getMaxDamage() - stack.getDamageValue()
                    : Integer.MAX_VALUE / 2;
            if (remaining > bestRemaining) {
                bestRemaining = remaining;
                bestSlot = slot;
            }
        }
        if (bestSlot < 0) {
            return new Slot(-1, 0);
        }
        ItemStack stack = inventory.getItem(bestSlot);
        int max = stack.isDamageableItem() ? stack.getMaxDamage() : 0;
        return new Slot(bestSlot, max > 0 ? max - stack.getDamageValue() : 0);
    }

    private static int countInRange(BotPlayer bot, Kind kind, int from, int to) {
        var inventory = bot.getInventory();
        int count = 0;
        for (int slot = Math.max(0, from); slot < Math.min(to, inventory.getContainerSize()); slot++) {
            if (kind.matches(inventory.getItem(slot))) {
                count++;
            }
        }
        return count;
    }

    /** 槽位 + 该槽位剩余耐久（`slot < 0` 表示没有）。 */
    public record Slot(int slot, int remaining) {
    }

    /** 全部种类的状况（顺序稳定，便于日志/汇报对比）。 */
    public static Map<Kind, Snapshot> inspectAll(BotPlayer bot) {
        Map<Kind, Snapshot> map = new LinkedHashMap<>();
        for (Kind kind : Kind.values()) {
            map.put(kind, inspect(bot, kind));
        }
        return map;
    }

    /** 一行式事实（决策层 prompt 与汇报共用同一份文本，避免两处口径不一致）。 */
    public static String describe(BotPlayer bot) {
        StringBuilder builder = new StringBuilder();
        for (Snapshot snapshot : inspectAll(bot).values()) {
            if (!builder.isEmpty()) {
                builder.append(" | ");
            }
            builder.append(snapshot.describe());
        }
        return builder.toString();
    }

    /**
     * **把主背包里最好的一件该类工具搬进快捷栏**（背包内移动，不写世界、不耗资源）。
     *
     * <p>策略：优先放空格；否则**替换手上的工具**（前提：手上那件也是同类 —— 不把无关物品挤掉）。
     *
     * @return 结果码：`already_in_hotbar` / `moved_to_empty` / `swapped_with_held` / `no_tool_in_main` / `hotbar_full_no_swap`
     */
    public static String promoteFromMain(BotPlayer bot, Kind kind) {
        var inventory = bot.getInventory();
        Slot main = bestInMain(bot, kind);
        if (main.slot() < 0) {
            return bestInHotbar(bot, kind).slot() < 0 ? "no_tool" : "already_in_hotbar";
        }
        Slot hotbar = bestInHotbar(bot, kind);
        // 手上（快捷栏）那把更好或一样好 ⇒ 什么都不用做
        if (hotbar.slot() >= 0 && hotbar.remaining() >= main.remaining()) {
            return "already_in_hotbar";
        }
        // ① 快捷栏有同类可替换（那把更差）⇒ 对调：好的进来、差的回主背包
        if (hotbar.slot() >= 0) {
            ItemStack better = inventory.getItem(main.slot());
            ItemStack worse = inventory.getItem(hotbar.slot());
            inventory.setItem(hotbar.slot(), better);
            inventory.setItem(main.slot(), worse);
            sync(bot);
            BotLog.info("[ToolSupply] {} 对调：主背包 slot={}（剩 {}）↔ 快捷栏 slot={}（剩 {}）",
                    kind.label(), main.slot(), main.remaining(), hotbar.slot(), hotbar.remaining());
            return "swapped_with_worn";
        }
        // ② 快捷栏没有同类 ⇒ 放空格；没有空格时不挤占无关物品（如实记录）
        int emptySlot = firstEmptyHotbar(inventory);
        if (emptySlot < 0) {
            BotLog.warn("[ToolSupply] {} 在主背包 slot={} 但快捷栏没有空格也没有同类可替换 ⇒ 不挤占（如实记录）",
                    kind.label(), main.slot());
            return "hotbar_full_no_swap";
        }
        inventory.setItem(emptySlot, inventory.getItem(main.slot()));
        inventory.setItem(main.slot(), ItemStack.EMPTY);
        sync(bot);
        BotLog.info("[ToolSupply] {} 从主背包 slot={} 搬入快捷栏 slot={}", kind.label(),
                main.slot(), emptySlot);
        return "moved_to_empty";
    }

    private static int firstEmptyHotbar(net.minecraft.world.entity.player.Inventory inventory) {
        for (int slot = 0; slot < HOTBAR_SIZE && slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    /** 唯一收敛点：背包改动后广播主手（D-110），否则客户端继续渲染旧手持物。 */
    private static void sync(BotPlayer bot) {
        BotManager.syncMainHand(bot);
    }

    /** 快捷栏里是否**任何**工具都没有，而主背包里有（`MineTask` 只警告不修的那个病灶）。 */
    public static boolean hasToolOnlyInMain(BotPlayer bot) {
        for (Kind kind : Kind.values()) {
            Snapshot snapshot = inspect(bot, kind);
            if (snapshot.present() && !snapshot.inHotbar()) {
                return true;
            }
        }
        return false;
    }
}
