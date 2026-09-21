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
 * ③（分类事实）空气 > 0 且眼在水里时共享分类表仍给 `WATER_CONTACT`（`LOW_AIR` 只在空气耗尽时才出现）；
 * ④ 判决是 `FLOAT_UP`（1 格宽水井 ⇒ 规划不出"走出去"的路 ⇒ 几何落点即使存在也不可规划）。
 *
 * <h2>判据（`B1` + `D-380` 口径）</h2>
 * 在 {@link #WATCH_TICKS} 内：**出现了一次任务**（`hasTask` 由 false 变 true = 无任务路径真的起了自救），
 * 且探针 bot 的头**露出过水面**并且空气回到 ≥ {@link SurvivalFloatTask#AIR_SAFE}（= 自救真的成功）。
 * ⭐ 并且这次自救必须在**空气还很满**时就发生（`firstTaskAir > {@link SurvivalSystem#DROWN_PRECURSOR_AIR}`）
 * —— 第九轮客户端实测老口径要等 air 从 300 掉到 100（约 10 秒）才动手，用户看到的是「没有浮出来」
 * （`D-380` 把无任务档改成「眼在水里就浮」，本断言就是那条口径的**可红判据**）。
 * ⇒ 修复前（`task == null` 直接 return）这里必然红：空气只会一路掉到 0 然后开始掉血。
 *
 * <h2>诚实边界</h2>
 * 本夹具把空气**直接设成** {@link #AIR_START}（200）而不是慢慢等它掉 —— 那样只是把 200 tick 的等待换成
 * 一次 `setAirSupply`，**被测的那条生产路径完全相同**（分类 → 判决 → 无任务动作），并且前提③④逐条自证。
 * 取 200（> 旧阈值）是为了让上面那条 `firstTaskAir > 旧阈值` 有判别力。
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

    /**
     * 空气初值：**必须 > {@link SurvivalSystem#DROWN_PRECURSOR_AIR}**（`D-380` 之后这就是判据本身）。
     *
     * <p>为什么改成 200（原 60）：`D-380` 把无任务档的「`air ≤ 旧阈值`」改成了「眼在水里就浮」
     * ⇒ 夹具必须让空气**还远高于旧阈值**，这样「首个任务出现在 air > 旧阈值」才是一条**有判别力**的断言
     * （若阈值复活，自救会等到 100 才发生 ⇒ 这条当场红）。
     */
    private static final int AIR_START = 200;

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
    /** 头**第一次露出水面**时的空气（`D-380` 口径的可观测判据）。 */
    private int firstEyeOutAir;
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
                // ⭐ `D-380`（2026-09-21 第九轮客户端）：**前提必须"出生就测"**。
                // 为什么（实测踩到）：新口径是「眼一进水就浮」⇒ 旧写法在这里再静置 20 tick 的话，
                // 探针**自己就浮到水面了**（实测 `foot=-61`、`eyeInWater=false`、`eyeY=-59.35`）⇒
                // 前提「眼在水里」「没有任务」「判决=FLOAT_UP」全部当场失效（判决还变成 `INTERRUPT`，
                // 因为浮到水面后水面格成了"可站的安全点"）—— 那是**修复生效**的副作用，不是缺陷。
                probe.setAirSupply(AIR_START);
                runPremises(level);
                settle = 0;
                phase = 4;            // 直接进观察期（不再有第二段静置）
                return Task.Status.RUNNING;
            }
            // ⚠️ `case 2`（第二段静置后读前提）已由 `D-380` **删除**：新口径下探针会在那 20 tick 里
            // 自己浮到水面（见 `case 1` 的注释）⇒ 前提必须与"生成探针"同一 tick 读。
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
                if (watch == 1) {
                    // 「眼睛真的在水里」在生成那一 tick 读不可靠（实体缓存）⇒ 挪到第一个观察 tick
                    runSubmergedPremise();
                }
                HazardType seen = SurvivalSystem.tick(probe).type();
                sawLowAir |= seen == HazardType.LOW_AIR;
                // ⭐ 这一档的**作用域**：空气还没耗尽时，共享分类表**仍是** WATER_CONTACT（不改 classify）
                sawLowAirWhileBreathing |= seen == HazardType.LOW_AIR && air > 0;
                if (!sawTask && BotManager.hasTask(probe)) {
                    sawTask = true;
                    firstTaskAir = air;      // 自救是在"空气还够"时就开始，还是等到耗尽？
                }
                // ⭐ `D-380`：**口径的可观测判据** —— 头**第一次露出水面**时空气还剩多少。
                // 为什么不用「首个任务出现时的空气」（`firstTaskAir`）：`D-380` 之后自救可能**在一两个
                // tick 内完成**（水井只有 3 格深、空气本来就 > `AIR_SAFE`）⇒ `hasTask` 那一瞬会被采样漏掉
                // （实测：红态 `firstTaskAir=100`、绿态 `sawTask=false` 但头已出水）。
                // 而「眼出水那一刻的空气」是**一个 tick 的事件**，采样一定看得见，且判别力相同：
                // 阈值活着时它必然 ≈ 旧阈值（等 10 秒才浮），阈值删掉后它必然 ≈ `AIR_START`。
                if (firstEyeOutAir == 0 && !probe.isEyeInFluid(FluidTags.WATER)) {
                    firstEyeOutAir = air;
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

    /**
     * **生成那一 tick**能可靠读到的前提（位置 / 无任务 / 判决）。
     *
     * <p>⚠️ **流体的物理读数不在这里读**：`isInWater` / `isEyeInFluid` 来自实体的**缓存**，
     * 刚 `spawn` 的那一 tick 还是旧的（实测：位置已是 `y=-63`、`getEyeY()=-61.38` 都对，
     * 但 `isInWater=false`）⇒ 那是 `fixture-hygiene` R4 的同一个坑（"传送那一 tick 的读数是旧的"）。
     * 它改在**第一个观察 tick** 读（见 {@link #runSubmergedPremise()}）。
     */
    private void runPremises(ServerLevel level) {
        check("前提：探针 bot 被生成在**水井底部**（foot=" + probe.blockPosition().toShortString()
                        + "，应为 " + ORIGIN.below(DEPTH).toShortString() + "）—— 位置是 spawn 当场就可靠的读数",
                probe.blockPosition().equals(ORIGIN.below(DEPTH)));
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
        findings.add("premise:hasTask=" + BotManager.hasTask(probe)
                + " verdict(afterGrace)=" + verdict + " air=" + probe.getAirSupply()
                + " eyeY=" + String.format("%.2f", eyeY) + " foot=" + probe.blockPosition().toShortString());
        BotLog.info("[IdleDrown] 前提成立（生成当场）：verdict={} air={} hasTask=false foot={} eyeY={}",
                verdict, probe.getAirSupply(), probe.blockPosition().toShortString(),
                String.format("%.2f", eyeY));
    }

    /**
     * **第一个观察 tick**读「眼睛真的在水里」——`isInWater`/`isEyeInFluid` 是实体缓存，spawn 那一 tick
     * 还不会更新（见 {@link #runPremises} 的注释）。这一刻救援最多才走了一两 tick（实测头要 ~15 tick
     * 才出水面）⇒ 读数仍然有效。
     */
    private void runSubmergedPremise() {
        boolean submerged = probe.isInWater() && probe.isEyeInFluid(FluidTags.WATER);
        check("前提：探针 bot 真的在水里且**眼睛在水里**（inWater=" + probe.isInWater()
                + " eyeInWater=" + probe.isEyeInFluid(FluidTags.WATER) + "）", submerged);
    }

    /** 合成一条危险状态（**只换类型与已持续 tick**，其余取 bot 当前真实值）。 */
    private com.dddgn.alice.survival.HazardState synthetic(HazardType type, int duration) {
        return new com.dddgn.alice.survival.HazardState(type, duration, probe.getAirSupply(),
                probe.getHealth(), probe.getHealth(), probe.blockPosition());
    }

    private void runJudgement(boolean recovered) {
        check("⭐ 沉底档（**作用域**）：air ∈ (0, " + AIR_START
                        + "] 且眼在水里时，**共享分类表仍是 WATER_CONTACT**（实际在呼吸中被判 LOW_AIR 的次数="
                        + (sawLowAirWhileBreathing ? ">0" : "0") + "）—— 这一档只对**无任务**生效、不改 "
                        + "`classify`（第一版改 classify 直接把电池步 `survival_exit` 判成真溺水 ⇒ 整轮 no_verdict）",
                !sawLowAirWhileBreathing);
        // ⭐ `D-380`（2026-09-21 第九轮客户端）：判据从「空气还剩一点就动」升级成
        // 「**空气还很满就得动**」—— 客户端实测的老口径要等 air 从 300 掉到 100（约 10 秒）才动手，
        // 用户看到的是「没有浮出来」。
        // ⚠️ 读数取「**头第一次露出水面时的空气**」而不是「首个任务出现时的空气」：`D-380` 之后自救可能
        // **在一两个 tick 内完成**（井只有 3 格深、空气本来就 > `AIR_SAFE`）⇒ `hasTask` 那一瞬会被逐 tick
        // 采样漏掉（实测绿态 `sawTask=false` 而头已出水）。「眼出水那一刻」是单 tick 事件，采样必然看见，
        // 判别力相同：阈值活着 ⇒ 它 ≈ 旧阈值（等 10 秒才浮）；阈值删掉 ⇒ 它 ≈ `AIR_START`。
        check("⭐ `D-380` 口径：头**第一次露出水面**时空气必须**还很满**（firstEyeOutAir=" + firstEyeOutAir
                        + "，必须 > 旧阈值 " + SurvivalSystem.DROWN_PRECURSOR_AIR
                        + "；阈值一旦复活，这个数必然 ≈ " + SurvivalSystem.DROWN_PRECURSOR_AIR + " ⇒ 红）",
                firstEyeOutAir > SurvivalSystem.DROWN_PRECURSOR_AIR);
        check("⭐ `B1`（**行为证据**）：**无任务**的 bot 也必须被维生接管 —— 头必须真的**露出过水面**"
                        + "（sawEyeOut=" + sawEyeOut + "；修复前它只会一路沉到 air=0 再掉血）", sawEyeOut);
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
