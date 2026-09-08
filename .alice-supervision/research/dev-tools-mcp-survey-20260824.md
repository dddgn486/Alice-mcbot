# Alice 开发工具与 MCP 调查

- **任务**：`.alice-supervision/research/dev-tools-mcp-survey-task.txt`
- **Alice 基线**：`ab510fd`，Forge `1.20.1-47.x`、Java 17、ForgeGradle 6、Parchment 映射。
- **日期**：2026-08-24
- **取证说明**：最初 `web_search` 返回 `Insufficient Balance`。监督员明确授权后改为官方 URL 直接取证；随后搜索服务恢复，已补充 `web_search` 结果。核心证据只采用 Forge 官方文档、项目维护者 GitHub/README/releases、Modrinth 官方 API；不使用转载或搜索聚合页作为结论依据。
- **限制**：本报告只给出选项和接入门槛；未安装工具、未改 Alice 代码/计划/HANDOVER、未运行测试。

## 1. 执行摘要

Alice 当前最需要的不是又一个“自动修复”框架，而是把三个长期缺口补齐：

1. **可复核的源码与映射检索**：避免在 FakePlayer、`ServerPlayer`、packet 和 `AbstractContainerMenu` 问题上反复依赖不完整反编译片段。
2. **服务端状态与客户端可见状态的双端证据**：`ab510fd` 的同步修复已经证明 server PASS 不代表客户端物理正确；必须同时采集 packet、实体状态和客户端截图/日志。
3. **可重复的最小场景测试**：现有 selftest 适合行为回归，不足以代替 Forge GameTest 和 Windows 客户端验收矩阵。

推荐优先顺序：

| 优先级 | 建议 | 为什么 | 是否建议立即安装 |
|---|---|---|---|
| P0 | Forge `@GameTest` + 最小物理/packet fixture 分层 | Forge 官方机制；Alice 已有 `gameTestServer` run 配置 | 是，先制订计划后接入 |
| P0 | `adhi-jp/minecraft-modding-mcp` 仅作开发期 MCP | 映射、反编译、符号存在性、Mixin/AT 校验可直接缩短源码调查 | 是，开发机/Agent 工具，不放 mod runtime |
| P0 | 结构化双端诊断日志和 packet correlation id | 直接针对 FakeConnection、同步、GUI 8 轮失败 | 是，但属于 Alice 自有诊断设计，不是第三方依赖 |
| P1 | `langyo/minecraft-mod-mcp` 单独调试实例试点 | 有 Forge 1.20.1 发布物；可读取运行中世界/屏幕/调试字段 | 可选试点，不加入日常客户端 profile |
| P1 | spark profile | 性能/线程/热点问题有价值，不能诊断协议语义 | 仅性能问题发生时使用 |
| P1 | IDEA 远程调试 + Forge logging markers | 成熟、低依赖，最适合断点验证 `hurt/push/send` 调用链 | 是，作为开发配置 |
| P2 | Mixin audit/verbose 调试参数 | 仅在 Alice 或依赖真正使用 Mixin/AT 时启用 | 按需 |
| 不推荐 | 把 MCP（Model Context Protocol）误作 Minecraft Coder Pack 或热重载器 | 两者不是同一概念；MCP 不提供 Forge 1.20.1 热替换/客户端验收 | 否 |
| 不推荐 | 以独立 GUI/packet “调试 mod”替代协议测试 | 大多没有证明 1.20.1 Forge 支持；可能改变网络/渲染时序 | 否 |

## 2. MCP 名称澄清

“**MCP**”至少有两种完全不同含义：

- **Minecraft Coder Pack**：历史上的 Minecraft 反编译/反混淆工具链概念。Alice 当前 ForgeGradle 6 + official/Parchment mappings 已承担现代开发映射工作；不应另行引入旧 MCP 工具链。
- **Model Context Protocol**：AI 客户端与工具服务器通信协议。本调查中的 `minecraft-modding-mcp` 与 `minecraft-mod-mcp` 都是此含义。它们不取代 ForgeGradle、不会让已编译 Java 自动热重载，也不替代 Windows 客户端物理验收。

Alice 的 [`build.gradle`](../../build.gradle) 已配置 ForgeGradle 6、Parchment、`runClient`/`runServer`/`gameTestServer`，所以正确路线是补强其开发与证据链，而不是更换映射体系。

## 3. MCP 候选

### A. `adhi-jp/minecraft-modding-mcp`：离线源码/映射分析 MCP

- 官方仓库：[adhi-jp/minecraft-modding-mcp](https://github.com/adhi-jp/minecraft-modding-mcp)
- 固定检查点：commit [`2c673ad`](https://github.com/adhi-jp/minecraft-modding-mcp/commit/2c673ad75f83dd7e5a667a3bfa1481489b56bfe6)，2026-08-22；MIT；非 archived。
- 官方 README 声明：41 tools、反编译源码浏览、Mojang/Yarn/Intermediary mapping 转换、Forge/Fabric/NeoForge jar 分析，以及 Mixin、Access Widener、Forge/NeoForge Access Transformer 校验。

**适用性**：高。它适合回答“1.20.1 的 `ServerPlayer.hurt`/`Entity.push` 到底覆盖了什么”“某 packet 或方法在当前 mappings 是否存在”“Access Transformer 是否指向正确符号”等源码证据问题。

**可解决的 Alice 问题**：

- FakePlayer 物理：以版本和 mapping 锁定的源文本/调用跟踪，替代手工 `javap` 片段拼接。
- GUI：检查 `InventoryMenu`、`AbstractContainerMenu.clicked` 与 `Slot` 子类的实际版本符号。
- packet：验证 `ClientboundMoveEntityPacket`、`ClientboundSetEntityMotionPacket` 和追踪逻辑的类/成员存在性。
- Mixin/AT：当前没有证据表明 Alice 已使用 Mixin；若未来引入，先在 CI 或本地预检其目标符号。

**不能解决**：不能连接 Alice 的运行中 Minecraft、不能观察真实玩家客户端、不能替代 packet 抓包或 GameTest。

**集成方式**：开发机/DSH MCP 客户端全局工具。配置其 cache/source/mapping 时必须传 Alice 实际 `GRADLE_USER_HOME` 或允许其读取 ForgeGradle 缓存；不得把 API 密钥、Windows 用户路径或 Alice 源码以外的数据暴露给不受控的远程 MCP 服务。

**成本**：配置中等（0.5-1 天）；学习 1-2 天；维护低到中等（Minecraft 版本变更时重建缓存）。

**结论**：P0 推荐，作为**研究/静态验证工具**，不作为 Alice runtime dependency。

### B. `langyo/minecraft-mod-mcp`：运行中游戏 MCP 调试 bridge

- 官方仓库：[langyo/minecraft-mod-mcp](https://github.com/langyo/minecraft-mod-mcp)
- 官方 release：[v0.2.1](https://github.com/langyo/minecraft-mod-mcp/releases/tag/v0.2.1)，2026-07-11。
- release assets 明确包含 `minecraft-mcp-1.20.1-forge-v0.2.1.jar`；因此存在 Forge 1.20.1 发布物证据。
- 最新仓库检查点：[`4f44c80`](https://github.com/langyo/minecraft-mod-mcp/commit/4f44c806e6d565d90b3480cf8e459b40f258f287)，2026-07-10；README 的许可证为 Apache-2.0 / MIT / CC0 多许可证组合。
- README 说明它可以经 MCP bridge 查询 player position、world info、screen buttons、debug fields，并提供本地 debug dashboard/SSE；握手报告 loader/version。

**适用性**：中等，值得隔离试点。它比静态 MCP 更接近 GUI/客户端问题，但它是额外运行时 mod，可能改变启动、事件、网络或 GUI 时序。

**可解决的 Alice 问题**：

- GUI 冻结重启前：把 snapshot revision、viewIndex、服务端 action result、客户端 screen state 暴露成只读 debug 字段，以机器可读方式采样。
- FakePlayer：采样 bot/player position、velocity、game mode、screen/world 状态，辅助复现步骤。
- 证据采集：可作为截图/`latest.log` 之外的辅助状态记录。

**不能解决**：不能证明碰撞/击退在无该 mod 时正确；不能替代真实用户输入与 Windows 客户端视觉验收；不能可靠地充当 raw packet sniffer，除非其 release 文档明确提供对应功能。

**集成门槛**：

1. 只创建独立 `runClient` 调试 profile，绝不进入发布 jar、用户正式整合包或 dedicated server。
2. 固定 jar checksum/release、localhost binding、MCP bridge 权限；默认只读。
3. 先做空白 Forge 1.20.1 + Alice 1.20.1 兼容性/启动耗时/事件时序对照。
4. 通过前不得将其输出作为客户端验收替代品。

**成本**：配置中等（0.5-1 天）；学习 1-3 天；维护中等（每个 Minecraft/Forge 组合都需确认 release asset）。

**结论**：P1 试点，不能立即成为主调试路径。

### C. 两者对比

| 项目 | `minecraft-modding-mcp` | `minecraft-mod-mcp` |
|---|---|---|
| 所在位置 | IDE/Agent 外部开发工具 | 加载进运行中 Minecraft 的调试 mod + bridge |
| Forge 1.20.1 证据 | README 声称 Forge 分析，需安装前实测 Alice Gradle cache | v0.2.1 release asset 明确含 `1.20.1-forge` |
| 最强能力 | 反编译、mapping、Jar/Mixin/AT 静态校验 | 运行时 world/screen/debug 状态读取 |
| FakePlayer 调试价值 | 高：精确源码调用链 | 中：观察状态，不能证明原生行为 |
| GUI 调试价值 | 中：协议/API 依据 | 高：运行中 screen/debug 观测 |
| 风险 | 读取本地缓存/工作区 | 额外 mod 改变运行时、暴露本地调试端点 |
| 推荐 | P0 | P1 沙箱试点 |

## 4. Forge 官方工具与生态

### 4.1 Forge GameTest：首选测试基础

官方文档：[Forge 1.20.x Game Tests](https://docs.minecraftforge.net/en/1.20.x/misc/gametest/)。文档规定 `@GameTest` 方法、`GameTestHelper`、structure template 场景；Forge getting-started 文档明确列出 `runGameTestServer`。Alice 的 `build.gradle:87-105` 已经存在 `gameTestServer` 和 `forge.enabledGameTestNamespaces`，因此基础设施已具备。

**建议的 fixture 层级**：

1. `GameTest`：方块、实体、碰撞箱、伤害、packet 的服务端可重复场景。
2. 现有 selftest：任务链/状态机的快速 smoke，不再宣称等同物理/客户端测试。
3. Windows 客户端矩阵：GUI 渲染、用户输入、可见位移和实际同步的唯一验收层。

**针对当前问题的最小 GameTest**：

- 生存 BotPlayer 与真实 server-side Player/测试实体的 `isPushable`、`canCollideWith`、AABB、`deltaMovement` 前后值。
- `hurt` 后 delta、health、hurt cooldown、`hasImpulse`。
- FakeConnection 对不同 `Clientbound*` 包的处理与“观察者收到包”计数；不要只断言 bot 自己 connection。
- GUI snapshot/action：viewIndex -> logical slot -> underlying Inventory slot 的纯函数 table fixture；revision/fingerprint stale rejection。

**限制**：GameTest 不能验证 Windows 客户端渲染、网络延迟或用户观察到的击退效果。必须保留客户端矩阵。

### 4.2 Forge logging、IDE debugger、JDWP

Forge 官方 [Getting Started](https://docs.minecraftforge.net/en/1.20.x/gettingstarted/) 说明 Gradle `run*` 配置；Alice 已以 `forge.logging.markers=REGISTRIES`、console debug 配置 userdev run（`build.gradle:61-79`）。

建议新增**调试运行配置，不新增生产依赖**：

- IntelliJ attach JDWP 到 `runClient`/`runServer`。
- 只在问题复现时设置方法断点/条件断点：`BotPlayer.tick`、`LivingEntity.aiStep`、`Entity.push`、`ServerPlayer.hurt`、`LivingEntity.knockback`、`FakeConnection.send`、`ServerEntity.sendChanges`。
- 使用 correlation id（bot UUID、server tick、packet type/entity id、task id）做结构化日志，而不是只增加自由文本 `[DEBUG]`。

**收益**：直接验证“到底没调用、调用了但状态未变、还是发包目标/客户端处理错误”，特别适合纠正此前“服务端物理完全正常”的过强推断。

### 4.3 Mixin

官方仓库：[SpongePowered/Mixin](https://github.com/SpongePowered/Mixin)，MIT，检查点 [`4053421`](https://github.com/SpongePowered/Mixin/commit/4053421aa10aaac6127d969028a29c94fe3054f6)（2024-07-06）；其 [Mixin Environment 文档](https://github.com/SpongePowered/Mixin/wiki/Introduction-to-Mixins---The-Mixin-Environment) 描述运行环境与诊断概念。

**建议**：不为调试而引入 Mixin。只有以下条件同时成立才规划：Forge event/公开 API 无法提供必要的只读观测或窄修复；目标版本和映射固定；有注入失败检测、冲突风险评估、GameTest+客户端矩阵。届时可配 `mixin.debug.verbose`/`mixin.dumpTargetOnFailure` 等调试属性，但这些参数的版本行为需在实施计划中单独核验。

### 4.4 Packet 监视

Forge [SimpleImpl networking 文档](https://docs.minecraftforge.net/en/1.20.x/networking/simpleimpl/) 的核心目标是让 client/server 视图同步。对于 Alice，首选**自有、只读、仅开发 profile 的 packet observer**，在 Alice 自己的 `AliceNetwork` 和 `FakeConnection`/广播边界记录：

- direction、packet simple class name、entity id、bot UUID、recipient UUID、server tick；
- 允许类型白名单（movement/motion/teleport/equipment/container snapshot/action）；
- packet 不序列化敏感玩家文本、NBT、inventory 全量内容；
- 采样/限额，避免 debug log 反过来导致 tick 或网络问题。

对比：

| 方案 | Forge 1.20.1 证据 | 结论 |
|---|---|---|
| Alice 内置 observer | 可与 Alice 固定版本和 packet 目标共同测试 | 推荐；最小面、证据可关联 |
| `minecraft-mod-mcp` | v0.2.1 有 1.20.1 Forge jar，但 README 未承诺 raw packet capture | 用于状态观测，不假定 packet sniffer |
| 通用“Packet Profiler/Inspector”mod | 本次未取得同时满足官方仓库、活跃维护、Forge 1.20.1 的充分证据 | 不推荐进入当前计划 |

### 4.5 GUI 与 entity 可视化

没有发现一个同时具备明确 Forge 1.20.1、活跃维护、官方开源、且能安全替代 Alice GUI 诊断的通用工具。推荐把可视化做成**独立开发期 overlay/命令**，只读显示：

- bot AABB、feet/supportTopY、onGround、horizontalCollision、deltaMovement；
- task id/phase/segment、surface path index；
- GUI snapshot revision、viewIndex/logical slot/underlying index、ActionResult；
- packet correlation 的最后 N 条摘要。

此工具仍需独立批准，因为 debug overlay 也会改变客户端渲染与输入路径。它不能替代真实 GUI 操作验收。

## 5. 性能、DataGen 与日志

### spark

- 官方仓库：[lucko/spark](https://github.com/lucko/spark)，GPL-3.0，检查点 [`2954351`](https://github.com/lucko/spark/commit/295435154c5ecbdd857c6076c74ffbd5229e3243)，2026-08-21。
- 官方 Modrinth 项目 [spark](https://modrinth.com/mod/spark) 的 API 元数据列出 1.20.1，更新时间 2026-06-18。

**适合**：高 CPU、tick stall、内存、event hot path，例如若 packet broadcast 或 pathfinding 导致卡顿。

**不适合**：确定 `push`/`hurt` 是否语义正确，或证明 GUI slot 映射正确。

**集成**：只作为 development/performance reproduction profile 的外置 mod，固定版本、先执行无 spark 对照，GPL 许可证意味着不要复制实现或打包进 Alice。

### Forge DataGen

官方文档：[Data Generation](https://docs.minecraftforge.net/en/1.20.x/datagen/)。Alice 已有 `runData` 配置（`build.gradle:107-118`）。它能减少 model/tag/recipe 等资源 JSON 手写错误，但不能解决当前物理或 GUI bug。P2 再考虑，当前不优先。

### 结构化日志与证据产物

这是最有价值的自制辅助能力：不新增第三方运行时依赖，依赖现有 `BotLog`，把调试事实统一成可过滤 schema。建议字段：`event`, `botUuid`, `taskId`, `tick`, `side`, `entityId`, `recipient`, `packet`, `position`, `velocity`, `revision`, `action`, `resultCode`。配套脚本只提取该 schema 和 Windows 测试截图/日志索引。

## 6. 推荐集成路线

### P0：先建立可证伪调试闭环（1-2 天，不改变产品行为）

**前置批准**：新的开发工具/测试工作包；不能以本报告直接实施。

1. 为现有 `gameTestServer` 添加最小 FakePlayer-physics、FakeConnection-recipient、GUI-index contract GameTest。
2. 添加 dev-only correlation log schema 和明确的 packet 类型白名单。
3. 创建 IntelliJ run/attach 文档；复现时先采集断点/日志/客户端证据。
4. 安装 `minecraft-modding-mcp` 到 DSH/IDE 的开发工具层，验证它能读取 Alice Forge 1.20.1 Parchment/Gradle 缓存，不修改 Alice jar。

**通过条件**：每个 T4 现象都能得到“服务端状态 + recipient packet + 客户端可见状态”三份关联证据；不能只报告 PASS/FAIL。

**停止条件**：GameTest 引入专用 fake/绕过生产生命周期，或日志改变 tick/网络时序，则退回为更窄的纯函数 fixture。

### P1：运行时调试 MCP 沙箱（0.5-1 天）

在单独 runClient profile 加载 `minecraft-mod-mcp-1.20.1-forge-v0.2.1.jar`，只读、localhost、固定 release/checksum。以两组相同复现（加载/不加载该 mod）比较 bot/player physics 与 GUI 行为。

**通过条件**：不改变复现结论、没有权限/端口暴露、能稳定导出所需状态。

**停止条件**：加载后跳跃、碰撞、packet 或 GUI 时序变化，或无法证明 release 的 Forge 47.x 兼容性；立即移出调试 profile。

### P2：性能/可视化专项（按问题启用）

路径/同步性能问题用 spark；需要空间物理诊断时单独计划只读 debug overlay。二者都不进入功能发布依赖。

### P3：GUI 重启前的测试升级（预计 2-4 天）

仅在用户决定解冻 GUI 后：先固化 view-slot contract、snapshot revision/fingerprint、ActionResult 与服务端纯函数 fixture；再写 GameTest；最后 Windows 客户端 G 矩阵。MCP/overlay 仅辅助观察，不能代替该设计和验收。

## 7. 风险与限制

- MCP server 可能读取项目源码、Gradle cache 与 mod jar；必须审查本地运行方式、端口绑定、日志和权限，不能连接不可信远程 endpoint。
- `minecraft-mod-mcp` 是外置 runtime mod；“可读取状态”不等于“无行为影响”。必须做加载/未加载对照。
- Forge GameTest 是服务端自动化，不证明客户端视觉、输入、渲染、网络时序或 Windows 实测。
- spark 是 profiler，不是正确性验证器；其 GPL-3.0 许可可用于独立开发运行，但不应复制其代码或把它打包进 Alice。
- Mixin 是高风险技术债；不要把它作为普通 debug 工具。
- 热重载：Forge 1.20.1 的 Java 类、registry、network/channel、Mixin 变更通常仍需要重启开发实例。MCP、GameTest、DataGen 和 profiler 都不能承诺完整热重载。

## 8. 最终决策建议

- **接入**：Forge GameTest 分层、开发期 `minecraft-modding-mcp`、JDWP/结构化双端诊断。
- **试点后再决定**：`minecraft-mod-mcp` 运行时 bridge、spark、只读物理/GUI overlay。
- **保持不接入**：旧 Minecraft Coder Pack、没有明确 Forge 1.20.1 兼容/维护证据的 packet/GUI inspector、为调试目的引入 Mixin、把任意工具当作客户端验收替代品。

当前最短收益路径是先用 GameTest 和 correlation logs 重新建立 FakePlayer 物理的可证伪事实链，然后才决定是否需要更深的 runtime MCP 或替换 bot 架构。

**本报告不构成实施授权**。任何工具接入均须经监督员审核并由用户批准新的工作包。
