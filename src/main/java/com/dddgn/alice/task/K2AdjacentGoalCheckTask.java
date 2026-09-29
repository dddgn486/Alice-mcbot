package com.dddgn.alice.task;

import com.dddgn.alice.action.BlockInteraction;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.job.JobWriteDeclaration;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.GoalAdjacent;
import com.dddgn.alice.pathing.core.search.GoalColumnBlocks;
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
 * ⭐⭐ **goal 形状夹具**（`K2` 第一刀 ＋ `1a`=甲 `D-517` ⇒ `1-1b₃` 扩成三种形状，2026-09-29）。
 *
 * <p>⚠️ **名字仍是 `K2Adjacent…`**（`1-1b₃` 起它管的已经不止"相邻"）：改名会动**名字锚定的门禁**
 * （电池步名 `adjacent_goal_exclusion`、`PathingModule` 的注册、多处文档引用）而**不增加正确性**
 * （`1-0b` 对 `GoalAdjacent` 的同一条理由）；撤销触发 = 用户点名要改（改名 + 改步名 + 改引用**同刀**）。
 *
 * <h2>它钉的三组（⛔ 三组都在**规划层**，一次右键跑完）</h2>
 * <ol>
 *   <li><b>A · 侧面形状（`GoalAdjacent`）＋"换脚格"排除集</b>（`K2` 甲 的验证列逐字）：
 *       能搜到路（到达脚位真的满足目标）· 排除集生效（换一个到达脚位）· `exactFoot()==false` 的**取值**
 *       （⚠️ "已登记"由 `tools/check-far-goal-usage.py` 断：本夹具断取值、门禁断登记表）；</li>
 *   <li><b>B · 同列形状（`GoalColumnBlocks`，`1-0a` 落地／`1-1b₂` 起是腿 1）</b>：
 *       深度由**触及几何**推导 · 整根到达柱逐层真值 · **目标格自身算到达**（与 A **取值相反**，
 *       这是两个形状的唯一实质分歧）· `exactFoot()==true` · **接得上真规划器**
 *       （走 `PathRequest.miningApproach(… GoalSpec …)` 那个形状自由重载）；</li>
 *   <li><b>C · 侧面兜底（洞壁凸出）</b>：柱内**全部不可破坏**（基岩，前提自断言 `estimateBreakTicks == +∞`）
 *       ⇒ **同列腿必须 `UNREACHABLE`**、而**侧面腿必须 `REACHED`**，且它的落点
 *       **不在同列到达集里** ⇒ ⭐「**腿 2 不是死代码**」的直接证据。</li>
 * </ol>
 *
 * <h2>⚠️ `§6.9.1` 三问（自己答，别在心里假设）</h2>
 * <ol>
 *   <li><b>测的是哪一层</b>：⭐ **规划层**（`CorePathPlanner.plan` 的到达判定 + 边生成）。
 *       ⛔ **不测执行层** —— 不 tick 任何移动、不调 `assign*`/`beginTask`
 *       ⇒ **不替换正在跑的电池步**（`D-254` 的坑）、**零副作用**。</li>
 *   <li><b>依赖的世界/模组/几何假设</b>：⛔ **不依赖世界地形** —— 本夹具**自建**三个孤立场景：
 *       `4000`（A · 侧面形状）· `4100 / dz 2600`（B · 同列，目标下面做实心石柱）·
 *       `4100 / dz 2620`（C · 兜底，基岩柱）。其它夹具用 3600/3700/3800/3900 ⇒ **不撞段**。
 *       ⚠️ **前提全部自断言**（脚位 == 场景原点 · 脚下有支撑 · 柱内不可破坏 · 侧面有现成可站格），
 *       不满足 ⇒ 判红（**不静默**）。⭐ 新场景一律走「**先传送 → 等区块加载 → 再 `setBlock`**」
 *       （未加载区块里 `setBlock` 会**静默 0 改动**，`D-244`）。</li>
 *   <li><b>失败时用户看到什么 / 判据在哪一行</b>：聊天 + `[K2Adjacent] SUMMARY …→ FAIL`，
 *       逐条 `✗` 在紧随其后的 warn 行。键：`plan1/plan2/plan3/attempts/bound/arrivalChanged/exclusionScoped/exactFoot`
 *       ＋ `reachK/columnPlan/columnArrival/fbColumn/fbSide/fbArrival/fbOutsideColumn/fbBedrock`
 *       ＋ `snapshots/remaining/ms`。</li>
 * </ol>
 *
 * <h2>⚠️ `§6.9.2` 副作用边界</h2>
 * 不动父任务（纯规划）· 不 tick 终态任务 · ⭐ **失败路径也清理**（`DONE` 相位无条件 `cleanup`），
 * 判据里含 `remaining=0`（`touched` 必须被全部还原）。
 *
 * <h2>⚠️ 它**不**证明什么（诚实边界）</h2>
 * ⛔ 不证明"执行器能真的走到那个脚位"（那要客户端/执行层）；⛔ 不证明 `planGoalApproach` **内部**的
 * 腿序（那是私有编排 ⇒ 顺序的证据在 `MineMenuCheckTask` 的 A2：一次规划调用发起的搜索次数上界）；
 * ⛔ C 组的"目标"**刻意**是基岩 ⇒ 不证明"基岩可挖"，只证明「柱不可进入时侧面兜底有解」。
 */
public final class K2AdjacentGoalCheckTask implements Task {

    /** 孤立段原点（其它夹具：3600 / 3700 / 3800 / 3900 ⇒ 本夹具用 4000，**不撞段**）。 */
    private static final BlockPos ORIGIN = new BlockPos(4000, 100, 2600);
    /** 平台（脚下那层）的 X 跨度：`-1 .. FLOOR_MAX_X`。 */
    private static final int FLOOR_MAX_X = 5;
    /** 目标方块相对原点的偏移：`+X 3`、与脚位同层 ⇒ 到达集里有**近**（+2）与**绕过去**（±Z 侧、+4）两类脚位。 */
    private static final int TARGET_DX = 3;
    private static final int BUDGET_TICKS = 240;

    /*
     * ⭐ `1-1b₃`（2026-09-29）：本夹具从"**侧面形状**夹具"扩成"**形状夹具**"（同列 ＋ 侧面 ＋ 兜底），
     * 两个**新增场景**都放在**同一个新段 = 4100**（`dz` 相差 20 格 ⇒ 互不干扰），
     * ⛔ **刻意不碰 4000 那个老场景** —— 它的 18 条既有断言的行为**一个字都不该变**（`D-254` 的教训）。
     * ⚠️ 每个新场景都走「**先传送 → 等区块加载 → 再 `setBlock` → 再等 → 才规划**」：
     * `setBlock` 在未加载区块里会**静默 0 改动**（`D-244`），而玩家传送会同步加载区块。
     */
    /** 同列成功场景的原点（段 4100 · `dz 2600`）。 */
    private static final BlockPos COL_ORIGIN = new BlockPos(4100, 100, 2600);
    /** 兜底场景的原点（段 4100 · `dz 2620`）。 */
    private static final BlockPos FB_ORIGIN = new BlockPos(4100, 100, 2620);
    /** 柱子往下做多深：比最深到达层再多 2 层（让"更深一层"那条断言也有实体支撑可言）。 */
    private static final int PILLAR_EXTRA = 2;

    private enum Phase { SETUP, PLAN, SETUP_COLUMN, PLAN_COLUMN, SETUP_FALLBACK, PLAN_FALLBACK, DONE }

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
    // ---- `1-1b₃` 新增：同列形状 / 兜底场景的读数（进 SUMMARY，便于真机与日志核对）----
    private int reachK = -1;
    private String columnPlan = "-";
    private String columnArrival = "-";
    private String fbColumn = "-";
    private String fbSide = "-";
    private String fbArrival = "-";
    private boolean fbSideOutsideColumn;
    private int fbBedrockCells = -1;

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
                    // ⭐ `1-1b₃`：段 4100 的两个新场景**先传送**（玩家 ticket 同步加载区块），
                    // 等到 `SETUP_*` 里区块真的加载好之后才 `setBlock`（`D-244`）。
                    bot.teleportTo(level, COL_ORIGIN.getX() + 0.5D, COL_ORIGIN.getY(),
                            COL_ORIGIN.getZ() + 0.5D, -90.0F, 0.0F);
                    phase = Phase.SETUP_COLUMN;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case SETUP_COLUMN -> {
                if (phaseTicks == 4) {
                    buildColumnScene(level);
                }
                if (phaseTicks >= 8) {
                    phase = Phase.PLAN_COLUMN;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case PLAN_COLUMN -> {
                if (phaseTicks >= 1) {
                    probeColumn(level);
                    bot.teleportTo(level, FB_ORIGIN.getX() + 0.5D, FB_ORIGIN.getY(),
                            FB_ORIGIN.getZ() + 0.5D, -90.0F, 0.0F);
                    phase = Phase.SETUP_FALLBACK;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case SETUP_FALLBACK -> {
                if (phaseTicks == 4) {
                    buildFallbackScene(level);
                }
                if (phaseTicks >= 8) {
                    phase = Phase.PLAN_FALLBACK;
                    phaseTicks = 0;
                }
                return Task.Status.RUNNING;
            }
            case PLAN_FALLBACK -> {
                if (phaseTicks >= 1) {
                    probeFallback(level);
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
                                + " exactFoot={} reachK={} columnPlan={} columnArrival={}"
                                + " fbColumn={} fbSide={} fbArrival={} fbOutsideColumn={} fbBedrock={}"
                                + " snapshots={} remaining={} ms={} → {}",
                        checks, failures.size(), plan1, plan2, plan3, attempts, bound,
                        arrivalChanged, exclusionScoped, exactFoot, reachK, columnPlan, columnArrival,
                        fbColumn, fbSide, fbArrival, fbSideOutsideColumn, fbBedrockCells,
                        snapshots, remaining, msTotal,
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

    // ============ `1-1b₃` 新增场景（段 4100；⛔ 不碰 4000 那个老场景） ============

    /**
     * **同列成功场景**（段 4100 · `dz 2600`）：与老场景同形（孤立平台 ＋ `+X 3` 的目标），
     * ⭐ 差别 = 目标下面**做实心石柱**（一直做到最深到达层再往下 {@link #PILLAR_EXTRA} 层）。
     *
     * <p>**为什么必须做实心柱**：同列到达集是 `y .. y−depth`（`depth` 由触及推导，实参 `4.5` ⇒ **5**），
     * 每一格都要"**站进去**"才算到达。若柱内是空气（平台只有一层），最深那几层**没有地板**，
     * 规划器只能靠 `PILLAR`/`FALL` 之类去够 ⇒ 到达与否**依赖平台之外的世界地形**，断言会 flaky。
     * 做实心柱之后：**每一格都恰好需要 1 次破坏、且脚下都有支撑** ⇒ 走哪一层都对，与环境无关。
     */
    private void buildColumnScene(ServerLevel level) {
        int k = GoalColumnBlocks.maxDepthForReach(bot.getBlockReach());
        BlockPos target = COL_ORIGIN.offset(TARGET_DX, 0, 0);
        for (int dx = -1; dx <= FLOOR_MAX_X; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                place(level, COL_ORIGIN.offset(dx, -1, dz), Blocks.STONE);
            }
        }
        for (int dx = -1; dx <= FLOOR_MAX_X + 1; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    place(level, COL_ORIGIN.offset(dx, dy, dz), Blocks.AIR);
                }
            }
        }
        for (int d = 1; d <= k + PILLAR_EXTRA; d++) {
            place(level, target.below(d), Blocks.STONE);
        }
        place(level, target, Blocks.STONE);
        // ⭐ `1-4` 修复（2026-09-29，台账 `O47` ②）：**建完立刻再传送一次** —— 与 A 组 `buildScene` 的
        // 「铺完地板 ⇒ 传送」**逐字同形**（那两行就在本类上一个方法里）。
        // ⚠️ 根因（实测，非推测）：无头世界母本是**超平坦（地表 `y=-60`）**
        // 、且段 `4100` 的区块**尚未生成** ⇒ PLAN 阶段那次“先传送”只为加载区块（`D-244`），
        // bot 会从 `y=100` **自由下落**；4 tick 后本方法把地板铺在 `y=99`
        // —— 正好铺进 bot 身体里 ⇒ `hazard=SUFFOCATING` ⇒ 维生中断
        // ⇒ **整轮 `verdict=no_verdict`**（台账 `O47` ② 的日志证据）。
        bot.teleportTo(level, COL_ORIGIN.getX() + 0.5D, COL_ORIGIN.getY(), COL_ORIGIN.getZ() + 0.5D, -90.0F, 0.0F);
        BotLog.info("[K2Adjacent] SETUP_COLUMN origin={} target={} reachK={} pillarTo={}",
                COL_ORIGIN.toShortString(), target.toShortString(), k,
                target.below(k + PILLAR_EXTRA).toShortString());
    }

    /**
     * **兜底场景**（段 4100 · `dz 2620`）：目标是一根**基岩柱的顶**，而它**四个水平邻格**都是
     * 现成的可站格（空气 ＋ 石地板）。
     *
     * <p>**为什么这么摆**：同列到达集的每一格都必须"站进去"，而基岩 `getDestroySpeed < 0`
     * ⇒ `BlockInteraction.estimateBreakTicks` 返回 `+∞`（与 Baritone `getMiningDurationTicks:588-590`
     * 「流体不可挖 → 代价无穷」同一口径，也与 `BreakEnterHeadBlockedCheckTask` 的 `UNBREAKABLE` 用例同前提）
     * ⇒ 那些边**结构上不可能**被选中 ⇒ **同列腿无解**；而侧面到达集里有**零破坏**就能站的格 ⇒ **侧面腿有解**。
     * ⚠️ 本场景的"目标"**刻意**是基岩：⛔ 它**不**证明"基岩可挖"，只证明
     * 「**柱不可进入时，侧面兜底真的给得出方案**」。
     */
    private void buildFallbackScene(ServerLevel level) {
        int k = GoalColumnBlocks.maxDepthForReach(bot.getBlockReach());
        BlockPos target = FB_ORIGIN.offset(TARGET_DX, 0, 0);
        for (int dx = -1; dx <= FLOOR_MAX_X; dx++) {
            place(level, FB_ORIGIN.offset(dx, -1, 0), Blocks.STONE);
        }
        for (int dx = -1; dx <= FLOOR_MAX_X + 1; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    place(level, FB_ORIGIN.offset(dx, dy, dz), Blocks.AIR);
                }
            }
        }
        // 目标那 4 个水平邻格的**地板**（让它们"现成可站"）—— ⛔ 不含目标正下方那格（它属于基岩柱）。
        for (BlockPos foot : horizontalNeighbours(target)) {
            place(level, foot.below(), Blocks.STONE);
        }
        // 基岩柱：从目标层一直往下 PILLAR_EXTRA 层（放在最后 ⇒ 覆盖上面铺的那些地板）。
        for (int d = 0; d <= k + PILLAR_EXTRA; d++) {
            place(level, target.below(d), Blocks.BEDROCK);
        }
        // ⭐ `1-4` 修复（2026-09-29，台账 `O47` ②）：**建完立刻再传送一次** —— 与 A 组 `buildScene` 的
        // 「铺完地板 ⇒ 传送」**逐字同形**（那两行就在本类上一个方法里）。
        // ⚠️ 根因（实测，非推测）：无头世界母本是**超平坦（地表 `y=-60`）**
        // 、且段 `4100` 的区块**尚未生成** ⇒ PLAN 阶段那次“先传送”只为加载区块（`D-244`），
        // bot 会从 `y=100` **自由下落**；4 tick 后本方法把地板铺在 `y=99`
        // —— 正好铺进 bot 身体里 ⇒ `hazard=SUFFOCATING` ⇒ 维生中断
        // ⇒ **整轮 `verdict=no_verdict`**（台账 `O47` ② 的日志证据）。
        bot.teleportTo(level, FB_ORIGIN.getX() + 0.5D, FB_ORIGIN.getY(), FB_ORIGIN.getZ() + 0.5D, -90.0F, 0.0F);
        BotLog.info("[K2Adjacent] SETUP_FALLBACK origin={} target={} reachK={} bedrockCells={}",
                FB_ORIGIN.toShortString(), target.toShortString(), k, k + PILLAR_EXTRA + 1);
    }

    /** 目标的 4 个水平邻格（与 `GoalAdjacent` 到达集里那 4 格**同形**）。 */
    private static List<BlockPos> horizontalNeighbours(BlockPos target) {
        return List.of(target.offset(1, 0, 0), target.offset(-1, 0, 0),
                target.offset(0, 0, 1), target.offset(0, 0, -1));
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

    // ============ `1-1b₃` 断言组：同列形状（`GoalColumnBlocks`） ============

    /**
     * **同列形状**（`1-0a` 落地、`1-1b₂` 起是目标腿的**腿 1**）的判据组：
     * 到达判据真值 ＋ 与侧面形状的差异 ＋ **接得上真规划器**（走 `1-1b₁` 那个形状自由重载）。
     */
    private void probeColumn(ServerLevel level) {
        long t0 = System.nanoTime();
        BlockPos start = MovementHelper.footCell(level, bot).immutable();
        BlockPos target = COL_ORIGIN.offset(TARGET_DX, 0, 0);
        GoalColumnBlocks column = GoalColumnBlocks.forReach(target, bot.getBlockReach());
        reachK = column.depth();

        check("同列形状：前提 —— bot 脚位必须 == " + COL_ORIGIN.toShortString()
                        + "（实测 " + start.toShortString() + "）", start.equals(COL_ORIGIN));
        check("同列形状：深度由**触及几何**推导（`forReach(reach)` ⇒ depth=" + reachK
                        + "，必须 == `maxDepthForReach(getBlockReach())` 且 ≥ `MIN_DEPTH`="
                        + GoalColumnBlocks.MIN_DEPTH + "）",
                reachK >= GoalColumnBlocks.MIN_DEPTH
                        && reachK == GoalColumnBlocks.maxDepthForReach(bot.getBlockReach()));
        check("同列形状：到达层数 == depth + 1（实测 " + column.arrivalLevels() + "）",
                column.arrivalLevels() == reachK + 1);
        check("同列形状：目标格**自身**算到达（`isInGoal(target)=true`）—— ⚠️ 与侧面形状**正好相反**"
                        + "（`GoalAdjacent` 第一条就排除目标格）⇒ 这是两个形状的**唯一实质分歧**，"
                        + "也是 Baritone `GoalTwoBlocks` 的语义「破坏并站进去」",
                column.isInGoal(target));
        check("同列形状：目标**正上方**不算到达（`isInGoal(target.above())=false`）",
                !column.isInGoal(target.above()));
        boolean wholeColumn = true;
        for (int k = 0; k <= reachK; k++) {
            wholeColumn &= column.isInGoal(target.below(k));
        }
        check("同列形状：整根到达柱 `y .. y−" + reachK + "`（**含目标层**）逐层 `isInGoal` 必须全 `true`",
                wholeColumn);
        check("同列形状：再深一层（`y−" + (reachK + 1) + "`）必须**不**算到达（深度上界真的生效）",
                !column.isInGoal(target.below(reachK + 1)));
        check("同列形状：**同层水平邻格**不算到达（`isInGoal(target.offset(1,0,0))=false`）"
                        + " —— ⭐ 横向那一圈**归侧面形状**，这就是「两条腿互补」的几何依据",
                !column.isInGoal(target.offset(1, 0, 0)));
        check("同列形状：`exactFoot()` 必须 == `true`（实测 " + column.exactFoot() + "）—— 候选脚位与目标"
                        + "**共享 XZ** ⇒ 同一区块 ⇒ 一次预检覆盖整个到达集（所以它**不进** "
                        + "`check-far-goal-usage.py` 登记表；⚠️ 与侧面形状那条断言的**取值相反**）",
                column.exactFoot());

        PathPlan pc = plan(level, start, PathRequest.miningApproach(
                bot.getUUID().toString(), start, column, "adjacent-goal-check"));
        columnPlan = String.valueOf(pc.status());
        BlockPos ac = arrivalFoot(pc);
        columnArrival = ac.toShortString();
        check("同列形状：必须接得上真规划器（走 `PathRequest.miningApproach(… GoalSpec …)` 那个"
                        + "**形状自由重载**；status 必须 == REACHED，实测 " + columnPlan + "）",
                pc.status() == PlanningStatus.REACHED);
        check("同列形状：到达脚位必须**真的**满足目标（`isInGoal(arrival)`，arrival=" + columnArrival + "）",
                column.isInGoal(ac));
        check("同列形状：到达脚位不许是起点（真的走了一步以上，实测 movements=" + pc.movements().size() + "）",
                pc.status() != PlanningStatus.REACHED || !ac.equals(start));
        // ⭐ **如实记一条会进日志的行为读数**（⛔ 不作断言：它取决于代价，不是不变量）：
        // 同列到达集**含目标格自身**（Baritone `GoalTwoBlocks` 的语义）⇒ 只要目标**可破坏**，
        // 规划就常会选「**破进目标格**」那一格 ⇒ `arrival == target`。⚠️ 这不是危险形状
        // （`action/MineBlockRunner.tick()` 开头有「目标已空 ⇒ `DONE`」，`:113-117`），
        // 但它**是**本刀的行为差异面 ⇒ 真机核对的第一个读数就是这一行。
        if (ac.equals(target)) {
            BotLog.info("[K2Adjacent] 同列形状：落点 == **目标格自身**（破进目标格）⇒ 真机请核对"
                    + "「approach 期就把目标挖掉 ⇒ runner 直接 DONE」这条读数（台账 `O39`）");
        }
        msTotal += (System.nanoTime() - t0) / 1_000_000L;
    }

    // ============ `1-1b₃` 断言组：侧面兜底（洞壁凸出） ============

    /**
     * **兜底场景**：证明「**同列腿给不出方案时，侧面腿给得出、而且落在同列腿永远不接受的那一格上**」。
     *
     * <p>⛔ <b>它不证明什么</b>：不断言 `planGoalApproach` 内部的**先后顺序**（那是私有编排，
     * 本夹具只到规划层；顺序的证据在 `MineMenuCheckTask` 的 A2 = 一次规划调用发起的搜索次数上界）。
     */
    private void probeFallback(ServerLevel level) {
        long t0 = System.nanoTime();
        BlockPos start = MovementHelper.footCell(level, bot).immutable();
        BlockPos target = FB_ORIGIN.offset(TARGET_DX, 0, 0);
        GoalColumnBlocks column = GoalColumnBlocks.forReach(target, bot.getBlockReach());
        GoalAdjacent side = GoalAdjacent.of(target);

        check("兜底：前提 —— bot 脚位必须 == " + FB_ORIGIN.toShortString()
                        + "（实测 " + start.toShortString() + "）", start.equals(FB_ORIGIN));
        // ⭐ 场景的承重前提：柱内每一格都**不可破坏** ⇒ 每一格都站不进去。
        // 不满足 ⇒ 场景没摆好，此时**如实判红**比"断言一条假结论"诚实（同 `§6.9.1` 的前提自断言纪律）。
        int bedrock = 0;
        boolean allInfinite = true;
        for (int k = 0; k <= column.depth() + PILLAR_EXTRA; k++) {
            bedrock++;
            allInfinite &= Double.isInfinite(
                    BlockInteraction.estimateBreakTicks(bot, level, target.below(k)));
        }
        fbBedrockCells = bedrock;
        check("兜底：前提 —— 柱内 " + bedrock + " 格**全部不可破坏**（`estimateBreakTicks == +∞`；"
                        + "与 Baritone `MovementHelper.getMiningDurationTicks:588-590` 的"
                        + "「流体不可挖 → 代价无穷」同一口径）⇒ 柱内哪一格都**站不进去**", allInfinite);
        boolean sideStandable = false;
        for (BlockPos foot : horizontalNeighbours(target)) {
            sideStandable |= level.getBlockState(foot).isAir()
                    && !level.getBlockState(foot.below()).isAir();
        }
        check("兜底：前提 —— 至少 1 个**水平邻格**是现成的可站格（空气 ＋ 脚下实心）", sideStandable);

        PathPlan pc = plan(level, start, PathRequest.miningApproach(
                bot.getUUID().toString(), start, column, "adjacent-goal-check"));
        fbColumn = String.valueOf(pc.status());
        check("兜底：**同列腿必须给不出方案** ⇒ 必须 `UNREACHABLE`（实测 " + fbColumn
                        + "；⚠️ 若这里是 `SEARCH_LIMIT`/`PARTIAL`，那是**没评价完**、不是不可达 —— "
                        + "本夹具按红线口径 `SEARCH_LIMIT ≠ UNREACHABLE` **如实判红**）",
                pc.status() == PlanningStatus.UNREACHABLE);

        PathPlan ps = plan(level, start, PathRequest.adjacentApproach(
                bot.getUUID().toString(), start, target, null, "adjacent-goal-check"));
        fbSide = String.valueOf(ps.status());
        BlockPos af = arrivalFoot(ps);
        fbArrival = af.toShortString();
        check("兜底：**侧面腿必须有方案**（水平邻格是现成的 ⇒ 必须 `REACHED`，实测 " + fbSide + "）",
                ps.status() == PlanningStatus.REACHED);
        check("兜底：侧面腿的到达脚位必须真的满足**它自己的**目标（`GoalAdjacent.isInGoal(arrival)`）",
                side.isInGoal(af));
        fbSideOutsideColumn = !column.isInGoal(af);
        check("⭐ 兜底：侧面腿给出的落点**不在同列到达集里**（`!GoalColumnBlocks.isInGoal(arrival)`，"
                        + "arrival=" + fbArrival + "）⇒ **腿 2 不是死代码**：它给的正是腿 1 永远不接受的那一格",
                fbSideOutsideColumn);
        msTotal += (System.nanoTime() - t0) / 1_000_000L;
    }

    /** 跑一次**真规划**（给定请求）—— `1-1b₃` 起既有"工厂 + 精确脚位"的请求，也有"工厂 + 形状"的重载请求。 */
    private PathPlan plan(ServerLevel level, BlockPos start, PathRequest request) {
        PathPlan plan = new CorePathPlanner().plan(bot, level, request);
        BotLog.info("[K2Adjacent] plan goal={} start={} status={} movements={} nodes={} ms={} arrival={}",
                request.goal().describe(), start.toShortString(), plan.status(), plan.movements().size(),
                plan.nodesExpanded(), plan.elapsedMillis(), arrivalFoot(plan).toShortString());
        return plan;
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
