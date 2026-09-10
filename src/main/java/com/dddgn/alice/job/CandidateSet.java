package com.dddgn.alice.job;

import java.util.List;

/**
 * 候选集合：**可行候选 + 被拒项的理由**。
 *
 * <p>被拒项必须机器可读（`docs/JOB_LAYER_DESIGN.md` §5.2 理由码表）——这是"拒绝必须带出口"的落地：
 * 无解时这些理由就是给玩家/未来 LLM 的情报，而不是一句"找不到目标"。
 */
public record CandidateSet(List<Candidate> viable, List<String> rejected) {

    public CandidateSet {
        viable = List.copyOf(viable);
        rejected = List.copyOf(rejected);
    }

    public static CandidateSet empty() {
        return new CandidateSet(List.of(), List.of());
    }

    public boolean isEmpty() {
        return viable.isEmpty();
    }
}
