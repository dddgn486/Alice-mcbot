package com.dddgn.alice.pathing;

import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * 路径跟随器(服务端假人版,参考 mc_aiplayer 的 FakePlayerMotion 思路)。
 * <p>
 * <b>关键机制</b>:服务端 {@code ServerPlayer} 的移动/重力是「客户端权威」——
 * 真实玩家靠客户端发包驱动,假人没有客户端,服务端不跑 travel(重力不生效)。
 * 因此本执行器用<b>手动位置步进</b>:每 tick 朝段目标推进一小段,
 * 到段后对齐 Y 并手动置 onGround(否则落地/拾取判定永远挂起)。</p>
 * <p>A* 已保证路径各段可走(canWalkThrough/canWalkOn),步进不会穿墙。</p>
 */
public final class PathExecutor {

    public enum Status { MOVING, DONE, FAILED }

    private static final double SEGMENT_ARRIVE = 0.3D;
    private static final double STEP_SPEED = 0.25D;   // 格/tick(约 4 tick 一格)
    private static final int NO_PROGRESS_LIMIT = 80;

    private final ServerPlayer bot;
    private final List<BlockPos> path;
    private int index;
    private BlockPos segmentGoal;
    private double lastX, lastY, lastZ;
    private int noProgressTicks;
    private int diagTicks;

    public PathExecutor(ServerPlayer bot, List<BlockPos> path) {
        this.bot = bot;
        this.path = path;
        this.lastX = bot.getX();
        this.lastY = bot.getY();
        this.lastZ = bot.getZ();
    }

    public Status tick() {
        if (index >= path.size()) {
            return Status.DONE;
        }
        if (segmentGoal == null) {
            segmentGoal = path.get(index);
            BotLog.info("路径段 {}/{}: {}", index + 1, path.size(), segmentGoal.toShortString());
        }

        double goalX = segmentGoal.getX() + 0.5D;
        double goalZ = segmentGoal.getZ() + 0.5D;
        double dx = goalX - bot.getX();
        double dz = goalZ - bot.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        // 诊断:每 40 tick 输出当前位置与段距离(定位多段路径卡住问题)
        diagTicks++;
        if (diagTicks % 40 == 0) {
            BotLog.info("路径诊断: bot=({}, {}, {}) 段{}/{} goal={} 水平距离 {:.1f} 无进展 {}",
                    String.format(java.util.Locale.ROOT, "%.2f", bot.getX()),
                    String.format(java.util.Locale.ROOT, "%.2f", bot.getY()),
                    String.format(java.util.Locale.ROOT, "%.2f", bot.getZ()),
                    index + 1, path.size(),
                    segmentGoal == null ? "-" : segmentGoal.toShortString(),
                    horizontal, noProgressTicks);
        }

        // 到达当前段:对齐到段目标脚位(含 Y),手动着地
        if (horizontal <= SEGMENT_ARRIVE) {
            bot.setPos(goalX, segmentGoal.getY(), goalZ);
            bot.setOnGround(true);
            bot.fallDistance = 0.0F;
            index++;
            segmentGoal = null;
            return tick(); // 立即推进下一段(或完成)
        }

        // 朝段目标水平步进(保持当前 Y,段间 Y 差在到达时对齐)
        double step = Math.min(STEP_SPEED, horizontal);
        bot.setPos(bot.getX() + dx / horizontal * step, bot.getY(), bot.getZ() + dz / horizontal * step);

        // 卡住检测
        double moved = Math.abs(bot.getX() - lastX) + Math.abs(bot.getZ() - lastZ);
        if (moved < 0.0001D) {
            noProgressTicks++;
            if (noProgressTicks > NO_PROGRESS_LIMIT) {
                return Status.FAILED;
            }
        } else {
            noProgressTicks = 0;
        }
        lastX = bot.getX();
        lastY = bot.getY();
        lastZ = bot.getZ();
        return Status.MOVING;
    }
}
