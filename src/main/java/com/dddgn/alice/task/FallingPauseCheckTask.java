package com.dddgn.alice.task;

import com.dddgn.alice.action.MineBlockRunner;
import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.task.mining.MiningBudget;
import com.dddgn.alice.task.mining.MiningPlan;
import com.dddgn.alice.task.mining.MiningPlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * **`1.4x`（`survey/35 §9` 桶3-4 / 缺口清单 `K1` 前半）**：重力方块（沙砾/沙）的
 * **挖掘期暂停**判据 —— 目标格里有**在飞的 `FallingBlockEntity`** ⇒ 本 tick **不挖**。
 *
 * <h3>它钉的机制（逐字对照 Baritone `pathing/movement/Movement.java:157-160`）</h3>
 * <pre>
 * for (BetterBlockPos blockPos : positionsToBreak) {
 *     if (!getEntitiesOfClass(FallingBlockEntity.class, new AABB(0,0,0,1,1.1,1).move(blockPos)).isEmpty()
 *             &amp;&amp; settings().pauseMiningForFallingBlocks) return false;   // ← 本 tick 不发挖掘输入
 * </pre>
 * 病灶：挖掉一格沙砾后**它要 2 tick 才落完**；Alice 此前在这 2 tick 里照样对着同一格推进度、
 * 或已经走开 ⇒ 落体正好把刚挖通的格填回，回程路当场消失。与 `F1` 同族：`F1` 是**空间**维度判据错位，
 * 本条是**时间**维度判据失同步。
 *
 * <h3>四臂（三真一假，假的那个防"永远绿"）</h3>
 * <pre>
 *   PAUSE     目标格里有落体            ⇒ `pausedForFallingBlock=true` + **窗口内一格没挖动**
 *   RESUME    清掉落体                  ⇒ 继续挖、**挖穿**（暂停不许是永久的）
 *   CTRL-V    落体在目标**上方 2 格**    ⇒ **不许**暂停（负对照：AABB 不许放宽到"头顶有东西就停"）
 *   CTRL-H    落体在目标**侧向 2 格**    ⇒ **不许**暂停（同上）
 * </pre>
 *
 * <h3>为什么"目标格还在"本身**不够**当判据（本夹具的关键设计）</h3>
 * 光断言"60 tick 后那格还是砾石"在**没实现暂停**的世界里也能成立 —— 只要 tick 数不够。
 * 所以本夹具同时断言①`MineBlockRunner.pausedForFallingBlock()`（走了暂停分支的**直接证据**，
 * 与 `mineInPlace()`/`supportSkipped()` 同族的只读访问器）②窗口长度取 {@value #PAUSE_TICKS} tick
 * —— 徒手/镐挖砾石约 18 tick（硬度 0.6 ⇒ 0.9 s）⇒ **没有暂停就必然挖穿** ⇒ 这条臂的红是**可算的**，
 * 不是"跑久一点看看"。
 *
 * <h3>⚠️ 测量陷阱与本夹具的诚实边界</h3>
 * 每次夹具 tick 都**丢弃并在原位重放**落体（{@link #pinFalling}）⇒ 它**从没有机会被 tick**
 * （不会下落、不会变回方块、也不会漂进别的格）。这样测的是**判据本身**，与"落体物理"解耦。
 * ⚠️ 因此**不要**把本夹具读成"沙砾真机行为已验证"：原版 `FallingBlock` 的 **2 tick 落体延迟**
 * 与"挖穿后多久才落下来"仍然只能由真机观察回答（`1.4x` 台账那条测量陷阱）。
 *
 * <p>零 bot 物理、不跑 Job、不用数据包地形：场景在**整块实心石头**里凿出来（孤立性是构造保证）。
 */
public final class FallingPauseCheckTask implements Task {

    /** 与同族夹具同一片专用孤立区（不同 z 偏移，互不重叠）。 */
    private static final BlockPos START = new BlockPos(3000, -60, 4400);

    /** bot 站位（= 房间中心）。 */
    private static final BlockPos FOOT = START;

    /** 房间半径：`y ∈ [0,1]` 上是空气（2 格高），之外是实心石（⇒ 天然围墙 + 天花板）。 */
    private static final int ROOM_RADIUS = 4;

    private static final BlockPos PAUSE_TARGET = FOOT.offset(-3, 0, 0);
    private static final BlockPos CTRL_V_TARGET = FOOT.offset(3, 0, 0);
    private static final BlockPos CTRL_H_TARGET = FOOT.offset(0, 0, 3);
    /** 负对照用的落体位置：目标**上方 2 格** / **侧向 2 格**（都在实心石里 ⇒ 与目标格不重叠）。 */
    private static final BlockPos CTRL_V_ENTITY = CTRL_V_TARGET.offset(0, 2, 0);
    private static final BlockPos CTRL_H_ENTITY = CTRL_H_TARGET.offset(0, 0, 2);

    private static final int SETTLE_TICKS = 20;
    /** 暂停窗口：≈ 3.3 倍挖掘时间（砾石 0.9 s = 18 tick）⇒ 没暂停必然挖穿。 */
    private static final int PAUSE_TICKS = 60;
    private static final int BREAK_TICKS = 240;
    private static final int BUDGET_TICKS = 3000;

    private enum Phase { BUILD, SETTLE, PAUSE, RESUME, CTRL_V, CTRL_H, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final List<String> findings = new ArrayList<>();
    private final Map<BlockPos, BlockState> before = new LinkedHashMap<>();

    private Phase phase = Phase.BUILD;
    private MineBlockRunner runner;
    private int ticks;
    private int armTicks;
    private int checks;
    private boolean armPausedEver;
    private boolean armPausedWrongly;
    private boolean done;

    public FallingPauseCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "mine_falling_pause";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(PAUSE_TARGET);
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
            check("四臂必须在预算内跑完（" + BUDGET_TICKS + " tick）", false);
            return finish();
        }
        ServerLevel level = bot.serverLevel();
        switch (phase) {
            case BUILD -> {
                buildScene(level);
                // 自检期间暂停决策层：本夹具会真的破坏方块，否则 `start_job` 可能中途砍掉本任务
                // （先例 `EventThresholdCheckTask:143`）。
                com.dddgn.alice.decision.GoalDirector.suspend(bot, BUDGET_TICKS + 200);
                phase = Phase.SETTLE;
            }
            case SETTLE -> {
                if (++armTicks >= SETTLE_TICKS) {
                    armTicks = 0;
                    premises(level);
                    startArm(level, PAUSE_TARGET, "PAUSE");
                    phase = Phase.PAUSE;
                }
            }
            case PAUSE -> {
                if (tickPauseArm(level)) {
                    // ⭐ **恢复臂刻意沿用同一个 runner**（不新建）：新建一个只能证明"同样几何去掉落体后能挖穿"，
                    // 证明不了"**暂停是可以解除的**"（同一个实例不许卡在暂停里）。清掉落体即可 ——
                    // 暂停分支**没有** tick `breakSession` ⇒ 没有任何半截状态需要归还。
                    clearFalling(level);
                    armTicks = 0;
                    armPausedWrongly = false;
                    phase = Phase.RESUME;
                }
            }
            case RESUME -> {
                if (tickResumeArm(level)) {
                    startArm(level, CTRL_V_TARGET, "CTRL-V");
                    phase = Phase.CTRL_V;
                }
            }
            case CTRL_V -> {
                if (tickControlArm(level, CTRL_V_TARGET, CTRL_V_ENTITY, "CTRL-V（落体在目标上方 2 格）")) {
                    startArm(level, CTRL_H_TARGET, "CTRL-H");
                    phase = Phase.CTRL_H;
                }
            }
            case CTRL_H -> {
                if (tickControlArm(level, CTRL_H_TARGET, CTRL_H_ENTITY, "CTRL-H（落体在目标侧向 2 格）")) {
                    return finish();
                }
            }
            case DONE -> {
                return finish();
            }
        }
        return Task.Status.RUNNING;
    }

    // ==================== 前提 ====================

    /**
     * 夹具自己的前提（不许被自己破坏）——**只读断言**，不传送（`R4`：传送 tick 不许当场读落地状态）。
     *
     * <p>⚠️ 首版把 bot 直接传送到**实心石**里 ⇒ 维生监测判 `SUFFOCATING` 打断整台电池
     * （`no_verdict exit=3`，`CorridorSkipPredicateCheckTask` 记过同一个坑）⇒ 本夹具**先把房间凿空、
     * 最后才把 bot 挪进一个两格高的空气位**。
     */
    private void premises(ServerLevel level) {
        check("前提：bot 站位两格高可通行且脚下有地板（" + FOOT.toShortString() + "）",
                level.getBlockState(FOOT).isAir() && level.getBlockState(FOOT.above()).isAir()
                        && level.getBlockState(FOOT.below()).isSolid());
        check("前提：三个目标格都已建为**砾石**（否则测的不是重力方块）",
                isGravel(level, PAUSE_TARGET) && isGravel(level, CTRL_V_TARGET)
                        && isGravel(level, CTRL_H_TARGET));
        check("前提：目标**下方**是实心（重力方块不许自己掉下去 —— 否则测到的是「方块不见了」"
                        + "而不是「暂停」）",
                level.getBlockState(PAUSE_TARGET.below()).isSolid()
                        && level.getBlockState(CTRL_V_TARGET.below()).isSolid()
                        && level.getBlockState(CTRL_H_TARGET.below()).isSolid());
    }

    // ==================== 四臂 ====================

    /**
     * 开一条臂：把房间恢复成"只有本臂的目标是砾石"、清掉落体，然后用**生产规划器**拿计划
     * （夹具的候选必须复用规划器 provider ⇒ 「可规划即可执行」）。
     */
    private void startArm(ServerLevel level, BlockPos target, String label) {
        prepareArm(level, target);
        MiningBudget budget = MiningBudget.forTarget(bot, level, target, true);
        MiningPlanner.Result result = new MiningPlanner().plan(bot, target, budget);
        MiningPlan plan = result.plan();
        if (plan == null) {
            check("前提（" + label + "）：生产规划器给出可执行计划（实际 null，reason="
                    + result.failureReason() + "）", false);
            runner = null;
            return;
        }
        check("前提（" + label + "）：模式 = CURRENT（**就地挖**；否则测到的是走路而不是暂停）"
                        + "（实际 " + plan.mode() + "）",
                plan.mode() == MiningPlan.Mode.CURRENT);
        runner = new MineBlockRunner(bot, plan,
                WriteGrant.of(taskName(), WriteReason.EXPECTED_TARGET));
        findings.add(label + ":plan=" + plan.mode()
                + " support=" + (plan.supportPlacementPos() == null ? "-"
                        : plan.supportPlacementPos().toShortString()));
    }

    /** 暂停臂：每 tick 把落体**钉**在目标格里（⇒ 它绝无机会被 tick），然后驱动 runner。 */
    private boolean tickPauseArm(ServerLevel level) {
        pinFalling(level, PAUSE_TARGET);
        if (runner != null) {
            runner.tick();
            armPausedEver |= runner.pausedForFallingBlock();
        }
        if (++armTicks < PAUSE_TICKS) {
            return false;
        }
        // ① 直接证据：暂停分支真的走过（Baritone `Movement.java:157-160` 的对应分支）
        check("⭐⭐ 暂停：目标格里有在飞的落体 ⇒ `MineBlockRunner.pausedForFallingBlock()` 在 "
                        + PAUSE_TICKS + " tick 窗口内出现过（实际 " + armPausedEver + "）"
                        + " —— 这是**走了暂停分支**的直接证据（没有它，"
                        + "「目标格还在」在没暂停的实现里也可能成立）",
                armPausedEver);
        // ② 物理后果：窗口内目标格**一格都没被挖动**（≈18 tick 就能挖穿 ⇒ 没暂停必然变空气）
        check("⭐⭐ 暂停：窗口 " + PAUSE_TICKS + " tick 内**目标格状态未变**（实际 "
                        + describe(level, PAUSE_TARGET) + "）—— 无暂停时砾石 ≈18 tick 就没了",
                isGravel(level, PAUSE_TARGET));
        // ③ 不许把"暂停"实现成"失败/终止"（暂停是等，不是放弃）
        check("⭐ 暂停：runner 仍停在本格上工作（`Status.MINING`，实际 " + statusOf(runner) + "）",
                runner != null && runner.status() == MineBlockRunner.Status.MINING);
        return true;
    }

    /**
     * 恢复臂：**沿用暂停臂那个 runner**、不再放落体 ⇒ 必须**继续挖并挖穿**（暂停不许是永久的、也不许卡住实例）。
     *
     * <p>⚠️ 为什么必须是同一个实例：新建一个 runner 只说明"同样几何现在能挖"，**证明不了**暂停状态可解除。
     * 而 `1.4x` 的暂停分支**刻意不 tick `breakSession`** ⇒ 结构上就不该有半截状态 ⇒ 这条臂把它钉住。
     */
    private boolean tickResumeArm(ServerLevel level) {
        if (runner == null) {
            return true;
        }
        runner.tick();
        if (runner.pausedForFallingBlock()) {
            armPausedWrongly = true;
        }
        return judgeWorkArm(level, PAUSE_TARGET, runner, armTicks++, "恢复（同一个 runner）",
                "落体清掉后**暂停解除**并挖穿");
    }

    /** 负对照臂：落体在**目标格之外** ⇒ **不许**暂停，目标照常被挖掉。 */
    private boolean tickControlArm(ServerLevel level, BlockPos target, BlockPos entityCell, String label) {
        pinFalling(level, entityCell);
        if (runner == null) {
            return true;
        }
        runner.tick();
        if (runner.pausedForFallingBlock()) {
            armPausedWrongly = true;
        }
        return judgeWorkArm(level, target, runner, armTicks++, label, "落体不在目标格 ⇒ **不许**暂停");
    }

    /**
     * 一条"应该能挖穿"的臂的公共判据。返回 `true` = 本臂结束。
     *
     * <p>负对照臂额外断言 `pausedForFallingBlock` **全程 false**；两条臂都断言目标真的变空气
     * （⇒ 反证：本夹具的暂停不是"什么都不做"）。
     */
    private boolean judgeWorkArm(ServerLevel level, BlockPos target, MineBlockRunner armRunner,
                                 int armTick, String label, String why) {
        if (armRunner.status() == MineBlockRunner.Status.DONE) {
            check("⭐ " + label + "：" + why + " ⇒ 目标格已成空气（实际 "
                    + describe(level, target) + "；tick=" + armTick + "）",
                    level.getBlockState(target).isAir());
            check("⭐ " + label + "：`pausedForFallingBlock` 全程 false（负对照；实际被误触发="
                    + armPausedWrongly + "）", !armPausedWrongly);
            return true;
        }
        if (armRunner.status() == MineBlockRunner.Status.FAILED) {
            check("⭐ " + label + "：runner 不许失败（实际 FAILED reason="
                    + armRunner.failureReason() + "）", false);
            return true;
        }
        if (armTick > BREAK_TICKS) {
            check("⭐ " + label + "：必须在 " + BREAK_TICKS + " tick 内挖穿（实际 status="
                    + armRunner.status() + "、目标 " + describe(level, target) + "）", false);
            return true;
        }
        return false;
    }

    // ==================== 落体（钉住，不许它自己 tick） ====================

    /**
     * 在 `cell` 里**重置**一个落体实体：先清掉该格的旧实体，再原地放一个新的。
     *
     * <p>⚠️ 两个**实测过**的坑决定了这段写法（`javap -c` 读的是 1.20.1 官方映射的字节码）：
     * <ol>
     *   <li>`FallingBlockEntity(Level, double, double, double, BlockState)` 是 **private**
     *       （公开面只有 `(EntityType, Level)` 与 `fall(...)`）⇒ 只能用 {@code fall(...)}；</li>
     *   <li>而 {@code fall(...)} 会把该格写成"流体遗留方块"⇒ **必须立刻还原**，
     *       否则测到的是"夹具把目标换成了空气"而不是"暂停挖掘"。</li>
     * </ol>
     * 载的方块状态取 **空气**（而不是砾石）：`tick()` 的**首分支**就是
     * {@code if (blockState.isAir()) discard();}（字节码事实）⇒ 这个实体在**它自己的那一 tick
     * 静默消失、永不落地成方块** ⇒ 它绝不会在场景里留下沙/砾石块、也不会污染负对照臂
     * （若载砾石/沙，它会落地成块，落点甚至可能正好压进目标格 ⇒ 假红）。
     */
    private void pinFalling(ServerLevel level, BlockPos cell) {
        for (FallingBlockEntity old : level.getEntitiesOfClass(FallingBlockEntity.class, cellBox(cell))) {
            old.discard();
        }
        BlockState was = level.getBlockState(cell);
        FallingBlockEntity entity = FallingBlockEntity.fall(level, cell, Blocks.AIR.defaultBlockState());
        // 还原：状态**逐字相同**时 vanilla `Level.setBlock` 直接返回 false（零方块更新、零级联）。
        level.setBlockAndUpdate(cell, was);
        entity.setNoGravity(true);
        entity.setDeltaMovement(Vec3.ZERO);
    }

    /** 与生产判据**同一个**盒子（`Baritone Movement.java:157-159` 的 `1.1` 放宽）；只用于找/清实体。 */
    private static AABB cellBox(BlockPos cell) {
        return new AABB(0.0D, 0.0D, 0.0D, 1.0D, 1.1D, 1.0D)
                .move(cell.getX(), cell.getY(), cell.getZ());
    }

    /** 清掉场景里**所有**落体（臂切换点用：不让上一条臂的残留参与下一条）。 */
    private void clearFalling(ServerLevel level) {
        for (FallingBlockEntity entity : level.getEntitiesOfClass(FallingBlockEntity.class, sceneBox())) {
            entity.discard();
        }
    }

    private static AABB sceneBox() {
        return new AABB(FOOT).inflate(ROOM_RADIUS + 2, 3.0D, ROOM_RADIUS + 2);
    }

    // ==================== 场景（自建 + 原样还原） ====================

    /**
     * 整块**实心石头**里凿出一个 `y ∈ [0,1]` 的房间（⇒ 孤立性是构造保证、天花板恒实心、
     * 三个目标格之间有净空 ⇒ 视线可达）。
     *
     * <p>⚠️ **先建盒、凿空站位、最后才传送 bot**（顺序反了 = 把 bot 塞进实心方块 ⇒ 维生监测打断电池）。
     */
    private void buildScene(ServerLevel level) {
        int r = ROOM_RADIUS;
        for (int dx = -(r + 1); dx <= r + 1; dx++) {
            for (int dz = -(r + 1); dz <= r + 1; dz++) {
                for (int dy = -2; dy <= 3; dy++) {
                    setBlock(level, FOOT.offset(dx, dy, dz), Blocks.STONE);
                }
            }
        }
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                setBlock(level, FOOT.offset(dx, 0, dz), Blocks.AIR);
                setBlock(level, FOOT.offset(dx, 1, dz), Blocks.AIR);
            }
        }
        for (BlockPos target : List.of(PAUSE_TARGET, CTRL_V_TARGET, CTRL_H_TARGET)) {
            setBlock(level, target, Blocks.GRAVEL);
        }
        teleport(bot, FOOT);
        FixtureToolKit.ensurePickaxe(bot);
    }

    /** 把房间恢复成"只有 `target` 是砾石"，并清掉上一臂留下的落体。 */
    private void prepareArm(ServerLevel level, BlockPos target) {
        clearFalling(level);
        for (int dx = -ROOM_RADIUS; dx <= ROOM_RADIUS; dx++) {
            for (int dz = -ROOM_RADIUS; dz <= ROOM_RADIUS; dz++) {
                setBlock(level, FOOT.offset(dx, 0, dz), Blocks.AIR);
                setBlock(level, FOOT.offset(dx, 1, dz), Blocks.AIR);
            }
        }
        setBlock(level, target, Blocks.GRAVEL);
        armTicks = 0;
        armPausedEver = false;
        armPausedWrongly = false;
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
        player.setDeltaMovement(Vec3.ZERO);
        player.controller().stopMovement();
    }

    // ==================== 读数 / 收尾 ====================

    private static boolean isGravel(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.GRAVEL);
    }

    private static String describe(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() ? "air" : String.valueOf(net.minecraft.core.registries.BuiltInRegistries
                .BLOCK.getKey(state.getBlock()));
    }

    private static String statusOf(MineBlockRunner armRunner) {
        return armRunner == null ? "null" : String.valueOf(armRunner.status());
    }

    private Task.Status finish() {
        done = true;
        if (runner != null) {
            runner.cancel();
        }
        ServerLevel level = bot.serverLevel();
        for (FallingBlockEntity entity : level.getEntitiesOfClass(FallingBlockEntity.class, sceneBox())) {
            entity.discard();
        }
        for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
            level.setBlockAndUpdate(entry.getKey(), entry.getValue());
        }
        teleport(bot, START);
        bot.controller().stopMovement();

        boolean pass = failures.isEmpty();
        BotLog.info("[FallingPause] SUMMARY checks={} failures={} verdict={} arms=4→{}｜发现：{}｜失败项：{}",
                checks, failures.size(), pass ? "PASS" : "FAIL", pass ? "PASS" : "FAIL", findings, failures);
        if (observer != null && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 重力方块「挖掘期暂停」取证 "
                    + (pass ? "PASS" : "FAIL") + "（4 臂：暂停/恢复/两个负对照；详见日志 [FallingPause]）"));
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
