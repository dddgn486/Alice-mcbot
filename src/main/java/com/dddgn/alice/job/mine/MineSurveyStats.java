package com.dddgn.alice.job.mine;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Locale;

/**
 * **真机地形实测的统计口径**（`D-360`，用户 2026-09-20：先走 A = 真实地形实测）。
 *
 * <p>⭐ 设计取舍：口径**先不拍死**，一次把**所有**口径都记下来（相对**起点**与相对**作业区中心**的
 * 向下占比、水平位移均值/最大值、Δy 直方图），这样"分母/向下定义"这些分歧可以**看着数据再定**，
 * 而不是先定口径再发现量错了东西。
 *
 * <p>**纯函数**（不读世界、只算给定坐标）⇒ 夹具能用合成数据逐条断言，真机数据也走同一段代码。
 *
 * <p>⚠️ 它统计的是**任务的决策行为**（选中/尝试了哪些格子），不是"世界被改了多少"——
 * 后者有独立账（`TaskMetrics` / `WriteAudit`），两者不许混（混了就无法判断"是偏置还是被拒"）。
 */
public final class MineSurveyStats {

    /**
     * 一次实测的快照。
     *
     * @param attempts      被尝试的目标数（= 选择次数）
     * @param successes     真的挖掉的格数
     * @param quota         目标配额
     * @param ticks         作业耗时
     * @param terminal      终态理由（`quota_met`/`partial_quota`/`world_refused`…）
     * @param downFromStart 相对**起点**向下（Δy&lt;0）的目标数
     * @param sameFromStart 与起点同层
     * @param upFromStart   相对起点向上（Δy&gt;0）
     * @param meanDeltaY    相对起点的平均 Δy（向下为负）
     * @param maxDrop       相对起点的最大下降深度（≥0）
     * @param meanHorizontal 到起点的平均水平距离
     * @param maxHorizontal 到起点的最大水平距离
     * @param distinctColumns 不同的水平列数（X/Z）——"是不是只在一个柱子上打转"的直接读数
     * @param failures      失败码 → 次数（`world_refused`/`tool_missing`/`target_replaced`…）
     */
    public record Snapshot(int attempts, int successes, int quota, int ticks, String terminal,
                           int downFromStart, int sameFromStart, int upFromStart,
                           double meanDeltaY, int maxDrop, double meanHorizontal, double maxHorizontal,
                           int distinctColumns, List<String> failures) {

        public Snapshot {
            failures = List.copyOf(failures);
        }

        public String describe() {
            return String.format(Locale.ROOT,
                    "attempts=%d/%d success=%d ticks=%d terminal=%s · Δy(相对起点) 下=%d 平=%d 上=%d"
                            + " 均值=%.1f 最大下降=%d · 水平 均值=%.1f 最大=%.1f 列数=%d · 失败=%s",
                    attempts, quota, successes, ticks, terminal,
                    downFromStart, sameFromStart, upFromStart, meanDeltaY, maxDrop,
                    meanHorizontal, maxHorizontal, distinctColumns,
                    failures.isEmpty() ? "无" : String.join(",", failures));
        }
    }

    private MineSurveyStats() {
    }

    /**
     * 汇总。
     *
     * @param start       作业起点（bot 起任务时的脚位）
     * @param order       被选中的目标序列（**决策行为**；顺序即尝试顺序）
     * @param successes   真挖掉的格数
     * @param quota       配额
     * @param ticks       耗时
     * @param terminal    终态理由
     * @param failedCodes 每次失败的理由码（与 `order` 不必等长：被拒/被替换也算失败）
     */
    public static Snapshot of(BlockPos start, List<BlockPos> order, int successes, int quota, int ticks,
                              String terminal, List<String> failedCodes) {
        int down = 0;
        int same = 0;
        int up = 0;
        long deltaSum = 0;
        int maxDrop = 0;
        double horizontalSum = 0.0D;
        double horizontalMax = 0.0D;
        java.util.Set<Long> columns = new java.util.LinkedHashSet<>();
        for (BlockPos pos : order) {
            int delta = pos.getY() - start.getY();
            deltaSum += delta;
            if (delta < 0) {
                down++;
                maxDrop = Math.max(maxDrop, -delta);
            } else if (delta == 0) {
                same++;
            } else {
                up++;
            }
            double horizontal = Math.sqrt(Math.pow(pos.getX() + 0.5D - (start.getX() + 0.5D), 2)
                    + Math.pow(pos.getZ() + 0.5D - (start.getZ() + 0.5D), 2));
            horizontalSum += horizontal;
            horizontalMax = Math.max(horizontalMax, horizontal);
            columns.add(((long) pos.getX() << 32) ^ (pos.getZ() & 0xFFFFFFFFL));
        }
        int attempts = order.size();
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String code : failedCodes) {
            counts.merge(code == null || code.isBlank() ? "unknown" : code, 1, Integer::sum);
        }
        List<String> failures = counts.entrySet().stream()
                .map(entry -> entry.getKey() + "×" + entry.getValue())
                .toList();
        return new Snapshot(attempts, successes, quota, ticks,
                terminal == null || terminal.isBlank() ? "-" : terminal,
                down, same, up,
                attempts == 0 ? 0.0D : (double) deltaSum / attempts, maxDrop,
                attempts == 0 ? 0.0D : horizontalSum / attempts, horizontalMax,
                columns.size(), failures);
    }

    /** 向下占比（0..1；`attempts==0` ⇒ 0）—— 用户最关心的那个数，单独给个函数免得每次现算。 */
    public static double downRatio(Snapshot snapshot) {
        return snapshot.attempts() == 0 ? 0.0D : (double) snapshot.downFromStart() / snapshot.attempts();
    }
}
