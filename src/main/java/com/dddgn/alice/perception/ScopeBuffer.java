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

    private BlockPos center;
    private int radius;
    private boolean active;
    private final List<ItemEntity> spawnedItems = new ArrayList<>();
    private final java.util.Map<java.util.UUID, BlockPos> itemOrigins = new java.util.HashMap<>();
    private final List<BlockPos> brokenBlocks = new ArrayList<>();

    /** 注册监听区间(重复调用先结束旧区间)。 */
    public void begin(BlockPos center, int radius) {
        end();
        this.center = center;
        this.radius = radius;
        this.active = true;
        ACTIVE.add(this);
        BotLog.info("作用域开启: center={} radius={}", center.toShortString(), radius);
    }

    public void end() {
        if (active) {
            ACTIVE.remove(this);
            active = false;
            spawnedItems.clear();
            itemOrigins.clear();
            brokenBlocks.clear();
        }
    }

    public boolean isActive() {
        return active;
    }

    /** 作用域内仍存活、仍有内容的掉落物(每次调用清理已消失的)。 */
    public List<ItemEntity> liveItems() {
        return liveItemsFrom(0);
    }

    /** 返回指定事件序号之后生成的存活掉落物，用于区分主目标产物与清障副产物。 */
    public List<ItemEntity> liveItemsFrom(int index) {
        spawnedItems.removeIf(item -> !item.isAlive() || item.getItem().isEmpty());
        int from = Math.max(0, Math.min(index, spawnedItems.size()));
        return List.copyOf(spawnedItems.subList(from, spawnedItems.size()));
    }

    /** 首次捕获时来源方块格等于 origin 的存活掉落物。 */
    public List<ItemEntity> liveItemsFromOrigin(BlockPos origin) {
        spawnedItems.removeIf(item -> !item.isAlive() || item.getItem().isEmpty());
        return spawnedItems.stream()
                .filter(item -> origin.equals(itemOrigins.get(item.getUUID())))
                .toList();
    }

    /** 作用域内被破坏的方块坐标(含 bot 自己挖的,外部扰动感知用)。 */
    public List<BlockPos> brokenBlocks() {
        return List.copyOf(brokenBlocks);
    }

    private boolean inScope(BlockPos pos) {
        return active && pos != null && pos.distSqr(center) <= (long) radius * radius;
    }

    // ---- 全局事件(所有作用域共享派发) ----

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel && event.getEntity() instanceof ItemEntity item) {
            for (ScopeBuffer scope : ACTIVE) {
                if (scope.inScope(item.blockPosition())) {
                    scope.spawnedItems.add(item);
                    scope.itemOrigins.put(item.getUUID(), item.blockPosition().immutable());
                    BotLog.info("作用域捕捉掉落物: {} x{} y{} z{}",
                            item.getItem().getItem(), item.getBlockX(), item.getBlockY(), item.getBlockZ());
                }
            }
        }
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel) {
            for (ScopeBuffer scope : ACTIVE) {
                if (scope.inScope(event.getPos())) {
                    scope.brokenBlocks.add(event.getPos().immutable());
                }
            }
        }
    }
}
