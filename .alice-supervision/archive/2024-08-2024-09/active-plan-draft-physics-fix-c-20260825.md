# Alice Active Work Plan（修复线草案，待用户批准）

- Plan ID: `20260825-bot-physics-fix-c-v1`
- Status: **PENDING_USER_APPROVAL**（草案；批准后登记为正式 active plan）
- Baseline: `21f653f`（HEAD：机制定位证据报告落盘；F1-F6 断言+NPE 佐证+定案裁决均已完成验收）
- User approval: ⏳ **待用户批准**（2026-08-25 批准「补佐证→规划修复线」两步，本草案为修复线实施计划）
- 定案裁决（监督员，已落盘）：`.alice-supervision/research/f1f6-bot-physics-verdict-20260825.md`
- 修复线规划（已验收）：`.alice-supervision/research/physics-fix-plan-20260825.md`

## Objective

**修复 bot 物理失效主根因**（定案：实体 tick 链的推挤/碰撞响应未被 bot 有效消费）——按规划推荐 **方案 C（任务层物理等价补链）**：任务层 travel 链之外补「外部 push delta 兜底消费」，使推挤/击退在任务层可观测、可位移、碰撞 flags 可置位。

**明确边界**：只改定案主根因相关（方案 C）；**不触碰** FakeConnection(跳跃异常线独立)、矿链 HARD_PATH(R30)、SOFT 实验线 5 个 travel 调用点、P0 门禁。

## Root Cause（定案裁决摘要）

- **主根因**：实体 tick 链（aiStep→pushEntities→move 碰撞段）推挤/碰撞响应未被 bot 有效消费——push 写入 delta=0.5 但 tick 后位移 0、碰撞 flags 恒 false（F3b/tickChain FAIL）
- **已排除**：B2 时序覆盖（F5 两时序均有位移）、B3 NPE 吞错（stderr 0 字节）
- **已确认**：任务层 travel 链正常（F4/F5/F2 PASS，`BotManager.onServerTick` 直接 travel 不经 bot.tick()）；碰撞 flags 仅由 `Entity.move` 碰撞段设置
- **未定案项**（方案 C 完全规避）：a 实体链 delta 清零点 / b pushEntities 内部条件 / c 墙距数值维度；F1 hurt false 复测安排（修复后区分伴随/第二根因）

## Allowed Scope（实施范围，方案 C）

### C-1（首选注射点）：BotManager.onServerTick 尾段兜底消费

`BotManager.onServerTick`（:345-353）`session.tick(hazard)` **之后**新增兜底段：检查每个 bot 的 `getDeltaMovement()` 水平分量是否有「任务层输入之外的残留 delta」（外部 push/推挤写入、未被实体链消费），若有则显式 `bot.move(MoverType.SELF, delta)` 消费 + 触发碰撞检测（flags 置位语义对齐 Entity.move）：

```java
// 兜底消费：外部推挤残留 delta（任务层输入之外的 push/knockback 写入）
Vec3 pushDelta = bot.getDeltaMovement();
if (pushDelta.horizontalDistanceSqr() > 1.0E-8 && !isTaskLayerInput(bot)) {
    bot.move(MoverType.SELF, new Vec3(pushDelta.x, 0, pushDelta.z)); // 碰撞段触发 flags
    bot.setDeltaMovement(new Vec3(0, pushDelta.y, 0)); // 消费后清水平、保留垂直
}
```

关键：
- `isTaskLayerInput(bot)` 消歧规则：任务层活动（NATIVE_TRAVEL 驱动中）时该 tick 的 travel 输入归任务层，兜底段**跳过**（避免双消费）；任务层空闲/非 travel 时外部 push 残留归兜底段消费
- 兜底段只在「外部残留 delta 存在且任务层未消费」时触发——正常情况下（任务层 travel 期间）不触发，**F4/F5/F2 零回归**
- 消歧规则具体实现细节实施期由主开发按「任务层活动标记」设计（BotSession 现有状态可判断）

### C-2（可选补充）：SoftMovementPrimitive NATIVE_TRAVEL 分支保留外部 delta

若实施后复测显示任务层 travel 期间的外部 push 仍被输入路径覆盖，`NATIVE_TRAVEL` 分支（:48-53，xxa=0/zza=1/travel(0,0,1)）前补「保存外部 delta → travel 后叠加回写」——**防御性冗余，首轮不实施**，视 C-1 复测结果决定。

### 定位复测小包（实施首步，必做）

- **c 复测**：墙置相邻格（原 3 格外改相邻）复测 F3b——确认 tickChain FAIL 是「墙距数值」还是「链失效」
- **b 复测**：Zombie 自身驱动复测 pushEntities 推挤条件是否满足（Zombie 高度/碰撞箱重叠）
- 复测结果可微调 C-1 消歧规则（若 c 成立 → tickChain 断言场景修正，C-1 兜底段仍保留）

## Forbidden Scope（禁止事项）

- **不触碰 FakeConnection**（跳跃异常线独立，本线不修——ab510fd 广播语义不动）
- **不触碰矿链**：MineTask/DropCollectionTask/BotMiner 零改动（R30 HARD_PATH 固定）
- **不触碰 SOFT 实验线 5 个 travel 调用点**（SoftPathProbeTask:141-144 / SoftPathMineTask:167-170 / FollowTask:106-107 / SoftMoveProbeTask:119-120 / settle:82-86——C-2 若实施只改 SoftMovementPrimitive 内部，不改消费方）
- **不触碰 P0 门禁**：SoftPhysicsObservationTest 零改动、skills-manifest/state-machine 零改动、BotPhysicsAssertionFixture 断言**可加不可改**（新增断言场景需监督员批准）
- **不翻案**：R# 既有裁决不动；定案裁决不翻
- 不实现修复之外的扩展（不做「实体链根因修复」——若 C 后 F3b 仍 FAIL 且兜底段确认未触发 → 停止，转 B 定位，交监督员+用户另批）

## Deliverables & Verification

### 服务端（实施验收，M1 套件全绿）

| 断言 | 预期（修复后） |
|---|---|
| F1 | **复测判定**：恢复 true = 伴随现象（主根因闭环）；仍 false = 第二根因单独立包 |
| F3b/tickChain | **转 PASS**（外部 push delta 被兜底消费 → 位移+flags 置位）|
| F4/F5/F2 | **保持 PASS**（任务层 travel 链零回归）|
| R2 观测套件 | 保持 PASS（P0 门禁）|
| R3 既有 suites | 保持 PASS（transfer/inventory）|

### 客户端（阶段 B，需用户 Windows 实测——服务端 PASS ≠ 客户端验收）

| 场景 | 观察点 | 预期日志关键字 | 失败回收条件 |
|---|---|---|---|
| M2 推挤 bot | bot 是否被推动/不再穿玩家 | BOT_PHYSICS_ASSERTION_SUITE f3=true + tickChain 相关 | 服务端 PASS 客户端不可见 → EVIDENCE_CONFLICT 补双端证据 |
| M3 击退 bot | 击退动画/位移可见 | F2 相关（击退保留）+ corr | F1 hurt false 修复后仍 false → 第二根因线，不等同物理线验收 |
| M4 软路径复跑 | 推挤/击退不改变任务轨迹 | soft_phys_* 关键字 | 任务层 travel 被兜底段干扰 → 停止回滚兜底段 |
| M5 跳跃异常回归 | **独立线不修**，仅记录是否复现 | FakeConnection 广播日志 | 本线不触碰跳跃线；若 C 意外影响 → 立即停止报告 |

### 交付

- 实施产出：BotManager 兜底段（C-1，约 15-40 行）+ 消歧规则 + 定位复测小包结果
- 证据报告：`.alice-supervision/research/physics-fix-c-result-20260825.md`（复测小包结果 + 服务端断言全绿 + F1 复测判定 + git status 确认 src 仅 BotManager 改动）
- 客户端证据：M2/M3/M4/M5 由用户 Windows 实测形成 evidence-report（`client-tests/`）
- **compileJava 通过 + 服务端 PASS ≠ 客户端验收**；修复后 commit + HANDOVER 更新 + 审核包

## Stop Conditions

- 定位复测小包显示 c/b 成立（fixture 场景问题）→ 微调断言场景，兜底段仍保留（不翻案）
- 服务端 PASS 与客户端 FAIL 冲突 → EVIDENCE_CONFLICT 停止，补双端证据交监督员裁决
- C 实施后 F3b 仍 FAIL 且兜底段确认未触发 → 停止，转 B 定位（需用户另批准）
- 用户要求调整 → 停止重排

## Relationship to p1-client-sync-fix-v1

- 本线为该线**定案后的修复实施**（bot 物理主根因已定案=实体链推挤未消费）
- FakeConnection 同步线（跳跃异常）**保持独立**：本修复不触碰广播语义；跳跃异常线另行处理（packet 抓包调查）
- 修复后 bot 物理恢复 → P1 线的「客户端可见性」才有意义（F6 阶段 B 可补）

---

**本草案待用户批准；批准前不构成实施授权。**