package com.dddgn.alice.ledger;

import com.dddgn.alice.write.Attribution;
import com.dddgn.alice.write.WriteReason;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 世界写入审计（D-082）：把"谁授权、为什么、写了哪一格"记下来。
 *
 * <p>这是 J6（{@code WorldModLedger} + 建拆同权 + 恢复）的**唯一数据来源**。本轮只做内存记账 +
 * 日志，持久化与"建/拆配对"留给 J6，避免在没有消费方时先造存储。
 *
 * <p>与 {@code ScopeBuffer} 的分工：{@code ScopeBuffer} 通过 Forge 事件记录"世界实际发生了什么"
 * （含外部玩家造成的破坏），本类记录"Alice 授权自己做了什么"——两者在 J6 交叉校验。
 */
public final class ModifyAudit {

    /** 环形缓冲上限；超出即丢弃最旧记录（只影响诊断可读性，不影响计数）。 */
    private static final int MAX_ENTRIES = 512;

    /** 单条写入记录。 */
    public record Entry(long tick, String action, BlockPos pos, String block, Attribution grant) {
        public String describe() {
            return action + " " + pos.toShortString() + " " + block + " by=" + grant.describe();
        }
    }

    private static final Deque<Entry> ENTRIES = new ArrayDeque<>();
    private static int breaks;
    private static int places;
    private static int unknownRequester;

    private ModifyAudit() {
    }

    /** 登记一次破坏。 */
    public static void breakWrite(ServerLevel level, BlockPos pos, BlockState state, Attribution grant) {
        breaks++;
        record(new Entry(level.getGameTime(), "break", pos.immutable(),
                String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock())), grant));
    }

    /** 登记一次放置。 */
    public static void placeWrite(ServerLevel level, BlockPos pos, BlockState state, Attribution grant) {
        places++;
        record(new Entry(level.getGameTime(), "place", pos.immutable(),
                String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock())), grant));
    }

    private static void record(Entry entry) {
        if (Attribution.UNKNOWN.equals(entry.grant().requester())) {
            unknownRequester++;
        }
        synchronized (ENTRIES) {
            if (ENTRIES.size() >= MAX_ENTRIES) {
                ENTRIES.removeFirst();
            }
            ENTRIES.addLast(entry);
        }
        BotLog.info("[WRITE] {} tick={}", entry.describe(), entry.tick());
    }

    /** 破坏次数。 */
    public static int breaks() {
        return breaks;
    }

    /** 放置次数。 */
    public static int places() {
        return places;
    }

    /** 未声明授权身份的写入次数（**应为 0**；非 0 表示有写入点漏接授权）。 */
    public static int unknownRequesterWrites() {
        return unknownRequester;
    }

    // ==================== 授权留痕（"谁被授予了"） ====================

    /**
     * ⭐ **授权放行的留痕上限**（条）：同一格 + 同一理由**只留一次**，且总数有上限。
     *
     * <p>为什么必须去重（**客户端实测逼出来的**，2026-09-19）：`BlockBreakSafety` 不只被**动作层**调用，
     * 还被**规划期**的候选谓词反复调用（同一个 `pos`+`reason` 在 50 ms 内被问 5 次）⇒ 不去重的话
     * 一轮区域伐木会刷出成千上万行 `ALLOW`，把真日志淹掉。行为一条没改，改的只是**打印次数**。
     *
     * <p>2026-10-01：本块从 `protection/ZoneAuthority` 搬来（用户裁定「留痕跟 `ledger/ModifyAudit` 走」）
     * —— 它回答"**谁被授予了**"，与 {@link Entry}（"**实际写了什么**"）同族，故并到同一个审计面。
     */
    public static final int ALLOW_AUDIT_CAP = 512;

    private static final Set<String> ALLOW_KEYS = new LinkedHashSet<>();
    private static boolean allowSaturated;

    /**
     * 放行时留一行（同一格 + 同一理由只留一次；总数到 {@link #ALLOW_AUDIT_CAP} 后不再逐条打，只报一次饱和）。
     * 目的：事后能审计"**谁被授予了区内写入**"，同时不把日志刷成噪声。
     *
     * @param reason 声明式写入理由（`null` ⇒ 记 `-`）
     * @param detail 授权面的判定说明（`AreaPermission.Decision#detail`）
     */
    public static void logAllowGrant(BlockPos pos, WriteReason reason, String detail) {
        if (pos == null) {
            return;
        }
        String key = pos.asLong() + "|" + (reason == null ? "-" : reason.name());
        if (!ALLOW_KEYS.add(key)) {
            return;
        }
        if (ALLOW_KEYS.size() > ALLOW_AUDIT_CAP) {
            if (!allowSaturated) {
                allowSaturated = true;
                BotLog.info("[ModifyAudit] 审计留痕已达上限 {} 条 ⇒ 后续放行不再逐条打印"
                        + "（**闸门行为不变**，只是不再打印；真要逐次审计看 `[WRITE]` / 账本）", ALLOW_AUDIT_CAP);
            }
            return;
        }
        BotLog.info("[ModifyAudit] ALLOW pos={} reason={} {}（同一格+同一理由只留痕一次）",
                pos.toShortString(), reason == null ? "-" : reason.name(), detail);
    }

    /** 已留痕的 (格, 理由) 条数（夹具据此断言"重复问同一格不会重复刷日志"）。 */
    public static int allowLoggedCount() {
        return ALLOW_KEYS.size();
    }

    /** **夹具/收尾专用**：清空授权留痕去重表（**不影响任何授权判定**，也不动 {@link #ENTRIES}）。 */
    public static void clearAllowAudit() {
        ALLOW_KEYS.clear();
        allowSaturated = false;
    }

    /** 当前环形缓冲快照（旧 → 新）。 */
    public static List<Entry> snapshot() {
        synchronized (ENTRIES) {
            return new ArrayList<>(ENTRIES);
        }
    }

    /** 一行汇总，供夹具/自检输出 `SUMMARY`。 */
    public static String summary() {
        return "writes breaks=" + breaks + " places=" + places + " unknown=" + unknownRequester;
    }

    /** 测试夹具在每次自检开始前复位。 */
    public static void reset() {
        synchronized (ENTRIES) {
            ENTRIES.clear();
        }
        breaks = 0;
        places = 0;
        unknownRequester = 0;
    }
}
