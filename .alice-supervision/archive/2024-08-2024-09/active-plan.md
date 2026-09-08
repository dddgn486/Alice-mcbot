# Alice Active Work Plan

- Plan ID: `20260825-f1-hurt-false-fix-a-v1`
- Status: APPROVED_FOR_IMPLEMENTATION
- Baseline: `d058dd0`（C-1 提交闭环；方案 C 客户端实测 M2 PASS/M3 FAIL，F1 hurt false 确认为第二根因）
- User approval: explicit approval received (2026-08-25); 批准方案 A（生成流程清除出生保护）实施
- Investigation report (deep research, supervisor accepted): `.alice-supervision/research/f1-hurt-false-investigation-20260825.md`
- Previous plan record: `20260825-bot-physics-fix-c-v1`（已完成：C-1 推挤修复 M2 PASS；F1 hurt false 单独立包）

## Objective

修复 F1 hurt false（第二根因）：`BotPlayer.hurt()` 返回 false 导致击退失效（M3 FAIL）。采用方案 A：在 `BotManager.spawn` 生成流程中清除/重置 `spawnInvulnerableTime`，使 bot 立即可伤害。

## Problem Statement

**现象**：方案 C 客户端实测 M2 推挤 PASS，但 M3 击退无位移 FAIL。

**根因**（调查报告已确认）：
- `ServerPlayer.hurt` 在 `spawnInvulnerableTime > 0` 时拒绝非 bypass 伤害源（generic/Zombie 均非 bypass），返回 false
- `ServerPlayer` 构造函数设 `spawnInvulnerableTime=60`（出生保护 60 tick）
- `BotPlayer` 继承此机制；`BotManager.spawn` 未清除保护，导致 bot spawn 后 60 tick 内无法被击退
- F1 断言在 spawn 后立即执行（未等待 60 tick），触发 `hurtOk=false`

**边界**：
- F1 hurt false 与 F3b pushEntities 无关（推挤不调用 `hurt()`）；修复 F1 不自动修复 F3b
- F1 是第二根因，独立于方案 C（实体链推挤未消费）；按定案裁决要求单独立包，不混修

## Research Decision

深度调查报告 `.alice-supervision/research/f1-hurt-false-investigation-20260825.md`（监督员已验收）：
- 根因：`ServerPlayer.hurt` :2366-2368 检查 `this.spawnInvulnerableTime > 0 && !damageSource.is(DamageTypeTags.BYPASSES_INVULNERABILITY)`，构造时设为 60
- BotPlayer 差异：`BotManager.spawn` 未清除保护（ServerPlayer 由 `PlayerList.placeNewPlayer` + connection 握手隐式等待，bot 无握手）
- 推荐方案 A（用户已批准）：BotManager 生成流程清除 `spawnInvulnerableTime`（通过可用 API 或窄反射单字段）
- 证据级别 A：Forge 1.20.1-47.4.10 mapped bytecode + Alice 源码行号可追溯

## Allowed Scope（方案 A 实施范围）

| 层 | 改动范围 |
|---|---|
| **生成流程** | `BotManager.spawn` :66-88 在 `placeNewPlayer` 后清除 `spawnInvulnerableTime`（注射点：spawn 尾段，bot 实例已创建后）|
| **注入方式** | 优先顺序：① 若存在公共 setter/方法（如 `setInvulnerable(false)` 副作用清零 timer）则用 API；② 否则用反射 `Field.setInt(bot, "spawnInvulnerableTime", 0)`（窄反射单字段，非复制完整 hurt 链）；③ 禁止 BotPlayer override `hurt()` 复制 ServerPlayer 逻辑（回归风险高）|
| **验证** | ① compileJava BUILD SUCCESSFUL；② headless 运行 F1 断言（generic/Zombie 两源 `hurtOk` 预期 true）；③ 客户端实测 M3 击退可见性（预期位移 + 击退动画）|
| **边界** | 不改 `BotPlayer.hurt()`/`ServerPlayer.hurt()`/`LivingEntity`；不触碰 `FakeConnection`（跳跃异常线独立）/矿链/5 个 travel 调用点/P0 门禁；不翻案方案 C；不混修 F3b|

## Forbidden Scope

- **不复制 ServerPlayer.hurt 全逻辑**：禁止在 BotPlayer override hurt() 后把 ServerPlayer.hurt 的 50 行全复制过来（Forge hooks、PVP、abilities、armor、health、effects 均需保留原版路径）
- **不反射私有 hurt 内部状态**：禁止反射 `lastHurt`/`lastDamage`/`hurtDuration` 等 hurt 内部字段（只清除 spawn timer）
- **不触碰其他伤害源**：方案 A 只清除出生保护；PVP check/abilities/armor/health/dying 状态等其他 hurt 前置检查保持原版
- **不改 fixture 为等待 60 tick**：方案 C（验证）不作为生产修复；fixture 保持 spawn 后立即断言（修复后应立即可伤害）
- **不触碰 F3b**：F3b pushEntities 是独立问题（doPush/move 消费），修复 F1 不保证 F3b 恢复
- **不改 FakeConnection/矿链/5 travel 调用点/P0 门禁**：R31 边界不可触碰

## Success Criteria（分阶段验收）

### 阶段 A1：本地 headless 验证

| 项 | 预期 | 验证方式 |
|---|---|---|
| compileJava | BUILD SUCCESSFUL | `./gradlew compileJava` |
| F1 generic hurt | `hurtOk=true`, `healthBefore=20.0`, `healthAfter=18.0`, `deltaBefore=0.0`, `deltaAfter=-2.0` | headless 运行 F1 断言，`latest.log` 搜 `BOT_PHYSICS_ASSERTION_FIXTURE` + `F1_HURT` |
| F1 Zombie hurt | 同上（Zombie 伤害值不同） | 同上 |
| 既有断言不回归 | F2/F4/F5/R2/R3/C1_CONSUME 保持 PASS | headless 全套跑完，无 FAIL |
| 边界 | 仅 `BotManager.java` 改动（+3~5 行清除 timer）；未改 BotPlayer/ServerPlayer/hurt 链/fixture | `git diff --stat` |

### 阶段 A2：客户端实测（用户 Windows）

| 项 | 预期 | 验证方式 |
|---|---|---|
| M3 击退可见性 | bot 被攻击后有击退位移 + 击退动画 | 用户在 Windows 客户端攻击 bot，观察位移/动画 |
| M2 推挤复测 | 推挤仍正常（方案 C 不回归）| 用户推挤 bot，确认仍可推动 |
| 服务端日志 | `BOT_PHYSICS_C1_CONSUME` 仍触发（方案 C 兜底段不丢）| 用户操作后检查 `logs/latest.log` |

### 停止条件

- F1 headless 仍 FAIL（timer 未清零）→ 停止，检查注射点是否生效
- compileJava FAIL（反射 API 不存在/签名错误）→ 停止，调整反射路径或改用其他方案
- 既有断言回归（F2/F4/F5 FAIL）→ EVIDENCE_CONFLICT，停止并报监督员
- 客户端 M3 仍 FAIL（击退无位移）→ 停止，补充根因（可能 timer 清零但其他 hurt 检查仍拦截）
- 用户要求调整 → 停止重排

## Implementation Steps（主开发员执行）

1. **读调查报告**：`.alice-supervision/research/f1-hurt-false-investigation-20260825.md` §6 方案 A 段落
2. **改动 BotManager.spawn**：
   - 定位 `:88` 行（`placeNewPlayer` 后、`return bot` 前）
   - 插入清零逻辑（优先顺序）：
     - 方式 ①（API）：若 `bot.setInvulnerable(false)` 或类似方法副作用清零 `spawnInvulnerableTime`，调用之
     - 方式 ②（反射）：`Field field = ServerPlayer.class.getDeclaredField("spawnInvulnerableTime"); field.setAccessible(true); field.setInt(bot, 0);`（需 try-catch，注释注明"清除 ServerPlayer 出生保护，使 bot 立即可伤害"）
   - 禁止在 BotPlayer override hurt()
3. **compileJava 验证**：`./gradlew compileJava` BUILD SUCCESSFUL
4. **headless 验证**：
   - `./gradlew runServer -Dalice.selftest.auto=true`
   - 检查 `run/logs/latest.log`：F1 generic/Zombie 两源 `hurtOk=true`，`healthAfter < healthBefore`
   - 既有断言 F2/F4/F5/R2/R3/C1_CONSUME 保持 PASS
5. **commit**：仅 `BotManager.java`，commit message `fix(phys): F1 clear spawn invulnerability for immediate hurt (scheme A, user-approved)`
6. **push**：`git push origin master`
7. **更新 HANDOVER**：追加 F1 修复段落（根因/方案 A/headless 验证/M3 待客户端实测）
8. **落盘证据**：`.alice-supervision/research/f1-hurt-false-fix-a-result-20260825.md`（headless 日志截取、diff、commit hash）
9. **report_task**：附任务书路径

## Client Test Matrix（用户实测，监督员整理）

执行前置：Windows 同步到最新 commit（F1 修复 commit）

| 编号 | 场景 | 操作 | 观察点 | 预期 | 失败回收 |
|---|---|---|---|---|---|
| M3 | 击退可见性 | 玩家攻击 bot 一次 | bot 位移、击退动画 | 有位移 + 动画；服务端日志 `BOT_PHYSICS_C1_CONSUME` + 击退分量 | 无位移 = F1 修复失败，停止并补充根因 |
| M2 | 推挤复测 | 玩家持续接触 bot | bot 被推动 | 仍可推动（方案 C 不回归）| 不可推 = 方案 C 回归，EVIDENCE_CONFLICT |

## Relationship to Other Lines

- **方案 C（C-1 推挤修复）**：已完成并闭环（M2 PASS），本线为第二根因独立修复，不混修
- **F3b pushEntities**：独立问题（doPush/move 消费），F1 修复不保证 F3b 恢复；若 M3 PASS 后 F3b 仍 FAIL，需单独定案
- **跳跃异常线（M5）**：FakeConnection 不触碰，本线不影响

## Approved Diagnostic Addendum（2026-08-26，用户批准）

- 诊断 ID：`20260826-m3-m5-packet-evidence-v1`
- 用户批准范围：建立最小 packet 级诊断证据包，调查 M3 击退服务端/客户端分层与 M5 跳跃异常的 `FakeConnection` 广播关联。
- 允许：只读源码/提交对照、日志与视频证据整理、经批准的诊断观测输出（packet class/entityId/recipient/tick/字段/重复计数）。
- 禁止：修改 `FakeConnection`、`BotManager`、`BotPlayer`、fixture、active plan 语义、客户端验收记录；不直接修复 M3/M5；不把 M3 与 M5 混修。
- 验收：报告必须区分事实/推断/未证实，完成 `65f4863 → ab510fd → f4f6ca6` 对照，给出 M3/M5 双端矩阵、停止条件与下一修复候选；服务端 PASS 不替代客户端验收。
- 证据状态：`DIAGNOSTIC_APPROVED`；报告完成后由监督员单独审核，修复仍需用户另行批准。

## Approved Diagnostic Observation Work Package（2026-08-26，用户批准）

- Work package ID：`20260826-m3-m5-packet-observer-v1`
- 用户批准：执行 D0-D4 诊断观测，采集 M3/M5 packet/entityId/recipient/tick/字段与服务端状态证据。
- 允许：独立、可禁用、可回滚的诊断观测器或既有诊断 seam；只记录，不改变 packet 筛选、recipient 集合、发送顺序、hurt、C-1、tracking 或客户端语义。
- 禁止：任何生产修复、FakeConnection 广播策略修改、BotManager/BotPlayer/fixture 语义修改、客户端验收状态修改、M3/M5 混修。
- 验收：compileJava；诊断输出包含 D1/D2 可得字段与 D3/D4 服务端状态；既有 F1/F2/F4/F5/R2/R3/C1 行为不回归；证据报告说明不可获得字段与停止条件；提交/push 后由监督员审核，Windows 只执行对应诊断矩阵。

## Evidence State

- 当前 F1 修复服务端：`SERVER_VERIFIED`（F1 hurtOk=true + health 减少）
- 当前 M3/M5 客户端：`CLIENT_TEST_PENDING`
- 诊断阶段：`DIAGNOSTIC_APPROVED`
- 用户确认客户端效果后：`USER_ACCEPTED`

---

**本计划已获用户批准（方案 A），监督员授权实施。**
