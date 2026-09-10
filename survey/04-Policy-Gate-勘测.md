# 04 · Policy Gate 勘测报告

> **基线 commit**：`90b8390`
> **勘测对象**：蓝图 §2.2 要求的「Policy Gate + Plan Validator」在现有代码里的**实际形状**
> **勘测日期**：2026-09-10
> **证据分级**：全部为**静态阅读代码 + 全仓符号扫描**。**未运行代码、未做客户端实测。**
> **本报告不具备决策效力**，是候选材料。采纳时请落到 `docs/AI_DECISIONS.md`。
> 角色边界见本目录 `README.md`：勘测员只做浅程度未来规划，不干预正在进行的开发主线。

---

## 0. 结论速览

### 0.1 对蓝图写法的一处修正

蓝图 §2.2 原话：

> 「**这是现有设计需要显式补出的层。**」

**勘测结论：这不是「一个需要补出的层」，而是三件成熟度完全不同的事。** 浑在一起看，才会显得需要一套大架构。

| 子问题 | 现状 | 该不该建「层」 |
|---|---|---|
| **判定**「谁允许这个动作」 | ✅ **已经做对了**，有正确样本 | ❌ 不该。建层反而会破坏现有松耦合 |
| **词表**「做不成是什么意思」 | ⚠️ **已存在，但结构倒置** | ❌ 不该。只需把已有枚举摆正 |
| **身份**「谁在请求」 | ❌ **字段存在，永远为空** | ⚠️ 一个枚举就够，别上令牌链 |

### 0.2 四个反直觉的发现

1. **`BlockBreakSafety` 已经是 Policy Gate 的正确形状** —— 它自称「Alice 唯一的方块破坏安全入口」，
   **不需要知道调用者**、**返回理由而非 boolean**。这证明了集中式 `PolicyGate` 是**错的方向**。
2. **失败词表不是缺失，是倒置** —— `TaskExecutionRecord.TerminalStatus` 已有 6 个值，
   但它记录的全是**任务之上**发生的事（谁取消的、谁拒绝的）；
   而任务**内部**的所有语义（是预算耗尽还是真做不到）被压扁成一个**自由字符串**。
   **语义重心在字符串里，枚举管的是边角。**
3. **`PathRequest.requester` 三个工厂方法全部硬编码 `"unknown"`，全仓无一处真正填充。**
   系统里**现在没有「谁在请求」这个概念**。这在阶段 G（LLM 接入）会立刻变成硬缺口，而它是**签名级**的。

4. **整合包独占内容不进通用识别**（用户 2026-09-10 裁定）。
   例：ATM9 的 Allthemodium / Vibranium / Unobtainium 属整合包独占，
   应作为**特定整合包适配工作的一环**（蓝图阶段 E / `PackProfile`），
   **不列入当前通用识别内容**。→ 详见 §5.2.4。

### 0.3 成本窗口（本报告最实用的部分）

三件事都是**接口级**改动，成本随调用方数量**线性增长**：

| 改动 | 现在（调用方 3 个） | D/E/F 之后（30+ 个） |
|---|---|---|
| 填 `requester` | 改 **3 个工厂方法** | 改 30+ 处调用签名 |
| 统一词表 | 融合 **3 套** | 融合 **8–10 套** |
| 中断钩子提接口 | **1 个 default 方法** | 长出 5 套中断协议 |

**都不阻塞 J1–J8 主线。**

---

## 1. 现状盘点（全部带文件行号）

### 1.1 已存在的「策略」类（6 个，共 274 行）

| 类 | 行数 | 域 |
|---|---:|---|
| `pathing/TunnelObstaclePolicy` | 82 | 隧道障碍 |
| `job/policy/NearestExposedPolicy` | 57 | L3 候选选择 |
| `road/RoadObstaclePolicy` | 48 | 筑路障碍 |
| `job/policy/NearestPolicy` | 40 | L3 候选选择 |
| `survival/FluidRiskPolicy` | 31 | 流体风险 |
| `job/SelectionPolicy`（接口） | 16 | L3 选择策略契约 |

**观察**：命名统一（`*Policy`），但**彼此无共享契约** —— 没有共同接口、没有共同返回类型。

### 1.2 已存在的「预算」类（4 种形态）

| 类 | 单位 | 额外语义 |
|---|---|---|
| `pathing/core/search/SearchBudget` | 节点数 + 毫秒 | **有取消信号** `BooleanSupplier cancelled` |
| `task/mining/MiningBudget` | tick | tick 计费 + **目标珍贵度分档**（1× / 2× / 4×） |
| `task/CollectDropsTask` 常量 | tick | `DEFAULT_TOTAL_BUDGET_TICKS = 600`、`CLUSTER_BUDGET_TICKS = 200` |
| `job/GoalSpec.maxTicks` | tick | 墙钟上限，**构造时强制 > 0** |

**观察**：单位差异是**真实的**（搜索空间 / 计算代价 / 墙钟），不应强行统一。

### 1.3 已存在的「判定」入口

| 入口 | 域 | 形状 |
|---|---|---|
| `protection/BlockBreakSafety.explicitTargetRefusal` | 方块破坏 | **返回 `String`（null = 允许）** ✅ |
| `protection/SafeZoneData.protectionReason` | 世界保护 | 返回 `String` |
| `pathing/TunnelObstaclePolicy` | 隧道 | — |
| `road/RoadObstaclePolicy` | 筑路 | — |

### 1.4 关键：**没有**统一层

全仓扫描 `WorldProfile` / `PackProfile` / `PipelineProfile` / `ProcessContract` / `NetworkProfile` → **0 命中**。
没有 `PolicyGate` 类，没有 `PlanValidator`，没有统一的 adjudication 入口。

---

## 2. 子问题一：判定 —— **已经做对了**

### 2.1 正确样本

`protection/BlockBreakSafety` 类注释第一句：

> **「Alice 唯一的方块破坏安全入口。」**

签名：

```java
/** 返回 null 表示目标本身允许挖。 */
public static String explicitTargetRefusal(ServerPlayer bot, BlockPos target)
```

**它的形状证明了四件事**：

1. **单一归属可行** —— 收敛了「能不能破坏方块」这一个问题，没有散落
2. **不需要知道调用者** —— 寻路器 / 挖矿任务 / 清障都调它，彼此不耦合
3. **返回理由而非 boolean** —— 拒绝时能说清为什么（`fluid_block` / `unbreakable_block` / 保护区理由）
4. **内部还区分了两类目标** —— 「明确任务目标」与「执行器自选的清障方块」用不同策略

### 2.2 因此：集中式 `PolicyGate` 是错的方向

建一个 `PolicyGate.check(...)` 会**把已经松耦合的东西重新拧成一坨**，且每加一个域就要加一个 case，
最终必然演变成「一个必须不断问别人问题的空壳」。

**正确的方向是：共享签名，各自实现。**

---

## 3. 子问题二：词表 —— ★ **已存在，但结构倒置**

> ⚠️ 这是本报告相对前几轮口头讨论的**重要修正**：词表不是「缺失」，而是「摆错了位置」。

### 3.1 现状：三层词表，层间映射有损

| 层 | 定义位置 | 取值 |
|---|---|---|
| **① Task 接口** | `task/Task.java:35-37` | `RUNNING` / `DONE` / **`FAILED`** —— **仅 3 个** |
| **② 终态记录** | `bot/TaskExecutionRecord.java:59-66` | `COMPLETED` / `FAILED` / `SURVIVAL_INTERRUPTED` / `CANCELLED_FOLLOW` / `CANCELLED_REPLACED` / **`REJECTED_BEFORE_START`** —— 6 个 |
| **③ 自由字符串** | `resultCode`（`TaskOutcome` / `TaskExecutionRecord`） | **无约束** |

### 3.2 致命处：映射是**单向压扁**的

`bot/BotManager.java:914-931`（编排驱动点）：

```java
Task.Status status = task.tick();
switch (status) {
    case DONE   -> complete("done",                     TerminalStatus.COMPLETED);
    case FAILED -> complete("failed:" + task.failureReason(), TerminalStatus.FAILED);  // ← 全部压成 FAILED
    default     -> { /* 进行中，保持 */ }
}
```

**任务内部的一切区分**（`goal_timeout` / `no_reachable_candidate` / `found_but_unminable` / `enter_target_over_budget`…）
**全部塌缩成同一个 `FAILED`**，只剩下字符串里那点信息。

### 3.3 枚举管的恰好是**边角**

看 `REJECTED_BEFORE_START` 的赋值点（`BotManager.java:869`、`892`）——
它只在**任务还没开始**时由编排层赋值。而 `CANCELLED_FOLLOW`（:336）、`CANCELLED_REPLACED`（:819）、
`SURVIVAL_INTERRUPTED`（:911）**也全部在编排层赋值**。

**即：枚举里的 4 个「特殊值」，全是「任务之上发生的事」；任务内部发生的事，一个都没进枚举。**

### 3.4 失败词表的分裂（量化）

全仓失败原因字符串去重统计，同一个意思有三套词：

| 域 | 用词 | 出处 |
|---|---|---|
| 寻路搜索 | `SEARCH_LIMIT` vs `UNREACHABLE` | `SearchBudget` / **D-076** |
| 挖掘规划 | **`found_but_unminable`** | `MiningPlanner.java:81` |
| Job 层 | `goal_timeout` / `no_reachable_candidate` | `LumberJob.java:117,137` |

`found_but_unminable` 这个名字是 **D-076 血泪教训的产物**：

> 「`SEARCH_LIMIT` 不等于 `UNREACHABLE`，不自动授权挖隧道。」

即：**「预算耗尽」曾被错误当成「不可达」，导致寻路器自己去挖隧道穿墙。**
修完之后**挖掘域学乖了**，专门造词表达这个微妙区别 —— **但这次学习没有传播**：
Job 层又用回了 `goal_timeout`，把「时间用完了」和「做不到」重新混在一起。

**这正是 `survey/02` 那条病灶的形状**：同一职责无单一归属 → 每个域重新发明 → **有的域学对了，有的没学。**

### 3.5 建议词表（只需四个，且直接对应差异②）

Alice 与 Baritone 的差异②是「**多层任务失败向上传递**」。理想词表应让**上层一看就知道该怎么办**：

| 词 | 含义 | 上层该怎么办 |
|---|---|---|
| `DONE` | 做成了 | 继续 |
| `REFUSED` | **明确不允许**（安全区 / 不可破坏 / 无权限） | **别重试**，换目标或问人 |
| `EXHAUSTED` | **没做完，且无法判定能否做成** | 可加预算重试 / 向上交 LLM |
| `FAILED` | **确定失败**（世界变了 / 目标没了） | 重规划 |

**关键在 `REFUSED` 与 `EXHAUSTED` 分开**：前者重试一万次也没用，后者加预算可能就成了。
现在两者混用字符串，**每次都要靠人读日志分辨**。

### 3.6 改造性质：**很轻**

不是重构控制流，而是：
1. 把 `TerminalStatus` 的语义**向任务内部延伸**（补 `REFUSED` / `EXHAUSTED`）
2. 让 `Task` 自己**返回**这个区分，而不是让编排层从字符串里猜
3. `resultCode` 保留为**细节**，但不再是**唯一**的语义载体

---

## 4. 子问题三：身份 —— `requester` 永远是 `"unknown"`

### 4.1 证据

`pathing/core/search/PathRequest.java` 是一个 record，最后字段是：

```java
String requester        // :21
```

**设计者想过「谁在请求」这件事。** 但看三个工厂方法：

```java
public static PathRequest of(...)                    { ... SearchBudget.UNLIMITED, "unknown"); }  // :39
public static PathRequest withWorldModification(...) { ... SearchBudget.UNLIMITED, "unknown"); }  // :51
public static PathRequest miningApproach(...)        { ... SearchBudget.UNLIMITED, "unknown"); }  // :63
```

**三个全部硬编码 `"unknown"`。** 全仓 grep：`requester` 除在 `PathRetryRunner.java:53` 被拼上 `":attempt2"` 外，
**无任何地方真正填充过它**。

### 4.2 后果

**系统里现在没有「谁在请求」这个概念。**

平时不疼。但阶段 G 一上来立刻疼，因为蓝图 §2.1 明写 LLM **不负责**：

> 「自行扩大破坏/搬运区域、**权限**、预算或重试次数。」

**要拦住 LLM 越权，前提是能区分「请求来自 LLM」与「请求来自玩家/测试命令」。**
现在两者在结构上**完全无法区分** —— 都是 `"unknown"`。

### 4.3 缺口性质：**签名级**

等 D/E/F 铺开、调用方从 3 个变成 30 个之后再补，要改 30 处调用签名。**现在补，改 3 处。**

---

## 5. 三个深聊点

### 5.1 Authority 的粒度 → **枚举够用**

「授权」实际是**三种不同的东西**，混在一起才显得需要大设计：

| | 是什么 | 现状 | 够用的形状 |
|---|---|---|---|
| **身份** | 谁在请求 | 全是 `"unknown"` | **枚举** |
| **范围** | 允许做到什么程度 | 已是 `Set<MovementType>` | **已有**，不必抽象 |
| **否决** | 谁能无条件叫停 | `SurvivalSystem.shouldInterrupt` | 已存在，但**接法有问题**（见 5.1.2） |

**结论：`enum Authority { PLAYER, LLM, TEST, SYSTEM }` 就够，不要上能力令牌链。**

依据是项目自己定过的约束 —— `pathing/risk/RiskSwitches.java` 类注释：

> 「将来的评估器通过 `set` 逐项设置这些开关，**不引入额外的抽象层**。」

同样克制应适用此处。而且「范围」这个维度**已经有结构了**（`allowedMovementTypes`），
真正缺的只有「身份」**一个**维度 —— 为一个维度上令牌链，是拿架构换还没遇到的问题。

#### 5.1.2 顺带发现：一个独立缺陷（建议单独记一笔）

`bot/BotManager.java:906-911` 的中断处理：

```java
if (SurvivalSystem.shouldInterrupt(hazard)) {
    if (task instanceof TransferTask transfer) {        // ← 硬编码到具体类型
        transfer.survivalInterrupted(SurvivalSystem.interruptionReason(hazard));
    }
    lastTaskResult = "failed:" + SurvivalSystem.interruptionReason(hazard);
    complete(lastTaskResult, TaskExecutionRecord.TerminalStatus.SURVIVAL_INTERRUPTED);
    return;
}
```

**只有 `TransferTask` 有优雅中断钩子，其它任务一律被直接杀掉。**

**这不是预测，已经在发生。** 全仓扫 `instanceof *Task` 共 **5 处**，其中 **3 处针对同一类型 `TransferTask`**：

| 位置 | 类型 | 语义 |
|---|---|---|
| `BotManager.java:173` | `TransferTask` | 查询态 |
| `BotManager.java:814` | `TransferTask` | 「是否在途 / 挂起」 |
| `BotManager.java:906` | `TransferTask` | **中断钩子** ← 本节点名的那个 |
| `BotManager.java:795` | `MineTask` | 取当前计划 |
| `BotManager.java:939` | `MineTask` | 取恢复阶段 |

**同一个类型在 3 个不同语义的位置被特判** —— 这正是「缺少通用接口」的典型征兆，
且它会随任务类型增加而**线性恶化**。

现在无所谓（其它任务没有「进行到一半的事务」）。但 D/E 一上来全是问题：
**投料投了一半、搬运搬了一半、样板写到一半** —— 每一个都是需要那个钩子的场景，
而这个 `instanceof` 会变成**一长串**。

**正确形状**：提到接口上 ——

```java
default void onInterrupt(String reason) { }   // Task 上的默认空实现
```

**改动极小（一个 default 方法 + 删掉 instanceof），但必须在 D/E 之前做**，否则会长出五套中断协议。

> **这顺带回答了用户此前的关切「维生系统应该有最大话语权」**：
> 话语权**已经在了**（`shouldInterrupt` 在 task tick 之前），只是**接口没开好**。

### 5.2 预算继承 → **不统一单位，但给额度加「归属」**

#### 5.2.1 现状：三套互不相干的额度同时存在

`LumberJob` 里三个层次并存：

| 层 | 额度 | 怎么来的 | 与上层的关系 |
|---|---|---|---|
| **Job** | `GoalSpec.maxTicks` | 调用者给 | — |
| **Task** | `DEFAULT_TOTAL_BUDGET_TICKS = 600` | **硬编码常量** | ❌ 无任何关系 |
| **Action** | `MiningBudget.forTarget(...)` | 方块硬度 × 10 × 珍贵度档 | ❌ 无任何关系 |

对应源码（`job/lumber/LumberJob.java`）：

```java
if (++ticks > spec.maxTicks())                                     // :117  Job 层额度
new CollectDropsTask(bot, tree.base(), scope, List.of(), false)     // :160  → 内部自定 600
new MineTask(bot, log, scope, MiningBudget.forTarget(...), true)    // :205  → 按方块硬度算
```

#### 5.2.2 真实后果

**父 Job 已因 `goal_timeout` 终止，子任务可能还在跑；或反过来，父还有 5000 tick，收集任务 600 tick 就放弃。**
任何一方都无法知道另一方还剩多少。

**为什么 D/E 会变严重**：多阶段任务（投料 → 等待 → 收产物）里，
「子任务耗尽了」这句话**不够** —— 上层要决定「加预算重试」还是「放弃」，
就必须知道**是谁的额度耗尽了**：Job 的时间？收集任务的 600？还是那个方块太硬？

#### 5.2.3 建议：最小改动是「可归因」，不是「统一」

**不要合并这三个数**（它们量的确实是不一样的东西）。只要在耗尽时**报出是哪个额度**：

- 失败原因里带上 `budget_owner` + `budget_kind`
- 配合 §3.5 的 `Outcome` 枚举

**这不是新抽象层，是让失败可归因。**

#### 5.2.4 附带：整合包独占矿石的价值分档缺口（**待实测项**）

**先否掉一个我起初的错误假设**（留档以便复查）：
起初怀疑「模组工具（Tinkers' / Silent Gear）动态组装 → 没有固定挖掘速度 → 6.0 tick 校准失效」。
**读源码后该假设不成立。** `action/BlockInteraction.java:261-285`：

```java
float speed = stack.isEmpty() ? 1.0F : stack.getDestroySpeed(state);      // 标准原版 API
boolean canHarvest = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
double seconds = canHarvest ? hardness * 1.5D / max(speed,1e-4F)
                            : hardness * 5.0D / max(speed,1e-4F);
```

两处均为**标准原版 API**，模组工具只要覆盖它们即可被**自适应**（Tinkers'/Silent Gear 确实覆盖）。
且 `FALLBACK_ORDINARY_TICKS = 6.0` **仅在估算返回非有限或 ≤0 时回退** —— 模组工具返回有效值，
**该常量几乎不触发**，也不构成「所有预算的乘数基数」。

**真实的缺口在 `task/mining/MiningBudget.java:46-58` 的 `tierOf()`**：

```java
if (state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE)
        || state.is(Blocks.ANCIENT_DEBRIS) || state.is(Blocks.EMERALD_ORE)
        || state.is(Blocks.DEEPSLATE_EMERALD_ORE)) { return 4.0D; }   // ← 硬编码原版方块
if (state.is(BlockTags.COAL_ORES) || ...)          { return 2.0D; }   // ← 原版标签（可扩展）
return 1.0D;                                                          // ← 兜底
```

| 档位 | 判定方式 | 对模组矿石是否可扩展 |
|---|---|---|
| 4× | **硬编码原版方块** | ❌ **不可扩展** |
| 2× | 原版 `BlockTags.*_ORES` | ✅ 可扩展（`Almost Unified` 会把跨模组重复矿物统一到标准标签） |
| 1× | 兜底 | — |

**观察（结构性）**：真正坏掉的只有 4× 一档 —— 它**缺一个标签入口**。
2× 档反而是正例，**佐证了「应只依赖标签，不依赖具体模组 ID」**这条建议。

##### 定性与处置方向（用户 2026-09-10 裁定）

1. **定性 = 待实测项**，**不预先判定为代码缺陷**。
2. **不把整合包独占方块加入通用识别表。**
   Allthemodium / Vibranium / Unobtainium 等属 **ATM 独占内容**，
   按用户裁定应作为**特定整合包适配工作的一环**（对应蓝图阶段 E / `PackProfile`），
   **不列入当前通用识别内容**。
3. 因此正确方向**不是**往 4× 列表里塞模组方块 ID，而是**给 4× 档一个标签驱动的入口**，
   由整合包适配层提供标签 / profile。

> **待实测（见 `survey/03` 文末补遗）**：ATM9 的独占矿石是否真的落到 1.0× 档，
> 以及名义表现（是否出现 `enter_target_over_budget`）。
> **影响面**：仅整合包独占高分档矿石；不涉及通用挖矿链。

### 5.3 检查点位置 → **图上少画了两个**

#### 5.3.1 蓝图画的 vs 实际存在的

**蓝图 §2 画的**：`ToolCall → [Policy Gate + Plan Validator] → Orchestrator`，**一个**检查点。

**实际是三个，权威性完全不同**：

| 检查点 | 位置 | 现状 | 权威性 |
|---|---|---|---|
| **① 计划级预检** | 选方案前 | 蓝图画的，**尚未实现** | **只是优化** —— 省时间 |
| **② 动作级断言** | 破坏/放置那一刻 | `BlockBreakSafety.explicitTargetRefusal` | **唯一权威** —— 保命 |
| **③ 抢占/否决** | 任务循环之上 | `SurvivalSystem.shouldInterrupt` | **最高优先** —— 无条件 |

#### 5.3.2 ① 为何不能替代 ②

**项目自己的 D-043（重规划下沉）就是证据** —— 规划时可行的路线，走到一半地形被改了。
**计划级判断天然会过期。**

因此：

- **① 该做**，但必须明确它是「便宜的先筛掉」，**不能作为安全依据**
- **② 绝不能省**，且现有形状已经是对的（返回理由而非 boolean）
- **③ 图里完全没有**，但它是真实存在的，且**维生系统在这层有最大话语权**

#### 5.3.3 ① 放在哪

**建议放在 Orchestrator 里，不单独设层。**

理由：`Policy Gate` 需要知道「这个任务类型要检查什么」，这个知识**天然属于编排**；
单独抽一层会变成「一个必须不断问别人问题的空壳」。这与 §2.2 的结论一致：**判定保持分散，别集中。**

---

## 6. 建议形状汇总

**不要建 `PolicyGate` 类。建三个小东西：**

| # | 名称 | 做什么 | 优先级 | 成本 |
|---|---|---|---|---|
| **①** | `Authority` | 填充已有的 `requester`：`enum {PLAYER, LLM, TEST, SYSTEM}` | **先做**（最便宜） | 改 3 个工厂方法 |
| **②** | `Outcome` | 词表四值（`DONE/REFUSED/EXHAUSTED/FAILED`），**把已有枚举摆正** | **收益最大** | 3 套词表融合 |
| **③** | 判定 | **保持分散，只统一签名**：各域检查器都长成 `BlockBreakSafety` 的样子，**返回 `Refusal`（null = 允许）而非 boolean** | 随域推进 | 渐进 |

**外加一个独立修复**：

| # | 名称 | 做什么 | 成本 |
|---|---|---|---|
| **④** | `Task.onInterrupt` | 中断钩子从 `instanceof TransferTask` 提到接口默认方法 | **1 个 default 方法** |

### 6.1 唯一需要立刻拍板的设计问题

> **LLM 与 PLAYER 的默认权限，相同还是不同？**

勘测员倾向：**不同**（LLM 更窄，需玩家显式提权）。
**但这必须由用户拍板** —— 它决定整个安全模型的形状，且直接约束阶段 G 的接入方式。

---

## 7. 与既有勘测的关系

| 本报告结论 | 呼应 |
|---|---|
| 失败词表分裂、每个域重新发明 | `survey/02-职责归属勘测.md` 的「结构性病灶：同一职责无单一归属 → 重复 / 死抽象」 |
| 判定该分散而非集中 | `survey/02` 的「不建议做的清单」（死抽象） |
| 三件事都不阻塞主线 | `survey/README.md` 的「不干预正在进行的开发主线」约定 |
| ATM9 独占矿石分档缺口（**待实测项**，已归入**整合包适配层**、不进通用识别） | `survey/03-ATM9-勘测报告.md` 文末「补遗 A」 |

---

## 8. 待确认清单

| # | 待确认 | 怎么确认 | 成本 |
|---|---|---|---|
| 1 | `TerminalStatus` 的 6 个值在客户端实测中是否真的都被用到 | 抓一次完整回归的 `TaskExecutionRecord` 输出 | 低 |
| 2 | `LumberJob` 的 `goal_timeout` 在实测中出现频率 | 跑 J1–J5 客户端测试看 `[Job] terminal` 行 | 低 |
| 3 | `requester` 字段当初是否有未落地的设计意图 | 查 `AI_DECISIONS.md` + 相关设计文档 | 低 |
| 4 | ~~是否有其他域也在偷偷用 `instanceof` 做中断/钩子分派~~ | ✅ **已确认**：全仓共 5 处，3 处为 `TransferTask`（见 §5.1.2） | 已完成 |
| 5 | ATM9 独占矿石（Allthemodium 等）是否真落到 1.0× 档，是否表现为 `enter_target_over_budget` | 客户端挖一次独占矿，看 `[MiningPlanner]` 日志 | 中 |

---

*勘测员（手机端智能体）· 2026-09-10 · 基线 `90b8390`*
*本报告只增不改历史结论；有新结论时追加「补遗」，不悄悄改写过去说过的话。*
