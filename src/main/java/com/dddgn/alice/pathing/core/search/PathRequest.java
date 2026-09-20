package com.dddgn.alice.pathing.core.search;

import com.dddgn.alice.pathing.core.MovementType;
import net.minecraft.core.BlockPos;

import java.util.Objects;
import java.util.Set;

/**
 * 路径规划请求（架构文档 §5.1）。
 *
 * <p>显式携带策略与预算，避免"含义不清的 BlockPos target"。
 * 坐标语义统一为**脚位（foot）**。
 */
public record PathRequest(
        String botId,
        BlockPos startFoot,
        GoalSpec goal,
        Set<MovementType> allowedMovementTypes,
        SearchBudget budget,
        String requester
) {
    public PathRequest {
        botId = Objects.requireNonNull(botId, "botId");
        // D-241：唯一构造点 ⇒ 顺手记下"本任务的信封里有没有写世界的权利"（推导事实，不维护名单）。
        com.dddgn.alice.pathing.core.WriteEnvelopes.note(botId, allowedMovementTypes);
        startFoot = Objects.requireNonNull(startFoot, "startFoot").immutable();
        goal = Objects.requireNonNull(goal, "goal");
        allowedMovementTypes = Set.copyOf(Objects.requireNonNull(allowedMovementTypes, "allowedMovementTypes"));
        budget = budget == null ? WALK_BUDGET : budget;
        requester = requester == null ? "unknown" : requester;
        if (allowedMovementTypes.isEmpty()) {
            throw new IllegalArgumentException("allowedMovementTypes must not be empty");
        }
    }

    /** 逃生请求的搜索预算：**刻意有界**（慌乱中不许烧满整个搜索空间）。 */
    private static final int ESCAPE_MAX_NODES = 4_000;
    private static final long ESCAPE_MAX_MILLIS = 100L;

    /**
     * **行走类请求的搜索预算（D-268④，2026-09-17 用户裁定）**：**不再是无界**。
     *
     * <p>现状问题：`PathRequest.of` 的五个调用点原先一律传 {@link SearchBudget#UNLIMITED}
     * ⇒ **规划器自己的天花板被绕过** ⇒ 行走可以烧满整个搜索空间（勘测 10 §2.3 的"真正无界的是行走类"）。
     * 裁定 = 撞限**如实报 `SEARCH_LIMIT`**（不静默、不假装不可达），初值取**保守**
     * （= {@link CorePathPlanner#DEFAULT_MAX_NODES} / {@link CorePathPlanner#DEFAULT_MAX_MILLIS}），
     * 并靠 `[PathRetry] plan … nodes/moved/ms` 这行日志**先观测真实分布再收紧**。
     *
     * <p>⚠️ 逃生仍用更紧的 {@code ESCAPE_*}（4000 节点 / 100 ms）—— 刻意更小。
     */
    public static final SearchBudget WALK_BUDGET = SearchBudget.of(
            CorePathPlanner.DEFAULT_MAX_NODES, CorePathPlanner.DEFAULT_MAX_MILLIS);

    /**
     * **维生自救的受限写授权**（D-241；用户 2026-09-16 定案 Q1–Q4）。
     *
     * <p>允许：纯通行 + `PLACE_STEP_AND_TRAVERSE`（搭桥/放台阶）+ `PILLAR`（垫柱子/爬竖井）
     * + `BREAK_AND_TRAVERSE`/`BREAK_AND_ENTER`（朝侧壁破开一条路）。
     * **刻意不允许** `DOWNWARD`（向下挖会把自己送进更深的坑或岩浆 —— 逃生的目的是离开危险）
     * 与 `FALL`（本入口不负责下落；跌落另有 D-058 的无水落地口径）。
     *
     * <p>**调用点必须持有写信封**：上层用 `WriteEnvelopes.had(botId)` 判断"这个任务本来就改世界吗"，
     * 只有"是"才允许用本入口（这就是 D-076 红线的**受控口子**：显式登记 + 专属理由码 + 预算上限 + 账本）。
     */
    public static PathRequest survivalEscape(String botId, BlockPos startFoot, BlockPos goalFoot,
                                             String requester) {
        return new PathRequest(botId, startFoot, new GoalFoot(goalFoot),
                Set.of(MovementType.TRAVERSE, MovementType.DIAGONAL, MovementType.ASCEND,
                        MovementType.DESCEND, MovementType.PLACE_STEP_AND_TRAVERSE, MovementType.PILLAR,
                        MovementType.BREAK_AND_TRAVERSE, MovementType.BREAK_AND_ENTER),
                SearchBudget.of(ESCAPE_MAX_NODES, ESCAPE_MAX_MILLIS), requester);
    }

    public static PathRequest of(String botId, BlockPos startFoot, BlockPos goalFoot, String requester) {
        return new PathRequest(botId, startFoot, new GoalFoot(goalFoot),
                Set.of(MovementType.TRAVERSE, MovementType.DIAGONAL,
                        MovementType.ASCEND, MovementType.DESCEND),
                WALK_BUDGET, requester);
    }

    /** 纯通行 + 世界修改（PATH_ACCESS 破坏 + TEMPORARY_SUPPORT 放置，R5-2/R5-3）。 */
    public static PathRequest withWorldModification(String botId, BlockPos startFoot, BlockPos goalFoot,
                                                     String requester) {
        return new PathRequest(botId, startFoot, new GoalFoot(goalFoot),
                Set.of(MovementType.TRAVERSE, MovementType.DIAGONAL, MovementType.ASCEND,
                        MovementType.DESCEND, MovementType.DOWNWARD, MovementType.PILLAR,
                        MovementType.FALL,
                        MovementType.BREAK_AND_TRAVERSE,
                        MovementType.BREAK_AND_ENTER,
                        MovementType.PLACE_STEP_AND_TRAVERSE),
                WALK_BUDGET, requester);
    }

    /**
     * 挖掘到达请求（D-067 ㉘）：允许破坏进入 / 破坏通行 / 放置台阶。
     *
     * <p>⭐ **D-366b（2026-09-20 用户裁定，临时让步）**：**取消**原先对 `PILLAR` / `FALL` / `DOWNWARD`
     * 的显式禁用（原理由："避免挖矿时先搭柱子/跳下来这类奇技"）。用户原话：
     * 「因为现在问题很多，**先取消挖矿的 Movement 禁用，能用之后再调整风险管理策略**」。
     * <ul>
     *   <li><b>为什么</b>：禁用垂直能力后，目标在下方时路径只能"跑到很远的同层可站点，再水平挖过去"
     *       （真机实测：目标离 bot 只有 2 格，却给出 11 格隧道；本轮 64 次破坏里 56 次是挖路）
     *       ⇒ 用户最在意的"绕远 / 来回折返"直接来自这里；</li>
     *   <li><b>让步范围**仅限**本工厂</b>：`of`（纯通行）仍不含任何写原语；`scaffoldRemoval` 仍"只拆不建"；
     *       破坏/放置仍走 `MiningBudget`/`WriteBudget` 闸门（授权面没有放松，放松的是**路线能力**）；</li>
     *   <li><b>回收条件</b>：等"能用"之后按用户口径**重新引入风险管理策略**（届时按风险/下落高度/危险方块
     *       给这些边加条件，而不是一刀切禁用）。见 `docs/AI_DECISIONS.md` `D-366`。</li>
     * </ul>
     */
    public static PathRequest miningApproach(String botId, BlockPos startFoot, BlockPos goalFoot,
                                             String requester) {
        return new PathRequest(botId, startFoot, new GoalFoot(goalFoot),
                Set.of(MovementType.TRAVERSE, MovementType.DIAGONAL, MovementType.ASCEND,
                        MovementType.DESCEND, MovementType.PILLAR, MovementType.FALL,
                        MovementType.DOWNWARD, MovementType.BREAK_AND_TRAVERSE,
                        MovementType.BREAK_AND_ENTER, MovementType.PLACE_STEP_AND_TRAVERSE),
                WALK_BUDGET, requester);
    }

    /**
     * 脚手架回收请求（J6-b）：**只拆不建、且只允许向下拆脚下那一格**。
     *
     * <p>为什么需要它：拆自己放的垫脚柱/台阶时，目标常常就是**脚下那格**——
     * 而 `MineTask` 的模式 A 显式排除 `target.above()` 作为站位（那是 DOWNWARD 的语义），
     * 于是"用 MineTask 拆脚下"**必然规划失败**（实测：4 块全部 `no_valid/no_reachable_standing_point`）。
     * 设计文档早在 D-081 §12.3 写明正确机制：**挖脚下 → `DOWNWARD` 落 1 格 → 重复**。
     *
     * <p>允许集合刻意**不含** `PILLAR` / `PLACE_STEP_AND_TRAVERSE`（回收阶段不许再建），
     * 也不含 `BREAK_AND_*`（不许沿途挖穿地形）；只允许通行 + `DOWNWARD` + `FALL`。
     * 这是 D-076 意义上**新增的显式授权入口**，已在 `docs/WORLD_WRITE_AUTHORIZATION.md` 登记。
     */
    public static PathRequest scaffoldRemoval(String botId, BlockPos startFoot, BlockPos goalFoot) {
        return new PathRequest(botId, startFoot, new GoalFoot(goalFoot),
                Set.of(MovementType.TRAVERSE, MovementType.DIAGONAL, MovementType.ASCEND,
                        MovementType.DESCEND, MovementType.DOWNWARD, MovementType.FALL),
                WALK_BUDGET, "restore");
    }

    /**
     * 攀爬到达请求（J7 Step 1，§11-① 要素①）：**显式授权入口**，允许 `PILLAR`（跳跃中在脚下放方块）。
     *
     * <p>为什么必须单列一个入口：D-076 规定寻路默认纯通行，放置类能力只能由上层**显式授权**；
     * 为挖掘站位服务的 {@link #miningApproach} 显式禁用 `PILLAR/FALL/DOWNWARD`（D-067 ㉘），
     * 因此"搭着方块爬上去"在今天**没有任何合法入口**——本工厂就是那个入口，**只由 Job/任务显式开启**，
     * 不进任何默认集合。已登记：`docs/WORLD_WRITE_AUTHORIZATION.md` A10。
     *
     * <p>允许集刻意**不含** `FALL` / `DOWNWARD` / `BREAK_*`：爬上去的途中不许破坏，也不许跳下去
     * （下来属于**拆除阶段**，走 {@link #scaffoldRemoval}）。
     */
    public static PathRequest climbApproach(String botId, BlockPos startFoot, BlockPos goalFoot,
                                            String requester) {
        return new PathRequest(botId, startFoot, new GoalFoot(goalFoot),
                Set.of(MovementType.TRAVERSE, MovementType.DIAGONAL, MovementType.ASCEND,
                        MovementType.DESCEND, MovementType.PILLAR),
                WALK_BUDGET, requester);
    }

    public boolean allows(MovementType type) {
        return allowedMovementTypes.contains(type);
    }

    /**
     * 本次尝试**降级为纯通行**（D-106 Slice B，用户 2026-09-11 裁定）。
     *
     * <p>什么算纯通行：不改世界的 Movement —— `TRAVERSE/DIAGONAL/ASCEND/DESCEND/FALL`。
     * 与 {@link #of} 相比保留 `FALL`（它同样不写世界，且排除它会让本来就该走的下落路线消失）；
     * 与之相比**去掉** `PILLAR`（放置）/`DOWNWARD`（破坏）/`BREAK_*`/`PLACE_STEP_*`。
     *
     * <p>目标、预算、归因不变：降级只改"允许的动作集合"，不改"要去哪"。
     */
    /** 只换搜索预算（D-238：维生出口预检用一个**小**预算跑一次真规划，别用无限预算）。 */
    public PathRequest withBudget(SearchBudget newBudget) {
        return new PathRequest(botId, startFoot, goal, allowedMovementTypes, newBudget, requester);
    }

    public PathRequest pureTraversal() {
        // **故意写成字面集合**（而不是从 `MovementType.changesWorld()` 过滤派生）：集合的迭代顺序未定义，
        // 派生会悄悄改掉顺序 ⇒ 规划/成本选择可能随之变化（实测：CORE 里一条与它无关的火焰伤害判据因此翻红）。
        // "两份名单会漂移"由**门禁**兜住：`WritePolicyCheckTask` 断言两边一致（写错就红，见 D-241）。
        return new PathRequest(botId, startFoot, goal,
                Set.of(MovementType.TRAVERSE, MovementType.DIAGONAL,
                        MovementType.ASCEND, MovementType.DESCEND, MovementType.FALL),
                budget, requester);
    }
}
