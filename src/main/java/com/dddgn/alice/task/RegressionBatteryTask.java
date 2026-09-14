package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.job.GoalSpec;
import com.dddgn.alice.job.lumber.LumberCandidateSource;
import com.dddgn.alice.job.lumber.LumberJob;
import com.dddgn.alice.job.mine.MineCandidateSource;
import com.dddgn.alice.job.mine.MineJob;
import com.dddgn.alice.job.policy.NearestPolicy;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * **串联回归电池**（{@code alice:regression_battery}，D-122）：一次右键跑完"改了生产任务必须复跑"的常用回归。
 *
 * <p>为什么要有它（项目测试规矩）："多个检查项合并为一个自检任务，一次右键跑完，输出
 * {@code SUMMARY key=VALUE}"；把 25 个入口拆成让用户点 25 次是**反模式**。本电池把
 * {@code docs/TESTING_GUIDE.md §1.7} 的清单固化下来，一步一项、各自复位、互不干扰。
 *
 * <p>每项独立：进入该项前先跑它需要的场景函数（有的项目已自带复位，就留空）、发齐夹具工具
 * （D-119 起生产任务不发工具）、把 bot 放到该场景起点，然后 tick 到终态；**任一项失败不中断**
 * （一趟看全），最后一行汇总。
 *
 * <p>输出：`[Regression] SUMMARY clear_retry=PASS … pathing=PASS K4=OK(…) (26/26) ticks=… → PASS`。
 */
public final class RegressionBatteryTask implements Task {

    /** 总兜底预算（各项预算之和 + 余量）；典型实跑约 5000~7500 tick（4~6 分钟，含决策层事件窗口 300 tick）。 */
    private static final int TOTAL_BUDGET_TICKS = 21000;

    /** 与 `MineJobItem` 对齐的挖掘 Job 配额。 */
    private static final int MINE_QUOTA = 4;

    /**
     * @param doneWhen 非 null = **常驻任务**（如区域型 Job 会一直巡查等生长）：满足该谓词就判本步通过，
     *                 不必等它自己结束（否则只能靠预算超时，把"本来就该常驻"误报成失败）。
     *                 为 null = 按常规"跑完看终态"。
     */
    private record Step(String name, List<String> scenes, Runnable provision,
                        Supplier<Task> factory, int budgetTicks,
                        java.util.function.Predicate<Task> doneWhen,
                        java.util.function.Predicate<Task> skipWhen) {
    }

    /** 常规步骤（跑完看终态）。 */
    private static Step step(String name, List<String> scenes, Runnable provision,
                             Supplier<Task> factory, int budgetTicks) {
        return new Step(name, scenes, provision, factory, budgetTicks, null, null);
    }

    /**
     * **可跳过**的步骤：任务失败但 `skipWhen` 成立时记 `SKIP`（**不算失败**）。
     *
     * <p>用途：**依赖模组**的站点/地形在没装模组的客户端上必然不可用 —— 那种"环境不具备"
     * 不该把整轮电池判红（同时 `SKIP` 会明写在 SUMMARY 的字段列表里，不是静默放过）。
     */
    private static Step stepSkippable(String name, List<String> scenes, Runnable provision,
                                      Supplier<Task> factory, int budgetTicks,
                                      java.util.function.Predicate<Task> skipWhen) {
        return new Step(name, scenes, provision, factory, budgetTicks, null, skipWhen);
    }

    /**
     * **回归项分档**（D-197，用户 2026-09-13 要求"电池只测必要基础项 + 当前主线项，由 AI 管理"）：
     * <ul>
     *   <li>{@link #BASELINE}：**必要基础** —— 坏了就不能信任 bot 的任何动作（移动内核 / 写入闸门 /
     *       破坏性路径 / 生产 Job 闭环）；</li>
     *   <li>{@link #MAIN}：**当前主线** —— 这一轮正在做的能力（今天 = 阶段 3-A 工作站/熔炼）；</li>
     *   <li>{@link #EXTRA}：**已验收、与主线无关、或耗时/需长期观察**的项 —— 默认**不跑**，
     *       需要时用 `/alice battery full` 全量跑。</li>
     * </ul>
     * 维护规则见 {@code docs/BATTERY_CURATION.md}：**每加一个场景/换一次主线，都要同步更新归属表与文档**。
     */
    public enum Profile { BASELINE, MAIN, EXTRA }

    /** 运行档位：默认只跑 BASELINE + MAIN。 */
    public enum Mode { CORE, FULL }

    /**
     * **CORE 档实跑项数**（= BASELINE + MAIN）：用户可见文案一律**现算**，**禁止写死**。
     *
     * <p>台账⑦（2026-09-14）：`RegressionBatteryItem` 的启动文案与 `AliceItems` 注释里写死过"26 项"，
     * 加到 29 项后就变成错话（实测 SUMMARY 打的是 `(29/29)`）。项数的唯一出处 = {@link #CURATION}。
     */
    public static int coreStepCount() {
        int count = 0;
        for (Profile profile : CURATION.values()) {
            if (profile != Profile.EXTRA) {
                count++;
            }
        }
        return count;
    }

    /**
     * **归属表（唯一配置入口）**：改电池配置只改这里。
     *
     * <p>刻意用"按名字的清单"而不是给每个步骤加参数：① 一处可见、便于 review；
     * ② 构造时会**自校验**（有步骤没归属 / 有归属没步骤 ⇒ 直接判红），防止"悄悄漏测"。
     */
    private static final Map<String, Profile> CURATION = Map.ofEntries(
            // ---- BASELINE：必要基础（14）----
            Map.entry("pathing", Profile.BASELINE),
            Map.entry("write_budget", Profile.BASELINE),
            Map.entry("mine_regression", Profile.BASELINE),
            Map.entry("mine_job", Profile.BASELINE),
            Map.entry("lumber_job", Profile.BASELINE),
            Map.entry("transfer", Profile.BASELINE),
            Map.entry("clear_guard", Profile.BASELINE),
            Map.entry("clear_retry", Profile.BASELINE),
            Map.entry("scaffold", Profile.BASELINE),
            Map.entry("partial_search", Profile.BASELINE),
            Map.entry("capability_gate", Profile.BASELINE),
            Map.entry("tool_supply", Profile.BASELINE),
            Map.entry("recoverability", Profile.BASELINE),
            // D-207 ①：写入集中策略表（区域×任务 → 回收义务/移动授权）——含**负例**（越权必须被拒）
            Map.entry("write_policy", Profile.BASELINE),
            // ---- MAIN：阶段 3-A 收口后的最小烟测集（4）----
            // 口径（D-201）：每一类"只此一步覆盖"的机制各留一步 + 查询层最便宜一步；
            // A2/A3/A3b/C/装配/发现器探针等同机制夹具退 FULL（机制不丢，默认时长下降）
            Map.entry("craft_check", Profile.MAIN),
            Map.entry("craft_furnace", Profile.MAIN),
            Map.entry("craft_cooking", Profile.MAIN),
            Map.entry("craft_goal", Profile.MAIN),
            Map.entry("machine_route", Profile.MAIN),
            Map.entry("machine_station", Profile.MAIN),
            // 阶段 3-B / S4（D-213）：单机最小闭环（真的把机器跑起来一次）——它**会写容器**，
            // 是 MAIN 里唯一带写入的一步；模组不在 ⇒ SKIP（同 machine_route/machine_station）
            Map.entry("machine_cycle", Profile.MAIN),
            // 阶段 3-B / (c) 增量 2（D-217）：**机器路线的生产路径**（CraftJob 真的驱动一台机器）。
            // 与 machine_cycle 同一份闭环实现、不同入口；也会写容器 ⇒ 模组不在 ⇒ SKIP。
            Map.entry("craft_machine", Profile.MAIN),
            // 2026-09-13 D-201 附注一：**回退整理**——撤走后 CORE 三项变红（缺隐含前置），
            // 而这些步骤在 FULL 里是绿的 ⇒ 先恢复绿基线，等"显式自证前提"做完再**逐条**撤（每条复跑一次）
            Map.entry("craft_action", Profile.MAIN),
            Map.entry("craft_table", Profile.MAIN),
            Map.entry("craft_station", Profile.MAIN),
            Map.entry("craft_probe_inventory", Profile.MAIN),
            Map.entry("craft_probe_table", Profile.MAIN),
            Map.entry("craft_probe_upgradetab", Profile.MAIN),
            Map.entry("craft_station_provision", Profile.MAIN),
            Map.entry("craft_station_craft", Profile.MAIN),
            // ---- EXTRA：已验收/无关/耗时（10）----
            Map.entry("lumber_failure", Profile.EXTRA),
            Map.entry("region_maintain", Profile.EXTRA),
            Map.entry("decision_contract", Profile.EXTRA),
            Map.entry("decision_trace", Profile.EXTRA),
            Map.entry("llm_contract", Profile.EXTRA),
            Map.entry("permission_gate", Profile.EXTRA),
            Map.entry("pickup_gate", Profile.EXTRA),
            Map.entry("collect_job", Profile.EXTRA),
            Map.entry("recipes_dump", Profile.EXTRA),
            Map.entry("event_thresholds", Profile.EXTRA));

    /** 归属表摘要（`/alice battery list` + 文档用）：按档位分组打印，一眼看清电池里有什么、为什么。 */
    public static List<String> curationSummary() {
        List<String> lines = new ArrayList<>();
        lines.add("回归电池归属表（改配置只改 CURATION；详见 docs/BATTERY_CURATION.md）：");
        for (Profile profile : Profile.values()) {
            List<String> names = new ArrayList<>();
            for (Map.Entry<String, Profile> entry : CURATION.entrySet()) {
                if (entry.getValue() == profile) {
                    names.add(entry.getKey());
                }
            }
            names.sort(String::compareTo);
            lines.add("  " + profile + "（" + names.size() + "）：" + String.join(", ", names));
        }
        lines.add("  跑法：/alice battery core（默认，必要基础+当前主线）| /alice battery full（全量）");
        return lines;
    }

    /** 运行档位（CORE 默认）。 */
    private final Mode mode;

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final ScopeBuffer scope;
    private final List<Step> steps = new ArrayList<>();
    private final Map<String, String> results = new LinkedHashMap<>();
    private final Map<String, String> details = new LinkedHashMap<>();
    /** 归属自校验结果与档位统计（进 SUMMARY，便于"配置漂移"一眼可见）。 */
    private boolean stepsPrepared;
    private String curationError = "";
    private int extraSkipped;
    /** **全量**步骤名（裁剪前快照）：自校验用它，避免"被跳过的档"被误判成 phantom。 */
    private List<String> allStepNames = new ArrayList<>();

    private int index;
    private int ticks;
    private int stepTicks;
    private Task current;
    private boolean stepStarted;
    private final Map<String, Integer> k4Baseline;

    public RegressionBatteryTask(BotPlayer bot, ServerPlayer observer, ScopeBuffer scope) {
        this(bot, observer, scope, Mode.CORE);
    }

    public RegressionBatteryTask(BotPlayer bot, ServerPlayer observer, ScopeBuffer scope, Mode mode) {
        this.mode = mode;
        this.bot = bot;
        this.observer = observer;
        this.scope = scope;
        // K-4 / D-167：记录"谓词不统一"计数的基线 ⇒ 收尾时按**本次电池的增量**断言
        // （进程累计会被电池之前的服务端活动污染，增量才是这次电池所有步骤的真实结果）。
        this.k4Baseline = com.dddgn.alice.pathing.core.search.PathingStats.totalsSnapshot();
        buildSteps();
    }

    @Override
    public String taskName() {
        return "RegressionBattery";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(bot.blockPosition());
    }

    @Override
    public String failureReason() {
        return results.entrySet().stream()
                .filter(e -> !"PASS".equals(e.getValue()))
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    // ==================== 清单 ====================

    private void buildSteps() {
        // 由轻到重：先跑秒级自检，再跑 Job / 长回归，便于"早失败早知道"
        steps.add(step("clear_retry", List.of(), null,
                () -> new ClearRetryCheckTask(bot, scope), 900));
        steps.add(step("write_budget", List.of(), null,
                () -> new WriteBudgetCheckTask(bot, scope), 900));
        steps.add(step("scaffold", List.of(), null,
                () -> new ScaffoldLifecycleTask(bot, scope), 900));
        steps.add(step("clear_guard", List.of(), null,
                () -> new ClearGuardCheckTask(bot, scope), 900));
        steps.add(step("lumber_failure", List.of(), null,
                () -> new LumberFailureCheckTask(bot, scope), 1800));
        steps.add(step("mine_regression", List.of(), null,
                () -> new MineRegressionTask(bot, observer, scope), 3200));
        // 伐木 Job：手动场景（terrain + 手写树）⇒ 电池自己跑场景函数 + 复刻 LumberJobItem 的发料
        steps.add(step("lumber_job",
                List.of("alice_test:lumber_course_terrain", "alice_test:lumber_course_trees"),
                () -> {
                    teleportBot(LumberCourseAnchor.START_FOOT);
                    FixtureToolKit.resetInventory(bot);
                    FixtureToolKit.ensureAxe(bot);
                    FixtureToolKit.ensurePickaxe(bot);
                    FixtureToolKit.ensureHotbarStack(bot, () -> new ItemStack(Items.COBBLESTONE),
                            stack -> stack.is(Items.COBBLESTONE), 12, "cobblestone");
                },
                () -> new LumberJob(bot,
                        GoalSpec.harvestUnits(LumberCourseAnchor.START_FOOT, 16, 4, 3600),
                        scope, new LumberCandidateSource(), new NearestPolicy()),
                1500));
        // 挖掘 Job：同上（ore_course + 复刻 MineJobItem 的发料）
        steps.add(step("mine_job",
                List.of("alice_test:ore_course_terrain"),
                () -> {
                    teleportBot(OreCourseAnchor.START_FOOT);
                    FixtureToolKit.resetInventory(bot);
                    FixtureToolKit.ensurePickaxe(bot);
                },
                () -> new MineJob(bot,
                        GoalSpec.mineBlocks(OreCourseAnchor.START_FOOT,
                                MineCandidateSource.SCAN_RADIUS, MINE_QUOTA, 3600),
                        scope,
                        new MineCandidateSource(MineCandidateSource.Target.ofBlock(
                                net.minecraft.world.level.block.Blocks.IRON_ORE),
                                MineCandidateSource.SCAN_RADIUS),
                        new NearestPolicy()),
                2200));
        // J8 可持续伐木区（MAINTAIN）：同一个伐木场景，但走"巡查 → 砍 → 继续巡查"的区域型 Job
        steps.add(new Step("region_maintain",
                List.of("alice_test:lumber_course_terrain", "alice_test:lumber_course_trees"),
                () -> {
                    teleportBot(LumberCourseAnchor.START_FOOT);
                    FixtureToolKit.resetInventory(bot);
                    FixtureToolKit.ensureAxe(bot);
                    FixtureToolKit.ensurePickaxe(bot);
                    FixtureToolKit.ensureHotbarStack(bot, () -> new ItemStack(Items.COBBLESTONE),
                            stack -> stack.is(Items.COBBLESTONE), 12, "cobblestone");
                    // Slice B：区域欠树要补种 ⇒ 夹具发**选定的那种**树苗（未选则默认橡树苗）
                    var state = com.dddgn.alice.job.lumber.LumberRegionState.get(bot.getServer());
                    if (state.saplingItem(bot.getUUID()) == null) {
                        state.setSaplingItem(bot.getUUID(), "minecraft:oak_sapling");
                    }
                    var saplingId = net.minecraft.resources.ResourceLocation
                            .tryParse(state.saplingItem(bot.getUUID()));
                    var sapling = saplingId == null ? null
                            : net.minecraft.core.registries.BuiltInRegistries.ITEM.get(saplingId);
                    if (sapling != null && sapling != Items.AIR) {
                        FixtureToolKit.ensureHotbarStack(bot, () -> new ItemStack(sapling),
                                stack -> stack.is(sapling), 8, "sapling");
                    }
                },
                () -> new com.dddgn.alice.job.lumber.RegionLumberJob(bot,
                        LumberCourseAnchor.region(),
                        scope, new LumberCandidateSource(), new NearestPolicy(), 20, 8000),
                2000,
                // 常驻任务：砍到 ≥1 棵且补种 ≥1 棵即算本步通过（之后它会继续巡查等苗长大）
                task -> task instanceof com.dddgn.alice.job.lumber.RegionLumberJob region
                        && region.treesChopped() >= 1 && region.plantedSomething(), null));
        // ==================== 决策层判据（基-2 / D-149）====================
        // 契约类断言：纯逻辑、不改世界、不调 LLM ⇒ 便宜且确定，任何改动都跑得到
        steps.add(step("decision_contract",
                List.of("alice_test:lumber_course_terrain", "alice_test:lumber_course_trees"),
                () -> teleportBot(LumberCourseAnchor.START_FOOT),
                () -> new DecisionContractCheckTask(bot, observer), 200));
        // 基-4：决策 trace 落盘 + 跨重启语义（NBT 往返 / 只报一次）
        steps.add(step("decision_trace", List.of(), null,
                () -> new DecisionTraceCheckTask(bot, observer), 200));
        // 基-5：LLM 上抛契约（Job 失败报告 / 产物判定口径 / 结构化拒绝回读）
        // 基-9：工具供给（换更好的 / 没得换如实报 / 不能凭空变出工具）
        // 基-8：能力闸门（MovementCapabilities 真的能拦人：保护区/资源/工具/预算/声明一致性）
        // 阶段 3-A / A1（D-185）：只读配方查询（正例/缺料/3×3/无配方/机器专属 + 背包未变硬断言）
        steps.add(step("craft_check", List.of(), null,
                () -> new CraftCheckTask(bot, observer), 200));
        // 阶段 3-A / A2（D-186）：随身 2×2 合成（真消耗真产物 + 缺料如实失败 + 网格清理）
        steps.add(step("craft_action", List.of(), null,
                () -> new CraftActionCheckTask(bot, observer), 200));
        // 阶段 3-A / A3（D-188）：现成工作台 3×3 合成（找台→走位→开菜单→合成 + 零写入断言）
        steps.add(step("craft_table", List.of("alice_test:craft_table_course"),
                () -> teleportBot(com.dddgn.alice.task.CraftTableCheckTask.START),
                () -> new CraftTableCheckTask(bot, observer), 900));
        // 阶段 3-A / A3b（D-190）：**自放工作站**（第一次真正写世界的合成路径）+ 建拆同权
        // 单独一步：它必须写世界（放置）并走恢复任务拆回，与 A3 的"零写入"断言分开可读
        steps.add(step("craft_station", List.of("alice_test:craft_station_course"),
                () -> teleportBot(com.dddgn.alice.task.CraftTableCheckTask.START),
                () -> new com.dddgn.alice.task.CraftStationCheckTask(bot, observer), 2600));
        // 阶段 3-A / S1-3（D-192）：**通用网格发现**的回归三连 ——
        // ① 随身 2×2（Auto⇒inventory）② 原版工作台 3×3（零模组依赖）③ 模组"升级页签"（不可用则 SKIP）
        steps.add(step("craft_probe_inventory", List.of(),
                () -> {
                    teleportBot(com.dddgn.alice.task.CraftGridProbeTask.START);
                    com.dddgn.alice.task.craft.CraftStation.select(bot, "inventory");
                },
                () -> new com.dddgn.alice.task.CraftGridProbeTask(bot, observer, 2, 2), 300));
        steps.add(step("craft_probe_table", List.of("alice_test:craft_table_course"),
                () -> {
                    teleportBot(com.dddgn.alice.task.CraftGridProbeTask.START);
                    com.dddgn.alice.task.craft.CraftStation.select(bot, "table");
                },
                () -> new com.dddgn.alice.task.CraftGridProbeTask(bot, observer, 3, 3), 300));
        steps.add(stepSkippable("craft_probe_upgradetab", List.of("alice_test:craft_tab_course"),
                () -> {
                    teleportBot(com.dddgn.alice.task.CraftGridProbeTask.START);
                    com.dddgn.alice.task.craft.CraftStation.select(bot, "upgradetab");
                },
                () -> new com.dddgn.alice.task.CraftGridProbeTask(bot, observer), 400,
                task -> task.failureReason().contains("station_opened")));
        // 阶段 3-A / L2（D-194）：**工作站装配**（装升级 → 能力验证 3×3 → 取回复原）
        // 依赖精妙存储：模组不在或站点不在 ⇒ SKIP（环境不具备，不判红）
        steps.add(stepSkippable("craft_station_provision", List.of("alice_test:craft_tab_course"),
                () -> teleportBot(com.dddgn.alice.task.CraftGridProbeTask.START),
                () -> new com.dddgn.alice.task.CraftStationProvisionCheckTask(bot, observer), 900,
                task -> task.failureReason().contains("mod_present")
                        || task.failureReason().contains("station_found")));
        // 阶段 3-A / C（D-195）：**模组站点真合成**（装升级 → 用页签 3×3 合成 → 拆回）
        steps.add(stepSkippable("craft_station_craft", List.of("alice_test:craft_tab_course"),
                () -> teleportBot(com.dddgn.alice.task.CraftGridProbeTask.START),
                () -> new com.dddgn.alice.task.CraftStationCraftCheckTask(bot, observer), 1200,
                task -> task.failureReason().contains("mod_present")
                        || task.failureReason().contains("station_found")));
        // 阶段 3-A / A4（D-196）：**熔炉**（"按时间工作"的另一种执行形状：放料→等烧→取产物→不留半成品）
        steps.add(step("craft_furnace", List.of("alice_test:furnace_course"),
                () -> teleportBot(com.dddgn.alice.task.CraftFurnaceCheckTask.START),
                () -> new com.dddgn.alice.task.CraftFurnaceCheckTask(bot, observer), 1000));
        // 阶段 3-A / A4b（D-198）：**菜单型炉子**（"熔炼升级页签"）—— 复用同一发现器，装升级→烧→取→拆回
        steps.add(stepSkippable("craft_cooking", List.of("alice_test:craft_tab_course"),
                () -> teleportBot(com.dddgn.alice.task.CraftFurnaceCheckTask.START),
                () -> new com.dddgn.alice.task.CraftFurnaceCheckTask(bot, observer, true), 1600,
                task -> task.failureReason().contains("mod_present")
                        || task.failureReason().contains("station_found")));
        // 阶段 3-A / A5（D-199）：**决策层合成自检**（可做清单 + 严格解析 + 生产路径 CraftJob）；
        // **不需要场景**（随身 2×2 用背包里的 4 块木板做工作台）
        steps.add(step("craft_goal", List.of(), () -> { },
                () -> new com.dddgn.alice.task.CraftGoalCheckTask(bot, observer), 600));
        // 阶段 3-B / S1（D-204 / §6.51）：**机器配方只读**（问上游自述读输入/输出 + 查询层给 MACHINE_ROUTE）；
        // 模组不在/该命名空间没有机器类型 ⇒ SKIP（不判红）。零写入。
        steps.add(stepSkippable("machine_route", List.of(), () -> { },
                () -> new com.dddgn.alice.task.MachineProbeTask(bot, observer), 200,
                task -> task.failureReason().contains("_absent")));
        // 阶段 3-B / S2+S3（D-206 / D-209）：**机器站点只读**，S3 起**按 `MachineMap` 认机器**
        // （半径内表里登记的方块每类一台 ⇒ 双机器场景也能自证点对了哪台；不再按"最近同命名空间方块"撞）；
        // 断言菜单类与"方块实体自述配方类型 == 表里的类型"。机器不在/模组未装 ⇒ `machine_absent` ⇒ SKIP。
        // 夹具**自带传送与结束复位**（PLAYBOOK §5.0d），探针预算 350 < 本步预算 400。零写入。
        steps.add(stepSkippable("machine_station", List.of("alice_test:machine_course"), () -> { },
                () -> new com.dddgn.alice.task.MachineStationProbeTask(bot, observer), 400,
                task -> task.failureReason().contains("_absent")));
        // 阶段 3-B / S4（D-213）：**单机最小闭环** —— 真的把一台机器跑起来一次（放料 → 等 → 取产物）。
        // 这是 3-B 的第一次**容器写入**：写入口径 `WriteBudget.consumeContainerWrite` +
        // 理由 CONTAINER_TRANSFER + requester `machine-cycle`（矩阵登记为 CONTAINER），
        // 写入是否成功一律**按结果验证**（机器里出现了料 / 背包里出现了产物），**不猜槽位语义**。
        // 前提：场景 `machine_course` 已摆好机器**且给了电**。⚠️ 创造方块**放下就是 0 J**
        // （上游 `BasicEnergyContainer.stored = ZERO` + creative 的 forced-SIMULATE），
        // ⇒ 场景用 `/data merge block … EnergyContainers=[{Container:0,stored:"4000000000"}]` 灌电，
        // `api_precharge`（4.0E6 J）只是**兜底**；判据是 SUMMARY 里 `energy_source=cube（场景电源，未补电）`
        // 且 `energy_at_open>0` —— 若退化成 `api_precharge`，说明场景电源失效，必须查场景而不是放宽断言。
        // 本步预算 1600 > 任务自身 MAX_TICKS 1400（让任务的守卫先报出**具体**失败原因，而不是电池的通用 TIMEOUT）；
        // 夹具**自带传送与结束复位**（PLAYBOOK §5.0d）；机器不在/模组未装 ⇒ `machine_absent` ⇒ SKIP。
        steps.add(stepSkippable("machine_cycle", List.of("alice_test:machine_course"), () -> { },
                () -> new com.dddgn.alice.task.MachineCycleCheckTask(bot, observer), 1600,
                task -> task.failureReason().contains("_absent")));
        // 阶段 3-B / (c) 增量 2（D-217）：**机器路线的生产路径** —— `CraftJob` 真的把一台机器跑起来一次。
        // 与上一步是**同一份闭环实现**（`task/craft/MachineCycle`），区别只在入口：
        // `machine_cycle` = 夹具入口（按机器类型挑配方 + 自带传送/备料/补电兜底）；
        // 本步 = **生产入口**（查询层判 MACHINE_ROUTE → `MachineMap` 的**数据驱动执行准入** EXECUTABLE →
        // 起真 `CraftJob`）。夹具只做三件测试专属的事：传送到平台远角、挑"只能靠机器做出来"的目标物
        // （用生产查询层现场复核）、按前提备料；走/开/电/放料/等/取全由 CraftJob 完成
        // ⇒ **不补电**：没电就是 `machine_no_energy` 如实失败（D-216 红线①）。
        // 预算 1800 > 夹具 MAX_TICKS 1600 > CraftJob 预算 1400（让任务先报**具体**失败原因）。
        // 机器不在/模组未装 ⇒ `machine_absent` ⇒ SKIP。本步会写容器（同 `machine_cycle`，requester=`craft`）。
        steps.add(stepSkippable("craft_machine", List.of("alice_test:machine_course"), () -> { },
                () -> new com.dddgn.alice.task.CraftMachineCheckTask(bot, observer), 1800,
                task -> task.failureReason().contains("_absent")));
        // 基-7：前缀搜索（K-1：预算耗尽交出前缀；真失败不给前缀）
        // R2：传输模块（4 个夹具：主流程/端点选择/选择器事件/命令解析）
        // K-3 安全点停止（D-169）**故意不进电池**：它的判据是"**顶层任务**被延后停止"，
        // 而 `stopTask` 停的是 bot 的顶层任务 —— 在电池里就是**电池自己**
        // （实测：`停止请求延后到安全点：task=RegressionBatteryTask` → 电池在第 12 步自杀、无 SUMMARY）。
        // ⇒ 走独立入口 `alice:k3_stop_check`（右键 DEFER / Shift+右键 FORCED），
        // 任务侧另有 `fixture_not_top_level` 前提断言兜底。
        steps.add(step("transfer", List.of(), null,
                () -> new TransferCheckTask(bot, observer), 400));
        steps.add(step("partial_search", List.of(), null,
                () -> new PartialSearchCheckTask(bot, observer), 200));
        steps.add(step("capability_gate", List.of(), null,
                () -> new CapabilityGateCheckTask(bot, observer), 200));
        steps.add(step("tool_supply", List.of(), null,
                () -> new ToolSupplyCheckTask(bot, observer), 400));
        steps.add(step("llm_contract", List.of(), null,
                () -> new LlmContractCheckTask(bot, observer), 200));
        steps.add(step("permission_gate", List.of(), null,
                () -> new PermissionContractCheckTask(bot, observer), 400));
        steps.add(step("pickup_gate",
                List.of("alice_test:lumber_course_terrain"),
                () -> teleportBot(LumberCourseAnchor.START_FOOT),
                () -> new PickupGateCheckTask(bot, observer), 600));
        steps.add(step("collect_job",
                List.of("alice_test:lumber_course_terrain"),
                () -> {
                    teleportBot(LumberCourseAnchor.START_FOOT);
                    // 夹具造掉落物并**登记为我方**（走安全默认那条路：我方 AUTO 放行）
                    for (int i = 0; i < 3; i++) {
                        var drop = new net.minecraft.world.entity.item.ItemEntity(bot.serverLevel(),
                                com.dddgn.alice.item.CollectJobItem.DROP_CENTER.getX() + 0.5D + i * 0.4D,
                                com.dddgn.alice.item.CollectJobItem.DROP_CENTER.getY() + 0.5D,
                                com.dddgn.alice.item.CollectJobItem.DROP_CENTER.getZ() + 0.5D,
                                new net.minecraft.world.item.ItemStack(
                                        net.minecraft.world.item.Items.COBBLESTONE, 8));
                        drop.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                        bot.serverLevel().addFreshEntity(drop);
                    }
                    scope.begin(com.dddgn.alice.item.CollectJobItem.DROP_CENTER, 12, bot.getUUID());
                    scope.adoptExistingDrops(bot.serverLevel(), com.dddgn.alice.item.CollectJobItem.DROP_CENTER, 12);
                },
                // **走统一入口**（JobRequest → JobLauncher）：顺带覆盖 D-134 的"起任意 Job"路径
                () -> com.dddgn.alice.job.JobLauncher.create(bot, scope,
                        com.dddgn.alice.job.JobRequest.collect(com.dddgn.alice.item.CollectJobItem.DROP_CENTER, 16, 24, 600)),
                800));
        steps.add(step("recipes_dump", List.of(), null,
                () -> new RecipesDumpCheckTask(bot, observer), 200));
        // S4 事件层：工具见底 / 卡住 两类可行动病症，各验"报到"和"只报一次"（自带夹具前提断言）
        steps.add(step("event_thresholds", List.of(), null,
                () -> new EventThresholdCheckTask(bot, observer), 800));
        // 基-1：可回收性真的被评估（P0-B：不再是"两边写死 LOCAL_STEP、校验恒假"）
        steps.add(step("recoverability", List.of(), null,
                () -> new RecoverabilityCheckTask(bot, observer), 200));
        // D-207 ①：写入集中策略表自检（纯计算 + 一次**注定失败**的规划尝试）。
        // 放在这里（而不是开头）是**有意的**：它要审计"本次电池此前所有写入"的归因样本
        //（未登记 requester / 表外 (行,理由)），样本越多越有意义。
        steps.add(step("write_policy", List.of(), null,
                () -> new WritePolicyCheckTask(bot, observer), 300));
        steps.add(step("pathing", List.of(), null,
                () -> new PathingRegressionTask(bot, observer), 5000));
    }

    // ==================== 执行 ====================

    /** 归属自校验 + 按档位裁剪（**第一次 tick 时执行**：那时 `steps` 已经全部登记完）。 */
    private void prepareSteps() {
        if (stepsPrepared) {
            return;
        }
        stepsPrepared = true;
        List<String> unclassified = new ArrayList<>();
        for (Step step : steps) {
            if (!CURATION.containsKey(step.name())) {
                unclassified.add(step.name());
            }
        }
        if (!unclassified.isEmpty()) {
            BotLog.warn("[Regression] 电池项缺归属（{}）⇒ 必须补进 CURATION 与 docs/BATTERY_CURATION.md：{}",
                    unclassified.size(), unclassified);
            curationError = "unclassified=" + unclassified;
        }
        // **自校验必须跟"全量步骤集"比**（2026-09-13 实测 bug：拿裁剪后的比 ⇒ CORE 模式把 10 个被跳过的
        // EXTRA 误判成"文档说测了、其实没测"，整轮电池假 FAIL，而其实 23/23 全绿）
        allStepNames = new ArrayList<>();
        for (Step step : steps) {
            allStepNames.add(step.name());
        }
        int before = steps.size();
        if (mode == Mode.CORE) {
            steps.removeIf(step -> profileOf(step.name()) == Profile.EXTRA);
        }
        extraSkipped = before - steps.size();
        BotLog.info("[Regression] PROFILE={} 实跑 {} 项（跳过 EXTRA {} 项）", mode, steps.size(), extraSkipped);
    }

    private Profile profileOf(String name) {
        return CURATION.getOrDefault(name, Profile.EXTRA);
    }

    /** 归属表里登记了、但电池里没有的步骤（也会判红：防止"文档说测了、其实没测"）。 */
    private List<String> phantomEntries() {
        List<String> phantom = new ArrayList<>();
        for (String name : CURATION.keySet()) {
            if (!allStepNames.contains(name)) {   // ← 用**全量**步骤集（不受档位裁剪影响）
                phantom.add(name);
            }
        }
        return phantom;
    }

    @Override
    public Status tick() {
        prepareSteps();
        if (++ticks > TOTAL_BUDGET_TICKS) {
            BotLog.warn("[Regression] 总预算用尽 step={} ticks={}", currentStepName(), ticks);
            return finish();
        }
        if (current == null) {
            if (index >= steps.size()) {
                return finish();
            }
            return startStep(steps.get(index));
        }
        // D-175：**夹具不得把服务端 tick 循环打死**。2026-09-13 实测：mine_regression 的内层
        // `MineTask` 在终态后被多 tick 一次触发 NPE ⇒ 整个服务端崩溃（客户端直接挂）。
        // 生产任务不该吞异常（崩溃本身是 bug，要修），但**测试电池**必须把单步故障隔离成
        // 一条如实上报的 FAIL + 完整栈（否则一次夹具缺陷就毁掉整轮测试与全部证据）。
        Status status;
        try {
            status = current.tick();
        } catch (Throwable throwable) {
            BotLog.warn("[Regression] step={} 抛出异常 ⇒ 记 FAIL 并继续（夹具隔离；完整栈如下）",
                    currentStepName(), throwable);
            record(currentStepName(), "FAIL", "exception=" + throwable);
            endStep();
            return Status.RUNNING;
        }
        if (steps.get(index).doneWhen() != null && steps.get(index).doneWhen().test(current)) {
            record(steps.get(index).name(), "PASS",
                    "ticks=" + stepTicks + "（常驻任务按达成判过：chopped/planted 已达判据）");
            endStep();
            return Status.RUNNING;
        }
        if (status == Status.RUNNING) {
            if (++stepTicks > steps.get(index).budgetTicks()) {
                record(steps.get(index).name(), "TIMEOUT",
                        "ticks=" + stepTicks + "（单项预算用尽）");
                endStep();
            }
            return Status.RUNNING;
        }
        // D-178（审查结论 ⑤）：**终态幂等契约的集中执行点**。
        // 契约：任务一旦返回终态（DONE/FAILED），再被 `tick()` 必须**幂等**（同状态返回、不崩）。
        // 2026-09-13 的服务端崩溃（MineTask.tickRestore NPE）正是这条契约被破坏 ⇒ 这里对**每一个**
        // 电池步的终态任务都补 tick 两次并如实记录 `idempotent=`：不必逐个类加闩锁，
        // 也能把"同类隐患 19 处"变成可观测的断言（谁违反，下一轮电池就会红）。
        boolean idempotent = true;
        String idemNote = "";
        try {
            Status again1 = current.tick();
            Status again2 = current.tick();
            if (again1 != status || again2 != status) {
                idempotent = false;
                idemNote = "（再 tick 返回 " + again1 + "/" + again2 + "，期望 " + status + "）";
            }
        } catch (Throwable throwable) {
            idempotent = false;
            idemNote = "（再 tick 抛 " + throwable + "）";
            BotLog.warn("[Regression] step={} 终态幂等被破坏：再 tick 抛异常", currentStepName(), throwable);
        }
        // **可跳过的步骤**：失败但"环境不具备"（例如没装对应模组）⇒ 记 SKIP，不判红
        var skipWhen = steps.get(index).skipWhen();
        boolean skipped = status != Status.DONE && idempotent
                && skipWhen != null && skipWhen.test(current);
        record(steps.get(index).name(),
                skipped ? "SKIP" : ((status == Status.DONE && idempotent) ? "PASS" : "FAIL"),
                "ticks=" + stepTicks
                        + (status == Status.DONE ? "" : " reason=" + safe(current.failureReason()))
                        + (skipped ? "（环境不具备 ⇒ 跳过，不算失败）" : "")
                        + " idempotent=" + idempotent + idemNote);
        endStep();
        return Status.RUNNING;
    }

    private Status startStep(Step step) {
        // **每步一个独立作用域**（镜像 BotSession.beginTask 的那一半）：WriteBudget 的
        // 破坏/放置上限与账本 TEMP 都是"一次任务一个作用域" ⇒ 九项共用一个作用域会串味
        // （后面的项会撞上前面的 64/32 上限、恢复阶段也会互相看见对方的临时方块）。
        com.dddgn.alice.ledger.WorldModLedger.openScope(
                bot.getServer(), bot.getUUID(), "Regression:" + step.name());
        BotLog.info("[Regression] step={} ({}/{}) scenes={} budget={}",
                step.name(), index + 1, steps.size(), step.scenes(), step.budgetTicks());
        bot.controller().stopMovement();
        if (!step.scenes().isEmpty()) {
            var server = bot.serverLevel().getServer();
            var source = server.createCommandSourceStack().withSuppressedOutput();
            for (String fn : step.scenes()) {
                server.getCommands().performPrefixedCommand(source, "function " + fn);
            }
        }
        if (step.provision() != null) {
            step.provision().run();
        }
        // **每步前提自证 + 修复**（D-201 附注一/三 / D-203）：一步开始前，环境必须干净到
        // "没有别人的容器菜单挂着"。为什么必须在**每步之间**做：上游页签槽位地址会重叠
        // （精妙容器菜单的 64..66 既是合成页签格子、也是熔炼页签格子）⇒ 上一步残留的菜单会让下一步
        // 的点击"被接受却落到别处"，症状是**料进去了却不烧**（2026-09-13 实测：撤走 8 步后
        // craft_furnace/craft_cooking/transfer 可复现变红，而 FULL 里同三步全绿 ⇒ 缺的就是这里的清场）。
        var premiseOwn = com.dddgn.alice.task.FixturePremise.ownMenu(bot);
        var premiseGround = com.dddgn.alice.task.FixturePremise.onGround(bot);
        BotLog.info("[Regression] premise step={} {} | {}", steps.get(index).name(),
                premiseOwn.ok() ? "own_menu=true" : "own_menu=false(" + premiseOwn.detail() + ")",
                premiseGround.ok() ? "on_ground=true" : "on_ground=false(" + premiseGround.detail() + ")");
        if (!premiseOwn.ok()) {
            BotLog.warn("[Regression] premise step={} 有残留容器菜单 ⇒ 先关掉再跑（{}）",
                    steps.get(index).name(), premiseOwn.detail());
            bot.closeContainer();
        }
        stepTicks = 0;
        stepStarted = true;
        current = step.factory().get();
        return Status.RUNNING;
    }

    private void endStep() {
        // 镜像 BotSession.clearTask 的作用域收尾：关账本作用域 → 关写入预算（打一行摘要）
        // → 销掉现场已非我方方块的条目 → 关作用域缓冲（下一步的子任务会自己 begin）
        String closed = com.dddgn.alice.ledger.WorldModLedger.closeScope(
                bot.getServer(), bot.getUUID());
        com.dddgn.alice.action.WriteBudget.closeScope(closed);
        com.dddgn.alice.ledger.WorldModLedger.dropStale(bot.serverLevel());
        var pending = com.dddgn.alice.ledger.WorldModLedger.pendingTemporary(
                bot.getServer(), closed);
        scope.end();
        // **站点选择不跨步泄漏**：电池是自检串联，谁设的谁收（下一步回到 auto = 现状顺序）
        com.dddgn.alice.task.craft.CraftStation.select(bot, "auto");
        if (!pending.isEmpty()) {
            BotLog.warn("[Regression] step={} 收尾仍有 {} 条我方临时放置未拆（建拆同权未闭合）",
                    currentStepName(), pending.size());
        }
        current = null;
        stepStarted = false;
        index++;
    }

    private void record(String name, String value, String detail) {
        results.put(name, value);
        details.put(name, detail);
        BotLog.info("[Regression] {}={} {}", name, value, detail);
    }

    private Status finish() {
        long pass = results.values().stream().filter("PASS"::equals).count();
        int expected = steps.size();
        // K-4 / D-167 自断言：本次电池里**纯通行类**的目标准入矛盾必须为 0。
        //   · goal_not_standable：规划期用离散格相等宣布 REACHED，但该格连"可站"都不成立，
        //     且最后一条边不是写入类 ⇒ 与 provider 自身的 canStandCentered 保证直接冲突；
        //   · final_segment_target_not_standable：最终段（EXACT）的目标格不可站且非写入类。
        // 写入类的两个信息码（goal_post_write_not_standable / final_segment_target_post_write）
        // 是**设计如此**（破坏类移动的落点只能证明"破坏之后可站"），不计入失败。
        int goalBad = k4Delta("goal_not_standable");
        int finalBad = k4Delta("final_segment_target_not_standable");
        int postWrite = k4Delta("goal_post_write_not_standable")
                + k4Delta("final_segment_target_post_write");
        boolean k4Ok = goalBad == 0 && finalBad == 0;
        if (!k4Ok) {
            BotLog.warn("[K4] VIOLATION 本次电池出现谓词矛盾：goal_not_standable={}"
                            + " final_segment_target_not_standable={}（可站谓词不统一的实测证据）",
                    goalBad, finalBad);
        }
        long skipped = results.values().stream().filter("SKIP"::equals).count();
        boolean allPass = (pass + skipped) == expected && results.size() == expected && k4Ok
                && curationError.isEmpty() && phantomEntries().isEmpty();
        StringBuilder line = new StringBuilder();
        for (Step step : steps) {
            if (!line.isEmpty()) {
                line.append(' ');
            }
            line.append(step.name()).append('=')
                    .append(results.getOrDefault(step.name(), "SKIPPED"));
        }
        line.append(" K4=").append(k4Ok
                ? "OK(goal_not_standable=0 final_segment_not_standable=0 写入类例外=" + postWrite + ")"
                : "VIOLATION(goal_not_standable=" + goalBad
                        + " final_segment_not_standable=" + finalBad + ")");
        BotLog.info("[Regression] SUMMARY {} PROFILE={} baseline={} main={} extra_skipped={}"
                        + " ({}/{}{}) ticks={} → {}",
                line, mode, countProfile(Profile.BASELINE), countProfile(Profile.MAIN), extraSkipped,
                pass + skipped, expected,
                skipped > 0 ? "，其中 SKIP=" + skipped : "", ticks, allPass ? "PASS" : "FAIL");
        List<String> phantom = phantomEntries();
        if (!curationError.isEmpty() || !phantom.isEmpty()) {
            BotLog.warn("[Regression] 电池归属表与实跑项不一致：{} {}（见 docs/BATTERY_CURATION.md）",
                    curationError, phantom.isEmpty() ? "" : "phantom=" + phantom);
        }
        return allPass ? Status.DONE : Status.FAILED;
    }

    private int countProfile(Profile profile) {
        int count = 0;
        for (Step step : steps) {
            if (profileOf(step.name()) == profile) {
                count++;
            }
        }
        return count;
    }

    /** 本次电池期间的累计计数增量。 */
    private int k4Delta(String code) {
        Map<String, Integer> now = com.dddgn.alice.pathing.core.search.PathingStats.totalsSnapshot();
        return now.getOrDefault(code, 0) - k4Baseline.getOrDefault(code, 0);
    }

    private String currentStepName() {
        return index < steps.size() ? steps.get(index).name() : "-";
    }

    private static String safe(String reason) {
        return reason == null || reason.isBlank() ? "-" : reason;
    }

    private void teleportBot(BlockPos foot) {
        bot.teleportTo(bot.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
    }
}
