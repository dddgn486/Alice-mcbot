package com.dddgn.alice.job.lumber;

import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.job.Candidate;
import com.dddgn.alice.job.CandidateSet;
import com.dddgn.alice.job.CandidateSource;
import com.dddgn.alice.job.GoalSpec;
import com.dddgn.alice.protection.SafeZoneData;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.task.mining.LineOfSightChecker;
import com.dddgn.alice.task.mining.MiningTuning;
import net.minecraft.world.phys.Vec3;
import com.dddgn.alice.task.mining.MiningBudget;
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
    /** 单棵树允许的清障格数上限（`JOB_LAYER_DESIGN.md` §9-4：≤8 格/棵）。 */
    public static final int MAX_CLEAR_PER_TREE = 8;

    /** 候选评估期用的授权身份（真正授权在执行期由 LumberJob 声明；此处只做"是否会被允许"的预演）。 */
    private static final WriteGrant CLEAR_GRANT =
            WriteGrant.of("lumber-plan", WriteReason.LINE_OF_SIGHT);

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
            // 掏空后可以站进去的"柱底"格子：本树原木格，且其下方不是本树原木（= 真实地面）
            List<BlockPos> anchorStands = anchorStands(level, tree);
            int unreachable = 0;
            int deferred = 0;
            int softBlocked = 0;
            int noise = 0;
            int clearBlocks = 0;
            for (BlockPos log : tree.logsBottomUp()) {
                if (!StandingPointSelector.generateCandidates(level, log, MovementHelper.footCell(level, bot), reach).isEmpty()) {
                    continue;   // 现在就能看见
                }
                unreachable++;
                if (deferredReachable(level, tree, log, anchorStands, reach)) {
                    // 现在看不见，但**自下而上砍完后站进掏空的树干里仰望底面**即可：
                    // 底面不会被树叶遮（树叶在侧面/顶上），因此不需要清障。
                    deferred++;
                    continue;
                }
                // 限次清障方案（不区分软/硬方块，只靠预算兜底）
                int plan = BlockerClearPlanner.clearPlanCount(level, bot, log, reach,
                        MAX_CLEAR_PER_TREE - clearBlocks, CLEAR_GRANT);
                if (plan >= 0) {
                    clearBlocks += plan;
                    softBlocked++;
                } else {
                    noise++;
                }
            }
            if (noise == tree.logCount()) {
                rejected.add(id + ":no_stand");
                continue;
            }
            if (noise > 0) {
                // 硬遮挡（石头/建筑/超触及）：不允许清障，如实拒绝
                rejected.add(id + ":trunk_too_tall(unreachable=" + unreachable
                        + ",hard=" + noise + ")");
                continue;
            }
            // 全部"可见 或 仅被软遮挡（树叶等）" → 可行；执行期按预算清障（§5.4）
            Map<String, String> features = features(level, bot, tree, tree.logCount() - unreachable);
            if (deferred > 0) {
                features.put("deferred", Integer.toString(deferred));
            }
            if (softBlocked > 0) {
                features.put("clear", clearBlocks + "(" + softBlocked + "根)");
            }
            viable.add(new Candidate(tree.base(), "tree", features));
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

    /** 掏空后可以站进去的柱底格（本树原木格，且下方不是本树原木 → 是真实地面）。 */
    private static List<BlockPos> anchorStands(ServerLevel level, Tree tree) {
        List<BlockPos> stands = new ArrayList<>();
        for (BlockPos cell : tree.logs()) {
            if (tree.logs().contains(cell.below())) {
                continue;   // 下方是本树原木 → 掏空后没有支撑，站不住
            }
            if (MovementHelper.canWalkOn(level, cell.below())) {
                stands.add(cell);
            }
        }
        return stands;
    }

    /**
     * "先砍下方、再站进去仰望"是否可达（**几何判定，不改世界**）。
     *
     * <p>成立条件：存在一个柱底格（掏空后能站），眼位到该原木**最近面心**的距离在保守触及内，
     * **且目标至少比站位高 2 格**——否则头位就是目标本身，站不进去
     * （紧邻其上的那一根只能从**侧面**挖，树冠挡住侧面时就要走限次清障）。
     * 同列时中间格必然是本树原木（会被掏空），相邻列（2×2 树干）由"同层优先"的砍伐顺序保证先空出来；
     * 真正的视线是否通，**由运行期如实裁决**（挖不动就报 `partial_tree`，不在这里假装成功）。
     */
    private static boolean deferredReachable(ServerLevel level, Tree tree, BlockPos log,
                                             List<BlockPos> anchorStands, double reach) {
        double limit = reach - MiningTuning.reachMargin();
        for (BlockPos stand : anchorStands) {
            if (stand.equals(log)) {
                continue;
            }
            if (log.getY() < stand.getY() + 2) {
                continue;   // 头位 = 目标所在格 → 站不进去（第二格必须从侧面挖）
            }
            int dx = Math.abs(stand.getX() - log.getX());
            int dz = Math.abs(stand.getZ() - log.getZ());
            if (dx > 1 || dz > 1) {
                continue;   // 只对同列/相邻列成立
            }
            Vec3 eye = StandingPointSelector.eyeAt(stand);
            double best = Double.MAX_VALUE;
            for (Vec3 sample : LineOfSightChecker.samples(log)) {
                best = Math.min(best, eye.distanceTo(sample));
            }
            if (best <= limit) {
                return true;
            }
        }
        return false;
    }

    private static String id(Tree tree) {
        return "tree@" + tree.base().getX() + "," + tree.base().getY() + "," + tree.base().getZ();
    }

    /**
     * @param visibleLogs 当前就能看见（无需清障即可下手）的原木数
     */
    private static Map<String, String> features(ServerLevel level, ServerPlayer bot, Tree tree,
                                                int visibleLogs) {
        Map<String, String> features = new LinkedHashMap<>();
        features.put("d", String.format(java.util.Locale.ROOT, "%.1f", Math.sqrt(bot.distanceToSqr(
                tree.base().getX() + 0.5D, tree.base().getY() + 0.5D, tree.base().getZ() + 0.5D))));
        features.put("logs", Integer.toString(tree.logCount()));
        features.put("height", Integer.toString(tree.trunkHeight()));
        features.put("species", tree.species());
        features.put("canopy", Boolean.toString(tree.hasCanopy()));
        features.put("visible", Integer.toString(visibleLogs));
        // **exposed 重定义（D-092）**：原定义是"树顶正上方通天"，但**自然树几乎永不满足**——
        // 实测本场景 3/3 棵树的顶格上方都被树叶盖住（云杉的叶尖甚至高出顶格 2 格），
        // 于是 NearestExposedPolicy 每次都回退到"最近"，两策略永远给出相同选择，
        // §6.3「两策略必须给出不同且可解释的选择」根本无法成立。
        // 改为**对伐木真正有意义**的判据：至少有一根原木当前可见（无需清障即可下手）。
        // 旧定义不删，降级为独立特征 `open_sky`。
        features.put("exposed", Boolean.toString(visibleLogs > 0));
        features.put("open_sky", Boolean.toString(isExposedToSky(level, tree)));
        return features;
    }

    /** `open_sky` = 树顶原木往上的垂直列无遮挡（原 `exposed` 定义，D-092 起不再驱动策略）。 */
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
