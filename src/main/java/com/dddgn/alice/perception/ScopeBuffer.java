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
 *   <li>区间内的方块破坏(BlockEvent.BreakEvent),用于外部扰动感知。</li>
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
    private static final double DROP_PAIR_RADIUS = 3.0D;

    private BlockPos center;
    private int radius;
    private boolean active;
    /** 作用域所有者（bot）；只把"该所有者造成的破坏"登记为掉落来源，避免把附近玩家挖的掉落物算进来。 */
    private java.util.UUID ownerUuid;
    private final List<ItemEntity> spawnedItems = new ArrayList<>();
    /** 掉落物 → 其**来源方块**（由破坏事件配对得到；未配对为 null）。 */
    private final java.util.Map<java.util.UUID, BlockPos> itemOrigins = new java.util.HashMap<>();
    private final List<BlockPos> brokenBlocks = new ArrayList<>();
    /** 最近的破坏事件（pos + 游戏 tick），用于与随后生成的掉落物配对。 */
    private final List<BreakRecord> recentBreaks = new ArrayList<>();

    private record BreakRecord(BlockPos pos, long tick) {
    }

    /** 待登记掉落物：生成事件里先排队，**tick 末**再确认它真的进入了世界。 */
    private record PendingItem(ItemEntity item, long tick) {
    }

    private final List<PendingItem> pending = new ArrayList<>();

    /** 注册监听区间(重复调用先结束旧区间)。 */
    public void begin(BlockPos center, int radius) {
        begin(center, radius, null);
    }

    /** 注册监听区间，并指定所有者（只有该玩家的破坏事件才登记为掉落来源）。 */
    public void begin(BlockPos center, int radius, java.util.UUID owner) {
        end();
        this.center = center;
        this.radius = radius;
        this.ownerUuid = owner;
        this.active = true;
        this.pending.clear();
        ACTIVE.add(this);
        BotLog.info("作用域开启: center={} radius={} owner={}", center.toShortString(), radius, owner);
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
            ownerUuid = null;
        }
    }

    public boolean isActive() {
        return active;
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
            adopted++;
        }
        return adopted;
    }

    /**
     * 掉落物是否**真的在世界里**：被其他模组取消的生成不会进入世界，
     * 但实体对象仍满足 {@code isAlive()}，不剔除就会变成永远追不到的幻影
     * （Ore Excavation 连锁期间缓冲掉落物即为此类）。
     */
    private static boolean inWorld(ItemEntity item) {
        return !item.isRemoved() && !item.getItem().isEmpty()
                && item.level() instanceof ServerLevel level && level.getEntity(item.getId()) != null;
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
                scope.pending.add(new PendingItem(item, tick));
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
     * 确认排队掉落物：被模组取消/缓冲的生成不会进入世界（{@code level.getEntity(id) == null}），
     * 直接丢弃并如实记录——否则会变成"永远追不到的幻影掉落物"。
     */
    private void flushPending() {
        if (pending.isEmpty()) {
            return;
        }
        List<PendingItem> batch = List.copyOf(pending);
        pending.clear();
        if (!active) {
            return;
        }
        for (PendingItem entry : batch) {
            ItemEntity item = entry.item();
            BlockPos pos = item.blockPosition();
            if (!inWorld(item)) {
                BotLog.info("作用域忽略未进入世界的掉落物(生成被取消/缓冲): {} x{} y{} z{}",
                        item.getItem().getItem(), pos.getX(), pos.getY(), pos.getZ());
                continue;
            }
            spawnedItems.add(item);
            long now = item.level() instanceof ServerLevel level ? level.getGameTime() : entry.tick();
            pruneBreaks(now);
            BlockPos source = matchBreakSource(pos, entry.tick());
            itemOrigins.put(item.getUUID(), source);
            BotLog.info("作用域捕捉掉落物: {} x{} y{} z{} source={}",
                    item.getItem().getItem(), pos.getX(), pos.getY(), pos.getZ(),
                    source == null ? "unpaired" : source.toShortString());
        }
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
            // 只登记所有者造成的破坏（附近玩家/爆炸的掉落物不计入本 bot 的收集目标）
            if (scope.ownerUuid == null || scope.ownerUuid.equals(breaker)) {
                scope.recentBreaks.add(new BreakRecord(event.getPos().immutable(), tick));
                scope.pruneBreaks(tick);
            }
        }
    }
}
