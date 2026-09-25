package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.AscendExecutionFactory;
import com.dddgn.alice.pathing.core.CompletionTolerance;
import com.dddgn.alice.pathing.core.DownwardExecutionFactory;
import com.dddgn.alice.pathing.core.LiveExecutionContext;
import com.dddgn.alice.pathing.core.MovementExecution;
import com.dddgn.alice.pathing.core.MovementSpec;
import com.dddgn.alice.pathing.core.MovementType;
import com.dddgn.alice.pathing.core.PlaceStepAndTraverseExecutionFactory;
import com.dddgn.alice.pathing.core.RecoverabilityEvaluator;
import com.dddgn.alice.pathing.core.search.MovementContext;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.search.PlannedMovement;
import com.dddgn.alice.pathing.core.search.PlannedMovementSpecs;
import com.dddgn.alice.pathing.core.search.SurfaceMovementProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * ⭐ **「搭石斜下」的过渡空间取证 + 「洞沿离心」下落取证**
 *（电池步 `place_step_descend_clearance`；前者 = `D-376` 的落地判据，后者 = `D-445` 裁定三的落地判据）。
 *
 * <h2>它钉住的是什么（两句话）</h2>
 * <b>① `PLACE_STEP_AND_TRAVERSE` 在 `dy = -1`（从上面一层走下来）时，必须证明**身体的过渡空间**能过 ——
 * 不只是"站进去之后放得下"。</b><br>
 * <b>② `DOWNWARD` 在**洞沿上、离心 > 0.2** 时，必须**把自己挪到格中心再掉下去** ——
 * 只 `stopMovement()` 会被邻列那块实心的角托住（`onGround=true`）而永不下落。</b>
 * 两条是**同一个家族**（都表现为 `segment_stall kind=segment_timeout` + `onGround=true`），
 * 但**按键读数恰好相反**（`D-376` 是 `forward=1.00` 被第三层挡住；`D-445` 是 `forward=0.00` 根本没按）
 * ⇒ 必须分开记账，不许混成一条（2026-09-25 的教训）。
 *
 * <h2>真机事故（第八轮，逐字见 `docs/reviews/2026-09-21-掉落物在洞里被瞬退.md` §13.2）</h2>
 * ```
 * [R4 Session] segment_stall kind=segment_timeout to=632, 64, 93 botFoot=632, 65, 94
 *   pos=632.499,65.000,94.300 onGround=true delta=-0.0001,-0.0784,0.0000
 *   input=BotController[forward=1.00 strafing=0.00 jumping=false]
 *   toBlock=空气 headBlock=空气 supportBlock=圆石 segmentTicks=222     ← 11 秒，同一形状发生两次
 * ```
 * 四条事实合起来只有一个解释：① 按着前进却**零位移**；② 位置 **z=94.300**（包围盒北面正好贴住格边界）；
 * ③ 目的地 `to` 与其上一层都是空气；④ ⇒ 挡住它的方块只能在**身体扫掠盒会覆盖、而闸门不查的那一层**
 * （= `to.above(2)`，即目的地正上方第二格）。
 *
 * <p><b>两侧闸门原本的口径差</b>：
 * <ul>
 *   <li>`canDescend`（普通下台阶）查了 `canSweepPlayer`（扫掠盒 `maxY = max(from,to)+1.8` ⇒ **含第 3 层**）；</li>
 *   <li>`AscendExecutionFactory` 也早就在查 `from.up2`（`ASCEND_NO_HEADROOM`，带 from/up/up2/up3 几何）；</li>
 *   <li>唯独 `appendPlaceStepAndTraverse` 只查 `bodyPassable(to)`（`to` + `to.above()` **两层**），
 *       执行工厂 `validate` 同样只查两层 ⇒ 计划里会出现**物理上过不去**的段。</li>
 * </ul>
 *
 * <h2>两个用例（判据 + 反证）</h2>
 * <table border="1">
 *   <tr><th>用例</th><th>几何</th><th>期望</th><th>`D-376` 修复前</th></tr>
 *   <tr><td>{@code TRANSITION_BLOCKED}</td><td>搭石斜下的目标：`to`/`to.above()` 空气、`to.below()` 缺支撑（正要放），
 *       但 **`to.above(2)` 实心**</td><td>**不许**生成这条 `PLACE_STEP_AND_TRAVERSE` 边；执行工厂也必须拒</td>
 *       <td>❌ 红（边被生成 ⇒ 真机上就是那 222 tick 的原地走）</td></tr>
 *   <tr><td>{@code TRANSITION_CLEAR}（反证）</td><td>同上，去掉 `to.above(2)` 那块</td>
 *       <td>这条边**必须**生成，且执行工厂接受（证明修复没有把合法的搭石斜下一并禁掉）</td>
 *       <td>✅ 本来就绿</td></tr>
 * </table>
 *
 * <h2>它断言哪一层（技能 §6.9.1 ③）</h2>
 * ① 过渡空间那两用例：只断言**边生成层**（`SurfaceMovementProvider.appendCandidates` 的产出）+
 *   **执行工厂的准入**（`PlaceStepAndTraverseExecutionFactory.validate`）⇒ 不 tick、零搜索、确定性。<br>
 * ② ⭐「洞沿离心」那一支（`edgeCrawlContract`）：**会真的 tick 一条 `DOWNWARD` 到终态**
 *   （跨 tick 分阶段）—— 因为那个缺陷是**物理 × 输入循环**的产物，只有真 tick 才复现得出来
 *   （真机形态：`onGround=true` + `delta=(0,-0.0784,0)` + `forward=0.00` + 145 tick 超时）。
 *   它自带几何（`EDGE_ORIGIN`，world 无关）并在结束时**原样还原**。
 *
 * <h2>前提自证（红了说明夹具坏，不是缺陷证据）</h2>
 * ① `bodyPassable(to)`；② `!canWalkOn(to)`（正是"要放一块"的那种缺支撑）；
 * ③ `canWalkThrough(to.below())`（放置位可替换）；④ `!canDescend(from,to)`（隔离：DESCEND 不可能提供这条边）；
 * ⑤ 有可放置材料 + 有放置面；⑥ 写入预算允许；⑦ 两个用例的 `canSweepPlayer` 取值恰好相反（**这就是被测的那个谓词**）；
 * ⑧ ⭐「洞沿」那一支：bot 必须**真的静止在洞沿上（`onGround` + 离心 > 0.2）**、正下方是空气、邻列角是实心
 * —— 三条缺一条就说明场景没搭出来（那时报红是夹具的错）。
 */
public final class PlaceStepDescendClearanceCheckTask implements Task {

    /** 专用孤立点（超平坦世界里的一块空地，离其它场景 ≥100 格）。 */
    private static final BlockPos START = new BlockPos(3000, -60, 4000);

    private static final int SETTLE_TICKS = 30;
    private static final int BUDGET_TICKS = 300;
    /** 合成 Movement 用的成本（`PlannedMovement` 需要有限非负；只有"构造得出来吗"这一条判据关心它）。 */
    private static final double SYNTHETIC_COST = 5.0D;

    // ---- ⭐ 机制 A（`D-445` 裁定三）：洞沿离心 ⇒ 必须真的掉下去 ----
    /** 专用孤立点**再往 +Z 40 格**（与上面三格场景隔开，互不干扰）。 */
    private static final BlockPos EDGE_ORIGIN = START.offset(0, 0, 40);
    /** 水平偏移 = **0.3**（= AABB 半宽 ⇒ AABB 刚好压进邻列 0.1 格 ⇒ 被托在洞沿上）。 */
    private static final double EDGE_OFFSET = 0.3D;
    /** 与 `DownwardExecution.CRAWL_TO_CENTER_EPSILON` 同源（Baritone `MovementDownward:90` 的 0.2）。 */
    private static final double CRAWL_TO_CENTER_EPSILON = 0.2D;
    private static final int EDGE_SETTLE_TICKS = 12;
    private static final int EDGE_BUDGET_TICKS = 120;

    private enum Case { TRANSITION_BLOCKED, TRANSITION_CLEAR }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final List<String> findings = new ArrayList<>();

    private int ticks;
    private int checks;
    private int prepareStep;
    private int index;
    private boolean done;
    private BlockPos from;
    private BlockPos to;
    /** 挖掉的那一格（`to.below()`）**原本**是什么 —— 收尾要按原样还原（不是一律写空气）。 */
    private BlockState holeOriginal;

    // ---- ⭐ 机制 A：洞沿离心下落的运行时契约（分阶段，跨 tick 跑一条真的 DOWNWARD） ----
    private int edgePhase;
    private int edgeSettleTicks;
    private int edgeExecTicks;
    private double edgeStartDistance;
    private String edgeVerdict = "";
    private MovementExecution edgeExecution;
    private final java.util.Map<BlockPos, BlockState> edgeBefore = new java.util.LinkedHashMap<>();

    public PlaceStepDescendClearanceCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "PlaceStepDescendClearanceCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(START);
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
            teleport(bot, START);
            FixtureToolKit.ensureHotbarStack(bot, () -> new ItemStack(Items.COBBLESTONE, 64),
                    stack -> stack.is(Items.COBBLESTONE), 1, "place-step-clearance");
            prepareStep = 1;
            return Task.Status.RUNNING;
        }
        if (prepareStep <= SETTLE_TICKS) {
            prepareStep++;
            return Task.Status.RUNNING;
        }
        if (prepareStep == SETTLE_TICKS + 1) {
            ServerLevel level = bot.serverLevel();
            // 脚位**从 bot 实际位置取**（D-328 附注：坐标假设必须自带判据）
            from = bot.blockPosition();
            // 搭石斜下的几何：正下方一格 + 水平一格（`|dx|+|dz| == 1`、`dy == -1`）
            to = from.offset(0, -1, 1);
            holeOriginal = level.getBlockState(to.below());
            check("前提：起点可站（from=" + from.toShortString() + "）", MovementHelper.canStandCentered(level, from));
            check("前提：待挖空的那一格原本是实心（to.below=" + to.below().toShortString()
                            + " state=" + holeOriginal.getBlock().getName().getString() + "）",
                    !holeOriginal.isAir());
            prepareStep++;
            return Task.Status.RUNNING;
        }
        if (index >= Case.values().length) {
            return edgeCrawlContract(bot.serverLevel());
        }
        runCase(Case.values()[index++]);
        return Task.Status.RUNNING;
    }

    // ==================== ⭐ 机制 A（`D-445` 裁定三）：洞沿离心 ⇒ 必须真的掉下去 ====================

    /**
     * ⭐ **运行时判据：把 bot 放在"洞沿上、离心 0.3"**（= AABB 半宽 ⇒ 正好蹭在邻列角上）**静止**，
     * 然后跑一条 `DOWNWARD`（正下方已挖空）⇒ **必须在预算内真的掉下去**。
     *
     * <p>为什么必须**跑**而不是只判谓词：真机那两条 `segment_stall` 的形态是
     * "`onGround=true` + `delta=(0,-0.0784,0)` + `input=forward 0.00` + 145 tick 超时" ——
     * 这是**物理与输入循环**的产物，只有在真 tick 里才复现得出来
     *（取证 `docs/reviews/2026-09-25-真机第三轮-根因取证.md` §4）。
     *
     * <p>修复前（`DownwardExecution` 只 `stopMovement()`）：邻列那块实心把 AABB 的 0.1 托住 ⇒
     * `onGround` 永远成立 ⇒ 这里是 `TIMEOUT`（= 真机形态）。红臂就是把它改回去。
     *
     * <p>几何（**自建 + 原样还原**，不依赖世界地形）：
     * <pre>
     *   邻列角(Y-1,Z+1)=石 ── 托住 AABB 的那 0.1
     *   (X,Y-1,Z)=空气 = 洞        bot 在 (X+0.5, Y, Z+0.8) ⇒ 离心 0.3
     *   (X,Y-2,Z)=石   = 洞底      （走到中心就掉进洞里、落在洞底）
     * </pre>
     */
    private Task.Status edgeCrawlContract(ServerLevel level) {
        switch (edgePhase) {
            case 0 -> {
                edgeBuildScene(level);
                edgeTeleportOffset(level, EDGE_ORIGIN, EDGE_OFFSET);
                edgePhase = 1;
                return Task.Status.RUNNING;
            }
            case 1 -> {
                if (++edgeSettleTicks < EDGE_SETTLE_TICKS) {
                    return Task.Status.RUNNING;
                }
                edgeStartDistance = MovementHelper.horizontalDistanceToCenter(bot, EDGE_ORIGIN);
                boolean onRim = bot.onGround() && MovementHelper.footCell(level, bot).equals(EDGE_ORIGIN);
                check("⭐机制 A 前提：bot 静止在**洞沿**上（onGround=" + bot.onGround() + " foot="
                                + MovementHelper.footCell(level, bot).toShortString() + " 离心="
                                + String.format("%.3f", edgeStartDistance) + "）"
                                + " —— 离心 > 0.2 才会蹭到邻列角（格半宽 0.5 − AABB 半宽 0.3）",
                        onRim && edgeStartDistance > CRAWL_TO_CENTER_EPSILON);
                check("⭐机制 A 前提：正下方是**空气**、再下一格才是支撑（洞="
                                + EDGE_ORIGIN.below().toShortString() + " air="
                                + level.getBlockState(EDGE_ORIGIN.below()).isAir() + "）",
                        level.getBlockState(EDGE_ORIGIN.below()).isAir()
                                && MovementHelper.canWalkOn(level, EDGE_ORIGIN.below()));
                check("⭐机制 A 前提：邻列角是实心（托住 bot 的那一格 " + EDGE_ORIGIN.offset(0, -1, 1).toShortString()
                                + "）", MovementHelper.canWalkOn(level, EDGE_ORIGIN.offset(0, 0, 1)));
                check("⭐机制 A 前提：**因果谓词认得出这个状态** —— `supportedByNeighbourCorner`"
                                + "（本列脚下已空 + AABB 探一步会撞到邻列）= "
                                + MovementHelper.supportedByNeighbourCorner(level, bot, EDGE_ORIGIN)
                                + "（假 ⇒ 修复的触发条件没成立 ⇒ 红的是夹具，不是缺陷）",
                        MovementHelper.supportedByNeighbourCorner(level, bot, EDGE_ORIGIN));
                PlannedMovement movement = new PlannedMovement(MovementType.DOWNWARD, EDGE_ORIGIN,
                        EDGE_ORIGIN.below(), SYNTHETIC_COST, RecoverabilityEvaluator.levelOf(MovementType.DOWNWARD));
                edgeExecution = new DownwardExecutionFactory().create(
                        PlannedMovementSpecs.toSpec(movement, List.of("session_segment")),
                        new LiveExecutionContext(bot, level, "downward-edge-crawl", 0L,
                                CompletionTolerance.COLUMN, "edge-crawl-contract"));
                edgePhase = 2;
                return Task.Status.RUNNING;
            }
            case 2 -> {
                edgeExecution.tick();
                if (edgeExecution.phase() == MovementExecution.Phase.SUCCEEDED) {
                    edgeVerdict = "SUCCEEDED（" + edgeExecTicks + " tick 落到 "
                            + MovementHelper.footCell(level, bot).toShortString() + "）";
                } else if (edgeExecution.phase() == MovementExecution.Phase.FAILED
                        || edgeExecution.phase() == MovementExecution.Phase.CANCELLED) {
                    edgeVerdict = edgeExecution.phase() + "/" + edgeExecution.failureCode();
                } else if (++edgeExecTicks > EDGE_BUDGET_TICKS) {
                    edgeExecution.cancel();
                    edgeVerdict = "TIMEOUT（" + EDGE_BUDGET_TICKS + " tick 没掉下去；离心="
                            + String.format("%.3f", MovementHelper.horizontalDistanceToCenter(bot, EDGE_ORIGIN))
                            + " onGround=" + bot.onGround() + " input=" + bot.controller().getInputStateString()
                            + " —— 这正是真机那两条 145 tick 的形态）";
                } else {
                    return Task.Status.RUNNING;
                }
                BotLog.info("[PlaceStepClear] edge_crawl startDistance={} verdict={}",
                        String.format("%.3f", edgeStartDistance), edgeVerdict);
                check("⭐机制 A：洞沿离心 " + String.format("%.3f", edgeStartDistance)
                                + " ⇒ `DOWNWARD` 必须在 " + EDGE_BUDGET_TICKS + " tick 内**真的掉下去**"
                                + "（Baritone `MovementDownward:86-94`：偏移 ≥ 0.2 就朝格中心走）。实测 " + edgeVerdict,
                        edgeVerdict.startsWith("SUCCEEDED"));
                edgePhase = 3;
                edgeRestore(level);
                return finish();
            }
            default -> {
                return finish();
            }
        }
    }

    /** 自建几何（**先在 `edgeBefore` 里记原状**，收尾原样还原：不许污染专用孤立点）。 */
    private void edgeBuildScene(ServerLevel level) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                edgeBlock(level, EDGE_ORIGIN.offset(dx, 0, dz), Blocks.AIR);
                edgeBlock(level, EDGE_ORIGIN.offset(dx, 1, dz), Blocks.AIR);
                edgeBlock(level, EDGE_ORIGIN.offset(dx, -2, dz), Blocks.STONE);
                // y-1：中间那格是"洞"，其余 8 格是石（其中 (0,-1,+1) 就是托住 bot 的那个角）
                edgeBlock(level, EDGE_ORIGIN.offset(dx, -1, dz),
                        dx == 0 && dz == 0 ? Blocks.AIR : Blocks.STONE);
            }
        }
    }

    private void edgeBlock(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.Block block) {
        BlockPos key = pos.immutable();
        edgeBefore.putIfAbsent(key, level.getBlockState(key));
        level.setBlockAndUpdate(key, block.defaultBlockState());
    }

    private void edgeRestore(ServerLevel level) {
        for (java.util.Map.Entry<BlockPos, net.minecraft.world.level.block.state.BlockState> e
                : edgeBefore.entrySet()) {
            level.setBlockAndUpdate(e.getKey(), e.getValue());
        }
    }

    /** 传送到某脚位格、**带水平偏移**（偏移就是被测量的那个自变量；夹具不许用"自动居中"的传送）。 */
    private void edgeTeleportOffset(ServerLevel level, BlockPos foot, double dz) {
        bot.teleportTo(level, foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D + dz,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
    }

    private void runCase(Case which) {
        ServerLevel level = bot.serverLevel();
        restore();
        // 几何：目标缺支撑（挖空 to.below）⇒ 只有 PLACE_STEP_AND_TRAVERSE 这条边能来
        level.setBlockAndUpdate(to.below(), Blocks.AIR.defaultBlockState());
        if (which == Case.TRANSITION_BLOCKED) {
            // 身体从上面一层走下来时会扫过的那一层（目的地正上方第二格）
            level.setBlockAndUpdate(to.above(2), Blocks.STONE.defaultBlockState());
        }

        boolean bodyOk = MovementHelper.bodyPassable(level, to);
        boolean supported = MovementHelper.canWalkOn(level, to);
        boolean placeable = MovementHelper.canWalkThrough(level, to.below());
        boolean descend = MovementHelper.canDescend(level, from, to);
        boolean sweep = MovementHelper.canSweepPlayer(level, from, to);

        // ---- 前提自证：除「过渡空间」外，边生成器的**其余闸门全部成立** ----
        check(which + " 前提：目的地整体通行（bodyPassable(to)=" + bodyOk + "）", bodyOk);
        check(which + " 前提：目的地**缺支撑**（canWalkOn(to)=" + supported + "）—— 这正是「要放一块」的形态",
                !supported);
        check(which + " 前提：放置位可替换（canWalkThrough(to.below)=" + placeable + "）", placeable);
        check(which + " 前提：`canDescend` 不可用（" + descend + "）—— 隔离：这条边只可能来自 PLACE_STEP",
                !descend);
        check(which + " 前提：有可放置材料（findPlaceableSlot）",
                com.dddgn.alice.action.BlockInteraction.findPlaceableSlot(bot) >= 0);
        check(which + " 前提：放置位有可用面", com.dddgn.alice.action.BlockInteraction.hasPlacementFace(level, to.below()));
        check(which + " 前提：⭐ 过渡空间（`canSweepPlayer`）=" + sweep + " —— 这就是被测的那个谓词",
                which == Case.TRANSITION_BLOCKED ? !sweep : sweep);

        // ---- 生成层：直接问生产用的边生成器 ----
        SurfaceMovementProvider provider = new SurfaceMovementProvider();
        PathRequest request = PathRequest.withWorldModification(bot.getUUID().toString(), from, to, "collect-drops");
        MovementContext context = MovementContext.live(bot, level, request);
        check(which + " 前提：写入预算允许这条边（writesAllowed）",
                context.writesAllowed(MovementType.PLACE_STEP_AND_TRAVERSE));
        List<PlannedMovement> out = new ArrayList<>();
        provider.appendCandidates(context, from, out);
        PlannedMovement edge = null;
        for (PlannedMovement movement : out) {
            if (movement.movementType() == MovementType.PLACE_STEP_AND_TRAVERSE
                    && movement.fromFoot().equals(from) && movement.toFoot().equals(to)) {
                edge = movement;
            }
        }

        // ---- 执行层：同一个几何喂给执行工厂（K-4 双向一致） ----
        MovementSpec spec = PlannedMovementSpecs.toSpec(
                edge != null ? edge : new PlannedMovement(MovementType.PLACE_STEP_AND_TRAVERSE, from, to,
                        SYNTHETIC_COST, RecoverabilityEvaluator.levelOf(MovementType.PLACE_STEP_AND_TRAVERSE)),
                List.of("session_segment", "target_support", "target_body_clear", "target_head_clear"));
        com.dddgn.alice.pathing.core.MovementExecutionFactory.ValidationResult verdict = new PlaceStepAndTraverseExecutionFactory().validate(spec,
                new LiveExecutionContext(bot, level, "place-step-clearance", 0L, CompletionTolerance.EXACT,
                        "collect-drops"));

        findings.add(which + ":edge=" + (edge != null) + " sweep=" + sweep + " factoryValid=" + verdict.valid()
                + " code=" + verdict.failureCode());
        BotLog.info("[PlaceStepClear] case={} candidates={} edge={} sweep={} factoryValid={} code={}",
                which, out.size(), edge != null, sweep, verdict.valid(), verdict.failureCode());

        switch (which) {
            case TRANSITION_BLOCKED -> {
                check("⭐ ① 过渡空间被挡 ⇒ 不许生成这条 PLACE_STEP_AND_TRAVERSE 边"
                        + "（D-376 修复前会生成 ⇒ 真机上 bot 顶在格边界原地走 222 tick ×2）", edge == null);
                check("⭐ ① 执行工厂也必须**拒绝**同一个几何（实际 valid=" + verdict.valid()
                        + " code=" + verdict.failureCode() + "）", !verdict.valid());
                check("⭐ ① 拒绝码必须是 NO_SWEEP（实际 " + verdict.failureCode() + "）"
                                + "—— 若它因别的原因拒绝，说明本夹具的几何前提没立住",
                        verdict.failureCode() != null
                                && verdict.failureCode().startsWith("PLACE_STEP_AND_TRAVERSE_NO_SWEEP"));
            }
            case TRANSITION_CLEAR -> {
                check("⭐ ② 过渡空间通畅 ⇒ 这条边**必须**生成（反证：修复不许把合法的搭石斜下也禁掉）",
                        edge != null);
                check("⭐ ② 执行工厂必须**接受**（实际 valid=" + verdict.valid()
                        + " code=" + verdict.failureCode() + "）", verdict.valid());
                if (edge != null) {
                    check("② 这条边能构造出执行端 MovementSpec（K-4 可规划即可执行）", spec != null);
                }
                // ⭐ `D-394`：同一循环里再量一次「起跳头位」那类几何（复用场景，末尾用例跑）
                ascendHeadroomContract(level, from);
            }
        }
    }

    // ==================== 收尾 ====================

    private Task.Status finish() {
        done = true;
        ServerLevel level = bot.serverLevel();
        restore();
        edgeRestore(level);     // 机制 A 的临时几何也要还原（**失败路径同样走**）
        teleport(bot, START);
        bot.controller().stopMovement();

        boolean pass = failures.isEmpty();
        BotLog.info("[PlaceStepClear] SUMMARY checks={} failures={} 用例={} from={} to={} → {}｜发现：{}｜失败项：{}",
                checks, failures.size(), Case.values().length,
                from == null ? "-" : from.toShortString(), to == null ? "-" : to.toShortString(),
                pass ? "PASS" : "FAIL", findings, failures);
        if (observer != null && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 搭石斜下「过渡空间」取证 "
                    + (pass ? "PASS" : "FAIL") + "（2 用例，详见日志 [PlaceStepClear]）"));
        }
        return pass ? Task.Status.DONE : Task.Status.FAILED;
    }

    /**
     * ⭐ `D-394`（2026-09-22 真机：`ASCEND_NO_HEADROOM` ×75 + 无限重规划循环）：
     * **规划侧准入 ⇔ 执行侧准入**（同一几何，两侧必须一致）。
     *
     * <p>几何：bot 站在 `from`（头位空、**`from.above(2)` 实心** = 两格高坑道），要升到 `to = from.offset(1,1,0)`。
     * 对照 Baritone `MovementAscend.java:42` 的位置集 `{dest, src.above(2), dest.above()}` ⇒ 起跳需要第三格。
     *
     * <p>三条判据：① 规划侧 `canAscend` 必须为假；② 执行侧必须拒且码是 `ASCEND_NO_HEADROOM`；
     * ③ ⭐ **两侧必须一致** —— 不一致就是"规划出边、执行必拒 ⇒ 重规划又算出同一条边"的死循环。
     * 红臂：删掉 `canAscend` 里新加的 `from.above(2)` 检查 ⇒ ①③ 必红。
     */
    private void ascendHeadroomContract(ServerLevel level, BlockPos from) {
        BlockPos up = from.offset(1, 1, 0);
        java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> before =
                new java.util.LinkedHashMap<>();
        block(level, before, from.below(), Blocks.STONE);
        block(level, before, from, Blocks.AIR);
        block(level, before, from.above(), Blocks.AIR);
        block(level, before, from.above(2), Blocks.STONE);   // ⭐ 被测的那一格：第三格实心
        block(level, before, from.above(3), Blocks.AIR);
        block(level, before, up.below(), Blocks.STONE);      // up 可站
        block(level, before, up, Blocks.AIR);
        block(level, before, up.above(), Blocks.AIR);
        teleport(bot, from);

        boolean planSide = MovementHelper.canAscend(level, from, up);
        PlannedMovement movement = new PlannedMovement(MovementType.ASCEND, from, up, SYNTHETIC_COST,
                RecoverabilityEvaluator.levelOf(MovementType.ASCEND));
        com.dddgn.alice.pathing.core.MovementExecutionFactory.ValidationResult verdict =
                new AscendExecutionFactory().validate(
                        PlannedMovementSpecs.toSpec(movement, List.of("session_segment")),
                        new LiveExecutionContext(bot, level, "ascend-headroom", 0L,
                                CompletionTolerance.EXACT, "mine"));

        findings.add("ascend_headroom:from=" + from.toShortString() + " planSide=" + planSide
                + " execValid=" + verdict.valid() + " code=" + verdict.failureCode());
        BotLog.info("[AscendHeadroom] from={} up2=stone planSide={} execValid={} code={}",
                from.toShortString(), planSide, verdict.valid(), verdict.failureCode());
        check("ASCEND 起跳头位：规划侧 `canAscend` 必须为假（实际 " + planSide + "；`D-394` 修复前是 true）",
                !planSide);
        // 执行侧必须拒；码可以是 `ASCEND_NO_HEADROOM`（如果共享谓词没拦住、由它兜底）
        // 或 `ASCEND_INVALID_PRECONDITION`（共享谓词 `canAscend` 自己就拦住了 —— **修复后的正常形态**）。
        String code = String.valueOf(verdict.failureCode());
        check("ASCEND 起跳头位：执行侧必须拒（码 ∈ {ASCEND_NO_HEADROOM, ASCEND_INVALID_PRECONDITION}，"
                        + "实际 valid=" + verdict.valid() + " code=" + code + "）",
                !verdict.valid() && (code.startsWith("ASCEND_NO_HEADROOM")
                        || code.startsWith("ASCEND_INVALID_PRECONDITION")));
        check("⭐ ASCEND 起跳头位：**两侧准入必须一致**（规划=" + planSide + " 执行=" + verdict.valid()
                        + "）—— 不一致 ⇒ 规划出边 / 执行必拒 / 重规划又算同一条边 ⇒ 死循环（真机 75 次）",
                planSide == verdict.valid());

        for (java.util.Map.Entry<BlockPos, net.minecraft.world.level.block.state.BlockState> e : before.entrySet()) {
            level.setBlockAndUpdate(e.getKey(), e.getValue());
        }
    }

    /** 记录原状再放置（本夹具的临时改动**必须在方法内还原**）。 */
    private static void block(ServerLevel level,
                              java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> before,
                              BlockPos pos, net.minecraft.world.level.block.Block block) {
        BlockPos key = pos.immutable();
        before.putIfAbsent(key, level.getBlockState(key));
        level.setBlockAndUpdate(key, block.defaultBlockState());
    }

    /** 把本夹具动过的格子还原（**失败路径也走**）。 */
    private void restore() {
        if (from == null || to == null) {
            return;
        }
        ServerLevel level = bot.serverLevel();
        level.setBlockAndUpdate(to, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(to.above(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(to.above(2), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(to.below(),
                holeOriginal == null ? Blocks.STONE.defaultBlockState() : holeOriginal);
    }

    private void teleport(BotPlayer target, BlockPos foot) {
        ServerLevel level = target.serverLevel();
        target.teleportTo(level, foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                java.util.Set.of(), target.getYRot(), target.getXRot());
        target.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        target.controller().stopMovement();
    }

    private void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures.add(what);
        }
    }
}
