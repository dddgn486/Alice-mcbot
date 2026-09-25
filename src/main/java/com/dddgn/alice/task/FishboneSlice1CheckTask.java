package com.dddgn.alice.task;

import com.dddgn.alice.action.WriteAudit;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.job.fishbone.FishboneJob;
import com.dddgn.alice.job.fishbone.FishboneTemplate;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.search.PathingStats;
import com.dddgn.alice.perception.ScopeBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * **鱼骨切片 1 夹具（`D-386`）：主巷 + 返回 —— 判据 `C1`/`C3`/`C4`/`C6`**。
 *
 * <p>设计原文 = {@code docs/plans/2026-09-21-鱼骨挖矿计划.md} §6（判据）/§7（切片 1）。
 * 本夹具**自建孤立场景**（不依赖真机存档、不依赖前序步留下的地形 —— `CheckModule` 的纪律）。
 *
 * <h2>三条臂（同一个夹具一次跑完，`alice-scene-based-testing` §3「一次动作覆盖全部」）</h2>
 * <table border="1">
 *   <tr><th>臂</th><th>场景</th><th>期望</th><th>判据</th></tr>
 *   <tr><td>① `MAIN_TUNNEL`</td><td>实心石体 + 地板，起点在石面前</td><td>`template_complete`</td>
 *       <td>`C1` 模板=事实 / `C3` 搜索规模 / `C4` 可返回</td></tr>
 *   <tr><td>② `BEDROCK_BLOCKED`</td><td>同上，但在第 `BEDROCK_UNIT` 个单元放**基岩**</td><td>`main_blocked:&lt;码&gt;` 且**先返回起点**</td>
 *       <td>`C6` 诚实失败（那一格与之后**零改动**）</td></tr>
 *   <tr><td>③ `START_SEALED`</td><td>bot 被封在远处**密闭石壳**里，起点本身可站</td><td>`start_unreachable`</td>
 *       <td>`C6` 诚实失败（**零世界改动**）</td></tr>
 * </table>
 *
 * <h2>⭐ 本夹具额外承担的两条**新读数**（`survey/32` 发现二，计划 §5 自标的"待验证假设"）</h2>
 * <p>计划 §5 把"每格一次小规划的开销"标成了**风险**（"可实测"）：鱼骨的卖点是"每格 1 次、距离恒 1 格"，
 * 但**"极廉价"是假设不是事实**。因此本夹具顺手量两条，零额外成本：
 * <ol>
 *   <li>**每轮规划耗时**：`PathingStats.scale()` 的 `millis/plans`（读数口是唯一漏斗
 *       {@code CorePathPlanner#plan}）；</li>
 *   <li>**每格 tick 成本**：`job.elapsedTicks() / advanced`。</li>
 * </ol>
 * <p>它们同时把 `C3` 从"规模恒定"升级成"**耗时也可断言**"。
 *
 * <h2>§6.9.1 三条前提（写下来并自断言）</h2>
 * <ol>
 *   <li><b>几何盒</b>：{@link #ORIGIN} 起、{@link #BODY_MIN_X}..{@link #BODY_MAX_X} 的实心石体，
 *       地板在 `y-1`、空气圈在盒外一圈；建场景与断言用**同一个盒**（{@link #box()}）；</li>
 *   <li><b>世界假设</b>：场地在 y=80 的高空（118 与 2400/3600 一带是本项目其它夹具的保留区，
 *       见 `MineRunMetricsCheckTask:105` 的 3400/2000）⇒ **不与任何场景/自然地形相连**；</li>
 *   <li><b>层归属</b>：断言落在**世界事实**（方块表 + `WriteAudit`）上，不落在"任务自报完成"上
 *       （`JobKindContract`/`D-349` 的口径）。</li>
 * </ol>
 *
 * <h2>⚠️ 收尾（§6.9.2：失败路径也走）</h2>
 * <p>三臂共用同一块场地 ⇒ 每臂开始时**先清盒再重建**（臂之间不互相继承地形），
 * 结束时清回空气 + 拆 forceload + 停输入 + 送回进来时的脚位。
 */
public final class FishboneSlice1CheckTask implements Task {

    // ==================== 场景常量（几何，唯一真值） ====================

    /** 起点脚位（= 模板的 `startFoot`；同时是 `RETURN` 的目标）。 */
    private static final BlockPos ORIGIN = new BlockPos(3600, 80, 2400);

    /** 主巷方向（固定向东；切片 3 的真机入口才会由玩家朝向决定）。 */
    private static final Direction DIR = Direction.EAST;

    /** 主巷长度（**单元数**，每单元 `HEIGHT` 格 ⇒ 一共 `MAIN_LENGTH * HEIGHT` 格要挖）。 */
    private static final int MAIN_LENGTH = 6;

    /** 巷道净高（`FishboneTemplate.DEFAULT_HEIGHT`）。 */
    private static final int HEIGHT = FishboneTemplate.DEFAULT_HEIGHT;

    /** 实心石体的 x 范围（相对 `ORIGIN`）：从 `+1` 到 `+MAIN_LENGTH`。 */
    private static final int BODY_MIN_X = 1;
    private static final int BODY_MAX_X = MAIN_LENGTH;

    /** 体侧石体厚度（z = ±1）⇒ 巷道**天生是 1 格宽**，bot 挖不出去（也是"没乱挖"的判据）。 */
    private static final int BODY_HALF_Z = 1;

    /** 盒子的外扩（**建场景 / 断言 / 清场三处同一个盒**）。 */
    private static final int BOX_MIN_DX = -2;
    private static final int BOX_MAX_DX = MAIN_LENGTH + 3;
    private static final int BOX_MIN_DY = -2;
    private static final int BOX_MAX_DY = HEIGHT + 2;
    private static final int BOX_HALF_DZ = 3;

    /** 臂 ② 放基岩的单元序号（1-based）⇒ 它**在前方**，主巷必须在那里如实停下。 */
    private static final int BEDROCK_UNIT = 3;

    /** 臂 ③ 密闭石壳的中心（远离主巷场地；同一个 forceload 覆盖区）。 */
    private static final BlockPos SEALED = ORIGIN.offset(MAIN_LENGTH + 6, 0, 0);

    // ==================== 预算 ====================

    /** 夹具总预算（EXTRA 步；CORE 不跑）。 */
    private static final int BUDGET_TICKS = 3600;

    /** 单臂的作业 tick 上限（传给 `FishboneJob.maxTicks`；夹具自己的护栏更早生效）。 */
    private static final int JOB_MAX_TICKS = 900;

    /** 每臂的 settle 上限（等落地 + 等区块热）。 */
    private static final int SETTLE_CAP = 40;

    /**
     * ⭐ `C3` 的常数：**每次推进允许的规划次数上限**（`plans ≤ 本值 × 推进格数`）。
     *
     * <p>**按实测定，不是拍脑袋**（2026-09-24 首轮）：6 格推进实测 `plans=20`（含 PREPARE 1 次 +
     * RETURN 1 次 + 每格 1 次 ≈ 14；多出来的 6 次是走位重规划）⇒ **3.3 次/格**。
     * 取 6 = 留 ~1.8× 余量。**复核触发**：某次正当改动让这里变红 ⇒ 先看 `plans` 涨在哪
     *（`[PathRetry] plan` 逐行有 requester），确认是"新能力"还是"搜索失控"，再决定调常数还是查缺陷。
     */
    private static final int PLANS_PER_ADVANCE = 6;

    /**
     * ⭐ `C3` 的常数：**每格允许的展开节点上限**（`nodes ≤ 本值 × 推进格数`）。
     *
     * <p>**按实测定**：6 格推进实测 `nodes=31` ⇒ **5.2 节点/格**（"每格 1 次、距离恒 1 格"的
     * 直接后果：每次规划只展开 1~2 个节点）。取 40 = 留 ~7.7× 余量（返回段随巷道变长会线性涨，
     * 那时这条是第一个该复算的）。
     */
    private static final int NODES_PER_ADVANCE = 40;

    /** 每格 tick 成本的上限（**读数**，不是判据 —— 它是 `survey/32` 发现二要量出来的东西）。 */
    private static final int TICK_COST_REPORT_ONLY = 200;

    private enum Arm { MAIN_TUNNEL, BEDROCK_BLOCKED, START_SEALED }

    private enum Phase { SETUP_ARM, SETTLE, RUN, ASSERT, CLEANUP, DONE }

    private static final Arm[] ARMS = Arm.values();

    // ==================== 依赖 ====================

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final ScopeBuffer scope;

    // ==================== 状态 ====================

    private Phase phase = Phase.SETUP_ARM;
    private int armIndex;
    private int ticks;
    private int settleTicks;
    private int checks;
    private final List<String> failures = new ArrayList<>();

    private FishboneTemplate template;
    private FishboneJob job;
    private BlockPos entryFoot;

    /** 本臂的读数（断言与 SUMMARY 共用）。 */
    private String armVerdict = "";
    private PathingStats.Scale scaleBefore;
    private PathingStats.Scale scaleDelta;
    private int auditBefore;
    private int auditDeltaBreaks;
    private int auditOutsideTemplate;
    private int worldDiffOutsideExpected;
    private int ticksPerAdvance;

    public FishboneSlice1CheckTask(BotPlayer bot, ServerPlayer observer, ScopeBuffer scope) {
        this.bot = bot;
        this.observer = observer;
        this.scope = scope;
    }

    @Override
    public String taskName() {
        return "FishboneSlice1Check";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(ORIGIN.above());
    }

    @Override
    public String failureReason() {
        return String.join(" | ", failures);
    }

    @Override
    public String terminalReason() {
        return failures.isEmpty() ? "passed" : "failed";
    }

    @Override
    public Task.Status tick() {
        if (++ticks > BUDGET_TICKS && phase != Phase.ASSERT && phase != Phase.CLEANUP && phase != Phase.DONE) {
            check("夹具护栏：" + BUDGET_TICKS + " tick 内必须跑完（实际 " + ticks + "，arm="
                    + ARMS[Math.min(armIndex, ARMS.length - 1)] + " job="
                    + (job == null ? "null" : job.terminalReason()) + "）", false);
            phase = Phase.CLEANUP;
        }
        return switch (phase) {
            case SETUP_ARM -> setupArm();
            case SETTLE -> settle();
            case RUN -> runJob();
            case ASSERT -> assertArm();
            case CLEANUP -> cleanup();
            case DONE -> failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
        };
    }

    // ==================== 建场景 ====================

    private Task.Status setupArm() {
        ServerLevel level = bot.serverLevel();
        Arm arm = ARMS[armIndex];

        forceload(level, true);
        if (entryFoot == null) {
            entryFoot = bot.blockPosition();
        }
        clearBox(level);
        switch (arm) {
            case MAIN_TUNNEL -> buildTunnel(level, false);
            case BEDROCK_BLOCKED -> buildTunnel(level, true);
            case START_SEALED -> buildSealedPocket(level);
        }

        // 传送（**先传送再清背包**：`resetInventory` 会把东西掉在脚下，顺序反了就把落物留在上一处）
        BlockPos startFoot = ORIGIN;
        teleport(level, arm == Arm.START_SEALED ? SEALED : startFoot);
        FixtureToolKit.resetInventory(bot);
        FixtureToolKit.ensurePickaxe(bot);
        bot.controller().stopMovement();

        template = FishboneTemplate.main(startFoot, DIR, MAIN_LENGTH);
        // ⭐ 切片 2 第一步（支巷几何）的纯几何判据：只在第一个臂跑一次（它不读世界、与臂无关）
        if (armIndex == 0) {
            spurGeometryChecks();
        }

        // ⭐ 前提自断言（§6.9.1）：场景没落地 ⇒ 后面所有判据都不可解读 ⇒ 当场记失败，不当缺陷证据
        boolean sceneOk = arm == Arm.START_SEALED
                ? level.getBlockState(SEALED).isAir() && level.getBlockState(SEALED.below()).is(Blocks.STONE)
                : level.getBlockState(ORIGIN).isAir() && level.getBlockState(ORIGIN.below()).is(Blocks.STONE)
                        && level.getBlockState(template.footOf(1)).is(Blocks.STONE);
        check("前提（" + arm + "）：场景已落地（起点空气 + 脚下实心"
                + (arm == Arm.START_SEALED ? "" : " + 第 1 格实体") + "）", sceneOk);

        scaleBefore = PathingStats.scale();
        auditBefore = WriteAudit.snapshot().size();
        BotLog.info("[Fishbone1] CHECK arm={} template={} 场景={} tick={} scaleBefore={}",
                arm, template.describe(), arm == Arm.START_SEALED ? "密闭石壳" : "实心石体+地板",
                ticks, scaleBefore.describe());
        settleTicks = 0;
        phase = Phase.SETTLE;
        return Task.Status.RUNNING;
    }

    /** 实心石体 + 地板（`bedrock=true` 时在第 `BEDROCK_UNIT` 个单元嵌基岩）。 */
    private void buildTunnel(ServerLevel level, boolean bedrock) {
        // 地板（整块，防掉落物掉进虚空 —— `MineRunMetricsCheckTask:284` 的同一条血的教训）
        for (int dx = BOX_MIN_DX; dx <= BOX_MAX_DX; dx++) {
            for (int dz = -BOX_HALF_DZ; dz <= BOX_HALF_DZ; dz++) {
                level.setBlock(ORIGIN.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        // 石体（巷道在 z=0，两侧 z=±1 是墙 ⇒ 1 格宽）
        for (int cx = BODY_MIN_X; cx <= BODY_MAX_X; cx++) {
            for (int dz = -BODY_HALF_Z; dz <= BODY_HALF_Z; dz++) {
                for (int dy = 0; dy < HEIGHT; dy++) {
                    boolean isTunnel = dz == 0;
                    boolean isBedrock = bedrock && cx == BEDROCK_UNIT && isTunnel;
                    BlockState state = isBedrock ? Blocks.BEDROCK.defaultBlockState()
                            : Blocks.STONE.defaultBlockState();
                    level.setBlock(ORIGIN.offset(cx, dy, dz), state, 3);
                }
            }
        }
        // 起点口袋（实心体之外那一格必须空，否则 bot 站不进去）
        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = 0; dy < HEIGHT; dy++) {
                level.setBlock(ORIGIN.offset(0, dy, dz), Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    /** 臂 ③：远处密闭石壳（1×1×2 空腔 + 一圈实心）——**起点本身可站**，但 `bot` 出不去。 */
    private void buildSealedPocket(ServerLevel level) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    level.setBlock(SEALED.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), 3);
                }
            }
        }
        level.setBlock(SEALED, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(SEALED.above(), Blocks.AIR.defaultBlockState(), 3);
        // 起点那一格也得可站（否则测的就不是"到不了"而是"起点不合法" ⇒ 两条判据会互相冒充）
        level.setBlock(ORIGIN.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(ORIGIN, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(ORIGIN.above(), Blocks.AIR.defaultBlockState(), 3);
    }

    // ==================== 跑作业 ====================

    private Task.Status settle() {
        settleTicks++;
        bot.controller().stopMovement();
        if (FixturePremise.settledOnGround(bot, settleTicks) || settleTicks > SETTLE_CAP) {
            job = new FishboneJob(bot, template, scope, JOB_MAX_TICKS);
            phase = Phase.RUN;
        }
        return Task.Status.RUNNING;
    }

    private Task.Status runJob() {
        Task.Status status = job.tick();
        if (status == Task.Status.RUNNING) {
            return Task.Status.RUNNING;
        }
        armVerdict = job.terminalReason();
        scaleDelta = PathingStats.scale().delta(scaleBefore);
        List<WriteAudit.Entry> audit = WriteAudit.snapshot();
        auditDeltaBreaks = 0;
        auditOutsideTemplate = 0;
        Set<BlockPos> allowed = template.cellSet();
        for (int i = auditBefore; i < audit.size(); i++) {
            WriteAudit.Entry entry = audit.get(i);
            if (!"break".equals(entry.action())) {
                continue;
            }
            auditDeltaBreaks++;
            if (!allowed.contains(entry.pos())) {
                auditOutsideTemplate++;
            }
        }
        ticksPerAdvance = job.advancedUnits() == 0 ? -1 : job.elapsedTicks() / job.advancedUnits();
        BotLog.info("[Fishbone1] RUN arm={} verdict={} status={} ticks={} advance={}/{} mined={}"
                        + " skipped={} auditBreaks={} auditOutsideTemplate={} scale={}"
                        + " ticksPerAdvance={}",
                ARMS[armIndex], armVerdict, status, job.elapsedTicks(), job.advancedUnits(),
                template.mainLength(), job.minedCells(), job.skippedCells(), auditDeltaBreaks,
                auditOutsideTemplate, scaleDelta.describe(), ticksPerAdvance);
        phase = Phase.ASSERT;
        return Task.Status.RUNNING;
    }

    // ==================== 断言 ====================

    private Task.Status assertArm() {
        ServerLevel level = bot.serverLevel();
        Arm arm = ARMS[armIndex];
        List<String> expected = expectedBlocks(arm);
        worldDiffOutsideExpected = diffExpected(level, expected);

        switch (arm) {
            case MAIN_TUNNEL -> assertMainTunnel(level);
            case BEDROCK_BLOCKED -> assertBedrock(level);
            case START_SEALED -> assertStartSealed(level);
        }

        // 每臂都过的两条公共判据
        check("（" + arm + "）场景与期望表**逐格一致**（差异格=" + worldDiffOutsideExpected
                + "；期望表 = 地板 + 两侧墙 + 远端墙 + 密闭壳，其余全空气）",
                worldDiffOutsideExpected == 0);
        check("（" + arm + "）**世界写入全部落在模板格内**（`WriteAudit` 破坏条目 " + auditDeltaBreaks
                + " 条，其中模板外 " + auditOutsideTemplate + " 条）—— 这是 `C5` 同族的最强形态："
                + "**不是「没超过预算」，而是「一格都没多」**", auditOutsideTemplate == 0);

        BotLog.info("[Fishbone1] ARM {} {} verdict={} scale={} ticksPerAdvance={}",
                arm, failures.isEmpty() ? "PASS" : "FAIL", armVerdict, scaleDelta.describe(), ticksPerAdvance);

        armIndex++;
        if (armIndex >= ARMS.length) {
            phase = Phase.CLEANUP;
        } else {
            phase = Phase.SETUP_ARM;
        }
        return Task.Status.RUNNING;
    }

    /** 臂 ①：`C1` / `C3` / `C4`。 */
    private void assertMainTunnel(ServerLevel level) {
        check("① `C1` 终态理由 = `template_complete`（实际 " + armVerdict + "）",
                "template_complete".equals(armVerdict));
        // C1 的正向半边：**模板格逐格都是空气**
        int notAir = 0;
        for (BlockPos cell : template.cells()) {
            if (!level.getBlockState(cell).isAir()) {
                notAir++;
            }
        }
        check("① `C1` 模板 " + template.cells().size() + " 格**全部为空气**（实测非空气 " + notAir + " 格）",
                notAir == 0);
        check("① `C1` 推进记账自洽：`advance=" + job.advancedUnits() + " == mainLength="
                        + template.mainLength() + "`，`mined + skipped=" + (job.minedCells() + job.skippedCells())
                        + " == 模板格数 " + template.cells().size() + "`",
                job.advancedUnits() == template.mainLength()
                        && job.minedCells() + job.skippedCells() == template.cells().size());
        check("① `C1` 真的挖了东西（mined=" + job.minedCells() + " > 0）—— 0 说明「模板生成被关掉也能过」",
                job.minedCells() > 0);

        // C4：回到起点 + 返回段零破坏（返回段若有破坏 ⇒ 破坏点会落在模板外 ⇒ 上面那条公共判据已经咬住）
        double dxz = Math.abs(bot.getX() - (template.startFoot().getX() + 0.5D))
                + Math.abs(bot.getZ() - (template.startFoot().getZ() + 0.5D));
        check("① `C4` 结束时**脚位回到起点**（XZ 偏差 " + fmt(dxz) + " ≤ 1.5；"
                        + "脚位=" + bot.blockPosition().toShortString() + " 起点="
                        + template.startFoot().toShortString() + "）", dxz <= 1.5D);

        // ⭐ C3：搜索规模恒定 + 零 SEARCH_LIMIT
        long adv = template.advanceCells();
        check("① `C3` **零 `SEARCH_LIMIT`**（`SEARCH_LIMIT ≠ UNREACHABLE`：预算是预算，可达性是可达性）"
                        + "｜searchLimit=" + scaleDelta.searchLimits(),
                scaleDelta.searchLimits() == 0);
        check("① `C3` 规划次数与推进格数成正比：plans=" + scaleDelta.plans() + " ≤ "
                        + PLANS_PER_ADVANCE + " × " + adv + " = " + (PLANS_PER_ADVANCE * adv),
                scaleDelta.plans() <= PLANS_PER_ADVANCE * adv);
        check("① `C3` 展开节点与推进格数成正比：nodes=" + scaleDelta.nodes() + " ≤ "
                        + NODES_PER_ADVANCE + " × " + adv + " = " + (NODES_PER_ADVANCE * adv),
                scaleDelta.nodes() <= NODES_PER_ADVANCE * adv);
        // ⭐ survey/32 发现二 的两条新读数（**报事实**：它们回答"每格一次小规划到底多便宜"）
        check("① 读数（`survey/32` 发现二）**每轮规划耗时** `" + fmt(scaleDelta.millisPerPlan())
                        + " ms/次`（plans=" + scaleDelta.plans() + " millis=" + scaleDelta.millis()
                        + "）· **每轮展开** `" + fmt(scaleDelta.nodesPerPlan()) + " 节点/次`",
                scaleDelta.plans() > 0 && scaleDelta.millisPerPlan() < 1000.0D);
        check("① 读数（`survey/32` 发现二）**每格 tick 成本** `" + ticksPerAdvance + " tick/格`"
                        + "（jobTicks=" + job.elapsedTicks() + " / advance=" + job.advancedUnits()
                        + "；参考上限 " + TICK_COST_REPORT_ONLY + "）",
                ticksPerAdvance > 0 && ticksPerAdvance <= TICK_COST_REPORT_ONLY);
    }

    /** 臂 ②：`C6` 的"前方硬拒绝"半边。 */
    private void assertBedrock(ServerLevel level) {
        check("② `C6` 归因 = `main_blocked:<码>`（实际 " + armVerdict + "）—— 归因码取 "
                        + "`MineTask.isHardTargetRefusal` 那一族，**不许**被总括码盖掉；"
                        + "也不许写成 `start_unreachable`",
                armVerdict.startsWith("main_blocked:"));
        // 基岩那一格与它之后**零改动**
        int changed = 0;
        for (int unit = BEDROCK_UNIT; unit <= MAIN_LENGTH; unit++) {
            for (int dy = 0; dy < HEIGHT; dy++) {
                BlockPos cell = template.footOf(unit).above(dy);
                boolean expectBedrock = unit == BEDROCK_UNIT;
                BlockState state = level.getBlockState(cell);
                boolean ok = expectBedrock ? state.is(Blocks.BEDROCK) : state.is(Blocks.STONE);
                if (!ok) {
                    changed++;
                }
            }
        }
        check("② `C6` 基岩那一格**零改动**、且它之后的模板格**一格没动**（违规格=" + changed + "）",
                changed == 0);
        check("② `C6` 之前那一格**确实挖到了**（第 " + (BEDROCK_UNIT - 1) + " 个单元的脚位是空气）"
                        + "—— 否则这条臂测的是「一开始就没挖」",
                level.getBlockState(template.footOf(BEDROCK_UNIT - 1)).isAir());
        check("② 诚实失败也**先返回起点**（计划 10.2 节：不许把 bot 留在洞里）"
                        + "｜脚位=" + bot.blockPosition().toShortString(),
                bot.blockPosition().distManhattan(template.startFoot()) <= 2);
    }

    /** 臂 ③：`C6` 的"起点不可达"半边。 */
    private void assertStartSealed(ServerLevel level) {
        check("③ `C6` 归因 = `start_unreachable`（实际 " + armVerdict + "）——"
                        + "**`SEARCH_LIMIT ≠ UNREACHABLE`**：没搜完必须报 `start_search_incomplete`，"
                        + "不许冒充「不可达」",
                "start_unreachable".equals(armVerdict));
        check("③ `C6` **零世界改动**（`WriteAudit` 破坏条目 = " + auditDeltaBreaks + "）",
                auditDeltaBreaks == 0);
        check("③ `C6` 起点那一格与地板**原封不动**（起点空气=" + level.getBlockState(ORIGIN).isAir()
                        + " 地板石头=" + level.getBlockState(ORIGIN.below()).is(Blocks.STONE) + "）",
                level.getBlockState(ORIGIN).isAir() && level.getBlockState(ORIGIN.below()).is(Blocks.STONE));
        check("③ 前提：bot 确实**出不去**（密闭壳完好=" + level.getBlockState(SEALED.offset(1, 0, 0)).is(Blocks.STONE)
                        + "；bot 脚位=" + bot.blockPosition().toShortString() + "）",
                level.getBlockState(SEALED.offset(1, 0, 0)).is(Blocks.STONE));
    }

    // ==================== 期望方块表（唯一真值：建场景与断言共用） ====================

    /**
     * 本臂**期望的最终方块表**（相对 `ORIGIN` 的偏移 → 方块）。
     *
     * <p>⚠️ 这是"**模板外改动 = 0**"的最强形态：不是"没超过预算"，而是**逐格枚举**。
     * 建场景与断言**共用本方法** ⇒ 两头不可能漂（`alice-scene-based-testing` §6.9.1 ①）。
     */
    private List<String> expectedBlocks(Arm arm) {
        List<String> rows = new ArrayList<>();
        for (int dx = BOX_MIN_DX; dx <= BOX_MAX_DX; dx++) {
            for (int dy = BOX_MIN_DY; dy <= BOX_MAX_DY; dy++) {
                for (int dz = -BOX_HALF_DZ; dz <= BOX_HALF_DZ; dz++) {
                    BlockPos pos = ORIGIN.offset(dx, dy, dz);
                    String expected = expectedAt(arm, dx, dy, dz, pos);
                    rows.add(expected);
                }
            }
        }
        return rows;
    }

    private String expectedAt(Arm arm, int dx, int dy, int dz, BlockPos pos) {
        if (arm == Arm.START_SEALED) {
            // 密闭壳（以 SEALED 为中心 3×3×4 的实心，除中心空腔）+ 起点脚位那一格
            int sdx = pos.getX() - SEALED.getX();
            int sdy = pos.getY() - SEALED.getY();
            int sdz = pos.getZ() - SEALED.getZ();
            if (Math.abs(sdx) <= 1 && Math.abs(sdz) <= 1 && sdy >= -1 && sdy <= 2) {
                boolean hollow = sdx == 0 && sdz == 0 && (sdy == 0 || sdy == 1);
                return hollow ? "air" : "stone";
            }
            if (dx == 0 && dz == 0 && dy == -1) {
                return "stone";      // 起点地板（可站前提）
            }
            return "air";
        }
        // 地板
        if (dy == -1) {
            return "stone";
        }
        boolean inBody = dx >= BODY_MIN_X && dx <= BODY_MAX_X
                && Math.abs(dz) <= BODY_HALF_Z && dy >= 0 && dy < HEIGHT;
        if (inBody) {
            if (dz != 0) {
                return "stone";                                  // 两侧墙：**不许动**
            }
            if (arm == Arm.BEDROCK_BLOCKED && dx >= BEDROCK_UNIT) {
                // 基岩那一格保持基岩；**它之后**的格保持石头（= 一格没动）
                return dx == BEDROCK_UNIT ? "bedrock" : "stone";
            }
            return "air";                                        // 模板格：挖穿后是空气
        }
        return "air";
    }

    /** 逐格比对，返回**不一致的格数**（> 0 即"动了不该动的地方"或"该挖的没挖"）。 */
    private int diffExpected(ServerLevel level, List<String> expectedRows) {
        int i = 0;
        int bad = 0;
        for (int dx = BOX_MIN_DX; dx <= BOX_MAX_DX; dx++) {
            for (int dy = BOX_MIN_DY; dy <= BOX_MAX_DY; dy++) {
                for (int dz = -BOX_HALF_DZ; dz <= BOX_HALF_DZ; dz++) {
                    BlockPos pos = ORIGIN.offset(dx, dy, dz);
                    String want = expectedRows.get(i++);
                    BlockState state = level.getBlockState(pos);
                    boolean ok = switch (want) {
                        case "air" -> state.isAir();
                        case "stone" -> state.is(Blocks.STONE);
                        case "bedrock" -> state.is(Blocks.BEDROCK);
                        default -> false;
                    };
                    if (!ok) {
                        bad++;
                        if (bad <= 5) {
                            BotLog.warn("[Fishbone1] 期望表不符 pos={} 期望={} 实际={}",
                                    pos.toShortString(), want, state.getBlock());
                        }
                    }
                }
            }
        }
        return bad;
    }

    // ==================== 收尾 ====================

    private Task.Status cleanup() {
        ServerLevel level = bot.serverLevel();
        clearBox(level);
        // 密闭壳所在的格子也在盒外 ⇒ 单独清（否则会污染后续步 —— `§6.9.2` 失败路径也清理）
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    level.setBlock(SEALED.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        forceload(level, false);
        bot.controller().stopMovement();
        if (entryFoot != null) {
            teleport(level, entryFoot);
        }
        check("收尾：场地清回空气（残留非空气格=" + countNonAir(level) + "）", countNonAir(level) == 0);
        boolean pass = failures.isEmpty();
        BotLog.info("[Fishbone1] SUMMARY checks={} failures={} arms={} pass={} → {}｜失败项：{}",
                checks, failures.size(), ARMS.length, pass, pass ? "PASS" : "FAIL", failures);
        if (observer != null && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 鱼骨切片 1 " + (pass ? "PASS" : "FAIL")
                    + "（" + ARMS.length + " 臂：" + checks + " checks / " + failures.size()
                    + " failures，详见日志 [Fishbone1]）"));
        }
        phase = Phase.DONE;
        return failures.isEmpty() ? Task.Status.DONE : Task.Status.FAILED;
    }

    // ==================== 工具 ====================

    /**
     * ⭐ **切片 2 第一步（支巷几何）的纯几何判据**（`D-386` 计划 §2/§3；判据 `C1`/`C5` 的**基准**）。
     *
     * <p>为什么单独钉这一层：`FishboneTemplate.cells()` / `unitFoots()` 是 `C1`（模板 = 事实）与
     * `C5`（白名单）的**基准** —— 它一漂，后面所有行为判据都跟着漂。所以它自己必须能被红
     * （`D-425` ⑤：只咬方法名/字面量的判据，恒真也能过 ⇒ 这里断言**逐个坐标**的期望表）。
     *
     * <p>期望值**独立算出**（手算的坐标表），不是从实现里抄的。
     */
    private void spurGeometryChecks() {
        // 主巷 6、间距 2 ⇒ 支巷位置 i = 2, 4, 6（3 条）；每条 2 格；LEFT（EAST 的逆时针 = NORTH）
        FishboneTemplate sp = FishboneTemplate.spurs(ORIGIN, Direction.EAST, 6, 2, 2,
                FishboneTemplate.SpurSide.LEFT);
        BlockPos f1 = ORIGIN.relative(Direction.EAST, 1);
        BlockPos f2 = ORIGIN.relative(Direction.EAST, 2);
        BlockPos f4 = ORIGIN.relative(Direction.EAST, 4);
        BlockPos f6 = ORIGIN.relative(Direction.EAST, 6);

        check("几何：支巷条数 == mainLength / spurSpacing（6/2 ⇒ 3，实测 " + sp.spurCount() + "）",
                sp.spurCount() == 3);
        check("几何：推进单元数 == 主巷 6 + 支巷 3×(1 侧)×2 格 = 12（实测 " + sp.advanceCells() + "）",
                sp.advanceCells() == 12);
        check("几何：模板格数 == 单元数 × 净高 = 12×2 = 24（实测 " + sp.cells().size() + "）",
                sp.cells().size() == 24);
        check("几何：cellSet 无重复（主巷与支巷正交 ⇒ 本几何不该有重叠；实测 "
                        + sp.cellSet().size() + " vs " + sp.cells().size() + "）",
                sp.cellSet().size() == sp.cells().size());

        // ⭐ 顺序：支巷**插在它分叉出去的那个主巷单元之后**（"开出去 → 挖到端点 → 原路退回"）
        List<BlockPos> u = sp.unitFoots();
        check("几何：单元序列 = [主巷1, 主巷2, 支巷(2)+1N, 支巷(2)+2N, 主巷3, …]（逐格坐标比对）",
                u.get(0).equals(f1) && u.get(1).equals(f2)
                        && u.get(2).equals(f2.north()) && u.get(3).equals(f2.north(2))
                        && u.get(4).equals(ORIGIN.relative(Direction.EAST, 3)));
        check("几何：第 2 条支巷挂在第 4 个主巷单元之后（i=4 ⇒ 索引 6 起）",
                u.get(5).equals(f4) && u.get(6).equals(f4.north()) && u.get(7).equals(f4.north(2)));
        check("几何：最后一条支巷挂在第 6 个主巷单元之后（i=mainLength 也开）",
                u.get(10).equals(f6.north()) && u.get(11).equals(f6.north(2)));

        // ⭐ 侧向：ALTERNATE 必须真的交替（奇数条 LEFT=逆时针，偶数条 RIGHT=顺时针）
        FishboneTemplate alt = FishboneTemplate.spurs(ORIGIN, Direction.EAST, 6, 2, 2,
                FishboneTemplate.SpurSide.ALTERNATE);
        List<BlockPos> a = alt.unitFoots();
        check("几何：ALTERNATE 第 1 条朝 LEFT（EAST 的逆时针 = NORTH），第 2 条朝 RIGHT（= SOUTH）",
                a.get(2).equals(f2.north()) && a.get(6).equals(f4.south()));

        // ⭐ BOTH：两侧都开 ⇒ 单元数翻倍
        FishboneTemplate both = FishboneTemplate.spurs(ORIGIN, Direction.EAST, 6, 2, 2,
                FishboneTemplate.SpurSide.BOTH);
        check("几何：BOTH 单元数 == 6 + 3×2 侧×2 格 = 18（实测 " + both.advanceCells() + "）",
                both.advanceCells() == 18);

        // ⭐ 作用域半径必须把支巷算进去（否则支巷的掉落物落在作用域外 ⇒ `C2` 永不成立，`D-346` 的教训）
        check("几何：scopeRadius == mainLength + spurLength + 2 == 10（实测 " + sp.scopeRadius() + "）",
                sp.scopeRadius() == 10);

        // ⭐ 向后兼容：`spurSpacing = 0` 必须**逐字**等于切片 1 的展开（主巷 only）
        FishboneTemplate plain = FishboneTemplate.main(ORIGIN, Direction.EAST, 6);
        check("几何：spurSpacing=0（切片 1 口径）⇒ 无支巷、单元数 == 6、格数 == 12",
                !plain.hasSpurs() && plain.advanceCells() == 6 && plain.cells().size() == 12);
        check("几何：spurSpacing=0 的 scopeRadius == 8（不含 spurLength）", plain.scopeRadius() == 8);

        // ⭐ 合法域：非法即**拒绝**而不是夹取（夹取会把"要 20 格"静默变成"挖了 3 格"）
        boolean rejectedSpacing = false;
        boolean rejectedLength = false;
        try {
            FishboneTemplate.spurs(ORIGIN, Direction.EAST, 6, 1, 2, FishboneTemplate.SpurSide.LEFT);
        } catch (IllegalArgumentException expected) {
            rejectedSpacing = true;
        }
        try {
            FishboneTemplate.spurs(ORIGIN, Direction.EAST, 6, 2, 0, FishboneTemplate.SpurSide.LEFT);
        } catch (IllegalArgumentException expected) {
            rejectedLength = true;
        }
        check("几何：spurSpacing=1 必须抛（会把相邻支巷塌成大厅）", rejectedSpacing);
        check("几何：spurLength=0 必须抛", rejectedLength);
    }

    private void check(String name, boolean ok) {
        checks++;
        if (!ok) {
            failures.add(name);
            BotLog.warn("[Fishbone1] ✗ {}", name);
        } else {
            BotLog.info("[Fishbone1] ✓ {}", name);
        }
    }

    private AABB box() {
        return new AABB(ORIGIN.getX() + BOX_MIN_DX, ORIGIN.getY() + BOX_MIN_DY,
                ORIGIN.getZ() - BOX_HALF_DZ,
                ORIGIN.getX() + BOX_MAX_DX + 1, ORIGIN.getY() + BOX_MAX_DY + 1,
                ORIGIN.getZ() + BOX_HALF_DZ + 1);
    }

    private void clearBox(ServerLevel level) {
        for (int dx = BOX_MIN_DX; dx <= BOX_MAX_DX; dx++) {
            for (int dy = BOX_MIN_DY; dy <= BOX_MAX_DY; dy++) {
                for (int dz = -BOX_HALF_DZ; dz <= BOX_HALF_DZ; dz++) {
                    level.setBlock(ORIGIN.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    private int countNonAir(ServerLevel level) {
        int total = 0;
        for (int dx = BOX_MIN_DX; dx <= BOX_MAX_DX; dx++) {
            for (int dy = BOX_MIN_DY; dy <= BOX_MAX_DY; dy++) {
                for (int dz = -BOX_HALF_DZ; dz <= BOX_HALF_DZ; dz++) {
                    if (!level.getBlockState(ORIGIN.offset(dx, dy, dz)).isAir()) {
                        total++;
                    }
                }
            }
        }
        return total;
    }

    /** forceload（**建/拆对称**：它是全局世界状态，泄漏会污染后续步）。 */
    private void forceload(ServerLevel level, boolean on) {
        int minX = ORIGIN.getX() + BOX_MIN_DX;
        int maxX = SEALED.getX() + 2;
        int minZ = ORIGIN.getZ() - BOX_HALF_DZ;
        int maxZ = ORIGIN.getZ() + BOX_HALF_DZ;
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                level.setChunkForced(cx, cz, on);
            }
        }
    }

    private void teleport(ServerLevel level, BlockPos foot) {
        bot.teleportTo(level, foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
