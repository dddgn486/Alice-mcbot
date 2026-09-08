# 多 Bot 并行接口预留（非当前验收目标）

当前不实施多 Bot 并行调度验收，也不接入 MineTask。仅记录后续接入时必须保留的身份边界：

- 每个 Bot 由 UUID 唯一标识；
- 每个 Bot 拥有独立 `BotSession`、当前 `Task`、`TaskTarget`、`ScopeBuffer` 和终态记录；
- 未来 `TaskOutcome`/`TaskExecutionRecord` 应能直接关联 `botUuid` 与任务实例标识；
- 未来客户端目标状态应按 Bot 标识隔离，不继续依赖单一全局目标；
- `MovementPathExecutor`、无进展计数和 Movement 序列必须属于单个任务实例；
- 本轮不改变现有 `BotManager` 调度、全局目标广播和持久化行为。

当前状态：接口位置已记录，行为改造和多 Bot 验收延期至 Alice 整体验收后重新立项。
