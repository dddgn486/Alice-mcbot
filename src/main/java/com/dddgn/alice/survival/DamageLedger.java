package com.dddgn.alice.survival;

import com.dddgn.alice.bot.BotPlayer;
import net.minecraft.world.damagesource.DamageSource;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * **S-9（2026-09-17，用户裁定「做」）**：伤害**按事件观测**，不再只靠"采样血量差"。
 *
 * <p>为什么必须这样：Alice 手动调 `Player.aiStep()` ⇒ **无条件** +1 HP / 20 tick 回血
 * （食物/饱和度清零也关不掉，D-261 实测）。而原版火焰伤害恰好也是 **1 HP / 20 tick**
 * ⇒ 两者**互相抵消** ⇒ 净血量变化为 0 ⇒ `SurvivalSystem` 的"血量差"**看不见火焰伤害**。
 * 这不是"伤害没发生"，而是"观测手段选错了"。
 *
 * <p>本类把 Forge `LivingDamageEvent`（**实际扣血量**）记成**按 bot 的台账**：
 * 命中次数、累计伤害、最后一次的来源与时刻。它是**记账**，不是决策 —— 消费者（维生/保险道具/
 * 判据）按需查询；`previousHealth` 那条采样路径保留（它还有"净变化"的信息价值，只是不足以观测伤害）。
 *
 * <p>⚠️ 口径：只记 **`LivingDamageEvent`**（已经过护甲/抗性结算的**真实扣血**）。
 * `LivingHurtEvent` 是"结算前、可被取消"的量 ⇒ 拿它当"实际受伤"会把被免疫的伤害也算进去。
 */
public final class DamageLedger {

    /** 一次命中（供**窗口查询**用；环有界，不会无限增长）。 */
    public record Hit(long tick, double amount, String source) {
    }

    /** 保留的命中条数上限（决策层只需要"最近几下"，不需要全史）。 */
    private static final int RECENT_CAP = 8;

    /** 单个 bot 的伤害台账。 */
    public static final class Entry {
        private long hits;
        private double totalDamage;
        private long lastTick = -1L;
        private String lastSource = "-";
        private double lastAmount;
        private final java.util.ArrayDeque<Hit> recent = new java.util.ArrayDeque<>();

        public long hits() {
            return hits;
        }

        public double totalDamage() {
            return totalDamage;
        }

        public long lastTick() {
            return lastTick;
        }

        public String lastSource() {
            return lastSource;
        }

        public double lastAmount() {
            return lastAmount;
        }
    }

    private static final Map<UUID, Entry> ENTRIES = new HashMap<>();

    private DamageLedger() {
    }

    /** Forge 钩子调用点：记一次**实际**伤害。 */
    public static void record(BotPlayer bot, float amount, DamageSource source, long tick) {
        if (amount <= 0.0F) {
            return;   // 0 伤害不是"受伤事实"（很多事件会以 0 触发）
        }
        Entry entry = ENTRIES.computeIfAbsent(bot.getUUID(), ignored -> new Entry());
        entry.hits++;
        entry.totalDamage += amount;
        entry.lastTick = tick;
        entry.lastAmount = amount;
        entry.lastSource = source == null ? "-" : source.getMsgId();
        entry.recent.addLast(new Hit(tick, amount, entry.lastSource));
        while (entry.recent.size() > RECENT_CAP) {
            entry.recent.removeFirst();
        }
    }

    /** **窗口查询**：`sinceTick` 之后（含）发生的命中条数 —— "最近挨了几下"的事实来源。 */
    public static int hitsSince(BotPlayer bot, long sinceTick) {
        int count = 0;
        for (Hit hit : of(bot).recent) {
            if (hit.tick() >= sinceTick) {
                count++;
            }
        }
        return count;
    }

    /** **窗口查询**：`sinceTick` 之后的累计扣血量。 */
    public static double totalSince(BotPlayer bot, long sinceTick) {
        double sum = 0.0D;
        for (Hit hit : of(bot).recent) {
            if (hit.tick() >= sinceTick) {
                sum += hit.amount();
            }
        }
        return sum;
    }

    /** 该 bot 的台账（没有记录时返回空表）。 */
    public static Entry of(BotPlayer bot) {
        return ENTRIES.computeIfAbsent(bot.getUUID(), ignored -> new Entry());
    }

    /** **夹具/自检用**：清零该 bot 的台账（每次用例独立）。 */
    public static void reset(BotPlayer bot) {
        ENTRIES.put(bot.getUUID(), new Entry());
    }
}
