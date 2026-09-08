package com.dddgn.alice.task.lumber;

import com.dddgn.alice.action.BotMiner;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.task.Task;
import com.dddgn.alice.task.TaskTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * 区域伐木任务。
 * <p>
 * 在指定的长方体区域内识别和砍伐所有树木：
 * - 扫描区域内的原木方块
 * - 识别完整的树结构
 * - 按顺序砍伐（从下往上）
 * - 收集掉落物
 * - 区域清空后任务完成
 */
public class RegionLumberTask implements Task {
    
    private enum Phase {
        SCANNING, MOVING_TO_TREE, CUTTING, COLLECTING, COMPLETED
    }
    
    private final ServerPlayer bot;
    private final AABB region;
    private final List<Tree> pendingTrees;
    private final Set<BlockPos> scannedLogs;
    private final Set<ItemEntity> trackedDrops;
    
    private Tree currentTree;
    private List<BlockPos> currentTreeLogs;
    private int currentLogIndex;
    private BotMiner miner;  // 当前的挖掘器
    private BlockPos currentMineTarget;  // 当前正在挖的目标（可能是原木或遮挡物）
    private BlockPos originalTarget;  // 原始目标原木（用于清障）
    private int clearDepth;  // 清障深度
    private static final int MAX_CLEAR_DEPTH = 3;  // 最多清3层遮挡物
    
    private int treesCut;
    private long startTime;
    private Phase phase;
    private String failureReason = "";
    
    public RegionLumberTask(ServerPlayer bot, AABB region) {
        this.bot = bot;
        this.region = region;
        this.pendingTrees = new ArrayList<>();
        this.scannedLogs = new HashSet<>();
        this.trackedDrops = new HashSet<>();
        this.currentLogIndex = 0;
        this.treesCut = 0;
        this.phase = Phase.SCANNING;
        this.startTime = System.currentTimeMillis();
        
        BotLog.info("lumber: 🪓 task created, region={}", region);
        
        // 给 Bot 装备钻石斧
        ItemStack axe = new ItemStack(Items.DIAMOND_AXE);
        bot.getInventory().setItem(bot.getInventory().selected, axe);
        BotLog.info("lumber: 🔧 equipped DIAMOND_AXE to slot {}, item={}", 
                bot.getInventory().selected, 
                bot.getInventory().getItem(bot.getInventory().selected).getItem());
        
        // 同步主手物品到客户端
        com.dddgn.alice.bot.BotManager.syncMainHand(bot);
        BotLog.info("lumber: 📡 called syncMainHand()");
        
        BotLog.info("lumber: task started, region={}", region);
        scanTrees();
    }
    
    @Override
    public TaskTarget target() {
        // 伐木任务没有单一目标，返回 null
        return null;
    }
    
    @Override
    public String failureReason() {
        return failureReason;
    }
    
    @Override
    public Status tick() {
        if (bot == null || bot.level() == null) {
            failureReason = "bot is null";
            return Status.FAILED;
        }
        
        switch (phase) {
            case SCANNING:
                return handleScanning();
            case MOVING_TO_TREE:
                return handleMovingToTree();
            case CUTTING:
                return handleCutting();
            case COLLECTING:
                return handleCollecting();
            case COMPLETED:
                return Status.DONE;
            default:
                return Status.RUNNING;
        }
    }
    
    private Status handleScanning() {
        if (pendingTrees.isEmpty()) {
            phase = Phase.COMPLETED;
            BotLog.info("lumber: all trees cut, task completed, total={}", treesCut);
            return Status.DONE;
        } else {
            selectNextTree();
            phase = Phase.MOVING_TO_TREE;
            return Status.RUNNING;
        }
    }
    
    private Status handleMovingToTree() {
        if (currentTree == null) {
            phase = Phase.SCANNING;
            return Status.RUNNING;
        }
        
        BlockPos target = currentTreeLogs.get(currentLogIndex);
        
        // 检查目标是否还存在
        if (!isLog(target)) {
            // 原木已经被破坏了（可能被玩家砍了），跳过
            currentLogIndex++;
            if (currentLogIndex >= currentTreeLogs.size()) {
                finishCurrentTree();
            }
            return Status.RUNNING;
        }
        
        // 直接进入砍伐阶段，BotMiner 会处理移动
        phase = Phase.CUTTING;
        
        return Status.RUNNING;
    }
    
    private Status handleCutting() {
        if (currentLogIndex >= currentTreeLogs.size()) {
            finishCurrentTree();
            return Status.RUNNING;
        }
        
        // 如果没有当前挖掘目标，从列表获取下一个原木
        if (currentMineTarget == null) {
            BlockPos nextLog = currentTreeLogs.get(currentLogIndex);
            
            // 检查目标是否还存在
            if (!isLog(nextLog)) {
                BotLog.warn("lumber: ⏭️ 原木已消失，跳过: index={} pos={}", 
                        currentLogIndex, nextLog.toShortString());
                currentLogIndex++;
                return Status.RUNNING;
            }
            
            currentMineTarget = nextLog;
            originalTarget = null;  // 新原木，清空原始目标
            clearDepth = 0;  // 新原木，重置清障深度
        }
        
        // 创建或重用 BotMiner
        if (miner == null) {
            miner = new BotMiner(bot, currentMineTarget);
            BotLog.info("lumber: start cutting log at {}", currentMineTarget.toShortString());
        }
        
        // 执行挖掘
        BotMiner.Status minerStatus = miner.tick();
        
        if (minerStatus == BotMiner.Status.DONE) {
            // 破坏完成，追踪掉落物
            BotLog.info("lumber: ✅ log destroyed at {}", currentMineTarget.toShortString());
            
            trackNearbyDrops(currentMineTarget);
            
            // 🆕 如果是清障完成，继续挖原目标（参考 MineTask）
            if (originalTarget != null && !currentMineTarget.equals(originalTarget)) {
                BotLog.info("lumber: 🌿 清障完成 ({}/{}): 已挖掉 {}, 继续挖原目标 {}", 
                        clearDepth, MAX_CLEAR_DEPTH, 
                        currentMineTarget.toShortString(), originalTarget.toShortString());
                // ⚠️ clearDepth 不重置，继续累加
                currentMineTarget = originalTarget;  // 切换回原目标
                miner = new BotMiner(bot, currentMineTarget);
                return Status.RUNNING;
            }
            
            // 原目标挖完，进入下一个原木
            currentLogIndex++;
            miner = null;
            currentMineTarget = null;
            originalTarget = null;
            clearDepth = 0;  // 只在原目标完成时重置
            BotLog.info("lumber: 📊 progress {}/{}", 
                    currentLogIndex, currentTreeLogs.size());
            return Status.RUNNING;
        }
        
        if (minerStatus == BotMiner.Status.FAILED) {
            String reason = miner.failureReason();
            BotLog.warn("lumber: ❌ miner failed at {}, reason={}", 
                    currentMineTarget.toShortString(), reason);
            
            // 🆕 参考 MineTask：尝试清障（只在深度限制内）
            if (clearDepth < MAX_CLEAR_DEPTH) {
                BlockPos blocker = findLeafBlocker(currentMineTarget);
                if (blocker != null && !blocker.equals(currentMineTarget)) {
                    // 找到遮挡的树叶，清除它
                    if (originalTarget == null) {
                        originalTarget = currentMineTarget;  // 保存原目标
                    }
                    clearDepth++;
                    BotLog.info("lumber: 🌿 局部清障 ({}/{}): reason={}, 挖遮挡物 {}", 
                            clearDepth, MAX_CLEAR_DEPTH, reason, blocker.toShortString());
                    currentMineTarget = blocker;  // 切换到遮挡物
                    miner = new BotMiner(bot, currentMineTarget);
                    return Status.RUNNING;
                }
            }
            
            // 没有找到遮挡物，或者达到清障深度限制，跳过这个原木
            BotLog.info("lumber: ⏭️ skipping unreachable log (reason={}, clearDepth={}/{})", 
                    reason, clearDepth, MAX_CLEAR_DEPTH);
            currentLogIndex++;
            miner = null;
            currentMineTarget = null;
            originalTarget = null;
            clearDepth = 0;
            return Status.RUNNING;
        }
        
        // MOVING 或 MINING 状态，继续运行
        return Status.RUNNING;
    }
    
    private Status handleCollecting() {
        // 清理已消失的掉落物
        trackedDrops.removeIf(drop -> drop.isRemoved());
        
        if (trackedDrops.isEmpty()) {
            // 所有掉落物收集完毕，继续下一棵树
            phase = Phase.SCANNING;
            return Status.RUNNING;
        }
        
        // 选择最近的掉落物
        ItemEntity nearest = null;
        double nearestDistSqr = Double.MAX_VALUE;
        
        for (ItemEntity drop : trackedDrops) {
            double distSqr = bot.distanceToSqr(drop);
            if (distSqr < nearestDistSqr) {
                nearestDistSqr = distSqr;
                nearest = drop;
            }
        }
        
        if (nearest == null) {
            phase = Phase.SCANNING;
            return Status.RUNNING;
        }
        
        // 如果在拾取范围内（2 格），等待自动拾取
        if (nearestDistSqr < 4.0) {
            nearest.playerTouch(bot);
            return Status.RUNNING;
        }
        
        // 如果太远（超过 16 格），放弃这个掉落物
        if (nearestDistSqr > 256.0) {
            trackedDrops.remove(nearest);
            BotLog.info("lumber: drop too far, abandoned");
            return Status.RUNNING;
        }
        
        // 移动到掉落物
        moveTowards(nearest.position());
        
        return Status.RUNNING;
    }
    
    /**
     * 简单的移动逻辑：朝目标方向移动
     */
    private void moveTowards(net.minecraft.world.phys.Vec3 target) {
        net.minecraft.world.phys.Vec3 botPos = bot.position();
        net.minecraft.world.phys.Vec3 direction = target.subtract(botPos).normalize();
        
        // 计算看向目标的 yaw 和 pitch
        double dx = target.x - botPos.x;
        double dy = target.y - botPos.y;
        double dz = target.z - botPos.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        
        float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        float pitch = (float) -(Math.atan2(dy, horizontalDist) * 180.0 / Math.PI);
        
        bot.setYRot(yaw);
        bot.setXRot(pitch);
        
        // 设置移动速度
        net.minecraft.world.phys.Vec3 movement = direction.scale(0.2);
        bot.setDeltaMovement(movement.x, bot.getDeltaMovement().y, movement.z);
    }
    
    private void selectNextTree() {
        if (pendingTrees.isEmpty()) {
            currentTree = null;
            return;
        }
        
        // 选择最近的树
        pendingTrees.sort(Comparator.comparingDouble(tree -> 
            bot.blockPosition().distSqr(tree.getBasePos())
        ));
        
        currentTree = pendingTrees.remove(0);
        currentTreeLogs = currentTree.getLogsInCutOrder();
        currentLogIndex = 0;
        
        BotLog.info("lumber: selected tree {}, logs={}, at={}",
                currentTree.getType().getDisplayName(),
                currentTree.getLogCount(),
                currentTree.getBasePos().toShortString());
    }
    
    private void finishCurrentTree() {
        treesCut++;
        BotLog.info("lumber: finished tree {}/{}, type={}",
                treesCut,
                treesCut + pendingTrees.size(),
                currentTree.getType().getDisplayName());
        
        currentTree = null;
        currentTreeLogs = null;
        currentLogIndex = 0;
        miner = null;  // 清理 miner
        
        // 收集掉落物
        if (!trackedDrops.isEmpty()) {
            phase = Phase.COLLECTING;
        } else {
            phase = Phase.SCANNING;
        }
    }
    
    private void scanTrees() {
        Level level = bot.level();
        pendingTrees.clear();
        scannedLogs.clear();
        
        BotLog.info("lumber: scanning trees in region...");
        
        // 扫描区域内的所有方块
        for (BlockPos pos : BlockPos.betweenClosed(
                (int) region.minX, (int) region.minY, (int) region.minZ,
                (int) region.maxX, (int) region.maxY, (int) region.maxZ)) {
            
            if (!isLog(pos)) {
                continue;
            }
            
            if (scannedLogs.contains(pos)) {
                continue;
            }
            
            // 检测树
            Tree tree = TreeDetector.detectTree(level, pos);
            
            if (tree != null) {
                pendingTrees.add(tree);
                scannedLogs.addAll(tree.getLogs());
                
                BotLog.info("lumber: found tree {}, logs={}, at={}",
                        tree.getType().getDisplayName(),
                        tree.getLogCount(),
                        tree.getBasePos().toShortString());
            }
        }
        
        BotLog.info("lumber: scan completed, found {} trees", pendingTrees.size());
    }
    
    private void trackNearbyDrops(BlockPos pos) {
        Level level = bot.level();
        List<ItemEntity> drops = level.getEntitiesOfClass(
                ItemEntity.class,
                AABB.ofSize(pos.getCenter(), 5, 5, 5)
        );
        trackedDrops.addAll(drops);
    }
    
    private boolean isLog(BlockPos pos) {
        return bot.level().getBlockState(pos).is(net.minecraft.tags.BlockTags.LOGS);
    }
    
    /**
     * 查找从 Bot 当前位置到目标原木之间的树叶遮挡物。
     * 参考 MineTask 的 findDirectBlocker() 逻辑。
     */
    private BlockPos findLeafBlocker(BlockPos target) {
        Vec3 eye = bot.getEyePosition();
        Vec3 targetCenter = target.getCenter();
        
        // 射线检测
        ClipContext context = new ClipContext(
            eye, 
            targetCenter,
            ClipContext.Block.OUTLINE,  // 使用 OUTLINE 模式检测所有方块（包括树叶）
            ClipContext.Fluid.NONE,
            bot
        );
        
        BlockHitResult hit = bot.level().clip(context);
        
        // 如果射线击中了方块，且不是目标原木
        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockPos hitPos = hit.getBlockPos();
            if (!hitPos.equals(target)) {
                // 检查是否是树叶
                if (bot.level().getBlockState(hitPos).is(BlockTags.LEAVES)) {
                    // 检查距离（最多清3格内的树叶）
                    if (eye.distanceTo(hitPos.getCenter()) <= 6.0) {
                        return hitPos;
                    }
                }
            }
        }
        
        return null;
    }
    
    // 状态查询方法（给 HUD 使用）
    
    public Phase getPhase() {
        return phase;
    }
    
    public int getTreesCut() {
        return treesCut;
    }
    
    public int getTotalTrees() {
        return treesCut + pendingTrees.size();
    }
    
    public float getProgress() {
        int total = getTotalTrees();
        if (total == 0) {
            return 1.0f;
        }
        return (float) treesCut / total;
    }
    
    public long getRunningTime() {
        return System.currentTimeMillis() - startTime;
    }
    
    public Tree getCurrentTree() {
        return currentTree;
    }
    
    public String getStatusLine1() {
        return String.format("已砍: %d/%d | 运行: %s",
                treesCut, getTotalTrees(), formatTime(getRunningTime()));
    }
    
    public String getStatusLine2() {
        if (currentTree != null) {
            return String.format("当前: 砍伐中 (%s @ %s)",
                    currentTree.getType().getDisplayName(),
                    currentTree.getBasePos().toShortString());
        } else if (phase == Phase.SCANNING) {
            return "扫描树木中...";
        } else if (phase == Phase.COLLECTING) {
            return "收集掉落物...";
        }
        return "等待中...";
    }
    
    private String formatTime(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format("%d分%d秒", minutes, seconds);
    }
}
