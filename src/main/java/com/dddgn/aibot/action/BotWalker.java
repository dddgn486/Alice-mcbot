package com.dddgn.aibot.action;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 直线行走控制器(M0 简化版)。
 * <p>
 * 通过 {@code xxa}/{@code zza} 输入 + {@code setJumping} 驱动假人真实物理移动
 * (1.20.1 移动输入在 LivingEntity 层),支持平地直线 + 一阶跳跃;卡住超时判失败。</p>
 * <p>
 * ⚠️ 审查点 2:M0 只用直线行走(目标旁 2 格内站位),真实 A* 寻路留给 M3;
 * 输入模拟(像人、走真实物理) vs 直接传送(快但不像人)——已定输入模拟。</p>
 */
public final class BotWalker {

    public enum Status { MOVING, DONE, FAILED }

    private static final double ARRIVE_THRESHOLD = 0.6D;
    private static final int NO_PROGRESS_LIMIT = 40;

    private final ServerPlayer bot;
    private final Vec3 goal;
    private Vec3 lastPos;
    private int noProgressTicks;
    private boolean jumpRequested;
    private int jumpTicks;

    public BotWalker(ServerPlayer bot, Vec3 goal) {
        this.bot = bot;
        this.goal = goal;
        this.lastPos = bot.position();
    }

    public Status tick() {
        double dx = goal.x - bot.getX();
        double dz = goal.z - bot.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        // 到达(水平距离足够近即可)
        if (horizontal <= ARRIVE_THRESHOLD) {
            stop();
            return Status.DONE;
        }

        // 面向目标(MC yaw:0=+Z 且顺时针为正,故 yaw = atan2(-dx, dz);
        // 反例:目标在 +X(东) 时需 yaw=-90°,而 atan2(dx,dz) 会给出 +90°=朝西,方向恰好相反)
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        bot.setYRot(yaw);
        bot.yRotO = yaw;

        // 前进输入(1.20.1 每 tick 由 aiStep 衰减,需持续重设)
        bot.zza = 1.0F;

        // 一阶跳跃:前方脚位有方块且头位空 → 点跳一次
        ServerLevel level = (ServerLevel) bot.level();
        BlockPos frontFoot = bot.blockPosition().relative(bot.getDirection());
        BlockState frontFootState = level.getBlockState(frontFoot);
        BlockState frontHeadState = level.getBlockState(frontFoot.above());
        boolean wall = !frontFootState.isAir() && frontFootState.getFluidState().isEmpty();

        if (wall && frontHeadState.isAir() && bot.onGround() && !jumpRequested) {
            jumpRequested = true;
            jumpTicks = 3;
        }
        if (jumpTicks > 0) {
            bot.setJumping(true);
            jumpTicks--;
        } else {
            bot.setJumping(false);
        }

        // 卡住检测
        if (bot.position().distanceTo(lastPos) < 0.01D) {
            noProgressTicks++;
        } else {
            noProgressTicks = 0;
        }
        lastPos = bot.position();
        if (noProgressTicks > NO_PROGRESS_LIMIT) {
            stop();
            return Status.FAILED;
        }
        return Status.MOVING;
    }

    private void stop() {
        bot.zza = 0.0F;
        bot.xxa = 0.0F;
        bot.setJumping(false);
        bot.setDeltaMovement(0.0D, bot.getDeltaMovement().y, 0.0D);
    }
}
