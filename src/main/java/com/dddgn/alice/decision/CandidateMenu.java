package com.dddgn.alice.decision;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.job.lumber.LumberRegionState;
import com.dddgn.alice.job.lumber.TreeScanner;
import com.dddgn.alice.log.BotLog;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * **候选菜单**（S2 选择层 / D-139）：把"能做什么"变成**服务端算好的有界选项**，LLM 只负责挑一个。
 *
 * <p>为什么必须有它：在此之前动作里的 `center` **只能是 bot 当前位置**，于是 LLM 选 `lumber` 时
 * 大概率选到一片没树的地方 —— 实测 `[Job] launch kind=LUMBER` 后 1 tick 就
 * `no_reachable_candidate`。**不是它笨，是我们没给菜单**（D-135 附注四）。
 *
 * <p>两条铁律（`DECISION_LAYER_DESIGN.md` §2）：**选项由确定性层生成，LLM 只负责选择**。
 * 菜单里的每一项都带**服务端算出的事实**（位置/距离/数量/来源），LLM 只能引用 `id`；
 * 引用不在菜单里的 id ⇒ **拒绝**（不猜、不换算坐标）。
 *
 * <p>有界性：每类候选最多 {@link #MAX_PER_KIND} 项、总项数最多 {@link #MAX_TOTAL} 项
 * （对齐 mc_aiplayer 的"感知有界"做法）。
 */
public final class CandidateMenu {

    public static final int MAX_PER_KIND = 5;
    public static final int MAX_TOTAL = 12;
    /** 树/掉落物的扫描半径。 */
    public static final int SCAN_RADIUS = 24;
    /**
     * **矿物候选的扫描半径**（比 {@link #SCAN_RADIUS} 小）：菜单是"**有界感知**"，不是作业搜索。
     * 11 个矿石目标 × (2r+1)³ ⇒ r=12 时约 17 万次方块读取，与树的 24 格扫描（≈12 万）同量级。
     */
    public static final int MINE_SCAN_RADIUS = 12;
    /** **可做清单**最多列几项（超出**如实标 truncated**，不静默隐藏）。 */
    public static final int MAX_CRAFTABLE = 40;
    /** 配方扫描上限（整合包配方表很大；超限**如实标 truncated**）。 */
    public static final int MAX_RECIPE_SCAN = 8000;

    /** 菜单项（{@code id} 是 LLM 唯一被允许引用的东西）。 */
    public record Entry(String id, String kind, String label, BlockPos pos, int amount, String extra) {
    }

    private final List<Entry> entries;
    /** **可做清单**：产物 id → 当前选中的工作站能不能做它（只读事实，供 {@code GoalAction.Craft} 校验）。 */
    private final Map<String, Boolean> craftable;
    private final boolean craftableTruncated;

    private CandidateMenu(List<Entry> entries) {
        this(entries, Map.of(), false);
    }

    private CandidateMenu(List<Entry> entries, Map<String, Boolean> craftable, boolean craftableTruncated) {
        this.entries = List.copyOf(entries);
        this.craftable = Map.copyOf(craftable);
        this.craftableTruncated = craftableTruncated;
    }

    /** 可做清单（产物 id → 当前站点能否做）。 */
    public Map<String, Boolean> craftable() {
        return craftable;
    }

    /** 可做清单是否因**扫描/列示上限**被截断（诚实标注：被截断 ≠ 做不到）。 */
    public boolean craftableTruncated() {
        return craftableTruncated;
    }

    public List<Entry> entries() {
        return entries;
    }

    public Entry find(String id) {
        if (id == null) {
            return null;
        }
        for (Entry entry : entries) {
            if (entry.id().equals(id)) {
                return entry;
            }
        }
        return null;
    }

    public JsonArray toJson() {
        JsonArray array = new JsonArray();
        for (Entry entry : entries) {
            JsonObject node = new JsonObject();
            node.addProperty("id", entry.id());
            node.addProperty("kind", entry.kind());
            node.addProperty("label", entry.label());
            if (entry.pos() != null) {
                node.addProperty("pos", entry.pos().toShortString());
            }
            if (entry.amount() > 0) {
                node.addProperty("amount", entry.amount());
            }
            if (entry.extra() != null && !entry.extra().isBlank()) {
                node.addProperty("extra", entry.extra());
            }
            array.add(node);
        }
        return array;
    }

    public String describe() {
        StringBuilder builder = new StringBuilder();
        for (Entry entry : entries) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(entry.id()).append('(').append(entry.label()).append(')');
        }
        return builder.toString();
    }

    /** 从**服务端事实**生成菜单（只读；不改世界、不派任务）。 */
    public static CandidateMenu build(BotPlayer bot) {
        final List<Entry> entries = new ArrayList<>();
        final BlockPos botPos = bot.blockPosition();

        // ① 树 —— 伐木候选。**必须复用 Job 自己的候选源**（`LumberCandidateSource`），
        // 而不是裸 `TreeScanner`：否则会把 Job 注定拒绝的树（如 2×2 高大云杉 `trunk_too_tall`）
        // 递给 LLM —— 2026-09-12 实测就发生了（菜单给了 `tree@22,64,218`，Job 报 `no_reachable_candidate`）。
        // 这就是项目既有规矩："夹具/菜单的候选必须复用规划器 provider（可规划即可执行）"。
        var lumberSource = new com.dddgn.alice.job.lumber.LumberCandidateSource();
        var probe = com.dddgn.alice.job.GoalSpec.harvestUnits(botPos, SCAN_RADIUS, 1, 3600);
        var candidates = lumberSource.candidates(bot, probe);
        candidates.viable().stream()
                .sorted(java.util.Comparator.comparingDouble(c -> c.anchor().distSqr(botPos)))
                .limit(MAX_PER_KIND)
                .forEach(c -> entries.add(new Entry(
                        c.id(),   // 与 Job 决策日志同一个 id 口径（`tree@x,y,z`）
                        "lumber", "可行树 距离"
                        + String.format(java.util.Locale.ROOT, "%.1f",
                        Math.sqrt(c.anchor().distSqr(botPos))),
                        c.anchor(), 1,
                        "species=" + c.feature("species") + " height=" + c.feature("height")
                                + " logs=" + c.feature("logs"))));

        if (!candidates.rejected().isEmpty()) {
            BotLog.info("[Goal] candidate_menu rejected(不可做)= {}", candidates.rejected());
        }

        // ② 我方掉落的簇（**只列我方登记过的**；FOREIGN/授权区要等 S3.5）—— 捡拾候选
        var session = BotManager.sessionOf(bot);
        if (session != null) {
            List<ItemEntity> ours = new ArrayList<>();
            for (ItemEntity item : session.scope().liveDrops()) {
                if (item.distanceToSqr(bot) <= (double) SCAN_RADIUS * SCAN_RADIUS) {
                    ours.add(item);
                }
            }
            java.util.Map<BlockPos, Integer> clusters = new java.util.LinkedHashMap<>();
            for (ItemEntity item : ours) {
                BlockPos anchor = item.blockPosition();
                clusters.merge(anchor, item.getItem().getCount(), Integer::sum);
            }
            clusters.entrySet().stream()
                    .sorted(java.util.Comparator.comparingDouble(e -> e.getKey().distSqr(botPos)))
                    .limit(MAX_PER_KIND)
                    .forEach(e -> entries.add(new Entry(
                            "drops@" + e.getKey().getX() + "," + e.getKey().getY() + "," + e.getKey().getZ(),
                            "collect", "我方掉落物 距离"
                            + String.format(java.util.Locale.ROOT, "%.1f",
                            Math.sqrt(e.getKey().distSqr(botPos))),
                            e.getKey(), e.getValue(), "provenance=OURS_DIRECT")));
        }

        // ③ 矿物 —— 挖掘候选（**M1 / G1**）。与①**同一纪律**：复用 Job 自己的候选源
        //    （`MineCandidateSource`），所以菜单里出现的矿 Job 一定选得中
        //    （`block@x,y,z` 就是 `MineJob` 决策日志的 id 口径）。
        //    矿石清单**只能**来自 `MiningBudget.COMMON_ORE_TAGS` / `RARE_ORES`（J-6：不许长出第二份）。
        //    扫描半径比树小（`MINE_SCAN_RADIUS`）：菜单是**有界感知**，不是作业搜索；
        //    条目里的 `block=` 是**确定性层算出的方块 id**，动作解析只许用它，不许 LLM 自己写。
        var mineProbe = com.dddgn.alice.job.GoalSpec.mineBlocks(botPos, MINE_SCAN_RADIUS, 1, 3600);
        List<com.dddgn.alice.job.Candidate> mineCandidates = new ArrayList<>();
        // **队列第②项（2026-09-17）**：原先对 11 个目标**各扫一整遍世界**（11 × (2r+1)³ 次读取；
        // 勘测 11 实测"菜单 30~90ms"的来源）⇒ 现在**一遍扫描、结果分发**，候选集逐字不变。
        List<com.dddgn.alice.job.mine.MineCandidateSource.Target> mineTargets = mineScanTargets();
        com.dddgn.alice.job.mine.MineCandidateSource.resetBlockReads();   // 判据：数**真实**读取
        com.dddgn.alice.job.mine.MineCandidateSource.resetUnscanned();    // 判据：数**未加载而跳过**的格
        var mineScan = com.dddgn.alice.job.mine.MineCandidateSource.candidatesForTargets(
                bot, mineProbe, mineTargets, MINE_SCAN_RADIUS);
        for (var set : mineScan.sets()) {
            mineCandidates.addAll(set.viable());
        }
        // 读取计数单独一行：**不碰**上面那行既有日志（`mine=N` 是夹具逐字断言的口径）
        BotLog.info("[Goal] candidate_menu_scan one_pass=true targets={} block_reads={} unscanned={}",
                mineTargets.size(), mineScan.blockReads(), mineScan.unscanned());
        CandidateMenu.lastMineScanBlockReads = (int) com.dddgn.alice.job.mine.MineCandidateSource.blockReads();
        CandidateMenu.lastMineScanUnscanned = (int) com.dddgn.alice.job.mine.MineCandidateSource.unscanned();
        CandidateMenu.lastMineScanTargets = mineTargets.size();
        mineCandidates.stream()
                .sorted(java.util.Comparator.comparingDouble(c -> c.anchor().distSqr(botPos)))
                .limit(MAX_PER_KIND)
                .forEach(c -> entries.add(new Entry(
                        c.id(),
                        "mine", "可挖 " + shortId(c.feature("block")) + " 距离" + c.feature("d"),
                        c.anchor(), 1,
                        "block=" + c.feature("block") + " y=" + c.feature("y"))));
        BotLog.info("[Goal] candidate_menu mine={} (扫描半径={} 目标集={})",
                mineCandidates.size(), MINE_SCAN_RADIUS, mineTargets.size());

        // ④ 已保存的可持续伐木区 —— 区域型候选（**只能用已存在的区域**，LLM 不能发明）
        LumberRegionState regionState = LumberRegionState.get(bot.getServer());
        var region = regionState.region(bot.getUUID());
        if (region != null) {
            entries.add(new Entry("region:saved", "region_lumber",
                    "已保存区域 " + region.describe(), region.center(), 0,
                    "baseline=" + regionState.baselineTrees(bot.getUUID())
                            + " mySaplings=" + regionState.mySaplingCount(bot.getUUID())));
        }

        // ⑤ **可做清单**（A5 / D-199）：以"当前背包里**实际持有**的材料"为准，用**只读**配方扫描
        //    算出"现在就能做出来的东西"。事实口径（**不猜**）：产物 id + 配方类型映射出的工作站 +
        //    **当前玩家选中的站点能不能做**（`can_use=`）。扫描/列示都有上限，超限写 `truncated`。
        //    为什么单独一组：它在语义上不是"位置候选"，不该挤掉树/掉落物的 12 项预算
        //    ⇒ 有界性 = `MAX_TOTAL`（位置类）+ `MAX_CRAFTABLE`（可做清单）。
        List<Entry> craftableEntries = new ArrayList<>();
        Map<String, Boolean> craftable = new java.util.LinkedHashMap<>();
        boolean craftableTruncated = craftableScan(bot, craftableEntries, craftable);
        entries.addAll(craftableEntries);

        List<Entry> bounded = entries.size() > MAX_TOTAL
                ? new ArrayList<>(entries.subList(0, MAX_TOTAL)) : entries;
        // 可做清单**不参与**位置类预算（否则 40 项会把树/掉落物挤掉）
        if (!craftable.isEmpty()) {
            bounded = new ArrayList<>(bounded);
            for (Entry entry : craftableEntries) {
                if (!bounded.contains(entry)) {
                    bounded.add(entry);
                }
            }
        }
        CandidateMenu menu = new CandidateMenu(bounded, craftable, craftableTruncated);
        BotLog.info("[Goal] candidate_menu entries={} craftable={}{} {}",
                menu.entries().size(), menu.craftable().size(),
                menu.craftableTruncated() ? "(truncated)" : "", menu.describe());
        return menu;
    }

    /** 从 `extra`（空格分隔的 `k=v`）里取一个键；没有 ⇒ null。条目字段都由**确定性层**算出。 */
    public static String extraValue(Entry entry, String key) {
        if (entry == null || entry.extra() == null) {
            return null;
        }
        for (String token : entry.extra().split("\\s+")) {
            int eq = token.indexOf('=');
            if (eq > 0 && token.substring(0, eq).equals(key)) {
                return token.substring(eq + 1);
            }
        }
        return null;
    }

    /**
     * 菜单的**矿物扫描目标集**：由 `MiningBudget` 的**唯一两份**矿石定义派生（J-6 不许第二份清单）。
     * 常见矿石按**标签**扫（覆盖模组同类矿石），稀有矿石按**具体方块**扫（口径精确）。
     */
    /** **夹具只读**（队列第②项的判据）：上一次建菜单时，矿扫描实际发生的方块读取次数与目标数。 */
    private static volatile int lastMineScanBlockReads = -1;
    private static volatile int lastMineScanUnscanned = -1;
    private static volatile int lastMineScanTargets = -1;

    /** **夹具只读**：上一次矿扫描的方块读取次数（未建过菜单 ⇒ -1）。 */
    public static int lastMineScanBlockReads() {
        return lastMineScanBlockReads;
    }

    /** **夹具只读**（D-329 ① 判据）：上一次矿扫描里**因未加载而跳过**的格数。 */
    public static int lastMineScanUnscanned() {
        return lastMineScanUnscanned;
    }

    /** **夹具只读**：上一次矿扫描的目标数。 */
    public static int lastMineScanTargets() {
        return lastMineScanTargets;
    }

    /** **夹具只读**：矿扫描半径（判据要算"单遍体积"）。 */
    public static int mineScanRadius() {
        return MINE_SCAN_RADIUS;
    }

    private static List<com.dddgn.alice.job.mine.MineCandidateSource.Target> mineScanTargets() {
        List<com.dddgn.alice.job.mine.MineCandidateSource.Target> targets = new ArrayList<>();
        for (var tag : com.dddgn.alice.task.mining.MiningBudget.COMMON_ORE_TAGS) {
            targets.add(com.dddgn.alice.job.mine.MineCandidateSource.Target.ofTag(tag));
        }
        for (var block : com.dddgn.alice.task.mining.MiningBudget.RARE_ORES) {
            targets.add(com.dddgn.alice.job.mine.MineCandidateSource.Target.ofBlock(block));
        }
        return targets;
    }

    /** 标签里显示用的短 id（去掉 `minecraft:` 前缀；只影响可读性，不参与任何判定）。 */
    private static String shortId(String blockId) {
        if (blockId == null) {
            return "?";
        }
        int colon = blockId.indexOf(':');
        return colon < 0 ? blockId : blockId.substring(colon + 1);
    }

    /**
     * **可做清单的确定性扫描**（只读；A5）。
     *
     * <p>口径（每条都是客观事实，不做"大概能做"）：
     * <ol>
     *   <li>产物来自**运行时的原版配方体系**（`RecipeManager`）；类型不在
     *       {@link RecipeDump#stationFor} 白名单里 ⇒ **如实跳过**（机器配方不在其中，不猜语义）；</li>
     *   <li>材料**当前背包里就持有**（按 `Ingredient` 逐个"至少有一个匹配物品"；
     *       这是**保守**口径：宁可漏报也不谎报"能做"）；</li>
     *   <li>`can_use` = **当前选中的工作站**做不做得了它（站点由**玩家**切换，不自动选优）：
     *       随身=只做 2×2 放得下的；工作台/升级页签=3×3；熔炼页签=烧炼；其余（高炉/烟熏/切石/锻造）
     *       我们**还没有可驱动的站点** ⇒ 如实 `false`（列出来但明确"当前站点做不了"）。</li>
     * </ol>
     *
     * @return 是否因扫描上限被截断
     */
    private static boolean craftableScan(BotPlayer bot, List<Entry> out, Map<String, Boolean> craftable) {
        var server = bot.getServer();
        if (server == null) {
            return false;
        }
        var access = server.registryAccess();
        String selected = com.dddgn.alice.task.craft.CraftStation.selected(bot);
        int scanned = 0;
        boolean truncated = false;
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (net.minecraft.world.item.crafting.Recipe<?> recipe : server.getRecipeManager().getRecipes()) {
            if (++scanned > MAX_RECIPE_SCAN) {
                truncated = true;
                break;
            }
            if (craftable.size() >= MAX_CRAFTABLE) {
                truncated = true;
                break;
            }
            net.minecraft.world.item.ItemStack result = recipe.getResultItem(access);
            if (result.isEmpty()) {
                continue;
            }
            String typeId = net.minecraft.core.registries.BuiltInRegistries.RECIPE_TYPE
                    .getKey(recipe.getType()).toString();
            String station = RecipeDump.stationFor(typeId);
            if (station == null) {
                continue;   // 未支持的配方类型 ⇒ 如实跳过（不猜）
            }
            if (!ingredientsHeld(bot, recipe)) {
                continue;
            }
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(result.getItem()).toString();
            if (!seen.add(id)) {
                continue;
            }
            boolean canUse = stationCanDo(selected, station, recipe);
            craftable.put(id, canUse);
            // `id` **就是物品 id**：LLM 直接把它抄进 `{"action":"craft","item":…}`，
            // 少一层"craft: 前缀"的翻译（少一次犯错的机会），也让 prompt 更省字。
            out.add(new Entry(id, "craftable", result.getHoverName().getString()
                    + " x" + result.getCount(),
                    botPos(bot), result.getCount(),
                    "needs=" + station + " can_use=" + canUse));
        }
        return truncated;
    }

    private static BlockPos botPos(BotPlayer bot) {
        return bot.blockPosition().immutable();
    }

    /** 材料是否**当前就持有**（保守：逐个 `Ingredient` 要求"背包里至少有一个匹配物品"）。 */
    private static boolean ingredientsHeld(BotPlayer bot, net.minecraft.world.item.crafting.Recipe<?> recipe) {
        var inventory = bot.getInventory();
        for (net.minecraft.world.item.crafting.Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            boolean found = false;
            for (int i = 0; i < inventory.getContainerSize() && !found; i++) {
                net.minecraft.world.item.ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty() && ingredient.test(stack)) {
                    found = true;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /** 当前选中的站点能不能做（**保守**：只认已经验收过的能力组合）。 */
    private static boolean stationCanDo(String selected, String station,
                                        net.minecraft.world.item.crafting.Recipe<?> recipe) {
        if (recipe instanceof net.minecraft.world.item.crafting.CraftingRecipe crafting
                && "crafting_table".equals(station)) {
            boolean fits2x2 = crafting.canCraftInDimensions(2, 2);
            boolean fits3x3 = crafting.canCraftInDimensions(3, 3);
            return switch (selected) {
                case "table", "upgradetab" -> fits3x3;
                // 随身只做 2×2；`auto` 也按"随身一定能做"算 —— **不替玩家自动选优**
                default -> fits2x2;
            };
        }
        if ("furnace".equals(station)) {
            // 目前只有"熔炼页签"这一条被验收过的烧炼站点；方块型熔炉尚未进站点模型 ⇒ 其余如实 false
            return "cookingtab".equals(selected);
        }
        return false;
    }
}
