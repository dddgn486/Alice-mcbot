/**
 * The agent-bus task ledger domain: record schemas and the `defineDomain` spec
 * the gateway opens. The zod schema is the durable-boundary validator.
 *
 * Durable state lives here rather than in the session log on purpose. An
 * out-of-repo plugin cannot enter `KNOWN_SESSION_EVENT_TYPES` (the generator
 * globs the harness repo only), so a custom session event would make every
 * later read of that log refuse unless marked `ignorable` — and `ignorable`
 * means the record may not affect reconstruction. Task state must survive
 * replay, so a storage domain is the only correct home.
 *
 * @module dsh-agent-bus/spec
 */
import { z } from 'zod';
import { SessionId } from '@deepseek-ai/dsh-session';
import type { TaskId } from './types.ts';
/**
 * Four-bucket token usage at the durable boundary, mirroring the token-meter
 * projection shape. Non-negative integers; strict so an unexpected bucket
 * fails load rather than parsing silently.
 */
export declare const tokenBuckets: z.ZodObject<{
    uncachedInputTokens: z.ZodNumber;
    outputTokens: z.ZodNumber;
    cacheReadTokens: z.ZodNumber;
    cacheWriteTokens: z.ZodNumber;
}, z.core.$strict>;
/** One advertised capability: machine key plus a short readable label. */
export declare const capability: z.ZodObject<{
    id: z.ZodString;
    label: z.ZodString;
}, z.core.$strip>;
/**
 * Durable shape of one task row. Optional fields are absent until the
 * lifecycle reaches the state that produces them.
 */
export declare const taskRecord: z.ZodObject<{
    id: z.ZodPipe<z.ZodString, z.ZodTransform<TaskId, string>>;
    assignedBy: z.ZodPipe<z.ZodString, z.ZodTransform<SessionId, string>>;
    assignedTo: z.ZodOptional<z.ZodPipe<z.ZodString, z.ZodTransform<SessionId, string>>>;
    assignedReviewer: z.ZodOptional<z.ZodPipe<z.ZodString, z.ZodTransform<SessionId, string>>>;
    workspacePath: z.ZodString;
    content: z.ZodString;
    title: z.ZodOptional<z.ZodString>;
    status: z.ZodEnum<{
        queued: "queued";
        submitted: "submitted";
        working: "working";
        "input-required": "input-required";
        "auth-required": "auth-required";
        completed: "completed";
        failed: "failed";
        canceled: "canceled";
        rejected: "rejected";
    }>;
    mode: z.ZodEnum<{
        followup: "followup";
        steer: "steer";
    }>;
    messageId: z.ZodOptional<z.ZodString>;
    turn: z.ZodOptional<z.ZodNumber>;
    report: z.ZodOptional<z.ZodString>;
    reportRef: z.ZodOptional<z.ZodString>;
    question: z.ZodOptional<z.ZodString>;
    outcome: z.ZodOptional<z.ZodEnum<{
        success: "success";
        failure: "failure";
    }>>;
    feedback: z.ZodOptional<z.ZodString>;
    reason: z.ZodOptional<z.ZodString>;
    retries: z.ZodNumber;
    tokensAtStart: z.ZodOptional<z.ZodRecord<z.ZodPipe<z.ZodString, z.ZodTransform<SessionId, string>>, z.ZodObject<{
        uncachedInputTokens: z.ZodNumber;
        outputTokens: z.ZodNumber;
        cacheReadTokens: z.ZodNumber;
        cacheWriteTokens: z.ZodNumber;
    }, z.core.$strict>>>;
    dependencies: z.ZodOptional<z.ZodArray<z.ZodPipe<z.ZodString, z.ZodTransform<TaskId, string>>>>;
    auto: z.ZodOptional<z.ZodBoolean>;
    acceptanceCriteria: z.ZodOptional<z.ZodString>;
    flowId: z.ZodOptional<z.ZodString>;
    handoffs: z.ZodOptional<z.ZodArray<z.ZodObject<{
        fromTask: z.ZodPipe<z.ZodString, z.ZodTransform<TaskId, string>>;
        document: z.ZodString;
        at: z.ZodString;
    }, z.core.$strip>>>;
    createdAt: z.ZodString;
    updatedAt: z.ZodString;
}, z.core.$strip>;
/** One stored task row, inferred from {@link taskRecord}. */
export type StoredTaskRecord = z.infer<typeof taskRecord>;
/**
 * Durable shape of one peer's self-maintained card. The description serves
 * model readers; capabilities serve programs, so their ids are
 * format-validated at the durable boundary.
 */
export declare const peerCard: z.ZodObject<{
    description: z.ZodString;
    capabilities: z.ZodArray<z.ZodObject<{
        id: z.ZodString;
        label: z.ZodString;
    }, z.core.$strip>>;
    updatedAt: z.ZodString;
}, z.core.$strip>;
/** One stored peer card, inferred from {@link peerCard}. */
export type StoredPeerCard = z.infer<typeof peerCard>;
/**
 * Durable shape of one flow: a named DAG container for tasks (v1.4). A flow
 * has no status of its own — active/archived is derived from its tasks.
 */
export declare const flowRecord: z.ZodObject<{
    id: z.ZodString;
    name: z.ZodString;
    description: z.ZodOptional<z.ZodString>;
    createdBy: z.ZodPipe<z.ZodString, z.ZodTransform<SessionId, string>>;
    workspacePath: z.ZodString;
    createdAt: z.ZodString;
}, z.core.$strip>;
/** One stored flow, inferred from {@link flowRecord}. */
export type StoredFlowRecord = z.infer<typeof flowRecord>;
/**
 * Durable shape of one pending note: a send_note that could not be
 * delivered because the recipient was offline (v1.5). The delivery sweep
 * retries it once the recipient is live; no acceptance, no receipt.
 */
export declare const pendingMessageRecord: z.ZodObject<{
    id: z.ZodString;
    sender: z.ZodPipe<z.ZodString, z.ZodTransform<SessionId, string>>;
    recipient: z.ZodPipe<z.ZodString, z.ZodTransform<SessionId, string>>;
    content: z.ZodString;
    sentAt: z.ZodString;
    createdAt: z.ZodString;
    attempts: z.ZodDefault<z.ZodNumber>;
}, z.core.$strip>;
/** One stored pending note, inferred from {@link pendingMessageRecord}. */
export type StoredPendingMessage = z.infer<typeof pendingMessageRecord>;
/**
 * Durable ledger state. `taskIds` is the authoritative creation order, which
 * the listing tools page over without scanning the table. Defaulted so a
 * record written before the field parses unchanged.
 */
export declare const agentBusDomainState: z.ZodObject<{
    taskIds: z.ZodDefault<z.ZodArray<z.ZodPipe<z.ZodString, z.ZodTransform<TaskId, string>>>>;
}, z.core.$strip>;
/** Durable ledger state inferred from {@link agentBusDomainState}. */
export type AgentBusDomainState = z.infer<typeof agentBusDomainState>;
/**
 * The agent-bus domain spec: a `tasks` table keyed by task id, a `peers`
 * table keyed by session id, a `flows` table keyed by flow id, a
 * `pending_messages` table keyed by note id, plus the order singleton.
 * Version 8 adds the flows container (`flows` table, `tasks.flowId`),
 * version 9 adds `tasks.handoffs`, version 10 adds `pending_messages`
 * (durable offline send_note delivery). The version bump invalidates the
 * storage unit — keep a backup of `agent_bus.json` first (v1.3 §6), then
 * bump the version stamp once after upgrading; the ledger migrates
 * pre-release `submitted` rows without a messageId to `queued` at open.
 */
export declare const agentBusDomainSpec: {
    name: string;
    version: number;
    global: {
        schema: z.ZodObject<{
            taskIds: z.ZodDefault<z.ZodArray<z.ZodPipe<z.ZodString, z.ZodTransform<TaskId, string>>>>;
        }, z.core.$strip>;
        initial: {
            taskIds: never[];
        };
    };
    tables: {
        tasks: import("@deepseek-ai/dsh-storage-domain").DomainTableSpec<TaskId, {
            id: TaskId;
            assignedBy: SessionId;
            workspacePath: string;
            content: string;
            status: "queued" | "submitted" | "working" | "input-required" | "auth-required" | "completed" | "failed" | "canceled" | "rejected";
            mode: "followup" | "steer";
            retries: number;
            createdAt: string;
            updatedAt: string;
            assignedTo?: SessionId | undefined;
            assignedReviewer?: SessionId | undefined;
            title?: string | undefined;
            messageId?: string | undefined;
            turn?: number | undefined;
            report?: string | undefined;
            reportRef?: string | undefined;
            question?: string | undefined;
            outcome?: "success" | "failure" | undefined;
            feedback?: string | undefined;
            reason?: string | undefined;
            tokensAtStart?: Record<SessionId, {
                uncachedInputTokens: number;
                outputTokens: number;
                cacheReadTokens: number;
                cacheWriteTokens: number;
            }> | undefined;
            dependencies?: TaskId[] | undefined;
            auto?: boolean | undefined;
            acceptanceCriteria?: string | undefined;
            flowId?: string | undefined;
            handoffs?: {
                fromTask: TaskId;
                document: string;
                at: string;
            }[] | undefined;
        }>;
        peers: import("@deepseek-ai/dsh-storage-domain").DomainTableSpec<SessionId, {
            description: string;
            capabilities: {
                id: string;
                label: string;
            }[];
            updatedAt: string;
        }>;
        flows: import("@deepseek-ai/dsh-storage-domain").DomainTableSpec<string, {
            id: string;
            name: string;
            createdBy: SessionId;
            workspacePath: string;
            createdAt: string;
            description?: string | undefined;
        }>;
        pending_messages: import("@deepseek-ai/dsh-storage-domain").DomainTableSpec<string, {
            id: string;
            sender: SessionId;
            recipient: SessionId;
            content: string;
            sentAt: string;
            createdAt: string;
            attempts: number;
        }>;
    };
};
