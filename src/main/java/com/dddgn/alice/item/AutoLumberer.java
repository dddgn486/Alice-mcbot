package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.task.lumber.ContinuousLumberTask;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 自动伐木器（alice:auto_lumberer，贴图=钻石斧）。
 * <p>
 * 用于持续自动砍树：
 * <ul>
 *   <li><b>右键</b> → 开始持续砍树（扫描附近树木，砍完一棵再扫描）</li>
 *   <li><b>Shift+右键</b> → 停止当前任务</li>
 * </ul>
 * 
 * 工作流程：
 * 1. 玩家右键激活
 * 2. Bot 扫描附近树木（16 格范围）
 * 3. Bot 砍树 + 收集掉落物
 * 4. 砍完后再次扫描
 * 5. 持续工作，直到附近没树或玩家 Shift+右键停止
 */
public class AutoLumberer extends Item {
    
    private static final int SCAN_RADIUS = 16;  // 扫描半径
    
    public AutoLumberer(Properties properties) {
        super(properties);
    }
    
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) {
            return InteractionResultHolder.success(player.getItemInHand(hand));
        }
        
        ServerLevel serverLevel = (ServerLevel) level;
        
        if (player.isShiftKeyDown()) {
            // Shift+右键：停止任务
            BotPlayer bot = BotManager.findNearestBot(serverLevel, player.blockPosition());
            
            if (bot == null) {
                player.sendSystemMessage(Component.literal(
                        "§c[自动伐木器] 附近没有 Bot！"));
                return InteractionResultHolder.fail(player.getItemInHand(hand));
            }
            
            if (BotManager.isBusy(bot)) {
                // 分配一个空任务来替换当前任务（相当于停止）
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
                        "§a[自动伐木器] 已停止 %s 的任务",
                        bot.getName().getString())));
            } else {
                player.sendSystemMessage(Component.literal(String.format(
                        "§c[自动伐木器] %s 当前没有任务",
                        bot.getName().getString())));
            }
            
            return InteractionResultHolder.success(player.getItemInHand(hand));
        } else {
            // 右键：开始任务
            BotPlayer bot = BotManager.findNearestBot(serverLevel, player.blockPosition());
            
            if (bot == null) {
                player.sendSystemMessage(Component.literal(
                        "§c[自动伐木器] 附近没有可用的 Bot！"));
                player.sendSystemMessage(Component.literal(
                        "§7提示: 使用 /alice spawn <名称> 生成 Bot"));
                return InteractionResultHolder.fail(player.getItemInHand(hand));
            }
            
            // 创建并分配任务
            ContinuousLumberTask task = new ContinuousLumberTask(bot, player.blockPosition(), SCAN_RADIUS);
            BotManager.assignTask(bot, task);
            
            player.sendSystemMessage(Component.literal(String.format(
                    "§a[自动伐木器] %s 开始持续砍树",
                    bot.getName().getString())));
            player.sendSystemMessage(Component.literal(
                    "§7扫描半径: " + SCAN_RADIUS + " 格"));
            player.sendSystemMessage(Component.literal(
                    "§7Shift+右键停止任务"));
            
            return InteractionResultHolder.success(player.getItemInHand(hand));
        }
    }
}
