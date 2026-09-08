# P0 可证伪调试闭环最小实施路线

- 规划角色：Alice 实现规划员+
- 日期：2026-08-24
- 暂停基线：`ab510fd`（以 `.alice-supervision/progress-snapshot-20260824.md:122-129` 为准）
- 工作状态：P0 已获用户批准，但尚未写入 active plan，尚未获实施授权；当前全面重构调查与 P1 跳跃/推挤异常处于暂停。
- 依据：`.alice-supervision/research/p0-debug-evidence-closure-task.txt`、`.alice-supervision/research/dev-tools-mcp-survey-20260824.md`、`build.gradle`、`docs/HANDOVER.md`、当前 Bot/FakeConnection/fixture 源码、三份待 DSH 维护员审核的 Skill 初稿。

> **本规划不构成实施授权。** P0 只建立服务端可重复事实、最小结构化关联证据和开发期静态工具验证；不修 P1 跳跃异常或 bot 推挤，不修改 BotPlayer/FakeConnection/P1/HARD_PATH/MineTask/GUI 产品行为，也不把 GameTest/MCP/日志当作 Windows 客户端验收的替代品。

## 1. P0 目标与非目标

### 1.1 目标

针对 `ab510fd` 后两个已证实但尚未解决的问题，建立每次复现都能回答三件事的最小闭环：

1. **服务端状态发生了什么？** BotPlayer 是否被推进/受伤，AABB、`isPushable`、`canCollideWith`、`deltaMovement`、health、`hasImpulse` 是否按预期变化。
2. **目标 recipient 是谁，收到了什么允许 packet？** 不再仅以 `bot.connection.send()` 是否调用代替观察者收包事实。
3. **Windows 客户端实际看见什么？** 与相同 correlation id 的日志、截图/短视频、测试报告关联，保持“服务器正确 != 客户端正确”的明确证据界限。

### 1.2 非目标（不允许扩展）

- 不修复玩家跳跃异常（`ab510fd` 相关 FakeConnection 广播）或 bot 推挤/击退失效。
- 不改 `BotPlayer.java`、`FakeConnection.java`、P1 软移动、`PathExecutor`、HARD_PATH、`MineTask`、`DropCollectionTask`、道路、隧道、流体、逃生。
- 不启用运行时 `langyo/minecraft-mod-mcp` bridge、spark、Mixin、overlay，且不向 mod/jar 加第三方 runtime dependency。
- 不重启/重构 GUI 产品；仅增加与当前 frozen GUI 无耦合的纯函数 contract fixture。
- 不将 `BotSelftest` 扩展为 GameTest，不将它的 headless PASS 写作物理或客户端 PASS。

## 2. 分阶段顺序与依赖

### 2.1 DAG

```text
D0 监督员确认 P0 审核结论、确定最小观测插点边界
 |
 +-- P0-A1 纯函数 GUI view-index/revision fixture -------------+
 |                                                               |
 +-- P0-A2 Forge GameTest: Bot physics facts -------------------+--> P0-V1 compileJava
 |                                                               |       + target gameTestServer
 +-- P0-A3 Forge GameTest: recipient packet facts --------------+       + existing focused suites
 |                                                               |
 +-- P0-C1 结构化事件 schema + dev-only sink/packet observer ---+--> P0-V2 Windows M1-M4 evidence
 |                                                                       + supervisor review
 +-- P0-B1 MCP 本地安装/权限/缓存验证（独立于所有代码） --------+
                                                                          --> P0 closure decision
```

### 2.2 可并行项

- **P0-B1 MCP 静态安装验证**可以与 P0-A fixture 设计/实现并行：它是 DSH/IDE 外部开发机工具，不进入 Alice Gradle dependency、mod runtime 或发布 jar。
- **P0-A1 GUI 纯 contract fixture**可以与 Bot physics/packet GameTest 并行：它不启动 Screen、网络或 `AbstractContainerMenu`，只验证未来 GUI 重启的纯映射与 revision/fingerprint 拒绝函数。
- **P0-A2 physics GameTest** 与 **P0-A3 recipient packet GameTest** 可并行开发，但在 P0-V1 中合并运行。

### 2.3 必须先做

1. **先锁定 P0-C 的 observer 插点**：目前真实 recipient fan-out 在 `FakeConnection.broadcastToRealPlayers(Packet<?>)`（`FakeConnection.java:66-75`）；P0 禁止修改 FakeConnection。因此实施前监督员必须选定：
   - C1a：允许在**独立、dev-only 观察辅助类**以不改变发送分支/recipient 集合的方式调用 observer（仍需精确确认可从何处调用）；或
   - C1b：缩小 P0-C 为 GameTest 的 recipient capture + 既有日志关联，推迟生产 packet correlation 到后续单独计划。
2. **先写纯 helper/fixture，再写 GameTest world fixture**。GameTest 不得通过伪造 PlayerList 生命周期、反射重设 connection、或直接调用生产私有方法来取得想要的 PASS。
3. **任何开发期日志开关默认 off、白名单默认空或最窄**，再做 Windows M1-M4；不允许先常开全量 packet 日志。

### 2.4 推荐分包

为可回滚性，建议拆为三个独立实现包，避免一包混入游戏行为、网络、开发机配置：

| 包 | 允许范围 | 完成证据 | 与其他包依赖 |
|---|---|---|---|
| P0-A `gametest-contracts` | 仅 `src/gametest`/测试辅助/纯 GUI contract | GameTest 三类 fixture + compile | 无 MCP 依赖；先于客户端 evidence |
| P0-C `dev-evidence-logging` | 单独 dev-only observer/schema + 开发文档 | 限额/白名单/unit fixture + Windows correlation | 需先定 C1a/C1b；不改发送行为 |
| P0-B `static-mcp-tooling` | DSH/IDE 配置和开发文档，不写 mod/Gradle | 安装、cache read、静态查询、卸载记录 | 可完全并行 |

## 3. 准确改动边界

### 3.1 候选新增文件

| 候选文件/位置 | 职责 | 允许/禁止 |
|---|---|---|
| `src/gametest/java/com/dddgn/alice/gametest/BotPhysicsGameTests.java` | `@GameTest` 物理事实 fixture | 允许；不改 BotPlayer 行为 |
| `src/gametest/java/com/dddgn/alice/gametest/FakeConnectionRecipientGameTests.java` | recipient/packet allow-list fact fixture | 允许；不能要求生产 FakeConnection 产生不同 packet |
| `src/test/java` 或 `src/main/java/.../gui/BotInventoryViewContract.java` | 纯 `viewIndex -> logical region -> backing slot`、revision/fingerprint stale rejection | 允许新增纯 value object/函数；不改 frozen GUI Screen/service/product packet |
| `src/test/java/.../BotInventoryViewContractTest.java` 或 GameTest 同包 | table-driven mapping/stale rejection fixture | 允许；优先 JUnit/纯函数，不需要 Minecraft world |
| `src/main/java/com/dddgn/alice/debug/DevEvidenceLog.java` | 仅 dev profile 的 schema、白名单、限额、redaction | 仅在监督员明确批准 C1a；默认 off，不触及任务/包行为 |
| `docs/development/p0-debug-evidence.md` | GameTest、日志、MCP、Windows correlation 操作手册 | 允许；不得写成产品功能或用户验收替代 |
| `.alice-supervision/...` | active plan、研究决策、Windows matrix/report 模板 | 仅监督员按流程写；规划员不改 |

### 3.2 可修改的既有配置（仅批准后）

- `build.gradle`：现有 `gameTestServer` 已配置 `forge.enabledGameTestNamespaces=mod_id`（`build.gradle:100-105`）。**首选零改动**；只有 ForgeGradle 确认证明 source set 未被发现时，才新增最小 gameTest source-set/config，且不得改变 `runClient/runServer`、依赖、MCP、运行时 classpath。
- `AliceMod.java`：只在 Forge GameTest discovery 确实需要注册时才加最窄测试 namespace 绑定；否则不改。
- `BotLog.java`：可作为 schema sink 的唯一现有日志门面（`BotLog.java:19-25`），但不能把全量 packet 或 player text 写进 logs。

### 3.3 明确不改

`BotPlayer.java`、`FakeConnection.java`、`BotManager.java` 的 spawn/recipient/任务语义、`SoftPathProbeTask`、`FollowTask`、`PathExecutor`、`BotMiner`、`MineTask`、`DropCollectionTask`、`AliceNetwork` packet 协议、`BotInventoryScreen`、`BotInventoryService`、`BotInventoryPacket`/`BotInventoryActionPacket` 的产品行为。

**现有关键事实**：`FakeConnection.send` 在 `ab510fd` 只对白名单三种 movement/motion packet（`ClientboundMoveEntityPacket`、`ClientboundSetEntityMotionPacket`、`ClientboundTeleportEntityPacket`）调用 `broadcastToRealPlayers`，后者给同 level、非 BotPlayer 的 `player.connection.send(packet)`（`FakeConnection.java:42-75`）。这是 P0 要测量的事实，不是本包修复点。

## 4. Forge GameTest fixture 最小设计

### 4.1 接入方式与可信界限

- `build.gradle` 已有 ForgeGradle 6/Parchment、`gameTestServer` run（`build.gradle:1-6,34,100-105`）；目标命令为 `./gradlew runGameTestServer`，以 `forge.enabledGameTestNamespaces=alice` 发现 fixture。
- `BotSelftest` 是 `ServerStartedEvent` 驱动的 headless smoke（`BotSelftest.java:28-38,141-149`），继续保留其定位；不把 P0 GameTest 塞入 auto selftest，也不把 selftest PASS 当 GameTest PASS。
- GameTest 仅证明：同一服务端 JVM 的 world/entity/packet observation 状态。它不证明 Windows 客户端渲染、预测、延迟、真实输入、可见撞击或 GUI 操作。

### 4.2 GT-1：BotPlayer/FakePlayer physics facts

**fixture**：`BotPhysicsGameTests#botPhysicsFacts(GameTestHelper helper)`。

**最小场景**：GameTest structure 中一块平坦固体地面；以公开 `BotManager.spawn` 正常注册一个 `BotPlayer`（或测试前确认 GameTest Server 支持该生产入口），另生成可控碰撞对象（优先原版 `ServerPlayer` 测试 helper；若 API 不可用则普通可推挤 `LivingEntity`，并在报告降级为“实体碰撞事实”，不声称“真实玩家”）。

**断言**：
- bot 和 collider 的 `AABB` 非空、初始不交叠、`bot.isPushable()`、`bot.canCollideWith(other)` 的值；记录而非预设“必为 true”。
- 调用已批准的原版 collision/hurt 路径前/后：`bot.getDeltaMovement()`、health、`hasImpulse`、位置、onGround。至少一项变化或明确“无变化”的可复现事实，断言差异与实际调用路径一致。
- fixture 结束时 `BotManager.remove(bot)`，不保存到正常世界、无 persistent BotWorldData 残留。

**可信边界**：不验证客户端是否显示位移、击退动画或玩家跳跃；若原版 GameTest 无法构造真实 `ServerPlayer`，不能把 dummy entity 结果外推为真人客户端碰撞。

**禁止**：不 override `isPushable`/`canCollideWith`，不设置 `noPhysics`，不反射注入 BotPlayer/FakeConnection，不用 `setPos` 人为制造结果。

### 4.3 GT-2：FakeConnection recipient packet facts

**fixture**：`FakeConnectionRecipientGameTests#movementPacketRecipientFacts(GameTestHelper helper)`。

**目的**：检验“bot 自己 connection 不等于真实观察者 recipient”，而非验证 Windows 实际收包。

**最小实现路线（按优先级）**：
1. 提取**纯 package-private recipient predicate/target enumeration helper**（输入：bot、server player list；输出：同 level 非 bot 的候选 UUID）。必须不改 `FakeConnection.send` 分支、顺序或实际 `player.connection.send` 调用；GameTest 对该 helper 建 table fixture。
2. 若无法在不碰 `FakeConnection` 的条件下暴露 real fan-out，创建 **test-only packet sink/recipient recorder**，在 GameTest 使用相同输入集合、相同 predicate 记录“应投递 recipient”，并明确标注为“routing policy contract”，不声称已拦截真实 `Connection.send`。
3. 只有监督员将 C1a 明确纳入 active plan 时，添加 dev-only observer hook，记录真实 fan-out 前的 `(packet class, recipient UUID, tick)`；它不得改变 packet、recipient、时序，默认 disabled。

**断言**：
- bot 自己 UUID 永不在 recipient；不同 level 的 server player 不在 recipient；同 level真实观察者恰好一次。
- allow-list input 三类 packet的 simple name 与 direction=`S2C` 被记录；不在白名单的 packet（例如 chat/container/unknown dummy）记录为 `ignored_not_whitelisted`，绝不转储 payload。
- `FakeConnection.send(packet, callback)` callback exactly once 的现有事实可通过 mock listener/计数断言；不由 fixture修改 dispatch。

**可信边界**：GameTest 不含真实 Windows Netty client，不能证明 packet 抵达、顺序、客户端接受/渲染；它只证明 recipient filter、observer 白名单和服务端 dispatch 调用证据。

### 4.4 GT-3：GUI view-index pure contract

**fixture**：优先普通 JUnit/pure fixture `BotInventoryViewContractTest`；如果项目暂未有 JUnit source set，做无 world 的 GameTest/自测 invocation，但不得启动 Screen 或 container packet。

**新纯 contract（未来 GUI restart 前置，不改变 frozen 产品）**：

```java
record ViewSlot(int viewIndex, Region region, int logicalSlot, int inventorySlot) {}
enum Region { HOTBAR, MAIN, ARMOR, OFFHAND }
record SnapshotStamp(long revision, String fingerprint) {}
enum ActionResult { OK, SLOT_EMPTY, STALE_REVISION, BUSY, INVALID_SLOT }
```

**最小映射表（沿用当前 snapshot index 语义，不做产品变更）**：
- view 0..8 -> `HOTBAR`, logical 0..8, backing ordinary 0..8;
- view 9..35 -> `MAIN`, logical 9..35, backing ordinary 9..35;
- view 36..39 -> `ARMOR`, logical 0..3, backing 36..39;
- view 40 -> `OFFHAND`, logical 0, backing 40;
- 其他 -> invalid。

**断言**：
- 41 个 view index 一一映射；无重复 backing slot；invalid index (-1/41) 稳定 `INVALID_SLOT`。
- revision 不等、或同 revision fingerprint 不等 -> `STALE_REVISION`；两者相等才允许动作进入下一层；空 slot/BUSY 的结果仍为显式 `ActionResult`。
- 只测试 mapping/revision/fingerprint 函数，不读 `Inventory`、不调用 `BotInventoryService.applyAction`、不触发 packet/Screen。

**可信边界**：这是未来解除 GUI 冻结的设计契约和低成本回归，不表示 GUI 已恢复、packet 已实现 revision，或客户端操作被验收。

## 5. 结构化双端诊断日志与 correlation

### 5.1 事件 schema（键值扁平格式）

所有 P0 新日志以稳定前缀 `evidence_v1` 输出；使用 `BotLog.info/warn`，便于 `latest.log` 与 `debug.log` 过滤。字段只输出存在值，禁止伪造值。

| 字段 | 必填 | 说明 |
|---|---:|---|
| `event` | 是 | 见 §5.2 事件名 |
| `corr` | 是 | 一次人工/fixture复现的 UUID 或短 ID；服务端创建，Windows 报告引用 |
| `side` | 是 | `server` 或 `client_evidence`（后者仅写在 Windows markdown，不由 mod client 伪造） |
| `tick` | server 是 | server tick；纯 fixture 无 server tick 标 `tick=na` |
| `bot_uuid`、`bot_entity_id` | physics/packet 是 | bot 身份；不写玩家显示名 |
| `task_kind`、`task_id` | 若有任务 | 当前 task 类型/稳定 task record id；无则 `none` |
| `pos`、`vel` | physics 是 | 格式化坐标/velocity，最多三位小数 |
| `on_ground`、`health`、`has_impulse` | physics 是 | 原始服务端状态 |
| `packet`、`direction`、`recipient_uuid` | packet 是 | 仅 simple class name、`S2C/C2S`、recipient UUID |
| `packet_seq` | packet 是 | 每 corr 单调编号，不能用于推断网络到达顺序 |
| `view_index`、`logical_slot`、`inventory_slot`、`revision`、`fingerprint_hash` | GUI contract 是 | 仅纯 contract 的摘要，不写 item/NBT |
| `result_code` | 可选 | 稳定 FAIL/ActionResult 码 |
| `sampled`、`dropped_count` | log-control 是 | 观测被采样/限额时的显式统计 |

### 5.2 事件名

- `evidence_v1.physics.before_push`
- `evidence_v1.physics.after_push`
- `evidence_v1.physics.before_hurt`
- `evidence_v1.physics.after_hurt`
- `evidence_v1.packet.recipient_candidate`
- `evidence_v1.packet.sent_observed`（仅 C1a 真正 observer hook 可用；否则不得伪造）
- `evidence_v1.packet.ignored_not_whitelisted`
- `evidence_v1.gui_contract.mapping`
- `evidence_v1.gui_contract.stale_rejected`
- `evidence_v1.log_limit_reached`

### 5.3 packet 类型白名单

默认只允许记录 simple name，且严格白名单：

```text
ClientboundMoveEntityPacket
ClientboundSetEntityMotionPacket
ClientboundTeleportEntityPacket
ClientboundSetEquipmentPacket
ClientboundSetCarriedItemPacket
ClientboundContainerSetSlotPacket
BotInventoryPacket
BotInventoryActionPacket
```

- P0 初始 physics incident 只开启前三种；equipment/container/GUI 名称保留为未来相同 schema 的白名单项，默认不开。
- 任何未列入类型：仅一次 `ignored_not_whitelisted packet=<simpleName>`，不记录 packet bytes、payload、NBT、item stack、chat/text/component。
- P0 不加入 generic Netty sniffer 或全通道 logging。

### 5.4 采样、限额、隐私

- 默认 `enabled=false`，仅 dev run 显式 JVM property 开启，例如 `-Dalice.devEvidence=true`；不得在生产服务默认打开。
- 每 `(corr, event, packet simple name)` 上限 20 条；每 corr 上限 200 条；达到限额记录单一 `log_limit_reached dropped_count=N`。
- physics tick log：状态变化立即记录，否则最多每 10 tick 一条；packet 同类型每 tick最多一条 recipient 摘要。
- 禁止记录：玩家 chat/命令文本、profile name/IP/session token、完整 NBT、完整 inventory、packet bytes、Component 内容、绝对 Windows 用户目录、MCP 配置秘密。
- `fingerprint_hash` 只能是稳定不可逆摘要（例如 item registry id + count + component hash 的 SHA-256 截断），不写全 item/NBT。

### 5.5 correlation 规则与 Windows 关联

1. 每次 test scenario 启动时生成 `corr=<UUID>`，通过 server log + chat 只展示短前缀；玩家在 Windows evidence report 中填完整 corr 或 timestamp+短前缀。
2. Server events 使用同一 corr、递增 `packet_seq`、server tick；不声明这些字段代表客户端到达顺序。
3. Windows 报告每个 M# 场景必须包含：commit、corr、起止本地时间、latest/debug.log 行号、截图/10-15 秒视频文件名、可见结果（PASS/FAIL）。
4. 关联规则：同 corr + packet_seq/tick 与 Windows 时间窗口一致，只能证明“服务器产生状态/选择 recipient 与客户端观察在同一复现”，不能单独证明 receiver 解码成功。若二者矛盾，标为 `EVIDENCE_CONFLICT` 并停止修复推断。

## 6. `adhi-jp/minecraft-modding-mcp` 开发期静态验证

### 6.1 定位与固定证据

- 工具：`adhi-jp/minecraft-modding-mcp`，MIT，固定检查点 `2c673ad75f83dd7e5a667a3bfa1481489b56bfe6`（调查报告 `dev-tools-mcp-survey-20260824.md:42-63`）。
- 仅作为 **DSH/IDE 外部开发机 MCP tool**：静态反编译、mapping、jar/符号/Mixin/AT 校验。P0 不使用 Mixin/AT，只可查询其静态符号。
- 不进入 `build.gradle`、`mods.toml`、`src/main` runtime dependency、run classpath、发布 jar；不得启动/加载 `langyo/minecraft-mod-mcp` runtime bridge。

### 6.2 推荐隔离安装路径

- 工具源码/venv/npm install：`~/.local/share/alice-devtools/minecraft-modding-mcp/`（用户目录、Alice repo 外）。
- 工具配置：`~/.config/alice-devtools/minecraft-modding-mcp/config.json`（权限 0600；不提交、不放项目内）。
- 临时 logs/cache：`~/.cache/alice-devtools/minecraft-modding-mcp/`（可删除）。
- Alice 工作区仅读路径：`/home/fb486/projects/alice`。
- Gradle/Forge/Parchment cache：以实际环境 `GRADLE_USER_HOME` 为准（未设置时通常 `~/.gradle`）；允许 read-only 访问其 ForgeGradle userdev、Minecraft/Forge、Parchment mapping artifacts。禁止写/清理 Gradle cache，禁止访问 `~/.ssh`、token/keyring、浏览器 profile、Windows user profile 或工作区外无关目录。

### 6.3 localhost/no-secret 原则

- 若 MCP transport 需要 server：绑定 `127.0.0.1` 或使用 stdio；不得 `0.0.0.0`、公网 tunnel、远程 endpoint。
- 不配置 API key、session token、Git credential、Mojang/Microsoft login；如果安装程序要求上传 workspace/cache/secret，停止。
- 启动时必须打印实际监听地址、进程 PID、可读根目录；文档记录版本/commit/checksum、启动命令和权限。

### 6.4 验证步骤

1. 在 repo 外固定 commit 安装；记录 package manager、lockfile/commit、校验和、PATH/command。
2. 配置 read-only Alice repo + `GRADLE_USER_HOME` 后，执行**只读静态查询**：
   - Forge 1.20.1 `ServerPlayer`/`LivingEntity`：`push`、`hurt`、`knockback`、`travel`、`getDeltaMovement`、`hasImpulse` 符号；
   - `ClientboundMoveEntityPacket`/`ClientboundSetEntityMotionPacket`/`ClientboundTeleportEntityPacket` 符号；
   - Parchment mapping 来源/版本，确认实际 cache 可读；
   - Alice `FakeConnection` 与 `BotPlayer` 引用图（只读）。
3. 记录查询输入、固定版本、结果摘要、退出码；不把工具输出直接当作客户端行为证据。
4. 验证不会改 `git status`、`build.gradle`、Gradle cache metadata；关闭服务并复查监听端口。

### 6.5 卸载/回滚

1. 停止 MCP process，确认 localhost/stdio 连接结束。
2. 删除 `~/.local/share/alice-devtools/minecraft-modding-mcp`、`~/.cache/alice-devtools/minecraft-modding-mcp` 和 MCP client registration；保留审计记录但不保留 secrets。
3. 删除 `~/.config/alice-devtools/minecraft-modding-mcp/config.json`；检查 `git status --short` 仍未变。
4. 不删除/清理 `~/.gradle`，除非用户单独授权。

## 7. 验证矩阵与 P0 完成标准

### 7.1 最小服务端验证

| ID | 检查 | PASS 的准确含义 |
|---|---|---|
| S1 | `./gradlew compileJava` | P0 代码/测试辅助编译；非客户端验收 |
| S2 | `./gradlew runGameTestServer`（仅 `alice` target fixtures） | GT-1/GT-2/GT-3 服务端断言均 pass；不证明客户端物理/GUI |
| S3 | 既有 focused suites / `BotSelftest` 显式执行 | P0 未回归已有 server fixture；不宣称 broad smoke 若超时 |
| S4 | dev evidence unit/fixture | 白名单、redaction、每 corr 限额、correlation 单调性均 pass |
| S5 | MCP static validation | 工具在 repo 外安装，实际 Parchment/Forge cache 可 read，查询/卸载可审计，且 Alice git/Gradle/runtime 无变更 |

### 7.2 Windows 最小证据矩阵

| ID | 场景 | 用户操作 | 必需证据 | PASS 标准 |
|---|---|---|---|---|
| M1 | 基线 bot 可见/空闲 | Spawn bot，观察正常站立 | corr、截图、latest.log task/bot state | 记录可复现基线，不把“可见”写为 physics PASS |
| M2 | 推挤 | 玩家持续接触 bot | 10-15 秒视频、corr server physics+packet events、bot/player最终坐标 | 视频与服务端状态可关联；若可见行为和服务端证据矛盾，报告 `EVIDENCE_CONFLICT`，非 PASS |
| M3 | 击退 | 玩家攻击 bot 一次 | 视频、before/after health/delta/hasImpulse、recipient event摘要、日志行号 | 同上；可证伪同步/物理假设，而非自动宣称修复 |
| M4 | 玩家跳跃回归 | 复现 `ab510fd` 的栅栏+方块+下半砖 1.5 格场景 | before/after 短视频、corr packet recipient/tick、版本 SHA | 明确记录异常是否复现；P0 不修复，证据足够即可 |
| M5 | 无观测开关回归 | 禁用 dev evidence 重跑 M2/M3 的最小版本 | 视频/关键日志、enabled=false 确认 | observer 不改变行为的对照；若有差异立即停止 P0-C |

### 7.3 P0 可说“完成”的条件

只有同时满足以下条件才可写“P0 evidence closure complete”，且必须仍标注客户端行为问题未解决：

1. P0-A 三类 fixture（physics、recipient、GUI pure contract）有明确 PASS/known limitation、可重复命令和日志；
2. P0-C（若获 C1a批准）schema/white-list/rate-limit/redaction/correlation fixture均通过，且 M5 证明观测关/开不改变最小复现结论；若选 C1b，报告明确“生产 recipient observer 未实现，P0 only server contract closure”；
3. P0-B MCP 实际映射/cache静态读取与卸载均审计通过，且其不在 Alice runtime/jar；
4. Windows M1-M5 evidence 报告完整，每个异常都有 corr/视频或截图/日志定位；
5. 不把 GameTest、MCP 或 server log 标记为 `USER_ACCEPTED`，不把 P0 误报为 P1 物理/跳跃/GUI 问题修复。

## 8. 主要风险与停止条件

| 停止条件 | 触发事实 | 动作 |
|---|---|---|
| GameTest 需绕过生产生命周期 | 只有通过反射/手造 connection/跳过 PlayerList 才能生成 BotPlayer/观察者 | 停止 GT-1/GT-2；缩回为纯 predicate/state fixture，向监督员报告可信边界 |
| GameTest 写入正常 BotWorldData/污染 world | `BotManager.spawn` 在 GameTest 留下持久化数据，无法清理或影响下一测试 | 停止，设计 test world cleanup 或不运行 BotManager lifecycle fixture |
| 日志需要修改 FakeConnection/发送时序 | 无法记录真实 recipient 而不改 `FakeConnection.send/broadcastToRealPlayers` | 停止 P0-C 真 observer；选择 C1b或另立受控 instrumentation plan，不能暗改 FakeConnection |
| 日志影响性能/时序 | 开关开启后 M5 与关闭对照的位移、跳跃、packet、tick 行为不同，或 tick budget超限 | 立即关 observer，保留简化 server fixture；不得继续把日志留在默认路径 |
| 白名单/限额无法保证 | 发现会记录 chat、NBT、inventory、credentials 或无界 packet量 | 停止，先完成 redaction/limit 纯单测再恢复 |
| MCP 权限/缓存边界不清 | 工具需要远程 endpoint、secret、repo外敏感目录，或尝试写 Gradle/Parchment cache | 不安装/停止；报告阻塞，改用官方 jar/source 与 JDWP |
| MCP 改变 Alice 环境 | 安装/查询导致 git/Gradle/cache/runtime changes | 停止并卸载工具目录，恢复前先审计差异；不得擅自清 cache |
| 与全面重构调查冲突 | 调查结论要求替换 BotPlayer/fake connection/网络架构，导致 P0 fixture假设失效 | 暂停 P0 实施，提交当前证据与依赖，等监督员重排；不把 P0 作为旧架构的变相固化 |
| P1 跳跃/推挤修复被混入 | 实现者开始改 `BotPlayer/FakeConnection`、movement 或 packet recipient policy | 立即停止当前工作包，撤出无关更改，回监督员重新授权 |

## 9. 工作量与审查点

| 工作项 | 估计 | 审查点 |
|---|---:|---|
| P0-A fixture设计+GameTest实现 | 0.5-1.0 天 | 先确认 GameTest lifecycle可行；每项有可信边界 |
| P0-C schema/限额/开发文档 | 0.5 天 | 必须先决议 C1a/C1b，observer 默认 off |
| P0-B MCP安装/权限/静态校验/卸载演练 | 0.5-1.0 天 | DSH维护员审核工具与权限；不写 repo |
| 服务端验证/Windows M1-M5 | 0.5-1.0 天 | Windows 由用户执行；结果只作 evidence closure |
| 监督员审查/用户决定后续物理修复或重构 | 单独排期 | P0 完结不自动授权 P1修复 |

预计总工作量：**2-3 个工程日**（含 Windows evidence 整理，不含任何 P1 bug 修复或全面重构）。

## 10. 交付状态

- 报告路径：`.alice-supervision/research/p0-debug-evidence-closure-plan-20260824.md`
- 本任务为只读规划：未安装 MCP、未改 business/Gradle/config、未运行测试、未改 active plan/HANDOVER/客户端记录。
- P0 尚待监督员审核和用户对 C1a/C1b（真实 packet observer 边界）的决定；之后才可写 active plan/派实现。
- **本规划不构成实施授权。**
