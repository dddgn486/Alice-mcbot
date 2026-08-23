package com.dddgn.alice.bot;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;

/**
 * 假人用伪造连接(1.20.1 Forge 版,思路同 mc_aiplayer 的 FakeClientConnection):
 * <ul>
 *   <li>用反射把 {@link Connection} 的 private {@code channel} 字段注入 {@link EmbeddedChannel}
 *       (netty 内存通道),使 {@code channel()/pipeline()} 非 null——否则
 *       {@code PlayerList.placeNewPlayer} 在事件派发时崩;</li>
 *   <li>发包/断线全部静默化:假人不真正走网络。</li>
 *   <li>P1 客户端同步修复：位置/速度包广播给真实玩家，使客户端正确观察 bot 物理状态。</li>
 * </ul>
 */
public class FakeConnection extends Connection {

    private final BotPlayer bot;

    public FakeConnection(PacketFlow side, BotPlayer bot) {
        super(side);
        this.bot = bot;
        try {
            Field field = Connection.class.getDeclaredField("channel");
            field.setAccessible(true);
            field.set(this, new EmbeddedChannel());
        } catch (Exception exception) {
            throw new IllegalStateException("无法为假人初始化网络通道", exception);
        }
    }

    @Override
    public void send(Packet<?> packet) {
        // P1 客户端同步修复：广播位置/速度包给真实玩家
        if (packet instanceof ClientboundMoveEntityPacket
            || packet instanceof ClientboundSetEntityMotionPacket
            || packet instanceof ClientboundTeleportEntityPacket) {
            broadcastToRealPlayers(packet);
        }
        // 其他包丢弃（bot 自己不需要）
    }

    @Override
    public void send(Packet<?> packet, PacketSendListener callback) {
        // P1 客户端同步修复：广播位置/速度包
        if (packet instanceof ClientboundMoveEntityPacket
            || packet instanceof ClientboundSetEntityMotionPacket
            || packet instanceof ClientboundTeleportEntityPacket) {
            broadcastToRealPlayers(packet);
        }
        if (callback != null) {
            callback.onSuccess();
        }
    }

    private void broadcastToRealPlayers(Packet<?> packet) {
        if (bot == null || bot.getServer() == null) {
            return;
        }
        // 广播给所有真实玩家（排除 bot 自己）
        for (ServerPlayer player : bot.getServer().getPlayerList().getPlayers()) {
            if (!(player instanceof BotPlayer) && player.level() == bot.level()) {
                player.connection.send(packet);
            }
        }
    }

    @Override
    public void disconnect(Component reason) {
        // 静默
    }

    @Override
    public void handleDisconnection() {
        // 静默
    }
}
