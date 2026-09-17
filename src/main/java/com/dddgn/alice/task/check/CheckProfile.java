package com.dddgn.alice.task.check;

/** 自检步的运行档位归类：`BASELINE`/`MAIN` 进 CORE，`EXTRA` 只在 FULL 跑。 */
public enum CheckProfile {
    /** 必要基础（秒级自检、核心原语）：任何档位都跑，失败即"地基不稳"。 */
    BASELINE,
    /** 当前主线：CORE 也跑。 */
    MAIN,
    /** 扩展项：只在 FULL 跑（贵、窄、或工具类）。 */
    EXTRA
}
