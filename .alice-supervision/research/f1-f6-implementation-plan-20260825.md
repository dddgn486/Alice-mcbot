# F1-F6 服务端物理断言实施线路规划（bot 物理根因定案）

- 规划角色：Alice 实现规划员
- 日期：2026-08-25
- Git 基线：`6c2b461`（HEAD，全量功能审查批 1-4 已完成并验收）
- 任务书：`.alice-supervision/research/f1-f6-implementation-planning-task-20260825.txt`
- 用户批准：✅（2026-08-25 批准起草本实施计划）
- 性质：**只读规划**。本报告只规划「F1-F6 断言怎么实施、挂哪、怎么写、结果怎么判」，不改任何文件、不评估代码对错、不预定根因结论、不预定修复方案。

> **本规划不构成实施授权。** 实施（新增断言/测试代码）需监督员审核 + 用户批准后写入 active plan；修复是定案后的下一步，另行批准。

---

## 0. 事实基线（规划依据）

### 0.1 已确认事实（审查建立）

- bot 物理失效（自 `2298dd3` 起：bot 无法推挤、无击退）与玩家跳跃异常（`ab510fd` 引入）是**两条独立问题线**（bot 审查特别核查2：65f4863 回退跳跃正常但 bot 仍无法推挤 → 与 FakeConnection 广播无关，根因在 BotPlayer/tick 链与任务层 travel 用法）。
- 根因候选（均已收敛，未定案）：**B2** 任务层每 tick travel 输入叠加+衰减（pathing javap 反编译修正，从「覆盖」降级为「影响因素之一」）；**B3** `BotPlayer.tick()` try/catch NPE 吞错 → 跳过 aiStep/pushEntities（候选）；**hurt/knockback 未触发**（isPushable/hurt 链，候选）；**客户端同步缺失**（FakeConnection 广播，客户端候选，与跳跃异常线独立）。
- `BotPlayer` 仅 override `tick()`（try/catch super.tick()，`BotPlayer.java:28-36`），其余物理全部继承原版；SURVIVAL → `isPushable()` 理论为 true、`noPhysics` 理论为 false（javap：`LivingEntity.isPushable`=isAlive&&!isSpectator&&!onClimbable；`aiStep()` 字节码 857 调 travel、1064 无条件调 pushEntities）。
- travel 调用点 5 处（task 审查+本规划佐证行号一致）：
  1. `SoftPathProbeTask.java:141-144`（每 tick `applyToward(NATIVE_TRAVEL)`/`applyJumpToward`）
  2. `SoftPathMineTask.java:167-170`（SOFT_NAVIGATE 阶段）
  3. `FollowTask.java:106-107`（每 tick `applyToward(NATIVE_TRAVEL)`）
  4. `SoftMoveProbeTask.java:119-120`（`applyToward(backend)`）
  5. settle 三任务共用 `SoftMovementPrimitive.java:82-86`（`xxa=0,zza=0; travel(Vec3.ZERO)`；NATIVE_TRAVEL 分支 :48-53 为 `xxa=0,zza=1; travel(0,0,1)`）
- 现有测试基础设施：
  - `SoftPhysicsObservationTest.run(ServerLevel)`（139 行）：断言观测字段非空/revalidate/replan/MAX_REPLAN，**无任何 isPushable/push/knockback 位移断言**；被 `BotSelftest.setup()` 调用（`:352`）。
  - `BotSelftest`（917 行）：13 场景状态机 + setup（spawn bot + fixture 汇总）+ `-Dalice.selftest.auto=true` headless 自动 / `/alice selftest` 手动（`start(boolean full)`）。
  - `BotManager.spawn(level, surface, name)` / `firstInLevel` / `isBusy` 可直接驱动 bot。
- active plan `20260824-p1-client-sync-fix-v1`：计划文件写 APPROVED，**实态 CLIENT_TEST_FAILED**（ab510fd 实测失败、两问题均未修复、无复测记录）。

### 0.2 F 项命名对照（必须处理的事实差异）

任务书对 F1-F6 的命名与 pathing/task 审查原文**存在两处不一致**，实施前必须对齐（监督员验收时不混淆）：

| 实施编号（任务书权威） | 任务书命名 | 审查原文对应/差异 |
|---|---|---|
| F1 | hurt→knockback→deltaMovement 非零 | pathing F1 一致 |
| F2 | hurt 后延迟 1 tick travel 位移对比 | pathing F2（travel 后位移保留）+ pathing F5（时序）的合并语义；本规划按任务书拆分 F2（延迟对比）与 F5（同 tick 对比） |
| F3 | push→delta+位移 | pathing F3 一致 |
| F4 | 摩擦衰减 | **pathing 原文无同名项**：原文 F4=tick 链完整性（horizontalCollision 断言，task 审查 F4 同）。本规划把「摩擦衰减」实施为独立断言（travel 前后 delta 缩放），并保留「tick 链完整性」为 F4 的伴随断言（见 §1.3，两断言同属「travel/aiStep 链是否完整执行」） |
| F5 | 同 tick vs 延迟 tick 时序 | pathing F5 一致 |
| F6 | 客户端收包对照 | **pathing 原文 F6=版本对照（回退 19af345）**；任务书 F6 定义为客户端收包对照。本规划：F6（实施编号）= 客户端可见/收包对照（Windows 侧）；「版本对照（引入点确认）」标注为 V0 附加服务端项（可选，见 §1.3 附注），不与 F6 编号冲突 |

> 结论：以任务书编号为实施编号；审查原文的「F4 tick 链完整性」并入本规划 F4（伴随断言），「F6 版本对照」降为 V0 附加项。若监督员另有裁定，以监督员为准。

---

## 1. 实施线路（核心）

### 1.1 hook 位置推荐

**推荐：新建独立 fixture 类 `src/main/java/com/dddgn/alice/task/BotPhysicsAssertionFixture.java`（F1-F6），不改 `SoftPhysicsObservationTest.java` 任何一行；在 `BotSelftest.setup()` 中 `spawn` 之后、`softPhysicsPass` 与 TEST1 之前增加一行调用。**

理由：

| 候选 | 结论 | 理由 |
|---|---|---|
| 扩展 `SoftPhysicsObservationTest` | ❌ 不推荐 | ① 它是 P0 门禁（观测字段/revalidate/replan），任务书禁止「不碰 P0 门禁逻辑」；② 新域（物理交互断言）与现有域（观测字段）执行场景互扰——F1 的 `hurt` 会扣血、F3 的 `push` 会移位，混入后可能改变现有观测断言运行时的 bot 状态；③ 职责分离：一个 fixture 一个断言域，失败定位快 |
| 新建 fixture 类 | ✅ **推荐** | ① 零改动 P0 门禁文件；② 可回滚（新增 1 文件 + `BotSelftest` 1 行调用 + 日志关键字，undo = 删文件+删行）；③ 复用现有基础设施（BotLog、level 清理模式、`runTaskTicks` helper 模式、`firstInLevel`）；④ 可独立 headless 运行（`-Dalice.selftest.auto=true`） |
| 扩展 `BotSelftest` 状态机（新增 TEST14/15） | ❌ 不推荐 | ① 13 场景状态机是既有验收链，插入新增 PHYS 阶段会改变 REPORT 汇总语义；② F1-F6 是「定案前置调查」而非「验收场景」，放 setup 阶段的 fixture 汇总行（与现有 6 个 fixture 并列）符合项目惯例（TransferFixture/BotInventoryFixture/SoftPhysicsObservationTest 均在 setup 调用） |

**挂接位置**：`BotSelftest.setup()` 内 `bot = BotManager.spawn(...)` 之后、`boolean softPhysicsPass = SoftPhysicsObservationTest.run(level)` 之前（或紧邻其后）加：
```java
boolean botPhysicsAssertionPass = BotPhysicsAssertionFixture.runF1F6(level, bot);
BotLog.info("BOT_PHYSICS_ASSERTION_SUITE {} f1={} f3={} f4={} f5={} f2={}", ...);
```
（F6 不在此——F6 是客户端侧，见 §1.3/§5。）

### 1.2 fixture 内部结构（实现蓝图，供实施者参考）

```
public final class BotPhysicsAssertionFixture {
    // 复用 SoftPhysicsObservationTest 的 level 清理模式：清 5x15x5 区域 + 铺 STONE 地面
    public static boolean runF1F6(ServerLevel level, BotPlayer bot)
        → 断言前记录 health / 位置 / delta 基线；断言后 setHealth 恢复 + 清理位移 + 不写档
    // 每 F 项独立方法：assertF1HurtDelta / assertF3PushDelta / assertF4Friction /
    //     assertF5Timing / assertF2DelayedTravel / assertTickChain（F4 伴随）
    // 驱动 helper：runSoftProbeTicks(task, n)（软任务驱动，供 F2/F5 的 travel 前后挂钩）
    // 日志关键字：BOT_PHYSICS_ASSERTION_SUITE PASS|FAIL f1= f3= f4= f5= f2= tickChain= …（含 corr 字段）
}
```
- hurt 副作用控制：`bot.hurt(damageSource, 1.0f)` 后 `bot.setHealth(基线值)` 恢复；若断言期间 bot 死亡（罕见，1 点伤害不会），按 fixture 失败处理并记录。
- 不写档：fixture 全程不触碰 `BotWorldData`/`saveToWorld`；运行时 bot 已在档（selftest spawn），断言后由 selftest 既有流程继续。

### 1.3 每 F 项断言写法

通用约定：除 F4 外每项都用 `bot.getDeltaMovement()`（public API，无需反射）；位移用 `bot.position()` 差；日志字段按 evidence-collection-standard（corr/tick/delta/health/pos）。

| # | 输入 | 断言点 | 预期（结果含义，见 §2） |
|---|---|---|---|
| F1 | `bot.hurt(level.damageSources().generic(), 1.0f)`（不依赖真实玩家；如需更真实可用 `damageSources().playerAttack(测试实体)`，fixture 内构造） | hurt 返回后**同一 tick 立即**读 `getDeltaMovement()` | hurt 返回 true 且 delta 非零 = hurt/knockback 链触发；hurt 返回 false = isPushable/hurt 链未触发（客户端被推挤时也不可能有 hurt） |
| F3 | 3a（写入路径）：`bot.push(0.5, 0, 0)`（LivingEntity.push 直接写 delta）；3b（aiStep 推挤链）：在 bot 碰撞箱内放置另一实体（可用原版 `Zombie`/`Cow` 或临时 `ServerPlayer` 替代——headless 可构造，标注为「实体推挤链断言」） | push 后 1 tick 读 delta + 位移 | 3a：delta 变化 = push 写入路径正常；3b：实体推挤后 delta/位移变化 = pushEntities 链在运行（若 3a OK 且 3b 恒 0 → 指向 B3 tick 链跳过 aiStep/pushEntities） |
| F4 | 基线=hurt 后的 delta；然后跑 1 tick 任务层 travel（经 `SoftPathProbeTask` 驱动或直接 `SoftMovementPrimitive.applyToward`——推荐经任务驱动以挂钩真实调用点） | travel 前后 delta 比较 | **摩擦衰减断言**：delta 水平分量按约 0.91 缩放（原版摩擦）= travel/aiStep 链完整执行；delta 原样不变 = 衰减未执行（B3 候选，travel 链未跑）；delta 归零 = 输入覆盖/清零（与原版 javap 结论矛盾，属异常发现，进 EVIDENCE_CONFLICT 审查）。**伴随断言（tick 链完整性）**：推挤/撞击后断言 `bot.horizontalCollision`/`verticalCollisionBelow` 变化（证明 aiStep/pushEntities 运行；恒 false → B3 强候选） |
| F5 | 同 tick：hurt 后同一 tick 内立刻跑 travel；延迟：hurt 后等 1 tick 再跑 travel（同一 fixture 两次对照） | 两种时序下的位移对比 | 同 tick≈0 而延迟有位移 = 时序覆盖（B2 影响因素成立）；两者均≈0 = 非时序问题（指向 hurt 未触发/F4 tick 链）；两者均有位移 = 时序非根因 |
| F2 | 在 F5 延迟对照基础上：以「无击退基线」位移作对照，断言「击退后延迟 1 tick travel」的位移**包含击退分量**（F2 的完整语义 =「travel 保留击败分量」） | 位移矢量 vs 基线 | 位移含击退分量 = travel 不吞击退（服务端物理链基本正常，根因倾向客户端 F6）；位移缺失击退分量 = travel 路径存在削弱（与 F4/F5 交叉） |
| F6 | **客户端侧，非服务端断言**（Windows 玩家实测）：玩家持续接触/攻击 bot，观察 bot 是否被推挤/击退（可见位移） | 客户端可见位移 + 时间窗口与服务端 corr 日志对照 | 详见 §5 M2/M3；若服务端 F1-F5 全 PASS 而客户端仍不可见 → 同步缺失候选（FakeConnection/追踪路径）→ 需 packet 级证据（收包对照），属 EVIDENCE_CONFLICT 分支 |
| V0（附加，可选，不占 F 编号） | 版本对照：回退到 `2298dd3` 之前（如 `65f4863` 或更早）跑 F1-F3，确认服务端侧 bot 物理失效引入点 | 引入点判定 | bot 审查已用 65f4863 做过客户端回退；此 V0 补服务端侧引入点（是否 `2298dd3` 引入 travel 观测改动），供监督员决定是否纳入实施包 |

### 1.4 5 个 travel 调用点与 F 项挂钩对照

| travel 调用点 | 挂钩 F 项 | 挂钩方式 |
|---|---|---|
| `SoftPathProbeTask.java:141-144`（每 tick NATIVE_TRAVEL/跳） | F2/F4/F5 | 主挂钩点：fixture 断言期间创建 `SoftPathProbeTask`（复用 SoftPhysicsObservationTest 模式），在其 travel 调用前后读 delta（F4）；hurt 后同/延迟 tick 对照（F5/F2） |
| `SoftPathMineTask.java:167-170` | F2/F4（复验） | 可选复验：SOFT_NAVIGATE 阶段同一挂钩（证明非单任务特例） |
| `FollowTask.java:106-107` | F4（复验） | 可选复验：跟随中 hurt 后 travel 是否保留 delta（覆盖第三个消费方） |
| `SoftMoveProbeTask.java:119-120` | F2/F4/F5（复验） | 可选复验：`applyToward(backend)` 含 SELF_MOVE 分支（`move(MoverType.SELF, step)`）——可同时对照 SELF_MOVE 是否也覆盖（信息增量） |
| `SoftMovementPrimitive.java:82-86`（settle 零输入） | F4（settle 侧） | 断言 settle travel(Vec3.ZERO) 是否同样执行摩擦衰减（`xxa=0,zza=0` 输入下 delta 应衰减而非保留） |

> 实施量级控制：**主挂钩点 = SoftPathProbeTask 一处**（F2/F4/F5 全部覆盖）；其余 4 处为可选项（每处 +20-40 行，属「复验」，防止单任务特例误判）。首次实施建议只做主挂钩点，复验点进第二轮（详见 §3 顺序）。

### 1.5 执行方式与量级

| 项 | 方式 | 量级 |
|---|---|---|
| F1/F3/F4/F5/F2（服务端断言） | **headless 可跑**：`-Dalice.selftest.auto=true` 启动服务器 → `BotSelftest.setup()` 内自动执行（无需真实客户端连接）；或手动 `/alice selftest` 带 full 模式 | 新文件 1 个（约 150-250 行）+ `BotSelftest` 1 行调用 + 日志关键字；**约 0.5-1 工程日**（含 compileJava 调试） |
| F6（客户端收包对照） | **仅 Windows 客户端**：玩家进服观察 + 抓包（如需）；服务端同时跑 headless 日志带 corr | 客户端证据采集 0.5-1 小时/场景（见 §5） |
| V0（版本对照） | 服务端：checkout 旧 commit 跑 fixture（只读工作树切换，不改当前实现） | 0.5 小时/commit |

- 不触犯禁止项：以上全部是「新增断言/测试代码 + 既有一行调用」，不改 `BotPlayer`/`FakeConnection`/`SoftMovementPrimitive`/travel 驱动/任务语义/`SoftPhysicsObservationTest` 现有断言。

---

## 2. 结果判定（根因区分表）

### 2.1 断言组合 → 根因结论映射

只列映射关系，**不预定哪个结果会发生**；每个「结论」均为候选闭包的结果（定案 = 单一候选被确认且其余排除）。

| 组合（服务端断言结果） | 根因结论（候选闭包） | 定案？ |
|---|---|---|
| F1 hurt 返回 **false**（或 true 但 delta 恒 0） | **hurt/isPushable 链未触发**（候选 3 闭合；与客户端“被推挤也无击退”吻合） | ✅ 定案（服务端可证实） |
| F1 OK + F4 摩擦衰减**不执行**（delta 原样）+ F3b 推挤链恒 0 | **B3：BotPlayer.tick() NPE 吞错跳过 aiStep/pushEntities**（候选 2 闭合；需补 NPE 专项日志佐证，见 2.3） | ✅ 定案（服务端断言 + tick 链伴随断言一致指向）；仍需 NPE 日志佐证 |
| F1 OK + F2/F5 显示同 tick 位移≈0、延迟 1 tick 有位移 | **时序覆盖（B2 影响因素成立，但限于「击退后同 tick travel」场景）** | 🟡 半定案：服务端时序机制确认，需客户端复测确认可见位移是否恢复 |
| F1-F5 服务端**全部 PASS**（hurt 触发、delta 写入、travel 保留、衰减执行、时序无差异） | **服务端物理链正常 → 根因在客户端同步缺失候选**（FakeConnection/追踪路径） | 🟡 指向客户端：**必须 F6 客户端实测/收包对照**，否则不定案 |
| F4 显示 delta **归零**（与原版 javap 叠加+衰减结论矛盾） | 异常发现：存在清零路径（当前 javap 未覆盖的调用链）→ **EVIDENCE_CONFLICT** | ⛔ 停止，补证据后重审（见 2.3） |
| F1 OK + F4 OK + F5 两时序均有位移 | 时序非根因；配合 F3/F6 重新闭包 | ❌ 未定案，续跑 F3/F6 |

### 2.2 定案 vs 需客户端复测的边界

- ✅ **服务端可定案**：hurt 链未触发、B3 tick 链（需 NPE 日志佐证）、时序覆盖机制（服务端侧确认）。
- 🟡 **必须客户端复测**：服务端全 PASS（同步缺失候选）、时序覆盖的「客户端可见位移恢复」确认。
- ⛔ **EVIDENCE_CONFLICT（停止条件）**：① 服务端断言互相矛盾（如 F1 显示 hurt 触发但 F4 显示衰减不执行却 F3b 推挤位移正常——三个断言无法指向同一候选）；② 服务端 PASS 与既有客户端 FAIL（T4）冲突且无法解释（如 F1-F5 全 PASS 但客户端仍不可见且 F6 未跑）。处理：停止定案，按 EVIDENCE_CONFLICT 规则补双端证据（服务端日志行号 + 客户端视频/corr），交监督员裁决；**不得以服务端 PASS 覆盖客户端 FAIL**。

### 2.3 定案附带的必要佐证（证据等级要求）

| 定案方向 | 必需佐证 | 等级要求 |
|---|---|---|
| B3（tick 链 NPE） | `BotPlayer.tick()` 专项 NPE 日志（fixture 运行期间捕获 `latest.log` 中异常栈；若 fixture 断言 B3 而日志无 NPE，需重审——存在「NPE 被其他路径吞掉」的可能） | 服务端日志（带行号） |
| 客户端同步缺失 | F6 客户端实测：推挤/击退可见位移观察 +（如需）packet 收包清单 + 服务端 corr 日志 | 客户端证据（视频/截图）+ 服务端日志 |
| 时序覆盖 | 服务端 F5 断言记录 + 客户端复测（推挤 bot 是否仍无位移） | 双端 |

---

## 3. 依赖与顺序

### 3.1 依赖现有基础设施

- `BotSelftest`（`-Dalice.selftest.auto=true` headless / `/alice selftest` 手动）：提供服务器启动、bot spawn、fixture 汇总、自动关服流程（`:37-38,189,521,892`）。
- `BotManager.spawn/firstInLevel`：bot 获取。
- `SoftPhysicsObservationTest` 的模式（level 清理/铺地/任务驱动/日志）作为新 fixture 的实现样板（**读代码复用模式，不改其文件**）。
- `BotLog` 日志门面（带 `[alice]` 前缀）。
- skill 方法论文档（evidence-collection-standard 日志/证据要求、test-coverage-matrix Dual-View 划分）。

### 3.2 最省成本判定顺序（两阶段）

**阶段 A（服务端，一次 headless 运行可全覆盖，约 0.5-1 天）**：
```
F1（hurt 链）→ F3（push 链）→ F4（摩擦衰减+tick 链完整性）→ F5（时序）→ F2（位移保留）
```
顺序理由：F1/F3 最快区分「服务端物理链是否写入」（若是 → 跳过大批服务端断言域，直接指向 B3 或客户端）；F4 判定 travel/aiStep 链完整性（B3 判别核心）；F5/F2 收尾时序与保留语义。**F2/F5 依赖 SoftPathProbeTask 主挂钩点**（同一次 fixture 运行内完成）。

**阶段 B（客户端，仅当阶段 A 结果指向时）**：
- 阶段 A 全 PASS → **必须** F6 客户端实测（同步缺失候选闭包）。
- 阶段 A 半定案（时序覆盖）→ F6 客户端复测「可见位移恢复」。
- 阶段 A 定案（hurt 链/B3）→ F6 可选（作交叉验证，非必需）。

**V0（版本对照）**：仅在阶段 A 结论指向「`2298dd3` 引入点」时才需要（引入点确认）；若阶段 A 直接定案 hurt/B3，V0 降级为可选信息。

### 3.3 与 active plan（p1-client-sync-fix-v1）的关系

- F1-F6 是**该线的定案前置**：线实态 CLIENT_TEST_FAILED（ab510fd 实测失败、两问题未修复）。实施本计划（新增断言）需监督员审核 + 用户批准后**写入 active plan**（新工作包或并入该线 Research Decision），不修改修复方案本身。
- 审查只产出证据，**修改权归修复主线**：定案后由监督员决定修复时机（分叉点：先修 hurt/B3 服务端口 vs 先修同步客户端口），本计划不预定该分叉。
- 执行期不与修复主线冲突：断言 fixture 是只读型测试代码，不改变任何生产路径时序。

---

## 4. 风险与边界

### 4.1 修改测试代码的风险与缓解

| 风险 | 缓解 |
|---|---|
| F1 `hurt` 扣血影响后续 selftest 场景（TEST1 起） | fixture 内记录基线 health、断言后立即 `setHealth` 恢复；若死亡（1 点伤害不会）记 FAIL 并清理 |
| F3 `push` 移位影响后续场景 | fixture 内记录位置、断言后 `teleportTo` 归位（测试代码对 bot 的位置复位；不属「生产行为修改」，fixture 内自清理） |
| fixture 运行改变 bot 状态导致 SoftPhysicsObservationTest 断言域互扰 | 挂接顺序：`BotPhysicsAssertionFixture.runF1F6` **先于** `SoftPhysicsObservationTest.run` 或与其隔离（两 fixture 各自铺地/清理；`SoftPhysicsObservationTest` 文件零改动） |
| 断言期间 bot 死亡/掉档 | 不触发死亡路径（单次 1.0f hurt）；不写档、不 remove；由 selftest 既有流程接管 |
| fixture 与 P0 门禁（soft_phys/replan）断言互扰 | 新类独立文件，不触碰 `SoftPhysicsObservationTest` 文件；P0 门禁断言语义零变化 |

### 4.2 禁止事项（边界）

- **不改业务实现**：`BotPlayer`/`FakeConnection`/`SoftMovementPrimitive`/travel 驱动/5 个 task 语义/R# 冻结边界（HARD_PATH/SOFT_SURFACE）全部零改动。
- **不改 P0 门禁**：`SoftPhysicsObservationTest` 现有断言、`BotSelftest` 13 场景状态机（仅 setup 加一行调用）、skills-manifest、state-machine、HANDOVER、active plan（实施需监督员写入，规划员/实施员不得自行改）。
- **不预定根因结论**：§2 表只列「结果→结论」映射，不写「哪个会发生」。
- **不预定修复方案**：定案后修复分叉由监督员决定。
- **不改 P0 功能线**（dsh-agent-bus P0 改造等与 Alice 无关项不触碰）。

### 4.3 与玩家跳跃异常线的区分

- 玩家跳跃异常（`ab510fd` 引入，1.5 格跳）由**独立调查线**处理（packet 抓包：收到哪些包、entityId、重复性——bot 审查 A4/A5）；F6 客户端收包对照**只针对 bot 物理可见性**（推挤/击退），不并入跳跃异常的抓包结论。
- 两线共用日志门面但**独立 corr 编号**，避免证据混淆（progress-snapshot/corr 规则）。

---

## 5. 客户端验收矩阵（监督员 §5.2 格式）

> 前提：F6 及「需客户端复测」路径的客户端证据由 **Windows 客户端用户**采集并形成 evidence-report（`.alice-supervision/client-tests/`），规划员/实施员不得标记 USER_ACCEPTED。

| 场景 ID | 场景 | 观察点（Windows 客户端） | 预期/supporting 日志关键字（服务端） | 失败回收条件 |
|---|---|---|---|---|
| M1 | 服务端断言套件运行（headless，阶段 A） | 无需客户端；`-Dalice.selftest.auto=true` 一次运行 | `BOT_PHYSICS_ASSERTION_SUITE PASS|FAIL f1= f3= f4= f5= f2= tickChain=`（含 corr/tick/delta/health） | 任一断言 FAIL → 按 §2.1 映射定案候选，附 latest.log 行号 + 断言记录；断言互相矛盾 → EVIDENCE_CONFLICT 停止 |
| M2 | 推挤 bot（玩家持续接触，阶段 A 半定案/全 PASS 后） | bot 是否被推动（可见位移/是否穿过玩家） | 同 M1 的 F3/F4 记录 + corr 对齐 | 服务端断言 PASS 而客户端不可见 → EVIDENCE_CONFLICT，补 F6 packet 收包 + 视频后重新闭包；**不得以服务端 PASS 收尾** |
| M3 | 击退 bot（玩家攻击 bot 一次，同步/时序定案后） | 击退动画/位移是否可见、高度/距离 | F1/F2/F5 记录 + corr 对齐 | 服务端 F1 PASS 客户端无击退 → 同步缺失候选；需 packet 收包对照；时序半定案 → 复测「恢复后位移」 |
| M4 | F6 收包对照（仅当指向同步缺失） | 玩家侧收包清单（若可抓）或可见位移时间窗口 | FakeConnection 广播日志（broadcast 遍历玩家集合/包类型，若监督员批准记录） | 收包与可见位移矛盾 → EVIDENCE_CONFLICT 停止，交监督员裁决 packet 路径 |
| M5 | 复测回归（定案修复后，属修复主线） | 推挤/击退回归 + ab510fd 跳跃异常回归（独立） | corr + 双端日志 | 见修复主线（本计划不覆盖） |

> M1/M4 的服务端日志字段建议含 `corr=<fixture-run-uuid>`、`tick`、`delta_x/delta_y/delta_z`（三位小数）、`health`、`horizontalCollision`/`verticalCollisionBelow`——按 evidence-collection-standard 事件 schema（`evidence_v1` 风格）落日志。

---

## 6. 实施授权边界与交付

- **本规划不构成实施授权。** 实施（新增 `BotPhysicsAssertionFixture` + `BotSelftest` 一行调用 + 日志）需监督员审核 + 用户批准后写入 active plan，按「只加服务端断言/测试代码」范围执行（compileJava 通过 + headless 断言 PASS 只能作为服务端证据，不得标 USER_ACCEPTED）。
- **修复不是本计划的一部分**：定案后修复（bot 物理/碰撞链/同步）由监督员在定案基础上另行批准、独立计划。
- 交付物（本规划）：`.alice-supervision/research/f1-f6-implementation-plan-20260825.md`；已确认事实/架构推论/待调查分级见 §0/§2；F 项命名对照（任务书 vs 审查原文差异）已在 §0.2 显式处理，供监督员裁定。

---

**本规划不构成实施授权。** 规划全程只读：未修改任何文件（业务代码/测试/skill/HANDOVER/active plan/客户端记录均未动），未运行测试，仅读取任务书、三份批 1 审查报告、SoftPhysicsObservationTest/BotSelftest/BotManager/SoftMovementPrimitive 源码片段与 git 佐证。
---

## 监督员验收记录（2026-08-25，Architecture Supervisor）

**验收结论：✅ 通过（F1-F6 实施线路规划）**

独立核查：
1. hook 可行性：`BotSelftest.java:352` 确认 `SoftPhysicsObservationTest.run(level)` 实际调用存在（规划注入点"spawn 后、softPhysicsPass 前/紧邻"可行）；新 fixture 与现有 `SOFT_PHYSICS_OBSERVATION_SUITE` 日志并行不冲突 ✅
2. 断言缺口确认：SoftPhysicsObservationTest grep isPushable/push/hurt/knockback/horizontalCollision 零命中——F1-F6 填补的缺口真实存在 ✅
3. 边界守住：只加断言不改实现（BotPlayer/FakeConnection/travel 驱动/5 task 语义零改动）、不预定根因结论（§2 只列映射不写"哪个会发生"）、不预定修复方案（修复分叉由监督员定案后决策）、不改 P0 门禁文件 ✅
4. F 项命名差异显式处理（§0.2）：F4"摩擦衰减"vs 审查原文"tick 链完整性"（合并为 F4+伴随断言）、F6"客户端收包"vs 原文"版本对照"（版本对照降为 V0 附加项）——对齐建议合理，避免实施混淆 ✅
5. 根因区分表完整（§2.1 六种组合→结论映射）：服务端可定案（hurt 链/B3 tick 链+佐证）/需客户端复测（全 PASS→同步缺失）/EVIDENCE_CONFLICT 停止条件明确 ✅
6. 量级合理：阶段 A 服务端 0.5-1 工程日、主挂钩点 SoftPathProbeTask 一处（复验点第二轮）；阶段 B 客户端仅当指向时 ✅
7. 客户端矩阵（M1-M5：场景/观察点/日志关键字/失败回收条件）按监督员格式；M1/M4 日志 schema 建议（corr/tick/delta/health/collision）符合 evidence-collection-standard ✅
8. 只读性：src 零改动、HEAD 仍 6c2b461 ✅

**采纳为 F1-F6 实施线路依据。下一步：写入 active plan 草案报用户批准（实施=新增 BotPhysicsAssertionFixture + BotSelftest 一行调用 + 日志，仅服务端断言；修复不在本包内）。**
