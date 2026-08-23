window.__ModuleLoader__.load({
	id: "dsh-agent-bus",
	factory: (require) => {
		var module = { exports: {} };
		var exports = module.exports;
		Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" });
		let react_jsx_runtime = require("react/jsx-runtime");
		let react_dom_client = require("react-dom/client");
		let react = require("react");
		let _deepseek_ai_dsh_client_ui_primitives = require("@deepseek-ai/dsh-client-ui-primitives");
		//#region lib/client/AgentBusToolRow.js
		/**
		* Collapsed tool row for the agent-bus tools.
		*
		* Replaces the generic "Tool call" card in the conversation for every
		* agent-bus tool: one always-collapsed line names the producer
		* (`agent-bus-task`) and the act (dispatch / review / cancel / …) with the
		* salient id; the full arguments stay inside the disclosure, opened on
		* demand — mirroring the context-injection rows so the state-machine traffic
		* never reads as a loud tool call.
		*
		* @module dsh-agent-bus/client/AgentBusToolRow
		*/
		/** Act labels per wire tool name, kept short for the collapsed line. */
		const ACT_LABELS = {
			list_peers: "发现 peer",
			send_note: "发送消息",
			create_flow: "创建流程",
			list_flows: "流程列表",
			create_task: "创建任务",
			dispatch_task: "派发任务",
			edit_task: "改任务",
			list_tasks: "任务列表",
			get_task: "读取任务",
			report_task: "提交结果",
			settle_task: "验收",
			cancel_task: "取消任务",
			request_input: "请求输入",
			update_card: "更新卡片"
		};
		/** Extract the salient argument for the collapsed summary. */
		function salient(args) {
			if (typeof args !== "object" || args === null) return "";
			const record = args;
			for (const key of [
				"task_id",
				"target",
				"scope",
				"outcome"
			]) {
				const value = record[key];
				if (typeof value === "string" && value !== "") return `${key}=${value.length > 13 ? `${value.slice(0, 13)}…` : value}`;
			}
			return "";
		}
		/** Render one agent-bus tool call as a collapsed producer row. */
		function AgentBusToolRow({ block, toolName }) {
			const [open, setOpen] = (0, react.useState)(false);
			const argsRaw = ("kind" in block ? block.call?.argsRaw : block.argsRaw) ?? "";
			let args;
			try {
				args = argsRaw === "" ? void 0 : JSON.parse(argsRaw);
			} catch {
				args = argsRaw;
			}
			const act = ACT_LABELS[toolName] ?? toolName;
			const key = salient(args);
			const summary = key === "" ? act : `${act} · ${key}`;
			return (0, react_jsx_runtime.jsx)(_deepseek_ai_dsh_client_ui_primitives.DisclosureRow, {
				icon: (0, react_jsx_runtime.jsx)(_deepseek_ai_dsh_client_ui_primitives.IconBrowseOutline16, { size: 14 }),
				title: "agent-bus-task",
				collapsedContent: summary,
				keepContentWhenOpen: true,
				open,
				expandable: true,
				onToggle: () => setOpen((previous) => !previous),
				children: (0, react_jsx_runtime.jsx)("pre", {
					style: {
						margin: 0,
						whiteSpace: "pre-wrap",
						wordBreak: "break-all"
					},
					children: argsRaw === "" ? "(no arguments)" : JSON.stringify(args, null, 2)
				})
			});
		}
		//#endregion
		//#region lib/client/panel-model.js
		/**
		* Pure view-model helpers for the v1.1 read-only task panel.
		*
		* Snapshot shapes match docs/v1.1-task-panel-spec.md §3.6–3.7 / §4.4.
		* No I/O, no React — every export is unit-tested from tests/panel-model.test.ts.
		*
		* @module dsh-agent-bus/client/panel-model
		*/
		const MINUTE_MS = 6e4;
		const HOUR_MS = 60 * MINUTE_MS;
		const DAY_MS = 24 * HOUR_MS;
		const WEEK_MS = 7 * DAY_MS;
		const EMPTY_STATS = {
			queued: 0,
			submitted: 0,
			working: 0,
			"input-required": 0,
			completed: 0,
			failed: 0,
			canceled: 0,
			total: 0
		};
		/**
		* Format a snapshot-relative age as a Chinese relative-time string.
		*
		* @param updatedMs - milliseconds since `updatedAt` (snapshot `updatedMs`).
		* @param nowMs - clock used only for the absolute `yyyy-mm-dd` fallback.
		*/
		function relativeTime(updatedMs, nowMs) {
			if (updatedMs < MINUTE_MS) return "刚刚";
			if (updatedMs < HOUR_MS) return `${Math.floor(updatedMs / MINUTE_MS)} 分钟前`;
			if (updatedMs < DAY_MS) return `${Math.floor(updatedMs / HOUR_MS)} 小时前`;
			if (updatedMs < WEEK_MS) return `${Math.floor(updatedMs / DAY_MS)} 天前`;
			const date = new Date(nowMs - updatedMs);
			return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
		}
		/** Terminal settled tasks — the archive list. */
		function settledTasks(tasks) {
			return tasks.filter((task) => task.settled);
		}
		/**
		* Restrict tasks to those a session participates in.
		*
		* @param sessionId - `null` keeps every task.
		*/
		function tasksOfSession(tasks, sessionId) {
			if (sessionId === null) return [...tasks];
			return tasks.filter((task) => task.assignedBy === sessionId || task.assignedTo === sessionId || task.assignedReviewer === sessionId);
		}
		/**
		* Restrict tasks to one workspace path.
		*
		* @param workspacePath - `null` keeps every task.
		*/
		function tasksOfWorkspace(tasks, workspacePath) {
			if (workspacePath === null) return [...tasks];
			return tasks.filter((task) => task.workspacePath === workspacePath);
		}
		/** Settled tasks, newest-updated first so the archive reads as history. */
		function sortSettled(tasks) {
			return settledTasks(tasks).sort((left, right) => {
				const updated = right.updatedAt.localeCompare(left.updatedAt);
				if (updated !== 0) return updated;
				return right.createdAt.localeCompare(left.createdAt);
			});
		}
		/**
		* Distinct agents that participated in settled tasks, live first.
		* Titles prefer the session directory, then the task's resolved names.
		*/
		function archiveAgents(tasks, sessions) {
			const byId = new Map(sessions.map((session) => [session.id, session]));
			const seen = /* @__PURE__ */ new Map();
			const consider = (sessionId, fallbackTitle) => {
				if (sessionId === null || sessionId === "" || seen.has(sessionId)) return;
				const session = byId.get(sessionId);
				seen.set(sessionId, {
					sessionId,
					title: session?.title ?? fallbackTitle ?? sessionId.slice(0, 8),
					live: session?.live ?? false
				});
			};
			for (const task of settledTasks(tasks)) {
				consider(task.assignedTo, task.toTitle);
				consider(task.assignedReviewer, task.reviewerTitle);
				consider(task.assignedBy, task.byTitle);
			}
			return [...seen.values()].sort((left, right) => Number(right.live) - Number(left.live));
		}
		/**
		* Truncate by Unicode code point so a surrogate pair (emoji) is never split.
		* Overflow is marked with a single `…`.
		*/
		function truncateCodePoints(text, max) {
			if (max <= 0) return text === "" ? "" : "…";
			const points = Array.from(text);
			if (points.length <= max) return text;
			return `${points.slice(0, max).join("")}…`;
		}
		/**
		* Cache-hit rate: cache-read / (cache-read + uncached input).
		* Missing when there is no input of either kind.
		*/
		function cacheHitPercent(tokens) {
			const denom = tokens.cacheReadTokens + tokens.uncachedInputTokens;
			if (denom <= 0) return null;
			return Math.round(tokens.cacheReadTokens / denom * 100);
		}
		/**
		* Invocation order for one task: initiator → executor → reviewer.
		* Executor is omitted when the task has no assignee yet.
		*/
		function callChain(task) {
			const hops = [{
				sessionId: task.assignedBy,
				title: task.byTitle,
				role: "initiator"
			}];
			if (task.assignedTo !== null && task.assignedTo !== "") hops.push({
				sessionId: task.assignedTo,
				title: task.toTitle ?? task.assignedTo.slice(0, 8),
				role: "executor"
			});
			const reviewerId = task.assignedReviewer ?? task.assignedBy;
			hops.push({
				sessionId: reviewerId,
				title: task.reviewerTitle ?? task.byTitle,
				role: "reviewer"
			});
			return hops;
		}
		/**
		* Expand the hop list into consecutive calls.
		* Dispatch uses the task instruction; review uses feedback / question.
		*/
		function callSteps(task) {
			const hops = callChain(task);
			const steps = [];
			for (let index = 0; index < hops.length - 1; index += 1) {
				const from = hops[index];
				const to = hops[index + 1];
				if (from === void 0 || to === void 0) continue;
				const summary = from.role === "executor" && to.role === "reviewer" ? task.feedback !== null && task.feedback !== "" ? task.feedback : task.question !== null && task.question !== "" ? task.question : "提交验收" : "派发";
				steps.push({
					from,
					to,
					summary
				});
			}
			return steps;
		}
		/** Task-period tokens for one participant, or null when unread. */
		function tokensForSession(task, sessionId) {
			return task.staff.find((entry) => entry.sessionId === sessionId)?.tokensInTask ?? null;
		}
		function formatTokenUsage(tokens) {
			if (tokens === null) return "缓存命中 — · 输入 — · 输出 —";
			const percent = cacheHitPercent(tokens);
			return `缓存命中 ${percent === null ? "—" : `${percent}%`} · 输入 ${formatNumber(tokens.uncachedInputTokens)} · 输出 ${formatNumber(tokens.outputTokens)}`;
		}
		/** Thousand-separated integer (en-US grouping, ASCII digits). */
		function formatNumber(n) {
			return Math.round(n).toLocaleString("en-US");
		}
		/**
		* Chinese status badge copy.
		*
		* `completed` without an outcome is 「待验收」; a recorded outcome is 「已完成」.
		*/
		function statusLabel(status, outcome) {
			switch (status) {
				case "queued": return "待投递";
				case "submitted": return "待执行";
				case "working": return "进行中";
				case "input-required": return "等待输入";
				case "completed": return outcome === null || outcome === void 0 ? "待验收" : "已完成";
				case "failed": return "失败";
				case "canceled": return "已取消";
				case "archived": return "已归档";
				case "auth-required": return "待授权";
				case "rejected": return "已拒绝";
			}
		}
		/**
		* Status color tone. `completed` is warning while awaiting a verdict and
		* success once an outcome is recorded; the optional `outcome` exists because
		* the two completed presentations do not share a color.
		*/
		function statusTone(status, outcome) {
			switch (status) {
				case "working": return "business";
				case "input-required": return "warning";
				case "completed": return outcome === "success" || outcome === "failure" ? "success" : "warning";
				case "failed": return "danger";
				case "queued":
				case "submitted":
				case "canceled":
				case "archived":
				case "auth-required":
				case "rejected": return "tertiary";
			}
		}
		/** Sessions of one workspace, live rows first, original order otherwise preserved. */
		function sessionsOfWorkspace(sessions, workspaceId) {
			return (workspaceId === null ? [...sessions] : sessions.filter((session) => session.workspaceId === workspaceId)).sort((left, right) => Number(right.live) - Number(left.live));
		}
		/** Host flag, dedicated status, or completed/settled older than 24h. */
		function isArchived(task) {
			if (task.archived === true || task.status === "archived") return true;
			return task.settled && task.updatedMs >= 864e5;
		}
		/**
		* Active tab: in-progress (not settled) plus 已完成 that is not yet archived.
		* Failed / canceled rows go to archive; they are not "进行中" or "已完成".
		* An offline executor cannot hold active work, so its rows are
		* archive-bound immediately.
		*/
		function activeTabTasks(tasks) {
			return tasks.filter((task) => {
				if (!task.executorLive) return false;
				if (isArchived(task)) return false;
				if (!task.settled) return true;
				return task.status === "completed";
			});
		}
		/**
		* Archive tab: everything of offline executors, host-archived rows,
		* completed-over-24h, and other terminals (failed / canceled) that are not
		* shown as active work.
		*/
		function archiveTabTasks(tasks) {
			return tasks.filter((task) => !task.executorLive || isArchived(task) || task.settled && task.status !== "completed");
		}
		/** Active-tab order: in-progress first (oldest update), then 已完成 (newest). */
		function sortActive(tasks) {
			return [...tasks].sort((left, right) => {
				const leftDone = left.settled ? 1 : 0;
				const rightDone = right.settled ? 1 : 0;
				if (leftDone !== rightDone) return leftDone - rightDone;
				if (!left.settled) {
					const updated = left.updatedAt.localeCompare(right.updatedAt);
					return updated !== 0 ? updated : left.createdAt.localeCompare(right.createdAt);
				}
				const updated = right.updatedAt.localeCompare(left.updatedAt);
				return updated !== 0 ? updated : right.createdAt.localeCompare(left.createdAt);
			});
		}
		/** True when any staff row is missing a task-period token delta. */
		function hasUnreadableTokens(staff) {
			return staff.some((entry) => entry.tokensInTask === null);
		}
		const EMPTY_SNAPSHOT = {
			workspaces: [],
			sessions: [],
			tasks: [],
			flows: [],
			stats: EMPTY_STATS
		};
		/** Empty snapshot used before the first successful poll (and on hard failure). */
		function emptySnapshot() {
			return EMPTY_SNAPSHOT;
		}
		function predecessorsOf(task) {
			return task.dependencies ?? [];
		}
		function successorsOf(task) {
			return task.dependents ?? [];
		}
		function reaches(from, target, adj) {
			const stack = [from];
			const seen = /* @__PURE__ */ new Set();
			while (stack.length > 0) {
				const current = stack.pop();
				if (current === void 0) break;
				if (current === target) return true;
				if (seen.has(current)) continue;
				seen.add(current);
				const next = adj.get(current);
				if (next === void 0) continue;
				for (const id of next) stack.push(id);
			}
			return false;
		}
		/**
		* Build the workspace DAG: nodes keep topological depth (longest path from
		* a root); edges run predecessor → dependent. Cycles are skipped defensively
		* (the ledger already rejects them on write). Isolated tasks sit at depth 0.
		*/
		function dagOf(tasks) {
			const byId = new Map(tasks.map((task) => [task.id, task]));
			const adj = /* @__PURE__ */ new Map();
			const incoming = /* @__PURE__ */ new Map();
			const edges = [];
			const ordered = [...tasks].sort((left, right) => {
				const created = left.createdAt.localeCompare(right.createdAt);
				return created !== 0 ? created : left.id.localeCompare(right.id);
			});
			for (const task of ordered) for (const dep of predecessorsOf(task)) {
				if (dep === task.id || !byId.has(dep)) continue;
				if (reaches(task.id, dep, adj)) continue;
				const tos = adj.get(dep);
				if (tos === void 0) adj.set(dep, [task.id]);
				else tos.push(task.id);
				const froms = incoming.get(task.id);
				if (froms === void 0) incoming.set(task.id, [dep]);
				else froms.push(dep);
				edges.push({
					from: dep,
					to: task.id
				});
			}
			const depth = /* @__PURE__ */ new Map();
			const walk = (id, stack) => {
				const cached = depth.get(id);
				if (cached !== void 0) return cached;
				if (stack.has(id)) return 0;
				stack.add(id);
				const preds = incoming.get(id) ?? [];
				let next = 0;
				if (preds.length > 0) {
					next = 1;
					for (const pred of preds) {
						const candidate = walk(pred, stack) + 1;
						if (candidate > next) next = candidate;
					}
				}
				stack.delete(id);
				depth.set(id, next);
				return next;
			};
			return {
				nodes: tasks.map((task) => ({
					task,
					depth: walk(task.id, /* @__PURE__ */ new Set())
				})),
				edges
			};
		}
		/**
		* Compact layered layout: one column per depth, rows packed by createdAt.
		*/
		function layoutDag(graph, opts) {
			const nodeW = opts?.nodeW ?? 176;
			const nodeH = opts?.nodeH ?? 64;
			const gapX = opts?.gapX ?? 64;
			const gapY = opts?.gapY ?? 22;
			const pad = opts?.pad ?? 20;
			const columns = /* @__PURE__ */ new Map();
			let maxDepth = 0;
			for (const node of graph.nodes) {
				if (node.depth > maxDepth) maxDepth = node.depth;
				const column = columns.get(node.depth);
				if (column === void 0) columns.set(node.depth, [node]);
				else column.push(node);
			}
			for (const column of columns.values()) column.sort((left, right) => {
				const created = left.task.createdAt.localeCompare(right.task.createdAt);
				return created !== 0 ? created : left.task.id.localeCompare(right.task.id);
			});
			const boxes = [];
			let height = pad * 2;
			for (const [depth, column] of columns) column.forEach((node, index) => {
				const y = pad + index * (nodeH + gapY);
				boxes.push({
					id: node.task.id,
					task: node.task,
					depth,
					x: pad + depth * (nodeW + gapX),
					y,
					w: nodeW,
					h: nodeH
				});
				const bottom = y + nodeH + pad;
				if (bottom > height) height = bottom;
			});
			return {
				boxes,
				width: graph.nodes.length === 0 ? pad * 2 : pad * 2 + (maxDepth + 1) * nodeW + maxDepth * gapX,
				height
			};
		}
		/**
		* Walk the dependency cone of one task. Upstream follows `dependencies`,
		* downstream follows `dependents`. The focus node itself is omitted.
		*/
		function dependencyChainOf(taskId, nodes) {
			const byId = new Map(nodes.map((node) => [node.task.id, node.task]));
			const walk = (start, nextOf) => {
				const out = [];
				const seen = /* @__PURE__ */ new Set([start]);
				const stack = [start];
				while (stack.length > 0) {
					const current = stack.pop();
					if (current === void 0) break;
					const task = byId.get(current);
					if (task === void 0) continue;
					for (const id of nextOf(task)) {
						if (seen.has(id)) continue;
						seen.add(id);
						const next = byId.get(id);
						if (next === void 0) continue;
						out.push(next);
						stack.push(id);
					}
				}
				return out;
			};
			return {
				upstream: walk(taskId, predecessorsOf),
				downstream: walk(taskId, successorsOf)
			};
		}
		/**
		* Client-side blockedBy: a predecessor is satisfied only when it settled
		* with `outcome === 'success'`. Missing ids stay blocking.
		*/
		function blockedByOf(task, tasks) {
			const byId = new Map(tasks.map((item) => [item.id, item]));
			return predecessorsOf(task).filter((id) => {
				const dep = byId.get(id);
				if (dep === void 0) return true;
				return !(dep.status === "completed" && dep.outcome === "success");
			});
		}
		/**
		* Readable copy for a failure that the scheduler propagated down the DAG.
		* Other reasons stay `null` so the view does not invent a dependency badge.
		*/
		function failureReasonOf(task) {
			if (task.reason === "dependency-failed") return "依赖失败";
			if (task.reason === "dependency-canceled") return "依赖已取消";
			return null;
		}
		/** True when any declared predecessor is terminally failed or canceled. */
		function hasFailedDependency(task, tasks) {
			const byId = new Map(tasks.map((item) => [item.id, item]));
			return predecessorsOf(task).some((id) => {
				const dep = byId.get(id);
				return dep !== void 0 && (dep.status === "failed" || dep.status === "canceled");
			});
		}
		/** Flows of one workspace: active first, then archived. */
		function flowsOfWorkspace(flows, workspacePath) {
			return (workspacePath === null ? [...flows] : flows.filter((flow) => flow.workspacePath === workspacePath)).sort((left, right) => Number(left.archived) - Number(right.archived));
		}
		/** Tasks that belong to one flow. Flow-less rows never appear in a DAG. */
		function tasksOfFlow(tasks, flowId) {
			if (flowId === null) return [];
			return tasks.filter((task) => task.flowId === flowId);
		}
		/**
		* The DAG's archive rule (v1.4 §6): a terminal failure/cancel/reject leaves
		* the active set IMMEDIATELY, and a settled success leaves after the 24h
		* archive phase. This mirrors the flow-archived derivation (panel flows
		* directory) and the tools' isActiveTask, so a faded node, the flow list,
		* and the agent-visible set never disagree about what is archived.
		*/
		function isDagArchived(task) {
			if (task.status === "failed" || task.status === "canceled" || task.status === "rejected") return true;
			return isArchived(task);
		}
		/**
		* v1.4 §6.1: active tasks plus every recursive predecessor, so an archived
		* ancestor chain stays visible beside live work. Terminal-failed tasks are
		* not anchors but still appear when an active task depends on them.
		* Isolated archived tasks drop off the graph.
		*/
		function visibleDagTasks(tasks) {
			const byId = new Map(tasks.map((task) => [task.id, task]));
			const keep = /* @__PURE__ */ new Set();
			const walk = (id) => {
				if (keep.has(id)) return;
				const task = byId.get(id);
				if (task === void 0) return;
				keep.add(id);
				for (const dep of predecessorsOf(task)) walk(dep);
			};
			for (const task of tasks) if (!isDagArchived(task)) walk(task.id);
			return tasks.filter((task) => keep.has(task.id));
		}
		/** Archived node on a live chain: faded, not interactive. */
		function isDagFaded(task) {
			return isDagArchived(task);
		}
		/** Upstream tone: settled success / still open / terminal failure. */
		function chainToneOf(task) {
			if (task.status === "failed" || task.status === "canceled") return "fail";
			if (task.status === "completed" && task.outcome === "success") return "ok";
			return "wait";
		}
		//#endregion
		//#region lib/client/DagView.js
		/**
		* DAG board for the v1.4 flow window: pick a flow, then a canvas of that
		* flow's tasks. Archived ancestors fade; live nodes stay interactive.
		*
		* @module dsh-agent-bus/client/DagView
		*/
		const ROLE_LABEL$1 = {
			initiator: "发起",
			executor: "执行",
			reviewer: "验收"
		};
		function badgeKind$1(task) {
			if (task.status === "queued") return "dashed";
			if (task.status === "completed" && task.outcome === null) return "dashed";
			if (task.status === "canceled") return "outline";
			return "solid";
		}
		function reportZoneLabel$1(zone) {
			if (zone === "hot") return "报告外置·热";
			if (zone === "cold") return "报告外置·冷(已归档)";
			if (zone === "missing") return "报告缺失";
			return null;
		}
		function TokenTriple$1({ tokens }) {
			const hint = tokens === null ? void 0 : `cache-write ${tokens.cacheWriteTokens}`;
			return (0, react_jsx_runtime.jsx)("div", {
				className: "abPTriple",
				title: hint,
				children: formatTokenUsage(tokens)
			});
		}
		function StatusDot$1({ task }) {
			return (0, react_jsx_runtime.jsx)("span", {
				className: "abPDot",
				"data-tone": statusTone(task.status, task.outcome),
				"aria-hidden": "true"
			});
		}
		function StatusBadge$1({ task }) {
			return (0, react_jsx_runtime.jsx)("span", {
				className: "abPBadge",
				"data-tone": statusTone(task.status, task.outcome),
				"data-kind": badgeKind$1(task),
				children: statusLabel(task.status, task.outcome)
			});
		}
		function nodeMark(task, tasks) {
			if (isDagFaded(task)) return "已归档";
			const propagated = failureReasonOf(task);
			if (propagated !== null) return propagated;
			if (hasFailedDependency(task, tasks)) return "依赖失败";
			if (blockedByOf(task, tasks).length > 0) return "等待依赖";
			return null;
		}
		const ZOOM_MIN = .25;
		const ZOOM_MAX = 2.5;
		const ZOOM_STEP = 1.12;
		const GRID_PX = 20;
		const POS_KEY = "dsh-agent-bus.dag.pos";
		const VIEW_KEY = "dsh-agent-bus.dag.view";
		function clampZoom(zoom) {
			return Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, zoom));
		}
		function readPos() {
			try {
				const raw = localStorage.getItem(POS_KEY);
				if (raw === null) return {};
				const parsed = JSON.parse(raw);
				return parsed !== null && typeof parsed === "object" ? parsed : {};
			} catch {
				return {};
			}
		}
		function writePos(pos) {
			try {
				localStorage.setItem(POS_KEY, JSON.stringify(pos));
			} catch {}
		}
		function readView() {
			try {
				const raw = localStorage.getItem(VIEW_KEY);
				if (raw === null) return null;
				const parsed = JSON.parse(raw);
				if (typeof parsed.x !== "number" || typeof parsed.y !== "number" || typeof parsed.zoom !== "number") return null;
				return {
					x: parsed.x,
					y: parsed.y,
					zoom: clampZoom(parsed.zoom)
				};
			} catch {
				return null;
			}
		}
		function writeView(view) {
			try {
				localStorage.setItem(VIEW_KEY, JSON.stringify(view));
			} catch {}
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
				const curve = Math.max(24, Math.abs(tx - sx) * .45);
				const dir = rightward ? 1 : -1;
				return `M ${sx} ${sy} C ${sx + dir * curve} ${sy} ${tx - dir * curve} ${ty} ${tx} ${ty}`;
			}
			const downward = dy >= 0;
			const sx = fromCx;
			const sy = downward ? from.y + from.h : from.y;
			const tx = toCx;
			const ty = downward ? to.y : to.y + to.h;
			const curve = Math.max(24, Math.abs(ty - sy) * .45);
			const dir = downward ? 1 : -1;
			return `M ${sx} ${sy} C ${sx} ${sy + dir * curve} ${tx} ${ty - dir * curve} ${tx} ${ty}`;
		}
		function TaskDetail({ task, nowMs, onClose }) {
			const zone = reportZoneLabel$1(task.reportZone);
			const steps = callSteps(task);
			const propagated = failureReasonOf(task);
			const criteria = task.acceptanceCriteria;
			return (0, react_jsx_runtime.jsxs)("article", {
				className: "abPDagDetail",
				children: [
					(0, react_jsx_runtime.jsxs)("div", {
						className: "abPFloatTop",
						children: [
							(0, react_jsx_runtime.jsx)(StatusDot$1, { task }),
							(0, react_jsx_runtime.jsxs)("div", {
								className: "abPTaskSummary",
								children: [(0, react_jsx_runtime.jsx)("div", {
									className: "abPTaskLine",
									children: (0, react_jsx_runtime.jsx)(StatusBadge$1, { task })
								}), (0, react_jsx_runtime.jsxs)("div", {
									className: "abPTaskMeta",
									children: [
										`任务时间 ${relativeTime(task.updatedMs, nowMs)}`,
										task.retries > 0 ? ` · 重做 ${task.retries}` : "",
										task.auto ? " · 自动派发" : ""
									]
								})]
							}),
							(0, react_jsx_runtime.jsx)("button", {
								type: "button",
								className: "abPClose",
								"aria-label": "关闭任务详情",
								onClick: onClose,
								children: (0, react_jsx_runtime.jsx)(_deepseek_ai_dsh_client_ui_primitives.IconCloseOutline16, { size: 16 })
							})
						]
					}),
					propagated !== null && (0, react_jsx_runtime.jsx)("div", {
						className: "abPDagFail",
						children: propagated
					}),
					task.blockedBy.length > 0 && (0, react_jsx_runtime.jsx)("div", {
						className: "abPTaskMeta",
						children: `等待依赖 ${task.blockedBy.join(" · ")}`
					}),
					(0, react_jsx_runtime.jsxs)("div", {
						className: "abPReq",
						children: [
							(0, react_jsx_runtime.jsx)("div", {
								className: "abPStaffHead",
								children: "任务要求"
							}),
							(0, react_jsx_runtime.jsx)("pre", {
								className: "abPContent",
								children: task.content
							}),
							criteria !== null && criteria !== "" && (0, react_jsx_runtime.jsxs)(react_jsx_runtime.Fragment, { children: [(0, react_jsx_runtime.jsx)("div", {
								className: "abPStaffHead",
								children: "验收标准"
							}), (0, react_jsx_runtime.jsx)("pre", {
								className: "abPContent",
								children: criteria
							})] })
						]
					}),
					(0, react_jsx_runtime.jsxs)("div", {
						className: "abPDagMore",
						children: [
							(0, react_jsx_runtime.jsxs)("div", {
								className: "abPStaffHead",
								children: ["本任务合计", hasUnreadableTokens(task.staff) ? " · 部分会话不可读" : ""]
							}),
							(0, react_jsx_runtime.jsx)(TokenTriple$1, { tokens: task.taskTokensTotal }),
							(0, react_jsx_runtime.jsx)("div", {
								className: "abPCalls",
								"aria-label": "调用过程",
								children: steps.map((step, index) => (0, react_jsx_runtime.jsxs)("div", {
									className: "abPCall",
									children: [
										(0, react_jsx_runtime.jsxs)("div", {
											className: "abPCallHead",
											children: [
												(0, react_jsx_runtime.jsx)("span", {
													className: "abPCallWho",
													children: step.from.title
												}),
												(0, react_jsx_runtime.jsx)("span", {
													className: "abPChainArrow",
													"aria-hidden": "true",
													children: "→"
												}),
												(0, react_jsx_runtime.jsx)("span", {
													className: "abPCallWho",
													children: step.to.title
												}),
												(0, react_jsx_runtime.jsx)("span", {
													className: "abPCallRoles",
													children: `${ROLE_LABEL$1[step.from.role]} · ${ROLE_LABEL$1[step.to.role]}`
												})
											]
										}),
										(0, react_jsx_runtime.jsx)("div", {
											className: "abPCallSummary",
											children: step.summary
										}),
										(0, react_jsx_runtime.jsxs)("div", {
											className: "abPCallCost",
											children: [(0, react_jsx_runtime.jsx)("span", {
												className: "abPCallCostName",
												children: step.from.title
											}), (0, react_jsx_runtime.jsx)(TokenTriple$1, { tokens: tokensForSession(task, step.from.sessionId) })]
										}),
										(0, react_jsx_runtime.jsxs)("div", {
											className: "abPCallCost",
											children: [(0, react_jsx_runtime.jsx)("span", {
												className: "abPCallCostName",
												children: step.to.title
											}), (0, react_jsx_runtime.jsx)(TokenTriple$1, { tokens: tokensForSession(task, step.to.sessionId) })]
										})
									]
								}, `${step.from.sessionId}:${step.to.sessionId}:${index}`))
							}),
							zone !== null && (0, react_jsx_runtime.jsx)("div", {
								className: "abPZone",
								"data-missing": task.reportZone === "missing" || void 0,
								children: zone
							})
						]
					})
				]
			});
		}
		/**
		* Session rail + layered DAG canvas + detail strip.
		* Empty-canvas drag pans; wheel zooms toward the cursor; nodes drag freely.
		*/
		function DagView({ tasks, flows, selectedFlowId, onSelectFlow, sidebarWidth, onSidebarResizeDown, onSidebarResizeMove, onSidebarResizeUp, nowMs, consumeEscRef }) {
			const flowTasks = (0, react.useMemo)(() => visibleDagTasks(tasksOfFlow(tasks, selectedFlowId)), [tasks, selectedFlowId]);
			const graph = (0, react.useMemo)(() => dagOf(flowTasks), [flowTasks]);
			const layout = (0, react.useMemo)(() => layoutDag(graph), [graph]);
			const [pos, setPos] = (0, react.useState)(readPos);
			const posRef = (0, react.useRef)(pos);
			posRef.current = pos;
			const [view, setView] = (0, react.useState)(() => readView() ?? {
				x: 24,
				y: 24,
				zoom: 1
			});
			const viewRef = (0, react.useRef)(view);
			viewRef.current = view;
			const canvasRef = (0, react.useRef)(null);
			const [hoverId, setHoverId] = (0, react.useState)(null);
			const [pinnedId, setPinnedId] = (0, react.useState)(null);
			const [detailId, setDetailId] = (0, react.useState)(null);
			const [flare, setFlare] = (0, react.useState)(() => /* @__PURE__ */ new Set());
			const [panning, setPanning] = (0, react.useState)(false);
			const [draggingId, setDraggingId] = (0, react.useState)(null);
			const seenSuccess = (0, react.useRef)(null);
			const panDrag = (0, react.useRef)(null);
			const nodeDrag = (0, react.useRef)(null);
			const boxes = (0, react.useMemo)(() => layout.boxes.map((box) => {
				const placed = pos[box.id];
				return placed === void 0 ? box : {
					...box,
					x: placed.x,
					y: placed.y
				};
			}), [layout.boxes, pos]);
			const byId = (0, react.useMemo)(() => new Map(boxes.map((box) => [box.id, box])), [boxes]);
			const taskById = (0, react.useMemo)(() => new Map(layout.boxes.map((box) => [box.id, box.task])), [layout.boxes]);
			const bbox = (0, react.useMemo)(() => {
				if (boxes.length === 0) return {
					x: 0,
					y: 0,
					w: 1,
					h: 1
				};
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
				return {
					x: minX - pad,
					y: minY - pad,
					w: maxX - minX + 160,
					h: maxY - minY + 160
				};
			}, [boxes]);
			const setViewLive = (next, persist) => {
				viewRef.current = next;
				setView(next);
				if (persist) writeView(next);
			};
			const persistPos = (next) => {
				posRef.current = next;
				setPos(next);
				writePos(next);
			};
			const fitTo = (items) => {
				const el = canvasRef.current;
				if (el === null || items.length === 0) return;
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
				const area = {
					x: minX - pad,
					y: minY - pad,
					w: maxX - minX + 160,
					h: maxY - minY + 160
				};
				const rect = el.getBoundingClientRect();
				const zoom = clampZoom(Math.min((rect.width - 56) / Math.max(area.w, 1), (rect.height - 56) / Math.max(area.h, 1), 1.25));
				setViewLive({
					zoom,
					x: (rect.width - area.w * zoom) / 2 - area.x * zoom,
					y: (rect.height - area.h * zoom) / 2 - area.y * zoom
				}, true);
			};
			const fitView = () => {
				fitTo(boxes);
			};
			const zoomBy = (factor, origin) => {
				const el = canvasRef.current;
				const current = viewRef.current;
				const nextZoom = clampZoom(current.zoom * factor);
				if (nextZoom === current.zoom) return;
				const rect = el?.getBoundingClientRect();
				const sx = origin?.x ?? (rect === void 0 ? 0 : rect.width / 2);
				const sy = origin?.y ?? (rect === void 0 ? 0 : rect.height / 2);
				const worldX = (sx - current.x) / current.zoom;
				const worldY = (sy - current.y) / current.zoom;
				setViewLive({
					zoom: nextZoom,
					x: sx - worldX * nextZoom,
					y: sy - worldY * nextZoom
				}, true);
			};
			const resetLayout = () => {
				const next = {};
				for (const box of layout.boxes) next[box.id] = {
					x: box.x,
					y: box.y
				};
				persistPos(next);
				fitTo(layout.boxes);
			};
			(0, react.useEffect)(() => {
				setPos((current) => {
					let changed = false;
					const next = { ...current };
					for (const box of layout.boxes) {
						if (next[box.id] !== void 0) continue;
						next[box.id] = {
							x: box.x,
							y: box.y
						};
						changed = true;
					}
					if (!changed) return current;
					writePos(next);
					return next;
				});
			}, [layout.boxes]);
			(0, react.useLayoutEffect)(() => {
				if (boxes.length === 0) return;
				fitTo(boxes);
			}, [selectedFlowId]);
			(0, react.useEffect)(() => {
				const el = canvasRef.current;
				if (el === null) return;
				const onWheel = (event) => {
					event.preventDefault();
					const rect = el.getBoundingClientRect();
					const factor = event.deltaY < 0 ? ZOOM_STEP : 1 / ZOOM_STEP;
					zoomBy(factor, {
						x: event.clientX - rect.left,
						y: event.clientY - rect.top
					});
				};
				el.addEventListener("wheel", onWheel, { passive: false });
				return () => el.removeEventListener("wheel", onWheel);
			}, [flowTasks.length]);
			const focusId = pinnedId ?? hoverId;
			const chain = (0, react.useMemo)(() => {
				if (focusId === null) return null;
				return dependencyChainOf(focusId, graph.nodes);
			}, [focusId, graph.nodes]);
			const chainTone = (0, react.useMemo)(() => {
				const tones = /* @__PURE__ */ new Map();
				if (focusId === null || chain === null) return tones;
				tones.set(focusId, "self");
				for (const task of chain.upstream) tones.set(task.id, chainToneOf(task));
				for (const task of chain.downstream) tones.set(task.id, "down");
				return tones;
			}, [focusId, chain]);
			const detailTask = detailId === null ? null : taskById.get(detailId) ?? null;
			(0, react.useEffect)(() => {
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
			}, [
				consumeEscRef,
				pinnedId,
				detailId
			]);
			(0, react.useEffect)(() => {
				const success = new Set(flowTasks.filter((task) => task.status === "completed" && task.outcome === "success").map((task) => task.id));
				const previous = seenSuccess.current;
				seenSuccess.current = success;
				if (previous === null) return;
				const released = /* @__PURE__ */ new Set();
				for (const id of success) {
					if (previous.has(id)) continue;
					const task = taskById.get(id);
					for (const dep of task?.dependents ?? []) released.add(dep);
				}
				if (released.size === 0) return;
				setFlare((current) => /* @__PURE__ */ new Set([...current, ...released]));
				const timer = window.setTimeout(() => {
					setFlare((current) => {
						const next = new Set(current);
						for (const id of released) next.delete(id);
						return next;
					});
				}, 2e3);
				return () => window.clearTimeout(timer);
			}, [flowTasks, taskById]);
			const onCanvasPointerDown = (event) => {
				if (event.button !== 0) return;
				const target = event.target;
				if (!(target instanceof Element)) return;
				if (target.closest(".abPDagNode") !== null || target.closest(".abPDagTools") !== null) return;
				event.preventDefault();
				event.currentTarget.setPointerCapture(event.pointerId);
				panDrag.current = {
					startX: event.clientX,
					startY: event.clientY,
					origX: viewRef.current.x,
					origY: viewRef.current.y
				};
				setPanning(true);
			};
			const onCanvasPointerMove = (event) => {
				const pan = panDrag.current;
				if (pan === null) return;
				setViewLive({
					zoom: viewRef.current.zoom,
					x: pan.origX + event.clientX - pan.startX,
					y: pan.origY + event.clientY - pan.startY
				}, false);
			};
			const onCanvasPointerUp = () => {
				if (panDrag.current !== null) writeView(viewRef.current);
				panDrag.current = null;
				setPanning(false);
			};
			const onNodePointerDown = (event, id) => {
				if (event.button !== 0) return;
				const task = taskById.get(id);
				if (task !== void 0 && isDagFaded(task)) return;
				event.stopPropagation();
				event.currentTarget.setPointerCapture(event.pointerId);
				const box = byId.get(id);
				nodeDrag.current = {
					id,
					startX: event.clientX,
					startY: event.clientY,
					origX: box?.x ?? 0,
					origY: box?.y ?? 0,
					moved: false
				};
				setHoverId(id);
			};
			const onNodePointerMove = (event) => {
				const drag = nodeDrag.current;
				if (drag === null) return;
				const zoom = viewRef.current.zoom;
				const dx = (event.clientX - drag.startX) / zoom;
				const dy = (event.clientY - drag.startY) / zoom;
				if (!drag.moved && (dx * dx + dy * dy) * zoom * zoom < 25) return;
				drag.moved = true;
				setDraggingId(drag.id);
				const next = {
					...posRef.current,
					[drag.id]: {
						x: drag.origX + dx,
						y: drag.origY + dy
					}
				};
				posRef.current = next;
				setPos(next);
			};
			const onNodePointerUp = (id) => {
				const drag = nodeDrag.current;
				nodeDrag.current = null;
				setDraggingId(null);
				if (drag === null || drag.id !== id) return;
				if (drag.moved) {
					writePos(posRef.current);
					return;
				}
				setPinnedId((current) => current === id ? null : id);
				setDetailId(id);
			};
			const activeFlows = flows.filter((flow) => !flow.archived);
			const archivedFlows = flows.filter((flow) => flow.archived);
			const selectedFlow = flows.find((flow) => flow.id === selectedFlowId) ?? null;
			const grid = GRID_PX * view.zoom;
			return (0, react_jsx_runtime.jsxs)("div", {
				className: "abPBody",
				children: [(0, react_jsx_runtime.jsxs)("nav", {
					className: "abPSessions",
					"aria-label": "流程",
					style: { width: sidebarWidth },
					children: [
						(0, react_jsx_runtime.jsx)("div", {
							className: "abPResize",
							role: "separator",
							"aria-orientation": "vertical",
							"aria-label": "调节侧栏宽度",
							onPointerDown: onSidebarResizeDown,
							onPointerMove: onSidebarResizeMove,
							onPointerUp: onSidebarResizeUp,
							onPointerCancel: onSidebarResizeUp
						}),
						(0, react_jsx_runtime.jsx)("div", {
							className: "abPFlowHead",
							children: "活跃"
						}),
						(0, react_jsx_runtime.jsxs)("div", {
							className: "abPFlowList",
							children: [activeFlows.length === 0 && (0, react_jsx_runtime.jsx)("div", {
								className: "abPFlowEmpty",
								children: "暂无活跃流程"
							}), activeFlows.map((flow) => (0, react_jsx_runtime.jsxs)("button", {
								type: "button",
								className: "abPFlow",
								"data-active": selectedFlowId === flow.id || void 0,
								onClick: () => onSelectFlow(flow.id),
								children: [(0, react_jsx_runtime.jsx)("span", {
									className: "abPFlowName",
									children: flow.name
								}), (0, react_jsx_runtime.jsx)("span", {
									className: "abPFlowCount",
									children: flow.unsettledCount
								})]
							}, flow.id))]
						}),
						archivedFlows.length > 0 && (0, react_jsx_runtime.jsxs)("div", {
							className: "abPGroup",
							children: [(0, react_jsx_runtime.jsx)("div", {
								className: "abPFlowHead",
								children: "归档"
							}), (0, react_jsx_runtime.jsx)("div", {
								className: "abPFlowList",
								children: archivedFlows.map((flow) => (0, react_jsx_runtime.jsx)("button", {
									type: "button",
									className: "abPFlow",
									"data-active": selectedFlowId === flow.id || void 0,
									"data-archived": true,
									onClick: () => onSelectFlow(flow.id),
									children: (0, react_jsx_runtime.jsx)("span", {
										className: "abPFlowName",
										children: flow.name
									})
								}, flow.id))
							})]
						})
					]
				}), (0, react_jsx_runtime.jsxs)("div", {
					className: "abPDagPane",
					children: [selectedFlow === null ? (0, react_jsx_runtime.jsxs)("div", {
						className: "abPEmpty",
						children: [(0, react_jsx_runtime.jsx)("div", {
							className: "abPEmptyTitle",
							children: "选择一个流程"
						}), (0, react_jsx_runtime.jsx)("div", {
							className: "abPEmptyHint",
							children: "左侧点选后，这里会画出它的任务图"
						})]
					}) : flowTasks.length === 0 ? (0, react_jsx_runtime.jsxs)("div", {
						className: "abPEmpty",
						children: [(0, react_jsx_runtime.jsx)("div", {
							className: "abPEmptyTitle",
							children: `${selectedFlow.name} 还没有任务`
						}), (0, react_jsx_runtime.jsx)("div", {
							className: "abPEmptyHint",
							children: "归入这个流程的任务会出现在画布上"
						})]
					}) : (0, react_jsx_runtime.jsxs)("div", {
						ref: canvasRef,
						className: "abPDagCanvas",
						"data-panning": panning || void 0,
						"data-dragging": draggingId !== null || void 0,
						style: {
							backgroundSize: `${grid}px ${grid}px`,
							backgroundPosition: `${view.x}px ${view.y}px`
						},
						onPointerDown: onCanvasPointerDown,
						onPointerMove: onCanvasPointerMove,
						onPointerUp: onCanvasPointerUp,
						onPointerCancel: onCanvasPointerUp,
						onDoubleClick: (event) => {
							if (event.target instanceof Element && event.target.closest(".abPDagNode") !== null) return;
							fitView();
						},
						onMouseLeave: () => {
							if (draggingId === null) setHoverId(null);
						},
						children: [(0, react_jsx_runtime.jsxs)("div", {
							className: "abPDagWorld",
							style: { transform: `translate(${view.x}px, ${view.y}px) scale(${view.zoom})` },
							children: [(0, react_jsx_runtime.jsxs)("svg", {
								className: "abPDagSvg",
								width: bbox.w,
								height: bbox.h,
								viewBox: `${bbox.x} ${bbox.y} ${bbox.w} ${bbox.h}`,
								style: {
									left: bbox.x,
									top: bbox.y
								},
								"aria-hidden": "true",
								children: [(0, react_jsx_runtime.jsx)("defs", { children: (0, react_jsx_runtime.jsx)("marker", {
									id: "abPDagArrow",
									markerWidth: "8",
									markerHeight: "8",
									refX: "7",
									refY: "4",
									orient: "auto",
									children: (0, react_jsx_runtime.jsx)("path", {
										d: "M 0 0 L 8 4 L 0 8 z",
										fill: "currentColor"
									})
								}) }), graph.edges.map((edge) => {
									const from = byId.get(edge.from);
									const to = byId.get(edge.to);
									if (from === void 0 || to === void 0) return null;
									const inChain = focusId !== null && chainTone.has(edge.from) && chainTone.has(edge.to);
									const tone = inChain ? chainTone.get(edge.from) ?? "wait" : void 0;
									return (0, react_jsx_runtime.jsx)("path", {
										className: "abPDagEdge",
										d: edgePath(from, to),
										"data-tone": tone,
										"data-dim": focusId !== null && !inChain || void 0,
										markerEnd: "url(#abPDagArrow)"
									}, `${edge.from}:${edge.to}`);
								})]
							}), boxes.map((box) => {
								const faded = isDagFaded(box.task);
								const mark = nodeMark(box.task, flowTasks);
								const ready = box.task.status === "queued";
								const failedDep = hasFailedDependency(box.task, flowTasks) || failureReasonOf(box.task) !== null;
								const blocked = blockedByOf(box.task, flowTasks).length > 0;
								const settledOk = box.task.status === "completed" && box.task.outcome === "success";
								const tone = chainTone.get(box.id);
								const dim = !faded && focusId !== null && tone === void 0;
								return (0, react_jsx_runtime.jsxs)("button", {
									type: "button",
									className: "abPDagNode",
									style: {
										left: box.x,
										top: box.y,
										width: box.w,
										height: box.h
									},
									"data-tone": statusTone(box.task.status, box.task.outcome),
									"data-chain": tone,
									"data-blocked": blocked || void 0,
									"data-ready": ready || void 0,
									"data-fail": failedDep || void 0,
									"data-ok": settledOk || void 0,
									"data-flare": flare.has(box.id) || void 0,
									"data-dim": dim || void 0,
									"data-archived": faded || void 0,
									"data-dragging": draggingId === box.id || void 0,
									disabled: faded,
									"aria-pressed": pinnedId === box.id,
									"aria-label": `${statusLabel(box.task.status, box.task.outcome)} ${box.task.title ?? box.task.contentPreview}`,
									onMouseEnter: () => {
										if (!faded) setHoverId(box.id);
									},
									onFocus: () => {
										if (!faded) setHoverId(box.id);
									},
									onPointerDown: (event) => onNodePointerDown(event, box.id),
									onPointerMove: onNodePointerMove,
									onPointerUp: () => onNodePointerUp(box.id),
									onPointerCancel: () => onNodePointerUp(box.id),
									children: [
										(0, react_jsx_runtime.jsxs)("span", {
											className: "abPDagNodeTop",
											children: [(0, react_jsx_runtime.jsx)(StatusDot$1, { task: box.task }), (0, react_jsx_runtime.jsx)(StatusBadge$1, { task: box.task })]
										}),
										(0, react_jsx_runtime.jsx)("span", {
											className: "abPDagNodeLabel",
											children: truncateCodePoints(box.task.title ?? box.task.contentPreview, 18)
										}),
										mark !== null && (0, react_jsx_runtime.jsx)("span", {
											className: "abPDagMark",
											children: mark
										})
									]
								}, box.id);
							})]
						}), (0, react_jsx_runtime.jsxs)("div", {
							className: "abPDagTools",
							onPointerDown: (event) => event.stopPropagation(),
							children: [
								(0, react_jsx_runtime.jsx)("button", {
									type: "button",
									className: "abPDagTool",
									"aria-label": "缩小",
									onClick: () => zoomBy(1 / ZOOM_STEP),
									children: "−"
								}),
								(0, react_jsx_runtime.jsx)("span", {
									className: "abPDagZoom",
									children: `${Math.round(view.zoom * 100)}%`
								}),
								(0, react_jsx_runtime.jsx)("button", {
									type: "button",
									className: "abPDagTool",
									"aria-label": "放大",
									onClick: () => zoomBy(ZOOM_STEP),
									children: "+"
								}),
								(0, react_jsx_runtime.jsx)("button", {
									type: "button",
									className: "abPDagTool",
									"aria-label": "适应画布",
									onClick: fitView,
									children: "适应"
								}),
								(0, react_jsx_runtime.jsx)("button", {
									type: "button",
									className: "abPDagTool",
									"aria-label": "恢复自动布局",
									onClick: resetLayout,
									children: "复位"
								})
							]
						})]
					}), detailTask !== null && (0, react_jsx_runtime.jsx)(TaskDetail, {
						task: detailTask,
						nowMs,
						onClose: () => {
							setDetailId(null);
							setPinnedId(null);
						}
					})]
				})]
			});
		}
		//#endregion
		//#region lib/client/TaskPanel.js
		/**
		* Workbench: capsule → 3×3 launcher → sticky-note feature windows.
		* The existing task list is the first feature window.
		*
		* Styles live in TaskPanel.module.css. This pipeline compiles client sources
		* through tsc then tsdown (no lightningcss CSS-modules plugin), so the sheet
		* is injected as a tagged <style> and class names stay the authored `abP*`
		* locals. Keep the two copies in lockstep.
		*
		* @module dsh-agent-bus/client/TaskPanel
		*/
		const STATE_PATH = "/plugins/dsh-agent-bus/state";
		const EVENTS_PATH = "/plugins/dsh-agent-bus/events";
		const DISPATCH_PATH = "/plugins/dsh-agent-bus/dispatch";
		const FLOW_KEY = "dsh-agent-bus.dag.flow";
		const STORAGE_KEY = "dsh-agent-bus.workspace";
		const SIDEBAR_KEY = "dsh-agent-bus.sidebar-width";
		const SIDEBAR_MIN = 128;
		const SIDEBAR_MAX = 280;
		const SIDEBAR_DEFAULT = 160;
		const TASK_NOTE_KEY = "dsh-agent-bus.note.tasks";
		const DAG_NOTE_KEY = "dsh-agent-bus.note.dag";
		const NOTE_MIN_W = 360;
		const NOTE_MIN_H = 320;
		const POLL_MS = 2e3;
		const STYLE_ID = "dsh-agent-bus-panel-styles";
		const LAUNCHER_TILES = [
			{
				id: "tasks",
				label: "任务",
				mark: "任",
				ready: true
			},
			{
				id: "dag",
				label: "流程",
				mark: "流",
				ready: true
			},
			{
				id: "soon-1",
				label: "预留",
				mark: "+",
				ready: false
			},
			{
				id: "soon-2",
				label: "预留",
				mark: "+",
				ready: false
			},
			{
				id: "soon-3",
				label: "预留",
				mark: "+",
				ready: false
			},
			{
				id: "soon-4",
				label: "预留",
				mark: "+",
				ready: false
			},
			{
				id: "soon-5",
				label: "预留",
				mark: "+",
				ready: false
			},
			{
				id: "soon-6",
				label: "预留",
				mark: "+",
				ready: false
			},
			{
				id: "soon-7",
				label: "预留",
				mark: "+",
				ready: false
			}
		];
		const ROLE_LABEL = {
			initiator: "发起",
			executor: "执行",
			reviewer: "验收"
		};
		function readStoredWorkspace() {
			try {
				return localStorage.getItem(STORAGE_KEY);
			} catch {
				return null;
			}
		}
		function writeStoredWorkspace(id) {
			try {
				localStorage.setItem(STORAGE_KEY, id);
			} catch {}
		}
		function clampSidebarWidth(width) {
			return Math.min(SIDEBAR_MAX, Math.max(SIDEBAR_MIN, Math.round(width)));
		}
		function readSidebarWidth() {
			try {
				const raw = localStorage.getItem(SIDEBAR_KEY);
				if (raw === null) return SIDEBAR_DEFAULT;
				const parsed = Number(raw);
				return Number.isFinite(parsed) ? clampSidebarWidth(parsed) : SIDEBAR_DEFAULT;
			} catch {
				return SIDEBAR_DEFAULT;
			}
		}
		function writeSidebarWidth(width) {
			try {
				localStorage.setItem(SIDEBAR_KEY, String(width));
			} catch {}
		}
		function defaultTaskNoteGeom() {
			const w = 520;
			const h = Math.min(620, Math.max(NOTE_MIN_H, window.innerHeight - 96));
			return {
				x: Math.max(16, window.innerWidth - w - 72),
				y: Math.max(24, Math.round((window.innerHeight - h) / 2)),
				w,
				h,
				pinned: false
			};
		}
		function defaultDagNoteGeom() {
			const w = 760;
			const h = Math.min(680, Math.max(400, window.innerHeight - 72));
			return {
				x: Math.max(16, window.innerWidth - w - 112),
				y: Math.max(24, Math.round((window.innerHeight - h) / 2) + 32),
				w,
				h,
				pinned: false
			};
		}
		function clampNoteGeom(geom) {
			const w = Math.min(Math.max(NOTE_MIN_W, geom.w), Math.max(NOTE_MIN_W, window.innerWidth - 24));
			const h = Math.min(Math.max(NOTE_MIN_H, geom.h), Math.max(NOTE_MIN_H, window.innerHeight - 24));
			return {
				x: Math.min(Math.max(8, geom.x), Math.max(8, window.innerWidth - 80)),
				y: Math.min(Math.max(8, geom.y), Math.max(8, window.innerHeight - 40)),
				w,
				h,
				pinned: geom.pinned
			};
		}
		function readNoteGeom(key, fallback) {
			try {
				const raw = localStorage.getItem(key);
				if (raw === null) return fallback();
				const parsed = JSON.parse(raw);
				return clampNoteGeom({
					...fallback(),
					...parsed,
					pinned: parsed.pinned === true
				});
			} catch {
				return fallback();
			}
		}
		function writeNoteGeom(key, geom) {
			try {
				localStorage.setItem(key, JSON.stringify(geom));
			} catch {}
		}
		function useNoteWindow(storageKey, fallback) {
			const [open, setOpen] = (0, react.useState)(() => readNoteGeom(storageKey, fallback).pinned);
			const [geom, setGeom] = (0, react.useState)(() => readNoteGeom(storageKey, fallback));
			const geomRef = (0, react.useRef)(geom);
			geomRef.current = geom;
			const drag = (0, react.useRef)(null);
			const resize = (0, react.useRef)(null);
			const persist = (next) => {
				const clamped = clampNoteGeom(next);
				geomRef.current = clamped;
				setGeom(clamped);
				writeNoteGeom(storageKey, clamped);
			};
			const onDragDown = (event) => {
				if (event.button !== 0) return;
				if (event.target instanceof Element && event.target.closest("button")) return;
				event.preventDefault();
				event.currentTarget.setPointerCapture(event.pointerId);
				drag.current = {
					originX: event.clientX,
					originY: event.clientY,
					startX: geomRef.current.x,
					startY: geomRef.current.y
				};
			};
			const onDragMove = (event) => {
				const current = drag.current;
				if (current === null) return;
				persist({
					...geomRef.current,
					x: current.startX + event.clientX - current.originX,
					y: current.startY + event.clientY - current.originY
				});
			};
			const onDragUp = () => {
				drag.current = null;
			};
			const onResizeDown = (event) => {
				event.preventDefault();
				event.stopPropagation();
				event.currentTarget.setPointerCapture(event.pointerId);
				resize.current = {
					originX: event.clientX,
					originY: event.clientY,
					startW: geomRef.current.w,
					startH: geomRef.current.h
				};
			};
			const onResizeMove = (event) => {
				const current = resize.current;
				if (current === null) return;
				persist({
					...geomRef.current,
					w: current.startW + event.clientX - current.originX,
					h: current.startH + event.clientY - current.originY
				});
			};
			const onResizeUp = () => {
				resize.current = null;
			};
			return {
				open,
				setOpen,
				geom,
				geomRef,
				persist,
				onDragDown,
				onDragMove,
				onDragUp,
				onResizeDown,
				onResizeMove,
				onResizeUp
			};
		}
		function asSnapshot(value) {
			if (typeof value !== "object" || value === null) return null;
			const record = value;
			if (!Array.isArray(record.workspaces) || !Array.isArray(record.sessions) || !Array.isArray(record.tasks)) return null;
			if (!Array.isArray(record.flows)) return {
				...value,
				flows: []
			};
			return value;
		}
		function resolveWorkspace(workspaces, storedId) {
			if (workspaces.length === 0) return null;
			return (storedId === null ? void 0 : workspaces.find((item) => item.id === storedId)) ?? workspaces[0] ?? null;
		}
		function badgeKind(task) {
			if (task.status === "queued") return "dashed";
			if (task.status === "completed" && task.outcome === null) return "dashed";
			if (task.status === "canceled") return "outline";
			return "solid";
		}
		function reportZoneLabel(zone) {
			if (zone === "hot") return "报告外置·热";
			if (zone === "cold") return "报告外置·冷(已归档)";
			if (zone === "missing") return "报告缺失";
			return null;
		}
		function TokenTriple({ tokens }) {
			const hint = tokens === null ? void 0 : `cache-write ${tokens.cacheWriteTokens}`;
			return (0, react_jsx_runtime.jsx)("div", {
				className: css.abPTriple,
				title: hint,
				children: formatTokenUsage(tokens)
			});
		}
		function taskTouchesSession(task, sessionId) {
			return task.assignedBy === sessionId || task.assignedTo === sessionId || task.assignedReviewer === sessionId;
		}
		function ensurePanelStyles() {
			if (typeof document === "undefined") return;
			if (document.getElementById(STYLE_ID)) return;
			const style = document.createElement("style");
			style.id = STYLE_ID;
			style.textContent = PANEL_CSS;
			document.head.appendChild(style);
		}
		function useCurrentSessionId(sessionsList) {
			const subscribe = (0, react.useCallback)((onStoreChange) => {
				if (sessionsList === void 0) return () => {};
				return sessionsList.subscribe(onStoreChange);
			}, [sessionsList]);
			const getSnapshot = (0, react.useCallback)(() => sessionsList?.getSnapshot().current, [sessionsList]);
			return (0, react.useSyncExternalStore)(subscribe, getSnapshot, () => void 0);
		}
		function dispatchReady(tasks, changedId) {
			const candidates = changedId === null ? tasks.filter((task) => task.status === "queued") : tasks.filter((task) => task.status === "queued" && (task.id === changedId || task.dependencies.includes(changedId)));
			for (const task of candidates) {
				if (blockedByOf(task, tasks).length > 0) continue;
				fetch(DISPATCH_PATH, {
					method: "POST",
					headers: { "content-type": "application/json" },
					body: JSON.stringify({ taskId: task.id })
				});
			}
		}
		function usePanelSnapshot() {
			const [snapshot, setSnapshot] = (0, react.useState)(emptySnapshot);
			const [loading, setLoading] = (0, react.useState)(true);
			const snapshotRef = (0, react.useRef)(snapshot);
			snapshotRef.current = snapshot;
			(0, react.useEffect)(() => {
				let cancelled = false;
				let pollTimer = null;
				const pull = async (changedId) => {
					try {
						const response = await fetch(STATE_PATH, { cache: "no-store" });
						if (!response.ok) return;
						const parsed = asSnapshot(await response.json());
						if (parsed === null || cancelled) return;
						snapshotRef.current = parsed;
						setSnapshot(parsed);
						if (changedId !== null) dispatchReady(parsed.tasks, changedId);
					} catch {} finally {
						if (!cancelled) setLoading(false);
					}
				};
				const startPoll = () => {
					if (pollTimer !== null) return;
					pollTimer = window.setInterval(() => {
						pull(null);
					}, POLL_MS);
				};
				const stopPoll = () => {
					if (pollTimer === null) return;
					window.clearInterval(pollTimer);
					pollTimer = null;
				};
				pull(null).then(() => {
					if (!cancelled) dispatchReady(snapshotRef.current.tasks, null);
				});
				let source = null;
				try {
					source = new EventSource(EVENTS_PATH);
					source.onopen = () => {
						stopPoll();
					};
					source.onerror = () => {
						startPoll();
					};
					source.onmessage = (event) => {
						let changedId = null;
						try {
							const payload = JSON.parse(event.data);
							if (typeof payload.taskId === "string") changedId = payload.taskId;
						} catch {
							changedId = null;
						}
						pull(changedId);
					};
				} catch {
					startPoll();
				}
				return () => {
					cancelled = true;
					stopPoll();
					source?.close();
				};
			}, []);
			return {
				snapshot,
				loading
			};
		}
		function StatusDot({ task, className }) {
			const tone = statusTone(task.status, task.outcome);
			return (0, react_jsx_runtime.jsx)("span", {
				className,
				"data-tone": tone,
				"aria-hidden": "true"
			});
		}
		function StatusBadge({ task }) {
			const tone = statusTone(task.status, task.outcome);
			return (0, react_jsx_runtime.jsx)("span", {
				className: css.abPBadge,
				"data-tone": tone,
				"data-kind": badgeKind(task),
				children: statusLabel(task.status, task.outcome)
			});
		}
		function TaskCard({ task, nowMs, currentSessionId, focused, onFocusTask }) {
			const current = currentSessionId !== void 0 && taskTouchesSession(task, currentSessionId);
			return (0, react_jsx_runtime.jsxs)("button", {
				type: "button",
				className: css.abPTask,
				"data-focused": focused || void 0,
				"data-current": current || void 0,
				"aria-expanded": focused,
				onMouseEnter: (event) => onFocusTask(event.currentTarget),
				onFocus: (event) => onFocusTask(event.currentTarget),
				onClick: (event) => onFocusTask(event.currentTarget),
				children: [(0, react_jsx_runtime.jsxs)("div", {
					className: css.abPTaskLine,
					children: [(0, react_jsx_runtime.jsx)(StatusBadge, { task }), (0, react_jsx_runtime.jsx)("span", {
						className: css.abPTaskPreview,
						children: task.title ?? task.contentPreview
					})]
				}), (0, react_jsx_runtime.jsx)("div", {
					className: css.abPTaskMeta,
					children: `任务时间 ${relativeTime(task.updatedMs, nowMs)}`
				})]
			});
		}
		function placeFloat(anchor) {
			const drawer = document.querySelector(`.${css.abPDrawer}`);
			const drawerLeft = drawer instanceof HTMLElement ? drawer.getBoundingClientRect().left : window.innerWidth - 440;
			const gap = 12;
			const available = Math.max(200, drawerLeft - gap - 12);
			const width = Math.min(380, available);
			const left = Math.max(12, drawerLeft - gap - width);
			const maxHeight = Math.min(window.innerHeight - 24, 560);
			let top = anchor.top;
			if (top + maxHeight > window.innerHeight - 12) top = Math.max(12, window.innerHeight - 12 - maxHeight);
			return {
				top,
				left,
				width
			};
		}
		function TaskFloat({ task, nowMs, anchor, onReady, onClose }) {
			const zone = reportZoneLabel(task.reportZone);
			const box = placeFloat(anchor);
			const steps = callSteps(task);
			return (0, react_jsx_runtime.jsxs)("article", {
				ref: onReady,
				className: css.abPFloat,
				style: {
					top: box.top,
					left: box.left,
					width: box.width
				},
				children: [
					(0, react_jsx_runtime.jsxs)("div", {
						className: css.abPFloatTop,
						children: [
							(0, react_jsx_runtime.jsx)(StatusDot, {
								task,
								className: css.abPDot
							}),
							(0, react_jsx_runtime.jsxs)("div", {
								className: css.abPTaskSummary,
								children: [(0, react_jsx_runtime.jsx)("div", {
									className: css.abPTaskLine,
									children: (0, react_jsx_runtime.jsx)(StatusBadge, { task })
								}), (0, react_jsx_runtime.jsxs)("div", {
									className: css.abPTaskMeta,
									children: [`任务时间 ${relativeTime(task.updatedMs, nowMs)}`, task.retries > 0 ? ` · 重做 ${task.retries}` : ""]
								})]
							}),
							(0, react_jsx_runtime.jsx)("button", {
								type: "button",
								className: css.abPClose,
								"aria-label": "关闭任务详情",
								onClick: onClose,
								children: (0, react_jsx_runtime.jsx)(_deepseek_ai_dsh_client_ui_primitives.IconCloseOutline16, { size: 16 })
							})
						]
					}),
					(0, react_jsx_runtime.jsxs)("div", {
						className: css.abPReq,
						children: [(0, react_jsx_runtime.jsx)("div", {
							className: css.abPStaffHead,
							children: "任务要求"
						}), (0, react_jsx_runtime.jsx)("pre", {
							className: css.abPContent,
							children: task.content
						})]
					}),
					(0, react_jsx_runtime.jsxs)("div", {
						className: css.abPStaffHead,
						children: ["本任务合计", hasUnreadableTokens(task.staff) ? " · 部分会话不可读" : ""]
					}),
					(0, react_jsx_runtime.jsx)(TokenTriple, { tokens: task.taskTokensTotal }),
					(0, react_jsx_runtime.jsx)("div", {
						className: css.abPCalls,
						"aria-label": "调用过程",
						children: steps.map((step, index) => (0, react_jsx_runtime.jsxs)("div", {
							className: css.abPCall,
							children: [
								(0, react_jsx_runtime.jsxs)("div", {
									className: css.abPCallHead,
									children: [
										(0, react_jsx_runtime.jsx)("span", {
											className: css.abPCallWho,
											children: step.from.title
										}),
										(0, react_jsx_runtime.jsx)("span", {
											className: css.abPChainArrow,
											"aria-hidden": "true",
											children: "→"
										}),
										(0, react_jsx_runtime.jsx)("span", {
											className: css.abPCallWho,
											children: step.to.title
										}),
										(0, react_jsx_runtime.jsx)("span", {
											className: css.abPCallRoles,
											children: `${ROLE_LABEL[step.from.role]} · ${ROLE_LABEL[step.to.role]}`
										})
									]
								}),
								(0, react_jsx_runtime.jsx)("div", {
									className: css.abPCallSummary,
									children: step.summary
								}),
								(0, react_jsx_runtime.jsxs)("div", {
									className: css.abPCallCost,
									children: [(0, react_jsx_runtime.jsx)("span", {
										className: css.abPCallCostName,
										children: step.from.title
									}), (0, react_jsx_runtime.jsx)(TokenTriple, { tokens: tokensForSession(task, step.from.sessionId) })]
								}),
								(0, react_jsx_runtime.jsxs)("div", {
									className: css.abPCallCost,
									children: [(0, react_jsx_runtime.jsx)("span", {
										className: css.abPCallCostName,
										children: step.to.title
									}), (0, react_jsx_runtime.jsx)(TokenTriple, { tokens: tokensForSession(task, step.to.sessionId) })]
								})
							]
						}, `${step.from.sessionId}:${step.to.sessionId}:${index}`))
					}),
					zone !== null && (0, react_jsx_runtime.jsx)("div", {
						className: css.abPZone,
						"data-missing": task.reportZone === "missing" || void 0,
						children: zone
					})
				]
			});
		}
		const css = {
			abPRoot: "abPRoot",
			abPCapsule: "abPCapsule",
			abPLauncher: "abPLauncher",
			abPLaunchTile: "abPLaunchTile",
			abPLaunchMark: "abPLaunchMark",
			abPNote: "abPNote",
			abPNoteBar: "abPNoteBar",
			abPNoteTitle: "abPNoteTitle",
			abPNotePin: "abPNotePin",
			abPNoteBody: "abPNoteBody",
			abPNoteGrip: "abPNoteGrip",
			abPCapsuleCount: "abPCapsuleCount",
			abPCapsuleMeta: "abPCapsuleMeta",
			abPCapsuleDot: "abPCapsuleDot",
			abPPreview: "abPPreview",
			abPPreviewHead: "abPPreviewHead",
			abPPreviewWs: "abPPreviewWs",
			abPPreviewStats: "abPPreviewStats",
			abPPreviewList: "abPPreviewList",
			abPPreviewRow: "abPPreviewRow",
			abPPreviewTo: "abPPreviewTo",
			abPPreviewText: "abPPreviewText",
			abPPreviewEmpty: "abPPreviewEmpty",
			abPDrawer: "abPDrawer",
			abPTop: "abPTop",
			abPWs: "abPWs",
			abPWsBtn: "abPWsBtn",
			abPWsTitle: "abPWsTitle",
			abPWsChevron: "abPWsChevron",
			abPWsMenu: "abPWsMenu",
			abPWsItem: "abPWsItem",
			abPWsItemPath: "abPWsItemPath",
			abPClose: "abPClose",
			abPBody: "abPBody",
			abPSessions: "abPSessions",
			abPResize: "abPResize",
			abPAll: "abPAll",
			abPAllBtn: "abPAllBtn",
			abPAllToggle: "abPAllToggle",
			abPGroup: "abPGroup",
			abPSessionList: "abPSessionList",
			abPSession: "abPSession",
			abPSessionText: "abPSessionText",
			abPSessionTitle: "abPSessionTitle",
			abPOffline: "abPOffline",
			abPOfflineToggle: "abPOfflineToggle",
			abPLive: "abPLive",
			abPMain: "abPMain",
			abPEmpty: "abPEmpty",
			abPEmptyTitle: "abPEmptyTitle",
			abPEmptyHint: "abPEmptyHint",
			abPTask: "abPTask",
			abPTaskSummary: "abPTaskSummary",
			abPTaskLine: "abPTaskLine",
			abPTaskPreview: "abPTaskPreview",
			abPTaskMeta: "abPTaskMeta",
			abPDot: "abPDot",
			abPBadge: "abPBadge",
			abPFloat: "abPFloat",
			abPFloatTop: "abPFloatTop",
			abPFloatTitle: "abPFloatTitle",
			abPChainArrow: "abPChainArrow",
			abPCalls: "abPCalls",
			abPCall: "abPCall",
			abPCallHead: "abPCallHead",
			abPCallWho: "abPCallWho",
			abPCallRoles: "abPCallRoles",
			abPCallSummary: "abPCallSummary",
			abPCallCost: "abPCallCost",
			abPCallCostName: "abPCallCostName",
			abPZone: "abPZone",
			abPContent: "abPContent",
			abPReq: "abPReq",
			abPStaffHead: "abPStaffHead",
			abPTriple: "abPTriple"
		};
		/**
		* Capsule opens the launcher; 任务 / 流程 each open a sticky-note window.
		*/
		function TaskPanel({ sessionsList }) {
			(0, react.useLayoutEffect)(() => {
				ensurePanelStyles();
			}, []);
			const { snapshot, loading } = usePanelSnapshot();
			const currentSessionId = useCurrentSessionId(sessionsList);
			const [launcherOpen, setLauncherOpen] = (0, react.useState)(false);
			const taskNote = useNoteWindow(TASK_NOTE_KEY, defaultTaskNoteGeom);
			const dagNote = useNoteWindow(DAG_NOTE_KEY, defaultDagNoteGeom);
			const [front, setFront] = (0, react.useState)("tasks");
			const [wsMenu, setWsMenu] = (0, react.useState)(false);
			const [dagWsMenu, setDagWsMenu] = (0, react.useState)(false);
			const [sessionFilter, setSessionFilter] = (0, react.useState)(null);
			const [storedFlow, setStoredFlow] = (0, react.useState)(() => {
				try {
					return localStorage.getItem(FLOW_KEY);
				} catch {
					return null;
				}
			});
			const [archiveMode, setArchiveMode] = (0, react.useState)(false);
			const [sessionsOpen, setSessionsOpen] = (0, react.useState)(true);
			const [archiveOpen, setArchiveOpen] = (0, react.useState)(false);
			const [offlineOpen, setOfflineOpen] = (0, react.useState)(false);
			const [focusedTaskId, setFocusedTaskId] = (0, react.useState)(null);
			const [floatAnchor, setFloatAnchor] = (0, react.useState)(null);
			const floatRef = (0, react.useRef)(null);
			const dagEscRef = (0, react.useRef)(null);
			const [storedWorkspace, setStoredWorkspace] = (0, react.useState)(readStoredWorkspace);
			const [nowMs, setNowMs] = (0, react.useState)(() => Date.now());
			const rootRef = (0, react.useRef)(null);
			const [sidebarWidth, setSidebarWidth] = (0, react.useState)(readSidebarWidth);
			const sidebarWidthRef = (0, react.useRef)(sidebarWidth);
			sidebarWidthRef.current = sidebarWidth;
			const sidebarDrag = (0, react.useRef)(null);
			const onSidebarResizeDown = (event) => {
				event.preventDefault();
				event.currentTarget.setPointerCapture(event.pointerId);
				sidebarDrag.current = {
					origin: event.clientX,
					start: sidebarWidthRef.current
				};
			};
			const onSidebarResizeMove = (event) => {
				const drag = sidebarDrag.current;
				if (drag === null) return;
				setSidebarWidth(clampSidebarWidth(drag.start + event.clientX - drag.origin));
			};
			const onSidebarResizeUp = () => {
				if (sidebarDrag.current === null) return;
				sidebarDrag.current = null;
				writeSidebarWidth(sidebarWidthRef.current);
			};
			const workspace = (0, react.useMemo)(() => resolveWorkspace(snapshot.workspaces, storedWorkspace), [snapshot.workspaces, storedWorkspace]);
			const workspaceTasks = (0, react.useMemo)(() => tasksOfWorkspace(snapshot.tasks, workspace?.path ?? null), [snapshot.tasks, workspace]);
			const workspaceFlows = (0, react.useMemo)(() => flowsOfWorkspace(snapshot.flows, workspace?.path ?? null), [snapshot.flows, workspace]);
			const selectedFlowId = (0, react.useMemo)(() => {
				if (workspaceFlows.length === 0) return null;
				return (storedFlow === null ? void 0 : workspaceFlows.find((flow) => flow.id === storedFlow))?.id ?? workspaceFlows[0]?.id ?? null;
			}, [workspaceFlows, storedFlow]);
			const visibleTasks = (0, react.useMemo)(() => {
				const scoped = tasksOfSession(workspaceTasks, sessionFilter);
				if (archiveMode) return sortSettled(archiveTabTasks(scoped));
				return sortActive(activeTabTasks(scoped));
			}, [
				workspaceTasks,
				sessionFilter,
				archiveMode,
				nowMs
			]);
			const focusedTask = (0, react.useMemo)(() => visibleTasks.find((task) => task.id === focusedTaskId) ?? null, [visibleTasks, focusedTaskId]);
			const activeCount = (0, react.useMemo)(() => activeTabTasks(workspaceTasks).length, [workspaceTasks]);
			const workspaceSessions = (0, react.useMemo)(() => sessionsOfWorkspace(snapshot.sessions, workspace?.id ?? null), [snapshot.sessions, workspace]);
			const activeSessions = (0, react.useMemo)(() => workspaceSessions.filter((session) => !session.archived), [workspaceSessions]);
			const archivedSessions = (0, react.useMemo)(() => workspaceSessions.filter((session) => session.archived), [workspaceSessions]);
			const historicalAgents = (0, react.useMemo)(() => archiveAgents(workspaceTasks, snapshot.sessions), [workspaceTasks, snapshot.sessions]);
			(0, react.useEffect)(() => {
				const onKey = (event) => {
					if (event.key !== "Escape") return;
					if (dagEscRef.current?.()) return;
					if (focusedTaskId !== null) {
						setFocusedTaskId(null);
						setFloatAnchor(null);
						return;
					}
					if (launcherOpen) {
						setLauncherOpen(false);
						setWsMenu(false);
						setDagWsMenu(false);
						return;
					}
					if (front === "dag" && dagNote.open && !dagNote.geom.pinned) {
						dagNote.setOpen(false);
						setDagWsMenu(false);
						return;
					}
					if (taskNote.open && !taskNote.geom.pinned) {
						taskNote.setOpen(false);
						setWsMenu(false);
						setFocusedTaskId(null);
						setFloatAnchor(null);
						return;
					}
					if (dagNote.open && !dagNote.geom.pinned) {
						dagNote.setOpen(false);
						setDagWsMenu(false);
					}
				};
				document.addEventListener("keydown", onKey);
				return () => document.removeEventListener("keydown", onKey);
			}, [
				focusedTaskId,
				launcherOpen,
				front,
				taskNote.open,
				taskNote.geom.pinned,
				dagNote.open,
				dagNote.geom.pinned,
				taskNote,
				dagNote
			]);
			(0, react.useEffect)(() => {
				if (!launcherOpen) return;
				const onPointer = (event) => {
					const target = event.target;
					if (!(target instanceof Node)) return;
					if (target instanceof Element && target.closest(`.${css.abPLauncher}`)) return;
					if (target instanceof Element && target.closest(`.${css.abPCapsule}`)) return;
					setLauncherOpen(false);
				};
				document.addEventListener("mousedown", onPointer);
				return () => document.removeEventListener("mousedown", onPointer);
			}, [launcherOpen]);
			(0, react.useEffect)(() => {
				if (focusedTaskId === null) return;
				const onDown = (event) => {
					const target = event.target;
					if (!(target instanceof Node)) return;
					if (floatRef.current?.contains(target)) return;
					if (target instanceof Element && target.closest(`.${css.abPTask}`)) return;
					setFocusedTaskId(null);
					setFloatAnchor(null);
				};
				document.addEventListener("mousedown", onDown);
				return () => document.removeEventListener("mousedown", onDown);
			}, [focusedTaskId]);
			(0, react.useEffect)(() => {
				if (!taskNote.open && !dagNote.open) return;
				const timer = window.setInterval(() => setNowMs(Date.now()), 15e3);
				return () => window.clearInterval(timer);
			}, [taskNote.open, dagNote.open]);
			const openFeature = (id) => {
				if (id === "tasks") taskNote.setOpen(true);
				else dagNote.setOpen(true);
				setFront(id);
				setLauncherOpen(false);
			};
			const closeTaskNote = () => {
				taskNote.setOpen(false);
				setWsMenu(false);
				setFocusedTaskId(null);
				setFloatAnchor(null);
			};
			const closeDagNote = () => {
				dagNote.setOpen(false);
				setDagWsMenu(false);
			};
			const selectWorkspace = (id) => {
				setStoredWorkspace(id);
				writeStoredWorkspace(id);
				setSessionFilter(null);
				setArchiveMode(false);
				setSessionsOpen(true);
				setArchiveOpen(false);
				setFocusedTaskId(null);
				setWsMenu(false);
				setDagWsMenu(false);
			};
			const selectAllSessions = () => {
				setArchiveMode(false);
				setSessionFilter(null);
				setSessionsOpen(true);
				setFocusedTaskId(null);
			};
			const toggleSessionList = () => {
				if (sessionsOpen) {
					setSessionsOpen(false);
					return;
				}
				setSessionsOpen(true);
				setArchiveMode(false);
				setSessionFilter(null);
			};
			const toggleSession = (id) => {
				setArchiveMode(false);
				setSessionFilter((current) => !archiveMode && current === id ? null : id);
				setSessionsOpen(true);
			};
			const selectArchive = () => {
				setArchiveMode(true);
				setSessionFilter(null);
				setArchiveOpen(true);
				setFocusedTaskId(null);
			};
			const toggleArchiveList = () => {
				if (archiveOpen) {
					setArchiveOpen(false);
					return;
				}
				setArchiveOpen(true);
				setArchiveMode(true);
				setSessionFilter(null);
			};
			const toggleArchiveAgent = (id) => {
				setArchiveMode(true);
				setSessionFilter((current) => archiveMode && current === id ? null : id);
				setArchiveOpen(true);
			};
			const wsTitle = workspace?.title ?? "未选择工作区";
			const emptyLabel = archiveMode ? sessionFilter === null ? "暂无归档任务" : "该会话暂无归档任务" : sessionFilter === null ? "暂无活跃任务" : "该会话暂无活跃任务";
			const emptyHint = archiveMode ? "已完成超过 24 小时的任务会列在这里" : "进行中和已完成的任务会列在这里";
			return (0, react_jsx_runtime.jsxs)("div", {
				className: css.abPRoot,
				ref: rootRef,
				"data-agent-bus-panel": true,
				children: [
					(0, react_jsx_runtime.jsxs)("button", {
						type: "button",
						className: css.abPCapsule,
						"data-loading": loading || void 0,
						"data-open": launcherOpen || void 0,
						"aria-expanded": launcherOpen,
						"aria-label": `工作台，${activeCount} 个活跃任务`,
						onClick: () => setLauncherOpen((value) => !value),
						children: [activeCount === 0 ? (0, react_jsx_runtime.jsx)("span", { className: css.abPCapsuleDot }) : (0, react_jsx_runtime.jsx)("span", {
							className: css.abPCapsuleCount,
							children: activeCount
						}), (0, react_jsx_runtime.jsxs)("span", {
							className: css.abPCapsuleMeta,
							children: [snapshot.workspaces.length, " ws"]
						})]
					}),
					launcherOpen && (0, react_jsx_runtime.jsx)("div", {
						className: css.abPLauncher,
						role: "menu",
						"aria-label": "工作台",
						children: LAUNCHER_TILES.map((tile) => (0, react_jsx_runtime.jsxs)("button", {
							type: "button",
							className: css.abPLaunchTile,
							role: "menuitem",
							disabled: !tile.ready,
							"data-active": tile.id === "tasks" && taskNote.open || tile.id === "dag" && dagNote.open || void 0,
							onClick: () => {
								if (tile.id === "tasks" || tile.id === "dag") openFeature(tile.id);
							},
							children: [(0, react_jsx_runtime.jsx)("span", {
								className: css.abPLaunchMark,
								children: tile.mark
							}), tile.label]
						}, tile.id))
					}),
					taskNote.open && (0, react_jsx_runtime.jsxs)("div", {
						className: css.abPNote,
						"data-pinned": taskNote.geom.pinned || void 0,
						"data-front": front === "tasks" || void 0,
						style: {
							left: taskNote.geom.x,
							top: taskNote.geom.y,
							width: taskNote.geom.w,
							height: taskNote.geom.h
						},
						onPointerDown: () => setFront("tasks"),
						children: [
							(0, react_jsx_runtime.jsxs)("div", {
								className: css.abPNoteBar,
								onPointerDown: taskNote.onDragDown,
								onPointerMove: taskNote.onDragMove,
								onPointerUp: taskNote.onDragUp,
								onPointerCancel: taskNote.onDragUp,
								children: [
									(0, react_jsx_runtime.jsx)("span", {
										className: css.abPNoteTitle,
										children: "任务"
									}),
									(0, react_jsx_runtime.jsx)("button", {
										type: "button",
										className: `${css.abPClose} ${css.abPNotePin}`,
										"data-on": taskNote.geom.pinned || void 0,
										"aria-pressed": taskNote.geom.pinned,
										"aria-label": taskNote.geom.pinned ? "取消钉选" : "钉选窗口",
										onClick: () => taskNote.persist({
											...taskNote.geomRef.current,
											pinned: !taskNote.geomRef.current.pinned
										}),
										children: "钉"
									}),
									(0, react_jsx_runtime.jsx)("button", {
										type: "button",
										className: css.abPClose,
										"aria-label": "关闭任务窗口",
										onClick: closeTaskNote,
										children: (0, react_jsx_runtime.jsx)(_deepseek_ai_dsh_client_ui_primitives.IconCloseOutline16, { size: 16 })
									})
								]
							}),
							(0, react_jsx_runtime.jsx)("div", {
								className: css.abPNoteBody,
								children: (0, react_jsx_runtime.jsxs)("aside", {
									className: css.abPDrawer,
									children: [(0, react_jsx_runtime.jsx)("div", {
										className: css.abPTop,
										children: (0, react_jsx_runtime.jsxs)("div", {
											className: css.abPWs,
											children: [(0, react_jsx_runtime.jsxs)("button", {
												type: "button",
												className: css.abPWsBtn,
												"aria-haspopup": "listbox",
												"aria-expanded": wsMenu,
												onClick: () => setWsMenu((value) => !value),
												children: [(0, react_jsx_runtime.jsx)("span", {
													className: css.abPWsTitle,
													children: wsTitle
												}), (0, react_jsx_runtime.jsx)("span", {
													className: css.abPWsChevron,
													children: (0, react_jsx_runtime.jsx)(_deepseek_ai_dsh_client_ui_primitives.IconChevronDownOutline14, { size: 14 })
												})]
											}), wsMenu && snapshot.workspaces.length > 0 && (0, react_jsx_runtime.jsx)("div", {
												className: css.abPWsMenu,
												role: "listbox",
												children: snapshot.workspaces.map((item) => (0, react_jsx_runtime.jsxs)("button", {
													type: "button",
													className: css.abPWsItem,
													role: "option",
													"data-active": item.id === workspace?.id || void 0,
													"aria-selected": item.id === workspace?.id,
													onClick: () => selectWorkspace(item.id),
													children: [(0, react_jsx_runtime.jsx)("span", { children: item.title }), (0, react_jsx_runtime.jsx)("span", {
														className: css.abPWsItemPath,
														children: item.path
													})]
												}, item.id))
											})]
										})
									}), (0, react_jsx_runtime.jsxs)("div", {
										className: css.abPBody,
										children: [(0, react_jsx_runtime.jsxs)("nav", {
											className: css.abPSessions,
											"aria-label": "会话",
											style: { width: sidebarWidth },
											children: [
												(0, react_jsx_runtime.jsx)("div", {
													className: css.abPResize,
													role: "separator",
													"aria-orientation": "vertical",
													"aria-label": "调节侧栏宽度",
													onPointerDown: onSidebarResizeDown,
													onPointerMove: onSidebarResizeMove,
													onPointerUp: onSidebarResizeUp,
													onPointerCancel: onSidebarResizeUp
												}),
												(0, react_jsx_runtime.jsxs)("div", {
													className: css.abPAll,
													children: [(0, react_jsx_runtime.jsx)("button", {
														type: "button",
														className: css.abPAllBtn,
														"data-active": !archiveMode && sessionFilter === null || void 0,
														onClick: selectAllSessions,
														children: "活跃任务"
													}), (0, react_jsx_runtime.jsx)("button", {
														type: "button",
														className: css.abPAllToggle,
														"data-open": sessionsOpen || void 0,
														"aria-expanded": sessionsOpen,
														"aria-label": sessionsOpen ? "折叠活跃任务列表" : "展开活跃任务",
														onClick: toggleSessionList,
														children: (0, react_jsx_runtime.jsx)(_deepseek_ai_dsh_client_ui_primitives.IconChevronDownOutline14, { size: 14 })
													})]
												}),
												sessionsOpen && (0, react_jsx_runtime.jsx)("div", {
													className: css.abPSessionList,
													children: activeSessions.map((session) => (0, react_jsx_runtime.jsxs)("button", {
														type: "button",
														className: css.abPSession,
														"data-active": !archiveMode && sessionFilter === session.id || void 0,
														"data-current": session.id === currentSessionId || void 0,
														onClick: () => toggleSession(session.id),
														children: [(0, react_jsx_runtime.jsx)("span", {
															className: css.abPLive,
															"data-on": session.live || void 0
														}), (0, react_jsx_runtime.jsx)("span", {
															className: css.abPSessionText,
															children: (0, react_jsx_runtime.jsx)("span", {
																className: css.abPSessionTitle,
																children: session.title
															})
														})]
													}, session.id))
												}),
												(0, react_jsx_runtime.jsxs)("div", {
													className: css.abPGroup,
													children: [(0, react_jsx_runtime.jsxs)("div", {
														className: css.abPAll,
														children: [(0, react_jsx_runtime.jsx)("button", {
															type: "button",
															className: css.abPAllBtn,
															"data-active": archiveMode && sessionFilter === null || void 0,
															onClick: selectArchive,
															children: "归档任务"
														}), (0, react_jsx_runtime.jsx)("button", {
															type: "button",
															className: css.abPAllToggle,
															"data-open": archiveOpen || void 0,
															"aria-expanded": archiveOpen,
															"aria-label": archiveOpen ? "折叠归档任务列表" : "展开归档任务",
															onClick: toggleArchiveList,
															children: (0, react_jsx_runtime.jsx)(_deepseek_ai_dsh_client_ui_primitives.IconChevronDownOutline14, { size: 14 })
														})]
													}), archiveOpen && (0, react_jsx_runtime.jsxs)("div", {
														className: css.abPSessionList,
														children: [historicalAgents.filter((agent) => agent.live).map((agent) => (0, react_jsx_runtime.jsxs)("button", {
															type: "button",
															className: css.abPSession,
															"data-active": archiveMode && sessionFilter === agent.sessionId || void 0,
															"data-current": agent.sessionId === currentSessionId || void 0,
															onClick: () => toggleArchiveAgent(agent.sessionId),
															children: [(0, react_jsx_runtime.jsx)("span", {
																className: css.abPLive,
																"data-on": true
															}), (0, react_jsx_runtime.jsx)("span", {
																className: css.abPSessionText,
																children: (0, react_jsx_runtime.jsx)("span", {
																	className: css.abPSessionTitle,
																	children: agent.title
																})
															})]
														}, agent.sessionId)), archivedSessions.length > 0 && (0, react_jsx_runtime.jsxs)(react_jsx_runtime.Fragment, { children: [(0, react_jsx_runtime.jsxs)("button", {
															type: "button",
															className: css.abPOfflineToggle,
															"data-open": offlineOpen || void 0,
															"aria-expanded": offlineOpen,
															onClick: () => {
																setOfflineOpen((value) => !value);
																setArchiveMode(true);
																setSessionFilter(null);
																setFocusedTaskId(null);
															},
															children: [(0, react_jsx_runtime.jsxs)("span", { children: ["归档 ", archivedSessions.length] }), (0, react_jsx_runtime.jsx)(_deepseek_ai_dsh_client_ui_primitives.IconChevronDownOutline14, { size: 14 })]
														}), offlineOpen && archivedSessions.map((session) => (0, react_jsx_runtime.jsxs)("button", {
															type: "button",
															className: css.abPSession,
															"data-active": archiveMode && sessionFilter === session.id || void 0,
															"data-current": session.id === currentSessionId || void 0,
															onClick: () => toggleArchiveAgent(session.id),
															children: [(0, react_jsx_runtime.jsx)("span", {
																className: css.abPLive,
																"data-on": session.live || void 0
															}), (0, react_jsx_runtime.jsx)("span", {
																className: css.abPSessionText,
																children: (0, react_jsx_runtime.jsx)("span", {
																	className: css.abPSessionTitle,
																	children: session.title
																})
															})]
														}, session.id))] })]
													})]
												})
											]
										}), (0, react_jsx_runtime.jsx)("div", {
											className: css.abPMain,
											children: visibleTasks.length === 0 ? (0, react_jsx_runtime.jsxs)("div", {
												className: css.abPEmpty,
												children: [(0, react_jsx_runtime.jsx)("div", {
													className: css.abPEmptyTitle,
													children: emptyLabel
												}), sessionFilter === null && (0, react_jsx_runtime.jsx)("div", {
													className: css.abPEmptyHint,
													children: emptyHint
												})]
											}) : visibleTasks.map((task) => (0, react_jsx_runtime.jsx)(TaskCard, {
												task,
												nowMs,
												currentSessionId,
												focused: task.id === focusedTaskId,
												onFocusTask: (el) => {
													setFocusedTaskId(task.id);
													setFloatAnchor(el.getBoundingClientRect());
												}
											}, task.id))
										})]
									})]
								})
							}),
							(0, react_jsx_runtime.jsx)("div", {
								className: css.abPNoteGrip,
								"aria-label": "缩放窗口",
								onPointerDown: taskNote.onResizeDown,
								onPointerMove: taskNote.onResizeMove,
								onPointerUp: taskNote.onResizeUp,
								onPointerCancel: taskNote.onResizeUp
							})
						]
					}),
					dagNote.open && (0, react_jsx_runtime.jsxs)("div", {
						className: css.abPNote,
						"data-pinned": dagNote.geom.pinned || void 0,
						"data-front": front === "dag" || void 0,
						style: {
							left: dagNote.geom.x,
							top: dagNote.geom.y,
							width: dagNote.geom.w,
							height: dagNote.geom.h
						},
						onPointerDown: () => setFront("dag"),
						children: [
							(0, react_jsx_runtime.jsxs)("div", {
								className: css.abPNoteBar,
								onPointerDown: dagNote.onDragDown,
								onPointerMove: dagNote.onDragMove,
								onPointerUp: dagNote.onDragUp,
								onPointerCancel: dagNote.onDragUp,
								children: [
									(0, react_jsx_runtime.jsx)("span", {
										className: css.abPNoteTitle,
										children: "流程"
									}),
									(0, react_jsx_runtime.jsx)("button", {
										type: "button",
										className: `${css.abPClose} ${css.abPNotePin}`,
										"data-on": dagNote.geom.pinned || void 0,
										"aria-pressed": dagNote.geom.pinned,
										"aria-label": dagNote.geom.pinned ? "取消钉选" : "钉选窗口",
										onClick: () => dagNote.persist({
											...dagNote.geomRef.current,
											pinned: !dagNote.geomRef.current.pinned
										}),
										children: "钉"
									}),
									(0, react_jsx_runtime.jsx)("button", {
										type: "button",
										className: css.abPClose,
										"aria-label": "关闭流程窗口",
										onClick: closeDagNote,
										children: (0, react_jsx_runtime.jsx)(_deepseek_ai_dsh_client_ui_primitives.IconCloseOutline16, { size: 16 })
									})
								]
							}),
							(0, react_jsx_runtime.jsx)("div", {
								className: css.abPNoteBody,
								children: (0, react_jsx_runtime.jsxs)("aside", {
									className: css.abPDrawer,
									children: [(0, react_jsx_runtime.jsx)("div", {
										className: css.abPTop,
										children: (0, react_jsx_runtime.jsxs)("div", {
											className: css.abPWs,
											children: [(0, react_jsx_runtime.jsxs)("button", {
												type: "button",
												className: css.abPWsBtn,
												"aria-haspopup": "listbox",
												"aria-expanded": dagWsMenu,
												onClick: () => setDagWsMenu((value) => !value),
												children: [(0, react_jsx_runtime.jsx)("span", {
													className: css.abPWsTitle,
													children: wsTitle
												}), (0, react_jsx_runtime.jsx)("span", {
													className: css.abPWsChevron,
													children: (0, react_jsx_runtime.jsx)(_deepseek_ai_dsh_client_ui_primitives.IconChevronDownOutline14, { size: 14 })
												})]
											}), dagWsMenu && snapshot.workspaces.length > 0 && (0, react_jsx_runtime.jsx)("div", {
												className: css.abPWsMenu,
												role: "listbox",
												children: snapshot.workspaces.map((item) => (0, react_jsx_runtime.jsxs)("button", {
													type: "button",
													className: css.abPWsItem,
													role: "option",
													"data-active": item.id === workspace?.id || void 0,
													"aria-selected": item.id === workspace?.id,
													onClick: () => selectWorkspace(item.id),
													children: [(0, react_jsx_runtime.jsx)("span", { children: item.title }), (0, react_jsx_runtime.jsx)("span", {
														className: css.abPWsItemPath,
														children: item.path
													})]
												}, item.id))
											})]
										})
									}), (0, react_jsx_runtime.jsx)(DagView, {
										tasks: workspaceTasks,
										flows: workspaceFlows,
										selectedFlowId,
										onSelectFlow: (id) => {
											setStoredFlow(id);
											try {
												localStorage.setItem(FLOW_KEY, id);
											} catch {}
										},
										sidebarWidth,
										onSidebarResizeDown,
										onSidebarResizeMove,
										onSidebarResizeUp,
										nowMs,
										consumeEscRef: dagEscRef
									})]
								})
							}),
							(0, react_jsx_runtime.jsx)("div", {
								className: css.abPNoteGrip,
								"aria-label": "缩放窗口",
								onPointerDown: dagNote.onResizeDown,
								onPointerMove: dagNote.onResizeMove,
								onPointerUp: dagNote.onResizeUp,
								onPointerCancel: dagNote.onResizeUp
							})
						]
					}),
					focusedTask !== null && floatAnchor !== null && (0, react_jsx_runtime.jsx)(TaskFloat, {
						task: focusedTask,
						nowMs,
						anchor: floatAnchor,
						onReady: (el) => {
							floatRef.current = el;
						},
						onClose: () => {
							setFocusedTaskId(null);
							setFloatAnchor(null);
						}
					})
				]
			});
		}
		/** Fallback sheet used when the CSS-module import is not a raw stylesheet. */
		const PANEL_CSS = "/* v1.1 task panel. Class prefix abP* stays clear of the host. Colors are\r\n   --dsw-alias-* tokens only; motion stays ≤150ms. */\r\n\r\n\r\n\r\n.abPRoot {\r\n  position: contents;\r\n  font-family: var(--dsw-font-family);\r\n  color: var(--dsw-alias-label-primary);\r\n  line-height: 1.45;\r\n}\r\n\r\n.abPCapsule {\r\n  position: fixed;\r\n  top: 50%;\r\n  right: 0;\r\n  z-index: 40;\r\n  display: flex;\r\n  flex-direction: column;\r\n  align-items: center;\r\n  justify-content: center;\r\n  gap: 6px;\r\n  width: 48px;\r\n  min-height: 84px;\r\n  padding: 14px 6px;\r\n  border: 1px solid var(--dsw-alias-border-l2);\r\n  border-right: none;\r\n  border-radius: 12px 0 0 12px;\r\n  background: color-mix(in srgb, var(--dsw-alias-bg-layer-1) 90%, transparent);\r\n  color: var(--dsw-alias-label-primary);\r\n  box-shadow: -6px 0 20px var(--dsw-alias-bg-mask-2);\r\n  backdrop-filter: blur(12px);\r\n  cursor: pointer;\r\n  transform: translateY(-50%);\r\n  transition: transform 150ms var(--ds-ease-in-out, ease);\r\n}\r\n\r\n.abPCapsule:focus-visible {\r\n  outline: 2px solid var(--dsw-alias-state-business-primary);\r\n  outline-offset: 2px;\r\n}\r\n\r\n.abPCapsule[data-open] {\r\n  border-color: var(--dsw-alias-state-business-primary);\r\n}\r\n\r\n.abPCapsuleCount {\r\n  font-size: 22px;\r\n  font-weight: 600;\r\n  line-height: 28px;\r\n  font-variant-numeric: tabular-nums;\r\n  letter-spacing: -0.03em;\r\n}\r\n\r\n.abPCapsuleMeta {\r\n  font-size: 11px;\r\n  line-height: 14px;\r\n  color: var(--dsw-alias-label-tertiary);\r\n}\r\n\r\n.abPCapsuleDot {\r\n  width: 8px;\r\n  height: 8px;\r\n  border-radius: 50%;\r\n  background: var(--dsw-alias-label-tertiary);\r\n  opacity: 0.65;\r\n}\r\n\r\n.abPCapsule[data-loading] .abPCapsuleDot,\r\n.abPCapsule[data-loading] .abPCapsuleCount {\r\n  animation: abPPulse 1.2s var(--ds-ease-in-out, ease) infinite;\r\n}\r\n\r\n.abPPreview {\r\n  position: fixed;\r\n  top: 50%;\r\n  right: 58px;\r\n  z-index: 41;\r\n  width: 280px;\r\n  padding: 14px 16px;\r\n  border: 1px solid var(--dsw-alias-border-l2);\r\n  border-radius: 12px;\r\n  background: color-mix(in srgb, var(--dsw-alias-bg-layer-1) 94%, transparent);\r\n  box-shadow: -8px 6px 24px var(--dsw-alias-bg-mask-2);\r\n  backdrop-filter: blur(12px);\r\n  transform: translateY(-50%);\r\n  pointer-events: none;\r\n}\r\n\r\nhtml[data-agent-bus-panel-open] .abPPreview {\r\n  display: none;\r\n}\r\n\r\n.abPPreviewHead {\r\n  display: flex;\r\n  flex-direction: column;\r\n  gap: 4px;\r\n  margin-bottom: 12px;\r\n  padding-bottom: 10px;\r\n  border-bottom: 1px solid var(--dsw-alias-border-l1);\r\n}\r\n\r\n.abPPreviewWs {\r\n  font-size: 13px;\r\n  font-weight: 600;\r\n  line-height: 20px;\r\n  color: var(--dsw-alias-label-primary);\r\n  overflow: hidden;\r\n  text-overflow: ellipsis;\r\n  white-space: nowrap;\r\n}\r\n\r\n.abPPreviewStats {\r\n  font-size: 12px;\r\n  line-height: 18px;\r\n  color: var(--dsw-alias-label-secondary);\r\n}\r\n\r\n.abPPreviewList {\r\n  display: flex;\r\n  flex-direction: column;\r\n  gap: 10px;\r\n}\r\n\r\n.abPPreviewRow {\r\n  display: grid;\r\n  grid-template-columns: 8px minmax(0, 1fr);\r\n  gap: 10px;\r\n  align-items: start;\r\n}\r\n\r\n.abPPreviewTo {\r\n  font-size: 12px;\r\n  line-height: 18px;\r\n  color: var(--dsw-alias-label-secondary);\r\n}\r\n\r\n.abPPreviewText {\r\n  font-size: 13px;\r\n  line-height: 20px;\r\n  color: var(--dsw-alias-label-primary);\r\n  overflow: hidden;\r\n  display: -webkit-box;\r\n  -webkit-line-clamp: 2;\r\n  -webkit-box-orient: vertical;\r\n}\r\n\r\n.abPPreviewEmpty {\r\n  font-size: 13px;\r\n  line-height: 20px;\r\n  color: var(--dsw-alias-label-tertiary);\r\n}\r\n\r\n.abPLauncher {\r\n  position: fixed;\r\n  right: 60px;\r\n  top: 50%;\r\n  z-index: 45;\r\n  display: grid;\r\n  grid-template-columns: repeat(3, 1fr);\r\n  gap: 8px;\r\n  width: 228px;\r\n  padding: 12px;\r\n  border: 1px solid var(--dsw-alias-border-l2);\r\n  border-radius: 16px;\r\n  background: color-mix(in srgb, var(--dsw-alias-bg-layer-1) 92%, transparent);\r\n  box-shadow: -8px 8px 28px var(--dsw-alias-bg-mask-2);\r\n  backdrop-filter: blur(14px);\r\n  transform: translateY(-50%);\r\n}\r\n\r\n.abPLaunchTile {\r\n  display: flex;\r\n  flex-direction: column;\r\n  align-items: center;\r\n  justify-content: center;\r\n  gap: 4px;\r\n  aspect-ratio: 1;\r\n  padding: 6px;\r\n  border: 1px solid var(--dsw-alias-border-l2);\r\n  border-radius: 12px;\r\n  background: var(--dsw-alias-bg-layer-2);\r\n  color: var(--dsw-alias-label-primary);\r\n  font: inherit;\r\n  font-size: 12px;\r\n  line-height: 16px;\r\n  cursor: pointer;\r\n}\r\n\r\n.abPLaunchTile:hover {\r\n  background: var(--dsw-alias-interactive-bg-hover);\r\n}\r\n\r\n.abPLaunchTile[data-active] {\r\n  border-color: var(--dsw-alias-state-business-primary);\r\n}\r\n\r\n.abPLaunchTile:disabled {\r\n  opacity: 0.38;\r\n  cursor: default;\r\n}\r\n\r\n.abPLaunchMark {\r\n  font-size: 16px;\r\n  font-weight: 600;\r\n  line-height: 22px;\r\n  color: var(--dsw-alias-state-business-primary);\r\n}\r\n\r\n.abPNote {\r\n  position: fixed;\r\n  z-index: 48;\r\n  box-sizing: border-box;\r\n  display: flex;\r\n  flex-direction: column;\r\n  min-width: 360px;\r\n  min-height: 320px;\r\n  overflow: hidden;\r\n  border: 1px solid var(--dsw-alias-border-l2);\r\n  border-radius: 12px;\r\n  background: color-mix(in srgb, var(--dsw-alias-bg-layer-1) 94%, transparent);\r\n  box-shadow: 0 16px 40px var(--dsw-alias-bg-mask-2);\r\n  backdrop-filter: blur(12px);\r\n  --dsh-scrollbar-thumb: var(--dsw-alias-scrollbar-bg-l2);\r\n  --dsh-scrollbar-thumb-hover: var(--dsw-alias-scrollbar-hover-l2);\r\n}\r\n\r\n.abPNote[data-pinned] {\r\n  z-index: 49;\r\n  box-shadow: 0 18px 44px var(--dsw-alias-bg-mask-1);\r\n}\r\n\r\n.abPNote[data-front] {\r\n  z-index: 50;\r\n}\r\n\r\n.abPNoteBar {\r\n  display: flex;\r\n  align-items: center;\r\n  gap: 8px;\r\n  flex: none;\r\n  min-height: 36px;\r\n  padding: 4px 8px 4px 12px;\r\n  border-bottom: 1px solid var(--dsw-alias-border-l2);\r\n  cursor: grab;\r\n  user-select: none;\r\n  touch-action: none;\r\n}\r\n\r\n.abPNoteBar:active {\r\n  cursor: grabbing;\r\n}\r\n\r\n.abPNoteTitle {\r\n  min-width: 0;\r\n  flex: 1;\r\n  overflow: hidden;\r\n  text-overflow: ellipsis;\r\n  white-space: nowrap;\r\n  font-size: 13px;\r\n  font-weight: 600;\r\n  line-height: 20px;\r\n}\r\n\r\n.abPNotePin[data-on] {\r\n  color: var(--dsw-alias-state-business-primary);\r\n}\r\n\r\n.abPNoteBody {\r\n  display: flex;\r\n  flex-direction: column;\r\n  min-height: 0;\r\n  flex: 1;\r\n}\r\n\r\n.abPNoteGrip {\r\n  position: absolute;\r\n  right: 2px;\r\n  bottom: 2px;\r\n  width: 14px;\r\n  height: 14px;\r\n  cursor: nwse-resize;\r\n  touch-action: none;\r\n  background:\r\n    linear-gradient(\r\n      135deg,\r\n      transparent 50%,\r\n      var(--dsw-alias-label-tertiary) 50%,\r\n      var(--dsw-alias-label-tertiary) 60%,\r\n      transparent 60%,\r\n      transparent 75%,\r\n      var(--dsw-alias-label-tertiary) 75%\r\n    );\r\n}\r\n\r\n.abPDrawer {\r\n  display: flex;\r\n  flex-direction: column;\r\n  min-height: 0;\r\n  flex: 1;\r\n}\r\n\r\n.abPTop {\r\n  display: flex;\r\n  align-items: center;\r\n  gap: 10px;\r\n  flex: none;\r\n  min-height: 56px;\r\n  padding: 10px 12px 10px 14px;\r\n  border-bottom: 1px solid var(--dsw-alias-border-l2);\r\n  background: var(--dsw-alias-bg-layer-1);\r\n}\r\n\r\n.abPWs {\r\n  position: relative;\r\n  min-width: 0;\r\n  flex: 1;\r\n}\r\n\r\n.abPWsBtn {\r\n  display: flex;\r\n  align-items: center;\r\n  gap: 8px;\r\n  width: 100%;\r\n  min-height: 36px;\r\n  padding: 6px 10px;\r\n  border: 1px solid var(--dsw-alias-border-l2);\r\n  border-radius: 10px;\r\n  background: var(--dsw-alias-bg-layer-2);\r\n  color: var(--dsw-alias-label-primary);\r\n  font: inherit;\r\n  text-align: left;\r\n  cursor: pointer;\r\n}\r\n\r\n.abPWsBtn:hover {\r\n  background: var(--dsw-alias-interactive-bg-hover);\r\n}\r\n\r\n.abPWsBtn:focus-visible,\r\n.abPClose:focus-visible,\r\n.abPSession:focus-visible,\r\n.abPTask:focus-visible,\r\n.abPDagNode:focus-visible,\r\n.abPDagTool:focus-visible,\r\n.abPFlow:focus-visible,\r\n.abPWsItem:focus-visible,\r\n.abPAllBtn:focus-visible,\r\n.abPAllToggle:focus-visible,\r\n.abPOfflineToggle:focus-visible {\r\n  outline: 2px solid var(--dsw-alias-state-business-primary);\r\n  outline-offset: 1px;\r\n}\r\n\r\n.abPWsTitle {\r\n  min-width: 0;\r\n  flex: 1;\r\n  overflow: hidden;\r\n  text-overflow: ellipsis;\r\n  white-space: nowrap;\r\n  font-size: 14px;\r\n  font-weight: 600;\r\n  line-height: 22px;\r\n}\r\n\r\n.abPWsChevron {\r\n  flex: none;\r\n  display: inline-flex;\r\n  color: var(--dsw-alias-label-tertiary);\r\n}\r\n\r\n.abPWsMenu {\r\n  position: absolute;\r\n  top: calc(100% + 6px);\r\n  left: 0;\r\n  right: 0;\r\n  z-index: 3;\r\n  max-height: 280px;\r\n  overflow: auto;\r\n  padding: 6px;\r\n  border: 1px solid var(--dsw-alias-border-l2);\r\n  border-radius: 10px;\r\n  background: var(--dsw-alias-bg-layer-1);\r\n  box-shadow: 0 10px 24px var(--dsw-alias-bg-mask-2);\r\n}\r\n\r\n.abPWsItem {\r\n  display: flex;\r\n  flex-direction: column;\r\n  gap: 2px;\r\n  width: 100%;\r\n  padding: 8px 10px;\r\n  border: none;\r\n  border-radius: 8px;\r\n  background: transparent;\r\n  color: inherit;\r\n  font: inherit;\r\n  text-align: left;\r\n  cursor: pointer;\r\n}\r\n\r\n.abPWsItem:hover,\r\n.abPWsItem[data-active] {\r\n  background: var(--dsw-alias-interactive-bg-hover);\r\n}\r\n\r\n.abPWsItemPath {\r\n  font-size: 12px;\r\n  line-height: 18px;\r\n  color: var(--dsw-alias-label-tertiary);\r\n  overflow: hidden;\r\n  text-overflow: ellipsis;\r\n  white-space: nowrap;\r\n}\r\n\r\n.abPClose {\r\n  flex: none;\r\n  display: inline-flex;\r\n  align-items: center;\r\n  justify-content: center;\r\n  width: 32px;\r\n  height: 32px;\r\n  padding: 0;\r\n  border: none;\r\n  border-radius: 8px;\r\n  background: transparent;\r\n  color: var(--dsw-alias-label-secondary);\r\n  cursor: pointer;\r\n}\r\n\r\n.abPClose:hover {\r\n  background: var(--dsw-alias-interactive-bg-hover);\r\n  color: var(--dsw-alias-label-primary);\r\n}\r\n\r\n.abPBody {\r\n  display: flex;\r\n  min-height: 0;\r\n  flex: 1;\r\n}\r\n\r\n.abPSessions {\r\n  position: relative;\r\n  display: flex;\r\n  flex-direction: column;\r\n  flex: none;\r\n  width: 160px;\r\n  min-width: 128px;\r\n  max-width: 280px;\r\n  padding: 10px 8px;\r\n  overflow: auto;\r\n  border-right: 1px solid var(--dsw-alias-border-l2);\r\n  background: var(--dsw-specific-sidebar-fill, var(--dsw-alias-bg-module-platform));\r\n}\r\n\r\n.abPResize {\r\n  position: absolute;\r\n  top: 0;\r\n  right: -3px;\r\n  z-index: 3;\r\n  width: 6px;\r\n  height: 100%;\r\n  cursor: col-resize;\r\n  touch-action: none;\r\n}\r\n\r\n.abPResize:hover,\r\n.abPResize:active {\r\n  background: color-mix(in srgb, var(--dsw-alias-state-business-primary) 40%, transparent);\r\n}\r\n\r\n.abPGroup {\r\n  display: flex;\r\n  flex-direction: column;\r\n  margin-top: 10px;\r\n  padding-top: 10px;\r\n  border-top: 1px solid var(--dsw-alias-border-l2);\r\n}\r\n\r\n.abPAll {\r\n  display: flex;\r\n  align-items: stretch;\r\n  gap: 2px;\r\n  margin-bottom: 6px;\r\n}\r\n\r\n.abPAllBtn {\r\n  display: flex;\r\n  align-items: center;\r\n  min-width: 0;\r\n  flex: 1;\r\n  min-height: 34px;\r\n  padding: 6px 8px;\r\n  border: none;\r\n  border-radius: 8px;\r\n  background: transparent;\r\n  color: var(--dsw-alias-label-primary);\r\n  font: inherit;\r\n  font-size: 13px;\r\n  font-weight: 600;\r\n  line-height: 20px;\r\n  text-align: left;\r\n  cursor: pointer;\r\n}\r\n\r\n.abPAllBtn:hover,\r\n.abPAllToggle:hover {\r\n  background: var(--dsw-alias-interactive-bg-hover);\r\n}\r\n\r\n.abPAllBtn[data-active],\r\n.abPSession[data-active] {\r\n  background: var(--dsw-alias-button-ghost-active-fill);\r\n}\r\n\r\n.abPAllToggle {\r\n  flex: none;\r\n  display: inline-flex;\r\n  align-items: center;\r\n  justify-content: center;\r\n  width: 28px;\r\n  border: none;\r\n  border-radius: 8px;\r\n  background: transparent;\r\n  color: var(--dsw-alias-label-tertiary);\r\n  cursor: pointer;\r\n}\r\n\r\n.abPAllToggle[data-open] {\r\n  color: var(--dsw-alias-label-secondary);\r\n}\r\n\r\n.abPAllToggle[data-open] svg {\r\n  transform: rotate(180deg);\r\n}\r\n\r\n.abPAllToggle svg {\r\n  transition: transform 150ms var(--ds-ease-in-out, ease);\r\n}\r\n\r\n.abPSessionList {\r\n  display: flex;\r\n  flex-direction: column;\r\n  gap: 2px;\r\n}\r\n\r\n.abPSession {\r\n  display: flex;\r\n  align-items: flex-start;\r\n  gap: 8px;\r\n  width: 100%;\r\n  min-height: 34px;\r\n  padding: 6px 8px;\r\n  border: none;\r\n  border-radius: 8px;\r\n  background: transparent;\r\n  color: var(--dsw-alias-label-primary);\r\n  font: inherit;\r\n  font-size: 13px;\r\n  line-height: 20px;\r\n  text-align: left;\r\n  cursor: pointer;\r\n}\r\n\r\n.abPSession:hover {\r\n  background: var(--dsw-alias-interactive-bg-hover);\r\n}\r\n\r\n.abPSession[data-current] .abPSessionTitle {\r\n  font-weight: 600;\r\n}\r\n\r\n.abPSessionText {\r\n  display: flex;\r\n  flex-direction: column;\r\n  gap: 1px;\r\n  min-width: 0;\r\n  flex: 1;\r\n}\r\n\r\n.abPSessionTitle {\r\n  min-width: 0;\r\n  overflow: hidden;\r\n  text-overflow: ellipsis;\r\n  white-space: nowrap;\r\n}\r\n\r\n.abPOffline {\r\n  font-size: 11px;\r\n  line-height: 16px;\r\n  color: var(--dsw-alias-label-tertiary);\r\n}\r\n\r\n.abPOfflineToggle {\r\n  display: flex;\r\n  align-items: center;\r\n  justify-content: space-between;\r\n  gap: 6px;\r\n  width: 100%;\r\n  min-height: 30px;\r\n  margin-top: 4px;\r\n  padding: 4px 8px;\r\n  border: none;\r\n  border-radius: 8px;\r\n  background: transparent;\r\n  color: var(--dsw-alias-label-tertiary);\r\n  font: inherit;\r\n  font-size: 12px;\r\n  line-height: 18px;\r\n  text-align: left;\r\n  cursor: pointer;\r\n}\r\n\r\n.abPOfflineToggle:hover {\r\n  background: var(--dsw-alias-interactive-bg-hover);\r\n  color: var(--dsw-alias-label-secondary);\r\n}\r\n\r\n.abPOfflineToggle svg {\r\n  flex: none;\r\n  transition: transform 150ms var(--ds-ease-in-out, ease);\r\n}\r\n\r\n.abPOfflineToggle[data-open] svg {\r\n  transform: rotate(180deg);\r\n}\r\n\r\n.abPLive {\r\n  flex: none;\r\n  width: 7px;\r\n  height: 7px;\r\n  margin-top: 6px;\r\n  border-radius: 50%;\r\n  background: var(--dsw-alias-label-tertiary);\r\n}\r\n\r\n.abPLive[data-on] {\r\n  background: var(--dsw-alias-state-success-primary);\r\n}\r\n\r\n.abPFlowHead {\r\n  padding: 2px 8px 6px;\r\n  font-size: 11px;\r\n  font-weight: 600;\r\n  line-height: 16px;\r\n  color: var(--dsw-alias-label-tertiary);\r\n}\r\n\r\n.abPFlowList {\r\n  display: flex;\r\n  flex-direction: column;\r\n  gap: 2px;\r\n}\r\n\r\n.abPFlowEmpty {\r\n  padding: 4px 8px 8px;\r\n  font-size: 12px;\r\n  line-height: 18px;\r\n  color: var(--dsw-alias-label-tertiary);\r\n}\r\n\r\n.abPFlow {\r\n  display: flex;\r\n  align-items: center;\r\n  gap: 8px;\r\n  width: 100%;\r\n  min-height: 36px;\r\n  padding: 6px 8px;\r\n  border: none;\r\n  border-radius: 8px;\r\n  background: transparent;\r\n  color: inherit;\r\n  font: inherit;\r\n  text-align: left;\r\n  cursor: pointer;\r\n}\r\n\r\n.abPFlow:hover {\r\n  background: var(--dsw-alias-interactive-bg-hover);\r\n}\r\n\r\n.abPFlow[data-active] {\r\n  background: var(--dsw-alias-button-ghost-active-fill);\r\n}\r\n\r\n.abPFlowName {\r\n  min-width: 0;\r\n  flex: 1;\r\n  overflow: hidden;\r\n  text-overflow: ellipsis;\r\n  white-space: nowrap;\r\n  font-size: 13px;\r\n  line-height: 20px;\r\n}\r\n\r\n.abPFlow[data-archived] .abPFlowName {\r\n  color: var(--dsw-alias-label-tertiary);\r\n}\r\n\r\n.abPFlowCount {\r\n  flex: none;\r\n  min-width: 20px;\r\n  padding: 0 6px;\r\n  border-radius: 999px;\r\n  background: var(--dsw-alias-interactive-bg-hover);\r\n  color: var(--dsw-alias-label-secondary);\r\n  font-size: 11px;\r\n  line-height: 18px;\r\n  font-variant-numeric: tabular-nums;\r\n  text-align: center;\r\n}\r\n\r\n.abPFlow[data-active] .abPFlowCount {\r\n  color: var(--dsw-alias-state-business-primary);\r\n  background: var(--dsw-alias-state-business-tertiary);\r\n}\r\n\r\n.abPMain {\r\n  position: relative;\r\n  display: flex;\r\n  flex-direction: column;\r\n  gap: 8px;\r\n  min-width: 0;\r\n  flex: 1;\r\n  padding: 12px;\r\n  overflow: auto;\r\n}\r\n\r\n.abPEmpty {\r\n  display: flex;\r\n  flex-direction: column;\r\n  align-items: center;\r\n  justify-content: center;\r\n  gap: 6px;\r\n  min-height: 160px;\r\n  padding: 24px 16px;\r\n  text-align: center;\r\n}\r\n\r\n.abPEmptyTitle {\r\n  font-size: 14px;\r\n  line-height: 22px;\r\n  color: var(--dsw-alias-label-secondary);\r\n}\r\n\r\n.abPEmptyHint {\r\n  font-size: 12px;\r\n  line-height: 18px;\r\n  color: var(--dsw-alias-label-tertiary);\r\n}\r\n\r\n.abPTask {\r\n  display: flex;\r\n  flex-direction: column;\r\n  gap: 4px;\r\n  width: 100%;\r\n  padding: 10px 12px;\r\n  border: 1px solid var(--dsw-alias-border-l2);\r\n  border-radius: 10px;\r\n  background: var(--dsw-alias-bg-layer-2);\r\n  color: inherit;\r\n  font: inherit;\r\n  text-align: left;\r\n  cursor: pointer;\r\n}\r\n\r\n.abPTask:hover,\r\n.abPTask[data-focused] {\r\n  background: var(--dsw-alias-interactive-bg-hover);\r\n}\r\n\r\n.abPTask[data-focused],\r\n.abPTask[data-current] {\r\n  border-color: color-mix(in srgb, var(--dsw-alias-state-business-primary) 50%, var(--dsw-alias-border-l2));\r\n}\r\n\r\n.abPTaskSummary {\r\n  display: flex;\r\n  flex-direction: column;\r\n  gap: 6px;\r\n  min-width: 0;\r\n}\r\n\r\n.abPTaskLine {\r\n  display: flex;\r\n  align-items: center;\r\n  gap: 8px;\r\n  min-width: 0;\r\n}\r\n\r\n.abPTaskPreview {\r\n  min-width: 0;\r\n  overflow: hidden;\r\n  text-overflow: ellipsis;\r\n  white-space: nowrap;\r\n  font-size: 14px;\r\n  line-height: 22px;\r\n}\r\n\r\n.abPTaskMeta {\r\n  font-size: 12px;\r\n  line-height: 18px;\r\n  color: var(--dsw-alias-label-tertiary);\r\n  overflow: hidden;\r\n  text-overflow: ellipsis;\r\n  white-space: nowrap;\r\n}\r\n\r\n.abPDot {\r\n  width: 8px;\r\n  height: 8px;\r\n  margin-top: 7px;\r\n  border-radius: 50%;\r\n  background: var(--dsw-alias-label-tertiary);\r\n}\r\n\r\n.abPDot[data-tone='business'] { background: var(--dsw-alias-state-business-primary); }\r\n.abPDot[data-tone='warning'] { background: var(--dsw-alias-state-warn-primary); }\r\n.abPDot[data-tone='success'] { background: var(--dsw-alias-state-success-primary); }\r\n.abPDot[data-tone='danger'] { background: var(--dsw-alias-state-error-primary); }\r\n.abPDot[data-tone='tertiary'] { background: var(--dsw-alias-label-tertiary); }\r\n\r\n.abPBadge {\r\n  flex: none;\r\n  padding: 0 7px;\r\n  border: 1px solid transparent;\r\n  border-radius: 5px;\r\n  font-size: 12px;\r\n  line-height: 20px;\r\n  color: var(--dsw-alias-label-secondary);\r\n  background: var(--dsw-alias-interactive-bg-hover);\r\n}\r\n\r\n.abPBadge[data-tone='business'] {\r\n  color: var(--dsw-alias-state-business-primary);\r\n  background: var(--dsw-alias-state-business-tertiary);\r\n}\r\n\r\n.abPBadge[data-tone='warning'] {\r\n  color: var(--dsw-alias-state-warn-label);\r\n  background: var(--dsw-alias-state-warn-tertiary);\r\n}\r\n\r\n.abPBadge[data-tone='success'] {\r\n  color: var(--dsw-alias-state-success-primary);\r\n  background: var(--dsw-alias-state-success-tertiary);\r\n}\r\n\r\n.abPBadge[data-tone='danger'] {\r\n  color: var(--dsw-alias-state-error-primary);\r\n  background: var(--dsw-alias-interactive-bg-hover-danger);\r\n}\r\n\r\n.abPBadge[data-kind='dashed'] {\r\n  border-color: var(--dsw-alias-state-warn-primary);\r\n  border-style: dashed;\r\n  background: transparent;\r\n}\r\n\r\n.abPBadge[data-kind='outline'] {\r\n  border-color: var(--dsw-alias-border-l3);\r\n  background: transparent;\r\n}\r\n\r\n.abPFloat {\r\n  position: fixed;\r\n  z-index: 50;\r\n  box-sizing: border-box;\r\n  display: flex;\r\n  flex-direction: column;\r\n  gap: 12px;\r\n  max-height: min(72vh, 560px);\r\n  overflow: auto;\r\n  padding: 14px 16px;\r\n  border: 1px solid var(--dsw-alias-border-l2);\r\n  border-radius: 14px;\r\n  background: color-mix(in srgb, var(--dsw-alias-bg-layer-1) 92%, transparent);\r\n  box-shadow: 0 18px 40px var(--dsw-alias-bg-mask-2);\r\n  backdrop-filter: blur(14px);\r\n  pointer-events: auto;\r\n}\r\n\r\n.abPFloatTop {\r\n  display: grid;\r\n  grid-template-columns: 8px minmax(0, 1fr) auto;\r\n  gap: 10px;\r\n  align-items: start;\r\n}\r\n\r\n.abPFloatTitle {\r\n  min-width: 0;\r\n  overflow: hidden;\r\n  text-overflow: ellipsis;\r\n  white-space: nowrap;\r\n  font-size: 14px;\r\n  line-height: 22px;\r\n}\r\n\r\n.abPChainArrow {\r\n  margin: 0 6px;\r\n  color: var(--dsw-alias-label-tertiary);\r\n}\r\n\r\n.abPCalls {\r\n  display: flex;\r\n  flex-direction: column;\r\n  gap: 10px;\r\n}\r\n\r\n.abPCall {\r\n  display: flex;\r\n  flex-direction: column;\r\n  gap: 6px;\r\n  padding: 10px 12px;\r\n  border: 1px solid var(--dsw-alias-border-l2);\r\n  border-radius: 10px;\r\n  background: var(--dsw-alias-bg-layer-2);\r\n}\r\n\r\n.abPCallHead {\r\n  display: flex;\r\n  flex-wrap: wrap;\r\n  align-items: baseline;\r\n  gap: 2px 0;\r\n}\r\n\r\n.abPCallWho {\r\n  font-size: 14px;\r\n  font-weight: 600;\r\n  line-height: 22px;\r\n  color: var(--dsw-alias-label-primary);\r\n}\r\n\r\n.abPCallRoles {\r\n  margin-left: 8px;\r\n  font-size: 11px;\r\n  line-height: 16px;\r\n  color: var(--dsw-alias-label-tertiary);\r\n}\r\n\r\n.abPCallSummary {\r\n  font-size: 13px;\r\n  line-height: 20px;\r\n  color: var(--dsw-alias-label-secondary);\r\n  overflow-wrap: anywhere;\r\n}\r\n\r\n.abPCallCost {\r\n  display: flex;\r\n  flex-direction: column;\r\n  gap: 2px;\r\n}\r\n\r\n.abPCallCostName {\r\n  font-size: 12px;\r\n  line-height: 18px;\r\n  color: var(--dsw-alias-label-primary);\r\n}\r\n\r\n.abPContent {\r\n  max-height: 240px;\r\n  margin: 0;\r\n  padding: 10px 12px;\r\n  overflow: auto;\r\n  border-radius: 8px;\r\n  background: var(--dsw-alias-markdown-code-block);\r\n  color: var(--dsw-alias-label-secondary);\r\n  font-family: var(--ds-font-family-code);\r\n  font-size: 12px;\r\n  line-height: 20px;\r\n  white-space: pre-wrap;\r\n  word-break: break-word;\r\n}\r\n\r\n.abPZone {\r\n  font-size: 12px;\r\n  line-height: 18px;\r\n  color: var(--dsw-alias-label-tertiary);\r\n}\r\n\r\n.abPZone[data-missing] {\r\n  color: var(--dsw-alias-state-error-primary);\r\n}\r\n\r\n.abPStaff {\r\n  display: flex;\r\n  flex-direction: column;\r\n  gap: 8px;\r\n  padding-top: 8px;\r\n  border-top: 1px solid var(--dsw-alias-border-l1);\r\n}\r\n\r\n.abPStaffHead {\r\n  font-size: 13px;\r\n  font-weight: 600;\r\n  line-height: 20px;\r\n  color: var(--dsw-alias-label-secondary);\r\n}\r\n\r\n.abPStaffRow {\r\n  display: grid;\r\n  grid-template-columns: 2em minmax(3em, 1fr);\r\n  gap: 4px 10px;\r\n  align-items: baseline;\r\n  padding: 8px 0 0;\r\n  border-top: 1px solid var(--dsw-alias-border-l1);\r\n  font-size: 13px;\r\n  line-height: 20px;\r\n}\r\n\r\n.abPRole {\r\n  color: var(--dsw-alias-label-tertiary);\r\n}\r\n\r\n.abPStaffTitle {\r\n  min-width: 2em;\r\n  overflow: hidden;\r\n  text-overflow: ellipsis;\r\n  white-space: nowrap;\r\n  color: var(--dsw-alias-label-primary);\r\n}\r\n\r\n.abPTriple {\r\n  grid-column: 1 / -1;\r\n  display: flex;\r\n  flex-wrap: wrap;\r\n  gap: 6px 14px;\r\n  font-size: 12px;\r\n  line-height: 18px;\r\n  color: var(--dsw-alias-label-secondary);\r\n  font-variant-numeric: tabular-nums;\r\n}\r\n\r\n@keyframes abPPulse {\r\n  50% { opacity: 0.4; }\r\n}\r\n\r\n@keyframes abPRelease {\r\n  0%,\r\n  100% { box-shadow: 0 0 0 0 transparent; }\r\n  25%,\r\n  70% { box-shadow: 0 0 0 2px var(--dsw-alias-state-success-primary); }\r\n}\r\n\r\n.abPDagPane {\r\n  display: flex;\r\n  flex-direction: column;\r\n  min-width: 0;\r\n  min-height: 0;\r\n  flex: 1;\r\n}\r\n\r\n.abPDagCanvas {\r\n  position: relative;\r\n  min-width: 0;\r\n  min-height: 72px;\r\n  flex: 1 1 42%;\r\n  overflow: hidden;\r\n  cursor: grab;\r\n  touch-action: none;\r\n  overscroll-behavior: none;\r\n  user-select: none;\r\n  background-color: var(--dsw-alias-bg-layer-1);\r\n  background-image: radial-gradient(circle, var(--dsw-alias-border-l2) 1px, transparent 1.2px);\r\n}\r\n\r\n.abPDagCanvas[data-panning],\r\n.abPDagCanvas[data-dragging] {\r\n  cursor: grabbing;\r\n}\r\n\r\n.abPDagWorld {\r\n  position: absolute;\r\n  left: 0;\r\n  top: 0;\r\n  transform-origin: 0 0;\r\n  will-change: transform;\r\n}\r\n\r\n.abPDagSvg {\r\n  position: absolute;\r\n  display: block;\r\n  overflow: visible;\r\n  color: var(--dsw-alias-label-tertiary);\r\n  pointer-events: none;\r\n}\r\n\r\n.abPDagTools {\r\n  position: absolute;\r\n  right: 10px;\r\n  top: 10px;\r\n  z-index: 2;\r\n  display: flex;\r\n  align-items: center;\r\n  gap: 4px;\r\n  padding: 4px;\r\n  border: 1px solid var(--dsw-alias-border-l2);\r\n  border-radius: 10px;\r\n  background: color-mix(in srgb, var(--dsw-alias-bg-layer-1) 92%, transparent);\r\n  backdrop-filter: blur(10px);\r\n}\r\n\r\n.abPDagTool {\r\n  min-width: 28px;\r\n  height: 28px;\r\n  padding: 0 8px;\r\n  border: none;\r\n  border-radius: 7px;\r\n  background: transparent;\r\n  color: var(--dsw-alias-label-primary);\r\n  font: inherit;\r\n  font-size: 12px;\r\n  line-height: 18px;\r\n  cursor: pointer;\r\n}\r\n\r\n.abPDagTool:hover {\r\n  background: var(--dsw-alias-interactive-bg-hover);\r\n}\r\n\r\n.abPDagZoom {\r\n  min-width: 40px;\r\n  padding: 0 4px;\r\n  font-size: 11px;\r\n  line-height: 16px;\r\n  color: var(--dsw-alias-label-tertiary);\r\n  font-variant-numeric: tabular-nums;\r\n  text-align: center;\r\n}\r\n\r\n.abPDagEdge {\r\n  fill: none;\r\n  stroke: var(--dsw-alias-border-l3);\r\n  stroke-width: 1.5;\r\n  transition: stroke 150ms var(--ds-ease-in-out, ease), opacity 150ms var(--ds-ease-in-out, ease);\r\n}\r\n\r\n.abPDagEdge[data-tone='ok'] {\r\n  stroke: var(--dsw-alias-state-success-primary);\r\n  color: var(--dsw-alias-state-success-primary);\r\n}\r\n\r\n.abPDagEdge[data-tone='wait'] {\r\n  stroke: var(--dsw-alias-state-warn-primary);\r\n  color: var(--dsw-alias-state-warn-primary);\r\n}\r\n\r\n.abPDagEdge[data-tone='fail'] {\r\n  stroke: var(--dsw-alias-state-error-primary);\r\n  color: var(--dsw-alias-state-error-primary);\r\n}\r\n\r\n.abPDagEdge[data-tone='self'],\r\n.abPDagEdge[data-tone='down'] {\r\n  stroke: var(--dsw-alias-state-business-primary);\r\n  color: var(--dsw-alias-state-business-primary);\r\n}\r\n\r\n.abPDagEdge[data-dim] {\r\n  opacity: 0.22;\r\n}\r\n\r\n.abPDagNode {\r\n  position: absolute;\r\n  box-sizing: border-box;\r\n  display: flex;\r\n  flex-direction: column;\r\n  justify-content: center;\r\n  gap: 2px;\r\n  height: 64px;\r\n  padding: 6px 8px;\r\n  overflow: hidden;\r\n  border: 1px solid var(--dsw-alias-border-l2);\r\n  border-radius: 8px;\r\n  background: var(--dsw-alias-bg-layer-2);\r\n  color: inherit;\r\n  font: inherit;\r\n  text-align: left;\r\n  cursor: grab;\r\n  touch-action: none;\r\n  transition:\r\n    border-color 150ms var(--ds-ease-in-out, ease),\r\n    box-shadow 150ms var(--ds-ease-in-out, ease),\r\n    opacity 150ms var(--ds-ease-in-out, ease);\r\n}\r\n\r\n.abPDagNode:hover,\r\n.abPDagNode[data-chain='self'] {\r\n  background: var(--dsw-alias-interactive-bg-hover);\r\n}\r\n\r\n.abPDagNode[data-ready] {\r\n  border-style: dashed;\r\n  border-color: var(--dsw-alias-state-business-primary);\r\n}\r\n\r\n.abPDagNode[data-blocked] {\r\n  border-color: var(--dsw-alias-state-warn-primary);\r\n}\r\n\r\n.abPDagNode[data-fail] {\r\n  border-color: var(--dsw-alias-state-error-primary);\r\n}\r\n\r\n.abPDagNode[data-ok] {\r\n  border-color: var(--dsw-alias-state-success-primary);\r\n}\r\n\r\n.abPDagNode[data-chain='self'] {\r\n  box-shadow: 0 0 0 2px color-mix(in srgb, var(--dsw-alias-state-business-primary) 55%, transparent);\r\n}\r\n\r\n.abPDagNode[data-flare] {\r\n  animation: abPRelease 2s var(--ds-ease-in-out, ease);\r\n}\r\n\r\n.abPDagNode[data-dragging] {\r\n  z-index: 2;\r\n  cursor: grabbing;\r\n}\r\n\r\n.abPDagNode[data-archived] {\r\n  opacity: 0.55;\r\n  cursor: default;\r\n  pointer-events: none;\r\n}\r\n\r\n.abPDagNode[data-dim] {\r\n  opacity: 0.4;\r\n}\r\n\r\n.abPDagNode[data-archived][data-dim] {\r\n  opacity: 0.4;\r\n}\r\n\r\n.abPDagNode[data-current] {\r\n  border-color: color-mix(in srgb, var(--dsw-alias-state-business-primary) 50%, var(--dsw-alias-border-l2));\r\n}\r\n\r\n.abPDagNodeTop {\r\n  display: flex;\r\n  align-items: center;\r\n  gap: 6px;\r\n  min-width: 0;\r\n  overflow: hidden;\r\n}\r\n\r\n.abPDagNode .abPDot {\r\n  margin-top: 0;\r\n  flex: none;\r\n}\r\n\r\n.abPDagNode .abPBadge {\r\n  padding: 0 5px;\r\n  font-size: 11px;\r\n  line-height: 18px;\r\n}\r\n\r\n.abPDagMark {\r\n  min-width: 0;\r\n  overflow: hidden;\r\n  text-overflow: ellipsis;\r\n  white-space: nowrap;\r\n  font-size: 11px;\r\n  line-height: 16px;\r\n  color: var(--dsw-alias-label-tertiary);\r\n}\r\n\r\n.abPDagNode[data-fail] .abPDagMark {\r\n  color: var(--dsw-alias-state-error-primary);\r\n}\r\n\r\n.abPDagNodeLabel {\r\n  overflow: hidden;\r\n  text-overflow: ellipsis;\r\n  white-space: nowrap;\r\n  font-size: 12px;\r\n  line-height: 18px;\r\n}\r\n\r\n.abPDagDetail {\r\n  display: flex;\r\n  flex-direction: column;\r\n  gap: 8px;\r\n  min-height: 0;\r\n  flex: 1 1 58%;\r\n  max-height: 70%;\r\n  overflow: hidden;\r\n  padding: 10px 12px 12px;\r\n  border-top: 1px solid var(--dsw-alias-border-l2);\r\n  background: var(--dsw-alias-bg-layer-1);\r\n}\r\n\r\n.abPReq {\r\n  display: flex;\r\n  flex-direction: column;\r\n  gap: 8px;\r\n  min-height: 6.5em;\r\n  flex: 1 1 auto;\r\n  overflow: auto;\r\n}\r\n\r\n.abPReq .abPContent {\r\n  min-height: 3.5em;\r\n  max-height: none;\r\n}\r\n\r\n.abPDagMore {\r\n  display: flex;\r\n  flex-direction: column;\r\n  gap: 8px;\r\n  flex: 0 1 auto;\r\n  min-height: 0;\r\n  max-height: 38%;\r\n  overflow: auto;\r\n}\r\n\r\n.abPDagDetail .abPContent,\r\n.abPFloat .abPContent {\r\n  max-height: none;\r\n}\r\n\r\n.abPDagFail {\r\n  font-size: 12px;\r\n  line-height: 18px;\r\n  color: var(--dsw-alias-state-error-primary);\r\n}\r\n\r\n@media (prefers-reduced-motion: reduce) {\r\n  .abPCapsule,\r\n  .abPDrawer,\r\n  .abPAllToggle svg,\r\n  .abPOfflineToggle svg,\r\n  .abPDagEdge,\r\n  .abPDagNode,\r\n  .abPCapsule[data-loading] .abPCapsuleDot,\r\n  .abPCapsule[data-loading] .abPCapsuleCount,\r\n  .abPDagNode[data-flare] {\r\n    transition: none;\r\n    animation: none;\r\n  }\r\n}\r\n";
		//#endregion
		//#region lib/client/index.js
		/**
		* Browser plugin for dsh-agent-bus.
		*
		* Registers the collapsed `agent-bus-task` tool rows: every agent-bus tool
		* call in the conversation is rendered as one always-collapsed producer line
		* instead of the generic "Tool call" card. The registration targets the
		* keyed `tool.call.toolview` slot, one entry per tool name; an unclaimed key
		* would fall back to the generic row, so registering is purely additive.
		*
		* @module dsh-agent-bus/client
		*/
		/** Required services: slot registry plus the session list used for current-session highlight. */
		const inject = ["slots", "sessions"];
		/** Every model-facing tool this plugin renders in collapsed form. */
		const AGENT_BUS_TOOLS = [
			"list_peers",
			"send_note",
			"create_flow",
			"list_flows",
			"create_task",
			"dispatch_task",
			"edit_task",
			"list_tasks",
			"get_task",
			"report_task",
			"settle_task",
			"cancel_task",
			"request_input",
			"update_card"
		];
		/**
		* Mount the toolview registrations.
		*
		* @param ctx - the client plugin context.
		*/
		function apply(ctx) {
			for (const tool of AGENT_BUS_TOOLS) ctx.slots.inject("tool.call.toolview", () => ctx.slots.register({
				name: "tool.call.toolview",
				key: tool
			}, AgentBusToolRow));
			const host = document.createElement("div");
			host.dataset.agentBusPanelHost = "";
			document.body.appendChild(host);
			const root = (0, react_dom_client.createRoot)(host);
			root.render((0, react_jsx_runtime.jsx)(TaskPanel, { sessionsList: ctx.sessions.list }));
			ctx.effect(() => () => {
				root.unmount();
				host.remove();
			}, "agent-bus: task panel");
		}
		//#endregion
		exports.apply = apply;
		exports.inject = inject;
		return module.exports;
	}
});

//# sourceMappingURL=client.js.map