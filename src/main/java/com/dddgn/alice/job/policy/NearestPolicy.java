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

/** 最近优先（v1 默认策略）：离 bot 最近的可达候选。 */
public final class NearestPolicy implements SelectionPolicy {

    @Override
    public String name() {
        return "nearest";
    }

    @Override
    public Selection select(ServerPlayer bot, GoalSpec spec, CandidateSet candidates) {
        List<String> rejected = new ArrayList<>(candidates.rejected());
        if (candidates.isEmpty()) {
            return Selection.none(rejected);
        }
        List<Candidate> sorted = new ArrayList<>(candidates.viable());
        sorted.sort(Comparator.comparingDouble(c -> bot.distanceToSqr(
                c.anchor().getX() + 0.5D, c.anchor().getY() + 0.5D, c.anchor().getZ() + 0.5D)));
        Candidate picked = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            rejected.add(sorted.get(i).id() + ":not_nearest");
        }
        double distance = Math.sqrt(bot.distanceToSqr(
                picked.anchor().getX() + 0.5D, picked.anchor().getY() + 0.5D, picked.anchor().getZ() + 0.5D));
        return new Selection(picked,
                String.format(java.util.Locale.ROOT, "nearest d=%.1f", distance), rejected);
    }
}
