package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.MovementHelper;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.pathing.core.session.PathExecutionResult;
import com.dddgn.alice.perception.ScopeBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 掉落物收集子任务（D-072，**公用子任务**）：把指定来源产生的掉落物捡回来。
 *
 * <p>设计（`docs/MINE_MIGRATION_DESIGN.md` §4，用户裁定）：
 * <ul>
 *   <li>**来源判定**：只收集 {@code ScopeBuffer.liveDrops()}——即"由 bot 自己的破坏事件配对到的掉落物"
 *       （D-074；连锁挖掘模组的多点破坏同样覆盖）；</li>
 *   <li>**职责单一**：只收集，不挖方块、不搭桥；"怎么过去"完全交给寻路内核；</li>
 *   <li>**按需授予世界修改权限**：`allowWorldModification=true` → `PathRequest.withWorldModification`，
 *       false → 纯通行（`PathRequest.of`，HARD_PATH）；</li>
 *   <li>**自然拾取**：站进拾取范围后等待原版拾取，**不反射、不调 `playerTouch`**；</li>
 *   <li>**best-effort**：收不到不判 FAILED，输出 `[CollectDrops] SUMMARY ...`。</li>
 * </ul>
 *
 * <p>**簇级收集（D-075 修正，用户裁定）**：原版拾取盒为玩家包围盒外扩 ±1.0 x/z、±0.5 y，
 * 一次走位会同时吸走邻近多件——逐实体"追一个等一个"既慢又少报。现在：
 * <ol>
 *   <li>把候选按**连通距离 2.0 格、|Δy| ≤ 1** 聚成簇；</li>
 *   <li>走到簇内最近成员格，等该簇成员全部消失（或 40 tick 超时；超时后**最多再换 2 次锚点**扫尾）；</li>
 *   <li>计数用**背包增量**（唯一地面真相），不再用"实体是否消失"推断；</li>
 *   <li>**守恒交叉校验**：`背包增量 == 簇起始 stack 总和 − 结束时剩余存活 stack 总和`，
 *       不等就记 `MISMATCH`（暴露"同类型被他人拾取/重复生成/背包满"等干扰），不做静默相信。</li>
 * </ol>
 */
public final class CollectDropsTask implements Task {

    /** 任务总预算（tick）。 */
    private static final int DEFAULT_TOTAL_BUDGET_TICKS = 600;
    /** 单个簇的扫描预算（tick，含走位与等待）。 */
    private static final int CLUSTER_BUDGET_TICKS = 200;
    /** 到位后等待自然拾取的 tick 数（原版拾取延迟 10 tick + 余量）。 */
    private static final int PICKUP_WAIT_TICKS = 40;
    /** 判定"已站进拾取范围"的水平距离（格）。 */
    private static final double PICKUP_RADIUS = 1.2D;
    /** 簇内两个掉落物的最大连通距离（格）：对应原版拾取盒 ±1.3。 */
    private static final double CLUSTER_LINK_DISTANCE = 2.0D;
    /** 簇内允许的最大垂直差（格）。 */
    private static final int CLUSTER_LINK_DY = 1;
    /** 一个簇内最多换几次锚点扫尾（覆盖簇边缘够不到的物品）。 */
    private static final int MAX_REANCHORS = 2;
    /** 超过该距离（格）放弃追踪（D-074 用户裁定）。 */
    private static final double MAX_CHASE_DISTANCE = 32.0D;
    /** 等待"仍在空中下落的掉落物"落地的最大 tick 数（2026-09-10 修正）。 */
    private static final int MAX_SETTLE_TICKS = 40;

    private final BotPlayer bot;
    private final BlockPos origin;
    private final ScopeBuffer scope;
    private final Set<UUID> expectedIds;
    private final boolean allowWorldModification;
    private final int totalBudgetTicks;

    private int ticks;
    private String failure = "";

    // ---- 全局统计 ----
    private final Set<UUID> known = new LinkedHashSet<>();
    private final Set<UUID> firstSeen = new LinkedHashSet<>();
    private final Set<UUID> consumed = new LinkedHashSet<>();
    private final Set<UUID> retired = new LinkedHashSet<>();
    private final Map<UUID, Integer> lastSeenStack = new HashMap<>();
    private final Map<UUID, ItemEntity> liveById = new LinkedHashMap<>();
    /** 期望物品数 = 首次见到各实体时的 stack 数量之和（合并不会改变它）。 */
    private int expectedItems;
    /** 实际进背包的物品数 = 各次扫描的背包增量之和。 */
    private int collectedItems;
    private int clustersSwept;
    private int unreachableCount;
    private int pickupTimeoutCount;
    private int mismatchCount;

    // ---- 当前簇扫描状态 ----
    private List<UUID> clusterIds;
    private BlockPos anchor;
    private Map<Item, Integer> typeBefore;
    private int clusterStartSum;
    private int sweepTicks;
    private int waitTicks;
    private int reanchors;
    private int settleTicks;
    private PathRetryRunner runner;

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

    /** 实际进背包的物品数（背包增量口径）。 */
    public int collected() {
        return collectedItems;
    }

    @Override
    public Status tick() {
        if (++ticks > totalBudgetTicks) {
            return finish("timeout");
        }
        List<ItemEntity> live = refreshCandidates();
        trackDisappearances(live);

        if (clusterIds == null) {
            if (live.isEmpty()) {
                return finish("done");
            }
            beginCluster(live);
            return Status.RUNNING;
        }

        List<ItemEntity> members = liveMembers(live);
        if (members.isEmpty()) {
            endCluster(false);
            return Status.RUNNING;
        }
        // 掉落物**还不在接地状态**时不要追：它的"当前格"可能够不到，
        // 追它会得到假的 MOVEMENT_FAILED（2026-09-10 两次客户端实测：
        // ① 6/7 根原木时追下落中的物品；② 4/4 根全砍完但最后一根的掉落物被退役 → inventoryDelta=3）。
        // 等它落定（≤ MAX_SETTLE_TICKS）——落在簇内自然会被拾取，或随后正常走位去捡。
        if (members.stream().anyMatch(CollectDropsTask::isAirborne)
                && ++settleTicks <= MAX_SETTLE_TICKS) {
            if (settleTicks == 1) {
                BotLog.info("[CollectDrops] settling members={}（等待空中的掉落物落地）", members.size());
            }
            cancelRunner();
            return Status.RUNNING;
        }

        if (++sweepTicks > CLUSTER_BUDGET_TICKS) {
            for (ItemEntity member : members) {
                retire(member.getUUID(), "cluster_budget");
            }
            endCluster(true);
            return Status.RUNNING;
        }

        // 0) 尚未走位、且还没进入拾取范围 → 建路径（走位优先；不建就会"原地放弃"）
        if (runner == null && members.stream().noneMatch(this::inPickupRange)) {
            // D-114：寻路目标必须是"**够得着掉落物的可站格**"，而不是掉落物所在格。
            // 反例（2026-09-11 实测）：支撑块被拆后掉落物停在平台格 (23,64,189) ✓，而锚点是
            // 刚拆掉的支撑块所在格 (23,64,190)——空气+下面也是空气 ⇒ 不可站 ⇒ UNREACHABLE ⇒ 退役残留。
            normalizeAnchor(members);
            PathRequest request = allowWorldModification
                    ? PathRequest.withWorldModification(bot.getUUID().toString(), MovementHelper.footCell(bot.serverLevel(), bot), anchor, "collect-drops")
                    : PathRequest.of(bot.getUUID().toString(), MovementHelper.footCell(bot.serverLevel(), bot), anchor, "collect-drops");
            runner = new PathRetryRunner(bot, request, PathRetryRunner.DEFAULT_MAX_REPLANS,
                    "collect-" + anchor.getX() + "_" + anchor.getY() + "_" + anchor.getZ());
            BotLog.info("[CollectDrops] sweep_start anchor={} members={} feet={} worldMod={}",
                    anchor.toShortString(), members.size(), bot.blockPosition().toShortString(),
                    allowWorldModification);
        }

        // 1) 走位优先：runner 未结束就继续走完（原版拾取会在路过时自动发生，
        //    提前取消寻路会让 bot 停在"看着够得着、实际差半格"的位置上，D-076 修正）
        if (runner != null) {
            PathRetryRunner.State state = runner.tick();
            if (state == PathRetryRunner.State.RUNNING) {
                return Status.RUNNING;
            }
            if (state == PathRetryRunner.State.DONE) {
                cancelRunner();
                // **不提前 return**（2026-09-10 修正）：当目标格就是 bot 自己所在格时，
                // 请求退化成"从自己走自己" → `movements=0 / REACHED` → 本分支每 tick 命中一次，
                // 于是重建同一个请求、空转到 CLUSTER_BUDGET_TICKS 才放弃。
                // 实测：掉落物从浮空平台掉到相邻下方 (24,64,189)，收集器空转 201 tick 后
                // reason=cluster_budget，使 mine_regression 的 exec_floating 假失败。
                // 现在落到下方既有的"到位判定"：在拾取范围内就等，
                // 不在就 reanchor 到物品**当前**位置（reanchor 一直存在，只是此前走不到）。
            } else {
                PathExecutionResult result = runner.result();
                String reason = result == null ? "unreachable" : result.status().name();
                for (ItemEntity member : members) {
                    retire(member.getUUID(), reason);
                }
                endCluster(true);
                return Status.RUNNING;
            }
        }

        // 2) 已到位：只有**真的进入原版拾取范围**才等待（否则继续换锚点/如实退休）
        if (members.stream().anyMatch(this::inPickupRange)) {
            if (++waitTicks >= PICKUP_WAIT_TICKS) {
                if (reanchors < MAX_REANCHORS) {
                    reanchor(members);
                } else {
                    for (ItemEntity member : members) {
                        retire(member.getUUID(), "pickup_timeout");
                    }
                    endCluster(true);
                }
            }
            return Status.RUNNING;
        }

        // 3) 到位但够不到（物品卡在够不着的位置）→ 换最近成员再试，用尽后如实退休
        if (reanchors < MAX_REANCHORS) {
            reanchor(members);
            return Status.RUNNING;
        }
        for (ItemEntity member : members) {
            retire(member.getUUID(), "not_in_pickup_range");
        }
        endCluster(true);
        return Status.RUNNING;
    }

    /** 换到**离 bot 最近的存活成员**作为新锚点（原样重走）。 */
    private void reanchor(List<ItemEntity> members) {
        reanchors++;
        normalizeAnchor(members);
        waitTicks = 0;
        runner = null;
        BotLog.info("[CollectDrops] reanchor cluster_anchor={} remaining={} reanchors={}/{}",
                anchor.toShortString(), members.size(), reanchors, MAX_REANCHORS);
    }

    /**
     * 把当前锚点（= 最近掉落物的所在格）规范化成"**够得着它的可站格**"（D-114）。
     *
     * <p>为什么必须：掉落物常常停在**站不住的格**上或旁边——刚被拆掉的支撑块所在格（空气+下方空气）、
     * 1×1 竖井口、台阶边缘、悬空块上方……此时若照原样去寻路，规划器会如实报 `UNREACHABLE`
     * （它不会为"走到一个站不住的格"编路径），于是物品被退役、材料留在世界里。
     * 实测两例：J7 掉在壁柱顶（够不到）②D-112 支撑块拆除后掉落物落在平台格而锚点在井口。
     *
     * <p>判据：优先用掉落物所在格（今天的行为，可站时完全不变）；否则在其周围
     * （水平 4 邻、y ∈ {0,+1,-1}，必要时半径 2）找**最近的可站格**，且与掉落物在拾取半径内。
     * 一个都没有 → 保留原格（失败码保持诚实，随后照旧退役）。
     */
    private void normalizeAnchor(List<ItemEntity> members) {
        ItemEntity nearest = members.stream()
                .min(java.util.Comparator.comparingDouble(item -> bot.distanceToSqr(item)))
                .orElse(members.get(0));
        BlockPos itemCell = nearest.blockPosition().immutable();
        BlockPos goal = pickupGoalFor(nearest, itemCell);
        anchor = goal;
        if (!goal.equals(itemCell)) {
            BotLog.info("[CollectDrops] goal_shift item={} itemPos={} goal={}（掉落物所在格不可站 → 走到够得着的可站格）",
                    nearest.getUUID(), itemCell.toShortString(), goal.toShortString());
        }
    }

    /** 够得着该掉落物的可站格；掉落物所在格可站时原样返回。 */
    private BlockPos pickupGoalFor(ItemEntity item, BlockPos itemCell) {
        if (isStandableCell(itemCell)) {
            return itemCell;
        }
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int radius = 1; radius <= 2 && best == null; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;   // 只看这一圈的边
                    }
                    for (int dy = 0; dy >= -1; dy--) {
                        BlockPos cell = itemCell.offset(dx, dy, dz);
                        if (!isStandableCell(cell) || !withinPickupReach(cell, item)) {
                            continue;
                        }
                        double d = cell.distSqr(itemCell);
                        if (d < bestDist) {
                            bestDist = d;
                            best = cell.immutable();
                        }
                    }
                }
            }
        }
        return best == null ? itemCell : best;
    }

    /** 可站：脚下有支撑 + 脚位/头位可穿过（与挖掘站位同一口径）。 */
    private boolean isStandableCell(BlockPos cell) {
        return com.dddgn.alice.task.mining.StandingPointSelector
                .isStandable(bot.serverLevel(), cell);
    }

    /** 粗判"站在该格能否拾取到该掉落物"（原版拾取盒外扩 1.0 x/z、0.5 y）；精确判定仍走 inPickupRange。 */
    private static boolean withinPickupReach(BlockPos cell, ItemEntity item) {
        return Math.abs(cell.getX() + 0.5D - item.getX()) <= 1.2D
                && Math.abs(cell.getZ() + 0.5D - item.getZ()) <= 1.2D
                && Math.abs(cell.getY() - item.getY()) <= 1.2D;
    }

    /**
     * 是否已进入**原版拾取范围**：与原版 `Player.tick()` 的判定完全一致——
     * 玩家包围盒外扩 `1.0 x/z、0.5 y` 与掉落物包围盒相交（掉落物落地后中心约在方块底面 +0.125，
     * 用"到方块中心距离"判定会出现"看着到位、实际差半格"的假到位）。
     */
    private boolean inPickupRange(ItemEntity item) {
        return bot.getBoundingBox().inflate(1.0D, 0.5D, 1.0D).intersects(item.getBoundingBox());
    }

    // ---- 候选与观测 ----

    /** 当前仍在范围、未被淘汰的候选；同时刷新 known/expected/lastSeenStack。 */
    private List<ItemEntity> refreshCandidates() {
        liveById.clear();
        List<ItemEntity> result = new ArrayList<>();
        for (ItemEntity item : scope.liveDrops()) {
            UUID id = item.getUUID();
            if (consumed.contains(id) || retired.contains(id)) {
                continue;
            }
            known.add(id);
            if (firstSeen.add(id)) {
                expectedItems += item.getItem().getCount();
            }
            lastSeenStack.put(id, item.getItem().getCount());
            liveById.put(id, item);
            if (!expectedIds.isEmpty() && !expectedIds.contains(id)) {
                continue;
            }
            if (bot.distanceToSqr(item) > MAX_CHASE_DISTANCE * MAX_CHASE_DISTANCE) {
                retire(id, "too_far");
                liveById.remove(id);
                continue;
            }
            result.add(item);
        }
        return result;
    }

    /** 记录"从已知集合里消失"的实体（被拾取、被合并、或离开世界）。计数不在这里，在簇结束时按背包增量统计。 */
    private void trackDisappearances(List<ItemEntity> live) {
        Set<UUID> liveIds = new LinkedHashSet<>();
        for (ItemEntity item : live) {
            liveIds.add(item.getUUID());
        }
        for (UUID id : known) {
            if (liveIds.contains(id) || consumed.contains(id) || retired.contains(id)) {
                continue;
            }
            consumed.add(id);
            BotLog.info("[CollectDrops] entity_gone item={}", id);
        }
    }

    // ---- 簇 ----

    private void beginCluster(List<ItemEntity> live) {
        ItemEntity seed = live.stream()
                .min(java.util.Comparator.comparingDouble(item -> bot.distanceToSqr(item)))
                .orElse(live.get(0));
        clusterIds = new ArrayList<>();
        Set<UUID> inCluster = new LinkedHashSet<>();
        Deque<ItemEntity> queue = new ArrayDeque<>();
        clusterIds.add(seed.getUUID());
        inCluster.add(seed.getUUID());
        queue.add(seed);
        while (!queue.isEmpty()) {
            ItemEntity current = queue.poll();
            for (ItemEntity other : live) {
                if (inCluster.contains(other.getUUID())) {
                    continue;
                }
                if (linked(current, other)) {
                    inCluster.add(other.getUUID());
                    clusterIds.add(other.getUUID());
                    queue.add(other);
                }
            }
        }
        anchor = seed.blockPosition().immutable();
        clusterStartSum = 0;
        typeBefore = new LinkedHashMap<>();
        for (UUID id : clusterIds) {
            ItemEntity item = liveById.get(id);
            if (item == null) {
                continue;
            }
            clusterStartSum += item.getItem().getCount();
            typeBefore.putIfAbsent(item.getItem().getItem(), countInInventory(item.getItem().getItem()));
        }
        sweepTicks = 0;
        waitTicks = 0;
        reanchors = 0;
        settleTicks = 0;
        runner = null;
        BotLog.info("[CollectDrops] cluster_start anchor={} members={} items={} types={}",
                anchor.toShortString(), clusterIds.size(), clusterStartSum, typeBefore.size());
    }

    /** 是否仍在空中下落（未落地且速度向下）。 */
    /**
     * 物品是否还未落定（仍在空中）。
     *
     * <p>**2026-09-10 第二次修正**：原判据是 `!onGround() && dy < -0.02`，只认"正在下落"，
     * 漏掉了原版掉落物的**上抛阶段**——`Block.popResource` 生成的 `ItemEntity` 带向上初速度，
     * 破块后最初几 tick `dy > 0`，判据为假 → 收集器立刻去追那一格空中位置 →
     * `UNREACHABLE`/`MOVEMENT_FAILED` → 物品被**退役**。
     * 实测代价：4 根原木全砍完，最后一根的掉落物在 `itemY=67.75` 被退役 → `inventoryDelta=3`
     * → 终态 `product_not_collected`（树砍完了却判失败）。
     * 改为"只要还没接地就等它落定"，上抛与下落一并覆盖；已接地但位置很高的物品不受影响，
     * 仍会走正常走位并在确实够不到时如实退休。
     */
    private static boolean isAirborne(ItemEntity item) {
        return !item.onGround();
    }

    private static boolean linked(ItemEntity a, ItemEntity b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.abs(a.getY() - b.getY()) <= CLUSTER_LINK_DY
                && dx * dx + dz * dz <= CLUSTER_LINK_DISTANCE * CLUSTER_LINK_DISTANCE;
    }

    private List<ItemEntity> liveMembers(List<ItemEntity> live) {
        List<ItemEntity> members = new ArrayList<>();
        for (ItemEntity item : live) {
            if (clusterIds.contains(item.getUUID())) {
                members.add(item);
            }
        }
        return members;
    }

    /**
     * 结束一次簇扫描：按**背包增量**计收集数，并用守恒式交叉校验
     * `增量 == 起始 stack 总和 − 结束剩余存活 stack 总和`。
     */
    private void endCluster(boolean timedOut) {
        int delta = 0;
        if (typeBefore != null) {
            for (Map.Entry<Item, Integer> entry : typeBefore.entrySet()) {
                delta += countInInventory(entry.getKey()) - entry.getValue();
            }
        }
        int remaining = 0;
        for (UUID id : clusterIds) {
            ItemEntity item = liveById.get(id);
            if (item != null) {
                remaining += item.getItem().getCount();
            }
        }
        int expectedGain = clusterStartSum - remaining;
        collectedItems += Math.max(0, delta);
        clustersSwept++;
        if (delta != expectedGain) {
            mismatchCount++;
            BotLog.warn("[CollectDrops] MISMATCH anchor={} delta={} expected={} startSum={} remaining={}"
                            + "（同类型被他人拾取/重复生成/背包满/统计漏洞）",
                    anchor == null ? "-" : anchor.toShortString(), delta, expectedGain, clusterStartSum, remaining);
        }
        BotLog.info("[CollectDrops] cluster_done anchor={} members={} delta={} remaining={} ticks={} timeout={}",
                anchor == null ? "-" : anchor.toShortString(), clusterIds.size(), delta, remaining,
                sweepTicks, timedOut);
        clusterIds = null;
        anchor = null;
        typeBefore = null;
        runner = null;
        sweepTicks = 0;
        waitTicks = 0;
        reanchors = 0;
        settleTicks = 0;
    }

    private void retire(UUID id, String reason) {
        if (!retired.add(id)) {
            return;
        }
        if ("pickup_timeout".equals(reason)) {
            pickupTimeoutCount++;
        } else {
            unreachableCount++;
        }
        ItemEntity item = liveById.get(id);
        BotLog.warn("[CollectDrops] retire item={} reason={} itemPos={} itemY={} stack={}"
                        + " botFeet={} botBox={} inRange={}",
                id, reason,
                item == null ? "-" : item.blockPosition().toShortString(),
                item == null ? "-" : String.format(java.util.Locale.ROOT, "%.3f", item.getY()),
                lastSeenStack.getOrDefault(id, 0),
                bot.blockPosition().toShortString(),
                String.format(java.util.Locale.ROOT, "[%.2f..%.2f y %.2f..%.2f z %.2f..%.2f]",
                        bot.getBoundingBox().minX, bot.getBoundingBox().maxX,
                        bot.getBoundingBox().minY, bot.getBoundingBox().maxY,
                        bot.getBoundingBox().minZ, bot.getBoundingBox().maxZ),
                item != null && inPickupRange(item));
    }

    private int countInInventory(Item item) {
        int total = 0;
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
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
        String summary = "reason=" + reason
                + " collected=" + collectedItems + "/" + expectedItems
                + " entities=" + consumed.size() + "/" + known.size()
                + " clusters=" + clustersSwept
                + " unreachable=" + unreachableCount
                + " pickup_timeout=" + pickupTimeoutCount
                + " mismatch=" + mismatchCount
                + " ticks=" + ticks;
        BotLog.info("[CollectDrops] SUMMARY {}", summary);
        return Status.DONE;
    }
}
