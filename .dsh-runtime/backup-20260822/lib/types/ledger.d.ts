/**
 * The task ledger: durable storage plus the A2A-aligned lifecycle machine.
 *
 * The ledger is the authority on intent and outcome, never on execution. The
 * agent inbox owns execution, and the two drift by design: an interrupt keeps
 * unclaimed queue items but does not requeue one already claimed into the
 * interrupted turn, and disposal discards every unclaimed item. So a row in
 * `submitted` or `working` records what was asked, not a promise that it will
 * finish. Two of those drifts are observable through inbox lifecycle events;
 * the third — a claimed message whose step is rejected, which emits neither
 * `discarded` nor any execution — is why `working` needs a timeout sweep
 * rather than pure event tracking.
 *
 * Domain reads are synchronous from the authoritative in-memory state; only
 * writes queue on the domain's own write chain.
 *
 * @module dsh-agent-bus/ledger
 */
import type { Context } from '@deepseek-ai/cordis';
import type { SessionId } from '@deepseek-ai/dsh-session';
import { type StoredPendingMessage, type StoredTaskRecord } from './spec.ts';
import { TaskId, type DeliveryMode, type FlowRecord, type HandoffEntry, type PeerCard, type TaskOutcome, type TaskRecord, type TaskStatus, type TokenBuckets } from './types.ts';
/**
 * Report whether one lifecycle transition is permitted.
 *
 * @param from - the current status.
 * @param to - the proposed status.
 * @returns `true` when the transition is part of the state machine.
 */
export declare function canTransition(from: TaskStatus, to: TaskStatus): boolean;
/**
 * Report whether a status admits no further transition.
 *
 * @param status - the status to test.
 * @returns `true` when the status is terminal.
 */
export declare function isTerminal(status: TaskStatus): boolean;
/** Fields a caller supplies when recording a new task. */
export interface NewTask {
    /** Ledger identity, pre-generated so the delivery message can reference it. */
    readonly id: TaskId;
    readonly assignedBy: SessionId;
    readonly assignedTo: SessionId;
    /** Reviewer, defaults to the initiator when absent. */
    readonly assignedReviewer?: SessionId;
    readonly workspacePath: string;
    readonly content: string;
    /** Short display title (REQUIRED); list/DAG nodes display it. */
    readonly title: string;
    readonly mode: DeliveryMode;
    /**
     * Harness message identity, recorded before delivery so the claimed
     * listener can find the row. Absent for a blocked DAG task: it is created
     * but not delivered until its dependencies settle.
     */
    readonly messageId?: string;
    /** Rework count; starts at 0 for a fresh task. */
    readonly retries: number;
    /**
     * Dispatch-time token totals per participant session (deduplicated staff).
     * Optional: an offline session or an absent projection registry simply
     * leaves its key out, and the panel shows the delta as unavailable.
     */
    readonly tokensAtStart?: Record<string, TokenBuckets>;
    /**
     * DAG predecessors. When non-empty and any predecessor is unsettled, the
     * task is created as `queued` (待投递) WITHOUT delivery; the event-driven
     * scheduler dispatches it once every predecessor settles (v1.4).
     */
    readonly dependencies?: readonly TaskId[];
    /**
     * The dispatcher's minimum acceptance requirement; the reviewer settles
     * against it (v1.4).
     */
    readonly acceptanceCriteria?: string;
    /**
     * Owning flow id (v1.4). When set, every dependency must belong to the
     * same flow — a flow is a self-contained DAG and cross-container
     * references are rejected at write time.
     */
    readonly flowId?: string;
}
/** Maximum number of DAG predecessors one task may declare. */
export declare const MAX_DEPENDENCIES = 16;
/**
 * Whether one row has settled successfully (its DAG precondition).
 *
 * @param row - the row.
 * @returns `true` when the row is settled and the verdict was success.
 */
export declare function isSettledSuccess(row: TaskRecord): boolean;
/**
 * A task's unsatisfied DAG predecessors: dependencies that have not settled
 * successfully. An empty list means the task is ready to dispatch.
 *
 * @param row - the row.
 * @param all - every row (dependency lookup).
 * @returns the ids of still-unsettled dependencies, in declaration order.
 */
export declare function blockedByOf(row: TaskRecord, all: readonly TaskRecord[]): readonly TaskId[];
/**
 * Validate a proposed dependency list against the ledger's DAG invariants.
 *
 * @param taskId - the task declaring the dependencies (self-reference check).
 * @param dependencies - the proposed predecessor list.
 * @param all - every row (existence, workspace, and cycle checks).
 * @param workspacePath - the declaring task's workspace.
 * @returns an error message, or `null` when the list is acceptable.
 */
export declare function validateDependencies(taskId: TaskId, dependencies: readonly TaskId[] | undefined, all: readonly TaskRecord[], workspacePath: string, flowId?: string): string | null;
/**
 * Depth-first cycle detection over a directed edge map.
 *
 * @param edges - node id → outgoing dependency ids.
 * @returns `true` when the graph contains any cycle.
 */
export declare function detectCycle(edges: ReadonlyMap<string, readonly string[]>): boolean;
/** Result of a ledger mutation. */
export type LedgerResult = {
    readonly ok: true;
    readonly task: TaskRecord;
} | {
    readonly ok: false;
    readonly message: string;
};
/**
 * Durable task ledger over one storage domain.
 *
 * Mutations that read-then-write are serialized through one local chain. The
 * domain form offers no cross-table transaction, so ordering those pairs here
 * is what keeps concurrent mutations from overwriting each other.
 */
export declare class TaskLedger {
    private table;
    private peers;
    private flows;
    private pendingNotes;
    private global;
    private chain;
    private ctx;
    /**
     * Open the ledger domain and bind its teardown to the plugin lifetime.
     *
     * Applies the v1.4 shape migration before the backup snapshot: a
     * pre-release `submitted` row without a messageId is the old implicit
     * "blocked, undelivered" form and becomes the explicit `queued` state.
     *
     * @param ctx - the plugin context, which must have `storageDomain` bound.
     * @returns the opened ledger.
     */
    static open(ctx: Context): Promise<TaskLedger>;
    /**
     * v1.4 shape migration: `submitted` without a messageId means "blocked,
     * undelivered" in the pre-v7 row format; the explicit state is `queued`.
     * Runs once at open, idempotently.
     */
    private migrateQueued;
    /**
     * Emit one TaskChanged event for the client event-driven scheduler.
     *
     * Emitted AFTER the durable write completes, so an event consumer never
     * sees state the ledger has not yet recorded.
     *
     * @param taskId - the row that changed.
     * @param from - prior status, or `-` for creation.
     * @param to - new status, or a settlement marker (`settled-success`,
     *   `settled-failure`) or `edited` for a mutation that keeps the status.
     */
    private emitChange;
    /**
     * Write a full content snapshot to the backups dir, kept to the newest 20.
     *
     * Insurance, not runtime data: the ledger itself never deletes rows, but a
     * domain version bump recreates the medium wholesale (see v1.2 spec §2.1 —
     * that is how task e420b249 and the v5-era rows were lost). Every open
     * writes one snapshot so a later loss is at least recoverable from the last
     * boot that saw the rows. Best-effort: a failed snapshot logs and does not
     * fail boot.
     */
    private snapshotBackup;
    /** Serialize one mutation behind every earlier one. */
    private enqueue;
    /**
     * Record a new task.
     *
     * A task with unsettled dependencies is created as `queued` (待投递) and
     * left undelivered — the event-driven scheduler dispatches it once every
     * predecessor settles. A task whose dependencies are already settled is
     * created `submitted` (the caller delivers it immediately). The row is
     * written before any delivery so a delivery failure leaves a durable trace
     * instead of a silent loss.
     *
     * @param task - the create intent.
     * @param maxPending - ceiling on unfinished rows for this recipient.
     * @returns the created row, or a refusal when the recipient's queue is full.
     */
    record(task: NewTask, maxPending: number): Promise<LedgerResult>;
    /**
     * Apply one status transition, rejecting a move the machine forbids.
     *
     * A transition INTO a terminal failure (`failed` / `canceled`) additionally
     * runs DAG failure propagation: every downstream task whose unsettled
     * dependencies are now all terminally failed is failed in turn,
     * recursively — the whole flow is driven by code, no human step.
     *
     * @param id - the row to advance.
     * @param to - the target status.
     * @param patch - fields to merge alongside the status change.
     * @returns the updated row, or a refusal naming the illegal transition.
     */
    transition(id: TaskId, to: TaskStatus, patch?: Partial<Omit<StoredTaskRecord, 'id' | 'status'>>): Promise<LedgerResult>;
    /** Transition core, run inside the write chain (no re-enqueue). */
    private applyTransitionLocked;
    /**
     * DAG failure propagation: after `failedId` reached a terminal failure,
     * fail every downstream task whose unsettled dependencies are all
     * terminally failed, recursively. A downstream with any still-active
     * dependency waits for it instead.
     *
     * @param failedId - the terminal-failed task whose dependents are examined.
     * @param reason - the failure reason stamped on propagated rows
     *   (`dependency-failed` / `dependency-canceled`).
     */
    private propagateFailureLocked;
    /**
     * List the downstream tasks that became dispatchable after one task
     * settled: `queued`, dependent on `id`, and with every dependency settled.
     * The scheduler delivers each returned id exactly once (the id vanishes
     * from this list once it transitions to submitted).
     *
     * @param id - the just-settled task.
     * @returns the ready dependents, in creation order.
     */
    pendingReleases(id: TaskId): Promise<TaskId[]>;
    /**
     * Record the delivery identity of the latest message without changing the
     * status. Used by the input-required answer path: the answer is a new
     * delivery on a paused task, and `transition` cannot move
     * `input-required → input-required`.
     *
     * @param id - the row to update.
     * @param messageId - the harness identity of the delivered answer.
     * @param auto - set when the scheduler (not a tool call) delivered this task.
     * @returns the updated row, or a refusal.
     */
    recordDelivery(id: TaskId, messageId: string, auto?: boolean): Promise<LedgerResult>;
    /**
     * Edit an undispatched task's requirement text and/or DAG predecessors.
     *
     * The topology is program-driven: an agent that finds its DAG unreasonable
     * rewrites it here. Only tasks that were never delivered (`queued` — and,
     * for pre-v7 rows, `submitted` without a messageId) are editable — a
     * delivered task's changes would never reach the worker. Dependency changes
     * run the same validation as creation.
     *
     * @param id - the row to edit.
     * @param patch - `title`, `content`, `dependencies`, `acceptanceCriteria`,
     *   and/or `flowId`; absent fields stay put.
     * @returns the updated row, or a refusal.
     */
    editTask(id: TaskId, patch: {
        readonly title?: string;
        readonly content?: string;
        readonly dependencies?: readonly TaskId[];
        readonly acceptanceCriteria?: string;
        readonly flowId?: string;
    }): Promise<LedgerResult>;
    /**
     * Create one flow: a named DAG container for tasks.
     *
     * @param id - flow identity (uuid).
     * @param name - display name, 1–80 characters.
     * @param description - optional note.
     * @param createdBy - the creating session.
     * @param workspacePath - the workspace the flow lives in.
     * @returns the created flow.
     */
    createFlow(id: string, name: string, description: string | undefined, createdBy: SessionId, workspacePath: string): Promise<FlowRecord>;
    /** List every flow in creation order. */
    listFlows(): FlowRecord[];
    /** Read one flow. */
    getFlow(id: string): FlowRecord | undefined;
    /**
     * Queue one undelivered note (v1.5): the recipient was offline, so the
     * message is held durably and the delivery sweep retries it once the
     * recipient is live.
     *
     * @param message - the pending note record.
     */
    queueNote(message: StoredPendingMessage): Promise<void>;
    /** All queued undelivered notes, oldest first. */
    listPendingNotes(): StoredPendingMessage[];
    /**
     * Remove one delivered (or dropped) note.
     *
     * @param id - the note id.
     */
    deleteNote(id: string): Promise<void>;
    /**
     * Bump one note's delivery attempt count (the sweep retries it).
     *
     * @param id - the note id.
     * @param attempts - the new attempt count.
     */
    markNoteAttempt(id: string, attempts: number): Promise<void>;
    /**
     * Attach one handoff document to a downstream task: a settled
     * predecessor's executor delivers structured context that dispatch
     * concatenates into the downstream's delivered content.
     *
     * @param id - the DOWNSTREAM task receiving the document.
     * @param handoff - the predecessor id, document text, and stamp.
     * @returns the updated row, or a refusal.
     */
    /**
     * Reassign an unsettled task (v1.5): move the executor and/or the reviewer
     * without recreating the task. The task id, history, dependencies, flow
     * membership, and acceptance criteria all stay — the DAG topology never
     * changes, only who works and who reviews.
     *
     * An executor change clears the delivery identity: the caller re-delivers
     * to the new worker (the old worker's report is then rejected by the
     * state machine, since it is no longer assignedTo).
     *
     * @param id - the row to reassign.
     * @param patch - `executor` and/or `reviewer`; absent fields stay put.
     * @returns the updated row, or a refusal.
     */
    reassign(id: TaskId, patch: {
        readonly executor?: SessionId;
        readonly reviewer?: SessionId;
    }): Promise<LedgerResult>;
    appendHandoff(id: TaskId, handoff: HandoffEntry): Promise<LedgerResult>;
    /** Handoff documents attached to one task, in arrival order. */
    handoffsFor(id: TaskId): readonly HandoffEntry[];
    /**
     * Attach a report to a row without changing its status.
     *
     * Used twice: the normal report on the way to `completed` goes through
     * {@link transition} instead; this path serves the cancel-summary, which a
     * worker attaches to a `canceled` task whose state must not move.
     *
     * @param id - the row to attach to.
     * @param report - the report text.
     * @returns the updated row, or a refusal.
     */
    attachReport(id: TaskId, report: string): Promise<LedgerResult>;
    /**
     * Settle a completed task with the reviewer's verdict.
     *
     * Success records the verdict on the row and leaves it `completed` —
     * terminal. Failure sends the row BACK to `submitted` for rework on the
     * SAME task id: the feedback becomes the rework instruction, `retries`
     * increments, and the previous attempt's report and turn are cleared so the
     * next claim starts fresh. One task id carries its whole life across
     * attempts; there is no new-task redo chain.
     *
     * @param id - the completed row to settle.
     * @param outcome - the verdict.
     * @param feedback - reviewer note; on failure this is the rework instruction.
     * @returns the updated row, or a refusal.
     */
    settle(id: TaskId, outcome: TaskOutcome, feedback: string | undefined): Promise<LedgerResult>;
    /**
     * Read one row.
     *
     * @param id - the row to read.
     * @returns the row, or `undefined` when unknown.
     */
    get(id: TaskId): TaskRecord | undefined;
    /**
     * List rows addressed to one session, in creation order.
     *
     * @param sessionId - the recipient to filter by.
     * @returns matching rows.
     */
    listFor(sessionId: SessionId): TaskRecord[];
    /**
     * List rows dispatched by one session, in creation order.
     *
     * @param sessionId - the dispatcher to filter by.
     * @returns matching rows.
     */
    listBy(sessionId: SessionId): TaskRecord[];
    /**
     * List every row in creation order.
     *
     * Order comes from the global `taskIds` account rather than table iteration,
     * which the domain form does not promise to order.
     *
     * @returns every row the ledger holds.
     */
    listAll(): TaskRecord[];
    /**
     * Find the row a delivered message belongs to.
     *
     * Used by inbox lifecycle listeners, which know the `MessageId` but not the
     * ledger id.
     *
     * @param messageId - the harness message identity.
     * @returns the owning row, or `undefined` when the message is not ours.
     */
    findByMessage(messageId: string): TaskRecord | undefined;
    /**
     * Write one peer's card, replacing the previous one wholesale.
     *
     * @param sessionId - the card owner.
     * @param card - the validated card content.
     */
    putCard(sessionId: SessionId, card: PeerCard): Promise<void>;
    /**
     * Read one peer's card.
     *
     * @param sessionId - the card owner.
     * @returns the card, or `undefined` when the peer never wrote one.
     */
    getCard(sessionId: SessionId): PeerCard | undefined;
}
