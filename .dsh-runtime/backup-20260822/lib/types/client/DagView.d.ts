/**
 * DAG board for the v1.4 flow window: pick a flow, then a canvas of that
 * flow's tasks. Archived ancestors fade; live nodes stay interactive.
 *
 * @module dsh-agent-bus/client/DagView
 */
import { type MutableRefObject, type PointerEvent as ReactPointerEvent } from 'react';
import { type FlowView, type TaskView } from './panel-model.ts';
export interface DagViewProps {
    readonly tasks: readonly TaskView[];
    readonly flows: readonly FlowView[];
    readonly selectedFlowId: string | null;
    readonly onSelectFlow: (id: string) => void;
    readonly sidebarWidth: number;
    readonly onSidebarResizeDown: (event: ReactPointerEvent<HTMLDivElement>) => void;
    readonly onSidebarResizeMove: (event: ReactPointerEvent<HTMLDivElement>) => void;
    readonly onSidebarResizeUp: () => void;
    readonly nowMs: number;
    /** Return true when Escape was consumed (pin / detail). */
    readonly consumeEscRef: MutableRefObject<(() => boolean) | null>;
}
/**
 * Session rail + layered DAG canvas + detail strip.
 * Empty-canvas drag pans; wheel zooms toward the cursor; nodes drag freely.
 */
export declare function DagView({ tasks, flows, selectedFlowId, onSelectFlow, sidebarWidth, onSidebarResizeDown, onSidebarResizeMove, onSidebarResizeUp, nowMs, consumeEscRef, }: DagViewProps): JSX.Element;
