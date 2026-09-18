package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.decision.BotEventLog;
import com.dddgn.alice.decision.EventThresholds;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.survival.HazardState;
import com.dddgn.alice.survival.HazardType;
import com.dddgn.alice.survival.SurvivalSystem;
import com.dddgn.alice.pathing.core.WriteEnvelopes;
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
 *       `DANGER` 事件，且冷却期内**不刷屏**。⚠️ **2026-09-18 起（D-312）**：注入前先**等 i-frame 过期**
 *       并**自证净掉 ≥ {@code HURT_AMOUNT}** —— 否则原版「伤害叠加」会把 2.0 削成 1.0，而 1.0 的净掉
 *       可被一次回血在采样上抹平 ⇒ 判据会偶发假红（3 次实测，见 {@code healthPhase}）。</li>
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
    /**
     * 原版火焰伤害节拍：**每 20 tick 恰好 1 点**（本项目自己的客户端证据：`duration=1/41/81/121`）。
     *
     * <p>`LivingEntity.baseTick()` 的伤害挂在 {@code tickCount % 20 == 0} 上 ⇒ 一次 tick 链打嗝就会漏掉一整拍。
     */
    private static final int FIRE_DAMAGE_CADENCE_TICKS = 20;

    /**
     * 真实着火观察窗口。
     *
     * <p><b>2026-09-16 修（D-261）</b>：原值 `宽限期 + 6` = **16** tick ⇒ 连同 `SETTLE_TICKS` 也只有 **21** tick 窗口
     * ⇒ **只可能覆盖 1 次火焰伤害**。于是判据"期间血量必须掉过"变成了"那唯一一拍必须没被打嗝吞掉"：
     * 实测 1/5 次 CORE 假红（`点火前 20.0，期间最低 20.0`，其余火焰判据全绿）。
     * 现在要求窗口覆盖 **≥3 拍**（20×3 + 5 余量）⇒ 连续三拍都漏才会红，那才是真故障。
     */
    private static final int REAL_FIRE_TICKS = Math.max(
            SurvivalSystem.SOFT_HAZARD_GRACE_TICKS + 6, 3 * FIRE_DAMAGE_CADENCE_TICKS + 5);
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

    /**
     * 掉血相位**注入前**最多等多少 tick 让 i-frame 过期（D-312）。
     *
     * <p>为什么需要等：上一相位是着火相位（每 20 tick 1 点火焰伤害）⇒ 注入时 `invulnerableTime` 通常还在
     * 11 左右，而原版 `LivingEntity.hurt` 的冷却分支对"冷却未过"的情况**只打增量**
     * （`actuallyHurt(amount - lastHurt)`）⇒ 2.0 只落地 1.0，而 1.0 的净掉可被一次回血在采样上抹平
     * ⇒ 判据偶发假红。20 tick 足够让 `invulnerableTime` 从 20 掉到 ≤10；多留 10 做余量。
     */
    private static final int HEALTH_INJECT_MAX_WAIT_TICKS = 30;

    private enum Phase { SETUP, TABLE, WALK, SEALED_BUILD, SEALED_CHECK, FOOT_CELL, SEALED_REAL, HEALTH, AIR, SNOW, DEEP_WATER, OPEN_WATER, UNREACHABLE_REFUGE, SHAFT_ESCAPE, FLOODED_SHAFT, DONE }

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
    /** 掉血相位里**实际注入**发生在哪个 `phaseTicks`（0 = 还没注入；D-312 起注入要等 i-frame 过期）。 */
    private int injectPhaseTick;
    private int fireStartTicks;
    /** 窗口内观测到的"新命中"次数（D-261：独立于血量的伤害证据）。 */
    private int fireHits;
    /** 上一 tick 的 `hurtTime`（用于上升沿计数）。 */
    private int lastHurtTime;
    /** 命中时**仍处于着火状态**的次数（归因：这些命中只能是火焰伤害）。 */
    private int hitsWhileOnFire;
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
            case OPEN_WATER -> openWaterPhase();
            case UNREACHABLE_REFUGE -> unreachableRefugePhase();
            case SHAFT_ESCAPE -> shaftEscapePhase();
            case FLOODED_SHAFT -> floodedShaftPhase();
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
            // ⚠️ **看返回值**（D-252，与 `PathingRegressionTask.prepare` 同一教训）：命令源是
            // `withSuppressedOutput()` ⇒ 数据包缺失/陈旧时 `/function` **一字不打**就失败，
            // 后面的"封闭/溺水"判据会拿着**没有几何**的世界跑（客户端实测过：存档里的场景数据包是旧拷贝）。
            int sealedCmds = server.getCommands().performPrefixedCommand(source,
                    "function alice_test:survival_sealed_course");
            check("场景前提：封闭场景函数真的跑了（alice_test:survival_sealed_course ⇒ " + sealedCmds
                            + " 条命令；0 = 数据包缺失/陈旧）", sealedCmds > 0);
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
            // **实测（D-261）**：把食物/饱和度清零**也**关不掉回血 ⇒ 回血挂在 `aiStep()` 的无条件
            // `health < max && tickCount % 20 == 0 ⇒ heal(1)` 上 ⇒ 火焰 1 点/20t 被它**永久抵消**。
            bot.setSecondsOnFire(6);
            fireTick = bot.getServer().getTickCount();
            fireStartTicks = bot.getRemainingFireTicks();
            healthBeforeFire = bot.getHealth();
            minHealthDuringFire = healthBeforeFire;
            fireHits = 0;
            lastHurtTime = 0;
            hitsWhileOnFire = 0;
            BotLog.info("[Survival] 夹具在封闭场景**真的点着** bot（6 秒）；期望：不否决（任务继续）"
                    + "+ 一条 exit=none decision=continue 事件");
            return;
        }
        if (phaseTicks < SETTLE_TICKS + REAL_FIRE_TICKS) {
            // 血量只作**诊断**（D-261 起不再是判据）：回血 1 点/20t 恰好抵消火焰 1 点/20t
            // ⇒ 采样到 19 还是 20 取决于**同一 tick 内的先后顺序** ⇒ 拿它当判据必然假红（实测 1/5 次 CORE）。
            minHealthDuringFire = Math.min(minHealthDuringFire, bot.getHealth());
            // 伤害计数（**观测口径独立于血量**）：`hurtTime` 被命中时置 10 并逐 tick 递减
            // ⇒ **上升沿 = 一次新命中**（采样相位落在 9/10 都无所谓，见 D-261 实测）。
            if (bot.hurtTime > lastHurtTime) {
                fireHits++;
                if (bot.getRemainingFireTicks() > 0) {
                    hitsWhileOnFire++;
                }
            }
            lastHurtTime = bot.hurtTime;
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
        // **D-261 前提判据**：窗口必须覆盖 ≥2 个伤害节拍 —— 否则"掉血"这条判据会退化成
        // "唯一那一拍必须没被打嗝吞掉"（实测 1/5 次 CORE 假红）。这条断言把该前提**写进判据**。
        int windowTicks = phaseTicks - SETTLE_TICKS;
        check("判据前提：火焰采样窗口必须覆盖 ≥2 个伤害节拍（窗口 " + windowTicks + " tick / 节拍 "
                        + FIRE_DAMAGE_CADENCE_TICKS + "）",
                windowTicks >= 2 * FIRE_DAMAGE_CADENCE_TICKS);
        // **独立于血量的伤害证据**（回血/采样相位都盖不住它）：窗口内必须观测到 ≥2 次"新命中"。
        check("窗口内必须观测到 ≥2 次火焰命中（新命中次数 " + fireHits + "；hurtTime 上升沿口径，与血量无关）",
                fireHits >= 2);
        // **D-261 判据重建**：原两条（"期间最低血量必须掉过"、"必须被读成 hazard=ON_FIRE 掉血事件"）
        // 都建立在**采样/净血量**上 ⇒ 在"回血恰好抵消火焰伤害"的现实下**不可判定**（实测 1/5 次假红）。
        // 换成两条**与血量无关**的确定性判据：命中次数（上升沿）+ 命中发生在着火期间（归因）。
        check("着火必须真的造成伤害（点火前 " + healthBeforeFire + "，期间最低 " + minHealthDuringFire
                        + "，现在 " + bot.getHealth() + "；**注**：回血抵消 ⇒ 该数值只作诊断）",
                fireHits >= 1);
        check("命中必须发生在着火期间（归因：命中时 fireTicks>0；命中次数 " + fireHits + "）",
                fireHits == 0 || hitsWhileOnFire == fireHits);
        check("真实状态过真实决策 ⇒ HOLD_NO_EXIT（实际 " + SurvivalSystem.decide(bot, real) + "）",
                SurvivalSystem.decide(bot, real) == SurvivalSystem.Verdict.HOLD_NO_EXIT);
        check("已如实登记「软危险无出口 ⇒ 不否决」（exit=none decision=continue）",
                hasNoExitEvent(fireTick));
        // 能走到这里本身就是断言：真被否决的话，会话任务（= 电池）已被 complete，本方法不会再被调用。
        BotLog.info("[Survival] 真实着火 {} tick 后电池仍在跑 ⇒ 未被否决（唯一的端到端证据）；"
                        + "窗口内新命中={} 最低血量={}（点火前 {}）",
                phaseTicks - SETTLE_TICKS, fireHits, minHealthDuringFire, healthBeforeFire);
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
            check("封闭水牢里**浮不上去**（canFloatUp=false ⇒ 走不了自救那条路）",
                    !SurvivalSystem.canFloatUp(bot));
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
            advance(Phase.OPEN_WATER);
            return;
        }
    }

    /**
     * **开阔水面 + 无落点 ⇒ 上浮自救**（D-237，用户批准的"乙"）：21×21 水池（水面之上是天空），
     * 于是"浮得上去"成立、而半径 8 内**没有干燥落点**（池壁在 ±9 之外）⇒ 判决应是 `FLOAT_UP`，
     * 而不是"放弃任务"。
     *
     * <p>**端到端**：夹具**真的驱动** {@link com.dddgn.alice.task.SurvivalFloatTask}（按住跳跃上浮），
     * 断言它到 `DONE`、眼睛离开水面、空气回到安全线。空气刻意设成 **5（>0）** 而不是 0：
     * 这样 monitor 看到的仍是 `WATER_CONTACT`（IGNORE）⇒ 不会从旁边插一手打死电池
     * （真溺水 ≤0 的判决只做**纯判据**断言，见 D-236/D-237 的结构性限制）。
     */
    private void openWaterPhase() {
        if (phaseTicks == 1) {
            normalizeVitals();
            BotLog.info("[Survival] 自建开阔水池（{} 为心，21×21、水 4 深、上方露天）；期望："
                    + "浮得上去 + 半径 8 无干燥落点 ⇒ 判决 FLOAT_UP，并真的浮上去", desc(OPEN_CENTER));
            fillBlocks(OPEN_CENTER.offset(-10, 0, -10), OPEN_CENTER.offset(10, 0, 10),
                    "minecraft:stone", 400, "水池底（21×21）");
            fillBlocks(OPEN_CENTER.offset(-11, 1, -11), OPEN_CENTER.offset(11, 5, 11),
                    "minecraft:stone", 1200, "水池外壁（hollow，把水兜住）");
            fillBlocks(OPEN_CENTER.offset(-10, 1, -10), OPEN_CENTER.offset(10, 4, 10),
                    "minecraft:water", 1700, "水池注水（21×21×4）");
            // **必须把天花板拆掉**：hollow 的上盖会让"池顶那一格"成为半径 4 内可站的落点
            // ⇒ 判决变成 INTERRUPT（而不是 FLOAT_UP），而那条逃生又**走不到**（实测：2026-09-15
            // `refuge=240,105,306` ⇒ `WalkToTask … PLAN_UNREACHABLE` ⇒ 真否决打死电池）。
            // 顺带这也是"`isRefuge` 不查可达性"的第一个实证案例（台账 §5.11）。
            fillBlocks(OPEN_CENTER.offset(-10, 5, -10), OPEN_CENTER.offset(10, 5, 10),
                    "minecraft:air", 400, "打开池顶（去掉天花板 ⇒ 露天水面）");
            return;
        }
        if (phaseTicks == 2) {
            bot.setAirSupply(300);
            teleport(OPEN_CENTER.above());
            BotLog.info("[Survival] 已把 bot 放进开阔水池 {}（inWater={} 眼睛在水里={}）",
                    desc(OPEN_CENTER.above()), bot.isInWater(), bot.isEyeInFluid(net.minecraft.tags.FluidTags.WATER));
            return;
        }
        if (phaseTicks == 4) {
            check("开阔水面：浮得上去（canFloatUp=true）", SurvivalSystem.canFloatUp(bot));
            check("开阔水面：半径 8 内**无**干燥落点（前提自证 ⇒ 走的是自救分支而非 WalkTo）",
                    !SurvivalSystem.hasRefuge(bot));
            check("溺水 + 无落点 + 浮得上去 ⇒ FLOAT_UP（先自救，不是放弃任务）",
                    SurvivalSystem.decide(bot, synthetic(HazardType.LOW_AIR, 99))
                            == SurvivalSystem.Verdict.FLOAT_UP);
            // 端到端：把空气压到 5（>0 ⇒ monitor 仍判 WATER_CONTACT ⇒ 不会插手），真驱动自救任务。
            bot.setAirSupply(5);
            eyeUnderWaterBefore = bot.isEyeInFluid(net.minecraft.tags.FluidTags.WATER);
            floatTask = new com.dddgn.alice.task.SurvivalFloatTask(bot);
            BotLog.info("[Survival] 端到端上浮自救：air=5 眼睛在水里={} ⇒ 驱动 SurvivalFloatTask",
                    eyeUnderWaterBefore);
            return;
        }
        if (phaseTicks >= 5 && floatTask != null) {
            // ⚠️ **头还在水里时把空气钉在 ≥1**：否则它每 tick 掉 1，几 tick 就到 0 ⇒ monitor 判 `LOW_AIR`
            // ⇒ 真的行使否决权（实测 2026-09-15：整轮电池被 `SURVIVAL_INTERRUPTED` 打死、`verdict=no_verdict`）。
            // 头一出水就交给原版自然回升（4/tick）⇒ 自救任务的 DONE 判据（air ≥ 100）才有意义。
            if (bot.isEyeInFluid(net.minecraft.tags.FluidTags.WATER)) {
                // 钳到 **≥2**：monitor 在 tick 开头读数，而原版在同一 tick 里再扣 1 ⇒ 钳 1 会被扣成 0
                // ⇒ 下一 tick monitor 判 LOW_AIR ⇒ 真的行使否决权（实测：电池本体被打成 SURVIVAL_INTERRUPTED）。
                bot.setAirSupply(Math.max(2, bot.getAirSupply()));
            }
            com.dddgn.alice.task.Task.Status status = floatTask.tick();
            if (status == com.dddgn.alice.task.Task.Status.RUNNING && phaseTicks - 4 < FLOAT_E2E_BUDGET) {
                return;
            }
            boolean finished = status != com.dddgn.alice.task.Task.Status.RUNNING;
            check("端到端上浮自救在预算内跑完（" + (phaseTicks - 4) + " tick / 预算 " + FLOAT_E2E_BUDGET + "）",
                    finished);
            check("端到端上浮自救：任务到达 DONE（空气复原；实际 " + status + " " + floatTask.terminalReason() + "）",
                    status == com.dddgn.alice.task.Task.Status.DONE);
            check("端到端上浮自救：眼睛真的离开了水面（自救前在水里=" + eyeUnderWaterBefore + "，现在="
                            + bot.isEyeInFluid(net.minecraft.tags.FluidTags.WATER) + "）",
                    !bot.isEyeInFluid(net.minecraft.tags.FluidTags.WATER));
            check("端到端上浮自救：空气回到安全线（实际 " + bot.getAirSupply() + "）",
                    bot.getAirSupply() >= com.dddgn.alice.task.SurvivalFloatTask.AIR_SAFE);
            // ⚠️ **必须置空**：否则下一 tick 会再次进这个分支、重复断言、永不进收尾
            // （2026-09-15 实测：`TIMEOUT ticks=901`，同一族坑的第三次 —— 断言后相位必须真的前进）。
            floatTask = null;
            return;
        }
        if (phaseTicks >= 5) {
            // 收尾：**先**传送回平台（否则池子一拆就从高空摔下去），再拆池子 + 复位。
            teleport(SurvivalCourseAnchor.PLATFORM_FOOT);
            SurvivalSystem.clearFloatFailures(bot);
            fillBlocks(OPEN_CENTER.offset(-11, 0, -11), OPEN_CENTER.offset(11, 5, 11),
                    "minecraft:air", 2500, "水池拆除（底+壁+水全部清掉）");
            normalizeVitals();
            BotLog.info("[Survival] 水池已拆、bot 回平台；本相位完");
            advance(Phase.UNREACHABLE_REFUGE);
            return;
        }
    }

    /**
     * **几何落点存在、但规划不可达**（D-238，2026-09-15）：一个封闭石盒里，隔着石墙有一个 1×2 空气袋
     * ——它满足 `isRefuge`（干、可站、头位可穿、距 4 格），**但规划器去不了**（石墙挡着、又没有写授权）。
     *
     * <p>为什么要立这一相：2026-09-15 实测到真实案例 —— 几何落点成立，`WalkToTask` 却
     * `PLAN_UNREACHABLE … walk_no_path`（1 tick 失败），而**当前任务已经被杀了**（为救一个去不了的落点）。
     * 修法 = 出逃生之前先跑一次**真规划预检**：`UNREACHABLE` ⇒ 按"没有出口"处理。
     */
    private void unreachableRefugePhase() {
        if (phaseTicks == 1) {
            normalizeVitals();
            BotLog.info("[Survival] 自建「去不了的落点」场景（{} 为心，5³ 实心石盒 + 两个 1×2 空气袋，"
                    + "相隔石墙）；期望：几何有落点、规划不可达 ⇒ 软危险**不再被否决**",
                    desc(UNREACHABLE_CENTER));
            fillBlocks(UNREACHABLE_CENTER.offset(-2, -1, -2), UNREACHABLE_CENTER.offset(2, 3, 2),
                    "minecraft:stone", 100, "石盒（5×5×5 实心）");
            fillBlocks(UNREACHABLE_CENTER, UNREACHABLE_CENTER.above(), "minecraft:air", 2, "bot 的封闭小间");
            fillBlocks(UNREACHABLE_CENTER.offset(2, 0, 2), UNREACHABLE_CENTER.offset(2, 1, 2),
                    "minecraft:air", 2, "隔着石墙的空气袋（几何上是落点）");
            return;
        }
        if (phaseTicks == 2) {
            teleport(UNREACHABLE_CENTER);
            BotLog.info("[Survival] 已把 bot 关进石盒 {}（foot={}）⇒ 落点只有隔壁那个空气袋",
                    desc(UNREACHABLE_CENTER), desc(foot()));
            return;
        }
        if (phaseTicks == 4) {
            BlockPos geometric = SurvivalSystem.nearestSafeRefuge(bot, SurvivalSystem.REFUGE_RADIUS,
                    SurvivalSystem.footCell(bot));
            check("前提自证：几何上**确实**有一个落点（" + desc(geometric) + "，否则本相位在验空气）",
                    geometric != null);
            check("新判据：那个落点**规划不可达** ⇒ 视为没有出口（plannableRefuge=null）",
                    SurvivalSystem.plannableRefuge(bot, HazardType.ON_FIRE) == null);
            check("行为：软危险 + 只有去不了的落点 ⇒ **不否决**（HOLD_NO_EXIT，别再为它杀任务）",
                    SurvivalSystem.decide(bot, synthetic(HazardType.ON_FIRE, 99))
                            == SurvivalSystem.Verdict.HOLD_NO_EXIT);
            check("对照：硬危险仍然无条件否决（不受落点可达性影响）",
                    SurvivalSystem.decide(bot, synthetic(HazardType.SUFFOCATING, 99))
                            == SurvivalSystem.Verdict.INTERRUPT);
            // 正对照 + 收尾：回平台后同一个危险类型必须能规划到落点（缓存按"类型+脚位"键，换位置会重算）
            teleport(SurvivalCourseAnchor.PLATFORM_FOOT);
            check("正对照：回到平台后**能**规划到落点（plannableRefuge != null）",
                    SurvivalSystem.plannableRefuge(bot, HazardType.ON_FIRE) != null);
            return;
        }
        if (phaseTicks >= 5) {
            fillBlocks(UNREACHABLE_CENTER.offset(-2, -1, -2), UNREACHABLE_CENTER.offset(2, 3, 2),
                    "minecraft:air", 120, "石盒拆除");
            normalizeVitals();
            BotLog.info("[Survival] 石盒已拆、bot 在平台；本相位完");
            advance(Phase.SHAFT_ESCAPE);
            return;
        }
    }

    /**
     * **逃生准备金**（D-241，用户 2026-09-16 批准的提案 B / `survey09` §4.4 那个杀手）：
     * bot 站在一个 **2 格深的 1×1 竖坑**底（房间地板在 y+2），四周是石头。
     *
     * <p>几何落点存在（2 格外的房间地板），但：纯通行**上不去**（`ASCEND` 只能上 1 格、且 y+1 那格是石头）
     * ⇒ 今天只能 `exit=none` 停在原地等干预。带**逃生准备金**（放置 + 破坏 + `PILLAR`，上限 8/8）时可以
     * 垫柱子/破开侧壁出来 —— 这就是"维生自救的受限写授权"要换的东西。
     *
     * <p>三道断言：① 纯通行去不了（前提自证）；② **信封闸门**（没写过的任务 `had==false`、写过之后 `true`、
     * 清空之后又 `false`）；③ 带准备金到得了 + **真的走出来**（驱动真 `SurvivalExitTask` 到 DONE）。
     */
    private void shaftEscapePhase() {
        if (phaseTicks == 1) {
            normalizeVitals();
            BotLog.info("[Survival] 自建 2 格深竖坑（{} 为心，房间地板在 y+2，四周石头）；期望：纯通行 "
                    + "exit=none、带逃生准备金能垫出来", desc(SHAFT_PIT_BOTTOM));
            fillBlocks(SHAFT_CENTER.offset(-5, -5, -4), SHAFT_CENTER.offset(5, 4, 4),
                    "minecraft:stone", 990, "竖坑所在的实心石块");
            fillBlocks(SHAFT_CENTER.offset(-4, 1, -3), SHAFT_CENTER.offset(4, 2, 3),
                    "minecraft:air", 126, "房间（地板 y+2，即坑口所在层）");
            fillBlocks(SHAFT_PIT_BOTTOM, SHAFT_PIT_BOTTOM.above(), "minecraft:air", 2, "1×1×2 竖坑");
            return;
        }
        if (phaseTicks == 2) {
            com.dddgn.alice.item.FixtureToolKit.ensureHotbarStack(bot,
                    () -> new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE),
                    stack -> stack.is(net.minecraft.world.item.Items.COBBLESTONE), 64, "cobblestone");
            com.dddgn.alice.item.FixtureToolKit.ensureHotbarTool(bot,
                    () -> new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE_PICKAXE),
                    stack -> stack.is(net.minecraft.tags.ItemTags.PICKAXES), "stone_pickaxe");
            teleport(SHAFT_PIT_BOTTOM);
            BotLog.info("[Survival] 已把 bot 放进坑底 {}（脚位 {}，头顶 {}）", desc(SHAFT_PIT_BOTTOM),
                    desc(foot()), desc(foot().above()));
            return;
        }
        if (phaseTicks == 4) {
            BlockPos geometric = SurvivalSystem.nearestSafeRefuge(bot, SurvivalSystem.REFUGE_RADIUS,
                    SurvivalSystem.footCell(bot));
            check("前提自证：坑口外 8 格内**确实**有几何落点（" + desc(geometric) + "）", geometric != null);
            check("纯通行去不了那个落点（ASCEND 上不了 2 格、y+1 是石头）⇒ 今天只能 exit=none",
                    SurvivalSystem.plannableRefuge(bot, HazardType.ON_FIRE, false) == null);
            String id = bot.getUUID().toString();
            // ⚠️ **不假设"初始是干净的"**：在 CORE 里整台电池是**同一个任务**，前面的 mine_*/scaffold 等步骤
            // 早写过世界 ⇒ 这个标志本就是 true（`single:survival_exit` 单跑时才是 false）。
            // 夹具要断的是**转移**（写请求 ⇒ true；任务边界 clear ⇒ false），不是初始状态。
            BotLog.info("[Survival] 信封初始值 had={}（电池上下文里可能已被前面的步骤置真，故不当判据）",
                    WriteEnvelopes.had(id));
            com.dddgn.alice.pathing.core.search.PathRequest mining =
                    com.dddgn.alice.pathing.core.search.PathRequest.miningApproach(id,
                            SurvivalSystem.footCell(bot), SurvivalSystem.footCell(bot).offset(1, 0, 0), "mine-plan");
            check("信封闸门②：出现写请求（miningApproach）⇒ had=true（推导事实，非手抄名单）",
                    WriteEnvelopes.had(id));
            WriteEnvelopes.clear(id);
            check("信封闸门③：任务边界清空（beginTask 做的就是这件事）⇒ had=false 回到起点",
                    !WriteEnvelopes.had(id));
            escapePick = SurvivalSystem.plannableRefuge(bot, HazardType.ON_FIRE, true);
            check("带逃生准备金就到得了（放置+破坏+PILLAR，上限 8/8）：" + desc(escapePick),
                    escapePick != null);
            check("并且预检据实标记「只有动用准备金才到得了」（逃生任务据此选受限请求）",
                    SurvivalSystem.escapeNeedsWrites(bot));
            return;
        }
        if (phaseTicks >= 5 && escapePick != null && !shaftDone) {
            if (shaftEscapeTask == null) {
                shaftEscapeTask = new com.dddgn.alice.task.SurvivalExitTask(bot, escapePick, true);
            }
            int shaftPlacesBefore = com.dddgn.alice.action.WriteBudget.places(bot);
            var status = shaftEscapeTask.tick();
            shaftPlaces += Math.max(0, com.dddgn.alice.action.WriteBudget.places(bot) - shaftPlacesBefore);
            if (status == com.dddgn.alice.task.Task.Status.RUNNING && phaseTicks - 4 < SHAFT_BUDGET) {
                return;
            }
            shaftDone = true;
            check("端到端：逃生任务到达终态（" + status + " " + shaftEscapeTask.terminalReason() + "）",
                    status == com.dddgn.alice.task.Task.Status.DONE);
            check("端到端：bot **真的从坑里出来了**（脚位 y=" + foot().getY() + " ≥ 101）",
                    foot().getY() >= SHAFT_PIT_BOTTOM.getY() + 2);
            // D-244 反向对照（干地侧）：干燥竖坑**必须**仍然靠放置上来 —— 证明"水柱不放方块"没有
            // 串到陆地上（那会让 dry PILLAR 直接不写、只靠跳，本判据就会红）。
            check("对照（干地）：干燥竖坑仍然靠**放置**上来（逃生期间放置=" + shaftPlaces + " ≥ 1）",
                    shaftPlaces >= 1);
            // ==================== D-245：逃生**不得**自动回收自己垫的方块（用户 2026-09-16 裁定） ====================
            // 理由（用户的）：自动回收会**把 bot 重新关回坑里** ⇒ 危险再触发 ⇒ 再逃生 = **逃生循环**。
            // 回收只能由**玩家入口**触发（`/alice restore` / `alice:restore_check` ⇒ `RestoreScopeTask`，
            // "建拆同权"的那条生产路径，权限没变、只是**时机**交给玩家）。
            // 下面两条是**负向门禁**：谁把自动回收接进逃生路径，它们立刻变红。
            var server = bot.serverLevel().getServer();
            var pillar = SHAFT_PIT_BOTTOM;
            var ledgerEntry = com.dddgn.alice.ledger.WorldModLedger.at(server, pillar);
            String pillarBlock = bot.serverLevel().getBlockState(pillar).getBlock().getName().getString();
            check("逃生不自动回收（D-245）：垫脚方块**仍在世界里**（" + desc(pillar) + " = " + pillarBlock
                            + "）⇒ 逃生任务没有拆自己垫的路（防逃生循环）",
                    !bot.serverLevel().getBlockState(pillar).isAir());
            check("逃生不自动回收（D-245）：账本里**仍记着这笔待拆**（policy="
                            + (ledgerEntry == null ? "无条目" : ledgerEntry.policy())
                            + "）⇒ 回收时机交玩家（`/alice restore`），不是静默丢弃",
                    ledgerEntry != null
                            && ledgerEntry.policy() == com.dddgn.alice.ledger.WorldModLedger.Policy.TEMP);
            return;
        }
        if (phaseTicks >= 5) {
            bot.controller().stopMovement();
            teleport(SurvivalCourseAnchor.PLATFORM_FOOT);
            WriteEnvelopes.clear(bot.getUUID().toString());
            fillBlocks(SHAFT_CENTER.offset(-5, -5, -4), SHAFT_CENTER.offset(5, 4, 4),
                    "minecraft:air", 863, "竖坑拆除");
            normalizeVitals();
            BotLog.info("[Survival] 竖坑已拆、bot 回平台；本相位完");
            advance(Phase.FLOODED_SHAFT);
            return;
        }
    }

    /**
     * **灌满水的竖坑**（D-242，用户最初那个问题"被水围住怎么办"的最小可复现形态）：
     * 1×1×2 的坑里**灌满水**，bot 站在坑底水里，房间地板在 y+2。
     *
     * <p>纯通行依然出不去（同 `SHAFT_ESCAPE`：`ASCEND` 上不了 2 格、y+1 那格不是合法落点），
     * 但**逃生准备金可以往水里放方块**（`appendPillar`/`appendPlaceStepAndTraverse` 都**没有**流体排除）
     * ⇒ 垫着方块从水里出来 —— 这就是"不新增水位 Movement 也能从水里自救"的那条路。
     *
     * <p>另加**上限守卫**（Q3 定案）：把准备金压到 1 破坏/1 放置后，同一个落点必须变成不可达
     * ⇒ 证明"预算真的会咬"，而不是摆设。
     */
    private void floodedShaftPhase() {
        if (phaseTicks == 1) {
            normalizeVitals();
            BotLog.info("[Survival] 自建**灌水**竖坑（{} 为心，坑里 2 格全是水，房间地板 y+2）；"
                    + "期望：纯通行 exit=none、带准备金能从水里垫出来", desc(FLOOD_PIT_BOTTOM));
            fillBlocks(FLOOD_CENTER.offset(-5, -5, -4), FLOOD_CENTER.offset(5, 4, 4),
                    "minecraft:stone", 990, "灌水竖坑所在的实心石块");
            fillBlocks(FLOOD_CENTER.offset(-4, 1, -3), FLOOD_CENTER.offset(4, 2, 3),
                    "minecraft:air", 126, "房间（地板 y+2）");
            fillBlocks(FLOOD_PIT_BOTTOM, FLOOD_PIT_BOTTOM.above(), "minecraft:water", 2, "坑里注水（2 格）");
            return;
        }
        if (phaseTicks == 2) {
            com.dddgn.alice.item.FixtureToolKit.ensureHotbarStack(bot,
                    () -> new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE),
                    stack -> stack.is(net.minecraft.world.item.Items.COBBLESTONE), 64, "cobblestone");
            teleport(FLOOD_PIT_BOTTOM);
            BotLog.info("[Survival] 已把 bot 放进水里 {}（inWater={} 空气 {}）", desc(FLOOD_PIT_BOTTOM),
                    bot.isInWater(), bot.getAirSupply());
            return;
        }
        if (phaseTicks == 4) {
            check("前提自证：bot 真的在水里（inWater=true）", bot.isInWater());
            BlockPos geometric = SurvivalSystem.nearestSafeRefuge(bot, SurvivalSystem.REFUGE_RADIUS,
                    SurvivalSystem.footCell(bot));
            check("前提自证：水里 8 格内**确实**有干落点（" + desc(geometric) + "）", geometric != null);
            check("纯通行去不了（水里那格不是合法落点、y+1 也不是）",
                    SurvivalSystem.plannableRefuge(bot, HazardType.ON_FIRE, false) == null);
            // ⚠️ **不构造"压低额度 ⇒ 不可达"的守卫**：`escapeWithReserve` 自己会装上 8/8（这正是生产该有的行为）
            // ⇒ 从外部压低额度**逻辑上不可能生效**（实测：0/0 依然 REACHED）。改为**直接量三条事实**：
            // ① 计划里确实有写动作（否则就不是"靠准备金"出去的）；② 写动作数 ≤ 逃生额度；③ 移动集不含 DOWNWARD/FALL。
            if (floodedPick == null) {
                floodedPick = SurvivalSystem.plannableRefuge(bot, HazardType.ON_FIRE, true);
            }
            var escapePlan = new com.dddgn.alice.pathing.core.search.CorePathPlanner().plan(bot,
                    bot.serverLevel(), com.dddgn.alice.pathing.core.search.PathRequest.survivalEscape(
                            bot.getUUID().toString(), SurvivalSystem.footCell(bot), floodedPick, "survival-escape"));
            long writeMoves = escapePlan.movements().stream()
                    .filter(move -> move.movementType().changesWorld()).count();
            check("额度守卫①：逃生计划里**确实含写类 Movement**（实际 " + writeMoves
                            + " 个，`PILLAR` 属写类）⇒ 这条出口在纯通行档根本不会生成，不是白走一趟",
                    writeMoves >= 1);
            check("额度守卫②：写动作数 ≤ 逃生额度 8（实际 " + writeMoves + "）", writeMoves <= 8);
            var allowed = com.dddgn.alice.pathing.core.search.PathRequest
                    .survivalEscape("probe", net.minecraft.core.BlockPos.ZERO,
                            net.minecraft.core.BlockPos.ZERO, "survival-escape").allowedMovementTypes();
            check("额度守卫③：逃生移动集含 PILLAR/放置/破坏，且**不含** DOWNWARD/FALL",
                    allowed.contains(com.dddgn.alice.pathing.core.MovementType.PILLAR)
                            && allowed.contains(com.dddgn.alice.pathing.core.MovementType.PLACE_STEP_AND_TRAVERSE)
                            && allowed.contains(com.dddgn.alice.pathing.core.MovementType.BREAK_AND_TRAVERSE)
                            && !allowed.contains(com.dddgn.alice.pathing.core.MovementType.DOWNWARD)
                            && !allowed.contains(com.dddgn.alice.pathing.core.MovementType.FALL));
            check("恢复准备金（8/8）后可达 ⇒ 能从水里垫出来：" + desc(floodedPick), floodedPick != null);
            // D-244 前提自证：**水柱**就这么两格 —— 下两格是水（⇒ 那两段 PILLAR 走上浮免放置），
            // 第三格不是水（⇒ 出水那一格仍然只能靠放置站上去）。两条一起读才说明"省料"省的是哪一段。
            var lvl = bot.serverLevel();
            check("前提自证：坑里 2 格**都是水**（" + desc(FLOOD_PIT_BOTTOM) + " / "
                            + desc(FLOOD_PIT_BOTTOM.above()) + "）⇒ 那两段是水柱上浮",
                    com.dddgn.alice.pathing.MovementHelper.isWater(lvl, FLOOD_PIT_BOTTOM)
                            && com.dddgn.alice.pathing.MovementHelper.isWater(lvl, FLOOD_PIT_BOTTOM.above()));
            check("前提自证：水柱上面那一格**不是水**（" + desc(FLOOD_PIT_BOTTOM.above(2))
                            + "）⇒ 出水那一段仍得靠放置",
                    !com.dddgn.alice.pathing.MovementHelper.isWater(lvl, FLOOD_PIT_BOTTOM.above(2)));
            // 路线形状**不作断言**（D-251 更正）：切片 B 落地后，同一场景里"水柱 `PILLAR`"（D-244）与
            // "破开侧壁 + 升到水面格"两条路**都自洽**，选哪条由成本决定（CORE 上下文里挖掘那条更便宜）。
            // 需求是"**从水里出来且不靠放置**"（下面的端到端 + 省料判据），不是某一条具体路线。
            // 这里改为两条**与路线无关**的前提：① 计划**自洽**（D-250 的口径在同一现场的第二处独立见证 ——
            // D-248 那次它就是红的）；② 计划从坑底起步。
            check("前提自证：逃生计划**自洽**（没有\u201C后面的段踩在自己挖掉的格子上\u201D，D-250/D-251）",
                    com.dddgn.alice.pathing.core.search.SelfWriteConsistency
                            .firstConflict(lvl, escapePlan.movements()) == null);
            var firstMove = escapePlan.movements().isEmpty() ? null : escapePlan.movements().get(0);
            check("前提自证：计划从**坑底**起步（"
                            + (firstMove == null ? "无" : firstMove.movementType() + " " + desc(firstMove.fromFoot())
                            + "→" + desc(firstMove.toFoot())) + "）",
                    firstMove != null && firstMove.fromFoot().equals(FLOOD_PIT_BOTTOM));
            return;
        }
        if (phaseTicks >= 5 && floodedPick != null && !floodedDone) {
            if (bot.isInWater()) {
                bot.setAirSupply(Math.max(2, bot.getAirSupply()));
            }
            if (floodedTask == null) {
                floodedTask = new com.dddgn.alice.task.SurvivalExitTask(bot, floodedPick, true);
            }
            // D-244 量法：**逐 tick 取放置计数的增量，并按"那一刻脚位是否在水里"归因**（比解析日志可靠：
            // 日志只有"放了"这一行，判据要的是"水里那几格到底放没放"）。
            boolean waterBefore = com.dddgn.alice.pathing.MovementHelper.isWater(
                    bot.serverLevel(), SurvivalSystem.footCell(bot));
            int placesBefore = com.dddgn.alice.action.WriteBudget.places(bot);
            var status = floodedTask.tick();
            int placedNow = Math.max(0, com.dddgn.alice.action.WriteBudget.places(bot) - placesBefore);
            if (placedNow > 0) {
                floodedPlaces += placedNow;
                if (waterBefore) {
                    floodedInWaterPlaces += placedNow;
                }
            }
            if (status == com.dddgn.alice.task.Task.Status.RUNNING && phaseTicks - 4 < FLOOD_BUDGET) {
                return;
            }
            floodedDone = true;
            // ✅ **D-243 已修**：`PILLAR` 执行器在水里改为**持续按住跳跃**（原版水里按跳跃即上浮；对照
            // Baritone `MovementPillar.java:150-161` 的水柱分支）⇒ 原来那条 tripwire（断言"执行失败"）
            // 已按设计翻红并**翻成正断言**：水下垫柱子真的把 bot 送出来了。
            BotLog.info("[Survival] 水里逃生：终态={}（D-243：PILLAR 水里上浮已生效）", status);
            check("端到端：水里逃生任务到达终态（" + status + " " + floodedTask.terminalReason() + "）",
                    status == com.dddgn.alice.task.Task.Status.DONE);
            check("端到端：bot **从水里出来了**（脚位 y=" + foot().getY() + " ≥ 101，inWater="
                            + bot.isInWater() + "）",
                    foot().getY() >= FLOOD_PIT_BOTTOM.getY() + 2 && !bot.isInWater());
            // D-244 主判据（红/绿分界）：整段逃生 **脚位在水里时一次都没放方块**（Baritone 水柱那支）。
            // 反向对照就是 D-243 之前的实测：水里那一段会 `[Pillar] placed`（脚位 100 那格是水）⇒ 这里会变 1。
            check("省料（D-244）：**脚位在水里时一次都没放方块**（水里放置=" + floodedInWaterPlaces + "）",
                    floodedInWaterPlaces == 0);
            // ✅ 实测（D-244，2026-09-16）：这条出口的**整段逃生一个方块都没放** —— 计划就两段
            // （`PILLAR` 水柱上浮 1 格 + `ASCEND` 从水里跳上干地板），两段都不写世界。
            // 这正是 D-242 的结论落地后的样子：**水里自救不是授权问题**（逃生准备金的作用只是让
            // "写类 Movement"（PILLAR）能被规划出来 —— 纯通行档这一格都不会生成 —— 实际额度一分没花）。
            check("省料（D-244）：整段逃生**一个方块都没放**（放置=" + floodedPlaces
                            + "）⇒ 水里自救零世界写入：上浮 + 从水里跳上干地板，全靠 Movement",
                    floodedPlaces == 0);
            return;
        }
        if (phaseTicks >= 5) {
            bot.controller().stopMovement();
            teleport(SurvivalCourseAnchor.PLATFORM_FOOT);
            // 收尾：把被逃生压过的预算恢复成默认（电池里整台是一个作用域，不恢复会限制后续步骤）
            com.dddgn.alice.action.WriteBudget.setCaps(com.dddgn.alice.action.WriteBudget.scopeOf(bot),
                    com.dddgn.alice.action.WriteBudget.Caps.DEFAULT);
            bot.setAirSupply(300);
            fillBlocks(FLOOD_CENTER.offset(-5, -5, -4), FLOOD_CENTER.offset(5, 4, 4),
                    "minecraft:air", 863, "灌水竖坑拆除");
            normalizeVitals();
            BotLog.info("[Survival] 灌水竖坑已拆、bot 回平台；本相位完");
            advance(Phase.DONE);
            return;
        }
    }

    /** 灌水竖坑所在实心石块的中心（房间地板层）。 */
    private static final BlockPos FLOOD_CENTER = new BlockPos(340, 100, 306);

    /** 坑底（bot 站在水里；房间地板 = 它的 y+2）。 */
    private static final BlockPos FLOOD_PIT_BOTTOM = FLOOD_CENTER.below();

    private com.dddgn.alice.task.SurvivalExitTask floodedTask;
    private BlockPos floodedPick;
    private boolean floodedDone;

    // ==================== D-244 省料量法（逐 tick 取放置增量） ====================

    /** 干燥竖坑逃生期间的**实际放置数**（反向对照：干地必须仍然靠放置上来）。 */
    private int shaftPlaces;

    /** 灌水竖坑逃生期间的**实际放置数**（含出水那一格）。 */
    private int floodedPlaces;

    /** 其中**脚位还在水里**时发生的那部分（D-244 主判据要求 = 0）。 */
    private int floodedInWaterPlaces;

    /** 竖坑所在实心石块的中心（房间地板层）。 */
    private static final BlockPos SHAFT_CENTER = new BlockPos(330, 100, 306);

    /** 坑底（bot 站这里；房间地板 = 它的 y+2）。 */
    private static final BlockPos SHAFT_PIT_BOTTOM = SHAFT_CENTER.below();

    private static final int SHAFT_BUDGET = 240;

    /** 灌水竖坑那档的路更长（先上浮再爬出），预算给足。 */
    private static final int FLOOD_BUDGET = 400;

    private com.dddgn.alice.task.SurvivalExitTask shaftEscapeTask;
    private BlockPos escapePick;
    private boolean shaftDone;

    /** 石盒中心（bot 站这里；隔壁空气袋在 +2,+2）。 */
    private static final BlockPos UNREACHABLE_CENTER = new BlockPos(270, 100, 306);

    /** 开阔水池的中心（池底所在层；bot 站其上一格）。 */
    private static final BlockPos OPEN_CENTER = new BlockPos(240, 99, 306);

    /** 端到端上浮自救的预算（tick）。 */
    private static final int FLOAT_E2E_BUDGET = 150;

    private com.dddgn.alice.task.SurvivalFloatTask floatTask;
    private boolean eyeUnderWaterBefore;

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

    /**
     * **掉血可见**（`previousHealth` 的第一个读者）+ 冷却期内不刷屏。
     *
     * <p><b>2026-09-18 修（D-312）</b>：本条判据曾**偶发假红 3 次**（都在 CORE 轮次）。根因**不在**
     * "没掉血"，也不在冷却/吸收/护甲，而是**注入时机撞上了原版「伤害叠加」规则**：
     * <ol>
     *   <li>本相位紧跟着**着火相位**（每 20 tick 打 1 点火焰伤害）⇒ 注入那一刻上一发的 i-frame 还在
     *       （实测 `invulnerableTime=11`）；</li>
     *   <li>原版 `LivingEntity.hurt` 的冷却分支对这种情况**只打「增量」**：`actuallyHurt(amount - lastHurt)`
     *       且**不刷新** `invulnerableTime/hurtTime` ⇒ 注入 2.0 实际只落地 **1.0**
     *       （实测：`ledger lastAmount=1.0`、`invuln 11→11`、`hurtTime 0→0`；分支见 Forge mapped jar
     *       `LivingEntity.hurt` 字节码偏移 249–331）；</li>
     *   <li>而本项目的回血是**无条件 +1 点 / 20 tick**（D-261/D-271 实测）⇒ **恰好 1.0 的净掉会被一次
     *       回血在采样上抹平**；掉血检测读的是**单 tick 边缘**（`HazardState.previousHealth > health`）⇒
     *       回血拍只要落进"注入 → 下一次观察"那 1 tick 缝里，这次掉血就**永远不会被上报**（边缘被消耗掉）
     *       ⇒ 两条判据同时红（"掉血必须变成可判读的事实" + "恰好 1 条"），且 `checks` 少 1（子判据被跳过）。</li>
     * </ol>
     * **修法（2026-09-18 用户批准「只修夹具前提」，不动产线语义）**：① 注入前**等 i-frame 过期**
     * （走 else 分支打满 {@link #HURT_AMOUNT}）；② {@link #normalizeVitals()} 满血作基准；
     * ③ 把前提**写成判据**（`hurt` 返回值 + 净掉 ≥ {@link #HURT_AMOUNT}）—— 将来环境再削伤害，会
     * **响亮地红在前提上**，而不是神秘地丢事件。
     *
     * <p>为什么这样就**结构上不再偶发**：+1 的回血永远填不平 ≥2 点的采样缺口（确定性对照实验见 D-312）。
     */
    private void healthPhase() {
        if (phaseTicks == 1) {
            bot.clearFire();
            teleport(SurvivalCourseAnchor.PLATFORM_FOOT);
            return;
        }
        if (injectPhaseTick == 0) {
            // ① 等 i-frame 过期（着火相位的余波）。上限内等不到也照注入 —— 那时前提判据会如实判红，
            //    比"静默丢事件"好定位得多。
            if (bot.invulnerableTime > 10 && phaseTicks < HEALTH_INJECT_MAX_WAIT_TICKS) {
                return;
            }
            int invulnBefore = bot.invulnerableTime;
            normalizeVitals();      // ② 满血作基准：净掉 = 完整的 HURT_AMOUNT
            EventThresholds.resetHealthTracking(bot);
            hurtTick = bot.getServer().getTickCount();
            injectPhaseTick = phaseTicks;
            float before = bot.getHealth();
            boolean applied = bot.hurt(bot.damageSources().generic(), HURT_AMOUNT);
            float after = bot.getHealth();
            // ③ 前提自证：净掉必须 > 一次回血的 1 点，否则检测器的单 tick 边缘会被回血抹平（D-312）
            check("前提自证：注入的 " + HURT_AMOUNT + " 点伤害必须真的落地并造成 ≥" + HURT_AMOUNT
                            + " 点净掉（i-frame 未过期时原版只打「增量」、吸收/护甲也会削；净掉被削到 ≤1 点时，"
                            + "一次回血就能在采样上把这次掉血抹平 ⇒ 判据偶发假红，见 D-312）"
                            + "（hurt=" + applied + " 血量 " + before + "→" + after
                            + " 净掉=" + (before - after) + " 注入前 i-frame=" + invulnBefore + "）",
                    applied && before - after >= HURT_AMOUNT - 0.01F);
            // ④ 前提自证（D-325）：掉血检测的**边缘基准**（监视器的「上一拍血量」）必须等于抬血后的满血。
            //    D-312 只证了"缺口够大"，**没证基准对不对**：监视器每 tick 至多观察一次，而本夹具在同 tick 内
            //    先抬血再打血 ⇒ 监视器那一拍看到的还是**改血前**的值（实测 19.0）⇒ 基准停在 19，下一拍
            //    回血 +1 把血量也带到 19 ⇒ `19 < 19` 为假 ⇒ 边缘被自家抬血吃掉、事件静默丢失（2026-09-18
            //    CORE 假红的实证：注入后没有任何 `[Threshold] 掉血` 行）。把基准写成判据 ⇒ 环境再变也响亮红在这里。
            check("前提自证：掉血检测的边缘基准必须 = 抬血后的满血 " + before
                            + "（监视器的「上一拍血量」比的是它；基准停在旧值 19 时，一次回血就能让 19<19 为假 ⇒"
                            + " 事件静默丢失，见 D-325）（实际基准=" + SurvivalSystem.healthBaseline(bot) + "）",
                    Math.abs(SurvivalSystem.healthBaseline(bot) - before) < 0.01F);
            BotLog.info("[Survival] 夹具对 bot 造成 {} 点伤害（血量 {}→{}，净掉 {}，注入前 i-frame {}），"
                            + "期望：掉血 ⇒ 一条 DANGER 事件",
                    HURT_AMOUNT, before, after, before - after, invulnBefore);
            return;
        }
        if (phaseTicks == injectPhaseTick + HEALTH_POLL_TICKS) {
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
        if (phaseTicks < injectPhaseTick + HEALTH_POLL_TICKS + HEALTH_QUIET_TICKS) {
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
        // ⚠️ **区块没加载时 `/fill` 会静默 0 改动**（2026-09-16 实测踩到一次：330 那一档三个 fill 全 0
        // ⇒ bot 被传进空气、8 格内没有落点 ⇒ 一次红了 8 条判据，看起来像"改动坏了"）。
        // 治本：动手前先确认整块区域已加载；没加载就把 bot 传到区块中心（玩家 ticket 同步加载区块）。
        // 下面那条 `changed >= expected` 断言保持不动 —— 它仍然会在真失败时红。
        if (!areaLoaded(from, to)) {
            BlockPos center = new BlockPos((from.getX() + to.getX()) / 2, (from.getY() + to.getY()) / 2,
                    (from.getZ() + to.getZ()) / 2);
            BotLog.warn("[Survival] fill 前区块未加载 ⇒ 先把 bot 传到 {} 加载区块（{}）", desc(center), what);
            teleport(center);
        }
        int changed = runCommand("fill " + xyz(from) + " " + xyz(to) + " " + block);
        check("场景自建生效：" + what + "（fill 改动方块数 " + changed + "，期望 ≥ " + expectedBlocks + "）",
                changed >= expectedBlocks);
    }

    /** 整块区域是否都已加载（`/fill` 的硬前提）。 */
    private boolean areaLoaded(BlockPos from, BlockPos to) {
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            if (!bot.serverLevel().hasChunkAt(pos)) {
                return false;
            }
        }
        return true;
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
