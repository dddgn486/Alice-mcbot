import { jsx as _jsx } from "react/jsx-runtime";
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
import { useState } from 'react';
import { DisclosureRow, IconBrowseOutline16 } from '@deepseek-ai/dsh-client-ui-primitives';
/** Act labels per wire tool name, kept short for the collapsed line. */
const ACT_LABELS = {
    list_peers: '发现 peer',
    send_note: '发送消息',
    create_flow: '创建流程',
    list_flows: '流程列表',
    create_task: '创建任务',
    dispatch_task: '派发任务',
    edit_task: '改任务',
    list_tasks: '任务列表',
    get_task: '读取任务',
    report_task: '提交结果',
    settle_task: '验收',
    cancel_task: '取消任务',
    request_input: '请求输入',
    update_card: '更新卡片',
};
/** Extract the salient argument for the collapsed summary. */
function salient(args) {
    if (typeof args !== 'object' || args === null)
        return '';
    const record = args;
    for (const key of ['task_id', 'target', 'scope', 'outcome']) {
        const value = record[key];
        if (typeof value === 'string' && value !== '') {
            const short = value.length > 13 ? `${value.slice(0, 13)}…` : value;
            return `${key}=${short}`;
        }
    }
    return '';
}
/** Render one agent-bus tool call as a collapsed producer row. */
export function AgentBusToolRow({ block, toolName }) {
    const [open, setOpen] = useState(false);
    const done = 'kind' in block;
    const argsRaw = (done ? block.call?.argsRaw : block.argsRaw) ?? '';
    let args;
    try {
        args = argsRaw === '' ? undefined : JSON.parse(argsRaw);
    }
    catch {
        args = argsRaw;
    }
    const act = ACT_LABELS[toolName] ?? toolName;
    const key = salient(args);
    const summary = key === '' ? act : `${act} · ${key}`;
    return (_jsx(DisclosureRow, { icon: _jsx(IconBrowseOutline16, { size: 14 }), title: "agent-bus-task", collapsedContent: summary, keepContentWhenOpen: true, open: open, expandable: true, onToggle: () => setOpen(previous => !previous), children: _jsx("pre", { style: { margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }, children: argsRaw === '' ? '(no arguments)' : JSON.stringify(args, null, 2) }) }));
}
