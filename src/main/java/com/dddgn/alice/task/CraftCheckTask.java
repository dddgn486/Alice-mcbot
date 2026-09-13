package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.task.craft.RecipeQuery;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **只读配方查询自检**（阶段 3-A / A1，D-185）：一次右键跑完，输出 `SUMMARY key=VALUE`。
 *
 * <p>用例（每个都自带前提，且**全部只读** —— 查询前后背包快照必须一致）：
 * <ul>
 *   <li>{@code craftable_sticks}：给 2 个橡木木板 → 查 4 木棍 ⇒ 期望 `CRAFTABLE` + `grid=2x2`；</li>
 *   <li>{@code missing_ingredients}：清空背包 → 查 4 木棍 ⇒ 期望 `MISSING_INGREDIENTS` 且 `missing=[…]`；</li>
 *   <li>{@code needs_table}：给 8 圆石 → 查 1 熔炉（3×3 配方）⇒ 期望 `NEEDS_TABLE` + `grid=3x3`；</li>
 *   <li>{@code machine_only_vanilla}：查 `minecraft:cobblestone`（原版只能挖，但装了 Mekanism/Create 后
 *       它们给了 `crushing`/`milling`/`enriching`）⇒ **S1 起**期望 `MACHINE_ROUTE`（有出处的机器路线）
 *       —— **"只由机器产出"与物品是不是模组物品无关**（2026-09-13 实测教训）；</li>
 *   <li>{@code no_recipe}：查 {@link #NO_RECIPE_ITEM}（**任何**配方类型都产不出；基岩已不适用——本整合包里它有机器配方）⇒ 期望 `NO_RECIPE`；</li>
 *   <li>{@code machine_only}：查一个**只由机器配方产出**的物品（默认 `mekanism:dust_iron`；
 *       未装 Mekanism 时该用例如实记 `SKIP`）⇒ **S1 起**期望 `MACHINE_ROUTE` 且路线带机器类型；</li>
 *   <li>{@code read_only}：上面全部查询跑完，**背包逐槽快照必须与开始时完全一致**（本原语是只读的硬断言）。</li>
 * </ul>
 */
public class CraftCheckTask implements Task {

    /** 机器专属物品（用于"不猜机器语义"的负例；未装该模组时跳过）。 */
    /** **任何**配方类型都产不出它（创造模式专属）——负例用；换物品时必须在此处改，并说明为什么。 */
    private static final String NO_RECIPE_ITEM = "minecraft:command_block";
    private static final String MACHINE_ONLY_ITEM = "mekanism:dust_iron";

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final Map<String, String> results = new LinkedHashMap<>();
    private final Map<String, String> details = new LinkedHashMap<>();

    private int ticks;
    private boolean done;

    public CraftCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "CraftCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(bot.blockPosition());
    }

    @Override
    public String failureReason() {
        return failures.isEmpty() ? "" : String.join(",", failures);
    }

    @Override
    public Status tick() {
        if (done) {
            return failures.isEmpty() ? Status.DONE : Status.FAILED;
        }
        ticks++;
        if (ticks > 40) {
            failures.add("fixture_timeout");
            return finish();
        }
        runChecks();
        return finish();
    }

    // ==================== 用例 ====================

    private void runChecks() {
        var server = bot.getServer();
        // 注意：只读断言的快照必须在**清空背包之后**采集（否则会把自己前面 resetInventory 的
        // 动作算成"查询改了背包"）—— 夹具自己的前提也要摆对。

        // ① 正例：材料齐备 + 随身 2×2 可做
        FixtureToolKit.resetInventory(bot);
        give(Items.OAK_PLANKS, 2);
        RecipeQuery.Result sticks = RecipeQuery.query(server, bot, "minecraft:stick", 4);
        expect("craftable_sticks", sticks, RecipeQuery.Verdict.CRAFTABLE,
                r -> r.route() != null && r.route().inventoryGrid(), 1);

        // ② 负例：缺料（如实报缺什么、缺多少）
        FixtureToolKit.resetInventory(bot);
        RecipeQuery.Result noPlanks = RecipeQuery.query(server, bot, "minecraft:stick", 4);
        expect("missing_ingredients", noPlanks, RecipeQuery.Verdict.MISSING_INGREDIENTS,
                r -> !r.missing().isEmpty(), 2);

        // ③ 边界：材料齐备但需要 3×3（熔炉 = 8 圆石）
        FixtureToolKit.resetInventory(bot);
        give(Items.COBBLESTONE, 8);
        RecipeQuery.Result furnace = RecipeQuery.query(server, bot, "minecraft:furnace", 1);
        expect("needs_table", furnace, RecipeQuery.Verdict.NEEDS_TABLE,
                r -> r.route() != null && !r.route().inventoryGrid(), 3);

        // ④ **"只由机器配方产出"并不限于模组物品**：圆石在原版只能挖，但装了 Mekanism/Create 之后
        //    它们给了 `mekanism:crushing` / `create:milling` / `mekanism:enriching`。
        //    **2026-09-14 语义升级（S1 / D-204）**：这类配方现在**读得出路线**（问上游自述：
        //    `getOutputDefinition()` / `getInput().getRepresentations()`）⇒ 正确结论从
        //    `MACHINE_RECIPE_UNSUPPORTED`（拒绝）升级为 **`MACHINE_ROUTE`（有出处的机器路线）**。
        //    ⚠️ 注意口径：**升级的是"读得出"，不是"能做"** —— 执行侧仍如实拒绝（`CraftJob` 报 not_executable）。
        FixtureToolKit.resetInventory(bot);
        RecipeQuery.Result cobble = RecipeQuery.query(server, bot, "minecraft:cobblestone", 1);
        expect("machine_only_vanilla", cobble, RecipeQuery.Verdict.MACHINE_ROUTE,
                r -> r.route() != null && !r.route().station().isBlank(), 4);

        // ⑤ 负例：**任何**配方类型都产不出它 ⇒ NO_RECIPE。
        //    **2026-09-14 换物品（实测教训）**：原用例用 `minecraft:bedrock`，而本整合包**真有机器配方能产出基岩**
        //    ⇒ 在 S1 之后它正确地变成 `MACHINE_ROUTE`，旧期望假失败。这正是 §6.9.1 ② 那条纪律：
        //    **负例必须按"当前模组集"核对**（"原版视角"写死会过期）。改用创造模式专属物品。
        FixtureToolKit.resetInventory(bot);
        RecipeQuery.Result negative = RecipeQuery.query(server, bot, NO_RECIPE_ITEM, 1);
        expect("no_recipe", negative, RecipeQuery.Verdict.NO_RECIPE,
                r -> r.machineTypes().isEmpty(), 5);

        // ⑥ 负例：只由机器配方产出（**模组物品**）⇒ 如实拒绝、不猜语义（没装该模组就 SKIP）
        FixtureToolKit.resetInventory(bot);
        if (net.minecraft.core.registries.BuiltInRegistries.ITEM
                .containsKey(net.minecraft.resources.ResourceLocation.tryParse(MACHINE_ONLY_ITEM))) {
            RecipeQuery.Result machine = RecipeQuery.query(server, bot, MACHINE_ONLY_ITEM, 1);
            expect("machine_only", machine, RecipeQuery.Verdict.MACHINE_ROUTE,
                    r -> r.route() != null && !r.route().station().isBlank(), 6);
        } else {
            record("machine_only", "SKIP", "未装 " + MACHINE_ONLY_ITEM + " 所属模组");
        }

        // ⑦ 硬断言：整个夹具期间**背包逐槽未变**（只读原语的证据）
        FixtureToolKit.resetInventory(bot);
        List<ItemStack> readOnlyBefore = snapshotInventory();
        runReadOnlyProbe();
        List<ItemStack> readOnlyAfter = snapshotInventory();
        boolean same = sameInventory(readOnlyBefore, readOnlyAfter);
        check("read_only", same, "beforeSlots=" + nonEmptySlots(readOnlyBefore)
                + " afterSlots=" + nonEmptySlots(readOnlyAfter));
    }

    /** 只读探针：把几个查询跑一遍（含正例），期间不改背包。 */
    private void runReadOnlyProbe() {
        RecipeQuery.query(bot.getServer(), bot, "minecraft:stick", 4);
        RecipeQuery.query(bot.getServer(), bot, "minecraft:crafting_table", 1);
        RecipeQuery.query(bot.getServer(), bot, "minecraft:diamond_pickaxe", 1);
    }

    private void expect(String name, RecipeQuery.Result result, RecipeQuery.Verdict wanted,
                        java.util.function.Predicate<RecipeQuery.Result> extra, int order) {
        boolean ok = result.verdict() == wanted && extra.test(result);
        check(name, ok, result.describe());
    }

    private void give(net.minecraft.world.item.Item item, int count) {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                inventory.setItem(slot, new ItemStack(item, count));
                return;
            }
        }
        BotLog.warn("[CraftCheck] 背包没有空槽，无法发料 {} x{}", item, count);
    }

    private List<ItemStack> snapshotInventory() {
        var inventory = bot.getInventory();
        List<ItemStack> snapshot = new ArrayList<>(inventory.getContainerSize());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            snapshot.add(inventory.getItem(slot).copy());
        }
        return snapshot;
    }

    private static boolean sameInventory(List<ItemStack> a, List<ItemStack> b) {
        if (a == null || b == null || a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            ItemStack x = a.get(i);
            ItemStack y = b.get(i);
            if (x.getCount() != y.getCount() || !ItemStack.isSameItemSameTags(x, y)) {
                return false;
            }
        }
        return true;
    }

    private static long nonEmptySlots(List<ItemStack> stacks) {
        return stacks == null ? -1 : stacks.stream().filter(s -> !s.isEmpty()).count();
    }

    // ==================== 记账 ====================

    private void check(String name, boolean ok, String detail) {
        record(name, ok ? "PASS" : "FAIL", detail);
        if (!ok) {
            failures.add(name);
        }
    }

    private void record(String name, String value, String detail) {
        results.put(name, value);
        details.put(name, detail);
        BotLog.info("[CraftCheck] {}={} {}", name, value, detail);
    }

    private Status finish() {
        done = true;
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<String, String> entry : results.entrySet()) {
            if (!summary.isEmpty()) {
                summary.append(' ');
            }
            summary.append(entry.getKey()).append('=').append(entry.getValue());
        }
        summary.append(" verdict=").append(failures.isEmpty() ? "PASS" : "FAIL");
        BotLog.info("[CraftCheck] SUMMARY {}", summary);
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[CraftCheck] " + summary));
        }
        return failures.isEmpty() ? Status.DONE : Status.FAILED;
    }
}
