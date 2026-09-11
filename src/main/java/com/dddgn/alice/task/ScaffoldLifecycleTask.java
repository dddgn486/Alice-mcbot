package com.dddgn.alice.task;

import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.ledger.WorldModLedger;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.MovementType;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.task.mining.MiningBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * J7 Step 1：**脚手架生命周期闭环**（§12.3 / §11-① 要素①–④）。
 *
 * <p>一句话：**搭着方块爬上去 → 在高处干活 → 仍然站在顶上自上而下拆掉 → 落地，世界不留残留**。
 *
 * <p>本步只证明"机制闭环"，不碰树（以树干为脚手架 = Step 2）：
 * <ol>
 *   <li>显式授权入口 {@link PathRequest#climbApproach}（只由本任务开启，不进默认集合）；</li>
 *   <li>方块预算 {@link #CLIMB_BUDGET}（计划里 `PILLAR` 步数 ≤ 预算，否则该目标拒绝）；</li>
 *   <li>返回保证：柱子**就是**下行路线（拆一格、`DOWNWARD` 落一格），因此不需要额外留路；</li>
 *   <li>残留策略：**会话内**拆完（复用 {@link RestoreScopeTask}：自上而下、只拆账本内我方 `TEMP`、
 *       `placedState` 不匹配即跳过、侧拆兜底），任务结束时该 scope 必须为空。</li>
 * </ol>
 *
 * <p>输出：`[Scaffold] SUMMARY pillar=?/12 torn=? remaining=? residue=? target=? grounded=? → PASS|FAIL`。
 * 拆不完时**不许静默**：`terminalReason=scaffold_left_behind` 进日志与任务终态。
 */
public final class ScaffoldLifecycleTask implements Task {

    /** 与 `scaffold_course_terrain` 对齐。 */
    public static final BlockPos START_FOOT = new BlockPos(38, 64, 46);
    /**
     * 爬升终点：**竖壁壁顶** (39,69,46)（脚位 y=69 ⇒ 在 x=38 列上 5 次 `PILLAR` + 1 次 `TRAVERSE`）。
     *
     * <p>为什么终点在壁上而不是柱顶：`PILLAR` 的规划前提是"当前格有放置面"，而壁体正好给出
     * 每一层的放置面（见场景注释）；壁顶又天然**可站**，是攀爬的合法目标脚位。
     */
    public static final BlockPos CLIMB_GOAL_FOOT = new BlockPos(39, 69, 46);
    /** 高处目标（地面够不到，只有壁顶够得着）。 */
    private static final BlockPos TARGET = new BlockPos(40, 70, 46);
    /** 我方柱子所在的列（残留检查）：y=64..68 是放置出来的方块，y=69 是站位空气格。 */
    private static final int COLUMN_X = START_FOOT.getX();
    private static final int COLUMN_Z = START_FOOT.getZ();
    private static final int COLUMN_Y_MIN = 64;
    private static final int COLUMN_Y_MAX = 68;
    /** 方块预算（用户 2026-09-11 裁定：默认 12，高树够用；模组超高树不在范围）。 */
    public static final int CLIMB_BUDGET = 12;
    /**
     * 单个阶段的安全上限（tick）。必须**大于** {@code RestoreScopeTask} 自己的内部预算
     * （`BASE_TICKS + 每格 450 tick`，5 格 ≈ 2350），否则外层会先把正常拆除掐成超时。
     */
    private static final int PHASE_BUDGET_TICKS = 4000;
    /** 收尾收集的 tick 预算（与 `RestoreScopeTask` 的收尾收集一致）。 */
    private static final int COLLECT_BUDGET_TICKS = 600;
    /** 高处目标的掉落物落点附近（收尾收集的锚点）。 */
    private static final BlockPos DROP_ANCHOR = new BlockPos(40, 64, 46);
    /** 场景包围盒（世界事实扫描：地上还有没有掉落物）。 */
    private static final net.minecraft.world.phys.AABB SCENE_BOX =
            new net.minecraft.world.phys.AABB(32, 58, 38, 45, 75, 55);

    private enum Phase { SETUP, CLIMB, MINE, TEARDOWN, COLLECT, ASSERT, DONE }

    private final BotPlayer bot;
    private final ScopeBuffer scope;
    private Phase phase = Phase.SETUP;
    private int ticks;
    private String failure = "";
    private String terminalReason = "";
    private String scopeId;
    private int pillarCount;
    private int pendingBefore;
    private String climbStatus = "-";
    private String mineStatus = "-";
    private String restoreReason = "-";
    /** 开始拆除时 bot 是否**仍在柱顶**（§12.3 的前提；若采矿阶段自己跑下去了，这里就会是 false）。 */
    private boolean onTopBeforeTeardown;
    private PathRetryRunner climber;
    private MineTask miner;
    private RestoreScopeTask restore;
    private CollectDropsTask collector;
    private int collectedDrops;

    public ScaffoldLifecycleTask(BotPlayer bot, ScopeBuffer scope) {
        this.bot = bot;
        this.scope = scope;
    }

    @Override
    public String taskName() {
        return "ScaffoldLifecycle";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(TARGET);
    }

    @Override
    public String failureReason() {
        return failure;
    }

    /** 见类注释：拆不完时必须能判读（`scaffold_left_behind`）。 */
    public String terminalReason() {
        return terminalReason;
    }

    @Override
    public Task.Status tick() {
        return switch (phase) {
            case SETUP -> setup();
            case CLIMB -> climb();
            case MINE -> mine();
            case TEARDOWN -> teardown();
            case COLLECT -> collect();
            case ASSERT -> assertResult();
            case DONE -> Task.Status.DONE;
        };
    }

    // ==================== 阶段 ====================

    private Task.Status setup() {
        ServerLevel level = bot.serverLevel();
        var server = level.getServer();
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(),
                "function alice_test:scaffold_course_terrain");
        bot.teleportTo(level, START_FOOT.getX() + 0.5D, START_FOOT.getY(), START_FOOT.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        scope.begin(START_FOOT, 24, bot.getUUID());
        // 夹具职责：一次性方块（攀爬消耗）+ 石镐（采高处目标）
        com.dddgn.alice.item.FixtureToolKit.ensureHotbarStack(bot,
                () -> new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE),
                stack -> stack.is(net.minecraft.world.item.Items.COBBLESTONE), CLIMB_BUDGET, "cobblestone");
        com.dddgn.alice.item.FixtureToolKit.ensureHotbarTool(bot,
                () -> new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE),
                stack -> stack.is(net.minecraft.tags.ItemTags.PICKAXES), "pickaxe");
        scopeId = WorldModLedger.currentScope(server, bot.getUUID());

        // 要素②：先规划一次，数出这条攀爬路线要花几个方块；超预算 → 该目标拒绝
        PathRequest request = climbRequest();
        PathPlan plan = new CorePathPlanner().plan(bot, level, request);
        pillarCount = (int) plan.movements().stream()
                .filter(m -> m.movementType() == MovementType.PILLAR)
                .count();
        BotLog.info("[Scaffold] plan status={} movements={} pillar={}/{} target={} start={}",
                plan.status(), plan.movements().size(), pillarCount, CLIMB_BUDGET,
                TARGET.toShortString(), START_FOOT.toShortString());
        if (!plan.reached()) {
            failure = "climb_plan_failed:" + plan.status();
            phase = Phase.ASSERT;
            return Task.Status.RUNNING;
        }
        if (pillarCount == 0 || pillarCount > CLIMB_BUDGET) {
            failure = pillarCount == 0 ? "climb_plan_not_climbing" : "climb_budget_exceeded";
            BotLog.warn("[Scaffold] climb 被拒 {} pillar={}/{}", failure, pillarCount, CLIMB_BUDGET);
            phase = Phase.ASSERT;
            return Task.Status.RUNNING;
        }
        climber = new PathRetryRunner(bot, request, PathRetryRunner.DEFAULT_MAX_REPLANS, "scaffold-climb");
        phase = Phase.CLIMB;
        return Task.Status.RUNNING;
    }

    private Task.Status climb() {
        if (++ticks > PHASE_BUDGET_TICKS) {
            climbStatus = "TASK_TIMEOUT";
            BotLog.warn("[Scaffold] climb 超时（{} tick）→ 断言", ticks);
            climber = null;
            phase = Phase.ASSERT;
            return Task.Status.RUNNING;
        }
        var state = climber.tick();
        if (state == PathRetryRunner.State.RUNNING) {
            return Task.Status.RUNNING;
        }
        var result = climber.result();
        climbStatus = result == null ? state.name() : result.status().name();
        BlockPos at = MovementHelper.footCell(bot.serverLevel(), bot);
        BotLog.info("[Scaffold] climb_end state={} status={} replans={} foot={} goal={}",
                state, climbStatus, climber.replans(), at.toShortString(), CLIMB_GOAL_FOOT.toShortString());
        climber = null;
        if (!at.equals(CLIMB_GOAL_FOOT)) {
            failure = "climb_not_at_top:" + climbStatus;
            phase = Phase.ASSERT;
            return Task.Status.RUNNING;
        }
        pendingBefore = WorldModLedger.pendingTemporary(bot.serverLevel().getServer(), scopeId).size();
        BotLog.info("[Scaffold] climbed foot={} ledgerPending={} blocks={}（准备在高处干活）",
                at.toShortString(), pendingBefore,
                com.dddgn.alice.action.BlockInteraction.countThrowaway(bot));
        phase = Phase.MINE;
        return Task.Status.RUNNING;
    }

    private Task.Status mine() {
        if (miner == null) {
            // collectDrops=false：本步只证明脚手架生命周期，不追掉落物（掉落物会落回地面，
            // 追它=自己先跑下去，正好破坏"仍在顶上时拆除"的前提）
            miner = new MineTask(bot, TARGET, scope,
                    MiningBudget.forTarget(bot, bot.serverLevel(), TARGET, false),
                    WriteGrant.of(taskName(), WriteReason.EXPECTED_TARGET));
            ticks = 0;
            return Task.Status.RUNNING;
        }
        if (++ticks > PHASE_BUDGET_TICKS) {
            mineStatus = "TASK_TIMEOUT";
            BotLog.warn("[Scaffold] mine 超时（{} tick）→ 断言", ticks);
            miner = null;
            phase = Phase.ASSERT;
            return Task.Status.RUNNING;
        }
        Task.Status status = miner.tick();
        if (status == Task.Status.RUNNING) {
            return Task.Status.RUNNING;
        }
        mineStatus = status.name() + (miner.failureReason() == null || miner.failureReason().isBlank()
                ? "" : ":" + miner.failureReason());
        BlockPos at = MovementHelper.footCell(bot.serverLevel(), bot);
        BotLog.info("[Scaffold] mine_end status={} foot={} target_air={}（仍应在柱顶）",
                mineStatus, at.toShortString(), bot.serverLevel().getBlockState(TARGET).isAir());
        miner = null;
        ticks = 0;
        phase = Phase.TEARDOWN;
        return Task.Status.RUNNING;
    }

    /** 要素④：**会话内、仍在顶上**自上而下拆除（复用已验证的 RestoreScopeTask）。 */
    private Task.Status teardown() {
        if (restore == null) {
            BlockPos at = MovementHelper.footCell(bot.serverLevel(), bot);
            onTopBeforeTeardown = at.equals(CLIMB_GOAL_FOOT);
            BotLog.info("[Scaffold] teardown_start foot={} on_top={} pending={} scope={}（§12.3 要求人还在柱顶）",
                    at.toShortString(), onTopBeforeTeardown,
                    at.toShortString(),
                    WorldModLedger.pendingTemporary(bot.serverLevel().getServer(), scopeId).size(), scopeId);
            restore = new RestoreScopeTask(bot, scope, scopeId);
            ticks = 0;
            return Task.Status.RUNNING;
        }
        if (++ticks > PHASE_BUDGET_TICKS) {
            restoreReason = "TASK_TIMEOUT";
            BotLog.warn("[Scaffold] teardown 超时（{} tick）→ 断言", ticks);
            restore = null;
            phase = Phase.ASSERT;
            return Task.Status.RUNNING;
        }
        Task.Status status = restore.tick();
        if (status == Task.Status.RUNNING) {
            return Task.Status.RUNNING;
        }
        restoreReason = restore.terminalReason() == null || restore.terminalReason().isBlank()
                ? status.name() : restore.terminalReason();
        BotLog.info("[Scaffold] teardown_end status={} reason={} foot={}", status, restoreReason,
                MovementHelper.footCell(bot.serverLevel(), bot).toShortString());
        restore = null;
        // 拆除成功 → 收尾收集（高处作业的掉落物落在地面，必须**拆完落地后**再收）
        phase = failure.isEmpty() ? Phase.COLLECT : Phase.ASSERT;
        return Task.Status.RUNNING;
    }

    /**
     * 收尾收集（用户 2026-09-11 指出：高处作业的掉落物掉到地面，拆除阶段的收集够不到它）。
     *
     * <p>顺序即规则：**先完成会话内拆除并落地，再收集**。此时 bot 在地面，掉落物就在旁边。
     * 这里复用 {@link CollectDropsTask}（只收"由本次破坏配对到的掉落物"、best-effort、worldMod=false）。
     */
    private Task.Status collect() {
        if (collector == null) {
            collector = new CollectDropsTask(bot, DROP_ANCHOR, scope, java.util.List.of(), false,
                    COLLECT_BUDGET_TICKS);
            BotLog.info("[Scaffold] collect_start anchor={} foot={} live_drops={}"
                            + "（拆完落地后再收）",
                    DROP_ANCHOR.toShortString(),
                    MovementHelper.footCell(bot.serverLevel(), bot).toShortString(),
                    scope.liveDrops().size());
            ticks = 0;
            return Task.Status.RUNNING;
        }
        if (++ticks > COLLECT_BUDGET_TICKS + 40) {
            BotLog.warn("[Scaffold] collect 超时（{} tick）→ 断言", ticks);
            collector = null;
            phase = Phase.ASSERT;
            return Task.Status.RUNNING;
        }
        Task.Status status = collector.tick();
        if (status == Task.Status.RUNNING) {
            return Task.Status.RUNNING;
        }
        collectedDrops = collector.collected();
        BotLog.info("[Scaffold] collect_end status={} collected={} live_drops={}",
                status, collectedDrops, scope.liveDrops().size());
        collector = null;
        phase = Phase.ASSERT;
        return Task.Status.RUNNING;
    }

    private Task.Status assertResult() {
        ServerLevel level = bot.serverLevel();
        int remaining = WorldModLedger.pendingTemporary(level.getServer(), scopeId).size();
        int residue = 0;
        for (int y = COLUMN_Y_MIN; y <= COLUMN_Y_MAX; y++) {
            if (!level.getBlockState(new BlockPos(COLUMN_X, y, COLUMN_Z)).isAir()) {
                residue++;
            }
        }
        boolean targetGone = level.getBlockState(TARGET).isAir();
        int dropsLeft = scope.liveDrops().size();
        int itemsOnGround = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                SCENE_BOX).size();
        boolean noDrops = dropsLeft == 0 && itemsOnGround == 0;
        BlockPos at = MovementHelper.footCell(level, bot);
        boolean grounded = at.getY() == START_FOOT.getY();
        int torn = Math.max(0, pendingBefore - remaining);
        boolean pass = failure.isEmpty()
                && remaining == 0 && residue == 0 && targetGone && grounded && onTopBeforeTeardown
                && torn == pillarCount && pillarCount > 0 && noDrops;
        if (!pass && failure.isEmpty()) {
            failure = "SCAFFOLD_LIFECYCLE_FAILED pillar=" + pillarCount + " torn=" + torn
                    + " remaining=" + remaining + " residue=" + residue
                    + " targetGone=" + targetGone + " grounded=" + grounded
                    + " onTopBeforeTeardown=" + onTopBeforeTeardown
                    + " dropsLeft=" + dropsLeft + " itemsOnGround=" + itemsOnGround;
        }
        if (remaining > 0) {
            terminalReason = "scaffold_left_behind:" + remaining;
        } else if (pass) {
            terminalReason = "scaffold_closed";
        }
        BotLog.info("[Scaffold] SUMMARY pillar={}/{} torn={} remaining={} residue={} target={}"
                        + " collected={} drops_left={} on_top_at_teardown={} grounded={}"
                        + " climb={} mine={} restore={} → {}",
                pillarCount, CLIMB_BUDGET, torn, remaining, residue, targetGone ? "gone" : "present",
                collectedDrops, itemsOnGround, onTopBeforeTeardown, grounded,
                climbStatus, mineStatus, restoreReason, pass ? "PASS" : "FAIL");
        phase = Phase.DONE;
        return pass ? Task.Status.DONE : Task.Status.FAILED;
    }

    private PathRequest climbRequest() {
        return PathRequest.climbApproach(bot.getUUID().toString(), START_FOOT, CLIMB_GOAL_FOOT,
                "scaffold-lifecycle");
    }
}
