package com.dddgn.aibot.log;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * 假人动作日志。
 * <p>
 * M0 假人客户端不可见,动作轨迹全靠日志验证(站位选择、移动、视线检查、挖掘结果)。
 * 格式统一为 [aibot] 前缀,方便在 latest.log 里 grep。</p>
 */
public final class BotLog {

    public static final Logger LOGGER = LogUtils.getLogger();

    private BotLog() {
    }

    public static void info(String message, Object... args) {
        LOGGER.info("[aibot] " + message, args);
    }

    public static void warn(String message, Object... args) {
        LOGGER.warn("[aibot] " + message, args);
    }
}
