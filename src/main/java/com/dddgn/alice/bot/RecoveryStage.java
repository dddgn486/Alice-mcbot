package com.dddgn.alice.bot;

/**
 * 任务执行期间发生过的最高层级恢复动作。
 * <p>这是观测契约，不改变各层实际的恢复责任和重试预算。</p>
 */
public enum RecoveryStage {
    NONE(0),
    TARGET_ACCESS_CLEAR(1),
    BOTMINER_PATH_RETRY(2),
    MINETASK_REPLAN(3),
    ESCALATED_FAILURE(4);

    private final int level;

    RecoveryStage(int level) {
        this.level = level;
    }

    /** 返回层级更高者，用于终态保留完整恢复轨迹。 */
    public static RecoveryStage highest(RecoveryStage first, RecoveryStage second) {
        RecoveryStage left = first == null ? NONE : first;
        RecoveryStage right = second == null ? NONE : second;
        return left.level >= right.level ? left : right;
    }
}
