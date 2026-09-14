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
 *   <li>**路径 ③ 上游自述**：没有"恰好 3 格的容器"时，改找**自己声明了 3 个烹饪槽**的上游对象
 *      （`getCookingSlots()`；精妙存储/精妙背包的"熔炼升级页签"就是这种 —— 那 3 格分属不同的
 *      `Container`，形态学判据必然认不出）。输入/燃料/输出**不采信自述顺序**，一律用 `mayPlace` 行为判定；</li>
 *   <li>候选**多于一个**就如实拒绝（不猜）+ 报 `ambiguous_furnace`。</li>
 * </ol>
 *
 * <p>**为什么按字段类型而不是字段名**：vanilla 的字段名在 Forge 生产环境是 SRG 名（`f_xxxxx_`），
 * 字符串反射必然踩空（D-192 附注五的教训）。
 *
 * <p>**为什么候选集要对"槽位宿主"多展开一层**（2026-09-13 A4b 实测）：上游把自述者放在宿主的私有字段里
 * （`CookingUpgradeContainer.cookingLogicContainer`），宿主本身在 2 层遍历里可见、自述者正好被深度上限挡住
 * ⇒ 症状是"**看见宿主却看不见自述者**"（`no_furnace_slots` 且 diag 里只有宿主）。
 * 修法是**只对产出过槽位的宿主**多展开一层（不是把全世界翻深一层），并**自校验**采用与否。
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

        /** 进度百分比（0..100；读不到给 -1 —— 别把"读不到"说成 0%）。 */
        public int percent() {
            return maxProgress <= 0 || progress < 0 ? -1 : (int) (100L * progress / maxProgress);
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
        List<Slot> slots = null;
        String pickedBy = "";
        Container pickedContainer = null;
        Declaration declaration = null;
        if (candidates.size() == 1) {
            pickedContainer = candidates.get(0).getKey();
            slots = new ArrayList<>(candidates.get(0).getValue());
            slots.sort(Comparator.comparingInt(Slot::getContainerSlot));
            pickedBy = "slotContainerIdentity";
        } else if (candidates.size() > 1) {
            return new Result(null, Codes.AMBIGUOUS,
                    "有 " + candidates.size() + " 个 3 格候选容器（哪台是目标不由我们猜）");
        } else {
            // **路径 ③（上游自述）**：模组的烹饪页签不是"一个容器占 3 格"，而是上游容器
            // `CookingLogicContainer.getCookingSlots()` 自述 3 个槽（2026-09-13 实测：精妙"熔炼升级"就是这种）。
            declaration = findCookingDeclaration(menu);
            if (declaration != null) {
                slots = declaration.slots();
                pickedContainer = slots.get(0).container;
                pickedBy = "ownerDeclaration(getCookingSlots)";
            }
        }
        if (slots == null) {
            for (String line : diagnose(menu)) {
                BotLog.info("[FurnaceDiag] {}", line);
            }
            return new Result(null, Codes.NO_FURNACE,
                    "既没有\"恰好 3 格\"的容器、也没有可识别的 3 格上游自述");
        }
        Map.Entry<Container, List<Slot>> picked = Map.entry(pickedContainer, slots);
        // **两条证据路径**（都可能"证明它按时间工作"）：
        //   ① 菜单里有 `ContainerData` 字段（原版熔炉形态，下标 0..3 有固定约定）；
        //   ② 菜单/上游容器里**有对象自述**了烹饪进度方法族（精妙存储的"熔炼升级页签"就是这样：
        //      `CookingLogicContainer.getCookTimeTotal/getCookTimeFinish/getBurnTimeTotal/isCooking`）——
        //      与 `GridDiscovery` 的"上游自述"同一套路：只认**方法名形态**，且用返回值**自校验**。
        ContainerData data = findContainerData(menu);
        Object logic = data == null ? cookingProgressReporter(menu, declaration) : null;
        if (data == null && logic == null) {
            return new Result(null, Codes.NO_PROGRESS_DATA,
                    "有 3 格容器（" + picked.getKey().getClass().getSimpleName()
                            + "）但既没有 ContainerData、也没有可识别的烹饪进度自述 ⇒ 不确认它按时间工作");
        }
        // 原版 `AbstractFurnaceBlockEntity.dataAccess` 的约定：0=剩余燃烧时间 1=本次燃料总时长
        // 2=烧炼进度 3=本配方总时长（先前我按 0/1/2 读，进度恒为 0/0 —— 纯报告瑕疵，已修）。
        // **注意**：这只用于"过程证据"；**判成功与否一律看世界事实**（产物/输入/炉内是否清空）。
        int litTime;
        int progress;
        int maxProgress;
        String dataName;
        if (data != null) {
            litTime = safeGet(data, 0);
            progress = safeGet(data, 2);
            maxProgress = safeGet(data, 3);
            dataName = dataName(data);
        } else {
            // 上游自述（路径 ②）：只有"总量"可读（精妙存储给的是 cookTimeTotal/burnTimeTotal）；
            // `getCookTimeFinish()` 是**一个 long 级别的游戏时刻**（不是倒计时），拿它跟总量算不出百分比
            // ⇒ 进度如实报**未知（-1）**，别把"读不到"写成 0%（原版路径靠 `ContainerData` 才有百分比）。
            maxProgress = intCall(logic, "getCookTimeTotal");
            progress = -1;
            litTime = intCall(logic, "getBurnTimeTotal");
            dataName = cookingLogicName(logic) + "(selfReported)";
        }
        int input = slots.get(0).index;
        int fuel = slots.get(1).index;
        int output = slots.get(2).index;
        String assignBy = "containerSlotOrder";
        if (!"slotContainerIdentity".equals(pickedBy)) {
            // **行为判定**（不信任自述顺序）：结果槽拒绝放置；燃料槽接受煤但拒绝圆石；输入槽接受圆石
            Slot outputSlot = null;
            Slot fuelSlot = null;
            Slot inputSlot = null;
            for (Slot slot : slots) {
                boolean coal = accepts(slot, net.minecraft.world.item.Items.COAL);
                boolean cobble = accepts(slot, net.minecraft.world.item.Items.COBBLESTONE);
                if (!coal && !cobble) {
                    outputSlot = slot;
                } else if (coal && !cobble) {
                    fuelSlot = slot;
                } else if (cobble) {
                    inputSlot = slot;
                }
            }
            if (inputSlot != null && fuelSlot != null && outputSlot != null) {
                input = inputSlot.index;
                fuel = fuelSlot.index;
                output = outputSlot.index;
                assignBy = "mayPlaceProbe";
            } else {
                assignBy = "upstreamOrder(fallback)";
            }
        }
        Found found = new Found(input, fuel, output,
                containerName(picked.getKey()), dataName, progress, maxProgress, litTime);
        BotLog.info("[Furnace] 认出炉子 by={} assignBy={} {}", pickedBy, assignBy, found.describe());
        return new Result(found, "", found.describe());
    }

    /**
     * **一次性诊断**（认不出炉子时打）：菜单**可达对象**的类名 + 它们身上"像烹饪进度的方法名"，
     * 以及**全槽位表**（下标/点击地址/槽类/容器类 + `mayPlace` 探针）。
     *
     * <p>为什么要它：2026-09-13 实测第二条路径"连对象都没找到" —— 只有把**对象图**与**槽位形态**摊开，
     * 才能知道该沿 `Supplier` 展开、还是该改用上游访问器（`getSmeltingLogicContainer()` 之类），
     * 而不是继续加特判。
     */
    public static List<String> diagnose(AbstractContainerMenu menu) {
        List<String> lines = new ArrayList<>();
        if (menu == null) {
            return lines;
        }
        lines.add("menu=" + menu.getClass().getName() + " menuSlots=" + menu.slots.size());
        List<Object> reachable = reachableObjects(menu);
        lines.add("reachableObjects=" + reachable.size());
        for (Object object : reachable) {
            List<String> methods = new ArrayList<>();
            Class<?> type = object.getClass();
            while (type != null && type != Object.class) {
                for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                    String name = method.getName();
                    if (name.contains("Cook") || name.contains("Burn") || name.equals("isCooking")
                            || name.contains("CookingSlot") || name.contains("Logic")) {
                        methods.add(name);
                    }
                }
                type = type.getSuperclass();
            }
            String simple = object.getClass().getSimpleName();
            lines.add("  obj=" + (simple.isEmpty() ? object.getClass().getName() : simple)
                    + (methods.isEmpty() ? "" : " methods=" + methods));
        }
        for (Slot slot : GridDiscovery.scan(menu).slots()) {
            String name = slot.getClass().getSimpleName();
            String container = slot.container.getClass().getSimpleName();
            lines.add("  slot#" + slot.index + " " + (name.isEmpty() ? "(anon)" : name)
                    + "/" + (container.isEmpty() ? "(anon)" : container)
                    + " containerSlot=" + slot.getContainerSlot()
                    + " mayPlace[coal=" + accepts(slot, net.minecraft.world.item.Items.COAL)
                    + " cobble=" + accepts(slot, net.minecraft.world.item.Items.COBBLESTONE) + "]"
                    + " item=" + slot.getItem().getCount() + "x"
                    + net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(slot.getItem().getItem()).getPath());
        }
        return lines;
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

    /**
     * **路径 ③ 的产物**：自述"我有 3 个烹饪槽"的上游对象 + 它自述的那 3 格。
     *
     * @param slots 自述的 3 个槽（顺序**只当参考**；输入/燃料/输出一律由 {@code mayPlace} 行为判定）
     * @param owner 自述者本身（它通常也自述烹饪进度，见 {@link #cookingProgressReporter}）
     */
    private record Declaration(List<Slot> slots, Object owner) {
    }

    /**
     * **路径 ③：上游自述的烹饪槽位**（**按方法名形态**找，不按类名）。
     *
     * <p>判据 = 某个可达对象自己声明 `getCookingSlots()` 且**确实**给出 3 个 `Slot`
     * （精妙存储/精妙背包的"熔炼/高炉/烟熏升级页签"就是这种：那 3 格不挂在同一个 `Container` 上，
     * 所以"一个容器恰好 3 格"这条形态学判据必然认不出它）。
     *
     * <p>**为什么候选集要扩一层**（2026-09-13 A4b 首测 `no_furnace_slots` 的真因）：上游把自述者
     * 放在槽位宿主的**私有字段**里（`CookingUpgradeContainer.cookingLogicContainer`）；宿主在 2 层遍历里
     * 看得见（diag 里 `obj=CookingUpgradeContainer methods=[getSmeltingLogicContainer]`），
     * 自述者正好被深度上限挡在外面 ⇒ **看见宿主却看不见自述者**。见 {@link #cookingCandidates}。
     */
    private static Declaration findCookingDeclaration(AbstractContainerMenu menu) {
        for (Object candidate : cookingCandidates(menu)) {
            Object declared = callNoArg(candidate, "getCookingSlots");
            if (!(declared instanceof List<?> list) || list.size() != 3) {
                continue;
            }
            if (!list.stream().allMatch(Slot.class::isInstance)) {
                continue;
            }
            List<Slot> slots = new ArrayList<>();
            for (Object entry : list) {
                slots.add((Slot) entry);
            }
            return new Declaration(slots, candidate);
        }
        return null;
    }

    /**
     * **自述烹饪进度的对象**：判据 = `getCookTimeTotal()` 且（`isCooking()` 或 `getBurnTimeTotal()`）。
     * 只做"只读调用"，返回值仅用于**报告过程证据**；**判成功与否一律看世界事实**。
     *
     * <p>自述槽位与自述进度实测是**同一个对象**（`CookingLogicContainer`），所以先问它、避免重复遍历。
     */
    private static Object cookingProgressReporter(AbstractContainerMenu menu, Declaration declaration) {
        if (declaration != null && hasCookingProgress(declaration.owner())) {
            return declaration.owner();
        }
        for (Object candidate : cookingCandidates(menu)) {
            if (hasCookingProgress(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean hasCookingProgress(Object target) {
        return hasMethod(target, "getCookTimeTotal")
                && (hasMethod(target, "isCooking") || hasMethod(target, "getBurnTimeTotal"));
    }

    /**
     * 烹饪自述的**候选对象**：菜单 + 菜单可达对象 + 每个"**槽位宿主**"的字段再展开一层。
     *
     * <p>只对"产出过槽位的宿主"（{@link GridDiscovery.Scan#owners()}，即上游容器对象）多展开一层：
     * 既够到藏在宿主私有字段里的自述者，又**不会把遍历炸开**（宿主不是 `ServerLevel` 那种世界对象）。
     * 收进来的对象一律要**自校验**（自述 3 个烹饪槽 / 自述烹饪进度方法族）才会被采用。
     */
    private static List<Object> cookingCandidates(AbstractContainerMenu menu) {
        List<Object> out = new ArrayList<>();
        out.add(menu);
        out.addAll(reachableObjects(menu));
        for (Object owner : GridDiscovery.scan(menu).owners()) {
            if (owner == null) {
                continue;
            }
            out.add(owner);
            for (Field field : allFields(owner.getClass())) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(owner);
                    if (value != null) {
                        out.add(value);
                    }
                } catch (Throwable ignored) {
                    // 只读诊断：读不到就跳过
                }
            }
        }
        return out;
    }

    /** 类层次上声明的全部字段（vanilla/上游都可能把东西藏在父类里）。 */
    private static List<Field> allFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                fields.add(field);
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    /** 菜单**可达的对象**（字段/集合/映射展开一层，深度受限；只读、逐项容错）。 */
    private static List<Object> reachableObjects(Object root) {
        List<Object> out = new ArrayList<>();
        collect(root, out, 0, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        return out;
    }

    private static void collect(Object value, List<Object> out, int depth, java.util.Set<Object> seen) {
        if (value == null || depth > 2 || !seen.add(value)) {
            return;
        }
        if (value instanceof java.util.Map<?, ?> map) {
            for (Object entry : map.values()) {
                collect(entry, out, depth + 1, seen);
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object entry : iterable) {
                collect(entry, out, depth + 1, seen);
            }
            return;
        }
        if (value instanceof Slot || value instanceof Container && depth > 0) {
            return;   // 槽位/普通容器内部不再展开（避免把整个世界翻一遍）
        }
        out.add(value);
        if (depth >= 2) {
            return;
        }
        Class<?> type = value.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    collect(field.get(value), out, depth + 1, seen);
                } catch (Throwable ignored) {
                    // 只读诊断：读不到就跳过
                }
            }
            type = type.getSuperclass();
        }
    }

    private static boolean hasMethod(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            try {
                type.getDeclaredMethod(name);
                return true;
            } catch (NoSuchMethodException e) {
                type = type.getSuperclass();
            }
        }
        return false;
    }

    /** 该槽是否接受这个物品（**只读探针**；`mayPlace` 是原版语义"这格收不收"）。 */
    private static boolean accepts(Slot slot, net.minecraft.world.item.Item item) {
        try {
            return slot.mayPlace(new ItemStack(item));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Object callNoArg(Object target, String name) {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            try {
                var method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (NoSuchMethodException e) {
                type = type.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static int intCall(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            try {
                var method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                Object value = method.invoke(target);
                return value instanceof Integer i ? i : -1;
            } catch (NoSuchMethodException e) {
                type = type.getSuperclass();
            } catch (Throwable t) {
                return -1;
            }
        }
        return -1;
    }

    private static String cookingLogicName(Object logic) {
        String simple = logic.getClass().getSimpleName();
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
                                   net.minecraft.world.item.Item item, com.dddgn.alice.action.WriteGrant grant) {
        Integer source = findInventoryAddress(bot, menu, item);
        if (source == null) {
            BotLog.warn("[Furnace] 背包里没有 {}", item);
            return false;
        }
        if (!click(bot, menu, source, net.minecraft.world.inventory.ClickType.PICKUP, 0, grant)) {
            return false;
        }
        if (!click(bot, menu, address, net.minecraft.world.inventory.ClickType.PICKUP, 1, grant)) {
            click(bot, menu, source, net.minecraft.world.inventory.ClickType.PICKUP, 0, grant);   // 放回
            return false;
        }
        click(bot, menu, source, net.minecraft.world.inventory.ClickType.PICKUP, 0, grant);       // 余量归位
        return true;
    }

    /** 取走某格的产出（shift-click 回背包）。 */
    public static boolean takeAll(BotPlayer bot, AbstractContainerMenu menu, int address,
                                  com.dddgn.alice.action.WriteGrant grant) {
        return click(bot, menu, address, net.minecraft.world.inventory.ClickType.QUICK_MOVE, 0, grant);
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
                                 net.minecraft.world.inventory.ClickType type, int button,
                                 com.dddgn.alice.action.WriteGrant grant) {
        if (menu == null || address < 0) {
            return false;
        }
        // ⚠️ T1 / R-5（2026-09-14）：**本原语原先整个文件都没有 WriteBudget/WriteGrant 引用**
        // （三路审计 §3.1 R-5 实证）⇒ "记账靠调用方自觉" ⇒ 任何新增模组适配默认无记账。
        // 现在做**编译期强制**：调本原语必须显式交出 `WriteGrant`，且理由必须属于"容器写入"家族
        // （`WriteReason.container()`，唯一真源）；**不在这里计数**（计数归调用方，登记在
        // `docs/authz/CONTAINER_WRITE_SITES.csv` 的 FurnaceStation 行：原语层本身不决定写谁）。
        if (grant == null || !grant.reason().container()) {
            BotLog.warn("[Furnace] clicked(address={}, type={}) 被拒：缺容器写入授权（grant={}）",
                    address, type, grant == null ? "null" : grant.describe());
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
