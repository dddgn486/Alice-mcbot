# 批 1 审查报告：task 模块（任务编排与 HARD_PATH/SOFT_SURFACE 边界）

- **任务**：`cb193779-bc8a-47ce-8a96-e138ef704e3f`（批 1 / task 模块只读审查）
- **审查基线**：`6c2b461`（HEAD）
- **日期**：2026-08-25
- **依据**：`full-review-plan-20260825.md`（§1.2/§2/§4/§5）、`review-batch1-bot-20260825.md`（bot 审查，B2 证据更正：travel"叠加+衰减"非"覆盖"）、`review-batch1-pathing-20260825.md`（pathing 审查：R26/R30/R33 遵守、F1-F6 断言方案）、`docs/HANDOVER.md §八`（R24/R25/R26/R30/R33/R35/R36）。
- **性质**：只读审查。未修改任何文件；未运行测试；未连接任何工具；未触碰 3081/3082/3083。
- **证据方式**：源码行号（task/ 12 文件 + BotManager/BotCommand/selector 引用）、grep 交叉引用、前置审查报告交叉核对。

---

## 2.1 定位与文件清单

**模块定位**（类注释原意）：task = "任务(执行层单元)：一个任务 = 一个确定性行为链，由 BotSession 在主线程逐 tick 驱动；任务层编排'先做什么、后做什么'，动作层单动作状态机，感知层世界状态查询"（Task.java:4-15）。

| 文件 | 角色 | 职责一句话 |
|---|---|---|
| `Task.java` | 基类 | 任务接口（target/tick/failureReason + RUNNING/DONE/FAILED 三态） |
| `TaskTarget.java` | 数据 | 任务目标（BLOCK/ENTITY 两类，客户端高亮与服务端校验共用） |
| `MineTask.java` | 矿链入口 | 单目标挖掘编排：有限局部清障 → BotMiner → DropCollectionTask；深埋 → `target_requires_tunnel` |
| `DropCollectionTask.java` | 拾取 | 只收 `liveItemsFromOrigin(origin)` ∩ primaryIds（R25）；PathExecutor HARD_PATH；不调 TunnelPlanner |
| `PlaceTask.java` | 放置 | 独立方块放置测试任务；不调 RoadBuilder/MineTask；A* + PathExecutor |
| `SoftPathProbeTask.java` | 实验线 | SOFT_SURFACE 连续脚位段探针（NATIVE_TRAVEL + P1 观测/revalidate/replan） |
| `SoftMoveProbeTask.java` | 实验线 | 软地面移动探针（backend 可选 SELF_MOVE/NATIVE_TRAVEL；validate 结构化校验 R32） |
| `SoftPathMineTask.java` | 实验线 | 软路径挖掘测试工具（SOFT_NAVIGATE → BotMiner MINING） |
| `FollowTask.java` | 实验线 | 低风险软移动跟随（独立可开关，R36） |
| `RoadBuildTask.java` | 道路 | 道路蓝图施工演示（forceBreak/无限圆石/WAIT_STABLE/MOVE 步进 → MineTask 收尾） |
| `TransferTask.java` | 传输 | 窄单请求编排（仅消费已有 HARD_PATH 规划/执行；ledger 状态机） |
| `SoftPhysicsObservationTest.java` | 夹具 | P1 物理观测 fixture（观测字段/revalidate/replan/MAX_REPLAN 断言） |

**注册方式**：无 Forge 总线注册；任务实例由 `BotManager.BotSession.assign*` 创建（BotManager.java:476/486/493/500/507/514/539），触发入口仅 BotCommand 命令 + selector 测试工具（SoftMoveSelector/SoftPathProbeSelector）——**无其他创建点**（grep 确认）。

---

## 2.2 边界归属

- **矿链（HARD_PATH）**：MineTask → BotMiner/PathExecutor → DropCollectionTask（PathExecutor）；TransferTask 仅消费 HARD_PATH。
- **SOFT 实验线（独立）**：SoftPathProbeTask/SoftMoveProbeTask/SoftPathMineTask/FollowTask——创建点仅 BotManager assign 入口 + BotCommand/selector（见 2.1 注册方式）。
- **道路**：RoadBuildTask（施工演示，独立于矿链）。
- **传输**：TransferTask（ledger 状态机，独立）。
- **R# 边界对照**：
  - R30：MineTask/DropCollectionTask 固定 HARD_PATH——**任务层语义确认**（见 2.4 与特别核查②）；
  - R25：DropCollectionTask 只收 origin 锁定 UUID（见 2.4）；
  - R24：RoadBuildTask 终点不进最终支撑格（见 2.4）；
  - R33/R35/R36：SOFT 实验线独立（见 2.4）。

---

## 2.3 功能正确性核查点

| # | 宣称行为 | 代码实现 | 证据类型 | 差异/疑点 |
|---|---|---|---|---|
| T1 | MineTask 编排：清障→挖掘→收集 | Phase.MINING/COLLECTING（MineTask.java:19,67-90）；清障用 raycast findDirectBlocker + BlockBreakSafety 拦截（:97-112） | 静态核对 + 服务端可证 | **一致**；latest.log SELFTEST TEST1-3 已验证（bot 审查 2.6） |
| T2 | 深埋目标 → `target_requires_tunnel`（不生成隧道） | MineTask.java:113-116；注释 :16 明示 | 静态核对 | **一致**（与 pathing 审查隧道零调用结论吻合） |
| T3 | 清障深度/距离受限 | MAX_CLEAR_DEPTH=2 / MAX_CLEAR_REACH=4.5（MineTask.java:21-22,134） | 静态核对 | **一致**（R34 相关：最多两格 4.5 格局部清障） |
| T4 | DropCollectionTask 只收 origin 锁定 UUID（R25） | `scope.liveItemsFromOrigin(origin)` ∩ `primaryIds`（DropCollectionTask.java:81-95）；**无 allItems 回退** | 静态核对 | **一致**（R25 禁止加回 allItems） |
| T5 | DropCollectionTask 不调 TunnelPlanner | 注释明示（DropCollectionTask.java:25）；代码无 TunnelPlanner 引用 | 静态核对 | **一致**（pathing 审查零调用方确认） |
| T6 | DropCollectionTask SEARCH_LIMIT 处理 | `!result.reachable()` → 尝试 `pos.above()` → 仍失败 → findStairBlocker 或 `collect_no_path`（:147-159）；**注意：未区分 inconclusive**（见特别核查③） | 静态核对 | **疑似偏差**：`collect_no_path` 将 SEARCH_LIMIT 与 UNREACHABLE 合并报告——与 R26"SEARCH_LIMIT 应单独报告"存在表述差异（失败码未区分预算耗尽与确定无路），**待监督员判定是否需拆失败码** |
| T7 | PlaceTask 独立放置 | 候选生成（:90-112）+ A* 路径（:56-64）+ 视线检查（:114-121）+ 直接 setBlock COBBLESTONE（:85） | 静态核对 | 一致（测试任务语义） |
| T8 | RoadBuildTask 终点不进最终支撑格（R24） | 最终支撑格上方 = 未挖目标方块；bot 停在倒数第二缓冲单元 → 直接 `new MineTask(bot, plan.second(), scope)`（RoadBuildTask.java:148-151,174-176） | 静态核对 | **一致**（R24） |
| T9 | RoadBuildTask forceBreak 语义 | `getDestroyProgress <= 0` 才失败（RoadBuildTask.java:118-128）；`setBlock(COBBLESTONE)` 无限圆石不走背包（:100-105） | 静态核对 | **一致**（HANDOVER 记录：用户强制"强行构建动画"） |
| T10 | TransferTask 仅消费 HARD_PATH | 移动用 SurfacePathfinder + PathExecutor（TransferTask.java:84-93）；ledger 状态机（:95-115） | 静态核对 + 服务端可证 | **一致**（TransferFixture 8 场景服务端断言，bot 审查 2.6 已记录 USER_ACCEPTED） |
| T11 | TransferTask SEARCH_LIMIT 独立码 | `path.inconclusive() ? HARD_PATH_SEARCH_LIMIT : HARD_PATH_UNREACHABLE`（TransferTask.java:86）+ FixtureMovementOutcome.SEARCH_LIMIT（:29,77-79） | 静态核对 + 服务端可证 | **一致**（R26；TransferFixture 断言此码） |
| T12 | SOFT 实验线不接入矿链/拾取/道路 | 创建点仅 BotManager assign 入口（:486-507）+ BotCommand/selector；MineTask/DropCollectionTask/RoadBuildTask **零 SOFT 引用**（pathing 审查 grep 已确认；本审查入口核查再确认） | 静态核对 | **一致**（AGENTS.md 不可协商边界） |
| T13 | SoftPhysicsObservationTest 断言物理观测 | 观测字段非空/revalidate/replan/MAX_REPLAN（SoftPhysicsObservationTest.java:23-99） | 服务端可证 | **缺口确认**（bot 审查指出）：**无 isPushable/push/knockback 位移断言**（见特别核查④） |

---

## 2.4 已知坑与 R# 对照

| R# | 内容 | 当前代码是否遵守 | 核查点证据 |
|---|---|---|---|
| R24 | RoadBuildTask 终点不能把 bot 移动到最终支撑格上方，应从缓冲单元直接进 MineTask | ✅ **遵守** | RoadBuildTask.java:148-151（缓冲单元→new MineTask）+ :174-176 |
| R25 | 采集只认 `liveItemsFromOrigin(target)` ∩ 锁定 UUID；不要加回 allItems | ✅ **遵守** | DropCollectionTask.java:81-95（primaryIds 捕获 + 过滤；无 allItems 回退） |
| R26 | SEARCH_LIMIT ≠ 通道授权，停止并报告 | ✅ **遵守（task 层）**：TransferTask 独立码（:86）、SoftPathProbeTask:84/315、SoftPathMineTask:104/299、FollowTask:135、MineTask 深埋不授权隧道（:113） | ⚠️ **D1 偏差**：DropCollectionTask:158 `collect_no_path` 未区分 SEARCH_LIMIT（见 T6/特别核查③）——不构成"通道授权"（该任务不挖隧道），但失败码粒度不足 R26"单独报告"语义 |
| R30 | 矿链固定 HARD_PATH，不可切 SOFT | ✅ **遵守（任务层语义）**：MineTask/DropCollectionTask/TransferTask/RoadBuildTask 全链 PathExecutor；grep 零 SOFT 引用；SOFT 任务创建点仅实验入口（见 2.1） | **无任何可切 SOFT 路径**（特别核查②） |
| R33 | NATIVE_TRAVEL 已验平地+单格高差；任务逐 tick 显式 travel | ✅ **遵守（代码）**：SoftPathProbeTask:141-144、SoftPathMineTask:167-170、FollowTask:106-107、SoftMoveProbeTask:119 | **D2（沿用 pathing 审查）**：R33"已验"范围不含实体交互（T4 FAIL） |
| R35 | 落地需真实支撑顶面；30 tick 未稳 → soft_path_unsettled | ✅ **遵守**：SoftPathProbeTask:198-225、SoftMoveProbeTask:146-161、SoftPathMineTask:202-223（均用 isStandingAtFootPos + onGround + settle + MAX_SETTLE_TICKS=30） | — |
| R36 | FollowTask 独立、可开关、与保护区解耦 | ✅ **遵守**：FollowTask 只跟随同维度玩家（:21-25），FOLLOW_DISTANCE=2/REPLAN_INTERVAL=10/MAX_TARGET_DISTANCE=24（:18-25）；BotManager.assignFollow/stopFollow 独立入口（bot 审查已确认） | — |

**边界漂移待监督员判定**：
- **D1**：DropCollectionTask `collect_no_path` 未区分 SEARCH_LIMIT/UNREACHABLE（R26 失败码粒度）——建议拆 `collect_search_limit`（或复用 HARD_PATH_SEARCH_LIMIT 风格）；不构成安全风险（该任务不挖隧道、不授权任何扩展），但语义清晰度不足。
- **D2**：R33 验证边界表述（沿用 pathing 审查 D1）——NATIVE_TRAVEL 实体交互未验证。
- **D3**：SoftMoveProbeTask 默认构造 `SELF_MOVE`（SoftMoveProbeTask.java:72-74）与 R33"金斧普通右键默认 NATIVE_TRAVEL"的潜在混淆（实际由 SoftMoveSelector 显式传 NATIVE_TRAVEL，:46-49）——待监督员确认默认后端语义显式化。

---

## 2.5 Dual-View：服务端可证 vs 需客户端实测

**服务端可证（fixture/日志可断言）**：
- 任务终态码（TaskExecutionRecord terminal/resultCode——bot 审查已确认；latest.log SELFTEST TEST1-3）；
- TransferTask ledger 状态机（TransferFixture 8 场景服务端断言，USER_ACCEPTED）；
- SEARCH_LIMIT/UNREACHABLE 失败码映射（TransferTask 独立码可断言；DropCollectionTask 合并码为 D1 缺口）；
- PathingRegression 成本模型（task 消费其结果，pathing 审查已确认）。

**需客户端实测（Windows）**：
- 软移动物理可见性（SoftPath*/Follow/SoftMove 的真实位移/跳跃/落地动画）——T4 已 FAIL（bot 穿玩家+无击退）；
- RoadBuildTask 施工动画（用户强制"强行构建动画"——HANDOVER 记录，需客户端验证动画与产物）；
- DropCollectionTask 拾取交互（forcePickup 反射兜底，DropCollectionTask.java:231-240——需客户端确认拾取可见）；
- FollowTask 持续运动/重算/失败边界（R36 自述未客户端验证）。

> **必须写明**：服务端 PASS ≠ 客户端正确。ab510fd 教训（bot 审查已记录）；task 层终态码 PASS 只证明编排与 ledger 正确，**不证明客户端可见物理/交互正确**（T4 FAIL 即为"服务端任务链 PASS + 客户端物理 FAIL"的现行实例）。按 evidence-collection-standard：实体交互类核查点必须补双端证据。

---

## 2.6 证据等级核查

| 项 | 现有证据 | 等级 | 缺口 |
|---|---|---|---|
| MineTask 编排（清障/挖/收） | latest.log SELFTEST TEST1-3 + 65f4863 T3 隔离 | 服务端 PASS + USER_ACCEPTED（客户端隔离） | 无全链客户端记录 |
| DropCollectionTask | 原版箱子转移 A1.1（TransferTask 相关）USER_ACCEPTED；forcePickup 兜底无专项 | USER_ACCEPTED（转移线）/ 拾取兜底无证据 | forcePickup 反射路径客户端未专项验证 |
| TransferTask + ledger | TransferFixture 8 场景 + A1.1-A9 客户端记录 | USER_ACCEPTED（bot 审查 2.6） | — |
| SOFT 实验线（SoftPath*/Follow/SoftMove） | 65f4863 T1-T2（软寻路/软挖）USER_ACCEPTED；T4 FAIL（实体交互） | USER_ACCEPTED（基础）/ CLIENT_TEST_FAILED（实体交互） | 实体交互服务端断言（F1-F6） |
| RoadBuildTask | HANDOVER 记录"强行构建动画"决策；无专项客户端记录 | 无直接证据（决策已记录） | 施工动画客户端矩阵 |
| SoftPhysicsObservationTest | selftest 套件内运行 | 服务端 PASS（观测字段/replan） | **无 isPushable/push/knockback 位移断言**（bot 审查缺口，本审查确认） |
| PlaceTask | 无专项证据 | 无证据 | 放置客户端验证 |

---

## 2.7 风险等级判定

| 模块/问题 | 等级 | 触发理由 |
|---|---|---|
| MineTask / DropCollectionTask（矿链） | **D 维持** | 与 R24/R25/R30 一致；隔离确认；无 SOFT 路径 |
| TransferTask | **D 维持** | R26 独立码 + ledger USER_ACCEPTED |
| SOFT 实验线（SoftPath*/Follow/SoftMove） | **B 修复（候选，关联 bot 物理；修改权归修复主线）** | travel 调用点是 B2 影响因素（叠加+衰减）；T4 客户端 FAIL；不改代码（本审查只评等级） |
| DropCollectionTask SEARCH_LIMIT 码 | **A 补测试/微修复（D1）** | 失败码粒度不足 R26"单独报告"语义；不涉安全（不挖隧道）——需监督员判定是否拆码 |
| SoftPhysicsObservationTest | **A 补测试（缺口）** | 缺物理交互断言（bot 审查缺口交叉确认）；F1-F6 方案可挂钩 |
| RoadBuildTask / PlaceTask | **A 补测试** | 无专项客户端证据 |
| 停止条件 | 未触发 | 未发现崩溃/死锁级 bug；未需改 R#/冻结边界才能下结论；未与 p1 线冲突（只提交证据） |

---

## 2.8 替换候选评估判据（只列方向不选型）

**方向：任务/调度（BotSession 自研 vs 通用任务框架）**

| 判据 | 已知证据 | 缺证据 |
|---|---|---|
| M1 维护成熟度 | BotSession 自研（BotManager.java:396-629：任务排他/作用域/终态记录）；通用框架（Spring State Machine/Job 队列等）不面向 MC 主线程语义 | 通用框架对 MC 主线程/世界语义的适配评估 |
| M2 Forge 1.20.1 兼容 | 自研直接跑 ServerThread tick（BotManager.onServerTick）；通用框架需桥接 | 桥接方案 |
| M3 集成成本 | 自研被 12 task + BotManager 紧密耦合；替换需重写创建/排他/记录面 | 引用面完整清单 |
| M4 客户端风险 | 任务层本身无直接客户端行为（客户端风险在移动/网络层） | — |
| M5 过渡成本 | 单实现切换 | 双实现并存方案未设计 |

**R37-R40 不可绕过标注**（规划 §4.2）：任何任务/调度替换不得让 LLM 绕过 Policy Gate（R39 计划门）；移动/攻击/库存/机器原语默认只供编排器使用（R39）；三产品主线（R37）与兼容分级（R38）不可被替换改变。**候选只列方向，不选型**。

---

## 特别核查项结论

### ① B2 交叉：task 层 travel 调用点逐处语义 + F1-F6 挂钩

**travel 调用点逐处清单（已确认）**：

| 调用点 | 文件:行 | 调用语义 | 输入方向 |
|---|---|---|---|
| SoftPathProbeTask | SoftPathProbeTask.java:141-144 | 每 tick（distance>ARRIVE 时）`applyToward(NATIVE_TRAVEL)` 或 `applyJumpToward`（ASCEND） | `xxa=0, zza=1, travel(0,0,1)`（SoftMovementPrimitive.java:48-53） |
| SoftPathMineTask | SoftPathMineTask.java:167-170 | 每 tick（SOFT_NAVIGATE 阶段）同 SoftPathProbeTask | 同上 |
| FollowTask | FollowTask.java:106-107 | 每 tick 向目标 `applyToward(NATIVE_TRAVEL)` | 同上 |
| SoftMoveProbeTask | SoftMoveProbeTask.java:119-120 | 每 tick 向目标 `applyToward(backend)`（SELF_MOVE 或 NATIVE_TRAVEL） | SELF_MOVE：`move(MoverType.SELF, step.delta())`；NATIVE_TRAVEL：同上 |
| settle（三任务共用） | SoftPathProbeTask:212 / SoftPathMineTask:212 / SoftMoveProbeTask:153 / FollowTask:117 | 抵达后零输入 `travel(Vec3.ZERO)` | `xxa=0, zza=0`（SoftMovementPrimitive.java:82-86） |

**B2 挂钩结论**（与 pathing 审查一致）：以上调用点每 tick 显式 travel，`moveRelative` **增量叠加** zza 输入到 deltaMovement + `move` 应用 + 摩擦衰减——**不覆盖**外部写入的击退/推挤 delta（bot 审查 B2 证据更正：叠加+衰减）。"无位移"需 hurt/knockback 未触发或 bot tick 链未完整运行配合（bot 审查 B3）。

**F1-F6 挂钩方案（本审查不实施，供修复主线采纳）**：
- **F1/F2 挂钩 SoftMoveProbeTask/SoftPathProbeTask**：对 probe 任务运行中的 bot 调 `hurt()`/`knockback()`，断言 deltaMovement 非零（F1）且 1 tick travel 后位置变化含击退分量（F2）——直接验证 travel 调用点是否阻止 delta 生效；
- **F3 挂钩同一批 SOFT 任务**：`bot.push(entity)` 后断言 deltaMovement 变化 + 位移；
- **F4 挂钩所有 SOFT 任务**：推挤/撞击后断言 `horizontalCollision`/`verticalCollisionBelow` 变化（证明 aiStep/pushEntities 在任务运行期间仍工作）；
- **F5**：hurt 后立即 travel vs 延迟 1 tick travel 位移对照（定位时序）；
- **F6**：回退 `19af345`（P1 前）跑 F1-F4，确认引入点。
- **断言载体**：扩展 SoftPhysicsObservationTest（当前只测观测字段/replan）或新建独立 fixture——**挂钩方案与 bot 审查 F1-F6 完全一致**。

### ② MineTask 是否存在任何可切 SOFT 的路径（R30，任务层语义）

**结论：不存在**。三层证据：
1. **代码层**：MineTask.java 全文件无 SoftMovementPrimitive/SoftPath*/NATIVE_TRAVEL/SELF_MOVE 引用（grep）；移动全部经 BotMiner（含 PathExecutor）+ DropCollectionTask（PathExecutor）；
2. **创建层**：SOFT 任务创建点仅 BotManager.java:486/493/500/507（assignSoft*/assignFollow），MineTask 创建点仅 :539（assign BLOCK → new MineTask）——两条创建链完全独立；
3. **触发层**：SOFT 触发入口仅 BotCommand（soft-probe/soft-path-probe/follow 子命令）+ selector（SoftMoveSelector/SoftPathProbeSelector）；MineTask 触发入口为 assignTarget BLOCK（/alice mine / target_selector）。**无任何共享路径可让矿链任务落到 SOFT 执行**。

### ③ DropCollectionTask 的 SEARCH_LIMIT/UNREACHABLE 处理（R26）

**结论：部分遵守，存在 D1 偏差**。
- **不违反 R26 安全语义**：`!reachable()` → 尝试 pos.above() → 失败 → `collect_no_path`（DropCollectionTask.java:147-159）——**不授权任何隧道/通道**（该任务本就不挖隧道），只停止并失败；
- **偏差（失败码粒度）**：SEARCH_LIMIT（预算耗尽）与 UNREACHABLE（确定无路）**合并为同一 `collect_no_path`**，未像 TransferTask/SoftPathProbeTask 那样拆分 `*_search_limit`——R26"SEARCH_LIMIT 单独报告"语义在拾取线未完全落实；
- **建议（待监督员判定）**：拆 `collect_search_limit` / `collect_unreachable`（或复用 HARD_PATH_SEARCH_LIMIT 风格），使预算耗尽与确定无路可区分——**不属安全缺口，属语义清晰度**。

### ④ SoftPhysicsObservationTest 是否缺物理断言（bot 审查缺口交叉确认）

**结论：确认缺口**。SoftPhysicsObservationTest.run（SoftPhysicsObservationTest.java:23-99）断言：
- 观测字段非空/非 NaN（:44-45,121-138）；
- 路径受阻 → revalidate 或 replan（:47-60）；
- MAX_REPLAN 保护（:62-82）；
- **无 isPushable()/push 后 delta/knockback 后 delta/位移断言**——与 bot 审查 2.6"物理交互零覆盖"缺口一致；F1-F6 方案可填补（见特别核查①挂钩）。

---

## 与 active plan 冲突声明

- task 层 SOFT 任务的 travel 调用点与 P1 客户端同步修复线（p1-client-sync-fix-v1）交叉于 bot 物理问题。按规划 §5.3/§5.4：该线实态 **CLIENT_TEST_FAILED**；本审查**只提交证据（travel 调用点清单 + F1-F6 挂钩），不派发修复、不改任何 task 代码**；修改权归修复主线，证据可被监督员采纳进该线调查计划（须写入 active plan Research Decision）。

---

## 证据分级汇总

- **已确认事实**：12 文件职责与行号；SOFT 任务创建点隔离（BotManager assign 入口仅 4 处 + 命令/selector）；MineTask 无 SOFT 路径（三层证据）；DropCollectionTask 只收 origin UUID（R25）；RoadBuildTask 终点缓冲单元→MineTask（R24）；TransferTask SEARCH_LIMIT 独立码（R26）；travel 调用点 5 处清单；SoftPhysicsObservationTest 断言缺口；R30/R33/R35/R36 遵守。
- **架构推论**：B2 交叉（travel 叠加+衰减，与 pathing 审查一致）；DropCollectionTask `collect_no_path` 应拆码（D1，推论性质待监督员判定）。
- **待调查**：bot 物理根因定案（F1-F6 + 回退 19af345，需服务端断言实测）；SoftMoveProbeTask 默认后端语义（D3）；RoadBuildTask/PlaceTask 客户端证据。

---

**本报告不构成实施授权**；审查未修改任何文件（业务代码/HANDOVER/active plan/skill/客户端记录均未动），未运行测试，未连接任何工具或 endpoint。替换提案、修复派发均需监督员审核 + 用户批准。

---

## 监督员验收记录（2026-08-25，Architecture Supervisor）

**验收结论：✅ 通过（批 1 最后一个模块）**

独立核查：
1. R30 任务层隔离：MineTask.java grep 零 SOFT 引用（SoftMovementPrimitive/SoftPath/NATIVE_TRAVEL/SELF_MOVE 均无）→ 矿链无任何可切 SOFT 路径（三层证据：代码层/创建层/触发层）✅
2. D1 偏差属实：DropCollectionTask.java:151-159 `!result.reachable()` 两次尝试后 `failure = "collect_no_path"`——未区分 SEARCH_LIMIT/UNREACHABLE（对比 TransferTask.java:86 独立码）→ R26"单独报告"粒度不足，但**不构成安全缺口**（该任务不挖隧道不授权通道）✅
3. B2 交叉：travel 调用点 5 处清单（SoftPathProbeTask:141-144 / SoftPathMineTask:167-170 / FollowTask:106-107 / SoftMoveProbeTask:119-120 / settle 三任务共用 :82-86）与 pathing 审查"叠加+衰减"结论一致 ✅
4. SoftPhysicsObservationTest 物理断言缺口确认（无 isPushable/push/knockback 断言，F1-F6 可填补）✅
5. 只读性：src/docs/active-plan/skills-manifest/state-machine 零改动，HEAD 仍 6c2b461 ✅

风险等级判定采纳：矿链 D 维持 / TransferTask D 维持 / SOFT 实验线 B 修复（候选，修改权归修复主线）/ DropCollection 失败码 A 补测试(D1) / SoftPhysicsObservationTest A 补测试 / RoadBuildTask+PlaceTask A 补测试。
D1/D2（R33 验证边界收窄）/D3（SoftMoveProbeTask 默认后端语义）作为待监督员判定的边界漂移项，转入批 1 汇总。

**批 1 三模块（bot/pathing/task）至此全部验收完成。**
