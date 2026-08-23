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
import type { ToolCallViewProps } from '@deepseek-ai/dsh-client-ui-tool/client';
/** Render one agent-bus tool call as a collapsed producer row. */
export declare function AgentBusToolRow({ block, toolName }: ToolCallViewProps): JSX.Element;
