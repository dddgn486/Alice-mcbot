package com.dddgn.alice.pathing;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

/**
 * SOFT_SURFACE 的最小移动原语：朝向目标并沿前方推进一 tick。
 * <p>这是 Forge 假人的适配层，仍由 MoverType.SELF 处理碰撞；完整玩家输入/travel 注入另行验证。</p>
 */
public final class SoftMovementPrimitive {
    /** 原版玩家无疾跑平地移动的近似水平速度，单位为格/tick。 */
    public static final double WALK_SPEED = 0.215D;

    private SoftMovementPrimitive() {
    }

    public record Step(double distance, float yaw, Vec3 delta) {
    }

    /** 计算“朝目标转身 + 前进”的单 tick 原语，不修改实体状态。 */
    public static Step toward(ServerPlayer bot, double targetX, double targetZ, double maxStep) {
        double dx = targetX - bot.getX();
        double dz = targetZ - bot.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= 0.0D) {
            return new Step(0.0D, bot.getYRot(), Vec3.ZERO);
        }
        double step = Math.min(Math.min(WALK_SPEED, maxStep), distance);
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        return new Step(step, yaw, new Vec3(dx / distance * step, 0.0D, dz / distance * step));
    }

    /** 执行单 tick 原语：设置朝向，再交给原版碰撞移动。 */
    public static Step applyToward(ServerPlayer bot, double targetX, double targetZ, double maxStep) {
        Step step = toward(bot, targetX, targetZ, maxStep);
        bot.setYRot(step.yaw());
        bot.setYHeadRot(step.yaw());
        if (step.distance() > 0.0D) {
            bot.move(MoverType.SELF, step.delta());
        }
        return step;
    }
}
