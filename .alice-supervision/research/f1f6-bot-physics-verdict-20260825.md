# F1-F6 bot 物理失效定案裁决（监督员，Architecture Supervisor）

- 裁决人：Alice 架构监督员
- 日期：2026-08-25
- 依据证据链：
  1. `research/f1-f6-assertion-result-20260825.md`（F1-F6 断言结果）
  2. `research/npe-evidence-result-20260825.md`（stderr 0 字节 → B3 排除）
  3. `research/physics-chain-mechanism-evidence-20260825.md`（aiStep 分支清单 / 驱动链三线 / flags 设置点 javap / F3b 复现定位）——报告尾部已附监督员验收记录
- active plan：`20260825-f1f6-bot-physics-assertions-v1`（区分表定案环节）
- 性质：**根因定案（裁决）**。本裁决只定案，**不构成修复授权**——修复是下一步（另行规划 + 用户批准）

---

## 一、证据事实汇总（全部经监督员独立复核）

| 证据 | 事实 | 复核 |
|---|---|---|
| F1 | `bot.hurt()` 双源（generic/Zombie）返回 **false**、health 不变、delta 恒 0 | ✅ 断言报告 :32 |
| F3a | `push` 直接写入 delta=0.500 **正常**（push 写入路径可用） | ✅ :33 |
| F3b | 实体推挤链 **无位移**（entityPushed=false）| ✅ :33 |
| F4 | travel 摩擦衰减 **执行**（ratioH=0.728）、含重力 -0.078 | ✅ :34 |
| tickChain | 手动 `bot.tick()` 后碰撞 flags **恒 false**（horizontalCollision=false->false）| ✅ :35 + 机制报告 ③ |
| F5/F2 | 同 tick < 延迟 tick、击退分量保留（任务层 travel 链正常）| ✅ :36-37 |
| NPE | **stderr 0 字节**（stdout 236KB 透传佐证）→ **无 NPE** | ✅ npe 报告 |
| 驱动链 | 任务层 `BotManager.onServerTick` 只调 `session.tick`（任务动作），**不调 bot.tick()**；任务 travel 直接驱动，不经实体链 | ✅ 机制报告 ② |
| aiStep | `pushEntities` 在 aiStep 偏移 **1064 无条件到达**（A1-A7 均不跳过）| ✅ 机制报告 ① |
| flags 设置点 | `horizontalCollision`(335)/`verticalCollisionBelow`(381) **仅由 Entity.move 碰撞段设置** | ✅ 机制报告 ④ |

---

## 二、候选闭包（区分表执行）

| 候选 | 判定 | 依据 |
|---|---|---|
| **B2 时序覆盖**（travel 抹掉击退）| ❌ 排除 | F5/F2 PASS：任务层 travel 保留击退分量、时序差异存在——任务层 Travel 链**正常** |
| **B3 NPE 吞错**（BotPlayer.tick catch 吞 NPE 跳过 aiStep）| ❌ 排除 | stderr 0 字节 + 透传佐证 → **无 NPE**；aiStep 的 pushEntities 无条件到达 |
| **hurt/isPushable 链未触发（B2 旧表述）**| ⚠️ 现象确认（F1 false）| `hurt()` 直接调用返回 false——**独立于 tick 链**，是另一条未触发路径（isPushable/hurt 检查失败），但与主根因（实体链未有效驱动）可能关联 |
| ✅ **主根因定案：实体 tick 链（aiStep→pushEntities→move 碰撞段）未被有效消费** | **定案** | 见下节 |

---

## 三、定案结论（主根因）

**bot 物理失效的主根因 = 任务层 travel 与实体 tick 链是两条独立驱动链，实体链的推挤/碰撞响应未被有效消费：**

1. **任务层链**（B2 相关）**运行正常**：`BotManager.onServerTick` 每 tick 直接调 `SoftMovementPrimitive.applyToward→bot.travel(...)`（SoftPathProbeTask:143），不经 `bot.tick()`——所以 **F4/F5/F2 PASS**（travel 摩擦/时序/击退保留都在任务层发生）。
2. **实体链**（推挤/碰撞承载段）**在 BotPlayer 上未被有效驱动或未被有效回应**：
   - 原版 `LivingEntity.aiStep()` 的 `pushEntities` 无条件到达（偏移 1064），但 **fixture 手动 `bot.tick()` 后 push delta（0.500）清零、位移 0、碰撞 flags 恒 false**（F3b/tickChain）。
   - flags 仅由 `Entity.move` 碰撞段设置（javap 335/381）——位移 0 → 无碰撞 → flags false，**与 "push delta 未在实体 move 中被消费" 直接吻合**。
3. **F1 hurt false 与主根因的关联**：`hurt()` 是独立调用路径（不依赖 tick 链），返回 false 说明 isPushable/hurt 检查另有原因——**是伴随现象还是第二根因，需修复后复测区分**（标记为 ⚠️ 待修复期复测项，不并入本定案）。

---

## 四、定案边界与遗留

- ✅ **定案范围**：主根因 = 实体链推挤/碰撞响应未被消费（任务层 travel 独立、实体链未生效）。B2/B3 已排除。
- ⚠️ **未定案项**（修复规划必须覆盖或标复测）：
  1. **精确注射点**：实体链 delta 清零发生在哪一层（`BotPlayer.tick` 未驱动？`aiStep` 后段条件？`pushEntities` 内部条件未满足？）——机制报告给出三候选（③:55-57），**修复规划需给出"如何驱动/保 delta"的注射设计**；
  2. **F1 hurt false 单独路径**：修复主根因后需复测 F1 是否恢复（若否，是第二根因，另行定案）。
- ❌ **本裁决不构成修复授权**。

---

## 五、修复规划要求（交付给规划员/主开发员的边界）

修复线 active plan（下一步，需用户批准）必须：
1. 覆盖定案主根因：**让实体链的推挤/碰撞响应对 bot 生效**（注射候选：确保 bot.tick() 实体链被有效驱动/或任务层补 pushEntities 等价消费/或修正 aiStep 后段条件——三候选由规划员给出设计对比，监督员审核、用户批准）
2. 保留任务层 travel 现有正常行为（F4/F5/F2 不得回归）
3. 修复后复测：F1-F6 全绿 + 客户端 M2/M3（用户 Windows 实测）——**编译/服务端 PASS ≠ 客户端验收**
4. 不触碰其他线（跳跃异常线/tunnel/transfer/road）

---

**裁决完成时间**：2026-08-25  
**状态**：待修复规划（监督员将派规划员出修复线设计对比 → 审核 → active plan 草案 → 用户批准）