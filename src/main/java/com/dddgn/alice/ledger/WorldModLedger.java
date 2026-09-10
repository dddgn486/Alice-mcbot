package com.dddgn.alice.ledger;

import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 世界修改账本（J6-a，D-081 §12）：**只记"我方放置"**，用于"建拆同权"的配对与恢复。
 *
 * <p>为什么只记放置：破坏型修改**无法配对恢复**（你没法把隧道还原），其控制手段是
 * "谓词 + 预算 + 归因"（见 D-095）；**只有放置需要、也能够被恢复**。
 * 账本因此小而准。授权归属（谁、为什么）仍由 `action/WriteAudit` 负责，两者用 `scopeId` 关联。
 *
 * <p>**记录点 = 动作层**（`BlockInteraction.placeAt` / `placeBulkEdit`）：只有动作层看得见
 * **每一次**修改——包括内核 `PILLAR` 放的方块（Job 未必看得见）。靠调用方自觉记录必然重演"六份重复"。
 *
 * <p>**作用域（scopeId）**：一次任务 = 一个授权作用域。`BotSession.beginTask` 开启、
 * `clearTask` 关闭（与 `ScopeBuffer.begin/end` 同步）。恢复以作用域为单位（`RESTORE_BY_SCOPE`）：
 * "会话结束时该 scope 在账本中应为空"——这是 J6 的可断言不变量。
 *
 * <p>**建拆同权（D-081）**：{@link Policy#TEMP} 的条目**必须在同一 scope 内被移除**；
 * {@link Policy#KEEP}（道路等永久放置）走独立授权，不受配对约束——两者不可混。
 */
public final class WorldModLedger extends SavedData {

    /** 放置物的生命周期策略。 */
    public enum Policy {
        /** 临时（垫脚柱/台阶/挖矿支撑）：**必须配对拆除**。 */
        TEMP,
        /** 永久（道路/玩家要求的建造）：**不该拆**，走独立授权。 */
        KEEP
    }

    /** 一条账本记录。`previousState` 是"精确恢复原状"的前提（往雪/草里放置时原状态不是空气）。 */
    public record Entry(BlockPos pos, String placed, String previous, String reason, Policy policy,
                        String scopeId, UUID owner, long tick) {

        public String describe() {
            return pos.toShortString() + " " + placed + "←" + previous
                    + " [" + policy + " " + reason + " scope=" + scopeId + "]";
        }
    }

    private static final String DATA_KEY = "alice_world_mod_ledger";

    /** 位置 → 记录（一格只可能有一条：重新放置即覆盖）。 */
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    /** owner → 当前打开的 scopeId（一次任务一个）。 */
    private final Map<UUID, String> openScopes = new HashMap<>();
    private int scopeSeq;

    public static WorldModLedger get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(WorldModLedger::load, WorldModLedger::new, DATA_KEY);
    }

    // ==================== 作用域 ====================

    /** 开启一个授权作用域（任务开始）。返回 scopeId。 */
    public static String openScope(MinecraftServer server, UUID owner, String label) {
        WorldModLedger ledger = get(server);
        String scopeId = (owner == null ? "unknown" : owner.toString().substring(0, 8))
                + "#" + (++ledger.scopeSeq) + ":" + (label == null ? "task" : label);
        if (owner != null) {
            ledger.openScopes.put(owner, scopeId);
        }
        ledger.setDirty();
        return scopeId;
    }

    /** 关闭当前作用域（任务收尾）。返回被关闭的 scopeId（无则 null）。 */
    public static String closeScope(MinecraftServer server, UUID owner) {
        WorldModLedger ledger = get(server);
        String closed = owner == null ? null : ledger.openScopes.remove(owner);
        if (closed != null) {
            ledger.setDirty();
        }
        return closed;
    }

    /** 该 owner 当前的作用域；没有打开的作用域时回退为 `implicit`（不静默丢弃记录）。 */
    private static String scopeOf(WorldModLedger ledger, UUID owner) {
        String open = owner == null ? null : ledger.openScopes.get(owner);
        return open != null ? open : "implicit";
    }

    // ==================== 记录（由动作层调用） ====================

    /**
     * 记录一次**放置**。必须在放置**之前**取到 {@code previousState}（调用方保证）。
     *
     * @param previous 放置前该格的状态（用于精确恢复 + "只拆自己放的"比对）
     */
    public static void recordPlacement(ServerLevel level, UUID owner, WriteGrant grant, BlockPos pos,
                                       BlockState previous, BlockState placed) {
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        WorldModLedger ledger = get(server);
        Policy policy = grant.reason().temporary() ? Policy.TEMP : Policy.KEEP;
        Entry entry = new Entry(pos.immutable(), blockId(placed), blockId(previous),
                grant.reason().name(), policy, scopeOf(ledger, owner), owner, level.getGameTime());
        ledger.entries.put(key(pos), entry);
        ledger.setDirty();
        BotLog.info("[Ledger] place {} by={}", entry.describe(), grant.describe());
    }

    /** 移除一条记录（该放置已被我方配对拆除）。 */
    public static void forget(ServerLevel level, BlockPos pos) {
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        WorldModLedger ledger = get(server);
        if (ledger.entries.remove(key(pos)) != null) {
            ledger.setDirty();
        }
    }

    // ==================== 查询 ====================

    /** 全部未清除的记录（快照）。 */
    public static List<Entry> pending(MinecraftServer server) {
        return new ArrayList<>(get(server).entries.values());
    }

    /** 按位置取条目（恢复前用它核对"这格是不是我放的"）。 */
    public static Entry at(MinecraftServer server, BlockPos pos) {
        return get(server).entries.get(key(pos));
    }

    /**
     * 待恢复的临时放置：`scopeId` 为 null 时取全部。
     *
     * <p>**只取 TEMP**——`KEEP`（道路等永久放置）不该被恢复（D-095/§12.1）。
     */
    public static List<Entry> pendingTemporary(MinecraftServer server, String scopeId) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : get(server).entries.values()) {
            if (entry.policy() != Policy.TEMP) {
                continue;
            }
            if (scopeId == null || entry.scopeId().equals(scopeId)) {
                result.add(entry);
            }
        }
        return result;
    }

    /** 某个作用域下未清除的记录。 */
    public static List<Entry> pendingInScope(MinecraftServer server, String scopeId) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : get(server).entries.values()) {
            if (entry.scopeId().equals(scopeId)) {
                result.add(entry);
            }
        }
        return result;
    }

    /** 仍在"打开"状态的作用域（任务未收尾/未清理的信号）。 */
    public static Map<UUID, String> openScopes(MinecraftServer server) {
        return new HashMap<>(get(server).openScopes);
    }

    public static int size(MinecraftServer server) {
        return get(server).entries.size();
    }

    /** 测试夹具用：清空账本。 */
    public static void clear(MinecraftServer server) {
        WorldModLedger ledger = get(server);
        ledger.entries.clear();
        ledger.openScopes.clear();
        ledger.setDirty();
    }

    private static String key(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String blockId(BlockState state) {
        return String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    // ==================== 持久化 ====================

    private static WorldModLedger load(CompoundTag root) {
        WorldModLedger ledger = new WorldModLedger();
        ledger.scopeSeq = root.getInt("scopeSeq");
        ListTag list = root.getList("entries", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            BlockPos pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
            Policy policy;
            try {
                policy = Policy.valueOf(tag.getString("policy"));
            } catch (IllegalArgumentException e) {
                policy = Policy.TEMP;   // 未知策略按最保守的 TEMP 处理（宁可多拆自家的，也不漏拆）
            }
            UUID owner = null;
            if (tag.hasUUID("owner")) {
                owner = tag.getUUID("owner");
            }
            Entry entry = new Entry(pos, tag.getString("placed"), tag.getString("previous"),
                    tag.getString("reason"), policy, tag.getString("scope"), owner, tag.getLong("tick"));
            ledger.entries.put(key(pos), entry);
        }
        ListTag scopes = root.getList("openScopes", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < scopes.size(); i++) {
            CompoundTag tag = scopes.getCompound(i);
            if (tag.hasUUID("owner")) {
                ledger.openScopes.put(tag.getUUID("owner"), tag.getString("scope"));
            }
        }
        return ledger;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        root.putInt("scopeSeq", scopeSeq);
        ListTag list = new ListTag();
        for (Entry entry : entries.values()) {
            CompoundTag tag = new CompoundTag();
            tag.putInt("x", entry.pos().getX());
            tag.putInt("y", entry.pos().getY());
            tag.putInt("z", entry.pos().getZ());
            tag.putString("placed", entry.placed());
            tag.putString("previous", entry.previous());
            tag.putString("reason", entry.reason());
            tag.putString("policy", entry.policy().name());
            tag.putString("scope", entry.scopeId());
            if (entry.owner() != null) {
                tag.putUUID("owner", entry.owner());
            }
            tag.putLong("tick", entry.tick());
            list.add(tag);
        }
        root.put("entries", list);
        ListTag scopes = new ListTag();
        openScopes.forEach((owner, scope) -> {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("owner", owner);
            tag.putString("scope", scope);
            scopes.add(tag);
        });
        root.put("openScopes", scopes);
        return root;
    }

    /** 供命令/自检使用的只读快照（按 tick 倒序，最近优先）。 */
    public static List<Entry> recent(MinecraftServer server, int limit) {
        List<Entry> all = pending(server);
        all.sort((a, b) -> Long.compare(b.tick(), a.tick()));
        return all.size() <= limit ? all : all.subList(0, limit);
    }

}
