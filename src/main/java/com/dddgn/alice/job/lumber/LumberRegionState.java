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
     * 玩家用 {@code /alice region set} 划区时的**自适应高度上限**默认值（不是"固定高度"）。
     *
     * <p>生效上界永远由巡查按**实测树高**收紧（见 {@link #Region}），这个值只做
     * "别把整片天空算进来"的兜底。放在这里而不是放在夹具的 {@code LumberCourseAnchor}，
     * 是因为它属于**区域语义** —— 玩家接口不该回头依赖测试夹具的常量（2026-09-12 J8 收尾）。
     */
    public static final int DEFAULT_MAX_HEIGHT = 48;

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
        /**
         * ⭐ `D-344` ④：**可配置拾取清单** —— 用户用 `/alice region pickup add` **显式加过**的物品注册名。
         *
         * <p><b>这里只存"显式项"</b>：**生效清单** = 显式项 ∪ `{选定的树苗}`，由
         * {@link LumberRegionState#effectivePickupItems} 派生 ——
         * 于是"默认捡选定的树苗"是**算出来的**，不是写死的（细节⑦「不许硬编码树苗」）；
         * 用户换树苗时，默认项**自动跟着换**，不需要再改一行配置。
         */
        private final Set<String> pickupItems = new LinkedHashSet<>();
        /** **区域目标棵数**（首次巡查时按当时的可作业树数确定；区域"欠树"就是相对它算的）。 */
        private int baselineTrees;
        /**
         * 目标棵数是否**已按本区域推导过**。
         *
         * <p>为什么不能用 `baselineTrees == 0` 当"还没推导"：**空区域推导出来的结果就是 0**，
         * 于是每一轮巡查都会重推一次、重打一行 `区域目标棵数 baseline=0`（2026-09-12 实测：常驻空区域
         * 每 600 tick 刷一行噪声，永久刷下去）。重划区域会让它作废（见 {@link #setRegion}）。
         */
        private boolean baselineDerived;
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

    /**
     * 设定/重划区域。
     *
     * <p>**区域被重新划定（与旧区域不同）⇒ 按旧区域积累的派生记账全部作废**（2026-09-12 J8 收尾）：
     * <ul>
     *   <li>{@code baselineTrees = 0} —— 让下一次巡查按**新区域**现场重推目标棵数。
     *       否则旧区域的 baseline 会污染新区域：典型症状是"在一个空区域上启动，
     *       立刻报 `deficit=5`、每轮都试图补种"（`/alice region set` 划到别处时必然遇到）；</li>
     *   <li>丢掉**水平范围之外**的"我种的苗/待补种位置"—— 区域不变量只对区域内的东西定义，
     *       界外的旧苗不该再算进 `standing`（方块本身不动，只是不再算作我们欠的账）。</li>
     * </ul>
     * 划**同一个**区域则是幂等重入（夹具反复右键、`/alice region start` 重启都一样），
     * 不重置任何记账 —— 否则砍完树后重启一次就会把"欠 5 棵"的目标丢掉。
     */
    public void setRegion(UUID owner, Region region) {
        Entry entry = entry(owner, true);
        Region previous = entry.region;
        entry.region = region;
        if (previous != null && !previous.equals(region)) {
            entry.baselineTrees = 0;
            entry.baselineDerived = false;
            int droppedSaplings = dropOutside(entry.mySaplings, region);
            int droppedReplant = dropOutside(entry.pendingReplant, region);
            com.dddgn.alice.log.BotLog.info("[Job] maintain 区域重划 ⇒ 派生记账重置 "
                            + "（旧 {} → 新 {}）：baseline=0，丢弃界外苗={} 待补种={}",
                    previous.describe(), region.describe(), droppedSaplings, droppedReplant);
        }
        setDirty();
    }

    /** 丢掉落在水平范围外的记录，返回丢弃条数。 */
    private static int dropOutside(Set<BlockPos> positions, Region region) {
        int dropped = 0;
        var it = positions.iterator();
        while (it.hasNext()) {
            if (!region.containsHorizontal(it.next())) {
                it.remove();
                dropped++;
            }
        }
        return dropped;
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

    // ==================== 可配置拾取清单（`D-344` ④ / 细则⑦）====================

    /** 用户**显式**加过的拾取物品（**不含**派生的默认项）。 */
    public List<String> pickupItems(UUID owner) {
        Entry entry = entry(owner, false);
        return entry == null ? List.of() : List.copyOf(entry.pickupItems);
    }

    /**
     * ⭐ **生效清单** = 显式项 ∪ `{选定的树苗}`（`D-344` ④ 裁定：默认值 = 选定树苗，且**不硬编码**）。
     *
     * <p>树苗未选定 ⇒ 只剩显式项（可能为空）；空清单 ⇒ 作业**不进扫描**（不猜用户想捡什么）。
     */
    public List<String> effectivePickupItems(UUID owner) {
        Entry entry = entry(owner, false);
        LinkedHashSet<String> items = new LinkedHashSet<>();
        if (entry != null) {
            items.addAll(entry.pickupItems);
            if (entry.saplingItem != null && !entry.saplingItem.isBlank()) {
                items.add(entry.saplingItem);
            }
        }
        return List.copyOf(items);
    }

    /** 加入清单（`/alice region pickup add`）。返回 `false` = 本来就在清单里（**幂等，不是错误**）。 */
    public boolean addPickupItem(UUID owner, String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return false;
        }
        Entry entry = entry(owner, true);
        boolean added = entry.pickupItems.add(itemId);
        if (added) {
            setDirty();
        }
        return added;
    }

    /** 移出清单。返回 `false` = 本来就不在（幂等）。⚠️ 移不掉**派生**的默认项（它跟着树苗选择走）。 */
    public boolean removePickupItem(UUID owner, String itemId) {
        Entry entry = entry(owner, false);
        if (entry == null) {
            return false;
        }
        boolean removed = entry.pickupItems.remove(itemId);
        if (removed) {
            setDirty();
        }
        return removed;
    }

    /** 该物品是不是**派生出来的默认项**（清单显示用：区分「默认」与「手动加」）。 */
    public boolean pickupItemIsDefault(UUID owner, String itemId) {
        Entry entry = entry(owner, false);
        if (entry == null || itemId == null) {
            return false;
        }
        return itemId.equals(entry.saplingItem) && !entry.pickupItems.contains(itemId);
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

    /** 目标棵数是否已按当前区域推导过（false ⇒ 下一次巡查推一次）。 */
    public boolean baselineDerived(UUID owner) {
        Entry entry = entry(owner, false);
        return entry != null && entry.baselineDerived;
    }

    public void setBaselineDerived(UUID owner, boolean derived) {
        Entry entry = entry(owner, true);
        entry.baselineDerived = derived;
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
            // `D-344` ④：可配置拾取清单（旧存档没有这一项 ⇒ 空集合 = 只有派生的默认项，行为不变）
            ListTag pickup = tag.getList("pickup_items", Tag.TAG_STRING);
            for (int k = 0; k < pickup.size(); k++) {
                entry.pickupItems.add(pickup.getString(k));
            }
            entry.baselineTrees = tag.getInt("baseline");
            // 旧存档没有这个标记：正数目标棵数视为"已推导"（别把历史目标冲掉）；0 则允许推一次
            entry.baselineDerived = tag.contains("baseline_derived")
                    ? tag.getBoolean("baseline_derived") : entry.baselineTrees > 0;
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
            // `D-344` ④：只持久化**显式项**；默认项是派生的（换树苗时自动跟着换，不必落盘）
            ListTag pickup = new ListTag();
            for (String itemId : entry.pickupItems) {
                pickup.add(net.minecraft.nbt.StringTag.valueOf(itemId));
            }
            tag.put("pickup_items", pickup);
            tag.putInt("baseline", entry.baselineTrees);
            tag.putBoolean("baseline_derived", entry.baselineDerived);
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
