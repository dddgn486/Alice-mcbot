package com.dddgn.alice.reach;

import com.dddgn.alice.pathing.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 挖掘站位候选生成（D-067 批次 2 重写）。
 *
 * <p>设计（`docs/MINING_STAND_SELECTION_DESIGN.md` v7）：
 * <ul>
 *   <li>**候选范围**：眼位可触及目标任一面的格子（`bot.getBlockReach()` 决定），不再是"水平最多 1 格"；</li>
 *   <li>**垂直规则**：y+1 / y / y−1 全水平展开；**y−2 … y−4 只允许正下方**（向上挖）；</li>
 *   <li>**只收"现成可站"**：无支撑 / 头脚空间不足的格子**不进候选**（A 腿不放置、不破坏）；
 *       ⚠️ 旧文这里写"归模式 B（批次 3）" —— 模式 B（`tunnelCandidates` ＋ 13 格枚举）**已于
 *       2026-09-29 `1-3` 删除**（`D-520` 已把那条腿整条换成内核的目标级搜索），见下方退役说明；</li>
 *   <li>**排除**：目标自身、目标正上方（挖掉自己支撑）、`target.above(2)`（脚下支撑必然挡视线，无效候选）；</li>
 *   <li>**硬前提（可挖掘面）**：从该站位的假设眼位能看到目标至少一个面（内缩多面体采样），
 *       且该可见采样点在触及距离内——"看得到但打不到"不算能挖（D-066）。</li>
 * </ul>
 *
 * <p>⚠️ <b>本类是 `reach/` 的**触及/站位几何原语**，不是"站位挖掘"本体</b>（`D-460` 的层定位）：
 * 它今天的活消费者有 `action/mining/MineBlockRunner` · `task/mining/BlockerClearPlanner` ·
 * `task/mining/MiningPlanner` · `job/lumber/LumberCandidateSource` ·
 * `task/MineTask.hasStandingCandidateNow` · `job/mine/StandingCostField` · `task/collecting/CollectStep`
 * ⇒ ⛔ **别按文件删它**（开工前侦察 `§14` 实测：那是"按文件删会立刻坏"的五条证据之一）。
 */
public final class StandingPointSelector {
    /** Bot 眼睛高度（脚底到眼睛）。 */
    public static final double BOT_EYE_HEIGHT = 1.62D;
    /** 正下方候选层数：y−2 … y−(1+BELOW_LEVELS)。 */
    private static final int BELOW_LEVELS = 3;

    /**
     * 候选站位：**脚位**。
     *
     * <p>⚠️ <b>2026-09-29 `1-3`（甲④）：原来的第二个组件（该站位的视线结果）已删</b> ——
     * 它的**唯一**读者是 A 腿里那张 `losByFoot` map，而那张 map 又只喂"评分载体"
     * （`§4e` 甲已退役 `StandingPointEvaluator`）与 `ReachPlan.visibility`（同刀退役）
     * ⇒ 零读者。⚠️ **视线过滤本身没变**：{@link #generateCandidates} 仍然**只收**
     * `isValidStandingPoint` 通过的格（判据照旧，只是不再把结果**带出来**）。
     */
    public record Candidate(BlockPos foot) {
    }

    private StandingPointSelector() {
    }

    /** 生成模式 A 候选（只含现成可站且能挖到的站位）。 */
    public static List<Candidate> generateCandidates(ServerLevel level, BlockPos target,
                                                     BlockPos botFoot, double reach) {
        List<Candidate> result = new ArrayList<>();
        int radius = (int) Math.ceil(reach) + 1;
        // y+1 / y / y−1：全水平展开
        for (int dy = 1; dy >= -1; dy--) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    addCandidate(level, target, target.offset(dx, dy, dz), reach, result);
                }
            }
        }
        // 正下方：深度按触及推导（与 tunnelCandidates 同口径 `k ≤ reach + 1.54`）。
        // 2026-09-10 修正：原固定 `y−2…y−(1+BELOW_LEVELS=3)` 只到下方 4 格，
        // 使"掏空树干后站在里面仰望上方原木底面"的伐木策略最多只支持 5 格树干；
        // 按触及推导后可达下方第 5 格（6 格树干），且新增候选仍必须通过 isValidStandingPoint
        // （可站 + 视线 + 触及），不会引入不合法站位。
        int belowDepth = Math.max(1 + BELOW_LEVELS, (int) Math.floor(reach + 1.54D));
        for (int k = 2; k <= belowDepth; k++) {
            addCandidate(level, target, target.below(k), reach, result);
        }
        return result;
    }

    /** Bot 当前站位是否已经"能挖到"（省去选位与寻路）。 */
    public static boolean isCurrentPositionGoodEnough(ServerLevel level, BlockPos target,
                                                      BlockPos currentPos, double reach) {
        return isValidStandingPoint(level, target, currentPos, reach) != null;
    }

    /** 站位有效性：可站 + 排除项 + 可挖掘面（可见面且在触及内）；返回视线结果，无效返回 null。 */
    public static LineOfSightChecker.LineOfSightResult isValidStandingPoint(ServerLevel level,
                                                                            BlockPos target,
                                                                            BlockPos pos, double reach) {
        if (pos.equals(target) || pos.equals(target.above())) {
            return null;
        }
        if (!isStandable(level, pos)) {
            return null;
        }
        Vec3 eye = eyeAt(pos);
        LineOfSightChecker.LineOfSightResult los = LineOfSightChecker.checkFromEye(level, eye, target);
        if (!los.isClear()) {
            return null;
        }
        // 保守余量：规划期假设眼位 vs 运行期真实眼位可差 ~0.4（D-070 修正）
        if (eye.distanceTo(los.getSuccessfulSample()) > reach - MiningTuning.reachMargin()) {
            return null;
        }
        return los;
    }

    /**
     * ⚠️ <b>2026-09-29 `1-3`（甲④）：`tunnelCandidates`（模式 B 的固定几何集 ＋ 竖井预算）已删。</b>
     *
     * <p><b>为什么删得掉</b>：它的**唯一**消费者是诊断夹具 `task/MineReachProbeTask`
     * （`§14.1` 实测：生产代码里零消费者）。而那个探针量的是 `D-520` 之前的形状
     * （"13 个候选各跑一次全预算 A\*"）—— 那条腿**已经被 `D-520` 整条替换**成
     * `GoalColumnBlocks` / `PathRequest.adjacentApproach` 两次顺序搜索
     * ⇒ 留着它 = 留着一份**死形状的第二出处**（正是 `rule_arrival_declared_and_consumed`
     * 要挡的复活面）。探针本身随同刀退休（`O45`），它的结论早已固化在
     * `docs/reviews/2026-09-21-B-深矿可达性判据实验.md`。
     * ⚠️ 这**取代**了 `AI_DECISIONS.md` 里 `OS-1` 那条"枚举留在 A"的旧裁定（本刀有用户裁定背书）。
     * ⛔ 别把 `generateCandidates` 也一起删 —— 现成可站候选有三个**活**生产消费者
     * （`task/MineTask.hasStandingCandidateNow` · `job/lumber/LumberCandidateSource` ·
     * `job/mine/StandingCostField`）。
     */

    /** 现成可站：脚下有支撑 + 脚位/头位可通行（K-4/D-167：委托内核唯一定义）。 */
    public static boolean isStandable(ServerLevel level, BlockPos pos) {
        return MovementHelper.canStandCentered(level, pos);
    }

    private static void addCandidate(ServerLevel level, BlockPos target, BlockPos pos, double reach,
                                     List<Candidate> out) {
        // ⚠️ `1-3`（甲④）：判据照旧跑（**只收**视线通过且在触及内的格），但结果不再被带出来 ——
        // 全仓已无读者（见 `Candidate` 的注释）。
        if (isValidStandingPoint(level, target, pos, reach) != null) {
            out.add(new Candidate(pos.immutable()));
        }
    }

    /** 站位假设眼位（与 {@link LineOfSightChecker#check} 的口径一致）。 */
    public static Vec3 eyeAt(BlockPos foot) {
        return foot.getCenter().add(0.0D, BOT_EYE_HEIGHT - 0.5D, 0.0D);
    }
}
