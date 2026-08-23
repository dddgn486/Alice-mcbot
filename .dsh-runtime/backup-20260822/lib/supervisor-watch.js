import { buildTaskMessage, deliverTask } from './delivery.js';
import { wakeSession } from './wake.js';

const DEFAULT_INTERVAL_MS = 60_000;
const DEFAULT_MAX_CHECKS = 10;
const DEFAULT_MAX_WAKE_FAILURES = 3;

function actionable(task) {
    return task.status === 'completed' && task.outcome === undefined
        || task.status === 'failed'
        || task.status === 'canceled'
        || task.status === 'input-required';
}

export class SupervisorWaitWatch {
    ctx;
    ledger;
    state;
    timer;

    constructor(ctx, ledger) {
        this.ctx = ctx;
        this.ledger = ledger;
    }

    arm(supervisorId, workspacePath, options = {}) {
        this.disarm('rearmed');
        this.state = {
            supervisorId,
            workspacePath,
            intervalMs: options.intervalMs ?? DEFAULT_INTERVAL_MS,
            maxChecks: options.maxChecks ?? DEFAULT_MAX_CHECKS,
            maxWakeFailures: options.maxWakeFailures ?? DEFAULT_MAX_WAKE_FAILURES,
            checks: 0,
            wakeFailures: 0,
            awaitingTurnEnd: true,
            armedAt: new Date().toISOString(),
        };
        return this.snapshot();
    }

    disarm(reason) {
        if (this.timer !== undefined) {
            clearInterval(this.timer);
            this.timer = undefined;
        }
        if (this.state !== undefined)
            this.state = { ...this.state, active: false, stoppedReason: reason };
        return this.snapshot();
    }

    snapshot() {
        if (this.state === undefined)
            return { active: false, stoppedReason: 'never-armed' };
        return {
            active: this.state.active !== false,
            ...(this.state.stoppedReason !== undefined ? { stoppedReason: this.state.stoppedReason } : {}),
            checks: this.state.checks,
            maxChecks: this.state.maxChecks,
            wakeFailures: this.state.wakeFailures,
            maxWakeFailures: this.state.maxWakeFailures,
            intervalMs: this.state.intervalMs,
            armedAt: this.state.armedAt,
        };
    }

    onSessionEvent(session, event) {
        if (this.state === undefined || this.state.active === false || session.id !== this.state.supervisorId)
            return;
        if (event.type === 'turn/end' && this.state.awaitingTurnEnd) {
            this.state.awaitingTurnEnd = false;
            this.startTimer();
            return;
        }
        // A later normal or wake-triggered turn means the supervisor is actively
        // processing again; waiting mode must never continue behind that turn.
        if (event.type === 'turn/start' && !this.state.awaitingTurnEnd)
            this.disarm('supervisor-active');
    }

    startTimer() {
        if (this.timer !== undefined || this.state === undefined || this.state.active === false)
            return;
        this.timer = setInterval(() => {
            void this.check();
        }, this.state.intervalMs);
        this.timer.unref?.();
    }

    async check() {
        const state = this.state;
        if (state === undefined || state.active === false || state.awaitingTurnEnd)
            return;
        state.checks += 1;
        if (state.checks > state.maxChecks) {
            this.disarm('max-checks');
            return;
        }
        const pending = this.ledger.listAll()
            .filter(task => task.workspacePath === state.workspacePath
                && (task.assignedReviewer ?? task.assignedBy) === state.supervisorId
                && actionable(task));
        if (pending.length === 0) {
            if (state.checks >= state.maxChecks)
                this.disarm('max-checks');
            return;
        }
        const task = pending[0];
        let supervisor;
        try {
            supervisor = this.ctx.agents.get(state.supervisorId);
            if (supervisor === undefined)
                supervisor = await wakeSession(this.ctx, state.supervisorId);
        }
        catch {
            // Upstream/model-route resolution is not allowed to crash the timer.
            supervisor = undefined;
        }
        if (supervisor === undefined) {
            state.wakeFailures += 1;
            if (state.wakeFailures >= state.maxWakeFailures)
                this.disarm('max-wake-failures');
            return;
        }
        const notice = buildTaskMessage(state.supervisorId, task.id,
            `监督员等待守护检测到任务 ${task.id} 已进入「${task.status}」并需要处理。请调用 get_task / list_tasks 审阅后决定验收、反馈、取消或回复。`,
            'supervisor_wait_watch');
        deliverTask(supervisor, notice, 'followup');
        this.disarm('supervisor-woken');
    }
}
