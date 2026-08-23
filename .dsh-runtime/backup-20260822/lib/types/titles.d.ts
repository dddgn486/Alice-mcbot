/**
 * Session-title resolution from the storage projection cache.
 *
 * The panel renders session ids as human-readable names. The authoritative
 * source is the disk file `session_projcache.json` that storage writes for
 * every known session — the same source the e2e suites resolve ids from.
 * Page text is never parsed: it is render output, not data.
 *
 * @module dsh-agent-bus/titles
 */
/**
 * Fallback shown when a session's title cannot be resolved.
 *
 * Session ids are `session-<uuid>`; slicing the raw id yields the useless
 * literal "session-", so the prefix is dropped and the short uuid fragment
 * is shown instead (e.g. `6e2cafd9`) — a dormant session stays identifiable
 * in the directory even before it ever got a title.
 */
export declare function fallbackTitle(sessionId: string): string;
/**
 * Resolve every session title from a parsed projection-cache document.
 *
 * The cache shape is `{ tables: { sessions: { [sessionId]: { rows: { title:
 * { val: string } } } } } }`. Rows whose title value is absent or not a
 * non-empty string are skipped — a session without a resolvable title simply
 * stays out of the map and callers fall back to the id prefix.
 *
 * @param document - the parsed JSON, or any other value (tolerated as empty).
 * @returns session id → title.
 */
export declare function resolveTitles(document: unknown): Map<string, string>;
/**
 * Read the projection cache and resolve every title.
 *
 * A missing or malformed file yields an empty map — the panel falls back to
 * id prefixes rather than failing the snapshot over a display concern.
 *
 * @param path - absolute path of the projection cache.
 * @returns session id → title.
 */
export declare function readTitlesFile(path: string): Promise<Map<string, string>>;
