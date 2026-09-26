package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.session.PathExecutionResult;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.task.mining.MiningProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 掉落物收集子任务（D-072，**公用子任务**）：把指定来源产生的掉落物捡回来。
 *
 * <p>设计（`docs/MINE_MIGRATION_DESIGN.md` §4，用户裁定）：
 * <ul>
 *   <li>**来源判定**：只收集 {@code ScopeBuffer.liveDrops()}——即"由 bot 自己的破坏事件配对到的掉落物"
 *       （D-074；连锁挖掘模组的多点破坏同样覆盖）；</li>
 *   <li>**职责单一**：只收集，不挖方块、不搭桥；"怎么过去"完全交给寻路内核；</li>
 *   <li>**世界修改权限（`D-372`，用户 2026-09-21 裁定）**：`allowWorldModification=true` →
 *       `PathRequest.withWorldModification`（**真实玩法调用点默认走这条**：挖掘/伐木/回收/收集作业），
 *       false → 纯通行（`PathRequest.of`，HARD_PATH，夹具反证用）。
 *       **"有界"的含义（`D-372`）**：① **时间预算** —— 本任务的 `totalBudgetTicks`（默认
 *       {@code DEFAULT_TOTAL_BUDGET_TICKS}，**防空转的唯一闸门**）；② **格数上限默认不限**
 *       （`WriteBudget` 回退 `Caps.UNBOUNDED`，显式装订的上限照旧强制）；
 *       **③ 保护区不在这里管** —— 它是独立权限层（`CapabilityGate` → `protectionReason`
 *       ⇒ `protected_area`/`protected_block`），用户口径「保持权限管理就行」；</li>
 *   <li>**自然拾取**：站进拾取范围后等待原版拾取，**不反射、不调 `playerTouch`**；</li>
 *   <li>**best-effort**：收不到不判 FAILED，输出 `[CollectDrops] SUMMARY ...`。</li>
 * </ul>
 *
 * <p>**簇级收集（D-075 修正，用户裁定）**：原版拾取盒为玩家包围盒外扩 ±1.0 x/z、±0.5 y，
 * 一次走位会同时吸走邻近多件——逐实体"追一个等一个"既慢又少报。现在：
 * <ol>
 *   <li>把候选按**连通距离 2.0 格、|Δy| ≤ 1** 聚成簇；</li>
 *   <li>走到簇内最近成员格，等该簇成员全部消失（或 40 tick 超时；超时后**最多再换 2 次锚点**扫尾）；</li>
 *   <li>计数用**背包增量**（唯一地面真相），不再用"实体是否消失"推断；</li>
 *   <li>**守恒交叉校验**：`背包增量 == 簇起始 stack 总和 − 结束时剩余存活 stack 总和`，
 *       不等就记 `MISMATCH`（暴露"同类型被他人拾取/重复生成/背包满"等干扰），不做静默相信。</li>
 * </ol>
 */
public final class CollectDropsTask implements Task {

    /** 任务总预算（tick）。 */
    private static final int DEFAULT_TOTAL_BUDGET_TICKS = 600;
    /** 单个簇的扫描预算（tick，含走位与等待）。 */
    private static final int CLUSTER_BUDGET_TICKS = 200;

    // ==================== 扫尾预算的推导口径（R3 / D-123） ====================
    // 依据实测代价反推，而不是"按某个场景试出来一个数"：
    //   · 建立簇 / 等 pickupDelay(~10 tick) / 等掉落物落地 / 收尾 —— 固定开销；
    //   · 每个待收掉落物一次走位-吸取循环（实测 6~30 tick）；
    //   · 每允许加高一步 = 一次 PILLAR（实测 10~16 tick）+ 重锚重规划余量。
    /** 扫尾固定开销（建立簇 + 等落地 + 收尾）。 */
    public static final int SWEEP_BASE_TICKS = 120;
    /** 每个待收掉落物的代价（实测 6~30 tick，取 2 倍余量）。 */
    public static final int SWEEP_TICKS_PER_DROP = 60;
    /** 每一步加高的代价（PILLAR 实测 10~16 tick + 重锚/重规划余量）。 */
    public static final int SWEEP_TICKS_PER_GAIN_STEP = 25;

    /**
     * 扫尾阶段的 tick 预算：`固定开销 + 待收物数 × 单价 + 允许的加高步数 × 单价`。
     *
     * @param dropEstimate 进入扫尾时作用域里的存活掉落物数（下界估计，`max(1, n)`）
     * @param gainProfile  扫尾用的能力信封（null = 不允许加高）
     */
    public static int suggestedSweepTicks(int dropEstimate, MiningProfile gainProfile) {
        int drops = Math.max(1, dropEstimate);
        int steps = gainProfile == null ? 0 : gainProfile.maxGainSteps();
        return SWEEP_BASE_TICKS + drops * SWEEP_TICKS_PER_DROP + steps * SWEEP_TICKS_PER_GAIN_STEP;
    }
    /** 到位后等待自然拾取的 tick 数（原版拾取延迟 10 tick + 余量）。 */
    private static final int PICKUP_WAIT_TICKS = 40;
    /**
     * ⭐ **原版拾取盒的外扩量**（`D-375`）：玩家包围盒外扩 `±1.0 x/z、±0.5 y` 后与掉落物包围盒
     * 相交 —— 这就是原版 `Player.tick()` 的判据，即 {@link #inPickupRange}。
     *
     * <p><b>为什么这两个数只许在这里定义一次</b>：规划期"站在这格够得着吗"
     * （{@link #withinPickupReach}）与执行期"到位了吗"（{@link #inPickupRange}）必须是**同一个盒子**。
     * 各写一份就会出现一段"真够得着、却被规划期否掉"的位置（旧粗判逐轴 `1.2` vs 真实上界 `1.425`）——
     * 2026-09-21 第六轮真机实测正是如此：够得着的可站邻格 `433,87,205` 被旧粗判否掉
     * ⇒ `pickupGoalFor` 找不到任何格 ⇒ 静默把**站不住的物品自身格**当目标 ⇒ 搜索被迫挖 19 段。
     */
    private static final double PICKUP_INFLATE_XZ = 1.0D;
    private static final double PICKUP_INFLATE_Y = 0.5D;
    /** 玩家包围盒半宽 / 身高（`0.6 × 1.8`，与 `BotPlayer` 同口径）：规划期假设 bot 站**正在格中心**。 */
    private static final double PLAYER_HALF_WIDTH = 0.3D;
    private static final double PLAYER_HEIGHT = 1.8D;
    /**
     * ⭐ **收集阶段"绕远"上报阈值**（tick，`D-375`）：一个簇耗掉**一半预算**还没收手 ⇒ 报一次事件。
     *
     * <p>取一半（= 100 tick = 5 秒）的依据：正常一簇的固定开销（建立 / 等落地 / 拾取延迟 / 收尾）
     * 只有 40~80 tick，走位按每格 1~2 tick ⇒ **100 tick 还没收手 = 明确的病态**；
     * 而真机实测的病态簇是"烧满 200 tick（10 秒）后退役"。等烧满再报太晚（预算已经没了）。
     */
    private static final int CLUSTER_SLOW_TICKS = CLUSTER_BUDGET_TICKS / 2;
    /** 簇内两个掉落物的最大连通距离（格）：对应原版拾取盒 ±1.3。 */
    private static final double CLUSTER_LINK_DISTANCE = 2.0D;
    /** 簇内允许的最大垂直差（格）。 */
    private static final int CLUSTER_LINK_DY = 1;
    /** 一个簇内最多换几次锚点扫尾（覆盖簇边缘够不到的物品）。 */
    private static final int MAX_REANCHORS = 2;
    /**
     * `approach_probe` 一行里最多列几格（**有界**：枚举本身最多 48 格 = 2 层 × (环1 8 + 环2 16)，
     * 这里只是防止"每格都可站/够得着"时日志行过长）。
     */
    private static final int PROBE_MAX_ENTRIES = 40;
    /**
     * **追取上限的下限兜底**（格）：`D-074` 用户裁定 2「**超过 N 格放弃**追踪」—— ⚠️ 裁定里
     * **N 从未被指定**，`32` 是实现当初选的。实际用的上限见 {@link #chaseLimit()}：它是
     * `max(本常量, 2 × 当前作用域半径)`。
     */
    private static final double MAX_CHASE_DISTANCE = 32.0D;
    /** 等待"仍在空中下落的掉落物"落地的最大 tick 数（2026-09-10 修正）。 */
    private static final int MAX_SETTLE_TICKS = 40;

    private final BotPlayer bot;
    private final BlockPos origin;
    private final ScopeBuffer scope;
    private final Set<UUID> expectedIds;
    /**
     * ⭐ `D-344`：候选来源（`null` = `scope.liveDrops()`）。见带 `liveDropsSource` 的构造器注释。
     */
    private final java.util.function.Supplier<List<ItemEntity>> liveDropsSource;

    /**
     * ⭐ `1.4z`（2026-09-26，用户口径）：**主动拾取清单** —— 只有清单内的落物才**值得专门跑一趟**
     * （进候选、被追、被等）。清单外的落物是**白名单**：掉在地上不专门去捡，**顺手路过被原版吸取**即可。
     *
     * <p><b>用户原话</b>：「掉的石头是可捡拾物，但不是主动捡拾物，这个任务里，默认只有矿物是主动拾取物」
     * —— 真机实测（2026-09-26，345 s / 208 个收集簇）：登记的落物 **213 件里 132 件（61%）是石头族**
     * （`cobblestone 43 + diorite 38 + andesite 27 + granite 22 + gravel 2`），而**每一件都要付
     * ~9 tick 的等待**（落地 + 原版 10 tick 拾取延迟）⇒ 那 61% 是纯负担。
     *
     * <p><b>为什么必须由调用方注入而不是本类内置"是不是矿"</b>：本类是**通用**收集器，被
     * `MineJob` / `LumberJob` / `CollectJob` / 多个夹具共用 —— 伐木的"产物"是原木与树苗，
     * 清障的"产物"就是石头本身。**判据只有一个出处**：调用方自己的产物谓词
     * （矿类作业传 {@code MineProductFilter} 的入口，夹具已钉"产物口径来自生产过滤器"，
     * 见 `JobAreaGrantCheckTask:335`）。
     *
     * <p>{@code null} = **全部落物**（既有行为**逐字不变**）。
     */
    private final java.util.function.Predicate<ItemStack> activePickup;
    private final boolean allowWorldModification;
    /**
     * 收集阶段的**能力信封**（D-116）：掉落物在头顶够不到时，允许用同一份"原地加高"能力上去拿。
     * 默认 {@link MiningProfile#STANDABLE_ONLY}（不允许加高）⇒ 既有调用点行为完全不变。
     */
    private final com.dddgn.alice.task.mining.MiningProfile gainProfile;
    private com.dddgn.alice.task.mining.GainStepRunner gainRunner;
    private int gainSteps;
    private final int totalBudgetTicks;

    private int ticks;
    private String failure = "";

    // ---- 全局统计 ----
    private final Set<UUID> known = new LinkedHashSet<>();
    private final Set<UUID> firstSeen = new LinkedHashSet<>();
    private final Set<UUID> consumed = new LinkedHashSet<>();
    private final Set<UUID> retired = new LinkedHashSet<>();
    private final Map<UUID, Integer> lastSeenStack = new HashMap<>();
    private final Map<UUID, ItemEntity> liveById = new LinkedHashMap<>();
    /** 期望物品数 = 首次见到各实体时的 stack 数量之和（合并不会改变它）。 */
    private int expectedItems;
    /** 实际进背包的物品数 = 各次扫描的背包增量之和。 */
    private int collectedItems;
    private int clustersSwept;
    private int unreachableCount;
    private int pickupTimeoutCount;
    private int mismatchCount;
    /** 被 `DropPolicy` 拒绝而留下的掉落物数（J-10：与 unreachable/timeout 分开记）。 */
    private int policyBlockedCount;
    /**
     * ⭐ `D-375`：**没有"可站且够得着"的格**而被如实退休的物品数（与 `unreachable` 分开记）。
     *
     * <p>它与 `unreachable` 是两件事：`unreachable` = "走位试过、失败了"；本计数 = "**根本不许规划**"
     * （把站不住的格当目标 = 要求寻路挖进去）。混在一起就看不出"是不是又有人在挖穿地形捡东西"。
     */
    private int noApproachCount;
    /** `D-375`：上报过的 `PICKUP_SLOW` / `PICKUP_DETOUR` 次数（每簇各至多一次）。 */
    private int slowEmits;
    private int detourEmits;

    // ---- 当前簇扫描状态 ----
    private List<UUID> clusterIds;
    private BlockPos anchor;
    private Map<Item, Integer> typeBefore;
    private int clusterStartSum;
    private int sweepTicks;
    private int waitTicks;
    private int reanchors;
    private int settleTicks;
    /** `D-375`：本簇是否已上报过"慢"/"在改造地形"（每簇至多一次 = 阈值的滞回形式）。 */
    private boolean slowReported;
    private boolean detourReported;

    /**
     * ⭐ **本簇已证明"模型说够得着、执行期够不到"的目标格**（3-b/D2，2026-09-21）。
     *
     * <p><b>为什么需要它</b>：`withinPickupReach` 建模的是「bot **站正在格中心**」，而执行期 bot 可以
     * 停在格内偏 0.19~0.49 处（`EXACT` 容差），掉落物又能停在自己那格的远角（偏移 ~0.375）
     * ⇒ 存在一段「**模型说够得着、`inPickupRange` 判否**」的几何。真机第七/八轮实测（`§12②`、`§13.1`）：
     * 收集器走到那格、够不到 ⇒ `reanchor` 又算出**同一个**格（没有排除集）⇒ 换锚点两轮后如实退休
     * `not_in_pickup_range`，物品留在地上（12/93 件）。
     *
     * <p><b>口径</b>：这一格不是"不可达"，而是"**站上去不够**"⇒ 本簇内不再把它当选址，
     * 换**次优**格（`pickupGoalFor` 的同一个最近优先序，只是跳过已失败的格）。簇结束即清空
     * （不跨簇记忆 —— 下一次簇的物品位置/朝向都可能变了，跨簇记忆会变成"永久拉黑"）。
     */
    private final Set<BlockPos> failedGoalCells = new HashSet<>();
    /** 累计被排除过的"够不到"目标格数（观测用，进 `SUMMARY goal_excluded=`）。 */
    private int goalExcludedCount;
    /**
     * `D-375`：本簇开始时的**世界改动运行账**（`TaskMetrics`）—— 用来判"这一簇在改造地形"。
     *
     * <p>为什么用运行账增量，而不是"读走位执行过的 Movement 类型"（自检实测踩到过）：
     * `PathSession.executedMovementTypes()` 是在**某一段成功之后**才追加的，而"为捡一件东西挖一格"
     * 常常正好是**最后一段** ⇒ 收集器在物品入包的那一 tick 就结束本簇（`members.isEmpty()`），
     * 之后再没机会读那个集合 ⇒ 破了 2 格石墙、`detour_events` 仍是 0 ✗。
     * 而运行账是在**真的扣掉一次写入预算的那一刻**记的（`WriteBudget.consumeBreak ⇒ TaskMetrics.noteBreak`）
     * ⇒ 它是"已经改了世界"的**地面真值**，与走位内部簿记无关。
     *
     * <p>口径诚实说明：运行账是**进程级**计数 ⇒ 它衡量的是"本簇这段时间里世界改了几格"。
     * 今天成立（作业是相位串行的：`MineJob.collectPhase()` 只 tick 收集器，矿工不在跑），
     * 但事件文案必须说清这是**增量**，不是"本条路径破的格数"。
     */
    private com.dddgn.alice.bot.TaskMetrics.Snapshot worldChangesBefore;
    /** `D-375`：最近一次**真的拿去规划**的目标格（夹具/取证用：它必须可站）。 */
    private BlockPos lastGoalFoot;
    private PathRetryRunner runner;

    public CollectDropsTask(BotPlayer bot, BlockPos origin, ScopeBuffer scope,
                            List<UUID> expectedIds, boolean allowWorldModification) {
        this(bot, origin, scope, expectedIds, allowWorldModification, DEFAULT_TOTAL_BUDGET_TICKS);
    }

    public CollectDropsTask(BotPlayer bot, BlockPos origin, ScopeBuffer scope,
                            List<UUID> expectedIds, boolean allowWorldModification, int totalBudgetTicks) {
        this(bot, origin, scope, expectedIds, allowWorldModification, totalBudgetTicks,
                com.dddgn.alice.task.mining.MiningProfile.STANDABLE_ONLY);
    }

    /**
     * @param gainProfile 收集阶段的能力信封（D-116）：允许"原地加高"时，头顶够不到的掉落物
     *                    可以搭上去拿（放置落在同一作用域内，由建拆同权阶段收回）。
     */
    public CollectDropsTask(BotPlayer bot, BlockPos origin, ScopeBuffer scope,
                            List<UUID> expectedIds, boolean allowWorldModification, int totalBudgetTicks,
                            com.dddgn.alice.task.mining.MiningProfile gainProfile) {
        this(bot, origin, scope, expectedIds, allowWorldModification, totalBudgetTicks, gainProfile, null);
    }

    /**
     * ⭐ `D-344`：**候选来源可换**（本次新增）—— 默认 `null` = 沿用 `scope.liveDrops()`（"我方登记在册"），
     * **既有调用方行为逐字不变**。
     *
     * <p><b>为什么必须能换</b>：{@code scope.liveDrops()} 只包含**归属我方**的掉落物
     * （我方破坏直接/间接产生的，`ScopeBuffer` 登记）。而 `D-344` 要收的是**区域地面上的旧树苗**
     * —— 它们是 `FOREIGN`（`DropPolicy` 三态里的第三态）⇒ **根本进不了候选集**，
     * 于是不管有没有收集授权，本任务都会"一件没看见就 DONE"（2026-09-19 端到端夹具实测：
     * `sweep 结束 status=DONE 实际入包=0 区内剩余=9`，三连零进展 ⇒ 如实失败）。
     *
     * <p><b>换了来源不放松授权</b>：能不能捡仍由 {@code DropPolicy.mayCollect}（{@code :632}）把关
     * ⇒ 两层各司其职：**来源 = "我想收哪些"**、**授权 = "我准不准收"**（`FOREIGN` 要靠
     * `CollectGrant` 变成 `GRANTED_AREA` 才放行）。
     *
     * @param liveDropsSource 候选来源（`null` = `scope::liveDrops`）；供应商每 tick 重新问一次，
     *                        所以调用方可以**实时重扫**（比如"区域内清单内落物"）
     */
    public CollectDropsTask(BotPlayer bot, BlockPos origin, ScopeBuffer scope,
                            List<UUID> expectedIds, boolean allowWorldModification, int totalBudgetTicks,
                            com.dddgn.alice.task.mining.MiningProfile gainProfile,
                            java.util.function.Supplier<List<ItemEntity>> liveDropsSource) {
        this(bot, origin, scope, expectedIds, allowWorldModification, totalBudgetTicks, gainProfile,
                liveDropsSource, null);
    }

    /**
     * ⭐ `1.4z`（2026-09-26）：带**主动拾取清单**的便捷重载（其余全用默认：预算 `DEFAULT_TOTAL_BUDGET_TICKS`
     * / `STANDABLE_ONLY` / 候选 = 作用域在册落物）。见 {@link #activePickup} 字段的注释。
     */
    public CollectDropsTask(BotPlayer bot, BlockPos origin, ScopeBuffer scope,
                            List<UUID> expectedIds, boolean allowWorldModification,
                            java.util.function.Predicate<ItemStack> activePickup) {
        this(bot, origin, scope, expectedIds, allowWorldModification, DEFAULT_TOTAL_BUDGET_TICKS,
                com.dddgn.alice.task.mining.MiningProfile.STANDABLE_ONLY, null, activePickup);
    }

    /**
     * @param activePickup **主动拾取清单**（`null` = 全部落物 ⇒ 既有行为逐字不变）；
     *                     清单外的落物**不进候选、不进账**（"顺手捡"由原版吸取范围负责，不需要本类做事）
     */
    public CollectDropsTask(BotPlayer bot, BlockPos origin, ScopeBuffer scope,
                            List<UUID> expectedIds, boolean allowWorldModification, int totalBudgetTicks,
                            com.dddgn.alice.task.mining.MiningProfile gainProfile,
                            java.util.function.Supplier<List<ItemEntity>> liveDropsSource,
                            java.util.function.Predicate<ItemStack> activePickup) {
        this.gainProfile = gainProfile == null
                ? com.dddgn.alice.task.mining.MiningProfile.STANDABLE_ONLY : gainProfile;
        this.bot = bot;
        this.origin = origin.immutable();
        this.scope = scope;
        this.expectedIds = expectedIds == null ? Set.of() : Set.copyOf(expectedIds);
        this.allowWorldModification = allowWorldModification;
        this.totalBudgetTicks = Math.max(40, totalBudgetTicks);
        this.liveDropsSource = liveDropsSource;
        this.activePickup = activePickup;
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(origin);
    }

    /**
     * **J-10（2026-09-16）**：有掉落物被策略拒绝时必须**如实上报**（否则"收干净了"与"有几件不许捡"
     * 在决策层眼里一样）。落进 D-134 的 `task_terminal_reason` 日志与决策快照。
     */
    @Override
    public String terminalReason() {
        return policyBlockedCount > 0 ? "policy_blocked:" + policyBlockedCount : "";
    }

    @Override
    public String failureReason() {
        return failure;
    }

    /** 实际进背包的物品数（背包增量口径）。 */
    public int collected() {
        return collectedItems;
    }

    /**
     * `D-375`：**因"没有可站且够得着的格"而被如实退休**的物品数（夹具/门禁用）。
     *
     * <p>它与 `unreachable` 是两件事：这一档是"**根本不许规划**"（把站不住的格当目标 = 让寻路挖进去）。
     */
    public int noApproachRetired() {
        return noApproachCount;
    }

    /** `D-375`：上报给决策层的 `PICKUP_SLOW` 事件数（夹具/门禁用）。 */
    public int slowEventEmits() {
        return slowEmits;
    }

    /** `D-375`：上报给决策层的 `PICKUP_DETOUR` 事件数（夹具/门禁用）。 */
    public int detourEventEmits() {
        return detourEmits;
    }

    /**
     * 3-b/D2：本任务累计**排除过多少个"站上去也够不到"的目标格**（夹具/门禁用）。
     *
     * <p>判据意义：这个数 > 0 = "模型说够得着、执行期判否"的几何**真的发生过**，而且收集器
     * **没有立刻放弃**（旧行为：换个锚点又算出同一个格，两轮后如实退休，物品留在地上）。
     */
    public int goalExcludedTotal() {
        return goalExcludedCount;
    }

    /**
     * `D-375`：最近一次**真的拿去规划**的目标格（`null` = 本任务从未规划过）。
     *
     * <p>为什么要暴露它：夹具必须能判"收集器挑的目标**可站**吗" —— 只看世界的最终状态是**不够**的，
     * 实测（2026-09-21 注入复现）会漏：目标不可站 ⇒ 计划里带一条破格边，但物品在**破格之前**
     * 就被原版拾取范围捞走了 ⇒ 世界零改动、看起来全绿，而缺陷（目标是站不住的格）已经在计划里了。
     */
    public BlockPos lastGoalFoot() {
        return lastGoalFoot;
    }

    @Override
    public Status tick() {
        if (++ticks > totalBudgetTicks) {
            return finish("timeout");
        }
        List<ItemEntity> live = refreshCandidates();
        trackDisappearances(live);

        if (clusterIds == null) {
            if (live.isEmpty()) {
                return finish("done");
            }
            beginCluster(live);
            return Status.RUNNING;
        }

        List<ItemEntity> members = liveMembers(live);
        if (members.isEmpty()) {
            endCluster(false);
            return Status.RUNNING;
        }
        // 掉落物**还不在接地状态**时不要追：它的"当前格"可能够不到，
        // 追它会得到假的 MOVEMENT_FAILED（2026-09-10 两次客户端实测：
        // ① 6/7 根原木时追下落中的物品；② 4/4 根全砍完但最后一根的掉落物被退役 → inventoryDelta=3）。
        // 等它落定（≤ MAX_SETTLE_TICKS）——落在簇内自然会被拾取，或随后正常走位去捡。
        if (members.stream().anyMatch(CollectDropsTask::isAirborne)
                && ++settleTicks <= MAX_SETTLE_TICKS) {
            if (settleTicks == 1) {
                BotLog.info("[CollectDrops] settling members={}（等待空中的掉落物落地）", members.size());
            }
            cancelRunner();
            return Status.RUNNING;
        }

        if (++sweepTicks > CLUSTER_BUDGET_TICKS) {
            for (ItemEntity member : members) {
                retire(member.getUUID(), "cluster_budget");
            }
            endCluster(true);
            return Status.RUNNING;
        }

        // ⭐ `D-375`：把"这一簇在磨 / 在改造地形"上报给决策层（**在烧完预算之前**）。
        reportCollectSymptoms(members);

        // 0) 正在加高（D-116）：先把它跑完 —— 完成后重锚并重试走位
        if (gainRunner != null) {
            com.dddgn.alice.task.mining.GainStepRunner.State gainState = gainRunner.tick();
            if (gainState == com.dddgn.alice.task.mining.GainStepRunner.State.RUNNING) {
                return Status.RUNNING;
            }
            gainRunner = null;
            if (gainState == com.dddgn.alice.task.mining.GainStepRunner.State.DONE) {
                gainSteps++;
                BotLog.info("[CollectDrops] gain_done item={} steps={}/{} foot={}",
                        members.get(0).getUUID(), gainSteps, gainProfile.maxGainSteps(),
                        bot.blockPosition().toShortString());
                if (!reanchor(members)) {
                    retireNoApproach(members);
                }
                return Status.RUNNING;
            }
            BotLog.warn("[CollectDrops] gain_failed steps={}/{} → 如实退役",
                    gainSteps, gainProfile.maxGainSteps());
            for (ItemEntity member : members) {
                retire(member.getUUID(), "gain_failed");
            }
            endCluster(true);
            return Status.RUNNING;
        }

        // 0) 尚未走位、且还没进入拾取范围 → 建路径（走位优先；不建就会"原地放弃"）
        if (runner == null && members.stream().noneMatch(this::inPickupRange)) {
            // D-114：寻路目标必须是"**够得着掉落物的可站格**"，而不是掉落物所在格。
            // 反例（2026-09-11 实测）：支撑块被拆后掉落物停在平台格 (23,64,189) ✓，而锚点是
            // 刚拆掉的支撑块所在格 (23,64,190)——空气+下面也是空气 ⇒ 不可站 ⇒ UNREACHABLE ⇒ 退役残留。
            if (!normalizeAnchor(members)) {
                retireNoApproach(members);
                return Status.RUNNING;
            }
            // ⭐ **不变式（`D-375`）**：收集请求的 `GoalFoot` **必须可站**。
            // `normalizeAnchor` 已经保证了它；这里是"响了就说明上游破了"的硬闸 ——
            // 一个站不住的目标 = 要求寻路**挖进去**（真机 19 段挖掘回环的成因）。
            if (!isStandableCell(anchor)) {
                BotLog.warn("[CollectDrops] INVARIANT_VIOLATION goal_not_standable goal={} itemPos={}"
                                + "（锚点规范化破了：目标格站不住 ⇒ 拒绝规划，如实退休）",
                        anchor.toShortString(), members.get(0).blockPosition().toShortString());
                retireNoApproach(members);
                return Status.RUNNING;
            }
            lastGoalFoot = anchor.immutable();
            PathRequest request = allowWorldModification
                    ? PathRequest.withWorldModification(bot.getUUID().toString(), MovementHelper.footCell(bot.serverLevel(), bot), anchor, "collect-drops")
                    : PathRequest.of(bot.getUUID().toString(), MovementHelper.footCell(bot.serverLevel(), bot), anchor, "collect-drops");
            runner = new PathRetryRunner(bot, request, PathRetryRunner.DEFAULT_MAX_REPLANS,
                    "collect-" + anchor.getX() + "_" + anchor.getY() + "_" + anchor.getZ());
            BotLog.info("[CollectDrops] sweep_start anchor={} members={} feet={} worldMod={}",
                    anchor.toShortString(), members.size(), bot.blockPosition().toShortString(),
                    allowWorldModification);
        }

        // 1) 走位优先：runner 未结束就继续走完（原版拾取会在路过时自动发生，
        //    提前取消寻路会让 bot 停在"看着够得着、实际差半格"的位置上，D-076 修正）
        if (runner != null) {
            PathRetryRunner.State state = runner.tick();
            if (state == PathRetryRunner.State.RUNNING) {
                return Status.RUNNING;
            }
            if (state == PathRetryRunner.State.DONE) {
                cancelRunner();
                // **不提前 return**（2026-09-10 修正）：当目标格就是 bot 自己所在格时，
                // 请求退化成"从自己走自己" → `movements=0 / REACHED` → 本分支每 tick 命中一次，
                // 于是重建同一个请求、空转到 CLUSTER_BUDGET_TICKS 才放弃。
                // 实测：掉落物从浮空平台掉到相邻下方 (24,64,189)，收集器空转 201 tick 后
                // reason=cluster_budget，使 mine_regression 的 exec_floating 假失败。
                // 现在落到下方既有的"到位判定"：在拾取范围内就等，
                // 不在就 reanchor 到物品**当前**位置（reanchor 一直存在，只是此前走不到）。
            } else {
                PathExecutionResult result = runner.result();
                String reason = result == null ? "unreachable" : result.status().name();
                // D-116：走位到不了（典型：掉落物在**头顶**的树冠里）→ 先用能力信封里的"加高"上去够，
                // 用尽或不可行才退役。与 D-114（换可站格）是两个不同手段：一个横着挪、一个往上抬。
                if (startGainToward(members)) {
                    return Status.RUNNING;
                }
                for (ItemEntity member : members) {
                    retire(member.getUUID(), reason);
                }
                endCluster(true);
                return Status.RUNNING;
            }
        }

        // 2) 已到位：只有**真的进入原版拾取范围**才等待（否则继续换锚点/如实退休）
        if (members.stream().anyMatch(this::inPickupRange)) {
            if (++waitTicks >= PICKUP_WAIT_TICKS) {
                if (reanchors < MAX_REANCHORS) {
                    if (reanchor(members)) {
                        return Status.RUNNING;
                    }
                    retireNoApproach(members);   // `D-375`：没有可站的可达格 ⇒ 不许规划，如实退休
                } else {
                    for (ItemEntity member : members) {
                        retire(member.getUUID(), "pickup_timeout");
                    }
                    endCluster(true);
                }
            }
            return Status.RUNNING;
        }

        // 3) 到位但够不到（物品卡在够不着的位置）→ 换最近成员再试，用尽后如实退休
        if (reanchors < MAX_REANCHORS) {
            // 3-b/D2：**这一格已经被证明"站上去也够不到"** ⇒ 记进本簇排除集，下一轮必须换**次优**格。
            // 没有这一条时 `reanchor` 会算出同一个格（真机第七/八轮：4 次同格 ≈ 5 秒，物品留在地上）。
            excludeFailedGoal();
            if (reanchor(members)) {
                return Status.RUNNING;
            }
            retireNoApproach(members);           // `D-375`：同上
            return Status.RUNNING;
        }
        logApproachProbe("not_in_pickup_range", nearestMember(members));
        for (ItemEntity member : members) {
            retire(member.getUUID(), "not_in_pickup_range");
        }
        endCluster(true);
        return Status.RUNNING;
    }

    /**
     * 3-b/D2：把"站上去也够不到"的**当前目标格**记进本簇排除集（下一轮 `reanchor` 就会取次优格）。
     *
     * <p>触发条件（只在这一处）：走位**已完成**、`members` 里**没有一件**进入 `inPickupRange`，
     * 而且还有换锚点余额 —— 即"位置到了、够不到"，不是"走不到"（走不到走 `retire(reason)` 那条）。
     */
    private void excludeFailedGoal() {
        if (anchor == null || !failedGoalCells.add(anchor.immutable())) {
            return;
        }
        goalExcludedCount++;
        BotLog.warn("[CollectDrops] goal_excluded cell={} botFeet={}（模型说够得着、执行期 `inPickupRange`"
                        + " 判否 ⇒ 本簇不再选这一格、换次优；真机定量：bot 离心可达 ~0.49 + 物品压格角"
                        + " ~0.375 > 模型余量）",
                anchor.toShortString(), bot.blockPosition().toShortString());
    }

    /**
     * 换到**离 bot 最近的存活成员**作为新锚点（原样重走）。
     *
     * @return `true` = 新锚点可站（可以继续规划）；`false` = 找不出可站且够得着的格 ⇒ 调用方如实收尾
     */
    private boolean reanchor(List<ItemEntity> members) {
        reanchors++;
        if (!normalizeAnchor(members)) {
            return false;
        }
        waitTicks = 0;
        runner = null;
        BotLog.info("[CollectDrops] reanchor cluster_anchor={} remaining={} reanchors={}/{}",
                anchor.toShortString(), members.size(), reanchors, MAX_REANCHORS);
        return true;
    }

    /**
     * ⭐ `D-375`：**没有"可站且够得着"的格** ⇒ 不许规划（把站不住的格当目标 = 要求寻路挖进去），
     * 逐件如实退休并收尾。
     *
     * <p>为什么不能"先规划看看"：寻路器**有能力**满足这种目标 —— 破掉格子头顶的方块就能站进去
     * （`D-374` 刚把那条边补上）⇒ 代价是"为捡一件掉落物挖穿地形"（真机 19 段 / 破 6 格 / 10 秒）。
     * 够不着就是够不着，如实退休（`no_standable_approach`）比挖穿地形诚实得多。
     */
    private void retireNoApproach(List<ItemEntity> members) {
        logApproachProbe("no_standable_approach", nearestMember(members));
        for (ItemEntity member : members) {
            retire(member.getUUID(), "no_standable_approach");
        }
        endCluster(true);
    }

    /**
     * 掉落物在**头顶**且走位不可达时，开一次"原地加高"（D-116，复用共享 {@code GainStepRunner}）。
     *
     * <p>只对"明显在上方"的成员动手（否则交给 D-114 的换格逻辑）；预算由信封的
     * `maxGainSteps` 决定；加高产生的放置记在同一作用域里 ⇒ 由任务的建拆同权阶段收回。
     */
    private boolean startGainToward(List<ItemEntity> members) {
        if (!gainProfile.mayGain() || gainSteps >= gainProfile.maxGainSteps()) {
            return false;
        }
        ItemEntity nearest = members.stream()
                .min(java.util.Comparator.comparingDouble(item -> bot.distanceToSqr(item)))
                .orElse(members.get(0));
        if (nearest.getY() <= bot.getY() + 0.5D) {
            return false;   // 不在头顶 → 不靠加高解决
        }
        // D-179（与 MineTask 同一守卫）：**加高只能在目标附近用**。原判据只看"在头顶"（竖直），
        // 远处高处的掉落物也满足 ⇒ 会在与它无关的位置搭柱子。这里补水平前提（判据单一定义在 MiningTuning）。
        if (!com.dddgn.alice.task.mining.MiningTuning
                .gainHorizontallyReachable(bot, nearest.blockPosition())) {
            BotLog.warn("[CollectDrops] gain_refused item={} 水平超出触及 ⇒ 不异地加高",
                    nearest.blockPosition().toShortString());
            return false;
        }
        BotLog.info("[CollectDrops] gain_start item={} itemPos={} botFeet={} steps={}/{}",
                nearest.getUUID(), nearest.blockPosition().toShortString(),
                bot.blockPosition().toShortString(), gainSteps + 1, gainProfile.maxGainSteps());
        gainRunner = new com.dddgn.alice.task.mining.GainStepRunner(bot, gainProfile,
                com.dddgn.alice.action.WriteGrant.of("collect-drops",
                        com.dddgn.alice.action.WriteReason.STEP_PLACEMENT));
        return true;
    }

    /**
     * 把当前锚点（= 最近掉落物的所在格）规范化成"**够得着它的可站格**"（D-114）。
     *
     * <p>为什么必须：掉落物常常停在**站不住的格**上或旁边——刚被拆掉的支撑块所在格（空气+下方空气）、
     * 1×1 竖井口、台阶边缘、悬空块上方……此时若照原样去寻路，规划器会如实报 `UNREACHABLE`
     * （它不会为"走到一个站不住的格"编路径），于是物品被退役、材料留在世界里。
     * 实测两例：J7 掉在壁柱顶（够不到）②D-112 支撑块拆除后掉落物落在平台格而锚点在井口。
     *
     * <p>判据：优先用掉落物所在格（今天的行为，可站时完全不变）；否则在其周围
     * （水平 4 邻、y ∈ {0,+1,-1}，必要时半径 2）找**最近的可站格**，且与掉落物在拾取半径内。
     *
     * @return `true` = 锚点已设定为**可站**格（可以据此规划）；`false` = 一个都没有
     *         ⇒ 调用方**不许**规划（规划到一个站不住的格 = 要求寻路"挖进去"，`D-375`），必须如实收尾
     */
    private boolean normalizeAnchor(List<ItemEntity> members) {
        ItemEntity nearest = members.stream()
                .min(java.util.Comparator.comparingDouble(item -> bot.distanceToSqr(item)))
                .orElse(members.get(0));
        BlockPos itemCell = nearest.blockPosition().immutable();
        BlockPos goal = pickupGoalFor(nearest, itemCell);
        if (goal == null) {
            // ⭐ `D-375`（2026-09-21 第六轮真机）：**这里原先静默回退到"物品自身格"**——
            // 那一格站不住（头位被挡）⇒ 搜索为了到达它只能**挖穿天花板**（真机：19 段计划、破 6 格、
            // 烧满 200 tick 簇预算）。现在如实拒绝，并**留下日志**（旧行为一行都没有）。
            BotLog.warn("[CollectDrops] no_standable_approach item={} itemPos={} itemY={} botFeet={}"
                            + "（物品所在格站不住，周围 2 格内也没有「可站且够得着」的格"
                            + " ⇒ 不许把不可站格当寻路目标（那等于让寻路挖进去），如实换锚点/退休）",
                    nearest.getUUID(), itemCell.toShortString(),
                    String.format(java.util.Locale.ROOT, "%.3f", nearest.getY()),
                    bot.blockPosition().toShortString());
            return false;
        }
        anchor = goal;
        if (!goal.equals(itemCell)) {
            BotLog.info("[CollectDrops] goal_shift item={} itemPos={} goal={}（掉落物所在格不可站 → 走到够得着的可站格）",
                    nearest.getUUID(), itemCell.toShortString(), goal.toShortString());
        }
        return true;
    }

    /**
     * 够得着该掉落物的可站格；掉落物所在格可站时原样返回。
     *
     * @return `null` = **找不到**（既不是"物品所在格"，也不是任何邻格）—— 调用方必须如实收尾，
     *         **不许**退回物品自身格（`D-375`：那一格正是"站不住"才要搜索的）
     */
    private BlockPos pickupGoalFor(ItemEntity item, BlockPos itemCell) {
        if (isStandableCell(itemCell) && !failedGoalCells.contains(itemCell)) {
            return itemCell;
        }
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int radius = 1; radius <= 2 && best == null; radius++) {
            for (BlockPos cell : approachCandidates(itemCell, radius)) {
                // 3-b/D2：本簇已证明"站上去也够不到"的格不再选 ⇒ 让位给次优格
                // （没有这一条时 `reanchor` 会算出**同一个**格，两轮后如实退休，物品留在地上）
                if (failedGoalCells.contains(cell)) {
                    continue;
                }
                if (!isStandableCell(cell) || !withinPickupReach(cell, item)) {
                    continue;
                }
                double d = cell.distSqr(itemCell);
                if (d < bestDist) {
                    bestDist = d;
                    best = cell.immutable();
                }
            }
        }
        return best;
    }

    /**
     * ⭐ **候选格枚举的唯一定义**（3-b/D0，2026-09-21）：`pickupGoalFor` 的**搜索**与
     * {@link #logApproachProbe} 的**取证**必须枚举**同一批格**——否则探针会"证错"。
     *
     * <p><b>为什么必须共用一个方法（真机教训）</b>：第八轮真机探针打出 `standable=0`，
     * 而 bot **自己就站在**物品下方那一层（`dy=-1`）的可站格上 —— 因为探针只扫 `dy=0`，
     * 而搜索扫 `dy ∈ {0,-1}`（`pickupGoalFor` 的 `for (int dy = 0; dy >= -1; dy--)`）。
     * 那份读数误导了当场的诊断（"一格都不可站" vs "bot 正站在可站格上"）。
     * 与 `D-375` 的 `withinPickupReach` 同一条纪律：**探针不许自己写第二份判据**。
     *
     * <p><b>口径</b>（逐字等于搜索一直在用的几何，行为不变）：
     * <ul>
     *   <li>**层**：`dy ∈ {0, -1}`（物品所在层 + 它下面那一层）；</li>
     *   <li>**环**：`radius=1` = 八邻（含斜角）；`radius=2` = 该环 16 格（4 正交 + 4 斜角，
     *       与搜索的 `|dx| == radius || |dz| == radius` 一致）；</li>
     *   <li>**顺序**：`dx` → `dz` → `dy`（= 搜索的遍历序 ⇒ 距离并列时"先遇到的赢"这条语义不变）。</li>
     * </ul>
     *
     * <p>包可见（`static`）是**故意**的：夹具要断言"搜索的层 = 探针的层"，必须能直接调它。
     */
    static List<BlockPos> approachCandidates(BlockPos itemCell, int radius) {
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                    continue;   // 只看这一圈的边（radius=1 时 = 八邻；radius=2 时 = 16 格环）
                }
                for (int dy = 0; dy >= -1; dy--) {
                    out.add(itemCell.offset(dx, dy, dz));
                }
            }
        }
        return out;
    }

    /** 可站：脚下有支撑 + 脚位/头位可穿过（与挖掘站位同一口径）。 */
    private boolean isStandableCell(BlockPos cell) {
        return com.dddgn.alice.task.mining.StandingPointSelector
                .isStandable(bot.serverLevel(), cell);
    }

    /**
     * ⭐ 站在该格（**站正在格中心**）能否拾取到该掉落物 —— **与 {@link #inPickupRange} 同一谓词**。
     *
     * <p><b>`D-375`（2026-09-21）：这里原先是一段粗判</b>（`|格中心 − 物品| ≤ 1.2` 逐轴），
     * 而真实判据是"玩家包围盒外扩 `±1.0 x/z、±0.5 y` 与掉落物包围盒相交"，逐轴上界是
     * `0.125(物品半宽) + 0.3(玩家半宽) + 1.0 = 1.425` ⇒ **1.2 &lt; 1.425**，于是存在一段
     * "**真够得着、却被规划期否掉**"的位置。第六轮真机实测就被这一段打中：
     * 掉落物在 `433,87,206`（自己那格站不住），够得着的可站邻格 `433,87,205` 距物品中心 1.3~1.4
     * ⇒ 被粗判否掉 ⇒ `pickupGoalFor` 返回 null 后被静默换成物品自身格 ⇒ 挖 19 段。
     *
     * <p>包可见（`static`）是**故意**的：夹具必须复用它，不许自己再写一份近似判据
     * （"可规划即可执行"的同一条纪律：夹具若用另一套判据，就会**自己骗自己**）。
     */
    static boolean withinPickupReach(BlockPos cell, ItemEntity item) {
        double cx = cell.getX() + 0.5D;
        double cz = cell.getZ() + 0.5D;
        AABB standing = new AABB(cx - PLAYER_HALF_WIDTH, cell.getY(), cz - PLAYER_HALF_WIDTH,
                cx + PLAYER_HALF_WIDTH, cell.getY() + PLAYER_HEIGHT, cz + PLAYER_HALF_WIDTH);
        return reachesFrom(standing, item);
    }

    /**
     * ⭐ **拾取判定本体**（`D-375`，2026-09-21）：给定一个**玩家包围盒**，它外扩 `±1.0 x/z、±0.5 y`
     * 后是否与掉落物包围盒相交 —— 与原版 `Player.tick()` 逐字一致（掉落物落地后中心约在方块底面
     * +0.125，用"到方块中心距离"判会出现"看着到位、实际差半格"的假到位）。
     *
     * <p><b>为什么单独抽出来</b>（3-b/D0）：`withinPickupReach`（**站着建模**，用格中心造盒子）与
     * `inPickupRange`（**执行期判据**，用 bot 的真实盒子）必须是**同一个相交谓词**，
     * 而夹具要能**用生产定义**断言"真机那个几何下：模型说够得着、真实盒子够不到"
     * —— 它必须拿到 `bot.getBoundingBox()` 的那条路径，而不是自己再抄一份外扩常量。
     *
     * <p>包可见（`static`）是**故意**的（与 `withinPickupReach` 同一条纪律）。
     */
    static boolean reachesFrom(AABB playerBox, ItemEntity item) {
        return playerBox.inflate(PICKUP_INFLATE_XZ, PICKUP_INFLATE_Y, PICKUP_INFLATE_XZ)
                .intersects(item.getBoundingBox());
    }

    /**
     * 是否已进入**原版拾取范围**：与原版 `Player.tick()` 的判定完全一致——
     * 玩家包围盒外扩 `1.0 x/z、0.5 y` 与掉落物包围盒相交。
     */
    private boolean inPickupRange(ItemEntity item) {
        return reachesFrom(bot.getBoundingBox(), item);
    }

    // ---- 候选与观测 ----

    /** 当前仍在范围、未被淘汰的候选；同时刷新 known/expected/lastSeenStack。 */
    private List<ItemEntity> refreshCandidates() {
        liveById.clear();
        List<ItemEntity> result = new ArrayList<>();
        double limit = chaseLimit();
        // `D-344`：候选来源可换（默认仍是"我方登记在册"的 `scope.liveDrops()`）
        List<ItemEntity> source = liveDropsSource == null ? scope.liveDrops() : liveDropsSource.get();
        for (ItemEntity item : source == null ? List.<ItemEntity>of() : source) {
            UUID id = item.getUUID();
            if (consumed.contains(id) || retired.contains(id)) {
                continue;
            }
            // ⭐ `1.4z`：**主动拾取清单**以外的落物不进候选（也不进 `known`/`expected` 账 —— 让本类的
            // 计数口径与作业的"产物进包"口径对齐）。"顺手捡"由原版吸取范围负责，不需要我们做任何动作。
            if (activePickup != null && !activePickup.test(item.getItem())) {
                continue;
            }
            known.add(id);
            if (firstSeen.add(id)) {
                expectedItems += item.getItem().getCount();
            }
            lastSeenStack.put(id, item.getItem().getCount());
            liveById.put(id, item);
            if (!expectedIds.isEmpty() && !expectedIds.contains(id)) {
                continue;
            }
            if (bot.distanceToSqr(item) > limit * limit) {
                retire(id, "too_far");
                liveById.remove(id);
                continue;
            }
            result.add(item);
        }
        return result;
    }

    /**
     * ⭐ **追取上限**（`D-346`，2026-09-20）：`max(MAX_CHASE_DISTANCE, 2 × 当前作用域半径)`。
     *
     * <p><b>为什么必须由作用域派生</b>：能进 `liveDrops()` 的落物**只可能是本作业作用域内登记的**
     * （{@link com.dddgn.alice.perception.ScopeBuffer} 的 `inScope` 检查）⇒ 它到 bot 的距离本来就不会
     * 超过 ~`2 × 半径`。而旧实现把 N 写死成 `32`，比 `MineJob` 自己的作用域直径（`2 × SCAN_RADIUS(24)`
     * = **48**）还小 ⇒ **自己挖出来的产物被自己的上限退休**（取证夹具 `mine_far_drop` 实测
     * `[CollectDrops] retire … reason=too_far itemPos=3240`，40 格外的那件产物在第一 tick 就被永久丢弃
     * ⇒ `gained < minedCount` ⇒ `FAILED product_not_collected`）。
     *
     * <p><b>裁定口径不变</b>：`D-074` 裁定 2 是"超过 N 格放弃"，**N 由实现定**；本次把 N 从"拍一个 32"
     * 改成"本作业作用域直径（下限 32 兜底）"—— "不追世界另一头"仍然成立：**登记在册 ⇒ 本来就在
     * 本作业作用域里**。无活动作用域（`0`）⇒ 退回 32。
     */
    private double chaseLimit() {
        return Math.max(MAX_CHASE_DISTANCE, 2.0D * (scope == null ? 0 : scope.currentRadius()));
    }

    /** 记录"从已知集合里消失"的实体（被拾取、被合并、或离开世界）。计数不在这里，在簇结束时按背包增量统计。 */
    private void trackDisappearances(List<ItemEntity> live) {
        Set<UUID> liveIds = new LinkedHashSet<>();
        for (ItemEntity item : live) {
            liveIds.add(item.getUUID());
        }
        for (UUID id : known) {
            if (liveIds.contains(id) || consumed.contains(id) || retired.contains(id)) {
                continue;
            }
            consumed.add(id);
            BotLog.info("[CollectDrops] entity_gone item={}", id);
        }
    }

    // ---- 簇 ----

    private void beginCluster(List<ItemEntity> live) {
        ItemEntity seed = live.stream()
                .min(java.util.Comparator.comparingDouble(item -> bot.distanceToSqr(item)))
                .orElse(live.get(0));
        clusterIds = new ArrayList<>();
        Set<UUID> inCluster = new LinkedHashSet<>();
        Deque<ItemEntity> queue = new ArrayDeque<>();
        clusterIds.add(seed.getUUID());
        inCluster.add(seed.getUUID());
        queue.add(seed);
        while (!queue.isEmpty()) {
            ItemEntity current = queue.poll();
            for (ItemEntity other : live) {
                if (inCluster.contains(other.getUUID())) {
                    continue;
                }
                if (linked(current, other)) {
                    inCluster.add(other.getUUID());
                    clusterIds.add(other.getUUID());
                    queue.add(other);
                }
            }
        }
        anchor = seed.blockPosition().immutable();
        clusterStartSum = 0;
        typeBefore = new LinkedHashMap<>();
        for (UUID id : clusterIds) {
            ItemEntity item = liveById.get(id);
            if (item == null) {
                continue;
            }
            clusterStartSum += item.getItem().getCount();
            typeBefore.putIfAbsent(item.getItem().getItem(), countInInventory(item.getItem().getItem()));
        }
        sweepTicks = 0;
        waitTicks = 0;
        reanchors = 0;
        settleTicks = 0;
        slowReported = false;
        detourReported = false;
        failedGoalCells.clear();          // 3-b/D2：排除集**只在本簇内**有效（不跨簇记忆，避免变永久拉黑）
        worldChangesBefore = com.dddgn.alice.bot.TaskMetrics.snapshot();   // `D-375`："改造地形"的基线
        runner = null;
        BotLog.info("[CollectDrops] cluster_start anchor={} members={} items={} types={}",
                anchor.toShortString(), clusterIds.size(), clusterStartSum, typeBefore.size());
    }

    /** 是否仍在空中下落（未落地且速度向下）。 */
    /**
     * 物品是否还未落定（仍在空中）。
     *
     * <p>**2026-09-10 第二次修正**：原判据是 `!onGround() && dy < -0.02`，只认"正在下落"，
     * 漏掉了原版掉落物的**上抛阶段**——`Block.popResource` 生成的 `ItemEntity` 带向上初速度，
     * 破块后最初几 tick `dy > 0`，判据为假 → 收集器立刻去追那一格空中位置 →
     * `UNREACHABLE`/`MOVEMENT_FAILED` → 物品被**退役**。
     * 实测代价：4 根原木全砍完，最后一根的掉落物在 `itemY=67.75` 被退役 → `inventoryDelta=3`
     * → 终态 `product_not_collected`（树砍完了却判失败）。
     * 改为"只要还没接地就等它落定"，上抛与下落一并覆盖；已接地但位置很高的物品不受影响，
     * 仍会走正常走位并在确实够不到时如实退休。
     */
    private static boolean isAirborne(ItemEntity item) {
        return !item.onGround();
    }

    private static boolean linked(ItemEntity a, ItemEntity b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.abs(a.getY() - b.getY()) <= CLUSTER_LINK_DY
                && dx * dx + dz * dz <= CLUSTER_LINK_DISTANCE * CLUSTER_LINK_DISTANCE;
    }

    private List<ItemEntity> liveMembers(List<ItemEntity> live) {
        List<ItemEntity> members = new ArrayList<>();
        for (ItemEntity item : live) {
            if (clusterIds.contains(item.getUUID())) {
                members.add(item);
            }
        }
        return members;
    }

    /**
     * 结束一次簇扫描：按**背包增量**计收集数，并用守恒式交叉校验
     * `增量 == 起始 stack 总和 − 结束剩余存活 stack 总和`。
     */
    private void endCluster(boolean timedOut) {
        int delta = 0;
        if (typeBefore != null) {
            for (Map.Entry<Item, Integer> entry : typeBefore.entrySet()) {
                delta += countInInventory(entry.getKey()) - entry.getValue();
            }
        }
        int remaining = 0;
        for (UUID id : clusterIds) {
            ItemEntity item = liveById.get(id);
            if (item != null) {
                remaining += item.getItem().getCount();
            }
        }
        int expectedGain = clusterStartSum - remaining;
        collectedItems += Math.max(0, delta);
        clustersSwept++;
        if (delta != expectedGain) {
            mismatchCount++;
            BotLog.warn("[CollectDrops] MISMATCH anchor={} delta={} expected={} startSum={} remaining={}"
                            + "（同类型被他人拾取/重复生成/背包满/统计漏洞）",
                    anchor == null ? "-" : anchor.toShortString(), delta, expectedGain, clusterStartSum, remaining);
        }
        BotLog.info("[CollectDrops] cluster_done anchor={} members={} delta={} remaining={} ticks={} timeout={}",
                anchor == null ? "-" : anchor.toShortString(), clusterIds.size(), delta, remaining,
                sweepTicks, timedOut);
        clusterIds = null;
        anchor = null;
        typeBefore = null;
        runner = null;
        sweepTicks = 0;
        waitTicks = 0;
        reanchors = 0;
        settleTicks = 0;
        slowReported = false;
        detourReported = false;
        failedGoalCells.clear();          // 3-b/D2：同上（簇结束即清空）
        worldChangesBefore = null;
    }

    /**
     * ⭐ **收集阶段的"绕远 / 在改造地形"上报**（`D-375` 第 4 条；第六轮真机实测的缺口）。
     *
     * <p><b>为什么必须有</b>：决策层能看到的一切只有"任务终态 + 事件"，而**簇内的那 10 秒**
     * （真机实证：一条 19 段计划、破 6 格、烧满 200 tick 簇预算、`collected=0/13`）对它**完全不可见**
     * —— 截图里它还在说「不动…mined 在涨…继续观察」。挖矿作业的代价恰恰是在这段时间被烧掉的。
     *
     * <p><b>两条判据（各自每簇只报一次；滞回 = 换簇重新武装）</b>：
     * <ul>
     *   <li>{@code PICKUP_SLOW}：本簇已耗 {@link #CLUSTER_SLOW_TICKS} tick（簇预算的一半）还没收手；</li>
     *   <li>{@code PICKUP_DETOUR}：本簇期间**世界真的被改动过**（运行账增量 &gt; 0）
     *       ⇒ 为了捡一件掉落物在改造地形（真机取证：`by=collect-drops:attempt0:PATH_ACCESS` ×16）。</li>
     * </ul>
     *
     * <p>两条都走统一出口 {@code DecisionEvents.emit}（事件环 + 日志 + 通知决策层），于是决策层
     * **当场**可以停 / 换点 / 放弃这一簇，而不是等 600 tick 的总预算烧完才发现。
     * 自检窗口内 `GoalDirector` 会只记录不通知（既有守卫）。
     */
    private void reportCollectSymptoms(List<ItemEntity> members) {
        if (!slowReported && sweepTicks >= CLUSTER_SLOW_TICKS) {
            slowReported = true;
            slowEmits++;
            com.dddgn.alice.decision.DecisionEvents.emit(bot, "PICKUP_SLOW", "warn",
                    "拾取一簇已耗 " + sweepTicks + " tick 还没收手（成员 " + members.size() + " 件）",
                    "anchor=" + (anchor == null ? "-" : anchor.toShortString())
                            + " members=" + members.size() + " sweepTicks=" + sweepTicks
                            + " reanchors=" + reanchors + "/" + MAX_REANCHORS
                            + " collected=" + collectedItems + "/" + expectedItems
                            + " ticks=" + ticks);
        }
        int changes = worldChangesInCluster();
        if (!detourReported && changes > 0) {
            detourReported = true;
            detourEmits++;
            com.dddgn.alice.decision.DecisionEvents.emit(bot, "PICKUP_DETOUR", "warn",
                    "为拾取掉落物**改造地形**（本簇期间世界已被改动 " + changes + " 格）",
                    "anchor=" + (anchor == null ? "-" : anchor.toShortString())
                            + " members=" + members.size()
                            + " botFeet=" + bot.blockPosition().toShortString()
                            + " worldChanges=" + changes + " sweepTicks=" + sweepTicks
                            + " worldMod=" + allowWorldModification);
        }
    }

    /** 本簇里**离 bot 最近**的存活成员（取证/探针用；空列表 ⇒ `null`）。 */
    private ItemEntity nearestMember(List<ItemEntity> members) {
        return members.stream()
                .min(java.util.Comparator.comparingDouble(item -> bot.distanceToSqr(item)))
                .orElse(null);
    }

    /** 包围盒的一行格式（`retire` 行既有 botBox 格式**逐字不变**，itemBox 共用它）。 */
    private static String fmtBox(AABB box) {
        return String.format(java.util.Locale.ROOT, "[%.2f..%.2f y %.2f..%.2f z %.2f..%.2f]",
                box.minX, box.maxX, box.minY, box.maxY, box.minZ, box.maxZ);
    }

    /**
     * ⭐ `D-375` **残差取证**：把「够不着」那一刻的**几何一次性打全**（**只在失败路径**调用）。
     *
     * <p><b>为什么需要它</b>（2026-09-21 第七轮真机 + 14:06 那段日志）：27 簇里 4 簇丢了 12 件
     * （`reason=not_in_pickup_range`，同一件被反复聚簇 4 次 ≈ 5 秒）。但 `retire` 行只有**格**坐标
     * 与 `botBox`，而"该不该修、怎么修"取决于两件**当时看不到**的事：
     * <ol>
     *   <li>物品停在自己那格的**哪一角** —— 拾取盒外扩只有 `1.0 x/z`，而 bot 在 `EXACT` 容差下
     *       可以停偏 0.19（第七轮实测 `botBox z 中心 152.31`）⇒ 只差一点点就"判得着、捡不到"；</li>
     *   <li>除已选的目标格外，**还有哪些格可站 / 够得着** —— 这决定"失败后换下一个候选格"能不能救回来。</li>
     * </ol>
     *
     * <p>口径纪律：**只读、只打日志、不做任何决策**（它是失败终态日志，不是临时探针 ——
     * 临时探针用完要删，这条要留到"够不着"这一类彻底收敛）。
     * 枚举**与搜索同一份定义**（{@link #approachCandidates}：`dy ∈ {0,-1}` × 环 1/环 2，见那里的注释），
     * 打印只列"可站或够得着"的格、每条带 `(dx,dy,dz)` 与层计数（**有界**：枚举本身最多 48 格）。
     */
    private void logApproachProbe(String why, ItemEntity item) {
        if (item == null) {
            return;
        }
        BlockPos itemCell = item.blockPosition().immutable();
        ApproachReading reading = approachReading(item);
        BotLog.warn("[CollectDrops] approach_probe why={} item={} itemPos={} itemBox={} itemY={}"
                        + " botFeet={} botBox={} itemCellStandable={} standable={}"
                        + " standableAndReachable={} standable_dy0={} standable_dy-1={} ring={}",
                why, item.getUUID(), itemCell.toShortString(), fmtBox(item.getBoundingBox()),
                String.format(java.util.Locale.ROOT, "%.3f", item.getY()),
                bot.blockPosition().toShortString(), fmtBox(bot.getBoundingBox()),
                isStandableCell(itemCell), reading.standable(), reading.standableAndReachable(),
                reading.standableDy0(), reading.standableDyMinus1(),
                reading.ring().isEmpty() ? "-" : reading.ring());
    }

    /**
     * `approach_probe` 的**读数**（3-b/D0）：与上面那行日志**共用同一份计算**
     * ⇒ 夹具断言的正是"真打出去的东西"，不必去扒日志文本。
     *
     * @param standable             枚举中可站的格数（两层合计）
     * @param standableAndReachable 既可站、又够得着的格数
     * @param standableDy0          `dy=0` 层的可站格数
     * @param standableDyMinus1     `dy=-1` 层的可站格数（**这一层原探针根本不扫**）
     * @param ring                  逐格 `(dx,dy,dz)stand=…/reach=…`（只列"可站或够得着"的，有界）
     */
    record ApproachReading(int standable, int standableAndReachable, int standableDy0,
                           int standableDyMinus1, String ring) {
    }

    ApproachReading approachReading(ItemEntity item) {
        BlockPos itemCell = item.blockPosition().immutable();
        StringBuilder ring = new StringBuilder();
        int standable = 0;
        int standableAndReachable = 0;
        int standableDy0 = 0;
        int standableDyMinus1 = 0;
        int entries = 0;
        for (int radius = 1; radius <= 2; radius++) {
            for (BlockPos cell : approachCandidates(itemCell, radius)) {
                int dx = cell.getX() - itemCell.getX();
                int dy = cell.getY() - itemCell.getY();
                int dz = cell.getZ() - itemCell.getZ();
                boolean canStand = isStandableCell(cell);
                boolean reach = withinPickupReach(cell, item);
                if (canStand) {
                    standable++;
                    if (dy == 0) {
                        standableDy0++;
                    } else {
                        standableDyMinus1++;
                    }
                }
                if (canStand && reach) {
                    standableAndReachable++;
                }
                if ((canStand || reach) && entries < PROBE_MAX_ENTRIES) {
                    entries++;
                    ring.append(" (").append(dx).append(',').append(dy).append(',').append(dz)
                            .append(")stand=").append(canStand ? 1 : 0)
                            .append("/reach=").append(reach ? 1 : 0);
                }
            }
        }
        return new ApproachReading(standable, standableAndReachable, standableDy0, standableDyMinus1,
                ring.toString());
    }

    /**
     * 本簇开始以来**世界被改动的格数**（运行账增量：破坏 + 放置 + 容器写入）。
     *
     * <p>见 {@link #worldChangesBefore} 的理由 —— 这是"改造地形"判据的唯一来源。
     */
    private int worldChangesInCluster() {
        if (worldChangesBefore == null) {
            return 0;
        }
        return com.dddgn.alice.bot.TaskMetrics.snapshot().delta(worldChangesBefore).worldChanges();
    }

    private void retire(UUID id, String reason) {
        if (!retired.add(id)) {
            return;
        }
        if ("pickup_timeout".equals(reason)) {
            pickupTimeoutCount++;
        } else if ("no_standable_approach".equals(reason)) {
            // `D-375`：与 `unreachable`（"试过走位、失败了"）分开记 —— 这一档是"**根本不许规划**"
            noApproachCount++;
        } else {
            unreachableCount++;
        }
        ItemEntity item = liveById.get(id);
        // **J-10 遗留项收口（2026-09-16）**：本任务是 best-effort（永不 FAILED）⇒ 若不把"**策略拒绝**"
        // 单独记一笔，"被 `DropPolicy` 拦下"与"够不着/超时"在下游**长得一模一样**。
        if (item != null) {
            com.dddgn.alice.decision.DropPolicy.Provenance provenance =
                    com.dddgn.alice.decision.DropPolicy.effectiveProvenance(bot, item);
            if (!com.dddgn.alice.decision.DropPolicy.mayCollect(bot, provenance)) {
                policyBlockedCount++;
                BotLog.warn("[CollectDrops] policy_blocked item={} provenance={} policy={}"
                                + "（策略不放行 ⇒ 如实计入 policy_blocked，别混进 unreachable）",
                        id, provenance, com.dddgn.alice.decision.DropPolicy.policy(bot, provenance));
            }
        }
        BotLog.warn("[CollectDrops] retire item={} reason={} itemPos={} itemY={} itemBox={} stack={}"
                        + " botFeet={} botBox={} inRange={}",
                id, reason,
                item == null ? "-" : item.blockPosition().toShortString(),
                item == null ? "-" : String.format(java.util.Locale.ROOT, "%.3f", item.getY()),
                // ⭐ `D-375` 残差取证：**物品的精确包围盒**（格内停在哪一角）——
                // 第七轮真机那 12 件留在地上的掉落物，结论正取决于它（见 `logApproachProbe`）。
                item == null ? "-" : fmtBox(item.getBoundingBox()),
                lastSeenStack.getOrDefault(id, 0),
                bot.blockPosition().toShortString(),
                fmtBox(bot.getBoundingBox()),
                item != null && inPickupRange(item));
    }

    private int countInInventory(Item item) {
        int total = 0;
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private void cancelRunner() {
        if (runner != null) {
            runner.cancel();
            runner = null;
        }
        bot.controller().stopMovement();
    }

    private Status finish(String reason) {
        cancelRunner();
        String summary = "reason=" + reason
                + " collected=" + collectedItems + "/" + expectedItems
                + " entities=" + consumed.size() + "/" + known.size()
                + " clusters=" + clustersSwept
                + " unreachable=" + unreachableCount
                + " no_approach=" + noApproachCount
                + " pickup_timeout=" + pickupTimeoutCount
                + " mismatch=" + mismatchCount
                + " policy_blocked=" + policyBlockedCount
                + " slow_events=" + slowEmits
                + " detour_events=" + detourEmits
                + " goal_excluded=" + goalExcludedCount
                + " goal_foot=" + (lastGoalFoot == null ? "-" : lastGoalFoot.toShortString())
                + " ticks=" + ticks;
        BotLog.info("[CollectDrops] SUMMARY {}", summary);
        return Status.DONE;
    }

    /** K-3：把"当前寻路段是否安全"透传给取消方（`/alice stop` 会据此延后到安全点）。 */
    @Override
    public boolean safeToCancel() {
        return runner == null || runner.safeToCancel();
    }
}
