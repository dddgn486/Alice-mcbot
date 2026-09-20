# 真机第三轮（挖矿 A 路线）根因取证档案 —— 2026-09-20

> 用户口径：「这几次测试价值很高，一定要充分记录证据，把每一个问题仔仔细细地修复」。
> 本文是**证据台账**（原文片段 + 路径 + 数字），不是设计说明。设计裁决见 `docs/AI_DECISIONS.md` `D-361`…`D-366`。

## 0. 轮次与工件

| 项 | 值 |
|---|---|
| 世界 | `新的世界 (2)`（固定客户端） |
| 入口 | `/alice mine here`（零参数；测试物品口径见 `docs/MINE_SURVEY_PROTOCOL.md`） |
| 客户端日志 | `/mnt/d/JAVA_projects/worldedit-test/versions/1.20.1-Forge_47.4.10/logs/latest.log` |
| **崩溃报告** | `…/crash-reports/crash-2026-09-20_21.32.05-server.txt`（**本轮真正结束原因**） |
| 截图 | `…/screenshots/2026-09-20_21.31.33.png`（1 格宽沟渠、梯田地形、聊天栏 `决策层动作被拒绝：empty_reply`） |
| 归档无头日志 | `run/headless-logs/*.log`（电池各步） |
| 只读取证工具 | `tools/region-ore-scan.py`（存档普查：`--center/--radius/--y-range/--pattern/--list-y/--mined`） |

**教训（我犯的错）**：我把日志末尾 `ThreadedAnvilChunkStorage: All dimensions are saved` 读成"用户关了游戏"，
实际是 **server 崩溃**（`IntegratedServer` 内）。**下次必须先看 `crash-reports/` 再解释作业为何没有终态。**

## 1. 崩溃（P0）：移动契约三方不一致

```
java.lang.IllegalArgumentException: PLACE_STEP_AND_TRAVERSE requires one cardinal step, dy 0 or -1
  MovementSpec.validateDisplacement(MovementSpec.java:110)   ← 硬抛
  MovementSpec.<init>:43 ← PlannedMovementSpecs.toSpec:57 ← PathSession.startSegment:329
  ← PathRetryRunner.tick:132 ← MineBlockRunner.tickMovement:201 ← MineTask.tickOnce:380
  ← MineJob.mine:393 ← BotManager.onServerTick:1145
```

- **三方 2:1**：生成侧 `SurfaceMovementProvider` `for (int dy = 1; dy >= -1; dy--)` ⇒ 允许 `dy=+1`；
  校验端 `MovementSpec:110` 与执行工厂 `PlaceStepAndTraverseExecutionFactory:34` **都只接受 `{0,-1}`**。
- **引入时间**：`git log -1 -S "for (int dy = 1; dy >= -1; dy--)"` ⇒ **`bc5ffaa`（2026-09-19，`D-336`「斜向上升那一格」）**。
- **为何门禁没拦住**：`D-336` 只加了 `PlaceStepDiagonalCheckTask` —— **EXTRA 档 + 规划级**（只断言"能规划出来"，
  **从没构造过 `MovementSpec`**）。
- **真机触发配方（崩溃前最后 4 行）**：
  ```
  [MineRunner] walk_start target=463,79,317 stand=462,79,317 mode=TUNNEL feet=461,77,318
  [PathRetry] planned attempt=0 status=REACHED movements=2 cost=6.67 writes>=0/1
              from=461,77,318 to=462,79,317
  → CRASH
  ```
  bot 在**自挖沟底** `461,77,318`，计划站位点 `462,79,317`（高 2 格）⇒ 最便宜路径含"放一格 + 踩上去"⇒ 第一段即非法边。
- **已修**（`D-366`）：收回生成侧 `dy=+1` + `PathSession.startSegment` 把 `toSpec` 包 try/catch
  （降级为段失败 `MOVEMENT_CONTRACT_VIOLATION`，**绝不崩服**）+ 夹具改成**执行级不变量** + 门禁
  `rule_movement_contract_agreement`（23 条，4 注入全红）。**先红后绿**：回退 `dy=1` ⇒
  `place_step_diagonal` `failures=4 FAIL`；修复后 `checks=10 PASS`。

## 2. 掉刻（**未归因，不许猜**）

- 证据：`Can't keep up! Is the server overloaded? Running 2035ms / 2632ms / 2232ms or 40 / 52 / 44 ticks behind`
  （21:30:53 / 21:31:11 / 21:31:30）⇒ **落后 2～2.6 秒**。
- 三次的上下文（`grep -n "Can't keep up"` 前 3 行）：① 紧接 `任务创建: MineTask target=458,80,323`（选择+建任务那一 tick）；
  ②③ 紧邻 `[alice] 决策层：不动 …` / `决策层动作被拒绝：empty_reply`（**LLM 决策层**）。
- 候选（**均未定量**）：① `SEARCH_LIMIT` 重搜索（`descend_precondition=71143/71238/70952 status=SEARCH_LIMIT`，
  单次预算 `nodes=20000`）；② 决策层 LLM 若在 tick 线程同步往返 ⇒ 秒级卡顿；
  ③ `D-363` 的 top-3 精算（每次选择最多 3 次 `MiningPlanner`；早前实测单次 `nodes=2348 ms=69`）。
  ⚠️ 本轮日志 `精算` 仅出现 3 次且 `ms` 很小 ⇒ **当前证据不足以定它的罪**。
- **下一步 = 按子系统插桩 tick 耗时**（选择/精算 · 扫描分片 · 路径搜索 · 决策层），再谈优化。

### 2bis. **实测数字（`D-367`，2026-09-20 离线测得，不需要客户端）**

`mine_menu`（CORE）新增一组计时判据，实测（`run/headless-logs/*single_mine_menu.log`）：

```
[MineMenu] D-367 tick耗时：候选=6 · 选择(含 top-K 精算)=102ms · 成本场only=17ms · 扫描分片=17ms
           （tick 预算 50ms；真机掉刻 2035/2632/2232ms）
```

**已证结论（第一手数字）**：
1. **一次成本选择 = 102 ms ≈ 2× tick 预算（50 ms）** ⇒ **每次选择都会让服务器落后 ≥1 tick**；
   其中 `成本场 only = 17 ms`、**精算（top-3 规划器）= ~85 ms**（≈28 ms/次规划器，与早前 `nodes=2348 ms=69` 同量级）。
2. ⚠️ 这里**只有 6 个候选**；真机那一轮是 **`候选=91`** ⇒ 成本场 Dijkstra 的规模项按 ~15× 估计 ⇒
   真机单次选择**很可能数百毫秒**。这正是"选择 tick 掉刻"的直接机制。
3. **扫描分片 = 17 ms / 8192 格** ⇒ 单次分片不超预算 ⇒ **不是**主要嫌疑（但一轮里连续推进 12 次会有累积）。
4. ⇒ **`D-363`（我加的 top-K 精算）是掉刻的一个已证来源**（但 102 ms 不足以单独解释 2035 ms；
   **决策层 LLM 是否在 tick 线程同步往返仍未测**）。

**第二轮补充实测（同日）**：
```
[MineMenu] D-367 tick耗时：候选=6 · 选择(含精算)=126ms · 成本场only=16ms · 扫描分片=13ms
[MineMenu] D-367 tick耗时②：候选菜单构建=31ms
```
- **已排除**："LLM 同步阻塞 tick" —— `GoalDirector` 用 `CompletableFuture<LlmClient.Reply> pending`（异步 + 看门狗）。
- **已证**：`CandidateMenu.build(bot)` = 31 ms，而 **`GoalDirector:533` 与 `BotStateReport:29` 各建一次**
  ⇒ **每个 PROGRESS 事件 ≥2 次菜单构建**；真机菜单含多目标全扫（夹具注释原文："重复构建会在一个 tick 里
  白烧掉百万次读"）⇒ 客户端会成倍放大。
- ⇒ **掉刻 = 多个超预算子系统之和**，量级最大的是 **选择(~100-126 ms) + 菜单构建(≥2×)**；
  真机 `候选=91` 时选择项还会更大。**不是单一元凶**（与 §2 的"不许猜"一致）。

**已做（`D-368`，同日落地，见 `docs/AI_DECISIONS.md` 同名条目）**：**摊销精算**取代"固定 top-3"——
每次选择最多 1 次规划器 + 跨选择缓存（TTL=200 tick，失败也记账）⇒ 覆盖逐次增长到**全部候选**。
**实测**：`[MineMenu] D-368 tick耗时③：摊销后（热缓存）一次选择=26ms vs 冷启动=99ms（tick 预算 50ms）`
⇒ 稳态回到预算内。判据 4 条 + **4 注入全红**（其中"失败不记账"因判据太弱第一次没红 ⇒ 已收紧）。

**原设计（下界分支限界）被否**：下界弱时"剪枝 + 上限"不保证真最优（反例：下界 5/6/7 而真代价 50/51/1），
且对"没有站位点"的候选要可采纳就得引入 reach 松弛量 = 自己发明公式（内核禁止）⇒ 记录在 `D-368 §五`。

**仍未做**：① **菜单构建同 tick 复用**（`GoalDirector:533` + `BotStateReport:29` 各建一次，31 ms×2/事件）；
② 缓存失效改用 Job 写入计数当"世界版本"（现在靠 TTL）；③ 选择期 Dijkstra 与规划器内部站位点搜索重复计算。

**判据**：`掉刻归因：一次选择(含 top-K 精算) 的耗时必须有界（实测 X ≤ 200ms）` —— 只做**数量级护栏**（不 flaky），
数字走 `[MineMenu] D-367 tick耗时…` 一行日志（绿了也能复核）。**候选=6 下已 102 ms，说明护栏 200 ms 只是防失控，
真正的整改方向是让"选择"不再吃掉整个 tick（缓存/降 K/移出 tick 线程，待设计，不许打补丁）。**

## 3. 掉落物没捡：**落在"走不进去的洞"里**（用户亲眼确认 + 存档印证）

```
[CollectDrops] cluster_start anchor=457,77,317 members=1 items=5
[CollectDrops] sweep_start  anchor=457,77,317 members=1 feet=461,77,317 worldMod=false   ← 纯通行
[PathRetry] plan attempt=0 status=SEARCH_LIMIT cost=Infinity nodes=20000 moved=124393
[PathRetry] plan_failed attempt=0 status=SEARCH_LIMIT
[CollectDrops] retire item=360b33e8… reason=MOVEMENT_FAILED itemPos=457,77,317 stack=5 inRange=false
```
- 存档普查（`region-ore-scan.py --pattern "." --list-y 77`）：bot `461,77,317` ↔ 掉落物 `457,77,317` 之间
  **x=458/459/460 全是石头**；`457,78,317` **也是石头** ⇒ 掉落物处在 **1 格高（无头位）口袋**；
  另两个在 `459,80,322/323`（高 3 格、无阶梯）。
- 用户口径：「那些没捡的掉落物，准确来说，**落在不能直接走进去的洞里**」⇒ 与存档一致 ✅
- 结论：**收集器按设计是纯通行**（`worldMod=false`，`PathRequest.of`；`D-076` 要求调用方显式授权才可改世界）
  ⇒ 这类格**永远进不去** ⇒ `SEARCH_LIMIT` ⇒ 退役。**洞是 bot 自己挖出来的**（只挖目标那一格、没挖头位格）。
- **待裁**：是否给收集器**有界**的世界修改权（挖/搭）去够这类掉落物；或先只做**归因细分**
  （头位不足 / 需上行 1 格 / 无路），现在一律 `MOVEMENT_FAILED` 下游分不清。

## 4. 簇没挖完：**三个独立成因**（本轮被崩溃截断，权重需按此读）

已挖 16 格；存档 26 邻域仍相邻的矿 = **15 格**，全部成层：`y=81`×5、`y=76`×5、`x=463/464`×5。
逐格查它们在本轮日志中的状态：

| 剩矿 | 决策状态 | 成因 |
|---|---|---|
| `456/457,76,318/319/320`（5） | **整轮从未出现** | **R1 覆盖缺口** |
| `458/459/460,81,322/323`（5） | **整轮从未出现** | **R1 覆盖缺口** |
| `463,80,316` `464,79,316` `464,80,316` `463,79,317` `463,80,317`（5） | 每格 3 次 `rejected…:higher_cost`；**前 4 个从未被规划**，第 5 个就是崩溃那一步 | **R2 选择排序** |

- **R1（已证，代码+日志）**：`MineJob.select()` 里 `session.advance()` **只在 `selection.picked() == null` 时调用**
  ⇒ **只要有任一候选可挖，扫描就不推进** ⇒ 候选集冻结在第一个分片。本轮 `分片推进` **只出现 1 次**
  （`visited=8192/117649`，7%），`dyAt` 序 = `0,+1,−1,+2,−2` ⇒ 只有起点附近几层可见。
- **R2（机制已证，因果待探针）**：`cells=0（所有候选都没枚举出站位点） 精算 尝试=3 成功=3（候选 91）`
  ⇒ **91 个候选里只有 3 个拿到真实成本**，其余"估不出"排在最后 ⇒ 只要那 3 个里还有能挖的，脉内其余矿**永远排不上**。
  这是 `D-363` 的 top-K=3 上界带来的退化（我引入的）。
- **R3（日志证据）**：站位点选择本身也绕远：`target=458,80,323 → standingFoot=457,79,326`（3 格外）；
  `target=463,79,317 → stand=462,79,317`（落进矿层）。
- ⚠️ **权重修正**：作业是**被崩溃截断**的 ⇒ "没挖完"含**截断**成分；R1/R2 是**覆盖与排序**层面的真实缺陷，
  但不能单凭此轮断定"永远挖不到"。

## 5. 寻路"不优"：用户口径 = **绕远 / 来回折返**（不是"挖沟本身"）

- 量化（本轮）：破坏 **52 次 = 16 次目标矿 + 36 次挖路**（`mine-runner:attempt0/2`）⇒ **挖路:挖矿 = 2.25:1**；
  `pathSize` 出现 **19 / 20 / 21**；`mine_in_place`（`D-365`）**生效 11 次**。
- **用户纠错（重要）**：我原以为"垂直能力被禁 ⇒ 会挖一排斜坡"是**错的**。用户原话：
  「他的结果**不是挖斜着的楼梯，而是跑到了很远的第一个同层可站点，然后水平挖过去**」。
  ⇒ 正确因果：垂直方向没有合法动作 ⇒ 路径必须去找一个**目标同层的入口**，而该入口可能很远
  ⇒ **远绕 + 一段水平隧道**（截图里那排沟渠就是这么来的）。
- 结构性因素：`PathRequest.miningApproach` 原**显式禁用** `PILLAR`/`FALL`/`DOWNWARD`；
  **`D-366b`（用户裁定）已放开**（「先取消挖矿的 Movement 禁用，能用之后再调整风险管理策略」），
  让步范围仅限该工厂，破坏/放置仍走预算闸门，**回收条件已写进注释 + `D-366`**。

## 6. 我自己的错误与纠正（逐条留档）

| 我说过 | 实际 | 纠正方式 |
|---|---|---|
| "用户关了游戏" | **server 崩溃** | 先读 `crash-reports/`；已写入本文 §0 教训 |
| "簇内顺序 = 扫描发现顺序（方环序）" | `Cluster.members` 本身按 `(y,x,z)` 排 ⇒ 真实原因是**层优先坐标序** | 反向对照暴露了它，判据改成 T 形簇（图距 0,1,2,3,4→**1** 的横跳必红） |
| "`dy=+1` 崩溃可能是我的 D-362…365 引起的" | 生成侧是**昨天 `bc5ffaa`/`D-336`** 加的 | `git log -S` 定性 |
| "垂直能力被禁 ⇒ 会挖斜坡" | 实际是"**远绕到同层入口再水平挖**" | 以用户观察为准，已作废并记录 |
| "删掉执行级不变量"注入没红 | 我的断言只查方法定义存在（太弱） | 收紧为"定义+调用点同时存在"，重验转红 |

## 7. 待修清单（按用户优先级 + 严重度）

| 序 | 项 | 判据计划 | 预估 |
|---|---|---|---|
| 1 | **掉刻归因**（2～2.6 s） | 按子系统插桩 tick 耗时（选择/精算·扫描·搜索·决策层），先量后改 | 只读探针，一轮客户端 |
| 2 | **R2 选择排序**（绕远折返的一半） | 只读探针打印：候选总数/有限成本数/top-3 身份/被压下的最近候选成本 | 一轮客户端 |
| 3 | **R3 站位点**（另一半） | 探针打印"岗位点 vs 就地可挖"；判据 = 就地可挖时**不许**走去站位点 | 夹具 + 探针 |
| 4 | **掉落物落进洞** | 先做 `unreachable` 原因细分；再议有界世界修改权 | 归因（不需要客户端） |
| 5 | **R1 覆盖** | 已挖格 26 邻域的目标必须进候选（不动扫描预算） | 夹具可在 CORE |
| 6 | **`D-366b` 风险补偿待验** | 放开后"跳下/搭柱"是否触发风险层；无则补 | 一轮客户端观察 |

**测试口径**：用户说「客户端等后面几个修复再测试」⇒ 上面 1～5 尽量在**离线（夹具/探针/确定性电池）**内先做，
到需要真人观察或拍板时停下等用户。
