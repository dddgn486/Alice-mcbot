package com.dddgn.alice.bot;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.lang.reflect.Field;

/**
 * 假人用伪造连接(1.20.1 Forge 版,思路同 mc_aiplayer 的 FakeClientConnection):
 * <ul>
 *   <li>用反射把 {@link Connection} 的 private {@code channel} 字段注入 {@link EmbeddedChannel}
 *       (netty 内存通道),使 {@code channel()/pipeline()} 非 null——否则
 *       {@code PlayerList.placeNewPlayer} 在事件派发时崩;</li>
 *   <li>发包/断线全部静默化:假人不真正走网络。</li>
 * </ul>
 */
public class FakeConnection extends Connection {

    public FakeConnection(PacketFlow side) {
        super(side);
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
        // 假人不真正发包
    }

    @Override
    public void send(Packet<?> packet, PacketSendListener callback) {
        if (callback != null) {
            callback.onSuccess();
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
