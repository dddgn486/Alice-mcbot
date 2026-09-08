# DSH agent-bus 投递卡死诊断（create_task 任务卡在 submitted）

- 记录：Alice 架构监督员
- 日期：2026-08-25
- 生产实例：3083（alice-bus profile，P0 运行中）
- 性质：**DSH 维护员排查工作包**（bus 成员侧无法自行修复——无手动投递工具）

## 现象

- `create_task` 创建的任务**卡在 submitted（未投递）**，worker 队列显示 "1 unfinished task(s) in that queue"，但 worker 从未收到任务（list_peers 显示 idle、pending=0）
- report_task 被拒："task is submitted; it cannot become completed"
- 连续三个任务同现象：
  1. `06117a13-d33e-4ab5-842f-9ada497ad1fa`（批3 road 审查）→ canceled
  2. `9f63ea03-0b92-43aa-956e-ff90d624ce94`（重投）→ canceled
  3. `37330abc-6d1c-48eb-ad61-c9126b93cb9c`（重投2）→ 仍 submitted
- **前 8 个任务（batches 1-2）均正常投递**——问题是近期出现的（时间窗在批 2 完成后、批 3 开始时）

## 已定位的源码路径（读 /home/fb486/projects/dsh-agent-bus/src/）

### create_task 投递（tools.ts:1155-1166）

```typescript
const blocked: string[] = dependencies === undefined ? [] : [...]
if (blocked.length === 0) {
  const target = await wakeSession(ctx, targetId)
  if (target !== undefined) {
    await ledger.recordDelivery(taskId, message.id)
    deliverTask(target, message, mode)   // ← 无 try/catch、无失败回退
  } else {
    await ledger.transition(taskId, 'queued')  // 仅 wake 失败才回退 queued
  }
}
```

### deliverTask（delivery.ts:281-287)

```typescript
export function deliverTask(target: Agent, message, mode): void {
  if (mode === 'steer') { target.steer(message) }
  else { target.followup(message) }
}
```

### 调度器 sweep（index.ts:358-399）

只重试 **queued** 任务；**submitted 不在重试范围**（input-required 在第 397 行 continue 跳过；submitted 若也跳过则僵尸化）。

## 根因候选（按可能性排序）

1. **deliverTask 静默失败**：`target.followup(message)` 在某些状态下抛异常或 no-op（agent 不可投递、inbox 满、消息装配失败）——create_task 无 try/catch → 异常被吞或任务留在 submitted
2. **wakeSession 返回 live agent 但 followup 被拒**：调查员会话 live=True（API 确认）但 followup 未到达（agent 内部状态异常）
3. **sweep 不覆盖 submitted**：即使有 backstop sweep，submitted 僵尸任务无自动恢复路径

## 排查要求（维护员）

1. 确认 3083 上 `create_task` 投递路径是否有异常日志（agent followup / message 装配 / delivery 错误）
2. 复现：对 live 会话 create_task → 观察是否卡 submitted
3. 修复方向（供评估，不限定）：
   - create_task 投递路径加 **try/catch + 失败回退 queued**（tools.ts:1160-1162）
   - 或 sweep 增加 **submitted 超时回收**（如 submitted 超过 N 秒未 working → 重新投递或回退 queued）
   - 或 deliverTask 失败时返回失败信息而非静默
4. 验证：create_task 正常投递（worker 收到任务可 report）+ 回归原有投递路径
5. 部署 3083 后由监督员实测（用测试任务验证投递闭环）

## 禁止事项

- 不改 bus 工具表面（工具语义不变）
- 不动 P0 门禁逻辑
- 不碰 3081/3082；仅 3083 生产部署（沿用备份/回滚预案）

## 产出

- 修复 commit + 验收报告写到 `.alice-supervision/research/dsh-delivery-stuck-fix-result-20260825.md`（diff、复现证据、修复、部署确认、回滚预案）

**本工作包不构成其他扩展授权。**