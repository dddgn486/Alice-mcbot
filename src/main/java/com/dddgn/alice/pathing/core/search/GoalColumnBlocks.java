package com.dddgn.alice.pathing.core.search;

import net.minecraft.core.BlockPos;

import java.util.Objects;

/**
 * ⭐ **同列到达集**（批次 1 · `1-0`，`D-532` §二「乙」的**同列那一半**）：**站进目标那一列，
 * 脚位在目标层或它下面 `depth` 层以内** —— 到达判据**纯算术、不读世界**。
 *
 * <p><b>到达判据（逐字）</b>：{@code x == target.x && z == target.z && (target.y - depth) <= y <= target.y}
 * ⇒ 到达层 = **目标层向下含本层共 `depth + 1` 层**。`depth` 的两个已知实例：
 * <ul>
 *   <li>{@code depth == 1} ⇒ {@code {y, y-1}} —— **逐字**等于 Baritone `GoalTwoBlocks.isInGoal`
 *       （`reference/baritone-1.20.1/src/api/java/baritone/api/pathing/goals/GoalTwoBlocks.java:60`：
 *       {@code x == this.x && (y == this.y || y == this.y - 1) && z == this.z}）；</li>
 *   <li>{@code depth == 2} ⇒ {@code {y, y-1, y-2}} —— **逐字**等于 Baritone `GoalThreeBlocks.isInGoal`
 *       （`reference/baritone-1.20.1/src/main/java/baritone/process/MineProcess.java:310`，
 *       它是 `MineProcess` 的**嵌套类**，不在 `api/pathing/goals/` 下）。</li>
 * </ul>
 *
 * <p><b>它替谁</b>：替掉"**枚举 512 个站位候选**再逐个跑视线/成本"那条链
 * （`StandingPointSelector.generateCandidates`，退休中 ⇒ `1-3` 删除）。本目标把"站哪"交回搜索：
 * 到达集就是目标那一列，落脚点由 A\* 自己发现 ⇒ **零枚举、零规划期射线**。
 *
 * <p><b>对照 Baritone（`D-036`）</b>：
 * <ol>
 *   <li>Baritone 的挖掘到达集**永远在目标那一列** —— `MineProcess.coalesce` 全部七个返回分支
 *       （`reference/baritone-1.20.1/src/main/java/baritone/process/MineProcess.java:260-300`）都在 `loc` 列上，
 *       {@code GoalGetToBlock} 一类的**侧面**形状只出现在 `FarmProcess` / `BuilderProcess`；</li>
 *   <li>连它的"原地挖"快路径也只作用于自己那一列且只往上
 *       （`reference/baritone-1.20.1/src/main/java/baritone/process/MineProcess.java:117-121`）；</li>
 *   <li>⇒ 本类**不是自制替代内核**，而是 Baritone 形状的**参数化**。</li>
 * </ol>
 *
 * <p>⚠️ <b>唯一偏离（`D-036` 要求显式登记）</b>：<b>深度由"触及几何"推导，Baritone 是手写常量</b>。
 * Baritone 只有 {@code 1}（`GoalTwoBlocks`）与 {@code 2}（`GoalThreeBlocks`）两个手写档；
 * 本类把它推广成 {@code depth}，并给出口径明确的工厂 {@link #forReach} / {@link #maxDepthForReach}
 * （推导见那两处 javadoc）。<b>Alice 特有理由</b>：Alice 的原地挖**本来就不限同列**
 * （`action/MineBlockRunner.inPlaceReachable` 只要求"真眼位 + 裸触及 + 视线"，⛔ 无列约束）
 * ⇒ 形状若不跟着放宽，"规划说到得了、执行期够得着"这条**不变式**会在列方向上留缺口。
 * <b>为什么放宽是安全的</b>：深档不是"免费"，`CostModel` 对破坏与上升分别计价
 * （`BREAK_PENALTY_TICKS`、`ASCEND_COST`）⇒ 搜索**自然偏好最浅的可达档**；
 * 本类只负责把"够得着的那几档"**如实**写进到达集，取舍交给代价模型。
 *
 * <p><b>⭐ 为什么 {@link #exactFoot()} 返回 {@code true}（与 {@link GoalAdjacent} 相反，改动前必须读）</b>：
 * {@code AStarMovementSearch} 的第 1 道红线守卫是
 * {@code if (goal.exactFoot() && !context.chunkLoaded(goal.goalFoot()))} ——
 * 它**只预检 `goalFoot()` 所在的那一个区块**。本目标的**全部**候选脚位与目标
 * **共享 XZ** ⇒ **落在同一个区块**里 ⇒ 一次预检**覆盖整个到达集** ⇒
 * 这道守卫对本类是**有效且语义正确**的（{@link GoalNearXZ} / {@link GoalAdjacent} 正是"没有唯一
 * 目标区块可预检"才返回 `false`，见 `D-500` §V）。
 * ⭐ 机械后果：本类**不进** `tools/check-far-goal-usage.py` 的 `FAR_GOAL_REGISTRY`
 * （登记表的判据 = "`exactFoot() == false` 的实现必须登记"）。
 *
 * <p>⭐ <b>已接线</b>（批次 1 `1-1b₂`，2026-09-29）：**唯一生产消费者** =
 * `task/mining/MiningPlanner.planGoalApproach` 的**腿 1**（`GoalColumnBlocks.forReach(target, bot.getBlockReach())`），
 * 即「甲 · 顺序两次搜索」里**先问的那一条**；拿不出方案才轮到侧面形状
 * {@link GoalAdjacent}（同一把刀的腿 2）。
 * ⚠️ 本类落地时（`1-0a`）逐字写的是「今天不接线／生产消费者 0」—— **那句已随接线作废**，
 * 保留在此只为让读旧笔记的人看到同一条指针（台账 `O33`/`O38`）。
 *
 * @param target 目标方块（到达判据只认它与脚位的算术关系）
 * @param depth  **目标层以下**还允许算到达的层数（见类头的两个逐字实例）；必须 ≥ {@link #MIN_DEPTH}
 */
public record GoalColumnBlocks(BlockPos target, int depth) implements GoalSpec {

    /** 到达集的最小深度（= Baritone `GoalTwoBlocks`：「脚或头进那一格」）。 */
    public static final int MIN_DEPTH = 1;

    /**
     * 站姿眼高（`1.62`，与原版玩家一致）。
     *
     * <p>⚠️ **同值第二处** = `reach/StandingPointSelector.BOT_EYE_HEIGHT`（`reach/` 的退休集成员
     * ⇒ `1-3` 删它）⇒ 删完之后**本处成为唯一出处**；在那之前两者**必须同刀改**。
     */
    public static final double BOT_EYE_HEIGHT = 1.62D;

    public GoalColumnBlocks {
        target = Objects.requireNonNull(target, "target").immutable();
        if (depth < MIN_DEPTH) {
            throw new IllegalArgumentException("depth 必须 ≥ " + MIN_DEPTH + "（实测 " + depth + "）");
        }
    }

    /** 便捷构造：按**触及**推导深度（挖掘侧的标准口径）。 */
    public static GoalColumnBlocks forReach(BlockPos target, double reach) {
        return new GoalColumnBlocks(target, maxDepthForReach(reach));
    }

    /**
     * **触及几何推导的最大到达深度**（`K = floor(reach + 眼高 − 0.5)`，下界 {@link #MIN_DEPTH}）。
     *
     * <p><b>推导（纯几何，`target` 的中心 = `y + 0.5`）</b>：脚站在 `y − k` 时
     * 眼位 = {@code (y − k) + eyeHeight} ⇒ 眼到目标中心的**竖向**距离 = {@code k − (eyeHeight − 0.5)}；
     * 同列 ⇒ 水平距离 0 ⇒ 只要
     * {@code k − (eyeHeight − 0.5) <= reach} 就够得着 ⇒ {@code k <= reach + eyeHeight − 0.5}。
     * 实参 {@code reach = 4.5}`（原版 `getBlockReach()`）⇒ {@code 4.5 + 1.12 = 5.62} ⇒ **`K = 5`**。
     * ⚠️ 设计单 `§4a` 记的 `K ≈ 5`（用 `k − 1.1 ≤ reach`）与本式在 `reach = 4.5` 上**同解**；
     * 本式把 `1.1` 展开成 `eyeHeight − 0.5`，为的是**可审计**（常数有出处）。
     *
     * <p>⚠️ **只管"眼位到方块中心"这一条**，⛔ 不含：① 视线是否被中间层挡住（那是执行期
     * `LINE_OF_SIGHT_BLOCKED` 的事，可重试）；② 规划期余量（`MiningTuning.reachMargin` 那类）。
     * 调用方若要保守，自己**先减**再传进来。
     */
    public static int maxDepthForReach(double reach) {
        int derived = (int) Math.floor(reach + BOT_EYE_HEIGHT - 0.5D);
        return Math.max(MIN_DEPTH, derived);
    }

    /** 到达层数（= `depth + 1`，含目标层本身）。 */
    public int arrivalLevels() {
        return depth + 1;
    }

    @Override
    public boolean isInGoal(BlockPos foot) {
        return foot.getX() == target.getX()
                && foot.getZ() == target.getZ()
                && foot.getY() <= target.getY()
                && foot.getY() >= target.getY() - depth;
    }

    /**
     * 启发式 = **水平 octile（到目标 XZ）＋ 竖向"折叠"**。
     *
     * <p><b>竖向折叠（与 Baritone 同构）</b>：记 {@code up = target.y − pos.y}（正 = 脚位在目标**下方**）
     * <ul>
     *   <li>{@code 0 <= up <= depth} ⇒ **已在到达层内** ⇒ 竖向 **0**；</li>
     *   <li>{@code up > depth} ⇒ 还要**升** {@code up − depth} 层 ⇒ 计其**竖向增量**；</li>
     *   <li>{@code up < 0}（在目标**上方**）⇒ 至少要**降** {@code −up} 层 ⇒ 计最便宜的下降下界。</li>
     * </ul>
     * 对照 Baritone 的两式（把 {@code yDiff = pos.y − target.y} 折到"最近到达层"）：
     * {@code GoalTwoBlocks.heuristic} 逐字 {@code yDiff < 0 ? yDiff + 1 : yDiff}
     * （`reference/baritone-1.20.1/src/api/java/baritone/api/pathing/goals/GoalTwoBlocks.java:68`，即 `depth = 1`）·
     * `GoalThreeBlocks.heuristic` 逐字 {@code yDiff < -1 ? yDiff + 2 : yDiff == -1 ? 0 : yDiff}
     * （`reference/baritone-1.20.1/src/main/java/baritone/process/MineProcess.java:318`，即 `depth = 2`）
     * ⇒ ⭐ **本类的折叠是那两式的参数化**：`depth = 1` / `depth = 2` 时**逐一相等**（已用数值对照核过
     * `yDiff ∈ [−40, 40]`，0 处不匹配）。⚠️ 一处**有意的分歧**：脚位**比最深层还低**时 Baritone 的折叠
     * 会给出**负值**（例：`depth = 2`、`yDiff = −3` ⇒ `−1`），本类给的是**非负的"还需上升层数"**
     * （同例 ⇒ `1`）—— 负数在 `GoalYLevel` 里恰好抵掉一格水平成本，语义上是"白送"，
     * 本类不复制这个副作用。
     *
     * <p><b>可采纳性（如实读，⛔ 不写"已证明"）</b>：
     * <ul>
     *   <li>✅ **水平项**：octile 在 `CostModel` 单价下是**水平位移的精确最小成本**（不是近似下界）——
     *       公式**单一出处** = {@link GoalNearXZ#horizontalOctileToRadius}（半径取 0）；</li>
     *   <li>✅ **竖向项**：从下方够 {@code n} 层，任何路径至少要 {@code n} 次上升 ⇒
     *       真成本 ≥ `n × (ASCEND 的竖向增量)`；</li>
     *   <li>⚠️ **但增量取 `{@link CostModel#ASCEND_COST} − {@link CostModel#TRAVERSE_COST}`（0.67）**
     *       —— 这是 {@link GoalFoot} 的既有标定（"`ASCEND` 成本里含 1.0 的水平进度"）。
     *       严格下界在**斜向上升**合成下应是 `ASCEND − DIAGONAL` = 0.34
     *       （`SurfaceMovementProvider` 对"斜向 + 竖向"**发的是 `ASCEND`**，其单价与直线上升同为 1.67），
     *       ⇒ 本式在那种合成下**可能高 0.33/步**；</li>
     *   <li>⇒ 结论：**本类与 {@link GoalFoot} 同标定、同性质**，⛔ 不是"严格可采纳"的证明。
     *       实际上内核用的是**加权 A\***（`AStarMovementSearch.COEFFICIENTS = {1.5 … 10}`）⇒
     *       最优性本就不由严格可采纳性背书。⚠️ 该 0.33 抵扣除以属**既有未登记项**，
     *       已随本刀登记（见台账 `O33`）。</li>
     * </ul>
     */
    @Override
    public double heuristic(BlockPos pos) {
        double h = GoalNearXZ.horizontalOctileToRadius(
                pos.getX(), pos.getZ(), target.getX(), target.getZ(), 0);
        int up = target.getY() - pos.getY();
        if (up > depth) {
            h += (up - depth) * (CostModel.ASCEND_COST - CostModel.TRAVERSE_COST);
        } else if (up < 0) {
            // 下降下界取"最便宜的下降动作"（与 GoalFoot 逐字同式）
            h += (-up) * Math.min(CostModel.DESCEND_COST - CostModel.TRAVERSE_COST,
                    CostModel.DOWNWARD_COST);
        }
        return h;
    }

    /**
     * 目标方块本身（**记录用**，⛔ 不是"规范脚位"）：本目标有 `depth + 1` 个候选脚位。
     *
     * <p>它与 {@link #exactFoot()} 的 `true` **不冲突**：守卫只需要"一个能代表整个到达集
     * **所在区块**"的位置，而全部候选脚位与它**共享 XZ**（`PathPlan` 的落点一律取
     * `toFoot()`，⛔ 不读本方法 —— 见 `pathing/core/search/PathPlan` 的头注）。
     */
    @Override
    public BlockPos goalFoot() {
        return target;
    }

    /** ⛔ 见类头「为什么返回 {@code true}」—— 这个取值**有红线后果**，改动前必须读那一节。 */
    @Override
    public boolean exactFoot() {
        return true;
    }

    @Override
    public String describe() {
        return "同列到达集 target=" + target.toShortString()
                + " depth=" + depth + "（层数 " + arrivalLevels() + "，最深层 y=" + (target.getY() - depth) + "）";
    }
}
