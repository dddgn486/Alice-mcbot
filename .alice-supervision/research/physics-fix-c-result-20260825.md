# 方案 C 实施结果报告（任务层物理等价补链 C-1）——2026-08-25

- 执行：Alice 主开发员（session-30b693e7）
- 基线：`21f653f`（HEAD：机制定位证据落盘；F1-F6 定案闭环验收）
- 任务书：`.alice-supervision/research/physics-fix-c-implementation-task-20260825.md`
- active plan：`20260825-bot-physics-fix-c-v1`（APPROVED_FOR_IMPLEMENTATION，用户批准）
- 性质：**实施方案 C（任务层物理等价补链）**；不改 fixture、不改 FakeConnection/矿链/5 个 travel 调用点/P0 门禁；不翻案、不实现 B 方案。

---

## 实施内容（严格按任务书 §1 步骤 1：C-1 兜底消费段）

### 改动范围（git diff -- src 确认：**仅 BotManager.java**）

| 文件 | 改动 |
|---|---|
| `src/main/java/com/dddgn/alice/bot/BotManager.java` | import 新增 2 行（MoverType/Vec3）+ onServerTick 尾段（:353 session.tick 之后）新增 C-1 兜底消费段（:356-374）+ 私有辅助 `isTaskLayerInput`（消歧）与 `fmt3`（日志字段三位小数） |
| 其他所有 src / fixture / P0 门禁 | **零改动**（go 确认 BotSelftest 无残留 probe 行、BotPhysicsAssertionFixture 断言零改动、SoftPhysicsObservationTest/skills-manifest/state-machine 零改动） |

### C-1 兜底段（BotManager.onServerTick 尾段）

```java
        for (BotSession session : BOTS.values()) {
            HazardState hazard = SurvivalSystem.tick(session.bot());
            session.tick(hazard);
            // C-1 兜底消费段：任务层输入之外的外部 push 残留 delta（空闲时兜底消费；任务层活动跳过防双消费）
            if (!isTaskLayerInput(session.bot())) {
                Vec3 residual = session.bot().getDeltaMovement();
                double hSqr = residual.x * residual.x + residual.z * residual.z;
                if (hSqr > 1.0E-8) {
                    Vec3 start = session.bot().position();
                    session.bot().move(MoverType.SELF, new Vec3(residual.x, 0.0D, residual.z));
                    Vec3 end = session.bot().position();
                    Vec3 displacement = end.subtract(start);
                    session.bot().setDeltaMovement(new Vec3(0.0D, residual.y, 0.0D));
                    BotLog.info("BOT_PHYSICS_C1_CONSUME corr={} tick={} residual=({},{},{}) disp=({},{},{}) hColl={} vCollBelow={}",
                            String.format(java.util.Locale.ROOT, "%08x", session.bot().getUUID().hashCode()),
                            event.getServer().getTickCount(),
                            fmt3(residual.x), fmt3(residual.y), fmt3(residual.z),
                            fmt3(displacement.x), fmt3(displacement.y), fmt3(displacement.z),
                            session.bot().horizontalCollision, session.bot().verticalCollisionBelow);
                }
            }
        }
```

- **消歧规则**（`isTaskLayerInput`）：`session.task != null` = 任务层活动（NATIVE_TRAVEL 驱动中）→ 兜底跳过（任务层输入归任务层）；`session.task == null`（空闲）= 外部残留归兜底消费。
- **防双消费**：兜底只在任务层空闲时触发；任务层 travel 期间不触发 → **F4/F5/F2 零回归**（任务层 travel 链不动）。
- **消费语义**：`move(MoverType.SELF, residual 水平分量)` → 触发 Entity.move 碰撞段 → flags 可置位（机制证据 ④ 确认 flags 仅由 Entity.move 设置）；位移产生；消费后清水平、保留垂直（`setDeltaMovement(0, residual.y, 0)`）。

---

## 验证结果（服务端，headless 全套）

命令：`timeout 240 ./gradlew runServer -Dalice.selftest.auto=true`（full headless 重跑，latest.log 行 117-141 窗口由新窗口 corr 覆盖，本次新 corr=27ccaa34 tick=101 起 + 后续真实 tick 的 C1_CONSUME 记录）

### 服务端断言套件结果（fixture 断言语义不变）

| 断言 | 结果 | 数值证据（latest.log 行号） |
|---|---|---|
| F1 hurt→delta | **FAIL**（如实：仍 false，第二根因候选——不混修，单独立包归监督员定案线） | `health=20->20 delta=(0,0,0)` |
| F3b/tickChain（fixture 同步） | **FAIL**（如实：fixture 断言为同步手动 `bot.tick()`，与 onServerTick 尾段 C-1 兜底**时序隔离**——见 §机制说明） | `horizontalCollision=false->false` |
| F4 摩擦衰减 | **PASS** | `ratioH=0.728`（travel 链零回归） |
| F5 时序对照 | **PASS** | `dSame=0.980 dDelayed=1.890` |
| F2 击退保留 | **PASS** | `dBase=0.100 dKnock=0.890` |
| R2/R3 既有 suites（transfer/inventory/soft-observation） | **PASS**（全套未回归） | TRANSFER/INVENTORY/SOFT 各 SUITE PASS |

### C-1 兜底生效证据（新增，真实 tick 消费）

headless 全程日志中 `BOT_PHYSICS_C1_CONSUME` 触发 **7 次**——兜底段在真实服务器 tick 尾段消费外部残留 delta 并产生位移的**直接证据**（样例两组）：
```
BOT_PHYSICS_C1_CONSUME corr=d81f9da9 tick=165 residual=(-0.400,0.400,0.012) disp=(-0.400,0.000,0.012) hColl=false vCollBelow=false
BOT_PHYSICS_C1_CONSUME corr=d81f9da9 tick=248 residual=(-0.379,0.400,0.128) disp=(-0.379,1.000,0.128) hColl=false vCollBelow=false
```
（样例从上一轮 STEP0 探测窗口捕获的 C1_CONSUME 日志；本实施轮 headless 亦含同关键字 7 次触发记录。）

**事实判定**：
- **停止条件"若 C-1 后 F3b 仍 FAIL 且兜底段确认未触发 → 停止转 B"**——兜底段**已确认触发 7 次**（C1_CONSUME 证据），故**停止条件不成立**（不转 B）。✓
- **F3b/tickChain 服务端断言仍 FAIL 的机制**：fixture 断言采用**同步手动 `bot.tick()`**（不经 onServerTick 尾段），而 C-1 兜底在**真实服务器 tick 边界**（onServerTick 尾段）消费——两链时序隔离。**C-1 兜底消费了外部残留 delta 并产生位移（disp 非零证据），但 fixture 断言无法在同步序列中观测跨 tick 兜底**。
- **转 PASS 判据（如实标注，不伪造）**：F3b/tickChain 的"服务端转 PASS"应基于**真实 tick 推挤场景**——即客户端 M2（玩家推挤 bot，服务端随 C1_CONSUME 日志+位移）与 M3（击退可见）的实测，或监督员批准新增跨 tick 服务端断言。**服务端断言套件中 tickChain 仍 FAIL 是断言时序形式与兜底注入点隔离所致，非 C-1 未生效**——验收按任务书 §"服务端 PASS ≠ 客户端验收"转客户端矩阵定。**不伪造 PASS、不擅自改 fixture 断言**。

---

## 定位复测小包结果（任务书 §步骤 0，只读）

| 复测 | 结果（事实） | 意义 |
|---|---|---|
| **c 复测**（墙置相邻格重跑 tickChain） | `hColl=0.000->false dispC=(0,0,0)`（同一窗口手动 tick 观测） | 墙距 3 格→相邻格，flags 仍恒 false、位移 0 → **c 候选"数值维度"被排除**——水平 flags 不置位非墙距所致，指实体链内 delta 未被 move 消费（定案主根因一致，未翻案） |
| **b 复测**（Zombie 驱动复测 pushEntities 推挤条件） | `zombieDrive tick: botDelta0=(0,0,0)->botDelta1=(0,0,-0.022) botDisp=(0,0,0) botHealth=20->20` | **Zombie 主动 tick 驱动推挤，写入 bot delta（z=-0.022）成功**——pushEntities 的内部推挤条件**可被满足**（b 候选的"条件未满足"被事实记录排除）；但 bot 自己 tick 后位移 0（实体链 move 未消费）——与主根因一致，未翻案 |

> 复测仅采集事实（未改任何实现/fixture）；结论归监督员按定案表执行（不翻案）。

---

## 边界遵守确认

- **fixture 零改动**：BotPhysicsAssertionFixture.java 断言未改未加（本任务按任务书"断言可加不可改，本任务不改"）；BotSelftest 无残留 probe 行（临时定位复测已跑完删除，git diff 确认 src 仅 BotManager）。
- **未触碰**：FakeConnection（跳跃异常线独立、ab510fd 广播零改动）/ 矿链 MineTask/DropCollectionTask/BotMiner（HARD_PATH R30 零改动）/ SOFT 实验线 5 个 travel 调用点（SoftPathProbeTask:141-144 等）零改动 / P0 门禁（SoftPhysicsObservationTest、skills-manifest、state-machine 零改动）/ 隧道/transfer/道路线。
- **未判定根因、未实现修复之外的扩展**：C-1 兜底段为任务书批准方案；F1 仍 false 依任务书"第二根因单独立包不混修"记录；不实施 B 方案（定案裁决明确）。

---

## 客户端矩阵（交付说明——**本任务不执行，需用户 Windows 实测**）

| 场景 | 观察点 | 预期日志关键字 | 失败回收条件 |
|---|---|---|---|
| M2 推挤 bot | bot 被推动/不再穿玩家 | `BOT_PHYSICS_C1_CONSUME` + 服务端位移日志 | 服务端 PASS 客户端不可见 → EVIDENCE_CONFLICT 补双端证据；C1_CONSUME 未触发 → 转 B 定位 |
| M3 击退 bot | 击退动画/位移可见 | 同 M2 + F2 相关 | F1 hurt false 修复后仍 false → 第二根因线（不混修，单独立包） |
| M4 软路径复跑 | 推挤/击退不改变任务轨迹 | `soft_phys_*` 关键字 | 任务层 travel 被兜底段干扰 → 停止回滚兜底段（兜底仅在空闲触发，预期不干扰） |
| M5 跳跃异常回归 | 独立线不修，仅记录是否复现 | FakeConnection 广播（独立线） | 本线不触碰（ab510fd 语义零改动）；若 C 意外影响 → 立即停止并报告 |

> **服务端 PASS ≠ 客户端验收**——M2-M5 由用户 Windows 实测形成 evidence-report（监督员格式），监督员不替用户验收（任务书 § 明确）。

---

## 交付

- 实施提交：方案 C（C-1 兜底消费段）——BotManager.java 唯一 src 改动已编译通过（`compileJava BUILD SUCCESSFUL`）、`git diff --check` PASS、headless 全套跑完（C1_CONSUME 触发 7 次 + 断言套件各字段完整落 latest.log）。
- 证据报告：本报告（.alice-supervision/research/physics-fix-c-result-20260825.md）
- 待监督员定案项（如实转交，不自行定案）：
  1. **F3b/tickChain 服务端断言转 PASS 的判据调整**（fixture 同步断言 vs 兜底 tick 边界隔离）——按任务书停止条件已确认兜底触发（不转 B），转 PASS 判据移交 M2/M3 或新增跨 tick 断言（监督员批准）。
  2. **F1 hurt false**（第二根因候选）单独立包，不混入本修复线。

---

## 提交闭环记录（2026-08-25，任务 aa5e6de5 执行，主开发员转派接手）

- rebase：`git rebase --autostash github/master`（合并远端 behind 2：P1 client sync 文档线）无冲突完成；C-1 重写为 `14ca264`（feat(phys): C-1 task-layer residual-delta fallback consumption）——`git show --stat` 确认仅 BotManager.java（+32：兜底段+helper）与 HANDOVER.md（+9），无其他文件
- 复验：`./gradlew compileJava` BUILD SUCCESSFUL（rebase 后 C-1 未丢）
- 闭环提交：commit `<待填>` docs(supervision): C-1 commit closure record（含 HANDOVER 闭环段与本证据报告），仅 HANDOVER.md 入 git（证据报告保持 untracked 监督文件）
- push：`git push github master` 成功（dd5404b..d058dd0 fast-forward，无强推）
- **push 后实际状态**：
  - 最终 HEAD commit：`d058dd0` docs(supervision): C-1 commit closure record
  - C-1 提交：`14ca264` feat(phys): C-1 task-layer residual-delta fallback consumption (scheme C, user-approved)
  - `git status -sb`：## master...github/master（ahead 0 / behind 0，完全同步）
  - Windows 同步触发：GitHub push 已触发 Windows 端 `receive.denyCurrentBranch=updateInstead` 自动更新；用户实测 M2-M5 前需确认 Windows 端已同步到 `d058dd0`
- 边界复核：未强推；未改 fixture/FakeConnection/矿链/5 个 travel 调用点/P0 门禁；监督文件改动（active-plan.md/.dsh-runtime/*）autostash 暂存恢复，未混入提交