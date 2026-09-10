package com.dddgn.alice.job.lumber;

import com.dddgn.alice.job.Candidate;
import com.dddgn.alice.job.CandidateSet;
import com.dddgn.alice.job.CandidateSource;
import com.dddgn.alice.job.GoalSpec;
import com.dddgn.alice.protection.SafeZoneData;
import com.dddgn.alice.task.mining.StandingPointSelector;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 伐木候选源：扫描半径内的树 → **可行性判定** → 可行候选 + 被拒理由（`JOB_LAYER_DESIGN.md` §5.2）。
 *
 * <p>关键纪律：可行性判据**复用生产规划器的候选生成器**
 * （{@code StandingPointSelector.generateCandidates}）——"候选源说能挖"与"规划器能规划"必须是同一口径，
 * 否则决策层会承诺一件做不到的事。
 *
 * <p>实例按 Job 持有、主线程同步调用；每次 {@link #candidates} 刷新 {@link #lastScan}，
 * 供 Job 用锚点取回 {@link Tree} 本体（{@link #treeAt}）。
 */
public final class LumberCandidateSource implements CandidateSource {

    /** 暴露度判定：树顶原木往上的垂直净空高度。 */
    private static final int SKY_CHECK_HEIGHT = 8;

    private List<Tree> lastScan = List.of();

    @Override
    public String name() {
        return "trees";
    }

    @Override
    public CandidateSet candidates(ServerPlayer bot, GoalSpec spec) {
        ServerLevel level = bot.serverLevel();
        lastScan = TreeScanner.scan(level, spec.center(), spec.radius());
        double reach = bot.getBlockReach();
        List<Candidate> viable = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (Tree tree : lastScan) {
            String id = id(tree);
            if (tree.tooLarge()) {
                rejected.add(id + ":too_large");
                continue;
            }
            if (SafeZoneData.get(level.getServer()).protectionReason(level, tree.base()) != null) {
                rejected.add(id + ":protected");
                continue;
            }
            int unreachable = 0;
            for (BlockPos log : tree.logsBottomUp()) {
                if (StandingPointSelector.generateCandidates(level, log, bot.blockPosition(), reach).isEmpty()) {
                    unreachable++;
                }
            }
            if (unreachable == tree.logCount()) {
                rejected.add(id + ":no_stand");
                continue;
            }
            if (unreachable > 0) {
                rejected.add(id + ":trunk_too_tall(unreachable=" + unreachable + ")");
                continue;
            }
            viable.add(new Candidate(tree.base(), "tree", features(level, bot, tree)));
        }
        return new CandidateSet(viable, rejected);
    }

    /** 取回某锚点对应的树（仅对上一次 {@link #candidates} 的扫描结果有效）。 */
    public Tree treeAt(BlockPos anchor) {
        for (Tree tree : lastScan) {
            if (tree.base().equals(anchor)) {
                return tree;
            }
        }
        return null;
    }

    private static String id(Tree tree) {
        return "tree@" + tree.base().getX() + "," + tree.base().getY() + "," + tree.base().getZ();
    }

    private static Map<String, String> features(ServerLevel level, ServerPlayer bot, Tree tree) {
        Map<String, String> features = new LinkedHashMap<>();
        features.put("d", String.format(java.util.Locale.ROOT, "%.1f", Math.sqrt(bot.distanceToSqr(
                tree.base().getX() + 0.5D, tree.base().getY() + 0.5D, tree.base().getZ() + 0.5D))));
        features.put("logs", Integer.toString(tree.logCount()));
        features.put("height", Integer.toString(tree.trunkHeight()));
        features.put("species", tree.species());
        features.put("canopy", Boolean.toString(tree.hasCanopy()));
        features.put("exposed", Boolean.toString(isExposedToSky(level, tree)));
        return features;
    }

    /** 暴露 = 树顶原木往上的垂直列无遮挡（用于"暴露优先"策略；定义写死以免两处口径不一）。 */
    private static boolean isExposedToSky(ServerLevel level, Tree tree) {
        BlockPos top = tree.top();
        for (int k = 1; k <= SKY_CHECK_HEIGHT; k++) {
            if (!level.getBlockState(top.above(k)).isAir()) {
                return false;
            }
        }
        return true;
    }
}
