package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.item.FixtureToolKit;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.survival.DamageLedger;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * **S-9 判据（2026-09-17，用户裁定「伤害改用事件观测」）**：证明**采样血量看不见的伤害**能被观测到。
 *
 * <p>**要证明的事实**（2026-09-17 实测校正，别把话说满）：
 * ① 事件路径（{@link DamageLedger} ← `LivingDamageEvent`）**精确**记下每一次真实扣血（次数/累计/来源/时刻）；
 * ② "采样血量差"那条路径**只能看净变化** —— Alice 手动调 `aiStep()` ⇒ 无条件 +1 HP / 20 tick 回血
 *    （D-261 实测），伤害被回血抹平后，**窗口净变化 ≈ 0**，于是"这段时间到底挨了几下、多重"读不出来。
 *    ⚠️ 注意（实测更正）：采样路径**并非完全看不见**单 tick 掉血（若采样恰好落在伤害与回血之间，
 *    会记到 `tickLoss=1.0`）—— 所以本夹具断言的是**净变化**，不是"采样为零"。
 *
 * <p>**为什么不用"点火"造伤害**（原设计，实测否决）：点火会被**维生系统**判为 `ON_FIRE` 危险
 * ⇒ `任务因维生危险中断: reason=failed:survival_on_fire` ⇒ 电池任务被中断、**没有判决**（`exit=3`）。
 * 这是**生产行为正确**（危险就该中断），但也给死亡/保险设计留了一条硬结论：
 * **保险/保命逻辑必须活在维生中断之下，不能指望任务层还在跑**。
 * 所以这里改用**非危险伤害源**（`damageSources().generic()`）精确制造 N 次 1 点伤害。
 *
 * <p>反向对照：把 `BotManager.onBotDamage` 关掉 ⇒ ① 必红（② 照样成立）。
 */
public class DamageEventVisibilityCheckTask implements Task {

    /** 观察窗口（tick）。 */
    private static final int OBSERVE_TICKS = 130;
    /** 施加的伤害次数（精确断言：一次不多、一次不少）。 */
    private static final int PLANNED_HITS = 4;
    /** 每两次伤害之间的间隔（tick）—— 留够回血把血量顶回去的时间。 */
    private static final int HIT_INTERVAL = 30;

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private int checksRun;
    private int ticks;
    private float startHealth;
    private float minHealth;
    private int appliedHits;
    private boolean done;

    public DamageEventVisibilityCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "DamageEventVisibilityCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(OreCourseAnchor.START_FOOT);
    }

    @Override
    public String failureReason() {
        return failures.isEmpty() ? "" : String.join(" | ", failures);
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
        ticks++;
        if (ticks == 1) {
            // 夹具纪律：自定位 + 复位库存（不依赖电池 provision）
            bot.teleportTo(bot.serverLevel(),
                    OreCourseAnchor.START_FOOT.getX() + 0.5D,
                    OreCourseAnchor.START_FOOT.getY(),
                    OreCourseAnchor.START_FOOT.getZ() + 0.5D,
                    java.util.Set.of(), bot.getYRot(), bot.getXRot());
            bot.setDeltaMovement(Vec3.ZERO);
            bot.controller().stopMovement();
            FixtureToolKit.resetInventory(bot);
            bot.setHealth(bot.getMaxHealth());
            DamageLedger.reset(bot);
            startHealth = bot.getHealth();
            minHealth = startHealth;
            BotLog.info("[DamageEvent] 夹具：用**非危险**伤害源施加 {} 次 1.0 点伤害（每 {} tick 一次），"
                            + "观察 {} tick", PLANNED_HITS, HIT_INTERVAL, OBSERVE_TICKS);
            return Status.RUNNING;
        }
        if (appliedHits < PLANNED_HITS && ticks % HIT_INTERVAL == 0) {
            bot.hurt(bot.damageSources().generic(), 1.0F);   // 非危险源 ⇒ 不会被维生中断
            appliedHits++;
        }
        minHealth = Math.min(minHealth, bot.getHealth());
        if (ticks < OBSERVE_TICKS) {
            return Status.RUNNING;
        }
        DamageLedger.Entry entry = DamageLedger.of(bot);
        float endHealth = bot.getHealth();
        float sampledDelta = Math.abs(endHealth - startHealth);
        BotLog.info("[DamageEvent] 观测结果：事件路径 hits={} total={} lastSource={} lastTick={}；"
                        + "采样路径 health {} → {}（净变化 {}；窗口内最低 {}）",
                entry.hits(), entry.totalDamage(), entry.lastSource(), entry.lastTick(),
                startHealth, endHealth, sampledDelta, minHealth);
        // ① 事件路径必须**精确**记下每一次（一次不多、一次不少）
        check("事件路径命中数 == 施加次数（" + PLANNED_HITS + "，实测 " + entry.hits() + "）",
                entry.hits() == PLANNED_HITS);
        check("事件路径累计伤害 == 施加总量（" + PLANNED_HITS + ".0，实测 " + entry.totalDamage() + "）",
                Math.abs(entry.totalDamage() - PLANNED_HITS) < 0.001D);
        check("来源归因可用（lastSource=" + entry.lastSource() + "）",
                entry.lastSource() != null && !entry.lastSource().isBlank() && !"-".equals(entry.lastSource()));
        check("最后命中时刻已被记录（lastTick=" + entry.lastTick() + "）", entry.lastTick() >= 0);
        // ② 采样路径**读不出总量**：净变化被回血抹平（≤1），尽管期间挨了 PLANNED_HITS 点伤害
        check("采样路径净血量变化 ≤1（" + sampledDelta + "）——即『靠净血量读不出挨了几下』",
                sampledDelta <= 1.0F);
        bot.setHealth(bot.getMaxHealth());
        bot.teleportTo(bot.serverLevel(),
                OreCourseAnchor.START_FOOT.getX() + 0.5D,
                OreCourseAnchor.START_FOOT.getY(),
                OreCourseAnchor.START_FOOT.getZ() + 0.5D,
                java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(Vec3.ZERO);
        bot.controller().stopMovement();
        done = true;
        boolean pass = failures.isEmpty();
        BotLog.info("[DamageEvent] SUMMARY checks={} failures={} {} → {}",
                checksRun, failures.size(), failures, pass ? "PASS" : "FAIL");
        if (observer != null && !observer.hasDisconnected() && !observer.isRemoved()) {
            observer.sendSystemMessage(Component.literal("[alice] 伤害事件观测自检 "
                    + (pass ? "PASS（" + entry.hits() + " 次扣血可见，血量差却为 "
                    + String.format(java.util.Locale.ROOT, "%.1f", sampledDelta) + "）"
                    : "FAIL " + failures)));
        }
        return Status.RUNNING;
    }

    private void check(String what, boolean ok) {
        checksRun++;
        if (!ok) {
            failures.add(what);
        }
    }
}
