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
import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client';
/** Required services: slot registry plus the session list used for current-session highlight. */
export declare const inject: string[];
/**
 * Mount the toolview registrations.
 *
 * @param ctx - the client plugin context.
 */
export declare function apply(ctx: ClientContext): void;
