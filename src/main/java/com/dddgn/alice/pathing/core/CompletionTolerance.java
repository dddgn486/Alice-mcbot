package com.dddgn.alice.pathing.core;

/**
 * 完成容差（D-027）：由 PathSession / PathRequest 指定，执行器不得自行决定。
 *
 * <ul>
 *   <li>{@link #EXACT}：脚位正确 + 落地 + 水平距中心 ≤0.3（安全关键站位，如挖矿站位、悬空边缘）；</li>
 *   <li>{@link #COLUMN}：脚位方块正确 + Y 达标即完成（对齐 Baritone，用于链式中间段，
 *       避免"空中掉头回冲"）。</li>
 * </ul>
 */
public enum CompletionTolerance {
    EXACT,
    COLUMN
}
