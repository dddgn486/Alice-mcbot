package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.search.CorePathPlanner;
import com.dddgn.alice.pathing.core.search.PathPlan;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.search.PlanningStatus;
import com.dddgn.alice.pathing.core.search.SearchBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * **前缀搜索自检**（K-1 / K-5，基-7 第一批）：三例，纯规划（**不移动 bot**），约 1 秒。
 *
 * <ol>
 *   <li>A **预算耗尽 ⇒ 交出前缀**：远目标 + 极小预算 ⇒ 期望 `PARTIAL` 且 `movements` 非空；</li>
 *   <li>B **同样的目标 + 正常预算 ⇒ `REACHED`** ⇒ 证明 A 的 PARTIAL 是"没算完"，不是"规划不好"；</li>
 *   <li>C **非 PARTIAL 的失败一律不带边**：目标丢到极远处（区块未加载）⇒ 期望 `GOAL_NOT_LOADED`
 *       且 `movements` 为空 ⇒ 守住"`SEARCH_LIMIT`/`UNREACHABLE` 不给前缀、`PARTIAL` 只给前缀"的契约。</li>
 * </ol>
 *
 * <p>为什么必须断言"`reached()` 为 false"：前缀**不是到达**。K-1 的意义是让消费者"先走一段再重规划"，
 * 而不是让任何调用方把它当成功（那会把 bot 带到半路就宣布完成）。
 */
public class PartialSearchCheckTask implements Task {

    /** 时间预算的允许过冲（预算按**节点**检查 ⇒ 单个节点的扩展时间；tick 预算 50 ms 的零头）。 */
    private static final long TIME_BUDGET_SLACK_MILLIS = 50L;

    private final BotPlayer bot;
    private final ServerPlayer observer;
    private final List<String> failures = new ArrayList<>();
    private int ticks;
    private boolean done;

    public PartialSearchCheckTask(BotPlayer bot, ServerPlayer observer) {
        this.bot = bot;
        this.observer = observer;
    }

    @Override
    public String taskName() {
        return "PartialSearchCheck";
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(bot.blockPosition());
    }

    @Override
    public String failureReason() {
        return failures.isEmpty() ? "" : String.join(",", failures);
    }

    @Override
    public String terminalReason() {
        return done ? (failures.isEmpty() ? "passed" : "failed") : "";
    }

    /**
     * **场景起点**（脚位）与场景函数（2026-09-16）：判据必须**位置确定** —— 此前本夹具直接
     * `bot.blockPosition()` 规划，依赖"电池前面步骤把 bot 摆到好地形" ⇒ **单跑本步时整组判据会红**
     * （实测 `single:partial_search`：`calibration_reachable_goal=FAIL`），违反"夹具自己传送 bot 到场景起点"。
     */
    public static final BlockPos SCENE_START_FOOT = new BlockPos(16, 64, 245);

    private static final String SCENE_FUNCTION = "alice_test:partial_search_terrain";
    private static final int TELEPORT_TICK = 1;
    /** 等区块真的加载（上限）；`/fill` 在未加载区块里**静默改 0 格**（D-252 教训）。 */
    private static final int CHUNK_WAIT_MAX_TICKS = 60;

    @Override
    public Status tick() {
        if (++ticks > 120) {
            return finish("timeout");
        }
        if (ticks == TELEPORT_TICK) {
            // 先到位：**先加载区块再建地形**（`/fill` 在未加载区块里会静默改 0 格 —— D-252 的教训）
            teleportToScene();
            return Status.RUNNING;
        }
        if (!terrainBuilt) {
            // 等到场景两端所在区块都加载（否则 `/fill` 是空操作，规划期就会看到"没有地板"）
            // 走廊横跨 3 个区块 ⇒ **两端都要加载**，否则大范围 `/fill` 会整条失败（实测坑）
            boolean loaded = level().hasChunkAt(SCENE_START_FOOT.west(16))
                    && level().hasChunkAt(SCENE_START_FOOT.east(24));
            if (!loaded && ticks - TELEPORT_TICK <= CHUNK_WAIT_MAX_TICKS) {
                return Status.RUNNING;
            }
            check("scene_chunks_loaded", loaded,
                    "起点与远目标两端区块 hasChunkAt（等 " + (ticks - TELEPORT_TICK) + " tick）");
            var server = bot.getServer();
            int commands = server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withSuppressedOutput(), "function " + SCENE_FUNCTION);
            check("scene_terrain_built", commands > 0,
                    SCENE_FUNCTION + " ⇒ " + commands + " 条命令（0 = 数据包缺失/陈旧）");
            teleportToScene();
            // **地板真的在吗**：`/fill` 改 0 格时返回值看不出来 ⇒ 直接查世界（否则规划期只会报 UNREACHABLE）
            check("scene_floor_present",
                    bot.serverLevel().getBlockState(SCENE_START_FOOT.below()).is(net.minecraft.world.level.block.Blocks.STONE)
                            && bot.serverLevel().getBlockState(SCENE_START_FOOT.east(20).below())
                            .is(net.minecraft.world.level.block.Blocks.STONE)
                            && bot.serverLevel().getBlockState(SCENE_START_FOOT).isAir()
                            && bot.serverLevel().getBlockState(SCENE_START_FOOT.above()).isAir(),
                    "起点支撑=" + bot.serverLevel().getBlockState(SCENE_START_FOOT.below()).getBlock().getName().getString()
                            + " 脚位=" + bot.serverLevel().getBlockState(SCENE_START_FOOT).getBlock().getName().getString());
            terrainBuilt = true;
            return Status.RUNNING;
        }
        if (done) {
            return failures.isEmpty() ? Status.DONE : Status.FAILED;
        }
        runChecks();
        done = true;
        return Status.RUNNING;
    }

    private void runChecks() {
        BlockPos from = SCENE_START_FOOT;   // 位置确定（见 tick 的传送相位）
        // **自校准**（2026-09-13 实测教训）：不能假设"往东 24 格一定可达"——
        // 第一版就这么写，结果该目标在无限预算下也是 UNREACHABLE（那片地形真的过不去），
        // 于是"给够预算就能到达"这条断言测的是**错误前提**。
        // 现在先探出"最远可达距离"，再用它构造两例。
        int reachableDistance = 0;
        PathPlan reachablePlan = null;
        for (int distance : new int[]{4, 8, 12, 16}) {
            PathPlan probe = plan(from, from.offset(distance, 0, 0), SearchBudget.UNLIMITED,
                    "partial_probe_" + distance);
            if (probe.reached()) {
                reachableDistance = distance;
                reachablePlan = probe;
            }
        }
        check("calibration_reachable_goal", reachablePlan != null,
                "最远可达距离=" + reachableDistance + (reachablePlan == null ? "（本处地形没有任何近距目标可达）"
                        : " movements=" + reachablePlan.movements().size()));

        BlockPos goal = from.offset(Math.max(4, reachableDistance), 0, 0);
        // A：**极小预算** ⇒ 期望只交出前缀（2 个节点的扩展不可能走完 ≥4 格）
        PathPlan tight = plan(from, goal, SearchBudget.of(2, 0L), "partial_tight");
        check("partial_with_prefix",
                tight.status() == PlanningStatus.PARTIAL && !tight.reached()
                        && !tight.movements().isEmpty()
                        && tight.projectedFootPath().size() == tight.movements().size() + 1,
                "goal=" + goal.toShortString() + " " + tight.summary() + " diagnostics=" + tight.diagnostics());

        // C（`D-369`）：**极小时间预算** ⇒ 搜索必须真的遵守**墙钟**预算。
        // 为什么必须有这条：Alice 的搜索跑在**服务器 tick 线程**上（tick 预算 50 ms），默认时间预算若被调大
        // （原值 3_000 ms = 60 倍预算）单次搜索就能合法独占一个 tick —— 真机三次 `Can't keep up!`
        // （2035/2632/2232 ms）正是这个机制。这条判据把"时间预算必须生效"钉住（节点预算已由 A 覆盖）。
        // ⚠️ 这里**不能**断言"1 ms 预算下不许算完"：本场景的搜索只要 **0–1 ms**（`nodes≈17`）
        // ⇒ 预算内自然跑完是**合法**的（"遵守预算" ≠ "必须没跑完"）。
        // 实测教训：CORE 全量跑时该断言**红过一次**（`status=REACHED elapsed=0ms`），而单步跑恰好 1 ms 通过
        // ⇒ 状态断言天然 flaky。改成**确定性**两段判据：
        //   ① 谓词本身正确（`timeBudgetExhausted`：>0 生效、≤0 = 不限制）；
        //   ② 端到端 elapsed 必须**不超过预算 + 一个节点的过冲**（这条与场景快慢无关）。
        check("time_budget_predicate",
                SearchBudget.of(0, 1L).timeBudgetExhausted(1L)
                        && !SearchBudget.of(0, 1L).timeBudgetExhausted(0L)
                        && !SearchBudget.of(0, 0L).timeBudgetExhausted(9999L),
                "预算谓词语义：>0 生效（1 ms 在 elapsed=1 时耗尽）、≤0 = 不限制（`D-369`）");
        PathPlan tightTime = plan(from, goal, SearchBudget.of(0, 1L), "partial_tight_time");
        check("time_budget_elapsed_bounded",
                tightTime.elapsedMillis() <= 1L + TIME_BUDGET_SLACK_MILLIS,
                "搜索必须在墙钟预算附近停下（实测 " + tightTime.elapsedMillis() + "ms ≤ 1+"
                        + TIME_BUDGET_SLACK_MILLIS + "ms · status=" + tightTime.status()
                        + "；超出 = 单次搜索会独占 tick）");

        // B：**同一目标 + 无限预算** ⇒ 期望到达（由 A 的校准保证可达，而不是靠假设）
        PathPlan ample = plan(from, goal, SearchBudget.UNLIMITED, "partial_ample");
        check("same_goal_reachable_with_budget",
                ample.reached() && !ample.movements().isEmpty(),
                "goal=" + goal.toShortString() + " " + ample.summary());

        // D（K-1 收口，2026-09-16）：**前缀也会被执行** ⇒ 它必须和整条计划吃同一份自洽校验，
        // 而且它的最后一段**不是目标段**（容差按中间段）。这两条是"前缀被当成完整计划"的两个漏洞。
        check("partial_prefix_self_consistent",
                tight.partial() && com.dddgn.alice.pathing.core.search.SelfWriteConsistency
                        .firstConflict(bot.serverLevel(), tight.movements()) == null,
                "前缀不得含「踩在自己挖掉的格子上」的边（D-250 同一份校验，规划器对 PARTIAL 也跑）");

        // 裁剪助手单测：合成"先挖掉 X、后面的边却要踩 X" ⇒ 必须裁到违规边之前（保留第一段）
        BlockPos stepA = from.offset(1, 0, 0);
        BlockPos stepB = from.offset(2, 0, 0);
        var digEdge = new com.dddgn.alice.pathing.core.search.PlannedMovement(
                com.dddgn.alice.pathing.core.MovementType.BREAK_AND_ENTER, from, stepA, 4.88D,
                com.dddgn.alice.pathing.core.RecoverabilityLevel.PATH_REVERSIBLE);
        var stepEdge = new com.dddgn.alice.pathing.core.search.PlannedMovement(
                com.dddgn.alice.pathing.core.MovementType.TRAVERSE, stepA, stepB, 1.0D,
                com.dddgn.alice.pathing.core.RecoverabilityLevel.PATH_REVERSIBLE);
        var syntheticConflict = new com.dddgn.alice.pathing.core.search.SelfWriteConsistency.Conflict(
                new com.dddgn.alice.pathing.core.search.SelfWriteConsistency.EdgeKey(
                        com.dddgn.alice.pathing.core.MovementType.BREAK_AND_ENTER, from, stepA),
                com.dddgn.alice.pathing.core.MovementType.TRAVERSE, stepB, stepA);
        var safePrefix = com.dddgn.alice.pathing.core.search.SelfWriteConsistency
                .safePrefixBefore(List.of(digEdge, stepEdge), syntheticConflict);
        check("prefix_truncated_before_conflict",
                safePrefix.size() == 1
                        && safePrefix.get(0).movementType() == com.dddgn.alice.pathing.core.MovementType.BREAK_AND_ENTER
                        && com.dddgn.alice.pathing.core.search.SelfWriteConsistency
                        .safePrefixBefore(List.of(stepEdge), syntheticConflict).isEmpty(),
                "裁到违规边之前（违规边在第一条时 ⇒ 空 = 没有可安全执行的前缀）");

        // 容差决策：**只有"最后一段 + 计划真的到达目标"**才用 EXACT；PARTIAL 前缀的最后一段按中间段口径
        check("prefix_tail_uses_column_tolerance",
                com.dddgn.alice.pathing.core.session.PathSession
                        .toleranceFor(true, false) == com.dddgn.alice.pathing.core.CompletionTolerance.COLUMN
                        && com.dddgn.alice.pathing.core.session.PathSession
                        .toleranceFor(true, true) == com.dddgn.alice.pathing.core.CompletionTolerance.EXACT
                        && com.dddgn.alice.pathing.core.session.PathSession
                        .toleranceFor(false, true) == com.dddgn.alice.pathing.core.CompletionTolerance.COLUMN,
                "(final=false,reached=true)=COLUMN / (final=true,reached=true)=EXACT / (final=true,reached=false)=COLUMN");

        // C：真失败（未加载）**不给前缀**
        BlockPos unloaded = from.offset(100000, 0, 0);
        PathPlan blocked = plan(from, unloaded, SearchBudget.UNLIMITED, "partial_unloaded");
        check("no_prefix_for_real_failures",
                blocked.status() == PlanningStatus.GOAL_NOT_LOADED && blocked.movements().isEmpty()
                        && !blocked.partial(),
                blocked.summary());

        String summary = "calibration_reachable_goal=" + verdict("calibration_reachable_goal")
                + " partial_with_prefix=" + verdict("partial_with_prefix")
                + " same_goal_reachable_with_budget=" + verdict("same_goal_reachable_with_budget")
                + " no_prefix_for_real_failures=" + verdict("no_prefix_for_real_failures")
                + " partial_prefix_self_consistent=" + verdict("partial_prefix_self_consistent")
                + " prefix_truncated_before_conflict=" + verdict("prefix_truncated_before_conflict")
                + " prefix_tail_uses_column_tolerance=" + verdict("prefix_tail_uses_column_tolerance")
                + " verdict=" + (failures.isEmpty() ? "PASS" : "FAIL");
        BotLog.info("[PartialSearch] SUMMARY {}（最远可达距离={}）scene={} start={}", summary, reachableDistance,
                SCENE_FUNCTION, SCENE_START_FOOT.toShortString());
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[PartialSearch] " + summary));
        }
    }

    private boolean terrainBuilt;

    private net.minecraft.server.level.ServerLevel level() {
        return bot.serverLevel();
    }

    private void teleportToScene() {
        bot.teleportTo(bot.serverLevel(), SCENE_START_FOOT.getX() + 0.5D, SCENE_START_FOOT.getY(),
                SCENE_START_FOOT.getZ() + 0.5D, java.util.Set.of(), bot.getYRot(), bot.getXRot());
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        bot.controller().stopMovement();
    }

    private PathPlan plan(BlockPos from, BlockPos goal, SearchBudget budget, String requester) {
        PathRequest request = new PathRequest(bot.getUUID().toString(), from,
                new com.dddgn.alice.pathing.core.search.GoalFoot(goal),
                PathRequest.of(bot.getUUID().toString(), from, goal, requester).allowedMovementTypes(),
                budget, requester);
        return new CorePathPlanner().plan(bot, bot.serverLevel(), request);
    }

    private void check(String name, boolean ok, String detail) {
        if (!ok && !failures.contains(name)) {
            failures.add(name);
        }
        BotLog.info("[PartialSearch] case={} result={} {}", name, ok ? "PASS" : "FAIL", detail);
    }

    private String verdict(String name) {
        return failures.contains(name) ? "FAIL" : "PASS";
    }

    private Status finish(String reason) {
        done = true;
        BotLog.warn("[PartialSearch] 自检未完成 reason={}", reason);
        return Status.FAILED;
    }
}
