package com.dddgn.alice.task.craft;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.List;

/**
 * **机器配方的只读事实**（阶段 3-B / S1，D-204/§6.51；多模组名族 2026-09-14 台账⑮）。
 *
 * <p><b>读取顺序（T3 / B3a，2026-09-14）</b>：**先原版语义**（`Recipe#getIngredients()` /
 * `Recipe#getResultItem(RegistryAccess)`），该字段读不出**才**退到模组名族。
 * 理由：原版接口是**跨模组契约**（模组换了名字也还在），而模组名族是**对上游访问器名字的断言**
 * —— 上游改名不会报错，只会**静默读不出**。台账⑮ 的漂移（探针与查询层两套口径）就是这么产生的。
 *
 * <p>为什么不能**只**看原版语义：机器类型在原版 `Recipe` 上 `getResultItem()` 常返回 AIR、
 * `getIngredients()` 常为空（客户端探针实测：Mekanism 51 条样例里 `unreadable_via_vanilla=31`）
 * ⇒ 只按原版接口读，机器配方**在查询层第一步就被丢掉**。
 *
 * <p><b>逐字段记出处 + 留痕（本轮新增）</b>：
 * <ul>
 *   <li>{@link Facts#inputOrigin()} / {@link Facts#outputOrigin()} 说明该字段最终**由谁给出**
 *       （`vanilla:…` / `mod:…` / `none`）—— 旧实现只留结果，于是"原版读得出"与"只有某个模组读得出"
 *       在数据上**完全同形**，而两者可靠性差一个量级；</li>
 *   <li>vanilla 与模组名族**不一致**时置 {@link Facts#divergent()} 并记 {@link Facts#readNotes()}
 *       —— 本次**仍采信 vanilla**，但差异**不静默吸收**（口径变化必须被量到，不许拿断言盖过去）；</li>
 *   <li>访问器**名字存在但调用失败**（签名变了 / 上游抛异常）记入 {@link Facts#readNotes()}
 *       —— 旧实现 `catch (Throwable ignored)` 让"**读不懂**"与"**没有**"不可区分；
 *       **名字不存在**（`NoSuchMethodException`）是名族的**正常情形**，**不入 notes**（否则刷屏）。</li>
 * </ul>
 *
 * <p><b>两类访问器名族</b>（都是上游自述，按返回值形态自校验，**不写死具体类名**）：
 * <table border="1">
 *   <caption>名族</caption>
 *   <tr><th>用途</th><th>Mekanism 形状</th><th>Thermal 形状（实测签名，`javap`）</th></tr>
 *   <tr><td>物品输入</td><td>`getInput()` / `getItemInput()` → 配料对象 → `getRepresentations()`</td>
 *       <td>`getInputItems()` → `List&lt;Ingredient&gt;`</td></tr>
 *   <tr><td>流体输入</td><td>—（当前仅用于"非物品输入"判据）</td><td>`getInputFluids()`</td></tr>
 *   <tr><td>物品输出</td><td>`getOutputDefinition()` / `getOutputs()`</td>
 *       <td>`getOutputItems()` → `List&lt;ItemStack&gt;`</td></tr>
 *   <tr><td>产出概率</td><td>—（无此访问器 ⇒ **无概率证据**）</td>
 *       <td>`getOutputItemChances()` → `List&lt;Float&gt;`</td></tr>
 * </table>
 *
 * <p><b>⚠️ 产出概率（台账⑮，2026-09-14 两次取证）</b>：Thermal 的产出**带概率信息** —— 实测
 * **146 / 670** 条 Thermal 配方声明了 `chance` 字段，取值跨 **0.05 ~ 12.5**（**不在 [0,1]**，
 * `2.0` 出现 50 次）⇒ **它不是"概率"本身，语义未取证**。
 * 因此本类**只做两件不越界的事**：① `Facts.outputChances` 原样带出数值；② {@link Facts#chanceDeclared()}
 * 声明"有概率信息"⇒ 调用方**不得**把该产出当成必然产物。
 * **不猜**"必然 vs 概率"（原先写成 `chance &lt; 1.0` 是猜语义，已撤掉）。
 * 读不到概率信息的类型（Mekanism）保持既有行为。
 *
 * <p>**只读**：不调用任何写方法、不碰世界。读不出就返回 {@link #EMPTY}，由调用方**如实报码**。
 */
public final class MachineRecipeFacts {

    /** 出处：**原版语义接口**（`Recipe#getIngredients()`）。 */
    public static final String VANILLA_INGREDIENTS = "vanilla:getIngredients";

    /** 出处：**原版语义接口**（`Recipe#getResultItem(RegistryAccess)`）。 */
    public static final String VANILLA_RESULT = "vanilla:getResultItem";

    /** 出处：**该字段读不出**（≠"没有"，见 {@link Facts#nonItemInput()}）。 */
    public static final String ORIGIN_NONE = "none";

    /**
     * 读出来的事实（空列表 = 读不出；调用方据此如实报码，不猜）。
     *
     * @param inputOrigin   输入最终由谁给出（{@link #VANILLA_INGREDIENTS} / `mod:&lt;访问器名&gt;` / {@link #ORIGIN_NONE}）
     * @param outputOrigin  产出最终由谁给出（{@link #VANILLA_RESULT} / `mod:&lt;访问器名&gt;` / {@link #ORIGIN_NONE}）
     * @param chanceOrigin  概率由谁给出（`mod:&lt;访问器名&gt;` / {@link #ORIGIN_NONE}；vanilla **没有**概率访问器）
     * @param divergent     vanilla 与模组名族**给出了不同结果**（本次采信 vanilla，差异仅留痕）
     * @param readNotes     **读不懂的留痕**：访问器调用失败、vanilla 与模组名族不一致（**不含"访问器不存在"**）
     */
    public record Facts(String typeId, String recipeId, List<ItemStack> inputs, List<ItemStack> outputs,
                        boolean inputIngredientPresent, List<Float> outputChances,
                        String inputOrigin, String outputOrigin, String chanceOrigin,
                        boolean divergent, List<String> readNotes) {

        /** 物品语义上**可读**（输入与输出都有物品形态）。 */
        public boolean itemReadable() {
            return !inputs.isEmpty() && !outputs.isEmpty();
        }

        /**
         * **配方的输入配料存在、但不是物品**（化学品/流体/气体等）。
         *
         * <p>为什么要单独这一栏（2026-09-13 实测）：`mekanism:crystallizing` 的路线读出来是 `mats=[]`，
         * 而它其实**需要化学输入** —— `mats=[]` 会被读成"不需要材料"。**"读不出"与"没有"必须分开报**。
         */
        public boolean nonItemInput() {
            return inputIngredientPresent && inputs.isEmpty();
        }

        /** 输入走的是**原版语义接口**（不是对上游访问器名的断言）。 */
        public boolean inputFromVanilla() {
            return VANILLA_INGREDIENTS.equals(inputOrigin);
        }

        /** 产出走的是**原版语义接口**（不是对上游访问器名的断言）。 */
        public boolean outputFromVanilla() {
            return VANILLA_RESULT.equals(outputOrigin);
        }

        /**
         * **上游给出了产出概率信息**（`getOutputItemChances()` 非空）。
         *
         * <p><b>⚠️ 只说"有概率信息"，不解释数值（2026-09-14 实测自我纠正）</b>：Thermal 的 `chance`
         * 取值实测跨 **0.05 ~ 12.5**（还有 `2.0` 出现 50 次），**不在 [0,1] 区间**
         * ⇒ 它**不是**直接的"概率"，而是**概率/倍率语义未取证**的量。
         * 所以本方法**不做**"必然 vs 概率"的判定（原先写成 `chance < 1.0` 是**在猜语义**，已撤），
         * 调用方只能据此**不把该产出当成必然产物**，并用原始值如实呈现。
         *
         * <p>反向也不承诺：`false` 只表示"上游没给概率信息"（如 Mekanism），**不等于**"必然产出"。
         */
        public boolean chanceDeclared() {
            return !outputChances.isEmpty();
        }

        public String describe() {
            return "type=" + typeId + " id=" + recipeId + " in=" + inputs.size() + " out=" + outputs.size()
                    + " in_from=" + inputOrigin + " out_from=" + outputOrigin
                    + (nonItemInput() ? " input=非物品" : "")
                    + (chanceDeclared() ? " chances=" + outputChances + "（声明了概率，语义未取证）" : "")
                    + (divergent ? " ⚠️vanilla与模组名族不一致" : "")
                    + (readNotes.isEmpty() ? "" : " notes=" + readNotes);
        }
    }

    public static final Facts EMPTY = new Facts("-", "-", List.of(), List.of(), false, List.of(),
            ORIGIN_NONE, ORIGIN_NONE, ORIGIN_NONE, false, List.of());

    private MachineRecipeFacts() {
    }

    /**
     * 读一道配方的物品事实（**与生产查询层同一份实现**）。
     *
     * <p>顺序见类注释：**逐字段先原版语义、再模组名族**；出处与留痕都记进 {@link Facts}。
     *
     * @param access **必须非空**：`getResultItem(RegistryAccess)` 要用它解析标签/注册表
     *               （传 null 会读不出产出 —— 那是**假读不出**，不是"没有产出"）
     */
    public static Facts read(Recipe<?> recipe, RegistryAccess access) {
        if (recipe == null) {
            return EMPTY;
        }
        String typeId = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString();
        List<String> notes = new ArrayList<>();
        boolean divergent = false;

        // ---------- 输入 ----------
        // 流体输入也算"配料存在"：否则**纯流体输入**的机器配方会报成 `mats=[]`（"不需要材料"，D-204 的 bug 类）。
        List<ItemStack> vanillaIns = itemStacks(vanillaIngredients(recipe, notes), notes, "in");
        // 模组名族**照样问一遍**：即使 vanilla 读出来了，也要能发现两者不一致（差异必须留痕）。
        Hit modIn = callFirst(recipe, notes, "in", "getInput", "getItemInput");
        if (modIn == null) {
            modIn = callFirst(recipe, notes, "in", "getInputItems");
        }
        List<ItemStack> modIns = itemStacks(modIn == null ? null : modIn.value(), notes, "in");
        boolean modFluidIn = modIn == null && callFirst(recipe, notes, "in", "getInputFluids") != null;

        List<ItemStack> inputs;
        String inputOrigin;
        boolean inputPresent;
        if (!vanillaIns.isEmpty()) {
            inputs = vanillaIns;
            inputOrigin = VANILLA_INGREDIENTS;
            inputPresent = true;
            if (!modIns.isEmpty() && !sameStacks(modIns, vanillaIns)) {
                divergent = true;
                notes.add("in:divergent(vanilla=" + summarise(vanillaIns) + ",mod:" + modIn.name() + "="
                        + summarise(modIns) + ")");
            }
        } else if (!modIns.isEmpty()) {
            inputs = modIns;
            inputOrigin = "mod:" + modIn.name();
            inputPresent = true;
        } else {
            // 配料存在但**不是物品**（化学品/流体）：**"读不出"与"没有"分开报**（D-204 附注七）。
            inputs = List.of();
            inputOrigin = modIn != null ? "mod:" + modIn.name()
                    : (modFluidIn ? "mod:getInputFluids" : ORIGIN_NONE);
            inputPresent = modIn != null || modFluidIn;
        }

        // ---------- 产出 ----------
        ItemStack vanillaOut = vanillaResult(recipe, access, notes);
        Hit modOut = callFirst(recipe, notes, "out", "getOutputDefinition", "getOutputs", "getOutputItems");
        List<ItemStack> modOuts = itemStacks(modOut == null ? null : modOut.value(), notes, "out");

        List<ItemStack> outputs;
        String outputOrigin;
        if (!vanillaOut.isEmpty()) {
            outputs = List.of(vanillaOut);
            outputOrigin = VANILLA_RESULT;
            if (!modOuts.isEmpty() && !sameStacks(modOuts, outputs)) {
                divergent = true;
                notes.add("out:divergent(vanilla=" + summarise(outputs) + ",mod:" + modOut.name() + "="
                        + summarise(modOuts) + ")");
            }
        } else if (!modOuts.isEmpty()) {
            outputs = modOuts;
            outputOrigin = "mod:" + modOut.name();
        } else {
            outputs = List.of();
            outputOrigin = ORIGIN_NONE;
        }

        // ---------- 产出概率（原版**没有**这个访问器 ⇒ 只可能来自模组名族） ----------
        Hit modChance = callFirst(recipe, notes, "chance", "getOutputItemChances");
        return new Facts(typeId, recipe.getId().toString(), inputs, outputs, inputPresent,
                chances(modChance == null ? null : modChance.value()),
                inputOrigin, outputOrigin,
                modChance == null ? ORIGIN_NONE : "mod:" + modChance.name(),
                divergent, List.copyOf(notes));
    }

    /** 一次成功的访问器命中：**名字**（= 出处）+ 值。 */
    private record Hit(String name, Object value) {
    }

    /** 原版语义输入：`Recipe#getIngredients()`（读失败**留痕**，不静默）。 */
    private static List<Ingredient> vanillaIngredients(Recipe<?> recipe, List<String> notes) {
        try {
            List<Ingredient> list = recipe.getIngredients();
            return list == null ? List.<Ingredient>of() : list;
        } catch (Throwable failure) {
            notes.add("in:vanilla:getIngredients:" + failure.getClass().getSimpleName());
            return List.of();
        }
    }

    /** 原版语义产出：`Recipe#getResultItem(RegistryAccess)`（读失败**留痕**，不静默）。 */
    private static ItemStack vanillaResult(Recipe<?> recipe, RegistryAccess access, List<String> notes) {
        if (access == null) {
            return ItemStack.EMPTY;
        }
        try {
            ItemStack stack = recipe.getResultItem(access);
            return stack == null ? ItemStack.EMPTY : stack;
        } catch (Throwable failure) {
            notes.add("out:vanilla:getResultItem:" + failure.getClass().getSimpleName());
            return ItemStack.EMPTY;
        }
    }

    /** 依次尝试若干**无参访问器名**（按返回值形态判断），取第一个"能给出非空列表"的。 */
    private static Hit callFirst(Object target, List<String> notes, String field, String... names) {
        for (String name : names) {
            try {
                Object value = target.getClass().getMethod(name).invoke(target);
                if (value == null) {
                    continue;
                }
                if (value instanceof List<?> list && list.isEmpty()) {
                    continue;
                }
                return new Hit(name, value);   // 输入侧可能是"配料对象"，真正的列举在它自己的 getRepresentations()
            } catch (NoSuchMethodException absent) {
                // **名族里没有这个名字是常态**（各模组命名不同）⇒ 不记录，否则 notes 会被刷屏
            } catch (Throwable failure) {
                // **不再静默**（T3/B3a）：名字在、但调用失败（签名变了/上游抛异常）⇒ 必须与"没有"可区分
                notes.add(field + ":" + name + ":" + failure.getClass().getSimpleName());
            }
        }
        return null;
    }

    /**
     * 把"配料对象 / 配料列表 / 物品列表"归一成物品栈列表：**逐项容错**，全失败就返回空（如实读不出）。
     *
     * <p>两种形态都要吃：Mekanism 的单个配料对象（`getRepresentations()` 给出代表物品），
     * 以及 Thermal 的 `List&lt;Ingredient&gt;`（每项取**第一个物品**当代表示 —— 与 `getRepresentations()`
     * 同一口径：**只取代表，不展开标签**，避免把标签展开成几十个物品）。
     */
    private static List<ItemStack> itemStacks(Object value, List<String> notes, String field) {
        if (value == null) {
            return List.of();
        }
        Object list = value instanceof List<?> ? value : callRepresentations(value, notes, field);
        if (!(list instanceof List<?> entries)) {
            return List.of();
        }
        List<ItemStack> out = new ArrayList<>();
        for (Object entry : entries) {
            if (entry instanceof ItemStack stack && !stack.isEmpty()) {
                out.add(stack);
            } else if (entry instanceof Ingredient ingredient) {
                ItemStack[] items = ingredient.getItems();
                if (items.length > 0 && !items[0].isEmpty()) {
                    out.add(items[0]);
                }
            }
        }
        return out;
    }

    /** 产出概率列表（读不到就空 ⇒ `chanceDeclared()` 为假 = 无概率证据）。 */
    private static List<Float> chances(Object value) {
        if (!(value instanceof List<?> entries)) {
            return List.of();
        }
        List<Float> out = new ArrayList<>();
        for (Object entry : entries) {
            if (entry instanceof Number number) {
                out.add(number.floatValue());
            }
        }
        return out;
    }

    private static Object callRepresentations(Object ingredient, List<String> notes, String field) {
        try {
            return ingredient.getClass().getMethod("getRepresentations").invoke(ingredient);
        } catch (NoSuchMethodException absent) {
            return null;
        } catch (Throwable failure) {
            notes.add(field + ":getRepresentations:" + failure.getClass().getSimpleName());
            return null;
        }
    }

    /** 只比"物品 + 数量"的序列（**不展开标签**，与读取口径一致）。 */
    private static boolean sameStacks(List<ItemStack> left, List<ItemStack> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (left.get(index).getItem() != right.get(index).getItem()
                    || left.get(index).getCount() != right.get(index).getCount()) {
                return false;
            }
        }
        return true;
    }

    /** 紧凑摘要（**用于留痕，不用于判据**）。 */
    private static String summarise(List<ItemStack> stacks) {
        List<String> parts = new ArrayList<>();
        for (ItemStack stack : stacks) {
            parts.add(BuiltInRegistries.ITEM.getKey(stack.getItem()) + "x" + stack.getCount());
        }
        return parts.toString();
    }
}
