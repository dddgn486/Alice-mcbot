/**
 * Vocabulary types for the agent-bus task ledger: branded ids, the A2A-aligned
 * task lifecycle, the peer card, and the durable record shape.
 *
 * The ledger records intent and outcome. It is deliberately NOT a mirror of
 * the agent inbox: the inbox is the execution authority and the two drift by
 * design (an interrupt keeps unclaimed queue items but does not requeue a
 * claimed one, and disposal discards every unclaimed item).
 *
 * @module dsh-agent-bus/types
 */
/** Brand a raw string as a {@link TaskId}; branding has no runtime effect. */
export function TaskId(value) {
    return value;
}
