package com.dddgn.alice.survival;

import net.minecraft.core.BlockPos;

/** 单 tick 的不可变危险快照；不负责决定逃生路线。 */
public record HazardState(
        HazardType type,
        int durationTicks,
        int airSupply,
        float health,
        float previousHealth,
        BlockPos position) {

    public HazardState {
        position = position.immutable();
    }

    /**
     * 是否**硬危险**（岩浆/窒息）。
     *
     * <p>S-5（2026-09-15）：判据**只此一份**（{@link SurvivalSystem#hardHazard}）—— 本方法此前把
     * 同样的 `type == LAVA_CONTACT || type == SUFFOCATING` 抄了第二遍（台账 TD-1 的谓词重复）。
     * 注意它**不**包含软危险（溺水/着火）：那两类是否动手取决于"有没有出口"，不是状态本身的属性。
     */
    public boolean dangerous() {
        return SurvivalSystem.hardHazard(type);
    }

    /**
     * **本 tick 掉血了吗**（S-5，2026-09-15）。
     *
     * <p>{@code previousHealth} 自 S-1 起就在记录里，但**一直只写不读**（审计发现）—— 于是"bot 在掉血"
     * 这件事对上层完全不可见。第一个读者是 {@code EventThresholds.checkHealthLoss}（掉血 ⇒ DANGER 事件）。
     */
    public boolean healthLost() {
        return health < previousHealth;
    }

    /** 本 tick 掉了多少血（未掉血 = 0）。 */
    public float healthLostAmount() {
        return Math.max(0.0F, previousHealth - health);
    }
}
