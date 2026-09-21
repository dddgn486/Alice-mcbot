package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.MovementType;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.MovementContext;
import com.dddgn.alice.pathing.core.search.MovementProvider;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.search.PlannedMovement;
import com.dddgn.alice.pathing.core.search.PlannedMovementSpecs;
import com.dddgn.alice.pathing.core.search.SearchBudget;
import com.dddgn.alice.pathing.core.search.SurfaceMovementProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ⭐⭐⭐ **`survey/27 §3 #1` 的收口判据**：电池步 `head_blocked_route_closure`（EXTRA，**规划级**）。
 *
 * <h2>要收口的那句话（勘测侧原文）</h2>
 * ```
 * 直挖路需要「破入边」（BREAK_AND_ENTER）
 *      ↓  一行谓词只查脚位、不查头位
 *      ↓  ⇒ 20% 的破入边没有生成（G2 差集实测：gaps=35）
 *      ↓  A* 找不到直挖路 ⇒ 只能绕 88 段
 *      ↓  88 段太贵 ⇒ 撞搜索预算 ⇒ SEARCH_LIMIT
 * ```
 * 前两段已证（`D-374` 的 `edge_completeness` 差集 + `break_enter_head_blocked` 距离 1 能力）。
 * **未证的是后两段**：缺口是否**真的**把搜索逼成"绕远 + 撞预算"。`P3` 复测因真机存档被覆盖而**读数无效**
 * （见 `docs/reviews/2026-09-21-B-深矿可达性判据实验.md` §P3：一次真实搜索都没发生，探针却输出
 * `reproduced=true`）⇒ 本夹具按勘测侧建议①②，改成**当场自建几何 + 同一请求跑两遍**。
 *
 * <h2>它钉住的是什么（一句话）</h2>
 * <b>同一条请求、同一份几何、同一个预算，只把「旧谓词会拒掉的那一类边」去掉，路径就从
 * {@value #EXPECTED_DIRECT_MOVEMENTS} 段变成 {@value #EXPECTED_DETOUR_MOVEMENTS} 段（或撞预算）。</b>
 *
 * <h2>场景（自己建、自己还原；无真机存档依赖）</h2>
 * 整块**基岩**盒子（不可破坏 ⇒ 隧道焊死，除"被破的夹缝格"外**没有任何别的路线差别**），内部挖出：
 * <pre>
 *   S(0,0) ──▶ (1,0) ──▶ [2,0] [3,0] [4,0] [5,0] [6,0] ──▶ (7,0)=目标
 *   起始腔室     普通格      ↑ 5 个「脚位空 + 头位实」的夹缝格（= 被测形状）      ↑ 脚位被挡
 *   └─▶ (0,-1) ─▶ … ─▶ (0,-20) ─▶ (1,-20) ─▶ … ─▶ (7,-20) ─▶ (7,-19) ─▶ … ─▶ (7,-1) ─┘
 *                    绕远通道：{@value #DETOUR_LEG}×2 + 7 格 ⇒ 进目标共 {@value #EXPECTED_DETOUR_MOVEMENTS} 段
 * </pre>
 * 三处几何设计**都是判据的一部分**（红了说明夹具坏，不是缺陷证据）：
 * <ol>
 *   <li><b>脚位层上下都焊死</b>（地板 y−1 = 基岩、天花板 y+2 = 基岩）⇒ 场景只有**一层**：
 *       `ASCEND` 需要"上方那格立得住"（其支撑是脚位层的空气）⇒ **全场景无一处可上升**；
 *       `PILLAR` 需要 `bodyPassable(from.above())` ⇒ 被天花板否；`DOWNWARD` 需要脚下那格**下方**
 *       有支撑 ⇒ 基岩下面就是虚空 ⇒ 否；`FALL` 需要落点有支撑 ⇒ 否；</li>
 *   <li><b>通道每一格都有地板</b> ⇒ `PLACE_STEP_AND_TRAVERSE` 的前提（`!canWalkOn(to)`）**处处不成立**
 *       ⇒ 放置类边一条都不会生成（**不依赖"bot 手里没有方块"这个假设**）；</li>
 *   <li><b>目标格脚位被挡（可破坏）</b>：`collectBlockers` 在**两遍里都**非空 ⇒ 目标**两遍都能进**，
 *       于是"绕远"这一段是量出来的，而不是"根本到不了"。若目标改成头位被挡，修复前**任何预算都到不了**
 *       （零入边）⇒ 就量不到绕远段数。</li>
 * </ol>
 *
 * <h2>三遍（同一场景、同一请求、只差<b>边集</b>）</h2>
 * <table border="1">
 *   <tr><th>档</th><th>provider</th><th>预算</th><th>期望</th></tr>
 *   <tr><td>{@code POST_PRODUCTION}</td><td>生产 {@link SurfaceMovementProvider}（= 修复后）</td>
 *       <td>20 000 / 200 ms（生产同款）</td>
 *       <td>**REACHED 且 ≤ {@value #EXPECTED_DIRECT_MOVEMENTS} 段**，其中
 *           {@value #SLIT_COUNT} 段是"旧谓词会拒掉的那一类"（脚位可通行 + 头位被挡）</td></tr>
 *   <tr><td>{@code PRE_LEGACY_AMPLE}</td><td>**旧谓词语义**（见 {@link LegacyHeadGateProvider}）</td>
 *       <td>同上</td><td>**REACHED 但 ≥ 40 段**（= 绕远被量出来）</td></tr>
 *   <tr><td>{@code PRE_LEGACY_TIGHT}</td><td>同上</td><td>{@code 40 节点 / 50 ms}</td>
 *       <td>**到不了**，且 `nodesExpanded` 打满 40（= 预算真的是绑定点 ⇒ 复现 `SEARCH_LIMIT` 的机制）</td></tr>
 * </table>
 *
 * <h2>旧谓词语义怎么来（不碰生产代码）</h2>
 * {@link CorePathPlanner#CorePathPlanner(MovementProvider)} 是公开构造器 ⇒ 反臂用**夹具自带的包装
 * provider**：委派生产生成器，然后**过滤掉"旧入口闸门会提前 return 的那一类边"**。
 * 等价性是可算的（不是"我觉得"）：修复后生成器**已**要求 `!bodyPassable(to)`，旧闸门要求 `!canWalkThrough(to)`
 * ⇒ 两者的差集恰好是 `canWalkThrough(to) && !canWalkThrough(to.above())` ⇒ 从修复后的边集里
 * **滤掉 `canWalkThrough(to)` 的 `BREAK_AND_ENTER`**，剩下的就是旧边集（逐字等价）。
 * ⚠️ 过滤计数 {@code dropped} **必须 > 0**，否则"没测到"会伪装成"测到了"（`silent-measurement-failure`，`P3` 的教训）
 * ⇒ 它进判据。
 *
 * <h2>它断言哪一层（技能 §6.9.1 ③）</h2>
 * 只断言**规划层**（`CorePathPlanner.plan` 的产出）—— 不 tick 子任务、不执行、不写世界
 * （除自建场景本身）。执行层由 `break_enter_head_blocked` / `pathing` / `break_enter` 类步覆盖。
 *
 * <h2>诚实边界（别拿它当"88 段已经解释完了"）</h2>
 * 它证的是**机制**（缺边 ⇒ 只剩绕远 ⇒ 绕远吃预算），不是"真机那次 88 段就是这条链算出来的"
 * —— 那需要一份**冻结的 round-4 世界副本**（真机存档必被下一轮覆盖，勘测侧建议①）。
 */
public final class HeadBlockedRouteClosureCheckTask implements Task {

    /** 场景基点 = **起始腔室脚位**（孤立点：离其它夹具场景 ≥100 格，且在 y=100 的虚空里）。 */
    private static final BlockPos ORIGIN = new BlockPos(3000, 100, 4400);

    /**
     * 收尾把 bot 停回**超平坦世界的地面**（`y=-60` 是各夹具共用的脚位层）。
     * 为什么不留在场景里：`restore()` 之后那里是虚空 ⇒ 会把 bot 掉进去（`EdgeCompleteness` 的遗留问题）。
     */
    private static final BlockPos PARK_FOOT = new BlockPos(3000, -60, 4400);

    /** 起始腔室正东 1 格：**普通通行格**（隔开"对角破入边"，见 §场景 与 {@code restor()} 之上的注释）。 */
    private static final int OPEN_A_X = 1;
    /** 夹缝格范围（脚位空 + 头位实）——被测的那个形状。 */
    private static final int SLIT_FIRST_X = 2;
    private static final int SLIT_LAST_X = 6;
    private static final int SLIT_COUNT = SLIT_LAST_X - SLIT_FIRST_X + 1;
    /** 目标格：**脚位被挡**（可破坏）⇒ 两遍都能进，差别只在路线。 */
    private static final int GOAL_X = 7;

    /** 绕远通道每条纵腿长度 K ⇒ 绕远段数 = 2K + 7。 */
    private static final int DETOUR_LEG = 20;

    // ---- 基岩盒（含外扩一圈）----
    private static final int BOX_X0 = -1;
    private static final int BOX_X1 = 8;
    private static final int BOX_Z0 = -DETOUR_LEG - 1;
    private static final int BOX_Z1 = 1;
    /** y−1 = 地板、y = 脚位、y+1 = 头位、y+2 = 天花板（⇒ 全场景只有一层）。 */
    private static final int BOX_Y0 = -1;
    private static final int BOX_Y1 = 2;

    private static final int SETTLE_TICKS = 30;
    private static final int BUDGET_TICKS = 600;

    /** 生产同款预算（与 `PathRequest.WALK_BUDGET` 同值：20 000 节点 / 200 ms）。 */
    private static final SearchBudget BUDGET_PRODUCTION = SearchBudget.of(20_000, 200L);

    /**
     * 「紧」预算：**按几何可证不足** —— 修复前唯一路线要 {@value #EXPECTED_DETOUR_MOVEMENTS} 段，
     * 而 A* 必须先弹出路径上的每一格 ⇒ 至少那么多次弹出 > 40 ⇒ 必然到不了。
     * 这一档复现的是真机那条链的**最后一段**（`SEARCH_LIMIT` = 预算耗尽、可达性未知）。
     */
    private static final SearchBudget BUDGET_TIGHT = SearchBudget.of(40, 50L);

    /** 修复后直挖路的理论段数：1 段普通通行 + {@value #SLIT_COUNT} 段破夹缝 + 1 段破进目标。 */
    private static final int EXPECTED_DIRECT_MOVEMENTS = SLIT_COUNT + 2;
    /** 通道里「直挖路那一段」的格数（起点 + 直挖路第一格 + 5 个夹缝格 + 目标）——其后全是绕远通道。 */
    private static final int DIRECT_CELLS = SLIT_COUNT + 3;
    /** 修复前唯一路线的理论段数（绕远通道全长 + 破进目标）。 */
    private static final int EXPECTED_DETOUR_MOVEMENTS = 2 * DETOUR_LEG + SLIT_COUNT + 2;

    /** 「绕远明显更长」的判据下界（不写死等号：规划器若有正当改进，缩短也是允许的）。 */
    private static final int DETOUR_MIN_MOVEMENTS = 40;

    /** 与兄弟夹具同口径：`collect-drops` = `WritePolicyMatrix` 的 P-03/GATHERING 行（含 `WITH_WORLD_MODIFICATION`）。 */
    private static final String REQUESTER = "collect-drops";

    private enum Arm { POST_PRODUCTION, PRE_LEGACY_AMPLE, PRE_LEGACY_TIGHT }

    /** 一遍的结果（读数 + 判据用的派生量）。 */
    private record Reading(String arm, PathPlan plan, int breakEnter, int headBlockedBreaks,
                           int outsideCorridor, int dropped) {
        boolean reached() {
            return plan.reached();
        }
    }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final List<String> findings = new ArrayList<>();

    private int ticks;
    private int checks;
    private int prepareStep;
    private int armIndex;
    private boolean done;
    private boolean built;
    private boolean restored = true;

    private BlockPos startFoot;
    private BlockPos goalFoot;
    private Set<BlockPos> corridor = Set.of();

    private final List<Reading> readings = new ArrayList<>();

    public HeadBlockedRouteClosureCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "HeadBlockedRouteClosureCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(ORIGIN);
    }

    @Override
    public String failureReason() {
        return String.join(" | ", failures);
    }

    @Override
    public String terminalReason() {
        return done ? (failures.isEmpty() ? "passed" : "failed") : "";
    }

    @Override
    public Task.Status tick() {
        if (done) {
            return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        }
        if (++ticks > BUDGET_TICKS) {
            check("规划级门禁必须在预算内跑完（" + BUDGET_TICKS + " tick）", false);
            return finish();
        }
        if (prepareStep == 0) {
            teleport(bot, ORIGIN);
            prepareStep = 1;
            return Task.Status.RUNNING;
        }
        if (prepareStep <= SETTLE_TICKS) {
            prepareStep++;
            return Task.Status.RUNNING;
        }
        if (prepareStep == SETTLE_TICKS + 1) {
            // 先传送再把场景建出来：区块随 bot 加载，`D-132` 的读脚印闸门才不会把搜索截断
            prepareStep++;
            buildAndAssert();
            return Task.Status.RUNNING;
        }
        if (armIndex >= Arm.values().length) {
            return finish();
        }
        // **每 tick 只跑一遍**（`A1` 每 tick 只放行一次烧预算的搜索；兄弟夹具同款）
        runArm(Arm.values()[armIndex++]);
        return Task.Status.RUNNING;
    }

    // ============================================================ 场景

    /** 通道格集合（脚位层；顺序固定 ⇒ 可复现）。 */
    private List<BlockPos> corridorCells() {
        List<BlockPos> cells = new ArrayList<>();
        cells.add(cell(0, 0));                                  // 起始腔室
        cells.add(cell(OPEN_A_X, 0));                           // 直挖路的第一格（普通通行）
        for (int x = SLIT_FIRST_X; x <= SLIT_LAST_X; x++) {
            cells.add(cell(x, 0));                              // 5 个夹缝格
        }
        cells.add(cell(GOAL_X, 0));                             // 目标
        for (int z = -1; z >= -DETOUR_LEG; z--) {
            cells.add(cell(0, z));                              // 绕远：纵腿 1
        }
        for (int x = 1; x <= GOAL_X; x++) {
            cells.add(cell(x, -DETOUR_LEG));                    // 绕远：横腿
        }
        for (int z = -DETOUR_LEG + 1; z <= -1; z++) {
            cells.add(cell(GOAL_X, z));                         // 绕远：纵腿 2
        }
        return cells;
    }

    private static BlockPos cell(int dx, int dz) {
        return ORIGIN.offset(dx, 0, dz);
    }

    private void buildAndAssert() {
        ServerLevel level = bot.serverLevel();
        List<BlockPos> cells = corridorCells();
        corridor = new HashSet<>(cells);
        startFoot = cell(0, 0);
        goalFoot = cell(GOAL_X, 0);

        // ---- 前提 ①：建之前这里是空的（否则说明别的场景/上一轮留了东西 ⇒ 本夹具不是孤立的）----
        check("前提：场景盒在动手前**全为空气**（x[" + BOX_X0 + "," + BOX_X1 + "] z[" + BOX_Z0 + ","
                + BOX_Z1 + "] y[" + BOX_Y0 + "," + BOX_Y1 + "]）", boxIsAir(level));
        // ---- 前提 ②：收尾要把 bot 停到地面（超平坦世界的 y=-60 脚位层）----
        check("前提：收尾停车位可站（PARK_FOOT=" + PARK_FOOT.toShortString()
                + " canStandCentered=" + MovementHelper.canStandCentered(level, PARK_FOOT) + "）",
                MovementHelper.canStandCentered(level, PARK_FOOT));

        // ---- 建：整盒基岩 → 挖通道（脚位 + 头位）→ 夹缝头位换可破坏方块 → 目标脚位换可破坏方块 ----
        for (int dx = BOX_X0; dx <= BOX_X1; dx++) {
            for (int dz = BOX_Z0; dz <= BOX_Z1; dz++) {
                for (int dy = BOX_Y0; dy <= BOX_Y1; dy++) {
                    level.setBlock(ORIGIN.offset(dx, dy, dz), Blocks.BEDROCK.defaultBlockState(), 2);
                }
            }
        }
        for (BlockPos foot : cells) {
            level.setBlock(foot, Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(foot.above(), Blocks.AIR.defaultBlockState(), 2);
        }
        for (int x = SLIT_FIRST_X; x <= SLIT_LAST_X; x++) {
            level.setBlock(cell(x, 0).above(), Blocks.DIRT.defaultBlockState(), 2);
        }
        level.setBlock(goalFoot, Blocks.DIRT.defaultBlockState(), 2);
        built = true;

        // ---- 前提 ③：几何真的长成设计的样子（每一条都是判据的一部分）----
        check("前提：起点可站（" + startFoot.toShortString() + "）", MovementHelper.canStandCentered(level, startFoot));
        int slitOk = 0;
        for (int x = SLIT_FIRST_X; x <= SLIT_LAST_X; x++) {
            BlockPos foot = cell(x, 0);
            if (MovementHelper.canWalkThrough(level, foot)
                    && !MovementHelper.canWalkThrough(level, foot.above())
                    && MovementHelper.canWalkOn(level, foot)
                    && !MovementHelper.canTraverse(level, cell(x - 1, 0), foot)) {
                slitOk++;
            }
        }
        check("前提：⭐ " + SLIT_COUNT + " 个夹缝格都是「脚位空 + 头位实 + 脚下有支撑 + 不是普通 TRAVERSE」"
                + "（实际合格 " + slitOk + "/" + SLIT_COUNT + "）—— 这就是被测的那个形状", slitOk == SLIT_COUNT);
        check("前提：目标格**脚位被挡**（可破坏）且头位空、脚下有支撑（goal=" + goalFoot.toShortString() + "）",
                !MovementHelper.canWalkThrough(level, goalFoot)
                        && MovementHelper.canWalkThrough(level, goalFoot.above())
                        && MovementHelper.canWalkOn(level, goalFoot));
        int detourOk = 0;
        // 通道前 {@link #DIRECT_CELLS} 格 = 起点 / 直挖路第一格 / 5 个夹缝格 / 目标；其余 = 绕远通道
        List<BlockPos> detour = cells.subList(DIRECT_CELLS, cells.size());
        for (BlockPos foot : detour) {
            if (MovementHelper.canStandCentered(level, foot)) {
                detourOk++;
            }
        }
        check("前提：绕远通道每一格都可站（" + detourOk + "/" + detour.size() + "，应为 "
                        + (EXPECTED_DETOUR_MOVEMENTS - 1) + " 格）",
                detour.size() == EXPECTED_DETOUR_MOVEMENTS - 1 && detourOk == detour.size());
        check("前提：场景盒外圈（含上下）一圈是空气（孤立长方体）", outerShellIsAir(level));
        MovementContext context = MovementContext.live(bot, level, requestFor(BUDGET_PRODUCTION));
        check("前提：本次请求信封允许破入边（writesAllowed(BREAK_AND_ENTER)）",
                context.writesAllowed(MovementType.BREAK_AND_ENTER));
        List<MovementType> startOffPlane = offPlaneTypes(context, startFoot);
        List<MovementType> deepOffPlane = offPlaneTypes(context, cell(0, -DETOUR_LEG / 2));
        check("前提：⭐ 场景**被封成一层**（起点 / 深通道里的候选都没有上升·下落·搭柱·放置类边："
                        + startOffPlane + " / " + deepOffPlane + "）"
                        + " —— 这一条不成立时，「绕远段数」就不再是几何决定的",
                startOffPlane.isEmpty() && deepOffPlane.isEmpty());

        BotLog.info("[RouteClosure] scene built: start={} goal={} corridorCells={} box=[{}..{}]x[{}..{}]x[{}..{}]"
                        + " slits={} detourExpectedMovements={} directExpectedMovements={}",
                startFoot.toShortString(), goalFoot.toShortString(), cells.size(),
                BOX_X0, BOX_X1, BOX_Z0, BOX_Z1, BOX_Y0, BOX_Y1,
                SLIT_COUNT, EXPECTED_DETOUR_MOVEMENTS, EXPECTED_DIRECT_MOVEMENTS);
    }

    private boolean boxIsAir(ServerLevel level) {
        for (int dx = BOX_X0; dx <= BOX_X1; dx++) {
            for (int dz = BOX_Z0; dz <= BOX_Z1; dz++) {
                for (int dy = BOX_Y0; dy <= BOX_Y1; dy++) {
                    if (!level.getBlockState(ORIGIN.offset(dx, dy, dz)).isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** 盒外一圈（含上下）必须全是空气。 */
    private boolean outerShellIsAir(ServerLevel level) {
        for (int dx = BOX_X0 - 1; dx <= BOX_X1 + 1; dx++) {
            for (int dz = BOX_Z0 - 1; dz <= BOX_Z1 + 1; dz++) {
                for (int dy = BOX_Y0 - 1; dy <= BOX_Y1 + 1; dy++) {
                    boolean inside = dx >= BOX_X0 && dx <= BOX_X1 && dz >= BOX_Z0 && dz <= BOX_Z1
                            && dy >= BOX_Y0 && dy <= BOX_Y1;
                    if (!inside && !level.getBlockState(ORIGIN.offset(dx, dy, dz)).isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * 某一格上**非平面**类的候选（上升 / 下落 / 搭柱 / 放置）。
     *
     * <p>用于自断言「场景被封成一层」：本场景的几何（基岩地板 + 基岩天花板 + 每格都有地板）
     * 应当把这几类边**全部**掐死 ⇒ 只剩平面移动 + 破入。不成立 ⇒ 绕远段数会被别的能力改写。
     */
    private List<MovementType> offPlaneTypes(MovementContext context, BlockPos from) {
        List<PlannedMovement> out = new ArrayList<>();
        new SurfaceMovementProvider().appendCandidates(context, from, out);
        List<MovementType> off = new ArrayList<>();
        for (PlannedMovement movement : out) {
            switch (movement.movementType()) {
                case ASCEND, DOWNWARD, PILLAR, FALL, PLACE_STEP_AND_TRAVERSE -> off.add(movement.movementType());
                default -> {
                }
            }
        }
        return off;
    }

    // ============================================================ 三遍

    private PathRequest requestFor(SearchBudget budget) {
        PathRequest base = PathRequest.withWorldModification(bot.getUUID().toString(), startFoot, goalFoot, REQUESTER);
        return new PathRequest(base.botId(), startFoot, base.goal(), base.allowedMovementTypes(),
                budget, base.requester());
    }

    private void runArm(Arm arm) {
        ServerLevel level = bot.serverLevel();
        SearchBudget budget = arm == Arm.PRE_LEGACY_TIGHT ? BUDGET_TIGHT : BUDGET_PRODUCTION;
        LegacyHeadGateProvider legacyProvider = arm == Arm.POST_PRODUCTION ? null : new LegacyHeadGateProvider();
        MovementProvider provider = legacyProvider == null ? new SurfaceMovementProvider() : legacyProvider;

        PathRequest request = requestFor(budget);
        PathPlan plan = new CorePathPlanner(provider).plan(bot, level, request);

        int breakEnter = 0;
        int headBlockedBreaks = 0;
        int outsideCorridor = 0;
        StringBuilder chain = new StringBuilder();
        for (PlannedMovement movement : plan.movements()) {
            if (movement.movementType() == MovementType.BREAK_AND_ENTER) {
                breakEnter++;
                // 「旧谓词会拒掉的那一类」：脚位**可通行**、头位被挡 ⇒ 修复前这条边根本不生成
                if (MovementHelper.canWalkThrough(level, movement.toFoot())) {
                    headBlockedBreaks++;
                }
            }
            if (!corridor.contains(movement.toFoot())) {
                outsideCorridor++;
            }
            if (chain.length() < 400) {
                chain.append('[').append(movement.movementType()).append(' ')
                        .append(movement.fromFoot().toShortString()).append('→')
                        .append(movement.toFoot().toShortString()).append("] ");
            }
        }
        int dropped = legacyProvider == null ? 0 : legacyProvider.dropped;
        Reading reading = new Reading(arm.name(), plan, breakEnter, headBlockedBreaks, outsideCorridor, dropped);
        readings.add(reading);
        String violation = contractViolation(plan);
        findings.add(arm + ":status=" + plan.status() + " reached=" + plan.reached()
                + " movements=" + plan.movements().size() + " breakEnter=" + breakEnter
                + " headBlockedBreaks=" + headBlockedBreaks + " outsideCorridor=" + outsideCorridor
                + " dropped=" + dropped + " nodes=" + plan.nodesExpanded()
                + " cost=" + String.format(Locale.ROOT, "%.2f", plan.totalCost())
                + " ms=" + plan.elapsedMillis());
        BotLog.info("[RouteClosure] arm={} status={} reached={} movements={} breakEnter={} headBlockedBreaks={}"
                        + " outsideCorridor={} legacyDropped={} nodes={} cost={} ms={} diag={}",
                arm, plan.status(), plan.reached(), plan.movements().size(), breakEnter, headBlockedBreaks,
                outsideCorridor, dropped, plan.nodesExpanded(),
                String.format(Locale.ROOT, "%.2f", plan.totalCost()), plan.elapsedMillis(), plan.diagnostics());
        BotLog.info("[RouteClosure] arm={} chain={}", arm, chain.toString().trim());

        switch (arm) {
            case POST_PRODUCTION -> {
                check("① 生产 provider（= 修复后）：头位被挡的直挖路**必须可规划**（status=" + plan.status() + "）",
                        plan.reached());
                check("① 直挖路段数 ≤ " + EXPECTED_DIRECT_MOVEMENTS + "（理论值 " + EXPECTED_DIRECT_MOVEMENTS
                                + "；实际 " + plan.movements().size() + "）—— 修复前这条路不存在，只能绕 "
                                + EXPECTED_DETOUR_MOVEMENTS + " 段",
                        plan.movements().size() <= EXPECTED_DIRECT_MOVEMENTS);
                check("① 恰好 " + SLIT_COUNT + " 段是「旧谓词会拒掉的那一类」（脚位可通行 + 头位被挡；实际 "
                                + headBlockedBreaks + "）—— 即：这条路**只因那一行谓词**才存在",
                        headBlockedBreaks == SLIT_COUNT);
                check("① 破入边总数 = " + (SLIT_COUNT + 1) + "（" + SLIT_COUNT + " 段夹缝 + 1 段破进目标；实际 "
                        + breakEnter + "）", breakEnter == SLIT_COUNT + 1);
                check("① 不许挖到通道之外（实际 " + outsideCorridor + " 段落点越界）", outsideCorridor == 0);
                check("① 每一步都能构造执行端 MovementSpec（K-4 可规划即可执行）"
                        + (violation.isEmpty() ? "" : "：违反=" + violation), violation.isEmpty());
            }
            case PRE_LEGACY_AMPLE -> {
                // ⚠️ `dropped` 计的是**这次搜索里真的生成过、又被滤掉**的边数 —— 旧臂进不了夹缝段
                // ⇒ 只有「进第一个夹缝格」那一条会生成 ⇒ 实测 1。判据只要求 > 0：**为 0 就是没测到**
                //（`silent-measurement-failure`，`P3` 的教训），而不是「测到没差别」。
                check("② 旧谓词模拟**真的删掉了边**（dropped > 0，实际 " + dropped + "）"
                        + " —— 为 0 就是「没测到」，不是「测到没差别」", dropped > 0);
                check("② 同预算下仍能到（绕远通道），但**段数 ≥ " + DETOUR_MIN_MOVEMENTS + "**"
                                + "（理论 " + EXPECTED_DETOUR_MOVEMENTS + "；实际 " + plan.movements().size() + "）",
                        plan.reached() && plan.movements().size() >= DETOUR_MIN_MOVEMENTS);
                check("② 旧边集里**不许**出现那类边（脚位可通行 + 头位被挡；实际 " + headBlockedBreaks + "）",
                        headBlockedBreaks == 0);
                check("② 不许挖到通道之外（实际 " + outsideCorridor + " 段落点越界）", outsideCorridor == 0);
            }
            case PRE_LEGACY_TIGHT -> {
                check("③ 旧谓词模拟**真的删掉了边**（dropped > 0，实际 " + dropped + "）", dropped > 0);
                check("③ 紧预算（" + BUDGET_TIGHT.maxNodes() + " 节点）下**到不了**（status=" + plan.status()
                        + " reached=" + plan.reached() + "）—— 修复前唯一路线要 " + EXPECTED_DETOUR_MOVEMENTS
                        + " 段 ⇒ 至少那么多次弹出", !plan.reached());
                check("③ 预算**真的是绑定点**（nodesExpanded=" + plan.nodesExpanded() + " ≥ "
                        + BUDGET_TIGHT.maxNodes() + "）—— 否则这条读数是「空间穷尽」而不是「预算耗尽」",
                        plan.nodesExpanded() >= BUDGET_TIGHT.maxNodes());
            }
        }
    }

    /** `K-4` 不变量：把计划里的**每一步**都真的构造一次执行端 `MovementSpec`（与兄弟夹具同口径）。 */
    private static String contractViolation(PathPlan plan) {
        for (PlannedMovement movement : plan.movements()) {
            try {
                PlannedMovementSpecs.toSpec(movement, List.of("session_segment", "target_support",
                        "target_body_clear", "target_head_clear"));
            } catch (RuntimeException exception) {
                return movement.movementType() + " " + movement.fromFoot().toShortString() + "→"
                        + movement.toFoot().toShortString() + " :: " + exception.getMessage();
            }
        }
        return "";
    }

    /**
     * **修复前语义的边过滤**（只给反臂用，**不碰生产代码**）。
     *
     * <p>旧入口闸门是 `if (MovementHelper.canWalkThrough(level, to)) return;`（只看脚位）⇒
     * 「脚位可通行 + 头位被挡」的目的地**不会**产出 `BREAK_AND_ENTER`。
     * 修复后闸门是 `!bodyPassable(to)`（脚位 **且** 头位）⇒ 差额 = 脚位可通行且头位被挡那一类。
     * 因此"从修复后的边集里滤掉脚位可通行的 `BREAK_AND_ENTER`" = **逐字等价于旧边集**。
     *
     * <p>⚠️ 两条纪律：① {@code dropped} 必须进判据（为 0 ⇒ 反臂什么都没测）；
     * ② 它**只包装生成器**，不改任何世界谓词/预算/信封（同一性由"只多一层过滤"保证）。
     */
    private static final class LegacyHeadGateProvider implements MovementProvider {
        private final SurfaceMovementProvider delegate = new SurfaceMovementProvider();
        private int dropped;

        @Override
        public void appendCandidates(MovementContext context, BlockPos from, List<PlannedMovement> out) {
            filterLegacy(context, from, out, false);
        }

        @Override
        public void appendStartEscapeCandidates(MovementContext context, BlockPos from,
                                                List<PlannedMovement> out) {
            filterLegacy(context, from, out, true);
        }

        private void filterLegacy(MovementContext context, BlockPos from, List<PlannedMovement> out,
                                  boolean escape) {
            List<PlannedMovement> raw = new ArrayList<>();
            if (escape) {
                delegate.appendStartEscapeCandidates(context, from, raw);
            } else {
                delegate.appendCandidates(context, from, raw);
            }
            for (PlannedMovement movement : raw) {
                if (movement.movementType() == MovementType.BREAK_AND_ENTER
                        && MovementHelper.canWalkThrough(context.level(), movement.toFoot())) {
                    dropped++;
                    continue;
                }
                out.add(movement);
            }
        }
    }

    // ============================================================ 收尾

    private Task.Status finish() {
        if (done) {
            return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        }
        done = true;
        ServerLevel level = bot.serverLevel();
        if (built) {
            // 还原 = 本盒子填回空气（建之前断言过它全为空气 ⇒ 这是**逐格还原**，不是"大概干净"）
            for (int dx = BOX_X0; dx <= BOX_X1; dx++) {
                for (int dz = BOX_Z0; dz <= BOX_Z1; dz++) {
                    for (int dy = BOX_Y0; dy <= BOX_Y1; dy++) {
                        level.setBlock(ORIGIN.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
            restored = boxIsAir(level);
        }
        check("清理：场景盒已整体还原为空气（restored=" + restored + "）", restored);
        teleport(bot, PARK_FOOT);
        bot.controller().stopMovement();

        if (readings.size() == Arm.values().length) {
            Reading post = readings.get(0);
            Reading ample = readings.get(1);
            Reading tight = readings.get(2);
            check("⭐ 收口判据：修复前/后**同一场景同一预算**，段数从 " + ample.plan.movements().size()
                            + " 段降到 " + post.plan.movements().size() + " 段（要求 "
                            + post.plan.movements().size() + "×4 ≤ " + ample.plan.movements().size() + "）",
                    post.plan.movements().size() * 4 <= ample.plan.movements().size());
            check("⭐ 前提：直挖路确实**更便宜**（post cost=" + oneDecimal(post.plan.totalCost())
                            + " < pre cost=" + oneDecimal(ample.plan.totalCost()) + "）"
                            + " —— 否则「绕远」就不是被谓词逼的，而是被成本逼的",
                    post.plan.totalCost() < ample.plan.totalCost());
            check("⭐ 前提：直挖路的搜索量确实更小（post nodes=" + post.plan.nodesExpanded()
                            + " < pre nodes=" + ample.plan.nodesExpanded() + "）",
                    post.plan.nodesExpanded() < ample.plan.nodesExpanded());
            check("③ 紧预算那档读到的状态属于「预算耗尽」而不是「空间穷尽」"
                            + "（status=" + tight.plan.status() + " reached=" + tight.plan.reached() + "）",
                    !tight.plan.reached() && tight.plan.nodesExpanded() >= BUDGET_TIGHT.maxNodes());
        } else {
            check("三遍都必须跑完（实际 " + readings.size() + "/" + Arm.values().length + "）"
                    + "—— 中途失败时读数不足以收口", false);
        }

        boolean pass = failures.isEmpty();
        String summary = "SUMMARY checks=" + checks + " failures=" + failures.size()
                + " arms=" + readings.size() + "/" + Arm.values().length
                + " directMovements=" + (readings.isEmpty() ? "-" : readings.get(0).plan.movements().size())
                + " detourMovements=" + (readings.size() < 2 ? "-" : readings.get(1).plan.movements().size())
                + " tightReached=" + (readings.size() < 3 ? "-" : readings.get(2).plan.reached())
                + " tightNodes=" + (readings.size() < 3 ? "-" : readings.get(2).plan.nodesExpanded())
                + " verdict=" + (pass ? "PASS" : "FAIL");
        BotLog.info("[RouteClosure] {}", summary);
        BotLog.info("[RouteClosure] findings={} failures={}", findings, failures);
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 头位夹缝「路线收口」判据 "
                    + (pass ? "PASS" : "FAIL") + "（详见日志 [RouteClosure]）"));
        }
        return pass ? Task.Status.DONE : Task.Status.FAILED;
    }

    private static String oneDecimal(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.2f", value) : "INF";
    }

    private void teleport(ServerPlayer who, BlockPos foot) {
        who.teleportTo(who.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                Set.of(), who.getYRot(), who.getXRot());
        who.setDeltaMovement(Vec3.ZERO);
        if (who instanceof BotPlayer botPlayer) {
            botPlayer.controller().stopMovement();
        }
    }

    private void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures.add(what);
        }
    }
}
