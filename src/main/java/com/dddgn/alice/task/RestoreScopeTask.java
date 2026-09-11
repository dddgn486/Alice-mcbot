package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.ledger.WorldModLedger;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.pathing.core.search.PathRequest;
import com.dddgn.alice.perception.ScopeBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 作用域恢复任务（J6-b，D-081 §12「执行恢复」层）：把**我方放置的临时方块**拆掉并销账。
 *
 * <p>**机制照设计文档（§12.3）**：`挖脚下 → DOWNWARD 落 1 格 → 重复`。每块两阶段：
 * <ol>
 *   <li><b>APPROACH</b>：走到目标**正上方**站好（{@link PathRequest#scaffoldRemoval}：只拆不建、只允许向下拆脚）；</li>
 *   <li><b>DESCEND</b>：向下走一格——`DOWNWARD` 会破坏脚下那格并让 bot 落进去，成功后销账。</li>
 * </ol>
 * 于是拆一根 n 格垫脚柱 = "落 n 次"，且**天然自上而下**；下一块又正好落到脚下，形成紧凑循环。
 *
 * <p>**为什么不用 `MineTask` 拆**（首测踩到）：`MiningPlanner` 模式 A **显式排除 `target.above()`
 * 作为站位**（那正是 `DOWNWARD` 的语义），所以"用 MineTask 拆脚下那格"**必然规划失败**——
 * 实测 4 块全部 `no_valid_standing_point` / `no_reachable_standing_point`，9 tick 内连败。
 *
 * <p>三条纪律：**严格自上而下**（y 降序）、**只拆自己放的**（拆前比对账本 `placed` 与现场方块，
 * 不匹配即放弃并销账 `not_ours`）、**不许沿途挖地形**（授权集合不含 `BREAK_AND_*`）。
 *
 * <p>**物质闭环（J6-b1b）**：拆下来的方块会变成掉落物——所以恢复任务在开始时**重开作用域**
 * （`ScopeBuffer.begin`，否则掉落物不被登记），处理完后**收尾调一次收集**（`CollectDropsTask`），
 * 并在终态日志里如实报出"一次性方块库存的变化"。这样"建拆同权"不只是把方块从世界里拿掉，
 * 而是**材料也回到背包**——否则每轮都在净消耗，夹具只能靠补料掩盖（D-099 的教训）。
 *
 * <p>终态：`restore_done` / `restore_partial`（超时或仍有剩余，如实失败）/ `nothing_to_restore`。
 */
public final class RestoreScopeTask implements Task {

    /** 每块预算：两段寻路 + 一次破坏。 */
    private static final int TICKS_PER_BLOCK = 300;
    private static final int BASE_TICKS = 100;
    /** 收尾收集的 tick 预算（与 CollectDropsTask 默认一致）。 */
    private static final int COLLECT_BUDGET_TICKS = 600;

    private enum Stage { APPROACH, DESCEND }

    private final BotPlayer bot;
    private final ScopeBuffer scope;
    /** 只恢复该作用域；null = 全部待恢复的 TEMP（命令兜底用）。 */
    private final String scopeId;
    private int maxTicks;

    private List<BlockPos> queue = new ArrayList<>();
    private boolean queueBuilt;
    private int index;
    private int ticks;
    private int restored;
    private int skipped;
    /** 第一个成功恢复的位置（收尾收集的锚点）。 */
    private BlockPos firstRestored;
    private final List<String> notes = new ArrayList<>();

    private BlockPos current;
    private Stage stage = Stage.APPROACH;
    private PathRetryRunner runner;
    /** 收尾收集（J6-b1b）：把拆下来的方块收回背包。 */
    private CollectDropsTask collector;
    private boolean collectStarted;
    /** 开始时的一次性方块库存（用于终态报"收回多少"）。 */
    private int throwawayBefore;
    private boolean scopeOpened;
    private String terminalReason = "";
    private String failure = "";
    private boolean terminated;

    public RestoreScopeTask(BotPlayer bot, ScopeBuffer scope, String scopeId) {
        this.bot = bot;
        this.scope = scope;
        this.scopeId = scopeId;
        this.maxTicks = BASE_TICKS;
    }

    @Override
    public String taskName() {
        return "RestoreScope";
    }

    /** 终态理由（`Task` 接口无此约定，`Job` 才有；此处为日志/命令可读性保留）。 */
    public String terminalReason() {
        return terminalReason;
    }

    @Override
    public TaskTarget target() {
        BlockPos pos = current != null ? current : (queue.isEmpty() ? bot.blockPosition() : queue.get(0));
        return TaskTarget.block(pos);
    }

    @Override
    public String failureReason() {
        return failure;
    }

    @Override
    public Task.Status tick() {
        if (terminated) {
            return Task.Status.DONE;
        }
        if (!queueBuilt) {
            buildQueue();
            if (queue.isEmpty()) {
                terminalReason = "nothing_to_restore";
                return finish(Task.Status.DONE);
            }
            BotLog.info("[Restore] start scope={} blocks={} （自上而下：站上去 → 向下拆）",
                    scopeId == null ? "<all>" : scopeId, queue.size());
        }
        if (++ticks > maxTicks) {
            terminalReason = "restore_partial";
            failure = terminalReason + "（超时，仍有 " + (queue.size() - index) + " 块未处理）";
            return finish(Task.Status.FAILED);
        }
        if (collector != null) {
            return tickCollector();
        }
        if (runner != null) {
            return tickRunner();
        }
        return pickNext();
    }

    // ==================== 编排 ====================

    private void buildQueue() {
        queueBuilt = true;
        ServerLevel level = bot.serverLevel();
        List<BlockPos> positions = new ArrayList<>();
        for (WorldModLedger.Entry entry : WorldModLedger.pendingTemporary(level.getServer(), scopeId)) {
            positions.add(entry.pos());
        }
        // **严格自上而下**：y 降序；同 y 按 x/z 稳定排序
        Comparator<BlockPos> topDown = Comparator.<BlockPos>comparingInt(BlockPos::getY).reversed()
                .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ);
        positions.sort(topDown);
        queue = positions;
        maxTicks = BASE_TICKS + TICKS_PER_BLOCK * Math.max(1, queue.size()) + COLLECT_BUDGET_TICKS;
        if (positions.isEmpty()) {
            return;   // 没有待恢复项 → 不开关作用域（免得影响调用方的掉落物登记）
        }
        // **物质闭环**：重开作用域，否则 DOWNWARD 拆下来的掉落物不会被登记（收集阶段就找不到）
        throwawayBefore = countThrowaway();
        BlockPos center = centroid(positions);
        int radius = spreadRadius(positions, center);
        scope.begin(center, radius, bot.getUUID());
        scopeOpened = true;
        BotLog.info("[Restore] 作用域已开启 center={} radius={}（用于回收拆下的方块）",
                center.toShortString(), radius);
    }

    /** 队列的几何中心（作用域中心）。 */
    private static BlockPos centroid(List<BlockPos> positions) {
        long x = 0, y = 0, z = 0;
        for (BlockPos pos : positions) {
            x += pos.getX();
            y += pos.getY();
            z += pos.getZ();
        }
        int n = Math.max(1, positions.size());
        return new BlockPos((int) (x / n), (int) (y / n), (int) (z / n));
    }

    /** 覆盖全部目标的作用域半径（至少 8 格）。 */
    private static int spreadRadius(List<BlockPos> positions, BlockPos center) {
        int max = 0;
        for (BlockPos pos : positions) {
            max = Math.max(max, (int) Math.ceil(Math.sqrt(center.distSqr(pos))));
        }
        return Math.max(8, max + 4);
    }

    /** 背包里"一次性方块"总数（= 会被恢复任务收回的那类材料）。 */
    private int countThrowaway() {
        int total = 0;
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem
                    && blockItem.getBlock().defaultBlockState()
                            .is(com.dddgn.alice.action.BlockInteraction.THROWAWAY)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private Task.Status pickNext() {
        ServerLevel level = bot.serverLevel();
        while (index < queue.size()) {
            BlockPos pos = queue.get(index++);
            WorldModLedger.Entry entry = WorldModLedger.at(level.getServer(), pos);
            if (entry == null) {
                continue;   // 已被别的路径销账
            }
            BlockState now = level.getBlockState(pos);
            String nowId = String.valueOf(
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(now.getBlock()));
            if (!nowId.equals(entry.placed())) {
                // **绝不拆不是自己放的方块**：账本记的是 X，现场是别的 → 放弃并销账
                WorldModLedger.forget(level, pos);
                skipped++;
                notes.add(pos.toShortString() + ":not_ours(" + entry.placed() + "→" + nowId + ")");
                BotLog.warn("[Restore] 跳过 {}：账本记的是 {}，现场是 {}（非我方放置）",
                        pos.toShortString(), entry.placed(), nowId);
                continue;
            }
            current = pos;
            stage = Stage.APPROACH;
            BotLog.info("[Restore] block {} placed={} policy={} scope={}",
                    pos.toShortString(), entry.placed(), entry.policy(), entry.scopeId());
            return startStage(pos.above());
        }
        terminalReason = skipped == 0 ? "restore_done" : "restore_partial";
        if (!notes.isEmpty()) {
            BotLog.warn("[Restore] 未能恢复: {}", String.join(" | ", notes));
        }
        if (restored > 0 && !collectStarted) {
            return startCollect();
        }
        if (skipped > 0) {
            failure = "restore_partial skipped=" + skipped;
            return finish(Task.Status.FAILED);
        }
        return finish(Task.Status.DONE);
    }

    /** 收尾收集（J6-b1b）：把拆下来的方块收回背包——物质闭环。 */
    private Task.Status startCollect() {
        collectStarted = true;
        BlockPos origin = firstRestored != null ? firstRestored : bot.blockPosition();
        collector = new CollectDropsTask(bot, origin, scope, List.of(), false, COLLECT_BUDGET_TICKS);
        BotLog.info("[Restore] 开始回收材料 origin={}（restored={}）", origin.toShortString(), restored);
        return Task.Status.RUNNING;
    }

    private Task.Status tickCollector() {
        Task.Status status = collector.tick();
        if (status == Task.Status.RUNNING) {
            return Task.Status.RUNNING;
        }
        collector = null;
        int remainingDrops = scope.liveDrops().size();
        if (remainingDrops > 0) {
            notes.add("drops_left=" + remainingDrops);
            BotLog.warn("[Restore] 仍有 {} 个掉落物没收回（可能落在够不到的地方）", remainingDrops);
        }
        if (skipped > 0) {
            failure = "restore_partial skipped=" + skipped;
            return finish(Task.Status.FAILED);
        }
        return finish(Task.Status.DONE);
    }

    /** 启动一段寻路：`goal` 是脚位。 */
    private Task.Status startStage(BlockPos goal) {
        PathRequest request = PathRequest.scaffoldRemoval(bot.getUUID().toString(),
                bot.blockPosition(), goal);
        runner = new PathRetryRunner(bot, request, PathRetryRunner.DEFAULT_MAX_REPLANS,
                "restore-" + current.getX() + "_" + current.getY() + "_" + current.getZ());
        return Task.Status.RUNNING;
    }

    private Task.Status tickRunner() {
        PathRetryRunner.State state = runner.tick();
        if (state == PathRetryRunner.State.RUNNING) {
            return Task.Status.RUNNING;
        }
        if (state == PathRetryRunner.State.DONE) {
            runner = null;
            if (stage == Stage.APPROACH) {
                // 已站到目标正上方 → 向下走一格（DOWNWARD 拆掉它并让 bot 落进去）
                stage = Stage.DESCEND;
                return startStage(current);
            }
            // DESCEND 完成：脚下那格已拆掉且 bot 落进去了 → 销账
            WorldModLedger.forget(bot.serverLevel(), current);
            restored++;
            if (firstRestored == null) {
                firstRestored = current;
            }
            current = null;
            return Task.Status.RUNNING;
        }
        // 该段失败 → 如实记账并换下一块（不重试，避免空转）
        String detail = stage == Stage.APPROACH ? "approach_failed" : "descend_failed";
        notes.add(current.toShortString() + ":" + detail);
        skipped++;
        BotLog.warn("[Restore] 恢复失败 {} stage={}（不挖地形，如实记录）",
                current.toShortString(), stage);
        runner = null;
        current = null;
        return Task.Status.RUNNING;
    }

    private Task.Status finish(Task.Status status) {
        terminated = true;
        bot.controller().stopMovement();
        if (scopeOpened) {
            scope.end();   // 本任务自己开的作用域，自己关（避免污染下一个任务的掉落物登记）
            scopeOpened = false;
        }
        int remaining = WorldModLedger.pendingTemporary(bot.serverLevel().getServer(), scopeId).size();
        int recovered = countThrowaway() - throwawayBefore;
        BotLog.info("[Restore] SUMMARY scope={} restored={} skipped={} remaining={} recovered={}"
                        + "（一次性方块库存变化）ticks={} reason={} → {}",
                scopeId == null ? "<all>" : scopeId, restored, skipped, remaining, recovered, ticks,
                terminalReason, status);
        return status;
    }
}
