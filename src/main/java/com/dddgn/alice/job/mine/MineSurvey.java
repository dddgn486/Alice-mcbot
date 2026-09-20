package com.dddgn.alice.job.mine;

import com.dddgn.alice.bot.ManualTestLock;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * **真机地形实测的采集钩子**（`D-360`）：手动测试起的那次挖掘，结束时打一行可复核的 `SUMMARY`。
 *
 * <p>为什么用"静态采集器 + 在 {@link MineJob} 的终态收口处打点"而不是包一层 Task：
 * ① `MineJob` 的终态路径有四条（配额达成 / 候选穷尽 / 背包满 / 超时），包一层很容易漏掉其中一条
 *    ⇒ 而"漏掉的那条不打印"正是实测里最致命的情形（看起来跑完了，其实没有数据）；
 * ② 工具发放、归属、事件环记录都走既有 `assignJob` 路径 ⇒ 手动实测与生产**同一条路**，
 *    唯一的差别就是那道锁（见 {@link ManualTestLock}）。
 *
 * <p>⚠️ 只在**手动实测**时打开（`/alice mine here`）；电池与生产路径 `active()==false`，零行为变化。
 */
public final class MineSurvey {

    private static boolean active;
    private static BlockPos startFoot;
    private static long startedAtTick;
    private static String label = "";
    /** 最近一次 SUMMARY（夹具/事后复核用：**判据不必去 grep 日志**，直接读快照）。 */
    private static MineSurveyStats.Snapshot lastSnapshot;

    private MineSurvey() {
    }

    /** 打开采集（手动测试入口调用）。 */
    public static synchronized void enable(BlockPos start, long tick, String what) {
        active = true;
        startFoot = start == null ? null : start.immutable();
        startedAtTick = tick;
        label = what == null ? "" : what;
        BotLog.info("[MineSurvey] 采集已开启（{}）：起点={} tick={}", label,
                startFoot == null ? "-" : startFoot.toShortString(), startedAtTick);
    }

    public static synchronized boolean active() {
        return active;
    }

    public static synchronized BlockPos startFoot() {
        return startFoot;
    }

    public static synchronized MineSurveyStats.Snapshot lastSnapshot() {
        return lastSnapshot;
    }

    public static synchronized void reset() {
        active = false;
        startFoot = null;
        startedAtTick = 0L;
        label = "";
    }

    /**
     * 作业终态收口：打 `SUMMARY` 并**解锁**（解锁放在这里，保证"任务怎么结束都会放锁"）。
     *
     * @param attempted 被选中的目标序列（决策行为）
     * @param failedCodes 每次失败的理由码
     * @param kindNote **种类分配**进度（`D-361`；空串 = 本轮没有种类分配）——单独一行打，
     *                 不改 `MineSurveyStats.Snapshot` 的字段（那份口径被夹具逐字咬着，不许悄悄加字段）
     */
    public static void reportTerminal(String jobName, BlockPos fallbackStart, List<BlockPos> attempted,
                                      int successes, int quota, int ticks, String terminal,
                                      List<String> failedCodes, String kindNote) {
        if (!active()) {
            return;
        }
        BlockPos start = startFoot != null ? startFoot : fallbackStart;
        MineSurveyStats.Snapshot snapshot = start == null
                ? null
                : MineSurveyStats.of(start, attempted, successes, quota, ticks, terminal, failedCodes);
        lastSnapshot = snapshot;
        if (snapshot == null) {
            BotLog.warn("[MineSurvey] ⚠️ 没有起点坐标 ⇒ 只能报原始读数：attempts={} success={} ticks={} terminal={}",
                    attempted.size(), successes, ticks, terminal);
        } else {
            BotLog.info("[MineSurvey] SUMMARY {} {} 向下占比={}（分母=被选中的目标数）",
                    jobName, snapshot.describe(),
                    String.format(java.util.Locale.ROOT, "%.2f", MineSurveyStats.downRatio(snapshot)));
            // 口径分歧留痕：起点口径与"**只看成功格**"口径都给出来（后者排除"试了但没挖到"的噪声）
            BotLog.info("[MineSurvey] 口径对照 起点={} 被选中={} 成功={}（向下占比的两种分母见上两行）",
                    start.toShortString(), attempted.size(), successes);
        }
        if (kindNote != null && !kindNote.isBlank()) {
            BotLog.info("[MineSurvey] 种类分配 {}", kindNote);
        }
        reset();
        ManualTestLock.off("作业已到终态（" + terminal + "）");
    }
}
