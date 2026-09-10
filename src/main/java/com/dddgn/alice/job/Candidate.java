package com.dddgn.alice.job;

import net.minecraft.core.BlockPos;

import java.util.Map;

/**
 * 一个候选目标（L3 决策的输入单位）。
 *
 * @param anchor   候选锚点（伐木 = 树基座最下方原木）；决策与日志都以此为标识
 * @param label    人类可读标签（如 `tree`）
 * @param features 特征键值对（`d`/`logs`/`height`/`species`/`exposed`…）——**决策的可判读依据**
 */
public record Candidate(BlockPos anchor, String label, Map<String, String> features) {

    public Candidate {
        anchor = anchor.immutable();
        features = Map.copyOf(features);
    }

    public String feature(String key) {
        return features.getOrDefault(key, "-");
    }

    /** 决策日志里的候选标识：`tree@12,64,8`。 */
    public String id() {
        return label + "@" + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ();
    }
}
