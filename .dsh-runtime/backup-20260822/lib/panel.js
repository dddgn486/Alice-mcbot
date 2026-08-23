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
import { dshHomePath } from '@deepseek-ai/dsh-home-paths';
import { blockedByOf } from "./ledger.js";
import { fallbackTitle, readTitlesFile } from "./titles.js";
/** Four-bucket token usage reported by the token-meter projection. */
const TOKEN_KEYS = ['uncachedInputTokens', 'outputTokens', 'cacheReadTokens', 'cacheWriteTokens'];
/** Whether a value has the token-meter projection's bucket shape. */
export function isTokenBuckets(value) {
    if (typeof value !== 'object' || value === null)
        return false;
    const record = value;
    return TOKEN_KEYS.every(key => typeof record[key] === 'number' && Number.isFinite(record[key]) && record[key] >= 0);
}
/**
 * How long a settled task stays in the active tab before the lifecycle's
 * archive phase takes it over (24 hours).
 */
export const ARCHIVE_AGE_MS = 24 * 60 * 60 * 1000;
/** Empty stats row. */
function emptyStats() {
    return {
        queued: 0, submitted: 0, working: 0, 'input-required': 0,
        completed: 0, failed: 0, canceled: 0, total: 0,
    };
}
/**
 * Truncate by Unicode code point so a surrogate pair (emoji) is never split;
 * overflow is marked with a single ellipsis. Mirrors the client model.
 *
 * @param text - the text to truncate.
 * @param max - maximum code points before the ellipsis.
 * @returns the truncated text.
 */
export function truncateCodePoints(text, max) {
    if (max <= 0)
        return text === '' ? '' : '…';
    const points = Array.from(text);
    if (points.length <= max)
        return text;
    return `${points.slice(0, max).join('')}…`;
}
/**
 * Locate an externalized report in the two-zone store.
 *
 * @param reports - the report store.
 * @param task - the row whose report zone is asked for.
 * @returns `'inline'` when the report rides the row, `'hot'` / `'cold'` when
 *   the file exists in the matching zone, `'missing'` when the reference
 *   names a file in neither zone, and `null` when the task has no report.
 */
export async function detectReportZone(reports, task) {
    if (task.report !== undefined && task.reportRef === undefined)
        return 'inline';
    if (task.reportRef === undefined)
        return null;
    if (await reports.existsHot(task.reportRef))
        return 'hot';
    if (await reports.existsCold(task.reportRef))
        return 'cold';
    return 'missing';
}
/** Whether a completed row has been settled. */
function isSettled(task) {
    return task.status === 'completed'
        ? task.outcome !== undefined
        : task.status === 'failed' || task.status === 'canceled';
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
export function staffRoles(initiator, executor, reviewer) {
    const slots = [];
    const seen = new Set();
    const push = (sessionId, role) => {
        if (sessionId === undefined || seen.has(sessionId))
            return;
        seen.add(sessionId);
        slots.push({ sessionId, role });
    };
    push(executor, 'executor');
    push(reviewer ?? initiator, 'reviewer');
    push(initiator, 'initiator');
    return slots;
}
/**
 * The staff of one ledger row (see {@link staffRoles}).
 *
 * @param task - the row.
 * @returns the role slots in display order.
 */
export function staffRolesOf(task) {
    return staffRoles(task.assignedBy, task.assignedTo, task.assignedReviewer);
}
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
export function tokenDeltaOf(projections, agents, task, sessionId) {
    const start = task.tokensAtStart?.[sessionId];
    if (start === undefined)
        return null;
    const agent = agents?.get(sessionId);
    const current = agent === undefined ? undefined : projections?.snapshot(agent.session).values.tokenUsage;
    if (!isTokenBuckets(current))
        return null;
    const clamp = (a, b) => Math.max(0, a - b);
    return {
        uncachedInputTokens: clamp(current.uncachedInputTokens, start.uncachedInputTokens),
        outputTokens: clamp(current.outputTokens, start.outputTokens),
        cacheReadTokens: clamp(current.cacheReadTokens, start.cacheReadTokens),
        cacheWriteTokens: clamp(current.cacheWriteTokens, start.cacheWriteTokens),
    };
}
/** Sum token buckets; `null` when every input is null (never partial here). */
function sumTokens(entries) {
    let total = null;
    for (const entry of entries) {
        if (entry === null)
            continue;
        total = total === null
            ? { ...entry }
            : {
                uncachedInputTokens: total.uncachedInputTokens + entry.uncachedInputTokens,
                outputTokens: total.outputTokens + entry.outputTokens,
                cacheReadTokens: total.cacheReadTokens + entry.cacheReadTokens,
                cacheWriteTokens: total.cacheWriteTokens + entry.cacheWriteTokens,
            };
    }
    return total;
}
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
export async function buildTaskView(task, titles, agents, projections, reports, now) {
    const titleOf = (sessionId) => sessionId === undefined ? null : titles.get(sessionId) ?? fallbackTitle(sessionId);
    const liveOf = (sessionId) => sessionId !== undefined && agents?.get(sessionId) !== undefined;
    const staff = staffRolesOf(task).map(({ sessionId, role }) => ({
        sessionId,
        title: titles.get(sessionId) ?? fallbackTitle(sessionId),
        role,
        live: liveOf(sessionId),
        tokensInTask: tokenDeltaOf(projections, agents, task, sessionId),
    }));
    return {
        id: task.id,
        workspacePath: task.workspacePath,
        status: task.status,
        settled: isSettled(task),
        dependencies: [...(task.dependencies ?? [])],
        acceptanceCriteria: task.acceptanceCriteria ?? null,
        flowId: task.flowId ?? null,
        content: task.content,
        title: task.title ?? null,
        contentPreview: truncateCodePoints(task.content, 120),
        mode: task.mode,
        assignedBy: task.assignedBy,
        assignedTo: task.assignedTo ?? null,
        assignedReviewer: task.assignedReviewer ?? null,
        byTitle: titleOf(task.assignedBy) ?? fallbackTitle(task.assignedBy),
        toTitle: titleOf(task.assignedTo),
        reviewerTitle: titleOf(task.assignedReviewer ?? task.assignedBy),
        retries: task.retries,
        reason: task.reason ?? null,
        outcome: task.outcome ?? null,
        feedback: task.feedback !== undefined ? truncateCodePoints(task.feedback, 200) : null,
        question: task.question !== undefined ? truncateCodePoints(task.question, 200) : null,
        reportZone: await detectReportZone(reports, task),
        hasReportRef: task.reportRef !== undefined,
        turn: task.turn ?? null,
        staff,
        taskTokensTotal: sumTokens(staff.map(entry => entry.tokensInTask)),
        executorLive: task.assignedTo !== undefined && agents?.get(task.assignedTo) !== undefined,
        archived: isSettled(task) && now - Date.parse(task.updatedAt) >= ARCHIVE_AGE_MS,
        dependents: [],
        blockedBy: [],
        auto: task.auto === true,
        createdAt: task.createdAt,
        updatedAt: task.updatedAt,
        ageMs: Math.max(0, now - Date.parse(task.createdAt)),
        updatedMs: Math.max(0, now - Date.parse(task.updatedAt)),
    };
}
/**
 * The session directory: every session the workspace registry indexes — the
 * harness sidebar's own index, byte for byte. The plugin adds no session
 * logic of its own: no log probes, no mtime windows, no attach heuristics.
 * Sessions referenced by tasks but missing from the registry are added by
 * the caller as offline references.
 *
 * Archived sessions keep their registry slot (archiving never touches
 * workspace accounting), so they stay in the directory flagged archived; the
 * UI's archive tab
 * renders them in its offline module. Blank seeds (a log file of metadata
 * only) are excluded exactly like the harness sidebar hides them.
 *
 * @param ctx - plugin context; the session store is optional at runtime.
 * @param now - probe clock.
 * @returns the authoritative set of visible session ids.
 */
async function visibleSessionIds(ctx) {
    // The workspace registry is the ONLY authority, byte for byte the harness
    // sidebar's source: `sessionIds` is already filtered by the registry's
    // header index, so whatever the sidebar shows is exactly this set. No log
    // probes, no mtime windows, no store heuristics — the plugin adds no
    // session logic of its own. Archived sessions keep their slot (archiving
    // never touches workspace accounting), so they stay in this set and the
    // archived flag comes from the archive set.
    const ids = new Set();
    const registry = ctx.get('workspaceRegistry');
    for (const workspace of registry?.list() ?? []) {
        for (const sessionId of workspace.sessionIds) {
            ids.add(String(sessionId));
        }
    }
    return ids;
}
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
export async function buildPanelSnapshot(ctx, ledger, reports, now = Date.now()) {
    const registry = ctx.get('workspaceRegistry');
    const workspaces = registry?.list() ?? [];
    const agents = ctx.get('agents');
    const projections = ctx.get('sessionProjections');
    const titles = await readTitlesFile(dshHomePath('storages', 'session_projcache.json'));
    // Live sessions: the title MUST match what the harness sidebar shows. The
    // sidebar reads the session-title projection ('title'), so the same
    // projection value overrides the disk cache for every live session; the
    // projection may legitimately be absent (title not generated yet), in which
    // case the disk value — or the id-prefix fallback — stands.
    const sessionStore = ctx.get('sessions');
    if (agents !== undefined && projections !== undefined) {
        for (const session of sessionStore?.list() ?? []) {
            if (session.header.origin === 'subagent')
                continue;
            const agent = agents.get(session.id);
            if (agent === undefined)
                continue;
            const title = projections.snapshot(agent.session).values.title;
            if (typeof title === 'string' && title !== '')
                titles.set(session.id, title);
        }
    }
    // Session directory: every visible session (sidebar same-source), mapped to
    // its owning workspace through the registry account, plus any session a
    // task references that is no longer visible (an offline reference).
    const registrySessionWorkspace = new Map();
    for (const workspace of workspaces) {
        for (const sessionId of workspace.sessionIds) {
            registrySessionWorkspace.set(String(sessionId), String(workspace.id));
        }
    }
    const visible = await visibleSessionIds(ctx);
    const archivedIds = new Set((registry?.archivedSessionIds ?? []).map(String));
    const sessionWorkspace = new Map();
    for (const sessionId of visible) {
        sessionWorkspace.set(sessionId, registrySessionWorkspace.get(sessionId) ?? '');
    }
    for (const task of ledger.listAll()) {
        for (const sessionId of [task.assignedBy, task.assignedTo, task.assignedReviewer]) {
            if (sessionId !== undefined)
                sessionWorkspace.set(String(sessionId), sessionWorkspace.get(String(sessionId)) ?? '');
        }
    }
    const sessions = [];
    for (const [sessionId, workspaceId] of sessionWorkspace) {
        sessions.push({
            id: sessionId,
            title: titles.get(sessionId) ?? fallbackTitle(sessionId),
            workspaceId: workspaceId === '' ? null : workspaceId,
            live: agents?.get(sessionId) !== undefined,
            archived: archivedIds.has(sessionId),
        });
    }
    const tasks = [];
    const stats = emptyStats();
    const allRows = ledger.listAll();
    for (const task of allRows) {
        const view = await buildTaskView(task, titles, agents, projections, reports, now);
        tasks.push(view);
        stats.total += 1;
        switch (task.status) {
            case 'queued':
            case 'submitted':
            case 'working':
            case 'input-required':
            case 'completed':
            case 'failed':
            case 'canceled':
                stats[task.status] += 1;
                break;
            default:
                break;
        }
    }
    // DAG columns need the whole table: reverse edges and unsettled blockers.
    const rowById = new Map(allRows.map(row => [String(row.id), row]));
    for (let index = 0; index < tasks.length; index++) {
        const view = tasks[index];
        const row = rowById.get(view.id);
        tasks[index] = {
            ...view,
            dependents: allRows
                .filter(item => (item.dependencies ?? []).some(dep => String(dep) === view.id))
                .map(item => String(item.id)),
            blockedBy: row === undefined ? [] : [...blockedByOf(row, allRows).map(String)],
        };
    }
    // Flow directory: derived active/archived per flow. The DAG view selects a
    // flow and renders only its tasks; flow-less tasks never appear there.
    const flows = ledger.listFlows().map(flow => {
        const tasks = allRows.filter(row => row.flowId === flow.id);
        const unsettled = tasks.filter(row => isActiveRow(row, now));
        return {
            id: flow.id,
            name: flow.name,
            description: flow.description ?? null,
            workspacePath: flow.workspacePath,
            taskCount: tasks.length,
            unsettledCount: unsettled.length,
            archived: tasks.length > 0 && unsettled.length === 0,
        };
    });
    return {
        workspaces: workspaces.map(workspace => ({
            id: String(workspace.id),
            title: workspace.title,
            path: workspace.path,
        })),
        sessions,
        tasks,
        flows,
        stats,
    };
}
/** Whether one row is still in the active set (archive-rule mirror). */
function isActiveRow(row, now) {
    if (row.status === 'failed' || row.status === 'canceled' || row.status === 'rejected') {
        return false;
    }
    if (row.status === 'completed') {
        if (row.outcome === undefined)
            return true;
        return now - Date.parse(row.updatedAt) < ARCHIVE_AGE_MS;
    }
    return true;
}
