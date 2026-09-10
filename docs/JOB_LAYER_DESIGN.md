# L3 目标级任务层（Job）设计 —— 伐木作为第一消费者

> 状态：**设计草案 v1**，待用户裁定 §9 的 5 项后转实施。
> 定位：这是 [D-073](AI_DECISIONS.md) 立的「伐木专项」落地设计，也是"决策层"的第一块地基。
> 前置裁定（2026-09-10，用户同意）：**新增 `Job` 层组合现有 `Task`，不扩展 `Task` 契约**（L2 已验收，保持不动）。

## §0 结论摘要

1. **缺口是 L3（目标级编排），不是 LLM。** `Task` 契约只支持"单目标单发"，高级任务无处安放——旧伐木 1037 行就是两个任务各造一套循环。
2. **L3 只做四件事**：选目标 → 生成子任务 → 记账 → 终止。移动/挖掘/收集**全部复用已验收的 L2**。
3. **决策缝三件套**：`CandidateSource`（候选从哪来）+ `SelectionPolicy`（选哪个，带理由）+ `DecisionTrace`（可判读）。第一版就有 **2 个真实策略** + **2 个真实候选源**（树 / 挖矿），不产生空抽象。
4. **完成判据 = 产物入包**（原始设计 §4.2 标准 3），用背包增量，与 `CollectDropsTask` 同口径。
5. **验收标准要升级**：L3 必须新增三类断言——**决策可判读 / 不变量 / 终止与恢复**；现有「场景 + SUMMARY」只能验收 L2。

---

## §1 目标与非目标

**目标**
- 让"目标"成为一等公民：一个 `GoalSpec`（配额 + 终止条件 + 范围）驱动一个 `Job`，`Job` 内部多步推进直到达成或**如实终止**。
- 让"选哪个"可判读、可断言、可替换（rule-based → 将来 LLM 用同一接口）。

**非目标（本轮明确不做）**
- ❌ LLM 接入（只留 `SelectionPolicy` 接口，不实现任何模型调用）
- ❌ 风险画像 / 维生"去向"（属横切，见 §10；它们需要 L3 作为消费者才不变成死抽象）
- ❌ 多 bot 并行、生产流水线（AE）、战斗
- ❌ 攀爬超高的树（v1 拒绝并给理由，见 §9-3）

---

## §2 现状证据与教训

### 2.1 `Task` 契约是单目标单发

```java
public interface Task {
    TaskTarget target();                    // ← 一个目标
    Status tick();
    String failureReason();
    enum Status { RUNNING, DONE, FAILED }   // ← 没有"进度/配额/子目标"
}
```

`BotManager` 有 ~20 个 `assignXxx` 入口，**全部是"单目标单发"**；`decision` 包只有 78 行的 `AutoMineDecision`，且只被 `/alice auto-mine <tag>` 一条命令调用（任务层无法复用）。

### 2.2 旧伐木代码的教训：六件事被两个任务各写两遍

被 D-073 删除的 `ContinuousLumberTask`（531 行）与 `RegionLumberTask`（505 行）：

| 职责 | Continuous | Region | **新归属（本设计）** |
|---|---|---|---|
| 目标选择 | `handleScanning` + `findTreeBase` + `countNearbyLogs` | `scanTrees` + `selectNextTree` | **L3**：`CandidateSource` + `SelectionPolicy` |
| 树识别 / 树模型 | `TreeDetector` / `Tree` / `TreeType`（两者共用） | 同 | **领域模块**：`TreeScanner` / `Tree`（保留并修正，见 §5.1） |
| 状态机 | 自带 `Phase{SCANNING,CUTTING,COLLECTING}` | 自带 `Phase` 枚举 | **L3**：`Job` 单一状态机 |
| 移动 | 藏在 `BotMiner` 内 | **`moveTowards`（自制直线移动）** | **复用 L2/L1**：`MineTask` / `WalkToTask` |
| 视线清障 | `clearDepth` + `MAX_CLEAR_DEPTH=3` | `findLeafBlocker` + `clearDepth` | **动作层限次能力**（§5.4） |
| 收集掉落物 | `handleCollecting` + `collectTicks=40` | `trackNearbyDrops` + `handleCollecting` | **复用 L2**：`CollectDropsTask` |
| 记账 / 终止 | 自带计数 | `treesCut` + `startTime` | **L3**：`GoalProgress` |

> 结论：旧代码不是"写得烂"，而是**没地方放**。本设计让每件事**各归其位、只写一遍**；`Job` 只剩"决策 + 记账 + 终止"。

### 2.3 一处文档债（本轮发现）

`docs/MINE_MIGRATION_DESIGN.md:126` 仍写着「清障：保留 `MAX_CLEAR_DEPTH = 2`（视线直接遮挡 → 挖掉遮挡块）」，
但**现行实现没有独立清障**：`MineTask` javadoc 明确"不再有独立清障：挡路方块由规划器的模式 B 处理"（D-071），
`MineBlockRunner` 遇遮挡只报 `LINE_OF_SIGHT_BLOCKED`（可重试）→ 重规划 ≤2 次 → 升级失败。
建议随本设计一并更正为「清障归 L3 Job，限次 + 预算内（§5.4）」。

---

## §3 架构

```
玩家命令 / 未来的 LLM                    ← 目标来源（本轮只有玩家）
        │  GoalSpec(配额 + 终止 + 范围 + 预算)
        ▼
   Job（L3）  ── 选目标 → 生成子任务 → 记账 → 终止
        ├── CandidateSource   （TreeScanner / MineScanner）
        ├── SelectionPolicy   （NearestPolicy / NearestExposedPolicy / 将来 LLM）
        └── DecisionTrace     （机器可判读）
        │  单个子目标
        ▼
   现有 Task（L2，**零改动**）：MineTask / CollectDropsTask / WalkToTask / PlaceTask
        │
        ▼
   L1 寻路内核 + L0 动作原语（已验收）
```

**接入方式（最小改动）**：`Job implements Task`。
`BotSession.beginTask(Task, TaskTarget)` 已存在 → **L3 接入不需要改 `BotManager`/`BotSession` 的既有调度**。
唯一需要的 5 行改动：`BotSession.tick()` 里比较 `task.target()` 与 `session.target`，变化时更新并 `broadcastTarget`（子目标高亮跟随）。

---

## §4 契约

```java
// ── 目标规格：一次 Job 的全部外部输入 ──
public record GoalSpec(
        Kind kind,                 // COLLECT_ITEMS | HARVEST_UNITS | UNTIL_FULL
        int quota,                 // 配额（COLLECT_ITEMS=物品数；HARVEST_UNITS=棵/块数）
        BlockPos center, int radius,
        int maxTicks,              // 硬上限（必须存在：禁止空转）
        boolean stopWhenFull,      // 背包满即停（计入 DONE）
        TagKey<Item> productTag    // 产物判定（伐木 = minecraft:logs）
) {}

// ── 候选 ──
public record Candidate(BlockPos anchor, String describe, Map<String, Object> features) {}

public interface CandidateSource {
    List<Candidate> candidates(ServerPlayer bot, GoalSpec spec);
}

// ── 选择：返回 null = 全部拒绝（必须带理由集）──
public record Selection(Candidate picked, String reason, List<String> rejected) {}

public interface SelectionPolicy {
    Selection select(ServerPlayer bot, GoalSpec spec, List<Candidate> candidates);
}

// ── Job ──
public interface Job extends Task {
    String progressSummary();     // "原木 4/8 棵 1/3"
}

// ── 决策可判读（统一出口，不新建日志框架）──
public final class DecisionTrace {
    static void selection(String job, Selection selection, List<Candidate> all);
    static void progress(String job, String phase, String detail);
    static void terminal(String job, String result, String reason, String progress);
}
```

**日志格式（机器可判读，约定死）**：

```
[Job] select job=lumber picked=tree@12,64,8 reason=nearest d=3.2 exposed=true logs=4
      candidates=3 rejected=[tree@10,64,9:no_stand,tree@18,64,14:trunk_too_tall]
[Job] step   job=lumber target=12,64,8 phase=CUT log=1/4
[Job] terminal job=lumber result=DONE reason=quota_met progress=logs 4/4 ticks=210
```

---

## §5 伐木领域设计

### 5.1 树识别（`TreeScanner`）——保留旧知识，修正三个弱项

**保留**：连通性 BFS 向上找原木、基座 = 最低原木、`getLogsInCutOrder()` 按 y 升序。

**修正**（旧 `TreeDetector` 的已知弱项）：

| # | 旧实现 | 问题 | 新实现 |
|---|---|---|---|
| 1 | 硬编码 8 种 `Blocks.OAK_LOG…` | 模组原木一律识别不出 | 用 `BlockTags.LOGS`（模组友好，符合"未知模组默认只读"原则） |
| 2 | BFS 只搜"上行 3×3 + 正下方" | **2×2 深色橡木 / 红树气根会被拆成多棵** | 增加**同层水平 4 向**邻居；仍只向下 1 格（避免连到玩家建筑） |
| 3 | `isValid()` 要求 `logs ≥ 4 && !leaves.isEmpty()` | 小树、被砍过一半的树**判不出**；且 5×5×5 逐原木扫叶会**串到邻树的树冠** | **不再要求有树叶才算树**；树叶只作**暴露度特征**，且扫描有上限 |
| — | 无上限 | 玩家搭的原木墙会被当成"巨树" | `maxLogs`（默认 64）超出即不算树（记 `too_large`） |

`Tree` 字段：`basePos`、`logs`（y 升序）、`species`（按原木方块映射，未知=UNKNOWN）、`trunkHeight`、`columnCount`、`hasCanopy`。

### 5.2 可达性 = "能砍完"的可行性（v1 判据）

**不做**"能不能走到树旁边"，而做**"这棵树能不能被完整砍掉"**：

1. 从任一**可站立位置**（`StandingPointSelector` 口径）出发，对每个原木要求
   `眼位 → 可见面采样点 ≤ reach`（与 `MineBlockRunner` 运行期同口径，含 `reachMargin`）；
2. 允许**自下而上**推理：砍掉下方原木后，脚下地面不变 → 上方原木的可达性不变（v1 只做这一步，不做"站到树干缺口里"这种高级推理）；
3. 全部满足 → 候选可行；否则拒绝并给理由码。

**理由码表**（进 `DecisionTrace.rejected`）：

| 码 | 含义 |
|---|---|
| `no_stand` | 周围没有可站立位置 |
| `trunk_too_tall` | 最高原木超出触及（超出 reach）——**v1 范围限制，非永久结论**，见 §11-① |
| `los_blocked_permanent` | 视线遮挡且遮挡物不可破坏/超清障预算（§5.4） |
| `protected` | 落在保护区（`SafeZoneData`，与 `AutoMineDecision` 同口径） |
| `too_large` | 超过 `maxLogs`（疑似玩家建筑） |
| `not_nearest` | 该策略下的排序劣势（**保留在 trace 里，用于解释"为什么不选它"**） |

### 5.3 砍伐顺序与子任务分工

- 顺序：`logs` 按 y 升序（**自下而上**，与旧 `getLogsInCutOrder()` 一致）；
- 每个原木 = 一个 **`MineTask` 子任务**（复用已验收的规划→走位→破坏→收集）；但**收集不逐块做**：
  - 子任务只负责"破坏这个原木"（`MineBudget.collectDrops=false`，D-070 已有该参数）；
  - 整棵树（或整轮）砍完后由 Job 起**一次** `CollectDropsTask`（簇级 + 守恒校验）→ 避免"挖一格捡一次"（旧代码 `collectTicks=40` 每棵都等一次，是慢的主因之一）；
- 工具：`BlockBreakSession` 已自动 `switchToBestToolFor` ✓ → Job 只保证背包里有斧。

### 5.4 视线遮挡：限次 + 预算内 + 用现有遮挡位置

**事实**：`LineOfSightChecker.LineOfSightResult.getFirstBlocker()` **已经返回遮挡方块坐标** → 不需要自制"找树叶"逻辑。

**规则（2026-09-10 用户裁定：取消软/硬方块分类）**：
> 树叶本来就有碰撞箱，把它单列成"软方块"是**多余分类**。统一规则是——
> **任何可破坏（排除保护区/不可破坏/流体）且不是原木的阻挡方块都可以清**，上限由**预算**兜底。

运行时真实存在的两种无解情形只有：**预算用尽** 与 **阻挡物不可破坏**。

流程：
1. 子任务（`MineTask`）报 `LINE_OF_SIGHT_BLOCKED`；
2. Job 取 `getFirstBlocker()` → 判定是否**可清除**：
   - 必须是**可破坏**（`BlockInteraction.breakable`）且**不是原木**（原木是目标，可能是别的树）；
   - 规划期先估算"清几格能看见"（`BlockerClearPlanner.clearPlanCount`：站位被占的格 + 射线上的阻挡格），
     超过预算即视为该树不可行——避免"清了还是看不见"；
3. 作为**独立子任务**执行（`MineTask` on blocker），计入 `clearBudget`（默认 **≤ 8 格/棵**）；
4. 超预算 → 该树拒绝 `los_blocked_permanent` → 选下一棵 + trace。

**红线一致性（D-076）**：清障不是"寻路器自己挖"，而是 **Job 显式授权的独立子任务 + 硬预算**，与
"收集子任务由调用方授予 `allowWorldModification`"同构。

**不主动清树叶**：原木砍完后原版树叶会自然衰减（4 格内无原木即开始衰减），无需清理——旧代码的 `MAX_CLEAR_DEPTH=3` 整段逻辑永久删除。

### 5.5 记账与完成判据

- **产物入包**（原始设计 §4.2 标准 3）：`原木增量 = countInInventory(productTag) − 起始值 ≥ quota`；
- 副产物（树苗/木棍/苹果）不计入配额，但**掉落物必须清零**（`scope.liveDrops().isEmpty()`）；
- `HARVEST_UNITS`：**整棵砍完**才 +1（半棵不计数，防止"刷进度"）。

---

## §6 测试与验收（L3 三类断言）

> 现有「一键物品 + 场景 + SUMMARY」标准**能验收 L0–L2，验收不了 L3** —— 它测"动作结果"，不测"**选对了哪个**"。

### 6.1 场景 `lumber_course`（隔离区域、固定布局 → 期望选择唯一）

| 树 | 布局 | 期望 |
|---|---|---|
| **A** | 距起点最近，但 3 面 + 顶被石头封死（需隧道，超预算） | `rejected=A:no_stand` |
| **B** | 中等距离、露天、4 原木、有树冠 | **`picked=B`** |
| **C** | 更远、6 原木 | `rejected=C:not_nearest`（nearest 策略下） |
| **D** | 原木柱但位于保护区内 | `rejected=D:protected` |

### 6.2 三类断言

| 类 | 断言 |
|---|---|
| **a 决策可判读** | `[Job] select` 行必须含：候选总数、`picked`、理由（距离/暴露/原木数）、**每条被拒项的理由码**。固定布局下 `picked` 必须唯一确定 |
| **b 不变量** | 原木增量 ≥ 该树 `logCount`；`dropsLeft=0`；**未破坏非目标方块**（场景快照比对：只允许 A–D 的原木与树冠变化）；不重复砍同一棵 |
| **c 终止与恢复** | ① 配额达成 → `DONE reason=quota_met`；② 全部候选被拒 → `FAILED reason=no_reachable_candidate` + 理由集；③ 无候选且背包满 → `DONE reason=inventory_full`；④ 硬超时 → `FAILED reason=goal_timeout`（**不空转**）；⑤ 砍到一半原木被替换/消失 → 跳过该树并 trace，配额未达成且无候选则如实失败 |

### 6.3 策略可替换性（为 LLM 铺路）

同一场景、同一断言，**`NearestPolicy` 与 `NearestExposedPolicy` 必须给出不同且都可解释的选择**
（例如 C 有树冠暴露而 B 被树冠半包时，exposed 策略应改选 C）→ 证明"策略是可替换的决策缝"，而不是写死的 if。

**验证等级**：J1/J2 = `SERVER_TESTED` + `WINDOWS_CLIENT`（必须真人看到砍倒与入包）；J3/J4 = `SERVER_TESTED`（决策断言服务端可判读）+ 抽测。

---

## §7 实施切片

| 片 | 内容 | 入口 | 完成判据 |
|---|---|---|---|
| **J1** | `GoalSpec` + `Job` 骨架 + `TreeScanner`/`Tree` + `NearestPolicy` + `DecisionTrace`；**只砍一棵、不循环** | `alice:lumber_job` 物品（零参数）+ `/function alice_test:lumber_course` | 选中 B 并砍完一棵 + 收集入包；`[Job] select/terminal` 可判读 |
| **J2** | 循环 + 配额 + `GoalProgress` + 终止语义（§6.2c） | 同上（配额来自物品默认值/命令单参数） | `DONE quota_met`；不变量全过 |
| **J3** | 第二个策略 `NearestExposedPolicy` + §6.3 策略可替换断言 | 同上 | 两策略选择不同且均可解释 |
| **J4** | 理由集完备 + 失败场景（保护/替换/超时）+ §5.4 限次清障 | 同上 + 夹具注入 | 五条终止路径各有场景 |
| **J5** | 把 `AutoMineDecision` 迁移为 `MineCandidateSource` + `MineJob` | `/alice job mine <tag>` | 同一套 Job/Trace 复用；**顺手消灭 78 行的不可复用孤岛** |
| **J6**（**新增，且必须先于 J7/J8**） | **世界修改账本 + 建拆同权 + 恢复**（§12） | 夹具：放柱子 → 拆柱子 | 账本清空 + 场景快照无残留；崩溃续做有场景 |
| **J7** | 攀爬砍树（§11-①，需 J6 就位） | 高树场景 | 建 N 拆 N；无残留；可回收性判据过 |
| **J8** | `MAINTAIN` 可持续伐木区（§13） | 区域场景 + 补种 | 区域不变量长期成立；跨重启记得自己的修改 |

> 排序理由：J1 证明"决策缝 + 复用"成立（最小可验证闭环）；J2 引入配额与终止（长任务的真正难点）；
> J3/J4 补决策与失败语义；J5 证明 L3 不是伐木专用；
> **J6 必须在 J7/J8 之前**——按"建拆同权"，恢复机制不存在时不得发放放置授权。

---

## §12 世界修改账本与"建拆同权"（2026-09-10 裁定）

> 用户裁定：**接受"授权即配对"**（建造与拆除属于同一份授权）、**账本持久化**、
> 可持续伐木区做成 **`MAINTAIN` 持续型 Job**。

### 12.1 原则：授权即配对（建拆同权）

> **任何被授权放置的临时方块，必须在同一授权内被移除。**
> 禁止"只能建、不能拆"的授权——预算、trace、可回收性判据只有**一份**。

推论：**如果 bot 不能保证拆掉柱子，它就无权获得放置柱子的授权**。
这条把"可持续伐木区不得残留"从"靠自觉"变成**授权本身内建的不变量**。

### 12.2 三层分工 + 一个持久化账本

| 责任 | 层 | 为什么只能是它 |
|---|---|---|
| **记录**（改了什么、原来是啥） | **动作层**（`BlockInteraction.placeAt` / `BlockBreakSession`）**自动写账本** | 只有动作层看得见**每一次**修改——**含内核 `PILLAR` 放的方块**（Job 未必看得见）；靠调用方自觉记录必然重演"六份重复" |
| **意图与策略** | **L3 Job**（`ScaffoldSession`；策略 `TEMP` / `KEEP` / `RESTORE_BY_SCOPE`） | 只有目标层知道"这根柱子是为了够到那棵树"；"区域内不许残留"是**目标语义** |
| **执行恢复** | **L2 子任务**（`RestoreScopeTask`，复用 `MineTask` + `DOWNWARD` + 寻路） | 复用已验收能力；Job 只调度（与 `CollectDropsTask` 同构） |
| **持久化** | `WorldModLedger`（`SavedData`，参照既有 `TransferLedgerData` 的挂起-恢复范式） | 崩溃/被替换/重启会留下脚手架，必须能**续做清理** |

**账本条目**：`(pos, placedState, previousState, kind, scopeId, owner, policy, tick)`

- 记 `previousState` 是**精确"恢复原状"的前提**（往雪/草里放方块时原状态不是空气），
  也是"**绝不拆除不是自己放的方块**"的依据（拆除前比对 `placedState`，不匹配即放弃并告警）；
- 否决过的方案：❌ 放 L1 内核自动清理（内核不知意图，会拆掉"作为返程手段"的合法柱子，且违反 D-076 分层）；
  ❌ 只放 Job 内存（跨任务/跨重启丢失，且每个新能力都要重写记录逻辑）。

### 12.3 生命周期约束（消灭"够不到的残留"）

```
建立脚手架 → 攀爬 → 使用（砍那棵高树） → 仍在顶上时自上而下拆除 → 才允许离开
```

> **脚手架的生命周期 = 一次使用会话**；不允许"先走开、以后再回来拆"
> （从地面拆高层柱子必然留下悬空的、够不到的残块）。

**拆除机制**：bot 站在柱顶 → 挖掉脚下那格 → `DOWNWARD` 落 1 格 → 重复 → 落到地面
（`DOWNWARD` 已在 D-048/D-050 客户端验收；它与攀爬**共用同一份授权**，不额外发放）。

**安全四条**（不能只图干净）：
1. 拆除前检查落点安全（不落进岩浆/虚空）——沿用"可回收性"思路；
2. 严格**自上而下**，禁止先拆下层；
3. **只拆账本内我方 `TEMP` 且 `placedState` 匹配**的方块（防误拆玩家建筑）；
4. 拆除的消耗计入**同一预算与 trace**（日志必须看得见"建 5 拆 5"）。

### 12.4 崩溃兜底与断言

- 启动时扫描账本中未完成的 `RESTORE_BY_SCOPE` 会话 → 续做（参照 `TransferLedgerData.suspendUnfinished`）；
- **可断言的不变量**：任务/会话结束时 `WorldModLedger` 中该 scope 为空；场景快照无 bot 残留方块。

---

## §13 两类伐木：同一 Job，两组参数（含 `MAINTAIN` 区域型）

| 维度 | ① 自动寻找树砍树 | ② 自定义可持续伐木区 |
|---|---|---|
| 范围来源 | `center` 跟随 bot（漫游） | 玩家定义的立方区域（持久化） |
| Job 类型 | 一次性（配额达成即 `DONE`） | **`MAINTAIN`：持续维持区域不变量** |
| 区域不变量 | 允许残留（只需清掉自己的脚手架） | **禁止残留**：无未砍完的树、无自己放的方块 |
| 补种 | 否 | **是**（树苗 = 计划内永久修改，策略 `KEEP`） |
| 新树检测 | 不需要 | 需要（巡查周期 + 树苗长成树 → 继续砍） |

> 这两类在旧代码里是 **531 + 505 两套任务**；在新设计里是**一个 `Job` + 不同 `GoalSpec`/策略**——
> 这是 L3 价值最直接的兑现点。

### 13.1 `MAINTAIN` 语义

- 不追求"跑完即结束"，而是**持续满足不变量**：区域内有成熟树 → 砍；有空格且欠树 → 补种；都满足 → 巡查待机；
- **停止**只由玩家命令触发（或在连续 N 次巡查无进展且区域无树无苗时如实报 `IDLE_NO_WORK`）；
- **必须有健康输出**：定期 `[Job] maintain` 摘要（区域树数/苗数/待补数/本轮动作数/上次巡查 tick），否则"常驻任务"会变成黑箱；
- 巡查周期由配置决定（树苗生长需要真实时间，禁止高频扫描）。

### 13.2 区域状态持久化（与账本同族）

`LumberRegionState`（`SavedData`）：区域定义、**我种下的树苗位置**、待补种位置、上次巡查 tick、本轮统计。

- **必须记得"哪些苗是我种的"**：否则无法判断"欠几棵"，也可能把玩家种的当自己的；
- 补种属于**放置类修改**，策略 `KEEP`（计划内永久）——与脚手架的 `TEMP` 在同一账本里，策略不同。

### 13.3 终止与失败（如实）

| 情形 | 结果 |
|---|---|
| 区域内有树但全部不可达 | `FAILED no_reachable_candidate` + 逐树理由（不空转） |
| 工具缺失/耗尽 | `FAILED tool_missing`（补种与砍伐都需对应工具） |
| 连续 N 次巡查无进展且区域内无树无苗 | `IDLE_NO_WORK`（如实待机，不算失败） |
| 脚手架未能清空 | `FAILED scaffold_restore_incomplete` + 账本残留清单 |

---

## §8 与既有决策的对齐

| 决策 | 关系 |
|---|---|
| **D-073** | 本设计即其"伐木专项"落地；旧领域知识保留（§5.1），其六份重复全部归位（§2.2） |
| **D-076** | Job 不新增隐式世界修改；清障是**显式授权子任务 + 硬预算**（§5.4），与收集子任务同构 |
| **D-062 / 草案 P1-B** | 维生中断仍由 `BotSession` 统一处理，**Job 内不调用 `SurvivalSystem.tick`**（不重犯重复调用） |
| **D-070** | 子任务用 `MiningBudget.collectDrops=false` 跳过逐块收集，Job 统一收一次（§5.3） |
| **D-040** | 一切数值（清障预算、超时、扫描半径）按 Alice 实测标定，不照抄他人常量 |
| **原始设计 §4.2** | 标准 2（搜索必排序、暴露优先）= `NearestExposedPolicy`；标准 3（完成=产物入包）= §5.5 |
| **风险/维生草案** | **顺序上在本设计之后**：`RiskProfile` 的第一个真实读者 = Job 的候选筛选（"这棵树值不值得去"）；维生"去向" = Job 终止后的一次 `WalkToTask` |

---

## §9 裁定结果（2026-09-10，用户）

| # | 问题 | 裁定 |
|---|---|---|
| **1** | 接入形态 | ✅ **`Job implements Task`**：现有调度零改动，只需 5 行让子目标高亮跟随 |
| **2** | v1 配额类型 | ✅ 支持 `COLLECT_ITEMS` + `HARVEST_UNITS` + `maxTicks`；`UNTIL_FULL` 延后（本设计默认采用） |
| **3** | 超出触及的高树 | ✅ **v1 拒绝并给理由**（`trunk_too_tall`）；**用户明确"不能当作最终方案"——攀爬砍树是可能实现的**，实现路径与红线关系见 **§11-①** |
| **4** | 视线限次清障 | ✅ **允许**：白名单保守（树叶/雪/藤/草）、≤8 格/棵、超预算拒绝该树、每次可 trace |
| **5** | 砍到一半目标被替换/消失 | ✅ **跳过该树继续**（记 trace）；配额未达成且无候选 → `FAILED no_reachable_candidate`（本设计默认采用） |

---

## §10 本设计**不**解决的问题（登记，避免误以为已覆盖）

- LLM 接入（只留 `SelectionPolicy` 接口）
- 风险画像 / 维生出口（横切，需 L3 作为消费者）
- 攀爬高树、树苗补种、只砍指定树种（`species` 已记录但 v1 不做过滤）
- 多 bot 并行（`MULTI_BOT_INTERFACE_RESERVATION.md` 的边界不变）
- 异步决策（`PathingStats` 清空式约束不变，仍同步主线程）

---

## §11 未来能力登记（本轮不做，但有实现路径与触发条件）

> 目的：把"暂不做"与"判定不该做"分开。以下能力**不是被否决**，而是**还没有授权的入口与预算模型**。
> 写法沿用风险系统草案 §5.4 的做法：**写清触发条件**，避免半年后被重新发明或永久遗忘。

### ① 攀爬砍树（超出触及的高树）

**现状**：v1 用 `trunk_too_tall` 拒绝。**用户裁定：这只是 v1 范围，不是最终方案。**

**为什么它触及 D-076 红线**：要够到超出触及的原木，bot 必须获得高度，而唯一现成的垂直上升能力是
`MovementType.PILLAR`（跳跃中在脚下放方块）——**它是一个"会放置方块"的 Movement**。
D-076 规定：寻路请求默认纯通行（`PathRequest.of`），破坏/放置只能由上层任务**显式授权**并受**预算闸门**约束；
而当前为挖掘站位授权的 `PathRequest.miningApproach` **显式禁用 `PILLAR/FALL/DOWNWARD`**。
⇒ 因此"搭着方块爬上去砍"**今天没有任何合法入口**；要做得先立**新的显式授权入口**，不能悄悄加进 `miningApproach`。

**一个像样的实现必须同时具备（缺一不可）**：

| # | 要素 | 说明 |
|---|---|---|
| 1 | **显式授权入口** | 例如 `PathRequest` 新增"可攀爬"参数或独立 `climbApproach`，**只由 Job 显式开启**，不进默认集合 |
| 2 | **方块预算** | 类比 `MiningBudget.maxExtraBreakTicks`：攀爬消耗的一次性方块有上限，超预算 → 该树拒绝 |
| 3 | **返回保证（可回收性）** | 必须保证砍完能下来：要么留一条可下行路线，要么保证手上仍有方块（D-058 的 PILLAR 返回守卫是同一思想的第一个实例） |
| 4 | **世界残留策略** | 攀爬会**在世界上留下柱子**（不像清树叶那样天然衰减）→ 需要明确：留还是拆、是否记录、是否计入"未破坏非目标方块"不变量 |
| 5 | **与树干的交互** | 人类技法是"贴着树干往上放方块"：树干本身可作放置面 → 站位/放置面选择要显式支持"以目标树为脚手架" |
| 6 | **砍伐顺序耦合** | 边爬边砍时，脚下的原木可能正在被自己砍掉 → 顺序与站位必须联合规划，不能各管各的 |

**触发条件（2026-09-10 二次裁定后收紧）**：**必须先有 §12 的世界修改账本与"建拆同权"机制**——
否则授权放置 = 必然残留柱子，与可持续伐木区的核心不变量冲突。
业务触发：出现"必须处理高树"的真实需求时（例如配额在只有高树的林地里无法达成）→ 按 §12 前置 + 上述 6 项要素立项。

### ② `UNTIL_FULL` 配额（砍到背包满）

**现状**：`GoalSpec.Kind.UNTIL_FULL` 已定义但 v1 不实现。
**触发条件**：出现"把背包塞满再回去"的真实任务（例如"装满一箱原木"）时启用；届时需要一个"背包满"的
权威判据（`Inventory.getFreeSlot()` 口径 + 产物可堆叠判定），不能靠估算。

### ③ 树种过滤与补种

**现状**：`Tree.species` 已记录（按原木方块映射），v1 **不做**过滤、**不做**补种。
**触发条件**：出现"只要橡木""砍完补种树苗"这类目标时启用；补种属于**放置类世界修改**，同样要走显式授权 + 预算。

### ④ 树叶主动清理

**现状**：**不做**（原木砍完后原版树叶自然衰减）。
**说明**：这不是"暂缓"，而是**判断为不需要**——旧代码的 `MAX_CLEAR_DEPTH` / `findLeafBlocker` 因此永久删除。
唯一的例外是"树叶挡住视线"（§5.4 的限次清障）。
