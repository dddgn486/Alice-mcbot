package com.dddgn.alice.job;

import net.minecraft.server.level.ServerPlayer;

/**
 * 选择策略（L3 决策缝之三）：**同一接口，可替换**。
 *
 * <p>v1 两个真实实现（`NearestPolicy` / `NearestExposedPolicy`）；将来 LLM 接同一个接口，
 * 用**同一套决策断言**验收——这是"LLM 可回归接入"的前提（`docs/JOB_LAYER_DESIGN.md` §6.3）。
 */
public interface SelectionPolicy {

    String name();

    Selection select(ServerPlayer bot, GoalSpec spec, CandidateSet candidates);
}
