# Alice

Alice 是一个处于早期研发和验证阶段的 Minecraft Forge 1.20.1 模组。项目目标是在服务端运行一个客户端可见的假人玩家，让它通过结构化感知、确定性任务和受约束的世界交互完成工作。

> 当前版本用于开发与测试，尚不适合作为稳定发布版直接安装使用。

## 架构原则

Alice 当前实现遵循四条边界：

1. **LLM 只做目标级决策**：语言模型未来负责选择目标和任务，不直接输出逐 tick 移动、背包写入或世界修改。
2. **确定性执行器负责落地**：任务状态机、寻路、库存验证和失败回收由服务端代码执行，并输出稳定结果码。
3. **服务端权威**：Bot、任务、库存、容器和世界变化均以服务端事实为准；客户端只负责显示和交互。
4. **GUI 转换为语义接口**：面向模组机器的长期方案是调用经过验证的 capability/adapter，而不是模拟视觉点击 GUI。

普通挖矿和拾取始终使用 `HARD_PATH`。`SOFT_SURFACE`、原版 `travel` 和软路径能力目前只存在于独立实验入口或已限定的短程跟随中，不会隐式接入挖矿、拾取、道路、隧道、流体或逃生。

设计文档：

- [产品架构路线](docs/PRODUCT_ARCHITECTURE_ROADMAP.md)
- [AI 玩家架构总纲](docs/AI_PLAYER_DESIGN.md)
- [执行层框架](docs/EXECUTION_FRAMEWORK.md)
- [寻路重构设计](docs/PATHING_REFACTOR.md)
- [Mekanism GUI 语义映射](docs/MEK_GUI_SEMANTICS.md)
- [道路数学模型](docs/ROAD_MATHEMATICAL_MODEL.md)

## 当前能力

| 模块 | 当前状态 |
|---|---|
| 假人玩家 | `BotPlayer` 继承 `ServerPlayer`，通过服务端玩家列表注册并在客户端可见 |
| 感知与任务 | `PerceptionSnapshot`、`ScopeBuffer`、`Task` 状态机和只读任务执行记录 |
| 挖矿与拾取 | 曲面可达站位、视线校验、显式掉落物追踪；深层目标保守失败为 `target_requires_tunnel` |
| HARD_PATH | `SurfacePathfinder` + `PathExecutor`，不破坏方块，不把 `SEARCH_LIMIT` 当作可挖隧道 |
| SOFT_SURFACE 实验 | 独立的短程移动和连续脚位探针；未接入普通挖矿、拾取或道路任务 |
| 短程跟随 | 同维度、命令执行者本人、距离与重算频率受限；与保护区巡逻无关 |
| C1 只读接口扫描 | 独立物品 `alice:interface_scanner` 读取原始只读 capability 快照；不继承原版钻石铲行为 |
| 道路模型 | 独立蓝图工具、普通弯曲路线和受限 2x2 螺旋模型；与挖矿链隔离 |
| A1.1 容器转移实验 | 权限等级 2 的显式命令执行单种原版无 NBT 物品、正数量、单箱到 Bot 背包再到单箱的审计式转移 |

### 验证状态

- `alice:interface_scanner` 的独立物品身份、原版单箱只读扫描、无 Block Entity 目标和原版钻石铲隔离已完成 Windows 客户端验收；该结论仅覆盖 C1 只读快照。
- A1.1 容器转移已通过 focused 服务端 fixture 和监督二审，仍处于 `CLIENT_TEST_PENDING`；客户端移动、在途背包可见性、冲突/重启/abort 和原版箱子 GUI 隔离仍需 Windows 实测。
- 编译成功、headless 日志和监督审核均不替代客户端验收。

## 测试入口

所有 `/alice` 命令要求权限等级 2。

| 工具或命令 | 用途 |
|---|---|
| `/alice spawn <name>` | 生成服务端假人 |
| `/alice mine <x y z>` | 指派一次显式挖掘任务 |
| `/alice auto-mine <tag-or-id>` | 最小规则决策：选择最近且未受保护的目标 |
| `/alice observe` | 输出挖矿视角的结构化感知摘要 |
| `/alice status` | 查看当前任务和最近终态记录 |
| `/alice protect ...` | 管理持久化保护区域、方块 ID 或标签 |
| `alice:target_selector` | 钻石斧外观；右键选择挖掘目标，Shift+右键启动隔离的放置测试 |
| `alice:interface_scanner` | 钻石铲外观的独立 Alice 物品；右键执行 C1 只读扫描 |
| `/alice scan <x y z>` | C1 扫描的命令入口 |
| `alice:road_planner` | 钻石锄外观；选择道路蓝图端点，Shift+右键重置 |
| `/alice road build` | 构建当前道路蓝图 |
| `/alice soft-probe <x y z>` | 独立短程 `NATIVE_TRAVEL` 实验 |
| `/alice soft-path-probe <x y z>` | 独立连续脚位软路径实验 |
| `/alice follow on|off` | 开关受限的同维度短程跟随 |
| `/alice transfer-test <source> <destination> <item> <count>` | A1.1 显式容器转移实验 |
| `/alice transfer-status <request>` | 只读查看转移 ledger 状态 |
| `/alice transfer-abort <request>` | 管理员 abort；在途物品保持保护并要求人工接管 |

## A1.1 转移边界

A1.1 只支持：

- 同维度、已加载的两个不同原版单箱；
- 显式 `minecraft:item` 和正整数数量；
- 默认无 NBT 的单种物品；
- `source chest -> bot 36 格普通背包 -> destination chest`；
- 每条写入腿均执行 `pre -> simulate -> fresh pre -> actual -> post`；
- 无部分成功、自动重试、自动恢复、隐式 rollback 或跨重启继续写入。

双箱、模组容器、标签/别名、多种物品批量、NBT 匹配、ACL、packet/UI 和通用 capability 写入均不在该实验范围内。

## 构建与运行

环境：

- JDK 17
- Minecraft 1.20.1
- Forge 47.4.10
- Gradle 8.8 wrapper
- Parchment 2023.09.03

```bash
./gradlew compileJava
./gradlew build
./gradlew runClient
./gradlew runServer
```

`libs/` 需要手动放置 `jecharacters-1.20.1-forge-4.6.9.jar`。JEI/JECh 只用于开发环境；`mods.toml` 未把它们声明为运行时硬依赖，项目代码也不依赖其 API。

Focused 服务端验证入口：

```bash
./gradlew runServer -Dalice.selftest.auto=true
```

该命令会继续进入历史 broad selftest；因此应以明确的 suite PASS/FAIL 日志和进程退出事实分别记录，不能因出现某个 PASS 行就声称整套测试通过。

## 开发流程

项目采用监督员批准的工作包流程：架构或客户端可见改动必须先写入 `.alice-supervision/active-plan.md` 并标记 `APPROVED_FOR_IMPLEMENTATION`。实现完成后必须编译、更新 HANDOVER、提交、生成审核包，并在监督二审通过后进入客户端测试。

公开仓库提交只包含已审核的功能和文档；实验草稿、待修复提交和客户端证据不会因为编译通过而自动发布。

## License

[GPL-3.0](LICENSE)
