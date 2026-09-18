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
                        java.util.function.Predicate<Task> skipWhen,
                        /**
                         * **B 方案（2026-09-17 用户裁定）**：本步**故意**在世界里留下我方临时方块。
                         *
                         * <p>不声明 ⇒ `endStep` 发现 `pendingTemporary(本步 scope)` 非空就**把本步判红**
                         * （错误当场出现在出错那一步，而不是十几步后某个名字不相干的 craft 步）。
                         */
                        boolean keepWorldState) {
    }

    /** 常规步骤（跑完看终态）。 */
    /** **故意留世界状态**的步（B 方案）：声明后 `endStep` 不再因遗留而判红。 */
    private static Step stepKeeping(String name, List<String> scenes, Runnable provision,
                                    Supplier<Task> factory, int budgetTicks) {
        return new Step(name, scenes, provision, factory, budgetTicks, null, null, true);
    }

    private static Step step(String name, List<String> scenes, Runnable provision,
                             Supplier<Task> factory, int budgetTicks) {
        return new Step(name, scenes, provision, factory, budgetTicks, null, null, false);
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
        return new Step(name, scenes, provision, factory, budgetTicks, null, skipWhen, false);
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
            // ---- BASELINE：必要基础（15）----
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
            // S-5（2026-09-15）：**维生决策表 + 出口可达 + 掉血可见** —— 维生是"长作业能活着回来"的底线件，
            // 而且此前**零电池步**（只能真人验）。放 BASELINE（CORE 跑），每次改动都跑得到。
            Map.entry("survival_exit", Profile.BASELINE),
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
            // M1（G1）：挖矿候选菜单契约 —— `mine` 不许猜位置（矿石场景 + 复用 MineCandidateSource）。
            // 放 MAIN（CORE 跑）而不是 EXTRA：它是"**不猜语义**"这条红线的门禁，必须每次改动都跑得到。
            Map.entry("mine_menu", Profile.MAIN),
            // M2（G2）：长作业周期复评 —— "自主"的物理载体（无进度 ⇒ 报一次 NO_PROGRESS）。
            // 放 MAIN（CORE 跑）：它是停止条件 A1「中途自己发现问题」的必要条件。
            Map.entry("no_progress", Profile.MAIN),
            // M3（G3 归因）：缺工具必须报 `tool_missing`，不许被总括码 `no_reachable_candidate` 盖掉。
            Map.entry("mine_no_tool", Profile.MAIN),
            // M3b（2026-09-15）：两条"照词表写了、但从来没被观测过"的归因映射各配一个确定性夹具。
            Map.entry("mine_stale", Profile.MAIN),
            Map.entry("mine_budget", Profile.MAIN),
            // 队列第③项（2026-09-17）：`MineJob` 新尝试上场时必须保留上一轮失败事实。
            // ⚠️ 判据必须在**运行中**采样（结束态走 finishedMinerNode 分支，天然有 failure ⇒ 判别不了）。
            Map.entry("mine_failure_visible", Profile.MAIN),
            // S-9（2026-09-17 用户裁定）：伤害改用**事件**观测 —— 证明"靠采样血量差看不见的伤害"能被看见。
            Map.entry("damage_event_visible", Profile.MAIN),
            // S-6（2026-09-17 用户裁定）：风险开关按 bot **冻结**（同一任务内口径不变；命令改开关后重新冻结）。
            Map.entry("risk_profile_frozen", Profile.MAIN),
            // F4 地基（D-267）：说话通道**只出不进** —— 说话不改变决策态（否则文本绕过 GoalAction 白名单）。
            Map.entry("speech_channel", Profile.MAIN),
            // 死亡机制第 1 步（D-276）：死亡**不删数据**（倒下态 = FALLEN，含位置/死因/时刻）。
            Map.entry("death_persistence", Profile.MAIN),
            // V-4 对照的 **Alice 侧**（2026-09-17）：把 FALL / PILLAR 的移动执行 tick 变成无头可取的数
            Map.entry("fall_execute", Profile.MAIN),
            Map.entry("pillar_execute", Profile.MAIN),
            // **CORE 修剪（2026-09-17）**：它是 V-4 停表的**工具检查**（V-4 已裁定不阻塞）⇒ 移出 CORE，
            // 只在 FULL 跑（`single:contrast_timer` 仍可随时单独跑）。判据本身不变。
            Map.entry("contrast_timer", Profile.EXTRA),
            Map.entry("driver_label", Profile.MAIN),
            Map.entry("container_access_profile", Profile.MAIN),
            Map.entry("hazard_aversion_plan", Profile.MAIN),
            // **设计线 · 保护区（D-313）**：`SafeZoneData` 改成区块级 2D 认领（忽略 Y / 全高度）+
            // 旧圆形迁移 ⇒ "认领的区块不许动、没认领放行、旧数据不静默丢"必须每轮 CORE 都成立。
            // 按 `BATTERY_CURATION.md` 规则 1（新能力默认进 MAIN）；成本≈0（纯查询、不写世界）。
            Map.entry("protection_zones", Profile.MAIN),
            // D-276 端到端（第 1 半）：**真弄死一个探针 bot**，验证数据落成倒下态。
            // 放 EXTRA：它会写"倒下态"存档（会覆盖 botTag）⇒ 只适合 `single:` 单独跑。
            Map.entry("death_kill_bot", Profile.EXTRA),
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
            // 2026-09-15（③/策展）：从 EXTRA 提到 MAIN —— 它自己的类注释写着"任何改动都跑得到"，
            // 而 CURATION 把它放 EXTRA ⇒ CORE 根本跑不到（承诺与档位矛盾）。与 M1/M2/M4 三次提档同一理由：
            // **门禁必须默认跑得到**。代价 = 200 tick、纯逻辑、不调 LLM、不改世界（确定性）。
            Map.entry("decision_contract", Profile.MAIN),
            Map.entry("decision_trace", Profile.EXTRA),
            // 2026-09-15（M4）：从 EXTRA 提到 MAIN —— 它现在含"失败事实必须是字段"的门禁
            Map.entry("llm_contract", Profile.MAIN),
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

    /**
     * **定向模式**（T2 无头通道用）：非 null 时只跑名单里的步。
     *
     * <p>用途：无头首次接入要一个"秒级样板"来验证通道本身（起服 → 生成假人 → 跑一步 → 出判决 → 非零退出），
     * 跑整轮 CORE（~3,350 tick）调试太慢。**只裁剪实跑集，不影响归属自校验** ——
     * {@code allStepNames} 在裁剪前已快照，所以 `phantomEntries()` 仍按全量比对。
     * 默认 null = 不裁剪（游戏内入口永不走这条路）。
     */
    private static volatile java.util.Set<String> onlySteps;

    /**
     * 最近一次跑完的电池判决（`PASS` / `DEGRADED` / `FAIL`）；null = 本进程还没跑完过一轮。
     *
     * <p>**唯一消费者是无头入口**（{@code com.dddgn.alice.headless.HeadlessBattery}）：它靠这个值把
     * "电池判决"翻译成**进程退出码**，从而让 `./gradlew runServer -Dalice.headless.battery=core`
     * 能被 CI 判红。游戏内路径不读它。
     */
    private static volatile String lastVerdict;

    /** 无头入口：设定定向步名单（null / 空 = 恢复不裁剪）。 */
    /**
     * **已知步名**（= 归属表 {@link #CURATION} 的键）。
     *
     * <p>为什么可以直接用 CURATION：它的文档注释写明「构造时会**自校验**（有步骤没归属 / 有归属没步骤 ⇒ 直接判红）」
     * ⇒ 归属表与实际步骤表**一一对应**，于是"这个步名存不存在"在**起服务端之前**就能回答。
     * 用于 `single:<step>` 的**快速失败**（2026-09-17：写错步名原先会白跑 200 tick 才报 `battery_never_ran`）。
     */
    public static java.util.Set<String> knownStepNames() {
        return CURATION.keySet();
    }

    public static void setOnlySteps(java.util.Collection<String> names) {
        onlySteps = (names == null || names.isEmpty()) ? null : java.util.Set.copyOf(names);
    }

    /** 最近一次电池判决（见 {@link #lastVerdict}）。 */
    public static String lastVerdict() {
        return lastVerdict;
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
    /** 已经为哪个 `index` 做过"开作用域/建场景/发料"（等待落地时会**重复进入** `startStep`，不能重做）。 */
    private int setupDoneForIndex = -1;
    /** 本步"等落地"已等的 tick 数（0 = 没在等）。 */
    private int premiseWaitTicks;
    /** 有界等待上限：bot 落到地面通常几个 tick 内完成；等这么久还不落地就**如实继续**（不再等）。 */
    private static final int MAX_PREMISE_WAIT_TICKS = 40;
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

    /** R-2：模块构建自检步时用的上下文（模块**只**通过它拿 bot/观察者/作用域 ⇒ 模块不持有编排状态 ✓）。 */
    private com.dddgn.alice.task.check.CheckContext checkContext() {
        return new com.dddgn.alice.task.check.CheckContext() {
            @Override
            public com.dddgn.alice.bot.BotPlayer bot() {
                return bot;
            }

            @Override
            public net.minecraft.server.level.ServerPlayer observer() {
                return observer;
            }

            @Override
            public com.dddgn.alice.perception.ScopeBuffer scope() {
                return scope;
            }
        };
    }

    /** R-2：把模块的 {@code CheckStep} 适配成电池内部步（Phase 1a 保留双类型；Phase 1b 合并）。 */
    private List<Step> fromCheckSteps(List<com.dddgn.alice.task.check.CheckStep> checks) {
        List<Step> out = new ArrayList<>(checks.size());
        for (com.dddgn.alice.task.check.CheckStep c : checks) {
            if (c.doneWhen() != null || c.skipWhen() != null || c.keepWorldState()) {
                out.add(new Step(c.name(), c.scenes(), c.provision(), c.factory(), c.budgetTicks(),
                        c.doneWhen(), c.skipWhen(), c.keepWorldState()));
            } else {
                out.add(new Step(c.name(), c.scenes(), c.provision(), c.factory(), c.budgetTicks(),
                        null, null, false));
            }
        }
        return out;
    }

    private void buildSteps() {
        // 由轻到重：先跑秒级自检，再跑 Job / 长回归，便于"早失败早知道"
        // ---- 模块化（R-2 Phase 1a）：**账本模块**从 `task/check/modules/LedgerModule` 取 ----
        // 逐字段等价搬迁（步名/顺序/预算/工厂完全一致 ✓）；档位仍由上面的 CURATION 表决定（Phase 1b 会把档位搬进模块）
        steps.addAll(fromCheckSteps(new com.dddgn.alice.task.check.modules.LedgerModule().steps(checkContext())));
        // ---- 模块化（R-2）：**伐木模块**（3 步）从 `LumberModule` 取 ----
        // 逐字段等价搬迁（步名/场景/发料/工厂/预算/doneWhen 一致 ✓）；三步的分工见模块头注释
        steps.addAll(fromCheckSteps(new com.dddgn.alice.task.check.modules.LumberModule().steps(checkContext())));
        // ---- 模块化（R-2）：**挖掘模块**（7 步）从 `MiningModule` 取 ----
        // 逐字段等价搬迁（步名/场景/发料/工厂/预算/doneWhen 一致 ✓）；七步的分工见模块头注释
        // ⚠️ 归因三连（mine_no_tool / mine_stale / mine_budget）的判据挂在 `doneWhen` 上 ⇒ 编排器必须
        // 支持 `doneWhen`（D-300），且**本步作用域要在 provision 之前开好**（`mine_budget` 要压本作用域预算，D-301）
        steps.addAll(fromCheckSteps(new com.dddgn.alice.task.check.modules.MiningModule().steps(checkContext())));
        // ---- 模块化（R-2）：`death_kill_bot` 已由 `DeathModule` 提供 ----
        // ⚠️ 它**不再在这里**：`DeathModule` 必须整块落在一个位置上，而"保持 CORE 步序逐字不变"优先
        // ⇒ 模块落在 `death_persistence` 的原位（下面），`death_kill_bot`（EXTRA，CORE 不跑）随之挪到那里。
        // 理由与代价写在 `DeathModule` 的 javadoc（含"反过来做会让 CORE 提前 3 个模块"的对照）✓
        steps.add(step("hazard_aversion_plan",
                List.of(),   // 场景由夹具自己装（先热区块再 fill ✓），不依赖前序模块 ✓
                () -> teleportBot(HazardAversionCheckTask.START),
                () -> new HazardAversionCheckTask(bot),
                200));
        // ---- 模块化（R-2 Phase 1b）：**决策/观测/归因模块**从 `DecisionModule` 取 ----
        steps.addAll(fromCheckSteps(new com.dddgn.alice.task.check.modules.DecisionModule().steps(checkContext())));
        // ---- 模块化（R-2 Phase 1b）：**移动模块**（fall/pillar/contrast 三步）从 `PathingModule` 取 ----
        steps.addAll(fromCheckSteps(new com.dddgn.alice.task.check.modules.PathingModule().steps(checkContext())));
        // ---- 模块化（R-2）：**死亡模块**（2 步）从 `DeathModule` 取 ----
        // 逐字段等价搬迁（步名/档位/场景/预算/工厂一致 ✓）；⭐ **模块落在这里是刻意的** ——
        // `death_persistence`（MAIN）保持**原位** ⇒ CORE 步序逐字不变 ✓；
        // 被挪动的只有 `death_kill_bot`（EXTRA ⇒ CORE 不跑；它的注释写明"只适合 `single:` 单独跑"）
        // ⚠️ 反过来放（"先杀后验"）会让 `death_persistence` 在 CORE 里提前 3 个模块 = 未证明的顺序变更 ✗
        steps.addAll(fromCheckSteps(new com.dddgn.alice.task.check.modules.DeathModule().steps(checkContext())));
        // ---- 模块化（R-2）：**决策契约模块**（3 步：speech_channel / decision_contract / decision_trace）----
        // 逐字段等价搬迁（步名/档位/场景/预算/工厂一致 ✓）；三步在电池里**本来就是连续的** ⇒ 原序落位 ✓
        // ⚠️ 其中 speech_channel 与 decision_contract 带场景（各自 pre-provision 传送 ⇒ 先热区块再 fill ✓）
        steps.addAll(fromCheckSteps(new com.dddgn.alice.task.check.modules.ContractsModule().steps(checkContext())));
        // 基-5：LLM 上抛契约（Job 失败报告 / 产物判定口径 / 结构化拒绝回读）
        // 基-9：工具供给（换更好的 / 没得换如实报 / 不能凭空变出工具）
        // 基-8：能力闸门（MovementCapabilities 真的能拦人：保护区/资源/工具/预算/声明一致性）
        // ---- 模块化（R-2）：**合成 / 工作站模块**（12 步）从 `CraftModule` 取 ----
        // 逐字段等价搬迁（步名/顺序/预算/工厂/跳过条件完全一致 ✓）；**场景与发料都由模块自带** ✓
        // ⇒ `module:craft` 单跑必须绿 —— 这段正是历史上「`single:craft_table` 单跑必红」的那个坑 ✓
        steps.addAll(fromCheckSteps(new com.dddgn.alice.task.check.modules.CraftModule().steps(checkContext())));
        // ---- 模块化（R-2）：**机器路线模块**（4 步）从 `MachineModule` 取 ----
        // 逐字段等价搬迁（步名/场景/工厂/预算/跳过条件一致 ✓）；**场景由模块自带**（并且先传送热区块 ✓）
        // ⚠️ 搬它时先修掉了一个**夹具前提假绿**：`machine_station` 单跑必红（`menu_not_settled`）——
        // 因为 teleport 那一 tick 的 `onGround` 是**上一处的陈旧读数** ⇒ 见 MachineStationProbeTask（2026-09-17）
        steps.addAll(fromCheckSteps(new com.dddgn.alice.task.check.modules.MachineModule().steps(checkContext())));
        // 基-7：前缀搜索（K-1：预算耗尽交出前缀；真失败不给前缀）
        // R2：传输模块（4 个夹具：主流程/端点选择/选择器事件/命令解析）
        // K-3 安全点停止（D-169）**故意不进电池**：它的判据是"**顶层任务**被延后停止"，
        // 而 `stopTask` 停的是 bot 的顶层任务 —— 在电池里就是**电池自己**
        // （实测：`停止请求延后到安全点：task=RegressionBatteryTask` → 电池在第 12 步自杀、无 SUMMARY）。
        // ⇒ 走独立入口 `alice:k3_stop_check`（右键 DEFER / Shift+右键 FORCED），
        // 任务侧另有 `fixture_not_top_level` 前提断言兜底。
        // ---- 模块化（R-2）：**传输模块**（1 步）从 `TransferModule` 取 ----
        // 逐字段等价搬迁（步名/档位/预算/工厂一致 ✓）；**模块自带"先传送"这一条前提** ——
        // 夹具自己会调场景函数，但顺序是"**先跑函数、再传送**" ⇒ 冷区块上那一发 `/fill` 会静默不落地
        // （D-296 同一个坑）⇒ 模块把"传送到课程起点"放进 `provision`
        //（编排器顺序 = openScope → provision → scenes ✓，所以传送顺便把区块热了 ✓）
        steps.addAll(fromCheckSteps(new com.dddgn.alice.task.check.modules.TransferModule().steps(checkContext())));
        // ---- 模块化（R-2）：**闸门模块**（2 步：partial_search / capability_gate）----
        // 逐字段等价搬迁；两步连续 ⇒ 原序落位 ✓；都纯逻辑（无场景、不写世界）⇒ 不需要前提 ✓
        steps.addAll(fromCheckSteps(new com.dddgn.alice.task.check.modules.GatesModule().steps(checkContext())));
        // ---- 模块化（R-2）：**工具模块**（1 步）从 `ToolsModule` 取 ----
        // 逐字段等价搬迁（步名/档位/预算/工厂一致 ✓）；无场景、无 provision（夹具**只动背包**，不依赖地形 ✓）
        steps.addAll(fromCheckSteps(new com.dddgn.alice.task.check.modules.ToolsModule().steps(checkContext())));
        // ---- 模块化（R-2）：**LLM 与权限契约模块**（2 步：llm_contract / permission_gate）----
        // 逐字段等价搬迁；两步连续 ⇒ 原序落位 ✓；`permission_gate` 是 EXTRA ⇒ 进模块后首次有单独跑通道 ✓
        steps.addAll(fromCheckSteps(new com.dddgn.alice.task.check.modules.LlmModule().steps(checkContext())));
        // ---- 模块化（R-2）：**掉落物模块**（2 步：pickup_gate / collect_job）----
        // 逐字段等价搬迁（步名/档位/场景/预算/工厂/provision 一致 ✓）；两步连续 ⇒ 原序落位 ✓
        // ⚠️ `collect_job` 的 provision 里"造 3 个掉落物 + scope.begin + adoptExistingDrops"必须保留
        // （否则收集 Job 起来时没有可收的东西 ⇒ 假绿）
        steps.addAll(fromCheckSteps(new com.dddgn.alice.task.check.modules.PickupModule().steps(checkContext())));
        // ---- 模块化（R-2）：**观测/转储模块**（2 步：recipes_dump / event_thresholds）----
        // 逐字段等价搬迁；两步连续 ⇒ 原序落位 ✓；都是 EXTRA ⇒ 进模块后首次有单独跑通道 ✓
        steps.addAll(fromCheckSteps(new com.dddgn.alice.task.check.modules.TelemetryModule().steps(checkContext())));
        // ---- 模块化（R-2）：**写入与可回收性模块**（2 步：recoverability / write_policy）----
        // 逐字段等价搬迁；两步连续 ⇒ 原序落位 ✓
        // ⚠️ `write_policy` **必须留在电池后段**：它审计"本次电池此前所有写入"的归因样本 ⇒ 越靠后样本越多 ✓
        steps.addAll(fromCheckSteps(new com.dddgn.alice.task.check.modules.WriteModule().steps(checkContext())));
        steps.add(step("pathing", List.of(), null,
                () -> new PathingRegressionTask(bot, observer), 5000));
        // ---- 模块化（R-2）：**维生模块**（1 步）从 `SurvivalModule` 取 ----
        // 逐字段等价搬迁（步名/档位/场景/预算/工厂一致 ✓）；**模块自带"先传送"的前提** ⇒
        // 编排器顺序 = provision → scenes ⇒ 区块先热再 `/fill` 才真的落地（旧电池是"场景 → provision"✗，D-296 记过）
        // ⚠️ 封闭场景（无出口）仍由夹具自己在到位后建造 —— 它进不了 `scenes`（见模块 javadoc）✓
        steps.addAll(fromCheckSteps(new com.dddgn.alice.task.check.modules.SurvivalModule().steps(checkContext())));
        // ---- 设计线：**保护区模块**（1 步，EXTRA：CORE 不跑 ⇒ 上面 48 步的次序一个格子都不动）----
        // 新能力（`SafeZoneData` 改成区块级 2D 认领 + 旧格式迁移）的第一个离线门禁；见 D-313
        steps.addAll(fromCheckSteps(new com.dddgn.alice.task.check.modules.ProtectionModule().steps(checkContext())));
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
        var only = onlySteps;
        if (only != null) {
            // 定向模式（无头样板）：按名字裁剪。**按名字点名时不再受 CORE/FULL 档位影响**
            // —— 否则 `single:<EXTRA 步>` 会被下面的 CORE 裁剪二次抹掉，症状是"0 项实跑"。
            steps.removeIf(step -> !only.contains(step.name()));
            BotLog.info("[Regression] 定向模式：只跑 {} 项 {}", steps.size(),
                    steps.stream().map(Step::name).toList());
        } else if (mode == Mode.CORE) {
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
            // 详情**通用化**并把任务自报的终态理由带出来（M3：`mine_no_tool` 的判据就是
            // "terminalReason == tool_missing"，写在日志里才看得见；旧文案是给 region_maintain
            // 写的"chopped/planted"，放到别的 doneWhen 步骤上就是错话）。
            record(steps.get(index).name(), "PASS",
                    "ticks=" + stepTicks + "（doneWhen 判据成立 ⇒ 本步按**达成**判过；task="
                            + current.getClass().getSimpleName()
                            + " terminalReason=" + current.terminalReason() + "）");
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
        // **可跳过的步骤**：**环境不具备**（例如没装对应模组）⇒ 记 SKIP，不判红。
        // ⚠️ 2026-09-14（T0-a 堵假绿）：判据**不再要求 `status != Status.DONE`**。
        // 原判据的漏洞（三路审计实证）：`MachineProbeTask` 在模组缺失时**如实**返回
        // `DONE` + `failureReason()="machine_namespaces_absent"`，却因为"终态是 DONE"而永远进不了
        // SKIP 分支 ⇒ 被记成 **PASS** —— 即"这一步什么都没断言"被记成了绿。
        // 现在：只要任务**自述环境不具备**，无论终态是 DONE 还是 FAILED，一律记 SKIP。
        // 而 SKIP **不再计入 PASS**（见 `finish()` 的 `DEGRADED`）⇒ 假绿在两端都被堵住。
        var skipWhen = steps.get(index).skipWhen();
        boolean skipped = idempotent && skipWhen != null && skipWhen.test(current);
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
        // ⚠️ **`startStep` 会被重复进入**（等落地时本方法每 tick 再进一次，见 `awaitGrounding`）
        // ⇒ 开作用域/建场景/发料**只能做一次**：重做会**重复发料**、重复执行场景函数。
        // ⚠️ 但**落地判断必须每 tick 复检** —— 我第一版让重入分支直接 `return awaitGrounding(...)`，
        // 于是复检被跳过、白等满上限（实测日志：超时行里 `onGround=`**`true`** 却仍在等 ⇒ 自己暴露了自己）。
        boolean freshStep = setupDoneForIndex != index;
        if (freshStep) {
            setupDoneForIndex = index;
            premiseWaitTicks = 0;
        }
        if (freshStep) {
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
        }
        // **（A）落地同步：每 tick 复检**（不复检 = 白等满上限，且会把"其实已经落地"读成超时）
        if (!com.dddgn.alice.task.FixturePremise.onGround(bot).ok()) {
            return awaitGrounding(step);
        }
        if (premiseWaitTicks > 0) {
            BotLog.info("[Regression] premise step={} 已落地（等了 {} tick）⇒ 开始本步",
                    step.name(), premiseWaitTicks);
            premiseWaitTicks = 0;
        }
        stepTicks = 0;
        stepStarted = true;
        // **F1 地基**：本步由**自检夹具**驱动（与"玩家让做的/LLM 决定的"区分开）——
        // 终态记录与决策快照据此归因（`Driver`）。
        com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.FIXTURE);
        current = step.factory().get();
        return Status.RUNNING;
    }

    /**
     * **（A）起步前的"落地同步"**（2026-09-14 实测缺陷的修法）。
     *
     * <p>为什么要修：步骤之间**没有起点锚定** ⇒ "起步时 bot 还在半空/还在滑动"会把一个**移动中的起点**
     * 交给下一步。实测代价：同一 jar 两轮，`partial_search` 的 premise 一次 `on_ground=true`
     * （`from.z=406` ⇒ `PARTIAL movements=2`，PASS）、一次 `on_ground=false`（`from.z=404` ⇒
     * `SEARCH_LIMIT movements=0 best=0.0`，**FAIL**）—— 那个用例的预算只有 2 个节点，
     * 起点差一两格就足以决定"有没有前缀可交"。**规划器行为是对的，是夹具前提不成立。**
     *
     * <p>**为什么必须同时打 warn**：等落地会把"**某一步把 bot 留在了半空**"这个**真 bug** 遮住。
     * 所以每次真的等待都**出声**（`起步时未落地`），并且只报一次、不刷屏 ⇒ 遮不住，只降噪。
     * 触发条件可 grep：`[Regression] premise step=… 起步时未落地`。
     *
     * <p>**有界**：等 {@link #MAX_PREMISE_WAIT_TICKS} tick 仍不落地就**如实继续**（不假装成功、
     * 也不把非确定性换成假红）——真出问题时会以它本来的样子失败。
     *
     * <p>返回 {@code RUNNING} 且**不设 `current`** ⇒ `tick()` 下一 tick 会再进 `startStep`，
     * 而 `setupDoneForIndex` 保证开作用域/建场景/发料不会被重做。
     */
    private Status awaitGrounding(Step step) {
        if (premiseWaitTicks == 0) {
            String detail = com.dddgn.alice.task.FixturePremise.onGround(bot).detail();
            if (index == 0) {
                // 第一步没有"上一步" ⇒ 空降只可能来自**出生/传送**，这是**每轮都发生**的正常事实
                // （实测 8/8 轮都是 `on_ground=false pos=6, 64, 67`）⇒ 记 info，避免把常态刷成告警。
                BotLog.info("[Regression] premise step={} 起步时未落地（出生/传送后尚未落地，正常）"
                        + "⇒ 等落地再开始：{}", step.name(), detail);
            } else {
                BotLog.warn("[Regression] premise step={} 起步时未落地 ⇒ 等待落地（最多 {} tick）。"
                                + "⚠️ 这通常意味着**上一步把 bot 留在了半空/还在滑动**，值得单独查：{}",
                        step.name(), MAX_PREMISE_WAIT_TICKS, detail);
            }
        }
        if (++premiseWaitTicks > MAX_PREMISE_WAIT_TICKS) {
            BotLog.warn("[Regression] premise step={} 等了 {} tick 仍未落地 ⇒ **不再等，如实继续**"
                            + "（本步可能因此以其本来的样子失败）：{}",
                    step.name(), MAX_PREMISE_WAIT_TICKS,
                    com.dddgn.alice.task.FixturePremise.onGround(bot).detail());
            premiseWaitTicks = 0;
        } else {
            return Status.RUNNING;
        }
        stepTicks = 0;
        stepStarted = true;
        // **F1 地基**：本步由**自检夹具**驱动（与"玩家让做的/LLM 决定的"区分开）——
        // 终态记录与决策快照据此归因（`Driver`）。
        com.dddgn.alice.decision.Driver.set(bot, com.dddgn.alice.decision.Driver.FIXTURE);
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
        // **B 方案（2026-09-17 用户裁定「按 B 做」）**：留下我方临时方块且**未声明 KEEP** ⇒ **本步直接判红**。
        // 为什么：2026-09-17 实测过一次事故 —— `pillar_execute` 漏收尾 ⇒ 15 步之后 `craft_table` 的
        // `no_world_write`（跨 scope 的 `pendingForOwner`）红，理由与现场毫不相干 ✗。
        // 现在错误**当场**出现在漏收尾的那一步；真需要留东西的步用 `stepKeeping(...)` 显式声明。
        var ownPending = com.dddgn.alice.ledger.WorldModLedger.pendingTemporary(bot.getServer(), closed);
        if (!ownPending.isEmpty()) {
            boolean keep = currentStep() != null && currentStep().keepWorldState();
            if (keep) {
                BotLog.info("[Regression] step={} 声明 KEEP：留下 {} 条我方临时方块（放行）",
                        currentStepName(), ownPending.size());
            } else {
                record(currentStepName(), "FAIL",
                        "leaked_temporary_blocks=" + ownPending.size()
                                + "（本步留下我方临时方块却未收尾；要么收尾，要么用 stepKeeping 声明 KEEP）"
                                + " ticks=" + stepTicks);
            }
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
        // D-250/②′ 自断言：**有界重搜没修好的自写入冲突必须为 0**。
        //   · `selfwrite_conflict`：规划期发现"后面的边踩在前面挖掉的格子上"的次数（发现即已修，信息码）；
        //   · `selfwrite_unresolved`：重搜 K 次后仍不自洽的次数（**真异常** ⇒ 红）。
        // 这条断言正是 D-248 那个 bug 的常绿守卫：切片 B 那次如果没有它，就只能靠人去读日志。
        int selfWriteConflicts = k4Delta("selfwrite_conflict");
        int selfWriteUnresolved = k4Delta("selfwrite_unresolved");
        boolean k4Ok = goalBad == 0 && finalBad == 0 && selfWriteUnresolved == 0;
        if (selfWriteUnresolved > 0) {
            BotLog.warn("[SelfWrite] VIOLATION 本次电池出现**找不到自洽计划**的规划："
                            + "selfwrite_conflict={} selfwrite_unresolved={}（D-250/②′ 有界重搜没修好）",
                    selfWriteConflicts, selfWriteUnresolved);
        }
        if (!k4Ok) {
            BotLog.warn("[K4] VIOLATION 本次电池出现谓词矛盾：goal_not_standable={}"
                            + " final_segment_target_not_standable={}（可站谓词不统一的实测证据）",
                    goalBad, finalBad);
        }
        long skipped = results.values().stream().filter("SKIP"::equals).count();
        long failed = results.values().stream().filter("FAIL"::equals).count();
        // **结构完整性**（步数齐、K4 无矛盾、归属表无漂移）——三种判决共用的前提。
        boolean wellFormed = results.size() == expected && k4Ok
                && curationError.isEmpty() && phantomEntries().isEmpty();
        // ⚠️ 2026-09-14（T0-a 堵假绿）：**SKIP 不再计入 PASS**。
        // 旧判据 `(pass + skipped) == expected` 让 8 个"环境不具备就跳过"的步（`craft_probe_upgradetab`
        // / `craft_station_provision` / `craft_station_craft` / `craft_cooking` / `machine_route` /
        // `machine_station` / `machine_cycle` / `craft_machine`）在**没装模组的客户端**上
        // 一键右键打出 `→ PASS`，而其中 ~27% 什么都没断言（三路审计 E-1 实证）。
        // 现在三态判决：
        //   · **PASS**     = 每一步都真的**跑过并通过**（`pass == expected`）；
        //   · **DEGRADED** = 没有真失败，但有步因**环境不具备**被跳过 ⇒ **不是绿**，不可作为验收证据；
        //   · **FAIL**     = 存在真失败，或有步根本没出结果。
        boolean allPass = pass == expected && wellFormed;
        boolean degraded = !allPass && failed == 0 && (pass + skipped) == expected && wellFormed;
        String verdict = allPass ? "PASS"
                : degraded ? "DEGRADED(SKIP=" + skipped + "：" + skippedNames()
                        + " —— 环境不具备，本轮**不是全绿**)" : "FAIL";
        // 无头入口据此决定进程退出码（见 lastVerdict）：**只认规范三态词**，不解析给人看的那行。
        lastVerdict = allPass ? "PASS" : degraded ? "DEGRADED" : "FAIL";
        StringBuilder line = new StringBuilder();
        for (Step step : steps) {
            if (!line.isEmpty()) {
                line.append(' ');
            }
            line.append(step.name()).append('=')
                    .append(results.getOrDefault(step.name(), "SKIPPED"));
        }
        line.append(" K4=").append(k4Ok
                ? "OK(goal_not_standable=0 final_segment_not_standable=0 写入类例外=" + postWrite
                        + " 自写入冲突=" + selfWriteConflicts + ")"
                : "VIOLATION(goal_not_standable=" + goalBad
                        + " final_segment_not_standable=" + finalBad
                        + " selfwrite_unresolved=" + selfWriteUnresolved + ")");
        BotLog.info("[Regression] SUMMARY {} PROFILE={} baseline={} main={} extra_skipped={}"
                        + " (passed={}/{} skipped={}) ticks={} → {}",
                line, mode, countProfile(Profile.BASELINE), countProfile(Profile.MAIN), extraSkipped,
                pass, expected, skipped, ticks, verdict);
        if (degraded) {
            BotLog.warn("[Regression] 本轮**不是全绿**：{} 步因环境不具备被跳过（{}）"
                            + " ⇒ passed={}/{}，**不可作为验收证据**（补齐模组/场景后重跑）",
                    skipped, skippedNames(), pass, expected);
        }
        List<String> phantom = phantomEntries();
        if (!curationError.isEmpty() || !phantom.isEmpty()) {
            BotLog.warn("[Regression] 电池归属表与实跑项不一致：{} {}（见 docs/BATTERY_CURATION.md）",
                    curationError, phantom.isEmpty() ? "" : "phantom=" + phantom);
        }
        // DEGRADED 仍返回 DONE：**运行本身完成了**（"环境不具备"不是电池的失败）；
        // 判决的严重性由那一行 `→ DEGRADED(...)` 承载，绝不冒充 PASS。
        return allPass || degraded ? Status.DONE : Status.FAILED;
    }

    /** 被跳过的步名（声明序，逗号分隔）——让 `DEGRADED` 那一行**自解释**，不必翻日志找哪几步没跑。 */
    private String skippedNames() {
        return steps.stream().map(Step::name)
                .filter(name -> "SKIP".equals(results.get(name)))
                .collect(java.util.stream.Collectors.joining(","));
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

    /** 当前正在跑的步（`endStep` 里 `index++` 之前它仍指向刚跑完的那一步）。 */
    private Step currentStep() {
        return index >= 0 && index < steps.size() ? steps.get(index) : null;
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
