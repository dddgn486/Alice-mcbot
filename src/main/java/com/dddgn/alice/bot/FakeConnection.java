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

import com.dddgn.alice.log.BotLog;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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

    private static final boolean PACKET_OBSERVER = Boolean.getBoolean("alice.packet.observer");
    private static final Map<String, Integer> PACKET_DUPLICATES = new HashMap<>();

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
                if (PACKET_OBSERVER) {
                    logPacketObservation(packet, player);
                }
                player.connection.send(packet);
            }
        }
    }

    private void logPacketObservation(Packet<?> packet, ServerPlayer recipient) {
        int tick = bot.getServer().getTickCount();
        int packetEntityId = packetEntityId(packet, bot);
        String key = tick + ":" + packetEntityId + ":" + recipient.getUUID();
        int duplicateCount = PACKET_DUPLICATES.merge(key, 1, Integer::sum);
        String fields = packetFields(packet);
        BotLog.info("M3_M5_PACKET_OBSERVER corr={} tick={} source=manual_fake_connection botUuid={} botName={} botEntityId={} packetClass={} packetEntityId={} recipientUuid={} recipientName={} recipientEntityId={} dimension={} sameLevel=true distance={} duplicateKey={} duplicateCount={} fields={}",
                UUID.randomUUID().toString().substring(0, 8), tick, bot.getUUID(), bot.getName().getString(), bot.getId(),
                packet.getClass().getSimpleName(), packetEntityId, recipient.getUUID(), recipient.getName().getString(), recipient.getId(),
                bot.level().dimension().location(), String.format(java.util.Locale.ROOT, "%.3f", bot.distanceTo(recipient)), key, duplicateCount, fields);
    }

    private static int packetEntityId(Packet<?> packet, BotPlayer bot) {
        if (packet instanceof ClientboundMoveEntityPacket move) {
            net.minecraft.world.entity.Entity entity = move.getEntity(bot.level());
            return entity == null ? -1 : entity.getId();
        }
        if (packet instanceof ClientboundSetEntityMotionPacket motion) {
            return motion.getId();
        }
        if (packet instanceof ClientboundTeleportEntityPacket teleport) {
            return teleport.getId();
        }
        return -1;
    }

    private static String packetFields(Packet<?> packet) {
        if (packet instanceof ClientboundMoveEntityPacket move) {
            return String.format(java.util.Locale.ROOT, "hasPosition=%s hasRotation=%s xa=%d ya=%d za=%d yaw=%d pitch=%d onGround=%s",
                    move.hasPosition(), move.hasRotation(), move.getXa(), move.getYa(), move.getZa(), move.getyRot(), move.getxRot(), move.isOnGround());
        }
        if (packet instanceof ClientboundSetEntityMotionPacket motion) {
            return String.format(java.util.Locale.ROOT, "id=%d xa=%d ya=%d za=%d", motion.getId(), motion.getXa(), motion.getYa(), motion.getZa());
        }
        if (packet instanceof ClientboundTeleportEntityPacket teleport) {
            return String.format(java.util.Locale.ROOT, "id=%d x=%.3f y=%.3f z=%.3f yaw=%d pitch=%d onGround=%s",
                    teleport.getId(), teleport.getX(), teleport.getY(), teleport.getZ(), teleport.getyRot(), teleport.getxRot(), teleport.isOnGround());
        }
        return "unavailable";
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
