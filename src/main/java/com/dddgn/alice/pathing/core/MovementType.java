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
    PLACE_STEP_AND_TRAVERSE;

    /**
     * **本 Movement 会改世界吗**（D-241）—— 规划期的**唯一静态口径**
     * （`PathRequest.pureTraversal()` 与 `WriteEnvelopes` 都从这里取，不再各抄一份名单）。
     *
     * <p>执行期的权威仍是 `MovementCapabilities.changesWorld`（按 Movement 实例带附加信息）；
     * 这里只是"按类型"的同一语义，供规划期做信封判断用。
     */
    public boolean changesWorld() {
        return this == DOWNWARD || this == PILLAR || this == BREAK_AND_TRAVERSE
                || this == BREAK_AND_ENTER || this == PLACE_STEP_AND_TRAVERSE;
    }
}
