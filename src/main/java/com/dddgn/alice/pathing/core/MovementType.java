package com.dddgn.alice.pathing.core;

/** R2 正式 Movement 类型；与旧实验 MovementType 隔离。 */
public enum MovementType {
    TRAVERSE,
    DIAGONAL,
    ASCEND,
    DESCEND,
    DOWNWARD,
    PILLAR,
    FALL,
    BREAK_AND_TRAVERSE,
    BREAK_AND_ENTER,
    PLACE_STEP_AND_TRAVERSE
}
