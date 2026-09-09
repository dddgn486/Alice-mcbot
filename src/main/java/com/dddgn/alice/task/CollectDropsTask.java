package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.session.PathExecutionResult;
import com.dddgn.alice.perception.ScopeBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 掉落物收集子任务（D-072，**公用子任务**）：把指定来源产生的掉落物捡回来。
 *
 * <p>设计（`docs/MINE_MIGRATION_DESIGN.md` §4，用户裁定）：
 * <ul>
 *   <li>**职责单一**：只收集，不挖方块、不搭桥；"怎么过去"完全交给寻路内核；</li>
 *   <li>**终点 = 掉落物所在位置**（`item.blockPosition()`），不做"相邻站位"改写；</li>
 *   <li>**按需授予世界修改权限**：`allowWorldModification=true` → `PathRequest.withWorldModification`
 *       （可破坏/放置打通路线）；false → 纯通行，够不到就标记不可达；</li>
 *   <li>**自然拾取**：站到物品格（或 1 格内）后等待原版拾取（默认 10 tick 延迟 + 余量），
 *       **不反射、不调 `playerTouch`**；</li>
 *   <li>**best-effort**：收不到不判 FAILED，输出 `[CollectDrops] SUMMARY collected=n/m unreachable=k`；</li>
 *   <li>**每物品预算 + 任务总预算**，替代 legacy 的多套计数与挖台阶逻辑。</li>
 * </ul>
 */
public final class CollectDropsTask implements Task {

    /** 任务总预算（tick）。 */
    private static final int DEFAULT_TOTAL_BUDGET_TICKS = 600;
    /** 单个物品的追踪预算（tick）。 */
    private static final int ITEM_BUDGET_TICKS = 200;
    /** 到位后等待自然拾取的 tick 数（原版拾取延迟 10 tick + 余量）。 */
    private static final int PICKUP_WAIT_TICKS = 40;
    /** 判定"站在物品格附近"的水平距离（格）。 */
    private static final double PICKUP_RADIUS = 1.2D;

    private final BotPlayer bot;
    private final BlockPos origin;
    private final ScopeBuffer scope;
    private final Set<UUID> expectedIds;
    private final boolean allowWorldModification;
    private final int totalBudgetTicks;

    private int ticks;
    private int collected;
    private final Set<UUID> known = new LinkedHashSet<>();
    private final Set<UUID> unreachable = new LinkedHashSet<>();
    private final Set<UUID> vanished = new LinkedHashSet<>();
    private final Set<UUID> gone = new LinkedHashSet<>();
    private UUID lastTargetId;
    private boolean lastTargetNear;
    private PathRetryRunner runner;
    private UUID currentId;
    private BlockPos currentPos;
    private int itemTicks;
    private int waitTicks;
    private String failure = "";

    public CollectDropsTask(BotPlayer bot, BlockPos origin, ScopeBuffer scope,
                            List<UUID> expectedIds, boolean allowWorldModification) {
        this(bot, origin, scope, expectedIds, allowWorldModification, DEFAULT_TOTAL_BUDGET_TICKS);
    }

    public CollectDropsTask(BotPlayer bot, BlockPos origin, ScopeBuffer scope,
                            List<UUID> expectedIds, boolean allowWorldModification, int totalBudgetTicks) {
        this.bot = bot;
        this.origin = origin.immutable();
        this.scope = scope;
        this.expectedIds = expectedIds == null ? Set.of() : Set.copyOf(expectedIds);
        this.allowWorldModification = allowWorldModification;
        this.totalBudgetTicks = Math.max(40, totalBudgetTicks);
    }

    @Override
    public TaskTarget target() {
        return TaskTarget.block(origin);
    }

    @Override
    public String failureReason() {
        return failure;
    }

    @Override
    public Status tick() {
        if (++ticks > totalBudgetTicks) {
            return finish("timeout");
        }
        List<ItemEntity> items = candidates();
        trackDisappearances(items);
        if (items.isEmpty()) {
            return finish("done");
        }
        ItemEntity item = choose(items);
        lastTargetId = item.getUUID();
        BlockPos itemPos = item.blockPosition().immutable();

        // 目标切换（消失/超距/换物品）→ 重置追踪状态
        if (currentId == null || !currentId.equals(item.getUUID()) || !itemPos.equals(currentPos)) {
            cancelRunner();
            currentId = item.getUUID();
            currentPos = itemPos;
            itemTicks = 0;
            waitTicks = 0;
        }
        if (++itemTicks > ITEM_BUDGET_TICKS) {
            markUnreachable(item.getUUID(), "item_budget");
            return Status.RUNNING;
        }

        // 已在拾取范围内 → 等待原版自然拾取
        double dx = bot.getX() - (itemPos.getX() + 0.5D);
        double dz = bot.getZ() - (itemPos.getZ() + 0.5D);
        boolean near = Math.abs(bot.getY() - itemPos.getY()) <= 1.5D
                && Math.sqrt(dx * dx + dz * dz) <= PICKUP_RADIUS;
        lastTargetNear = near;
        if (near) {
            cancelRunner();
            if (++waitTicks >= PICKUP_WAIT_TICKS && !item.isRemoved()) {
                markUnreachable(item.getUUID(), "pickup_wait_timeout");
            }
            return Status.RUNNING;
        }

        // 移动：终点 = 掉落物所在位置
        if (runner == null) {
            PathRequest request = allowWorldModification
                    ? PathRequest.withWorldModification(bot.getUUID().toString(), bot.blockPosition(), itemPos)
                    : PathRequest.of(bot.getUUID().toString(), bot.blockPosition(), itemPos);
            runner = new PathRetryRunner(bot, request, PathRetryRunner.DEFAULT_MAX_REPLANS,
                    "collect-" + itemPos.getX() + "_" + itemPos.getY() + "_" + itemPos.getZ());
            BotLog.info("[CollectDrops] chase item={} pos={} feet={} worldMod={}",
                    item.getUUID(), itemPos.toShortString(), bot.blockPosition().toShortString(),
                    allowWorldModification);
        }
        PathRetryRunner.State state = runner.tick();
        if (state == PathRetryRunner.State.RUNNING) {
            return Status.RUNNING;
        }
        if (state == PathRetryRunner.State.DONE) {
            cancelRunner();
            return Status.RUNNING;
        }
        PathExecutionResult result = runner.result();
        markUnreachable(item.getUUID(), result == null ? "unreachable" : result.status().name());
        return Status.RUNNING;
    }

    /** 当前仍在范围内、且未标记不可达的候选物品。 */
    private List<ItemEntity> candidates() {
        List<ItemEntity> result = new ArrayList<>();
        for (ItemEntity item : scope.liveItemsFromOrigin(origin)) {
            UUID id = item.getUUID();
            known.add(id);
            if (unreachable.contains(id)) {
                continue;
            }
            if (!expectedIds.isEmpty() && !expectedIds.contains(id)) {
                continue;
            }
            result.add(item);
        }
        return result;
    }

    /** 最近优先 + 粘滞（同一物品连续追）。 */
    private ItemEntity choose(List<ItemEntity> items) {
        if (currentId != null) {
            for (ItemEntity item : items) {
                if (item.getUUID().equals(currentId)) {
                    return item;
                }
            }
        }
        return items.stream()
                .min(Comparator.comparingDouble(item -> bot.distanceToSqr(item)))
                .orElse(items.get(0));
    }

    /** 记录"从已知集合里消失"的物品：目标物品且在拾取范围内 → 计为已收集；否则计为消失。 */
    private void trackDisappearances(List<ItemEntity> live) {
        Set<UUID> liveIds = new LinkedHashSet<>();
        for (ItemEntity item : live) {
            liveIds.add(item.getUUID());
        }
        for (UUID id : known) {
            if (liveIds.contains(id) || gone.contains(id)) {
                continue;
            }
            gone.add(id);
            if (id.equals(lastTargetId) && lastTargetNear) {
                collected++;
                BotLog.info("[CollectDrops] collected item={}", id);
            } else {
                vanished.add(id);
                BotLog.info("[CollectDrops] vanished item={}", id);
            }
        }
    }

    private void markUnreachable(UUID id, String reason) {
        cancelRunner();
        if (unreachable.add(id)) {
            BotLog.warn("[CollectDrops] unreachable item={} reason={} pos={}",
                    id, reason, currentPos == null ? "-" : currentPos.toShortString());
        }
        currentId = null;
        currentPos = null;
    }

    private void cancelRunner() {
        if (runner != null) {
            runner.cancel();
            runner = null;
        }
        bot.controller().stopMovement();
    }

    private Status finish(String reason) {
        cancelRunner();
        int total = known.size();
        int missing = Math.max(0, total - collected);
        String summary = "reason=" + reason + " collected=" + collected + "/" + total
                + " vanished=" + vanished.size() + " unreachable=" + unreachable.size()
                + " ticks=" + ticks;
        BotLog.info("[CollectDrops] SUMMARY {}", summary);
        return Status.DONE;
    }

    public int collected() {
        return collected;
    }
}
