package com.dddgn.alice.job.mine;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **挖矿扫描记忆**（`D-329` §2 **S5**）：记住"**哪个区块、为哪个目标、在哪个 tick 扫过、见到几个目标块**"。
 *
 * <p>为什么需要它：`D-329` ① 的裁定是「扫描边界 = **不加载**」⇒ 大范围只能靠「**时间 + 记忆累积**」，
 * 而"累积"必须**跨 tick、跨任务**，所以必须落盘、必须有界。
 *
 * <p>⭐⭐ **记忆里只有"计数"，没有"位置"** —— 这是**类型即约束**（与 `D-354` 的 retrofit 同一个手法）：
 * 因为存不下坐标，记忆就**不可能**被当成"该挖哪一格"的事实来源。真正的目标位置**永远**只能来自
 * **这一次**的扫描 + 身份复检（`D-348` 的纪律：代理判据 ≠ 世界事实）。
 *
 * <p>**有界 + 确定性淘汰**：条目数上限 {@link #DEFAULT_CAP}（夹具可临时调小）；超限时先淘汰 `lastTick`
 * 最旧的，同 tick 再按「维度 → 目标 → 区块键」字典序淘汰（**不许随机**）⇒ 同一输入两次跑出同一结果。
 *
 * <p>**写入门槛**：只有**扫完**（`done`）的扫描才写 —— 被预算截断的扫描**不写**。
 * 把"没看完"记成"扫过了"，正是 `S3` 禁止的那类谎言。
 */
public final class MineScanMemoryData extends SavedData {

    public static final String DATA_KEY = "alice_mine_scan_memory";

    private static final int FORMAT_VERSION = 1;

    /** 条目上限（一个条目 = 一个区块 × 一个目标）。 */
    public static final int DEFAULT_CAP = 4096;

    /**
     * 一条记忆。
     *
     * @param lastTick     最后一次扫到这个区块的 tick
     * @param hits         当时见到的**目标方块个数**
     * @param cellsVisited 当时**实际考察过的格数**（整卷扫描时一个区块最多 16×16×高度段；
     *                     ⚠️ 它**不是**"该区块已完整扫过"的证明，调用方要自己按覆盖口径判读）
     */
    public record Memory(long lastTick, int hits, int cellsVisited) {
    }

    /** 键 = `维度|目标`；值 = 区块键 → 记忆。 */
    private final Map<String, Map<Long, Memory>> entries = new LinkedHashMap<>();

    /** 本次淘汰掉的条目数（诊断/判据用：有界不是一句话，是能被数出来的）。 */
    private int evictedTotal;

    /** 夹具可临时调小上限（默认 {@link #DEFAULT_CAP}）；`0` 或负数 = 用默认值。 */
    private int cap = DEFAULT_CAP;

    public static MineScanMemoryData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(MineScanMemoryData::load, MineScanMemoryData::new, DATA_KEY);
    }

    /** 从 NBT 读入（SavedData 工厂入口；夹具也用它做**存/读往返**契约测试）。 */
    public static MineScanMemoryData load(CompoundTag root) {
        MineScanMemoryData data = new MineScanMemoryData();
        int version = root.getInt("version");
        if (version > FORMAT_VERSION) {
            // 未来版本写下的数据：**响亮地**保留为空而不是猜语义（本项目一贯口径）
            return data;
        }
        data.cap = root.contains("cap") ? Math.max(1, root.getInt("cap")) : DEFAULT_CAP;
        for (Tag bucketTag : root.getList("buckets", Tag.TAG_COMPOUND)) {
            CompoundTag bucket = (CompoundTag) bucketTag;
            String key = bucket.getString("key");
            if (key.isEmpty()) {
                continue;
            }
            Map<Long, Memory> byChunk = new LinkedHashMap<>();
            for (Tag memTag : bucket.getList("memories", Tag.TAG_COMPOUND)) {
                CompoundTag mem = (CompoundTag) memTag;
                byChunk.put(mem.getLong("chunk"),
                        new Memory(mem.getLong("tick"), mem.getInt("hits"), mem.getInt("cells")));
            }
            if (!byChunk.isEmpty()) {
                data.entries.put(key, byChunk);
            }
        }
        data.evictedTotal = root.getInt("evicted");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        root.putInt("version", FORMAT_VERSION);
        root.putInt("cap", cap);
        root.putInt("evicted", evictedTotal);
        ListTag buckets = new ListTag();
        for (Map.Entry<String, Map<Long, Memory>> entry : entries.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            CompoundTag bucket = new CompoundTag();
            bucket.putString("key", entry.getKey());
            ListTag memories = new ListTag();
            for (Map.Entry<Long, Memory> byChunk : entry.getValue().entrySet()) {
                CompoundTag mem = new CompoundTag();
                mem.putLong("chunk", byChunk.getKey());
                mem.putLong("tick", byChunk.getValue().lastTick());
                mem.putInt("hits", byChunk.getValue().hits());
                mem.putInt("cells", byChunk.getValue().cellsVisited());
                memories.add(mem);
            }
            bucket.put("memories", memories);
            buckets.add(bucket);
        }
        root.put("buckets", buckets);
        return root;
    }

    // ==================== 写 ====================

    /** 夹具用：调小上限以便**确定性**地触发淘汰（`<=0` 恢复默认）。 */
    public void setCapForTesting(int newCap) {
        this.cap = newCap <= 0 ? DEFAULT_CAP : newCap;
        setDirty();
    }

    public int cap() {
        return cap;
    }

    public int evictedTotal() {
        return evictedTotal;
    }

    /** 条目总数（有界性判据直接读它）。 */
    public int totalEntries() {
        int total = 0;
        for (Map<Long, Memory> byChunk : entries.values()) {
            total += byChunk.size();
        }
        return total;
    }

    public static String bucketKey(ResourceLocation dimension, String targetKey) {
        return dimension + "|" + targetKey;
    }

    /** 记一条"这个区块被扫过"。**只有扫完的扫描才该调它**（截断的扫描不许写，见类注释）。 */
    public void noteScanned(ServerLevel level, String targetKey, int chunkX, int chunkZ,
                            long tick, int hits, int cellsVisited) {
        ResourceLocation dimension = level.dimension().location();
        String key = bucketKey(dimension, targetKey);
        entries.computeIfAbsent(key, ignored -> new LinkedHashMap<>())
                .put(ChunkPos.asLong(chunkX, chunkZ), new Memory(tick, hits, cellsVisited));
        evictIfNeeded();
        setDirty();
    }

    /**
     * **有界 + 确定性淘汰**：先按 `lastTick` 从旧到新，同 tick 用「bucketKey → 区块键」字典序。
     *
     * <p>⚠️ 顺序必须是**全序**（不许随机、不许依赖 `HashMap` 的迭代顺序）—— 否则"同样输入两次跑出不同记忆"
     * 就成了不可复现的现场，判据也就无从写起。
     */
    private void evictIfNeeded() {
        List<Map.Entry<String, Long>> all = new ArrayList<>();
        for (Map.Entry<String, Map<Long, Memory>> bucket : entries.entrySet()) {
            for (Long chunkKey : bucket.getValue().keySet()) {
                all.add(Map.entry(bucket.getKey(), chunkKey));
            }
        }
        int overflow = all.size() - cap;
        if (overflow <= 0) {
            return;
        }
        all.sort(Comparator
                .comparingLong((Map.Entry<String, Long> e) -> entries.get(e.getKey()).get(e.getValue()).lastTick())
                .thenComparing(Map.Entry::getKey)
                .thenComparingLong(Map.Entry::getValue));
        for (int i = 0; i < overflow; i++) {
            Map.Entry<String, Long> victim = all.get(i);
            Map<Long, Memory> bucket = entries.get(victim.getKey());
            bucket.remove(victim.getValue());
            if (bucket.isEmpty()) {
                entries.remove(victim.getKey());
            }
            evictedTotal++;
        }
    }

    // ==================== 读 ====================

    /** 某区块的记忆；`null` = 没扫过（**不是**"没有目标方块"—— 那是两个完全不同的结论）。 */
    public Memory at(ServerLevel level, String targetKey, int chunkX, int chunkZ) {
        Map<Long, Memory> bucket = entries.get(bucketKey(level.dimension().location(), targetKey));
        return bucket == null ? null : bucket.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    /**
     * **某个目标在某片区域内的记忆汇总**（给决策层看的**事实串**，不参与"该挖哪一格"的判定）。
     *
     * @return `[已扫区块数, 累计命中数, 最新 tick]`；没扫过则 `[0, 0, -1]`
     */
    public long[] nearbySummary(ServerLevel level, String targetKey, int centerChunkX, int centerChunkZ,
                                int chunkRadius) {
        Map<Long, Memory> bucket = entries.get(bucketKey(level.dimension().location(), targetKey));
        if (bucket == null || bucket.isEmpty()) {
            return new long[]{0L, 0L, -1L};
        }
        long chunks = 0L;
        long hits = 0L;
        long latest = -1L;
        for (Map.Entry<Long, Memory> entry : bucket.entrySet()) {
            int cx = ChunkPos.getX(entry.getKey());
            int cz = ChunkPos.getZ(entry.getKey());
            if (Math.abs(cx - centerChunkX) > chunkRadius || Math.abs(cz - centerChunkZ) > chunkRadius) {
                continue;
            }
            chunks++;
            hits += entry.getValue().hits();
            latest = Math.max(latest, entry.getValue().lastTick());
        }
        return new long[]{chunks, hits, latest};
    }

    /** 诊断串（日志/判据都能读；**不含坐标** —— 记忆里本来就没有位置）。 */
    public String describe() {
        return "entries=" + totalEntries() + " cap=" + cap + " evicted=" + evictedTotal
                + " buckets=" + entries.size();
    }

    /** 清空（夹具收尾复位用）。 */
    public void clear() {
        entries.clear();
        evictedTotal = 0;
        setDirty();
    }

    /** 目标键（记忆按它分桶）：用 `MineCandidateSource.Target#describe()`，保证与日志同口径。 */
    public static String targetKey(MineCandidateSource.Target target) {
        return target.describe();
    }
}
