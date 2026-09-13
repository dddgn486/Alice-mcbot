package com.dddgn.alice.decision;

import com.dddgn.alice.log.BotLog;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **运行时配方导出**（S5 第二步 / D-146）：把服务端**运行时**的配方表导成 `tools/recipe-graph.py`
 * 能吃的归一化 JSON —— 这样离线规划器就能对**真实整合包**（含 KubeJS/CraftTweaker 魔改）跑 P1。
 *
 * <p>为什么必须用运行时数据：魔改最终都体现在运行时配方表里，**运行时 > wiki**（D-145/参考材料 §1）。
 *
 * <p>**能力边界（诚实标注）**：
 * <ul>
 *   <li>只导**原版配方体系**（`RecipeManager` 里的 `CraftingRecipe`/`SmeltingRecipe`/…）；
 *       **格雷/精英工作台这类"机器配方"不在其中**（它们走 JEI 类别）—— 那部分留到 S6 的机器适配器，
 *       按项目规矩**先只读、不猜语义**；</li>
 *   <li>无法归一化的类型**如实跳过并计数**（不猜）；</li>
 *   <li>标签：物品标签导出**成员上限**（默认 256），超出只记数量；配方的 Ingredient 若与某个标签成员集合
 *       **完全相等**则记为 `#tag`，否则记为"任取其一"的物品列表（诚实表达"我们只知道这个集合"）。</li>
 * </ul>
 */
public final class RecipeDump {

    /** 单个物品标签导出的成员上限（避免超大标签把文件撑爆）。 */
    public static final int TAG_MEMBER_LIMIT = 256;
    /** 配方输入"任取其一"列表的长度上限。 */
    public static final int INGREDIENT_LIST_LIMIT = 16;

    private static final Map<String, String> STATION_BY_TYPE = Map.ofEntries(
            Map.entry("minecraft:crafting_shaped", "crafting_table"),
            Map.entry("minecraft:crafting_shapeless", "crafting_table"),
            Map.entry("minecraft:smelting", "furnace"),
            Map.entry("minecraft:blasting", "blast_furnace"),
            Map.entry("minecraft:smoking", "smoker"),
            Map.entry("minecraft:campfire_cooking", "campfire"),
            Map.entry("minecraft:stonecutting", "stonecutter"),
            Map.entry("minecraft:smithing_transform", "smithing_table"),
            Map.entry("minecraft:smithing_trim", "smithing_table"));

    private RecipeDump() {
    }

    /** 导出结果摘要。 */
    public record Result(int recipes, int skipped, int tags, String path,
                         java.util.Map<String, Integer> skippedByType) {
        public String describe() {
            return "recipes=" + recipes + " skipped=" + skipped + " tags=" + tags
                    + " skippedTop=" + topSkipped() + " → " + path;
        }

        /** 被跳过配方**按类型**直方图的前 3 项（阶段 2 判据："读得懂多少 / 适配器候选是谁"）。 */
        String topSkipped() {
            if (skippedByType == null || skippedByType.isEmpty()) {
                return "-";
            }
            StringBuilder sb = new StringBuilder();
            skippedByType.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .limit(3)
                    .forEach(e -> sb.append(sb.isEmpty() ? "" : ",").append(e.getKey())
                            .append('=').append(e.getValue()));
            return sb.toString();
        }
    }

    /** 导出到 `config/<fileName>`（默认 `alice-recipes.json`）。 */
    public static Result dump(MinecraftServer server, String fileName) {
        Path path = FMLPaths.CONFIGDIR.get().resolve(
                fileName == null || fileName.isBlank() ? "alice-recipes.json" : fileName);

        // ① 物品标签 → 成员（供离线工具做"标签 → 成员"展开）
        JsonObject itemTags = new JsonObject();
        Map<String, String> tagByMemberSet = new HashMap<>();
        int tagCount = 0;
        for (var tag : BuiltInRegistries.ITEM.getTags().toList()) {
            List<String> collected = new ArrayList<>();
            for (var holder : tag.getSecond()) {
                collected.add(BuiltInRegistries.ITEM.getKey(holder.value()).toString());
                if (collected.size() > TAG_MEMBER_LIMIT) {
                    break;   // 超大标签只导前 N 个并标记 truncated（如实）
                }
            }
            boolean truncated = collected.size() > TAG_MEMBER_LIMIT;
            List<String> members = truncated
                    ? new ArrayList<>(collected.subList(0, TAG_MEMBER_LIMIT)) : collected;
            JsonArray array = new JsonArray();
            members.forEach(array::add);
            JsonObject node = new JsonObject();
            node.add("members", array);
            if (truncated) {
                node.addProperty("truncated", true);
            }
            itemTags.add("#" + tag.getFirst().location(), node);
            tagCount++;
            List<String> sorted = new ArrayList<>(members);
            java.util.Collections.sort(sorted);
            tagByMemberSet.putIfAbsent(String.join(",", sorted), "#" + tag.getFirst().location());
        }

        // ② 配方表
        JsonArray recipes = new JsonArray();
        int skipped = 0;
        // 阶段 2 判据（D-148/D-181）：**跳过必须可归类** —— 只报一个总数无法回答
        // "Alice 读不懂的是哪些模组的哪些配方类型"（= 适配器候选清单的证据）。
        java.util.Map<String, Integer> skippedByType = new java.util.TreeMap<>();
        var access = server.registryAccess();
        for (Recipe<?> recipe : server.getRecipeManager().getRecipes()) {
            String type = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString();
            if (!STATION_BY_TYPE.containsKey(type)) {
                skipped++;   // 未知/模组机器配方：如实跳过（不猜语义）
                skippedByType.merge(type, 1, Integer::sum);
                continue;
            }
            ItemStack result = recipe.getResultItem(access);
            if (result == null || result.isEmpty()) {
                skipped++;
                skippedByType.merge(type + "(空产出)", 1, Integer::sum);
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("id", recipe.getId().toString());
            entry.addProperty("type", type);
            entry.addProperty("station", STATION_BY_TYPE.get(type));
            entry.addProperty("output", BuiltInRegistries.ITEM.getKey(result.getItem()).toString());
            entry.addProperty("count", Math.max(1, result.getCount()));
            JsonArray inputs = new JsonArray();
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient.isEmpty()) {
                    continue;
                }
                JsonObject input = new JsonObject();
                List<String> items = new ArrayList<>();
                for (ItemStack stack : ingredient.getItems()) {
                    items.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                    if (items.size() >= INGREDIENT_LIST_LIMIT) {
                        break;
                    }
                }
                List<String> sorted = new ArrayList<>(items);
                java.util.Collections.sort(sorted);
                String tag = tagByMemberSet.get(String.join(",", sorted));
                if (tag != null) {
                    input.addProperty("key", tag);
                } else {
                    input.addProperty("key", items.isEmpty() ? "unknown" : items.get(0));
                    if (items.size() > 1) {
                        JsonArray any = new JsonArray();
                        items.forEach(any::add);
                        input.add("any", any);
                    }
                }
                input.addProperty("count", 1);
                inputs.add(input);
            }
            entry.add("inputs", inputs);
            recipes.add(entry);
        }

        JsonObject root = new JsonObject();
        root.addProperty("source", "alice runtime RecipeManager");
        root.addProperty("generatedAtTick", server.getTickCount());
        root.addProperty("note", "只含原版配方体系；机器配方（JEI 类别）不在此，见 D-146 的能力边界");
        root.add("itemTags", itemTags);
        root.add("recipes", recipes);
        // 跳过的类型直方图（只读事实；离线分析据此列"适配器候选"，本仓**不写适配器**）
        JsonObject skipObj = new JsonObject();
        skippedByType.forEach(skipObj::addProperty);
        root.add("skippedTypes", skipObj);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, new GsonBuilder().create().toJson(root), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            BotLog.warn("[Recipes] 导出失败：{}", ex.toString());
            return new Result(recipes.size(), skipped, tagCount, "失败: " + ex.getMessage(), skippedByType);
        }
        Result result = new Result(recipes.size(), skipped, tagCount, path.toString(), skippedByType);
        BotLog.warn("[Recipes] dump {}", result.describe());
        return result;
    }

    private static String key(ResourceLocation id) {
        return id == null ? "unknown" : id.toString();
    }
}
