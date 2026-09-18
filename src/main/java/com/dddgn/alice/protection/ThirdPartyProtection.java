package com.dddgn.alice.protection;

import com.dddgn.alice.compat.ftbchunks.FtbChunksBridge;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * **第三方保护层**（`D-326`）—— 与 {@link SafeZoneData}（**我们自己的**保护区）并列的那一层：
 * 这里问的是**别的模组的裁决**。
 *
 * <p><b>为什么需要它</b>：三条**批量写**路径（道路施工的 `placeBulkEdit`/`breakForBulkEdit`、伐木补种的
 * 直接 `setBlock`）走的是**世界底层写入**，而 Forge 的破坏/放置事件由 `ServerPlayerGameMode` 触发
 * （已反汇编核实：`Level.destroyBlock` **不**触发 `BlockEvent.BreakEvent`）⇒ **FTB 认领、以及任何靠
 * Forge 事件做保护的模组，对这三条路径完全不可见**。2026-09-18 的门禁实测过：
 * 在别人队伍的认领内，批量放置/破坏**都成功且世界真的被改了**。本类就是把这三条路收口。
 *
 * <p><b>判据来源</b>：调用 FTB **自己那条裁决函数**（{@link FtbChunksBridge#wouldPreventBlockEdit}）——
 * 不自己发明规则（不改 FTB 的任何数据、不猜它的配置）。
 *
 * <p><b>边界（诚实说明）</b>：
 * <ol>
 *   <li>FTB 不在场 / 我们的反射签名对不上 ⇒ **不拦**（fail-open），但打一条 warn —— 自方闸门仍是权威，
 *       而且不许"我们自己的反射失配"把荒野里的修路也锁死；每次进程只 warn 一次。</li>
 *   <li>调用期异常同样按 fail-open 处理（留痕），理由同上。</li>
 *   <li>只覆盖**批量写**三条路；单方块路径（`placeAt`/`BlockBreakSession`）本来就走原版交互
 *       ⇒ FTB 自己会拦，不需要这里再问一遍。</li>
 * </ol>
 */
public final class ThirdPartyProtection {

    /** 拒绝理由码（落进既有 `[WRITE-REFUSED] … reason=` 口径）。 */
    public static final String FTB_CLAIM_DENIED = "ftb_claim_denied";

    private static final AtomicBoolean WARNED_UNAVAILABLE = new AtomicBoolean();
    private static final AtomicBoolean WARNED_ERROR = new AtomicBoolean();

    private ThirdPartyProtection() {
    }

    /**
     * @return `null` = 允许写（含"第三方保护不可用"）；非 null = **拒绝理由码**（调用方据此拒绝并留痕）
     */
    public static String refusalReason(ServerPlayer bot, BlockPos pos) {
        if (!FtbChunksBridge.available()) {
            if (WARNED_UNAVAILABLE.compareAndSet(false, true)) {
                BotLog.warn("[FTB] 第三方保护预检不可用（{}）⇒ 批量写路径**不拦**（自方保护区/预算闸门仍然有效）",
                        FtbChunksBridge.unavailableReason());
            }
            return null;
        }
        try {
            return FtbChunksBridge.wouldPreventBlockEdit(bot, pos) ? FTB_CLAIM_DENIED : null;
        } catch (RuntimeException | Error failure) {
            if (WARNED_ERROR.compareAndSet(false, true)) {
                BotLog.warn("[FTB] 第三方保护预检调用失败（{}）⇒ 批量写路径**不拦**（自方闸门仍有效）",
                        failure.toString());
            }
            return null;
        }
    }
}
