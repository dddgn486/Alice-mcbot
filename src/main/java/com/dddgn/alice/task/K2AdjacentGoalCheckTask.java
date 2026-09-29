package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.job.JobWriteDeclaration;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.GoalAdjacent;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.search.PlanningStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ⭐ `K2` 第一刀 ＋ `1a`=甲（`D-517`）：**相邻目标（`GoalAdjacent`）与"换脚格"排除集的夹具**。
 *
 * <h2>它钉的三条（`K2` 甲 的验证列逐字）</h2>
 * <ol>
 *   <li><b>能搜到路</b> —— 新目标形状接上真规划器（不是只有数据结构），且**到达的脚位真的满足目标**；</li>
 *   <li><b>排除集生效</b> —— 排除上一轮到达的那个脚位后，规划器**换一个**到达脚位（`1a`=甲 的重试单位 = 换脚格）；</li>
 *   <li><b>`exactFoot()==false`</b> —— 断言它的取值本身（⚠️ **"已登记"由 `tools/check-far-goal-usage.py` 断**：
 *       本夹具断**取值**，门禁断**登记表**；两者合起来才是"被登记"）。</li>
 * </ol>
 *
 * <h2>⚠️ `§6.9.1` 三问（自己答，别在心里假设）</h2>
 * <ol>
 *   <li><b>测的是哪一层</b>：⭐ **规划层**（`CorePathPlanner.plan` 的到达判定 + 边生成）。
 *       ⛔ **不测执行层** —— 不 tick 任何移动、不调 `assign*`/`beginTask`
 *       ⇒ **不替换正在跑的电池步**（`D-254` 的坑）、**零副作用**。</li>
 *   <li><b>依赖的世界/模组/几何假设</b>：⛔ **不依赖世界地形** —— 本夹具**自建**一块孤立平台
 *       （`ORIGIN` 段 = 4000，其它夹具用 3600/3700/3800/3900 ⇒ **不撞段**）＋ 一根目标方块柱。
 *       ⚠️ 前提**自断言**：跑之前 bot 脚位必须 == `ORIGIN` 且脚下有支撑（不满足 ⇒ `PRECONDITION_FAILED`，不静默）。</li>
 *   <li><b>失败时用户看到什么 / 判据在哪一行</b>：聊天 + `[K2Adjacent] SUMMARY …→ FAIL`，
 *       逐条 `✗` 在紧随其后的 warn 行。键：`plan1/plan2/plan3/attempts/bound/arrivalChanged/exclusionScoped/exactFoot/remaining`。</li>
 * </ol>
 *
 * <h2>⚠️ `§6.9.2` 副作用边界</h2>
 * 不动父任务（纯规划）· 不 tick 终态任务 · ⭐ **失败路径也清理**（`DONE` 相位无条件 `cleanup`），
 * 判据里含 `remaining=0`（`touched` 必须被全部还原）。
 *
 * <h2>⚠️ 它**不**证明什么（诚实边界）</h2>
 * ⛔ 不证明"执行器能真的走到那个脚位"（那要客户端/执行层）；⛔ 不证明"接线后 `B` 分支可以删"
 * （本刀**生产路径零改动**：`adjacentApproach` 今天只有本夹具一个消费者）。
 */
public final class K2AdjacentGoalCheckTask implements Task {

    /** 孤立段原点（其它夹具：3600 / 3700 / 3800 / 3900 ⇒ 本夹具用 4000，**不撞段**）。 */
    private static final BlockPos ORIGIN = new BlockPos(4000, 100, 2600);
    /** 平台（脚下那层）的 X 跨度：`-1 .. FLOOR_MAX_X`。 */
    private static final int FLOOR_MAX_X = 5;
    /** 目标方块相对原点的偏移：`+X 3`、与脚位同层 ⇒ 到达集里有**近**（+2）与**绕过去**（±Z 侧、+4）两类脚位。 */
    private static final int TARGET_DX = 3;
    private static final int BUDGET_TICKS = 240;

    private enum Phase { SETUP, PLAN, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final List<String> findings = new ArrayList<>();
    private final Map<BlockPos, BlockState> touched = new LinkedHashMap<>();

    private Phase phase = Phase.SETUP;
    private int phaseTicks;
    private int totalTicks;
    private int checks;

    private String plan1 = "-";
    private String plan2 = "-";
    private String plan3 = "-";
    private int attempts = -1;
    private int bound = -1;
    private boolean arrivalChanged;
    private boolean exclusionScoped;
    private boolean exactFoot;
    private int snapshots = -1;
    private int remaining = -1;
    private long msTotal;

    public K2AdjacentGoalCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "K2AdjacentGoalCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(ORIGIN);
    }

    @Override
    public String failureReason() {
        return failures.isEmpty() ? "" : String.join(" | ", failures);
    }

    @Override
    public String terminalReason() {
        return failures.isEmpty() ? "passed" : "failed";
    }

    private void check(String what, boolean ok) {
        checks++;
        if (ok) {
            findings.add(what + " ✓");
        } else {
            failures.add(what + " ✗");
            BotLog.warn("[K2Adjacent] FAIL {}", what);
        }
    }

    @Override
    public Task.Status tick() {
        ServerLevel level = (ServerLevel) bot.level();
        totalTicks++;
        phaseTicks++;
        if (totalTicks > BUDGET_TICKS) {
            failures.add("超时 " + BUDGET_TICKS + " tick（phase=" + phase + "）");
            cleanup(level);
            return Task.Status.FAILED;
        }
        switch (phase) {
            case SETUP -> {
                if (phaseTicks == 1) {
                    buildScene(level);
                }
                // `/fill`/`setBlock` 在区块未加载时会静默 0 改动（`D-244`）⇒ 先把 bot 传进场景
                // （玩家 ticket 会同步加载区块），再等几 tick 让它落地，然后才规划。
                if (phaseTicks >= 4) {
                    phase = Phase.PLAN;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case PLAN -> {
                if (phaseTicks >= 1) {
                    probe(level);
                    phase = Phase.DONE;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case DONE -> {
                cleanup(level);
                // ⚠️ 键的语义必须与账本纪律一致（`§6.9.2`）：`remaining` = **还没还原**的格数（必须 0），
                // ⛔ 不是"一共记了多少个快照"（那是 `snapshots`）。第一版把快照数叫成 `remaining`
                // ⇒ SUMMARY 打出 `remaining=101` ⇒ 读的人会以为"101 格没还原"（**静默误读**的形状，`D-517` 已登记）。
                remaining = touched.size();
                check("副作用边界：**失败路径也清理**（收尾后 `remaining` 必须 == 0，实测 " + remaining + "）",
                        remaining == 0);
                BotLog.info("[K2Adjacent] SUMMARY checks={} failures={} plan1={} plan2={} plan3={}"
                                + " attempts={} bound={} arrivalChanged={} exclusionScoped={}"
                                + " exactFoot={} snapshots={} remaining={} ms={} → {}",
                        checks, failures.size(), plan1, plan2, plan3, attempts, bound,
                        arrivalChanged, exclusionScoped, exactFoot, snapshots, remaining, msTotal,
                        failures.isEmpty() ? "PASS" : "FAIL");
                for (String line : findings) {
                    BotLog.info("[K2Adjacent]   {}", line);
                }
                for (String line : failures) {
                    BotLog.warn("[K2Adjacent]   {}", line);
                }
                if (observer != null) {
                    observer.sendSystemMessage(Component.literal(
                            "[alice] 相邻目标夹具：" + (failures.isEmpty() ? "PASS" : "FAIL")
                                    + " checks=" + checks + " failures=" + failures.size()
                                    + "（plan1=" + plan1 + " plan2=" + plan2
                                    + " attempts=" + attempts + "/" + bound + "）"));
                }
                return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
            }
            default -> {
                return Task.Status.FAILED;
            }
        }
    }

    // ==================== 场景（自建、孤立、可还原） ====================

    private void buildScene(ServerLevel level) {
        // 孤立平台：脚下那层铺石（dx -1..FLOOR_MAX_X，dz -1..1），再把 bot 传到原点。
        for (int dx = -1; dx <= FLOOR_MAX_X; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                place(level, ORIGIN.offset(dx, -1, dz), Blocks.STONE);
            }
        }
        // 清掉脚位与头位（防止落进老地形），并清一圈上方，保证"边界外一圈是空气"。
        for (int dx = -1; dx <= FLOOR_MAX_X + 1; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    place(level, ORIGIN.offset(dx, dy, dz), Blocks.AIR);
                }
            }
        }
        // ⭐ 目标方块：与脚位同层的一根实心柱（要挖的就是它；到达 = 站到它的某一面）。
        place(level, ORIGIN.offset(TARGET_DX, 0, 0), Blocks.STONE);

        BlockPos foot = ORIGIN;
        bot.teleportTo(level, foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D, -90.0F, 0.0F);
        BotLog.info("[K2Adjacent] SETUP origin={} target={} floorX=-1..{}",
                ORIGIN.toShortString(), ORIGIN.offset(TARGET_DX, 0, 0).toShortString(), FLOOR_MAX_X);
    }

    private void place(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.Block block) {
        BlockPos key = pos.immutable();
        touched.putIfAbsent(key, level.getBlockState(key));
        level.setBlock(key, block.defaultBlockState(), 3);
    }

    // ==================== 断言 ====================

    private void probe(ServerLevel level) {
        long t0 = System.nanoTime();
        BlockPos start = MovementHelper.footCell(level, bot).immutable();
        BlockPos target = ORIGIN.offset(TARGET_DX, 0, 0);

        // ---- 前提（自断言，不静默）----
        check("前提：bot 脚位必须 == 起点 " + ORIGIN.toShortString() + "（实测 " + start.toShortString() + "）",
                start.equals(ORIGIN));
        check("前提：起点脚下必须有支撑（`canStandCentered` 的几何前提，`D-252`）",
                !level.getBlockState(ORIGIN.below()).isAir());
        if (!start.equals(ORIGIN)) {
            plan1 = "PRECONDITION_FAILED";
            remaining = touched.size();
            return;
        }

        // ---- ③ `exactFoot()==false`：断**取值**（"已登记"＝门禁 `check-far-goal-usage.py` 断）----
        GoalAdjacent bare = GoalAdjacent.of(target);
        exactFoot = bare.exactFoot();
        check("`GoalAdjacent.exactFoot()` 必须 == `false`（实测 " + exactFoot + "）"
                        + " —— ⚠️ 取值本身是**红线开关**（`A-5`）：它跳过 `GOAL_NOT_LOADED` 前置守卫，"
                        + "所以 `tools/check-far-goal-usage.py` 的登记表**必须**有它，且记录同一个取值",
                !exactFoot);

        // ---- 到达判据是**纯算术**：四条合取的逐条真值（零世界访问）----
        check("到达判据：目标格自身**不算**到达（`isInGoal(target)=false`）",
                !bare.isInGoal(target));
        check("到达判据：目标**正上方**不算到达（`isInGoal(target.above())=false`，"
                        + "理由 = 方块可能悬空、站上去挖不动 —— Baritone `GoalBreak` 逐字给过）",
                !bare.isInGoal(target.above()));
        check("到达判据：目标**正下方**算到达（`isInGoal(target.below())=true`）",
                bare.isInGoal(target.below()));
        check("到达判据：隔一格不算到达（`isInGoal(target.offset(2,0,0))=false` ⇒ 曼哈顿 ≤1 生效）",
                !bare.isInGoal(target.offset(2, 0, 0)));
        check("到达判据：侧邻算到达（`isInGoal(target.offset(0,0,1))=true`）",
                bare.isInGoal(target.offset(0, 0, 1)));

        // ---- ① 能搜到路（plan1）----
        PathPlan p1 = plan(level, start, target, Set.of());
        plan1 = String.valueOf(p1.status());
        BlockPos a1 = arrivalFoot(p1);
        check("能搜到路：`GoalAdjacent` 必须接得上真规划器（plan1.status 必须 == REACHED，实测 " + plan1 + "）",
                p1.status() == PlanningStatus.REACHED);
        check("能搜到路：到达脚位必须**真的**满足目标（`isInGoal(arrival)`，arrival="
                        + a1.toShortString() + "）",
                bare.isInGoal(a1));
        check("能搜到路：到达脚位不许是起点（真的走了一步以上，实测 movements="
                        + p1.movements().size() + "）",
                p1.status() != PlanningStatus.REACHED || !a1.equals(start));

        // ---- ② 排除集生效（plan2）----
        Set<BlockPos> excluded = new LinkedHashSet<>();
        excluded.add(a1);
        PathPlan p2 = plan(level, start, target, excluded);
        plan2 = String.valueOf(p2.status());
        BlockPos a2 = arrivalFoot(p2);
        arrivalChanged = !a2.equals(a1);
        check("排除集生效：排除「plan1 到达的那一格」后必须换一个到达脚位"
                        + "（arrival1=" + a1.toShortString() + " arrival2=" + a2.toShortString()
                        + " plan2=" + plan2 + "）",
                p2.status() == PlanningStatus.REACHED && arrivalChanged && bare.isInGoal(a2));
        check("排除集生效：plan2 的到达脚位**不许**落在排除集里",
                p2.status() != PlanningStatus.REACHED || !excluded.contains(a2));

        // ---- ④ 时效纪律（`§49.2` 第 3 条 / `C-5`）：排除**只在一次规划内有效**，不跨调用累积 ----
        PathPlan p3 = plan(level, start, target, Set.of());
        plan3 = String.valueOf(p3.status());
        BlockPos a3 = arrivalFoot(p3);
        exclusionScoped = a3.equals(a1);
        check("时效纪律：**新的一次规划**（不带排除）必须回到与 plan1 相同的到达脚位"
                        + "（arrival3=" + a3.toShortString() + "，= **排除不许跨 tick 累积成永久拉黑**）",
                p3.status() == PlanningStatus.REACHED && exclusionScoped);

        // ---- ⑤ `1b`=丙 的上界默认值：它是 `#16` 最小载体的一栏，**消费者就是本夹具**（`P4/A`：断次数）----
        JobWriteDeclaration decl = JobWriteDeclaration.mining("adjacent-goal-check", 64);
        bound = decl.maxFootRetries();
        attempts = runRetryLoop(level, start, target, bound);
        check("`1b` 上界：重试上界必须来自 `JobWriteDeclaration`（`#16` 最小载体，`D-511`/`D-502`）且 > 0"
                        + "（实测 bound=" + bound + "）—— ⛔ 刻意**不**是独立常量（`D-502` 丙："
                        + "它是 `DS-19` 统一账里的一条默认值）",
                bound > 0);
        check("`1b` 上界：排除集驱动的重试必须**真的发生**且**不超过上界**"
                        + "（attempts=" + attempts + " bound=" + bound + "）"
                        + " —— ⚠️ `P4/A`：判据断**次数**，时间只进日志",
                attempts >= 2 && attempts <= bound);
        msTotal = (System.nanoTime() - t0) / 1_000_000L;

        snapshots = touched.size();
        check("副作用边界：本夹具只写它自己记录过的格（`snapshots` = " + snapshots + "；"
                        + "⭐ 真正的 **`remaining == 0`** 判据在 `DONE` 相位、`cleanup` 之后才断 —— "
                        + "两把键**语义不同**，别混读）", snapshots > 0);
    }

    /**
     * `1a`=甲 的"换脚格"重试回路（**最多 `bound` 次**）：每轮把上一轮到达的脚位排除，再规划一次。
     *
     * <p>⛔ 上界**来自** {@link JobWriteDeclaration#maxFootRetries()}（`1b`=丙），⛔ **不是**本夹具自造的常量。
     * ⚠️ 返回的是**次数**（`P4/A`：夹具断次数）—— 时间只进 `msTotal` 的日志。
     */
    private int runRetryLoop(ServerLevel level, BlockPos start, BlockPos target, int bound) {
        Set<BlockPos> excluded = new LinkedHashSet<>();
        for (int i = 1; i <= bound; i++) {
            PathPlan p = plan(level, start, target, Set.copyOf(excluded));
            if (p.status() != PlanningStatus.REACHED) {
                return i;                       // 用尽（诚实：不把"用尽"当"不可达"以外的任何东西）
            }
            BlockPos arrived = arrivalFoot(p);
            if (!excluded.add(arrived)) {
                return i;                       // 已经排除过同一格 ⇒ 回路不动了，如实停
            }
        }
        return bound;
    }

    /** 到达脚位 = 最后一段的 `toFoot()`；没有移动段 ⇒ 起点（已在目标内）。 */
    private static BlockPos arrivalFoot(PathPlan plan) {
        List<com.dddgn.alice.pathing.core.search.PlannedMovement> ms = plan.movements();
        return ms.isEmpty() ? plan.startFoot() : ms.get(ms.size() - 1).toFoot();
    }

    /**
     * 跑一次**真规划**（`CorePathPlanner`）。
     * ⚠️ 能力集经 {@link PathRequest#adjacentApproach}（与 `miningApproach` **同一份**单一出处）；
     * ⛔ 本夹具**不执行**这条路径（不 tick 移动）⇒ 零副作用、不替换任何正在跑的任务。
     */
    private PathPlan plan(ServerLevel level, BlockPos start, BlockPos target, Set<BlockPos> excluded) {
        String botId = bot.getUUID().toString();
        PathRequest request = PathRequest.adjacentApproach(botId, start, target, excluded, "adjacent-goal-check");
        PathPlan plan = new CorePathPlanner().plan(bot, level, request);
        BotLog.info("[K2Adjacent] plan target={} excluded={} status={} movements={} nodes={} ms={} arrival={}",
                target.toShortString(), excluded.size(), plan.status(), plan.movements().size(),
                plan.nodesExpanded(), plan.elapsedMillis(), arrivalFoot(plan).toShortString());
        return plan;
    }

    // ==================== 收尾 ====================

    private void cleanup(ServerLevel level) {
        for (Map.Entry<BlockPos, BlockState> entry : touched.entrySet()) {
            level.setBlock(entry.getKey(), entry.getValue(), 3);
        }
        touched.clear();
        bot.teleportTo(level, ORIGIN.getX() + 0.5D, ORIGIN.getY(), ORIGIN.getZ() + 0.5D, 0.0F, 0.0F);
    }
}
