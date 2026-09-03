package com.dddgn.alice.pathing;

import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.movement.Movement;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

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

    private final ServerPlayer bot;
    private final List<Movement> movements;
    private int index;
    private int noProgressTicks;
    private int diagTicks;
    private String failureReason = "";

    public MovementPathExecutor(ServerPlayer bot, List<Movement> movements) {
        this.bot = bot;
        this.movements = movements;
        this.index = 0;
    }

    public String failureReason() {
        return failureReason;
    }

    public Status tick() {
        if (index >= movements.size()) {
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
                    failureReason = "movement_timeout_" + current.getClass().getSimpleName();
                    BotLog.warn("Movement 超时: {}/{} type={} noProgress={}",
                            index + 1, movements.size(),
                            current.getClass().getSimpleName(),
                            noProgressTicks);
                    return Status.FAILED;
                }
                return Status.MOVING;
            }
        }

        return Status.MOVING;
    }
}
