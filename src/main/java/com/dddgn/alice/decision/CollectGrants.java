package com.dddgn.alice.decision;

import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **收集授权**（S3.5 第二步 / D-138 裁定）：玩家把某片**水平范围**（竖直自适应，沿用 D-130 的区域语义）
 * 授权给 bot 捡拾 —— 落在授权区内的掉落物归 {@code GRANTED_AREA}（默认策略 `AUTO` ⇒ 主动与被动两条路都放行）。
 *
 * <p>为什么必须有它：默认 `FOREIGN`（玩家丢的/未知来源）= `ASK`，于是"**派 bot 去捡那片东西**"这条
 * 真实需求会被自己的安全策略挡住。授权就是这个需求的正规出口，且**只能由玩家签发**（决策层只能请求）。
 *
 * <p>范围与期限：
 * <ul>
 *   <li>{@code ONCE}：只对**下一次**收集生效（用掉即销账）；</li>
 *   <li>{@code SESSION}：本次服务器会话有效（内存）；</li>
 *   <li>{@code ALWAYS}：**持久化**（SavedData），并且**必须在报告里显式标记**（用户裁定第 4 条：
 *       免得以后忘了 bot 为什么在捡玩家的东西）。</li>
 * </ul>
 */
public final class CollectGrants {

    /** 一次授权（水平范围 + 期限 + 来源）。 */
    public record Grant(String id, int minX, int minZ, int maxX, int maxZ, long untilTick,
                        PermissionGate.Scope scope, String grantedBy, int radius) {

        public Grant {
            minX = Math.min(minX, maxX);
            maxX = Math.max(minX, maxX);
            minZ = Math.min(minZ, maxZ);
            maxZ = Math.max(minZ, maxZ);
        }

        public boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }

        /** 是否仍在有效期（{@code untilTick < 0} = 永久）。 */
        public boolean validAt(long tick) {
            return untilTick < 0 || tick <= untilTick;
        }

        public String describe() {
            return id + " x" + minX + ".." + maxX + " z" + minZ + ".." + maxZ
                    + (untilTick < 0 ? " 永久" : " 至 tick " + untilTick)
                    + " scope=" + scope + " by=" + grantedBy;
        }
    }

    /** 会话级授权（`ONCE` / `SESSION`；`ALWAYS` 走持久化的 {@link GrantsData}）。 */
    private static final Map<String, Grant> SESSION = new LinkedHashMap<>();
    private static int sequence;

    private CollectGrants() {
    }

    // ==================== 签发与查询 ====================

    /** 加一条授权（`ALWAYS` 会同时写持久化）。 */
    public static Grant add(MinecraftServer server, int minX, int minZ, int maxX, int maxZ,
                            PermissionGate.Scope scope, String grantedBy, int durationTicks) {
        String id = "g" + (++sequence);
        long until = scope == PermissionGate.Scope.ALWAYS || durationTicks < 0
                ? -1L : server.getTickCount() + Math.max(20, durationTicks);
        Grant grant = new Grant(id, minX, minZ, maxX, maxZ, until, scope, grantedBy, 0);
        if (scope == PermissionGate.Scope.ALWAYS) {
            // **ALWAYS 只进持久化表**：否则同一条会在"会话 + 持久化"里各出现一次（列表重复）
            GrantsData.get(server).add(grant);
        } else {
            SESSION.put(id, grant);
        }
        BotLog.warn("[Grant] add {}（{} ⇒ 落在该范围内的掉落物按 GRANTED_AREA 处理）", grant.describe(),
                scope == PermissionGate.Scope.ALWAYS ? "**always 级授权（已持久化，报告会显式标记）**"
                        : scope.name());
        // 事件环需要 bot 实例才能入环；授权是**玩家级**事实，这里只留日志（报告里单独列授权）
        return grant;
    }

    /** 覆盖该位置的授权（会话 + 持久化），过期的自动销账。 */
    public static Grant covering(MinecraftServer server, BlockPos pos) {
        long now = server.getTickCount();
        SESSION.entrySet().removeIf(entry -> !entry.getValue().validAt(now));
        for (Grant grant : SESSION.values()) {
            if (grant.contains(pos)) {
                return grant;
            }
        }
        for (Grant grant : GrantsData.get(server).all()) {
            if (grant.contains(pos)) {
                return grant;
            }
        }
        return null;
    }

    /** 全部有效授权（报告用；`ALWAYS` 会带标记）。 */
    public static List<Grant> active(MinecraftServer server, long now) {
        List<Grant> result = new ArrayList<>();
        SESSION.entrySet().removeIf(entry -> !entry.getValue().validAt(now));
        result.addAll(SESSION.values());
        result.addAll(GrantsData.get(server).all());
        return result;
    }

    /** 清空（`/alice grant clear`）：会话 + 持久化一起清。 */
    public static int clear(MinecraftServer server) {
        int size = SESSION.size() + GrantsData.get(server).all().size();
        SESSION.clear();
        GrantsData.get(server).clearAll();
        BotLog.warn("[Grant] clear 全部授权（共 {} 条）", size);
        return size;
    }

    /**
     * **把该位置的 `ONCE` 授权销账**（用掉一次即失效）。
     *
     * <p>由收集路径在真正把东西捡起来之后调用 —— 这样"授权一次"不会变成"永久放行"。
     */
    public static void consumeOnce(MinecraftServer server, BlockPos pos) {
        SESSION.entrySet().removeIf(entry -> entry.getValue().scope() == PermissionGate.Scope.ONCE
                && entry.getValue().contains(pos));
    }

    // ==================== 持久化（`always`）====================

    public static final class GrantsData extends SavedData {
        private static final String DATA_KEY = "alice_collect_grants";
        private final List<Grant> grants = new ArrayList<>();

        public static GrantsData get(MinecraftServer server) {
            return server.overworld().getDataStorage()
                    .computeIfAbsent(GrantsData::load, GrantsData::new, DATA_KEY);
        }

        public void add(Grant grant) {
            grants.add(grant);
            setDirty();
        }

        public List<Grant> all() {
            return List.copyOf(grants);
        }

        public void clearAll() {
            grants.clear();
            setDirty();
        }

        private static GrantsData load(CompoundTag root) {
            GrantsData data = new GrantsData();
            ListTag list = root.getList("grants", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag tag = list.getCompound(i);
                PermissionGate.Scope scope;
                try {
                    scope = PermissionGate.Scope.valueOf(tag.getString("scope"));
                } catch (IllegalArgumentException ex) {
                    scope = PermissionGate.Scope.ALWAYS;
                }
                data.grants.add(new Grant(tag.getString("id"), tag.getInt("min_x"), tag.getInt("min_z"),
                        tag.getInt("max_x"), tag.getInt("max_z"), -1L, scope,
                        tag.getString("by"), 0));
            }
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag root) {
            ListTag list = new ListTag();
            for (Grant grant : grants) {
                CompoundTag tag = new CompoundTag();
                tag.putString("id", grant.id());
                tag.putInt("min_x", grant.minX());
                tag.putInt("min_z", grant.minZ());
                tag.putInt("max_x", grant.maxX());
                tag.putInt("max_z", grant.maxZ());
                tag.putString("scope", grant.scope().name());
                tag.putString("by", grant.grantedBy());
                list.add(tag);
            }
            root.put("grants", list);
            return root;
        }
    }
}
