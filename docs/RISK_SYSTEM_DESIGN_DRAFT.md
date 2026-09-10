# Alice 风险系统 · 实现草案

> ⚠️ **非最终方案（草案）**——本文**未定案**，不作为当前实现授权。
> 出处：用户与另一 AI 于 2026-09-10 讨论产出；作者原话：「这套方案与评估顺序绝不是最终定案，还需要大量分析和评估修改。」
> 复核结论见 [`RISK_SYSTEM_REVIEW_20260910.md`](RISK_SYSTEM_REVIEW_20260910.md)（9 条断言逐条核对＋待确认事实 Q1 的反编译答案＋2 处机制/事实校正）。
> 入库时仅做两处客观校正（§2.2 归档路径、§6 回归项数），其余内容保持原文。

- 日期：2026-09-10
- 代码基线：`693e419`（2026-09-10 02:51）
- 状态：**草案，非最终定案**。作者原话：「这套方案与评估顺序绝不是最终定案，还需要大量分析和评估修改。」
- 配套文档：`RISK_SYSTEM_ISSUE_LIST.md`（缺陷与待裁定项单独成文）
- 性质：设计层 + 实施切片。**不含代码改动。**

> **本文怎么读**
> - **只想懂大意**：读 §1（人话总览）+ §3 每条的黑体结论 + §7 的表格。
> - **要动手做**：读 §5（契约）+ §7（切片）。
> - **要判断"为什么这么设计"**：读 §3 的理由 + §8（与既有决策对齐）。
> - **要查"哪里不对劲"**：去看配套的《问题修复清单》（`RISK_SYSTEM_ISSUE_LIST.md`）。

---

## §0 目录

| 节 | 内容 |
|---|---|
| 1 | 人话总览：这个方案在干什么 |
| 2 | 前提与基线（含已作废内容） |
| 3 | 八条设计原则（含理由） |
| 4 | 术语对齐表 |
| 5 | 契约形态（三种时机 + 报价延后条件） |
| 6 | 现实基线：你已经有什么 |
| 7 | 实施切片 S1–S6 |
| 8 | 与既有决策的对齐 |
| 9 | 遥测与日志扩展点 |
| 10 | 与《问题修复清单》（`RISK_SYSTEM_ISSUE_LIST.md`）的分工 |

---

## §1 人话总览

### 1.1 一句话

> **别把"风险"做成一个全局的总开关，要把风险做成"每条路上的价格标签"。**

### 1.2 风险系统 × 维生系统：按"什么时候做决定"分，不按"危险有哪几种"分

打个比方，你出门旅行：

| 角色 | 对应系统 | 干什么 |
|---|---|---|
| 出门前看地图定路线 | **风险系统** | 哪条路能走、走多久、干粮够不够 |
| 路上突然被狗咬 | **维生系统** | 立刻处理：打、跑、包扎 |

区别是**时间点**，不是**危险种类**。

三句口诀：

1. **风险系统是闸门**（决定要不要走）；
2. **维生系统是否决权**（决定还能不能继续走）；
3. **谁否决，都得说清"那该去哪"** —— 否则两方都保守，Bot 就原地不动。

### 1.3 为什么不能做全局模式开关

同一片岩浆洞里，**有的边安全、有的边致命**。

- 若因"附近有岩浆"把全局切保守 → **在最需要能力的地方把能力关掉**（爬出洞可能要 parkour，而低风险模式恰好禁了 parkour）。
- 模式翻转还**非单调**：降级 → 路径变长/无解 → 乱走 → 进入新风险 → 再降级，会抖。

**正解**：把"模式"降格成**每个边的硬约束 + 每个边的风险价格**，在搜索时按边评估。

---

## §2 前提与基线

### 2.1 快照与真实进度

| 项 | 值 |
|---|---|
| HEAD | `693e419` — `docs: 记录 D-079…与 2026-09-10 仓库整理结果` |
| 提交时间 | 2026-09-10 02:51 (+08:00) |
| 分支 | `master` 单线，无 tag |
| 相对 9-03 的增量 | **112 个提交**，890 文件变化，+51814 / −40128 |

作者口述的 6 天工作：移植了 Baritone 内核、寻路系统大部分对齐 Baritone、站位选优挖掘系统完全重写并与新寻路结合。寻路与挖掘「几乎完成」。

### 2.2 已作废（不再作为有效信息）

| 已作废 | 说明 |
|---|---|
| 监督员工作流 | 纯流程产物已删除，文档归档至 `docs/archive/legacy-2026-08/`（17 份设计文档）与 `.alice-supervision/archive/legacy-2026-08/`（30 份历史材料） |
| `docs/START_HERE.md` 中的"每个小改动都要 active plan / 审核包 / HANDOVER" | 现行文件明确写：**不要求** |
| `HARD_PATH` / `SOFT_SURFACE` 术语 | 已由 **D-076 寻路红线**取代（语义不变，表述更新） |
| `PATHING_REFACTOR` / `MINING_SAFETY_AND_PLANNING` / `PRODUCT_ARCHITECTURE_ROADMAP` 等 | 已归档到 `docs/archive/legacy-2026-08/` |

> 注意：`.alice-supervision/skills/`（21 个 skill）**仍在有效使用**——`START_HERE.md` 明确「Skills 是技术知识库，不是审批流程」。别误删。

### 2.3 作者已确认的立场

- Alice 与 Baritone 的区别**只有三点**（D-036 与 `AI_PROJECT_STATE.md`）：
  1. **Bot 可回收性安全策略**；
  2. **多层任务失败向上传递**（任务层 → LLM 决策层）；
  3. 未来 Bot 并行运行接口。
- 风险画像用**一组开关**表达，**不用 H/G/S 三态等级枚举**（D-059，作者："等级枚举后续怎么处理我不理解"）。
- **S 模式默认 = Baritone 原样高风险**；风险评估的产物是"把哪些部分换成低风险"。
- 路线优先级（`RISK_MODES_DISCUSSION.md` A.1）：**先按 Baritone 原样把整套框架搭好、先能用**；低风险模式现在**只讨论不实现**。
- 低风险模式只需**禁用**高风险 Movement —— 因为允许集合里有 `PLACE_STEP_AND_TRAVERSE` / `BREAK_AND_TRAVERSE`，禁掉某类高风险 Movement 后规划器会**自己拼出楼梯/绕行**（2026-09-09 客户端实证：4 格落差场景规划器用 `TRAVERSE + PLACE_STEP×4` 自己搭台阶下去，根本没用 FALL）。
- 维生系统拥有**最大话语权**（生命不可协商）。
- 食物与敌对生物归维生系统（**目前完全空白，未开工**）。

---

## §3 八条设计原则

> 每条先一句大白话，再给理由。**这些是设计层结论，与代码实现无关，不因重构失效。**

### 3.1 风险是"边"的属性，不是 Bot 的全局状态

**大白话**：同一片岩浆洞里，有的路能走有的不能。别一刀切。

**理由**：全局模式会在最需要能力的地方切断能力，且翻转非单调（见 §1.3）。
**工程含义**：风险价格必须能被 A\* 直接吸收（`f = g + h + r·w`），而不是一个外部状态机。

### 3.2 进入时保守，逃离时激进

**大白话**：先算清"哪些地方我还能跑掉"，然后只在能跑掉的范围内小心走。跑的时候允许用全力。

**理由**：这样低风险模式**永远不会把 Bot 困死**——因为"跑掉"本身允许用全力。
**工程含义**：以已知安全点为种子，用**激进包络**做反向可达性搜索得到"逃生集"；正向搜索只在逃生集内用**保守包络**展开。
**已有对应物**：`RecoverabilityLevel.SAFE_EXIT_REQUIRED` / `EMERGENCY_EXIT_REQUIRED` 的字面语义就是这条（见《问题修复清单》（`RISK_SYSTEM_ISSUE_LIST.md`）P0-B）。

### 3.3 认知风险是第一号真实死因

**大白话**：它看不见的风险对它不存在。正要挖穿的那堵墙后面是不是岩浆湖，它不知道。

**理由**："挖一个未知方块"和"走一条已探明走廊"在代价图上一样，但**前者有未观测的后果**。
**工程含义**：代价函数里应有**信息代价**项——偏好已知地形，惩罚后果不可观测的动作。
**推论**：挖掘的风险守护不该是"打风险分后决定挖不挖"，而应是**先获取信息再承诺**（薄壁探测、视线校验、分段开挖）。
**已有对应物**：`FluidRiskPolicy` 正是这条的第一个实例（见《问题修复清单》（`RISK_SYSTEM_ISSUE_LIST.md`）P0-C）。

### 3.4 反过度保护：守太紧也是 bug

**大白话**：所有人都在防"放太松把 Bot 搞死"，很少人防"**守太紧，Bot 哪也不去**"。

**理由**：这正是 Alice 与 Baritone 的**真正分野**——Baritone 被拒之后没人可问，拒绝是静默的；Alice 可以问 LLM。
**工程含义**：
- 权重必须有**上界**（`maxRiskCostPerEdge`）；
- **拒绝必须产出机器可读的理由集**（哪条边、被哪条规则、缺什么资源），无解时**升级 LLM**；
- 这样"过度保护"从 bug 变成功能——**风险系统成为 LLM 的情报源**。

### 3.5 单调性：时间窗越长，拒绝集只增不减

**大白话**：允许待得越久，能去的地方只会更少，不会更多。

**形式化**：`deny(dwell = t2) ⊇ deny(dwell = t1)` 当 `t2 > t1`。

**理由**：没有它，"规划时冻结"会被执行期推翻——规划期按 40 tick 判"安全"，执行到那儿待了 400 tick，预言机改口"危险"，**规划时的承诺被推翻**。
**有了它**：规划期永远比执行期保守，执行期**只会同意、不会翻案**。
**工程含义**：规划期用**上界式**粗参数（长 dwell、宽松半径），执行期用精确参数。由聚合器强制单调，违反时**取保守值**。

### 3.6 一律用绝对时间戳，禁止相对时长

**大白话**：记"几点到"，别记"还剩几秒"。

**理由**：搜索本身要花时间（可能几百毫秒），"还剩 3 秒"会被搜索过程悄悄吃掉，**安全余量无声蒸发**。这类 bug 极难查。
**注意**：`SearchBudget` 用的 `maxMillis` 是**预算**语境，没问题；但**风险判定**的 TTL 必须用绝对 tick。

### 3.7 未知 ≠ 禁止，但要计价

**大白话**："不知道"要算"有点贵"，不能算"禁止"——否则 Bot 一步都不肯迈。

**唯一例外**：L0 不可协商安全底线（虚空、岩浆、世界边界、未加载区块）是硬拒。

### 3.8 回程代价必须进代价函数

**大白话**：下落永远比上升容易。只看当前这一步，Bot 会兴高采烈跳进爬不出来的坑。

**理由**：这就是"卡在峡谷底"的全部成因。
**工程含义**：`f(n)` 里必须有**回程代价估计**，不是可选项。
**已有对应物**：`FALL` 的 **PILLAR 返回守卫**（D-058）就是这个思想的第一个实现。**它应该被看作可回收性机制的样板，而不是一个孤立的特例。**

---

## §4 术语对齐表

> **原则**：新术语只在**旧术语确实不够用**时引入。你已经有一套词，我改口对齐你。

| 通用叫法 | 对上你的代码应该叫 | 理由 |
|---|---|---|
| 安全预言机 `SafetyOracle` | **准入判定 `RiskAdmission`** | 你已有 `pathing/risk` 包；"oracle" 是你没有的概念 |
| 规划上下文 `PlanningContext` | **`PathRequest` + `RiskProfile` 快照** | D-046 已点名这条贯通路径，别另起炉灶 |
| 暴露预算 `ExposureQuote` | **（延后，见 §5.4）** | 今天的消费者为零 |
| 撤离服务 `RetreatService` | **`SurvivalSystem` 的"去向"查询** | 不新建服务，复用既有类 |
| L0 不变量 | **不可协商安全底线** | `RISK_MODES_DISCUSSION.md` §5 的既有用词 |

---

## §5 契约形态

### 5.1 关键结构问题：风险决策事实上分**三种时机**，而你只实现了两种

| 时机 | 何时 | 现状 | 缺什么 |
|---|---|---|---|
| **① 规划期准入**（搜索时逐边） | 每次搜索 | `SurfaceMovementProvider` 内联：`overshootColumnSafe`、`appendFall` 的 PILLAR 返回守卫 | **没有统一入口**；每个新守卫都要改 provider |
| **② 执行期事前**（每 tick/每段） | 执行中 | `PathSession.currentTargetStillValid` / `futureTargetBlocked` / `validPositions` 重同步 | 判定的是**"世界变了吗"**，**不含即死危害** |
| **③ 执行期事后**（已经出事） | 出事时 | `SurvivalSystem` | ✅ 有，且设计正确 |

> **关键缺口**：`SurvivalSystem` 的 `LAVA_CONTACT` 意味着**已经泡在岩浆里了**（事后）；
> 规划期的 `avoidWalkingInto` 是事发前的（事前）。
> **两者之间没有"执行期事前"这一环** —— 即"计划时安全，但我走到第 3 格时，第 4 格变成了岩浆"。

### 5.2 规划期准入的形态（不新建接口，先统一内联）

```java
// pathing/risk/ 下
public interface EdgeAdmission {
    /** @return null = 放行；非 null = 拒绝码（直接进 PathingStats，自解释） */
    String refusal(EdgeAdmissionContext ctx);
}
```

**硬要求（防止它变成第 4 个空抽象）**：
引入时**必须**把**已存在的两处**搬进来——
1. `overshootColumnSafe` → `descend_overshoot`（D-059 的第一个开关）
2. `appendFall` 的 PILLAR 返回守卫（D-058）

让第一版就有 **2 个真实实现 + 1 个真实消费者**（`SurfaceMovementProvider`）。
**只有 1 个实现就等** —— 1 个实现叫内联，不叫抽象。

### 5.3 执行期事前的形态

在 `MovementContext` / `PathSession` 的既有检查旁边补一层**即死危害 + 可达性前置**检查，拒绝码进 `PathingStats`。
具体候选见《问题修复清单》（`RISK_SYSTEM_ISSUE_LIST.md`）P1-A（区块加载）。

### 5.4 暴露预算：**延后引入**，并给出触发条件

**这是对早期方案的一处自我修正。**

早期主张把预算从"上限"改成"报价"（`freeTicks` + `costPerExtraTick` + `hardCapTicks`）。理由仍然成立（**上限是报价的特例**，选报价不丢表达能力；上限会产生"399 tick 安全、401 tick 直接 DENY"的悬崖）。**但今天不引入**，因为：

| 报价字段 | 今天的真实消费者 |
|---|---|
| `freeTicks` / `costPerExtraTick` | **没有** |
| `maxFallDistance` | 有，但它是**硬约束**（`FALL` dy ∈ {-2,-3}），硬约束不需要报价 |
| `hardCapTicks` | **没有** |

而 D-046 明文写着：

> 「**只做文档与决策预留，暂不引入 `RiskProfile` 空类型/空字段**——`WorldView` 的教训是'零实现的空抽象会被审计记为缺陷'。」

**结论**：报价今天会变成第 4 个死抽象。**不引入**，但把设计结论转成一条**触发条件**：

> **触发条件**：当**第一个"消耗型风险源"**落地时，引入报价形态。
> 候选触发源（推荐顺序）：
> 1. **暴露时长**（露天停留 → 被敌对生物发现的机会）；
> 2. **工具耐久**（`MiningBudget` 已有 `estimateBreakTicks`，是天然接口）；
> 3. **饱食度消耗**（"走 1000 格耗多少"）。
>
> **判断标准**：只要某个风险第一次需要"**贵但可接受**"这个中间态，报价就有消费者了。
> 在那之前，一律用**硬约束 + 权重**，不要提前建抽象。

### 5.5 权力结构（防止出现第二个否决通道）

| 通道 | 角色 |
|---|---|
| **规划期准入 / 执行期事前** | 做**过滤 + 定价**，**不宣称否决权** |
| **`SurvivalSystem.shouldInterrupt`** | 维生**唯一且绝对的嘴**（硬否决在这里） |

> **预算是情报，不是否决。否决只有一处。**

### 5.6 维生否决之后：必须有出口

现状：`SurvivalSystem.shouldInterrupt()` → `BotSession.complete("failed:survival_...", SURVIVAL_INTERRUPTED)` → **任务失败，bot 停在原地**。

> **这就是"拒绝没有出口"。** 岩浆里的 bot 会：任务失败 → 站着不动 → 继续被烧。

**建议的最小形态**（**不引入 `RetreatService`**）：

```java
/** 只回答"哪个方向是安全的"，不规划路径。 */
public static BlockPos nearestSafeRefuge(ServerPlayer bot, int radius);
```

**硬边界**：`HazardState` 的 javadoc 已写死「**不负责决定逃生路线**」——**保持这个承诺**。
所以：**维生给目标落点，寻路（`WalkToTask`，D-060 已迁移）给路径。谁也不越界。**
这也是"逃跑是一个*目标*，不是一段*移动*"的字面落地。

---

## §6 现实基线：你已经有什么

> **好消息**：以下这些不用重做，本方案只做"改名对齐 + 补缺口"。

| 设计要素 | 你的现状 | 位置 |
|---|---|---|
| 风险模式用**一组开关**而非等级枚举 | ✅ | `pathing/risk/RiskSwitches.java` + `/alice risk` |
| 维生拥有**否决权** | ✅ | `SurvivalSystem.shouldInterrupt()` |
| 维生**绝不自己找路** | ✅ 注释写死 | `HazardState` javadoc |
| 否决**必须带理由** | ✅ | `SurvivalSystem.interruptionReason()` |
| 执行期**冻结**在路径上（数据位） | ✅ | `LiveExecutionContext.policyVersion` |
| 拒绝原因要**计数**（调参依据） | ✅ 骨架已有 | `PathingStats.record/snapshotAndReset` |
| 任务终态**可回填** | ✅ | `TaskExecutionRecord` + `recordTerminal` |
| 风险决策可**被上层覆盖** | ✅ 决策链已定 | D-046：任务前置 → 内核推导 → LLM 覆盖 → 玩家强制 |
| 落差守卫的**第一个实例** | ✅ | `descend_overshoot` |
| 维生的**监测类型** | ✅ | `HazardType`：`WATER_CONTACT/ON_FIRE/LOW_AIR/SUFFOCATING/LAVA_CONTACT` |
| 挖掘预算 | ✅（但是 **tick 预算**，非资源充足性） | `MiningBudget`（10× 倍数、1/2/4× 分档、6 tick 基线） |
| 回归 + 覆盖断言 | ✅ | `alice:pathing_regression`（14 项 + `coverage=` 断言） |

### 6.1 其他值得记录的基线事实

| 项 | 事实 |
|---|---|
| 根因分析文档 | D-001 ~ D-079 全部登记在 `AI_DECISIONS.md`；D-024 补充、`ALIGNMENT_OPEN_QUESTIONS.md` §Q1 有「局部可回收性不变式」的逐类型证明 |
| 寻路红线 | **D-076**：默认纯通行；破坏/放置由上层显式授权 + 预算闸门；`SEARCH_LIMIT` ≠ `UNREACHABLE` |
| Baritone 对照实例 | `Bariton_contrast` 已就绪（Forge 47.4.23 + Baritone 1.10.5） |
| 客户端证据 | `.alice-supervision/client-tests/`（19 个证据目录） |
| 成本模型 | 已按 Alice 实测标定（`TRAVERSE 6 / DIAGONAL 8 / ASCEND 10 / DESCEND 16 tick`），非照抄 Baritone 常量（D-040） |

---

## §7 实施切片

> **两条硬规矩**：
> ① **先有消费者，再建类型**（D-046）；
> ② **每片自带游戏内验证入口**，且必须声明目标验证等级（`IMPLEMENTED`/`COMPILES`/`SERVER_TESTED`/`WINDOWS_CLIENT`/`USER_ACCEPTED`）。
>
> **排序依据**：先消除**不一致**，再补**缺口**，最后才是**新能力**。
> S1/S2/S3 都是"消除既有不一致"，代价最小、收益最大。

### S1 ⭐ 把三处风险决策统一为**随请求冻结**的 `RiskProfile`

**解决什么**
- `RiskSwitches` 是全局静态（见《问题修复清单》（`RISK_SYSTEM_ISSUE_LIST.md`）P0-A）；
- D-059 明文写着"三个默认值不一致，待用户确认"；
- D-046 第 3 条要求"每个任务层可自定义"。

**为什么排第一**：它是唯一一片**同时**满足
「**≥3 个真实消费者**（不是空抽象）+ 解决一个**已登记的**不一致 + 正是 D-046 点名的贯通路径」。

**三个真实消费者**（都已存在，都是硬编码）：

| # | 决策 | 现在在哪 | 当前默认 |
|---|---|---|---|
| 1 | `descend_overshoot` | `RiskSwitches`（**全局静态**） | 关（对齐 Baritone） |
| 2 | `FALL` 的 PILLAR 返回守卫 | `SurfaceMovementProvider.appendFall`（内联） | 开 |
| 3 | `ASCEND` 的两条前置 | `SurfaceMovementProvider` / `AscendExecutionFactory` | 开 |

> **三个默认值目前不一致**：1 是"Baritone 原样"，2/3 是"更保守"。按 D-059 的"S 默认 = Baritone 原样"原则**它们应该一致**。

**触及文件**

| 文件 | 改动 |
|---|---|
| `pathing/risk/RiskSwitches.java` | 保留（作为**默认值来源 + 游戏内切换入口**），但不再是消费者的直读对象 |
| `pathing/risk/RiskProfile.java` | **新增**（record，不可变，3 个字段起步，非遗留空类型） |
| `pathing/core/search/PathRequest.java` | 加 `RiskProfile` 字段 + 静态工厂默认值 |
| `pathing/core/search/MovementContext.java` | `allows()` 旁边加 `risk()` 转出 |
| `pathing/core/search/SurfaceMovementProvider.java` | 守卫改读 `context.risk()`，不再读全局静态 |
| `pathing/core/DescendExecutionFactory.java` | 同上 |
| `pathing/core/session/PathSession.java` | `LiveExecutionContext.policyVersion` 填 `profile.version()`（该字段目前恒为 0，见 P2-A） |

**验证入口**：`/alice risk` 增加 `profile` 子命令显示当前快照；复用 `fall_course` / `pathing_course` / 回归 13/13 **不变红**。

**目标等级**：`SERVER_TESTED`（回归全绿 + `/alice risk profile` 输出），`WINDOWS_CLIENT` 抽测。

---

### S2 裁定 `FluidRiskPolicy`

**解决什么**：一个已写好、**零调用**的挖掘前岩浆探针（P0-C）。

**两条路，你选一条**（都不许留现状）：

| 选项 | 动作 | 适用 |
|---|---|---|
| **S2a 接入** | 在 `MiningPlanner` / `MineBlockRunner` 的目标确认阶段调 `FluidRiskPolicy.miningRefusal`，拒绝码进 `MiningBudget` 的失败枚举 | 你认可"挖穿后邻格岩浆流入"是真实风险 |
| **S2b 删除** | 删类 + 在 D 记录登记原因（例：被 D-037 覆盖） | 你认为 D-037 已足够 |

**推荐 S2a**，理由：
1. 它检查**邻格**（6 邻位），D-037 检查**目标格本身**——**覆盖范围不重叠**；
2. 它是"认知风险"的第一个真实实例，且**已经写好了**；
3. 成本仅 6 次 `getBlockState`，**可忽略**。

**验证入口**：新增 `alice_test:fluid_mine_course`（目标格旁 1 格放岩浆源）+ 一键物品；断言 `refusal=fluid_risk_lava`。

**目标等级**：`SERVER_TESTED` + `WINDOWS_CLIENT`（涉及岩浆，必须有客户端证据）。

---

### S3 给「可回收性」接线

**解决什么**：`RecoverabilityLevel` / `IntrinsicReversibility` 是**已登记的死抽象**（P0-B），而它承载的是你**亲口说的项目签名能力**。

**两条路**：

| 选项 | 动作 | 代价 |
|---|---|---|
| **S3a 接线** | 让 `FALL` 的 PILLAR 返回守卫**产出** `evaluatedRecoverability`（守卫通过 = `PATH_REVERSIBLE`，否则该边不生成）；`MovementSpec` 里那个**永不触发**的校验立刻开始有意义 | 中 |
| **S3b 删除** | 删两个枚举 + `MovementSpec` 的校验 + `MovementCapabilities.requiredRecoverabilityLevel` | 小，但动多处构造点 |

**推荐 S3a**，理由：
- 「可回收性」是项目签名能力，**它在数据模型里却从未被计算**——这比没有更危险，因为它会让未来的人（和 AI 会话）以为它在工作；
- `SAFE_EXIT_REQUIRED` / `EMERGENCY_EXIT_REQUIRED` 一旦接线就有**明确用途**：§3.2 的"进入时保守、逃离时激进"就是这两个等级的字面语义；
- **若选 S3b，请顺带在 `AI_DECISIONS.md` 登记一条**，否则半年后会有人重新引入它。

**验证入口**：复用 `fall_course` 的 `fall_recover_guard`（已在测"不可回收落点不生成 FALL 候选"）——接线后应**仍 PASS**，且新增 detail 显示 `recoverability=PATH_REVERSIBLE`。

**目标等级**：`SERVER_TESTED`。

---

### S4 统一内联守卫为单一准入入口

**解决什么**：`SurfaceMovementProvider` 里的守卫开始散落（§5.1 的规划期相）。

**前置**：S1 完成。

**动作**：引入 `EdgeAdmission` + `EdgeAdmissionContext`，把**已存在的两个**搬进去（`overshootColumnSafe`、`appendFall` 的 PILLAR 返回守卫）。

**硬要求**：**第一版必须有 2 个真实实现**。

**验证入口**：回归 13/13 不变红。

**目标等级**：`SERVER_TESTED`。

---

### S5 执行期事前准入（区块加载等）

**解决什么**：寻路链路**没有"执行期事前"这一环**（§5.1、P1-A）。

**先要确认事实**（不能假设）：`ServerLevel.getBlockState()` 在**未加载区块**上的实际行为。
- 返回 void air → 规划器**把它当可通行**（真实缺陷）
- 同步加载 → 是**性能问题**（搜索期间卡服），不是安全问题

**动作（确认是缺陷后）**：在 `MovementContext` 加 `hasChunkAt` 前置检查，拒绝码 `unloaded_chunk` 进 `PathingStats`。

**为何排在 S1–S4 之后**：这是**服务端假人独有**的失败模式（Baritone 有玩家视距兜底），所以 **Baritone 对照测试测不出来**，只能靠模拟器/断言。先确认机制再动手。

---

### S6（可选，框架完成后）给维生补"去向"

**解决什么**：§5.6 —— `SURVIVAL_INTERRUPTED` 之后 bot 停在原地被烧。

**动作**：`SurvivalSystem.nearestSafeRefuge(bot, radius)` 纯查询 + 由 `BotSession` 在中断后发起 `WalkToTask`。

**硬边界**：`SurvivalSystem` **仍然不找路**。

**排序理由**：属于 `RISK_MODES_DISCUSSION.md` A.1 第 1 条"低风险模式现在只讨论不实现"的范畴。**放最后。**

---

### 依赖与顺序总览

```
S1 (RiskProfile 随请求冻结)     ← 最关键，解锁 S4；同时落地 D-059 待确认项
 ├─ S2 (FluidRiskPolicy 裁定)   ← 独立，可并行
 ├─ S3 (可回收性接线)           ← 独立，可并行
 └─ S4 (统一准入入口)           ← 依赖 S1
S5 (执行期事前: 区块加载)        ← 先确认机制，独立
S6 (维生去向)                   ← 最后，框架完成后
```

---

## §8 与既有决策的对齐

> 以下每条都是**作者的既定决策**，本方案**不得与之冲突**。冲突项单列。

| 决策 | 内容 | 是否冲突 |
|---|---|---|
| **D-076** | 寻路默认纯通行；破坏/放置由上层显式授权 + 预算闸门；`SEARCH_LIMIT` ≠ `UNREACHABLE` | ✅ 不冲突。准入判定只做**过滤 + 定价**，不授予权限 |
| **D-059** | 过冲红线**默认关闭**（对齐 Baritone）；风险画像用**开关**不用等级枚举 | ✅ 不冲突，且 S1 正好落地它 |
| **D-046** | 三模式确认但**暂不实现**；**不引入空抽象**；`RiskProfile` 贯通 `PathRequest→MovementContext→provider→执行器` | ⚠️ **修正已吸收**：早期"三接口 + 桩实现"违反"不引入空抽象"，已按 §5.4 撤回。所有切片改为"**先有消费者，再建类型**" |
| **D-050** | Movement 先做 Baritone 原样语义，**安全守卫后置** | ✅ 不冲突。§7 全部排在框架之后 |
| **D-024 / D-058** | 落差红线 ≤1 格；`FALL` 允许 2~3 格**但必须过 PILLAR 返回守卫** | ✅ 不冲突。该守卫是 S4 要搬进统一入口的实例之一 |
| **D-037** | 流体不可挖；危险方块扩表；删任意 `BlockItem` 兜底 | ✅ **互补**：它管"身体不进岩浆"，`FluidRiskPolicy` 管"挖穿后岩浆流进来" |
| **D-062** | 维生中断由 `BotSession.tick` 统一处理（`FollowTask` 不再重复调用） | ⚠️ **`MineTask` 违反** → 见 P1-B |
| **D-040** | 成本模型按 Alice 实测标定，不照抄 Baritone 常量 | ✅ 本文所有数值均须按此原则重新标定 |
| **D-036** | Alice 是 Baritone 兼容内核，只做**目标差异**自制 | ✅ 不冲突。风险系统属于"目标/安全差异"层 |

---

## §9 遥测与日志扩展点

> **不要新建日志框架。** 你已有三个可用的扩展点。

| 扩展点 | 现状 | 建议怎么用 |
|---|---|---|
| `PathingStats` | `record(code)` + 每次规划结束汇总输出并清零 | §5.2 的**拒绝码**直接进这里（`unloaded_chunk` / `overshoot_cliff` / `recoverability_below_required`…）。**已有 `[PathingStats]` 日志行不用改格式** |
| `PathSession` 的 `segment_done` | 已有 `ticks=` 遥测（D-051 用过） | 加 `risk=` 字段（本段实际用的 `profile.version`）——这条**正好验证 S1 的冻结是否生效** |
| `TaskExecutionRecord` | 已有终态记录 + `recordTerminal` | 加 `outcome` **回填**通道：任务终态时回填"这次安全策略选对了吗"。**这是以后调默认值的唯一依据** |

**采样建议**：`PathingStats` 全量（规划次数本来就少）；`segment_done` 全量；`TaskExecutionRecord` 必全量。

**约束提醒**：`PathingStats.snapshotAndReset()` 是**清空式**的。将来若要在**异步线程**规划，这个设计会丢数据。目前是同步主线程（`CorePathPlanner` javadoc 明说），**先记着这个约束**。

---

## §10 与《问题修复清单》（`RISK_SYSTEM_ISSUE_LIST.md`）的分工

| 文档 | 回答什么 |
|---|---|
| **本文** | 「**应该长成什么样**」——设计原则、契约形态、实施切片、决策对齐 |
| `RISK_SYSTEM_ISSUE_LIST.md` | 「**现在哪里不对劲**」——缺陷、死代码、不一致、待裁定项、需确认的事实 |

两份文档的交叉引用点：

| 本文 | 对应清单条目 |
|---|---|
| S1 | P0-A（`RiskSwitches` 全局静态）、P2-A（`policyVersion` 恒 0）、Q2（三个默认值是否统一） |
| S2 | P0-C（`FluidRiskPolicy` 零调用） |
| S3 | P0-B（`RecoverabilityLevel` 死抽象） |
| S5 | P1-A（执行期无事前准入 / 区块加载） |
| §5.6 | P1-C（维生否决后没有出口） |
| §7 全部 | Q1（`getBlockState` 未加载区块行为）、Q3（`MineTask` 重复调用） |

---

## §11 如果只记五条

1. **风险是边的属性，不是全局模式。** 按边评估，进 A\* 代价函数。
2. **进入时保守，逃离时激进。** 这样低风险模式永远不会把 Bot 困死。
3. **认知风险是第一号死因**，而它的第一个探针（`FluidRiskPolicy`）你已经写好了，只是没接上。
4. **报价先不要建** —— 今天没有消费者，你会得到第 4 个死抽象。等第一个"消耗型风险源"落地再建。
5. **拒绝必须带出口。** 现在 `SURVIVAL_INTERRUPTED` 是唯一反例。
