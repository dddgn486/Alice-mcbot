package com.dddgn.alice.pathing.core;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;

/** 主线程执行期上下文；不用于后台路径搜索。 */
public record LiveExecutionContext(
        ServerPlayer bot,
        ServerLevel level,
        String sessionId,
        long currentWorldRevision,
        long policyVersion
) {
    public LiveExecutionContext {
        Objects.requireNonNull(bot, "bot");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(sessionId, "sessionId");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (currentWorldRevision < 0 || policyVersion < 0) {
            throw new IllegalArgumentException("revisions must be non-negative");
        }
    }
}
