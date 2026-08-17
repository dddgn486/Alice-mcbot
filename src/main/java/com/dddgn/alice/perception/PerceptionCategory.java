package com.dddgn.alice.perception;

/**
 * 感知分类(设计文档 §3 上下文压缩的落地):把原始方块流按语义归类,喂给 AI 的是聚合摘要而非逐格列表。
 */
public enum PerceptionCategory {
    /** 任务目标(如挖矿视角的矿石)。最高优先级,列坐标。 */
    TARGET,
    /** 危险/障碍(岩浆、水、黑曜石、火等)。列坐标。 */
    DANGER,
    /** 意外收获(刷怪笼、宝箱、贵重方块等)。列坐标。 */
    TREASURE,
    /** 无关紧要、易挖掘的普通方块。仅聚合计数,不列坐标。 */
    COMMON
}
