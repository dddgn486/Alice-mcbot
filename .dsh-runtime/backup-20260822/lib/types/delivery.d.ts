/**
 * Delivery onto the harness agent inbox, plus the content hygiene every
 * relayed message passes through.
 *
 * The plugin owns no queue. `Agent.followup()` puts one message on the
 * recipient's `next-turn` list, which the driver claims exactly one item at a
 * time and runs as its own turn, with a durability checkpoint between items.
 * That is already "a worker completes a task list one at a time, only when
 * idle", so reimplementing it would fork the ordering authority.
 *
 * `inject()` is deliberately not offered for task delivery: injected context
 * waits in the inbox until some other message wakes the agent, so an injected
 * task can sit forever unrun.
 *
 * @module dsh-agent-bus/delivery
 */
import { createUserMessage } from '@deepseek-ai/dsh-llm';
import type { Agent } from '@deepseek-ai/dsh-agent';
import type { SessionId } from '@deepseek-ai/dsh-session';
import type { DeliveryMode } from './types.ts';
/**
 * Durable attribution for one dispatched task.
 *
 * `form: 'relay'` is the established context form for a message another agent
 * addressed to this one; the sender rides along as an opaque session id.
 * Attribution records who supplied an admitted message and grants no
 * authority — the authorization gate has already run by the time this is
 * built.
 */
export interface AgentBusTaskMessageSource {
    readonly kind: 'agent-bus-task';
    /** A message another agent addressed to this one (`relay` context form). */
    readonly form: 'relay';
    /** Session id of the dispatching agent. */
    readonly senderSessionId: SessionId;
}
/**
 * Durable attribution for one lightweight message (the non-task channel).
 *
 * Same relay form as a task, distinct kind: the recipient distinguishes a
 * message ("reply in prose, nothing to report") from a task ("do the work and
 * call report_task") by the header that rides on the content.
 */
export interface AgentBusMessageSource {
    readonly kind: 'agent-bus-message';
    /** A message another agent addressed to this one (`relay` context form). */
    readonly form: 'relay';
    /** Session id of the sending agent. */
    readonly senderSessionId: SessionId;
}
declare module '@deepseek-ai/dsh-llm' {
    interface MessageSourceMap {
        'agent-bus-task': AgentBusTaskMessageSource;
        'agent-bus-message': AgentBusMessageSource;
    }
}
/**
 * Strip terminal control sequences from relayed content.
 *
 * A gateway's whole function is to move another agent's text into this
 * agent's context, which makes escape sequences a real vector: raw CSI bytes
 * relayed into a transcript can rewrite what a reader sees. Sanitizing at
 * admission keeps the durable record and the model input identical.
 *
 * Tab, newline, and carriage return survive; every other control byte does
 * not. Order matters: full CSI sequences are removed before bare ESC pairs,
 * so a CSI parameter list is not left behind as visible text.
 *
 * @param raw - untrusted content from the sending agent.
 * @returns the sanitized text.
 */
export declare function sanitizeContent(raw: string): string;
/** Outcome of one admission check on relayed content. */
export type ContentDecision = {
    readonly ok: true;
    readonly content: string;
} | {
    readonly ok: false;
    readonly message: string;
};
/**
 * Sanitize and length-check content before it reaches a peer.
 *
 * Over-length content is refused rather than silently truncated: a task
 * instruction cut in half mid-sentence is worse than a visible error, and a
 * refusal tells the sender to split the work.
 *
 * @param raw - untrusted content from the sending agent.
 * @param maxLength - configured ceiling in characters.
 * @returns the sanitized content, or a refusal naming the overage.
 */
export declare function admitContent(raw: string, maxLength: number): ContentDecision;
/**
 * Tool or mechanism whose call produced a task delivery or notice. Pinned to
 * the FRONT of every injected prompt so the recipient knows what kind of
 * request this is and whether it must act (dispatch/settle/cancel) or may
 * ignore it (reminder/timeout notices).
 */
export type DeliverySource = 'create_task' | 'scheduler' | 'settle_task' | 'cancel_task' | 'report_task' | 'timeout' | 'reminder' | 'reassign_task' | 'retry';
/**
 * Build the message object for one task.
 *
 * Split from delivery so the caller can record the message identity in the
 * ledger BEFORE the send: `followup()` on an idle recipient claims the
 * message in the same tick, and the `agent/inbox/claimed` listener that
 * advances the ledger row can only find the row if its `messageId` is already
 * stored.
 *
 * @param senderSessionId - the dispatching session, recorded for attribution.
 * @param taskId - the ledger row being delivered.
 * @param content - sanitized, length-checked instruction text.
 * @param tool - the calling tool or mechanism (defaults to a manual dispatch).
 * @returns the identified message ready for delivery.
 */
export declare function buildTaskMessage(senderSessionId: SessionId, taskId: string, content: string, tool?: DeliverySource): ReturnType<typeof createUserMessage>;
/**
 * Build the message object for one delayed note delivery (v1.5): the
 * recipient was offline when the note was sent; the sweep delivers it with
 * the ORIGINAL send time stamped on the header, so the recipient knows this
 * is old context, not a fresh message.
 *
 * @param senderSessionId - the sending session, recorded for attribution.
 * @param messageId - the delivery identity.
 * @param content - sanitized, length-checked message text.
 * @param sentAt - the original send time (ISO).
 * @returns the identified message ready for delivery.
 */
export declare function buildDelayedMessage(senderSessionId: SessionId, messageId: string, content: string, sentAt: string): ReturnType<typeof createUserMessage>;
/**
 * Build the message object for one lightweight message.
 *
 * A message is delivered like a task but records nothing in the ledger: no
 * row to find, no lifecycle to advance, no acceptance to await. The sender
 * chooses this channel when the peer owes no reportable result.
 *
 * @param senderSessionId - the sending session, recorded for attribution.
 * @param messageId - the delivery identity, generated by the caller.
 * @param content - sanitized, length-checked message text.
 * @returns the identified message ready for delivery.
 */
export declare function buildMessageMessage(senderSessionId: SessionId, messageId: string, content: string): ReturnType<typeof createUserMessage>;
/**
 * Deliver one prepared task message to a live peer.
 *
 * @param target - the verified live recipient.
 * @param message - the message {@link buildTaskMessage} produced.
 * @param mode - `followup` opens a new turn; `steer` interrupts the current step.
 */
export declare function deliverTask(target: Agent, message: ReturnType<typeof createUserMessage>, mode: DeliveryMode): void;
