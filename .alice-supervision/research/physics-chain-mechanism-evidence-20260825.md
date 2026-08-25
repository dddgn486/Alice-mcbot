# bot 实体 tick 链"无碰撞反应"机制证据（F1-F6 定案最后佐证，修复注射点前置）

- 执行：Alice 主开发员（session-30b693e7）
- 日期：2026-08-25
- Alice 基线：`e9af1b3`（HEAD；NPE 佐证已验收——B3 排除）
- 任务书：`.alice-supervision/research/physics-chain-mechanism-task-20260825.txt`
- 性质：**只读证据采集**——不改任何业务实现与 fixture；不判定根因（监督员按区分表定案）；不实现修复

## 目标（任务书 §3 四项定位）

回答"`bot.tick()` 实体链（含 aiStep/pushEntities 碰撞段）在驱动下为什么不产生碰撞反应"，只列机制证据与候选分支，**不写根因结论**。

---

## ① aiStep() 提前返回 / 跳段分支清单（javap 1.20.1-47.4.10 mapped 字节码）

来源：`LivingEntity.aiStep()` 反编译（livingentity-full.txt）。

| # | 字节码偏移 | 分支 | 条件 | 行为 | 是否跳 pushEntities |
|---|---|---|---|---|---|
| A1 | 4 | `ifle 17` | noJumpDelay ≤ 0 | 正常继续；>0 递减 noJumpDelay | 否 |
| A2 | 17-45 | `isControlledByLocalInstance → ifeq 45` | 非本地控制实例 | 跳至 lerpSteps 段(45)处理插值 | 否 |
| A3 | 45-49 | `lerpSteps → ifle 208` | lerpSteps ≤ 0 | 跳至 isEffectiveAi 检查(208) | 否 |
| A4 | 208-212 | `isEffectiveAi → ifne 229` | **isEffectiveAi=false** | **deltaMovement *= 0.98 衰减后 goto 229**（仍汇合主逻辑） | **否（不跳过）** |
| A5 | 371-393 | `isImmobile → ifeq 396` | **isImmobile=true** | jumping=false、xxa=0、zza=0，goto 434 | 否（immobile 清零输入，仍进主逻辑段） |
| A6 | 396-400 | `isEffectiveAi → ifeq 434` | **isEffectiveAi=false** | 跳过 serverAiStep 段(403-429)，goto 434 | 否 |
| A7 | 969-1009 | `level.isClientSide → ifne 1009` | **客户端侧** | 跳过冻结伤害段(972-1005)，跳到 1009 汇合 | 否 |
| A8 | 1009-1064 | — | 汇合后 push profiler → `pushEntities()`（偏移 **1064**） | **实体推挤链调用点** | **是（无条件到达）** |

**结论（事实）：pushEntities() 在 aiStep 内偏移 1064 无条件到达**——A1-A7 均不构成"跳过 pushEntities"的返回分支；唯一影响是 A4/A6 在 isEffectiveAi=false 时仍汇合到主逻辑。**若 bot.tick() 链完整执行到 aiStep，pushEntities 必然被调用**（这一事实与 F4 链完整、F3b flags 无变化并存，机制的落点在 pushEntities 内部与 Entity.move 碰撞段，见 ③④）。

---

## ② bot.tick() 实际驱动链（代码证据）

| 驱动层 | 代码证据 | 调用关系 | 频率 |
|---|---|---|---|
| 原版实体循环 | `ServerLevel` 构造含 `entityTickList` 字段（字节码 putfield #257）；服务器主循环每 tick 遍历实体列表调 `Entity.tick()` | `BotPlayer.tick()`（override）→ `super.tick()` → `LivingEntity.tick()` → `aiStep()` | 每服务器 tick |
| 任务层 | `BotManager.onServerTick(ServerTickEvent)` @BotManager.java:345-353：`session.tick(hazard)` → `task.tick()` | **不调用 `bot.tick()`**——任务动作经 `SoftMovementPrimitive.applyToward/travel`（SoftPathProbeTask:143-144）直接驱动 travel，独立于实体链 | 每服务器 tick |
| fixture 手动 | `BotPhysicsAssertionFixture` F3b/tickChain：`bot.tick()`（:116、:171） | 手动驱动完整实体链，用于碰撞 flags 佐证 | fixture 运行内 |

**事实链**：BotPlayer 经 `PlayerList.placeNewPlayer` 注册进 ServerLevel 实体列表（BotManager.spawn:76）→ 实体循环每 tick 调 `BotPlayer.tick()`；任务层 via ServerTickEvent 并行驱动 travel。两条链独立。**F3b/tickChain 的"手动 bot.tick() 后 flags 无变化"指向实体链内的碰撞检测未被触发或 flags 未置位——但 aiStep 的 pushEntities 在链完整时无条件到达（见 ①），因此差异点在 pushEntities 内部条件或 Entity.move 的碰撞设置（③④）**——此句为机制推理，定案归监督员。

---

## ③ F3b/tickChain 最小复现定位

- 复现动作（fixture 已实施，本任务未改）：`bot.push(0.5,0,0)` 写入 delta（pushWritten=true，观测到 pushD=0.500）→ `bot.tick()` → 记录 delta/disp。
- 观测结果（latest.log 行 117-134 窗口，corr=147947fa / a49052a6 两次一致）：
  - `entityPushed=false`：tick 后 delta=(0.000,0.000,0.000)、disp=(0.000,0.000,0.000)
  - tickChain：`horizontalCollision=false->false`、`verticalCollisionBelow=false->false`
- 墙撞场景（assertTickChain）：bot 面向 +X 墙(3 格外) push 后 bot.tick() → flags 恒 false。

**机制定位（候选，不预定结论）**：
1. `pushEntities` 被调用但其内部"实体-实体推挤"条件未满足（Zombie 放置高度 y+1 与 bot 碰撞箱不重叠、或 `canBePushedBy` 判定、或 tick 驱动时 Zombie 自身未在有效推挤状态）——**需 pushEntities 字节码逐行展开（待调查）**；
2. `Entity.move` 的碰撞检测段：flags 置 true 依赖 move 期间的方块碰撞命中（见 ④ 335/381）；若 push 后 delta 在 travel 前被清零（fx 输入路径）则 move 不产生位移、无碰撞命中 → flags false——**与 F5/F2"任务层 travel 保留击退分量"并存，说明任务层 travel 链正常；实体链 travel 是否消费 push delta 是差异候选（待调查）**；
3. 墙撞场景墙在 3 格外，单 tick bot.tick() 的 maxStep 位移不足以触墙（horizontalCollision 在**当 tick 移动量**够近时才开始检测）——flags false 可能是"距离未达"，不必然表示链失效——**数值维度候选（待调查，需复测将墙置于相邻格）**。

（以上三项均为机制候选清单，未判定哪个成立；监督员按定案表执行。）

---

## ④ 碰撞 flags 设置点定位（javap `Entity.move`）

| flag | putfield 偏移 | 设置条件（字节码证据） |
|---|---|---|
| `horizontalCollision` | **335**（`putfield #1183`） | 由 327 `ifeq 334` 控制：移动碰撞检测命中（碰撞修复后移动量 >0 且碰撞存在）时置 **true**；默认 false |
| `verticalCollisionBelow` | **381**（`putfield #1187`） | 由 373 `ifge 380` 控制：下方碰撞（地面/方块）命中置 **true**；默认 false |

- 这两个 flag **只在 `Entity.move` 的方块/世界碰撞检测段设置**，不是在 aiStep 或 pushEntities 处直接赋值。
- **因此"flags 无变化"的观测直接对应：当 tick 的 move 过程中未发生方块碰撞命中**。触发碰撞命中的前提是：move 携带有效位移（非零 delta 且未被输入路径清零）+ 位移轨迹与方块 AABB 相交。
- 结合 ③：push 写入 delta=0.5 但 tick 后 disp=0 → **move 未产生位移** → 无碰撞命中 → flags false。**差异候选 = 实体链内 push delta 在 move 前被如何处置（travel 输入路径 vs push delta 保留）——待调查，监督员定案**。

---

## 证据分级

| 证据 | 等级 |
|---|---|
| aiStep 内 pushEntities 在偏移 1064 无条件到达（A1-A7 均不跳过） | **已确认事实**（javap 字节码） |
| horizontalCollision/verticalCollisionBelow 仅由 Entity.move 碰撞段设置（335/381），非 aiStep 直接赋值 | **已确认事实**（javap 字节码） |
| BotPlayer 实体 tick 由原版 ServerLevel 实体循环驱动；任务层 ServerTickEvent 独立驱动 travel，不调 bot.tick | **已确认事实**（源码 + 字节码） |
| pushEntities 内部"实体-实体推挤"判定条件未逐行展开；单 tick 位移是否足够触墙（3 格外）未定量 | **待调查** |
| tick 链中 push delta 是否被任务层 travel 输入路径覆盖/清零（实体链 vs 任务链 delta 处置差异） | **推断候选（待调查）**——不构成定案 |

## 边界遵守确认

- 本任务期间**未修改任何业务实现与 fixture**（git 无 src 变更，仅本报告新增）。
- **未判定根因**（只列机制事实与候选清单，定案由监督员按区分表执行）；**未实现修复**。
- 未触碰跳跃异常线 / tunnel / transfer / road 等其他线。