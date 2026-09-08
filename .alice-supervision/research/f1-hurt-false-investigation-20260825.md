# Alice Deep Research Report

- Topic: `f1-hurt-false-investigation`
- Date: `2026-08-25`
- Task source: `.alice-supervision/research/f1-hurt-false-investigation-task-20260825.txt`
- Scope: `Read-only research; does not authorize implementation.`

## 1. Projects and Versions / Commits

| Project | Repository | Version / commit | Version certainty |
|---|---|---|---|
| Alice | `/home/fb486/projects/alice` | 当前工作树 HEAD `d058dd024138150137fef8881809f56e25194fb3`；任务关联断言/裁决证据分别标注 `6c2b461`、`e9af1b3`、`21f653f` | A：本地 Git，可复核；工作树含监督流程变更，未改业务代码 |
| Minecraft/Forge | 本地 ForgeGradle cache | Forge `1.20.1-47.4.10`, Parchment `2023.09.03-1.20.1`; mapped jar `forge-1.20.1-47.4.10_mapped_parchment_2023.09.03-1.20.1.jar` | A：项目 `build.gradle`/`gradle.properties` 与本地依赖一致 |
| Forge fixed sources | 本地 Forge sources | `forge-1.20.1-47.4.10-sources.jar`, SHA-256 `918a11bdfceace2752d4c29bddbdf327981e1f6a1e1f0675f23e5fbf01e226c0` | A：固定本地源码包；原版正文以 mapped jar `javap` 复核，Forge patch 记录 Forge hooks |

## 2. Source Evidence

| Source file | Class / function | Confirmed fact | Evidence level | Notes |
|---|---|---|---|---|
| `src/main/java/com/dddgn/alice/bot/BotPlayer.java:22-36` | `BotPlayer`, constructor/inherited behavior | `BotPlayer extends ServerPlayer`;只 override `tick()`，没有 override `hurt()`、`isInvulnerableTo()` 或 `isPushable()` | A | 因此运行时 dispatch 进入 `ServerPlayer.hurt()` |
| `src/main/java/com/dddgn/alice/bot/BotManager.java:66-89` | `spawn` | `new BotPlayer(server, level, profile)` 后立即 `placeNewPlayer(...)`、`teleportTo(...)`、`gameMode.changeGameModeForPlayer(SURVIVAL)`；没有清除/等待 `spawnInvulnerableTime` 的操作 | A | `placeNewPlayer` 是注册/连接初始化流程，不等同于已过出生保护时间 |
| `src/main/java/com/dddgn/alice/bot/BotSelftest.java:327-354` | `setup` | `BotManager.spawn(...)` 后连续运行多项 fixture，其中 `BotPhysicsAssertionFixture.runF1F6` 被立即调用；没有专门等待 60 tick | A | 这是 F1 的时序充分条件 |
| `src/main/java/com/dddgn/alice/task/BotPhysicsAssertionFixture.java:60-95` | `assertF1Hurt` | generic 与 Zombie 两次调用均检查 `hurtOk`、health 与 delta；证据日志为两个 `hurtOk=false`、health 不变、delta 为零 | A | 任务书禁止修改，本调查未修改 |
| `.alice-supervision/research/f1-f6-assertion-result-20260825.md:28-37` | F1 evidence | `generic=false`, `gHurtOk=false`, `health=20→20`; Zombie 同样 `mHurtOk=false`, health 不变 | A | 服务端观测，非客户端验收 |
| mapped Forge jar `ServerPlayer.hurt` (`javap` bytecode offsets 0-65) | `ServerPlayer.hurt(DamageSource,float)` | 顺序为：`isInvulnerableTo` → PVP/fall 特例 → `spawnInvulnerableTime > 0` 且 damage 不 bypass invulnerability 时 `return false` → 来源实体检查 → `Player.hurt` | A | 这是直接根因的固定版本字节码证据 |
| mapped Forge jar `ServerPlayer.<init>` (`javap` offsets 74-78) | constructor | 构造函数执行 `spawnInvulnerableTime = 60` | A | 60 是 server player 出生无敌计时器；首次 spawn 后若未经历足够 tick，普通伤害被拒绝 |
| mapped Forge jar `ServerPlayer.tick` (`javap` offsets 831-854) | `tick` | 每次 tick 将 `spawnInvulnerableTime` 减 1；同时维护 `invulnerableTime` | A | `BotPlayer.tick()` 调 `super.tick()`，但 F1 fixture 在 spawn 后立即执行；不得把“最终会递减”误写成“当前已为 0” |
| mapped Forge jar `Player.hurt` (`javap` offsets 0-153) | `Player.hurt` | 在 `Player` 层还会检查 Forge `onPlayerAttack`、`isInvulnerableTo`、`abilities.invulnerable`、`isDeadOrDying`；这些分支均位于 ServerPlayer 的 `spawnInvulnerableTime` 检查之后 | A | F1 当前失败不需要假设 abilities 或 health 异常 |
| mapped Forge jar `LivingEntity.hurt` (`javap` offsets 0-42+) | `LivingEntity.hurt` | LivingEntity 层首先检查 Forge `onLivingAttack`、`isInvulnerableTo`、client side、`isDeadOrDying`；随后才执行 shield/armor/actuallyHurt 等 | A | generic 的 server-side damage source 不应因 client-side 分支失败；当前直接阻断在 ServerPlayer 层更早 |
| mapped Forge jar `Player.isInvulnerableTo` / `ServerPlayer.isInvulnerableTo` | invulnerability chain | `Player.isInvulnerableTo` 委托 LivingEntity 后按 drowning/fall gamerule 增加规则；`ServerPlayer.isInvulnerableTo` 额外在 changing dimension 时返回 true | A | 本次 generic/Zombie 结果与 `spawnInvulnerableTime` 一致；仍建议实施前 fixture 记录该方法结果用于排除环境状态 |
| mapped Forge jar `LivingEntity.isPushable` | `isPushable()` | 默认实现为 `isAlive() && !isSpectator() && !onClimbable()`；mapped jar 未显示 Player/ServerPlayer override | A | `isPushable` 不是当前 `hurt=false` 的直接分支；它属于实体推挤链的独立条件 |
| mapped Forge jar `LivingEntity.pushEntities` | `pushEntities()` | server side 获取当前 AABB 内实体并调用 `doPush`；实体推挤循环不调用目标 `hurt()`。只有 max entity cramming 超限时才调用自身 `hurt(cramming, 6)` | A | 因此 F1 hurt false 不会解释普通 Zombie→bot 的 F3b 推挤失败；F3b 的 `doPush`/move 消费应独立定位 |
| `.alice-supervision/research/f1f6-bot-physics-verdict-20260825.md:37,47-59` | 定案边界 | 裁决明确 F1 是独立于实体 tick 链的另一条未触发路径，要求单独立包；主根因是实体链响应未被有效消费 | A | 本报告不翻案、不合并方案 C |

### 2.1 直接根因的逐分支追踪

固定 Forge 1.20.1-47.4.10 mapped bytecode 的实际路径为：

```text
BotPhysicsAssertionFixture.assertF1Hurt
  -> bot.hurt(generic, 1.0F)
  -> ServerPlayer.hurt
     -> bot.isInvulnerableTo(generic) == false（当前证据未显示该分支触发）
     -> dedicated/PvP fall 特例不适用
     -> spawnInvulnerableTime > 0 && generic 不 bypass invulnerability
     -> return false
     -> 不进入 Player.hurt / LivingEntity.hurt
     -> health 不变，knockback/actuallyHurt 不执行，delta 不变
```

`BotManager.spawn` 直接构造 `ServerPlayer` 子类，而固定版本 `ServerPlayer` 构造把 `spawnInvulnerableTime` 设为 `60`。`BotSelftest.setup` 在 spawn 后立即跑 F1，没有等待该值递减至 0。因此这是足以解释两源均 false 的充分条件。对于 Zombie `doHurtTarget`，调用最终仍到目标 `ServerPlayer.hurt`，故同一保护计时器先行拒绝；并非 Zombie 攻击专属条件。

`invulnerableTime` 是另一种伤害冷却字段，且 `Player.hurt` 的主要前置中并不以它作为最早返回分支；不要把 `invulnerableTime` 与 `spawnInvulnerableTime` 混同。`isDamageSourceBlocked` 属于 LivingEntity 后续的 shield 分支，当前 Bot 背包/手部状态也不是直接原因。`getHealth() <= 0` 不是当前路径证据：日志显示 health=20.000，且 `isDeadOrDying` 必须在更早层被单独记录才能宣称已排除。

## 3. Model or Execution Comparison

### 3.1 BotPlayer vs ordinary ServerPlayer

| 维度 | ordinary `ServerPlayer` | Alice `BotPlayer` | 影响 |
|---|---|---|---|
| 类型 dispatch | 进入 `ServerPlayer.hurt` | 同样进入继承的 `ServerPlayer.hurt` | BotPlayer 没有 hurt 特殊实现 |
| 构造 | `ServerPlayer` constructor 设 `spawnInvulnerableTime=60` | `super(server, level, profile)`，同样继承该初始化 | 不是“BotPlayer 缺字段”，而是 bot 立即被测试 |
| 注册/生命周期 | 正常登录/重生后会经历 tick | `placeNewPlayer` 注册后立即 teleport/setup/fixture | 测试时序暴露 60 tick 出生保护 |
| GameMode | 由登录流程设置 | spawn 后显式切为 SURVIVAL | 能影响 `abilities.invulnerable` 的后续判断，但不能绕过 ServerPlayer 的 spawn timer |
| connection | 真实连接 | `FakeConnection` 经 `placeNewPlayer` 注入 | 与 F1 直接返回 false 无关；禁止触碰 FakeConnection |
| `abilities` | Player 构造并由登录流程维护 | Player/Super 构造存在，后切生存 | 当前没有证据表明 `abilities.invulnerable=true`；实施前应记录它作为诊断字段 |
| `isPushable` | LivingEntity 默认条件（除非上游版本 override） | 同一继承链 | 与 F1 的 ServerPlayer spawn timer 是不同机制 |
| `invulnerableTime` | LivingEntity 字段默认/运行时维护 | 同一继承链，ServerPlayer tick 维护 | 不应代替检查 `spawnInvulnerableTime` |

### 3.2 F1 与 F3b 是否关联

**结论：不构成普通推挤链的直接因果；修复 F1 后 F3b 不应自动恢复。**

- `LivingEntity.pushEntities()` 的普通 server-side 分支枚举 AABB 内实体后直接调用 `doPush(Entity)`；普通实体推挤不调用 `hurt()`。
- `hurt()` 只在 `maxEntityCramming` 超限的特殊分支中作为 cramming damage 调用。F3b fixture 只放置一个 Zombie，不是 cram 超限场景，因此 F3b 不依赖目标 `hurt()` 返回值。
- F1 的 `ServerPlayer.spawnInvulnerableTime` 修复只能使伤害链进入 `Player.hurt`/`LivingEntity.hurt`，并可能产生伤害反馈/击退；它不能使 `pushEntities` 的 AABB 查询、`EntitySelector.pushableBy`、`doPush` 或 `Entity.move` 自动改变。
- 这与裁决一致：F1 是独立路径；F3b/tickChain 仍需保持方案 C（或后续实体链方案）的单独复测。若未来测试使用 cramming damage 作为 F3b 变体，才可能观察到 `hurt` 影响该变体，但不能推广到普通 Zombie 推挤。

## 4. Transferable Principles

### 4.1 修复注射点候选（只给候选，不实施）

| 方案 | 注射点与预计改动 | 机制 | 风险/回归 | R31 边界 |
|---|---|---|---|---|
| A：构造/生成后清除出生保护 | 首选诊断位置是 `BotManager.spawn` `BotPlayer` 构造/注册后约 `:71-84`；若可通过固定 API 安全设置，应在 spawn 后将 bot 的出生保护状态置为允许测试/运行伤害；否则需要 `BotPlayer` 暴露窄 setter 或在生成流程延迟可受伤状态。预计 1-10 行（API 可见性需先确认） | 保持原版 `ServerPlayer.hurt` 语义，只改变 Alice bot 的生命周期策略，使 `spawnInvulnerableTime` 不再阻断普通伤害 | 推荐候选。须避免反射/AccessTransformer 隐式改私有字段；必须复测 F1 generic/Zombie、健康扣减、delta、死亡过滤、F2/F4/F5/R2/R3。若用等待 60 tick，则测试稳定但 bot 初期不可攻击且客户端矩阵改变 | **不触碰软移动核心**，通常不触碰 R31；若通过重写移动/伤害链绕过原版则越界 |
| B：BotPlayer `hurt` 窄 override | `src/main/java/com/dddgn/alice/bot/BotPlayer.java` 约 `:28-36` 后新增 override（约 5-20 行）；只允许在明确 bot policy 下绕过/重置出生保护，再调用 `super.hurt`；不应复制 `ServerPlayer.hurt` 全体逻辑 | 将 bot 特殊生命周期策略注入伤害入口，保留原版后续 PVP、abilities、health、armor、shield 和事件路径 | 中高风险：若 override 直接调用 `Player`/`LivingEntity` 超越 `ServerPlayer`，会绕过 PVP、spawn protection、Forge hooks 或未来版本语义；若反射修改私有 timer，脆弱且不可审计。需验证普通玩家不受影响、bot death/respawn/health 与 F2/F4/F5/R2/R3 不回归 | 只要是窄伤害入口策略，不重写移动，原则上不触碰 R31；但“从零复制 hurt”属于高风险、不可直接采纳 |
| C：fixture/生命周期等待验证（非业务修复） | 仅在 `BotPhysicsAssertionFixture` 或 `BotSelftest` 的测试前置等待 60+ tick，或先读证据字段再断言；业务文件零改动 | 验证当前根因：若等待后 generic/Zombie `hurtOk=true` 且 health/delta 变化，确认 timer 充分条件；若仍 false，继续查 `isInvulnerableTo`/Forge event/abilities | 最低风险、最适合作为修复前验证；不能解决实际运行中的 bot 初期无击退行为，也不能作为生产修复授权 | 不触碰 R31；但任务书明确 fixture 零改动，本调查未实施 |

**方案选择建议：**监督员可先批准一个独立的验证小包（方案 C 的“等待/诊断”思路，但遵守本任务禁止事项，不在本调查实现），确认 `spawnInvulnerableTime` 清零后 F1 是否恢复。若确认，生产修复首选 A（生成生命周期策略）而非复制 vanilla `hurt`；B 只应作为 A 无可用 API 时的窄 fallback，并严禁绕过 `ServerPlayer.hurt` 全链。

### 4.2 可验证修复/复测断言

1. spawn 后记录 `spawnInvulnerableTime`（若 API 不可见，使用可审计的 tick 计数/行为证据，而非猜测）；记录 `bot.isInvulnerableTo(generic)`, `bot.isDeadOrDying()`, `bot.isSpectator()`, `bot.isPushable()`, `bot.getAbilities().invulnerable`, `health`, `delta`。
2. 在 timer > 0 与 timer == 0 两个窗口分别调用 generic；预期前者 `false/health unchanged/delta unchanged`，后者进入 Player/LivingEntity 路径，预期至少 `hurtOk=true` 或记录其后续拒绝分支。
3. Zombie `doHurtTarget` 做相同双窗口对照；确认与 generic 一致，排除攻击源特有路径。
4. 修复后至少保持 F2/F4/F5、方案 C 的 F3a/F3b/tickChain 断言；同时检查 bot death filter、transfer 与既有 R2/R3。服务器 PASS 不能替代 Windows M3 用户验收。

## 5. Non-transferable Risks and Unknowns

- 本报告精确锁定的是**当前 fixture 时序下**的直接阻断：`ServerPlayer.spawnInvulnerableTime > 0`。仍未通过改 fixture 直接完成“等待后 F1 恢复”的实验，因为任务书禁止修改/实施；因此应在独立验证包中确认后再写生产计划。
- `spawnInvulnerableTime` 是 `ServerPlayer` 私有字段；本地 mapped jar 未显示公开 setter。不要直接采用反射、脆弱字段访问或完整复制 `hurt()`，除非监督员另行批准并记录兼容性边界。
- `ServerPlayer.hurt` 还有 `isInvulnerableTo`、dedicated/PVP、spawn timer、来源玩家/PvP 等条件；当前日志未逐项记录所有条件。若清除 timer 后仍 false，下一优先检查为 Forge `ForgeHooks.onPlayerAttack` 取消、`isInvulnerableTo`、abilities.invulnerable、dead state，而不是猜测 shield/armor。
- `isDamageSourceBlocked`、shield、armor、`invulnerableTime` 位于更后续路径或不同层次，不能冒充本次直接原因。
- `isPushable()` 默认只决定实体推挤选择条件；F1 的伤害返回值与普通 F3b `doPush` 不等价。修复 F1 不保证 F3b 恢复。
- F1 仅服务端证据；M3 客户端可见击退仍需 Windows 客户端实测。FakeConnection、方案 C 主修复、矿链、5 个 travel 点、P0 门禁均不在本报告范围。
- 外部 web_search 本轮因服务余额不足失败；本报告没有用博客、宣传页面或 headless PASS 替代固定本地 Forge 源码证据。

## 6. Smallest Alice Plan and Client Matrix

### A.1 最小后续计划（只建议，不授权）

1. **验证包**：新增独立、只读诊断 seam（不得改本任务 fixture），在 spawn 后分别等待/观测 timer 窗口，记录上述前置条件；确认 timer 清零后 F1 是否恢复。
2. **窄修复包（若验证确认）**：只处理 bot 出生保护生命周期，优先通过生成流程/API 清除或延迟可伤害状态；禁止复制完整 vanilla hurt；不改 `FakeConnection`、方案 C、travel、矿链。
3. **服务端回归**：F1 generic/Zombie、F2、F3a/F3b、F4、F5、tickChain；健康、死亡、Bot death filter、任务替换/生存中断；既有 transfer/inventory/selection suites。
4. **客户端验收**：用户 Windows 客户端验证 M3，确认真实玩家攻击或指定攻击源下 bot 出现可见击退、健康/伤害表现符合预期；同时确认 M2 推挤、GUI 隔离、普通 HARD_PATH 不回归。
5. **停止条件**：若 timer 清零后 F1 仍 false，停止修改，转为逐项记录 `isInvulnerableTo`/Forge attack event/abilities/dead state；不得未经新证据扩展到 FakeConnection 或移动链。

### B. 客户端矩阵

| 场景 | 证据目标 | 责任 | 通过条件 |
|---|---|---|---|
| M3-1 generic/等价真实攻击 | bot 受到伤害且出现可见击退 | Windows 用户 | 客户端观察到位移；服务端日志同时显示 hurt true/health delta/deltaMovement |
| M3-2 Zombie 攻击 | 生物攻击链不再被出生保护错误阻断 | Windows 用户 + 服务端 | Zombie 命中后 bot 位移/伤害表现一致，非只 headless 断言 |
| M3-3 spawn 初期策略 | 确认出生保护是否仍有意保留 | 监督员/用户裁决 | 明确 bot 刚生成时应可否受伤；若保留，F1 fixture 必须等待保护窗口而非把 false 当永久故障 |
| M2 回归 | 推挤与 F1 分离 | Windows 用户 | M2 仍 PASS；F1 修复不被误报为推挤修复 |
| R2/R3/GUI 隔离 | 既有用户验收边界不回归 | Windows 用户 | 原有窄范围继续 PASS |

## 7. Adoption and Freshness Notes

- Evidence confidence: **A：Alice 本地源码/fixture/裁决 + 固定 Forge 1.20.1-47.4.10 mapped bytecode/source hash**；B：由固定字节码到当前 fixture 时序的窄推论。
- Assumptions that invalidate this report: Forge/Minecraft 版本变化；`ServerPlayer.hurt` 顺序或 `spawnInvulnerableTime` 初始化变化；`BotManager.spawn` 改为等待 60 tick 或显式改变生命周期；fixture 改为在保护窗口后运行。
- Conclusions that should not be adopted without new evidence: 直接复制/绕过 `ServerPlayer.hurt`；把 F1 修复当作 F3b 修复；把 `isPushable` 当作 hurt false 直接原因；把清除 spawn timer 当作用户已批准的行为策略。
- Parallel work that remains safe while this topic is unresolved: 只读日志/字节码审计、F3b `pushEntities` 独立机制分析、客户端证据采集准备；不得实施修复或修改 active plan。

## 8. Delivery Boundary

本报告不构成实现授权，也不修改 `.alice-supervision/active-plan.md`。只有 Alice 项目监督员可以决定证据是否充分、把结论写入 active plan，并在用户批准后授权实现。

**本报告不构成实现授权。**
