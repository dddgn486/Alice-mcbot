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
 */
public final class SafeZoneData extends SavedData {

    public static final String DATA_KEY = "alice_safe_zones";

    /** 持久化格式版本：1 = 旧圆形半径（只读兼容）；2 = 区块认领（当前写入）。 */
    private static final int FORMAT_VERSION = 2;

    /** 维度 → 认领的区块键集合（键 = {@link ChunkPos#asLong(int, int)}）。 */
    private final Map<ResourceLocation, Set<Long>> claimedChunks = new LinkedHashMap<>();
    private final Set<ResourceLocation> protectedBlocks = new LinkedHashSet<>();
    private final Set<ResourceLocation> protectedTags = new LinkedHashSet<>();
    /** 本次加载从旧格式转换过来的区域条数（只读诊断用；>0 时加载期会告警）。 */
    private int migratedLegacyAreas;
    /** 转换不了的旧条目条数（维度解析失败等）—— 同样要"响亮"，不能静默丢。 */
    private int droppedLegacyAreas;

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
                .append(" blocks=").append(protectedBlocks.size())
                .append(" tags=").append(protectedTags.size());
        if (migratedLegacyAreas > 0 || droppedLegacyAreas > 0) {
            text.append(" migrated_legacy=").append(migratedLegacyAreas)
                    .append(" dropped_legacy=").append(droppedLegacyAreas);
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
