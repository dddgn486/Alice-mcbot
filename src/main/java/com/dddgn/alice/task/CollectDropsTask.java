package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.task.collecting.CollectStep;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
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
 *       **"有界"的含义（`D-372`）**：① **时间预算** —— 本任务的 `totalBudgetTicks`
 *       （**构造参数，无默认口**，`D-493` 拍点 3 `3甲`；防空转的唯一闸门）；
 *       ② **格数上限默认不限**（`Quota` 回退 `Caps.UNBOUNDED`，显式装订的上限照旧强制）；
 *       **③ 保护区不在这里管** —— 它是独立权限层（`CapabilityGate` → `protectionReason`
 *       ⇒ `protected_area`/`protected_block`），用户口径「保持权限管理就行」；</li>
 *   <li>**自然拾取**：站进拾取范围后等待原版拾取，**不反射、不调 `playerTouch`**；</li>
 *   <li>**best-effort**：收不到不判 FAILED，输出 `[CollectDrops] SUMMARY ...`。</li>
 * </ul>
 *
 * <p>⭐ **`step 5b` 刀②（`D-493` 拍点 1 `1甲`）：本类现在是**编排器**，不再是"一个胖原语"。
 * 一簇的作业（走到簇锚点 ＋ 等这一簇被吸走 ＋ 计数 ＋ 该簇的锚点重试）切进了原语
 * {@link CollectStep}（`task/collecting/`）；本类留下：**候选收集 ＋ 聚类 ＋ 逐簇迭代 ＋
 * 总预算 ＋ 守恒交叉校验 ＋ 摘要**。两个终态闩锁各自持有（`D-493` 拍点 2 `2甲`）。
 *
 * <p>⚠️ **簇级收集的设计口径（`D-075` 修正，用户裁定）没变，只是换了住处**：原版拾取盒为玩家包围盒
 * 外扩 ±1.0 x/z、±0.5 y，一次走位会同时吸走邻近多件——逐实体"追一个等一个"既慢又少报。现在：
 * <ol>
 *   <li>把候选按**连通距离 2.0 格、|Δy| ≤ 1** 聚成簇（**本类**）；</li>
 *   <li>走到簇内最近成员格，等该簇成员全部消失（或 40 tick 超时；超时后**最多再换 2 次锚点**扫尾）
 *       （**{@link CollectStep}**）；</li>
 *   <li>计数用**背包增量**（唯一地面真相），不再用"实体是否消失"推断（**{@link CollectStep}**）；</li>
 *   <li>**守恒交叉校验**：`背包增量 == 簇起始 stack 总和 − 结束时剩余存活 stack 总和`，
 *       不等就记 `MISMATCH`（暴露"同类型被他人拾取/重复生成/背包满"等干扰），不做静默相信
 *       （**本类**，`D-493` 拍点 1）。</li>
 * </ol>
 */
public final class CollectDropsTask implements Task {

    /**
     * **单个簇的扫描预算**（tick，含走位与等待）—— `D-493` 拍点 3 `3甲` 的"**private 机制档不动**"。
     *
     * <p>⚠️ **它为什么住在编排器**：`D-466` 第 4 条裁定"类内额度常量**随编排留下、不注入**"
     * （先例 = `MineTask.MAX_RECOVERY_ATTEMPTS` / `CHAIN_TIMEOUT_TICKS`）。而 `D-466` 判 3 · D1
     * 又要求**原语**的额度只许来自构造参数 ⇒ 两者合起来的形态就是这里：**常量在编排器、
     * 值由构造参数下去**（`new CollectStep(..., CLUSTER_BUDGET_TICKS, ...)`）。
     * 这样原语里就**没有**任何"额度词命名"的 `static final`，消费点也只剩一处。
     */
    private static final int CLUSTER_BUDGET_TICKS = 200;

    // ==================== 扫尾预算的推导口径（R3 / D-123） ====================
    // 依据实测代价反推，而不是"按某个场景试出来一个数"：
    //   · 建立簇 / 等 pickupDelay(~10 tick) / 等掉落物落地 / 收尾 —— 固定开销；
    //   · 每个待收掉落物一次走位-吸取循环（实测 6~30 tick）；
    //   · 每允许加高一步 = 一次 PILLAR（实测 10~16 tick）+ 重锚重规划余量。
    /**
     * 扫尾固定开销（建立簇 + 等落地 + 收尾）。
     *
     * <p>⚠️ `D-493` 拍点 3 `3甲`：`SWEEP_*` 三个常量是**公式系数**、不是对外口 ⇒ `public` 降 `private`
     * （它们此前 `public` 却**零外部引用** —— 实测只有本类内那一个公式在用）。
     */
    private static final int SWEEP_BASE_TICKS = 120;
    /** 每个待收掉落物的代价（实测 6~30 tick，取 2 倍余量）。 */
    private static final int SWEEP_TICKS_PER_DROP = 60;
    /** 每一步加高的代价（PILLAR 实测 10~16 tick + 重锚/重规划余量）。 */
    private static final int SWEEP_TICKS_PER_GAIN_STEP = 25;

    /**
     * 扫尾阶段的 tick 预算：`固定开销 + 待收物数 × 单价 + 允许的加高步数 × 单价`。
     *
     * @param dropEstimate 进入扫尾时作用域里的存活掉落物数（下界估计，`max(1, n)`）
     * @param gainProfile  扫尾用的能力信封（null = 不允许加高）
     */
    public static int suggestedSweepTicks(int dropEstimate, com.dddgn.alice.task.mining.MiningProfile gainProfile) {
        int drops = Math.max(1, dropEstimate);
        int steps = gainProfile == null ? 0 : gainProfile.maxGainSteps();
        return SWEEP_BASE_TICKS + drops * SWEEP_TICKS_PER_DROP + steps * SWEEP_TICKS_PER_GAIN_STEP;
    }

    /** 簇内两个掉落物的最大连通距离（格）：对应原版拾取盒 ±1.3。 */
    private static final double CLUSTER_LINK_DISTANCE = 2.0D;
    /** 簇内允许的最大垂直差（格）。 */
    private static final int CLUSTER_LINK_DY = 1;
    /**
     * **追取上限的下限兜底**（格）：`D-074` 用户裁定 2「**超过 N 格放弃**追踪」—— ⚠️ 裁定里
     * **N 从未被指定**，`32` 是实现当初选的。实际用的上限见 {@link #chaseLimit()}：它是
     * `max(本常量, 2 × 当前作用域半径)`。
     */
    private static final double MAX_CHASE_DISTANCE = 32.0D;

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
     * 见 `JobRegionGrantCheckTask:335`）。
     *
     * <p>{@code null} = **全部落物**（既有行为**逐字不变**）。
     */
    private final java.util.function.Predicate<ItemStack> activePickup;
    private final boolean allowWorldModification;
    /**
     * 收集阶段的**能力信封**（D-116）：掉落物在头顶够不到时，允许用同一份"原地加高"能力上去拿。
     * 默认 {@link com.dddgn.alice.task.mining.MiningProfile#STANDABLE_ONLY}（不允许加高）
     * ⇒ 既有调用点行为完全不变。
     */
    private final com.dddgn.alice.task.mining.MiningProfile gainProfile;
    private final int totalBudgetTicks;
    /** ⭐ 一簇作业的原语（`step 5b` 刀②）；本类只做编排。 */
    private final CollectStep step;

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
    /**
     * ⭐ **最近一次结束的簇的守恒读数**（`step 5b` 刀①，2026-09-27）。
     *
     * <p><b>为什么必须有它</b>：`mismatchCount` 是**累计**的，只能回答"记过几笔"，
     * 回答不了"记的那笔**数的是不是那件事**"。而在此之前，这两个量的**唯一**读取路径是
     * {@link #finish(String)} 拼出来的 `SUMMARY` **展示串** —— 正是 `D-329 ⑤.3` 明令禁止
     * 判定去读的那种（展示串可以随文案调整而变，判定不能跟着变）。
     *
     * <p>{@code null} = 本任务还没有任何簇结束过（不是"守恒成立"—— 那要读 {@code mismatch=false}）。
     */
    private ConservationReading lastConservation;
    /** 被 `DropPolicy` 拒绝而留下的掉落物数（J-10：与 unreachable/timeout 分开记）。 */
    private int policyBlockedCount;
    /**
     * ⭐ `D-375`：**没有"可站且够得着"的格**而被如实退休的物品数（与 `unreachable` 分开记）。
     *
     * <p>它与 `unreachable` 是两件事：`unreachable` = "走位试过、失败了"；本计数 = "**根本不许规划**"
     * （把站不住的格当作目标 = 要求寻路挖进去）。混在一起就看不出"是不是又有人在挖穿地形捡东西"。
     */
    private int noApproachCount;

    /**
     * **原语与编排器之间的那条窄边**（实现 {@link CollectStep.Host}）。
     *
     * <p>做成匿名实现而不是让本类 `implements`：那五个读口里有三个是**展示用**的、一个是**内部索引**，
     * 让它们出现在 `CollectDropsTask` 的公开方法表上会误导读代码的人（"这是给别人调的 API"），
     * 而实际上**只有原语**会调。
     */
    private final CollectStep.Host host = new CollectStep.Host() {
        @Override
        public Map<UUID, ItemEntity> liveIndex() {
            return liveById;
        }

        @Override
        public void retire(UUID id, String reason) {
            CollectDropsTask.this.retire(id, reason);
        }

        @Override
        public int collectedTotal() {
            return collectedItems;
        }

        @Override
        public int expectedTotal() {
            return expectedItems;
        }

        @Override
        public int taskTicks() {
            return ticks;
        }
    };

    /**
     * ⭐ `D-493` 拍点 4 `4甲` **全参构造器**（9 参）。
     *
     * <p>⚠️ **尾部 5 参逐字保持改造前的形状**（`allowWorldModification, totalBudgetTicks,
     * gainProfile, liveDropsSource, activePickup`）—— `tools/check-collect-callsite-shape.py`
     * 从右锚定的就是这 5 个（那 5 样"改坏了照样编译、行为夹具也抓不到"的实参）。
     *
     * @param gainProfile     收集阶段的能力信封（D-116）：允许"原地加高"时，头顶够不到的掉落物
     *                        可以搭上去拿（放置落在同一作用域内，由建拆同权阶段收回）
     * @param liveDropsSource 候选来源（`null` = `scope::liveDrops`）；供应商每 tick 重新问一次，
     *                        所以调用方可以**实时重扫**（比如"区域内清单内落物"）
     * @param activePickup    **主动拾取清单**（`null` = 全部落物 ⇒ 既有行为逐字不变）；
     *                        清单外的落物**不进候选、不进账**（"顺手捡"由原版吸取范围负责）
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
        this.step = new CollectStep(bot, allowWorldModification, this.gainProfile,
                CLUSTER_BUDGET_TICKS, host);
    }

    /**
     * ⭐ `D-493` 拍点 4 `4甲` **便捷构造器**（7 参）：`STANDABLE_ONLY` ＋ 候选 = 作用域在册落物，
     * 只有**额度**与**主动拾取清单**由调用方给。
     *
     * <p>⚠️ **额度没有默认值**（`D-493` 拍点 3 `3甲`：默认口 `DEFAULT_TOTAL_BUDGET_TICKS` 已删）——
     * 「调用方不说也能用」的入口只要存在一个，额度就有一份住在原语里
     * （`J-★` 第 6 段 step 2a / `D-455` ⑧③：**额度归 Job**）。
     */
    public CollectDropsTask(BotPlayer bot, BlockPos origin, ScopeBuffer scope,
                            List<UUID> expectedIds, boolean allowWorldModification, int totalBudgetTicks,
                            java.util.function.Predicate<ItemStack> activePickup) {
        this(bot, origin, scope, expectedIds, allowWorldModification, totalBudgetTicks,
                com.dddgn.alice.task.mining.MiningProfile.STANDABLE_ONLY, null, activePickup);
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
        return step.slowEventEmits();
    }

    /** `D-375`：上报给决策层的 `PICKUP_DETOUR` 事件数（夹具/门禁用）。 */
    public int detourEventEmits() {
        return step.detourEventEmits();
    }

    /**
     * 3-b/D2：本任务累计**排除过多少个"站上去也够不到"的目标格**（夹具/门禁用）。
     *
     * <p>判据意义：这个数 > 0 = "模型说够得着、执行期判否"的几何**真的发生过**，而且收集器
     * **没有立刻放弃**（旧行为：换个锚点又算出同一个格，两轮后如实退休，物品留在地上）。
     */
    public int goalExcludedTotal() {
        return step.goalExcludedTotal();
    }

    /**
     * ⭐ `step 5b` 刀①（2026-09-27）：**守恒校验的累计命中次数**（`delta != expected` 的簇数）。
     *
     * <p>与 {@link #lastConservation()} 是**两个不同的问题**，夹具两条都要断言：
     * 本方法答"**记过几笔**"，后者答"**记的那笔数的是不是那件事**"。
     * 只断言本方法 ⇒ 一个"每簇无脑 +1"的实现照样全绿（`silent-measurement-failure` 规则 3）。
     */
    public int mismatchTotal() {
        return mismatchCount;
    }

    /**
     * ⭐ `step 5b` 刀①（2026-09-27）：**最近一次结束的簇**的守恒读数（没结束过簇 ⇒ `null`）。
     *
     * <p><b>为什么不是"总和"而是"最近一次"</b>：守恒式 `增量 == 起始 stack 总和 − 结束剩余 stack 总和`
     * 是**逐簇**成立的（`clusterStartSum` / `remaining` 每簇重建），把它们跨簇相加没有物理含义。
     * 需要跨簇累计就看 {@link #mismatchTotal()}。
     *
     * <p>这是**判定面**，不是展示面：`SUMMARY` 那行字符串与本读数**同源**（同一处构造），
     * 所以不存在"日志说 A、判定读 B"的缺口（`D-329 ⑤.3`）。
     */
    public ConservationReading lastConservation() {
        return lastConservation;
    }

    /**
     * **一次簇扫描结束时的守恒读数**（`step 5b` 刀①）。
     *
     * <p>守恒式：`delta == expected`，其中 `expected = startSum - remaining`。
     *
     * <p>三项失配的物理含义（真机语料里三种都出现过，`47 / 1121` 份日志带 `MISMATCH`）：
     * <ul>
     *   <li>`delta &lt; expected`：有东西**没进包就消失了**（被他人/他 bot 拾取、实体被合并、被清除）
     *       —— 真机样本 `delta=0 expected=1 startSum=3 remaining=2`；</li>
     *   <li>`delta &gt; expected`：**同类型物品从别的渠道进了包**（另一条链路/上一个任务的残留）
     *       —— 真机样本 `delta=5 expected=4 startSum=4 remaining=0`；</li>
     *   <li>相等：守恒成立（**这才是负对照**，见 `CollectConservationCheckTask` 臂③）。</li>
     * </ul>
     *
     * @param delta     背包增量口径的**实际**进包数（各类型 `countInInventory` 增量之和）
     * @param expected  守恒式算出的**应收**数（`startSum - remaining`）
     * @param startSum  本簇开始时全部成员的 stack 数量之和
     * @param remaining 本簇结束时**仍在 `liveById` 里**的成员 stack 数量之和
     * @param mismatch  `delta != expected`（= 判定本体；与 `mismatchTotal()` 用的是同一个谓词）
     */
    public record ConservationReading(int delta, int expected, int startSum, int remaining, boolean mismatch) {
    }

    /**
     * `D-375`：最近一次**真的拿去规划**的目标格（`null` = 本任务从未规划过）。
     *
     * <p>为什么要暴露它：夹具必须能判"收集器挑的目标**可站**吗" —— 只看世界的最终状态是**不够**的，
     * 实测（2026-09-21 注入复现）会漏：目标不可站 ⇒ 计划里带一条破格边，但物品在**破格之前**
     * 就被原版拾取范围捞走了 ⇒ 世界零改动、看起来全绿，而缺陷（目标是站不住的格）已经在计划里了。
     */
    public BlockPos lastGoalFoot() {
        return step.lastGoalFoot();
    }

    /**
     * `approach_probe` 的读数（3-b/D0）—— **委托给原语**（探针与那里的搜索共用同一份计算
     * ⇒ 夹具断言的正是"真打出去的东西"，不必去扒日志文本）。
     */
    public CollectStep.ApproachReading approachReading(ItemEntity item) {
        return step.approachReading(item);
    }

    @Override
    public Status tick() {
        if (++ticks > totalBudgetTicks) {
            return finish("timeout");
        }
        List<ItemEntity> live = refreshCandidates();
        trackDisappearances(live);

        if (!step.hasActiveCluster()) {
            if (live.isEmpty()) {
                return finish("done");
            }
            beginCluster(live);
            return Status.RUNNING;
        }
        if (step.tick(live) == CollectStep.Outcome.FINISHED) {
            endCluster();
        }
        return Status.RUNNING;
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

    // ---- 簇（聚类在这里；一簇的作业在 `CollectStep`） ----

    /**
     * **逐簇迭代的第一步**：把候选按"连通距离 2.0 格、|Δy| ≤ 1"聚成一簇（BFS），
     * 然后把这一簇交给原语。
     *
     * <p>与改造前逐字等价：种子 = 离 bot 最近的候选，`clusterIds` 的**顺序** = BFS 发现序
     * （它进 `cluster_done` 的 `members=` 与守恒读数的 `remaining` 口径 ⇒ 顺序必须保持不变）。
     */
    private void beginCluster(List<ItemEntity> live) {
        ItemEntity seed = live.stream()
                .min(java.util.Comparator.comparingDouble(item -> bot.distanceToSqr(item)))
                .orElse(live.get(0));
        List<UUID> ids = new ArrayList<>();
        Set<UUID> inCluster = new LinkedHashSet<>();
        Deque<ItemEntity> queue = new ArrayDeque<>();
        ids.add(seed.getUUID());
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
                    ids.add(other.getUUID());
                    queue.add(other);
                }
            }
        }
        step.begin(ids);
    }

    private static boolean linked(ItemEntity a, ItemEntity b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.abs(a.getY() - b.getY()) <= CLUSTER_LINK_DY
                && dx * dx + dz * dz <= CLUSTER_LINK_DISTANCE * CLUSTER_LINK_DISTANCE;
    }

    /**
     * **一簇结束**：按**背包增量**累计收集数，并用守恒式交叉校验
     * `增量 == 起始 stack 总和 − 结束剩余存活 stack 总和`（`D-493` 拍点 1：守恒交叉校验属编排）。
     *
     * <p>⚠️ 日志**先后次序与改造前逐字一致**：`MISMATCH`（warn）先、`cluster_done`（info）后 ——
     * 原语在这一 tick 里已经把 `retire` 那些行打完了（它们本来就在 `endCluster` 之前）。
     */
    private void endCluster() {
        CollectStep.Reading reading = step.reading();
        int delta = reading.delta();
        int startSum = reading.startSum();
        int remaining = reading.remaining();
        int expectedGain = startSum - remaining;
        collectedItems += Math.max(0, delta);
        clustersSwept++;
        // ⭐ `step 5b` 刀①：**判定与读数同源** —— 下面这一个 record 既是日志/SUMMARY 的来源，
        // 也是夹具/门禁断言的来源 ⇒ 两边不可能各自漂移（`D-329 ⑤.3`）。
        lastConservation = new ConservationReading(delta, expectedGain, startSum, remaining,
                delta != expectedGain);
        String anchor = reading.anchor() == null ? "-" : reading.anchor().toShortString();
        if (lastConservation.mismatch()) {
            mismatchCount++;
            BotLog.warn("[CollectDrops] MISMATCH anchor={} delta={} expected={} startSum={} remaining={}"
                            + "（同类型被他人拾取/重复生成/背包满/统计漏洞）",
                    anchor, delta, expectedGain, startSum, remaining);
        }
        BotLog.info("[CollectDrops] cluster_done anchor={} members={} delta={} remaining={} ticks={} timeout={}",
                anchor, reading.members(), delta, remaining, reading.sweepTicks(), reading.timedOut());
    }

    // ---- 失败归因（唯一出口：原语与编排器共用） ----

    /**
     * **如实退休一件掉落物**（`CollectStep.Host#retire` 的实现）。
     *
     * <p>⚠️ 这是全类**唯一**的退休出口，原语的四条失败路径与编排器的候选过滤（`too_far`）
     * 都走它 ⇒ 计数口径（`unreachable` / `pickup_timeout` / `no_standable_approach`）、
     * 策略归因（`policy_blocked`）与残差取证日志**不可能各自漂移**。
     */
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
                // 第七轮真机那 12 件留在地上的掉落物，结论正取决于它（见 `CollectStep#logApproachProbe`）。
                item == null ? "-" : CollectStep.fmtBox(item.getBoundingBox()),
                lastSeenStack.getOrDefault(id, 0),
                bot.blockPosition().toShortString(),
                CollectStep.fmtBox(bot.getBoundingBox()),
                item != null && CollectStep.reachesFrom(bot.getBoundingBox(), item));
    }

    private Status finish(String reason) {
        step.cancel();
        String summary = "reason=" + reason
                + " collected=" + collectedItems + "/" + expectedItems
                + " entities=" + consumed.size() + "/" + known.size()
                + " clusters=" + clustersSwept
                + " unreachable=" + unreachableCount
                + " no_approach=" + noApproachCount
                + " pickup_timeout=" + pickupTimeoutCount
                + " mismatch=" + mismatchCount
                + " policy_blocked=" + policyBlockedCount
                + " slow_events=" + step.slowEventEmits()
                + " detour_events=" + step.detourEventEmits()
                + " goal_excluded=" + step.goalExcludedTotal()
                + " goal_foot=" + (step.lastGoalFoot() == null ? "-" : step.lastGoalFoot().toShortString())
                + " ticks=" + ticks;
        BotLog.info("[CollectDrops] SUMMARY {}", summary);
        return Status.DONE;
    }

    /** K-3：把"当前寻路段是否安全"透传给取消方（`/alice stop` 会据此延后到安全点）。 */
    @Override
    public boolean safeToCancel() {
        return step.safeToCancel();
    }
}
