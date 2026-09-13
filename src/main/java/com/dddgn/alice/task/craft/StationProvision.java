package com.dddgn.alice.task.craft;

import com.dddgn.alice.action.MenuSession;
import com.dddgn.alice.action.WriteBudget;
import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * **工作站装配**（阶段 3-A / L2，D-194）：把"升级物品"放进容器的升级槽、以及**用完取回**。
 *
 * <p>用户裁定（2026-09-13）：*"装上升级本身算一种配置行为"*、*"bot 自己用菜单协议把合成升级点进升级槽这条路可以"*、
 * *"点开标签页也单独算上一层"*（后者已由实测判定为**无需动作**，见 D-192 附注六）。
 * ⇒ 本类就是那一层：**不改合成任务，不偷偷替用户改配置**；合成任务只接受"已经具备能力"的站点。
 *
 * <p>**协议**（全部走菜单协议，不碰容器字段）：
 * <ol>
 *   <li>打开容器菜单（`MenuSession`）；</li>
 *   <li>`QUICK_MOVE`（shift-click）把升级从**玩家背包**送进去 —— 与真人操作同一个动作；</li>
 *   <li>**关掉再打开**菜单（上游在菜单构造时才按升级建容器 ⇒ 不重开就看不到新能力）；</li>
 *   <li>**用能力验证**：{@link GridDiscovery} 必须认出一个 ≥3×3 网格 ⇒ 才算装成功
 *       （**不猜槽位语义，只看结果**）；失败就**把物品取回来**（失败也要清场）。</li>
 * </ol>
 *
 * <p>**写入授权**：容器写入维度（`WriteBudget.consumeContainerWrite`，与 A11 `CONTAINER_TRANSFER` 同源）+
 * 理由 {@link WriteReason#STATION_PROVISION}；并遵循**建拆同权**：装进去必须能原样取回
 * （{@link Mode#REMOVE} 就是它的对称操作）。
 */
public final class StationProvision {

    /** 失败码（如实上报；调用方据此决定是"环境不具备"还是"我们没做成"）。 */
    public static final class Codes {
        public static final String OUT_OF_REACH = "station_out_of_reach";
        public static final String MENU_FAILED = "menu_open_failed";
        public static final String ITEM_ABSENT = "upgrade_item_absent";
        public static final String MOVE_REJECTED = "upgrade_move_rejected";
        public static final String BUDGET_REFUSED = "container_write_refused";
        public static final String NO_EFFECT = "provision_no_effect";
        public static final String STILL_THERE = "deprovision_no_effect";
        public static final String SLOT_NOT_FOUND = "upgrade_slot_not_found";

        private Codes() {
        }
    }

    /** 装配方向。 */
    public enum Mode {
        /** 把升级装进容器。 */
        INSTALL,
        /** 把升级取回玩家背包（建拆同权的另一半）。 */
        REMOVE
    }

    /**
     * 一次装配动作的结果。
     *
     * @param ok         动作是否**已用能力验证**（不是"发出过点击"）
     * @param code       失败码（ok 时为空）
     * @param detail     可读事实（物品前后数量、菜单槽数、发现的网格等）
     * @param rolledBack 失败时是否已经把动过的物品放回原位
     */
    public record Result(boolean ok, String code, String detail, boolean rolledBack) {

        public String describe() {
            return (ok ? "OK" : "FAIL:" + code) + " " + detail
                    + (rolledBack ? "（已回滚）" : "");
        }
    }

    private StationProvision() {
    }

    // ==================== 计数（世界事实，用来证明"真搬了"） ====================

    /** 玩家背包里某物品的数量（只读）。 */
    public static int countInInventory(BotPlayer bot, net.minecraft.world.item.Item item) {
        return RecipeQuery.countInInventory(bot, item);
    }

    /**
     * **"真正落地"的产物数量**：玩家背包 + 容器里**除结果槽与合成网格以外**的槽位。
     *
     * <p>为什么必须排除结果槽（2026-09-13 实测回归）：原版工作台/模组页签的**结果槽里放的是合成预览**，
     * 摆好料之后它就已经是产物了 —— 把预览也算进"产物"会让
     * `before=1（预览）→ after=1（产物进背包、预览消失）` ⇒ 差值为 0 ⇒ **成功被判成 `result_not_taken`**。
     * 网格里的东西是**材料**，同理不算产物。
     */
    public static int countLandedProduct(AbstractContainerMenu menu, BotPlayer bot,
                                         net.minecraft.world.item.Item item, InventoryCraft.GridSpec spec) {
        int total = RecipeQuery.countInInventory(bot, item);
        if (menu == null) {
            return total;
        }
        for (Slot slot : GridDiscovery.scan(menu).slots()) {
            if (slot.container == bot.getInventory()) {
                continue;                       // 玩家背包已数过
            }
            if (spec != null && (slot.index == spec.resultSlot() || isGridSlot(spec, slot.index))) {
                continue;                       // 结果槽 = 预览；网格格 = 材料
            }
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static boolean isGridSlot(InventoryCraft.GridSpec spec, int address) {
        for (int gridSlot : spec.gridSlots()) {
            if (gridSlot == address) {
                return true;
            }
        }
        return false;
    }

    /** 容器菜单里某物品的数量（只读；"装进去了"的直接证据）。 */
    public static int countInContainer(AbstractContainerMenu menu, BotPlayer bot,
                                       net.minecraft.world.item.Item item) {
        int count = 0;
        for (Slot slot : GridDiscovery.scan(menu).slots()) {
            if (slot.container == bot.getInventory()) {
                continue;   // 玩家背包那部分不算
            }
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * **开菜单前把手里的升级换走**（夹具纪律）。
     *
     * <p>**2026-09-13 实测机制**：拿着合成升级右键容器时，**升级物品自己会把它装进容器**
     * （物品驱动的装配路径，"Right Click To Add"），于是那次右键**不打开 GUI** ——
     * 表现就是 `use_item_on … result=SUCCESS`（而正常打开时是 `CONSUME`）+ 随后 `menu_open_timeout`。
     * ⇒ 凡是要"打开站点菜单"的动作，**先把选中的快捷栏槽换成空格**，行为才确定。
     *
     * @return 是否换过（换过会同步主手，客户端观感一致）
     */
    public static boolean clearHeldUpgrade(BotPlayer bot, net.minecraft.world.item.Item upgrade) {
        var inventory = bot.getInventory();
        if (upgrade == null || !inventory.getItem(inventory.selected).is(upgrade)) {
            return false;
        }
        for (int slot = 0; slot < 9 && slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                inventory.selected = slot;
                com.dddgn.alice.bot.BotManager.syncMainHand(bot);
                BotLog.info("[Provision] 开菜单前把升级换出主手（selected={}）", slot);
                return true;
            }
        }
        return false;
    }

    // ==================== 动作（每个方法 = 一次点击，调用方负责 tick 节奏） ====================

    /**
     * 把升级**送进容器**：优先 shift-click（与真人同一个动作）；上游若不接受，再试"直接放进升级槽"。
     *
     * <p>`QUICK_MOVE` 的落点由**菜单自己**决定（我们不给它指定槽位）—— 这正是"不猜槽位语义"的做法；
     * 成功与否一律由调用方**重开菜单后用能力验证**。
     *
     * @return 是否至少发出了一次被上游接受的点击
     */
    public static boolean moveIntoContainer(BotPlayer bot, AbstractContainerMenu menu,
                                            net.minecraft.world.item.Item item, BlockPos pos) {
        int source = findInventorySlot(bot, menu, item);
        if (source >= 0 && click(bot, menu, source, ClickType.QUICK_MOVE)) {
            BotLog.info("[Provision] QUICK_MOVE 送出 upgrade={} fromSlot={}", id(item), source);
            return true;
        }
        // 兜底：直接放进容器的升级槽（读上游自己的 `upgradeSlots` 字段；模组字段名不重映射 ⇒ 稳定）
        Integer target = firstUpgradeSlotAddress(menu);
        if (target == null) {
            BotLog.warn("[Provision] 找不到升级槽（{}）且 shift-click 未被接受", Codes.SLOT_NOT_FOUND);
            return false;
        }
        if (source < 0) {
            return false;
        }
        // 先拿起，再放进目标槽（两步都是菜单协议）
        if (!click(bot, menu, source, ClickType.PICKUP)) {
            return false;
        }
        if (!click(bot, menu, target, ClickType.PICKUP)) {
            click(bot, menu, source, ClickType.PICKUP);   // 放回去，别把物品留在光标上
            return false;
        }
        if (!menu.getCarried().isEmpty()) {
            click(bot, menu, source, ClickType.PICKUP);   // 目标槽没吃下 ⇒ 回滚
            return false;
        }
        BotLog.info("[Provision] 直接放入升级槽 upgrade={} address={}", id(item), target);
        return true;
    }

    /** 把升级**从容器取回**：找到容器里拿着该物品的槽位 → shift-click 回玩家背包。 */
    public static boolean moveOutOfContainer(BotPlayer bot, AbstractContainerMenu menu,
                                             net.minecraft.world.item.Item item) {
        Integer address = containerSlotHolding(menu, bot, item);
        if (address == null) {
            BotLog.warn("[Provision] 容器里没有 {}", id(item));
            return false;
        }
        if (click(bot, menu, address, ClickType.QUICK_MOVE)) {
            BotLog.info("[Provision] QUICK_MOVE 取回 upgrade={} fromAddress={}", id(item), address);
            return true;
        }
        // 兜底：拿起到光标再放进玩家背包第一个空槽
        if (!click(bot, menu, address, ClickType.PICKUP)) {
            return false;
        }
        int destination = firstEmptyInventorySlot(bot, menu);
        if (destination < 0 || !click(bot, menu, destination, ClickType.PICKUP)) {
            click(bot, menu, address, ClickType.PICKUP);   // 回滚
            return false;
        }
        return true;
    }

    /**
     * 容器写入授权（G5/A11 同源维度）。
     *
     * @return true = 允许写入；false = 预算拒绝（**必须如实上报，不许绕过**）
     */
    public static boolean allowContainerWrite(BotPlayer bot, BlockPos pos) {
        WriteGrant grant = WriteGrant.of("station-provision", WriteReason.STATION_PROVISION);
        WriteBudget.Verdict verdict = WriteBudget.consumeContainerWrite(bot, pos, grant);
        return verdict != WriteBudget.Verdict.REFUSED;
    }

    /** 便于日志/校验：容器里到底有没有那颗升级（不点任何东西，只读）。 */
    public static boolean containerHas(AbstractContainerMenu menu, BotPlayer bot,
                                       net.minecraft.world.item.Item item) {
        return countInContainer(menu, bot, item) > 0;
    }

    // ==================== 私有工具 ====================

    /** 菜单点击：**直接用 `menu.clicked`**（`MenuSession.click` 会拒绝超出 `menu.slots` 的地址，而
     *  上游自管的槽位恰恰在那里 —— 见 {@link GridDiscovery} 的说明）。 */
    private static boolean click(BotPlayer bot, AbstractContainerMenu menu, int address, ClickType type) {
        if (menu == null || address < 0) {
            return false;
        }
        try {
            menu.clicked(address, 0, type, bot);
            menu.broadcastChanges();
            return true;
        } catch (RuntimeException | Error thrown) {
            BotLog.warn("[Provision] clicked(address={}, type={}) 抛异常 {}", address, type, thrown.toString());
            return false;
        }
    }

    /**
     * 找出"玩家背包里放着该物品的那个槽位"在**菜单里的地址**。
     *
     * <p>**不许按公式猜**（`hotbar ⇒ 36+i`、`main ⇒ i` 只在某些菜单成立）：精妙容器的玩家背包段是
     * `27..53`(主背包) + `54..62`(快捷栏) —— 公式一猜就错。正确做法是**发现**：
     * 找 `container == player.getInventory()` 且 `getContainerSlot() == 背包下标` 的那个槽位，取它的 `index`。
     */
    private static int findInventorySlot(BotPlayer bot, AbstractContainerMenu menu,
                                         net.minecraft.world.item.Item item) {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.is(item)) {
                return addressOfInventorySlot(bot, menu, slot);
            }
        }
        return -1;
    }

    private static int firstEmptyInventorySlot(BotPlayer bot, AbstractContainerMenu menu) {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                return addressOfInventorySlot(bot, menu, slot);
            }
        }
        return -1;
    }

    /** 发现式地址映射：玩家背包下标 → 菜单地址（见 {@link #findInventorySlot} 的说明）。 */
    private static int addressOfInventorySlot(BotPlayer bot, AbstractContainerMenu menu, int inventoryIndex) {
        for (Slot slot : GridDiscovery.scan(menu).slots()) {
            if (slot.container == bot.getInventory() && slot.getContainerSlot() == inventoryIndex) {
                return slot.index;
            }
        }
        return -1;
    }

    /** 容器里拿着该物品的槽位**地址**（排除玩家背包那部分）。 */
    private static Integer containerSlotHolding(AbstractContainerMenu menu, BotPlayer bot,
                                                net.minecraft.world.item.Item item) {
        for (Slot slot : GridDiscovery.scan(menu).slots()) {
            if (slot.container == bot.getInventory()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && stack.is(item)) {
                return slot.index;
            }
        }
        return null;
    }

    /**
     * 读上游自己的 `upgradeSlots` 列表拿到升级槽地址（**兜底路径**用）。
     *
     * <p>为什么可以直接读字段名：`upgradeSlots` 是**模组类**的字段（Forge 不重映射模组代码）⇒ 名字稳定；
     * 反过来说，**绝不能**对 vanilla 成员用字符串反射（那是 SRG 名，见 D-192 附注五）。
     */
    private static Integer firstUpgradeSlotAddress(AbstractContainerMenu menu) {
        Object value = readField(menu, "upgradeSlots");
        if (value instanceof List<?> list) {
            List<Integer> addresses = new ArrayList<>();
            for (Object entry : list) {
                if (entry instanceof Slot slot) {
                    addresses.add(slot.index);
                }
            }
            if (!addresses.isEmpty()) {
                return addresses.get(0);
            }
        }
        return null;
    }

    private static Object readField(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static String id(net.minecraft.world.item.Item item) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
    }
}
