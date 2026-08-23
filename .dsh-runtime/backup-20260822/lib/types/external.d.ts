/**
 * External report storage with hot/cold partitioning.
 *
 * Oversized reports live on disk, keyed by task id, in one of two zones:
 *
 * - **hot** (`cache/`): reports of active tasks (`submitted` / `working` /
 *   `input-required`) and recent terminals; pruned after
 *   {@link HOT_IDLE_MS} of no access.
 * - **cold** (`archive/`): reports of terminal tasks, moved here the moment
 *   a task reaches `completed` (settled), `failed`, or `canceled`; pruned
 *   after {@link COLD_IDLE_MS} — the archive is the long-lived record.
 *
 * The ledger row carries a bounded inline summary plus a `reportRef`; the
 * reference is the task id, never a path. get_task reads back through
 * {@link ReportStore.read}, which checks the hot zone first and falls back
 * to the cold zone, so the model never sees the zone split.
 *
 * @module dsh-agent-bus/external
 */
import type { TaskId } from './types.ts';
/** Hot-zone files untouched for this long are swept (7 days). */
export declare const HOT_IDLE_MS: number;
/** Cold-zone files untouched for this long are swept (30 days). */
export declare const COLD_IDLE_MS: number;
/**
 * Two-zone report storage.
 */
export declare class ReportStore {
    private readonly hotRoot;
    private readonly coldRoot;
    /**
     * @param hotRoot - directory for active-task reports (`cache/`).
     * @param coldRoot - directory for terminal-task reports (`archive/`).
     */
    constructor(hotRoot: string, coldRoot: string);
    /**
     * Persist one report body into the hot zone.
     *
     * @param taskId - the owning task; also the reference.
     * @param content - the full report text.
     * @returns the reference stored on the ledger row.
     */
    save(taskId: TaskId, content: string): Promise<string>;
    /**
     * Move one report from the hot zone to the cold zone.
     *
     * Called when a task reaches a terminal state; the reference does not
     * change, so ledger rows stay untouched.
     *
     * @param taskId - the reference to archive.
     */
    archive(taskId: string): Promise<void>;
    /**
     * Read a report back by its reference: hot zone first, cold zone second.
     *
     * @param ref - the reference stored on the ledger row.
     * @returns the full text, or `undefined` when the file is missing.
     */
    read(ref: string): Promise<string | undefined>;
    /**
     * Whether a report file exists in the hot zone. A missing zone root reads
     * as an empty zone, never an error.
     *
     * @param ref - the report reference (the task id).
     * @returns `true` when the hot-zone file exists.
     */
    existsHot(ref: string): Promise<boolean>;
    /**
     * Whether a report file exists in the cold zone, same tolerance as
     * {@link existsHot}.
     *
     * @param ref - the report reference (the task id).
     * @returns `true` when the cold-zone file exists.
     */
    existsCold(ref: string): Promise<boolean>;
    /**
     * Sweep both zones: remove files untouched for the zone's idle window.
     *
     * @returns the number of removed files.
     */
    sweep(): Promise<number>;
    private sweepZone;
}
