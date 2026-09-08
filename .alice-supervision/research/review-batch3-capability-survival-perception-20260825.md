# 批 3 审查报告：capability / survival / perception 模块（独立业务线三连）

- **任务**：批 3 剩余模块（用户指示一次处理完剩余批次；依据 `full-review-plan-20260825.md` §1.2 批 3、§2 模板、§4/§5）
- **审查基线**：`6c2b461`（HEAD）
- **日期**：2026-08-25
- **性质**：只读审查。未修改任何文件；未运行测试；未连接工具；未触碰 3081/3082/3083。
- **证据方式**：源码行号（capability/ 3 文件、survival/ 4 文件、perception/ 5 文件）、grep 引用、HANDOVER R# 对照。

---

# 第一部分：capability 模块（C1 只读接口扫描）

## 2.1 定位与文件清单

**模块定位**（类注释原意）：capability = "服务端 C1 只读接口扫描器（设计文档 §5「只读接口自动生成」v1）——只读取、不写入；不进行传输/插入/抽取模拟；不推断 per-machine 槽位角色"（InterfaceScanner.java:18-30）。

| 文件 | 角色 | 职责一句话 |
|---|---|---|
| `InterfaceScanner.java` | 扫描器 | 服务端 C1 只读扫描：capability 捕获（物品/能量/流体 + Mek 非通用 legacy 投影）+ format |
| `InterfaceSnapshot.java` | 快照 | 不可变版本化 C1 观测 record（schemaVersion/dimension/pos/blockId/status/items/energy/fluids/legacy） |
| `ObservationStatus.java` | 枚举 | 观测结果状态（OK/NO_BLOCK_ENTITY/CHUNK_NOT_LOADED/CAPTURE_ERROR） |

**注册方式**：静态工具类；被 ScanWand（item 批已核对）+ BotSelftest 调用。

## 2.2 边界归属

- **归属线**：capability = **C1 只读接口扫描线**（S1-S4 已用户验收——规划 §1.2 批 3 明示）；C2-C4 未做边界（不写操作）。
- **相邻边界**：tool（ScanWand 触发入口，item 批已核对）+ command（/alice scan）+ perception（无直接关联）。

## 2.3 功能正确性核查点

| # | 宣称行为 | 代码实现 | 证据类型 | 差异/疑点 |
|---|---|---|---|---|
| C1-1 | 只读采集（不写、不模拟） | capture 只 getCapability 读事实（InterfaceScanner.java:61-68）；无 setStackInSlot/extract/insert 调用 | 静态核对 | **一致**（C1 语义：只读） |
| C1-2 | 未加载 chunk 保守返回 | `!level.hasChunkAt(pos)` → CHUNK_NOT_LOADED + UNKNOWN_BLOCK_ID（:50-53）——**先于任何 getBlockState/getBlockEntity**（:48-49 注释） | 静态核对 | **一致**（不触发 chunk 加载） |
| C1-3 | 捕获值拷贝（防共享可变状态） | "Copy every mutable capability value"（:44 注释）；ItemFact 快照字段非引用 ItemStack | 静态核对 | **一致**（immutable record + 值拷贝语义） |
| C1-4 | 异常兜底 | RuntimeException → CAPTURE_ERROR（:69-73） | 静态核对 | **一致** |
| C1-5 | 快照不可变 | InterfaceSnapshot record + 默认值转换 + List.copyOf（:21-28） | 静态核对 | **一致** |
| C1-6 | Mek 投影标注非通用 legacy | `scanMek` 仅作为显式非通用 legacy 投影（:26,64-65）；注释"不属于 C1 通用事实" | 静态核对 | **一致**（R38 兼容分级相关：识别≠写操作） |

## 2.4 R#/决策对照

| R#/决策 | 内容 | 遵守性 | 证据 |
|---|---|---|---|
| R38（兼容分级） | 识别/发现≠机器写操作；C1 只读 | ✅ 遵守（C1 只读语义 + legacy 标注） | InterfaceScanner.java:18-30,64 |
| S1-S4 验收 | C1 Scanner 用户验收 | ✅ 已验收（规划 §1.2 批 3 记录） | 规划 + A1.1 关联记录 |

## 2.5-2.8（合并）

**Dual-View**：服务端可证（capture 同步读取 + 状态枚举 + 异常兜底）；需客户端实测（扫描结果日志可见性——ScanWand 写 BotLog + 玩家消息，客户端可证）。

**证据等级**：S1-S4 USER_ACCEPTED（验收记录）；本审查静态核对补充。

**风险等级**：**D 维持**（C1 只读语义一致、验证充分、无写路径）。

**替换判据**：无明确成熟候选（Forge capability 原生 API + 自研只读投影）——只列方向不选型。

**边界漂移**：无。

---

# 第二部分：survival 模块（维生硬中断 vs 逃生独立）

## 2.1 定位与文件清单

**模块定位**（类注释原意）：survival = "所有 bot 共用的维生监控入口。**第一版只观察并给出硬中断信号，不主动逃生或改动世界**"（SurvivalSystem.java:12-15）。

| 文件 | 角色 | 职责一句话 |
|---|---|---|
| `SurvivalSystem.java` | 监控 | 统一维生入口：tick/current/shouldInterrupt/interruptionReason + Monitor 状态机（classify/duration/logCooldown） |
| `HazardState.java` | 快照 | 单 tick 不可变危险快照（type/duration/air/health/prevHealth/pos）；dangerous()=LAVA\|SUFFOCATING |
| `HazardType.java` | 枚举 | 危险类型（NONE/WATER_CONTACT/ON_FIRE/LOW_AIR/SUFFOCATING/LAVA_CONTACT） |
| `FluidRiskPolicy.java` | 策略 | 挖掘前保守流体风险检查（只拒可预见岩浆，不自动堵水/挖源头） |

**注册方式**：静态工具；被所有 task 每 tick 调用（MineTask/DropCollectionTask/SoftPath*/FollowTask——批 1/批 3 item 已确认）。

## 2.2 边界归属

- **归属线**：survival = **维生底线线**（所有任务共享的硬中断；逃生独立——R29）。
- **相邻边界**：task（每任务首行 SurvivalSystem.tick）；FluidRiskPolicy（MineTask/BotMiner 挖掘前检查，批 1 确认 fluid_risk_lava）。

## 2.3 功能正确性核查点

| # | 宣称行为 | 代码实现 | 证据类型 | 差异/疑点 |
|---|---|---|---|---|
| S1 | 只观察不逃生不改世界 | tick 只读取（isInLava/getAirSupply/isOnFire 等）；无 setPos/清除方块/teleport | 静态核对 | **一致**（R29：逃生独立由 EmergencyEscapeTask 后续实现） |
| S2 | 硬中断仅 LAVA/SUFFOCATING | shouldInterrupt（SurvivalSystem.java:46-49）+ HazardState.dangerous（HazardState.java:15-16） | 静态核对 | **一致**（R28：水/低空气/着火只记录） |
| S3 | 稳定失败码 | interruptionReason → survival_lava_contact/survival_suffocating（:51-57）——与 TransferCodes.SURVIVAL_* 一致（transfer 审查已核对） | 静态核对 | **一致** |
| S4 | 每 tick 缓存（同 tick 多任务不重复观测） | lastTick/lastState 缓存（:25-27,69） | 静态核对 | **一致**（性能 + 一致性） |
| S5 | 日志节流 | logCooldown 20 tick（:87-91） | 静态核对 | **一致** |
| S6 | 流体风险只拒岩浆 | FluidRiskPolicy 检查目标+相邻六格 LAVA → fluid_risk_lava（:13-24）；含水不拒（R27 注释：不自动堵水/挖源头） | 静态核对 | **一致**（R27/R28） |

## 2.4 R#/决策对照

| R# | 内容 | 遵守性 | 证据 |
|---|---|---|---|
| R27 | 通道/执行层独立否决流体/保护区/非法几何 | ✅ 遵守 | FluidRiskPolicy（挖掘前）+ RoadObstaclePolicy/TunnelObstaclePolicy（批 3 road 已核对） |
| R28 | SurvivalSystem 每 tick 先于任务监测；LAVA/SUFFOCATING 中断；水/低空气/着火只记录；FluidRiskPolicy 不挖源头 | ✅ 遵守 | SurvivalSystem.java:46-57 + FluidRiskPolicy.java:13-24 |
| R29 | 维生只做监测硬中断，不主动逃生/上浮/灭火/切换软移动；逃生由独立 EmergencyEscapeTask | ✅ 遵守（当前无逃生任务——待实现，非违反） | SurvivalSystem.java:12-15 注释明示 | 

**边界漂移**：无。

## 2.5-2.8（合并）

**Dual-View**：服务端可证（classify 逻辑/中断码/缓存/日志节流——可 fixture 断言：置 bot 于岩浆/墙内 → tick → shouldInterrupt=true + code）；需客户端实测（维生中断时的客户端可见行为——bot 停在原地无逃生动画——属"中断后行为"客户端验证）。

**证据等级**：无独立客户端记录（维生中断与任务中断联动已在 A1.1 A6 hazard 场景客户端验收——transfer 审查记录：SUSPENDED + 稳定 code + 不自动恢复）。

**风险等级**：**D 维持**（R27/R28/R29 遵守；无逃生任务属 R29"后续独立实现"的设计内状态，非缺口）。

**替换判据**：无明确成熟候选（维生监测是 Forge 实体状态读取 + 自研状态机）——只列方向不选型。

---

# 第三部分：perception 模块（ScopeBuffer 归属与感知分类）

## 2.1 定位与文件清单

**模块定位**（类注释原意）：perception = "任务运行时的世界状态查询（目标校验、掉落物定位）——Task 构造时注入，即「感知驱动执行」的接线点"（Task.java:11-12）；ScopeBuffer = "任务作用域（以目标为中心注册监听区间，任务生命周期内实时记录新生成掉落物与方块破坏）"（ScopeBuffer.java:16-24）。

| 文件 | 角色 | 职责一句话 |
|---|---|---|
| `ScopeBuffer.java` | 作用域 | 任务作用域事件缓冲：EntityJoinLevelEvent→ItemEntity 捕获（含 itemOrigins 来源格）+ BlockEvent.BreakEvent→brokenBlocks；liveItemsFromOrigin 归属 |
| `PerceptionCategory.java` | 枚举 | 感知分类（待读） |
| `PerceptionClassifier.java` | 分类器 | 方块分类逻辑 |
| `PerceptionProfile.java` | 档案 | 感知档案（MINING/GENERAL + DANGER/TREASURE 方块集） |
| `PerceptionSnapshot.java` | 快照 | 感知汇总（summarize/appendGroup） |

**注册方式**：ScopeBuffer 经 `@SubscribeEvent` 静态全局（ScopeBuffer.java:97,111）+ `ACTIVE` CopyOnWriteArrayList（:33）；其余静态工具。

## 2.2 边界归属

- **归属线**：perception = **感知线**（任务作用域 + 感知分类）；ScopeBuffer 是"挖完捡掉落物"与外部扰动感知的载体。
- **相邻边界**：task（ScopeBuffer 注入 MineTask/DropCollectionTask/RoadBuildTask）；command（PerceptionSnapshot.summarize 供 /alice diagnose 类命令）。

## 2.3 功能正确性核查点

| # | 宣称行为 | 代码实现 | 证据类型 | 差异/疑点 |
|---|---|---|---|---|
| P1 | 作用域按距离过滤 | inScope = distSqr ≤ radius²（ScopeBuffer.java:91-93） | 静态核对 | **一致** |
| P2 | 掉落物记录来源格（归属） | `itemOrigins.put(uuid, item.blockPosition().immutable())`（:103）→ liveItemsFromOrigin（:79-84） | 静态核对 | **一致**（R25 归属语义：只收 origin 掉落物——批 1 task 已核对 DropCollectionTask 使用） |
| P3 | 清理失效掉落物 | removeIf(!isAlive \|\| isEmpty)（:73） | 静态核对 | **一致** |
| P4 | 事件回调在主线程 | @SubscribeEvent（Forge 主线程派发）+ CopyOnWriteArrayList | 静态核对 | **一致**（批 1 bot 审查已确认） |
| P5 | 作用域结束清理 | end() 清 ACTIVE/缓冲（:52-60） | 静态核对 | **一致** |
| P6 | 全局静态派发（多作用域共享事件） | 静态 ACTIVE 列表遍历（:97-120）；注释标注 R7 审查点（多作用域线性过滤） | 静态核对 | **一致**（R7：M3 若扩大再按区块分桶——待评估） |

## 2.4 R#/决策对照

| R# | 内容 | 遵守性 | 证据 |
|---|---|---|---|
| R25 | 采集只认 liveItemsFromOrigin(target) ∩ 锁定 UUID；不要加 allItems 回退 | ✅ 遵守（ScopeBuffer 提供 origin 归属；DropCollectionTask 只收 primaryIds——批 1 task T4/T5 已核对） | ScopeBuffer.java:79-84 + task 报告 |
| R7 | 作用域规模扩大时按区块分桶（M3 评估） | ⏸️ 待评估（当前线性过滤，非违反） | ScopeBuffer.java:26-28 注释 |
| R37-R40 | 产品主线/兼容/计划门 | 🈴 不适用（感知层无 LLM/写路径） | — |

**边界漂移**：无；R7 是设计内待评估项。

## 2.5-2.8（合并）

**Dual-View**：服务端可证（作用域捕获/归属/清理——fixture 可断言：破坏方块后可观察 brokenBlocks、掉落物可经 liveItemsFromOrigin 命中）；需客户端实测（掉落物拾取客户端可见性——A1.1/65f4863 已提供部分客户端证据）。

**证据等级**：作用域行为经 selftest（BotSelftest 内 ScopeBuffer 使用）+ 65f4863 T2（掉落物捕捉日志）服务端 PASS + 客户端部分。

**风险等级**：**D 维持**（职责清晰、归属语义符合 R25、事件主线程）；R7 分桶待 M3 评估（非风险）。

**替换判据**：无明确成熟候选（Forge 事件 + 自研作用域缓冲）——只列方向不选型。

---

## 综合风险/替换/边界

- **三模块全部 D 维持**：capability（C1 只读 USER_ACCEPTED）、survival（R27-R29 遵守）、perception（R25 归属 + 主线程作用域）。
- **无预定替换结论**；无修改文件（git status 仅新增本报告）。

## 与 active plan 冲突声明

- survival 的维生中断与 p1 线无交叉（维生是任务层中断，P1 是同步层）；perception/capability 与 p1 线无交叉。若监督员需引用维生中断/作用域证据到 P1 线调查，写入 active plan Research Decision。

## 证据分级汇总

- **已确认事实**：12 文件职责与行号；C1 只读语义/未加载保守/异常兜底；维生 R27-R29 遵守/硬中断码/缓存/日志节流；流体风险只拒岩浆；ScopeBuffer R25 归属/主线程/清理；R7 待评估。
- **架构推论**：无功能缺陷推论（三模块与设计/R# 一致）。
- **待调查**：R7 按区块分桶（M3 评估）；维生中断后的客户端行为矩阵；capability 扫描结果客户端日志可见性。

---

**本报告不构成实施授权**；审查未修改任何文件（业务代码/HANDOVER/active plan/skill/客户端记录均未动），未运行测试，未连接任何工具或 endpoint。替换提案、改动均需监督员审核 + 用户批准。
---

## 监督员验收记录（2026-08-25，Architecture Supervisor）

**验收结论：✅ 通过**（本报告由调查员在 bus 投递故障期间以 send_note 交付；监督员独立核查后验收）

独立核查要点：关键代码引用（源码 grep 核实）、R# 遵守性、只读性（src 零改动、HEAD 仍 6c2b461）、无预定替换结论、已确认/推论/待调查分级。验收通过。
