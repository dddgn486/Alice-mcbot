package com.dddgn.alice.task;

import com.dddgn.alice.action.BlockInteraction;
import com.dddgn.alice.action.TaskTargetProtection;
import com.dddgn.alice.action.WriteBudget;
import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.MovementType;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.MovementContext;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.search.PathingStats;
import com.dddgn.alice.pathing.core.search.PlannedMovement;
import com.dddgn.alice.pathing.core.search.PlanningStatus;
import com.dddgn.alice.pathing.core.search.SurfaceMovementProvider;
import com.dddgn.alice.protection.SafeZoneData;
import com.dddgn.alice.protection.ZoneAuthority;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * **`I5` 放置面**（2026-09-25 用户裁定「先治根因，让他不会在作业区放方块」）的落地判据。
 *
 * <h3>它钉的是哪一行真机事实</h3>
 * 第四轮真机（逐字证据见 {@code docs/reviews/2026-09-25-真机第四轮-自己搭的方块拦住回程.md}）：
 * <pre>
 * 23:17:20.605 [WRITE] break -65,54,181 stone   ← 鱼骨挖穿通道末列脚位格
 * 23:17:21.704 [WRITE] break -65,55,181 stone   ← 头位格 ⇒ 该列成为合格 2 格高通道
 * 23:17:31.257 [WRITE] place -65,54,181 cobblestone by=collect-drops:attempt0:STEP_PLACEMENT
 * 23:17:36.157 [PathingStats] descend_precondition=11 status=UNREACHABLE（nodes=3）
 * 23:17:36.204 return_failed ⇒ 整个作业 FAILED
 * </pre>
 *
 * <h3>因果链（本夹具逐条断言，不是"相关"）</h3>
 * <ol>
 *   <li>那一格是空气时：`appendFall` 的 drop=2 落点 = 通道脚位格 ⇒ `canStandCentered` 成立
 *       （落点空 + 底下石头 + 下落列净空 + `fallRecoverable` 的放置面/一次性方块够）⇒
 *       **一条零破坏的 `FALL` 边**；</li>
 *   <li>被填成圆石后：落点不可站 ⇒ `FALL` 消失；而 `DESCEND` 又因
 *       "2 格高通道（脚位/头位）+ 中继格**头位之上是天花板石头**"被拒
 *       （`canDescend` 要求 `mid.above()` 可穿）⇒ **回家的唯一那条边没了** ⇒
 *       自己的**一格**把整条路切断。</li>
 * </ol>
 *
 * <h3>本夹具的几何（自建 + 原样还原；与真机拓扑同形）</h3>
 * <pre>
 *            x=C.x     x+1
 *   y+4 ──  石        石      ← 盒顶（实心）
 *   y+3 ──  竖井(空)  口袋头位(空)
 *   y+2 ──  竖井(空)  口袋脚位(空) ← bot 站这里（矿簇掏出来的口袋）
 *   y+1 ──  头位(空)  石（口袋地板 = 真机 `-66,55,181`）
 *   y   ──  脚位C(空/圆石) 石      ← ⭐ **被测的那一格**（真机 `-65,54,181`）
 *   y-1 ──  石地板    石
 *          （北 = 目标方向 G = 通道再往北一格，真机里是 `junction=-65,54,172`）
 * </pre>
 * 实现方式是**整盒先填实心石头、再只凿出上面这几格空气** ⇒ 场景天然孤立：
 * 盒内不存在任何"计划外"的空中格或落脚面（前提 ② 自证这一条）。
 *
 * <h3>为什么必须有"负对照"（D 组）</h3>
 * skill/`AGENTS` 的实测事故（`D-254` 陷阱 #6）：**判据自己必须先被反向对照** ——
 * 永远绿的判据比没有判据更坏。所以本夹具在**撤掉作用域**时**必须**看见
 * "放置格落在通道层格里"的那条边（这正是真机 23:17:31 把圆石放进 `-65,54,181` 的路径）；
 * 看不见 ⇒ 红的是**夹具**（判据看不出这个机制），不是缺陷。
 *
 * <p>⚠️ 本夹具**只做规划级 + 授权级**判定（零 bot 物理、不跑执行器）⇒ 它证明的是
 * "那条边不存在 / 闸门拒绝 / 世界未被改写"；**不**声称真机观感（那要真人客户端）。
 */
public final class ChannelPlaceGuardCheckTask implements Task {

    /** 与 `PlaceStepDescendClearanceCheckTask` 同一片专用孤立区（不同 z 偏移，互不重叠）。 */
    private static final BlockPos START = new BlockPos(3000, -60, 4000);

    /** 通道末列**脚位格**（= 真机 `-65,54,181` 的形状位）。 */
    private static final BlockPos CORRIDOR_FOOT = START.offset(0, 0, 80);

    /** 盒：横竖各 ≥2 圈实心石（隔离），高度覆盖竖井与口袋外一圈。 */
    private static final int BOX_DX_MIN = -2;
    private static final int BOX_DX_MAX = 3;
    private static final int BOX_DZ_MIN = -3;
    private static final int BOX_DZ_MAX = 2;
    private static final int BOX_DY_MIN = -3;
    private static final int BOX_DY_MAX = 5;

    private static final int SETTLE_TICKS = 20;
    private static final int BUDGET_TICKS = 200;

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final List<String> findings = new ArrayList<>();
    private final Map<BlockPos, BlockState> before = new LinkedHashMap<>();

    private int ticks;
    private int checks;
    private int step;
    private boolean done;

    /** 通道末列脚位格（被填/被保护的那一格）。 */
    private BlockPos blocked;
    /** 目标格（通道再往北一格；真机里是 `junction=-65,54,172`）。 */
    private BlockPos goal;
    /** 矿簇口袋站位的脚位格（真机 `-66,56,181`）。 */
    private BlockPos pocket;
    /** 通道口正上方的竖井格（bot 追矿簇时自己挖上去的第一格，真机 `-65,56,181`）。 */
    private BlockPos shaft;

    /** "通道层格"集合（生产里由 `FishboneTemplate.cellSet()` 给出，这里取它的最小投影）。 */
    private Set<BlockPos> channelCells;

    public ChannelPlaceGuardCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "channel_place_guard";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(CORRIDOR_FOOT);
    }

    @Override
    public String failureReason() {
        return failures.isEmpty() ? "" : failures.get(0);
    }

    @Override
    public String terminalReason() {
        return failures.isEmpty() ? "ok" : "failed:" + failures.get(0);
    }

    @Override
    public Task.Status tick() {
        if (done) {
            return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        }
        if (++ticks > BUDGET_TICKS) {
            check("规划级/授权级判据必须在预算内跑完（" + BUDGET_TICKS + " tick）", false);
            return finish();
        }
        if (step == 0) {
            // ⚠️ 一次性方块必须 **≥ 落差** 才可能有 `FALL` 边（`fallRecoverable`：`countThrowaway >= drop`）
            // —— 真机那轮 bot 手里有圆石（刚放过一块、`[Recover]` 记了 4 次 PILLAR），
            // 给 1 个的话本夹具量到的会是"没材料"而不是"落点被自己堵掉"（首版踩到）。
            FixtureToolKit.ensureHotbarStack(bot, () -> new ItemStack(Items.COBBLESTONE, 64),
                    stack -> stack.is(Items.COBBLESTONE), 8, "channel-place-guard");
            buildScene(bot.serverLevel());
            step = 1;
            return Task.Status.RUNNING;
        }
        if (step <= SETTLE_TICKS) {
            step++;
            return Task.Status.RUNNING;
        }
        if (step == SETTLE_TICKS + 1) {
            ServerLevel level = bot.serverLevel();
            // 三条前提**必须自证**（skill §6.9.1：几何盒 / 世界假设 / 层归属，不能只写在心里）
            check("前提①：口袋可站（pocket=" + pocket.toShortString() + " canStandCentered="
                            + MovementHelper.canStandCentered(level, pocket)
                            + "）—— 假 ⇒ 几何建错了，红的是夹具",
                    MovementHelper.canStandCentered(level, pocket));
            check("前提②：场景孤立 —— 口袋唯一的水平开口是西侧竖井列（"
                            + shaft.toShortString() + " 可穿=" + MovementHelper.canWalkThrough(level, shaft)
                            + "、可站=" + MovementHelper.canWalkOn(level, shaft)
                            + "），其余三面与**天花板**（" + pocket.above(2).toShortString() + "）全是实心",
                    MovementHelper.canWalkThrough(level, shaft)
                            && !MovementHelper.canWalkOn(level, shaft)
                            && !MovementHelper.bodyPassable(level, pocket.offset(1, 0, 0))
                            && !MovementHelper.bodyPassable(level, pocket.offset(0, 0, 1))
                            && !MovementHelper.bodyPassable(level, pocket.offset(0, 0, -1))
                            && !MovementHelper.canWalkThrough(level, pocket.above(2)));
            check("前提③：区域级授权面不拦这一格（否则执行层判据会拿到 `ZONE_DENIED` 而不是 `CHANNEL_DENIED`）",
                    ZoneAuthority.regionRefusal(level, bot.getUUID(), blocked,
                            SafeZoneData.get(level.getServer()).protectionReason(level, blocked),
                            WriteReason.STEP_PLACEMENT, ZoneAuthority.Act.PLACE) == null);
            check("前提④：放置预算可用（否则执行层会被预算先拦下）", WriteBudget.placeAllowed(bot));
            step++;
            return Task.Status.RUNNING;
        }

        ServerLevel level = bot.serverLevel();
        runCausalCase(level);
        runFixedCase(level);
        runAdmissionCase(level);
        runAscentCase(level);
        return finish();
    }

    // ==================== A 因果前提：填掉那一格 ⇒ 回家的唯一那条边消失 ====================

    private void runCausalCase(ServerLevel level) {
        setBlock(level, blocked, Blocks.COBBLESTONE);
        check("A 前提（因果）：通道脚位格被填成圆石后 **不再可站**（canStandCentered="
                        + MovementHelper.canStandCentered(level, blocked)
                        + "）—— `appendFall` 的落点准入就是它 ⇒ 那条零破坏的 `FALL` 边必须消失",
                !MovementHelper.canStandCentered(level, blocked));

        PathPlan plan = new CorePathPlanner().plan(bot, level,
                PathRequest.withPlacement(bot.getUUID().toString(), pocket, goal, "fishbone-spur"));
        findings.add("A:cobble_plan=" + plan.status() + " nodes=" + plan.nodesExpanded()
                + " movements=" + movementTypes(plan) + " diag=[" + plan.diagnostics() + "]"
                + " fromPocket=" + candidatesFromPocket(level));
        BotLog.info("[ChannelGuard] A cobble_plan={} nodes={} movements={} diag={} fromPocket={}",
                plan.status(), plan.nodesExpanded(), movementTypes(plan), plan.diagnostics(),
                candidatesFromPocket(level));
        check("A 因果：把圆石放进自己的通道脚位格 ⇒ 回程 `UNREACHABLE`"
                        + "（真机原文 `descend_precondition=11 status=UNREACHABLE nodes=3`；"
                        + "实际 " + plan.status() + " nodes=" + plan.nodesExpanded()
                        + " movements=" + movementTypes(plan) + "）",
                plan.status() == PlanningStatus.UNREACHABLE);
        check("A 因果：这条失败的计划里**没有** `FALL`（落点被自己堵掉 ⇒ 该边不存在）",
                !containsType(plan, MovementType.FALL));
    }

    // ==================== B 修复后的状态（`I5` 保住那一格）⇒ 回程仍然可走且零破坏 ====================

    private void runFixedCase(ServerLevel level) {
        setBlock(level, blocked, Blocks.AIR);
        check("B 前提：`I5` 保住的形态 —— 通道脚位格仍是空气且可站（canStandCentered="
                        + MovementHelper.canStandCentered(level, blocked) + "）",
                level.getBlockState(blocked).isAir() && MovementHelper.canStandCentered(level, blocked));

        PathPlan plan = new CorePathPlanner().plan(bot, level,
                PathRequest.withPlacement(bot.getUUID().toString(), pocket, goal, "fishbone-spur"));
        findings.add("B:kept_plan=" + plan.status() + " nodes=" + plan.nodesExpanded()
                + " cost=" + plan.totalCost() + " movements=" + movementTypes(plan)
                + " diag=[" + plan.diagnostics() + "]"
                + " fallReady=" + fallPremises(level));
        BotLog.info("[ChannelGuard] B kept_plan={} nodes={} movements={} diag={} fallReady={}",
                plan.status(), plan.nodesExpanded(), movementTypes(plan), plan.diagnostics(),
                fallPremises(level));
        check("⭐ B 修复后：同一条回程**可达**（实际 " + plan.status() + "）", plan.reached());
        check("⭐ B 修复后：这条回程靠的是**零破坏的 `FALL`**，落点就是通道脚位格"
                        + "（movements=" + movementTypes(plan) + "）", containsFallInto(plan, blocked));
        check("⭐ B 修复后：整条回程**不含任何世界写入类移动**"
                        + "（`changesWorld()`：PILLAR/PLACE_STEP/BREAK_*/DOWNWARD；movements="
                        + movementTypes(plan) + "）", !containsAnyWorldWrite(plan));
    }

    // ==================== C 放置准入：谓词 + 反证（不许误伤） ====================

    private void runAdmissionCase(ServerLevel level) {
        TaskTargetProtection.beginChannel(bot, taskName(), channelCells::contains);
        check("C 前提：`I5` 作用域**真的装上了**（active=" + TaskTargetProtection.active(bot) + "）"
                        + "—— 装不上（例如被 `beginTask` 清掉）会让下面几条变成"
                        + "「没有保护」的假绿，所以这一条必须自证",
                TaskTargetProtection.active(bot));

        String footRefusal = BlockInteraction.placementRefusal(bot, blocked);
        String headRefusal = BlockInteraction.placementRefusal(bot, blocked.above());
        String supportRefusal = BlockInteraction.placementRefusal(bot, blocked.below());
        String pocketRefusal = BlockInteraction.placementRefusal(bot, pocket);
        findings.add("C:refusal foot=" + footRefusal + " head=" + headRefusal
                + " support=" + supportRefusal + " pocket=" + pocketRefusal);
        BotLog.info("[ChannelGuard] C refusal foot={} head={} support={} pocket={}",
                footRefusal, headRefusal, supportRefusal, pocketRefusal);

        check("⭐ C：通道**脚位格**被拒且码正确（期望 " + TaskTargetProtection.CHANNEL_CODE
                + "，实际 " + footRefusal + "）", TaskTargetProtection.CHANNEL_CODE.equals(footRefusal));
        check("⭐ C：通道**头位格**被拒且码正确（实际 " + headRefusal + "）",
                TaskTargetProtection.CHANNEL_CODE.equals(headRefusal));
        check("C 反证（不许误伤）：**支撑格**（脚位格下面那格 = 补地板的目标）必须**仍可放**"
                        + "（实际 " + supportRefusal + "）—— 否则「路面被自己挖掉后补回来」会被本保护禁掉",
                supportRefusal == null);
        check("C 反证（不许误伤）：口袋（通道层格之外）必须**仍可放**（实际 " + pocketRefusal + "）"
                        + "—— 修复不是「到处都不许放」", pocketRefusal == null);

        // 执行层：真正写世界的那一步也必须拒绝，且**世界未被改写**。
        // ⚠️ `placeAt` 里那道闸门排在触及检查（`reachable`）**之前** ⇒ 本条断言不依赖 bot 站位。
        BlockState stateBefore = level.getBlockState(blocked);
        BlockInteraction.PlaceResult result = BlockInteraction.placeAt(bot, level, blocked, false,
                WriteGrant.of(taskName(), WriteReason.STEP_PLACEMENT));
        BlockState stateAfter = level.getBlockState(blocked);
        findings.add("C:placeResult=" + result + " worldChanged=" + (stateBefore != stateAfter));
        check("⭐ C：执行层最后一道闸门拒绝（期望 CHANNEL_DENIED，实际 " + result + "）"
                        + "—— 「靠调用点自觉」的守卫迟早漏一处，所以真正写世界那一步必须自己拦",
                result == BlockInteraction.PlaceResult.CHANNEL_DENIED);
        check("⭐ C：被拒时**世界未被改写**（" + blocked.toShortString() + " 仍是 "
                        + stateAfter.getBlock().getName().getString() + "）", stateBefore == stateAfter);

        runPlannerCase(level);
    }

    // ==================== D 生成层：负对照（必须看得见）+ 修复后必须看不见 ====================

    private void runPlannerCase(ServerLevel level) {
        // ⚠️ 几何前提必须**在这里重新确立**：上一组（C）真跑了一次 `placeAt`，闸门失效时它**会真的写进去**
        // （红臂实测：那一格变实心 ⇒ 从实心格生成候选恒为 0 ⇒ 本组的"负对照"被污染成永远 0）。
        // 夹具的每一组都要自带前景，不许依赖上一组的副作用（skill §6.9.1 ③ 层归属）。
        setBlock(level, blocked, Blocks.AIR);
        // 负对照：**撤掉**作用域 ⇒ 这条边必须出现，否则本夹具永远绿、抓不住真机那个机制
        TaskTargetProtection.end(bot);
        int negativeHits = plannerChannelPlacementHits(level, "negative");
        check("⭐ D 负对照：撤掉 `I5` 作用域后，从通道脚位格出发的候选里**必须**出现"
                        + "「放置格落在通道层格里」的边（命中=" + negativeHits + "）"
                        + "—— 它正是真机 23:17:31 把圆石放进 `-65,54,181` 的那条路径；"
                        + "命中 0 ⇒ 红的是**夹具**（判据看不出这个机制），不是缺陷",
                negativeHits > 0);

        // 修复后：同一几何、同一请求，装上作用域 ⇒ 一条都不许有
        TaskTargetProtection.beginChannel(bot, taskName(), channelCells::contains);
        int hits = plannerChannelPlacementHits(level, "guarded");
        check("⭐ D 修复后：同一次候选生成里**零**条「放置格落在通道层格里」的边（命中=" + hits + "）",
                hits == 0);
        check("D：规划期剪枝必须**计数可见**（`PathingStats` 累计到 `place_channel_reserved`，实际 "
                        + PathingStats.totalsSnapshot().getOrDefault("place_channel_reserved", 0) + "）",
                PathingStats.totalsSnapshot().getOrDefault("place_channel_reserved", 0) > 0);
        findings.add("D:plannerHits negative=" + negativeHits + " guarded=" + hits);
    }

    /**
     * 从通道脚位格出发，用**生产用**的边生成器取候选，数"放置格落在通道层格里"的边。
     *
     * <p>放置格口径直接照搬执行器（唯一定义处）：`PILLAR` 放**自己那一格**、
     * `PLACE_STEP_AND_TRAVERSE` 放 `to.below()` —— 与 `PillarExecution` /
     * `PlaceStepAndTraverseExecution` 传给 `BlockInteraction.placeAt` 的参数一致。
     */
    private int plannerChannelPlacementHits(ServerLevel level, String tag) {
        teleport(bot, blocked);
        PathRequest request = PathRequest.withPlacement(bot.getUUID().toString(), blocked, goal, "fishbone-spur");
        MovementContext context = MovementContext.live(bot, level, request);
        check("D 前提（" + tag + "）：写预算允许放置边（writesAllowed(PILLAR)="
                        + context.writesAllowed(MovementType.PILLAR) + "）",
                context.writesAllowed(MovementType.PILLAR));
        List<PlannedMovement> out = new ArrayList<>();
        new SurfaceMovementProvider().appendCandidates(context, blocked, out);

        int hits = 0;
        for (PlannedMovement movement : out) {
            BlockPos placeCell = placeCellOf(movement);
            if (placeCell != null && channelCells.contains(placeCell)) {
                hits++;
            }
        }
        BotLog.info("[ChannelGuard] D {} candidates={} channelPlacementHits={} types={}",
                tag, out.size(), hits, movementTypes(out));
        return hits;
    }

    /**
     * 从口袋出发的候选（只读探针，长期保留）：把"为什么没有 `FALL` 边"变成**可判读的一行**。
     */
    private String candidatesFromPocket(ServerLevel level) {
        PathRequest request = PathRequest.withPlacement(bot.getUUID().toString(), pocket, goal, "fishbone-spur");
        MovementContext context = MovementContext.live(bot, level, request);
        List<PlannedMovement> out = new ArrayList<>();
        new SurfaceMovementProvider().appendCandidates(context, pocket, out);
        return movementTypes(out);
    }

    /**
     * `FALL` 边要成立所需的**每一个**子条件（只读探针，长期保留）—— `appendFall` 的闸门逐条对应：
     * ① 出口边缘可穿（`bodyPassable`）；② 下落列净空；③ 落点可站；④ 落点非流体/非底半砖；
     * ⑤ `fallRecoverable`（落点上方 drop+1 格全空 + 有放置面 + 一次性方块 ≥ drop）。
     *
     * <p>为什么把它写进判据：`descend_precondition` 那种**聚合计数**在真机上害我们查了一轮
     * （第四轮层③"未定"）—— 夹具这里不重复那个错误。
     */
    private String fallPremises(ServerLevel level) {
        BlockPos edge = pocket.offset(-1, 0, 0);
        BlockPos to = blocked;
        boolean edgePassable = MovementHelper.bodyPassable(level, edge);
        boolean clear = true;
        for (int y = pocket.getY() - 1; y > to.getY(); y--) {
            if (!MovementHelper.canWalkThrough(level, new BlockPos(to.getX(), y, to.getZ()))) {
                clear = false;
            }
        }
        boolean standable = MovementHelper.canStandCentered(level, to);
        boolean fluid = !level.getFluidState(to).isEmpty() || !level.getFluidState(to.above()).isEmpty();
        boolean recoverClear = true;
        for (int k = 1; k <= 3; k++) {
            if (!MovementHelper.canWalkThrough(level, to.above(k))) {
                recoverClear = false;
            }
        }
        return "[edge=" + edgePassable + " clear=" + clear + " standable=" + standable
                + " fluid=" + fluid + " recoverClear=" + recoverClear
                + " face=" + BlockInteraction.hasPlacementFace(level, to)
                + " throwaway=" + BlockInteraction.countThrowaway(bot) + "]";
    }

    /** 放置格（与执行器同口径）：`PILLAR` = 自己那一格；`PLACE_STEP_AND_TRAVERSE` = `to.below()`。 */
    private static BlockPos placeCellOf(PlannedMovement movement) {
        return switch (movement.movementType()) {
            case PILLAR -> movement.fromFoot();
            case PLACE_STEP_AND_TRAVERSE -> movement.toFoot().below();
            default -> null;
        };
    }

    // ==================== E 用户的问题：「禁掉放置后，他还能找到别的路上去吗」 ====================

    /**
     * 真机里 bot 是**垫脚爬上去**够矿簇掉落物的（`PILLAR@(-65,54,181)` + `ASCEND@(-66,56,181)`，
     * `cost=6.67 movements=2`）。本条把"上去"这件事单独量一次：
     *
     * <ul>
     *   <li><b>负对照（断言）</b>：撤掉作用域 ⇒ 上去的路**必须**存在，**且**靠"放置格落在通道层格里"；</li>
     *   <li><b>不变量（断言）</b>：装上之后 ⇒ 任何**写类**移动的放置格都不许落在通道层格里；</li>
     *   <li><b>结果（只报告、不断言）</b>：装上之后**到底上不上得去**进 `findings`。</li>
     * </ul>
     *
     * <p>为什么"上不去"不许写成断言：那是**产品取舍**（代价 = 那几件需要垫脚的掉落物，真机那轮它们
     * 本来也没捡到、被 `retire` 了），不是"缺陷"；而"上得去"是更好的结果。两者都不该让电池变红 ——
     * 判据只钉**不变量**，事实进台账等用户裁定。
     *
     * <p>⚠️ **实测出来的是 `SEARCH_LIMIT`，不是 `UNREACHABLE`**（首次真跑 `2026-09-25 23:51`）——
     * 按本项目红线（`SEARCH_LIMIT ≠ UNREACHABLE`，`D-329`）**不许**把它读成"证明上不去"：
     * 诚实的读法是"**便宜的替代路（挖穿石头）规划器在预算内没找到** ⇒ 可达性未知"。
     * 本夹具只报告这个状态，不做任何"不可达"的断言。
     */
    private void runAscentCase(ServerLevel level) {
        setBlock(level, blocked, Blocks.AIR);
        BlockPos goalFoot = pocket;

        // 负对照：撤掉 I5 ⇒ 老路（垫脚爬）必须还在
        TaskTargetProtection.end(bot);
        PathPlan unguarded = planToPocket(level, goalFoot);
        int unguardedHits = placementsIntoChannel(unguarded);
        findings.add("E:unguarded_ascent=" + unguarded.status() + " hits=" + unguardedHits
                + " movements=" + movementTypes(unguarded));
        BotLog.info("[ChannelGuard] E unguarded_ascent={} hits={} movements={}",
                unguarded.status(), unguardedHits, movementTypes(unguarded));
        check("⭐ E 负对照：撤掉 `I5` 时「上去」的路**必须**存在，且靠「把方块放进通道层格」"
                        + "（status=" + unguarded.status() + " 命中=" + unguardedHits
                        + " movements=" + movementTypes(unguarded) + "）"
                        + "—— 这正是真机 `PILLAR@-65,54,181 + ASCEND@-66,56,181`（cost=6.67 movements=2）那条路",
                unguarded.reached() && unguardedHits > 0);

        // 修复后：不变量（写类移动的放置格一律不许落在通道层格里）+ 结果只报告
        TaskTargetProtection.beginChannel(bot, taskName(), channelCells::contains);
        PathPlan guarded = planToPocket(level, goalFoot);
        int guardedHits = placementsIntoChannel(guarded);
        findings.add("⭐ E:guarded_ascent=" + guarded.status() + " hits=" + guardedHits
                + " movements=" + movementTypes(guarded)
                + "（用户的问题「他还能找到别的路上去吧」= 这一行；上不去是可接受结果 ——"
                + " 代价：需要垫脚的掉落物被 retire，真机那轮它们本来也没捡到）");
        BotLog.info("[ChannelGuard] E guarded_ascent={} hits={} movements={}",
                guarded.status(), guardedHits, movementTypes(guarded));
        check("⭐ E 不变量：装上 `I5` 后，**任何**写类移动的放置格都不许落在通道层格里"
                        + "（命中=" + guardedHits + " movements=" + movementTypes(guarded) + "）",
                guardedHits == 0);
    }

    /** CollectDrops 的信封（`withWorldModification`：含 PILLAR / BREAK_AND_* / ASCEND / FALL / PLACE_STEP）。 */
    private PathPlan planToPocket(ServerLevel level, BlockPos goalFoot) {
        return new CorePathPlanner().plan(bot, level,
                PathRequest.withWorldModification(bot.getUUID().toString(), blocked, goalFoot, "collect-drops"));
    }

    /** 计划里"放置格落在通道层格里"的写类移动数（放置格口径同 `placeCellOf`）。 */
    private int placementsIntoChannel(PathPlan plan) {
        int hits = 0;
        for (PlannedMovement movement : plan.movements()) {
            BlockPos placeCell = placeCellOf(movement);
            if (placeCell != null && channelCells.contains(placeCell)) {
                hits++;
            }
        }
        return hits;
    }

    // ==================== 场景（自建 + 原样还原） ====================

    /**
     * 整盒**先填实心石头**，再**只凿出设计里的那几格空气**（逐格与真机同形）。
     *
     * <p>为什么反过来做（先实心再凿）：孤立性变成**构造保证**而不是"我记得填了外围空气" ——
     * 盒内不存在任何计划外的空中格，因此也不存在计划外的落脚面/通路。
     */
    private void buildScene(ServerLevel level) {
        blocked = CORRIDOR_FOOT;
        shaft = blocked.above(2);
        pocket = blocked.offset(1, 2, 0);
        goal = blocked.offset(0, 0, -1);
        // 通道层格 = 每个单元的**脚位格 + 头位格**（生产里 `FishboneTemplate.cellSet()` 就是这个形状，
        // 只是长度覆盖整条主巷+支巷；本夹具只放一格目标 ⇒ 取它的最小投影）。
        channelCells = Set.of(blocked, blocked.above());

        for (int dx = BOX_DX_MIN; dx <= BOX_DX_MAX; dx++) {
            for (int dz = BOX_DZ_MIN; dz <= BOX_DZ_MAX; dz++) {
                for (int dy = BOX_DY_MIN; dy <= BOX_DY_MAX; dy++) {
                    setBlock(level, blocked.offset(dx, dy, dz), Blocks.STONE);
                }
            }
        }
        // 凿出通道（脚位格 + 头位格）：末列 `blocked` 与其北一格 `goal`
        for (BlockPos foot : List.of(blocked, goal)) {
            setBlock(level, foot, Blocks.AIR);
            setBlock(level, foot.above(), Blocks.AIR);
        }
        // 凿出通道口正上方的竖井两格（bot 追矿簇时自己挖上去的；`fallRecoverable` 的 k=1..3 全要空：
        // 真机正是因为它自己把 `-65,56/57,181` 挖空了，那条 `FALL` 才会存在）
        setBlock(level, shaft, Blocks.AIR);
        setBlock(level, shaft.above(), Blocks.AIR);
        // 凿出矿簇口袋（2 格高：脚位 + 头位；真机 `-66,56,181` 是掏空的矿簇格，其下 `-66,55,181` 是石头）
        setBlock(level, pocket, Blocks.AIR);
        setBlock(level, pocket.above(), Blocks.AIR);
        teleport(bot, pocket);
    }

    private void setBlock(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.Block block) {
        BlockPos key = pos.immutable();
        before.putIfAbsent(key, level.getBlockState(key));
        level.setBlockAndUpdate(key, block.defaultBlockState());
    }

    private void teleport(BotPlayer player, BlockPos foot) {
        ServerLevel level = player.serverLevel();
        player.teleportTo(level, foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                Set.of(), player.getYRot(), player.getXRot());
        player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        player.controller().stopMovement();
    }

    // ==================== 判读助手 ====================

    private static boolean containsType(PathPlan plan, MovementType type) {
        return plan.movements().stream().anyMatch(m -> m.movementType() == type);
    }

    private static boolean containsFallInto(PathPlan plan, BlockPos foot) {
        return plan.movements().stream().anyMatch(m -> m.movementType() == MovementType.FALL
                && m.toFoot().equals(foot));
    }

    private static boolean containsAnyWorldWrite(PathPlan plan) {
        return plan.movements().stream().anyMatch(m -> m.movementType().changesWorld());
    }

    private static String movementTypes(PathPlan plan) {
        return movementTypes(plan.movements());
    }

    private static String movementTypes(List<PlannedMovement> movements) {
        List<String> out = new ArrayList<>();
        for (PlannedMovement movement : movements) {
            out.add(movement.movementType() + "@" + movement.toFoot().toShortString());
        }
        return out.toString();
    }

    // ==================== 收尾 ====================

    private Task.Status finish() {
        done = true;
        ServerLevel level = bot.serverLevel();
        // `I5` 作用域必须撤（**失败路径也走**）：留着它 = 后面的电池步一律放不了方块（跨步污染）
        TaskTargetProtection.end(bot);
        for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
            level.setBlockAndUpdate(entry.getKey(), entry.getValue());
        }
        teleport(bot, START);
        bot.controller().stopMovement();

        boolean pass = failures.isEmpty();
        BotLog.info("[ChannelGuard] SUMMARY checks={} failures={} blocked={} → {}｜发现：{}｜失败项：{}",
                checks, failures.size(), blocked == null ? "-" : blocked.toShortString(),
                pass ? "PASS" : "FAIL", findings, failures);
        if (observer != null && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] I5 放置面（通道层格不许放方块）取证 "
                    + (pass ? "PASS" : "FAIL") + "（详见日志 [ChannelGuard]）"));
        }
        return pass ? Task.Status.DONE : Task.Status.FAILED;
    }

    private void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures.add(what);
        }
    }
}
