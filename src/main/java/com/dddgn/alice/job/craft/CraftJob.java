package com.dddgn.alice.job.craft;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.MachineMap;
import com.dddgn.alice.job.Job;
import com.dddgn.alice.action.MenuSession;
import com.dddgn.alice.action.WriteBudget;
import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.task.TaskTarget;
import com.dddgn.alice.task.craft.CraftStation;
import com.dddgn.alice.task.craft.FurnaceStation;
import com.dddgn.alice.task.craft.GridDiscovery;
import com.dddgn.alice.task.craft.InventoryCraft;
import com.dddgn.alice.task.craft.MachineCycle;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** 开机找机器的立方盒半径（与 S4 夹具同值：起点在平台远角时半径 6 不够）。 */
    private static final int MACHINE_SCAN_RADIUS = 12;
    /** 走到机器旁的上限（tick）。 */
    private static final int MACHINE_WALK_TICKS = 400;
    /** 等机器出产物的上限（tick；★不缩短：真实机器进度，缩短只会把真绿变假红）。 */
    private static final int MACHINE_WAIT_TICKS = 600;

    /** 站点探测/开菜单半径。 */
    private static final int STATION_RADIUS = 6;
    /** 开菜单等待上限（tick）。 */
    private static final int OPEN_TICKS = 60;
    /** 每个产物的烧炼预算（tick；原版 200/个，给足余量）。 */
    private static final int SMELT_TICKS_PER_ITEM = 240;
    /** 烧炼预算下限（tick）。 */
    private static final int SMELT_TICKS_MIN = 400;

    private enum Phase { PREPARE, OPEN, WAIT_OPEN, PROVISION, DO_CRAFT, MACHINE, WAIT, VERIFY, CLEANUP, DONE }

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

    /** 机器路线（(c) 增量 2 / D-217）：闭环执行器 + 它报出的事实（生产侧只留一段 SUMMARY）。 */
    private MachineCycle machine;
    private final Map<String, String> machineFacts = new LinkedHashMap<>();
    private final List<String> machineFailedChecks = new ArrayList<>();
    private Item machineInput;
    private int machineInputCount;
    private Item machineOutput;
    private int machineOutputCount;

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
        BlockPos anchor = opened != null && opened.pos() != null ? opened.pos()
                : (machine != null && machine.machinePos() != null ? machine.machinePos() : bot.blockPosition());
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

    /**
     * **机器路线的事实出口**（(c) 增量 2 / D-217）：只在走了机器路线时非空。
     * 生产调用方用不到它；电池自检 `craft_machine` 靠它断言"**真的走了机器路线**"（否则可能是合成/熔炼路线，
     * 那就成了假绿）。键与夹具 `[MachineCycle] SUMMARY` 同源（同一份执行器出口）。
     */
    public Map<String, String> machineFacts() {
        return Map.copyOf(machineFacts);
    }

    @Override
    public String progressSummary() {
        return "craft " + itemId + " x" + count + " phase=" + phase + " produced=" + produced
                + (opened == null ? "" : " station=" + opened.station().id())
                + (machineFacts.isEmpty() ? "" : " machine=" + machineFacts.getOrDefault("machine", "-")
                        + " walk=" + machineFacts.getOrDefault("walk_state", "-"));
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
            case MACHINE -> runMachine();
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
            return prepareMachineRoute();
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

    /**
     * **机器路线**（(c) 增量 2 / D-217）：把 S4 已客户端实测过的那条闭环接进生产路径。
     *
     * <p><b>准入是数据驱动的</b>：`route.station()` 是**机器方块 id**（S3/D-209 起），拿它查
     * {@link MachineMap}；只有 {@link MachineMap.Capability#EXECUTABLE} 的行才允许驱动 —— 那一档的门槛是
     * "① 有执行适配器 ② `menuClass` 已客户端实测 ③ 有客户端验证记录"（前两条由 `tools/machine-map.py` 静态强制）。
     * 其余一律如实拒绝 `not_executable:<station>`（与 S1 的文案同源，便于对账）。
     *
     * <p><b>本轮只支持单物品输入</b>（与 S4 实测同形：一台机器 + 一道配方）：多输入、或化学品/流体输入，
     * **如实拒绝**（不猜语义）。缺料也在**出发前**判掉 —— 别走到机器旁才发现喂不进去。
     *
     * <p><b>不补电</b>（D-216 红线①）：交给执行器的**补电回调是 `null`**（生产路径里连那个类型名都不出现 ——
     * 门禁 `tools/check-precharge-containment.sh` 机械断言），机器没电就是
     * `machine_no_energy` 如实失败 —— 生产不许凭空造能量。
     */
    private Status prepareMachineRoute() {
        RecipeQuery.Route route = query.route();
        if (route == null) {
            return failAndFinish(Codes.MACHINE_RECIPE + ":no_route");
        }
        String station = route.station();
        MachineMap.Row row = MachineMap.forBlock(station);
        if (row == null || row.capability() != MachineMap.Capability.EXECUTABLE) {
            // 读得出路线、但这一台**没有执行准入** ⇒ 如实拒绝，不假装能做
            return failAndFinish(Codes.MACHINE_RECIPE + ":not_executable:" + station);
        }
        if (route.materials().size() != 1) {
            return failAndFinish(Codes.MACHINE_RECIPE + ":multi_input_not_supported:"
                    + route.materials().size());
        }
        RecipeQuery.Material material = route.materials().get(0);
        if (material.candidates().size() != 1) {
            // 非物品输入（化学品/流体）在 RecipeQuery 里是 `candidates=[]` 的占位材料 ⇒ 如实拒绝
            return failAndFinish(Codes.MACHINE_RECIPE + ":non_item_input:" + material.describe());
        }
        Item input = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(material.candidates().get(0)));
        if (input == net.minecraft.world.item.Items.AIR) {
            return failAndFinish(Codes.MACHINE_RECIPE + ":unknown_input:"
                    + material.candidates().get(0));
        }
        int need = Math.max(1, material.perCraft()) * Math.max(1, route.crafts());
        int have = RecipeQuery.countInInventory(bot, input);
        if (have < need) {
            return failAndFinish(Codes.MISSING + ":" + material.describe() + "x" + (need - have));
        }
        machineInput = input;
        machineInputCount = need;
        machineOutput = target;
        machineOutputCount = Math.max(1, route.outputPerCraft()) * Math.max(1, route.crafts());
        productBefore = productCount();
        BotLog.info("[CraftJob] 机器路线 machine={} type={} recipe={} in={}x{} out={}x{}"
                        + "（走 → 开 → 电 → 放料 → 等 → 取）",
                station, row.typeId(), route.recipeId(),
                BuiltInRegistries.ITEM.getKey(input), machineInputCount,
                BuiltInRegistries.ITEM.getKey(machineOutput), machineOutputCount);
        machine = new MachineCycle(bot, machineSink(), NAME,
                new MachineCycle.Spec(station, row.typeId(), MACHINE_SCAN_RADIUS,
                        "craft-machine-walk", "craft-machine-walk",
                        MACHINE_WALK_TICKS, MACHINE_WAIT_TICKS,
                        machineInput, machineInputCount, machineOutput, machineOutputCount, productBefore,
                        NAME, "present（开机时机器自己已有电；来源不由本层判定）", false),
                null);
        return advance(Phase.MACHINE);
    }

    /**
     * 驱动闭环执行器（与电池步 `machine_cycle` **同一份实现**）。
     *
     * <p>失败码**原样透传**执行器的码（`machine_absent:*` / `machine_out_of_reach:*` / `machine_no_energy` /
     * `walk_failed:*` / `container_write_refused` / `no_product_in_Nticks:*` …）—— 夹具与生产说同一种话，
     * 便于拿同一张判据表对账。
     */
    private Status runMachine() {
        MachineCycle.State state = machine.tick();
        if (state == MachineCycle.State.RUNNING) {
            return Status.RUNNING;
        }
        logMachineSummary(state == MachineCycle.State.DONE);
        if (state == MachineCycle.State.FAILED) {
            return failAndFinish(machine.failure());
        }
        machine.closeMenu("machine_done");     // 先关菜单，再让 `verify` 只看背包这一处世界事实
        return advance(Phase.VERIFY);
    }

    /** 生产侧只留一段 SUMMARY（不把每条事实都当独立日志刷屏）。 */
    private void logMachineSummary(boolean ok) {
        StringBuilder sb = new StringBuilder();
        for (var entry : machineFacts.entrySet()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        if (!machineFailedChecks.isEmpty()) {
            sb.append(" failedChecks=").append(machineFailedChecks);
        }
        BotLog.info("[CraftJob] machine SUMMARY {} verdict={}", sb, ok ? "PASS" : "FAIL");
    }

    /** 执行器的事实出口：收进 job 自己的表；判据失败**不吞**，记进 `failedChecks`。 */
    private MachineCycle.Sink machineSink() {
        return new MachineCycle.Sink() {
            @Override
            public void record(String key, String value) {
                machineFacts.put(key, value);
            }

            @Override
            public void check(String name, boolean ok, String detail) {
                machineFacts.put(name, ok ? "true" : "false");
                if (!ok) {
                    machineFailedChecks.add(name + "(" + detail + ")");
                }
            }
        };
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

    /**
     * **容器写入授权**（R1 收口，2026-09-14）：烧炼这条路真的在改容器内容（输入格/燃料格/输出格），
     * 因此过 `WriteBudget` 的**容器维度** + 策略表的声明判定。
     *
     * <p>为什么以前没有：容器写入 2026-09-14 才纳入策略表；本 Job 的装配阶段借用了
     * `StationProvision.allowContainerWrite`（那次计数归 `station-provision`），而**放料/取产物这几下
     * 谁也没记账**——它是"容器写入覆盖面"缺口里唯一的**生产**路径（由 {@code tools/policy-map.py}
     * 的调用点覆盖断言顶出来）。requester 用 Job 名（{@code craft} ⇒ 表里 CRAFT 行，
     * 该行已声明 {@code CONTAINER_TRANSFER}）。
     */
    /** 容器写入的授权对象（与 {@link #allowContainerWrite} **同一份口径**）—— 供写入原语做编译期强制（T1/R-5）。 */
    private WriteGrant containerGrant() {
        return WriteGrant.of(NAME, WriteReason.CONTAINER_TRANSFER);
    }

    private boolean allowContainerWrite(String what) {
        WriteGrant grant = containerGrant();
        BlockPos pos = opened == null ? null : opened.pos();
        if (WriteBudget.consumeContainerWrite(bot, pos, grant) == WriteBudget.Verdict.REFUSED) {
            BotLog.warn("[CraftJob] 容器写入被拒 what={} grant={} ⇒ 停止写入并如实失败", what, grant.describe());
            return false;
        }
        return true;
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
            if (!allowContainerWrite("input")) {
                return failAndFinish(StationProvision.Codes.BUDGET_REFUSED + ":input");
            }
            if (!FurnaceStation.placeOne(bot, menu, found, found.input(), input, containerGrant())) {
                return failAndFinish(Codes.INPUT_PLACE);
            }
            inputPlaced = true;
        }
        if (!fuelPlaced) {
            Item fuel = firstHeldFuel();
            if (fuel == null) {
                return failAndFinish(Codes.MISSING + ":fuel_not_held");
            }
            if (!allowContainerWrite("fuel")) {
                return failAndFinish(StationProvision.Codes.BUDGET_REFUSED + ":fuel");
            }
            if (!FurnaceStation.placeOne(bot, menu, found, found.fuel(), fuel, containerGrant())) {
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
            if (!allowContainerWrite("take_output")) {
                return failAndFinish(StationProvision.Codes.BUDGET_REFUSED + ":take_output");
            }
            boolean taken = FurnaceStation.takeAll(bot, menu, found.output(), containerGrant());
            BotLog.info("[CraftJob] 出炉 {} x{} taken={}", output.getItem(), output.getCount(), taken);
            return advance(Phase.VERIFY);
        }
        int budget = Math.max(SMELT_TICKS_MIN, SMELT_TICKS_PER_ITEM * count);
        if (phaseTicks > budget) {
            boolean back = allowContainerWrite("timeout_return_input")
                    && FurnaceStation.takeAll(bot, menu, found.input(), containerGrant());
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
            if (station.ok() && allowContainerWrite("cleanup_return_input")) {
                FurnaceStation.takeAll(bot, menu, station.found().input(), containerGrant());
            }
        }
        if (machine != null) {
            machine.closeMenu("craft_failed");
            machine = null;
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
        if (machine != null) {
            machine.closeMenu("craft_job_done");
            machine = null;
        }
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
