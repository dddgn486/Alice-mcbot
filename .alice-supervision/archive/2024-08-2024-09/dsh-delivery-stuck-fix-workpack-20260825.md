# 工作包：create_task 投递失败回退（修复 submitted 卡死缺陷）

- 派发：Alice 架构监督员
- 执行：DSH 维护员（Kiro/鲸鱼娘，隔离进程）
- 日期：2026-08-25
- 用户批准：✅（2026-08-25，用户明确指示"修投递缺陷"）
- 开发仓库：`/home/fb486/projects/dsh-agent-bus/`
- 部署目标：3083 alice-bus profile（生产）
- 诊断文档：`/home/fb486/projects/alice/.alice-supervision/research/dsh-delivery-stuck-diagnosis-20260825.md`

## 背景与根因

**现象（2026-08-25 夜间实测）**：
- `create_task` 连续 3 个任务卡在 **submitted（未投递）**：worker（Alice 深度调查员）live=True 但从未收到任务；`report_task` 被拒（"task is submitted; it cannot become completed"）
- 触发场景：**无人值守/会话休眠瞬态**（用户关屏睡觉整夜后出现；白天探针任务 15 秒正常闭环）——但源码缺陷是诱因放大器，任何"deliverTask 静默失败"场景都会触发
- 受影响任务：`06117a13` / `9f63ea03` / `37330abc`（均已 cancel，报告走 send_note 兜底，业务零损失）

**根因（源码定位）**：`src/tools.ts:1155-1166` 的 `create_task` 投递路径**无失败回退**：

```typescript
if (blocked.length === 0) {
  const target = await wakeSession(ctx, targetId)
  if (target !== undefined) {
    await ledger.recordDelivery(taskId, message.id)
    deliverTask(target, message, mode)   // ← 无 try/catch、无失败检查：静默失败则任务永久卡 submitted
  } else {
    await ledger.transition(taskId, 'queued')  // 仅 wake 失败才回退 queued
  }
}
```

- `deliverTask`（`src/delivery.ts:281-287`）极简：`target.steer/followup(message)`——投递失败时不抛错、无返回
- 现有兜底（`src/index.ts:358-399` stranded-recovery heartbeat）仅覆盖 **submitted + executor idle 超 5 分钟（retryIdleMs=300000）** 的自动重投；窗口过长 + 重投路径同样无失败检查 → 慢且不可靠

## 改动（最小，1-2 处）

### 改动 1（核心）：create_task 投递路径失败回退（tools.ts:1158-1166 附近）

投递后校验结果；失败时不静默，回退 `queued` 交给 sweep 重试：

```typescript
if (blocked.length === 0) {
  const target = await wakeSession(ctx, targetId)
  if (target !== undefined) {
    try {
      await ledger.recordDelivery(taskId, message.id)
      deliverTask(target, message, mode)
    } catch (err) {
      // 投递失败：回退 queued 交由 sweep 重试，不留 submitted 僵尸
      await ledger.transition(taskId, 'queued')
      // 可选：记录一条监督员可见的失败日志（ctx.logger 或 BotLog 风格）
    }
  } else {
    await ledger.transition(taskId, 'queued')
  }
}
```

（若 `deliverTask` 有可检查的返回/失败信号而非抛错，则用返回值判断；以仓库实际实现为准。）

### 改动 2（建议）：sweep 的 stranded-recovery 缩短窗口 + 对齐重试路径（index.ts:374 附近）

把 `retryIdleMs` 从 300000 缩短（如 60_000），使夜间无人值守场景下 submitted 僵尸在 1 分钟内被自动重投；并确保重投路径（index.ts:368-387）与改动 1 共享同一失败回退语义（重投失败 → 保持/回退 queued，不无限尝试）。

## 验证要求（隔离验证通过后部署 3083）

1. 编译：`pnpm test`（或仓库既有测试入口）通过；无 tsc 新错误
2. 投递正常回归（隔离 profile，如 alice-bus-lab 或临时 lab profile）：
   - create_task → worker 收到 → report → 闭环（探针式最小任务）
   - 连续 5 个任务投递全正常（验证改动 1 不破坏正常路径）
3. 失败回退逻辑验证（可单元测试模拟 deliverTask 抛错，或注入故障）：
   - deliverTask 失败 → 任务状态从 submitted 回退到 queued → sweep 重试 → 最终投递成功或明确停止（不卡 submitted 僵尸）
4. 部署 3083 后：监督员用探针任务实测（create_task → worker 收到 → report → 闭环）
5. 可回滚：单点改动，还原即回滚（沿用 P0 的 backup 模式）

## 禁止事项

- 不改工具表面语义（create_task 的对外行为不变）；不改 ledger/scheduler 核心状态机
- 不动 P0 门禁逻辑（required_skills/evidence_state 等）
- 不恢复 supervisor_wait_watch（废弃决策保持）；不引入轮询机制
- 不触碰 3081/3082 生产无关实例；不触碰 Alice 业务代码
- 不把"编译通过"当作"投递验证通过"（必须实测投递闭环）

## 产出

- 开发仓库提交（commit message 说明修复）
- 验收报告写到 `.alice-supervision/research/dsh-delivery-stuck-fix-result-20260825.md`：改动 diff、测试/验证证据（正常投递回归 5 连测 + 失败回退模拟）、3083 部署确认、回滚预案

**本工作包不构成其他扩展授权。**