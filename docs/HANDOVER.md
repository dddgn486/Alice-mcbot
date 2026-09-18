# 交接断点（HANDOVER）—— 2026-09-15 10:20 收口

> **本文件只是"当前断点"，不写历史**（历史在 `git log` 与 `docs/AI_DECISIONS.md`）。
> 会话恢复时**权威入口仍是 `docs/AI_PROJECT_STATE.md`**（AGENTS.md 的会话协议里列的是它）。
> 本文件 2026-09-14 从 300 行压到 ~70 行：旧内容（S0–S4 逐轮细节、R1 收口）全部可在
> `AI_DECISIONS.md` / `OPEN_ITEMS_LEDGER.md` / `git log` 里查到，按 AGENTS.md「规则准入尺子」第 3 条
> （只放指针，不抄原文）删除。

---

## 1. 一句话现状（2026-09-15 上午）

> **⏭ 本轮客户端待验证（2026-09-18，最新）**：**保护区 3/3 地图细化（`D-315`）+ 4/4 FTB Chunks 只读兼容（`D-316`）**
> 都已实现并**已同步到固定客户端**（⚠️ 协议有变 ⇒ **必须重启客户端**再用）。
> 入口 = 零参数物品 **`alice:protection_selector`** 右键 ⇒ 以你为中心的区块网格：
> 每格 **3×3 子格地形 + 高度明暗**（`G` 关明暗）、边缘区块刻度、悬停显示「区块/状态/地表方块/高度」、
> **紫框 = FTB 认领**（只读；本地认领是红框）。**看什么、日志怎么读、反证与 FTB 验收脚本（4 步）**，
> 见 `docs/TESTING_GUIDE.md` **§2.5 / §2.5.1**。已装工件 `alice-1.0.0-1.20.1.jar`，内容摘要
> `JAR_CONTENT_SHA256=297ab1553d02ff7cb5edb903cbd28c7c4cbe66f6a20ae178215f74ff303414fb`（同步于 2026-09-18 15:5x；
> jar **不可字节复现** ⇒ 认"同一版代码"要用 `tools/jar-content-hash.sh`，实测与 `build/libs` 一致 ✓）。
> 离线侧已绿：`module:protection` **73/73** · CORE **49/49**（步序 diff=0） · `check-all.sh` **17 PASS + 0 WARN**（含无头断言）。
> ⚠️ **日志证明不了的两件事**：① 界面观感（明暗好不好看、3×3 够不够分辨）；② **`available()=true` 那条路从未跑过**
> （本环境取不到 FTB 工件）⇒ 反射签名对不对、以及「FTB 的保护会不会拦住我们的假人」，**第一次真跑才知道**。
> 🔧 **`D-317`（客户端首帧崩溃）已定根因并修**：`ProtectionMapGeometry` 当时有两个**同名** `keyAt` 重载，
> 而 `render(...)` 的鼠标参数是 `int` ⇒ Java 重载解析把**像素当格号**用 ⇒ 渲染线程越界。修法 = 两个空间两个名字
> （`keyAtCell` / `keyAtPixel`）+ 判据 + **门禁「几何类不许有同名重载」**（注入即红）。⇒ **请用同步后的新 jar 复跑**。

> **2026-09-16（本日进展）** —— **D-244 水柱省料分支已落地**：`PILLAR` 在"起点格与目的地格**都是水**"
> 时改为**上浮、不放方块**（对齐 Baritone `MovementPillar.java:150-161` + `:77-82` 的 `LADDER_UP_ONE_COST`），
> 完成口径随之改成 Baritone 的"**脚位到格即成功**"（水里没有 `onGround`/支撑 ⇒ D-026 的"已落地"永不成立 ——
> 这是 D-242「规划得到、执行不了」的另一半根因）。另新增**唯一**水谓词 `MovementHelper.isWater(Level,BlockPos)`
> 并收口 `AscendExecution`/`SurvivalSystem` 的 4 处内联副本。
> 判据（既有 `FLOODED_SHAFT`，**零新增电池步**，checks 112 → **118**）：脚位在水里时**一次都没放方块**
> + **干地反向对照**（干燥竖坑仍靠放置上来）+ 前提自证三条。**实测比预期更强：整段逃生零放置**
> （`PILLAR` 水柱上浮 11 tick + `ASCEND` 从水里跳上干地板 14 tick）⇒ **D-242 的结论落地验证**：
> 水里自救**不是授权问题**（准备金只是让"写类 Movement"能被规划出来，额度一分没花）。
> 顺带修掉夹具缺陷：`fillBlocks` 在区块未加载时 `/fill` **静默 0 改动**（本轮真踩到一次，一次红了 8 条判据）
> ⇒ 现在先 `areaLoaded` 检查、未加载就把 bot 传到区域中心。
> **D-264（同日，S-8 收口：删掉零读者的 `policyVersion`）**：按用户裁定「删字段」—— `LiveExecutionContext` 与 `PlanningDependency` 的同名位一起删除（12 个构造点同步），`grep` 归零、编译通过、CORE PASS；新门禁 **S8-P1**（不得无声复活，反向对照实测可红）。顺带发现并登记 **S-10**：`PlanningDependency` 的分量（6 个列表 + `worldRevision`）**没有任何读取者**（未修，二选一需裁定）。
>
> **⏭ 任务队列唯一口径（2026-09-17 勘测分诊后）**：**`docs/reviews/2026-09-17-勘测分诊与任务队列.md`**（含 10/11/12/14/15/16 六份勘测的逐条分诊 + A/B/C/D/E 分档 + 队列 + 6 个待拍板裁定）。本日已做：142 客户端验证（`[Job] launch … quota=2` ✓）、R2-残 收口、J-3 补全（4 个生产 Job + 门禁 R4）、survey10 #37 数据采集（`[PathRetry] plan` 首次真实数据：短程 nodes=54/10、ms=1）。
> **⏭ 昨日指针（已并入上述队列）**：① **S-6** —— `RiskSwitches` 全局静态 → **按 bot 冻结的 `RiskProfile` 容器**（字段先只放现有 1 个开关，消费者逐步迁移；**不做** Job 候选筛选）；② **S-9** —— 危险伤害改由**伤害事件**观测（Forge `LivingHurtEvent`/`LivingDamageEvent`），**先出方案再做**、配可红判据。尚未裁定：**S-10**（`PlanningDependency` 删或接）。等用户客户端轮次：**105/107** Baritone 对照（唯一真验证债，须标注 Alice 多开两个守卫）、**142** 一次零参数右键 `alice:job_launcher`（看 `[Job] launch` 是否出现）。
> **D-263（同日，J-10 `policy_blocked`：仪器化已接、判据到不了 —— 诚实边界）**：`CollectDropsTask` 的 `retire` 现在把「策略不放行」单独计数/告警/进 `SUMMARY policy_blocked=N` 与 `terminalReason()`（D-134 通路）；但**判据未成** —— 夹具里让主动收集去收 FOREIGN 掉落物时任务 `entities=0/0 ticks=1`（成员按作用域发现 ⇒ 外来不入集合）⇒ 期望分支不可达（相位已撤）；可达场景只剩「跟踪中的掉落物中途被撤授权」（需新夹具）。⚠️ 另发现 **`single:pickup_gate` 单跑本就不成立**（基线同样 FAIL ⇒ EXTRA 步入口不可靠）。
> **D-262（同日，矩阵 22 行「待测」逐条核证）**：16 行改判（含 6 行 `USER_ACCEPTED`）、5 行仍待测（Baritone 对照 105/107 = 唯一真债）、2 行失效（138 条目已废、211 机制已删）、1 行 `SERVER_TESTED`；并把 **4 处自相矛盾**（顶部权威表 vs 历史行）写进矩阵注记。另：**J-1/J-3 收口** —— `taskKind` 改用 `Task.taskName()`（`stableTaskKind` helper）+ 新门禁 `check-exec-record`（R1 taskKind 必须可追溯到 `taskName()`、R2/R3 `terminalReason`+`botId` 必须进快照与记录，两类注入实测都能红）；`llm_contract` 补判据 `snapshot_terminal_identity`。
> **D-261（同日，`survival_exit` 火焰判据重建 = 天生易红 → 确定性）**：CORE 偶发红（1/5）根因 = **窗口只覆盖 1 个伤害节拍**（21 tick 窗口 vs 20 tick 节拍）+ **回血恰好抵消火焰伤害** ⇒ 「采样净血量」这条观测**本质上不可判定**（食物清零也关不掉回血 = `aiStep()` 无条件 heal）。修：窗口 ≥3 拍（`max(GRACE+6, 3×20+5)`）+ 前提判据「窗口必须覆盖 ≥2 拍」+ 换用 `hurtTime` **上升沿**命中计数与「命中时 fireTicks>0」归因判据（都与血量无关）；删死代码 `hasFireDamageEvent`。**证据**：单步 PASS ×4（`checks=124 failures=0`，`新命中=4`）、**反向对照**（窗口改回 21t ⇒ 双红）、CORE PASS。**新登记 S-9**：生产侧危险事件按血量差记录 ⇒ 被回血抹平的伤害不可见（未修，需立项）。
> **D-260（同日，G3/R1-残 收口 = 连锁破坏预算拒绝可上报）**：`chainRefusedByBudget` 死哨兵接进 `MineTask.terminalReason()`（D-134 既有通路 ⇒ `task_terminal_reason` 日志 + 决策快照）；夹具新增 `exec_chain_budget_refused`（`setCaps` 压到 1 ⇒ 确定性触发，无需 65 格场景）。判据：`single:mine_regression` PASS + **反向对照拆消费者 ⇒ FAIL** + CORE 全绿。**边界**：任务结局语义未改（实测仍 `status=DONE`：目标确实挖掉了，只是连锁被砍短）——是否升级为失败属产品口径，未做。
> **D-259（同日，R5-残 收口 = 菜单写入原语编译期强制）**：`WriteReason.CRAFT_GRID` + `menuWrite()`（合成网格**不吃容器预算**但要显式交理由）；`InventoryCraft.click`（两重载）与 `StationProvision.click` 强制 `WriteGrant` 形参并校验；5 个调用点交出 `CRAFT_GRID`；注册表三行 why 更新。**判据**：① 调用点去掉 grant ⇒ **编译失败** ✓；② `check-policy-matrix.sh` 新增 ⑨「每个 click 重载必须带 `WriteGrant`」（⚠️ 第一版只查一处被另一重载顶绿，实测后改为逐重载+平衡括号）⇒ 注入即红 ✓；③ **CORE PASS**（`craft_action`/`craft_table`/`craft_station_craft`/`craft_station_provision`/`craft_furnace`/`machine_route` 全绿 = 无误杀）。边界：不改任何预算计数，`CRAFT_GRID` 不进 `WritePolicyMatrix`（矩阵仍 24 行）。
> **D-258（同日，审计表全表复核 §1/§3/§4/§5/§6，36 行）**：4 个**只读**子代理并行核证据 + 父 agent 交叉复核。**14 行「未实现」作废**（S-5/J-1/J-2/J-4/J-6/J-7/J-9/G3/G6/G7/G-新/D-3/D-4/D-5）、12 行部分失真、5 行真缺口保持（S-6/S-7/S-8/J-5/V-4）、5 行准确。**两条新发现**：① **菜单合成绕过容器写入预算与策略矩阵**（`InventoryCraft:300`／`MenuSession:256` 直调 `menu.clicked`）；② `chainRefusedByBudget` 只写不读 + 无 chain 电池步。另修正 `docs/reference/BARITONE_PORTING_CHECKLIST.md:21` 的过期常量（`maxFallHeightNoWater = 1` → 代码实为 `FALL_DROPS={2,3}`，会污染对照结论）与 §0 一览的数量矛盾。**未动**：三份历史报告原文、矩阵 22 行「待测」的逐行改判、V-4 的 Baritone 对照实验。
> **D-257（同日，内核审计 §2 收口）**：**K-3/K-5/K-6 都是过期行**（K-3 `safeToCancel` 全链早已实现 + 有 `k3_stop`/`capability_gate` 判据；K-5 `POSTCONDITION_FAILED` 有生产者（三条码映射）；K-6 里世界变化检测/不推进硬失败其实已实现，其余记为**有意省略**）；**K-4 是真缺口** —— TRAVERSE/DIAGONAL 工厂缺 `canSweepPlayer` 扫掠 ⇒ **执行比内核宽松**。修法：四个干净族工厂改用规划侧同一谓词（`canTraverse`/`canAscend`/`canDescend`；DOWNWARD/BREAK_*/PLACE_STEP 有意不并）。新门禁 `check-kernel-predicates.sh`（K4-P1 谓词统一 / K5-P1 死状态），两类注入都实测能红；**CORE `(38/38) ticks=4300 → PASS`**（= 收紧后没误伤任何真实计划）。边界：缺运行时「越界边必须被拒」判别场景；K-6 的省略项无判据。
> **D-256（同日，K-1 收口：best-so-far 前缀）**：先纠正过期台账 —— K-1 **早已实现**（`AStarMovementSearch:213-227` 交 `PathPlan.partial`、`PathRetryRunner:65-80` 消费、BASELINE 步 `partial_search` 在 CORE PASS），K 行原「未实现」作废。本轮补两个**真洞**：① **前缀绕过了 D-250 计划自洽校验**（前缀会被执行 ⇒ 现在也校验，冲突时用 `SelfWriteConsistency.safePrefixBefore` **裁到冲突之前**，绝不交出会执行非法边的计划）；② **前缀最后一段被当成目标段**（容差收敛到 `PathSession.toleranceFor(finalSegment, planReachesGoal)`，只有真到达才 `EXACT`，K-4 统计也只对目标段生效）。顺带修一个**夹具违规**：`partial_search` 原先依赖电池前序步骤把 bot 摆到好地形（单跑整组红）⇒ 现在自传送 + 新场景 `partial_search_terrain`；场景坑已记入文件：走廊跨 3 区块时**大范围 `/fill` 会整条失败**。判据：`partial_search` 7 条，**四条反向对照实测能红**。门槛：`single:partial_search` PASS、**CORE `(38/38) ticks=4261 → PASS`**、`check-all.sh` 10 PASS + 1 预期 WARN。对照 Baritone 已实地核对（`bestSoFar[]`/`bestPathSoFar()`/`AStarPathFinder:196`；**没有** PARTIAL 枚举 ⇒ Alice 是显式状态的有意偏离）。
> **D-255（同日，§5.9 的 C1：人工确认解除通道，用户裁定「甲」）**：`/alice transfer-resolve <request> confirm`（必须打全 `confirm`）⇒ `resolveManual` 落 `ABORTED`/`resolved_by_operator`，证据带**只读对账**（实测 `botHeld=1/1 item=minecraft:iron_ingot`）与**谁解除的**；**物品零移动**。`transfer-abort` 那条路保持保守（继续挂起保护）。判据全在离线层：既有 `transfer` 步的内存账本语义 + **端到端真实命令通道**（世界账本 `NOT_MOVED` 探针 + 负向对照「没打 `confirm` 不许解除」）+ 结构规则 R3；反向对照两类实测能红（翻转 `resolveReleases` ⇒ FAIL；删 `confirm` ⇒ 端到端 FAIL + R3 红）。**⚠️ 反向对照的注入本身也必须能编译**（有一次括号不平衡 ⇒ battery `exit=5`＝环境/脚本错误、**没有判决行** ⇒ 不能当「判据红了」的证据）。门槛：`single:transfer` PASS、**CORE `(38/38) ticks=4277 → PASS`**、`check-all.sh` **10 PASS + 1 预期 WARN**。**§5.9 epic 至此收口**（唯一保留项：没人确认时 `BOT_INVENTORY` 挂起仍永久阻塞 = 有意保守口径）。**不需要客户端验证**（用户 2026-09-16：不要让客户端做不必要的测试）。
> **D-254（同日，§5.9 收口：传输挂起的时钟与门禁）**：① **唯一时钟** —— 落章用世界时间而超时判定用 `getTickCount()`（进程内计数、重启归零）⇒ 差值恒为负 ⇒ **运行中产生的挂起永不过期**（`manual_takeover_required` 降级是死代码，`BOT_INVENTORY` 挂起 = 没有解除手段的永久阻塞）。现在统一 `TransferLedgerData.clockNow` + 只能传服务端的重载（4 处调用点 + `TransferTask` 落章）。② **门禁接线判据**：`refusal(ledger, botId)` 纯函数（生产与夹具共用）；⚠️ 台账原提案（在 `transfer` 步里真调 `assignWalkTo`）**不能照抄** —— 那会 `clearTask()` 掉**正在跑的电池步自己**；改为内存账本判据。③ 新门禁 `tools/check-transfer-clock.sh`（R1 时钟混用 / R2 替换型派活过门禁），**反向对照两类都实测能红**（并因此抓出两个假绿：参数表按第一个 `)` 截断、方法体按固定缩进切分把签名行当证据）。门槛：`single:transfer` PASS、**CORE `(38/38) ticks=4260 → PASS`**、`check-all.sh` **10 PASS + 1 预期 WARN**。**未做（待用户裁定）**：`BOT_INVENTORY` 挂起的解除通道（口径放宽）。
> **D-253（同日，水位 epic 收口）**：真人复核通过（用户「符合我的预期」）—— 客户端日志佐证：`[Survival] checks=122 failures=0 → PASS`、`[Regression] pathing… 0 条 FAIL`、`deep_pond_course=COMPLETED`、`water_course+cost=17.50/expected=17.50`、`PREMISE_FAILED=0`。⇒ **D-241/242/243/244/245/247/250/251/252 升级为 `WINDOWS_CLIENT` + `USER_ACCEPTED`**（矩阵已同步）；**§5.11 水位 epic 关闭**（IN 四条全达成；OUT：游泳/落水免伤/水柱成本/水面理由码；已知边界：池底出发 `UNREACHABLE`、入水物理、水面以下水平潜游）。下一步按「同时 1 个活跃 epic」只挑一件。
> **D-252（同日，客户端 `pathing_regression` 失败 → 是数据包陈旧，不是 mod 回归）**：客户端存档里的场景数据包是 **09-15 的手工拷贝**，缺 D-247/D-248 新增的 `water_course_terrain` / `deep_pond_course_terrain` ⇒ 那三条判据（`water_course` / `deep_pond_course` / `water_course+cost`）**根本没有地形** ⇒ `MISSING=[TRAVERSE]` / `cost=Infinity`。无头电池每轮 `cp -r` 刷新数据包所以全绿；夹具又用了抑制输出 ⇒ `/function` 失败**一字不打**。**两条修法**：① 夹具看 `/function` 返回值 + 加「起点可站」几何前提（`PathingRegressionTask.prepare`、`SurvivalExitCheckTask` 的 `survival_sealed_course`）⇒ `checks 120 → 122`；② `tools/sync-windows-artifact.sh` 新增数据包刷新（第 4 参数 / `ALICE_CLIENT_WORLD`，备份 + 函数份数校验 + 提示 `/reload`）。客户端数据包已刷成 111 个函数。**⏳ 仍需用户重跑一次**才算 `WINDOWS_CLIENT`。
> **D-251（同日，水位切片 B 落地 + 两个真 bug）**：`canWalkOn` 水位支撑例外（只认水面格）+ 浮着完成口径推广到 `TRAVERSE/DESCEND/ASCEND` + 唯一化 `isFloatingDestination/isAtFootCell/shouldHoldJumpInWater`（对照 Baritone `MovementTraverse:243-248`）。**顺带挖出两个「水里没有落地」的真 bug**：① 段间 settle 一律 `stopMovement()` ⇒ 水里松手 ⇒ 下沉 + 被邻格流过来的水推走 ⇒ 下一段 `STALE_START`（**这才是 D-248 回退现场的真因**）；② `handleFailure` 的「空中不处理」守卫 在水里**静默吞掉失败** ⇒ 会话空转（深水池实测空转 ~57 tick，期间没人按跳）。修后 **`deep_pond_course` 从现状登记 `refused` 升级为执行判据并 `COMPLETED`**（3 格深、纯通行、零写入；水面 12/11 tick vs 陆地 5 tick）、灌水逃生 **DONE**（水里零放置）。门槛：`single:pathing` / `single:survival_exit` PASS、**CORE `(38/38) ticks=4229 → PASS`**（`自写入冲突=3`、`unresolved=0`）。**⏳ 收口待用户复核**：`alice:survival_full_check` + `alice:pathing_regression` 各右键一次 ⇒ 才算 `USER_ACCEPTED`。
> **D-250（同日，计划自我写入自洽性 / ②′，用户拍板）**：**"计划不许踩在自己稍后会挖掉的格子上"** 落成**计划级校验 + 有界重搜**：新增 `pathing/core/search/SelfWriteConsistency.java`（沿计划回放写入，**与执行器同一口径**：破坏格调 `BreakAndEnterExecution.collectBlockers`/`BreakAndTraverseExecution.collectBlockers`，放置格调 `PillarExecution.placePos`/`PlaceStepAndTraverseExecution.placePos`，后两者为此加了坐标版重载）；`CorePathPlanner.plan` 校验不过就**禁掉那条"清空者"边**（按具体边 `EdgeKey(类型,from,to)`，不按类禁）重搜 ≤3 次；`AStarMovementSearch` 只加**路径无关**的禁用边过滤。⚠️ 为什么不做在搜索里（D-250 实测）：A\* 用位置做节点键 ⇒ 更便宜的挖掘前缀占住节点、干净前缀永不重挂 ⇒ 按路径过滤会把灌水坑逃生从"可解"变成 `UNREACHABLE`（`own_write_support=245`）。**电池新增硬自断言** `selfwrite_unresolved == 0`（摘要带 `自写入冲突=N`）—— 这正是 D-248 那个 bug 的常绿守卫。实测：**修法单独** ⇒ `single:pathing` / `single:survival_exit` / CORE 全 PASS 且 `自写入冲突=0`（对今天零影响）；**修法 + 切片 B 实验** ⇒ 守卫按预期开火（`[SelfWrite] 计划不自洽… ⇒ 禁边重搜` → `重搜 1 次后计划自洽`），但**执行**仍失败（bot 从浮着的水面格被推回坑里，`ASCEND_STALE_START`）⇒ **切片 B 现在只卡在"跨段持续浮着"这一条**（需要单独拍板）。补丁留档 `/tmp/d250-selfwrite-experiment.patch`。
> **D-249（同日，D-248 那个 `Air` 查清了）：是我们自己的执行器破的**——失败轮日志从**轮转日志** `alice-server/logs/debug-3.log.gz`（16:06）取回：`[WRITE] break 340, 100, 305 … by=survival-escape:attempt0:PATH_ACCESS` + `[BreakEnter] cleared pos=340, 100, 305 index=1/2` + 探针 `ASCEND to=340,101,305 … 下方方块=Air` ⇒ `(340,101,305)` 的支撑正是被**同一条计划的 `BREAK_AND_ENTER` 自己清掉的头位格**（清脚位+头位两格，Baritone `MovementTraverse.java:56` 同款）⇒ **②「前序步骤动过」证实、①「未加载区块」排除**。四轮对照（16:02 单跑 PASS / 16:06 切片 B CORE FAIL / 16:13 回退 CORE PASS）证明差异在**计划形状**：切片 B 让上面的水格成为合法脚位，才多出「挖穿侧壁 → 升进水面格 → 升上地板」这条更便宜的路（其最后一段踩在自己挖空的头位格上）。真问题=**搜索不模拟自己的写入**（Baritone `CalculationContext` 同样没有；它靠执行期 `COST_INF` 重算，Alice 的 `futureTargetBlocked` 提前抓到，而重规划从被水推回的起点又算出同一形状）⇒ 三条修法待拍板（有界自我写入感知 / 计划级自洽校验+收窄重试 / 维持现状+补不需水位的复现场景）。**本轮不改代码**，只落盘结论。
> **D-248（同日，水位切片 B）：勘查做完、规划那半回退**——`canWalkOn` 水位例外（只认水面格）+ 三个执行器的浮着完成口径写完并在**单跑**全绿，但 **CORE 红**：逃生换走更便宜的"破墙 + 升到水面格"路线（6.55 < 6.67），其最终段在健康检查时刻读到的支撑是 **`Air`**（几 tick 前的落点自证还是石质 ⇒ 当时未解释，**已由 D-249 查清**）⇒ `SEGMENT_FUTURE_BLOCKED` ⇒ FAIL。按纪律**回退**（只留 D-247 + 两条现状登记判据 `refused(deep_pond_course[+floor])`）；落地时必须连**会话健康检查的谓词一致性**一起改。
> **D-247（同日，水位切片 A 完成）**：先做**判别性实测**才动代码 —— 新夹具场景 `water_course`（1 格深水沟**横跨全场** ⇒ 绕不过去）证明**蹚水今天就能走**（纯通行 `REACHED` + 5 段全 `COMPLETED` + 零写入），但实测**耗时与成本脱节**：陆地 5/7 tick vs 水里 **45/42** tick，而计划只按 1.0 计价 ⇒ ① 选路偏爱穿水 ② 段预算只有实际的 ~1/7。改法：`CostModel.WATER_TRAVERSE_MULTIPLIER = 7.25`（结构抄 Baritone `MovementTraverse:87-90`，**数值代入 Alice 实测**；照抄 Baritone 的 1.96 是给客户端游泳标定的）。判据 `water_course`（执行 COMPLETED + 零写入）+ `water_course+cost`（期望值**从计划自身推导**），**反向对照精确变红**；⚠️ 第一版期望值引用了同一个常量 ⇒ **自指**、反向对照不会红（已改成判据侧独立来源，与 D-241「重复来源要用门禁消」同源）。门槛：`single:pathing` PASS / CORE `(38/38) ticks=4068 → PASS`。
> **D-246（同日）**：§5.11 ②「出口列表」**判定基本为空 ⇒ 关闭不建** —— 纯通行档下「最近那个不可达」
> **等价于**「根本没有纯通行出口」（证明：路线的第一步目的地本身就是落点 ⇒ 距离 1 处必有可规划落点），
> 残留只有「等距并列 + 人为不可破几何」这一档，正常游玩构造不出来（尺子同 D-240）。
> **D-245（同日，用户裁定）**：逃生放置**不自动回收** —— 回收时机交玩家（`/alice restore`）；
> 补偿 = 负向门禁（`SHAFT_ESCAPE` 断言"逃生结束后方块仍在 + 账本仍 `TEMP`"，取反恰好两条变红）⇒ checks 118 → **120**。
> 门槛：`single:survival_exit` **checks=120 failures=0** ✅ / **CORE `(38/38) ticks=4024 → PASS`**（干地 PILLAR/ASCEND 无回归；
> 电池上下文里同一相位同样 `checks=118 failures=0`）✅ / `check-all.sh` **9 PASS + 1 预期 WARN** ✅ / 文档预算 **1472/1476** ✅。
> 客户端 jar 已同步：`dffd3f22…`（可选真人验：`alice:survival_full_check` 一次右键跑完全部 118 条判据）。
> **仍未做（下一步候选）**：① 水柱的**成本模型**（Baritone 给 `LADDER_UP_ONE_COST`，Alice 仍算 `PILLAR_COST`，只影响选路）；
> ② `PILLAR` 仍属**写类 Movement** ⇒ 纯通行档连"不写世界的水柱上浮"都生成不出来（过严但安全，要动就得碰 D-076/P-01）；
> ③ **浮在水面（无可站支撑）时起不来**（合法位置集问题，属"丙"的其余部分，最贵）；
> ④ ~~逃生放置的自动回收~~ ⇒ **已定案不做自动档（D-245：自动拆会把 bot 关回坑里 ⇒ 逃生循环）**，
> 回收交玩家许可（`/alice restore` / `alice:restore_check`），并已加**负向门禁**（逃生结束后方块仍在 + 账本仍 TEMP）；
> ⑤ **深水浮着（切片的 B 半；水平蹚水已由 D-247 做完）**—— **下一个候选**：
> 浅水（脚位是水但**底下是实心** ⇒ `canStandCentered` 本来就算过）今天被那一条硬排除挡掉，
> 放宽它就能蹚过水沟；深水（底下也是水）会被 `canWalkOn` 自动排除，不需要额外判据（对照 Baritone `MovementTraverse` 水支）；⑥ 落水（D-058 定案不做）。

> **2026-09-16（本日进展）** —— **D-243 水里垂直移动已落地**（"丙"的第一个子集）：
> `PillarExecution`/`AscendExecution` 在水里改为**按住跳跃上浮**（陆地那套一次性 `jumpOnce` 在水里抬不到 1 格
> ⇒ 实测 `wastedJumpLandings` ⇒ `SEGMENT_NO_PROGRESS`）⇒ **只动执行器那一支，不动合法位置集/成本模型**。
> 判据：`FLOODED_SHAFT` 的 tripwire（D-242 当时**故意断言"水里执行失败"**）按设计翻红后**翻成正断言** ——
> `水里逃生：终态=DONE` + bot 脚位 y ≥ 101 且 `inWater=false`（真的从灌水竖坑里出来了）。
> 门槛：`single:survival_exit` **checks=112 failures=0** ✅ / **CORE `(38/38) ticks=4033 → PASS`**（陆地 PILLAR/ASCEND 无回归）✅ /
> `check-all.sh` **9 PASS + 1 预期 WARN** ✅。
> **仍未做（下一步候选）**：① 整列是水时**不放方块**的省料分支（Baritone 水柱 `LADDER_UP_ONE_COST` 那支）；
> ② **浮在水面（无可站支撑）时起不来** —— 那是**合法位置集**问题，属"丙"的其余部分；③ 逃生放置的自动回收；
> ④ 水平游/蹚水（`SurfaceMovementProvider:127-130` 仍跳过流体目的格）；⑤ 落水（D-058 定案不做）。

> **2026-09-16（本日进展，最新）** —— 落地 **D-241 逃生准备金**（提案 B 第一步：信封轴 + `survivalEscape`
> + 策略表 P-23/P-24 + 阶梯用法 + 上限 8/8；判据 `SHAFT_ESCAPE` 竖坑真垫出来）与 **D-242 水里那档**。
> **D-242 改变了水里工作的排期（重要）**：灌水竖坑场景逼出——写准备金在水里**规划得到、执行不了**
> （`PILLAR` 执行器是跳起-落地式 ⇒ `wastedJumpLandings` ⇒ `SEGMENT_NO_PROGRESS`）⇒ 水里自救的正解不是写授权，
> 而是**"水柱"这一小块内核**：对齐 Baritone `MovementPillar.java:77-82` + 执行段"swimming up a water column"
> = **纯输入上浮、不放置**；**不需要**动合法位置集/成本模型（坑底"水+实心底"本就是合法脚位）⇒ 比整个"丙"小得多，
> 且**夹具已就位**（`FLOODED_SHAFT`，当前以 tripwire 记录"水里执行失败"）。
> **门槛**：`single:survival_exit` **checks=112 failures=0** ✅ / **CORE `(38/38) → PASS`** ✅ /
> `check-all.sh` **9 PASS + 1 预期 WARN** ✅。**下一步首选**：把 `PILLAR`/`ASCEND` 执行器在水里改成"上浮"（上句那一小块）。

> **2026-09-16（本日进展）** —— 用户批准了提案 B 的五条（**D-239**），并落地第一步 **D-241「逃生准备金」**：
> 轴 = **任务信封**（`pathing/core/WriteEnvelopes`，推导事实不维护名单）+ `PathRequest.survivalEscape`
> （放置 + 破坏 + `PILLAR`，**不含** `DOWNWARD`/`FALL`）+ 策略表 **P-23/P-24** + 阶梯用法（只在纯通行
> `UNREACHABLE` 时升档）+ 上限 **8 破坏/8 放置**。判据挂既有 `survival_exit` 新相位 **`SHAFT_ESCAPE`**
> （2 格深竖坑 ⇒ 纯通行 `exit=none`；带准备金**真的垫出来**：`PILLAR 330,99,306 → 330,100,306` ⇒
> `WalkToTask completed actualFoot=330,101,305`）⇒ **checks 87 → 99**，反向对照三条判据精确变红。
> 同日还判定 **B1「原路返回」无可测量收益、不落地**（D-240：`isRefuge` 与 `TRAVERSE/ASCEND` 目的地生成
> 共用同一组谓词 ⇒ "可通行且可规划"的格就是落点 ⇒ bot 的上一格永远是最近且可达的落点）。
> **未做（下一步候选）**：① 逃生放置的**自动回收**（TEMP 已声明、未接 `scaffoldRemoval`）；
> ② 水渠搭桥场景 + 预算上限守卫（竖坑一档已覆盖）；③ 准备金"从 64 里做减法"；
> ④ 台账 §5.11 ②（出口列表）/④（丙 内核水位）。
> **门槛**：`single:survival_exit` **checks=99 failures=0**（反向对照一次精确变红）✅ /
> **CORE `(38/38) ticks=3955 → PASS`** ✅ / `check-all.sh` **9 PASS + 1 预期 WARN** ✅。

> **2026-09-15 晚（本日收尾快照）** —— S-5 维生线一整天推进，全部走"判据必须可红 + 反向对照"：
> - **D-236 溺水不再静默**（`WINDOWS_CLIENT`）：溺水 + 无出口 ⇒ `ABANDON_NO_EXIT`（干净收尾 + 大声登记）；
>   封闭水牢判据（真建 17³ 水牢实测）。**"水里逃生"整条缺口如实登记**（台账 §5.11）。
> - **D-237「乙」= 溺水先上浮自救**：新判决 `FLOAT_UP` + `SurvivalFloatTask`（按住跳跃，头出水即松手；
>   纯输入，**不新增 Movement**）；实测 `上浮自救成功（tick=44 air=102 y=102.81）`。
>   ⚠️ **接线只观测到一次**：真维生判决会 `complete()` 掉**电池本体**（电池=会话任务）⇒ 那半只能真人验。
> - **D-238 出口必须是"可规划"的**：出逃生之前跑一次真规划预检（`UNREACHABLE` 才算没出口，`SEARCH_LIMIT` 按未知放行）；
>   软危险不再为"去不了的落点"杀任务，登记 `exit=unreachable`（区别于 `exit=none`）。
> - 同日另三件：D-233（`decision_contract` 提档 MAIN）/ D-234（四 Job 统一子阶段失败口径）/ D-235（挂起传输结清落盘）。
> - **门槛**：`compileJava` ✅ / `single:survival_exit` **checks=87 failures=0**（反向对照各一次精确变红）✅ /
>   **CORE `(38/38) ticks=3901 → PASS`** ✅ / `check-all.sh` **9 PASS + 1 预期 WARN** ✅。
> - **下一步候选**（台账 §5.11 四条，均未开工、各留一行）：水面专用理由码 / 出口列表 / **B 维生写授权（待用户拍板：
>   搭桥/垫柱子出水的受限写授权）** / **丙（内核水位位置 + 垂直水位移动 + 成本模型，最贵）**。
> - **客户端可验（可选）**：`alice:survival_full_check` 一次右键 ⇒ 跑完全部 87 条判据，含新的深水/露天水池/石盒三相。

**`T0-a → T0-b → T1 → T2` 已全部落地（T2 已客户端验证）。
T3 八步已走完七步：步骤 1 / B3a / A / A2 / C / (A) / **B4** 全部落地并实测；
**只剩"接第 3 个模组本身"（`MachineMap` 加 19 行）与 B3b —— 两者**均已被 D-219 推迟**（等真实存档需要）。
⇒ **T3 视为已收口**（用户 2026-09-14 认可）。

**2026-09-15 上午的工作：把"验证通道本身就是可信的"这件事做实**（D-220）。
原计划"`pathing` 起点必填契约"**被实测否掉**（改前 6/6 绿、场景行逐字相同 ⇒ 抖动不复现），
转而查到并修掉三处**可证**缺陷：夹具时机参数**从未生效**、`DIAGONAL` 覆盖**靠偶发绕行凑**、
**无头通道会被敌对生物杀 bot**（`exit=3` 无判决）。修后 `core` ×3 = PASS 3/3、`coverage=PASS`、
怪物/死亡命中 0、三轮场景行逐字相同。
全文：`docs/reviews/2026-09-15-夹具时机基准与DIAGONAL覆盖.md`。

**今天 2 个提交**：`ce3fac7`（D-220：夹具时机 + 夹具必动手断言 + `dip_course+run` + 无头 `peaceful`）、
`1aa948c`（D-220 附注：`FixtureScript` 共用原语 + 物品侧静默降级）；另有 `4a3bada`/`fe37f8e` 两条文档。

⚠️ **今天所有改动都只做了无头验证（`SERVER_TESTED`）**；没有一条碰渲染/物理/GUI，
所以**按纪律不冒充 `WINDOWS_CLIENT`**，也**没有**开客户端轮次（用户 2026-09-14 裁定：
无头能覆盖的就不占真人轮次）。**客户端 jar 已同步成 `462b10b5…`**（含 T3 七步 + D-220），
**且已由用户 2026-09-15 第十九轮实测验收**（见 §2d）：电池 `(30/30) ticks=3337 → PASS`、
pathing 场景行与无头**逐字相同**、T3 探针 42 字段中 41 个与无头逐字相同 ⇒ **T3 与 D-220 电池侧升 `WINDOWS_CLIENT`**。
**唯一没覆盖的**：R4 夹具物品（`pathing_disturber`/`pathing_waller`）本轮没跑 ⇒ 物品侧"未生效 ⇒ FAILED"仍是 `COMPILES`。

**待你拍板的问题**（§4）：① **客户端电池要不要也在夹具层清怪物**（无头侧已 `peaceful`）；
② 夹具分离要不要做（**只做包移动**，不要"移出发布 jar" —— 见 §2c 的范围修正）；
③ 要不要转向决策层（`survey/04`–`06` 需先过同样的核实）。

## 2. 更早的（T0–T2；今天的工作见 §2b）

| 项 | commit | 状态 |
|---|---|---|
| **完成度与优先级审查**（三路只读审计 + 无头通道实测） | `512f224`…`f5eb66e` | 全文 `docs/reviews/2026-09-14-项目完成度与优先级审查.md` |
| **T0-a 堵证据层假绿** | `75c1d23` | `COMPILES` + 门禁 + 4 条反向测试 |
| **T0-b 门禁进 CI** | `6bb26b2` | 本地 9/9 PASS；CI 已接 |
| **T1 修五条红线漏接** | `8b66572` | **`WINDOWS_CLIENT`**（第十七轮，见下） |
| **流程尺子 + 文档预算机器化** | `1ac024e` | 第 9 道门禁 `check-doc-budget` 在管 |
| **T2 无头通道 + 修「玩家区块票冻结」根因** | `4091694` | **`SERVER_TESTED`**：30/30 全绿、`entity_tick_missing` 47→0；**待客户端验证** |

**T2 报告全文：`docs/reviews/2026-09-14-无头回归通道T2-首轮实测.md`**（两个必须记住的坑 + 根因证据链）。

**第十七轮客户端（19:09–19:12，jar `abed83d2…`）**：6 条预测**逐条命中**；`(passed=30/30 skipped=0) → PASS`；
全会话 `decision_request` = **0**。两条硬证据：
- **R-1 前后对照**：同作用域 `Regression:mine_regression breaks` **5/64 → 13/64**，差值 **8 = 连锁自报 `mined=8`**；
- **R-3 深层漏洞被现场证实**：`trigger_skipped … trigger=terminal:RegressionBatteryTask until=1287 hold=true`
  —— `until` 早已过期，**挡住它的是 `hold`**（所以只改 `isSelfCheck()` 是不够的）。

证据全文：`docs/reviews/2026-09-14-项目完成度与优先级审查.md` §7/§8。

## 2b. 今天（T3 七步；全部已推送，HEAD = `e23869d`）

| 项 | 状态 | 读数 |
|---|---|---|
| **T3 步骤 1** `SiteKind` 五态 + 共享站点（`bc2b1aa`） | `SERVER_TESTED` | `with_site_confirmed 46→52`、`no_site 10→4`、`shared_site=6`、`row_block_missing=[]` |
| **T3 步骤 B3a** 读取器「先原版、再名族」+ 逐字段出处 | `SERVER_TESTED` | **值不变且被证明**（`vanilla_input=0` + `divergent=0` + `read_notes=0` ⇒ 每字段逐位相同）；判据一行未动 |
| **顺带修的**：`MachineProbe` 抽样不再是确定性的 | `SERVER_TESTED` | 修前**同 jar 两轮读数就不同**（`input_readable` 65/64、`query_machine_route` 0/2、`recipe_order_hash` 5 轮 5 值）⇒ **旧读数全部作废**；修后**同 jar 三轮 SUMMARY 逐字相同** |
| **T3 步骤 A** 探针可见性：枚举来源 = **配方注册表** | `SERVER_TESTED` | `unmapped_total=19`（`create` **15 类型/506 配方**、`ExtendedCrafting` **4 类型/25 配方**，**表里各 0 行**）；已登记部分读数逐字未变；两轮逐字相同 |
| **T3 步骤 B4** 能力登记（散文判据的可执行部分） | `PASS`（含**负测试**） | `capabilities=31 组=5 已用=31`；上游已取证 4/5；改一个名字 ⇒ 3 条独立报错。`MOD_ADAPTER_PROTOCOL` §3 的散文判据**保留**，但旧取证方式（`grep` 字面量计数）**作废** |
| **(甲) `ASCEND_NO_HEADROOM` 可判读读数** | `IMPLEMENTED`+`COMPILES`（**尚未被触发**） | 失败码带几何 `ASCEND_NO_HEADROOM@from.up2=(x,y,z):block_id`；加完 4 轮未再现，2026-09-15 又跑 6 轮仍未再现 ⇒ **不是** `SERVER_TESTED` |
| **(A) 落地同步（电池）**：步骤起步未落地时有界等待 | `SERVER_TESTED` | 修前同一 jar 两轮 1 FAIL / 1 PASS（`partial_search`）；**临时探针确定性复现触发条件**后 ⇒ 等待生效、`partial_search` PASS、`(30/30)`；删探针后 `(30/30) ticks=3414 → PASS`。step 1 空降是常态（8/8 轮）⇒ 该步记 info |
| **T3 步骤 A2+C** 未登记类型的形状 + M-4 查询层判决 | `SERVER_TESTED`（**两轮同 jar：轮 1 因无关的 `partial_search` 非确定性变红，轮 2 `(30/30) PASS`**） | ⭐ `unregistered_vanilla_only_out=37/37` ⇒ **模组名族对 Create/EC 读不出产出**，是 B3a 的原版路径在读 ⇒ **B3a 对接第 3 个模组是承重的**（改前这批会判 `MACHINE_RECIPE_UNSUPPORTED`）；M-4：`query_no_recipe=0`、`reachable=6/6` ⇒ 查询层对新模组物品诚实 |

**B3a 全文：`docs/reviews/2026-09-14-T3-B3a-读取器vanilla优先与探针确定性.md`**
（含两个新事实：**机器配方不实现原版 `getIngredients()` 的物品语义**；**旧 `catch(Throwable ignored)`
在本模组集下是潜在风险而非已发生的 bug** —— 对第 3 个模组才是真闸门）。
**步骤 A 全文：`docs/reviews/2026-09-14-T3-步骤A-探针可见性.md`**（核心事实：旧探针的枚举来源就是
`MachineMap` 本身 ⇒ **未登记命名空间连枚举都进不去**，所以 `unmapped=[]` 是"**看不见**"而不是"没有"；
现在接 Create 的工作量 = **实测 15 行**、EC = **4 行**）。

## 2c. 今天后半段（`survey/07` 审查的处理）

| 项 | 结果 |
|---|---|
| **D-219 模组适配改需求驱动** | `8ac6fbe`。`AGENTS.md` 加边界（**净增 0 行**，预算 1475/1476）+ `AI_DECISIONS.md` D-219 |
| **`survey/07` 行动清单核实** | 5 条里 **2.5 条已过时**：`LumberJob` 清障作用域缺陷 **2026-09-10 就修了**（`a8dd9ac`）、`requester` 无填充**已过时**、Create/EC"静默读不出"**今天被 B3a 修掉**；`task/` 那四个数**全对**；§2.2 架构结论**不冲突**（实建 = `action/WritePolicyMatrix` 按域实现）⇒ **不照单执行**。全文 `docs/reviews/2026-09-14-survey07-可行动条目核实.md` |
| **范围修正** | 审查说夹具"移出发布 jar"，但**交付工件只有一个**（同时是游戏内测试物品 + 无头电池的载体）⇒ 移出会把两者一起移走、**验证变贵**（负收益）。**建议只做包移动** |

**D-219 的直接后果**：`MachineMap` **不再**"接第 3 个模组就得补齐 19 行"（Create 15 + EC 4）——
那 19 行**当前买不到任何功能**（实测：`RecipeQuery` 已经报得出那些路线，加表行只把 `station`
从类型 id 换成方块 id，执行准入仍全 `READ_ONLY`）。**T3 视为已收口**，19 行按需再加。

## 2d. 2026-09-15 上午（验证通道"声明必须为真"）

| 项 | 结果 |
|---|---|
| **原计划被否** | 原打算做"`pathing` 子用例起点必填契约"；实测改前 `single:pathing` **3/3**、`core` **3/3** 全绿且**场景行逐字相同** ⇒ **假设不成立**，改做下面的可证缺陷 |
| **① 夹具时机参数从未生效** | `+wall`/`+disturb` 的 `30` 比的是**任务级** `ticks`（从不按场景复位）⇒ 实测 `tick=346`/`422` = **第一个执行 tick**；且放弃时 `disturbed = true` **假装做过**。改为 `sceneTicks` + **声明了夹具必须断言它真的动手**（`FIXTURE_NOT_FIRED`）+ 删静默降级 |
| **独立对照** | 修后 `wall_placed at=5,64,66 sceneTick=30`，与 `AI_DECISIONS.md:569` 的 **2026-09-09 客户端验证记录一致**；修前电池打的 `at=2,64,66` **不是同一个场景** |
| **② `DIAGONAL` 覆盖靠运气** | 修① 后 `core` **2/2 FAIL `coverage=FAIL([DIAGONAL])`** —— 对角线只来自 `+disturb` 修前那条偶发绕行；`dip_course`/`lava_course` 都只规划不执行。补 `dip_course+run`（复用同一地形）⇒ 确定性覆盖 |
| **③ 无头通道的噪声源 = 敌对生物** | 实测 `假人死亡: Alice was blown up by Creeper → 直接清除` ⇒ **无判决 `exit=3`**；同轮更早 `PLACE_NO_VALID_FACE` + `feet=1,64,68`（起点 `z=66`）⇒ **bot 被推离预期格**。修法：`headless-battery.sh` 把无头服务端设 `difficulty=peaceful`（**零生产代码改动**） |
| **修后判据** | `core` **8 轮全绿**（`30/30`、`coverage=PASS`、怪物/死亡命中 **0**、**8 轮场景行逐字相同**）；对照改前 **9 轮 3 红**（诚实边界：8/8 绿证不了"根除"，只证三个缺陷被修掉且没再出现） |
| **证据位改动** | `/tmp` 里上一会话的证据**已被清空**（历史 3 红因此无法复算）⇒ 本轮起证据放 `/home/fb486/alice-evidence/<日期>-<主题>/`（含 `README.md`） |
| **追加：消掉"同一份夹具两处实现"这一类** | 新增 `task/FixtureScript`（`wallPlan` 用 `footCell` + 前方第 2 格；`notFired` 共用字段串）—— §5 的教训不只是"时机算错"，而是**同一份语义写了两遍且已分叉**（时机基准 / 脚位算法 / 放弃策略），**没有编译期信号**。物品侧同时补掉两处静默降级：扰动放弃 ⇒ warn；夹具未生效 ⇒ 结果行带 `/FIXTURE_NOT_FIRED=…` 且**任务 FAILED**。**验证**：共用原语 `SERVER_TESTED`（`single:pathing` 场景行**重构前后逐字相同**、`core` ×2 PASS、累计 **core 10/10**）；**物品侧接线 `COMPILES`**（验收点 = 下一次 R4 客户端轮次） |
| **第十九轮客户端验收（用户 2026-09-15）** | ✅ 电池 **`(30/30) ticks=3337 → PASS`**、`K4=OK(0/0)`；**pathing 步 54 行场景日志与无头 `core` 逐字相同**（跨通道等价；⚠️ 比对前必须 `tr -d '\r'`——Windows 日志是 CRLF，直接 `diff` 会把 54 行全报成"不同"，我第一次就踩了）；`wall_placed at=5,64,66 sceneTick=30 pathIndex=3/9`、`disturbed sceneTick=32`、`coverage=PASS`（9 种 Movement 全到）、`FIXTURE_NOT_FIRED` **0**；**T3 探针 42 字段中 41 个与无头逐字相同**（唯一差异 `recipe_order_hash`，已登记为非确定值）⇒ **T3 与 D-220 电池侧升 `WINDOWS_CLIENT`**。怪物/死亡命中 **0**。证据 `.alice-supervision/client-tests/d220-t3-20260915/evidence/` |

## 3. ⚠️ 未验证 / 未做（**不要当成做完了**）

台账 **§8 / §9**（`docs/OPEN_ITEMS_LEDGER.md`）逐条有"触发条件"：

- **§5.9 / D-227 两点未验证**：① 结清结果的**落盘**（无头 halt 不存档；客户端正常退出会存，
  且**不存盘也会每次启动重新结清** ⇒ 症状已修）；② `assignWalkTo` 返回 false 这条**接线**缺一个
  "live 会话被挡"的电池判据（提案见台账 §5.9 末尾）。
- **S-5 / D-226（2026-09-15）只到 `SERVER_TESTED`**，两条**故意**没做客户端轮次（无渲染/物理/GUI 改动）：
  ① `alice:survival_exit_check` 的**两个模式**（右键=窒息硬危险；**潜行右键=着火软危险**，新增）
  ② "软危险 + 无出口 ⇒ **不否决**"与 no-exit 分支的**真人可见行为**。
  ⚠️ **"真被否决"不能在电池里验**：电池步的会话任务就是电池自己，真否决会把整轮电池 `complete` 掉
  ⇒ 这半只能真人看；动手时用 §6 的一条命令即可（右键/潜行右键各一次）。
- **✅ 客户端已验证（第十八轮，2026-09-14 20:14–20:18，jar `f478d9f7…`）**：
  `(passed=30/30 skipped=0) → PASS`、`entity_tick_missing` 0 条、无新增异常。**且是决定性的 B 轮** ——
  聊天行实证真人被传送到 `(10000, -50, 10000)`（离夹具区一万多格，票零覆盖）后才起电池
  ⇒ **票修复升为 `WINDOWS_CLIENT`**。机器真跑起来的读数：`smelt_ticks=200 smelted=true`、
  `walk_state=DONE walk_ticks=41`、`energy 20000→19950 progress_ticks=199`。
- **R2-残**：**已折进 T2**（用户 2026-09-14 裁定，不再单独开客户端轮次）；无头 30/30 全绿即已覆盖生产入口。
- **R1-残**：`prod_budget_exhausted` 分支无场景能触发（干净 3×3 只挖 8 块 ≪ 64）。
- **R4-残**：`RegionLumberJob` 补种仍直接 `setBlock`（已补触及校验）；改走 `placeAt` 会同时改**物品消耗路径**，需独立验证。
- **R5-残**：`StationProvision.click` / `InventoryCraft.click` 未做编译期强制（~19 处机械重构）。
- **代码结构债 TD-1（2026-09-15 用户复盘发现，AI 已核实）**：`*ExecutionFactory.validate()` 与
  `*Execution.preconditionsHold()` **各写一遍同一组前置谓词（8/10 个动作）**，且已分叉一处 ——
  `Traverse` 是唯一 `validate` 里**没有**几何检查的（其余 9 个都有 `<动作>_INVALID_GEOMETRY`）⇒
  越距 spec 到运行期第一帧才失败（码退化成 `TRAVERSE_INVALID_PRECONDITION`）。**优先级低、无已知故障**，
  且**不是有意移除**（自 `5d63cdf` 起就漏）。全文 + 复算命令：台账 **§5.5 / TD-1**。
- **`craft_check` 门禁缺口**（T2 新发现）：`machine_only_vanilla` 期望 `MACHINE_ROUTE`（需 Mekanism/Create），
  没装模组时它 `FAIL` 而非 `SKIP` —— 同文件 ⑥ 已有"没装该模组就 SKIP"的写法，④ 漏了前提声明。
- **T3 状态 = 已收口**（八步走完七步；第八步"接第 3 个模组本身"由 **D-219 判定为按需再做**）；
  剩余仅 **B3b**（等真有第 3 个模组要接时同期做）。以下原条目保留作历史指针：
  **B4 已完成**（§2b）；
  **接第 3 个模组本身**：`MachineMap` 加 **19 行**（Create 15 + EC 4）+ 每行方块/菜单/能力 + `machine-map.py`
  的 `UPSTREAMS` 一段；**B3b** Port 化 `Facts`（每产出自带 `chance` + 长度断言 —— 今天 `outputs`/`chances`
  是两个独立列表 + 空栈过滤 ⇒ **结构上无法配对**，A5；等第 3 个模组同期做）。
- **`query_reachable` 尚未升级为断言**（有意：measure first）—— 它是"读取器读出 X ⇒ 查询层不得对 X 报
  `NO_RECIPE`"这条真不变式，本轮只计数。
- **✅ 通道缺陷已修：(A) 落地同步**（用户 2026-09-14 批准）。`startStep` 在 premise 后**有界等待落地**
  （`awaitGrounding`，40 tick 上限）；setup 只做一次、落地**每 tick 复检**、等不到就**如实继续**。
  **验证 = 临时探针确定性复现**（`transfer` 后抬到 Y+3）：等待生效 → `partial_search` PASS；
  删探针后 `(30/30) ticks=3414 → PASS`。**我自己的实现 bug 被这次验证抓出**：
  第一版重入分支跳过复检 ⇒ 白等满 40 tick（超时日志里 `onGround=true`）。
  step 1 的空降是**常态**（8/8 轮，出生后第一 tick）⇒ 第一步记 info、第二步起记 warn。
  **grep 触发条件**：`premise step=… 起步时未落地`。
- **✅ 通道缺陷 2026-09-15 复核（D-220）**：上条"起步位置"假设**实测排除** —— 改前 `single:pathing` 3/3、
  `core` 3/3 全绿且**场景行逐字相同**（历史 3 红的 `/tmp` 日志**已被清空** ⇒ 归因永久停在"候选"）。
  转而在同一区域查到三处**可证**缺陷并修掉：① `+wall`/`+disturb` 的 `wallTick/disturbTick=30`
  **从未生效**（基准是任务级 `ticks` ⇒ 夹具总是"第一个执行 tick"就动手，且放弃时**假装做过**）
  ⇒ 改场景局部基准 + **声明了夹具必须断言它真的动手**（`FIXTURE_NOT_FIRED`）；
  ② `DIAGONAL` 覆盖**靠 ① 的偶发绕行凑** ⇒ 补 `dip_course+run` 变确定性；
  ③ **通道真正的噪声源 = 敌对生物**（实测 `假人死亡: … blown up by Creeper` ⇒ **无判决 exit=3**；
  同轮更早 bot 已被推离预期格 `feet=1,64,68`）⇒ `headless-battery.sh` 把无头服务端设 `difficulty=peaceful`。
  **修后**：`core` ×3 = **PASS 3/3**、`30/30`、`coverage=PASS`、怪物/死亡命中 0、三轮场景行逐字相同。
  全文：`docs/reviews/2026-09-15-夹具时机基准与DIAGONAL覆盖.md`。
  **证据改放** `/home/fb486/alice-evidence/<日期>-<主题>/`（`/tmp` 会丢）。
- **⚠️ 等待你拍的根治方向**：把"起点"变成**必填契约** —— `Step` 与 `pathing` 子用例都必须声明
  `startFoot` 或显式声明"我自带场景/复位"，**不声明就响亮失败**（这样"依赖上一步残留"在构造上不可能）。
  它碰全部 30 步 + `pathing` 子用例，是**电池语义**的改动 ⇒ 值得你过一眼再动手。
  ⚠️ 2026-09-15 复核后**优先级下降**：位置类假设已被实测排除，且真噪声源（怪物）已在无头通道清零；
  客户端侧仍是 `easy` 有怪物 ⇒ **先请你拍"客户端要不要也在夹具层清场"**（D-220 的复核触发 ①）。

## 4. 待用户拍板（恢复后**先问这个**）

1. **T3 剩余**：步骤 1 / B3a / A / A2 / C / B4 均已完成（§2b）。**只剩"接第 3 个模组本身"**
   （`MachineMap` 加 **19 行** + 每行方块/菜单/能力 + `machine-map.py` 的 `UPSTREAMS` 一段；
   **B3a 已被证明是它的承重前提**）与 **B3b**（等第 3 个模组同期做）。
   ⚠️ 这 19 行**已被 D-219 推迟**（买不到功能：`RecipeQuery` 已如实报路线，执行仍 `READ_ONLY`）
   ⇒ 等真实存档第一次需要走某台 Create/EC 机器时再做。

**下一轮"接第 3 个模组"的真实工作量（实测，不再是估计）**：`create` **15 行** + `ExtendedCrafting` **4 行**
= **19 行** `MachineMap`（每行含方块/菜单/能力/取证件）+ `tools/machine-map.py` 的 `UPSTREAMS` 一段。
**并且 B3a 已被证明是它的承重前提**（`unregistered_vanilla_only_out=37/37`：名族读不出 Create/EC，
是原版路径在读）—— 好消息是这一步已经做完并验证过了。

2. **通道**：2026-09-15 已定 ① **客户端不清怪物**（用户裁定：客户端仍 `easy`，**先观察**下一次客户端电池
   是否真被怪物干扰，有证据再改）；其余两条（`place_course+wall` 单次红、起点必填契约）已被本轮证据降级/排除（§3）。
3. **【潜在方向·待议，未动代码】用「旗舰自主挖掘任务」验收决策-执行框架**（用户 2026-09-15 提出；
   定位是**潜讨论 / 范例**，用户将**先与勘测员讨论再定**，并已提示"实际实现可能非常难于预估"）。
   **审计已完成**（三路只读审计，产物 = `docs/reviews/2026-09-15-挖矿高级任务作为框架验收范例-完成度审计与规划.md`
   + 台账 **§5.7**）：判定 = 框架"**单次闭环能用、自主长作业不能**"，差距是四件事
   （挖矿无候选菜单 / 长作业中段静音 / 父子额度零传递 ⇒ 缺镐被聚合成"没矿" / 无"转向"动作且任务树不持久化）；
   含 16 项能力对照、**7 块承重缺失**（第一 = 作业区抽象 + "进入作业区"）、最小补齐清单 **M1–M6**、
   维生+风险最小件、以及**明确拒绝清单**（战斗 AI / 簇优化 / 常驻矿区 / 饥饿进食 / 暴露预算 / `EdgeAdmission`）。
   **给勘测员的提示**：该文 §0 已声明**全部为静态审计、未实测**；其中一条可在无头通道 5 分钟内证伪/证实
   （"`MineJob` 缺镐 ⇒ 终态报 `no_reachable_candidate`"）⇒ 若要把这份审计当决策依据，**建议先跑它**。
   **用户 2026-09-15 已拍板**：确认 **A1–A3**（见 `AI_DECISIONS.md` **D-221**）、**启动 M 线**、§9 待实测按 AI 推荐先做 **#4**。
   **M 线进度**：✅ **M1**（D-222）、✅ **M2**（D-223）、✅ **M4**（D-224）、✅ **M3**（D-225）**四项全部完成并无头验证**；
   ⏭️ 剩下 **M4b**（`tree[].lastFailure`）与 **M3b**（预算耗尽/stale 的实测夹具）—— 都**不是** M 线的承重件；
   ⚠️ 但 **M5 已撤销**（见下）；
   **CORE 电池项数 30 → 34**（`mine_menu` / `no_progress` / `mine_no_tool` 新增，`llm_contract` 从 EXTRA 提到 MAIN）⇒ 客户端跑电池应看到 **`(34/34)`**。
   **下一轮客户端只需两件事**：① 跑一次电池（预期 **`(34/34) ticks≈3560 → PASS`**）；
   ② 跑 **R4 负例**（`/reload` → `/function alice_test:r4_negative_disturb` → 右键 `alice:pathing_disturber` 一次，
   **预期 `disturb_not_applicable` + `FIXTURE_NOT_FIRED=[disturb]` + FAILED** —— 那是如实回报）。
   ⚠️ 场景函数已复制进客户端存档，但**要先 `/reload`** 才认得新函数。
4. 之后收残留：**R1-残 / R4-残 / R5-残** + `craft_check` 门禁缺口（台账 §8/§9，各有触发条件）。

**上下文/文档纪律（AGENTS.md 已机器化，不用背）**：`AGENTS.md + PLAYBOOK + STATE ≤ 1476 行`
（当前 **1472**，余额 4）；新增散文规则必须同时删旧的；任何新验证手段必须挂在
`./gradlew build` 或 `tools/check-all.sh` 上（T2 就是这么接的：第 10 道门禁 `check-headless-battery`，
默认 WARN「断言未执行」，`ALICE_HEADLESS=1` 才跑）。

## 5. 环境与入口速查

- **客户端**：`/mnt/d/JAVA_projects/worldedit-test/versions/1.20.1-Forge_47.4.10`（日志 `logs/latest.log`）。
- **镜像 / 同步**：`./tools/mirror-windows-workspace.sh`；
  `./tools/sync-windows-artifact.sh build/libs/alice-1.0.0-1.20.1.jar /mnt/d/JAVA_projects/alice "<客户端>/mods"`。
- **本断点已同步的 jar**：文件 `eaf99f17fd1b283d1bd5218e9b4b0b3a7d02c84a885979692685e680b9bd3c11`、**内容摘要 `659dd6cfd57300133fe5d7d03dea6137fb5c3d8be72b371368615669e754ac05`（761 条目，含 R-2 第 2 个分类模块）**（2026-09-17；⚠️ **jar 不可字节复现** ⇒ 判「客户端是不是这版」只能用内容摘要：`tools/jar-content-hash.sh --compare <构建产物> <客户端 mods 里的 jar>`；文件哈希只表示「当时同步的是这个文件」）（**含 D-226…D-277 + 队列①②③④⑤⑦ + S-6/S-9(观测+消费) + F1/F2/F3/F4 + 死亡第 1 步(端到端已验)**，客户端与 Windows 仓库内容一致；门禁 **16 PASS + 1 预期 WARN**；CORE **48 步** / FULL **58** 项
- **重验证（发布前/大改后）**：`tools/module-selftest.sh` —— 逐模块**单独跑**，全 PASS 才算过（R-2 验收，D-294 ✓））**含 D-226…D-277 + 队列①②③④⑤⑦ + S-6/S-9(观测+消费) + F1/F2/F3/F4 + 死亡第 1 步(端到端已验)**，客户端与 Windows 仓库同哈希；门禁 **16 PASS + 1 预期 WARN**；CORE **43 步** / FULL **52** 项）
- 上一版（含步名门禁 SH-P1）：`3ac006f9c791d689fd28ee46101be77a55419788c601fbba719ea76c74d9b666`
**含 D-226…D-277 + 队列①②③④⑤⑦ + S-6/S-9(观测+消费) + F1/F2/F3/F4 + 死亡第 1 步 + 步名门禁 SH-P1**，客户端与 Windows 仓库同哈希；门禁 **16 PASS + 1 预期 WARN**（新增 SH-P1）；CORE **43 步** / FULL 51 项）
- 上一版（含 S-9 消费）：`8b966e9c759bd392221afceaf4c227fbd5339327165e52d8e6558c8db5af4fdf`
**含 D-226…D-277 + 队列①②③④⑤⑦ + S-6/S-9(观测+消费) + F1/F2/F3/F4 + 死亡第 1 步**，客户端与 Windows 仓库同哈希；门禁 **15 PASS + 1 预期 WARN**；CORE **43 步** / FULL 51 项）
- 上一版（含死亡第 1 步）：`6cb519fe432734e8762ef115e8a2a707471acb41e76e7e614b5afc4d8ed75527`
- 上一版（含 F3）：`c26a0e23401400cfd083f3da80705c2514bb57dc80239fbb08e136993f4f4cc7`
- 上一版（含 F1）：`048623a9cd226b3d99e6b1b630088581eb9df6718432709e72c8086c62a4e992`
- 上一版（含 F4）：`43c0d764caa79299804ce384dcfabb651f80491ee90ea529d6217bdd507afc9e`
- 上一版（含 S-6）：`a086e5de456315f4e42570c3c1a1381fe4a28869f1a869031612625c96817f81`
- 上一版（含 S-9 观测）：`c8ed987936afb1accd2574a7d124332e09beb1fe7188ad9aa837984c879c2f0f`
- 上一版（含队列①②③④⑦）：`fd2ef7e288758e964c9eb0d8c62b577bb8a88ac6a9d953c610fca0959d2c6b9f`
- 上一版（含 D-270）：`19b97017bba09123209b7f7364a1fa0185343eacb8d745ef48f4766602d3ceaa`
- 上一版（含 D-269）：`500347b8b98edfcaaaec7ab4df1ee4f24bf3ebea84fbb9f7c4e103996e8a9956`
- 上一版（D-226…D-265）：`25a58041cc0410c3fe82eba4f499f280297f42d63eb2b983353e4e160b44b0c4`
- 上一版（D-226…D-264）：`af1224ae8ee972f695ffce988213e3572075a6d6dc3e5f965c66d017355b7826`
- 上一版（D-226…D-263）：`141f7d5a3685399e6885cf3300ad044d6145679a424e2cf858d172c66afad91a`
- 上一版（D-226…D-261）：`b5d0fe0fca98681dd00f74d9c224fddfa8a67652fa4cfabad3293db8b6d407cd`
- 上一版（D-226…D-259）：`8608d18796ea226f5f32ac57a2a1c77598d43cdfc0e184e4817faa04cfbd1f6b`
- 上一版（D-226…D-258）：**含 D-226…D-276 + 队列①②③④⑤⑦ + S-6/S-9 + F1/F2/F3/F4 + 死亡第 1 步**，客户端与 Windows 仓库同哈希；门禁 **15 PASS + 1 预期 WARN**；CORE **43 步** / FULL 51 项）
- 上一版（含 F3）：`c26a0e23401400cfd083f3da80705c2514bb57dc80239fbb08e136993f4f4cc7`
- 上一版（含 F1）：`048623a9cd226b3d99e6b1b630088581eb9df6718432709e72c8086c62a4e992`
- 上一版（含 F4）：`43c0d764caa79299804ce384dcfabb651f80491ee90ea529d6217bdd507afc9e`
- 上一版（含 S-6）：`a086e5de456315f4e42570c3c1a1381fe4a28869f1a869031612625c96817f81`
- 上一版（含 S-9 观测）：`c8ed987936afb1accd2574a7d124332e09beb1fe7188ad9aa837984c879c2f0f`
- 上一版（含队列①②③④⑦）：`fd2ef7e288758e964c9eb0d8c62b577bb8a88ac6a9d953c610fca0959d2c6b9f`
- 上一版（含 D-270）：`19b97017bba09123209b7f7364a1fa0185343eacb8d745ef48f4766602d3ceaa`
- 上一版（含 D-269）：`500347b8b98edfcaaaec7ab4df1ee4f24bf3ebea84fbb9f7c4e103996e8a9956`
- 上一版（D-226…D-265）：`25a58041cc0410c3fe82eba4f499f280297f42d63eb2b983353e4e160b44b0c4`
- 上一版（D-226…D-264）：`af1224ae8ee972f695ffce988213e3572075a6d6dc3e5f965c66d017355b7826`
- 上一版（D-226…D-263）：`141f7d5a3685399e6885cf3300ad044d6145679a424e2cf858d172c66afad91a`
- 上一版（D-226…D-261）：`b5d0fe0fca98681dd00f74d9c224fddfa8a67652fa4cfabad3293db8b6d407cd`
- 上一版（D-226…D-259）：`8608d18796ea226f5f32ac57a2a1c77598d43cdfc0e184e4817faa04cfbd1f6b`
- 上一版（D-226…D-258）：**含 D-226…D-275 + 队列①②③④⑤⑦ + S-6/S-9 + F1/F2/F3/F4 地基**，客户端与 Windows 仓库同哈希；门禁 **15 PASS + 1 预期 WARN**；CORE **42 步** / FULL 50 项）
- 上一版（含 F1）：`048623a9cd226b3d99e6b1b630088581eb9df6718432709e72c8086c62a4e992`
- 上一版（含 F4）：`43c0d764caa79299804ce384dcfabb651f80491ee90ea529d6217bdd507afc9e`
- 上一版（含 S-6）：`a086e5de456315f4e42570c3c1a1381fe4a28869f1a869031612625c96817f81`
- 上一版（含 S-9 观测）：`c8ed987936afb1accd2574a7d124332e09beb1fe7188ad9aa837984c879c2f0f`
- 上一版（含队列①②③④⑦）：`fd2ef7e288758e964c9eb0d8c62b577bb8a88ac6a9d953c610fca0959d2c6b9f`
- 上一版（含 D-270）：`19b97017bba09123209b7f7364a1fa0185343eacb8d745ef48f4766602d3ceaa`
- 上一版（含 D-269）：`500347b8b98edfcaaaec7ab4df1ee4f24bf3ebea84fbb9f7c4e103996e8a9956`
- 上一版（D-226…D-265）：`25a58041cc0410c3fe82eba4f499f280297f42d63eb2b983353e4e160b44b0c4`
- 上一版（D-226…D-264）：`af1224ae8ee972f695ffce988213e3572075a6d6dc3e5f965c66d017355b7826`
- 上一版（D-226…D-263）：`141f7d5a3685399e6885cf3300ad044d6145679a424e2cf858d172c66afad91a`
- 上一版（D-226…D-261）：`b5d0fe0fca98681dd00f74d9c224fddfa8a67652fa4cfabad3293db8b6d407cd`
- 上一版（D-226…D-259）：`8608d18796ea226f5f32ac57a2a1c77598d43cdfc0e184e4817faa04cfbd1f6b`
- 上一版（D-226…D-258）：**含 D-226…D-274 + 队列①②③④⑤⑦ + S-6/S-9 + F1/F2/F4 地基**，客户端与 Windows 仓库同哈希；门禁 **15 PASS + 1 预期 WARN**；CORE **42 步** / FULL 50 项）
- 上一版（含 F4）：`43c0d764caa79299804ce384dcfabb651f80491ee90ea529d6217bdd507afc9e`
- 上一版（含 S-6）：`a086e5de456315f4e42570c3c1a1381fe4a28869f1a869031612625c96817f81`
- 上一版（含 S-9 观测）：`c8ed987936afb1accd2574a7d124332e09beb1fe7188ad9aa837984c879c2f0f`
- 上一版（含队列①②③④⑦）：`fd2ef7e288758e964c9eb0d8c62b577bb8a88ac6a9d953c610fca0959d2c6b9f`
- 上一版（含 D-270）：`19b97017bba09123209b7f7364a1fa0185343eacb8d745ef48f4766602d3ceaa`
- 上一版（含 D-269）：`500347b8b98edfcaaaec7ab4df1ee4f24bf3ebea84fbb9f7c4e103996e8a9956`
- 上一版（D-226…D-265）：`25a58041cc0410c3fe82eba4f499f280297f42d63eb2b983353e4e160b44b0c4`
- 上一版（D-226…D-264）：`af1224ae8ee972f695ffce988213e3572075a6d6dc3e5f965c66d017355b7826`
- 上一版（D-226…D-263）：`141f7d5a3685399e6885cf3300ad044d6145679a424e2cf858d172c66afad91a`
- 上一版（D-226…D-261）：`b5d0fe0fca98681dd00f74d9c224fddfa8a67652fa4cfabad3293db8b6d407cd`
- 上一版（D-226…D-259）：`8608d18796ea226f5f32ac57a2a1c77598d43cdfc0e184e4817faa04cfbd1f6b`
- 上一版（D-226…D-258）：**含 D-226…D-273 + 队列①②③④⑤⑦ + S-6/S-9 + F4 地基**，客户端与 Windows 仓库同哈希；门禁 **15 PASS + 1 预期 WARN**；CORE **42 步** / FULL 50 项）
- 上一版（含 S-6）：`a086e5de456315f4e42570c3c1a1381fe4a28869f1a869031612625c96817f81`
- 上一版（含 S-9 观测）：`c8ed987936afb1accd2574a7d124332e09beb1fe7188ad9aa837984c879c2f0f`
- 上一版（含队列①②③④⑦）：`fd2ef7e288758e964c9eb0d8c62b577bb8a88ac6a9d953c610fca0959d2c6b9f`
- 上一版（含 D-270）：`19b97017bba09123209b7f7364a1fa0185343eacb8d745ef48f4766602d3ceaa`
- 上一版（含 D-269）：`500347b8b98edfcaaaec7ab4df1ee4f24bf3ebea84fbb9f7c4e103996e8a9956`
- 上一版（D-226…D-265）：`25a58041cc0410c3fe82eba4f499f280297f42d63eb2b983353e4e160b44b0c4`
- 上一版（D-226…D-264）：`af1224ae8ee972f695ffce988213e3572075a6d6dc3e5f965c66d017355b7826`
- 上一版（D-226…D-263）：`141f7d5a3685399e6885cf3300ad044d6145679a424e2cf858d172c66afad91a`
- 上一版（D-226…D-261）：`b5d0fe0fca98681dd00f74d9c224fddfa8a67652fa4cfabad3293db8b6d407cd`
- 上一版（D-226…D-259）：`8608d18796ea226f5f32ac57a2a1c77598d43cdfc0e184e4817faa04cfbd1f6b`
- 上一版（D-226…D-258）：**含 D-226…D-272 + 队列①②③④⑤⑦ + S-6/S-9**，客户端与 Windows 仓库同哈希；门禁 **15 PASS + 1 预期 WARN**；CORE **41 步** / FULL 49 项）
- 上一版（含 S-9 观测）：`c8ed987936afb1accd2574a7d124332e09beb1fe7188ad9aa837984c879c2f0f`
- 上一版（含队列①②③④⑦）：`fd2ef7e288758e964c9eb0d8c62b577bb8a88ac6a9d953c610fca0959d2c6b9f`
- 上一版（含 D-270）：`19b97017bba09123209b7f7364a1fa0185343eacb8d745ef48f4766602d3ceaa`
- 上一版（含 D-269）：`500347b8b98edfcaaaec7ab4df1ee4f24bf3ebea84fbb9f7c4e103996e8a9956`
- 上一版（D-226…D-265）：`25a58041cc0410c3fe82eba4f499f280297f42d63eb2b983353e4e160b44b0c4`
- 上一版（D-226…D-264）：`af1224ae8ee972f695ffce988213e3572075a6d6dc3e5f965c66d017355b7826`
- 上一版（D-226…D-263）：`141f7d5a3685399e6885cf3300ad044d6145679a424e2cf858d172c66afad91a`
- 上一版（D-226…D-261）：`b5d0fe0fca98681dd00f74d9c224fddfa8a67652fa4cfabad3293db8b6d407cd`
- 上一版（D-226…D-259）：`8608d18796ea226f5f32ac57a2a1c77598d43cdfc0e184e4817faa04cfbd1f6b`
- 上一版（D-226…D-258）：**含 D-226…D-271 + 队列①②③④⑤⑦**，客户端与 Windows 仓库同哈希；门禁 **15 PASS + 1 预期 WARN**；CORE **40 步** / FULL 48 项）
- 上一版（含队列①②③④⑦）：`fd2ef7e288758e964c9eb0d8c62b577bb8a88ac6a9d953c610fca0959d2c6b9f`
- 上一版（含 D-270）：`19b97017bba09123209b7f7364a1fa0185343eacb8d745ef48f4766602d3ceaa`
- 上一版（含 D-269）：`500347b8b98edfcaaaec7ab4df1ee4f24bf3ebea84fbb9f7c4e103996e8a9956`
- 上一版（D-226…D-265）：`25a58041cc0410c3fe82eba4f499f280297f42d63eb2b983353e4e160b44b0c4`
- 上一版（D-226…D-264）：`af1224ae8ee972f695ffce988213e3572075a6d6dc3e5f965c66d017355b7826`
- 上一版（D-226…D-263）：`141f7d5a3685399e6885cf3300ad044d6145679a424e2cf858d172c66afad91a`
- 上一版（D-226…D-261）：`b5d0fe0fca98681dd00f74d9c224fddfa8a67652fa4cfabad3293db8b6d407cd`
- 上一版（D-226…D-259）：`8608d18796ea226f5f32ac57a2a1c77598d43cdfc0e184e4817faa04cfbd1f6b`
- 上一版（D-226…D-258）：**含 D-226…D-270 + 队列①②③④⑦**，客户端与 Windows 仓库同哈希；门禁 **15 PASS + 1 预期 WARN**；CORE **39 步** / FULL 47 项）
- 上一版（含 D-270）：`19b97017bba09123209b7f7364a1fa0185343eacb8d745ef48f4766602d3ceaa`
- 上一版（含 D-269）：`500347b8b98edfcaaaec7ab4df1ee4f24bf3ebea84fbb9f7c4e103996e8a9956`
- 上一版（D-226…D-265）：`25a58041cc0410c3fe82eba4f499f280297f42d63eb2b983353e4e160b44b0c4`
- 上一版（D-226…D-264）：`af1224ae8ee972f695ffce988213e3572075a6d6dc3e5f965c66d017355b7826`
- 上一版（D-226…D-263）：`141f7d5a3685399e6885cf3300ad044d6145679a424e2cf858d172c66afad91a`
- 上一版（D-226…D-261）：`b5d0fe0fca98681dd00f74d9c224fddfa8a67652fa4cfabad3293db8b6d407cd`
- 上一版（D-226…D-259）：`8608d18796ea226f5f32ac57a2a1c77598d43cdfc0e184e4817faa04cfbd1f6b`
- 上一版（D-226…D-258）：客户端与 Windows 仓库同哈希；门禁 **14 PASS + 1 预期 WARN**）
- 上一版（D-226…D-269）：`500347b8b98edfcaaaec7ab4df1ee4f24bf3ebea84fbb9f7c4e103996e8a9956`
- 上一版（D-226…D-265）：`25a58041cc0410c3fe82eba4f499f280297f42d63eb2b983353e4e160b44b0c4`
- 上一版（D-226…D-264）：`af1224ae8ee972f695ffce988213e3572075a6d6dc3e5f965c66d017355b7826`
- 上一版（D-226…D-263）：`141f7d5a3685399e6885cf3300ad044d6145679a424e2cf858d172c66afad91a`
- 上一版（D-226…D-261）：`b5d0fe0fca98681dd00f74d9c224fddfa8a67652fa4cfabad3293db8b6d407cd`
- 上一版（D-226…D-259）：`8608d18796ea226f5f32ac57a2a1c77598d43cdfc0e184e4817faa04cfbd1f6b`
- 上一版（D-226…D-258）：客户端与 Windows 仓库同哈希；场景数据包 113 个函数；门禁 13 PASS + 1 预期 WARN）
- 上一版（D-226…D-265）：`25a58041cc0410c3fe82eba4f499f280297f42d63eb2b983353e4e160b44b0c4`
- 上一版（D-226…D-264）：`af1224ae8ee972f695ffce988213e3572075a6d6dc3e5f965c66d017355b7826`
- 上一版（D-226…D-263）：`141f7d5a3685399e6885cf3300ad044d6145679a424e2cf858d172c66afad91a`
- 上一版（D-226…D-261）：`b5d0fe0fca98681dd00f74d9c224fddfa8a67652fa4cfabad3293db8b6d407cd`
- 上一版（D-226…D-259）：`8608d18796ea226f5f32ac57a2a1c77598d43cdfc0e184e4817faa04cfbd1f6b`
- 上一版（D-226…D-258）：客户端与 Windows 仓库同哈希；场景数据包 113 个函数）
- 上一版（D-226…D-264）：`af1224ae8ee972f695ffce988213e3572075a6d6dc3e5f965c66d017355b7826`
- 上一版（D-226…D-263）：`141f7d5a3685399e6885cf3300ad044d6145679a424e2cf858d172c66afad91a`
- 上一版（D-226…D-261）：`b5d0fe0fca98681dd00f74d9c224fddfa8a67652fa4cfabad3293db8b6d407cd`
- 上一版（D-226…D-259）：`8608d18796ea226f5f32ac57a2a1c77598d43cdfc0e184e4817faa04cfbd1f6b`
- 上一版（D-226…D-258）：客户端与 Windows 仓库同哈希；场景数据包 113 个函数）
- 上一版（D-226…D-263）：`141f7d5a3685399e6885cf3300ad044d6145679a424e2cf858d172c66afad91a`
- 上一版（D-226…D-261）：`b5d0fe0fca98681dd00f74d9c224fddfa8a67652fa4cfabad3293db8b6d407cd`
- 上一版（D-226…D-259）：`8608d18796ea226f5f32ac57a2a1c77598d43cdfc0e184e4817faa04cfbd1f6b`
- 上一版（D-226…D-258）：客户端与 Windows 仓库同哈希；场景数据包 113 个函数）
- 上一版（D-226…D-261）：`b5d0fe0fca98681dd00f74d9c224fddfa8a67652fa4cfabad3293db8b6d407cd`
- 上一版（D-226…D-259）：`8608d18796ea226f5f32ac57a2a1c77598d43cdfc0e184e4817faa04cfbd1f6b`
- 上一版（D-226…D-258）：客户端与 Windows 仓库同哈希；场景数据包 113 个函数）
- 上一版（D-226…D-259）：`8608d18796ea226f5f32ac57a2a1c77598d43cdfc0e184e4817faa04cfbd1f6b`
- 上一版（D-226…D-258）：客户端与 Windows 仓库同哈希；场景数据包 113 个函数已刷）
- 上一版（D-226…D-258）：`62422f495cfff5eca2af9efa87b5bf3fc81310082dbba7781129db76890e530f`
- 上一版（D-226…D-257）：客户端与 Windows 仓库同哈希；场景数据包 113 个函数已刷）
- 上一版（D-226…D-256）：`af2ad53c64f09f11758fcf0311a322bc7f2ce57cf97c9a6f0c9403c77a2636eb`
- 上一版（D-226…D-255）：客户端与 Windows 仓库同哈希；场景数据包 113 个函数已刷）
- 上一版（D-226…D-255）：`e7f6692eaafab297aecab80e1a772b5aa239e859dfb8202c6a76f7084cd62631`
- 上一版（D-226…D-254）：客户端与 Windows 仓库同哈希；场景数据包 111 个函数已刷）
- 上一版（D-226…D-254）：`7b06d63303bca995b61a6bf8df6992e4631ef2817376606d27b9989ef6529fc8`
- 上一版（D-226…D-252）：客户端与 Windows 仓库同哈希；场景数据包 111 个函数同轮已刷）
- 上一版（D-226…D-252）：`6cc5e7e0b218a231038635be86dc3c287f4e27078bee8ecd3f33298f4bd1c486`
- 上一版（D-226…D-252，19:5x 之前）：客户端与 Windows 仓库同哈希；**场景数据包同轮已刷成 111 个函数**）
- 上一版（D-226…D-251）：`935cf6ed5b1d293e776d682aec0e0dae718b067069332912b348c58ef4a039b6`
- 上一版（D-226…D-250）：`6593ff729f9dde800c062ee950049ce7c69959ba027082bc4b5be9608ca6d56b`
- 上一版（D-226…D-248）：`4522a0eb23d32c6bc9d99e7b009bd0566894d4076b9ee4c9149927fde12bbbef`
- 上一批 jar（含 D-247）：`c6cb9c72041a3fb1b67e5ba82f6c5307eeca33915ec68341d0b18fc640c76c51`
- 上一批 jar（含 D-245）：`496b3cd3e92dea945bdb9b7360bf43fdfd68bc6ec67b2228e501a2b5117eaa8f`
- 上一批 jar（含 D-244）：`dffd3f226825603e81fb85f1609425f284b049c3f8f0e564c59cd76a48d01401`
- 上一批 jar（含 D-243）：`2d2735af1517c23502545c9e4e7d868802d15b1b4d0d8b744479ce8645e1ebbb`
- 上一批 jar（含 D-242）：`9738e6fca2b07ede8b985b29a6199372b205ede61bb08071b1cba6b997eaa594`
- 上一批 jar（含 D-241）：`872c30fdedc1a2b74ae921f741c38f4607f0114dc572d5813ad3c50548aa1dcd`
- 上一批 jar（含 D-238）：`a5f758902b4160be3b69af3ea7d57ff5bd581aeaaedd62b99e6cfdd89a65c600`
- 上一批 jar（含 D-236）：`e05b3492eeee7edf289260204a70e11b4f1dd52bf1b29929989c3ac9a1548d0f`
- 上一个 jar（23:4x，含 D-236）：`63f84e7719b8ab0bd2ad556a4222aed27aa7e04996359d1f624b29be9aba8ef0`
- 上一个 jar（23:2x，含 D-235）：`8e8ae5a3f5ac941a6daf03626a03fc246827d4631a1473d2c7868f6ad0681947`
- 上一个 jar（23:1x，含 D-234）：`30ff9b8712f9525945d3e383209dd059b8370a2e0b9b58a8837e42a5951c7fb6`
- 上一个 jar（22:4x）：`cd9dab70db40a248cd30885edd89dc2f90b194b7f4a52aae947e8772d5382b87`
- 上一个 jar（22:33，含 M3b）：`eefbea3a2f18d271c34d69c2d6fa993458c037b3075abda7348036c817a586d9`
- 上一个 jar（22:24，含 M4b）：`494f26dbe436bb4755b2da20b36e5ebfee72c735624046c199a58578333ee3c5`
- 上一个 jar（22:06，含 D-230 开关）：`be8a353f7db914b7bdf5d205e718c50f49641f133120a0b8e3e822b62eefb14a`
- 上一个 jar（21:36，含 D-229 与 survival_full_check）：`c251caf2d99e53c769c069a0559be7b7ac557a08cd04270315c0698dae0cf671`
- 上一个 jar（20:33，含 D-228）：`51e5719dc6e6ef11b489e66dd4915886db1bbd81209d7b072179254136f7d942`
- 上一个 jar（19:51，含 §5.9）：`ed30f04a9356d10fdb2acb2673a82569b0d24e33cf4dd395fbae2ce28a4ebd51`
- 上一轮 jar（19:03，仅 S-5）：`b918d7b201c792d6f453c247b92e58783b0a4896baaf5021c0e3be4a3628e1af`
- 上一轮 jar（2026-09-15 16:18，M 线 + R4 负例）：`35fa4580dfd8040e27490a8cd219df4c81f7011a94acf3e65c644f8538439814`
  —— 在 `462b10b5…` 之上**新增 M 线四项：M1（挖矿候选菜单）/ M2（长作业周期复评）/ M4（失败事实字段化）/ M3（专有终态理由）**。
  **已完成真人验收（2026-09-15 第十九轮）**：电池 `(30/30) ticks=3337 → PASS`、pathing 场景行与无头逐字相同、
  T3 探针与无头逐字相同（仅 `recipe_order_hash` 不同，已登记为非确定值）。
  ⚠️ **唯一没覆盖**：R4 夹具物品（`pathing_disturber` / `pathing_waller`）—— 想覆盖就各右键一次
  （预期见 `TESTING_GUIDE.md` §4.7；**没看到 `[R4 Fixture] disturbed|wall_placed` 就是如实 FAILED，不是 bug**）。
- **离线门禁（改完就跑这一条）**：`bash tools/check-all.sh`（**10 道**，三态 PASS/WARN/FAIL；
  `WARN` = 断言**没执行**，不是通过；`ALICE_MODS_DIR` 可指定上游模组目录）。
  ⚠️ 改了 Java/工具后**再跑一次**，别只看编译过。
- **无头回归（T2，新）**：`bash tools/headless-battery.sh core`（一条命令，无真人，退出码即判决）。
  一次性准备 `--install`（下 Forge 47.4.10 并装生产服务端到 `/home/fb486/alice-server`）；
  `--dev` 退回 dev 服务端（**装不了上游模组**，会因 cofh mixin refmap 缺条目崩服）；
  `single:<step>` 秒级调试。接进门禁：`ALICE_HEADLESS=1 bash tools/check-all.sh`。
  报告 `docs/reviews/2026-09-14-无头回归通道T2-首轮实测.md`。
- **电池**：`alice:regression_battery`（CORE=**30**）/ `/alice battery full`（FULL=**40**）；
  唯一配置入口 `RegressionBatteryTask.CURATION`。**判决三态**：`PASS`（30/30 全跑过）/ `DEGRADED`（有步被跳过
  ⇒ **不是绿**）/ `FAIL`。
- **场景**：仓库 `tools/test-scenes/alice_test/` → 客户端存档 `saves/新的世界/datapacks/alice_test/`；
  **改场景后要手动复制 + 游戏内 `/reload`**（`mirror`/`sync` 两个脚本都**不碰 `saves/`**）。
- **无头起服**：`./gradlew runServer --no-daemon`（`run/eula.txt` 已 `true`；`run/mods/` 目前为空 ⇒ 机器类步跑不了）。
- **上下文**：`bash tools/dsh-context-usage.sh "${DSH_SESSION_ID:-}"`；取回被压缩的原文：
  `node tools/dsh-session-log.mjs --shadowed|--seq a-b|--grep`。
- **三路审计的"完整报告"只在会话日志里**（落盘的只有结论：审查文 §3.1/§3.2/§3.3/§3.5）。
  需要原始细节（逐条 `file:line`、门禁的实测绕过记录、断言覆盖表）时：
  `node tools/dsh-session-log.mjs --grep "三路"` / `--grep "回归网能力审计"` 取回，**不要凭记忆重建**。

## 6. 断点后下一步（2026-09-15 晚更新：原推荐路线 = 维生最小件，**已完成**）

> 断点 commit = `c208fab`（工作树干净、已推送 `github/master`）；**S-5 落地后客户端 jar = `b918d7b2…`**。
> **维生最小件（S-5）= D-226 已落地**：`survival_exit` 步（BASELINE）+ 决策表 + 无出口不否决 +
> 掉血可见 + 脚位格口径修正；CORE `(35/35) → PASS`。全文见 D-226 / 台账 §5.8。

**推荐下一步（二选一，都由 AI 先推、用户最后拍板）**：

> **2026-09-15 深夜 ✅ 维生这条线收口**：D-228（补 `baseTick`）→ S-5/D-226 与 D-228 已 `WINDOWS_CLIENT`；
> D-229（冻结档）已 `WINDOWS_CLIENT`（`alice:survival_full_check` 右键 = `SUMMARY checks=55 failures=0 → PASS`）；
> D-230 结论 = **不启用** `doTick()`（实测推翻了我自己"血只减不增"的判断：`Player.aiStep()` 本来就有自然回血）。
> **下一步候选**：① 台账里排队的 M4b（`tree[].lastFailure`）/ M3b（`write_budget_exhausted`/`stale_target` 未观测）；
> ② `decision_contract` EXTRA-vs-doc 的口径裁定；③ 台账 §5.6/§5.8 的风险选项；
> ④ `AI_PROJECT_STATE.md` 的"当前目标/当前进度"仍停在 R2–R4 时代（要在冻结预算里做等行替换，等你裁定）。

0. **【可做】点 `alice:survival_full_check`（D-229 物品）**：普通右键 ⇒ 整套维生夹具
   （含细雪冻结）跑一遍，约 15~20 秒后在聊天里打 `[Survival] SUMMARY checks=55 failures=N → PASS/FAIL`。
   顺带把 D-228（火焰/掉血）与 S-5（否决/逃生）一起复核。⚠️ 细雪那条**旧的"疾跑+右键"入口已撤**
   （原版站着不动进不了疾跑 ⇒ 点不到，用户实测踩到）。
0. **【立刻可做·真人必做】复测 D-228（补 `baseTick`）**：新建/重启世界后点 `alice:survival_exit_check`：
   **右键**（窒息）与**潜行右键**（着火 8 秒）各一次，看 ①**bot 身上有没有火焰**（这是本次修复的核心可见结果）、
   ② 着火期间**血量会掉**（`维生监测` 行 `health=19.0` / `[Threshold] 掉血 … hazard=ON_FIRE`），
   ③ 走动/放置/挖掘是否照常（`baseTick` 动了物理前置，已过 CORE 35/35，但真人观感最重要）。
   顺便留意：**药水效果现在会正常到期**（此前永不失效）、**空气会消耗**（可以试着让 bot 头浸水看 `air=` 掉）。
0. **【立刻可做·零成本】客户端重启一次世界**：§5.9 的 11 条挂起会在启动时被结清
   （日志 `[Transfer] 启动结清：11 条…`），随后点 `alice:survival_exit_check`（右键 = 窒息；
   **潜行右键 = 着火软危险**）就能看到 `任务因维生危险中断 …` → `[Survival] 逃生出口 …` →
   `kind=SurvivalExitTask terminal=COMPLETED`。**这一步同时把 S-5 从 `SERVER_TESTED` 升 `WINDOWS_CLIENT`。**
1. **【离线可推】`SurvivalExitTask` 的"选了落点却没走到"变可判读 + 真实否决链的真人入口收口**
   （台账 §5.8 的剩余两格）：① 现成的 `alice:survival_exit_check` 两模式**各点一次**（零参数，30 秒）
   就能把 S-5 从 `SERVER_TESTED` 升到 `WINDOWS_CLIENT`；② 若懒得分开点，可把它合并进一次客户端电池轮次
   （顺手复跑 CORE，确认客户端 35/35）。**代价**：占你一次客户端操作。**收益**：S-5 收口、维生不再是"半验证"。
2. **【要你参与】作业区抽象 / 粗目标 / "到达"**（`survey/08` §3 的结论：短线→长线真正的差别是
   `GoalFoot` 太精确 ⇒ `GOAL_NOT_LOADED`，而滚动重规划骨架已在跑）。**为什么必须你参与**：
   `survey/08` §10 明确**不回答**"到达算法"，因为它需要**真实存档地形做判据**（客户端）。
   这是"旗舰挖掘"的第一块承重件，做之前要先把"作业区"的语义（粗目标落在哪、到达判据是什么）定下来。

**明确不是承重件（按需再做，别插队）**：**M4b**（`tree[].lastFailure`）、**M3b**（`write_budget_exhausted`/
`stale_target` 两条映射**照词表写但未被观测**）、**`decision_contract` 的档位**（自称"任何改动都跑得到"
却挂 EXTRA，属策展裁定）、台账 §5.6 风险议题（B/C 候选）。

**压缩后取细节**：`node tools/dsh-session-log.mjs --shadowed last` 看被遮蔽的 seq，`--seq a-b` 取回，`--grep` 过滤。
