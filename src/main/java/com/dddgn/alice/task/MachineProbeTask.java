package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.MachineMap;
import com.dddgn.alice.decision.RecipeDump;
import com.dddgn.alice.ledger.WorldModLedger;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
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
    private int typeCount = -1;
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
        // 该命名空间**没有**被跳过的机器类型 ⇒ 模组不在/不适用 ⇒ 由电池记 SKIP（不判红）
        if (typeCount == 0) {
            return NAMESPACE + "_absent";
        }
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
        typeCount = types;
        int typeTotal = byType.values().stream().mapToInt(List::size).sum();
        BotLog.info("[MachineProbe] 命名空间={} 类型={} 条数={}（全表：可读={} 跳过={}）",
                NAMESPACE, types, typeTotal, readable, skipped);

        // ==================== S3（D-209）机器映射覆盖检查 ====================
        // 口径：**先把表里的行分完桶**（表行数 == 各桶之和，可自校），再反查运行时多出来的类型。
        // 分桶对表行做**完整划分**，因此"表 27 行、mapped 22"这种看着像缺口的数字不再出现歧义：
        //   with_site_confirmed  = 有站点，且该类型**在配方管理器里出现** ⇒ 本模组集下已核对
        //   with_site_unobserved = 有站点，但该类型**没在管理器里出现** ⇒ 上游 0 配方（如 smelting）时
        //                          管理器里根本没这个键；**只报事实不判红**（配方可被数据包/配置增删，
        //                          把"今天为 0"钉成期望，将来加一条配方就假红）
        //   no_site              = 表里**无站点**（多方块/机器内部）⇒ 查询层如实回落成类型 id
        //   row_block_missing    = 表里写了方块 id、注册表里**没有这个方块** ⇒ **判红**（我们自己的错）
        //   unmapped             = **运行时有、表里没有** ⇒ 上游新类型或漏登记（**只告警不判红**：模组集可变）
        java.util.List<String> withSiteConfirmed = new ArrayList<>();
        java.util.List<String> withSiteUnobserved = new ArrayList<>();
        java.util.List<String> noSite = new ArrayList<>();
        java.util.List<String> rowBlockMissing = new ArrayList<>();
        java.util.List<String> unmapped = new ArrayList<>();
        for (MachineMap.Row row : MachineMap.rows()) {
            if (!row.hasSite()) {
                noSite.add(row.typeId());
                continue;
            }
            boolean anyPresent = row.blockIds().stream().anyMatch(
                    id -> BuiltInRegistries.BLOCK.containsKey(ResourceLocation.tryParse(id)));
            if (!anyPresent) {
                rowBlockMissing.add(row.typeId() + "→" + String.join("|", row.blockIds()));
            } else if (byType.containsKey(row.typeId())) {
                withSiteConfirmed.add(row.typeId());
            } else {
                withSiteUnobserved.add(row.typeId());
            }
        }
        for (String typeId : byType.keySet()) {
            if (MachineMap.forType(typeId) == null) {
                unmapped.add(typeId);
            }
        }
        java.util.Collections.sort(withSiteConfirmed);
        java.util.Collections.sort(withSiteUnobserved);
        java.util.Collections.sort(noSite);
        java.util.Collections.sort(unmapped);
        java.util.Collections.sort(rowBlockMissing);
        int rowCount = MachineMap.rows().size();
        int bucketed = withSiteConfirmed.size() + withSiteUnobserved.size()
                + noSite.size() + rowBlockMissing.size();
        if (bucketed != rowCount) {
            failed = true;
            BotLog.warn("[MachineProbe] 机器映射分桶不守恒（表 {} 行，桶内 {} 行）⇒ 探针自身口径 bug",
                    rowCount, bucketed);
        }
        if (!rowBlockMissing.isEmpty()) {
            failed = true;
            BotLog.warn("[MachineProbe] 机器映射表引用了**不存在的方块** ⇒ 表错（我们自己的 bug）：{}",
                    rowBlockMissing);
        }
        if (!unmapped.isEmpty()) {
            BotLog.warn("[MachineProbe] 运行时出现但**表里没有**的机器类型（不判红：模组集可变，应登记为行）：{}",
                    unmapped);
        }
        if (!withSiteUnobserved.isEmpty()) {
            BotLog.info("[MachineProbe] 表里有站点但配方管理器里**未出现**的类型（上游 0 配方或模组集差异，只报事实）：{}",
                    withSiteUnobserved);
        }
        BotLog.info("[MachineProbe] 机器映射 {} | 表={}行 with_site_confirmed={} with_site_unobserved={} no_site={} unmapped={}",
                MachineMap.describe(), rowCount, withSiteConfirmed.size(), withSiteUnobserved, noSite, unmapped);
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
                .append(" machine_map_rows=").append(rowCount)
                .append(" with_site_confirmed=").append(withSiteConfirmed.size())
                .append(" with_site_unobserved=").append(withSiteUnobserved)
                .append(" no_site=").append(noSite)
                .append(" unmapped=").append(unmapped)
                .append(" row_block_missing=").append(rowBlockMissing)
                .append(" no_writes=").append(pending == 0)
                .append(" verdict=").append(failed ? "FAIL" : (types == 0 ? "SKIP" : "PASS"));
        BotLog.info("[MachineProbe] SUMMARY {}", summary);
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[MachineProbe] " + summary));
        }
    }
}
