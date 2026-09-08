---
name: alice-task-lifecycle-and-recovery
description: 保持 Alice 任务状态、取消、失败码、超时和 bot 可回收性的统一契约。
---
# Alice Task Lifecycle and Recovery

## 何时使用
新增或修改 Task、BotSession、任务取消、超时、失败码、掉落物回收或任务重启时使用。

## 核心原则
任务成功不是“调用过动作”，而是服务端后置条件成立；失败不是隐藏或无限重试，而是给出稳定原因并保留 bot/物品的可回收状态。

## 最小状态

```text
CREATED -> RUNNING -> DONE
                    -> FAILED(reason)
                    -> CANCELLED(reason)
```

每个任务至少明确：目标、当前阶段、开始时间、超时、取消入口、终态、失败码和 bot 最终位置。

## 编码检查

- tick 中只推进一个明确阶段，不在多个 Task 同时写 bot 输入；
- 终态只触发一次清理；
- 取消和异常都停止输入、释放作用域并记录原因；
- 不用传送、跳过后置条件或无限重试制造成功；
- 复杂恢复先记录并等待下一次决策，不隐式换成另一种任务。

## 轻量验证

优先用一个 focused fixture 或游戏内测试物品验证：正常完成、主动取消、超时/失败各一次。日志记录 `taskId/phase/result/reason` 即可，不要求完整形式化测试。
