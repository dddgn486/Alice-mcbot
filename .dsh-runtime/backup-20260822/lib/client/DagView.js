import { jsx as _jsx, jsxs as _jsxs, Fragment as _Fragment } from "react/jsx-runtime";
/**
 * DAG board for the v1.4 flow window: pick a flow, then a canvas of that
 * flow's tasks. Archived ancestors fade; live nodes stay interactive.
 *
 * @module dsh-agent-bus/client/DagView
 */
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { IconCloseOutline16 } from '@deepseek-ai/dsh-client-ui-primitives';
import { blockedByOf, callSteps, chainToneOf, dagOf, dependencyChainOf, failureReasonOf, formatTokenUsage, hasFailedDependency, hasUnreadableTokens, isDagFaded, layoutDag, relativeTime, statusLabel, statusTone, tasksOfFlow, tokensForSession, truncateCodePoints, visibleDagTasks, } from "./panel-model.js";
const ROLE_LABEL = {
    initiator: '发起',
    executor: '执行',
    reviewer: '验收',
};
function badgeKind(task) {
    if (task.status === 'queued')
        return 'dashed';
    if (task.status === 'completed' && task.outcome === null)
        return 'dashed';
    if (task.status === 'canceled')
        return 'outline';
    return 'solid';
}
function reportZoneLabel(zone) {
    if (zone === 'hot')
        return '报告外置·热';
    if (zone === 'cold')
        return '报告外置·冷(已归档)';
    if (zone === 'missing')
        return '报告缺失';
    return null;
}
function TokenTriple({ tokens }) {
    const hint = tokens === null ? undefined : `cache-write ${tokens.cacheWriteTokens}`;
    return _jsx("div", { className: "abPTriple", title: hint, children: formatTokenUsage(tokens) });
}
function StatusDot({ task }) {
    return _jsx("span", { className: "abPDot", "data-tone": statusTone(task.status, task.outcome), "aria-hidden": "true" });
}
function StatusBadge({ task }) {
    return (_jsx("span", { className: "abPBadge", "data-tone": statusTone(task.status, task.outcome), "data-kind": badgeKind(task), children: statusLabel(task.status, task.outcome) }));
}
function nodeMark(task, tasks) {
    if (isDagFaded(task))
        return '已归档';
    const propagated = failureReasonOf(task);
    if (propagated !== null)
        return propagated;
    if (hasFailedDependency(task, tasks))
        return '依赖失败';
    if (blockedByOf(task, tasks).length > 0)
        return '等待依赖';
    return null;
}
const ZOOM_MIN = 0.25;
const ZOOM_MAX = 2.5;
const ZOOM_STEP = 1.12;
const DRAG_PX = 5;
const GRID_PX = 20;
const POS_KEY = 'dsh-agent-bus.dag.pos';
const VIEW_KEY = 'dsh-agent-bus.dag.view';
function clampZoom(zoom) {
    return Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, zoom));
}
function readPos() {
    try {
        const raw = localStorage.getItem(POS_KEY);
        if (raw === null)
            return {};
        const parsed = JSON.parse(raw);
        return parsed !== null && typeof parsed === 'object' ? parsed : {};
    }
    catch {
        return {};
    }
}
function writePos(pos) {
    try {
        localStorage.setItem(POS_KEY, JSON.stringify(pos));
    }
    catch {
        /* private mode */
    }
}
function readView() {
    try {
        const raw = localStorage.getItem(VIEW_KEY);
        if (raw === null)
            return null;
        const parsed = JSON.parse(raw);
        if (typeof parsed.x !== 'number' || typeof parsed.y !== 'number' || typeof parsed.zoom !== 'number') {
            return null;
        }
        return { x: parsed.x, y: parsed.y, zoom: clampZoom(parsed.zoom) };
    }
    catch {
        return null;
    }
}
function writeView(view) {
    try {
        localStorage.setItem(VIEW_KEY, JSON.stringify(view));
    }
    catch {
        /* private mode */
    }
}
function edgePath(from, to) {
    const fromCx = from.x + from.w / 2;
    const fromCy = from.y + from.h / 2;
    const toCx = to.x + to.w / 2;
    const toCy = to.y + to.h / 2;
    const dx = toCx - fromCx;
    const dy = toCy - fromCy;
    if (Math.abs(dx) >= Math.abs(dy)) {
        const rightward = dx >= 0;
        const sx = rightward ? from.x + from.w : from.x;
        const sy = fromCy;
        const tx = rightward ? to.x : to.x + to.w;
        const ty = toCy;
        const curve = Math.max(24, Math.abs(tx - sx) * 0.45);
        const dir = rightward ? 1 : -1;
        return `M ${sx} ${sy} C ${sx + dir * curve} ${sy} ${tx - dir * curve} ${ty} ${tx} ${ty}`;
    }
    const downward = dy >= 0;
    const sx = fromCx;
    const sy = downward ? from.y + from.h : from.y;
    const tx = toCx;
    const ty = downward ? to.y : to.y + to.h;
    const curve = Math.max(24, Math.abs(ty - sy) * 0.45);
    const dir = downward ? 1 : -1;
    return `M ${sx} ${sy} C ${sx} ${sy + dir * curve} ${tx} ${ty - dir * curve} ${tx} ${ty}`;
}
function TaskDetail({ task, nowMs, onClose, }) {
    const zone = reportZoneLabel(task.reportZone);
    const steps = callSteps(task);
    const propagated = failureReasonOf(task);
    const criteria = task.acceptanceCriteria;
    return (_jsxs("article", { className: "abPDagDetail", children: [_jsxs("div", { className: "abPFloatTop", children: [_jsx(StatusDot, { task: task }), _jsxs("div", { className: "abPTaskSummary", children: [_jsx("div", { className: "abPTaskLine", children: _jsx(StatusBadge, { task: task }) }), _jsxs("div", { className: "abPTaskMeta", children: [`任务时间 ${relativeTime(task.updatedMs, nowMs)}`, task.retries > 0 ? ` · 重做 ${task.retries}` : '', task.auto ? ' · 自动派发' : ''] })] }), _jsx("button", { type: "button", className: "abPClose", "aria-label": "\u5173\u95ED\u4EFB\u52A1\u8BE6\u60C5", onClick: onClose, children: _jsx(IconCloseOutline16, { size: 16 }) })] }), propagated !== null && _jsx("div", { className: "abPDagFail", children: propagated }), task.blockedBy.length > 0 && (_jsx("div", { className: "abPTaskMeta", children: `等待依赖 ${task.blockedBy.join(' · ')}` })), _jsxs("div", { className: "abPReq", children: [_jsx("div", { className: "abPStaffHead", children: "\u4EFB\u52A1\u8981\u6C42" }), _jsx("pre", { className: "abPContent", children: task.content }), criteria !== null && criteria !== '' && (_jsxs(_Fragment, { children: [_jsx("div", { className: "abPStaffHead", children: "\u9A8C\u6536\u6807\u51C6" }), _jsx("pre", { className: "abPContent", children: criteria })] }))] }), _jsxs("div", { className: "abPDagMore", children: [_jsxs("div", { className: "abPStaffHead", children: ["\u672C\u4EFB\u52A1\u5408\u8BA1", hasUnreadableTokens(task.staff) ? ' · 部分会话不可读' : ''] }), _jsx(TokenTriple, { tokens: task.taskTokensTotal }), _jsx("div", { className: "abPCalls", "aria-label": "\u8C03\u7528\u8FC7\u7A0B", children: steps.map((step, index) => (_jsxs("div", { className: "abPCall", children: [_jsxs("div", { className: "abPCallHead", children: [_jsx("span", { className: "abPCallWho", children: step.from.title }), _jsx("span", { className: "abPChainArrow", "aria-hidden": "true", children: "\u2192" }), _jsx("span", { className: "abPCallWho", children: step.to.title }), _jsx("span", { className: "abPCallRoles", children: `${ROLE_LABEL[step.from.role]} · ${ROLE_LABEL[step.to.role]}` })] }), _jsx("div", { className: "abPCallSummary", children: step.summary }), _jsxs("div", { className: "abPCallCost", children: [_jsx("span", { className: "abPCallCostName", children: step.from.title }), _jsx(TokenTriple, { tokens: tokensForSession(task, step.from.sessionId) })] }), _jsxs("div", { className: "abPCallCost", children: [_jsx("span", { className: "abPCallCostName", children: step.to.title }), _jsx(TokenTriple, { tokens: tokensForSession(task, step.to.sessionId) })] })] }, `${step.from.sessionId}:${step.to.sessionId}:${index}`))) }), zone !== null && (_jsx("div", { className: "abPZone", "data-missing": task.reportZone === 'missing' || undefined, children: zone }))] })] }));
}
/**
 * Session rail + layered DAG canvas + detail strip.
 * Empty-canvas drag pans; wheel zooms toward the cursor; nodes drag freely.
 */
export function DagView({ tasks, flows, selectedFlowId, onSelectFlow, sidebarWidth, onSidebarResizeDown, onSidebarResizeMove, onSidebarResizeUp, nowMs, consumeEscRef, }) {
    const flowTasks = useMemo(() => visibleDagTasks(tasksOfFlow(tasks, selectedFlowId)), [tasks, selectedFlowId]);
    const graph = useMemo(() => dagOf(flowTasks), [flowTasks]);
    const layout = useMemo(() => layoutDag(graph), [graph]);
    const [pos, setPos] = useState(readPos);
    const posRef = useRef(pos);
    posRef.current = pos;
    const [view, setView] = useState(() => readView() ?? { x: 24, y: 24, zoom: 1 });
    const viewRef = useRef(view);
    viewRef.current = view;
    const canvasRef = useRef(null);
    const [hoverId, setHoverId] = useState(null);
    const [pinnedId, setPinnedId] = useState(null);
    const [detailId, setDetailId] = useState(null);
    const [flare, setFlare] = useState(() => new Set());
    const [panning, setPanning] = useState(false);
    const [draggingId, setDraggingId] = useState(null);
    const seenSuccess = useRef(null);
    const panDrag = useRef(null);
    const nodeDrag = useRef(null);
    const boxes = useMemo(() => layout.boxes.map(box => {
        const placed = pos[box.id];
        return placed === undefined ? box : { ...box, x: placed.x, y: placed.y };
    }), [layout.boxes, pos]);
    const byId = useMemo(() => new Map(boxes.map(box => [box.id, box])), [boxes]);
    const taskById = useMemo(() => new Map(layout.boxes.map(box => [box.id, box.task])), [layout.boxes]);
    const bbox = useMemo(() => {
        if (boxes.length === 0)
            return { x: 0, y: 0, w: 1, h: 1 };
        let minX = Infinity;
        let minY = Infinity;
        let maxX = -Infinity;
        let maxY = -Infinity;
        for (const box of boxes) {
            minX = Math.min(minX, box.x);
            minY = Math.min(minY, box.y);
            maxX = Math.max(maxX, box.x + box.w);
            maxY = Math.max(maxY, box.y + box.h);
        }
        const pad = 80;
        return { x: minX - pad, y: minY - pad, w: maxX - minX + pad * 2, h: maxY - minY + pad * 2 };
    }, [boxes]);
    const setViewLive = (next, persist) => {
        viewRef.current = next;
        setView(next);
        if (persist)
            writeView(next);
    };
    const persistPos = (next) => {
        posRef.current = next;
        setPos(next);
        writePos(next);
    };
    const fitTo = (items) => {
        const el = canvasRef.current;
        if (el === null || items.length === 0)
            return;
        let minX = Infinity;
        let minY = Infinity;
        let maxX = -Infinity;
        let maxY = -Infinity;
        for (const box of items) {
            minX = Math.min(minX, box.x);
            minY = Math.min(minY, box.y);
            maxX = Math.max(maxX, box.x + box.w);
            maxY = Math.max(maxY, box.y + box.h);
        }
        const pad = 80;
        const area = { x: minX - pad, y: minY - pad, w: maxX - minX + pad * 2, h: maxY - minY + pad * 2 };
        const rect = el.getBoundingClientRect();
        const inset = 28;
        const scale = Math.min((rect.width - inset * 2) / Math.max(area.w, 1), (rect.height - inset * 2) / Math.max(area.h, 1), 1.25);
        const zoom = clampZoom(scale);
        setViewLive({
            zoom,
            x: (rect.width - area.w * zoom) / 2 - area.x * zoom,
            y: (rect.height - area.h * zoom) / 2 - area.y * zoom,
        }, true);
    };
    const fitView = () => {
        fitTo(boxes);
    };
    const zoomBy = (factor, origin) => {
        const el = canvasRef.current;
        const current = viewRef.current;
        const nextZoom = clampZoom(current.zoom * factor);
        if (nextZoom === current.zoom)
            return;
        const rect = el?.getBoundingClientRect();
        const sx = origin?.x ?? (rect === undefined ? 0 : rect.width / 2);
        const sy = origin?.y ?? (rect === undefined ? 0 : rect.height / 2);
        const worldX = (sx - current.x) / current.zoom;
        const worldY = (sy - current.y) / current.zoom;
        setViewLive({
            zoom: nextZoom,
            x: sx - worldX * nextZoom,
            y: sy - worldY * nextZoom,
        }, true);
    };
    const resetLayout = () => {
        const next = {};
        for (const box of layout.boxes)
            next[box.id] = { x: box.x, y: box.y };
        persistPos(next);
        fitTo(layout.boxes);
    };
    useEffect(() => {
        setPos(current => {
            let changed = false;
            const next = { ...current };
            for (const box of layout.boxes) {
                if (next[box.id] !== undefined)
                    continue;
                next[box.id] = { x: box.x, y: box.y };
                changed = true;
            }
            if (!changed)
                return current;
            writePos(next);
            return next;
        });
    }, [layout.boxes]);
    useLayoutEffect(() => {
        if (boxes.length === 0)
            return;
        fitTo(boxes);
    }, [selectedFlowId]);
    useEffect(() => {
        const el = canvasRef.current;
        if (el === null)
            return;
        const onWheel = (event) => {
            event.preventDefault();
            const rect = el.getBoundingClientRect();
            const factor = event.deltaY < 0 ? ZOOM_STEP : 1 / ZOOM_STEP;
            zoomBy(factor, { x: event.clientX - rect.left, y: event.clientY - rect.top });
        };
        el.addEventListener('wheel', onWheel, { passive: false });
        return () => el.removeEventListener('wheel', onWheel);
    }, [flowTasks.length]);
    const focusId = pinnedId ?? hoverId;
    const chain = useMemo(() => {
        if (focusId === null)
            return null;
        return dependencyChainOf(focusId, graph.nodes);
    }, [focusId, graph.nodes]);
    const chainTone = useMemo(() => {
        const tones = new Map();
        if (focusId === null || chain === null)
            return tones;
        tones.set(focusId, 'self');
        for (const task of chain.upstream)
            tones.set(task.id, chainToneOf(task));
        for (const task of chain.downstream)
            tones.set(task.id, 'down');
        return tones;
    }, [focusId, chain]);
    const detailTask = detailId === null ? null : (taskById.get(detailId) ?? null);
    useEffect(() => {
        consumeEscRef.current = () => {
            if (pinnedId !== null || detailId !== null) {
                setPinnedId(null);
                setDetailId(null);
                setHoverId(null);
                return true;
            }
            return false;
        };
        return () => {
            consumeEscRef.current = null;
        };
    }, [consumeEscRef, pinnedId, detailId]);
    useEffect(() => {
        const success = new Set(flowTasks.filter(task => task.status === 'completed' && task.outcome === 'success').map(task => task.id));
        const previous = seenSuccess.current;
        seenSuccess.current = success;
        if (previous === null)
            return;
        const released = new Set();
        for (const id of success) {
            if (previous.has(id))
                continue;
            const task = taskById.get(id);
            for (const dep of task?.dependents ?? [])
                released.add(dep);
        }
        if (released.size === 0)
            return;
        setFlare(current => new Set([...current, ...released]));
        const timer = window.setTimeout(() => {
            setFlare(current => {
                const next = new Set(current);
                for (const id of released)
                    next.delete(id);
                return next;
            });
        }, 2000);
        return () => window.clearTimeout(timer);
    }, [flowTasks, taskById]);
    const onCanvasPointerDown = (event) => {
        if (event.button !== 0)
            return;
        const target = event.target;
        if (!(target instanceof Element))
            return;
        if (target.closest('.abPDagNode') !== null || target.closest('.abPDagTools') !== null)
            return;
        event.preventDefault();
        event.currentTarget.setPointerCapture(event.pointerId);
        panDrag.current = {
            startX: event.clientX,
            startY: event.clientY,
            origX: viewRef.current.x,
            origY: viewRef.current.y,
        };
        setPanning(true);
    };
    const onCanvasPointerMove = (event) => {
        const pan = panDrag.current;
        if (pan === null)
            return;
        setViewLive({
            zoom: viewRef.current.zoom,
            x: pan.origX + event.clientX - pan.startX,
            y: pan.origY + event.clientY - pan.startY,
        }, false);
    };
    const onCanvasPointerUp = () => {
        if (panDrag.current !== null)
            writeView(viewRef.current);
        panDrag.current = null;
        setPanning(false);
    };
    const onNodePointerDown = (event, id) => {
        if (event.button !== 0)
            return;
        const task = taskById.get(id);
        if (task !== undefined && isDagFaded(task))
            return;
        event.stopPropagation();
        event.currentTarget.setPointerCapture(event.pointerId);
        const box = byId.get(id);
        nodeDrag.current = {
            id,
            startX: event.clientX,
            startY: event.clientY,
            origX: box?.x ?? 0,
            origY: box?.y ?? 0,
            moved: false,
        };
        setHoverId(id);
    };
    const onNodePointerMove = (event) => {
        const drag = nodeDrag.current;
        if (drag === null)
            return;
        const zoom = viewRef.current.zoom;
        const dx = (event.clientX - drag.startX) / zoom;
        const dy = (event.clientY - drag.startY) / zoom;
        if (!drag.moved && (dx * dx + dy * dy) * zoom * zoom < DRAG_PX * DRAG_PX)
            return;
        drag.moved = true;
        setDraggingId(drag.id);
        const next = {
            ...posRef.current,
            [drag.id]: { x: drag.origX + dx, y: drag.origY + dy },
        };
        posRef.current = next;
        setPos(next);
    };
    const onNodePointerUp = (id) => {
        const drag = nodeDrag.current;
        nodeDrag.current = null;
        setDraggingId(null);
        if (drag === null || drag.id !== id)
            return;
        if (drag.moved) {
            writePos(posRef.current);
            return;
        }
        setPinnedId(current => current === id ? null : id);
        setDetailId(id);
    };
    const activeFlows = flows.filter(flow => !flow.archived);
    const archivedFlows = flows.filter(flow => flow.archived);
    const selectedFlow = flows.find(flow => flow.id === selectedFlowId) ?? null;
    const grid = GRID_PX * view.zoom;
    return (_jsxs("div", { className: "abPBody", children: [_jsxs("nav", { className: "abPSessions", "aria-label": "\u6D41\u7A0B", style: { width: sidebarWidth }, children: [_jsx("div", { className: "abPResize", role: "separator", "aria-orientation": "vertical", "aria-label": "\u8C03\u8282\u4FA7\u680F\u5BBD\u5EA6", onPointerDown: onSidebarResizeDown, onPointerMove: onSidebarResizeMove, onPointerUp: onSidebarResizeUp, onPointerCancel: onSidebarResizeUp }), _jsx("div", { className: "abPFlowHead", children: "\u6D3B\u8DC3" }), _jsxs("div", { className: "abPFlowList", children: [activeFlows.length === 0 && (_jsx("div", { className: "abPFlowEmpty", children: "\u6682\u65E0\u6D3B\u8DC3\u6D41\u7A0B" })), activeFlows.map(flow => (_jsxs("button", { type: "button", className: "abPFlow", "data-active": selectedFlowId === flow.id || undefined, onClick: () => onSelectFlow(flow.id), children: [_jsx("span", { className: "abPFlowName", children: flow.name }), _jsx("span", { className: "abPFlowCount", children: flow.unsettledCount })] }, flow.id)))] }), archivedFlows.length > 0 && (_jsxs("div", { className: "abPGroup", children: [_jsx("div", { className: "abPFlowHead", children: "\u5F52\u6863" }), _jsx("div", { className: "abPFlowList", children: archivedFlows.map(flow => (_jsx("button", { type: "button", className: "abPFlow", "data-active": selectedFlowId === flow.id || undefined, "data-archived": true, onClick: () => onSelectFlow(flow.id), children: _jsx("span", { className: "abPFlowName", children: flow.name }) }, flow.id))) })] }))] }), _jsxs("div", { className: "abPDagPane", children: [selectedFlow === null ? (_jsxs("div", { className: "abPEmpty", children: [_jsx("div", { className: "abPEmptyTitle", children: "\u9009\u62E9\u4E00\u4E2A\u6D41\u7A0B" }), _jsx("div", { className: "abPEmptyHint", children: "\u5DE6\u4FA7\u70B9\u9009\u540E\uFF0C\u8FD9\u91CC\u4F1A\u753B\u51FA\u5B83\u7684\u4EFB\u52A1\u56FE" })] })) : flowTasks.length === 0 ? (_jsxs("div", { className: "abPEmpty", children: [_jsx("div", { className: "abPEmptyTitle", children: `${selectedFlow.name} 还没有任务` }), _jsx("div", { className: "abPEmptyHint", children: "\u5F52\u5165\u8FD9\u4E2A\u6D41\u7A0B\u7684\u4EFB\u52A1\u4F1A\u51FA\u73B0\u5728\u753B\u5E03\u4E0A" })] })) : (_jsxs("div", { ref: canvasRef, className: "abPDagCanvas", "data-panning": panning || undefined, "data-dragging": draggingId !== null || undefined, style: {
                            backgroundSize: `${grid}px ${grid}px`,
                            backgroundPosition: `${view.x}px ${view.y}px`,
                        }, onPointerDown: onCanvasPointerDown, onPointerMove: onCanvasPointerMove, onPointerUp: onCanvasPointerUp, onPointerCancel: onCanvasPointerUp, onDoubleClick: event => {
                            if (event.target instanceof Element && event.target.closest('.abPDagNode') !== null)
                                return;
                            fitView();
                        }, onMouseLeave: () => {
                            if (draggingId === null)
                                setHoverId(null);
                        }, children: [_jsxs("div", { className: "abPDagWorld", style: { transform: `translate(${view.x}px, ${view.y}px) scale(${view.zoom})` }, children: [_jsxs("svg", { className: "abPDagSvg", width: bbox.w, height: bbox.h, viewBox: `${bbox.x} ${bbox.y} ${bbox.w} ${bbox.h}`, style: { left: bbox.x, top: bbox.y }, "aria-hidden": "true", children: [_jsx("defs", { children: _jsx("marker", { id: "abPDagArrow", markerWidth: "8", markerHeight: "8", refX: "7", refY: "4", orient: "auto", children: _jsx("path", { d: "M 0 0 L 8 4 L 0 8 z", fill: "currentColor" }) }) }), graph.edges.map(edge => {
                                                const from = byId.get(edge.from);
                                                const to = byId.get(edge.to);
                                                if (from === undefined || to === undefined)
                                                    return null;
                                                const inChain = focusId !== null && chainTone.has(edge.from) && chainTone.has(edge.to);
                                                const tone = inChain ? (chainTone.get(edge.from) ?? 'wait') : undefined;
                                                return (_jsx("path", { className: "abPDagEdge", d: edgePath(from, to), "data-tone": tone, "data-dim": focusId !== null && !inChain || undefined, markerEnd: "url(#abPDagArrow)" }, `${edge.from}:${edge.to}`));
                                            })] }), boxes.map(box => {
                                        const faded = isDagFaded(box.task);
                                        const mark = nodeMark(box.task, flowTasks);
                                        const ready = box.task.status === 'queued';
                                        const failedDep = hasFailedDependency(box.task, flowTasks) || failureReasonOf(box.task) !== null;
                                        const blocked = blockedByOf(box.task, flowTasks).length > 0;
                                        const settledOk = box.task.status === 'completed' && box.task.outcome === 'success';
                                        const tone = chainTone.get(box.id);
                                        const dim = !faded && focusId !== null && tone === undefined;
                                        return (_jsxs("button", { type: "button", className: "abPDagNode", style: { left: box.x, top: box.y, width: box.w, height: box.h }, "data-tone": statusTone(box.task.status, box.task.outcome), "data-chain": tone, "data-blocked": blocked || undefined, "data-ready": ready || undefined, "data-fail": failedDep || undefined, "data-ok": settledOk || undefined, "data-flare": flare.has(box.id) || undefined, "data-dim": dim || undefined, "data-archived": faded || undefined, "data-dragging": draggingId === box.id || undefined, disabled: faded, "aria-pressed": pinnedId === box.id, "aria-label": `${statusLabel(box.task.status, box.task.outcome)} ${box.task.title ?? box.task.contentPreview}`, onMouseEnter: () => { if (!faded)
                                                setHoverId(box.id); }, onFocus: () => { if (!faded)
                                                setHoverId(box.id); }, onPointerDown: event => onNodePointerDown(event, box.id), onPointerMove: onNodePointerMove, onPointerUp: () => onNodePointerUp(box.id), onPointerCancel: () => onNodePointerUp(box.id), children: [_jsxs("span", { className: "abPDagNodeTop", children: [_jsx(StatusDot, { task: box.task }), _jsx(StatusBadge, { task: box.task })] }), _jsx("span", { className: "abPDagNodeLabel", children: truncateCodePoints(box.task.title ?? box.task.contentPreview, 18) }), mark !== null && _jsx("span", { className: "abPDagMark", children: mark })] }, box.id));
                                    })] }), _jsxs("div", { className: "abPDagTools", onPointerDown: event => event.stopPropagation(), children: [_jsx("button", { type: "button", className: "abPDagTool", "aria-label": "\u7F29\u5C0F", onClick: () => zoomBy(1 / ZOOM_STEP), children: "\u2212" }), _jsx("span", { className: "abPDagZoom", children: `${Math.round(view.zoom * 100)}%` }), _jsx("button", { type: "button", className: "abPDagTool", "aria-label": "\u653E\u5927", onClick: () => zoomBy(ZOOM_STEP), children: "+" }), _jsx("button", { type: "button", className: "abPDagTool", "aria-label": "\u9002\u5E94\u753B\u5E03", onClick: fitView, children: "\u9002\u5E94" }), _jsx("button", { type: "button", className: "abPDagTool", "aria-label": "\u6062\u590D\u81EA\u52A8\u5E03\u5C40", onClick: resetLayout, children: "\u590D\u4F4D" })] })] })), detailTask !== null && (_jsx(TaskDetail, { task: detailTask, nowMs: nowMs, onClose: () => {
                            setDetailId(null);
                            setPinnedId(null);
                        } }))] })] }));
}
