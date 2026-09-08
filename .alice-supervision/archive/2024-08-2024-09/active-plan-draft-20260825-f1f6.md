# Alice Active Work Plan（草案，待用户批准）

- Plan ID: `20260825-f1f6-bot-physics-assertions-v1`
- Status: **PENDING_USER_APPROVAL**（草案，待用户批准；批准后按项目惯例登记为正式 active plan）
- Baseline: `6c2b461`（HEAD，全量功能审查批 1-4 已完成并验收）
- User approval: ⏳ **待用户批准**（2026-08-25 批准起草）
- Planning report (supervisor accepted): `.alice-supervision/research/f1-f6-implementation-plan-20260825.md`

## 背景关系

当前登记 plan `20260824-p1-client-sync-fix-v1` 实态 **CLIENT_TEST_FAILED**（ab510fd 引入玩家跳跃异常、bot 物理失效自 2298dd3 起、均未修复、无复测记录）。F1-F6 是**该线定案前置**：先定案 bot 物理根因，再决定修复分叉。

## Objective

为 **bot 物理失效**（无法推挤、无击退）实施 F1-F6 服务端物理断言（定案前置），通过服务端可证证据收敛根因候选闭包（hurt 链未触发 / B3 tick 链 NPE / 时序覆盖 / 客户端同步缺失），为后续修复决策提供定案依据。

**明确范围：只加服务端断言/测试代码，不改任何业务实现；修复是定案后的下一步（另行计划+批准）。**

## Root Cause Context（审查已建立，未定案）

- **B2**：任务层每 tick travel 驱动 —— pathing 审查 javap 反编译修正为「叠加+衰减」非「覆盖」→ 降级为影响因素之一
- **B3**：`BotPlayer.tick()` try/catch NPE 吞错 → 跳过 aiStep/pushEntities
- **hurt/knockback 未触发**：isPushable/hurt 链
- **客户端同步缺失**：FakeConnection 广播（独立于跳跃异常线）

## Allowed Scope

### 只加断言/测试代码（不实现修复）

1. **新建** `src/main/java/com/dddgn/alice/task/BotPhysicsAssertionFixture.java`（独立 fixture，F1-F6）：
   - F1：`bot.hurt(...)` 后读 `getDeltaMovement()`（hurt/knockback 链触发）
   - F3：`bot.push(...)` 后读 delta+位移（push 写入）；实体推挤链（aiStep/pushEntities 佐证）
   - F4：travel 前后 delta 摩擦衰减（约 0.91 缩放）+ 伴随 tick 链完整性（horizontalCollision/verticalCollisionBelow）
   - F5：同 tick vs 延迟 1 tick travel 时序对照
   - F2：击退后延迟 travel 位移保留（含击退分量）
   - fixture 内自清理：hurt 后恢复 health、push 后归位、不写档
2. **修改** `BotSelftest.java`：`setup()` 中 spawn 后加**一行调用** + 日志 `BOT_PHYSICS_ASSERTION_SUITE`
3. 日志按 evidence-collection-standard（corr/tick/delta_x/y/z/health/horizontalCollision）

### 执行方式

- 阶段 A（服务端）：`-Dalice.selftest.auto=true` headless 一次全覆（或 `/alice selftest` full），约 0.5-1 工程日
- 阶段 B（客户端）：仅当阶段 A 指向时，Windows 客户端实测（M1-M5 矩阵）

### 根因区分表（判定映射，不预定结果）

| 断言组合结果 | 根因结论 | 定案？ |
|---|---|---|
| F1 hurt false / delta 恒 0 | hurt/isPushable 链未触发 | ✅ 服务端定案 |
| F1 OK + F4 衰减不执行 + F3b 恒 0 | B3 tick 链 NPE 跳过 aiStep | ✅（需 NPE 日志佐证）|
| F1 OK + F5 同 tick≈0 延迟有位移 | 时序覆盖（B2 因素成立）| 🟡 半定案需客户端复测 |
| F1-F5 全 PASS | 服务端物理链正常 → 同步缺失候选 | 🟡 必须 F6 客户端实测 |
| F4 delta 归零（与 javap 矛盾）| 异常清零路径 | ⛔ EVIDENCE_CONFLICT 停止 |
| F1 OK + F4 OK + F5 两时序均有位移 | 时序非根因 | ❌ 续跑 F3/F6 |

## Client Acceptance Matrix（阶段 B；需用户 Windows 实测）

| 场景 | 观察点 | 预期日志关键字 | 失败回收条件 |
|---|---|---|---|
| M1 | headless 套件运行 | `BOT_PHYSICS_ASSERTION_SUITE PASS|FAIL f1= f3= f4= f5= f2= tickChain=` | 任一 FAIL → 按区分表定案，附 latest.log 行号 |
| M2 | 推挤 bot | bot 是否被推动/穿过 | F3/F4 记录+corr | 服务端 PASS 客户端不可见 → EVIDENCE_CONFLICT 补 F6+视频 |
| M3 | 攻击 bot 一次 | 击退动画/位移可见 | F1/F2/F5 记录+corr | 服务端 F1 PASS 客户端无击退 → 同步缺失候选，收包对照 |
| M4 | F6 收包对照 | 玩家侧收包清单 | FakeConnection 广播日志 | 收包与可见位移矛盾 → EVIDENCE_CONFLICT 停止裁决 |
| M5 | 复测回归（修复后）| 推挤/击退回归+跳跃回归 | corr+双端日志 | 属修复主线，本计划不覆盖 |

## Forbidden Scope（禁止事项）

- **不改业务实现**：BotPlayer/FakeConnection/SoftMovementPrimitive/travel 驱动/5 个 task 语义零改动
- **不改 P0 门禁**：SoftPhysicsObservationTest 现有断言零改动（新 fixture 独立文件）；skills-manifest/state-machine 零改动
- **不预定根因结论**：区分表只列映射，实施结果出来后按表判定
- **不预定修复方案**：修复分叉（服务端口 vs 同步口）由定案后监督员决定、另行批准
- 不触碰玩家跳跃异常线（独立 packet 抓包调查）；不触碰 tunnel/transfer/road 等其他线
- 实施后 git status 仅新增 1 文件 + 1 处修改；不提交未经用户确认的修复

## Deliverables & Verification

- 实施产出：新 fixture（约 150-250 行）+ BotSelftest 一行调用 + 日志
- 验证：`./gradlew compileJava` 通过 + headless 运行 `BOT_PHYSICS_ASSERTION_SUITE` 日志（服务端证据）
- 定案输出：`.alice-supervision/research/f1-f6-assertion-result-20260825.md`（各 F 项结果 + 区分表映射 + 佐证日志行号 + 待客户端项）
- **服务端 PASS ≠ 客户端验证通过**；阶段 B 客户端证据由用户 Windows 实测确认

## Stop Conditions

- 断言互相矛盾 / 服务端 PASS 与既有客户端 FAIL（T4）冲突 → EVIDENCE_CONFLICT 停止，补双端证据后监督员裁决
- 发现必须立即修的严重 bug → 停止单独立包
- 用户要求调整 → 停止重排

## Relationship to p1-client-sync-fix-v1

- 本计划是该线定案前置（实态 CLIENT_TEST_FAILED）
- 只产出证据（定案）；FakeConnection/同步方案改动权归修复主线（定案后另行计划）
- 批 1 证据（bot/pathing/task 三报告）可被监督员采纳进该线 Research Decision

---

**本草案待用户批准；批准前不构成实施授权。**