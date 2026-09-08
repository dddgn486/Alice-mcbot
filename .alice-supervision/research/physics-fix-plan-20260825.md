# bot 物理失效修复线规划（注射点设计对比）——F1-F6 定案后的修复线

- 规划角色：Alice 实现规划员
- 日期：2026-08-25
- Git 基线：`21f653f`（HEAD，机制定位证据报告落盘；任务书标注 e9af1b3 为 NPE 佐证验收后 HEAD）
- 任务书：`.alice-supervision/research/physics-fix-planning-task-20260825.txt`
- 用户批准：✅（2026-08-25 批准「补佐证 → 规划修复线」两步走；本任务是第二步的规划环节）
- 性质：**只读规划**——不修改任何文件；只产出「修复设计对比 + 推荐方案 + 验证矩阵」；**不预定最终选型**（监督员审核 + 用户批准后才定）；**不实现修复**。

> **本规划不构成实施授权。** 修复实施方案需监督员审核 + 用户批准后写入 active plan；本报告给出的是可对比的设计候选与推荐，不是选型结论。

---

## 0. 定案事实基线（规划依据）

### 0.1 已确认事实（监督员定案裁决 + 机制报告 + 断言/NPE 证据）

| 证据 | 事实 | 来源 |
|---|---|---|
| 主根因 | 实体 tick 链（aiStep→pushEntities→move 碰撞段）的推挤/碰撞响应**未被 bot 有效消费** | verdict §三:42-50 |
| 任务层链正常 | `BotManager.onServerTick`（:345-353）每 tick 直接 `session.tick → task.tick → SoftMovementPrimitive.applyToward → bot.travel`，**不经 bot.tick()**——F4/F5/F2 PASS | verdict :46 / mechanism ②:48 / BotManager.java:345-353 |
| 实体链失效 | push 写入 delta=0.5 但 tick 后位移 0、碰撞 flags 恒 false（F3b/tickChain FAIL） | f1-f6-assertion-result :33,35 |
| B2 排除 | F5 两时序均有位移（dSame=0.980 / dKeep=1.000 / dDelayed=1.890）；任务层 travel 保留击退分量 → 时序覆盖不成立 | verdict :35 / assertion :36-37 |
| B3 排除 | stderr 0 字节 + stdout 236,851 字节透传佐证 → **无 NPE** | npe-evidence :14-15 / verdict :36 |
| flags 设置点 | `horizontalCollision`（Entity.move 偏移 335）/`verticalCollisionBelow`（偏移 381）**仅由 Entity.move 碰撞段设置**；位移 0 → 无碰撞 → flags false | mechanism ④:65-71 / verdict :49 |
| aiStep 链 | `pushEntities` 在 aiStep 偏移 1064 **无条件到达**（A1-A7 均不跳过） | mechanism ①:28-30 |
| 三条驱动链 | 原版实体循环（ServerLevel entityTickList → BotPlayer.tick()）/ 任务层（ServerTickEvent END，不经 bot.tick()）/ fixture 手动 bot.tick() | mechanism ②:36-42 |
| F1 hurt false | `hurt()` 双源（generic/Zombie）均返回 false、health 不变、delta 恒 0——**独立于 tick 链的另一条未触发路径（isPushable/hurt 检查）** | verdict :37 / assertion :32 |

### 0.2 未定案项（修复规划必须覆盖或标注）

| 候选 | 内容 | 来源 |
|---|---|---|
| **a** | push delta 在实体链 move 前被清零（任务层 travel 与实体链 delta 处置差异） | mechanism ③:55-57 |
| **b** | `pushEntities` 内部「实体-实体」推挤条件未满足（Zombie 放置高度/碰撞箱不重叠） | mechanism ③:55-57 |
| **c** | 墙距 3 格外单 tick 位移不足（数值维度，需复测墙置相邻格）——**tickChain FAIL 可能是 fixture 场景问题而非链失效** | mechanism ③:55-57 |
| **F1 hurt false 单独路径** | 是主根因的伴随现象还是第二根因——**修复后复测区分**（⚠️ 标记） | verdict :50,59 |

### 0.3 现有 fixture 与驱动关系（实施时可复用）

- `BotPhysicsAssertionFixture.java`（278 行）：F1/F3/F4/F5/F2/tickChain 六断言，fixture 内自清理（setHealth 恢复/teleportTo 归位/discard zombie/不写档），日志 `BOT_PHYSICS_ASSERTION_SUITE`；挂于 `BotSelftest.setup()` spawn 之后。
- `BotPlayer.java:28-36`：仅 override `tick()`（try/catch super.tick()，catch NPE printStackTrace）。
- `BotManager.java:345-353`：ServerTickEvent END 遍历 BOTS → `session.tick(hazard)`，**不调 bot.tick()**。
- `SoftMovementPrimitive.java:48-53`（NATIVE_TRAVEL：xxa=0/zza=1/travel(0,0,1)）、`:82-86`（settle：xxa=0/zza=0/travel(Vec3.ZERO)）。
- travel 调用点 5 处：SoftPathProbeTask:141-144 / SoftPathMineTask:167-170 / FollowTask:106-107 / SoftMoveProbeTask:119-120 / settle 共用 :82-86。

---

## 1. 修复方案设计对比（3 候选详列）

### 方案 A：实体链驱动修复

| 项 | 内容 |
|---|---|
| **概述** | 确保 `bot.tick()` 实体链被完整、正确地驱动且不被任务层 travel 绕过——修改任务层与实体链的驱动关系/时序，使 aiStep→pushEntities→move 在 bot 上每 tick 有效运行。改动位置：`BotManager.onServerTick`（:345-353）与/或 `BotPlayer.tick()`（:28-36）。 |
| **注射点候选** | **A-1**（主要）：`BotManager.onServerTick` END 阶段、`session.tick` 之前显式调用 `bot.tick()` 实体链段（前置驱动）；**A-2**：`BotPlayer.tick()` 保留 catch 但在链完整前提下确保 aiStep 段不被任何提前返回跳过（对照 mechanism ① A1-A7 分支清单逐条核对，当前证据显示无跳过分支——**A-2 若仅为此则无增量**）；**A-3**：时序对齐——确认原版实体循环与 ServerTickEvent END 的先后关系，若实体循环先于任务层 travel，则将任务层 travel 改为在实体链之后驱动（当前机制报告 ② 已确认实体循环每 tick 驱动 bot.tick()——**若属实，A-1 会造成双驱动**，必须先复测确认实际驱动时序）。 |
| **机制原理** | 让实体链（aiStep→pushEntities→move 碰撞段）在**任务层 travel 之前/独立于任务层**完整消费 push delta：push 写入 delta → 实体链 move 消费 → 位移发生、碰撞 flags 置位。若事实是实体链已被原版循环驱动而仍失效，则 A 的本质变为「消除任务层与实体链的 delta 处置差异」（转入 B 的候选 a）。 |
| **覆盖/排除** | 候选 **a**：部分覆盖（依赖 A-3 时序复测——若实体链确实已被驱动，A 无增量，需移交 B）；候选 **b**：不覆盖（pushEntities 内部条件与驱动方式无关）；候选 **c**：不覆盖（墙距是 fixture 场景问题）；**F1 hurt false**：不涉及（hurt 是独立调用路径）。 |
| **风险** | **双驱动回归风险（最高）**：实体循环若已调 bot.tick()（mechanism ② 已确认 entityTickList 每 tick 驱动），A-1 补调会造成每 tick 两次 aiStep/travel → 任务层 F4/F5/F2 时序与位移全部改变 → **任务层链回归高风险**；需先复测实际驱动时序才能定注射点（前置成本）。 |
| **量级** | BotManager 1-3 行 + 驱动时序复测（0.2-0.5 天）；**若复测证明双驱动已存在则 A 方案作废转向 B/C**。 |

### 方案 B：实体链 delta 保真修复

| 项 | 内容 |
|---|---|
| **概述** | 修正实体链内 push delta 的消费路径：定位并将「move 前清零/覆盖外部 delta」的逻辑改为「外部写入 delta（push/knockback）保真进入 move」，使推挤/击退位移与碰撞 flags 在实体链中生效。改动位置：`BotPlayer`（override `hurt`/`push`/`knockback` 或关键 delta 处置点）与/或 `SoftMovementPrimitive` travel 链。 |
| **注射点候选** | **B-1**（主要，对应候选 a）：在 BotPlayer/实体链内 travel 前置 delta 处置处保留外部 delta——具体清零点**未定案**（mechanism ③.3 未逐行展开 pushEntities 内部），实施首步必须 javap 逐行定位清零/不消费点（`moveRelative` 叠加 vs push delta 保留的差异，mechanism ③.2）；**B-2**（对应候选 b）：`pushEntities` 内部「实体-实体」推挤条件（碰撞箱重叠/isPushable/高度判定）逐行展开并修正——若候选 b 成立；**B-3**：`BotPlayer` 增加针对性 override（如 `hurt()` 保证 knockback 写入后不被任务层输入路径清零），需对照 R31「不从零重写核心移动」边界判定。 |
| **机制原理** | 实体链本质是原版链：aiStep 无条件到 pushEntities（偏移 1064），flags 仅由 Entity.move 碰撞段设置（偏移 335/381）。因此「push delta 无位移、flags false」的充分条件是 **move 未携带有效位移**；B 的目标是修「delta 处置差异」，让 push/knockback delta 与任务层输入一样进入 move 并被消费。 |
| **覆盖/排除** | 候选 **a**：**直接覆盖**（清零/不消费点的保真修复）；候选 **b**：条件覆盖（仅当 b 成立时 B-2 生效——需先复测 b）；候选 **c**：不覆盖（墙距是 fixture 场景问题，可由复测排除后再定 b/a）；**F1 hurt false**：B-1 若覆盖 `hurt()` 链则不涉及（hurt 返回 false 在 isPushable/hurt 检查层，B-3 可覆盖「hurt 后 knockback 不被清零」，但 hurt 自身返回 false 的检查路径需另定位——标记伴随复测）。 |
| **风险** | ① 清零点未定案 → 盲改回归风险（改错层会破坏任务层 travel 的 F4 摩擦/F5 时序/F2 保留）；② 改动在实体链与任务层共享的 delta 通道上，需双链回归保护（M1 全套 + 客户端 M2/M3）；③ 若候选 c/b 成立（fixture 场景问题），B 修改的是不存在的问题 → 需定位复测前置。 |
| **量级** | 定位复测（javap pushEntities 逐行 + 墙相邻格复测 + Zombie 驱动 tick 复测）0.5 天；修复 1-2 文件、20-50 行；合计 1-1.5 工程日。 |

### 方案 C：任务层物理等价补链

| 项 | 内容 |
|---|---|
| **概述** | 不动实体链；在**任务层 travel 链上补「推挤/碰撞等价消费」**：任务层每 tick 检查 bot 的外部 push delta（未消费部分），显式执行位移消费/碰撞响应等价逻辑（复用原版 `Entity.move` 或等价位移 + 碰撞检测），使推挤/击退在任务层可观测、可位移。改动位置：`SoftMovementPrimitive`（NATIVE_TRAVEL/settle 分支旁）或 `BotManager.onServerTick`（:345-353）新增兜底消费段。 |
| **注射点候选** | **C-1**（主要）：`BotManager.onServerTick` END、`session.tick` 之后——对每个 bot 检查 `bot.getDeltaMovement()`：若水平分量来自外部推挤（任务层输入之外的 delta，如 `pushEntities` 写件）且实体链未消费，则由任务层显式 `bot.move(MoverType.SELF, delta)` 消费 + 触发碰撞检测（flags 语义对齐 Entity.move 335/381 的等价实现或直接复用原版 move）；**C-2**：`SoftMovementPrimitive.NATIVE_TRAVEL` 分支（:48-53）扩展——travel 前保留外部 delta 与输入 delta 的叠加（**即 B 的候选 a 在任务层侧的等价实现，绕开实体链清零点**）；**C-3**：新建任务层 helper（如 `task/BotPhysicsBridge.java`）封装「推挤响应消费」段，供 C-1/C-2 复用（若 C-2 已覆盖则 C-3 可免）。 |
| **机制原理** | 任务层链已被证明完整（F4/F5/F2 PASS、travel 摩擦/时序/保留全正常）；实体链失效点不在任务层可改范围内。C 让「推挤/碰撞响应」在任务层获得**可验证的等价消费**：F3b 的「push 后位移 0」在任务层兜底段会变为「push delta 被任务层 move 消费 → 位移 + flags 置位」——直接把 F3b/tickChain 的判别点接到已验证可用的任务层链上（架构一致性：R33 任务逐 tick 显式 travel 已是既定模式）。 |
| **覆盖/排除** | 候选 **a**：**完全规避**（任务层在实体链清零点之外显式消费——不受实体链 delta 处置差异影响，mechanism ③.1 差异被绕开）；候选 **b**：**完全规避**（任务层自身判「外部 delta 存在即消费」，不依赖 pushEntities 内部条件）；候选 **c**：覆盖（任务层可显式控制每次消费位移量，墙距/碰撞检测由任务层侧 move 决定——亦可用 M2/M3 客户端验证真实场景）；**F1 hurt false**：不直接覆盖（hurt 检查路径独立）——但 C 修复后**必复测 F1**：若 F1 恢复 true → F1 false 是伴随现象（主根因单一）；若仍 false → 第二根因另线（verdict :50,59 要求）。 |
| **风险** | ① **双消费风险**：任务层兜底消费与实体链 move 若同时消费同一 delta → 位移加倍/位移顺序改变——需消歧规则（如「实体链 flags 已置位/位移已发生则任务层跳过」或「任务层成为唯一移动消费者、实体链降级」——后者触碰 BotPlayer.tick → 需要监督员判定 R31/R33 边界）；② 任务层显式 move 可能绕过原版 aiStep 的「输入路径摩擦/重力」细节 → F4/F5/F2 回归需全量复测（M1 套件 6 断言全绿）；③ 属于新增执行路径，需文档化（R33 验证边界表述需更新——D1/D2 待监督员判定的边界漂移项）。 |
| **量级** | C-1 单点 15-40 行 + 消歧规则（0.3-0.5 天）；若需 C-2/C-3 扩展 80-150 行跨 2-3 文件；合计 **1-1.5 工程日**。 |

### 三方案对比总表

| 维度 | A 实体链驱动 | B 实体链 delta 保真 | C 任务层等价补链 |
|---|---|---|---|
| 覆盖未定案 a | 部分（依赖时序复测） | **直接覆盖** | **完全规避** |
| 覆盖未定案 b | 否 | 条件覆盖（需复测 b） | **完全规避** |
| 覆盖未定案 c | 否 | 否（由复测排除） | **覆盖** |
| F1 hurt false 复测安排 | 不涉及（复测仍必须） | B-3 条件覆盖（复测仍必须） | 不直接覆盖（复测仍必须） |
| 回归风险（F4/F5/F2） | **高（双驱动）** | 中（共享 delta 通道盲改） | 中（双消费需消歧规则） |
| 改动面 | BotManager/BotPlayer | BotPlayer/SoftMovementPrimitive | SoftMovementPrimitive/BotManager（+可选 helper） |
| 与任务层 travel 架构一致性 | 低（绕回实体链） | 中（改共享通道） | **最高（任务层既有模式扩展）** |
| 量级 | 0.2-0.5 天（或作废） | 1-1.5 天 | **1-1.5 天** |

---

## 2. 推荐方案与理由

### 2.1 推荐：方案 C（任务层物理等价补链），注射点 C-1（BotManager.onServerTick 尾段兜底消费）为首选，C-2 作为候选 a 的补充手段

理由（按任务书四项：覆盖最多 / 回归最小 / 改动最小 / 与任务层 travel 架构最一致）：

1. **覆盖未定案项最多**：C 对三候选 a/b/c 全部规避或覆盖（见 §1 C 行）——A 依赖「实体链缺驱动」前提，但机制报告 ② 已确认实体循环每 tick 驱动 bot.tick()（A 的前提大概率不成立，反而有双驱动风险）；B 依赖候选 a/b 未定案的清零点 javap 定位，盲改共享 delta 通道回归风险高。C 直接在**已被证明完整可用的任务层链**上做兜底消费，不依赖任何未定案机制结论。
2. **回归最小（F4/F5/F2 不得回归）**：C-1 插在 `session.tick` **之后**的兜底段，任务层 travel 链（摩擦/时序/保留）本身零改动；C-2 若启用也只是在 NATIVE_TRAVEL 分支内保留外部 delta（对照 F5 dKeep=1.000 已证明任务层天然保留击退分量——C-2 实际是防御性冗余）。A 的双驱动会直接破坏 F5 时序；B 改共享 delta 通道有清零点错位风险。
3. **改动最小且可回滚**：C-1 单点 15-40 行 + 消歧规则；undo = 删兜底段。A/B 都依赖前置复测（A 需时序验证、B 需 javap 定位），前置成本即 0.5 天。
4. **与任务层 travel 主导架构最一致**：R33 既定「任务逐 tick 显式 travel」是当前驱动模式；C 把「推挤响应」同样收敛到任务层，使 bot 的物理消费路径**单一化、可断言化**（F3b/tickChain 的判别点直接接到任务层链），并保持 `BotPlayer`/`FakeConnection` **零改动**——跳异常线（ab510fd 广播）完全不受影响（verdict §四:70「不触碰其他线」）。

### 2.2 为什么不用其他方案

| 方案 | 不用理由 |
|---|---|
| A | 机制报告 ② 已确认实体循环每 tick 驱动 bot.tick()——「补驱动」前提不成立；强行补调 = 双驱动，直接回归任务层 F4/F5/F2；A 仅剩「时序对齐」子项，其本质已落入 B 的候选 a，无独立增量。 |
| B | 候选 a/b 均**未定案**（mechanism ③.3 自述：pushEntities 内部条件未逐行展开、「清零点」是推断候选）；在未定位的共享 delta 通道上盲改是 B 的核心风险（改错层即回归任务层链）；B 的正确时序应是「定位复测后、若 a/b 确认」再细化——可作为 C 失效时的后备（标注：若 C 实施后 F3b 仍 FAIL 且任务层兜底段被确认未触发，则转 B 做实体链定位修复）。 |
| 组合 | 首轮不组合：C 独立实施即可验证候选 a/b/c 的排除效果；若 C 后仍 FAIL 才有 B 介入的必要（组合会增加双消费消歧复杂度，违反「改动最小」）。 |

> ⚠️ **选型边界**：本推荐不构成选型结论。定案须经监督员审核本对比 + 用户批准后写入 active plan；实施首步仍建议包含**定位复测小包**（墙置相邻格复测 c、Zombie 自身 tick 驱动复测 b——mechanism ③.3 明确要求的两个复测），用于确认「tickChain FAIL 是 fixture 场景问题还是真实链失效」，该复测结果可微调 C-1 的消歧规则（如 c 成立 → tickChain 断言场景修正，C 兜底段仍保留）。

---

## 3. 回归与验证矩阵

> 验收原则：服务端 PASS ≠ 客户端验收；编译/headless 不能标 USER_ACCEPTED（SUPERVISION_PROTOCOL / evidence-collection-standard）。

### 3.1 服务端回归（修复后必须全绿）

| 项 | 断言 | 预期 | 来源 |
|---|---|---|---|
| R1 | `BOT_PHYSICS_ASSERTION_SUITE` 6 断言 | **F3b/tickChain 转 PASS**（实体推挤产生位移/flags 置位——C 兜底消费后）；F4/F5/F2 **保持 PASS**（travel 链不回归）；F1 **按复测结果标记**（见 3.3） | f1-f6-assertion-result :28 |
| R2 | `SOFT_PHYSICS_OBSERVATION_SUITE`（既有 P0 门禁） | 保持 PASS（观测字段/revalidate/replan 零变化） | BotSelftest:352 |
| R3 | 既有 suites（TRANSFER_*/BOT_INVENTORY_*） | 保持 PASS（C 不改任务语义/ledger/inventory） | f1-f6-assertion-result :24 |
| R4 | 矿链 HARD_PATH（R30） | `MineTask`/`DropCollectionTask`/`BotMiner` **零改动**（grep 复核零 SOFT 引用，与 pathing/task 审查结论一致） | review-batch1-pathing :44 |
| R5 | SOFT 实验线边界（R33/R35/R36） | C-1 只加兜底消费，不改 5 个 travel 调用点（SoftPathProbeTask:141-144 等）与 settle/follow 语义 | review-batch1-task 特别核查① |

### 3.2 客户端验证（Windows 实测，用户执行，监督员格式）

| 场景 ID | 场景 | 观察点（Windows 客户端） | 预期/supporting 日志关键字（服务端） | 失败回收条件 |
|---|---|---|---|---|
| M2 | 推挤 bot（玩家持续接触） | bot 是否被推动（可见位移/不再穿玩家） | `BOT_PHYSICS_ASSERTION_SUITE`（f3=true、f1 复测行）+ corr 对齐（evidence_v1 风格） | 服务端 PASS 而客户端仍不可见 → EVIDENCE_CONFLICT（补双端证据，不得以服务端 PASS 覆盖）；C 兜底段被确认未触发时 → 转 B 定位 |
| M3 | 击退 bot（玩家攻击一次） | 击退动画/位移是否可见、距离合理 | `f2=true`（击退保留）+ corr 对齐 | 同上；若 F1 hurt false 且修复后仍 false → 第二根因线，M3 暂不等同 bot 物理线验收 |
| M4 | 软路径任务复跑（可选回归） | bot 走 soft-path 时推挤/击退不再改变任务轨迹 | `soft_phys_*` 关键字（disruption/revalidate 仍按既有观测） | 任务层 travel 时序/位移被兜底段干扰 → 停止，回滚兜底段，重定消歧规则 |
| M5 | 跳跃异常独立线回归（不触碰） | 玩家跳跃异常（ab510fd）**本线不修**：仅记录是否仍复现（独立 corr） | FakeConnection 广播日志（如监督员批准） | 本线不触碰 FakeConnection → 跳跃异常状态不变即符合边界；若 C 意外影响 → 立即停止并报告 | 

### 3.3 F1 hurt false 复测安排（实施验收标准必须包含）

- **复测时机**：C 实施并 M1 服务端断言通过后，**同一运行窗口内复测 F1**（fixture 已有 generic/Zombie 双源，无需新增代码）。
- **判定**：
  - F1 恢复 true（hurt 返回 true、delta 非零）→ **F1 false 是伴随现象**（主根因单一 = C 修复目标），bot 物理线定案闭环；
  - F1 仍 false → **第二根因**：isPushable/hurt 检查路径独立定案（候选：BotPlayer 构造后 isPushable 条件、hurt 的 damageSource 检查、ServerPlayer 特殊路径）——单独立调查包，**不得并入本修复线混修**（verdict :50,59 要求）。
- **记录**：复测结果写入 `.alice-supervision/research/` 证据报告（corr/tick/health/delta 双源数值 + latest.log 行号）。

---

## 4. 实施授权边界

- **本规划不构成实施授权。** 修复实施方案（含方案 C 注射细节、消歧规则、定位复测小包）需监督员审核本对比 → 写入 active plan → 用户批准后实施。
- **实施范围限定**：只改定案主根因相关实现（方案 C：`BotManager.onServerTick` 兜底段 + 可选 `SoftMovementPrimitive` 分支；方案 B 后备时：`BotPlayer`/delta 处置点）。**不触碰**：`FakeConnection`（跳跃异常线独立）、跳跃异常线本身、tunnel/transfer/road 线、`MineTask`/`DropCollectionTask`/`BotMiner`（HARD_PATH R30）、`SoftPhysicsObservationTest`（P0 门禁）、skills-manifest/state-machine/HANDOVER 的既有裁决（R# 不翻案）。
- **验收标准（实施时）必须包含**：R1-R5 服务端断言全绿 + 客户端 M2/M3（Windows 实测）+ F1 hurt false 复测判定（§3.3）+ EVIDENCE_CONFLICT 停止条件。任何「服务端 PASS」不得自动成为客户端验收。
- **不预定最终选型**：本报告推荐 C，但选型由监督员审核 + 用户批准；若定位复测小包结果显示实体链在友好场景下正常（候选 c/b 成立），C 的兜底段与断言场景可同步微调，仍属本推荐范围（不翻案）。

---

## 5. 交付状态

- 报告路径：`.alice-supervision/research/physics-fix-plan-20260825.md`
- 未改任何文件：本规划只读——业务代码/测试/fixture/skill/HANDOVER/active plan 均未动（工作区 `.alice-supervision/active-plan.md` 的既有修改属定案流程，非本会话改动）；未运行测试；仅读取任务书、定案裁决、机制证据、F1-F6 断言结果、NPE 佐证、BotManager/BotPlayer/FakeConnection/BotPhysicsAssertionFixture 源码与 git 基线。
- 待移交监督员：三方案对比（§1）、推荐方案与理由（§2）、回归验证矩阵（§3）、实施授权边界（§4）；监督员审核后决定选型/写入 active plan。

---

**本规划不构成实施授权。** 修复实现、选型批准须由监督员审核 + 用户批准后另行执行。
---

## 监督员验收记录（2026-08-26，Architecture Supervisor）

**验收结论：✅ 通过（bot 物理修复线规划）**

独立核查：
1. 关键代码引用属实：BotManager.onServerTick（:345-353 任务层 session.tick 不调 bot.tick，sed 抽查）、SoftMovementPrimitive NATIVE_TRAVEL（:48-53 xxa=0/zza=1/travel(0,0,1)）与 settle（:82-86 xxa=0/zza=0/travel(Vec3.ZERO)）——任务层 travel 链实现与规划描述一致 ✅
2. 三方案对比完整（A 实体链驱动/B 实体链 delta 保真/C 任务层等价补链），每方案含概述/注射点/机制原理/覆盖未定案项/风险/量级 ✅
3. 推荐 C（任务层物理等价补链，注射点 C-1=BotManager.onServerTick 尾段兜底消费）理由充分：覆盖未定案项 a/b/c 最多（完全规避）、回归最小（任务层 travel 链零改动）、改动最小（15-40 行单点）、与任务层 travel 主导架构最一致；A 因机制报告②已确认实体循环每 tick 驱动 bot.tick() 而前提不成立（双驱动风险）；B 因清零点未定案而盲改回归风险高 ✅
4. 回归矩阵完整：R1-R5 服务端断言（F3b/tickChain 转 PASS、F4/F5/F2 不回归、矿链 R30 零改动、SOFT 实验线边界不改 5 调用点）+ 客户端 M2/M3/M4/M5（Windows 实测、EVIDENCE_CONFLICT 停止条件、跳跃异常线不触碰）✅
5. F1 hurt false 复测安排明确（§3.3）：修复后同窗复测，恢复 true = 伴随现象，仍 false = 第二根因单独立包，不混修 ✅
6. 边界全守：不触碰 FakeConnection（跳跃异常线独立）、MineTask/DropCollectionTask/BotMiner（HARD_PATH R30）、SoftPhysicsObservationTest（P0 门禁）、R# 不翻案；服务端 PASS ≠ 客户端验收 ✅
7. 只读性：src 零改动、HEAD 仍 21f653f ✅
8. 不预定最终选型：推荐 C 但选型由监督员审核+用户批准；定位复测小包（墙置相邻格复测 c、Zombie 驱动复测 b）可微调兜底段，不翻案 ✅

**采纳为修复线实施依据。下一步：写修复 active plan 草案（方案 C）报用户批准。**
