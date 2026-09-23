package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.MachineMap;
import com.dddgn.alice.decision.RecipeDump;
import com.dddgn.alice.ledger.WorldModLedger;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.task.craft.MachineRecipeFacts;
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
 * <p>口径（与协议 §1 的"只读先于执行"一致）：**只读** `RecipeManager`，按**命名空间**过滤
 * （命名空间 = {@code MachineMap} 表里出现过的那些，**按表推导、不写死**）、
 * 按**类型**聚合，每类最多打 {@value #SAMPLES_PER_TYPE} 条样例；**不改世界、不发包、不派任务**。
 * S1 的产出 = 用这批事实去写"类型 → 输入/输出 + 机器类型"的读法（只读），并升级查询层的拒绝码。
 */
public class MachineProbeTask implements Task {

    /**
     * 本轮取证对象 = **`MachineMap` 表里出现过的全部命名空间**（S3 起不再写死一个常量，台账⑭）。
     *
     * <p>为什么必须按表推导：表加了第二个模组（Thermal）之后，写死 `"mekanism"` 会让
     * **26 个 Thermal 站点行永远进不了 `with_site_confirmed`** —— 它们全部落进 `with_site_unobserved`，
     * 而那个桶的日志文案写的是"上游 0 配方或模组集差异" ⇒ 会把"**探针没采样这个命名空间**"
     * 误读成"该模组没有配方"（第十四轮实测 `22 + 27 + 10 + 0 = 59` 才发现）。
     * 表是唯一真源 ⇒ 探针跟着表走，加模组**不用改这里**。
     */
    private static List<String> adoptedNamespaces() {
        java.util.LinkedHashSet<String> namespaces = new java.util.LinkedHashSet<>();
        for (MachineMap.Row row : MachineMap.rows()) {
            int colon = row.typeId().indexOf(':');
            if (colon > 0) {
                namespaces.add(row.typeId().substring(0, colon));
            }
        }
        return List.copyOf(namespaces);
    }

    /** 每个类型最多打几条样例（有界：够定 S2/S3 的范围，又不至于刷屏）。 */
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
        // 表里那些命名空间**一个类型都没被跳过** ⇒ 模组不在/不适用 ⇒ 由电池记 SKIP（不判红）。
        // 电池侧的判据是 `failureReason().contains("_absent")`，所以**后缀必须保留**。
        if (typeCount == 0) {
            return "machine_namespaces_absent";
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
     * **配方读取**走 {@link MachineRecipeFacts#read}（**与生产查询层同一份实现**）。
     *
     * <p>这里原先有两份各自反射的私有读取器（`upstreamOutputDefinition` / `upstreamInputRepresentations`），
     * 于是"探针读得出来、查询层读不出来"这类**两处口径漂移**只能靠人眼发现 —— 台账⑮ 就是这种漂移的产物
     * （探针报 `upstream_readable=0` 而真正的原因是名族只认 Mekanism）。**现在只有一个读取器**。
     */
    private void run() {
        var server = bot.getServer();
        var access = server.registryAccess();
        List<String> namespaces = adoptedNamespaces();
        java.util.Set<String> adopted = new java.util.LinkedHashSet<>(namespaces);
        Map<String, List<Recipe<?>>> byType = new LinkedHashMap<>();
        // **（T3 步骤 A）把枚举来源从"表"换成"配方注册表"**：旧实现只把**已登记命名空间**的类型收进
        // `byType`，而 `unmapped`（"运行时有、表里没有"）又只遍历 `byType` ⇒ **结构上看不见任何
        // 我们还没登记过的模组**。实测：无头生产服务端里装着 create / ExtendedCrafting /
        // refinedstorage / sophisticated*，而探针 `namespaces=[mekanism, thermal]`、`unmapped=[]`
        // —— 一个字都不说。那正是 T3 要接的对象。
        // 现在：**类型/配方计数覆盖全部非原版命名空间**；**采样范围仍只限已登记命名空间**
        // （否则每个模组都会撑长一轮的时间，而"能不能接"与"采样多少条"是两件事）。
        java.util.Set<String> allTypes = new java.util.TreeSet<>();
        Map<String, Integer> recipesByNamespace = new java.util.TreeMap<>();
        // **T3 步骤 A2**：未登记命名空间的配方也留一份（类型 id 有序）⇒ 可以定点采它们的**形状**。
        // 与 `byType` 完全分开：现有 8 个桶、`samples`、`machineOutputs` **一个都不碰**，基线不移动。
        Map<String, List<Recipe<?>>> unregisteredByType = new java.util.TreeMap<>();
        int readable = 0;
        int skipped = 0;
        for (Recipe<?> recipe : server.getRecipeManager().getRecipes()) {
            String typeId = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString();
            if (RecipeDump.stationFor(typeId) != null) {
                readable++;
                continue;
            }
            skipped++;
            allTypes.add(typeId);
            int colon = typeId.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String namespace = typeId.substring(0, colon);
            recipesByNamespace.merge(namespace, 1, Integer::sum);
            if (adopted.contains(namespace)) {
                byType.computeIfAbsent(typeId, key -> new ArrayList<>()).add(recipe);
            } else {
                unregisteredByType.computeIfAbsent(typeId, key -> new ArrayList<>()).add(recipe);
            }
        }
        // 每个命名空间的**去重类型数**（配方条数另算）—— 这一个数就回答"要接的模组有多少个机器类型"。
        Map<String, Integer> typesByNamespace = new java.util.TreeMap<>();
        for (String typeId : allTypes) {
            int colon = typeId.indexOf(':');
            if (colon > 0) {
                typesByNamespace.merge(typeId.substring(0, colon), 1, Integer::sum);
            }
        }
        int samples = 0;
        int unreadableViaVanilla = 0;
        int upstreamReadable = 0;
        int machineOutputNotItem = 0;
        int inputReadable = 0;
        int chanceDeclared = 0;   // 台账⑮：上游声明了产出概率信息的抽样条数（只计"有"，不解释数值）
        // **T3/B3a 出处与留痕计数**（只计数、不判红）：用来量"改成 vanilla 优先"到底改变了什么。
        // 没有这几个数，"读取器改了但结论没变"与"读取器根本没生效"在数据上**完全同形**。
        int vanillaInput = 0;
        int vanillaOutput = 0;
        int divergent = 0;
        java.util.LinkedHashSet<String> readNotes = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> machineOutputs = new java.util.LinkedHashSet<>();
        Map<String, int[]> perNamespace = new LinkedHashMap<>();
        int types = byType.size();
        typeCount = types;
        int typeTotal = byType.values().stream().mapToInt(List::size).sum();
        BotLog.info("[MachineProbe] 命名空间={}（按 `MachineMap` 推导，不再写死）类型={} 条数={}（全表：可读={} 跳过={}）",
                namespaces, types, typeTotal, readable, skipped);

        // ==================== S3（D-209）机器映射覆盖检查 ====================
        // 口径：**先把表里的行分完桶**（表行数 == 各桶之和，可自校），再反查运行时多出来的类型。
        // 分桶对表行做**完整划分**，因此"表 59 行、mapped 22"这种看着像缺口的数字不再出现歧义：
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
        java.util.List<String> sharedSite = new ArrayList<>();
        java.util.List<String> rowBlockMissing = new ArrayList<>();
        java.util.List<String> unmapped = new ArrayList<>();
        for (MachineMap.Row row : MachineMap.rows()) {
            if (!row.hasSite()) {
                noSite.add(row.typeId());
                continue;
            }
            // **T3：必须走 `siteBlock()`，不能读 `blockIds()`** —— `SiteKind.SHARED` 行的 `blockIds` 是空的
            // （共享写在 `hostTypeId`），站点要问宿主行。读 `blockIds()` 会把 6 个 Thermal 子类型
            // 判成"表引用了不存在的方块" ⇒ **假红**。
            String site = row.siteBlock();
            if (row.siteKind() == MachineMap.SiteKind.SHARED) {
                sharedSite.add(row.typeId() + "→" + row.hostTypeId());
            }
            boolean present = site != null
                    && BuiltInRegistries.BLOCK.containsKey(ResourceLocation.tryParse(site));
            if (!present) {
                rowBlockMissing.add(row.typeId() + "→" + (site == null ? "<宿主解析失败>" : site));
            } else if (byType.containsKey(row.typeId())) {
                withSiteConfirmed.add(row.typeId());
            } else {
                withSiteUnobserved.add(row.typeId());
            }
        }
        // **（T3 步骤 A）`unmapped` 的遍历域从 `byType`（已登记命名空间）换成 `allTypes`（全部非原版类型）**：
        // 否则"某个模组出现了、我们一行都没登记"这件事**永远不会被报出来**（旧实现里它连枚举都进不去）。
        for (String typeId : allTypes) {
            if (MachineMap.forType(typeId) == null) {
                unmapped.add(typeId);
            }
        }
        java.util.Collections.sort(withSiteConfirmed);
        java.util.Collections.sort(withSiteUnobserved);
        java.util.Collections.sort(noSite);
        java.util.Collections.sort(unmapped);
        java.util.Collections.sort(rowBlockMissing);
        // **上游枚举序的指纹**（只报读数、不判红）：抽样已改成按 id 排序 ⇒ 本探针的读数与它无关，
        // 但一旦将来基线又"动了"，这个数能立刻区分"**枚举序变了**"与"**读取器变了**"。
        long recipeOrderHash = 17L;
        for (List<Recipe<?>> recipes : byType.values()) {
            for (Recipe<?> recipe : recipes) {
                recipeOrderHash = recipeOrderHash * 31L + recipe.getId().toString().hashCode();
            }
        }
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
            BotLog.warn("[MachineProbe] 运行时出现但**表里没有**的机器类型共 {} 个（不判红：模组集可变，"
                            + "要接某个模组就会看到它）：{}", unmapped.size(),
                    unmapped.size() <= MAX_UNMAPPED_IN_SUMMARY ? unmapped
                            : unmapped.subList(0, MAX_UNMAPPED_IN_SUMMARY) + " …(共 " + unmapped.size() + ")");
        }
        // **（T3 步骤 A）未登记命名空间逐条出声**：这是"接下一个模组"的第一份读数 ——
        // 它回答"这个模组有几个机器类型、有多少条配方、我们登记了几行（0）"。
        // 旧实现对此**完全静默**（枚举来源就是表本身）⇒ "表里没有"和"这个模组不存在"不可区分。
        for (Map.Entry<String, Integer> entry : typesByNamespace.entrySet()) {
            if (adopted.contains(entry.getKey())) {
                continue;
            }
            List<String> typesOfNamespace = new ArrayList<>();
            for (String typeId : allTypes) {
                if (typeId.startsWith(entry.getKey() + ":")) {
                    typesOfNamespace.add(typeId);
                }
            }
            BotLog.warn("[MachineProbe] 未登记命名空间 ns={} types={} type_recipes={} 表里 0 行 ⇒ 本探针不采样它：{}",
                    entry.getKey(), entry.getValue(), recipesByNamespace.getOrDefault(entry.getKey(), 0),
                    typesOfNamespace);
        }
        if (!withSiteUnobserved.isEmpty()) {
            BotLog.info("[MachineProbe] 表里有站点但配方管理器里**未出现**的类型（上游 0 配方或模组集差异，只报事实）：{}",
                    withSiteUnobserved);
        }
        BotLog.info("[MachineProbe] 机器映射 {} | 表={}行 with_site_confirmed={} shared_site={} with_site_unobserved={} no_site={} unmapped={}",
                MachineMap.describe(), rowCount, withSiteConfirmed.size(), sharedSite, withSiteUnobserved, noSite,
                bounded(unmapped));
        // **两处遍历都必须有序**：`getRecipes()` 的迭代序跨轮不稳定 —— 既影响"每类取前 2 条"，
        // 也影响"类型之间的先后"（实测同一 jar 两轮：`type=thermal:insolator` 先 vs `type=mekanism:pigment_mixing` 先）。
        // 只排内层会让**聚合计数**稳定、而 `machineOutputs`（取最先见到的 3 个产出 ⇒ 自证式查询）仍漂。
        List<Map.Entry<String, List<Recipe<?>>>> orderedTypes = new ArrayList<>(byType.entrySet());
        orderedTypes.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, List<Recipe<?>>> entry : orderedTypes) {
            BotLog.info("[MachineProbe]   type={} count={}", entry.getKey(), entry.getValue().size());
            // 每个命名空间各自的桶：types / recipes / samples / unreadable / upstream_readable / not_item / input_readable  / chance_declared
            int[] namespaceBucket = perNamespace.computeIfAbsent(
                    entry.getKey().substring(0, entry.getKey().indexOf(':')), key -> new int[8]);
            namespaceBucket[0]++;
            namespaceBucket[1] += entry.getValue().size();
            // **抽样必须先按配方 id 排序（2026-09-14 实测教训）**：`RecipeManager.getRecipes()` 的迭代序
            // **跨轮不稳定**（同一 jar、同一世界、同一模组集，两轮抽到的配方 id 不同）⇒ 直接取"前 N 条"
            // 会让本探针的读数**看起来变了而其实只是抽到了别的配方**。实测两次同 jar：`input_readable` 65/64、
            // `query_machine_route` 0/2 —— 与"改了读取器"完全同形。**读数要能当基线，抽样就必须是确定性的。**
            List<Recipe<?>> typeRecipes = new ArrayList<>(entry.getValue());
            typeRecipes.sort(java.util.Comparator.comparing((Recipe<?> recipe) -> recipe.getId().toString()));
            int shown = 0;
            for (Recipe<?> recipe : typeRecipes) {
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
                MachineRecipeFacts.Facts facts = MachineRecipeFacts.read(recipe, access);
                samples++;
                namespaceBucket[2]++;
                if (facts.inputFromVanilla()) {
                    vanillaInput++;
                }
                if (facts.outputFromVanilla()) {
                    vanillaOutput++;
                }
                if (facts.divergent()) {
                    divergent++;
                }
                readNotes.addAll(facts.readNotes());
                boolean vanillaReadable = !out.isEmpty() && out.getItem() != net.minecraft.world.item.Items.AIR;
                if (!vanillaReadable) {
                    unreadableViaVanilla++;
                    namespaceBucket[3]++;
                }
                if (!facts.outputs().isEmpty()) {
                    upstreamReadable++;
                    namespaceBucket[4]++;
                    // **收集全部**（不再"边采边取前 3"）：取前 3 的动作必须发生在**全部采样之后**并按 id 排序，
                    // 否则"自证式查询挑了哪 3 个物品"仍随枚举序漂（实测三轮 2/1/0 就是这么来的）。
                    machineOutputs.add(BuiltInRegistries.ITEM.getKey(facts.outputs().get(0).getItem()).toString());
                } else if (!vanillaReadable) {
                    machineOutputNotItem++;   // 原版读不出、上游也没给出物品输出 ⇒ 如实归为"非物品输出"
                    namespaceBucket[5]++;
                }
                // 口径与旧实现一致：只数"上游自述读出了**输入物品**"（化学品/流体输入不混进来，
                // 否则会和"读不出"混成一个数）；非物品输入由 `RecipeQuery` 的 note 单独如实标注。
                if (!facts.inputs().isEmpty()) {
                    inputReadable++;
                    namespaceBucket[6]++;
                }
                if (facts.chanceDeclared()) {
                    chanceDeclared++;    // 台账⑮：**上游给了概率信息就计数**（数值语义未取证，只声明不解释）
                    namespaceBucket[7]++;
                }
                BotLog.info("[MachineProbe]     sample id={} out={} x{} in={} upstream_item_out={} upstream_in={}{}",
                        recipe.getId(), BuiltInRegistries.ITEM.getKey(out.getItem()), out.getCount(), ins,
                        facts.outputs().isEmpty() ? "-"
                                : BuiltInRegistries.ITEM.getKey(facts.outputs().get(0).getItem()) + " x"
                                        + facts.outputs().get(0).getCount(),
                        facts.inputs().isEmpty() ? (facts.nonItemInput() ? "非物品输入" : "-")
                                : facts.inputs().stream().limit(3)
                                        .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()) + "x"
                                                + stack.getCount())
                                        .collect(java.util.stream.Collectors.joining(",")),
                        facts.chanceDeclared() ? " chances=" + facts.outputChances() : "");
                // **出处逐条打印（T3/B3a）**：只有把"这个字段是谁给的"摆在样例行里，
                // "vanilla 兜底生效了多少"才是**读数**而不是推断；`notes` 是"读不懂"的留痕。
                BotLog.info("[MachineProbe]       origin in={} out={} chance={}{}{}",
                        facts.inputOrigin(), facts.outputOrigin(), facts.chanceOrigin(),
                        facts.divergent() ? " ⚠️divergent" : "",
                        facts.readNotes().isEmpty() ? "" : " notes=" + facts.readNotes());
            }
        }
        // **自证式查询验证**（S1/D-204）：拿"上游自述读出来的机器产出"去问查询层。
        //
        // ⚠️ **`query_machine_route` 是读数，不是不变式**（2026-09-14 实测）：抽样到的机器产出里
        // 很多**同时有原版合成路线**（`cyan_dye` / `light_gray_dye`…）⇒ 查询层按设计先走 `craftable`，
        // 结论**本来就不该**是 `MACHINE_ROUTE`。这个数只说明"抽到的那几个物品恰巧只走机器线"，
        // 与读取器是否正确**无关** —— 证据：**只**把采样改成按 id 排序（零语义改动），它就 0/1/2 → 3。
        // 所以它可以当**本口径下的稳定读数**，但**改采样口径它就会变**，不许升级成判据。
        //
        // **真正有意义的不变式是 `query_reachable`**：读取器既然从某条配方读出了产出 X，
        // 查询层扫同一批配方就**不可能**对 X 报 `NO_RECIPE`（那意味着同一份读取器在两处结论不一致）。
        // 本轮先**只计数不当断言**（measure first），等它在多轮稳定成立后再升级为判红。
        int machineRouteOk = 0;
        int queryReachable = 0;
        int queryProbed = 0;
        List<String> probedItems = machineOutputs.stream().sorted().limit(SELF_CHECK_ITEMS).toList();
        for (String itemId : probedItems) {
            net.minecraft.world.item.Item item =
                    BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.tryParse(itemId));
            if (item == net.minecraft.world.item.Items.AIR) {
                continue;
            }
            queryProbed++;
            var result = com.dddgn.alice.task.craft.RecipeQuery.query(server, bot, item, 1);
            boolean isMachineRoute = result.verdict()
                    == com.dddgn.alice.task.craft.RecipeQuery.Verdict.MACHINE_ROUTE;
            if (isMachineRoute) {
                machineRouteOk++;
            }
            if (result.verdict() != com.dddgn.alice.task.craft.RecipeQuery.Verdict.NO_RECIPE) {
                queryReachable++;
            }
            BotLog.info("[MachineProbe] query item={} verdict={} {}", itemId, result.verdict(),
                    result.describe());
        }
        // 逐命名空间各打一行（SUMMARY 里保留全局合计）：这样"哪一族覆盖了多少"不必从总量里反推，
        // 也避免"26 个 Thermal 行没被采样"被读成"Thermal 没有配方"（台账⑭ 的教训）。
        for (String namespace : namespaces) {
            int[] value = perNamespace.getOrDefault(namespace, new int[8]);
            BotLog.info("[MachineProbe] namespace={} types={} type_recipes={} samples={}"
                            + " unreadable_via_vanilla={} upstream_readable={} machine_output_not_item={}"
                            + " input_readable={} chance_declared={}",
                    namespace, value[0], value[1], value[2], value[3], value[4], value[5], value[6], value[7]);
        }

        // ==================== T3 步骤 A2：未登记命名空间的**形状**（纯只读定点采样） ====================
        // 步骤 A 回答了"要不要接"（有哪些类型、各多少条），但回答不了"**接了要写什么**"：
        // `MachineMap` 的行内容与 `UPSTREAMS` 的能力声明，输入是**形状** —— 物品输入还是非物品输入？
        // 产出读得出吗？有概率信息吗？**与已登记命名空间同一份读取器**（不另写一份 ⇒ 不会出现口径漂移）。
        // **只读**：只读配方对象，不碰世界、不碰方块实体、不写任何东西。
        int unregisteredTypes = 0;
        int unregisteredSampled = 0;
        int unregisteredOutputReadable = 0;
        int unregisteredItemReadable = 0;
        int unregisteredNonItemInput = 0;
        int unregisteredChanceDeclared = 0;
        // **承重判据**：`vanilla 赢 且 名族读不出` ⇒ 这条配方**只有靠原版路径**才读得出，
        // 旧读取器（只有名族）在它身上会返回空产出/空输入。见 `Facts#modOutputCount`。
        int unregisteredVanillaOnlyIn = 0;
        int unregisteredVanillaOnlyOut = 0;
        java.util.LinkedHashSet<String> unregisteredNotes = new java.util.LinkedHashSet<>();
        // 每个未登记命名空间采到的**产出物品**（有序）⇒ 步骤 C/M-4 拿它们去问生产查询层。
        Map<String, java.util.TreeSet<String>> unregisteredOutputsByNamespace = new java.util.TreeMap<>();
        // {types, sampled, out_readable, item_readable, non_item_in, chance}
        Map<String, int[]> shapeByNamespace = new java.util.TreeMap<>();
        for (Map.Entry<String, List<Recipe<?>>> entry : unregisteredByType.entrySet()) {
            String typeId = entry.getKey();
            int[] shape = shapeByNamespace.computeIfAbsent(
                    typeId.substring(0, typeId.indexOf(':')), key -> new int[6]);
            shape[0]++;
            unregisteredTypes++;
            List<Recipe<?>> typeRecipes = new ArrayList<>(entry.getValue());
            typeRecipes.sort(java.util.Comparator.comparing((Recipe<?> recipe) -> recipe.getId().toString()));
            BotLog.info("[MachineProbe]   未登记 type={} count={}", typeId, typeRecipes.size());
            int shown = 0;
            for (Recipe<?> recipe : typeRecipes) {
                if (shown++ >= SAMPLES_PER_TYPE) {
                    break;
                }
                MachineRecipeFacts.Facts facts = MachineRecipeFacts.read(recipe, access);
                unregisteredSampled++;
                shape[1]++;
                if (!facts.outputs().isEmpty()) {
                    unregisteredOutputReadable++;
                    shape[2]++;
                }
                if (facts.itemReadable()) {
                    unregisteredItemReadable++;
                    shape[3]++;
                }
                if (facts.nonItemInput()) {
                    unregisteredNonItemInput++;
                    shape[4]++;
                }
                if (facts.chanceDeclared()) {
                    unregisteredChanceDeclared++;
                    shape[5]++;
                }
                if (facts.inputFromVanilla() && facts.modInputCount() == 0) {
                    unregisteredVanillaOnlyIn++;
                }
                if (facts.outputFromVanilla() && facts.modOutputCount() == 0) {
                    unregisteredVanillaOnlyOut++;
                }
                for (net.minecraft.world.item.ItemStack stack : facts.outputs()) {
                    unregisteredOutputsByNamespace
                            .computeIfAbsent(typeId.substring(0, typeId.indexOf(':')),
                                    key -> new java.util.TreeSet<>())
                            .add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                }
                unregisteredNotes.addAll(facts.readNotes());
                BotLog.info("[MachineProbe]     未登记 sample id={} out={} in={} non_item_in={} chances={}"
                                + " origin(in/out)={}/{} mod_family(in/out)={}/{}",
                        recipe.getId(), summariseStacks(facts.outputs()), summariseStacks(facts.inputs()),
                        facts.nonItemInput(), facts.chanceDeclared(),
                        facts.inputOrigin(), facts.outputOrigin(),
                        facts.modInputCount(), facts.modOutputCount());
            }
        }
        for (Map.Entry<String, int[]> entry : shapeByNamespace.entrySet()) {
            int[] shape = entry.getValue();
            BotLog.info("[MachineProbe] 未登记形状 ns={} types={} sampled={} out_readable={} item_readable={}"
                            + " non_item_input={} chance_declared={}",
                    entry.getKey(), shape[0], shape[1], shape[2], shape[3], shape[4], shape[5]);
        }
        BotLog.info("[MachineProbe] 未登记承重（原版路径**唯一**读得出）in={}/{} out={}/{} ⇒ "
                        + "旧读取器（只有名族）会读出 {} 条空产出",
                unregisteredVanillaOnlyIn, unregisteredSampled,
                unregisteredVanillaOnlyOut, unregisteredSampled, unregisteredVanillaOnlyOut);

        // ==================== T3 步骤 C / M-4：未登记模组产物的**查询层判决** ====================
        // 问题（勘察 §D M-4）：一个"只有某未登记机器能产出"的物品，查询层报 `NO_RECIPE`（**不诚实**：
        // 它明明做得出来）还是 `MACHINE_RECIPE_UNSUPPORTED`（诚实：只由我们不支持的机器类型产出）？
        // 这三条分支的实际判据（`RecipeQuery`）：读出名族/原版产出 ⇒ `MACHINE_ROUTE`；读不出产出但
        // 原版 `getResultItem` 命中 ⇒ `MACHINE_RECIPE_UNSUPPORTED`；**两者都不命中 ⇒ `NO_RECIPE`**。
        // 与已登记侧的自检**完全分开计数**（`query_probed`/`query_reachable`/`query_machine_route` 一个不动）。
        int unregisteredQueryProbed = 0;
        int unregisteredQueryReachable = 0;
        int unregisteredQueryNoRecipe = 0;
        Map<String, Integer> unregisteredVerdicts = new java.util.TreeMap<>();
        for (Map.Entry<String, java.util.TreeSet<String>> entry : unregisteredOutputsByNamespace.entrySet()) {
            for (String itemId : entry.getValue().stream().sorted().limit(SELF_CHECK_ITEMS).toList()) {
                net.minecraft.world.item.Item item =
                        BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(itemId));
                if (item == net.minecraft.world.item.Items.AIR) {
                    continue;
                }
                unregisteredQueryProbed++;
                var result = com.dddgn.alice.task.craft.RecipeQuery.query(server, bot, item, 1);
                unregisteredVerdicts.merge(result.verdict().name(), 1, Integer::sum);
                if (result.verdict() == com.dddgn.alice.task.craft.RecipeQuery.Verdict.NO_RECIPE) {
                    unregisteredQueryNoRecipe++;
                } else {
                    unregisteredQueryReachable++;
                }
                BotLog.info("[MachineProbe] 未登记查询 ns={} item={} verdict={} {}", entry.getKey(), itemId,
                        result.verdict(), result.describe());
            }
        }

        int pending = WorldModLedger.pendingForOwner(server, bot.getUUID()).size();
        // ⭐ `Z4`：探针是只读的 ⇒ 判据要能看见**真实写入次数**（账本口径在野外是空集）
        int writes = com.dddgn.alice.action.WriteBudget.writeCount(bot);
        if (pending != 0 || writes != 0) {
            failed = true;
        }
        BotLog.info("[MachineProbe] 只读自证 pendingTemporary={} {}", pending,
                com.dddgn.alice.action.WriteBudget.population(bot));
        StringBuilder summary = new StringBuilder();
        summary.append("namespaces=").append(namespaces)
                .append(" types=").append(types)
                .append(" type_recipes=").append(typeTotal)
                .append(" readable_total=").append(readable)
                .append(" skipped_total=").append(skipped)
                .append(" samples_per_type=").append(SAMPLES_PER_TYPE)
                .append(" samples=").append(samples)
                .append(" unreadable_via_vanilla=").append(unreadableViaVanilla)
                .append(" recipe_order_hash=").append(Long.toHexString(recipeOrderHash))
                .append(" upstream_readable=").append(upstreamReadable)
                .append(" machine_output_not_item=").append(machineOutputNotItem)
                .append(" input_readable=").append(inputReadable)
                .append(" chance_declared=").append(chanceDeclared)
                .append(" vanilla_input=").append(vanillaInput)
                .append(" vanilla_output=").append(vanillaOutput)
                .append(" divergent=").append(divergent)
                .append(" read_notes=").append(readNotes.size())
                .append(readNotes.isEmpty() ? "" : " read_notes_sample=" + limitNotes(readNotes))
                .append(" query_probed=").append(queryProbed)
                .append(" query_reachable=").append(queryReachable)
                .append(" query_machine_route=").append(machineRouteOk)
                .append(" machine_map_rows=").append(rowCount)
                .append(" with_site_confirmed=").append(withSiteConfirmed.size())
                .append(" shared_site=").append(sharedSite)
                .append(" with_site_unobserved=").append(withSiteUnobserved)
                .append(" no_site=").append(noSite)
                .append(" unmapped_total=").append(unmapped.size())
                .append(" unmapped=").append(bounded(unmapped))
                .append(" unregistered_ns=").append(describeUnregistered(typesByNamespace, adopted, recipesByNamespace))
                .append(" unregistered_types=").append(unregisteredTypes)
                .append(" unregistered_sampled=").append(unregisteredSampled)
                .append(" unregistered_shape=").append(describeShape(shapeByNamespace))
                .append(" unregistered_vanilla_only_in=").append(unregisteredVanillaOnlyIn)
                .append(" unregistered_vanilla_only_out=").append(unregisteredVanillaOnlyOut)
                .append(" unregistered_query_probed=").append(unregisteredQueryProbed)
                .append(" unregistered_query_reachable=").append(unregisteredQueryReachable)
                .append(" unregistered_query_no_recipe=").append(unregisteredQueryNoRecipe)
                .append(" unregistered_verdicts=").append(unregisteredVerdicts)
                .append(" unregistered_notes=").append(unregisteredNotes.size())
                .append(unregisteredNotes.isEmpty() ? "" : " unregistered_notes_sample=" + bounded(unregisteredNotes.stream().toList()))
                .append(" row_block_missing=").append(rowBlockMissing)
                .append(" no_writes=").append(pending == 0)
                .append(" verdict=").append(failed ? "FAIL" : (types == 0 ? "SKIP" : "PASS"));
        BotLog.info("[MachineProbe] SUMMARY {}", summary);
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[MachineProbe] " + summary));
        }
    }

    /**
     * 留痕**原样带出但限长**：`read_notes` 是"读不懂"的证据，不是判据 ⇒ 截断到常数条，
     * 避免一条退化配方（例如某类型全类调用失败）把 SUMMARY 撑爆。
     */
    private static String limitNotes(java.util.Collection<String> notes) {
        return notes.stream().limit(MAX_NOTES_IN_SUMMARY)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new))
                .toString();
    }

    private static final int MAX_NOTES_IN_SUMMARY = 8;

    /** 自证式查询抽查几个物品（`machineOutputs` 按 id 排序后取前 N ⇒ 确定性）。 */
    private static final int SELF_CHECK_ITEMS = 3;

    /** `unmapped` 在日志/SUMMARY 里最多原样带出多少个（完整清单由**逐命名空间**的 warn 行承载）。 */
    private static final int MAX_UNMAPPED_IN_SUMMARY = 24;

    /** 限长但**不隐藏规模**：截断处带上真实总数。 */
    private static String bounded(List<String> values) {
        if (values.size() <= MAX_UNMAPPED_IN_SUMMARY) {
            return values.toString();
        }
        return values.subList(0, MAX_UNMAPPED_IN_SUMMARY) + " …(共 " + values.size() + ")";
    }

    /** `[ns{t=类型,s=抽样,out=产出可读,item=物品可读,nonitem=非物品输入,chance=声明概率}, …]`。 */
    private static String describeShape(Map<String, int[]> shapeByNamespace) {
        StringBuilder sb = new StringBuilder("[");
        for (Map.Entry<String, int[]> entry : shapeByNamespace.entrySet()) {
            int[] shape = entry.getValue();
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append("{t=").append(shape[0]).append(",s=").append(shape[1])
                    .append(",out=").append(shape[2]).append(",item=").append(shape[3])
                    .append(",nonitem=").append(shape[4]).append(",chance=").append(shape[5]).append('}');
        }
        return sb.append(']').toString();
    }

    /** 物品栈列表的紧凑摘要（**只用于留痕，不用于判据**；空列表 ⇒ `-`）。 */
    private static String summariseStacks(List<net.minecraft.world.item.ItemStack> stacks) {
        if (stacks.isEmpty()) {
            return "-";
        }
        return stacks.stream().limit(3)
                .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()) + "x" + stack.getCount())
                .collect(java.util.stream.Collectors.joining(","));
    }

    /** `[ns=types,…]`：表里 0 行的命名空间各有多少个机器类型（**接下一个模组的第一份读数**）。 */
    private static String describeUnregistered(Map<String, Integer> typesByNamespace,
                                              java.util.Set<String> adopted,
                                              Map<String, Integer> recipesByNamespace) {
        StringBuilder sb = new StringBuilder("[");
        for (Map.Entry<String, Integer> entry : typesByNamespace.entrySet()) {
            if (adopted.contains(entry.getKey())) {
                continue;
            }
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue())
                    .append("(rows=0,recipes=").append(recipesByNamespace.getOrDefault(entry.getKey(), 0))
                    .append(')');
        }
        return sb.append(']').toString();
    }
}
