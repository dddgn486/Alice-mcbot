# 授权 / 审批框架总览（**自动生成**，勿手改）

> **单一出处**：`docs/authz/AUTHZ_REGISTRY.csv`（Excel 可直接打开、批注；改它再跑 `bash tools/authz-map.sh`）
> 生成时间：2026-09-14 11:18 ｜ 闸门 27 条

## 四问速查（唯一需要背的东西）

1. **这一步改世界吗？** 不改 ⇒ 只走 L2（Movement 集合 + 物理前提 + 可逆性）。
2. **改世界呢？** L1 谁授权/为什么（WriteGrant）→ L3 还有预算吗（WriteBudget）+ 执行期复验（CapabilityGate）→ L4 记哪种账（TEMP 必拆 / KEEP 不拆）。
3. **要拆吗？** 只拆自己放的、自上而下、材料回收（RestoreScopeTask；悬空桥面走侧拆兜底）。
4. **LLM 能自己决定吗？** 不能：只能提目标；拒绝走 Refused；未知模组能力**问用户**（权限契约）。

## 六层一览

| 层 | 闸门数 | 这一层在回答什么 |
|---|---|---|
| L0 目标层 | 3 | LLM 能提什么目标、谁能越权直连、能力未知时问谁 |
| L1 请求层 | 5 | 这次请求带什么策略/预算/凭证（**策略选择层**） |
| L2 规划期 | 4 | 这条边在物理与可逆性上**合不合法**（硬校验，不合格直接拒） |
| L3 执行期 | 5 | **用当前世界事实复验** + 记下授权写入（事实可能已变） |
| L4 收尾期 | 5 | 记什么账、拆什么、材料回不回收（闭环） |
| L5 验证层 | 5 | 怎么**自证**夹具/探针的前提与零写入 |

## 闸门明细

### L0 目标层

| id | 类型 | 闸门 | 触发 | 拒绝码 | 代码位置 | 默认 | 谁能放开 |
|---|---|---|---|---|---|---|---|
| `L0-1` | 合约 | LLM 只能提目标（解析期合约校验） | action/kind/target 必须来自候选清单 | refused（26 处） | `decision/GoalAction.java` | 无（LLM 不能自授权） | 无（红线） |
| `L0-2` | 审批 | 操作员直连通道 /alice instruct | 仅操作员显式指令（唯一 directed） | mode=directed | `decision/GoalDirector.java; command/BotCommand.java` | 关闭 | 操作员本人 |
| `L0-3` | 审批 | 未知模组能力 → 权限契约（问用户） | 能力未被上游自述/未验证 | CAPABILITY_*（闸门侧） | `permission/*；/alice ask` | 默认只读 | 用户回答 |

### L1 请求层

| id | 类型 | 闸门 | 触发 | 拒绝码 | 代码位置 | 默认 | 谁能放开 |
|---|---|---|---|---|---|---|---|
| `L1-1` | 策略 | allowedMovementTypes（每请求显式声明） | 每次规划请求 | — | `pathing/core/search/PathRequest.java:35` | 纯通行 4 类（TRAVERSE/DIAGONAL/ASCEND/DESCEND） | 任务调用点 |
| `L1-2` | 策略 | miningApproach 显式禁用 PILLAR/FALL/DOWNWARD | 挖掘站位请求（D-067㉘） | — | `PathRequest.java:56,91` | 禁用（待 R3 改按条件放行） | 改代码（须 A/B 证据） |
| `L1-3` | 预算 | SearchBudget（搜索能烧多少） | 搜索节点/时间上限 | SEARCH_LIMIT | `pathing/core/search/SearchBudget.java` | 默认 UNLIMITED | 调用点 |
| `L1-4` | 凭证 | WriteGrant(requester, reason)（D-082） | 任何破坏/放置；指向“这一格/这一次” | 无凭证不可写 | `action/WriteGrant.java（16 种 WriteReason；30 个调用点）` | 无凭证拒写 | 任务/Job 调用点 |
| `L1-5` | 预算 | MiningBudget（这次挖掘值得拆多少） | 挖掘站位选点 | — | `mining/MiningBudget` | 按任务设定 | 调用点 |

### L2 规划期

| id | 类型 | 闸门 | 触发 | 拒绝码 | 代码位置 | 默认 | 谁能放开 |
|---|---|---|---|---|---|---|---|
| `L2-1` | 硬约束 | Movement 校验器（物理前提，planning 期拒绝） | 每条候选边构造/执行前 | 116 码：BREAK20 PLACE18 FALL15 PILLAR11 ASCEND11 DESCEND10 DOWNWARD8 DIAGONAL8 TRAVERSE6 | `pathing/core/*ExecutionFactory.java` | 不合格即拒 | 无（物理事实） |
| `L2-2` | 硬约束 | RecoverabilityPolicy：FALL 必须带 fall_return_verified | 构造 MovementSpec 时比较“提供 vs 要求” | 规划期抛异常（不静默降级） | `core/RecoverabilityPolicy.java` | 不足即炸（唯一真能拒的一条） | 无（可逆性） |
| `L2-3` | 硬约束 | IntrinsicReversibility 三档 | 每种 Movement 的固有可逆性 | — | `core/IntrinsicReversibility.java` | REVERSIBLE / CONDITIONALLY / NOT | 无 |
| `L2-4` | 策略 | RiskSwitches（默认全关＝Baritone 高风险） | /alice risk 或未来评估器（未实现） | — | `pathing/risk/RiskSwitches.java` | 全关；当前仅 descend_overshoot | 用户 / 评估器（未实现） |

### L3 执行期

| id | 类型 | 闸门 | 触发 | 拒绝码 | 代码位置 | 默认 | 谁能放开 |
|---|---|---|---|---|---|---|---|
| `L3-1` | 硬约束 | CapabilityGate 执行期复验（基-8） | 执行前用**当前世界事实**复验 | CAPABILITY_UNAUTHORIZED / ZONE_PROTECTED_AREA·BLOCK·TAG / NO_THROWAWAY_BLOCKS / NO_REQUIRED_TOOL / BREAK_BUDGET_EXHAUSTED / PLACE_BUDGET_EXHAUSTED | `core/CapabilityGate.java` | 会改世界的 Movement 必须过 | 世界事实（保护区/资源/工具/预算） |
| `L3-2` | 硬约束 | MovementCapabilities 12 分量（能力声明） | 声明“会改世界/需授权/耗资源/需工具” | — | `core/MovementCapabilities` | 声明即受检 | 无 |
| `L3-3` | 预算 | WriteBudget（D-106：作用域内写入次数上限） | 累计写入次数（清障是“有多少拆多少”） | WRITE_*（5 码） | `action/WriteBudget.java` | 按 scope 上限 | 调用点 |
| `L3-4` | 凭证 | WriteAudit（记录“Alice 授权自己做了什么”） | 每次授权写入 | — | `action/WriteAudit.java` | 环形缓冲 + 日志 | — |
| `L3-5` | 硬约束 | BlockBreakSession / BlockInteraction | 破坏时序与可破坏判定 | — | `action/BlockBreakSession.java` | 必须可破坏 | 工具 / 方块属性 |

### L4 收尾期

| id | 类型 | 闸门 | 触发 | 拒绝码 | 代码位置 | 默认 | 谁能放开 |
|---|---|---|---|---|---|---|---|
| `L4-1` | 账本 | WorldModLedger：TEMP 必须配对拆除 / KEEP 不该拆 | 每次放置入账（**只记放置，不记破坏**） | — | `ledger/WorldModLedger.java:40-45` | TEMP 必拆 | 调用点（**待 R1 集中策略表**） |
| `L4-2` | 硬约束 | 只拆自己放的（比对账本，不匹配即销账）+ 不许沿途挖地形 | 恢复任务 | not_ours | `task/RestoreScopeTask.java` | 强制 | 无 |
| `L4-3` | 机制 | 两条拆除路径：①站上去向下拆（DOWNWARD）②侧拆兜底（SIDE_BREAK） | ①下方有支撑 ②悬空桥面/走不到正上方 | restore_done / restore_partial / nothing_to_restore | `task/RestoreScopeTask.java` | 按世界事实选路 | — |
| `L4-4` | 闭环 | 物质闭环：拆除掉落物回收（ScopeBuffer + CollectDropsTask） | 拆除后掉落物 | — | `task/RestoreScopeTask.java` | 必须回收（D-099 教训） | — |
| `L4-5` | 账本 | ScopeBuffer：世界**实际发生**了什么（含外部玩家） | Forge 事件 | — | `ledger/ScopeBuffer` | 与 WriteAudit 交叉校验 | — |

### L5 验证层

| id | 类型 | 闸门 | 触发 | 拒绝码 | 代码位置 | 默认 | 谁能放开 |
|---|---|---|---|---|---|---|---|
| `L5-1` | 自证 | 电池步（CORE 27 / FULL 37；CURATION 唯配置入口） | 每个自检任务 | PASS / FAIL / SKIP | `task/RegressionBatteryTask.java; docs/BATTERY_CURATION.md` | 分档唯一出处 | — |
| `L5-2` | 自证 | FixturePremise 前提断言 | 夹具关键点 | premise_own_menu / station_menu_open / on_ground / no_observer | `task/FixturePremise.java` | 前提不成立即红 | — |
| `L5-3` | 自证 | 夹具自带传送 + 结束复位（§5.0d） | 场景夹具 | reset=true | `PLAYBOOK §5.0d` | 强制（失败路径同走） | — |
| `L5-4` | 自证 | 零写入自证 | 探针 / 只读夹具 | no_writes=true / 背包逐槽未变 | `task/MachineStationProbeTask.java 等` | 强制 | — |
| `L5-5` | 自证 | 负例前提运行时自证 | 无配方负例等 | no_recipe / firstUnproducibleItem | `task/CraftCheckTask.java` | 强制（写死会过期） | — |

## 其它视图

- 流程图：`docs/authz/flow.svg`（浏览器直接打开）
- 可搜索页面：`docs/authz/index.html`（内嵌流程图 + 完整表格）
- 文本版流转：`bash tools/authz-map.sh` 的 stdout 摘要
