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

    /**
     * **连接 tick 计数（D-176 附注）**：字节码事实 —— `ServerPlayer.doTick()`（`aig.m()`）
     * 的唯一调用者是 `ServerGamePacketListenerImpl`（`aiy`），而 `doTick()` 内部 `invokespecial`
     * 调父类 `tick()`；也就是说**假人的物理（`BotPlayer.tick()`）挂在它的连接被 tick 这条链上**。
     * 与 Alice 的任务/会话（跑在全局 `ServerTickEvent.END`）**不同源** ⇒ 一旦这条链断了，
     * 就是"任务在跑、bot 一格不动、且没有任何报错"（实测 `entityTicksInSegment=0`）。
     *
     * <p>这里只**计数**（不做任何行为改变）：冻结复现时对照 `entityTickCount` 即可判定
     * "是连接没被 tick" 还是"连接 tick 了但实体没 tick"。
     */
    private long tickCount;

    /** 连接被 tick 的累计次数（只读诊断量）。 */
    public long tickCount() {
        return tickCount;
    }

    @Override
    public void tick() {
        tickCount++;
        super.tick();
    }

    private static final boolean PACKET_OBSERVER = Boolean.getBoolean("alice.packet.observer");

    /**
     * **旋转包转发计数**（D-320 诊断量，只计数、不改变行为）。
     *
     * <p>为什么要它：`send()` 会把服务端**发给这只假人**的旋转包再广播给"追踪它的玩家" ——
     * 而**别的假人也在追踪者名单里** ⇒ 两只假人挨着时会 A⇄B 互相喂到 `StackOverflowError`。
     * 判据（`BotPairNoRecurseCheckTask`）就靠这两个计数把"有界"和"被闸住"钉住。
     */
    private static final java.util.concurrent.atomic.AtomicLong RELAY_COUNT =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong SUPPRESSED_COUNT =
            new java.util.concurrent.atomic.AtomicLong();

    /** 累计转发次数（单调递增，诊断用）。 */
    public static long relayCount() {
        return RELAY_COUNT.get();
    }

    /** 累计"转发过程中收到、于是被丢弃"的旋转包次数（D-320 的闸生效证据）。 */
    public static long suppressedCount() {
        return SUPPRESSED_COUNT.get();
    }

    /**
     * **转发中**标记（D-320 的闸）：整条转发链路都在服务端线程上 ⇒ `ThreadLocal` 足够；
     * 置位期间再进来的旋转包一律丢弃（那正是"另一只假人"收到的那一份）。
     */
    private static final ThreadLocal<Boolean> RELAYING = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final Map<String, Integer> PACKET_DUPLICATES = new HashMap<>();

    private final BotPlayer bot;

    public FakeConnection(PacketFlow side, BotPlayer bot) {
        super(side);
        this.bot = bot;
        try {
            // 发行版运行时字段会是混淆名（例如 f_129468_），不能依赖开发映射名 "channel"。
            // 按唯一的 Netty Channel 类型定位，兼容 UserDev 与标准 Forge 客户端。
            Field channelField = null;
            for (Field field : Connection.class.getDeclaredFields()) {
                if (io.netty.channel.Channel.class.isAssignableFrom(field.getType())) {
                    channelField = field;
                    break;
                }
            }
            if (channelField == null) {
                throw new NoSuchFieldException("Connection Channel field");
            }
            channelField.setAccessible(true);
            channelField.set(this, new EmbeddedChannel());
        } catch (Exception exception) {
            throw new IllegalStateException("无法为假人初始化网络通道", exception);
        }
    }

    @Override
    public void send(Packet<?> packet) {
        // ✅ 修复：只广播头部/身体旋转包，不广播装备包
        // 原因：装备包会被客户端误认为是玩家自己的，导致快捷栏选择混乱
        
        // 需要广播的包类型（只有旋转包）
        if (isRotationPacket(packet)) {
            relayRotation(packet);
            return;
        }
        
        // 调试用：观察包内容
        if (PACKET_OBSERVER && (packet instanceof ClientboundMoveEntityPacket
            || packet instanceof ClientboundSetEntityMotionPacket
            || packet instanceof ClientboundTeleportEntityPacket)) {
            logPacketObservationNoSend(packet);
        }
        
        // 其他包丢弃（bot 自己不需要）
    }

    @Override
    public void send(Packet<?> packet, PacketSendListener callback) {
        // ✅ 修复：只广播头部/身体旋转包，不广播装备包
        if (isRotationPacket(packet)) {
            relayRotation(packet);
            if (callback != null) {
                callback.onSuccess();
            }
            return;
        }
        
        // 调试用：观察包内容
        if (PACKET_OBSERVER && (packet instanceof ClientboundMoveEntityPacket
            || packet instanceof ClientboundSetEntityMotionPacket
            || packet instanceof ClientboundTeleportEntityPacket)) {
            logPacketObservationNoSend(packet);
        }
        
        if (callback != null) {
            callback.onSuccess();
        }
    }
    
    /** 只有这两类是"要转给真人看"的旋转包（装备包会污染真人快捷栏 ⇒ 刻意不转，D-… 见 5d63cdf）。 */
    private static boolean isRotationPacket(Packet<?> packet) {
        return packet instanceof net.minecraft.network.protocol.game.ClientboundRotateHeadPacket
                || packet instanceof ClientboundMoveEntityPacket.Rot;
    }

    /**
     * 转发一个旋转包给"追踪这只假人的玩家"。
     *
     * <p>⚠️ **D-320**：这里曾经是"两只假人互相喂到爆栈"的入口 —— 追踪者名单里**包含别的假人**，
     * 而它们的 `send()` 又会转发一次 ⇒ `A.send → broadcast → B.send → broadcast → A.send …`
     * ⇒ `StackOverflowError`（2026-09-18 客户端实测：两假人相隔 1 格，生成后 2 秒开始爆栈）。
     *
     * <p>修法 = **转发中再收到旋转包就丢弃**：整条转发链路都跑在服务端线程上 ⇒ 一个
     * {@link ThreadLocal} 闸就够；**投递集合一个都没改**（转给真人的行为与之前完全一致）。
     */
    private void relayRotation(Packet<?> packet) {
        if (Boolean.TRUE.equals(RELAYING.get())) {
            // 转发过程中收到的副本 ⇒ 它属于"另一只假人"，不再转发（否则就是那次的无限递归）
            SUPPRESSED_COUNT.incrementAndGet();
            return;
        }
        RELAYING.set(Boolean.TRUE);
        try {
            RELAY_COUNT.incrementAndGet();
            broadcastToTracking(packet);
        } finally {
            RELAYING.set(Boolean.FALSE);
        }
    }

    /** 广播包给所有追踪 bot 的玩家 */
    private void broadcastToTracking(Packet<?> packet) {
        if (bot != null && bot.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            serverLevel.getChunkSource().broadcast(bot, packet);
        }
    }
    
    private void logPacketObservationNoSend(Packet<?> packet) {
        if (bot == null || bot.getServer() == null) {
            return;
        }
        int tick = bot.getServer().getTickCount();
        int packetEntityId = packetEntityId(packet, bot);
        String fields = packetFields(packet);
        BotLog.info("[PACKET_OBSERVER] tick={} botEntityId={} packetClass={} packetEntityId={} fields={} NOTE:NOT_BROADCASTED",
                tick, bot.getId(), packet.getClass().getSimpleName(), packetEntityId, fields);
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
