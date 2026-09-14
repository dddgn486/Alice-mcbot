package com.dddgn.alice.task.craft;

import com.dddgn.alice.decision.MachineMap;
import com.dddgn.alice.decision.RecipeDump;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * **只读配方查询原语**（阶段 3-A / A1，D-185）。
 *
 * <p>回答："按 `itemId × count`，现在**能不能**在**原版**配方体系里做出来？"
 * 只查询**运行时 `RecipeManager`**（与 `/alice recipes` 导出、阶段 2 审计**同一份数据**），
 * **不摆料、不点菜单、不动世界、不改背包** —— 纯只读事实。
 *
 * <p>为什么先做它：合成是多步动作链（摆料 → 取产物），先写动作就会边写边猜；而
 * "够不够料 / 要不要 3×3 / 有几条路线"本身就是**决策层需要的事实**（事实由确定性代码产出，LLM 只选）。
 *
 * <p>边界：① 只认原版配方体系里可读的类型，工作站映射与 {@link RecipeDump#stationFor} **共用一处定义**；
 * ② 命中机器/未知类型**不猜语义** ⇒ {@link Verdict#MACHINE_RECIPE_UNSUPPORTED} 并列出类型名；
 * ③ **只读**：本类没有任何写世界/改背包的路径（夹具另有"查询前后背包不变"的断言）。
 */
public final class RecipeQuery {

    /** 结论。 */
    public enum Verdict {
        /** 材料齐备，且所需网格能用**随身 2×2**完成。 */
        CRAFTABLE,
        /** 材料齐备，但需要 3×3 工作台（随身 2×2 放不下）。 */
        NEEDS_TABLE,
        /** 有主配方，但材料不齐（{@link Result#missing()} 列出缺什么、缺多少）。 */
        MISSING_INGREDIENTS,
        /** 原版配方体系里没有产出该物品的配方。 */
        NO_RECIPE,
        /** 只有机器/未支持类型配方能产出 ⇒ 如实拒绝（不猜语义）。 */
        MACHINE_RECIPE_UNSUPPORTED,
        /**
         * **机器产线**（S1 / D-204）：配方来自由**上游自述**读得出物品输入/输出的机器类型
         * （`getInput().getRepresentations()` / `getOutputDefinition()`）⇒ 给出**有出处的路线**
         * （哪台机器、输入什么、产出什么）。**能不能执行取决于那台机器的执行准入**
         * （`MachineMap.Capability.EXECUTABLE`；D-217 起 `CraftJob` 只驱动有准入的行）——
         * 本查询层**只排不删**：多条机器路线时按准入优先排列，并在 `note` 里写清挑了哪条、为什么（D-218）。
         */
        MACHINE_ROUTE
    }

    /**
     * 一条材料需求。
     *
     * @param describe  人类可读（单一物品给 id；多候选给"N选1(首个…)"）
     * @param candidates 候选物品 id（"任选其一"语义）
     * @param perCraft   每次合成需要几个
     * @param shortage   还缺几个（只在 {@link Result#missing()} 里有意义；够用时为 0）
     */
    public record Material(String describe, List<String> candidates, int perCraft, int shortage) {
    }

    /** 一条候选路线（{@code crafts} = 要合几次才够 {@code count}）。 */
    public record Route(String recipeId, String type, String station, boolean inventoryGrid,
                        int crafts, int outputPerCraft, List<Material> materials) {
    }

    /** 查询结果（不可变）。 */
    public record Result(Verdict verdict, String target, int count, Route route,
                         List<Material> missing, Set<String> machineTypes, String note) {

        /** 一行机器可判读的摘要。 */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(verdict).append(" target=").append(target).append(" x").append(count);
            if (route != null) {
                sb.append(" station=").append(route.station())
                        .append(" grid=").append(route.inventoryGrid() ? "2x2" : "3x3")
                        .append(" crafts=").append(route.crafts())
                        .append(" perCraft=").append(route.outputPerCraft());
                sb.append(" mats=[");
                for (int i = 0; i < route.materials().size(); i++) {
                    Material m = route.materials().get(i);
                    sb.append(m.describe()).append(" x").append(m.perCraft());
                    if (i < route.materials().size() - 1) {
                        sb.append(", ");
                    }
                }
                sb.append(']');
            }
            if (!missing.isEmpty()) {
                sb.append(" missing=[");
                for (int i = 0; i < missing.size(); i++) {
                    sb.append(missing.get(i).describe()).append(" 缺").append(missing.get(i).shortage());
                    if (i < missing.size() - 1) {
                        sb.append(", ");
                    }
                }
                sb.append(']');
            }
            if (!machineTypes.isEmpty()) {
                sb.append(" machineTypes=").append(machineTypes);
            }
            if (note != null && !note.isBlank()) {
                sb.append(" note=").append(note);
            }
            return sb.toString();
        }
    }

    private RecipeQuery() {
    }

    /** 按物品 id 查询（未注册的 id ⇒ {@link Verdict#NO_RECIPE} + note=unknown_item）。 */
    public static Result query(MinecraftServer server, ServerPlayer bot, String itemId, int count) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        Item item = id == null ? null : BuiltInRegistries.ITEM.get(id);
        if (item == null || item == Items.AIR) {
            return new Result(Verdict.NO_RECIPE, itemId, Math.max(1, count), null,
                    List.of(), Set.of(), "unknown_item");
        }
        return query(server, bot, item, Math.max(1, count));
    }

    /** 核心查询：**只读**（不碰背包、不碰世界）。 */
    public static Result query(MinecraftServer server, ServerPlayer bot, Item target, int count) {
        String targetId = BuiltInRegistries.ITEM.getKey(target).toString();
        RegistryAccess access = server.registryAccess();
        List<Route> craftable = new ArrayList<>();
        List<Material> firstMissing = new ArrayList<>();
        Set<String> machineTypes = new LinkedHashSet<>();

        List<Route> machineRoutes = new ArrayList<>();
        for (Recipe<?> recipe : server.getRecipeManager().getRecipes()) {
            String type = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString();
            String station = RecipeDump.stationFor(type);
            ItemStack result = recipe.getResultItem(access);
            boolean vanillaHits = result != null && !result.isEmpty() && result.getItem() == target;
            if (station == null) {
                // **机器/未知类型**（S1/D-204）：原版接口常读不出（`getResultItem()=AIR`、`getIngredients()=[]`）
                // ⇒ **问上游自述**；能读出物品输入/输出且产出命中目标 ⇒ 记一条**有出处的机器路线**（只报不接）
                MachineRecipeFacts.Facts facts = MachineRecipeFacts.read(recipe);
                ItemStack machineOut = facts.outputs().stream()
                        .filter(stack -> stack.getItem() == target).findFirst().orElse(ItemStack.EMPTY);
                if (!machineOut.isEmpty()) {
                    int per = Math.max(1, machineOut.getCount());
                    int crafts = (int) Math.ceil(count / (double) per);
                    List<Material> materials = machineMaterials(facts.inputs());
                    if (materials.isEmpty() && facts.nonItemInput()) {
                        // **"读不出"与"没有"分开报**（D-204 附注七）：输入是化学品/流体等非物品形态
                        materials = List.of(new Material("非物品输入（化学品/流体等，未由物品语义表达）",
                                List.of(), 0, 0));
                    }
                    // **S3（D-209）**：机器路线的 `station` 从"配方类型 id"换成**机器方块 id**
                    // —— 路线要回答的是"**去哪台**"，类型 id 不是一个能去的地方（`mekanism:enriching`
                    // 不是一个方块）。表里没登记/无单方块站点 ⇒ **如实回落成类型 id**（旧文案），不猜方块。
                    // 类型 id 本身仍留在 `Route.type` 里，不丢信息。
                    String site = MachineMap.blockFor(type);
                    machineRoutes.add(new Route(recipe.getId().toString(), type,
                            site == null ? type : site, false, crafts, per, materials));
                } else if (vanillaHits) {
                    // **只在"这条配方确实产出目标物品"时才记类型**（2026-09-14 修 bug）：
                    // 原先我把它放在目标过滤之前 ⇒ 任何一次查询都会把**全表**的机器类型收进来，
                    // 于是"负例物品"（如 barrier）被判成 MACHINE_RECIPE_UNSUPPORTED 而不是 NO_RECIPE
                    // —— 是**夹具的自证负例**把这个回归抓出来的。
                    machineTypes.add(type);   // 读不出物品输出（化学品/气体）⇒ 如实记录，不猜语义
                }
                continue;
            }
            if (!vanillaHits) {
                continue;
            }
            int perCraft = Math.max(1, result.getCount());
            int crafts = (int) Math.ceil(count / (double) perCraft);
            List<Material> materials = materialsOf(recipe);
            Route route = new Route(recipe.getId().toString(), type, station,
                    fitsInventoryGrid(recipe), crafts, perCraft, materials);
            List<Material> missing = shortageOf(bot, materials, crafts);
            if (missing.isEmpty()) {
                craftable.add(route);
            } else if (firstMissing.isEmpty()) {
                firstMissing = missing;
            }
        }

        if (craftable.isEmpty() && firstMissing.isEmpty() && !machineRoutes.isEmpty()) {
            // **(c) 增量 2 收尾（D-218 / 台账⑬）**：同一个产出可能有多台机器能做，而配方管理器给的是
            // **迭代序** ⇒ 生产侧"能不能做"就会碰运气：第十二轮实测 `minecraft:clay_ball` 先命中
            // `mekanism:chemical_injection_chamber`（**没有执行准入**）⇒ 生产如实拒绝，其实富集仓也能做它。
            // 这里**只排不删**：有执行准入的排前面，其余仍留在表里（"读得出就报得出"的语义不变），
            // 并以 `recipeId` 收尾 ⇒ **同一次查询的结论是确定的**（不随迭代序漂）。
            List<Route> ordered = new ArrayList<>(machineRoutes);
            ordered.sort(Comparator
                    .comparingInt((Route route) -> hasExecutor(route.station()) ? 0 : 1)
                    .thenComparing(Route::recipeId));
            Route chosen = ordered.get(0);
            long executable = ordered.stream().filter(route -> hasExecutor(route.station())).count();
            return new Result(Verdict.MACHINE_ROUTE, targetId, count, chosen,
                    List.of(), machineTypes, "机器产线（上游自述可读输入/输出；" + chosen.station()
                            + (hasExecutor(chosen.station()) ? " 已有执行准入" : " 暂无该机器的执行适配")
                            + "；同类机器路线 " + ordered.size() + " 条，其中 " + executable + " 条有执行准入"
                            + "（按准入优先选取）"
                            + (chosen.materials().stream()
                                    .anyMatch(material -> material.candidates().isEmpty())
                                    ? "；输入含非物品形态" : "") + "）");
        }
        if (craftable.isEmpty() && firstMissing.isEmpty()) {
            if (!machineTypes.isEmpty()) {
                return new Result(Verdict.MACHINE_RECIPE_UNSUPPORTED, targetId, count, null,
                        List.of(), machineTypes, "只由机器/未支持类型产出（不猜语义）");
            }
            return new Result(Verdict.NO_RECIPE, targetId, count, null, List.of(), Set.of(), "");
        }
        if (craftable.isEmpty()) {
            return new Result(Verdict.MISSING_INGREDIENTS, targetId, count, null,
                    firstMissing, machineTypes, "");
        }
        // 优先给"随身 2×2 就能做"的路线；否则如实报 NEEDS_TABLE
        Route best = craftable.get(0);
        for (Route route : craftable) {
            if (route.inventoryGrid()) {
                best = route;
                break;
            }
        }
        Verdict verdict = best.inventoryGrid() ? Verdict.CRAFTABLE : Verdict.NEEDS_TABLE;
        return new Result(verdict, targetId, count, best, List.of(), machineTypes, "");
    }

    /** 机器路线的材料清单（来自上游自述的输入物品；同一物品合并计数）。 */
    private static List<Material> machineMaterials(List<ItemStack> inputs) {
        java.util.Map<String, Integer> aggregated = new java.util.LinkedHashMap<>();
        for (ItemStack stack : inputs) {
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            aggregated.merge(id, Math.max(1, stack.getCount()), Integer::sum);
        }
        List<Material> out = new ArrayList<>();
        for (var entry : aggregated.entrySet()) {
            out.add(new Material(entry.getKey(), List.of(entry.getKey()), entry.getValue(), 0));
        }
        return out;
    }

    /** 该配方能否放进**随身 2×2**（玩家自带网格）。 */
    private static boolean fitsInventoryGrid(Recipe<?> recipe) {
        if (recipe instanceof ShapedRecipe shaped) {
            return shaped.getWidth() <= 2 && shaped.getHeight() <= 2;
        }
        if (recipe instanceof ShapelessRecipe shapeless) {
            return shapeless.getIngredients().size() <= 4;
        }
        return false;   // 熔炉/切石/锻造等不是 2×2 语义
    }

    /** 展开**每次合成**的材料需求（同一 ingredient 在配方里出现多次 ⇒ 合并为 perCraft 计数）。 */
    private static List<Material> materialsOf(Recipe<?> recipe) {
        List<Material> out = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            List<String> candidates = new ArrayList<>();
            for (ItemStack stack : ingredient.getItems()) {
                candidates.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            }
            if (candidates.isEmpty()) {
                continue;
            }
            int index = indexOfSameCandidates(out, candidates);
            if (index >= 0) {
                Material old = out.get(index);
                out.set(index, new Material(old.describe(), old.candidates(),
                        old.perCraft() + 1, 0));
            } else {
                out.add(new Material(describeCandidates(candidates), candidates, 1, 0));
            }
        }
        return out;
    }

    private static int indexOfSameCandidates(List<Material> materials, List<String> candidates) {
        for (int i = 0; i < materials.size(); i++) {
            if (materials.get(i).candidates().equals(candidates)) {
                return i;
            }
        }
        return -1;
    }

    private static String describeCandidates(List<String> candidates) {
        return candidates.size() == 1
                ? candidates.get(0)
                : candidates.size() + "选1(" + candidates.get(0) + "…)";
    }

    /** 算出**还缺**哪些材料（按 crafts 次合成折算总需求）。 */
    private static List<Material> shortageOf(ServerPlayer bot, List<Material> materials, int crafts) {
        List<Material> missing = new ArrayList<>();
        for (Material material : materials) {
            int need = material.perCraft() * crafts;
            int have = 0;
            for (String candidate : material.candidates()) {
                ResourceLocation id = ResourceLocation.tryParse(candidate);
                Item item = id == null ? null : BuiltInRegistries.ITEM.get(id);
                if (item != null) {
                    have += countInInventory(bot, item);
                }
            }
            if (have < need) {
                missing.add(new Material(material.describe(), material.candidates(),
                        material.perCraft(), need - have));
            }
        }
        return missing;
    }

    /**
     * 这台机器（按**方块 id**）有没有**执行准入**（`MachineMap` 是唯一出处；D-217 起生产只驱动这一档）。
     * `false` 的语义是"读得出路线、但 Alice 还没有它的执行适配" ⇒ 调用方应**如实拒绝**，不是"做不到"。
     */
    public static boolean hasExecutor(String machineBlockId) {
        MachineMap.Row row = MachineMap.forBlock(machineBlockId);
        return row != null && row.capability() == MachineMap.Capability.EXECUTABLE;
    }

    /** 背包里该物品的总数（只读）。 */
    public static int countInInventory(ServerPlayer bot, Item item) {
        int total = 0;
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }
}
