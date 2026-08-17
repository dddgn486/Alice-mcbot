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
 * 行为链:MINING(挖掘状态机,含站位/视线/距离检查,<b>失败时清障</b>)
 * → COLLECTING(感知定位掉落物 → A* 走位 → 主动拾取入包) → DONE。
 * </p>
 * <p><b>清障挖掘</b>:原目标视线被方块遮挡(在墙里/被围)时,raycast 找出遮挡方块
 * 作为新的挖掘目标先挖掉,再回头挖原目标(限 3 层)——解决「头顶/墙内方块挖不到」。</p>
 * <p>
 * 临时设定(用户指定):任务开始时直接替换 bot 主手为钻石镐,结束后不换回,
 * 等真正的装备系统(后续里程碑)接管。</p>
 */
public final class MineTask implements Task {

    private enum Phase { MINING, COLLECTING }

    private static final int COLLECT_TIMEOUT_TICKS = 400;
    private static final int MAX_CLEAR_DEPTH = 3;

    private final ServerPlayer bot;
    private final BlockPos target;           // 原始目标(不变,清障后仍挖它)
    private final ScopeBuffer scope;

    private BotMiner miner;                  // 当前挖掘器(可能是清障目标)
    private BlockPos currentMineTarget;      // 当前挖掘目标(原目标或遮挡方块)
    private int clearDepth;
    private Phase phase = Phase.MINING;
    private PathExecutor collectExecutor;
    private int collectElapsed;
    private int collectWaitTicks;
    private int collectRetries;
    private String failureReason = "";

    public MineTask(ServerPlayer bot, BlockPos target, ScopeBuffer scope) {
        this.bot = bot;
        this.target = target.immutable();
        this.scope = scope;
        this.currentMineTarget = this.target;
        this.miner = new BotMiner(bot, this.currentMineTarget);
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
                    if (!currentMineTarget.equals(target)) {
                        // 清障完成 → 回头挖原目标
                        BotLog.info("清障完成: 已挖掉遮挡 {} → 继续挖原目标 {}",
                                currentMineTarget.toShortString(), target.toShortString());
                        startMining(target);
                        return Status.RUNNING;
                    }
                    BotLog.info("挖掘阶段完成,进入拾取阶段: target={}", target.toShortString());
                    phase = Phase.COLLECTING;
                    return Status.RUNNING;
                }
                case FAILED -> {
                    // 挖掘失败 → 尝试清障: 挖开视线上的遮挡方块(限 3 层)
                    BlockPos blocker = findLineOfSightBlocker();
                    if (blocker != null && clearDepth < MAX_CLEAR_DEPTH && !blocker.equals(currentMineTarget)) {
                        clearDepth++;
                        BotLog.info("清障(第{}层): 视线被 {} 遮挡, 先挖它", clearDepth, blocker.toShortString());
                        startMining(blocker);
                        return Status.RUNNING;
                    }
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

    /** 切换当前挖掘目标(原目标或遮挡方块)。 */
    private void startMining(BlockPos pos) {
        this.currentMineTarget = pos.immutable();
        this.miner = new BotMiner(bot, this.currentMineTarget);
    }

    /** 从 bot 眼睛到原目标中心的 raycast:返回第一个非目标的遮挡方块(无则 null)。 */
    private BlockPos findLineOfSightBlocker() {
        net.minecraft.world.phys.Vec3 eye = bot.getEyePosition();
        net.minecraft.world.phys.Vec3 center = target.getCenter();
        net.minecraft.world.level.ClipContext ctx = new net.minecraft.world.level.ClipContext(eye, center,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, bot);
        net.minecraft.world.phys.BlockHitResult hit = bot.level().clip(ctx);
        if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                && !hit.getBlockPos().equals(target)) {
            return hit.getBlockPos().immutable();
        }
        return null;
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
                // 掉落物未落地(挖高处方块后下落/弹跳中, 目标格可能悬空站不住)
                // → 等待落地再试, 不立即判失败
                if (!nearest.onGround()) {
                    BotLog.info("拾取等待: 掉落物未落地 y={}, 等落地",
                            String.format(java.util.Locale.ROOT, "%.1f", nearest.getY()));
                    return Status.RUNNING;
                }
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
            if (collectExecutor.wasObstructed() && collectRetries < 2) {
                collectRetries++;
                BotLog.warn("拾取路径受阻,重新规划({}/2): target={}", collectRetries, target.toShortString());
                collectExecutor = null; // 下 tick 重新寻路
                return Status.RUNNING;
            }
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
