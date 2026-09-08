package com.dddgn.alice.network;

import com.dddgn.alice.bot.BotManager;
import com.dddgn.alice.bot.BotPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * C2S: 遥控器输入包（客户端 → 服务端）
 * <p>
 * 客户端捕获按键状态（WASD/空格/Shift），发送到服务端控制 Bot。
 * 服务端接收后调用 {@link com.dddgn.alice.bot.BotController} 设置输入。
 * </p>
 * 
 * <h3>设计原则</h3>
 * <ul>
 *   <li>仅在输入改变时发送（减少网络流量）</li>
 *   <li>客户端屏蔽玩家自身移动（修改 player.input）</li>
 *   <li>服务端单一权威（只有服务端能改变 Bot 状态）</li>
 * </ul>
 */
public record BotInputPacket(
        UUID botId,
        float forward,    // 前后输入 [-1.0, 1.0]
        float strafing,   // 左右输入 [-1.0, 1.0]
        boolean jumping,  // 是否跳跃
        boolean sneaking  // 是否潜行
) {

    public static void encode(BotInputPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.botId);
        buf.writeFloat(packet.forward);
        buf.writeFloat(packet.strafing);
        buf.writeBoolean(packet.jumping);
        buf.writeBoolean(packet.sneaking);
    }

    public static BotInputPacket decode(FriendlyByteBuf buf) {
        UUID botId = buf.readUUID();
        float forward = buf.readFloat();
        float strafing = buf.readFloat();
        boolean jumping = buf.readBoolean();
        boolean sneaking = buf.readBoolean();
        return new BotInputPacket(botId, forward, strafing, jumping, sneaking);
    }

    public static void handle(BotInputPacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isServer()) {
            context.enqueueWork(() -> {
                ServerPlayer sender = context.getSender();
                if (sender == null) {
                    return;
                }
                
                // 获取 Bot
                BotPlayer bot = BotManager.getBot(packet.botId());
                if (bot == null) {
                    return;
                }
                
                // 应用输入到 BotController
                bot.controller().setForward(packet.forward());
                bot.controller().setStrafing(packet.strafing());
                bot.controller().setSneaking(packet.sneaking());
                
                if (packet.jumping()) {
                    bot.controller().setJumping(true);
                } else {
                    bot.controller().setJumping(false);
                }
            });
        }
        context.setPacketHandled(true);
    }
}
