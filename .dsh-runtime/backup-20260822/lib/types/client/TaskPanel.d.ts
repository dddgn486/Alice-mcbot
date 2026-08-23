/**
 * Workbench: capsule → 3×3 launcher → sticky-note feature windows.
 * The existing task list is the first feature window.
 *
 * Styles live in TaskPanel.module.css. This pipeline compiles client sources
 * through tsc then tsdown (no lightningcss CSS-modules plugin), so the sheet
 * is injected as a tagged <style> and class names stay the authored `abP*`
 * locals. Keep the two copies in lockstep.
 *
 * @module dsh-agent-bus/client/TaskPanel
 */
import type { ObservableSnapshot, SessionListState } from '@deepseek-ai/dsh-client-runtime/client';
/** Optional current-session feed; highlighting is best-effort. */
export interface TaskPanelProps {
    readonly sessionsList?: ObservableSnapshot<SessionListState>;
}
/**
 * Capsule opens the launcher; 任务 / 流程 each open a sticky-note window.
 */
export declare function TaskPanel({ sessionsList }: TaskPanelProps): JSX.Element;
