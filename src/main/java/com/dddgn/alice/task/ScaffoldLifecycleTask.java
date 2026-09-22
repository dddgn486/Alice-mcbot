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
    /**
     * 方块预算（用户 2026-09-11 裁定：默认 12，高树够用；模组超高树不在范围）。
     *
     * <p>D-111 起，这个值由**能力信封**统一承载（{@code MiningProfile.DEFAULT_GAIN_BLOCK_BUDGET}），
     * 不再各写一份——本夹具的"爬到指定作业平台"是另一种能力（多步 climb），其预算沿用同一个默认值。
     */
    public static final int CLIMB_BUDGET = com.dddgn.alice.task.mining.MiningProfile
            .DEFAULT_GAIN_BLOCK_BUDGET;
    /**
     * 单个阶段的安全上限（tick）。必须**大于** {@code RestoreScopeTask} 自己的内部预算
     * （`BASE_TICKS + 每格 450 tick`，5 格 ≈ 2350），否则外层会先把正常拆除掐成超时。
     */
    private static final int PHASE_BUDGET_TICKS = 4000;
    /** ③ 落地扫尾的 tick 预算（与 `RestoreScopeTask` 的收尾收集一致）。 */
    private static final int COLLECT_BUDGET_TICKS = 600;
    /** ① 就地扫尾的 tick 预算（best-effort：够不到就留给 ③，不长等）。 */
    /**
     * ① 就地扫尾的 tick 预算**按同一口径推导**（R3 / D-123）：本夹具扫尾不加高（信封为
     * `STANDABLE_ONLY`）⇒ 预算 = 固定开销 + 待收物数×单价。原先写死 200。
     */
    private int sweepBudgetTicks = com.dddgn.alice.task.CollectDropsTask
            .suggestedSweepTicks(1, null);
    /** 高处目标的掉落物落点附近（收尾收集的锚点）。 */
    private static final BlockPos DROP_ANCHOR = new BlockPos(40, 64, 46);
    /** 场景包围盒（世界事实扫描：地上还有没有掉落物）。 */
    private static final net.minecraft.world.phys.AABB SCENE_BOX =
            new net.minecraft.world.phys.AABB(32, 58, 38, 45, 75, 55);

    private enum Phase { SETUP, CLIMB, MINE, SWEEP_UP, TEARDOWN, SWEEP_GROUND, ASSERT,
                         CLIMB_PLAN, RECOVER_DECIDE, RECOVER_ASSERT, DONE }

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
    /** ① 就地扫尾（仍在脚手架上）收进背包的数量。 */
    private int sweptUp;
    /** ③ 落地扫尾（拆除完成、回到地面）收进背包的数量。 */
    private int sweptGround;
    // ==== 第二轮（J7 Step 3 / D-127）：**故意不拆**，验证崩溃兜底判定与续做 ====
    /** 1 = 正常生命周期；2 = 残留验证轮。 */
    private int round = 1;
    /** ⭐ `Z1`/`D-398`：夹具自摆的"保护区 + 任务区"前提（结束复位）。 */
    private FixtureZone.Handle zone;
    /** 第一轮是否通过（第二轮断言要把两轮一起算）。 */
    private boolean phase1Pass;
    private String recoveryDecision = "-";
    private int recoveryLeftBefore = -1;
    private int recoveryLeftAfter = -1;
    private int recoveryResidue = -1;

    public ScaffoldLifecycleTask(BotPlayer bot, ScopeBuffer scope) {
        this.bot = bot;
        this.scope = scope;
    }

    /** K-3：把"当前寻路段是否安全"透传给取消方（`/alice stop` 会据此延后到安全点）。 */
    @Override
    public boolean safeToCancel() {
        return climber == null || climber.safeToCancel();
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
        Task.Status status = switch (phase) {
            case SETUP -> setup();
            case CLIMB -> climb();
            case MINE -> mine();
            case SWEEP_UP -> sweepUp();
            case TEARDOWN -> teardown();
            case SWEEP_GROUND -> sweepGround();
            case ASSERT -> assertResult();
            case CLIMB_PLAN -> planClimbForRound2();
            case RECOVER_DECIDE -> recoverDecide();
            case RECOVER_ASSERT -> assertRecovery();
            case DONE -> Task.Status.DONE;
        };
        // 夹具纪律：**结束复位**（含失败路径）—— 本夹具自己摆的"保护区 + 任务区"前提必须还回去。
        if (status != Task.Status.RUNNING && zone != null) {
            zone.release();
        }
        return status;
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
        // 夹具职责（用户 2026-09-11 建议）：**先清空背包**再发料 —— ①避免 bot 背包爆满；
        // ②让"一次性方块库存变化（recovered）"这类账目干净（否则历史余料会把账搅浑）
        com.dddgn.alice.item.FixtureToolKit.resetInventory(bot);
        // 夹具职责：一次性方块（攀爬消耗）+ 石镐（采高处目标）
        com.dddgn.alice.item.FixtureToolKit.ensureHotbarStack(bot,
                () -> new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE),
                stack -> stack.is(net.minecraft.world.item.Items.COBBLESTONE), CLIMB_BUDGET, "cobblestone");
        com.dddgn.alice.item.FixtureToolKit.ensureHotbarTool(bot,
                () -> new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE),
                stack -> stack.is(net.minecraft.tags.ItemTags.PICKAXES), "pickaxe");
        scopeId = WorldModLedger.currentScope(server, bot.getUUID());
        // ⭐ `D-398`/`Z1`（2026-09-22）：**本夹具验的是"保护区内"的建拆同权** —— 区外按裁定
        // **不记账、不回收**（`recordPlacement` 直接跳过）⇒ 不摆这个前提，"拆干净"这条判据根本
        // 无从成立（实测：`scaffold` 曾 `pillar=4 torn=0 residue=4` 红过一次）。
        // 前提 = 认领场景区块 + 声明 L2 任务区封套（`FixtureZone` 有完整理由）。
        zone = FixtureZone.protect(level, bot.getUUID(),
                BlockPos.containing(SCENE_BOX.minX, SCENE_BOX.minY, SCENE_BOX.minZ),
                BlockPos.containing(SCENE_BOX.maxX, SCENE_BOX.maxY, SCENE_BOX.maxZ), "region_lumber");
        if (!zone.ok()) {
            // **不许静默降级**：前提没摆成 ⇒ 如实失败（否则后面的判据会在错误的世界前提上做判断）
            failure = "FIXTURE_ZONE_PREMISE_FAILED " + zone.describe();
            BotLog.warn("[Scaffold] {}", failure);
            phase = Phase.ASSERT;
            return Task.Status.RUNNING;
        }

        // 要素②：先规划一次，数出这条攀爬路线要花几个方块；超预算 → 该目标拒绝
        prepareClimbPlan();
        return Task.Status.RUNNING;
    }

    /**
     * 规划一次攀爬（第一轮 SETUP 与第二轮 CLIMB_PLAN 共用）：数 PILLAR 步数、超预算即拒绝。
     *
     * @return true = 计划可用（已把 {@code phase} 置为 CLIMB）；false = 拒绝（已置 failure 与 ASSERT）
     */
    private boolean prepareClimbPlan() {
        ServerLevel level = bot.serverLevel();
        PathRequest request = climbRequest();
        PathPlan plan = new CorePathPlanner().plan(bot, level, request);
        pillarCount = (int) plan.movements().stream()
                .filter(m -> m.movementType() == MovementType.PILLAR)
                .count();
        BotLog.info("[Scaffold] plan status={} movements={} pillar={}/{} target={} start={} round={}",
                plan.status(), plan.movements().size(), pillarCount, CLIMB_BUDGET,
                TARGET.toShortString(), START_FOOT.toShortString(), round);
        if (!plan.reached() || pillarCount == 0 || pillarCount > CLIMB_BUDGET) {
            failure = !plan.reached() ? "climb_plan_failed:" + plan.status()
                    : (pillarCount == 0 ? "climb_plan_not_climbing" : "climb_budget_exceeded");
            BotLog.warn("[Scaffold] climb 被拒 {} pillar={}/{}", failure, pillarCount, CLIMB_BUDGET);
            phase = Phase.ASSERT;
            return false;
        }
        climber = new PathRetryRunner(bot, request, PathRetryRunner.DEFAULT_MAX_REPLANS, "scaffold-climb");
        phase = Phase.CLIMB;
        return true;
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
        // 第二轮：爬上去后**故意不拆**，交给崩溃兜底验证
        phase = round == 2 ? Phase.RECOVER_DECIDE : Phase.MINE;
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
        // 作业期间不追掉落物（`collectDrops=false`）→ 产物收在 ① 就地扫尾这一处，
        // 因为"够得到"这件事会随着拆除而消失（实测：停在壁柱顶面的掉落物，拆完就再也够不到）
        phase = Phase.SWEEP_UP;
        return Task.Status.RUNNING;
    }

    /**
     * ① **就地扫尾**：作业完成后、拆除之前，仍在脚手架上，只收"此刻够得到"的产物。
     *
     * <p>为什么必须有这一步：掉落物有 `pickupDelay`（约 10 tick），而拆除是每格 ~14 tick 往下走；
     * 如果只等"拆完落地再收"，**作业点平台上的产物会先变得够不到**（实测 `retire reason=MOVEMENT_FAILED
     * itemPos=39,69,46`）。本步是 best-effort：够不到的不硬追，留给 ③ 或如实报 `drops_left`。
     *
     * <p>不额外加"不许下行"的限制：越界的风险由已有的前提断言 `on_top_at_teardown` 兜住
     * （真走下去了就直接 FAIL 并暴露，而不是悄悄改语义）。
     */
    private Task.Status sweepUp() {
        if (collector == null) {
            sweepBudgetTicks = com.dddgn.alice.task.CollectDropsTask.suggestedSweepTicks(
                    scope.liveDrops().size(), null);
            collector = new CollectDropsTask(bot, TARGET, scope, java.util.List.of(), false,
                    sweepBudgetTicks);
            BotLog.info("[Scaffold] sweep_up_start anchor={} foot={} live_drops={} budgetTicks={}"
                            + "（仍在架上，就地收）",
                    TARGET.toShortString(),
                    MovementHelper.footCell(bot.serverLevel(), bot).toShortString(),
                    scope.liveDrops().size(), sweepBudgetTicks);
            ticks = 0;
            return Task.Status.RUNNING;
        }
        if (++ticks > sweepBudgetTicks + 40) {
            BotLog.warn("[Scaffold] sweep_up 超时（{}/{} tick）→ 继续拆除", ticks, sweepBudgetTicks);
            collector = null;
            phase = Phase.TEARDOWN;
            return Task.Status.RUNNING;
        }
        Task.Status status = collector.tick();
        if (status == Task.Status.RUNNING) {
            return Task.Status.RUNNING;
        }
        sweptUp = collector.collected();
        BotLog.info("[Scaffold] sweep_up_end status={} swept={} live_drops={} foot={}",
                status, sweptUp, scope.liveDrops().size(),
                MovementHelper.footCell(bot.serverLevel(), bot).toShortString());
        collector = null;
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
        // 拆除成功 → ③ 落地扫尾（拆除过程中落地的产物与拆下来的方块）；
        // 第二轮（残留验证）拆完直接进第二轮断言
        if (round == 2) {
            phase = Phase.RECOVER_ASSERT;
        } else {
            phase = failure.isEmpty() ? Phase.SWEEP_GROUND : Phase.ASSERT;
        }
        return Task.Status.RUNNING;
    }

    /**
     * 收尾收集（用户 2026-09-11 指出：高处作业的掉落物掉到地面，拆除阶段的收集够不到它）。
     *
     * <p>顺序即规则：**先完成会话内拆除并落地，再收集**。此时 bot 在地面，掉落物就在旁边。
     * 这里复用 {@link CollectDropsTask}（只收"由本次破坏配对到的掉落物"、best-effort、worldMod=false）。
     */
    private Task.Status sweepGround() {
        if (collector == null) {
            ServerLevel level = bot.serverLevel();
            // D-124 起**不需要**再手工收养（D-108 的 `adoptExistingDrops`）：重开区间会继承
            // "仍活着且落在区间内"的我方掉落物归属，收尾收集自然看得见它们。
            // 顺序仍是"拆完落地 → 再收"。
            scope.begin(DROP_ANCHOR, 8, bot.getUUID());
            BotLog.info("[Scaffold] sweep_ground_start anchor={} foot={} live_drops={}"
                            + "（拆完落地后再收）",
                    DROP_ANCHOR.toShortString(),
                    MovementHelper.footCell(level, bot).toShortString(),
                    scope.liveDrops().size());
            collector = new CollectDropsTask(bot, DROP_ANCHOR, scope, java.util.List.of(), false,
                    COLLECT_BUDGET_TICKS);
            ticks = 0;
            return Task.Status.RUNNING;
        }
        if (++ticks > COLLECT_BUDGET_TICKS + 40) {
            BotLog.warn("[Scaffold] sweep_ground 超时（{} tick）→ 断言", ticks);
            collector = null;
            phase = Phase.ASSERT;
            return Task.Status.RUNNING;
        }
        Task.Status status = collector.tick();
        if (status == Task.Status.RUNNING) {
            return Task.Status.RUNNING;
        }
        sweptGround = collector.collected();
        BotLog.info("[Scaffold] sweep_ground_end status={} swept={} live_drops={}",
                status, sweptGround, scope.liveDrops().size());
        collector = null;
        phase = Phase.ASSERT;
        return Task.Status.RUNNING;
    }

    private Task.Status assertResult() {
        if (round == 2) {
            return assertRecovery();
        }
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
        java.util.List<net.minecraft.world.entity.item.ItemEntity> strandedItems =
                level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, SCENE_BOX);
        int itemsOnGround = strandedItems.size();
        String strandedPos = strandedItems.isEmpty() ? "-"
                : strandedItems.stream().map(item -> item.blockPosition().toShortString())
                        .collect(java.util.stream.Collectors.joining(","));
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
                    + " dropsLeft=" + dropsLeft + " itemsOnGround=" + itemsOnGround
                    + " strandedAt=" + strandedPos;
        }
        if (remaining > 0) {
            terminalReason = "scaffold_left_behind:" + remaining;
        } else if (pass) {
            terminalReason = "scaffold_closed";
        }
        BotLog.info("[Scaffold] SUMMARY pillar={}/{} torn={} remaining={} residue={} target={}"
                        + " sweep_up={} sweep_ground={} drops_left={} stranded={}"
                        + " on_top_at_teardown={} grounded={} climb={} mine={} restore={} → {}",
                pillarCount, CLIMB_BUDGET, torn, remaining, residue, targetGone ? "gone" : "present",
                sweptUp, sweptGround, itemsOnGround, strandedPos, onTopBeforeTeardown, grounded,
                climbStatus, mineStatus, restoreReason, pass ? "PASS" : "FAIL");
        if (!pass) {
            phase = Phase.DONE;
            return Task.Status.FAILED;
        }
        // 第一轮通过 ⇒ 进入第二轮：**再爬一次、故意不拆**，验证 §12.4 的崩溃兜底
        phase1Pass = true;
        round = 2;
        BotLog.info("[Scaffold] 第一轮通过 ⇒ 第二轮：再爬上柱顶后**故意不拆**，验证崩溃兜底（J7 Step 3）");
        phase = Phase.CLIMB_PLAN;
        return Task.Status.RUNNING;
    }

    /** 第二轮：只用已有台地重新规划一次攀爬（**不重放场景**，免得把第一轮的结论洗掉）。 */
    private Task.Status planClimbForRound2() {
        // 第二轮要再搭一次柱子：把一次性方块补回预算（第一轮拆下来的应已回收，这里兜底保证夹具自足）
        com.dddgn.alice.item.FixtureToolKit.ensureHotbarStack(bot,
                () -> new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE),
                stack -> stack.is(net.minecraft.world.item.Items.COBBLESTONE),
                CLIMB_BUDGET, "cobblestone");
        if (!prepareClimbPlan()) {
            phase = Phase.RECOVER_ASSERT;   // failure 已置 ⇒ 第二轮断言会如实 FAIL
            return Task.Status.RUNNING;
        }
        return Task.Status.RUNNING;
    }

    /** 判定：账本残留是否"就近可续做"（**故意不在这轮自己拆**，先看判定）。 */
    private Task.Status recoverDecide() {
        recoveryLeftBefore = WorldModLedger
                .pendingTemporary(bot.serverLevel().getServer(), scopeId).size();
        recoveryDecision = com.dddgn.alice.bot.BotManager.teardownRecoveryDecision(bot);
        BotLog.info("[Scaffold] recover_decide decision={} pending={}", recoveryDecision,
                recoveryLeftBefore);
        if (!"ready".equals(recoveryDecision)) {
            failure = "recovery_decision_" + recoveryDecision;
            phase = Phase.RECOVER_ASSERT;
            return Task.Status.RUNNING;
        }
        // 复用既有 TEARDOWN 相位做"续做"（round==2 ⇒ 它完成后会回到 RECOVER_ASSERT）
        phase = Phase.TEARDOWN;
        return Task.Status.RUNNING;
    }

    /** 第二轮断言：续做是否真的把这次未闭合的会话收尾（账本 + 世界事实）。 */
    private Task.Status assertRecovery() {
        ServerLevel level = bot.serverLevel();
        recoveryLeftAfter = WorldModLedger
                .pendingTemporary(level.getServer(), scopeId).size();
        recoveryResidue = 0;
        for (int y = COLUMN_Y_MIN; y <= COLUMN_Y_MAX; y++) {
            if (!level.getBlockState(new BlockPos(COLUMN_X, y, COLUMN_Z)).isAir()) {
                recoveryResidue++;
            }
        }
        boolean pass = phase1Pass && failure.isEmpty()
                && "ready".equals(recoveryDecision)
                && recoveryLeftBefore > 0 && recoveryLeftAfter == 0 && recoveryResidue == 0;
        if (!pass && failure.isEmpty()) {
            failure = "TEARDOWN_RECOVERY_FAILED decision=" + recoveryDecision
                    + " before=" + recoveryLeftBefore + " after=" + recoveryLeftAfter
                    + " residue=" + recoveryResidue;
        }
        BotLog.info("[Scaffold] RECOVERY SUMMARY decision={} ledger_before={} ledger_after={}"
                        + " residue={} restore={} → {}",
                recoveryDecision, recoveryLeftBefore, recoveryLeftAfter, recoveryResidue,
                restoreReason, pass ? "PASS" : "FAIL");
        phase = Phase.DONE;
        return pass ? Task.Status.DONE : Task.Status.FAILED;
    }

    private PathRequest climbRequest() {
        return PathRequest.climbApproach(bot.getUUID().toString(), START_FOOT, CLIMB_GOAL_FOOT,
                "scaffold-lifecycle");
    }
}
