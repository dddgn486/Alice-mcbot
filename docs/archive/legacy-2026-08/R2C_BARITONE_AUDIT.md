# R2-C Movement × Baritone 对照审计报告

- 日期：2026-09-08
- 审计对象：R2-C 新建的 4 个 Movement 执行器（Traverse / Diagonal / Ascend / Descend）及其工厂、诊断任务、跨切面输入时序
- Baritone 基线：本地克隆 `/home/fb486/projects/reference/baritone/`，commit `64333af99a072caa3f4d6f17e4b223a5ae7da3f4`（`git log -1` 显示为 `1.21.4 (grafted)` 树）
- 方法：逐行源码对照 + `javap` 字节码核实（Forge 1.20.1-47.4.10 parchment jar）+ 客户端日志实测数据
- 验证等级：**代码/字节码级 = IMPLEMENTED**；Descend = **WINDOWS_CLIENT（4/4 精确命中，见 §0.1）**；其余 Movement 的 B/C 条目为静态结论，未逐条运行时复现

---

## §0 结论摘要

### 0.1 最新实测（2026-09-08 19:37-19:38，单循环版 `0ef33660`）

`latest.log` 实测 **Descend 4/4 `completed`，`actualFoot` 全部精确等于 `toFoot`，0 失败**：

| from | to | actualFoot | ticks |
|---|---|---|---|
| -1,65,2 | 0,64,2 | 0,64,2 | 12 |
| 0,65,1 | 0,64,0 | 0,64,0 | 11 |
| 1,65,2 | 0,64,2 | 0,64,2 | 11 |
| 0,65,3 | -1,64,3 | -1,64,3 | 11 |

另有 2 次 `DESCEND_INVALID_PRECONDITION` 拒绝（目标地形无支撑），属正确行为。

探针揭示的机制（以 `from=0,65,3 to=-1,64,3` 为例）：

```
tick 5  ab=0.15 onGround=true  forward=1.0   仍在上一层走向边缘
tick 6  ab=0.06 onGround=false forward=1.0   已落入目标列、开始下落
tick 7  ab=0.17 forward=0.0                  停止分支命中（feet==to && ab<=0.25）
tick 8  ab=0.28 forward=0.0                  惯性继续漂移（0.11 格/tick）
tick 9  ab=0.35 yaw 90.38→-91.42 forward=1.0 ab>0.25 触发"回头朝 dest"重新瞄准
tick 10 SUCCESS（落地）
```

**结论**：Baritone 风格单循环已把 Descend 从"过冲/超时"变为"精确命中"，但**刹车阈值 0.25 从未真正命中**（ab 一步跨过窗口 0.36→0.15），实际依靠"漂移后回头重新瞄准 + 落地判定"收敛，落点可到 `ab≈0.35`（碰撞箱越出方块边约 0.15 格）。

### 0.2 三大结构性发现

1. **Alice 假人的台阶物理与真实玩家不同**：`ServerPlayer` 构造里 `setMaxUpStep(1.0F)`，而 `LivingEntity` 默认 `0.6f`、`LocalPlayer`（Baritone 玩家）无覆写 → **bot 能直接踩上整格方块，真实玩家不能**（§2，字节码级证据）。
2. **控制回路相位错位**：Baritone 在玩家 tick **之前**施加输入/旋转（同 tick 生效）；Alice 在 `ServerTickEvent.Phase.END`（实体物理**之后**）驱动，输入要到下一 tick 的 `aiStep()` 才生效 → 照搬的 1.25/0.25/0.5 容差被 ~0.2 格延迟吃掉（§3）。
3. **完成契约在 4 个执行器间不统一**：`Phase.SETTLING` 只有 Ascend 用；Traverse/Diagonal 判定不含 `onGround`；Ascend 以"Y 到位"即停机 → 直接产生用户观察到的"偶发水平偏移"（§4.3）。

---

## §1 分类总表

分类口径：
- **A = 合理架构差异**：由服务端权威 / 确定性执行器契约 / 无客户端预测等架构决定，有正当理由，保留
- **B = 缺陷**：偏离 Baritone 且无正当理由，可能或已导致错误行为
- **C = Alice 物理/系统所致**：环境约束（服务端假人物理、时序、无预测），不是设计选择，需显式补偿

| # | 项目 | 类别 | 严重度 | 证据 |
|---|---|---|---|---|
| 1 | 起点严格 `blockPosition()==fromFoot`，无合法位置集容忍 → 多段必断链 | **B** | 高 | `TraverseExecutionFactory:28-30`、`DiagonalExecutionFactory:38-40`、`AscendExecutionFactory:39`、`DescendExecutionFactory:39-41` vs Baritone `Movement:72-74,112-114`、`PathExecutor:324-343`（snipsnap） |
| 2 | 完成判定不统一（SETTLING 仅 Ascend；Traverse/Diagonal 无 onGround） | **B** | 高 | `MovementExecution:9` vs `TraverseExecution:103-106`、`DiagonalExecution:120-123`、`AscendExecution:143-147`、`DescendExecution:75` |
| 3 | Ascend 以 Y 到位即停机 → 水平偏移 | **C** | 高 | `AscendExecution:67,69`（触发即 stopMovement）、`:77-97`（SETTLING 无驱动）、`:81-87`（超时兜底放宽到 0.6） |
| 4 | bot 台阶高度 1.0，使"墙不挡人"、D-024 墙假设失效 | **C** | 高 | 字节码 `ServerPlayer.<init>`: `fconst_1`→`setMaxUpStep`；`LivingEntity.<init>`: `ldc_w 0.6f`；`LocalPlayer` 无调用（§2） |
| 5 | 输入/旋转相位比物理晚 1 tick，容差预算被吃掉 | **C** | 高 | `BotManager:494-505`、`BotPlayer:106,113,120` vs Baritone `MixinMinecraft:84-91`、`MixinClientPlayerEntity:62-76` |
| 6 | 无中间速度档位（`setSneaking` 0.3x 能力未接线）→ 只能全速或停 | **B** | 中高 | `DescendExecution:102-107`、`TraverseExecution:124-125` vs `BotController:70-74`（能力存在） |
| 7 | 刹车/停止线无预判，只用瞬时 `ab<=0.25` | **B/C** | 中高 | `DescendExecution:96`；实测 ab 一步跨过窗口（§0.1） |
| 8 | 执行期不重查世界（目标被填/支撑被破坏无感知） | **B** | 中高 | `TraverseExecution:54-61` 等仅首 tick 校验 vs Baritone `Movement:153-193`、`PathExecutor:196-212` |
| 9 | 执行器无卡住/无进展看门狗，无超时契约 | **B** | 中高 | `MovementExecution` 全接口无超时；Alice 自身 legacy `PathExecutor:129-143`（`NO_PROGRESS_LIMIT=80`）已有 |
| 10 | 危险方块集合过窄（仅 LAVA/FIRE/MAGMA） | **B** | 中 | `MovementHelper:49-53` vs Baritone `MovementHelper:350-361`（流体/仙人掌/浆果丛/蛛网/气泡柱/末地门） |
| 11 | `canWalkThrough` 是碰撞判定而非寻路质量判定 → 水/蛛网/气泡柱放行 | **B** | 中 | `MovementHelper:37-46` vs Baritone `MovementHelper:144-233` |
| 12 | `canWalkOn` below2 回退与 `isStandingAtFootPos` 语义不一致 | **B** | 中高 | `MovementHelper:26-32` vs `:73-87`（前置可过、后置恒假；半砖落点下一段 `STALE_START`） |
| 13 | 缺 Baritone 的脚位偏置与半砖修正（`+0.1251`、slab 上移、拒绝 bottom slab 落点） | **C/B** | 中 | `IPlayerContext:63-80`、`MovementDescend:203-205` vs Alice 裸 `blockPosition()` |
| 14 | 契约不一致：spec 允许对角 ASCEND/DESCEND，三个工厂全拒 | **B** | 中 | `MovementSpec:68-77` vs `AscendExecutionFactory:35-37`、`DescendExecutionFactory:35-37`、`AscendExecution:124`、`DescendExecution:130` |
| 15 | R2-C 前置弱于 Alice 自身 legacy（未复用 `canSweepPlayer`/`canDescend`/`canTraverse`） | **B** | 中 | `MovementHelper:100-119,125-164,205-216` vs `TraverseExecutionFactory:31-35`、`DescendExecutionFactory:44-48` |
| 16 | 对角升降在 R2-C 真空（低于旧基线 `WalkMovementProvider` 8 方向×3 层） | **B** | 高 | `WalkMovementProvider:25-48`、`WalkMovement:84-87` vs `MovementSpec:63-77` + 三个工厂 |
| 17 | 起手不校验 `onGround`/支撑（悬空起手必卡超时） | **B** | 中高 | `AscendExecution:128-140`、`TraverseExecution:95-97` vs `MovementAscend:161-164` |
| 18 | 进入 EXECUTING 不归一化 controller（继承上任务 sprint/jump/sneak） | **B** | 中 | 各执行器仅在终态 `stopMovement()`；`BotManager:778-785` 不清动作 |
| 19 | 偏离合法位置无快速失败码（只能等超时） | **B** | 中 | `DiagonalExecution:50-71` vs `MovementDiagonal:284-286`（UNREACHABLE） |
| 20 | 能力声明未落实（`supportsMidExecutionRevalidation=true`、`canEnterFluid=false`） | **B** | 中 | `MovementCapabilities:44-46` vs 执行器无对应实现 |
| 21 | `cancel()` 无 safeToCancel 语义 | **B** | 低 | `MovementDiagonal:65-91` vs `DiagonalExecution:74-81` |
| 22 | 头顶空间只查单格，无四邻/落体方块评估 | **B** | 中 | `AscendExecutionFactory:51` vs `MovementAscend:233-243`、`:96-113` |
| 23 | 成本模型双轨（旧 planner 对角=1.0 且拒绝编译 vs 新核心 1.414） | **B** | 低 | `MovementHelper:218-229`、`MovementPlanCompiler:43-48` vs `DiagonalDiagnosticTask:104` |
| 24 | 近距离 `atan2` 退化可致 180° 反转（无 wrap/最小距离保护） | **B** | 中 | 实测 yaw `90.38→-91.42`；Baritone 有 `Rotation.normalizeYaw`/`wrapAnglesToRelative` |
| 25 | 不挖/不放/不开门/不绕角 | **A** | — | HARD_PATH 边界、`MovementCapabilities.pureTraversal`；Baritone 的搭桥/挖掘分支被有意排除 |
| 26 | 瞬时转向、无插值 | **A** | — | 服务端假人无客户端预测/鼠标；Baritone `smoothLook` 默认 false |
| 27 | 不冲刺 | **A** | — | 服务端 `setSprinting` 不改变速度（`Player.getSpeed()` 字节码），故无速度收益 |
| 28 | 完成判定比 Baritone 严（+0.3 中心距） | **A** | — | 与 `WalkMovement:150`、legacy `PathExecutor:37`（`SEGMENT_ARRIVE=0.3`）同口径 |
| 29 | 超时归任务层 | **A**（但需契约化） | — | `MovementExecution` 生命周期契约；问题在于"永不终止"无兜底（#9） |
| 30 | 成本为固定值 | **A** | — | 受限曲面模型既定简化 |
| 31 | 不跳跃（Ascend 依赖自动踩台阶） | **A**（条件性） | — | 在 `maxUpStep=1.0` 下可靠；若改为 0.6 则必须强制跳跃（§2 决策点） |

---

## §2 最重大发现：Alice 假人台阶高度 = 1.0（真实玩家 0.6）

### 2.1 字节码证据（Forge 1.20.1-47.4.10 parchment jar，`javap -p -c`）

| 类 | `setMaxUpStep` 调用 | 值 |
|---|---|---|
| `LivingEntity.<init>` | 1 次 | `ldc_w 0.6f` → `setMaxUpStep(F)V` |
| `ServerPlayer.<init>`（Alice bot） | 1 次 | `fconst_1` → `setMaxUpStep(F)V` = **1.0F** |
| `LocalPlayer`（Baritone 玩家） | **0 次** | 继承 `LivingEntity` 的 0.6F |
| `RemotePlayer` | 1 次 | 同 `ServerPlayer` |

### 2.2 后果

1. **用户观察被证实**："bot 遇到方块会自动上升"是 `maxUpStep=1.0` 的原版行为；真实玩家 0.6 上不了整格，**必须跳**。
2. **Baritone 强制 `Input.JUMP` 是物理必然，不是风格选择**（`MovementAscend:220,230`）。其 `assumeStep`（默认 false）只影响成本估算是否信任踩台阶。
3. **"墙会挡住过冲"的安全假设失效**：一格高的方块对 bot 是可踩台阶。历史日志中反复出现的异常由此解释：
   ```
   west: to=-1,64,3 → actualFoot=-2,65,3  support=false   ← 过冲 1 格 + 踩上高一级
   ```
4. **D-024 的过冲列判定前提需修订**：`DescendExecutionFactory:50-56` 注释"实心墙会挡住过冲（撞停或踩上）"中的"踩上"正是本项；当前实现把"过冲列可穿越"作为需要检查的前提，但**不可穿越的一格墙也可能被踩上去**。
5. **Baritone 的数值常数不可移植**：1.25 制动点、fakeDest、Ascend 跳跃门控都是为 0.6 台阶玩家调校的。

### 2.3 需要决策（架构级，二选一）

| 方案 | 做法 | 代价 |
|---|---|---|
| **A. 保持 1.0** | 承认 bot 物理强于真人，重推所有边缘/过冲/避险逻辑 | 安全边界与 Baritone 参考同时失效；需自建"可踩上一格"的过冲模型 |
| **B. 显式设 0.6** | `bot.setMaxUpStep(0.6F)` 对齐真实玩家 | Baritone 语义/常数可直接参考，D-024 墙假设成立；但 Ascend 必须像 Baritone 一样强制跳跃，且依赖自动踩台阶的现有代码（含 legacy 挖矿）需复测 |

---

## §3 控制回路相位错位（C，量化）

| 项 | Baritone | Alice |
|---|---|---|
| 施加时机 | `MixinMinecraft:84-91`（tick HEAD）→ `PathingBehavior:95-108` → `PathExecutor:93` → `Movement:123-146`，**玩家 tick 之前** | `BotManager:494-505`（`ServerTickEvent.Phase.END`，实体 tick 之后）→ `BotSession:697-710` → `execution.tick()` |
| 旋转施加 | `MixinClientPlayerEntity:62-76` → `LookBehavior:91-102`，与输入同 tick | 执行器直接 `setYRot`，下一 tick 才被 `moveRelative` 消费 |
| 物理入口 | 客户端 `player.tick()` | `BotPlayer:120` 显式 `aiStep()`（javap 全量扫描确认：1.20.1 仅 `LivingEntity` 自身调用 `aiStep`，`ServerPlayer.tick()` 无该调用） |

**量化**：实测水平位移 0.10→0.21 格/tick；1 tick 延迟 ≈ 0.2 格 ≈ Baritone 0.25 停止窗口的 80%、0.5 落点容差的 40%。→ **照搬常数必然失效**，需按"预判滑行距离"重算停止线。

**不受执行器控制的位移来源**（已排查，无第二主动驱动）：原版自动跨台阶（§2）、`BotPlayer:94-96` 击退速度回填、实体推挤、流体/攀爬。

---

## §4 逐 Movement 差异要点

### 4.1 Traverse
- 等价部分：yaw 公式（`TraverseExecution:121` ≡ `RotationUtils:117-125`）、逐 tick 重算朝向、前进输入。
- 主要缺陷：起点严格校验（#1）、无结算（#2）、执行期不重查（#8）、无看门狗（#9）、危险集过窄（#10/#11）、脚位语义二义（#12）、能力声明未落实（#20）。
- 最大风险：SUCCESS 时仍有残余速度且落点未被验证 → 多段链接必 `TRAVERSE_STALE_START`（与 D-023 结论同源）。

### 4.2 Diagonal
- 等价范围仅 dy=0 分支；Baritone 同类含 dy=±1（`MovementDiagonal:51-58,196-219`）。
- 对角升降在 R2-C 真空，且**低于 Alice 自身旧基线**（`WalkMovementProvider:25-48` 8 方向 × 3 层）。
- 侧格扫掠：规划期 `canTraverse` 有，执行期工厂没有（#15）。
- 完成判定更严但无 valid-position 快速失败（#19）。

### 4.3 Ascend（用户已观察的"偶发水平偏移"根因）
- `:67` 以 Y 到位为触发；`:69` 立即 `stopMovement()`；`:77-97` SETTLING 无任何驱动；`:81-87` 超时兜底放宽到 0.6。
- 三者叠加 → 自动踩台阶把 bot 抬到目标高度时 XZ 未到位，随后停手靠惯性，最终以 ≤0.6 偏移"成功"。
- 对照 Baritone：以脚位全维度命中合法位置集为完成条件（`MovementAscend:57-65,172-174`）。
- 起手不校验 onGround/支撑（#17）：悬空起手时原版台阶门槛 `onGround` 不成立，必卡超时。

### 4.4 Descend
- 单循环版已 4/4 精确命中（§0.1）。
- 残留风险：刹车阈值 0.25 从未命中，落点可到 ab≈0.35；停止线无预判（#7）；无中间速度档（#6）。
- 历史 `(7,64,0)` 跑飞：**机制未建立**（该版本已被单循环版替换且无探针日志，证据链已断）；已确认与单循环版无关（失败码 `DESCEND_SETTLING_TIMEOUT` 只存在于旧双阶段版）。
- `safeMode()`/`skipToAscend()` 缺失**不是**本次根因（平地无危险方块时 Baritone 也不触发）。

---

## §5 与 Alice 自身 legacy 的对照（R2-C 反而更弱处）

| 能力 | legacy（生产/挖矿在用） | R2-C 现状 |
|---|---|---|
| 物理结算 | `PathExecutor:103-123`（`MAX_SETTLE_TICKS=30`、`BasicMovement.settle()`） | 仅 Ascend 有 SETTLING，且无驱动 |
| 卡住看门狗 | `PathExecutor:129-143`（`NO_PROGRESS_LIMIT=80`） | 无 |
| 后置 `onGround` | `PathExecutor:105-106` | Traverse/Diagonal 无 |
| 对角升降 | `WalkMovementProvider:25-48`（8×3） | 真空 |
| 下降方式 | `DescendMovement:59,138-145`（**放台阶再走**，无自由下落） | 自由下落（Baritone 风格） |
| 连续扫掠前置 | `MovementHelper.canSweepPlayer:125-164` | 工厂未使用 |
| 同步物理驱动 | `BasicMovement.applyToward:62-64`（直接 `travel()`） | 延迟输入模型 |

**注**：legacy 的 `DescendMovement` 走"放台阶"路线，完全规避了下落/动量/过冲问题；R2-C 选择自由下落是**不同的能力选择**（不消耗方块、不改世界），但必须自带结算与安全约束。

---

## §6 优先级修复建议

### P0（多段 R3 的前置，必须先做）
1. **放宽起点校验为合法位置集**：工厂接受 `{fromFoot, toFoot}`（或上段落点列）作为合法起点，严格相等仅用于链路首段；配套 `snipsnap` 式重对齐。
2. **统一完成契约**：在 `MovementExecution` 层固化"到位 = 脚位正确 + onGround + 水平容差"，四个执行器共用；Ascend 的"Y 到位即停"必须改为 XZ 到位。
3. **决策 §2.3 的台阶高度**（保持 1.0 或改 0.6），并据此修订 D-024 与所有边缘假设。

### P1（鲁棒性）
4. 接入中间速度档（`setSneaking` 0.3x）与"预判停止线"（按当前水平速度 + 阻尼估算滑行距离）。
5. 执行期每 tick 重查关键格；加入无进展看门狗与执行器层超时契约。
6. 统一脚位模型（唯一函数，含半砖/台阶/草丛补偿），修掉 `canWalkOn` below2 与 `isStandingAtFootPos` 的矛盾。
7. 危险集合与 `canWalkThrough` 语义对齐 Baritone（流体/蛛网/气泡柱/仙人掌/浆果丛）。
8. 补齐对角升降（扩展 ASCEND/DESCEND 或新增类型），同时移植 head-bonk/可越性检查。
9. 起手校验 `onGround` + 支撑；进入 EXECUTING 前归一化 controller 输入。

### P2（一致性/健壮）
10. 能力声明与实际一致（`supportsMidExecutionRevalidation`、`canEnterFluid`）。
11. 偏离合法位置快速失败码、`cancel()` 安全语义、头顶四邻检查、落体方块评估。
12. 统一成本源（避免双轨）；yaw 加最小距离保护。

---

## §7 未验证项与下一步

- 单循环版 Descend 在"落点列边缘 + 最大水平速度"极端场景的跨列风险：无实测日志。
- 半砖/台阶/草丛落点差异：无实测场景。
- 其余三个 Movement 在本轮无新客户端测试（Traverse/Diagonal 沿用 R2-C 归档，Ascend 沿用"通过但有偏移"）。
- 多段链接（`STALE_START` 断链）尚未有实测用例——这是 P0#1 的验收条件。
- 临时探针 `[Descend-PROBE]` 待用户确认验收后移除。

**审计输入来源**：5 个独立只读审计（Traverse / Diagonal / Ascend / Descend / 跨切面时序）+ 本报告作者的独立核验（字节码、归档日志、legacy 对照、契约一致性）。
