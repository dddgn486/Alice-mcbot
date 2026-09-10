# Alice

Alice 是一个 Minecraft Forge 1.20.1 模组：在服务端运行一个客户端可见的假人玩家，让它通过结构化感知、确定性任务和受约束的世界交互完成工作。

> 当前版本用于开发与测试，尚不适合作为稳定发布版直接安装使用。
> 内核路线见 [D-036](docs/AI_DECISIONS.md)：**Alice = Baritone 兼容内核**（搜索 / Movement / 执行器 / 自愈 / 成本模型先对照 Baritone 再实现）。

## 架构原则

1. **LLM 只做目标级决策**：语言模型负责选择目标，不直接输出逐 tick 移动、背包写入或世界修改。
2. **确定性执行器负责落地**：任务状态机、寻路、库存验证和失败回收由服务端执行，并输出稳定结果码。
3. **服务端权威**：Bot、任务、库存、容器和世界变化均以服务端事实为准；客户端只负责显示与交互。
4. **寻路红线（D-076）**：寻路请求**默认纯通行**（`PathRequest.of`）；破坏/放置只能由上层任务**显式授权**并受**预算闸门**约束——挖掘站位用 `PathRequest.miningApproach` + `MiningBudget`（禁用 `PILLAR/FALL/DOWNWARD`），掉落物收集需调用方显式 `allowWorldModification=true`。禁止寻路器自行挖穿地形、禁止把 `SEARCH_LIMIT` 当授权、禁止实验性移动模式隐式接入正式任务。
5. **未知模组能力默认只读**：不猜槽位、配方或写入语义；模组兼容必须走可验证的软依赖适配器（如 `compat/ChainMining`）。

## 文档入口

- **接手第一入口**：[`docs/START_HERE.md`](docs/START_HERE.md)
- 当前状态：[`docs/AI_PROJECT_STATE.md`](docs/AI_PROJECT_STATE.md) ｜ 决策：[`docs/AI_DECISIONS.md`](docs/AI_DECISIONS.md) ｜ 测试矩阵：[`docs/AI_TEST_MATRIX.md`](docs/AI_TEST_MATRIX.md)
- 开发协作与 skills：[`docs/AI_DEVELOPMENT_PLAYBOOK.md`](docs/AI_DEVELOPMENT_PLAYBOOK.md)、[`AGENTS.md`](AGENTS.md)、[`.alice-supervision/skills/`](.alice-supervision/skills)
- 内核与设计：[`ALICE_PATHING_CORE_ARCHITECTURE.md`](docs/ALICE_PATHING_CORE_ARCHITECTURE.md)、[`R4_BARITONE_ALIGNMENT_AUDIT.md`](docs/R4_BARITONE_ALIGNMENT_AUDIT.md)、[`MINING_STAND_SELECTION_DESIGN.md`](docs/MINING_STAND_SELECTION_DESIGN.md)、[`MINE_MIGRATION_DESIGN.md`](docs/MINE_MIGRATION_DESIGN.md)
- 测试流程：[`TESTING_GUIDE.md`](docs/TESTING_GUIDE.md)、[`BARITONE_CONTRAST_TESTING.md`](docs/BARITONE_CONTRAST_TESTING.md)
- 完整索引与归档说明：[`docs/README.md`](docs/README.md)

## 当前能力

| 模块 | 当前状态 |
|---|---|
| 假人玩家 | `BotPlayer` 继承 `ServerPlayer`，服务端注册并在客户端可见（`FakeConnection` 只转发旋转包） |
| 寻路内核 | Baritone 兼容：9 种 Movement（含 `BREAK_AND_ENTER`、`PILLAR`、`FALL`）、两模式搜索、`PathSession` 分段执行 + 自愈重规划；客户端回归 14 项 + 覆盖断言 |
| 挖掘 | `MiningPlanner`（站位候选 + S1/S2 成本估算 + top-K 精算）→ `MineBlockRunner`（走位 → 放支撑 → 破坏）→ `BlockBreakSession`；深埋目标由 `MiningBudget` 判定，超预算如实报 `found_but_unminable` |
| 掉落物收集 | `CollectDropsTask`：来源由 `BlockEvent.BreakEvent` 配对、**簇级**扫掠（连通 2.0 格）、按**背包增量**计数 + 守恒交叉校验（不一致记 `MISMATCH`） |
| 模组兼容（Ore Excavation） | 软依赖 `compat/ChainMining` 反射触发连锁；`MiningTuning.ChainMode{OFF,AUTO,FORCE}` **默认 OFF**，AUTO 仅连锁**矿石/原木**，失败如实回落单格挖掘 |
| 回归入口 | `alice:pathing_regression`（寻路 14 项，含 10 种 Movement 覆盖率断言）、`alice:mine_regression`（挖掘 10 项）、`alice:lumber_failure_check`（5 项失败语义）、`alice:lumber_policy_check`（策略可替换性） |
| 感知与任务 | `PerceptionSnapshot`、`ScopeBuffer`、`Task` 状态机与执行记录 |
| 跟随 / WalkTo | 已迁移新内核：`alice:follow_runner`、`alice:walk_to_runner`（受限目标语义，对齐 Baritone `GoalNear`） |
| C1 只读接口扫描 | `alice:interface_scanner` 读取只读 capability 快照，不继承原版钻石铲行为 |
| 道路模型 | 独立蓝图工具与受限路线模型，与挖矿链隔离 |
| 容器转移（A1.1） | 权限等级 2 的显式命令，单箱 → Bot 背包 → 单箱的审计式转移（边界见下） |
| 伐木（L3 Job） | `alice:lumber_job`：选树 → 就近策略 → 限次清障（≤8 格/棵）→ 自下而上砍 → 收集入包；支持**配额循环**、逐树记账与 §6.2c 终止语义 |
| 挖掘（L3 Job） | `alice:mine_job` / `/alice auto-mine <tag\|block> [count]`：与伐木**同一套**候选集 / 策略 / 决策 trace / 终止词表 |
| 世界写入授权 | 唯一写入原语携带 `WriteGrant(谁, 为什么)`（D-082），执行期复验授权集合；每次写入进 `WriteAudit`（`unknown=0` 即无漏接） |
| 世界修改账本 | `WorldModLedger`（J6-a）：动作层自动记录**放置**（含内核 `PILLAR` 放的方块）+ 配对策略 TEMP/KEEP；`/alice ledger` 只读查看 |
| 决策缝自检 | `alice:lumber_policy_check`（同场景两策略对比）、`alice:lumber_failure_check`（五条终止路径各有真实场景） |

### 验证等级

始终区分 `IMPLEMENTED` / `COMPILES` / `SERVER_TESTED` / `WINDOWS_CLIENT` / `USER_ACCEPTED`。
编译成功与服务端日志**不能替代**真人在游戏客户端的观察；每项能力的当前等级见 [`docs/AI_TEST_MATRIX.md`](docs/AI_TEST_MATRIX.md)。

## 测试入口

优先使用**游戏内物品右键**（零参数），其次是无坐标的一条命令；场景由数据包函数一键生成（`/function alice_test:<scene>`）。

| 工具或命令 | 用途 |
|---|---|
| `alice:pathing_regression` | 寻路串联回归（一键跑完全部寻路场景 + Movement 覆盖率断言） |
| `alice:mine_regression` | 挖掘专项回归（规划 5 + 执行 3 + 悬空支撑 + 模组连锁，共 10 项） |
| `alice:target_selector` | 右键指派挖掘目标；Shift+右键指派放置 |
| `alice:mining_scene_tester` / `_b_` / `_c_` | 挖掘场景 A/B/C 单项验证（配合 `alice_test:scene_a` 等） |
| `alice:mine_course_runner` | 挖掘站位选优自检（自由/贴墙/被围/头位/掩埋五类） |
| `alice:chain_test_runner` | 模组连锁兼容自检（掉落物捕获 + 收集） |
| `alice:pathing_battery` / `alice:pathing_session` / `alice:pathing_break_enter` / `alice:pathing_fall` / `alice:pathing_pillar` / `alice:pathing_lava_guard` / `alice:pathing_fluid_guard` / `alice:pathing_fence_guard` / `alice:pathing_dip_route` / `alice:pathing_waller` / `alice:pathing_disturber` | 单项寻路能力与守卫验证 |
| `alice:walk_to_runner` / `alice:follow_runner` | 迁移后的 WalkTo / 跟随验证 |
| `/alice spawn <name>` / `/alice status` / `/alice stop` | 生成假人、查看当前任务与终态、停止 |
| `/alice chain` / `off` / `auto` / `force` | 连锁挖掘策略查询与切换（默认 `off`） |
| `/alice mining estimate lower_bound\|dijkstra` | 站位成本估算方案切换 |
| `/alice risk` / `/alice trace` | 风险开关查询、运动轨迹记录开关 |
| `/alice protect ...` / `/alice scan <x y z>` / `/alice road build` | 保护区域、C1 只读扫描、道路构建 |
| `/alice transfer-selection ...` / `/alice transfer-test ...` / `/alice transfer-status ...` / `/alice transfer-abort ...` | A1.1 容器转移草稿与审计式执行 |

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

环境：JDK 17 · Minecraft 1.20.1 · Forge 47.4.10 · Gradle 8.8 wrapper · Parchment 2023.09.03

```bash
./gradlew compileJava
./gradlew build
./gradlew runClient
./gradlew runServer
```

离线构建可用 `--offline`（Parchment 仓库不可达时）。

干净 clone 即可 `./gradlew build`。

## 开发方式

以**最小可验证闭环**推进：先定成功条件与验证方式，再实现，最后以日志/客户端证据判定。
决策与验收分别记录在 [`docs/AI_DECISIONS.md`](docs/AI_DECISIONS.md) 与
[`docs/AI_TEST_MATRIX.md`](docs/AI_TEST_MATRIX.md)；协作细则见
[`docs/AI_DEVELOPMENT_PLAYBOOK.md`](docs/AI_DEVELOPMENT_PLAYBOOK.md)。

## Credits

部分物品贴图来自开源贴图集 [malcolmriley/unused-textures](https://github.com/malcolmriley/unused-textures)，
以 **CC-BY-4.0** 许可使用（署名与逐项映射见
[`src/main/resources/assets/alice/textures/CREDITS.md`](src/main/resources/assets/alice/textures/CREDITS.md)）。

## License

[GPL-3.0](LICENSE)
