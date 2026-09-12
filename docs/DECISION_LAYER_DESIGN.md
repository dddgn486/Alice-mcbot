# L4 决策层（`GoalDirector` 家族）设计 —— 三条通道 + 分步骨架

> 定位：这是"LLM 只做目标级决策"（项目三条差异之二）的落地设计。
> 前置已就绪：L0–L2 内核（R1–R5）、L3 `Job` 层（J1–J8）、决策层**契约与管道**（D-134/D-135）。
> 知识/配方面的讨论见 [`KNOWLEDGE_RECIPE_GRAPH_NOTES.md`](KNOWLEDGE_RECIPE_GRAPH_NOTES.md)（参考材料）。
> 纪律不变：LLM **提议**，确定性层**处置**；授权/预算/安全/完成判据永远留在确定性层（D-076/D-106）。

## §0 现状（S0 已完成，`WINDOWS_CLIENT`）

| 已有 | 出处 |
|---|---|
| `JobRequest` + `JobLauncher` + `BotManager.assignJob`（"起任意 Job"的唯一入口，含入口发料） | D-134 |
| 终态记录带 `terminalReason`（`quota_met`/`idle_no_work`/`no_reachable_candidate`…）与 `botId` | D-134 |
| `LlmConfig`（`config/alice-llm.json`，**永不打印 key**）+ `LlmClient`（路径矩阵：直连 → 代理 → 中继；裸 TCP 探针；绝不在主线程等） | D-135 |
| `DecisionSnapshot`（只放服务端已知事实）+ `GoalAction`（严格解析、未知即 `Refused`、参数夹取）+ 事件驱动 + 三闸节流 + 看门狗 | D-135 |
| 实测证据：2 次真实决策 + 2 次执行成功 + 事件驱动触发 + 节流/限流 + `terminalReason` 到位 | D-135 附注三/四 |

## §1 目标 / 非目标

**目标**：让玩家用**自然语言**下目标；让 bot 能**自主执行**、**按需汇报**、**关键处请示**；
决策层对**任务树与状态**有权威理解，但**不接触执行细节**。

**非目标**：每 tick 的移动/挖掘决策、预算与授权裁定、安全边界、成为"真相来源"（服务端才是）、
把 LLM 当唯一安全网。

## §2 三条通道（本设计的核心）

| 通道 | 谁发起 | 给 LLM 的输入 | 输出 | 频率 |
|---|---|---|---|---|
| **① 决策** | 事件驱动（终态/危险/空闲/阈值） | **摘要状态 + 任务树摘要 + 候选菜单** | 动作（`start_job(target)`/`stop_current`/`report_status`/`no_op`） | 稀疏、节流 |
| **② 汇报** | **玩家手动问** | 完整事实（确定性组装）+ 事件流水 | 结构化摘要（+ 可选 LLM 措辞） | 手动 |
| **③ 请示** | 确定性层或 LLM 提议 | 结构化请求（能力/理由/选项/期限/默认） | 玩家选择（或超时默认） | 罕见 |

**两条铁律**
1. **事实由确定性层产出，LLM 只负责措辞**（汇报通道）；
2. **选项由确定性层生成，LLM 只负责选择**（决策/请示通道）——不编坐标、不发明能力。

## §3 契约（数据形态，逐条落地）

```
BotStateReport { taskTree[], survival{}, inventory{}, recentEvents[], ledger{}, pendingRequests[] }
TaskNode       { kind, target, phase, ticks, progress, lastFailure, children[] }
BotEvent       { tick, type(DANGER|FAILURE|RECOVERY|COMBAT|MILESTONE), severity, summary, data{} }
CandidateMenu  { entries[{ id, kind, label, distance, costEstimate, missing[], stations[] }] }
GoalAction     { start_job(kind, target=candidateId|region, params) | stop_current | report_status | no_op }
PermissionRequest  { id, botId, capability, reason, options[], deadlineTicks, default, context{} }
PermissionResponse { id, chosen, scope=once|session|always }
CapabilityPolicy   { capability → AUTO | NOTIFY | ASK }（默认值进配置，玩家可改）
```

## §3.1 掉落物归属与收集授权（**D-138 已裁定**，用户 2026-09-12）

> **裁定结果**：`FOREIGN` 默认 **`ASK`**（超时=不捡）；`OURS_INDIRECT` 窗口 **60 tick / 4 格**（可配置 + 进报告）；
> 授权入口 = **选区物品右键** + 命令兜底；**`always` 允许但必须显式标记**；
> **被动拾取默认 `auto`**（只放行 `OURS_*`，`FOREIGN` 转 ASK），另留 `off`/`notify`/`ask`。

**动机**：现在"能捡什么"只认 `ScopeBuffer` 登记过的（我方**破坏事件**配对 / 夹具收养）。
这漏了三类**真实需要**，其中第 1 类用户点名要求：
1. **我方行为的间接后果** —— 砍树后**树叶自然衰减**掉的树苗/木棍；移除支撑后**仙人掌/甘蔗**弹出；
   重力方块摔碎；流体移除后的作物。它们**不是**"我方破坏事件"，但**是我方行为的结果**；
2. **玩家派活** —— "去把那片东西捡了"：目标区域的掉落物可能是玩家丢的/箱子碎的/怪物掉的；
3. （将来）**我方击杀的 mob 掉落**。

同时，**默认谁都捡是危险的**：会捡走玩家故意丢在箱子边的东西、破坏玩家用掉落物做的分类/装饰，
多 bot 后还会互相抢。

### 契约

```
DropProvenance = OURS_DIRECT | OURS_INDIRECT | GRANTED_AREA | FOREIGN
DropPolicy     = provenance → AUTO | NOTIFY | ASK | IGNORE     （默认值进配置，玩家可改）

CollectGrant {  area(region/radius), until(tick|once), provenanceMask, scope=once|session|always,
                grantedBy="player:…"|"config" }
```
- `OURS_DIRECT`（现有配对）与 `OURS_INDIRECT`（**时间窗 + 空间窗**归属）⇒ `AUTO`；
- `GRANTED_AREA`（落在玩家授权区/时段内）⇒ `AUTO`；
- `FOREIGN` ⇒ **默认 `ASK`**（弹窗/聊天问一次，超时=不捡）；可配成 `NOTIFY`（静默捡+事后报告）
  或 `IGNORE`（永不捡）；
- **`anyDrops` 布尔退役**，语义由 `CollectGrant` + `DropPolicy` 覆盖；
- **授权只能由玩家或配置签发**：决策层（LLM）可以**请求** collect，但**不能自己签 grant**；
  要在授权区外动 `FOREIGN` ⇒ 走 S3 请示；
- **每条归属判定都要留证据**（进事件环与报告），例：
  `drop@20,64,208 provenance=OURS_INDIRECT because=owner_window(lumber#12, dt=40tick, d=3.2)`
  —— 便于复盘与标定窗口参数。

### 被动拾取必须同一策略管（用户 2026-09-12 追问："有办法控制 bot 的被动拾取吗"）

**问题**：原版里玩家**走路路过就自动吸附**掉落物 —— 这条路径**绕过** `CollectJob` 的策略，
于是"不派它去捡玩家的东西"会被一次路过直接推翻。

**钩子（已用字节码确认）**：
```
ItemEntity.playerTouch → ForgeEventFactory.onItemPickup(ItemEntity, Player)  ← 可取消的 EntityItemPickupEvent
                       → ForgeEventFactory.firePlayerItemPickupEvent(...)    ← 拾取后通知（不参与拦截）
```
⇒ 用 Forge 的 `EntityItemPickupEvent` **取消本次转移**即可；**不要**用 `ItemEntity.setNeverPickUp()`
（那会改物品状态、连玩家也捡不了）。

**`PickupGate`（S3.5 的一部分，与 `DropPolicy` 同一份判定）**
1. 只对 `BotPlayer` 介入（其他玩家一概不动）；
2. 分类该掉落物的 `DropProvenance`；`AUTO` 放行 / `ASK` 取消并聚合待请示 / `IGNORE` 取消并静默计数；
3. 留证据：`[Pickup] blocked bot=… item=… count=… provenance=FOREIGN reason=policy:ASK`。

**玩家侧档位**（配置 + 命令）：`off`（完全不被动拾取，捡拾必须显式派活）/ `auto`（**推荐默认**：只放行
`OURS_*`）/ `notify`（路过就捡 + 事后一行）/ `ask`（连我方掉落物也问）。

**连带必修**（否则造新病）：
1. `CollectDropsTask` 要把"策略拒绝"与 `pickup_timeout`/`unreachable` 区分开（新增 `policy_blocked`
   计数与终态理由），且**不空转到超时**；
2. `CollectJob` 候选扫描**先按策略过滤**，否则"挑不动还一直挑同一堆"。

**待客户端实测**：取消后物品是否仍留在地上且玩家可捡；`setTarget` 飞行吸附是否同路径；
取消与 `CollectDropsTask` 守恒校验的交互。

### 技术缺口（要动的地方）
1. `ScopeBuffer` 的窗口目前只有**破坏点**（`BreakRecord`）⇒ 要并入**我方放置/拆除点**
   （`WorldModLedger` 已有记录），合成"我方最近动作点集合"，再按窗口给新生成的 `ItemEntity`
   打 `OURS_INDIRECT` 标记（**只登记归属，不判所有权**）；
2. `CollectJob.dropsInRange()` 改为「扫描 → 分类 → 按 `DropPolicy` 过滤」：
   `AUTO` 直接捡、`ASK` 聚合成一条请示、`IGNORE` 跳过；报告/菜单里给出
   "可捡 N 堆（我方 N1 / 授权区 N2 / 待请示 N3）"；
3. 授权入口（零参数优先）：**选区物品右键**（记 pos1/pos2 或半径）为主 + 命令兜底；
4. 窗口参数（时间/空间）**可配置并写进报告**，先给 `60 tick / 4 格` 作为起点，靠实测标定。

### 验证矩阵（四类，都要可测）
| 用例 | 场景 | 期望 |
|---|---|---|
| 1 我方直接 | 我方挖掉方块 | `OURS_DIRECT` ⇒ 自动捡（**已验**，D-137） |
| 2 我方间接 | 砍树后**等树叶衰减**；或移除甘蔗下方沙块 | `OURS_INDIRECT` ⇒ 自动捡（**当前会漏**，需要窗口归属） |
| 3 玩家丢的 | 玩家在不属授权区的地方丢几堆 | `FOREIGN` ⇒ **不捡**（默认 ASK；测试里选不捡或超时） |
| 4 玩家授权区 | 玩家授权某区域后丢/碎 | `GRANTED_AREA` ⇒ 自动捡（含玩家的东西） |

## §4 分步骨架（每步独立可验证；**一次只做一步**）

| 步 | 内容 | 判据 | 依赖 |
|---|---|---|---|
| **S1 事实层** | `TaskNode`/`TaskTree`（Job 暴露子任务摘要）+ `BotEvent` 环形缓冲 + `BotStateReport` 组装 + 零参数入口 `alice:bot_report` | 报告字段齐、与事实一致（对照日志逐字段核）；LLM 措辞不引入新事实（可选开关）；**纯只读** | S0 |
| **S2 选择层** | `CandidateMenu`（附近树/矿、已保存区域、可执行 Job 类型）+ 动作 `target` 只能引用菜单项 + 输入精简（摘要+菜单+任务树摘要） | 复跑"LLM 选了没有树的地方"场景 ⇒ 不再出现；引用不存在的 id ⇒ `Refused` | S1 |
| **S3 请示层** | `PermissionRequest/Response` + `CapabilityPolicy` + 客户端侧边弹窗**与聊天等价入口** + 超时默认拒绝 + `once/session/always` + 审计日志 | "是否允许 bot 自行取工具材料"跑通：批准执行一次；不答 ⇒ 超时拒绝并自动返回 | S2 |
| **S3.5 收集归属** | `DropProvenance` 分类（破坏点 + **我方放置/拆除点**的时空窗口）+ `CollectGrant` + `DropPolicy` + 授权入口（选区物品）；`anyDrops` 退役 | 验证矩阵四类全过（尤其**树叶衰减/仙人掌**这类间接掉落能自动捡，**玩家丢的不捡**） | S3 |
| **S4 事件层** | 阈值（危险 / **工具总耐久** / 卡住 / 无活 / 关键材料不足）只上报**可行动病症**；事件驱动决策接阈值 | 阈值触发一次决策；噪声（树叶清障一类）不上报 | S1 |
| **S5 知识层（只读）** | 游戏内导出运行时配方/JEI 类别 → 离线 Python 建索引 + 逆推规划（材料树/机器需求/top-N 路线） | 与 JEI 人工核对一致；环（铜锭↔铜粉）给出有界不循环路线 | S1（汇报里显示缺料树） |
| **S6 执行层** | `CraftTask`/`SmeltTask` → `ProcessTask`（JEI 类别适配器，先 1–2 种）→ `RoutePreference` 偏好规则 | 库存 0 ⇒ 做出木镐/石镐；一句"矿石先磨粉再烧" ⇒ 计划改道 | S5 |

**顺序理由**：S1 是**事实底座**（汇报与决策都要它）；S2 让决策**不再瞎猜**；S3 是**人类闸门**；
S4 决定**什么时候该开口**；S5/S6 才是"复杂物品"的执行能力 —— 先有地图，再修路。

## §5 与既有层的关系

- **L3 `Job`**：决策层只通过 `JobRequest` 起 Job；Job 的内部阶段通过 S1 的任务树**被看见**，但不被干预。
- **账本/预算**：`WorldModLedger`（J6）与 `WriteBudget`（D-106）继续是唯一写世界闸门；请示通道**不绕过**它们。
- **维生**：危险/逃生（S-1..S-4）仍是确定性层职责；决策层只**被通知**（S4）。
- **多 bot**：`botId` 已进终态记录；S1/S3 的契约都按 `botId` 结构化，为并行留好边界。

## §6 风险与反模式

1. ❌ 把执行细节塞进快照（会诱发 LLM 管执行）→ 只放摘要 + 菜单；
2. ❌ 让 LLM 决定"能不能做"（授权/预算）→ 一律确定性层；
3. ❌ 用请求频率换智能（每 tick 调）→ 事件驱动 + 三闸节流（已实现）；
4. ❌ 让"没批准"变成"卡死" → 每个能力都要有**默认策略**；
5. ❌ 用成功率评价 LLM → 用"玩家少下多少命令 / 请示批准率 / 超时率 / 异常被正确升级的比例"。
