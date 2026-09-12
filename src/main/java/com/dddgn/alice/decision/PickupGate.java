package com.dddgn.alice.decision;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * **被动拾取闸门**（S3.5 / D-138 裁定第 5 条）：走路路过时的自动吸附也必须走同一份 {@link DropPolicy}。
 *
 * <p>用户 2026-09-12 追问"有办法控制 bot 的被动拾取吗"——原版是**路过就吸附**，
 * 这条路径会**绕过** {@code CollectJob} 的策略（"不派它去捡玩家的东西"会被一次路过推翻）。
 *
 * <p>钩子（已用字节码确认）：`ItemEntity.playerTouch → ForgeEventFactory.onItemPickup(...)`
 * ⇒ Forge 的 {@link EntityItemPickupEvent} 正好卡在"物品要进背包"之前，**可取消**。
 * 只取消**这一次转移**，不改物品实体（**不用** `setNeverPickUp()`，那会连玩家一起禁掉）。
 *
 * <p>默认档位（用户裁定）：被动拾取 = `auto` —— 只放行 `OURS_*`；`FOREIGN` 策略是 `ASK`，
 * 而在**被动**路径上 `ASK` **直接拦下**（不弹请示，否则每路过一堆就问一次会变骚扰）；
 * 想捡 `FOREIGN` 只能显式派活（`CollectJob` + 授权）。
 */
@Mod.EventBusSubscriber(modid = "alice")
public final class PickupGate {

    /** 同一件物品的拦截日志冷却（tick）与累计次数：防刷屏，同时保留"被拦了多少次"的事实。 */
    private static final long LOG_COOLDOWN_TICKS = 100L;
    private static final java.util.Map<java.util.UUID, long[]> BLOCKED = new java.util.HashMap<>();
    private static long blockedTotal;

    private PickupGate() {
    }

    private static synchronized void logBlocked(BotPlayer bot, ItemEntity item,
                                                DropPolicy.Provenance provenance) {
        blockedTotal++;
        long now = bot.getServer().getTickCount();
        long[] state = BLOCKED.computeIfAbsent(item.getUUID(), ignored -> new long[]{now, 0});
        state[1]++;
        boolean first = state[0] == now || now - state[0] >= LOG_COOLDOWN_TICKS;
        if (state[0] != now && now - state[0] >= LOG_COOLDOWN_TICKS) {
            state[0] = now;   // 冷却到期 ⇒ 允许再报一次（并带上这一段累计次数）
        }
        if (first) {
            long repeats = state[1];
            state[1] = 0;
            BotLog.warn("[Pickup] blocked bot={} item={} x{} provenance={} policy={} 次数={} 累计={}"
                            + "（被动拾取闸门；要捡请显式派活或先授权）",
                    bot.getName().getString(), item.getItem().getItem(), item.getItem().getCount(),
                    provenance, DropPolicy.policy(bot, provenance), repeats, blockedTotal);
        }
        if (BLOCKED.size() > 256) {
            BLOCKED.entrySet().removeIf(entry -> now - entry.getValue()[0] > 1200L);
        }
    }

    /** 本次服务器会话累计拦截次数（汇报/自检可读）。 */
    public static synchronized long blockedTotal() {
        return blockedTotal;
    }

    @SubscribeEvent
    public static void onItemPickup(EntityItemPickupEvent event) {
        if (!(event.getEntity() instanceof BotPlayer bot)) {
            return;   // 真人玩家一概不动
        }
        ItemEntity item = event.getItem();
        if (item == null) {
            return;
        }
        // 归属判定**只走这一个入口**（登记在册 → 授权区 → FOREIGN），与主动收集路径共用
        DropPolicy.Provenance provenance = DropPolicy.effectiveProvenance(bot, item);
        if (DropPolicy.mayPickUpPassively(bot, provenance)) {
            return;
        }
        event.setCanceled(true);
        // **节流**（D-143 附注）：bot 站在物品上时事件**每 tick** 都触发 ——
        // 2026-09-12 实测一次测试刷了 584 行。按项目规矩"报警只报可行动的病症"：
        // 同一件物品 100 tick 内只报一次，之后只**累计次数**（计数在报告/汇总里可见）。
        logBlocked(bot, item, provenance);
    }
}
