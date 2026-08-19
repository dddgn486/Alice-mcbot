package com.dddgn.alice.task;

import com.dddgn.alice.action.BotMiner;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.AStarPathfinder;
import com.dddgn.alice.pathing.Goal;
import com.dddgn.alice.pathing.PathExecutor;
import com.dddgn.alice.protection.BlockBreakSafety;
import com.dddgn.alice.perception.ScopeBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 挖矿任务(任务框架第一个实现,对应验收标准 3「产物入包」)。
 * <p>
 * 行为链:MINING(挖掘状态机,含站位/视线/距离检查,<b>最多两格直接清障</b>)
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
    /** 只有掉落物实时离 bot 超过这个 3D 距离，才允许放弃它。 */
    private static final double MAX_COLLECT_DISTANCE = 64.0D;
    /** 必须站到掉落物脚位格中心附近才允许拾取；不再以原版吸取半径代替寻路终点。 */
    private static final double PICKUP_CENTER_TOLERANCE_SQR = 0.04D;
    /** 为拾取掉落物开凿的侧向单格阶梯上限；禁止竖直下挖与自由落下。 */
    private static final int MAX_COLLECT_STAIR_CLEARS = 8;
    /** 单目标任务只允许处理极短、直接可见的局部遮挡；深埋目标交给未来 TunnelPlanner。 */
    private static final int MAX_CLEAR_DEPTH = 2;
    private static final double MAX_CLEAR_REACH = 4.5D;

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
    /** 已明确超出 64 格而放弃的掉落物；每 tick 重新测距，避免旧判定影响新位置。 */
    private final Set<UUID> abandonedItems = new HashSet<>();
    private UUID collectingItemId;
    private BlockPos collectingItemPos;
    /** 原始目标产物的 UUID 队列；清障副产物不得抢占主目标。 */
    private final Set<UUID> primaryItemIds = new HashSet<>();
    private boolean primaryItemsCaptured;
    private int primaryCaptureWait;
    /** 拾取下行阶梯当前正挖的侧向方块；完成后重新规划到掉落物。 */
    private BotMiner collectStairMiner;
    private int collectStairClears;
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
                    // 拾取阶段按原始目标方块格锁定主产物，不依赖 ItemEntity 事件先后。
                    phase = Phase.COLLECTING;
                    return Status.RUNNING;
                }
                case FAILED -> {
                    // 明确目标的硬拒绝必须立即终止；脚下目标不在此列，BotMiner 会先换侧面站位。
                    String minerFailure = miner.failureReason();
                    if (isHardTargetRefusal(minerFailure)) {
                        failureReason = minerFailure;
                        return Status.FAILED;
                    }
                    // 单目标只处理当前站位直接可见、可达的最多两格局部遮挡；不能把
                    // MineTask 退化成反复重选站位的长通道施工器。
                    BlockPos blocker = findDirectBlocker();
                    if (blocker != null && clearDepth < MAX_CLEAR_DEPTH && !blocker.equals(currentMineTarget)) {
                        String refusalReason = BlockBreakSafety.clearingRefusal(bot, blocker);
                        if (refusalReason != null) {
                            failureReason = refusalReason;
                            BotLog.warn("清障被安全策略拦截: blocker={} originalTarget={} reason={}",
                                    blocker.toShortString(), target.toShortString(), failureReason);
                            return Status.FAILED;
                        }
                        clearDepth++;
                        BotLog.info("局部清障({}/{}): 当前站位直接挖 {}", clearDepth,
                                MAX_CLEAR_DEPTH, blocker.toShortString());
                        startMining(blocker);
                        return Status.RUNNING;
                    }
                    failureReason = "target_requires_tunnel";
                    BotLog.info("目标需要独立通道规划: target={} minerFailure={} clearDepth={}/{}",
                            target.toShortString(), minerFailure, clearDepth, MAX_CLEAR_DEPTH);
                    return Status.FAILED;
                }
                default -> {
                    return Status.RUNNING;
                }
            }
        }
        return collectTick();
    }

    private static boolean isHardTargetRefusal(String reason) {
        return "unbreakable_block".equals(reason) || reason.startsWith("protected_");
    }

    /** 切换当前挖掘目标(原目标或遮挡方块)。 */
    private void startMining(BlockPos pos) {
        this.currentMineTarget = pos.immutable();
        this.miner = new BotMiner(bot, this.currentMineTarget);
    }

    /** 当前站位到原目标射线上的第一格遮挡；必须在原版挖掘距离内。 */
    private BlockPos findDirectBlocker() {
        net.minecraft.world.phys.Vec3 eye = bot.getEyePosition();
        BlockPos blocker = raycastBlock(eye, target.getCenter());
        if (blocker == null || blocker.equals(target)
                || eye.distanceTo(blocker.getCenter()) > MAX_CLEAR_REACH) {
            return null;
        }
        // 射线第一命中即是当前可见 blocker；不递归向远处延展为通道。
        return blocker;
    }

    /** raycast 第一个命中的方块(无命中返回 null)。 */
    private BlockPos raycastBlock(net.minecraft.world.phys.Vec3 from, net.minecraft.world.phys.Vec3 to) {
        net.minecraft.world.level.ClipContext ctx = new net.minecraft.world.level.ClipContext(from, to,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, bot);
        net.minecraft.world.phys.BlockHitResult hit = bot.level().clip(ctx);
        if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            return hit.getBlockPos().immutable();
        }
        return null;
    }

    /** 拾取阶段:直接扫描目标周围掉落物(不依赖事件捕捉) → 走位 → 原版拾取入包。 */
    private Status collectTick() {
        collectElapsed++;
        if (collectElapsed > COLLECT_TIMEOUT_TICKS) {
            failureReason = "collect_timeout";
            BotLog.warn("拾取失败: target={} reason={} (仍有近距离掉落物未确认入包)",
                    target.toShortString(), failureReason);
            return Status.FAILED;
        }
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) bot.level();
        // 采集任务只追踪显式目标方块产生的掉落物；清障副产物、遗留物和其他任务掉落物
        // 都不属于本任务的收集范围。
        if (!primaryItemsCaptured) {
            primaryCaptureWait++;
            for (ItemEntity item : scope.liveItemsFromOrigin(target)) {
                primaryItemIds.add(item.getUUID());
            }
            if (!primaryItemIds.isEmpty()) {
                primaryItemsCaptured = true;
                BotLog.info("主目标产物锁定: {} 个 origin={}",
                        primaryItemIds.size(), target.toShortString());
            } else if (primaryCaptureWait < 30) {
                // 掉落物可能延迟生成（被推远/尚未被作用域捕捉），最多等 30 tick；
                // 不能立即空捕获而漏掉真正属于目标的产物。
                return Status.RUNNING;
            } else {
                primaryItemsCaptured = true;
                BotLog.info("主目标 {} 无产物可收集", target.toShortString());
            }
        }
        List<ItemEntity> items = scope.liveItemsFromOrigin(target).stream()
                .filter(item -> primaryItemIds.contains(item.getUUID())).toList();
        if (items.isEmpty()) {
            BotLog.info("拾取阶段完成: 主动目标 {} 的掉落物已全部入包/消失", target.toShortString());
            return Status.DONE;
        }

        // 每 tick 按真实实体位置刷新三维距离：只有超过 64 格的单个掉落物才允许放弃。
        ItemEntity nearest = null;
        double best = Double.MAX_VALUE;
        // 当前目标保持粘性，避免多件物品距离接近时 UUID 来回切换、等待计时永远归零。
        if (collectingItemId != null) {
            for (ItemEntity item : items) {
                if (item.getUUID().equals(collectingItemId)) {
                    double d = bot.distanceToSqr(item);
                    if (d <= MAX_COLLECT_DISTANCE * MAX_COLLECT_DISTANCE) {
                        nearest = item;
                        best = d;
                    }
                    break;
                }
            }
        }
        boolean stickyTarget = nearest != null;
        for (ItemEntity item : items) {
            double d = bot.distanceToSqr(item);
            if (d > MAX_COLLECT_DISTANCE * MAX_COLLECT_DISTANCE) {
                if (abandonedItems.add(item.getUUID())) {
                    BotLog.warn("拾取放弃: item@{} distance={} 超过 {} 格三维阈值",
                            item.blockPosition().toShortString(),
                            String.format(java.util.Locale.ROOT, "%.1f", Math.sqrt(d)), MAX_COLLECT_DISTANCE);
                }
                continue;
            }
            if (!stickyTarget && d < best) {
                best = d;
                nearest = item;
            }
        }
        if (nearest == null) {
            BotLog.info("拾取阶段完成: 剩余掉落物均超过 {} 格三维距离", MAX_COLLECT_DISTANCE);
            return Status.DONE;
        }
        BlockPos nearestPos = nearest.blockPosition().immutable();
        if (!nearest.getUUID().equals(collectingItemId) || !nearestPos.equals(collectingItemPos)) {
            collectingItemId = nearest.getUUID();
            collectingItemPos = nearestPos;
            collectExecutor = null;
            collectStairMiner = null;
            collectStairClears = 0;
            collectWaitTicks = 0;
            collectRetries = 0;
            BotLog.info("拾取目标刷新: item@{} 三维距离 {} 格", nearestPos.toShortString(),
                    String.format(java.util.Locale.ROOT, "%.1f", Math.sqrt(best)));
        }

        // 只有实际站到掉落物脚位格的格心附近才允许拾取；不能在坑边依赖 1.5 格吸取半径。
        double centerDx = bot.getX() - (nearestPos.getX() + 0.5D);
        double centerDz = bot.getZ() - (nearestPos.getZ() + 0.5D);
        boolean atItemCellCenter = bot.blockPosition().equals(nearestPos)
                && centerDx * centerDx + centerDz * centerDz <= PICKUP_CENTER_TOLERANCE_SQR;
        if (atItemCellCenter) {
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

        // 拾取下行阶梯的单动作状态机：只挖侧向斜下方方块，完成后回到普通 A*。
        if (collectStairMiner != null) {
            BotMiner.Status stairStatus = collectStairMiner.tick();
            if (stairStatus == BotMiner.Status.DONE) {
                collectStairMiner = null;
                collectExecutor = null;
                BotLog.info("拾取阶梯清障完成: 重新规划至掉落物 {}", nearest.blockPosition().toShortString());
                return Status.RUNNING;
            }
            if (stairStatus == BotMiner.Status.FAILED) {
                failureReason = "collect_stair_failed:" + collectStairMiner.failureReason();
                BotLog.warn("拾取阶梯清障失败: item@{} reason={}",
                        nearest.blockPosition().toShortString(), failureReason);
                return Status.FAILED;
            }
            return Status.RUNNING;
        }

        // 尚未落地时实体位置还在变化，不做昂贵且注定失效的 A*；等稳定后再规划。
        if (!nearest.onGround()) {
            if (collectElapsed % 20 == 0) {
                BotLog.info("拾取等待: 掉落物未落地 @{} y={}", nearest.blockPosition().toShortString(),
                        String.format(java.util.Locale.ROOT, "%.1f", nearest.getY()));
            }
            return Status.RUNNING;
        }

        // 尚未到位:寻路到掉落物实际脚位格
        if (collectExecutor == null) {
            BlockPos stand = nearest.blockPosition();
            // 拾取终点必须是掉落物实际脚位格，不能只到相邻格：坑底物品需要下坑。
            Goal.GoalBlock pickupGoal = new Goal.GoalBlock(stand);
            if (pickupGoal.isInGoal(bot.blockPosition())) {
                // 已在同一方块格但未居中：执行一个只含终点的短路径，让 PathExecutor 对齐格心。
                collectExecutor = new PathExecutor(bot, List.of(stand));
                BotLog.info("拾取居中: item@{} bot=({}, {})", stand.toShortString(),
                        String.format(java.util.Locale.ROOT, "%.2f", bot.getX()),
                        String.format(java.util.Locale.ROOT, "%.2f", bot.getZ()));
                return Status.RUNNING;
            }
            List<BlockPos> path = AStarPathfinder.computePath(level, bot.blockPosition(), pickupGoal);
            if (path.isEmpty()) {
                // 目标格不可达:只尝试上方的实际脚位格；仍不可达则任务失败，不能把近距离产物当损耗吞掉。
                path = AStarPathfinder.computePath(level, bot.blockPosition(), new Goal.GoalBlock(stand.above()));
            }
            if (path.isEmpty()) {
                BlockPos stairBlocker = findCollectStairBlocker(level, stand);
                if (stairBlocker != null) {
                    collectStairClears++;
                    collectStairMiner = new BotMiner(bot, stairBlocker);
                    BotLog.info("拾取阶梯清障({}/{}): item@{} 先挖侧向方块 {}",
                            collectStairClears, MAX_COLLECT_STAIR_CLEARS,
                            nearest.blockPosition().toShortString(), stairBlocker.toShortString());
                    return Status.RUNNING;
                }
                failureReason = "collect_no_path";
                BotLog.warn("拾取失败: item@{} 三维距离 {} 格 reason={} (近距离产物未入包)",
                        nearest.blockPosition().toShortString(),
                        String.format(java.util.Locale.ROOT, "%.1f", Math.sqrt(best)), failureReason);
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
            BotLog.warn("拾取失败: item@{} 三维距离 {} 格 reason={} (近距离产物未入包)",
                    nearest.blockPosition().toShortString(),
                    String.format(java.util.Locale.ROOT, "%.1f", Math.sqrt(best)), failureReason);
            return Status.FAILED;
        }
        if (pathStatus == PathExecutor.Status.DONE) {
            collectExecutor = null; // 到达,下一 tick 等待拾取
        }
        return Status.RUNNING;
    }

    /**
     * 普通路径到较低掉落物失败时，寻找一个可安全开凿的“侧向单格下行”方块。
     * 只允许挖 bot 侧前方的 y-1 脚位格：挖完后 A* 的 DESCEND 可合法走入；
     * 当前脚下方块绝不在候选中，因此不会形成竖井或自由落下。
     */
    private BlockPos findCollectStairBlocker(net.minecraft.server.level.ServerLevel level, BlockPos itemStand) {
        if (collectStairClears >= MAX_COLLECT_STAIR_CLEARS || itemStand.getY() >= bot.blockPosition().getY()) {
            return null;
        }
        int dxToItem = Integer.compare(itemStand.getX(), bot.blockPosition().getX());
        int dzToItem = Integer.compare(itemStand.getZ(), bot.blockPosition().getZ());
        List<BlockPos> candidates = new java.util.ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue; // 铁律：不挖自己脚下
                }
                candidates.add(bot.blockPosition().offset(dx, -1, dz));
            }
        }
        candidates.sort(java.util.Comparator.comparingInt(pos ->
                Math.abs(pos.getX() - bot.blockPosition().getX() - dxToItem)
                        + Math.abs(pos.getZ() - bot.blockPosition().getZ() - dzToItem)));
        for (BlockPos candidate : candidates) {
            // 该格被挖掉后必须能作为脚位；头部已经净空，下面必须有稳定支撑。
            if (level.getBlockState(candidate).isAir()
                    || !level.getBlockState(candidate.above()).getCollisionShape(level, candidate.above()).isEmpty()
                    || level.getBlockState(candidate.below()).getCollisionShape(level, candidate.below()).isEmpty()
                    || !level.getBlockState(candidate).getFluidState().isEmpty()
                    || !level.getBlockState(candidate.below()).getFluidState().isEmpty()) {
                continue;
            }
            String refusal = BlockBreakSafety.clearingRefusal(bot, candidate);
            if (refusal != null) {
                BotLog.info("拾取阶梯避障: 不挖 {} reason={}", candidate.toShortString(), refusal);
                continue;
            }
            return candidate;
        }
        return null;
    }

    /** 反射读取私有字段 pickupDelay(诊断用). */
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
