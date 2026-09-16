package com.dddgn.alice.task.craft;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;

import java.util.ArrayList;
import java.util.List;

/**
 * **随身 2×2 合成**（阶段 3-A / A2，D-186）：只用玩家**自带**的合成网格，**不碰世界**。
 *
 * <p>走的是**菜单协议**（与 L2 传输路线同源，D-163/D-165 已验证的那条）：
 * 拿起材料 → 右键在网格格里放**单个** → 把余量放回原槽 → 全部摆好后 shift-click 结果槽。
 * 不直接改背包字段、不凭空生成物品 —— **真消耗、真产物**。
 *
 * <p><b>槽位口径现在**由 {@link GridDiscovery} 现场发现**（S1-5a / D-193）</b>；下面这段是"随身菜单"的
 * 实际形态（与 D-163 记录一致），用来说明发现器认出来的东西长什么样，**不再是写死的常量**：
 * <pre>
 *   0        = 合成结果
 *   1..4     = 2×2 网格（行优先：1=(0,0) 2=(1,0) 3=(0,1) 4=(1,1)）
 *   5..8     = 盔甲
 *   9..35    = 主背包
 *   36..44   = 快捷栏
 *   45       = 副手
 * </pre>
 *
 * <p>**失败必清理**：任何一步失败都把网格里已摆的材料收回背包（不留半成品、不丢物品）。
 */
public final class InventoryCraft {

    /** 失败码（如实上报，供上层区分"缺料"与"协议没走通"）。 */
    public static final class Codes {
        /**
         * **发现器认不出当前菜单的合成网格**（S1-5a / D-193）。
         *
         * <p>取代原来的 `not_inventory_menu`：过去是"菜单必须**是**玩家自带菜单"，现在改成
         * "菜单里必须**能认出**一个合成网格" —— 通用发现器给出的 `GridSpec` 才是唯一依据，
         * 认不出就**如实失败**（不回退到任何写死的下标常量）。
         */
        public static final String GRID_UNRECOGNIZED = "grid_unrecognized";
        public static final String UNSUPPORTED_RECIPE = "unsupported_recipe";
        /**
         * 配方**放不进**当前认出来的网格（历史上叫 `recipe_not_2x2`；现在网格可能是 3×3 甚至更大，
         * 所以语义是"放不下"而不是"不是 2×2"——代码串保持不变以免打断既有日志/文档口径）。
         */
        public static final String NOT_2X2 = "recipe_not_2x2";
        public static final String MISSING_INGREDIENT = "missing_ingredient";
        public static final String CLICK_REJECTED = "click_rejected";
        public static final String RESULT_NOT_TAKEN = "result_not_taken";

        private Codes() {
        }
    }

    /**
     * **格网规格**（A3 泛化）：网格槽位（行优先）+ 宽高 + 结果槽位 + 背包槽位区间。
     *
     * <p>2×2（玩家自带 `InventoryMenu`）：grid={1,2,3,4} w=2 h=2 result=0 inv=[9,44]；
     * 3×3（工作台 `CraftingMenu`）：grid={1..9} w=3 h=3 result=0 inv=[10,45]。
     * 槽位**从既有布局记录推导**（D-163/D-165 的 L2 记录），不靠"我记得是这样"。
     */
    public record GridSpec(int[] gridSlots, int width, int height, int resultSlot,
                           int inventoryFirst, int inventoryLast) {
    }

    /** 一次合成尝试的结果。 */
    public record Result(boolean ok, String code, int crafts, int produced, List<String> consumed) {
        public String describe() {
            return (ok ? "OK" : "FAIL:" + code)
                    + " crafts=" + crafts + " produced=" + produced
                    + (consumed.isEmpty() ? "" : " consumed=" + consumed);
        }
    }

    private InventoryCraft() {
    }

    /**
     * **产物计数口径**（D-195 附注二）：不同站点的产物**落点不同** ——
     * 原版随身/工作台进玩家背包，而精妙存储的合成页签**进容器**（实测 `product_in_container=1`）。
     * 所以"产物有没有出来"这一判断必须由**站点**决定口径，不能写死成"数玩家背包"。
     */
    @FunctionalInterface
    public interface ProductCounter {
        int count(net.minecraft.world.item.Item item);
    }

    /**
     * 默认口径：只数**玩家背包**（原版站点；与历史行为一致）。
     */
    public static ProductCounter playerInventoryCounter(BotPlayer bot) {
        return item -> RecipeQuery.countInInventory(bot, item);
    }

    /**
     * 合出至少 {@code count} 个 {@code recipe} 的产物 —— **格网规格由发现器现场给出**（S1-5a / D-193）。
     *
     * <p>过去这里写死 `inventorySpec()`（= 记忆下来的 2×2 下标），只要换一个模组菜单就必然错位。
     * 现在改走 {@link GridDiscovery}：**认容器**（`CraftingContainer`/`ResultContainer`）得出格子与结果槽，
     * 认不出就如实返回 {@link Codes#GRID_UNRECOGNIZED}（**不回退**任何常量）。
     *
     * <p>调用方应先用 {@link RecipeQuery} 确认"料齐"，本方法**不做**选路决策。
     */
    public static Result craft(BotPlayer bot, AbstractContainerMenu menu, Recipe<?> recipe, int count,
                               com.dddgn.alice.action.WriteGrant grant) {
        GridDiscovery.Result discovery = GridDiscovery.discover(menu, bot);
        if (!discovery.ok()) {
            BotLog.warn("[InventoryCraft] 认不出合成网格 ⇒ 拒绝（不猜下标）：{}", discovery.describe());
            return new Result(false, Codes.GRID_UNRECOGNIZED, 0, 0, List.of());
        }
        return craft(bot, menu, recipe, count, discovery.spec(), playerInventoryCounter(bot), grant);
    }

    /** 兼容重载：显式给规格 ⇒ 产物口径按**玩家背包**（原版站点）。 */
    public static Result craft(BotPlayer bot, AbstractContainerMenu menu, Recipe<?> recipe, int count,
                               GridSpec spec, com.dddgn.alice.action.WriteGrant grant) {
        return craft(bot, menu, recipe, count, spec, playerInventoryCounter(bot), grant);
    }

    /**
     * 通用入口：显式给出格网规格**与产物计数口径**（D-195 附注二）。
     *
     * <p>为什么要注入口径：实测模组站点会把产物放进**容器**，若仍按"数玩家背包"判断，
     * 成功会被判成 `result_not_taken`（假失败）。
     */
    public static Result craft(BotPlayer bot, AbstractContainerMenu menu, Recipe<?> recipe, int count,
                               GridSpec spec, ProductCounter counter, com.dddgn.alice.action.WriteGrant grant) {
        if (!(recipe instanceof CraftingRecipe)) {
            return new Result(false, Codes.UNSUPPORTED_RECIPE, 0, 0, List.of());
        }
        int[] grid = gridLayout(recipe, spec);
        if (grid == null) {
            return new Result(false, Codes.NOT_2X2, 0, 0, List.of());
        }
        ItemStack preview = recipe.getResultItem(bot.serverLevel().registryAccess());
        int perCraft = Math.max(1, preview.getCount());
        int crafts = (int) Math.ceil(count / (double) perCraft);
        int produced = 0;
        List<String> consumed = new ArrayList<>();

        for (int round = 0; round < crafts; round++) {
            String placeFailure = placeGrid(bot, menu, recipe, grid, spec, grant);
            if (placeFailure != null) {
                clearGrid(bot, menu, spec, grant);
                return new Result(false, placeFailure, round, produced, consumed);
            }
            int before = counter.count(preview.getItem());
            if (!click(bot, menu, spec.resultSlot(), ClickType.QUICK_MOVE, grant)) {
                clearGrid(bot, menu, spec, grant);
                return new Result(false, Codes.CLICK_REJECTED, round, produced, consumed);
            }
            int after = counter.count(preview.getItem());
            if (after <= before) {
                clearGrid(bot, menu, spec, grant);
                return new Result(false, Codes.RESULT_NOT_TAKEN, round, produced, consumed);
            }
            produced += after - before;
            consumed.add(preview.getItem() + "+" + (after - before));
        }
        // 收尾：网格必须是空的（材料已被配方消耗；若有残留说明协议没走干净）
        clearGrid(bot, menu, spec, grant);
        return new Result(true, "", crafts, produced, consumed);
    }

    /** 返回网格占位（行优先，长度 4，元素=配方 ingredient 下标，-1=空）；非 2×2 ⇒ null。 */
    private static int[] gridLayout(Recipe<?> recipe, GridSpec spec) {
        List<Ingredient> ingredients = recipe.getIngredients();
        int cells = spec.width() * spec.height();
        if (recipe instanceof ShapedRecipe shaped) {
            int width = shaped.getWidth();
            int height = shaped.getHeight();
            if (width > spec.width() || height > spec.height()) {
                return null;
            }
            int[] grid = new int[cells];
            java.util.Arrays.fill(grid, -1);
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    int index = row * width + col;
                    if (index < ingredients.size() && !ingredients.get(index).isEmpty()) {
                        grid[row * spec.width() + col] = index;
                    }
                }
            }
            return grid;
        }
        if (ingredients.size() > cells) {
            return null;
        }
        int[] grid = new int[cells];
        java.util.Arrays.fill(grid, -1);
        for (int i = 0; i < ingredients.size(); i++) {
            grid[i] = i;
        }
        return grid;
    }

    /** 把配方要求的材料逐格摆进 2×2 网格（每格 1 个）。 */
    /**
     * 摆料。
     *
     * @return **null = 成功**；否则是失败码（{@link Codes#MISSING_INGREDIENT} 或
     *         {@link Codes#CLICK_REJECTED}）—— 刻意**区分**这两种：
     *         "缺料"与"我们点不动"是两回事，混成一个码会把排查方向带偏（D-195 附注一就是这么白费了一个回合）。
     */
    private static String placeGrid(BotPlayer bot, AbstractContainerMenu menu, Recipe<?> recipe, int[] grid,
                                    GridSpec spec, com.dddgn.alice.action.WriteGrant grant) {
        List<Ingredient> ingredients = recipe.getIngredients();
        Inventory inventory = bot.getInventory();
        for (int cell = 0; cell < grid.length; cell++) {
            int ingredientIndex = grid[cell];
            if (ingredientIndex < 0) {
                continue;
            }
            Ingredient ingredient = ingredients.get(ingredientIndex);
            int source = findInventorySlot(menu, inventory, ingredient, spec);
            if (source < 0) {
                BotLog.warn("[InventoryCraft] 缺料：网格格 {} 需要 {}，背包里找不到（区间 {}..{}）",
                        cell, ingredient.getItems().length > 0 ? ingredient.getItems()[0] : "?",
                        spec.inventoryFirst(), spec.inventoryLast());
                return Codes.MISSING_INGREDIENT;
            }
            int gridSlot = spec.gridSlots()[cell];
            if (!click(bot, menu, source, ClickType.PICKUP, grant)) {
                BotLog.warn("[InventoryCraft] 点击源槽被拒 address={}（背包区间 {}..{}）",
                        source, spec.inventoryFirst(), spec.inventoryLast());
                return Codes.CLICK_REJECTED;
            }
            if (!click(bot, menu, gridSlot, ClickType.PICKUP, 1, grant)) {   // 右键：只放 1 个
                click(bot, menu, source, ClickType.PICKUP, grant);           // 放回
                BotLog.warn("[InventoryCraft] 点击网格格被拒 cell={} address={}（可达槽位 {} 个）",
                        cell, gridSlot, GridDiscovery.scan(menu).slots().size());
                return Codes.CLICK_REJECTED;
            }
            // 余量放回原槽（光标为空时是无害的空点）
            click(bot, menu, source, ClickType.PICKUP, grant);
        }
        return null;
    }

    /** 把网格里的东西全部收回背包（失败清理 / 收尾）。 */
    private static void clearGrid(BotPlayer bot, AbstractContainerMenu menu, GridSpec spec,
                                  com.dddgn.alice.action.WriteGrant grant) {
        for (int cell = 0; cell < spec.gridSlots().length; cell++) {
            int gridSlot = spec.gridSlots()[cell];
            Slot gridCell = GridDiscovery.slotByAddress(menu, gridSlot);
            if (gridCell == null || gridCell.getItem().isEmpty()) {
                continue;
            }
            click(bot, menu, gridSlot, ClickType.QUICK_MOVE, grant);
        }
    }

    /**
     * 找背包里第一个匹配该 ingredient 的槽位（地址落在 {@code spec} 的背包区间内）。
     *
     * <p>用**一次发现的槽位表**来找（而不是 `menu.getSlot(地址)` 逐个取）：既避免对上游自管地址越界，
     * 也避免 O(n²) 反复扫描。地址用 `slot.index`（点击地址），与摆放/清理保持同一口径。
     */
    private static int findInventorySlot(AbstractContainerMenu menu, Inventory inventory, Ingredient ingredient,
                                         GridSpec spec) {
        for (Slot slot : GridDiscovery.scan(menu).slots()) {
            if (slot.container != inventory || slot.index < spec.inventoryFirst()
                    || slot.index > spec.inventoryLast()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && ingredient.test(stack)) {
                return slot.index;
            }
        }
        return -1;
    }

    private static boolean click(BotPlayer bot, AbstractContainerMenu menu, int slot, ClickType type,
                                 com.dddgn.alice.action.WriteGrant grant) {
        return click(bot, menu, slot, type, 0, grant);
    }

    /**
     * 菜单点击。**只拒绝负数地址**。
     *
     * <p>**2026-09-13 实测缺陷（D-195 附注一）**：这里原本还有 `slot >= menu.slots.size()` 的守卫，
     * 于是"精妙存储的合成页签"（那 9 格与结果槽建在 `menu.slots` **之外**，地址 64..72/73，
     * 而 `menu.slots.size()==63`）**每一次点击都被静默拒绝** —— 材料一颗没动、产物为 0，
     * 而失败码却报成 `missing_ingredient`（把"我们点不动"说成"你没料"），白费一个回合。
     * ⇒ **地址的合法性由调用方用"发现出来的槽位集合"保证**（{@link GridDiscovery#scan}），这里只管协议。
     */
    private static boolean click(BotPlayer bot, AbstractContainerMenu menu, int slot, ClickType type, int button,
                                 com.dddgn.alice.action.WriteGrant grant) {
        if (slot < 0) {
            return false;
        }
        // ⚠️ **R5-残 收口（2026-09-16）**：与 `FurnaceStation.click` 同一套**编译期强制** ——
        // 调本原语必须显式交出 `WriteGrant`，且理由必须属于**菜单写入家族**（`WriteReason.menuWrite()`：
        // 世界容器 `container()` 或合成网格 `CRAFT_GRID`）。原先"记账靠调用方自觉"⇒ 新增模组适配默认无理由。
        // **不在这里记账**（合成网格不吃容器写入预算；计数归调用方）。
        if (grant == null || !grant.reason().menuWrite()) {
            BotLog.warn("[InventoryCraft] clicked(slot={}, type={}) 被拒：缺菜单写入授权（grant={}）",
                    slot, type, grant == null ? "null" : grant.describe());
            return false;
        }
        try {
            menu.clicked(slot, button, type, bot);
            menu.broadcastChanges();
            return true;
        } catch (RuntimeException | Error thrown) {
            BotLog.warn("[InventoryCraft] clicked(slot={}, button={}, type={}) 抛异常 {}",
                    slot, button, type, thrown.toString());
            return false;
        }
    }
}
