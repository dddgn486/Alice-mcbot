package com.dddgn.alice.task.craft;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.List;

/**
 * **机器配方的只读事实**（阶段 3-B / S1，D-204/§6.51；多模组名族 2026-09-14 台账⑮）：问**上游自述**取输入/输出，
 * **自校验**后采信。
 *
 * <p>为什么不能只看原版语义：机器类型在原版 `Recipe` 上 `getResultItem()` 常返回 AIR、
 * `getIngredients()` 常为空（客户端探针实测：Mekanism 51 条样例里 `unreadable_via_vanilla=31`）
 * ⇒ 只按原版接口读，机器配方**在查询层第一步就被丢掉**。
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
 * <p><b>⚠️ 概率产出（台账⑮，2026-09-14）</b>：Thermal 的产出**可以是有概率的** —— 实测本客户端
 * **65 / 670** 条 Thermal 配方带 `chance &lt; 1.0`（`pulverizer` 32/81、`smelter` 21/70…）。
 * 只读 `getOutputItems()` 会把"5% 的副产物"写成"必然产出"（**过度承诺**）。
 * ⇒ {@link Facts#probabilistic()} **只在真的读到 `chance &lt; 1.0` 时为真**；
 * 读不到概率信息的类型（Mekanism）保持"**无概率证据**"，既有行为不变。
 * **`false` 不等于"保证必然产出"**，只表示"没有概率证据"—— 这一点由调用方按需措辞，别倒过来用。
 *
 * <p>**只读**：不调用任何写方法、不碰世界。读不出就返回 {@link #EMPTY}，由调用方**如实报码**。
 */
public final class MachineRecipeFacts {

    /** 读出来的事实（空列表 = 读不出；调用方据此如实报码，不猜）。 */
    public record Facts(String typeId, String recipeId, List<ItemStack> inputs, List<ItemStack> outputs,
                        boolean inputIngredientPresent, List<Float> outputChances) {

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

        /**
         * **已知至少一条产出不是必然**（读到 `chance &lt; 1.0`）。
         *
         * <p>口径严格单向：**只有读到机会值且其中 &lt;1.0 才为真**。读不到机会值的类型保持 `false`
         * （既有行为不变），但 `false` **不构成**"必然产出"的承诺。
         */
        public boolean probabilistic() {
            return outputChances.stream().anyMatch(chance -> chance != null && chance < 1.0F);
        }

        public String describe() {
            return "type=" + typeId + " id=" + recipeId + " in=" + inputs.size() + " out=" + outputs.size()
                    + (nonItemInput() ? " input=非物品" : "")
                    + (probabilistic() ? " chances=" + outputChances + "（概率产出）" : "");
        }
    }

    public static final Facts EMPTY = new Facts("-", "-", List.of(), List.of(), false, List.of());

    private MachineRecipeFacts() {
    }

    public static Facts read(Recipe<?> recipe) {
        if (recipe == null) {
            return EMPTY;
        }
        String typeId = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString();
        // 输入：先 Mekanism 的"单一配料对象"名族，再 Thermal 的"配料列表"名族。
        // 流体输入也算"配料存在"：否则**纯流体输入**的机器配方会报成 `mats=[]`（"不需要材料"，D-204 的 bug 类）。
        Object inputSource = callFirst(recipe, "getInput", "getItemInput");
        boolean inputPresent = inputSource != null;
        if (inputSource == null) {
            inputSource = callFirst(recipe, "getInputItems");            // Thermal：List<Ingredient>
            inputPresent = inputSource != null;
        }
        if (!inputPresent) {
            inputPresent = callFirst(recipe, "getInputFluids") != null;
        }
        // 输出：Mekanism 名族优先，再 Thermal 的 `getOutputItems()`。
        Object outputSource = callFirst(recipe, "getOutputDefinition", "getOutputs", "getOutputItems");
        return new Facts(typeId, recipe.getId().toString(),
                itemStacks(inputSource),
                itemStacks(outputSource),
                inputPresent,
                chances(callFirst(recipe, "getOutputItemChances")));
    }

    /** 依次尝试若干**无参访问器名**（按返回值形态判断），取第一个"能给出非空列表"的。 */
    private static Object callFirst(Object target, String... names) {
        for (String name : names) {
            try {
                var method = target.getClass().getMethod(name);
                Object value = method.invoke(target);
                if (value == null) {
                    continue;
                }
                if (value instanceof List<?> list) {
                    if (!list.isEmpty()) {
                        return value;
                    }
                    continue;
                }
                return value;   // 输入侧是"配料对象"，真正的列举在它自己的 getRepresentations()
            } catch (Throwable ignored) {
                // 换下一个名字（不猜语义）
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
    private static List<ItemStack> itemStacks(Object value) {
        if (value == null) {
            return List.of();
        }
        Object list = value instanceof List<?> ? value : callRepresentations(value);
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

    /** 产出概率列表（读不到就空 ⇒ `probabilistic()` 为假 = 无概率证据）。 */
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

    private static Object callRepresentations(Object ingredient) {
        try {
            return ingredient.getClass().getMethod("getRepresentations").invoke(ingredient);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
