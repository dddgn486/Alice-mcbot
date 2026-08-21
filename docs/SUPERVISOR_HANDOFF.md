# Alice 下一任监督员接管说明

> 更新时间：2026-08-21
> 用途：四角色 `dsh-agent-bus` 工作流启用后的首份监督员恢复清单。

## 1. 当前唯一可信状态

- 当前 Git HEAD、Windows `origin/master`：`10f4cd7`（F1-F4 维护业务提交，含本文件更新；GitHub `master` 推送前先 `git fetch github` 查冲突）。
- 最新业务实现：`10f4cd7`（`20260821-interface-c1-maintenance-f1-f4-v1` 收口）；前序业务 `8bb2d7b1641c0b6e6063d4c78ae0357896fd0c90`（`interface-readonly-snapshot-v1`，S1-S4 已 `USER_ACCEPTED`）。
- Active plan：`.alice-supervision/active-plan.md`，状态 `APPROVED_FOR_IMPLEMENTATION`；F1-F4 已实现，等待监督员审核本轮提交与审核包。
- 实现包结论：C1 S1-S4 已由用户验收（窄范围：独立扫描器身份、只读箱子扫描、无 block entity 扫描、原版钻石铲隔离）；F1-F4 维护已实现并通过 `compileJava` + focused headless，待监督审核，不是客户端验收。
- 客户端结果：`USER_ACCEPTED`（仅独立扫描器 S1-S4，见 `.alice-supervision/client-tests/8bb2d7b.md`）。
- 历史旧存档卡死保持**未归因、未修复**；F1-F4 与其隔离，本轮未诊断、未重复加载。

## 2. 历史旧存档卡死观察（未归因、未修复）

Windows 客户端加载旧存档时曾卡死。用户提供的最后可见 Alice 日志是：

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

## 4. `8bb2d7b` 的维护条件已收口（F1-F4）

`20260821-interface-c1-maintenance-f1-f4-v1` 已收口评审 `20260821-8bb2d7b.md` 的 F1-F4 全部条件：

1. ✅ selftest `runInterfaceSnapshotRegression` 补 formatter 纯度、捕获后快照稳定、非空 items 不可变断言，全部并入单一 `INTERFACE_SNAPSHOT_SELFTEST PASS|FAIL`；
2. ✅ `InterfaceScanner.capture` 的 `hasChunkAt` 先于目标 `getBlockState/getBlockEntity`，未加载以保守常量 `unknown` 为 `blockId`，不读取目标世界状态；
3. ✅ HANDOVER/SUPERVISOR_HANDOFF 保持实际 HEAD（业务 SHA 与治理提交分开记录）；
4. ✅ 删除无调用者的 `scanItems/scanEnergy/scanFluid/slotHint` 死代码，类注释改为 C1 raw unsided readonly facts，Mek 投影保留为显式非通用 legacy；
5. ✅ 用户已完成 `alice:interface_scanner` S1-S4 矩阵（`USER_ACCEPTED`）。

历史旧存档卡死仍为未归因观察，与本维护包隔离；不要把这些维护与旧存档卡死修复混成一个工作包。

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
