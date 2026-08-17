package com.dddgn.alice.task;

import com.dddgn.alice.action.BotMiner;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.AStarPathfinder;
import com.dddgn.alice.pathing.Goal;
import com.dddgn.alice.pathing.PathExecutor;
import com.dddgn.alice.perception.ScopeBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * 挖矿任务(任务框架第一个实现,对应验收标准 3「产物入包」)。
 * <p>
 * 行为链:MINING({@link BotMiner} 原版挖掘状态机,含站位/视线/距离检查)
 * → COLLECTING(感知层 {@link ScopeBuffer} 定位任务作用域内的掉落物,
 * A* 走到最近掉落物旁,由原版实体拾取逻辑入包) → DONE。
 * </p>
 * <p>
 * 临时设定(用户指定):任务开始时直接替换 bot 主手为钻石镐,结束后不换回,
 * 等真正的装备系统(后续里程碑)接管。</p>
 */
public final class MineTask implements Task {

    private enum Phase { MINING, COLLECTING }

    private static final int COLLECT_TIMEOUT_TICKS = 400;

    private final ServerPlayer bot;
    private final BlockPos target;
    private final ScopeBuffer scope;

    private final BotMiner miner;
    private Phase phase = Phase.MINING;
    private PathExecutor collectExecutor;
    private int collectElapsed;
    private int collectWaitTicks;
    private String failureReason = "";

    public MineTask(ServerPlayer bot, BlockPos target, ScopeBuffer scope) {
        this.bot = bot;
        this.target = target.immutable();
        this.scope = scope;
        this.miner = new BotMiner(bot, this.target);
        // 临时设定:行为执行时直接替换主手工具(挖矿 → 钻石镐),并同步给客户端
        bot.getInventory().setItem(bot.getInventory().selected, new ItemStack(Items.DIAMOND_PICKAXE));
        com.dddgn.alice.bot.BotManager.syncMainHand(bot);
        BotLog.info("任务创建: MineTask target={}", this.target.toShortString());
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(target);
    }

    /** 挖掘开始时的 bot 位置(隔空挖验收断言用;转发 BotMiner)。 */
    public BlockPos mineStartPos() {
        return miner.mineStartPos();
    }

    @Override
    public String failureReason() {
        return failureReason;
    }

    @Override
    public Status tick() {
        if (phase == Phase.MINING) {
            BotMiner.Status s = miner.tick();
            switch (s) {
                case DONE -> {
                    BotLog.info("挖掘阶段完成,进入拾取阶段: target={}", target.toShortString());
                    phase = Phase.COLLECTING;
                    return Status.RUNNING;
                }
                case FAILED -> {
                    failureReason = miner.failureReason();
                    return Status.FAILED;
                }
                default -> {
                    return Status.RUNNING;
                }
            }
        }
        return collectTick();
    }

    /** 拾取阶段:直接扫描目标周围掉落物(不依赖事件捕捉) → 走位 → 原版拾取入包。 */
    private Status collectTick() {
        collectElapsed++;
        if (collectElapsed > COLLECT_TIMEOUT_TICKS) {
            failureReason = "collect_timeout";
            BotLog.warn("拾取失败: target={} reason={}", target.toShortString(), failureReason);
            return Status.FAILED;
        }
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) bot.level();
        // 感知:扫描目标为中心 8 格内的存活掉落物
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class,
                new net.minecraft.world.phys.AABB(target).inflate(8.0D),
                item -> item.isAlive() && !item.getItem().isEmpty());
        if (items.isEmpty()) {
            BotLog.info("拾取阶段完成: 目标周围无掉落物(全部入包/消失)");
            return Status.DONE;
        }

        // 取最近的掉落物作为拾取目标
        ItemEntity nearest = items.get(0);
        double best = Double.MAX_VALUE;
        for (ItemEntity item : items) {
            double d = bot.distanceToSqr(item);
            if (d < best) {
                best = d;
                nearest = item;
            }
        }

        // 已在拾取半径内:等原版 playerTouch 入包(掉落物有 pickupDelay,需等)
        if (best <= 2.25D) { // 1.5 格
            collectWaitTicks++;
            // 主动拾取兜底:原版 Player.tick 触碰检测对玩家化假人偶发不触发,
            // 等待 20 tick 仍没入包则反射清 pickupDelay 后直接 playerTouch
            if (collectWaitTicks >= 20) {
                forcePickup(nearest);
                if (nearest.isRemoved() || nearest.getItem().isEmpty()) {
                    BotLog.info("拾取成功(主动 playerTouch): 掉落物已入包");
                    collectWaitTicks = 0;
                    return Status.RUNNING;
                }
                if (collectWaitTicks % 80 == 0) {
                    int used = 0;
                    for (var s : bot.getInventory().items) {
                        if (!s.isEmpty()) {
                            used++;
                        }
                    }
                    BotLog.warn("拾取未成功持续: 背包已用 {}/36 格(可能背包满导致 Inventory.add 失败)", used);
                }
            }
            if (collectWaitTicks % 40 == 0) {
                int delay = readPickupDelay(nearest);
                BotLog.info("拾取等待中: 掉落物 {} @{} 距离 {} 格 pickupDelay={} age={}",
                        nearest.getItem().getItem(), nearest.blockPosition().toShortString(),
                        String.format(java.util.Locale.ROOT, "%.1f", Math.sqrt(best)),
                        delay, nearest.tickCount);
            }
            return Status.RUNNING;
        }

        // 尚未到位:寻路到掉落物旁
        if (collectExecutor == null) {
            BlockPos stand = nearest.blockPosition();
            // 已在目标 1 格水平范围内(同层) → 直接进入等待拾取
            // (否则 A* 因 start 已在 goal 返回空路径,被误判 collect_no_path)
            if (new Goal.GoalNear(stand, 1).isInGoal(bot.blockPosition())) {
                collectWaitTicks++;
                if (collectWaitTicks >= 20) {
                    forcePickup(nearest); // 兜底:等待超过 20 tick 仍未入包则主动拾取
                }
                return Status.RUNNING;
            }
            List<BlockPos> path = AStarPathfinder.computePath(level,
                    bot.blockPosition(), new Goal.GoalNear(stand, 1));
            if (path.isEmpty()) {
                // 目标格不可达:尝试掉落物上方一格(掉落在非平地时兜底)
                path = AStarPathfinder.computePath(level,
                        bot.blockPosition(), new Goal.GoalNear(stand.above(), 1));
            }
            if (path.isEmpty()) {
                failureReason = "collect_no_path";
                BotLog.warn("拾取失败: target={} item@{} reason={}",
                        target.toShortString(), nearest.blockPosition().toShortString(), failureReason);
                return Status.FAILED;
            }
            collectExecutor = new PathExecutor(bot, path);
            BotLog.info("拾取寻路: 掉落物@{} 路径 {} 段", nearest.blockPosition().toShortString(), path.size());
        }

        PathExecutor.Status pathStatus = collectExecutor.tick();
        if (pathStatus == PathExecutor.Status.FAILED) {
            failureReason = "collect_path_failed";
            BotLog.warn("拾取失败: target={} reason={}", target.toShortString(), failureReason);
            return Status.FAILED;
        }
        if (pathStatus == PathExecutor.Status.DONE) {
            collectExecutor = null; // 到达,下一 tick 等待拾取
        }
        return Status.RUNNING;
    }

    /** 反射读取私有字段 pickupDelay(诊断用)。 */
    private static int readPickupDelay(ItemEntity item) {
        try {
            java.lang.reflect.Field f = ItemEntity.class.getDeclaredField("pickupDelay");
            f.setAccessible(true);
            return f.getInt(item);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 主动拾取:反射清 pickupDelay 后直接调 playerTouch(绕过原版触碰检测)。 */
    private void forcePickup(ItemEntity item) {
        try {
            java.lang.reflect.Field f = ItemEntity.class.getDeclaredField("pickupDelay");
            f.setAccessible(true);
            f.setInt(item, 0);
        } catch (Exception e) {
            BotLog.warn("拾取兜底: 反射清 pickupDelay 失败 {}", e.getMessage());
        }
        item.playerTouch(bot);
    }
}
