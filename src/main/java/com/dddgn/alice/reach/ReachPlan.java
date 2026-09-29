package com.dddgn.alice.reach;

import com.dddgn.alice.pathing.core.search.CostModel;
import com.dddgn.alice.pathing.core.search.PathPlan;
import net.minecraft.core.BlockPos;

import java.util.Objects;

/**
 * 单个原始挖掘目标的规划快照：从规划起点前往挖掘站位的方案。
 * 不包含挖掘进度、任务生命周期、目标访问清障或掉落物拾取状态。
 *
 * <p>⭐ <b>2026-09-29 `1-5`：本类型原名 {@code MiningPlan}，本刀改名 {@code ReachPlan}</b>
 * （施工设计单 `§10` 的 `1-5`）—— 直接理由 = 与同包的 {@link ReachOutcome} 对齐：`1-3` 把到达腿的
 * 结果载体改成了 `Reach*` 家族，而计划本身还叫 `Mining*`，命名不一致是**已登记的偏离**
 * （台账 `O46` ③，复核触发就是本刀）。⚠️ 更硬的理由在 `survey/42 §3.3`「**锚在名字上的门禁会静默失效**」
 * —— 本刀实测抓到第一例：`tools/kernel-predicates.py` 的旧 `Mode` 检测写成
 * {@code r"\bMiningPlan\.Mode\b"}，**平凡的全局改名抓不到它**（`\b` 前是字面量 `b`）⇒
 * 若只做机械替换，那项检查会静默失效（该文件里有逐字的诚实登记）。
 * ⛔ 本刀**零行为改动**：只动名字（类型名 ＋ `BotManager.currentMiningPlan` → `currentReachPlan`）。
 *
 * <p>D-064：路径类型由 legacy `SurfacePathfinder.Result` 换成新内核 {@link PathPlan}（R3/R4）。
 *
 * <p>⭐ <b>2026-09-29 `1-3`（批次 1 改革 ① 主体 · 甲）「组件定型」：7 组件 → 6 组件</b>
 * —— 去掉的是 <b>规划期视线</b>（{@code LineOfSightResult visibility}），依据 = 施工设计单
 * `§4e` 甲（2026-09-29 用户裁定「**删规划期 LOS ＋ 退役 score 冗余载体**」）：
 * <ol>
 *   <li>它的**唯一**消费者是 `task/MineTask` 的一行遥测日志
 *       （`[MiningPlanner探针] … visibility=…`）⇒ **行为承重 = 零**；</li>
 *   <li>执行期 `action/MineBlockRunner` **自己在运行期**复核视线
 *       （`LINE_OF_SIGHT_BLOCKED` / `OUT_OF_REACH`，可重试）⇒ **从不读**这一份；</li>
 *   <li>⇒ 留在计划里等于让“规划那一刻的视线”被误当成**执行期事实**（`D-348` 同一条纪律：
 *       快照是历史，不是现在）。</li>
 * </ol>
 * ⚠️ 代价如实记：真机取证时少一个 `visibility=` 读数。
 *
 * <p>⭐ 同刀把“评分”从**载体**（旧的 `StandingPointEvaluator.StandingPointScore`）降级成
 * **本 record 的导出量** {@link #totalCost()}：那个 score 的四个字段里三个冗余
 * （`position` == {@link #standingFoot}、`lineOfSightResult` == 上面刚退役的 `visibility`、
 * `estimate` 全仓无读者）⇒ 只剩“成本”这一件真信息，而它按定义等于
 * `path.totalCost()` ＋（要垫支撑块时）那一次放置的计价。详见 {@link ReachOutcome}。
 */
public record ReachPlan(
        BlockPos target,
        BlockPos startFoot,
        BlockPos standingFoot,
        PathPlan path,
        Arrival arrival,
        BlockPos supportPlacementPos
) {
    /**
     * ⭐⭐ **"怎么到位" = 执行期写授权的唯一出处**（`D-520`，改革 ① 主体第一刀）。
     *
     * <p><b>它替谁</b>：替掉旧的 {@code ReachPlan.Mode}。旧枚举把**两件事压在一个字段里** ——
     * "规划模式记录"（诊断用）与"**这一趟走位能否改写世界**"（`D-076` 红线的最后一处接力）。
     * 只有后者是承重的：`action/MineBlockRunner` 在**运行时**靠 {@code mode} 值决定要不要把走位请求
     * 建成 {@code PathRequest.miningApproach}（含 {@code BREAK_*}/{@code PILLAR}）还是
     * {@code PathRequest.of}（纯通行）。
     *
     * <p><b>⚠️ 旧形状的陷阱（这就是本字段存在的理由）</b>：只要 {@code Mode} 被"收窄/换语义"
     * 而两个枚举值**没被删**，{@code plan.mode()} 就永远不再等于它们 ⇒ 三元恒走纯通行 ⇒
     * **破坏能力被静默拿掉**，而症状表现为"**站不到站位**"（{@code no_reachable_standing_point}）、
     * **不是**"权限被拒"；**编译器不报错**（枚举值还在）、**门禁也不报错**。
     * ⇒ 现在改成：**规划器在产生计划的那一行显式写死取值**，执行期**只读、零反推**
     * （`MineBlockRunner` 是穷尽 {@code switch}，新增取值即编译不过）。
     *
     * <p><b>⭐ 顺带修掉一处同族既有缺陷</b>：A 腿（`planDirect`）在 {@link ApproachCapability#PLACEMENT_ALLOWED}
     * 下用 {@code PathRequest.withPlacement} 规划（`D-443` 裁定 1a，鱼骨"补一块再走"），
     * 而执行期一律按 {@code of} 重走 ⇒ 规划期到得了、执行期到不了。分开 {@link #DIRECT_PURE_PASSAGE}
     * 与 {@link #DIRECT_PLACEMENT_ALLOWED} 之后，执行期能**逐字复现**规划期用的那个工厂。
     * 这正是 `D-443` 裁定 1a 那条"同一 tick 两个组件给相反答案"病灶的**执行侧那一半**（当时只治了规划侧）。
     *
     * <p>⚠️ 取值**不是**"授权本体"：真正的授权来源仍是**作业级声明**（`D-500` §IV，
     * 临时载体 {@code job/JobWriteDeclaration}）与 {@code MiningBudget} 闸门。本字段只保证
     * "规划期用了什么、执行期就用什么"，⛔ 不扩大也不缩小任何授权面。
     *
     * <p>⚠️ **刻意不叫 {@code Approach}**：`task.mining.MiningProfile.Approach` 已存在，
     * 而 `tools/check-duplicate-class-names.py` 是**有检查的门禁** ⇒ 不许造同名两物
     * （`GoalSpec` 的教训，设计讨论 `§12.1.2`）。
     *
     * <p>📌 **2026-09-29 更新（`D-527`，只加指针、上文原文不改）**：那个曾与它同名的嵌套枚举
     * **已经不存在了** —— `MiningProfile.Approach` 被提出来、落成 {@link ApproachCapability}
     * （`①-0`「拆信封」，为的是让「选」能搬进 `reach/`）。⇒ 上面那条"不许同名两物"的理由
     * **今天不再成立**；本枚举仍然叫 {@code Arrival}，因为改名是**另一件事**（锚在名字上的门禁会静默失效，
     * `survey/42 §3.3`），不在本刀范围内。
     */
    public enum Arrival {
        /** 当前站位即可挖掘（无走位）⇒ `PathRequest.of`。 */
        IN_PLACE,
        /** A 腿：纯通行走到现成可站的站位 ⇒ `PathRequest.of`（`D-076` 默认口径）。 */
        DIRECT_PURE_PASSAGE,
        /** A 腿：**只放不拆**地补一块再走 ⇒ `PathRequest.withPlacement`（`D-443` 裁定 1a）。 */
        DIRECT_PLACEMENT_ALLOWED,
        /**
         * 目标级一次搜索（内核 `GoalAdjacent`：站到目标格的某一面）⇒ `PathRequest.miningApproach`。
         *
         * <p>这是改革 ① 的产物：旧 `TUNNEL`（固定 13 格站位枚举 → 逐个全预算 A*）与
         * 旧 `ENTER_TARGET`（以目标格为终点破坏进入）**两条腿合成了这一条**。
         */
        MINING_APPROACH
    }

    public ReachPlan {
        target = Objects.requireNonNull(target, "target").immutable();
        startFoot = Objects.requireNonNull(startFoot, "startFoot").immutable();
        standingFoot = Objects.requireNonNull(standingFoot, "standingFoot").immutable();
        path = Objects.requireNonNull(path, "path");
        arrival = Objects.requireNonNull(arrival, "arrival");
        supportPlacementPos = supportPlacementPos == null ? null : supportPlacementPos.immutable();
        // ⭐ `D-520`：不变量收在**路径实际落点**上，而不是 `path.goalFoot()`。
        // 旧写法 `path.goalFoot().equals(standingFoot)` 只在"精确脚位目标"下才与落点重合
        // （`GoalFoot`）；换成谓词目标 `GoalAdjacent` 之后 `goalFoot()` 返回的是**目标方块**，
        // 于是旧不变量会把每一个合法计划都判成非法（抛 IAE）。新写法**更强**：
        // 它要求 `standingFoot` 是这条路径**真的走到**的那一格，而不是调用方"以为"的那一格。
        BlockPos landed = path.finalFoot();
        if (landed == null) {
            throw new IllegalArgumentException("ReachPlan requires a path that has a landing foot"
                    + " (status=" + path.status() + ")");
        }
        if (!landed.equals(standingFoot)) {
            throw new IllegalArgumentException("ReachPlan standingFoot must be the path's landing foot"
                    + " standingFoot=" + standingFoot.toShortString()
                    + " landed=" + landed.toShortString()
                    + " goalFoot=" + path.goalFoot().toShortString()
                    + " status=" + path.status());
        }
    }

    /** 仅表示规划快照满足正常直接挖掘的基础前置条件，不替代运行时验证。 */
    public boolean isExecutable() {
        return path.reached();
    }

    /**
     * 本计划的**选择成本**（走路格数口径）：路径成本 ＋ 若这一趟要垫支撑块则加那一次放置的计价。
     *
     * <p>⭐ 它是旧 `StandingPointEvaluator.StandingPointScore#getScore()` 的**唯一真信息**
     * （另三个字段全是冗余，见类注释）⇒ 从“载体里的一个字段”变成“计划自己的导出量”：
     * ⛔ **唯一出处**就在这里，⛔ 别在消费者侧再写一遍
     * （`PlanRefinedCostProvider` 与 A 腿的择优比较都读它）。
     *
     * <p>⚠️ 与旧 `score` 的**唯一差**：`Arrival.IN_PLACE` 且这一趟确实要垫支撑块时，
     * 旧值恒为 `0.0`（`selectDirect` 快路径那条构造点传的死值），而这里如实算上放置成本。
     * 该字段的读者只有日志、`PlanRefinedCostProvider`（排序用估算）与 A 腿的**同组内**择优
     * （同组 extraCost 相同 ⇒ 相减抵消）⇒ 不改变任何分支选择；`IN_PLACE` 也不参与
     * 跨组比较（`cheaper`）。已登记在 `docs/reviews/2026-09-29-内核改革-施工设计单.md` §14.5。
     */
    public double totalCost() {
        return path.totalCost()
                + (supportPlacementPos == null ? 0.0D : CostModel.PLACE_ONE_BLOCK_COST);
    }
}
