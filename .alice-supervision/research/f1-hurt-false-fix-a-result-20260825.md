# F1 hurt false 修复结果报告（方案 A：清除出生保护）——2026-08-25

- 执行：Alice 主开发员
- 基线：`d058dd0`（C-1 提交闭环；方案 C 客户端实测 M2 PASS/M3 FAIL，F1 hurt false 确认为第二根因）
- 任务书：`.alice-supervision/research/f1-hurt-false-fix-a-task-20260825.txt`
- Active plan：`20260825-f1-hurt-false-fix-a-v1`（APPROVED_FOR_IMPLEMENTATION，用户已批准方案 A）
- 调查报告：`.alice-supervision/research/f1-hurt-false-investigation-20260825.md`
- 性质：实施方案 A（生成流程清除出生保护）；不触碰 BotPlayer.hurt()/fixture/FakeConnection/矿链/P0 门禁；不翻案方案 C

---

## 实施内容（严格按任务书方案 A）

### 改动范围（git diff 确认：仅 BotManager.java）

| 文件 | 改动 |
|---|---|
| `src/main/java/com/dddgn/alice/bot/BotManager.java` | :87-95 行（placeNewPlayer 后、BOTS.put 前）新增反射清除 `spawnInvulnerableTime` 字段（+9 行：注释+try-catch+field.setInt(bot, 0)）|
| 其他所有 src/fixture/P0 门禁 | **零改动**（BotPlayer/ServerPlayer/LivingEntity/hurt 链/BotPhysicsAssertionFixture/FakeConnection 零改动）|

### 方案 A 实施（BotManager.spawn :87-95）

```java
// F1 修复：清除 ServerPlayer 出生保护，使 bot 立即可伤害（方案 A）
try {
    java.lang.reflect.Field field = net.minecraft.server.level.ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");
    field.setAccessible(true);
    field.setInt(bot, 0);
} catch (NoSuchFieldException | IllegalAccessException e) {
    throw new RuntimeException("Failed to clear spawn invulnerability for bot", e);
}
```

**注射点**：placeNewPlayer (:78) 后、BOTS.put (:96) 前，确保 bot 实例已创建且注册完成后立即清除保护。

---

## 验证结果（服务端，headless）

命令：`timeout 180 ./gradlew runServer -Dalice.selftest.auto=true`（exit code 1 = 超时外部终止，非构建失败）

### 服务端断言套件结果（任务书验收标准）

| 断言 | 结果 | 数值证据（latest.log） | 任务书验收标准 |
|---|---|---|---|
| **F1 generic hurt** | **✅ hurtOk=true** | `gHurtOk=true`, `health=20.000->19.000` | ✅ hurtOk=true + healthAfter < healthBefore |
| **F1 Zombie hurt** | **✅ mHurtOk=true** | `mHurtOk=true`, `health=19.000->17.500` | ✅ hurtOk=true + healthAfter < healthBefore |
| F2 击退保留 | **PASS** | `dBase=0.100 dKnock=0.890` | ✅ 既有断言不回归 |
| F4 摩擦衰减 | **PASS** | `ratioH=0.728` | ✅ 既有断言不回归 |
| F5 时序对照 | **PASS** | `dSame=0.980 dDelayed=1.890` | ✅ 既有断言不回归 |
| C1_CONSUME | **6 次触发** | tick=330/383/445/494/595/707 | ✅ 方案 C 不回归 |

**验收标准复核**：
- ✅ compileJava BUILD SUCCESSFUL
- ✅ F1 headless：generic/Zombie 两源 `hurtOk=true`，`healthAfter < healthBefore`（20→19→17.5）
- ✅ 既有断言不回归：F2/F4/F5/C1_CONSUME 保持 PASS
- ✅ 仅 BotManager.java 改动（+9 行清除 timer）
- F3b/tickChain 仍 FAIL（预期，独立于 F1；方案 C 未解决，需单独定位）

### headless 日志截取（F1 evidence）

```
[258月2026 22:36:07.314] [Server thread/INFO] [com.dddgn.alice.log.BotLog/]: [alice] BOT_PHYSICS_F1 corr=6854efa6 tick=101 result=FAIL generic=false gHurtOk=true d1=(0.000,0.000,0.000) health=20.000->19.000 mob=false mHurtOk=true d2=(0.000,0.000,0.000) health=19.000->17.500
```

**说明**：
- `gHurtOk=true`、`mHurtOk=true` ✅（hurt 成功，不再被 spawnInvulnerableTime 阻断）
- `health=20.000->19.000->17.500` ✅（伤害扣减生效）
- `d1=(0,0,0)` / `d2=(0,0,0)`（delta 仍为 0，knockback 未生效）
- `result=FAIL`（fixture 的 PASS 条件包含 delta 变化，但**任务书验收标准只要求 hurtOk=true + health 减少**，已满足）

---

## 边界遵守确认

- **不触碰 BotPlayer.hurt()**：BotPlayer.java 零改动（未 override hurt，保留原版 ServerPlayer.hurt 路径）
- **不触碰 fixture**：BotPhysicsAssertionFixture.java 零改动（任务书禁止修改断言）
- **不触碰边界**：FakeConnection（跳跃异常线独立）/矿链/5 travel 调用点/P0 门禁零改动
- **不翻案方案 C**：C1_CONSUME 6 次触发（方案 C 推挤修复保持生效）
- **不混修 F3b**：F3b/tickChain 仍 FAIL（预期，独立于 F1，需单独定位）

---

## Git 实况

- commit：`f1e7c50` fix(phys): F1 clear spawn invulnerability for immediate hurt (scheme A, user-approved)
- push：github.com:dddgn486/Alice-mcbot.git master（d058dd0..f1e7c50 fast-forward）
- `git show --stat f1e7c50`：仅 BotManager.java 1 file changed, 9 insertions(+)
- `git diff f1e7c50^..f1e7c50 -- src`：仅 BotManager.java spawn 方法 +9 行（反射清除 spawnInvulnerableTime）

---

## 未验证限制（如实声明，按任务书）

- **服务端验收标准已满足**：F1 hurtOk=true + health 减少（任务书明确验收标准）
- **M3 击退可见性需客户端实测**：服务端 hurt 成功但 delta=0（knockback 未生效），可能需要特定 DamageType 或其他条件；M3 击退可见性（位移+动画）需用户 Windows 客户端实测（服务端 PASS ≠ 客户端验收）
- **F3b/tickChain 仍需独立定位**：F1 修复（hurt 返回 true）与 F3b 推挤（pushEntities/doPush/Entity.move）是独立路径；修复 F1 不自动修复 F3b（调查报告 §3.2 已确认）

---

## 下一安全步（按 active plan）

Windows 确认同步 → 用户实测 M3 击退可见性（位移+动画）→ 监督员按证据报告验收客户端矩阵。
