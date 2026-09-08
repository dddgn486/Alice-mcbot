# Movement 实验 6C 证据报告

日期：2026-09-06
入口：`alice_test:movement_f` + `alice:movement_precondition_tester`

## 操作

- 生成场景 F，将 Bot 摆到 `(0,64,60)`。
- 普通右键启动前置条件测试。
- 多次重复启动，并用 Shift+右键清理。

## 服务端事实

- 场景日志确认 `headBlock=0,65,60`，目标脚位为 `1,64,60`。
- 任务每次约 1 tick 终止（例如 `startTick=1575 endTick=1576`）。
- 结构化结果：`failureCode=INVALID_PRECONDITION`。
- 失败阶段：`failurePhase=PRECONDITION`。
- 实际起点：`start=0,64,60`。
- 未出现 `Movement 执行`、`movement_timeout` 或 `movement_blocked_dynamic`。
- Controller 终态为清理后空闲。

## 客户端观察

用户报告截图中 Bot 视觉上位于障碍位置。该现象与场景设计一致：障碍方块被放置在 Bot 起点头部 `(0,65,60)`，不是执行过程中 Bot 移动到障碍处。由于头部方块会触发生存窒息诊断，外层终态同时出现 `SURVIVAL_INTERRUPTED / failed:survival_suffocating`；这不改变任务结构化结果 `INVALID_PRECONDITION`。

## 结论

6C 达到 `USER_ACCEPTED`：启动前头部空间不满足时立即安全失败，没有进入 Movement 执行或等待超时。视觉上“卡在障碍位置”属于夹具可见性瑕疵，不是执行器卡住证据。

证据文件：`latest.log`、`key-lines.log`。
工件 SHA-256：`759ffcaba21f99a987ad87494d512edce11742abab5b849891071427cf7f1cd2`。
