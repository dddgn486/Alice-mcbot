package com.dddgn.alice.pathing;

/**
 * 移动语义边界。当前普通路径和收集仍固定使用 HARD_PATH；其他模式必须由专用执行器实现。
 */
public enum MovementMode {
    /** 现有 setPos 小步路径跟随，不模拟完整玩家物理。 */
    HARD_PATH,
    /** 未来使用原版碰撞移动的安全地面模式。 */
    SOFT_SURFACE,
    /** 未来受限水域移动，不用于岩浆。 */
    SOFT_FLUID,
    /** 道路/通道施工专用强制移动，仍受 SurvivalSystem 保护。 */
    FORCED_BUILD
}
