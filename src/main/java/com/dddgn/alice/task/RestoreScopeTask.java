package com.dddgn.alice.task;

import com.dddgn.alice.action.WriteGrant;
import com.dddgn.alice.action.WriteReason;
import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.ledger.WorldModLedger;
import com.dddgn.alice.log.BotLog;
import com.dddgn.alice.perception.ScopeBuffer;
import com.dddgn.alice.task.mining.MiningBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 作用域恢复任务（J6-b，D-081 §12「执行恢复」层）：把**我方放置的临时方块**拆掉并销账。
 *
 * <p>三条纪律（缺一条就会伤到别人的世界）：
 * <ol>
 *   <li><b>严格自上而下</b>：按 y 降序拆。垫脚柱必须"站在顶上拆脚下"，否则会留下够不到的悬空块；</li>
 *   <li><b>只拆自己放的</b>：拆前比对账本 {@code placed} 与该格当前方块——不匹配即
 *       **放弃并销账**（记 `not_ours`）。绝不能因为"账本里记着"就去拆玩家后来放的东西；</li>
 *   <li><b>不许挖地形</b>：子任务用 `standableOnly=true`（只允许现成可站站位），
 *       禁止为了够到一块脚手架而挖穿地面。</li>
 * </ol>
 *
 * <p>复用已验收的 L2（{@link MineTask}：走位 → 视线 → 触及 → 破坏）；本任务只做编排与记账。
 * 破坏理由用 {@link WriteReason#SCAFFOLD_RESTORE}（明确目标策略——清障策略会拒 `underfoot_block`，
 * 而拆垫脚柱恰恰就是拆自己脚下那格）。
 *
 * <p>终态：`restore_done`（队列拆完且该作用域已空）/ `restore_partial`（超时或仍有剩余）/
 * `nothing_to_restore`（本来就没有待恢复项）。
 */
public final class RestoreScopeTask implements Task {

    /** 每块恢复的 tick 预算（走位 + 破坏；实测单块破坏 ≤ 10 tick，其余是走位）。 */
    private static final int TICKS_PER_BLOCK = 200;
    /** 基础预算。 */
    private static final int BASE_TICKS = 100;

    private final BotPlayer bot;
    private final ScopeBuffer scope;
    /** 只恢复该作用域；null = 全部待恢复的 TEMP（命令兜底用）。 */
    private final String scopeId;
    /** 总预算：建好队列后按实际块数算（`BASE + 每块预算 × 块数`）。 */
    private int maxTicks;

    private List<BlockPos> queue = new ArrayList<>();
    private boolean queueBuilt;
    private int index;
    private int ticks;
    private int restored;
    private int skipped;
    private final List<String> notes = new ArrayList<>();

    private MineTask miner;
    private BlockPos current;
    private String terminalReason = "";
    private String failure = "";
    private boolean terminated;

    public RestoreScopeTask(BotPlayer bot, ScopeBuffer scope, String scopeId) {
        this.bot = bot;
        this.scope = scope;
        this.scopeId = scopeId;
        this.maxTicks = BASE_TICKS;   // 建好队列后按实际块数收紧（见 buildQueue）
    }

    @Override
    public String taskName() {
        return "RestoreScope";
    }

    /** 终态理由（`Task` 接口没有这个约定，`Job` 才有；这里为日志/命令可读性保留）。 */
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
            BotLog.info("[Restore] start scope={} blocks={} （自上而下）",
                    scopeId == null ? "<all>" : scopeId, queue.size());
        }
        if (++ticks > maxTicks) {
            terminalReason = "restore_partial";
            failure = terminalReason + "（超时，仍有 " + (queue.size() - index) + " 块未处理）";
            return finish(Task.Status.FAILED);
        }
        if (miner != null) {
            Task.Status status = miner.tick();
            if (status == Task.Status.RUNNING) {
                return Task.Status.RUNNING;
            }
            finishBlock(status == Task.Status.DONE);
            return Task.Status.RUNNING;
        }
        return pickNext();
    }

    // ==================== 编排 ====================

    private void buildQueue() {
        queueBuilt = true;
        ServerLevel level = bot.serverLevel();
        List<WorldModLedger.Entry> pending = WorldModLedger.pendingTemporary(level.getServer(), scopeId);
        List<BlockPos> positions = new ArrayList<>();
        for (WorldModLedger.Entry entry : pending) {
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
            BotLog.info("[Restore] block {} placed={} policy={} scope={}",
                    pos.toShortString(), entry.placed(), entry.policy(), entry.scopeId());
            miner = new MineTask(bot, pos, scope,
                    MiningBudget.forTarget(bot, level, pos, true),
                    true,   // standableOnly：恢复**不许挖地形**
                    WriteGrant.of(taskName(), WriteReason.SCAFFOLD_RESTORE));
            return Task.Status.RUNNING;
        }
        terminalReason = (index >= queue.size() && skipped == 0) ? "restore_done" : "restore_partial";
        if (!notes.isEmpty()) {
            BotLog.warn("[Restore] 未能恢复: {}", String.join(" | ", notes));
        }
        Task.Status status = skipped == 0 ? Task.Status.DONE : Task.Status.FAILED;
        if (status == Task.Status.FAILED) {
            failure = "restore_partial skipped=" + skipped;
        }
        return finish(status);
    }

    private void finishBlock(boolean ok) {
        ServerLevel level = bot.serverLevel();
        BlockPos pos = current;
        miner = null;
        current = null;
        if (ok) {
            WorldModLedger.forget(level, pos);
            restored++;
        } else {
            skipped++;
            notes.add(pos.toShortString() + ":restore_failed");
        }
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
