package com.dddgn.alice.client;

import com.dddgn.alice.item.BotRemoteControl;
import com.dddgn.alice.network.AliceNetwork;
import com.dddgn.alice.network.BotInputPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

/**
 * 客户端输入捕获器（遥控器模式）
 * 
 * <h3>功能</h3>
 * <ul>
 *   <li>监听 MovementInputUpdateEvent 屏蔽玩家输入</li>
 *   <li>捕获原始按键状态（WASD/空格/Shift）</li>
 *   <li>持续发送网络包到服务端控制 Bot</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = "alice", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientInputHandler {

    // 缓存上一次的输入（避免重复发送）
    private static float lastForward = 0;
    private static float lastStrafing = 0;
    private static boolean lastJumping = false;
    private static boolean lastSneaking = false;
    private static UUID lastBotId = null;

    private ClientInputHandler() {
    }

    /**
     * ✅ 正确方案：监听 MovementInputUpdateEvent 屏蔽玩家输入
     */
    @SubscribeEvent
    public static void onMovementInputUpdate(MovementInputUpdateEvent event) {
        // 只处理客户端本地玩家
        if (!(event.getEntity() instanceof LocalPlayer player)) {
            return;
        }
        
        // 检查玩家是否持有遥控器
        ItemStack mainHand = player.getMainHandItem();
        if (!(mainHand.getItem() instanceof BotRemoteControl)) {
            return;
        }

        // 获取遥控的 Bot UUID
        UUID botId = BotRemoteControl.getBotId(mainHand);
        if (botId == null) {
            return;
        }

        // ✅ 屏蔽玩家输入（清空所有字段）
        Input input = event.getInput();
        input.forwardImpulse = 0;
        input.leftImpulse = 0;
        input.up = false;
        input.down = false;
        input.left = false;
        input.right = false;
        input.jumping = false;
        input.shiftKeyDown = false;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        // 只在 tick 开始阶段处理（避免重复）
        if (event.phase != TickEvent.Phase.START) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        
        if (player == null) {
            return;
        }

        // 检查玩家是否持有遥控器
        ItemStack mainHand = player.getMainHandItem();
        if (!(mainHand.getItem() instanceof BotRemoteControl)) {
            resetCache();
            return;
        }

        // 获取遥控的 Bot UUID
        UUID botId = BotRemoteControl.getBotId(mainHand);
        if (botId == null) {
            resetCache();
            return;
        }

        // ✅ 捕获原始按键状态
        Options options = mc.options;
        float forward = 0;
        float strafing = 0;
        
        if (options.keyUp.isDown()) forward += 1.0F;
        if (options.keyDown.isDown()) forward -= 1.0F;
        if (options.keyRight.isDown()) strafing -= 1.0F;  // ✅ 修正：右键应该是负值（向右）
        if (options.keyLeft.isDown()) strafing += 1.0F;   // ✅ 修正：左键应该是正值（向左）
        
        boolean jumping = options.keyJump.isDown();
        boolean sneaking = options.keyShift.isDown();

        // ✅ 改进：只要有输入就持续发送（保持 Bot 持续移动）
        boolean hasInput = (forward != 0 || strafing != 0 || jumping || sneaking);
        boolean hadInput = (lastForward != 0 || lastStrafing != 0 || lastJumping || lastSneaking);
        
        // 发送条件：
        // 1. 当前有输入 → 持续发送
        // 2. 之前有输入但现在没有 → 发送一次停止信号
        if (hasInput || hadInput) {
            // 发送到服务端
            AliceNetwork.CHANNEL.sendToServer(
                new BotInputPacket(botId, forward, strafing, jumping, sneaking)
            );

            // 更新缓存
            lastForward = forward;
            lastStrafing = strafing;
            lastJumping = jumping;
            lastSneaking = sneaking;
            lastBotId = botId;
        }
    }

    private static void resetCache() {
        lastForward = 0;
        lastStrafing = 0;
        lastJumping = false;
        lastSneaking = false;
        lastBotId = null;
    }
}
