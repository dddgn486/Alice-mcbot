package com.dddgn.alice.job;

import net.minecraft.server.level.ServerPlayer;

/**
 * 候选来源（L3 决策缝之一）：只回答"有哪些候选、哪些被拒为什么"，**不做选择**。
 *
 * <p>第一版真实实现：伐木 `TreeScanner` 驱动的 `LumberCandidateSource`；
 * 后续把既有的 `decision/AutoMineDecision` 迁移为第二个实现（切片 J5），避免它继续做不可复用的孤岛。
 */
public interface CandidateSource {

    /** 来源名（进决策日志）。 */
    String name();

    CandidateSet candidates(ServerPlayer bot, GoalSpec spec);
}
