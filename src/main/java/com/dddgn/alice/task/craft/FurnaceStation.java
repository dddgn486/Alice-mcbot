package com.dddgn.alice.task.craft;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **熔炉站点发现**（阶段 3-A / A4，D-196）：从**活菜单**里认出"输入格 / 燃料格 / 输出格"与**进度数据**。
 *
 * <p>与合成网格同一套思路（{@link GridDiscovery}）：**不看下标常量、不看类名**，只看客观事实 ——
 * <ol>
 *   <li>某容器在菜单里**恰好占 3 格**（原版熔炉/高炉/烟熏炉/模组熔炉都是这个形状）；
 *      <b>输入/燃料/输出按该容器自己的槽号 0/1/2</b>（原版 `AbstractFurnaceMenu` 的语义，也是模组普遍沿用的形状）；</li>
 *   <li>菜单里存在一个 **`ContainerData` 类型的字段**（= 这台机器会同步"烧炼进度/燃烧时间"），
 *      这是"这是个会**按时间工作**的机器"的证据（与"点一下就出"的合成菜单区分开）；</li>
 *   <li>候选**多于一个**就如实拒绝（不猜）+ 报 `ambiguous_furnace`。</li>
 * </ol>
 *
 * <p>**为什么按字段类型而不是字段名**：vanilla 的字段名在 Forge 生产环境是 SRG 名（`f_xxxxx_`），
 * 字符串反射必然踩空（D-192 附注五的教训）。
 */
public final class FurnaceStation {

    /** 失败码。 */
    public static final class Codes {
        /** 菜单里没有"恰好 3 格"的候选容器。 */
        public static final String NO_FURNACE = "no_furnace_slots";
        /** 有多个候选（谁是这台炉子？不猜）。 */
        public static final String AMBIGUOUS = "ambiguous_furnace";
        /** 有 3 格容器，但菜单里没有 `ContainerData` ⇒ 不能确认它"会按时间工作"。 */
        public static final String NO_PROGRESS_DATA = "no_progress_data";

        private Codes() {
        }
    }

    /**
     * 认出来的熔炉。
     *
     * @param input  输入格**点击地址**
     * @param fuel   燃料格**点击地址**
     * @param output 输出格**点击地址**
     * @param dataClass 菜单里那个 `ContainerData` 的实现类（证据）
     */
    public record Found(int input, int fuel, int output, String containerClass, String dataClass,
                        int progress, int maxProgress, int litTime) {

        /** 进度百分比（0..100；读不到给 -1）。 */
        public int percent() {
            return maxProgress <= 0 ? -1 : (int) (100L * progress / maxProgress);
        }

        public String describe() {
            return "input=#" + input + " fuel=#" + fuel + " output=#" + output
                    + " container=" + containerClass + " data=" + dataClass
                    + " progress=" + progress + "/" + maxProgress + " litTime=" + litTime;
        }
    }

    /** 发现结果。 */
    public record Result(Found found, String code, String detail) {
        public boolean ok() {
            return found != null;
        }

        public String describe() {
            return ok() ? "OK " + found.describe() : "FAIL:" + code + " " + detail;
        }
    }

    private FurnaceStation() {
    }

    /** **认熔炉**（只读）。 */
    public static Result discover(AbstractContainerMenu menu) {
        if (menu == null) {
            return new Result(null, Codes.NO_FURNACE, "菜单为空");
        }
        Map<Container, List<Slot>> byContainer = new LinkedHashMap<>();
        for (Slot slot : GridDiscovery.scan(menu).slots()) {
            if (slot.container instanceof net.minecraft.world.entity.player.Inventory) {
                continue;   // 玩家背包不是炉子
            }
            byContainer.computeIfAbsent(slot.container, key -> new ArrayList<>()).add(slot);
        }
        List<Map.Entry<Container, List<Slot>>> candidates = new ArrayList<>();
        for (Map.Entry<Container, List<Slot>> entry : byContainer.entrySet()) {
            if (entry.getValue().size() == 3) {
                candidates.add(entry);
            }
        }
        if (candidates.isEmpty()) {
            return new Result(null, Codes.NO_FURNACE, "菜单里没有\"恰好 3 格\"的候选容器");
        }
        if (candidates.size() > 1) {
            return new Result(null, Codes.AMBIGUOUS,
                    "有 " + candidates.size() + " 个 3 格候选容器（哪台是目标不由我们猜）");
        }
        Map.Entry<Container, List<Slot>> picked = candidates.get(0);
        List<Slot> slots = new ArrayList<>(picked.getValue());
        slots.sort(Comparator.comparingInt(Slot::getContainerSlot));
        ContainerData data = findContainerData(menu);
        if (data == null) {
            return new Result(null, Codes.NO_PROGRESS_DATA,
                    "有 3 格容器（" + picked.getKey().getClass().getSimpleName()
                            + "）但菜单里没有 ContainerData ⇒ 无法确认它按时间工作");
        }
        // 原版 `AbstractFurnaceBlockEntity.dataAccess` 的约定：0=剩余燃烧时间 1=本次燃料总时长
        // 2=烧炼进度 3=本配方总时长（先前我按 0/1/2 读，进度恒为 0/0 —— 纯报告瑕疵，已修）。
        // **注意**：这只用于"过程证据"；**判成功与否一律看世界事实**（产物/输入/炉内是否清空）。
        int litTime = safeGet(data, 0);
        int progress = safeGet(data, 2);
        int maxProgress = safeGet(data, 3);
        Found found = new Found(slots.get(0).index, slots.get(1).index, slots.get(2).index,
                containerName(picked.getKey()), dataName(data), progress, maxProgress, litTime);
        return new Result(found, "", found.describe());
    }

    /** 菜单里的 `ContainerData`（**按类型找**，不按名字）。 */
    private static ContainerData findContainerData(AbstractContainerMenu menu) {
        Class<?> type = menu.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (!ContainerData.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(menu);
                    if (value instanceof ContainerData data) {
                        return data;
                    }
                } catch (Throwable ignored) {
                    // 读不到就继续（未知模组字段可能拒绝访问）
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    /** 匿名类要给 `(anonymous)`，别输出空串让人以为没读到（vanilla 的 `ContainerData` 就是匿名实现）。 */
    private static String containerName(Container container) {
        String simple = container.getClass().getSimpleName();
        return simple == null || simple.isEmpty() ? "(anonymous)" : simple;
    }

    private static String dataName(ContainerData data) {
        String simple = data.getClass().getSimpleName();
        return simple == null || simple.isEmpty() ? "(anonymous)" : simple;
    }

    private static int safeGet(ContainerData data, int index) {
        try {
            return index < data.getCount() ? data.get(index) : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    // ==================== 动作（每次一/两次点击；调用方负责 tick 节奏） ====================

    /** 把 {@code item} 放 1 个进指定格（从玩家背包取；走菜单协议）。 */
    public static boolean placeOne(BotPlayer bot, AbstractContainerMenu menu, Found found, int address,
                                   net.minecraft.world.item.Item item) {
        Integer source = findInventoryAddress(bot, menu, item);
        if (source == null) {
            BotLog.warn("[Furnace] 背包里没有 {}", item);
            return false;
        }
        if (!click(bot, menu, source, net.minecraft.world.inventory.ClickType.PICKUP, 0)) {
            return false;
        }
        if (!click(bot, menu, address, net.minecraft.world.inventory.ClickType.PICKUP, 1)) {
            click(bot, menu, source, net.minecraft.world.inventory.ClickType.PICKUP, 0);   // 放回
            return false;
        }
        click(bot, menu, source, net.minecraft.world.inventory.ClickType.PICKUP, 0);       // 余量归位
        return true;
    }

    /** 取走某格的产出（shift-click 回背包）。 */
    public static boolean takeAll(BotPlayer bot, AbstractContainerMenu menu, int address) {
        return click(bot, menu, address, net.minecraft.world.inventory.ClickType.QUICK_MOVE, 0);
    }

    /** 某格现在有什么（只读；`null` = 该地址不存在）。 */
    public static ItemStack stackAt(AbstractContainerMenu menu, int address) {
        Slot slot = GridDiscovery.slotByAddress(menu, address);
        return slot == null ? null : slot.getItem();
    }

    /** 玩家背包里放着该物品的槽位**点击地址**（发现式映射，不按公式猜）。 */
    public static Integer findInventoryAddress(BotPlayer bot, AbstractContainerMenu menu,
                                               net.minecraft.world.item.Item item) {
        var inventory = bot.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (!inventory.getItem(i).is(item)) {
                continue;
            }
            for (Slot slot : GridDiscovery.scan(menu).slots()) {
                if (slot.container == inventory && slot.getContainerSlot() == i) {
                    return slot.index;
                }
            }
        }
        return null;
    }

    /** 菜单点击（只拒负数；地址合法性由"发现出来的槽位集合"保证 —— 见 D-192 附注一/三）。 */
    private static boolean click(BotPlayer bot, AbstractContainerMenu menu, int address,
                                 net.minecraft.world.inventory.ClickType type, int button) {
        if (menu == null || address < 0) {
            return false;
        }
        try {
            menu.clicked(address, button, type, bot);
            menu.broadcastChanges();
            return true;
        } catch (RuntimeException | Error thrown) {
            BotLog.warn("[Furnace] clicked(address={}, type={}) 抛异常 {}", address, type, thrown.toString());
            return false;
        }
    }
}
