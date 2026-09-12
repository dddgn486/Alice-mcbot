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

    @Override
    public Status tick() {
        if (++ticks > 120) {
            return finish("timeout");
        }
        if (ticks > 1) {
            return done ? (failures.isEmpty() ? Status.DONE : Status.FAILED) : Status.RUNNING;
        }
        runChecks();
        done = true;
        return Status.RUNNING;
    }

    private void runChecks() {
        BlockPos from = bot.blockPosition();
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

        // B：**同一目标 + 无限预算** ⇒ 期望到达（由 A 的校准保证可达，而不是靠假设）
        PathPlan ample = plan(from, goal, SearchBudget.UNLIMITED, "partial_ample");
        check("same_goal_reachable_with_budget",
                ample.reached() && !ample.movements().isEmpty(),
                "goal=" + goal.toShortString() + " " + ample.summary());

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
                + " verdict=" + (failures.isEmpty() ? "PASS" : "FAIL");
        BotLog.info("[PartialSearch] SUMMARY {}（最远可达距离={}）", summary, reachableDistance);
        if (observer != null) {
            observer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[PartialSearch] " + summary));
        }
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
