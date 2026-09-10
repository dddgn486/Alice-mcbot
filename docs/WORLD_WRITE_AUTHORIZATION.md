# 世界写入授权登记表（D-082）

> 本文是 D-076 红线第 2 条要求的**显式授权入口登记簿**：任何一个能改世界的入口都必须在此登记，
> 并写明"谁授权、为什么、谓词是什么、预算从哪来、是否记账"。**未登记即为隐式默认，违规。**
>
> 现状口径：本表描述 **D-082 实施后**的代码。基线 `5db7108` + D-082 提交。

## 1. 契约

| 组件 | 位置 | 职责 |
|---|---|---|
| `WriteReason` | `action/WriteReason.java` | 10 个结构化理由；每个理由携带 `Policy`（决定拒绝规则）与 `Action`（BREAK/PLACE/BOTH） |
| `WriteGrant` | `action/WriteGrant.java` | `(requester, reason)`——**谁、为什么**。不含预算 |
| `WriteAudit` | `action/WriteAudit.java` | 每次写入一条记录 + `[WRITE]` 日志 + 计数 + `unknownRequesterWrites()` 缺口度量。J6 账本的数据源 |
| `BlockBreakSafety.refusal(bot, pos, reason)` | `protection/BlockBreakSafety.java` | **破坏判定的唯一分发点**：按 `reason.policy()` 选 `explicitTargetRefusal` 或 `clearingRefusal` |
| `BlockInteraction.*(..., WriteGrant)` | `action/BlockInteraction.java` | **方块写入的唯一原语层**：`beginBreak` / `breakForBulkEdit` / `placeAt` / `placeBulkEdit` |
| `Task.taskName()` | `task/Task.java` | 任务身份默认值（类名；Job 覆写为 `lumber` 等），授权的 `requester` 来源 |

**策略派生规则**（不再由"调哪个方法"决定）：

| `Policy` | 理由 | 语义 |
|---|---|---|
| `EXPLICIT_TARGET` | `EXPECTED_TARGET` `DESCEND_FOOT` `BULK_EDIT` `SUPPORT_PLACEMENT` `STEP_PLACEMENT` `MANUAL` | 明确目标：保护区 + 不可破坏 + 流体拒绝；允许脚下与高代价方块 |
| `CLEARING` | `LINE_OF_SIGHT` `STANDING_SPACE` `PATH_ACCESS` | 清障：额外回避 `underfoot_block` 与 `expensive_clearing_block` |

## 2. 授权入口登记表

### 2.1 动作层（本 bot 直接破坏/放置）

| # | 入口 | 谓词（改哪一格） | 预算 | `requester` 来源 | 理由 | 记账 |
|---|---|---|---|---|---|---|
| A1 | `MineTask` 目标挖掘 | 构造时给定的单个 `target` | `MiningBudget`（规划期） | 调用点显式声明 | `EXPECTED_TARGET` | ✅ `WriteAudit` |
| A2 | `MineTask` 放支撑块 | `plan.supportPlacementPos()` | `findPlaceableSlot ≥ 0` | 同上（`grant.with`） | `SUPPORT_PLACEMENT` | ✅ |
| A3 | `LumberJob` 清障 | `BlockerClearPlanner.nextClearStep` 返回的单格 | `MAX_CLEAR_PER_TREE=8`/棵 | `jobName()` = `lumber` | `LINE_OF_SIGHT` | ✅ |
| A4 | `LumberJob` 砍伐 | 队列里的单根原木 | `MiningBudget` | 同上 | `EXPECTED_TARGET` | ✅ |
| A5 | `PlaceTask` 放置 | 目标格 | 一次性方块白名单 + 支撑面 | `taskName()` | `STEP_PLACEMENT` | ✅ |
| A6 | 道路施工（`RoadBuildTask` / `RoadBuilder`） | 单元格 | ⚠️ 无预算 | 常量 `road-build` / `road-builder` | `BULK_EDIT`（破坏与放置） | ✅ |
| A7 | 玩家命令（`BotManager` 挖掘 / `BotCommand`） | 目标格 | `MiningBudget` | 常量 `command` | `EXPECTED_TARGET` / `MANUAL` | ✅ |
| A8 | **脚手架回收**（`RestoreScopeTask`，J6-b） | 账本里**我方 TEMP 放置**逐块 | 每块两段寻路（`300` tick/块） | `restore` | `SCAFFOLD_RESTORE` | ✅ 并**销账** |

### 2.2 寻路内核（Movement 触发的破坏/放置）

许可 = `PathRequest.allowedMovementTypes`（**默认纯通行，无写入原语**）；凭证 = `PathRequest.requester`
（经 `LiveExecutionContext.requester()` 传到执行器）。

| # | 入口 | MovementType | 谓词 | 预算 | 理由 | 记账 |
|---|---|---|---|---|---|---|
| P1 | `BreakAndTraverseExecution` | `BREAK_AND_TRAVERSE` | `collectBlockers`（中间列 2 格） | ⚠️ 无（搜索成本模型计入路径成本） | `PATH_ACCESS` | ✅ |
| P2 | `BreakAndEnterExecution` | `BREAK_AND_ENTER` | `collectBlockers`（目标列躯干+头位） | ⚠️ 无 | `PATH_ACCESS` | ✅ |
| P3 | `DownwardExecution` | `DOWNWARD` | 脚下 1 格 | ⚠️ 无 | `DESCEND_FOOT` | ✅ |
| P4 | `PillarExecution` | `PILLAR` | 自己脚下 1 格 | 一次性方块 | `STEP_PLACEMENT` | ✅ |
| P5 | `PlaceStepAndTraverseExecution` | `PLACE_STEP_AND_TRAVERSE` | 前方落点 1 格 | 一次性方块 | `STEP_PLACEMENT` | ✅ |
| P6 | `BreakAndWalkMovement`（legacy，**无生产调用者**） | — | 脚位/头位障碍 | 无 | `PATH_ACCESS`（`requester=legacy-break-and-walk`） | ✅ |
| P7 | **脚手架回收专用请求** `PathRequest.scaffoldRemoval`（J6-b） | `DOWNWARD` + `TRAVERSE/DIAGONAL/ASCEND/DESCEND/FALL` | 脚下那一格（`DOWNWARD` 语义） | 每块 300 tick | `restore` | ✅ |
| | ↑ **刻意不含** `PILLAR` / `PLACE_STEP_AND_TRAVERSE`（回收阶段不许再建）与 `BREAK_AND_*`（不许沿途挖地形） | | | | | |

### 2.3 三个工厂的 Movement 集合（授权强度）

| 工厂 | MovementType 集合 | 用途 |
|---|---|---|
| `PathRequest.of` | `TRAVERSE, DIAGONAL, ASCEND, DESCEND` | 纯通行（默认；HARD_PATH） |
| `PathRequest.miningApproach` | 上列 + `BREAK_AND_TRAVERSE, BREAK_AND_ENTER, PLACE_STEP_AND_TRAVERSE`（**显式禁用** `PILLAR/FALL/DOWNWARD`） | 挖矿到达（D-067 ㉘） |
| `PathRequest.withWorldModification` | 上列 + `DOWNWARD, PILLAR, FALL` | 通用世界修改（诊断/少量测试） |

## 3. 已登记缺口（**尚未修复，按影响排序**）

| # | 缺口 | 事实 | 影响 |
|---|---|---|---|
| ~~G1~~ | ~~`PathRequest.requester` 恒为 `"unknown"`~~ **已修复（R2a）** | 三个工厂改为**必填** requester 参数（无 3 参重载），**29 处**调用点全部显式命名；`LiveExecutionContext` 改为必填第 7 组件并**删除 5/6 参兼容构造器**（那是"默认 unknown"通道）；`PathSession.startSegment` 传入 `request.requester()` → 内核写入 P1–P5 现在也有主 | 已闭合（待客户端 `unknown=0` 复验） |
| ~~G2~~ | ~~执行期不复验授权~~ **已修复（R2b）** | `PathSession.startSegment` 现在每段执行前用**会话自己的请求**复验 `request.allows(movementType)`，未授权即 `UNAUTHORIZED_MOVEMENT` 拒绝执行 | 已闭合 |
| G3 | 模组连锁破坏无凭证 | `MineTask.beginChain` → `ChainMining` 反射调模组 `MiningScheduler` | 破坏量不受 Alice 预算约束，**Alice 无法在其内部插入判定**；只能控制"是否触发" |
| G4 | 内核写入无独立预算 | P1–P3 的破坏量只体现在搜索成本里，执行期不再比对 | 到达路径的实际破坏量不受 `MiningBudget` 约束（勘测清单 4 已记） |
| G5 | 容器写入是第三个维度 | `TransferTask` / `ChestBotTransferPrimitive`（`extractItem`/`insertItem`） | 现有授权面完全不覆盖；需单独立项（`TransferLedgerData` 是记账不是权限） |
| G6 | legacy `pathing/movement` 裸写入 | `DescendMovement.java:142`、`PillarMovement.java:146` 裸 `level.setBlock(DIRT)` | 当前**无生产调用者**（`MovementHelper.generateMovements` 无外部调用），是潜在缺口 |
| G7 | 死代码闸门 | `FluidRiskPolicy.miningRefusal` 无调用者，但 `MineTask` 保留 `fluid_risk_lava` 硬拒绝分支 | 一个**永远为假**的硬拒绝分支（勘测清单 4） |
| ~~G9~~ | ~~`breakForBulkEdit` 只审计不设闸~~ **已修复（R2b）** | 闸门收进方法内（按 `grant.reason()` 派生策略；`BULK_EDIT` → 明确目标策略）；`RoadBuildTask.forceBreak` 改为**如实返回被拒**（不再假装成功）；返回值 `true=已破坏 / false=被拒未写入` | 已闭合（行为变更，待回归验证） |
| G8 | `MovementCapabilities` 无读取点 | `changesWorld`/`canBreakBlocks`/`requiresZoneAuthorization` 等字段只在自身内部出现 | 文档性元数据，**不构成闸门**（勘测清单 2） |

**豁免（非生产写入）**：`bot/BotSelftest`、`pathing/PathingRegression`、`task/mining/MiningReplanFixture`、
`transfer/*Fixture` 等夹具类直接改世界用于布景，不走授权面。

## 4. 后续顺序

| 步骤 | 内容 | 前置 |
|---|---|---|
| ~~R2a~~ | ~~填 `PathRequest.requester`~~ **已完成**：29 处工厂调用点 + `PathSession` → `LiveExecutionContext` 归因贯通 | — |
| ~~R2b~~ | ~~执行期复验授权（G2）+ 闸门收进 `breakForBulkEdit`（G9）~~ **已完成**；附带修 `LumberJob` 清障预算按棵计（J2 前置缺陷） | R2a |
| **R3** | 内核写入预算（G4）：把 `MiningBudget` 或等价预算接到 P1–P3 的执行期 | R2 |
| **J6** | `WriteAudit` → 持久化 `WorldModLedger`；建拆同权配对；恢复 | R2/R3 |
| 之后 | G3（模组连锁，需与模组能力层一起）、G5（容器写入维度）、G6/G7/G8 清理 | J6 |

## 5. J6 接入点

- `WriteAudit.record(...)` 是唯一写入口 → 替换 sink 为账本即可，**不需要改任何调用点**；
- 配对所需字段已齐：`tick / action / pos / block / requester / reason`；
- 与 `ScopeBuffer` 交叉校验：`ScopeBuffer` 记"世界实际发生了什么"（含外部玩家破坏，经 Forge 事件），
  `WriteAudit` 记"Alice 授权自己做了什么"——两者的差集就是外部扰动，正是 `MAINTAIN` 区域不变量需要的输入。
