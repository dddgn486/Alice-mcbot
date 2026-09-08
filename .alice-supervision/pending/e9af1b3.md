# Alice work-session review packet

- Commit: `e9af1b3c9b4890127f1eb3dcde2c9ceba8f0d1e7`
- Subject: docs(supervision): NPE evidence collection result (read-only, no root-cause)
- Authored: 2026-08-25T15:46:10+08:00
- Handover updated in this commit: `false`
- Active plan ID: `20260825-f1f6-bot-physics-assertions-v1`
- Active plan status at handoff: `APPROVED_FOR_IMPLEMENTATION`
- Push status: verify separately after `git push origin master`

## Changed files

```text
.alice-supervision/research/npe-evidence-result-20260825.md
```

## Commit summary

```text
 .../research/npe-evidence-result-20260825.md       | 67 ++++++++++++++++++++++
 1 file changed, 67 insertions(+)
```

## Handover delta

No handover change was included; this closure was explicitly overridden.

## Active Plan Snapshot

```markdown
# Alice Active Work Plan

- Plan ID: `20260825-f1f6-bot-physics-assertions-v1`
- Status: APPROVED_FOR_IMPLEMENTATION
- Baseline: `6c2b461`（HEAD，全量功能审查批 1-4 已完成并验收）
- User approval: explicit approval received (2026-08-25); 批准实施 F1-F6 服务端物理断言（定案前置）
- Planning report (supervisor accepted): `.alice-supervision/research/f1-f6-implementation-plan-20260825.md`
- Previous plan record: `20260824-p1-client-sync-fix-v1`（APPROVED 但**实态 CLIENT_TEST_FAILED**：ab510fd 引入玩家跳跃异常、bot 物理失效自 2298dd3 起、均未修复、无复测记录——参见 HANDOVER 与 progress-snapshot；本计划为其定案前置，其修复主线在定案后另行计划）

## Objective

为 **bot 物理失效**（自 `2298dd3` 起：bot 无法推挤、无击退）实施 F1-F6 服务端物理断言（定案前置）：通过服务端可证证据收敛根因候选闭包（hurt 链未触发 / B3 tick 链 NPE / 时序覆盖 / 客户端同步缺失），为后续修复决策提供定案依据。

**明确范围：本计划只加服务端断言/测试代码，不改任何业务实现；修复是定案后的下一步（另行计划 + 用户批准）。**

## Root Cause Context（审查已建立，未定案）

- **B2**：任务层每 tick `xxa=0/zza=1/travel(0,0,1)` 驱动 —— pathing 审查 javap 反编译修正为「叠加+衰减」非「覆盖」→ 降级为影响因素之一
- **B3**：`BotPlayer.tick()` try/catch NPE 吞错 → 跳过 aiStep/pushEntities（候选）
- **hurt/knockback 未触发**：isPushable/hurt 链（候选）
- **客户端同步缺失**：FakeConnection 广播（客户端候选，与跳跃异常线独立）

## Allowed Scope（实施范围，严格遵守）

### 只加断言/测试代码（不实现修复）

1. **新建** `src/main/java/com/dddgn/alice/task/BotPhysicsAssertionFixture.java`（独立 fixture，F1-F6 服务端断言）：
   - F1：`bot.hurt(...)` 后读 `getDeltaMovement()` —— hurt/knockback 链是否触发
   - F3：`bot.push(...)` 后读 delta+位移 —— push 写入路径；实体推挤链（aiStep/pushEntities 运行佐证）
   - F4：travel 前后 delta 摩擦衰减（约 0.91 缩放）+ 伴随 tick 链完整性断言（horizontalCollision/verticalCollisionBelow）
   - F5：同 tick vs 延迟 1 tick travel 时序对照
   - F2：击退后延迟 travel 位移保留（包含击退分量）
   - fixture 内自清理：hurt 后恢复 health、push 后归位、不写档
2. **修改** `src/main/java/com/dddgn/alice/bot/BotSelftest.java`：`setup()` 中 spawn 后加**一行调用** `BotPhysicsAssertionFixture.runF1F6(...)` + 日志 `BOT_PHYSICS_ASSERTION_SUITE`
3. 日志按 evidence-collection-standard（corr/tick/delta_x/y/z/health/horizontalCollision）

### 执行方式

- 阶段 A（服务端，一次 headless 可全覆）：`-Dalice.selftest.auto=true` 启动服务器自动执行（或 `/alice selftest` full 模式），约 0.5-1 工程日
- 阶段 B（客户端）：**仅当阶段 A 结果指向时**（全 PASS → 同步缺失候选；时序半定案 → 复测），由 Windows 客户端实测（M1-M5 矩阵，见下）

### 根因区分表（判定映射，不预定结果）

| 断言组合结果 | 根因结论 | 定案？ |
|---|---|---|
| F1 hurt false / delta 恒 0 | hurt/isPushable 链未触发 | ✅ 服务端定案 |
| F1 OK + F4 衰减不执行 + F3b 推挤恒 0 | B3 tick 链 NPE 跳过 aiStep | ✅ 定案（需 NPE 日志佐证） |
| F1 OK + F5 同 tick≈0 延迟有位移 | 时序覆盖（B2 因素成立） | 🟡 半定案，需客户端复测 |
| F1-F5 全 PASS | 服务端物理链正常 → 同步缺失候选 | 🟡 必须 F6 客户端实测 |
| F4 delta 归零（与 javap 矛盾） | 存在清零路径（异常发现） | ⛔ EVIDENCE_CONFLICT 停止 |
| F1 OK + F4 OK + F5 两时序均有位移 | 时序非根因 → 续跑 F3/F6 | ❌ 未定案 |

## Client Acceptance Matrix（阶段 B，仅当指向时执行；需用户 Windows 实测）

| 场景 ID | 场景 | 观察点 | 预期日志关键字 | 失败回收条件 |
|---|---|---|---|---|
| M1 | 服务端套件运行（headless，阶段 A） | 无需客户端 | `BOT_PHYSICS_ASSERTION_SUITE PASS\|FAIL ...` | 任一 FAIL → 按区分表定案，附 latest.log 行号 |
| M2 | 推挤 bot（玩家持续接触） | bot 是否被推动/穿过 | F3/F4 记录 + corr 对齐 | 服务端 PASS 客户端不可见 → EVIDENCE_CONFLICT，补 F6 收包+视频 |
| M3 | 击退 bot（玩家攻击一次） | 击退动画/位移可见 | F1/F2/F5 记录 + corr | 服务端 F1 PASS 客户端无击退 → 同步缺失候选，收包对照 |
| M4 | F6 收包对照（指向同步缺失时） | 玩家侧收包清单 | FakeConnection 广播日志 | 收包与可见位移矛盾 → EVIDENCE_CONFLICT 停止 |
| M5 | 复测回归（定案修复后，修复主线） | 推挤/击退回归 + 跳跃异常回归 | corr + 双端日志 | 见修复主线（本计划不覆盖） |

## Forbidden Scope（禁止事项）

- **不改业务实现**：`BotPlayer`/`FakeConnection`/`SoftMovementPrimitive`/travel 驱动/5 个 task 语义（SoftPathProbeTask/SoftPathMineTask/FollowTask/SoftMoveProbeTask/settle）零改动
- **不改 P0 门禁**：`SoftPhysicsObservationTest` 现有断言零改动（新 fixture 独立文件）；skills-manifest/state-machine 零改动
- **不预定根因结论**：区分表只列映射，实施结果出来后按表判定
- **不预定修复方案**：修复分叉（服务端口 vs 同步口）由定案后监督员决定、另行计划
- **不触碰**玩家跳跃异常线（独立：packet 抓包调查）；不触碰 tunnel/transfer/road 等其他线
- 实施后 git status 仅新增 1 文件 + 1 处修改 + 测试产物

## Deliverables & Verification

- 实施产出：新 fixture（约 150-250 行）+ BotSelftest 一行调用 + 日志
- 验证：`./gradlew compileJava` 通过 + headless 运行 `BOT_PHYSICS_ASSERTION_SUITE` 日志（服务端证据）
- 定案输出：`.alice-supervision/research/f1-f6-assertion-result-20260825.md`（各 F 项结果 + 区分表映射 + 佐证日志行号 + 待客户端项）——**根因判定由监督员按区分表执行**，实施者只收集证据不判定
- **服务端 PASS ≠ 客户端验证通过**；阶段 B 客户端证据由用户 Windows 实测确认

## Stop Conditions

- 断言互相矛盾 / 服务端 PASS 与既有客户端 FAIL（T4）冲突 → **EVIDENCE_CONFLICT 停止**，补双端证据后监督员裁决，不得以服务端 PASS 覆盖客户端 FAIL
- 实施过程中发现必须立即修的严重 bug → 停止单独立包交监督员评估
- 用户要求调整 → 停止重排

## Relationship to p1-client-sync-fix-v1

- 本计划是该线的**定案前置**（实态 CLIENT_TEST_FAILED：ab510fd 引入跳跃异常、bot 物理失效未修复、无复测记录）
- 本计划只产出证据（定案），`FakeConnection`/同步方案改动权归修复主线（定案后另行计划）
- 审查批次中的 p1 线证据（bot/pathing/task 三报告）可被采纳进该线 Research Decision（由监督员写入）```

## Supervisor checklist

- Does this change preserve the architecture: goal-level decision, deterministic execution, server authority, semantic GUI interfaces?
- Does it preserve the separate boundaries among `HARD_PATH`, experimental `SOFT_SURFACE`, forced road construction, survival monitoring, and future tunnel planning?
- Does it contradict a numbered decision in `docs/HANDOVER.md` or the frozen plan in `docs/PATHING_REFACTOR.md`?
- Are verification evidence, client-test requirements, known limitations, and the next safe step recorded accurately?
- Was `./gradlew compileJava` run and was the committed revision pushed to `origin/master`?
