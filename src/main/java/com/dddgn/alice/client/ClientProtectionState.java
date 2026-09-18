package com.dddgn.alice.client;

import com.dddgn.alice.network.AliceNetwork;
import com.dddgn.alice.network.ProtectionClaimsPacket;
import com.dddgn.alice.network.ProtectionSyncRequestPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 客户端侧的**保护区状态**（D-314）：服务端快照 + 待提交的选择。
 *
 * <p>两条纪律（都是"客户端不许自己造真相"的落点）：
 * <ol>
 *   <li>{@link #claimed()} **只由 S2C 快照写入**；界面从不自己改它。没有本维度快照时
 *       {@link #hasSnapshotFor} 为 false ⇒ 界面**拒绝编辑**（宁可不能点，也不能盲取消认领）；</li>
 *   <li>待提交（{@link #pending}）是**意图**，不是状态：界面关闭时整批发给服务端，
 *       服务端回推快照后才算数。快照到达时，目标状态已与服务端一致的条目会被自动清掉。</li>
 * </ol>
 *
 * <p>待提交刻意**活在这个静态状态里**而不是 Screen 对象上：界面被顶掉（比如按 T 打开聊天会让
 * Screen 被替换、只有 {@code removed()} 会被调用）时，选择不会随对象一起蒸发。
 */
@OnlyIn(Dist.CLIENT)
public final class ClientProtectionState {

    /** 快照所属维度（null = 还没有快照）。 */
    private static volatile ResourceLocation snapshotDimension;
    private static volatile Set<Long> claimed = Set.of();
    private static volatile boolean truncated;

    /** 待提交：区块键 → 目标状态（true = 认领 / false = 取消认领）。只在客户端主线程访问。 */
    private static final Map<Long, Boolean> PENDING = new LinkedHashMap<>();
    private static ResourceLocation pendingDimension;

    private ClientProtectionState() {
    }

    // ==================== 服务端快照 ====================

    public static void accept(ProtectionClaimsPacket packet) {
        Set<Long> next = new HashSet<>();
        for (long key : packet.chunkKeys()) {
            next.add(key);
        }
        snapshotDimension = packet.dimension();
        claimed = Set.copyOf(next);
        truncated = packet.truncated();
        // 目标状态已与服务端一致 ⇒ 这条不必再提交（也用于界面上"已生效"的显示）
        if (pendingDimension != null && pendingDimension.equals(packet.dimension())) {
            PENDING.entrySet().removeIf(entry -> claimed.contains(entry.getKey()) == entry.getValue());
            if (PENDING.isEmpty()) {
                pendingDimension = null;
            }
        }
    }

    public static boolean hasSnapshotFor(ResourceLocation dimension) {
        return dimension != null && dimension.equals(snapshotDimension);
    }

    public static ResourceLocation snapshotDimension() {
        return snapshotDimension;
    }

    public static Set<Long> claimed() {
        return claimed;
    }

    /** 快照被服务端截断（还有更多认领不在本快照里）—— 界面必须说出来。 */
    public static boolean truncated() {
        return truncated;
    }

    public static boolean isClaimed(long chunkKey) {
        return claimed.contains(chunkKey);
    }

    /** 玩家当前所在维度（拿不到时返回 null）。 */
    public static ResourceLocation currentDimension() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? null : minecraft.level.dimension().location();
    }

    // ==================== 待提交 ====================

    public static boolean hasPending() {
        return !PENDING.isEmpty();
    }

    public static int pendingCount() {
        return PENDING.size();
    }

    /** 该区块的待提交目标状态；没有待提交返回 null。 */
    public static Boolean pending(long chunkKey) {
        return PENDING.get(chunkKey);
    }

    /**
     * 记下"我想把这块地改成 claim"。**只有已有本维度快照时才接受**（否则就是盲改）。
     *
     * @return 是否被接受
     */
    public static boolean setPending(long chunkKey, boolean claim, ResourceLocation dimension) {
        if (!hasSnapshotFor(dimension)) {
            return false;
        }
        if (pendingDimension != null && !pendingDimension.equals(dimension)) {
            return false;       // 跨维度的待提交不许混在一起（服务端只按发送者当前维度落库）
        }
        pendingDimension = dimension;
        PENDING.put(chunkKey, claim);
        return true;
    }

    /** 撤掉某区块的待提交（用户又点回了服务端状态）。 */
    public static void clearPending(long chunkKey) {
        PENDING.remove(chunkKey);
        if (PENDING.isEmpty()) {
            pendingDimension = null;
        }
    }

    /** 取出某个动作的全部待提交区块（true = 认领批 / false = 取消批）。 */
    public static long[] pendingKeys(boolean claim) {
        return PENDING.entrySet().stream()
                .filter(entry -> entry.getValue() == claim)
                .mapToLong(Map.Entry::getKey)
                .toArray();
    }

    /** 待提交所属维度（null = 没有待提交）。 */
    public static ResourceLocation pendingDimension() {
        return pendingDimension;
    }

    public static void clearPendingAll() {
        PENDING.clear();
        pendingDimension = null;
    }

    // ==================== 界面 ====================

    /** 打开保护区界面；已经开着就不重开（避免快照回推把界面顶掉，同 D-091）。 */
    public static void openScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof com.dddgn.alice.client.gui.ProtectionMapScreen) {
            return;
        }
        minecraft.setScreen(new com.dddgn.alice.client.gui.ProtectionMapScreen());
    }

    /** 请求一次认领快照（服务端限流；断线时静默不发，待提交保留到下次）。 */
    public static void requestSync() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getConnection() == null) {
            return;
        }
        AliceNetwork.CHANNEL.sendToServer(new ProtectionSyncRequestPacket());
    }
}
