package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.task.craft.InventoryCraft;
import com.dddgn.alice.task.craft.RecipeQuery;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **随身 2×2 合成自检**（阶段 3-A / A2，D-186）：一次右键跑完，输出 `SUMMARY key=VALUE`。
 *
 * <p>用例（每条都自带前提：先清空背包 → 只发需要的材料 → 断言**净变化**）：
 * <ul>
 *   <li>{@code craft_sticks}：2 木板 → 4 木棍（shaped 1×2）⇒ 产物 +4、材料 −2；</li>
 *   <li>{@code craft_table}：4 木板 → 1 工作台（shaped 2×2）⇒ 产物 +1、材料 −4；</li>
 *   <li>{@code craft_multi}：4 木板 → **8** 木棍（需要合 2 次）⇒ 产物 +8、材料 −4；</li>
 *   <li>{@code query_refuses_when_short}：清空背包 → 合木棍 ⇒ **查询层**如实拒绝
 *       （`query_MISSING_INGREDIENTS`）且**背包逐槽无变化**（不许凭空给、不许吞掉材料）；</li>
 *   <li>{@code action_cleanup_on_partial}：只给煤、不给木棍，**直接调动作原语**合火把 ⇒ 摆料摆到一半
 *       缺料 ⇒ 必须如实失败 `missing_ingredient`，且**已摆进网格的煤被收回背包、网格四格皆空**；</li>
 *   <li>{@code grid_clean}：上面全部跑完后，随身 2×2 网格**四格皆空**（不留半成品）；</li>
 *   <li>{@code menu_is_inventory}：前提断言 —— 当前打开的就是玩家自带菜单（否则本夹具测的不是随身网格）。</li>
 * </ul>
 *
 * <p>与 A1（只读查询）的分工：A1 只回答"能不能做"，本夹具才**真正消耗真产物**（真消耗、真产物）。
 */
public class CraftActionCheckTask implements Task {

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final Map<String, String> results = new LinkedHashMap<>();

    private int ticks;
    private boolean done;

    public CraftActionCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "CraftActionCheck";
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
        if (++ticks > 40) {
            failures.add("fixture_timeout");
            return finish();
        }
        runChecks();
        return finish();
    }

    // ==================== 用例 ====================

    private void runChecks() {
        // 前提断言：必须开着"玩家自带菜单"（否则测的不是随身 2×2）
        check("menu_is_inventory", bot.containerMenu instanceof InventoryMenu,
                "menu=" + bot.containerMenu.getClass().getSimpleName());

        // ① 2 木板 → 4 木棍
        reset();
        give(Items.OAK_PLANKS, 2);
        int planksBefore = RecipeQuery.countInInventory(bot, Items.OAK_PLANKS);
        int sticksBefore = RecipeQuery.countInInventory(bot, Items.STICK);
        var r1 = craftFirst("minecraft:stick", 4);
        int planksAfter = RecipeQuery.countInInventory(bot, Items.OAK_PLANKS);
        int sticksAfter = RecipeQuery.countInInventory(bot, Items.STICK);
        check("craft_sticks", r1.ok() && sticksAfter - sticksBefore == 4 && planksBefore - planksAfter == 2,
                "product+" + (sticksAfter - sticksBefore) + " material-" + (planksBefore - planksAfter)
                        + " " + r1.describe());

        // ② 4 木板 → 1 工作台（2×2 shaped）
        reset();
        give(Items.OAK_PLANKS, 4);
        int tablesBefore = RecipeQuery.countInInventory(bot, Items.CRAFTING_TABLE);
        var r2 = craftFirst("minecraft:crafting_table", 1);
        int tablesAfter = RecipeQuery.countInInventory(bot, Items.CRAFTING_TABLE);
        check("craft_table", r2.ok() && tablesAfter - tablesBefore == 1
                        && RecipeQuery.countInInventory(bot, Items.OAK_PLANKS) == 0,
                "product+" + (tablesAfter - tablesBefore) + " " + r2.describe());

        // ③ 4 木板 → 8 木棍（两轮）
        reset();
        give(Items.OAK_PLANKS, 4);
        int s3Before = RecipeQuery.countInInventory(bot, Items.STICK);
        var r3 = craftFirst("minecraft:stick", 8);
        int s3After = RecipeQuery.countInInventory(bot, Items.STICK);
        check("craft_multi", r3.ok() && s3After - s3Before == 8
                        && RecipeQuery.countInInventory(bot, Items.OAK_PLANKS) == 0,
                "product+" + (s3After - s3Before) + " " + r3.describe());

        // ④ 缺料 ⇒ **查询层**如实拒绝 + 背包逐槽无变化
        //    （注意：缺料在 A1 查询就挡住了，动作层根本不会被调用 ⇒ 这里断言的是查询层拒绝码；
        //     动作层自己的清理路径由 ⑤ 直接调用原语来验。）
        reset();
        List<ItemStack> before = snapshot();
        var r4 = craftFirst("minecraft:stick", 4);
        boolean unchanged = sameInventory(before, snapshot());
        boolean refusedHonestly = !r4.ok()
                && ("query_" + RecipeQuery.Verdict.MISSING_INGREDIENTS).equals(r4.code());
        check("query_refuses_when_short", refusedHonestly && unchanged,
                "code=" + r4.code() + " inventoryUnchanged=" + unchanged);

        // ⑤ **动作层的失败清理**（真验）：只给煤、不给木棍 ⇒ 摆料摆到一半必然缺料 ⇒
        //    已摆进网格的煤**必须被收回背包**（网格不留半成品、材料不丢）。
        reset();
        give(Items.COAL, 1);
        var torchRecipe = bot.getServer().getRecipeManager()
                .byKey(net.minecraft.resources.ResourceLocation.tryParse("minecraft:torch")).orElse(null);
        boolean cleanupOk = false;
        String cleanupDetail = "no_recipe";
        if (torchRecipe != null) {
            var r5 = InventoryCraft.craft(bot, bot.containerMenu, torchRecipe, 1);
            boolean gridEmptyAfter = true;
            for (int cell = 0; cell < 4; cell++) {
                gridEmptyAfter &= bot.containerMenu.getSlot(1 + cell).getItem().isEmpty();
            }
            int coalLeft = RecipeQuery.countInInventory(bot, Items.COAL);
            cleanupOk = !r5.ok() && InventoryCraft.Codes.MISSING_INGREDIENT.equals(r5.code())
                    && gridEmptyAfter && coalLeft == 1;
            cleanupDetail = "code=" + r5.code() + " gridEmpty=" + gridEmptyAfter
                    + " coalLeft=" + coalLeft;
        }
        check("action_cleanup_on_partial", cleanupOk, cleanupDetail);

        // ⑤ 网格清空（不留半成品）
        boolean gridEmpty = true;
        for (int cell = 0; cell < 4; cell++) {
            gridEmpty &= bot.containerMenu.getSlot(1 + cell).getItem().isEmpty();
        }
        check("grid_clean", gridEmpty, "slots1..4Empty=" + gridEmpty);
    }

    /** 用 A1 的查询选出一条 2×2 路线，再用 A2 的原语执行。 */
    private InventoryCraft.Result craftFirst(String itemId, int count) {
        RecipeQuery.Result query = RecipeQuery.query(bot.getServer(), bot, itemId, count);
        if (query.verdict() != RecipeQuery.Verdict.CRAFTABLE || query.route() == null) {
            return new InventoryCraft.Result(false, "query_" + query.verdict(), 0, 0, List.of());
        }
        var recipe = bot.getServer().getRecipeManager()
                .byKey(net.minecraft.resources.ResourceLocation.tryParse(query.route().recipeId()))
                .orElse(null);
        if (recipe == null) {
            return new InventoryCraft.Result(false, "recipe_lookup_failed", 0, 0, List.of());
        }
        return InventoryCraft.craft(bot, bot.containerMenu, recipe, count);
    }

    // ==================== 工具 ====================

    private void reset() {
        FixtureToolKit.resetInventory(bot);
    }

    private void give(net.minecraft.world.item.Item item, int count) {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                inventory.setItem(slot, new ItemStack(item, count));
                return;
            }
        }
        BotLog.warn("[CraftActionCheck] 背包没有空槽，无法发料 {} x{}", item, count);
    }

    private List<ItemStack> snapshot() {
        var inventory = bot.getInventory();
        List<ItemStack> out = new ArrayList<>(inventory.getContainerSize());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            out.add(inventory.getItem(slot).copy());
        }
        return out;
    }

    private static boolean sameInventory(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).getCount() != b.get(i).getCount()
                    || !ItemStack.isSameItemSameTags(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private void check(String name, boolean ok, String detail) {
        results.put(name, ok ? "PASS" : "FAIL");
        BotLog.info("[CraftActionCheck] {}={} {}", name, ok ? "PASS" : "FAIL", detail);
        if (!ok) {
            failures.add(name);
        }
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
        BotLog.info("[CraftActionCheck] SUMMARY {}", summary);
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[CraftActionCheck] " + summary));
        }
        return failures.isEmpty() ? Status.DONE : Status.FAILED;
    }
}
