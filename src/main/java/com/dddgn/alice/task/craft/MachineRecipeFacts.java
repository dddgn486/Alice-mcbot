package com.dddgn.alice.task.craft;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.List;

/**
 * **机器配方的只读事实**（阶段 3-B / S1，D-204/§6.51）：问**上游自述**取输入/输出，**自校验**后采信。
 *
 * <p>为什么不能只看原版语义：机器类型（Mekanism 等）在原版 `Recipe` 上 `getResultItem()` 常返回 AIR、
 * `getIngredients()` 常为空（客户端探针实测：51 条样例里 `unreadable_via_vanilla=31`、`in=[]` 普遍为空）
 * ⇒ 只按原版接口读，机器配方**在查询层第一步就被丢掉**。
 *
 * <p>本类只做两件事（都是协议 §3 第一条"上游自述驱动"的复用，**不写死具体类名**）：
 * <ul>
 *   <li>**输出**：`getOutputDefinition()` / `getOutputs()` → 非空且不含 AIR 才采信；
 *   <li>**输入**：`getInput()`（或 `getItemInput()`）→ 结果对象上的 `getRepresentations()` → 非空才采信。</li>
 * </ul>
 * **只读**：不调用任何写方法、不碰世界。读不出就返回 {@link #EMPTY}，由调用方**如实报码**。
 */
public final class MachineRecipeFacts {

    /** 读出来的事实（空列表 = 读不出；调用方据此如实报码，不猜）。 */
    public record Facts(String typeId, String recipeId, List<ItemStack> inputs, List<ItemStack> outputs,
                        boolean inputIngredientPresent) {

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

        public String describe() {
            return "type=" + typeId + " id=" + recipeId + " in=" + inputs.size() + " out=" + outputs.size()
                    + (nonItemInput() ? " input=非物品" : "");
        }
    }

    public static final Facts EMPTY = new Facts("-", "-", List.of(), List.of(), false);

    private MachineRecipeFacts() {
    }

    public static Facts read(Recipe<?> recipe) {
        if (recipe == null) {
            return EMPTY;
        }
        String typeId = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString();
        Object inputIngredient = callFirst(recipe, "getInput", "getItemInput");
        return new Facts(typeId, recipe.getId().toString(),
                itemStacks(inputIngredient),
                itemStacks(callFirst(recipe, "getOutputDefinition", "getOutputs")),
                inputIngredient != null);
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

    /** 把"配料对象/物品列表"归一成物品栈列表：**逐项容错**，全失败就返回空（如实读不出）。 */
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
