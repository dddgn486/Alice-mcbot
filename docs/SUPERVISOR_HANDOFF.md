# Alice 下一任监督员接管说明

> 更新时间：2026-08-21
> 用途：四角色 `dsh-agent-bus` 工作流启用后的首份监督员恢复清单。

## 1. 当前唯一可信状态

- 当前 Git HEAD、Windows `origin/master`、GitHub `master`：`276339ff12bb6c122780be0cde2d45ea6b4821db`（四角色 bus 交接文档提交）；本次工作流接管基线为 `f9d89da7e6540099312fa8438a8299be1c9d3b21`（客户端阻塞文档 checkpoint）。
- 最新业务实现：`8bb2d7b1641c0b6e6063d4c78ae0357896fd0c90`，`interface-readonly-snapshot-v1`。
- Active plan：`.alice-supervision/active-plan.md`，状态 `NEEDS_USER_DECISION`。
- 实现包结论：`CONDITIONAL_PASS / HEADLESS_PARTIALLY_VERIFIED`，不是完成、不是客户端通过。
- 客户端结果：`NEEDS_FIX`，但扫描器测试场景尚未开始。
- 用户已要求暂停并待命；未经新的明确恢复指令，不得派发实现或重复加载旧存档。

## 2. 当前阻塞事实

Windows 客户端加载旧存档时卡死。用户提供的最后可见 Alice 日志是：

```text
[03:29:07] 假人已生成(玩家化): name=tango pos=17, -59, 18
[03:29:07] 已从世界存档恢复假人: name=tango pos=(17, -59, 18)
[03:29:07] SELFTEST 待命(手动 /alice selftest 触发)
```

这些日志只说明最后可见阶段位于假人恢复和 selftest 待命之后。当前没有异常栈、完整 Windows `latest.log`/`debug.log`、线程转储、新世界对照或明确卡死界面阶段。不得先验归因到：

- C1 snapshot；
- `alice:interface_scanner` 或 registry remap；
- BotSavedData/假人恢复；
- fake connection/player list；
- 客户端同步；
- 其他模组初始化。

## 3. 恢复工作时的第一动作

先向用户确认是否恢复诊断。收到确认后，只创建诊断计划，不直接修代码。最小证据包：

1. 同一 `f9d89da` 构建的新建空白世界是否可进入；
2. 旧存档卡死时完整客户端 `latest.log` 与 `debug.log`；
3. 卡死时客户端与 integrated server 线程转储；
4. 用户看到的是“加载地形”、已渲染后冻结，还是窗口无响应；
5. registry remap、SavedData 恢复、`PlayerList.placeNewPlayer` 和首个 server tick 的顺序。

在这些事实能缩小责任模块前，不授权业务修复。诊断任务适合先派给调查员；若需要安排多阶段复现和停止条件，再派规划员。开发员保持空闲。

## 4. `8bb2d7b` 仍欠的维护条件

即使旧存档卡死不是本包造成，下一产品包前仍要单独收口：

1. selftest 补 formatter 不改世界与捕获后不可变断言；
2. `hasChunkAt` 必须早于 `getBlockState/getBlockEntity`；
3. HANDOVER 的实现/工具事实已在 `f9d89da` 修正，后续提交继续保持实际 HEAD；
4. 删除旧 `scanItems/scanEnergy/scanFluid/slotHint` 死代码，更新 C1 注释；
5. 用户完成 `alice:interface_scanner` 和原版钻石铲隔离矩阵。

不要把这些维护与旧存档卡死修复混成一个工作包。

## 5. 四角色 bus 规则

### 启用前确认

当前已检查到 `dsh-agent-bus 0.1.1` 安装在隔离 profile `~/.dsh/profiles/alice-bus-lab`，不是对现有 3081 Web 生效的证明。新团队启动后先确认四个 Alice 会话位于同一个启用 bus 的 profile 和 `/home/fb486/projects/alice` workspace，并实际拥有 `list_peers/create_task/report_task/settle_task` 等工具。若任一条件不满足，退回 `.alice-supervision` + Git + 用户消息流程；不要启动第二个服务器或假定 HMR/现有 GUI 已加载插件。

### 监督员

- 启动后先 `update_card`，能力建议：`alice-supervision`、`architecture-review`、`task-routing`、`client-gate`。
- `list_peers` 识别规划员/开发员/调查员；`list_tasks` 核对遗留任务。
- 只有监督员可 `create_task` 派发 Alice 正式工作并作为默认 reviewer `settle_task`。
- 派单必须带：项目绝对路径、Git 基线、active-plan 状态/ID、允许范围、禁止范围、验收标准、落盘路径。

### 规划员

- 能力建议：`alice-planning`、`risk-slicing`、`test-matrix`。
- 只读；报告落盘到监督员指定路径并 `report_task`。
- 不创建 active plan、不派开发任务、不直接给用户最终路线。

### 开发员

- 能力建议：`alice-development`、`forge-1-20-1`、`java-17`、`headless-validation`。
- bus task 不是开工授权。必须先运行 `./tools/work-session-start.sh`；当前 `NEEDS_USER_DECISION` 应拒绝实现并 `request_input`/报告监督员。
- 完成后先 Git/HANDOVER/审核包，再 `report_task`；报告必须含 commit、push、验证和未验证项。

### 调查员

- 能力建议：`alice-research`、`source-audit`、`runtime-diagnostics`。
- 只读；研究报告落盘后 `report_task`。
- 证据不足时 `request_input`，不得靠推测给开发员下修复结论。

## 6. bus 与项目状态的优先级

```text
用户明确决定 / 客户端证据
> Git + 当前代码事实
> .alice-supervision/active-plan / reviews / reports
> docs/HANDOVER.md
> bus task/report/note
> 会话记忆与模型推断
```

bus 负责唤醒、编排、依赖和回传，不负责批准。bus 任务若与 active plan 冲突，暂停任务并由监督员修正；不要让开发员“按 bus 指令先做再补计划”。

## 7. bus 当前已知限制

隔离实验已验证依赖投递、报告/结算、取消、超时和重启恢复。仍有一个重要缺口：浏览器关闭或会话归档后，worker 可能继续留在运行时 agent registry，导致 `executorLive=true`，`offlineGraceMs` 不及时通知。

因此监督员不能只看“未收到离线告警”。恢复时要同时看 `list_tasks/get_task`、角色会话、Git 和 `.alice-supervision`。任务疑似滞留时优先对原 task id 进行取消、转派或请求状态，禁止重复创建同一实现任务。

## 8. 不可越过的项目边界

- 普通挖矿、`MineTask`、`DropCollectionTask` 保持 `HARD_PATH`。
- `SOFT_SURFACE` 不进入挖矿、拾取、道路、隧道、流体或逃生。
- `SEARCH_LIMIT != UNREACHABLE`，不能授权隧道。
- 编译、headless、调查报告、bus settle 和监督审核均不替代用户客户端验收。
- 当前不授权 C2/C3/C4、物品转移、AttackTask、LLM、AE/Mek 写操作或任何新行为。
