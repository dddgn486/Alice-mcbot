/**
 * The model-facing tool surface: nine tools over the ledger and the delivery
 * path, named after the A2A operation set where one exists.
 *
 * The surface stays deliberately small. The reference implementation this
 * draws on grew to 73 tools and had to fold them behind a router to keep the
 * prompt-cache prefix stable; the lesson taken here is not to build a router
 * but to never need one. Orchestration concerns — dependency graphs, goals,
 * file locks, shared knowledge — are a different capability and stay out.
 *
 * There is no receive-side tool. `followup()` turns a delivered task into an
 * ordinary turn on the recipient, so a worker reads its task as user input
 * with no claim step to perform.
 *
 * @module dsh-agent-bus/tools
 */
import type { Context } from '@deepseek-ai/cordis';
import type { SessionId } from '@deepseek-ai/dsh-session';
import type { WorkspaceRegistry } from '@deepseek-ai/dsh-workspace';
import { type DeliverySource } from './delivery.ts';
import type { ReportStore } from './external.ts';
import { type TaskLedger } from './ledger.ts';
import { DispatchRateLimiter } from './rate-limit.ts';
import { TaskId, type TaskRecord } from './types.ts';
/** Resolved plugin configuration the tools read. */
export interface ToolsConfig {
    readonly maxContentLength: number;
    readonly maxPendingPerAgent: number;
    readonly maxSendsPerMinute: number;
    /** Reports longer than this are externalized to the report store (default `400`). */
    readonly maxInlineReport: number;
    /** Lightweight messages one sender may send per minute (default `20`). */
    readonly maxMessagesPerMinute: number;
}
/** Services the tool bodies need beyond `ctx`. */
export interface ToolsDeps {
    readonly ledger: TaskLedger;
    readonly workspaces: WorkspaceRegistry;
    readonly limiter: DispatchRateLimiter;
    /** Separate sliding window for send_note, so chatter cannot exhaust task quota. */
    readonly messageLimiter: DispatchRateLimiter;
    readonly reports: ReportStore;
}
/** Model-facing projection of one ledger row for listings. */
interface TaskView {
    readonly id: string;
    readonly status: string;
    readonly from: string;
    readonly to?: string;
    readonly content: string;
    readonly title?: string;
    readonly report?: string;
    readonly outcome?: string;
    readonly reason?: string;
    readonly dependencies?: string[];
    readonly acceptanceCriteria?: string;
    readonly retries: number;
}
/**
 * Whether one row is visible to the agent tools — the active set only.
 *
 * Archived tasks are invisible to list_tasks by design, using the SAME
 * archive rule as the panel: a terminal failure/cancel/reject leaves the
 * listing immediately, and a settled success leaves once the settlement is
 * more than 24h old (the panel's archive-phase age). Everything the agent
 * can still act on — submitted, working, awaiting input, and a completed
 * row awaiting its verdict — stays visible. History lives in the panel and
 * session logs; get_task still reads an archived row by id for a reference
 * that reached the agent before archiving.
 *
 * @param row - the ledger row.
 * @param now - current epoch milliseconds.
 * @returns `true` when the row belongs to the active set.
 */
export declare function isActiveTask(row: TaskRecord, now: number): boolean;
export declare function renderTaskRow(t: TaskView): string;
/** Model-facing projection of one full task record. */
interface TaskDetailView {
    readonly id: string;
    readonly status: string;
    readonly from: string;
    readonly to?: string;
    readonly content: string;
    readonly title?: string;
    readonly acceptanceCriteria?: string;
    readonly dependencies?: string[];
    readonly handoffs?: {
        fromTask: string;
        document: string;
        at: string;
    }[];
    readonly report?: string;
    readonly question?: string;
    readonly outcome?: string;
    readonly feedback?: string;
    readonly reason?: string;
    readonly reviewer?: string;
    readonly retries: number;
    readonly createdAt: string;
    readonly updatedAt: string;
}
/**
 * Decide whether one session may read a task.
 *
 * The workspace is the trust boundary, matching the harness's own
 * cross-session authorization (tool-session-query's cwd predicate):
 * participants always pass, and any other session sharing the task's
 * workspace does too. Task ids can reach non-participants through relayed
 * messages and future visibility surfaces, so the read gate stays even where
 * ids are undiscoverable today.
 *
 * @param task - the row being read.
 * @param callerId - the session requesting the read.
 * @param callerWorkspace - the caller's resolved workspace path, if any.
 * @returns `true` when the read is authorized.
 */
export declare function canReadTask(task: TaskRecord, callerId: SessionId, callerWorkspace: string | undefined): boolean;
/**
 * Render one full task record.
 *
 * get_task exists so a listing's truncation caps never cost information: the
 * content and report are printed complete here.
 *
 * @param t - the projected full record.
 * @returns the text of the record.
 */
export declare function renderTaskDetail(t: TaskDetailView): string;
export declare function notifySession(ctx: Context, sessionId: SessionId, taskId: TaskId, text: string, tool?: DeliverySource): void;
/**
 * Register the nine agent-bus tools.
 *
 * @param ctx - context carrying the tool registry and live Agent registry.
 * @param config - resolved tunables.
 * @param deps - the opened ledger and the workspace registry.
 */
export declare function registerAgentBusTools(ctx: Context, config: ToolsConfig, deps: ToolsDeps): void;
export {};
