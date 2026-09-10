package com.dddgn.alice.action;

import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 世界写入审计（D-082）：把"谁授权、为什么、写了哪一格"记下来。
 *
 * <p>这是 J6（{@code WorldModLedger} + 建拆同权 + 恢复）的**唯一数据来源**。本轮只做内存记账 +
 * 日志，持久化与"建/拆配对"留给 J6，避免在没有消费方时先造存储。
 *
 * <p>与 {@code ScopeBuffer} 的分工：{@code ScopeBuffer} 通过 Forge 事件记录"世界实际发生了什么"
 * （含外部玩家造成的破坏），本类记录"Alice 授权自己做了什么"——两者在 J6 交叉校验。
 */
public final class WriteAudit {

    /** 环形缓冲上限；超出即丢弃最旧记录（只影响诊断可读性，不影响计数）。 */
    private static final int MAX_ENTRIES = 512;

    /** 单条写入记录。 */
    public record Entry(long tick, String action, BlockPos pos, String block, WriteGrant grant) {
        public String describe() {
            return action + " " + pos.toShortString() + " " + block + " by=" + grant.describe();
        }
    }

    private static final Deque<Entry> ENTRIES = new ArrayDeque<>();
    private static int breaks;
    private static int places;
    private static int unknownRequester;

    private WriteAudit() {
    }

    /** 登记一次破坏。 */
    public static void breakWrite(ServerLevel level, BlockPos pos, BlockState state, WriteGrant grant) {
        breaks++;
        record(new Entry(level.getGameTime(), "break", pos.immutable(),
                String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock())), grant));
    }

    /** 登记一次放置。 */
    public static void placeWrite(ServerLevel level, BlockPos pos, BlockState state, WriteGrant grant) {
        places++;
        record(new Entry(level.getGameTime(), "place", pos.immutable(),
                String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock())), grant));
    }

    private static void record(Entry entry) {
        if (WriteGrant.UNKNOWN.equals(entry.grant().requester())) {
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
