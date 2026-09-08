# Alice 自制方案全面重构调查

- **任务**：`.alice-supervision/research/project-refactor-survey-task.txt`
- **当前基线**：`ab510fd`；项目为 Forge 1.20.1 / Java 17 / ForgeGradle 6 / Parchment。
- **日期**：2026-08-24
- **方式**：只读。已读取任务、进度快照、`docs/HANDOVER.md`、Alice 模块源码，并联网检索/固定官方 GitHub、Forge 官方文档和项目发布物；未改代码、计划、HANDOVER 或客户端证据，未运行 Alice 测试。

## 0. 结论先行

不建议把本次“全面重构”理解成把 Alice 替换为 Baritone、Carpet、Mineflayer 或 TLM。它们各自的执行模型、loader、运行侧和产品语义不同：

- **Baritone** 是控制实际客户端玩家的路径机器人；可借鉴 A*、movement primitive、成本/重规划模型，不能直接取代服务端 `BotPlayer`、任务账本或 Forge 任务生命周期。
- **Carpet FakePlayer** 是 Fabric mod 内的 `ServerPlayer` 子类与 action pack；其模式对 Alice 有价值，但不能作为 Forge 1.20.1 依赖，也不能证明 Alice 的物理问题为“FakePlayer 固有不可修”。
- **Mineflayer** 是 Node.js 协议客户端；它的“离散路径 + 局部逐 tick 物理仿真”值得借鉴，但不是 Java/Forge 嵌入库。
- **TLM Maid** 是普通服务端 `LivingEntity`（女仆）模型，不是玩家假人；适合借鉴实体库存/屏幕交互分层，不能替换“必须被 PlayerList 注册、以玩家身份行动”的 Alice bot。

真正需要重构的不是全部模块，而是两个**证据与边界失配**：

1. **P0 FakePlayer 适配层**：当前 `BotPlayer`/`FakeConnection` 的物理、网络同步和客户端表现没有被同一证据链验证。`progress-snapshot` 已明确否定早期“服务端物理完全正常”的结论，且 `ab510fd` 广播修复又引入真实玩家 1.5 格跳跃异常。先冻结这层扩展，先建立可证伪测试。
2. **P0 GUI 交互协议**：8 轮失败的根因不是缺少另一个 GUI 库，而是 server logical slot 与 client view slot 没有明确契约。方向 B（server-authoritative snapshot + action packet）方向正确，但必须先固化 `viewIndex -> region -> inventoryIndex -> rule`、revision/fingerprint 和 ActionResult，才允许解冻。

其余模块中，**寻路只做“部分借鉴”而不直接替换**；transfer ledger、保护/范围、任务语义、持久化、道路产品模型仍应保持自制。

## 1. 证据边界与外部项目版本

| 项目 | 固定来源/边界 | 兼容和许可事实 | 可用于 Alice 的边界 |
|---|---|---|---|
| Baritone | [官方仓库](https://github.com/cabaletta/baritone)，本次源码固定 `ad627c83cc0ac6059d7a1b29eca4e54214584b5e`；README 当前列出 1.20.1 Forge `v1.10.1` API 发布物 | LGPL-3.0（README 标明 anime exception）；有 1.20.1 Forge release 表项 | 算法/设计参考；**不是**可直接嵌入服务端 FakePlayer 的库 |
| Fabric Carpet | [官方仓库](https://github.com/gnembon/fabric-carpet)，`EntityPlayerMPFake.java` / `EntityPlayerActionPack.java` 当前主线源码 | README 明确是 Fabric；不是 Forge 1.20.1 artifact | FakePlayer 生命周期与 ActionPack 模式参考；不能直接依赖 |
| Touhou Little Maid | [官方仓库](https://github.com/TartaricAcid/TouhouLittleMaid)，Alice 已有固定 1.20 分支 `8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6` 的专项调查 | 项目是 Forge 女仆实体 mod；不是 PlayerList fake player | Inventory/实体 GUI 设计参考；不能替换玩家假人 |
| Mineflayer Pathfinder | [官方仓库](https://github.com/PrismarineJS/mineflayer-pathfinder)，固定 `d1f4d7fdbebc452f390a9bc8b64e9d8ebfdb9f95` | Node.js + PrismarineJS 协议生态，不是 Java Forge runtime | movement cost、局部 physics simulation、failure event 模式参考 |
| Forge | [GameTest 官方文档](https://docs.minecraftforge.net/en/1.20.x/misc/gametest/)；[Menus 官方文档](https://docs.minecraftforge.net/en/1.20.1/gui/menus/) | Alice 当前 `build.gradle:87-105` 已配置 `gameTestServer` | 正式测试/容器基线，应优先用而非新依赖 |

注意：Baritone 仓库 README 的 1.20.1 发布物证据，不代表该仓库当前主分支就是为 Alice 的 Forge `47.x` /服务端环境设计。Carpet 也不能从“其 fake player 支持某行为”推导 Forge 版本的相同行为。外部项目只能作为设计或独立试验的依据。

## 2. Alice 已确认的自制方案清单与决策

下表中的“替代”严格指可以安全替换 Alice 当前职责，而不是“外部项目存在相似名称”。

| 分类 / Alice 模块 | 当前源码职责事实 | 最接近现成方案 | 决策 | 理由与边界 |
|---|---|---|---|---|
| 核心 | `BotPlayer` | Carpet `EntityPlayerMPFake` | 部分借鉴 | 两者均继承 `ServerPlayer` 并经 PlayerList 注册；Carpet 是 Fabric、版本/网络栈不兼容。先学习 tick/action/connection 边界，不替换类。 |
| 核心 | `FakeConnection` | Carpet `FakeClientConnection` | 部分借鉴后重写 | Alice 当前自制空/选择性 send 是问题集中点；Carpet 的连接与 spawn/tick 边界可审计，但不可拷贝入 Forge。必须做 packet recipient contract。 |
| 核心 | `BotManager` + `BotSession` | Carpet player manager + ActionPack；一般 job queue | 保持自制，收窄接口 | Alice 有 PlayerList、SavedData、任务替换阻断、Scope 生命周期和客户端高亮责任；通用 queue 不了解世界/主线程。 |
| 核心 | `Task`/`TaskTarget` | Java 状态机库、`ScheduledExecutorService` | 保持自制，部分借鉴 | `Task.tick()` 的三态、主线程语义是小而明确的正确抽象。通用异步 queue 会破坏 MC 主线程和 deterministic failure code。 |
| 感知 | `ScopeBuffer` | Forge Event Bus | 保持自制，改进索引 | 已正确建立在 Forge events 上（`EntityJoinLevelEvent`、`BlockEvent.BreakEvent`）；全局 `ACTIVE` 线性过滤仅在规模增长时按 chunk/bot 分桶。 |
| 路径 | `AStarPathfinder`/`SurfacePathfinder` | Baritone A*；Mineflayer pathfinder | 部分借鉴 | Alice 有明确 `REACHED/UNREACHABLE/SEARCH_LIMIT` 边界；Baritone 可供 movement cost/timeout/cancel 优化，不能直接替换产品语义。 |
| 路径 | `MovementHelper` | Baritone `MovementHelper` | 部分借鉴 | 当前已移植保守几何思路；应按 primitive 引入实体、液体、风险和可恢复性，而非复制整个 client context。 |
| 移动 | `PathExecutor` HARD_PATH | Baritone movement executor | 保持至 P0 后再决定 | Alice 现为 `setPos` + `setOnGround`，不真实但确定。Baritone 控制客户端 player input；不能嵌进 server fake player。 |
| 移动 | `SoftMovementPrimitive` NATIVE_TRAVEL | Mineflayer physics / Baritone input actions | 部分借鉴，P0 冻结扩展 | `travel` 本身不是完整 fake-player integration。需要输入/physics/tick/packet 的单独证据链，不得接 mine/road/tunnel。 |
| 任务 | `MineTask` + `BotMiner` | Baritone MineProcess | 保持自制，借鉴矿点风险模型 | Alice 的受限局部清障、保护区、origin-drop 归属、`target_requires_tunnel` 是产品安全边界；Baritone 的自动 break/place 路径会扩大权限。 |
| 任务 | `PlaceTask` | Baritone placement movement | 保持自制 | Alice 站位候选、raycast、保护语义不应被 client bot 行为替代。 |
| 任务 | `DropCollectionTask` | Baritone dropped scan / Mineflayer item collection | 保持自制 | Alice 只收目标 origin 掉落物，是业务所有权规则，外部框架一般按邻近物品收集。 |
| 任务 | `TransferTask` + `ChestBotTransferPrimitive` | 通用 inventory transaction / Forge capability | 保持自制 | 仅限原版单箱、三方观察、无自动恢复、明确 suspend 的审计语义不可外包。 |
| 任务 | `SoftPathProbeTask` / `FollowTask` / `SoftPathMineTask` | Baritone follow/path behavior | 保持实验隔离，部分借鉴 | 现有为 probe/test tool；不能因 Baritone 功能完整而绕过 P1 和冻结边界。 |
| 任务 | `RoadBuildTask` + `ContinuousRoadCurve`/`TunnelPlanner` | Baritone builder / schematic mods | 保持自制 | Alice 道路是用户定义“施工动画”和数学成本模型，甚至允许无限圆石/强制施工；通用 bot 不匹配且风险更大。 |
| 数据 | `TransferLedgerData` | Forge `SavedData` | 保持自制 | 正确使用 `SavedData`；状态/证据/手工接管是 Alice 产品语义。 |
| 数据 | `BotWorldData` | Forge `SavedData` | 保持自制 | 这是正确的 Forge 持久化载体；只应补 schema migration/health lifecycle tests。 |
| 数据 | `InterfaceSnapshot`/`InterfaceScanner` | Forge capability APIs | 保持自制，部分借鉴 | Scanner 已直接利用 capability；immutable projection/snapshot 是防跨线程/生命周期泄露的本地边界。 |
| GUI | retired `BotInventoryMenu` / `ModMenuTypes` | Vanilla `InventoryMenu` / `AbstractContainerMenu` | 不再继续替代性修补 | 方向 A 已冻结；可保留作源码参照，不能再增加第 9 轮试错。 |
| GUI | `BotInventoryService` + snapshot + packets + Screen | vanilla menu、TLM 风格实体 inventory UI | 保持方向 B，重构协议契约 | server-authoritative custom snapshot/action 是可行模式；现成菜单不能解决 view/server index mismatch。 |
| 交互 | `TransferEndpointSelector` / `SoftPathProbeSelector` | Forge `UseOn` + `PlayerInteractEvent` | 保持自制 | 是窄产品入口，已尽量使用 Forge event；没有可替代组件。 |
| 测试 | `BotSelftest` / focused fixtures | Forge GameTest | 分层并存，增加 GameTest | selftest 适合快速业务 smoke；GameTest 适合实体/世界可重复场景；两者均不替代客户端。 |
| 可观测性 | `TaskExecutionRecord` / `BotLog` | tracing/logging library | 保持自制，升级 schema | task id、stable failure code、bot tick/position是项目语义；引入外部 tracing 对当前价值低。 |

计数（按 22 个边界模块）：**0 个建议完全替代；8 个建议部分借鉴或分层替换；14 个应保持自制**。这不是保守偏好，而是 Alice 的关键差异确实在服务端权威、保护、审计、显式失败和用户限定行为。

## 3. 重点模块深度分析

### 3.1 FakePlayer 物理：首要重构的是适配/证据层，不是换库

#### Alice 当前事实

- `BotPlayer.java:22-36` 仅继承 `ServerPlayer` 并重写 `tick()` 包裹 `super.tick()` 的 `NullPointerException`；没有覆盖 `travel`、`aiStep`、`hurt`、`push` 或 `knockback`。
- `BotManager.spawn:72-80` 构造 BotPlayer、以 `FakeConnection` 经 `placeNewPlayer` 注册并设为 `SURVIVAL`。
- `FakeConnection` 是 Alice 自制网络边界；`progress-snapshot:28-39` 记录 `ab510fd` 的位置/速度广播修复引入玩家跳跃异常，而 bot 无推挤/无击退至少在 `2298dd3` 已存在。
- 因此“空 `send` 是唯一根因”已被客户端测试推翻；它至多解释部分可见同步问题，不足以解释 bot 的服务端/客户端物理全链路。

#### Carpet 的可借鉴事实

[Carpet 的 `EntityPlayerMPFake`](https://github.com/gnembon/fabric-carpet/blob/master/src/main/java/carpet/patches/EntityPlayerMPFake.java) 是 `ServerPlayer` 子类；`createFake` 通过 `PlayerList.placeNewPlayer(new FakeClientConnection(...))` 注册。其 `tick()` 先处理 connection position 再 `super.tick()`。其 [`EntityPlayerActionPack`](https://github.com/gnembon/fabric-carpet/blob/master/src/main/java/carpet/helpers/EntityPlayerActionPack.java) 将 forward/strafing/jump/use/attack 作为独立逐 tick action 组件，而不是把高层任务直接塞进 player tick。

**不可迁移风险**：Carpet README 明确是 Fabric；当前源代码映射/版本同 Alice Forge 1.20.1 不同；其实现出现过 fake-player 击退相关 issue（[issue #1058](https://github.com/gnembon/fabric-carpet/issues/1058)），所以“Carpet 也做 fake player”不能作正确性背书。

#### Baritone 与 TLM

- Baritone 控制实际客户端玩家 context/input，并不是生成 `ServerPlayer` fake entity；它不解决 Alice PlayerList/FakeConnection 物理问题。
- TLM 使用普通女仆 `LivingEntity`，由原版 entity tick/collision/knockback 管理。这解释“普通实体路径”可能更自然，但失去 `ServerPlayer` 身份、玩家背包/权限/PlayerList/交互语义。不能为了碰撞而将 Alice bot 直接改成 Maid。

#### 推荐 P0 方案

保留 `BotPlayer`，将 `FakeConnection` 改造为**显式、最小、可测的 adapter**；新增但不立即实现以下契约：

1. `BotLifecycleAdapter`：spawn、despawn、game mode、connection/player-list lifecycle。
2. `BotInputController`：模拟输入与任务策略分离，借鉴 Carpet ActionPack；仅在测试中先验证 forward/jump/zero input。
3. `BotNetworkReplicationPolicy`：明确包的**生产者、目标接收者、方向、实体 id、可见范围、是否允许 broadcast**。绝不能将“发给 fake player's connection 的 S2C 包”改成对所有真实玩家重发。
4. `BotPhysicsProbe`：记录 server tick、AABB、noPhysics、spectator、pushable、onGround、deltaMovement、hurt return、health、collision flags；并记录实际 client recipient packet。

**最小验证矩阵**：真实玩家与 bot 相对位置、双方 `isPushable`/AABB、推挤前后坐标/速度；伤害前后 `hurt` return/health/delta；recipient packet；Windows 客户端视觉。若服务端断言与客户端不一致，停止扩展并先定位 replication；若服务端自身不变，停止网络修补并定位 entity/tick/attack 语义。

### 3.2 GUI：不换菜单库，先完成 view-model 协议

Forge 官方 [Menus 文档](https://docs.minecraftforge.net/en/1.20.1/gui/menus/) 的模型是 server `AbstractContainerMenu` 与 client screen 的受控同步。Alice 已连续 8 轮在方向 A 和早期方向 B 失败，`HANDOVER.md:81` 已明确统一根因：client 猜测 index，而 server 读取 vanilla inventory index。

`BotInventoryService.java:22-31,62-90` 已有正确方向的局部基础：client 不直接写、本体 server 先校验、写完回推 snapshot。但当前 packet 只携带 bot id/name/slots，`applyAction` 只接原始 `slotIndex`，没有协议 revision、fingerprint、view mapping 或结构化 ActionResult。

**TLM 可借鉴**：它作为成熟 Forge 实体 mod 证明实体 inventory/GUI 能在 Forge 上实现；但其女仆 inventory layout、entity ownership、menu 和 capability 语义不是 Alice bot 的 41 槽/任务只读业务协议。借鉴其“inventory domain 与 GUI 分层”，不复制 screen/menu。

**推荐**：永久放弃继续修 retired `BotInventoryMenu` 方向 A；方向 B 保留，重构为：

```text
InventoryViewDefinition (versioned, pure data)
  viewIndex -> logicalRegion -> backingInventoryIndex -> allowedAction
BotInventorySnapshot(revision, slotFingerprints, viewDefinitionId, items)
BotInventoryAction(expectedRevision, viewIndex, expectedFingerprint, action)
BotInventoryActionResult(code, refreshedSnapshot)
```

所有 mapping 应先用纯 Java fixture 验证，再做 Forge GameTest，再做 Windows G 矩阵。没有“成熟 bot inventory GUI mod”能替代这一产品专属的 41 槽、busy read-only、binding curse、server authority 和 stale action 规则。

### 3.3 寻路：Baritone 是参考实现，不是可直接替换件

Baritone 固定源码中：

- [`AStarPathFinder.java`](https://github.com/cabaletta/baritone/blob/ad627c83cc0ac6059d7a1b29eca4e54214584b5e/src/main/java/baritone/pathing/calc/AStarPathFinder.java) 是 A* 搜索，有 timeout/cancel、movement 计数与 cost validation。
- [`MovementHelper.java`](https://github.com/cabaletta/baritone/blob/ad627c83cc0ac6059d7a1b29eca4e54214584b5e/src/main/java/baritone/pathing/movement/MovementHelper.java) 处理方块、液体、门、危险、可通行与工具/方块状态。
- [`MineProcess.java`](https://github.com/cabaletta/baritone/blob/ad627c83cc0ac6059d7a1b29eca4e54214584b5e/src/main/java/baritone/process/MineProcess.java) 管理候选矿点、blacklist、drop scan、重新选 goal。

Mineflayer pathfinder 的 [`movements.js`](https://github.com/PrismarineJS/mineflayer-pathfinder/blob/d1f4d7fdbebc452f390a9bc8b64e9d8ebfdb9f95/lib/movements.js) 将 entity intersection、dig/place、liquid、gravity blocks 计入邻居成本；[`physics.js`](https://github.com/PrismarineJS/mineflayer-pathfinder/blob/d1f4d7fdbebc452f390a9bc8b64e9d8ebfdb9f95/lib/physics.js) 以 `prismarine-physics` 的 PlayerState 做局部逐 tick模拟。

Alice 的 `SurfacePathfinder.java:8-27` 已刻意规定只走曲面，且 `SEARCH_LIMIT` 不可作 `UNREACHABLE`；`PathExecutor.java:12-16,85-98` 则明确使用手动位置步进。这与 Baritone 的可破/可放路径和客户端动作执行不是同一契约。

**决定**：

- 不接入 Baritone jar；它的 1.20.1 artifact、client context 和 LGPL 许可均使 server-side internal replacement 成本/风险高。
- 保留 Alice `SurfacePathfinder`/`MovementHelper`/明确失败码；从 Baritone 借鉴 movement primitive 成本、动作可行性预检、cancellation/replan 和风险处理。
- 软物理在 P0 成功前不扩展；普通 MineTask/DropCollection/Road/Tunnel 仍为 HARD_PATH，不能由 Baritone “功能更全”授权接入。
- Mineflayer 不移植；最多将其 physics probe 作为评估“是否需要局部预测”的研究原型。

### 3.4 任务系统：没有需要替换的 Forge Task Queue

Forge Event Bus 是事件派发器，不是有世界语义的延迟任务/状态机框架。`ScheduledExecutorService` 也不可以驱动 world mutation，因为 Minecraft 逻辑必须在 server main thread。

Alice `Task.java:5-30` 是小接口，`BotSession` 每 tick 驱动，`TaskExecutionRecord` 记录终态；这是合适基础。`TransferLedgerData.java:19-123` 又提供独立审计状态机：不自动恢复、`SUSPENDED` 要求手工接管、unfinished blocks bot replacement。这类不变量既不应交给 generic queue，也不该由状态机库隐藏。

**决定**：保留 Task；P2 只做内部标准化：`TaskOutcome`（stable code/evidence）、可组合 phase、cancellation/replan policy、任务资源声明（bot/target/scope/ledger），而非引入 Spring State Machine 等通用依赖。

## 4. P0-P3 重构路线图

### P0：FakePlayer 物理与同步的证据闭环

- **目标**：把 `BotPlayer`/`FakeConnection` 从隐式行为改为最小、明确、可测 adapter；先判定问题位于 entity physics、fake connection、tracking recipient 还是客户端表现。
- **依赖**：先接入 Forge GameTest/结构化双端诊断（开发工具报告建议）；不得在现有 `ab510fd` 上继续扩大广播。
- **范围**：不改 MineTask、PathExecutor、道路、隧道、普通转移；不实现 P2 软移动。
- **风险**：server tick、PlayerList、connection/packet 重复发送可能影响真实玩家，当前跳跃异常已证明该风险。
- **估时**：调查与 fixture 2-4 天；一个窄适配修复 1-3 天；Windows 矩阵 1-2 天。不是“30 分钟重发 packet”。
- **验证**：GameTest + dev packet observer + JDWP 栈/字段 + Windows push/knockback/normal player jump 对照；server/client 证据必须关联同一 tick/entity/recipient。
- **停止条件**：任何改动影响非 bot 玩家物理，立刻回退该适配分支；若证明 ServerPlayer fake 模型无法满足产品需求，再由用户选择“保持硬路径 fake player”或“单独评估普通 LivingEntity bot”，不可隐式换实体类型。

### P1：GUI 协议重启前置重构

- **目标**：方向 B 升级为 versioned view model 和 action result；不再使用裸 integer slot index 作为跨端契约。
- **依赖**：P0 不必完全结束，但必须有 packet correlation/客户端证据规范；GUI 工作与物理修复不得混一个包。
- **范围**：保留 41 backing slots、busy read-only、armor/offhand/binding curse、server authority；方向 A 继续冻结。
- **风险**：现有 `BotInventoryService` 的 whole-stack语义、玩家 inventory merge 和 stale client snapshot 需要逐项定义；不能用“界面能开”宣称正确。
- **估时**：契约/fixture 2-3 天，窄实施 2-4 天，Windows G 矩阵 1-2 天。
- **验证**：view mapping table 纯函数测试，GameTest server action/result，Windows G1-G13 含 revision stale、task busy、armor/offhand、快速连续点击。
- **停止条件**：协议仍无法用纯函数表表达，或重启需触及普通 transfer/write semantics，则停下重新规划。

### P2：寻路 primitive 与任务编排标准化

- **目标**：保留 Alice 领域边界，以 Baritone/Mineflayer 为参考增加 explicit `MovementPrimitive`、risk/cost、replan/cancel policy；Task outcome/phase 统一。
- **依赖**：P0 确认软物理真实边界；P1 与本阶段可独立。
- **范围**：先只针对独立 probe/follow；普通 mine/collection/road/tunnel 明确继续 HARD_PATH。
- **风险**：把可破/可放路线或 `SEARCH_LIMIT` 错当 tunneling 授权；路径改善可能突破保护/掉落归属限制。
- **估时**：设计/fixture 1 周；每个 primitive 1-2 天；任务链接入仅在用户逐项批准后估算。
- **验证**：A* 成本/搜索限制/动态阻塞 fixture，Windows 基础物理与危险矩阵；没有客户端验收不扩大使用场景。
- **停止条件**：需要复制 Baritone LGPL 源码、需要 client-only context 或需要改 MineTask 边界，停止并另立架构决策。

### P3：保持的领域模块与低风险维护

- **保持自制**：TransferLedger、TransferTask、ScopeBuffer、InterfaceSnapshot、SafeZone、Road/Tunnel 产品模型、selector tools、TaskExecutionRecord。
- **优化而非替换**：ScopeBuffer active scopes 超过性能阈值时 chunk index；SavedData 增 schema migration fixture；数据生成使用现有 Forge `runData`；性能瓶颈时用 spark profile。
- **估时**：按单一维护包 0.5-3 天；不启动“全项目改写”。
- **验证**：不破坏用户已接受 transfer/C1/mainhand 功能；各修改独立 client matrix。

## 5. 现成方案的收益/成本对比

| 候选 | 完整度/社区收益 | 接入成本 | 主要不匹配/风险 | 最终建议 |
|---|---|---|---|---|
| Baritone | 寻路、break/place、cost、重规划成熟 | 高：client input/context、Forge version、LGPL、与保护/账本冲突 | 自动矿/搭路比 Alice 权限大；不能驱动 server fake player | 只借鉴，不依赖 |
| Carpet FakePlayer | 真实 fake player/action pack 经验丰富 | 极高：Fabric、不兼容 mappings/mixins | 不能直接跑 Forge；自身也有 fake-player edge cases | 源码设计参考 |
| TLM Maid | Forge 实体/库存/UI 经验，版本邻近 | 高：实体模型与 Alice 玩家语义不同 | 普通 LivingEntity 不是 ServerPlayer；领域依赖大 | GUI/实体分层参考 |
| Mineflayer | 测试与物理 simulation 思路明确 | 极高：Node、协议客户端、Prismarine 数据 | 不是 in-process Forge mod；版本/网络模型不同 | 模式参考 |
| Forge GameTest | 官方、当前工程已预留，低侵入 | 低到中 | 不覆盖客户端 | 立即纳入测试分层 |
| 通用状态机/任务库 | API 形式更丰富 | 中 | 不能理解 MC 主线程、ledger/保护/任务资源 | 不引入，内部整理 |

## 6. 总体评估

- **可完全替代模块**：0。没有一个候选同时满足 Forge 1.20.1、服务端 PlayerList bot、Alice 保护/审计/任务语义和许可/维护边界。
- **适合部分借鉴/分层使用模块**：8（FakePlayer adapter、A*、MovementHelper、soft primitive、GUI protocol、测试、日志、任务 phase/outcome）。
- **应保持自制的领域模块**：14（bot lifecycle manager、Task/Scope、矿/放/收/转移/道路、账本/持久化/快照、selectors/observability）。
- **总投入估计**：若严格执行 P0/P1/P2，约 **3-6 周工程时间 + 每个阶段 Windows 客户端验收时间**；若仅完成 P0 证据闭环，约 **1-2 周**。全量直接替换预计会超过 2-3 月且风险更高，不建议。
- **收益估计**：最大收益不是少写多少代码，而是减少“服务端 PASS 后多轮客户端失败”的返工。P0/P1 的显式证据/协议测试应先把 FakePlayer 与 GUI 的无根因试错压缩为可定位故障；无法承诺绝对 bug 数或百分比下降。

## 7. 决策冲突与需用户裁决点

1. 当前 active-plan 与 `progress-snapshot` 对 P1 物理的结论冲突：active-plan 仍写“服务端物理完全正常”，快照已明确称该结论被实测证明不准确。后续计划必须以快照为准并重新建立证据，不能把旧结论当实施前提。
2. 用户若坚持“bot 必须作为真实玩家且要完整物理”，应先批准 P0，不能因 TLM 的普通实体稳定就隐式改变 BotPlayer 产品身份。
3. 用户若希望 GUI 尽快恢复，必须接受先做协议契约/fixture而非直接再改 Screen；否则应继续 FROZEN。
4. 用户若要 Baritone“直接替代寻路”，需单独决定是否接受 LGPL、client-side control、权限模型扩大和与 ServerPlayer fake 的架构不兼容；本报告不建议默认接受。

**本报告不构成实施授权**。任何 P0-P3 工作均需监督员审核并取得用户批准的独立 active plan；现有 MineTask、DropCollectionTask、普通挖矿、道路、隧道和 SOFT_SURFACE 冻结边界不因本报告改变。
