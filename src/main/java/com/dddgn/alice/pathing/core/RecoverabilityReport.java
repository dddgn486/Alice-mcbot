package com.dddgn.alice.pathing.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * **可回收性实测统计**（基-1 的"先测量"）。
 *
 * <p>统计点是**唯一转换点** {@code PlannedMovementSpecs.toSpec}（执行期每段一次）：
 * 记下"这条 Movement 被评估成什么等级、依据是什么"，于是"可回收性是不是常量"这个问题变成
 * **可核对的数据**，而不是读代码猜。
 *
 * <p>累计语义：自服务器启动累计（不随会话清空）；需要清零用 {@link #reset()}（自检入口会调）。
 */
public final class RecoverabilityReport {

    private static final Map<String, Integer> COUNTS = new LinkedHashMap<>();
    private static int total;

    // ==================== 第二条轴：**残留**（我方临时方块没收回 ⇒ 可回收性失败） ====================
    // 为什么算同一条不变式：可回收性 = "bot 干完活能干净地离开"。要么"每一步都有回程"（第一条轴），
    // 要么"自己改过的世界都收回来"（这条）。2026-09-12 实测到真实反例：一轮 pathing 夹具在基岩竖井里
    // 留了 4 块我方 cobblestone，事后回收 UNREACHABLE ⇒ 只报 `restore_partial` 是不够的，
    // 它其实是**可回收性**没达成。

    private static int residueBlocks;
    private static int residueEvents;
    private static String lastResidue = "-";

    private RecoverabilityReport() {
    }

    public static synchronized void record(MovementType type, RecoverabilityAssessment assessment) {
        total++;
        COUNTS.merge(type + "=" + assessment.describe(), 1, Integer::sum);
    }

    public static synchronized int total() {
        return total;
    }

    /** 出现过的**等级**数量（>1 ⇒ 可回收性不是常量，P0-B 的"死抽象"已被打破）。 */
    public static synchronized int distinctLevels() {
        return (int) COUNTS.keySet().stream()
                .map(key -> key.substring(key.indexOf('=') + 1, key.indexOf('/')))
                .distinct()
                .count();
    }

    public static synchronized String describe() {
        if (total == 0) {
            return "movements=0（尚未经过执行期转换点）";
        }
        return "movements=" + total + " distinctLevels=" + distinctLevels() + " " + COUNTS;
    }

    /** 记一次"残留"（活动结束仍有我方方块没收回）。 */
    public static synchronized void recordResidue(String scopeId, int blocks, String details) {
        if (blocks <= 0) {
            return;
        }
        residueBlocks += blocks;
        residueEvents++;
        lastResidue = (scopeId == null ? "<all>" : scopeId) + " blocks=" + blocks + " " + details;
    }

    public static synchronized int residueBlocks() {
        return residueBlocks;
    }

    public static synchronized int residueEvents() {
        return residueEvents;
    }

    public static synchronized String describeResidues() {
        return residueEvents == 0
                ? "residues=0（本进程内没有出现「我方方块未收回」）"
                : "residues=" + residueEvents + " blocks=" + residueBlocks + " last[" + lastResidue + "]";
    }

    public static synchronized void reset() {
        COUNTS.clear();
        total = 0;
        residueBlocks = 0;
        residueEvents = 0;
        lastResidue = "-";
    }
}
