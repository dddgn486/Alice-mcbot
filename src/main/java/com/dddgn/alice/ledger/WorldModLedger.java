package com.dddgn.alice.ledger;

import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.action.WritePolicyMatrix;
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
 * <p>⭐⭐ <b>适用地理范围（`D-398`，用户 2026-09-22 决定性断言）：只记"保护区及其子区域"。</b>
 * 无主区域（区外）**不负任何修改责任**（R1）、**一定不恢复**（R2）、**无限制修改**（R4）；
 * 保护区内**一定记账**、但不要求立即恢复（R3）。于是：
 * <ul>
 *   <li>写入期：{@link #recordPlacement} 在区外**不记**（只留一行 `[Ledger] skip` + 遥测计数）；</li>
 *   <li>读取期：{@link #pendingTemporaryProtected} 是回收的取件口径（区外条目一律不认）；</li>
 *   <li>对账期：{@link #dropStale} 把旧存档遗留 / unclaim 后的区外条目销掉（逐条留日志）；</li>
 *   <li>⇒ `J6` 不变量的适用面随之收窄为"**保护区内**的条目"（`Z2`）。</li>
 * </ul>
 * ⚠️ **后果**：区外的垫脚方块不再被收尾拆回（野外会留下圆石、并净消耗一次性方块）—— 这是
 * "区外一定不恢复"的推论，不是遗漏；口径与判据统一在
 * {@link com.dddgn.alice.protection.ProtectionZones}。
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
    /** ⭐ `D-398`：被跳过的"区外放置"次数（遥测，见 {@link #outsideSkipCount}）。 */
    private int outsideSkips;

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

    /**
     * 该 owner 当前打开的作用域 id；没有打开时返回 {@code null}。
     *
     * <p>供 {@code action/WriteBudget}（D-106）按"一次任务 = 一个作用域"记账；
     * 与 {@link #scopeOf} 不同，这里**不回退**到 {@code implicit}——
     * 没有任务作用域时写入不设上限（作用域是记账单位），由调用方决定如何留痕。
     */
    public static String currentScope(MinecraftServer server, UUID owner) {
        WorldModLedger ledger = get(server);
        return owner == null ? null : ledger.openScopes.get(owner);
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
     * <p>⭐⭐ <b>`D-398`（用户 2026-09-22 决定性断言）：账本只记"保护区及其子区域"内的放置。</b>
     * 无主区域（区外）的修改**不负任何责任**（R1/R2）⇒ 既不进账本、也不会产生任何恢复动作。
     * 判据口径与"为什么任务区不算保护区"见 {@link com.dddgn.alice.protection.ProtectionZones}。
     *
     * <p>这条过滤是**真机事故的直接修复**（`D-406` §二）：玩家在野外指派挖矿 ⇒ 内核 `PILLAR` 在
     * 无主区域放了 7 格垫脚石 ⇒ 全部进账 ⇒ 任务收尾的自动拆除把 bot **正踩着的那格**拆了 ⇒ bot 坠落。
     * 按裁定，那片地**本来就不该有条目**，也就没有谁会去拆它。
     *
     * <p>⚠️ <b>代价（如实登记，别当没发生）</b>：区外不再记账 ⇒ 区外的垫脚方块**不会**在任务收尾被
     * 拆回背包（`RestoreScopeTask` 只认区内条目）⇒ 野外作业会留下垫脚石、并**净消耗**一次性方块
     * （`BlockInteraction.THROWAWAY`，通常是圆石）。这是"区外一定不恢复"的直接推论，不是遗漏；
     * 要改就得改裁定本身。
     *
     * <p><b>不是静默丢弃</b>：每次跳过留一行 `[Ledger] skip`（位置/方块/理由/累计数），
     * 并计入 {@link #outsideSkipCount}（同量级开销：原先每次放置本来也打一行 `[Ledger] place`）。
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
        // ⭐ `D-398` R1/R2/R4：区外不记账（= 不承担恢复责任 = 不产生回收义务）。
        if (com.dddgn.alice.protection.ProtectionZones.isWild(level, pos)) {
            ledger.outsideSkips++;
            ledger.setDirty();
            BotLog.info("[Ledger] skip {} place={} by={}（区外：D-398 R1/R2 不记账、不恢复；累计 {} 次）",
                    pos.toShortString(), blockId(placed), grant.describe(), ledger.outsideSkips);
            return;
        }
        // D-207 ①：回收义务由**集中策略表**（区域归属 × 任务类别）解析，不再在此处直接问理由。
        // 这是**执行期复验**：规划期已由 `CorePathPlanner.plan` 拦过一道，这里是"真的要在世界里动手"那一刻的第二次判定
        // （与 CapabilityGate 的复验理由相同：计划可能比产生它的请求活得久）。
        Policy policy = WritePolicyMatrix.ledgerPolicy(level, owner, grant, pos);
        Entry entry = new Entry(pos.immutable(), blockId(placed), blockId(previous),
                grant.reason().name(), policy, scopeOf(ledger, owner), owner, level.getGameTime());
        ledger.entries.put(key(pos), entry);
        ledger.setDirty();
        BotLog.info("[Ledger] place {} by={}", entry.describe(), grant.describe());
    }

    /**
     * **销掉不该留在账本里的条目**：两类，各打一行日志。
     *
     * <ol>
     *   <li><b>幽灵条目</b>：现场已不是账本记录的方块（场景重放清掉、别人拆掉、我方已拆都算）。
     *       为什么需要：`not_ours` 判定原来只在恢复任务**走到**那一格时才执行——
     *       而测试夹具的场景重放会把方块直接抹掉，条目却留在账本里 ⇒ `pending` 只增不减。
     *       这里把判定提前到"动作发生的地方"（任务收尾 / 触发恢复前），账本因此保持"活的"。</li>
     *   <li>⭐ <b>区外条目</b>（`D-398`，2026-09-22）：账本**只认保护区内**的条目。
     *       `recordPlacement` 已经不再产生区外条目，这里处理的是**旧存档遗留**（Z1 之前放的行）
     *       以及"**先记账、后取消认领**"的情形（玩家 unclaim ⇒ 那片地变回无主区域 ⇒ 回收义务随之消失）。
     *       ⚠️ 夹具注意：**先 unclaim 再收尾**会让收尾看不到条目（本函数按时序正确地销掉它们）；
     *       要验"区内收尾"就必须**收尾之后再 unclaim**。</li>
     * </ol>
     *
     * <p>**不是静默丢弃**：每条被销掉的都记日志（含账本记录 vs 现场方块）。
     *
     * @return 销掉的条数（两类之和；明细见日志）
     */
    public static int dropStale(ServerLevel level) {
        MinecraftServer server = level.getServer();
        if (server == null) {
            return 0;
        }
        WorldModLedger ledger = get(server);
        List<String> dropped = new ArrayList<>();
        List<String> outside = new ArrayList<>();
        java.util.Iterator<Map.Entry<String, Entry>> it = ledger.entries.entrySet().iterator();
        while (it.hasNext()) {
            Entry entry = it.next().getValue();
            // 先判区域归属（纯认领集查询，不读方块）：区外条目**无论现场是什么**都不该在账本里。
            if (com.dddgn.alice.protection.ProtectionZones.isWild(level, entry.pos())) {
                outside.add(entry.describe());
                it.remove();
                continue;
            }
            String nowId = blockId(level.getBlockState(entry.pos()));
            if (!nowId.equals(entry.placed())) {
                dropped.add(entry.pos().toShortString() + "(" + entry.placed() + "→" + nowId + ")");
                it.remove();
            }
        }
        if (!dropped.isEmpty() || !outside.isEmpty()) {
            ledger.setDirty();
        }
        if (!dropped.isEmpty()) {
            BotLog.info("[Ledger] 销掉 {} 条已失效条目（现场已非我方方块）: {}",
                    dropped.size(), String.join(", ", dropped.size() > 8
                            ? dropped.subList(0, 8) : dropped));
        }
        if (!outside.isEmpty()) {
            BotLog.info("[Ledger] 销掉 {} 条区外条目（D-398：账本只记保护区内）: {}",
                    outside.size(), String.join(" | ", outside.size() > 8
                            ? outside.subList(0, 8) : outside));
        }
        return dropped.size() + outside.size();
    }

    /**
     * ⭐ `D-398`：**本次进程内被跳过的"区外放置"次数**（遥测读数，不参与任何判据的通过/失败）。
     *
     * <p>为什么要有：账本"区外恒为空"这条断言**在没有任何写入时也成立**（空跑假绿）⇒ 光看账本空
     * 分不清"过滤生效了"和"根本没干活"。夹具（`ledger_zone_scope`）据此断言**区外真的发生过放置**，
     * 再用"账本无条目"证明它被正确地拒绝记账。
     */
    public static int outsideSkipCount(MinecraftServer server) {
        return get(server).outsideSkips;
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
    /**
     * 该 owner 名下**所有**未拆除的我方临时放置（跨 scope）。
     *
     * <p>用途（J7 Step 3 / §12.4 崩溃兜底）：服务器上次是崩溃/被强杀退出的，账本里可能留着
     * "建了一半没拆"的脚手架；bot 重新可用时要能一眼看到它们，并在**就近**时续做拆除。
     */
    public static List<Entry> pendingForOwner(MinecraftServer server, java.util.UUID owner) {
        if (owner == null) {
            return List.of();
        }
        List<Entry> result = new java.util.ArrayList<>();
        for (Entry entry : get(server).entries.values()) {
            if (owner.equals(entry.owner()) && entry.policy() == Policy.TEMP) {
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * **只看"本步自己的 scope"**的临时放置（B 方案配套，2026-09-17）。
     *
     * <p>为什么要有它：断言"我没写世界"的夹具原先用 {@link #pendingForOwner}（**跨 scope**）⇒
     * 会被**别的步**的遗留误伤（2026-09-17 实测事故正是如此）。本方法把口径收回到当前作用域。
     * 没有打开的作用域时返回空表（此时"本步没写"这一判断无从谈起，由 B 方案的泄漏判红兜底）。
     */
    public static List<Entry> pendingTemporaryInCurrentScope(MinecraftServer server, java.util.UUID owner) {
        String scope = currentScope(server, owner);
        return scope == null ? List.of() : pendingTemporary(server, scope);
    }

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

    /**
     * ⭐ `D-398`：**待恢复的临时放置 —— 只认保护区内的条目**（`RestoreScopeTask` 的取件口径）。
     *
     * <p>为什么查询也要过滤（而不是只靠写入期过滤）：写入期过滤只保证**新条目**都在区内；
     * 旧存档遗留 + "先记账、后 unclaim"（`dropStale` 会销掉这类，但**调用时序**不保证它先跑过）
     * 仍可能让区外条目出现在这里 ⇒ 回收就会去动无主区域，正是裁定禁止的事。两道一起才闭合。
     */
    public static List<Entry> pendingTemporaryProtected(ServerLevel level, String scopeId) {
        MinecraftServer server = level.getServer();
        if (server == null) {
            return List.of();
        }
        List<Entry> result = new ArrayList<>();
        for (Entry entry : get(server).entries.values()) {
            if (entry.policy() != Policy.TEMP) {
                continue;
            }
            if (scopeId != null && !entry.scopeId().equals(scopeId)) {
                continue;
            }
            if (com.dddgn.alice.protection.ProtectionZones.isProtected(level, entry.pos())) {
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
        ledger.outsideSkips = 0;
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
        ledger.outsideSkips = root.getInt("outsideSkips");
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
        root.putInt("outsideSkips", outsideSkips);
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
