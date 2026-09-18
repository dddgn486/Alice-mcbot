package com.dddgn.alice.protection;

import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * **外部认领源**（D-316）：把"别人的地盘"接进保护区闸门，而**不复制**他们的数据。
 *
 * <p>为什么是这个形状：
 * <ul>
 *   <li><b>只读、不复制</b>：{@link SafeZoneData} 仍然只装"我们自己的认领"；外部来源**每次现问**
 *       （它们随时在变：玩家在 FTB 里点一下、队伍解散、认领过期）⇒ 抄一份进我们的存档 =
 *       两套真相，必然漂移。夹具会**断言**这件事（外部认领不会出现在 `claimedChunkCount()` 里）。</li>
 *   <li><b>软依赖 + 可注入</b>：真实适配器（`compat.ftbchunks.FtbChunksClaims`）靠反射挂上来，
 *       模组不在场时它自己空转（{@code isClaimed} 恒 false）⇒ 行为与没有兼容时**完全一致**；
 *       无头夹具注册一个**假源**就能离线判"外部认领会不会真的拦住破坏"，不必安装那个模组。</li>
 *   <li><b>单一入口</b>：所有消费者（`BlockBreakSafety` / `BlockInteraction` / 候选源 / `CapabilityGate`）
 *       本来就走 {@link SafeZoneData#protectionReason} ⇒ 挂在那一处即可，**不改任何调用点**。</li>
 * </ul>
 *
 * <p>⚠️ **失败方向是"跳过 + 响亮告警"，不是"一律拒绝"**：第三方模组升级导致查询抛异常时，
 * 若改成 fail-closed，会让 bot 在**全世界**都不能动（比少一层外部保护糟得多）。夹具用"会抛异常的假源"
 * 把这条钉住（不许把异常冒泡出去）。
 */
public final class ClaimSources {

    /** 一个外部认领来源。实现必须**只读、无副作用、可被高频调用**（候选源是半径³ 的循环）。 */
    public interface Source {

        /** 来源 id：进理由码（{@code protected_<id>}）与日志，例如 {@code ftb_claim}。 */
        String id();

        /** 该区块是否被这个来源认领。 */
        boolean isClaimed(ServerLevel level, ChunkPos pos);

        /**
         * 收集窗口内的认领（给地图快照用）。
         *
         * <p>默认实现逐格问 {@link #isClaimed}：窗口是 ±{@code radius} 个区块，
         * {@code MAX_GRID=25} 的界面最多要看 ±12 ⇒ 默认实现够用，别为省几次查询引入第二种口径。
         */
        default void collect(ServerLevel level, int centerChunkX, int centerChunkZ, int radius, Set<Long> out) {
            for (int chunkX = centerChunkX - radius; chunkX <= centerChunkX + radius; chunkX++) {
                for (int chunkZ = centerChunkZ - radius; chunkZ <= centerChunkZ + radius; chunkZ++) {
                    if (isClaimed(level, new ChunkPos(chunkX, chunkZ))) {
                        out.add(ChunkPos.asLong(chunkX, chunkZ));
                    }
                }
            }
        }
    }

    private static final List<Source> SOURCES = new CopyOnWriteArrayList<>();

    private ClaimSources() {
    }

    /** 挂上一个来源（幂等：同一个实例重复注册只留一份）。 */
    public static void register(Source source) {
        if (source != null && !SOURCES.contains(source)) {
            SOURCES.add(source);
        }
    }

    /**
     * 摘掉一个来源。
     *
     * <p>⚠️ 刻意**没有** {@code clear()}：真模组适配器是在 mod 构造期注册的，一个"清空"很容易被
     * 夹具顺手用掉，把真兼容静默关死。夹具用 {@code unregister(自己的假源)} 收尾。
     */
    public static void unregister(Source source) {
        SOURCES.remove(source);
    }

    public static int size() {
        return SOURCES.size();
    }

    public static List<String> ids() {
        List<String> out = new ArrayList<>();
        for (Source source : SOURCES) {
            out.add(source.id());
        }
        return out;
    }

    /**
     * 该位置的**外部**拒绝理由（{@code protected_<id>}）；没有任何来源命中则返回 {@code null}。
     *
     * <p>按注册顺序取第一个命中的来源。查询抛异常 ⇒ **跳过该来源并告警**（不许冒泡：一次第三方小版本
     * 升级不该让 bot 瘫掉）。
     */
    public static String reasonFor(ServerLevel level, BlockPos pos) {
        if (SOURCES.isEmpty()) {
            return null;
        }
        ChunkPos chunk = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
        for (Source source : SOURCES) {
            try {
                if (source.isClaimed(level, chunk)) {
                    return "protected_" + source.id();
                }
            } catch (RuntimeException | LinkageError broken) {
                BotLog.warn("[Protection] 外部认领源 {} 查询失败（本位置跳过它）: {}",
                        source.id(), broken.toString());
            }
        }
        return null;
    }

    /** 收集所有外部来源在窗口内的认领（去重合并；异常来源同样只跳过）。 */
    public static Set<Long> collect(ServerLevel level, int centerChunkX, int centerChunkZ, int radius) {
        Set<Long> out = new LinkedHashSet<>();
        for (Source source : SOURCES) {
            try {
                source.collect(level, centerChunkX, centerChunkZ, radius, out);
            } catch (RuntimeException | LinkageError broken) {
                BotLog.warn("[Protection] 外部认领源 {} 窗口收集失败（跳过它）: {}",
                        source.id(), broken.toString());
            }
        }
        return out;
    }
}
