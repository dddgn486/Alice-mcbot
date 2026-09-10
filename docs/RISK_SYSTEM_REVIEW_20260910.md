# 风险系统 / 维生系统草案 · 复核记录（2026-09-10）

> **本文是复核，不是新方案。** 复核对象是用户与另一 AI 讨论产出的两份草案：
> [`RISK_SYSTEM_DESIGN_DRAFT.md`](RISK_SYSTEM_DESIGN_DRAFT.md)（实现草案）与
> [`RISK_SYSTEM_ISSUE_LIST.md`](RISK_SYSTEM_ISSUE_LIST.md)（问题修复清单）。
>
> 复核基线：`693e419`（与两份草案声明一致）。做法：**逐条拿代码核对 + 反编译 1.20.1 字节码回答待确认事实**，
> 不使用"文档说它坏了所以它坏了"的推理。

## §1 结论：9 条断言全部属实

| 条目 | 核对方式 | 结论 |
|---|---|---|
| **P0-A** `RiskSwitches` 全局静态 | 读 `pathing/risk/RiskSwitches.java` + 全仓 grep 消费者 | ✅ 属实：`private static volatile descendOvershootGuard`；消费者恰好 2 处（`DescendExecutionFactory:58`、`SurfaceMovementProvider:371`），均直读全局，无参数传入 |
| **P0-B** 可回收性死抽象 | 逐枚举值计数 + 读唯一转换点 | ✅ 属实（**机制需精确化，见 §3**）：`SAFE_EXIT_REQUIRED` / `EMERGENCY_EXIT_REQUIRED` / `NOT_REVERSIBLE` **使用次数 0** |
| **P0-C** `FluidRiskPolicy` 零调用 | `grep -rn "FluidRiskPolicy" src/main/java` | ✅ 属实：仅命中定义文件自身 |
| **P1-A** 无"执行期事前"准入 | `hasChunkAt` 全仓 grep + 读 `PathSession` 检查 | ✅ 属实：`pathing` 包 `hasChunkAt` 命中 **0**；执行期检查只有 `canWalkOn` / `canWalkThrough` / `isAir`，**无即死危害** |
| **P1-B** `MineTask` 重复调用维生 | `grep -rn "SurvivalSystem.tick"` | ✅ 属实：`MineTask.java:158` 与 `BotManager.java:688` 各一次；`SurvivalSystem` 有同 tick 缓存 → **行为等价，不是可观测故障**，但产生第二条终态路径（判断与原文一致） |
| **P1-C** 维生否决后无出口 | 读 `BotManager` 中断块 | ✅ 属实：`complete(lastTaskResult, SURVIVAL_INTERRUPTED)` 后直接 `return`，无后续动作 |
| **P2-A** `policyVersion` 恒 0 | 全仓 grep 构造点 | ✅ 属实：`PathSession.java:242` 写死 `0L, 0L`，无其它写入点 |
| **P2-B** `PathingStats` 清空式 | 读类 + 调用点 | ✅ 属实：`snapshotAndReset()` 内 `COUNTS.clear()`；调用点 1 处（`CorePathPlanner`） |
| **P2-C** 三守卫默认值不一致 | 读 provider 守卫实现 | ✅ 属实：`descend_overshoot` 默认**关**；`fallRecoverable` 与 `appendPillar` 前置**无条件执行**（恒开） |

**总评**：草案的静态阅读质量高（连 `MineTask:158` 行号都准确）。以下三项不是"它错了"，而是**动手前必须知道**的内容。

## §2 Q1 的确定答案（反编译证据）

**问题**：`ServerLevel.getBlockState()` 在**未加载区块**上是什么行为？

**答案（1.20.1 srg 字节码，非推断）**：

```java
// Level.getBlockState(BlockPos)
if (isOutsideBuildHeight(pos)) {                 // m_151570_
    return Blocks.VOID_AIR.defaultBlockState();  // f_50626_
}
LevelChunk chunk = this.getChunk(                // m_6325_(II)
        SectionPos.blockToSectionCoord(pos.getX()),   // m_123171_
        SectionPos.blockToSectionCoord(pos.getZ()));
return chunk.getBlockState(pos);

// 调用链
// Level.getChunk(int,int)            → getChunk(x, z, ChunkStatus.FULL)          (ChunkStatus.f_62326_)
// Level.getChunk(x,z,status,require) → ChunkSource.getChunk(x,z,status,true)     (m_7587_)
// ServerChunkCache.getChunk(...)     → CompletableFuture.supplyAsync(...).join() ← 同步阻塞
```

> **结论：不是 void air，而是"同步加载/生成区块并阻塞服务端主线程"。**
> void air 只在**越界高度**（`isOutsideBuildHeight`）返回。

**因此 P1-A 的性质落在草案列的第二个分支（性能问题），而不是第一个（通行性缺陷）**：

- 规划器**不会**把未加载区块当可通行 —— 它会把区块**真加载出来**再读真实方块；
- 真实代价是**搜索期间强制加载/生成远处区块**（卡服），以及 bot 自己的 tick 预算与段超时被 chunk gen 吃掉。

**复核补充（草案未列的第三条后果）**：这会**凭空生成玩家从未去过的世界**（世界膨胀、与"玩家视距"语义不符），
且**在 `Bariton_contrast` 里测不出来**（Baritone 跑客户端有视距兜底）—— 这一点草案判断正确。

**复核对草案 §3.7 的一处反对意见**：

> §3.7「未知 ≠ 禁止，但要计价」**不能套用到未加载区块**。
> **未加载区块不是"未知地形"，而是"读它有副作用"** —— 计价意味着"可能仍然走"，而"读一下"这个动作本身就会加载/生成世界。

建议区分两类，并把结论写进决策：

| 情形 | 处置 |
|---|---|
| **未加载区块** | **硬拒**（与虚空、岩浆、世界边界同级的不可协商底线），拒绝码 `unloaded_chunk` 进 `PathingStats` |
| 已加载但未观测（视野外） | 才适用 §3.7「未知要计价」 |

修法便宜：寻路读方块前 `level.hasChunkAt(pos)`（`capability/InterfaceScanner.java:50` 与 `transfer/ChestEndpointRef.java:27` 已在用同一 API），未加载即拒绝。

## §3 P0-B 机制必须精确化（否则"接线"做了还是空的）

草案结论正确（抽象不生效），但机制描述需要修正：

- 生产路径并非"`SurfaceMovementProvider` 7 处写死 `LOCAL_STEP` 而 required 另有值"；
- 实际是**唯一转换点** `PlannedMovementSpecs.toSpec()` **同时**写死两边：
  - `MovementSpec` 的 `evaluatedRecoverability = LOCAL_STEP`（构造参数）
  - 所有 case 的 `MovementCapabilities.requiredRecoverabilityLevel() = LOCAL_STEP`
- 于是 `MovementSpec` 的校验 `evaluated.ordinal() < required.ordinal()` → `0 < 0` **恒假** → 校验从未生效。

**对草案 S3a 的必要修正**（顺序不能反）：

| 步 | 动作 | 只做这一步的后果 |
|---|---|---|
| (a) | provider 对**通过 PILLAR 返回守卫的 FALL** 产出 `PATH_REVERSIBLE` | 两边都 `PATH_REVERSIBLE` → 校验**仍然恒真**，等于没接 |
| (b) | `PlannedMovementSpecs` 把 FALL 的 `required` 提到 `PATH_REVERSIBLE` | 若 (a) 未完成 → `LOCAL_STEP < PATH_REVERSIBLE` → **抛 `IllegalArgumentException` 崩规划器**（不是优雅拒绝） |

⇒ 必须先 (a) 后 (b)；且 (b) 落地时"所有仍存在的 FALL 边都已过守卫"必须成立。

## §4 对草案 §13 执行顺序的调整建议

1. **① 跳过**：Q1 已由本文 §2 回答，无需再写服务端探针；
2. **S1（`RiskProfile` 随请求冻结 + `policyVersion` 接上）排第一** —— 同意，它是唯一同时解决 P0-A + P2-A + P2-C 且有 3 个真实消费者的改动；
3. **把 P1-A（`hasChunkAt` 前置）从 S5 提前到 S1 之后** —— 反编译已证明它是**主线程阻塞 + 世界副作用**，性质比"死抽象"更接近真实故障；
4. **S2（`FluidRiskPolicy` 接线）/ S3（可回收性接线）**随后，二者都是裁定项，S2 更便宜；
5. **P1-B 顺手做**（删 2 行，对齐 `FollowTask`）；
6. **S6（维生去向）排最后** —— 同意（属"低风险模式只讨论不实现"范畴）。

**不同意的只有一处**：草案对 §3.7 的套用（见本文 §2 末尾）。

## §5 对两份草案的事实性校正（已就地修正）

| 位置 | 草案原文 | 实际 |
|---|---|---|
| 实现草案 §2.2 | 47 份文档归档至 `docs/archive/legacy-workflow/` | 实为 `docs/archive/legacy-2026-08/`（17 份设计文档）+ `.alice-supervision/archive/legacy-2026-08/`（30 份历史材料）；`legacy-workflow/` 是**更早**的归档目录 |
| 实现草案 §6 | `alice:pathing_regression`（13 项） | 当前为 **14 项 + `coverage=` 覆盖断言** |

## §6 待裁定（草案提出、本轮未决）

| # | 问题 | 复核倾向 |
|---|---|---|
| Q2 | 三个守卫默认值是否统一 | 同意草案：**保持现状 + 显式登记为过渡态**。补充一条可执行约束：`Bariton_contrast` 对照实验的记录**必须标注 Alice 侧多开了 2 个 Baritone 没有的守卫**，否则对照结论不可信 |
| Q3 | `MineTask` 重复调用维生怎么处理 | 同意：删掉，对齐 `FollowTask`；`BotSession` 的 `lastTaskResult = "failed:" + reason` 已带原因码，信息足够 |
| Q4 | "报价"形态延后是否接受 | 同意：延后 + 登记触发条件（第一个"消耗型风险源"落地时再引入），避免第 4 个死抽象 |

## §7 复核未覆盖的部分

- 本文只做**静态核对 + 反编译**；**未运行代码、未做客户端实测**。
- 草案 §3 的设计原则（无全局开关、进入保守逃离激进、认知风险计价、单调性、绝对时间戳、回程代价）属**设计判断**，
  本文不评估其对错，只确认它们与既有决策（D-024/D-036/D-037/D-046/D-050/D-058/D-059/D-062/D-076）无冲突。
