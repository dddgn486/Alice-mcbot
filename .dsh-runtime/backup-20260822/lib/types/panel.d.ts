/**
 * Panel snapshot builder for the v1.1 task panel route.
 *
 * Serves the browser floater one whole workspace-scoped snapshot per poll:
 * the workspace directory, the session directory (with live flags), every
 * task projected to the panel's read view, and status counters. All inputs
 * come through `ctx.get` so the snapshot degrades — never throws — when a
 * service the Web profile mounts is absent.
 *
 * Token figures are the panel's only non-ledger data. A session's global
 * usage is dsh's own (token-meter projection, shown by the native UI); this
 * module only computes the task-period delta (`tokensAtStart` snapshot taken
 * at dispatch, see tools.ts) and its sum. Staff rows whose current projection
 * or starting snapshot is unavailable carry `null` and the sum is partial.
 *
 * @module dsh-agent-bus/panel
 */
import type { Context } from '@deepseek-ai/cordis';
import type { Agent } from '@deepseek-ai/dsh-agent';
import type { Session } from '@deepseek-ai/dsh-session';
import type { ReportStore } from './external.ts';
import { type TaskLedger } from './ledger.ts';
import type { TaskRecord, TokenBuckets } from './types.ts';
export type { TokenBuckets } from './types.ts';
/** Whether a value has the token-meter projection's bucket shape. */
export declare function isTokenBuckets(value: unknown): value is TokenBuckets;
/** One workspace entry in the snapshot directory. */
export interface WorkspaceView {
    readonly id: string;
    readonly title: string;
    readonly path: string;
}
/** One session in the snapshot directory. */
export interface SessionView {
    readonly id: string;
    readonly title: string;
    readonly workspaceId: string | null;
    readonly live: boolean;
    /** Whether the session was archived in the workspace registry. */
    readonly archived: boolean;
}
/** One participant on a task card's staff directory. */
export interface StaffEntry {
    readonly sessionId: string;
    readonly title: string;
    readonly role: 'initiator' | 'executor' | 'reviewer';
    readonly live: boolean;
    /** Task-period token delta; unavailable projection or start snapshot → null. */
    readonly tokensInTask: TokenBuckets | null;
}
/** One task row projected for the panel. Report text is never included. */
export interface TaskView {
    readonly id: string;
    readonly workspacePath: string;
    readonly status: TaskRecord['status'];
    readonly settled: boolean;
    readonly content: string;
    readonly title: string | null;
    readonly contentPreview: string;
    readonly mode: TaskRecord['mode'];
    readonly assignedBy: string;
    readonly assignedTo: string | null;
    readonly assignedReviewer: string | null;
    readonly byTitle: string;
    readonly toTitle: string | null;
    readonly reviewerTitle: string | null;
    readonly retries: number;
    readonly reason: string | null;
    readonly outcome: TaskRecord['outcome'] | null;
    readonly feedback: string | null;
    readonly question: string | null;
    readonly reportZone: 'inline' | 'hot' | 'cold' | 'missing' | null;
    readonly hasReportRef: boolean;
    readonly turn: number | null;
    readonly staff: readonly StaffEntry[];
    readonly taskTokensTotal: TokenBuckets | null;
    /** Whether the executor (assignedTo) is live; the authoritative tab-partition key. */
    readonly executorLive: boolean;
    /**
     * Whether the task reached the lifecycle's archive phase: settled and
     * settled more than {@link ARCHIVE_AGE_MS} ago. Host-derived so the panel
     * never computes the age client-side.
     */
    readonly archived: boolean;
    /** DAG predecessors (task ids), in declaration order; empty when none. */
    readonly dependencies: readonly string[];
    /** The dispatcher's minimum acceptance requirement (v1.4); null when unset. */
    readonly acceptanceCriteria: string | null;
    /** Owning flow id (v1.4); null when the task belongs to no flow. */
    readonly flowId: string | null;
    /** Tasks that depend on this one (reverse edges for the DAG view). */
    readonly dependents: readonly string[];
    /** Unsettled dependencies; empty means the task is ready to dispatch. */
    readonly blockedBy: readonly string[];
    /** Whether the scheduler (not a tool call) delivered this task. */
    readonly auto: boolean;
    readonly createdAt: string;
    readonly updatedAt: string;
    readonly ageMs: number;
    readonly updatedMs: number;
}
/**
 * How long a settled task stays in the active tab before the lifecycle's
 * archive phase takes it over (24 hours).
 */
export declare const ARCHIVE_AGE_MS: number;
/** Status counters, mirroring the client panel-model keys. */
export interface PanelStats {
    readonly queued: number;
    readonly submitted: number;
    readonly working: number;
    readonly 'input-required': number;
    readonly completed: number;
    readonly failed: number;
    readonly canceled: number;
    readonly total: number;
}
/** One flow in the snapshot directory (v1.4): a named DAG container. */
export interface FlowView {
    readonly id: string;
    readonly name: string;
    readonly description: string | null;
    readonly workspacePath: string;
    readonly taskCount: number;
    /** Tasks still in the active set (not archived). */
    readonly unsettledCount: number;
    /** Derived: every task in the flow has archived. */
    readonly archived: boolean;
}
/** The full document served by GET /plugins/dsh-agent-bus/state. */
export interface PanelSnapshot {
    readonly workspaces: readonly WorkspaceView[];
    /** Flow directory for the DAG view; archived flows follow the derived rule. */
    readonly flows: readonly FlowView[];
    readonly sessions: readonly SessionView[];
    readonly tasks: readonly TaskView[];
    readonly stats: PanelStats;
}
/** Structural face of the projection registry (Service, optional at runtime). */
interface ProjectionRegistryLike {
    snapshot(session: Session): {
        values: Record<string, unknown>;
    };
}
/** Structural face of the agent registry (already injected as `agents`). */
interface AgentRegistryLike {
    get(id: string): Agent | undefined;
}
/**
 * Truncate by Unicode code point so a surrogate pair (emoji) is never split;
 * overflow is marked with a single ellipsis. Mirrors the client model.
 *
 * @param text - the text to truncate.
 * @param max - maximum code points before the ellipsis.
 * @returns the truncated text.
 */
export declare function truncateCodePoints(text: string, max: number): string;
/**
 * Locate an externalized report in the two-zone store.
 *
 * @param reports - the report store.
 * @param task - the row whose report zone is asked for.
 * @returns `'inline'` when the report rides the row, `'hot'` / `'cold'` when
 *   the file exists in the matching zone, `'missing'` when the reference
 *   names a file in neither zone, and `null` when the task has no report.
 */
export declare function detectReportZone(reports: ReportStore, task: TaskRecord): Promise<TaskView['reportZone']>;
/** One role of the staff directory, in display order. */
type StaffRole = 'executor' | 'reviewer' | 'initiator';
/** Staff assembly input: session id plus the role it plays. */
interface RoleSlot {
    readonly sessionId: string;
    readonly role: StaffRole;
}
/**
 * The staff of one task from its three role holders: executor, reviewer,
 * initiator — deduplicated by session id (the initiator reviewing its own
 * task appears once, as executor or reviewer), fixed order executor →
 * reviewer → initiator. The reviewer defaults to the initiator.
 *
 * @param initiator - the dispatching session.
 * @param executor - the worker; may be absent until dispatched.
 * @param reviewer - the settling session; `undefined` falls back to the initiator.
 * @returns the role slots in display order.
 */
export declare function staffRoles(initiator: string | undefined, executor: string | undefined, reviewer: string | undefined): readonly RoleSlot[];
/**
 * The staff of one ledger row (see {@link staffRoles}).
 *
 * @param task - the row.
 * @returns the role slots in display order.
 */
export declare function staffRolesOf(task: TaskRecord): readonly RoleSlot[];
/**
 * The task-period token delta for one session: current projection minus the
 * dispatch-time snapshot, clamped at zero. Either side unavailable → null.
 *
 * @param projections - the projection registry, or `undefined` when absent.
 * @param agents - the agent registry (live sessions only).
 * @param task - the row holding the `tokensAtStart` snapshot.
 * @param sessionId - the staff session.
 * @returns the delta buckets, or `null` when it cannot be computed.
 */
export declare function tokenDeltaOf(projections: ProjectionRegistryLike | undefined, agents: AgentRegistryLike | undefined, task: TaskRecord, sessionId: string): TokenBuckets | null;
/**
 * Build the panel's task view for one row: projection plus zone and staff.
 * Exported for unit tests with stub dependencies.
 *
 * @param task - the ledger row.
 * @param titles - session id → title.
 * @param agents - agent registry for live flags and projections.
 * @param projections - projection registry for token deltas.
 * @param reports - the two-zone report store (zone detection).
 * @param now - snapshot clock (ms since epoch).
 * @returns the projected row.
 */
export declare function buildTaskView(task: TaskRecord, titles: ReadonlyMap<string, string>, agents: AgentRegistryLike | undefined, projections: ProjectionRegistryLike | undefined, reports: ReportStore, now: number): Promise<TaskView>;
/**
 * Assemble the full snapshot: workspace directory, session directory, all
 * tasks, and counters. Any missing service degrades to empty arrays / nulls.
 *
 * @param ctx - the plugin context (services read via `ctx.get`).
 * @param ledger - the task ledger.
 * @param reports - the two-zone report store.
 * @param now - snapshot clock (ms since epoch); defaults to the current time.
 * @returns the snapshot document.
 */
export declare function buildPanelSnapshot(ctx: Context, ledger: TaskLedger, reports: ReportStore, now?: number): Promise<PanelSnapshot>;
