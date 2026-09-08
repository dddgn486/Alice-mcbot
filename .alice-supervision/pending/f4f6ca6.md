# Alice work-session review packet

- Commit: `f4f6ca6890f1b1dbc166cf32afd7725b8c764897`
- Subject: docs(handover): F1 hurt false fix closure (scheme A)
- Authored: 2026-08-25T22:43:13+08:00
- Handover updated in this commit: `true`
- Active plan ID: `20260825-f1-hurt-false-fix-a-v1`
- Active plan status at handoff: `APPROVED_FOR_IMPLEMENTATION`
- Push status: verify separately after `git push origin master`

## Changed files

```text
docs/HANDOVER.md
```

## Commit summary

```text
 docs/HANDOVER.md | 9 +++++++++
 1 file changed, 9 insertions(+)
```

## Handover delta

```diff
diff --git a/docs/HANDOVER.md b/docs/HANDOVER.md
index e7360cf..a520d1a 100644
--- a/docs/HANDOVER.md
+++ b/docs/HANDOVER.md
@@ -418,3 +418,12 @@ headless 验收：`./gradlew runServer -Dalice.selftest.auto=true`（测完自
 - push 待执行：`git push github master`（fast-forward，无强推）→ push 后 `git status -sb` 应为 ahead 0；push 成功后触发 Windows `updateInstead` 自动同步；最终状态见证据报告 `.alice-supervision/research/physics-fix-c-result-20260825.md` 尾部提交闭环段（commit hash/push 结果/ahead 确认）
 - 下一安全步：Windows 确认同步 → 用户实测 M2/M3/M4（推挤/击退/软路径复跑）与 M5（跳跃异常独立线回归）→ 监督员按证据报告验收客户端矩阵
 
+## F1 hurt false 修复（2026-08-25，方案 A：清除出生保护）
+
+- 根因：`ServerPlayer` 构造函数设置 `spawnInvulnerableTime=60`（出生保护 60 tick）；`ServerPlayer.hurt` 在保护期内拒绝非 bypass 伤害源并返回 false；BotPlayer 继承此机制但 spawn 后未清除保护，导致 F1 断言立即执行时 hurt 返回 false（客户端实测 M3 击退无位移）
+- 方案 A 实施：`BotManager.spawn` :87 行（placeNewPlayer 后、BOTS.put 前）反射清除 `spawnInvulnerableTime` 字段（`Field.setInt(bot, 0)`），使 bot 立即可伤害
+- 服务端验证：F1 generic/Zombie 两源 `hurtOk=true`（health 20.0→19.0→17.5，伤害生效）；F2/F4/F5/C1_CONSUME 保持 PASS；compileJava BUILD SUCCESSFUL
+- 未验证限制：服务端 F1 验收标准（hurtOk=true + health 减少）已满足；M3 击退可见性（位移+动画）需用户 Windows 客户端实测（服务端 PASS ≠ 客户端验收）
+- 边界：不触碰 BotPlayer.hurt()/ServerPlayer.hurt()/FakeConnection/矿链/5 travel 调用点/P0 门禁；不翻案方案 C；F3b/tickChain 仍需独立定位
+- 下一安全步：Windows 确认同步 → 用户实测 M3 击退可见性 → 监督员验收
+
```

## Active Plan Snapshot

```markdown
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

## Evidence State

- PLANNING（本 active plan 落盘）
- 实施后 → IMPLEMENTED（commit + headless PASS）
- 客户端实测后 → SERVER_VERIFIED（M3 PASS）/ CLIENT_TEST_PENDING / EVIDENCE_CONFLICT（M3 FAIL 或 M2 回归）
- 用户确认 → USER_ACCEPTED

---

**本计划已获用户批准（方案 A），监督员授权实施。**
```

## Supervisor checklist

- Does this change preserve the architecture: goal-level decision, deterministic execution, server authority, semantic GUI interfaces?
- Does it preserve the separate boundaries among `HARD_PATH`, experimental `SOFT_SURFACE`, forced road construction, survival monitoring, and future tunnel planning?
- Does it contradict a numbered decision in `docs/HANDOVER.md` or the frozen plan in `docs/PATHING_REFACTOR.md`?
- Are verification evidence, client-test requirements, known limitations, and the next safe step recorded accurately?
- Was `./gradlew compileJava` run and was the committed revision pushed to `origin/master`?
