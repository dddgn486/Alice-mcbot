package com.dddgn.alice.perception;

import com.dddgn.alice.log.BotLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 任务作用域缓冲区(设计文档 §3.2 的首次落地,M1 骨架)。
 * <p>
 * 任务启动时以目标为中心注册一个监听区间,在任务生命周期内实时记录:
 * <ul>
 *   <li>新生成的掉落物(EntityJoinLevelEvent → ItemEntity),用于「挖完去捡掉落物」;</li>
 *   <li>区间内的方块破坏(BlockEvent.BreakEvent),用于外部扰动感知;</li>
 *   <li>区间内的生物死亡(LivingDeathEvent)⇒ **我方击杀的产物归我方**(`A3`)。</li>
 * </ul>
 * 事件回调均在服务端主线程,实例过滤只做距离判断,O(1) 开销。
 * </p>
 * <p>⚠️ 审查点 R7:事件订阅为全局静态(所有作用域共享一次事件派发),
 * 与「任务内直接 hook」相比省注册开销,但作用域多时过滤循环会线性增长;
 * M3 若作用域规模扩大,再评估按区块分桶。</p>
 */
public final class ScopeBuffer {

    /** 所有活跃作用域(静态事件派发到每个实例做距离过滤)。 */
    private static final List<ScopeBuffer> ACTIVE = new CopyOnWriteArrayList<>();

    /** 破坏事件与随后掉落物的配对窗口（tick）与半径（格）：覆盖连锁挖掘模组。 */
    private static final int DROP_PAIR_WINDOW_TICKS = 10;

    /**
     * **宽限窗口**（`D-348`，2026-09-20）：排队掉落物在这个窗口内**允许"还没被登记进世界"**，超时才丢弃。
     *
     * <p>为什么必须有它 —— 实测（临时探针 + 10 tick 后验）：
     * <ul>
     *   <li>`EntityJoinLevelEvent` 是在 `PersistentEntitySectionManager` **把实体登记进查找表之前**
     *       发出的（探针调用栈：`PersistentEntitySectionManager:79 → EventBus`）⇒ `level.getEntity(id)`
     *       在事件当刻**必然是 null**；</li>
     *   <li>登记可能被推迟：真实挖掘掉落物 **1~4 tick**（`single:mine_run_metrics` 10 连跑猎捕实测）、
     *       刚 forceload 的区块里 summon **19 tick**（`D-345` 实测）⇒ 窗口取 {@code 40}（≈2 倍余量）；</li>
     *   <li>旧实现只在 **tick 末判一次**就永久丢弃 ⇒ 会把**真的会进世界**的掉落物丢掉
     *       （收集器永远看不到它 ⇒ `MineJob` 如实 `FAILED product_not_collected`，**本可成功**）。</li>
     * </ul>
     * ⚠️ 窗口内登记的**归属仍然正确**：直接配对（{@link #DROP_PAIR_WINDOW_TICKS} 10 tick / 3 格）与
     * 松窗（60 tick / 4 格，`D-138`）用的都是**入队 tick**（不是登记 tick）⇒ 窗口内到达照旧算我方。
     */
    private static final int PENDING_GRACE_TICKS = 40;
    private static final double DROP_PAIR_RADIUS = 3.0D;

    private BlockPos center;
    private int radius;
    private boolean active;
    /** 作用域所有者（bot）；只把"该所有者造成的破坏"登记为掉落来源，避免把附近玩家挖的掉落物算进来。 */
    private java.util.UUID ownerUuid;
    private final List<ItemEntity> spawnedItems = new ArrayList<>();
    /** 掉落物 → 其**来源方块**（由破坏事件配对得到；未配对为 null）。 */
    private final java.util.Map<java.util.UUID, BlockPos> itemOrigins = new java.util.HashMap<>();
    /**
     * 掉落物 → **归属**（S3.5 / D-138）：破坏直配 = `OURS_DIRECT`；我方击杀 = `OURS_KILL`（`A3`）；
     * 落在"我方动作点松窗内" = `OURS_INDIRECT`。未登记 = 不是我们的（`FOREIGN`，由 `DropPolicy` 判定）。
     */
    private final java.util.Map<java.util.UUID, com.dddgn.alice.decision.DropPolicy.Provenance>
            itemProvenance = new java.util.HashMap<>();
    private final List<BlockPos> brokenBlocks = new ArrayList<>();
    /** 最近的破坏事件（pos + 游戏 tick），用于与随后生成的掉落物配对。 */
    private final List<BreakRecord> recentBreaks = new ArrayList<>();

    // ==================== `A3`：**我方击杀**（2026-09-24） ====================
    // 为什么要有这条通道：`recentBreaks` 只认 `BlockEvent.BreakEvent`，而**击杀不产生破坏事件** ⇒
    // 击杀产物没有任何归属来源 ⇒ `DropPolicy.effectiveProvenance` 落到 `FOREIGN` ⇒ `PickupGate`
    // 在被动路径上直接拦下（`drop.foreign = ASK`）⇒ "自己杀的牛，肉捡不起来，只有一行节流日志"。
    // 攻击/猎杀能力上线后第一天就是这个形状（`survey/29 §2.1`）⇒ 归属先行。
    /** 我方击杀记录（死亡点 + tick + 受害者），与 `recentBreaks` 同形的"时间窗 + 空间窗"配对。 */
    private final List<KillRecord> recentKills = new ArrayList<>();

    private record KillRecord(BlockPos pos, long tick, String victim) {
    }

    // ==================== G3：**外来破坏**（模组连锁/爆炸/其他玩家） ====================
    // 2026-09-12 复核：原先只登记"我方破坏"，范围内由**别人/模组**造成的破坏被静默忽略 ——
    // 世界确实被改了，但账本、预算、上报里都看不见。这里把事实记下来并上报（**不改行为**：
    // 是否要阻止外来破坏属于另一层决策，本批只保证"不再无声无息"）。
    /** 范围内"非我方"破坏的**有界**记录（最新在后）。 */
    private final java.util.Deque<ForeignBreak> foreignBreaks = new java.util.ArrayDeque<>();
    private static final int FOREIGN_CAPACITY = 16;
    private int foreignBreakCount;
    private String lastForeignSummary = "-";

    /** 一次外来破坏的事实：位置、tick、归因（uuid 或 `unattributed`=无玩家来源，如爆炸/模组程序化破坏）。 */
    public record ForeignBreak(BlockPos pos, long tick, String source) {
    }

    private record BreakRecord(BlockPos pos, long tick) {
    }

    /** 待登记掉落物：生成事件里先排队，**tick 末**再确认它真的进入了世界。 */
    /**
     * 排队项（`D-348` 起带上**入队时已解析好的归属**）。
     *
     * <p>为什么归属必须在入队时定、而不是登记时算：直接配对窗口只有 {@link #DROP_PAIR_WINDOW_TICKS}(10) tick，
     * 破坏记录也会被 prune ⇒ 若登记被推迟到 21 tick（实测），**登记时已经查不到那条记录了** ⇒
     * 掉落物会以 `FOREIGN(未登记)` 落账 ⇒ `liveDrops()` 里看不到、收集器也捡不起来
     * （实测：宽限窗口救回了实体但丢了归属 ⇒ 夹具当场判红）。语义窗口本身**不变**，
     * 参照点仍是**入队 tick**（= 掉落物真正出现在世界的那一刻），只是不再因技术延迟而失效。
     */
    private record PendingItem(ItemEntity item, long tick, BlockPos source,
                               com.dddgn.alice.decision.DropPolicy.Provenance provenance) {
    }

    private final List<PendingItem> pending = new ArrayList<>();

    /**
     * **宽限窗口内的排队项**（`D-348`）：tick 末还没被确认进世界、但**仍在窗口内**的那些。
     * 每个 tick 末与当 tick 的新项一起复验；窗口用完 ⇒ 丢弃并如实记录。
     */
    private final List<PendingItem> deferred = new ArrayList<>();

    /** 累计"进入过宽限窗口"的项数（`D-348` 只读读数，供夹具断言"**这一轮确实推迟了**"）。 */
    private int deferredEnteredCount;
    /** 实测到的**最大登记延迟**（tick）：窗口内项最终被确认进世界时 `now - 入队tick` 的最大值。 */
    private long maxDeferTicks;

    /** 注册监听区间(重复调用先结束旧区间)。 */
    public void begin(BlockPos center, int radius) {
        begin(center, radius, null);
    }

    /** 注册监听区间，并指定所有者（只有该玩家的破坏事件才登记为掉落来源）。 */
    public void begin(BlockPos center, int radius, java.util.UUID owner) {
        begin(center, radius, owner, true);
    }

    /**
     * 注册监听区间（可指定是否**继承**已登记的掉落物）。
     *
     * <p>**D-124 / T4：重开区间默认继承掉落物归属**。历史病灶：{@link #begin} 原先无条件
     * {@link #end()}，而 `end()` 会清空 {@code spawnedItems}/{@code itemOrigins} ⇒
     * "先配对到来源、随后区间被重开"的掉落物**丢掉归属**，即使它还好端端躺在地上；
     * 后续收集再也看不见它（实测：拆除任务重开区间后 `live_drops=0`，收尾收集无物可追）。
     * D-108 的 {@link #adoptExistingDrops} 只是当时的手工补丁（而且会把**别人**的掉落物也收养进来）。
     *
     * <p>现在的语义：重开只换"监听窗口"，**仍活着且落在新窗口内**的我方掉落物照旧记账；
     * 被移出窗口/已消失的自然淘汰；{@code pending}/{@code brokenBlocks}/{@code recentBreaks}
     * 仍照常清空（它们只服务"刚刚发生的破坏-掉落配对"，跨重开没有意义）。
     * **整个会话结束时**仍走 {@link #end()}（`BotSession.clearTask` 调用），所以跨任务不会串味。
     *
     * @param inheritDrops false = 旧行为（彻底清空后重开），仅供需要"干净区间"的调用点显式选择
     */
    public void begin(BlockPos center, int radius, java.util.UUID owner, boolean inheritDrops) {
        List<ItemEntity> carried = new ArrayList<>();
        java.util.Map<java.util.UUID, BlockPos> carriedOrigins = new java.util.HashMap<>();
        if (inheritDrops && active) {
            long maxDistSqr = (long) radius * radius;
            for (ItemEntity item : spawnedItems) {
                if (!inWorld(item) || item.blockPosition().distSqr(center) > maxDistSqr) {
                    continue;   // 已消失 / 已被移出新区间 ⇒ 不再记账
                }
                carried.add(item);
                BlockPos origin = itemOrigins.get(item.getUUID());
                if (origin != null) {
                    carriedOrigins.put(item.getUUID(), origin);
                }
            }
        }
        if (active) {
            ACTIVE.remove(this);
            active = false;
        }
        this.center = center;
        this.radius = radius;
        this.ownerUuid = owner;
        this.active = true;
        this.pending.clear();
        this.deferred.clear();      // `D-348`：宽限窗口里的项也必须随作用域重置清掉
        this.deferredEnteredCount = 0;
        this.maxDeferTicks = 0L;
        this.spawnedItems.clear();
        this.itemOrigins.clear();
        this.brokenBlocks.clear();
        this.recentBreaks.clear();
        this.recentKills.clear();
        this.foreignBreaks.clear();
        this.foreignBreakCount = 0;
        this.lastForeignSummary = "-";
        this.spawnedItems.addAll(carried);
        this.itemOrigins.putAll(carriedOrigins);
        ACTIVE.add(this);
        if (carried.isEmpty()) {
            BotLog.info("作用域开启: center={} radius={} owner={}", center.toShortString(), radius, owner);
        } else {
            BotLog.info("作用域重开: center={} radius={} owner={} 继承掉落物={}（仍在区间内的我方掉落物）",
                    center.toShortString(), radius, owner, carried.size());
        }
    }

    public void end() {
        if (active) {
            ACTIVE.remove(this);
            active = false;
            pending.clear();
            spawnedItems.clear();
            itemOrigins.clear();
            brokenBlocks.clear();
            recentBreaks.clear();
            recentKills.clear();
            foreignBreaks.clear();
            foreignBreakCount = 0;
            lastForeignSummary = "-";
            ownerUuid = null;
        }
    }

    public boolean isActive() {
        return active;
    }

    /**
     * **当前作用域半径**（只读；无活动作用域 ⇒ `0`）。
     *
     * <p>为什么需要暴露它（`D-346`）：`CollectDropsTask` 的**追取上限**必须 ≥ 本作业作用域的直径
     * —— 能进 `liveDrops()` 的落物**只可能是本作用域内登记的**（{@link #onEntityJoin} 的
     * {@link #inScope}）⇒ 上限比作用域还小，就等于"**自己挖出来的产物被自己的上限退休**"
     * （实测：`MineJob` 作用域半径 24（直径 48）> 旧上限 32 ⇒ `retire reason=too_far`）。
     * 这里只给读数，不改任何行为。
     */
    public int currentRadius() {
        return active ? radius : 0;
    }

    /**
     * **收养**当前已经躺在世界里的掉落物（D-108）：把它们纳入本作用域的登记表，使其重新成为
     * `liveDrops()` 的候选。
     *
     * <p>为什么需要：{@link #begin} 会先 {@link #end}，而 `end()` 会清空 {@code spawnedItems} /
     * {@code itemOrigins}。于是"**先登记过、随后作用域被重开**"的掉落物**会丢失归属**——即使它
     * 还好端端躺在地上。实测（J7 Step 1，2026-09-11 19:54）：拆除任务重开作用域后，
     * 高处作业掉落的目标方块 `live_drops=0`，收尾收集**无物可追**，`drops_left=1`（世界事实）。
     *
     * <p>正确用法是"**先拆除落地、再收集**"时调用一次：此时 bot 在地面，掉落物就在旁边。
     * 收养条目的"来源方块"取它**当前所在格**（原始来源已不可考），因此只会被
     * {@code liveDrops()}（按来源配对）接受，语义仍是"我方掉落物"。
     *
     * <p>**调用方负责范围**：本方法把半径内**所有**存活掉落物都收养（无法按主人归属，
     * 掉落物实体不带"谁挖的"信息），所以只在孤立场景/明确属于我方的区域里用。
     *
     * @return 新收养的条目数（已在登记表里的不重复计入）
     */
    public int adoptExistingDrops(ServerLevel level, BlockPos center, int radius) {
        if (!active) {
            return 0;
        }
        spawnedItems.removeIf(item -> !inWorld(item));
        java.util.Set<java.util.UUID> known = new java.util.HashSet<>();
        for (ItemEntity item : spawnedItems) {
            known.add(item.getUUID());
        }
        int adopted = 0;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class,
                new net.minecraft.world.phys.AABB(center).inflate(radius))) {
            if (!inWorld(item) || known.contains(item.getUUID())) {
                continue;
            }
            spawnedItems.add(item);
            itemOrigins.put(item.getUUID(), item.blockPosition().immutable());
            itemProvenance.put(item.getUUID(),
                    com.dddgn.alice.decision.DropPolicy.Provenance.OURS_DIRECT);
            adopted++;
        }
        return adopted;
    }

    /**
     * **间接归属**（S3.5 / D-138 裁定：松窗起点 **60 tick / 4 格**，可配置 + 进报告便于标定）。
     *
     * <p>覆盖用户点名的两类：砍树后**树叶自然衰减**掉的树苗/木棍、移除支撑后**仙人掌/甘蔗**弹出 ——
     * 它们不是"我方破坏事件直接配对"，但发生在**我方动作点附近的时间窗内**。
     *
     * @return 匹配到的动作点；不在窗口内 ⇒ null
     */
    private BlockPos matchIndirectOrigin(BlockPos itemPos, long tick) {
        double radiusSqr = com.dddgn.alice.decision.DropPolicy.INDIRECT_WINDOW_RADIUS
                * com.dddgn.alice.decision.DropPolicy.INDIRECT_WINDOW_RADIUS;
        BlockPos best = null;
        long bestAge = Long.MAX_VALUE;
        for (BreakRecord record : recentBreaks) {
            long age = tick - record.tick();
            if (age < 0 || age > com.dddgn.alice.decision.DropPolicy.INDIRECT_WINDOW_TICKS) {
                continue;
            }
            if (record.pos().distSqr(itemPos) > radiusSqr) {
                continue;
            }
            if (age < bestAge) {
                bestAge = age;
                best = record.pos();
            }
        }
        return best;
    }

    /** 立刻按"我方掉落物"登记（夹具用；真实来源见 {@link #matchBreakSource} / {@link #matchIndirectOrigin}）。 */
    public boolean registerAsOurs(net.minecraft.world.entity.item.ItemEntity item,
                                 com.dddgn.alice.decision.DropPolicy.Provenance provenance,
                                 BlockPos origin) {
        if (item == null || provenance == null) {
            return false;
        }
        itemProvenance.put(item.getUUID(), provenance);
        itemOrigins.put(item.getUUID(), origin == null ? item.blockPosition().immutable() : origin);
        return true;
    }

    /**
     * 掉落物是否**真的在世界里**：被其他模组取消的生成不会进入世界，
     * 但实体对象仍满足 {@code isAlive()}，不剔除就会变成永远追不到的幻影
     * （Ore Excavation 连锁期间缓冲掉落物即为此类）。
     */
    /**
     * `D-348`：**在入队那一刻**解析归属（直接配对 10 tick / 3 格 → 击杀 10 tick / 3 格 → 松窗 60 tick / 4 格）。
     *
     * <p>入队 = 掉落物刚出现在世界的那一刻 ⇒ 破坏/击杀记录必然是新鲜的；把结果**存进排队项**，
     * 登记被推迟多久都不会丢归属（见 {@link PendingItem}）。
     *
     * <p>⭐ `A3`（2026-09-24）：**击杀**排在"间接松窗"**之前** —— 两者都能命中时取**更精确的证据**
     * （击杀是确证的死因；间接窗口是 60 tick/4 格的兜底）。三条通道的优先级：
     * 破坏直配 → 我方击杀 → 间接松窗。
     */
    private PendingItem pendingEntry(ItemEntity item, long tick, BlockPos pos) {
        BlockPos source = matchBreakSource(pos, tick);
        com.dddgn.alice.decision.DropPolicy.Provenance provenance = null;
        if (source != null) {
            provenance = com.dddgn.alice.decision.DropPolicy.Provenance.OURS_DIRECT;
        } else {
            BlockPos killed = matchKillOrigin(pos, tick);
            if (killed != null) {
                source = killed;
                provenance = com.dddgn.alice.decision.DropPolicy.Provenance.OURS_KILL;
            } else {
                BlockPos indirect = matchIndirectOrigin(pos, tick);
                if (indirect != null) {
                    source = indirect;
                    provenance = com.dddgn.alice.decision.DropPolicy.Provenance.OURS_INDIRECT;
                }
            }
        }
        return new PendingItem(item, tick, source, provenance);
    }

    /** `D-348` 只读读数：累计进入过宽限窗口的项数（0 = 本作用域内没有任何登记被推迟）。 */
    public int deferredEnteredCount() {
        return deferredEnteredCount;
    }

    /** `D-348` 只读读数：实测到的最大登记延迟（tick）；0 = 从未发生推迟。 */
    public long maxDeferTicks() {
        return maxDeferTicks;
    }

    private static boolean inWorld(ItemEntity item) {
        return !item.isRemoved() && !item.getItem().isEmpty()
                && item.level() instanceof ServerLevel level && level.getEntity(item.getId()) != null;
    }

    /**
     * 该掉落物的**归属**（S3.5）：{@code null} = 不在我方登记表里（即 `FOREIGN`）。
     *
     * <p>`OURS_INDIRECT` 的判定见 {@link #matchIndirectOrigin}（我方动作点的时间/空间松窗）。
     */
    public com.dddgn.alice.decision.DropPolicy.Provenance provenanceOf(net.minecraft.world.entity.item.ItemEntity item) {
        return item == null ? null : itemProvenance.get(item.getUUID());
    }

    /** 作用域内仍存活、仍有内容的掉落物(每次调用清理已消失的)。 */
    public List<ItemEntity> liveItems() {
        return liveItemsFrom(0);
    }

    /** 返回指定事件序号之后生成的存活掉落物，用于区分主目标产物与清障副产物。 */
    public List<ItemEntity> liveItemsFrom(int index) {
        spawnedItems.removeIf(item -> !inWorld(item));
        int from = Math.max(0, Math.min(index, spawnedItems.size()));
        return List.copyOf(spawnedItems.subList(from, spawnedItems.size()));
    }

    /**
     * 已配对到**破坏事件**的存活掉落物（D-074）：来源来自
     * `BlockEvent.BreakEvent` + 随后 {@link #DROP_PAIR_WINDOW_TICKS} 内、{@link #DROP_PAIR_RADIUS} 内的生成事件。
     * <p>连锁挖掘模组会一次破坏多格 → 每个破坏点都登记 → 其掉落物无论聚在一处还是各掉一份都能配对。
     */
    public List<ItemEntity> liveDrops() {
        spawnedItems.removeIf(item -> !inWorld(item));
        return spawnedItems.stream()
                .filter(item -> itemOrigins.get(item.getUUID()) != null)
                .toList();
    }

    /** 首次捕获时来源方块格等于 origin 的存活掉落物。 */
    public List<ItemEntity> liveItemsFromOrigin(BlockPos origin) {
        spawnedItems.removeIf(item -> !inWorld(item));
        return spawnedItems.stream()
                .filter(item -> origin.equals(itemOrigins.get(item.getUUID())))
                .toList();
    }

    /** 作用域内被破坏的方块坐标(含 bot 自己挖的,外部扰动感知用)。 */
    public List<BlockPos> brokenBlocks() {
        return List.copyOf(brokenBlocks);
    }

    /** 与最近窗口内、最近距离的破坏点配对；无匹配返回 null（例如区块加载带入的旧物品）。 */
    private BlockPos matchBreakSource(BlockPos itemPos, long tick) {
        BreakRecord best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BreakRecord record : recentBreaks) {
            if (tick - record.tick() > DROP_PAIR_WINDOW_TICKS) {
                continue;
            }
            double distance = record.pos().distSqr(itemPos);
            if (distance <= DROP_PAIR_RADIUS * DROP_PAIR_RADIUS && distance < bestDistance) {
                bestDistance = distance;
                best = record;
            }
        }
        if (best != null) {
            return best.pos();
        }
        // 回退：掉落物位置命中"本作用域内已登记的破坏点"（缓冲型模组在结束时才生成掉落物，
        // 早于配对窗口；它们通常落在被破坏方块的位置上）
        for (BlockPos broken : brokenBlocks) {
            if (broken.equals(itemPos)) {
                return broken;
            }
        }
        return null;
    }

    private void pruneBreaks(long tick) {
        recentBreaks.removeIf(record -> tick - record.tick() > DROP_PAIR_WINDOW_TICKS);
    }

    /** `A3`：击杀记录的过期淘汰（与破坏记录同一窗口/同一条纪律）。 */
    private void pruneKills(long tick) {
        recentKills.removeIf(record -> tick - record.tick() > DROP_PAIR_WINDOW_TICKS);
    }

    /**
     * ⭐ `A3`：与最近的**我方击杀**配对（时间窗 + 空间窗，参数与破坏配对一致）。
     *
     * <p>为什么用"窗口配对"而不是"在死亡事件里直接给产物标 UUID"：本类的纪律是
     * **归属在掉落物入队那一刻解析**（`D-348`），而 `LivingDeathEvent` 与产物进世界之间隔着
     * `dropAllDeathLoot`（模组还可能重排/缓冲生成事件）⇒ 直接标注会依赖事件顺序，且
     * `flushPending` 会用自己的 `source` 覆盖 `itemOrigins`。窗口配对与 {@link #matchBreakSource}
     * 完全同形，而**死亡就发生在产物那一格**（半径 3 格绰绰有余）。
     *
     * @return 命中则返回**死亡点** —— 它同时当"来源"用 ⇒ `liveDrops()` 能看见它、收集任务能收它
     */
    private BlockPos matchKillOrigin(BlockPos itemPos, long tick) {
        double radiusSqr = DROP_PAIR_RADIUS * DROP_PAIR_RADIUS;
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (KillRecord record : recentKills) {
            if (tick - record.tick() > DROP_PAIR_WINDOW_TICKS) {
                continue;
            }
            double distance = record.pos().distSqr(itemPos);
            if (distance <= radiusSqr && distance < bestDistance) {
                bestDistance = distance;
                best = record.pos();
            }
        }
        return best;
    }

    private boolean inScope(BlockPos pos) {
        return active && pos != null && pos.distSqr(center) <= (long) radius * radius;
    }

    // ---- 全局事件(所有作用域共享派发) ----

    /**
     * 生成事件必须**最后**处理（LOWEST）：连锁模组会在自己的 handler 里
     * {@code event.setCanceled(true)} 并缓冲掉落物——被取消的生成不会进入世界。
     * Forge 默认不把已取消事件投递给未声明 {@code receiveCanceled} 的监听器，
     * 因此排在取消方之后即可彻底避免把"幻影掉落物"登记进作用域。
     */
    /**
     * 生成事件只**排队**，不立刻登记（见 {@link #flushPending()}）：
     * 连锁模组会在自己的 handler 里取消生成并缓冲掉落物，而它的监听器同样是
     * LOWEST 优先级，靠注册顺序不可靠。排队到 tick 末再校验"实体是否真的在世界里"，
     * 与事件顺序无关。
     */
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.isCanceled()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ItemEntity item)) {
            return;
        }
        long tick = level.getGameTime();
        BlockPos pos = item.blockPosition();
        for (ScopeBuffer scope : ACTIVE) {
            if (scope.inScope(pos)) {
                scope.pending.add(scope.pendingEntry(item, tick, pos));
            }
        }
    }

    /** 服务端 tick 末确认排队中的掉落物是否真的进入了世界。 */
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void onServerTickEnd(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) {
            return;
        }
        for (ScopeBuffer scope : ACTIVE) {
            scope.flushPending();
        }
    }

    /**
     * 确认排队掉落物：tick 末校验"实体是否真的进了世界"（`level.getEntity(id) != null`）。
     *
     * <p>⚠️ **`D-348`（2026-09-20）实测更正**：这里原来把这句日志写成"生成被取消/缓冲"，
     * 但那只是**解释**、只说对了一半 —— 实测（临时探针 + 10 tick 后验）：
     * <ul>
     *   <li><b>模组取消生成</b>（连锁挖掘 Ore Excavation 的 `captureAgent` 会取消 ItemEntity 生成并缓冲，
     *       结束时 `dropEverything()` 在同一格重新生成）⇒ 实体**永远不会**进世界 ⇒ 丢弃正确；
     *       归档实证：每次 CORE 稳定 17 条（`exec_chain` 用例），而同一轮里真产物
     *       `raw_iron … provenance=OURS_DIRECT source=23,64,172` **被正常捕捉** ⇒ 无物品损失；</li>
     *   <li>⭐ <b>注册被延迟</b> —— `EntityJoinLevelEvent` 是在 `PersistentEntitySectionManager`
     *       **把实体登记进查找表之前**发出的（探针调用栈：`PersistentEntitySectionManager:79 → EventBus`），
     *       而登记可能晚 **1~4 tick** ⇒ **在 tick 末只判一次就永久丢弃，会把真的会进世界的掉落物丢掉**
     *       （实测：被丢弃的同一个实体 `id=75` 在 1 tick / 4 tick 后 `visible=true`，
     *        收集器因此看不到它 ⇒ `MineJob` 如实 `FAILED product_not_collected`）。</li>
     * </ul>
     * ⇒ 因此本方法**只打实测子项**（不再替读者下结论），且**不在第一 tick 就下最终判断**这件事
     * 已登记为 `D-348` 的修复方向（宽限窗口）。
     */
    private void flushPending() {
        if (pending.isEmpty() && deferred.isEmpty()) {
            return;
        }
        // ⚠️ 必须是**可变**副本：`List.copyOf` 不可变 ⇒ `addAll` 会抛
        // `UnsupportedOperationException` 并把服务端 tick 打死（`D-348` 修复首跑实测踩到，别改回去）。
        List<PendingItem> batch = new ArrayList<>(pending);
        pending.clear();
        // `D-348`：上一轮"还没确认进世界、但仍在宽限窗口内"的项，与本轮新项一起复验
        batch.addAll(deferred);
        deferred.clear();
        if (!active) {
            return;
        }
        for (PendingItem entry : batch) {
            ItemEntity item = entry.item();
            BlockPos pos = item.blockPosition();
            if (!inWorld(item)) {
                long graceNow = item.level() instanceof ServerLevel graceLevel
                        ? graceLevel.getGameTime() : entry.tick();
                long age = graceNow - entry.tick();
                if (age < PENDING_GRACE_TICKS) {
                    // ⭐ `D-348`：**不再在 tick 末一次定生死** —— 放进宽限窗口，下个 tick 末再复验。
                    if (age == 0) {
                        BotLog.info("作用域登记被推迟（{} tick 宽限窗口内复验，不再当 tick 丢弃）: {} x{} y{} z{}",
                                PENDING_GRACE_TICKS, item.getItem().getItem(),
                                pos.getX(), pos.getY(), pos.getZ());
                    }
                    deferredEnteredCount++;
                    deferred.add(entry);
                    continue;
                }
                BotLog.info("作用域丢弃未确认进入世界的掉落物（宽限窗口 {} tick 已用完）: {} x{} y{} z{}"
                                + " removed={} empty={} inGetEntity={} chunkLoaded={}",
                        PENDING_GRACE_TICKS, item.getItem().getItem(), pos.getX(), pos.getY(), pos.getZ(),
                        item.isRemoved(), item.getItem().isEmpty(),
                        item.level() instanceof ServerLevel lookupLevel
                                && lookupLevel.getEntity(item.getId()) != null,
                        item.level().hasChunkAt(pos));
                continue;
            }
            spawnedItems.add(item);
            long now = item.level() instanceof ServerLevel level ? level.getGameTime() : entry.tick();
            maxDeferTicks = Math.max(maxDeferTicks, now - entry.tick());   // `D-348`：实测登记延迟
            pruneBreaks(now);
            // `D-348`：归属用**入队时**解析好的那一份（① 直接配对 10 tick/3 格 → OURS_DIRECT；
            // ② 松窗 60 tick/4 格 → OURS_INDIRECT）—— 登记被推迟也不丢归属（否则会退化成 FOREIGN 捡不起来）。
            BlockPos source = entry.source();
            com.dddgn.alice.decision.DropPolicy.Provenance provenance = entry.provenance();
            itemOrigins.put(item.getUUID(), source);
            if (provenance != null) {
                itemProvenance.put(item.getUUID(), provenance);
            }
            BotLog.info("作用域捕捉掉落物: {} x{} y{} z{} provenance={} source={}",
                    item.getItem().getItem(), pos.getX(), pos.getY(), pos.getZ(),
                    provenance == null ? "FOREIGN(未登记)" : provenance,
                    source == null ? "unpaired" : source.toShortString());
        }
    }

    private void recordForeignBreak(BlockPos pos, long tick, String source) {
        foreignBreakCount++;
        lastForeignSummary = pos.toShortString() + " tick=" + tick + " by=" + source;
        foreignBreaks.addLast(new ForeignBreak(pos, tick, source));
        while (foreignBreaks.size() > FOREIGN_CAPACITY) {
            foreignBreaks.removeFirst();
        }
        if (foreignBreakCount == 1 || foreignBreakCount % 10 == 0) {
            BotLog.warn("[Scope] 范围内**外来破坏** #{} {}（非我方；账本不恢复、预算不计入 —— 仅如实记录）",
                    foreignBreakCount, lastForeignSummary);
        }
    }

    /** G3 事实：范围内外来破坏的累计次数（0 = 本作用域内世界只被我们改过）。 */
    public int foreignBreakCount() {
        return foreignBreakCount;
    }

    public String describeForeignBreaks() {
        return foreignBreakCount == 0 ? "外来破坏=0"
                : "外来破坏=" + foreignBreakCount + "（最后 " + lastForeignSummary + "）";
    }

    /** 最近的外来破坏（最新在后，供汇报）。 */
    public java.util.List<ForeignBreak> recentForeignBreaks() {
        return java.util.List.copyOf(foreignBreaks);
    }

    /**
     * ⭐ `A3`（2026-09-24）：**击杀归属** —— 我方打死的生物，其产物算我方（`OURS_KILL`）。
     *
     * <p><b>为什么必须有这条订阅</b>：`onBlockBreak` 只认 `BlockEvent.BreakEvent`，而**击杀不产生
     * 破坏事件** ⇒ 击杀产物没有任何归属来源 ⇒ 落 `FOREIGN` ⇒ `PickupGate` 直接拦下（`drop.foreign=ASK`）
     * ⇒ "自己杀的牛，肉捡不起来"，且只有一行节流日志。攻击/猎杀能力一旦上线，第一天就是这个形状。
     *
     * <p><b>归因口径（谁算"我方"）</b>：① 先认 `LivingEntity.getKillCredit()` —— 它覆盖
     * "我打伤之后它死于火焰/坠落/摔伤"这类**我方行为的后果**；② 再认伤害来源实体（投射物也算
     * `getEntity()` = 射手的场合）。两条都要是**玩家** —— 模组程序化死亡（无玩家来源）一律不认。
     * ③ 最后与破坏同一条纪律：killer 必须是**本作用域的 owner**（`ownerUuid == null` = 匿名作用域，
     * 与前文口径一致）⇒ 别的玩家在同一片地杀的东西**不算我们的**（产物继续走 `FOREIGN` + 闸门拦截）。
     *
     * <p>⚠️ 诚实边界：**玩家驯服的宠物**（狼/猫）的击杀credit 是主人，`getKillCredit()` 会给出主人 ⇒
     * 按"我方"处理；"我方宠物"这个概念今天不存在（Alice 不养宠物），不做区分。
     */
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void onLivingDeath(net.minecraftforge.event.entity.living.LivingDeathEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        java.util.UUID killer = creditedKiller(event);
        if (killer == null) {
            return;   // 无玩家归因（自然死亡/模组/生物互殴）⇒ 产物仍是 FOREIGN，不许算我们的
        }
        long tick = level.getGameTime();
        BlockPos pos = event.getEntity().blockPosition().immutable();
        for (ScopeBuffer scope : ACTIVE) {
            if (!scope.inScope(pos)) {
                continue;
            }
            if (scope.ownerUuid != null && !scope.ownerUuid.equals(killer)) {
                continue;   // 别人打的 ⇒ 不是我们的（不要静默认领）
            }
            scope.recentKills.add(new KillRecord(pos, tick, victimId(event)));
            scope.pruneKills(tick);
        }
    }

    /** 击杀归因（`A3`）：先认击杀credit（含"打伤后死于他因"），再认伤害来源实体；都要求是玩家。 */
    private static java.util.UUID creditedKiller(
            net.minecraftforge.event.entity.living.LivingDeathEvent event) {
        // ⚠️ 1.20.1 的 `getKillCredit()` 返回 `LivingEntity`（无玩家时会给 `lastHurtByMob`）
        // ⇒ **必须再判一次是不是玩家**，否则"生物互殴"会被当成我方击杀（`A3` 反向臂就是钉这个）。
        net.minecraft.world.entity.LivingEntity credit = event.getEntity().getKillCredit();
        if (credit instanceof net.minecraft.world.entity.player.Player player) {
            return player.getUUID();
        }
        if (event.getSource().getEntity()
                instanceof net.minecraft.world.entity.player.Player player) {
            return player.getUUID();
        }
        return null;
    }

    private static String victimId(net.minecraftforge.event.entity.living.LivingDeathEvent event) {
        return String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getKey(event.getEntity().getType()));
    }

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        java.util.UUID breaker = event.getPlayer() == null ? null : event.getPlayer().getUUID();
        long tick = serverLevel.getGameTime();
        for (ScopeBuffer scope : ACTIVE) {
            if (!scope.inScope(event.getPos())) {
                continue;
            }
            scope.brokenBlocks.add(event.getPos().immutable());
            if (scope.ownerUuid == null || scope.ownerUuid.equals(breaker)) {
                // 我方破坏：用于掉落物配对
                scope.recentBreaks.add(new BreakRecord(event.getPos().immutable(), tick));
                scope.pruneBreaks(tick);
            } else {
                // **G3：外来破坏**（模组连锁/爆炸/其他玩家）⇒ 记录 + 计数 + 节流告警（不再静默）
                scope.recordForeignBreak(event.getPos().immutable(), tick,
                        breaker == null ? "unattributed" : breaker.toString());
            }
        }
    }
}
