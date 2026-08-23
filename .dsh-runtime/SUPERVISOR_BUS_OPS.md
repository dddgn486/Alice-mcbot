# 监督员操作指引 — agent-bus 稳定性修复落地清单

- 日期：2026-08-22
- 适用范围：3083（Alice 生产）监督员会话
- 关联变更：`.dsh/profiles/alice-bus/cordis.patch.yml` + `dsh-agent-bus@0.1.1` lib 补丁
- 详细交接：`/home/fb486/projects/dsh-agent-bus-lab/patches-20260822/HANDOVER.md`

---

## 变更摘要（已生效）

1. **超时恢复 2 小时**：`taskTimeoutMs: 7200000`（原 10 分钟），`offlineGraceMs: 900000`（原 2 分钟）。
   正常工作的执行方不会再被误判超时。
2. **failed 任务可补交摘要**：执行方对 failed 任务调用 `report_task`，工作摘要会 attach 到任务行
   （状态保持 failed），监督员会收到通知。
3. **忽略清单**：监督员可用 `ignore_task` 标记已确认 obsolete 的任务，wait-watch 不再反复唤醒；
   `unignore_task` 可恢复。

---

## 监督员需要做的三件事

### 1. 清理历史 failed 任务（止住重复唤醒）

对**已经确认不需要再处理**的旧 failed 任务，逐个调用：

```
ignore_task(task_id="<任务ID>", reason="<为什么无需再处理>")
```

要点：
- 只有该任务的 reviewer（监督员自己，或指派给其他会话的 reviewer）能调用
- 标记后任务行与历史保留，只是不再触发 wait-watch 唤醒
- 想恢复就调用 `unignore_task(task_id="<任务ID>")`

当前 ledger 有 15 个 failed 任务（含历史）。先审阅再忽略，不要批量无差别忽略。

### 2. 处理「成果已完成但超时失败」的任务

若执行方已在 failed 任务上补交摘要（你会收到通知），按此流程：

1. `get_task(task_id="<任务ID>")` 读取完整摘要
2. 决策三选一：
   - 成果可接受 → 以此为据**重新派发收尾任务**（引用原摘要），或直接验收该部分成果
   - 需要重做 → 派发新任务（原任务 ID 是终态，不能复用为 completed）
   - 不再需要 → `ignore_task` 标记

### 3. 派发新任务时的预期

- 任务超时窗口现在是 **2 小时**，Forge 窄修复（15–30 分钟）不会再误超时
- 执行方离线超过 15 分钟才会收到监督员侧提醒（`offlineGraceMs`）
- 如果执行方确实中途失联超过 2 小时，任务才会 failed(timeout)——这是兜底，不是常规路径

---

## 验证命令（监督员可用）

```text
list_tasks            # 查看活跃任务
get_task              # 查看单任务完整记录（含补交摘要、ignored 标记）
ignore_task           # 标记 obsolete，止住 wait-watch 唤醒
unignore_task         # 恢复可唤醒
supervisor_wait_watch action=status   # 查看等待守护状态
```

---

## 注意事项

- **不要**对仍在进行中的任务调用 `ignore_task`（会隐藏需要验收的结果）
- **不要**用 `send_note` 代替 `report_task` 补交——补交摘要会正式落在任务行
- 回滚方案见 HANDOVER.md §5（不影响 ledger 数据）
