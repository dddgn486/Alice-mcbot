package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.lumber.RegionLumberTask;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 伐木规划器（alice:lumber_planner，贴图=铁斧）。
 * <p>
 * 用于选择伐木区域并分配伐木任务：
 * <ul>
 *   <li><b>Shift+右键方块（第一次）</b> → 设置区域的第一个角落（A点）</li>
 *   <li><b>Shift+右键方块（第二次）</b> → 设置区域的第二个角落（B点）</li>
 *   <li><b>右键方块</b> → 确认区域并开始任务</li>
 * </ul>
 * 
 * 工作流程：
 * 1. 玩家 Shift+右键选择角落 A
 * 2. 玩家 Shift+右键选择角落 B
 * 3. 玩家右键确认并开始任务
 * 4. 系统创建伐木任务并分配给最近的 Bot
 * 5. Bot 开始在区域内砍树
 */
public class LumberPlanner extends Item {
    
    // 存储每个玩家选择的角落
    private static final Map<UUID, BlockPos> cornerA = new HashMap<>();
    private static final Map<UUID, BlockPos> cornerB = new HashMap<>();
    
    public LumberPlanner(Properties properties) {
        super(properties);
    }
    
    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel().isClientSide) {
            return InteractionResult.SUCCESS;  // 客户端只播交互动画
        }
        
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        
        BlockPos clickedPos = context.getClickedPos();
        ServerLevel level = (ServerLevel) context.getLevel();
        
        UUID playerId = player.getUUID();
        
        if (player.isShiftKeyDown()) {
            // Shift+右键：设置角落 A 或 B
            BlockPos posA = cornerA.get(playerId);
            
            if (posA == null) {
                // 第一次 Shift+右键：设置角落 A
                cornerA.put(playerId, clickedPos);
                player.sendSystemMessage(Component.literal(
                        "§a[伐木规划器] 已设置角落 A: " + formatPos(clickedPos)));
                player.sendSystemMessage(Component.literal(
                        "§7继续 Shift+右键设置角落 B"));
                return InteractionResult.SUCCESS;
            } else {
                // 第二次 Shift+右键：设置角落 B
                cornerB.put(playerId, clickedPos);
                player.sendSystemMessage(Component.literal(
                        "§a[伐木规划器] 已设置角落 B: " + formatPos(clickedPos)));
                player.sendSystemMessage(Component.literal(
                        "§e区域已选择，右键确认并开始任务"));
                player.sendSystemMessage(Component.literal(String.format(
                        "§7区域: %s 到 %s",
                        formatPos(posA), formatPos(clickedPos))));
                return InteractionResult.SUCCESS;
            }
        } else {
            // 右键：确认并开始任务 或 停止任务
            BlockPos posA = cornerA.get(playerId);
            BlockPos posB = cornerB.get(playerId);
            
            // 查找最近的 Bot
            BotPlayer bot = BotManager.findNearestBot(level, player.blockPosition());
            
            if (bot == null) {
                player.sendSystemMessage(Component.literal(
                        "§c[伐木规划器] 附近没有可用的 Bot！"));
                player.sendSystemMessage(Component.literal(
                        "§7提示: 使用 /alice spawn <名称> 生成 Bot"));
                return InteractionResult.FAIL;
            }
            
            // 如果 Bot 正在执行区域伐木任务，右键停止
            if (BotManager.isBusy(bot)) {
                // 分配一个空任务来停止当前任务
                BotManager.assignTask(bot, new com.dddgn.alice.task.Task() {
                    @Override
                    public com.dddgn.alice.task.TaskTarget target() {
                        return null;
                    }
                    
                    @Override
                    public Status tick() {
                        return Status.DONE;
                    }
                    
                    @Override
                    public String failureReason() {
                        return null;
                    }
                });
                
                player.sendSystemMessage(Component.literal(String.format(
                        "§a[伐木规划器] 已停止 %s 的任务",
                        bot.getName().getString())));
                
                // 清除选择的角落
                cornerA.remove(playerId);
                cornerB.remove(playerId);
                
                return InteractionResult.SUCCESS;
            }
            
            // 如果没有选择区域，提示
            if (posA == null) {
                player.sendSystemMessage(Component.literal(
                        "§c[伐木规划器] 请先 Shift+右键设置角落 A"));
                return InteractionResult.FAIL;
            }
            
            if (posB == null) {
                player.sendSystemMessage(Component.literal(
                        "§c[伐木规划器] 请先 Shift+右键设置角落 B"));
                return InteractionResult.FAIL;
            }
            
            // 创建区域
            AABB region = createRegion(posA, posB);
            
            // 清除已选择的角落
            cornerA.remove(playerId);
            cornerB.remove(playerId);
            
            // 创建并分配任务
            RegionLumberTask task = new RegionLumberTask(bot, region);
            BotManager.assignTask(bot, task);
            
            player.sendSystemMessage(Component.literal(String.format(
                    "§a[伐木规划器] 已分配伐木任务给 %s",
                    bot.getName().getString())));
            player.sendSystemMessage(Component.literal(String.format(
                    "§7区域: %s 到 %s",
                    formatPos(posA), formatPos(posB))));
            
            return InteractionResult.SUCCESS;
        }
    }
    
    /**
     * 根据两个角落创建 AABB 区域。
     * 自动处理坐标大小关系，并往下延伸 1 格，往上固定 16 格高度。
     */
    private AABB createRegion(BlockPos a, BlockPos b) {
        int minX = Math.min(a.getX(), b.getX());
        int minZ = Math.min(a.getZ(), b.getZ());
        
        int maxX = Math.max(a.getX(), b.getX());
        int maxZ = Math.max(a.getZ(), b.getZ());
        
        // Y 坐标：取最低点，往下延伸 1 格，往上固定 16 格高度
        int minY = Math.min(a.getY(), b.getY()) - 1;
        int maxY = minY + 16;
        
        return new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
    }
    
    /**
     * 格式化坐标显示。
     */
    private String formatPos(BlockPos pos) {
        return String.format("(%d, %d, %d)", pos.getX(), pos.getY(), pos.getZ());
    }
}
