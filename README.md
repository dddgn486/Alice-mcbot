# Alice (alice) ----- 项目在早期功能实现测试阶段，暂无法直接使用

**游戏内 AI 助手** —— Minecraft Forge 1.20.1 模组。服务端假人玩家（Alice）能像真实玩家一样感知世界、决策、执行任务：挖矿、拾取掉落物、清障挖通道、扫描模组机器接口。

目前核心实现代码由ai大模型生成，质量水准不一定高
> 设计文档: [`docs/AI_PLAYER_DESIGN.md`](docs/AI_PLAYER_DESIGN.md) · 执行层框架: [`docs/EXECUTION_FRAMEWORK.md`](docs/EXECUTION_FRAMEWORK.md) · Mek GUI 语义表: [`docs/MEK_GUI_SEMANTICS.md`](docs/MEK_GUI_SEMANTICS.md)

## 核心特性（当前里程碑）

| 模块 | 说明 |
|---|---|
| **假人玩家化** | `BotPlayer` 继承 `ServerPlayer`，伪造连接经 `PlayerList.placeNewPlayer` 注册——客户端可见、tick 无 NPE、物理交互走原版玩家逻辑 |
| **感知层** | `PerceptionSnapshot` 世界直读 + 分类聚合摘要（目标/危险/收获/普通）；`ScopeBuffer` 任务作用域事件流（掉落物/方块变化） |
| **决策层** | `AutoMineDecision` 最小规则：扫描匹配方块标签的目标 → 取最近 → 执行（LLM 决策接入点预留） |
| **任务层** | `Task` 接口 + `MineTask`：挖掘（含清障挖通道，最多 8 层）→ 感知掉落物 → 走位拾取入包 |
| **执行层** | `BotMiner` 挖掘状态机（站位候选逐个尝试、视线无遮挡硬检查、faceTarget 朝向）；`PathExecutor` 位置步进（服务端假人无客户端，手动移动+着地） |
| **寻路** | A* 移植自 Baritone（`pathing/` 包，不依赖 Baritone 库）：跳跃语义（1 格高方块可越过、垂直爬升悬空过渡）、路径动态重规划 |
| **接口扫描** | `InterfaceScanner`：capability + Mek GUI 页签统一扫描（物品/能量/流体/气体/红石/升级/安全/传输配置） |
| **客户端测试效果** | 任务目标透视高亮（自定义 RenderType 关深度测试，方块=绿框、实体=红框）；道路蓝图为蓝色外轮廓透视 |
| **道路数学模型** | `alice:road_planner` 选择两端，三维体素最短路绕开水/岩浆及一格 clearance，支持受侧格检查的对角拐角、单格坡度和虚空支撑 |
| **独立放置测试** | 目标指定器 Shift+右键选择点击面外侧格，由独立 `PlaceTask` 寻站位、验视线后放圆石，不调用道路构建 |

## 测试工具

| 工具/命令 | 用途 |
|---|---|
| `/alice spawn <name>` | 生成假人 |
| `/alice mine <x y z>` | 指派挖掘任务 |
| `/alice auto-mine <tag>` | **决策层**：自动挖最近的匹配标签或方块 ID（受保护目标自动跳过） |
| `/alice protect ...` | 持久化安全区：保护区域、方块 ID 或 `#标签`，并可列出摘要 |
| `/alice observe` | 感知摘要（挖矿视角） |
| `/alice scan <x y z>` | 接口扫描 |
| `/alice selftest` | 自动化验收（13 个场景，headless 用 `-Dalice.selftest.auto=true` 自动触发+关服；单项严格 10/20/30 秒预算） |
| `/alice road build` | 按当前道路数学蓝图逐水平单元搭建/清理，使用圆石并等待坠落稳定 |
| `alice:road_planner` | 道路蓝图工具（贴图=钻石锄）：右键两端生成蓝色三维道路，Shift+右键重置 |
| `alice:target_selector` | 目标指定器（贴图=钻石斧）：右键方块派挖掘；Shift+右键点击面外侧格派独立放置任务 |
| 钻石铲右键 | 快捷接口扫描 |

## 构建

要求：JDK 17、Gradle 8.8（wrapper 自带）、网络（下载 Minecraft/Forge/Mekanism/JEI 依赖）。

```bash
./gradlew build        # 编译 + 打包 mod jar (build/libs/)
./gradlew runClient    # 客户端测试（Windows 上开发常用）
./gradlew runServer    # 服务端测试（需要临时注释 libs 里的 JECh 依赖,见下）
```

**本地依赖**：`libs/` 目录需要手动放置 `jecharacters-1.20.1-forge-4.6.9.jar`（中文/拼音搜索，无 maven 发布）。JEI 已从 modmaven 解析，无需本地 jar。

**开发依赖说明**：JEI/JECh 是**可选开发工具**（非硬依赖——mods.toml 未声明、发布 jar 不含、代码零引用）。JEI 仅 dev 环境查看配方界面；JECh（纯客户端）通过独立的 `clientOnly` 配置只注入 `runClient`，`runServer` 不加载。

## 验收测试

```bash
./gradlew runServer -Dalice.selftest.auto=true
```

自动跑 13 个场景（正常挖掘/隔空挖拦截/远端寻路/坑场景/草丛寻路/头顶挖/洞壁矿石/清障挖通道/决策匹配/安全区拦截/沿单格楼梯拾取两格深坑掉落物/脚下目标换侧面站位/挖侧向单格台阶拾取坑底物）并关服，日志见 `run/logs/latest.log`。

## 架构分层

```
决策层 (LLM 接入点 / AutoMineDecision 规则)
  → TaskTarget
任务层 (Task / MineTask: 挖矿 → 拾取, 清障挖通道)
  → 动作调用 + 感知查询
动作层 (BotMiner / PathExecutor)    感知层 (PerceptionSnapshot / ScopeBuffer)
```

## 开发环境

项目在 WSL（`~/projects/alice`）与 Windows（`D:\JAVA_projects\alice`）双仓库同步开发：
WSL 改代码 → `git push origin master` → Windows 仓库（`receive.denyCurrentBranch=updateInstead`）→ 本地 runClient 实测。

## License

见 [LICENSE](LICENSE)。
