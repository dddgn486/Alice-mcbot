package com.dddgn.alice.task;

import com.dddgn.alice.bot.BotPlayer;
import com.dddgn.alice.pathing.PathExecutor;
import com.dddgn.alice.pathing.SurfacePathfinder;
import com.dddgn.alice.transfer.ChestBotTransferPrimitive;
import com.dddgn.alice.transfer.TransferCodes;
import com.dddgn.alice.transfer.TransferLedgerData;
import com.dddgn.alice.transfer.TransferRequest;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Narrow single-request orchestration. It consumes only existing HARD_PATH planning/execution. */
public final class TransferTask implements Task {
    private static final int PHASE_NO_PROGRESS = 200;
    private static final int ACTIVE_DEADLINE = 2400;
    private final BotPlayer bot;
    private final TransferRequest request;
    private final TransferLedgerData ledger;
    private final ServerLevel level;
    private Phase phase = Phase.TO_SOURCE;
    private PathExecutor movement;
    private long started;
    private long phaseStarted;
    private String failure = "";
    private boolean completed;
    private enum Phase { TO_SOURCE, SOURCE_WRITE, TO_DESTINATION, DESTINATION_WRITE }

    public TransferTask(BotPlayer bot, TransferRequest request, TransferLedgerData ledger) {
        this.bot = bot; this.request = request; this.ledger = ledger; this.level = bot.serverLevel();
        started = level.getGameTime(); phaseStarted = started;
        transition(TransferLedgerData.State.MOVE_TO_SOURCE, TransferLedgerData.Location.NOT_MOVED, "", false);
    }
    @Override public TaskTarget target() { return TaskTarget.block(phase == Phase.TO_DESTINATION || phase == Phase.DESTINATION_WRITE ? request.destination().position() : request.source().position()); }
    @Override public String failureReason() { return failure; }
    @Override public Status tick() {
        TransferLedgerData.Entry entry = ledger.find(request.requestId()).orElse(null);
        if (entry != null && entry.state() == TransferLedgerData.State.ABORTED) { failure = "aborted"; return Status.FAILED; }
        long now = level.getGameTime();
        if (now - started > ACTIVE_DEADLINE) return suspend(TransferCodes.TIMEOUT, TransferLedgerData.Location.NOT_MOVED);
        if (now - phaseStarted > PHASE_NO_PROGRESS) return suspend(TransferCodes.TIMEOUT, phase == Phase.TO_DESTINATION || phase == Phase.DESTINATION_WRITE ? TransferLedgerData.Location.BOT_INVENTORY : TransferLedgerData.Location.NOT_MOVED);
        return switch (phase) {
            case TO_SOURCE -> move(request.source().position().above(), TransferLedgerData.State.MOVE_TO_SOURCE, false);
            case SOURCE_WRITE -> sourceWrite();
            case TO_DESTINATION -> move(request.destination().position().above(), TransferLedgerData.State.MOVE_TO_DESTINATION, true);
            case DESTINATION_WRITE -> destinationWrite();
        };
    }
    private Status move(BlockPos goal, TransferLedgerData.State state, boolean inTransit) {
        if (movement == null) {
            SurfacePathfinder.Result path = SurfacePathfinder.find(level, bot.blockPosition(), goal);
            if (!path.reachable()) return suspend(path.inconclusive() ? TransferCodes.HARD_PATH_SEARCH_LIMIT : TransferCodes.HARD_PATH_UNREACHABLE,
                    inTransit ? TransferLedgerData.Location.BOT_INVENTORY : TransferLedgerData.Location.NOT_MOVED);
            movement = new PathExecutor(bot, path.path()); transition(state, inTransit ? TransferLedgerData.Location.BOT_INVENTORY : TransferLedgerData.Location.NOT_MOVED, "", false);
        }
        PathExecutor.Status status = movement.tick();
        if (status == PathExecutor.Status.MOVING) return Status.RUNNING;
        if (status == PathExecutor.Status.FAILED) return suspend(TransferCodes.HARD_PATH_FAILED, inTransit ? TransferLedgerData.Location.BOT_INVENTORY : TransferLedgerData.Location.NOT_MOVED);
        phase = inTransit ? Phase.DESTINATION_WRITE : Phase.SOURCE_WRITE; movement = null; phaseStarted = level.getGameTime(); return Status.RUNNING;
    }
    private Status sourceWrite() {
        transition(TransferLedgerData.State.SOURCE_LEG_PRE, TransferLedgerData.Location.NOT_MOVED, "", false);
        ChestBotTransferPrimitive.Result result = ChestBotTransferPrimitive.sourceChestToBot(level, request, bot.getInventory());
        if (!result.proven()) return result.unknownDiscrepancy() ? unknown(result) : failNotMoved(result);
        transition(TransferLedgerData.State.IN_TRANSIT_BOT, TransferLedgerData.Location.BOT_INVENTORY, result.code() + evidence(result), false);
        phase = Phase.TO_DESTINATION; phaseStarted = level.getGameTime(); return Status.RUNNING;
    }
    private Status destinationWrite() {
        transition(TransferLedgerData.State.DESTINATION_LEG_PRE, TransferLedgerData.Location.BOT_INVENTORY, "", false);
        ChestBotTransferPrimitive.Result result = ChestBotTransferPrimitive.botToDestinationChest(level, request, bot.getInventory());
        if (!result.proven()) return result.unknownDiscrepancy() ? unknown(result) : suspend(result.code(), TransferLedgerData.Location.BOT_INVENTORY);
        transition(TransferLedgerData.State.VERIFIED, TransferLedgerData.Location.DESTINATION_CHEST, result.code() + evidence(result), false);
        completed = true; return Status.DONE;
    }
    private Status failNotMoved(ChestBotTransferPrimitive.Result result) { failure = result.code(); transition(TransferLedgerData.State.FAILED_NOT_MOVED, TransferLedgerData.Location.NOT_MOVED, failure + evidence(result), false); return Status.FAILED; }
    private Status unknown(ChestBotTransferPrimitive.Result result) { failure = TransferCodes.UNKNOWN_DISCREPANCY; transition(TransferLedgerData.State.UNKNOWN_DISCREPANCY, TransferLedgerData.Location.UNKNOWN, failure + evidence(result), true); return Status.FAILED; }
    private Status suspend(String code, TransferLedgerData.Location location) { failure = code; transition(TransferLedgerData.State.SUSPENDED, location, code, true); return Status.FAILED; }
    public void survivalInterrupted(String code) { if (!completed) transition(TransferLedgerData.State.SUSPENDED, phase == Phase.TO_DESTINATION || phase == Phase.DESTINATION_WRITE ? TransferLedgerData.Location.BOT_INVENTORY : TransferLedgerData.Location.NOT_MOVED, code, true); }
    public void botRemoved() { if (!completed) transition(TransferLedgerData.State.UNKNOWN_DISCREPANCY, TransferLedgerData.Location.UNKNOWN, TransferCodes.UNKNOWN_DISCREPANCY, true); }
    public TransferRequest request() { return request; }
    private void transition(TransferLedgerData.State state, TransferLedgerData.Location location, String code, boolean manual) { ledger.transition(request.requestId(), state, location, code, level.getGameTime(), state + ":" + location + ":" + level.getGameTime(), manual); }
    private static String evidence(ChestBotTransferPrimitive.Result r) { return " source=" + r.sourceDelta() + " bot=" + r.botDelta() + " destination=" + r.destinationDelta(); }
}
