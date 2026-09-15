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
 *   <li>**封闭场景（无出口）**：软危险 ⇒ {@code HOLD_NO_EXIT}（**不否决**）而硬危险**仍然**否决；</li>
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

    private enum Phase { SETUP, TABLE, WALK, SEALED_BUILD, SEALED_CHECK, FOOT_CELL, SEALED_REAL, HEALTH, DONE }

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
        check("软危险 + 无出口 ⇒ HOLD_NO_EXIT（不否决）· 溺水（实际 "
                        + SurvivalSystem.decide(bot, synthetic(HazardType.LOW_AIR,
                        SurvivalSystem.SOFT_HAZARD_GRACE_TICKS)) + "）",
                SurvivalSystem.decide(bot, synthetic(HazardType.LOW_AIR, SurvivalSystem.SOFT_HAZARD_GRACE_TICKS))
                        == SurvivalSystem.Verdict.HOLD_NO_EXIT);
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
            bot.setSecondsOnFire(6);
            fireTick = bot.getServer().getTickCount();
            BotLog.info("[Survival] 夹具在封闭场景**真的点着** bot（6 秒）；期望：不否决（任务继续）"
                    + "+ 一条 exit=none decision=continue 事件");
            return;
        }
        if (phaseTicks < SETTLE_TICKS + REAL_FIRE_TICKS) {
            return;
        }
        HazardState real = SurvivalSystem.current(bot);
        check("真实状态被判为 ON_FIRE（实际 " + real.type() + "）", real.type() == HazardType.ON_FIRE);
        // ⚠️ **已知限制（D-228 / 台账 §5.10，2026-09-15 用户实测逼出）**：这里本该断言
        // "喂给客户端的火焰渲染输入立起来了"，实测 sharedFlag0=false —— 根因不是渲染，是
        // **假人拿不到原版 tick**：真实玩家的 `Player.tick()→LivingEntity.tick()→baseTick()`
        // 由网络层 `ServerGamePacketListenerImpl.tick()→ServerPlayer.doTick()` 驱动，
        // 而假人的 `FakeConnection.tick()` **永不被调用**（BotPlayer javadoc 自己写着这条），
        // 所以 `remainingFireTicks` 不递减、共享标志不置位、身上**不着火、也不受火焰伤害**，
        // 空气也永不减少（⇒ LOW_AIR 在产线不可达）。
        // 修复（补 tick）落地后，这一行应当变回 `check(...)` —— 那时它就是修复的判据。
        BotLog.info("[Survival] 已知限制：着火时 sharedFlag0={}（客户端渲染火焰的输入）—— 假人无原版 tick，"
                        + "火焰渲染/火焰伤害/空气消耗均缺失（D-228 / 台账 §5.10）",
                bot.sharedFlagOnFire());
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
        advance(Phase.DONE);
    }

    // ==================== 工具 ====================

    private void finish() {
        bot.clearFire();
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
    private void runCommand(String command) {
        var server = bot.getServer();
        var source = server.createCommandSourceStack().withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, command);
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
