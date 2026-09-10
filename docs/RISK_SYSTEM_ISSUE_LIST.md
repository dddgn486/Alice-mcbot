# Alice 问题修复清单

> ⚠️ **非最终方案（草案）**——本文**未定案**，不作为当前实现授权。
> 出处：用户与另一 AI 于 2026-09-10 讨论产出；**证据来源为静态阅读代码 + 全仓符号扫描，未运行代码、未做客户端实测**。
> 复核结论见 [`RISK_SYSTEM_REVIEW_20260910.md`](RISK_SYSTEM_REVIEW_20260910.md)：
> 本文 9 条断言**逐条核对全部属实**（P0-B 的机制描述在复核文档 §3 做了精确化）；
> **§10 的 Q1（未加载区块行为）已由反编译确认**：不是 void air，而是同步加载/生成区块并阻塞主线程 —— 复核文档 §2。

- 日期：2026-09-10
- 代码基线：`693e419`（2026-09-10 02:51）
- 状态：**待裁定，非最终定案**
- 配套文档：`RISK_SYSTEM_DESIGN_DRAFT.md`（设计原则、契约形态、实施切片）
- **证据来源声明**：本文所有结论来自**静态阅读代码 + 全仓库符号扫描**；**未运行代码、未做客户端实测**。凡需要运行才能确认的，均已标注「**待确认**」。

> **每条的统一格式**
> `现象` → `证据（文件/符号）` → `影响` → `建议处置` → `验证方式`
>
> **优先级说明**（用"行动优先级"，不是"bug 严重度"）
> - **P0**：会**阻塞后续决策**_或_会让未来的人误判现状 —— 必须先裁定
> - **P1**：**真实缺口** —— 有可观测的负面后果
> - **P2**：**卫生 / 约束登记** —— 今天无害，但会被遗忘
>
> **⚠️ 特别提醒**：P0-B 与 P0-C 属于**同一类病**（死抽象 / 死代码）。D-046 自己写过这个教训：
> 「`WorldView` 的教训是**零实现的空抽象会被审计记为缺陷**。」
> 现在这种病已有 **3 例**，见 §9 的复发性检查。

---

## §0 优先级总表

| # | 问题 | 类型 | 影响面 | 与既有决策的关系 |
|---|---|---|---|---|
| **P0-A** | `RiskSwitches` 是**全局静态状态** | 设计不一致 | 阻塞 D-046 落地 | 与 D-046 第 3 条冲突 |
| **P0-B** | `RecoverabilityLevel` 等是**死抽象** | 死代码 | 项目**签名能力**空实现 | 违反 D-046 精神 |
| **P0-C** | `FluidRiskPolicy` **零调用** | 死代码 | 已有探针未接 | — |
| **P1-A** | 寻路链路**没有"执行期事前"准入** | 真实缺口 | 即死危害无事前检查 | 与 D-037 互补 |
| **P1-B** | `MineTask` **重复调用** `SurvivalSystem.tick` | 不一致 | 两套终态记录 | 与 D-062 做法不一致 |
| **P1-C** | 维生否决后**没有出口** | 真实缺口 | bot 停在岩浆里 | 违反"拒绝对须带出口" |
| **P2-A** | `LiveExecutionContext.policyVersion` **恒为 0** | 数据位未接 | 冻结机制无验证手段 | 阻塞 S1 的可见性 |
| **P2-B** | `PathingStats` 是**清空式**设计 | 约束登记 | 异步规划会丢数据 | — |
| **P2-C** | 三个守卫默认值**互不一致** | 过渡态登记 | 污染对照测试 | D-059 明文"待确认" |
| **Q1** | `getBlockState` 在**未加载区块**上的行为 | **待确认事实** | 决定 P1-A 优先级 | — |

---

## §1 [P0-A] `RiskSwitches` 是全局静态状态

### 现象

风险开关是**进程级单例**，不是 per-bot、不是 per-request、不是 per-search。

### 证据

```java
// src/main/java/com/dddgn/alice/pathing/risk/RiskSwitches.java
private static final java.util.Set<String> KNOWN = java.util.Set.of(DESCEND_OVERSHOOT_GUARD);
private static volatile boolean descendOvershootGuard = false;
```

**消费者只有 2 处，且都直读全局静态**（无参数传入）：

| 位置 | 调用 |
|---|---|
| `pathing/core/DescendExecutionFactory.java:58` | `if (!RiskSwitches.descendOvershootGuard()) return accepted();` |
| `pathing/core/search/SurfaceMovementProvider.java:371` | `if (!RiskSwitches.descendOvershootGuard()) return true;` |

`pathing/risk/` 包**只有这一个文件**。

### 影响

1. **与 D-046 第 3 条冲突**。原文：「覆盖粒度：不只全局——**每个主要任务层都可让玩家自定义风险模式**；未定义时用默认的 S。」全局静态**无法**做到 per-task。
2. **多 bot 并行时间题会放大**。两个 bot 共用一个开关；D-036 已把"未来 Bot 并行运行接口"列为 Alice 三大差异之一，而 D-046 明确要求"每任务层可覆盖"。
3. **搜索中途可变**。`/alice risk` 在规划进行中能改变开关，导致**规划假设中途变化** —— 这正是"冻结"要防的那类 bug。
4. **但注意**：**今天不是可观测的 bug**（只有 1 个开关、尚未做多 bot、搜索是同步主线程）。这是**技术债，不是故障**。

### 建议处置

**做 S1**（见实现草案 §7）：把开关值收进 **不可变 `RiskProfile`**，随 `PathRequest` → `MovementContext` → provider/执行器 贯通，在**搜索开始时冻结**。
`RiskSwitches` **保留**，但降格为「**默认值来源 + 游戏内切换入口**」，不再是消费者的直读对象。

> **注意 D-046 的原文约束**：`RiskProfile` 贯通路径正是它点名的
> `PathRequest → MovementContext → provider → 执行器`。
> **所以这一片不是新增抽象，而是执行 D-046 已经写好的计划。**
> 且它天然有 **3 个消费者**（见 P2-C），**不违反"无空抽象"**。

### 验证方式

- `/alice risk profile` 输出当前快照；
- 回归 13/13 不变红；
- `segment_done` 日志新增 `risk=` 字段，确认一次搜索内**不变**。

---

## §2 [P0-B] `RecoverabilityLevel` / `IntrinsicReversibility` 是死抽象

### 现象

**「可回收性」是项目签名能力（D-036 三大差异之一），但它在代码里从未被实际计算过。**

### 证据（全仓库符号扫描）

| 枚举值 | 使用情况 |
|---|---|
| `RecoverabilityLevel.LOCAL_STEP` | ✅ 使用 —— 但**所有生产规划输出都硬编码这一个值** |
| `RecoverabilityLevel.PATH_REVERSIBLE` | 仅在 4 个诊断任务的手工构造里（`Traverse/Diagonal/Ascend/DescendDiagnosticTask`） |
| `RecoverabilityLevel.SAFE_EXIT_REQUIRED` | ❌ **从未使用** |
| `RecoverabilityLevel.EMERGENCY_EXIT_REQUIRED` | ❌ **从未使用** |
| `IntrinsicReversibility.CONDITIONALLY_REVERSIBLE` | 仅 `PlannedMovementSpecs` 的 `FALL` 一处 |
| `IntrinsicReversibility.NOT_REVERSIBLE` | ❌ **从未使用** |

生产路径上，`SurfaceMovementProvider` 的所有输出点都写死 `LOCAL_STEP`（7 处）；
`AStarMovementSearch:134` 同样写死。

而 `MovementSpec` 里**唯一的**用途是一个**永不触发**的比较：

```java
// pathing/core/MovementSpec.java
if (evaluatedRecoverability.ordinal() < capabilities.requiredRecoverabilityLevel().ordinal()) {
    throw new IllegalArgumentException("evaluated recoverability is below Movement requirement");
}
```

生产路径上两边**永远都是 `LOCAL_STEP`** → 条件永假 → **这个校验从未生效过**。

### 影响

1. **这是最危险的一类技术债**：不是"功能缺失"，而是**"看起来在工作，实际从未计算"**。未来接手的人（或 AI 会话）读到 `MovementCapabilities.requiredRecoverabilityLevel` 会**合理地认为**可回收性已被评估。
2. 它承载的正是项目**对外宣称的差异点**。文档说"Alice 更注重可回收性"，代码里可回收性只是一个恒定的枚举值。
3. 与 D-046 的自我警告**完全吻合**：「零实现的空抽象会被审计记为缺陷」。

### 建议处置

**二选一，不许留现状。**

| 选项 | 动作 | 代价 |
|---|---|---|
| **S3a 接线（推荐）** | 让 `FALL` 的 PILLAR 返回守卫**产出** `evaluatedRecoverability`（守卫通过 = `PATH_REVERSIBLE`，否则**该边不生成**）。`MovementSpec` 的校验立刻开始有意义 | 中；改 `SurfaceMovementProvider` + `PlannedMovementSpecs` |
| **S3b 删除** | 删两个枚举 + `MovementSpec` 的校验 + `MovementCapabilities.requiredRecoverabilityLevel` | 小；但要动 `MovementSpec` 多处构造点 |

**推荐 S3a** 的理由：
- 实现草案 §3.2「进入时保守、逃离时激进」正好就是 `SAFE_EXIT_REQUIRED` / `EMERGENCY_EXIT_REQUIRED` 的**字面语义**——接线后它们有明确未来用途，不是空占位；
- **若选 S3b，请务必在 `AI_DECISIONS.md` 登记一条**，否则半年后必然有人重新引入。

### 验证方式

- 复用 `fall_course` 的 `fall_recover_guard`（已在测"不可回收落点不生成 FALL 候选"）；
- 接线后应**仍 PASS**，且 detail 新增 `recoverability=PATH_REVERSIBLE`。

---

## §3 [P0-C] `FluidRiskPolicy` 零调用

### 现象

一个**已经写好**的挖掘前岩浆风险探针，**从未被任何代码调用**。

### 证据

```java
// src/main/java/com/dddgn/alice/survival/FluidRiskPolicy.java
public static String miningRefusal(ServerPlayer bot, BlockPos target) {
    if (level.getFluidState(target).is(FluidTags.LAVA)) return "fluid_risk_lava";
    for (BlockPos nearby : adjacentRiskCells(target)) {   // north/south/east/west/above/below
        if (level.getFluidState(nearby).is(FluidTags.LAVA)) return "fluid_risk_lava";
    }
    return null;
}
```

全仓库扫描 `FluidRiskPolicy` 的引用：**只有它自己的定义文件**。

### 影响

**它检查的与 D-037 检查的**不重叠**，所以这不是"重复保护"：

| 检查 | 层次 | 回答的问题 |
|---|---|---|
| D-037（`MovementHelper.avoidWalkingInto`、流体不可挖） | **Movement 通行性** | 身体别**进**岩浆 |
| `FluidRiskPolicy` | **挖掘前目标确认** | 挖穿后**邻格**岩浆会不会**流进来** |

具体后果：**目标格本身不是岩浆、其 6 个邻格中有岩浆源 → bot 挖穿 → 岩浆涌入**。当前**没有任何检查覆盖这个场景**。

### 建议处置

**推荐 S2a 接入**：在 `MiningPlanner` / `MineBlockRunner` 的目标确认阶段调 `FluidRiskPolicy.miningRefusal`，拒绝码进 `MiningBudget` 的失败枚举。

理由：
1. **覆盖范围不重叠**（见上）；
2. 它是实现草案 §3.3「**认知风险 / 未知的后果**」的**第一个真实实例**，而且**已经写好了**；
3. 成本 = 6 次 `getBlockState`，**可忽略**（对比 `SearchBudget` 的 20 000 节点）。

> 备选 **S2b 删除**：若你认为 D-037 已足够覆盖，就删掉它并在 D 记录登记原因。**但不许留着不接** —— 这是第 3 个死抽象。

### 验证方式

新增 `alice_test:fluid_mine_course`（目标格旁 1 格放岩浆源）+ 一键物品；
断言 `refusal=fluid_risk_lava`。
**涉及岩浆，必须有 `WINDOWS_CLIENT` 证据**，不能只看服务端日志。

---

## §4 [P1-A] 寻路链路没有"执行期事前"准入

### 现象

风险决策事实上存在**三种时机**，而你只实现了两种：

| 时机 | 现状 |
|---|---|
| ① **规划期准入** | ✅ 有，但**内联散落**在 provider 里 |
| ② **执行期事前** | ❌ **缺失** |
| ③ **执行期事后**（已出事） | ✅ `SurvivalSystem` |

### 证据

- **规划期**：`SurfaceMovementProvider.overshootColumnSafe`、`appendFall` 的 PILLAR 返回守卫 —— 内联，无统一入口。
- **执行期**：`PathSession` 有 `currentTargetStillValid()` / `futureTargetBlocked()` / `validPositions()` 重同步，但它们判定的是**"世界是否发生了变化"**，**不含即死危害判定**。
- **即死危害目前只靠事后**：`SurvivalSystem.HazardType.LAVA_CONTACT` / `SUFFOCATING` 的语义是**"已经发生了"**（`bot.isInLava()` / `bot.isInWall()`）。

**推论**：即"计划时安全，但我走到第 3 格时，第 4 格变成了岩浆"——**没有任何一层事前拦它**。

### 附带发现：区块加载

全仓库 `hasChunkAt` 命中**仅 2 处**，且都不在寻路链路上：

| 位置 | 用途 |
|---|---|
| `capability/InterfaceScanner.java:50` | C1 只读扫描 |
| `transfer/ChestEndpointRef.java:27` | 箱子端点校验 |

**寻路链路（`MovementContext` / `SurfaceMovementProvider` / `AStarMovementSearch`）一处都没有。**

> **这是服务端假人独有的失败模式**：Baritone 跑在客户端，有玩家视距兜底；Alice 没有。
> **因此你的 `Bariton_contrast` 对照实例测不出来这个**。见 Q1。

### 建议处置

**先确认 Q1 的事实**，再决定优先级：

- 若 `getBlockState` 在未加载区块返回 **void air** → 规划器**把它当可通行** → **真实缺陷**，优先级提前；
- 若**同步加载** → 是**性能问题**（搜索期间卡服），不是安全问题。

**动作**（任一情况都值得做）：在 `MovementContext` 加一层前置检查，拒绝码 `unloaded_chunk` 进 `PathingStats`（骨架已有，`record(code)` + 汇总输出，不用改日志格式）。

**同时**（实现草案 S4）：把规划期的两个内联守卫收成 `EdgeAdmission` 统一入口。

### 验证方式

- 区块加载：模拟器断言 + `PathingStats` 计数（服务端可测，无需客户端）；
- 统一入口：回归 13/13 不变红。

---

## §5 [P1-B] `MineTask` 重复调用 `SurvivalSystem.tick`

### 现象

同一 tick 内，维生监测被**两处**调用，并且**产生两套不同的任务终态**。

### 证据

| 位置 | 调用 |
|---|---|
| `bot/BotManager.java:688`（`onServerTick`） | `HazardState hazard = SurvivalSystem.tick(session.bot()); session.tick(hazard);` |
| `bot/BotManager.java:890-894`（`BotSession.tick`） | `if (shouldInterrupt) { … complete(lastTaskResult, SURVIVAL_INTERRUPTED); }` |
| **`task/MineTask.java:158-160`** | **`HazardState hazard = SurvivalSystem.tick(bot);` 又一次** |

**而同期的 `FollowTask` 明确不重复调用**：

```java
// task/FollowTask.java:39
* <p>维生危险中断由 {@code BotSession.tick} 统一处理（本任务不再重复调用 `SurvivalSystem`）。
```

### 影响

1. **行为等价**（`SurvivalSystem.Monitor` 有同 tick 缓存：`if (monitor.lastTick == gameTime …) return monitor.lastState;`），**所以不是可观测故障**。
2. **但终态记录分叉**：
   - `MineTask` 路径 → `return Status.FAILED` + `failureReason = survival_*`
   - `BotSession` 路径 → `complete(..., TerminalStatus.SURVIVAL_INTERRUPTED)`
   - **两条路会产生不同的 `TaskExecutionRecord`** —— 对"统计多少次死在岩浆里"是干扰。
3. 这是**已登记原则的局部违反**（D-062 的做法 + `FollowTask` 的注释就是那条原则）。

### 建议处置

**对齐 `FollowTask`：删掉 `MineTask.tick()` 里的重复调用**，统一由 `BotSession` 处理。

**注意一个副作用**：删掉后 `MineTask` 的 `failureReason` 不再被维生中断赋值 —— 需确认 `BotSession` 的 `SURVIVAL_INTERRUPTED` 终态里**带了足够信息**（当前 `lastTaskResult = "failed:" + interruptionReason` 已带原因码，应足够）。

### 验证方式

- 用既有岩浆/窒息场景触发中断，确认**只有一条** `SURVIVAL_INTERRUPTED` 终态记录；
- 回归 13/13 不变红。

---

## §6 [P1-C] 维生否决之后没有出口

### 现象

维生系统行使否决权后，**bot 停在原地不动**。

### 证据

- `SurvivalSystem` 已具备：`HazardState`（只读观测）✅、`shouldInterrupt()`（否决权）✅、`interruptionReason()`（理由）✅、javadoc 写死「**不负责决定逃生路线**」✅
- 中断后行为：

```java
// bot/BotManager.java ~890
lastTaskResult = "failed:" + SurvivalSystem.interruptionReason(hazard);
BotLog.warn("任务因维生危险中断: …");
complete(lastTaskResult, TaskExecutionRecord.TerminalStatus.SURVIVAL_INTERRUPTED);
```

→ **任务失败，无后续动作。**

### 影响

> **这就是"拒绝没有出口"的具体形态。**
> 泡在岩浆里的 bot：任务失败 → 站着不动 → **继续被烧**。

这与前几轮定下的规矩直接冲突：**谁否决，都得说清"那该去哪"**。这也是当前唯一的反例。

### 建议处置

**加一条纯查询，不新建服务**（保持"维生不找路"的边界）：

```java
/** 只回答"哪个方向是安全的"，不规划路径。 */
public static BlockPos nearestSafeRefuge(ServerPlayer bot, int radius);
```

然后由 `BotSession` 在中断后发起 **`WalkToTask`**（D-060 已迁移到新内核，可直接复用）。

**这样三方职责干净**：
- **维生**给目标落点；
- **寻路**给路径；
- **维生仍然不找路**（`HazardState` javadoc 的承诺不破）。

**排序建议**：这属于 `RISK_MODES_DISCUSSION.md` A.1 第 1 条「低风险模式现在只讨论不实现」的范畴，**建议排在框架完成之后**（实现草案的 S6）。但**现在就登记**，别让它悬着。

### 验证方式

构造岩浆接触场景，断言：中断后有 `WalkTo` 启动日志 + bot 离开危险格。
**需要 `WINDOWS_CLIENT` 证据**。

---

## §7 [P2-A] `LiveExecutionContext.policyVersion` 恒为 0

### 现象

"执行期冻结"的数据位**已经预留，但从未被填过**。

### 证据

```java
// pathing/core/session/PathSession.startSegment()
LiveExecutionContext context = new LiveExecutionContext(bot, level, sessionId, 0L, 0L, tolerance);
//                                                                          ↑   ↑
//                                                           currentWorldRevision, policyVersion
```

- 字段定义在 `LiveExecutionContext`（record），含 `Objects` 校验（`>= 0`）。
- 同类字段也出现在 `PlanningDependency.policyVersion`。
- **全仓库无任何写入点** —— 除 `PathSession` 的 `0L, 0L`。

### 影响

1. **本身无害**（`0` 通过了校验）。
2. **但它意味着：(a) 世界修订号从未被追踪；(b) 风险策略版本从未被追踪。**
3. **它让 S1 的工作无法被验证**：即使 `RiskProfile` 冻结了，**也没有机制能证明**"执行期看到的 profile 与搜索期是同一份"。

### 建议处置

**随 S1 一起接上**：`policyVersion` = `RiskProfile.version()`。
`currentWorldRevision` 可暂缓（需要世界修订号来源，属独立议题）—— **但要在注释里明确标出它仍是 0**，避免又被当成"已在工作"。

### 验证方式

- `segment_done` 日志新增 `risk=` 字段 = `policyVersion`；
- 断言一次搜索+执行全程 `policyVersion` **恒定**，重新规划后**变化**。

---

## §8 [P2-B] `PathingStats` 是清空式设计

### 现象

统计是**读-改-清**模式，不是可累积的。

### 证据

```java
// pathing/core/search/PathingStats.java
public static String snapshotAndReset() {
    if (COUNTS.isEmpty()) return "";
    …
    COUNTS.clear();      // ← 清空
    …
}
// 调用点：CorePathPlanner.plan() 末尾
```

同一个类只用 `Map<String,Integer> COUNTS`，`LinkedHashMap`，**无同步**。

### 影响

1. **今天是安全的**：`CorePathPlanner` javadoc 明说「同步主线程执行」。
2. **但设计留下了两个隐患**：
   - 将来若改成**异步搜索**（R7 曾有此设想），`snapshotAndReset` 会**丢数据**且 `LinkedHashMap` **非线程安全**；
   - 若**多个 bot 并行规划**（D-036 列为三大差异之一），统计会**互相污染**。
3. 这是**约束登记**，不是缺陷 —— 但**必须写下来**，否则届时无人知道。

### 建议处置

**只登记，不改**。在 `PathingStats` javadoc 补一句约束：

> 「仅同步主线程。异步或多 bot 并行规划前，必须改为 per-bot / per-search 的实例化统计（当前为全局静态 `LinkedHashMap`）。」

**注意这正好与 P0-A 同源**：两者都是"全局静态状态"在单 bot 同步假设下的产物。**修 P0-A 的时候顺手看一眼这里。**

### 验证方式

无需验证（文档登记项）。

---

## §9 [P2-C] 三个守卫默认值互不一致 + 死抽象复发性检查

### 9.1 三个默认值不一致

D-059 的「现状说明」原文：

> 「FALL 的 PILLAR 返回守卫与 ASCEND 的两条前置**目前仍默认开启**；按"S 默认 = Baritone 原样"原则**它们将来也应默认关闭，等用户确认后再翻**。」

| # | 守卫 | 当前默认 | 与"S = Baritone 原样"一致？ |
|---|---|---|---|
| 1 | `descend_overshoot` | **关** | ✅ 一致 |
| 2 | `FALL` 的 PILLAR 返回守卫 | **开** | ❌ 不一致 |
| 3 | `ASCEND` 的两条前置 | **开** | ❌ 不一致 |

**影响**：
- 风险画像的三个值互相矛盾，**"S 模式"当前没有明确语义**；
- 更重要的是：**会污染 Baritone 对照测试**（`Bariton_contrast` 已就绪）。用不同基准做对照，结论不可信。

**建议处置**：见 Q2。当前倾向 **「保持现状 + 显式登记为过渡态」** ——
因为 D-046 说"实现推迟到功能细节完成"，而框架**还没完成**（`RISK_MODES_DISCUSSION.md` A.1 第 1 条）。

> **关键不是"选哪边"，而是"别再悬着"**：现在这个不一致**没有任何文档标记**，只在 D-059 里有一句"待用户确认"。至少要让它出现在 `AI_DECISIONS.md` 里。

### 9.2 死抽象复发性检查（参考价值最高的一条）

**本次静态扫描发现 3 例死抽象/死代码：**

| # | 符号 | 状态 |
|---|---|---|
| 1 | `RecoverabilityLevel.SAFE_EXIT_REQUIRED` / `EMERGENCY_EXIT_REQUIRED` | 从未使用 |
| 2 | `IntrinsicReversibility.NOT_REVERSIBLE` | 从未使用 |
| 3 | `FluidRiskPolicy` 整个类 | 从未调用 |

D-046 已经总结过这个教训（`WorldView`）。**建议把检查方法固化下来**：

> **每次给内核加抽象前的 30 秒自检**：
> 1. `grep -rn "<新符号>" src/main/java` → **消费者 ≥ 1 个真实实现点**？
> 2. 是**枚举/常量**吗？→ 逐个值 grep，**这个值真的会被生产路径写入吗**？
> 3. 若答案是"将来会用" → **不要建**，写进 `AI_DECISIONS.md` 作为**触发条件**（参考实现草案 §5.4 对报价的处理方式）。

第 2 条是本清单里**唯一一条能防止未来重复犯错**的建议。前三例全部是"枚举值/类建好了但没人写"。

---

## §10 待确认的事实（Q1）

### Q1. `ServerLevel.getBlockState()` 在未加载区块上的行为？

**这不是设计问题，是事实问题。我不能从静态阅读断言。**

| 若行为是 | 则 |
|---|---|
| 返回 **void air**（`Blocks.VOID_AIR`） | `MovementHelper.canWalkThrough` 可能为真 → **规划器把它当可通行** → **真实缺陷**，P1-A 优先级提前 |
| **同步加载区块** | 是**性能问题**（搜索期间卡服/加载爆炸），不是安全问题 |
| **其它**（抛异常 / 返回默认） | 需要单独定义语义 |

**为何必须先确认**：
1. 它决定 P1-A 是"缺陷"还是"性能项"；
2. **Baritone 对照测试测不出来**（Baritone 有玩家视距兜底）；
3. 推理链会分叉到完全不同的实现方向。

**获取方式（任一）**：
- 你直接告诉我（如果你已知）；
- **我写一个服务端探针**：在未加载区块坐标上调用 `getBlockState` / `hasChunkAt` / `canWalkThrough`，把返回值与 chunk 加载状态打进日志。**无需客户端，服务端可跑**。

---

## §11 需要拍板的问题

### Q2. 三个守卫默认值最终要一致吗？（P2-C）

| 选项 | 效果 |
|---|---|
| **全关**（三者都对齐 Baritone） | 最"原样"；但 bot 会尝试不可回收落差。符合"先框架后用" |
| **全开** | 最保守；但**偏离 Baritone 基准，S 模式失去意义** |
| **保持现状 + 显式登记** | 承认过渡态，在 profile 里如实标出 |

**倾向第三个**，理由：D-046 说"实现推迟到功能细节完成"，框架还没完成；强行统一会**污染 `Bariton_contrast` 对照测试**。

### Q3. `MineTask` 里的重复 `SurvivalSystem.tick()` 怎么处理？（P1-B）

**倾向：删掉，对齐 `FollowTask`。** 但需你确认 `BotSession` 的 `SURVIVAL_INTERRUPTED` 终态信息是否已足够（当前 `lastTaskResult = "failed:" + reason`，看起来够）。

### Q4. "报价"延后是否接受？（实现草案 §5.4）

早期主张把预算从"上限"改成"报价"。**理由仍成立**（上限是报价的特例；上限会产生"399 tick 安全、401 tick 直接 DENY"的悬崖），**但今天不引入** —— 消费者为零，会变成**第 4 个死抽象**，违反 D-046。

**倾向：延后 + 登记触发条件**（第一个"消耗型风险源"落地时再引入）。

---

## §12 明确**不建议**动的（避免过度修复）

> 这一节和上面的清单**同等重要**。以下看起来像问题，但**不是**。

| 项 | 为什么别动 |
|---|---|
| `.alice-supervision/skills/`（21 个 skill） | **仍在有效使用**。`START_HERE.md` 明确「Skills 是技术知识库，不是审批流程」。**别跟着"清理监督工作流"一起删** |
| `BotManager` 用 `TickEvent.Phase.END` | **D-038 已撤回**，Forge 源码证明 `START`/`END` 等价。**不要再改相位** |
| `descend_overshoot` 默认关闭 | 这是 **D-059 的明确裁定**（对齐 Baritone），不是遗漏 |
| `MiningBudget` 用 tick 而非方块数 | 它是**破坏耗时预算**，不是资源充足性预算。**两回事，别合并** |
| `PathingStats` 清空式 | 在**同步主线程**前提下是正确的（见 P2-B，只登记） |
| `SurvivalSystem` 不找路 | **这是正确的边界**（`HazardState` javadoc 写死了）。补"去向"时**不要**把寻路塞进去 |
| `RecoverabilityLevel` 的存在本身 | 问题不是"它该不该存在"，是"**它从未被计算**"。要么接线，要么删除 —— 但**别只是留着** |

---

## §13 建议的执行顺序

```
① Q1 事实确认（服务端探针，可立即做，不依赖你）
        ↓
② P0-A + P2-A + P2-C  一起做（S1：RiskProfile 随请求冻结 + policyVersion 接上）
        ↓                ← 一次解决 3 条，且是 D-046 已定的计划
③ P0-C 裁定（S2：FluidRiskPolicy 接入或删除）
④ P0-B 裁定（S3：可回收性接线或删除）
        ↓
⑤ P1-A（S4 统一准入入口 + 区块加载）
⑥ P1-B（MineTask 重复调用，1 行级修复）
        ↓
⑦ P1-C（S6 维生去向，框架完成后）
```

**为什么 ② 排最前**：它是唯一一次改动**同时**解决 3 条（P0-A / P2-A / P2-C）、**有 3 个真实消费者**、且**正是 D-046 已经写好的计划** —— 不违反"不引入空抽象"。
**为什么 ③④ 紧跟**：它们都是**死代码裁定**，二选一即可，决策成本低（S2 更便宜：代码已写好，只差接线）。

---

## §14 一句话总结

> **三例死抽象（`RecoverabilityLevel` / `IntrinsicReversibility` / `FluidRiskPolicy`）是本清单最重要的事 —— 尤其第一例，它把项目宣称的核心差异做成了恒定值。第二个重点是 `RiskSwitches` 的全局静态，它阻塞 D-046 落地。其余是缺口和登记项。**
