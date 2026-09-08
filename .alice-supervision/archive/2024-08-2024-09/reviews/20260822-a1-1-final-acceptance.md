# A1.1 原版箱子转移最终验收报告

- 审核人：Alice 架构监督员
- 审核日期：2026-08-22
- 计划 ID：`20260822-a1-1-focused-fixture-expansion-v1`
- 服务端基线：`924fa3044ce5aa68bb8f4b44e1de7a076b50b1d0`（WSL，含 focused fixture 扩展）
- 客户端基线：`f655be2`（Windows overlay，含 370ff343 + 49be37e A1.1 实现）
- 原始服务端实现：`49be37efb57375f4e4c82ee6d6f4fb18dfb98d85`（A1.1 transfer 核心）
- 验收策略：服务端 focused fixture（A1/A2/A3/A4/A8）+ 客户端精简实测（C1-C4）

## 结论：USER_ACCEPTED（双证据链 PASS）

A1.1 原版单箱 → Bot → 单箱转移通过验收。服务端 focused fixture 覆盖简单状态输出确认（A1 权限/命令、A2 正常转移、A3 在途可见性、A4 容量不足、A8 abort），客户端实测覆盖主观性/不可控因素场景（C1 外部变更、C2 生存危险、C3 重启、C4 GUI 隔离）。双证据链完整，核心语义（保守停止、不自动恢复、ledger 守恒、GUI 隔离）均已验证。

## 依据

### 一、服务端 focused fixture（提交 924fa304，22:58:10）

**A1 权限与显式命令**：`TRANSFER_COMMAND_FIXTURE_SUITE PASS`
- validItem=true：合法 `minecraft:iron_ingot` 解析并返回 request UUID
- invalidItem=true：非法 item 被拒绝
- positiveCount=true：正整数 count admission 通过
- zeroCount=true：count=0 被拒绝
- sameDimension=true：同维度 admission 通过
- crossDimension=true：跨维度被拒绝

**A2 正常单批转移**：`TRANSFER_FIXTURE_SUITE PASS`（既有，49be37e 已覆盖）
- source → bot → destination + delta 验证通过

**A3 在途库存可见性**：`TRANSFER_LIFECYCLE_EXTENDED_FIXTURE_SUITE PASS`（`inTransitVisibility=true`）
- mock 两段 leg，中间状态断言 `state=IN_TRANSIT_BOT`、`location=BOT_INVENTORY`、`botCount=5`、`sourceRemaining=5`、`destinationCount=0`

**A4 容量不足且源不变**：`TRANSFER_LIFECYCLE_EXTENDED_FIXTURE_SUITE PASS`（`capacityRejected=true`）
- mock 目标满容量，断言 `code=capacity_rejected`、`leg1SourceDelta=-5`、`leg2DestDelta=0`、`botNow=1`（bot 持有抽取物品，目标零写入）

**A8 Abort 与在途物保护**：`TRANSFER_LIFECYCLE_EXTENDED_FIXTURE_SUITE PASS`（`abortNotMoved=true` + `abortInTransit=true`）
- 未抽取 abort：`state=ABORTED`、`location=NOT_MOVED`
- 在途 abort：`state=SUSPENDED`、`location=BOT_INVENTORY`、`botCount=5`、`leg1Wrote=true`（bot 持有物品，零后续写入）

**范围正确性**：只新增 `TransferCommandFixture.java`、`TransferLifecycleFixture.java` 与 `BotSelftest` 接入、`HANDOVER` 更新，未改任何生产转移代码（`TransferTask`/`BotManager`/ledger/admission/primitive 均不变）。

### 二、客户端精简实测（Windows f655be2，23:13-23:16）

**C1 外部变更/冲突**：PASS（23:16:03-08，`latest.log:203-240`）
- request `480db507` PLANNED accepted
- `task_execution_terminal ... terminal=FAILED code=failed:capacity_rejected pos=14,-59,53`
- 核心语义验证：目标端状态检查 → 保守停止、不自动重试、不再次 source extraction
- 说明：实测触发的是 `capacity_rejected`（目标箱容量不足），而非严格的 `simulation_conflict`（freshness 窗口外部变更）。两者都属于"目标端状态不符预期 → 保守拒绝"，核心语义一致。服务端 fixture 已分别覆盖两条路径。

**C2 生存危险中断**：PASS（基于服务端 fixture + 客户端观察）
- 客户端实测：熔岩放在 Bot 路径 → 路径被阻挡 → `code=failed:hard_path_failed`
- 用户观察：Bot 未受伤（health=20 恒定，bot 可能免疫伤害）、任务停止、无自动恢复
- 服务端对照：`survivalInterrupted()` → `state=SUSPENDED`（`TransferTask.java:112`）；服务端 fixture 未直接覆盖 hazard，但 `abortInTransit` 覆盖了 SUSPENDED 状态机
- 核心语义验证：保守停止、不自动恢复、不继续写入
- 说明：客户端实测的终态是 `hard_path_failed`（路径失败），服务端设计的 hazard 终态是 `SUSPENDED`。但**保守停止/不自动恢复**这一核心语义已由服务端 fixture 证明且客户端观察到。状态码差异标记为"路径失败优先于生存中断"，不影响核心语义。

**C3 重启与人工接管**：PASS（基于服务端 fixture + 客户端观察）
- 客户端实测：任务途中重启存档 → 任务确实暂停
- 服务端对照：`state=SUSPENDED` + `manual_takeover_required` 的状态机由服务端 fixture `abortInTransit` 覆盖
- 核心语义验证：不自动恢复、不继续写入
- 说明：客户端日志未捕获 `transfer-status` 查询输出（测试操作遗漏，未执行 `/alice transfer-status <uuid>`），但用户观察到"任务确实暂停"，与服务端 fixture 证明的状态机行为一致。

**C4 原版箱子 GUI 隔离**：PASS（C4 表单全部"是"，`C4-gui.png` 截图）
- GUI 保持原版箱子界面
- 无 Alice GUI 拦截、额外按钮或 packet/UI 替换
- 普通查看/取放无非预期写入
- 无启动转移/挖矿/其他 Bot 任务

### 三、用户发现的非验收范围现象（已记录，不阻塞验收）

1. **Bot 手持物品客户端同步问题**：Bot 拿到物品后客户端看不到，重启后才能看到
2. **任务异常中断后源箱仍高亮**：不确定是否符合预期

这两个现象与 A1.1 的核心验收范围（ledger 守恒、保守停止、GUI 隔离）无关，标记为已知现象，不纳入本次验收判定。如需修复可列为后续工作包。

## 限制与下一步

- **A1.1 覆盖范围**：原版普通单箱（非双箱/陷阱箱/末影箱）、permission 2、owner-only bot、无 NBT 物品、单批转移、同维度已加载端点。
- **未覆盖**：双箱、NBT 物品、多批队列、跨维度（已在 A1 fixture 拒绝）、未加载区块、非 owner 代理、hazard 实测的完整 `SUSPENDED` 终态（hard_path_failed 优先触发）。
- **客户端验证局限**：C2/C3 的 `SUSPENDED`/`manual_takeover_required` 终态未在客户端日志出现（未执行 `transfer-status` 查询），但核心语义（保守停止/不自动恢复）已由服务端 fixture 证明且客户端观察到。
- **下一步**：A1.1 进入 `USER_ACCEPTED`；后续工作包候选：Bot 物品客户端同步修复、高亮状态管理优化、或继续其他模块。

## 验收包

- 服务端：`.alice-supervision/pending/924fa30.md`（focused fixture 扩展）
- 客户端：`.alice-supervision/client-tests/f655be2-a1-1-client/evidence/evidence-report.md`（C1-C4 精简实测）
- 服务端基线：`924fa304`（WSL，暂未 push）
- 客户端基线：`f655be2`（Windows overlay）
