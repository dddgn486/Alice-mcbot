package com.dddgn.alice.protection;

import com.dddgn.alice.action.WritePolicyMatrix;
import com.dddgn.alice.action.WritePolicyMatrix.Level;
import com.dddgn.alice.ledger.WorldModLedger;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * **任务区**（`D-338` ⑦ + 附注二 + 附注四）—— 保护区**父类**之外的另一种区块级授权封套。
 *
 * <p><b>两层结构（用户 2026-09-19 定案，`D-338` 附注四②）</b>：
 * <ul>
 *   <li><b>实际工作区域 work area</b> = **方块级**（任务语义：林场范围 / 蓝图 footprint / 目标簇），
 *       由**任务**给出（玩家给意图，任务算范围）；</li>
 *   <li><b>任务区 task zone</b> = 工作区域**所占区块的最小覆盖**（hull，**不扩边**），
 *       由工作区域**单向派生**（反向不存在：任务区**绝不**裁剪工作区域）。</li>
 * </ul>
 * 为什么分两层：保护闸门与候选扫描是**逐方块热路径**，而区块集合是 **O(1) 查表**；
 * 而任务真正需要精度的东西（目标集、记账、恢复）本来就在任务手里
 * ⇒ 授权层留在区块级，精度留在任务里。
 *
 * <p><b>⭐"目标内 / 目标外"的判据（`D-338` ① 的落地口径）</b>：方块 ∈ **目标集** ⇒ 目标内
 * （账本 `KEEP`、免提权、无恢复义务）；方块 ∈ **任务区** 但 ∉ 目标集 ⇒ 目标外
 * （显式提权 + 消耗预算 + `TEMP` 待恢复）。**任务区只回答"这里的写入要不要提权"，目标集回答
 * "这一格算不算成果"** —— 所以本类**不**替任务判"目标内/目标外"（那需要目标集，只有任务有）。
 *
 * <p><b>覆盖规则（`D-338` 附注二第 1/4 条，用户原话要点）</b>：任务区**可以覆盖保护区父类**
 * （也可以在没有保护区的野外独立划分）；⛔ **不得覆盖任何子类声明**（今天 = 安全区）。
 * 有冲突 ⇒ **如实报错、拒绝声明**（不静默裁剪、不静默降级、不替玩家把安全区变成普通保护区）；
 * 要走这条路必须**显式退化**：先取消那些区块的安全区声明，再重来。
 *
 * <p><b>锁定与生命周期（附注二第 2 条 + 主条 ⑦）</b>：任务区由**任务**声明、**随 `scopeId` 生灭**，
 * 任务存续期间**玩家不能手改** —— 这不是靠约定，而是结构性的：① 唯一的写入者是
 * {@link #declare}（只有任务调得到），命令层没有写入口；② 键就是当前作用域，
 * {@link #zoneOf} 每次都拿 {@link WorldModLedger#currentScope} 复核
 * ⇒ 作用域一收尾（正常终态、被替换、`/alice region stop` 显式打断…）**权威自动消失**，
 * 不需要任何调用方记得来关。
 *
 * <p>⚠️ <b>故意不持久化</b>（与 `SafeZoneData` / `ReturnPointData` 的 `SavedData` 不同）：
 * 任务树本身不跨重启存活，任务区却活下来的话，留下的就是一个**没有任务对应的授权封套**
 * —— 那是"静默留权"，与 `D-338` 附注二第 4 条同一纪律。所以本类只用**进程内状态**，
 * 重启后世界回到"没有任务区"（= 保护区默认无写入权限，保守方向）。
 *
 * <p>⚠️ <b>本片（`§5.12` 第 4 件的"几何 + 锁定"层）不改任何既有权限行为</b>：
 * 破坏/放置闸门一个字没动（`BlockBreakSafety` / `WritePolicyMatrix` 仍按原样拒绝保护区块内的写入）。
 * 任务区接进闸门当"授权封套"（目标内 `KEEP` / 目标外 `TEMP` + 预算）属**第 4 件的下一片**，
 * 且要等**第 5 件权限等级阶梯**（台账 §5.12 第 5 行）拍板。
 */
public final class TaskZoneRegistry {

    /**
     * **实际工作区域**（方块级）：一个水平矩形 + 它所在的维度。
     *
     * <p>为什么是矩形：今天唯一的真实工作区域是**玩家划的林场**（`LumberRegionState.Region` 就是
     * `minX/minZ/maxX/maxZ`，只划水平范围、垂直自适应）。蓝图 footprint / 目标簇将来也用
     * {@link #chunkCoverOf(Collection)} 那条**方块集合**口径，两条派生路径都在本类里，
     * 且对矩形**结果必须相同**（夹具对这一点有判据）。
     *
     * <p>⚠️ **Y 不参与派生**：与保护区的口径一致（忽略 Y、覆盖全高度）—— 否则"同一条隧道里
     * 上下两格拿到不同授权"这种怪事就会出现。
     */
    public record WorkArea(ResourceLocation dimension, int minX, int minZ, int maxX, int maxZ) {

        /** 规范化：两个角反过来写也得到同一个区域（`equals` 因此对"重划同一个区"稳定 ⇒ 幂等可判）。 */
        public WorkArea {
            Objects.requireNonNull(dimension, "work area dimension");
            if (minX > maxX) {
                int swap = minX;
                minX = maxX;
                maxX = swap;
            }
            if (minZ > maxZ) {
                int swap = minZ;
                minZ = maxZ;
                maxZ = swap;
            }
        }

        /** 水平格数（**方块级**的规模；与区块数不是一回事，日志里两个都打）。 */
        public long areaXZ() {
            return (long) (maxX - minX + 1) * (long) (maxZ - minZ + 1);
        }

        /** 派生：本工作区域**所占区块的最小覆盖**（单向：工作区域 ⇒ 任务区）。 */
        public Set<Long> chunkCover() {
            return chunkCoverOf(this);
        }

        public String describe() {
            return "[" + minX + ".." + maxX + "," + minZ + ".." + maxZ + "]";
        }
    }

    /**
     * 一条**已声明的任务区**：派生的区块集合 + 归属元数据（谁的任务、哪种任务、多大面积、什么时候声明的）
     * + ⭐**区域级权限等级**（`D-338` 附注七②，用户 2026-09-19 拍板；来源 = `WritePolicyMatrix` 单一出处）。
     *
     * <p>`kind` 用任务的稳定名（如 `region_lumber`，`Job#taskName` 的口径）而不是实现类名。
     */
    public record Zone(String scopeId, UUID owner, String kind, Level level, WorkArea area,
                       Set<Long> chunks, long declaredTick, boolean playerDriven) {

        public Zone {
            chunks = Set.copyOf(chunks);
        }

        /** 该方块是否落在本任务区里（**区块级 O(1)**：逐方块热路径上问得起）。 */
        public boolean covers(BlockPos pos) {
            return covers(pos.getX(), pos.getZ());
        }

        /** 同上，直接给坐标（夹具/命令用）。 */
        public boolean covers(int blockX, int blockZ) {
            return chunks.contains(ChunkPos.asLong(blockX >> 4, blockZ >> 4));
        }

        /** ⭐ `D-338` 附注十四：**保护区内**该任务区的**生效等级**（非玩家发起 ⇒ 封顶 `L1`）。 */
        public Level effectiveLevel() {
            return playerDriven ? level : level.cappedForUnattended();
        }

        public String describe() {
            return "kind=" + kind + " level=" + level.label() + (playerDriven ? "" : "(非玩家发起)")
                    + " chunks=" + chunks.size()
                    + " area(block)=" + area.areaXZ() + " " + area.describe();
        }
    }

    /** 声明结果（**命令/任务/夹具共用同一套判据** ⇒ 只有一处口径）。 */
    public enum Declare {
        /** 新声明成功。 */
        DECLARED,
        /** 同一作用域、同一工作区域（幂等）。 */
        ALREADY,
        /** 同一作用域换了工作区域 ⇒ 覆盖旧的（任务可以改主意；旧区块不再被覆盖）。 */
        REPLACED,
        /** ⛔ 该 owner 没有打开的任务作用域 ⇒ 拒绝（**不许在任务之外造授权封套**）。 */
        NO_SCOPE,
        /** ⛔ 工作区域是空集（理论不可达，但拒绝比静默声明一个空区好）。 */
        EMPTY_AREA,
        /** ⛔ 与**子类声明**（安全区）有交集 ⇒ 拒绝，且**不裁剪**（附注二第 4 条）。 */
        CONFLICT_SUBZONE
    }

    /** 声明结果 + 生效的任务区 + 冲突区块（`CONFLICT_SUBZONE` 时非空）。 */
    public record Result(Declare status, Zone zone, List<Long> conflicts) {

        public Result {
            conflicts = List.copyOf(conflicts);
        }

        /** 是否真的有一条生效的任务区在起作用。 */
        public boolean active() {
            return status == Declare.DECLARED || status == Declare.ALREADY || status == Declare.REPLACED;
        }

        public String describe() {
            if (status == Declare.CONFLICT_SUBZONE) {
                return status + " safe_zone_chunks=" + conflicts.size() + " " + describeChunks(conflicts);
            }
            return zone == null ? status.name() : status + " " + zone.describe();
        }
    }

    /** 进程内状态（**故意不持久化**，理由见类注释）：scopeId → 任务区。 */
    private static final Map<String, Zone> ZONES = new HashMap<>();

    /**
     * ⭐ **区内放置计数**（`D-338` 附注七②：`L1` 的"≤8 次"）：scopeId → 已在**任务区内**落地的放置次数。
     *
     * <p>为什么记在这里而不是做成 `WriteBudget` 的 caps：预算 caps 是**作用域级**的，
     * 而"≤8"是**区内**配额 —— 用 caps 实现会把任务在**野外**的放置也一起清零
     * （一个 `L0` 的区会让整条任务失去野外写入权，那是错的）。所以按位置计数、只记区内的。
     */
    private static final Map<String, Integer> ZONE_PLACES = new HashMap<>();

    private TaskZoneRegistry() {
    }

    // ==================== 派生（纯函数）====================

    /**
     * 矩形工作区域的**最小覆盖**：`[minX>>4 .. maxX>>4] × [minZ>>4 .. maxZ>>4]`。
     *
     * <p>为什么这就是最小覆盖：矩形是**连续**的 ⇒ 这个区块矩形里每一个区块都至少含矩形的一格
     * （不会出现"扫进来却空着"的区块）⇒ 既覆盖全部方块，又没有一个多余区块。夹具用
     * **逐方块枚举**那条独立路径对这一点做等式断言（不是同一公式抄两遍）。
     */
    public static Set<Long> chunkCoverOf(WorkArea area) {
        Set<Long> result = new LinkedHashSet<>();
        for (int chunkX = area.minX() >> 4; chunkX <= area.maxX() >> 4; chunkX++) {
            for (int chunkZ = area.minZ() >> 4; chunkZ <= area.maxZ() >> 4; chunkZ++) {
                result.add(ChunkPos.asLong(chunkX, chunkZ));
            }
        }
        return result;
    }

    /**
     * **任意方块集合**的最小覆盖（蓝图 footprint / 目标簇用）：不同方块落在同一区块 ⇒ 只算一个。
     * 与 {@link #chunkCoverOf(WorkArea)} 在同一批方块上**必须给出同一集合**。
     */
    public static Set<Long> chunkCoverOf(Collection<BlockPos> blocks) {
        Set<Long> result = new LinkedHashSet<>();
        for (BlockPos pos : blocks) {
            result.add(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        }
        return result;
    }

    /** 某方块所在区块的键（与 `SafeZoneData` 同一口径：忽略 Y）。 */
    public static long chunkKey(BlockPos pos) {
        return ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /**
     * ⭐ **覆盖冲突**：任务区与**子类声明**（今天只有安全区）有交集的区块（**纯查询**）。
     *
     * <p>为什么只查子类声明：任务区**允许**覆盖保护区父类（附注二第 1 条）—— 那是"任务在玩家的
     * 基地里干活"的正常情形；子类声明（安全区）是玩家对**更大范围的资产**做的更强承诺，
     * 系统不许替他把承诺降级（"静默剥离子类"与"静默提权"同罪）。
     *
     * @return 冲突区块键（**稳定排序** ⇒ 报错/日志可复现）；无冲突 ⇒ 空表
     */
    public static List<Long> safeZoneConflicts(SafeZoneData zones, ResourceLocation dimension, Set<Long> chunks) {
        Set<Long> safe = zones.safeClaims(dimension);
        if (safe.isEmpty()) {
            return List.of();
        }
        List<Long> hits = new ArrayList<>();
        for (long key : chunks) {
            if (safe.contains(key)) {
                hits.add(key);
            }
        }
        hits.sort(Long::compare);
        return hits;
    }

    // ==================== 声明 / 锁定 / 生命周期 ====================

    /**
     * 声明（或重声明）**当前任务**的任务区。作用域由 {@link WorldModLedger#currentScope} 解析
     * —— **不接受调用方传 scopeId**（否则"任务区随 scope 生灭"就有旁路了）。
     *
     * <p>⭐ **等级由 `WritePolicyMatrix` 单一出处解析**（`D-338` ⑦ / 附注七③）：调用方只给
     * {@code kind}（= 任务的稳定名 ⇒ 任务类别）+ {@code playerDriven}（`L3` 只能由玩家显式取得），
     * **不许自报等级**。等级一旦声明就**锁定**在元数据里（任务存续期内玩家改不了、任务自己也不改）。
     *
     * <p>失败路径一律**如实返回**：没有作用域 ⇒ {@code NO_SCOPE}；与安全区冲突 ⇒
     * {@code CONFLICT_SUBZONE}（**不裁剪、不降级**，冲突区块原样报出去）。
     *
     * @param playerDriven 该任务是**玩家显式**发起的吗（任务层用 {@code Driver.of(bot)} 判定后传进来；
     *                     `false` 时 `L3` 降级为 `L2`）
     */
    public static Result declare(MinecraftServer server, UUID owner, String kind, WorkArea area,
                                 boolean playerDriven) {
        if (server == null || owner == null || area == null) {
            return new Result(Declare.NO_SCOPE, null, List.of());
        }
        String scopeId = WorldModLedger.currentScope(server, owner);
        if (scopeId == null) {
            BotLog.warn("[TaskZone] 没有打开的**任务作用域** ⇒ 拒绝声明任务区（owner={} kind={} area={}）"
                            + " —— 任务区是「一次任务一个作用域」的授权封套，不许在任务之外存在",
                    owner, kind, area.describe());
            return new Result(Declare.NO_SCOPE, null, List.of());
        }
        Set<Long> chunks = area.chunkCover();
        if (chunks.isEmpty()) {
            BotLog.warn("[TaskZone] 工作区域为空 ⇒ 拒绝声明（scope={} kind={} area={}）",
                    scopeId, kind, area.describe());
            return new Result(Declare.EMPTY_AREA, null, List.of());
        }
        List<Long> conflicts = safeZoneConflicts(SafeZoneData.get(server), area.dimension(), chunks);
        if (!conflicts.isEmpty()) {
            BotLog.warn("[TaskZone] ⛔ 任务区与**子类声明（安全区）**冲突 ⇒ 拒绝声明（**不裁剪、不降级**）："
                            + "scope={} kind={} area={} 冲突区块={} —— 要走这条路必须先**显式退化**"
                            + "（取消那些区块的安全区声明），系统不会替玩家把安全区变成普通保护区",
                    scopeId, kind, area.describe(), describeChunks(conflicts));
            return new Result(Declare.CONFLICT_SUBZONE, null, conflicts);
        }
        Level level = WritePolicyMatrix.zoneLevel(kind, playerDriven);
        Zone existing = ZONES.get(scopeId);
        // ⭐ `D-338` 附注十四：**驱动身份也是声明的一部分** —— 同一任务区"换个身份再声明"
        // （`playerDriven` 变）必须**真的重建**（`REPLACED`），否则会留下**过时的封顶标记**
        // （门禁实测抓到：同 kind/同区域的再声明被判 `ALREADY` ⇒ 新的身份被忽略）。
        if (existing != null && existing.owner().equals(owner) && existing.kind().equals(kind)
                && existing.level() == level && existing.area().equals(area)
                && existing.playerDriven() == playerDriven) {
            return new Result(Declare.ALREADY, existing, List.of());
        }
        Zone zone = new Zone(scopeId, owner, kind, level, area, chunks,
                server.overworld() == null ? 0L : server.overworld().getGameTime(), playerDriven);
        ZONES.put(scopeId, zone);
        // 换区/换等级 ⇒ 区内放置配额重新开始（配额是**区内**记账，跟着这条任务区走）
        ZONE_PLACES.remove(scopeId);
        Level ceiling = WritePolicyMatrix.zoneLevel(WritePolicyMatrix.taskOf(kind));
        BotLog.info("[TaskZone] {} scope={} owner={} {} blocks={} conflicts=0（派生工作区域 ⇒ 区块最小覆盖；"
                        + "单向派生，不许反向裁剪工作区域）{}",
                existing == null ? "declared（声明任务区）" : "replaced（换工作区域/等级 ⇒ 旧区块不再覆盖）",
                scopeId, owner.toString().substring(0, 8), zone.describe(), area.areaXZ(),
                (ceiling == level ? "" : " ⚠ 等级已降级：请求 " + ceiling.label()
                        + " ⇒ 授予 " + level.label() + "（L3 全权只能由玩家显式取得）")
                        + (playerDriven ? ""
                        : " ⚠ 非玩家发起（LLM/未归因）⇒ **保护区内按 "
                        + level.cappedForUnattended().label() + " 执行**（拆不了玩家的方块；野外不受影响）"));
        return new Result(existing == null ? Declare.DECLARED : Declare.REPLACED, zone, List.of());
    }

    /** 解除某作用域的任务区。返回是否真的移除了一条（**取消任务 ⇒ 自动解除**由作用域收尾调用）。 */
    public static boolean release(String scopeId) {
        if (scopeId == null) {
            return false;
        }
        ZONE_PLACES.remove(scopeId);
        boolean removed = ZONES.remove(scopeId) != null;
        if (removed) {
            BotLog.info("[TaskZone] released scope={}（任务结束/被取消 ⇒ 任务区随之解除；玩家无需再点一次）", scopeId);
        }
        return removed;
    }

    /**
     * ⭐ **区内放置够不够配额**（`L1` 的"≤8 次"）：该作用域**已经**在区内放了几个。
     * 只统计"落点在**自己**任务区覆盖范围内"的放置（区外的放置不进这个计数）。
     */
    public static int zonePlaceCount(String scopeId) {
        return scopeId == null ? 0 : ZONE_PLACES.getOrDefault(scopeId, 0);
    }

    /**
     * 记录一次**已落地**的放置：落点在**自己**任务区覆盖范围内才计数（其它一律 no-op）。
     *
     * <p>由动作层在**世界真的变了之后**调用（`BlockInteraction` 的两条放置路径）——
     * 计数放在"落地之后"与 `WriteBudget.consumePlace` 同一纪律：失败的尝试不占额度。
     *
     * @return 该作用域累计的区内放置次数（不在区内 ⇒ 返回当前值、不计数）
     */
    public static int recordZonePlacement(ServerLevel level, UUID owner, BlockPos pos) {
        MinecraftServer server = level.getServer();
        if (server == null || owner == null || pos == null) {
            return 0;
        }
        Zone zone = zoneOf(server, owner);
        if (zone == null || !zone.covers(pos)) {
            return zone == null ? 0 : zonePlaceCount(zone.scopeId());
        }
        return ZONE_PLACES.merge(zone.scopeId(), 1, Integer::sum);
    }

    /**
     * ⭐ **该 bot 当前生效的任务区**（权威入口）：键 = 它**当前**的作用域。
     *
     * <p>作用域一收尾（终态 / 被替换 / 显式打断）⇒ `currentScope` 变了或为 null ⇒ 这里**立刻**返回
     * null —— 这就是"随 `scopeId` 生灭"的实现方式：**不靠谁记得来关**。
     */
    public static Zone zoneOf(MinecraftServer server, UUID owner) {
        if (server == null || owner == null) {
            return null;
        }
        String scopeId = WorldModLedger.currentScope(server, owner);
        if (scopeId == null) {
            return null;
        }
        Zone zone = ZONES.get(scopeId);
        return zone != null && owner.equals(zone.owner()) ? zone : null;
    }

    /**
     * **该位置被哪个任务区覆盖**（任意 bot；只读查询面用 —— `/alice protect list`）。
     * 只返回**作用域仍有效**的任务区（过期条目不会被当成权威）。
     */
    public static Zone zoneAt(ServerLevel level, BlockPos pos) {
        MinecraftServer server = level.getServer();
        if (server == null) {
            return null;
        }
        ResourceLocation dimension = level.dimension().location();
        long key = chunkKey(pos);
        for (Zone zone : ZONES.values()) {
            if (zone.area().dimension().equals(dimension) && zone.chunks().contains(key)
                    && isCurrent(server, zone)) {
                return zone;
            }
        }
        return null;
    }

    /** 当前生效的任务区条数（顺带清掉过期条目）。 */
    public static int activeCount(MinecraftServer server) {
        return activeZones(server).size();
    }

    /** 当前生效的任务区（快照；**顺带 prune**）。 */
    public static List<Zone> activeZones(MinecraftServer server) {
        prune(server);
        return List.copyOf(ZONES.values());
    }

    /**
     * 清掉**已过期**的任务区（作用域已收尾但条目还在 —— 只有"没走收尾钩子"的路径会留下）。
     *
     * <p>过期条目**本来就不构成权威**（{@link #zoneOf} / {@link #zoneAt} 都复核作用域），
     * prune 只是不让进程内表无限长。返回清掉的条数。
     */
    public static int prune(MinecraftServer server) {
        int dropped = 0;
        var iterator = ZONES.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Zone> entry = iterator.next();
            if (!isCurrent(server, entry.getValue())) {
                iterator.remove();
                dropped++;
            }
        }
        if (dropped > 0) {
            BotLog.info("[TaskZone] 清掉 {} 条过期任务区（作用域已收尾 ⇒ 授权封套随 scopeId 生灭）", dropped);
        }
        return dropped;
    }

    /** **夹具/收尾专用**：清空全部任务区（生产路径不许调用 —— 那是"静默留权"的反面：静默撤权）。 */
    public static void clearAll() {
        ZONES.clear();
        ZONE_PLACES.clear();
    }

    /** 一行可读摘要（诊断/日志用）。 */
    public static String summary(MinecraftServer server) {
        List<Zone> active = activeZones(server);
        if (active.isEmpty()) {
            return "task_zones=0";
        }
        List<String> parts = new ArrayList<>();
        for (Zone zone : active) {
            parts.add(zone.kind() + ":" + zone.chunks().size() + "chunks:scope=" + zone.scopeId());
        }
        return "task_zones=" + active.size() + "[" + String.join(", ", parts) + "]";
    }

    private static boolean isCurrent(MinecraftServer server, Zone zone) {
        return zone.scopeId().equals(WorldModLedger.currentScope(server, zone.owner()));
    }

    /** 区块键列表 → `chunkX,chunkZ | …`（日志与报错里给人看的形式）。 */
    public static String describeChunks(Collection<Long> chunks) {
        List<String> parts = new ArrayList<>();
        for (long key : chunks) {
            parts.add(ChunkPos.getX(key) + "," + ChunkPos.getZ(key));
        }
        return String.join(" | ", parts);
    }
}
