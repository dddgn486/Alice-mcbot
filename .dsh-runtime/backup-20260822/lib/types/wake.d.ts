/**
 * Wake-on-delivery (v1.5): activate a persisted session that is not
 * currently live, so a tool call never fails just because the target's
 * browser tab is closed.
 *
 * The harness exposes `AgentRegistry.resume()` — load a persisted session
 * and run an agent on it (the same mechanism sub-agents use for cold
 * resume). Delivery paths try it before falling back to queued / offline
 * queueing.
 *
 * Model resolution: a browser-attached session's model is injected by the
 * frontend's model-selection (the `{{model}}` persona variable), which a
 * headless resume does not have — without `agentOptions.provider/model`
 * the first prompt assembly fails. The waker therefore inherits the route
 * from a live session's persisted request header, or uses the configured
 * `wakeProvider` / `wakeModel` fallback. Without either, the wake is
 * refused (the caller falls back to queued / offline queueing).
 *
 * Lifecycle: an activated session STAYS live for the process lifetime. The
 * resume handle is a capability — disposing it would remove the session
 * from the store — so the plugin holds it without ever tearing it down; a
 * restart returns the session to dormant and the next delivery wakes it
 * again. Wake is therefore a process-scoped affordance, exactly like the
 * rate limiter.
 *
 * @module dsh-agent-bus/wake
 */
import type { Context } from '@deepseek-ai/cordis';
import type { Agent } from '@deepseek-ai/dsh-agent';
import type { SessionId } from '@deepseek-ai/dsh-session';
/** Set the configured fallback route once at plugin mount. */
export declare function setWakeRoute(route: {
    provider?: string;
    model?: string;
}): void;
/**
 * Resolve a session to a live agent, waking it if it is dormant.
 *
 * A live session resolves as-is; a dormant-but-persisted session is resumed
 * through the harness agent registry with an inherited model route; a
 * session that cannot be woken (never existed, resume failed, or no model
 * route is available) resolves `undefined` and the caller falls back to its
 * offline behavior (queued task, queued note, refusal).
 *
 * @param ctx - plugin context carrying the agent registry.
 * @param sessionId - the session to make live.
 * @returns the live agent, or `undefined` when the session cannot be woken.
 */
export declare function wakeSession(ctx: Context, sessionId: SessionId): Promise<Agent | undefined>;
