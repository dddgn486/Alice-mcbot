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
 * <p>终态：`restore_done` / `restore_partial`（超时或仍有剩余，如实失败）/ `nothing_to_restore`。
 */
public final class RestoreScopeTask implements Task {

    /** 每块预算：两段寻路 + 一次破坏。 */
    private static final int TICKS_PER_BLOCK = 300;
    private static final int BASE_TICKS = 100;

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
    private final List<String> notes = new ArrayList<>();

    private BlockPos current;
    private Stage stage = Stage.APPROACH;
    private PathRetryRunner runner;
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
        maxTicks = BASE_TICKS + TICKS_PER_BLOCK * Math.max(1, queue.size());
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
        int remaining = WorldModLedger.pendingTemporary(bot.serverLevel().getServer(), scopeId).size();
        BotLog.info("[Restore] SUMMARY scope={} restored={} skipped={} remaining={} ticks={} reason={} → {}",
                scopeId == null ? "<all>" : scopeId, restored, skipped, remaining, ticks,
                terminalReason, status);
        return status;
    }
}
