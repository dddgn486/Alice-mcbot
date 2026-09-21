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
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * ⭐ **`D-377` 取证：一个「没有任务」的 bot 在水里沉底时，维生必须仍然动手**（电池步 `survival_idle_drown`）。
 *
 * <h2>它钉住的是什么</h2>
 * <b>危险处理不许挂在任务上。</b>`BotSession.tick(HazardState)` 里那句 `if (task == null) return;`
 * 的原意是"没有任务就没有可中断/可放弃的东西"，但它把 **`decide` 本身**也跳过了 ⇒
 * <b>空闲的 bot 在水里沉底/在火里/被埋时，维生零动作、零日志</b>。
 *
 * <h2>真机事故（第八轮 17:56–17:57，逐字见 `docs/AI_DECISIONS.md` `D-377`）</h2>
 * ```
 * 17:56:50.569 task_execution_terminal kind=RestoreScope terminal=CANCELLED_BY_USER
 * 17:56:50.571 任务在**不安全时刻被强制停止**（累计 1）        ← 延后停止落地 ⇒ task = null
 * 17:57:00.233 [SurvProbe] enter type=LOW_AIR duration=20 …    ← 危险处理还"进得来"
 * （之后 340 tick：verdict 再没打过、无 [Survival] 日志、air 300→-2、health 20→1.0）
 * ```
 * ⚠️ 探针当时打的是 `taskKind`（**陈旧字符串**，仍是 `RestoreScope`）⇒ 把 `task == null` 掩盖了；
 * 现在探针与 {@link BotManager#hasTask} 都能直接读出这件事。
 *
 * <h2>为什么必须用**第二个假人**</h2>
 * 夹具本身是一个 `Task` ⇒ 在"跑夹具的那个 bot"身上 `task != null`，**永远测不到**无任务这条路径。
 * ⇒ 本夹具另起一只**天然无任务**的假人（{@link BotManager#spawn}），把它放进 1 格宽的水井底部，
 * 观察生产路径（`BotManager` 每 tick 的 `SurvivalSystem.tick` → `tickHazardWithoutTask`）。
 *
 * <h2>前提（红了说明夹具坏，不是缺陷证据）</h2>
 * ① 探针 bot 真的在水里且**眼睛在水里**（整只没入水中）；② 它**没有任务**（`hasTask=false`）；
 * ③（`B2` 生效）`SurvivalSystem.tick(probe).type() == LOW_AIR` —— 空气 ≤ {@link SurvivalSystem#DROWN_PRECURSOR_AIR}
 * 且眼在水里；④ 判决是 `FLOAT_UP`（1 格宽水井 ⇒ 规划不出"走出去"的路 ⇒ 几何落点即使存在也不可规划）。
 *
 * <h2>判据（`B1`）</h2>
 * 在 {@link #WATCH_TICKS} 内：**出现了一次任务**（`hasTask` 由 false 变 true = 无任务路径真的起了自救），
 * 且探针 bot 的头**露出过水面**并且空气回到 ≥ {@link SurvivalFloatTask#AIR_SAFE}（= 自救真的成功）。
 * ⇒ 修复前（`task == null` 直接 return）这里必然红：空气只会一路掉到 0 然后开始掉血。
 *
 * <h2>诚实边界</h2>
 * 本夹具把空气**直接设成** {@link #AIR_START}（60）而不是慢慢等它掉 —— 那样只是把 200 tick 的等待换成
 * 一次 `setAirSupply`，**被测的那条生产路径完全相同**（分类 → 判决 → 无任务动作），并且前提③④逐条自证。
 * 另外它**不测**"自救成功后 bot 会缓慢再次下沉"（那是"维持浮力"的另一个议题，见 `D-377` 未做项），
 * 只看**第一次**自救是否发生并成功。
 */
public final class SurvivalIdleDrownCheckTask implements Task {

    /** 专用孤立点（超平坦世界里的一块空地，离其它场景 ≥200 格）。 */
    private static final BlockPos ORIGIN = new BlockPos(3000, -60, 4200);

    /**
     * 水井深度（格）：`ORIGIN.below(1..DEPTH)` 全部灌水，探针 bot 站在最底那一格，
     * 井底（`ORIGIN.below(DEPTH + 1)`）**自己补一块实心**。
     *
     * <p>⚠️ 实测（2026-09-21）：无头测试世界的地面**只有 3 层**（`y=-61..-63`），
     * `y=-64` 以下读到的是 **Void Air**（放不下方块）⇒ `DEPTH=4` 会把地板挖穿、`setBlock` 静默无效、
     * bot 一出生就在虚空里（红态那次掉到 `y=-70`，把"没被救"搅成"掉出去了"）。
     * 取 **3** 正好：水面在 `-61`，bot 站在 `-63`（眼睛 `-61.38` 仍在水中 ⇒ 满足"整只没入"的前提），
     * 井底落在 `-64`（可放置）。
     */
    private static final int DEPTH = 3;

    /** 空气初值：**必须** ≤ {@link SurvivalSystem#DROWN_PRECURSOR_AIR}（否则前提③不成立）。 */
    private static final int AIR_START = 60;

    /** 观察窗口：上浮 4 格 + 空气回满在实测里 < 100 tick；给 300 tick 余量。 */
    private static final int WATCH_TICKS = 300;

    private static final int SETTLE_TICKS = 20;
    private static final int BUDGET_TICKS = 600;

    private static final String PROBE_NAME = "drown_probe";

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private final List<String> findings = new ArrayList<>();

    private final List<BlockPos> shaft = new ArrayList<>();
    private final List<BlockState> shaftOriginal = new ArrayList<>();

    private int ticks;
    private int checks;
    private int phase;
    private int settle;
    private int watch;
    private boolean done;
    private boolean sawTask;
    private boolean sawLowAir;
    private boolean sawLowAirWhileBreathing;
    private int firstTaskAir;
    private boolean sawEyeOut;
    private int maxAir;
    private BotPlayer probe;

    public SurvivalIdleDrownCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "SurvivalIdleDrownCheck";
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
            check("整步必须在预算内跑完（" + BUDGET_TICKS + " tick）", false);
            return finish();
        }
        ServerLevel level = bot.serverLevel();
        switch (phase) {
            case 0 -> {
                // 观察者（跑夹具的这只 bot）站在**干地**上：别让它自己也掉进水里把两件事搅在一起
                teleport(bot, ORIGIN.offset(4, 0, 0));
                buildShaft(level);
                phase = 1;
                return Task.Status.RUNNING;
            }
            case 1 -> {
                if (settle++ < SETTLE_TICKS) {
                    return Task.Status.RUNNING;
                }
                probe = BotManager.spawn(level, ORIGIN.below(DEPTH), PROBE_NAME);
                if (probe == null) {
                    check("前提：能生成探针假人（" + PROBE_NAME + "）", false);
                    return finish();
                }
                // 别让决策层（LLM）给探针派活 —— 那会让它**有任务**，本夹具测的东西就没了
                com.dddgn.alice.decision.GoalDirector.suspend(probe, BUDGET_TICKS + 200);
                settle = 0;
                phase = 2;
                return Task.Status.RUNNING;
            }
            case 2 -> {
                if (settle++ < SETTLE_TICKS) {
                    return Task.Status.RUNNING;
                }
                // ⚠️ 空气一设下去就**当场**读前提：此时宽限期（{@link SurvivalSystem#SOFT_HAZARD_GRACE_TICKS}）
                // 还没过 ⇒ 维生必然还没动手 ⇒ `hasTask` 一定是 false（无竞态）。
                // 判决那条用 `synthetic(...)` 跳过宽限期，否则就是在"宽限期内"断言"宽限后该有的判决"。
                probe.setAirSupply(AIR_START);
                runPremises(level);
                settle = 0;
                phase = 4;
                return Task.Status.RUNNING;
            }
            case 4 -> {
                watch++;
                if (probe.isRemoved() || probe.getHealth() <= 0.0F) {
                    check("⭐ 探针 bot 在观察窗口内**死亡/被移除** ⇒ 无任务的溺水确实**没人管**"
                            + "（这正是 `D-377` 的缺陷本体；watch=" + watch + " air=" + probe.getAirSupply() + "）",
                            false);
                    return finish();
                }
                int air = probe.getAirSupply();
                maxAir = Math.max(maxAir, air);
                HazardType seen = SurvivalSystem.tick(probe).type();
                sawLowAir |= seen == HazardType.LOW_AIR;
                // ⭐ 这一档的**作用域**：空气还没耗尽时，共享分类表**仍是** WATER_CONTACT（不改 classify）
                sawLowAirWhileBreathing |= seen == HazardType.LOW_AIR && air > 0;
                if (!sawTask && BotManager.hasTask(probe)) {
                    sawTask = true;
                    firstTaskAir = air;      // 自救是在"空气还够"时就开始，还是等到耗尽？
                }
                boolean eyeOut = !probe.isEyeInFluid(FluidTags.WATER);
                sawEyeOut |= eyeOut;
                sawTask |= BotManager.hasTask(probe);
                if (watch % 20 == 1) {
                    BotLog.info("[IdleDrown] watch={} air={} maxAir={} eyeOut={} hasTask={} hazard={} y={}",
                            watch, air, maxAir, eyeOut, BotManager.hasTask(probe), seen,
                            String.format("%.2f", probe.getY()));
                }
                boolean recovered = maxAir >= SurvivalFloatTask.AIR_SAFE && sawEyeOut;
                if (recovered || watch >= WATCH_TICKS) {
                    runJudgement(recovered);
                    return finish();
                }
                return Task.Status.RUNNING;
            }
            default -> {
                return finish();
            }
        }
    }

    // ==================== 场景 ====================

    private void buildShaft(ServerLevel level) {
        if (!shaft.isEmpty()) {
            return;
        }
        for (int d = 1; d <= DEPTH; d++) {
            BlockPos cell = ORIGIN.below(d);
            shaft.add(cell);
            shaftOriginal.add(level.getBlockState(cell));
        }
        // ⚠️ 井底必须**自己补一块实心**（实测教训：超平坦测试世界的地面只有 4 层 ⇒ 挖 4 格就挖穿了，
        // 底下的空气会让 bot 掉进虚空 —— 红态那次它掉到 y=-70，把"没被救"这件事搅成了"掉出去了"）
        BlockPos floor = ORIGIN.below(DEPTH + 1);
        shaft.add(floor);
        shaftOriginal.add(level.getBlockState(floor));
        level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        for (int d = 1; d <= DEPTH; d++) {
            level.setBlockAndUpdate(ORIGIN.below(d), Blocks.WATER.defaultBlockState());
        }
        check("前提：水井真的灌满了水（" + DEPTH + " 格）+ 井底实心（bot 有地方站）",
                java.util.stream.IntStream.range(1, DEPTH + 1)
                        .allMatch(d -> level.getBlockState(ORIGIN.below(d)).is(Blocks.WATER))
                        && !level.getBlockState(ORIGIN.below(DEPTH + 1)).isAir());
    }

    // ==================== 前提与判据 ====================

    private void runPremises(ServerLevel level) {
        boolean submerged = probe.isInWater() && probe.isEyeInFluid(FluidTags.WATER);
        check("前提：探针 bot 真的在水里且**眼睛在水里**（inWater=" + probe.isInWater()
                + " eyeInWater=" + probe.isEyeInFluid(FluidTags.WATER) + "）", submerged);
        check("前提：探针 bot **没有任务**（`BotManager.hasTask`=false）—— 这正是被测的那条路径",
                !BotManager.hasTask(probe));

        double eyeY = probe.getEyeY();
        // ⚠️ 分类（`B2`）**不在这一 tick 断言**：本夹具跑在 bot #1 的会话里，而探针 bot 的会话
        // 可能**已经先 tick 过**同一 game tick ⇒ `SurvivalSystem.tick` 会返回**缓存**的旧分类
        // （实测：刚设完 air=60 仍读到 `WATER_CONTACT`）。⇒ 改成观察期逐 tick 记录（见 `sawLowAir`）。
        // 判决要**跳过宽限期**读（`decide` 在宽限期内恒 IGNORE；夹具若当场断言"判决=FLOAT_UP"，
        // 那是在测夹具自己的时序，不是测缺陷）⇒ 用 `synthetic(..., grace+1)` 那一档。
        com.dddgn.alice.survival.HazardState afterGrace =
                synthetic(HazardType.LOW_AIR, SurvivalSystem.SOFT_HAZARD_GRACE_TICKS + 1);
        SurvivalSystem.Verdict verdict = SurvivalSystem.decide(probe, afterGrace, false);
        check("前提：宽限期之后的判决必须是 FLOAT_UP（1 格宽水井 ⇒ 走不出去、但浮得上去；实际 "
                        + verdict + "）—— 这条是 `D-377` 修复要动作的那一档",
                verdict == SurvivalSystem.Verdict.FLOAT_UP);
        StringBuilder col = new StringBuilder();
        for (int d = 1; d <= DEPTH + 3; d++) {
            col.append(ORIGIN.below(d).getY()).append('=')
                    .append(level.getBlockState(ORIGIN.below(d)).getBlock().getName().getString()).append(' ');
        }
        // 几何自证（`P3` 的教训：探针类工具必须自己断言几何前提，否则"没测到"会伪装成"测到了"）
        BotLog.info("[IdleDrown] 几何自证 列={} probeY={} onGround={} blockPos={}",
                col, String.format("%.2f", probe.getY()), probe.onGround(),
                probe.blockPosition().toShortString());
        findings.add("premise:submerged=" + submerged + " hasTask=" + BotManager.hasTask(probe)
                + " verdict(afterGrace)=" + verdict + " air=" + probe.getAirSupply()
                + " eyeY=" + String.format("%.2f", eyeY) + " foot=" + probe.blockPosition().toShortString());
        BotLog.info("[IdleDrown] 前提成立（当场）：verdict={} air={} hasTask=false submerged=true foot={} eyeY={}",
                verdict, probe.getAirSupply(), probe.blockPosition().toShortString(),
                String.format("%.2f", eyeY));
    }

    /** 合成一条危险状态（**只换类型与已持续 tick**，其余取 bot 当前真实值）。 */
    private com.dddgn.alice.survival.HazardState synthetic(HazardType type, int duration) {
        return new com.dddgn.alice.survival.HazardState(type, duration, probe.getAirSupply(),
                probe.getHealth(), probe.getHealth(), probe.blockPosition());
    }

    private void runJudgement(boolean recovered) {
        check("⭐ 沉底档（**作用域**）：air ∈ (0, " + SurvivalSystem.DROWN_PRECURSOR_AIR
                        + "] 且眼在水里时，**共享分类表仍是 WATER_CONTACT**（实际在呼吸中被判 LOW_AIR 的次数="
                        + (sawLowAirWhileBreathing ? ">0" : "0") + "）—— 这一档只对**无任务**生效、不改 "
                        + "`classify`（第一版改 classify 直接把电池步 `survival_exit` 判成真溺水 ⇒ 整轮 no_verdict）",
                !sawLowAirWhileBreathing);
        check("⭐ 沉底档：自救必须在**空气还够的时候**就开始（首个任务出现在 air=" + firstTaskAir
                        + "，必须 > 0；若等到 0 才动，就是「白等 15 秒」的老症状）",
                sawTask && firstTaskAir > 0);
        check("⭐ `B1`：**无任务**的 bot 也必须被维生接管 —— 观察窗口内必须出现一次任务"
                        + "（`hasTask` 由 false 变 true = 无任务路径真的起了自救；实际 sawTask=" + sawTask + "）",
                sawTask);
        check("⭐ `B1`：头必须**露出过水面**（sawEyeOut=" + sawEyeOut + "，最高空气 " + maxAir + "）",
                sawEyeOut);
        check("⭐ `B1`：空气必须回到 ≥ AIR_SAFE=" + SurvivalFloatTask.AIR_SAFE + "（实际最高 " + maxAir
                        + "）—— 修复前只会一路掉到 0 然后开始掉血",
                maxAir >= SurvivalFloatTask.AIR_SAFE);
        findings.add("judgement:recovered=" + recovered + " sawTask=" + sawTask + " sawLowAir=" + sawLowAir
                + " firstTaskAir=" + firstTaskAir + " sawEyeOut=" + sawEyeOut
                + " maxAir=" + maxAir + " watchTicks=" + watch + " health=" + probe.getHealth());
    }

    // ==================== 收尾 ====================

    private Task.Status finish() {
        done = true;
        ServerLevel level = bot.serverLevel();
        if (probe != null && !probe.isRemoved()) {
            BotLog.info("[IdleDrown] 拆除探针假人 {}（air={} y={}）", PROBE_NAME,
                    probe.getAirSupply(), String.format("%.2f", probe.getY()));
            BotManager.remove(probe);
        }
        for (int i = 0; i < shaft.size(); i++) {
            level.setBlockAndUpdate(shaft.get(i), shaftOriginal.get(i));
        }
        teleport(bot, ORIGIN.offset(4, 0, 0));
        bot.controller().stopMovement();

        boolean pass = failures.isEmpty();
        BotLog.info("[IdleDrown] SUMMARY checks={} failures={} 观察={} tick → {}｜发现：{}｜失败项：{}",
                checks, failures.size(), watch, pass ? "PASS" : "FAIL", findings, failures);
        if (observer != null && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 无任务溺水自救取证 "
                    + (pass ? "PASS" : "FAIL") + "（详见日志 [IdleDrown]）"));
        }
        return pass ? Task.Status.DONE : Task.Status.FAILED;
    }

    private void teleport(BotPlayer target, BlockPos foot) {
        ServerLevel level = target.serverLevel();
        target.teleportTo(level, foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                Set.of(), target.getYRot(), target.getXRot());
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
