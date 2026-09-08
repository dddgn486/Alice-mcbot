package com.dddgn.alice.pathing.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 移动原语（Movement Primitive）接口。
 * 
 * <h3>设计原则</h3>
 * <ul>
 *   <li>每个 Movement 实例代表一次具体的移动（从 A 到 B）</li>
 *   <li>Movement 是不可变的（Immutable）：创建后不应该改变</li>
 *   <li>每个 Movement 必须遵守 Bot 可回收性约束</li>
 *   <li>每个 Movement 有明确的成本和有效性判断</li>
 * </ul>
 * 
 * <h3>可回收性约束（Recoverability Constraints）</h3>
 * <ul>
 *   <li>❌ 不能挖脚下方块（防止掉落）</li>
 *   <li>❌ 不能跳下2格高（无法返回）</li>
 *   <li>❌ 不能进入液体（Phase 1）</li>
 *   <li>❌ 不能进入禁区（保护 Bot）</li>
 * </ul>
 * 
 * <h3>Phase 1 实现的原语</h3>
 * <ul>
 *   <li>{@link WalkMovement} - 平地行走</li>
 *   <li>{@link PillarMovement} - 搭柱子上升</li>
 *   <li>{@link BreakAndWalkMovement} - 挖前方障碍并前进</li>
 *   <li>{@link DescendMovement} - 搭台阶下降</li>
 * </ul>
 * 
 * <h3>使用方式</h3>
 * <pre>
 * // A* 搜索时
 * for (Movement move : MovementHelper.getPossibleMovements(bot, from, level)) {
 *     if (move.isValid()) {
 *         double cost = gScore.get(from) + move.cost();
 *         // 加入队列
 *     }
 * }
 * 
 * // 执行时
 * Status status = movement.tick(bot);
 * </pre>
 * 
 * @see BasicMovement
 */
public interface Movement {
    
    /**
     * 获取起点位置。
     * 
     * @return 移动起点
     */
    BlockPos from();
    
    /**
     * 获取终点位置。
     * 
     * @return 移动终点
     */
    BlockPos to();
    
    /**
     * 移动的时间成本（单位：tick）。
     * 
     * @return 完成这个移动需要的时间（tick）
     */
    double cost();
    
    /**
     * 判断这个移动是否有效。
     * 
     * <p>检查：
     * <ul>
     *   <li>地形是否允许（方块状态）</li>
     *   <li>是否违反可回收性约束</li>
     *   <li>Bot 是否有必要的资源（方块、工具）</li>
     * </ul>
     * 
     * @return true = 可以执行，false = 无效
     */
    boolean isValid();
    
    /**
     * 执行这个移动（单次 tick）。
     * 
     * <p>注意：Movement 可能需要多个 tick 才能完成。
     * 
     * @param bot ServerPlayer（通常是 BotPlayer）
     * @return 执行状态
     */
    Status tick(ServerPlayer bot);
    
    /**
     * Movement 执行状态。
     */
    enum Status {
        /** 还在进行中 */
        RUNNING,
        /** 成功完成 */
        SUCCESS,
        /** 失败（例如：路径被阻挡） */
        FAILED
    }
}
