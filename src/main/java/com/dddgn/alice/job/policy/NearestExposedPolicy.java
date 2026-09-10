package com.dddgn.alice.job.policy;

import com.dddgn.alice.job.Candidate;
import com.dddgn.alice.job.CandidateSet;
import com.dddgn.alice.job.GoalSpec;
import com.dddgn.alice.job.Selection;
import com.dddgn.alice.job.SelectionPolicy;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 暴露优先 + 最近（v1 第二策略）：对应原始设计的 §4.2 标准 2「搜索必有排序，暴露方块优先」。
 *
 * <p>`暴露` 的定义由候选源给出（伐木：树顶原木到天空的垂直列无遮挡）。
 * 若没有暴露候选，则**如实回退**到最近并注明 `no_exposed`（不静默假装暴露）。
 */
public final class NearestExposedPolicy implements SelectionPolicy {

    @Override
    public String name() {
        return "nearest_exposed";
    }

    @Override
    public Selection select(ServerPlayer bot, GoalSpec spec, CandidateSet candidates) {
        List<String> rejected = new ArrayList<>(candidates.rejected());
        if (candidates.isEmpty()) {
            return Selection.none(rejected);
        }
        List<Candidate> exposed = new ArrayList<>();
        for (Candidate candidate : candidates.viable()) {
            if (Boolean.parseBoolean(candidate.feature("exposed"))) {
                exposed.add(candidate);
            } else {
                rejected.add(candidate.id() + ":not_exposed");
            }
        }
        boolean fallback = exposed.isEmpty();
        List<Candidate> pool = fallback ? new ArrayList<>(candidates.viable()) : exposed;
        pool.sort(Comparator.comparingDouble(c -> bot.distanceToSqr(
                c.anchor().getX() + 0.5D, c.anchor().getY() + 0.5D, c.anchor().getZ() + 0.5D)));
        Candidate picked = pool.get(0);
        if (fallback) {
            for (int i = 1; i < pool.size(); i++) {
                rejected.add(pool.get(i).id() + ":not_nearest");
            }
        }
        double distance = Math.sqrt(bot.distanceToSqr(
                picked.anchor().getX() + 0.5D, picked.anchor().getY() + 0.5D, picked.anchor().getZ() + 0.5D));
        return new Selection(picked, String.format(java.util.Locale.ROOT,
                "nearest_exposed d=%.1f exposed=%s%s",
                distance, picked.feature("exposed"), fallback ? " fallback=no_exposed" : ""), rejected);
    }
}
