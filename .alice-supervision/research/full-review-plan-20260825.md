# Alice 全量功能审查线路规划（分批、每模块独立报告 + 总报告）

- 规划角色：Alice 实现规划员
- 日期：2026-08-25
- Git 基线：`6c2b4613a74c534080df518b71f6257797ea32b5`（HEAD；含 P1 客户端同步修复 `ab510fd`、P0 skill 升级 `4312880`，监督员记录 P1 Git MCP 试点暂停）
- 任务书：`.alice-supervision/research/full-review-planning-task-20260825.txt`
- 性质：**只读规划**。本报告只定义"审查怎么分批、每批查什么、报告怎么写、替换怎么评估"，不评估任何具体代码对错、不预定替换结论、不修改任何文件。

> **本规划不构成实施授权。** 审查执行、修复、替换均需监督员审核通过后另行派发；任何替换决策都需用户批准。

## 0. 项目事实基线（本规划依据）

- 94 个 Java 文件、19 个功能模块（实时清点，含子目录）：
  `pathing(14) / transfer(13) / task(12) / item(8) / client(6，含 client/gui+client/render) / bot(6) / perception(5) / network(5) / gui(5) / survival(4) / road(4) / capability(3) / protection(2) / command(2) / tool(1) / log(1) / decision(1) / action(1) / AliceMod(1)`
- 近期主线（git log）：GUI 重构线 `f0ef6fc..9534a5d`（约 10 笔）→ 拾取/同步 `1621ad2..f7dc5e9` → `2298dd3`（P1 物理观测/revalidate/replan）→ `65f4863`（soft-path 测试工具）→ `ab510fd`（P1 客户端同步：FakeConnection 广播位置/速度包）→ `4312880/d872367`（P0 skill 升级）→ `6c2b461`（P1 Git MCP 试点暂停）。
- 当前 active plan：`20260824-p1-client-sync-fix-v1`（计划文件写 APPROVED_FOR_IMPLEMENTATION，方案 A = FakeConnection 广播 bot 位置/速度包给真实玩家）。**实态更正（监督员补充的已核查事实）**：P1 客户端同步修复实测失败——`ab510fd` 引入玩家跳跃异常、bot 物理失效自 `2298dd3`（P1 物理观测实施时）起就已存在、两个问题均未修复、复测记录从未创建；故该线的有效状态是 **CLIENT_TEST_FAILED**，不是计划文件标注的 APPROVED。审查必须以此事实为基线；审查只读，不得修改该计划或打断修复主线。
- 方法论：3 个已升级 skill（debugging-root-cause-analysis / test-coverage-matrix / evidence-collection-standard，frontmatter + eval + 双端证据已落盘，commit `4312880`）；审查执行阶段必须按这三者产出证据。
- 已知坑/不可翻案项：HANDOVER §八 R8/R14–R40；GUI 交互 FROZEN（`19af345` docs 记录）；A1.1 装备渲染/死亡过滤 USER_ACCEPTED（`a6b9f93`）。

---

## 1. 批次划分（核心）

### 1.1 优先级判据（可解释）

每模块按以下 5 项打分（只统计事实，不做对错评估）：

| 判据 | 数据来源 | 说明 |
|---|---|---|
| C1 提交频率 | `git log --oneline -- <module目录>` | 近 30 笔内该模块的改动笔数（含功能+修复+docs 内变更），反映活跃度/迭代风险 |
| C2 模块耦合 | grep import 交叉统计 | 谁 import 谁；被 bot/task/pathing/network 多向引用的模块耦合更重 |
| C3 已知坑数 | HANDOVER §八 R 编号归属 | 每模块落到的 R#/坑条目数量 |
| C4 客户端可见性 | HANDOVER 各章节 + client 目录 | 是否直接影响 Windows 客户端可见行为（渲染/同步/GUI/交互） |
| C5 主线关联 | R30-R36 + active plan | 与 HARD_PATH + SOFT_SURFACE 主线、当前修复线（p1-client-sync-fix-v1）的关联程度 |

**批次规则（含用户决策者补充的优先级）**：用户明确表示「bot 的物理系统是非常重要基础，直接影响软路径实现，后期写任务实现时会有很多隐患」。因此：
- **批 1 = bot 物理模块（BotPlayer/FakeConnection/travel/碰撞/击退）最高优先级**，与 pathing 软路径同批——它是当前 P1 两个未解决问题（玩家跳跃异常、bot 物理失效）的交汇点，也是未来一切任务实现的地基；
- 批 1 其余 = C1/C2/C5 高且直接关联该问题线（先审"正在出问题的线"）；批 2 = C4 高、跨模块（客户端可见与网络/GUI 耦合）；批 3 = 独立业务线、已验收/半验收、迭代少；批 4 = 外围单文件/低耦合/稳定。

### 1.2 四批划分

| 批次 | 模块 | 审查重点 | 只读工作量级 | 产出物 |
|---|---|---|---|---|
| **批 1（bot 物理地基最高优先 + 当前问题线）** | `bot(6)`（bot 物理最高优先）、`pathing(14)`、`task(12)` | **bot（用户决策者指定最高优先）**：BotPlayer 玩家化生命周期、FakeConnection 同步语义（`ab510fd` 引入玩家跳跃异常、bot 物理失效自 `2298dd3` 起、均为未修复待查问题线）、travel/碰撞/击退/推挤的物理事实、BotManager 任务排他；**pathing（与 bot 物理同批）**：HARD_PATH（PathExecutor）与 SOFT_SURFACE（MovementHelper/SoftMovementPrimitive/SurfacePathfinder）边界、R30-R36 归属、SEARCH_LIMIT≠UNREACHABLE（软路径/未来任务都依赖 bot 物理地基）；task：MineTask/DropCollectionTask SoftPath*/FollowTask/RoadBuildTask 与 R24/R25/R33/R35 对照 | 6-9 小时/模块（约 18-27 小时批1；bot 物理 3 文件 BotPlayer/FakeConnection/BotManager 作为批1第一批先审） | 每模块独立报告 + 批 1 汇总（含与 p1-client-sync-fix-v1 的 CLIENT_TEST_FAILED 实态判定） |
| **批 2（客户端可见 + 跨模块网络/GUI）** | `transfer(13)`、`gui(5)`、`client(6)`、`network(5)` | transfer：A1.1 已验收范围、ledger/request/selector 与 inventory/GUI 耦合；gui：FROZEN 边界（方向 B 自定义 packet 后 `BotInventoryMenu/ModMenuTypes/ClientMenuScreens` 是否仍留作死代码）、snapshot/service 纯函数；client：Client*State 与 render 仅客户端、双端证据缺口；network：SimpleChannel 协议版本/方向、packet 白名单 | 4-6 小时/模块（约 16-24 小时批2） | 每模块独立报告 + 批 2 汇总（含 Frozen/USER_ACCEPTED 不可翻案核对） |
| **批 3（独立业务线）** | `item(8)`、`road(4)`、`capability(3)`、`survival(4)`、`perception(5)` | item：测试工具/选择器入口语义（右键/Shift、support→foot 映射 R32）；road：R17-R23 决策核对、RoadPlan 单例 R21、螺旋阈值 R17；capability：C1 只读 Scanner（S1-S4 已用户验收）、C2-C4 未做边界；survival：R28/R29 维生硬中断 vs 逃生独立；perception：ScopeBuffer liveItemsFromOrigin 归属 | 2-4 小时/模块（约 10-18 小时批3） | 每模块独立报告 + 批 3 汇总 |
| **批 4（外围/单文件）** | `protection(2)`、`command(2)`、`tool(1)`、`log(1)`、`decision(1)`、`action(1)`、`AliceMod(1)` | protection：BlockBreakSafety/SafeZoneData 硬拦截一致性；command：/alice 路由权限与子命令；tool/log/decision/action/AliceMod：单文件职责、注册方式、与模块边界是否错位 | 1-2 小时/模块（约 7-14 小时批4） | 每模块独立报告 + 批 4 汇总 |

**批号即建议执行顺序**（批 1 最先，因 bot 物理是当前未修复问题线（CLIENT_TEST_FAILED）与未来一切任务实现的地基）；批 1 与批 4 可部分并行（批 4 属外围，互不共享风险），批 2/批 3 在批 1 结论后展开。每批结束由监督员验收后再放下一批（见 §5）。

---

## 2. 每模块审查模板（供执行者使用）

每个模块独立报告统一按以下模板（字段完整、可执行）：

### 2.1 定位与文件清单
- `模块定位`：一句话职责（引用类注释/设计文档，不改写）。
- `文件清单`：目录下全部 Java 文件（含子目录），标注每个文件 3 字角色（如"状态机/门面/原语/夹具/命令"）。
- `注册方式`：mod 总线注册（AliceMod 内 MOD/EVENT 总线）、DeferredRegister、client-only 标记。

### 2.2 边界归属
- 该模块落在哪条线：HARD_PATH / SOFT_SURFACE / 独立实验 / 冻结线 / 已验收线。
- 相邻边界：挖矿/拾取/道路/跟随/隧道/逃生 的归属对照（例如：`MineTask` 归属 HARD_PATH 矿链，不接 SOFT；`SoftPathProbeTask` 独立实验，不得接入矿链——引用 HANDOVER R30/R33/R35/R36）。

### 2.3 功能正确性核查点
- 该模块"宣称行为" vs "代码实现"的差异清单（只列核查点，不下结论）。
- 每个核查点标注证据类型：静态核对（读代码） / 服务端可证（headless/log） / 需客户端实测（Dual-View）。

### 2.4 已知坑与 R# 对照（HANDOVER §八）
- 该模块命中的 R# 清单 + 每条的"当前代码是否仍遵守"核查点（如 R24 RoadBuildTask 终点不进最终支撑格、R25 采集只收 origin 锁定 UUID、R26 SEARCH_LIMIT 不作通道授权、R30 HARD_PATH 固定）。
- 未命中 R# 但疑似边界漂移处：单独列出"待监督员判定"。

### 2.5 服务端可证 vs 需客户端实测（Dual-View，按 test-coverage-matrix）
- 服务端可证清单：fixture/日志可断言的行为（A* 成本、ledger 状态迁移、拒绝码）。
- 需客户端实测清单：渲染、同步时序、GUI 交互、物理可见性（必须写明"服务端 PASS 不等于客户端正确"，引用 `ab510fd` 教训与 evidence-collection-standard 的 EVIDENCE_CONFLICT 规则）。

### 2.6 证据等级核查（按 evidence-collection-standard）
- 该模块现有验证证据分级：USER_ACCEPTED（有客户端记录）/ CLIENT_TEST_PENDING / 服务端 fixture PASS / 无证据。
- 证据缺口：哪些核查点当前零证据。

### 2.7 风险等级判定
| 等级 | 含义 | 触发条件（举例，不判模块） |
|---|---|---|
| A 补测试 | 行为可能正确但零/弱证据 | 无 fixture 无客户端记录、宣称行为无证据 |
| B 修复 | 行为与宣称/边界不符且证据指向明确 | 与 R# 冲突、日志失败码与语义不符 |
| C 替换候选 | 结构上存在更成熟/兼容的外部或 vanilla 方案可对照（**只列方向不选型**） | 自研实现与成熟方案重叠、维护成本高 |
| D 维持 | 与 R#/冻结边界一致且证据充分 | 已 USER_ACCEPTED、与主线一致 |

### 2.8 替换候选评估判据（引用 §4 框架，不预定结论）
- 按 §4 的 5 项判据逐项填写该模块候选方向的"已知证据/缺证据"，输出"替换决策点所需证据清单"（不是选型结论）。

---

## 3. 总报告规格

总报告 = 批 1-4 全部独立报告的汇总，必须包含：

### 3.1 汇总表（19 模块）
| 模块 | 批次 | 风险等级(A/B/C/D) | 判定建议 | 依赖链(上游→下游) | 建议 | 证据等级 |
|---|---|---|---|---|---|---|
| pathing | 1 | … | … | task/bot → pathing | … | … |
| … | … | … | … | … | … | … |

### 3.2 交叉边界矩阵（已验证 vs 盲区）
- 行 = 功能组合（如：寻路×挖掘、SOFT_SURFACE×碰撞、transfer×inventory、GUI×FakeConnection 同步、道路×流体）。
- 列 = 证据状态：✅ 客户端实测、🟡 仅服务端日志、⬜ 零证据、⛔ 冻结线。
- 用途：一眼看出"哪些组合实际被 Windows 验收过"、哪些是"服务端自证"的盲区（这正是"加新功能挖旧 bug"的高发区）。

### 3.3 系统性根因分析维度（为什么"每次加新实现挖出一堆旧 bug"）
只列分析维度与证据采集点，不下结论：

1. **共享状态归属**：每个可写共享状态（BotSession.task、ScopeBuffer.liveItems、RoadPlan 单例、BotWorldData、TransferLedgerData、player inventory）是否有唯一 owner，谁可写、谁只读；交叉写入点即根因候选。采证：import 图 + 静态字段/单例清单。
2. **同步链路双端证据缺失**：服务端状态与客户端可见是否总是同时留证（FakeConnection 同步、GUI snapshot、装备渲染）；凡"单端证据"的判定一律记盲区。采证：交叉矩阵 🟡/⬜ 行。
3. **回归层次**：每次改动作用于哪一层（A* 算法 / movement primitive / 任务编排 / 网络 / 客户端渲染）；改动是否只在一个层次、回归是否覆盖相邻层次（协议/单层改动却跨层出效果 = 需标记）。采证：git log 按模块聚类 + 每模块自测覆盖层。
4. **owner 契约**：模块间契约是否被显式记录（R#、HANDOVER、Active Plan）；无契约的隐式约定（如"谁结束 ScopeBuffer"）在改动时最易破坏。采证：每模块 2.2/2.4 的边界与 R# 对照表。

### 3.4 推荐执行顺序
- 按风险/依赖排序：批 1 → 批 2 → 批 3 → 批 4（模块内部按风险等级 A→B→C 优先）。
- 与 HANDOVER §十一"下个会话建议"（TunnelPlanner/目标簇/道路回归）对照：审查发现的证据缺口直接纳入相应待办或新工作包，**不并入本审查执行**。

---

## 4. 替换评估框架（不预定结论）

### 4.1 "更成熟、兼容性更强"判据（5 项）

| 判据 | 问什么 | 证据要求 |
|---|---|---|
| M1 维护成熟度 | 上游/vanilla/社区方案是否有活跃维护、版本锁定、文档；自研是否重复造轮子 | 固定版本/commit、release 时间、issues 活跃度、license |
| M2 Forge 1.20.1-47.x + JDK17 兼容 | 候选方案是否支持目标环境（例：DebugBridge=Fabric、VitaminMCP=Paper 1.21+ → 不兼容，只能借鉴模式） | 官方支持矩阵/发布物实测 |
| M3 集成成本 | 替换改动波及模块数、是否需改 R# 决策/冻结边界 | import 图 + 现有契约对照 |
| M4 客户端风险 | 替换是否改变客户端可见行为、是否需新 Windows 矩阵 | 双端证据矩阵（§3.2） |
| M5 过渡成本 | 双实现并存 / 一次性切换；回滚成本 | 执行方案设计（替换决策点时才需） |

### 4.2 候选方向清单（仅列举方向，**不选型**）

| 方向 | 候选（对照物） | 标注不可翻案项 |
|---|---|---|
| GUI 交互 | 自研容器菜单 / vanilla `AbstractContainerMenu` 复用 / 自定义 packet+Screen（方向 B，已实施 `9534a5d`） | **GUI 交互 FROZEN（`19af345`）**；方向 B 已定，任何切换需用户重新批准并解冻；`BotInventoryMenu/ModMenuTypes/ClientMenuScreens` 若成死代码需清或保留由监督员定 |
| 寻路算法 | 自研 A*/SurfacePathfinder / Baritone 移植（R31：软移动优先借鉴成熟实现） | R26 SEARCH_LIMIT≠UNREACHABLE（一切替换不得把预算耗尽当通道授权）；R30 矿链 HARD_PATH 固定；R31 不从零重写核心运动/寻路 |
| 软移动执行 | 自研 travel 注入 / 成熟 input-override 方案 / 原版 PathNavigation | R33 当前 NATIVE_TRAVEL 已验平地+单格高差；R35 落地需真实支撑顶面；R36 FollowTask 独立 |
| 客户端同步 | FakeConnection 广播（`ab510fd` 方案 A）/ 标准 `ServerEntity` tracker / vanilla broadcast | R# 无直接条文；但 `ab510fd` 引入玩家跳跃异常、bot 物理失效自 `2298dd3` 起（**均为未修复问题**，实态 CLIENT_TEST_FAILED），候选必须同时满足"观察者收包 + 不干扰玩家物理" |
| 感知/作用域 | ScopeBuffer/PerceptionSnapshot / 原版 `Entity` tracking / 成熟感知库 | R25 采集只收 origin 锁定 UUID（替换不得放开收集范围） |
| 道路/隧道 | RoadPlan 自研曲线+螺旋 / 成熟结构生成 | R17（螺旋阈值改动后勿回旧值）、R21（单例绑定 ServerLevel）、R22/R23（勿复活已删临时逻辑）；TunnelPlanner 属未实现，不属替换审查 |
| 任务/调度 | BotSession 自研 / 通用任务框架 | R37-R40 产品主线/兼容分级/计划门/知识边界（替换不得让 LLM 绕过 Policy Gate） |

### 4.3 每个候选方向的"替换决策点"需要什么证据

- 决策点证据清单（每个方向通用）：
  1. §3.2 交叉矩阵中该方向涉及的组合现状（客户端实测/服务端日志/盲区）；
  2. M1-M5 五判据的对照表（固定版本+license）；
  3. 与 R# 冲突面：替换后哪些 R#/冻结边界必须重写、哪些保持（不可翻案项不得并入替换包，需独立用户批准）；
  4. 过渡方案（双实现并存 vs 切换）与回滚步骤；
  5. 新 Windows 客户端矩阵草案（若改变客户端可见行为）。
- **任何方向没有同时满足 1-5 不形成替换提案**；替换提案先经监督员审核、再用户批准，不随审查批次自动获得授权。

---

## 5. 执行路径与验收

### 5.1 谁执行、怎么执行
- **执行者**：深度调查员/深度审查员（按模块分批派发）做只读审查；监督员审核每批；规划员（本角色）不执行审查。
- **批内流程**：每模块按 §2 模板产出独立报告 → 监督员核对模板完整性/证据等级 → 批内全部报告通过后监督员审核批汇总 → 允许下一批展开。
- **工具**：审查期使用已升级 skill（root-cause / coverage / evidence）作为报告质量门；git log/import 统计为只读数据源。
- **禁止**：审查员在审查期间修改任何文件（含业务代码、skill、HANDOVER、active plan）；发现疑似 bug 只记录证据，不修（见 5.3）。

### 5.2 每批验收标准
1. 本批每模块独立报告覆盖 §2 全部字段（定位/边界/核查点/R#对照/Dual-View/证据等级/风险等级/替换判据）；
2. 批汇总含 §3 相应部分（汇总表行、交叉矩阵行、根因维度证据点）；
3. 报告明确区分"已确认事实 / 架构推论 / 待调查"（证据分级按 evidence-collection-standard）；
4. 无任何文件被修改（审查后 `git status` 与审查前一致，仅新增 `.alice-supervision/research/` 报告）；
5. 未出现"预定替换结论"（候选方向只列不选型）。

### 5.3 停止条件
| 触发 | 动作 |
|---|---|
| 审查中发现必须立即修的严重 bug（数据损坏/崩溃/任务死锁） | **停止该模块审查**，将证据单独立包交监督员 → 监督员评估是否中断 active plan 派发紧急修复（不开平行维修） |
| 证据互相冲突（服务端 PASS vs 客户端 FAIL） | 停止该模块，按 EVIDENCE_CONFLICT 规则补双端证据后再续 |
| 审查需修改 R#/冻结边界（HARD_PATH、SOFT_SURFACE、GUI FROZEN、R26）才能继续下结论 | 停止，交监督员/用户决策，不得在审查中预判 |
| 与 active plan `p1-client-sync-fix-v1` 冲突（审查结论要求改 FakeConnection 同步方案） | 停止并注明"该线实态 CLIENT_TEST_FAILED（ab510fd 引入玩家跳跃异常、bot 物理失效自 2298dd3 起、均未修复、无复测记录）"，审查只提交证据，修改权归修复主线，不得由审查派发修复 |
| 用户/supervisor 要求调整批次顺序 | 停止当前批，按新顺序重排 |

### 5.4 与 p1-client-sync-fix-v1 主线的关系
- 审查是**只读并行线**：不修改 active plan、不打断修复主线；批 1（bot/pathing/task）的审查结论直接为**实态 CLIENT_TEST_FAILED** 的 P1 客户端同步问题（`ab510fd` 引入玩家跳跃异常、bot 物理失效自 `2298dd3` 起、均未修复）提供证据，但不替代其调查/修复；
- 批 1 产出的证据可被监督员采纳进该线的调查计划，但"采纳"须监督员写入 active plan 的 Research Decision，审查员无权自行接入；
- 若修复主线（重新计划/重新批准后）推进，审查批次与主线冲突时停止并重排（5.3）。

---

## 6. 交付状态

- 报告路径：`.alice-supervision/research/full-review-plan-20260825.md`
- 本任务只读：未修改任何文件（业务代码、skill、HANDOVER、active plan、客户端记录均未动）；未运行测试；只读取了任务书、HANDOVER（§一~§十一）、active plan、git log、模块清点。
- 监督员补充信息（send_note 2026-08-25）已纳入：① 用户决策者指定 bot 物理模块为最高优先级第一批（与 pathing 软路径同批）；② 事实基线更正为 CLIENT_TEST_FAILED（ab510fd 引入玩家跳跃异常、bot 物理失效自 2298dd3 起、均未修复、无复测记录），§0/§1.1/§1.2/§3.4 关联处/§4.2/§5.3/§5.4 已同步。
- 待办移交：批次划分（§1）、审查模板（§2）、总报告规格（§3）、替换框架（§4）、执行路径（§5）均待监督员审核；审核通过后才可派发批 1 审查执行。
- **本规划不构成实施授权。**