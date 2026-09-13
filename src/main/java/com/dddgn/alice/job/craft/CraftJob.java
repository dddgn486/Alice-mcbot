package com.dddgn.alice.job.craft;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.job.Job;
import com.dddgn.alice.action.MenuSession;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.task.TaskTarget;
import com.dddgn.alice.task.craft.CraftStation;
import com.dddgn.alice.task.craft.FurnaceStation;
import com.dddgn.alice.task.craft.GridDiscovery;
import com.dddgn.alice.task.craft.InventoryCraft;
import com.dddgn.alice.task.craft.RecipeQuery;
import com.dddgn.alice.task.craft.StationProvision;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

/**
 * **合成 / 熔炼 Job**（阶段 3-A / A5，D-199）：把"要什么"接进决策层之后的那条**生产**路径。
 *
 * <p>它是**薄**的：不重新实现合成，只做"选站 → （必要时）装配 → 调已验证的原语 → **看世界事实判成功** → 失败也清场"。
 * 用到的原语全部是前面各步客户端验收过的：
 * <ul>
 *   <li>{@link RecipeQuery}：**只读**配方查询（路线/材料/缺料）—— 没有配方就如实拒绝，不猜；</li>
 *   <li>{@link CraftStation}：工作站**由玩家切换**（这里只读 `selected`，**不自动选优**）；</li>
 *   <li>{@link GridDiscovery} + {@link InventoryCraft}：认网格 + 摆料/取产物（原版随身/工作台/模组页签同一套）；</li>
 *   <li>{@link FurnaceStation}：3 格烹饪槽（原版熔炉与精妙"熔炼升级页签"同一套判据）；</li>
 *   <li>{@link StationProvision}：需要的能力没装就**装配**（走 `WriteReason.STATION_PROVISION` + 容器写入预算）。</li>
 * </ul>
 *
 * <p><b>装配独立成层</b>（用户裁定）：本 Job 只"按需装上"，**不自动拆回** —— 拆是独立的一层
 * （夹具的"装→验→拆"只是自检纪律，不是生产行为）。
 *
 * <p><b>判成功只看世界事实</b>：产物真的多了（背包 + 容器）、材料真的少了；**不看**原语的返回值当结论
 * （返回值只用来给失败码与过程证据）。
 *
 * <p><b>失败也清场</b>：网格里的半成品由 {@link InventoryCraft} 自己收回（其契约）；烧炼路径在超时/失败时
 * 把输入取回；菜单一律关掉。**绝不凭空补物品**（合成只真消耗真产物）。
 */
public final class CraftJob implements Job {

    public static final String NAME = "craft";

    /** 站点探测/开菜单半径。 */
    private static final int STATION_RADIUS = 6;
    /** 开菜单等待上限（tick）。 */
    private static final int OPEN_TICKS = 60;
    /** 每个产物的烧炼预算（tick；原版 200/个，给足余量）。 */
    private static final int SMELT_TICKS_PER_ITEM = 240;
    /** 烧炼预算下限（tick）。 */
    private static final int SMELT_TICKS_MIN = 400;

    private enum Phase { PREPARE, OPEN, WAIT_OPEN, PROVISION, DO_CRAFT, WAIT, VERIFY, CLEANUP, DONE }

    public static final class Codes {
        public static final String NO_RECIPE = "no_recipe";
        public static final String MACHINE_RECIPE = "machine_recipe_unsupported";
        public static final String MISSING = "missing_ingredients";
        public static final String STATION = "station_unavailable";
        public static final String UNSUPPORTED = "recipe_type_unsupported_by_job";
        public static final String INPUT_PLACE = "input_place_failed";
        public static final String FUEL_PLACE = "fuel_place_failed";
        public static final String SMELT_TIMEOUT = "smelt_timeout";
        public static final String PARTIAL = "partial_quota";
        public static final String NO_PRODUCT = "no_product";

        private Codes() {
        }
    }

    private final BotPlayer bot;
    private final String itemId;
    private final int count;
    private final int maxTicks;

    private Phase phase = Phase.PREPARE;
    private int ticks;
    private int phaseTicks;

    private RecipeQuery.Result query;
    private Recipe<?> recipe;
    private Item target;
    private CraftStation.Opened opened;
    private int productBefore;
    private int produced;
    private boolean provisionAttempted;
    private boolean inputPlaced;
    private boolean fuelPlaced;

    private String terminalReason = "";
    private String failure = "";

    public CraftJob(BotPlayer bot, String itemId, int count, int maxTicks) {
        this.bot = bot;
        this.itemId = itemId == null ? "" : itemId;
        this.count = Math.max(1, count);
        this.maxTicks = Math.max(100, maxTicks);
    }

    @Override
    public String jobName() {
        return NAME;
    }

    @Override
    public String taskName() {
        return NAME;
    }

    @Override
    public TaskTarget target() {
        BlockPos anchor = opened != null && opened.pos() != null ? opened.pos() : bot.blockPosition();
        return TaskTarget.block(anchor);
    }

    @Override
    public String failureReason() {
        return failure;
    }

    @Override
    public String terminalReason() {
        return terminalReason;
    }

    @Override
    public String progressSummary() {
        return "craft " + itemId + " x" + count + " phase=" + phase + " produced=" + produced
                + (opened == null ? "" : " station=" + opened.station().id());
    }

    @Override
    public Status tick() {
        if (phase == Phase.DONE) {
            return failure.isEmpty() ? Status.DONE : Status.FAILED;
        }
        if (++ticks > maxTicks) {
            return failAndFinish("job_timeout");
        }
        phaseTicks++;
        return switch (phase) {
            case PREPARE -> prepare();
            case OPEN -> open();
            case WAIT_OPEN -> waitOpen();
            case PROVISION -> provision();
            case DO_CRAFT -> doCraft();
            case WAIT -> waitSmelt();
            case VERIFY -> verify();
            case CLEANUP -> cleanup();
            case DONE -> finish();
        };
    }

    // ==================== 相位 ====================

    /** 只读配方查询：路线/材料/缺料都从**运行时配方表**来（没有配方就如实拒绝，不猜）。 */
    private Status prepare() {
        target = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(itemId));
        if (target == net.minecraft.world.item.Items.AIR) {
            return failAndFinish(Codes.NO_RECIPE + ":unknown_item:" + itemId);
        }
        query = RecipeQuery.query(bot.getServer(), bot, target, count);
        BotLog.info("[CraftJob] query item={} x{} → {}", itemId, count, query.describe());
        if (query.verdict() == RecipeQuery.Verdict.NO_RECIPE) {
            return failAndFinish(Codes.NO_RECIPE);
        }
        if (query.verdict() == RecipeQuery.Verdict.MACHINE_RECIPE_UNSUPPORTED) {
            return failAndFinish(Codes.MACHINE_RECIPE);
        }
        if (query.verdict() == RecipeQuery.Verdict.MACHINE_ROUTE) {
            // S1/D-204：**读得出路线、但 Alice 还没有该机器的执行适配** ⇒ 如实拒绝，不假装能做。
            // （路线本身有出处：机器类型 + 输入 + 输出，已在 RecipeQuery 里给出；等 S4 单机闭环再谈执行。）
            String machine = query.route() == null ? "?" : query.route().station();
            return failAndFinish(Codes.MACHINE_RECIPE + ":not_executable:" + machine);
        }
        if (query.verdict() == RecipeQuery.Verdict.MISSING_INGREDIENTS) {
            return failAndFinish(Codes.MISSING + ":" + describeMissing());
        }
        if (query.route() == null) {
            return failAndFinish(Codes.NO_RECIPE + ":no_route");
        }
        recipe = bot.getServer().getRecipeManager()
                .byKey(ResourceLocation.tryParse(query.route().recipeId())).orElse(null);
        if (recipe == null) {
            return failAndFinish(Codes.NO_RECIPE + ":recipe_gone:" + query.route().recipeId());
        }
        productBefore = productCount();
        return advance(Phase.OPEN);
    }

    /** 开**玩家当前选中的**工作站（不自动选优）；需要能力没装就先装配。 */
    private Status open() {
        opened = CraftStation.open(bot, STATION_RADIUS);
        if (!opened.ok()) {
            return failAndFinish(Codes.STATION + ":" + opened.code() + ":" + opened.detail());
        }
        BotLog.info("[CraftJob] 站点 {}", opened.describe());
        // 方块站点：菜单要等 `MenuSession` 把容器打开（**不许**假设"open 返回就已有菜单"）
        if (opened.session() != null && opened.menu() == null) {
            return advance(Phase.WAIT_OPEN);
        }
        return advance(needsProvision() ? Phase.PROVISION : Phase.DO_CRAFT);
    }

    /** 等菜单打开（方块站点）；打开后再决定"要不要先装配"。 */
    private Status waitOpen() {
        MenuSession.State state = opened.session().tick();
        if (state == MenuSession.State.FAILED) {
            return failAndFinish(Codes.STATION + ":menu_open_failed:"
                    + opened.session().failure());
        }
        if (state != MenuSession.State.OPEN) {
            return phaseTicks > OPEN_TICKS ? failAndFinish(Codes.STATION + ":menu_open_timeout")
                    : Status.RUNNING;
        }
        BotLog.info("[CraftJob] 菜单已开 slots={}", currentMenu() == null ? -1 : currentMenu().slots.size());
        return advance(needsProvision() ? Phase.PROVISION : Phase.DO_CRAFT);
    }

    /** 当前站点需要的能力装了吗（**数据驱动**：站点自己声明要装什么升级）。 */
    private boolean needsProvision() {
        if (provisionAttempted || opened == null) {
            return false;
        }
        ResourceLocation upgrade = opened.station().provisionUpgrade();
        if (upgrade == null) {
            return false;
        }
        Item upgradeItem = BuiltInRegistries.ITEM.get(upgrade);
        AbstractContainerMenu menu = currentMenu();
        return upgradeItem != net.minecraft.world.item.Items.AIR && menu != null
                && !StationProvision.containerHas(menu, bot, upgradeItem);
    }

    /** 站点的当前菜单（随身站点 `opened.menu()` 就是 `inventoryMenu`）。 */
    private AbstractContainerMenu currentMenu() {
        if (opened != null && opened.menu() != null) {
            return opened.menu();
        }
        return bot.containerMenu;
    }

    /** 按需装配（`open` 已确认容器里没有这个能力）：预算闸门 + 装完**关掉再开**（上游在菜单构造时建容器）。 */
    private Status provision() {
        provisionAttempted = true;
        AbstractContainerMenu menu = currentMenu();
        ResourceLocation upgrade = opened.station().provisionUpgrade();
        Item upgradeItem = BuiltInRegistries.ITEM.get(upgrade);
        if (StationProvision.countInInventory(bot, upgradeItem) < 1) {
            return failAndFinish(StationProvision.Codes.ITEM_ABSENT);
        }
        BlockPos pos = opened.pos();
        if (pos == null || !StationProvision.allowContainerWrite(bot, pos)) {
            return failAndFinish(StationProvision.Codes.BUDGET_REFUSED);
        }
        if (!StationProvision.moveIntoContainer(bot, menu, upgradeItem, pos)) {
            return failAndFinish(StationProvision.Codes.MOVE_REJECTED);
        }
        // 关掉再开：上游在**菜单构造**时才按升级建容器（A4b 实测）
        closeSession("provisioned");
        opened = null;
        return advance(Phase.OPEN);
    }

    /** 调已验证的原语：网格合成（随身/工作台/模组合成页签）或 3 格烹饪（原版熔炉/熔炼页签）。 */
    private Status doCraft() {
        AbstractContainerMenu menu = currentMenu();
        if (menu == null) {
            return failAndFinish(Codes.STATION + ":menu_closed");
        }
        if (isCooking()) {
            return doCooking(menu);
        }
        GridDiscovery.Result grid = GridDiscovery.discover(menu, bot);
        if (!grid.ok()) {
            return failAndFinish(InventoryCraft.Codes.GRID_UNRECOGNIZED + ":" + grid.code());
        }
        InventoryCraft.ProductCounter counter =
                item -> StationProvision.countLandedProduct(menu, bot, item, grid.spec());
        InventoryCraft.Result crafted = InventoryCraft.craft(bot, menu, recipe, count, grid.spec(), counter);
        BotLog.info("[CraftJob] craft {} → {}", grid.describe().substring(0, Math.min(60,
                grid.describe().length())), crafted.describe());
        if (!crafted.ok()) {
            // `InventoryCraft` 的契约：失败时它自己把网格里的材料收回背包（不留半成品）
            return failAndFinish(crafted.code());
        }
        produced = crafted.produced();
        return advance(Phase.VERIFY);
    }

    /** 烧炼：放输入 + 放燃料（燃料由 `ForgeHooks.getBurnTime` 给事实，不写死煤）。 */
    private Status doCooking(AbstractContainerMenu menu) {
        FurnaceStation.Result station = FurnaceStation.discover(menu);
        if (!station.ok()) {
            return failAndFinish(FurnaceStation.Codes.NO_FURNACE + ":" + station.code());
        }
        FurnaceStation.Found found = station.found();
        Item input = firstHeldMatching(inputIngredient());
        if (input == null) {
            return failAndFinish(Codes.MISSING + ":input_not_held");
        }
        if (!inputPlaced) {
            if (!FurnaceStation.placeOne(bot, menu, found, found.input(), input)) {
                return failAndFinish(Codes.INPUT_PLACE);
            }
            inputPlaced = true;
        }
        if (!fuelPlaced) {
            Item fuel = firstHeldFuel();
            if (fuel == null) {
                return failAndFinish(Codes.MISSING + ":fuel_not_held");
            }
            if (!FurnaceStation.placeOne(bot, menu, found, found.fuel(), fuel)) {
                return failAndFinish(Codes.FUEL_PLACE);
            }
            fuelPlaced = true;
        }
        return advance(Phase.WAIT);
    }

    /** 等它烧（**世界事实** = 输出格真的有了东西），超时把输入取回并如实失败。 */
    private Status waitSmelt() {
        AbstractContainerMenu menu = currentMenu();
        FurnaceStation.Result station = FurnaceStation.discover(menu);
        if (!station.ok()) {
            return failAndFinish(FurnaceStation.Codes.NO_FURNACE + ":" + station.code());
        }
        FurnaceStation.Found found = station.found();
        ItemStack output = FurnaceStation.stackAt(menu, found.output());
        if (output != null && !output.isEmpty()) {
            boolean taken = FurnaceStation.takeAll(bot, menu, found.output());
            BotLog.info("[CraftJob] 出炉 {} x{} taken={}", output.getItem(), output.getCount(), taken);
            return advance(Phase.VERIFY);
        }
        int budget = Math.max(SMELT_TICKS_MIN, SMELT_TICKS_PER_ITEM * count);
        if (phaseTicks > budget) {
            boolean back = FurnaceStation.takeAll(bot, menu, found.input());
            BotLog.warn("[CraftJob] 烧炼超时 {} tick，输入已取回={}", phaseTicks, back);
            return failAndFinish(Codes.SMELT_TIMEOUT + ":inputReturned=" + back);
        }
        return Status.RUNNING;
    }

    /** **判成功只看世界事实**：产物真的多了。 */
    private Status verify() {
        int after = productCount();
        produced = Math.max(produced, after - productBefore);
        if (after - productBefore >= count) {
            terminalReason = "crafted:" + itemId + " x" + (after - productBefore);
            BotLog.info("[CraftJob] 世界事实 product {}→{}（目标 x{}）⇒ 达成", productBefore, after, count);
            closeSession("crafted");
            return finish();
        }
        if (after - productBefore > 0) {
            return failAndFinish(Codes.PARTIAL + ":" + (after - productBefore) + "/" + count);
        }
        return failAndFinish(Codes.NO_PRODUCT);
    }

    /** 失败清场：关菜单（网格半成品由 `InventoryCraft` 收回；烧炼的半成品这里取回）。 */
    private Status cleanup() {
        AbstractContainerMenu menu = currentMenu();
        if (menu != null && isCooking() && inputPlaced) {
            FurnaceStation.Result station = FurnaceStation.discover(menu);
            if (station.ok()) {
                FurnaceStation.takeAll(bot, menu, station.found().input());
            }
        }
        closeSession("craft_failed");
        return finish();
    }

    private Status advance(Phase next) {
        phase = next;
        phaseTicks = 0;
        return Status.RUNNING;
    }

    private Status failAndFinish(String code) {
        failure = code;
        BotLog.warn("[CraftJob] 失败 code={} {}", code, progressSummary());
        return advance(Phase.CLEANUP);
    }

    private Status finish() {
        phase = Phase.DONE;
        closeSession("craft_job_done");
        return failure.isEmpty() ? Status.DONE : Status.FAILED;
    }

    // ==================== 事实小工具 ====================

    /** 路线是不是"按时间工作"的烧炼（**按配方类型**判断，不按站点名字）。 */
    private boolean isCooking() {
        return recipe instanceof AbstractCookingRecipe
                || (query != null && query.route() != null && "furnace".equals(query.route().station()));
    }

    private Ingredient inputIngredient() {
        if (recipe == null || recipe.getIngredients().isEmpty()) {
            return Ingredient.EMPTY;
        }
        return recipe.getIngredients().get(0);
    }

    /** 背包里第一个匹配该材料的物品（**事实**：现场有什么就用什么）。 */
    private Item firstHeldMatching(Ingredient ingredient) {
        if (ingredient == null || ingredient.isEmpty()) {
            return null;
        }
        var inventory = bot.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && ingredient.test(stack)) {
                return stack.getItem();
            }
        }
        return null;
    }

    /** 背包里第一个**真能烧**的东西（`ForgeHooks.getBurnTime` 给事实，不写死煤）。 */
    private Item firstHeldFuel() {
        var inventory = bot.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            int burn = net.minecraftforge.common.ForgeHooks.getBurnTime(stack, null);
            if (burn > 0) {
                return stack.getItem();
            }
        }
        return null;
    }

    /** 产物数（**背包 + 当前菜单容器**：模组站点会把产物放进容器，D-195 附注二）。 */
    private int productCount() {
        if (target == null) {
            return 0;
        }
        int total = RecipeQuery.countInInventory(bot, target);
        AbstractContainerMenu menu = currentMenu();
        if (menu != null) {
            total += StationProvision.countInContainer(menu, bot, target);
        }
        return total;
    }

    private String describeMissing() {
        StringBuilder sb = new StringBuilder();
        for (RecipeQuery.Material material : query.missing()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(material.describe()).append("x").append(Math.max(0, material.shortage()));
        }
        return sb.toString();
    }

    private void closeSession(String reason) {
        if (opened != null && opened.session() != null) {
            opened.session().close(reason);
        }
        opened = null;
    }
}
