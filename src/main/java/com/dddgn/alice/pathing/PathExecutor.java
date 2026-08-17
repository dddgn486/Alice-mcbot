package com.dddgn.alice.pathing;

import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * 路径跟随器(移植自 Baritone PathExecutor 的思路,M 阶段简化版)。
 * <p>
 * 沿 A* 路径逐段行走:每段用输入模拟(面向 + zza 前进 + 一阶跳),段间自然过渡
 * (下台阶靠重力,上台阶靠跳跃);卡住超时判失败。</p>
 */
public final class PathExecutor {

    public enum Status { MOVING, DONE, FAILED }

    private static final double SEGMENT_ARRIVE = 0.5D;
    private static final int NO_PROGRESS_LIMIT = 40;

    private final ServerPlayer bot;
    private final List<BlockPos> path;
    private int index;
    private BlockPos segmentGoal;
    private int jumpTicks;
    private Vec3Cache lastPos = new Vec3Cache();
    private int noProgressTicks;

    public PathExecutor(ServerPlayer bot, List<BlockPos> path) {
        this.bot = bot;
        this.path = path;
        this.index = 0;
    }

    public Status tick() {
        if (index >= path.size()) {
            stopInput();
            return Status.DONE;
        }
        if (segmentGoal == null) {
            segmentGoal = path.get(index);
            BotLog.info("路径段 {}/{}: {}", index + 1, path.size(), segmentGoal.toShortString());
        }

        double dx = segmentGoal.getX() + 0.5D - bot.getX();
        double dz = segmentGoal.getZ() + 0.5D - bot.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double dy = segmentGoal.getY() - bot.getY();

        // 到达当前段(水平够近 + 高度差在可接受范围)
        if (horizontal <= SEGMENT_ARRIVE && Math.abs(dy) <= 1.5D) {
            index++;
            segmentGoal = null;
            return tick(); // 立即推进下一段(或完成)
        }

        // 面向段目标
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        bot.setYRot(yaw);
        bot.yRotO = yaw;

        // 前进输入
        bot.zza = 1.0F;

        // 上台阶(段目标高一格):前方有墙且落地时点跳一次(jumpTicks 归零后可再跳)
        boolean wall = dy > 0.5D;
        if (wall) {
            var level = bot.serverLevel();
            BlockPos frontFoot = bot.blockPosition().relative(bot.getDirection());
            wall = !level.getBlockState(frontFoot).isAir()
                    || !level.getBlockState(frontFoot.above()).isAir();
        }
        if (wall && bot.onGround() && jumpTicks == 0) {
            jumpTicks = 3;
        }
        if (jumpTicks > 0) {
            bot.setJumping(true);
            jumpTicks--;
        } else {
            bot.setJumping(false);
        }

        // 卡住检测
        if (lastPos.distanceSq(bot.getX(), bot.getY(), bot.getZ()) < 0.0001D) {
            noProgressTicks++;
        } else {
            noProgressTicks = 0;
        }
        lastPos.set(bot.getX(), bot.getY(), bot.getZ());
        if (noProgressTicks > NO_PROGRESS_LIMIT) {
            stopInput();
            return Status.FAILED;
        }
        return Status.MOVING;
    }

    private void stopInput() {
        bot.zza = 0.0F;
        bot.xxa = 0.0F;
        bot.setJumping(false);
        bot.setDeltaMovement(0.0D, bot.getDeltaMovement().y, 0.0D);
    }

    /** 轻量位置缓存,避免每 tick 分配 Vec3。 */
    private static final class Vec3Cache {
        private double x, y, z;

        void set(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        double distanceSq(double x, double y, double z) {
            double dx = this.x - x;
            double dy = this.y - y;
            double dz = this.z - z;
            return dx * dx + dy * dy + dz * dz;
        }
    }
}
