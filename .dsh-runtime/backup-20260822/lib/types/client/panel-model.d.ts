/**
 * Pure view-model helpers for the v1.1 read-only task panel.
 *
 * Snapshot shapes match docs/v1.1-task-panel-spec.md §3.6–3.7 / §4.4.
 * No I/O, no React — every export is unit-tested from tests/panel-model.test.ts.
 *
 * @module dsh-agent-bus/client/panel-model
 */
/** Task vocabulary, mirrored so the client never imports the host module. */
export type TaskStatus = 'queued' | 'submitted' | 'working' | 'input-required' | 'auth-required' | 'completed' | 'failed' | 'canceled' | 'rejected' | 'archived';
/** Delivery mode requested for a task. */
export type DeliveryMode = 'followup' | 'steer';
/** Dispatcher verdict on a completed task. */
export type TaskOutcome = 'success' | 'failure';
/** Four-bucket token usage, same shape as the host TokenBuckets. */
export interface TokenBuckets {
    readonly uncachedInputTokens: number;
    readonly outputTokens: number;
    readonly cacheReadTokens: number;
    readonly cacheWriteTokens: number;
}
/** One participant on a task card's staff directory. */
export interface StaffEntry {
    readonly sessionId: string;
    readonly title: string;
    readonly role: 'initiator' | 'executor' | 'reviewer';
    readonly live: boolean;
    readonly tokensInTask: TokenBuckets | null;
}
/** One task row as projected by GET /plugins/dsh-agent-bus/state. */
export interface TaskView {
    readonly id: string;
    readonly workspacePath: string;
    readonly status: TaskStatus;
    readonly settled: boolean;
    readonly content: string;
    /** Short display title (v1.6); null falls back to the content preview. */
    readonly title: string | null;
    readonly contentPreview: string;
    readonly mode: DeliveryMode;
    readonly assignedBy: string;
    readonly assignedTo: string | null;
    readonly assignedReviewer: string | null;
    readonly byTitle: string;
    readonly toTitle: string | null;
    readonly reviewerTitle: string | null;
    readonly retries: number;
    readonly reason: string | null;
    readonly outcome: TaskOutcome | null;
    readonly feedback: string | null;
    readonly question: string | null;
    readonly reportZone: 'inline' | 'hot' | 'cold' | 'missing' | null;
    readonly hasReportRef: boolean;
    readonly turn: number | null;
    readonly staff: readonly StaffEntry[];
    readonly taskTokensTotal: TokenBuckets | null;
    /** Whether the executor (assignedTo) is live; the authoritative tab-partition key. */
    readonly executorLive: boolean;
    /** Host-set when the task has entered the archive phase (completed ≥ 24h). */
    readonly archived?: boolean;
    /** DAG predecessors (task ids), in declaration order; empty when none. */
    readonly dependencies: readonly string[];
    /** Dispatcher's minimum acceptance requirement; null when unset. */
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
/** Status counters, isomorphic with the host snapshot `stats` object. */
export interface StatsView {
    readonly queued: number;
    readonly submitted: number;
    readonly working: number;
    readonly 'input-required': number;
    readonly completed: number;
    readonly failed: number;
    readonly canceled: number;
    readonly total: number;
}
/** One workspace in the snapshot directory. */
export interface WorkspaceView {
    readonly id: string;
    readonly title: string;
    readonly path: string;
}
/** One flow in the snapshot directory: a named DAG container (v1.4). */
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
/** One session in the snapshot directory. */
export interface SessionView {
    readonly id: string;
    readonly title: string;
    readonly workspaceId: string | null;
    readonly live: boolean;
    /** Whether the session was archived in the workspace registry. */
    readonly archived: boolean;
}
/** Full panel snapshot returned by the state route. */
export interface PanelSnapshot {
    readonly workspaces: readonly WorkspaceView[];
    readonly sessions: readonly SessionView[];
    readonly tasks: readonly TaskView[];
    readonly flows: readonly FlowView[];
    readonly stats: StatsView;
}
/** Color tone for a status dot / badge. */
export type Tone = 'tertiary' | 'business' | 'warning' | 'success' | 'danger';
/**
 * Format a snapshot-relative age as a Chinese relative-time string.
 *
 * @param updatedMs - milliseconds since `updatedAt` (snapshot `updatedMs`).
 * @param nowMs - clock used only for the absolute `yyyy-mm-dd` fallback.
 */
export declare function relativeTime(updatedMs: number, nowMs: number): string;
/** Tasks that have not reached a terminal settled state. */
export declare function unsettledTasks(tasks: readonly TaskView[]): TaskView[];
/** Terminal settled tasks — the archive list. */
export declare function settledTasks(tasks: readonly TaskView[]): TaskView[];
/** One historical participant drawn from settled workspace tasks. */
export interface ArchiveAgent {
    readonly sessionId: string;
    readonly title: string;
    readonly live: boolean;
}
/**
 * Restrict tasks to those a session participates in.
 *
 * @param sessionId - `null` keeps every task.
 */
export declare function tasksOfSession(tasks: readonly TaskView[], sessionId: string | null): TaskView[];
/**
 * Restrict tasks to one workspace path.
 *
 * @param workspacePath - `null` keeps every task.
 */
export declare function tasksOfWorkspace(tasks: readonly TaskView[], workspacePath: string | null): TaskView[];
/**
 * Unsettled tasks, oldest-updated first (the ones stuck longest sit on top).
 * Ties break on `createdAt` ascending. `nowMs` is accepted for the §4.4
 * signature; ordering is by the ISO stamps, not the clock.
 */
export declare function sortUnsettled(tasks: readonly TaskView[], _nowMs: number): TaskView[];
/** Settled tasks, newest-updated first so the archive reads as history. */
export declare function sortSettled(tasks: readonly TaskView[]): TaskView[];
/**
 * Distinct agents that participated in settled tasks, live first.
 * Titles prefer the session directory, then the task's resolved names.
 */
export declare function archiveAgents(tasks: readonly TaskView[], sessions: readonly SessionView[]): ArchiveAgent[];
/** Recount status buckets from a task list (same keys as the host `stats`). */
export declare function statsOf(tasks: readonly TaskView[]): StatsView;
/**
 * Most recently updated unsettled tasks, newest first — preview hover list.
 *
 * @param count - maximum rows to return.
 */
export declare function recentActivity(tasks: readonly TaskView[], count: number): TaskView[];
/**
 * Truncate by Unicode code point so a surrogate pair (emoji) is never split.
 * Overflow is marked with a single `…`.
 */
export declare function truncateCodePoints(text: string, max: number): string;
/** Sum the four token buckets. */
export declare function tokenTotal(tokens: TokenBuckets): number;
/** Three-part display: cache hit / input / output. */
export interface TokenParts {
    readonly cacheHit: number;
    readonly input: number;
    readonly output: number;
}
/** Project four host buckets onto the three-part staff display. */
export declare function tokenParts(tokens: TokenBuckets): TokenParts;
/**
 * Cache-hit rate: cache-read / (cache-read + uncached input).
 * Missing when there is no input of either kind.
 */
export declare function cacheHitPercent(tokens: TokenBuckets): number | null;
/** `缓存命中 92% · 输入 1,261 · 输出 732` */
/** One hop in the task's invocation chain. */
export interface CallHop {
    readonly sessionId: string;
    readonly title: string;
    readonly role: 'initiator' | 'executor' | 'reviewer';
}
/**
 * Invocation order for one task: initiator → executor → reviewer.
 * Executor is omitted when the task has no assignee yet.
 */
export declare function callChain(task: TaskView): CallHop[];
/** One directed call in the task: A → B plus the summary for that hop. */
export interface CallStep {
    readonly from: CallHop;
    readonly to: CallHop;
    readonly summary: string;
}
/**
 * Expand the hop list into consecutive calls.
 * Dispatch uses the task instruction; review uses feedback / question.
 */
export declare function callSteps(task: TaskView): CallStep[];
/** Task-period tokens for one participant, or null when unread. */
export declare function tokensForSession(task: TaskView, sessionId: string): TokenBuckets | null;
export declare function formatTokenUsage(tokens: TokenBuckets | null): string;
/** Thousand-separated integer (en-US grouping, ASCII digits). */
export declare function formatNumber(n: number): string;
/**
 * Chinese status badge copy.
 *
 * `completed` without an outcome is 「待验收」; a recorded outcome is 「已完成」.
 */
export declare function statusLabel(status: TaskStatus, outcome?: TaskOutcome | null): string;
/**
 * Status color tone. `completed` is warning while awaiting a verdict and
 * success once an outcome is recorded; the optional `outcome` exists because
 * the two completed presentations do not share a color.
 */
export declare function statusTone(status: TaskStatus, outcome?: TaskOutcome | null): Tone;
/** Sessions of one workspace, live rows first, original order otherwise preserved. */
export declare function sessionsOfWorkspace(sessions: readonly SessionView[], workspaceId: string | null): SessionView[];
/**
 * A completed task stays in the active tab for this long after settlement;
 * the host then marks it archived. The client also applies this locally
 * when the snapshot has not yet set `archived`.
 */
export declare const ARCHIVE_AGE_MS: number;
/** Host flag, dedicated status, or completed/settled older than 24h. */
export declare function isArchived(task: TaskView): boolean;
/**
 * Active tab: in-progress (not settled) plus 已完成 that is not yet archived.
 * Failed / canceled rows go to archive; they are not "进行中" or "已完成".
 * An offline executor cannot hold active work, so its rows are
 * archive-bound immediately.
 */
export declare function activeTabTasks(tasks: readonly TaskView[]): TaskView[];
/**
 * Archive tab: everything of offline executors, host-archived rows,
 * completed-over-24h, and other terminals (failed / canceled) that are not
 * shown as active work.
 */
export declare function archiveTabTasks(tasks: readonly TaskView[]): TaskView[];
/** Active-tab order: in-progress first (oldest update), then 已完成 (newest). */
export declare function sortActive(tasks: readonly TaskView[]): TaskView[];
/**
 * Sidebar sessions for one tab. Every tab lists all unarchived sessions of
 * the workspace — live rows first — since a task may reference any of them;
 * the archive tab additionally lists archived sessions at the end, which the
 * UI renders as its offline module. Archived sessions own no active work, so
 * they never appear on the active tab.
 */
export declare function sessionsForTab(sessions: readonly SessionView[], archiveMode: boolean): SessionView[];
/** True when any staff row is missing a task-period token delta. */
export declare function hasUnreadableTokens(staff: readonly StaffEntry[]): boolean;
/** Empty snapshot used before the first successful poll (and on hard failure). */
export declare function emptySnapshot(): PanelSnapshot;
/** One node in the workspace DAG: the task plus its topological depth. */
export interface DagNode {
    readonly task: TaskView;
    readonly depth: number;
}
/** A directed edge: `from` is the predecessor, `to` is the dependent. */
export interface DagEdge {
    readonly from: string;
    readonly to: string;
}
/** Laid-out box for one DAG node (compact layered placement). */
export interface DagBox {
    readonly id: string;
    readonly task: TaskView;
    readonly depth: number;
    readonly x: number;
    readonly y: number;
    readonly w: number;
    readonly h: number;
}
export declare const DAG_NODE_W = 176;
export declare const DAG_NODE_H = 64;
export declare const DAG_GAP_X = 64;
export declare const DAG_GAP_Y = 22;
export declare const DAG_PAD = 20;
/**
 * Build the workspace DAG: nodes keep topological depth (longest path from
 * a root); edges run predecessor → dependent. Cycles are skipped defensively
 * (the ledger already rejects them on write). Isolated tasks sit at depth 0.
 */
export declare function dagOf(tasks: readonly TaskView[]): {
    nodes: DagNode[];
    edges: DagEdge[];
};
/**
 * Compact layered layout: one column per depth, rows packed by createdAt.
 */
export declare function layoutDag(graph: {
    readonly nodes: readonly DagNode[];
    readonly edges: readonly DagEdge[];
}, opts?: {
    readonly nodeW?: number;
    readonly nodeH?: number;
    readonly gapX?: number;
    readonly gapY?: number;
    readonly pad?: number;
}): {
    boxes: DagBox[];
    width: number;
    height: number;
};
/**
 * Walk the dependency cone of one task. Upstream follows `dependencies`,
 * downstream follows `dependents`. The focus node itself is omitted.
 */
export declare function dependencyChainOf(taskId: string, nodes: readonly DagNode[]): {
    upstream: TaskView[];
    downstream: TaskView[];
};
/**
 * Client-side blockedBy: a predecessor is satisfied only when it settled
 * with `outcome === 'success'`. Missing ids stay blocking.
 */
export declare function blockedByOf(task: TaskView, tasks: readonly TaskView[]): readonly string[];
/**
 * Readable copy for a failure that the scheduler propagated down the DAG.
 * Other reasons stay `null` so the view does not invent a dependency badge.
 */
export declare function failureReasonOf(task: TaskView): string | null;
/** True when any declared predecessor is terminally failed or canceled. */
export declare function hasFailedDependency(task: TaskView, tasks: readonly TaskView[]): boolean;
/**
 * @deprecated v1.4 uses the explicit `queued` status. Kept for older tests.
 */
export declare function isReadyUndelivered(task: TaskView, tasks: readonly TaskView[]): boolean;
/** Flows of one workspace: active first, then archived. */
export declare function flowsOfWorkspace(flows: readonly FlowView[], workspacePath: string | null): FlowView[];
/** Tasks that belong to one flow. Flow-less rows never appear in a DAG. */
export declare function tasksOfFlow(tasks: readonly TaskView[], flowId: string | null): TaskView[];
/**
 * The DAG's archive rule (v1.4 §6): a terminal failure/cancel/reject leaves
 * the active set IMMEDIATELY, and a settled success leaves after the 24h
 * archive phase. This mirrors the flow-archived derivation (panel flows
 * directory) and the tools' isActiveTask, so a faded node, the flow list,
 * and the agent-visible set never disagree about what is archived.
 */
export declare function isDagArchived(task: TaskView): boolean;
/**
 * v1.4 §6.1: active tasks plus every recursive predecessor, so an archived
 * ancestor chain stays visible beside live work. Terminal-failed tasks are
 * not anchors but still appear when an active task depends on them.
 * Isolated archived tasks drop off the graph.
 */
export declare function visibleDagTasks(tasks: readonly TaskView[]): TaskView[];
/** Archived node on a live chain: faded, not interactive. */
export declare function isDagFaded(task: TaskView): boolean;
/** Queued with every predecessor settled — the client scheduler may POST /dispatch. */
export declare function isReadyToDispatch(task: TaskView, tasks: readonly TaskView[]): boolean;
/** Color of a node on a highlighted dependency chain. */
export type ChainTone = 'ok' | 'wait' | 'fail';
/** Upstream tone: settled success / still open / terminal failure. */
export declare function chainToneOf(task: TaskView): ChainTone;
