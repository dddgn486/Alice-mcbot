package com.dddgn.alice.task;

import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.protection.ThirdPartyProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * **夹具前提：这段范围不在「别人的保护」里**（`D-409`）。
 *
 * <h2>为什么需要它</h2>
 * 无头电池的世界母本 = **玩家真实存档的副本**（`tools/headless-battery.sh:33-39` 的设计，为了让 5 个
 * 依赖绝对坐标的 CORE 步拿到真地形）。代价是：夹具会**继承玩家世界的一切** —— 其中包括**玩家自己的
 * FTB Chunks 认领**。
 *
 * <p>2026-09-23 实测（`D-409`）：`lumber_course_*` 场景是从真实存档抓下来的（坐标就在玩家基地里），
 * 而那块地已被玩家认领 ⇒ FTB 取消 bot 的破坏事件（`gameMode.destroyBlock` 返回 false）⇒ 夹具拿到
 * "树砍不动"的世界。**表现却是内核失败码 `no_reachable_candidate`** —— **错的原因、对的表象**，
 * 于是这一步连红 35 轮，所有人（包括勘测侧）都以为内核坏了。
 *
 * <h2>口径（诚实标注）</h2>
 * <ul>
 *   <li>裁决**不自己发明**：调用 {@link ThirdPartyProtection#refusalReason}，它转发 FTB 自己的
 *       {@code ClaimedChunkManager.shouldPreventInteraction + Protection.EDIT_BLOCK}；</li>
 *   <li>**采样粒度 = 每个区块一次**：第三方保护是按区块的（与我们的 `SafeZoneData` 同形），
 *       同区块内再采样不改变结论。采样点取「盒 ∩ 区块」的最小角（**保证落在盒内**）；</li>
 *   <li>**fail-open**：FTB 不在场 / 桥不可用 ⇒ 返回 `null`（前提成立）。这与 `D-326` 的取舍一致
 *       —— 不许「我们自己的反射失配」把荒野里的夹具全锁死；桥不可用时它自己会打 warn；</li>
 *   <li>⚠️ 本类**不改世界、不认领、不解认领**（`FtbChunksBridge` 是只读桥）；它只回答"前提成不成立"。</li>
 * </ul>
 */
public final class FixtureThirdParty {

    /** 归因码（进 `[WRITE-REFUSED]` / `failureReason()` 口径的**稳定**字符串，别改字面量）。 */
    public static final String CODE = "third_party_claim";

    private FixtureThirdParty() {
    }

    /**
     * 逐区块查"第三方是否拦下方块编辑"。
     *
     * @return 命中的区块描述（空列表 = 这段范围没有第三方保护）
     */
    public static List<String> refusedChunks(ServerPlayer bot, BlockPos cornerA, BlockPos cornerB) {
        List<String> hits = new ArrayList<>();
        if (bot == null || cornerA == null || cornerB == null) {
            return hits;
        }
        int minX = Math.min(cornerA.getX(), cornerB.getX());
        int minY = Math.min(cornerA.getY(), cornerB.getY());
        int minZ = Math.min(cornerA.getZ(), cornerB.getZ());
        int maxX = Math.max(cornerA.getX(), cornerB.getX());
        int maxZ = Math.max(cornerA.getZ(), cornerB.getZ());
        for (int cx = minX >> 4; cx <= (maxX >> 4); cx++) {
            for (int cz = minZ >> 4; cz <= (maxZ >> 4); cz++) {
                BlockPos sample = new BlockPos(Math.max(minX, cx << 4), minY, Math.max(minZ, cz << 4));
                String reason = ThirdPartyProtection.refusalReason(bot, sample);
                if (reason != null) {
                    hits.add("chunk(" + cx + "," + cz + ")@" + sample.toShortString() + ":" + reason);
                }
            }
        }
        return hits;
    }

    /**
     * 前提求值（**副作用：命中时打一条响亮日志** —— 前提不成立这件事必须看得见，不许静默）。
     *
     * @return `null` = 前提成立（可以跑）；非 null = {@link #CODE}（**本步结论不作数**）
     */
    public static String refusal(ServerPlayer bot, BlockPos cornerA, BlockPos cornerB) {
        List<String> hits = refusedChunks(bot, cornerA, cornerB);
        if (hits.isEmpty()) {
            return null;
        }
        BotLog.warn("[Premise] 第三方保护覆盖本夹具范围 ⇒ **判据不成立**（这不是代码缺陷）："
                        + "{} 个区块被拦 {}（裁决来自 FTB 自己的函数；见 D-409）",
                hits.size(), hits);
        return CODE;
    }
}
