package com.dddgn.alice.pathing.calc;

import net.minecraft.core.BlockPos;

import java.util.Objects;
import java.util.Set;

/**
 * ⭐ **相邻目标**（`K2` 第一刀 + `1a`=甲，`D-517`）：**站到目标方块的某一面即可** ——
 * 到达判据**纯算术、不读世界**。
 *
 * <p><b>它解决什么</b>：此前"走到被掩埋的目标旁边"被拆成
 * 「站位枚举（`StandingPointSelector`，固定 13 格）→ 逐个 top-K 全预算 `A*`（`MAX_APPROACH_PLANS = 3`）
 * → `ReachPlan.Mode.TUNNEL`」那一整条 `B` 分支。本目标把它收回内核一句话：
 * **"与目标格曼哈顿相邻，且不站在目标格、不站在它上方"** ⇒ 落脚点由 A* 自己找。
 *
 * <p><b>对照 Baritone</b>（`D-036`）：模板 = `BuilderProcess.GoalAdjacent extends GoalGetToBlock`
 * （`reference/baritone-1.20.1/src/main/java/baritone/process/BuilderProcess.java:892`）。
 * 到达判据 = 曼哈顿 ≤1（`GoalGetToBlock`）＋ **自带排除字段**。
 * ⚠️ Baritone 那个是 **`BuilderProcess` 的嵌套类**（**不在** `api/pathing/goals/` 下）。
 * Alice 把它落成**顶层 record**，排除项从单个 `no` 扩成**排除集**（`1a`=甲 的重试单位 = 换脚格）。
 *
 * <p>⭐⭐ <b>定性更正（批次 1 · `1-0b`，2026-09-29；`D-532` §二）—— 本类<u>不是</u>
 * "Baritone 挖掘的到达集"</b>：
 * <ul>
 *   <li>Baritone 的**挖掘**侧**从不含侧面格** —— `MineProcess.coalesce` 的全部七个返回分支都在目标那一列
 *       （`reference/baritone-1.20.1/src/main/java/baritone/process/MineProcess.java:260-300`），
 *       连"原地挖"快路径也只作用于自己那一列、且只往上（同文件 `:117-121`）；
 *       `GoalGetToBlock` / `GoalAdjacent` 只出现在 `FarmProcess` / `BuilderProcess`（农业与**建造**）；</li>
 *   <li>⇒ 本类是 **Alice 特有的侧面兜底形状**（按 `D-036` 必须**显式登记偏离**；
 *       `D-430` 已失效 ⇒ **登记即准入**）。<b>Alice 特有理由</b>：Alice 的原地挖本就不限同列
 *       （`action/MineBlockRunner.inPlaceReachable` 只要求"真眼位 ＋ 裸触及 ＋ 视线"，⛔ 无列约束）；</li>
 *   <li>⭐ <b>它在「乙」里的角色 = 第二条搜索</b>：先问**同列形状**（{@link GoalColumnBlocks}，
 *       含按触及深化的 `y−2 … y−K`），**它拿不出方案时**才问本形状 ⇒ 侧面视线由**执行期复核**
 *       （`action/MineBlockRunner` 的 `LINE_OF_SIGHT_BLOCKED` / `OUT_OF_REACH`，可重试）
 *       ⇒ 全程**零枚举、零规划期射线、最多 2 次搜索**；</li>
 *   <li>⚠️ 与同列形状**只共有 1 格**（{@code target.below()}）—— 兜底时那格已被第一条搜索否定，重叠无害。</li>
 * </ul>
 * ⚠️ <b>本处原文曾逐字写「新到达集 = Baritone 自己的 `GoalAdjacent`」</b>（`D-517` 时期）——
 * 那句把**建造**用的形状当成了**挖掘**的形状 ⇒ 已随 `D-532` 落地更正（台账 `O33`）。
 * ⛔ <b>不改名</b>：`GoalAdjacent` 描述的是**到达谓词**（曼哈顿 ≤1、不站目标格、不站上方），描述**准确**；
 * 改名只会动 `tools/check-far-goal-usage.py` 的登记键（"名字锚定的门禁会**静默**失效"，`D-529`/`D-530` 同族）
 * 而**不增加正确性** ⇒ 本项**偏离**设计单 `§4b` 的"改名"建议，理由与撤销条件登记在台账 `O33`（可供用户否决）。
 *
 * <p><b>到达判据（逐字，四条合取）</b>：
 * <ol>
 *   <li>{@code manhattan(foot, target) <= 1}；</li>
 *   <li>⛔ {@code !foot.equals(target)} —— 不许站进目标格；</li>
 *   <li>⛔ {@code foot.getY() <= target.getY()} —— **不许站到目标方块上方**。
 *       理由是 Baritone `BuilderProcess.GoalBreak` 逐字给过的：*"can't stand right on top of a block,
 *       that might not work (what if it's unsupported, can't break then)"* ⇒ 方块可能悬空，
 *       站上去反而挖不动；**下方与侧面允许**（那才是够得着的方向）。</li>
 *   <li>⛔ {@code !excluded.contains(foot)} —— 调用方给的排除集（见下）。</li>
 * </ol>
 * ⇒ 到达集合 = **4 个水平邻格 + 正下方那 1 格 = 最多 5 个脚位**（"上方"被第 3 条排掉）。
 *
 * <p><b>⭐ 为什么 {@link #exactFoot()} 返回 {@code false}（`A-5` 的契约级后果，改动前必须读）</b>：
 * <ul>
 *   <li><b>语义上</b>：到达集合有**最多 5 个脚位**、**没有唯一规范脚位** ⇒ 不是"钉死在一个脚位"。
 *       照 {@link GoalNearXZ} 的先例，{@link #goalFoot()} 只作**记录落点**
 *       （`PathPlan` / 日志 / 归因消息），**不参与到达判断**。</li>
 *   <li>⚠️ <b>机械上</b>：{@code exactFoot()} 是 `AStarMovementSearch` 那条
 *       `GOAL_NOT_LOADED` 前置守卫的**开关**
 *       （`if (goal.exactFoot() && !context.chunkLoaded(goal.goalFoot()))`）⇒
 *       返回 `false` = **关掉第 1 道防线**。</li>
 *   <li>✅ <b>为什么这样做是对的（不是图省事）</b>：本目标**没有唯一目标区块可预检**
 *       （`goalFoot()` 指的是一个**方块**，而候选脚位有 5 个），且 `isInGoal` **不读任何方块**
 *       ⇒ 那道守卫的立法目的（"别读未加载方块"）在这里**不存在**。
 *       ⭐ **真正的防线是第 2 道**（`MovementContext` 的**读脚印闸门**，`READ_FOOTPRINT_RADIUS`，
 *       在**读之前**判断）；第 3 道（`AStarMovementSearch` 扩展处的 `skippedUnloaded`）是
 *       **后置见证**，拦不住读。</li>
 *   <li>⚠️ **因此本实现必须出现在 `tools/check-far-goal-usage.sh` 的登记表里**
 *       （`D-500` §V ＋ `A-5`：判据 = `exactFoot() == false` 的实现**必须登记**，且登记表要**记录取值**）。</li>
 * </ul>
 *
 * <p>⚠️ <b>顺带登记一条设计缺陷（不是本类的错）</b>：`exactFoot()` 这个**名字**说的是
 * "钉死在一个脚位"（形状），而它的**机械作用**是"开关一道红线守卫"（后果）——
 * 两者已经**分家**。给它取名时只想到 {@link GoalFoot} 的形状，没预料到它会变成守卫开关。
 * ⇒ 后来者**不能**按名字直觉推断它的用途。见 `D-517`。
 *
 * <p><b>⭐ 排除集的时效纪律</b>（`§49.2` 第 3 条，`C-5` 同一条不变式）：
 * 排除集**只在一次规划调用内有效** —— ⛔ **不许跨 tick 累积成"永久拉黑"**
 * （"当时无解" ≠ "永远无解"）。调用方每次规划传一份**新的**集合，别持有它。
 *
 * @param target   目标方块（到达判据只认它与脚位的算术关系）
 * @param excluded 额外排除的脚位（**可为空集**）—— `1a`=甲 用它做"换脚格重试"，
 *                 也是"必须从某一面接近"这类约束的载体。⚠️ 构造时**拷贝成不可变集**，
 *                 且**不跨调用累积**（见上面的时效纪律）。排除集里放**目标格或上方**是无意义的
 *                 （前两条已经排除），不报错，但也不会改变结果。
 */
public record GoalAdjacent(BlockPos target, Set<BlockPos> excluded) implements GoalSpec {

    /** 到达判据的曼哈顿半径（Baritone `GoalGetToBlock` 同值）。 */
    public static final int ARRIVAL_MANHATTAN = 1;

    public GoalAdjacent {
        target = Objects.requireNonNull(target, "target").immutable();
        excluded = excluded == null ? Set.of() : Set.copyOf(excluded);
    }

    /** 便捷构造：无额外排除项。 */
    public static GoalAdjacent of(BlockPos target) {
        return new GoalAdjacent(target, Set.of());
    }

    /**
     * 便捷构造：排除**一个**脚位（`1a`=甲 的"这次换一格"）。⚠️ 每次规划都要**新传**一份，
     * 不要在原集合上追加（那会变成跨 tick 累积）。
     */
    public static GoalAdjacent excluding(BlockPos target, BlockPos no) {
        return new GoalAdjacent(target, Set.of(Objects.requireNonNull(no, "no").immutable()));
    }

    @Override
    public boolean isInGoal(BlockPos foot) {
        if (foot.equals(target)) {
            return false;                                   // ① 不许站进目标格
        }
        if (foot.getY() > target.getY()) {
            return false;                                   // ② 不许站在目标方块上方
        }
        if (excluded.contains(foot)) {
            return false;                                   // ③ 调用方的排除集
        }
        int d = Math.abs(foot.getX() - target.getX())
                + Math.abs(foot.getY() - target.getY())
                + Math.abs(foot.getZ() - target.getZ());
        return d <= ARRIVAL_MANHATTAN;                      // ④ 曼哈顿 ≤1
    }

    /**
     * 启发式 = **到"目标 XZ 的 Chebyshev 半径 1 邻域"的 octile 下界**（忽略竖向）。
     *
     * <p>可采纳性证明（`GoalSpec` 接口头逐字要求"不得高估"）：本目标的到达集**逐条枚举**是
     * "曼哈顿 ≤1 且不在目标格、不在上方、不在排除集"，它**是**集合
     * {@code {XZ 在 (tx,tz) 的 Chebyshev 1 内、任意 Y}} 的**子集**
     * ⇒ **到超集的距离 ≤ 到子集的距离** ⇒ 本启发式是**真剩余成本的下界** ✓。
     *
     * <p>⚠️ 竖向在"从下方够"时**本来就不需要上升**（正下方那格就是合法到达）⇒ 忽略竖向
     * 不会高估；需要下降时忽略竖向只会**低估** ⇒ 仍然可采纳。
     *
     * <p>公式**单一出处** = {@link GoalNearXZ#horizontalOctileToRadius}（与 `GoalNearXZ` 共用，
     * 避免两份公式漂移）。
     */
    @Override
    public double heuristic(BlockPos pos) {
        return GoalNearXZ.horizontalOctileToRadius(
                pos.getX(), pos.getZ(), target.getX(), target.getZ(), ARRIVAL_MANHATTAN);
    }

    /**
     * **记录落点** —— ⚠️ 返回的是**目标方块**，不是"规范脚位"（本目标没有唯一规范脚位）。
     * 它只进 `PathPlan` 记录 / 日志 / 归因消息，**不参与到达判断**（到达判断见 {@link #isInGoal}）。
     * 照 `GoalNearXZ.anchor` 的先例。
     */
    @Override
    public BlockPos goalFoot() {
        return target;
    }

    /** ⛔ 见类头「为什么返回 {@code false}」—— 这个取值**有红线后果**，改动前必须读那一节。 */
    @Override
    public boolean exactFoot() {
        return false;
    }

    @Override
    public String describe() {
        return "相邻目标 target=" + target.toShortString()
                + " manhattan<=" + ARRIVAL_MANHATTAN
                + (excluded.isEmpty() ? "" : " excluded=" + excluded.size() + "个");
    }
}
