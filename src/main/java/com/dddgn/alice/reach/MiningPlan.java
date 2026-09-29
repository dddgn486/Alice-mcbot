package com.dddgn.alice.reach;

import com.dddgn.alice.pathing.core.search.PathPlan;
import net.minecraft.core.BlockPos;

import java.util.Objects;

/**
 * 单个原始挖掘目标的规划快照：从规划起点前往挖掘站位的方案。
 * 不包含挖掘进度、任务生命周期、目标访问清障或掉落物拾取状态。
 *
 * <p>D-064：路径类型由 legacy `SurfacePathfinder.Result` 换成新内核 {@link PathPlan}（R3/R4）。
 */
public record MiningPlan(
        BlockPos target,
        BlockPos startFoot,
        BlockPos standingFoot,
        PathPlan path,
        LineOfSightChecker.LineOfSightResult visibility,
        Arrival arrival,
        BlockPos supportPlacementPos
) {
    /**
     * ⭐⭐ **"怎么到位" = 执行期写授权的唯一出处**（`D-520`，改革 ① 主体第一刀）。
     *
     * <p><b>它替谁</b>：替掉旧的 {@code MiningPlan.Mode}。旧枚举把**两件事压在一个字段里** ——
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
     * <p><b>⭐ 顺带修掉一处同族既有缺陷</b>：A 腿（`planDirect`）在 `MiningProfile.Approach.PLACEMENT_ALLOWED`
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
     * 而 `tools/check-duplicate-class-names.py` 是**有牙的门禁** ⇒ 不许造同名两物
     * （`GoalSpec` 的教训，设计讨论 `§12.1.2`）。
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

    public MiningPlan {
        target = Objects.requireNonNull(target, "target").immutable();
        startFoot = Objects.requireNonNull(startFoot, "startFoot").immutable();
        standingFoot = Objects.requireNonNull(standingFoot, "standingFoot").immutable();
        path = Objects.requireNonNull(path, "path");
        visibility = Objects.requireNonNull(visibility, "visibility");
        arrival = Objects.requireNonNull(arrival, "arrival");
        supportPlacementPos = supportPlacementPos == null ? null : supportPlacementPos.immutable();
        // ⭐ `D-520`：不变量收在**路径实际落点**上，而不是 `path.goalFoot()`。
        // 旧写法 `path.goalFoot().equals(standingFoot)` 只在"精确脚位目标"下才与落点重合
        // （`GoalFoot`）；换成谓词目标 `GoalAdjacent` 之后 `goalFoot()` 返回的是**目标方块**，
        // 于是旧不变量会把每一个合法计划都判成非法（抛 IAE）。新写法**更强**：
        // 它要求 `standingFoot` 是这条路径**真的走到**的那一格，而不是调用方"以为"的那一格。
        BlockPos landed = path.finalFoot();
        if (landed == null) {
            throw new IllegalArgumentException("MiningPlan requires a path that has a landing foot"
                    + " (status=" + path.status() + ")");
        }
        if (!landed.equals(standingFoot)) {
            throw new IllegalArgumentException("MiningPlan standingFoot must be the path's landing foot"
                    + " standingFoot=" + standingFoot.toShortString()
                    + " landed=" + landed.toShortString()
                    + " goalFoot=" + path.goalFoot().toShortString()
                    + " status=" + path.status());
        }
    }

    /** 仅表示规划快照满足正常直接挖掘的基础前置条件，不替代运行时验证。 */
    public boolean isExecutable() {
        return path.reached() && visibility.isClear();
    }
}
