package com.dddgn.alice.job.lumber;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * **可持续伐木区状态**（J8 / §13.2）：区域定义 + "我种下的树苗" + 上次巡查 tick + 统计。
 *
 * <p>为什么必须持久化：`MAINTAIN` 型 Job 不追求"跑完即结束"，而是**持续维持区域不变量**
 * （区域内无未砍完的树、无我方残留、欠树则补种）。这要求跨会话记得：
 * <ul>
 *   <li>**区域边界**（玩家定义的立方区域）；</li>
 *   <li>**哪些树苗是我种的** —— 否则无法判断"欠几棵"，也可能把玩家种的当自己的（§13.2）；</li>
 *   <li>**选定的树苗物品**（用户 2026-09-12 裁定：补种**不需要**与原树一一对应，
 *       但要有接口让用户选择用哪种树苗 ⇒ 这里存物品 id；未配置时保持 {@code null}）；</li>
 *   <li>上次巡查 tick 与累计统计（给"健康输出"用）。</li>
 * </ul>
 *
 * <p>与 {@code WorldModLedger} 同族（都是 {@link SavedData}），因为两者记的是同一件事的两面：
 * 账本记"我方改了世界的什么"（脚手架 `TEMP` / 补种 `KEEP`），区域状态记"这片区域该长什么样"。
 */
public final class LumberRegionState extends SavedData {

    private static final String DATA_KEY = "alice_lumber_regions";

    /**
     * **可持续伐木区**：**只划水平范围**（玩家定义 x/z），**垂直自适应**。
     *
     * <p>用户 2026-09-12 裁定：区域由玩家划分，但玩家只圈水平范围；竖直方向不该让玩家操心 ——
     * 这里存一个 {@code baseY}（基准层，取玩家选区较低的那个 Y）与 {@code maxHeight}（自适应**上限**），
     * 实际生效的上界由巡查按**实测树高**收紧（见 {@code RegionLumberJob#effectiveTopY}）：
     * 既不会漏掉刚长高的树，也不会有个"柱子一样"的固定高度把整片天空算进来。
     */
    public record Region(int minX, int minZ, int maxX, int maxZ, int baseY, int maxHeight) {

        public Region {
            minX = Math.min(minX, maxX);
            maxX = Math.max(minX, maxX);
            minZ = Math.min(minZ, maxZ);
            maxZ = Math.max(minZ, maxZ);
            maxHeight = Math.max(1, maxHeight);
        }

        /** 水平（x/z）是否落在区域内。 */
        public boolean containsHorizontal(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }

        /** 是否落在区域盒内（竖直方向用"基准层往下留 2 格（树桩）+ 自适应上限"）。 */
        public boolean contains(BlockPos pos) {
            return containsHorizontal(pos)
                    && pos.getY() >= baseY - 2
                    && pos.getY() <= baseY + maxHeight;
        }

        /** 覆盖该水平范围所需的外接半径（`TreeScanner` 只吃"中心 + 半径"）。 */
        public int coverRadius() {
            int dx = maxX - minX;
            int dz = maxZ - minZ;
            return (int) Math.ceil(Math.sqrt((double) dx * dx + (double) dz * dz) / 2.0D) + 2;
        }

        public BlockPos center() {
            return new BlockPos((minX + maxX) / 2, baseY, (minZ + maxZ) / 2);
        }

        public int areaXZ() {
            return (maxX - minX + 1) * (maxZ - minZ + 1);
        }

        public String describe() {
            return "x" + minX + ".." + maxX + " z" + minZ + ".." + maxZ
                    + " baseY=" + baseY + " maxH=" + maxHeight + "（垂直自适应）";
        }
    }

    /** 单个 owner 的区域记录。 */
    public static final class Entry {
        private Region region;
        /** **我种下的**树苗位置（只记我种的，§13.2）。 */
        private final Set<BlockPos> mySaplings = new LinkedHashSet<>();
        /** 用户选定的树苗物品 id（`alice:item/...` 形态的物品注册名）；null = 未配置。 */
        private String saplingItem;
        /** 砍完树留下的**待补种位置**（通常是树桩格）；补种成功后从这里销账。 */
        private final Set<BlockPos> pendingReplant = new LinkedHashSet<>();
        /** **区域目标棵数**（首次巡查时按当时的可作业树数确定；区域"欠树"就是相对它算的）。 */
        private int baselineTrees;
        /**
         * 是否"连续无活就自动收工"（旧设计的默认行为，`IDLE_NO_WORK`）。
         *
         * <p>用户 2026-09-12 裁定：**常驻任务本就该只由玩家/决策层显式打断**，所以默认 {@code false}
         * （= 一直巡查等生长）；想要旧行为可用 {@code /alice region idle-stop on} 打开。
         */
        private boolean autoIdleStop;
        private long lastPatrolTick;
        private int treesChopped;
        private int saplingsPlanted;
        private int patrols;
    }

    private final java.util.Map<UUID, Entry> entries = new java.util.HashMap<>();

    public static LumberRegionState get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(LumberRegionState::load, LumberRegionState::new, DATA_KEY);
    }

    private Entry entry(UUID owner, boolean create) {
        if (owner == null) {
            return null;
        }
        Entry entry = entries.get(owner);
        if (entry == null && create) {
            entry = new Entry();
            entries.put(owner, entry);
        }
        return entry;
    }

    // ==================== 区域定义 ====================

    public Region region(UUID owner) {
        Entry entry = entry(owner, false);
        return entry == null ? null : entry.region;
    }

    public void setRegion(UUID owner, Region region) {
        Entry entry = entry(owner, true);
        entry.region = region;
        setDirty();
    }

    public void clearRegion(UUID owner) {
        entries.remove(owner);
        setDirty();
    }

    // ==================== 树苗选择（用户接口，J8 Slice B 用）====================

    /** 用户选定的树苗物品 id（null = 未配置）。 */
    public String saplingItem(UUID owner) {
        Entry entry = entry(owner, false);
        return entry == null ? null : entry.saplingItem;
    }

    /**
     * 选择补种用的树苗物品（用户 2026-09-12 裁定：**不必与被砍的树一一对应**）。
     *
     * @param itemId 物品注册名（如 {@code minecraft:oak_sapling}）；null = 清空选择
     */
    public void setSaplingItem(UUID owner, String itemId) {
        Entry entry = entry(owner, true);
        entry.saplingItem = itemId;
        setDirty();
    }

    // ==================== 我种的苗 ====================

    public void addMySapling(UUID owner, BlockPos pos) {
        Entry entry = entry(owner, true);
        if (entry.mySaplings.add(pos.immutable())) {
            entry.saplingsPlanted++;
            setDirty();
        }
    }

    public boolean isMySapling(UUID owner, BlockPos pos) {
        Entry entry = entry(owner, false);
        return entry != null && entry.mySaplings.contains(pos);
    }

    public int mySaplingCount(UUID owner) {
        Entry entry = entry(owner, false);
        return entry == null ? 0 : entry.mySaplings.size();
    }

    public List<BlockPos> mySaplings(UUID owner) {
        Entry entry = entry(owner, false);
        return entry == null ? List.of() : List.copyOf(entry.mySaplings);
    }

    /** 苗长成树（或苗没了）时销账，避免"欠树"判断被幽灵条目污染。 */
    public void forgetSapling(UUID owner, BlockPos pos) {
        Entry entry = entry(owner, false);
        if (entry != null && entry.mySaplings.remove(pos)) {
            setDirty();
        }
    }

    // ==================== 待补种位置（§13.2 的区域不变量）====================

    public void addPendingReplant(UUID owner, BlockPos pos) {
        Entry entry = entry(owner, true);
        if (entry.pendingReplant.add(pos.immutable())) {
            setDirty();
        }
    }

    public void removePendingReplant(UUID owner, BlockPos pos) {
        Entry entry = entry(owner, false);
        if (entry != null && entry.pendingReplant.remove(pos)) {
            setDirty();
        }
    }

    public List<BlockPos> pendingReplant(UUID owner) {
        Entry entry = entry(owner, false);
        return entry == null ? List.of() : List.copyOf(entry.pendingReplant);
    }

    public int pendingReplantCount(UUID owner) {
        Entry entry = entry(owner, false);
        return entry == null ? 0 : entry.pendingReplant.size();
    }

    /** 是否"无活即自动收工"（默认 false = 常驻，只由显式打断结束）。 */
    public boolean autoIdleStop(UUID owner) {
        Entry entry = entry(owner, false);
        return entry != null && entry.autoIdleStop;
    }

    public void setAutoIdleStop(UUID owner, boolean value) {
        Entry entry = entry(owner, true);
        entry.autoIdleStop = value;
        setDirty();
    }

    /** 区域目标棵数（0 = 还没定，首次巡查时确定）。 */
    public int baselineTrees(UUID owner) {
        Entry entry = entry(owner, false);
        return entry == null ? 0 : entry.baselineTrees;
    }

    public void setBaselineTrees(UUID owner, int baseline) {
        Entry entry = entry(owner, true);
        entry.baselineTrees = baseline;
        setDirty();
    }

    // ==================== 巡查与统计 ====================

    public void markPatrol(UUID owner, long tick) {
        Entry entry = entry(owner, true);
        entry.lastPatrolTick = tick;
        entry.patrols++;
        setDirty();
    }

    public void addChopped(UUID owner) {
        Entry entry = entry(owner, true);
        entry.treesChopped++;
        setDirty();
    }

    public long lastPatrolTick(UUID owner) {
        Entry entry = entry(owner, false);
        return entry == null ? -1L : entry.lastPatrolTick;
    }

    public int treesChopped(UUID owner) {
        Entry entry = entry(owner, false);
        return entry == null ? 0 : entry.treesChopped;
    }

    public int saplingsPlanted(UUID owner) {
        Entry entry = entry(owner, false);
        return entry == null ? 0 : entry.saplingsPlanted;
    }

    public int patrols(UUID owner) {
        Entry entry = entry(owner, false);
        return entry == null ? 0 : entry.patrols;
    }

    // ==================== 持久化 ====================

    private static LumberRegionState load(CompoundTag root) {
        LumberRegionState state = new LumberRegionState();
        ListTag list = root.getList("regions", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            UUID owner;
            try {
                owner = UUID.fromString(tag.getString("owner"));
            } catch (IllegalArgumentException ex) {
                continue;
            }
            Entry entry = new Entry();
            if (tag.contains("region")) {
                CompoundTag r = tag.getCompound("region");
                entry.region = new Region(r.getInt("min_x"), r.getInt("min_z"), r.getInt("max_x"),
                        r.getInt("max_z"), r.getInt("base_y"), r.getInt("max_h"));
            }
            entry.saplingItem = tag.contains("sapling_item") ? tag.getString("sapling_item") : null;
            entry.baselineTrees = tag.getInt("baseline");
            entry.autoIdleStop = tag.getBoolean("auto_idle_stop");
            entry.lastPatrolTick = tag.getLong("last_patrol");
            entry.treesChopped = tag.getInt("chopped");
            entry.saplingsPlanted = tag.getInt("planted");
            entry.patrols = tag.getInt("patrols");
            ListTag saplings = tag.getList("saplings", Tag.TAG_COMPOUND);
            for (int k = 0; k < saplings.size(); k++) {
                entry.mySaplings.add(readPos(saplings.getCompound(k)));
            }
            ListTag replant = tag.getList("replant", Tag.TAG_COMPOUND);
            for (int k = 0; k < replant.size(); k++) {
                entry.pendingReplant.add(readPos(replant.getCompound(k)));
            }
            state.entries.put(owner, entry);
        }
        return state;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        ListTag list = new ListTag();
        for (var e : entries.entrySet()) {
            Entry entry = e.getValue();
            CompoundTag tag = new CompoundTag();
            tag.putString("owner", e.getKey().toString());
            if (entry.region != null) {
                CompoundTag r = new CompoundTag();
                r.putInt("min_x", entry.region.minX());
                r.putInt("min_z", entry.region.minZ());
                r.putInt("max_x", entry.region.maxX());
                r.putInt("max_z", entry.region.maxZ());
                r.putInt("base_y", entry.region.baseY());
                r.putInt("max_h", entry.region.maxHeight());
                tag.put("region", r);
            }
            if (entry.saplingItem != null) {
                tag.putString("sapling_item", entry.saplingItem);
            }
            tag.putInt("baseline", entry.baselineTrees);
            tag.putBoolean("auto_idle_stop", entry.autoIdleStop);
            tag.putLong("last_patrol", entry.lastPatrolTick);
            tag.putInt("chopped", entry.treesChopped);
            tag.putInt("planted", entry.saplingsPlanted);
            tag.putInt("patrols", entry.patrols);
            ListTag saplings = new ListTag();
            for (BlockPos pos : entry.mySaplings) {
                saplings.add(writePos(pos));
            }
            tag.put("saplings", saplings);
            ListTag replant = new ListTag();
            for (BlockPos pos : entry.pendingReplant) {
                replant.add(writePos(pos));
            }
            tag.put("replant", replant);
            list.add(tag);
        }
        root.put("regions", list);
        return root;
    }

    private static CompoundTag writePos(BlockPos pos) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        return tag;
    }

    private static BlockPos readPos(CompoundTag tag) {
        return new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
    }

    /** 便于日志/命令输出的一行摘要。 */
    public List<String> describeAll() {
        List<String> lines = new ArrayList<>();
        for (var e : entries.entrySet()) {
            Entry entry = e.getValue();
            lines.add(e.getKey().toString().substring(0, 8)
                    + " region=" + (entry.region == null ? "-" : entry.region.describe())
                    + " mySaplings=" + entry.mySaplings.size()
                    + " saplingItem=" + (entry.saplingItem == null ? "-" : entry.saplingItem)
                    + " baseline=" + entry.baselineTrees
                    + " autoIdleStop=" + entry.autoIdleStop + " pendingReplant=" + entry.pendingReplant.size()
                    + " chopped=" + entry.treesChopped + " planted=" + entry.saplingsPlanted
                    + " patrols=" + entry.patrols + " lastPatrol=" + entry.lastPatrolTick);
        }
        return lines;
    }
}
