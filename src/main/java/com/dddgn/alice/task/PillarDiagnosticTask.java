package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.CompletionTolerance;
import com.dddgn.alice.pathing.core.LiveExecutionContext;
import com.dddgn.alice.pathing.core.MovementExecution;
import com.dddgn.alice.pathing.core.MovementExecutionFactory;
import com.dddgn.alice.pathing.core.MovementSpec;
import com.dddgn.alice.pathing.core.MovementType;
import com.dddgn.alice.pathing.core.PillarExecutionFactory;
import com.dddgn.alice.pathing.core.RecoverabilityEvaluator;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.search.PlannedMovement;
import com.dddgn.alice.pathing.core.search.PlannedMovementSpecs;
import com.dddgn.alice.pathing.core.search.SearchBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PILLAR（垂直上升 1 格）诊断任务：一次右键验证三条路径。
 *
 * <ol>
 *   <li><b>规划</b>：3 格深 1×1 竖井（基岩壁）内 `(24,64,44)` → 井口边缘 `(25,67,44)`，
 *       期望 `REACHED` 且首步 = PILLAR、PILLAR 数量 ≥ 2（唯一出路必须含"跳跃中在脚下放方块"）；</li>
 *   <li><b>资源守卫</b>：清空快捷栏方块后再规划，期望**不** REACHED
 *       （对照 Baritone `costOfPlacingAt` 无一次性方块 → COST_INF）；</li>
 *   <li><b>执行</b>：恢复方块后实跑，期望 `COMPLETED` 并稳定在井口边缘。</li>
 *   <li>⭐ <b>水柱准入契约</b>（`P2` Pillar 片 / `D-427`）：见 {@link #floodedColumnContract()} ——
 *       自建 4 格灌水竖井，把 bot 悬在柱中（`onGround` 恒假），验"第 2 段起"的准入与规划级见证。</li>
 * </ol>
 */
public final class PillarDiagnosticTask implements Task {
    /** 竖井内部脚位（3 格深：64/65/66 空气，壁 y=64..66，井口顶面 y=67）。 */
    public static final BlockPos SHAFT_START = new BlockPos(24, 64, 44);
    /** 井口边缘脚位（站在壁顶，比井底高 3 格）。 */
    public static final BlockPos RIM_GOAL = new BlockPos(25, 67, 44);
    /**
     * 竖井逃生的 PILLAR 下限：3 格深 1×1 竖井至少需要 2 次 PILLAR
     * （规划器实测选 2×PILLAR + 1×ASCEND，cost 11.68，比 3×PILLAR+TRAVERSE 的 16 便宜）。
     */
    private static final int MIN_PILLARS = 2;

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final ItemStack[] savedHotbar = new ItemStack[9];

    private int phase;
    private int ticks;
    /** **V-4 对照用**：移动执行相位（runner 相位）的 tick 数。 */
    private int execStartTicks = -1;
    private int execTicks = -1;
    private PathRetryRunner runner;
    private String failure = "";
    private boolean planPass;
    private boolean guardPass;
    private boolean executePass;
    private String planDetail = "-";

    // ==================== `P2` Pillar 片（`D-427`）：水柱准入契约 ====================

    /** 自建灌水竖井的**底部水格**（孤立点：与对角/其它场景相距 80+ 格）。 */
    private static final BlockPos COLUMN_BOTTOM = new BlockPos(3100, -60, 3800);
    /** 水柱格数：≥3 才会出现"第 2 段"（4 格 ⇒ 2 段水柱上浮；`D-244` 的夹具只有 2 格 = 1 段）。 */
    private static final int COLUMN_CELLS = 4;
    /** 物理稳定的**等待上限**（柱底那条对照：等 `onGround` 被物理立起来，最多 20 tick）。 */
    private static final int CONTRACT_MAX_SETTLE_TICKS = 20;
    /**
     * 悬空那条判据前**必须经过的物理 tick 数**（实测取 2）：`teleport` 之后的 `onGround` 是**旧标志位**，
     * 要跑过物理才是"这个站位给定的"。下沉重测：水里约 10 tick 掉一格 ⇒ 2 tick 还在这一格内。
     */
    private static final int CONTRACT_PHYSICS_TICKS = 2;
    /** 干地悬空对照用的空中格（下面也是空气 ⇒ 真的在空中）。 */
    private static final BlockPos CONTRACT_AIRBORNE = new BlockPos(3105, -58, 3805);
    /** 水柱契约用的合成代价（本契约只看**准入判据**，不看代价；与 `place_step_diagonal` 同形）。 */
    private static final double CONTRACT_COST = 5.0D;

    /** 本夹具动过的方块（建水柱前是空气 ⇒ 结束按原样还原）。 */
    private final Map<BlockPos, BlockState> columnOriginal = new LinkedHashMap<>();
    private final List<String> contractFailures = new ArrayList<>();
    private int contractStage;
    private int contractTicks;
    private int contractChecks;
    private String contractDetail = "-";

    public PillarDiagnosticTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(RIM_GOAL);
    }

    @Override
    public Status tick() {
        if (++ticks > 1200) {
            failure = "PILLAR_TIMEOUT";
            return finish();
        }
        switch (phase) {
            case 0 -> {
                teleport(SHAFT_START);
                ensureCobblestone(8);
                phase = 1;
                return Status.RUNNING;
            }
            case 1 -> {
                PathPlan plan = plan();
                long pillars = plan.movements().stream()
                        .filter(m -> m.movementType() == MovementType.PILLAR).count();
                String first = plan.movements().isEmpty()
                        ? "-" : plan.movements().get(0).movementType().name();
                planPass = plan.reached() && pillars >= MIN_PILLARS
                        && MovementType.PILLAR.name().equals(first);
                planDetail = plan.status() + "/first=" + first + "/pillars=" + pillars;
                BotLog.info("[Pillar] plan_check={} detail={}", planPass ? "PASS" : "FAIL", planDetail);
                phase = 2;
                return Status.RUNNING;
            }
            case 2 -> {
                int cleared = clearHotbarBlocks();
                PathPlan plan = plan();
                guardPass = cleared > 0 && !plan.reached();
                BotLog.info("[Pillar] resource_guard={} cleared={} status={}",
                        guardPass ? "PASS" : "FAIL", cleared, plan.status());
                restoreHotbar();
                phase = 3;
                return Status.RUNNING;
            }
            case 3 -> {
                teleport(SHAFT_START);
                ensureCobblestone(8);
                execStartTicks = ticks;   // V-4：从这里开始量移动
                runner = new PathRetryRunner(bot, request(), PathRetryRunner.DEFAULT_MAX_REPLANS,
                        "pillar-execute");
                phase = 4;
                return Status.RUNNING;
            }
            case 4 -> {
                PathRetryRunner.State state = runner.tick();
                if (state == PathRetryRunner.State.RUNNING) {
                    return Status.RUNNING;
                }
                executePass = state == PathRetryRunner.State.DONE
                        && MovementHelper.footCell(bot.serverLevel(), bot).equals(RIM_GOAL);
                execTicks = execStartTicks < 0 ? -1 : ticks - execStartTicks;
                BotLog.info("[Pillar] execute={} detail={} replans={} exec_ticks={} foot={}",
                        executePass ? "PASS" : "FAIL",
                        runner.result() == null ? "-" : runner.result().status().name(),
                        runner.replans(), execTicks, bot.blockPosition().toShortString());
                phase = 5;   // ⭐ `P2` Pillar 片（`D-427`）：井口到达之后再加"水柱准入契约"
                return Status.RUNNING;
            }
            case 5 -> {
                if (floodedColumnContract()) {
                    return finish();
                }
                return Status.RUNNING;
            }
            default -> {
                return finish();
            }
        }
    }

    @Override
    public String failureReason() {
        return failure;
    }

    private PathRequest request() {
        PathRequest base = PathRequest.withWorldModification(bot.getUUID().toString(),
                SHAFT_START, RIM_GOAL, "pillar-diagnostic");
        return new PathRequest(base.botId(), SHAFT_START, base.goal(), base.allowedMovementTypes(),
                SearchBudget.of(CorePathPlanner.DEFAULT_MAX_NODES, CorePathPlanner.DEFAULT_MAX_MILLIS),
                base.requester());
    }

    private PathPlan plan() {
        return new CorePathPlanner().plan(bot, bot.serverLevel(), request());
    }

    /** 指定起终点的规划（水柱契约要的**悬空起点**）。起终点取自 `request.startFoot()`（A\* 用的就是它）。 */
    private PathPlan plan(BlockPos from, BlockPos to) {
        PathRequest base = PathRequest.withWorldModification(bot.getUUID().toString(), from, to,
                "pillar-diagnostic");
        PathRequest request = new PathRequest(base.botId(), from, base.goal(), base.allowedMovementTypes(),
                SearchBudget.of(CorePathPlanner.DEFAULT_MAX_NODES, CorePathPlanner.DEFAULT_MAX_MILLIS),
                base.requester());
        return new CorePathPlanner().plan(bot, bot.serverLevel(), request);
    }

    // ==================== `P2` Pillar 片（`D-427`）：水柱准入契约 ====================

    /**
     * ⭐ **"水柱第 2 段起"的准入契约**（`P2` Pillar 片 / `D-427`）。
     *
     * <h3>缺口是什么（先说事实，再说判据）</h3>
     * `PillarExecutionFactory.validate` 原先**无条件**要求 `bot.onGround()`。而 `D-244` 的水柱支
     * （起点格与目的地格**都是水**）里 `onGround` **恒假** —— 这一点是**执行侧自己写着的契约**：
     * `PillarExecution.postconditionHolds()` 的水柱支原文「水里没有 `onGround`、也没有支撑 ⇒ D-026 的
     * "已落地/居中"两套完成口径都永远不会成立」，且执行侧的 `preconditionsHold()` **故意**不查它。
     * ⇒ 灌水竖井只要 ≥3 格（= 需要 **2 段**以上 `PILLAR`），**第 2 段起**在**准入**处就被
     * `PILLAR_NOT_ON_GROUND` 挡下 = 又一次「规划得到、执行不了」（`D-242` 家族）。
     * <p>真机证据：`docs/reviews/2026-09-21-掉落物在洞里被瞬退.md:554`
     * （`PILLAR_NOT_ON_GROUND` ×11，现场 = 破掉脚下 → 落水 → 沉底 → 溺水）。
     * 而 `D-244` 的夹具 `FLOODED_SHAFT` 只有 **2 格水** = **恰好 1 段**水柱 ⇒ 这条缝从来没被量到
     * （本方法的 4 格水柱 + 悬空起点就是把它补上）。
     *
     * <h3>五组判据（互为对照，全部走**真实代码路径**）</h3>
     * ① **前提自证四条**：水柱 4 格都是水 · bot 脚位在柱中且下面是水 ·
     *    ⭐ **`onGround=false`（实测，不是引用文档）** · 干地悬空那格同 tick 也 `onGround=false`；
     * ② **规划级见证**：从**悬空**的水柱格规划到墙顶必须 `REACHED`、首段必须是 `PILLAR`、
     *    且计划里必须有 **≥2 段水柱 `PILLAR`**（`from`/`to` 都是水）—— 证明这条边是**真的会被规划出来的**；
     * ③ ⭐ **准入（核心判据）**：第 2 段（悬空 + `from`/`to` 都是水）必须被**接受**
     *    （被 `PILLAR_NOT_ON_GROUND` 拒 = 本片要修的缺陷）；
     * ④ **执行侧第一 tick** 不许拒（`create(...).tick()` 后 `phase != FAILED`）—— 两侧同源；
     * ⑤ **对照两条**：同一形状的水柱段在**站在柱底**（`onGround=true`）时必须接受（证明不是"水一律拒"）；
     *    而**干地悬空**必须仍被拒、且拒绝码**逐字**是 `PILLAR_NOT_ON_GROUND`
     *    （证明修法是给门控加**条件**，不是把门控删掉）。
     *
     * <p><b>红臂</b>：把 `PillarExecutionFactory` 的 `!MovementHelper.isWater(...) && ` 去掉 ⇒ ③ 必红。
     *
     * @return true = 契约跑完（含还原），可以收尾
     */
    private boolean floodedColumnContract() {
        ServerLevel level = bot.serverLevel();
        switch (contractStage) {
            case 0 -> {
                buildFloodedColumn(level);
                teleport(columnFloating());
                contractTicks = 0;
                contractStage = 1;
            }
            case 1 -> {
                // ⚠️ **两次实测教训（`D-427`）**：① 水里没有"原地悬停"——假人在水里**照常下沉**
                // （实测 10 tick 掉出整整一格 ⇒ 第一版"等 10 tick 再量"量到的是柱底，码变成
                // `PILLAR_STALE_START`，判据量错了对象）；② 反过来**每 tick 都传**也不行 ——
                // `teleport` 会把 `onGround` 按住成 false ⇒ 柱底那条对照永远立不起来（第二版实测）。
                // 正解 = **只传一次、等 N 个物理 tick**（标志位由物理给，且还在这一格里）。
                if (++contractTicks >= CONTRACT_PHYSICS_TICKS) {
                    floatingStageChecks(level);
                    contractTicks = 0;
                    contractStage = 2;
                }
            }
            case 2 -> {
                // 干地悬空对照：**必须在同一 tick 内**传送 + 判定（下一 tick 物理就开始下落）
                teleport(CONTRACT_AIRBORNE);
                dryAirborneCheck(level);
                teleport(COLUMN_BOTTOM);          // 站到柱底，等物理把 onGround 立起来
                contractTicks = 0;
                contractStage = 3;
            }
            case 3 -> {
                if (bot.onGround() || ++contractTicks >= CONTRACT_MAX_SETTLE_TICKS) {
                    groundedControlCheck(level);
                    contractStage = 4;
                }
            }
            default -> {
                int touched = columnOriginal.size();
                int mismatched = restoreFloodedColumn(level);
                // 夹具纪律（PLAYBOOK §5.0d）：结束必须复位；这里**量**它，不靠"应该还原了"
                check("清理：本夹具动过的 " + touched + " 格已按原样还原（不匹配=" + mismatched + "）",
                        touched > 0 && mismatched == 0);
                return true;
            }
        }
        return false;
    }

    /** 水柱第 `index` 格（`0` = 柱底）。 */
    private BlockPos columnCell(int index) {
        return COLUMN_BOTTOM.above(index);
    }

    /** 悬空的那一格（柱底之上 1 格：下面是水 ⇒ 永远拿不到 `onGround`）。 */
    private BlockPos columnFloating() {
        return columnCell(1);
    }

    /** 墙顶脚位（站在水柱旁的墙上，比柱顶高一格）。 */
    private BlockPos columnRim() {
        return columnCell(COLUMN_CELLS - 1).offset(1, 1, 0);
    }

    /** 自建孤立灌水竖井：`COLUMN_CELLS` 格水 + 四面石墙（放置面 + 不漂走）。 */
    private void buildFloodedColumn(ServerLevel level) {
        for (int dy = 0; dy < COLUMN_CELLS; dy++) {
            setFixtureBlock(level, columnCell(dy), Blocks.WATER.defaultBlockState());
            for (Direction side : Direction.Plane.HORIZONTAL) {
                setFixtureBlock(level, columnCell(dy).relative(side), Blocks.STONE.defaultBlockState());
            }
        }
    }

    private void setFixtureBlock(ServerLevel level, BlockPos pos, BlockState state) {
        columnOriginal.putIfAbsent(pos.immutable(), level.getBlockState(pos));
        level.setBlockAndUpdate(pos, state);
    }

    /** @return 还原后与世界**不一致**的格数（0 = 干净复位；与 `BreakTraverseFootingCheckTask` 同形） */
    private int restoreFloodedColumn(ServerLevel level) {
        int mismatched = 0;
        for (Map.Entry<BlockPos, BlockState> entry : columnOriginal.entrySet()) {
            level.setBlockAndUpdate(entry.getKey(), entry.getValue());
            if (!level.getBlockState(entry.getKey()).equals(entry.getValue())) {
                mismatched++;
            }
        }
        columnOriginal.clear();
        return mismatched;
    }

    /** 阶段 1：悬在水中 —— 前提自证 + 规划级见证 + **核心判据（准入）**。 */
    private void floatingStageChecks(ServerLevel level) {
        BlockPos floating = columnFloating();
        BlockPos top = columnCell(COLUMN_CELLS - 1);
        BlockPos rim = columnRim();
        check("前提：自建水柱 " + COLUMN_CELLS + " 格全是水（底部 " + COLUMN_BOTTOM.toShortString() + "）",
                allColumnCellsWater(level));
        // ⭐ 三条**实测**事实合成一条前提（不是引用 `D-244` 的结论）：脚位在柱中 + 下面是水 +
        // **`onGround=false`**。门禁 `rule_pillar_water_admission` 臂④钉的就是末尾那个
        // `&& !bot.onGround()`（掏成 `&& true` ⇒ 构建红，注入实测过 —— `D-425` 的"判据太糙"教训）。
        boolean floatingPremise = MovementHelper.footCell(level, bot).equals(floating)
                && MovementHelper.isWater(level, floating.below())
                && !bot.onGround();
        check("前提：bot 悬在水柱中（脚位=" + MovementHelper.footCell(level, bot).toShortString()
                        + "，期望 " + floating.toShortString() + "；下面那格是水；`onGround="
                        + bot.onGround() + "`）",
                floatingPremise);

        PathPlan plan = plan(floating, rim);
        long pillars = plan.movements().stream()
                .filter(m -> m.movementType() == MovementType.PILLAR).count();
        long swimPillars = plan.movements().stream()
                .filter(m -> m.movementType() == MovementType.PILLAR)
                .filter(m -> MovementHelper.isWater(level, m.fromFoot())
                        && MovementHelper.isWater(level, m.toFoot())).count();
        StringBuilder chain = new StringBuilder();
        for (PlannedMovement m : plan.movements()) {
            chain.append('[').append(m.movementType()).append(' ').append(m.fromFoot().toShortString())
                    .append('→').append(m.toFoot().toShortString()).append("] ");
        }
        contractDetail = "chain=" + chain + "status=" + plan.status() + " pillars=" + pillars
                + " swimPillars=" + swimPillars;
        BotLog.info("[Pillar] flooded_column floating={} top={} rim={} onGround={} detail={}",
                floating.toShortString(), top.toShortString(), rim.toShortString(), bot.onGround(),
                contractDetail);

        check("规划级见证①：从**悬空**的水柱格规划到墙顶必须 REACHED（status=" + plan.status() + "）",
                plan.reached());
        check("规划级见证②：首段必须是 `PILLAR`（实际 "
                        + (plan.movements().isEmpty() ? "-" : plan.movements().get(0).movementType()) + "）",
                !plan.movements().isEmpty()
                        && plan.movements().get(0).movementType() == MovementType.PILLAR);
        check("规划级见证③：计划里必须有 ≥2 段**水柱** `PILLAR`（实际 pillars=" + pillars
                + " swimPillars=" + swimPillars + "）⇒ 第 2 段真的会被规划出来", swimPillars >= 2);

        MovementExecutionFactory.ValidationResult verdict =
                pillarVerdict(level, floating, floating.above());
        check("⭐ 核心判据：水柱**第 2 段**（悬空 + `from`/`to` 都是水）的准入必须被接受"
                        + "（实际 valid=" + verdict.valid() + " code=" + verdict.failureCode() + "）"
                        + " —— 被 `PILLAR_NOT_ON_GROUND` 拒 = 「灌水竖井第 2 段起」整族边"
                        + "「规划得到、执行不了」（`D-427`）",
                verdict.valid());
        BotLog.info("[Pillar] flooded_column_admission floating={}→{} valid={} code={}",
                floating.toShortString(), floating.above().toShortString(), verdict.valid(),
                verdict.failureCode());
        if (verdict.valid()) {
            firstTickAccepted(level, floating, floating.above());
        } else {
            // `create` 会再 validate 一次并抛异常 ⇒ 准入已拒时不去构造它（本判据如实记失败）
            check("执行侧第一 tick 的水柱段不许被拒（准入已拒 ⇒ 跳过构造，避免 `create` 抛异常）", false);
        }
    }

    /** 核心判据的执行侧那一半：第一 tick 不许进 `FAILED`（本夹具**不跑物理**，一 tick 就收手）。 */
    private void firstTickAccepted(ServerLevel level, BlockPos from, BlockPos to) {
        MovementExecution execution = null;
        try {
            execution = new PillarExecutionFactory().create(pillarSpec(from, to), liveContext(level));
            execution.tick();
            check("核心判据（执行侧）：水柱段第一 tick 不许被拒（phase=" + execution.phase()
                            + " code=" + execution.failureCode() + "）",
                    execution.phase() != MovementExecution.Phase.FAILED
                            && execution.failureCode() == null);
        } catch (RuntimeException exception) {
            check("核心判据（执行侧）：构造水柱段不许抛异常（" + exception + "）", false);
        } finally {
            if (execution != null) {
                execution.cancel();
            }
            bot.controller().stopMovement();
        }
    }

    /** 阶段 2：**干地悬空**对照 —— 修法是给门控加条件，不是删门控。 */
    private void dryAirborneCheck(ServerLevel level) {
        // ⚠️ 顺序刻意写成 `!bot.onGround()` 在前：门禁臂④咬的是**合取形态** `&& !bot.onGround()`
        // 且必须**唯一**（只属于上面那条"水里悬空"的前提；写反了 ⇒ 掏空它也不红 = 假绿）。
        check("前提：干地上方悬空（下面那格是空气 ⇒ 没有支撑；`onGround=" + bot.onGround()
                        + "`，与上一阶段同一 tick，物理还没跑）",
                !bot.onGround() && !MovementHelper.canWalkOn(level, CONTRACT_AIRBORNE));
        MovementExecutionFactory.ValidationResult verdict =
                pillarVerdict(level, CONTRACT_AIRBORNE, CONTRACT_AIRBORNE.above());
        String code = String.valueOf(verdict.failureCode());
        check("对照①：干地悬空的 `PILLAR` 仍必须被拒（实际 valid=" + verdict.valid() + "）",
                !verdict.valid());
        // ⚠️ 咬**精确表达式**（`D-425` 的教训：判据只咬方法名/字面量 ⇒ 掏空了也看不出来）
        check("对照②：干地悬空的拒绝码必须逐字是 `PILLAR_NOT_ON_GROUND`（实际 " + code + "）"
                        + " —— 水柱那支放行靠的是「脚位是水」这个条件，不是把门控删掉",
                "PILLAR_NOT_ON_GROUND".equals(code));
    }

    /** 阶段 3：**同一形状的水柱段 + 站在柱底**（`onGround=true`）⇒ 必须接受（证明不是"水一律拒"）。 */
    private void groundedControlCheck(ServerLevel level) {
        BlockPos bottom = COLUMN_BOTTOM;
        check("前提：bot 站到水柱底部（脚位=" + MovementHelper.footCell(level, bot).toShortString()
                        + "，期望 " + bottom.toShortString() + "；下面那格是实心）⇒ `onGround=true`（实际 "
                        + bot.onGround() + "）",
                MovementHelper.footCell(level, bot).equals(bottom) && bot.onGround());
        MovementExecutionFactory.ValidationResult verdict =
                pillarVerdict(level, bottom, bottom.above());
        check("对照③：**站在水里**（水柱第 1 段）的准入必须被接受（实际 valid=" + verdict.valid()
                        + " code=" + verdict.failureCode() + "）—— 与第 2 段同一形状、同一谓词，"
                        + "唯一差别是 bot 站得住",
                verdict.valid());
    }

    private boolean allColumnCellsWater(ServerLevel level) {
        for (int dy = 0; dy < COLUMN_CELLS; dy++) {
            if (!MovementHelper.isWater(level, columnCell(dy))) {
                return false;
            }
        }
        return true;
    }

    private MovementExecutionFactory.ValidationResult pillarVerdict(ServerLevel level, BlockPos from,
                                                                    BlockPos to) {
        return new PillarExecutionFactory().validate(pillarSpec(from, to), liveContext(level));
    }

    /** 与 `place_step_diagonal` 的契约同形：合成代价 + 执行端 `MovementSpec`。 */
    private MovementSpec pillarSpec(BlockPos from, BlockPos to) {
        return PlannedMovementSpecs.toSpec(
                new PlannedMovement(MovementType.PILLAR, from, to, CONTRACT_COST,
                        RecoverabilityEvaluator.levelOf(MovementType.PILLAR)),
                List.of("session_segment"));
    }

    private LiveExecutionContext liveContext(ServerLevel level) {
        return new LiveExecutionContext(bot, level, "pillar-flooded-column", 0L,
                CompletionTolerance.EXACT, "pillar-diagnostic");
    }

    private void check(String what, boolean ok) {
        contractChecks++;
        if (!ok) {
            contractFailures.add(what);
        }
    }

    /** 夹具：确保快捷栏里有圆石（放置一次性方块用）。 */
    private void ensureCobblestone(int count) {
        com.dddgn.alice.item.FixtureToolKit.ensureHotbarStack(bot,
                () -> new ItemStack(Items.COBBLESTONE),
                stack -> stack.is(Items.COBBLESTONE), count, "cobblestone");
    }

    /** 临时清空快捷栏的方块物品，返回清空的槽位数（用于资源守卫检查）。 */
    private int clearHotbarBlocks() {
        var inventory = bot.getInventory();
        int cleared = 0;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            savedHotbar[slot] = stack.copy();
            inventory.setItem(slot, ItemStack.EMPTY);
            cleared++;
        }
        return cleared;
    }

    private void restoreHotbar() {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            if (savedHotbar[slot] != null) {
                inventory.setItem(slot, savedHotbar[slot]);
                savedHotbar[slot] = null;
            }
        }
    }

    private void teleport(BlockPos foot) {
        bot.teleportTo(bot.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
    }

    private Status finish() {
        boolean contractPass = contractFailures.isEmpty();
        boolean allPass = planPass && guardPass && executePass && contractPass;
        String summary = "pillar_plan=" + (planPass ? "PASS" : "FAIL")
                + " resource_guard=" + (guardPass ? "PASS" : "FAIL")
                + " pillar_execute=" + (executePass ? "PASS" : "FAIL")
                + " flooded_column=" + (contractPass ? "PASS" : "FAIL")
                + " detail=" + planDetail
                + " contract_detail=" + contractDetail
                + " exec_ticks=" + execTicks   // V-4：与 Baritone 对照的"纯移动 tick"
                + " contract_checks=" + contractChecks;
        if (!contractPass) {
            summary += " contract_failures=" + contractFailures;
        }
        BotLog.info("[Pillar] SUMMARY {}", summary);
        if (observer != null) {
            observer.sendSystemMessage(Component.literal("[alice] PILLAR 自检 " + summary)
                    .withStyle(allPass ? net.minecraft.ChatFormatting.GREEN
                            : net.minecraft.ChatFormatting.RED));
        }
        if (!allPass) {
            failure = "PILLAR_FAILED " + summary;
            return Status.FAILED;
        }
        return Status.DONE;
    }
}
