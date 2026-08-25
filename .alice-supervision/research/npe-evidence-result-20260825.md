# NPE 佐证证据采集结果（F1-F6 定案佐证，B3 候选核查）

- 执行：Alice 主开发（session-30b693e7）
- 日期：2026-08-25
- 基线：`709bc5a`（F1-F6 fixture 已实施验收后的 HEAD）
- 性质：**只读证据采集**——未改任何业务实现（BotPlayer/FakeConnection/SoftMovementPrimitive/BotManager 零改动）、未改 BotPhysicsAssertionFixture、未判定根因、未实现修复
- 依据：任务书 `.alice-supervision/research/npe-evidence-task-20260825.md`；active plan 区分表 §2.3（B3 定案需 NPE 专项日志佐证）

## ① 运行期 stderr 查证（首查：printStackTrace 走 System.err，不进 latest.log）

**结论：F1-F6 断言运行窗口内 stderr 捕获为 0 字节（无任何 NPE 栈输出）。**

- 捕获方式：重跑 headless `./gradlew runServer -Dalice.selftest.auto=true`（200s 窗口），stdout 与 stderr **分离落盘**（`stdout > /tmp/f1f6-stdout.log 2> /tmp/f1f6-stderr.log`）——这是首次完整捕获 stderr（既往运行 `2>&1 | grep` 过滤已丢弃 stderr）。
- stderr 字节数：`wc -c /tmp/f1f6-stderr.log` = **0**
- stdout 佐证（透传成立证明）：本窗口 fixture 完整运行并输出 `BOT_PHYSICS_ASSERTION_SUITE FAIL corr=a49052a6 tick=101 f1=false f3=false f4=true f5=true f2=true tickChain=false`（stdout 236,851 字节，含全部 fixture 日志）——证明 gradle JavaExec 子进程 stdout 透传正常；stderr 与 stdout 走同一透传机制，0 字节可信。
- stdout 中无异常栈混入：`grep -cE '^\[[0-9:]+\].*(Exception|Error)'` = 0。
- latest.log / debug.log 交叉佐证：最近窗口 latest.log（行 117-141）含 `BOT_PHYSICS_*` 日志、`soft_phys_disruption/soft_phys_revalidate` 佐证行，**无任何 NullPointerException / printStackTrace 记录**（grep 计数 0）；run/logs/debug.log 同 0。
- 时间窗对齐：stderr=0 与 F1/F3/tickChain FAIL（corr=a49052a6, tick=101）同一运行窗口内发生——若 BotPlayer.tick() 链内 aiStep/pushEntities 抛 NPE，printStackTrace 必须在本窗口 stderr 出现；实际 0 字节。

**证据分级**：
- 已确认事实：本 headless 窗口 stderr 0 字节、latest.log/debug.log 无 NPE 栈、stdout 透传正常。
- 推断（留监督员核）：gradle JavaExec 子进程 stderr 与 stdout 同机制透传且未在 capture 边界丢失（stdout 236KB 完整佐证透传链可用；理论上 gradle daemon 对 stderr 的缓冲转发无已知丢字节路径）。
- 待调查：本任务未覆盖真实客户端连入场景（Windows 实测 M2-M4）与服务器长期运行窗口——NPE 是否仅在特定 tick 上下文（如玩家交互/伤害链激活时）出现，需要进一步运行形态验证，**不在本包定案**。

## ② 代码级 NPE 候选点清单（只列候选，不写结论）

基于 1.20.1-47.4.10 mapped jar javap 反编译 + 源码行号。BotPlayer.tick() 的 `super.tick()` 链访问以下字段/调用，**当对应引用为 null 或前置步骤失败时可能 NPE**（供区分表对照 B3 候选）：

| 链节点 | 访问字段/调用 | 源码/字节码位置 | NPE 候选条件 |
|---|---|---|---|
| `BotPlayer.tick()` catch 边界 | `super.tick()` | BotPlayer.java:28-36 | 链上任意 NPE 被此 catch 吞掉，printStackTrace 走 stderr |
| `ServerPlayer.tick()` | `gameMode`（ServerPlayerGameMode.tick） | 字节码 :291 field | gameMode 为 null（注册前 tick） |
| `ServerPlayer.tick()` | `wardenSpawnTracker.tick()` | 字节码 :251 field | tracker 未初始化（构造路径异常） |
| `ServerPlayer.tick()` | `connection`（发送/状态同步） | ServerPlayer connection field | connection 为 null（`FakeConnection` 注入前 tick） |
| `ServerPlayer.tick()` | `containerMenu.broadcastChanges()` | 字节码 :846 field | containerMenu 为 null（玩家 inventory 未初始化） |
| `LivingEntity.tick()→aiStep()` | `level()` / `getBlockState` / `random` | aiStep 字节码访问云 | level 引用异常（被 remove 后 tick） |
| `LivingEntity.aiStep()` | `useItem`（ItemStack）/ `getItemBySlot` | aiStep 访问云（useItem×29, getItemBySlot×6） | useItem 或 slot 栈为 null |
| `LivingEntity.aiStep()→pushEntities()` | `level().getEntities(boundingBox.inflate(0.2))` | pushEntities 链 | 实体列表迭代中实体被移除（并发修改） |
| `LivingEntity.aiStep()` | `getSleepingPos()`（Optional）| aiStep 访问云（×5） | Optional 空态处理路径异常 |

**候选条件均为"可能 NPE 的字段/调用点"枚举，未断言哪一条是实际触发路径**（区分表职责）。

## ③ bot.tick() 驱动链核查

区分三条驱动链（均为已确认事实，结论谁成立留区分表）：

1. **`BotPlayer.tick()` 实体级驱动 = 原版 ServerLevel 实体循环**：`ServerLevel` 字节码确认含 `entityTickList` 字段（构造 :257），服务器主循环每 tick 遍历实体列表调用 `Entity.tick()` → BotPlayer.tick()（override）→ super.tick() 链。BotPlayer 经 `PlayerList.placeNewPlayer(FakeConnection, bot)` 注册进 ServerLevel 实体列表（BotManager.spawn:76），进入原版实体 tick 循环。
2. **任务层动作驱动 = `BotManager.onServerTick`（ServerTickEvent END）**：BotManager.java:345-353，每服务器 tick 遍历 BOTS → `session.tick(hazard)` → 当前 task.tick()。**这是任务动作（soft_phys travel/观测）的驱动源，不直接调 bot.tick()**。
3. **fixture 手动驱动**：`BotPhysicsAssertionFixture` 在 F3b/tickChain 中**手动调用 `bot.tick()`** 以显式执行实体 tick 链（aiStep/pushEntities 佐证）——这是测试辅助驱动，非生产路径。

**对照意义（只记录事实）**：任务层驱动（②）每 tick 直接调 `SoftMovementPrimitive.applyToward → bot.travel(...)`（SoftPathProbeTask:143-144），不经 `BotPlayer.tick()` 的实体链——F4 PASS（travel 摩擦衰减执行）证明该直接 travel 链运行完整；而 F3b/tickChain 手动 `bot.tick()` 驱动实体链后碰撞 flags 无变化（horizontalCollision=false->false）。两者并存的解释（B3 NPE 吞错 vs 实体链未含 pushEntities 效果 vs 其他）**由监督员按区分表定案**，本包不写结论。

## 证据分级汇总

| 项 | 证据 | 分级 |
|---|---|---|
| stderr 0 字节（F1-F6 窗口） | /tmp/f1f6-stderr.log wc -c=0 + stdout 236KB 透传佐证 | 已确认事实 |
| latest.log/debug.log 无 NPE 栈 | grep 计数 0（行 117-141 窗口） | 已确认事实 |
| ServerLevel 含 entityTickList（原版实体循环存在） | javap 字节码 :257 | 已确认事实 |
| BotManager ServerTickEvent 只驱动任务层不调 bot.tick | BotManager.java:345-353 | 已确认事实 |
| "stderr 透传未丢字节" | stdout 透传完整 + gradle 同机制 | 推断（需监督员认可） |
| NPE 是否在真实客户端/长期窗口出现 | 未覆盖（M2-M4 Windows 实测未执行） | 待调查 |

## 禁止项遵守确认

- 业务实现零改动：`git diff` 不含 src/main/java（BotPlayer/FakeConnection/SoftMovementPrimitive/BotManager/BotSelftest fixture 调用外）任何改动——本任务期间 fixture 文件亦零改动。
- 未判定根因（区分表执行方为监督员）；未实现修复；未把"latest.log 无 NPE 栈"误当否定证据（已按任务书要求首查 stderr 路径并交叉佐证）。