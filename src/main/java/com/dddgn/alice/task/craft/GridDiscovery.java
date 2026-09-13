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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * **合成网格发现器**（阶段 3-A / S1-1，D-192）：从**活菜单**里认出"哪几格是合成网格、哪一格是结果槽"。
 *
 * <p>目标是**不硬编码合成方式**（用户 2026-09-13 的问题）：判据从"记下标常量"改成"**认容器与关系**"。
 * 三个已装模组实测下来的形态：
 * <ol>
 *   <li><b>随身 2×2 / 工作台 3×3（原版）</b>：那几格的 `container` **就是**那个 `CraftingContainer`；</li>
 *   <li><b>精妙存储/精妙背包的"合成升级页签"</b>（实测）：槽位**不在 `menu.slots` 里** —— 上游
 *       `StorageContainerMenuBase.addUpgradeSlot(...)` 只设 `slot.index` 并收进自己的列表（字节码已核对），
 *       而且那 9 格的 `container` **不是** `CraftingContainer`（挂在升级自己的物品处理器上），
 *       只有**结果槽**挂着 `ResultContainer`；</li>
 *   <li><b>Refined Storage 的合成网格</b>：矩阵就是原版 `TransientCraftingContainer(3,3)`（属形态 1）。</li>
 * </ol>
 *
 * <p>所以按**证据强弱**依次尝试四条路（用了哪条一律写进 `note`，绝不静默）：
 * <pre>
 *   ① 身份     槽位的 container 就是矩阵容器                        —— 原版形态，最强
 *   ② 结果槽字段 原版 ResultSlot.craftSlots（反射读）给出矩阵        —— 通用、零模组知识
 *   ③ 上游自述  可达对象自己声明 getRecipeSlots()/getCraftMatrix()   —— 上游语义 + 自校验
 *   ④ 内容镜像  某容器与矩阵逐格同物、格数 == 宽×高，且**唯一**      —— 最弱；不唯一就拒绝
 * </pre>
 *
 * <p>**点击地址一律用 `slot.index`**（不是它在 `menu.slots` 里的位置）—— 上游把槽位建在 `menu.slots` 之外时
 * 只有 `slot.index` 可寻址；原版两者相同，故对随身/工作台行为不变（回归由既有夹具证明）。
 *
 * <p>**如实拒绝**（未知能力默认只读，不猜）：认不出就给码 + 说明，绝不"取第一个像的"。
 */
public final class GridDiscovery {

    /** 失败码：每一种都对应"我们确实不知道"，不是"大概能用"。 */
    public static final class Codes {
        /** 菜单里没有任何合成网格。 */
        public static final String NO_GRID = "no_grid";
        /** 菜单里没有 `ResultContainer` 结果槽（取不到产物 ⇒ 不认为是合成站）。 */
        public static final String NO_RESULT_SLOT = "no_result_slot";
        /** 有多个不同的网格容器（谁是目标？不猜）。 */
        public static final String AMBIGUOUS_GRID = "ambiguous_grid";
        /** 结果槽多于一个。 */
        public static final String AMBIGUOUS_RESULT = "ambiguous_result";
        /** 网格槽数量与 `getWidth()*getHeight()` 不符（布局不是规整矩形）。 */
        public static final String SHAPE_MISMATCH = "grid_shape_mismatch";
        /** 网格容器自己在 `getWidth()/getHeight()` 里抛了异常（模组实现问题，如实上报）。 */
        public static final String GRID_METRICS_FAILED = "grid_metrics_failed";
        /** 扫描槽位时至少有一格抛异常（细节见 note；**不因此崩服务端**）。 */
        public static final String SLOTS_PARTIAL = "slot_facts_partial";
        /** 菜单里没有玩家背包槽（无法把材料/产物放回去）。 */
        public static final String NO_PLAYER_SLOTS = "no_player_inventory_slots";
        /** 找到了结果槽与矩阵，但**认不出**对应的格子（内容镜像不唯一）⇒ 拒绝而不是猜。 */
        public static final String GRID_SLOTS_UNRESOLVED = "grid_slots_unresolved";

        private Codes() {
        }
    }

    /**
     * 一个槽位的**客观事实**（探针用）。刻意**不含**任何"这格是干什么的"的推断 ——
     * 报告事实、由人判断，正是本项目"未知模组默认只读"的做法。
     *
     * @param position   在"菜单可达槽位表"里的序号
     * @param address    **点击地址** = `slot.index`
     * @param registered 是否登记在 `menu.slots` 里（false = 上游自管，见类注释）
     */
    public record SlotInfo(int position, int address, boolean registered, String slotClass,
                           String containerClass, int x, int y, boolean active, String item) {

        public String describe() {
            return (registered ? "" : "*") + position + ":#" + address + "/" + slotClass
                    + "/" + containerClass + "@" + x + "," + y
                    + (active ? "" : "(inactive)") + (item.isEmpty() ? "" : "=" + item);
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

    /**
     * 扫描结果：菜单里**所有可达**的槽位 + 产出过槽位的"宿主对象"（供路径 ③ 用）+
     * **按类型收集到的可达 `CraftingContainer`**（vanilla 字段名在生产环境是 SRG 名 ⇒ 只能认类型）。
     */
    public record Scan(List<Slot> slots, List<Object> owners, List<CraftingContainer> matrices,
                       int slotExceptions, int registered) {
    }

    private GridDiscovery() {
    }

    // ==================== 槽位采集 ====================

    /**
     * 收集"菜单里所有可达的槽位"（不只是 `menu.slots`），以及产出过槽位的宿主对象。
     *
     * <p>**为什么**（实测教训）：上游可以把槽位建在 `menu.slots` **之外**并只设 `slot.index`
     * （精妙存储的升级槽与合成页签 9 格就是这样）⇒ 只遍历 `menu.slots` 的发现器**必然看不见**。
     */
    public static Scan scan(AbstractContainerMenu menu) {
        List<Slot> out = new ArrayList<>();
        List<Object> owners = new ArrayList<>();
        List<CraftingContainer> matrices = new ArrayList<>();
        Set<CraftingContainer> seenMatrices = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Slot> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (menu == null) {
            return new Scan(out, owners, matrices, 0, 0);
        }
        int registered = 0;
        for (Slot slot : menu.slots) {
            if (seen.add(slot)) {
                out.add(slot);
                registered++;
            }
        }
        int[] exceptions = {0};
        collectFromFields(menu, out, owners, matrices, seenMatrices, seen, 0, exceptions);
        return new Scan(out, owners, matrices, exceptions[0], registered);
    }

    private static void collectFromFields(Object target, List<Slot> out, List<Object> owners,
                                          List<CraftingContainer> matrices, Set<CraftingContainer> seenMatrices,
                                          Set<Slot> seen, int depth, int[] exceptions) {
        if (target == null || depth > 2) {
            return;
        }
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(target);
                } catch (Throwable t) {
                    exceptions[0]++;
                    continue;   // 取不到就跳过（未知模组字段可能拒绝访问）
                }
                collectValue(value, out, owners, matrices, seenMatrices, seen, depth, exceptions);
            }
            type = type.getSuperclass();
        }
    }

    private static void collectValue(Object value, List<Slot> out, List<Object> owners,
                                     List<CraftingContainer> matrices, Set<CraftingContainer> seenMatrices,
                                     Set<Slot> seen, int depth, int[] exceptions) {
        if (value == null) {
            return;
        }
        // **按类型**收矩阵容器：vanilla 的字段名在 Forge 生产环境里是 SRG 名（`f_xxxxx_`），
        // 写死 "craftSlots"/"getCraftSlots" 只在开发环境成立（2026-09-13 客户端实测 matrix=-(0) 就是这个原因）。
        if (value instanceof CraftingContainer crafting && seenMatrices.add(crafting)) {
            matrices.add(crafting);
            return;
        }
        if (value instanceof Slot slot) {
            if (seen.add(slot)) {
                out.add(slot);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object entry : map.values()) {
                collectValue(entry, out, owners, matrices, seenMatrices, seen, depth + 1, exceptions);
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object entry : iterable) {
                collectValue(entry, out, owners, matrices, seenMatrices, seen, depth + 1, exceptions);
            }
            return;
        }
        if (value instanceof Object[] array) {
            for (Object entry : array) {
                collectValue(entry, out, owners, matrices, seenMatrices, seen, depth + 1, exceptions);
            }
            return;
        }
        if (depth >= 2) {
            return;
        }
        // "自带槽位列表的宿主对象"（例如上游的 UpgradeContainerBase）：展开取槽位，并记住宿主
        Object slots = callByName(value, "getSlots");
        if (slots != null) {
            if (!owners.contains(value)) {
                owners.add(value);
            }
            collectValue(slots, out, owners, matrices, seenMatrices, seen, depth + 1, exceptions);
        }
    }

    // ==================== 认网格 ====================

    public static Result discover(AbstractContainerMenu menu, Player player) {
        if (menu == null) {
            return new Result(null, Codes.NO_GRID, "菜单为空", "-", 0, "-", 0);
        }
        Scan scan = scan(menu);
        List<Slot> slots = scan.slots();
        int slotExceptions = scan.slotExceptions();
        Container playerInv = player == null ? null : player.getInventory();

        // ① 结果槽：container 是 ResultContainer（上游常用匿名子类 ⇒ 类名可能是空串，标出来）
        List<Slot> resultSlots = new ArrayList<>();
        String resultSlotClass = "-";
        for (Slot slot : slots) {
            if (slot.container instanceof ResultContainer) {
                resultSlots.add(slot);
                if (resultSlotClass.equals("-")) {
                    String simple = className(slot.getClass());
                    resultSlotClass = simple + (slot instanceof ResultSlot ? "" : "!");
                }
            }
        }
        if (resultSlots.isEmpty()) {
            return new Result(null, Codes.NO_RESULT_SLOT, "菜单里没有 ResultContainer 结果槽（不认为是合成站）",
                    "-", 0, "-", 0);
        }
        if (resultSlots.size() > 1) {
            return new Result(null, Codes.AMBIGUOUS_RESULT, "结果槽多于一个",
                    "-", 0, resultSlotClass, resultSlots.size());
        }
        Slot resultSlot = resultSlots.get(0);

        // ② 矩阵：先看"有槽位的 container 本身就是 CraftingContainer"（原版形态），
        //    再用原版 ResultSlot 的 craftSlots 字段（通用、零模组知识）
        CraftingContainer matrix = null;
        String matrixBy = "";
        boolean ambiguousGrid = false;
        for (Slot slot : slots) {
            if (slot != resultSlot && slot.container instanceof CraftingContainer crafting) {
                if (matrix == null) {
                    matrix = crafting;
                    matrixBy = "slotContainerIdentity";
                } else if (matrix != crafting) {
                    ambiguousGrid = true;
                }
            }
        }
        if (matrix == null) {
            Object fromResult = matrixFromResultSlot(resultSlot);
            if (fromResult instanceof CraftingContainer crafting) {
                matrix = crafting;
                matrixBy = "resultSlotFieldByType";
            }
        }
        int matrixCandidates = scan.matrices().size();
        if (matrix == null && matrixCandidates == 1) {
            // 兜底：菜单里**按类型**可达的 CraftingContainer 只有一个 ⇒ 采用它（并在 note 里标明来源）
            matrix = scan.matrices().get(0);
            matrixBy = "reachableCraftingContainer(unique)";
        } else if (matrix == null && matrixCandidates > 1) {
            return new Result(null, Codes.AMBIGUOUS_GRID,
                    "可达的 CraftingContainer 有 " + matrixCandidates + " 个（哪个是目标不由我们猜）",
                    "-", 0, resultSlotClass, 1);
        }
        String matrixClass = matrix == null ? "-" : className(matrix.getClass());
        if (ambiguousGrid) {
            return new Result(null, Codes.AMBIGUOUS_GRID,
                    "菜单里有多个不同的网格容器（哪个是目标不由我们猜）", matrixClass, 0,
                    resultSlotClass, 1);
        }
        if (matrix == null) {
            return new Result(null, Codes.NO_GRID, "找不到矩阵容器（该站点当前没有合成网格）",
                    "-", 0, resultSlotClass, 1);
        }
        int width;
        int height;
        try {
            width = matrix.getWidth();
            height = matrix.getHeight();
        } catch (RuntimeException e) {
            return new Result(null, Codes.GRID_METRICS_FAILED,
                    "网格容器在 getWidth/getHeight 里抛了 " + e.getClass().getSimpleName(),
                    matrixClass, 0, resultSlotClass, 1);
        }
        int expected = width * height;

        // ③ 格子：身份 → 上游自述 → 内容镜像（证据由强到弱）
        List<Integer> gridSlots = new ArrayList<>();
        String gridBy = "";
        for (Slot slot : slots) {
            if (slot != resultSlot && slot.container == matrix) {
                gridSlots.add(address(slot));
            }
        }
        if (!gridSlots.isEmpty()) {
            gridBy = "slotContainerIdentity";
        }
        if (gridSlots.isEmpty()) {
            List<Slot> declared = gridByOwnerDeclaration(scan.owners(), matrix, expected, resultSlot);
            if (declared != null) {
                for (Slot slot : declared) {
                    gridSlots.add(address(slot));
                }
                gridBy = "ownerDeclaration(getRecipeSlots)";
            }
        }
        int mirrorCandidates = 0;
        if (gridSlots.isEmpty()) {
            Map<List<Slot>, Integer> mirror = gridByContentMirror(slots, matrix, expected, resultSlot);
            mirrorCandidates = mirror.size();
            if (mirror.size() == 1) {
                for (Slot slot : mirror.keySet().iterator().next()) {
                    gridSlots.add(address(slot));
                }
                gridBy = "contentMirror";
            }
        }
        if (gridSlots.isEmpty()) {
            return new Result(null, Codes.GRID_SLOTS_UNRESOLVED,
                    "找到了结果槽与 " + width + "x" + height + " 矩阵，但认不出对应格子"
                            + "（内容镜像候选=" + mirrorCandidates + "；唯一性不成立就不猜）",
                    matrixClass, expected, resultSlotClass, 1);
        }
        if (gridSlots.size() != expected) {
            return new Result(null, Codes.SHAPE_MISMATCH,
                    "网格槽数 " + gridSlots.size() + " != " + width + "x" + height,
                    matrixClass, expected, resultSlotClass, 1);
        }

        // ④ 玩家背包区间：主背包 + 快捷栏（容器槽号 <36；盔甲/副手 ≥36 排除）
        List<Integer> playerSlots = new ArrayList<>();
        for (Slot slot : slots) {
            if (playerInv != null && slot.container == playerInv && slot.getContainerSlot() < 36) {
                playerSlots.add(address(slot));
            }
        }
        if (playerSlots.isEmpty()) {
            return new Result(null, Codes.NO_PLAYER_SLOTS, "菜单里没有玩家背包槽（材料无处放回）",
                    matrixClass, expected, resultSlotClass, 1);
        }

        int[] grid = gridSlots.stream().mapToInt(Integer::intValue).toArray();
        InventoryCraft.GridSpec spec = new InventoryCraft.GridSpec(grid, width, height,
                address(resultSlot), playerSlots.get(0), playerSlots.get(playerSlots.size() - 1));
        String note = "matrixBy=" + matrixBy + " gridBy=" + gridBy
                + " matrixCandidates=" + matrixCandidates
                + " registeredSlots=" + scan.registered() + " reachableSlots=" + slots.size()
                + " playerSlots=" + playerSlots.size()
                + (resultSlot instanceof ResultSlot ? "" : " resultSlotNotVanillaType")
                + (slotExceptions > 0 ? " slotExceptions=" + slotExceptions + "(" + Codes.SLOTS_PARTIAL + ")" : "");
        return new Result(spec, "", note, matrixClass, expected, resultSlotClass, 1);
    }

    /** 点击地址：**`slot.index`**（上游把槽位建在 `menu.slots` 之外时只有它可寻址）。 */
    private static int address(Slot slot) {
        return slot.index;
    }

    /** 特征名：匿名类给 `(anonymous)`，别输出空串让人以为没读到。 */
    private static String className(Class<?> type) {
        String simple = type.getSimpleName();
        return simple == null || simple.isEmpty() ? "(anonymous)" : simple;
    }

    /**
     * 通用路径：从结果槽拿它的矩阵容器。**按类型找，不按名字找**。
     *
     * <p>原版 `ResultSlot` 有一个 `CraftingContainer` 字段（Mojang 名 `craftSlots`），但在 **Forge 生产环境**
     * 里 vanilla 字段被重映射成 SRG 名（`f_xxxxx_`）⇒ 写死 `"craftSlots"` 只有开发环境能命中
     * （2026-09-13 客户端实测 `matrix=-(0)` 就是这个原因）。⇒ 遍历类层次，**认字段/无参方法的类型**。
     */
    private static Object matrixFromResultSlot(Slot resultSlot) {
        Class<?> type = resultSlot.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (!CraftingContainer.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(resultSlot);
                    if (value != null) {
                        return value;
                    }
                } catch (Throwable ignored) {
                    // 读不到就继续找（可能被模块系统拒绝）
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (method.getParameterCount() != 0
                        || !CraftingContainer.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    Object value = method.invoke(resultSlot);
                    if (value != null) {
                        return value;
                    }
                } catch (Throwable ignored) {
                    // 同上
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    /**
     * 路径 ③：**上游自述** —— 宿主对象自己声明"哪些是我的配方槽位、我的矩阵是哪个"。
     * 只用**方法名**（`getRecipeSlots()` / `getCraftMatrix()`），不引编译期依赖；并且**自校验**：
     * 声明的槽位里必须能剔出"结果槽之外、数量 == 宽×高"的那一组，且矩阵必须是同一个对象。
     */
    private static List<Slot> gridByOwnerDeclaration(List<Object> owners, CraftingContainer matrix,
                                                     int expected, Slot resultSlot) {
        for (Object owner : owners) {
            Object declared = callByName(owner, "getRecipeSlots");
            if (!(declared instanceof List<?> list)) {
                continue;
            }
            Object declaredMatrix = callByName(owner, "getCraftMatrix");
            if (declaredMatrix != null && declaredMatrix != matrix) {
                continue;   // 与我们从结果槽读到的矩阵不是同一个 ⇒ 不采信
            }
            List<Slot> grid = new ArrayList<>();
            for (Object entry : list) {
                if (entry instanceof Slot slot && slot != resultSlot
                        && !(slot.container instanceof ResultContainer)) {
                    grid.add(slot);
                }
            }
            if (grid.size() == expected) {
                return grid;
            }
        }
        return null;
    }

    /**
     * 路径 ④：**内容镜像** —— 某个容器与矩阵**逐格同物**、格数 == 宽×高，且这样的容器**唯一**。
     * 不唯一就返回多个候选（调用方据此如实拒绝，不猜）。
     */
    private static Map<List<Slot>, Integer> gridByContentMirror(List<Slot> slots, CraftingContainer matrix,
                                                                int expected, Slot resultSlot) {
        Map<Container, List<Slot>> byContainer = new LinkedHashMap<>();
        for (Slot slot : slots) {
            if (slot == resultSlot || slot.container == matrix || slot.container instanceof ResultContainer) {
                continue;
            }
            try {
                if (slot.container.getContainerSize() != expected) {
                    continue;
                }
            } catch (RuntimeException e) {
                continue;
            }
            byContainer.computeIfAbsent(slot.container, key -> new ArrayList<>()).add(slot);
        }
        Map<List<Slot>, Integer> matches = new LinkedHashMap<>();
        for (Map.Entry<Container, List<Slot>> entry : byContainer.entrySet()) {
            if (entry.getValue().size() == expected && mirrorsMatrix(entry.getKey(), matrix, expected)) {
                matches.put(entry.getValue(), expected);
            }
        }
        return matches;
    }

    private static boolean mirrorsMatrix(Container container, CraftingContainer matrix, int expected) {
        try {
            for (int i = 0; i < expected; i++) {
                if (!ItemStack.matches(container.getItem(i), matrix.getItem(i))) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ==================== 事实表（探针用） ====================

    /** **全槽位事实表**：序号 / 点击地址 / 是否登记在 `menu.slots` / 槽类 / 容器类 / 坐标 / active / 物品。 */
    public static List<SlotInfo> describeSlots(AbstractContainerMenu menu) {
        List<SlotInfo> out = new ArrayList<>();
        if (menu == null) {
            return out;
        }
        Scan scan = scan(menu);
        int registered = scan.registered();
        for (int i = 0; i < scan.slots().size(); i++) {
            // **逐槽保护**：来源未知的槽位对象可能在任何 getter 里抛（模组实现千奇百怪）；
            // 一格出事不该让整张表（更不该让服务端）陪葬 —— 如实把异常写进那一格的描述里。
            Slot slot = scan.slots().get(i);
            try {
                ItemStack stack = slot.getItem();
                String item = stack.isEmpty() ? ""
                        : BuiltInRegistries.ITEM.getKey(stack.getItem()) + "x" + stack.getCount();
                out.add(new SlotInfo(i, address(slot), i < registered, className(slot.getClass()),
                        className(slot.container.getClass()), slot.x, slot.y, slot.isActive(), item));
            } catch (RuntimeException e) {
                out.add(new SlotInfo(i, -1, i < registered, "?", "?", 0, 0, false,
                        "EXCEPTION:" + e.getClass().getSimpleName()));
            }
        }
        return out;
    }

    /** 菜单身份（日志/报告用）：类名 + 槽数 + 菜单类型 id（**可能没有**）。 */
    public static String describeMenu(AbstractContainerMenu menu) {
        if (menu == null) {
            return "-";
        }
        String type;
        try {
            // **不许无保护地调 `menu.getType()`**：没有 MenuType 的菜单（原版 `InventoryMenu` 自己就是）
            // 会抛 UnsupportedOperationException —— 2026-09-13 实测把服务端 tick 循环打崩过。
            ResourceLocation key = BuiltInRegistries.MENU.getKey(menu.getType());
            type = key == null ? "?" : key.toString();
        } catch (RuntimeException e) {
            type = "unregistered(" + e.getClass().getSimpleName() + ")";
        }
        return className(menu.getClass()) + "(" + type + ") menuSlots=" + menu.slots.size();
    }

    // ==================== 反射小工具（只读、逐项容错） ====================

    private static Object callByName(Object target, String name) {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Method method = type.getDeclaredMethod(name);
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
}
