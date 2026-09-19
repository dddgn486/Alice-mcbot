package com.dddgn.alice.protection;

import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Alice 的破坏保护规则，保存在主世界 {@code SavedData}（键 {@code alice_safe_zones}）。
 *
 * <p><b>形状（2026-09-18 变更，D-305 ①′ / D-313）</b>：区域从"水平圆形半径"改为 ⭐ **区块级 2D 认领**
 * —— 与 FTB Chunks 同一套心智：**忽略 Y、覆盖该维度全高度**、按区块（16×16 列）认领。
 * 方块规则（ID/标签黑名单）仍是全世界通用的，不随形状变化。
 *
 * <p><b>为什么改</b>：① 用户裁定"按区块划分"（D-305 ①′-重开的第 ① 条）；② 认领界面是**地图式勾选**
 * （D-307），区块天然是勾选单位；③ 判定从"遍历区域列表做内积比较（O(区域数)）"变成"集合查键（O(1)）"
 * —— 候选源扫描对每个方块都要问一次（`MineCandidateSource` 是半径³ 的循环）⇒ 这是热路径。
 *
 * <p><b>迁移（不静默丢）</b>：旧的 {@code areas=[{dimension,x,y,z,radius}]} 数据在加载时**自动转换**成区块集合，
 * 规则 = **与该圆相交即认领**（保守：宁可多保护一点，也不少），并**响亮提示**（服务端日志 WARN + 计数进
 * {@link #summary()} ⇒ `/alice protection list` 看得见）。丢弃的坏条目（维度解析失败等）同样计数并告警。
 *
 * <p>⚠️ 本类**只回答"能不能动"**，不授权、不记账（记账在 `ledger`，授权在 `WritePolicyMatrix`/`WriteBudget`）。
 *
 * <p><b>层次（`D-338` ②③，2026-09-19）</b>：玩家认领的区块 = **保护区**（具体父类实例：保护资产）；
 * **安全区**是它的**子类声明**，且**必须是保护区的子集**（{@link #declareSafe} 对未认领区块直接拒绝
 * `NOT_PROTECTED`）—— 保护区的破坏闸门对安全区自动成立，不需要第二套判据。返程目的地的**优先级**
 * 是 归位点 > 安全区 > 保护区，终点取**内部区块**（{@link #internalChunks}：自身及四邻都已认领 ⇒
 * "向区域中心靠但不要求到中心"）；内部区块为空（1 区块 / 条带 / 2×2）⇒ 消费方按**退化**处理
 * （等价"进区即到"），这正是小区域的正确行为。
 */
public final class SafeZoneData extends SavedData {

    public static final String DATA_KEY = "alice_safe_zones";

    /** 持久化格式版本：1 = 旧圆形半径（只读兼容）；2 = 区块认领（当前写入）。 */
    private static final int FORMAT_VERSION = 2;

    /** 维度 → 认领的区块键集合（键 = {@link ChunkPos#asLong(int, int)}）。 */
    private final Map<ResourceLocation, Set<Long>> claimedChunks = new LinkedHashMap<>();
    /**
     * 维度 → **安全区**区块键集合（`D-338` ②）：保护区的**子类声明**。
     * ⚠️ 不变量：**安全区 ⊆ 保护区**（{@link #declareSafe} 拒绝未认领的区块；{@link #unclaim} 连带清除；
     * {@link #load} 丢掉并计数任何孤儿条目 —— 三条路都堵住，不变量就不可能被单边破坏）。
     */
    private final Map<ResourceLocation, Set<Long>> safeChunks = new LinkedHashMap<>();
    private final Set<ResourceLocation> protectedBlocks = new LinkedHashSet<>();
    private final Set<ResourceLocation> protectedTags = new LinkedHashSet<>();
    /** 本次加载从旧格式转换过来的区域条数（只读诊断用；>0 时加载期会告警）。 */
    private int migratedLegacyAreas;
    /** 转换不了的旧条目条数（维度解析失败等）—— 同样要"响亮"，不能静默丢。 */
    private int droppedLegacyAreas;
    /** 加载期丢掉的**孤儿安全区**（不在认领集合里的安全区标记）—— 同样计数 + 告警。 */
    private int droppedOrphanSafeClaims;

    public static SafeZoneData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(SafeZoneData::load, SafeZoneData::new, DATA_KEY);
    }

    /** 从 NBT 读入（SavedData 工厂入口；夹具也用它做**存/读往返**契约测试）。 */
    public static SafeZoneData load(CompoundTag root) {
        SafeZoneData data = new SafeZoneData();
        // ① 当前格式：区块认领
        for (Tag entry : root.getList("claims", Tag.TAG_COMPOUND)) {
            CompoundTag tag = (CompoundTag) entry;
            ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("dimension"));
            if (dimension == null) {
                continue;
            }
            Set<Long> chunks = data.claimedChunks.computeIfAbsent(dimension, ignored -> new LinkedHashSet<>());
            for (long key : tag.getLongArray("chunks")) {
                chunks.add(key);
            }
        }
        // ①′ 安全区（`D-338` ②）：**必须在 claims 之后读** —— 孤儿判定要用认领集合。
        // 孤儿（安全区标记指向未认领区块）只可能来自外部改档/未来版本 ⇒ **丢掉 + 计数 + 告警**，不静默留。
        for (Tag entry : root.getList("safe_chunks", Tag.TAG_COMPOUND)) {
            CompoundTag tag = (CompoundTag) entry;
            ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("dimension"));
            if (dimension == null) {
                data.droppedOrphanSafeClaims += tag.getLongArray("chunks").length;
                continue;
            }
            Set<Long> claimed = data.claimedChunks.getOrDefault(dimension, Set.of());
            for (long key : tag.getLongArray("chunks")) {
                if (claimed.contains(key)) {
                    data.safeChunks.computeIfAbsent(dimension, ignored -> new LinkedHashSet<>()).add(key);
                } else {
                    data.droppedOrphanSafeClaims++;
                }
            }
        }
        // ② 旧格式（v1，水平圆形半径）⇒ 自动转区块集合 + 计数（调用方/本类负责"响亮提示"）
        for (Tag entry : root.getList("areas", Tag.TAG_COMPOUND)) {
            CompoundTag tag = (CompoundTag) entry;
            ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("dimension"));
            if (dimension == null || !tag.contains("radius", Tag.TAG_INT)) {
                data.droppedLegacyAreas++;
                continue;
            }
            data.claimCircle(dimension,
                    new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")), tag.getInt("radius"));
            data.migratedLegacyAreas++;
        }
        readIds(root.getList("blocks", Tag.TAG_STRING), data.protectedBlocks);
        readIds(root.getList("tags", Tag.TAG_STRING), data.protectedTags);
        if (data.migratedLegacyAreas > 0 || data.droppedLegacyAreas > 0) {
            // ⭐ 响亮提示（D-307：不静默丢）。这条日志是**人看得见**的迁移证据，也是夹具的判据来源。
            BotLog.warn("[SafeZone] 旧格式（圆形半径）保护区已迁移为**区块认领**：migrated={} 条 / 丢弃={} 条"
                            + "（规则 = 与该圆相交即认领；忽略 Y ⇒ 覆盖全高度）⇒ 现在 {}",
                    data.migratedLegacyAreas, data.droppedLegacyAreas, data.summary());
        }
        if (data.droppedOrphanSafeClaims > 0) {
            // 安全区必须是保护区的子集（`D-338` ②）⇒ 孤儿条目既不能留（会破坏不变量），也不能静默丢
            BotLog.warn("[SafeZone] 孤儿**安全区**标记已丢弃：orphans={}（安全区 ⊆ 保护区；这些区块不在认领集合里）"
                            + "⇒ 现在 {}",
                    data.droppedOrphanSafeClaims, data.summary());
        }
        return data;
    }

    private static void readIds(ListTag source, Set<ResourceLocation> target) {
        for (Tag value : source) {
            ResourceLocation id = ResourceLocation.tryParse(value.getAsString());
            if (id != null) {
                target.add(id);
            }
        }
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        root.putInt("version", FORMAT_VERSION);
        ListTag claims = new ListTag();
        for (Map.Entry<ResourceLocation, Set<Long>> entry : claimedChunks.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            CompoundTag tag = new CompoundTag();
            tag.putString("dimension", entry.getKey().toString());
            long[] keys = new long[entry.getValue().size()];
            int i = 0;
            for (long key : entry.getValue()) {
                keys[i++] = key;
            }
            tag.putLongArray("chunks", keys);
            claims.add(tag);
        }
        root.put("claims", claims);
        ListTag safe = new ListTag();
        for (Map.Entry<ResourceLocation, Set<Long>> entry : safeChunks.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            CompoundTag tag = new CompoundTag();
            tag.putString("dimension", entry.getKey().toString());
            long[] keys = new long[entry.getValue().size()];
            int i = 0;
            for (long key : entry.getValue()) {
                keys[i++] = key;
            }
            tag.putLongArray("chunks", keys);
            safe.add(tag);
        }
        root.put("safe_chunks", safe);
        root.put("blocks", writeIds(protectedBlocks));
        root.put("tags", writeIds(protectedTags));
        return root;
    }

    private static ListTag writeIds(Set<ResourceLocation> ids) {
        ListTag result = new ListTag();
        for (ResourceLocation id : ids) {
            result.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
        }
        return result;
    }

    // ==================== 认领（区块级原语）====================

    /** 认领一个区块（忽略 Y ⇒ 覆盖该维度全高度）。返回是否发生了变化。 */
    public boolean claim(ServerLevel level, int chunkX, int chunkZ) {
        boolean changed = claimBlocking(level.dimension().location(), chunkX, chunkZ);
        if (changed) {
            setDirty();
        }
        return changed;
    }

    /** 取消认领一个区块。返回是否发生了变化。 */
    public boolean unclaim(ServerLevel level, int chunkX, int chunkZ) {
        ResourceLocation dimension = level.dimension().location();
        Set<Long> chunks = claimedChunks.get(dimension);
        if (chunks == null || !chunks.remove(ChunkPos.asLong(chunkX, chunkZ))) {
            return false;
        }
        // 不变量（D-338 ②）：取消保护区**连带**取消该区块的安全区声明 —— 否则安全区会存活在保护区之外
        clearSafeInternal(dimension, ChunkPos.asLong(chunkX, chunkZ));
        if (chunks.isEmpty()) {
            // 空集合不留档：否则 summary() 会报「dims=2、chunks=0」这种把人看糊涂的计数
            // （诊断字符串是给人读的 ⇒ 它自己必须是诚实的；save() 本来就会跳过空维度）
            claimedChunks.remove(dimension);
        }
        setDirty();
        return true;
    }

    /** 取消**包含该坐标**的那个区块的认领。返回是否发生了变化。 */
    public boolean unclaimAt(ServerLevel level, BlockPos pos) {
        return unclaim(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    /**
     * 认领"以 `center` 为心、`radius` 为半径的水平圆"**所及的全部区块**（相交即认领）。
     *
     * <p>这条规则同时是**旧格式迁移**的规则（一个函数、一处口径 ⇒ 不会出现"迁移与命令各按一套算"）。
     * 保守方向的选择理由：漏保护（圆边上的区块没被认领）会让 bot 挖进用户想保护的地方，
     * 而多保护只会在相邻区块多拦一次（用户点一下即可取消）。
     *
     * @return 本次**新增**的区块数（已认领的不重复计数）
     */
    public int claimCircle(ServerLevel level, BlockPos center, int radius) {
        return claimCircle(level.dimension().location(), center, radius);
    }

    private int claimCircle(ResourceLocation dimension, BlockPos center, int radius) {
        int minChunkX = (center.getX() - radius) >> 4;
        int maxChunkX = (center.getX() + radius) >> 4;
        int minChunkZ = (center.getZ() - radius) >> 4;
        int maxChunkZ = (center.getZ() + radius) >> 4;
        int added = 0;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (circleIntersectsChunk(center, radius, chunkX, chunkZ)
                        && claimBlocking(dimension, chunkX, chunkZ)) {
                    added++;
                }
            }
        }
        if (added > 0) {
            setDirty();
        }
        return added;
    }

    /** 圆与区块的**矩形**是否相交（用区块内离圆心最近的点判距）—— "相交即认领"的判据。 */
    private static boolean circleIntersectsChunk(BlockPos center, int radius, int chunkX, int chunkZ) {
        long nearestX = Math.max((long) chunkX << 4, Math.min(center.getX(), ((long) chunkX << 4) + 15));
        long nearestZ = Math.max((long) chunkZ << 4, Math.min(center.getZ(), ((long) chunkZ << 4) + 15));
        long dx = nearestX - center.getX();
        long dz = nearestZ - center.getZ();
        return dx * dx + dz * dz <= (long) radius * radius;
    }

    private boolean claimBlocking(ResourceLocation dimension, int chunkX, int chunkZ) {
        return claimedChunks.computeIfAbsent(dimension, ignored -> new LinkedHashSet<>())
                .add(ChunkPos.asLong(chunkX, chunkZ));
    }

    /**
     * **该位置是否落在已认领区块里**（`D-327` 机制 B 的到达判据）——**纯认领集查询，不读方块**。
     *
     * <p>为什么不复用 {@link #protectionReason}：那个方法为了"保护方块/标签"还会**读方块状态**
     * （`level.getBlockState`）⇒ 对未加载区块会触发同步加载（红线 `D-132` 同类）。
     * 到达判据每 tick 都要问，必须是零副作用的。
     */
    public boolean isClaimed(ServerLevel level, BlockPos pos) {
        return claims(level.dimension().location())
                .contains(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
    }

    /**
     * ⭐ **回程终点**（`D-327` 机制 B / `§5.2` 那个"回哪一格"的定案）：**离 {@code from} 最近的认领区块里、
     * 离它最近的那一格**（XZ；Y 取 {@code from} 的 Y 作占位——实际落脚点由返程任务在到达后解析）。
     *
     * <p>为什么是这条规则：① 目的是"回到保护区**里面**"，所以终点取**区内**的格（不是边界外）；
     * ② 多个认领区时取**最近**的（少走路 = 更快脱离野外）；③ 完全不读方块 ⇒ 远处未加载也照样能算方向。
     * ⚠️ 这是**主线的选择**（用户把该问题挂账，`D-327` 执行时定案）；要改成"某个固定集合点"只需换本方法。
     *
     * @return 最近的区内格；**该维度没有任何认领 ⇒ `null`**（调用方按"没有安全区"处理）
     */
    public BlockPos nearestClaimedCell(ServerLevel level, BlockPos from) {
        return nearestCellIn(claims(level.dimension().location()), from);
    }

    /**
     * ⭐ **返程目标区**（`D-338` ③ 优先级链的第一段）：**有安全区 ⇒ 安全区；没有 ⇒ 保护区**。
     * 纯集合查询（零方块读取、零区块加载）。
     */
    public Set<Long> returnZoneChunks(ResourceLocation dimension) {
        Set<Long> safe = safeClaims(dimension);
        return safe.isEmpty() ? claims(dimension) : safe;
    }

    /**
     * ⭐ **返程到达集**（`D-338` ③）：目标区的**内部区块**（"向区域中心靠"的**自适应安全范围**）；
     * 内部集为空（1 区块 / 条带 / ≤3×2 的小区）⇒ **退化为目标区本身** = "进区即到"
     * （用户 2026-09-19 裁定：小基地不要加几何，正解是玩家设定归位点）。
     */
    public Set<Long> returnArrivalChunks(ResourceLocation dimension) {
        Set<Long> zone = returnZoneChunks(dimension);
        Set<Long> internal = internalChunks(zone);
        return internal.isEmpty() ? zone : internal;
    }

    /**
     * **到达判据**（每 tick 都要问 ⇒ 纯集合查询、零副作用）：脚位所在区块在返程到达集里。
     * `D-327` 机制 B 用 `isClaimed`（"走到认领区块边界就算到家"）；`D-338` ③ 起改成这一条
     * （"走到**安全区的内部**才算到家"）。
     */
    public boolean isInReturnZone(ServerLevel level, BlockPos pos) {
        return returnArrivalChunks(level.dimension().location())
                .contains(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
    }

    /** **返程终点**：到达集里离 {@code from} 最近的那一格；世界没有任何区 ⇒ `null`。 */
    public BlockPos nearestReturnCell(ServerLevel level, BlockPos from) {
        return nearestCellIn(returnArrivalChunks(level.dimension().location()), from);
    }

    /** 集合里离 {@code from} 最近的那一格（XZ；Y 取 {@code from} 的 Y 作占位）；空集 ⇒ `null`。 */
    private static BlockPos nearestCellIn(Set<Long> chunks, BlockPos from) {
        long best = Long.MAX_VALUE;
        BlockPos bestCell = null;
        for (long key : chunks) {
            int chunkX = ChunkPos.getX(key);
            int chunkZ = ChunkPos.getZ(key);
            // 区块内离 from 最近的格（就是圆/矩形相交判据用的那套夹取）
            int cellX = (int) Math.max((long) chunkX << 4, Math.min(from.getX(), ((long) chunkX << 4) + 15));
            int cellZ = (int) Math.max((long) chunkZ << 4, Math.min(from.getZ(), ((long) chunkZ << 4) + 15));
            long dx = cellX - from.getX();
            long dz = cellZ - from.getZ();
            long distSq = dx * dx + dz * dz;
            if (distSq < best) {
                best = distSq;
                bestCell = new BlockPos(cellX, from.getY(), cellZ);
            }
        }
        return bestCell == null ? null : bestCell.immutable();
    }

    /** 某维度已认领的区块键（**只读**；网络层下发认领元数据时用）。 */
    public Set<Long> claims(ResourceLocation dimension) {
        Set<Long> chunks = claimedChunks.get(dimension);
        return chunks == null ? Set.of() : Collections.unmodifiableSet(chunks);
    }

    /** 已认领的区块总数（跨维度）。 */
    public int claimedChunkCount() {
        int total = 0;
        for (Set<Long> chunks : claimedChunks.values()) {
            total += chunks.size();
        }
        return total;
    }

    public int migratedLegacyAreas() {
        return migratedLegacyAreas;
    }

    public int droppedLegacyAreas() {
        return droppedLegacyAreas;
    }

    // ==================== 安全区（保护区的子类声明，D-338 ②）====================

    /** 安全区声明的结果（命令与将来的勾选界面**共用同一套判据** ⇒ 只有一处口径）。 */
    public enum SafeDeclare {
        /** 从"不是"变成"是"。 */
        DECLARED,
        /** 本来就是安全区（幂等）。 */
        ALREADY,
        /** ⛔ 该区块**未认领保护区** ⇒ 拒绝：安全区 ⊆ 保护区（`D-338` ②）。 */
        NOT_PROTECTED
    }

    /**
     * 把某区块声明为**安全区**（必须已是保护区 —— 保护区的破坏闸门因此自动对它成立）。
     *
     * <p>为什么不自动连带认领保护区：那等于让"声明安全区"顺手扩大资产保护范围，
     * 是**静默提权**。这里选择**明确拒绝**并把下一步告诉调用方（命令会提示"先认领保护区"）。
     */
    public SafeDeclare declareSafe(ServerLevel level, int chunkX, int chunkZ) {
        ResourceLocation dimension = level.dimension().location();
        long key = ChunkPos.asLong(chunkX, chunkZ);
        if (!claimedChunks.getOrDefault(dimension, Set.of()).contains(key)) {
            return SafeDeclare.NOT_PROTECTED;
        }
        if (!safeChunks.computeIfAbsent(dimension, ignored -> new LinkedHashSet<>()).add(key)) {
            return SafeDeclare.ALREADY;
        }
        setDirty();
        return SafeDeclare.DECLARED;
    }

    /** 取消某区块的安全区声明（**保留**其保护区认领）。返回是否发生了变化。 */
    public boolean clearSafe(ServerLevel level, int chunkX, int chunkZ) {
        if (!clearSafeInternal(level.dimension().location(), ChunkPos.asLong(chunkX, chunkZ))) {
            return false;
        }
        setDirty();
        return true;
    }

    private boolean clearSafeInternal(ResourceLocation dimension, long key) {
        Set<Long> chunks = safeChunks.get(dimension);
        if (chunks == null || !chunks.remove(key)) {
            return false;
        }
        if (chunks.isEmpty()) {
            safeChunks.remove(dimension);   // 与认领同一口径：空集合不留档（诊断字符串必须诚实）
        }
        return true;
    }

    /**
     * **该位置是否落在已声明的安全区里**（`D-338` ③ 的返程判据）——**纯认领集查询，不读方块**。
     * 与 {@link #isClaimed} 同一纪律：每 tick 都要问的东西不许有副作用。
     */
    public boolean isSafe(ServerLevel level, BlockPos pos) {
        return safeClaims(level.dimension().location())
                .contains(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
    }

    /** 某维度已声明为安全区的区块键（**只读**）。 */
    public Set<Long> safeClaims(ResourceLocation dimension) {
        Set<Long> chunks = safeChunks.get(dimension);
        return chunks == null ? Set.of() : Collections.unmodifiableSet(chunks);
    }

    /** 已声明的安全区区块总数（跨维度）。 */
    public int safeChunkCount() {
        int total = 0;
        for (Set<Long> chunks : safeChunks.values()) {
            total += chunks.size();
        }
        return total;
    }

    /** 加载期丢掉的孤儿安全区标记数（>0 时加载期会告警；夹具据此断言"不静默丢"）。 */
    public int droppedOrphanSafeClaims() {
        return droppedOrphanSafeClaims;
    }

    /**
     * ⭐ **内部区块**（`D-338` ③ 的"自适应安全范围"）：自身及**四邻**（N/S/E/W）**都**在给定集合里的区块。
     *
     * <p>用途：返程目的地取"内部区块"而不是"认领区块" —— 于是 bot **向区域中心靠**，
     * 不会贴着边界停下（边界另一侧就是野外）。**纯函数**（只查集合、零方块读取、零区块加载）。
     *
     * <p>⚠️ **退化是正确行为**：1 区块 / 条带 / 2×2 的区域**没有**内部区块（返回空集）⇒ 消费方按
     * "进区即到"处理（今天口径）。这也是"自适应"的含义：区域越大，安全范围相对越小。
     * 对角**不计**（四邻口径，2026-09-19 用户选定）。
     *
     * <p>⛔ **不要再为小基地加几何（用户 2026-09-19 裁定，已否决两个提案）**：
     * ① 换**八邻**（Moore）**没用** —— 穷举实测：两种口径都是"自身 + 一圈全在集合里"（4 邻 = ＋字形 5 格、
     * 8 邻 = 完整 3×3 共 9 格）⇒ **1×1 / 2×1 / 2×2 / 3×2 在两种口径下都为空**；且对**矩形**区域两者结果
     * **完全相同**（`(宽−2)×(高−2)`，差别只在非矩形），而 8 邻在非矩形上**更严**（十字形 1→0、3×3 缺角 1→0）；
     * ② **质心退化**与**块级内缩 K 格**两个提案用户**都否决**了："如果有问题应该**鼓励玩家自己设定归位点**，
     * 而不是**优化没必要的逻辑**" ⇒ 小基地 / 想要精确落点的正解 = **玩家设定归位点**（返程最高优先，见 `D-338` ③），
     * **不是**在这里加内缩几何。
     */
    public static Set<Long> internalChunks(Set<Long> chunks) {
        Set<Long> result = new LinkedHashSet<>();
        for (long key : chunks) {
            int chunkX = ChunkPos.getX(key);
            int chunkZ = ChunkPos.getZ(key);
            if (chunks.contains(ChunkPos.asLong(chunkX + 1, chunkZ))
                    && chunks.contains(ChunkPos.asLong(chunkX - 1, chunkZ))
                    && chunks.contains(ChunkPos.asLong(chunkX, chunkZ + 1))
                    && chunks.contains(ChunkPos.asLong(chunkX, chunkZ - 1))) {
                result.add(key);
            }
        }
        return result;
    }

    /** 某维度**保护区的内部区块**（返程目的地的次优先目标 = 安全区之后的兜底）。 */
    public Set<Long> internalClaims(ResourceLocation dimension) {
        return internalChunks(claims(dimension));
    }

    /** 某维度**安全区的内部区块**（返程目的地的优先目标；空集 ⇒ 退化，消费方按"进区即到"处理）。 */
    public Set<Long> internalSafeClaims(ResourceLocation dimension) {
        return internalChunks(safeClaims(dimension));
    }

    // ==================== 方块规则（黑名单；语义不变）====================

    public boolean addBlock(ResourceLocation id) {
        boolean changed = protectedBlocks.add(id);
        if (changed) {
            setDirty();
        }
        return changed;
    }

    public boolean removeBlock(ResourceLocation id) {
        boolean changed = protectedBlocks.remove(id);
        if (changed) {
            setDirty();
        }
        return changed;
    }

    public boolean addTag(ResourceLocation id) {
        boolean changed = protectedTags.add(id);
        if (changed) {
            setDirty();
        }
        return changed;
    }

    public boolean removeTag(ResourceLocation id) {
        boolean changed = protectedTags.remove(id);
        if (changed) {
            setDirty();
        }
        return changed;
    }

    // ==================== 查询（唯一入口）====================

    /** Returns a stable failure reason suffix, or null when the block may be broken. */
    public String protectionReason(ServerLevel level, BlockPos pos) {
        if (claims(level.dimension().location()).contains(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4))) {
            return "protected_area";
        }
        BlockState state = level.getBlockState(pos);
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (blockId != null && protectedBlocks.contains(blockId)) {
            return "protected_block";
        }
        for (ResourceLocation tagId : protectedTags) {
            if (state.is(TagKey.create(Registries.BLOCK, tagId))) {
                return "protected_tag";
            }
        }
        return null;
    }

    /** 两个位置是否都落在**当前维度已认领的区块**里。仅用于保守移动边界，不授权破坏。 */
    public boolean sharesArea(ServerLevel level, BlockPos first, BlockPos second) {
        Set<Long> chunks = claims(level.dimension().location());
        return chunks.contains(ChunkPos.asLong(first.getX() >> 4, first.getZ() >> 4))
                && chunks.contains(ChunkPos.asLong(second.getX() >> 4, second.getZ() >> 4));
    }

    public String summary() {
        StringBuilder text = new StringBuilder("chunks=").append(claimedChunkCount())
                .append(" dims=").append(claimedChunks.size())
                .append(" safe=").append(safeChunkCount())
                .append(" blocks=").append(protectedBlocks.size())
                .append(" tags=").append(protectedTags.size());
        if (migratedLegacyAreas > 0 || droppedLegacyAreas > 0) {
            text.append(" migrated_legacy=").append(migratedLegacyAreas)
                    .append(" dropped_legacy=").append(droppedLegacyAreas);
        }
        if (droppedOrphanSafeClaims > 0) {
            text.append(" safe_orphans=").append(droppedOrphanSafeClaims);
        }
        return text.toString();
    }

    /** 只读快照（夹具/诊断用）：某维度已认领区块的 `chunkX,chunkZ` 列表文本。 */
    public List<String> claimedChunkList(ResourceLocation dimension) {
        List<String> result = new ArrayList<>();
        for (long key : claims(dimension)) {
            result.add(ChunkPos.getX(key) + "," + ChunkPos.getZ(key));
        }
        return result;
    }

    /** 供夹具/命令构造标签规则用：把一个方块标签 id 转成 `TagKey`（含存在性判断交给调用方）。 */
    public static TagKey<Block> blockTag(ResourceLocation id) {
        return TagKey.create(Registries.BLOCK, id);
    }
}
