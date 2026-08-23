/**
 * Pure view-model helpers for the v1.1 read-only task panel.
 *
 * Snapshot shapes match docs/v1.1-task-panel-spec.md §3.6–3.7 / §4.4.
 * No I/O, no React — every export is unit-tested from tests/panel-model.test.ts.
 *
 * @module dsh-agent-bus/client/panel-model
 */
const MINUTE_MS = 60_000;
const HOUR_MS = 60 * MINUTE_MS;
const DAY_MS = 24 * HOUR_MS;
const WEEK_MS = 7 * DAY_MS;
const EMPTY_STATS = {
    queued: 0,
    submitted: 0,
    working: 0,
    'input-required': 0,
    completed: 0,
    failed: 0,
    canceled: 0,
    total: 0,
};
/**
 * Format a snapshot-relative age as a Chinese relative-time string.
 *
 * @param updatedMs - milliseconds since `updatedAt` (snapshot `updatedMs`).
 * @param nowMs - clock used only for the absolute `yyyy-mm-dd` fallback.
 */
export function relativeTime(updatedMs, nowMs) {
    if (updatedMs < MINUTE_MS)
        return '刚刚';
    if (updatedMs < HOUR_MS)
        return `${Math.floor(updatedMs / MINUTE_MS)} 分钟前`;
    if (updatedMs < DAY_MS)
        return `${Math.floor(updatedMs / HOUR_MS)} 小时前`;
    if (updatedMs < WEEK_MS)
        return `${Math.floor(updatedMs / DAY_MS)} 天前`;
    const date = new Date(nowMs - updatedMs);
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
}
/** Tasks that have not reached a terminal settled state. */
export function unsettledTasks(tasks) {
    return tasks.filter(task => !task.settled);
}
/** Terminal settled tasks — the archive list. */
export function settledTasks(tasks) {
    return tasks.filter(task => task.settled);
}
/**
 * Restrict tasks to those a session participates in.
 *
 * @param sessionId - `null` keeps every task.
 */
export function tasksOfSession(tasks, sessionId) {
    if (sessionId === null)
        return [...tasks];
    return tasks.filter(task => task.assignedBy === sessionId
        || task.assignedTo === sessionId
        || task.assignedReviewer === sessionId);
}
/**
 * Restrict tasks to one workspace path.
 *
 * @param workspacePath - `null` keeps every task.
 */
export function tasksOfWorkspace(tasks, workspacePath) {
    if (workspacePath === null)
        return [...tasks];
    return tasks.filter(task => task.workspacePath === workspacePath);
}
/**
 * Unsettled tasks, oldest-updated first (the ones stuck longest sit on top).
 * Ties break on `createdAt` ascending. `nowMs` is accepted for the §4.4
 * signature; ordering is by the ISO stamps, not the clock.
 */
export function sortUnsettled(tasks, _nowMs) {
    return unsettledTasks(tasks).sort((left, right) => {
        const updated = left.updatedAt.localeCompare(right.updatedAt);
        if (updated !== 0)
            return updated;
        return left.createdAt.localeCompare(right.createdAt);
    });
}
/** Settled tasks, newest-updated first so the archive reads as history. */
export function sortSettled(tasks) {
    return settledTasks(tasks).sort((left, right) => {
        const updated = right.updatedAt.localeCompare(left.updatedAt);
        if (updated !== 0)
            return updated;
        return right.createdAt.localeCompare(left.createdAt);
    });
}
/**
 * Distinct agents that participated in settled tasks, live first.
 * Titles prefer the session directory, then the task's resolved names.
 */
export function archiveAgents(tasks, sessions) {
    const byId = new Map(sessions.map(session => [session.id, session]));
    const seen = new Map();
    const consider = (sessionId, fallbackTitle) => {
        if (sessionId === null || sessionId === '' || seen.has(sessionId))
            return;
        const session = byId.get(sessionId);
        seen.set(sessionId, {
            sessionId,
            title: session?.title ?? fallbackTitle ?? sessionId.slice(0, 8),
            live: session?.live ?? false,
        });
    };
    for (const task of settledTasks(tasks)) {
        consider(task.assignedTo, task.toTitle);
        consider(task.assignedReviewer, task.reviewerTitle);
        consider(task.assignedBy, task.byTitle);
    }
    return [...seen.values()].sort((left, right) => Number(right.live) - Number(left.live));
}
/** Recount status buckets from a task list (same keys as the host `stats`). */
export function statsOf(tasks) {
    const next = { ...EMPTY_STATS };
    for (const task of tasks) {
        next.total += 1;
        switch (task.status) {
            case 'queued':
            case 'submitted':
            case 'working':
            case 'input-required':
            case 'completed':
            case 'failed':
            case 'canceled':
                next[task.status] += 1;
                break;
            default:
                break;
        }
    }
    return next;
}
/**
 * Most recently updated unsettled tasks, newest first — preview hover list.
 *
 * @param count - maximum rows to return.
 */
export function recentActivity(tasks, count) {
    return unsettledTasks(tasks)
        .sort((left, right) => {
        const updated = right.updatedAt.localeCompare(left.updatedAt);
        if (updated !== 0)
            return updated;
        return right.createdAt.localeCompare(left.createdAt);
    })
        .slice(0, Math.max(0, count));
}
/**
 * Truncate by Unicode code point so a surrogate pair (emoji) is never split.
 * Overflow is marked with a single `…`.
 */
export function truncateCodePoints(text, max) {
    if (max <= 0)
        return text === '' ? '' : '…';
    const points = Array.from(text);
    if (points.length <= max)
        return text;
    return `${points.slice(0, max).join('')}…`;
}
/** Sum the four token buckets. */
export function tokenTotal(tokens) {
    return tokens.uncachedInputTokens
        + tokens.outputTokens
        + tokens.cacheReadTokens
        + tokens.cacheWriteTokens;
}
/** Project four host buckets onto the three-part staff display. */
export function tokenParts(tokens) {
    return {
        cacheHit: tokens.cacheReadTokens,
        input: tokens.uncachedInputTokens,
        output: tokens.outputTokens,
    };
}
/**
 * Cache-hit rate: cache-read / (cache-read + uncached input).
 * Missing when there is no input of either kind.
 */
export function cacheHitPercent(tokens) {
    const denom = tokens.cacheReadTokens + tokens.uncachedInputTokens;
    if (denom <= 0)
        return null;
    return Math.round((tokens.cacheReadTokens / denom) * 100);
}
/**
 * Invocation order for one task: initiator → executor → reviewer.
 * Executor is omitted when the task has no assignee yet.
 */
export function callChain(task) {
    const hops = [
        { sessionId: task.assignedBy, title: task.byTitle, role: 'initiator' },
    ];
    if (task.assignedTo !== null && task.assignedTo !== '') {
        hops.push({
            sessionId: task.assignedTo,
            title: task.toTitle ?? task.assignedTo.slice(0, 8),
            role: 'executor',
        });
    }
    const reviewerId = task.assignedReviewer ?? task.assignedBy;
    hops.push({
        sessionId: reviewerId,
        title: task.reviewerTitle ?? task.byTitle,
        role: 'reviewer',
    });
    return hops;
}
/**
 * Expand the hop list into consecutive calls.
 * Dispatch uses the task instruction; review uses feedback / question.
 */
export function callSteps(task) {
    const hops = callChain(task);
    const steps = [];
    for (let index = 0; index < hops.length - 1; index += 1) {
        const from = hops[index];
        const to = hops[index + 1];
        if (from === undefined || to === undefined)
            continue;
        const reviewHop = from.role === 'executor' && to.role === 'reviewer';
        const summary = reviewHop
            ? (task.feedback !== null && task.feedback !== ''
                ? task.feedback
                : task.question !== null && task.question !== ''
                    ? task.question
                    : '提交验收')
            : '派发';
        steps.push({ from, to, summary });
    }
    return steps;
}
/** Task-period tokens for one participant, or null when unread. */
export function tokensForSession(task, sessionId) {
    return task.staff.find(entry => entry.sessionId === sessionId)?.tokensInTask ?? null;
}
export function formatTokenUsage(tokens) {
    if (tokens === null)
        return '缓存命中 — · 输入 — · 输出 —';
    const percent = cacheHitPercent(tokens);
    const hit = percent === null ? '—' : `${percent}%`;
    return `缓存命中 ${hit} · 输入 ${formatNumber(tokens.uncachedInputTokens)} · 输出 ${formatNumber(tokens.outputTokens)}`;
}
/** Thousand-separated integer (en-US grouping, ASCII digits). */
export function formatNumber(n) {
    return Math.round(n).toLocaleString('en-US');
}
/**
 * Chinese status badge copy.
 *
 * `completed` without an outcome is 「待验收」; a recorded outcome is 「已完成」.
 */
export function statusLabel(status, outcome) {
    switch (status) {
        case 'queued': return '待投递';
        case 'submitted': return '待执行';
        case 'working': return '进行中';
        case 'input-required': return '等待输入';
        case 'completed': return outcome === null || outcome === undefined ? '待验收' : '已完成';
        case 'failed': return '失败';
        case 'canceled': return '已取消';
        case 'archived': return '已归档';
        case 'auth-required': return '待授权';
        case 'rejected': return '已拒绝';
    }
}
/**
 * Status color tone. `completed` is warning while awaiting a verdict and
 * success once an outcome is recorded; the optional `outcome` exists because
 * the two completed presentations do not share a color.
 */
export function statusTone(status, outcome) {
    switch (status) {
        case 'working': return 'business';
        case 'input-required': return 'warning';
        case 'completed': return outcome === 'success' || outcome === 'failure' ? 'success' : 'warning';
        case 'failed': return 'danger';
        case 'queued':
        case 'submitted':
        case 'canceled':
        case 'archived':
        case 'auth-required':
        case 'rejected':
            return 'tertiary';
    }
}
/** Sessions of one workspace, live rows first, original order otherwise preserved. */
export function sessionsOfWorkspace(sessions, workspaceId) {
    const scoped = workspaceId === null
        ? [...sessions]
        : sessions.filter(session => session.workspaceId === workspaceId);
    return scoped.sort((left, right) => Number(right.live) - Number(left.live));
}
/**
 * A completed task stays in the active tab for this long after settlement;
 * the host then marks it archived. The client also applies this locally
 * when the snapshot has not yet set `archived`.
 */
export const ARCHIVE_AGE_MS = 24 * 60 * 60 * 1000;
/** Host flag, dedicated status, or completed/settled older than 24h. */
export function isArchived(task) {
    if (task.archived === true || task.status === 'archived')
        return true;
    return task.settled && task.updatedMs >= ARCHIVE_AGE_MS;
}
/**
 * Active tab: in-progress (not settled) plus 已完成 that is not yet archived.
 * Failed / canceled rows go to archive; they are not "进行中" or "已完成".
 * An offline executor cannot hold active work, so its rows are
 * archive-bound immediately.
 */
export function activeTabTasks(tasks) {
    return tasks.filter(task => {
        if (!task.executorLive)
            return false;
        if (isArchived(task))
            return false;
        if (!task.settled)
            return true;
        return task.status === 'completed';
    });
}
/**
 * Archive tab: everything of offline executors, host-archived rows,
 * completed-over-24h, and other terminals (failed / canceled) that are not
 * shown as active work.
 */
export function archiveTabTasks(tasks) {
    return tasks.filter(task => !task.executorLive || isArchived(task) || (task.settled && task.status !== 'completed'));
}
/** Active-tab order: in-progress first (oldest update), then 已完成 (newest). */
export function sortActive(tasks) {
    return [...tasks].sort((left, right) => {
        const leftDone = left.settled ? 1 : 0;
        const rightDone = right.settled ? 1 : 0;
        if (leftDone !== rightDone)
            return leftDone - rightDone;
        if (!left.settled) {
            const updated = left.updatedAt.localeCompare(right.updatedAt);
            return updated !== 0 ? updated : left.createdAt.localeCompare(right.createdAt);
        }
        const updated = right.updatedAt.localeCompare(left.updatedAt);
        return updated !== 0 ? updated : right.createdAt.localeCompare(left.createdAt);
    });
}
/**
 * Sidebar sessions for one tab. Every tab lists all unarchived sessions of
 * the workspace — live rows first — since a task may reference any of them;
 * the archive tab additionally lists archived sessions at the end, which the
 * UI renders as its offline module. Archived sessions own no active work, so
 * they never appear on the active tab.
 */
export function sessionsForTab(sessions, archiveMode) {
    const unarchived = sessions.filter(session => !session.archived);
    if (!archiveMode)
        return unarchived.sort((left, right) => Number(right.live) - Number(left.live));
    const archived = sessions.filter(session => session.archived);
    return [
        ...unarchived.sort((left, right) => Number(right.live) - Number(left.live)),
        ...archived.sort((left, right) => Number(right.live) - Number(left.live)),
    ];
}
/** True when any staff row is missing a task-period token delta. */
export function hasUnreadableTokens(staff) {
    return staff.some(entry => entry.tokensInTask === null);
}
const EMPTY_SNAPSHOT = {
    workspaces: [],
    sessions: [],
    tasks: [],
    flows: [],
    stats: EMPTY_STATS,
};
/** Empty snapshot used before the first successful poll (and on hard failure). */
export function emptySnapshot() {
    return EMPTY_SNAPSHOT;
}
export const DAG_NODE_W = 176;
export const DAG_NODE_H = 64;
export const DAG_GAP_X = 64;
export const DAG_GAP_Y = 22;
export const DAG_PAD = 20;
function predecessorsOf(task) {
    return task.dependencies ?? [];
}
function successorsOf(task) {
    return task.dependents ?? [];
}
function reaches(from, target, adj) {
    const stack = [from];
    const seen = new Set();
    while (stack.length > 0) {
        const current = stack.pop();
        if (current === undefined)
            break;
        if (current === target)
            return true;
        if (seen.has(current))
            continue;
        seen.add(current);
        const next = adj.get(current);
        if (next === undefined)
            continue;
        for (const id of next)
            stack.push(id);
    }
    return false;
}
/**
 * Build the workspace DAG: nodes keep topological depth (longest path from
 * a root); edges run predecessor → dependent. Cycles are skipped defensively
 * (the ledger already rejects them on write). Isolated tasks sit at depth 0.
 */
export function dagOf(tasks) {
    const byId = new Map(tasks.map(task => [task.id, task]));
    const adj = new Map();
    const incoming = new Map();
    const edges = [];
    const ordered = [...tasks].sort((left, right) => {
        const created = left.createdAt.localeCompare(right.createdAt);
        return created !== 0 ? created : left.id.localeCompare(right.id);
    });
    for (const task of ordered) {
        for (const dep of predecessorsOf(task)) {
            if (dep === task.id || !byId.has(dep))
                continue;
            if (reaches(task.id, dep, adj))
                continue;
            const tos = adj.get(dep);
            if (tos === undefined)
                adj.set(dep, [task.id]);
            else
                tos.push(task.id);
            const froms = incoming.get(task.id);
            if (froms === undefined)
                incoming.set(task.id, [dep]);
            else
                froms.push(dep);
            edges.push({ from: dep, to: task.id });
        }
    }
    const depth = new Map();
    const walk = (id, stack) => {
        const cached = depth.get(id);
        if (cached !== undefined)
            return cached;
        if (stack.has(id))
            return 0;
        stack.add(id);
        const preds = incoming.get(id) ?? [];
        let next = 0;
        if (preds.length > 0) {
            next = 1;
            for (const pred of preds) {
                const candidate = walk(pred, stack) + 1;
                if (candidate > next)
                    next = candidate;
            }
        }
        stack.delete(id);
        depth.set(id, next);
        return next;
    };
    const nodes = tasks.map(task => ({ task, depth: walk(task.id, new Set()) }));
    return { nodes, edges };
}
/**
 * Compact layered layout: one column per depth, rows packed by createdAt.
 */
export function layoutDag(graph, opts) {
    const nodeW = opts?.nodeW ?? DAG_NODE_W;
    const nodeH = opts?.nodeH ?? DAG_NODE_H;
    const gapX = opts?.gapX ?? DAG_GAP_X;
    const gapY = opts?.gapY ?? DAG_GAP_Y;
    const pad = opts?.pad ?? DAG_PAD;
    const columns = new Map();
    let maxDepth = 0;
    for (const node of graph.nodes) {
        if (node.depth > maxDepth)
            maxDepth = node.depth;
        const column = columns.get(node.depth);
        if (column === undefined)
            columns.set(node.depth, [node]);
        else
            column.push(node);
    }
    for (const column of columns.values()) {
        column.sort((left, right) => {
            const created = left.task.createdAt.localeCompare(right.task.createdAt);
            return created !== 0 ? created : left.task.id.localeCompare(right.task.id);
        });
    }
    const boxes = [];
    let height = pad * 2;
    for (const [depth, column] of columns) {
        column.forEach((node, index) => {
            const y = pad + index * (nodeH + gapY);
            boxes.push({
                id: node.task.id,
                task: node.task,
                depth,
                x: pad + depth * (nodeW + gapX),
                y,
                w: nodeW,
                h: nodeH,
            });
            const bottom = y + nodeH + pad;
            if (bottom > height)
                height = bottom;
        });
    }
    const width = graph.nodes.length === 0
        ? pad * 2
        : pad * 2 + (maxDepth + 1) * nodeW + maxDepth * gapX;
    return { boxes, width, height };
}
/**
 * Walk the dependency cone of one task. Upstream follows `dependencies`,
 * downstream follows `dependents`. The focus node itself is omitted.
 */
export function dependencyChainOf(taskId, nodes) {
    const byId = new Map(nodes.map(node => [node.task.id, node.task]));
    const walk = (start, nextOf) => {
        const out = [];
        const seen = new Set([start]);
        const stack = [start];
        while (stack.length > 0) {
            const current = stack.pop();
            if (current === undefined)
                break;
            const task = byId.get(current);
            if (task === undefined)
                continue;
            for (const id of nextOf(task)) {
                if (seen.has(id))
                    continue;
                seen.add(id);
                const next = byId.get(id);
                if (next === undefined)
                    continue;
                out.push(next);
                stack.push(id);
            }
        }
        return out;
    };
    return {
        upstream: walk(taskId, predecessorsOf),
        downstream: walk(taskId, successorsOf),
    };
}
/**
 * Client-side blockedBy: a predecessor is satisfied only when it settled
 * with `outcome === 'success'`. Missing ids stay blocking.
 */
export function blockedByOf(task, tasks) {
    const byId = new Map(tasks.map(item => [item.id, item]));
    return predecessorsOf(task).filter(id => {
        const dep = byId.get(id);
        if (dep === undefined)
            return true;
        return !(dep.status === 'completed' && dep.outcome === 'success');
    });
}
/**
 * Readable copy for a failure that the scheduler propagated down the DAG.
 * Other reasons stay `null` so the view does not invent a dependency badge.
 */
export function failureReasonOf(task) {
    if (task.reason === 'dependency-failed')
        return '依赖失败';
    if (task.reason === 'dependency-canceled')
        return '依赖已取消';
    return null;
}
/** True when any declared predecessor is terminally failed or canceled. */
export function hasFailedDependency(task, tasks) {
    const byId = new Map(tasks.map(item => [item.id, item]));
    return predecessorsOf(task).some(id => {
        const dep = byId.get(id);
        return dep !== undefined && (dep.status === 'failed' || dep.status === 'canceled');
    });
}
/**
 * @deprecated v1.4 uses the explicit `queued` status. Kept for older tests.
 */
export function isReadyUndelivered(task, tasks) {
    return task.status === 'queued' && blockedByOf(task, tasks).length === 0;
}
/** Flows of one workspace: active first, then archived. */
export function flowsOfWorkspace(flows, workspacePath) {
    const scoped = workspacePath === null
        ? [...flows]
        : flows.filter(flow => flow.workspacePath === workspacePath);
    return scoped.sort((left, right) => Number(left.archived) - Number(right.archived));
}
/** Tasks that belong to one flow. Flow-less rows never appear in a DAG. */
export function tasksOfFlow(tasks, flowId) {
    if (flowId === null)
        return [];
    return tasks.filter(task => task.flowId === flowId);
}
/**
 * The DAG's archive rule (v1.4 §6): a terminal failure/cancel/reject leaves
 * the active set IMMEDIATELY, and a settled success leaves after the 24h
 * archive phase. This mirrors the flow-archived derivation (panel flows
 * directory) and the tools' isActiveTask, so a faded node, the flow list,
 * and the agent-visible set never disagree about what is archived.
 */
export function isDagArchived(task) {
    if (task.status === 'failed' || task.status === 'canceled' || task.status === 'rejected') {
        return true;
    }
    return isArchived(task);
}
/**
 * v1.4 §6.1: active tasks plus every recursive predecessor, so an archived
 * ancestor chain stays visible beside live work. Terminal-failed tasks are
 * not anchors but still appear when an active task depends on them.
 * Isolated archived tasks drop off the graph.
 */
export function visibleDagTasks(tasks) {
    const byId = new Map(tasks.map(task => [task.id, task]));
    const keep = new Set();
    const walk = (id) => {
        if (keep.has(id))
            return;
        const task = byId.get(id);
        if (task === undefined)
            return;
        keep.add(id);
        for (const dep of predecessorsOf(task))
            walk(dep);
    };
    for (const task of tasks) {
        if (!isDagArchived(task))
            walk(task.id);
    }
    return tasks.filter(task => keep.has(task.id));
}
/** Archived node on a live chain: faded, not interactive. */
export function isDagFaded(task) {
    return isDagArchived(task);
}
/** Queued with every predecessor settled — the client scheduler may POST /dispatch. */
export function isReadyToDispatch(task, tasks) {
    return task.status === 'queued' && blockedByOf(task, tasks).length === 0;
}
/** Upstream tone: settled success / still open / terminal failure. */
export function chainToneOf(task) {
    if (task.status === 'failed' || task.status === 'canceled')
        return 'fail';
    if (task.status === 'completed' && task.outcome === 'success')
        return 'ok';
    return 'wait';
}
