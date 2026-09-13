package com.dddgn.alice.task.craft;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * **合成网格发现器**（阶段 3-A / S1-1，D-192）：从**活菜单**里认出"哪几格是合成网格、哪一格是结果槽"，
 * **只用原版 API、零模组知识**。
 *
 * <p>为什么需要它（用户 2026-09-13 的问题："是不是不应该硬编码合成的方式"）：现有 `InventoryCraft.inventorySpec()`
 * 与 `TableCraft.tableSpec()` 把槽位下标写成常量（来自 D-163/D-165 的布局记录）。只要换一个模组菜单
 * （精妙存储的"合成升级页签"、精妙背包、Refined Storage 的合成网格……），下标就完全不同 ——
 * 而它们在**类型**上是同一件东西：原版 `CraftingContainer`（网格）+ 原版 `ResultContainer`（结果）。
 * 所以判据从"记下标"改成"**认容器**"。
 *
 * <p>实测依据（三个已装模组，均已核对源码/容器类型）：
 * <ul>
 *   <li>随身 2×2（`InventoryMenu`）：矩阵 = `TransientCraftingContainer`，结果 = `ResultContainer`；</li>
 *   <li>工作台（`CraftingMenu`）：同上，3×3；</li>
 *   <li>精妙存储/精妙背包的合成升级页签（`CraftingUpgradeContainer`）：
 *       矩阵 = `CraftingItemHandler extends TransientCraftingContainer`，结果 = `ResultContainer`，
 *       槽位建在 **(-100,-100)**（渲染在屏幕外，见 `docs/MOD_COMPAT_CRAFT_STATION_PLAN.md` §1.4）；</li>
 *   <li>Refined Storage（`GridContainerMenu`）：矩阵 = `TransientCraftingContainer(3,3)`，结果 = `ResultContainer`。</li>
 * </ul>
 *
 * <p>**如实拒绝**（未知能力默认只读，不猜）：认不出就给码，绝不"取第一个像的"。
 */
public final class GridDiscovery {

    /** 失败码：每一种都对应"我们确实不知道"，不是"大概能用"。 */
    public static final class Codes {
        /** 菜单里没有任何 `CraftingContainer` 网格。 */
        public static final String NO_GRID = "no_grid";
        /** 菜单里没有 `ResultContainer` 结果槽。 */
        public static final String NO_RESULT_SLOT = "no_result_slot";
        /** 菜单里有**多个不同的**网格容器（谁是目标？不猜）。 */
        public static final String AMBIGUOUS_GRID = "ambiguous_grid";
        /** 结果槽多于一个。 */
        public static final String AMBIGUOUS_RESULT = "ambiguous_result";
        /** 网格槽数量与 `getWidth()*getHeight()` 不符（布局不是规整矩形）。 */
        public static final String SHAPE_MISMATCH = "grid_shape_mismatch";
        /** 网格容器自己在 `getWidth()/getHeight()/getContainerSize()` 里抛了异常（模组实现问题，如实上报）。 */
        public static final String GRID_METRICS_FAILED = "grid_metrics_failed";
        /** 扫描槽位时至少有一格抛异常（细节见 note；**不因此崩服务端**）。 */
        public static final String SLOTS_PARTIAL = "slot_facts_partial";
        /** 菜单里没有玩家背包槽（无法把材料/产物放回去）。 */
        public static final String NO_PLAYER_SLOTS = "no_player_inventory_slots";

        private Codes() {
        }
    }

    /**
     * 一个槽位的**客观事实**（探针用）。刻意**不含**任何"这格是干什么的"的推断 ——
     * 报告事实、由人判断，正是本项目"未知模组默认只读"的做法。
     */
    public record SlotInfo(int index, String slotClass, String containerClass, int x, int y,
                           boolean active, String item) {

        public String describe() {
            return index + ":" + slotClass + "/" + containerClass + "@" + x + "," + y
                    + (active ? "" : "(inactive)") + (item.isEmpty() ? "" : "=" + item);
        }
    }

    /**
     * **收集"菜单里所有可达的槽位"**（不只是 `menu.slots`）。
     *
     * <p><b>为什么必须这样</b>（2026-09-13 实测事故，D-192 §6.22）：精妙存储/精妙核心的
     * `StorageContainerMenuBase.addUpgradeSlot(...)` 做的是 ——
     * `slot.index = getTotalSlotsNumber()` + 加进它**自己的** `upgradeSlots` 列表，
     * **从不调用 `AbstractContainerMenu.addSlot(...)`**（字节码已核对）。于是"合成升级页签"的
     * **9 格矩阵 + 结果槽根本不在 `menu.slots` 里**：探针实测 `upgradeContainers=1 [0]`（容器建出来了）
     * 但 `menu.slots.size()=63`（27 存储 + 36 玩家）⇒ 只遍历 `menu.slots` 的发现器**永远看不见它**。
     *
     * <p>做法：以 `menu.slots` 为起点，再**反射**把菜单字段里可达的 {@link Slot} 都收进来
     * （`Slot` / `Collection<Slot>` / `Map<?,Slot>`，以及"值对象自己带 `getSlots()`"的一层展开），
     * 按**对象身份**去重。**这些槽位的点击地址是 `slot.index`（不是它在 `menu.slots` 里的位置）** ——
     * 原版槽位的 `index` 就等于其位置，所以对随身/工作台**行为完全不变**（回归由既有夹具证明）。
     */
    public static List<Slot> collectSlots(AbstractContainerMenu menu) {
        List<Slot> out = new ArrayList<>();
        java.util.Set<Slot> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        if (menu == null) {
            return out;
        }
        for (Slot slot : menu.slots) {
            if (seen.add(slot)) {
                out.add(slot);
            }
        }
        collectFromFields(menu, out, seen, 0);
        return out;
    }

    /** 反射遍历（**只读**、逐项容错、深度受限）：`Slot` / 集合 / 映射 / "带 getSlots() 的值对象"。 */
    private static void collectFromFields(Object target, List<Slot> out, java.util.Set<Slot> seen, int depth) {
        if (target == null || depth > 2) {
            return;
        }
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(target);
                } catch (Throwable t) {
                    continue;   // 取不到就跳过（未知模组字段可能拒绝访问）
                }
                collectValue(value, out, seen, depth);
            }
            type = type.getSuperclass();
        }
    }

    private static void collectValue(Object value, List<Slot> out, java.util.Set<Slot> seen, int depth) {
        if (value == null) {
            return;
        }
        if (value instanceof Slot slot) {
            if (seen.add(slot)) {
                out.add(slot);
            }
            return;
        }
        if (value instanceof java.util.Map<?, ?> map) {
            for (Object entry : map.values()) {
                collectValue(entry, out, seen, depth + 1);
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object entry : iterable) {
                collectValue(entry, out, seen, depth + 1);
            }
            return;
        }
        if (value instanceof Object[] array) {
            for (Object entry : array) {
                collectValue(entry, out, seen, depth + 1);
            }
            return;
        }
        // "自带槽位列表的值对象"（例如上游的 UpgradeContainerBase）：展开一层
        if (depth < 2) {
            try {
                java.lang.reflect.Method getSlots = value.getClass().getMethod("getSlots");
                Object slots = getSlots.invoke(value);
                collectValue(slots, out, seen, depth + 1);
            } catch (Throwable ignored) {
                // 没有 getSlots() 就不是槽位宿主 —— 正常情况
            }
        }
    }

    /** 发现结果：`spec != null` 才表示认出网格；否则 `code` 说明为什么认不出。 */
    public record Result(InventoryCraft.GridSpec spec, String code, String note,
                         String matrixClass, int matrixSlots,
                         String resultSlotClass, int resultSlots) {

        public boolean ok() {
            return spec != null;
        }

        public String describe() {
            StringBuilder sb = new StringBuilder();
            if (spec != null) {
                sb.append("OK grid=").append(spec.width()).append('x').append(spec.height())
                        .append(" slots=").append(describeSlots(spec.gridSlots()))
                        .append(" result=").append(spec.resultSlot())
                        .append(" inv=[").append(spec.inventoryFirst()).append("..")
                        .append(spec.inventoryLast()).append(']');
            } else {
                sb.append("FAIL:").append(code);
            }
            sb.append(" matrix=").append(matrixClass).append('(').append(matrixSlots).append(')')
                    .append(" resultSlot=").append(resultSlotClass).append('(').append(resultSlots).append(')');
            if (!note.isEmpty()) {
                sb.append(" note=").append(note);
            }
            return sb.toString();
        }

        private static String describeSlots(int[] slots) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < slots.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(slots[i]);
            }
            return sb.append(']').toString();
        }
    }

    private GridDiscovery() {
    }

    /**
     * **认网格**：网格槽 = `slot.container instanceof CraftingContainer`（同一实例）；
     * 结果槽 = `slot.container instanceof ResultContainer`；玩家背包 = `slot.container == player.getInventory()`。
     *
     * <p>为什么用**容器实例的身份**而不是"第一个 CraftingContainer"：菜单里可能同时存在多个网格
     * （例如同时装了"合成升级"和"工作台升级"，或者玩家自带 2×2 与容器的 3×3 同时出现）——
     * 那时**谁是目标不由我们猜**，如实报 `ambiguous_grid`。
     */
    public static Result discover(AbstractContainerMenu menu, Player player) {
        if (menu == null) {
            return new Result(null, Codes.NO_GRID, "菜单为空", "-", 0, "-", 0);
        }
        CraftingContainer matrix = null;
        int matrixSlots = 0;
        boolean ambiguousGrid = false;
        List<Integer> gridSlots = new ArrayList<>();
        List<Integer> resultSlots = new ArrayList<>();
        String resultSlotClass = "-";
        boolean resultIsVanillaResultSlot = false;
        List<Integer> playerSlots = new ArrayList<>();
        Container playerInv = player == null ? null : player.getInventory();
        int slotExceptions = 0;

        List<Slot> allSlots = collectSlots(menu);
        int extraSlots = allSlots.size() - menu.slots.size();
        for (Slot slot : allSlots) {
            // 逐槽保护：这是**报告路径**上的常用入口（`bot_report`、候选列出都调它），
            // 来源未知的槽位对象可能在任何 getter 里抛 ⇒ 一格出事不该崩服务端。
            Container container;
            int address;
            try {
                container = slot.container;
                // **点击地址 = `slot.index`**（不是它在 menu.slots 里的位置）：
                // 上游把升级页签的槽位建在 menu.slots 之外，只设了 `slot.index`（见 collectSlots 的说明）。
                address = slot.index;
                if (address < 0) {
                    address = allSlots.indexOf(slot);
                }
            } catch (RuntimeException e) {
                slotExceptions++;
                continue;
            }
            if (container instanceof CraftingContainer crafting) {
                if (matrix == null) {
                    matrix = crafting;
                    matrixSlots = crafting.getContainerSize();
                } else if (matrix != crafting) {
                    ambiguousGrid = true;   // 两个不同的网格容器：不猜谁是目标
                }
                if (matrix == crafting) {
                    gridSlots.add(address);
                }
            } else if (container instanceof ResultContainer) {
                resultSlots.add(address);
                if (resultSlotClass.equals("-")) {
                    resultSlotClass = slot.getClass().getSimpleName();
                    resultIsVanillaResultSlot = slot instanceof ResultSlot;
                }
            } else if (playerInv != null && container == playerInv && slot.getContainerSlot() < 36) {
                // 玩家主背包/快捷栏（0..35）；盔甲与副手容器槽号 ≥36 ⇒ 排除
                playerSlots.add(address);
            }
        }

        String matrixClass = matrix == null ? "-" : matrix.getClass().getSimpleName();
        int resultCount = resultSlots.size();
        if (matrix == null) {
            return new Result(null, Codes.NO_GRID, "菜单里没有 CraftingContainer（该站点当前没有合成网格）",
                    "-", 0, resultSlotClass, resultCount);
        }
        if (ambiguousGrid) {
            return new Result(null, Codes.AMBIGUOUS_GRID,
                    "菜单里有多个不同的网格容器（哪个是目标不由我们猜）", matrixClass, matrixSlots,
                    resultSlotClass, resultCount);
        }
        if (resultCount == 0) {
            return new Result(null, Codes.NO_RESULT_SLOT, "有网格但没有 ResultContainer 结果槽",
                    matrixClass, matrixSlots, "-", 0);
        }
        if (resultCount > 1) {
            return new Result(null, Codes.AMBIGUOUS_RESULT, "结果槽多于一个",
                    matrixClass, matrixSlots, resultSlotClass, resultCount);
        }
        int width;
        int height;
        try {
            width = matrix.getWidth();
            height = matrix.getHeight();
        } catch (RuntimeException e) {
            return new Result(null, Codes.GRID_METRICS_FAILED,
                    "网格容器在 getWidth/getHeight 里抛了 " + e.getClass().getSimpleName(),
                    matrixClass, matrixSlots, resultSlotClass, resultCount);
        }
        if (width * height != gridSlots.size()) {
            return new Result(null, Codes.SHAPE_MISMATCH,
                    "网格槽数 " + gridSlots.size() + " != " + width + "x" + height + "（布局不是规整矩形）",
                    matrixClass, matrixSlots, resultSlotClass, resultCount);
        }
        if (playerSlots.isEmpty()) {
            return new Result(null, Codes.NO_PLAYER_SLOTS, "菜单里没有玩家背包槽（材料无处放回）",
                    matrixClass, matrixSlots, resultSlotClass, resultCount);
        }
        int[] slots = gridSlots.stream().mapToInt(Integer::intValue).toArray();
        InventoryCraft.GridSpec spec = new InventoryCraft.GridSpec(slots, width, height,
                resultSlots.get(0), playerSlots.get(0), playerSlots.get(playerSlots.size() - 1));
        String note = "resultSlotIsVanillaResultSlot=" + resultIsVanillaResultSlot
                + " playerSlots=" + playerSlots.size()
                + " extraSlots=" + extraSlots + "(不在 menu.slots 里的可达槽位)"
                + (slotExceptions > 0 ? " slotExceptions=" + slotExceptions + "(" + Codes.SLOTS_PARTIAL + ")" : "");
        return new Result(spec, "", note, matrixClass, matrixSlots, resultSlotClass, resultCount);
    }

    /** **全槽位事实表**（探针输出用）：下标/槽类/容器类/坐标/是否 active/物品。 */
    public static List<SlotInfo> describeSlots(AbstractContainerMenu menu) {
        List<SlotInfo> out = new ArrayList<>();
        if (menu == null) {
            return out;
        }
        List<Slot> allSlots = collectSlots(menu);
        int vanilla = menu.slots.size();
        for (int i = 0; i < allSlots.size(); i++) {
            // **逐槽保护**：来源未知的槽位对象可能在任何 getter 里抛（模组实现千奇百怪）；
            // 一格出事不该让整张表（更不该让服务端）陪葬 —— 如实把异常写进那一格的描述里。
            try {
                Slot slot = allSlots.get(i);
                ItemStack stack = slot.getItem();
                String item = stack.isEmpty() ? ""
                        : BuiltInRegistries.ITEM.getKey(stack.getItem()) + "x" + stack.getCount();
                // `i` = 槽位表里的序号；`slot.index` = **点击地址**；`*` = 不在 menu.slots 里（上游自管）
                String marker = i < vanilla ? "" : "*";
                out.add(new SlotInfo(i, marker + slot.getClass().getSimpleName() + "#" + slot.index,
                        slot.container.getClass().getSimpleName(), slot.x, slot.y, slot.isActive(), item));
            } catch (RuntimeException e) {
                out.add(new SlotInfo(i, "?", "?", 0, 0, false,
                        "EXCEPTION:" + e.getClass().getSimpleName()));
            }
        }
        return out;
    }

    /**
     * 菜单身份（日志/报告用）：类名 + 槽数 + 菜单类型 id（**可能没有**）。
     *
     * <p><b>2026-09-13 实测事故</b>：这里原本直接调 `menu.getType()`，结果把整个服务端 tick 循环打崩了 ——
     * `AbstractContainerMenu.getType()` 在**没有 MenuType** 的菜单上会抛
     * `UnsupportedOperationException: Unable to construct this menu by type`，
     * 而**原版自己的 `InventoryMenu`（玩家随身菜单）就是用 `null` MenuType 构造的**，
     * 不是模组的毛病。教训有两层：
     * ① 菜单身份是**可选信息**，拿不到就如实标 `unregistered`，绝不为它冒崩服务的险；
     * ② **凡是对"来源未知的菜单/槽位对象"取值，一律加保护**（模组可能在任何方法里抛）。
     */
    public static String describeMenu(AbstractContainerMenu menu) {
        if (menu == null) {
            return "-";
        }
        String type;
        try {
            ResourceLocation key = BuiltInRegistries.MENU.getKey(menu.getType());
            type = key == null ? "?" : key.toString();
        } catch (RuntimeException e) {
            type = "unregistered(" + e.getClass().getSimpleName() + ")";
        }
        return menu.getClass().getSimpleName() + "(" + type + ") slots=" + menu.slots.size();
    }
}
