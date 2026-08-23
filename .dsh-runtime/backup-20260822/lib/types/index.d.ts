/**
 * Agent Bus for DeepSeek Harness.
 *
 * A host-plane plugin that gives live sessions in the same workspace a way to
 * dispatch work to each other, with a durable ledger recording what was asked
 * and how it turned out. The ledger's lifecycle follows the A2A TaskState
 * vocabulary; the settlement verdict is recorded without changing the state.
 *
 * Two planes, deliberately separate. Delivery is the harness's own: a task
 * becomes one `followup()` on the recipient's inbox, and the driver claims one
 * queued item at a time, running each as its own turn with a durability
 * checkpoint between them. The ledger is this plugin's: it records intent and
 * outcome, and never mirrors the inbox — the inbox is the execution authority
 * and the two drift by design.
 *
 * Authority is derived from durable relationships, never from a stored role.
 * Reachability comes from shared workspace membership; settlement and cancel
 * authority belong to the session recorded as a task's dispatcher. So "PM" is
 * emergent: dispatch work to someone and you are that task's dispatcher, with
 * no role to assign and no way to approve your own work.
 *
 * Installation: `dsh plugin --profile <name> add <this package>`.
 *
 * @module dsh-agent-bus
 */
import type { Context } from '@deepseek-ai/cordis';
import z from '@deepseek-ai/schemastery';
export declare const name = "agent-bus";
/**
 * Required services and provided values. `storageDomain` is a value the
 * storage-domain plugin provides (not a Service), so it is injected by name
 * exactly as the workspace package injects it. A profile that mounts neither
 * storage nor the workspace registry fails loud at load rather than booting a
 * gateway that could record nothing — misconfiguration must not degrade into
 * a silent prompt-only stub. `sessionTitle` ships with the base bundle, so it
 * resolves in every profile.
 */
export declare const inject: string[];
/** Plugin configuration. */
export interface Config {
    /** Character ceiling on relayed content; over-length content is refused, not truncated (default `16000`). */
    maxContentLength?: number;
    /** Unfinished tasks one recipient may hold before dispatch is refused (default `20`). */
    maxPendingPerAgent?: number;
    /** Dispatches one sender may issue per minute (default `10`). */
    maxSendsPerMinute?: number;
    /** Lightweight messages one sender may send per minute (default `20`). */
    maxMessagesPerMinute?: number;
    /** How long a working or input-required task may sit before failing (default `7200000`, 2 hours). */
    taskTimeoutMs?: number;
    /** How long a working task's offline executor may be gone before the initiator is asked to decide (default `900000`, 15 min). */
    offlineGraceMs?: number;
    /** Model route for woken dormant sessions; defaults to inheriting from a live session. */
    wakeProvider?: string;
    /** Model id for woken dormant sessions; defaults to inheriting from a live session. */
    wakeModel?: string;
    /** How long a working/submitted task may sit with an IDLE live executor before the heartbeat re-delivers it (default `300000`, 5 min). */
    retryIdleMs?: number;
    /** Reports longer than this are externalized to the report store (default `400`). */
    maxInlineReport?: number;
    /** Prompt-section order for the usage policy (default `118`). */
    promptSectionOrder?: number;
}
export declare const Config: z<Config>;
/**
 * Mount the gateway.
 *
 * Opens the ledger first; a failed open is loud and the tools stay
 * unregistered rather than accepting dispatches the ledger cannot record.
 *
 * @param ctx - the plugin context.
 * @param config - validated configuration.
 * @returns resolution after the ledger is open and the tools are registered.
 */
export declare function apply(ctx: Context, config: Config): Promise<void>;
