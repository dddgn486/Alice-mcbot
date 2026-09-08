package com.dddgn.alice.task.lumber;

import com.dddgn.alice.action.BotMiner;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.task.Task;
import com.dddgn.alice.task.TaskTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 持续伐木任务（自动伐木器使用）。
 * <p>
 * 工作流程：
 * 1. 扫描中心点附近的树木
 * 2. 选择最近的树
 * 3. 砍完一棵树后，重新扫描
 * 4. 持续工作，直到附近没树或任务被停止
 */
public final class ContinuousLumberTask implements Task {
    
    private enum Phase { SCANNING, CUTTING, COLLECTING }
    
    private final BotPlayer bot;
    private final BlockPos center;      // 扫描中心点
    private final int scanRadius;       // 扫描半径
    
    private Phase phase = Phase.SCANNING;
    private List<BlockPos> allLogs = new ArrayList<>();
    private Set<BlockPos> invalidLogs = new HashSet<>();  // 黑名单：无效的原木
    private Set<BlockPos> invalidTrees = new HashSet<>(); // 黑名单：无效的整棵树（basePos）
    private List<BlockPos> currentTree = new ArrayList<>();
    private BlockPos currentTreeBasePos = null;  // 当前树的基座位置
    private BlockPos currentTarget = null;  // 当前高亮的目标
    private BlockPos originalTarget = null;   // 原始目标原木（清障时保存）
    private int currentLogIndex = 0;
    private int currentLogFailures = 0;  // 当前原木连续失败次数
    private int clearDepth = 0;  // 当前清障深度
    private BotMiner miner;
    private int collectTicks = 0;
    private int scanAttempts = 0;
    
    // 位置追踪（用于检测瞬移）
    private BlockPos lastBotPos = null;
    private int positionCheckTicks = 0;
    
    private static final int COLLECT_DURATION = 40;  // 收集掉落物的时间（2 秒）
    private static final int MAX_SCAN_ATTEMPTS = 3;  // 最多扫描 3 次没树就结束
    private static final int MAX_LOG_FAILURES = 3;   // 单个原木最多失败 3 次
    private static final int MAX_CLEAR_DEPTH = 3;    // 最大清障深度（伐木可以多清几层树叶）
    private static final double MAX_CLEAR_REACH = 4.5D;  // 最大清障距离
    
    public ContinuousLumberTask(BotPlayer bot, BlockPos center, int scanRadius) {
        this.bot = bot;
        this.center = center;
        this.scanRadius = scanRadius;
        
        // 给 Bot 装备钻石斧
        bot.getInventory().setItem(bot.getInventory().selected,
                new ItemStack(Items.DIAMOND_AXE));
        
        BotLog.info("continuous_lumber: start at {} radius={}", center.toShortString(), scanRadius);
    }
    
    @Override
    public TaskTarget target() {
        // 初始返回中心点，避免 beginTask() 时 null 导致崩溃
        if (currentTarget == null) {
            return TaskTarget.block(center);
        }
        return TaskTarget.block(currentTarget);
    }
    
    @Override
    public Status tick() {
        // 🔍 探针：位置追踪（检测瞬移）
        BlockPos currentPos = bot.blockPosition();
        if (lastBotPos != null && !currentPos.equals(lastBotPos)) {
            double distance = Math.sqrt(lastBotPos.distSqr(currentPos));
            
            // 如果一个 tick 内移动超过 5 格，很可能是瞬移
            if (distance > 5.0) {
                BotLog.warn("⚠️ [位置追踪] 检测到疑似瞬移: {} -> {}, 距离: {:.1f} 格, 阶段: {}",
                        lastBotPos.toShortString(),
                        currentPos.toShortString(),
                        distance,
                        phase);
            } else if (distance > 0.5) {
                // 正常移动
                if (positionCheckTicks % 20 == 0) {  // 每秒打印一次
                    BotLog.info("📍 [位置追踪] Bot移动: {} -> {}, 距离: {:.2f} 格, 阶段: {}",
                            lastBotPos.toShortString(),
                            currentPos.toShortString(),
                            distance,
                            phase);
                }
            }
        }
        lastBotPos = currentPos.immutable();
        positionCheckTicks++;
        
        Status result = switch (phase) {
            case SCANNING -> handleScanning();
            case CUTTING -> handleCutting();
            case COLLECTING -> handleCollecting();
        };
        
        // 探针：每个阶段的状态
        if (phase == Phase.SCANNING && result == Status.RUNNING) {
            BotLog.info("🔍 [SCANNING] 扫描中... 已扫描尝试: {}/{}, 找到原木: {}, 无效原木: {}",
                    scanAttempts, MAX_SCAN_ATTEMPTS, allLogs.size(), invalidLogs.size());
        }
        
        return result;
    }
    
    /**
     * 扫描阶段：扫描附近的树木
     */
    private Status handleScanning() {
        // 使用 Bot 当前位置作为扫描中心（而不是固定的初始中心）
        BlockPos scanCenter = bot.blockPosition();
        
        BotLog.info("🔍 [SCANNING] 开始扫描，中心: {}, 半径: {}, Bot位置: {}",
                scanCenter.toShortString(), scanRadius, bot.blockPosition().toShortString());
        
        ServerLevel level = (ServerLevel) bot.level();
        
        // 扫描 Bot 附近的区域
        AABB scanArea = new AABB(scanCenter).inflate(scanRadius);
        allLogs.clear();
        
        // 手动扫描区域内的原木
        for (BlockPos pos : BlockPos.betweenClosed(
                (int) scanArea.minX, (int) scanArea.minY, (int) scanArea.minZ,
                (int) scanArea.maxX, (int) scanArea.maxY, (int) scanArea.maxZ)) {
            if (level.getBlockState(pos).is(net.minecraft.tags.BlockTags.LOGS)) {
                BlockPos immutable = pos.immutable();
                // 跳过黑名单中的无效原木
                if (!invalidLogs.contains(immutable)) {
                    allLogs.add(immutable);
                }
            }
        }
        
        BotLog.info("🔍 [SCANNING] 扫描完成，找到原木: {}, 黑名单: {}", allLogs.size(), invalidLogs.size());
        
        if (allLogs.isEmpty()) {
            scanAttempts++;
            BotLog.info("🔍 [SCANNING] 未找到有效树木，扫描尝试: {}/{}", scanAttempts, MAX_SCAN_ATTEMPTS);
            if (scanAttempts >= MAX_SCAN_ATTEMPTS) {
                BotLog.info("continuous_lumber: no trees found after {} attempts, done", scanAttempts);
                return Status.DONE;
            }
            // 再等一会儿，可能树还在生长
            return Status.RUNNING;
        }
        
        // 不在这里重置 scanAttempts，等识别成功后再重置
        
        // 选择最近的树
        BlockPos nearestLog = allLogs.stream()
                .min(Comparator.comparingDouble(pos -> bot.distanceToSqr(Vec3.atCenterOf(pos))))
                .orElse(null);
        
        if (nearestLog == null) {
            BotLog.warn("🔍 [SCANNING] 没有找到最近的原木（不应该发生）");
            return Status.DONE;
        }
        
        BotLog.info("🔍 [SCANNING] 选择最近的原木: {} (距离: {:.1f})",
                nearestLog.toShortString(),
                Math.sqrt(bot.distanceToSqr(Vec3.atCenterOf(nearestLog))));
        
        // 🔧 从底部开始识别树
        BlockPos treeBase = findTreeBase(level, nearestLog);
        if (!treeBase.equals(nearestLog)) {
            BotLog.info("🔍 [SCANNING] 找到树的底部: {} (原木: {})",
                    treeBase.toShortString(),
                    nearestLog.toShortString());
        }
        
        // 识别整棵树
        Tree tree = TreeDetector.detectTree(level, treeBase);
        
        // 检查 tree 是否为 null（可能是残缺的树）
        if (tree == null || tree.getLogs().isEmpty()) {
            // 🔍 探针：TreeDetector 失败详细分析
            BlockState logState = level.getBlockState(nearestLog);
            int nearbyLogsCount = countNearbyLogs(level, nearestLog);
            
            BotLog.warn("🔍 [TreeDetector失败] 原木识别失败:");
            BotLog.warn("  位置: {}, Y坐标: {}", nearestLog.toShortString(), nearestLog.getY());
            BotLog.warn("  方块类型: {}", logState.getBlock().getName().getString());
            BotLog.warn("  是否是原木: {}", logState.is(net.minecraft.tags.BlockTags.LOGS));
            BotLog.warn("  周围3x3x3原木数: {}", nearbyLogsCount);
            BotLog.warn("  TreeDetector返回: {}", tree == null ? "null" : "empty logs");
            
            // 加入黑名单，避免重复检测
            invalidLogs.add(nearestLog);
            // 从当前列表移除
            allLogs.remove(nearestLog);
            
            if (allLogs.isEmpty()) {
                scanAttempts++;
                if (scanAttempts >= MAX_SCAN_ATTEMPTS) {
                    BotLog.info("continuous_lumber: no valid trees after {} scan attempts, done", scanAttempts);
                    return Status.DONE;
                }
            }
            return Status.RUNNING;
        }
        
        // 检查树是否在黑名单中
        BlockPos detectedTreeBase = tree.getBasePos();
        if (invalidTrees.contains(detectedTreeBase)) {
            BotLog.warn("🔍 [SCANNING] 树 {} 在黑名单中，跳过", detectedTreeBase.toShortString());
            // 将这棵树的所有原木加入黑名单
            for (BlockPos log : tree.getLogs()) {
                invalidLogs.add(log);
            }
            // 从当前列表移除
            allLogs.removeAll(tree.getLogs());
            
            if (allLogs.isEmpty()) {
                scanAttempts++;
                if (scanAttempts >= MAX_SCAN_ATTEMPTS) {
                    BotLog.info("continuous_lumber: no valid trees after {} scan attempts, done", scanAttempts);
                    return Status.DONE;
                }
            }
            return Status.RUNNING;
        }
        
        currentTree = new ArrayList<>(tree.getLogs());
        currentTreeBasePos = detectedTreeBase;  // 记录当前树的基座
        currentLogIndex = 0;
        currentLogFailures = 0;  // 重置失败计数
        
        // 成功识别到树，重置扫描计数
        scanAttempts = 0;
        
        BotLog.info("🌳 [SCANNING->CUTTING] 识别到树木: {} 原木, basePos: {}, 开始砍伐",
                currentTree.size(),
                tree.getBasePos().toShortString());
        
        phase = Phase.CUTTING;
        return Status.RUNNING;
    }
    
    /**
     * 砍伐阶段：使用 BotMiner 砍树
     */
    private Status handleCutting() {
        if (currentLogIndex >= currentTree.size()) {
            // 当前树砍完了，开始收集掉落物
            BotLog.info("🪓 [CUTTING->COLLECTING] 树砍完了，开始收集掉落物");
            phase = Phase.COLLECTING;
            collectTicks = 0;
            currentTarget = null;
            return Status.RUNNING;
        }
        
        BlockPos target = currentTree.get(currentLogIndex);
        currentTarget = target;  // 更新高亮目标
        
        // 探针：砍伐进度
        if (miner == null) {
            BotLog.info("🪓 [CUTTING] 开始砍伐原木 {}/{}: {} (Bot位置: {})",
                    currentLogIndex + 1,
                    currentTree.size(),
                    target.toShortString(),
                    bot.blockPosition().toShortString());
        }
        
        // 创建或更新 BotMiner
        if (miner == null) {
            miner = new BotMiner(bot, target);
            
            // 🔍 探针：站位记录
            BotLog.info("🔍 [站位记录] 创建BotMiner: 目标={}, Bot位置={}, 距离={:.2f}, 眼睛高度={:.2f}",
                    target.toShortString(),
                    bot.blockPosition().toShortString(),
                    Math.sqrt(bot.blockPosition().distSqr(target)),
                    bot.getEyeY());
        }
        
        BotMiner.Status minerStatus = miner.tick();
        
        // 探针：BotMiner 状态
        if (minerStatus == BotMiner.Status.MOVING) {
            // 每 10 tick 打印一次移动状态
            if (currentLogIndex % 3 == 0 || System.currentTimeMillis() % 500 < 50) {
                double distance = Math.sqrt(bot.blockPosition().distSqr(target));
                BotLog.info("🪓 [CUTTING] BotMiner 正在移动到原木 {}: {} -> {}, 距离: {:.1f}",
                        currentLogIndex + 1,
                        bot.blockPosition().toShortString(),
                        target.toShortString(),
                        distance);
            }
        } else if (minerStatus == BotMiner.Status.MINING) {
            // 挖掘状态不打印，避免刷屏
        }
        
        if (minerStatus == BotMiner.Status.DONE) {
            // 当前原木砍完，继续下一个
            BotLog.info("🪓 [CUTTING] 原木 {}/{} 砍完: {}, 继续下一个",
                    currentLogIndex + 1,
                    currentTree.size(),
                    currentTarget.toShortString());
            currentLogIndex++;
            currentLogFailures = 0;  // 重置失败计数
            miner = null;
            currentTarget = null;
        } else if (minerStatus == BotMiner.Status.FAILED) {
            String failureReason = miner.failureReason();
            
            // 🔍 探针：失败详细分析
            BotLog.warn("🔍 [砍伐失败分析]");
            BotLog.warn("  原木: {}/{}, 位置: {}", 
                    currentLogIndex + 1, 
                    currentTree.size(), 
                    currentTarget.toShortString());
            BotLog.warn("  失败原因: {}", failureReason);
            BotLog.warn("  Bot位置: {}, 眼睛: ({:.2f}, {:.2f}, {:.2f})",
                    bot.blockPosition().toShortString(),
                    bot.getEyePosition().x,
                    bot.getEyePosition().y,
                    bot.getEyePosition().z);
            BotLog.warn("  与目标距离: {:.2f}", 
                    Math.sqrt(bot.blockPosition().distSqr(currentTarget)));
            
            // 检查是否是"硬失败"（不可恢复）
            if (isHardFailure(failureReason)) {
                BotLog.warn("🪓 [CUTTING] 原木遇到硬失败: {}, 原因: {}, 跳过",
                        currentTarget.toShortString(), failureReason);
                currentLogIndex++;
                currentLogFailures = 0;
                miner = null;
                currentTarget = null;
                return Status.RUNNING;
            }
            
            // 伐木任务不清障
            // 原因：视线中的障碍物很可能是其他树的树干，清除会破坏树木识别
            // 解决方案：失败 3 次后放弃整棵树，选择下一棵树
            currentLogFailures++;
            BotLog.warn("🪓 [CUTTING] 原木 {}/{} 砍伐失败: {}, 失败次数: {}/{}, 原因: {}（不清障）",
                    currentLogIndex + 1,
                    currentTree.size(),
                    currentTarget.toShortString(),
                    currentLogFailures,
                    MAX_LOG_FAILURES,
                    failureReason);
            
            miner = null;
            
            // 检查是否超过失败次数
            if (currentLogFailures >= MAX_LOG_FAILURES) {
                // 放弃整棵树
                BotLog.warn("🪓 [CUTTING->SCANNING] 原木连续失败 {} 次，放弃整棵树: {}",
                        MAX_LOG_FAILURES,
                        currentTreeBasePos.toShortString());
                
                // 将整棵树加入黑名单
                invalidTrees.add(currentTreeBasePos);
                
                // 将当前树的所有原木加入黑名单
                for (BlockPos log : currentTree) {
                    invalidLogs.add(log);
                }
                
                // 重置状态，重新扫描
                phase = Phase.SCANNING;
                currentTree.clear();
                currentTreeBasePos = null;
                currentLogIndex = 0;
                currentLogFailures = 0;
                
                return Status.RUNNING;
            }
            
            // 未超过失败次数，重试同一个原木
            // 不增加 currentLogIndex，下次 tick 继续尝试
        }
        
        return Status.RUNNING;
    }
    
    /**
     * 收集阶段：收集掉落物
     */
    private Status handleCollecting() {
        if (collectTicks == 0) {
            BotLog.info("📦 [COLLECTING] 开始收集掉落物，持续 {} ticks, Bot位置: {}",
                    COLLECT_DURATION, bot.blockPosition().toShortString());
        }
        
        collectTicks++;
        
        // 🔍 探针：收集前的位置
        BlockPos beforeCollect = bot.blockPosition().immutable();
        
        // 简单收集：尝试拾取附近的掉落物
        ServerLevel level = (ServerLevel) bot.level();
        AABB collectArea = bot.getBoundingBox().inflate(2.0);
        int itemCount = 0;
        List<net.minecraft.world.phys.Vec3> itemPositions = new ArrayList<>();
        
        for (net.minecraft.world.entity.item.ItemEntity item : 
                level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, collectArea)) {
            if (bot.distanceToSqr(item) < 4.0) {
                itemPositions.add(item.position());
                BotLog.info("📦 [COLLECTING] 拾取掉落物: {} 距离: {:.2f}, Bot位置: {}",
                        item.getItem().getHoverName().getString(),
                        Math.sqrt(bot.distanceToSqr(item)),
                        bot.blockPosition().toShortString());
                item.playerTouch(bot);  // ⚠️ 瞬移拾取点
                itemCount++;
            }
        }
        
        // 🔍 探针：收集后的位置
        BlockPos afterCollect = bot.blockPosition().immutable();
        if (!beforeCollect.equals(afterCollect)) {
            BotLog.warn("⚠️ [COLLECTING] Bot位置发生变化: {} -> {}, 拾取了 {} 个物品",
                    beforeCollect.toShortString(),
                    afterCollect.toShortString(),
                    itemCount);
        }
        
        // 探针：每 20 tick 打印一次收集进度
        if (collectTicks % 20 == 0 || collectTicks == 1) {
            BotLog.info("📦 [COLLECTING] 收集进度: {}/{} ticks, 本次收集物品: {}, Bot位置: {}",
                    collectTicks, COLLECT_DURATION, itemCount, bot.blockPosition().toShortString());
        }
        
        if (collectTicks >= COLLECT_DURATION) {
            // 收集完成，重新扫描
            BotLog.info("📦 [COLLECTING->SCANNING] 收集完成，重新扫描下一棵树");
            BotLog.info("📦 当前黑名单: 无效树木 {}, 无效原木 {}",
                    invalidTrees.size(), invalidLogs.size());
            
            phase = Phase.SCANNING;
            allLogs.clear();
            invalidLogs.clear();  // 清空原木黑名单（树可能已经重新长了）
            // invalidTrees 保留，避免重复尝试失败的树
            currentTree.clear();
            currentTreeBasePos = null;
            currentLogIndex = 0;
            currentLogFailures = 0;
            collectTicks = 0;
            currentTarget = null;
        }
        
        return Status.RUNNING;
    }
    
    public void onStop() {
        BotLog.info("continuous_lumber: task stopped");
        if (miner != null) {
            miner = null;
        }
    }
    
    /**
     * 判断是否是硬失败（不可恢复的失败）。
     */
    private boolean isHardFailure(String reason) {
        return "unbreakable_block".equals(reason)
                || "fluid_risk_lava".equals(reason)
                || reason.startsWith("protected_");
    }
    
    /**
     * 统计某个位置周围 3x3x3 范围内的原木数量（用于调试）。
     */
    private int countNearbyLogs(ServerLevel level, BlockPos center) {
        int count = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (level.getBlockState(pos).is(net.minecraft.tags.BlockTags.LOGS)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }
    
    /**
     * 从给定的原木位置向下寻找树的底部。
     */
    private BlockPos findTreeBase(ServerLevel level, BlockPos startLog) {
        BlockPos current = startLog;
        int maxDepth = 20;  // 最多向下搜索 20 格，防止死循环
        
        // 持续向下搜索，直到找到最底部的原木
        for (int i = 0; i < maxDepth; i++) {
            BlockPos below = current.below();
            
            // 如果下方是原木，继续向下
            if (level.getBlockState(below).is(net.minecraft.tags.BlockTags.LOGS)) {
                current = below;
            } else {
                // 下方不是原木，找到底部了
                break;
            }
        }
        
        return current;
    }
    
    @Override
    public String failureReason() {
        return null;  // 持续任务不会失败，只会完成或被停止
    }
}
