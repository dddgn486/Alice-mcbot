package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.RecipeDump;
import com.dddgn.alice.ledger.WorldModLedger;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **机器配方只读探针**（阶段 3-B / S1，D-204）：零参数、**零写入**，一次跑完输出 `SUMMARY`。
 *
 * <p>为什么需要它：S0（{@link RecipeDump} 的运行时导出）只给了"被跳过的类型 × 条数"，
 * **不含这些类型的输入/输出样例**（它们本来就不在原版白名单里）⇒ 认机器（S2）之前必须先**取证**：
 * 这些类型长什么样、输入输出是什么形态。
 *
 * <p>口径（与协议 §1 的"只读先于执行"一致）：**只读** `RecipeManager`，按**命名空间**过滤、
 * 按**类型**聚合，每类最多打 {@value #SAMPLES_PER_TYPE} 条样例；**不改世界、不发包、不派任务**。
 * S1 的产出 = 用这批事实去写"类型 → 输入/输出 + 机器类型"的读法（只读），并升级查询层的拒绝码。
 */
public class MachineProbeTask implements Task {

    /** 本轮取证对象（S0 的第一大户；换模组只改这里 + 入口名字）。 */
    private static final String NAMESPACE = "mekanism";
    /** 每个类型最多打几条样例（有界：26 类 × 2 条，够定 S2 的范围，又不至于刷屏）。 */
    private static final int SAMPLES_PER_TYPE = 2;
    private static final int MAX_TICKS = 40;

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private int ticks;
    private boolean finished;
    private boolean failed;

    public MachineProbeTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "MachineProbe";   // `*ProbeTask` 命名约定：探针不招 LLM（D-192 附注二）
    }

    @Override
    public boolean isSelfCheck() {
        return true;
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(bot.blockPosition());
    }

    @Override
    public String failureReason() {
        return failed ? "probe_failed" : "";
    }

    @Override
    public Status tick() {
        if (finished) {
            return failed ? Status.FAILED : Status.DONE;
        }
        if (++ticks > MAX_TICKS) {
            failed = true;
            finished = true;
            return Status.FAILED;
        }
        run();
        finished = true;
        return failed ? Status.FAILED : Status.DONE;
    }

    /**
     * **问上游自述**（协议 §3 第一条：上游自述 + 自校验）：机器类型不认原版语义，但常常**自己声明**了
     * 与输入无关的输出定义（实测 `ItemStackToItemStackRecipe.getOutputDefinition() → List&lt;ItemStack&gt;`）。
     * 这里**按返回值形态**取（不写死具体类名），并**自校验**（非空、不含 AIR）。
     */
    private static java.util.List<ItemStack> upstreamOutputDefinition(Recipe<?> recipe) {
        for (String name : new String[]{"getOutputDefinition", "getOutputs"}) {
            try {
                var method = recipe.getClass().getMethod(name);
                Object value = method.invoke(recipe);
                if (!(value instanceof java.util.List<?> list)) {
                    continue;
                }
                java.util.List<ItemStack> out = new ArrayList<>();
                for (Object entry : list) {
                    if (entry instanceof ItemStack stack && !stack.isEmpty()) {
                        out.add(stack);
                    }
                }
                if (!out.isEmpty()) {
                    return out;   // 自校验通过才采信
                }
            } catch (Throwable ignored) {
                // 没有这个方法/取不到就换下一个（不猜语义）
            }
        }
        return java.util.List.of();
    }

    /** **问上游自述（输入侧）**：`getInput().getRepresentations()`（`InputIngredient#getRepresentations`），自校验非空。 */
    private static java.util.List<String> upstreamInputRepresentations(Recipe<?> recipe) {
        for (String name : new String[]{"getInput", "getItemInput"}) {
            try {
                var accessor = recipe.getClass().getMethod(name);
                Object ingredient = accessor.invoke(recipe);
                if (ingredient == null) {
                    continue;
                }
                var representations = ingredient.getClass().getMethod("getRepresentations").invoke(ingredient);
                if (!(representations instanceof java.util.List<?> list)) {
                    continue;
                }
                java.util.List<String> out = new ArrayList<>();
                for (Object entry : list) {
                    if (entry instanceof ItemStack stack && !stack.isEmpty()) {
                        out.add(BuiltInRegistries.ITEM.getKey(stack.getItem()) + "x" + stack.getCount());
                    }
                    if (out.size() >= 3) {
                        break;
                    }
                }
                if (!out.isEmpty()) {
                    return out;
                }
            } catch (Throwable ignored) {
                // 换下一个名字/形态（不猜语义）
            }
        }
        return java.util.List.of();
    }

    private void run() {
        var server = bot.getServer();
        var access = server.registryAccess();
        Map<String, List<Recipe<?>>> byType = new LinkedHashMap<>();
        int readable = 0;
        int skipped = 0;
        for (Recipe<?> recipe : server.getRecipeManager().getRecipes()) {
            String typeId = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString();
            if (RecipeDump.stationFor(typeId) != null) {
                readable++;
                continue;
            }
            skipped++;
            if (typeId.startsWith(NAMESPACE)) {
                byType.computeIfAbsent(typeId, key -> new ArrayList<>()).add(recipe);
            }
        }
        int samples = 0;
        int unreadableViaVanilla = 0;
        int upstreamReadable = 0;
        int machineOutputNotItem = 0;
        int inputReadable = 0;
        java.util.LinkedHashSet<String> machineOutputs = new java.util.LinkedHashSet<>();
        int types = byType.size();
        int typeTotal = byType.values().stream().mapToInt(List::size).sum();
        BotLog.info("[MachineProbe] 命名空间={} 类型={} 条数={}（全表：可读={} 跳过={}）",
                NAMESPACE, types, typeTotal, readable, skipped);
        for (Map.Entry<String, List<Recipe<?>>> entry : byType.entrySet()) {
            BotLog.info("[MachineProbe]   type={} count={}", entry.getKey(), entry.getValue().size());
            int shown = 0;
            for (Recipe<?> recipe : entry.getValue()) {
                if (shown++ >= SAMPLES_PER_TYPE) {
                    break;
                }
                ItemStack out = recipe.getResultItem(access);
                List<String> ins = new ArrayList<>();
                for (Ingredient ingredient : recipe.getIngredients()) {
                    if (ingredient.isEmpty()) {
                        continue;
                    }
                    ItemStack[] items = ingredient.getItems();
                    ins.add(items.length == 0 ? "?" : BuiltInRegistries.ITEM.getKey(items[0].getItem()).toString());
                    if (ins.size() >= 3) {
                        break;
                    }
                }
                java.util.List<ItemStack> upstream = upstreamOutputDefinition(recipe);
                samples++;
                boolean vanillaReadable = !out.isEmpty() && out.getItem() != net.minecraft.world.item.Items.AIR;
                if (!vanillaReadable) {
                    unreadableViaVanilla++;
                }
                if (!upstream.isEmpty()) {
                    upstreamReadable++;
                    if (machineOutputs.size() < 3) {
                        machineOutputs.add(BuiltInRegistries.ITEM.getKey(upstream.get(0).getItem()).toString());
                    }
                } else if (!vanillaReadable) {
                    machineOutputNotItem++;   // 原版读不出、上游也没给出物品输出 ⇒ 如实归为"非物品输出"
                }
                java.util.List<String> upstreamIn = upstreamInputRepresentations(recipe);
                if (!upstreamIn.isEmpty()) {
                    inputReadable++;
                }
                BotLog.info("[MachineProbe]     sample id={} out={} x{} in={} upstream_item_out={} upstream_in={}",
                        recipe.getId(), BuiltInRegistries.ITEM.getKey(out.getItem()), out.getCount(), ins,
                        upstream.isEmpty() ? "-"
                                : BuiltInRegistries.ITEM.getKey(upstream.get(0).getItem()) + " x"
                                        + upstream.get(0).getCount(),
                        upstreamIn.isEmpty() ? "-" : upstreamIn);
            }
        }
        // **自证式查询验证**（S1/D-204）：拿"上游自述读出来的机器产出"去问查询层，
        // 期望它给出 MACHINE_ROUTE（有出处的路线：机器类型 + 输入），而不是 MACHINE_RECIPE_UNSUPPORTED。
        int machineRouteOk = 0;
        for (String itemId : machineOutputs) {
            net.minecraft.world.item.Item item =
                    BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.tryParse(itemId));
            if (item == net.minecraft.world.item.Items.AIR) {
                continue;
            }
            var result = com.dddgn.alice.task.craft.RecipeQuery.query(server, bot, item, 1);
            boolean isMachineRoute = result.verdict()
                    == com.dddgn.alice.task.craft.RecipeQuery.Verdict.MACHINE_ROUTE;
            if (isMachineRoute) {
                machineRouteOk++;
            }
            BotLog.info("[MachineProbe] query item={} verdict={} {}", itemId, result.verdict(),
                    result.describe());
        }
        int pending = WorldModLedger.pendingForOwner(server, bot.getUUID()).size();
        if (pending != 0) {
            failed = true;
        }
        StringBuilder summary = new StringBuilder();
        summary.append("namespace=").append(NAMESPACE)
                .append(" types=").append(types)
                .append(" type_recipes=").append(typeTotal)
                .append(" readable_total=").append(readable)
                .append(" skipped_total=").append(skipped)
                .append(" samples_per_type=").append(SAMPLES_PER_TYPE)
                .append(" samples=").append(samples)
                .append(" unreadable_via_vanilla=").append(unreadableViaVanilla)
                .append(" upstream_readable=").append(upstreamReadable)
                .append(" machine_output_not_item=").append(machineOutputNotItem)
                .append(" input_readable=").append(inputReadable)
                .append(" query_probed=").append(machineOutputs.size())
                .append(" query_machine_route=").append(machineRouteOk)
                .append(" no_writes=").append(pending == 0)
                .append(" verdict=").append(failed ? "FAIL" : "PASS");
        BotLog.info("[MachineProbe] SUMMARY {}", summary);
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[MachineProbe] " + summary));
        }
    }
}
