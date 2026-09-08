# 批 1 审查报告：bot 模块（bot 物理地基，第一批）

- **任务**：`b84636d3-360a-4be6-bb0a-ec8f1e274628`（批 1 / bot 模块只读审查）
- **审查基线**：`6c2b461`（HEAD；任务书声明基线；当前工作区含 ab510fd 之后提交）
- **日期**：2026-08-25
- **依据**：`full-review-plan-20260825.md`（§1.2 批 1、§2 模板、§4 替换框架、§5 验收/停止）、`progress-snapshot-20260824.md`、`client-tests/65f4863-soft-path-test-tool-retest-result.md`（T4 FAIL）、`docs/HANDOVER.md §八`（R#）、P1 调查报告。
- **性质**：只读审查。未修改任何文件（仅新增本报告）；未运行测试；未连接任何工具；未触碰 3081/3082/3083。
- **证据方式**：源码行号（bot/ 目录 6 文件 + SoftMovementPrimitive + SoftPathProbeTask + SoftPhysicsObservationTest）、git show ab510fd/2298dd3 diff、javap 反编译 Forge 1.20.1-47.4.10 mapped jar、客户端测试记录、run/logs/latest.log。

---

## 2.1 定位与文件清单

**模块定位**（类注释原意）：bot = "假人管理：tick 驱动、创建、当前动作分配；玩家化假人通过 `PlayerList.placeNewPlayer(伪造Connection, this)` 注册，客户端可见、tick 无 NPE、物理/交互全走原版玩家逻辑"（BotManager.java:41-52 / BotPlayer.java:8-21）。

| 文件 | 角色 | 职责一句话 |
|---|---|---|
| `BotPlayer.java` | 实体 | 继承 `ServerPlayer`，仅 override `tick()`（try/catch super.tick()），其余物理/交互全部继承原版 |
| `FakeConnection.java` | 门面 | 伪造 `Connection`（EmbeddedChannel 反射注入），`send()` 拦截 S2C 包：ab510fd 起广播 move/motion/teleport 三类包给真实玩家 |
| `BotManager.java` | 状态机 | 全局 `BOTS` map + `BotSession` 内部类：spawn/remove/restore、ServerTickEvent 驱动、任务排他、SavedData 持久化 |
| `BotSelftest.java` | 夹具 | 服务器启动/手动触发的 headless 套件（13 测试 + fixture 汇总） |
| `BotWorldData.java` | 数据 | `SavedData` 存 bot 概要（UUID/名/位置/朝向/游戏模式/主手） |
| `TaskExecutionRecord.java` | 数据 | 任务终态不可变记录（kind/target/tick 区间/terminal/code/pos/recovery） |

**注册方式**：`BotManager` 通过 `@SubscribeEvent` 挂 `ServerTickEvent`/`ServerStartedEvent`/`ServerStoppingEvent`/`LivingDeathEvent`（BotManager.java:344-393）；bot 实体经 `PlayerList.placeNewPlayer` 注册（:76）；BotWorldData 经 `SavedData.computeIfAbsent`（:22-26）。

---

## 2.2 边界归属

- **归属线**：bot 物理是 **HARD_PATH / SOFT_SURFACE 所有线的地基**（用户决策者指定最高优先；full-review-plan §1.1/§1.2）。当前问题线：P1 客户端同步修复线 **CLIENT_TEST_FAILED**（ab510fd 引入玩家跳跃异常；bot 物理失效自 2298dd3 起；均未修复、无复测记录——progress-snapshot:31-39）。
- **R# 对照**：
  - R30（PathExecutor 固定 HARD_PATH）：bot 目录不违反——PathExecutor 属 pathing，bot 仅驱动任务；**待 pathing 批核查**。
  - R31（软移动借鉴成熟实现、`setPos` 不能伪装软移动）：SoftMovementPrimitive NATIVE_TRAVEL 用 `travel()` 而非 setPos（SoftMovementPrimitive.java:48-53,82-86）；**符合**。
  - R33（NATIVE_TRAVEL 已验平地+单格高差；`ServerPlayer.tick()` 不自动消费 xxa/zza，任务逐 tick 显式调 travel）：**代码一致**（SoftPathProbeTask.java:141-144 显式调 applyToward）；**但该显式 travel 与 bot 物理失效有强关联（见 2.3 核查点 B2）**。
  - R35（落地需真实支撑顶面；跨障后零输入 `travel(Vec3.ZERO)` 结算；30 tick 未稳定返回 `soft_probe_unsettled`）：SoftPathProbeTask.java:212-225 **一致**（settle + MAX_SETTLE_TICKS=30 → soft_path_unsettled）。
  - R36（FollowTask 独立、可开关、与保护区解耦）：BotManager.stopFollow/assignFollow（:217-229）**一致**。
- **边界漂移待监督员判定**：无（bot 目录未触碰冻结边界；GUI FROZEN 与 bot 目录无交集）。

---

## 2.3 功能正确性核查点

| # | 宣称行为 | 代码实现 | 证据类型 | 差异/疑点 |
|---|---|---|---|---|
| A1 | FakeConnection 丢弃 S2C 包，bot 无真实网络 | ab510fd 前 `send()` 空实现；ab510fd 后广播 3 类包（FakeConnection.java:42-76） | 静态核对 | **已变更**；广播实现引入玩家跳跃异常（见 A2-A5） |
| A2 | 广播"给真实玩家"（排除 bot 自己） | `!(player instanceof BotPlayer) && player.level()==bot.level()`（:71-74） | 静态核对 | **已确认排除 bot 自身**；但**无距离/追踪过滤**：所有同维度真实玩家（含 192 格追踪范围外的）都会收到 |
| A3 | 广播包类型"位置/速度" | `ClientboundMoveEntityPacket`（抽象类，匹配全部子类含 DeltaMove/PosRot/Rot）/`ClientboundSetEntityMotionPacket`/`ClientboundTeleportEntityPacket`（:45-49） | 静态核对 | **宽类型过滤**：未区分相对位移/绝对位置/纯旋转子类；未校验包内 entityId 归属 |
| A4 | 广播不干扰玩家物理（玩家跳跃异常候选判据：观察者收包 + 不干扰玩家物理，full-review-plan §4.2） | 同一 Packet 实例被 `player.connection.send(packet)` 循环发送给多个 connection（:71-75） | 静态核对 + **需客户端实测** | 同一实例多 connection 编码竞态风险；且 ServerEntity 正常追踪路径（broadcastAndSend）与 FakeConnection 广播**可能双发同一包**（重复包）——**玩家跳跃异常的精确机制待 packet 级证据** |
| A5 | 玩家跳跃异常（ab510fd 引入，能稳定跳 1.5 格） | ab510fd diff 确认广播为新增逻辑；65f4863（无广播）跳跃正常 | 客户端实测（progress-snapshot + 回退测试） | **现象已确认、机制未闭合**：代码层证据 = 广播实现三事实（无距离过滤/宽类型/共享实例+潜在双发）；需玩家侧抓包确认收到的包流 |
| B1 | bot 应可被推挤/击退（原版玩家逻辑） | `BotPlayer` 不 override `isPushable/push/travel/knockback/hurt`（BotPlayer.java 全文）；反编译：`LivingEntity.isPushable`=`isAlive()&&!isSpectator()&&!onClimbable()`；`Player.tick` 设 `noPhysics=isSpectator()`；`aiStep()` 字节码 857 调 `travel`、**1064 无条件调 `pushEntities()`**；`ServerPlayer` 不 override 这些 | 静态核对（javap） | **理论上 isPushable=true、noPhysics=false（SURVIVAL）**——但客户端实测 bot 无法被推挤/无击退（T4）→ **宣称与实测冲突** |
| B2 | NATIVE_TRAVEL 逐 tick `travel(new Vec3(0,0,1))` 驱动（SoftMovementPrimitive.java:48-53） | 每 tick 强制 `xxa=0,zza=1` 后 `travel()`；travel 内部 `moveRelative+move+setDeltaMovement` 按输入重算（javap） | 静态核对 | **强推论**：击退/推挤写入的 deltaMovement 会被下一 tick 任务层 travel 按 zza=1 输入覆盖 → 击退无位移、bot 不抵抗推挤 → "bot 穿过玩家 + 击退无位移"现象吻合（**待服务端断言实测**） |
| B3 | bot 的 tick 链完整（aiStep→travel+pushEntities） | `BotPlayer.tick()` try{super.tick()}catch(NPE){printStackTrace}（BotPlayer.java:28-36）——注释自述"吞掉会静默中断物理" | 静态核对 + **需服务端日志** | **推论候选**：若玩家化后 super.tick() 链内仍有 NPE（如 connection 相关），NPE 之后 aiStep/pushEntities 被跳过 → bot 不推挤；latest.log 未见 NPE 证据（当前日志为 MineTask 场景）——**待调查** |
| B4 | 服务端"物理正常"（P1 报告 §1 结论） | P1 报告 §7 自认"未运行客户端或服务端实测"；仅静态 javap | 静态核对 | **该结论已被实测否定**（progress-snapshot:26）：服务端物理是否正常**尚无服务端断言证据**（见 2.6 证据缺口） |
| C1 | spawn 强制 SURVIVAL | `changeGameModeForPlayer(SURVIVAL)`（BotManager.java:81） | 静态核对 | 与 B1 前提一致（非 spectator → noPhysics=false） |
| C2 | 任务排他（replaceTaskIfRunning） | BotSession.replaceTaskIfRunning（BotManager.java:451-465）+ TransferTask in-transit 拒绝 | 静态核对 | 一致；与 p1 线无关 |
| C3 | 存档恢复（BotWorldData） | spawn 写档、restoreFromWorld 恢复（:69-87,141-179） | 静态核对 | SavedData 用法正确（computeIfAbsent/setDirty） |
| C4 | TaskExecutionRecord 不可变 | record + compact constructor 默认值（TaskExecutionRecord.java:6-35） | 静态核对 | 一致（record 语义） |

---

## 2.4 已知坑与 R# 对照（HANDOVER §八）

| R# | 内容 | 当前代码是否仍遵守 | 核查点证据 |
|---|---|---|---|
| R30 | PathExecutor 固定 HARD_PATH，矿链不可切 SOFT | bot 目录无违反（属 pathing 批） | — |
| R31 | 软移动借鉴成熟实现，`setPos` 不能伪装软移动 | ✅ 遵守 | SoftMovementPrimitive NATIVE_TRAVEL 用 travel 不用 setPos |
| R33 | NATIVE_TRAVEL 已验平地+单格高差；任务逐 tick 显式 travel | ✅ 遵守（但见 B2 风险） | SoftPathProbeTask.java:141-144 |
| R35 | 落地需真实支撑顶面；零输入 travel 结算；30 tick 未稳 → soft_probe_unsettled | ✅ 遵守 | SoftPathProbeTask.java:212-225 |
| R36 | FollowTask 独立可开关、与保护区解耦 | ✅ 遵守 | BotManager.java:217-229 |

**边界漂移待监督员判定项**：无新漂移；但 **B2（任务层 travel 覆盖击退 delta）与 R33"已验"的表述有张力**——R33 称 NATIVE_TRAVEL 已通过客户端测试，但 T4 击退/推挤 FAIL 表明其"物理完整性"未经受推挤/击退场景验证（R33 验证的是平地+单格高差，非实体交互），**待监督员确认是否需更新 R33 验证边界表述**。

---

## 2.5 Dual-View：服务端可证 vs 需客户端实测

**服务端可证（headless/日志可断言）**：
- `isPushable()` 返回值、`noPhysics` 值（fixture 可断言 bot.isPushable()==true、noPhysics==false）；
- 推挤：构造玩家/实体 push bot 后断言 `deltaMovement` 变化与位移；
- 击退：对 bot 调 `hurt()` 后断言 deltaMovement 非零、后续 tick 位置变化；
- 广播范围：日志/拦截断言 `broadcastToRealPlayers` 实际遍历的玩家集合（应=同维度真实玩家全集，**当前实现无距离过滤**）。

**需客户端实测（Windows）**：
- 玩家跳跃异常（1.5 格）——必须玩家侧复现 + **packet 级证据**（收到哪些包、entityId、重复性）确认机制；
- bot 被推挤/击退的客户端可见位移（渲染/插值）——即使服务端 delta 正确，客户端视觉仍需验收；
- 广播造成的重复包对客户端实体状态插值的影响。

> **必须写明**：服务端 PASS ≠ 客户端正确。`ab510fd` 教训：P1 报告 §1 静态推断"服务端物理正常"（未实测），随后客户端实测否定（T4 + 玩家跳跃异常）。按 evidence-collection-standard 的 EVIDENCE_CONFLICT 规则：凡"服务端 PASS / 客户端 FAIL"冲突，必须补双端证据，不得以服务端结论覆盖客户端可见失败。**当前 bot 物理线正是 EVIDENCE_CONFLICT 状态（服务端无断言、客户端实测 FAIL）**。

---

## 2.6 证据等级核查

| 项 | 现有证据 | 等级 | 缺口 |
|---|---|---|---|
| MineTask HARD_PATH 任务链 | latest.log（SELFTEST TEST1-3 PASS，task_execution_terminal COMPLETED） | 服务端 PASS（selftest） | 无客户端记录 |
| 软路径测试工具 T1-T3 | 65f4863 retest-result（右键寻路/Shift 挖掘/隔离） | USER_ACCEPTED（客户端记录） | — |
| T4 bot 穿玩家 + 击退无位移 | 65f4863 retest-result §T4（用户观察，无日志） | CLIENT_TEST_FAILED（客户端记录，现象级） | **无服务端断言**：isPushable/push 后 delta/knockback 后 delta 均无 fixture |
| 玩家跳跃异常（ab510fd） | progress-snapshot（可稳定复现）+ 回退 65f4863 正常 | CLIENT_TEST_FAILED（客户端记录，现象级） | **无 packet 证据、无日志** |
| FakeConnection 广播行为 | 代码静态核对（本报告 A2-A4） | 静态核对 | 无运行日志/抓包 |
| SoftPhysicsObservationTest | selftest 套件内运行（观测字段/revalidate/replan/MAX_REPLAN） | 服务端 PASS（fixture） | **不断言 isPushable/push/knockback 位移**——物理交互零覆盖 |
| BotWorldData/TaskExecutionRecord | 代码 + selftest 间接覆盖 | 静态核对 + 服务端 PASS | 低 |

**证据缺口汇总**：bot 物理交互（推挤/击退）在**服务端断言层与客户端 packet 层双缺口**；玩家跳跃异常机制零抓包证据。

---

## 2.7 风险等级判定

| 模块/问题 | 等级 | 触发理由 |
|---|---|---|
| 玩家跳跃异常（ab510fd 广播实现） | **B 修复** | 与 full-review-plan §4.2 客户端同步候选判据"观察者收包 + 不干扰玩家物理"冲突；客户端实测 FAIL；修改权归修复主线（审查不修） |
| bot 物理失效（不可推挤/无击退） | **B 修复** | 行为与"原版玩家逻辑"宣称不符、客户端实测 FAIL（T4）；代码链证据（B2 travel 覆盖 delta / B3 tick 链 NPE）指向明确待实测项；根因未最终闭合 → B + 待调查 |
| FakeConnection 广播实现本身 | **B 修复**（候选） | 无距离过滤/宽类型/共享实例/潜在双发；至少需收窄广播语义（但改法归修复主线） |
| BotSelftest / BotWorldData / TaskExecutionRecord | **A 补测试**（BotSelftest 物理断言部分）/ **D 维持**（后两者） | selftest 缺物理交互断言；SavedData/record 与设计一致且证据充分 |
| 停止条件核对 | 未触发 | 未发现数据损坏/崩溃/死锁级 bug；未需修改 R#/冻结边界才能下结论；未要求改 FakeConnection 方案后自行实施（只列证据） |

---

## 2.8 替换候选评估判据（引用 §4 框架，只列方向不选型）

**方向 1：客户端同步（FakeConnection 广播 vs 标准追踪/vanilla broadcast）**

| 判据 | 已知证据 | 缺证据 |
|---|---|---|
| M1 维护成熟度 | vanilla `ServerEntity.sendChanges` / `ChunkSource.broadcastAndSend` 是原版机制（Forge 1.20.1 自带） | 无（原版即权威） |
| M2 Forge 1.20.1 兼容 | 原版机制天然兼容 | — |
| M3 集成成本 | 当前 ab510fd 已实施广播（改 FakeConnection 2 处 send）；替换需回滚/重做该改动，波及 BotPlayer.tick 或 ServerEntity 接入点 | 具体接入点设计 |
| M4 客户端风险 | **当前广播已引入玩家跳跃异常（客户端实测 FAIL）**——高客户端风险证据已存在 | 新方案（如 tracker.sendChanges 或 distance 过滤广播）的客户端矩阵 |
| M5 过渡成本 | 单实现切换；回滚 = 恢复 ab510fd 前 FakeConnection | 双实现并存方案未设计 |

**方向 2：bot 物理执行（任务层显式 travel vs 完整 tick 链/输入注入）**

| 判据 | 已知证据 | 缺证据 |
|---|---|---|
| M1 维护成熟度 | 自研 travel 注入（R31 已记录"借鉴 Baritone movement primitive + Carpet action pack"方向） | 成熟方案在 Forge 1.20.1 ServerPlayer 上的可行性未验证 |
| M2 Forge 1.20.1 兼容 | ServerPlayer.tick 不消费 xxa/zza（R33 已确认） | 输入注入边界（R31 明示"Forge 适配前先确认 tick/travel/input 注入边界"） |
| M3 集成成本 | 改动 SoftMovementPrimitive/BotPlayer.tick 驱动方式 | 波及任务层所有软移动调用点 |
| M4 客户端风险 | 当前 T4 客户端 FAIL（bot 穿玩家/无击退） | 修复后客户端矩阵（推挤/击退/穿墙） |
| M5 过渡成本 | 单实现切换 | 回滚验证方案 |

**替换决策点所需证据清单**（按 §4.3）：
1. 交叉矩阵：bot 物理 × 推挤/击退 组合当前为 ⬜ 零证据（服务端无断言、客户端仅现象记录）；
2. M1-M5 对照表（方向 1 已有部分证据；方向 2 需先做 Forge 输入注入边界实测）；
3. R# 冲突面：方向 1 无直接 R#；方向 2 需核对 R31/R33（若改 travel 驱动方式，R33"已验"表述须同步更新）；
4. 过渡/回滚方案；
5. 新 Windows 矩阵草案（推挤、击退、穿墙、玩家跳跃回归）。

> 本报告只列方向，不形成替换提案；替换需监督员审核 + 用户批准（§4.3）。

---

## 特别核查项结论

### 1. FakeConnection.send() 广播（ab510fd）对玩家物理的干扰路径

**代码事实（已确认）**：
- 广播范围：**所有同维度真实玩家，无距离过滤、无追踪过滤、无 entityId 校验**（FakeConnection.java:66-76）——理论上 192 格追踪范围外的玩家也会收到；
- 包类型：`ClientboundMoveEntityPacket`（抽象类，含全部子类）/`ClientboundSetEntityMotionPacket`/`ClientboundTeleportEntityPacket`（:45-49），未细分相对位移（DeltaMove）与绝对位置（Pos）变体；
- 发送方式：**同一 Packet 实例循环 send 给多个 connection**（:71-75），存在编码竞态与"正常追踪路径 + 广播"双发同一包的风险（ServerEntity 追踪与 FakeConnection 广播两条路径未去重）；
- 回退测试对照：65f4863（无广播）跳跃正常 → **跳跃异常确由 ab510fd 广播引入**（已确认现象级）。

**推论（待 packet 级证据）**：玩家跳跃 1.5 格的**精确机制未闭合**——候选包括重复包/超范围包干扰客户端实体状态插值、共享实例编码竞态、宽类型误广播。需要玩家侧抓包确认收到包流（entityId/类型/重复性）+ 服务端发送日志对照。**这是"只提交证据不修"项；修改权归修复主线（§5.4）**。

### 2. BotPlayer 的 isPushable/push/travel 调用链（bot 无法推挤/击退）

**已确认（javap 反编译 + 源码）**：
- `BotPlayer` 不 override 任何物理方法 → 走原版 `LivingEntity.isPushable`（=isAlive&&!isSpectator&&!onClimbable）、`Player.tick` 设 `noPhysics=isSpectator()`、`aiStep()` 字节码 857 调 travel、1064 无条件调 pushEntities；SURVIVAL → isPushable 应为 true、noPhysics 应为 false；
- **服务端"物理正常"从未被断言证明**：P1 报告基于静态 javap，自认未实测；且该结论已被客户端实测否定。

**推论（待服务端断言实测）**：
- **B2 强候选**：任务层每 tick `travel(new Vec3(0,0,1))`（SoftPathProbeTask:141-144 → SoftMovementPrimitive:48-53）按 zza=1 输入重算 deltaMovement，**覆盖**推挤/击退写入的 delta → bot 无位移、不抵抗 → "穿过玩家 + 无击退"现象吻合；
- **B3 次候选**：`BotPlayer.tick()` 的 NPE catch 若在玩家化后仍触发（super.tick() 链内 connection 相关），NPE 后 aiStep/pushEntities 被跳过 → bot 不推挤（注释自述"吞掉会静默中断物理"）；当前日志无 NPE 证据（待专项日志）；
- **B4 排除项**：`pushEntities` 在 aiStep 内、travel 内不含实体推挤——即便 travel 正常，实体交互也只走 pushEntities 链。

**回退测试对照（65f4863 跳跃正常 + bot 仍无法推挤）与代码一致**：两问题独立——跳跃异常依赖 ab510fd 广播（无广播即正常）；bot 物理失效与广播无关（无广播仍失效）→ 根因在 BotPlayer/tick 链与任务层 travel 用法，与 FakeConnection 无关。

### 3. 回退测试结论 vs 代码事实：**一致**（见上）

---

## 与 active plan 冲突声明

- 本模块审查结论**涉及 FakeConnection 广播方案**（ab510fd），与 active plan `20260824-p1-client-sync-fix-v1` 存在交叉。按规划 §5.3/§5.4：**该线实态为 CLIENT_TEST_FAILED**（ab510fd 引入玩家跳跃异常、bot 物理失效自 2298dd3 起、均未修复、无复测记录）；本审查**只提交证据，不派发修复，不改 FakeConnection**；修改权归修复主线，证据可被监督员采纳进该线调查计划（须写入 active plan Research Decision）。

---

## 证据分级汇总

- **已确认事实**：BotPlayer 仅 override tick()（文件全文）；FakeConnection 广播实现细节（:42-76 + ab510fd diff）；BotManager spawn/排他/持久化（:64-89,344-393,443-630）；isPushable/noPhysics/aiStep→travel+pushEntities 反编译链；T4 现象记录；回退测试结论；selftest 物理断言缺口（SoftPhysicsObservationTest 无 isPushable/push/knockback 断言）。
- **架构推论**：B2（任务层 travel 覆盖击退 delta）、B3（tick 链 NPE 跳过 aiStep）、A4 广播双发/竞态——均需实测确认。
- **待调查**：玩家跳跃异常精确机制（需 packet 抓包）；bot 物理失效根因定案（需服务端断言 fixture + 回退 19af345 验证 + 专项日志查 NPE）；P1 报告"服务端物理正常"的替代结论（服务端无断言证据）。

---

**本报告不构成实施授权**；审查未修改任何文件（业务代码/HANDOVER/active plan/skill/客户端记录均未动），未运行测试，未连接任何工具或 endpoint。替换提案、修复派发均需监督员审核 + 用户批准。

---

## 监督员证据更正记录（2026-08-25，pathing 审查验收时追加）

**更正对象**：本报告 2.3-B2"任务层 travel 覆盖击退 delta"表述。

**更正依据**：pathing 模块审查（review-batch1-pathing-20260825.md）用 javap 反编译 `LivingEntity.travel` 链证明：`moveRelative` 是**增量叠加输入到现有 deltaMovement**（非清零覆盖），随后 `move` 应用位移、`setDeltaMovement` 按摩擦衰减（0.91/0.98 类）。即 travel **不会清零击退/推挤 delta**——"覆盖"语义与游戏事实矛盾（原版生物每 tick travel 与击退共存）。

**更正后表述**：任务层每 tick `xxa=0/zza=1/travel(0,0,1)` 是对移动输入的**叠加+衰减**，"无位移"更可能由 hurt/knockback 未触发（isPushable/hurt 链）、bot tick 链未完整运行（本报告 B3）、或客户端未收到位置包（FakeConnection 同步）配合产生——**B2 从"唯一根因候选"降级为"影响因素之一"**。

**定案要求**：仍须 pathing 报告 F1-F6 服务端断言（hurt→delta 非零 / 延迟 1 tick travel 位移对比等）+ 客户端复测，方可定案。
