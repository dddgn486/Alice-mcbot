package com.dddgn.alice.survival;

/** 维生系统统一识别的危险类型，按严重程度由调用方处理。 */
public enum HazardType {
    NONE,
    WATER_CONTACT,
    ON_FIRE,
    /** **冻结**（细雪）：`ticksFrozen` 累积到 {@link SurvivalSystem#FREEZE_WARN_TICKS} 即算危险（软危险）。 */
    FREEZING,
    LOW_AIR,
    SUFFOCATING,
    LAVA_CONTACT
}
