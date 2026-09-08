# 工作包：request_input 通知 dispatcher（最小监督员唤醒机制）

- 派发：Alice 架构监督员
- 执行：DSH 维护员（Kiro/鲸鱼娘，隔离进程）
- 日期：2026-08-25
- 用户批准：✅（监督员方案 + 用户明确批准）
- 开发仓库：`/home/fb486/projects/dsh-agent-bus/`
- 部署目标：3083 alice-bus profile（生产，P0 已运行）

## 背景与根因

- 问题：worker 调用 `request_input` 后，dispatcher（监督员）**未被唤醒**；此前靠用户手动提醒才发现任务卡在 input-required。
- 根因（源码确认）：`src/tools.ts` 的 `request_input` 工具（1732-1773 行）在 `ledger.transition(taskId, 'input-required', ...)` 成功后**直接返回，无任何 notifySession 通知 dispatcher**。现有 index.ts 的 timeout sweep 对 input-required `continue` 跳过（index.ts:397），只靠 2h 超时兜底。
- 对比：`report_task`→`settle_task` 通知路径是通的（1552/1619 行有 notifySession），唯独 request_input 缺通知。

## 改动（最小，仅 1 处约 4 行）

文件：`src/tools.ts`，`request_input` 的 `execute` 中（约 1769-1772 行），在 transition 成功后追加 notifySession：

```typescript
const paused = await ledger.transition(taskId, 'input-required', { question: admitted.content })
if (!paused.ok) throw new Error(paused.message)
notifySession(ctx, task.assignedBy, taskId,
  `任务 ${taskId} 需要你的输入（input-required）：\n${admitted.content}\n\n请用 create_task 传入 task_id 回答该问题，任务将恢复。`,
  'request_input')
return { taskId, status: paused.task.status }
```

要点：
- `notifySession` 签名：`(ctx, sessionId, taskId, text, tool)`（tools.ts:444-451），已在本文件多处使用，**直接复用，零新增机制**
- `tool` 参数传 `'request_input'`（DeliverySource 字符串，与现有 'reminder'/'retry'/'settle' 等并列）
- `task.assignedBy` 即 dispatcher session id（ledger 记录）
- `noticeMerger`（tools.ts:439）自动做去重/合并，无需额外处理
- 不改工具签名、不改 ledger/scheduler、不影响 P0 门禁

## 验证要求（3082 lab 模式或等价隔离验证，通过后再部署 3083）

1. 编译测试：`pnpm test`（或仓库既有测试入口）通过；无 tsc 新错误
2. 通知路径验证（隔离 profile，如 alice-bus-lab 或临时 lab profile）：
   - 派发一个任务给测试 worker → worker 调用 request_input → **断言 dispatcher 会话收到标记为 `tool="request_input"` 的 agent-bus 消息**（含任务 id 与问题文本）
   - dispatcher 用 create_task 传 task_id 回答 → 任务恢复 working → 正常闭环
3. 回归：request_input 原有行为不变（任务进入 input-required、回答后恢复）；ledger 状态流转正确
4. 部署 3083 后验证：真实 worker request_input → 监督员收到通知（可由用户确认收到消息即可）
5. 可回滚：单点改动，还原即回滚（沿用 P0 的 backup 模式）

## 禁止事项

- 不改工具签名、不改 ledger.ts/scheduler.ts/wake.ts、不动 P0 门禁逻辑
- 不恢复 supervisor_wait_watch（本方案是"补缺口"而非"建轮询"；废弃决策保持）
- 不触碰 3081/3082 生产无关实例、不触碰 Alice 业务代码
- 不把"编译通过"当作"通知验证通过"（必须实测通知送达）

## 产出

- 开发仓库提交（含改动说明 commit message）
- 验收报告写到 `.alice-supervision/research/dsh-minimal-wake-request-input-result-20260825.md`：改动 diff、测试/验证证据（含 dispatcher 收到通知的消息记录）、3083 部署确认、回滚预案

**本工作包不构成其他扩展授权。**