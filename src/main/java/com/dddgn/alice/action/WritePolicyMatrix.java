package com.dddgn.alice.action;

import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.MovementType;
import com.dddgn.alice.pathing.core.search.PathRequest;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * **世界写入集中策略表**（D-207 ①，2026-09-14 接线）：<b>区域归属 × 任务类别 → 回收义务 + 移动授权 + 允许理由</b>。
 *
 * <p><b>为什么要有这张表</b>：这三个维度此前**各自散落在不同的类里**——
 * 回收义务在 {@link WriteReason#temporary()}（只被账本读一次）、移动授权在
 * {@link PathRequest} 的六个命名工厂里、理由词表在 {@code WriteReason} 枚举里，而
 * "谁能用哪一组"**没有任何一处写下来**。结果是：想收紧/放宽某个任务的世界写入权限时，
 * 改动点不可见、覆盖不可断言（`MANUAL` 曾是零调用点的死值，14 个 `WriteReason` 里
 * 只有 3 个被 `temporary()` 认定为临时）。本表把三者收成**一个文件**，并让"覆盖是否完整"
 * 变成可执行的断言（{@link #audit()} + {@code WritePolicyCheckTask}）。
 *
 * <p><b>三个维度的语义（2026-09-14 用户拍板，取代早期提案里的三分层）</b>：
 * <ul>
 *   <li><b>区域归属只有两层</b>：{@link Zone#EXTERNAL}（默认，不是 Alice 的地）与
 *       {@link Zone#WORKSPACE}（Alice 的地，来源 = 玩家已划定的区域）。<b>保护区不是第三层</b>：
 *       它是**优先级更高的独立闸门**（{@code SafeZoneData} 命中即禁止破坏），与"归属"正交，
 *       两个概念不该挤在同一列里（旧提案的 {@code PROTECTED} 与既有"保护区"同名反义）。</li>
 *   <li><b>移动授权用 {@link PathRequest} 的工厂名当词表</b>：不新造形容词（旧提案的 `WILD` 就是
 *       把"场合"当成了"能力"）。集合内容**直接问工厂要**（见 {@link MovementGrant#types()}），
 *       因此表与工厂**在定义上不会漂移**。</li>
 *   <li><b>回收义务</b>（{@link Obligation}）：{@code REASON_DEFAULT} = 按理由自身语义
 *       （今天的行为，{@link WriteReason#temporary()}）；{@code TEMP}/{@code KEEP} = 该组合下的
 *       放置**一律**必须回收 / 一律免回收（比理由更强的声明）。</li>
 * </ul>
 *
 * <p><b>本轮（R1）刻意不改变任何默认行为</b>——这是用户选定的解释 A（"矩阵只管回收义务，
 * 上层显式授权如 {@code BULK_EDIT}/{@code MANUAL} 不受默认区约束"）与"工作区只是**允许**
 * {@code KEEP} 类理由"的必然结果：EXTERNAL 与 WORKSPACE 今天对每个理由解析结果**相同**，
 * 该等式由 {@code workspace_equiv} 断言守着（谁让它不相等，就必须同时补一条决策记录）。
 * 本轮真正带上牙齿的是**移动授权**：纯通行任务（{@code walk-to} 这类）今天起**不能再**规划出会写世界的移动
 * ——这正是 D-076 红线的可执行版本，也是 R2/R3 要用到的挂点。
 *
 * <p><b>不变量（自检断言，见 {@code WritePolicyCheckTask}）</b>：
 * <ol>
 *   <li>表对 {@code Zone × Task} **全枚举**（一行不缺、一行不多）；</li>
 *   <li>每个 {@link WriteReason} 至少被一行登记（无孤儿理由）；显式义务与该行理由的
 *       {@code temporary()} **不矛盾**（防止"显式 KEEP"把本该回收的东西放过）；</li>
 *   <li>负例：未授权组合（纯通行任务 + 会写世界的移动集）**必须抛异常**；</li>
 *   <li>EXTERNAL ≡ WORKSPACE（今天）；</li>
 *   <li>未登记 requester 的写入**必须留痕**（{@code UNREGISTERED} 计数 + 一次 WARN），不静默；</li>
 *   <li>（R1 收口，2026-09-14）**容器写入也过表**：判定挂点 = {@code WriteBudget.consumeContainerWrite}
 *       （容器写入没有账本条目，这是它的唯一必经之处）；已登记任务写了未声明的理由 ⇒ **硬拒**
 *       （{@link #refuses}），登记缺口仍只留痕。覆盖面的活体证据 = {@code containerChecks}：
 *       装了闸门却恒为 0 ⇒ 挂点没接上。</li>
 * </ol>
 */
public final class WritePolicyMatrix {

    private WritePolicyMatrix() {
    }

    // ==================== 维度定义 ====================

    /**
     * **区域归属**（两层）。
     *
     * <p>刻意不含"保护区"：保护区（{@code SafeZoneData}）是独立闸门，命中即禁止破坏，
     * 与"这块地是不是 Alice 的"正交。把两者混成一列会造出同名反义的术语。
     */
    public enum Zone {
        /** 默认：不是 Alice 的地。放置是**借用**（按理由决定是否必须归还）。 */
        EXTERNAL,
        /** Alice 的地（来源 = 玩家已划定的区域）：允许留下永久改动。 */
        WORKSPACE
    }

    /** **任务类别**：由 {@code requester} 经登记表（{@link #taskOf}）推导，不由调用点自报。 */
    public enum Task {
        TRAVERSAL, MINING, GATHERING, LUMBER, CRAFT, CONTAINER, BUILD, RESTORE, MANUAL, DIAGNOSTIC,
        /** **维生自救**（D-241）：只有拿到写信封的任务才允许用逃生准备金（用户 2026-09-16 Q1 定案）。 */
        SURVIVAL,
        /** 未登记的 requester：**记为错误**（计数 + WARN），但不据此拒绝（那是我们的登记缺口，不是任务的错）。 */
        UNREGISTERED
    }

    /**
     * ⭐ **区域级权限等级**（用户 2026-09-19 拍板"权限阶梯我同意"，`D-338` 附注七②）。
     *
     * <p>它回答的是**第三维**问题：在**保护区**（= 玩家认领的区块）里，**这个任务**被允许写到什么程度。
     * 与另两维正交：① 保护区闸门（`SafeZoneData`：认领即禁止）管"能不能动"；
     * ② 本表的 {@link Zone}（EXTERNAL/WORKSPACE）管"这片地是不是 Alice 的"；
     * ③ 本枚举管"**任务在这个封套里被授予了哪一档**"。
     *
     * <p>⚠️ **等级不是新制度**：它**不放宽**任何既有红线 —— 每一次写入仍然要过
     * `WriteGrant`（谁/为什么）+ `WriteBudget`（改了世界几次）+ 账本（`TEMP`/`KEEP`）。
     * 本枚举只是把"保护区内的写入"从**一律拒绝**变成**按档授予**（`D-338` ① 的落地）。
     *
     * <p>⚠️ 野外（未认领区块）**不看这一档**（`D-327` 场所化：野外由成本模型 + 维生 + 只读审计治理）。
     */
    public enum Level {
        /** **只读**：区内零写入。 */
        L0_READ_ONLY("L0", false, false, 0),
        /** **临时脚手架**：只许**临时**放置（`WriteReason#temporary()`），**上限 8 次**；不许破坏。 */
        L1_SCAFFOLD("L1", false, true, 8),
        /** **工作面**：目标内（`Policy.EXPLICIT_TARGET`）+ 目标外（`Policy.CLEARING`，走显式授权/预算/`TEMP`）都放行。 */
        L2_WORKFACE("L2", true, true, 0),
        /** **全权**：只剩预算/账本这一层；**只能由玩家显式取得**（见 {@link #zoneLevel(String, boolean)}）。 */
        L3_FULL("L3", true, true, 0);

        private final String label;
        private final boolean breakAllowed;
        private final boolean placeAllowed;
        private final int scaffoldPlaceQuota;

        Level(String label, boolean breakAllowed, boolean placeAllowed, int scaffoldPlaceQuota) {
            this.label = label;
            this.breakAllowed = breakAllowed;
            this.placeAllowed = placeAllowed;
            this.scaffoldPlaceQuota = scaffoldPlaceQuota;
        }

        /**
         * ⭐ `D-338` 附注十四：**非玩家发起**（LLM / 未归因）在**保护区**内的等级**封顶**。
         *
         * <p>只收紧、**不放宽**：`L0` 仍是 `L0`（只读不许被抬成"可临时放置"），`L2/L3` 降到 `L1`
         * （"能清障垫脚，**拆不了玩家的方块**"）。野外/无认领区块**根本不走这条**（`ZoneAuthority`
         * 在未认领时就返回 `NOT_GATED`）⇒ 对既有行为零影响。
         */
        public Level cappedForUnattended() {
            return ordinal() <= L1_SCAFFOLD.ordinal() ? this : L1_SCAFFOLD;
        }

        public String label() {
            return label;
        }

        public boolean allowsBreak() {
            return breakAllowed;
        }

        public boolean allowsPlace() {
            return placeAllowed;
        }

        /** **区内**放置配额（`0` = 无配额限制，只受预算/账本约束）；`L1` = 8（用户口径）。 */
        public int scaffoldPlaceQuota() {
            return scaffoldPlaceQuota;
        }
    }

    /**
     * **任务类别 → 区域级等级**（**单一出处**；用户拍板的阶梯 + 我的类别映射，见 `D-338` 附注七③）。
     *
     * <p>为什么 `MINING` ⇒ `L0`：`D-338` ④ 已定"**挖矿 = 野外采集，不发生在保护区内**"
     * ⇒ 保护区里的矿**本来就不该挖**（候选层排除照旧）⇒ 给它工作面等级会与那条裁定冲突。
     *
     * <p>为什么 `UNREGISTERED`/`DIAGNOSTIC` ⇒ `L0`：未登记的任务与自检夹具**不该**拿到区内写入权
     * （保守方向，且让"没登记"这件事保持可见）。
     */
    public static Level zoneLevel(Task task) {
        return switch (task) {
            case LUMBER, RESTORE, SURVIVAL -> Level.L2_WORKFACE;
            case BUILD, MANUAL -> Level.L3_FULL;
            case CRAFT -> Level.L1_SCAFFOLD;
            case TRAVERSAL, MINING, GATHERING, CONTAINER, DIAGNOSTIC, UNREGISTERED -> Level.L0_READ_ONLY;
        };
    }

    /**
     * **requester → 区域级等级**（含 ⭐"`L3` 只能由玩家显式取得"这条规则）。
     *
     * <p>没有玩家驱动身份（`Driver.IN_GAME_PLAYER`）时 `L3` **降级为 `L2`** —— 不是拒绝：
     * 拒绝一个 LLM 发起的建筑任务会更糟，但它拿不到 `L3` 的"只剩预算"那档额度。
     *
     * @param playerDriven 调用方（任务层）用 {@code Driver.of(bot)} 判定后传进来 ——
     *                     本类在 `action` 层，不去反向依赖 `decision` 层
     */
    public static Level zoneLevel(String requester, boolean playerDriven) {
        Level level = zoneLevel(taskOf(requester));
        if (level == Level.L3_FULL && !playerDriven) {
            return Level.L2_WORKFACE;
        }
        return level;
    }

    /** 放置的**回收义务**。 */
    public enum Obligation {
        /** 按理由自身语义（{@link WriteReason#temporary()}）——今天的默认行为。 */
        REASON_DEFAULT,
        /** 该组合下的放置**一律必须回收**（比理由更强的约束，更保守）。 */
        TEMP,
        /** 该组合下的放置**一律免回收**（= 上层显式授权，如批量地形编辑/玩家命令）。 */
        KEEP
    }

    /**
     * **移动授权词表 = {@link PathRequest} 的工厂名**（不新造词；每个名字都能 grep 到实现与登记条目）。
     *
     * <p>{@link #types()} **直接调用工厂**取得集合 ⇒ 表与工厂不会漂移（没有第二份副本可写错）。
     */
    public enum MovementGrant {
        OF("of"),
        PURE_TRAVERSAL("pureTraversal"),
        MINING_APPROACH("miningApproach"),
        SCAFFOLD_REMOVAL("scaffoldRemoval"),
        CLIMB_APPROACH("climbApproach"),
        WITH_WORLD_MODIFICATION("withWorldModification"),
        /** 逃生准备金（D-241）：放置 + 破坏 + PILLAR，**不含** `DOWNWARD`/`FALL`。 */
        SURVIVAL_ESCAPE("survivalEscape");

        private final String factory;

        MovementGrant(String factory) {
            this.factory = factory;
        }

        /** 工厂方法名（写日志/CSV 用）。 */
        public String factory() {
            return factory;
        }

        /** 该授权对应的 Movement 集合——**问工厂要**，不在这里抄一遍。 */
        public Set<MovementType> types() {
            BlockPos zero = BlockPos.ZERO;
            PathRequest probe = switch (this) {
                case OF -> PathRequest.of(PROBE, zero, zero, PROBE);
                case PURE_TRAVERSAL -> PathRequest.of(PROBE, zero, zero, PROBE).pureTraversal();
                case MINING_APPROACH -> PathRequest.miningApproach(PROBE, zero, zero, PROBE);
                case SCAFFOLD_REMOVAL -> PathRequest.scaffoldRemoval(PROBE, zero, zero);
                case CLIMB_APPROACH -> PathRequest.climbApproach(PROBE, zero, zero, PROBE);
                case WITH_WORLD_MODIFICATION -> PathRequest.withWorldModification(PROBE, zero, zero, PROBE);
                case SURVIVAL_ESCAPE -> PathRequest.survivalEscape(PROBE, zero, zero, PROBE);
            };
            return probe.allowedMovementTypes();
        }
    }

    private static final String PROBE = "policy-probe";

    /**
     * 一行策略。
     *
     * @param movements 该组合允许的移动授权集合；{@code null} = **未声明**（不拦、只留痕，用于 {@code UNREGISTERED}）
     * @param reasons   该组合登记的理由（覆盖检查的比对基准；未登记的写入会 WARN 留痕）
     */
    public record Row(String id, Zone zone, Task task, Obligation obligation,
                      Set<MovementGrant> movements, Set<WriteReason> reasons,
                      String codeRef, String note) {
    }

    // ==================== 表本体（单一出处）====================

    private static final Set<WriteReason> TEMP_PLACEMENTS = Set.of(
            WriteReason.STEP_PLACEMENT, WriteReason.SUPPORT_PLACEMENT, WriteReason.CRAFT_STATION_PLACE);

    /**
     * **逃生准备金能带出的理由**（D-241）—— 取**执行器真正会发出**的那两个（实测：`PillarExecution:57`
     * 与 `PlaceStepAndTraverseExecution:41` 用 `STEP_PLACEMENT`、`BreakAndTraverseExecution:52` 用 `PATH_ACCESS`），
     * **刻意不新造"逃生专用理由码"**：归因由 `requester="survival-escape"`（已登记前缀 → `Task.SURVIVAL`）承担，
     * 新造词只会再造一个没人发的死值（K-5 同族）。
     */
    private static final Set<WriteReason> ESCAPE_REASONS = Set.of(
            WriteReason.STEP_PLACEMENT, WriteReason.PATH_ACCESS);

    private static final Set<WriteReason> PATHING_REASONS = Set.of(
            WriteReason.PATH_ACCESS, WriteReason.DESCEND_FOOT, WriteReason.STEP_PLACEMENT,
            WriteReason.SUPPORT_PLACEMENT, WriteReason.SCAFFOLD_RESTORE, WriteReason.EXPECTED_TARGET,
            WriteReason.STANDING_SPACE, WriteReason.LINE_OF_SIGHT);

    /**
     * 会写世界的移动集（{@code withWorldModification}）能带出的理由。
     *
     * <p>破坏类理由也在这里：账本**只记放置**，但表要对齐"这个组合能干什么"——
     * 少列一条就会在运行期触发 {@code undeclared_reason} 留痕（那不是拒绝，是"表与代码不一致"的信号）。
     */
    private static final Set<WriteReason> WORLD_MOD_REASONS = Set.of(
            WriteReason.PATH_ACCESS, WriteReason.DESCEND_FOOT, WriteReason.STEP_PLACEMENT,
            WriteReason.SUPPORT_PLACEMENT, WriteReason.SCAFFOLD_RESTORE, WriteReason.EXPECTED_TARGET,
            WriteReason.STANDING_SPACE);

    /** 集合合并助手（表里读起来比 `Set.of(a, b, c...)` 摊平更清楚"共同部分 + 特有部分"）。 */
    private static Set<WriteReason> with(Set<WriteReason> base, WriteReason... extra) {
        Set<WriteReason> result = new LinkedHashSet<>(base);
        result.addAll(List.of(extra));
        return Set.copyOf(result);
    }

    public static final List<Row> ROWS = List.of(
            // ---- EXTERNAL（默认：不是 Alice 的地）----
            new Row("P-01", Zone.EXTERNAL, Task.TRAVERSAL, Obligation.TEMP,
                    Set.of(MovementGrant.OF, MovementGrant.PURE_TRAVERSAL), PATHING_REASONS,
                    "action/PathRequest.java:35,119",
                    "纯通行任务（walk-to/follow/place）：**刻意显式 TEMP** —— 走到某处不该留下永久痕迹；"
                            + "移动集刻意**不含** withWorldModification（D-076 红线的可执行版本）"),
            new Row("P-02", Zone.EXTERNAL, Task.MINING, Obligation.REASON_DEFAULT,
                    Set.of(MovementGrant.OF, MovementGrant.PURE_TRAVERSAL, MovementGrant.MINING_APPROACH,
                            MovementGrant.CLIMB_APPROACH),
                    Set.of(WriteReason.EXPECTED_TARGET, WriteReason.STANDING_SPACE, WriteReason.PATH_ACCESS,
                            WriteReason.SUPPORT_PLACEMENT, WriteReason.STEP_PLACEMENT,
                            WriteReason.SCAFFOLD_RESTORE),
                    "task/mining/MiningPlanner.java:182,241、action/MineBlockRunner.java:158",
                    "挖掘站位用 miningApproach（D-067 ㉘ 禁用 PILLAR/FALL/DOWNWARD）；支撑块用完即拆"),
            new Row("P-03", Zone.EXTERNAL, Task.GATHERING, Obligation.REASON_DEFAULT,
                    Set.of(MovementGrant.OF, MovementGrant.PURE_TRAVERSAL, MovementGrant.WITH_WORLD_MODIFICATION,
                            MovementGrant.CLIMB_APPROACH),
                    with(WORLD_MOD_REASONS, WriteReason.LINE_OF_SIGHT),
                    "task/CollectDropsTask.java:254,360",
                    "掉落物收集需调用方**显式** allowWorldModification（D-076）"),
            new Row("P-04", Zone.EXTERNAL, Task.LUMBER, Obligation.REASON_DEFAULT,
                    Set.of(MovementGrant.OF, MovementGrant.PURE_TRAVERSAL, MovementGrant.MINING_APPROACH,
                            MovementGrant.CLIMB_APPROACH),
                    Set.of(WriteReason.LINE_OF_SIGHT, WriteReason.EXPECTED_TARGET, WriteReason.REGION_REPLANT,
                            WriteReason.STEP_PLACEMENT, WriteReason.SUPPORT_PLACEMENT,
                            WriteReason.STANDING_SPACE),
                    "job/lumber/RegionLumberJob.java:467、job/lumber/LumberJob.java:348",
                    "补种 = 计划内永久（KEEP 来自理由自身）；脚手架仍 TEMP"),
            new Row("P-05", Zone.EXTERNAL, Task.CRAFT, Obligation.REASON_DEFAULT,
                    Set.of(MovementGrant.OF, MovementGrant.PURE_TRAVERSAL, MovementGrant.WITH_WORLD_MODIFICATION),
                    with(WORLD_MOD_REASONS, WriteReason.CRAFT_STATION_PLACE, WriteReason.CONTAINER_TRANSFER),
                    "task/craft/StationPlacement.java:116、job/craft/CraftJob.java:310",
                    "合成工作站用完即拆（TEMP，建拆同权）；**熔炼路线要写容器**（放料/取产物）"
                            + "——2026-09-14 覆盖面审查发现 CraftJob 这几下没记账 ⇒ 补声明 + 过容器闸门"),
            new Row("P-06", Zone.EXTERNAL, Task.CONTAINER, Obligation.REASON_DEFAULT,
                    Set.of(MovementGrant.OF, MovementGrant.PURE_TRAVERSAL, MovementGrant.WITH_WORLD_MODIFICATION),
                    with(WORLD_MOD_REASONS, WriteReason.CONTAINER_TRANSFER, WriteReason.STATION_PROVISION),
                    "task/TransferTask.java:197、task/craft/StationProvision.java:242",
                    "容器写入走 WriteBudget 的容器维度（不产生放置账本条目）"),
            new Row("P-07", Zone.EXTERNAL, Task.BUILD, Obligation.KEEP,
                    Set.of(MovementGrant.OF, MovementGrant.PURE_TRAVERSAL),
                    Set.of(WriteReason.BULK_EDIT, WriteReason.EXPECTED_TARGET),
                    "task/RoadBuildTask.java:33、road/RoadBuilder.java:27",
                    "**显式 KEEP**：批量地形编辑是上层显式授权（D-095 §12.1），不被默认区降级为 TEMP"),
            new Row("P-08", Zone.EXTERNAL, Task.RESTORE, Obligation.REASON_DEFAULT,
                    Set.of(MovementGrant.SCAFFOLD_REMOVAL, MovementGrant.OF, MovementGrant.PURE_TRAVERSAL),
                    Set.of(WriteReason.SCAFFOLD_RESTORE, WriteReason.DESCEND_FOOT, WriteReason.EXPECTED_TARGET),
                    "task/RestoreScopeTask.java:303,367",
                    "只拆不建（scaffoldRemoval 不含 PILLAR/PLACE_STEP/BREAK_*）；回收豁免破坏上限"),
            new Row("P-09", Zone.EXTERNAL, Task.MANUAL, Obligation.REASON_DEFAULT,
                    Set.of(MovementGrant.OF, MovementGrant.PURE_TRAVERSAL, MovementGrant.WITH_WORLD_MODIFICATION,
                            MovementGrant.CLIMB_APPROACH),
                    with(WORLD_MOD_REASONS, WriteReason.MANUAL, WriteReason.LINE_OF_SIGHT),
                    "bot/BotManager.java:1706、command/BotCommand.java:1058",
                    "**不显式 KEEP**（虽然 MANUAL 理由本身是永久类）：命令挖矿会顺带垫脚/搭柱，"
                            + "那些脚手架必须回收 —— 显式 KEEP 会把垃圾永远留在世界里"),
            new Row("P-10", Zone.EXTERNAL, Task.DIAGNOSTIC, Obligation.REASON_DEFAULT,
                    Set.of(MovementGrant.OF, MovementGrant.PURE_TRAVERSAL, MovementGrant.WITH_WORLD_MODIFICATION,
                            MovementGrant.MINING_APPROACH, MovementGrant.SCAFFOLD_REMOVAL,
                            MovementGrant.CLIMB_APPROACH),
                    Set.of(WriteReason.values()),
                    "task/…CheckTask.java、item/Pathing…Item.java",
                    "自检/夹具：为了能**构造**各类场景而持有全集；"
                            + "注意它是**唯一**持有全集的行 ⇒ 生产任务不能用它兜底"),
            new Row("P-11", Zone.EXTERNAL, Task.UNREGISTERED, Obligation.REASON_DEFAULT,
                    null, Set.of(WriteReason.values()),
                    "action/WriteGrant.java:26（UNKNOWN）",
                    "未登记 requester：**movements=null ⇒ 不拦**，但写入/规划会留痕（计数 + 一次 WARN）"),
            // ---- WORKSPACE（Alice 的地：来源 = 玩家已划定的区域）----
            // 今天与 EXTERNAL **逐条相同**（ⓑ：工作区只是"允许"KEEP 类理由，不改其余理由的回收义务）。
            // 差异一旦出现，workspace_equiv 断言会红 ⇒ 必须同时补决策记录。
            new Row("P-12", Zone.WORKSPACE, Task.TRAVERSAL, Obligation.TEMP,
                    Set.of(MovementGrant.OF, MovementGrant.PURE_TRAVERSAL), PATHING_REASONS,
                    "同上（R1b 起工作区才可能不同）", "同 P-01（今天两区解析相同）"),
            new Row("P-13", Zone.WORKSPACE, Task.MINING, Obligation.REASON_DEFAULT,
                    Set.of(MovementGrant.OF, MovementGrant.PURE_TRAVERSAL, MovementGrant.MINING_APPROACH,
                            MovementGrant.CLIMB_APPROACH),
                    Set.of(WriteReason.EXPECTED_TARGET, WriteReason.STANDING_SPACE, WriteReason.PATH_ACCESS,
                            WriteReason.SUPPORT_PLACEMENT, WriteReason.STEP_PLACEMENT,
                            WriteReason.SCAFFOLD_RESTORE),
                    "同 P-02", "同 P-02（今天两区解析相同）"),
            new Row("P-14", Zone.WORKSPACE, Task.GATHERING, Obligation.REASON_DEFAULT,
                    Set.of(MovementGrant.OF, MovementGrant.PURE_TRAVERSAL, MovementGrant.WITH_WORLD_MODIFICATION,
                            MovementGrant.CLIMB_APPROACH),
                    with(WORLD_MOD_REASONS, WriteReason.LINE_OF_SIGHT),
                    "同 P-03", "同 P-03（今天两区解析相同）"),
            new Row("P-15", Zone.WORKSPACE, Task.LUMBER, Obligation.REASON_DEFAULT,
                    Set.of(MovementGrant.OF, MovementGrant.PURE_TRAVERSAL, MovementGrant.MINING_APPROACH,
                            MovementGrant.CLIMB_APPROACH),
                    Set.of(WriteReason.LINE_OF_SIGHT, WriteReason.EXPECTED_TARGET, WriteReason.REGION_REPLANT,
                            WriteReason.STEP_PLACEMENT, WriteReason.SUPPORT_PLACEMENT,
                            WriteReason.STANDING_SPACE),
                    "job/lumber/LumberRegionState.java:32", "工作区的**唯一来源**就是这里的已划区域"),
            new Row("P-16", Zone.WORKSPACE, Task.CRAFT, Obligation.REASON_DEFAULT,
                    Set.of(MovementGrant.OF, MovementGrant.PURE_TRAVERSAL, MovementGrant.WITH_WORLD_MODIFICATION),
                    with(WORLD_MOD_REASONS, WriteReason.CRAFT_STATION_PLACE, WriteReason.CONTAINER_TRANSFER),
                    "同 P-05", "同 P-05（今天两区解析相同）"),
            new Row("P-17", Zone.WORKSPACE, Task.CONTAINER, Obligation.REASON_DEFAULT,
                    Set.of(MovementGrant.OF, MovementGrant.PURE_TRAVERSAL, MovementGrant.WITH_WORLD_MODIFICATION),
                    with(WORLD_MOD_REASONS, WriteReason.CONTAINER_TRANSFER, WriteReason.STATION_PROVISION),
                    "同 P-06", "同 P-06（今天两区解析相同）"),
            new Row("P-18", Zone.WORKSPACE, Task.BUILD, Obligation.KEEP,
                    Set.of(MovementGrant.OF, MovementGrant.PURE_TRAVERSAL),
                    Set.of(WriteReason.BULK_EDIT, WriteReason.EXPECTED_TARGET),
                    "同 P-07", "同 P-07（今天两区解析相同）"),
            new Row("P-19", Zone.WORKSPACE, Task.RESTORE, Obligation.REASON_DEFAULT,
                    Set.of(MovementGrant.SCAFFOLD_REMOVAL, MovementGrant.OF, MovementGrant.PURE_TRAVERSAL),
                    Set.of(WriteReason.SCAFFOLD_RESTORE, WriteReason.DESCEND_FOOT, WriteReason.EXPECTED_TARGET),
                    "同 P-08", "同 P-08（今天两区解析相同）"),
            new Row("P-20", Zone.WORKSPACE, Task.MANUAL, Obligation.REASON_DEFAULT,
                    Set.of(MovementGrant.OF, MovementGrant.PURE_TRAVERSAL, MovementGrant.WITH_WORLD_MODIFICATION,
                            MovementGrant.CLIMB_APPROACH),
                    with(WORLD_MOD_REASONS, WriteReason.MANUAL, WriteReason.LINE_OF_SIGHT),
                    "同 P-09", "同 P-09（今天两区解析相同）"),
            new Row("P-21", Zone.WORKSPACE, Task.DIAGNOSTIC, Obligation.REASON_DEFAULT,
                    Set.of(MovementGrant.OF, MovementGrant.PURE_TRAVERSAL, MovementGrant.WITH_WORLD_MODIFICATION,
                            MovementGrant.MINING_APPROACH, MovementGrant.SCAFFOLD_REMOVAL,
                            MovementGrant.CLIMB_APPROACH),
                    Set.of(WriteReason.values()),
                    "同 P-10", "同 P-10（今天两区解析相同）"),
            new Row("P-22", Zone.WORKSPACE, Task.UNREGISTERED, Obligation.REASON_DEFAULT,
                    null, Set.of(WriteReason.values()),
                    "action/WriteGrant.java:26（UNKNOWN）", "同 P-11"),
            // ---- 维生自救（D-241，2026-09-16 用户批准的提案 B）----
            // 轴 = "这个任务改不改世界"（`MovementCapabilities.changesWorld()`）：只有信封里本就有写授权的任务，
            // 才允许在危急时用 `survivalEscape`（放置 + 破坏 + PILLAR，不含 DOWNWARD/FALL）。
            new Row("P-23", Zone.EXTERNAL, Task.SURVIVAL, Obligation.TEMP,
                    Set.of(MovementGrant.OF, MovementGrant.PURE_TRAVERSAL, MovementGrant.SURVIVAL_ESCAPE),
                    ESCAPE_REASONS,
                    "action/PathRequest.java:53（survivalEscape）",
                    "逃生准备金（Q2/Q3/Q4 定案）：**放置 TEMP 必拆**（复用 scaffoldRemoval）+ 破坏按 KEEP 登记；"
                            + "预算上限 8 破坏/8 放置、每次危险事件最多升档 1 次；信封无写权 ⇒ 根本不发这张凭证；"
                            + "**回收时机由玩家定（D-245）：逃生任务自己不拆**（自动拆会把 bot 关回坑里 ⇒ 逃生循环）"
                            + "⇒ 拆走玩家入口 `/alice restore` / `alice:restore_check`（同一条 RestoreScopeTask 路径）"),
            new Row("P-24", Zone.WORKSPACE, Task.SURVIVAL, Obligation.TEMP,
                    Set.of(MovementGrant.OF, MovementGrant.PURE_TRAVERSAL, MovementGrant.SURVIVAL_ESCAPE),
                    ESCAPE_REASONS,
                    "同 P-23", "同 P-23（今天两区解析相同：逃生留下的桥**照样要拆**，不因为在自己地上就免回收；"
                            + "**拆的时机由玩家说了算** —— 见 D-245）"));

    // ==================== requester 登记表（前缀 → 任务类别）====================

    /**
     * 登记表（**最长前缀优先**）。
     *
     * <p>为什么用前缀而不是精确串：`PathRetryRunner` 会给 requester 追加 `:attemptN`，
     * 任务类 requester 又常等于 {@code taskName()}（如 `ClearRetryCheck`）——精确串维护不住。
     */
    private static final List<String[]> PREFIX_RULES = List.of(
            new String[]{"survival-escape", "SURVIVAL"},
            new String[]{"road-build", "BUILD"},
            new String[]{"road-builder", "BUILD"},
            new String[]{"transfer", "CONTAINER"},
            new String[]{"station-provision", "CONTAINER"},
            // 3-B / S4 机器闭环（`MachineCycleCheckTask`）：往机器容器放料、把产物取回来
            // ⇒ 与传输/装配同一类（容器写入维度 + CONTAINER_TRANSFER），不新造类别。
            new String[]{"machine-cycle", "CONTAINER"},
            new String[]{"craft-station", "CRAFT"},
            new String[]{"craft", "CRAFT"},
            new String[]{"mine-plan", "MINING"},
            new String[]{"mine-runner", "MINING"},
            new String[]{"mining-planner", "MINING"},
            // job NAME（`job/mine/MineJob.java:91`）：任务自身的 requester 就是 "mine"
            new String[]{"mine", "MINING"},
            // job NAME（`job/lumber/RegionLumberJob.java:134` = "region_lumber"）
            new String[]{"region_lumber", "LUMBER"},
            new String[]{"lumber-plan", "LUMBER"},
            new String[]{"lumber", "LUMBER"},
            new String[]{"collect-drops", "GATHERING"},
            new String[]{"collect", "GATHERING"},
            new String[]{"restore", "RESTORE"},
            new String[]{"RestoreScope", "RESTORE"},
            new String[]{"walk-to", "TRAVERSAL"},
            new String[]{"follow", "TRAVERSAL"},
            new String[]{"place", "TRAVERSAL"},
            new String[]{"standing-cost-estimator", "TRAVERSAL"},
            new String[]{"item:pathing-fence-guard", "TRAVERSAL"},
            new String[]{"item:pathing-dip-route", "TRAVERSAL"},
            new String[]{"item:pathing-planner", "TRAVERSAL"},
            new String[]{"item:pathing-lava-guard", "DIAGNOSTIC"},
            new String[]{"item:pathing-fluid-guard", "DIAGNOSTIC"},
            // 夹具的自造 requester（`PartialSearchCheckTask:97-112`）
            new String[]{"partial", "DIAGNOSTIC"},
            // 夹具（`ScaffoldLifecycleTask:531`）
            new String[]{"scaffold-lifecycle", "DIAGNOSTIC"},
            new String[]{"command", "MANUAL"},
            new String[]{"ToolMaintenance", "CRAFT"});

    /** 派生规则（前缀规则没命中时按名字形态判定；夹具/自检的命名约定：`*Check`/`*Probe`/`*Diagnostic`）。 */
    private static Task derivedTask(String lowerNormalized) {
        // ① 夹具/自检标记**优先**：一个叫 `LumberFailureCheck` 的东西是夹具，不该按"lumber"归到生产任务
        // ⚠️ T1 / R-3（2026-09-14）：判据改调 `Task.looksLikeSelfCheck`（**唯一真源**）。
        // 这里此前内联了一份标记表，而 `Task.isSelfCheck()` 用的是**后缀**约定 ⇒ 两套约定漂移了
        // **18 个类**（主电池 `RegressionBatteryTask` 自己在差集里）⇒ 电池终态会招 LLM、占住测试场地。
        if (com.dddgn.alice.task.Task.looksLikeSelfCheck(lowerNormalized)) {
            return Task.DIAGNOSTIC;
        }
        // ② 任务族（`region_lumber` 这类带下划线的名字归一化后也能命中）
        //    注：`Task.taskName()` 的默认值是**类名**（`task/Task.java:42`）⇒ requester 可能是 "PlaceTask"，
        //    所以先剥掉尾缀 "task"，否则 `PlaceTask` 认不出来（它不会命中前缀规则 "place"：大小写不同）。
        String stripped = lowerNormalized.endsWith("task")
                ? lowerNormalized.substring(0, lowerNormalized.length() - 4) : lowerNormalized;
        if (stripped.contains("lumber")) {
            return Task.LUMBER;
        }
        if (stripped.contains("collect")) {
            return Task.GATHERING;
        }
        if (stripped.contains("craft")) {
            return Task.CRAFT;
        }
        if (stripped.contains("transfer")) {
            return Task.CONTAINER;
        }
        if (stripped.contains("road")) {
            return Task.BUILD;
        }
        if (stripped.contains("restore")) {
            return Task.RESTORE;
        }
        if (stripped.contains("command")) {
            return Task.MANUAL;
        }
        if (stripped.contains("mine")) {
            return Task.MINING;
        }
        if (stripped.contains("walk") || stripped.contains("follow") || stripped.contains("pathing")
                || stripped.contains("place")) {
            return Task.TRAVERSAL;
        }
        if (com.dddgn.alice.task.Task.looksLikeSelfCheck(stripped)) {
            return Task.DIAGNOSTIC;
        }
        return Task.UNREGISTERED;
    }

    /** `requester` → 任务类别（登记表查不到 ⇒ {@link Task#UNREGISTERED}，**记为错误**）。 */
    public static Task taskOf(String requester) {
        if (requester == null || requester.isBlank()) {
            return Task.UNREGISTERED;
        }
        String value = requester.trim();
        String bestPrefix = null;
        Task bestTask = null;
        for (String[] rule : PREFIX_RULES) {
            if (value.startsWith(rule[0]) && (bestPrefix == null || rule[0].length() > bestPrefix.length())) {
                bestPrefix = rule[0];
                bestTask = Task.valueOf(rule[1]);
            }
        }
        if (bestTask != null) {
            return bestTask;
        }
        return derivedTask(value.toLowerCase(Locale.ROOT).replace("_", "").replace("-", ""));
    }

    // ==================== 区域（R1b 才接线来源）====================

    /**
     * 区域归属的来源。**默认恒 {@link Zone#EXTERNAL}**。
     *
     * <p>为什么不直接调 {@code LumberRegionState}：那是 `job.lumber` 的高层类，
     * 而本类在 `action`（低层）。低层不该反向依赖 job 层（今天仓库里没有这种依赖，别开这个头）；
     * R1b 接线时由**上层**注入（`installZoneSource`）。
     *
     * <p>今天不接线也**不丢任何行为**：EXTERNAL 与 WORKSPACE 对每个理由解析相同（见类注释）。
     */
    public interface ZoneSource {
        Zone zoneAt(ServerLevel level, BlockPos pos, java.util.UUID owner);
    }

    private static volatile ZoneSource zoneSource = (level, pos, owner) -> Zone.EXTERNAL;

    /** 由上层安装区域来源（R1b：已划区域 ⇒ WORKSPACE）。 */
    public static void installZoneSource(ZoneSource source) {
        zoneSource = source == null ? (level, pos, owner) -> Zone.EXTERNAL : source;
    }

    /** 该位置属于哪种归属（今天恒 EXTERNAL）。 */
    public static Zone zoneAt(ServerLevel level, BlockPos pos, java.util.UUID owner) {
        if (level == null || pos == null) {
            return Zone.EXTERNAL;
        }
        return zoneSource.zoneAt(level, pos, owner);
    }

    // ==================== 解析 ====================

    /** 精确查行；缺失时回退到同任务的 EXTERNAL 行，再回退到 UNREGISTERED 行（**函数总是全的**）。 */
    public static Row row(Zone zone, Task task) {
        for (Row candidate : ROWS) {
            if (candidate.zone() == zone && candidate.task() == task) {
                return candidate;
            }
        }
        for (Row candidate : ROWS) {
            if (candidate.zone() == Zone.EXTERNAL && candidate.task() == task) {
                return candidate;
            }
        }
        for (Row candidate : ROWS) {
            if (candidate.task() == Task.UNREGISTERED) {
                return candidate;
            }
        }
        throw new IllegalStateException("策略表缺 UNREGISTERED 行（表被改坏了）");
    }

    /** 回收义务（**执行期与账本的唯一判据**）。 */
    public static Obligation obligation(Zone zone, Task task, WriteReason reason) {
        Row row = row(zone, task);
        return switch (row.obligation()) {
            case TEMP -> Obligation.TEMP;
            case KEEP -> Obligation.KEEP;
            case REASON_DEFAULT -> reason != null && reason.temporary() ? Obligation.TEMP : Obligation.KEEP;
        };
    }

    /** 该组合允许的移动授权；{@code null} = 未声明（不拦）。 */
    public static Set<MovementGrant> grants(Zone zone, Task task) {
        return row(zone, task).movements();
    }

    /** 该组合允许的 Movement 类型全集；未声明 ⇒ {@code null}。 */
    public static Set<MovementType> allowedMovementTypes(Zone zone, Task task) {
        Set<MovementGrant> grants = grants(zone, task);
        if (grants == null) {
            return null;
        }
        Set<MovementType> allowed = new LinkedHashSet<>();
        for (MovementGrant grant : grants) {
            allowed.addAll(grant.types());
        }
        return allowed;
    }

    // ==================== 规划期执法 ====================

    /** 规划期违规（**早失败、可归因**：带上 requester/任务类别/超出的 Movement）。 */
    public static final class Violation extends IllegalArgumentException {
        private final String code;

        Violation(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * **规划期**校验：这次请求要用的 Movement 必须在 (区域, 任务) 的授权内，否则抛 {@link Violation}。
     *
     * <p>为什么在**规划入口**做（`CorePathPlanner.plan`）：那是所有规划的必经之处，
     * 放在这里等于"一处管住全部"；而放到每个调用点，就会重演"六份重复"的老问题。
     * 为什么抛而不是降级：与 `RecoverabilityPolicy` 同一条理由——**规划期就该炸，
     * 别把越权的路带进执行期**（执行期复验是第二道，不是第一道）。
     *
     * <p>未登记 requester（{@code movements == null}）**不抛**，但留痕（{@link #noteUnregistered}）：
     * 那是登记表缺口，拿它拒绝会误伤生产任务。
     */
    public static void requireMovementsGranted(ServerLevel level, PathRequest request) {
        if (request == null) {
            return;
        }
        Task task = taskOf(request.requester());
        if (task == Task.UNREGISTERED) {
            noteUnregistered(request.requester(), "pathing");
        }
        Zone zone = zoneAt(level, request.startFoot(), null);
        Set<MovementType> allowed = allowedMovementTypes(zone, task);
        if (allowed == null) {
            return;   // 未声明 ⇒ 不拦
        }
        Set<MovementType> extra = new LinkedHashSet<>(request.allowedMovementTypes());
        extra.removeAll(allowed);
        if (!extra.isEmpty()) {
            throw new Violation("WRITE_POLICY_MOVEMENT_DENIED",
                    "世界写入授权越权：requester=" + request.requester() + " task=" + task
                            + " zone=" + zone + " row=" + row(zone, task).id()
                            + " 未授权的 Movement=" + extra
                            + "（该行只授权 " + row(zone, task).movements() + "）");
        }
    }

    // ==================== 执行期复验 ====================

    /**
     * 执行期复验（由账本在**记录放置时**调用）：把 (区域, 任务, 理由) 解析成账本策略。
     *
     * <p>未登记的 (任务, 理由) 组合会留痕（{@link #noteUndeclared}）但**不拒绝**：
     * 与"未登记 requester"同一口径——记录为错误，而不是拿我们自己的登记缺口去拒绝任务。
     */
    public static com.dddgn.alice.ledger.WorldModLedger.Policy ledgerPolicy(ServerLevel level, java.util.UUID owner,
                                                                            WriteGrant grant, BlockPos pos) {
        Task task = taskOf(grant == null ? null : grant.requester());
        if (task == Task.UNREGISTERED) {
            noteUnregistered(grant == null ? null : grant.requester(), "place");
        }
        Zone zone = zoneAt(level, pos, owner);
        WriteReason reason = grant == null ? null : grant.reason();
        Row row = row(zone, task);
        if (reason != null && !row.reasons().contains(reason)) {
            noteUndeclared(row, reason);
        }
        return obligation(zone, task, reason) == Obligation.TEMP
                ? com.dddgn.alice.ledger.WorldModLedger.Policy.TEMP
                : com.dddgn.alice.ledger.WorldModLedger.Policy.KEEP;
    }

    // ==================== 容器写入（执行期复验的容器对应物）====================

    /**
     * 容器写入的策略判定。
     *
     * <p><b>为什么容器写入要有自己的判定</b>：放置类写入的"执行期复验"挂在账本
     * （{@link #ledgerPolicy}，由 {@code WorldModLedger.record} 调用）；而**容器写入不产生账本条目**
     * （它改的是容器内容，不是方块）⇒ 到今天为止 {@code P-06/P-17} 行登记的
     * {@code CONTAINER_TRANSFER}/{@code STATION_PROVISION} **没有任何读者**：表在，牙齿不在。
     * 本方法的挂点是 {@link WriteBudget#consumeContainerWrite} —— **所有**已接线的容器写入的必经之处
     * （与"移动授权挂在 {@code CorePathPlanner.plan}"同一个理由：一处管住全部）。
     *
     * <p><b>判定口径与移动授权**对齐**（不是新口径）</b>：
     * <ul>
     *   <li>requester 未登记（{@code UNREGISTERED}）⇒ **不拒**，只留痕 —— 那是我们的登记缺口，
     *       拿它拒绝会误伤生产任务（同 {@link #requireMovementsGranted}）；</li>
     *   <li>requester 已登记、但该 (区域, 任务) 行**没声明**这个理由 ⇒ {@link Decision#UNDECLARED_REASON}，
     *       {@link #refuses} 在武装时返回 true ⇒ **硬拒**。这是"纯通行任务不许写容器"的可执行版本；</li>
     *   <li>已声明的组合 ⇒ 放行；**预算维度照旧是数量闸门**，两者互不替代（策略管"能不能"，
     *       预算管"还能几次"）。</li>
     * </ul>
     *
     * <p><b>为什么这里拒绝、而放置类的"未声明理由"只留痕</b>（**有意的不对称，别照抄**）：
     * 放置类的未声明理由仍能安全落地——回收义务由 {@link #obligation} 兜底（决定"要不要拆回来"），
     * 那是一个**后果**决定；容器写入没有后果维度（取出来的东西不会自己回去），
     * 所以"这一格能不能写"是它唯一的门 ⇒ 门必须是硬的，否则等于没有门。
     */
    public enum Decision {
        /** requester 已登记，且该行声明了这个理由：允许。 */
        DECLARED,
        /** requester 未登记（登记缺口）：留痕、**不拒**。 */
        UNREGISTERED,
        /** 已登记的任务写了它**没声明**的理由：武装时拒绝。 */
        UNDECLARED_REASON
    }

    /**
     * 容器写入的策略判定（**纯函数**：不留痕、不动计数、不看武装开关）。
     *
     * <p>夹具用它做负例断言（{@code WritePolicyCheckTask.container_gate_live}），
     * 因此它必须**零副作用**——否则自检会污染电池样本（G 段断言"未登记=0/未声明=0"）。
     */
    public static Decision decideContainerWrite(ServerLevel level, java.util.UUID owner,
                                                BlockPos pos, WriteGrant grant) {
        Task task = taskOf(grant == null ? null : grant.requester());
        if (task == Task.UNREGISTERED) {
            return Decision.UNREGISTERED;
        }
        Row row = row(zoneAt(level, pos, owner), task);
        if (row.movements() == null) {
            return Decision.UNREGISTERED;   // 该行没声明任何能力 ⇒ 不拦（与 allowedMovementTypes==null 同口径）
        }
        WriteReason reason = grant == null ? null : grant.reason();
        return reason != null && row.reasons().contains(reason)
                ? Decision.DECLARED : Decision.UNDECLARED_REASON;
    }

    /** 判定 + 留痕 + 计数（**执行期入口**；由 {@link WriteBudget#consumeContainerWrite} 调用）。 */
    public static Decision noteContainerWrite(ServerLevel level, java.util.UUID owner,
                                              BlockPos pos, WriteGrant grant) {
        Decision decision = decideContainerWrite(level, owner, pos, grant);
        CONTAINER_CHECKS++;
        if (refuses(decision)) {
            CONTAINER_REFUSED++;
        }
        switch (decision) {
            case UNREGISTERED -> noteUnregistered(grant == null ? null : grant.requester(), "container");
            case UNDECLARED_REASON -> {
                WriteReason reason = grant == null ? null : grant.reason();
                if (reason == null) {
                    // 到不了这里（UNDECLARED 的前提是 requester 已登记、grant 必非空）；响亮记一笔而不是 NPE
                    BotLog.warn("[WritePolicy] container_write 判定 UNDECLARED_REASON 但 reason 为空（调用点 bug）");
                } else {
                    noteUndeclared(row(zoneAt(level, pos, owner), taskOf(grant.requester())), reason);
                }
            }
            case DECLARED -> {
            }
        }
        return decision;
    }

    /**
     * 该判定在**当前模式**下是否真的拒绝。
     *
     * <p>武装开关（{@link #setContainerRefusalArmed}）的存在理由：它是**一线回退把手**——
     * 若某条已登记任务因表缺口被硬停，退回"观察模式"（留痕但不拒）只需一行，不必改代码逻辑。
     * 默认**武装**（与移动授权"规划期就该炸"同一口径：闸门要能真的拦住东西）。
     */
    public static boolean refuses(Decision decision) {
        return decision == Decision.UNDECLARED_REASON && containerGateArmed;
    }

    /** 容器写入的拒绝权是否武装（默认 true）。 */
    public static boolean containerRefusalArmed() {
        return containerGateArmed;
    }

    /** 开关拒绝权（**夹具专用**：负例要断言两种模式都活着；也用作生产回退把手）。 */
    public static void setContainerRefusalArmed(boolean armed) {
        containerGateArmed = armed;
    }

    // ==================== 留痕（"缺口不静默"）====================

    private static final Map<String, Integer> UNREGISTERED_SEEN = new LinkedHashMap<>();
    private static final Set<String> UNREGISTERED_LOGGED = new LinkedHashSet<>();
    private static final Map<String, Integer> UNDECLARED_SEEN = new LinkedHashMap<>();
    private static final Set<String> UNDECLARED_LOGGED = new LinkedHashSet<>();

    /** 容器写入闸门是否**真的**拒绝（默认武装；见 {@link #refuses}）。 */
    private static volatile boolean containerGateArmed = true;
    /** 经过容器写入闸门的调用次数（**覆盖面的活体证据**：装了闸门却恒为 0 ⇒ 接线断了）。 */
    private static int CONTAINER_CHECKS;
    /** 其中被拒的次数（武装模式下 = 未声明理由的容器写入次数）。 */
    private static int CONTAINER_REFUSED;

    /** 已过闸门的容器写入次数（0 而世界里确实有容器写入 ⇒ 挂点没接上）。 */
    public static int containerChecks() {
        return CONTAINER_CHECKS;
    }

    /** 被策略拒绝的容器写入次数。 */
    public static int containerRefused() {
        return CONTAINER_REFUSED;
    }

    /** 观察样本快照（**夹具专用**：做负例断言后原样还原，不污染电池样本）。 */
    public record Observations(Map<String, Integer> unregistered, Map<String, Integer> undeclared,
                               Set<String> unregisteredLogged, Set<String> undeclaredLogged,
                               int containerChecks, int containerRefused) {
    }

    public static Observations snapshotObservations() {
        return new Observations(Map.copyOf(UNREGISTERED_SEEN), Map.copyOf(UNDECLARED_SEEN),
                Set.copyOf(UNREGISTERED_LOGGED), Set.copyOf(UNDECLARED_LOGGED),
                CONTAINER_CHECKS, CONTAINER_REFUSED);
    }

    public static void restoreObservations(Observations snapshot) {
        if (snapshot == null) {
            return;
        }
        UNREGISTERED_SEEN.clear();
        UNREGISTERED_SEEN.putAll(snapshot.unregistered());
        UNDECLARED_SEEN.clear();
        UNDECLARED_SEEN.putAll(snapshot.undeclared());
        UNREGISTERED_LOGGED.clear();
        UNREGISTERED_LOGGED.addAll(snapshot.unregisteredLogged());
        UNDECLARED_LOGGED.clear();
        UNDECLARED_LOGGED.addAll(snapshot.undeclaredLogged());
        CONTAINER_CHECKS = snapshot.containerChecks();
        CONTAINER_REFUSED = snapshot.containerRefused();
    }

    /** 该 requester 未登记：计数 + 首次 WARN（**记为错误**，但不据此拒绝）。 */
    public static void noteUnregistered(String requester, String where) {
        String key = (requester == null || requester.isBlank() ? "<blank>" : requester.trim());
        UNREGISTERED_SEEN.merge(key, 1, Integer::sum);
        // PathRetryRunner 会追加 ":attemptN"：按前缀收敛，避免一次任务刷一行
        String base = key.contains(":attempt") ? key.substring(0, key.indexOf(":attempt")) : key;
        if (!UNREGISTERED_LOGGED.contains(base)) {
            UNREGISTERED_LOGGED.add(base);
            BotLog.warn("[WritePolicy] unregistered_requester requester={} where={}"
                            + " ⇒ 归因不可查（应补进 WritePolicyMatrix.PREFIX_RULES；见 docs/authz/POLICY_MATRIX.csv）",
                    base, where);
        }
    }

    private static void noteUndeclared(Row row, WriteReason reason) {
        String key = row.id() + ":" + reason.name();
        UNDECLARED_SEEN.merge(key, 1, Integer::sum);
        if (!UNDECLARED_LOGGED.contains(key)) {
            UNDECLARED_LOGGED.add(key);
            BotLog.warn("[WritePolicy] undeclared_reason row={} zone={} task={} reason={}"
                            + " ⇒ 表与该调用点不一致（补行或改调用点）",
                    row.id(), row.zone(), row.task(), reason);
        }
    }

    /** 未登记 requester 的观察计数（自检断言 + `/alice authz` 显示）。 */
    public static Map<String, Integer> unregisteredSeen() {
        return Map.copyOf(UNREGISTERED_SEEN);
    }

    /** 未登记 (行, 理由) 组合的观察计数。 */
    public static Map<String, Integer> undeclaredSeen() {
        return Map.copyOf(UNDECLARED_SEEN);
    }

    /** 自检/夹具用：清空留痕计数（不动表）。 */
    public static void resetObservations() {
        UNREGISTERED_SEEN.clear();
        UNDECLARED_SEEN.clear();
        UNREGISTERED_LOGGED.clear();
        UNDECLARED_LOGGED.clear();
        CONTAINER_CHECKS = 0;
        CONTAINER_REFUSED = 0;
    }

    // ==================== 审计（覆盖检查）====================

    /**
     * 覆盖审计：返回**人类可读的问题清单**（空 = 表是完整的）。
     *
     * <p>与 `docs/authz/AUTHZ_REGISTRY.csv` 同一套思路：表是唯一出处，
     * "有没有漏 / 有没有孤儿"必须**可执行地**回答，而不是靠 review 眼睛看。
     */
    public static List<String> audit() {
        List<String> problems = new ArrayList<>();

        // 1) Zone × Task 全枚举
        for (Zone zone : Zone.values()) {
            for (Task task : Task.values()) {
                int count = 0;
                for (Row candidate : ROWS) {
                    if (candidate.zone() == zone && candidate.task() == task) {
                        count++;
                    }
                }
                if (count != 1) {
                    problems.add("行数异常 " + zone + "×" + task + "=" + count + "（应为 1）");
                }
            }
        }

        // 2) 理由无孤儿（每个 WriteReason 至少被一行登记）
        for (WriteReason reason : WriteReason.values()) {
            boolean covered = false;
            for (Row candidate : ROWS) {
                if (candidate.reasons() != null && candidate.reasons().contains(reason)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                problems.add("孤儿理由 " + reason + "（没有任何行登记它）");
            }
        }

        // 3) 显式义务与理由不矛盾（口径：显式值必须与"理由自身的 temporary 结论"一致，
        //    否则"显式 KEEP"会悄悄把本该回收的东西放过）
        for (Row candidate : ROWS) {
            if (candidate.obligation() == Obligation.REASON_DEFAULT || candidate.reasons() == null) {
                continue;
            }
            for (WriteReason reason : candidate.reasons()) {
                if (reason.action() == WriteReason.Action.BREAK) {
                    continue;   // 只拆不建的理由不参与"放置回收义务"的一致性检查
                }
                boolean expectedTemp = reason.temporary();
                if (candidate.obligation() == Obligation.TEMP && !expectedTemp) {
                    problems.add(candidate.id() + " 显式 TEMP 但理由 " + reason + " 属永久类"
                            + "（更保守，允许，但请确认是有意的）");
                }
                if (candidate.obligation() == Obligation.KEEP && expectedTemp) {
                    problems.add(candidate.id() + " 显式 KEEP 但理由 " + reason + " 属临时类"
                            + "（会把该回收的放置放过 —— 必须改）");
                }
            }
        }

        // 4) 未登记观察（运行期留痕）
        if (!UNREGISTERED_SEEN.isEmpty()) {
            problems.add("未登记 requester 写入/规划：" + UNREGISTERED_SEEN);
        }
        if (!UNDECLARED_SEEN.isEmpty()) {
            problems.add("未登记 (行,理由) 组合：" + UNDECLARED_SEEN);
        }
        return problems;
    }

    /** EXTERNAL 与 WORKSPACE 的解析差异条数（今天必须为 0；不为 0 ⇒ 必须补决策记录）。 */
    public static int zoneDiffCount() {
        int diff = 0;
        for (Task task : Task.values()) {
            for (WriteReason reason : WriteReason.values()) {
                if (obligation(Zone.EXTERNAL, task, reason) != obligation(Zone.WORKSPACE, task, reason)) {
                    diff++;
                }
            }
        }
        return diff;
    }

    /** 一行摘要（命令/日志用）。 */
    public static String describe() {
        return "rows=" + ROWS.size() + " zones=" + Zone.values().length + " tasks=" + Task.values().length
                + " reasons=" + WriteReason.values().length + " grants=" + MovementGrant.values().length
                + " zoneDiff=" + zoneDiffCount()
                + " unregistered=" + UNREGISTERED_SEEN.size()
                + " undeclared=" + UNDECLARED_SEEN.size()
                + " containerGate=" + (containerGateArmed ? "armed" : "observe")
                + " containerChecks=" + CONTAINER_CHECKS
                + " containerRefused=" + CONTAINER_REFUSED;
    }
}
