package com.dddgn.alice.task;

import net.minecraft.core.BlockPos;

/**
 * 任务目标:执行层「干什么」的结构化描述。
 * <p>
 * 现阶段只有两类,与客户端透视高亮一一对应:
 * <ul>
 *   <li>{@code BLOCK}:方块目标(挖掘)——{@link #blockPos()} 有效;</li>
 *   <li>{@code ENTITY}:实体目标(攻击/拾取)——{@link #entityId()} 有效。</li>
 * </ul>
 * 掉落物是 {@code ItemEntity},归 ENTITY 类(服务端知道 id,客户端实时取 AABB 画框)。</p>
 * <p>框架位:后续 LLM 决策层产出、行为包产出、玩家测试工具产出,统一落到这个类型。</p>
 */
public record TaskTarget(Type type, BlockPos blockPos, int entityId) {

    public enum Type { BLOCK, ENTITY }

    public static TaskTarget block(BlockPos pos) {
        return new TaskTarget(Type.BLOCK, pos.immutable(), -1);
    }

    public static TaskTarget entity(int entityId) {
        return new TaskTarget(Type.ENTITY, null, entityId);
    }

    /** 人类可读描述(日志/测试反馈用)。 */
    public String describe() {
        return switch (type) {
            case BLOCK -> "方块@" + blockPos.toShortString();
            case ENTITY -> "实体#" + entityId;
        };
    }
}
