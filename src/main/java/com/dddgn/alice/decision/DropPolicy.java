package com.dddgn.alice.decision;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;

/**
 * **掉落物归属与收集策略**（S3.5 / D-138 裁定，2026-09-12）。
 *
 * <p>动机（用户提出）：现在"能捡什么"只认"我方破坏事件配对"，于是漏掉两类真实需要：
 * <ol>
 *   <li>**我方行为的间接后果** —— 砍树后**树叶自然衰减**掉的树苗/木棍；移除支撑后**仙人掌/甘蔗**弹出；
 *       重力方块摔碎；它们不是"我方破坏事件"，但是**我方行为的结果**；</li>
 *   <li>**玩家派活** —— "去把那片东西捡了"（那里可能是玩家丢的/箱子碎的/怪掉的）。</li>
 * </ol>
 * 而"默认谁都捡"是危险的（捡走玩家故意丢的东西、破坏玩家的物品摆放、多 bot 互相抢）。
 *
 * <p>**两条路径共用同一份策略**：主动（`CollectJob`）与**被动（走路路过自动吸附）** ——
 * 被动那条由 {@link PickupGate} 在 `EntityItemPickupEvent` 上拦截，绝不能绕过本判定。
 */
public final class DropPolicy {

    /** 掉落物来源。 */
    public enum Provenance {
        /** 我方破坏事件**直接**产生（现成的配对）。 */
        OURS_DIRECT,
        /** 我方行为的**间接后果**（时间窗 + 空间窗归属：树叶衰减、支撑被移除后弹出…）。 */
        OURS_INDIRECT,
        /** 落在**玩家授权**的收集区域/时段内（`CollectGrant`；S3.5 第二步落地）。 */
        GRANTED_AREA,
        /** 其它（玩家丢的、未知来源）。 */
        FOREIGN
    }

    /** 能力名（复用 `PermissionGate` 的策略表 ⇒ `/alice policy drop.foreign ASK` 直接可用）。 */
    public static final String CAP_OURS_DIRECT = "drop.ours_direct";
    public static final String CAP_OURS_INDIRECT = "drop.ours_indirect";
    public static final String CAP_GRANTED_AREA = "drop.granted_area";
    public static final String CAP_FOREIGN = "drop.foreign";

    /**
     * **间接归属窗口**（用户 2026-09-12 裁定：起点 **60 tick / 4 格**，可配置 + 写进报告便于标定）。
     * 树叶离树干 2–3 格，所以半径至少要 3–4。
     */
    public static final int INDIRECT_WINDOW_TICKS = 60;
    public static final double INDIRECT_WINDOW_RADIUS = 4.0D;

    private DropPolicy() {
    }

    public static String capability(Provenance provenance) {
        return switch (provenance) {
            case OURS_DIRECT -> CAP_OURS_DIRECT;
            case OURS_INDIRECT -> CAP_OURS_INDIRECT;
            case GRANTED_AREA -> CAP_GRANTED_AREA;
            case FOREIGN -> CAP_FOREIGN;
        };
    }

    /**
     * **有效归属**（S3.5 第二步）：按优先级判定 —— ① 我方登记在册（`ScopeBuffer`，直接/间接）
     * ② 落在**玩家授权区**内（`CollectGrants`）③ 其余 = `FOREIGN`。
     *
     * <p>主动（`CollectJob`）与被动（`PickupGate`）**都必须用这一个入口**判定，不允许各写一套。
     */
    public static Provenance effectiveProvenance(BotPlayer bot, net.minecraft.world.entity.item.ItemEntity item) {
        if (bot == null || item == null) {
            return Provenance.FOREIGN;
        }
        var session = com.dddgn.alice.bot.BotManager.sessionOf(bot);
        Provenance registered = session == null ? null : session.scope().provenanceOf(item);
        if (registered != null) {
            return registered;
        }
        if (CollectGrants.covering(bot.getServer(), item.blockPosition()) != null) {
            return Provenance.GRANTED_AREA;
        }
        return Provenance.FOREIGN;
    }

    /** 策略（默认值由 `PermissionGate` 的表给出：我方的 AUTO，FOREIGN 的 ASK）。 */
    public static PermissionGate.Policy policy(BotPlayer bot, Provenance provenance) {
        return PermissionGate.policy(bot.getServer(), capability(provenance));
    }

    /** **能不能捡**（主动路径用）：`AUTO`/`NOTIFY` 可以；`ASK`/`IGNORE` 不可以。 */
    public static boolean mayCollect(BotPlayer bot, Provenance provenance) {
        PermissionGate.Policy policy = policy(bot, provenance);
        return policy == PermissionGate.Policy.AUTO || policy == PermissionGate.Policy.NOTIFY;
    }

    /**
     * **被动拾取**是否放行（走路路过自动吸附时调用）。
     *
     * <p>关键取舍：`ASK` 在**被动**路径上**直接拦下**（不弹请示）—— 否则每路过一堆东西就问一次会变成骚扰；
     * 想捡 `FOREIGN` 只能**显式派活**（`CollectJob` + 授权）或玩家先授权。
     */
    public static boolean mayPickUpPassively(BotPlayer bot, Provenance provenance) {
        PermissionGate.Policy policy = policy(bot, provenance);
        if (policy == PermissionGate.Policy.NOTIFY) {
            BotLog.info("[Pickup] 被动拾取放行（策略=NOTIFY） provenance={} item={}", provenance,
                    capability(provenance));
            return true;
        }
        return policy == PermissionGate.Policy.AUTO;
    }

    public static String describeWindow() {
        return "indirect_window=" + INDIRECT_WINDOW_TICKS + "tick/" + INDIRECT_WINDOW_RADIUS + "格";
    }
}
