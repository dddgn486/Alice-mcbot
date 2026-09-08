# 实施任务：bot 物理修复方案 C（任务层物理等价补链）——用户已批准

- 派发：Alice 架构监督员
- 执行：Alice 主开发员（session-30b693e7）
- 日期：2026-08-25
- Alice 基线：`21f653f`（HEAD：机制定位证据报告落盘；F1-F6 断言+NPE 佐证+定案裁决全链路已验收闭环）
- 用户批准：✅（2026-08-25 明确批准方案 C 实施）
- 依据：
  - active plan `2026-08-25-bot-physics-fix-c-v1`（APPROVED_FOR_IMPLEMENTATION，.alice-supervision/active-plan.md）
  - 定案裁决 `.alice-supervision/research/f1f6-bot-physics-verdict-20260825.md`（主根因=实体 tick 链推挤/碰撞未被 bot 有效消费）
  - 修复线规划 `.alice-supervision/research/physics-fix-plan-20260825.md`（三方案对比，推荐 C）
  - 机制定位证据 `.alice-supervision/research/physics-chain-mechanism-evidence-20260825.md`（四清单：aiStep 分支/驱动链三线/flags 设置点/F3b 复现定位）

## 背景（定案摘要，不翻案）

**主根因**：实体 tick 链（aiStep→pushEntities→move 碰撞段）的推挤/碰撞响应未被 bot 有效消费——push 写入 delta=0.5 但 tick 后位移 0、碰撞 flags 恒 false（F3b/tickChain FAIL）。**已排除** B2 时序覆盖（F5 两时序均有位移）、B3 NPE 吞错（stderr 0 字节）。**已确认**：任务层 travel 链正常（F4/F5/F2 PASS，BotManager.onServerTick 直接 travel 不经 bot.tick()）；碰撞 flags 仅由 Entity.move 碰撞段设置。

**修复方向（用户已批准）**：方案 C——任务层物理等价补链：任务层 travel 链之外补「外部 push delta 兜底消费」，使推挤/击退在任务层可位移、碰撞 flags 可置位。**不触碰实体链根因（B 方案不实施）**。

## 实施范围（严格按 active plan Allowed Scope）

### 第 0 步（必做）：定位复测小包

C 实施**之前**先做两个只读复测（不改任何实现/fixture，只重跑断言场景变体）：

| 复测 | 内容 | 目的 |
|---|---|---|
| **c 复测** | 墙置相邻格（原 F3b 场景墙在 3 格外，改 1 格外）重跑 tickChain 断言 | 区分 tickChain FAIL 是「墙距数值维度」还是「链失效」（若相邻格 flags 转 true → c 成立，链实际可达，tickChain FAIL 是场景数值问题）|
| **b 复测** | Zombie 驱动复测 pushEntities 推挤条件（Zombie 放置高度/碰撞箱与 bot 重叠检查） | 区分 b 候选（pushEntities 内部条件未满足）是否成立 |

复测结果记录到实施证据报告（不判定根因，只记录事实——定案不翻案，复测只影响 C-1 消歧规则微调）。

### 第 1 步（核心）：C-1 兜底消费段（BotManager.onServerTick 尾段）

`BotManager.onServerTick`（:345-353）`session.tick(hazard)` **之后**新增兜底段：检查每个 bot 的 `getDeltaMovement()` 水平分量是否有「任务层输入之外的残留 delta」（外部 push/推挤写入、未被实体链消费），若有则显式消费 + 触发碰撞检测：

```java
// 兜底消费：外部推挤残留 delta（任务层输入之外的 push/knockback 写入）
Vec3 pushDelta = bot.getDeltaMovement();
if (pushDelta.horizontalDistanceSqr() > 1.0E-8 && !isTaskLayerInput(bot)) {
    bot.move(MoverType.SELF, new Vec3(pushDelta.x, 0, pushDelta.z)); // 碰撞段触发 flags
    bot.setDeltaMovement(new Vec3(0, pushDelta.y, 0)); // 消费后清水平、保留垂直
}
```

关键要求：
- `isTaskLayerInput(bot)` 消歧规则：任务层活动（NATIVE_TRAVEL 驱动中）时该 tick 的 travel 输入归任务层，兜底段**跳过**（避免双消费）；任务层空闲/非 travel 时外部 push 残留归兜底段消费
- 兜底段只在「外部残留 delta 存在且任务层未消费」时触发——正常情况下（任务层 travel 期间）不触发，**F4/F5/F2 零回归**
- 消歧规则具体实现按「任务层活动标记」设计（BotSession 现有状态可判断）——实施前若需确认 BotSession 状态 API，可读源码确认，不再 request_input 问监督员（属实施细节）

### 第 1.5 步（可选，先不做）：C-2 NATIVE_TRAVEL 分支保留外部 delta

若第 1 步实施后复测显示任务层 travel 期间外部 push 仍被输入路径覆盖，`NATIVE_TRAVEL` 分支（:48-53）前补「保存外部 delta → travel 后叠加回写」——**仅视 C-1 复测结果决定，首轮不实施**。

## 禁止事项（硬性，违反即任务失败）

- **不触碰 FakeConnection**（跳跃异常线独立——ab510fd 广播语义零改动）
- **不触碰矿链**：MineTask/DropCollectionTask/BotMiner 零改动（R30 HARD_PATH 固定）
- **不触碰 SOFT 实验线 5 个 travel 调用点**：SoftPathProbeTask:141-144 / SoftPathMineTask:167-170 / FollowTask:106-107 / SoftMoveProbeTask:119-120 / settle:82-86（C-2 若实施只改 SoftMovementPrimitive 内部，不改消费方）
- **不触碰 P0 门禁**：SoftPhysicsObservationTest 零改动、skills-manifest/state-machine 零改动、BotPhysicsAssertionFixture **断言可加不可改**（新增断言场景需监督员批准——本任务不改 fixture 断言）
- **不翻案**：R# 既有裁决不动；定案裁决不翻
- 不实现修复之外的扩展（不做 B 方案实体链根因修复——若 C-1 后 F3b 仍 FAIL 且兜底段确认未触发 → 停止，转 B 定位，交监督员+用户另批）

## 验证与产出

### 服务端（实施验收，M1 套件全绿）

| 断言 | 预期（修复后） |
|---|---|
| F1 | **复测判定**：恢复 true = 伴随现象（主根因闭环）；仍 false = 第二根因单独立包（不混修）|
| F3b/tickChain | **转 PASS**（外部 push delta 被兜底消费 → 位移+flags 置位）|
| F4/F5/F2 | **保持 PASS**（任务层 travel 链零回归）|
| R2 观测套件 | 保持 PASS（P0 门禁）|
| R3 既有 suites | 保持 PASS（transfer/inventory）|

验证命令：`./gradlew compileJava` 通过 + headless 运行 `-Dalice.selftest.auto=true`（或等效 selftest 入口）产出含 `BOT_PHYSICS_ASSERTION_SUITE` 的日志。

### 证据报告（必写）

报告写到 `.alice-supervision/research/physics-fix-c-result-20260825.md`：
- 定位复测小包结果（c 复测/b 复测事实记录）
- C-1 实施 diff（BotManager 改动行）
- 服务端断言结果（F1 复测判定 + F3b/tickChain 转 PASS 证据 + F4/F5/F2 保持 + latest.log 行号）
- git status 确认 src 仅 BotManager 改动（其余零改动）
- 客户端矩阵交付说明（M2-M5 由用户 Windows 实测，本报告不标客户端验收）

### 客户端矩阵（阶段 B——需用户 Windows 实测，本任务不执行）

| 场景 | 观察点 | 预期日志关键字 | 失败回收条件 |
|---|---|---|---|
| M2 推挤 bot | bot 是否被推动/不再穿玩家 | BOT_PHYSICS_ASSERTION_SUITE f3b=true + tickChain 相关 | 服务端 PASS 客户端不可见 → EVIDENCE_CONFLICT 补双端证据 |
| M3 击退 bot | 击退动画/位移可见 | F2 相关（击退保留）+ corr | F1 hurt false 修复后仍 false → 第二根因线，不等同物理线验收 |
| M4 软路径复跑 | 推挤/击退不改变任务轨迹 | soft_phys_* 关键字 | 任务层 travel 被兜底段干扰 → 停止回滚兜底段 |
| M5 跳跃异常回归 | **独立线不修**，仅记录是否复现 | FakeConnection 广播日志 | 本线不触碰跳跃线；若 C 意外影响 → 立即停止报告 |

## 完成与验收

- 完成后 commit（feat/fix 消息说明方案 C 实施）+ 更新 HANDOVER（修复线章节）+ report_task 附报告路径
- 验收标准：服务端断言全绿（F1 复测判定 + F3b/tickChain PASS + F4/F5/F2 不回归）；证据报告落盘完整；src 仅 BotManager 改动；未触碰所有禁止项；客户端矩阵已交付说明（不标客户端验收）

**服务端 PASS ≠ 客户端验收**——M2-M5 由用户 Windows 实测形成 evidence-report，监督员不替用户验收。