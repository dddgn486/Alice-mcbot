package com.dddgn.alice.pathing;

/**
 * 寻路移动类型(移植自 Baritone Moves 枚举的子集)。
 * M 阶段先支持:平走 / 上一阶 / 下一格 / 向下挖一格;
 * 斜走、跑酷、搭柱、挖隧道后续里程碑扩展。
 */
public enum MovementType {
    TRAVERSE,
    ASCEND,
    DESCEND,
    DOWNWARD
}
