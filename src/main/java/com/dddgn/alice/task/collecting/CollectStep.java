package com.dddgn.alice.task.collecting;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.PathRetryRunner;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.session.PathExecutionResult;
// ⭐ `4a` 柱②（用户 2026-09-29 裁 `N3`）：`Step` = **原语注册口**（接口即注册）
import com.dddgn.alice.task.Step;
import com.dddgn.alice.fixture.mining.GainStepRunner;
import com.dddgn.alice.task.mining.MiningProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * **单簇收集原语**（`step 5b`，`D-493` 拍点 1 `1甲` / 拍点 6 `6甲`）：
 * **一簇的一次作业** —— 走到簇锚点 → 等这一簇被吸走 → 计数 → 该簇的锚点重试。
 *
 * <p>与 {@link com.dddgn.alice.task.mining.MineStep} 对称：编排器
 * （{@link com.dddgn.alice.task.CollectDropsTask}）负责**候选收集 ＋ 聚类 ＋ 逐簇迭代 ＋ 总预算
 * ＋ 守恒交叉校验 ＋ 摘要**，本类只管**一簇**：锚点规范化 / 换锚点重试 / 等落定 / 等原版吸附 /
 * 加高 / 簇预算 / 簇内症状上报。
 *
 * <p><b>为什么边界画在这里</b>（`D-493` 拍点 1 的理由，逐字）：锚点重试与"等吸附"**共享同一套状态**
 * （`sweepTicks` / `settleTicks` / 簇内成员）⇒ 切开会在两边各造一份状态。
 *
 * <p><b>生命周期</b>：**一个本类实例 = 一个作业**（在编排器里长存），一簇 = 一次
 * {@link #begin(List)} … {@link #reading()} 循环。簇内状态在 {@link #begin(List)} 里建立、
 * 在 {@link #reading()} 之后清空 ⇒ 不存在"忘了重置某个字段"这一类跨簇污染（改造前
 * `beginCluster`/`endCluster` 各写一份重置清单，两份漏一个就静默串簇）。
 *
 * <p>⚠️ **本类不认识编排器**（与 `MineStep` 同一条边界）：它不 import
 * `com.dddgn.alice.task.CollectDropsTask`，只通过下面这个**窄接口**
 * {@link Host} 拿三样东西：候选索引（读）、退休出口（写）、展示用进度（读）。
 * 依赖方向是 **编排器 → 本类**。
 *
 * <p>⚠️ **四条结构判据**（`D-466` 判 3 的 D1/D2 + `D-466` §四.1）由
 * `tools/check-task-orchestration-split.py` 静态断言：本类**不造**子任务 ·
 * **不造**额度（只来自构造参数）· 类内**没有**"额度词命名"的 `static final` ·
 * 消费额度的点**恰好 1 处**且具名（{@link #sweepBudgetExhausted()}）。
 */
public final class CollectStep implements Step {

    /** 一簇作业的两种形态（终态只有一个：{@link Outcome#FINISHED}）。 */
    public enum Outcome { RUNNING, FINISHED }

    /**
     * **原语与编排器之间唯一的那条边**（窄接口；实现方是编排器，原语不引用它）。
     *
     * <p>三个读口都**只用于展示/日志**（`D-329 ⑤.3`：判定永不读展示串）—— 所以它们不进
     * {@link Reading}，也就不可能被判定悄悄依赖上。
     */
    public interface Host {

        /** 当前候选索引（`id → 实体`）；编排器每 tick 刷新一次，原语只读。 */
        Map<UUID, ItemEntity> liveIndex();

        /**
         * **如实退休一件**（去重 ＋ 单一失败归因 ＋ `DropPolicy` 策略归因 ＋ 残差取证日志）。
         *
         * <p>为什么这个出口住在编排器而不是原语里：① 它的计数（`unreachable` / `no_approach` /
         * `pickup_timeout` / `policy_blocked`）是 `SUMMARY` 的输入，而 `SUMMARY` 是编排器的职责；
         * ② 编排器自己的候选过滤（`too_far`）也走**同一个出口** —— 两份实现必定漂移
         * （`D-490` 同族教训：同一件事的第二份实现先漂移、后被当成两个事实）。
         */
        void retire(UUID id, String reason);

        /** 展示用：本任务累计进包数（仅出现在 `PICKUP_SLOW` 的文案里）。 */
        int collectedTotal();

        /** 展示用：本任务期望进包数（仅出现在 `PICKUP_SLOW` 的文案里）。 */
        int expectedTotal();

        /** 展示用：本任务的已运行 tick 数（仅出现在 `PICKUP_SLOW` 的文案里）。 */
        int taskTicks();
    }

    /**
     * **一次簇扫描的读数**（`reading()` 的产物；**判定面**，不是展示面）。
     *
     * <p>守恒式 `增量 == 起始 stack 总和 − 结束剩余存活 stack 总和` 由**编排器**组装
     * （`D-493` 拍点 1：守恒交叉校验属编排） ⇒ 这里只给三个原始量 + 日志要用的两个数。
     *
     * @param anchor    本簇最后的锚点（日志用；`begin` 之后必非 null）
     * @param members   本簇成员数（进入簇时的成员数，与改造前的 `clusterIds.size()` 同口径）
     * @param delta     背包增量口径的**实际**进包数（各类型 `countInInventory` 增量之和）
     * @param remaining 本簇结束时**仍在 `liveIndex` 里**的成员 stack 数量之和
     * @param startSum  本簇开始时全部成员的 stack 数量之和
     * @param sweepTicks 本簇烧掉的 tick 数（不含 `begin` 的那一 tick）
     * @param timedOut  本簇是不是**超预算/失败**收尾（false = 成员都被吸走/消失了）
     */
    public record Reading(BlockPos anchor, int members, int delta, int remaining, int startSum,
                          int sweepTicks, boolean timedOut) {
    }

    /**
     * `D-375` **残差取证**的读数（3-b/D0）：探针日志与夹具断言**共用这一份计算**
     * ⇒ 夹具断言的正是"真打出去的东西"，不必去扒日志文本。
     *
     * @param standable             枚举中可站的格数（两层合计）
     * @param standableAndReachable 既可站、又够得着的格数
     * @param standableDy0          `dy=0` 层的可站格数
     * @param standableDyMinus1     `dy=-1` 层的可站格数（**这一层原探针根本不扫**）
     * @param ring                  逐格 `(dx,dy,dz)stand=…/reach=…`（只列"可站或够得着"的，有界）
     */
    public record ApproachReading(int standable, int standableAndReachable, int standableDy0,
                                  int standableDyMinus1, String ring) {
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
    /** 一个簇内最多换几次锚点扫尾（覆盖簇边缘够不到的物品）。 */
    private static final int MAX_REANCHORS = 2;
    /** 等待"仍在空中下落的掉落物"落地的最大 tick 数（2026-09-10 修正）。 */
    private static final int MAX_SETTLE_TICKS = 40;
    /**
     * `approach_probe` 一行里最多列几格（**有界**：枚举本身最多 48 格 = 2 层 × (环1 8 + 环2 16)，
     * 这里只是防止"每格都可站/够得着"时日志行过长）。
     */
    private static final int PROBE_MAX_ENTRIES = 40;

    private final BotPlayer bot;
    private final boolean allowWorldModification;
    /**
     * 收集阶段的**能力信封**（D-116）：掉落物在头顶够不到时，允许用同一份"原地加高"能力上去拿。
     * 编排器把 {@link MiningProfile#STANDABLE_ONLY}（不允许加高）传进来 ⇒ 既有调用点行为完全不变。
     */
    private final MiningProfile gainProfile;
    /**
     * ⭐ **本簇的扫描预算**（tick，含走位与等待）—— **构造注入**（`D-466` 判 3 · D1）。
     *
     * <p>口径没变：编排器注入的就是改造前那个私有机制档 `CLUSTER_BUDGET_TICKS = 200`
     * （`D-493` 拍点 3 `3甲`：**private 机制档不动** ⇒ 常量留在编排侧，值由构造参数下来）。
     * 本类**不许**自带它的副本 —— 否则同一个数就有两个出处（`D-455` ⑧③）。
     */
    private final int sweepBudgetTicks;
    private final Host host;

    // ---- 任务级（跨簇累计；本类的实例活一个作业） ----
    /**
     * 加高（`D-116`）的**任务级**计数器：**不按簇重置**。
     *
     * <p>改造前它就是任务级的（`beginCluster`/`endCluster` 都没碰它）⇒ 语义是"这个收集任务总共
     * 最多加高 `gainProfile.maxGainSteps()` 步"。⚠️ 本刀**保持**这个语义（改它会静默放松/收紧
     * 加高上界）；上界本身来自注入的 {@link #gainProfile}，不是本类自带。
     */
    private int gainSteps;
    private GainStepRunner gainRunner;
    /** 累计被排除过的"够不到"目标格数（观测用，进 `SUMMARY goal_excluded=`）。 */
    private int goalExcludedCount;
    /** `D-375`：上报过的 `PICKUP_SLOW` / `PICKUP_DETOUR` 次数（每簇各至多一次）。 */
    private int slowEmits;
    private int detourEmits;
    /** `D-375`：最近一次**真的拿去规划**的目标格（夹具/取证用：它必须可站）。 */
    private BlockPos lastGoalFoot;

    // ---- 本簇状态（`begin` 建立、`reading()` 之后清空） ----
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
    private PathRetryRunner runner;
    /** 最近一次结束的簇的读数（`null` = 还没有簇结束过）。 */
    private Reading reading;

    /**
     * @param bot                    执行者
     * @param allowWorldModification `true` → `PathRequest.withWorldModification`（真实玩法调用点的默认口径），
     *                               `false` → 纯通行（`PathRequest.of`，夹具反证用）
     * @param gainProfile            收集阶段的能力信封（`null` ⇒ `STANDABLE_ONLY`）
     * @param sweepBudgetTicks       **本簇的扫描预算**（构造注入；见字段注释）
     * @param host                   编排器（窄接口；见 {@link Host}）
     */
    public CollectStep(BotPlayer bot, boolean allowWorldModification, MiningProfile gainProfile,
                       int sweepBudgetTicks, Host host) {
        this.bot = bot;
        this.allowWorldModification = allowWorldModification;
        this.gainProfile = gainProfile == null ? MiningProfile.STANDABLE_ONLY : gainProfile;
        this.sweepBudgetTicks = sweepBudgetTicks;
        this.host = host;
    }

    // ==================== 簇的生命周期 ====================

    /** 本类当前是否有一簇在跑（编排器据此决定"开始新簇"还是"推进当前簇"）。 */
    public boolean hasActiveCluster() {
        return clusterIds != null;
    }

    /**
     * **开始一簇**：建立本簇的全部状态（成员的起始 stack 总和 / 各类型背包基线 / 世界改动基线 /
     * 排除集）。
     *
     * <p>⚠️ **聚类（BFS）不在这里** —— 那是编排器的"逐簇迭代"（`D-493` 拍点 1）；本方法只吃
     * 已经分好组的 `clusterIds`。
     *
     * @param clusterIds 本簇成员（编排器给出的 `UUID` 列表，非空）
     */
    public void begin(List<UUID> clusterIds) {
        this.clusterIds = new ArrayList<>(clusterIds);
        Map<UUID, ItemEntity> live = host.liveIndex();
        ItemEntity seed = nearestOf(this.clusterIds, live);
        anchor = seed == null ? null : seed.blockPosition().immutable();
        clusterStartSum = 0;
        typeBefore = new LinkedHashMap<>();
        for (UUID id : this.clusterIds) {
            ItemEntity item = live.get(id);
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
                anchor == null ? "-" : anchor.toShortString(), this.clusterIds.size(), clusterStartSum,
                typeBefore.size());
    }

    /**
     * 推进当前簇一格。**只在一簇在跑时调用**（否则如实抛，绝不静默重开一簇）。
     *
     * <p>⚠️ 返回值是**簇**的形态，不是任务的：本簇只要结束了就一定是
     * {@link Outcome#FINISHED} —— 哪怕那一 tick 里"该做的决定"是"接着跑"
     * （改造前那些分支是 `endCluster(...); return Status.RUNNING;`，最容易在这里把
     * "簇结束了"读成"簇还在跑"）。
     */
    public Outcome tick(List<ItemEntity> live) {
        if (clusterIds == null) {
            throw new IllegalStateException("CollectStep.tick() 在 begin() 之前被调用");
        }
        Outcome outcome = tickOnce(live);
        return clusterIds == null ? Outcome.FINISHED : outcome;
    }

    private Outcome tickOnce(List<ItemEntity> live) {
        List<ItemEntity> members = liveMembers(live);
        if (members.isEmpty()) {
            return finishCluster(false);
        }
        // 掉落物**还不在接地状态**时不要追：它的"当前格"可能够不到，
        // 追它会得到假的 MOVEMENT_FAILED（2026-09-10 两次客户端实测：
        // ① 6/7 根原木时追下落中的物品；② 4/4 根全砍完但最后一根的掉落物被退役 → inventoryDelta=3）。
        // 等它落定（≤ MAX_SETTLE_TICKS）——落在簇内自然会被拾取，或随后正常走位去捡。
        if (members.stream().anyMatch(CollectStep::isAirborne)
                && ++settleTicks <= MAX_SETTLE_TICKS) {
            if (settleTicks == 1) {
                BotLog.info("[CollectDrops] settling members={}（等待空中的掉落物落地）", members.size());
            }
            cancelRunner();
            return Outcome.RUNNING;
        }

        sweepTicks++;
        if (sweepBudgetExhausted()) {
            for (ItemEntity member : members) {
                host.retire(member.getUUID(), "cluster_budget");
            }
            return finishCluster(true);
        }

        // ⭐ `D-375`：把"这一簇在磨 / 在改造地形"上报给决策层（**在烧完预算之前**）。
        reportCollectSymptoms(members);

        // 0) 正在加高（D-116）：先把它跑完 —— 完成后重锚并重试走位
        if (gainRunner != null) {
            GainStepRunner.State gainState = gainRunner.tick();
            if (gainState == GainStepRunner.State.RUNNING) {
                return Outcome.RUNNING;
            }
            gainRunner = null;
            if (gainState == GainStepRunner.State.DONE) {
                gainSteps++;
                BotLog.info("[CollectDrops] gain_done item={} steps={}/{} foot={}",
                        members.get(0).getUUID(), gainSteps, gainProfile.maxGainSteps(),
                        bot.blockPosition().toShortString());
                if (!reanchor(members)) {
                    retireNoApproach(members);
                }
                return Outcome.RUNNING;
            }
            BotLog.warn("[CollectDrops] gain_failed steps={}/{} → 如实退役",
                    gainSteps, gainProfile.maxGainSteps());
            for (ItemEntity member : members) {
                host.retire(member.getUUID(), "gain_failed");
            }
            return finishCluster(true);
        }

        // 0) 尚未走位、且还没进入拾取范围 → 建路径（走位优先；不建就会"原地放弃"）
        if (runner == null && members.stream().noneMatch(this::inPickupRange)) {
            // D-114：寻路目标必须是"**够得着掉落物的可站格**"，而不是掉落物所在格。
            // 反例（2026-09-11 实测）：支撑块被拆后掉落物停在平台格 (23,64,189) ✓，而锚点是
            // 刚拆掉的支撑块所在格 (23,64,190)——空气+下面也是空气 ⇒ 不可站 ⇒ UNREACHABLE ⇒ 退役残留。
            if (!normalizeAnchor(members)) {
                retireNoApproach(members);
                return Outcome.RUNNING;
            }
            // ⭐ **不变式（`D-375`）**：收集请求的 `GoalFoot` **必须可站**。
            // `normalizeAnchor` 已经保证了它；这里是"响了就说明上游破了"的硬闸 ——
            // 一个站不住的目标 = 要求寻路**挖进去**（真机 19 段挖掘回环的成因）。
            if (!isStandableCell(anchor)) {
                BotLog.warn("[CollectDrops] INVARIANT_VIOLATION goal_not_standable goal={} itemPos={}"
                                + "（锚点规范化破了：目标格站不住 ⇒ 拒绝规划，如实退休）",
                        anchor.toShortString(), members.get(0).blockPosition().toShortString());
                retireNoApproach(members);
                return Outcome.RUNNING;
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
                return Outcome.RUNNING;
            }
            if (state == PathRetryRunner.State.DONE) {
                cancelRunner();
                // **不提前 return**（2026-09-10 修正）：当目标格就是 bot 自己所在格时，
                // 请求退化成"从自己走自己" → `movements=0 / REACHED` → 本分支每 tick 命中一次，
                // 于是重建同一个请求、空转到簇预算才放弃。
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
                    return Outcome.RUNNING;
                }
                for (ItemEntity member : members) {
                    host.retire(member.getUUID(), reason);
                }
                return finishCluster(true);
            }
        }

        // 2) 已到位：只有**真的进入原版拾取范围**才等待（否则继续换锚点/如实退休）
        if (members.stream().anyMatch(this::inPickupRange)) {
            if (++waitTicks >= PICKUP_WAIT_TICKS) {
                if (reanchors < MAX_REANCHORS) {
                    // ⭐ `1.4z-C`（2026-09-26 真机取证）：3-b/D2 的"排除这一格"只补在下面
                    // `// 3) 到位但够不到` 那一支，**这一支漏了**（"模型说已经进入拾取范围、等满
                    // `PICKUP_WAIT_TICKS` 却没进包"）。真机逐字（子巷）：`簇起 anchor=-144,50,242`
                    // → 走 6 格 → 站 40 tick → `reanchor cluster_anchor=-144,50,242 reanchors=1/2`
                    // （**同一个格**）→ 再站 40 tick → `retire pickup_timeout` `delta=0 ticks=148`
                    // = **7.4 秒白跑**，而下一个收集任务还会把同一格从头再来一遍。
                    // 与 3) 同一处置：排除该格 ⇒ 换次优格，或如实退休（`D-375`）。
                    excludeFailedGoal();
                    if (reanchor(members)) {
                        return Outcome.RUNNING;
                    }
                    retireNoApproach(members);   // `D-375`：没有可站的可达格 ⇒ 不许规划，如实退休
                } else {
                    for (ItemEntity member : members) {
                        host.retire(member.getUUID(), "pickup_timeout");
                    }
                    return finishCluster(true);
                }
            }
            return Outcome.RUNNING;
        }

        // 3) 到位但够不到（物品卡在够不着的位置）→ 换最近成员再试，用尽后如实退休
        if (reanchors < MAX_REANCHORS) {
            // 3-b/D2：**这一格已经被证明"站上去也够不到"** ⇒ 记进本簇排除集，下一轮必须换**次优**格。
            // 没有这一条时 `reanchor` 会算出同一个格（真机第七/八轮：4 次同格 ≈ 5 秒，物品留在地上）。
            excludeFailedGoal();
            if (reanchor(members)) {
                return Outcome.RUNNING;
            }
            retireNoApproach(members);           // `D-375`：同上
            return Outcome.RUNNING;
        }
        logApproachProbe("not_in_pickup_range", nearestMember(members));
        for (ItemEntity member : members) {
            host.retire(member.getUUID(), "not_in_pickup_range");
        }
        return finishCluster(true);
    }

    /**
     * **收尾本簇并给出读数**（`D-493` 拍点 1：**计数**在原语、**守恒交叉校验**在编排器）。
     *
     * <p>⚠️ 这里**只**做三件事：算背包增量 · 算剩余存活 stack · 清空簇内状态。
     * `MISMATCH` 的判定与 `cluster_done` 那行日志都在**编排器**（照改造前的先后次序：
     * `MISMATCH` 先、`cluster_done` 后）。
     */
    private Outcome finishCluster(boolean timedOut) {
        int delta = 0;
        if (typeBefore != null) {
            for (Map.Entry<Item, Integer> entry : typeBefore.entrySet()) {
                delta += countInInventory(entry.getKey()) - entry.getValue();
            }
        }
        int remaining = 0;
        for (UUID id : clusterIds) {
            ItemEntity item = host.liveIndex().get(id);
            if (item != null) {
                remaining += item.getItem().getCount();
            }
        }
        reading = new Reading(anchor, clusterIds.size(), delta, remaining, clusterStartSum,
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
        return Outcome.FINISHED;
    }

    /** 最近一次结束的簇的读数（`null` = 还没有簇结束过）。 */
    public Reading reading() {
        return reading;
    }

    // ==================== 额度 ====================

    /**
     * ⭐ **本原语消费额度的唯一一处**（`D-466` 判 3 · D2，具名清单 = `tools/check-task-orchestration-split.py`
     * 的 `NAMED_BUDGET_SITES_5B`）：本簇的扫描预算烧完了吗。
     *
     * <p><b>为什么必须"恰好一处"且"具名"</b>：额度注入进来之后，如果它能被**两处**扣掉
     * （比如"走位扣一点、等待也扣一点"），"额度归 Job"就只剩一句口号 —— Job 给出去的那个数
     * 不再等于原语真正烧掉的上限。写成**一个具名方法**，是为了让"消费点"在代码里有名字、
     * 在门禁里有对应项：多一处 ⇒ 门禁红（静态可失败，不靠人记得）。
     */
    private boolean sweepBudgetExhausted() {
        return sweepTicks > sweepBudgetTicks;
    }

    /**
     * 「这一簇在磨」的上报阈值（tick）：**簇预算的一半**。
     *
     * <p>改造前它是私有常量 `CLUSTER_SLOW_TICKS = CLUSTER_BUDGET_TICKS / 2`（= 100 tick = 5 秒）。
     * 预算改成构造注入之后，它必须**从注入值派生** —— 写死 100 就会与注入的预算脱钩
     * （同一个"一半"在两个地方各算一次是要漂移的）。
     *
     * <p>取一半的依据（逐字保留）：正常一簇的固定开销（建立 / 等落地 / 拾取延迟 / 收尾）只有
     * 40~80 tick，走位按每格 1~2 tick ⇒ **100 tick 还没收手 = 明确的病态**；而真机实测的病态簇是
     * "烧满 200 tick（10 秒）后退役"。等烧满再报太晚（预算已经没了）。
     */
    private int slowReportTicks() {
        return sweepBudgetTicks / 2;
    }

    // ==================== 簇内机制 ====================

    /**
     * 3-b/D2：把"站上去也够不到"的**当前目标格**记进本簇排除集（下一轮 `reanchor` 就会取次优格）。
     *
     * <p>触发条件（只在两处）：走位**已完成**、`members` 里**没有一件**进入 `inPickupRange`，
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
            host.retire(member.getUUID(), "no_standable_approach");
        }
        finishCluster(true);
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
        if (!com.dddgn.alice.reach.MiningTuning
                .gainHorizontallyReachable(bot, nearest.blockPosition())) {
            BotLog.warn("[CollectDrops] gain_refused item={} 水平超出触及 ⇒ 不异地加高",
                    nearest.blockPosition().toShortString());
            return false;
        }
        BotLog.info("[CollectDrops] gain_start item={} itemPos={} botFeet={} steps={}/{}",
                nearest.getUUID(), nearest.blockPosition().toShortString(),
                bot.blockPosition().toShortString(), gainSteps + 1, gainProfile.maxGainSteps());
        gainRunner = new GainStepRunner(bot, gainProfile,
                com.dddgn.alice.write.WriteGrant.of("collect-drops",
                        com.dddgn.alice.write.WriteReason.STEP_PLACEMENT));
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
        ItemEntity nearest = nearestMember(members);
        if (nearest == null) {
            return false;
        }
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
     * <p>`5b` 刀② 起本方法随原语搬进 `task/collecting/`，**公开**是**故意**的：夹具要断言
     * "搜索的层 = 探针的层"，必须能直接调它（`tools/kernel-predicates.py` 的 `D-375` 判据 ⑧
     * 同时断言夹具**复用**它而不是自写一份近似判据）。
     */
    public static List<BlockPos> approachCandidates(BlockPos itemCell, int radius) {
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
        return com.dddgn.alice.reach.StandingPointSelector
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
     * <p>`5b` 刀② 起随原语搬包并**公开**：夹具必须复用它，不许自己再写一份近似判据
     * （"可规划即可执行"的同一条纪律：夹具若用另一套判据，就会**自己骗自己**）。
     */
    public static boolean withinPickupReach(BlockPos cell, ItemEntity item) {
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
     */
    public static boolean reachesFrom(AABB playerBox, ItemEntity item) {
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

    private List<ItemEntity> liveMembers(List<ItemEntity> live) {
        List<ItemEntity> members = new ArrayList<>();
        for (ItemEntity item : live) {
            if (clusterIds.contains(item.getUUID())) {
                members.add(item);
            }
        }
        return members;
    }

    /** 本簇里**离 bot 最近**的存活成员（取证/探针用；空列表 ⇒ `null`）。 */
    private ItemEntity nearestMember(List<ItemEntity> members) {
        return members.stream()
                .min(java.util.Comparator.comparingDouble(item -> bot.distanceToSqr(item)))
                .orElse(null);
    }

    /** 给定 id 列表里离 bot 最近的那个实体（`begin` 用；取不到 ⇒ `null`）。 */
    private ItemEntity nearestOf(List<UUID> ids, Map<UUID, ItemEntity> live) {
        ItemEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (UUID id : ids) {
            ItemEntity item = live.get(id);
            if (item == null) {
                continue;
            }
            double d = bot.distanceToSqr(item);
            if (d < bestDist) {
                bestDist = d;
                best = item;
            }
        }
        return best;
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

    // ==================== 对外读数（夹具/门禁） ====================

    /**
     * 3-b/D2：本作业累计**排除过多少个"站上去也够不到"的目标格**（夹具/门禁用）。
     *
     * <p>⚠️ 这里只有"排除格数"这一半；「因此被如实退休了几件」在编排器
     * （`CollectDropsTask.noApproachRetired()`）—— **退休出口只有编排器那一个**（见 {@link Host#retire}）。
     */
    public int goalExcludedTotal() {
        return goalExcludedCount;
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
     * `D-375`：最近一次**真的拿去规划**的目标格（`null` = 本任务从未规划过）。
     *
     * <p>为什么要暴露它：夹具必须能判"收集器挑的目标**可站**吗" —— 只看世界的最终状态是**不够**的，
     * 实测（2026-09-21 注入复现）会漏：目标不可站 ⇒ 计划里带一条破格边，但物品在**破格之前**
     * 就被原版拾取范围捞走了 ⇒ 世界零改动、看起来全绿，而缺陷（目标是站不住的格）已经在计划里了。
     */
    public BlockPos lastGoalFoot() {
        return lastGoalFoot;
    }

    /** K-3：把"当前寻路段是否安全"透传给取消方（`/alice stop` 会据此延后到安全点）。 */
    public boolean safeToCancel() {
        return runner == null || runner.safeToCancel();
    }

    /**
     * 收尾时的清理（`/alice stop`、任务超时、任务结束）：
     * 取消寻路 ＋ 停住走位（**终态幂等**：没有簇在跑时是空操作）。
     */
    public void cancel() {
        cancelRunner();
    }

    // ==================== 症状上报（`D-375`） ====================

    /**
     * ⭐ **收集阶段的"绕远 / 在改造地形"上报**（`D-375` 第 4 条；第六轮真机实测的缺口）。
     *
     * <p><b>为什么必须有</b>：决策层能看到的一切只有"任务终态 + 事件"，而**簇内的那 10 秒**
     * （真机实证：一条 19 段计划、破 6 格、烧满 200 tick 簇预算、`collected=0/13`）对它**完全不可见**
     * —— 截图里它还在说「不动…mined 在涨…继续观察」。挖矿作业的代价恰恰是在这段时间被烧掉的。
     *
     * <p><b>两条判据（各自每簇只报一次；滞回 = 换簇重新武装）</b>：
     * <ul>
     *   <li>{@code PICKUP_SLOW}：本簇已耗 {@link #slowReportTicks()} tick（簇预算的一半）还没收手；</li>
     *   <li>{@code PICKUP_DETOUR}：本簇期间**世界真的被改动过**（运行账增量 &gt; 0）
     *       ⇒ 为了捡一件掉落物在改造地形（真机取证：`by=collect-drops:attempt0:PATH_ACCESS` ×16）。</li>
     * </ul>
     *
     * <p>两条都走统一出口 {@code DecisionEvents.emit}（事件环 + 日志 + 通知决策层），于是决策层
     * **当场**可以停 / 换点 / 放弃这一簇，而不是等 600 tick 的总预算烧完才发现。
     * 自检窗口内 `GoalDirector` 会只记录不通知（既有守卫）。
     */
    private void reportCollectSymptoms(List<ItemEntity> members) {
        if (!slowReported && sweepTicks >= slowReportTicks()) {
            slowReported = true;
            slowEmits++;
            com.dddgn.alice.decision.DecisionEvents.emit(bot, "PICKUP_SLOW", "warn",
                    "拾取一簇已耗 " + sweepTicks + " tick 还没收手（成员 " + members.size() + " 件）",
                    "anchor=" + (anchor == null ? "-" : anchor.toShortString())
                            + " members=" + members.size() + " sweepTicks=" + sweepTicks
                            + " reanchors=" + reanchors + "/" + MAX_REANCHORS
                            + " collected=" + host.collectedTotal() + "/" + host.expectedTotal()
                            + " ticks=" + host.taskTicks());
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

    // ==================== 失败路径取证 ====================

    /** 包围盒的一行格式（`retire` 行既有 botBox 格式**逐字不变**，itemBox 共用它）。 */
    public static String fmtBox(AABB box) {
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

    /** `approach_probe` 的读数（与上面那行日志**共用同一份计算** ⇒ 夹具断言的就是"真打出去的东西"）。 */
    public ApproachReading approachReading(ItemEntity item) {
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
}
