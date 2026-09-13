package com.dddgn.alice.task.craft;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
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
 * <p>槽位口径（1.20.1 `InventoryMenu`，与 D-163 记录的布局一致）：
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
        public static final String NOT_INVENTORY_MENU = "not_inventory_menu";
        public static final String UNSUPPORTED_RECIPE = "unsupported_recipe";
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

    /** 玩家自带 2×2 网格规格。 */
    public static GridSpec inventorySpec() {
        return new GridSpec(new int[]{1, 2, 3, 4}, 2, 2, 0, 9, 44);
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
     * 合出至少 {@code count} 个 {@code recipe} 的产物（只用随身 2×2）。
     *
     * <p>调用方应先用 {@link RecipeQuery} 确认"料齐 + {@code grid=2x2}"，本方法**不再**做选路决策。
     */
    public static Result craft(BotPlayer bot, AbstractContainerMenu menu, Recipe<?> recipe, int count) {
        return craft(bot, menu, recipe, count, inventorySpec());
    }

    /** 通用入口：显式给出格网规格（2×2 随身 / 3×3 工作台）。 */
    public static Result craft(BotPlayer bot, AbstractContainerMenu menu, Recipe<?> recipe, int count,
                               GridSpec spec) {
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
            if (!placeGrid(bot, menu, recipe, grid, spec)) {
                clearGrid(bot, menu, spec);
                return new Result(false, Codes.MISSING_INGREDIENT, round, produced, consumed);
            }
            int before = RecipeQuery.countInInventory(bot, preview.getItem());
            if (!click(bot, menu, spec.resultSlot(), ClickType.QUICK_MOVE)) {
                clearGrid(bot, menu, spec);
                return new Result(false, Codes.CLICK_REJECTED, round, produced, consumed);
            }
            int after = RecipeQuery.countInInventory(bot, preview.getItem());
            if (after <= before) {
                clearGrid(bot, menu, spec);
                return new Result(false, Codes.RESULT_NOT_TAKEN, round, produced, consumed);
            }
            produced += after - before;
            consumed.add(preview.getItem() + "+" + (after - before));
        }
        // 收尾：网格必须是空的（材料已被配方消耗；若有残留说明协议没走干净）
        clearGrid(bot, menu, spec);
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
    private static boolean placeGrid(BotPlayer bot, AbstractContainerMenu menu, Recipe<?> recipe, int[] grid,
                                     GridSpec spec) {
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
                BotLog.warn("[InventoryCraft] 缺料：网格格 {} 需要 {}，背包里找不到",
                        cell, ingredient.getItems().length > 0 ? ingredient.getItems()[0] : "?");
                return false;
            }
            int gridSlot = spec.gridSlots()[cell];
            if (!click(bot, menu, source, ClickType.PICKUP)) {
                return false;
            }
            if (!click(bot, menu, gridSlot, ClickType.PICKUP, 1)) {   // 右键：只放 1 个
                click(bot, menu, source, ClickType.PICKUP);           // 放回
                return false;
            }
            // 余量放回原槽（光标为空时是无害的空点）
            click(bot, menu, source, ClickType.PICKUP);
        }
        return true;
    }

    /** 把网格里的东西全部收回背包（失败清理 / 收尾）。 */
    private static void clearGrid(BotPlayer bot, AbstractContainerMenu menu, GridSpec spec) {
        for (int cell = 0; cell < spec.gridSlots().length; cell++) {
            int gridSlot = spec.gridSlots()[cell];
            if (menu.getSlot(gridSlot).getItem().isEmpty()) {
                continue;
            }
            click(bot, menu, gridSlot, ClickType.QUICK_MOVE);
        }
    }

    /** 找背包里第一个匹配该 ingredient 的槽位（9..44）。 */
    private static int findInventorySlot(AbstractContainerMenu menu, Inventory inventory, Ingredient ingredient,
                                         GridSpec spec) {
        for (int slot = spec.inventoryFirst(); slot <= spec.inventoryLast(); slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (!stack.isEmpty() && ingredient.test(stack)) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean click(BotPlayer bot, AbstractContainerMenu menu, int slot, ClickType type) {
        return click(bot, menu, slot, type, 0);
    }

    private static boolean click(BotPlayer bot, AbstractContainerMenu menu, int slot, ClickType type, int button) {
        if (slot < 0 || slot >= menu.slots.size()) {
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
