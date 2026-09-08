package com.dddgn.alice.pathing;

import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.movement.Movement;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Movement 路径执行器（Phase 2B）。
 * 
 * <h3>设计原则</h3>
 * <ul>
 *   <li>✅ 使用 Movement 对象执行路径</li>
 *   <li>✅ Movement 自己负责移动逻辑</li>
 *   <li>✅ PathExecutor 只负责调度</li>
 * </ul>
 * 
 * <h3>与旧版本的区别</h3>
 * <pre>
 * ❌ 旧版本：执行 List&lt;BlockPos&gt;，使用 BasicMovement
 * ✅ 新版本：执行 List&lt;Movement&gt;，使用 Movement.tick()
 * </pre>
 */
public final class MovementPathExecutor {

    public enum Status { MOVING, DONE, FAILED }

    private static final int NO_PROGRESS_LIMIT = 100;  // Movement 可能需要更多 tick

    private Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> captureCollisionBaseline(List<Movement> moves) {
        Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> baseline = new HashMap<>();
        if (moves.isEmpty() || !(bot.level() instanceof net.minecraft.server.level.ServerLevel level)) return baseline;
        Movement first = moves.get(0);
        Movement last = moves.get(moves.size() - 1);
        int minX = Math.min(first.from().getX(), last.to().getX()) - 1;
        int maxX = Math.max(first.from().getX(), last.to().getX()) + 1;
        int minY = Math.min(first.from().getY(), last.to().getY());
        int maxY = Math.max(first.from().getY(), last.to().getY()) + 2;
        int minZ = Math.min(first.from().getZ(), last.to().getZ()) - 1;
        int maxZ = Math.max(first.from().getZ(), last.to().getZ()) + 1;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
                    baseline.put(pos, level.getBlockState(pos));
                }
            }
        }
        return baseline;
    }

    private String detectDynamicBlock(Movement current) {
        if (!(bot.level() instanceof net.minecraft.server.level.ServerLevel level)) return null;
        net.minecraft.world.phys.AABB entityBox = bot.getBoundingBox().inflate(0.05D);
        for (Map.Entry<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> entry
                : initialCollisionStates.entrySet()) {
            net.minecraft.core.BlockPos pos = entry.getKey();
            net.minecraft.world.level.block.state.BlockState before = entry.getValue();
            net.minecraft.world.level.block.state.BlockState now = level.getBlockState(pos);
            if (!before.getCollisionShape(level, pos).isEmpty() || now.getCollisionShape(level, pos).isEmpty()) continue;
            if (!now.getCollisionShape(level, pos).bounds().move(pos.getX(), pos.getY(), pos.getZ()).intersects(entityBox)) continue;
            dynamicObstacle = pos;
            return "BLOCKED_DYNAMIC";
        }
        return null;
    }

    private final ServerPlayer bot;
    private final List<Movement> movements;
    private int index;
    private int noProgressTicks;
    private int diagTicks;
    private String failureReason = "";
    private final Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> initialCollisionStates;
    private net.minecraft.core.BlockPos dynamicObstacle;

    public MovementPathExecutor(ServerPlayer bot, List<Movement> movements) {
        this.bot = bot;
        this.movements = List.copyOf(movements);
        this.index = 0;
        this.initialCollisionStates = captureCollisionBaseline(this.movements);
    }

    public String failureReason() {
        return failureReason;
    }

    public String failureClass() {
        return failureReason.startsWith("movement_blocked_dynamic_")
                ? "BLOCKED_DYNAMIC"
                : failureReason.startsWith("movement_timeout_") ? "MOVEMENT_TIMEOUT" : "UNKNOWN";
    }

    public net.minecraft.core.BlockPos dynamicObstacle() {
        return dynamicObstacle;
    }

    public int noProgressTicks() {
        return noProgressTicks;
    }

    public Status tick() {
        if (index >= movements.size()) {
            if (bot instanceof com.dddgn.alice.bot.BotPlayer botPlayer) {
                botPlayer.controller().stopMovement();
            }
            return Status.DONE;
        }

        Movement current = movements.get(index);

        // 诊断：每 40 tick 输出进度
        diagTicks++;
        if (diagTicks % 40 == 0) {
            BotLog.info("Movement 执行: {}/{} type={} from={} to={} bot=({}, {}, {})",
                    index + 1, movements.size(),
                    current.getClass().getSimpleName(),
                    current.from().toShortString(),
                    current.to().toShortString(),
                    String.format(java.util.Locale.ROOT, "%.2f", bot.getX()),
                    String.format(java.util.Locale.ROOT, "%.2f", bot.getY()),
                    String.format(java.util.Locale.ROOT, "%.2f", bot.getZ()));
        }

        // 执行当前 Movement
        Movement.Status status = current.tick(bot);

        switch (status) {
            case SUCCESS -> {
                BotLog.info("Movement 完成: {}/{} type={} to={}",
                        index + 1, movements.size(),
                        current.getClass().getSimpleName(),
                        current.to().toShortString());
                index++;
                noProgressTicks = 0;
                return Status.MOVING;  // 继续下一个 Movement
            }
            case FAILED -> {
                if (bot instanceof com.dddgn.alice.bot.BotPlayer botPlayer) {
                    botPlayer.controller().stopMovement();
                }
                failureReason = "movement_failed_" + current.getClass().getSimpleName();
                BotLog.warn("Movement 失败: {}/{} type={} from={} to={}",
                        index + 1, movements.size(),
                        current.getClass().getSimpleName(),
                        current.from().toShortString(),
                        current.to().toShortString());
                return Status.FAILED;
            }
            case RUNNING -> {
                // Movement 还在执行中
                noProgressTicks++;
                if (noProgressTicks > NO_PROGRESS_LIMIT) {
                    String dynamicReason = detectDynamicBlock(current);
                    failureReason = dynamicReason == null
                            ? "movement_timeout_" + current.getClass().getSimpleName()
                            : "movement_blocked_dynamic_" + current.getClass().getSimpleName();
                    if (bot instanceof com.dddgn.alice.bot.BotPlayer botPlayer) {
                        botPlayer.controller().stopMovement();
                    }
                    BotLog.warn("Movement {}: {}/{} type={} noProgress={} obstacle={} cleanup=controller_stop_movement",
                            dynamicReason == null ? "超时" : "动态阻挡",
                            index + 1, movements.size(),
                            current.getClass().getSimpleName(),
                            noProgressTicks,
                            dynamicObstacle == null ? "-" : dynamicObstacle.toShortString());
                    return Status.FAILED;
                }
                return Status.MOVING;
            }
        }

        return Status.MOVING;
    }
}
