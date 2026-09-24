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

    /**
     * **撤销一条会话级授权**（按 id）。返回 `true` = 确实撤掉了。
     *
     * <p>为什么需要它（`D-344` ①）：区域作业只在**扫地面期间**放宽拾取，授权窗口应当**精确等于**
     * 那段时长 —— 光靠 TTL 会让权限多活最多一个 TTL。授权是"我签发的东西"，所以我该能收回来。
     *
     * <p>⚠️ 只覆盖**会话级**（`ONCE`/`SESSION`）；`ALWAYS` 是**玩家签发并持久化**的，作业无权撤销
     * ⇒ 传 `ALWAYS` 的 id 会**撤不掉**（这是有意的：作业不能悄悄改玩家的持久授权）。
     */
    public static boolean revoke(String id) {
        if (id == null) {
            return false;
        }
        return SESSION.remove(id) != null;
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

    // ==================== ⭐ `P3`：**作业级**收集授权（带产物过滤）====================

    /**
     * ⭐ `P3`（2026-09-22 用户裁定 / 2026-09-24 落地）：**作业自己声明范围内**的收集授权，
     * 且**只认本作业的目标产物**。
     *
     * <h3>为什么不能直接用上面的玩家授权</h3>
     * 玩家授权的语义是"这片地上的东西我都准你捡"（{@code GRANTED_AREA} ⇒ 策略 `AUTO`，
     * **主动与被动两条路都放行**）。而作业需要的只是"**我自己挖出来的产物**别因为没配上破坏事件
     * 就永远捡不起来" ⇒ 直接按片授权会把**范围内玩家丢的东西**一起吸走（被动吸附是范围触发）。
     * 所以作业级授权**必须带产物过滤**：范围内的目标产物 = `GRANTED_AREA`；**其余仍是 `FOREIGN`**
     * （被动闸门照旧拦）。
     *
     * <h3>三条结构性约束（都是刻意的）</h3>
     * <ol>
     *   <li>**只在内存**：作业无权把授权**持久化**成玩家授权（`D-344` 同一条口径：
     *       作业不能悄悄改玩家的持久授权）⇒ 本类不进 {@link GrantsData}，重启即无；</li>
     *   <li>**成对生命周期**：作业开工时签发、`finish()` 时撤销（四条终态路径都过那里）；
     *       TTL 只是"撤销路径被漏掉"的兜底，不是正常期限；</li>
     *   <li>**产物过滤是判据的一部分**，不是附带说明 ⇒ 查询入口要求传 `ItemStack`
     *       （只按坐标放行 = 把玩家授权的那套放宽悄悄搬进来，`P3` 夹具专门钉这条）。</li>
     * </ol>
     *
     * @param product 给人看的产物口径（例：`#forge:raw_materials/iron`），只进日志/报告
     * @param accepts 产物判据（生产路径 = `MineProductFilter::matches`）
     */
    public record JobGrant(String id, int minX, int minZ, int maxX, int maxZ, long untilTick,
                           String product, java.util.function.Predicate<net.minecraft.world.item.ItemStack> accepts,
                           String grantedBy) {

        public JobGrant {
            minX = Math.min(minX, maxX);
            maxX = Math.max(minX, maxX);
            minZ = Math.min(minZ, maxZ);
            maxZ = Math.max(minZ, maxZ);
        }

        public boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }

        /** 是否仍在有效期（`untilTick < 0` = 永久，但作业级授权**不许**用这个值）。 */
        public boolean validAt(long tick) {
            return untilTick < 0 || tick <= untilTick;
        }

        /** **产物判据**（范围 + 产物同时成立才是本作业的产物）。 */
        public boolean acceptsItem(net.minecraft.world.item.ItemStack stack) {
            return stack != null && !stack.isEmpty() && accepts != null && accepts.test(stack);
        }

        public String describe() {
            return id + " x" + minX + ".." + maxX + " z" + minZ + ".." + maxZ
                    + (untilTick < 0 ? " 永久" : " 至 tick " + untilTick)
                    + " product=" + product + " by=" + grantedBy + "（**仅内存**，作业结束即撤销）";
        }
    }

    /** 作业级授权（`P3`）：**内存表**，进程重启即无（由构造决定，没有持久化入口）。 */
    private static final Map<String, JobGrant> JOB_SCOPED = new LinkedHashMap<>();

    /**
     * 签发一条**作业级**授权（`P3`）。`durationTicks < 0 | 0` 会退化成**兜底上限**（见调用点注释），
     * 正常期限由作业在 `finish()` 里显式 `revokeJobScoped(...)` 决定。
     */
    public static JobGrant addJobScoped(MinecraftServer server, BlockPos center, int radius,
                                        String product,
                                        java.util.function.Predicate<net.minecraft.world.item.ItemStack> accepts,
                                        String grantedBy, int durationTicks) {
        int r = Math.max(1, radius);
        String id = "jg" + (++sequence);
        long until = server.getTickCount() + Math.max(20, durationTicks);
        JobGrant grant = new JobGrant(id, center.getX() - r, center.getZ() - r,
                center.getX() + r, center.getZ() + r, until, product, accepts, grantedBy);
        JOB_SCOPED.put(id, grant);
        BotLog.info("[Grant] 作业级授权 {}（范围内**且**属于该产物 ⇒ GRANTED_AREA；其余照旧 FOREIGN）",
                grant.describe());
        return grant;
    }

    /**
     * **查询作业级授权**（`P3` 的唯一入口）：范围命中 **且** 产物命中才算。
     *
     * <p>⚠️ 必须传 `ItemStack`：只按坐标查就等于把"整片都准捡"放宽进来（那正是玩家授权的语义，
     * 不是作业该有的）。过期的自动销账。
     */
    public static JobGrant coveringJobScoped(MinecraftServer server, BlockPos pos,
                                            net.minecraft.world.item.ItemStack stack) {
        long now = server.getTickCount();
        JOB_SCOPED.entrySet().removeIf(entry -> !entry.getValue().validAt(now));
        for (JobGrant grant : JOB_SCOPED.values()) {
            if (grant.contains(pos) && grant.acceptsItem(stack)) {
                return grant;
            }
        }
        return null;
    }

    /** 撤销一条作业级授权（作业终态必调；返回 `true` = 确实撤掉了）。 */
    public static boolean revokeJobScoped(String id) {
        if (id == null) {
            return false;
        }
        JobGrant removed = JOB_SCOPED.remove(id);
        if (removed != null) {
            BotLog.info("[Grant] 撤销作业级授权 {}（权限窗口 = 作业时长，`P3`）", removed.id());
        }
        return removed != null;
    }

    /**
     * 当前有效的作业级授权（报告/夹具读数用；过期的自动销账）。
     *
     * <p>⚠️ **时间基由本方法自己取**（`server.getTickCount()`）——**不**做成调用方传参：
     * 签发用的是 `getTickCount()`，而 `ServerLevel.getGameTime()` 是**另一个钟**（世界时间）。
     * 混用会让授权"凭空过期"（`P3` 夹具第一版就这么踩的：传了 `getGameTime()` ⇒ 授权刚签发就被销账，
     * 现象是"签了、日志里有、查询说没有"）。同一族的 `active(...)` 保留旧的 `now` 形参（既有调用方已定），
     * 新增的这条不再给这种错法留门。
     */
    public static List<JobGrant> activeJobScoped(MinecraftServer server) {
        long now = server.getTickCount();
        JOB_SCOPED.entrySet().removeIf(entry -> !entry.getValue().validAt(now));
        return List.copyOf(JOB_SCOPED.values());
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
