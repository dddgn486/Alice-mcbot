/**
 * DAG auto-dispatch: the code that turns a settled dependency into a
 * delivered task.
 *
 * The scheduler is deliberately dumb — it reads ledger state and delivers
 * exactly the tasks that are ready, through the same message path a tool
 * call uses. Idempotency comes from the state itself: a task whose row has a
 * messageId is already delivered, so a restart sweep or a second release
 * trigger can never double-dispatch.
 *
 * @module dsh-agent-bus/scheduler
 */
import type { Context } from '@deepseek-ai/cordis';
import { type TaskLedger } from './ledger.ts';
import type { TaskId } from './types.ts';
/**
 * Deliver one queued task: transition it to submitted, record the delivery
 * with the auto flag, and hand it to the harness inbox. Idempotent by
 * construction — a task that is not queued (already delivered, running, or
 * terminal) is skipped, so the client's event-driven POST /dispatch and the
 * server backstop sweep can race without double-delivery.
 *
 * Exported so the edit_task tool can dispatch a task whose dependencies just
 * cleared.
 *
 * @param ctx - plugin context (notifications).
 * @param ledger - the task ledger.
 * @param id - the ready task's id.
 */
export declare function dispatchOne(ctx: Context, ledger: TaskLedger, id: TaskId): Promise<void>;
/**
 * Release the dependents of one just-settled task. Every dependent that is
 * submitted, undelivered, and no longer blocked is dispatched.
 *
 * @param ctx - plugin context.
 * @param ledger - the task ledger.
 * @param taskId - the task that just settled.
 * @returns how many tasks were dispatched.
 */
export declare function releaseDependents(ctx: Context, ledger: TaskLedger, taskId: TaskId): Promise<number>;
/**
 * Sweep every ready-but-undelivered task. Used at startup (restore
 * auto-scheduling after a restart) and as a periodic backstop; idempotent by
 * construction.
 *
 * @param ctx - plugin context.
 * @param ledger - the task ledger.
 * @returns how many tasks were dispatched.
 */
export declare function dispatchReadyTasks(ctx: Context, ledger: TaskLedger): Promise<number>;
