package com.dddgn.alice.task;

import com.dddgn.alice.bot.TaskFailureReport;
import com.dddgn.alice.log.BotLog;

import java.util.function.Supplier;

/**
 * **前提闸门**：在夹具任务**开跑之前**求值一次"世界前提"，不成立就**当场以专属码收场**。
 *
 * <h2>为什么不是"跑完再判"</h2>
 * `CheckStep.skipWhen` 是**终态之后**才被求值的（`RegressionBatteryTask:754-761`）。若把第三方保护
 * 做成 `skipWhen`，任务仍会真跑一遍、仍会打出那条**误导性的功能失败码**（`no_reachable_candidate`），
 * 只是最后被记成 SKIP —— **错的原因还是留在日志里**（`D-409` 的核心教训正是这个）。
 * ⇒ 闸门把前提提到**首 tick 之前**：不成立就**一个字的世界操作都不做**，日志里只有"前提不成立"。
 *
 * <h2>语义：这是 `SKIP`（环境不具备），不是 `FAILED`</h2>
 * 前提不成立时返回 `DONE` + `failureReason() = 前提码`，配合 `CheckStep.skippable(...)` 的条件
 * ⇒ 电池记 **`SKIP`**（"环境不具备 ⇒ 跳过，不算失败"），且 `finish()` 里 SKIP **不计入 PASS**
 * ⇒ 整轮变 **`DEGRADED`**。**绝不假绿**：本步的结论就是"不作数"，如实说出来。
 *
 * <h2>必须透传的成员（写错会静默改变行为）</h2>
 * <ul>
 *   <li>{@link #isSelfCheck()}：`action/WritePolicyMatrix` 与夹具洁净门禁都读它判"这是不是自检任务"；
 *       包装器**自己**名字里没有夹具标记 ⇒ 不透传就会让内层任务被判成生产任务（写策略随之改变）；</li>
 *   <li>{@link #taskName()} / {@link #target()} / {@link #safeToCancel()} / {@link #failureReport()}：
 *       归因与取消语义都必须还是**内层任务**的。</li>
 * </ul>
 */
public final class PremiseGateTask implements Task {

    private final Task inner;
    private final String owner;
    private final Supplier<String> premise;

    private String premiseCode;
    private boolean premiseChecked;
    private Task.Status terminal;

    /**
     * @param owner   归因用名字（日志里说清是哪一步）
     * @param premise 前提求值：返回 `null` = 成立；非 null = **不成立**（码）
     * @param inner   前提成立时才真正跑的任务
     */
    public PremiseGateTask(String owner, Supplier<String> premise, Task inner) {
        this.owner = owner == null ? "unnamed" : owner;
        this.premise = premise;
        this.inner = inner;
    }

    @Override
    public Task.Status tick() {
        if (terminal != null) {
            return terminal;   // 终态幂等（D-178）
        }
        if (!premiseChecked) {
            premiseChecked = true;
            premiseCode = premise == null ? null : premise.get();
            if (premiseCode != null) {
                BotLog.warn("[Premise] {} 世界前提不成立（{}）⇒ 本步**结论不作数**（记 SKIP，不是功能失败）",
                        owner, premiseCode);
                terminal = Task.Status.DONE;
                return terminal;
            }
        }
        Task.Status status = inner.tick();
        if (status != Task.Status.RUNNING) {
            terminal = status;
        }
        return status;
    }

    /** 前提不成立 ⇒ **如实报前提码**（`CheckStep.skipWhen` 就是按它匹配的）。 */
    @Override
    public String failureReason() {
        return premiseCode != null ? premiseCode : inner.failureReason();
    }

    @Override
    public String terminalReason() {
        return premiseCode != null ? premiseCode : inner.terminalReason();
    }

    @Override
    public TaskTarget target() {
        return inner.target();
    }

    @Override
    public boolean isSelfCheck() {
        return inner.isSelfCheck();
    }

    @Override
    public String taskName() {
        return inner.taskName();
    }

    @Override
    public boolean safeToCancel() {
        return inner.safeToCancel();
    }

    @Override
    public TaskFailureReport failureReport() {
        return inner.failureReport();
    }

    /**
     * 内层任务（**给 `doneWhen` 这类判据用**：它们按内层类型写，闸门不改变那套写法）。
     * 例如 `LumberModule` 的 `region_maintain` 写成 `unwrap(task) instanceof RegionLumberJob`。
     */
    public Task inner() {
        return inner;
    }
}
