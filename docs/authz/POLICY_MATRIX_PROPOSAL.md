# R1 集中策略表 —— 设计提案（**未接线，待拍板**）

> 依据：`AI_DECISIONS.md` D-207 ①（区域×任务类别 → `TEMP/KEEP` + 允许 Movement 集合 + 预算上限；默认 `PROTECTED`、
> 显式降级到工作区；**不新增任务级授权开关，`WriteGrant` 一行不改**）与 `docs/reviews/2026-09-14-外部质疑与工作流审查留档.md` §3
> （"工作区免 RESTORE、保护区必须回收 ＝ 策略表的 `TEMP/KEEP`"）。
> 状态：**已拍板并接线**（2026-09-14）。§1–§3 是**当时的**勘察与设计（保留原样作为来龙去脉），
> §4 的三个问题已答复，**§5 记录接线时纠正的三处术语错误与最终形态**——以 §5 为准。

## 1. 现状清点（今天各维度由谁决定；行号取自当前 master）

| 维度 | 今天的决定者 | 位置 | 备注 |
|---|---|---|---|
| `TEMP/KEEP` | `WriteReason.temporary()` —— **只有 3 个值算 TEMP**：`STEP_PLACEMENT`/`SUPPORT_PLACEMENT`/`CRAFT_STATION_PLACE` | `action/WriteReason.java:138-141` | 被 `ledger/WorldModLedger.java:126` 读取（`grant.reason().temporary()`）；`REGION_REPLANT`/`BULK_EDIT`/`MANUAL` = KEEP |
| 区域保护 | `SafeZoneData.protectionReason(level,pos)` → `protected_area`/`protected_block`/`protected_tag`，无命中返回 `null` | `protection/SafeZoneData.java:137` | **只有"禁止"语义，没有"工作区"概念**；快照实测 `areas=0 blocks=0 tags=0` |
| 允许 Movement 集合 | `PathRequest` 命名工厂：`of`(:35) / `withWorldModification`(:43) / `miningApproach`(:59) / `scaffoldRemoval`(:80) / `climbApproach`(:98) / `pureTraversal()`(:119) | `pathing/core/search/PathRequest.java` | `miningApproach` **整类禁用** `PILLAR/FALL/DOWNWARD`（D-067㉘，见 :91 注释）⇒ R3 要改成按条件放行 |
| 预算上限 | `WriteBudget.Caps`：`DEFAULT_MAX_BREAKS=64` / `PLACES=32` / `CONTAINER=32`；`setCaps` 仅夹具可用 | `action/WriteBudget.java:44-48`、`task/WriteBudgetCheckTask.java:101` | 无作用域 ⇒ 闸门未生效（D-106 附注，2026-09-14） |
| 挖掘预算 | `MiningBudget.DEFAULT_MULTIPLIER=10.0` / `FALLBACK_ORDINARY_TICKS=6.0` | `task/mining/MiningBudget.java:25,27` | 与 `Caps` 不同维度（时间/代价，不是次数） |
| 授权凭据 | `WriteGrant(requester, reason)` | `action/WriteGrant.java`（D-082） | **本提案不动它**；requester 形如 `pathing-regression:attempt1:STEP_PLACEMENT` |
| 账本 | `WorldModLedger`：**只记放置**，`Policy.TEMP` 必须配对拆除 / `KEEP` 不该拆 | `ledger/WorldModLedger.java:126,211,221,280` | 夹具"零写入自证"依赖它（D-207 驳回项⑤） |

**结论**：`TEMP/KEEP`、Movement 集合、预算上限**各自分散在不同类里**，且**没有"区域×任务类别"这个二维**——
这正是 R1 要补的：把三个维度收进一张表，默认列（`PROTECTED`）保持今天的保守行为，工作区靠显式声明降级。

## 2. 提议的表（单一出处，仿 `docs/authz/AUTHZ_REGISTRY.csv` 的模式）

`docs/authz/POLICY_MATRIX.csv`，列：

| 列 | 含义 |
|---|---|
| `id` | `P-xx` 稳定编号 |
| `zone_class` | `PROTECTED`（默认，含 `SafeZoneData` 命中区）/ `WORKSPACE`（显式声明的工作区）/ `TRANSIT`（纯通行） |
| `task_class` | `TRAVERSAL` / `MINING` / `GATHERING` / `LUMBER` / `CRAFT` / `CONTAINER` / `BUILD` / `DIAGNOSTIC` |
| `placement_policy` | `TEMP`（必须回收）/ `KEEP`（免回收）/ `DENY`（该类任务在此区域**不得放置**） |
| `allowed_reasons` | 允许的 `WriteReason` 集合（**白名单**，其余规划期拒绝） |
| `movements` | 允许的 `MovementType` 集合（命名集合，如 `PURE` / `MINING_APPROACH` / `WILD`） |
| `max_breaks`/`max_places`/`max_containers` | 该组合的预算上限（缺省 = `Caps.DEFAULT`） |
| `code_ref` | 执法点（类:方法） |
| `note` | 例外与理由 |

执法（两层，防绕过）：**规划期**按表拒绝非法组合（像 `RecoverabilityPolicy` 那样**抛异常、早失败、可归因**）
+ **执行期**在 `WriteBudget`/`CapabilityGate` 已有关口上复验（防规划期之后世界变化或调用点绕过）。

配套（照搬 authz 那套，已验证好用）：生成器 `tools/policy-map.py` → `docs/authz/POLICY_OVERVIEW.md`；
检查脚本 `tools/check-policy-matrix.sh` 断言"表 ↔ 代码覆盖"（每个 `WriteReason` / `MovementType` / 任务类别都有行归属，
无孤儿行）。**接线后的验收 = `CORE 27` 全绿**（证明默认列没有行为回归）。

## 3. 与 R2/R3 的边界（不在本轮）

- **R2**：野外采集/伐木默认集加入 `PILLAR/FALL/DOWNWARD`；
- **R3**：`miningApproach` 的整类禁用改按条件放行。
两者**都必须先有 A/B 客户端证据**（D-207 ②）⇒ 本轮只把表的**结构**和**默认列**落地，`movements` 列填今天的集合。

## 4. 待你拍板（三个问题）

1. **`zone_class` 的来源**：只把"已保存区域（`region:*` 任务）"算 `WORKSPACE`，还是允许一条玩家命令**显式标记**
   任意区域为工作区？（前者零新入口、更保守；后者灵活但新增一个写入授权面）
2. **工作区里 `TEMP→KEEP` 的边界**：ⓐ "工作区内一切放置都免回收"，还是 ⓑ "工作区只是**允许** `KEEP` 类 reason
   （如补种），其余仍 `TEMP`"？我建议 **ⓑ**：不改 `WriteReason.temporary()` 的语义、不削弱"建拆同权"的可回收性保证，
   且与今天行为差异最小。
3. **执法位置**：规划期抛 + 执行期复验（我建议）还是只做执行期？（只做执行期会导致失败晚、归因差，但代码面更小）

另外 D-207 ① 里还有一条独立小项：**`UNKNOWN` requester 记为错误**——今天 `WriteGrant` 的 `requester` 是自由字符串，
需要确认"未知"的判定口径（空/`unknown`/未注册前缀），可以在接线时一并做，请一并确认。

## 5. 接线结果（2026-09-14）——§1–§4 里的三处术语错误已纠正

### 5.1 用户答复

| §4 问题 | 答复 |
|---|---|
| 语义冲突：矩阵管"回收义务"(A) 还是"写世界资格"(B) | **A** —— 上层显式授权（`BULK_EDIT`/`MANUAL`）不受默认区约束；不新增拒绝面、不误拆玩家/道路方块 |
| ① `WORKSPACE` 来源 | **只认玩家已划定的区域**（今天 = `LumberRegionState`），不新增命令入口 |
| ③ 执法位置 | **规划期抛异常 + 执行期复验**（两层） |

（§4 里的第 2 个问题"工作区里 `TEMP→KEEP` 的边界"取 ⓑ：工作区只是**允许** KEEP 类理由，
不改其余理由的回收义务——因此**今天两区解析逐条相同**，`zoneDiff=0` 由自检断言守着。）

### 5.2 接线时纠正的三处术语错误（用户质疑触发，2026-09-14）

1. **`PROTECTED` 不能当默认区类的名字**：项目里"保护区"= `SafeZoneData` 命中 ⇒ **禁止破坏**
   （`protection/BlockBreakSafety.java:47`、`action/BlockInteraction.java:462`、`pathing/core/CapabilityGate.java:71`、
   `road/RoadObstaclePolicy.java:42`）；而默认区**允许**破坏（挖矿/清障/脚手架都在里面）。**同名反义**必致误读。
   ⇒ 默认区改名 **`EXTERNAL`**（不是 Alice 的地）。
2. **`TRANSIT` 是维度混淆**：通行是 **`PathRequest` 的属性**（D-076 默认纯通行；`of:35` vs 降级 `pureTraversal():119`），
   不是**地块的属性**；塞进区域分层等于给同一概念造第三个同义词。
   ⇒ **保护区不占"归属"这一列**：它是优先级更高的**独立闸门**（与归属正交）。区域归属**只剩两层**：
   `EXTERNAL` / `WORKSPACE`。
3. **`WILD` 是拿"场合"冒充"能力"**：移动集的本质是 `PathRequest.java:110-118` 写清的那条轴——**允不允许写世界的原语**。
   ⇒ 词表**直接用工厂名**（`of`/`pureTraversal`/`miningApproach`/`scaffoldRemoval`/`climbApproach`/`withWorldModification`），
   且 `MovementGrant.types()` **直接调用工厂**取集合 ⇒ 表与工厂**在定义上不会漂移**。
   R2 要给野外采集/伐木放开 `PILLAR/FALL/DOWNWARD`：**新增显式工厂 + 在 `WORLD_WRITE_AUTHORIZATION.md` 登记**，
   不造 `WILD` 这种词（名字 = 授权面）。

### 5.3 最终形态

- **真源**：`src/main/java/com/dddgn/alice/action/WritePolicyMatrix.java`（22 行 = 2 区 × 11 任务；含 `movements`/`reasons`/义务/出处）
  ——**代码是唯一的表**（Forge 模组运行期读不到仓库 docs，这一点决定了方向与 `AUTHZ_REGISTRY.csv` 相反）。
- **视图**：`docs/authz/POLICY_MATRIX.csv`（由 `tools/policy-map.py` **从源码生成**，不要手改）；
  **断言**：`bash tools/check-policy-matrix.sh` ⇒ `POLICY_MATRIX_CHECK_RESULT PASS`
  （全枚举 / 无孤儿理由 / 无孤儿授权 / 工厂词表完整 / 24 个 requester 字面量可归类 / 视图不过期）。
- **两层执法**：规划期 `CorePathPlanner.plan`（越权抛 `WRITE_POLICY_MOVEMENT_DENIED`，在规划器入口转成如实失败的 plan）、
  执行期 `WorldModLedger.recordPlacement:126`（回收义务）。
- **自检**：`task/WritePolicyCheckTask`（电池步 `write_policy`，BASELINE）——含**负例**（`walk-to` + `withWorldModification`
  必须被拒）与 `zone_equiv`（两区今天必须逐条相同）。
- **登记表实测补全**（接线时逐个 grep 出来的真实 requester，都会掉进 `UNREGISTERED` 的）：
  `mine`（`MineJob.NAME`）、`region_lumber`（`RegionLumberJob.NAME`）、`PlaceTask`（`Task.taskName()` 默认 = **类名**，
  `task/Task.java:42`）、`scaffold-lifecycle`、`partial_*`、`ToolMaintenance`。
- **R1b（未做）**：把 `WORKSPACE` 来源接到已划区域（`installZoneSource` 挂点已留，今天恒 `EXTERNAL`）；
  预算档位列本**未**引入（今天没有"按行不同"的证据，`WriteBudget.Caps.DEFAULT` 仍是唯一真源）。
