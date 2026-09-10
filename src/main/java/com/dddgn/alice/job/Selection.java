package com.dddgn.alice.job;

import java.util.List;

/**
 * 选择结果（L3 决策缝之二）。
 *
 * @param picked   选中项；null = 全部被拒（此时 {@code rejected} 必须解释原因）
 * @param reason   选中理由（如 `nearest d=3.2`）
 * @param rejected 被拒项：候选源的理由 + 本策略的排序理由（如 `tree@…:not_nearest`）
 */
public record Selection(Candidate picked, String reason, List<String> rejected) {

    public Selection {
        rejected = List.copyOf(rejected);
    }

    public static Selection none(List<String> rejected) {
        return new Selection(null, "none", rejected);
    }
}
