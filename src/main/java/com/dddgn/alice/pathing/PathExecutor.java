package com.dddgn.alice.pathing;

import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.movement.BasicMovement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * 路径跟随器（重构版，使用原版物理引擎）。
 * 
 * <h3>设计原则</h3>
 * <ul>
 *   <li>✅ 使用 BasicMovement（原版 travel() + 物理引擎）</li>
 *   <li>✅ 自然碰撞检测、重力、摩擦力</li>
 *   <li>✅ 正确的到达判定（isStandingAtFootPos）</li>
 *   <li>✅ 物理结算（settle）</li>
 * </ul>
 * 
 * <h3>与旧版本的区别</h3>
 * <pre>
 * ❌ 旧版本：手动 setPos() 传送，绕过物理引擎
 * ✅ 新版本：使用原版物理，自然移动
 * </pre>
 * 
 * @see BasicMovement
 * @see MovementHelper
 */
public final class PathExecutor {

    public static final MovementMode MODE = MovementMode.HARD_PATH;

    public enum Status { MOVING, DONE, FAILED }

    private static final double SEGMENT_ARRIVE = 0.3D;
    private static final int NO_PROGRESS_LIMIT = 80;
    private static final int MAX_SETTLE_TICKS = 30;

    private final ServerPlayer bot;
    private final List<BlockPos> path;
    private int index;
    private BlockPos segmentGoal;
    private double lastX, lastY, lastZ;
    private int noProgressTicks;
    private int diagTicks;
    private int settleTicks;
    private boolean obstructed;

    public PathExecutor(ServerPlayer bot, List<BlockPos> path) {
        this.bot = bot;
        this.path = path;
        this.lastX = bot.getX();
        this.lastY = bot.getY();
        this.lastZ = bot.getZ();
    }

    /** 是否因路径中方块变化(新方块挡住段目标)而中断——上层应重新寻路而非直接失败。 */
    public boolean wasObstructed() {
        return obstructed;
    }

    public Status tick() {
        if (index >= path.size()) {
            return Status.DONE;
        }
        if (segmentGoal == null) {
            segmentGoal = path.get(index);
            settleTicks = 0;
            BotLog.info("路径段 {}/{}: {}", index + 1, path.size(), segmentGoal.toShortString());
        }

        ServerLevel level = (ServerLevel) bot.level();

        // 路径动态变化:段目标格(脚位/头位)被新方块占据 → 中断, 请求重规划
        if (!MovementHelper.canWalkThrough(level, segmentGoal)
                || !MovementHelper.canWalkThrough(level, segmentGoal.above())) {
            obstructed = true;
            BotLog.warn("路径受阻: 段目标 {} 不可走(方块变化), 请求重新规划", segmentGoal.toShortString());
            return Status.FAILED;
        }

        double goalX = segmentGoal.getX() + 0.5D;
        double goalZ = segmentGoal.getZ() + 0.5D;
        double dx = goalX - bot.getX();
        double dz = goalZ - bot.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        // 诊断:每 40 tick 输出当前位置与段距离
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

        // 到达当前段：使用物理判定，而不是手动 setPos()
        if (horizontal <= SEGMENT_ARRIVE) {
            // ✅ 检查是否真正站稳（物理结算完成）
            boolean settled = MovementHelper.isStandingAtFootPos(level, bot, segmentGoal) 
                           && bot.onGround();
            if (settled) {
                index++;
                segmentGoal = null;
                settleTicks = 0;
                return tick(); // 立即推进下一段(或完成)
            }
            
            // ✅ 物理结算：让 travel() 处理重力、摩擦和落地
            BasicMovement.settle(bot);
            
            if (++settleTicks > MAX_SETTLE_TICKS) {
                BotLog.warn("路径段 {} 物理结算超时: settleTicks={}, onGround={}, standing={}",
                        segmentGoal.toShortString(), settleTicks, bot.onGround(),
                        MovementHelper.isStandingAtFootPos(level, bot, segmentGoal));
                return Status.FAILED;
            }
            return Status.MOVING;
        }

        // ✅ 朝段目标移动：使用原版物理引擎
        BasicMovement.applyToward(bot, goalX, goalZ);

        // 卡住检测
        double moved = Math.abs(bot.getX() - lastX) + Math.abs(bot.getZ() - lastZ);
        if (moved < 0.0001D) {
            noProgressTicks++;
            if (noProgressTicks > NO_PROGRESS_LIMIT) {
                BotLog.warn("路径执行失败: 卡住 {} tick, 位置 ({}, {}, {})",
                        noProgressTicks,
                        String.format(java.util.Locale.ROOT, "%.2f", bot.getX()),
                        String.format(java.util.Locale.ROOT, "%.2f", bot.getY()),
                        String.format(java.util.Locale.ROOT, "%.2f", bot.getZ()));
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
