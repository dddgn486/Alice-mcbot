package com.dddgn.alice.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Alice 网络通道(S2C 为主:任务目标同步)。
 * <p>1.20.1 SimpleChannel 老式注册;协议版本不一致时拒连,避免包格式错位。</p>
 */
public final class AliceNetwork {

    public static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("alice", "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private static int nextId = 0;

    private AliceNetwork() {
    }

    /** 在 mod 构造时调用,注册所有包。 */
    public static void register() {
        CHANNEL.registerMessage(nextId++, TargetPacket.class,
                TargetPacket::encode, TargetPacket::decode,
                TargetPacket::handle, java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
}
