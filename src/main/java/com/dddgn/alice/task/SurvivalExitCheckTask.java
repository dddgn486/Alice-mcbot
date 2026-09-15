package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.BotEventLog;
import com.dddgn.alice.decision.EventThresholds;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.survival.HazardState;
import com.dddgn.alice.survival.HazardType;
import com.dddgn.alice.survival.SurvivalSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * **维生决策自检**（S-5，2026-09-15）—— 电池步 {@code survival_exit}。
 *
 * <p>为什么要有它：维生此前是**唯一零电池步的子系统**（`RegressionBatteryTask` 里 `Survival` 命中 0），
 * 只能靠真人点 `alice:survival_exit_check`。S-5 又把"溺水/着火"接进了否决链，判据变多了 ⇒ 必须有离线门禁。
 *
 * <p>它断言什么（`checks` 计数进 SUMMARY，任一失败即整步 FAIL）：
 * <ol>
 *   <li>**决策表**（{@link SurvivalSystem#decide}）：硬危险无条件否决；涉水/无危险不否决；
 *       软危险**宽限期内**不否决、过宽限**且有出口**才否决；理由码进 `survival_*` 词汇表；</li>
 *   <li>**封闭场景（无出口）**：着火/冻结 ⇒ {@code HOLD_NO_EXIT}（**不否决**，可能自愈）、
 *       **溺水 ⇒ {@code ABANDON_NO_EXIT}（放弃任务，D-236：无出口时不动手必死）**，而硬危险**仍然**否决；</li>
 *   <li>**真实软危险端到端**：在封闭场景里真的点着 bot ⇒ 真的不否决（否则整轮电池会被维生打断，
 *       代码根本走不到下一行）+ 事件环里确实有 `exit=none decision=continue` 这条事实；</li>
 *   <li>**出口能用**：拿维生给的真实落点起一个真实 {@link SurvivalExitTask}，必须**走到**
 *       （"选了落点却没走到"是 S-1 留下的观察盲区）；</li>
 *   <li>**掉血可见**（{@code HazardState.previousHealth} 的第一个读者）：真的扣血 ⇒ 一条带 `delta=` 的
 *       `DANGER` 事件，且冷却期内**不刷屏**。</li>
 * </ol>
 *
 * <p>⚠️ **结构性限制（必须如实说明，不要误以为这条步覆盖了整条否决链）**：
 * 电池步的**会话任务就是电池自己**，而维生否决会 `complete()` 掉会话任务 —— 所以在电池里
 * **不可能**跑"真实危险 ⇒ 真被否决 ⇒ 起逃生"这条端到端路径：那会**打死整轮电池**（连 SUMMARY 都没有）。
 * 因此：
 * <ul>
 *   <li>**能**在电池里验的是"决策 + 落点 + 逃生任务能走到 + 事实登记"（本类）；</li>
 *   <li>**只能真人验**的是"否决真的发生了"（`BotSession` 那一侧的接线）——
 *       入口 `alice:survival_exit_check`（右键 = 窒息有出口；潜行右键 = 着火有出口）。</li>
 * </ul>
 * 真实软危险（第 3 条）之所以**可以**在电池里点火，正是因为新判据"软危险无出口 ⇒ 不否决"——
 * 这是它被设计成"不否决"的直接好处之一（能被离线复现）。前提自证保证"万一其实有出口就**不点火**"。
 */
public class SurvivalExitCheckTask implements Task {

    /** 传送后等它落地的 tick 数（`SurvivalSystem.current` 的状态是**上一步**观察的，要等它刷新）。 */
    private static final int SETTLE_TICKS = 5;
    /** 逃生任务的单项预算（1~2 格的平地行走，正常 <40 tick）。 */
    private static final int WALK_BUDGET_TICKS = 300;
    /** 真实着火观察窗口：宽限期 + 余量（再久只是在烧 bot 的血）。 */
    private static final int REAL_FIRE_TICKS = SurvivalSystem.SOFT_HAZARD_GRACE_TICKS + 6;
    /** 扣血后等事件出现的余量（判据是"下一 tick 的观察"）。 */
    private static final int HEALTH_POLL_TICKS = 6;
    /** 冷却窗口内不该出现第二条掉血事件的观察时长（< {@link EventThresholds#HEALTH_LOSS_COOLDOWN_TICKS}）。 */
    private static final int HEALTH_QUIET_TICKS = 20;
    private static final float HURT_AMOUNT = 2.0F;
    /** 入水前把空气设成这个值：够掉、又**不会掉到 0**（0 ⇒ `LOW_AIR` 会真的否决 ⇒ 打死整轮电池）。 */
    private static final int AIR_START = 20;
    private static final int AIR_POLL_TICKS = 8;
    /** 全冻（140 tick）之后再等这么久，保证"每 40 tick 1 点"的冻结伤害至少来过一次。 */
    private static final int FREEZE_DAMAGE_WAIT_TICKS = 45;

    private enum Phase { SETUP, TABLE, WALK, SEALED_BUILD, SEALED_CHECK, FOOT_CELL, SEALED_REAL, HEALTH, AIR, SNOW, DEEP_WATER, DONE }

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();

    private Phase phase = Phase.SETUP;
    private int phaseTicks;
    private int checks;
    private boolean done;
    private BlockPos refuge;
    private Task subTask;
    private long fireTick;
    private long hurtTick;
    private int fireStartTicks;
    private float healthBeforeFire;
    private float minHealthDuringFire;
    private int airStart;
    private int snowStartFrozen;
    private int fullyFrozenAt;
    private float healthBeforeSnow;
    private float minHealthDuringSnow;

    public SurvivalExitCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "SurvivalExitCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(bot.blockPosition());
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
    public Status tick() {
        if (done) {
            return failures.isEmpty() ? Status.DONE : Status.FAILED;
        }
        phaseTicks++;
        switch (phase) {
            case SETUP -> setupPhase();
            case TABLE -> tablePhase();
            case WALK -> walkPhase();
            case SEALED_BUILD -> sealedBuildPhase();
            case SEALED_CHECK -> sealedCheckPhase();
            case FOOT_CELL -> footCellPhase();
            case SEALED_REAL -> sealedRealPhase();
            case HEALTH -> healthPhase();
            case AIR -> airPhase();
            case SNOW -> snowPhase();
            case DEEP_WATER -> deepWaterPhase();
            case DONE -> finish();
            default -> {
            }
        }
        return Status.RUNNING;
    }

    // ==================== 各阶段 ====================

    /** 就位：站到**安全**的平台角上，先证明"场景前提成立"再谈判据。 */
    private void setupPhase() {
        if (phaseTicks == 1) {
            teleport(SurvivalCourseAnchor.PLATFORM_FOOT);
            EventThresholds.resetHealthTracking(bot);   // 掉血基准从这一刻起算
            return;
        }
        if (phaseTicks < SETTLE_TICKS) {
            return;
        }
        HazardState now = SurvivalSystem.current(bot);
        check("起点必须是安全格（场景前提；实际 hazard=" + now.type() + "）", now.type() == HazardType.NONE);
        refuge = SurvivalSystem.nearestSafeRefuge(bot, SurvivalSystem.REFUGE_RADIUS, foot());
        check("有出口场景里必须能找到落点（半径 " + SurvivalSystem.REFUGE_RADIUS + "，实际 "
                + (refuge == null ? "无" : refuge.toShortString()) + "）", refuge != null);
        BotLog.info("[Survival] 就位 foot={} blockPosition={} y={} hazard={} refuge={}",
                foot().toShortString(), bot.blockPosition().toShortString(), bot.getY(),
                SurvivalSystem.current(bot).type(), refuge == null ? "无" : refuge.toShortString());
        advance(Phase.TABLE);
    }

    /** 决策表（纯逻辑；用真实 bot + 真实世界，但危险类型是合成的 —— 合成状态不会打断电池）。 */
    private void tablePhase() {
        HazardState lava = synthetic(HazardType.LAVA_CONTACT, 5);
        check("岩浆接触 ⇒ 否决（硬危险，与 S-1 行为一致）",
                SurvivalSystem.decide(bot, lava) == SurvivalSystem.Verdict.INTERRUPT);
        check("岩浆接触的理由码 = survival_lava_contact",
                "survival_lava_contact".equals(SurvivalSystem.interruptionReason(lava)));
        HazardState suffocating = synthetic(HazardType.SUFFOCATING, 5);
        check("窒息 ⇒ 否决（硬危险）",
                SurvivalSystem.decide(bot, suffocating) == SurvivalSystem.Verdict.INTERRUPT);
        check("窒息的理由码 = survival_suffocating",
                "survival_suffocating".equals(SurvivalSystem.interruptionReason(suffocating)));
        check("无危险 ⇒ 不否决",
                SurvivalSystem.decide(bot, synthetic(HazardType.NONE, 99)) == SurvivalSystem.Verdict.IGNORE);
        check("涉水 ⇒ 不否决（走过水面是正常动作，不该打断长作业）",
                SurvivalSystem.decide(bot, synthetic(HazardType.WATER_CONTACT, 99))
                        == SurvivalSystem.Verdict.IGNORE);
        check("溺水第 1 tick ⇒ 不否决（宽限期内不抖动）",
                SurvivalSystem.decide(bot, synthetic(HazardType.LOW_AIR, 1)) == SurvivalSystem.Verdict.IGNORE);
        check("着火第 1 tick ⇒ 不否决（宽限期内不抖动）",
                SurvivalSystem.decide(bot, synthetic(HazardType.ON_FIRE, 1)) == SurvivalSystem.Verdict.IGNORE);

        HazardState drowning = synthetic(HazardType.LOW_AIR, SurvivalSystem.SOFT_HAZARD_GRACE_TICKS);
        check("溺水过宽限 + 有出口 ⇒ 否决（S-5 新增）",
                SurvivalSystem.decide(bot, drowning) == SurvivalSystem.Verdict.INTERRUPT);
        check("溺水的理由码 = survival_low_air",
                "survival_low_air".equals(SurvivalSystem.interruptionReason(drowning)));
        HazardState burning = synthetic(HazardType.ON_FIRE, SurvivalSystem.SOFT_HAZARD_GRACE_TICKS);
        check("着火过宽限 + 有出口 ⇒ 否决（S-5 新增）",
                SurvivalSystem.decide(bot, burning) == SurvivalSystem.Verdict.INTERRUPT);
        check("着火的理由码 = survival_on_fire",
                "survival_on_fire".equals(SurvivalSystem.interruptionReason(burning)));
        check("冻结第 1 tick ⇒ 不否决（宽限期内不抖动）",
                SurvivalSystem.decide(bot, synthetic(HazardType.FREEZING, 1)) == SurvivalSystem.Verdict.IGNORE);
        HazardState freezing = synthetic(HazardType.FREEZING, SurvivalSystem.SOFT_HAZARD_GRACE_TICKS);
        check("冻结过宽限 + 有出口 ⇒ 否决（D-229 新增）",
                SurvivalSystem.decide(bot, freezing) == SurvivalSystem.Verdict.INTERRUPT);
        check("冻结的理由码 = survival_freezing",
                "survival_freezing".equals(SurvivalSystem.interruptionReason(freezing)));
        // ⚠️ 别在这张表里断言"冻结无出口 ⇒ 不否决"：**这里（平台）有出口** ⇒ `decide` 正确返回 INTERRUPT。
        // "无出口 ⇒ HOLD_NO_EXIT" 由**真实无出口**的封闭石壳相位覆盖（下面的细雪相位 + S-5 的闭合相位）。
        check("宽限不是「只报一次」：过了宽限很久仍在 ⇒ 仍然否决",
                SurvivalSystem.decide(bot, synthetic(HazardType.LOW_AIR,
                        SurvivalSystem.SOFT_HAZARD_GRACE_TICKS + 40)) == SurvivalSystem.Verdict.INTERRUPT);

        check("previousHealth 语义：本 tick 掉血 ⇒ healthLost()",
                new HazardState(HazardType.NONE, 0, bot.getAirSupply(), 18, 20, foot()).healthLost());
        check("previousHealth 语义：血量没变 ⇒ 不算掉血",
                !new HazardState(HazardType.NONE, 0, bot.getAirSupply(), 18, 18, foot()).healthLost());
        advance(refuge == null ? Phase.SEALED_CHECK : Phase.WALK);
    }

    /** 出口能用：真实 {@link SurvivalExitTask} 必须走到维生给的那个落点。 */
    private void walkPhase() {
        if (subTask == null) {
            subTask = new SurvivalExitTask(bot, refuge);
            BotLog.info("[Survival] 夹具拿维生给的真实落点起 SurvivalExitTask refuge={}（当前 {}）",
                    refuge.toShortString(), bot.blockPosition().toShortString());
            return;
        }
        Status status = subTask.tick();
        if (status == Status.RUNNING) {
            if (phaseTicks > WALK_BUDGET_TICKS) {
                check("逃生任务必须在预算内到终态（" + WALK_BUDGET_TICKS + " tick）", false);
                subTask = null;
                advance(Phase.SEALED_BUILD);
            }
            return;
        }
        check("逃生任务终态 = COMPLETED（实际 " + status + " reason=" + subTask.failureReason() + "）",
                status == Status.DONE);
        BlockPos arrived = foot();
        check("逃生终点 == 维生给出的落点 " + refuge.toShortString() + "（实际 " + arrived.toShortString() + "）",
                refuge.equals(arrived));
        subTask = null;
        advance(Phase.SEALED_BUILD);
    }

    /**
     * **建造封闭场景**（S-5）。
     *
     * <p>⚠️ 为什么不能放进电池步的 `scenes` 列表、也不能边站边建：
     * <ul>
     *   <li>首轮实测：服务端刚起时那一带区块还没加载，`scenes` 里的 `/fill` **一格都没落下**
     *       （bot 从 64 直落 50），而"半径内没有落点"这句判据在**虚空**里照样成立 ⇒ **因为错误的理由变绿**；</li>
     *   <li>第二轮实测：先传送（玩家票拉起区块）再建是对的，但 bot 就站在空腔里 —— `/fill` 把石头
     *       填进它脚下那一刻它已经下坠到 y=63（**埋在方块里**），几何自证于是读到
     *       `footCell=64 / blockPosition=63` 这种"看起来像真分歧"的假象。</li>
     * </ul>
     * 现在：传送（拉区块）→ 等落地 → 跑场景函数 → **再传送一次**（站到已建好的地面上）→ 再等落地
     * → 交给 {@link #sealedCheckPhase()} 做几何自证。
     *
     * <p>⚠️ **客户端轮次前必须先在游戏里 `/reload`**：`survival_sealed_course` 是**后加**的场景文件，
     * 只在数据包重载时才进内存。没 reload 时这里跑的就是一条不存在的函数（输出被抑制 ⇒ 看不出来），
     * 但随后的几何自证会**如实判红**（"脚下必须有真支撑"等），不会假绿。
     */
    private void sealedBuildPhase() {
        if (phaseTicks == 1) {
            teleport(SurvivalCourseAnchor.SEALED_FOOT);
            return;
        }
        if (phaseTicks == SETTLE_TICKS) {
            var server = bot.getServer();
            var source = server.createCommandSourceStack().withSuppressedOutput();
            server.getCommands().performPrefixedCommand(source, "function alice_test:survival_sealed_course");
            BotLog.info("[Survival] 封闭场景已建造（bot 到位后，区块已加载）：scene={} foot={}",
                    "alice_test:survival_sealed_course", SurvivalCourseAnchor.SEALED_FOOT.toShortString());
            return;
        }
        if (phaseTicks == SETTLE_TICKS * 2) {
            teleport(SurvivalCourseAnchor.SEALED_FOOT);   // 站在**已建好**的地面上，避免"埋在方块里"
            return;
        }
        if (phaseTicks >= SETTLE_TICKS * 3) {
            advance(Phase.SEALED_CHECK);
        }
    }

    /** 封闭场景（半径内**没有任何落点**）下的决策：软危险不否决、硬危险照样否决。 */
    private void sealedCheckPhase() {
        BlockPos here = foot();
        var level = bot.serverLevel();
        boolean supported = com.dddgn.alice.pathing.MovementHelper.canWalkOn(level, here);
        boolean headFree = com.dddgn.alice.pathing.MovementHelper.canWalkThrough(level, here.above());
        BlockPos foundHere = SurvivalSystem.nearestSafeRefuge(bot, SurvivalSystem.REFUGE_RADIUS, here);
        BotLog.info("[Survival] 几何自证 foot={} blockPosition={} y={} onGround={} inWall={} support={}"
                        + " head_free={} 脚下方块={} hazard={} refuge_from_foot={}",
                here.toShortString(), bot.blockPosition().toShortString(), bot.getY(), bot.onGround(),
                bot.isInWall(), supported, headFree, level.getBlockState(here.below()).getBlock(),
                SurvivalSystem.current(bot).type(), foundHere == null ? "无" : foundHere.toShortString());
        // **前提自证**（四层，缺一层就可能"因为错误的理由变绿"）：
        // ① bot 真的站在场景空腔里（不是还在坠/掉进虚空）；② 脚下真有支撑；③ 头位不在方块里；
        // ④ 半径内确实没有落点（**排除脚位格**，与生产同口径）。
        check("bot 必须站在封闭场景的空腔里（实际 " + here.toShortString() + "，期望 "
                + SurvivalCourseAnchor.SEALED_FOOT.toShortString() + "）",
                SurvivalCourseAnchor.SEALED_FOOT.equals(here));
        check("空腔脚下必须有真支撑（否则是虚空 ⇒ 判据会假绿）", supported);
        check("空腔头位必须可穿过（bot 不该卡在方块里）", headFree);
        check("站好之后不该卡在墙里（hazard=" + SurvivalSystem.current(bot).type() + "）",
                SurvivalSystem.current(bot).type() == HazardType.NONE);
        check("封闭场景必须真的没有落点（前提自证；实际 "
                + (foundHere == null ? "无" : foundHere.toShortString()) + "）", foundHere == null);
        // D-236（2026-09-15）：**溺水单独一档** —— 无出口时它不是"不否决"，而是"放弃任务"
        // （着火/冻结无出口仍可能自愈 ⇒ 保持 HOLD_NO_EXIT，见下两条）。
        check("溺水 + 无出口 ⇒ ABANDON_NO_EXIT（放弃任务，不是不否决）· 溺水（实际 "
                        + SurvivalSystem.decide(bot, synthetic(HazardType.LOW_AIR,
                        SurvivalSystem.SOFT_HAZARD_GRACE_TICKS)) + "）",
                SurvivalSystem.decide(bot, synthetic(HazardType.LOW_AIR, SurvivalSystem.SOFT_HAZARD_GRACE_TICKS))
                        == SurvivalSystem.Verdict.ABANDON_NO_EXIT);
        check("软危险 + 无出口 ⇒ HOLD_NO_EXIT（不否决）· 着火（实际 "
                        + SurvivalSystem.decide(bot, synthetic(HazardType.ON_FIRE,
                        SurvivalSystem.SOFT_HAZARD_GRACE_TICKS)) + "）",
                SurvivalSystem.decide(bot, synthetic(HazardType.ON_FIRE, SurvivalSystem.SOFT_HAZARD_GRACE_TICKS))
                        == SurvivalSystem.Verdict.HOLD_NO_EXIT);
        check("硬危险 + 无出口 ⇒ 仍然否决（硬危险不要求有出口）",
                SurvivalSystem.decide(bot, synthetic(HazardType.SUFFOCATING, SurvivalSystem.SOFT_HAZARD_GRACE_TICKS))
                        == SurvivalSystem.Verdict.INTERRUPT);
        advance(Phase.FOOT_CELL);
    }

    /**
     * **脚位格口径回归**（S-5 / 2026-09-15，由本步首轮实测逼出来的一条修正）。
     *
     * <p>"哪个格算 bot 站着的格"在仓里有**两个**口径：原版 `blockPosition()` = "脚**所在**格"，
     * `MovementHelper.footCell` = "支撑格的上一格"（D-105 定的唯一口径）。二者在**整格平地上一致**
     * （脚正好落在方块顶面），但在**半砖/箱子/台阶**这类非整格支撑上**必然不同**：
     * 脚在 slab 顶面 63.5 ⇒ `blockPosition()` = **63（slab 那一格）**、`footCell` = **64（bot 站的那格）**。
     *
     * <p>为什么这值得一条判据：`startSurvivalExit` 原来拿 `blockPosition()` 当"排除自己"，于是
     * 站在半砖上时**排除的是 slab 那一格**（永远不是落点）⇒ **bot 自己站的那格被当成出口**
     * ⇒ 逃生任务走到原地、0 步 COMPLETED，而 bot 一格没动、还在危险里。本节把这件事变成可复现的断言。
     */
    private void footCellPhase() {
        BlockPos support = SurvivalCourseAnchor.SEALED_FOOT.below();
        if (phaseTicks == 1) {
            runCommand("setblock " + support.getX() + " " + support.getY() + " " + support.getZ()
                    + " minecraft:stone_slab[type=bottom]");
            teleport(SurvivalCourseAnchor.SEALED_FOOT);
            return;
        }
        if (phaseTicks < SETTLE_TICKS) {
            return;
        }
        BlockPos here = foot();
        BlockPos raw = bot.blockPosition();
        BotLog.info("[Survival] 脚位口径自证（站在半砖上） foot={} blockPosition={} y={} hazard={}",
                here.toShortString(), raw.toShortString(), bot.getY(), SurvivalSystem.current(bot).type());
        check("站在半砖上时两个口径必须**不同**（foot=" + here.toShortString() + " blockPos="
                + raw.toShortString() + " y=" + bot.getY() + "）", !here.equals(raw));
        check("脚位格 = 支撑格的上一格 " + SurvivalCourseAnchor.SEALED_FOOT.toShortString()
                + "（实际 " + here.toShortString() + "）", SurvivalCourseAnchor.SEALED_FOOT.equals(here));
        check("排除**脚位格**后半径内没有落点（bot 自己那格不算出口；实际 "
                        + desc(SurvivalSystem.nearestSafeRefuge(bot, SurvivalSystem.REFUGE_RADIUS, here)) + "）",
                SurvivalSystem.nearestSafeRefuge(bot, SurvivalSystem.REFUGE_RADIUS, here) == null);
        check("对照·旧口径（排除 blockPosition）会把 bot 自己那格当出口 ⇒ 本节真的在修东西（实际 "
                        + desc(SurvivalSystem.nearestSafeRefuge(bot, SurvivalSystem.REFUGE_RADIUS, raw)) + "）",
                SurvivalSystem.nearestSafeRefuge(bot, SurvivalSystem.REFUGE_RADIUS, raw) != null);
        runCommand("setblock " + support.getX() + " " + support.getY() + " " + support.getZ()
                + " minecraft:stone");
        teleport(SurvivalCourseAnchor.SEALED_FOOT);
        advance(Phase.SEALED_REAL);
    }

    /**
     * **真实软危险端到端**：在封闭场景里真的点着 bot。
     *
     * <p>为什么要真点火：合成状态只能证明"判据函数返回了 HOLD_NO_EXIT"，证明不了"整条链（观察 ⇒ 决策 ⇒
     * 不否决 ⇒ 登记）在真实的 tick 循环里成立"。真点火的**风险**是"万一其实有出口" ⇒ 真被否决 ⇒
     * 打死整轮电池 ⇒ 所以**先自证前提、不成立就不点火**（那一步会记 FAIL，但电池活着）。
     */
    private void sealedRealPhase() {
        if (phaseTicks == 1) {
            return;     // 上一步已把 bot 重新传送好，这里先等它落地
        }
        if (phaseTicks < SETTLE_TICKS) {
            return;
        }
        if (phaseTicks == SETTLE_TICKS) {
            HazardState now = SurvivalSystem.current(bot);
            BlockPos found = SurvivalSystem.nearestSafeRefuge(bot, SurvivalSystem.REFUGE_RADIUS,
                    SurvivalSystem.footCell(bot));
            boolean premise = now.type() == HazardType.NONE     // 别在硬危险里点火（那会打断整轮电池）
                    && found == null
                    && SurvivalSystem.decide(bot, synthetic(HazardType.ON_FIRE,
                    SurvivalSystem.SOFT_HAZARD_GRACE_TICKS)) == SurvivalSystem.Verdict.HOLD_NO_EXIT;
            check("点火前的前提自证（当前无危险=" + (now.type() == HazardType.NONE) + " hazard=" + now.type()
                    + " 无出口=" + (found == null) + "）", premise);
            if (!premise) {
                advance(Phase.HEALTH);
                return;
            }
            normalizeVitals();      // 判据要确定性：清掉上一步可能残留的效果（会掩盖/治疗伤害）
            bot.setSecondsOnFire(6);
            fireTick = bot.getServer().getTickCount();
            fireStartTicks = bot.getRemainingFireTicks();
            healthBeforeFire = bot.getHealth();
            minHealthDuringFire = healthBeforeFire;
            BotLog.info("[Survival] 夹具在封闭场景**真的点着** bot（6 秒）；期望：不否决（任务继续）"
                    + "+ 一条 exit=none decision=continue 事件");
            return;
        }
        if (phaseTicks < SETTLE_TICKS + REAL_FIRE_TICKS) {
            // 持续采样**最低**血量：净血量可能被治疗/回血掩盖（CORE 实测），"期间掉过"才是事实。
            minHealthDuringFire = Math.min(minHealthDuringFire, bot.getHealth());
            return;
        }
        HazardState real = SurvivalSystem.current(bot);
        check("真实状态被判为 ON_FIRE（实际 " + real.type() + "）", real.type() == HazardType.ON_FIRE);
        // 以下三条是 **D-228 的判据**（2026-09-15 用户实测"看不到燃烧/受伤"逼出）：
        // 假人原本拿不到原版 tick ⇒ 火焰渲染输入不置位、着火 tick 不递减、火焰伤害不发生。
        // 修好之后它们必须一直绿；任何一条变红都说明 tick 链又断了（回归）。
        check("着火时必须立起实体共享标志 0（客户端渲染火焰的**唯一**输入；客户端 `isOnFire()` 读它，"
                        + "`remainingFireTicks` 根本不同步）",
                bot.sharedFlagOnFire());
        check("着火 tick 必须真的递减（点火时 " + fireStartTicks + " → 现在 " + bot.getRemainingFireTicks()
                        + "）", bot.getRemainingFireTicks() < fireStartTicks);
        check("着火必须真的造成伤害（点火前 " + healthBeforeFire + "，期间最低 " + minHealthDuringFire
                        + "，现在 " + bot.getHealth() + "）", minHealthDuringFire < healthBeforeFire);
        check("火焰伤害必须被读成可判读的掉血事件（hazard=ON_FIRE）", hasFireDamageEvent(fireTick));
        check("真实状态过真实决策 ⇒ HOLD_NO_EXIT（实际 " + SurvivalSystem.decide(bot, real) + "）",
                SurvivalSystem.decide(bot, real) == SurvivalSystem.Verdict.HOLD_NO_EXIT);
        check("已如实登记「软危险无出口 ⇒ 不否决」（exit=none decision=continue）",
                hasNoExitEvent(fireTick));
        // 能走到这里本身就是断言：真被否决的话，会话任务（= 电池）已被 complete，本方法不会再被调用。
        BotLog.info("[Survival] 真实着火 {} tick 后电池仍在跑 ⇒ 未被否决（唯一的端到端证据）",
                phaseTicks - SETTLE_TICKS);
        bot.clearFire();
        advance(Phase.HEALTH);
    }

    /**
     * **空气/溺水可达**（D-228 第四条判据）：补上原版 tick 之前 `airSupply` 永不下降 ⇒
     * `LOW_AIR`（溺水）只能靠夹具硬改字段才测得到、**产线不可达**。这里让假人真的进水里，
     * 断言空气**自己会掉**（= 溺水这条路在真实 tick 循环里成立）。
     *
     * <p>⚠️ 空气只降到 {@link #AIR_START} 以上：掉到 0 就变成真的 `LOW_AIR` 软危险，
     * 宽限 10 tick 后会**否决整轮电池**（夹具的会话任务就是电池本身）。
     * `WATER_CONTACT` 本身**不是**否决档（`SurvivalSystem.softHazard` 只含 LOW_AIR/ON_FIRE）⇒ 进水是安全的。
     */
    private void airPhase() {
        if (phaseTicks == 1) {
            // ⚠️ **必须先传送、后放水**：`/fill` 在**未加载的区块**里会静默什么都不做
            //（2026-09-15 踩过一次：水没放上，空气反而回涨 20→52）。传送会让玩家区块票据把区块加载起来。
            teleport(SurvivalCourseAnchor.SEALED_FOOT);
            return;
        }
        if (phaseTicks == SETTLE_TICKS) {
            fillBlocks(SurvivalCourseAnchor.SEALED_FOOT, SurvivalCourseAnchor.SEALED_FOOT.above(), "water", 2,
                    "封闭口袋里放水（溺水前提）");
            return;
        }
        if (phaseTicks == SETTLE_TICKS + 2) {
            normalizeVitals();      // 防"水下呼吸"类效果让空气不掉（判据要测的是原版空气消耗）
            bot.setAirSupply(AIR_START);
            airStart = bot.getAirSupply();
            BotLog.info("[Survival] 把 bot 放进水里（眼睛在水里={}），空气设为 {}；期望：原版 tick 真的在消耗空气",
                    bot.isEyeInFluid(net.minecraft.tags.FluidTags.WATER), airStart);
            return;
        }
        if (phaseTicks >= SETTLE_TICKS + 2 + AIR_POLL_TICKS) {
            int now = bot.getAirSupply();
            boolean eyeInWater = bot.isEyeInFluid(net.minecraft.tags.FluidTags.WATER);
            check("入水后空气必须真的被消耗（" + airStart + " → " + now + "，眼睛在水里=" + eyeInWater
                            + "）—— 这是 LOW_AIR/溺水在产线可达的前提", now < airStart && now > 0);
            teleport(SurvivalCourseAnchor.PLATFORM_FOOT);     // 先出来，再复原（顺序无关，但保持"有人看时场景是干净的"）
            fillBlocks(SurvivalCourseAnchor.SEALED_FOOT, SurvivalCourseAnchor.SEALED_FOOT.above(), "air", 2,
                    "复原封闭口袋（拆水）");
            advance(Phase.SNOW);
        }
    }

    /**
     * **冻结可达 + 决策安全**（D-229，2026-09-15）：`ticksFrozen` 是**假人真的会累积**的量
     * （累积在 `LivingEntity.aiStep()`，假人手动调得到），补了 `baseTick` 之后**冻结伤害也真的会发生**
     * （`LivingEntity.baseTick()`：全冻 140 tick 后每 40 tick 1 点）。
     *
     * <p>本相位在**封闭石壳**里做，出于安全：那里**没有任何出口**（几何自证过）
     * ⇒ 判决必然是 `HOLD_NO_EXIT` ⇒ **不会否决**（否决会把夹具的会话任务，也就是整轮电池，`complete` 掉）。
     * 因此这里断言的重点是"机理活着 + 危险被判出来 + 无出口时不乱否决"，而不是"被否决"。
     * （"有出口 ⇒ 否决"由决策表的合成行覆盖；真人侧的可见入口见 `alice:survival_exit_check` 的冻结模式。）
     */
    /**
     * **深水 + 无落点**（D-236，2026-09-15）：把 bot 放进一座**封闭水牢**（17³ 石壳 + 内部全为水），
     * 于是半径 8 格内**没有任何干燥可站的格子** ⇒ 这是"溺水且无处可逃"的确定性构造。
     *
     * <p>为什么必须做这一相位：`LOW_AIR`（溺水）此前和 `ON_FIRE`/`FREEZING` 一样走"无出口 ⇒ 不否决"，
     * 但**溺水不动手就一定死**（原版不按跳跃键只缓慢下沉）⇒ 继续跑 = 死在作业中途。
     * 现在 `decide` 对它返回新判决 `ABANDON_NO_EXIT`（放弃任务、干净收尾）⇒ 这里把这条钉住。
     *
     * <p>⚠️ 两条纪律：① **先建场景、再传送**（D-229 的教训）；② **拆场景前先把 bot 传送回平台** ——
     * 否则水牢一拆，bot 从 y≈100 直落 64 摔死。③ 空气只**点一下** 0（1 tick，宽限期 10 tick 内立刻恢复），
     * 免得 monitor 真的走到"放弃任务" ⇒ 打死整轮电池。
     */
    private void deepWaterPhase() {
        if (phaseTicks == 1) {
            normalizeVitals();
            BotLog.info("[Survival] 自建封闭水牢（{} 为心，17³ 石壳 hollow + 内部水）；期望："
                    + "半径 8 内无干燥落点 ⇒ 溺水判决 = ABANDON_NO_EXIT（放弃任务）", desc(DEEP_CENTER));
            fillBlocks(DEEP_CENTER.offset(-8, -8, -8), DEEP_CENTER.offset(8, 8, 8),
                    "minecraft:stone", 1500, "水牢石壳（17³ 实心；内部随后注水）");
            fillBlocks(DEEP_CENTER.offset(-7, -7, -7), DEEP_CENTER.offset(7, 7, 7),
                    "minecraft:water", 3300, "水牢内部注水");
            return;
        }
        if (phaseTicks == 2) {
            bot.setAirSupply(300);      // 先把空气归到基线，后面才好"点一下 0"
            teleport(DEEP_CENTER);
            BotLog.info("[Survival] 已把 bot 放进水牢中心 {}（inWater={}）", desc(DEEP_CENTER), bot.isInWater());
            return;
        }
        if (phaseTicks == 4) {
            HazardState inWater = SurvivalSystem.tick(bot);
            check("深水里 hazard 分类 = WATER_CONTACT（空气还够）",
                    inWater.type() == HazardType.WATER_CONTACT);
            check("水牢几何自证：半径 8 格内**无**干燥可站落点",
                    !SurvivalSystem.hasRefuge(bot));
            check("溺水 + 无落点 ⇒ 放弃任务（ABANDON_NO_EXIT，不是「不否决」）",
                    SurvivalSystem.decide(bot, synthetic(HazardType.LOW_AIR, 99))
                            == SurvivalSystem.Verdict.ABANDON_NO_EXIT);
            check("放弃判决带自己的判定码（survival_drowning_no_exit）",
                    "survival_drowning_no_exit".equals(
                            SurvivalSystem.abandonReason(synthetic(HazardType.LOW_AIR, 99))));
            check("对照：着火 + 无落点仍是「不否决」（可能自愈，D-226 语义不变）",
                    SurvivalSystem.decide(bot, synthetic(HazardType.ON_FIRE, 99))
                            == SurvivalSystem.Verdict.HOLD_NO_EXIT);
            check("对照：涉水仍不否决（走过水面是正常动作）",
                    SurvivalSystem.decide(bot, synthetic(HazardType.WATER_CONTACT, 99))
                            == SurvivalSystem.Verdict.IGNORE);
            BotLog.info("[Survival] 水牢判定：hazard={} exit_none=true drowning_verdict={}",
                    inWater.type(), SurvivalSystem.decide(bot, synthetic(HazardType.LOW_AIR, 99)));
            return;
        }
        if (phaseTicks == 5) {
            bot.setAirSupply(0);        // 只点一下：下一个 tick 断言完立刻恢复（宽限 10 tick 内）
            BotLog.info("[Survival] 把空气点成 0（1 tick）⇒ 期望 monitor 把**溺水**认出来（不是涉水）");
            return;
        }
        if (phaseTicks == 6) {
            HazardState drowning = SurvivalSystem.tick(bot);
            bot.setAirSupply(300);      // **立刻恢复**：别让 duration 走到宽限期（那会真的放弃任务、打死电池）
            check("空气 0 ⇒ hazard 分类 = LOW_AIR（溺水排在涉水之前，没被 WATER_CONTACT 盖掉）",
                    drowning.type() == HazardType.LOW_AIR);
            BotLog.info("[Survival] 溺水分类实测：hazard={} duration={} air={}（随后立即把空气恢复）",
                    drowning.type(), drowning.durationTicks(), drowning.airSupply());
            return;
        }
        if (phaseTicks >= 7) {
            // ⚠️ 顺序：**先**传送回平台，**再**拆水牢（否则从 y≈100 直落 64）。
            teleport(SurvivalCourseAnchor.PLATFORM_FOOT);
            fillBlocks(DEEP_CENTER.offset(-8, -8, -8), DEEP_CENTER.offset(8, 8, 8),
                    "minecraft:air", 4900, "水牢拆除（石壳+水全部清掉）");
            normalizeVitals();
            BotLog.info("[Survival] 水牢已拆、bot 回平台；本相位完");
            advance(Phase.DONE);
            return;
        }
    }

    /** 封闭水牢的几何中心（高空，不与任何场景/地形相交）。 */
    private static final BlockPos DEEP_CENTER = new BlockPos(206, 100, 306);

    private void snowPhase() {
        if (phaseTicks == 1) {
            teleport(SurvivalCourseAnchor.SEALED_FOOT);      // 先传送：区块加载是 /fill 生效的前提
            return;
        }
        if (phaseTicks == SETTLE_TICKS) {
            BlockPos found = SurvivalSystem.nearestSafeRefuge(bot, SurvivalSystem.REFUGE_RADIUS,
                    SurvivalSystem.footCell(bot));
            boolean premise = SurvivalSystem.current(bot).type() == HazardType.NONE && found == null;
            check("细雪相位前提自证（当前无危险=" + (SurvivalSystem.current(bot).type() == HazardType.NONE)
                    + " 无出口=" + (found == null) + "）—— 有出口的话这里会被否决、打死整轮电池", premise);
            if (!premise) {
                advance(Phase.DONE);
                return;
            }
            fillBlocks(SurvivalCourseAnchor.SEALED_FOOT, SurvivalCourseAnchor.SEALED_FOOT.above(),
                    "powder_snow", 2, "封闭口袋里放细雪（冻结前提）");
            return;
        }
        if (phaseTicks == SETTLE_TICKS + 1) {
            normalizeVitals();      // 清零基线：冻结伤害是 1 点/40 tick，别被残留效果/血量掩盖
            healthBeforeSnow = bot.getHealth();
            minHealthDuringSnow = healthBeforeSnow;
            snowStartFrozen = bot.getTicksFrozen();
            BotLog.info("[Survival] 把 bot 放进细雪（起始 ticksFrozen={}，全冻阈值 {}，血量 {}）；"
                            + "期望：累积 ≥ {} 后判成 FREEZING，且在无出口壳里不否决、全冻后真的掉血",
                    snowStartFrozen, bot.getTicksRequiredToFreeze(), healthBeforeSnow,
                    SurvivalSystem.FREEZE_WARN_TICKS);
            return;
        }
        // 等"真的全冻"（`ticksFrozen >= getTicksRequiredToFreeze()`，原版 = 140）——
        // **别用算数估**：累积速率会随实现变（第一次写成 60+10+45 只到 116，没到全冻，白等）。
        if (!bot.isFullyFrozen()) {
            minHealthDuringSnow = Math.min(minHealthDuringSnow, bot.getHealth());
            if (phaseTicks > SETTLE_TICKS + 1 + 400) {     // 兜底：真冻不上就如实报，别把相位挂死
                check("细雪里必须在合理时间内全冻（ticksFrozen=" + bot.getTicksFrozen()
                        + "，阈值 " + bot.getTicksRequiredToFreeze() + "）", false);
                advance(Phase.DONE);
                return;
            }
            return;
        }
        if (fullyFrozenAt == 0) {
            fullyFrozenAt = phaseTicks;
            BotLog.info("[Survival] 已全冻（ticksFrozen={}，第 {} tick 起）；再等 {} tick 让"
                            + "「每 40 tick 1 点」的冻结伤害至少来过一次",
                    bot.getTicksFrozen(), phaseTicks, FREEZE_DAMAGE_WAIT_TICKS);
        }
        if (phaseTicks < fullyFrozenAt + FREEZE_DAMAGE_WAIT_TICKS) {
            minHealthDuringSnow = Math.min(minHealthDuringSnow, bot.getHealth());
            return;
        }
        int frozen = bot.getTicksFrozen();
        HazardState state = SurvivalSystem.current(bot);
        check("细雪里 ticksFrozen 必须真的累积（" + snowStartFrozen + " → " + frozen
                        + "，期望 ≥ " + SurvivalSystem.FREEZE_WARN_TICKS + "）—— 这是冻结伤害的前提",
                frozen >= SurvivalSystem.FREEZE_WARN_TICKS);
        check("累积到阈值后必须被判成 FREEZING（实际 " + state.type() + "，已持续 "
                        + state.durationTicks() + " tick）", state.type() == HazardType.FREEZING);
        check("无出口 + 危险持续超过宽限 ⇒ 冻结走 HOLD_NO_EXIT、不乱否决（实际 "
                        + SurvivalSystem.decide(bot, state) + "）",
                SurvivalSystem.decide(bot, state) == SurvivalSystem.Verdict.HOLD_NO_EXIT);
        check("全冻（≥ " + bot.getTicksRequiredToFreeze() + " tick）后必须真的掉血"
                        + "（起始 " + healthBeforeSnow + "，期间最低 " + minHealthDuringSnow
                        + "，现在 " + bot.getHealth() + "）",
                minHealthDuringSnow < healthBeforeSnow);
        int before = frozen;
        fillBlocks(SurvivalCourseAnchor.SEALED_FOOT, SurvivalCourseAnchor.SEALED_FOOT.above(), "air", 2,
                "复原封闭口袋（拆细雪）");
        bot.setTicksFrozen(0);
        BotLog.info("[Survival] 拆掉细雪并清零（拆前 ticksFrozen={}，清零后={}）；本相位完", before,
                bot.getTicksFrozen());
        advance(Phase.DEEP_WATER);
    }

    /** 掉血可见（`previousHealth` 的第一个读者）+ 冷却期内不刷屏。 */
    private void healthPhase() {
        if (phaseTicks == 1) {
            bot.clearFire();
            teleport(SurvivalCourseAnchor.PLATFORM_FOOT);
            return;
        }
        if (phaseTicks == SETTLE_TICKS) {
            EventThresholds.resetHealthTracking(bot);
            hurtTick = bot.getServer().getTickCount();
            bot.hurt(bot.damageSources().generic(), HURT_AMOUNT);
            BotLog.info("[Survival] 夹具对 bot 造成 {} 点伤害（现有血量 {}），期望：掉血 ⇒ 一条 DANGER 事件",
                    HURT_AMOUNT, bot.getHealth());
            return;
        }
        if (phaseTicks == SETTLE_TICKS + HEALTH_POLL_TICKS) {
            List<BotEventLog.BotEvent> hits = healthEventsSince(hurtTick);
            HazardState state = SurvivalSystem.current(bot);
            check("掉血必须变成可判读的事实（DANGER 事件含 delta=…；实际命中 " + hits.size()
                            + " 条，state health=" + state.health() + " prev=" + state.previousHealth() + "）",
                    !hits.isEmpty());
            if (!hits.isEmpty()) {
                check("事件里带掉血量/血量键（data=" + hits.get(0).data() + "）",
                        hits.get(0).data() != null && hits.get(0).data().contains("delta=")
                                && hits.get(0).data().contains("health="));
            }
            return;
        }
        if (phaseTicks < SETTLE_TICKS + HEALTH_POLL_TICKS + HEALTH_QUIET_TICKS) {
            return;
        }
        List<BotEventLog.BotEvent> quiet = healthEventsSince(hurtTick);
        check("冷却窗口（" + HEALTH_QUIET_TICKS + " tick < " + EventThresholds.HEALTH_LOSS_COOLDOWN_TICKS
                + "）内不刷屏：掉血事件恰好 1 条（实际 " + quiet.size() + " 条）", quiet.size() == 1);
        advance(Phase.AIR);
    }

    // ==================== 工具 ====================

    private void finish() {
        bot.clearFire();
        bot.setTicksFrozen(0);      // 别把"半冻"状态留给下一步（判据要确定性）
        bot.removeAllEffects();
        bot.controller().stopMovement();
        bot.setDeltaMovement(Vec3.ZERO);
        teleport(SurvivalCourseAnchor.PLATFORM_FOOT);
        done = true;
        boolean pass = failures.isEmpty();
        BotLog.info("[Survival] SUMMARY checks={} failures={} {} → {}",
                checks, failures.size(), failures, pass ? "PASS" : "FAIL");
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 维生决策自检 "
                    + (pass ? "PASS" : "FAIL " + failures)));
        }
    }

    private void advance(Phase next) {
        phase = next;
        phaseTicks = 0;
    }

    private void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures.add(what);
        }
    }

    /** 合成危险状态：类型/时长是造的，血/空气/位置取自真实 bot（"危险类型之外的一切都是真的"）。 */
    private HazardState synthetic(HazardType type, int duration) {
        return new HazardState(type, duration, bot.getAirSupply(), bot.getHealth(), bot.getHealth(), foot());
    }

    /** 跑一条服务端命令（夹具自建/复原场景用；输出抑制，避免噪声）。 */
    /**
     * 跑一条服务端命令（夹具自建/复原场景用）。
     *
     * <p>@return 命令的返回值（`/fill` = 实际改动的方块数）。**必须看它**：2026-09-15 实测过一次
     * "`/fill` 跑在未加载的区块里 ⇒ 什么都没放"，而当轮判据照样绿（假绿）⇒ 现在把结果返回出来，
     * 由调用方断言（`fillBlocks(...)`）。
     */
    /** 自 `sinceTick` 起，是否有**带着 `hazard=ON_FIRE`** 的掉血事件（= 火焰伤害被读成了事实）。 */
    private boolean hasFireDamageEvent(long sinceTick) {
        return healthEventsSince(sinceTick).stream().anyMatch(event -> event.data().contains("hazard=ON_FIRE"));
    }

    private int runCommand(String command) {
        var server = bot.getServer();
        var source = server.createCommandSourceStack().withSuppressedOutput();
        return server.getCommands().performPrefixedCommand(source, command);
    }

    /**
     * **把生命/空气/效果归到已知基线** —— 判据必须确定性，不能被上一步的残留掩盖。
     *
     * <p>2026-09-15 CORE 实测踩到：单步跑时"着火掉血"稳定可见，跑全量 CORE 时 bot 的伤被**治了回去**
     * （18→20，疑似前序机器/药剂类步骤残留的效果 —— 补上 `baseTick` 后药水效果**开始真的 tick** 了）。
     * 判据因此改成"**相位期间的最低血量** + 掉血事件"，并在相位前清效果/满血/满空气。
     */
    private void normalizeVitals() {
        bot.removeAllEffects();
        bot.setHealth(bot.getMaxHealth());
        bot.setAirSupply(300);
    }

    /** `/fill` 并**断言真的改动了方块**（防"未加载区块里静默无操作"的假绿）。 */
    private void fillBlocks(BlockPos from, BlockPos to, String block, int expectedBlocks, String what) {
        int changed = runCommand("fill " + xyz(from) + " " + xyz(to) + " " + block);
        check("场景自建生效：" + what + "（fill 改动方块数 " + changed + "，期望 ≥ " + expectedBlocks + "）",
                changed >= expectedBlocks);
    }

    /**
     * **给命令用的坐标**（空格分隔）。⚠️ 别用 {@link #desc} —— 它是 `BlockPos.toShortString()`
     * 格式（`"206, 64, 306"`，**带逗号**），拼进命令就是非法坐标 ⇒ `/fill` 会**静默 0 改动**
     * （2026-09-15 实测踩到：水没放上，空气反而回涨）。`fillBlocks(...)` 的返回值断言正是为此加的。
     */
    private static String xyz(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static String desc(BlockPos pos) {
        return pos == null ? "无" : pos.toShortString();
    }

    private BlockPos foot() {
        return com.dddgn.alice.pathing.MovementHelper.footCell(bot.serverLevel(), bot);
    }

    private void teleport(BlockPos foot) {
        bot.teleportTo(bot.serverLevel(), foot.getX() + 0.5D, foot.getY(), foot.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
    }

    private boolean hasNoExitEvent(long sinceTick) {
        return BotEventLog.recent(bot, BotEventLog.CAPACITY).stream()
                .anyMatch(event -> event.tick() >= sinceTick && "DANGER".equals(event.type())
                        && event.data() != null && event.data().contains("exit=none")
                        && event.data().contains("decision=continue"));
    }

    private List<BotEventLog.BotEvent> healthEventsSince(long sinceTick) {
        return BotEventLog.recent(bot, BotEventLog.CAPACITY).stream()
                .filter(event -> event.tick() >= sinceTick && "DANGER".equals(event.type())
                        && event.summary() != null && event.summary().contains("掉血"))
                .toList();
    }
}
