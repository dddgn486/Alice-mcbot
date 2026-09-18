package com.dddgn.alice.protection;

import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.network.AliceNetwork;
import com.dddgn.alice.network.ProtectionClaimsPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 保护区认领的**服务端权威入口**（D-314，保护区 2/2）。
 *
 * <p>分工（照 `D-307` + FTB Chunks 的同一套）：
 * <ul>
 *   <li>客户端只发 <b>"我想改哪些区块 + 什么动作"</b>（{@code ProtectionActionPacket}），
 *       <b>永远不发维度</b> —— 维度由 {@link #apply} 的调用方用**发送者当前所在维度**裁定；</li>
 *   <li>服务端是唯一写认领清单的地方（{@link SafeZoneData}），写完把**认领元数据**（只有区块坐标，
 *       没有地形、没有颜色）回推给该玩家（{@link #snapshot}）。</li>
 * </ul>
 *
 * <p><b>三条边界</b>（都在夹具里逐条断言，因为它们全是"客户端可以撒谎"的地方）：
 * <ol>
 *   <li>{@link #MAX_BATCH}：单包最多这么多区块 ⇒ 超出在**解码期拒收**（畸形包，不是夹住）；</li>
 *   <li>{@link #inRange}：只接受 {@link #MAX_ABS_CHUNK} 以内的区块坐标 ⇒ 越界的**不落库**并计数上报
 *       （否则恶意客户端能把 SavedData 撑成"任意坐标的垃圾集合"）；</li>
 *   <li>{@link #MAX_CHUNKS_PER_DIMENSION}：每维度认领上限 ⇒ 整批拒绝并**响亮**告诉玩家
 *       （内存有界；取消认领不受限，所以不会把自己锁死）。</li>
 * </ol>
 */
public final class ProtectionClaimService {

    /** 单包最多接受的区块数（界面一屏最多 25×25=625 ⇒ 正常用法碰不到）。 */
    public static final int MAX_BATCH = 1024;

    /** 单次 S2C 快照最多下发的区块数（超出按"离玩家最近的先发"截断，并在包里置 truncated）。 */
    public static final int MAX_SNAPSHOT_CHUNKS = 16384;

    /**
     * 快照里**外部认领**（FTB Chunks 等，D-316）的收集半径（区块）。
     *
     * <p>界面最大 {@code MAX_GRID=25} ⇒ 只看得到中心 ±12，取 ±16 留余量；外部集合因此天然有界
     * （最多 33×33 = 1089 个）⇒ 不需要再截断。
     */
    public static final int EXTERNAL_WINDOW_CHUNKS = 16;

    /** 每维度认领上限（内存有界；一个基地用不到这个数）。 */
    public static final int MAX_CHUNKS_PER_DIMENSION = 32768;

    /** 允许的区块坐标绝对值上限（= 原版 ±30,000,000 方块 ÷ 16）。 */
    public static final int MAX_ABS_CHUNK = 30_000_000 >> 4;

    /** 同一个玩家的"请求快照"最小间隔（tick）⇒ 空包也不许刷。 */
    public static final int SYNC_MIN_INTERVAL_TICKS = 10;

    /** 每个玩家上次请求快照的服务端 tick。 */
    private static final Map<UUID, Integer> LAST_SYNC_TICK = new HashMap<>();

    private ProtectionClaimService() {
    }

    /**
     * 一批认领/取消的**结果账**（机器可判读：进日志、也进玩家聊天）。
     *
     * @param code {@code "ok"} 或 {@code "over_limit"}（整批未执行）
     */
    public record Report(boolean claim, int offered, int applied, int changed, int rejected, String code) {
        public String summary() {
            return "action=" + (claim ? "claim" : "unclaim") + " offered=" + offered + " applied=" + applied
                    + " changed=" + changed + " rejected=" + rejected + " code=" + code;
        }
    }

    // ==================== 写入（唯一入口）====================

    /**
     * 把一批区块认领/取消认领应用到**该 level 的维度**上。
     *
     * @param claim true = 认领，false = 取消认领
     * @param keys 区块键（{@link ChunkPos#asLong(int, int)}，可重复、可越界 —— 都会被如实计数）
     */
    public static Report apply(ServerLevel level, boolean claim, long[] keys) {
        SafeZoneData data = SafeZoneData.get(level.getServer());
        ResourceLocation dimension = level.dimension().location();
        long[] offered = keys == null ? new long[0] : keys;

        if (claim) {
            int additions = 0;
            for (long key : offered) {
                if (inRange(key) && !data.claims(dimension).contains(key)) {
                    additions++;
                }
            }
            if (exceedsLimit(data.claims(dimension).size(), additions)) {
                return new Report(true, offered.length, 0, 0, offered.length, "over_limit");
            }
        }

        int applied = 0;
        int changed = 0;
        int rejected = 0;
        for (long key : offered) {
            if (!inRange(key)) {
                rejected++;
                continue;
            }
            boolean did = claim
                    ? data.claim(level, ChunkPos.getX(key), ChunkPos.getZ(key))
                    : data.unclaim(level, ChunkPos.getX(key), ChunkPos.getZ(key));
            applied++;
            if (did) {
                changed++;
            }
        }
        return new Report(claim, offered.length, applied, changed, rejected, "ok");
    }

    /** 区块键是否在允许的坐标范围内（纯函数，夹具直接测）。 */
    public static boolean inRange(long chunkKey) {
        return Math.abs(ChunkPos.getX(chunkKey)) <= MAX_ABS_CHUNK
                && Math.abs(ChunkPos.getZ(chunkKey)) <= MAX_ABS_CHUNK;
    }

    /** 认领后是否会超过每维度上限（纯函数，夹具直接测）。 */
    public static boolean exceedsLimit(int currentTotal, int requestedAdditions) {
        return (long) currentTotal + requestedAdditions > MAX_CHUNKS_PER_DIMENSION;
    }

    /** C2S 动作包的处理：应用 + 记日志 + 聊天回执 + 回推快照。 */
    public static void handleBatch(ServerPlayer actor, boolean claim, long[] keys) {
        ServerLevel level = actor.serverLevel();
        Report report = apply(level, claim, keys);
        SafeZoneData data = SafeZoneData.get(actor.server);
        BotLog.info("[Protection] batch player={} {} total={} dim={}",
                actor.getName().getString(), report.summary(), data.claimedChunkCount(),
                level.dimension().location());
        if ("over_limit".equals(report.code())) {
            actor.sendSystemMessage(Component.literal("[alice] 保护区：本维度已达上限 " + MAX_CHUNKS_PER_DIMENSION
                    + " 个区块，**整批未执行**（先取消一些再试）"));
        } else {
            actor.sendSystemMessage(Component.literal("[alice] 保护区："
                    + (claim ? "认领" : "取消认领") + " " + report.changed() + " 个区块"
                    + (report.rejected() > 0 ? "（拒绝 " + report.rejected() + " 个越界区块）" : "")
                    + "，本次提交 " + report.offered() + " 个；当前共 " + data.claimedChunkCount() + " 个区块"));
        }
        pushSnapshot(actor);
    }

    /** C2S 空请求包的处理：限流后回推快照（界面自愈用，避免"界面开着却拿不到数据"）。 */
    public static void handleSyncRequest(ServerPlayer actor) {
        int now = actor.server.getTickCount();
        Integer last = LAST_SYNC_TICK.get(actor.getUUID());
        if (last != null && now - last < SYNC_MIN_INTERVAL_TICKS) {
            return;
        }
        LAST_SYNC_TICK.put(actor.getUUID(), now);
        pushSnapshot(actor);
    }

    // ==================== 读取（S2C）====================

    /** 向该玩家回推他当前维度的认领快照（只含区块坐标 ⇒ 地形永远由客户端自己画）。 */
    public static void pushSnapshot(ServerPlayer viewer) {
        ProtectionClaimsPacket packet = snapshot(viewer);
        AliceNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> viewer), packet);
        BotLog.info("[Protection] sync player={} dim={} sent={} truncated={} total={}",
                viewer.getName().getString(), packet.dimension(), packet.chunkKeys().length, packet.truncated(),
                SafeZoneData.get(viewer.server).claimedChunkCount());
    }

    /** 组装某玩家的快照（维度 = **他自己的**当前维度；离他最近的先发，超出上限则置 truncated）。 */
    public static ProtectionClaimsPacket snapshot(ServerPlayer viewer) {
        ServerLevel level = viewer.serverLevel();
        ResourceLocation dimension = level.dimension().location();
        SafeZoneData data = SafeZoneData.get(viewer.server);
        ChunkPos center = viewer.chunkPosition();
        ProtectionClaimsPacket own = selectNearest(dimension, data.claims(dimension),
                center.x, center.z, MAX_SNAPSHOT_CHUNKS);
        // 外部认领（D-316）：只读现问、只发窗口内 ⇒ 界面能给它们单独标记，而门禁那一侧同样算保护区
        Set<Long> external = ClaimSources.collect(level, center.x, center.z, EXTERNAL_WINDOW_CHUNKS);
        long[] externalKeys = new long[external.size()];
        int index = 0;
        for (long key : external) {
            externalKeys[index++] = key;
        }
        return own.withExternal(externalKeys);
    }

    /**
     * 从认领集合里挑**离玩家最近**的至多 {@code limit} 个区块（纯函数 ⇒ 夹具直接测）。
     *
     * <p>为什么要挑而不是全发：旧 `add-area` 造出的圆可能一次认领上千区块，认领集合再被别人
     * 恶意刷大 ⇒ 快照必须**有界**；而界面只看得见玩家周围那几十格 ⇒ "最近的先发"保证
     * **看得见的部分永远是完整的**，被截断的只是远端（并如实置 {@code truncated}）。
     */
    public static ProtectionClaimsPacket selectNearest(ResourceLocation dimension, Set<Long> claimed,
                                                       int centerChunkX, int centerChunkZ, int limit) {
        int safeLimit = Math.max(0, limit);
        List<Long> sorted = new ArrayList<>(claimed);
        sorted.sort(Comparator.comparingLong(key -> distanceSq(key, centerChunkX, centerChunkZ)));
        boolean truncated = sorted.size() > safeLimit;
        if (truncated) {
            sorted = sorted.subList(0, safeLimit);
        }
        long[] keys = new long[sorted.size()];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = sorted.get(i);
        }
        return new ProtectionClaimsPacket(dimension, truncated, keys);
    }

    private static long distanceSq(long chunkKey, int centerChunkX, int centerChunkZ) {
        long dx = (long) ChunkPos.getX(chunkKey) - centerChunkX;
        long dz = (long) ChunkPos.getZ(chunkKey) - centerChunkZ;
        return dx * dx + dz * dz;
    }
}
