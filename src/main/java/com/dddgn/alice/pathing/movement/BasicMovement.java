package com.dddgn.alice.pathing.movement;

import com.dddgn.alice.bot.BotPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

/**
 * 基础移动工具类（Basic Movement Utilities）。
 * 
 * <h3>职责</h3>
 * <p>
 * 提供底层的移动执行方法，供 Movement 原语调用。
 * 这是对原版物理引擎的简单封装。
 * </p>
 * 
 * <h3>与 SoftMovementPrimitive 的关系</h3>
 * <p>
 * 这个类替代了原来的 SoftMovementPrimitive，名字更清晰。
 * 功能保持不变：使用原版 travel() 执行移动。
 * </p>
 * 
 * @see Movement
 */
public final class BasicMovement {
    
    /** 原版玩家无疾跑平地移动的近似水平速度（格/tick）。 */
    public static final double WALK_SPEED = 0.215D;
    
    private BasicMovement() {}
    
    /**
     * 朝目标方向移动一步（使用原版物理引擎）。
     * 
     * <p>执行流程：
     * <ol>
     *   <li>计算朝向目标的 yaw 角度</li>
     *   <li>设置 Bot 朝向</li>
     *   <li>设置输入（bot.zza = 1.0F）</li>
     *   <li>调用 bot.travel() 执行移动</li>
     * </ol>
     * 
     * @param bot ServerPlayer（通常是 BotPlayer）
     * @param targetX 目标 X 坐标
     * @param targetZ 目标 Z 坐标
     * @return 实际移动的距离
     */
    public static double applyToward(ServerPlayer bot, double targetX, double targetZ) {
        double dx = targetX - bot.getX();
        double dz = targetZ - bot.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        
        if (distance <= 0.0D) {
            return 0.0D;
        }
        
        // 设置朝向
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        bot.setYRot(yaw);
        bot.setYHeadRot(yaw);
        
        // D-025/D-030：台阶高度 0.6，前方高于台阶高度的障碍必须跳跃（参照 Baritone MovementAscend）
        jumpIfStepAhead(bot, dx, dz, distance);
        
        // 设置输入并执行移动（使用原版物理）
        bot.xxa = 0.0F;
        bot.zza = 1.0F;
        bot.travel(new Vec3(0.0D, 0.0D, 1.0D));
        
        return distance;
    }

    /**
     * 前方是否存在需要跳跃的障碍（高度 &gt; 当前 maxUpStep），并按 Baritone
     * {@code MovementAscend} 的门控决定是否请求跳跃。
     *
     * <p>门控（对照 Baritone `MovementAscend:209-230`）：
     * <ul>
     *   <li>必须在地面；</li>
     *   <li>沿运动轴的横向速度 ≤ 0.1（先对准再跳，避免斜着起跳落回原地）；</li>
     *   <li>障碍上方可穿过（否则会撞头，不跳）；</li>
     *   <li>半砖/台阶（顶面 ≤ maxUpStep）不触发，交给原版自动跨台阶。</li>
     * </ul>
     * 跳跃请求交给 controller，由下一次 `aiStep()` 消费（legacy 直驱模型下同样生效）。
     */
    public static void jumpIfStepAhead(ServerPlayer bot, double dx, double dz, double distance) {
        if (!(bot instanceof BotPlayer botPlayer) || !bot.onGround() || distance < 1.0E-6D) {
            return;
        }
        double dirX = dx / distance;
        double dirZ = dz / distance;

        // 沿运动轴的横向速度（Baritone 的 lateralMotion 语义）
        boolean xDominant = Math.abs(dirX) >= Math.abs(dirZ);
        double lateral = xDominant
                ? Math.abs(bot.getDeltaMovement().z)
                : Math.abs(bot.getDeltaMovement().x);
        if (lateral > 0.1D) {
            return;
        }

        for (double ahead : new double[]{0.55D, 0.85D}) {
            net.minecraft.core.BlockPos foot = net.minecraft.core.BlockPos.containing(
                    bot.getX() + dirX * ahead, bot.getY(), bot.getZ() + dirZ * ahead);
            net.minecraft.world.level.block.state.BlockState state = bot.level().getBlockState(foot);
            net.minecraft.world.phys.shapes.VoxelShape shape =
                    state.getCollisionShape(bot.level(), foot);
            if (shape.isEmpty()) {
                continue;
            }
            double top = shape.max(net.minecraft.core.Direction.Axis.Y);
            if (top <= bot.maxUpStep() + 1.0E-6D) {
                continue;
            }
            net.minecraft.core.BlockPos head = foot.above();
            if (!bot.level().getBlockState(head)
                    .getCollisionShape(bot.level(), head).isEmpty()) {
                return; // 撞头，不跳
            }
            botPlayer.controller().jumpOnce();
            return;
        }
    }
    
    /**
     * 朝目标方向移动并跳跃（上台阶或越过障碍）。
     * 
     * @param bot ServerPlayer（通常是 BotPlayer）
     * @param targetX 目标 X 坐标
     * @param targetZ 目标 Z 坐标
     * @return 实际移动的距离
     */
    public static double applyJumpToward(ServerPlayer bot, double targetX, double targetZ) {
        double dx = targetX - bot.getX();
        double dz = targetZ - bot.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        
        if (distance <= 0.0D) {
            return 0.0D;
        }
        
        // 设置朝向
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        bot.setYRot(yaw);
        bot.setYHeadRot(yaw);
        
        // 设置输入并跳跃
        bot.setJumping(true);
        bot.xxa = 0.0F;
        bot.zza = 1.0F;
        bot.travel(new Vec3(0.0D, 0.0D, 1.0D));
        
        return distance;
    }
    
    /**
     * 物理结算（Settle）：零输入，让 travel() 处理重力、摩擦和落地。
     * 
     * <p>用途：
     * <ul>
     *   <li>到达目标后，等待 Bot 稳定落地</li>
     *   <li>清除残留速度</li>
     *   <li>让原版物理自然处理（不手动 setPos）</li>
     * </ul>
     * 
     * @param bot ServerPlayer（通常是 BotPlayer）
     */
    public static void settle(ServerPlayer bot) {
        bot.xxa = 0.0F;
        bot.zza = 0.0F;
        bot.setJumping(false);
        bot.travel(Vec3.ZERO);
    }
}
