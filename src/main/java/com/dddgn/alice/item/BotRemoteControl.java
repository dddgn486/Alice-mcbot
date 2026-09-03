package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bot 遥控器物品。
 * 
 * <p>右键进入/退出 Bot 控制模式。控制模式下：
 * <ul>
 *   <li>W/A/S/D - 前后左右移动（通过 BotController）</li>
 *   <li>Space - 单次跳跃（BotController.jumpOnce）</li>
 *   <li>Shift - 潜行</li>
 * </ul>
 * 
 * <p>实际输入捕获在 {@link BotRemoteControlHandler} 中处理。
 */
public class BotRemoteControl extends Item {
    
    /** 玩家 -> 被控制的 Bot UUID */
    private static final Map<UUID, UUID> CONTROLLING = new HashMap<>();
    
    public BotRemoteControl(Properties properties) {
        super(properties);
    }
    
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) {
            return InteractionResultHolder.success(player.getItemInHand(hand));
        }
        
        ServerPlayer serverPlayer = (ServerPlayer) player;
        UUID playerUuid = player.getUUID();
        ItemStack itemStack = player.getItemInHand(hand);
        
        // 如果已经在控制某个 bot，则退出控制
        if (CONTROLLING.containsKey(playerUuid)) {
            UUID botUuid = CONTROLLING.remove(playerUuid);
            BotPlayer bot = BotManager.getBot(botUuid);
            if (bot != null) {
                bot.controller().stopMovement();
                BotLog.info("Player {} stopped controlling bot {}", 
                        player.getName().getString(), bot.getName().getString());
            }
            
            // ✅ 清除物品 NBT 中的 Bot UUID
            if (itemStack.hasTag()) {
                itemStack.getTag().remove("BotUUID");
            }
            
            player.displayClientMessage(
                    Component.literal("§e已退出 Bot 控制模式"), true);
            return InteractionResultHolder.success(itemStack);
        }
        
        // 查找最近的 bot
        BotPlayer nearestBot = findNearestBot(serverPlayer);
        if (nearestBot == null) {
            player.displayClientMessage(
                    Component.literal("§c附近没有 Bot！"), true);
            return InteractionResultHolder.fail(itemStack);
        }
        
        // 进入控制模式
        UUID botUUID = nearestBot.getUUID();
        CONTROLLING.put(playerUuid, botUUID);
        
        // ✅ 将 Bot UUID 写入物品 NBT（自动同步到客户端）
        itemStack.getOrCreateTag().putUUID("BotUUID", botUUID);
        
        player.displayClientMessage(
                Component.literal("§a正在控制 Bot: " + nearestBot.getName().getString() 
                        + " §7(再次右键退出)"), true);
        BotLog.info("Player {} started controlling bot {}", 
                player.getName().getString(), nearestBot.getName().getString());
        
        return InteractionResultHolder.success(itemStack);
    }
    
    /**
     * 查找最近的 bot（16 格范围内）。
     */
    private BotPlayer findNearestBot(ServerPlayer player) {
        BotPlayer nearest = null;
        double minDistance = 16.0 * 16.0;  // 平方距离
        
        for (BotPlayer bot : BotManager.getAllBots()) {
            if (bot.level() != player.level()) {
                continue;
            }
            double distSq = bot.distanceToSqr(player);
            if (distSq < minDistance) {
                nearest = bot;
                minDistance = distSq;
            }
        }
        
        return nearest;
    }
    
    /**
     * 获取玩家正在控制的 bot。
     */
    public static BotPlayer getControllingBot(Player player) {
        UUID botUuid = CONTROLLING.get(player.getUUID());
        if (botUuid == null) {
            return null;
        }
        return BotManager.getBot(botUuid);
    }
    
    /**
     * 获取玩家正在控制的 bot 的 UUID（用于客户端）。
     * 
     * @param itemStack 遥控器物品
     * @return Bot UUID，如果没有控制则返回 null
     */
    @OnlyIn(Dist.CLIENT)
    public static UUID getBotId(ItemStack itemStack) {
        if (!itemStack.hasTag()) {
            return null;
        }
        if (!itemStack.getTag().hasUUID("BotUUID")) {
            return null;
        }
        return itemStack.getTag().getUUID("BotUUID");
    }
    
    /**
     * 检查玩家是否在控制某个 bot。
     */
    public static boolean isControlling(Player player) {
        return CONTROLLING.containsKey(player.getUUID());
    }
    
    /**
     * 清除玩家的控制状态（用于玩家下线等情况）。
     */
    public static void clearControlling(Player player) {
        UUID botUuid = CONTROLLING.remove(player.getUUID());
        if (botUuid != null) {
            BotPlayer bot = BotManager.getBot(botUuid);
            if (bot != null) {
                bot.controller().stopMovement();
            }
        }
    }
}
