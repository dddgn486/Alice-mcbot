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

    private PickupGate() {
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
        BotManager.BotSession session = BotManager.sessionOf(bot);
        DropPolicy.Provenance provenance = session == null ? null
                : session.scope().provenanceOf(item);
        if (provenance == null) {
            provenance = DropPolicy.Provenance.FOREIGN;
        }
        if (DropPolicy.mayPickUpPassively(bot, provenance)) {
            return;
        }
        event.setCanceled(true);
        BotLog.warn("[Pickup] blocked bot={} item={} x{} provenance={} policy={}（被动拾取闸门；"
                        + "要捡请显式派活或先授权）",
                bot.getName().getString(), item.getItem().getItem(), item.getItem().getCount(),
                provenance, DropPolicy.policy(bot, provenance));
    }
}
