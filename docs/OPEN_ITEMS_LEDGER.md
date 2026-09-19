# 未收口项总账（2026-09-12 整理）

> **用途**：把散在 6+ 份勘测/审计报告里的"还没做完 / 还没验证 / 报告已过期"集中到一处，
> 每条给出来源、**当前复核证据**、**验证判据**、触发条件。避免"半年后重新发明或永久遗忘"。
> **规则**：只登记**可行动**的项；已完成的老条目不再保留（历史见 git log 与 `AI_DECISIONS.md`）。
> **整理时的复核基线**：`HEAD = e7f1a87`（J8 收尾闭合后）。标注「**复核**」的条目由本轮逐条 grep/打开源码二次确认。

**来源报告**（本总账的输入）：
`R4_BARITONE_ALIGNMENT_AUDIT.md`、`ALIGNMENT_OPEN_QUESTIONS.md`、`reference/BARITONE_PORTING_CHECKLIST.md`、
`RISK_SYSTEM_ISSUE_LIST.md`、`RISK_SYSTEM_DESIGN_DRAFT.md`、`RISK_SYSTEM_REVIEW_20260910.md`、
`RISK_MODES_DISCUSSION.md`、`JOB_LAYER_DESIGN.md`、`WORLD_WRITE_AUTHORIZATION.md`、
`MULTI_BOT_INTERFACE_RESERVATION.md`、`MINE_MIGRATION_DESIGN.md`。

---

### 复活线（**已登记，暂不实现** —— D-284，2026-09-17 用户裁定）

| 步 | 内容 | 状态 |
|---|---|---|
| 1 | 死亡**不删数据**（detach/remove 分离 + 倒下态落盘 + 重启读回） | **✅ 完成**（D-276；判据 `death_persistence` 9 checks + 门禁 D1-P1 + `tools/death-persistence-e2e.sh` 两轮 e2e 可红）|
| 2 | **复活流程**：亡骸方块 + 魂匣（可携带）+ 神龛方块 + 成本 | **已登记，未开工**（形态见 D-284 / 草案 §4.5）|
| 3 | **保险道具**：一次性保命 + 召回符（须**比复活便宜**、**不得成为依赖**） | 已登记 |
| 4 | **死亡惩罚档** c1/c2/c3 | 留档（草案 §4.7 原裁定：以后讨论）|

> 触发条件：用户说"复活线开工"。在此之前不占当前任务层。

### 两条待细化/待排期（2026-09-17 用户口述，均为**方向**、非最终解释）

| # | 内容 | 现状 |
|---|---|---|
| **R-1** | **分层重试**（替代任务保险）：低级任务由任务/决策层固定次数重试；高级/长线任务**需玩家批准**才重试 | 方向已登记（D-286）；**分层细则待后续详细拟定**（哪些算低级/高级、次数、批准链路）⇒ 不实现 ✗ |
| **R-2** | **电池模块化整理**（用户原话："让电池本身脱离任务管理的束缚，每个电池步按分类模块化，一个模块保证可以单独测"） | 待排期；已有方案骨架（编排器脱会话任务 + 模块化 + 模块单独可测验收）|

> R-2 的**动因（实测）**：① `/alice follow on` 会**顶掉电池自身**（电池占着 bot 的任务槽 ✗）⇒ 电池无判决行、`no_verdict`；② `single:craft_table` **单跑必红**（依赖前序步把区块热起来 ✗）⇒ "单步可测"目前不成立。

### 决策清单进度（2026-09-17 会话，用户逐条裁定）

| # | 决策点 | 裁定 | 状态 |
|---|---|---|---|
| 1 | `PlanningDependency`（S-10） | **删** | ✅ 已落地（D-282；门禁 S10-P1 可红；CORE 46/46）|
| 2 | 电池步骤间世界状态契约 | **B**（留了要么收尾要么声明 KEEP） | ✅ 已落地（D-283；反向对照当场红；CORE 46/46）|
| 3 | 复活线第 2 步形态 | **先登记、不进当前任务层；形态按草案倾向** | ✅ 已登记（D-284）|
| 4 | F1-残 / F3-残 | **F1 现在补 + F3 结案** | ✅ 已落地（D-285；门禁 F1-P1 + 夹具可红；顺带修 tick 重入 NPE）|
| 5 | 任务保险 | **不做**；改"分层重试"方向 | ✅ 已登记（D-286；**细则待细化，非最终解释**；台账 R-1）|
| 6 | 临时权限凭证 | **完全不做** | ✅ 已登记（D-287；并确立"保护性外挂 = 游戏机制降死亡概率"的性质界定）|
| 7 | 视觉三问 + 单眼 vs N 眼 | **共享 / 按需显式触发 / 无任务限额改尝试冷却（先单档）/ 结论+图都留** | ✅ 已登记（D-288；红线"视觉不进执行路径"**待用户一句确认**）|
| 8 | `RiskProfile` 字段 + 行走预算 + `PROGRESS` 默认 | **首批两字段（待消费者）/ 行走预算不动 / PROGRESS 默认 200** | ✅ 已登记（D-289）；`PROGRESS` 默认已落地 + 门禁断言 ✓；`containerAccess` 消费面=`MenuSession`（**等测试数据**再定）；`hazardTolerance` **无处可接**（规划器无危险代价 ✗）|
| 9 | `J-5` `UNTIL_FULL` | **删** | ✅ 已落地（D-290；门禁 J5-P1 注入可红；顺带发现同类死字段 `GoalSpec.stopWhenFull` 待你一句话）|

> **D-292 已闭环（2026-09-17）**：`hazardAversion` 字段 + 消费者（`MovementContext.cost`）+ **plan-only 判据**（电池步 `hazard_aversion_plan`）全部落地 ✓：厌恶开 ⇒ **贴危险格数 3 → 0**、代价 20.00 → 26.66（改走绕行）✓；反向对照（去掉加价）**红** ✓。⚠️ 原遗留说明（判据待补）已作废 ✗
> ⇒ 需补 plan-only 夹具：场景 = 一条「贴着熔岩的短路 + 安全绕行长路」，开/关两次规划断言 `cost`/`nodes` 不同（反向对照：去掉加价 ⇒ 两次相同 ⇒ 红）。可用面：`CorePathPlanner` + `SearchBudget`（各诊断任务已这样用 ✓）。
> **R-2 进度（2026-09-17）**：**Phase 1a 完成**（`task/check/` 框架 + `LedgerModule` 接入，CORE 判"行为等价"✓）；**Phase 1b 首片完成**（`CheckHarness` 编排器脱离会话任务 ✓ · `module:<id>` 单跑 ✓ · `module-selftest.sh` 验收 ✓ · `harness_self` 自检模块证明"命令顶不掉编排器" ✓ —— D-293/294/295）。
> **R-2 余下**：① v1「模块自带场景/发料/前提」⇒ 才能搬 `pathing`/`mining`/`lumber`/`craft`/`machine`/`transfer`/`survival`/`decision`/`death`/`tools`；② 搬完一类即在 `module-selftest.sh` 里多一个"可单独跑通"的模块 ✓。
> **R-2 已搬模块（2026-09-18）**：`ledger`(4) → `harness_self`(3) → `pathing`(3，D-296) → `decision`(5，D-297) → `craft`(12，D-298) → `machine`(4，D-299) → `mining`(7，D-301) → `lumber`(3，D-303) → **`transfer`(1，D-308)** → **`survival`(1，D-308)** → **`death`(2，D-309)** → **`tools`(1，D-310)**；模块注册表现 **18** 个（电池内 **56/58 步**由模块提供）；**全量 `module-selftest` = 18/18 PASS**（干净退出码 ✓，2026-09-18 里程碑）；CORE **48/48**（每片都与上一轮做步序 diff = **0 处差异** ✓）。
> **⇒ R-2 主体完成**：电池已成**纯组合表**（17 个模块 + 2 个内联步）；「每个电池步按分类模块化、一个模块保证可以单独测」两条都已成立 ✓。
> **新增门禁（D-304 收口）**：`R2-P2` **步清单完整性**（CURATION 的每一步必须**恰好**有一个提供者 —— 电池内联或某个模块；电池未组合的模块如 `harness_self` 正当豁免）· `R2-P3` **步边界对齐**（电池 `endStep` 里的跨步还原，编排器 `endStepHygiene` 必须都有）—— 两条都用注入验证过可红 ✓。
> **工具自守卫（D-304）**：`module-selftest.sh` 自完整性守卫（运行期间被改 ⇒ exit 4，失败关闭）· `headless-battery.sh` 每轮自动归档服务端 stdout 到 `run/headless-logs/`（两次丢证据的教训 ⇒ 结构化 ✓）。
> **R-2 下一步**：十个**分类片**全部搬完 ✓（`transfer`/`survival`/`death`/`tools` 收尾）。**电池内只剩 15 个内联步**：
> `hazard_aversion_plan`(MAIN) · `speech_channel`(MAIN) · `decision_contract`(MAIN) · `decision_trace`(EXTRA) ·
> `partial_search`(BASELINE) · `capability_gate`(BASELINE) · `llm_contract`(MAIN) · `permission_gate`(EXTRA) ·
> `pickup_gate`(EXTRA) · `collect_job`(EXTRA) · `recipes_dump`(EXTRA) · `event_thresholds`(EXTRA) ·
> `recoverability`(BASELINE) · `write_policy`(BASELINE) · `pathing`(BASELINE，聚合 5000 tick)。
> ⚠️ **它们的 CORE 位置不连续**（实测：13 / 22-23 / 41-42 / 44 / 45-46 / 47）⇒ 按 D-309 的口径，
> **分组必须选「保住 CORE 次序」的连续段**，否则就是未证明的顺序变更 ⇒ **分组口径待用户拍板**（见台账随附方案）。
> **验收节奏（D-304，用户 2026-09-17 选 A+B）**：每模块只跑 `module:<id>`；**CORE + selftest 攒到 2–3 个模块集中跑**；`selftest` 默认只跑本轮碰过的模块（`tools/module-selftest.sh --changed`，`--list` 可静态自查、秒回）；**框架文件一改就自动回全量**；里程碑（提交前）跑一次全量 ✓。
> **⭐ 迁移纪律（D-302，四次真实红换来的）**：抄步定义只保证**输入**等价；**判据在哪里被求值**同样是行为。编排器与电池曾在四处不同：① 步边界卫生（D-298）② `skipWhen`/三态判决（D-300）③ 步作用域相对 `provision` 的时序（D-301①）④ `doneWhen` 的求值位置（D-301②）—— **四处全都只在 `module:<id>` 单跑里暴露、CORE 里看不见**（CORE 跑电池，天然带正确语义）⇒ **每搬一个模块必须单跑一次** ✓。
> **`machine` 片的两条产出（D-299/300）**：① 入口里那条已登记的坑已修 —— 「`single:machine_station` 必红」的根因是**三个夹具在 `teleportTo` 那一 tick 读 `onGround`**（陈旧值 ⇒ 一个读法两种相反假判决：`machine_station` 假绿、`machine_cycle`/`craft_machine` 假红）⇒ `FixturePremise.settledOnGround` + 门禁 **R4**（现策展表 2026-09-17 行的「这类步不要用 `single:`」**已作废**：它现在能单独跑 ✓）；② 编排器补 `doneWhen`/`skipWhen`/三态判决（**DEGRADED**）——这是 `lumber`（常驻 Job）与任何缺模组场景的前置件 ✓。
> **⭐ R-2 的口径（D-297/298 两次确认）**：验收单位是**模块**（模块内允许步间依赖 ✓）；**编排器的步边界必须与电池 `endStep` 同口径** —— D-298 实测：缺一句站点还原就让 `craft_goal` 单跑假红，而**电池那边有、编排器没有** ⇒ 门禁 `R2-P1` 钉住"两侧都要有" ✓。
> **R-2（电池模块化）**：用户 2026-09-17 定为"下一条主线"，**开工后按用户要求暂停** ✗（"等会，决策还没做完"）⇒ 已放下的样板：`task/check/{CheckStep,CheckProfile,CheckContext,CheckModule}.java` + `modules/LedgerModule.java`（未接入电池）⇒ 决策走完后继续 ✓。

### 2026-09-18 拉取登记：`survey/19` + `survey/20`（保护区 / 紧急提权线）

| 项 | 内容 | 状态 |
|---|---|---|
| **来源** | `survey/19-勘测侧复核回写-第二次-20260917.md`、`survey/20-保护区与紧急提权-20260917.md`（同批 `22562ec`；勘测侧基线 `cddc90c`） | 已入库 ✓ |
| **待拍板 7 项** | 指针 = **`survey/20 §8`**。**✅ 2026-09-18 已逐条走完 7 条**（含 1 条重开）⇒ 裁定全文见 **`AI_DECISIONS.md → D-305`** 表格（①保护区的载体与形状 = `SafeZoneData` + **按区块划分**·②无主地三处语义·③区域=上限/任务=需求 = **两个区域+换档**·④B-3 挖 · ⑤B-4 `NOTIFY` · ⑥B-5 延后队列 = 账本查询+三档触发 · ⑦D-1 = **不做** + 登记将来形态）| **✅ 已裁完**（**只登记，未实现**） |
| **⚠️ 主线对 §0 分层表的一处异议** | `survey/20 §0` 把 A 层（`RegionCap` 查询 / 越界降级挂点 / 区外 `PermissionGate` 通道）列为"可立刻做"。**但三条的前提都是"保护区/安全区"的定义，而那正是 §D-2（报告自评"风险极大"）待拍板的内容** ⇒ 无定义时 `RegionCap` 是**无调用者的死代码**（不失败、不拦不可逆动作、不做指针 ⇒ 过不了准入尺子）⇒ **正确顺序是 D-2 定义 → 才谈 A 层** | 异议已提（见主线回复） |
| **⚠️ `survey/20 §B-1` 三处自相矛盾** | 标题「**不取交集**」／公式「实际权限 = 需求 **∩** 上限」／理由行（"取交集 ⇒ 一出区权限归零 ⇒ 被迫待在区内"）**三者不一致** ⇒ 照做会做错，**需勘测员或作者澄清** | 待澄清 |
| **勘测侧两处待回写**（主线只登记，不改 `survey/`） | ① `survey/20 §2.1` 把逃生常量写成 `PathRequest.java:34-35`，**实为 `:38-39`**（`survey/18`/`19` 是对的）—— ⚠️ 这类"行号在范围内但内容错位"**R-P1 结构上抓不到**（门禁只抓越界）；② `survey/03-ATM9-勘测报告.md` 的 InterfaceScanner.java:167 **越界**（该文件仅 **162** 行）—— R-P1 **刻意不扫 `survey/`**（只增不改 ⇒ 红它无法修复）⇒ 由勘测员回写 | 登记 |
| **门禁 R-P1 补一处实测盲区**（2026-09-18） | 旧正则要求引用带扩展名 ⇒ **`Class.method:行` 这种写法结构上看不见**。实测 `docs` 范围内该类引用 **310 处**（补前），其中 **1 处必然过期**（本仓自己的 GoalAction.parse:351，而 `GoalAction.java` 只有 313 行）。补齐后：校验量 **299 → 564 处**（其中类限定 262 处）/ 越界 **0**；注入两类各一次 ⇒ 均红 ✓ | ✅ 已落地 |
| **工具 `module-selftest.sh --changed` 的静默假阴性**（2026-09-18，`D-314`） | 改动落在**夹具本体**（`task/ProtectionZoneCheckTask.java`）时，原有两条映射规则（模块文件 / 注册表新增）都不命中 ⇒ 打印「没有检测到受影响的模块 ⇒ **无事可做 ✓**」并**跑 0 个**，与该文件头「绝不静默跑 0 个 ✗」直接矛盾。**修法**：非模块 Java 源按「哪个模块引用了这个类」归属；判不出来 ⇒ **跑全部**（失败安全）；纯文档/工具改动仍是「无事可做 ✓」 | ✅ 已落地（+ 三条对照：夹具类 ⇒ 选中/跑全部、生产类 ⇒ 跑全部、纯文档 ⇒ 无事可做） |
| **⭐ 新增 `D-306` / `D-307`** | `D-306` = **`survey/20 §2.3` 的实质性遗漏**（保护区/黑名单/区域两层**早已存在**：`protection/SafeZoneData` + `BlockBreakSafety` 的 `hasBlockEntity` 不可清障 + `WritePolicyMatrix.Zone` 两层 + `CapabilityGate.ZONE_*`）；`D-307` = **①-入口**：地图式勾选（**实际 clone 并读 FTB Chunks 1.20.1 源码**：地形**客户端**自绘 · 服务端只发认领元数据 · 勾选 = 一个 C2S 批量包）⇒ **复用原版地图渲染器 = 否决**（连它自己都不用） | ✅ 已登记 |
| **⭐ 新工作项（独立于 R-2）** | **保护区区块化 + 地图式勾选界面**（客户端 UI 子系统，带协议）⇒ 结构见 `D-305` ①′ + `D-307`；⚠️ 需要一个**客户端轮次**验证 | **1/2 ✓（`D-313`）· 2/2 已实现 ✓（`D-314`）· 3/3 地图细化已实现 ✓（`D-315`）**：1/2 = `SafeZoneData` 改 ⭐ **区块级 2D 认领**（忽略 Y、全高度）+ **旧格式自动迁移**（相交即认领、计数可查、不静默丢）；2/2 = **零参数物品 `alice:protection_selector`** + 17×17 区块网格（左键认领 / 右键取消 / 拖动连选 / 关闭即批量提交）+ **3 包协议**（S2C 快照 / C2S 批量动作 / C2S 空请求自愈；动作包**无维度字段** ⇒ 维度由服务端裁定）；3/3 = **地图细化**（每格 3×3 子格各采自己的列 + **高度明暗** + 渐进入场不占帧 + 边缘区块刻度 + 悬停显示地表方块/高度 + 图例；`G` 键关明暗）。夹具判据 **32 → 57 → 63 → 66 条**（协议契约 / 界面几何 / 子格平铺 / 中心向外采样序；**零世界写入**；`D-316` 的外部认领源那 10 条随回撤删除）、反向对照见 `D-313`/`D-314`/`D-315`/`D-317` 各条（含 `D-317` 崩溃回归门禁）、`module:protection` **1/1 PASS**、CORE **49/49**（步序 diff = 0 ×3）。⚠️ **界面观感仍未验证**：`WINDOWS_CLIENT` / `USER_ACCEPTED` 待取，入口与判读见 `docs/TESTING_GUIDE.md` §2.5。**FTB Chunks 只读兼容（`D-316`）已整体回撤**（`D-318`：适配器反射入口猜错 `FTBChunksAPI.isManagerLoaded()` ⇒ 从未生效；bot 经字节码核实**不是假人**（architectury `isFake` = `instanceof FakePlayer`）⇒ 按用户条件**不保留**假人白名单；⭐ **实测 bot 已自动拿到自己的 FTB 队伍**：`world/ftbteams/player/<uuid>.snbt` = `player_name: "Alice"`、rank=owner ⇒ 有自己一套身份，**不是借用玩家身份**）|
| **⭐⭐ 新需求（2026-09-18 用户追加）：bot 的「创建者」登记 + 继承创建者的身份与权限** | 起因：FTB 联动的正确做法**不是**「给 bot 建自己的队」（用户：那只能当**备用**），而是**继承 bot 创建者的身份与权限**；而今天 `/alice spawn` **只收名字**（`BotCommand.java:1606` → `BotManager.spawn(level, pos, name)`，`:130`/`:135`）⇒ **创建者根本没被登记**（全仓 `bot/` 无 creator/owner 字段）。**已核实的 FTB 机制**（`2001.3.8` 源码）：非假人玩家在别人领地能否编辑 = `ChunkTeamDataImpl.canPlayerUse:291-305` —— `PUBLIC`⇒true · `ALLIES`⇒`isAlly(uuid)` · 否则 `getRankForPlayer(uuid).isMemberOrBetter()`；而 `isAlly:280-288` = `ALLY_MODE=FORCED_ALL` 或 `getRankForPlayer(id).isMemberOrBetter()` 或 **`== TeamRank.ALLY`** ⇒ 两条路线：**① 结盟**（在创建者队里给 bot `ALLY` 级：默认 `block_edit_mode: allies` 下**正好够用**；FTB 自带 `addAlly`/`removeAlly` 与 GUI「Ally」按钮）· **② 入队**（party join ⇒ `MEMBER` 级，连 `PRIVATE` 的设置也过，最贴近「继承」；公开 API 无 addMember ⇒ 走命令/GUI）。⚠️ 我们的 bot **必然是「非假人」**（`PlayerHooks.isFake` = `instanceof Forge FakePlayer`）⇒ 走的就是上面这条**普通玩家**逻辑。 | **已实现 ✓ + 客户端已验证 ✓（`D-319`，登记与显示这一半）**（`WINDOWS_CLIENT`：`/alice spawn` 回显 `创建者=dddgn (902b9056)`、`/alice bots` 列出两只；用户判「符合预期」）：`bot/BotOwnership`（唯一出入口）+ `BotPlayer` 归属字段 + `spawn(...,creator)` 过载 + `saveToWorld`/`restoreFromWorld` 往返 + `/alice bots` / `/alice adopt`（**单向**）+ 新模块 `ownership`/新步 `bot_ownership`（判据 **20** 条、CORE **50/50**、步序 diff `49a50`、三个注入各自红）。**待做**：~~FTB **入队 party** 继承（用 FTB 自己的 `party create/invite/join` 驱动 ⇒ **动作点待你拍板**）~~ ⇒ **已实现 ✓（`D-321`）**：**写入只由显式命令触发**（用户 2026-09-18 选项 A）——`/alice ftb status`（只读）/ `bind`（建队→邀请→入队）/ `unbind`（假人先退、你再退）；写入路径 = **代打 FTB 自己的命令**（不反射其写入 API），只读走签名逐条核过的反射桥（缺类/签名不符 ⇒ 明确回原因，不猜不降级）。离线门 `bot_ownership` 追加 FTB 组（checks 20 → **36**，含"同队 + 身份 ≥ MEMBER"与 party **计数**幂等）。⚠️ 建队的副作用已核实：创建者已有认领区会被 FTB **转入该 party**（退队还回；上限默认 `LARGEST` 不变）⇒ 这也是它**不自动**做的原因。**仍待拍板**：创建者是否同时是 Alice 侧**唯一指挥官** |
| **⭐⭐ D-320：两假人挨着 ⇒ 服务端爆栈崩（已修 + 已加离线门）** | 客户端实测：`demo`/`tango` **相隔 1 格** ⇒ `StackOverflowError: Sending packet`（`latest.log` 84,892 帧；症状 = `/alice bots` 无反应 + 退出存档卡住）。根因 = `FakeConnection.send` 把「发给本假人的旋转包」再广播给**追踪者**，而追踪者**含别的假人** ⇒ A⇄B 互相转发；`5d63cdf`（2026-09-08）把 `broadcastToRealPlayers` 换成 `broadcastToTracking` 引入的**回归**。修 = 转发中闸（投递集合不变）。⭐ **离线门先红后绿**：旧码 `verdict=<无> exit=3`（服务端死）→ 修后 `PASS`（`checks=9`）| ✅ **已修 + 客户端复验通过（`D-320` 附注一 ⇒ `WINDOWS_CLIENT`）**：2026-09-18 20:49 那轮（jar=18:52:17，类内确有 `relayRotation`/`RELAYING`）两假人**相隔 ≈2 格**共处 87 秒、其间 `demo` 在它们中间连挖 10+ 格 ⇒ `StackOverflowError` **0 处**、`/alice bots` **有反应**、退档 **2.1 秒**；同配置的旧 jar 会话（`logs/2026-09-18-1.log.gz`，18:25:35–18:28:15）**133 处**爆栈、命令零输出。⚠️ 仍未验：真人**肉眼**看 bot 转头/移动是否与之前一致（客户端日志读不到 `relayCount`/`suppressedCount`） |
| **⭐⭐ D-321：假人继承创建者的 FTB 身份（只读 + 显式命令）** | 事实（1.20.1 源码 + 装好的 jar）：`canPlayerUse:291-305` 决定"能不能在别人领地里动手"（默认 `def_block_edit=ALLIES`）· 「加盟友/入队」两条路**都挂在 `/ftbteams party` 下** ⇒ 创建者没 party 时假人**无任何合法途径**；`party join` 要求 rank ≥ `INVITED` ⇒ 顺序 create→invite→join；建队会把创建者**已有认领区转入该 party**（退队还回；上限 `LARGEST` 不变）| ✅ **已实现 ✓ + 离线门绿（`D-321`）**：`/alice ftb status`（只读）/ `bind` / `unbind`；写入 = **代打 FTB 自己的命令**（`FtbCommandRunner`：不反射写入 API），只读 = `FtbTeamsBridge`（签名逐条核过；不符就明说原因，不猜）；离线门在 `bot_ownership` 内（`checks=36 failures=0`，含"同队 + rank ≥ MEMBER"与 party 计数幂等）；CORE **50/50**、`check-all` **17 PASS**。⚠️ **待 `WINDOWS_CLIENT`**：你敲 `bind` 后 FTB GUI/地图上看到什么、假人能否真的在领地内动手 |
| **⭐ D-322：`BotManager` 迭代活 map ⇒ 步内 spawn/remove 假人崩** | `onServerTick` 直接 `for (… : BOTS.values())`，而自检步就在这个循环里跑 ⇒ 步内 `BOTS.put/remove` 触发 `ConcurrentModificationException` ⇒ 服务端崩 + 看门狗（`verdict=<无> exit=3`）。**是否炸看哈希顺序**：双假人转发自检侥幸没炸、FTB 自检炸了 | ✅ **已修**：遍历快照 + 跳过已 detach 会话（`module:ownership` 修前崩、修后 PASS；`single:bot_pair_no_recurse` PASS；CORE 50/50）| 
| **⭐ 已修（`D-325`）：CORE 偶发假红 `survival_exit` 掉血两条** | 现象：两条 `D-312` 判据同时红（`命中 0 条，state health=19.0 prev=19.0` + `冷却窗口内恰好 1 条`）而前提自证是绿的（`净掉 2.0`），≈1/4 CORE 轮。**根因（存档日志 `run/headless-logs/20260918-214512-core.log` 实证，无需插桩）**：监视器每 tick 至多观察一次，而夹具在**同一 tick 内**先抬血（`normalizeVitals`→20）再打血（`hurt(2)`→18）⇒ 那一拍的观察看到的是**改血前**的 19；`resetHealthTracking` 只拨了**阈值层**基准、没拨监视器的边缘基准 ⇒ 下一拍回血 +1 让血量也到 19 ⇒ `19<19` 为假 ⇒ **边缘被自家抬血吃掉**（注入后完全没有 `[Threshold] 掉血` 行 ⇒ 「环 CAPACITY=32 挤掉」假设被证伪）。⇒ **`D-312` 的"净掉≥2 就不再偶发"作废**（决定成败的是**比较基准**，不是缺口大小）。**修**：新增 `SurvivalSystem.resetHealthBaseline/healthBaseline` 测试接缝 + `EventThresholds.resetHealthTracking` 顺带拨监视器基准；夹具把前提写成判据（`边缘基准必须 = 抬血后满血`）。**先红后绿**：修前新判据读出 `实际基准=19.0`（要 20.0）→ 修后 `PASS（checks=126 failures=0）`。⚠️ 不拿"多跑几轮 CORE"当证据（1/4 偶发率下 3 轮全绿的置信度只有 ~58%），证据是那条**确定性**前提判据 |
| **⭐⭐ 已修（`D-323`）：破坏被拒**不再谎报成功**（真机发现 + 先红后绿的离线门）** | 用户 2026-09-18 22:16 实测（FTB 队伍联动首次真机验收）：**在队里能挖领地内方块、退队就不行** —— 观感正确，而**我们全绿**：退队后 4 次破坏（`31,63,217`/`30,63,218`/`30,63,219`/`28,63,220`，都在 dddgn 认领区块 (1,13) 内）打了 `block_break_done` + `COMPLETED` + `breaks=1/64`，但**存档里那 4 格仍是 `minecraft:dirt`**（在队那两格 `31,63,220`/`30,63,220` 读出来是 `air` ✓）⇒ 被拦的破坏被记成了成功（账实不符）。根因 = `action/BlockBreakSession.java:106` 丢弃 `destroyBlock` 返回值且**不验证世界事实**就 DONE；`MineBlockRunner.java:239-243` 只信这个 DONE | ✅ **已修（`D-323`）**：破坏前后比**方块对象身份**，没变 ⇒ `fail("REFUSED")`（上游 `BREAK_REFUSED`）+ 既有 `[WRITE-REFUSED] … reason=world_unchanged` 口径；批量破坏同判据。**新步 `break_refused`（MAIN，进 CORE）先红后绿**：修前 `verdict=FAIL`（②冒险 ③FTB 都 `DONE` 而方块还在）→ 修后 `PASS（checks=14 failures=0，冒险=REFUSED FTB=REFUSED）`、CORE **51/51 ticks=4800**。✅ **客户端已验（用户 2026-09-18 22:39）**：`reason=world_unchanged` 与 `BREAK_REFUSED` 终态都出现了；用户同时指出**同一目标白重试两次** ⇒ 见 `D-323` 附注一：`BREAK_REFUSED` 已加进 `MineTask.isHardTargetRefusal`（命中即升级失败、不花 `MAX_RECOVERY_ATTEMPTS=2` 次重规划），门禁加两条策略断言（含反向对照）⇒ `checks=16`、CORE **51/51**、check-all **17 PASS**。⚠️ 复测要点：同一目标现在只出现**一次** `[WRITE-REFUSED]`、终态证据 `recoveryAttempts=0`。✅ **已收口（`D-326`，用户裁定 A）**：`placeBulkEdit` / `breakForBulkEdit` / `RegionLumberJob` 补种三条批量写路径新增**第三方保护只读预检**（`compat/ftbchunks/FtbChunksBridge` + `protection/ThirdPartyProtection`）：问的就是 FTB 自己那条裁决（`ClaimedChunkManager.shouldPreventInteraction` + `Protection.EDIT_BLOCK`，与它破坏/放置钩子逐字一致），被拒 ⇒ `[WRITE-REFUSED] … reason=ftb_claim_denied`；桥不可用/异常 ⇒ **不拦但 warn**（fail-open，自方闸门仍权威）。门禁 `break_refused` 16→**21** 条判据（含"撤认领后必须真能写"的反向对照）：修前两条红（`返回=true 方块真的改了`）→ 修后 `PASS（checks=21 failures=0）`；CORE **51/51**、check-all **17 PASS**。**边界**：只认 FTB Chunks（别的模组若只挂 Forge 事件仍看不见这三条路，覆盖它要走"改原语"那条更大的路，已被本次裁定排除）；单方块路径不需再问（FTB 自己会拦）。|
| **⭐⭐ D-327：红线的场所化（保护区 = 记账/预算的唯一消费者；野外 = 成本模型 + 维生 + 只读审计）** | 用户 2026-09-18 提出的**方向性修正**：此前红线按"全局"写，真场所是保护区。修正后：野外撤掉**世界修改账本/预算/恢复义务**，治理者 = **寻路成本模型 + 维生 + 只读审计**（审计保留，粗细度可降）。★ 我的复算支持这条：内核**已经**把改世界动作计价且无免费洞（`CostModel`：PLACE≈3.33 走路格 / BREAK_PENALTY=2.0 / 破坏按实测 tick 累加 / DOWNWARD·PILLAR·FALL 分档 / 水×7.25 / 未计价 ⇒ +∞）；`WorldModLedger` 原文自证"挖无法配对恢复 ⇒ 其控制手段是谓词+预算+归因"。⚠️ 我加的三条保留：① 回程/逃生是**路径级**性质，逐边成本替代不了 ⇒ 至少留"就地固守"兜底；② 野外保留只读审计（用户同意）；③ 保护区外的**他人财产** ⇒ `SafeZoneData` + `D-326` 第三方预检必须保留。**逃生语义拆分（用户明确）**：机制 A 局部紧急逃离 = **保留 8 格 + 收紧搜索预算**（理由是时间成本，合理）；机制 B「任务失败后返回安全区」= **不设预算**，是 A 的后续动作；**不要再叫"全局逃生"**。**待办**：先做回程兜底再放开 B 的预算 → 预算/账本适用范围收窄 + **重写 6 个 CORE 步**（代价在门禁口径）→ 通道能力从"新授权制度"降级为"一个信封 + 回程兜底" | ✅ **执行项 ① 已落地（2026-09-19，`D-327` 附注一）**：机制 B = `SafeReturnTask`（一跳一跳逼近 + 终点精确落脚） + `BotManager.complete` 接线（判决 `shouldStart`：**区外 + 有认领区**；无认领区 ⇒ 一字不变） + 门禁 `safe_return`（三用例 15 项：无区诚实码 / 200 格 2 段 732 tick 走回区内 / 封死格 `return_unreachable` 且原地不动）。**用户本轮重新声明**：就地固守**不是兜底**（是「避免死亡的最保守行为」，现在不做）⇒ 返程失败就**站定不动**（临时兜底）。**剩 ②**：预算/账本收窄到保护区 + 重写 6 个 CORE 步；**③** 野外审计粗细度 ⇒ ⚠️ **② 的口径已按 `D-338` 细化**（2026-09-19：保护区 = 父类 + 子类安全区/任务区；账本新增 break 条目；恢复解耦边界；落地清单 = `§5.12`）|
| **⭐⭐ D-329：挖矿任务设计路线图 + Q1 定案 + Q2/Q3 挂账** | ① **已定口径**：扫描边界 = **不加载**（只扫已加载 + 记忆累积，符合 D-132）；价值模型 = **用户给优先级与风险容忍度**。② **路线图**（用户"可采纳"）：设计文档 → 扫描契约/分片扫描器 → **1.5 作业区+推进式扫描** → 成本契约+`CostOptimalPolicy` → **2.5 通道能力** → 2.6 分支巷 → **3.0 M2 长作业复评+M4 归因结构化** → 3.1 `MineJob` 大范围模式 → 4 场景+客户端轮次；≈16~19 项 / 5~7 会话；还需 1 轮客户端。③ **Q1 定案**：通道**不能**靠清障预算不限制实现（清障语义=腾站位、深度上限≈6 是结构性的、P-02 不含 `WITH_WORLD_MODIFICATION`；且**挖矿今天清障预算 = 0**、"不限制"与现状相反）⇒ 改走 D-327 口径（成本模型 + 一个信封 + 回程兜底）。④ **挂账（用户"先留着"）**：**Q2** 只扫已加载会不会更趋于向下挖（结论：偏置来自**没有作业区意图**，不是加载策略）· **Q3** 要不要"向水平探索"的任务（结论：要，意图层 `GoalSpec` + 执行层推进式扫描/分支巷，骨架抄 `RegionLumberJob`，与 2.5 配对）。⑤ `survey/08` 三件承重事实：三种承诺（清障 TEMP 要还原 / 通道 KEEP 且必须能回来 / 修路 KEEP 是产物）· 可回收性天生是给通道准备的 · §7 停止条件「一次长作业自主闭环」+ **M2 是"自主"的物理载体**、**M4 先于 M3** |
| **⭐⭐ D-328 附注：远距离实测第二轮 —— 墙是"硬拒"不是"慢"** | 四遍测量全部有效：① 走廊/平地/不加载（20~160 格）全部 `REACHED`、**1~16 ms**、节点数 = 距离+1（线性）；② **320/640 格 ⇒ `GOAL_NOT_LOADED`（0 节点 0 ms，立刻拒）**，本环境加载边界 ≈**192 格**（view-distance 决定）；③ **执行器**：走廊内走 320 格 = **1217 tick（≈61 s，3.8 tick/格）**、`DONE`、准时到达。⇒ 结论：**已加载范围内规划是毫秒级**，"A* 远距离太慢"不成立；第一优先级仍是**粗目标+滚动重规划**（D-330 ③）；若用户说的"慢"是"走得慢"，那是**移动速度/执行器**的事，要换问题去量。⚠️ 本轮踩的两个夹具 bug 已固化防复发：**脚位写死错 1 格**（量出"20 格平地撞满 20k 节点"的假象 ⇒ 现改为从 `bot.blockPosition()` 取 + "脚下必须有支撑"前提判据 + 端点三格诊断）、**过时完整性判据**。**下一步**：⭐ **任务层失败重试节奏**（`GOAL_NOT_LOADED` 0 ms，但任务层可能反复重试 ⇒ "看起来卡"的真凶嫌疑）· 障碍场景（未做）|
| **⭐⭐⭐ D-331：`WalkToTask` 规划前同步加载目标区块（绕过 `GOAL_NOT_LOADED`）—— ✅ 已修+门禁+无回归** | **发现**：`path_retry_bench` 里 400 格外未加载目标竟 `REACHED`；两侧探针实测"**规划前 hasChunkAt=false → 首 tick（含一次 plan）后 =true**"。**根因（代码级）**：`WalkToTask.tick():54-63` 目标安全预检直接读 `goalFoot` 的方块 ⇒ `MovementHelper.canWalkOn:49-51` → `Level.getBlockState` ⇒ **未加载区块被同步加载**（在服务端 tick 线程、在任何预算/超时之外）。**后果**：① 与红线 `D-132`/`D-076`"内核从不加载区块"直接冲突（内核实现在 `AStarMovementSearch:64` 有守卫，但这条生产路径上轮不到）；② ⭐ **这才是"远距离慢"最合理的解释**（慢的不是搜索——D-328 实测 1~16 ms，而是**搜索之前那次同步远区块加载**）；③ 同一件事两个入口结论不同：`planTo` ⇒ `GOAL_NOT_LOADED`（0 ms），`WalkToTask` ⇒ 静默加载并真走 400 格（`DONE ticks=1531`）。**同类审计**：`PlaceTask:70` 同款（风险低）；`MineTask` 目标都在扫描半径 24 内（低）；`MovementHelper` 本身不该加守卫（会把"未加载"误报成"不可走"）。**✅ 已修（用户选 A）**：预检先 `hasChunkAt` ⇒ 未加载不读方块、以既有 `walk_goal_unloaded` 失败；门禁实测 **修前 false→true / 修后 false→false**、任务从 `DONE ticks=1531` 变成 `FAILED ticks=1 wallMs=0`；`check-all` **17 PASS/0 FAIL、CORE 51/51**（无回归 ⇒ 旧行为无生产消费者，属纯负债）。**同类第二处 ✅ 已修（2026-09-19，见 D-331 附注二）**：`MineCandidateSource` 的 `(2r+1)³` 扫描已加 `hasChunkAt` 守卫 + 不变式与"不加载区块"两条门禁（先红后绿实测：去守卫 ⇒ 读 4913 + 区块被加载；有守卫 ⇒ 读 0 + 未加载）；"只扫已加载"必须在扫描器内部用 `hasChunkAt` 强制（`PlaceTask:70` 同款低风险，待收口）。⚠️ **需用户先定语义**：远目标是"如实拒绝（上层粗目标/分段走）"还是"任务层小步接近"——按架构是前者，但会改变现有行为 | ✅ **同类收口完毕（2026-09-19，`D-337` 附注三）**：内核搜索（读脚印闸门，`boundary_blocked=117`）· 走（`walk_goal_unloaded`）· 扫描（未扫跳过）· **放置**（`place_target_unloaded`；红 `newlyLoaded=1`/`place_no_path` → 绿 `newlyLoaded=0`/`place_target_unloaded`）|
| **⭐ D-330：内核成本模型/远距离寻路的 Baritone 对照结论** | ① **"Baritone 做了耐久预算"不成立**（破坏成本 = `1/strVsBlock + blockBreakAdditionalPenalty`，耐久只出现在鞘翅；它有的是 `ToolSet.getMaterialCost` 同速偏好低材料工具 + `itemSaver`（**默认关**）运行期阈值）⇒ 主线建议**耐久不进 `CostModel`**（单位制：走路格由实测 tick 标定；耐久不是时间 ⇒ 汇率无实测基准），要效果就用运行期阈值，要进成本就单列第二维。② 远距离三件武器（两段超时 500/2000ms、`COEFFICIENTS={1.5..10}`+`MIN_DIST_PATH=5` 竞速早退、粗目标+滚动重规划）⇒ 落地顺序：**粗目标（唯一必须）** → 两段超时 → 加权 A*（带 `suboptimal` 标记与质量门禁，**不照抄 yolo**）→ 节点降本（缓存**暂缓**：D-328 实测平地 ≈25 µs/节点）|
| **⭐ D-332：自测档位节奏（用户裁定）** | 小改动跑 `single:`/`module:`；**CORE/全量只在收口跑**；文档-only 只跑文档类门禁。代价实测：CORE ≈250 s / `check-all` ≈5 min。复核触发：若"单步全绿但收口 CORE 红"≥2 次 ⇒ 回来收紧 |
| **⭐ `survey/21`：勘测侧更正（第三次）** | `survey/20 §2.3`「项目没有保护区概念」是**实质性遗漏** ⇒ `protection/SafeZoneData` + `WritePolicyMatrix.Zone`（**保护区不是第三层**，D-207 ①）+ `BlockBreakSafety.hasBlockEntity`（模组机器）**早已存在**并接在破坏闸门 + 四个候选源上；行号更正：`PathRequest.java:38-39`（逃生预算常量）、`survivalEscape` 动作集 `:66-74`。⭐ 纪律：报告里所有「项目今天没有 X」都当**假设**（勘测员自述病因：把"我没搜到的包"当"项目没有"）|
| **⭐ `survey/22 §1`：挖矿形态三件差 + 两条已知失败链** | `MineTask`（一格）vs `MineJob`（quota 组）差三件：**目标来源**（调用方给格 vs 自己扫 `SCAN_RADIUS=24`、只找已暴露）、**作业形态**（单目标 vs 多目标 `attempted` 去重）、**判据**（挖掉这格 vs quota + 产物真进包）。⚠️ 事实：收集**已是"挖完再一起捡"**（`MineJob:309-311`），真正该拆的是**归属耦合**（`startCollect()` 的 `origin=firstMined` ⇒ 收集要横穿通道走回去）；`expectedIds` 是 **UUID 白名单不是物品过滤**（`CollectDropsTask:97,470`）⇒「不捡石头」需要**新一层"按物品/来源过滤"**；⚠️ 两条失败链：① 挖 8 捡 5 ⇒ 整个 Job `FAILED(product_not_collected)`（`MineJob:333-337`）② ~~`inventory_full ⇒ finish(DONE)` ⇒ 通道挖一半报成功~~ ⚠️ **2026-09-19 复核=非缺陷**（`D-335`）：`MineJob:235` 的容量检查**每 tick 都跑**（不受"只跑一次"约束）⇒ 本来就是作业中守卫；报告把伐木的作业中检查形状安到了挖矿头上；缺的是**矿侧门禁**，已补 `mine_inventory`（先红后绿 ✔） |
| **⭐ `survey/22 §5.2`：矿工"到达+返回"的三处空档 + 验收口径（勘测侧判断，未拍板）** | ① ✅ **已定案（2026-09-19，`D-327` 附注一）**：安全终点 = **最近认领区块内离 bot 最近的那一格**（`SafeZoneData.nearestClaimedCell`，纯查询不读方块）—— 原「"返回安全区"需要一个安全终点坐标**，而 `SafeZoneData` 是**区块集合**（全高度 2D 认领）**不提供"回哪一格"**；② 目标**扫描/选择**是判据的入口（新失败来源，**未验**）；③ **缺可测量判据** —— 到达率/返回率/平均 tick/世界改动数**没人测过**。勘测侧建议验收口径：连续 N 次挖矿 ⇒ 到达率/返回率/平均 tick/世界改动数。作者问法：「目标已确定时，几乎能保证到达并返回安全区吗？」| ⚠️ **① 的终点规则已被 `D-338` ③ 扩展**（2026-09-19）：改成**优先级链 归位点 > 安全区 > 保护区** + **内部区块**（自适应安全范围，不再"到区块边界即到"）；见 `§5.12` 第 1/2/8 行。|
| **⭐ D-336：斜向上升那一格 ✅ 已补 + 门禁** | `PLACE_STEP_AND_TRAVERSE` 的 `dy` 从 `{0,-1}` 扩到 `{+1,0,-1}`（几何复用同一 helper；定价基类 `dy>0 ⇒ ASCEND`）。**不动 `ASCEND`**（`D-334`：`changesWorld()` 是信封分档唯一口径）。先红后绿实测：**挖矿信封**（不含 PILLAR）修前**不可达** → 修后 1 条边 `REACHED`；通用信封从 `TRAVERSE+PILLAR`（2 边/成本 6.0）降到 **1 边/5.0**；纯通行信封验证**未被渗透**。门禁 `place_step_diagonal`（EXTRA，三用例）|
| **⭐⭐⭐ D-337：粗目标已实现 + ⚠️ 内幕：内核搜索会读未加载区块（红线 D-132 违反）** | `GoalSpec.exactFoot()` + `GoalNearXZ`（Baritone `GoalXZ`/`GoalNear` 的半径版）+ 守卫只对精确目标生效 —— **已实现，但生产未接线**。⚠️ 门禁沿路采样实测：粗目标不被前置拒后，搜索**把 224→384 的 14 个区块同步加载了进来**（新被加载采样点=6）⇒ ① 违反红线 D-132；② 真世界 = tick 线程上的同步区块生成/磁盘 I/O ⇒ **"远距离一规划就卡"的又一层真因**（`D-331` 同类）。此前未暴露是因为**精确目标的 `GOAL_NOT_LOADED` 守卫替真正的加载守卫背了锅**。**✅ 已修 + 门禁（2026-09-19，先红后绿）**：机制查证 = 那条跨区块边闸门是**后置**的，而读发生在它**之前**且更远（候选生成 ≤2 格 / `hazardAdjacencyPenalty` ≤2 格 / 粗目标 `isInGoal` 纯算术）⇒ 读先加载、门随后放行 = **自增强泄漏**（`D-337 附注一`）。修法 = `MovementContext.READ_FOOTPRINT_RADIUS=3` + `readFootprintLoaded()`（只 `hasChunkAt`，自身零副作用）+ 扩展前读脚印闸门 + 边界状态语义（`boundaryBlocked>0` ⇒ 有前缀给 `PARTIAL`、无前缀给 `SEARCH_LIMIT`，**绝不 `UNREACHABLE`**）。红 `failures=1`（`newlyLoaded=6`）→ 绿 `failures=0` + `newlyLoaded=0` + `PARTIAL prefixLen=198 progress=197` + `diag boundary_blocked=117 skipped_unloaded=0`。剩余：`GoalNearXZ` 仍**生产未接线**（见下一行）|
| **⭐ `D-337 附注二`：远距离 = 一跳一跳逼近 ✅ 已修 + 判据（2026-09-19）** | 洪泛事实：粗目标在边界外不可达 ⇒ 整片展开（`nodes=20000` = 上限 / 147~163 ms），对照可达 640 格 = `641 节点 / 8~14 ms`。修法：⭐ `pathing/core/search/FarTravelHop`（只读 `hasChunkAt` 采样已加载前沿 ⇒ 夹到边界内侧 + `GoalNearXZ` 半径版）+ ⭐ `task/FarWalkTask`（生产任务：反复跳 + 跳数/tick/单调性护栏；到达口径刻意粗 = XZ 半径内 ⇒ 精确落脚接 `WalkToTask`，即 Baritone `GoalNear`→`GoalBlock`）。**A/B 实测（同一夹具同一轮）**：粗目标 `20000 节点 / 142~186 ms / PARTIAL` vs **一跳 `161 节点 / 1 ms / REACHED`**；**执行侧**：300 格 = **hops=2 / 1111 tick / DONE**，随后精确落脚 30 tick ✓。两个实测踩到的判据已固化：① 整条线全加载时不许夹（否则脚边目标被误判为「边界在脚下」）② 跳数口径 = 已开始的跳数（到达可能在上一跳「在飞」时被捕获）|
| **⭐ `FarWalkTask` 仍无生产调用方（复核触发已设）** | `D-327` 机制 B 的返程走的是 **`FarTravelHop` + `PathRetryRunner` 直连**（`SafeReturnTask`），所以 `FarWalkTask` 至今**没有生产调用方** —— 它已被 `far_path_bench` 的 `FAR_WALK` 相位验过（300 格 / 2 跳 / 1111 tick / DONE + 精确落脚 30 tick）。**复核触发**：若下一个主线增量里仍没有它的调用方（预期是决策层「去某坐标」目标，或「跑腿/巡视」类任务），就**删掉**（避免「验过但没人用」的常驻代码）|
| **⭐ 已登记（未实现）：Xaero 世界地图联动** | 用户 2026-09-18 追加三项：① 保护区/认领叠加到 Xaero 世界地图；② **bot 头像**在地图上显示；③ **右键地图上的 bot ⇒ 交互/管理菜单**。可行性：① = 继承 `xaero.map.highlight.ChunkHighlighter` 并注册进 `HighlighterRegistry`（无官方注册点 ⇒ **要 mixin**；参考 `hserranome/dissonance` 1.20.1）；Xaero jar **可从 Modrinth CDN 取得**（≠ FTB）⇒ 可编译、可离线自测。② = **公开静态字段** `WorldMap.mapElementRenderHandler.add(...)`（可自绘图标，**无需 mixin**）或 `WorldMap.playerTrackerSystemManager`（复用 Xaero 玩家图标，但客户端**没有 PlayerInfo 就不画**、**不支持自定义图标**）；③ = 菜单挂**我们自己的元素**上（`ElementReader.getRightClickOptions`）**无需 mixin**，挂通用菜单则要 mixin `GuiMap.getRightClickOptions`。⭐ **无官方 API**（三 jar 全扫）；调查全文 `docs/reviews/2026-09-18-Xaero地图联动可行性调查.md`（**静态字节码，一次都没跑游戏**）| **用户裁定：三项都要做**，按难度排序 ⇒ ②头像（公开 API）→ ③右键菜单（公开）→ ①认领叠加（唯一 mixin）。⭐ **已核实 1.20.1 Forge 上「现成桥接件」实际拿不到**：`dissonance`（Modrinth 现为 fabric/neoforge 1.21+，1.20.1 那份只在旧仓库）、`FTBUXaeroCompat`（仅 1.12.2 / FTB Utilities）、`FTB Chunks x Xaero's Compat`（CurseForge，本环境 403 取不到 ⇒ **版本请你确认**）⇒ **自研直连基本是唯一可行路线**（这也正好回答「直接联动 vs 桥接件」）|

> **`survey/20` 的承重事实**（主线已逐条复算，全部成立）：`WorldModLedger` 原文自证"只记放置"（`ledger/WorldModLedger.java:20-26`）·
> `Policy` 只有 `TEMP`/`KEEP` 两值（`:41-46`）· `Entry.previous` 字段已存在（`:49`）· `survivalEscape` 今天**已含** `BREAK_AND_TRAVERSE`/`BREAK_AND_ENTER`（`pathing/core/search/PathRequest.java:69-71`，只禁 `DOWNWARD`/`FALL`）·
> `ESCAPE_MAX_NODES/_MILLIS` = `:38-39` · `isRefuge` 判据=流体/可通行/可站立，**不看怪、不看距离、不看是不是基地**（`survival/SurvivalSystem.java:422-432`）· `REFUGE_RADIUS = 8`（`:375`，`SurvivalExitTask` = `WalkToTask` 到半径 8 内落点）·
> `PermissionGate.Policy.NOTIFY` 已存在（`decision/PermissionGate.java:39`）· `RestoreScopeTask` 的 `drops_left` **只告警不判失败**（`:288-291`）· `CollectDropsTask` 第 4/5 参是 `List<UUID> expectedIds` **不是物品过滤**（`:141`、`:470`）·
> `CollectGrants.covering(server, pos)` 是无状态坐标查询（`decision/DropPolicy.java:74`）· **②"放弃任务后回家"确实无归属**（`SurvivalSystem.abandonReason` 只给判定码，`task/` 里没有任何"回基地/回安全区"任务）。

## §0 一览

> **死亡机制状态（2026-09-17，D-276）**：**第 1 步已完成** —— 死亡不再删数据
> （`detach`/`remove` 分离 + `saveFallenState` 倒下态 + `restoreDecisionFor`；判据 `death_persistence` + 门禁 D1-P1）。
> **第 2 步（复活流程 + 成本）未开工**；惩罚档位 c1/c2/c3 留档、以后讨论。
> **端到端已闭合（2026-09-17）**：`tools/death-persistence-e2e.sh` 两轮（真杀探针 bot ⇒ `alice_bot.dat` 带 `AliceFallen` ⇒ 重启读回「倒下态」），反向对照可红。
> **F3 地基状态（2026-09-17，D-275）**：请示答复入口**唯一化**（`PermissionService.answer(transport, …)`；
> `PermissionGate.answer` 降为包内可见 ⇒ 跨包直调**编译不过**；transport = command/client_packet/fixture/external）。
> **F3-残**：『看』这一侧未做 —— 非游戏内主体读不到待答复请示（无对外查询通道）⇒ 等外部驱动者线开工时补。 **✅ 结案（D-285）**：实测已有读路径（`BotStateReport`/报告物品/`[Report] json=` 日志）；只差"非游戏内实时查询通道"⇒ 属外部驱动者线形态问题|

> **F1 地基状态（2026-09-17，D-274）**：驱动者身份位**已端到端落地**（`Driver` + `TaskOutcome`/`TaskExecutionRecord`
> + `task_terminal_reason` 日志 + `DecisionSnapshot`；入口标注 = `llm`/`fixture`）。
> **F1-残**：`BotCommand` 的 16 处指派点与物品入口**尚未标注**（现报 `system`=未归因）—— **✅ 已收口（D-285）**：73 处指派点标归因 + 门禁 F1-P1 + 夹具 `driver_label`（均可红）；⚠️ 玩家命令入口的行为验证需专用无头模式（后续）|

> 机械可做，但无强判据 ⇒ 与『外部驱动者』那条线一起做（那时归因才有观察价值）。
> **F2** 已完成（门禁 G-P1）；**F4** 已完成（D-273）；**F3**（请示答复通道抽象）排队中。
> **偶发假红登记（2026-09-17）**：`survival_exit`（BASELINE，537 tick）出现 **1 次** FAIL ——
> `[Survival] SUMMARY checks=123 failures=2`，两条都是「掉血必须变成可判读的事实（DANGER 事件含 delta=…；**实际命中 0**）」。
> 同一天的前后两轮同一步均 **PASS** ⇒ 按「同问题 2+ 次才升级调查」的规矩**只登记、不追查**。
> 假设（未验证）：该轮场景没真的产生掉血事实（时序/RNG）。⚠️ 与 S-9 改动**无关**：`onBotDamage`/`DecisionSnapshot.damage` 对 DANGER 事件路径是**只读**的。
> **⇒ 第 2 次出现（2026-09-17 晚，D-298 的 CORE run1）⇒ 按本行自己的规矩升级调查**（不再"只登记"）：
> 同一工件下 `single:survival_exit` **2/2 PASS**、CORE run2 **48/48 PASS** ⇒ 确认偶发、且**与 craft 模块搬迁无关** ✓。
> **本轮新增判别性证据（推翻了上面那条假设）**：注入的那一步血量**确实掉了**
> （`[Survival] 夹具对 bot 造成 2.0 点伤害（现有血量 18.0）`），但**紧跟其后的** `[Threshold] 掉血 DANGER … hazard=NONE`
> 事件**缺失**（PASS 轮里它必然出现在同一位置，如 `health=18.0/20.0 hazard=NONE pos=64, 64, 102`）
> ⇒ 问题在**掉血事件的产生/冷却/采样**，**不是"没掉血"** ✓。相关代码：`SurvivalExitCheckTask.healthPhase()`
> （tick5 `resetHealthTracking`+`bot.hurt(2.0)`；tick11 查事件；tick31 查"恰好 1 条"）+ `EventThresholds` 的掉血阈值/冷却。
> **下一步（判别手段已定，一次 CORE 就能定案）**：在夹具 tick5 处临时打印 **`bot.hurt(...)` 的返回值** +
> 追踪器采样（baseline / health / cooldown 剩余）⇒ 分辨 ① 伤害被 `invulnerableTime` 吃掉（前一相位着火伤害的余波）
> 还是 ② 事件被 `HEALTH_LOSS_COOLDOWN_TICKS` 吞掉。**在拿到这个判别证据前不改代码** ✓。

> **⇒ 第 3 次出现（2026-09-18，D-311 的 CORE run1）—— 同工件 run1 FAIL / run2 PASS，签名逐字相同**
> （`[Survival] SUMMARY checks=123 failures=2`；两条 = 「掉血…实际命中 0 条，state health=19.0 prev=19.0」+「冷却窗口（20 tick < 40）内不刷屏：恰好 1 条（实际 0 条）」）⇒ **再次确认偶发** ✓。
> ⭐ **本次新取到的判别性对照（PASS 轮 vs FAIL 轮，同一路径同一秒级位置）**：
> | | FAIL 轮（11:21） | PASS 轮（11:26） |
> |---|---|---|
> | 前序着火相位的事件 | **两条**（:20 / :22，`hazard=ON_FIRE pos=206,64,306`）| **一条**（:39） |
> | 夹具 hurt | :23，`现有血量 18.0` | :40，`现有血量 19.0` |
> | hurt 后 PLATFORM_FOOT 的期望事件 | **缺失** ✗ | **同秒出现** ✓（`health=19.0 hazard=NONE pos=64,64,102`）|
> | 检查时血量 | `state health=19.0 prev=19.0` | 一致 |
> ⇒ **两条新事实**：① 夹具打印的是**伤害后的当前血量**，**无法判别「伤害被 i-frame 吃掉」**（伤害前后同为 18.0 时印出来一模一样）⇒ 台账已写明的判别手段（打印 `bot.hurt(...)` 的**返回值**）**仍然必需**；
> ② ⚠️ **注意 `health=19.0` 这个数**：PASS 轮里被判「满足」的那条事件是 `tickLoss=1.0 total=1.0 health=19.0 hazard=NONE` —— 它看起来是**前序着火那笔 1.0 的结账**，而不是夹具那笔 2.0 ⇒ **新假设（未验证）：夹具的窗口（hurtTick..+10）能否看到事件，取决于「环境伤害的那笔损失是否恰好落在窗口内」**，而着火相位每 2 秒结一次账（40 tick 冷却刚好到点）⇒ **窗口命中与否是时序决定的** ⇒ 这解释了"同工件偶发"。
> ⚠️ **仍然不补丁**：等判别探针（`bot.hurt` 返回值 + 追踪器采样 baseline/health/cooldown）跑出数据再定因。
> **✅ 已定根因并修复（2026-09-18，D-312）** —— 判别探针（**自标定回血拍** + 对齐/未对齐对照）一次跑出结论，
> 并**推翻了上面那段「结账窗口」假设**：
> **决定性一行**（`single:survival_exit` 12:00:56 实测）：`hurt 返回=true 血量 19.0→18.0 净掉=1.0
> absorption=0.0 armor=0 invulnerableTime 11→11 hurtTime 0→0｜事件路径 ledger lastAmount=1.0`
> ⇒ 注入的 2.0 **实际只落地 1.0**，且 i-frame/`hurtTime` **都没被刷新** ⇒ 原版 `LivingEntity.hurt` 的
> **「伤害叠加」分支**（`invulnerableTime > 10 ⇒ actuallyHurt(amount - lastHurt)`，夹具紧跟着火相位注入、
> 上一发火焰伤害的 `lastHurt=1.0` 还在）⇒ **不是**"伤害被吞"（不是 0）、**不是**冷却（`resetHealthTracking` 已清零）、
> **不是**吸收/护甲（实测 0/0）✓。
> **为什么变红**：掉血检测读的是**单 tick 边缘**（`HazardState.previousHealth > health`），而本项目回血是
> **无条件 +1 点/20 tick**（D-261/D-271 实测）⇒ **恰好 1.0 的净掉会被一次回血在采样上抹平**（边缘被消耗掉）
> ⇒ 事件永不产生。**「对齐」是必要不充分条件**：存档日志 n=9 里唯一 FAIL 的那轮是唯一 `(hurtTick+1) % 20 == 0`
> 的（100% 分离）；而探针证明**净掉 2.0 时即使对齐也照样出事件** ⇒ 两个条件都满足才红 —— 这解释了"同工件偶发"。
> **修法（用户批准「只修夹具前提」，产线语义不动）**：`healthPhase` 注入前**等 i-frame ≤10**（上限 30 tick）
> + `normalizeVitals()` 满血 + **把前提写成判据**（`hurt` 返回值 + 净掉 ≥ 2.0）。结构上不再偶发：+1 回血填不平 ≥2 缺口。
> **验证**：`single:survival_exit` / `module:survival` 均 PASS，日志出现 `血量 20.0→18.0，净掉 2.0，注入前 i-frame 10`
> 与 `total=2.0`（修前是 `净掉 1.0` / `total=1.0`），`checks=125 failures=0`（多的那一条就是前提自证）✓；
> CORE **48/48 PASS**、**FULL 也 PASS**（步级 FAIL=0）—— 新增的前提判据在**另一种前序组合**里同样成立 ✓（见 D-312）。
> **保留的边界（登记，不修）**：产线的 DANGER 掉血事件仍走血采样边缘 ⇒「真实 1 点小伤恰好被同 tick 回血抹平」
> 这一类**不会上报**；决策层不受影响（`DamageLedger` 按 `LivingDamageEvent` 精确记账，D-271）。
> **S-12（2026-09-17 新登记，队列⑤-1 的产物）**：『JEI 显示的催化剂』与『我们认的站点』之间**没有断言**。
> 事实：我方**零 JEI 引用**（`grep mezz.jei src/main/java` = 0）；站点来自自维护的 `RecipeDump.STATION_BY_TYPE`
> 与 `MachineMap` CSV（D-219 需求驱动）。⇒ 若某模组配方在 JEI 里有催化剂而我们没映射，
> 菜单可能给出『该站点做不了』的合成（玩家侧 JEI 与我们不一致）。**未修**；触发条件 = 真的遇到这种配方。
> **勘测分诊（2026-09-17，D-269 补：survey/17）**：三条设想（视觉只读标注器 / bot 视角渲染 / 带成本回收道具）
> **全部只登记**、不排期；已钉边界见 `AI_DECISIONS.md` D-269。新增待办一行：
> **S-11** —— `survey/17 §1.7c`「视觉评价要不要留存」：今天项目**没有**『记忆』型存档
> （`WorldModLedger` 只记『我改了什么』，不记『我看到/我说过什么』）⇒ 若要留存需新路径（未裁定）。
> 另：§3.3 的『死亡后复活』与 §3.4-3『一次性保命』**都受 S-9 阻塞**（危险伤害应按伤害事件观测，见 S-9 行）。
> **勘测分诊（2026-09-17，D-265）**：`survey/` 的 10/11/12/14/15/16 **六份此前从未被主线分诊**，本轮已逐条分诊：
> 现在可做 12 / 需拍板 19（归并成 6 个裁定）/ 需客户端 9 / 仅登记未来 26 / **勘测侧更正 12**。
> **唯一队列与全部证据**：`docs/reviews/2026-09-17-勘测分诊与任务队列.md`（本台账不复制该表，避免两处漂移）。


| 组 | 项数 | 影响业务？ | 备注 |
|---|---|---|---|
| §1 安全底座（风险 / 维生 / 写入准入） | 10（新增 S-9/S-10） | ✅ 4 项（S-1/S-2/S-3/S-4），常驻任务后上升 | 风险清单 9 条断言**至今全部成立**；**2026-09-16 全表复核**：S-1…S-5 已落地（S-5 的基-1 接线+判据已就位，仅两个最高等级无产出者=有意保留）；**真缺口剩 S-6/S-7/S-8**（RiskSwitches 全局静态、两守卫无开关、`policyVersion` 恒 0 且零读者）—— 前两条等 S1 冻结点裁口径，第三条优先级低；另新发现一项（`FluidRiskPolicy` 只覆盖目标格 6 邻格）见 S-4 行–S6 **0/6** |
| §2 内核搜索正确性 | 8 | ✅ 8 项（K-1…K-8 **全部收口**） | 2026-09-16 复核：K-1/K-2/K-3/K-5/K-6 **五行原状态过期**（已实现），K-4 是真缺口（规划/执行谓词差一半，已修），K-7 补登记，K-8 自写一致（D-249/D-250）|
| §3 Job 层与决策缝契约 | 10（表内实际 10 行，原写 9） | ✅ 8 项（J-1/J-2/J-4/J-6/J-7/J-9/J-10 主 claim + J-3 的 botId 部分） | 2026-09-16 复核：4 条「未实现」作废（J-1/J-2/J-7/J-9）；**真缺口剩 J-5**（`UNTIL_FULL`/`stopWhenFull`/collect `productTag` 无消费者 ⇒ 有意不做 v1）、**J-3 剩 taskKind 用类名**、**J-8 物种过滤**、**J-10 遗留 `policy_blocked`** |
| §4 世界写入授权登记缺口 | 7（表内实际 7 行，原写 8） | ⚠️ 登记债；**含一条新发现** | 2026-09-16 复核：G3/G6/G7 + G-新(A9) **已收口**；G5 原「完全不覆盖」作废（已接多处，但**菜单合成路径 `InventoryCraft:300`/`MenuSession:256` 绕过容器写入预算与策略矩阵** = 新发现真缺口）；G4 口径缩小到只剩 tick 预算；G8 行准确（4 字段未读）|
| §5 验证债 | 5 | ⚠️ 证据可信度 | `AI_TEST_MATRIX.md` 里有 **16 处「待测」**，其中若干已被后续 D-0xx 取代（需逐条核对） |
| §6 文档债 | 6 | ⚠️ 误导风险 | 三份审计报告的**包路径/常量/禁令**都已过期 |

---

## §1 安全底座（风险 / 维生 / 写入准入）

> 复核结论：`RISK_SYSTEM_REVIEW_20260910.md` 的 9 条断言**全部属实且至今未修**；
> 该复核对草案只有**一处反对意见**（§3.7「未知要计价」不适用于未加载区块，应**硬拒**）。
> 执行顺序建议：`S1` → `P1-A`（原在 S5，复核要求提前）→ `S2`/`S3` → `S4` → `S5` → `S6`。

| # | 项 | 来源 | 现状（复核证据） | 验证判据 |
|---|---|---|---|---|
| **S-1** | **维生否决后没有出口**（P1-C） | `ISSUE_LIST.md:297-348` | **✅ `WINDOWS_CLIENT`（D-132 附注二：14:47 两次复现：逃生出口 → `start_escape` → `SurvivalExitTask COMPLETED`，`segment_done ticks=5` 真的走出方块）**：`SurvivalSystem.nearestSafeRefuge`（纯查询）+ `SurvivalExitTask`（复用已验收 WalkTo）+ `SurvivalExit` 豁免（防每 tick 自杀循环）；入口 `alice:survival_exit_check` + 场景 `alice_test:survival_course` | 被岩浆/火包围 ⇒ 中断后应有一次"到最近安全点"的动作（`WalkToTask`），而不是停在原地被烧。**J8 常驻后优先级上升最多** |
| **S-2** | **执行期没有"未加载区块/世界边界"准入**（P1-A + 审计 §3.A:181） | `ISSUE_LIST.md:202-252`、`R4_AUDIT.md:181` | **已实施（D-132；A/B `WINDOWS_CLIENT`：`GOAL_NOT_LOADED`+`no_sync_load=true` / `near_goal=REACHED`；C 也已有夹具（`alice:chunk_guard_check`，2026-09-16 复核确认 `plan.status()==GOAL_NOT_LOADED` 与 `no_sync_load` 两条判据都在））**：`MovementContext.chunkLoaded/withinWorldBorder` + 新状态 `GOAL_NOT_LOADED` + 跨区块节点门控（照 Baritone `AStarPathFinder:105-112`）；入口 `alice:chunk_guard_check` | 目标落在未加载区块 ⇒ **硬拒**（复核 §2：`getBlockState` 会**同步加载/生成区块并阻塞主线程**，不是 void air）。这也是 D-004/D-076「`SEARCH_LIMIT ≠ UNREACHABLE`」判据的前提 ｜ **复核建议**：`chunk_guard` 是**唯一没有电池步**的安全底座项之一，建议挂进 BASELINE（否则只能靠真人复测维持）|
| **S-3** | `MineTask` **重复调用** `SurvivalSystem.tick`（P1-B） | `ISSUE_LIST.md:254-296` | **已实施（D-132，客户端间接确认：维生终态只由会话记一次）**：删掉 `MineTask.tick` 里那次调用（含 import），维生一律由会话统一记 `SURVIVAL_INTERRUPTED` | 删掉 `MineTask` 里那次（对齐 `FollowTask`/`Job`）；`lastTaskResult = "failed:"+reason` 已带原因码，信息不丢 |
| **S-4** | `FluidRiskPolicy` **零调用**（P0-C） | `ISSUE_LIST.md:151-200` | **✅ `WINDOWS_CLIENT`（D-132 附注：`[FluidMineCheck] SUMMARY … → PASS`）**：`MiningPlanner.plan` 目标确认接线 + `MineTask` 硬拒优先（不许再加高/清障）；入口 `alice:fluid_mine_check` + 场景 `alice_test:fluid_mine_course` | 探针已写好，只差接线；伐木/挖矿循环持续"挖穿未知方块"⇒ 邻格岩浆流入场景被反复暴露 ｜ **2026-09-16 复核（新发现，未被单列）**：`FluidRiskPolicy` 只查**目标格自身 + 6 邻格**（`FluidRiskPolicy.java:26-30`）⇒ 「**挖穿未知方块后邻格岩浆流入**」这一档仍未被覆盖（伐木/挖矿循环会反复暴露）⇒ 记为**真缺口**（未做：破块后的**暴露面**风险判定）|
| **S-5** | **可回收性 = 空实现**（P0-B）＝项目差异① | `ISSUE_LIST.md:94-150`、`REVIEW:73-90` | **✅ 基-1 已实施（2026-09-16 复核，原状态行作废）**：`PlannedMovementSpecs.toSpec` 的 `required` 来自**策略表** `RecoverabilityPolicy.requiredFor`（不是散落写死），`evaluatedRecoverability` 来自`RecoverabilityEvaluator.evaluate`（唯一裁决点），`MovementSpec:42` 的 `ordinal` 比较因此**非恒假** —— `FALL` 要求 `PATH_REVERSIBLE`，缺 `fall_return_verified` 事实的 FALL 边**构造即抛**（不变量代码注释原文）。另有电池步 `recoverability`（`RecoverabilityCheckTask`）+ 运行期日志 `[Recover] … PATH_REVERSIBLE/…` 可观测。**仍然为真的残余**：`SAFE_EXIT_REQUIRED`/`EMERGENCY_EXIT_REQUIRED` 两个**最高等级无生产者**（`RecoverabilityPolicy:21` 明确记为「尚未使用的等级」）⇒ 属**有意保留**，不再是「空实现」|
| **S-6** | `RiskSwitches` 全局静态 → `RiskProfile` 冻结（D-046） | `survey/08 §3`；`survey/12 §4.3`（消费者恰 2 处） | **✅ 容器已落地（2026-09-17，D-272，用户裁定「先做冻结容器」）**：`RiskProfile`（按 bot 冻结，冻结点=`assignTask`）+ 两个消费者迁移 + 命令改开关后 `freezeAll`（保 A/B）。判据：夹具 `risk_profile_frozen`（9 checks，反向对照可红）+ 门禁 **S6-P1**（消费者不得直读全局开关，注入可红）。**未做**：D-046 的字段清单、Job 候选筛选（风险等级枚举 D-059 已否） |
| **S-7** | 三个守卫默认值**互不一致**（P2-C） | `ISSUE_LIST.md:349-…` | **⚪ 实质成立、措辞需改（2026-09-16 复核）**：三个守卫确实不一致 —— `descend_overshoot` 是**开关**且默认 `false`（`RiskSwitches.java:20`），而 FALL 的 `fallRecoverable`（`SurfaceMovementProvider.java:234` `continue` 逐边守卫）与 ASCEND 的 FallingBlock 前置（`AscendExecutionFactory:68-72` `ASCEND_FALLING_BLOCK_ABOVE`）**没有开关、无条件执行**。⇒ 结论成立；**残余动作（已补登记）**：Alice 比 Baritone **多开两个守卫**，必须在对照记录里标注（否则对照结论失真）——已在 `alice-baritone-kernel-alignment.skill.md` 与 D-258 登记 |
| **S-8** | `LiveExecutionContext.policyVersion` **恒为 0**（P2-A） | `ISSUE_LIST.md:349-370` | **✅ 已收口（2026-09-16，D-264，用户裁定「删字段」）**：该字段**连一个读取者都没有**（`grep policyVersion()` = 0）⇒ 与 `PlanningDependency` 的同名位一起**删除**（7 + 6 个构造点同步）；`grep -rn policyVersion src/main/java` = **0**，编译通过、CORE PASS（若有隐藏读者，编译期即报错）| 新门禁 **S8-P1**（`check-kernel-predicates.sh`）：`policyVersion` 不得无声复活 —— 先给读取者再谈引入 |
| **S-9** | 危险伤害**按采样血量差记录** ⇒ 被回血抹平的伤害不可见 | D-261 实测；`previousHealth()` **零生产消费者** | **✅ 观测（D-271）+ 消费（D-277）均已落地**：`DamageLedger`（有界命中环 + `hitsSince`/`totalSince` 窗口查询）→ `DecisionSnapshot.damage`（窗口 200 tick）⇒ **决策层看得见挨打**。判据：夹具 `damage_event_visible`（9 checks，反向对照可红）+ 门禁 **S9-P1**。**仍未被行为消费**：维生升级与保险触发属复活线（第 2 步之后），届时直接读这本台账 |
| **S-10** | `PlanningDependency` 的分量没有任何读取者 | D-264 复核 | **✅ 已收口（2026-09-17 用户裁定「删」，D-282）**：整条移除（`PlanningDependency.java` 删除 + `MovementSpec` 分量 + 6 个构造点 + 3 处遗留 import），门禁 **S10-P1** 防复活（注入可红）。裁定依据：零读取者 + 生产侧占位数据 + `worldRevision` 恒 0。⚠️ 4 个诊断无电池覆盖（改动仅参数移除，编译即可保证）|

**未核实（需实测，不属于上述清单）**：① 工具耐久（全仓仅 `InterfaceScanner` 提及，Job 层无判定）；
② `bot` 被清除（`BotManager.remove` 不重生）后 `LumberRegionState`（按 UUID 的 SavedData）残留是否可观测
—— "常驻 Job + bot 消失"的组合没有日志覆盖。

---

## §2 内核搜索正确性（对齐审计 b 类）

> 审计原结论（`R4_AUDIT.md:400`）：64 项 = **39 未登记偏离 + 13 缺失 + 7 已登记 + 4 对齐**；
> D-036「除目标层外默认对齐 Baritone」**当时不成立**。
> **复核后大量已收口**（成本模型 D-040、启发式、A\* 系数、危险方块、放置校验、COLUMN 容差×5、
> 超时收敛、`abort()` 清进度、条件 settle、疾跑、`canWalkOn` 白名单、`WorldView` 已删 D-044）。

| # | 项 | 来源 | 现状（复核证据） | 判据 / 备注 |
|---|---|---|---|---|
| **K-1** | 预算耗尽**丢弃 best-so-far 前缀** | `R4_AUDIT.md:180` | **✅ 早已实现（2026-09-16 复核，原状态行作废）**：`AStarMovementSearch:213-227` 预算耗尽即交 `PathPlan.partial(...)`（best-so-far 前缀）；`PathRetryRunner:65-80` 消费它（`MAX_PARTIAL_HOPS=4`、`PLAN_PARTIAL_*`）；电池 BASELINE 步 `partial_search` 在 CORE 里 PASS。**D-256 补掉两个「前缀被当成完整计划」的洞**：① 前缀绕过 D-250 自洽校验 ⇒ 现在也校验、冲突时**裁到冲突之前**（`SelfWriteConsistency.safePrefixBefore`，绝不交出会执行非法边的计划）；② 前缀最后一段被当成目标段⇒ 容差收敛到 `PathSession.toleranceFor(finalSegment, planReachesGoal)`，只有真到达才用 `EXACT`。**判据**：`partial_search` 步 7 条（含 3 条前提），四条反向对照实测能红 | 对照 Baritone（2026-09-16 实地核对）：`AbstractNodeCostSearch:57 bestSoFar[]`（按启发式系数各存一份）、`:185-188 bestPathSoFar()`、`:190-216 bestSoFar(...)` 组 `Path`；`AStarPathFinder:196` 收尾时**仍返回 best-so-far 路径** —— Baritone **没有** `PARTIAL` 状态枚举，「部分」是**隐含语义**（返回路径终点 ≠ 目标，执行器走完再重算）。Alice 的**有意偏离**（已登记）：用**显式** `PlanningStatus.PARTIAL`（`reached()==false`），因为本仓契约要求「预算不够 / 真不可达 / 未加载」严格分开（D-076 / `GOAL_NOT_LOADED`），消费侧是 `PathRetryRunner` 的**有界跳数**（`MAX_PARTIAL_HOPS=4`）|
| **K-2** | **legacy 双内核仍有活引用** | `R4_AUDIT.md:231/280` | **✅ 已收口（2026-09-16 复核）**：`pathing/movement/` 包**已不存在**（`find` 零文件）；`TransferTask.java:104` 注释明写「原先这里是 legacy `SurfacePathfinder` + `PathExecutor` —— 生产路径上最后一处双内核引用」⇒ 生产路径已无 legacy 引用；全仓剩下的是**历史注释**（`FollowTask`/`WalkToTask`/`MiningPlan`/`BotCommand` 的 javadoc）。残留：`BotManager.LEGACY_PATHING_TASKS_ENABLED=false` + `legacyTaskDisabled` 的死分支（可选清理）；裸 `setBlock` 只剩**夹具自检任务**（`PathingRegressionTask`/`PathSessionDiagnosticTask`/`FootCellRuleCheck` 等，属测试脚手架） | D-031「唯一入口」当前成立；**判据**：`grep -rn "SurfacePathfinder\|new PathExecutor" src/main/java` 只应命中注释 |
| **K-3** | `safeToCancel` 抢占门控缺失 | `R4_AUDIT.md:204` | **✅ 早已实现（2026-09-16 复核，原状态行作废）**：`Task.safeToCancel()` 默认接口（`Task.java:56-65`）、`MovementExecution.safeToCancel()` 默认（`:25-35`）+ 9 个移动执行器实现（Traverse/Ascend/Descend/Diagonal/Fall/Pillar/Downward/PlaceStep…）、`PathSession:277` / `PathRetryRunner:187` / `WalkToTask:128` / `FollowTask`/`PlaceTask`/`TransferTask`/`CollectDropsTask`/`RestoreScopeTask`/`ScaffoldLifecycleTask` 透传、`BotManager:1821` 用 `task.safeToCancel() && bot.onGround()` 决定**延后取消**、`MenuSession:74` 的 K-3 门。**判据**：电池步 `k3_stop`（`K3StopCheckTask`）+ `capability_gate` 的 `safe_cancel_wiring`（默认 true / 空转安全）| 对照 Baritone：`Movement.safeToCancel` / `InventoryPauserProcess:53` |
| **K-4** | 规划/执行谓词**不统一** | `R4_AUDIT.md:220` | **✅ 已收口（2026-09-16）**：原判据部分过期（`DiagonalExecutionFactory` 早已查两侧格），但**真缺口成立**：`Traverse/Diagonal` 工厂**缺 `canSweepPlayer` 连续扫掠**（比内核**宽松** ⇒ 可能接受内核永不会生成的边）。**修**：四个干净族（TRAVERSE/DIAGONAL/ASCEND/DESCEND）的 `validate` 改为引用**规划侧同一谓词**（`MovementHelper.canTraverse/canAscend/canDescend`，provider 也用它们）；**有意不并** DOWNWARD/BREAK_*/PLACE_STEP（它们的准入故意比 `canStandCentered` 宽，provider 里写了原因）。**判据**：新门禁 `check-kernel-predicates.sh` 的 K4-P1（工厂必须引用共享谓词；**反向对照**：删掉 `canTraverse` 调用 ⇒ 门禁红）+ CORE `(38/38)` 仍 PASS（= 收紧后没有任何真实计划被判死）| 仍缺**运行时**的「越界边必须被拒」判别性场景（见 D-257 边界）|
| **K-5** | 死状态 `POSTCONDITION_FAILED` | `R4_AUDIT.md:206` | **✅ 早已实现（2026-09-16 复核，原状态行作废）**：`PathSessionStatus` 映射表把 `SETTLING_TIMEOUT`/`OVERSHOT`/`POSTCONDITION` 三个码归到 `POSTCONDITION_FAILED`（`:51-53`），生产者是 `map(code)`（执行器的后置条件类失败码不再被泛化成 `TIMEOUT`）。**判据**：`capability_gate` 步里的状态映射表断言（`ASCEND_SETTLING_TIMEOUT`/`DESCEND_OVERSHOT_BELOW_TARGET` ⇒ `POSTCONDITION_FAILED`）+ 新门禁 K5-P1（声明了的状态必须有生产者；**反向对照**：删掉全部三条映射 ⇒ 门禁红）|
| **K-6** | 其余完备性项 | `R4_AUDIT.md:182-206` | **部分已实现（2026-09-16 复核）+ 其余有意不做**：① 世界变化检测**已实现**——`PathSession:206-210` 周期健康检查（`currentTargetStillValid`）与 `:702` 前瞻段封死检查（注释即写明对照 `costVerificationLookahead`）；② 「不推进就硬失败」**已实现**（D-105 无效跳跃计数 `MAX_WASTED_JUMP_LANDINGS`、段超时、K-1 前缀跳数上限）；③ `closestPathPos`/`ticksAway`/`isReplaceable`/开门分支/直放分支/路径裁剪 favoring/双阶段预算：**确无实现**，但属「性能/润色」档（非正确性），PARKOUR 已按 D-044 定为**暂不采纳** ⇒ 记为**有意省略**，不再列为待办（谁要做请先给「从改一行到知道对不对」的判据）|
| **K-7** | **登记缺口** | 复核 | **✅ 已补登记（2026-09-16）**：① **未加载区块门控**（`GOAL_NOT_LOADED`，硬拒而不是同步加载/生成）——已在 `PlanningStatus` javadoc 写明对照 Baritone `AStarPathFinder:105-112` / `worldContainsLoadedChunk`，并进入 D-257 的登记表；② **best-so-far 前缀**（K-1）——D-256 已登记（含 Baritone 实地核对：`AbstractNodeCostSearch:57/185-216`、`AStarPathFinder:196`，**无 PARTIAL 枚举** ⇒ Alice 显式状态为有意偏离）；③ 三层超时已按 D-042 收敛（代码即登记）；④ snipsnap 语义差异见 D-065 | 规则「未登记即隐式默认 = 违规」当日成立 |
| **K-8** | **搜索不模拟自己的写入**（Baritone 也没有） | D-249/D-250 实测 | **已实施（D-250/②′，2026-09-16）**：`pathing/core/search/SelfWriteConsistency.java`（沿计划回放写入，口径**直接调执行器**的 `collectBlockers`/`placePos`）+ `CorePathPlanner` 校验不过则**禁掉那条具体边**重搜 ≤3 次；`AStarMovementSearch` 只加路径无关的禁用边过滤 | **判据**：电池硬自断言 `selfwrite_unresolved == 0`（摘要 `自写入冲突=N`）。⚠️ **不能做进搜索里按路径过滤**：A\* 位置键合并会吃掉它（实测把灌水坑逃生从可解变 `UNREACHABLE`，`own_write_support=245`）；边界：放置类镜像风险（自己放的方块占了后面要穿的格）**未做** |

---

## §3 Job 层与决策缝契约（**决定"能不能接 LLM"**）

> `JOB_LAYER_DESIGN.md` 的切片表**止于 J8**，**没有 J9+ 清单**；§10 明确把
> 「LLM 接入 / 风险画像 / 多 bot」列为**不解决**。J1–J8 已把"决策缝"的地基做完（见 `AI_PROJECT_STATE.md`）。

| # | 项 | 出处 | 现状（复核证据） |
|---|---|---|---|
| **J-1** | ✅ **已实施（D-134，客户端待测）**：两个记录类各加 `botId` + `terminalReason`，并有 `task_terminal_reason` 证据行 | `TaskExecutionRecord.java:8-19`、`BotManager.java:1225-1234` | **✅ 已实施（D-134；2026-09-16 复核，原「未实现」作废）**：`TaskExecutionRecord:25-27` 有 `terminalReason`/`botId`；生产者 `BotManager:2101-2107`、日志 `:2119 task_terminal_reason`；回读 `DecisionSnapshot:245` 进 prompt。**判据缺口**：`llm_contract` 未断言这两个键，建议补 |
| **J-2** | ✅ **已实施（D-134，客户端待测）**：`JobRequest` + `JobLauncher` + `BotManager.assignJob`（入口发料也收在这一处） | `BotManager.java:487/528/579` | **✅ 已实施（2026-09-16 复核，原「未实现」作废）**：`JobRequest:17/58-81` 五种 Job 工厂 + `JobLauncher:120` 唯一构造点 + `BotManager:560-576` 唯一入口；`assignLumberJob`/`assignMineJob`(`:512/:582`) 已是薄包装。判据：`decision_contract`／`collect_job`／`mine_menu`（原引 `:487/528/579` 已过期） |
| **J-3** | ✅ **已实施（D-134）**：终态记录/TaskOutcome 均有 `botId`（UUID 字符串） | `TaskExecutionRecord.java:8-19` vs `MULTI_BOT_INTERFACE_RESERVATION.md:7` | **⚠️ PARTIAL（2026-09-16 复核）**：`botId` 已落地（`TaskExecutionRecord:23`、`TaskOutcome:16`）；**真缺口**：`BotManager:1666 taskKind = getClass().getSimpleName()` ⇒ 多 bot／跨版本稳定标识仍缺（建议用 `taskName()`；无判据） |
| **J-4** | `job/` 包**未覆写 `failureReport()`** | 复核 | **✅ 已实施（2026-09-16 复核）**：四处覆写 —— `LumberJob:235`、`RegionLumberJob:181`、`MineJob:174`、`CollectJob:76`。判据：`llm_contract.checkJobFailureReports()`（反射断言 declaringClass==自身，4/4） |
| **J-5** | `UNTIL_FULL` 声明了**没实现** | `JOB_LAYER_DESIGN.md:383/435-439` | **❌ REAL-GAP（行准确）**：`GoalSpec:16` `UNTIL_FULL` 仅 2 命中（枚举+javadoc 自称未实现）、`:20` `stopWhenFull` 仅 1 命中；`CollectJob:60/113/208` 从不读 `productTag`。**代码已自认 §11-②** ⇒ 记为**有意不做 v1**（要做先给判据） |
| **J-6** | `MineJob.countTargetItems` 硬编码矿物清单、忽略 `productTag` | `job/mine/MineJob.java:278-302` | **✅ 已实施（2026-09-16 复核）**：`MineJob:411-418` 走 `productFilter.matches(stack)`；口径在 `job/mine/MineProductFilter.java:22`。判据：`llm_contract.checkProductFilter()`（target／default） |
| **J-7** | 决策结果/拒绝理由**无结构化回读** | `DecisionTrace` | **✅ 已实施（2026-09-16 复核）**：`GoalDirector:463-489` 写入/清除 `lastRefusal`、`:484` 读回；`DecisionSnapshot:90-95` 进 prompt；`decision/DecisionTrace` JSONL 落盘（`BotStateReport:184` 消费）；`job/DecisionTrace:30-38` 结构化 select/rejected。判据：`llm_contract.checkRefusalReadback()` + `decision_trace` |
| **J-8** | 树种过滤未做 / 非区域补种未做 | `JOB_LAYER_DESIGN.md:441-444` | **⚠️ PARTIAL（行准确）**：区域补种已做（`RegionLumberJob:470`）；**真缺口**：`species` 8 命中全是记录/日志/feature（`Tree:19`、`TreeScanner:73/127`、`LumberCandidateSource:197`、`CandidateMenu:145`）⇒ 无过滤消费者。判据：`region_maintain` 覆盖补种，物种过滤无判据 |
| **J-10 掉落物归属与收集授权** | ✅ **已实施并 `WINDOWS_CLIENT`**（D-143/D-144）：`DropProvenance`（我方直接/间接 60tick·4格松窗 / `GRANTED_AREA` / `FOREIGN`）+ `DropPolicy` 唯一判定入口 + **被动拾取闸门**（`EntityItemPickupEvent`，节流+计数）+ `CollectGrant` 选区授权（ONCE/SESSION/ALWAYS，`always` 报告显式标记）+ 收集按策略过滤（`anyDrops` 退役）。**遗留登记**：`CollectDropsTask` 的 `policy_blocked` 终态区分、"我方放置/拆除点"并入松窗。（原提案： 现在只认"我方破坏事件配对"⇒ 漏掉**我方行为的间接后果**（树叶衰减掉树苗/木棍、仙人掌/甘蔗被移除支撑后弹出）与**玩家派活**（捡玩家授权区里的东西）） ｜ **2026-09-16 处置**：`policy_blocked` 仪器化**已接**（计数 + 单独告警 + `SUMMARY policy_blocked=N` + `terminalReason()=policy_blocked:N`，进 D-134 通路），但**判据未成**：夹具里让主动收集去收 FOREIGN 掉落物时，任务 `entities=0/0 ticks=1`（成员按作用域发现 ⇒ 外来掉落物不入集合）⇒ 期望分支不可达；**可达场景**只剩「跟踪中的掉落物中途被撤授权」（需新夹具，未做）。⚠️ 另发现 `single:pickup_gate` 单跑本就不成立（基线同样 FAIL ⇒ EXTRA 步入口不可靠）。详见 D-263 |
| **J-9** | **LLM 接入本体**（差异②）✅ **已实施（D-135，客户端待测）**：快照契约 + 动作词汇表 + 事件驱动/节流 + 严格拒绝 + 决策 trace；配置从当前部署复制（`config/alice-llm.json`） | `JOB_LAYER_DESIGN.md:390-396`、`:21/:244-247` | **✅ 已实施（D-135；2026-09-16 复核，原「未实现」作废）**：① `LlmClient:52 askAsync` 真 HTTP ② `DecisionSnapshot.buildPrompt` ③ `GoalAction:21` sealed 词汇表 ④ `GoalDirector:24/260` 节奏。判据：`decision_contract`／`decision_trace`／`llm_contract` + `alice:goal_director`；`WINDOWS_CLIENT` 历史证据见 §6.47 |

---

## §4 世界写入授权登记缺口（G 项）

来源 `WORLD_WRITE_AUTHORIZATION.md:69-77,101`。**G1/G2/G9 已修**（R2a/R2b）。

| # | 项 | 现状 |
|---|---|---|
| **G3** | 模组连锁破坏**无凭证**（`MineTask.beginChain` 反射调模组 `MiningScheduler`） | **✅ 已接线（2026-09-16 复核）**：`MineTask:532-538` 逐次 `WriteBudget.consumeBreak`，REFUSED 即 `ChainMining.stop`。**缺判据 + 死哨兵**：`chainRefusedByBudget`(`:134/534`) **只写不读**，且无 chain 电池步 ⇒ 建议补 `chain_budget` 步（全表唯一「接了线没判据」项） ｜ **✅ 判据已补（2026-09-16，D-260）**：死哨兵接进**已有**通路 —— `MineTask.chainRefusedByBudget()` + `terminalReason()==「chain_budget_refused」`（D-134 ⇒ `task_terminal_reason` 日志与决策快照）；夹具新增 `exec_chain_budget_refused`（`WriteBudget.setCaps` 把预算压到 1 ⇒ **确定性**走到拒绝分支，无需 65 格场景）。实测 `refused=true/terminalReason=chain_budget_refused/chainStopped=true/remainingBreaks=0/status=DONE`；**反向对照**：拆掉 `terminalReason()` 的返回 ⇒ `mine_regression=FAIL` |
| **G4** | Slice B2：**尝试级 tick 预算** | **❌ REAL-GAP，口径已缩小（2026-09-16 复核）**：计数侧已落地（`WriteBudget.plannedWritesAllowed:311`、`MovementContext:98`、`PathSession:757`、`PathRetryRunner:104-115`）；**只剩 tick 预算**（`AI_DECISIONS.md:2671-2674`：`MiningBudget.maxExtraBreakTicks`）。`write_budget` 判次数非 tick |
| **G5** | **容器写入是第三个维度**（`TransferTask`/`ChestBotTransferPrimitive`） | **⚠️ PARTIAL（2026-09-16 复核，原「完全不覆盖」作废）**：已接 `WriteBudget.consumeContainerWrite:234` → `TransferTask:220/290/427/449`、`MachineCycle:427`、`StationProvision:243`、`CraftJob:469`；策略层 `WritePolicyMatrix:616/664`；A11 登记 `WORLD_WRITE_AUTHORIZATION.md:50`。**新发现真缺口（本次扫出）**：`InventoryCraft:300`／`MenuSession:256` 直调 `menu.clicked(...)`（该文件 WriteBudget 命中 0）⇒ **菜单合成绕过容器写入预算与策略矩阵**，本次复核最该排期 |
| **G6** | legacy `pathing/movement` **裸写入** | **✅ 已收口（2026-09-16 复核）**：`DescendMovement`/`PillarMovement` 源码已不存在（只留在 archive）；`MovementHelper.generateMovements` 0 命中。与 K-2 同一条 |
| **G7** | **死闸门**：`FluidRiskPolicy.miningRefusal` 无调用者，而 `MineTask` 保留 `fluid_risk_lava` 硬拒绝分支 | **✅ 已收口（2026-09-16 复核）**：`MiningPlanner:71` 已调 `FluidRiskPolicy.miningRefusal` → `:74-76` 硬拒码，消费者 `MineTask:605/850`。判据：`FluidMineCheckTask:133/174-175`。与 S-4 同一件事 |
| **G8** | **✅ 行准确（2026-09-16 复核）**：6 字段确在读（`CapabilityGate:62/70/77/81/85/88`）；4 字段确未读（`mutationIntents` 仅 toString+自洽断言；`maxNaturalDrop`／`supportsMidExecutionRevalidation`／`intrinsicReversibility` 无行为消费者）。判据：`capability_gate` |
| **G-新** | **✅ 已补登记（D-157；2026-09-16 复核）**：`WORLD_WRITE_AUTHORIZATION.md:53` A9 已登记；生产链 `RegionLumberJob:470-472` grant+consumePlace + `:492` setBlock + `:493` 触及校验 + `:494` 账本 KEEP。判据：`region_maintain`（`:473-474`） |

---

## §5 验证债

| # | 项 | 说明 |
|---|---|---|
| **V-1** | `AI_TEST_MATRIX.md` 里 **16 处「待测」** | **✅ 已逐条核证（2026-09-16，D-262）**：实际 **22 行**（原写 16）；本轮按「顶部表 = 权威等级」的口径逐行取证 ⇒ **16 行改判**（`WINDOWS_CLIENT`，其中 6 行同时 `USER_ACCEPTED`）、1 行并入他行、1 行机制已删、1 行改 `SERVER_TESTED`；**剩 5 行真待测**：105/107（Baritone 对照 = 全表唯一真验证债 V-4）、142（`[Job] launch` 从未出现，`RegressionBatteryTask:312` 直接 `new LumberJob`）、207（`/alice auto-mine` 无实测）、217（`restore_check` 未单跑）。另在本矩阵写入 **4 处自相矛盾**（顶部表 vs 历史行）留档 |
| **V-2** | **未覆盖行为分支** | **⚠️ PARTIAL（2026-09-16 复核）**：矩阵 `:171`「未覆盖：`climb_incomplete` 的场景」与 `:172`「未覆盖：`too_far` 分支…**真实崩溃重启路径**」**今天仍在**；`AI_DECISIONS.md:5679` 的真实重启只覆盖 D-154 决策 trace ⇒ **两个真缺口**：`too_far`／`climb_incomplete` 缺带入口的判据（其余几个分支已被后续步覆盖） |
| **V-3** | **T6 盲区** | **✅ 大部分收口（2026-09-16 复核）**：`AI_DECISIONS.md:3866-3867`（D-125）原文成立，但 `:3325` 已定根因（**夹具几何**）、`:3389` 已记「F4（夹具通道）—— 修好」⇒ 改写为「F4 已修好；剩余是工具分工问题」，不再是验证债 |
| **V-4** | **Baritone 对照实验** | **⚠️ 半收口、不阻塞（2026-09-17 用户裁定：这个数据现在不重要）**：① **计时口径已修**（D-280：`sw_start` 改"待发"，玩家离开起点才计时 ⇒ 打字延迟不计入；判据 `contrast_timer` 11 checks，两组反向对照可红）；② **Alice 侧已无头化**（`single:fall_execute` ⇒ FALL `exec_ticks=17`；`single:pillar_execute` ⇒ PILLAR `exec_ticks=36`，以后不用客户端）；③ Baritone 侧重跑**可选**（两个客户端世界的数据包已更新，进世界 `/reload` 即可）。**旧数字 43/44/64/100 与 45/45/48/76 含打字时间 ⇒ 只当上界** |
| **V-5** | **未核实项** | **⚠️ PARTIAL（2026-09-16 复核）**：4 项里 **3 项已收口** —— 工具耐久（`AI_DECISIONS.md:5785/5799` 基-9 客户端全绿）、`MovementSpec`/`MovementCapabilities` 一致性（`:5806` D-157）、Diagonal（`:614` D-044⑨）；**仍真的 1 项**：bot 被清除后 `LumberRegionState`（按 UUID 的 SavedData）残留是否可观测（本文件 §1 表后那段） |

---

## §5.5 代码结构债（**2026-09-15 用户复盘源码时发现**，AI 已逐条核实）

| # | 项 | 说明 |
|---|---|---|
| **TD-1** | **前置谓词被写两遍：`*ExecutionFactory.validate()` 与 `*Execution.preconditionsHold()`** | 见下（**用户看到 1 处，实测 8 处**） |

### TD-1 详录

**位置（符号名，不用行号）**：`pathing/core/*ExecutionFactory.validate()` 对 `pathing/core/*Execution.preconditionsHold()`。
两者各自实现同一组"可走"谓词（`canWalkThrough(to)` + `canWalkThrough(to.above())` + `canWalkOn(to)`）。

**实测重复范围**（计数命令见文末；用户只看到 Traverse 一处，实际 **8/10**）：

| 动作 | `validate` 里 | `preconditionsHold` 里 |
|---|---|---|
| Traverse / Diagonal / Ascend / Descend / Downward / BreakAndTraverse / BreakAndEnter | 1 | 1 |
| PlaceStepAndTraverse | 1 | 2 |
| Fall | 1 | 0（换成自己的谓词） |
| Pillar | 0 | 0（换成自己的谓词） |

**已经分叉的那一处（用户发现的，已核实为真）**：
`TraverseExecutionFactory.validate` 里**没有几何检查**，而 `TraverseExecution.preconditionsHold` 里**有**
（`from.y == to.y && 曼哈顿距离 == 1`）。**Traverse 是 10 个动作里唯一没有 `<动作>_INVALID_GEOMETRY` 的**：
其余 9 个工厂全都有（`grep -n INVALID_GEOMETRY src/main/java/com/dddgn/alice/pathing/core/*ExecutionFactory.java` ⇒ 9 行，无 Traverse）。
⇒ **越距的 `MovementSpec` 能通过 `validate`**，直到运行期第一帧 `TraverseExecution.tick()` 的 `PRECONDITION_CHECK`
才失败，错误码从"派发前拦截的几何码"退化成 `TRAVERSE_INVALID_PRECONDITION`。
**派发前拦截点确实存在**：`PathSession.java:345` 与 `PathingBatteryTask` / `Ascend|Descend|Traverse|Diagonal|ChainDiagnosticTask`
都是**先 `factory.validate(spec, ctx)` 再 `create`**。

**是自始缺失，不是有意移除（AI 核实，含一条自我纠错）**：
- `preconditionsHold` 与 9 个 `*_INVALID_GEOMETRY` 都出自**同一个提交 `5d63cdf`**（Movement core R1/R2）；
  看该提交里的 `TraverseExecutionFactory.validate` **本来就没有几何检查** ⇒ 从第一天就是漏的。
- ⚠️ **方法学坑（差点让我写错）**：`git log -S "TRAVERSE_INVALID_GEOMETRY"`（**不带前引号**）会**因子串命中**
  `BREAK_AND_TRAVERSE_INVALID_GEOMETRY` / `PLACE_STEP_AND_TRAVERSE_INVALID_GEOMETRY` 而报"历史上存在过"
  —— 我据此一度准备写"该码曾被有意移除"。**精确检索必须带前引号**：
  `git log --all -S '"TRAVERSE_INVALID_GEOMETRY"'` ⇒ **空**（对照：`'"DIAGONAL_INVALID_GEOMETRY"'` 能搜到 `5d63cdf`）。

**优先级：低**（用户判定，AI 同意）：**无已知故障** —— 规划器构造的 TRAVERSE 天然相邻，
所以运行期那份检查一直兜住了；这条属**防御性不变式的位置不对**，不是活 bug。

**建议修法（未实施，分两步、各自可独立落地）**：
1. **Traverse 对齐兄弟**：`TraverseExecutionFactory.validate` 补几何检查，沿用兄弟的命名形状
   `TRAVERSE_INVALID_GEOMETRY`（已证该码从未存在 ⇒ 不是推翻旧决定）。
2. **单一定义（较大）**：让每个 `*Execution` 复用其工厂的谓词（或把共享谓词提到 `MovementHelper`），
   8 个动作的机械重构。**必须独立一轮做**，判据 = `tools/headless-battery.sh single:pathing` +
   `core` 的**场景行逐字不变**（这套判据今天刚建好，见 D-220）。

**不得顺手改的地方**：`TRAVERSE_INVALID_PRECONDITION` 这个码**不能回收改名** ——
`task/CapabilityGateCheckTask.java:280` 把它映射进 `INVALID_PRECONDITION` 状态表（是有断言的契约）。

**复算命令**：
```bash
cd /home/fb486/projects/alice
grep -n INVALID_GEOMETRY src/main/java/com/dddgn/alice/pathing/core/*ExecutionFactory.java   # 9 行，无 TRAVERSE
grep -rn "private boolean preconditionsHold" src/main/java/com/dddgn/alice/pathing/core/     # 10 处
for f in Traverse Diagonal Ascend Descend Fall Downward Pillar BreakAndTraverse BreakAndEnter PlaceStepAndTraverse; do
  printf "%-24s validate=%s execution=%s\n" "$f" \
    "$(grep -c 'canWalkOn(context.level(), to)' src/main/java/com/dddgn/alice/pathing/core/${f}ExecutionFactory.java)" \
    "$(grep -c 'canWalkOn(level, to)' src/main/java/com/dddgn/alice/pathing/core/${f}Execution.java)"
done
git log --all --oneline -S '"TRAVERSE_INVALID_GEOMETRY"'     # 空 ⇒ 该码从未存在
```

---

## §5.6 议题：风险管控体系的现状（用户 2026-09-15 提出，**只记录不展开**）

**用户的问题**：`PathRequest.miningApproach` 禁用 `PILLAR/FALL/DOWNWARD` 本该是"**可选的风险控制**"，
现在是**硬禁**，会让挖矿麻烦很多。用户的判断：① 现在的风险管控**几乎一片空白、只留草案**；
② 或者风险是**在别处以别的方式**降低的；③ **先完成当前主线，不适合马上展开修复**。

**AI 核实结论：①②都对，但要说清是"哪一层空白"**：

- **① 成立（指"统一的风险系统"）**：`pathing/risk/RiskSwitches` 全仓只有 **1 个开关**（`DESCEND_OVERSHOOT_GUARD`），
  其 javadoc 自述"**评估体系（任务层/维度/群系/现实条件/局部场景）只做讨论与预留，尚未实现**"；
  D-046/D-059 定的模型是"**默认全部关闭 = Baritone 原样高风险**，开关把某些部分换成低风险"。
  ⇒ **"可按场景/维度调节的风险等级"这一层确实接近空白**，而且它的**极性**与 `miningApproach` 相反
  （后者默认就不许）—— 这正是用户"本来该是可选的"这一直觉的来源。
- **② 成立：风险不是没人管，而是分散在四层**（每层都有可执行的闸门/断言）：

| 层 | 机制（符号/命令） | 今天的状态 |
|---|---|---|
| **授权层** | D-076：寻路默认纯通行；写能力必须走**显式登记的入口**（`PathRequest.of` / `miningApproach` / `climbApproach` / `scaffoldRemoval` …）；门禁 `tools/check-authz-registry.sh` | **在管**。`miningApproach` 的三件禁用**属于这一层** —— 它是**二元授权决定**，不是风险等级 |
| **预算/闸门层** | `WriteBudget`（每作用域 64 破坏/32 放置/32 容器写）、`MiningBudget`、`MiningProfile`（`maxGainSteps` ≤12 方块 / `clearBudget` / `restoreOwnPlacements`）、段超时、`safeToCancel` | **在管** |
| **策略/归因层** | `action/WritePolicyMatrix`（区域 × 任务 → 回收义务 / 移动授权）+ 电池 `write_policy` 步的**负例**断言 | **在管**（2026-09-15 客户端实测命中 `WRITE_POLICY_MOVEMENT_DENIED … 该行只授权 [PURE_TRAVERSAL, OF]`） |
| **安全/环境层** | `SafeZoneData` 保护理由；`PLAN_SAFE_ROUTE` 的岩浆接触断言（`lava_contacts` 必须 0）；未知模组能力默认只读（`CapabilityGate`） | **在管** |

⇒ **准确说法**：今天是"**默认不许 + 显式登记 + 预算闸门**"三件事分散在各层，
**空白的是"可调的风险等级与评估器"**（`RiskSwitches` 那一层）。所以"把三件禁用改成开关"**不是翻一个标志**，
而是要**先建那个空白的层**（或者退一步：加一个**已登记的兄弟入口** `miningApproachHighRisk` + 默认关的开关）。

**三个候选方向（2026-09-15 讨论，未采纳）**：
- **A（便宜，不动授权面）**：痛点若是"目标在上面够不到"，给 `MineJob` 的信封加 `withGain(...)` ——
  机制现成（`MiningProfile.withGain`，归因 `WriteReason.STANDING_SPACE`），伐木已在用（`LumberJob` 的 `withGain`）；
  而 `MineJob` 今天是 `TUNNEL_ALLOWED.withRestore()` ⇒ **gain = 0**。
- **B（真要那三个 movement 时）**：新增**已登记**的 `miningApproachHighRisk` + `RiskSwitches` 开关（默认关 = 今天行为），
  并把 `write_policy` 的断言扩成"两个入口都存在且集合不同"，登记进 `docs/WORLD_WRITE_AUTHORIZATION.md`。
- **C**：维持现状。

**✅ 复核（2026-09-15 深夜，按用户"按排队顺序继续做"逐条核对三个触发条件，结论：仍不展开）**：
- **触发③（`RiskSwitches` 需要第 2 个开关）未成立**：该类今天仍只有 **1 个开关**
  （`DESCEND_OVERSHOOT_GUARD = "descend_overshoot"`，`KNOWN` 集合里就它一个，默认 `false`）。
- **触发①（真实挖矿出现"明明有办法却够不到"的具体案例）未成立**：`MineJob` 今天仍是
  `MiningProfile.TUNNEL_ALLOWED.withRestore()`（**gain = 0**，与 §5.6 记录一致），
  而 `LumberJob` 用的是 `STANDABLE_ONLY.withGain(MAX_GAIN_PER_TREE)`（同一机制现成可用）；
  台账/评审里**没有**任何"目标 + bot 坐标 + 失败行"的真实案例。
- **触发②（开始做决策层 / 蓝图 §1.2「玩家登记自己的流水线」）未开始**：M 线刚收口（D-231/D-232），
  决策层下一件是 ③ 之外的排队项，尚未进入需要"可调风险等级"的阶段。
⇒ **维持 D-XXX 口径：本议题继续只记录**；一旦上面任一条成立，走候选 **A**（最便宜：给 `MineJob` 加
`withGain(...)`，机制现成、伐木已在用）而不是直接放开那三个 movement。

**用户裁定（2026-09-15）**：**先完成当前主线，本议题只记录、不展开修复。**
**复核触发**：① 真实挖矿出现"明明有办法却够不到"的**具体案例**（目标 + bot 坐标 + 失败行）；
② 开始做决策层 / 蓝图 §1.2「玩家登记自己的流水线」而需要一个**可调风险等级**时；
③ `RiskSwitches` 需要第 2 个开关时（那时顺手把它做成真正的"风险配置面"）。

**同议题的负例缺口**：物品侧"夹具未生效 ⇒ `FIXTURE_NOT_FIRED` + FAILED"分支 ——
**✅ 2026-09-15 已造出可一键执行的负例场景**（`survey/08` §9#4 的落地）：
- 新场景函数 **`alice_test:r4_negative_disturb`**（`tools/test-scenes/.../functions/`，已在
  `tools/check-scene-connectivity.py` 的 `SCENES` 登记；`--all` 23 个场景全过）。
- **做法是确定性的**：扰动是**单格判定**（`to = foot + (dx,0,dz)`，`dz=+1` ⇒ 落在 **z=67**），
  把该列脚位层（y=64）填成石头 ⇒ `canWalkThrough(to)` 为假 ⇒ 40 tick 宽限内无合法落点
  ⇒ `disturb_not_applicable` ⇒ 任务带 `/FIXTURE_NOT_FIRED=[disturb]` **如实 FAILED**。
  **不影响 bot 自己的通路**（`place_course` 走廊全程在 z=66，实测分段日志确认）。
- **一键入口**：`/reload` 一次（新函数要重载数据包）→ `/function alice_test:r4_negative_disturb`
  → 右键 `alice:pathing_disturber` 一次。**看到 FAILED 才是对的**（旧行为会静默当成功）。
  **恢复**：`/function alice_test:place_course_reset`。
- ⚠️ **第一次实测失败（2026-09-15 18:33，用户客户端）**：场景确实跑起来了
  （`已执行函数 alice_test:r4_negative_disturb 中的 22 条命令` + tellraw 出现），但夹具**仍然动手了**：
  `[R4 Fixture] disturbed from=7,65,66 to=7,65,67 tick=48` + `COMPLETED segments=10/10` ⇒
  **负例分支没被走到**。根因（日志可判读）：`place_course` 地板在 y=63 ⇒ 脚位本应 y=64，
  但**扰动是"先等 `disturbTick=30`、失败则每 tick 重试到 +40 宽限"**，bot 中途踩自己放的台阶升到
  **y=65**，而第一版只填了 **y=64 一层** ⇒ tick 48 拿到 `to=(7,65,67)` 那个没被填的格子 ⇒ 照样动手。
  ⇒ **修法：填整列（y=60..67）**，已改 + 已重新复制到客户端存档。
- ✅ **第二次实测通过（2026-09-15 18:36，用户客户端）**：`[R4 Fixture] not_fired … missing=[disturb]` +
  `task_execution_terminal … terminal=FAILED code=failed:FIXTURE_NOT_FIRED:[disturb]`，且 `[R4 Fixture] disturbed`
  计数 = **0**（无静默成功）⇒ **负例分支升 `WINDOWS_CLIENT`**。证据
  `.alice-supervision/client-tests/d220-t3-20260915/evidence/r4-negative-key-lines.log` +
  截图 `screenshots/2026-09-15_18.37.13.png`。
  ⚠️ **预期更正**：`disturb_not_applicable` 只在整趟跑过 tick 70 时才出现；本次 65 tick 走完 ⇒ 抓到它的是
  **终态断言**（`FixtureScript.notFired`），与计时无关 —— 两个机制互补，别混为一谈。

---

## §6 文档债

| # | 项 | 说明 |
|---|---|---|
| **D-1** | 三份审计报告的**包路径全部过期** | **⚠️ 措辞需改（2026-09-16 复核：原断言一半不成立）**：`grep -rn "com/alice/"` 那三份报告 ⇒ **0 命中**（报告用的是相对路径），所以「包路径全部过期」**不成立**；**真正过期的是常量**：`docs/reference/BARITONE_PORTING_CHECKLIST.md:21` 写 `maxFallHeightNoWater = 1`，而今日代码是 **`FALL_DROPS = {2, 3}`**（`SurfaceMovementProvider:137`，D-058）⇒ 已在 2026-09-16 就地修正（这类过期会**污染对照结论**）。另：`WorldView` 确已删（D-044）但报告里仍有悬空引用（历史文档不改，仅登记） |
| **D-2** | `reference/BARITONE_PORTING_CHECKLIST.md:39` 的**禁令已被推翻** | **⚠️ PARTIAL（2026-09-16 复核：3/5 已被取代）**：`checklist:39` 原文未改，但 `MovementType.java:5-14` 的 10 个枚举**已含** DOWNWARD／FALL／PILLAR（`R4_BARITONE_ALIGNMENT_AUDIT.md:252` 记 `WINDOWS_CLIENT`）⇒ 3 条禁令已被 D-048/D-050/D-055 取代；**PARKOUR 与水桶仍关闭**（`AI_DECISIONS.md:615` D-044 Q1③ 暂不采纳） |
| **D-3** | `JOB_LAYER_DESIGN.md` §11 状态过期 | **✅ 已就地更新（2026-09-16 复核）**：`JOB_LAYER_DESIGN.md:410`（攀爬已由 J7 实现）、`:449`（补种 WINDOWS_CLIENT）、`:394`（§10 已更新）⇒ 仅 `:27`「❌ 攀爬超高的树」一行未同步（可删该行） |
| **D-4** | `AI_PROJECT_STATE.md` 中段已重写 | **✅ 已完成（2026-09-16 复核）**：`git log -1 e7f1a87` = 「docs(state): 重写 09-07 的过时中段…」；仅 `AI_PROJECT_STATE.md:5` 的「更新时间」字样未跟着改 ⇒ 本行删除即可 |
| **D-5** | `ALIGNMENT_OPEN_QUESTIONS.md` **Q1/Q4/Q7 仍未裁定** | **✅ 已全部裁定（2026-09-16 复核）**：Q7→D-040（`ALIGNMENT_OPEN_QUESTIONS.md:176`）、Q4→D-043（`AI_DECISIONS.md:567`）、Q1→基-1（`:5459/5471/5477`，客户端全绿）；Q2/Q3 早已撤回（`:110` D-038）⇒ 本行删除 |
| **D-6** | `RISK_MODES_DISCUSSION.md`（H/G/S 风险模式） | **⚪ 不是债（2026-09-16 复核）**：`RISK_MODES_DISCUSSION.md` 与 D-046「已决策、预留」一致；`RiskSwitches` 仍 1 开关（见 S-6）⇒ 从「文档债」移出，保留为**有意保留** |

---

## §6.9 传输模块彻查（2026-09-13，用户要求）

**产出**：[`TRANSFER_MODULE_AUDIT.md`](TRANSFER_MODULE_AUDIT.md)（架构图 + 6 条发现，逐条带 `文件:行` 证据）。

**结论**：**不是屎山**，但三处真问题 —— ① **生产/测试错位**（533 行夹具在生产树，测试钩子伸进写入原语
`ChestBotTransferPrimitive:35/42/59/68`、生产状态机里带夹具短路 `TransferTask:31/75`）；
② **死码/只写状态**（3 个零引用错误码 + 2 个只写状态）；③ **容器写入无授权/预算维度**（0 处 `WriteBudget/WriteGrant`）。
核心（两段式写入 + 三重增量证明 / `blocksBot` 在途阻塞 / `SERVER_RESTART` 挂起）**扎实，不建议推倒重写**。

**建议**：定向重构 R1 生产/测试分离 → R2 新增 `alice:transfer_check` 一键入口 → R3 清死码 + 裁定授权维度；
**退役 `/alice selftest`**（必崩于无 Mekanism 客户端 + 与 in-game 电池重复 + 判据陈旧），
但**立即修** `InterfaceScanner` 的 Mekanism 硬引用（活雷）。

**用户裁定（2026-09-13）**：**R1+R2+R3 全做**；**容器写入算世界改动**（进 A 表 + 授权/预算）。

**已先行完成（D-160）**：`InterfaceScanner` 的 **Mekanism 硬引用**拆除（活雷；2026-09-13 崩溃根因）
—— Mek 代码整体搬进 `MekanismScanner`，只在 `ModList.isLoaded("mekanism")` 时触碰（JVM 惰性加载 ⇒ 缺席永不加载）。

**已执行（D-161）**：依赖清理（build.gradle 去掉 Mekanism，删除 `MekanismScanner`）/ **selftest 退役**
（BotSelftest + 命令 + legacy `PathingRegression`，有用断言救出为 `FootCellRuleCheck`）/
**R1 夹具迁出**（`com.dddgn.alice.fixture.transfer`，生产只留 `TransferTestHooks` 一个惰性接缝）/
**R2 一键入口** `alice:transfer_check`（电池第 23 步）。
**已做（D-162）**：**R3**（删 3 死码；两个只写状态重新定性为**审计哨兵**并注明；容器写入纳入
"世界改动"体系 —— `WriteReason.CONTAINER_TRANSFER` + `WriteBudget` 第三维度 `maxContainerWrites=32` +
两段写入前消费 + 拒绝码 `container_budget_exhausted` + G5 记录带 `requester/reason` + 登记表 **A11**）+
**L1**（行走目标改为**端点附近最近合法站点**、到达与写入前各做一次**触及校验**、新增
`endpoint_no_standing_point`/`endpoint_out_of_reach` 两码）；
**L2（真实 openMenu 菜单协议）另立项讨论**（用户：影响整个项目的模组方块交互基底）。

## §6.10 方块交互三条路线对比（2026-09-13 用户提问）

**产出**：[`INTERACTION_LAYERS_COMPARISON.md`](INTERACTION_LAYERS_COMPARISON.md)（A 接口直写 / B 菜单协议 /
C 视觉识别；含"兼容性与效率""玩家能看到什么"两项逐维度对比 + 6 个讨论点的两条路线形态）。

**要点**：
- **C（视觉识别）与 B 无关**：B 走的是真实玩家客户端同一条协议（`useItemOn` + `ContainerClick` 的服务端落点），
  不渲染、不看屏幕、不需要真实客户端；
- **兼容性**：A 只覆盖 Forge capability（物品/流体/能量），**配置/安全/升级只能靠模组专属接口**（强耦合、易碎），
  且**可能绕过模组的权限/统计/GUI 钩子**；B 走模组自己的入口 ⇒ 不绕过、版本更稳，但受触及/菜单校验约束，且做不到"玩家做不到的事"；
- **效率**：A 微秒级、可跨距离批量；B 毫秒级、逐槽点击（慢，但那正是玩家的真实速度）；
- **玩家可见**：A 几乎不可见（物品凭空变化、无开盖音效动画、可能无声绕过保护）；B 与真人一致（转向→右键→开盖动画/音效→逐堆移动→关盖）。
- **建议**：**分层** —— **B 作为默认**（模组自己的契约、不绕过权限、观感可读），**A 仅作显式优化**
  （适配表声明"语义等价"时才用，且仍守 L1 站位/触及）；并把"**旁观者能看懂 bot 在做什么**"写成产品要求。
- **用户裁定（2026-09-13）**：同意 **"B 默认 + A 显式优化"**；先做第 5 项验证，且**加延迟**便于观察。
  **已实现探针（D-163）**：`alice:menu_probe`（约 8 秒）—— `useItemOn` 开真菜单 → `menu.clicked` 两次搬铁锭 →
  `closeContainer`；判据 `[MenuProbe] SUMMARY menu_opened=… clicks=… item_moved=… verdict=…`。
  **结果待测**：通过 ⇒ B 路线可行（继续做槽位语义表/生命周期/A11 衔接）；不通过 ⇒ B 路线重新评估。

## §6.11 2026-09-13 会话收尾状态（交接用）

**本会话完成的**（全部有文档与判据）：
- **基-1 可回收性不变式**（两条轴：逐步有回程 / 活动无残留）——`WINDOWS_CLIENT`；
- **基-2 决策层进回归电池**（电池 9 → 26 项）；**基-3 S4 事件层**——`WINDOWS_CLIENT`；
- **基-4 决策 trace 落盘 + 跨重启语义**（含一次真实重启验证）——`WINDOWS_CLIENT`；
- **基-5 LLM 上抛契约**（J-4/J-6/J-7）——`WINDOWS_CLIENT`；
- **基-8 授权登记缺口**（G8 能力闸门 / A9 登记 / G3 外来破坏留痕 / G5 容器写入留痕）——`WINDOWS_CLIENT`；
- **基-9 工具与耐久管理（第一批）**——`WINDOWS_CLIENT`；
- **基-7 K-1**（`PlanningStatus.PARTIAL` + best-so-far 前缀 + 消费者真的用它）——`WINDOWS_CLIENT`；
  **K-2 迁移**（`TransferTask` 行走 + `/alice path` 诊断切到新内核 ⇒ 生产/开发路径无 legacy 引用）；
- **L2 交互路线**：对比文档（A/B/C 三路线）+ 探针验证（`alice:menu_probe`，用户实测通过）+
  `MenuSession` 组件化 + `MenuLifec​ycle` 生命周期（收尾收敛点 + 看门狗）+ **生产化**
  （`ContainerSemantics` v0 / 路线开关 `/alice transfer route` / 传输默认走菜单路线 / 菜单打开入事件环）；
- **传输模块彻查**（`TRANSFER_MODULE_AUDIT.md`）+ R1（夹具迁出生产）+ R2（`alice:transfer_check`）+
  R3（死码/授权预算/A11）+ L1（附近可站点 + 触及校验）；
- **环境事故与修复**：D 盘被 330 份镜像备份写满 ⇒ 清理 + 镜像脚本改为"默认不备份/不校验、自动轮转"（8.6 秒）；
  **依赖清理**（移除 Mekanism 等可选模组依赖，删 `MekanismScanner`）+ **`/alice selftest` 退役**
  （有用断言救出为 `FootCellRuleCheck`）。

**唯一未收口的（明确交接）**：`alice:transfer_check` 的 **`end_to_end`** 用例。
根因已精确定位并修复（**背包索引 ≠ 菜单槽位号**，见 D-165 附注四），
但**修复未经过客户端验证** ⇒ 下次开工第一件事：跑一次 `alice:transfer_check`，
判据 `[Transfer] SUMMARY … end_to_end=PASS … verdict=PASS` 且 `[Transfer] end_to_end … moved=3`。
若仍失败，日志里 `[Transfer] suspend code=… phase=… src=… dest=…` 与 `[Menu] …` 会直接给出第一现场。

**下次开工的建议顺序**：① 复测并收口 `end_to_end`；② 电池 `transfer` 步保持绿色（回归门不能长期红）；
③ 若 L2 生产化通过 ⇒ 用 `/alice transfer route capability|menu` 做一次 A/B 观感对照；

### §6.12 2026-09-13 下半场收尾（K-3/K-4/D-168/D-169/D-170）

> `end_to_end` 已闭合，两份独立证据：① 单跑 `alice:transfer_check`（10:09，`2026-09-13-7.log.gz`）
> ② 本轮 26 项电池里的 `transfer` 步（11:06，`2026-09-13-1.log.gz`）——
> 两次都是 `[Transfer] SUMMARY fixture=PASS end_to_end=PASS selection=PASS selector_events=PASS command_parse=PASS verdict=PASS`。
> §6.11 的"唯一未收口"**已闭合**。

**本轮完成的（都有客户端证据）**：

| 项 | 结论 | 依据 | 等级 |
|---|---|---|---|
| K-4 谓词统一 | 6 处复制 → 唯一定义 `canStandCentered`（8 调用点）；目标准入先测量；真异常 0 ⇒ **不引硬拒**，收口为"唯一定义 + 永久自断言" | 电池 `K4=OK(goal_not_standable=0 final_segment_not_standable=0 写入类例外=88)` | `WINDOWS_CLIENT` |
| D-168 夹具测量 | `dropsLeft` 改基线 UUID 增量 + 三场景清实体 ⇒ 世界残留不再假失败 | `mine_regression` 11/11 PASS | `WINDOWS_CLIENT` |
| D-169 电池自杀 | K-3 退出电池（25 → 23）+ `fixture_not_top_level` 前提断言 + 手工入口两模式 | 电池 `(26/26) ticks=3507 → PASS` | `WINDOWS_CLIENT` |
| D-170 资源缺陷 | 3 个 0 字节模型 + 1 个死贴图引用补齐；新增 `tools/check-item-models.sh` 并接入构建前清单 | 客户端 `Failed to load model alice` = **0** 条 | `WINDOWS_CLIENT` |

**唯一待测**：**K-3 自检复测**（`alice:k3_stop_check` 右键 DEFER / Shift+右键 FORCED，各 5 秒；
判据 `bot_report` → `deferred=1` / `forcedUnsafe=1`）。FORCED 用例**至今从未跑过**。

**§6.12 续（同日晚些时候）**：
- **K-2 第三批已完成（D-171）**：legacy 双内核**整批删除** —— `pathing/` 顶层 21 个文件删 19 个
  （`AStarPathfinder`/`AStarPathPlanner`/`PathPlanner(s)`/`Local`/`Hybrid`/`Surface`/`OpenSet`/`PathNode`/`Goal`/
  `MovementPlan(Compiler)`/`MovementPathExecutor`/`MovementMode`/顶层 `MovementType`/`PathExecutor`/
  `TunnelPlanner`/`TunnelObstaclePolicy`/`TunnelPlan`），保留 `MovementHelper` + `FootCellRuleCheck`；
  删除依据 = **按路径分析的零活引用**（不是"看着像死"）；顺带删掉 `MovementHelper.cost` 与残留 import。
- **K-5 已收口（D-172）**：`POSTCONDITION_FAILED` **不删**（§5.3 契约要求），改为**给它生产者** ——
  归属规则收敛为纯函数 `PathSessionStatus.classify`，把原先被吞掉的 `*_SETTLING_TIMEOUT`/`*_OVERSHOT_*`
  归入"后置条件未满足"；并新增自检 `session_status_no_dead_value`（分类表 + 覆盖率，
  除 RUNNING/COMPLETED 外每个枚举值都必须有生产者 ⇒ 以后再加死值会红）。

**仍未动的余项**：Job 细粒度 `safeToCancel` 聚合（待频率数据）、阶段 2 模组浅测（§7）、
§7 的其他方向候选。

**§6.13 第四次电池（2026-09-13 晚）**：26 项 **22/23**，唯一失败 = `transfer` 步的 `end_to_end`
（间歇：同计划 10:09 / 11:06 两次 5 tick 走完，这次一格没动磨满段预算）。
已排除夹具/重生成/菜单/强制停；已补**失败终态诊断** `[R4 Session] segment_stall`（D-174），
**遥控器候选已被用户否证**（没用过遥控器）⇒ 改为"链路计数"诊断（D-174 附注一）：
段卡死时同时打出 `entityTicksInSegment` / `travelCallsInSegment` + 输入串，
可判定"实体没 tick / tick 了但 travel 没进 / 物理跑了但没位移 / 有人每 tick 清零输入"。
**待复现**：独立跑 `alice:transfer_check` 约 15 次**全 PASS** ⇒ 该失败只在**电池语境**出现；
电池内已排除并发驱动（决策层 `trigger_skipped reason=suspended`、无 Job 活动、
无 `controller_stop_movement`）⇒ 待电池复现 + 新诊断读数。
**新开账（J 级，未修）**：**常驻 Job 不感知"bot 被传送离开作业范围"** ——
实测 `RegionLumberJob` 在转移场景（z≈404，目标树在 198 格外）一路搭了 12 格圆石再拆回；
触发条件 = "跑测试夹具时后台挂着常驻 Job"（本项目最常用操作）。
另登记潜在设计洞（非本次病因）：任务驱动与手动遥控无互斥。
K-5 新用例 `session_status_no_dead_value=PASS` ✓（能力闸门 11 项全 PASS）。
另：D-173 补删 `pathing/movement/` 14 文件 + 一个被 `.gitignore` 藏住的 `.backup` 残留。

④ 然后回到总账：**基-6 多 bot 并行（差异③，建议单独立项）** / 基-7 余项（K-3 `safeToCancel`、K-4 谓词不统一）。

## §7 方向候选（**2026-09-12 已裁定：基层优先，模组适配只做浅测**）

**用户裁定（D-147）**：不要急于深入模组适配；挑**两个模组**浅测；**先把基层补齐**。新路线：
1. **阶段 1 基层收口**：
   - ✅ **基-2 决策层进回归电池**（D-149，已实现已编译，待客户端跑电池）
   - ✅ **基-3 S4 事件层**（D-150，`TOOL_LOW`/`STUCK` 阈值 + 滞回；**2026-09-12 20:40 四例全 PASS → `WINDOWS_CLIENT`**）
   - 🔄 **基-1 可回收性不变式**（项目差异①）：**第一步已完成并实测（D-151 + 附注一）** = 可回收性真的被算出来 +
     依据可审计 + 真实计划分布（12 会话、10 种 Movement、distinctLevels=2）+ 寻路回归 13/13 无退化 +
     自检四例（含"校验是活的"负例）。
     **第二步已实施（D-152）**：`RecoverabilityFacts` 逐边事实（穿搜索到执行期）+ `RecoverabilityPolicy`
     策略表（`FALL>=PATH_REVERSIBLE`）⇒ **不带返回守卫事实的 FALL 边会被真的拒绝**（本机实测 `REFUSED`）；
     自检加 `fall_without_fact_refused` 负例。**待客户端复测**（自检 + `pathing_regression` 的 `fall_course`）。
     仍未使用：`SAFE_EXIT_REQUIRED`/`EMERGENCY_EXIT_REQUIRED`（无产出者，策略表不写死）。
     **客户端自检 6/6 PASS**（含两个负例）；**待补**：`pathing_regression` 真实 FALL 边（事实穿搜索链路）。
     ✅ **第二条轴已落地（D-153，用户批准）**：*残留 = 可回收性失败* —— `RestoreScopeTask` 对账后
     仍剩我方方块 ⇒ 计数 + `[Recover] RESIDUE` + `RESIDUE` 事件（统一出口 `DecisionEvents`，自检期只记录不通知）；
     `PathingRegressionTask` 判分后自清场（**账本精准回收**，见下）。
     ⚠️ 首版清场实现有 bug（重跑场景地形 ⇒ 把站在区域里的 bot 埋了，所有任务一启动就死于窒息）——
     已修为"只回收我方 TEMP 方块、不碰地形"（D-153 附注一）。待客户端复测：
     `cleanup=ledger 剩余=0` 且不再出现 `[Recover] RESIDUE`。
     ✅ **客户端全绿（2026-09-12 22:59）**：真实 FALL 边带 `pillar_return_guard_passed` 事实（多会话），
     回归 13/13 + 10 种 Movement 全覆盖、`PathingRegressionTask terminal=COMPLETED`；
     `cleanup=ledger … 剩余=0`、`residues=0`、零 `RESIDUE` 事件 ⇒ **两条轴都验证完毕**。
     **未做**：残留的自动补救（回收 UNREACHABLE 时挖开/搭桥取回）—— 碰 D-076 红线，属能力扩展；
     `SAFE_EXIT_REQUIRED`/`EMERGENCY_EXIT_REQUIRED` 仍无产出者（策略表不写死）
   - ✅ **基-4 决策 trace 落盘 + 跨重启语义**（D-154 + 附注一，**客户端全绿 + 真实跨重启验证**）：`DecisionTrace`（JSONL +
     内存尾 + 超限轮转 + 写失败不影响决策）、`DecisionState`（登记"重启会丢的未决请示/当前任务"，
     启动时**只报一次**）；裁定：请示作废但必须报出 / 任务不自动续做但如实汇报 / 不做假恢复；
   - ✅ **基-5 LLM 上抛契约**（D-155 + 附注一，**客户端全绿**）：J-4 四个 Job 覆写 `failureReport()`、
     J-6 `MineProductFilter`（目标驱动 + 标签族，尊重 `productTag`）、J-7 结构化拒绝回读（进 prompt + 汇报 +
     接受后清除）；电池第 19 步 `llm_contract`；
   - ✅ **基-9 工具与耐久管理（第一批，D-156 + 附注一/二：`WINDOWS_CLIENT` 全绿）**：`ToolSupply`（事实 + 安全搬运）、
     `ToolMaintenanceTask`（`already_ok`/`promoted_from_main`/`worn_no_spare`/`no_tool`）、
     决策动作 `maintain_tool`、工具事实进 prompt/汇报、`TOOL_LOW` 文案可行动；电池第 20 步 `tool_supply`。
     **未做**：工具来源（合成/取材料 = S6）、"耐久不足以完成计划工作量时提前拒绝"；
   - ✅ **基-8 授权登记缺口**（D-157 三附注）：G8 能力闸门（含"保护区字段又变装饰"真问题修复）、
     A9 登记表断档补齐、G3 外来破坏留痕（双向自检）、G5 容器写入留痕（NBT 往返断言）；
   - 🔄 **基-7 内核残余（第二批 = K-2 迁移，D-159）**：`TransferTask` 行走 + `/alice path` 诊断
     迁移到新内核 ⇒ **生产/开发路径上已无 legacy 引用**；余项：batch 3 删除死代码
     （`TunnelPlanner`/`LocalPathPlanner`/`HybridPathPlanner`/`PathPlanners`）+ `BotSelftest` 归属；
     **K-3 已实施（D-166 + 附注一）**：8 个执行器按 Baritone 承诺点声明 `safeToCancel` + 7 个寻路任务透传 +
`stopTask` 安全点延后（上限 20 tick，超时强停计数）+ 生存打断不安全计数 + **L2 空中门** + `/alice stop-task`；
**确定性夹具**见 D-166 附注二（`alice:k3_stop_check`：右键 DEFER / Shift+右键 FORCED；**不进电池**见 D-169）。
     **K-4 已实施（D-167，`COMPILES`，待客户端）**："可站"谓词 6 处复制 → **唯一定义**
     `MovementHelper.canStandCentered`（8 个调用点，纯重构；2 处写入类例外在代码处写明理由）；
     目标准入**只测量不硬拒**（遥测进 `bot_report` 的"目标准入（K-4 累计）"行 +
     电池 SUMMARY 自断言 `K4=OK/VIOLATION`）——**待电池实测决定**要不要引 `GOAL_NOT_STANDABLE` 硬拒。
     **K-4 真实运行数据（`SERVER_TESTED`）**：累计行只有写入类例外（89 / 51），**真异常 0 次**；
     完整电池的 `K4=OK` 仍待一次跑完的电池。
     **D-168 夹具测量修复**：`mine_regression` 的 `dropsLeft` 原先数"盒内全部掉落物"⇒ 世界残留假失败
     （run1 两次 FAIL 均只因 `dropsLeft=1`；run2 手动清残留后 PASS）。改为**基线 UUID 增量**
     （残留报 `foreignDrops=` 不计入）+ `mine_course`/`floating_course`/`chain_mine_course` 三个场景函数
     补 `kill @e[type=item,…]`。**新规则**：凡夹具断言涉及世界掉落物或背包净增量，
     必须"场景清实体"或"取基线"至少一条（同类风险：`ScaffoldLifecycleTask` 的 `itemsOnGround`，
     目前靠其场景已有 `kill` 兜住）。
**K-4 已收口（第四轮实测）**：完整电池 `K4=OK(真异常 0 / 写入类例外 88)` ⇒ 不引硬拒，
删临时告警、保留计数与电池自断言（D-167 附注一）。
**新增资源自检**：`tools/check-item-models.sh`（D-170；空模型/死贴图引用是"只有客户端日志能看出来"的缺陷类）。
**余项**：Job 细粒度承诺点聚合（待频率数据）、**K-5 遗留枚举死值**、**K-3 自检复测**（DEFER + FORCED）。
   - （第一批 = K-1，D-158）：`PlanningStatus.PARTIAL` + best-so-far 前缀
     + `PathRetryRunner` 真的消费（先走前缀再重规划）；**余项**：K-2 legacy 双内核活引用
     （`SurfacePathfinder` 7+ 处）、K-3 `safeToCancel` 全缺、K-4 谓词不统一、K-5 遗留枚举死值；
   - ⏭ 之后：**基-6 多 bot 并行（差异③，建议单独立项）**；
2. **阶段 2 真实数据浅测**：模组集合 = **Create + Extended Crafting + Mekanism + Thermal Expansion**
   （D-148：后两者是"配方打架"对照 ⇒ 压测**多路线/冲突**理解）；只验"运行时导出 + P1 读得懂多少"，
   产出**适配器清单**与**冲突报告**（⚠ 矿物词典=标签：成品材料统一、中间物与机器配方不统一 —— 见 D-148）；
3. **阶段 3 能力扩展**：S6 Craft/Smelt → 机器适配器（按清单挑 1–2 个）→ 偏好规则；多 bot 并行另立项。

（原文候选，保留备查）

| 方向 | 内容 | 依据（为什么现在） | 代价 / 风险 |
|---|---|---|---|
| **① 安全底座小批次** | `S-1 维生出口` + `S-2/K 未加载区块门控` + `S-3 删重复调用` + `S-4 接线` | J8 让 bot **常驻**（`RegionLumberJob.java:25-29`）⇒ 暴露从"任务瞬时"变"持续在线"；S-1 是唯一"拒绝对没有出口"的反例；S-2 同时是内核审计的 MISSING-IN-ALICE 高项、也是 D-004/D-076 判据的前提 | 4 项都是小改动；S-2 需定"硬拒"口径（复核已给答案）；可服务端 + 客户端双向验证 |
| **② 决策层接入（差异②）** | 先补契约（`J-1` `terminalReason` 进 `TaskOutcome`、`J-3` `botUuid`、`J-2` `GoalSpec→Job` 统一入口），再做 LLM 目标级循环（状态快照 + 动作词汇表 + 触发节奏） | 这是项目**立项目标本体**（LLM 只做目标级决策），J1–J8 造的地基正是为它；`J-1` 不做则 LLM 连"任务为什么结束"都拿不到 | 大弧线（多轮）；需先与用户定"LLM 的输入契约/动作集/触发时机"，避免做成第 4 个死抽象 |
| **③ 第二个高级任务** | 候选：**农耕补种**（成熟作物=新 `CandidateSource`；破坏一格=现成 `MineTask`；补种放置+账本 `KEEP`=J8 已跑通范式） | 证明 Job 层**不是伐木专用**（J5 只证明了骨架复用）；农耕**不需要新授权入口**、子动作全现成 | 业务价值取决于是否要做"农牧"方向；建造/拆除**最后做**（放置为主目标，踩 D-076，且 `RoadBuildTask` 的 `BULK_EDIT` 仍标"无预算"） |
| **④ 验证债/文档债清零** | `V-1` 矩阵 16 处待测逐条改判 + `V-2` 未覆盖分支补场景 + `D-1..D-3` 报告勘误 | 矩阵与审计报告是"下一个人读的第一手材料"，过期即误导（本轮已吃到一次：状态文档中段） | 纯卫生工作，可与其他方向并行；不产生新能力 |
| **⑤ 内核完备性** | `K-1` best-so-far 前缀 + `K-2` legacy 双内核收口 | `K-2` 是 R7 异步搜索与"唯一入口"的未解债务；`K-1` 对长距离/挖矿重试有直接收益 | 不直接服务业务；`K-1` 需同时定 `PARTIAL` 的对外语义（不得 = `UNREACHABLE`） |

**用户裁定（2026-09-12）**：**① → ②**（先安全底座小批次，再开决策层接入）。
**① 已完成并全部 `WINDOWS_CLIENT`**（S-1/S-2/S-3/S-4 + 起点脱困 D-133，见 D-132 附注三收口表）；
入口 `alice:survival_exit_check`、`alice:chunk_guard_check`、`alice:fluid_mine_check`（均零参数右键）。
→ **② 决策层接入进行中（三条通道骨架）**：S0 契约与管道（D-134/D-135）→ **S1 事实层（D-136）** →
**S2 选择层（D-139，`WINDOWS_CLIENT`）** → **S3 请示层服务端契约（D-140，`WINDOWS_CLIENT`）** →
下一步 **S3b 客户端弹窗** → **S3.5 收集归属 + 被动闸门（D-138 裁定）** → S4 事件层 → S5 知识层 → S6 执行层。
（原文）**当前进行中 = ② 决策层接入**（先补 §3 的 J-1/J-2/J-3 契约）。

**整理者的原建议**：**① → ②**。
先花 1–2 天把安全底座那 4 项小改做完（其中 S-2 与内核 K 项是同一件事，一次改动吃两份收益；
S-1 因常驻任务而真实化），再开 **②决策层接入** 这条真正的大弧线 —— 它才是"LLM 做目标级决策"
立项目标的核心，而 J1–J8 刚把它的地基（候选源/策略/trace/失败码/账本）铺完。
**③农耕** 可以作为 ② 的第一个"LLM 可选动作"来落，天然证明决策缝可用；
**④** 建议穿插着做（每轮收尾顺手清 2–3 条）。

**§6.14 崩溃与冻结（2026-09-13 晚，两件都已有实锤）**

- **服务端崩溃（已修，D-175）**：`crash-2026-09-13_12.07.23` —— `MineTask.tickRestore` NPE
  （`restoreTask` 已置 null 而 `phase` 仍是 RESTORE），由 `MineRegressionTask` 的 SCOPE_REOPEN
  用例"终态后又 tick 内层任务"触发，**打死服务端 tick 循环**。
  修：任务终态闩锁 + 防御守卫 + 夹具不再 tick 终态任务 + **电池 try/catch 隔离**（单步异常 → FAIL + 完整栈）。
  同类隐患（09-06 崩过一次同型 NPE）：19 处"字段置 null + `.tick()` 无守卫"，待逐个上闩锁。
- **bot 物理冻结（已定性，D-176）**：`segmentTicks=121 / entityTicksInSegment=0 / travelCallsInSegment=0`
  ⇒ **假人实体整段没被 tick**（输入 forward=1.00、onGround、空地、速度为 0）。
  这解释了 `exec_floating` 超时、`exec_chain` 收不到掉落、`restore FAILED remaining=1`、
  以及此前 transfer `end_to_end` 的间歇一格不动 —— **同一个病因**。
  已加 `[Bot] entity_tick_missing` 看门狗（含 removed/区块/玩家表/连接/task 现场）；
  **待复现取现场**后定修法（可能需要在 `BotManager` 侧补漏 tick 兜底驱动）。

**§6.15 2026-09-13 收尾复测（客户端 12:42）**：电池 **(26/26) ticks=3301 → PASS**、
`K4=OK(真异常 0 / 写入类例外 83)`、**22/22 终态步 `idempotent=true`**、
四个 exec 用例 `foreignOk=true(另有残留1件不计入)`（D-168 分支首次全量实测通过）、
`entity_tick_missing` / `segment_stall` / 异常均为 **0**。

**仍未闭环（按优先级）**：
1. **P0 假人物理冻结**（D-176 / 附注一）：**字节码实证** —— `ServerPlayer.doTick()` 的唯一调用者是
   `ServerGamePacketListenerImpl`（内部调父类 `tick()`）⇒ 假人物理**挂在"假连接被 tick"这条链上**，
   与 Alice 的全局 `ServerTickEvent.END` **不同源**。看门狗已带 `entityTicking=` 与新加的 `connTicks=`
   （`FakeConnection.tick()` 计数）：`connTicks` 不动 ⇒ 断在连接；在涨而实体不动 ⇒ 断在实体侧。
   **待复现取现场**后定修法（候选：让任务侧兜底驱动、或保证假连接进连接表）。
2. **传送感知**（D-180，用户要求只加报告）：`BotPlayer` 覆写两个 `teleportTo` ⇒ 计数 + from/to/tick +
   `[Bot] teleported` 日志 + 事件环 `TELEPORT` + `bot_report` 一行。
   **日志已实测（13:05，5 条）**；附注一修掉"原地复位"噪声（只有位移 ≥1 格才记日志/入环）。
   **仍待验证**：`bot_report` 的"传送（位移）"行（本轮没跑过 `bot_report`）。
2. **终态幂等契约推广**：19 处直接 tick 点里 17 处是夹具（已被电池隔离 + `idempotent=` 点名兜住），
   推广属欠账（同型 NPE 已出现两次：09-06 / 09-13）。
3. ~~两处设计洞~~ → **② 已修（A 项，D-179）**：`MiningTuning.gainHorizontallyReachable` 唯一定义 +
   `MineTask`/`CollectDropsTask` 入口守卫 + `RegionLumberJob` 的 `nearRegion()` 漂移守卫
   （区外挂起、每 100 tick 告警、400 tick 后如实失败 `outside_region`）；
   断言 `capability_gate → gain_requires_proximity`。**客户端已复测（`WINDOWS_CLIENT`）**：
   `gain_requires_proximity=PASS(near=true farRefused=true reach=4.5)`；
   真起 region_lumber 后把 bot 传走 ⇒ `[Job] region_drifted … ⇒ 挂起作业`，其后**无任何作业/写入**。
   附注一修正了**我方**一处缺陷：`driftTicks` 原按"巡查次数"计（400×20=8000 tick ≈ 6.7 分钟，
   与文档声称的 20 秒不符）⇒ 已改为真实 tick 计数 + 新增 `region_returned` 恢复日志。
   **未验证**：`outside_region` 终态（需连续漂移 20 秒）。
   ① 任务驱动与**手动遥控**无互斥（`BotInputPacket` 静默覆盖任务输入）—— **仍未修**（需先定语义）。
4. `pathing/` 包语义（`MovementHelper`/`FootCellRuleCheck` 是否迁入 `core`）。

**§6.16 阶段 2（模组浅测）开工（D-181）**：判据与工具链已就位。
- 客户端现状：`mods/` 275 个 jar，**四个目标模组（Create / Extended Crafting / Mekanism / Thermal）都未安装**
  （由用户按需安装，符合"开发期依赖清零"的裁定）。
- 已有链：`/alice recipes [file]` → `config/alice-recipes.json` → `tools/recipe-graph.py`（P1 逆推）。
- 本轮补：`RecipeDump` 增 `skippedTypes` 直方图（"读不懂的是谁"= 适配器候选证据）+
  新 `tools/recipe-readability.py`（只读离线审计：读得懂/读不懂、适配器候选、配方打架/跨模组同产出；
  `--selftest` PASS）。
- **模组已由 AI 代装（D-182）**：7 个 jar（create 6.0.8 / extendedcrafting 6.0.10 / cucumber 7.0.16 /
  mekanism 10.4.16.80 / thermal_expansion 11.0.1.29 / thermal_foundation 11.0.6.70 / cofh_core 11.0.2.56），
  sha1 全 OK；`flywheel`+`ponder` 内嵌在 create、`thermal_core` 内嵌在 thermal_foundation（均已 `mandatory=true`）
  ⇒ 依赖闭合；Forge 47.4.10 与已装 JEI 均满足；无重复 modId（客户端真实 jar 12 个）⇒ 读不懂类型可干净归因。
- **顺带修掉**：同步脚本在客户端 `mods/` 留 271 份 `.bak`（241 MB）不清理 ⇒ 已加轮转（keep=2）并清理（287 MB → 50 MB）。
- **首轮导出已完成并出报告**（`docs/STAGE2_MODS_READABILITY.md`，D-183）：
  可读 **772 / ≈5293（14.6%）**，可读类型只有 5 种；**抓到真问题** —— `STATION_BY_TYPE` 原键是
  **序列化器 id**（`crafting_shaped`…）而运行时是**类型 id**（`minecraft:crafting`）⇒ 2136 条工作台配方 +
  31 条锻造配方被误跳；已改用类型 id（预测可读率 → **2939 / 55.5%**）。
  适配器候选（只列不写）：Mekanism 1171/26 类 > Thermal 652/30 类 > Create 506/15 类 > Extended Crafting 25/4 类。
  冲突检测当前**不可信**（模组 crafting 未进可读集 ⇒ 跨模组同产出=0 是假象）。
- **最终复测完成（D-183 附注一）**：可读 **2923 / ≈5293（55.2%）**，原版体系**已完整覆盖**
  （只剩 16 条"无产出"特殊配方）；标签输入 5 → **262**；产出可读 minecraft 1253 / create 940 /
  thermal 333 / mekanism 318 / extendedcrafting 79。
  **配方打架**：**575** 个产出物多路线、**48** 个跨模组（copper_ingot 11 条、iron/gold 10 条…）；
  另发现**同材料多 id 由 `#forge:*` 归一**（101 个跨模组标签、涉及 1029 物品）⇒ 知识层须按标签归一。
  适配器候选排序：Mekanism 1171/26 类 > Thermal 652/30 类 > Create 506/15 类 > EC 25/4 类（**只列不写**）。
  报告：`docs/STAGE2_MODS_READABILITY.md`。**阶段 2 目标达成**。
  诚实边界：机器专属类型互斗（crushing vs pulverizer）在跳过集里，当前看不到。

**§6.17 阶段 3-A 启动（2026-09-13，用户同意方向）**：计划见 `docs/STAGE3A_CRAFT_PLAN.md`。
阶梯：**A1 只读配方查询原语**（本轮唯一增量）→ A2 随身 2×2 合成（复用 `MenuSession` 点击原语）
→ A3 工作台 3×3（优先用现成；放置才涉及世界写入 ⇒ 授权 + 预算 + 用完即拆）
→ A4 熔炉（燃料/时间/清理）→ A5 决策层接线（`GoalAction.Craft`，LLM 只从菜单选）。
**本轮不做**：机器适配器（D-183 排序）、跨模组材料归一。
A1 判据（零参数 `alice:craft_check`）：正例给工作站+材料清单，缺料给 `missing=`，无配方给 `no_recipe`，
**纯只读**（不改背包/世界）。

**§6.18 阶段 3-A 阶梯推进（2026-09-13，A3b 落盘）**：
- **A1 / A2 / A3 客户端 PASS**（`alice:craft_check` 7/7、`alice:craft_action_check` 7/7、
  `alice:craft_table_check` 7/7，含 `no_world_write` 零写入硬断言）。
- **A3b 已实施、待客户端**（D-190）：`task/craft/StationPlacement`（授权入口 **A12**，
  `WriteReason.CRAFT_STATION_PLACE`，账本记 `TEMP` ⇒ 受"建拆同权"约束）+ 夹具
  `alice:craft_station_check` + 场景 `alice_test:craft_station_course`（**故意没有工作台**）+
  电池 26 → **27 项**（`craft_station` 步）。
  判据：`station_placed`（世界事实）/ `write_accounted`（账本 TEMP）/ `placed_table_craft`（用自己放的台合出熔炉）/
  `teardown_clean`（**方块回空气 + pending=0**）。
- **A3b 首轮客户端实测失败并已修**（D-190 附注一）：`holding_station_item=PASS mainHand=crafting_table`
  但账本记的是 `place … minecraft:cobblestone←minecraft:air [TEMP CRAFT_STATION_PLACE]` ⇒
  根因 = 用了"放一个一次性方块"的原语去放工作台（它按白名单挑中了背包的圆石）；
  且夹具失败时未清理，圆石留在世界里。修：`placeAt` **按语义分成两个重载**（一次性方块 / 指定方块，
  共用实现体）+ `findSlotForBlock` + `NO_ITEM`；夹具加 **`CLEANUP` 失败清理相位** +
  起手 `dropStale` 销掉上一轮幽灵账目。**待复测**。
- **新登记的能力缺口**：**"把物品从主背包搬到快捷栏"未实现**（Baritone `InventoryBehavior.attemptToPutOnHotbar`）。
  现状：`findSlotForBlock` 只查快捷栏，找不到就如实报 `station_not_in_hotbar`（不假装能做）。
  将来任何"必须手持某物"的动作（放机器、喂熔炉、装桶）都会撞上这一条 ⇒ 要做就做成一个独立原语。
- **未做**（仍在本阶梯上）：A4 熔炉加工（燃料/时间/取出、失败不留半成品）、
  A5 决策层接线（`GoalAction.Craft` + 缺料多路事实进候选菜单）。

**§6.19 阶段 3-A / S1 合成工作站可切换（2026-09-13，D-192）**：
- 已实施（**待客户端**）：`GridDiscovery`（通用网格发现，原版容器判据）+ `CraftStation`（站点描述符 + 玩家切换，
  `auto` 只复刻现状）+ `alice:craft_grid_probe`（零参数只读探针）+ `/alice craft station` + `bot_report` 一行；
  场景 `alice_test:craft_tab_course` / 诊断 `alice_test:craft_tab_snapshot`。
- **已装模组**（固定客户端）：精妙存储 `1.4.86.2131`、精妙背包 `3.26.3.2157`、精妙核心 `1.5.1.2335`、
  Refined Storage `1.12.4`（用户裁定保留，暂不做兼容）。
- **待实测**：① 精妙容器菜单的真实槽位表（9 格是否出现在 `-100,-100`、`isActive` 真值）；
  ② `grid_addressable_without_tab` 是否 `true`（= "点开页签与不点开没区别"，用户裁定的判据）；
  ③ 开/关菜单是否改动容器 NBT（`craft_tab_snapshot` 前后差分）。
- **下一步（S1-5 / L2）**：按实测给 `upgradetab` 定 `take/source`；独立"装配"任务把合成升级点进升级槽
  （新 `WriteReason.STATION_PROVISION` + A 表条目 + 建拆同权的复位纪律）。

**§6.20 D-192 复测（2026-09-13 17:04，客户端）**：
- ✅ 崩溃已修（`InventoryMenu(unregistered(…))`）；✅ 通用发现器在随身菜单上**逐项复现 D-163 布局**（2×2@[1,2,3,4]、result=0、inv=[9..44]）；
  ✅ 站点切换生效（`upgradetab` → `StorageContainerMenu(sophisticatedstorage:storage) slots=63`）；✅ 落地等待 `settle_ticks=2`；
  ✅ 未装升级时如实 `no_grid`。
- ❌ **回归已修**：探针不匹配自检命名 ⇒ 终态招来 LLM、自动起了两次 `region_lumber`
  （D-192 附注二）⇒ 约定扩展到 `*ProbeTask` + 探针显式 `isSelfCheck()=true`。
- 📌 **新事实**：精妙菜单服务端槽位坐标全是 `@0,0` ⇒ **x/y 不可作"是否显示"的判据**，只能用容器身份 + `isActive`。
- ⏳ **仍待证**：装上升级后 `grid_addressable_without_tab` 的真值（L3 是否要发包）。

**§6.21 D-192 第三轮复测（2026-09-13 17:08–17:10，客户端）**：**顺序问题，不是缺陷**。
- ✅ **自检修复已验证**：本轮 `latest.log` 里 `decision_request trigger=terminal:CraftGridProbeTask` **0 次**
  （上一轮 2 次、自动起了两次 `region_lumber`）⇒ `*ProbeTask` 约定 + 显式覆写生效。
- ✅ 崩溃修好（`InventoryMenu(unregistered(…))`）、切换生效（`upgradetab@46,64,306` → `StorageContainerMenu slots=63`）。
- 🔎 `no_grid` 是**当时状态下的正确答案**：17:09:58 那一轮菜单只有 63 槽
  （27 存储 + 36 玩家 = **连升级槽都没有**）⇒ 那一刻升级**还没装**。
  而**关服存档**（17:10:37，`region/r.0.0.mca` chunk 2,19 解析）显示：
  `upgradeInventory{Size:1, Items:[crafting_upgrade]}`、`numberOfUpgradeSlots:1`、
  `numberOfInventorySlots:27` ⇒ **升级现在装着**（木头箱子有 1 个升级槽，场景选型没问题）。
  ⇒ 时序是：**探针 → 装升级 → 关游戏**。
- ⏭ **只差重跑一次探针**（jar 未变，**不用重启**）：应看到 `slots≈74`（27+1 升级槽+10 合成+36 玩家）+
  `discover=OK grid=3x3 …` + `grid_addressable_without_tab=true`。

**§6.22 D-192 第四轮：**升级确实在里面，服务端菜单却不认**（真矛盾，不是时序）**
- 精确时间线（`latest.log` + `stat`）：`17:13:32` 跑场景（**重建箱子**）→ `17:13:35/17:13:50` 两次"暂停并保存"（装升级）→
  **`region/r.0.0.mca` 写入时刻 `17:13:43.95`，解析出 `x:46 y:64 z:306 id:sophisticatedstorage:chest`、
  `numberOfUpgradeSlots:1`、`upgradeInventory:[sophisticatedstorage:crafting_upgrade]`** → `17:13:57` 探针仍 `63 槽、无升级槽、无网格`。
  ⇒ **升级比探针早 14 秒**，所以**不是时序问题**：方块实体有升级槽，服务端菜单里连升级槽都没有。
- 结论：需要**现场内省**才能定位（菜单手里的包装器 vs 方块实体手里的包装器）。已加**只读反射诊断** `CraftMenuIntrospection`
  （逐项 try/catch、只调 getter/读字段、只认接口形态），探针 SUMMARY 会多出 `diag_menu_wrapper=` / `diag_be_wrapper=` /
  `diag_menu_upgradeContainers=` 三行。
- **待测**：重启后**不要重跑场景**（否则升级又被清掉）→ `/alice craft station upgradetab` → 右键探针 → 看 `diag_*`。
  另请**自己打开那个箱子**看一眼：右侧有没有"合成"页签（这条客户端事实能立刻分开"模组不给"与"我们开法不同"）。

**§6.23 D-192 第五轮：根因 = 槽位不在 `menu.slots` 里**
- 用户实验（页签关/开各一次）⇒ 两次结果相同 ⇒ **页签状态不影响菜单内容**（符合"没区别就不用管"）。
- 反射诊断：`upgradeHandler=[slots=1]`、`upgradeContainers=1 [0]`（容器存在）但 `menu.slots=63`（27+36）⇒
  上游 `addUpgradeSlot` **只设 `slot.index` 并收进自己的列表，从不 `addSlot`**（字节码核对）。
- 已修：`collectSlots`（反射收全可达槽位）+ **点击地址改用 `slot.index`**；探针表新增 `#index` 与 `*` 标记。
  对原版站点行为不变（`index` == 位置）。
- **待测**：重启后（不要重跑场景）跑探针 ⇒ 期望 `discover=OK grid=3x3 slots=[63…]`、
  `extra_slots=10`、`grid_addressable_without_tab=true`。

**§6.24 D-192 第六轮：槽位表露出真身 + 发现器四路径重写**
- 17:22 探针：`slots(74)`；`*#63` 升级槽、`*#64..72` 九个网格格（**@-100,-100**）、`*#73` 结果槽 ⇒
  证明"页签槽位在 `menu.slots` 之外"的推断完全正确；但 `matrix=-(0)` ⇒ 9 格并不挂在 `CraftingContainer` 上。
- 17:19 截图作证：玩家视角该箱子右侧"合成"页签 + 3×3 + 结果槽正常 ⇒ 模组本身没问题。
- 发现器重写为**四条证据路径**（身份 → 结果槽 `craftSlots` → 上游 `getRecipeSlots` 自述 → 内容镜像唯一性），
  并输出 `matrixBy=` / `gridBy=`；如实拒绝码新增 `grid_slots_unresolved`。
- **待测**：`gridBy` 取哪条、`grid=3x3 slots=[64..72] result=73`；点击可用性留第二步（S1-5）。

**§6.25 D-192 第七轮：`matrix=-(0)` 真因 = vanilla 成员在生产环境是 SRG 名**
- 17:26 探针：`menu_slots=74` ✓、结果槽认出 ✓，但按名字反射 `craftSlots` 拿不到矩阵。
- 修：按**类型**找矩阵（结果槽字段/无参方法），并给 `scan` 增加"按类型收集可达 `CraftingContainer`"兜底；
  诊断读物品名改**编译期调用**。`note` 增 `matrixCandidates=`。
- **待测**：`matrixBy=resultSlotFieldByType`、`gridBy=?`、`grid=3x3 slots=[64..72] result=73`。

**§6.26 D-192 第八轮：探针入电池（串联三连）+ SKIP 语义**（2026-09-13）
- 电池 27 → **30 项**，新增三步（一步右键全跑）：
  - `craft_probe_inventory`：`station=inventory` + **期望 2×2 硬断言**（回归：通用发现器在原版随身菜单上不变）；
  - `craft_probe_table`：`station=table` + **期望 3×3 硬断言**（回归：原版工作台；**零模组依赖**）；
  - `craft_probe_upgradetab`：模组站点；**模组不在 ⇒ `station_opened=false` ⇒ SKIP（不判红）**；
    本轮它是"**探测路径**跑通 + 事实如实 + 零写入"的 PASS（升级未装时如实 `grid_found=false`）——
    **3×3 硬断言要等 L2 装配层落地**（届时换 `new CraftGridProbeTask(bot, observer, 3, 3)`）。
- 电池基础设施：`Step` 增 `skipWhen`（+ `stepSkippable` 工厂）、SUMMARY **SKIP 计入绿**（会写明 `SKIP=n`）、
  `endStep()` **复位站点选择**（自检串联不许把"我选了哪个站"泄漏给下一步）。
- 探针新增期望断言（`grid_found_expected` / `grid_matches_expected`）供回归用；独立物品入口仍是纯事实探针。

**§6.27 D-193（S1-5a）：执行接入发现器**（2026-09-13，待客户端复测）
- `InventoryCraft` 4 参入口 = 发现驱动（`grid_unrecognized` 兜底，不回退常量）；`inventorySpec()` 删除。
- `TableCraft.craftWithMenu` 前置断言 = "菜单里认得出 ≥3×3 网格"（取代 `instanceof CraftingMenu`，
  为模组站点打开门）；`tableSpec()` 删除。
- **等价性证据**：探针已证明两种原版菜单的发现结果与旧常量逐项一致 ⇒ 由电池 `craft_action`/`craft_table`/`craft_station` 复测。
- **下一步 A（L2 装配层）**：bot 用菜单协议装/拆合成升级（独立 `WriteReason.STATION_PROVISION` + A 表 + 复位纪律）
  ⇒ 之后 `craft_probe_upgradetab` 升级成 3×3 硬断言。再往后的 C：模组站点真合成（摆 `#64..#72`、取 `#73`，
  并实测"Shift 右击将成品放入容器/玩家物品栏"开关与"材料来源=容器优先"两维）。

**§6.28 D-194（L2 装配层）**（2026-09-13，待客户端）
- 新写入理由 `WriteReason.STATION_PROVISION` + **A13**（容器写入维度 `consumeContainerWrite`，超限 REFUSED）。
- `task/craft/StationProvision`：装入/取回两个动作，**落点交给菜单**（QUICK_MOVE，不被接受时兜底读模组 `upgradeSlots`），
  **地址映射靠发现**（`container == player.getInventory() && getContainerSlot() == 下标`），绝不按公式猜；
  点击用 `menu.clicked`（不用 `MenuSession.click`，它会拒绝超出 `menu.slots` 的上游自管地址）。
- 夹具 `CraftStationProvisionCheckTask` + 入口 `alice:craft_station_provision_check`：
  前提（无网格）→ 装 → **关掉再开** → **能力验证 ≥3×3** → 取回 → 能力消失 + 物品回包；失败**回滚**。
- 电池 30 → **31 项**（模组不在/站点不在 ⇒ SKIP）；场景不再给玩家发升级。
- **未验证**：QUICK_MOVE 是否被上游接受、重开后 3×3 是否出现、取回后是否复原。**下一步 C**：模组站点真合成 + 两维语义实测。

**§6.29 D-195（C：模组站点真合成）**（2026-09-13，待客户端）
- **A 已客户端验证通过**：`provision_verified=true`（QUICK_MOVE 被上游接受：`fromSlot=54`）、
  `item_moved_into_container=true`、`deprovision_verified=true`（`fromAddress=63` 取回）、`item_returned=true`、
  `no_block_writes=true`、`verdict=PASS`。
- 用户确认场景："装一次 → 一直用 → 直到让它拆"（装配独立成层；夹具的"装→验→拆"只是自检）。
- C 新增：`CraftStationCraftCheckTask` + `alice:craft_station_craft_check`；
  `CraftStation.UPGRADE_TAB.provisionUpgrade`（数据驱动"该装什么"）；电池 31 → **32 项**。
- **待测**：`materials_consumed=8`、`product_produced=1`、产物去向（`product_in_player`/`product_in_container`）、
  开关值 `shift_click_into_storage`、以及"原语假设是否被打脸"（`primitive_assumption_mismatch`）。

**§6.30 D-195 附注一（C 首测失败 → 已修）**：`InventoryCraft.click` 用 `slot >= menu.slots.size()` 守卫，
把上游自管地址（64..72/73 ≥ 63）**静默拒绝** ⇒ 材料没动、产物 0，且失败码被报成 `missing_ingredient`（病名反向）。
修：守卫只拒负数 + `GridDiscovery.slotByAddress` + `placeGrid` 返回**区分过的**失败码 + 清理/找料改用发现表。
**待客户端复测**（jar `81fc53ec…`）。

**§6.31 D-195 附注二（C PASS + 产物口径修复）**：C 复测 PASS（真合成 8 圆石→1 熔炉、装/拆全绿）。
实测两维：**产物进容器**（`product_in_container=1`）、**不自动补料**（`grid_after_craft` 全空）；
原语因"数玩家背包"报 `result_not_taken` ⇒ 已把**产物计数口径**做成可注入（默认玩家背包；
`TableCraft` 用"玩家背包 + 菜单容器"）。**待查（假设非结论）**：开关读到 `false` 但产物仍进容器
⇒ 决定因素可能是**玩家侧**设置 `shift_click_open_tab`。**待复测**：32 项电池（确认口径改动无回归）。

**§6.32 32 项电池两个缺陷（已修，待复测）**：① 产物口径把结果槽**预览**算成产物 ⇒ A3/A3b 假失败
（`product+1 FAIL:result_not_taken`）⇒ 新增 `countLandedProduct`（排除结果槽与网格）；
② 偶发"服务端没开菜单"（`use_item_on result=SUCCESS` vs 成功时的 `CONSUME` + `menu_open_timeout`）⇒
失败如实打码（failure code / 当前菜单 / 重试次数）+ **重试一次**（冷却 10 tick、计时归零）。
jar `4217b7ec…`。

**§6.33 前提污染（已修，待复测）**：`setblock` 放同种方块**短路** ⇒ 箱子里的升级跨场景存活 ⇒
`craft_station_provision` 的"本来没有合成能力"前提被弄脏。修：场景先 `setblock … air` 再放箱子；
两个夹具都**自己清理前提**（provision 记 `premise_cleaned`，craft 重算数量基线）。
jar `2efb2d1e…`；**教训**："重建场景"不等于"状态干净"。

**§6.34 32 项电池第三轮：全绿 + 机制发现（已加固）**：`(32/32) ticks=3592 → PASS`、无 SKIP/FAIL/崩溃。
发现：**握着合成升级右键容器 ⇒ 物品自己装进去、GUI 不开**（`result=SUCCESS` vs 正常 `CONSUME`），
这解释了上一轮的 `menu_open_timeout`，也解释了本轮夹具为何要清理"残留升级"。
加固：开菜单前 `clearHeldUpgrade`（把选中槽换成空格）。
**阶段 3-A 工作站大块收口**。jar `e8082b1b…`。

**§6.35 D-196（A4 熔炉）**：新增 `FurnaceStation`（3 格容器 + `ContainerData` 类型字段 ⇒ 认炉子，零写死下标）
+ 夹具 `CraftFurnaceCheckTask` + 入口 `alice:craft_furnace_check` + 场景 `alice_test:furnace_course`；
电池 32 → **33 项**。判据=世界事实（stone+1 / cobble-1 / 炉内不留东西 / 零方块写入），超时把输入取回。
**待客户端**；**A4b（熔炉升级/菜单型炉子）**复用同一发现器。

**§6.36 D-197 电池分档管理**：三档（BASELINE 13 / MAIN 10 / EXTRA 10），默认 CORE=23 项；
配置唯一入口 `RegressionBatteryTask.CURATION`；说明书 `docs/BATTERY_CURATION.md`（含 6 条维护规则 + 历史表）；
入口 `alice:regression_battery`（CORE）与 `/alice battery core|full|list`；
**自校验防漂移**（漏登记/文档不一致直接判红）。顺带修 A4 的 `ContainerData` 下标映射与匿名类名。
jar `1aef94f3…`；**待客户端**：CORE 23 项跑通。

**§6.37 CORE 首跑 + 熔炉复位（已修，待复测）**：CORE `(23/23) ticks=2583`（FULL 3527 ⇒ −27%），
但自校验拿"裁剪后的步骤集"比归属表 ⇒ 把 10 个被跳过的 EXTRA 误判成 phantom ⇒ 假 FAIL（已修：比全量快照）。
用户提醒"熔炉会一直燃烧"属实（煤 1600tick vs 一次 200tick ⇒ ~1400tick 余焰，且取走燃料不会灭）⇒
夹具加 CLEANUP：取回炉内剩余物 + `setblock air`→`furnace` 复位方块（场景同款做法）。
jar `3312608d…`。

**§6.38 D-198（A4b 菜单型炉子）**：精妙"熔炼升级"= `sophisticatedstorage:smelting_upgrade`，页签是 3 格烹饪槽
+ **方法自述**（无 `ContainerData`）⇒ `FurnaceStation` 加第二条证据路径；站点模型加 `COOKING_TAB`
（容器 + 哪一种能力）；夹具复用 `CraftFurnaceCheckTask(upgradeTab=true)`；入口 `alice:craft_cooking_check`；
电池 33 → **34**（MAIN 11 ⇒ CORE 24），归属表与说明书同步。jar `2d7cf69e…`；**待客户端**。

**§6.39 D-198 附注一（A4b 首测失败→已修）**：装配成功但 `no_furnace_slots` —— 模组烹饪页签的 3 格**不在同一个容器**上。
修：`FurnaceStation` 加**路径 ③ 上游自述** `getCookingSlots()`（恰好 3 个 Slot）+ **`mayPlace` 行为探针**判定
输入/燃料/输出（结果槽两样都不收、燃料槽收煤不收圆石、输入槽收圆石），并打印 `by=`/`assignBy=`。jar `c94a2ee9…`。

**§6.40 D-198 附注二（A4b 第二轮实测：真因 = 自述者在宿主"下面一层"，已修）**（2026-09-13，客户端 19:02）
- **客户端事实**（`[FurnaceDiag]`，装配成功、菜单重开后的那一次发现）：
  - `menu=…sophisticatedstorage…StorageContainerMenu menuSlots=63`、`reachableObjects=167`；
  - `obj=CookingUpgradeContainer methods=[getSmeltingLogicContainer]` ⇒ **宿主看得见**；
  - 槽位表：`#63 (anon)/SimpleContainer containerSlot=0 item=1xsmelting_upgrade`（存储的升级槽，**未登记**）、
    `#64 SlotSuppliedHandler/… containerSlot=0 mayPlace[coal=false cobble=true]`（=输入）、
    `#65 SlotSuppliedHandler/… containerSlot=1 mayPlace[coal=true cobble=false]`（=燃料）、
    `#66 (anon)/… containerSlot=2 mayPlace[coal=false cobble=false]`（=输出）⇒ **3 格烹饪槽确实可达、形态完美**；
  - `discover=FAIL:no_furnace_slots` 且 **没有 `[Furnace] 认出炉子 by=…`** ⇒ 路径 ③ 的候选里**没有自述者**。
- **真因（字节码核对）**：`CookingUpgradeContainer.<init>` = `cookingLogicContainer = new CookingLogicContainer<>(player,
  supplyFromWrapper(w -> w.getCookingLogic()), slots::add)` —— 自述者（`CookingLogicContainer`：`getCookingSlots()` 恰好 3 格 +
  `getCookTimeTotal/getBurnTimeTotal/isCooking`）是宿主的一个**私有字段**，比 2 层遍历上限**深一层** ⇒
  **看见宿主、看不见自述者**（不是"没有这个对象"，也不是"格子数不对"）。修：候选集对**槽位宿主**
  （`GridDiscovery.Scan.owners()`，即产出过槽位的上游容器）**再展开一层字段**，并**自校验**采用
  （`getCookingSlots()` 必须给出恰好 3 个 `Slot`）；**不做全局加深**（免得把 `ServerLevel` 那类世界对象翻进去）。
- **判据顺序不变**：路径 ①（同一容器恰好 3 格）→ 路径 ③（自述 3 格）；输入/燃料/输出**仍由 `mayPlace` 行为判定**，
  不采信自述顺序；进度证据 = `ContainerData`（原版）或自述方法族（模组）。
- **动作路径补证（为什么 64/65/66 点得动）**：上游 `StorageContainerMenuBase` 重写了 `doClick`：
  先 `if (slotId >= getTotalSlotsNumber()) return;`（`getTotalSlotsNumber()` = `menu.slots.size()` + **`upgradeSlots.size()`**，
  页签槽位就挂在 `upgradeSlots` 里），然后用**被重写的 `getSlot(int)`** 解析（`getSlot(i≥slots.size())` → `upgradeSlots.get(i-slots.size())`）。
  ⇒ 地址 `64..66` 合法且落到那 3 格上 —— 与 C（合成页签）实测能点 `64..73` 真合成是**同一机制**。
- jar `dcb45b76…`（当时同步的工件；已修，当轮客户端即验证能力通过）；**待客户端**：`alice:craft_cooking_check` + CORE 电池（应 24/24）。

**§6.41 A4b 能力客户端已验证 ✓ + 新缺陷（夹具 CLEANUP 自我重入）已修**（2026-09-13 第二轮：能力证据来自 jar `dcb45b76…` 的那次实测；夹具修复在 jar `502ec5f2…`，已同步）
- **能力通过（世界事实，19:39:55–19:40:05）**：
  - `[Furnace] 认出炉子 by=ownerDeclaration(getCookingSlots) assignBy=mayPlaceProbe input=#64 fuel=#65 output=#66
    container=SimpleContainer data=CookingLogicContainer(selfReported)` ⇒ **路径③ 修好了**（§6.40）；
  - `provision_verified=true`、`input_and_fuel_placed=true input=true fuel=true` ⇒ 装升级 + 往页签槽放料**都生效**；
  - `smelted=true product+1`、`input_consumed=true input-1`、`no_half_products=true inputLeft=0 outputLeft=0`
    ⇒ **沙子真被烧成玻璃、料真被消耗、炉内不留东西**（`litTime=1600` 证明真的在烧）。**A4b 能力 = 客户端已验证**。
- **新缺陷（夹具，不是能力；用户报"一直开关箱子开关个不停"）**：`[Menu] closed reason=furnace_cleanup type=-` ↔
  `[Menu] use_item_on … result=CONSUME` **每 ~50ms 一对、无限循环**，SUMMARY 从未打印。
  **根因（控制流，确定性）**：`cleanupFurnace()` 是**每 tick 都被调用的相位处理函数**；A4b 分支里它
  `closeSession(...)` 之后把活交给需要**多 tick** 的 `deprovisionAndFinish()`（开菜单→等 OPEN→取回升级），
  但**相位仍停在 CLEANUP** ⇒ 下一 tick 又跑 `cleanupFurnace()`、把刚开的菜单关掉 ⇒ 永不收敛。
  （A4 方块炉没这问题：它一 tick 内 `setblock`+`finish()` 就完了。）
- **修（夹具层，最小）**：① 新增独立相位 `DEPROVISION`（`deprovisionAndFinish` → `deprovision`），
  `cleanupFurnace()` 一律 `advance(Phase.DEPROVISION)` 换相位，**CLEANUP 只走一次**；
  ② cleanup 加**菜单守卫**（只剩玩家自带 `InventoryMenu` 时不再跑发现器、不再拿旧地址点击 —— 原先会打 150 行 diag +
  3 次 `ReportedException`）；③ `waitSmelt()` 里发现器从**每 tick** 改成**每 40 tick**（原来刷了 **383 行**
  `[Furnace] 认出炉子`，把真正要看的行淹了）。
- **等级**：IMPLEMENTED + COMPILES；**待客户端**：`alice:craft_cooking_check`（这次应打出 SUMMARY，期望 PASS）+ CORE 电池（应 24/24）。

**§6.42 A4b 收口 ✅（客户端 PASS，jar `502ec5f2…`）**（2026-09-13 第三轮，19:49 + 19:51）
- **单跑 `alice:craft_cooking_check` = PASS**：
  `discover=OK input=#64 fuel=#65 output=#66 container=SimpleContainer data=CookingLogicContainer(selfReported)`
  `provision_verified=true input_placed=true fuel_placed=true input_and_fuel_placed=true smelt_ticks=207 output_taken=true`
  `product_delta=1 cobblestone_delta=-1 input_left=0 fuel_left=0 output_left=0 smelted=true input_consumed=true`
  `no_half_products=true burn_left_ticks_before_reset=1600 cleanup_station_open=true`
  `leftovers_returned=fuel=true input=true output=true deprovision_moved=true upgrade_returned=true`
  `no_block_writes=true verdict=PASS`（19:49:00→19:49:11，约 11 秒）
- **死循环已消失**（对照 §6.41）：`reason=furnace_cleanup` 全程 **3 次**（两个 A4b 跑 + 一个 A4 方块炉跑，各一次）、
  `ReportedException` **0 次**、`[Furnace] 认出炉子` 从 383 行降到 **23 行**（= 24 步电池各一两次）。
- **CORE 电池 = `(24/24) ticks=2809 → PASS`**，逐项含 **`craft_cooking=PASS`**（与单跑同样的 SUMMARY）、
  `craft_furnace=PASS`（原版方块炉回归无变化，`input=#0 fuel=#1 output=#2`）；
  `PROFILE=CORE baseline=13 main=11 extra_skipped=10`、无 SKIP/FAIL、无崩溃。
- ⇒ **A4b（菜单型炉子 / 精妙"熔炼升级页签"）收口**；阶段 3-A 只剩 **A5 决策层接线**。
- **USER_ACCEPTED（用户 2026-09-13 确认）**：客户端看到"只开关箱子 2~3 次、没有连续开关"，**认可 A4b 通过**。

**§6.43 A5 决策层接线：`GoalAction.Craft` + 可做清单 + 生产路径 `CraftJob`**（2026-09-13，D-199，实施完成待客户端）
- **词汇表**：新增动作 `{"action":"craft","item":"<物品id>","count":N}`（`GoalAction.Craft`）。**严格解析**：
  `item` 必须命中本轮候选菜单的**可做清单**（否则 `Refused(not_in_menu:…)` 并**回读**清单规模与截断事实）；
  在清单里但**当前站点做不了** ⇒ `Refused(station_cannot:…)`（换站点是玩家的事）；
  `count` 夹取到 `[1,32]` 并记 `clamps`。**请求里不带站点**（沿用用户裁定：工作站由玩家切换，不自动选优）。
- **候选菜单（确定性事实）**：新增 `craftable` 清单 —— 用**只读**扫描运行时配方表算出"**以当前背包持有的材料
  就能做**"的产物：产物 id + 配方类型映射出的工作站 + `can_use=`（当前选中站点能否做）+ 有界（`MAX_CRAFTABLE=40`
  / `MAX_RECIPE_SCAN=8000`，超限如实 `truncated`）。保守口径：`Ingredient` 逐个"背包里至少一个"；
  未在 `RecipeDump.stationFor` 白名单里的类型**如实跳过**（机器配方不猜）。可做清单**不占**位置类 12 项预算。
- **执行（生产路径）**：`JobRequest.Kind.CRAFT`（`productTag`=产物 id、`quota`=数量）→ `JobLauncher`
  （`CRAFT` **不发料**：合成只真消耗真产物）→ 新增 `job/craft/CraftJob`：只读查询 → 开**玩家选中的**站点 →
  等菜单 OPEN → 需要的能力没装就**按需装配**（`StationProvision` + 容器写入预算）并**关掉再开** →
  网格合成（`GridDiscovery`+`InventoryCraft`，产物口径含容器）或 3 格烧炼（`FurnaceStation`；燃料由
  `ForgeHooks.getBurnTime` 给事实，不写死煤）→ **判据只看世界事实**（产物 +N、材料 −N）→ 失败清场。
  **不自动拆回**升级（装配独立成层，拆是另一层的事）。
- **自检入口**：`alice:craft_goal_check`（零参数、**不需要场景**）+ 电池步 `craft_goal`（MAIN ⇒ **CORE 25 / FULL 35**）：
  给 4 块橡木木板 ⇒ 断言清单里有 `minecraft:crafting_table` 且 `can_use=true` ⇒ 断言越界被拒、在清单里被接受 ⇒
  用同一个 `CraftJob` 真做一个工作台 ⇒ 断言木板 −4、工作台 +1 ⇒ 清空背包。
- **等级**：IMPLEMENTED + COMPILES + 资源自检 PASS（76 项）+ 已同步（jar `8497ec11…`）；**待客户端**（`alice:craft_goal_check` + CORE 电池 = 25/25 + `/alice ask` 观察 LLM 只在清单里选）。

**§6.44 A5 客户端实测：确定性路径 PASS + CORE 25/25 ✅**（2026-09-13 20:07–20:13，jar `8497ec11…`）
- **`alice:craft_goal_check` = PASS**（跑了两遍，`durationTicks=8`）：
  `materials_given=true menu_craftable_total=27 menu_truncated=false menu_has_target=true`
  `parse_out_of_menu=Refused(not_in_menu:minecraft:diamond_block（可做清单 27 项…）) parse_out_of_menu_refused=true`
  `parse_in_menu=Craft(minecraft:crafting_table x1) parse_in_menu_accepted=true`
  `station_cannot_probe=minecraft:netherite_boots parse_station_cannot_refused=true`
  `job_terminal=DONE job_terminal_reason=crafted:minecraft:crafting_table x1 job_failure=-`
  `planks_delta=-4 product_count=1 planks_consumed=true product_produced=true inventory_cleaned=true verdict=PASS`
  过程证据：`[CraftJob] query item=minecraft:crafting_table x1 → CRAFTABLE … grid=2x2 crafts=1` →
  `[CraftJob] 站点 OK station=inventory 随身菜单（无需打开）` → `craft OK … produced=1` → `世界事实 product 0→1 ⇒ 达成`。
  ⇒ **"可做清单 + 严格解析（越界/站点做不了都拒）+ 生产路径 CraftJob + 世界事实判据"整条链在客户端成立**。
- **CORE 电池 = `(25/25) ticks=2737 → PASS`**（`baseline=13 main=12 extra_skipped=10`），逐项含 **`craft_goal=PASS`**。
- 自检期间决策层被正确挂起（`[Goal] trigger_skipped reason=suspended trigger=terminal:CraftGoalCheckTask`）⇒ D-192 附注二的约定仍然生效。
- **LLM 路径（`/alice ask`）本轮没测到**：`/alice ask` 是 **S3 权限请示通道**（列出/答复未决请示），
  无请示时回 `[alice] 没有未决请示` 是**正确行为**，它**不发起决策**。发起决策的入口见 §6.45。

**§6.45 决策触发入口（澄清，避免下次再走错门）**（2026-09-13）
- **自动触发**：任务终态（`terminal:<kind>`）、阈值事件（`event:<…>`）、维生中断（`survival:<…>`）、
  空闲（`idle`，**默认关**，需 `idleDecisionEnabled`）。
- **手动/强制触发**：`alice:goal_director`（右键）⇒ 打印 LLM 配置 + 权威快照 + `GoalDirector.forceOnce`，
  **不受自检暂停影响**（这正是自检窗口内想单独验 LLM 时该用的入口）。
- **不是触发入口**：`/alice ask`（那是 S3 请示通道）。
- 因此 A5 的 **LLM 路径**（LLM 是否会从 `craftable` 清单里选 `craft`）**仍未驗**，下一轮用 `goal_director` 物品观察。

**§6.46 测试通路改造（D-200）**：`trigger` 进 LLM 状态 + `/alice instruct <原话>`（**直连测试通道**）+ `/alice region clear`。
- `trigger` 现在出现在快照顶层（`terminal:…`/`event:…`/`manual`/`operator`）⇒ LLM 能知道"为什么被问"。
- **直连通道的用法（连通性测试，不看菜单）**：`/alice instruct 用你词汇表里的 craft 动作做一个工作台` ⇒
  user prompt 就是这句话，解析**放行菜单校验**（包里没材料也会照做，执行层如实报 `missing_ingredients` —— 这本身是通路证据），
  日志打一行 `[Goal] directed_result raw=… → …`。**自动触发路径仍严格按"只能从菜单选"校验**。
- **登记为临时裁定**：直连通道放行菜单校验**削弱了 A5 红线**；复核触发 = A5 的 LLM 路径验证通过 ⇒ 二选一：
  回收 `/alice instruct`，或加 `LlmConfig` 闸门（默认关）并写进红线说明。
- `/alice region clear`：清掉玩家已选定区域（夹具/测试收尾），菜单里不再出现 `region:saved`。

**§6.47 A5 的 LLM 路径客户端验证 ✅（jar `8e497003…`，2026-09-13 20:39）**
```
[Goal] decision_request trigger=operator mode=directed model=deepseek-flash calledAtTick=243
[Goal] directed_result raw={"action":"craft","item":"minecraft:crafting_table","count":1} → Craft(minecraft:crafting_table x1)
[Goal] execute action=craft ok=true trigger=operator
[CraftJob] query item=minecraft:crafting_table x1 → CRAFTABLE … grid=2x2 crafts=1
[CraftJob] 站点 OK station=inventory 随身菜单（无需打开）
[CraftJob] craft OK … produced=1 consumed=[crafting_table+1]
[CraftJob] 世界事实 product 0→1（目标 x1）⇒ 达成
```
⇒ **prompt → LLM 选动作 → 严格解析 → 唯一执行入口 → 真世界变化** 整条 LLM 通路成立；
用户确认"结果符合预期"。**阶段 3-A（A1–A5）到此全部客户端验证。**

**§6.49 阶段 3-B 启动：模组机器适配（2026-09-13，D-202）**
- 用户裁定：按 D-183 优先级做机器适配器，**本次适配 = 今后适配其它模组的实验**。
- 新增 `docs/MOD_ADAPTER_PROTOCOL.md`（协议 v1）：**六步流水线** S0 枚举 → S1 只读发现 → S2 站点发现 →
  S3 能力闸门 → S4 单机闭环 → S5 收口回收；每步独立验收点、可随时停；**只读先于执行**；
  "读不懂多少"始终可见；**通用 vs 专属**分离判据（上游自述／两上游共享／纯形态可自校验）否则留模组专属；
  实验记录 8 栏模板 + 反模式清单 + 探针回收纪律。
- **第一个实验 = Mekanism**（被跳过 1171 条 / 26 类型；`crushing` 210、`enriching` 142、`injecting` 76、
  `combining` 62、`purifying` 28）。**第一最小闭环 = S0 + S1（全只读）**：`docs/MEKANISM_FACTS.md` +
  查询层机器路线 + 零参数探针 `alice:machine_probe`。S2 的机器范围**等 S1 读数再定**。
- 等级：协议 IMPLEMENTED；S0/S1 未开始。

**§6.50 电池整理后的红项调查（进行中）**（2026-09-13）
- **事实**：整理后 CORE 首跑 `(14/17) → FAIL`，红项 = `craft_furnace`（`smelted=false` 超时 421 tick、`input_consumed=false`、
  `discover=OK input=#0/#1/#2`、`input_placed=true`）、`craft_cooking`（同形，`#64/#65/#66`）、
  **`transfer`（BASELINE，上一轮 25/25 时 PASS）**：`segment_stall … onGround=true delta=0,0,0 input=forward=1.00 → SEGMENT_TIMEOUT`。
- **反证**：同一轮里 `pathing`/`mine_job`/`lumber_job`（都要移动+世界推进）PASS ⇒ **不是"服务端不 tick"**。
- **22:53 单跑复测**：`alice:craft_furnace_check` **PASS**（`smelted=true smelt_ticks=200 product_delta=1 furnace_reset=true`；
  首次 `furnace_found=false` 是**场景没摆**，不是缺陷）⇒ **in-battery 失败未复现**。
- **当前假设（未定论）**：① 序列/残留污染（本轮把 8 步从 CORE 挪走 ⇒ 前置清场/场景序列变了）；
  ② 那一次会话的世界状态（上一轮 `/alice instruct` 起过 `region_lumber`、bot 被反复传送）。
  首要可检验机制 = **"发现时的菜单 ≠ 点击时的菜单"**（点击被接受但机器里没料 ⇒ 点了错菜单；
  `craft_cooking` 的 64..66 与精妙**合成页签** 64..73 地址重叠，正好同族）。
- **下一步（探针先装再测）**：`[Furnace] menu#<identity> slots=<n>`（discover 时 + 每次 placeOne 前）+
  transfer 侧一行输入/前方方块事实；然后**重跑 CORE**：若复现 ⇒ 用探针定位"菜单身份"；若不复现 ⇒ 记为**偶发/世界状态型**，
  但仍要补一条"步骤前清场"的守卫（不允许靠"重跑就好了"收口）。
- **判据纪律**：不因为"单跑绿了"就宣布整理成功；CORE 17/17 未确认前，整理视为**未收口**。
- **22:50 复跑（重启客户端后）= `(14/17) → FAIL`，同样三项**（`craft_furnace`/`craft_cooking`/`transfer`）⇒ **可复现 ×2**。
- **关键对照**：同样的这三步在 **CORE 25（20:07）是 PASS**（`(25/25) ticks=2737 → PASS`）⇒
  **唯一变量 = 我把 8 步从 CORE 挪走** ⇒ 结论升级为：**"撤走的步骤在替这三步做前置/清场"**（是我的整理引入的回归，
  **不是**世界状态偶发）。⇒ **按证据的处置是"先恢复、再逐条撤"**：不能靠"少跑几步"换来假绿。
- **判别实验（23:06，`/alice battery full`）= `(34/35) ticks=3964 → FAIL`**：
  **`craft_furnace=PASS craft_cooking=PASS transfer=PASS`** ⇒ **结论确认**：CORE 17 的那三项红 = **缺前置**
  （撤走的 8 步里有谁在替它们清场）；FULL 里它们跑在"完整链条"中就是绿的。
  **同时暴露同一疾病的第二个受害者**：唯一红项 = **`capability_gate=FAIL reason=foreign_break_attribution`
  （`ticks=1`）** —— BASELINE 的闸门步在**第一 tick**就断言失败：它假定"外来破坏归因"的前提是干净的，
  而 FULL 的**顺序**里它前面刚跑过 `region_maintain`/`transfer`（真破坏/真写入）⇒ **前提被前面的步骤污染**。
  （CORE 17 里它反而绿，只是因为 `transfer` 那时**失败了**、写得更少 ⇒ "绿"是假的。）
- **共因（本轮真正的收获）**：**电池内存在隐式的跨步耦合**——夹具互相依赖"上一步顺便清场"。
  我的整理不是"引入了 bug"，而是**把隐藏的耦合暴露出来**；`capability_gate` 证明这病不止一处。
- **处置（按证据、可复现）**：
  ① **先把 8 步恢复进 CORE**（回到可复现的绿基线 25 项），不为"少跑几步"接受假绿；
  ② 给每个夹具**显式自证前提**（当前菜单=玩家自带菜单 / bot 在起点且 onGround / 用到的方块实体状态已复位 /
     账本与归因检查**限定在自己的时间窗内**），做到**顺序无关**；
  ③ 然后**逐条**把步骤移出 CORE，**每移一条复跑一次**，绿灯才继续；
  ④ 把这条写进 `docs/MOD_ADAPTER_PROTOCOL.md` 的反模式（"**不许依赖上一步顺便清场；前置必须显式自证**"）。

**§6.51 阶段 3-B 启动与 S0/S1/S2（2026-09-13/14，D-202~D-206）**
- **方法先行**：`docs/MOD_ADAPTER_PROTOCOL.md`（六步流水线 + 通用/专属分离判据 + 记录模板 + 反模式）；
  用户裁定"本次适配 = 今后适配其它模组/附属模组的实验"。
- **S0**（离线，真数据）：`docs/MEKANISM_FACTS.md` —— Mekanism **26 类型 / 1171 条**（`crushing` 210、
  `pigment_extracting` 178、`painting` 176、`enriching` 142、`sawing` 124…）；总量随会话变化已标出处。
- **S1**（只读，客户端验证）：`MachineRecipeFacts`（问上游 + 自校验）→ `RecipeQuery.MACHINE_ROUTE`
  （有出处路线：机器类型 + 材料）；`CraftJob` 对机器路线**如实拒绝** `machine_recipe_unsupported:not_executable`。
  实测样例：`mekanism:dust_lithium → MACHINE_ROUTE station=mekanism:crystallizing`；
  `mekanism:dust_iron → station=mekanism:enriching perCraft=12 mats=[minecraft:raw_iron_block x1]`。
  期间修掉两个真 bug：机器分支未按目标过滤（`machineTypes` 收全表）、非物品输入的 `mats=[]` 歧义。
- **S2**（只读，客户端验证）：机器站点认得出（命名空间方块 → `MekanismTileContainer` → `getTileEntity()`）；
  **进度=上游自述**（`getScaledProgress`/`getOperatingTicks`/`getActive`）。
- **S5 回收**：两支临时入口（`alice:machine_probe`、`alice:machine_station_probe`）已删除，
  任务转电池步 `machine_route` / `machine_station`（机器不在 ⇒ SKIP）⇒ **CORE 25 → 27**。
- **S3**（只读，**客户端验证 ✅**，2026-09-14，D-209，commit `fb979e5` + `edf5643`）：
  ① 真源 `decision/MachineMap.java` **27 行 = 上游全部类型**（23 有站点 + 4 无站点：`evaporating`
  多方块、`energy_conversion`/`gas_conversion`/`infusion_conversion` 无单方块站点）；
  ② `Route.station` 由配方类型 id → **机器方块 id**（`RecipeQuery` 两行，类型仍留在 `Route.type`）；
  ③ 探针改「按表认机器」：每台一组 `m{i}_*`，断言**菜单类 == 已实测登记值** +
  **方块实体自述配方类型 == 表里的类型**（`getRecipeType()`→`getRegistryName()` 只读反射）；
  ④ 生成视图 `docs/MACHINE_MAP.csv` + 双向闸门 `tools/check-machine-map.sh`
  （Tier A 结构 / Tier B `javap` 读上游 jar：**27 类型 ↔ 27 行双向一致**；反向验证删行、改 `crusher_typo` 均立刻 FAIL）；
  ⑤ 场景加第二台 `mekanism:crusher`（@66,64,307）作"加机器不改 Java"的活证据，已同步进存档 datapack。
  **第二轮实测（`latest.log:2941`/`:2953`/`:2971`/`:3680`）**：`按表找到 2 台`、`m1_binding=true m2_binding=true`、
  电池 `(28/28) ticks=2789 → PASS` ⇒ 表真的成了"哪台机器"的判据。
  **实测纠正两条口径**（已改，`COMPILES` + 闸门全绿，待下次复跑确认显示）：
  ① 实测类型 **26** ≠ 表 27，差的是**零配方的 `mekanism:smelting`** ⇒ 探针改为对表行**完整划分**
  （`with_site_confirmed` 22 + `with_site_unobserved` 1 + `no_site` 4 + `row_block_missing` 0 = 27）
  + 分桶守恒自检，`with_site_unobserved` 只报事实不判红；② **`menuClass` 不区分机器**（两机同一个
  `MekanismTileContainer`、槽位表逐项相同）⇒ 身份只能靠 `m{i}_binding`；crusher 菜单类已按观察值回填。
  **未覆盖如实登记**：只登记基础机（`crushing` 的 4 档工厂变体在 `note` 点名未入表）。
  **待客户端**：`/alice authz` 的 L2 行（**仍从未在客户端敲过**）+ 下次复跑看新口径显示。
- **S4**（**3-B 的第一次写入**，`COMPILES` + 闸门全绿 / **待客户端验证**，2026-09-14，D-210）：
  ① 夹具 `MachineCycleCheckTask` + 零参数物品 `alice:machine_cycle_check`：认机器（表）→ 开菜单 →
  挑一道**物品进出**的配方（按 id 排序取第一道，不写死）→ shift-click 放料 → 等**真实进度** → shift-click 取产物
  → 断言 `product_before/product_after`、`machine_emptied`、`input_consumed`；
  ② **不新造授权**：容器写入维度 `WriteBudget` + 理由 `CONTAINER_TRANSFER` + requester `machine-cycle`
  （矩阵前缀规则登记为 `CONTAINER`）；**不猜槽位**：QUICK_MOVE 让菜单决定落点，成不成**只看结果**；
  ③ 场景 `machine_course` 在富集仓下方加**真实电源** `mekanism:creative_energy_cube`（纯数据 ⇒ 不改 Java）；
  夹具先等场景电源，真喂不上才**前提补电**并**必然留痕** `energy_source=…`（D-210）；
  ④ 失败码全是可归因的：`machine_absent`/`machine_out_of_reach`/`no_recipe_with_item_io`/
  `container_write_refused`/`feed_*`/`take_*`/`no_product_in_600ticks:no_energy|progress_stalled`。
  **v1 边界**：单机单配方 + 站位用夹具传送；**内核寻路走到机器旁 = S4 v2**。
  **客户端状态（第四轮实测）**：S4 物品**没跑**（日志里 `MachineCycle` 0 次）⇒ 待下一轮。
- **R1 收口：容器写入进策略表**（2026-09-14，D-211，`COMPILES` + 六闸门 PASS / 待客户端）：
  ① **矩阵首次经手容器写入**：挂点 = `WriteBudget.consumeContainerWrite`（所有已接线容器写入的必经之处，
  容器写入**不产生账本条目** ⇒ 放置类的执行期复验在容器侧没有对应物，这就是它的对应物）；
  顺序**先策略后预算**；口径与移动授权对齐（**未登记 ⇒ 留痕不拒**；**已登记但未声明 ⇒ 硬拒**）；
  拒绝权默认**武装**，留一行开关 `setContainerRefusalArmed` 作回退把手 + 给夹具断言两种模式。
  ② **可执行覆盖**：`docs/authz/CONTAINER_WRITE_SITES.csv`（**20 个调用点**，每行 `category`+`why`，
  `gated=no` 必须写理由）+ `tools/policy-map.py` **断言⑦**（未登记点 / 谎报 `gated=yes` /
  陈旧登记行 / `enforced_by` 指向不存在或没真调用 `consumeContainerWrite` ⇒ 全 FAIL；四条负例已实测都红）。
  ③ **覆盖审查顶出的两个真缺口（已补）**：`CraftJob`（**生产**熔炼：往炉子放料/取产物**从没记过账**）、
  `MenuProbeTask`；表随之在 **CRAFT 行（P-05/P-16）声明 `CONTAINER_TRANSFER`**。
  ④ **已知边界（未做，如实登记）**：`InventoryCraft` 结果槽 shift-click 在开着容器菜单时会把产物放进容器
  （实测 `product_in_container=1`）而不过闸——接它需要给该方法 grant 参数；`TransferFixture` 在隔离层
  直接驱动搬运原语，**有意**不过闸。
  ⑤ **复核触发**（下一轮电池）：`container_checks=N`（恒 0 ⇒ 挂点没接上）、`container_refused=0`
  （>0 ⇒ 有生产路径被硬停，先读归因）。
- **夹具纪律**（用户要求）：场景夹具**自带传送 + 结束复位**（PLAYBOOK §5.0d）；审计已无缺口。

**§6.52 授权模型与过程开销：修订方向（2026-09-14，D-207 + 两轮工作流审查）**
- **代码侧待办**：① 集中策略表（区域×任务类别 → TEMP/KEEP + Movement 集合 + 预算；默认 PROTECTED）；② 野外默认放开 `PILLAR/FALL/DOWNWARD`；③ `miningApproach` 改"按条件放行"（**先 A/B 客户端证据**）。
- **过程侧待办**：记账 ≤15 行/条；收口集中更新文档；验证批量化；`tools/check-fixture-hygiene.sh`；`docs/WORKFLOW_RULES.md` 索引；"待验证"单一队列。
- **待用户拍板**：验证等级 5→3；`AI_TEST_MATRIX` 去留；规则日落机制。
- **审查量化留档**：09-14 当日 19 提交（6 纯文档/13 含代码）、`docs +436` vs `src +864`；场景函数 102 个、电池仅引用 8 个；决策文档 62 处"待客户端"。
- **第五轮：R1 容器闸门活了 + S4 首次跑通 + 抓到一个"假前提"**（2026-09-14，客户端验证）：
  ① **R1**：`container_gate_live=PASS container_gate_armed=PASS containerGate=armed container_checks=13
  container_refused=0`（`latest.log:3206`）。**13 = 各步 `containers=N/32` 之和**（provision 2 + craft 2 +
  furnace 3 + cooking 4 + transfer 2)⇒ 闸门覆盖面与预算覆盖面**逐点一致**，不是恒 0 的死开关；
  每步 `refusedContainers=0` ⇒ 无生产路径被硬停。日志里唯一那条 `denied action=container`（`:3201`，
  `by=walk-to:CONTAINER_TRANSFER`）是 `container_gate_armed` 负例故意打的（故 WARN 与 `container_refused=0`
  不矛盾：负例跑完 `finally` 还原开关 + 快照还原观察样本）。⇒ **D-211 的两条复核触发都已解除**。
  ② **S4**：`[MachineCycle] SUMMARY … binding=true feed_verified=true in_machine=1 active_seen=true
  progress_ticks=199 product_after=1 product_landed=true machine_emptied=true input_consumed=true
  container_writes=2 reset=true verdict=PASS`（`:3811`）；两次写容器走同一套闸门+预算
  （`scope=…#1478:MachineCycleCheckTask … containers=2/32 refusedContainers=0`，`:3815`）。⇒ S4 = `WINDOWS_CLIENT`。
  ③ **场景电源前提是假的**：第五轮判为"朝向"并改 `[facing=up]`（D-212）；**第六轮带着 `[facing=up]` 重跑仍是
  `energy_at_open=0.0` + `api_precharge`（`latest.log:281`；存档 `r.0.0.mca` 里 `facing:"up"` 已核实生效）
  ⇒ 朝向不是根因（D-213）**。真因 = **创造能量方块放下时自带电量 0 J、且永远充不进电**
  （`BasicEnergyContainer:52` 初值 ZERO + 创造档 `insert`/`extract` 强制模拟；`TileComponentEjector:166` 跳过空容器），
  上游设计里"空变体"就是 power sink、"满变体"靠**放置时读物品 NBT** 灌入（`BlockMekanism:310-314`）。
  证据 = 存档里的对照组（同一次保存）：机器 `EnergyContainers=[{"Container":0,"stored":"3990000"}]`、
  方块 `EnergyContainers=[]`。已修场景加 `data merge block … {EnergyContainers:[{Container:0,stored:"4000000000"}]}`
  （命令 10→11 条，**纯数据、不重编 jar**）。勘察全文 `docs/reviews/2026-09-14-S4电源根因勘察.md`。
  **✅ 2026-09-14 第七轮已重验通过（`WINDOWS_CLIENT`）**：`/reload` → `/function alice_test:machine_course`
  （11 条命令，`latest.log:187`）→ 右键 `alice:machine_cycle_check` ⇒ `:213`
  `energy_at_open=20000.0 energy_source=cube（场景电源，未补电）`（**不再是** `api_precharge` 的 4.0E6）、
  `progress_ticks=199 product_landed=true machine_emptied=true input_consumed=true container_writes=2 reset=true
  verdict=PASS` ⇒ **电来自场景本身，D-213 判据成立**（D-212 因果判定确认作废；朝向修正保留）。
  语义别读强：`energy_source=cube` 证的是"场景把电送上了"（开机 `energy_at_open > 0`），不是"认出了 cube 方块"。
  ④ 旁记：`K4=OK(… 写入类例外=43)`（上轮 56，交替，两次都 `K4=OK`，未取证，暂不动）。
  ⑤ **S4 升级为电池步（D-197）— ✅ 已执行（2026-09-14 第七轮）**：条件（③ 重验为 `cube`）已满足 ⇒
  新增 `machine_cycle`（MAIN，`stepSkippable`，预算 1600 > 任务自身 `MAX_TICKS` 1400），
  电池 **CORE 28→29 / FULL 38→39**（`RegressionBatteryTask` CURATION + `docs/BATTERY_CURATION.md` + 历史表已同步）。
  **✅ 该步客户端绿已确认（2026-09-14 第九轮）**：新会话（新 jar）⇒ `machine_cycle=PASS ticks=210 idempotent=true`
  （`latest.log:3130`）、整轮 `(29/29) ticks=3108 → PASS`（`:3842`）；步内仍是 `energy_source=cube（场景电源，未补电）`
  + `containers=2/32 refusedContainers=0` ⇒ **升格闭环完成**。
  ⑦ **✅ 已关闭（2026-09-14，随 S5 收口的 jar 重编一并做）**：用户可见文案里的项数**不再写死** ——
  新增 `RegressionBatteryTask.coreStepCount()`（项数唯一出处 = `CURATION`），`RegressionBatteryItem` 的启动文案
  与类注释、`AliceItems` 注释全部改为**现算**（原写死"26 项"而实测 `(29/29)`）。
  同一轮还归位了一处**错位注释**（`assignCraftCookingCheck` 的 A4b 说明原本挂在机器探针上方）。
  新 jar `sha256=d5e49c1b4ec7e8f48b634c97f912f4513c5423ef4952871b569899d44fcfc5ef` ⇒ **客户端需重启加载**。
  ⑥ **✅ 已关闭（2026-09-14 第七轮）**：随本次因功能需要重编 jar 一并改了 `MachineCycleCheckTask` 类注释 ② ——
  写明"创造方块放下就是 0 J（`BasicEnergyContainer.stored = ZERO` + creative 强制 SIMULATE ⇒ 灌不满也放不出），
  场景用 `/data merge block …` 把电写进方块实体（`load()` → `setEnergy`，绕过插入守卫；灌进去后 extract 仍 SIMULATE
  ⇒ 永不为空 = 真无限电源），`api_precharge` 只是兜底"，并新增"能量判据的准确含义"一段。
  新 jar `sha256=b290b8b3a1276915feee509f6e7203aad7ca16ad2454e742b5f2d14dc3a7984f`（已同步到客户端 `mods/`）；
  **客户端需重启才会加载新 jar**。
  ⑧ **✅ 已关闭（2026-09-14 第十轮复核，全绿）**：启动文案打 **"29 项，约 2~4 分钟"**（不再写死 26 ⇒ 台账⑦ 修复生效）、
  `[Regression] SUMMARY … machine_cycle=PASS … (29/29) ticks=3083 → PASS`（`latest.log:3849`）。
  **"探针零残留"的判据由 Forge 自己给出**：进世界时报 `[ERROR] Unidentified mapping from registry minecraft:item
  alice:machine_cycle_check: 3405` + `missing registry entries`（另有 stats 一条非法统计警告）⇒ 该物品**已不在注册表**。
  ⚠️ **这两条是"删注册物品"的一次性自愈警告，不是回归**（已用**磁盘状态**证实，不靠日志措辞）：退出后
  `level.dat` 里 `grep machine_cycle_check` = **0**、`stats/<uuid>.json` 里该键已消失 ⇒ 详见 D-215 附注一，
  **下次回收物品时别在复核轮里把它误判成"回收搞坏了什么"**。
  旁记（第十一轮**已取证定性 ⇒ 结案，不再是开放项**）：`K4 写入类例外` = **`PathingStats` 的全局事件计数**
  （`RegressionBatteryTask.k4Delta` 用 `totalsSnapshot()` 减去电池开始时的基线），计的是
  "A* 到达目标格但 `canStandCentered=false`、**且进入边是写入类**（`plannedBreaks/plannedPlaces>0`）"的
  **搜索事件次数** —— 见 `AStarMovementSearch:112-122`（**纯通行边**走进来的那种才是真异常 `goal_not_standable`）。
  **它按构造就不参与判定**：`k4Ok = goalBad==0 && finalBad==0`，`postWrite` 只被打印。
  实测 43 / 56 / 56 / **58**（第十一轮 `latest.log:3924`）⇒ 波动 = 每轮**搜索次数**差异
  （挖掘/掉落收集类步骤的规划次数本来就不稳定），**不是语义变化**，也不再指示"上一轮遗留脚手架"。

## 阶段 3-B 后续（2026-09-14，(c) 起步 + (a) Thermal S0 的产出）

  ⑨ **✅ 已关闭（2026-09-14，第十四轮）—— `--target/--routes` 已真做**：`tools/recipe-readability.py` 现在注册了
  `--target <item>`（聚焦模式：只打印反查段，不打印完整报告）与 `--routes`（逐条明细 + 输入），
  `find_routes()` 统一处理 `output` 的字符串/对象/列表三种形态（原版配方实测 **3689/3689 全是字符串**，
  兼容分支是保险），**自证断言已加**（`--selftest` 现要求铁锭反查得到 **2** 条原版路线且 type 集合正确 ——
  没有断言的实现等于没实现）。实测验收：`--target minecraft:netherite_ingot --routes` ⇒ **7 条 / 3 个命名空间**
  （minecraft / mekanism / thermal，含熔炉与高炉）—— 这正是 S1/S2 要的"跨模组打架"视图。
  **并把口径边界写进输出**（[Target] 段固定打印一行）：机器类型在导出里**只有计数、没有产出字段**
  ⇒ **机器路线（`thermal:press` / `mekanism:crushing` …）本查询一条也看不到**，要含机器路线必须走运行时
  `RecipeQuery`。历史：docstring 曾宣传这两个参数但 `main()` 未注册（实测 `error: unrecognized arguments`）；
  **修的是实现，不是删承诺**。
  ⑩ **Thermal S1 的两个前置事实（如实登记）**：
  ① **没有上游 sources jar、也没有本地反编译产物** ⇒ 与 Mekanism 不同，S1 要**先解决"去哪拿源码/字节码"**
  （本机可 `unzip` 静态 jar 读 data/ 与 class，但"问上游自述"那套判据仍建议对着源码核，见 D-036 的取证纪律）；
  ② **✅ 已关闭（2026-09-14，第十三轮）—— `alice-recipes.json` 已就地重导**：游戏内 `/alice recipes` ⇒
  `recipes=3689 skipped=2379 tags=693 skippedTop=thermal:press=227,mekanism:crushing=210,mekanism:pigment_extracting=178`
  （`config/alice-recipes.json` 1527420 字节，mtime 2026-09-14 17:15）⇒ **现在的表就是"当前客户端真实分布"**。
  重导复核：Thermal 仍 **30 类型 / 652 条**（与 S0 旧导出**逐项一致** ⇒ 结构事实稳定）；可读 2923→3689 的增量来自
  后装的 refinedstorage / sophisticatedcore|storage|backpacks，而**它们的配方全部落在原版可读类型里
  （`skippedTypes` 里 0 条）**，跳过量只 +9。新发现见 `docs/THERMAL_FACTS.md` §6（**30 个类型里约一半是
  燃料/催化/增幅类修饰类型，不是机器** ⇒ S1 枚举不得按类型数建行）。
  ③ **✅ 已关闭（2026-09-14，第十四轮）—— `+34` 两个机制各占一边，逐项闭合**：
  **`+52` = 一个被整片漏掉的内嵌 jar（JiJ）**：`thermal_foundation` 里藏着
  `META-INF/jarjar/thermal_core-1.20.1-11.0.6.24.jar`（4.4 MB，**是内容不是库**：11 个 `device_*` 方块 + 22 条 device/fuel 类型配方全在里面）。
  `mods/*.jar` 逐个 `unzip` **看不到内嵌 jar**（只显示为一行 `META-INF/jarjar/xxx.jar`）⇒ 这是"按资源枚举设备"最容易踩的坑；
  全客户端只有 `create`（4 个内嵌 jar，纯库，`data/*/recipes` 各 0 条）与 `thermal_foundation` 带 JiJ，**Mekanism 没有**
  ⇒ 既有 Mekanism S0/S1 结论不受影响。**`−18` = 配方条件在加载期筛掉**：`smelter_recycle` 22 条**全部**带
  `conditions:[{type:"cofh_core:tag_exists", tag:"forge:armor/<金属>"|"forge:tools/<金属>"}]`，运行时只有 **4** 条存活 ——
  而 Alice 自己的 `itemTags`（693 个）里**恰好只有这 4 个标签存在**（`forge:armor/{gold,iron}` + `forge:tools/{gold,iron}`），
  其余 18 个（9 种金属 × armor/tools）不存在 ⇒ 条件不通过。**两侧独立吻合：`670 − 18 = 652`。**
  教训入册：**"静态 jar 里有多少条配方" ≠ "运行时有多少条"**（条件会在加载期筛），引用静态计数时必须声明这一点。
  完整证据与复算命令见 **`docs/THERMAL_S1_FACTS.md`**。
  ⑪ **✅ 已关闭（2026-09-14，D-217）—— (c) 增量 2 施工清单已全部落地**：`CraftJob` 的 `MACHINE_ROUTE`
  不再 `not_executable`，改为**数据驱动的执行准入**（`MachineMap.executable(...)`，只升 `mekanism:enriching`
  一行 ⇒ `CraftJob` 只驱动 `EXECUTABLE` 的行）。闭环本体抽成 `task/craft/MachineCycle`（**夹具与生产同一份**，
  夹具变薄壳），两条红线都落地：① **不补电**（执行器里没有造能量的代码；`EnergyTopUp` 只有夹具实现、
  生产位置传 `null`）+ **门禁 `tools/check-precharge-containment.sh`** 三条断言（含反向测试：注入一次
  `precharge(` ⇒ 立刻红）；② 目标机器取 `route.station()`，多输入/化学品输入如实拒绝。新电池步
  `craft_machine` ⇒ **CORE 29→30 / FULL 39→40**；六道门禁 PASS（`check-policy-matrix` 顺带抓到写入点登记的
  漂移：`docs/authz/CONTAINER_WRITE_SITES.csv` 已从夹具改登记 `task/craft/MachineCycle`）。
  **剩余如实边界**（不是台账项，是范围）：机器路线目前只支持**单物品输入**的机器。
  ⑫ **`[Recover] session=<id> movements=N …` 的标签与载荷不符（观测缺陷，第十一轮踩到，未修）**：
  该行由 `PathSession:273` 打印，载荷来自 `RecoverabilityReport.describe()` —— 而后者是**自服务器启动累计**
  的静态计数（`RecoverabilityReport:13` 明确"不随会话清空"，`reset()` 只由 `recoverability` 自检步调用）。
  ⇒ 第十一轮 `machine-walk-0` 那行的 `movements=167`（含 `PILLAR=13 / DOWNWARD=15 / BREAK_*`）**不是这段路的**，
  是当时的**全进程累计**；**别拿它给某次寻路定罪**（该步是否真写入，看同一步 `WriteBudget`：实测 `breaks=0 places=0`）。
  最小修法 = 会话开始记基线、打印**每会话增量**（或至少把措辞改成"累计"）。
  ⑬ **✅ 已关闭（2026-09-14，D-218）—— 查询层现在按"执行准入"优先挑机器路线**：`RecipeQuery` 返回
  `MACHINE_ROUTE` 前把候选路线**只排不删**（主序 `hasExecutor(station)`、次序 `recipeId` ⇒ 结论**确定**，
  不随配方管理器迭代序漂），并在 `Result.note` 里写清"挑了哪台 / 有没有准入 / 同类共几条、其中几条有准入"。
  **不删**保住了 S1 的既有语义（读得出路线就报得出），执行与否仍由 `CraftJob` 按 `EXECUTABLE` 如实判定。
  **观测点** = 电池步 `craft_machine` 新增的 `fallback_used`：修好后首选候选应直接可用 ⇒ 期望
  **`fallback_used=false` 且 `target=minecraft:clay_ball`**（第十二轮的 `true` + `target=soul_soil` 即本问题的证据）。
  变回 `true` ⇒ **先查是不是又有"未准入"的机器抢了同一产出**（那是产品问题，不是回归）。
  ⑭ **✅ 已关闭（2026-09-14，第十五轮）—— 探针的采样命名空间改为"按 `MachineMap` 表推导"**：
  原状：`MachineProbeTask.NAMESPACE` 是单个常量 `"mekanism"`（`MOD_ADAPTER_PROTOCOL` §6 早标成"**半通用**"）。
  加了 32 行 Thermal 后，探针仍只枚举 `mekanism` 的配方类型 ⇒ **26 个 Thermal 站点行全部落进
  `with_site_unobserved`**（第十四轮实测 `22 + 27 + 10 + 0 = 59`，守恒成立、`row_block_missing=[]` 通过），
  而那个桶的日志文案写的是"上游 0 配方或模组集差异" ⇒ **会把"探针没采样这个命名空间"读成"该模组没有配方"**。
  **修法**（最小）：① `adoptedNamespaces()` 从 `MachineMap.rows()` 的 `typeId` 前缀推导（表是唯一真源，
  加模组不用改探针）⇒ `MachineProbeTask` 现在 **0 处 `mekanism` 代码字面量**；② 过滤器从 `startsWith` 改为
  "命名空间 ∈ 表里出现过的那些"；③ 新增**逐命名空间一行**覆盖计数
  （`[MachineProbe] namespace=<ns> types=… type_recipes=… samples=… unreadable_via_vanilla=… upstream_readable=…
  machine_output_not_item=… input_readable=…`），SUMMARY 保留全局合计并把 `namespace=` 改为 `namespaces=[…]`；
  ④ `failureReason()` 的 `_absent` 后缀**保留**（电池侧判据是 `contains("_absent")`），前缀改为
  `machine_namespaces_absent`。
  **第十五轮期望（新基线）**：`namespaces=[mekanism, thermal] types=56 type_recipes=1823`，
  `with_site_confirmed=**46** with_site_unobserved=[mekanism:smelting, thermal:brewer, thermal:hive_extractor]`
  （分桶 `46+3+10+0=59`）—— 即 `with_site_unobserved` **回到它文档里的本义：只有真·零配方**。
  算术来源：有站点 49 行 − 零配方 3 行（`smelting`；Thermal 侧 `brewer`/`hive_extractor` 上游有类型但本次零配方）。
  **顺带入册的教训**：**"没被采样"与"不存在"必须能从日志上区分开** —— 一个统计字段混着两种含义，
  迟早会被读成错的那一种。
  **✅ 第十五轮实测复核通过**（`latest.log:3007`/`:3177`/`:3178`）：`命名空间=[mekanism, thermal] 类型=56 条数=1823`、
  `with_site_confirmed=**46**`、`with_site_unobserved=[mekanism:smelting, thermal:brewer, thermal:hive_extractor]`
  （`46+3+10+0=59`）、`row_block_missing=[]`、`unmapped=[]`、`(30/30) ticks=3353 → PASS`；
  逐命名空间行与**手算预测逐项相同**（`mekanism 26/1171`、`thermal 30/652`）。
  ⇒ "Thermal 32 个类型里哪 30 个在运行时真有配方"**从此有自动化证据**。
  ⑮ **✅ 已关闭（第十五轮发现 → 第十六轮修复并客户端复核）—— Thermal 机器配方的"读法"缺失：不是读不出，是访问器名字不同**：
  逐命名空间行里 `namespace=thermal … upstream_readable=0 input_readable=0 machine_output_not_item=57`（57/57 抽样全空），
  而 `namespace=mekanism` 是 `upstream_readable=20 input_readable=30`。**根因已取证（javap，不是猜）**：
  Thermal 的机器配方类（`PressRecipe` / `PulverizerRecipe` / `CentrifugeRecipe` / `CrystallizerRecipe` /
  `RefineryRecipe` / `PyrolyzerRecipe` / `CrucibleRecipe` / `PulverizerRecycleRecipe` …）**都 `extends ThermalRecipe`**，
  访问器是 **`getInputItems()`（`List<Ingredient>`）/ `getInputFluids()` / `getOutputItems()`（`List<ItemStack>`）/
  `getOutputItemChances()`（`List<Float>`）/ `getEnergy()` / `getXp()`**；
  而 Alice 现在反射问的是 `getOutputDefinition`/`getOutputs`（输出）与 `getInput`/`getItemInput`（输入）——
  **Mekanism 的名字**。⇒ 查询层因此给不出 Thermal 的"机器产线（上游自述可读输入/输出）"路线。
  **⚠️ 修之前必须先想清楚的一条**：**Thermal 的产出带概率信息**，实测 **146 / 670** 条配方**声明了 `chance` 字段**，
  取值跨 **0.05 ~ 12.5**（`2.0` 出现 50 次）⇒ **不在 [0,1]**，**它本身不是"概率"**，语义未取证
  ⇒ **只读 `getOutputItems()` 会把这类产出写成"必然产出"**，那是**过度承诺**（红线：未知语义默认只读、不猜）。
  最小修法 = 扩展名族 + **配套读 `getOutputItemChances()`**，但**只声明"有概率信息"、不解释数值**（语义另立 ⑯）。
  **验证点**：下一轮电池里 `namespace=thermal upstream_readable>0 input_readable>0`，且有概率信息的条数被如实报出。
  **输入形态也不同**：Mekanism 的输入是单一 `InputIngredient#getRepresentations()`，Thermal 是 `List<Ingredient>`
  ⇒ 读取代码要**按形态分支**，不能照抄。
  **✅ 已修（第十六轮，2026-09-14）—— 只做"读出来 + 如实标注"，判据一行没动**：
  ① `MachineRecipeFacts` 成为**唯一读取器**：名族扩到 `getInputItems`/`getInputFluids`/`getOutputItems`/`getOutputItemChances`；
  ② `itemStacks()` 新增吃 `List<Ingredient>` 形态（每项取**第一个物品**当代表，与 `getRepresentations()` 同一口径：
  **只取代表、不展开标签**）；③ 新增 `Facts.chanceDeclared()`（**上游给了概率信息就为真**；
  **明确不解释数值** —— 第一版写成 `chance < 1.0` 是**在猜语义**，被运行时数字当场证伪后撤掉，见下）；
  ④ **纯流体输入**也算"配料存在"（否则会报成 `mats=[]` = "不需要材料"，正是 D-204 那个 bug 类）；
  ⑤ `MachineProbeTask` 的两份私有反射读取器**删除**，改调同一个 `MachineRecipeFacts.read()`
  （"探针读得出、查询层读不出"这种两处口径漂移从此不可能再现），并新增
  **`chance_declared=`** 计数（per-namespace 行 + SUMMARY 都有）；
  ⑥ `RecipeQuery` 在 `note` 末尾如实追加"⚠️ 该配方声明了产出概率（chance 语义未取证 ⇒ 不作必然产出）"，
  **`Verdict` 语义、`Route` 记录字段、`MachineMap` 能力列、`CraftJob` 准入全部未动**（`Route` 不加字段 ⇒ 下游构造点零改动）。
  **第十六轮实测**（`latest.log:3177`/`:3178` 一带）：`namespace=thermal` 的 `upstream_readable` **0 → 26**、
  `input_readable` **0 → 34**（57 条抽样；流体输出的 `refinery`/`crucible` **仍读不出**，是如实结果），
  `namespace=mekanism` 四个计数（31/20/31/30）**逐字未变** ⇒ **只读逻辑没有误伤既有行为**。
  ⚠️ **同时暴露了我第一版的错误**：`probabilistic_output=23/57` 与"JSON 里 `chance<1` 的条数"**算不出来**
  （按 9.7% 比例、每类抽 2 条，期望只有 ~5）⇒ **`getOutputItemChances()` 返回的很可能不是 JSON 原值**，
  拿它做 `< 1.0` 判断是**猜语义** ⇒ 已撤，改为只声明"有概率信息"。见 ⑯。
  ⑯ **Thermal 的 `chance` 到底是什么意思 —— 语义未取证（第十六轮发现，未修；**不阻塞**任何当前工作）**：
  **已知事实**（三条，都可复算）：① 静态 JSON 里 **146 / 670** 条 Thermal 配方声明了 `chance`，
  取值跨 **0.05 ~ 12.5**（`2.0` 出现 50 次、`1.0` 出现 14 次、`0.2` 出现 32 次）⇒ **不在 [0,1]**，
  **不能当概率直接读**；② 每条配方里 `chance` 的个数分布是 1 个（74 条）/ 2 个（45 条）/ 3 个（27 条）
  ⇒ 常见形态是"主产出 + 1~2 个副产物各带一个数"；③ **运行时的 `getOutputItemChances()` 与 JSON 对不上**：
  第十六轮实测 `namespace=thermal … probabilistic_output=23 / 57`，而按"JSON 里至少含一个 `<1` 值"的比例
  （65/670 = 9.7%）与"每类抽 2 条"的抽样设计，期望只有 **~5** ⇒ **访问器返回的很可能不是 JSON 原值**
  （疑：按机器/增幅折算后的值、或对缺省项填了默认值）。
  **为什么现在不做**：它的唯一用途是"能不能把某条机器路线的产出当**必然**"，
  而 **Thermal 全表 32 行都是 `READ_ONLY`**、没有任何执行准入 ⇒ 当前**不影响任何行为**；
  本轮的处置（只声明"有概率信息"、不解释数值）已经**堵住了过度承诺**这一侧。
  **要动它时的最小取证路径**：`javap -c` 追 `IMachineRecipe#getOutputItemChances(IMachineInventory)` 的
  **调用方**（机器方块实体里真正算产出的那处），看它把返回值当**概率**（除/比 100）还是当**倍率**（乘基准）；
  顺带核 `use_chance`（14 条配方带这个顶层字段）与 `primary_mod`/`secondary_mod`（各 11 条）的含义。
  **触发条件**：任何"要把 Thermal 机器升 `EXECUTABLE`、或要让概率产出参与 `CraftJob` 判定"的动作之前，**必须先收这一项**。

---

## §8 T1 审计残留（2026-09-14 登记，来源 = 三路只读审计 R-4/R-5/R-1 的"没做完"部分）

> 全部出自 `docs/reviews/2026-09-14-项目完成度与优先级审查.md` §7.1。
> **登记的目的是"不是遗忘"，不是"现在要做"** —— 每条都给了触发条件。

| # | 项 | 现状 | 为什么现在不做 | 触发条件 |
|---|---|---|---|---|
| **R4-残** | `RegionLumberJob` 补种**仍直接 `level.setBlock`** | 已补 `BlockInteraction.reachable` **触及前提**；其余（朝向/放置面/`gameMode` 交互路径）未走原语 | 选定树苗可能落在**主背包**，而 `BlockInteraction.placeAt` 只认**快捷栏 0–8** ⇒ 改走原语会**同时改变物品消耗路径与放置面语义**，属行为变更、需独立一轮客户端验证 | 下一次动 `RegionLumberJob` 补种路径时一起做 |
| **R5-残** | `StationProvision.click` / `InventoryCraft.click` **未做编译期强制** | `FurnaceStation` 已做（`WriteGrant` 必传 + `WriteReason.container()` 校验）；这两个仍是「调用方自觉」（内部调用点共约 19 处） | **✅ 已收口（2026-09-16）**：新增**菜单写入家族** `WriteReason.menuWrite()` = `container()` ∪ `CRAFT_GRID`（新理由：合成网格/结果槽，**不吃容器写入预算**，但必须显式交理由）；两处 `click` 原语的**每个重载**都强制 `WriteGrant` 形参并校验 `menuWrite()`（对齐 `FurnaceStation` 样板），`InventoryCraft` 的 3 个公开入口与 5 个调用点（`CraftJob`／`TableCraft`／`CraftActionCheckTask`×2／`CraftStationCraftCheckTask`）全部显式交出 `CRAFT_GRID`；注册表三行 why 已改。 **判据（两条，均实测）**：① **编译期** —— 去掉任一调用点的 grant 实参 ⇒ **编译失败**；② **门禁** `check-policy-matrix.sh` 新增 ⑨「每个 `click` 重载必须带 `WriteGrant`」（平衡括号解析）—— 把一个重载改成 `Object` ⇒ 门禁红（⚠️ 旧写法只查一处会被另一个重载顶绿，已实测修正）；③ 行为证据 = CORE 全绿（合成/装配各步未被误杀）|
| **R2-残** | **R-2 的运行期路径没被走到**（`[Job] launch` 客户端 0 条） | 门禁 `check-provision-containment.sh` 已断言；客户端第十七轮 `[Job] launch` **0 条**（LLM 全程未被触发 ⇒ 没有 Job 被起） | **✅ 已收口（2026-09-17 客户端实测）**：用户右键 `alice:job_launcher` ⇒ 客户端日志 `[Job] launch bot=tango kind=LUMBER center=23,64,207 radius=16 quota=2 maxTicks=3600` ✓，并看到 `[Job] 生产入口只搬运不发料（LUMBER）：斧=already_in_hotbar 镐=already_in_hotbar`（正是本行要的那条运行期证据）+ `task_execution_terminal … terminal=… quota_met` 与 `task_terminal_reason … terminalReason=quota_met` ⇒ **统一 Job 入口在真机上确实被走到**（等级 `WINDOWS_CLIENT`）|
| **R1-残** | `prod_budget_exhausted`（连锁破坏预算耗尽）分支**无客户端证据** | 代码已接入 `WriteBudget` 并按增量计账；但电池 `exec_chain` 用例只挖干净 3×3 矿脉（约 9 次破坏 ≪ `DEFAULT_MAX_BREAKS=64`）⇒ 新分支不会被现有场景触发 | **✅ 离线判据已补（2026-09-16，D-260）**：不再需要「造 65 格场景」—— 夹具用 `WriteBudget.setCaps(scope, Caps(1,0,0))`（**夹具专用**钩子）把预算压到 1，跑同一条矿脉 ⇒ `exec_chain_budget_refused` 断言「连锁当场停 + 拒绝如实上报 + 预算确实打满」；`single:mine_regression` PASS。**仍缺**：客户端目视（按现状**不必要** —— 本分支只有计数与上报语义，无物理/视觉差异）|

## §8.5 `survey/07` 方向审查的核实结果（2026-09-14）

- **用户要求"拉取审查 → 做完 T3 后看一遍"；我按修复纪律先逐条核实再动手 ⇒ 5 条里 2.5 条已过时**：
  ① `survey/06` §0.2 的 `LumberJob` 清障作用域缺陷 **2026-09-10 就修了**（`a8dd9ac`），现在字段是
  `clearedThisTree`，引用行号与当前文件对不上；② `survey/04` §0.2-3 的 "`requester` 全仓无填充"
  **已过时**（现 10+ 处传真实值）；③ `survey/07` §3.1 的 "Create/EC 静默读不出" **今天被本会话 B3a 修掉了**
  （`vanilla_only_out=37/37`）；④ `survey/07` §2.1 的四个数**逐字复现，全对**；
  ⑤ `survey/04` §2.2 的架构结论**不冲突**（实建 = `action/WritePolicyMatrix` 按域实现）。
  ⇒ **不要照单执行 §5 的清单**。全文与复算命令：`docs/reviews/2026-09-14-survey07-可行动条目核实.md`；
  两条过时条目已**就地**在 `survey/04` / `survey/06` 里加了纠错指针。
- **§3「夹具与生产分离」的范围修正**：审查说"移出生产包 / **移出发布 jar**"。实测**交付工件只有一个**
  （`build/libs/alice-1.0.0-1.20.1.jar`），它同时是①用户右键的游戏内测试物品 ②无头电池的载体
  ⇒ **"移出发布 jar"会把测试入口与电池一起移走**，按尺子第 1 问是**负收益**（抬高"从改一行到知道对不对"）。
  **建议只做包移动**（夹具挪出 `task/` 顶层），不碰交付工件。
- **流程教训**：审查类文档的"缺陷/缺口"条目必须能一键自检 —— 引用**符号名**（不是行号）、
  附**引入/修复它的 commit hash**、直接给**复算命令**；AI 收到建议**先核实再动手**、并把"已过时"明确回写。

## §9 T2 已落地 / T3 进行中（步骤 1 已落地 · 步骤 B3a 已落地）

- **✅ T2 无头回归已落地（2026-09-14，`4091694`）**：一条命令 `tools/headless-battery.sh core`，
  无真人，判决机器可读、退出码可判红。**闸门通过**：首轮 N=22（≥8），修复根因后 **`(passed=30/30 skipped=0) → PASS`**。
  报告全文 `docs/reviews/2026-09-14-无头回归通道T2-首轮实测.md`（含两个必须记住的坑：
  Gradle 吞退出码；服务端线程上 `System.exit` 会与 shutdown hook 互 join 死锁）。
  - **✅ 客户端已验证（第十八轮 20:14–20:18）= 决定性 B 轮**：`(passed=30/30 skipped=0) → PASS`、
    `entity_tick_missing` 0 条。聊天行实证真人被传送到 `(10000,-50,10000)`（离夹具区一万多格、
    **票零覆盖**）后才起电池 ⇒ **票修复升为 `WINDOWS_CLIENT`**。机器真跑的读数：`smelt_ticks=200 smelted=true`、
    `walk_state=DONE walk_ticks=41`、`energy 20000→19950 progress_ticks=199`。
    同时证实"以前的客户端全绿靠真人补票"（用户亦确认第十七轮全程站在测试区）。
  - **R2-残 已折进 T2**（用户 2026-09-14 裁定）：无头跑通即覆盖生产入口，不再单独开客户端轮次。
- **✅ T3 步骤 1 已落地（`bc2b1aa`）**：`SiteKind` 五态（`SINGLE`/`SHARED`/`MULTIBLOCK`/`INTERNAL`/`UNLOCATED`）
  + `hostTypeId` + `siteBlock()`；10 行 `noSite` 重分类（6 → `SHARED`、1 → `MULTIBLOCK`、3 → `UNLOCATED`）；
  新增两条防漂移断言（**行构造器普查**；`SHARED` 宿主存在且为 `SINGLE`、无自有方块、**恒 `READ_ONLY`**）。
  读数：`with_site_confirmed 46→52`、`no_site 10→4`、`shared_site=6`、`row_block_missing=[]`，四步 `machine_*` 仍 PASS。
- **✅ T3 步骤 B3a 已落地（2026-09-14）**：读取器 `MachineRecipeFacts.read(Recipe<?>, RegistryAccess)`
  **逐字段先原版语义**（`getIngredients()` / `getResultItem(access)`）、读不出再退模组名族；
  `Facts` 增 `inputOrigin`/`outputOrigin`/`chanceOrigin`/`divergent`/`readNotes`；`catch (Throwable ignored)` 已拆成
  "**名字不存在**（名族常态，不记）"与"**调用失败**（记异常类型）"。**判据一行未动**。
  - **实测值不变且被证明**：`vanilla_input=0` + `divergent=0` + `read_notes=0` ⇒ `Facts` 每字段与改动前逐位相同。
    两个新事实：**机器配方不实现原版 `getIngredients()` 的物品语义**（⇒ 名族对输入不是可选项）；
    **旧 `catch (Throwable ignored)` 在本模组集下是潜在风险、不是已发生的 bug**（对第 3 个模组才是真闸门）。
  - **顺带修掉测量通道缺陷（重要）**：`MachineProbe` 抽样**跨轮不稳定**（`RecipeManager.getRecipes()`
    迭代序不稳；`recipe_order_hash` 5 轮 5 值）⇒ 修前**同 jar 两轮读数就不同**（`input_readable` 65/64、
    `query_machine_route` 0/2），**旧读数全部不能当基线**。修法 = 两处遍历 + 自检选样全部排序；
    修后**同 jar 三轮 SUMMARY 逐字相同、108 条抽样 id 顺序逐字相同**。
  - 报告全文 `docs/reviews/2026-09-14-T3-B3a-读取器vanilla优先与探针确定性.md`。
- **✅ T3 步骤 A（探针可见性）已落地（2026-09-14）**：`MachineProbe` 的枚举来源由**表**改为**配方注册表**，
  `unmapped` 遍历域扩到**全部非原版类型**，并对表里 0 行的命名空间逐条出声。**根因**：旧实现
  `adoptedNamespaces()` = 遍历表行，而未登记命名空间**连枚举都进不去** ⇒ 「表里一行没有」与
  「这个模组不存在」**输出完全同形**（`unmapped=[]` 是**看不见**，不是"没有"）。
  - **实测（新读数，两轮逐字相同）**：`unmapped_total=19`；`create` **15 个类型 / 506 条配方**、
    `ExtendedCrafting` **4 个类型 / 25 条配方**，**表里各 0 行**。**已登记部分读数逐字未变**。
  - **买到什么**：①「接第 3 个模组」的工作量第一次是**实测值**（合计 **19 行** `MachineMap` + 每行
    方块/菜单/能力 + `machine-map.py` 的 `UPSTREAMS`），不再是估计；② M-4 的前置
    （"怎么摸到 EC 的机器类型"）自动解决 —— EC 的机器类型 = `compressor` / `ender_crafter` / `flux_crafter`
    （`table` 是工作台）；③ 反面事实：`refinedstorage` / `sophisticated*` / `oreexcavation` / `cofh_core`
    **没有任何非原版工作站配方类型** ⇒ 不需要表行（**当前模组集下的观测**，非永久断言）。
  - `unmapped` 仍是 **warn-only**（不判红：模组集可变，"我们 0 行"是待办）。报告
    `docs/reviews/2026-09-14-T3-步骤A-探针可见性.md`。
- **✅ T3 步骤 A2 + C（形状与查询层判决）已落地（2026-09-14）**：未登记命名空间做**纯只读**形状定点采样
  （同一份读取器；**只加新键、现有 8 个桶一个不动**），并拿抽样产出物去问生产查询层。
  - **读数**（两轮同 jar 逐字相同）：`unregistered_sampled=37`；`create` 29/29、`extendedcrafting` 8/8
    **产出可读**；`unregistered_notes=0`（两个新模组也没有访问器抛异常）。
  - **⭐ 承重结论**：`unregistered_vanilla_only_in=35/37`、`unregistered_vanilla_only_out=37/37`
    ⇒ **模组名族对 Create/EC 读出的产出是空的**，是步骤 B3a 的**原版路径**在读它们
    ⇒ **改前这批会被判 `MACHINE_RECIPE_UNSUPPORTED`**（19 个类型 / 37 条抽样）。
    **B3a 对接第 3 个模组不是卫生工作，是承重的**；而这个判决差异**在做步骤 A 之前根本无法观测**。
  - **M-4 已答**：`unregistered_query_no_recipe=0`、`query_reachable=6/6`、
    `verdicts={MACHINE_ROUTE=2, MISSING_INGREDIENTS=4}` ⇒ 查询层**没有**把"明明做得出来"报成 `NO_RECIPE`
    （站点如实回落成类型 id，因为表里没有该模组的行 ⇒ 不猜方块）。**边界**：37 条抽样 ≠ 全部 531 条；
    `MACHINE_ROUTE` 只表示"读得出"，执行侧仍如实拒绝。
- **✅ 【通道缺陷·已修】`partial_search` 非确定性（2026-09-14 实测并修复，用户批准 (A)）**：
  **同一 jar 两轮**，轮 1 `partial_search=FAIL`（`(29/30)`）、轮 2 `PASS`（`(30/30)`）。
  失败用例 `partial_with_prefix`（`SearchBudget.of(2,0L)`）在起步时 bot **未落地**（`on_ground=false`、
  `from.z=404` vs 正常 `406`）⇒ 规划器只扩 2 节点且无改善（`best=0.0`）⇒ **没有前缀可交**，
  返回 `SEARCH_LIMIT` —— **规划器行为正确，是夹具前提不成立**（`elapsedMs=0`，非时间问题）。
  **根因（假设）**：步骤间**无起点锚定/落地同步** —— ① `partial_search` 在电池里注册的起点参数是 `null`
  且夹具自称"不移动 bot"；② 电池对 premise 的 `on_ground` **只打日志不行动**
  （`RegressionBatteryTask.java:684-691`，而 `ownMenu` 不 ok 会 `closeContainer()`）。
  **已修（(A) 落地同步）**：`RegressionBatteryTask.startStep` 在 premise 之后加**有界等待**
  （`awaitGrounding`，上限 40 tick）—— setup（开作用域/建场景/发料）由 `setupDoneForIndex` 保证**只做一次**，
  落地则**每 tick 复检**；等不到就**如实继续**（不假装成功，也不把非确定性换成假红）。
  **验证**：临时探针**确定性地**复现触发条件（`transfer` 后把 bot 抬到 Y+3）⇒ 等待生效、
  `partial_search` **PASS**、整轮 `(30/30)`；删掉探针后 `(30/30) ticks=3414 → PASS`。
  **两个实测教训**（① 我自己的实现 bug）：第一版把重入分支写成直接 `return awaitGrounding(...)`
  ⇒ **复检被跳过、白等满 40 tick**（超时日志里 `onGround=`**`true`** 却仍在等 ⇒ 日志自己暴露了自己）；
  **② step 1 的空降是常态**：`clear_retry` 在 **8/8 轮**都是 `on_ground=false pos=6,64,67`
  （出生/传送后第一 tick）⇒ 第一步记 **info**、第 2 步起才记 **warn**（否则每轮都在喊狼来了）。
  **触发条件可 grep**：`premise step=… 起步时未落地`。
- **✅ T3 步骤 B4（能力登记）已落地（2026-09-14）**：`MOD_ADAPTER_PROTOCOL.md` §3 的散文判据**保留**，
  但它的**可执行部分**落地为 `tools/machine-map.py` 的**上游访问器能力登记双向对账** +
  （有 jar 时）上游存在性。读数 `capabilities=31 组=5 已用=31`，上游已取证 4/5（`forge` 不随模组目录分发）。
  **负测试**：改一个名字 ⇒ **3 条独立报错**（正向漏声明 / 反向没用上 / 上游字节找不到）⇒ `FAIL 问题=3`。
  **判据原有两个洞（实测）**：① `grep` 分不出注释 —— `getCraftSlots` 在 mods 目录 **0 个 jar 命中**、
  `getItem` **13 个命中**，而两者**只在注释里**；② "0 处模组字面量" ≠ 通用（`MachineCycle` 0 处字面量
  却写死 5 个上游访问器名）。**归属不靠猜**：由字节扫描上游 jar 反查。
  顺带修掉工具缺口：`find_jar` 原来把 glob 锚在开头 ⇒ 带中文前缀的 jar（`[矿石挖掘] oreexcavation-…`）
  **静默匹配不到**（症状是进 `unverified`，容易误读成环境问题）。
  报告 `docs/reviews/2026-09-14-B4能力登记与起步位置确定性.md`。
- **⚠️ 【(甲) 未验证】`ASCEND_NO_HEADROOM` 的可判读读数已就位，但尚未被触发**（2026-09-14）：
  失败码格式改为 `<稳定码>@<label>=<x,y,z>:<block_id>,…`（`to`/`from.up`/`from.up2`/`from.up3` 等），
  `ASCEND_INVALID_PRECONDITION` 同期加上；已核对全仓**没有**任何代码按整串比较这些码。
  加完连跑 **4 轮**，`ASCEND_NO_HEADROOM` **没有再现** ⇒ 等级 `IMPLEMENTED`+`COMPILES`，
  **不是** `SERVER_TESTED`。下次见到它 grep `ASCEND_NO_HEADROOM@`。
- **⚠️ 【通道缺陷·未解释·单次】`place_course+wall` 在 `ground2` 轮变红（2026-09-14）**：
  `MOVEMENT_FAILED code=ASCEND_NO_HEADROOM index=5 actualFoot=6, 61, 66`（前一段是 `PILLAR`，
  `replans=2/minReplans=1`）。**该子用例此前 13 轮连续 PASS**（det1…ground1），同轮 `cleanup` 干净
  （`回收我方临时方块=2 剩余=0`），其后 `place_course+disturb` PASS。
  **诚实边界**：我**没有**输入级证据（只有 `ASCEND_NO_HEADROOM` 与起始脚位，没有该处几何/世界状态对照），
  既不支持也不排除"是 (A) 的时序位移（早 36 tick）引入的"。**不盲修**（失败模式识别 Anti-Pattern 1：
  第三次失败就停下分析共性，不要继续单点打补丁）。
  **下一步（未做，需先定方向）**：给该子用例加**一条可判读读数**（`ASCEND_NO_HEADROOM` 时 dump
  目标格与其上方两格的方块 id），再跑 N 轮 ⇒ 区分"场景没建好"与"时序相位"。
- **⚠️ 【通道缺陷·系统性·已定形，2026-09-14】**：**今天 9 轮里 3 轮红，3 轮是不同步骤、不同码，
  但共同点是同一个 —— 起步时 bot 不在预期位置**：`partial_search`（空降 ⇒ `from.z` 差 2 格）、
  `place_course+wall`（`ASCEND_NO_HEADROOM`，脚位不在预期格）、
  `place_course+disturb`（**`STALE`** = `PathSessionStatus.STALE_START`，显式说"起步位置过期"）。
  后两例都在 `pathing` 子用例内、都 `replans=2`。**⇒ (A) 修的是电池"步骤"层；
  `pathing` 的"子用例"层有同一个缺口。**
  **决定：不继续单点打补丁**（Anti-Pattern 1：第三次失败就停下分析共性）。
  **候选根治（未实施，需你拍）**：`Step` 与 `pathing` 子用例都必须**声明起点**（`startFoot`）或
  **显式声明"我自带场景/复位"**，**不声明就响亮失败** ⇒ "依赖上一步残留"在构造上不可能。
- **⚠️ 【通道缺陷·系统性假设】**：上述各例的**共性**是"步骤起步时的**位置/世界状态/时序相位**"，
  即**电池的步骤集合整体不是时序无关的**（每步只依赖上一步留下什么：`partial_search`/`transfer`
  在电池里起点参数都是 `null`）。**候选根治方向（未做，需要你拍）**：把"起点"变成 `Step` 的**必填契约**
  —— 每个步骤要么声明 `startFoot`、要么显式声明"我自带场景/复位"，**不声明就响亮失败**。
- **✅ 【通道缺陷·2026-09-15 复核更新】**（上面三条的现状；全文与证据
  `docs/reviews/2026-09-15-夹具时机基准与DIAGONAL覆盖.md`，决定 **D-220**）：
  - **"起步位置"假设 → 实测排除**：改前 `single:pathing` 3/3、`core` 3/3 全绿，**场景行逐字相同**
    ⇒ 抖动**不复现**。（历史 3 红的 `/tmp` 日志**已被清空**，**无法复算** ⇒ 归因永久停在"候选"。）
  - **查到并修掉两处"声明与事实不符"**：① `+wall`/`+disturb` 的 `wallTick/disturbTick=30`
    **从未生效**（比较基准是任务级 `ticks` ⇒ 夹具总是"第一个执行 tick"就动手；放弃时还**假装做过**）
    ⇒ 改为场景局部基准 + **声明了夹具就必须断言它真的动手**（`FIXTURE_NOT_FIRED`）；
    ② `DIAGONAL` 覆盖**靠 ① 的偶发绕行凑**出来的 ⇒ 补 `dip_course+run` 变成确定性覆盖。
  - **查到通道真正的噪声源：敌对生物**。实测 `假人死亡: Alice was blown up by Creeper → 直接清除`
    ⇒ **无判决 `exit=3`**；同轮更早 bot 已被**推离预期格**（`feet=1,64,68`，起点 `z=66`）
    ⇒ `PLACE_NO_VALID_FACE` / `*_STALE_START` 这类"位置不对"的码都能由它造成
    —— **这是那 3 红目前最强的候选（仍未证）**。修法：`tools/headless-battery.sh` 把**无头服务端**
    设 `difficulty=peaceful`（`server.properties`，脚本自己的环境；`peaceful` 连存档里已有的怪物一起清）。
  - **修后判据**：`core` ×3 = **PASS 3/3**、`30/30`、`coverage=PASS`、怪物/死亡命中 **0**、
    **三轮 pathing 场景行逐字相同**。
  - **客户端不清怪物（用户 2026-09-15 裁定：先不动）**：客户端存档仍 `easy` 有怪物，**先观察**
    下一次客户端电池是否真被怪物干扰（判据：日志出现击退/位移 + 判决异常），**有证据再改**；
    届时候选 = `RegressionBatteryTask` 起始处 `kill @e[type=#minecraft:hostile, distance=…]`（要占一轮客户端验证）。
  - **✅ 物品侧的同类静默降级也已修（同日追加，D-220 附注）**：新增共用原语 `task/FixtureScript`
    （`wallPlan` 用 `MovementHelper.footCell` + 前方第 2 格；`notFired` 共用字段串）——
    因为**同一份夹具被写了两遍**且已分叉（时机基准、脚位算法、放弃策略），**分叉没有编译期信号**。
    物品侧现在：扰动放弃 ⇒ warn + `disturbGaveUp`；封路/扰动未生效 ⇒ 结果行带 `/FIXTURE_NOT_FIRED=…`
    且**任务 FAILED**（不再"干净地报 DONE"）。
    **验证分级**：共用原语 = `SERVER_TESTED`（电池侧调用它，`single:pathing` 场景行**重构前后逐字相同**、
    `core` ×2 PASS `30/30`）；**物品侧接线 = `COMPILES`**（客户端入口，验收点 = 下一次 R4 客户端轮次）。
  - **✅ 2026-09-15 第十九轮客户端实测（jar `462b10b5…`）**：电池 **`(30/30) ticks=3337 → PASS`**，
    `wall_placed at=5,64,66 sceneTick=30 pathIndex=3/9`、`disturbed sceneTick=32`、`coverage=PASS`、
    `FIXTURE_NOT_FIRED` 命中 **0**；**pathing 步 54 行场景日志与无头 `core` 逐字相同** ⇒
    **D-220 电池侧升 `WINDOWS_CLIENT`**（跨通道等价）。**T3 探针读数同轮复核**：42 字段中 41 个与无头逐字相同
    （唯一差异 `recipe_order_hash`，已登记为非确定值）⇒ **T3 升 `WINDOWS_CLIENT`**。
    ⚠️ **R4 夹具物品本轮没跑**（全会话 `[R4 Fixture]` 命中 0）⇒ 物品侧那条仍停在 `COMPILES`。
    证据 `.alice-supervision/client-tests/d220-t3-20260915/evidence/`。

- **T3 剩余（第 3 个模组之前必须做）**：**B3b** Port 化 `Facts`（每产出自带 `chance` + 长度断言 ——
  今天 `outputs`/`chances` 两个独立列表 + 空栈过滤 ⇒ **结构上无法配对**）；**B4** 把
  `MOD_ADAPTER_PROTOCOL.md:44-51` 的散文判据变成断言或删掉 + `UPSTREAMS[ns]["capabilities"]` 双向对账；
  **M-4 已答**（见上：`query_no_recipe=0`，查询层诚实）；
  **仍未做**：`MachineMap` 那 **19 行**（Create 15 + EC 4）与该模组的 `UPSTREAMS` 能力声明 ——
  那才是"接第 3 个模组"本身（**B3a 已被证明是它的承重前提**）。
- **T3 模组 #3 的数据模型**：**`create-1.20.1-6.0.8.jar` 与 `ExtendedCrafting-1.20.1-6.0.10.jar`
  已经在客户端 `mods/` 里**，且按审查 §3.3 会被**静默读错**（读取器把 Mekanism 特例当通则、不试 vanilla 接口；
  `MachineMap` 的 1 方块↔1 类型不变式让 Thermal 6 行**已是错事实**）。
  ⇒ **推任何新模组前必须先做 T3。**
  - ⚠️ T2 的新发现与 T3 有关：无头生产服务端**已经装着** create/ExtendedCrafting/refinedstorage/
    sophisticated* 全套（与客户端同构）⇒ T3 的"静默读错"现在有了一条**可复现的无头验证通道**。

---

## §5.7 「旗舰自主挖掘任务」作为框架验收范例（用户 2026-09-15 提出；审计已完成，**未动代码**）

**用户提议**：把"自主挖矿"（事前准备→区域选择→导航到区域→区域内搜索→选最优目标簇→连续挖掘→自动捡拾→
中途风险控制→维生监控→突发危险反应→局部战斗/逃离→放弃逃离→工具方块食物自补充→收尾返回待机区）
当作**范例**来验收决策-执行框架的完成度，并规划维生/风险子系统；**明确邀请 AI 回绝**，因为转向风险极大。

**AI 结论（三路只读审计，全文 `docs/reviews/2026-09-15-挖矿高级任务作为框架验收范例-完成度审计与规划.md`）**：
- **框架"单次闭环能用"**（`GoalDirector`/`LlmClient`/`DecisionSnapshot`/`CandidateMenu`/`GoalAction`/
  `JobRequest`+`JobLauncher`+`assignJob`/`PermissionGate` 均有调用链）；**"自主管一个长作业"不能**。
- 差距是 4 件事，不是某个功能：**① 挖矿无候选菜单（LLM 在猜位置）→ ② 长作业中段静音（无周期复评、
  节流丢弃不补发）→ ③ 父子额度零传递（缺镐/预算耗尽被聚合成"没矿"）→ ④ 无"转向"动作、任务树不持久化**。
- 16 项能力现状见评审 §2；**7 块承重缺失**按依赖数排序，第一位是**作业区抽象 + "进入作业区"**
  （这是"旗舰挖掘"与现有 `MineJob` 最本质的差别）。
- **最小补齐清单 M1–M6**（每件都有可判据）+ **维生/风险各 2 项最小件**见评审 §5/§4。
- **明确拒绝**：战斗 AI、簇优化、常驻矿区、饥饿/进食、暴露预算、`EdgeAdmission`（理由见评审 §6）。

**同时纠正三处过期文档结论**（本次三路审计发现，均已核实）：
`AI_PROJECT_STATE.md` 的"`RecoverabilityLevel` 全仓库 0 处读取 ⇒ 空实现"与"决策缝没有 LLM 调用/快照/词汇表/触发节奏"
**都不成立**（`RecoverabilityEvaluator`→`RecoverabilityPolicy.requiredFor`→`MovementSpec` 抛异常校验；
`GoalDirector` 等齐备）⇒ **STATE 已就地改**；`GoalProgress` 在源码中**不存在**（实际是 `TaskNode`），
`JOB_LAYER_DESIGN.md` 仍引用它；`RISK_SYSTEM_ISSUE_LIST.md` §2 P0-B 的同类结论也未同步。

**待用户拍板**：走 (a) 只出审计 / (b) 审计+M1–M3 / (c) 审计+维生最小件 / (d) 回绝。
**AI 推荐 (b) + (c)①②** —— 它同时补掉 §5.6 那个"缺失的可调风险层"的一半。

**M 线进度**：
- ✅ **M1 已完成**（2026-09-15）：`CandidateMenu` 加 mine 条目（复用 `MineCandidateSource`；矿石清单来自
  `MiningBudget.COMMON_ORE_TAGS` / `RARE_ORES` 这**唯一一份**定义，J-6）、`GoalAction.parseStartJob` 把
  `mine` 与 lumber/collect 同列为"**必须引用菜单 id**"且 `productTag` **只取菜单条目的 `block=`**（不采信 LLM 自写）、
  `radius` 自动覆盖目标距离、`JobLauncher.refusalReason` **前置拒绝**（用返回值，不抛异常 —— 异常会穿过
  `assignJob` 冒到调用方）、并删掉 `mineTargetFor` 的"**静默回落 IRON_ORE**"。
  **新电池步 `mine_menu`（MAIN ⇒ CORE 跑）**：`checks=11 failures=0`；无头 `core` **`(31/31) ticks=3404 → PASS`**。
- ✅ **M2 已完成**（2026-09-15）：`EventThresholds` 新增 `NO_PROGRESS` —— **可观测进度**的定义 = 任务 `Job.progressSummary()` + bot 脚位 + 背包指纹（三者任一变化即有进度 ⇒ **走路中的任务不会误报**）；**等容器交互时跳过**（等待态不是病症，同 `STUCK` 纪律）；**默认窗口 0 = 关**（对齐 `idleDecisionEnabled` 默认关），`setNoProgressWindow` 供配置/夹具；同 episode 只报一次，进度恢复即**重新武装**。新电池步 `no_progress`（MAIN ⇒ CORE 跑）+ `NoProgressCheckTask`（夹具用**自己的停滞**当被观察对象，不造假 Job；断言"关着不报 / 恰好一次 / 重新武装 / 只记录不通知 / 收尾复位窗口"）。
- ✅ **M4 已完成**（2026-09-15）：`DecisionSnapshot` 抽出可测的 `lastTerminalJson(record)`，
      `lastTerminal` 新增 **`failurePhase`** 与 **`failureDetails`**（后者**有界**：截到 `MAX_FAILURE_DETAILS=240`
      并如实标 `…` —— 因为 `MineJob` 会把逐候选 `rejected()` 拼进 details，不设上限会灌爆 prompt）。
      判据进 `llm_contract`（基-5 / J-4 同一个契约）：新增 `snapshot_failure_fields`，断言
      ① 字段齐（code/phase/details）② 超长 details **必须截断**且长度 = 上限+1 ③ **无失败时不许留 stale 字段**。
      **`llm_contract` 由 EXTRA 提到 MAIN**（同 M1/M2 的理由：门禁必须默认跑得到）。
- ✅ **M4b 已完成（MineJob 一条最小闭环，D-231，2026-09-15）**：`TaskFailureReport.oneLine()`（`code@phase`，有界 120）
      + `TaskNode.leaf(..., lastFailure)` 重载 + `MineJob` 记录子阶段失败（并在子任务置空后仍把该子阶段摊进树里）。
      判据挂在既有 `mine_no_tool` 步的 `doneWhen`（归因对 **且** 树里有 `lastFailure` 才判过 ⇒ 否则预算耗尽判红）；
      **反向对照**已做（短路那支 ⇒ `mine_no_tool=FAIL`）。CORE `(35/35) ticks=3832 → PASS`。
      ✅ **其余三个 Job 也已接上（D-234）**：口径统一为 `TaskNode.finished(...)`（成功不留行 / 失败带 `code@phase`），
      四个 Job 共用；判据 = `decision_contract`（MAIN ⇒ CORE 跑）里的三条纯逻辑断言，反向对照会红。
      ⚠️ **端到端覆盖缺口（如实登记）**：`lumber_failure` 的六个用例**没有一个**以"子任务结束且失败"收场
      （无候选/全被拒 = 没建子任务；背包满/换块 = DONE；超时 = 子任务还在跑；缺斧 = 前置检查先拒）
      ⇒ 我第一版给它们加的"Job FAILED ⇒ 树里必有失败行"判据**是错的**、被夹具打回、**已撤回**
      （错的判据比没判据坏）。lumber/collect/region 三条端到端口径目前只到 `IMPLEMENTED`。
      **补法**：加一个"内层 `MineTask` 真的失败"的确定性用例（够不到 / 超出 `MAX_GAIN_PER_TREE` 的树干）。
- ✅ **M3 已完成**（2026-09-15）：`MineJob.attemptFailures` 结构化（`record AttemptFailure(pos, code)`），
      新增 `deriveTopLevelReason(base)` —— **逐码精确比较**（不再 `contains()` 拼接串）：
      全 `no_suitable_tool` ⇒ **`tool_missing`**、全预算码 ⇒ **`write_budget_exhausted`**、
      只剩 `target_replaced` ⇒ **`stale_target`**；只对"目标被尝试过却没一个成功"的总括码归因。
      新电池步 `mine_no_tool`（MAIN ⇒ CORE）：与 `mine_job` 同场景同 Job，**唯一差别是不发镐**，
      判据 = `terminalReason == tool_missing`（Job 自身 FAILED 是预期的）。
      ✅ **M3b 已完成（D-232，2026-09-15）**：两条映射各配确定性夹具并**首次被观测** ——
      新电池步 `mine_stale`（夹具注入身份复检恒假 ⇒ 全 `target_replaced` ⇒ `stale_target`）与
      `mine_budget`（`WriteBudget.setCaps` 把本步作用域预算压 0 ⇒ 全 `WRITE_BUDGET_EXHAUSTED` ⇒ `write_budget_exhausted`）；
      实测确认**词表是对的**（真实路径产的就是 `BUDGET_CODES` 里的大写码）。反向对照：映射短路 ⇒ **两步各判红**。
      CORE 35 → **37 步**（`(37/37) ticks=3916 → PASS`），归属表已同步。
- ✅ **M 线全部完成**（M1/M2/M4/M3 + **M4b**/D-231 + **M3b**/D-232）。剩下的只有**客户端验证**（用户侧）
      与**其余 Job 的 `lastFailure`**（`LumberJob`/`CollectJob`/`RegionLumberJob`，同一接法）。
- ✅ **`decision_contract` 的档位矛盾已解决（D-233，2026-09-15）**：它自己的类注释写着"任何改动都跑得到"，
  而 `CURATION` 把它放 **EXTRA** ⇒ CORE 跑不到。按 M1/M2/M4 三次同型先例提到 **MAIN**
  （200 tick、纯逻辑、不调 LLM、不改世界）⇒ CORE **37 → 38 步**（`(38/38) ticks=3847 → PASS`）。
  若用户要的是"改文档承诺"而非"提档位"，改回一行即可。

**用户裁定（2026-09-15，第二次）**：**确认 A1–A3、启动 M 线、§9 待实测按 AI 推荐（先 #4）** ⇒ 已落 **D-221**。
**M 线顺序**：**M1 → M2 → M4 → M3**（采纳勘测员 §8.2 的 M4/M3 对调），其后 M6。
**⚠️ M5 撤销**：核查发现 `BotManager.tryRecoverUnfinishedTeardown` **已在假人生成时被调用**（紧接
`reportRestartState`）⇒ 本审计 §1.3 与 `survey/08` §8.2 的"全仓 0 调用者"**均不成立**（D-221 附注一）。

---

## §5.8 S-5「维生最小件」落地记录（2026-09-15，D-226）

**动因**：压缩断点后的推荐路线（HANDOVER §6，用户 2026-09-15 认可"继续"）。维生是**唯一零电池步的子系统**，
而三处缺口都是真的：溺水/着火**只报不拦**、`HazardState.previousHealth` **只写不读**（掉血不可见）、
`EventThresholds` 类注释自称的"第三档 `DANGER`"**代码里不存在**。全文见 `docs/AI_DECISIONS.md` D-226。

| 子项 | 状态 | 判据 / 证据 |
|---|---|---|
| ① 软危险（`LOW_AIR`/`ON_FIRE`）纳入否决（宽限 10 tick + **必须有出口**） | **`SERVER_TESTED`** | 决策表 15 项 + 封闭场景 7 项断言；无头 `single:survival_exit` `checks=38 failures=0 → PASS` |
| ② 掉血可见（`previousHealth` 的第一个读者 ⇒ `DANGER` + `delta=`，冷却 40 tick 合并） | **`SERVER_TESTED`** | 真实扣 2 点血 ⇒ `[Threshold] 掉血 DANGER … total=2.0` + `[Events] DANGER … delta=2.0`；冷却窗口内**恰好 1 条** |
| ③ "否决了却没出口"变成可判读事实（`exit=none decision=stop` + DANGER） | **`COMPILES`**（该分支要**真否决**才会走到 ⇒ 电池里不可达，见下） | 代码路径 + 客户端日志可读；真人侧未观测 |
| ④ 维生进电池：新步 `survival_exit`（**BASELINE**） | **`SERVER_TESTED`** | `PROFILE=CORE baseline=15 main=20 extra_skipped=9 (passed=35/35 skipped=0) ticks=3686 → PASS` |
| ⑤ 顺带修：`startSurvivalExit` 的"排除自己"改用**脚位格**（`SurvivalSystem.footCell`） | **`SERVER_TESTED`**（半砖对照断言） | 半砖上实测 `foot=206,64,306 / blockPosition=206,63,306`；排除脚位格 ⇒ 无落点；**对照**（排除 `blockPosition`）⇒ 非空 |

**⚠️ 结构性限制（记住它，别把它读成"整条否决链已验证"）**：电池步的**会话任务就是电池自己**，
维生否决会 `complete()` 掉会话任务 ⇒ 电池里**不可能**跑"真危险 ⇒ 真被否决 ⇒ 起逃生"这条端到端路径
（会**打死整轮电池**，连 SUMMARY 都没有）。**能**离线验的是决策 + 落点 + 逃生任务能走到 + 事实登记；
**只能真人验**的是"否决真的发生了"（`BotSession` 那一侧的接线）与 no-exit 分支：
入口 `alice:survival_exit_check`（右键 = 窒息硬危险；**潜行右键 = 着火软危险**），**待客户端轮次**。

**教训（新的一类：判据的"前提"必须自证，否则会因错误的理由变绿）**：本节 `survival_exit` 首轮实测
`/fill` 在**区块未加载**的坐标上一格都没落下（bot 直落 64 → 50），而"半径内没有落点"这句判据
在**虚空**里**照样成立** ⇒ 假绿。修法两层：① 场景改为 **bot 到位后由夹具自己建造**（玩家票先把区块拉起来）；
② 几何**四层前提自证**（站在空腔里 / 脚下有真支撑 / 头位可穿 / 半径内确实无落点）。
第二次实测又暴露"边站边建"会把 bot **埋进方块**（`y=63` 那种假分歧）⇒ 建完**再传送一次**。

**M 线/维生的挂账（不在本轮）**：`write_budget_exhausted`/`stale_target` 映射仍**未被观测**（M3b）；
`decision_contract` 档位（EXTRA vs 类文档承诺）仍待策展裁定；`tree[].lastFailure`（M4b）。

---

## §5.9 【真缺陷·待裁定】挂起的传输把 bot 的 `assign*` 通路**永久堵死**，而且**完全静默**（2026-09-15 客户端实测发现）

**发现路径**：用户在客户端点 `alice:survival_exit_check`（硬危险 = 窒息）后"**bot 没反应**"，
怀疑"窒息机制到底存不存在"。读客户端日志 + 解析存档账本后定位（**不是维生逻辑的问题**）。

**事实（逐条有证据）**：

| # | 事实 | 证据 |
|---|---|---|
| 1 | **窒息机制存在且工作** | `logs/latest.log`：`维生监测: bot=tango hazard=SUFFOCATING duration=1/21/…/181 air=300 health=20.0 pos=66, 64, 104`（每 20 tick 一行，持续 9 秒+），并**真的掉血**：`[Threshold] 掉血 DANGER … total=1.0 health=19.0` |
| 2 | **否决分支一次都没执行** | 全日志**没有** `任务因维生危险中断`、**没有** `[Survival] 逃生出口`、**没有** `walkto-68_64_106` 的 `[PathingStats]`/`[PathRetry]`（连规划都没发生） |
| 3 | **根因：该 bot 被存档账本永久阻塞** | `saves/新的世界/data/alice_transfer_ledger.dat`（NBT 解析）：bot `1ae26630-…`（tango）共 72 条传输记录，其中 **11 条 `SUSPENDED`**（`code=server_restart`、`manualTakeover=1`、**`location=NOT_MOVED`**、`count=3 minecraft:iron_ingot`，最近一次 `suspensionStartedTick=614073`）⇒ `TransferLedgerData.blocksBot(tango) == true` |
| 4 | **阻塞链（静默三层）** | `blocksBot` ⇒ `BotSession.replaceTaskIfRunning()` 返回 `false` ⇒ `BotSession.assignWalkTo(BlockPos)` 是 **void、什么都不做、不留痕** ⇒ `BotManager.assignSurvivalExitCheck` **无条件返回 true** ⇒ 物品仍打印 `[SurvivalExitCheck] 就位 …` ⇒ 夹具的**前提**（"bot 手上有个活"，维生否决只在 `task != null` 时检查）**从未被证明** |
| 5 | **`BotSession.tick` 因此提前返回** | `if (task == null) return;` 在否决判据**之前** ⇒ 危险照旧被检测（第 1 条）但**永不否决** ⇒ 用户看到"bot 站在压顶格里不动、也不救自己" |
| 6 | **电池/Job/真实逃生不受影响** | 它们走 `session.beginTask(...)` **直连**（`RegressionBatteryTask` 入口 `BotManager:710`；`assignJob` `BotManager:578`；`startSurvivalExit` 也是 `beginTask`）⇒ 所以 19:0x 那轮客户端电池仍能 35/35 全绿，而 `assign*` 家族（walk/follow/place/transfer/road/**维生自检**）静默失效 |
| 7 | **没有任何现成解除手段** | `expireSuspensions` 超时后**仍然**写成 `SUSPENDED`（只改 code 为 `manual_takeover_required`）；`/alice transfer-abort <id>` → `abort()` 对 `SUSPENDED`/`BOT_INVENTORY` 条目**也仍然**是 `SUSPENDED`；唯一的终态写入者 `TransferTask.transition(...)` 需要**同 requestId 再跑一次传输**，而那条路先被 `blocksBot` 挡住（先有鸡还是先有蛋） |
| 8 | **来源是"客户端中途重启"** | 11 条全是 `code=server_restart`、`location=NOT_MOVED`（**从未进过 bot 背包**，源箱 → 终点箱，铁锭 ×3）；`TransferFixture.ledgerPolicies` 用的是 `new TransferLedgerData()`（**内存临时实例**）⇒ **不是夹具污染**，而是真实传输被重启打断 |

**结论**：这不是"窒息机制不存在"，而是**存档账本把 tango 的 `assign*` 通路永久堵死 + 夹具不验前提**两件事叠加。
**它同时暴露一个更一般的问题**：`blocksBot` 拦的是"**任何**任务替换"，连纯通行 `WalkToTask` 都拦
（本意是"别在有未决传输时动这个 bot 的物品"）。

**✅ 零代码立即绕开（本轮就能继续验维生）**：关闭游戏后把 `saves/新的世界/data/alice_transfer_ledger.dat`
**改名/删除**——该文件按设计"**只存证据、不存物品状态**"（`TransferLedgerData` javadoc）⇒ 删掉不动物品，
tango 立刻解除阻塞。代价：丢 61 条 `VERIFIED` 传输审计记录（其中 11 条挂起记录本来就无法解除）。

**修复选项（**待用户拍板**，未动代码）**：

- **A（小·必做）前提自证 + 拒绝可见**：`BotSession.assignWalkTo` 改返回 `boolean`；
  `assignSurvivalExitCheck` 在"没派上活"时如实返回 false 并说清原因；物品侧改为显示真实原因。
  ⇒ 与电池步 `survival_exit` 的"前提自证"同一条纪律，把"**静默成功**"这一类堵死。
- **B（小）**：`blocksBot` 拦截时打一行 warn（现在完全静默，用户与日志都毫无线索）。
- **C3（推荐，直击本症状）**：`suspendUnfinished` / `expireSuspensions` 对 **`location == NOT_MOVED`**
  （从未进过 bot 背包）的条目直接落 `ABORTED`，不再要求人工接管；只有 `BOT_INVENTORY`/`IN_TRANSIT_BOT`
  才挂起阻塞。理由：本次 11 条挂起**全是 `NOT_MOVED`**，没有任何"需要人工接管"的东西，
  却把 bot 的 `assign*` 永久堵死。
- **C1（可选兜底）**：给一条显式人工接管通道（如 `/alice transfer-resolve <id> confirm` ⇒ `ABORTED`）。
- **C2（可选，设计取舍）**：`blocksBot` 只拦"**会写物品**的任务"（transfer/craft/collect），
  不拦纯移动/逃生/诊断 —— "任何任务替换"这个口径太粗。

**AI 推荐**：**C3 + A + B**（最小且直击），C1 作为兜底；C2 需要你定（它放宽的是安全口径）。

**✅ 用户裁定（2026-09-15 晚）：采纳 `C3 + A + B`，C2 不放宽（保守）。**

**已落地（D-227）**：
1. **C3**：`TransferLedgerData.suspendUnfinished` / `expireSuspensions` ——
   `location != BOT_INVENTORY` 的条目**直接落 `ABORTED`**（新码 `aborted_no_bot_inventory`，`manualTakeover=false`），
   只有 `BOT_INVENTORY`（= 物品确实在 bot 身上）才挂起。**已被旧版本堵死的存档会自动自愈**：
   启动时重跑 `suspendUnfinished` 即把这 11 条 `NOT_MOVED` 挂起结清成终态。
2. **A（前提自证 + 拒绝可见）**：`BotSession.assignWalkTo` 由 `void` 改 **返回 boolean**；
   静态 `BotManager.assignWalkTo` 透传；`assignSurvivalExitCheck` 派活失败时 **返回 false 并打日志**；
   物品侧改为显示**真实原因**（"bot 手上有未结清传输 ⇒ 换假人 / 重开世界自动结清"），不再打印骗人的"就位"。
3. **B**：`replaceTaskIfRunning` 被 `blocksBot` 拦住时**打一行 warn**（带条数与首个 requestId/state/code/location）。
   新增只读 `TransferLedgerData.blockingSummary(UUID)` 供命令/物品入口复用。
4. **判据（零新增电池项）**：挂到**既有 BASELINE 步 `transfer`** 的 `ledgerPolicies` 夹具里
   （`TransferFixture`）：`suspendUnfinished` 后 `location != BOT_INVENTORY` 的条目必须
   `ABORTED` + `code=aborted_no_bot_inventory` + `manualTakeover=false`。
   **反向对照已做**：把该断言取反 ⇒ `single:transfer` 立刻 `FAIL`（证明判据真的能红，不是摆设）。

**✅ 持久化待办关闭（D-235，2026-09-15 深夜）**：新增两个**持久化实验开关**（默认关、挂在既有命令上）——
`ALICE_KEEP_ALICE_DATA=1`（保留世界里的 `alice_*.dat`）+ `-Dalice.headless.saveOnHalt=true`
（停机前同步存档）。实测：① 启动 `结清：11 条 → ABORTED`，存档后解压 `world/data/alice_transfer_ledger.dat`
**读到** `state=ABORTED` + `code=aborted_no_bot_inventory` + `manualTakeover=false` ⇒ **结清落盘** ✅；
② 以上轮存档为母本再启动 ⇒ 结清**只剩 1 条**（那 11 条已 terminal）⇒ **跨重启幂等** ✅；两轮 `transfer` 均 `PASS`。
⚠️ **方法教训**：按 NBT 字符串计 `state=SUSPENDED` 得 1938 条，看着像"只结清了一小部分" ——
**是假的**（`transitions[]` 历史与当前状态同名字段）⇒ **字符串计数 ≠ 数活状态**，要用系统自报的 `released` 计数。

**⚠️ 明确没做（越界会被现有断言挡住）**：`TransferTask.survivalInterrupted` / `menuFailed` / `suspend`
这三处**仍在跑动中**写入 `NOT_MOVED` 挂起 —— 那是**有意设计**且**已被夹具断言**
（`taskInterruptPolicies`：`SUSPENDED` + `NOT_MOVED` + `manualTakeover=true`）⇒ 本轮**不动**。
后果（如实记下）：一次"传输中被维生打断"仍会让该 bot 在**本次会话内**被挡住 `assign*`
（现在至少**有 warn 可查**，不再是静默）；跨会话则由 C3 在启动时结清。
**✅ 已修（D-254，2026-09-16）**：`expireSuspensions`/`suspendUnfinished`/`abortTransfer` 原先用
`server.getTickCount()`（**进程内**计数、重启归零）而落章用世界时间 ⇒ **差值恒为负 ⇒ 运行中产生的挂起
永远不过期**（`manual_takeover_required` 降级是死代码）。现在统一走 `TransferLedgerData.clockNow`
（世界时间）+ 只能传服务端的重载 ⇒ 调用方**没法**再自带时钟；并有可执行规则
（`tools/check-transfer-clock.sh` R1，时钟混用即构建红）。
**✅ 解除通道已落地（D-255，2026-09-16 用户裁定「甲」）**：`/alice transfer-resolve <request> confirm`
（必须打全 `confirm`）⇒ `resolveManual` 落 `ABORTED`（码 `resolved_by_operator`），证据里带**只读对账**
（`botHeld=<n>/<expected> item=<id>`）与**谁解除的**；**不移动任何物品**。`transfer-abort` 那条路保持保守
（继续挂起保护）。判据：既有 `transfer` 步的内存账本语义 + **端到端走真实命令通道**（含"没打 confirm 不许解除"），
另有结构规则 R3 双保险。**§5.9 至此收口**（唯一剩余项 = 没人确认时仍永久阻塞，这是**有意保留**的保守口径）。

**§5.9 验证（本条的所有证据）**：
- `single:transfer` **正向 `PASS`**；把新判据取反 ⇒ **反向 `FAIL`**（`verdict=FAIL exit=1`）⇒ 判据真能红；
- 无头 **CORE `(35/35) ticks=3633 → PASS`**；`check-all.sh` = 9 PASS + 1 预期 WARN；
- **自愈实测**：无头默认 `rm -f world/data/alice_*.dat`（**有意的**确定性设计）会把账本清掉、掩盖这条路径 ⇒
  我**临时**关掉那一行跑了一次（跑完立即还原，脚本无 diff），用**与客户端同形**的污染账本 ⇒
  `[Transfer] 启动结清：11 条**未进过 bot 背包**的未完成传输直接落 ABORTED（code=aborted_no_bot_inventory，不再挂起阻塞）` ✅
- ⚠️ **未验证两点**：① 结清结果的**落盘**（无头 `halt` 不存档 ⇒ 观测不到；客户端正常退出会存，
  且**即使不存盘，每次启动都会重新结清** ⇒ 症状仍被修掉）；② `assignWalkTo` 返回 false 这条**接线**
  目前只有编译级 + 真人侧可见。
  **✅ 已落地（D-254，2026-09-16）**，但**原提案不能照抄**：从电池步内部真调 `assignWalkTo` 会触发
  `replaceTaskIfRunning()` 的 `clearTask()` ⇒ **把正在跑的电池步任务自己替换掉**（步以 `CANCELLED_REPLACED` 收场）。
  改为：门禁判据提成纯函数 `TransferLedgerData.refusal(ledger, botId)`，夹具用**内存账本**判它（零世界写入）；
  "每个替换型派活都过门禁"做成可执行规则 R2。反向对照：翻转判据 ⇒ `single:transfer` FAIL；删掉门禁调用 ⇒ R2 红。

---

## §5.10 假人只跑了**半个原版 tick** —— 火焰渲染/火焰伤害/空气（溺水）全缺失（2026-09-15，客户端实测逼出）

**用户现场反馈**：「bot 有反应了，但看不到任何窒息/燃烧的效果，也看不到受伤的效果。」

**已确认事实（逐条可查）**：
1. **客户端日志（服务器权威）**：两种模式都真的跑通了 —— 硬（窒息）×3、软（着火）×2，每次都
   `任务因维生危险中断 → [Survival] 逃生出口 refuge=… 距 1.000 格 → SurvivalExitTask terminal=COMPLETED`，
   起飞点 `66,64,104 → 66,64,103`（硬）/ `64,64,102 → 65,64,102`（软）。**维生否决链本身没问题。**
2. 整个着火实验里 `health=20.0` 一路不变，`[Threshold] 掉血` **一次都没出现**。
3. **无头探针（临时，已删）**：`setSecondsOnFire(6)` 后 `remainingFireTicks=120 / isOnFire=true`，
   **16 tick 后仍是 120**，且 `sharedFlag0=false`（= 客户端渲染火焰的唯一输入没立起来）。
4. **字节码取证**：
   - `Entity.baseTick()`：`remainingFireTicks--`、每 20 tick `hurt(onFire,1)`、**并在服务端按
     `remainingFireTicks>0` 调 `setSharedFlagOnFire`**；`Entity.isOnFire()` 客户端分支读的正是这个共享标志
     （`remainingFireTicks` **不同步**）⇒ 标志不置位 ⇒ **客户端画不出火焰**，且**同段代码里的火焰伤害也不发生**。
   - `LivingEntity.baseTick()`：`super.baseTick()` + 火焰免疫/`clearFire` + **`getAirSupply/decreaseAirSupply/
     increaseAirSupply`（溺水/空气）**。
   - **`ServerPlayer.tick()`（记账 tick）里没有任何 `Player.tick()`/`baseTick()`/`aiStep()` 调用**；
     真正的 `Player.tick()→LivingEntity.tick()→Entity.tick()→baseTick()` 在 **`doTick()`** 里
     （`Player.tick()` 那句 `invokespecial` 落在 934 行起的 `doTick()` 内），而 `doTick()` 由
     **`ServerGamePacketListenerImpl.tick()`（字节码 78）** 驱动。
   - Alice 自己的 `BotPlayer` javadoc（第 82-83 行）写着：假人的连接是 `FakeConnection`，
     「**不在 `ServerConnectionListener` 的连接表里（`tick()` 永不被调用）**」。
   ⇒ **结论**：假人只跑 `ServerPlayer.tick()`（记账）+ Alice **手动补的 `aiStep()`**（物理），
   **原版的 `baseTick` 那条链从未执行**。
5. **所以三件事全部解释清楚**（并且修正我先前的错误假设）：
   - **看不到燃烧**：不是渲染问题，是"身上根本没着火"（服务端字段为真但共享标志不置位 ⇒ 客户端无火焰），
     而且**火焰伤害也不发生**（和标志在同一段代码里）。
   - **看不到受伤**：着火实验里**确实一点伤害都没有**。⚠️ 我先前说"peaceful 回血抵消了火焰伤害"是**错的** ——
     实测是"根本没有伤害"。窒息那次看到的 `20 → 19` 之所以发生，是因为窒息伤害在
     **`Entity.move()`（`checkInsideBlocks`）**里，而 `move()` 由 Alice 手动补的 `aiStep()` 走到 ⇒ 那条路是通的；
     且它**只在移动时判定**，所以速率很低（站着不动就不再掉）。
   - **看不到窒息效果**：原版对**第三方视角**没有任何窒息视觉（"脸埋方块"的贴脸遮罩只画给本地玩家镜头），
     第三方能看到的只有掉血红闪 + 音效，而伤害本身既稀有又小。
6. **连带缺失（同一根因，尚未逐条实测）**：空气不消耗 ⇒ **`LOW_AIR`（溺水）在产线不可达**
   （S-5 刚加进否决链的那一档，目前只能靠夹具直接改 `airSupply=0` 才测得到）；
   传送门冷却、冻结（细雪）、`walkDistO`（脚步声）、`Player.tick()` 那半（食物/饥饿、自然回血、
   药水效果计时、`updateIsUnderwater`）同样不会跑。

**修复选项（待用户裁定，本轮未改代码）**：
- **A. 忠实补全**：`BotPlayer.tick()` 改为调 `this.doTick()`（真玩家由网络层驱动的"真身 tick"），
  并**删掉手动 `aiStep()`**（否则 `aiStep` 跑两遍 ⇒ 双推进）。
  覆盖最全（含 `Player.tick()` 那半：食物/回血/效果计时）。**风险**：物理推进顺序/次数变了，
  直接冲击 D-174 那套实测基线 ⇒ 必须重跑 physics/pathing 相关电池 + 真人复测。
- **B. 最小补丁（推荐先做）**：在 `BotPlayer.tick()` 的 `super.tick()` 与手动 `aiStep()` **之间**插入
  `this.baseTick()`（= `LivingEntity.baseTick()` → `Entity.baseTick()`）。
  **顺序与原版一致**（原版 `LivingEntity.tick()`：baseTick 在 offset 9、aiStep 在 offset 179），
  且 `ServerPlayer.tick()` 里确认没有 baseTick 调用 ⇒ **不会跑两遍**。
  恢复：火焰（渲染标志 + 伤害）、空气/溺水、传送门、冻结、脚步声等。
  不恢复：`Player.tick()` 那半（食物/自然回血/药水效果计时/水下判定）。
- **C. 只登记不修**：把"假人没有原版 tick"写进台账/技能库，S-5 的 `LOW_AIR` 档标注"产线不可达"。

**判据（修复轮一并落地）**：`SurvivalExitCheckItem` 软模式（着火）后断言
`bot.sharedFlagOnFire() == true`（访问器已就位：`BotPlayer.sharedFlagOnFire()`）+ 断言
`remainingFireTicks` 会递减 + 溺水：让 bot 入水后 `airSupply` 真的下降。
本轮的临时断言已按纪律**降级为信息行**（`[Survival] 已知限制：着火时 sharedFlag0=false …`），
避免让 CORE 门槛常红；修复落地后它必须变回 `check(...)`。


**§5.10 的修复与验证（2026-09-15 同日，D-228，用户裁定 B）**：
- **改法**：`BotPlayer.tick()` 在 `super.tick()` 与手动 `aiStep()` 之间插 `this.baseTick()`
  （顺序与原版一致：`LivingEntity.tick()` 里 baseTick 在 offset 9、aiStep 在 179；且经字节码核对**不会跑两遍**）。
- **判据（4 条进 CORE，挂既有 BASELINE 步 `survival_exit`，checks 38 → 45）**：
  ① 着火后 `sharedFlagOnFire()` 为真（客户端画火焰的唯一输入）；② `remainingFireTicks` 递减；
  ③ 相位期间**最低血量**低于点火前；④ 出现带 `hazard=ON_FIRE` 的掉血事件；
  ⑤（另一相位）入水后 `airSupply` 真的被消耗 ⇒ 溺水产线可达。
- **反向对照**：注掉 `this.baseTick()` ⇒ 恰好 ①~⑤ 全红（`120→120`、`20.0→20.0`、空气 `20→20`）⇒ 判据真能红。
- **门槛**：`single:survival_exit` PASS（45/0）；CORE `(35/35) ticks=3701 → PASS`；`check-all.sh` ✅。
- **期间踩到并修掉的假绿**：`fillBlocks(...)`（断言 `/fill` 改动方块数）立刻抓到
  `desc()` = `BlockPos.toShortString()`（**带逗号**）被拼进命令 ⇒ 非法坐标 ⇒ `/fill` 静默 0 改动
  （新增 `xyz()` 专供命令）；另"`/fill` 必须先传送后执行（未加载区块静默无操作）"也被再证实一次。
- **待查**：CORE 里出现"伤被治回去（18→20）"的**治疗来源未定位**（疑似前序机器/药剂步骤的残留效果；
  补 `baseTick` 后**药水效果开始真的 tick**）。判据已改为"期间最低血量 + 掉血事件"并在相位前归一化生命基线。
- **新待办（本修复带出的真实风险）**：细雪**冻结**（`freeze` 伤害）现在会真的发生，但 `HazardType` 里
  **没有冻结档** ⇒ 否决链覆盖不到。另有：药水效果现在会**正常到期**（此前永不失效）—— 这是原版语义，
  但值得知会（若有人依赖"给 bot 的效果永久有效"，行为已变）。


**§5.10 客户端验证（2026-09-15 晚，用户真人实测）**：
- **火焰看得见**（共享标志真到了客户端并由原版渲染）✅；**走动/放置/挖掘与以前一致** ✅；无新崩溃。
- 着火期间 `掉血 DANGER … hazard=ON_FIRE` 出现在 `duration=1/41/81/121` ⇒ **每 20 tick 稳定 1 点**（原版节奏），
  血量停在 19.0 ⇒ **客户端没有回血**（与"`Player.tick()` 那半仍未补"一致）。
- 窒息×2 / 着火×1：否决 → 逃生出口 1 格 → `SurvivalExitTask COMPLETED` ✅。

**本次修复带出的后续项（按我的优先级）**：
1. ~~**bot 的血只减不增**~~ ❌ **我说错了，本条作废（D-230 实测）**：`Player.aiStep()` 里本来就有
   `health < max && tickCount % 20 == 0 ⇒ heal(1)`，而 `aiStep()` 在现状路径里一直被手动调用
   ⇒ **自然回血一直在跑**（1 点/秒、不吃饱和度；真人客户端那次 `19.0 → 20.0` 就是它）。
   `FoodData.tick()`（吃饱和度的回血、饥饿）确实只在 `Player.tick()` 里 ⇒ 那才是缺的部分。
   **② 的最终处置：不启用**（开关 `alice.bot.vanillaTick` 默认关、保留为实验开关）—— 收益只剩饥饿/`updateIsUnderwater`，
   代价是物理顺序变化 + **新增"饿死"这一失效模式**（Alice 无"饿了就吃"行为）；A/B 两态 CORE 均 `(35/35)`。
2. ~~**细雪冻结没有危险档**~~ ✅ **已修（D-229，2026-09-15）**：新增 `HazardType.FREEZING`（软危险，
   阈值 `ticksFrozen ≥ 60`），`survival_freezing` 理由码，电池 checks 45 → 55
   （含"全冻后真的掉血"）；反向对照（阈值改 2000）②③④ 精确变红；CORE `(35/35) ticks=3830 → PASS`；
   真人入口 = `alice:survival_exit_check` **疾跑+右键**（无出口 ⇒ 不否决 + 全冻掉血）。
3. ~~**`LOW_AIR` 现在产线可达**，建议补一条真人可复现的溺水入口~~ ✅ **已覆盖（2026-09-15 晚真人实测）**：
   D-229 的入口 `alice:survival_full_check`（**一次右键、零参数**）里就有入水相位 —— 客户端日志
   `.alice-log` 归档 `logs/debug-1.log.gz` 里可见
   `把 bot 放进水里（眼睛在水里=true），空气设为 20；期望：原版 tick 真的在消耗空气` +
   维生监测 `hazard=WATER_CONTACT duration=1 air=299`，整轮 `SUMMARY checks=55 failures=0 → PASS`
   ⇒ **溺水这条路在真实 tick 循环里成立，且已在真人客户端跑过**。
4. ~~**"治疗来源"未定位**~~ ✅ **结案（D-230）**：不是模组、也不是效果，而是
   **`Player.aiStep()` 自带的自然回血**（`health < max && tickCount % 20 == 0 ⇒ heal(1)`，不吃饱和度）
   —— `aiStep()` 一直在被手动调用。CORE 里那次 `18 → 20` 还额外叠加了夹具相位前的 `normalizeVitals()`。
   无需再让用户跑 `/data get entity`。
5. **行为变化需知会**（已在 D-228 的报告里告知用户）：药水效果**现在会正常计时并到期**（此前永不失效）；
   同理生效的还有：空气会消耗、火焰/冻结/传送冷却会走、脚步声回归。**仍需真人留意**（可选观察：
   给 bot 一瓶短时效果，看它是否按时消失）。
6. 本次实测日志里有**几条 LLM 请求超时**（`HttpTimeoutException`/`Connection reset`）⇒ 那几次决策请求没拿到回复；
   与本次改动无关，但会影响真人测试时"bot 反应慢/不反应"的观感，建议网络侧留意。
7. **⚠️ 已知间歇（2026-09-16，无头 CORE 一轮实测）**：`survival_exit` 的**着火相位**偶发"没造成伤害" ⇒
   2 条红（`着火必须真的造成伤害（点火前 20.0，期间最低 20.0…）`、`火焰伤害必须被读成可判读的掉血事件`）。
   证据：18:00 那轮日志 `[Survival] 真实着火 16 tick 后电池仍在跑`（火只烧了 16 tick 就没了 ⇒ 没掉血），
   同一相位在 17:59 / 18:08 两轮 PASS（`checks=120 failures=0`）⇒ **与本轮代码改动无关的间歇**，
   读到这两条红时先复跑一轮再怀疑回归（要不要给该相位加"点火自证/补火"另议）。

---

## §5.11 水里逃生：**已收口**（2026-09-15 提出 → 2026-09-16 epic 关闭；D-236 起登记，D-253 收口）

**✅ 收口状态（D-253，2026-09-16）**：IN 四条全部达成 + **真人复核通过**（用户「符合我的预期」；客户端 `checks=122 failures=0`、pathing 全场景 0 FAIL）⇒ 本 epic **关闭**。明确不做：游泳 Movement / 落水免伤（D-058）/ 水柱成本模型 / 水面理由码。仍未做（已知边界，不是待办）：池底出发 `UNREACHABLE`、入水物理专做、水面以下水平潜游。
> 以下为过程记录（保留追溯，不再作为待办清单）。

**用户问题**：没有游泳 Movement，水里逃生是怎么处理的？**答（事实 + 代码位置见 D-236）**：

1. **内核连"进水"都规划不出**：`MovementHelper.java:52/62/179/196`（流体源格不算支撑 ⇒ 水里那格永不是合法脚位）、
   `FallExecution.java:186`（落点及上一格必须无流体）⇒ **没有任何含水路线**。⚠️ **按 D-236 更正后的准确说法**：
   ① "不进水"的精确机制在 `core/search/SurfaceMovementProvider.java:127-130`（TRAVERSE/DIAGONAL/ASCEND **刻意跳过**
   "目的格或头格是流体"）；② Baritone 有 `MovementTraverse.java:88-96`（水走成本）与 `MovementFall.java:102`（落水），
   其中"FALL 不做落水"**不是漏登记**——它是 **D-058 的用户决策**；③ Baritone 的**垂直**水位能力在
   `MovementPillar.java:77-82`（水柱分支，且要求"已经在水中"），不是 `MovementAscend`；
   ④ `MovementCapabilities.canEnterFluid` 全库无读者。
2. **维生侧原本"静默淹死"**：`LOW_AIR` 与着火/冻结同档 ⇒ 无落点 ⇒ `HOLD_NO_EXIT`（不否决、继续干活）。
   **已修（D-236）**：新增 `ABANDON_NO_EXIT` —— 溺水 + 无落点 ⇒ **放弃任务**（干净收尾 + 大声登记 +
   `GoalDirector.onSurvivalInterrupt`），不再静默跑到死；着火/冻结仍 `HOLD_NO_EXIT`（可能自愈）。
3. **判据**：既有 BASELINE 步 `survival_exit` 新增 `DEEP_WATER` 相位（自建封闭水牢 ⇒ 半径 8 无干燥落点）：
   `WATER_CONTACT`（空气够）→ **`LOW_AIR`（空气点 0 的 1 tick，真水里被认出来）** → `ABANDON_NO_EXIT` + 判定码 →
   对照（着火/涉水语义不变）。**checks 55 → 65**；反向对照两条新判据精确变红。
   ⚠️ `BotManager` 那条"放弃任务"的**接线在电池里追不到**（放弃 = 结束会话任务 = 打死电池）⇒ 纯判据 + 编译级；
   端到端要真人（与 §5.9 ③ 同款结构性限制）。

**✅ "乙"已完成（D-237，2026-09-15）**：溺水 + 无落点 + **浮得上去** ⇒ 新判决 `FLOAT_UP` ⇒
`SurvivalFloatTask` 按住跳跃上浮（头出水即松手；头出水且空气 ≥100 ⇒ DONE，240 tick 预算内没成功 ⇒ 失败并封禁
1200 tick ⇒ 下次直接放弃任务）。判据挂既有 `survival_exit`（新相位 `OPEN_WATER`：露天水池 + 端到端驱动自救任务）
⇒ **checks 65 → 78**，实测 `上浮自救成功（tick=44 air=102 y=102.81）`；反向对照两条新判据变红。
**接线只观测到一次**（monitor 真判 `LOW_AIR` ⇒ `decision=float_up` ⇒ `SurvivalFloatTask COMPLETED`），
那一轮 `verdict=no_verdict`：**被打断的是电池本体**（电池=会话任务 ⇒ 真判决必打死它）⇒ 这条不能做成绿色电池步。

**⚠️ 新证据（真实案例）**：`isRefuge` **不检查可达性** —— 水池第一版忘了拆天花板 ⇒ 落点选到 `240,105,306`
（我池顶那一格，距 4 格、干且可站）⇒ `WalkToTask … PLAN_UNREACHABLE … walk_no_path` 失败。
**✅ 候选改进已落地（D-238，2026-09-15）**：出逃生之前先跑一次**真规划预检** ⇒ **只有 `UNREACHABLE` 才算"没有出口"**
（`SEARCH_LIMIT` 按"未知 ⇒ 允许尝试"）；软危险遇"去不了的落点"不再杀任务（`HOLD_NO_EXIT`），
`startSurvivalExit` 把这种情况如实登记成 **`exit=unreachable`**（区别于 `exit=none`）。判据挂既有 `survival_exit`
（新相位 `UNREACHABLE_REFUGE`，checks 78 → 87），反向对照精确变红。

**✅ ③ B：逃生准备金已落地（D-241，2026-09-16，用户批准的提案 B 第一步）**：轴=任务信封（`WriteEnvelopes`，推导事实非名单）
+ `PathRequest.survivalEscape`（放置+破坏+PILLAR，不含 DOWNWARD/FALL）+ 策略表 P-23/P-24 + 阶梯用法（只在纯通行
`UNREACHABLE` 时升档）+ 上限 8/8。判据挂既有 `survival_exit`（新相位 `SHAFT_ESCAPE`：2 格深竖坑 ⇒ 真垫出来），
**checks 87 → 99**，反向对照三条判据精确变红。**未做**：~~自动回收（TEMP 已声明但未接 `scaffoldRemoval`）~~
⇒ **已定案：不做自动档，改玩家许可（D-245，2026-09-16）**；水渠搭桥与预算守卫场景、准备金"从 64 里做减法"。

**✅ ④ 水里那档的结论（D-242，2026-09-16）—— 排期因此改了**：写准备金在水里**规划得到、执行不了**
（`PILLAR` 执行器是跳起-落地式，水里 `wastedJumpLandings`）⇒ 水里自救的正解不是写授权，而是
**"水柱"这一小块内核工作**：对齐 Baritone `MovementPillar.java:77-82`（+ 其执行段"swimming up a water column"）
= **纯输入上浮、不放置**；**不需要**动合法位置集/成本模型（坑底"水+实心底"本就是合法脚位）
⇒ 比整个"丙"小得多，且**夹具已就位**（`FLOODED_SHAFT` 相位即它的判据，当前以 tripwire 记录"水里执行失败"）。
另：Q3 的"额度守卫"在当前几何下不可构造（半径 8 与额度 8 耦合；准备金自己装额度）⇒ 改为"量出来的三条事实"。

**✅ 水里那档的"垂直"部分已做（D-243，2026-09-16）**：`PILLAR`/`ASCEND` 在水里改为**按住跳跃上浮**
（只动执行器那一支，不动合法位置集/成本模型）；`FLOODED_SHAFT` 那条 tripwire 按设计翻红后**翻成正断言**
⇒ `水里逃生：终态=DONE`（bot 真的从灌水竖坑里出来了）。**仍未做**：整列是水时不放方块的省料分支、
**浮在水面时起不来**（合法位置集问题，仍是丙的其余部分）、水平游/蹚水、落水（D-058 定案不做）。

**✅ 省料分支已做（D-244，2026-09-16）**：`PILLAR` 的**水柱那一支**（起点格与目的地格**都是水**）改为对齐
Baritone `MovementPillar.java:150-161`（"swimming up a water column"）+ `:77-82`（`LADDER_UP_ONE_COST`）——
**上浮、不放方块**；完成口径也照 Baritone 改成"**脚位到格即成功**"（水里既无 `onGround` 也无支撑
⇒ D-026 的"已落地"口径**永不成立** —— 这正是 D-242「规划得到、执行不了」的另一半根因）。
判据挂既有 `FLOODED_SHAFT`（**checks 112 → 118**，零新增电池步）：**脚位在水里时一次都没放方块**
（逐 tick 取 `WriteBudget.places` 增量 + 按当时脚位是否在水里归因）+ **干地反向对照**（干燥竖坑仍靠放置上来）
+ 前提自证三条（坑里 2 格都是水 / 水上那格不是水 / 计划第一段就是水柱 `PILLAR`）。
**实测比预期更强**：整段逃生**一个方块都没放**（`PILLAR` 水柱上浮 11 tick + `ASCEND` 从水里跳上干地板 14 tick）
⇒ **D-242 的结论落地验证**：水里自救**不是授权问题**（准备金只是让"写类 Movement"能被规划出来，额度一分没花）。
**仍未做**：① 成本模型没跟（Baritone 水柱给 `LADDER_UP_ONE_COST`，Alice 仍算 `PILLAR_COST`，只影响选路）；
② `PILLAR` 仍属写类 Movement ⇒ 纯通行档连"不写世界的水柱上浮"都生成不出来（过严但安全，不碰 D-076/P-01）；
③ **浮在水面时起不来** / 水平游/蹚水 —— **2026-09-16 已查清现成方案**（用户要求）：Baritone **没有 `MovementSwim`**，水位 = `canWalkOnPosition:432-448`「下面也是水且上面也是水 ⇒ 算支撑」+ `waterWalkSpeed ≈ 2.0×` + 复用 `MovementTraverse`（按需 `JUMP`/`SPRINT`），⇒ 拆成**切片 A（蹚水）**与**切片 B（深水浮着：那条 `canWalkOn` 例外 + 完成口径）**；
**✅ 切片 A 已完成（D-247，2026-09-16）**：实测先证明**蹚水今天就能走**（新夹具 `water_course`：水沟横跨全场、纯通行 5 段全 `COMPLETED`、零写入），并补上"水里的步子按水速计价"（`WATER_TRAVERSE_MULTIPLIER = 7.25`，按 Alice 实测 42~45 vs 5~7 tick；Baritone 结构、Alice 数值）⇒ 选路不再偏爱穿水、段预算不再只有实际的 1/7；判据 `water_course` + `water_course+cost`（期望值从计划自身推导）+ 反向对照精确变红。**切片 B 勘查完成但规划那半已回退（D-248，2026-09-16）**：水位例外（只认水面格）+ 三个执行器的浮着完成口径写完并在**单跑**下全绿，但**CORE 里引出未解释的回归**（逃生换了条更便宜的"破墙 + 升到水面格"路线，其最终段在健康检查时刻读到的支撑是 `Air` ⇒ `SEGMENT_FUTURE_BLOCKED` ⇒ FAIL），按纪律**回退**、把证据与三个候选解释登记在 D-248；**根因已查清（D-249，同日）**：那格 `Air` 不是未加载区块，而是**同一条计划自己的 `BREAK_AND_ENTER` 清掉的头位格**（清脚位+头位两格，Baritone 同款）⇒ **真问题是「搜索不模拟自己的写入」**（Baritone 同样没有；Alice 的前瞻健康检查提前抓到，而重规划时 bot 被水推回坑里 ⇒ 从同一起点重算出同一形状）；三条修法（有界自我写入感知 / 计划级自洽校验+收窄重试 / 维持现状并补不需水位的复现场景）**待拍板**；规划已能贴着水面走（`deep_pond_course+cost`：7 步 / 4 个水步都按水速计价）；但**"从岸上走进深水 ⇒ 游过去"实测跑不通**（入水动量把 bot 带到池底、水面才是合法位置 ⇒ 从池底判 UNREACHABLE），以 `deep_pond_course+floor` **常绿登记**（补上了就变红提醒改文档）。要跑通需再拍板：(a) 入水前先跳一下 /(b) 会话级跨段持续浮着（Baritone 的客户端游泳耦合在 Alice 侧要显式做）；落水仍按 D-058 不做。对照全文 `docs/reviews/2026-09-16-水位处理现成方案对照.md`；落水仍按 D-058 不做。
**⚠️ 2026-09-16 更新（D-250 实验后的准确状态）**：有了计划自洽校验（§2 K-8）之后，切片 B 的**规划**那半已能给出**自洽**计划（`[SelfWrite] … 禁掉那条边重搜` → `重搜 1 次后计划自洽`），**执行**那半仍失败 —— bot 从浮着的水面格被推/落回坑里（`ASCEND_STALE_START`，`actualFoot=341,99,306`）⇒ 切片 B 现在**只卡在「水里跨段保持浮着」**这一条，需单独拍板：(a) 入水前先跳一下 / (b) 会话级持续浮着。

**✅ ④ 回收时机已定案（D-245，2026-09-16 用户裁定）**：逃生放置**不做自动回收** —— 自动拆会把 bot
**重新关回坑里**（危险再触发 ⇒ **逃生循环**）。回收交**玩家许可**：`/alice restore` / `alice:restore_check`
（已存在的 `RestoreScopeTask` 路径，"建拆同权"的权限没变，只改了**时机**）；待拆在账本 `pending` 里一直可见、
收尾会提示。补偿做法：**负向门禁**（`SHAFT_ESCAPE` 相位断言"逃生结束后方块仍在 + 账本仍 `TEMP`"，
取反恰好两条变红 ⇒ checks 118 → 120）。§5.11 原来那条"未做自动回收"**就此关闭**。

**🎯 本 epic 的「做完」定义 + 收口检验（2026-09-16 用户确认收口方式=甲；用户提醒：AI 不要把"已完成"当成已完成）**：
- **IN（做到这些就算完）**：① 蹚水 1 格 ✅（D-247）；② 水柱上浮、出水零放置 ✅（D-243/D-244）；
  ③ **深水浮着：能规划 + 能执行跨段** ✅（**D-251 落地**：水位支撑例外 + 浮着完成口径 + 段间浮着 ⇒
  `deep_pond_course` 从「现状登记 refused」改成**执行判据**并 `COMPLETED`，纯通行、零写入）；④ 逃生准备金 ✅（D-241/D-245）。
- **OUT（明确不做，不再挂账）**：游泳 Movement（Baritone 也没有 `/projects/reference/baritone` 只有 8 个 Movement 类）；
  落水免伤（D-058 用户定案）；水柱成本模型 `LADDER_UP_ONE_COST`（只影响选路，不影响能力）；水面专用理由码（`UNREACHABLE` 够用）。
- **✅ 收口检验（必须有一次真人检查，否则不算完）**：客户端**一次右键**跑 `alice:survival_full_check`
  （零参数，122 条判据）⇒ 聊天里应看到 `SUMMARY checks=122 failures=0 → PASS`；**外加**对"溺水逃生闭环"的肉眼确认：
  水里能出来（不静默等死）、干地竖坑仍靠**放置**上来、水里那两段**不放方块**。
  **AI 在此之前不得声明"溺水逃生已闭环"**；客户端现象以用户观察为准（日志只作佐证）。

**仍挂账（各一行，触发条件不变）**：① 水面专用理由码（现有 `UNREACHABLE` + 失败理由够用）；
② ~~出口**列表**（现在只验最近那个，最近不可达就说不可达，不找更远的）~~ ⇒ **已判定基本为空，关闭（D-246，2026-09-16）**：
纯通行档下"最近那个不可达"**等价于**"根本没有纯通行出口"（证明在 D-246），只有**等距并列**且其中一个是
纯不可达时才会误判 —— 那要求人为的不可破几何（基岩），**正常游玩里构造不出来** ⇒ 不建机制（D-240 同款尺子）；
③ **B：维生自救的受限写授权**
（搭桥/垫柱子出水；机制现成但需放宽 D-076，**待用户拍板**）⇒ **一页决策稿已出（2026-09-16）**：
`docs/authz/PROPOSAL_B_survival_write_authorization.md`（**v2，2026-09-16 按 `survey/09 §3` 合并**：
挖矿「通道挖掘」与溺水「自救」是**同一堵墙** ⇒ 只问一个问题"路径规划器可以在什么条件下写世界"；
轴改成**任务信封** `MovementCapabilities.changesWorld()`，机制改成"**逃生准备金**"（预算预留 + 专属 WriteReason）；
含 5 个待拍板问题、可红判据（水渠 + 竖井 + 上限守卫 + 反向对照）、边界声明：**不解决深水浮着**、**不替代丙**）；
未批准前代码与 `AUTHZ_REGISTRY.csv` 都不动。

**❌ 勘测员的"更便宜前置"B1 已被**实测 + 推演**判定无可测量收益 ⇒ 不落地（D-240，2026-09-16）**：`isRefuge` 与 TRAVERSE/ASCEND 的目的地生成**共用同一组谓词** ⇒ "可通行且可规划"的格子**就是**落点，于是 bot 的**上一格永远**是"最近且可达"的落点 ⇒ D-238 的单候选半径扫描本来就会挑中它（L 形走廊实测 trail/radius/escape **三者同格**）。轨迹长 ≠ 能过去（每格仍要过 D-238 预检）⇒ 它只覆盖"退后一步被切断"那一档，而那正是缺水位 / 缺 `PILLAR` / 缺写授权的档。同理 §9.2 的 B3（逃生路径岩浆检查）也基本"构造性满足"（Movement 校验器本就拒绝岩浆格）。**⇒ 批准的 Q5 直接落到 B4（D-239 五条已定案）。**

**（历史建议，已被上面结论取代）勘测员的更便宜前置（`survey/09` §8/§9.2 B1）：**「**原路返回**」——全仓**无任何轨迹记忆**，
`SurvivalExitTask` 的候选格来源从"8 格内最近安全格"改成"**先看来时轨迹，再看半径**"：不碰 D-076、不需新 Movement、
不需流体预测，且**正好能防住 D-238 抓到的那个真实案例**（几何落点成立但规划不可达），并天然缓解 §4.4 的"自挖竖井无出口"。；④ **丙**（内核水位位置 + 垂直水位移动 + 成本模型）。

**已知限制（如实登记，属"丁"）**：**水里逃生 = 不支持**。真实行为：浅水/岸边 8 格内有干燥落点 ⇒ 走过去
（`isRefuge` **不检查可达性**，所以可能"派了活但走不到"）；**深水浮着 ⇒ 放弃任务**；只涉水 ⇒ 不动作。

**复核触发（要不要做"乙"上浮 / "丙"按 Baritone 补内核）**：① 真实出现水下作业/水下目标；
② 出现"bot 掉进深水后放弃任务"的真实案例；③ 真要补时，先做 Baritone 两处水位分支的逐行对照 + 成本模型评估。

---

## §5.12 保护区层级 / 安全区 / 任务区（`D-338`，2026-09-19 登记；⚠️ **零代码**，本表就是落地清单）

> 口径见 `AI_DECISIONS.md D-338`（七条用户裁定 + 现状核对表）。这里只放**可开工的件**与触发条件。

| # | 落地件 | 现状 | 依赖 / 触发 |
|---|---|---|---|
| 1 | `SafeZoneData`：**安全区子集**（必须落在保护区内）+ `internalChunks()`（**四邻腐蚀**的内部区块；1 区块/散点区 ⇒ 空集 ⇒ 退化"进区即到"） | ✅ **已落地**（2026-09-19，`D-338` 附注一）：`safeChunks`+`safe_chunks` NBT + `SafeDeclare` + `declareSafe`/`clearSafe`/`isSafe`；不变量三条路都堵（拒声明 / `unclaim` 连带清 / `load` 丢孤儿并计数+summary）；`internalChunks` 纯函数 + 维度级入口 | 无（已完成） |
| 2 | **返程目标链**：归位点 > 安全区内部 > 保护区内部；`SafeReturnTask` 终点与到达判据从"最近认领格 / 区块边界"改成"最近**内部**格" | ❌ 未做（今天 = `nearestClaimedCell` + `isClaimed` 边界判据，已绿） | **依赖 1（已满足）** ⇒ 可开工；⚠️ 会重写 `safe_return` 夹具的"进区块即到"断言（按 `D-332` 单独一片、先红后绿）；"内部区块为空 ⇒ 退化"这条路径要有断言 |
| 3 | `WorldModLedger` **break 条目**（谁/何时/拆了什么/**原状态**；默认不恢复；**限保护区**） | ❌ 未做（今天只记我方放置；`Entry.previousState` 已具恢复前提） | 依赖 1（要判"在保护区内"） |
| 4 | **区域级权限 / 预算授予面** + **任务区声明**（任务自声明，随 `scopeId` 生灭；目标内 `KEEP` / 目标外提权+预算+`TEMP`） | ❌ 未做（今天授予全是**动作级**） | 需先拍板第 5 行 |
| 5 | **权限等级阶梯**（AI 提案：`L0 只读` / `L1 临时脚手架(≤8 放置)` / `L2 工作面(目标内 KEEP + 目标外逐块提权)` / `L3 全权(仅玩家显式)`） | ⏳ **待用户拍板** | 拍板后才能做 4 |
| 6 | 玩家预先声明的**长期任务区**（"这是我的林场/工地"） | ⏳ 下一阶段 | 4 落地后 |
| 7 | **玩家文案统一**：claim 命令/勾选 UI 的"保护区"= 正名；返程/`/alice status` 侧改口 | 🟡 **一半已落地**（2026-09-19）：`/alice protect list` 现在打印「当前位置：保护区=…/安全区=…」+「保护区（父类）/ 安全区（子类）：summary」；返程侧文案仍写着"安全区"（属第 2 件一起改） | 第 2 件落地时一并收口 |
| 8 | **归位点**（玩家设定，返程最高优先；形态未定：每 bot / 每区、命令 / 物品） | ⏳ 形态待定 | 与 2 同批 |
| 9 | **勾选界面的安全区模式**（可多选/框选；今天只有零参数命令） | ⏳ **未做**（用户 2026-09-19：**操作逻辑要先给他审核**再实现） | 命令入口已可用（`/alice protect safe claim|unclaim`）⇒ 不阻塞 |

**已成立（不用做，只是别误删）**：候选层已排除保护区内目标（`MineCandidateSource:184` / `LumberCandidateSource:68`，
理由码 `protected_area`）+ 动作层再拒（`BlockBreakSafety:52`）⇒ "挖矿只扫保护区外"与"保护区内不可破坏"今天为真。

**复核触发**：① 安全区"必须在保护区内"不够用（想在危险区旁单独建避难屋）⇒ 重开"独立声明"；
② 内部区块判据在长条/环形区表现不好（到家后仍贴边）⇒ 换判据；
③ 任务区随 scope 生灭若导致"中途重规划丢授权" ⇒ 补持久化；
④ ⚠️ **1×1 / 2×1 / 2×2 / 3×2 没有内部区块**（四邻腐蚀下）⇒ 小基地上"向中心靠"看不到效果
（退化成"进区即到"）。⛔ **"改八邻"已实测证伪**（2×2 在两种口径下都为空；矩形区域两者结果相同；
8 邻在非矩形上更严）⇒ 出路只有：⒜ **质心退化**（空集时取"离质心最近的认领格"）或
⒝ **块级内缩 K 格**（不能物化成区块集合）。**待用户拍板**。
