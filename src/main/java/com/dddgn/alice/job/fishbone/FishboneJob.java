package com.dddgn.alice.job.fishbone;

import com.dddgn.alice.action.MineBlockRunner;
import com.dddgn.alice.action.WriteAudit;
import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.config.FishboneConfig;
import com.dddgn.alice.job.Job;
import com.dddgn.alice.job.mine.MineCandidateSource;
import com.dddgn.alice.job.mine.MineProductFilter;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.search.PathingStats;
import com.dddgn.alice.pathing.core.search.PlanningStatus;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.task.CollectDropsTask;
import com.dddgn.alice.task.MineTask;
import com.dddgn.alice.task.PathRetryRunner;
import com.dddgn.alice.task.Task;
import com.dddgn.alice.task.TaskTarget;
import com.dddgn.alice.task.mining.MiningBudget;
import com.dddgn.alice.task.mining.MiningProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * **鱼骨挖矿作业（`L3`；`D-386` 切片 1 = 主巷 + 返回，`D-437` 切片 2 = 支巷 + 顺手挖 + 收集）**。
 *
 * <p>设计原文 = {@code docs/plans/2026-09-21-鱼骨挖矿计划.md} §3（状态机 + 逐格动作）：
 * <ul>
 *   <li><b>切片 1</b>（计划 §7-1）：{@code PREPARE → EXCAVATE → RETURN → DONE}，
 *       判据 <b>`C1`（模板 = 事实）/ `C3`（搜索规模恒定 + 零 `SEARCH_LIMIT`）/ `C4`（可返回）/
 *       `C6`（诚实失败）</b>；</li>
 *   <li>⭐ <b>切片 2</b>（计划 §7-2）：{@code SPUR_RETURN / IN_PLACE / COLLECT} 三个相位，
 *       判据 <b>`C1`（含支巷）/ `C2`（露头矿进包）/ `C5`（不越界、不连锁）</b>；</li>
 *   <li>⭐ <b>切片 4</b>（`D-439`，2026-09-25 真机实测驱动）：<b>追簇</b> —— 暴露面从"模板格"
 *       扩成"**本次作业挖出来的面**"（{@link #dugCells}），且每挖掉一颗矿就扫它的 6 邻域
 *       ⇒ 矿脉深处的格顺着队列一层层进队，直到**触及范围**或**每单元上限**为止。
 *       尺寸不再写死：{@code config/alice-fishbone.toml}（主巷 / 中心距 / 支巷长 / 侧向 / 净高 / 上限）。</li>
 * </ul>
 *
 * <h2>分层（本项目的红线，不是风格问题）</h2>
 * <ul>
 *   <li><b>L3（本类）只决策/记账/终止</b>：取下一个模板单元、把终态理由说清楚；</li>
 *   <li><b>L2 只复用已验收的链路</b>：每格 = 一个 {@link MineTask}（`DIRECT` 模式，站位就在身后
 *       ⇒ **规划距离恒 1 格**），返回 / 支巷退路 = {@link PathRetryRunner}
 *       （⭐ 切片 5 `D-440`：`PathRequest.withPlacement` = **纯通行 + 只放不拆** —— 追簇把路面挖掉之后
 *       由规划器自然补一块再走；额度用完**如实退回** `PathRequest.of` 纯通行），收集 = {@link CollectDropsTask}；</li>
 *   <li>⚠️ **本类不新增 Movement、不改成本模型、不调 `planTunnel`**（计划 §1「明确不做」——
 *       搜索型规划正是鱼骨要绕开的东西）。</li>
 * </ul>
 *
 * <h2>逐格动作的精确顺序（计划 §3，防"把自己埋了"）</h2>
 * <ol>
 *   <li>先挖**前方脚位格**（站位 = 身后那格）；</li>
 *   <li>再挖**前方头位格**（净高 2 ⇒ 只这两格）；</li>
 *   <li>然后**走进刚挖出来那格** —— 由**下一个单元的 `MineTask`** 承担（它的站位候选恰好包含
 *       "我现在站的格" ⇒ 规划距离 1）；</li>
 *   <li>**脚位格下方不动**（不挖地板）⇒ 不会把自己挖穿。</li>
 * </ol>
 *
 * <h2>⭐ 切片 2 的三条纪律（计划 §10.1 / §10.2，用户 2026-09-21 裁定）</h2>
 * <ol>
 *   <li><b>支巷放弃 ≠ 主巷失败</b>（§10.2）：支巷单元挖不动 ⇒ 记 {@code spur_abandoned:<码>}、
 *       **跳过该支巷剩余单元**、沿支巷**原路退回主巷继续**；主巷单元挖不动 ⇒ {@code main_blocked:<码>}
 *       + 终态 FAILED + **先沿主巷返回起点**。这两档**不许合并**（合并过一次就会被真机归因骗到）。</li>
 *   <li><b>露头矿顺手挖</b>（§10.1）：只在**每个单元挖完的那一 tick** 扫**该单元自己的 6 邻域**
 *       （O(6×净高)/单元，**不全局扫、不周期扫**）；三条同时成立才算：① 是矿（
 *       {@link MineCandidateSource.Target}，不新增第二份矿物清单）② 是**本次作业挖出来的**暴露面
 *       （6 邻域里有一格是模板格**且现在已通行**）③ **顺手**（视线通 + 触及，与 `D-365` 就地挖
 *       **同一对谓词** {@link MineBlockRunner#inPlaceReachable}）。⇒ **不许"走去挖"**：
 *       消费前用同一对谓词复检，不达标就 {@code ore_deferred} 并丢弃（那是"跟随/探洞"的事，不是鱼骨）。</li>
 *   <li><b>收集半径从模板推导</b>（`D-346` 的教训："追取上限 &lt; 作业直径 ⇒ 判据永不成立"）：
 *       `prepares` 里已经把作用域半径设成 {@link FishboneTemplate#scopeRadius()}
 *       （含支巷），而 {@code CollectDropsTask.chaseLimit()} 是 `max(32, 2×作用域半径)` ⇒ **自动覆盖全作业面**。</li>
 * </ol>
 *
 * <h2>终态词表（`terminalReason()` 的取值）</h2>
 * <table border="1">
 *   <tr><th>码</th><th>含义</th><th>状态</th></tr>
 *   <tr><td>{@code template_complete}</td><td>模板全部挖穿并回到起点</td><td>DONE</td></tr>
 *   <tr><td>⭐ {@code template_complete_spurs_abandoned=<n>}</td><td>主巷做完、**有 `<n>` 条支巷被放弃**
 *       （§10.2 的"子巷放弃"档）—— 与 {@code template_complete} **分开**，否则决策层只看终态会以为"全挖完了"</td><td>DONE</td></tr>
 *   <tr><td>{@code start_unreachable}</td><td>起点不可站 <b>或</b> 纯通行规划证明不可达</td><td>FAILED（**零世界改动**）</td></tr>
 *   <tr><td>{@code start_search_incomplete}</td><td>起点可达性**没搜完**（`SEARCH_LIMIT`）—— `SEARCH_LIMIT ≠ UNREACHABLE`（`D-076`）</td><td>FAILED（**零世界改动**）</td></tr>
 *   <tr><td>{@code main_blocked:&lt;码&gt;</td><td>**主巷**前方**硬拒绝**（基岩/保护区/认领区/岩浆…）—— 归因码逐字取 `MineTask.isHardTargetRefusal` 那一族</td><td>FAILED（先返回再失败）</td></tr>
 *   <tr><td>{@code main_unreachable:&lt;码&gt;</td><td>**主巷**前方**挖得到但到不了**（占位/进入方案规划不出）</td><td>FAILED（先返回再失败）</td></tr>
 *   <tr><td>{@code spur_return_failed:&lt;码&gt;</td><td>支巷挖到端点/放弃之后**回不到主巷**</td><td>FAILED（先回家再失败）</td></tr>
 *   <tr><td>{@code product_not_collected}</td><td>`COLLECT` 之后**产物没进包**（&lt; 露头矿数）——
 *       `D-436 §一.2` 的例外条款：落物可能掉出可达范围，此时**如实失败**，不假装完成</td><td>FAILED（先返回再失败）</td></tr>
 *   <tr><td>{@code no_progress}</td><td>`STALL_TICKS` 内一格没推进</td><td>FAILED</td></tr>
 *   <tr><td>{@code goal_timeout}</td><td>`maxTicks` 用尽</td><td>FAILED</td></tr>
 *   <tr><td>{@code return_failed}</td><td>作业本身完成/失败之后**回不到起点**（不许静默留在洞里，`D-327` 同宗）</td><td>FAILED</td></tr>
 * </table>
 *
 * <p>⭐ **为什么"失败也要先回家"**：`§10.2` 逐字裁定「主巷遇不可挖/液体 ⇒ 报告 + 如实失败，
 * **且优先沿已挖通的主巷返回起点**（`D-327` 安全返回的思想：先回家再失败，不许把 bot 留在洞里）」。
 */
public final class FishboneJob implements Job {

    /** 停滞护栏：这么多 tick 一格没推进 ⇒ `no_progress`（不让作业悄悄空转）。 */
    public static final int STALL_TICKS = 400;

    /**
     * **"顺手挖"允许的站位微调上限（格，切比雪夫）**。
     *
     * <p>依据 = 2026-09-25 真机实测（`latest.log` 12:16:45 `ore_moved 顺手挖把 bot 带离了原位
     * -96, 47, 149 → -96, 47, 148`，正好 1 格）：`MineTask` 会先走到"目标正下方"这个**更优站位**
     * 再挖顶棚矿 ⇒ **1 格的微调是它的正常工作方式，不是"我们走过去了"**。
     * 超过这个数才说明"为了这颗矿真的移动过" ⇒ 记 `oreWalkedAway`（计划 §10.1 的"不必移动"被破坏）。
     */
    public static final int ORE_STAND_ADJUST_MAX = 1;

    /** 返回段/支巷退路段的段数上限（`PathRetryRunner` 的重规划次数；与其它调用点同口径）。 */
    private static final int RETURN_MAX_REPLANS = 2;

    /** 全模板挖完的终态（切片 1 口径）。 */
    public static final String TEMPLATE_COMPLETE = "template_complete";

    /** 支巷放弃的日志码前缀（`§10.2` 的注册码；终态里出现 `=` 计数版本）。 */
    public static final String SPUR_ABANDONED_PREFIX = "spur_abandoned:";

    /**
     * **"是不是矿"的唯一来源**（计划 §10.1 条件①：**不新增第二份矿物清单**）——
     * 复用生产谓词 {@link MineCandidateSource.Target}，取值 = Forge 的 `#forge:ores` 标签族
     * （模组矿物天然覆盖；与 {@link MineProductFilter} 的"产物"口径配套但**不是**同一件事：
     * 那个认掉落物，这个认世界里的方块）。
     */
    public static final MineCandidateSource.Target DEFAULT_ORE_TARGET =
            MineCandidateSource.Target.ofTag(TagKey.create(Registries.BLOCK,
                    new ResourceLocation("forge", "ores")));

    private enum Phase { PREPARE, EXCAVATE, SPUR_RETURN, IN_PLACE_APPROACH, IN_PLACE, COLLECT, RETURN, DONE }

    private final BotPlayer bot;
    private final FishboneTemplate template;
    private final ScopeBuffer scope;
    private final WriteGrant grant;
    private final int maxTicks;
    private final List<FishboneTemplate.Unit> units;

    private Phase phase = Phase.PREPARE;
    private MineTask current;
    private CollectDropsTask collector;

    // ==================== ⭐ `1.4z-B`（2026-09-26）：批量收集 ====================

    /** **一段活干完了**（支巷完工 / 追簇队列清空）⇒ 下一次"手上没活在飞"的 tick 起一次批量收集。 */
    private boolean collectDue;
    /** 本次收集是**收尾**（`true`：做 `product_not_collected` 判定后回家）还是**周期**（`false`：收完回工地）。 */
    private boolean collectIsFinal;
    /** 周期收集收完回哪个相位。 */
    private Phase collectReturnPhase = Phase.EXCAVATE;
    /** 本作业起了几趟**周期**收集（进 `SUMMARY` 的 `collects=`；真机/夹具靠它看"是不是还在逐格收"）。 */
    private int batchCollects;
    /** 安全下限的评估节流（`liveDrops()` 不必每 tick 扫）。 */
    private int nextAgeCheckTick;

    /**
     * ⭐ `1.4z-B`：**最老的产物落物在地上躺够多少 tick 就必须收一次**（**安全下限**，唯一出处）。
     *
     * <p>出处 = 原版 despawn 常数 − 一趟收集的余量（同 `D-346` 的 `chaseLimit`：口径由事实派生）：
     * `6000`（`ItemEntity.tick()` 里 `age >= 6000 ⇒ discard`，1.20.1 字节码 `sipush 6000` 实测）
     * − `2400`（2 分钟余量：单簇预算 200 tick + 走位，实测最坏一趟 ≈150 tick）⇒ `3600`。
     * ⚠️ **不许 ≥6000**（那等于"等它消失再收"，产物必丢）。
     *
     * <p><b>主判据仍是结构边界</b>（{@link #requestBatchCollect}）—— 本条只是**下限**：作业预算
     * `maxTicks` 默认 86800（≈72 分钟），一段活可能比 5 分钟长。
     */
    private static final int COLLECT_BEFORE_DESPAWN_TICKS = 3600;
    private PathRetryRunner returnRunner;
    private PathRetryRunner spurReturnRunner;

    /** 下一个待处理的**单元**下标（`units` 的口径）。 */
    private int unitIndex;
    /** 当前单元里下一个待处理的格（0..`height-1`）。 */
    private int cellInUnit;
    private int advanced;           // 已完整处理（挖穿/本来就通）的单元数（不含被放弃的支巷单元）
    private int skipped;            // 本来就通行的格（不重复挖）
    private int mined;              // 我方真的挖掉的**模板**格数
    private int ticks;
    private int stallTicks;
    private long lastProgressMark = -1L;
    private boolean excavationFailed;
    private String terminalReason = "";
    private Task.Status terminalStatus = Task.Status.FAILED;

    // ---- 切片 2：支巷 ----
    private int spursAbandoned;
    private int spurUnitsSkipped;
    private int spurReturns;
    private String firstSpurAbandonReason = "";
    private BlockPos spurReturnTarget;
    private FishboneTemplate.Unit pendingSpurReturn;

    // ---- 切片 2：露头矿顺手挖 ----
    private final Deque<BlockPos> oreQueue = new ArrayDeque<>();
    /**
     * **已结案**的候选格：要么已入队/已挖掉，要么 ① 不是矿、② 不是我们挖出来的面。
     *
     * <p>⚠️ 切片 4 起**不再**包含"③ 现在够不着"的格（那些进 {@link #chasePending}）——
     * 用同一个集合记两种语义，会让"站远了一点点"变成永久放弃。
     */
    private final Set<BlockPos> oreSettled = new LinkedHashSet<>();
    /**
     * ⭐ **本次作业挖出来的面**（切片 4，`D-439`）= 已挖通的**模板单元格** ∪ 已挖掉的**矿格**。
     *
     * <p>为什么条件②必须用**这个集合**而不是 {@code template.cellSet()}：
     * 2026-09-25 真机实测（截图 + `latest.log`）—— 矿簇**只挖掉贴巷道那一层**就停了。
     * 结构原因：矿挖掉第一格之后，第二格旁边挨着的是**刚挖出来的矿洞**、**不是模板格**
     * ⇒ 用模板格当"暴露面"时②**必然不成立** ⇒ 矿簇永远只挖一层。
     * 计划 §10.1 早就裁定了正确的口径：「⭐ 追踪整个矿簇，上限 = 目标额度」——
     * 本集合就是那句裁定的**唯一判据**：**我们挖出来的面**才算暴露面（不是"看到矿就去挖"）。
     */
    private final Set<BlockPos> dugCells = new LinkedHashSet<>();
    /**
     * ①+② 成立、但**当前站位够不着**（③ 为假）的矿格。
     *
     * <p>⚠️ 这些格**不永久否**（`oreSeen` 只记"已结案"的格）：站到更好的位置之后要能重评 ——
     * 2026-09-25 夹具实测（`D-439`）：矿脉第二层在 bot 站在**身后一格**时视线被巷道壁挡住，
     * 站进刚挖完的那一格就够得着了。集合非空 ⇒ 下一次单元收尾会先走"游走段"。
     */
    private final Set<BlockPos> chasePending = new LinkedHashSet<>();
    private PathRetryRunner chaseApproach;
    /** 放置额度用尽只提示一次（否则每次走位刷一行）。 */
    private boolean placeBudgetExhaustedLogged;

    /**
     * ⭐ **逐格挖掘的能力信封**（`D-443` 裁定 1a，2026-09-25）：`STANDABLE_ONLY`（只用现成可站站位、
     * 不挖隧道）+ **接近允许「补一块再走」**（`A14` 的同一个集合：`PLACE_STEP` + `PILLAR` + `FALL`，只放不拆）。
     *
     * <p>为什么必须给接近这一步：真机（2026-09-25 16:21）逐字读数 —— 同一次失败里，挖掘站位用纯通行接近
     * ⇒ `no_reachable_standing_point`（`descend_precondition=2668`），而**同一 tick、同一位置**
     * 鱼骨自己的 `SPUR_RETURN`（`withPlacement`）`status=REACHED cost=14.31`
     * ⇒ 「能不能到」是同一个问题，不该由两个能力集给出两个答案（`survey/34 §2.1`）。
     *
     * <p>⚠️ 额度与上限仍在作业侧：`C8` 的三条上限 + `A14` 的累计额度（`bridgeBlockBudget()`）照旧管着
     * 模式 A 规划出来的放置（归因串随 `grant.requester()` = `fishbone*` ⇒ 计得进 `placementsUsed()`）。
     */
    private static final MiningProfile CELL_PROFILE = MiningProfile.STANDABLE_ONLY.withPlacementApproach();

    /**
     * **本次调用时的逐格挖掘信封**（`D-443` 裁定 7b）：还有搭路额度 ⇒ {@link #CELL_PROFILE}
     * （接近可以补一块）；额度用尽 ⇒ 退回 {@link MiningProfile#STANDABLE_ONLY}
     * ⇒ 挖掘接近**不再放置**（否则额度会被"挖掘接近"这条路径悄悄绕过，作业侧看不见）。
     */
    private MiningProfile cellProfile() {
        return placementsUsed() < bridgeBlockBudget() ? CELL_PROFILE : MiningProfile.STANDABLE_ONLY;
    }
    private FishboneTemplate.Unit chaseApproachUnit;
    private MineTask oreTask;
    private BlockPos oreStartFeet;
    /** 本颗露头矿的**破坏是否已完成**（用于"位移"只在破坏那一刻量；见 {@link #inPlace()}）。 */
    private boolean oreBroken;
    private int scannedUnits;
    /** 走位之后的**重扫**次数（切片 4；与 `scannedUnits` 分开 ⇒ `C9` 的口径不被追簇偷改）。 */
    private int rescans;
    private int oreFound;
    /** **真的挖掉的矿格数**（= 世界改动，在**破坏那一刻**记账；收集成败另记 `oreUncollected`）。 */
    private int oreMined;
    private int oreDeferred;
    private int oreWalkedAway;
    /**
     * 每个模板单元最多消费几个候选（`FishboneConfig.oreBudgetPerUnit`）——**构造时读一次**。
     *
     * <p>为什么读进字段而不是每 tick 查配置：作业跑起来之后配置不该再变（改了也要下一次右键才生效），
     * 而"作业中途预算忽然变了"会让同一轮作业的前后段不可比。
     */
    private final int oreBudgetPerUnit;
    /** **单段连续悬空上限**（`C8` 第一条，`D-443` 裁定 1b）——构造时读一次。 */
    private final int maxGapLength;
    /** 本单元已经消费掉几个候选（`oreBudgetPerUnit` 的分子；每单元开始清零）。 */
    private int oreBudgetUsed;
    /** 触发 `ore_budget_exhausted` 的次数（护栏生效的证据；0 = 自然上界比护栏更紧）。 */
    private int oreBudgetExhausted;
    /**
     * **挖掉了但掉落物没进包**的矿格数（= 目标变空气了、但 `MineTask` 的收集段失败）。
     *
     * <p>⚠️ 与 `oreDeferred` **分开**：`oreDeferred` = "**没挖**"（复检不达标 / 任务失败），
     * `oreUncollected` = "**挖了、东西可能留在洞里**"。把这两件事压成一个数，
     * 真机归因就会把"没挖"说成"丢了"，或反过来。
     */
    private int oreUncollected;

    // ---- 切片 2：收集 ----
    private int itemsBefore;
    private int collectedProducts;

    // ---- 切片 3：真机取样用的结构化日志读数（计划 §8）----
    /** 已完整处理（挖穿/本来就通）的**主巷**单元数（= 日志里 `advance=` 的分子）。 */
    private int mainUnitsDone;
    /** 是否真的开过挖（`start_unreachable` 时没开过 ⇒ `return=n/a`）。 */
    private boolean excavationStarted;
    /** `RETURN` 段是否成功回到起点。 */
    private boolean returnedHome;
    private boolean summaryEmitted;
    private PathingStats.Scale scaleAtStart = new PathingStats.Scale(0L, 0L, 0L, 0L);
    private long gameTimeAtStart;
    /**
     * 本次作业真的挖掉的**露头矿/追簇矿**格（`SUMMARY` 的 `outside=` 白名单要用）。
     *
     * <p>⚠️ 切片 4 起改成**在破坏那一刻**登记（原来在 `MineTask` 成功返回时才登记）：
     * 追簇的深格矿常常"挖得掉、捡不回" ⇒ 收集段失败也会让任务 FAILED，而**世界已经改了**。
     * 白名单必须描述"世界被我们改成了什么样"，不是"任务成功了几次"。
     */
    private final Set<BlockPos> oreBrokenCells = new LinkedHashSet<>();

    public FishboneJob(BotPlayer bot, FishboneTemplate template, ScopeBuffer scope, int maxTicks) {
        this.bot = Objects.requireNonNull(bot, "bot");
        this.template = Objects.requireNonNull(template, "template");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.maxTicks = maxTicks;
        this.units = template.units();
        // 世界写入授权（`D-082`）：鱼骨挖的是**模板几何列出的格** + **本次作业自己挖出来的暴露面矿**，
        // 不是"随手挖掉的阻挡" ⇒ 一次性授权即可，理由码复用 `BULK_EDIT`
        //（"批量地形编辑（道路施工等非寻路场景）"）—— 计划 §4 明确**不新增枚举值**。
        this.grant = WriteGrant.of(REQUESTER, WriteReason.BULK_EDIT);
        // ⚠️ **产物基线必须在构造时取**（与 `MineJob` 同口径）：掉落物常常在**挖掉那一 tick 就被玩家
        // 捡起**（脚下就是掉落点），等到 `COLLECT` 相位再取基线 ⇒ 增量恒为 0（首版实测：
        // `COLLECT 开始 … 产物=3` ⇒ `产物=+0`，而背包里明明躺着 3 个原铁）。
        this.itemsBefore = countProductItems();
        this.oreBudgetPerUnit = FishboneConfig.oreBudgetPerUnit();
        this.maxGapLength = FishboneConfig.maxGapLength();
    }

    /** 世界写入的 requester（`WritePolicyMatrix.PREFIX_RULES` 里登记为 `MINING` 类）。 */
    public static final String REQUESTER = "fishbone";

    // ==================== Job 契约 ====================

    @Override
    public String jobName() {
        return REQUESTER;
    }

    @Override
    public String terminalReason() {
        return terminalReason;
    }

    @Override
    public String failureReason() {
        return terminalStatus == Task.Status.FAILED ? terminalReason : "";
    }

    @Override
    public String progressSummary() {
        return "advance=" + advanced + "/" + units.size()
                + " mined=" + mined + " skipped=" + skipped
                + " spurReturns=" + spurReturns + " spursAbandoned=" + spursAbandoned
                + " ore=" + oreMined + "/" + oreFound + " phase=" + phase;
    }

    /** 目标高亮：指向当前正在处理的模板格（没有就指起点）。 */
    @Override
    public TaskTarget target() {
        BlockPos cell = unitIndex < units.size() ? units.get(unitIndex).foot() : template.startFoot();
        return TaskTarget.block(cell);
    }

    @Override
    public Task.Status tick() {
        ticks++;
        if (ticks > maxTicks && phase != Phase.DONE) {
            // 预算用尽也必须**先回家**（同 §10.2 的纪律），除非当前已经在返回段。
            if (phase != Phase.RETURN) {
                terminalReason = "goal_timeout";
                excavationFailed = true;
                beginReturn();
            }
        }
        // ⭐⭐ `1.4z-B`：**批量收集的唯一触发点**（判据只有 `batchCollectDue()` 一处）。
        // 改前是"每挖一格收一趟"（`MineTask` 自带收集段）—— 真机实测：拾取簇 199 趟 = 25% 的 tick，
        // 且逐格收集**必须站在原地等**那一格的落物（落地 + 原版 10 tick 拾取延迟，每趟 ~9 tick）
        // ⇒ 子巷 65 s 里只挖掉 4 格、却起了 12 个收集簇（7 个白跑）、收集占 81% 时间。
        if (batchCollectDue()) {
            startCollect(false);
            return Task.Status.RUNNING;
        }
        return switch (phase) {
            case PREPARE -> prepare();
            case EXCAVATE -> excavate();
            case SPUR_RETURN -> spurReturn();
            case IN_PLACE_APPROACH -> chaseApproach();
            case IN_PLACE -> inPlace();
            case COLLECT -> collectPhase();
            case RETURN -> returnPhase();
            case DONE -> terminalStatus;
        };
    }

    // ==================== PREPARE ====================

    /**
     * 起点合法性 + 生成模板单元序列 + 开作业作用域（计划 §3 `PREPARE`）。
     *
     * <p>⚠️ **两条判据必须分开**（`D-076`）：`UNREACHABLE`（真到不了）与 `SEARCH_LIMIT`（**没搜完**）
     * 是不同的事实 ⇒ 不许把它们压成同一个终态理由。
     */
    private Task.Status prepare() {
        ServerLevel level = bot.serverLevel();
        BlockPos start = template.startFoot();
        // ⭐ 切片 3（计划 §8）：`searchNodes`/`outside=` 两个读数的**窗口起点** —— 从本作业第一 tick 起算。
        scaleAtStart = PathingStats.scale();
        gameTimeAtStart = level.getGameTime();
        if (!MovementHelper.canStandCentered(level, start)) {
            BotLog.warn("[Fishbone] start_unreachable 起点不可站 start={}（零世界改动）", start.toShortString());
            return finishFail("start_unreachable");
        }
        PathPlan plan = new CorePathPlanner().plan(bot, level,
                PathRequest.of(bot.getUUID().toString(), bot.blockPosition(), start, REQUESTER + "-prepare"));
        if (!plan.reached()) {
            String code = plan.status() == PlanningStatus.SEARCH_LIMIT
                    ? "start_search_incomplete" : "start_unreachable";
            BotLog.warn("[Fishbone] {} 起点可达性判定 status={} feet={} start={}（零世界改动）",
                    code, plan.status(), bot.blockPosition().toShortString(), start.toShortString());
            return finishFail(code);
        }
        // ⭐ 切片 2：作用域半径**从模板推导**（含支巷）—— `CollectDropsTask` 的追取上限
        // 是 `max(32, 2×本半径)` ⇒ 这一行就是 `C2` 能成立的前提（`D-346` 的教训）。
        scope.begin(start, template.scopeRadius(), bot.getUUID());
        // ⭐ `I5` **放置面**（2026-09-25 用户裁定「先治根因，让他不会在作业区放方块」）：
        // 本作业自己那条通道的**通道层格**（每个单元的脚位格 + 其上 `height-1` 格）在施工期间
        // **不许被放方块**。真机靶子（第四轮）：`collect-drops` 的 PILLAR 把圆石放进
        // `-65,54,181`（2 秒前 bot 刚走过的通道脚位格）⇒ 那个格从"回程 FALL 的合法落点"
        // 变成实心 ⇒ 零破坏的回家路被自己切断 ⇒ `return_failed` ⇒ 整个作业 FAILED。
        //
        // 判据取 `template.cellSet()`（= `C1`/`C5` 的同一份形状真源，**不另抄一份几何**）；
        // 闸门在 `BlockInteraction.placementRefusal`（规划期剪枝 + 执行期最后一道闸门共用）。
        //
        // ⚠️ **必须装在这里、不许挪进构造器**：`BotManager.assignFishboneJob` 是
        // `new FishboneJob(...)` **先求值**、再 `session.beginTask(job, ...)`，而后者函数体里会
        // `TaskTargetProtection.end(bot)` ⇒ 构造器里装的作用域会被**同 tick 清掉**。
        Set<BlockPos> channelCells = template.cellSet();
        com.dddgn.alice.action.TaskTargetProtection.beginChannel(bot, jobName(), channelCells::contains);
        BotLog.info("[Fishbone] channel_reserved cells={}（I5 放置面：本作业自己的通道层格不许被放方块）",
                channelCells.size());
        BotLog.info("[Fishbone] start template={} scopeRadius={} maxTicks={}",
                template.describe(), template.scopeRadius(), maxTicks);
        excavationStarted = true;
        emitStart();
        phase = Phase.EXCAVATE;
        return Task.Status.RUNNING;
    }

    // ==================== EXCAVATE ====================

    private Task.Status excavate() {
        if (unitIndex >= units.size()) {
            startCollect(true);
            return Task.Status.RUNNING;
        }
        FishboneTemplate.Unit unit = units.get(unitIndex);
        ServerLevel level = bot.serverLevel();

        // ⭐ `C8` 推进门（`D-443` 裁定 1b/7b，2026-09-25）：**每个单元开头**先判"这一段还能不能推进"。
        // 判据只有一处（{@link #advanceRefusal}）、**处置**只有一处（{@link #advanceRefusalIsHard}）；
        // 两档与挖不动**同一套**（§10.2：支巷 ⇒ 只放弃那条支巷；主巷 ⇒ 如实失败）。
        if (current == null && cellInUnit == 0) {
            String refusal = advanceRefusal(unit);
            if (refusal != null && advanceRefusalIsHard(unit.isSpur(), refusal)) {
                if (unit.isSpur()) {
                    abandonSpur(unit, refusal);
                } else {
                    String code = "main_unreachable:" + refusal;
                    BotLog.warn("[Fishbone] {} 主巷单元 {} 无法推进 target={} ⇒ 先沿主巷返回起点再失败",
                            code, unitIndex + 1, unit.foot().toShortString());
                    terminalReason = code;
                    excavationFailed = true;
                    beginReturn();
                }
                return Task.Status.RUNNING;
            }
            if (refusal != null) {
                // ⭐ 主巷的**前瞻档只是提示**（用户 2026-09-25 真机裁定：「bot 不能因为起点开始方向是空气
                // 就不动，直接当成主巷一部分就行」——当时 bot 就在矿洞/空腔里）。
                // 为什么可以让它过去：`big_cavern_ahead` 是**预测**（前瞻 N 格看不到地板），而预测不许当判决
                //（与 `SEARCH_LIMIT ≠ UNREACHABLE` 同一条纪律，`D-329`）；真正的停手交给**计数事实**
                //（搭路额度用尽）或单元自己的失败码（`no_reachable_standing_point` / `no_throwaway_blocks` …）。
                BotLog.warn("[Fishbone] 主巷单元 {} 前瞻提示 {} target={} ⇒ 按裁定**继续推进**"
                                + "（已通的格按\"已通\"跳过；只有搭路额度用尽才如实失败）",
                        unitIndex + 1, refusal, unit.foot().toShortString());
            }
        }

        if (current == null) {
            if (cellInUnit >= template.height()) {
                finishUnit(unit);
                return Task.Status.RUNNING;
            }
            BlockPos cell = unit.foot().above(cellInUnit);
            if (isAlreadyPassable(level, cell)) {
                skipped++;
                cellInUnit++;
                return Task.Status.RUNNING;
            }
            BotLog.info("[Fishbone] cell unit={}/{} cell={}/{} target={} spur={}",
                    unitIndex + 1, units.size(), cellInUnit + 1, template.height(),
                    cell.toShortString(), unit.isSpur() ? unit.spurDir().getName() + unit.spurStep() : "-");
            current = new MineTask(bot, cell, scope,
                    // ⭐ `1.4z-B`：**逐格收集关掉**（`collectDrops=false`）—— 收集改由本作业在
                    // **结构边界**批量做（`batchCollectDue()`）。关掉只跳过"拾取"这一段：
                    // `MineTask.enterCollection()` 会走 `enterRestoreOrDone()` ⇒ 支撑块建拆照旧。
                    MiningBudget.forTarget(bot, level, cell, false),
                    // 站位只用**现成可站**的格：鱼骨的站位永远在身后一格 ⇒ 不需要规划器自己挖隧道
                    //（`STANDABLE_ONLY` = 计划 §5 方案 A 的"每格 1 次、距离恒 1 格"）。
                    // ⭐ `D-443` 裁定 1a（2026-09-25）：**接近能力**升到「补一块再走」（与 `A14` 同一集合）——
                    // 真机实测：追簇把 bot 带到通道层之外时，挖掘站位曾因「接近 = 纯通行」判
                    // `no_reachable_standing_point` 而整条支巷被放弃，而**同一 tick** 鱼骨自己的走位却 `REACHED`。
                    cellProfile(), grant,
                    // ⭐ `1.4z`：**主动拾取清单 = 产物过滤器**（与 `countProductItems()` 同一个 `PRODUCT_FILTER`
                    // ⇒ 判据只有一个出处）。石头族落物不进候选：真机实测那 61% 的石头点名声就是它的代价。
                    PRODUCT_FILTER::matches);
            return Task.Status.RUNNING;
        }

        Task.Status status = current.tick();
        if (status == Task.Status.RUNNING) {
            stallGuard();
            return Task.Status.RUNNING;
        }
        if (status == Task.Status.DONE) {
            mined++;
            current = null;
            cellInUnit++;
            stallTicks = 0;
            return Task.Status.RUNNING;
        }
        // ⚠️ 失败：**先分"主巷 / 支巷"两档**（`§10.2` 用户裁定），再按 `MineTask` 自己的硬拒绝名单归因
        //（`J-6`：拒绝清单只许有一处定义）。
        String reason = current.failureReason();
        current = null;
        if (unit.isSpur()) {
            abandonSpur(unit, reason);
            return Task.Status.RUNNING;
        }
        String code = (MineTask.isHardTargetRefusal(reason)
                ? "main_blocked:" : "main_unreachable:") + reason;
        BotLog.warn("[Fishbone] {} 主巷单元 {} 挖不动 target={} reason={} ⇒ 先沿主巷返回起点再失败",
                code, unitIndex + 1, unit.foot().toShortString(), reason);
        terminalReason = code;
        excavationFailed = true;
        beginReturn();
        return Task.Status.RUNNING;
    }

    /**
     * 一个单元处理完（挖穿 / 本来就通）：推进下标 ⇒ ⭐ 扫露头矿 ⇒ 该退回主巷就先退。
     *
     * <p>顺序**逐字**按计划 §10.1：「扫到就进顺手挖队列，**在下一个推进动作之前**逐个消费」。
     */
    private void finishUnit(FishboneTemplate.Unit unit) {
        advanced++;
        if (!unit.isSpur()) {
            mainUnitsDone++;
        }
        scanForOre(unit);
        boolean lastOfSpur = unit.isSpur() && unit.spurStep() == template.spurLength();
        unitIndex++;
        cellInUnit = 0;
        stallTicks = 0;
        oreBudgetUsed = 0;      // ⭐ 切片 4：每单元上限的分母按单元重置

        emitProgress(unit);
        // ⭐ 切片 4：追簇的**游走段** —— 队列里有货（或有"够不着"的候选）时，先走进**刚挖完的那一格**
        // 再消费。为什么必须走这一步（夹具实测的硬事实，不是推测）：设计上每个单元都是"站在身后一格挖"，
        // 于是消费队列时 bot 站在**矿脉列的后一格** ⇒ 视线被巷道壁挡住 ⇒ 矿脉第二层 ③ 判"够不着"
        //（`ore_eval pos=3763,80,2402 exposed=true reachable=false feet=3762,80,2400`）。
        // 走进那一格之后视线顺着矿脉轴 ⇒ 一层层往里追得到。
        ServerLevel level = bot.serverLevel();
        // ⭐ 切片 5（`D-440`）：**作业面自己站不住了也要走** —— 追簇允许往下挖，而地板往往就是矿簇
        // 本身 ⇒ 挖掉之后脚位格失去支撑（真机实测 `belowSolid=false`、`faceStandable=0/6`
        // ⇒ 站位搜索 0 候选 ⇒ `spur_abandoned:no_reachable_standing_point`）。
        // 这一步的请求带 `PLACE_STEP_AND_TRAVERSE` ⇒ 规划器**自然**在下方补一块再走上去
        //（不写"修复地板"的专门流程）。⚠️ 所以这里**不再**要求"目标格现在可站"。
        boolean workCellNotStandable = !MovementHelper.canStandCentered(level, unit.foot());
        boolean wantWorkCell = !oreQueue.isEmpty() || !chasePending.isEmpty() || workCellNotStandable;
        if (wantWorkCell && !MovementHelper.footCell(level, bot).equals(unit.foot())) {
            chaseApproachUnit = unit;
            chaseApproach = null;
            pendingSpurReturn = lastOfSpur ? unit : null;
            phase = Phase.IN_PLACE_APPROACH;
            return;
        }
        if (!oreQueue.isEmpty()) {
            // 顺手挖排在"下一个推进动作"之前；支巷退路**记着**，等队列空了再做（`IN_PLACE` 出口处理）。
            pendingSpurReturn = lastOfSpur ? unit : null;
            oreTask = null;
            phase = Phase.IN_PLACE;
            return;
        }
        if (lastOfSpur) {
            requestBatchCollect("spur_done");   // ⭐ `1.4z-B`：支巷完工 = 一段活干完
            beginSpurReturn(unit);
        }
    }

    /** 停滞护栏（`no_progress`）：**按"有没有推进"记账**，不按"tick 有没有跑"。 */
    private void stallGuard() {
        // ⚠️ 切片 4：**暴露矿的活动必须计入"推进"** —— 追簇会在一个单元里连挖十几颗矿，
        // 那段时间 `unitIndex/cellInUnit/mined` 全都不变 ⇒ 旧算式会把"正在好好追矿"报成
        // `no_progress`（400 tick 护栏）。记的是"有没有发生事"，不是"单元有没有前进"。
        long mark = (long) unitIndex * 100_000_000L + (long) cellInUnit * 10_000_000L
                + (long) mined * 10_000L + (long) skipped * 100L
                + (long) oreMined * 10L + oreDeferred;
        if (mark != lastProgressMark) {
            lastProgressMark = mark;
            stallTicks = 0;
            return;
        }
        if (++stallTicks > STALL_TICKS) {
            BotLog.warn("[Fishbone] no_progress 连续 {} tick 没推进（unit={}/{} cell={} mined={}）⇒ 如实失败",
                    stallTicks, unitIndex + 1, units.size(), cellInUnit, mined);
            terminalReason = "no_progress";
            excavationFailed = true;
            beginReturn();
        }
    }

    /**
     * 一格是否**已经可通行**（跳过、不重复挖）。⭐ **判据是单格的**（`F1` / `1.4j①` / `D-443` 片 C `P1`）。
     *
     * <p>⚠️ **2026-09-26 真机第五轮把这个判据钉死了（4/4）**：本方法原先返回
     * {@link MovementHelper#bodyPassable}（= 本格 **∧ 上面一格**），而调用方给的是
     * {@code cells()} **逐格**展开的格子 ⇒ 判据与粒度不匹配，两个后果：
     * <ul>
     *   <li><b>被问的是头位格时</b>（`cell=2/2`，本轮 4/4 次失败都在这一格），判据实际在问
     *       "头位 + **天花板**"，而通道天花板**恒为实心**（本轮任务只挖 `cells()`，从不碰天花板）
     *       ⇒ 头位格**永远不被判成"已通"** ⇒ 去 {@code MineTask} 挖一格**已经是空气**的格子
     *       ⇒ `LineOfSightChecker` 永远看不见空气 ⇒ `no_valid_standing_point`
     *       ⇒ 主巷 ⇒ **整个作业失败**（用户报的「主巷有一格空的，他就放弃了」）；</li>
     *   <li><b>被问的是脚位格，且该格是"半成品格"时</b>（脚位已空 + 头位实心），同样问成两格 ⇒
     *       去挖空气 ⇒ 同一条死路。</li>
     * </ul>
     * <p>用户的现场感受就是「**要起点旁边有一堵墙才能启动**」—— 因为只有"前方第一格是实心"
     * 才会走"正常挖"那条路；前方若已是空气（本轮的已开采区里是常态）⇒ 5 tick 内必死。
     *
     * <h3>为什么单格谓词就自洽（三步同时成立）</h3>
     * 因为 `cells()` 本来就**逐格**展开（每单元 = 脚位格 + 头位格），所以：
     * <ol>
     *   <li>脚位格空气 ⇒ 跳过；头位格空气 ⇒ 跳过 ⇒ 单元完成（**不再产生"挖空气"的请求**）；</li>
     *   <li>脚位格实心 ⇒ 照常 `MineTask`（新走廊照挖，**没把正常路径禁掉**）；</li>
     *   <li>⭐ **半成品格**（脚位空 + 头位实）⇒ 脚位跳过、**头位格照常被挖** ⇒ 单元补齐 ——
     *       这就是 `I3`（不留半成品格）在**同一处判据**下的落地形态；`F1` 的红臂第三条专钉它。</li>
     * </ol>
     *
     * <p>判据仍是"**不用挖**"而不是"是不是空气"（保留 1 格高洞 / 草 / 水那类**非空气但可通行**的格）——
     * 但**再看本格自己**：`canWalkThrough(cell)`，不看上面那一格（那属于"两格身位"的语义，
     * 由 `MovementHelper.bodyPassable` 表达，两者刻意分开）。
     *
     * <p>⚠️ 公开为 `static` 是给夹具复用**同一处出处**用的（先例：`advanceRefusalIsHard`，
     * `FishboneSlice2CheckTask` 直接判生产判据，不在夹具里照抄一份 `if`）。
     */
    public static boolean isAlreadyPassable(ServerLevel level, BlockPos cell) {
        return MovementHelper.canWalkThrough(level, cell);
    }

    // ==================== 切片 2：支巷（§10.2 两档处置） ====================

    /**
     * ⭐ **`C8` 推进门**（计划 §10.3 三条上限，`D-443` 裁定 1b/7b，2026-09-25 落地）：
     * 返回 `null` = 可以推进；否则返回**归因码**（由调用方按主巷/支巷两档如实处置）。
     *
     * <p>两条（第三条"搭路方块不足"由放置原语自己拒 ⇒ `no_throwaway_blocks`，不在这里判）：
     * <ol>
     *   <li>{@code bridge_budget_exhausted}：本次作业累计放置已达
     *       {@link #bridgeBlockBudget()}（= `max(16, 单元数/10)`，`D-443` 裁定 7a）
     *       ⇒ **如实放弃**。取代 `A14` 原来的"额度用完就退回纯通行、坑留着继续挖"：
     *       那会留下**半成品通道**（违反 `I2` 支撑格必须存在），而这条通道是 bot 自己后面
     *       还要反复走的（`SPUR_RETURN` / `RETURN`）。</li>
     *   <li>{@code big_cavern_ahead}：沿走向前瞻 {@link #maxGapLength} 格**找不到一格有地板**
     *       ⇒ 这是"大矿洞"（`§10.3` 产品裁定：**放弃**，不是架桥过去）。
     *       ⚠️ 反例臂：把 `maxGapLength` 调到很大 ⇒ 本判据失效 ⇒ 会开始"一格一格搭桥"（`C8` 原红臂）。
     *       ⚠️ **本档只是判据的一半**：它要不要**停**由 {@link #advanceRefusalIsHard} 决定 ——
     *       支巷停、**主巷不停**（用户 2026-09-25 真机裁定：站在矿洞里不许因为"方向是空气"就整体失败）。</li>
     * </ol>
     *
     * <p>⚠️ 为什么放在**单元开头**而不是每个格子：它表达的是"这一段通道还值不值得开工"，
     * 每格判一次等于把"额度用尽"变成随机中断；单元粒度也让日志/归因可读。
     */
    private String advanceRefusal(FishboneTemplate.Unit unit) {
        int used = placementsUsed();
        int budget = bridgeBlockBudget();
        if (used >= budget) {
            return "bridge_budget_exhausted";
        }
        Direction dir = unit.isSpur() ? unit.spurDir() : template.dir();
        if (!floorWithinLookahead(bot.serverLevel(), unit.foot(), dir, maxGapLength)) {
            return "big_cavern_ahead";
        }
        return null;
    }

    /**
     * **`C8` 的单段悬空判据**（`D-443` 裁定 1b）：从 {@code foot} 沿 {@code dir} 往前
     * {@code maxGapLength} 格之内，**有没有一格"脚下有地板"**。
     *
     * <p>{@code false} = 连续悬空 > {@code maxGapLength} ⇒ 这是大矿洞（产品裁定：放弃，不是架桥）。
     *
     * <p>⚠️ 抽成 **public static** 是为了让夹具能**直接判这条几何判据**（`FishboneSlice2CheckTask`
     * 的 `cavernChecks`），而不是照抄一份谓词 —— 判据必须与生产同一个出处（`K4-P1` 的纪律）。
     */
    public static boolean floorWithinLookahead(ServerLevel level, BlockPos foot, Direction dir, int maxGapLength) {
        for (int d = 0; d <= maxGapLength; d++) {
            // ⚠️ `canWalkOn(level, pos)` 的定义就是「**pos 下面那一格**是实心支撑」（`MovementHelper:49-51`）
            // ⇒ 问"这一列有没有地板"要传**列本身**；传 `column.below()` 会多问一层（夹具实测踩到）。
            if (MovementHelper.canWalkOn(level, foot.relative(dir, d))) {
                return true;
            }
        }
        return false;
    }

    /**
     * **`C8` 推进门的处置**（`D-443` 裁定 1b/7b + 用户 2026-09-25 真机裁定）：这一档要不要**停**。
     *
     * <table border="1">
     *   <tr><th>档</th><th>支巷</th><th>主巷</th></tr>
     *   <tr><td>{@code bridge_budget_exhausted}（**计数事实**：额度真的用完了）</td>
     *       <td>放弃该支巷</td><td><b>停</b>（如实失败）</td></tr>
     *   <tr><td>{@code big_cavern_ahead}（**预测**：前瞻 N 格看不到地板）</td>
     *       <td>放弃该支巷</td><td><b>不停</b>（只记一笔，继续挖）</td></tr>
     * </table>
     *
     * <p>为什么主巷对前瞻档不停（真机实测）：bot 站在**矿洞/空腔**里开工时，主巷**第 2 个单元**就判
     * {@code big_cavern_ahead} ⇒ **6 tick、一格没挖**（`mined=0`）整体失败。用户裁定：「那是方向是空气，
     * 应当**当成主巷的一部分**继续挖」。预测（看不到地板）不是判决；主巷该不该停，由**计数事实**
     * （额度用尽）或单元自己的失败码回答 —— 与 `SEARCH_LIMIT ≠ UNREACHABLE` 同一条纪律（`D-329`）。
     *
     * <p>为什么支巷仍按前瞻档放弃：支巷只值一条肋（放弃的代价**局部且有界**，且 §10.2 的两档就是这个意思），
     * 主巷才是这轮作业的目的 ⇒ 把额度留给主巷。
     *
     * <p>⚠️ 抽成 **public static** 是为了让夹具能**直接判这一条处置**（`FishboneSlice2CheckTask`
     * 的 `advanceRefusalDispositionChecks`），而不是在夹具里照抄一份 `if` —— 判据与生产**同一个出处**
     *（`K4-P1` 的纪律，与 {@link #floorWithinLookahead} 同一种做法）。
     */
    public static boolean advanceRefusalIsHard(boolean isSpur, String refusal) {
        if (isSpur) {
            return true;      // 支巷两档都只是"放弃这条肋"，代价局部 ⇒ 一律停这一条支巷
        }
        return "bridge_budget_exhausted".equals(refusal);   // 主巷：只有计数事实才停
    }

    /**
     * **支巷放弃**（§10.2 用户裁定）：记 `spur_abandoned:<码>`、**跳过该支巷剩余单元**、
     * 沿支巷**原路退回主巷继续**。
     *
     * <p>⚠️ **不判任务失败**：这正是"子巷放弃 / 主巷如实失败"两档的分界；终态由 {@code spursAbandoned}
     * 决定（见 {@link #returnPhase()}）⇒ 决策层**看得出**"主巷做完了但有支巷被放弃"。
     *
     * <p>⚠️ **已知简化**：本片对**任何**理由都放弃该支巷（含暂时性的 `search_incomplete`）——
     * 保守且如实（理由码原样上报）；若真机出现"支巷因暂时性理由被放弃"，再加"先重试后放弃"。
     */
    private void abandonSpur(FishboneTemplate.Unit unit, String reason) {
        int skippedUnits = skipRestOfSpur(unit);
        spursAbandoned++;
        spurUnitsSkipped += skippedUnits;
        if (firstSpurAbandonReason.isEmpty()) {
            firstSpurAbandonReason = reason;
        }
        BotLog.warn("[Fishbone] {}{} 支巷（主巷单元 {}，方向 {}，第 {} 格）放弃剩余 {} 个单元 ⇒ 退回主巷继续"
                        + "（§10.2：子巷放弃 / 主巷失败两档，不许合并）",
                SPUR_ABANDONED_PREFIX, reason, unit.mainUnit(),
                unit.spurDir() == null ? "-" : unit.spurDir().getName(), unit.spurStep(), skippedUnits);
        cellInUnit = 0;
        oreQueue.clear();       // 这条支巷里扫到的候选矿随支巷一起放弃（不许"走去挖"）
        beginSpurReturn(unit);
    }

    /** 跳过当前支巷的剩余单元（含当前这个没挖完的）；返回跳过个数。 */
    private int skipRestOfSpur(FishboneTemplate.Unit unit) {
        int n = 0;
        while (unitIndex < units.size()) {
            FishboneTemplate.Unit u = units.get(unitIndex);
            if (!u.isSpur() || u.mainUnit() != unit.mainUnit() || u.spurDir() != unit.spurDir()) {
                break;
            }
            unitIndex++;
            n++;
        }
        return n;
    }

    /** 支巷端点（或放弃点）⇒ **原路退回主巷**（纯通行、零破坏，计划 §3 `SPUR`）。 */
    private void beginSpurReturn(FishboneTemplate.Unit unit) {
        pendingSpurReturn = null;
        spurReturnTarget = template.footOf(unit.mainUnit());
        spurReturnRunner = null;
        phase = Phase.SPUR_RETURN;
    }

    private Task.Status spurReturn() {
        ServerLevel level = bot.serverLevel();
        if (spurReturnRunner == null) {
            if (MovementHelper.footCell(level, bot).equals(spurReturnTarget)) {
                spurReturns++;      // 放弃/退回点就是岔口本身 ⇒ 没有可走的段
                phase = Phase.EXCAVATE;
                return Task.Status.RUNNING;
            }
            spurReturnRunner = new PathRetryRunner(bot,
                    walkRequest(spurReturnTarget, "-spur"),
                    RETURN_MAX_REPLANS, REQUESTER + "-spur");
            BotLog.info("[Fishbone] SPUR_RETURN 原路退回主巷 feet={} → junction={}（纯通行 + 允许补一块再走，不含破坏）",
                    bot.blockPosition().toShortString(), spurReturnTarget.toShortString());
        }
        PathRetryRunner.State state = spurReturnRunner.tick();
        if (state == PathRetryRunner.State.RUNNING) {
            return Task.Status.RUNNING;
        }
        boolean returned = state == PathRetryRunner.State.DONE && spurReturnRunner.result() != null
                && spurReturnRunner.result().completed();
        String code = spurReturnRunner.result() == null ? "no_result"
                : spurReturnRunner.result().failureCode();
        spurReturnRunner = null;
        if (!returned) {
            BotLog.warn("[Fishbone] spur_return_failed:{} feet={} junction={} ⇒ 如实失败（先回家）",
                    code, bot.blockPosition().toShortString(), spurReturnTarget.toShortString());
            terminalReason = "spur_return_failed:" + code;
            excavationFailed = true;
            beginReturn();
            return Task.Status.RUNNING;
        }
        spurReturns++;
        phase = Phase.EXCAVATE;
        return Task.Status.RUNNING;
    }

    // ==================== 切片 2：露头矿顺手挖（§10.1） ====================

    /**
     * ⭐ **暴露矿判定的唯一真源**（计划 §10.1 三条件，全部**复用生产谓词**）。
     *
     * <p>为什么是 `public static`：夹具要能**逐条件隔离**地断言它（否则"①②③任一"只能靠行为臂间接验证）。
     * 它**不读任何 Job 状态**（暴露面集合由调用方显式传入）⇒ 是纯谓词（同输入同输出），
     * 夹具可以喂"把 bot 传送走""换一份暴露面集合"这类真实输入把某一条单独打红。
     *
     * @param exposedCells 条件②的**暴露面集合**：本次作业**已经挖出来的格**
     *                     （模板单元格 ∪ 已挖掉的矿格 —— 见 {@link #dugCells}）。
     *                     ⚠️ 切片 4 起**不再只传模板格**：那样矿簇只能挖一层（`D-439` 的真机证据）。
     */
    public static boolean opportunisticTarget(ServerLevel level, BotPlayer bot, BlockPos pos,
                                              Set<BlockPos> exposedCells) {
        // ① 是矿（不新增第二份矿物清单）
        if (!DEFAULT_ORE_TARGET.matches(level.getBlockState(pos))) {
            return false;
        }
        // ② 是本次作业挖出来的暴露面
        if (!exposedByOurExcavation(level, pos, exposedCells)) {
            return false;
        }
        // ③ 顺手 = 从**当前站位**不必走过去（与 `D-365` 就地挖**同一对谓词**：视线通 + 触及）
        return MineBlockRunner.inPlaceReachable(level, bot, pos);
    }

    /**
     * 条件②：该格 6 邻域里至少有一格**属于本次作业挖出来的面**、且现在**确实通行**。
     *
     * <p>⚠️ 判"这一格已经挖开"用的是**单格**通行 `canWalkThrough`，**不是** `bodyPassable`
     * （后者还要求"它上面那格也通"）—— 第一版用 `bodyPassable` 实测当场红：**顶棚矿**
     * （模板头位格的正上方那格）会让头位格的 `bodyPassable` 变 false，于是"我们自己挖出来的暴露面"
     * 反而判不出来（`ore_found=0`，2026-09-25 `fishbone_slice2` 首跑）。语义上正确的是
     * "这一格是空的了"（脚位格上方是矿时，脚位格照样是挖空的）。
     *
     * <p>⚠️ **两个条件必须同时成立**（集合里有 + 世界确实是通的）：只信集合就是"自报值"，
     * 只信世界就变成"任何空处旁边的矿都挖"（会把别人挖的洞、天然洞穴算进来）。
     */
    public static boolean exposedByOurExcavation(ServerLevel level, BlockPos pos, Set<BlockPos> exposedCells) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (exposedCells.contains(n) && MovementHelper.canWalkThrough(level, n)) {
                return true;
            }
        }
        return false;
    }

    /**
     * **扫一格自己的 6 邻域**（计划 §10.1：O(6)/格、不全局扫、不周期扫）。
     *
     * <p>切片 4 起这个方法被**两处**调用，语义完全相同（这就是"追簇"的全部机制）：
     * <ol>
     *   <li>{@link #scanForOre} —— 每个模板单元挖完时，扫它的每一格；</li>
     *   <li>{@link #inPlace} —— **每挖掉一颗矿**，扫那一格 ⇒ 矿脉深处的新暴露面自然进队（传递闭包）。</li>
     * </ol>
     * `oreSettled` 保证每个候选格**至多入队一次**（①② 结案）；③ 够不着的进 `chasePending` 等重评。
     *
     * @param origin 日志用的来源说明（"单元 k/n" / "追簇"）
     */
    private void scanAround(BlockPos cell, String origin) {
        if (oreBudgetPerUnit <= 0) {
            return;     // 配置 = 0 ⇒ 关闭暴露矿（连扫都不扫：别为关掉的功能付 O(6)）
        }
        ServerLevel level = bot.serverLevel();
        for (Direction d : Direction.values()) {
            BlockPos candidate = cell.relative(d);
            // ⚠️⚠️ **顺序就是要害**（第一版在这里踩过）：`oreSettled.add` 必须**只在真正结案时**调用。
            // 第一版把它放在循环第一行 ⇒ 每个候选第一次被看到就永久结案 ⇒ "③ 不永久否"根本没生效
            //（夹具实测：矿脉第二层被记成"看过了"，走位之后再也不重评，`oreFound` 卡在 3/5）。
            if (oreSettled.contains(candidate) || oreQueue.contains(candidate)) {
                continue;
            }
            // ① 不是矿 ⇒ 结案（与站位无关）
            if (!DEFAULT_ORE_TARGET.matches(level.getBlockState(candidate))) {
                oreSettled.add(candidate);
                continue;
            }
            // ② 不是"本次作业挖出来的面" ⇒ 结案（同样与站位无关）
            if (!exposedByOurExcavation(level, candidate, dugCells)) {
                oreSettled.add(candidate);
                continue;
            }
            // ③ 现在够不着 ⇒ **不结案**：进 `chasePending`，等走位之后重评（切片 4 的追簇）
            if (!MineBlockRunner.inPlaceReachable(level, bot, candidate)) {
                if (chasePending.add(candidate)) {
                    BotLog.info("[Fishbone] ore_reach_deferred pos={} origin={} feet={}"
                                    + "（①+②成立、③现在够不着 ⇒ 等走位后重评，不丢）",
                            candidate.toShortString(), origin,
                            MovementHelper.footCell(level, bot).toShortString());
                }
                continue;
            }
            oreSettled.add(candidate);
            chasePending.remove(candidate);
            oreFound++;
            oreQueue.add(candidate);
            BotLog.info("[Fishbone] ore_found pos={} origin={}（候选队列={}）",
                    candidate.toShortString(), origin, oreQueue.size());
        }
    }

    /**
     * **扫一个模板单元**（计划 §10.1：只在单元挖完那一 tick、只扫自己的邻域、不重复扫）。
     *
     * <p>代价 O(6 × 净高)/单元；`scannedUnits` 满足判据 `C9`（扫描次数 == 单元数）。
     */
    private void scanForOre(FishboneTemplate.Unit unit) {
        scanUnit(unit, true);
    }

    /**
     * 扫一个单元的每一格。
     *
     * @param primary `true` = 单元收尾时的常规扫描（计进 `scannedUnits`，判据 `C9` 的口径
     *                "扫描次数 == 单元数"）；`false` = **走位之后的重扫**（切片 4 的追簇游走段），
     *                单独计 {@link #rescans} —— 不与常规扫描混在一个计数器里，
     *                否则 `C9` 会被追簇悄悄改口径。
     */
    private void scanUnit(FishboneTemplate.Unit unit, boolean primary) {
        ServerLevel level = bot.serverLevel();
        if (primary) {
            scannedUnits++;
        } else {
            rescans++;
        }
        for (int dy = 0; dy < template.height(); dy++) {
            BlockPos cell = unit.foot().above(dy);
            // ⭐ 先登记"这一格是我们挖出来的面"再扫（顺序不能反：否则本单元内部的邻格判不出来）
            dugCells.add(cell);
            scanAround(cell, "单元 " + (unitIndex + 1) + "/" + units.size());
        }
    }

    // ==================== 切片 5：走位额度（`D-440`） ====================

    /**
     * **本次作业的放置额度**：按形状推导 —— **每 10 个单元 1 块**，下限 8
     * （真机默认形状 404 单元 ⇒ **40 块**）。
     *
     * <p>为什么按形状推导而不是写死：形状是可配置的（`config/alice-fishbone.toml`），
     * 写死一个数会在"你把支巷改成 64 格"之后悄悄变成"路补到一半没额度了"。
     */
    private int bridgeBlockBudget() {
        int configured = com.dddgn.alice.config.FishboneConfig.bridgeBlockBudget();
        if (configured > 0) {
            return configured;
        }
        // `D-443` 裁定 7a：**合成一个数** —— `C8` 的计划默认（16）当下限，`A14` 的形状推导当缩放。
        return Math.max(com.dddgn.alice.config.FishboneConfig.BRIDGE_BLOCK_BUDGET_FLOOR,
                template.advanceCells() / com.dddgn.alice.config.FishboneConfig.BRIDGE_BLOCK_UNITS_PER_BLOCK);
    }

    /**
     * 已经用掉的放置块数（**只数本次作业自己的**放置）。
     *
     * <p>⚠️ 判"是不是我们的"用**前缀**而不是相等：走位请求的 requester 是
     * `fishbone-chase` / `fishbone-spur` / `fishbone-return`（`PlaceStepAndTraverseExecution` /
     * `PillarExecution` 把 `context.requester()` 原样写进 `WriteGrant`），
     * 而 `MineTask` 用的是 `fishbone`。首版用相等 ⇒ 夹具实测 `places=0`，
     * 而 `[WRITE] place … by=fishbone-chase:attempt0:STEP_PLACEMENT` 明明在日志里（数错了，不是没放）。
     */
    private int placementsUsed() {
        int n = 0;
        for (WriteAudit.Entry entry : WriteAudit.snapshot()) {
            if ("place".equals(entry.action()) && isOurs(entry.grant().requester())) {
                n++;
            }
        }
        return n;
    }

    /** 这条写入是不是**本次鱼骨作业**发起的（`requester` 前缀 = {@value #REQUESTER}）。 */
    public static boolean isOurs(String requester) {
        return requester != null && requester.startsWith(REQUESTER);
    }

    /**
     * ⭐ **走位请求**（`D-440`）：还有放置额度 ⇒ {@link PathRequest#withPlacement}
     * （**纯通行 + 只放不拆**：目标格下方补一块再走上去 / 跳起在脚下补一块）；
     * 额度用完 ⇒ **如实退回纯通行** {@link PathRequest#of}（不再补路，坑就留着）。
     *
     * <p>为什么这次走位需要"能放"：追簇允许往下挖，而**地板往往就是矿簇本身** ——
     * 挖掉之后脚位格失去支撑（真机实测 `belowSolid=false` ⇒ 站位搜索 0 候选 ⇒
     * `spur_abandoned:no_reachable_standing_point`）。补回来这件事**不写专门流程**：
     * 规划器在允许 `PLACE_STEP_AND_TRAVERSE` 时会**自然**产出那条边。
     *
     * <p>⚠️ **额度用尽时的边界**（`D-443` 裁定 7b）：这一层只做「辅助走位」（追簇 / 原路退回 / 回家），
     * 它不承诺通道的完整性；通道的完整性由**推进门**负责（{@link #advanceRefusal}：
     * 额度用尽或前瞻无地板 ⇒ **如实放弃/失败**）。所以这里额度用尽时退回纯通行是**安全的**：
     * 走得通就继续，走不通就如实失败 —— 绝不会出现「拿没额度的通道继续往前挖」。
     */
    private PathRequest walkRequest(BlockPos goal, String suffix) {
        int used = placementsUsed();
        int budget = bridgeBlockBudget();
        if (used < budget) {
            return PathRequest.withPlacement(bot.getUUID().toString(), bot.blockPosition(), goal,
                    REQUESTER + suffix);
        }
        if (!placeBudgetExhaustedLogged) {
            placeBudgetExhaustedLogged = true;
            BotLog.info("[Fishbone] place_budget_exhausted used={}/{} ⇒ 走位退回纯通行"
                            + "（阶梯/坑不再补；想要更多就调形状或看 `D-440`）", used, budget);
        }
        return PathRequest.of(bot.getUUID().toString(), bot.blockPosition(), goal, REQUESTER + suffix);
    }

    /**
     * ⭐ **追簇游走段**（切片 4，`D-439`）：先走进**刚挖完的那一格**，再消费暴露矿队列。
     *
     * <p><b>为什么需要这一步</b>（夹具实测的硬事实，不是推测）：鱼骨的每个单元都是
     * 「站在身后一格挖」⇒ 单元挖完时 bot 还站在**后一格**。而矿脉是从巷道壁往**侧向**长的，
     * 站在后一格时视线会被未挖的巷道壁切掉 ⇒ 矿脉第二层虽然 ② 成立，③ 却判"够不着"
     *（实测：`ore_eval pos=3763,80,2402 exposed=true reachable=false feet=3762,80,2400`）。
     * 走进刚挖完的那一格之后，视线顺着矿脉轴 ⇒ 一层层能追进去。
     *
     * <p><b>为什么这是允许的</b>：计划 §10.1 对追簇**明确允许游走**（「游走段按'支巷'级处置」
     * 「模板外改动 ⊆ 簇游走区域」）；「不许走去挖」管的是**露头矿的顺手挖那一档**（第一层），
     * 那一档仍然要求 `inPlaceReachable`（本方法只在队列非空/有够不着的候选时才走，
     * 且**纯通行、零破坏**）。
     *
     * <p>走不过去**不判失败**：用当前站位继续，③ 会在消费时如实拒绝（`ore_deferred`）。
     * 走成功则**重扫**这一格 —— 原本"够不着"的候选现在可能够得着（`rescans` 单独计数）。
     */
    private Task.Status chaseApproach() {
        if (chaseApproach == null) {
            BlockPos work = chaseApproachUnit.foot();
            chaseApproach = new PathRetryRunner(bot, walkRequest(work, "-chase"),
                    RETURN_MAX_REPLANS, REQUESTER + "-chase");
            BotLog.info("[Fishbone] CHASE_APPROACH 走进刚挖完的那一格 {}（追簇游走段；纯通行 + "
                            + "允许补一块再走（`PILLAR` / `PLACE_STEP_AND_TRAVERSE`，不含破坏）；额度 {}/{}）",
                    work.toShortString(), placementsUsed(), bridgeBlockBudget());
        }
        PathRetryRunner.State state = chaseApproach.tick();
        if (state == PathRetryRunner.State.RUNNING) {
            return Task.Status.RUNNING;
        }
        boolean arrived = state == PathRetryRunner.State.DONE && chaseApproach.result() != null
                && chaseApproach.result().completed();
        chaseApproach = null;
        if (!arrived) {
            BotLog.info("[Fishbone] CHASE_APPROACH 走不过去 ⇒ 用当前站位继续（③ 会在消费时如实拒绝）");
        } else {
            // 站好了 ⇒ 重扫一次：原本"够不着"的候选现在可能够得着（③ 不永久否，见 `chasePending`）
            chasePending.removeIf(pos -> !DEFAULT_ORE_TARGET.matches(bot.serverLevel().getBlockState(pos)));
            scanUnit(chaseApproachUnit, false);
        }
        chaseApproachUnit = null;
        if (oreQueue.isEmpty()) {
            requestBatchCollect("chase_done");  // ⭐ `1.4z-B`：追簇队列空了 = 一整簇挖完
            if (pendingSpurReturn != null) {
                beginSpurReturn(pendingSpurReturn);
            } else {
                phase = Phase.EXCAVATE;
            }
        } else {
            oreTask = null;
            phase = Phase.IN_PLACE;
        }
        return Task.Status.RUNNING;
    }

    /**
     * `IN_PLACE`：逐个消费暴露矿队列（计划 §10.1「在下一个推进动作之前」）。
     *
     * <p>⚠️ **不许走去挖**：消费前**再用同一对谓词复检**（站位可能已经变了）—— 不达标就
     * `ore_deferred` 并**丢弃**（那是"跟随/探洞"的事，不是鱼骨）。
     * 真的移动了也如实记账（{@link #oreWalkedAway()}）。
     *
     * <p>⭐ <b>切片 4 的两处机制（`D-439`）</b>：
     * <ol>
     *   <li><b>追簇</b>：矿**破坏成功那一刻**就把这一格登记成新的暴露面，并**立刻扫它的 6 邻域**
     *       ⇒ 矿脉深处的格自然接着进队（一层 → 两层 → …），直到**触及范围**或**每单元上限**为止。
     *       这是"追踪整个矿簇"（计划 §10.1，2026-09-21 用户裁定）的**最小落地形态**；</li>
     *   <li><b>记账在破坏那一刻</b>：`oreMined`（世界改动）与"收集段是否成功"**分开算** ——
     *       深格矿常常"挖得掉、捡不回"（`MineTask` 的收集段会失败），把这两件事压成一个数
     *       会让 `SUMMARY` 的 `ores=` 说谎。</li>
     * </ol>
     */
    private Task.Status inPlace() {
        if (oreQueue.isEmpty()) {
            requestBatchCollect("vein_done");   // ⭐ `1.4z-B`：矿脉挖完 = 一整簇挖完
            if (pendingSpurReturn != null) {
                beginSpurReturn(pendingSpurReturn);
            } else {
                phase = Phase.EXCAVATE;
            }
            return Task.Status.RUNNING;
        }
        if (oreBudgetUsed >= oreBudgetPerUnit) {
            // 护栏（计划 §10.1）：本单元不再消费 —— 记一次、清空队列、**照常推进**（不停任务）。
            int dropped = oreQueue.size();
            oreQueue.clear();
            oreBudgetExhausted++;
            BotLog.warn("[Fishbone] ore_budget_exhausted 单元 {} 已消费 {} 个暴露矿（上限 {}）⇒ 丢弃剩余 {} 个"
                            + "候选、继续推进（护栏生效；配置键 fishbone.oreBudgetPerUnit）",
                    unitIndex + 1, oreBudgetUsed, oreBudgetPerUnit, dropped);
            return Task.Status.RUNNING;
        }
        BlockPos ore = oreQueue.peek();
        ServerLevel level = bot.serverLevel();
        if (oreTask == null) {
            if (level.getBlockState(ore).isAir()) {
                oreQueue.poll();    // 还没轮到它就没了（连锁模组/上一格带掉）
                oreBudgetUsed++;
                return Task.Status.RUNNING;
            }
            if (!opportunisticTarget(level, bot, ore, dugCells)) {
                BotLog.info("[Fishbone] ore_deferred pos={}（复检不达标：站位/视线变了 ⇒ 不走去挖，丢弃）",
                        ore.toShortString());
                oreDeferred++;
                oreQueue.poll();
                oreBudgetUsed++;
                return Task.Status.RUNNING;
            }
            oreStartFeet = MovementHelper.footCell(level, bot);
            oreBroken = false;
            oreTask = new MineTask(bot, ore, scope,
                    // ⭐ `1.4z-B`：追簇的逐格收集同样关掉（同一处置）。⚠️ 记账不受影响：`oreMined++`
                    // 判的是"目标格变空气那一瞬"，`oreWalkedAway` 量的也是那一瞬的位移 ⇒ 与"什么时候捡"无关。
                    MiningBudget.forTarget(bot, level, ore, false),
                    cellProfile(), grant,
                    // ⭐ `1.4z`：**主动拾取清单 = 产物过滤器**（与 `countProductItems()` 同一个 `PRODUCT_FILTER`
                    // ⇒ 判据只有一个出处）。石头族落物不进候选：真机实测那 61% 的石头点名声就是它的代价。
                    PRODUCT_FILTER::matches);
            return Task.Status.RUNNING;
        }
        // ⚠️⚠️ **有任务在跑就必须把它跑到终态**，哪怕目标**已经是空气**了 ——
        // `MineTask` 破坏完成后还有一段「收集掉落物」相位，那时目标必然是空气。
        // 首版把"目标已空气 ⇒ 提前出队"放在最前面，实测（2026-09-25 `fishbone_slice2` 首跑）
        // 把 3 个露头矿的 `MineTask` 全部腰斩在收集相位：产物**确实进了包**（夹具独立清点 3 个原铁），
        // 但 `oreMined=0`（账没记）。**"目标空了"不是"任务完了"** —— 这两件事在不同的层。
        Task.Status status = oreTask.tick();
        if (!oreBroken && level.getBlockState(ore).isAir()) {
            oreBroken = true;
            // ⭐ 切片 4：这一格现在是我们的暴露面（追簇的传递跳板）+ 白名单（世界改动已发生）
            dugCells.add(ore.immutable());
            oreBrokenCells.add(ore.immutable());
            chasePending.remove(ore);
            oreMined++;
            // ⭐ 只在**破坏完成的那一刻**量位移：那之前的位移 = "走去站位"（不合法，"顺手"要求不必移动），
            // 那之后的位移 = 捡自己刚挖下来的掉落物（合法，"顺手挖"本来就带收集）。
            BlockPos nowFeet = MovementHelper.footCell(level, bot);
            int adjusted = maxAxisDistance(nowFeet, oreStartFeet);
            if (adjusted > ORE_STAND_ADJUST_MAX) {
                oreWalkedAway++;
                BotLog.warn("[Fishbone] ore_moved 顺手挖把 bot 带离了原位 {} → {}（{} 格）",
                        oreStartFeet.toShortString(), nowFeet.toShortString(), adjusted);
            } else if (adjusted > 0) {
                // 2026-09-25 真机实测：这一档**必然出现**（`MineTask` 会先走到"目标正下方"这个更优站位再挖
                // 顶棚矿）⇒ 它不是缺陷，是 `MineTask` 的正常站位选择；只有 > 1 格才说明"真的走过去了"。
                BotLog.info("[Fishbone] ore_stand_adjust 站位微调 {} → {}（{} 格，`MineTask` 选更优站位，正常）",
                        oreStartFeet.toShortString(), nowFeet.toShortString(), adjusted);
            }
            // ⭐ 追簇：把这一格带出来的新暴露面入队（本方法会在队列非空时继续被 tick ⇒ 传递闭包）
            scanAround(ore, "追簇·来自 " + ore.toShortString());
        }
        if (status == Task.Status.RUNNING) {
            return Task.Status.RUNNING;
        }
        if (status != Task.Status.DONE) {
            if (oreBroken) {
                // 挖掉了、但收集段失败 ⇒ **世界已经改了**，账已记在 oreMined；掉落物交给收尾 COLLECT。
                oreUncollected++;
                BotLog.warn("[Fishbone] ore_drop_left pos={} reason={}（矿已挖掉、收集段没把掉落物拿回来"
                                + " ⇒ 交给收尾 COLLECT；`oreMined` 照记，不撒谎）",
                        ore.toShortString(), oreTask.failureReason());
            } else {
                BotLog.info("[Fishbone] ore_deferred pos={} reason={}（顺手挖失败 ⇒ 不重试不追）",
                        ore.toShortString(), oreTask.failureReason());
                oreDeferred++;
            }
        }
        oreTask = null;
        oreQueue.poll();
        oreBudgetUsed++;
        return Task.Status.RUNNING;
    }

    /** 两格之间的**切比雪夫距离**（"挪了几格"按格算，不按欧氏距离）。 */
    private static int maxAxisDistance(BlockPos a, BlockPos b) {
        return Math.max(Math.abs(a.getX() - b.getX()),
                Math.max(Math.abs(a.getY() - b.getY()), Math.abs(a.getZ() - b.getZ())));
    }

    // ==================== 切片 2：收集（C2） ====================

    /**
     * 起一次收集。`isFinal` = **收尾**（全模板处理完 ⇒ 做 `product_not_collected` 判定后回家）；
     * `false` = **周期**（{@link #requestBatchCollect} 那些结构边界 ⇒ 收完回工地）。
     *
     * <p>⭐ `1.4z-B`+`D-453`：两档都 **`allowWorldModification=false`**（`D-375`：够不着就如实退休，
     * 不许为捡东西挖穿地形 —— 真机实测那 18 格 `collect-drops:PATH_ACCESS` + 10 次 `PICKUP_DETOUR`
     * 就是它），清单 = `PRODUCT_FILTER::matches`（与 `countProductItems()` **同一个入口**）。
     */
    private void startCollect(boolean isFinal) {
        collectIsFinal = isFinal;
        collectReturnPhase = phase;
        if (!isFinal) {
            batchCollects++;
        }
        collector = new CollectDropsTask(bot, template.startFoot(), scope, List.of(), false,
                CollectDropsTask.DEFAULT_TOTAL_BUDGET_TICKS,
                com.dddgn.alice.task.mining.MiningProfile.STANDABLE_ONLY, null,
                PRODUCT_FILTER::matches);
        BotLog.info("[Fishbone] COLLECT 开始 round={} kind={} origin={} scopeRadius={} oreMined={} 产物基线={}"
                        + "（半径从模板推导；追取上限 = max(32, 2×半径)，`D-346`；worldMod=false）",
                batchCollects, isFinal ? "final" : "periodic", template.startFoot().toShortString(),
                scope.currentRadius(), oreMined, itemsBefore);
        phase = Phase.COLLECT;
    }

    /**
     * ⭐ `1.4z-B`：**该不该起一次批量收集** —— 判据只有这一处。
     *
     * <p><b>主判据 = 一段活干完了</b>（`collectDue`，由 {@link #requestBatchCollect} 在三处**结构边界**
     * 置位：支巷完工 / 追簇队列清空）；<b>下限 = 最老的产物落物已躺够
     * {@link #COLLECT_BEFORE_DESPAWN_TICKS}</b>（否则它会**消失在世界上**，原版常数派生）。
     *
     * <p>守卫：**手上没有在飞的子任务** ⇒ 不打断半截挖掘；只在 `EXCAVATE`/`SPUR_RETURN` 切相位
     * （追簇中途不切，免得搅乱 `oreQueue`/`pendingSpurReturn` 那套状态）。
     */
    private boolean batchCollectDue() {
        if (collector != null || current != null || oreTask != null) {
            return false;   // 有活在飞：不打断
        }
        if (phase != Phase.EXCAVATE && phase != Phase.SPUR_RETURN) {
            return false;
        }
        if (!collectDue && ticks >= nextAgeCheckTick) {
            nextAgeCheckTick = ticks + 20;      // 节流：每 20 tick 量一次最老产物落物的年龄
            collectDue = oldestProductDropAge() >= COLLECT_BEFORE_DESPAWN_TICKS;
        }
        if (!collectDue) {
            return false;
        }
        collectDue = false;
        return true;
    }

    /** 作用域在册落物里**产物**的最老年龄（tick；没有产物 ⇒ −1）。判据 = 同一个 `PRODUCT_FILTER`。 */
    private int oldestProductDropAge() {
        int oldest = -1;
        for (ItemEntity item : scope.liveDrops()) {
            if (PRODUCT_FILTER.matches(item.getItem()) && item.tickCount > oldest) {
                oldest = item.tickCount;
            }
        }
        return oldest;
    }

    /**
     * ⭐ `1.4z-B`：**一段活干完了** ⇒ 置位，真正切相位由 {@link #batchCollectDue()} 那一处决定。
     *
     * <p>三处调用点都是**结构边界**（同一概念的三个入口，不是三份判据）：支巷完工
     * （`finishUnit` 的 `lastOfSpur`）、追簇队列清空（`chaseApproach`/`inPlace` 的两个出口）。
     */
    private void requestBatchCollect(String why) {
        if (collectDue) {
            return;
        }
        collectDue = true;
        BotLog.info("[Fishbone] collect_due 段完工 why={} 单元={}/{} 追簇队列={} 已挖={} 周期收集趟数={}",
                why, unitIndex, units.size(), oreQueue.size(), mined, batchCollects);
    }

    private Task.Status collectPhase() {
        Task.Status status = collector.tick();
        if (status == Task.Status.RUNNING) {
            return Task.Status.RUNNING;
        }
        if (!collectIsFinal) {
            // ⭐ `1.4z-B`：**周期收集收完就回工地** —— 产物齐不齐**只在收尾那次判**（否则"这一趟没把
            // 全部产物收回来"会被误报成 `product_not_collected`；真机实测单趟收不回全部是常态，
            // 落物会掉进够不着的空洞 = `D-375` 那一档）。
            collector = null;
            phase = collectReturnPhase;
            BotLog.info("[Fishbone] COLLECT 完成（周期 #{}）⇒ 回 {} 继续", batchCollects, phase);
            return Task.Status.RUNNING;
        }
        collectedProducts = countProductItems() - itemsBefore;
        if (collectedProducts >= oreMined) {
            BotLog.info("[Fishbone] COLLECT 完成 产物=+{} 露头矿={}（`C2` 满足）", collectedProducts, oreMined);
            beginReturn();
            return Task.Status.RUNNING;
        }
        BotLog.warn("[Fishbone] product_not_collected 产物=+{} < 露头矿={} ⇒ 如实失败（先回家；"
                        + "`D-436 §一.2` 的例外条款：落物可能掉出可达范围）",
                collectedProducts, oreMined);
        terminalReason = "product_not_collected";
        excavationFailed = true;
        beginReturn();
        return Task.Status.RUNNING;
    }

    /** 背包里的**产物**（`forge:ores/*` + `raw_materials/*` + 原版兜底；与 `MineJob` 同口径）。 */
    private static final MineProductFilter PRODUCT_FILTER = MineProductFilter.forTag(null);

    private int countProductItems() {
        int total = 0;
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (PRODUCT_FILTER.matches(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    // ==================== RETURN ====================

    private void beginReturn() {
        phase = Phase.RETURN;
        returnRunner = null;
    }

    /**
     * 沿主巷**纯通行**返回起点（计划 §3 `RETURN`）。
     *
     * <p>主巷 = 自己挖出来的返回通道 ⇒ 天然可返回。⚠️ **这一段不许有任何世界写入**（判据 `C4`）；
     * 所以走 `PathRequest.of`（默认纯通行，`D-076`），不走 `miningApproach`。
     */
    private Task.Status returnPhase() {
        if (returnRunner == null) {
            returnRunner = new PathRetryRunner(bot, walkRequest(template.startFoot(), "-return"),
                    RETURN_MAX_REPLANS, REQUESTER + "-return");
            BotLog.info("[Fishbone] RETURN 开始 feet={} → start={}（纯通行，零破坏）",
                    bot.blockPosition().toShortString(), template.startFoot().toShortString());
        }
        PathRetryRunner.State state = returnRunner.tick();
        if (state == PathRetryRunner.State.RUNNING) {
            return Task.Status.RUNNING;
        }
        boolean returned = state == PathRetryRunner.State.DONE && returnRunner.result() != null
                && returnRunner.result().completed();
        returnedHome = returned;
        if (!returned) {
            String code = returnRunner.result() == null ? "no_result"
                    : returnRunner.result().failureCode();
            BotLog.warn("[Fishbone] return_failed code={} feet={}（前一段的结果：{}）",
                    code, bot.blockPosition().toShortString(),
                    terminalReason.isBlank() ? "作业尚未失败" : terminalReason);
            terminalReason = "return_failed";
            return finishFail("return_failed");
        }
        if (excavationFailed) {
            BotLog.warn("[Fishbone] 已安全返回起点，如实失败 reason={} progress={}",
                    terminalReason, progressSummary());
            return finishFail(terminalReason);
        }
        // ⭐ 支巷被放弃时**换一个终态词**（`§10.2`）：只说 `template_complete` 会让决策层以为
        // "全挖完了" —— 而事实上主巷做完了、有 n 条支巷没挖（`C1` 的那部分不成立）。
        terminalReason = spursAbandoned == 0
                ? TEMPLATE_COMPLETE
                : TEMPLATE_COMPLETE + "_spurs_abandoned=" + spursAbandoned;
        BotLog.info("[Fishbone] {} progress={} ticks={}", terminalReason, progressSummary(), ticks);
        return finishOk();
    }

    // ==================== 收尾 ====================

    private Task.Status finishOk() {
        terminalStatus = Task.Status.DONE;
        return finishTerminal();
    }

    private Task.Status finishFail(String reason) {
        terminalReason = reason;
        terminalStatus = Task.Status.FAILED;
        return finishTerminal();
    }

    /** **唯一终态出口**：无论成功/失败都从这里落地（`SUMMARY` 只打一次）。 */
    private Task.Status finishTerminal() {
        // `I5` 放置面：作业自己的作用域自己撤（`BotManager` 下个任务开始时也会清一次 —— 双保险：
        // 泄漏的后果是"这个 bot 以后都不能在别处放方块"，比多撤一次危险得多）。
        com.dddgn.alice.action.TaskTargetProtection.end(bot);
        if (!summaryEmitted) {
            summaryEmitted = true;
            emitSummary();
        }
        phase = Phase.DONE;
        return terminalStatus;
    }

    // ==================== 切片 3：真机取样的结构化日志（计划 §8） ====================

    /** `dir=` 的取值（计划 §8 的形状 = 单字母 N/S/E/W；模板方向只许水平四向）。 */
    private static String dirLetter(Direction dir) {
        return dir.getName().substring(0, 1).toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * ⭐ **开始行**（计划 §8）—— 真机取样时你只要贴这一行，就知道 bot 打算挖什么。
     */
    private void emitStart() {
        BotLog.info("[Fishbone] start template=dir={} main={} spacing={} spur={} side={} height={} start={}",
                dirLetter(template.dir()), template.mainLength(), template.spurSpacing(),
                template.spurLength(), template.side(), template.height(),
                template.startFoot().toShortString());
    }

    /**
     * ⭐ **推进行**（计划 §8）—— 只在**主巷**单元收尾时报（`advance` 每次都真的 +1；
     * 支巷单元也报的话，`advance` 会连着十几行不动，读数反而更难读）。支巷进度看 `spurs=`。
     */
    private void emitProgress(FishboneTemplate.Unit unit) {
        if (unit.isSpur()) {
            return;
        }
        PathingStats.Scale delta = PathingStats.scale().delta(scaleAtStart);
        BotLog.info("[Fishbone] advance={}/{} cell={} mined={} spurs={}/{} ores={}/{} searchNodes={}"
                        + " products={}",
                mainUnitsDone, template.mainLength(), unit.foot().toShortString(), mined,
                spurReturns, template.spurBranches(), oreMined, oreFound,
                delta.nodes(), countProductItems() - itemsBefore);
    }

    /**
     * ⭐ **收尾行**（计划 §8）—— 真机验收的四条读数全在这一行：
     * `main=`/`spursAbandoned=`（模板=事实）、`outside=`（没乱挖）、`searchNodes=`/`searchLimit=`（鱼骨的卖点）、
     * `return=`（回得来）。
     *
     * <p>⚠️ `spursAbandoned=` 的**键名口径**见本方法内的 `1.4u` 注释：分子是**放弃数**（不是"未放弃数"）。
     * 键名是与用户的契约，改它必须同步 `tools/kernel-predicates.py` 的 `rule_fishbone_live_log_shape`。
     *
     * <p>⚠️ `outside=` 的口径**逐字**是：自本作业第一 tick 起、`WriteAudit` 里 requester = `fishbone`
     * 的**破坏**条目中，位置**不在**（模板格 ∪ 本次挖掉的露头矿格）里的条数。别人的破坏不算。
     */
    private void emitSummary() {
        PathingStats.Scale delta = PathingStats.scale().delta(scaleAtStart);
        Set<BlockPos> whitelist = new LinkedHashSet<>(template.cellSet());
        whitelist.addAll(oreBrokenCells);
        int inside = 0;
        int outside = 0;
        for (WriteAudit.Entry entry : WriteAudit.snapshot()) {
            if (!"break".equals(entry.action()) || entry.tick() < gameTimeAtStart) {
                continue;
            }
            if (!isOurs(entry.grant().requester())) {
                continue;
            }
            if (whitelist.contains(entry.pos())) {
                inside++;
            } else {
                outside++;
            }
        }
        String ret = !excavationStarted ? "n/a" : (returnedHome ? "ok" : "no");
        // ⭐ `1.4u`（2026-09-26，`F3`）：键名从 `spurs=<未放弃>/<总数>` 改成 `spursAbandoned=<放弃>/<总数>`。
        // 病灶（真机实测）：原键名打印的是 `spurBranches()-spursAbandoned / spurBranches()` = **未放弃数/总数**，
        // 于是"主巷只走了 1 格（`main=1/20`）"与"子巷一条都没开挖（`spurs=12/12`）"**并排出现** ⇒
        // `12/12` 极易被读成"12 条子巷全挖完了"。改后**分子就是放弃数**、含义唯一，
        // 且与紧挨着的 `abandoned=` 重复 ⇒ 顺手把重复字段去掉（一个数一个键）。
        // ⚠️ 键名是与用户的**契约**（计划 §8）：本行改动 **必须** 同步 `tools/kernel-predicates.py`
        // 的 `rule_fishbone_live_log_shape`（改一处不改另一处 ⇒ 门禁红，这正是它存在的意义）。
        BotLog.info("[Fishbone] SUMMARY dir={} main={}/{} spursAbandoned={}/{} mined={} skipped={}"
                        + " ores={}/{} uncollected={} collected={}/{} collects={} searchNodes={} searchLimit={} return={}"
                        + " worldChangesInside={} outside={} ticks={} → {}",
                dirLetter(template.dir()), mainUnitsDone, template.mainLength(),
                spursAbandoned, template.spurBranches(),
                mined, skipped, oreMined, oreFound, oreUncollected, collectedProducts, oreMined,
                batchCollects, delta.nodes(), delta.searchLimits(), ret, inside, outside, ticks,
                terminalStatus == Task.Status.DONE ? "PASS" : "FAIL");
    }

    // ==================== 夹具只读（判据 C1/C2/C3/C4/C5） ====================

    /** **夹具只读**：已完整处理的推进单元数（`C1`/`C3` 的分母）。 */
    public int advancedUnits() {
        return advanced;
    }

    /** **夹具只读**：模板**单元**总数（含支巷；= `template.advanceCells()`）。 */
    public int unitCount() {
        return units.size();
    }

    /** **夹具只读**：我方真的挖掉的**模板**格数。 */
    public int minedCells() {
        return mined;
    }

    /** **夹具只读**：本来就通行、被跳过的模板格数。 */
    public int skippedCells() {
        return skipped;
    }

    /** **夹具只读**：作业总 tick。 */
    public int elapsedTicks() {
        return ticks;
    }

    /** **夹具只读**：被放弃的支巷条数（`§10.2` 的"子巷放弃"档）。 */
    public int spursAbandoned() {
        return spursAbandoned;
    }

    /** **夹具只读**：随放弃的支巷一起跳过的单元数。 */
    public int spurUnitsSkipped() {
        return spurUnitsSkipped;
    }

    /** **夹具只读**：成功"原路退回主巷"的次数（判据：每条开了的支巷各一次）。 */
    public int spurReturns() {
        return spurReturns;
    }

    /** **夹具只读**：第一条被放弃支巷的原始理由码（终态词 `=n` 版本不带它 ⇒ 单独暴露）。 */
    public String firstSpurAbandonReason() {
        return firstSpurAbandonReason;
    }

    /** **夹具只读**：扫过 6 邻域的单元数（判据：== 处理完的单元数，不是"每 tick 全区域扫"）。 */
    public int scannedUnits() {
        return scannedUnits;
    }

    /** **夹具只读**：顺手挖队列里**入过队**的露头矿数。 */
    public int oreFound() {
        return oreFound;
    }

    /**
     * **夹具只读**：真的**挖掉**的暴露矿格数（`C2` 的分母）。
     *
     * <p>⚠️ 切片 4 起口径 = **世界改动**（破坏那一刻记账），不再要求"掉落物也进了包" ——
     * 追簇的深格矿常常"挖得掉、捡不回"，两者的差额单独记 {@link #oreUncollected()}。
     */
    public int oreMined() {
        return oreMined;
    }

    /** **夹具只读**：挖掉了但**掉落物没进包**的矿格数（`MineTask` 收集段失败）。 */
    public int oreUncollected() {
        return oreUncollected;
    }

    /** **夹具只读**：触发 `ore_budget_exhausted` 的次数（每单元上限护栏生效的证据）。 */
    public int oreBudgetExhausted() {
        return oreBudgetExhausted;
    }

    /** **夹具只读**：复检不达标 / 挖失败而**丢弃**的候选数（"不走去挖"的代价，如实记账）。 */
    public int oreDeferred() {
        return oreDeferred;
    }

    /** **夹具只读**：顺手挖**把 bot 带离原位**的次数 —— 必须为 0（计划 §10.1"顺手 = 不必移动"）。 */
    public int oreWalkedAway() {
        return oreWalkedAway;
    }

    /** **夹具只读**：收集段净增的**产物**件数（`C2` 的读数）。 */
    public int collectedProducts() {
        return collectedProducts;
    }
}
