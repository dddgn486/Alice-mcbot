# M3/M5 Packet 级诊断最小路线（只读规划）

- 诊断 ID：`20260826-m3-m5-packet-evidence-v1`
- 任务：`b81ad9b3-4794-4b8f-af7e-f8e455a93fee`
- Git 基线：`f4f6ca6890f1b1dbc166cf32afd7725b8c764897`（`f4f6ca6`）
- 对照提交：`65f4863f6407d406d9417a41939ecf023e7644a8`（`65f4863`）、`ab510fdd4cc9c4ae4f73e5661b1b2b76fa8214d6`（`ab510fd`）、`f4f6ca6890f1b1dbc166cf32afd7725b8c764897`（`f4f6ca6`）
- 证据状态：`DIAGNOSTIC_APPROVED`；M3/M5 客户端仍为 `CLIENT_TEST_PENDING`
- 性质：只读规划；不修改业务代码、`active-plan.md`、HANDOVER 或客户端验收记录。

> **本规划不构成实现授权。** 未经监督员审核、路线采纳及用户另批，不得据此派发开发任务、修改 `FakeConnection`/`BotManager` 或标记 M3/M5 客户端验收。

## 1. 已确认事实、推论与未知

### 已确认事实

1. 当前 `src/main/java/com/dddgn/alice/bot/FakeConnection.java:42-64` 仅对 `ClientboundMoveEntityPacket`（抽象基类匹配其具体位置/旋转变体）、`ClientboundSetEntityMotionPacket`、`ClientboundTeleportEntityPacket` 调用转发；其余包丢弃。`send(packet, callback)` 转发后调用 callback 成功，但 callback 不是客户端收到证明。
2. `FakeConnection.java:66-75` 遍历 PlayerList 中全部玩家，排除 `BotPlayer`，只以 `player.level() == bot.level()` 为条件发送；没有距离/追踪范围筛选，也没有 entityId 校验。相同 packet 对象会依次传给多个真实玩家连接。
3. `BotManager.spawn` 当前在 `:74-99` 创建 `BotPlayer`、经 `placeNewPlayer(new FakeConnection(..., bot), bot)` 注册、传送并清出生保护。`BotManager.onServerTick:355-385` 含 C-1 残余 delta 消费，但该日志不是 hurt/击退生成证据。
4. `65f4863` 的 `FakeConnection.send` 为空；`ab510fd` 首次增加 bot 引用、三类 packet 转发和同维度真实玩家全量广播；`f4f6ca6` 未改变 FakeConnection 的转发行为，仅增加 F1/C-1 后续代码及文档闭环。
5. `.alice-supervision/research/f1-hurt-false-fix-a-result-20260825.md:46-70` 记录 F1 generic/Zombie `hurtOk=true`、生命值 `20→19→17.5`，但 `d1/d2=(0,0,0)`，fixture 仍 `result=FAIL`；这证明伤害接受和生命值变化，不证明击退 delta 或客户端位移。
6. `.alice-supervision/progress-snapshot-20260824.md:28-39` 记录 `ab510fd` 能复现玩家约 1.5 格跳跃，回退 `65f4863` 后跳跃正常；同一回退中 bot 推挤问题仍在。因此 `ab510fd` 是 M5 跳跃异常的可信变更点，但不是因果闭环。

### 架构推论（未证实）

- M3 必须先分层：服务端 `hurtOk=true + delta=0` 属于服务端击退/物理链缺失候选；只有服务端 delta/position 非零而客户端不动，才可判为 packet 同步候选。不能用 `BOT_PHYSICS_C1_CONSUME` 单独证明 M3 击退。
- M5 的候选机制包括：全量同维度广播超出 vanilla tracking、手动广播与正常 `ServerEntity` tracking 重复、packet 具体变体/字段在错误时机到达、共享 packet 对象多 recipient 的编码风险。当前均无 runtime packet 证据。
- `ClientboundMoveEntityPacket` 需要区分 `hasPosition`/`hasRotation` 及相对 `xa/ya/za`；只记录 Java 类名不足以解释跳跃。

### 待调查问题

- M5 报告所述 bad run 的 Windows `latest.log`/`debug.log` 及确切 build SHA 是否存在；当前调查报告只确认 MP4 元数据，未解码视频。
- 同一 `(serverTick, packet entityId, recipient)` 是否重复，是否同时存在 manual FakeConnection 与正常 tracking 路径。
- 客户端实际收到并处理的 packet entityId 是否始终等于 bot entityId，是否有任何 packet entityId 等于观察玩家 entityId。
- M3 的 hurt 后 delta 是否在服务端后续 tick 才产生、被 C-1 消费、或从未产生；以及非零 server position 是否被 observer 客户端接收/渲染。

## 2. 最小诊断线路（按顺序）

### D0：建立可比基线（不加代码）

对 `65f4863`、`ab510fd`、当前 `f4f6ca6` 后代分别记录：实际 Windows 客户端/服务端构建 SHA、服务端启动时间、世界/测试场景标识、玩家 UUID/entityId、bot UUID/entityId。固定同一 fence+block+slab 场景；先做 bot 缺席跳跃基线，再做 bot 生成、无跳跃等待、跳跃、清除后的重复测试。任何无法证明 run identity 的旧日志不得替代本轮证据。

### D1：服务端 packet send/recipient 证据

只观察 bot 发送边界，记录每个 packet：`corr`、`serverTick`、source bot UUID/name/entityId、具体 class、packet entityId、recipient UUID/name/entityId、recipient dimension、source path（FakeConnection manual 或 vanilla ServerEntity tracking）、recipient distance、是否同 level、`duplicateKey=(tick,entityId,recipient)` 计数。

三类 packet 的字段最低要求：

| packet class | 必须记录字段 |
|---|---|
| `ClientboundMoveEntityPacket` | concrete subtype、`entityId`、`hasPosition`、`hasRotation`、相对 `xa/ya/za`（解码后的值）、yaw/pitch、`onGround`（若该变体可见） |
| `ClientboundSetEntityMotionPacket` | `id`、编码/解码后的 `xa/ya/za`，source bot id，tick，recipient |
| `ClientboundTeleportEntityPacket` | `id`、绝对 `x/y/z`、yaw/pitch、`onGround`，source bot id，tick，recipient |

最小判据：同一 recipient 在同一 tick 对同一 entityId 的 manual/tracking 总数；manual packet entityId 必须等于 source bot entityId；必须能区分“发送给连接”与“客户端收到”。

### D2：客户端接收/渲染证据

Windows 端保留与 D1 同一 run 的 `latest.log`、`debug.log`、视频/截图及 build SHA。若已有客户端 packet receive hook 可用，记录客户端 tick、packet class/entityId/字段、接收连接/来源标识和处理结果；若没有，须另行批准最小客户端诊断 hook。客户端渲染证据只记录 bot 实际可见位置/动画及观察玩家位置，不把服务端发送或 callback 成功当成接收。

### D3：M3 双端受控攻击

在 F1 初始化完成后，固定 observer 与 bot 距离和攻击源，单次攻击并给每次操作 `corr`。服务端记录攻击前后至少 N 个 tick：`hurtOk`、source/type、health、`getDeltaMovement`（x/y/z）、position（x/y/z）、`onGround`、collision flags、C-1 是否触发及 displacement。客户端同时记录 observer 收到的 bot packet class/entityId/字段/ tick、渲染前后 bot 位置、击退动画是否出现。

先判服务端：`hurtOk=true` 且 health 降低但 delta/position 始终为零 → 保留服务端物理冲突，停止把 M3 当成纯同步问题；server delta/position 非零而客户端无对应接收/渲染 → 才进入同步分类；两端均有有效证据但视觉仍错 → 标为未解释，不扩大修复范围。

### D4：M5 生命周期与运动对照

对 bot absent / bot present / bot cleared 三状态，在每次跳跃前后记录观察玩家：`serverTick/clientTick`、position x/y/z、velocity/delta x/y/z、`onGround`、jump input/动作标识；同时记录 bot spawn tick、spawn position/entityId/UUID、clear/remove tick、clear 前后 bot position/velocity/onGround，以及 D1/D2 packet 计数。至少重复 good=`65f4863`、bad=`ab510fd`、current=`f4f6ca6` 后代各一轮；若一轮中 baseline 已异常，先停止，不解释广播机制。

## 3. 允许的最小观测注入点（仅规划，不实施）

当前 addendum 禁止修改 `FakeConnection.java` 与 `BotManager.java`。因此本报告只列出后续若经监督员重新授权时的最小候选，不授权当前编辑：

1. **服务端发送边界候选**：`src/main/java/com/dddgn/alice/bot/FakeConnection.java:42-64` 的两个 `send` 重载，以及 `:66-75` 的 recipient loop。只增加诊断输出/旁路 observer，不改变 packet 类型筛选、recipient 集合、packet 对象、回调和发送顺序。字段必须覆盖 D1 全部字段，含 `manual=true` 和共享对象标识（仅诊断 correlation，不改变对象）。
2. **vanilla tracking 对照候选**：不改 Alice 业务路径；若 Forge/映射允许，应在现有 server-side packet tracking 观察器或独立 test harness 记录 `ServerEntity` 发送路径，标 `manual=false`。不得为实现便利改写 tracking recipient。
3. **服务端 M3 状态候选**：`BotPhysicsAssertionFixture`/`BotManager` 当前均在 addendum 禁止修改；若需要观测，应建立独立诊断 harness/既有日志 seam，采集 D3 字段，不改变 hurt、tick、C-1 或 fixture 判定。任何触及 fixture 的新增断言必须由监督员另行批准。
4. **客户端接收候选**：Windows 客户端现有可用诊断入口优先；否则另建独立客户端诊断构建/接收 hook，不能把服务端 FakeConnection 日志冒充客户端接收。具体客户端文件与行号须在实现前由监督员根据 Windows checkout 固定，当前仓库没有可确认的 client receive 文件，不能臆造路径。

上述候选均是观测注入点，不是修复注入点；不得在本诊断包中修改 `FakeConnection`、`BotManager`、`BotPlayer`、fixture、5 个 SOFT travel 调用点或客户端验收记录。

## 4. M3 双端验收矩阵（诊断证据，不等同 USER_ACCEPTED）

| 场景 | server 必须记录 | client 必须记录 | 解释/停止条件 |
|---|---|---|---|
| M3-S0 初始化 | build SHA、corr、bot/player ids、F1 timer 已清零 | observer build/world identity | identity 不完整则停止 |
| M3-S1 单次 generic 攻击 | hurtOk、health 前后、delta 前后、position N tick、velocity、onGround、source | 收到 packet class/id/fields/tick，bot render position/动画 | `hurtOk=true+health↓+delta=0` 保留 server conflict |
| M3-S2 单次 Zombie 攻击 | 同 S1，区分 source 与伤害量 | 同 S1 | generic/Zombie 不得合并计数 |
| M3-S3 packet/状态关联 | packet entityId=bot id；manual/tracking 各自计数 | client receive id 与 bot id 一致 | id 不匹配或重复未解释，立即停止扩大 |
| M3-S4 C-1 对照 | C-1 residual/displacement/collision flags | bot visible displacement | C-1 日志单独不能判击退 PASS |
| M3-S5 回收 | clear tick、bot state、无残留任务 | bot 消失/客户端状态回收 | crash、悬挂实体、世界变更则停止 |

最终分类只能是：`SERVER_PHYSICS_CONFLICT`、`SYNC_CONFLICT`、`EVIDENCE_CONFLICT` 或 `UNRESOLVED`。任何分类都不自动产生 `USER_ACCEPTED`。

## 5. M5 双端/生命周期矩阵

| 场景 | build | bot 状态 | 玩家证据 | bot 证据 | packet 判据 |
|---|---|---|---|---|---|
| M5-A 基线 | good/bad/current | absent | 跳跃前后 position/velocity/onGround | 无 bot spawn/packet | baseline 异常则停止 |
| M5-B 生成后静置 | good/bad/current | spawned | 玩家状态时间线 | spawn tick、UUID/entityId、position/velocity/onGround | 不应凭视频推断因果 |
| M5-C 固定障碍跳跃 | good/bad/current | present | 每次 jump 的 position/velocity/onGround、视频 | bot 状态与 packet counts | packet entityId 必须等于 bot id；重复/越界须分类 |
| M5-D 清除后复跳 | good/bad/current | cleared | clear 前后玩家状态 | clear tick、bot final state | 症状消失仅为相关性，不能单独闭合因果 |
| M5-E 双 recipient（如可安全复现） | bad/current 优先 | present | 两个真实玩家各自收到/渲染 | recipient UUID/entityId/distance | 检查共享 packet 与 recipient 差异；不得改发送语义 |

M5 的 minimum PASS 不是“跳跃看起来正常”，而是：三基线 identity 完整、bot absent baseline 正常、bad run 的 packet recipient/entityId/tick/字段可重建、且客户端接收与渲染证据能和服务端发送一一对应。若 packet 证据缺失，结论只能是“机制未闭合”。

## 6. Good/Bad/Current 对照要求

- **Good `65f4863`**：FakeConnection 完全丢弃这些 S2C 包；项目记录称跳跃正常，但 bot 物理问题仍存在。它是 M5 跳跃对照，不是 M3 正常基线。
- **Bad `ab510fd`**：首次把三类 packet 从 fake connection 广播给同维度所有真实玩家；项目记录称可复现约 1.5 格跳跃。需验证是 manual/tracking 重复、超范围 recipient、字段/ID错误还是其他时序问题。
- **Current `f4f6ca6`**：FakeConnection 行为仍为 `ab510fd`；后续仅有 F1 出生保护清除与 C-1 任务层残余消费。F1 让 hurt 成功/health 下降，但当前 headless 仍见 `d1/d2=0`；不得将 current 的 F1/C-1 变化归因到 M5。
- 每个 Windows 构建必须明确 server/client SHA；不能仅以 Git 提交相邻或文件时间判断对照一致。

## 7. 停止条件、回滚与不混修边界

### 停止条件

1. 缺少 build SHA、run 时间、world/scene identity，或日志无法证明属于本轮 → 停止，标 `INSUFFICIENT_EVIDENCE`。
2. bot absent 的 good/bad/current 基线跳跃已异常 → 停止 M5 机制分析。
3. 发现 packet entityId 等于观察玩家 entityId、recipient 错误、重复计数无法解释、或 client decode 与 server fields 不一致 → 停止，不尝试在线修复。
4. M3 `hurtOk=true`/health↓ 但服务端 delta/position=0 → 停止同步-only 路线，转监督员处理独立 server-physics 证据。
5. 服务端有非零位移而客户端无接收/渲染对应 → 停止扩大范围，仅报告 sync conflict。
6. 诊断造成 crash、世界修改、bot 悬挂/重复生成、玩家位置异常扩大，或观察器改变 packet 时序 → 立即停止并回收诊断构建。
7. 证据互相冲突或无法区分 manual/tracking → 保持 `UNRESOLVED/EVIDENCE_CONFLICT`，不得用推论填空。

### 回滚

- 诊断观察器必须独立、可禁用；回滚目标按对应测试构建恢复到原始 `65f4863`、`ab510fd` 或当前 `f4f6ca6`，不得手工复制业务文件。
- 任何未来观测提交若越界或改变行为，应整提交回滚/取消，不 cherry-pick 部分业务逻辑；保留原始日志、SHA、diff 和报告以便审计。
- 本报告阶段不执行回滚、不改代码、不改客户端记录。

### 不混修边界

- M3 服务端 hurt/knockback、M3 客户端同步与 M5 跳跃/广播是三条证据子链；不得因同属“物理”合并修复。
- 不修改 `FakeConnection`、`BotManager`、`BotPlayer`、`BotPhysicsAssertionFixture`、`active-plan.md`、客户端验收记录。
- 不触碰 MineTask/DropCollectionTask/HARD_PATH、5 个 SOFT travel 调用点、P0 门禁；不以 `SEARCH_LIMIT`、回退、传送或世界编辑制造 PASS。
- packet 诊断完成也不等同修复授权；任何 M3/M5 修复须有新工作包、监督员审核和用户另批。

## 8. 监督员决策门

完成 D0-D4 后，监督员仅从以下结果选路：

1. `SERVER_PHYSICS_CONFLICT`：另立最小 M3 服务端物理证据/修复包，仍不混入 M5。
2. `SYNC_CONFLICT`：另立 FakeConnection/追踪同步修复包，用户另批；先明确 recipient/entityId/duplicate 事实。
3. `M5_CORRELATION_ONLY`：packet 证据不足，保留问题，禁止修复猜测。
4. `EVIDENCE_CONFLICT/UNRESOLVED`：停止扩展，补充指定缺口或请求用户决策。

监督员审核本报告前，不得将其转换为 `APPROVED_FOR_IMPLEMENTATION`，不得派发开发任务。

**本规划不构成实现授权。**
