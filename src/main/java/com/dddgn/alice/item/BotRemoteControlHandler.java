package com.dddgn.alice.item;

import com.dddgn.alice.bot.BotPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Bot 遥控控制器 - 处理玩家输入并转发到 Bot。
 * 
 * <p>当玩家持有遥控器并处于控制模式时，捕获玩家的移动输入：
 * <ul>
 *   <li>W - bot.controller().setForward(1.0F)</li>
 *   <li>S - bot.controller().setForward(-1.0F)</li>
 *   <li>A - bot.controller().setStrafing(-1.0F)</li>
 *   <li>D - bot.controller().setStrafing(1.0F)</li>
 *   <li>Space - bot.controller().jumpOnce()</li>
 *   <li>Shift - bot.controller().setSneaking(true)</li>
 * </ul>
 * 
 * <p>注册到 Forge 事件总线：MinecraftForge.EVENT_BUS.register(new BotRemoteControlHandler())
 */
public class BotRemoteControlHandler {
    
    private boolean lastJumping = false;
    
    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        // 只处理服务端的 tick 结束阶段
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide) {
            return;
        }
        
        Player player = event.player;
        
        // 检查玩家是否在控制 bot
        if (!BotRemoteControlHandler.isHoldingRemote(player)) {
            return;
        }
        
        BotPlayer bot = BotRemoteControl.getControllingBot(player);
        if (bot == null) {
            return;
        }
        
        // 读取玩家的输入状态
        float forward = player.zza;    // W/S 输入 (1.0 = 前进, -1.0 = 后退)
        float strafing = player.xxa;   // A/D 输入 (-1.0 = 左移, 1.0 = 右移)
        // 服务端无法直接获取跳跃输入，使用速度 Y 分量检测
        boolean jumping = player.getDeltaMovement().y > 0.1;  // 向上速度 > 0.1 视为跳跃
        boolean sneaking = player.isShiftKeyDown();
        
        // 应用到 bot 的控制器
        bot.controller().setForward(forward);
        bot.controller().setStrafing(strafing);
        bot.controller().setSneaking(sneaking);
        
        // 跳跃：只在按下瞬间调用一次 jumpOnce()（边缘检测）
        if (jumping && !lastJumping) {
            bot.controller().jumpOnce();
        }
        lastJumping = jumping;
        
        // 让 bot 朝向玩家的视角方向（可选，可以注释掉）
        bot.setYRot(player.getYRot());
        bot.setYHeadRot(player.getYRot());
        bot.setXRot(player.getXRot());
    }
    
    /**
     * 检查玩家是否持有遥控器。
     */
    private static boolean isHoldingRemote(Player player) {
        return player.getMainHandItem().getItem() instanceof BotRemoteControl
                || player.getOffhandItem().getItem() instanceof BotRemoteControl;
    }
}
