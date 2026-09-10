package com.dddgn.alice.job;

import com.dddgn.alice.log.BotLog;

import java.util.ArrayList;
import java.util.List;

/**
 * 决策可判读（L3 决策缝之四）：L3 的**唯一**日志出口，格式固定、机器可判读。
 *
 * <p>设计依据 `docs/JOB_LAYER_DESIGN.md` §6：现有「场景 + SUMMARY」只能验收 L0–L2（动作结果），
 * 验收不了"**选对了哪个**"。因此 L3 的每类断言都依赖下列三种行：
 *
 * <pre>
 * [Job] select   job=lumber policy=nearest picked=tree@12,64,8 reason=nearest d=3.2 candidates=3 rejected=[tree@10,64,9:no_stand,…]
 * [Job] step     job=lumber phase=CUT target=12,64,8 detail=log 1/4
 * [Job] terminal job=lumber result=DONE reason=quota_met progress=logs 4/4 ticks=210
 * </pre>
 *
 * <p>不新建日志框架：与 `PathingStats` / `TaskExecutionRecord` 并列，只做 L3 的决策出口。
 */
public final class DecisionTrace {

    private DecisionTrace() {
    }

    public static void select(String job, String policy, CandidateSet set, Selection selection) {
        List<String> rejected = new ArrayList<>(selection.rejected());
        if (selection.picked() == null && rejected.isEmpty()) {
            rejected.add("all_rejected_without_reason");
        }
        BotLog.info("[Job] select job={} policy={} picked={} reason={} candidates={} rejected={}",
                job, policy,
                selection.picked() == null ? "-" : selection.picked().id(),
                selection.reason(),
                set.viable().size(),
                rejected.isEmpty() ? "[]" : "[" + String.join(",", rejected) + "]");
    }

    public static void step(String job, String phase, String target, String detail) {
        BotLog.info("[Job] step job={} phase={} target={} detail={}", job, phase, target, detail);
    }

    public static void terminal(String job, String result, String reason, String progress, int ticks) {
        BotLog.info("[Job] terminal job={} result={} reason={} progress={} ticks={}",
                job, result, reason, progress, ticks);
    }
}
