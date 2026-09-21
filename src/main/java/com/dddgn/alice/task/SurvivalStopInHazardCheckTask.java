package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.survival.HazardType;
import com.dddgn.alice.survival.SurvivalSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ⭐ **B3：有活动危险时，延后停止不许落地**（电池步 `survival_stop_in_hazard`；2026-09-21 用户裁定）。
 *
 * <h2>它钉住的是什么（一句话）</h2>
 * <b>危险正在被处理时，K-3 的"延后停止"不许把任务从 bot 脚下抽走 —— 只有危险解除（或等满上限 60 秒）
 * 才落地。</b>
 *
 * <h2>为什么（本链条的起点）</h2>
 * `D-377` 的真机现场就是**延后停止在危险中落地**：任务被清掉 ⇒ bot 进入"没人管"的状态，而当时
 * 维生在"没有任务"下**整段被跳过** ⇒ 在水里沉底、`air 300→-2`、掉血 20→**1.0**。
 * `D-380`（一进水就浮）/`D-382`（浮完自己走上岸）已经把"无任务 + 危险"那条路补上了，
 * 所以 B3 不再是唯一防线；但它仍然**正确**：危险处理中不该把任务抽走。
 *
 * <h2>场景与判据（**用第二个假人**，因为夹具不能停掉自己）</h2>
 * <pre>
 * 密封石盒（3×3×3 外壳 + 1×2 空腔，**半径 8 内没有可规划的落点**）
 *   + 把探针假人**点着**（`ON_FIRE`：软危险，且"无出口"⇒ 判决 `HOLD_NO_EXIT` ⇒ **不中断任务**）
 *   + 给它一个 dummy 任务（`WalkToTask`）+ 提请一次**延后停止**
 * ⇒ 相位 A：危险还在 ⇒ **任务必须还在**（`hasTask=true`，停止未落地）
 * ⇒ 相位 B：把火灭掉（危险解除）⇒ 停止必须**落地**（`hasTask=false` + 终态记录里 `:safe_point`）
 * </pre>
 *
 * <p>⚠️ **口径（为什么用 `ON_FIRE` 而不是"泡在水里"）**：B3 只对**软/硬危险**生效
 * （`softHazard`/`hardHazard`），`WATER_CONTACT` **故意不算**（否则"水下作业想停一下"会被拖到 60 秒）；
 * "在水里"那一段由 `D-380`/`D-382` 覆盖。所以本夹具必须造一个**真的软危险**。
 * 又因为"软危险 + **有**出口"会走 `INTERRUPT`（任务被维生中断 ⇒ 停止自然落地），
 * 所以盒子必须**封死**（`plannableRefuge == null`）⇒ 才落在 `HOLD_NO_EXIT` 这一档。
 *
 * <h2>副作用边界（`§6.9.2`）</h2>
 * 全程**不动夹具自己的任务**（停止请求是发给**第二个假人**的）；收尾拆除探针、还原地形、复位观察者。
 */
public final class SurvivalStopInHazardCheckTask implements Task {

    /** 密封盒中心（天然地面之上那一层；盒体建在它四周）。 */
    private static final BlockPos BOX_CENTER = new BlockPos(2600, -60, 5400);
    /** 外壳半径（1 ⇒ 3×3 壳）。 */
    private static final int SHELL_R = 1;
    /**
     * 中心竖井高度（格）：**1 格宽 × 3 格高**。
     *
     * <p>为什么必须是"1 宽 + 4 高"：① **1 宽** ⇒ 洞内除了 bot 自己那格**没有别的可站格**
     * （正上方那格脚下是空气 ⇒ 站不住）⇒ 软危险落在 `HOLD_NO_EXIT`（不中断任务）；
     * ② **4 高** ⇒ 顶部安全脚位（`above(2)`，头在 `above(3)` 的**空气**里）与井底之间有一段
     * 落差 ⇒ 夹具能在**空中**提停止请求（`safeToStopNow()` 为假 ⇒ 走进 K-3 的"延后"那一档）。
     * ⚠️ 实测踩到两次：**3 高**时探针的头正好埋在天花板里 ⇒ `isInWall()` ⇒ `SUFFOCATING`（硬危险）
     * ⇒ 维生**中断**任务（`failed:survival_suffocating`）⇒ 停止自然落地，判据全废。
     */
    private static final int SHAFT_HEIGHT = 4;
    private static final String PROBE_NAME = "stop_probe";

    /** 相位 A（危险中）观察多久 —— 必须**超过**普通延后上限（20 tick），否则测不到差别。 */
    private static final int HAZARD_WATCH_TICKS = 60;
    /** 相位 B（危险解除后）等停止落地的最长 tick。 */
    private static final int AFTER_CLEAR_CAP = 40;
    private static final int BUDGET_TICKS = 500;

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final List<String> findings = new ArrayList<>();

    private final Map<BlockPos, BlockState> built = new LinkedHashMap<>();
    private BlockPos entryFoot;

    private int ticks;
    private int checks;
    private int phase;
    private int watch;
    private boolean done;
    private BotPlayer probe;

    // ---- 相位 A 读数 ----
    private boolean hazardWhilePending;
    private boolean hazardStillAtEnd;
    private boolean taskAliveWhileHazard;
    private int safeStopsOnEnter;
    private String requested;
    private boolean requestedAirborne;

    // ---- 相位 B 读数 ----
    private boolean taskGoneAfterClear;
    private String lastResultAfterClear = "";

    public SurvivalStopInHazardCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "SurvivalStopInHazardCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(BOX_CENTER);
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
            check("整步必须在预算内跑完（" + BUDGET_TICKS + " tick）", false);
            return finish();
        }
        ServerLevel level = bot.serverLevel();
        switch (phase) {
            case 0 -> setup(level);
            case 1 -> phaseHazard(level);
            case 2 -> phaseCleared(level);
            case 3 -> {
                assertPhase();
                return finish();
            }
            default -> {
                return finish();
            }
        }
        return Task.Status.RUNNING;
    }

    private void setup(ServerLevel level) {
        forceload(level, true);
        // ⚠️ 只要求"**地面之上**这一带是空气"：盒底的 `dy=-1` 就是**天然地面**（实心，正常）
        // —— 实测踩到：第一版把它也算进"必须是空气"⇒ 9 个非空气 ⇒ 场景直接不建、探针为 null。
        int preExisting = 0;
        for (int dx = -SHELL_R; dx <= SHELL_R; dx++) {
            for (int dz = -SHELL_R; dz <= SHELL_R; dz++) {
                for (int dy = 0; dy <= SHAFT_HEIGHT; dy++) {
                    if (!level.getBlockState(BOX_CENTER.offset(dx, dy, dz)).isAir()) {
                        preExisting++;
                    }
                }
            }
        }
        if (preExisting != 0) {
            check("硬前提失败：SCENE_NOT_AIR（地面之上有 " + preExisting + " 个非空气方块）", false);
            phase = 3;
            return;
        }
        check("前提：天然地面实心（盒底 " + BOX_CENTER.below(1).toShortString() + "）",
                !level.getBlockState(BOX_CENTER.below(1)).isAir());
        // 3×3 石壳（dy = -1..SHAFT_HEIGHT）+ 中心 1×3 竖井
        for (int dx = -SHELL_R; dx <= SHELL_R; dx++) {
            for (int dz = -SHELL_R; dz <= SHELL_R; dz++) {
                for (int dy = -1; dy <= SHAFT_HEIGHT; dy++) {
                    BlockPos pos = BOX_CENTER.offset(dx, dy, dz);
                    boolean shaft = dx == 0 && dz == 0 && dy >= 0 && dy < SHAFT_HEIGHT;
                    set(level, pos, shaft ? Blocks.AIR : Blocks.STONE);
                }
            }
        }
        check("前提：竖井底部可站（脚位+头位都可穿过）",
                com.dddgn.alice.pathing.MovementHelper.bodyPassable(level, BOX_CENTER)
                        && com.dddgn.alice.pathing.MovementHelper.canWalkOn(level, BOX_CENTER));

        entryFoot = bot.blockPosition();
        probe = BotManager.spawn(level, BOX_CENTER, PROBE_NAME);
        if (probe == null) {
            check("硬前提失败：探针假人生成失败（" + PROBE_NAME + "）", false);
            phase = 3;
            return;
        }
        com.dddgn.alice.decision.GoalDirector.suspend(probe, BUDGET_TICKS + 200);
        // 给它一个 dummy 任务（**只求"手上有活"**）。⚠️ 不能用 `WalkToTask`：目标就是它自己那格 ⇒
        // 请求退化 ⇒ 任务瞬间 DONE ⇒ `hasTask` 变假，判据全废（首版设计就踩在这里）。
        BotManager.assignTask(probe, new HoldTask(probe));
        check("前提：探针**有任务**（`hasTask`=true）", BotManager.hasTask(probe));
        // ⭐ 几何前提：半径 8 内**没有可规划的无液体落点** ⇒ 软危险会落在 `HOLD_NO_EXIT`（不中断任务）
        boolean plannable = SurvivalSystem.plannableRefuge(probe, HazardType.ON_FIRE, false) != null;
        findings.add("premise:plannableRefuge(ON_FIRE)=" + plannable + " bodyPassable="
                + com.dddgn.alice.pathing.MovementHelper.bodyPassable(level, BOX_CENTER));
        BotLog.info("[StopInHazard] 前提 盒体={} 探针脚位={} plannableRefuge(ON_FIRE)={}",
                BOX_CENTER.toShortString(), probe.blockPosition().toShortString(), plannable);
        check("前提：密封盒 ⇒ 半径 8 内**没有可规划落点**（实际 plannable=" + plannable
                        + "）—— 有出口的话软危险会走 `INTERRUPT`，本夹具就测不到 `HOLD_NO_EXIT` 那一档",
                !plannable);

        // 点着它（软危险 `ON_FIRE`）+ 从井顶扔下去（**在空中**才提停止请求 ⇒ 进 K-3 的延后档）
        probe.setRemainingFireTicks(20 * 60);
        safeStopsOnEnter = countSafeStops();
        // 顶部**安全**脚位（头位必须是空气 ⇒ 不能贴天花板，否则 `isInWall()` 判 SUFFOCATING）
        probe.teleportTo(level, BOX_CENTER.getX() + 0.5D, BOX_CENTER.getY() + SHAFT_HEIGHT - 2,
                BOX_CENTER.getZ() + 0.5D, Set.of(), probe.getYRot(), probe.getXRot());
        probe.setDeltaMovement(Vec3.ZERO);
        watch = 0;
        phase = 1;
    }

    private void phaseHazard(ServerLevel level) {
        // 火要一直烧（否则软危险提前解除，相位 A 就白测了）；同时**不许它被烧死**
        probe.setRemainingFireTicks(20 * 60);
        watch++;
        // ⭐ 在**空中**提一次延后停止（`safeToStopNow()` 为假 ⇒ 落在 K-3 的延后档；
        //    站在地上提 = 立即停止，根本不进这一档）
        if (requested == null && !probe.onGround()) {
            requested = BotManager.stopTask(probe, "b3_fixture");
            requestedAirborne = !probe.onGround();
            BotLog.info("[StopInHazard] 空中提请延后停止 requested={} onGround={} 危险={} 任务={}",
                    requested, probe.onGround(), SurvivalSystem.current(probe).type(),
                    BotManager.hasTask(probe));
            check("前提：延后停止**已被受理**（返回非空），且请求是在**空中**提的（requested=" + requested
                            + "，onGround=" + probe.onGround() + "）",
                    requested != null && !requested.isEmpty());
        }
        // ⚠️ 危险读数**不许只在某一 tick 采样**（首版在 `watch==1` 读 ⇒ 探针的会话可能还没 tick 过、
        // 分类仍是 NONE ⇒ 前提随机红，实测红对照那一跑就撞上了）⇒ 改成**闩锁 + 末态**双判据。
        if (SurvivalSystem.current(probe).type() == HazardType.ON_FIRE) {
            hazardWhilePending = true;
        }
        if (watch > HAZARD_WATCH_TICKS) {
            hazardStillAtEnd = SurvivalSystem.current(probe).type() == HazardType.ON_FIRE;
            // ⭐ 判据窗口从"**已经落地**之后"开始数：落地后 `safeToStopNow()` 会变真，
            //    旧行为正是在那一刻把停止落地的
            taskAliveWhileHazard = BotManager.hasTask(probe) && probe.onGround();
            BotLog.info("[StopInHazard] 相位A 结束 watch={} hazard={} hasTask={} health={}",
                    watch, SurvivalSystem.current(probe).type(), BotManager.hasTask(probe), probe.getHealth());
            phase = 2;
            watch = 0;
            return;
        }
        if (watch % 20 == 0) {
            BotLog.info("[StopInHazard] watch={} hazard={} hasTask={} health={}",
                    watch, SurvivalSystem.current(probe).type(), BotManager.hasTask(probe), probe.getHealth());
        }
    }

    private void phaseCleared(ServerLevel level) {
        // 危险解除：灭火（`ON_FIRE` 立刻消失 ⇒ `safeToStopNow` 那一侧也不再被 hazard 挡）
        probe.setRemainingFireTicks(0);
        probe.clearFire();
        watch++;
        boolean hazardGone = SurvivalSystem.current(probe).type() == HazardType.NONE;
        if (!BotManager.hasTask(probe) && hazardGone) {
            taskGoneAfterClear = true;
            lastResultAfterClear = BotManager.lastTaskResult(probe);
            BotLog.info("[StopInHazard] 相位B 停止已落地（watch={} lastResult={}）", watch,
                    lastResultAfterClear);
            phase = 3;
            return;
        }
        if (watch > AFTER_CLEAR_CAP) {
            BotLog.info("[StopInHazard] 相位B 超时未落地（hazardGone={} hasTask={}）", hazardGone,
                    BotManager.hasTask(probe));
            phase = 3;
        }
    }

    private void assertPhase() {
        findings.add("requested=" + requested + " requestedAirborne=" + requestedAirborne
                + " hazardWhilePending=" + hazardWhilePending + " hazardStillAtEnd=" + hazardStillAtEnd
                + " taskAliveWhileHazard="
                + taskAliveWhileHazard + " taskGoneAfterClear=" + taskGoneAfterClear
                + " lastResult=" + lastResultAfterClear + " safeStopsOnEnter=" + safeStopsOnEnter
                + " taskKind=" + (probe == null ? "-（探针未生成 ⇒ 硬前提已红）"
                        : BotManager.currentTaskKind(probe)));
        BotLog.info("[StopInHazard] 读数 危险中(有危险={} 任务还在={}) 解除后(任务已停={}) lastResult={}",
                hazardWhilePending, taskAliveWhileHazard, taskGoneAfterClear, lastResultAfterClear);

        check("前提：相位 A 里探针**真的着过火**（`ON_FIRE`，闩锁=" + hazardWhilePending + "）"
                        + "且**末态仍在着火**（实际 " + hazardStillAtEnd + "）—— 相位 A 的意义就是"
                        + "「危险一直活着，停止却不许落地」",
                hazardWhilePending && hazardStillAtEnd);
        check("⭐ B3：**活动危险中延后停止不许落地** —— 危险还在（" + HAZARD_WATCH_TICKS
                        + " tick > 普通上限 20）时任务必须还在（实际 hasTask=" + taskAliveWhileHazard + "）",
                taskAliveWhileHazard);
        check("⭐ B3：危险解除后，延后停止**必须落地**（任务已停=" + taskGoneAfterClear
                        + "，lastResult=" + lastResultAfterClear + "）", taskGoneAfterClear);
    }

    /** 夹具自己的会话里"已到安全点而落地"的计数（**不是**探针的）—— 只做旁证。 */
    private int countSafeStops() {
        String described = BotManager.describeSafeStops(bot);
        int idx = described.indexOf("deferred=");
        if (idx < 0) {
            return -1;
        }
        int end = described.indexOf(' ', idx);
        try {
            return Integer.parseInt(described.substring(idx + "deferred=".length(),
                    end < 0 ? described.length() : end));
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    // ==================== 世界读写 ====================

    private void set(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.Block block) {
        built.putIfAbsent(pos, level.getBlockState(pos));
        level.setBlockAndUpdate(pos, block.defaultBlockState());
    }

    private void forceload(ServerLevel level, boolean on) {
        for (int cx = (BOX_CENTER.getX() - 2) >> 4; cx <= (BOX_CENTER.getX() + 2) >> 4; cx++) {
            for (int cz = (BOX_CENTER.getZ() - 2) >> 4; cz <= (BOX_CENTER.getZ() + 2) >> 4; cz++) {
                level.setChunkForced(cx, cz, on);
            }
        }
    }

    private Task.Status finish() {
        done = true;
        ServerLevel level = bot.serverLevel();
        if (probe != null && !probe.isRemoved()) {
            probe.clearFire();
            BotLog.info("[StopInHazard] 拆除探针假人 {}（health={}）", PROBE_NAME, probe.getHealth());
            BotManager.remove(probe);
        }
        for (Map.Entry<BlockPos, BlockState> entry : built.entrySet()) {
            level.setBlockAndUpdate(entry.getKey(), entry.getValue());
        }
        forceload(level, false);
        bot.controller().stopMovement();
        if (entryFoot != null) {
            bot.teleportTo(level, entryFoot.getX() + 0.5D, entryFoot.getY(), entryFoot.getZ() + 0.5D,
                    Set.of(), bot.getYRot(), bot.getXRot());
            bot.setDeltaMovement(Vec3.ZERO);
        }
        boolean pass = failures.isEmpty();
        BotLog.info("[StopInHazard] SUMMARY checks={} failures={} → {}｜发现：{}｜失败项：{}",
                checks, failures.size(), pass ? "PASS" : "FAIL", findings, failures);
        if (observer != null && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] B3「危险中延后停止不许落地」取证 "
                    + (pass ? "PASS" : "FAIL") + "（详见日志 [StopInHazard]）"));
        }
        return pass ? Task.Status.DONE : Task.Status.FAILED;
    }

    /**
     * 夹具用的**永不结束**的 dummy 任务：存在感 = "bot 手上有活"。
     *
     * <p>类名**故意**不含 `check`/`probe`/`dump` 等字样 ⇒ `Task.isSelfCheck()` 默认为假
     * （自检任务会触发决策层的 hold，那不是本夹具要测的东西）。
     */
    private static final class HoldTask implements Task {
        private final BotPlayer bot;

        HoldTask(BotPlayer bot) {
            this.bot = bot;
        }

        @Override
        public TaskTarget target() {
            return TaskTarget.block(bot.blockPosition());
        }

        @Override
        public String failureReason() {
            return "";
        }

        @Override
        public Status tick() {
            return Status.RUNNING;
        }
    }

    private void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures.add(what);
        }
    }
}
