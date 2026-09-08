package com.dddgn.alice.pathing.core.session;

import net.minecraft.core.BlockPos;

import java.util.Objects;

/**
 * PathSession 执行结果（结构化事实，供任务层与 LLM 决策层消费）。
 *
 * <p>只报告事实，不替任务决定重试/换策略/放弃（架构文档 §9）。
 */
public record PathExecutionResult(
        PathSessionStatus status,
        int plannedSegments,
        int completedSegments,
        String failureCode,
        int failureSegment,
        BlockPos finalFoot,
        int totalTicks,
        String diagnostics
) {
    public PathExecutionResult {
        status = Objects.requireNonNull(status, "status");
        finalFoot = finalFoot == null ? BlockPos.ZERO : finalFoot.immutable();
        failureCode = failureCode == null ? "" : failureCode;
        diagnostics = diagnostics == null ? "" : diagnostics;
    }

    public boolean completed() {
        return status == PathSessionStatus.COMPLETED;
    }

    public String summary() {
        return "status=" + status
                + " segments=" + completedSegments + "/" + plannedSegments
                + " ticks=" + totalTicks
                + (failureCode.isEmpty() ? "" : " code=" + failureCode)
                + (failureSegment >= 0 ? " failedAt=" + failureSegment : "")
                + " finalFoot=" + finalFoot.toShortString();
    }
}
