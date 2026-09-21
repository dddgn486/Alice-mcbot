# 交接断点（HANDOVER）—— 2026-09-15 10:20 收口

> # ▶ 压缩后**第一件事**（2026-09-21 用户指令）
>
> 用户原话：「新的**勘测报告**出来，**前后修正有点多**，**先一口气读完，再整理信息**，
> 然后针对现状给出**后续的规划**」。
> ⇒ 压缩后按此顺序做：**① 完整通读新勘测报告（不要碎片化浏览）→ ② 整理信息 → ③ 针对现状给规划**。
> 报告位置：仓库根 `survey/`（`survey/README.md` 51 KB / 2026-09-20 09:53；编号最新
> `23-决策层提案评价与方法论答复-20260920.md` 16 KB）。**若"新的"指向不明（用户说"前后修正有点多"），先问一句是哪一份**，不要猜。
>
> ## 现状速览（压缩后以此为事实，细节见下列路径，不要从对话里找）
> **源码状态**：`D-363`…`D-372` 全部已实现/验证/推送；工作树干净；远端只剩 `github`（`origin`=Windows 镜像路径
> 的陈旧引用已于 2026-09-21 删除）。客户端 jar `1fa0ee5bc8b7689e`。
> **真机第四轮（2026-09-21）已修好并实测生效**：`D-371` 扫描全覆盖（`visited=117649/117649`，候选 936）·
> `D-372` 收集器 `worldMod=true` `collected=2/2 unreachable=0` · `D-365` `mine_in_place` ×5 ·
> `D-372` 取消默认格数上限**未见跑飞**。
> **仍在的两个问题（用户点名，明天修）**：① **成本模型偏向"同层开通道"**（`mode` TUNNEL 29/CURRENT 15/DIRECT 1；
> 候选形状无"斜下"+ 破坏定价无下行先验 + 下行路线最易 `SEARCH_LIMIT`）② **深矿搜索拖死服务器**
> （`SEARCH_LIMIT` ×52，goal 全在 y=73~79 而 bot y≈84；`[Search] 超 tick 预算 170~200ms nodes≈20000`；
> `Can't keep up! 2301/3579/2656 ms`）⇒ 主修 = **每 tick 搜索总预算**（`D-369` 只框住了单次搜索）。
> **算法事实（已核实）**：路径搜索**是带启发式的 A\***（`GoalFoot.heuristic` + Baritone 式多系数 `COEFFICIENTS`）；
> **Dijkstra 只用于选择期"一对多"成本场**（有界：`costFieldMaxCost=24` / `costFieldMaxNodes=20000`）⇒ 卡顿**不是"缺启发式"**。
> **harness**：`lumber_job=FAIL`（用户确认没碰过那片地；已用"暂存改动跑干净版本"对照证明与源码无关）⇒
> 用户说**先不管**；手边有现成工具 `tools/analyze-lumber-scene.py` 可供离线查场景。
> **证据路径**：`docs/reviews/2026-09-21-客户端第四轮-深矿搜索卡顿.md`（第四轮全文 + §6 算法事实核查）
> · `docs/reviews/2026-09-20-mine-round3-root-cause.md`（第三轮证据台账）· `docs/AI_DECISIONS.md` `D-361`…`D-372`。
> **下一轮客户端测试入口**：`/alice mine here`（零参数）。

> ## ⭐ 最新断点（2026-09-20 真机 A 路线第一轮：`/alice mine here` **成功**，暴露三处新事实）
>
> **入口/结果**：用户新世界（`新的世界 (2)`，和平）在 `470,69,91` 地表执行零参数 `/alice mine here`。
> 客户端 `[fixed-client]/logs/latest.log` `:1125` 上锁 → `:2005` `[Job] terminal job=mine result=DONE reason=quota_met
> progress=mined 8/8 writes breaks=35 places=0 ticks=644` → `:2006` `[MineSurvey] SUMMARY … attempts=8/8 success=8
> terminal=quota_met · Δy 均值=-1.0 最大下降=1 · 水平 均值=14.0 列数=8 · 失败=无 向下占比=1.00` → `:2008` 自动解锁。
> ⇒ **测试工具本身全链路通过**（锁/统计/终态钩子/自动解锁），生产 `cost_optimal` 在**标签目标**上首次真机跑通。
>
> **离线核对（新工具，只读存档）**：`python3 tools/region-ore-scan.py --save "<存档>" --center 470 69 91
> --radius 24 --y-range 45 93 …` ⇒ 扫描立方体 117,649 格（与日志 `体积=117649` 一致）里**共 1546 个矿**；
> 而候选集只有 **55**。分层：y=68→49、y=69→10、y=70→3、y=71→12、**y=67→62、y≤66 还有约 1400**。
> 自校验：未挖的 `477,69,102 / 477,69,103 / 478,69,103` 读出 `coal_ore` ✔；8 个已挖格读出 `air` ✔（索引数学可信）。
>
> **三处新事实（都已用存档证实，不是推测）**：
> 1. ⚠️ **扫描冻在第一片**：`MineJob.select()`（`:320-329`）**只在"选不出候选"时才 `advance()`** ⇒ 一旦有候选就永远不再扫。
>    实测 `visited=8192/117649 = 7%`（3.41 层，= bot 自己高度那三层），**此后整轮再没推进过**（全程只有 1 行 `分片推进`）。
>    连带：`finish()` 从不执行 ⇒ **S5 记忆永远写不进去**（成功轮次恒 0 区块），`search_incomplete` 也永不出现。
> 2. ⚠️ **簇判定本身没错，缺的是"看见"和"配额"**：镍矿簇真身 = 5 格（已挖 `480,68,97` + y=67 的
>    `479/480,67,97/98`）；煤矿簇剩余 16 格与已挖格**全是 26 邻接**（⇒ 我们自己的连通口径会收它们）。
>    残留 = **带内 4 格（看见了，quota=8 停的）+ y=67 的 8 煤 4 镍（从没扫到）**。
> 3. ⚠️ **顺带破坏会吃掉目标矿且不计账**：`:…` `[WRITE] break 479,68,104 minecraft:coal_ore
>    by=mine-runner:attempt0:PATH_ACCESS` —— 它是**活候选/同簇成员**，被当通路方块挖掉 ⇒
>    `candidates=55` = 世界带内 54 + 它 1；`inventoryDelta=9` = 8 目标 + 它 1（产物被捡走，但**不进 success/failure**）。
> 4. ⚠️ **成本场全瞎**：`select #2` 起 `cells=0（所有候选都没枚举出站位点）` ⇒ 每个候选 `travel=∞`
>    ⇒ `cost_optimal` 退化成"欧氏最近"（8 次里 7 次）。真因：站位点模式 A 只认**现成可站**，
>    而矿体嵌在地表（相邻面全被同簇方块/地面挡住），执行器随后用 `mode=TUNNEL`（8 个目标里 3 个）。
>    ⇒ **成本模型只算"走"，执行器是"走+挖"**，真实地形下必然退化（= 台账里 deferred 的 break 成本项，现在有实测证据）。
>
> **用户已拍板（2026-09-20 第二轮口径）**：① 配额 8→**64**（`BotCommand`，已改）+ `maxTicks` 3600→12000；
> ② **扫描方式先不改**（保持贪心，"候选耗尽才 advance"这一支由配额 64 自然触发）；③ **要加"挖完一簇"语义**
> （用户同时点出陷阱：**同簇成员不一定都能挖，必须如实拒绝**，不许静默跳过、不许无限重试）；
> ④ **`PATH_ACCESS` 吃目标块** → 用户改口「**先不管这个**」（修复方向被认可，登记在 `D-359` 附注，触发=再出现）；
> ⑤ 成本模型加 `break` 分量 → **已同意**（未实施，见下）；
> ⑥ **X 光材质包撤掉**（改用旁观者模式观察；已移到 `[client]/resourcepacks-disabled/AliceXray`、`options.txt` 已清空）。
>
> ### ✅ 本轮已落地（`D-361`，2026-09-20；`check-all` pass=18 warning=1 failed=0）：
> ① **配额 8→64 / maxTicks 3600→12000**（`BotCommand`）；② **种类分配（per-kind allocation）**：
> `MineKindPlan` + `GoalSpec`/`JobRequest` 新组件 + `MineJob.filterByKind`（**在簇之前**）+ `MineCostConfig.kindQuotas`
> + `/alice mine here` 从 `config/alice-mine.json` 读；③ **簇语义**：成员不可用⇒**逐个了结 + 各自理由码**
> （不再"弹一个不可用就掉回全局选择 ⇒ 整簇作废"），**配额仍是硬上限**（`minedCount >= spec.quota()` 原地不动）。
> **证据**：`mine_menu` **checks=64**（+13 条新判据）**4 种注入全红**（`>=`→`>` / 反向找第一条 / 重复键不去重 /
> 只返回第一条之和）；门禁新规则 `rule_kind_filter_before_cluster`（18 条）**4 种注入全红**；
> `single:mine_survey` PASS（ticks=96）· `single:mine_job` PASS（ticks=245）· **CORE 51/51 PASS**（ticks=4822，
> 缓存指纹 `cd11671c4fb2`）；jar 已同步客户端 `runtime_sha256=0be2424f…`。
> ⏳ **未实施**：`break` 成本分量（已获同意；方案 = 补上 `D-329` §2.1 里**本就设计好但没实现的 top-K 精算**：
> 用 `MiningPlanner` 的成本（**已含破坏 tick 项**）给前 K 个候选重新打分 ⇒ 修掉 `cells=0` 时退化成"欧氏最近"）。
>
> ### ✅ 同日第二/三件也已落地（`D-362` 清障不吃任务目标 · `D-363` break 分量进成本）
> **`D-362`**（用户修正口径：「**对清障目标和任务目标的区分还是要修复**，即使是绕过去」）：
> 新 `TaskTargetProtection`（按 botId 作用域）+ 闸门**两处**（`breakRefusal` 搜索/执行共用 + `beginBreak`
> 真正写世界那一步）；**只在 `PATH_ACCESS` 下生效**；`MineJob`/`LumberJob` 成对 begin/end；
> `BotManager` 换任务兜底清（与 `WriteEnvelopes.clear` 同处）；`MineJob` 谓词豁免"当前那一格"（否则
> `ENTER_TARGET` 被自己拦死）。对照 Baritone `MovementHelper.avoidBreaking:68` ⇒ `:590 COST_INF`（绕行）。
> **判据**：`clear_guard` 步骤新增一组（**同一场景**把通道塞由箱子换成**铁矿**）：基线可挖 / 清障被拒
> （理由 `task_target_not_clearance`）/ `EXPECTED_TARGET` 不受影响 / **跑真实 `MineTask` 后矿塞仍在**
> （实测 `status=DONE` —— bot 绕开矿塞从石头那边挖过去了）/ `end` 后不泄漏；**3 种注入全红**。
> **门禁** `rule_clearance_never_eats_task_target`（19 条）**5 种注入全红**。
>
> **`D-363`**（用户：「break 分量我觉得可以马上做」）：新 `PlanRefinedCostProvider`（成本场估算 → **top-K=3 精算**），
> `CostOptimalPolicy.production()` 换用它；精算值 = `MiningPlanner` 的 `score`（**规划器路径成本本来就含破坏 tick 折算**
> ⇒ 不需另造估算器）；精算失败**只保持"估不出"、绝不拒绝**（`SEARCH_LIMIT ≠ UNREACHABLE`）；次数写进 `note`。
> **判据**：`mine_menu` **checks=64 → 68**（在矿石场景现搭"被石头**同层**包住的矿"：纯成本场 `∞` →
> 精算得有限成本 `30.323` == 规划器 score）；**门禁** `rule_cost_includes_break`（20 条）。
> ⚠️ 途中教训：第一版场景把矿盖在**脚下一层** ⇒ 规划器如实报 `tunnel=no_reachable_tunnel_standing_point`
> （`miningApproach` 禁 `DOWNWARD`）—— 那是**能力边界**不是 bug；判据场景必须搭在**同层**。
>
> ### ✅ 同日第四件已落地（`D-364` 垫方块与簇顺序；真机第二轮取证驱动）
> **真机第二轮读数**（`新的世界 (2)` @393,71,176，客户端 20:25 关闭）：`mined 12/64`、实挖 16 格；
> 失败 33 次 = `MOVE_MOVEMENT_FAILED` 23 + `SUPPORT_PLACE_FAILED` 9 + `LINE_OF_SIGHT_BLOCKED` 1；
> 垫方块触发 20/54 次规划（成功 4）；**扫描只有 1 次分片推进**（作业全程没重扫）；`WRITE-REFUSED`=0（与本轮无关）。
> **存档对照**（`tools/region-ore-scan.py`）⇒ 仍与已挖格 26 邻接的矿 = **10 格**，两类原因：
> **① `400/401/402,73,181`**：日志里都被选为 `cluster_member`，随后 `SUPPORT_PLACE_FAILED` ⇒ **垫方块毁掉的**；
> **② `410/411/412,73,*`（7 格）**：整份日志**一次都没出现**（同 x/z 的 y=72 是候选）⇒ **扫描没覆盖**（已登记）。
> **三处修法**：判据 `dropWouldBeLost`（下方 4 格内无可落面 或 先撞岩浆才算「会丢」——旧判据「下方那格不是实心就垫」
> 连**自己刚挖出的坑**都算悬空）+ 垫不上**降级为照挖**（不再判死目标）+ 簇内**按 BFS 图距**排序。
> **判据**：`mine_menu` **68 → 72**（T 形簇图距序；临时矿 `54,63,132` 浅坑 `support=null` / 深坑 `54,62,132` / 岩浆 `54,62,132`，
> 读数进 `[MineMenu] D-364 判别性事实…`）；`mine_regression` 悬空用例**前提更新**（1 格浅坑 ⇒ 不垫）；
> 门禁 `rule_support_and_cluster_order`（21 条）**3 注入全红**；夹具 **2 注入全红**。
> ⚠️ **自查纠错**：我第一版把簇顺序的原因写成「扫描发现顺序」，反向对照暴露它是错的 ——
> `Cluster.members` 本身按 (y,x,z) 排 ⇒ 真实原因是**层优先坐标序**（先挖完整层再进下一层）；
> 第一版判据也因此**空转**（输入会被重排）。已改正判据（T 形簇，图距 0,1,2,3,4→**1** 的横跳必红）。
>
> ### ✅ 同日第五件已落地（`D-365` 视线内就地挖；第三轮真机取证驱动）
> **第三轮读数**（@373,78,435，63 秒）：终态 **`partial_quota`**（`attempts=9/64 success=8`）；
> 写预算 **`breaks=64/64` 打满，其中只有 8 次挖到目标矿、56 次是挖路**（`mine-runner:attempt0/2`）；
> 规划模式 `CURRENT` 1 · `DIRECT` 3 · **`TUNNEL` 5**；典型 = 目标 `367,77,430`(铜矿) 离 bot 站位
> `369,78,430` **只有 2 格**却给了 11 格隧道。⇒ **预算是被"挖路"吃掉的**。
> **修法**（用户要求「目标在视线内就直接尝试挖」）：`MineBlockRunner.tick()` 在**走路闸门之前**问一次
> 「现在这格能不能挖到」（**执行期同一套** `checkFromEye` + `getBlockReach`，不新增物理），能就就地挖；
> 与 `isValidStandingPoint` 的关键区别 = **不要求"这格适合站位"**（bot 已经在上面了，那是寻路问题）。
> 用户要求的**掉落物接住面判定保留**（计划要求垫方块且未垫 ⇒ 照旧按计划走）。
> **判据**：`mine_menu` **72 → 75**（正例 `mineInPlace=true` / 负例挡视线 `false`，读数进日志）；
> 门禁 `rule_mine_in_place_before_walk`（22 条）**3 注入全红**；夹具 **1 注入全红**；
> CORE **51/51 PASS**（ticks=4836）。⚠️ 首跑 49/51 是 **FTB 前提 flake**（`bot_ownership`/`break_refused`
> 的"FTB 队伍/区块认领未就绪"），重跑即 51/51 —— 待办：让这两步的前提**等待**而不是当场判死。
> **登记**：①「规划器当初为何挖隧道」的具体子原因仍是**日志盲区**（`isValidStandingPoint` 内部
> `checkFromEye` 不打日志）——`D-365` 已让它对挖矿行为无影响，要归因需临时探针；②「同层挖掘」不是规则，
> 是**扫描顺序副作用**（`dyAt` = 0,+1,−1,+2,−2 ⇒ 候选集只有前几层，簇又是在"当前已扫候选"上算的）。
>
> ### 🔴 同日第六件已落地（`D-366` **崩服修复** + **挖矿信封临时放开**；用户两条裁定）
> **裁定**：(a) 崩溃走 **A**（收回搜索侧 `dy=+1`）；(b)「**先取消挖矿的 Movement 禁用，能用之后再调整风险管理策略**」。
> **崩服根因**（`crash-reports/crash-2026-09-20_21.32.05-server.txt`）：搜索生成侧 `SurfaceMovementProvider`
> 的 `for (int dy = 1; …)`（**昨天 `bc5ffaa`/`D-336` 加入**）允许 `PLACE_STEP_AND_TRAVERSE dy=+1`，
> 而 `MovementSpec.validateDisplacement:110`（**硬抛**）与 `PlaceStepAndTraverseExecutionFactory`
> **都只接受 {0,-1}**（2:1）⇒ 规划出执行端构造不出来的边 ⇒ **崩服**；没被拦住是因为 `D-336` 的夹具是
> **EXTRA + 规划级**（从没构造过 `MovementSpec`）。
> **修法**：收回生成侧 `dy=+1` + `PathSession.startSegment` 把 `toSpec` 包进 try/catch（坏边降级为段失败，
> **不许崩服**）+ 夹具改成**执行级不变量**（每一步都真构造一次 `MovementSpec`）。
> **先红后绿**：回退 `dy=1` ⇒ `place_step_diagonal` **failures=4 FAIL**；修复后 `checks=10 PASS`；
> 门禁 `rule_movement_contract_agreement`（**23 条**）**4 注入全红**（其中"删掉不变量"第一次没红 ⇒
> 判据太弱、已收紧为"定义+调用点同时存在"）。
> **`D-366b` 让步**：`PathRequest.miningApproach` 放开 PILLAR/FALL/DOWNWARD，**范围仅限该工厂**
> （`of`/`scaffoldRemoval` 不动，破坏/放置仍走预算闸门）⇒ **授权面没放松，放松的是路线能力**；
> **回收条件**写进代码注释 + `D-366`。意外好副作用：`D-336` 想要的能力**由合法的 `PILLAR` 实现**
> （夹具实测 `[TRAVERSE][PILLAR]`）⇒ 能力保留、契约统一。
> **验证**：`write_policy`/`place_step_diagonal`/`mine_regression`/`mine_menu` 全 PASS ·
> **CORE 51/51 PASS**（ticks=4829）· `check-all pass=18 warning=1 failed=0`。
> ⏳ **仍未做**：**掉刻 2～2.6 s ×3 未归因**（候选：`SEARCH_LIMIT` 重搜索 / 决策层 LLM 同步调用 /
> `D-363` top-3 精算 ⇒ 需按子系统插桩）；**R2/R3 绕远折返**待只读探针；**掉落物落进不可进入的洞**
> 是否给收集器有界世界修改权待裁。

>
> ### ✅ 断点更新（目标轮 5，2026-09-20）：`D-368` **摊销精算已落地**
> - **①掉刻 + ②绕远折返的一半，同一个改动解决**：`PlanRefinedCostProvider` 从"每次选择固定精算 top-3"
>   改为**摊销**——每次选择 ≤**1** 次规划器（`REFINE_PER_SELECT=1`）+ 跨选择缓存（`CACHE_TTL_TICKS=200`，
>   **失败也记账**，被采走立刻剔除）⇒ 覆盖逐次增长到**全部候选**。
> - **实测**（`mine_menu`，CORE，离线）：`摊销后（热缓存）一次选择=26ms vs 冷启动=99ms`（tick 预算 50ms）
>   —— 此前 `D-363` 是 **102→126 ms/选择**（超预算 2 倍）。
> - **判据 4 条 + 反向对照 4 注入全红**（精算改回 3 个 / TTL 退化 / **失败不记账** / 忽略缓存）；
>   ⚠️ "失败不记账"第一次没红是因为我拿"索引 0"当失败靶子 ⇒ 改成"**排序第一个**"才抓住（判据太弱第二次栽）。
> - **设计修正**：原 `D-368` 的"下界分支限界"**被否**（下界弱时剪枝+上限不保证真最优；要对"无站位点"的候选
>   可采纳就得引入 reach 松弛量 = 自己发明公式）⇒ 记在 `D-368 §五`。
> - **验证**：CORE **51/51 PASS**（ticks=4805）· `check-all` 见下行。
> - **仍未做**：菜单构建同 tick 复用（31 ms×2/事件）· 缓存失效改用 Job 写入计数当世界版本 ·
>   选择期 Dijkstra 与规划器内部站位点搜索重复计算 · ②的其余来源（R3 站位点 / R1 覆盖）与 ④ 掉落物归因。
>
> ### ✅ 断点更新（目标轮 6）：`D-369` **搜索预算同 tick 量级**（真机单 tick 2～2.6 s 停顿的机制）
> - **机制**：搜索**跑在 tick 线程**（预算 50 ms），而 `DEFAULT_MAX_MILLIS` 原值 **3_000 ms = 60 倍预算**
>   ⇒ 单次搜索可合法独占近 3 秒；真机 `Can't keep up! 2035/2632/2232 ms` 正落在该上限之下。
> - **改**：默认预算 **3000 → 200 ms**（=4 倍预算，卡顿上限从 ~2.6 s 降到 ≤0.2 s）+ 新增**超 tick 预算日志**
>   （`elapsed ≥ 50 ms` 打一条）⇒ 以后调紧调松有数据；超预算结局仍是既有的 `SEARCH_LIMIT`/`PARTIAL`
>   （`SEARCH_LIMIT ≠ UNREACHABLE` 不变）。
> - **内核对照**：Baritone **把搜索放独立线程**（`PathingBehavior.java:469 findPathInNewThread` + `safeForThreadedUse`
>   + `primaryTimeoutMS`/`failureTimeoutMS`）⇒ **线程化才是根治**，需线程安全世界视图 ⇒ **登记待办**。
> - **判据**：`partial_search` 新增时间预算用例（`SearchBudget.of(0,1L)` ⇒ 不许 `REACHED`、elapsed ≤ 1+50ms）；
>   **行为对照**去掉强制点 ⇒ `status` 变 `REACHED` ⇒ 红。门禁 `rule_search_budget_is_tick_aware`（**24 条**）3 注入全红
>   （⚠️ 其中"强制点"那条第一次没红：只查标识符会被我自己新加的"记账"调用满足 ⇒ 已收紧为精确强制表达式）。
> - **本轮两次自我订正**（`D-369 §三`）：①"每事件 ≥2 次菜单构建"**错**（决策路径只建一次，`BotStateReport` 是右键按需）
>   ⇒ 菜单复用待办**作废**；②"时间预算是死代码"**错**（生效的是 `timeBudgetExhausted`，`isExpired()` 才是没人用的）。
> - **验证**：CORE **51/51 PASS**（ticks=4780）· `check-all` 见下行 · 目标剩余：③R3 站位点 ④掉落物归因 ⑤R1 覆盖 + D-366b 风险核实。
>
> ### ✅ 断点更新（目标轮 7）：`D-370` **R3 = 病根是"到达路径长度"**（不是站位格远近）
> - **证据交叉**：真机 bot 在沟底 `461,77,318`、目标 `463,79,317`（高 2 格）⇒ 站位格 `462,79,317`
>   **离目标只 1 格**，但 **`pathSize=11`**；同层石壳夹具实测站位格距离 1、`pathSize` 3–4
>   ⇒ **病根是绕远的路，不是站得远**（与用户"绕远/来回折返"口径一致）。
> - **夹具**（`mine_menu` 新增 `runStandingPointChoiceChecks`，CORE）：真机同形"坑底 + 目标上方 2 格"几何实测
>   **`mode=TUNNEL pathSize=0`（就地挖，零绕路）**；同层对照几何站位格距离 **1**。断言：成功 + 路径 ≤6 段 + 站位格 ≤2 格。
> - ⭐ **夹具纪律（已上门禁）**：第一版坑底几何**只清脚位没清头位** ⇒ 眼睛嵌石头 ⇒ `LineOfSightChecker`
>   **假阳性**（`mode=CURRENT pathSize=0` 看着完美实为非法状态）⇒ **场景必须物理合法（脚位+头位都清）**。
> - **门禁** `rule_standing_point_detour_bounded`（**25 条**）3 注入全红；⚠️ "注释掉调用点"第一次没红
>   ⇒ 改用 `code_only()` 剥注释（**本会话第四次「判据太弱」**）。
> - ⚠️ **我自己的判据被 CORE 抓出 flaky**：`partial_search` 原断言"1 ms 预算下不许 REACHED"是错的
>   （该场景搜索只要 0–1 ms ⇒ 预算内跑完合法）⇒ 单步绿、CORE 红过一次 ⇒ 已改成**确定性**判据
>   （谓词语义 + 端到端上界）。**教训：判据不许依赖"某次搜索恰好慢到能被截断"。**
> - **验证**：CORE **51/51 PASS**（ticks=4824）· `check-all` 见下行。
> - **R3 剩余（等客户端）**：`D-366b`+`D-365` 上线后观察 `pathSize` 分布/`mine_in_place`/挖路比；若仍 >10 段绕远，
>   再按 T1 加只读探针（打印规划器枚举的候选走位点与成本）——**没有证据不改代码**。
>
> ### ✅ 断点更新（目标轮 8，最终轮）：`D-371` R1 扫描覆盖已修；目标五项进度见下
> - **`D-371`**：`MineJob.select()` 里"只在没得挖时才推进扫描"⇒**只要第一分片还有能挖的，候选集永久冻结**
>   （真机整轮只推进 1 次 `visited=8192/117649`，同脉 10 格从未进候选）。改为**每次选择至多推进一格**
>   （有界：8192 格 ≈ 13–20 ms；扫完即零成本）。夹具 3 条 + 门禁 `rule_scan_advances_every_select`（**26 条**）2 注入全红。
> - **进度**：①掉刻归因 ✅ · ②R2 摊销 ✅ · ③R3 诊断 ✅（真机确认待测试轮）· ⑤R1 覆盖 ✅ ·
>   **④掉落物落入洞里 ⏳ 等你裁定**（是否给收集器有界世界修改权 = `D-076` 红线）· **`D-366b` 风险补偿核实 ⏳**。
> - **验证**：CORE **51/51 PASS**（ticks=4783）· `check-all` 见下行 · 全部已推送/镜像/同步。
> - **下一轮客户端建议观察**：`分片推进 visited=…/…` 是否突破 8192 · `pathSize` 分布 · `mine_in_place` 次数 ·
>   挖路:挖矿比例 · `[Search] 超 tick 预算` 条数 · 是否还有崩溃。**未验证的都标着，不许当已验证。**
>
> ### ✅ 断点更新（2026-09-21）：`D-372` **默认不设格数上限 + 收集器获得世界修改权**（用户裁定）
> - **用户原话**：「保护区外，世界修改全部放开，只限制时间防止空转」+「保护区本来不就是有分级权限管理吗，
>   保持权限管理就行」+「可以完全给掉落物收集器有界的世界修改权，跟挖掘任务一样」。
> - **改**：`WriteBudget.consumeBreak/consumePlace` 默认回退 **`Caps.UNBOUNDED`**（计数照记）；
>   **显式装订**上限（`setCaps`/`capForEscape`，含 `D-241` 逃生准备金）**照旧强制**；
>   容器轴**不动**（别人的存储 = 另一条红线）；收集器在 `MineJob`/`LumberJob`/`RestoreScopeTask`/`CollectJob`
>   全部改为 `allowWorldModification=true`（有界：`DEFAULT_TOTAL_BUDGET_TICKS=600` + 簇预算 + 重试上限）；
>   **保护区权限层原样保留**（`CapabilityGate` → `protectionReason` ⇒ `protected_area`）。
> - **代价/补偿/回收条件**已写进 `D-372 §三`：格数上限原本是"停止损失"，现在**时间预算**是唯一停止损失
>   （收集器预算 / `MiningBudget.maxExtraBreakTicks` / 作业 `maxTicks` / `no_progress` 看门狗）；
>   若真机出现跑飞，按用户口径**按风险分档重新引入默认上限**，而不是回到一刀切 64。
> - **判据**：`write_policy` 新增 4 条（前提：作用域存在 · 默认不限 200/200 ALLOW · 显式上限 ⇒ 1 · `capForEscape` ⇒ 1）；
>   门禁 `rule_write_caps_default_open_protection_kept`（**27 条**）**4 注入全红**
>   （⚠️ 又踩「判据太弱」三次：容器轴"别处有 DEFAULT"、权限层"接口声明 vs 调用点"、收集器"逐字替换只改第一处"）。
> - **静默测量失败已提前堵**：`consumeBreak` 在 `scope == null` 时直接返回 ALLOW ⇒ "200 次全 ALLOW"可能是
>   "没有作用域"⇒ 夹具加了**前提断言** `scope != null`。
> - **验证**：六步单步全 PASS · **CORE 见提交说明** · `check-all pass=18 failed=0`。
>
> ### ⚠️ harness 异常（2026-09-21，与源码改动无关）：CORE `lumber_job=FAIL`（50/51）
> - 症状：`reason=partial_quota trees 1/4`（开局 `candidates=1`）→ 我删掉 `run/world-pristine` 重建后变成
>   `reason=no_reachable_candidate ticks=0`。
> - **已排除我的改动**：`git stash push -u` + `./gradlew build` + `single:lumber_job` ⇒ **干净版本同样 FAIL**（同症状）。
> - 场景源干净、数据包每轮重装、`trunk_too_tall` 是故意摆的对照大云杉 ⇒ 缺的是几棵正常橡树。
> - **待用户确认**：`run/world-pristine` 来自客户端存档；若你在世界里动过**伐木考场那片地**，场景函数重建地形后
>   树的落点条件已不成立（属 harness 场景鲁棒性：应先清场再搭）⇒ 详见取证档案 §9。
> - 期间我做了一次**harness 状态干预**（删 `run/world-pristine`，已自动重建）——如实记录。
>
> ## 🔖 断点（2026-09-21 收尾 · 明天从这里继续）
>
> **本轮（真机第四轮）证据与全文分析**：`docs/reviews/2026-09-21-客户端第四轮-深矿搜索卡顿.md`
> —— 含两份截图（sha256 已记）、原文日志标记、机制推断、明天的 6 项修复计划。**用户要求今天只分析、明天再修。**
>
> **本轮已验证修好（别再重复劳动）**：
> ① `D-371` 扫描覆盖 **`visited=117649/117649` 全覆盖**（上轮冻结 `8192`）⇒ 候选 936 个；
> ② `D-372` 收集器 `worldMod=true` · `collected=2/2 unreachable=0`（上轮 `MOVEMENT_FAILED` + 残留）；
> ③ `D-365` `mine_in_place` 生效；④ `D-372` 取消默认格数上限**未见跑飞**（破坏仍是目标+遮挡）。
>
> **明天要修的两个问题（用户点名）**：
> - **成本模型/几何偏向"同层开通道"**：`mode` 分布 TUNNEL 29 / CURRENT 15 / DIRECT 1；
>   根因①候选形状里没有"斜下"（4 面 × {y,y−1} + 下方列）②破坏定价无"下行更优"先验 ③下行路线最易撞 `SEARCH_LIMIT`。
> - **深矿搜索把服务器拖死**（主修）：`SEARCH_LIMIT` ×**52**（goal 全在 y=73~79，bot 在 y≈84）·
>   `[Search] 超 tick 预算 170~200ms nodes≈20000 open≈3300` · `Can't keep up! 2301/3579/2656 ms`。
>   机制：`D-366b` 放开垂直动作 ⇒ **分支更多 ⇒ 搜索爆炸**；`D-369` 只框住**单次**搜索，
>   **没有"每 tick 搜索总预算"** ⇒ 重试累积到 3.6 s；`D-371` 的 936 候选又放大了选择期 Dijkstra。
>
> **harness**：`lumber_job=FAIL`（用户确认**没碰过那片地**）⇒ 归 harness 场景鲁棒性（场景函数应先清场再搭），
> 与源码无关（已用"暂存改动跑干净版本"对照证明）；用户说**先不管**。
>
> **手上工件**：jar `1fa0ee5bc8b7689e`（= 客户端 mods 里的哈希）· 工作树干净 · 已推送/镜像。
> ## 🔖 断点（2026-09-20 22:0x · 用户压缩前落盘）
>
> **证据台账（唯一的证据来源，别再从对话里找）**：`docs/reviews/2026-09-20-mine-round3-root-cause.md`
> —— 含崩溃报告路径与栈、掉刻三次原文、掉落物 `retire` 原文 + 存档截面、15 格剩矿的逐格决策状态、
> 我自己的 5 条错误纠正、待修清单（判据计划）。
>
> **本轮真机结束原因 = server 崩溃**（`crash-reports/crash-2026-09-20_21.32.05-server.txt`；
> ⚠️ 我一度误读成"用户关了游戏"）。崩因 = 移动契约三方不一致，**已修**（`D-366`，`a4252f5`）。
>
> **已交付（本日六个 commit，均已推送 + 镜像 + jar 同步 `runtime_sha256=0f07ba17…`）**：
> `D-361` 配额64+种类分配+簇语义 · `D-362` 清障不吃任务目标 · `D-363` break 分量(top-3 精算) ·
> `D-364` 垫方块只在掉落物真会丢+垫不上不判死+簇内按图距 · `D-365` 视线内就地挖 ·
> `D-366` 崩服修复(A)+挖矿信封临时放开垂直能力(b)。
> **验证底线**：CORE **51/51 PASS**（ticks=4829）· `check-all pass=18 warning=1 failed=0`。
>
> **用户最新裁定（不许忘）**：
> ① 崩溃走 **A**（收回搜索侧 `dy=+1`）——**已做**；
> ② 「**先取消挖矿的 Movement 禁用，能用之后再调整风险管理策略**」——**已做**，回收条件写在 `D-366`；
> ③ 「客户端等后面几个修复再测试」⇒ **离线优先**，需要真人观察/拍板才停。
>
> **下一步顺序（离线可做，证据台账 §7）**：
> ① **掉刻归因**（2～2.6 s ×3，未定案 ⇒ 按子系统插桩 tick 耗时，**不许猜**）；
> ② **R2 选择排序**（`精算 尝试=3 成功=3（候选 91）` ⇒ 97% 候选无成本 ⇒ 绕远/折返的一半）；
> ③ **R3 站位点**（就地可挖时不许走去站位点）；
> ④ **掉落物落进"走不进去的洞"**（先做 `unreachable` 原因细分）；
> ⑤ **R1 覆盖**（`advance()` 只在无候选时调用 ⇒ 已挖格 26 邻域目标必须进候选）。
> ⏳ **待裁**：收集器是否给**有界**世界修改权；`D-366b` 的风险补偿（放开后是否仍有风险层拦住跳下/搭柱）。
>
>
> **已交付并推送（`master`）**：`e74de1e`（`D-350`）· `58c505f`（电池离线开关）· `40405b7`（⑥ `FarWalkTask`）·
> `d728e19`（③ 挖矿阶段 0 设计）· `50068bc`（`D-351` + 台账四处更正）· `b3f4590`（`D-349` 三坑）· `60df344`（`D-348` 修复）。
> **源码已镜像 `/mnt/d/JAVA_projects/alice/`，jar 已同步客户端**（`mods/alice-1.0.0-1.20.1.jar` sha `7d9221de…`，与仓库一致）。
>
> ### ✅ 已闭环：`D-350` 客户端验证（2026-09-20，`WINDOWS_CLIENT` + `USER_ACCEPTED`）
> **用户口径**：「符合预期，bot 没有卡住的感觉，有新的掉落物也会去捡」；入口 = 现有 `region_lumber` 零参数命令/物品，区域 `x17..37 z203..231`。
> **日志锚点**（`[fixed-client]/logs/latest.log`，14:43–14:46）：`:2760` `扫描判定 HAS_SAPLINGS → ENTER（deficit=0 手里苗=3 区内可捡=13）`
> （⭐ 关键 = **不欠树也进扫描**）→ `:2762` `sweep 开始 目标=13 预算=900` → `:3021` `sweep 结束 status=DONE 实际入包=16 区内剩余=2`
> → `:3092` `ENTER → NO_DEFICIT（区内可捡=0）` → `:3094` 起 `间隔退避 40→80→160→320→600`（**非必需零收获只退避**）
> → `:3112` 新落物 `NO_DEFICIT → ENTER` → `:3183` `入包=1 剩余=0`。`sweep_no_progress=0`、`failed=0`、正常关服。
> **对照轮** `logs/2026-09-19-3.log.gz`（立论那轮，同区域）：`sweep`/`实际入包`/`撤销授权` **各 0 行**，`deficit=0` 后原地待机到轮次结束。
> ⚠️ **别误判**：`[Pickup] blocked … provenance=FOREIGN` 两轮**都是 26 行**（≠ 回归）——窗口内授权收走 19 件、窗口外照常拦；
> 且旧包**没有** `扫描判定` 这行日志，"旧轮 0 行"只能当**该行缺失**读。详见 `D-350` 附注一。
>
> ### 环境坑（今天踩过，压缩后照这个查）
> ⚠️ **构建卡死 30+ 分钟的真因 = Gradle 在等网络**（`jstack` 见 `Socket.connect`；`ss` 见 `SYN-SENT` 对 :443 不返回）。
> ⇒ 电池支持 `ALICE_GRADLE_OFFLINE=1` 离线构建（依赖缓存热，实测 7–12 s）。若再遇无输出长跑：先
> `jstack <pid>` + `ss -tnp | grep <pid>` 判网络，**不要傻等**。
>
> ### 待办（按优先级；都**已登记**，压缩后别重新推导）
> 1. ✅ **17-② 已闭环（2026-09-20）**：核代码发现台账那行**写错了** —— 「启动前拒绝不进事件环」**早在 `826a57d` 就落地**；
>    顺着同一口径补了**真正缺的三处**（`replaceTaskIfRunning` 未结清传输 / ⭐在飞传输**此前完全静默** / `assignJob` kind 契约不全）
>    + 门禁 `rule_stop_event_ring` 从「全局计数 ≥2」改成**按站点结构断言（7 处）**，**六处注入全红**、旧版在同样注入下**六次全假绿**。
>    见 `D-338` 附注十六。
> 2. ✅ **CORE 结果缓存已落地（2026-09-20，`D-352`）**：同指纹 + 上次 PASS ⇒ **0.5 s 复用判决**（真跑 262 s）；
>    指纹 = `src/`+`tools/`+构建脚本+**世界母本**+**上游模组**+`server.properties`(剔注释)+`unix_args.txt` 的内容+`java -version`；
>    `--no-cache` / `ALICE_BATTERY_NO_CACHE=1` 强制真跑；**11 项反向对照全对**（其中 `=1` 第一版被静默当"关"⇒反而命中 ⇒ 已修）。
>    见 `D-352` / `docs/BATTERY_CURATION.md` §3.2。⚠️ 命中时 `check-all` 那行会带 **`缓存复用`** 字样 —— 那不是刚跑的证据。
> 3. `D-351`（LLM 记忆/上下文）= **仅登记**：真需求；四道前置（入通道 / 记忆库形状 / ⭐快照预算（世界事实优先）/ ⭐写入口只能提议）；
>    **触发 A（推荐）= 决策层队列 `GoalRecord` 落地之后**（与 `history` 共用落盘+裁剪+读入快照）；知识库/本地配置/记忆库**三类必须分开**（已登记）。
> 4. `D-333`（守卫冻结）与 `D-337` 附注四（`FarWalkTask`）各自挂着**触发条件**；`RoadBuilder` 已标 🅿️ **陈旧未验证**（要完整复查再决定）。
> 5. `survey/22`+`21` 复读剩余：② 收集器过滤面 → **核实为不做**（已落台账 + 触发条件）；③ 已交付；⑤ **不做**（冻结，已补开封条件）；⑥ 已处置。
>
> ### 过程教训（会再犯的，写死）
> ① ❌ **未提交改动面前不许 `git checkout <file>`**（今天把 `D-350` 改动冲掉一次，已重打并全部复验）；
> ② ❌ **Java 字符串里不许出现 ASCII 双引号**（今天踩 3 次；一律用 `「」`）；③ 探针用完即删（`D-348` 已示范）。


> ⭐ **六、`D-349`：勘测侧三处隐藏坑已处置（2026-09-20，用户拍板"一起做"）** —— 两条**落地**、一条**纯声明**。
> **Pit 1（"能保证"押在刹车上）**：§7 那四条是**刹车**（防失控），**不产生成功** ⇒ 标题校正 +
> 新 §7.5「能**成功**的全部来源」＝每个 kind 必须声明 `successCriterion` / **读世界事实的方法** / 不一致时怎么办。
> ⭐ **已落地且不是散文**：`JobKindContract` 声明表（5 个 kind）+ 门禁 `tools/check-job-kind-contracts.sh`
> （挂 `check-all`；**核对 `queryRef` 指向的方法真的存在**；三种注入实测全红）+ `JobLauncher.create` 受理闸
> （不齐 ⇒ 拒绝入队；`BotManager` 对 `null` 如实不起任务）。动机是三次真学费：`D-345/346` 判据永不可能成立、
> ⭐`D-348` 拿"查找表里在不在"当**代理**（真事实是"登记被推迟 1~19 tick"）。
> **Pit 2（`MAINTAIN` 没有"不变量还能不能维持"的判据）**：`RegionLumberJob` **早就算出**了"无树无苗无欠"，
> 但**只在 `idle-stop=true` 时**才用 ⇒ 默认常驻下"区域已永久不可作业"**没人知道**。修法 = 与模式无关地
> **如实登记 + 上报「可做什么」+ 事件环**、恢复后自清，⭐**不擅自收工**（用户 2026-09-12 裁定：常驻只由
> 玩家/决策层打断）。夹具 `region_maintain_unmaintainable`（EXTRA，自建空盒 + 自己 tick 真 Job）：
> 前提 ∧ 触发 ∧ **不越权**（登记时仍 `RUNNING`）∧ 恢复（真补种）＝ `checks=12 failures=0`；反向对照红 5 条。
> **Pit 3（升级链无上限）**：**只改声明**（今天没有升级链在跑 ⇒ 写代码 = 死代码）：
> `escalationCount >= ESCALATION_CAP(3)` ⇒ 强制 `END_QUEUE` + 待机 + 如实登记；
> **复核触发**：队列落地时 `goal_queue` 夹具若没加这条判据 ⇒ 本规则退回散文 ⇒ 应删。
> **状态**：代码/夹具/门禁/文档全绿（`single` + `module:lumber/pickup/mining` + CORE + `check-all` 见本文件末）。
>

> **本文件只是"当前断点"，不写历史**（历史在 `git log` 与 `docs/AI_DECISIONS.md`）。
> 会话恢复时**权威入口仍是 `docs/AI_PROJECT_STATE.md`**（AGENTS.md 的会话协议里列的是它）。
> 本文件 2026-09-14 从 300 行压到 ~70 行：旧内容（S0–S4 逐轮细节、R1 收口）全部可在
> `AI_DECISIONS.md` / `OPEN_ITEMS_LEDGER.md` / `git log` 里查到，按 AGENTS.md「规则准入尺子」第 3 条
> （只放指针，不抄原文）删除。

---

## 1. 一句话现状（2026-09-15 上午）

> **⏭ 2026-09-19 夜 断点（压缩后从这里接）。** 以下按"**最新在上**"；再往下的旧断点**只作历史指针**。

> ⭐ **最新断点（2026-09-20 日间）：`D-347` 运行账（`survey/22 §5.2③` 的"可测量判据"）已落地**。
>
> **一、这一轮做了什么**：复读剩下项里用户选 **④ 可测量判据**，范围 **A** = 累计出口 + 任务自报「到达」
> + 先红后绿夹具。**关键前提（决定了这不是从零建仪表盘）**：四个数里**三个已经在盘上、只是没有出口** ——
> 世界改动数（`WriteBudget.closeScope` 每任务已经打 SUMMARY）· 平均 tick（`TaskExecutionRecord.durationTicks()`
> 算了但只留最后一条）· 返回率（`SafeReturnTask` 终态已进 `complete()`，没人计数）；**唯一真空白 = 到达率**。
> ⇒ 落地 = 新 `bot/TaskMetrics`（累计台账）+ 四处接线 + `bot_report` 一行 + **两道"账活着"的门禁**
> + 取证夹具 `mine_run_metrics`。全文（含写入点表、实测表、4 次注入、5 条边界）见 ⭐ **`D-347`**。
>
> **二、实测数字（`single:mine_run_metrics` PASS，27 判据 0 失败）**：连跑 3 次真 `MineJob`（配额 2）
> + 1 次反向对照（目标不存在）⇒ **到达率 3/4**（反向对照那次到达增量 **0**、世界改动 **0**）·
> **平均 tick 99** · **世界改动 11**（`TaskMetrics` 与 `WriteBudget` **两个独立读数逐位一致**）。
> ⭐ **首跑推翻了我自己的假设**"挖 2 格配额 ⇒ 世界改动 2 格"：实测 3/4/4 —— 多出来的是**走位清障**
> （矿嵌在行走层 ⇒ 挖掉留 1 格深坑 ⇒ bot 站进坑里、出来清 1 格）⇒ 判据改成"两读数一致 + 配额下界 + 场景上界"。
>
> **三、反向对照 4 次（都真的红了，别省这一步）**：删 `arrived` 调用 ⇒ 4 条红；把 `arrived` 挪到
> `tick()` 开头 ⇒ 5 条红（**含反向对照那次变 1**）；删 `WriteBudget.noteBreak` ⇒ 交叉校验红（账 0 vs 独立读数 3）；
> 删 `beginTask.noteStart` ⇒ **无头驱动门禁红**（`verdict=FAIL` + `⛔ 运行账门禁未通过`，而夹具本身仍绿
> ⇒ 证明**两套断言互不冒充**）。
>
> **四、⚠️ 两个"接线点"不能挪（都踩过）**：① 终态必须钉在 `BotSession.recordTerminal`
> —— **不能挂 `complete()`**，因为 `immediateStop`（玩家 `/alice stop-task`、`doneWhen` 步、延后到安全点）
> 不走它（`D-338` 附注十五）⇒ 挂错会整类漏记"被显式停止"的运行；② `arrived` **刻意不去重**
> （原按 bot 去重 ⇒ 夹具直驱的子任务边界对账本不可见 ⇒ 连跑 3 次只记到 1 次）。
>
> **四之二、今晚的判据边界修正（`D-347` 附注一）**：夹具首版要求每次 `quota_met` ⇒ **同一条命令出现
> 间歇红**（`single` 红 / `module` 绿）。已改成**只判运行账**（到达增量恰好 1 + 世界改动与 `WriteBudget`
> 逐位一致 + 耗时 > 0 + 反向对照两次增量 0），把 `quota_met` 降为 SUMMARY 的**事实字段**
> （`quota_met次数` 实测 single **2/3** / module **3/3**）。**理由**：与判据无关的路径抖动让电池随机红
> = **假红**，与假绿一样有害；失败**照样完整打印**，只是不由本夹具定罪。判据数 27 → **24**。
>
> **✅ 五、`D-348`：那条尾巴查清了 —— 两条路径，一条是设计、一条是**真缺陷**（⏳ 修复待你拍板）**。
> **路径 A（模组取消生成）**：`[ChainMine]` 那 17 条/轮**不是稀有竞态**（每次 CORE 都稳定 17 条）——
> 连锁挖掘交给 Ore Excavation，它 **取消** ItemEntity 生成并缓冲（`captureAgent` 静态单例），结束时
> `dropEverything()` 在同格重新生成。探针实测这些实体**永远不会**进世界，而**同一轮里真产物被正常捕捉**
> （`raw_iron … provenance=OURS_DIRECT source=23,64,172`）⇒ **丢弃正确、无物品损失**。
> **路径 B（普通破坏，无连锁）**：⭐ **探针后验推翻了"永远不会进世界"** —— 被丢弃的同一个实体
> `id=75` 在 **1 tick / 4 tick 后 `visible=true`**（10 连跑循环第 7/8 轮各命中一次）。机制（调用栈实测）：
> `EntityJoinLevelEvent` 是在 `PersistentEntitySectionManager` **把实体登记进查找表之前**发出的
> ⇒ `getEntity` 当刻必为 null，而**登记可能被推迟 1~4 tick** ⇒ **在 tick 末只判一次就永久丢弃，
> 会把真的会进世界的掉落物丢掉**（收集器看不到它 ⇒ `MineJob` 如实 `product_not_collected`）。
> ⇒ ✅ **已修（用户拍板"加有界宽限窗口 + 先红后绿夹具"）**：① `PENDING_GRACE_TICKS=40` 宽限窗口内每 tick 复验
> （tick 末不再一次定生死）；② ⭐ **归属在"入队那一刻"解析好并随排队项携带** —— 只做①会让实体救回来
> 但**归属丢了**（登记时破坏记录已被 prune ⇒ `FOREIGN` ⇒ 照样捡不起来），这半截是**夹具逼出来的**。
> 取证夹具 `scope_pending_grace`（EXTRA，`PickupModule`）：绿 `checks=8 failures=0`（实测登记延迟 13 tick、
> 归属 `OURS_DIRECT`）/ 反向对照（注入窗口=0）**红 2 条**。**CORE 51/51** · `module:pickup`/`module:mining` PASS ·
> `check-all` 17 PASS/0 FAIL。丢弃日志改为打印**实测子项**；三处临时探针已全删。
>
> **六、验证（✅ 次日已收口）**：当晚 `single:mine_run_metrics` PASS（24 判据 0 失败）· `module:mining`
> PASS（10 步 / 106 s；编排器门禁 `started=+10 finished=+10`）。次日补跑 ⭐ **CORE = PASS 51/51**
>（4778 tick / 275 s，`extra_skipped=21` —— 比上轮多 1 就是新步）· ⭐ **`check-all` = 17 PASS / 0 WARN /
> 0 FAIL**（内含 CORE 再跑一次 PASS 274 s）· **`list-modules` = 21 模块**（`mining:PASS`；
> 只有声明过的 `harness_self:FAIL`）· 四条静态门禁 PASS（`step-names` 172/72 · `ref-integrity` 634/0 越界 ·
> `fixture-hygiene` 62 · `kernel-predicates` 九类全 0）。⇒ **`D-347` 弧收口**。
>
> **七、剩余可做项（复读结论，②③⑤⑥ 仍未做）**：② 收集器**物品过滤钩子**（`CollectDropsTask` 只有 UUID
> 白名单；半成品 `MineProductFilter` 只用于计数）· ③ **挖矿设计文档**（`D-329` 路线图第 1 步，`docs/` 里
> 只有 2026-09-09 的旧迁移稿 ⇒ 纯文档、离线可做）· ⑤ `HAZARD_ADJACENCY_PENALTY` 相对化（被 `D-333`
> 冻结压住，等实测触发）· ⑥ `FarWalkTask` 仍无生产调用方（台账第 102 行）。
> **建议顺序**：③（纯文档、零风险）→ ②（"不捡石头"一行接线）→ ⑤⑥（等触发）。
>
> **八、接续入口（`D-347` 已收口）**：① ⭐ **先做「五、」那件事** —— 查"真实挖掘掉落物被作用域当成
> 未进入世界而忽略"的触发条件（`D-345` 同一现象，今天出现在 bot 脚边 ⇒ 触发条件未定性）；
> ② 然后是 `survey/23`（2026-09-20 已拉取合并，`406fc82`）的三处"隐藏坑"与"开工前置检查三问"——
> 它是**对提案与方法论的判断**，不是项目事实清单，采纳与否要你拍板；
> ③ 再往下是复读剩下的可做项（② 收集器物品过滤钩子 · ③ 挖矿设计文档 · ⑤⑥ 等触发）。
> `AI_DECISIONS.md` 尾部 `D-347`（+ 附注一）是全文，台账第 98 行与队列索引已标记，
> `BATTERY_CURATION §2/§4` 已更新为 **72 项 / CORE 51 / `mining` 10 步**。
>
> ---

> ⭐ **前一个断点（2026-09-20 凌晨）：`survey/22` 复读（第二次）+ `D-345` 缺陷取证夹具已落地**。
>
> **一、复读口径**：用户要求"**复读最新勘测报告，已验过的不必重验，只看还能做什么**"。做法 = 逐条与
> `D-333`…`D-344` + 台账 + **代码**交叉核对（**代码为准**）。报告 = `survey/22`（506 行，最新实质）
> + `survey/21`（第三次更正）。
> **已核销、不必重做**：`§4.3` 斜向上升（`D-336` 已补 `dy=+1` + 门禁）· `§1.5②` `inventory_full`（`D-335`
> 复核=**非缺陷**）· `§3` 守卫线冻结（`D-333`）· `§4.1/4.2` Baritone 对照（`D-334`）· `§5.2①` 安全终点
> （`D-327` 附注一 + `D-338` ③/附注三/五）· `§9` 提权"搭和挖"（`D-305` ④ + 挖掘黑名单早已存在）·
> `§2` 记账边界（`D-327` 场所化）· `survey/21` 全部（`D-338` 已全面展开）。
> ⚠️ **两处勘测结论被代码推翻**（都是减负方向）：① `§1.3`「`origin=firstMined` ⇒ 收集要横穿通道走回去」
> **不成立**（`origin` 只进 `TaskTarget` 上报；真锚点来自种子落物 `CollectDropsTask:554`）⇒ 该待办作废；
> ② `§5.2②` "只扫已暴露/能不能扫"不是空档（`MineCandidateSource:174` 有 `hasChunkAt` 守卫）⇒ 真空白只是
> "**目标选择的合理性未验**"。
>
> **二、剩下可做（复读结论，按我建议的顺序）**：
> ① ✅ **挖矿收集的距离窗口**（`survey/22 §1.5①`）—— `D-345` 取证（红）⇒ `D-346` **已修**（上限由作用域派生），
>    夹具 `mine_far_drop` 已搬进 `MiningModule`（EXTRA）⇒ **本项已收口**（详见下面「四、」）；
> ② 收集器的**物品过滤钩子**（`§1.4`：`CollectDropsTask` 只有 UUID 白名单；半成品 `MineProductFilter`
>    已存在但只用于**计数**）——"不捡石头"从"做不到"变成"一行接线"；
> ③ **挖矿设计文档**（`D-329` 路线图第 1 步，`§5.2②`）——`docs/` 里只有 2026-09-09 的旧迁移稿
>    `MINE_MIGRATION_DESIGN.md`，路线图的"扫描契约 / 作业区 / 通道能力"设计稿**不存在**（纯文档、离线可做）；
> ④ **可测量判据**（`§5.2③`：到达率/返回率/平均 tick/世界改动数）——全仓 grep **零命中**，确实没人测过；
> ⑤ `HAZARD_ADJACENCY_PENALTY=20.0` 固定值（`MovementContext:109`）——被 `D-333` 守卫冻结压住，
>    **只在"实测特别影响效率/安全性"时**才做；
> ⑥ 顺带：`FarWalkTask` **仍无生产调用方**（台账第 102 行，复核触发已设）。
>
> **三、本轮落地（`D-345`）**：`task/MineDropRangeCheckTask` + 模块 `mine_drop_range`（步 `mine_far_drop`，
> **故意红**：`expectedVerdict()=FAIL`、**不进电池**、只走 `module:mine_drop_range`）。机制 = 收集器
> `MAX_CHASE_DISTANCE=32` 硬墙 + `retired` **永不清空**（`CollectDropsTask:90/504-505/492`）＋ `MineJob`
> **挖满才起收集**（`:309-311`）⇒ 收集第一 tick 就把 32 格外的产物**永久退休** ⇒ `gained < minedCount`
> ⇒ `FAILED product_not_collected`（`:331-337`）。**今天就能咬到**：`32 < 2 × SCAN_RADIUS(24) = 48`。
> **红证据**（`run/headless-logs/20260920-002625-module_mine_drop_range.log`）：
> `落物就位 可见=2 adopted=2 近件=8.00 远件=40.00` → `[CollectDrops] retire … reason=too_far itemPos=3240`
> → `SUMMARY collected=1/2 unreachable=1` → 夹具 `checks=10 failures=2`（**恰好只有两条 ⭐ 判据红**）。
> **验证等级**：`SERVER_TESTED`（无头）· `check-all` **16 PASS / 1 WARN（headless 未跑）/ 0 FAIL** ·
> `module:mine_drop_range` = FAIL（**预期**）· `list-modules` 期望表含 `mine_drop_range:FAIL`（注册表 **22** 个模块）。
> ⚠️ **无生产行为改动 ⇒ 不需要你复测**（也没有客户端可见变化）；**修法未定，不许动生产代码**：
> ① 抬墙/可重扫（32 格硬墙改相对判据）② 挖一段捡一段（让缺口不出现；那时夹具要**改成链路级**，不许删）。
> ⚠️ 已知边界：**定向模式跑不了这一步**（`single:` 只认 CURATION 里的步名，而该步刻意**不在** CURATION 里
> ⇒ 只能走 `module:mine_drop_range`；这也是为什么本文不写它的「`single:` + 步名」形式 —— 写了会被
> `step-names` 门禁判成「未知步」，而那条门禁是**对的**）。

> ⭐ **四、同一轮已修复（`D-346`，用户选「只做 A」）**：追取上限从写死的 `32` 改成
> `max(32, 2 × scope.currentRadius())` —— 理由 ⭐ **不是「该不该设上限」，而是「这个上限比它自己的作用域还小」**
> （`MineJob` 作用域半径 = `SCAN_RADIUS` 24 ⇒ 直径 **48** > 32；能进 `liveDrops()` 的落物**只可能是
> 本作用域内登记的** ⇒ 32 必然丢自己人）。`D-074` 裁定的 N 由实现定 ⇒ 口径不变（"不追世界另一头"仍成立）。
> **A/B（同一夹具同一场景）**：修复前 `clusters=1 / collected=1 / 地上剩 1 / FAIL` → 修复后
> **`clusters=2`（第二个簇锚点正是远件 `3240`）/ `collected=2` / 地上 0 / PASS**，收集器里**再无
> `reason=too_far`**。**夹具已翻面**：`mine_far_drop` 搬进 `MiningModule`（EXTRA）+ CURATION，
> 临时模块 `mine_drop_range` 与注册表项已删（`BATTERY_CURATION §3.1` 改成"翻面示例"）。
> **验证**：`single:mine_far_drop` PASS · `module:mining` PASS（8 步，95 s）· ⭐ **CORE 51/51 PASS**（271 s，
> 声明 71 项 = BASELINE 15 / MAIN 36 / EXTRA 20，`mine_far_drop` 正确落在 EXTRA ⇒ CORE 步序未变）·
> `check-all` 16 PASS / 0 FAIL · 反向对照 = `D-345` 那轮红证据本身（同夹具去派生 ⇒ 红）。
> ⚠️ **仍然不需要你复测**（无客户端可见行为）；改动面 = `CollectDropsTask`（收集追取上限，被挖矿/伐木/
> 区域/恢复多处共用）+ `ScopeBuffer.currentRadius()`（只读）。

> ⏭ **五、压缩后从这里接（2026-09-20 00:50，HEAD=`803adc4`）**：
> 1. **本弧已收口**，没有任何在飞任务/未提交改动（工作区干净、已推 `github/master`）。**不要重做**：
>    `D-338` 主线 · `D-339`/`D-340` · `D-341` · `D-342` · `D-343` · `D-344` 全弧 · **`D-345`+`D-346`**（含
>    "已核销不必重验"的那 8 项勘测条目，清单在本块「一、」）。
> 2. **下一步等你点一个**（`survey/22` 复读后的剩余可做项，见本块「二、」）：② 收集器**物品过滤钩子**
>    （复用已有 `MineProductFilter`）· ③ **挖矿设计文档**（`D-329` 路线图第 1 步，**尚不存在**）·
>    ④ **可测量判据**（到达率/返回率/平均 tick/世界改动数，全仓零命中）· ⑤ `HAZARD_ADJACENCY_PENALTY`
>    相对化（被 `D-333` 冻结压住）· ⑥ `FarWalkTask` 无生产调用方。**我的建议顺序：④ → ② → ③**
>    （④ 直接回答 `survey/22 §5` 的"几乎能保证到达并返回吗"，且纯测量、不改行为）。
> 3. **口径提醒**：默认**无头**；客户端轮次只用于"证据本身在客户端"的类别（见 `TESTING_GUIDE`
>    「客户端轮次的准入尺子」）；动生产代码 ⇒ 收口要跑 CORE；临时红模块只活到修复那一刻
>    （`BATTERY_CURATION §3.1` 有完整翻面示例）。
> 4. **工件**：jar `JAR_CONTENT_SHA256=7c41ae43…`（已同步 client + 仓库镜像）；证据归档在
>    `run/headless-logs/20260920-{002625,003713,003912,004359}-*.log`（红/绿/CORE 三段都留了）。

> ⭐ **当前状态（2026-09-19 夜，最新）**：本弧（保护区反向测试 → 夹具血脉闸门 → 循环闸）**已收口**：
> · `D-339` 夹具**终态**不交 LLM（`fixture_terminal_silent`）—— 客户端已验证"符合预期"
> · `D-340` 夹具**事件**不交 LLM（`fixture_event_silent`）—— 客户端 ×23 留痕、全会话零多余 LLM 调用
> · `D-341` **「无权」≠「没有」**：区域作业在"树全被永久拒绝"时**如实失败** `no_permitted_candidate`
>   —— 客户端端到端已验证（`task_terminal_reason kind=region_lumber driver=llm terminalReason=no_permitted_candidate`）
> · `D-342` **循环受理闸**（同一 `kind|目标` 在 1200 tick 内失败 2 次 ⇒ 拒再起 + 如实回读；玩家显式豁免）
>   —— 🔴 客户端首测是**反例**（三次 `instruct` 全放行）：根因 = 身份**跨边界字符串匹配**（受理侧
>   `JobRequest.Kind.name()` 大写 / 终态侧 `Task.taskName()` 小写）⇒ **已做结构修订**：在飞身份只由 LLM
>   受理侧写、`BotManager.beginTask` 在非 LLM 派活时 `clearAttempt` ⇒ **不再需要任何跨边界匹配**；
>   `noteTerminalOutcome` 去掉 `kind`；`task_zone` 判据 **94 → 93**（删掉已废弃的"跨写法"断言）。
> **证据（全无头）**：`single:llm_contract`（10 判据全绿）· `single:task_zone` **93/0** ·
> `module:{llm,lumber,protection}` · **CORE 51/51（`ticks=4777`）** · 内核规则
> （`rule_stop_event_ring` / `rule_no_permitted_candidate` / `rule_loop_admission` / `rule_bulk_write_zone_gate`）PASS ·
> 反向对照**每条机制都做过**（明细见各 `D-3xx`，含"注入被弱规则放过 ⇒ 改结构断言"两次教训）。
> **jar 已同步**：`alice-1.0.0-1.20.1.jar` **`JAR_CONTENT_SHA256=c246fab65a9134ba…`**（`D-347` 后重新同步，2026-09-20 01:28；上一版 `7c41ae43…` 已过期；⚠️ 这次**没有客户端可见行为改动** ⇒ 换不换 jar 都不影响你上次的观察，换 jar 仍旧要**重启客户端**）。
> ⚠️ `642e388b → 80eab57f` 那一次**不是行为变化**（只加注释 ⇒ 只动了 `LineNumberTable`，已用 `javap -c`
> 逐指令比对确认）；而 **`80eab57f → eea49c93` 是真实行为变化**（`D-344` 片 A：区域"扫地面"阶段）——
> 但**没有需要你复测的客户端可见行为**（判定/配置层已由无头夹具 `region_sweep` 19 判据覆盖）。
> ⭐ **本弧最重要的口径（用户裁定，不许再犯）**：**默认无头**。客户端轮次只用于"证据本身在客户端"的类别
> （渲染 / 物理 / GUI / 同步 / 真实模组交互 / 真人观感）⇒ 见 `docs/TESTING_GUIDE.md`「客户端轮次的准入尺子」。
> 交付话术必须明说"**不需要你复测**（已无头覆盖：`<命令>`）"，或"需要，因为 `<客户端才有的信息>`"。
> ✅ **两项未决已裁定（2026-09-19，`D-343`）**：① **`L2` 不加**区内放置配额（**无洞**：`WriteBudget` 同按
> `scopeId` 计默认 32、随任务生灭；加了会伤补种/火把/垫脚 ⇒ 假拒绝）· ② **`RoadObstaclePolicy` 裸判据有意保留**
> （那是**规划期规避**（收紧）不是欠账，写入闸门没绕过）⇒ 已变**可失败断言** `rule_bulk_write_zone_gate`
> （含 ⭐"**不许顺手接上阶梯**"反向断言；三条反向对照都做过）。新增第 20 项：**玩家显式在自认领区修路做不了**（道路任务不声明任务区 ⇒ 未开工）。
> **⏳ 下一步候选**（台账 `§5.12`）：① **区域补种异步化 + 可配置拾取清单**（第 13 项）——
> ⭐ **`D-344` 片 A/B/C 全部落地入库**（本弧收口）（`D-344`：`sweepDecision` 纯判据 / `sweep` 阶段 / 互斥 / `SESSION` 短 TTL
> 授权 + `revoke` / `SWEEP_NO_PROGRESS_LIMIT=3` + `sweep_no_progress`；夹具步 `region_sweep` **19 判据** + 端到端 `region_sweep_e2e`（**抓出 3 个真缺陷**）；`CORE 51 步 PASS`；
> 内核规则 + 反向对照 4 条、夹具反向对照 3 条全红）。⇒ ✅ **片 C 也已落地**：`/alice region pickup add|remove|list`（读主手、零参数）+ 退避豁免（`workedThisPatrol`）。**本弧无剩余待做项**；可选项见台账 `§5.12` 第 15 项（注册容器卸货）。
> 方案全文 `docs/REGION_REPLANT_ASYNC_DESIGN.md`。
> ② 区域内**注册容器卸货**（第 15 项）· ③ 决策层**队列 + 持久终态**（`docs/DECISION_LAYER_FINAL_FORM.md`，设计定稿·未实现·未验收）。
> ⏭ **压缩前断点（2026-09-19 深夜）—— 下个会话从这里接**
> ⭐ **在飞意图（用户刚下达、被压缩打断）**：**审计最新勘测报告**
> `survey/22-挖矿形态与风险守卫与垂直移动-20260918.md`（506 行）+ `survey/21-勘测侧更正回写-第三次-20260918.md`
> ⇒ **过滤掉已验证/已落地的部分，只列「还有什么可以做」**（用户原话）。
> **已定的做法**：逐条与 `AI_DECISIONS.md`（`D-338`…`D-344`）+ 台账 `§5.12` + **代码**交叉核对，
> **以代码为准**（报告若称"未做"而代码已做 ⇒ 算已落地）；输出**按可做性排序**的清单。
> ⚠️ **别把「台账 `§5.12`」与「勘测报告 `survey/`」混为一谈** —— 用户要的是**后者**（我上一轮误审了台账）。
> **我上一轮已完成的台账侧审计（结论已入库 `68a29b5`，不用重做）**：
> · 第 14 行（LLM 起任务留痕）**基本已做**：`GoalDirector:655` 已有 `[alice] 决策层：已起 Job <desc>`，
>   `tell()` 还**总留一行** `[Goal] notify … delivered=N text=…`（`:763`）；driver 归因另有门禁 `F1-P1` +
>   步 `driver_label` + 终态 `task_terminal_reason … driver=`（`BotManager:2397`）⇒ 剩余只是
>   "要不要也进事件环/落盘"（窄）；那轮"聊天零提示"的成因 ⚠️ **日志已被覆盖、不可追溯**。
> · 第 17 行（中断点语义）**一半过期**：`D-338` 附注十五已让玩家显式停止记 `STOP` 进事件环；
>   真问题只剩**「不叫 LLM」**（有意还是缺口，待裁定）。
> · 第 11 行（区域补种缺料）**区域内那一半已被 `D-344` 吸收（有实证）**，只剩「区外/其它来源」是否给权限。
> · 其余待办（第 10/12/15/16/18/2/3/4/5/6/9 行）审计为**确实仍开**。
> **另需修（若下轮做勘测审计时顺手）**：`docs/OPEN_ITEMS_LEDGER.md` 的**队列索引**里我把第 14 项
> 留在 P1（应已降级/撤下）。

> **历史指针（从这里**不要**接，细节查 `AI_DECISIONS.md` 与 `git log`）**：
> · `D-338` 附注十四/十五（保护区里非玩家发起**封顶 `L1`** + **事件环补全**）—— 客户端三轮：**三通过 + 一处真缺陷**：
>   玩家自起照旧干活（`chopped=0→5` + `STEP_PLACEMENT` **真垫了方块** + `scaffoldLeft=0`）、封顶生效
>   （LLM 自起那轮 5 棵树全 `zone_break_not_allowed`）、`bot_report` 快照 `recentEvents` 里
>   `type=STOP`；❌ 缺陷 = 被拦下的任务**不"如实失败"而是空转 20 分钟** ⇒ 由此引出 `D-339`~`D-342`。
> · **下一节是 19:00 之前的旧断点**（`§5.12` 第 4 件、队列 ①→④ 等），**已被本弧取代**；其内残留的
>   "下一步/客户端复验清单"**已删除**，避免照着过期指令做事。
> **⏭ 2026-09-19 断点（队列 ①→④ 施工线，仍在进行）**：
> ① **扫描器加载守卫** ✅ `fb6e142`（`hasChunkAt` ⇒ 未加载记「未扫」+ 不变式/不加载两条门禁，先红后绿）
> ② **`inventory_full` 复核 = 非缺陷** ✅ `8ccb47e`（`MineJob:235` 每 tick 都跑 ⇒ 本就是作业中守卫；
>    我按勘测报告补的那条被红证明证伪 ⇒ 已撤；补的是**矿侧门禁** `mine_inventory`）
> ③ **斜向上升那一格** ✅ `bc5ffaa`（`PLACE_STEP_AND_TRAVERSE` `dy=+1`；挖矿信封修前**不可达**⇒修后 1 条边；
>    门禁 `place_step_diagonal` 三用例；收口 CORE **17 PASS/0 FAIL**）
> ④ **粗目标 + 滚动重规划**：一半 ✅ `5a88167`（`GoalSpec.exactFoot()` + **`GoalNearXZ`** + 守卫仅对精确目标生效；
>    ⚠️ **生产未接线**，目前只有门禁用它）。
>    ✅ **下半段（`D-337`）已修 + 门禁（2026-09-19，先红后绿）**：机制查证 = 那条跨区块**边闸门是后置**的
>    （读发生在它**之前**且更远：候选生成/危险厌恶 ≤2 格、粗目标 `isInGoal` 纯算术）⇒ 读先加载、门随后看到
>    "已加载"而放行 = **自增强泄漏**（这才是 224→384 **连续**翻真的原因）。修法 = `MovementContext` 加
>    `READ_FOOTPRINT_RADIUS=3` + `readFootprintLoaded()`（只 `hasChunkAt`）+ 搜索在 `appendCandidates`
>    **之前**的读脚印闸门 + 边界语义（`boundaryBlocked>0` ⇒ 有前缀给 `PARTIAL`、无前缀给 `SEARCH_LIMIT`，
>    **绝不 `UNREACHABLE`**：未知≠到不了）。红 `failures=1 newlyLoaded=6` → 绿 `failures=0 newlyLoaded=0` +
>    `PARTIAL prefixLen=198 progress=197` + `diag boundary_blocked=117 skipped_unloaded=0`。
>    ✅ **洪泛 + 生产接线（`D-337 附注二`）也已落地（2026-09-19）**：⭐ `FarTravelHop`（只读 `hasChunkAt`
>    采样已加载前沿 ⇒ **夹到边界内侧** + `GoalNearXZ` 半径版）+ ⭐ `FarWalkTask`（生产任务：**反复跳**，
>    护栏 = 跳数/tick/单调性；到达口径粗 ⇒ 精确落脚接 `WalkToTask` = Baritone `GoalNear`→`GoalBlock`）。
>    **A/B（同一轮）**：粗目标 `20000 节点 / 142~186 ms / PARTIAL` vs **一跳 `161 节点 / 1 ms / REACHED`**；
>    **执行侧**：300 格 = **hops=2 / 1111 tick / DONE** + 精确落脚 30 tick ✓。判据 19 项 / 0 失败。
> ✅ **同一病理的最后一处（任务层）也收口了**：`PlaceTask` 先问 `hasChunkAt` 再读目标方块 ⇒ 未加载报
>    `place_target_unloaded`（红：`newlyLoaded=1` + 误导性 `place_no_path` → 绿：`newlyLoaded=0`）；候选站位扫描也按列
>    问一次（`target ±4` 可能跨区块）。⇒ **内核 / 走 / 扫描 / 放置四处全部收口**（`D-337` 附注三）。
> ✅ **`D-327` 执行项 ①（回程兜底 = 机制 B）已落地**（2026-09-19）：`SafeReturnTask`（一跳一跳逼近 + 终点精确落脚）
>    + `BotManager.complete` 接线（判决 `SafeReturnTask.shouldStart`：**区外 + 有认领区**；无认领区 ⇒ 一字不变）
>    + 门禁 `safe_return`（EXTRA，15 项）：无区 ⇒ `return_no_safe_zone`（不许移动）/ 200 格外 ⇒ **2 段 732 tick 走回区内** /
>    封死格 ⇒ `return_unreachable` 且**原地不动**。**用户本轮重新声明**：就地固守**不是兜底**（是「避免死亡的最保守行为」，现在不做）
>    ⇒ 返程失败就**站定不动**（临时兜底）。**`D-327` 剩**：② 预算/账本收窄到保护区 + 重写 6 个 CORE 步（口径为主）；③ 野外审计粗细度。
> 📌 **`D-338` 已登记（2026-09-19，零代码）**：保护区 = **具体父类**（资产保护 / 账本 / 权限要求 / 默认无修改权限）
>    + 子类 **安全区**（必须落在保护区内；默认无权限无预算）/ **任务区**（任务自声明、随 scope 生灭；目标内 `KEEP` /
>    目标外提权+预算+`TEMP`）；**账本新增 break 条目**（默认不恢复，限保护区）；**恢复解耦边界**（世界原状恢复显式下令，
>    脚手架自拆保留自动）；**返程优先级 归位点 > 安全区 > 保护区**+ **自适应安全范围 = 内部区块（四邻腐蚀）**；
>    ⛔ **挖矿 = 野外采集**（只扫保护区外，今天已成立）⇒ "工作面第三类"**不做**。落地清单 = `OPEN_ITEMS_LEDGER §5.12`（8 件）。
> ✅ **`D-338` 执行项①/③ 已落地**（2026-09-19，`D-338` 附注一）：`SafeZoneData` 加**安全区子集**（`safe_chunks` NBT +
>    `SafeDeclare`；不变量"安全区 ⊆ 保护区"三条路都堵：拒声明 / `unclaim` 连带清 / `load` 丢孤儿并计数+summary）+
>    **`internalChunks` 四邻腐蚀**（自适应安全范围，纯函数，1 区块/2×2 ⇒ 空 ⇒ 退化"进区即到"）+ 命令
>    **`/alice protect safe claim|unclaim`**（**零参数**，作用对象 = 执行者所在区块）+ `/alice protect list` 打印
>    "当前位置两态 + 内部区块计数"。门禁 `protection_zones` 判据 **67 → 95**（+28，含命令入口三例）；
>    **先红后绿**（拆子集检查 ⇒ `failures=4` 恰好是依赖该不变量的四条）→ `checks=95 failures=0`；
>    `ALICE_HEADLESS=1 check-all` = **17 PASS / 0 WARN / 0 FAIL**（CORE 51/51）。✅ **用户客户端实测命令口径符合预期
>    ⇒ 命令路径 `USER_ACCEPTED`**。⚠️ 小基地（1×1~3×2）没有内部区块、退化"进区即到" —— **用户 2026-09-19 裁定
>    "保持现状"**（"有问题应该**鼓励玩家设定归位点**，而不是优化没必要的逻辑"）⇒ ⛔ 不要再提八邻 / 质心退化 /
>    K 格内缩；正解 = `§5.12` 第 8 件（归位点）。
> ✅ **`§5.12` 第 2 件已落地**（2026-09-19，`D-338` 附注三）：返程判据从"进认领区块即到"切到**优先级链**
>    （归位点（待落地）> **安全区内部** > **保护区内部** > 内部为空则退化"进区即到"）；**到达判据 = 返程到达集**
>    （`SafeZoneData.isInReturnZone` / `nearestReturnCell`，纯集合查询、不读方块）。门禁 `safe_return` 重写成
>    **5 用例 30 判据**（含⭐优先级链判决 + 单区块退化）；**先红后绿**（旧实现 `failures=4` ⇒ 恰好是设计的三处 + 同源一条；
>    绿：`safe @2992 内部=true 3 段 734 tick` / `prot @2976 内部=true 2 段 292 tick` / `single 退化` / `sealed 1 tick 不动`）。
>    `module:protection` **2/2**、`ALICE_HEADLESS=1 check-all` = **17 PASS / 0 WARN / 0 FAIL**（CORE 51/51，261s）。
> ✅ **第 8 件归位点已落地**（2026-09-19，`D-338` 附注五）：`protection/ReturnPointData`（**每 bot 一个**：dimension+pos+radius=3）
>    + 命令 **`/alice bot-home set|clear|show`**（零参数：`set` = 以你的站位为准，作用于该维度第一只假人）
>    + 返程链**最前项**（`zone=home`；有归位点 ⇒ 跳过区几何；**跨维度忽略**）。门禁 `safe_return` 判据 **30 → 41**，
>    **反向对照先红后绿**（假装无归位点 ⇒ `failures=3` 恰好是归位点三条；绿：`home DONE@2933,4064 dHome=3.0 dZone=93.0`）。
>    `module:protection` 2/2、`ALICE_HEADLESS=1 check-all` = **17 PASS / 0 WARN / 0 FAIL**（CORE 51/51，260s）。
> ✅ **`§5.12` 第 4 件的「几何 + 锁定」层已落地**（2026-09-19，`D-338` 附注六）：新 `protection/TaskZoneRegistry`
>    —— **工作区域（方块级）⇒ 任务区（区块级最小覆盖）**、**单向派生**；`WorkArea`（两角规范化）/`Zone`（带
>    `scopeId`/kind 元数据）/**纯函数** `chunkCoverOf`（矩形与**逐方块枚举**两条路径必须同结果）/`safeZoneConflicts`。
>    **口径**：任务区**可以覆盖保护区父类**；与**安全区**（子类声明）有交集 ⇒ **拒绝 + 报错 + 不裁剪、不降级**
>    （要走必须**显式退化**）；**随 `scopeId` 生灭**（`zoneOf` 每次拿 `currentScope` 复核 ⇒ 作用域一收尾权威立刻消失，
>    **不靠谁记得来关**）+ 作用域收尾两处钩子（`BotManager.clearTask` / 电池 `endStep`）；没有作用域 ⇒ `NO_SCOPE`。
>    ⚠️ **本片零权限改动**（破坏闸门照旧；判据里有一条"`protectionReason=protected_area`"钉住这一点）。
>    **生产接线**：`RegionLumberJob` 首 tick 解算（工作区域 = 玩家林场矩形），冲突 ⇒ `task_zone_conflict` **如实失败**
>    （失败事实带冲突区块 + 退路提示），终态自动解除；只读面 `/alice protect list`（三态）+ `/alice region info`（预检）。
>    门禁 `task_zone`（**EXTRA**）**50 判据 / 9 组**；⭐**反向对照两次**：拆冲突检查 ⇒ `failures=8`（**恰好**冲突那两组，
>    几何/生命周期/覆盖全绿）；`zoneOf` 退回"按 owner 找" ⇒ `failures=2`（**恰好**两条生命周期判据）⇒ 复原绿
>    `checks=50 failures=0`。`module:protection` **3/3**、CORE 未动（CORE 仍 51 步；总步数 67 → **68**）。
>    已同步客户端 `mods/`：`alice-1.0.0-1.20.1.jar` `JAR_CONTENT_SHA256=e8dfd174345d99c72e589c1522027a2ea94b6b1846f942ca36478fd15ae38597`（用 `tools/jar-content-hash.sh` 复核"同一版代码"）。
>    ⚠️ 附带教训：**第一次生命周期注入无效**（只改尾部、`currentScope==null` 的早退还在 ⇒ 注入后仍绿）——
>    "注入必须真的拆掉待证机制"；另把该判据的顺序改成**先重开作用域、再问 authority**（否则会被 prune 侥幸顶绿）。
> ✅ **两条待拍板已关闭**（2026-09-19 用户）：① **冲突 = 任务如实失败、不继续跑**（现实现状即终态，零代码改动）；
>    ② **权限阶梯我同意**（`D-338` 附注七②：`L0 只读` / `L1 临时脚手架(≤8 放置)` / `L2 工作面(目标内 KEEP + 目标外逐块提权)` /
>    `L3 全权(仅玩家显式)`）。
> ✅ **`§5.12` 第 4 件下半段已落地**（2026-09-19，`D-338` 附注八）：**权限阶梯接进闸门** ——
>    `WritePolicyMatrix.Level`（等级**单一出处**，`L3` 无玩家驱动身份 ⇒ 降级 `L2`）+ 新 `protection/ZoneAuthority`
>    = ⭐**一个判据、多处消费**：① 候选扫描（`MineCandidateSource`/`LumberCandidateSource`）② 破坏闸门（`BlockBreakSafety`）
>    ③ **放置闸门**（`BlockInteraction.placeAt` —— 今天**完全没有**保护区检查 = `D-338` 核对表里的缺口，本片补上，
>    `PlaceResult` 加 `ZONE_DENIED`）④ ⚠️ **能力闸门**（`PathSession`→`CapabilityGate`，2026-09-19 客户端实测**漏接**
>    ⇒ 保护区内 `PILLAR` 被拒 144 次；"三处消费"的说法**作废**，见 `D-338` 附注十）。`L1` 的"≤8 次"= **区内**配额（不是作用域级预算上限 ⇒ 野外在 `L0` 期间照旧可写）。
>    ⚠️ **一处变严**：保护区内**放置**从"没闸门"变成"要任务区 + 等级"（无任务区时码仍逐字 `protected_area`）。
>    门禁 `task_zone` **50 → 76 判据**；⭐**反向对照三次**：拆候选那处消费 ⇒ `failures=2`（恰好两条候选判据）；
>    破坏闸恒放行 ⇒ `failures=3`（恰好 `L0`/`L1`/候选 `L0` 三条**拒绝**判据）；破坏恒拒 ⇒ `failures=3`（恰好 `L2` 三条**放行**判据）。
>    绿：`checks=76 failures=0`、`module:protection` **3/3**、`ALICE_HEADLESS=1 check-all` = **17 PASS / 0 WARN / 0 FAIL**
>    （CORE **51/51**，`ticks=4762` ⇒ 收紧对 CORE **零回归**）。已同步客户端 `mods/`：
>    `alice-1.0.0-1.20.1.jar` `JAR_CONTENT_SHA256=da9af34fcdc77b5dfa300dc3725a3c81347dba29fd0c38981a022877bbd0b71d`（含第四处消费修复）。
> 📌 **客户端第二轮已跑（2026-09-19，`D-338` 附注九）**：用例 1（保护区里划林场 `level=L2 chunks=6` + 真的砍到树）
>    与用例 3（安全区冲突 ⇒ `task_zone_conflict` 如实失败、1 tick 终态）**成立**；用例 2（无任务区的一次性砍树）
>    日志显示"5 棵树全 `protected_area`、1 tick FAILED"，**与用户观察不一致 ⇒ 待问清入口/时机**。
>    用户报告"高树没搭柱子"拆成两件：2×2 高云杉 = 候选期 `trunk_too_tall`（设计拒绝，新需求另议）；
>    真正卡住的是云杉树叶清障 **40 次 `world_unchanged`**，全部在用户 `/alice ftb bind` **之前**、bind 后 0 次
>    ⇒ **是 FTB 不是我们的闸门**。本轮**真缺陷**（我引入、已修）：`[ZoneAuthority] ALLOW` 被规划期谓词反复触发而刷屏
>    （50 ms 30 行）⇒ 按 (格, 理由) 去重 + 上限 512；门禁 `task_zone` **76 → 77 判据**。
>    ⚠️ 用户报告①**查出真回归**（`D-338` 附注十）：`PathSession`→`CapabilityGate` 是**第四处**保护区判据、
>    我上一片**漏接了**（走裸 `SafeZoneData`）⇒ 保护区块里 `PILLAR`"垫一格"被拒 **144 次**、全轮 `places=0`
>    ⇒ 云杉最高一格跳过；离线同场景（无认领）`7/7` + `places=9` 锁根因。**已修**：`CapabilityGate.Facts`
>    带 `placing` 语义 + `ZoneAuthority.silentRefusal/movementRefusal`，判据 **76 → 82**。
>    ⚠️ 用户报告② = **观察归属**：一次性砍树那次**被拒了**（5 棵树全 `protected_area`、1 tick FAILED），
>    砍树的是 **2 秒后 LLM 自起的 `region_lumber`**（聊天零提示）⇒ 待拍板：LLM 能否自起 `L2` 任务 +
>    LLM 起任务要在聊天留一行。
>    ⚠️ **本轮没覆盖**：真正的 `PathSession` 会话级复验 ⇒ 下一轮客户端必须复看 **`places ≥ 1`**（`[Ledger] place …
>    cobblestone [TEMP STEP_PLACEMENT]`）；另见台账 §5.12 新增第 10–14 项（攀爬未验 / 区域补种缺料 / 高树支持 /
>    **区域补种异步化需求** / **LLM 自起 L2 政策**）。
> 📌 **决策层最终形态已落档**：`docs/DECISION_LAYER_FINAL_FORM.md`（任务队列 + 持久终态 + 历史落盘，
>    **设计定稿 · 未实现 · 未验收**，2026-09-19 用户提案；摘要见 `DECISION_LAYER_DESIGN.md` §7）。
>    **与主线弱耦合、不阻塞**；它是「多层任务失败向上传递」的成熟形态 + 机器线"维持生产"的落点。
>    临时缺口见台账第 18 项（保护区封顶 `L1` / 事件环补全 / 最小循环检测 / 可观测性已做）。
>    **下一件**：① 把上面的攀爬用例补上（客户端）；② 默认任务区（第 6 件：玩家预存工作区域 + 启动时派生 + 冲突报错）或 **勾选界面的等级选择**
>    （第 9 件，⚠️ 操作逻辑要先给你审核）；备选线 = 第 3 件（`WorldModLedger` break 条目）。
> ⚠️ **④ 唯一剩余**：`FarWalkTask` **还没有生产调用方**（复核触发：下个增量仍无调用方就删）（预期先给 `D-327` 机制 B「返回安全区」或决策层
>    「去坐标」目标；后者要改闭集动词表，属另一件事）—— 已登记台账。
> ⚠️ 自测档位按 `D-332`：小改动只跑 `single:`，CORE/全量留到收口。


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

## 2026-09-20 断点（真机实测 A 路线：工具已就绪，等用户新世界一轮）

**用户下一步（已在做）**：新建**普通世界 + 和平难度**，跑 `/alice mine here` ⇒ 观测量 = 挖矿的
水平位移分布 / 向下占比。**操作·预期·判读·要交回的证据** = `docs/MINE_SURVEY_PROTOCOL.md`（单页，先读它）。

**已就绪（都已提交 + jar 已同步到固定客户端 `runtime_sha256=a192ee38…`）**：
- `/alice mine here`（零参数：bot 当前位为中心、半径 24、**配额 64**、**12000 tick**、`#forge:ores`）⇒ `de39d4f`；
  （**口径已改**：原 8 / 3600 在第一轮实测里被 55 格带内候选截断 ⇒ 用户 2026-09-20 裁定改成 64 / 12000）
- **阻断 LLM 接手** = `ManualTestLock`（锁上时 `BotManager.assignJob` 一律拒绝并记事件环；放行口 = 作用域内一次性窗口）；
- 终态打点 `[MineSurvey] SUMMARY …`（收口在 `MineJob.finish` 一处，四条终态路径共用）+ **自动解锁**；
- 世界侧拒绝归因 `world_refused`（`D-359`）：被 FTB 认领/保护层拦下不再显示成"这里没矿"；
- 环境：连锁挖掘已禁用（`mods-disabled/`）、透视包 `AliceXray`（带边框透明玻璃，`tools/gen-xray-pack.py` 可重生）。

**这一轮我（AI）已验证/未验证**：
- 已验证：`single:mine_survey` PASS(12 判据) · `single:break_refused` PASS(25) · `single:scope_pending_grace` PASS ·
  **CORE 51/51 PASS**（`/home/fb486/alice-server/logs/latest.log` 的 `RESULT verdict=PASS ticks=4768`）·
  `check-all` pass=19 warning=0 failed=0。
- **未验证**：`full` 整链（修复夹具前提后没再跑，用户指示只跑 CORE）；`/alice mine here` 的真机效果；
  真实矿脉场景下的簇消费；`break`/`clear`/`risk` 成本分量（未实现）。

**回归护栏（改动别踩）**：门禁 `kernel-predicates.py` 现有 17 条规则（新增：`世界侧拒绝要归因` / `实测锁要挡LLM`），
每条都做过注入反向对照；`tools/machine-map.py` 的复核目录 = `mods/ + mods-disabled/`。
**读数陷阱**：harness 打印 `verdict=<无> exit=3 用时=9s` 时**不要**当红 —— 以服务端 `latest.log` 的
`[Headless] RESULT` 行为准（`BATTERY_CURATION.md` 有判据）。
