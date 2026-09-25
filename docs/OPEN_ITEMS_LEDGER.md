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
| **⭐ `survey/22 §1`：挖矿形态三件差 + 两条已知失败链** | `MineTask`（一格）vs `MineJob`（quota 组）差三件：**目标来源**（调用方给格 vs 自己扫 `SCAN_RADIUS=24`、只找已暴露）、**作业形态**（单目标 vs 多目标 `attempted` 去重）、**判据**（挖掉这格 vs quota + 产物真进包）。⚠️ 事实：收集**已是"挖完再一起捡"**（`MineJob:309-311`），真正该拆的是**归属耦合**（`startCollect()` 的 `origin=firstMined` ⇒ 收集要横穿通道走回去）；`expectedIds` 是 **UUID 白名单不是物品过滤**（`CollectDropsTask:97,470`）⇒「不捡石头」需要**新一层"按物品/来源过滤"**；⚠️ 两条失败链：① 挖 8 捡 5 ⇒ 整个 Job `FAILED(product_not_collected)`（`MineJob:333-337`）② ~~`inventory_full ⇒ finish(DONE)` ⇒ 通道挖一半报成功~~ ⚠️ **2026-09-19 复核=非缺陷**（`D-335`）：`MineJob:235` 的容量检查**每 tick 都跑**（不受"只跑一次"约束）⇒ 本来就是作业中守卫；报告把伐木的作业中检查形状安到了挖矿头上；缺的是**矿侧门禁**，已补 `mine_inventory`（先红后绿 ✔）<br>⭐⭐ **2026-09-20 复核（`D-345`）：§1.3 的「`origin=firstMined` ⇒ 收集要横穿整条通道走回去」不成立** —— `origin` 在 `CollectDropsTask` 里**只进 `target()` 上报**（`:199`），真正的簇锚点来自**种子落物的当前位置**（`:554`），追取以 **bot 为心 ≤32 格**（`:504`）⇒ **该待办作废**。<br>⭐ **真机制（三条一起）**：① `MAX_CHASE_DISTANCE=32` 硬墙 —— 每 tick 刷新候选时距离 >32 立即 `retire(id,"too_far")`，且 `retired` **从不清空**（`:504-505`/`:492`/`:521`）⇒ **永久**不再考虑（不像"够不着"能靠加高挽回）；② `MineJob` **挖满 quota 才起收集**（`:309-311`）⇒ 收集第一 tick 站在**最后挖的那格** ⇒ 更早的产物已在 32 格外；③ `collectPhase` 要求 `gained >= minedCount`（`:331-337`）⇒ 如实 `FAILED product_not_collected`。⭐ **今天就能咬到**：`32 < 2 × SCAN_RADIUS(24) = 48`（扫描球里能放两颗相隔 >32 的产物）—— 这条不等关系就是缺陷的不变量。<br>⭐ **取证夹具已落地**：`task/MineDropRangeCheckTask` + 模块 `mine_drop_range`（步 `mine_far_drop`，**故意红**：`expectedVerdict()=FAIL`、不进电池，机制见 `D-345` / `BATTERY_CURATION.md §3.1`）。实测 `retire … reason=too_far itemPos=3240,101,2000` + `collected=1/2` + `地上剩余=1` ⇒ `checks=10 failures=2`（**恰好只有那两条 ⭐ 判据红**）。✅ **已修（2026-09-20，`D-346`，用户选「只做 A」）**：上限从写死的 `32` 改成 `max(32, 2 × scope.currentRadius())`（`MineJob` 下 = **48** = 它自己的作用域直径）。⭐ 理由不是「该不该设上限」，而是**这个上限比它自己的作用域还小**（裁定见 `D-074`：N 由实现定，口径不变）。**A/B 同一夹具**：修复前 `clusters=1 / collected=1 / 地上剩 1 / FAIL` → 修复后 `clusters=2（远件 3240 也被捡）/ collected=2 / 地上 0 / PASS`。夹具**已翻面**：搬进 `MiningModule`（EXTRA，步名 `mine_far_drop`）+ CURATION 登记，临时模块 `mine_drop_range` 已删。验证：`single:mine_far_drop` PASS · `module:mining` PASS(8 步) · **CORE 51/51**（271 s）· `check-all` 16 PASS/0 FAIL |
| **⭐ `survey/22 §5.2`：矿工"到达+返回"的三处空档 + 验收口径（勘测侧判断，未拍板）** | ① ✅ **已定案（2026-09-19，`D-327` 附注一）**：安全终点 = **最近认领区块内离 bot 最近的那一格**（`SafeZoneData.nearestClaimedCell`，纯查询不读方块）—— 原「"返回安全区"需要一个安全终点坐标**，而 `SafeZoneData` 是**区块集合**（全高度 2D 认领）**不提供"回哪一格"**；② 目标**扫描/选择**是判据的入口（新失败来源，**未验**）；③ ✅ **已落地（2026-09-20，`D-347`）**：「缺可测量判据 —— 到达率/返回率/平均 tick/世界改动数**没人测过**」⇒ 现在**有出口了**：`bot/TaskMetrics`（累计台账，任务收发口 + `WriteBudget` + 任务自报「到达」三处喂）→ `alice:bot_report` 一行可读；连线判据 `CheckHarness.verdict` / `HeadlessBattery` 判决前各一道（账没动 ⇒ 整轮红）。现场取证夹具 `mine_run_metrics`：连跑 3 次真 `MineJob` ＋ 1 次反向对照 ⇒ **到达率 3/4**、平均 tick 99、世界改动 11（账与 `WriteBudget` 逐位一致）。⚠️ 本次是「**仪器 + 仪器自己的可证伪性**」；真实存档里的数字要另一轮场景去量。作者问法：「目标已确定时，几乎能保证到达并返回安全区吗？」| ⚠️ **① 的终点规则已被 `D-338` ③ 扩展**（2026-09-19）：改成**优先级链 归位点 > 安全区 > 保护区** + **内部区块**（自适应安全范围，不再"到区块边界即到"）；见 `§5.12` 第 1/2/8 行。|
| **⭐ D-336：斜向上升那一格 ✅ 已补 + 门禁** | `PLACE_STEP_AND_TRAVERSE` 的 `dy` 从 `{0,-1}` 扩到 `{+1,0,-1}`（几何复用同一 helper；定价基类 `dy>0 ⇒ ASCEND`）。**不动 `ASCEND`**（`D-334`：`changesWorld()` 是信封分档唯一口径）。先红后绿实测：**挖矿信封**（不含 PILLAR）修前**不可达** → 修后 1 条边 `REACHED`；通用信封从 `TRAVERSE+PILLAR`（2 边/成本 6.0）降到 **1 边/5.0**；纯通行信封验证**未被渗透**。门禁 `place_step_diagonal`（EXTRA，三用例）|
| **⭐⭐⭐ D-337：粗目标已实现 + ⚠️ 内幕：内核搜索会读未加载区块（红线 D-132 违反）** | `GoalSpec.exactFoot()` + `GoalNearXZ`（Baritone `GoalXZ`/`GoalNear` 的半径版）+ 守卫只对精确目标生效 —— **已实现，但生产未接线**。⚠️ 门禁沿路采样实测：粗目标不被前置拒后，搜索**把 224→384 的 14 个区块同步加载了进来**（新被加载采样点=6）⇒ ① 违反红线 D-132；② 真世界 = tick 线程上的同步区块生成/磁盘 I/O ⇒ **"远距离一规划就卡"的又一层真因**（`D-331` 同类）。此前未暴露是因为**精确目标的 `GOAL_NOT_LOADED` 守卫替真正的加载守卫背了锅**。**✅ 已修 + 门禁（2026-09-19，先红后绿）**：机制查证 = 那条跨区块边闸门是**后置**的，而读发生在它**之前**且更远（候选生成 ≤2 格 / `hazardAdjacencyPenalty` ≤2 格 / 粗目标 `isInGoal` 纯算术）⇒ 读先加载、门随后放行 = **自增强泄漏**（`D-337 附注一`）。修法 = `MovementContext.READ_FOOTPRINT_RADIUS=3` + `readFootprintLoaded()`（只 `hasChunkAt`，自身零副作用）+ 扩展前读脚印闸门 + 边界状态语义（`boundaryBlocked>0` ⇒ 有前缀给 `PARTIAL`、无前缀给 `SEARCH_LIMIT`，**绝不 `UNREACHABLE`**）。红 `failures=1`（`newlyLoaded=6`）→ 绿 `failures=0` + `newlyLoaded=0` + `PARTIAL prefixLen=198 progress=197` + `diag boundary_blocked=117 skipped_unloaded=0`。剩余：`GoalNearXZ` 仍**生产未接线**（见下一行）|
| **⭐ `D-337 附注二`：远距离 = 一跳一跳逼近 ✅ 已修 + 判据（2026-09-19）** | 洪泛事实：粗目标在边界外不可达 ⇒ 整片展开（`nodes=20000` = 上限 / 147~163 ms），对照可达 640 格 = `641 节点 / 8~14 ms`。修法：⭐ `pathing/core/search/FarTravelHop`（只读 `hasChunkAt` 采样已加载前沿 ⇒ 夹到边界内侧 + `GoalNearXZ` 半径版）+ ⭐ `task/FarWalkTask`（生产任务：反复跳 + 跳数/tick/单调性护栏；到达口径刻意粗 = XZ 半径内 ⇒ 精确落脚接 `WalkToTask`，即 Baritone `GoalNear`→`GoalBlock`）。**A/B 实测（同一夹具同一轮）**：粗目标 `20000 节点 / 142~186 ms / PARTIAL` vs **一跳 `161 节点 / 1 ms / REACHED`**；**执行侧**：300 格 = **hops=2 / 1111 tick / DONE**，随后精确落脚 30 tick ✓。两个实测踩到的判据已固化：① 整条线全加载时不许夹（否则脚边目标被误判为「边界在脚下」）② 跳数口径 = 已开始的跳数（到达可能在上一跳「在飞」时被捕获）|
| **⭐ `FarWalkTask` 无生产调用方 ⇒ 处置已定（`D-337` 附注四，2026-09-20）** | 对照结论：**`SafeReturnTask` 不等价**（它自成一体：rounds/安全区+家/末段落脚/自己的失败码）⇒ 不能简单合并；它期望的消费者（决策层「去某坐标」目标，`D-330`/队列）**还没实现** ⇒ **今天没有接线对象**；而它是 `D-337`（用户裁定 A 案）的执行侧且被 `far_path_bench` 持续验 ⇒ **不删**。**真障碍 = 红线前置**：裸粗目标会让 A* 读未加载区块（`D-132`）⇒ 接线必须走 `FarTravelHop`；⭐ 已落地门禁 `tools/check-far-goal-usage.sh`（裸粗目标只许在 `FarTravelHop` + 夹具；注入生产 ⇒ 红）。**触发**：① 队列「去某坐标」落地时**必须用它**；② 那时仍不用 ⇒ **删**（连同 `FAR_WALK` 相位）；③ 可选重构（安全网内联 hop ⇒ 复用）低优先，**今天不动** |
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

## §5.12 保护区层级 / 安全区 / 任务区（`D-338`，2026-09-19 登记；本表就是落地清单）

> 口径见 `AI_DECISIONS.md D-338`（七条用户裁定 + 现状核对表）。这里只放**可开工的件**与触发条件。

> ⭐ **队列索引（2026-09-19 夜重整）** —— 下面的表格是**明细**；这里只做**分组指针**（不抄内容，照"只放指针"纪律）。
> **P0 阻塞在你一句话**：`§5.12` 第 **17**（中断点语义：玩家显式停止不叫 LLM/不进事件环）· 第 **11**
> （自己砍下的树苗被**被动拾取闸门**挡；⚠️ 可能与刚落地的 `D-344` ① 重叠 ⇒ **先复核再裁定**）·
> 第 **7** 行尾注（`D-288` 红线"视觉不进执行路径"待确认）。
> **P1 方向已定、可随时开工**：第 **14**（LLM 自起任务的**可观测性**：聊天留一行"谁起的" —— 两次实测
> "4 秒内自起 `region_lumber` 砍掉保护区的树、聊天零提示"⇒ 我建议**排第一**，改动小、用户可见）·
> 第 **20**（玩家显式在自认领区修路做不了，`D-343` ③）· 第 **4** 行剩项（勾选界面的等级选择 + 玩家划 `L3` 区的入口）。
> **P2 需先出方案/评估**：第 **12**（2×2 高大云杉搭柱子）· 第 **15**（区域内注册容器卸货）· 第 **16**（销毁/垃圾桶）
> · 第 **18**（决策层队列 + 持久终态，已定稿未实现）· 第 **2/3/4/5/6/9**（复活线后续形态，已登记未开工）。
> **P3 唯一"证据在客户端"的一件**：第 **10**（J7 攀爬兜底在保护区内会不会搭柱子 —— 需专门造
> "够不到的上层原木"在被认领区块里；口径见 `docs/TESTING_GUIDE.md`「客户端轮次的准入尺子」）。
> **已收口（不再排）**：保护区线 `D-338` 主线 · `D-339`/`D-340`（夹具血脉）· `D-341`（「无权」≠「没有」）·
> `D-342`（循环受理闸）· `D-343`（两项未决正名 + 门禁）· **`D-344` 全弧**（区域补种异步化 + 拾取清单 +
> 退避豁免，片 A/B/C）· ⭐ **`D-345`+`D-346`**（挖矿收集距离窗口：取证 → 上限改由作用域派生，同日转绿；
> 夹具 `mine_far_drop` 已搬进 `MiningModule`）· ⭐ **`D-347`**（运行账 / 可测量判据：`bot/TaskMetrics` +
> 只读出口 + 两道会话侧门禁 + 取证夹具 `mine_run_metrics`，到达率 **3/4** 是量出来的）。
> ✅ **`D-348` 已修（2026-09-20，用户拍板"加有界宽限窗口 + 先红后绿夹具"）**：`ScopeBuffer.flushPending`
> 不再"tick 末一次定生死" ⇒ **40 tick 宽限窗口内每 tick 复验**；并且**归属在入队那一刻就解析好、随窗口携带**
> （否则登记被推迟 13~21 tick 时记录已被 prune ⇒ 实体救回但归属丢 ⇒ **照样捡不起来** —— 这半截是夹具逼出来的）。
> 取证夹具 `scope_pending_grace`（EXTRA，`PickupModule`）：绿 `checks=8 failures=0`（推迟 13 tick、登记为
> `OURS_DIRECT`）/ 反向对照（窗口=0）**红 2 条**。
> 🅿️ **`D-351`（2026-09-20 用户裁定"先判断 / 登记 / 标记何时做"）**：**LLM 的记忆 / 上下文**（陪伴线的核心缺口）——
> 判断 = **真需求**（`D-267` 陪伴线在档，但"记忆 / 上下文"此前**没登记**；实测入通道**零命中**、LLM 侧无记忆、
> "自动适应未硬编码需求"**无处存放**）；**能做但需四道前置**（入通道 / 记忆库形状 / ⭐**快照预算**（世界事实优先）/
> ⭐**写入口只能提议、确定性层校验**）；与 `D-267` F4 + 权限红线的关系与**证伪判据**已写进 `D-351`。
> **何时做**：**推荐 A = 队列（`GoalRecord`）落地之后**（与 `history` 共用"落盘 + 裁剪 + 读入快照"，只付一次代价）；
> B = 实测出现"它不记得"抱怨；C = 只做最小闲聊。**状态：仅登记，未动代码**。

> ✅ **`survey/23` 三处隐藏坑已处置（`D-349`，2026-09-20 用户拍板"一起做"）**：Pit 1（"能保证"押在刹车上）
> ⇒ §7 标题校正 + 新 §7.5 世界事实对账表 + **已落地** `JobKindContract` 声明表 + 门禁
> `tools/check-job-kind-contracts.sh`（三种注入全红）+ `JobLauncher` 受理闸；Pit 2（`MAINTAIN` 无"还能不能维持"判据）
> ⇒ **已落地** `RegionLumberJob.maintainUnreachable()`（如实登记 + 上报「可做什么」+ 恢复自清，**不擅自收工**）
> + 夹具 `region_maintain_unmaintainable`（触发/不越权/恢复，反向对照红 5 条）；Pit 3（升级链无上限）
> ⇒ **纯声明** `ESCALATION_CAP=3 ⇒ END_QUEUE + 待机 + 如实登记`（复核触发：队列落地时若 `goal_queue`
> 夹具没加这条判据，本规则退回散文 ⇒ 应删）。
>
> ✅ **③ 挖矿设计文档（`D-329` 阶段 0）已落地（2026-09-20）**：新 `docs/MINE_TASK_DESIGN.md` ——
> 收口已定项 + **每阶段带可失败判据**（S1–S5 扫描契约 / 作业区意图 / `D-327` 通道能力形状 / `M4` 先于 `M3` /
> 红线清单含 `D-333` 冻结）+ 挂账 `Q2/Q3`/站位选优/分支巷；旧 `MINE_MIGRATION_DESIGN.md` 转为历史实施记录。
>
> ✅ **② 收集器"物品过滤钩子"——核实后结论：不做（2026-09-20）**。事实（代码为准）：
> 生产调用者**几乎全传空白名单**（`MineJob:324` / `MineTask:501` / `LumberJob:442` / `ScaffoldLifecycleTask`×2 /
> `RestoreScopeTask` / `ChainMineDiagnosticTask` 全 `List.of()`），而 `CollectDropsTask:506` **只在非空时**按 UUID 过滤
> ⇒ 这些路径**本来就不过滤**（按作用域 + 归属收）。**唯一**传真 UUID 的是 `CollectJob:165`，而它是
> **有意的"最近一簇"快照**（3×3×3、≤16 个），外层每收完一簇会**重新挑簇** ⇒ 后出现的掉落物会在**下一轮**被捡，
> 代价是**一轮循环**，不是"收不到"。`MineProductFilter` 是**挖矿侧**的产物判定（目标驱动 + 标签族兜底，`D-219`），
> 与收集侧白名单不是一回事。
> **⇒ 不改过滤面**（扩面会**扩大收集范围**，直接触碰 `D-138` 授权红线，而收益不明）。
> **触发条件（实测才改）**：出现"任务报完成、地上仍有同类**我方**掉落物"⇒ 改法已备：
> 过滤面扩成 `UUID 集合 ∨（归属我方 ∧ 物品在清单内）`，**必须**复用 `DropPolicy.effectiveProvenance`，不许绕过。
>
> **复读（`survey/22`+`21` 第二次）后**剩下的可做项：② 收集器**物品过滤钩子**
> （`D-329` 路线图第 1 步，尚不存在）·
> ⑤ `HAZARD_ADJACENCY_PENALTY` 相对化（被 `D-333` 冻结压住，等实测触发）· ⑥ `FarWalkTask` 仍无生产调用方。

| # | 落地件 | 现状 | 依赖 / 触发 |
|---|---|---|---|
| 1 | `SafeZoneData`：**安全区子集**（必须落在保护区内）+ `internalChunks()`（**四邻腐蚀**的内部区块；1 区块/散点区 ⇒ 空集 ⇒ 退化"进区即到"） | ✅ **已落地**（2026-09-19，`D-338` 附注一）：`safeChunks`+`safe_chunks` NBT + `SafeDeclare` + `declareSafe`/`clearSafe`/`isSafe`；不变量三条路都堵（拒声明 / `unclaim` 连带清 / `load` 丢孤儿并计数+summary）；`internalChunks` 纯函数 + 维度级入口 | 无（已完成） |
| 2 | **返程目标链**：归位点 > 安全区内部 > 保护区内部；到达判据与终点改成"**到达集**"（内部区块，空则退化） | ✅ **已落地**（2026-09-19，`D-338` 附注三）：`returnZoneChunks`/`returnArrivalChunks`/`isInReturnZone`/`nearestReturnCell`；`SafeReturnTask` 三处判据切换 + `BotManager` 用新终点；门禁 `safe_return` 重写成 **5 用例 30 判据**（含优先级链判决与退化），**先红后绿**（旧实现 `failures=4`；绿：安全区内部 @2992 / 保护区内部 @2976 / 单区块退化 / 封死格不动） | 已完成；**归位点（第 8 件）落地后插到本链最前** |
| 3 | `WorldModLedger` **break 条目**（谁/何时/拆了什么/**原状态**；默认不恢复；**限保护区**） | ❌ 未做（今天只记我方放置；`Entry.previousState` 已具恢复前提） | 依赖 1（要判"在保护区内"） |
| 4 | **区域级权限 / 预算授予面** + **任务区声明**（任务自声明，随 `scopeId` 生灭；目标内 `KEEP` / 目标外提权+预算+`TEMP`） | ✅ **已落地**（2026-09-19，`D-338` 附注六 + 附注八）：几何/锁定层 = `TaskZoneRegistry`（工作区域方块级 ⇒ 任务区区块最小覆盖；可覆盖保护区父类、不得覆盖安全区 ⇒ 报错不裁剪；随 `scopeId` 生灭）+ ⭐**权限阶梯接闸门** = `WritePolicyMatrix.Level`（`L0/L1/L2/L3` 单一出处）+ 新 `protection/ZoneAuthority`（**一个判据三处消费**：候选扫描 / 破坏闸门 / **放置闸门（本片补上的缺口）**）+ `L1` 区内 8 次配额；门禁 `task_zone` **76 判据**（三次反向对照先红后绿）、`module:protection` 3/3、CORE 51/51 零回归。⏳ 剩：勾选界面的等级选择 / 玩家划 `L3` 区的命令入口（第 9 件，操作逻辑先给用户审核）· 默认任务区（第 6 件） | ⏳ 剩两项属第 6/9 件 |
| 5 | **权限等级阶梯**（`L0 只读` / `L1 临时脚手架(≤8 放置)` / `L2 工作面(目标内 KEEP + 目标外逐块提权)` / `L3 全权(仅玩家显式)`） | ✅ **已拍板**（2026-09-19 用户「权限阶梯我同意」，`D-338` 附注七②；任务类别→等级映射 = AI 提案，`MINING`⇒`L0` 照 `D-338` ④） ⚠️ 同时拍板：冲突**如实失败、不继续跑**（附注七①） | 已解锁第 4 行剩下的「任务区接闸门」 |
| 6 | 玩家预先声明的**长期任务区**（"这是我的林场/工地"）+ **默认任务区记录**（玩家设定，免得每次手划） | ⏳ 下一阶段；⛔ 口径已定（`D-338` 附注二）：任务区**可覆盖保护区父类**、**不得覆盖子类声明**（要覆盖必须先**显式退化**）、由**任务管理与锁定**（任务存续期玩家不可手改）、**取消任务自动解除**、默认区**覆盖冲突必须报错**（不许静默裁剪/降级） | 4 落地后 |
| 7 | **玩家文案统一**：claim 命令/勾选 UI 的"保护区"= 正名；返程/`/alice status` 侧改口 | ✅ **已收口**（2026-09-19）：`/alice protect list` 打印「当前位置：保护区/安全区 + 内部区块计数」；返程侧改用「**返程到达集**」措辞、日志带 `zone=safe\|protect` 与 `arrivalChunks=N`（不再自称"安全区边界"） | 已完成 |
| 8 | **归位点**（玩家设定，返程最高优先） | ✅ **已落地**（2026-09-19，`D-338` 附注五）：`ReturnPointData`（每 bot 一个，含维度）+ 命令 `/alice bot-home set\|clear\|show`（零参数、以执行者站位为准、半径 3）+ 返程链**最前项**（`zone=home`，有归位点 ⇒ 跳过区几何；跨维度忽略）；门禁 `safe_return` 判据 **30 → 41**，**反向对照先红后绿**（假装无归位点 ⇒ `failures=3` 恰好是归位点三条） | 已完成；界面与多 bot 名称参数 = 以后 |
| 9 | **勾选界面的安全区模式**（可多选/框选；今天只有零参数命令） | ⏳ **未做**（用户 2026-09-19：**操作逻辑要先给他审核**再实现） | 命令入口已可用（`/alice protect safe claim|unclaim`）⇒ 不阻塞 |

**已成立（不用做，只是别误删）**：候选层已排除保护区内目标（`MineCandidateSource:184` / `LumberCandidateSource:68`，
理由码 `protected_area`）+ 动作层再拒（`BlockBreakSafety:52`）⇒ "挖矿只扫保护区外"与"保护区内不可破坏"今天为真。

**2026-09-19 客户端第二轮（`D-338` 附注九）留下的三项**：
| # | 事项 | 现状 |
|---|---|---|
| 10 | ⭐ **J7 攀爬兜底在保护区内会不会搭柱子**（`L2` 的 `STEP_PLACEMENT` 放行 + `TEMP` 账本） | ✅ **已验证**（2026-09-20 用户指出归档已存在 ⇒ 本行原为**过期状态**）：**客户端第三轮**（`D-338` **附注十一**，`latest.log` 17:28–17:30）：`ZONE_PROTECTED*` **144 → 0**、`[Ledger] place 28,64,208 / 65 / 66 minecraft:cobblestone [TEMP STEP_PLACEMENT scope=…#1960:Region…]` = **真的在保护区里垫了三格上去**、云杉 `chopped 6/7 failed=1 → 7/7 failed=0`、内层 Job `places=0 → 3`（4 次）⇒ 用户判定「**修复后符合预期**」；根因链见 `D-338` 附注十（能力闸门是保护区的**第四处**消费点）。已补 `AI_TEST_MATRIX` 行 |
| 11 | **区域补种缺料**：自己砍下的树苗在地上被**被动拾取闸门**挡（`[Pickup] blocked … provenance=FOREIGN policy=ASK`）⇒ 区域任务 `tool_missing` 中止 | ✅ **已落地（`D-350`，2026-09-20 用户裁定「让区域任务显式收集自己的掉落物」）**：现场 = 客户端 2026-09-19 20:49 那轮（树苗已选定、`deficit=0` ⇒ **全轮无 `扫描判定`** ⇒ 从不进扫描；而 `[Pickup] blocked` 3 次）。修法 = 入口条件重排（**地上有我方产物 ⇒ 就收，与欠不欠树无关**）+ ⭐ **非必需扫描零收获只退避不失败**（否则「地上有一件捡不到的」会把任务判死）+ 权限面不变（`CollectGrants` SESSION + `D-138`）。判据：纯函数 **23 条全过**、反向对照 **红 3 条**、`module:lumber` PASS。✅ **客户端实测已过（2026-09-20，`WINDOWS_CLIENT` + `USER_ACCEPTED`；用户口径「符合预期，bot 没有卡住的感觉，有新的掉落物也会去捡」）**：`latest.log` `:2760` `扫描判定 HAS_SAPLINGS → ENTER（deficit=0 手里苗=3 区内可捡=13）`（旧包同一时刻 `viable=0` 原地待机到轮次结束）⇒ 三次扫描 `实际入包=16 / 2 / 1` + `撤销收集授权 g1/g2/g3`，`:3112` 新落物 `NO_DEFICIT → ENTER` 再收 1 件；`sweep_no_progress=0`、`failed=0`、正常关服。⇒ 上面「e2e 未单独造场景」已由**客户端真 Job** 覆盖；对「`[Pickup] blocked` 行数两轮都是 26」的如实更正见 `D-350` 附注一（**不是回归**：窗口内授权收走 19 件，窗口外闸门照常拦） |
| 12 | **2×2 高大云杉支持**（77 原木 > `MAX_LOGS=64` ⇒ `trunk_too_tall` 直接跳过，不搭柱子） | ⏳ **需求**（用户 2026-09-19 提出"应该搭柱子上去"）；成本/风险需评估 |
| 13 | ⭐ **区域补种异步化 + 可配置拾取清单**（用户 2026-09-19 **细则已定**）：砍完**立刻捡原木**；"等待窗口"= **现在的巡查退避/等生长**（不新开窗口）；窗口内**主动扫区域内地上的东西**，**一次不设上限、扫到区域内捡完为止**，可**反复扫描等待**；⚠️ **补种与扫描不许撞车**（串行互斥，扫的时候不补、补的时候不扫）；"该补的种"先记账（已有 `pendingReplant`），捡够再补；身上有苗则砍完立刻补（现行为保持）；**不许硬编码树苗** ⇒ **可配置清单**（例：树苗 / 树枝 / 苹果…） | ✅ **已完成（2026-09-19，`D-344` 片 A/B/C 全弧）**：`docs/REGION_REPLANT_ASYNC_DESIGN.md`（设计已定·**已实现**）。裁定 = ① 区内落物**只在扫地面期间**自动 mint `SESSION` 短 TTL 授权（`CollectGrant`；代价：旁边的 `FOREIGN` 会被范围吸附顺手捡走）② 缺口①**修**（补种/扫描不吃 600 tick 退避）③ `N=3` + **新码 `sweep_no_progress`**（带 `foreign=`/`unreachable=` 分类；不复用 `tool_missing`——那会撒谎）④ 默认清单 = `{选定树苗}`（不硬编码）⑤ 接口 = `/alice region pickup add\|remove\|list` **读主手物品**（GUI 归第 9 件）。⚠️ 方案初稿曾与本行细则冲突 + 退避上限误写"数分钟"（实为 600 tick = 30 s）⇒ 均已改正 |
| 15 | **区域内注册容器卸货**：区域内**手动选定注册**容器，背包每满一次 / 每个等待期就**卸货**过去 | ⏳ 登记；⭐ **只做手动选定**（**不做自动检测** —— 用户担心装了模组后分不清"什么是真正的容器"，自动检测**后面再讨论**） |
| 18′ | ✅ **临时缺口①（保护区里 LLM 自起任务封顶 `L1`）已落地**（`D-338` 附注十四；`task_zone` 89 判据 + 反向对照 3 红）；✅ **缺口②（事件环补全）已落地**（`D-338` 附注十五；源码规则 `rule_stop_event_ring` + 反向对照；`droppedTriggers` 进快照）；⏳ 余项：③ 最小循环检测 ④ 可观测性（已做） | — |
| 18 | ⭐ **决策层最终形态已完整落档**：`docs/DECISION_LAYER_FINAL_FORM.md`（任务队列 + 持久终态 + 历史落盘；状态 = **设计定稿 · 未实现 · 未验收**）。**与主线关系**：弱耦合、不阻塞；但它是 `AI_PROJECT_STATE.md`「当前目标」里**差异②「多层任务失败向上传递」的成熟形态**，也是**机器线"维持一台机器持续生产"的落点**。⏳ **临时缺口（独立于队列，可先做）**：① 保护区里 LLM 自起任务封顶 `L1`（实测咬到两次）② 事件环补全（`immediateStop` 的 stop、启动前拒绝、静默丢弃）③ 最小跨任务循环检测 ④ 可观测性（已做） | ⏳ 待用户排期 |
| 17 | **中断点语义**（`D-338` 附注十三）：① 玩家显式停止（`immediateStop`）；② 启动前拒绝（`unimplemented` 实体目标 / 修路计划非法）今天不进事件环 | ✅ **用户 2026-09-20 裁定**：**不叫 LLM，但要让 LLM 知道**。核实（代码为准）：机制**已存在**——`BotManager:2065` 已把停止记进事件环（`BotEventLog.record(bot, "STOP", …)`），而 `DecisionSnapshot:171` 把 `BotEventLog.recent(bot, 12)` **送进每次快照** ⇒ "让 LLM 知道"无需新机制。⚠️ **三条诚实边界**（已登记）：① 只在**下次被叫到时**可见（不是即时推送）；② 只留**最近 12 条** ⇒ 之后若发生 ≥12 条事件，`STOP` 会被挤出窗口；③ **不跨重启**（事件环是内存 32 条）。② 启动前拒绝（`unimplemented` 实体目标 / 修路计划非法）**不进事件环**——⚠️ **这一句是错的**（本行 2026-09-20 写时没核代码）：它们**早在 `826a57d`（2026-09-19 18:52「事件环补全」）就已落地**（`BotManager` `assignRoadBuild` / `assign` 的 `ENTITY` 分支各一条 `BotEventLog.record(bot, "REFUSED", …)`），且有源码门禁 `rule_stop_event_ring` 咬住。⭐ **附注十六（2026-09-20）**：顺着这条口径补了**真正还缺的三处派活/受理被拒** —— `replaceTaskIfRunning` 的「未结清传输」（此前只有 warn）与「在飞传输」（此前**完全静默**，连 warn 都没有）+ `assignJob` 的「kind 契约不全」；门禁从**全局计数 ≥2**（在别处新增一条 `REFUSED` 就能掩盖被删的那条 ⇒ **假绿**）改成**按每个站点各自的函数体做结构断言**，六处注入实测全红（旧规则在六种注入下**全部假绿**）|
| 20 | ⭐ **玩家显式在自己认领区修路 = 目前做不了**（`D-343` 裁定③，2026-09-19 新发现）：道路任务**不声明任务区**（`TaskZoneRegistry.declare` 在 `road/` **零命中**），且 `RoadBuilder.start` **只由玩家命令**触发（`BotCommand.java:1726`）⇒ 写入闸门按 `protected_area` 拒（`placeBulkEdit:482` / `breakForBulkEdit:526`）。按阶梯 `BUILD ⇒ L3`（玩家显式本该可开）算**缺口** | 🅿️ **用户 2026-09-20 裁定：先把「道路任务」登记为**未验证的陈旧任务**，**不做任何修复**；要做之前必须**完整复查**（哪些还能用、哪些与世界模型/闸门体系脱节、值不值得留）⇒ 再决定"继续修复 / 重写 / 删除"。⚠️ 复查前**不许**顺手修，⚠️ 更**不许**借 `D-343` 裁定②放宽 `RoadObstaclePolicy`（规划期规避保持**收紧**） |
| 19 | ⭐ **客户端反向测试（2026-09-19 19:00–19:07 那轮 + 19:28 复验）的结论 + `D-339`/`D-340`/`D-341` 落地**：✅ 保护区里**玩家自己**发起照旧干活（`chopped=0→5`、`STEP_PLACEMENT` **真垫了方块**、`scaffoldLeft=0`）· ✅ **封顶生效**（LLM 自起那轮 5 棵树全 `zone_break_not_allowed`）· ✅ **事件环可取证**（`bot_report` 快照 `recentEvents` 里 `type=STOP` + `droppedTriggers=3`）· ✅ **`D-339` 夹具终态不交给 LLM**（客户端已验证：全会话 `[Goal]` 仅 `trigger_dropped reason=fixture_driver` 一行）· ✅ **`D-340` 夹具事件不交给 LLM**（`fixture_event_silent`；反向对照恰 1 红）· ✅ **`D-341` "无权" ≠ "没有"**（区域作业在树全被永久拒绝时**如实失败** `no_permitted_candidate`；`task_zone` 93 判据 + 内核结构断言 + 两条反向对照）· ✅ **口径①已定**（一次性作业在自己认领区被拒**是设计行为、是反例、不开豁免**） | ✅ **客户端端到端已验**（`task_terminal_reason kind=region_lumber driver=llm terminalReason=no_permitted_candidate`）：`/alice instruct "在保护区里起一个 region_lumber"` ⇒ 立刻 `FAILED no_permitted_candidate`；夹具事件通道 ⇒ 应只见 `trigger_dropped reason=fixture_driver（夹具驱动的事件…）`。⏳ **`L2` 要不要也加"每 `scopeId` 区内放置上限"**（`L1` 有 ≤8）⇒ ✅ **已裁定 = 不加**（`D-343` 裁定①：**无洞**——`WriteBudget` 同按 `scopeId` 计、默认 32；加了会伤补种/火把/垫脚 ⇒ 假拒绝）。✅ **`RoadObstaclePolicy` 裸判据 = 有意保留**（`D-343` 裁定②：那是**规划期规避**（收紧）不是欠账，写入闸门没绕过 ⇒ 已变**可失败断言** `rule_bulk_write_zone_gate`，含"不许顺手接上阶梯"的反向断言）。✅ **新发现立项 = 第 20 项**。✅ **③ 最小跨任务循环检测已落地 = `D-342`**（受理闸：同一 `(kind\|目标)` 窗口 1200 tick 内失败 2 次 ⇒ **拒再起** + `REFUSED` 进事件环 + 带身份/次数/换目标指示的回读；玩家显式**豁免**；成功/过期复位；夹具 5 断言 + 内核顺序·结构断言 + 反向对照两条）|
| 16 | **销毁/垃圾桶兼容**（模组联动）：背包内垃圾桶模组、实体垃圾桶模组 ⇒ 可定义"**哪些东西捡到要拿去销毁**" | ⏳ 登记（与 15 同族：都是"区域内注册点 + 捡到的东西去哪"） |
| 14 | ✅ **已落地（2026-09-19）**：保护区里**非玩家发起封顶 `L1`**（能清障垫脚、拆不了玩家的方块），玩家显式照旧 —— 见 `D-338` 附注十四。原问题：LLM 能否**自行**起一个拿到 `L2` 区内写权限的任务（今天等级只按任务类别授予、不看驱动身份） | ⏳ **已咬到两次**（2026-09-19 两轮客户端：一次性砍树被如实拒绝后，LLM 4 秒内自起 `region_lumber` 并砍掉用户保护区里的树，聊天**零提示**）⇒ 优先级升高；附**可观测性**：⭐ **审计修正（2026-09-19 夜）—— 这条基本已做，原措辞过期**：聊天里**已有** `[alice] 决策层：已起 Job <desc>`（`GoalDirector:655`，经 `notifyTargets` 送达），且 `tell` **总留一行** `[Goal] notify … delivered=N text=…`（`:763`）；driver 归因另有门禁 `F1-P1` + 步 `driver_label` + 终态 `task_terminal_reason … driver=`（`BotManager:2397`）。⇒ **剩余只是窄问题**：要不要也写进**事件环/落盘**（事后审计）；以及那轮客户端看到「聊天零提示」的成因 —— ⚠️ **该日志已被覆盖、不可追溯**（诚实标注）。⭐ 机制事实（`D-338` 附注十一）：LLM 决策层是**生产路径**（事件触发 + 节流 + 有界菜单），提示词已有"失败后别立刻重跑同一个"，但**换 kind 即绕过**（`lumber`→`region_lumber`）；且 `region:saved` 候选**常驻菜单**、区域配置**持久化** ⇒ **会复发**。四个候选护栏见附注十一 |

**复核触发**：① 安全区"必须在保护区内"不够用（想在危险区旁单独建避难屋）⇒ 重开"独立声明"；
② 内部区块判据在长条/环形区表现不好（到家后仍贴边）⇒ 换判据；
③ 任务区随 scope 生灭若导致"中途重规划丢授权" ⇒ 补持久化；
④ ⚠️ **1×1 / 2×1 / 2×2 / 3×2 没有内部区块**（四邻腐蚀下）⇒ 小基地上"向中心靠"退化"进区即到"。
⛔ **已裁定：保持现状**（2026-09-19 用户）—— "如果有问题应该**鼓励玩家自己设定归位点**，而不是优化没必要的
逻辑" ⇒ **不加**质心退化、**不加**块级内缩 K 格、**不改**八邻（八邻也已实测证伪：2×2 两种口径都为空；
矩形两者结果相同；8 邻在非矩形上更严）⇒ 该需求的正解 = **第 8 件（归位点）**。

---

## §11 当前工作队列（2026-09-22 用户裁定顺序；**本表是唯一排期来源**）

> **为什么要这张表**：用户 2026-09-22 明确要求"**好好管理工作队列，不要凭印象只做刚说完的内容**"。
> 规则：① 本表**只许追加/改状态**，不许凭记忆重写；② 每条必须有**判据**与**依赖**；
> ③ 完成后标 `✅ 完成（判据/提交）`，**不删除**（历史见本行）；④ 状态口径 = `IMPLEMENTED/COMPILES/SERVER_TESTED/WINDOWS_CLIENT/USER_ACCEPTED`。

### A. 用户 2026-09-22 已同意的安全/责任修复（**按此顺序做**）

| # | 事项 | 判据 | 依赖 | 状态 |
|---|---|---|---|---|
| **C** | ⭐ **安全守卫 I-1**：移除我方方块前，`canWalkOn(level, botFoot)` **在移除后**仍须为真（否则先把 bot 移到安全处）+ 回收**自上而下**、bot 当前支撑那格最后 | 夹具：bot 站在**自己放的 3 格脚手架柱**上跑**保护区内**回收 ⇒ 断言全程有支撑、脚位不下落；**红臂** = 去掉守卫 ⇒ 红 | 无 | **✅ 守卫落地（`D-399`，防御性）；判据不可红（`D-400` 两次实测，不再追）** |
| **Z1** | **账本/恢复收窄到保护区内**（`D-398` R1–R4）：无主区域**不记账、不恢复**；`RestoreScopeTask` 只认保护区内条目 | 判据：区外放/破后**账本无条目**、无恢复动作；保护区内**必有条目** | `D-398` | **✅ 完成**（`D-407`）：`protection/ProtectionZones`（唯一判据）+ `recordPlacement` 区外跳过（遥测 `outsideSkipCount`）+ `dropStale` 销区外条目 + `pendingTemporaryProtected`（取件与 `remaining` 同口径）；判据 = 新步 **`ledger_zone_scope`**（EXTRA）四臂 **绿 `checks=24 failures=0`**；连带：`task/FixtureZone`（夹具前提：认领 + L2 任务区）改了 3 个夹具（`scaffold`/`craft_station`/`restore_underfoot_safety`，前两个在 Z1 后**实测真红过**，第三个原是**静默假绿**）· ⭐ **真机确认**（`WINDOWS_CLIENT`+`USER_ACCEPTED`，23:11 与事故**同坐标**复现：`[Ledger] skip ×3` / `place=0` / `SCAFFOLD_RESTORE=0` / 无坠落 / `COMPLETED`）|
| **Z2** | `J6` 不变量**范围收窄**（只对保护区内条目闭合）并**做成门禁**（不是注释） | `kernel-predicates.py` 新增/修改规则 + **注入即变红** | Z1 | **✅ 完成**（`D-414`）：口径 = `WorldModLedger.Closure`（`inZone`/`wildInLedger`/`recordedSince`/`wildSkippedSince`，**架在已有两视图之上**不复制判据）；6 处收紧（电池 `endStep`（**先读人口再 `dropStale`**）· 编排器终态（**跨 scope 的 owner 口径 → 本步 scope + 区内**，用户裁定）· `clearTask` · `assignRestore` · `RestoreCheckItem` · `Z1` 夹具收尾）；门禁 `rule_ledger_closure_zone_scoped` **八条注入臂全红**（含"臂③第一版没红"的修正）；⭐ **连带抓到 `Z1` 引出的空集假绿**（8 条记账全来自自认领的 `scaffold` 步、13 次放置全 `skip` ⇒ `no_world_write`/`residues` 一族在野外**人口为 0**）⇒ 见 `Z4` |
| **Z3** | **区外取消格数额度**（保留 `capForEscape` 显式装订）；保护区内**不许静默降级** | 门禁/夹具：区外无 cap；cap 打满必须给**瞬时**理由 + 日志（不许写成永久失败） | `D-398` | **✅ 完成**（`D-415`）：⭐ **开工先审计 ⇒ 登记时的两条大半已由 `D-372`+`P1-a`+`P1-c` 满足**；审计挖出**真缺陷** = 同一个量在 `WriteBudget` 里**还有 4 个读者回退 `Caps.DEFAULT`(64/32)**（`plannedWritesAllowed`（**A\* 写边谓词**）/`breakAllowed`/`placeAllowed`/`describe`），且全有生产调用者 ⇒ 破满 64 次后**计划静默降级为纯通行**（`P1-a` 同类复发）。修法 = **唯一出处 `effectiveCaps`**（9 个读者同源）+ 删死 API `capsOf` + 瞬时码单一出处（`EXHAUSTED_CODE`，7 处字面量收口）+ **容器例外结构化**（用户裁定保留：`Caps.UNBOUNDED` 的容器份额不再顺带放开）；判据 = `write_budget` 步新增 `ZONE` 臂（**野外前提自证** + 无装订连做 **70** 次不被拒 + `capForEscape` 「再给 1 次」第 2 次必被拒）+ 门禁 `rule_write_budget_zone_and_container_exception`（**八条注入臂全红**） |
| **Z4** | ⭐ **空集断言清单**（`Z1` 的连带，`Z2` 只做了「可见」）：10 组「用账本证明我没写世界/没残留」的判据在野外**人口为 0** ⇒ 空集恒真（**假绿且不报错**） | 逐条选三种修法之一：① 夹具 `FixtureZone.protect(...)` 让**人口回来** ② 判据改读**世界事实** ③ 明确标 `n/a（区外⇒无义务）` 并**印出人口**（`Z2` 已给的能力） | `Z2`（已给可见性） | **✅ 完成**（`D-416`）：钥匙 = **与区无关的权威读数** `WriteBudget.writeCount/population`（每一次真实写入都过闸门 ⇒ 野外不是空集）；「零写入」类改成 **`writes==0`**（更强）、「无残留」类补**覆盖度**、B 类（`MineTask`/`LumberJob`）标注为 `D-398 R2` 的设计；⭐ **顺带补齐 `Z2` 没扫完的 5 处义务读数**（`BotManager` 4 处含一处「拿区外条目起恢复任务」+ `DecisionSnapshot` 喂 LLM 的世界事实）；门禁 `rule_vacuous_assertions_carry_population`（**四条注入臂全红**，含「臂③第一版没红」的加严）；残余 = 野外残留**只在世界里、不在账上**（真清理拆不到）⇒ 已登记 + 复核触发 |

> **C 的开工锚点（已核对源码，2026-09-22）**：`task/RestoreScopeTask.pickNext():231-257` ——
> 循环取 `queue` 里的我方方块 → 复检 `nowId.equals(entry.placed())`（**不是自己放的绝不拆**，已有 ✓）
> → `current=pos; stage=APPROACH; return startStage(pos.above())`（**"站上去 → 向下拆"**，顺序已是自上而下 ✓）。
> **缺的守卫**：当 bot 的脚位 = `pos.above()`（正踩着待拆的那格）时，**必须先证明拆完之后还有落脚面** ——
> 形状对照 Baritone `MovementDownward:61` 的 `canWalkOn(x, y-2, z)` ⇒ 这里 = **`MovementHelper.canWalkOn(level, pos.below())`**；
> 不满足则**本次不拆**（延后到队列末尾重试，收尾仍不安全 ⇒ 计 `skipped` + 归因 `underfoot_unsafe`，不许静默）。

| **C2** | ⭐ C 的**强判据**（真坠落 ≥2 格）：需要**乱序账本**几何 —— 把 bot 脚下那根的**下层**方块（或支撑它footing 的邻居）作为账本条目 ⇒ 拆它会让整根塌。**守卫开** ⇒ 延后/`underfoot_unsafe` 如实跳过、`biggestFall ≤ 1`；**守卫关** ⇒ `biggestFall ≥ 2` ⇒ 红 | 夹具：新步 `restore_underfoot_safety`（EXTRA，自建地形 + 直接 `WorldModLedger.record` 播种 TEMP 条目 + `setBlock`）；读数 `biggestFall/unsupportedTicks/skipped 归因` | 为什么必须做：2026-09-22 实测——现夹具（`craft_station`）**守卫开/关都 `biggestFall=1`** ⇒ 判据空跑 | **✅ 完成**：新步 `restore_underfoot_safety`（阶梯几何）绿；真判据 = 不许摔 + **归因不静默**（`D-400`） |
| **C3** | ⚠️ **事故归因复核**：真机那次坠落**可能不是回收造成的**，而是**路径**的 `BREAK_AND_ENTER` 进了"破坏后下方无支撑"的格子（真机日志 `planned … executable=false support=-` + `chosen=74,116,198` + 该格随后被 `PATH_ACCESS` 破掉）⇒ 候选落点 `SurfaceMovementProvider.appendBreakAndEnter:187`（只查 `canWalkOn(to)`）/ `AStarMovementSearch:143`（只守 goal）。**C 的守卫不覆盖这条路径** | 先补读数：把"破坏后 foot 是否有支撑"做成夹具（乱序/无支撑几何）再定改动 | 与 C 同族（"我方写入把自己置于险境"） | ⚠️ **已被 `D-404` 排除为机制**（两侧判据对称）⇒ 转为「**待用户补现场几何**」，**不再**为此加守卫 |

### A2. 回收方案（用户 2026-09-22 要求「完整回收方案」⇒ 设计文档已出：`docs/plans/2026-09-22-回收方案.md`）

| # | 事项 | 判据 | 依赖 | 状态 |
|---|---|---|---|---|
| **RC1** | ⭐ **按 `D-403` 简化**：**保留自动拆除**；三条保留条件（已加载 / 现场仍是我方 / 不摔 bot）；**不满足 ⇒ 当场放弃 + 必带归因**（不许静默）；**不做**"待办/分批/显式回收入口" | 夹具：不满足条件 ⇒ `skipped` + 带码 notes（三条臂全绿 + **三处注入即红**） | 无 | **✅ 完成（2026-09-24）**：`single:restore_underfoot_safety = PASS checks=8 failures=0 ticks=184`；修复范围**被迫扩大**到 `dropStale`（见「RC1-进度」）|
| **RC2** | ⛔ **暂不做**（`D-403` 判为冗余）：显式回收入口/回收任务命令 —— 保留登记；**触发条件**：真机出现"必须专程回收"的实例（否则不做） | — | 真机实例 | 登记 |
| **RC3** | `CANNOT_RECLAIM`（容器物品 / 流体光照副作用）**如实记账**（不做逐 item 还原，但必须有可查计数/日志） | 门禁/夹具：不许把不可逆说成可逆 | 无 | **✅ 完成（2026-09-24）**：`Lossy` 分类 + `[Ledger] cannot_reclaim` 明细 + `Closure` 人口（`lossy=+N`）+ 两条破坏路径都接 + 门禁 `rule_lossy_write_accounted` + 夹具 `single:lossy_write_accounted` `PASS checks=7 failures=0`；**三处注入即红**（见「RC3-进度」）· 提交 `083a376` |
| **RC4** | **冗余 1 处置**：账本为唯一真相，`WriteBudget` 降级为遥测/闸门（呼应 `D-387` P1-a 两份不同源） | 门禁：同一量不得有两份"真相" | Z3 | **✅ 完成（2026-09-24）**：① **修好一处真缺陷** —— "世界没变 ⇒ 预算退回扣账"（`D-323` 只修了失败码，`WriteBudget breaks=1/64` 那笔假账一直留着）；② 三本账口径写清（账本=义务/残留、`WriteBudget`=闸门+人口、`WriteAudit`=授权明细）；③ 门禁 `rule_write_truth_single_source` 四条臂 + **五处注入即红**；④ 阈值：事件环人口统一走账本闭合（去掉 `WriteBudget.population` 这个第二来源）。见「RC4-进度」· 提交 `8b60bd8` |

### B. 能力线（用户要的"能干活"）

| # | 事项 | 判据 | 依赖 | 状态 |
|---|---|---|---|---|
| **P3** | **掉落物"本作业范围内"收集授权**（`provenance=GRANTED_AREA`）—— 用户已裁定方案 | 夹具：作业声明半径内、且属该作业目标产物的掉落物 ⇒ 收；**范围外/玩家丢的 ⇒ 仍不碰** | 无 | **✅ 完成（2026-09-24）**：作业级授权 = **范围内 + 只认本作业产物**（范围外/玩家丢的仍 `FOREIGN`）· 门禁四条臂 + 五处注入即红 · 见「P3-进度」 |
| **P3-进度** | ✅ **完成（2026-09-24）** — 夹具 `single:job_area_grant` **PASS checks=15 failures=0**（**连跑三次全绿**：42 s / 41 s / 39 s）· 提交 `6813d3a`。<br>① **形态**：`CollectGrants` 新增**作业级授权** `JobGrant`（**只在内存**，不进 `GrantsData`）+ `addJobScoped/coveringJobScoped/revokeJobScoped/activeJobScoped`；查询入口**要求传 `ItemStack`**（范围 **且** 产物才算）；`DropPolicy.effectiveProvenance` 在玩家授权之后咨询它；`MineJob` 开工签发（`productFilter::matches`）、`finish()` 撤销（四条终态路径都过那里），10 分钟 TTL 只是「撤销被漏掉」的兜底。<br>② **为什么必须带产物过滤**：`GRANTED_AREA` 的策略是 `AUTO` 且**被动吸附也放行** ⇒ 只按坐标放行 = 把「这片我都准捡」（玩家授权语义）搬给作业 ⇒ 范围里**玩家丢的东西**会被一起吸走。夹具臂②③ 钉这个（范围内非产物 / 范围外产物都必须仍 `FOREIGN`），臂④ 钉「撤销即关」。<br>③ **判据（比台账原话更硬）**：门禁 `rule_job_area_grant_scoped`（四臂）+ **五处注入即红**：A 去掉作业级咨询 / B 咨询只传坐标 / C 查询去掉产物判定 / D `MineJob` 去掉撤销 / E 把 `JobGrant` 塞进持久化 `save()`。<br>④ ⭐ **本项抓到 4 个夹具陷阱（全部写进夹具 javadoc；这比「功能绿了」更值钱）**：<br> · (a) **时间基**：授权签发/查询用 `server.getTickCount()`，第一版把 `level.getGameTime()` 传进 `activeJobScoped` ⇒ 授权刚签发就被当过期销账（现象 = 日志里有、查询说没有）⇒ API 改成**自己取钟**，不给调用方留这种错法；<br> · (b) **`new AABB(pos).inflate(r)` 的半边长是 `r+0.5`**：写 1.5 想要「这一格」，实际覆盖 ±2 格 ⇒ 相距 2 格的两个落点互相进框 ⇒ 臂② 读到的是**邻格的铁**（而闸门日志明明是 `diamond … FOREIGN`）⇒ 判据改成 `blockPosition()` 相等；<br> · (c) **落物「可查」要等**：新建方块 + 生成实体后实体登记会被推迟（`D-345` 实测 19 tick）⇒ 固定 3 tick 跑出三条「落点没有落物」⇒ 用「等条件 + 上限 40 tick」（`A3` 的同一条教训）；<br> · (d) **「东西还在吗」只能按身份（UUID）判**：`ItemEntity` 是**可推动**实体 ⇒ 站在它上面 20 tick 会把它推出原来那格（第一版按格子数 ⇒ 同代码两次判决不同：剩 1 堆 / 剩 0 堆）⇒ 改按 UUID。<br>⑤ **回归 + 门禁**：CORE **41/41 PASS**（`LC_ALL=C.UTF-8`）· 生产路径实证：CORE 日志里出现 `[Grant] 作业级授权 …` / `[Grant] 撤销作业级授权 …`（`MineJob` 真的在签、也真的在撤）· `ALICE_MODS_DIR=$HOME/mc-client/mods tools/check-all.sh` = `pass=20 warning=2 failed=0` · `docs/CAPABILITY_LIST.md` 重生成（步 95 → **96**，CORE 仍 41）。|
| **P5** | ⭐ **`D-391` 收口**：被同族矿石+石壳包住的目标给不出站位/进入方案（已排除 A2 截断、排除搜索预算）+ **死候选剔除**（`candidates` 曾涨到 1037 ⇒ 每次选择全量复检） | 新探针先补读数；收口后把 `mine_vein_propagation` 的完成度下限**从 12/30 抬到 ~28** | 无 | 待做（**能力仍未交付**：见 P5-a 切片1/切片2；`MIN_MINED` 保持 12）|
| **P5-复测** | ⭐ **现状复测（2026-09-24，云端无头实跑，131 s）**：`mine_vein_propagation` 仍 **`mined 12/30` / `failed=18`**（与 `D-391` 当时逐字一致）· 18/18 失败**三条路径全灭**（`no_valid_standing_point` / `no_reachable_tunnel_standing_point` / `enter_target_unreachable`）· `veinPropagations=11`（传播有效）· ⭐ **`candidates=13`（21 次），不再是 1037** | ⚠️ **更正台账两条前提**：① `D-392` 的 `FACE` 改动**没有**改善本夹具完成度；②「死候选剔除」的依据（`candidates=1037`）是 `FACE` **之前**的过期观测 ⇒ **不构成本夹具的阻断点**。瓶颈机制（读码 + 读数一致，可证伪）：候选枚举要求**现成可站**（`isStandable` = `canStandCentered`），实心脉里邻格全是矿石 ⇒ **天然零候选**；修法只能走 `D-076` 既有通道（`MiningApproach` 显式授权 + `MiningBudget`）。取证 = `docs/reviews/2026-09-24-P5-现状复测与瓶颈定位.md` | 待做（下一步 = 实现「先破同族矿石腾站位」候选 ⇒ 复测 ⇒ 按实测抬 `MIN_MINED`）|
| **P5-追查** | 轮 2 读码（可核）：`GoalFoot` 只做**等值判定**（不含可站要求）· `miningApproach` **允许** `BREAK_*`/`PILLAR`/`FALL` · 写边谓词只有预算（`WriteBudget.plannedWritesAllowed`）· `MiningBudget` 无「可破谓词」· 模式 A 的 `isStandable` 要求**现成可站**。⇒ 收紧后的机制假设：三条腿全灭发生在**移动边生成**（「破入后身体放不下」= 目标格与其上一格都是矿石）。**下一步 = 先加探针读数**（邻格可站数 / 脚位头位可通行 / 模式 B 每个候选的 `planPath` 状态与首个被拒移动类型），再改代码 | 已登记（探针未落地前不改内核）|
| **P5-读数** | ⭐ **探针实测（2026-09-24，126 s，真跑）**：失败目标 **20 个**，模式 A 目标几何 **`faceStandable=0/6` 20/20 全中**、`footPassable=false headPassable=false` 全中；模式 B **58 次候选全部 `UNREACHABLE`（0 次 `SEARCH_LIMIT`）** ⇒ **排除搜索预算/受限**这一替代解释；连「脚位或头位已有一个通」的 8 个候选也 `UNREACHABLE` ⇒ 卡点在**移动边生成**（2 格高脉里前进需同时清脚位+头位，现有移动集无此边）| 已登记 ⇒ 下一步 **P5-a** |
| **P5-a** | ⭐ **给 mining approach 增加「2 格高走廊」能力**（显式授权 `miningApproach` + 预算闸门 + 只许破产品族/允许集内的方块）⇒ 让被包住的目标能被挖进去。⚠️ Baritone 对照：它的隧道挖掘由**独立挖掘行为**（`MineProcess` 隧道变体）开辟 2 格高走廊，**不是**在 Movement 里破头位 ⇒ 本项必须落在挖掘行为/approach 层，不许让 Movement 默认破头位（`D-076` 纯通行红线）| 先红后绿：本夹具 `mined` 12/30 **必须上升**；据实测抬 `MIN_MINED`（目标 ~28）；撤掉该能力 ⇒ 落回 12/30（红臂）；复跑 CORE 逐步 diff | 待做（P5 的实现部分）|
| **P5-a 设计** | ⭐ **设计已定档（2026-09-24）**：`docs/plans/2026-09-24-P5a-两格高走廊设计.md`。Baritone 对照（`8c55ad0`，云端 clone 后 checkout 同一 commit）：`command/defaults/TunnelCommand.java:84` 用 `BuilderProcess.clearArea(corner1, corner2)` **先清一个盒子**，`:46` **`height < 2` 直接拒绝**；`Movement` 不参与 ⇒ Alice 侧落点 = 挖掘行为/approach 层（复用 `WriteGrant` + `MiningBudget` + `BlockerClearPlanner`；**不新增** Movement 破头位）。形状 = 「口袋挖掘」：先破头位再破脚位、只碰允许集、无解时如实报新码 `no_diggable_pocket` | 判据 = `mined` >12 → 按实测抬 `MIN_MINED` → 撤掉即落回 12/30（红臂）→ CORE 逐步 diff 只许本步变化 | 待实现 |
| **P5-a 切片1** | ⭐ **已实装并真跑（2026-09-24）**：`nextPocketStep` + `startClear(..., WriteReason)` 重载 + `tryDigPocket` + 接线。实测 `pocket_start=20` / `pocket_exhausted=8`（能力**确实触发**，不再是死代码），但 **`mined` 仍 12/30 ⇒ 未达标，不抬 `MIN_MINED`**。原因（有读数）：挖的是**目标旁**的口袋，bot 与它之间还隔着矿石 ⇒ 走不进去；Baritone 的 `clearArea` 是**从 bot 一侧朝目标清盒子**（`TunnelCommand:51-83`）。⚠️ 顺带抓到 3 个真陷阱：① 短路求值下复用 `clearExhausted` ⇒ 口袋永不执行；② 合成码是 `found_but_unminable`（不是三条腿各自的码）；③ 挖掘 profile 的 `clearBudget` 都是 **0**（`MiningProfile:37/40`）⇒ 既有「限次清障」对挖掘从来是死的。取证 = 设计文档 §七/§八 | 待做（**切片 2 = 走廊式**：从可达区边缘逐格朝目标推进，预算按格数且有显式上界）|
| **P5-a 切片2** | ⚠️ **走廊式实装 ⇒ 回归 ⇒ 已回退（2026-09-24）**：`nextCorridorStep`（从 bot 脚位朝目标逐格推进）实测 `pocket_start=4`（走廊确实在动），但夹具 **`verdict=FAIL` / `mine_vein_propagation=TIMEOUT`**（163 s，服务端无异常；日志里的 `Failed to load function …mekanism…` 是云端未装客户端模组的既有现象）。根因：既有升级机器是「**一格一个子 `MineTask`**」（`startClear` 内 `new MineTask(...)`）⇒ 走廊 6 格 × 多目标 ⇒ tick 爆炸 ⇒ 超夹具预算。处置：**代码整体回退**（`git checkout 2a866e7 -- MineTask.java BlockerClearPlanner.java`），回退后复跑确认 **基线恢复**（PASS/121 s/12-30）。⭐ **新增两条硬判据**：① **时间不得回退**（判据 = `mined` 上升 **且** 夹具仍在 `BUDGET_TICKS` 内 PASS；回归即回退、不留红树）；② **执行器必须便宜**（父任务内直接破 / 一次子任务破两格；预算口径从「每目标」改「每作业」）。| 待做（下一轮按这两条重做）|
| **P5-a 切片3** | ⚠️ **口袋候选交给规划器 ⇒ 同样回归 ⇒ 已回退**：`MiningPlanner.plan(..., extraStanding)` + `nextCorridorStep`/`farthestStandableInCorridor` + 两步循环。实测 `pocket_start=4` / `pocket_walkable=3` 但 ⭐ **`pocket_candidate=0`**（规划器一个都没收：`isValidStandingPoint` 要求**看得见目标**，走廊格到目标没视线）；夹具 **TIMEOUT ticks=2801**（基线 PASS / ticks=2035）⇒ 触发硬判据 #1 ⇒ 回退，复跑确认基线恢复。| 已回退（取证见设计文档 §十）|
| **P5-重估** | ⭐ **三个切片连续失败 ⇒ 按纪律停手重估**（不再打第四个补丁）。三条事实：① 口袋必须一直挖到**目标的面**且看得见目标，否则一律被 `isValidStandingPoint` 否决；② 既有升级机器「一格一个子 `MineTask`」**必然**顶穿作业 tick 预算；③ **TIMEOUT 会盖掉真实失败码** ⇒ 预算类判据必须同时报 ticks 与失败码。两条候选路线：**A** = 父任务内联破坏（`BlockBreakSession`）+ 作业级预算 + ticks 判据（改动面最大）；**B** = 承认该夹具几何要的是「从脉外打进实心脉」，属 **鱼骨/探洞产品线**（D 段）而非 P5。| ⏳ **待用户裁**（不擅自开工）|
| **P5 裁定** | ✅ **按既定指令以推荐方案裁定（2026-09-24）**：**P5 收口为「测量半边」** —— ① 新探针补读数 ✅（20 目标 / `faceStandable=0/6` / 58 候选全 `UNREACHABLE` / 0 次 `SEARCH_LIMIT`）；② 「死候选剔除」依据**实测过期**（`candidates=13`）⇒ **不需要做**（有读数支撑）；③ 抬 `MIN_MINED` **未达成**，卡在「从脉外打进实心脉」这个**新能力**上 —— 而它属 **D 段产品线**（鱼骨→跟随→探洞，明确排在内核修复之后）⇒ 排期错位，不塞进 P5。`MIN_MINED` **保持 12**（不假绿）；能力登记为 **PL-1**；队列继续下一项 **RC1** | ✅ 已裁（取证见设计文档 §十二）|
| **P5 段收口补记** | ⛔ **2026-09-24（`PL-1` 完成后追加）**：本段（`P5` / `P5-复测` / `P5-追查` / `P5-读数` / `P5-a` / `P5-a 设计` / `P5-a 切片1/2/3` / `P5-重估`）**整体收口** —— ⭐ **真正的缺口是「过早永久了结」，不是「缺便宜执行器」**：作业终态后**只读复评**每一格剩余矿石 ⇒ 18 个孤儿里 **17 个现在都有方案**（`D-424` §一，日志 `run/headless-logs/20260924-115423-*`）。`PL-1` 的**过期证明重评**交付后 `mined 12/30 → 30/30`、`MIN_MINED` 按实测 12 → **28**。⇒ **不再需要**「内联走廊执行器」（设计文档 §十三 把它保留为 D 段产品线的设计输入，当前**没有任何夹具要求它**）；**本段各行的「待做」全部作废**（历史记录保留，不再重做）。| ⛔ 段内各行状态以本行为准 |
| **RC1-进度** | ✅ **完成（2026-09-24）** — `single:restore_underfoot_safety = PASS checks=8 failures=0 ticks=184`（基线 181）· 提交 `e048d91` · 日志 `run/headless-logs/20260924-090127-single_restore_underfoot_safety.log`。<br>① 第①条「已加载」落在 `RestoreScopeTask.pickNext`（码 `chunk_not_loaded`，置于 `getBlockState` **之前**）。<br>② ⭐ **修复必须同时落在 `WorldModLedger.dropStale`**，否则上面那条是**惰性的**：`buildQueue` **开头**就调 `dropStale`，它对区内条目**裸读** `getBlockState(entry.pos())` ⇒ 未加载区块被**强制同步加载**，条目还被当"幽灵"销掉 ⇒ `pickNext` 的 `chunk_not_loaded` **根本到不了**。**不是读码猜的**：先按"播种时就摆成不一致"加臂②③实跑，得到 `销掉 1 条…4512,99,2600(cobblestone→air)` + `farLoaded=true` + `nothing_to_restore`（`…/20260924-085903-*`）⇒ 才加守卫「未加载 ⇒ **不读、不销**、留待下次」+ 逐条日志 `[Ledger] 暂不对账 N 条…（不为了对账去开图；RC1）`。<br>③ 夹具补齐三条臂，每条都带**世界事实**断言：`underfoot_unsafe`（账本恰好剩「脚下那格」）/ `not_ours`（那格 `DIRT` 原封不动）/ `chunk_not_loaded`（**跑完仍未加载** + 条目仍在账本里）。⚠️ 夹具陷阱（写进类 javadoc）：制造 `not_ours` **不能**靠播种时摆错方块（会被 `dropStale` 的幽灵销账先吃掉）⇒ 必须"**先与账本一致**、等 `buildQueue` 跑过**之后再改世界**"（= 真机竞态窗口）。另：臂③用**内层作用域**隔离，否则 512 格外的条目把 `scope.begin` 的 `spreadRadius` 拉到 256 格。<br>④ **注入即红三连**（各自单独开火，另两臂保持绿）：A 关 `pickNext` 的 isLoaded ⇒ 臂③红（`notesFar=…:not_ours(cobblestone→air)`、`remainingFar=0`，`…/20260924-090240-*`）；B 关 `not_ours` ⇒ 臂②红（`restored=3`、现场被拆成 `air`，`…/20260924-090350-*`）；C 关 `dropStale` 守卫 ⇒ 臂③红（`nothing_to_restore`，`…/20260924-090447-*`）。<br>⑤ **回归**：CORE **41/41 PASS**（`ALICE_CLIENT_MODS=$HOME/mc-client/mods`，`…/20260924-091313-core.log`）· 门禁 `ALICE_MODS_DIR=$HOME/mc-client/mods tools/check-all.sh` = `pass=19 warning=2 failed=1`（唯一 FAIL = 既有红 `check-ref-integrity`；doc-budget 1475/1476）· ⚠️ 云端**不带** `ALICE_CLIENT_MODS` 跑电池 ⇒ craft 类步骤**假红**（已诊断 + 电池默认回退到 `~/mc-client/mods` + 记 `docs/CLOUD_MIGRATION.md` §9-39）| ✅ 完成 |
| **RC3-进度** | ✅ **完成（2026-09-24）** — `single:lossy_write_accounted = PASS checks=7 failures=0 ticks=18` · 提交 `083a376` · 日志 `run/headless-logs/20260924-092146-single_lossy_write_accounted.log`。<br>① **事实（可核，逼出本项的原因）**：`BlockBreakSafety.clearingRefusal` 只在**清障**策略下拒 `hasBlockEntity()`（`D-095`），而明确目标策略（`EXPECTED_TARGET`/`DESCEND_FOOT`/`BULK_EDIT`）照挖 ⇒ 没有记账时"箱子连同内容一起没了"**完全静默**。<br>② **实现**：`WorldModLedger.Lossy{container_contents,block_entity,fluid}` + `lossyOf(BlockState)`（唯一分类入口；容器复用 `ContainerSemantics`；**未知模组的带方块实体方块一律如实记为不可逆**，不猜语义）+ `recordLossyWrite`（逐条 `[Ledger] cannot_reclaim pos block reason by`）+ `recordLossyRefusal`（执行器层被拦下的人口读数）+ 明细环 16 条 + 计数持久化；`Population`/`Closure` 各加两个数 ⇒ **每个闭合点都印** `lossy=+N lossyRefused=+N`。<br>③ ⭐ **`anythingHappened()` 必须认"不可逆"**：破箱子**不产生任何回收义务**（`recorded=0`）⇒ 漏了它就会在"刚把世界改了"时报「本窗口没写过世界」（`describe()` 的"没写过世界"分支同样修掉）—— 实测人口行现在是 `recorded=+0 wildSkipped=+0 lossy=+2 lossyRefused=+0（⚠️ 本窗口没有任何记账，但发生过 2 次不可逆写入…）`。<br>④ **两条真的改世界的路都接**：`BlockBreakSession`（挖矿/破入/下落；记在 `after == before` 判据**之后** —— 世界没变就不算写成功）+ `BlockInteraction.breakForBulkEdit`（`level.destroyBlock` 批量）；`beginBreak` 的两处拒绝记 `lossy_refused`。<br>⑤ **门禁**：`rule_lossy_write_accounted`（5 条臂：分类入口与**顺序** / 两条破坏路径都记 / 计数进闭合人口 / `anythingHappened` 认不可逆 / 不静默）。<br>⑥ **三处注入即红**（各自单独开火；夹具与门禁**同时**变红）：A 去掉 `anythingHappened()` 里的不可逆项 ⇒ 夹具 `failures=1`；B 去掉 `BlockBreakSession` 的记账调用 ⇒ 夹具 `failures=3`（`lossyWrites=0` + 明细缺失 + `lossy=+0`）；C 把分类顺序颠倒（容器先判 `hasBlockEntity`）⇒ 夹具 `failures=2`（`classify=BLOCK_ENTITY/…` + 明细族名错）。<br>⑦ **裁定实证**：夹具真破一个**装着 3 颗钻石的箱子** ⇒ `inventoryDiamonds=0`（内容物**没有**被逐 item 还原；散落成掉落物后由夹具清场）。<br>⑧ **回归 + 门禁**：CORE **41/41 PASS**（`ALICE_CLIENT_MODS=$HOME/mc-client/mods`）· `ALICE_MODS_DIR=$HOME/mc-client/mods tools/check-all.sh` = `pass=19 warning=2 failed=1`（唯一 FAIL = 既有红 `check-ref-integrity`）。⚠️ 新夹具的容器写入点按门禁要求登记进 `docs/authz/CONTAINER_WRITE_SITES.csv`（`category=scenario-seeding`：夹具给**新建方块实体**塞物品，不经 Alice 的库存/容器写入路径）；`docs/CAPABILITY_LIST.md` 随之重生成（步 94 / CORE 41）。| ✅ 完成 |
| **RC4-进度** | ✅ **完成（2026-09-24）** — `single:break_refused = PASS checks=31 failures=0`（新增两臂）· 提交 `8b60bd8` · 日志 `run/headless-logs/20260924-093502-single_break_refused.log`。<br>① **事实（代码里可核）**：`consumeBreak` 在**会话开始前**扣账（闸门必须在下手前拦住），而破坏可能在很多 tick 之后才被证明**根本没发生**。`BreakRefusedCheckTask` 头部原话就是现场：FTB 认领内 4 次破坏全打了 `WriteBudget breaks=1/64` + `COMPLETED`，而存档里那 4 格仍是 `minecraft:dirt` ⇒ `D-323` 修好了**报告**（世界没变 ⇒ `REFUSED`），**扣账一直留着** = 预算账说"写了 N 次"、世界/审计说"一次都没写"（同一量两份真相）。放置那边本来是对的（`placeAt` 落地之后才计数）⇒ 本次把破坏补齐成同一条原则。<br>② **三本账的分工（写清，避免下次又读成"漏了"）**：`WorldModLedger` = **义务与残留**（"还欠多少"，含 `RC3` 的不可逆事实）；`WriteBudget` = **闸门 + 人口**（"还让不让写"/"这次窗口写了多少次，含区外"）；`WriteAudit` = **逐条授权明细**（attempt）。<br>③ **改动**：`WriteBudget.refundBreak(...)`（免额扣账不参与；无扣账不退）+ `refundedBreaks()` 人口 + `describe()` 印 `refunded=`；`BlockBreakSession.fail(...)` 统一退回（所有"没成功"的终态都走它：`REFUSED`/超时/中止，`refunded` 闩锁）；`breakForBulkEdit` 的 `world_unchanged` 分支同样退回；`BotManager.immediateStop` 的事件环人口**去掉** `WriteBudget.population` （第二来源，且退回生效后必然分叉）⇒ 统一用 `closure(...).describe()`。<br>④ **门禁四条臂**（`rule_write_truth_single_source`）：退回扣账的调用点 / 放置侧"落地后才计数"回归锁 / `WriteBudget` 不得有"待收/残留"API / 预算计数读数只许在夹具探针。<br>⑤ **五处注入即红**（全部静态门禁，逐条单独开火）：I1 去掉 `fail()` 退回；I2 去掉批量路径退回；I3 **放置侧落地判据挪到扣账之后**；I4 给 `WriteBudget` 加一个 `pendingWrites()`；I5 在 `action/MineBlockRunner` 里读 `WriteBudget.breaks(`。<br>⑥ ⚠️ **规则自己假红两次（记下来，同 `Z2` 的"判据太糙"教训）**：抠 `placeAt` 签名只拿到**第一个重载**（真实现在第二个）⇒ 改成"只看 `consumePlace(` 所在方法内部"；正则里写了 `owed` ⇒ 把 `breakAllowed/placeAllowed/plannedWritesAllowed` 三条闸门 API 误报成"待收"。**I3 第一次注入没红也暴露了判据太糙**（拿全文件第一次 `canBeReplaced()` 比位置）⇒ 修完才红。<br>⑦ **本项顺带发现的真缺陷只有一条**（①的那个）；`BotManager` 那处属口径统一，`WriteAudit` 计数只是授权 attempt 数（与"落地写入数"本就不是同一个量）。<br>⑧ **回归 + 门禁**：CORE **41/41 PASS**（`ALICE_CLIENT_MODS=$HOME/mc-client/mods`）· `ALICE_MODS_DIR=$HOME/mc-client/mods tools/check-all.sh` = `pass=19 warning=2 failed=1`（唯一 FAIL = 既有红 `check-ref-integrity`）。| ✅ 完成 |
| **RC1-b** | ✅ **完成（2026-09-24）**：把「账本对账路径**不许裸读**未加载区块」做成**静态谓词** —— 挂在既有 `Z2·账本闭合口径` 规则上作**臂④**（`kernel-predicates.py`：`dropStale` 读 `getBlockState(entry.pos())` 之前必须有 `!level.isLoaded(entry.pos())`）。**注入即红实测**：改成 `if (false)` ⇒ `KERNEL_PREDICATE_CHECK_RESULT FAIL … 账本闭合口径=1` + 指名报错；还原后 `PASS`。⇒ 将来谁再加一处裸读 = **构建红**，不再靠夹具兜 | ✅ 完成 |
| **PL-1** | 产品线：**从脉外打进实心脉** —— ⭐ **切片 1 按实测改道（2026-09-24）**：真正的缺口不是「缺便宜执行器」，而是**过早永久了结**（详见「PL-1-进度」） | 判据（已达成）：`mine_vein_propagation` **`mined 12/30 → 30/30`**（连跑两次）· `staleRetries=18` · 复评探针「剩余矿石格 18 → 0」· `MIN_MINED` 按实测 **12 → 28**；红臂（关掉重评）⇒ 落回 12/30；门禁 `rule_stale_proof_replan` 六臂注入即红 | ✅ **完成（2026-09-24）**：见「PL-1-进度」+ `D-424` |
| **PL-1-进度** | ✅ **完成（2026-09-24）** — 提交见本轮 commit（`feat(pl-1): 过期证明重评`）；**生产代码**：`MineJob`（`proofWitness`/`attemptCount`/`proofExpired`/`neighbourhoodWitness` + `MAX_STALE_PROOF_RETRIES=3` + `staleProofRetries()` 读数）+ `MineTask.isHardTargetRefusal` 改 `public`（拒绝清单只许一处定义）· 夹具 `mine_vein_propagation`（`MIN_MINED` 12→28 + 新增第 3 条判据 `staleRetries>0` + 预算据实抬）· 门禁 `rule_stale_proof_replan`。<br>① ⭐ **先补读数、把前提否掉**：作业终态后**只读复评**每一格剩余矿石 ⇒ **孤儿=18、现在可规划=17**（9 `CURRENT` + 8 `TUNNEL`，只剩 1 格暂时性 `search_incomplete`）⇒ 卡点是"失败那一刻的证明被当成永久事实"，不是执行器贵。取证 `run/headless-logs/20260924-115423-single_mine_vein_propagation.log`（探针行 `[VeinProp复评探针]`）。<br>② **机制**：失败时记 **26 邻域通行性位串**（`canWalkThrough`，与规划器同源）；只有当前 ≠ 记录才重评；每格上限 3 次；**硬拒绝不记**（`D-323`）。<br>③ **实测**：`mined=30/30`（4421 / 4540 tick，258 / 264 s）· `staleRetries=18` · 复评探针 `孤儿=0` · `terminal=FAILED(product_not_collected)`（掉落物落进悬空场景下方 y=67，收不到 ⇒ 如实上报；夹具判据不看它）。<br>④ **红臂（真跑）**：`proofExpired` 恒 `false` ⇒ **`mined 12/30` · `staleRetries=0`** = 与改前逐字一致。<br>⑤ ⚠️ **两条预算事实（纠正设计文档 §九 的归因）**：切片 2 的 `2801 ticks` TIMEOUT 是 **harness 单项预算 2800**（不是作业预算、也不是夹具的 4600，后者旧配置下**永远轮不到**）—— 本轮就撞上：能力已把 `mined` 推到 ~19 而读数**没机会打印**（`…/20260924-115901-*`）；以及**每格收集阶段 ≈130 tick**（落物掉到 y=67 收不到）⇒ 挖穿 30 格 ≈4400 tick，预算据实抬到夹具 5400 / 作业 5200。<br>⑥ **门禁** `rule_stale_proof_replan` 六臂：① 过滤处必须问过期 ② 判据必须比较 ③ 必须有上限 ④ 失败时必须记证明 ⑤ 硬拒绝必须排除（且 `isHardTargetRefusal` 必须 public）⑥ harness 单项预算必须 > 夹具预算；**逐条注入即红**。<br>⑦ **边界**：只看 26 邻域（更远变化不改判决，登记在 `D-424` §五）；**执行器路线仍留档**（设计文档 §十一 路线 A），当前**没有任何夹具要求它** ⇒ 属 D 段产品线（鱼骨/探洞）。| ✅ 完成 |
| **P2** | **Movement 审查切片**（对齐 Baritone；`Ascend` 已做）：~~`Descend`/`Downward`（用户报**未复现**，暂不做回归）~~ → ⭐ **`Diagonal` ✅（2026-09-24）** → ⭐ **`Traverse`（含搭石/破通族）✅（2026-09-24）** → ⭐ **`Pillar` ✅（2026-09-24，`D-427`）** → ⭐ **`Fall` ✅（2026-09-24，`D-428`）**；每片 = Baritone `文件:行` 对照 + 差异表 + 判据 | 每片一条红/绿判据；同时把 `EXECUTOR_REFUSAL_CLASSES` 里**未指名的能力类码逐个指名/退役**（`CAPABILITY_UNRESOLVED_BUDGET` **双向钉死**：**已 26 → 25 → 21 → 19 → 13**） | `D-396` 尺子 | ✅ **四片全部完成（2026-09-24）**（`Diagonal`/`Traverse`/`Pillar`/`Fall`；余 = `P2c` 决策点，另立一行） |
| **P2-进度** | ✅ **`Diagonal` 切片完成（2026-09-24）** — 裁定与取证 = `D-425`。① **事实**：`DiagonalExecutionFactory.validate` 在执行侧**第二次手搓**了两侧格判定并给出 `DIAGONAL_SIDE_COLLISION`，而它与 `canTraverse` 内部的 `|dx|=|dz|=1` 分支**逐格相同**、又**后于**它执行 ⇒ 那个码**永远不可达**（死码）。② **实跑实证两步**：把那段**原地复活** ⇒ 夹具仍全绿（码仍是 `DIAGONAL_INVALID_PRECONDITION`）=**真的不可达**；把它**挪到共享谓词之前** ⇒ 夹具立刻红（`failures=1`，码变 `DIAGONAL_SIDE_COLLISION`）=判据真会咬。③ **改法**：删重复判定 + 退役死码 + 上限 26→25（读数 `未指名能力类=25/25`、`总准入码=75→74`）。④ **Baritone 对照**（`movements/MovementDiagonal.java:194-195`/`:220-221`/`:197-207`/`:229`）：Baritone 把侧格当**可挖（成本化）**、Alice 走 `D-076` 纯通行 ⇒ **差异有意保留并登记**。⑤ **门禁** `rule_diagonal_side_single_source` 五臂 + **五处注入即红**（A 复活重复判定 · B 删规划侧侧格检查 · C 生产路径写回死码 · D 掏空夹具判据 · E 上限脱钩）。⚠️ **D 第一次没红**（判据只咬方法名/字面量 ⇒ 恒真也能过）⇒ 改成咬断言表达式才红（记进 `D-425` §四）。⑥ **夹具** `single:place_step_diagonal` 新增 `SIDE` 用例：`checks=17 failures=0`（绿）/ 红臂 `failures=1`（日志 `run/headless-logs/20260924-123554-*` 绿 · `…/20260924-123826-*` 红）。⑦ ⭐ **方法论**：**死码删除的判据只能是静态门禁**（删前删后行为逐字相同 = "不可达"的定义）；夹具在本片的作用是**见证不可达**。<br>⑧ ⭐⭐ **`Traverse` 片也完成（同日，`D-426`）** —— Baritone 的 `MovementTraverse` 在 Alice 被拆成三支（`TRAVERSE`/`PLACE_STEP_AND_TRAVERSE`/`BREAK_AND_TRAVERSE`）⇒ 按「这一族」一起审。抓到两条真事实：**(a) 尺子漏码的第三种形态** = `invalid("CODE@" + "…")`（字面量 + 诊断串拼接）—— 原正则要求闭引号紧跟大写 ⇒ 实测**整条看不见 2 个码**（`PLACE_STEP_AND_TRAVERSE_NO_SWEEP`、`BREAK_AND_TRAVERSE_NO_MID_SUPPORT`）⇒ `总准入码` 一直是 74（实际 **76**）；修法 = 正则不要求闭引号 + 新增**人口下限** `REFUSAL_CODES_MIN=76`（正则退回严格 ⇒ 74 < 76 ⇒ 红）；**(b) 执行侧把 `bodyPassable` 手搓成两次 `canWalkThrough`**（`D-374` 建那个谓词就是为了收这类「只查一半」）⇒ 改成同一谓词（**行为逐字相同**）。**指名 6 个码**（4 个 place-step 族 + 2 个新看见的）⇒ `CAPABILITY_UNRESOLVED_BUDGET` **25 → 21**、`未指名能力类=21/21`、`总准入码=76`；`PLACE_RESOURCE_UNAVAILABLE` **有意留债**（执行期库存事实，不是移动谓词）。**新门禁** `rule_place_step_parity`（搭石族两侧谓词六条齐备 + 人口）+ **六处注入即红**（A 删执行侧 `canSweepPlayer`（`D-376` 回归）· B 删规划侧 `canWalkOn`（`D-379` 形态）· C 删表项 · D 执行侧退回手搓 · E 正则退回严格 · F 新增拼接未分类码）。**证据**：夹具 `single:place_step_descend_clearance` = **PASS checks=27 failures=0**（`code=PLACE_STEP_AND_TRAVERSE_NO_SWEEP@…` 逐字可见）· CORE **41/41 PASS**（逐步逐字相同，含总 tick）· `check-all` = `pass=20 warning=2 failed=0`。<br>⑨ ⭐ **`Pillar` 片也完成（同日，`D-427`）** —— 抓到**一处真缺陷**：`PillarExecutionFactory.validate` 的 `PILLAR_NOT_ON_GROUND` 原先**无条件**，而 `D-244` 的水柱支（`from`/`to` 都是水）里 `onGround` **恒假**（执行侧自己写着这条契约：`postconditionHolds` 水柱支 + `preconditionsHold()` 故意不查它）⇒ 灌水竖井 **≥3 格**（= 2 段以上 `PILLAR`）**第 2 段起**全被挡在**准入**处 = 「规划得到、执行不了」（`D-242` 家族残余）。**真机证据** = `docs/reviews/2026-09-21-掉落物在洞里被瞬退.md:554`（`PILLAR_NOT_ON_GROUND` ×11）；**为什么以前量不到** = `D-244` 的夹具 `FLOODED_SHAFT` 只有 **2 格水 = 恰好 1 段**。**改法**：门控加条件（口径 = **脚位那一格**，与执行器读的 `footCell` 同源）+ 两侧净空统一成 `bodyPassable`（原先 `validate`/`preconditionsHold` 各自手搓两次 `canWalkThrough`，规划侧用的是 `bodyPassable`）+ 步预算 `900 → 1300`（夹具内部上限 1200，`PL-1` 的教训）。**夹具**（既有 EXTRA 步 `pillar_execute` 新增相位，零新增电池步）：自建孤立 **4 格灌水竖井** + 悬空起点，**13 checks**；⭐ **规划级见证** = `[PILLAR -59→-58] [PILLAR -58→-57] [ASCEND →墙顶] REACHED pillars=2 swimPillars=2`（这条边**真的会被规划出来**）；**核心判据** = 第 2 段准入必须被接受 —— **绿**（`valid=true code=null`，`…/20260924-133837-…`）/**红臂**（`code=PILLAR_NOT_ON_GROUND`，`…/20260924-133937-…`）；另两组对照 = 站柱底必须接受 + **干地悬空必须仍拒且码逐字不变**。**新门禁** `rule_pillar_water_admission`（四臂）+ **七处注入全红**（⚠️ 臂④第一版**假绿**：只咬子串 `!bot.onGround()` 而夹具里有第二处同串 ⇒ 改成咬**合取形态** `&& !bot.onGround()`，与 `D-425` §四是同一课）。**两条新的夹具物理事实**：假人在水里**照常下沉**（≈10 tick 一格）· `teleport()` 会把 `onGround` 按成 false（所以「每 tick 拉回站位」会让柱底对照永远立不起来）。**码**：`PILLAR_HEAD_BLOCKED → MovementHelper.bodyPassable`、`PILLAR_PLACE_OCCUPIED → MovementHelper.canWalkThrough` ⇒ `CAPABILITY_UNRESOLVED_BUDGET` **21 → 19**、`总准入码=76`。<br>⑩ ⭐ **`Fall` 片完成（同日，`D-428`）** —— 本片**无行为改动**（方法同 `D-425`/`D-426`：逐字等价的重构 ⇒ 判据只能是静态门禁），产出三件：**(a) 三处同源**（边缘 `bodyPassable` / 落点 `canStandCentered` / 回收守卫两侧同步）—— `FALL` 的边缘与落点判据原先在 **三个现场**各写一份：规划侧 `appendFall`（用共享谓词 ✓）· 执行运行时 `preconditionsHold`（边缘手搓、落点用共享 ✓）· 执行准入 `validate`（**两处都手搓**）⇒ 统一（`bodyPassable:188-190`、`canStandCentered:207-211` 与手搓逐字相同）；**(b) 新门禁** `rule_fall_landing_parity`（四臂：边缘三处 / 落点三处 / 回收守卫两侧同步 / 两张表人口）+ **七处注入逐条单独开火全红**；**(c) 尺子第 4 批**：指名 6 个（`FALL_{COLUMN_BLOCKED,EDGE_BLOCKED,LANDING_BOTTOM_SLAB,LANDING_INVALID,NOT_RECOVERABLE_HEADROOM,NOT_RECOVERABLE_NO_FACE}`）⇒ `CAPABILITY_UNRESOLVED_BUDGET` **19 → 13**（读数 `未指名能力类=13/13`、`总准入码=76`）；**有意留债 2 个**并写明理由（`NOT_RECOVERABLE_NO_BLOCKS` = 库存事实，同 `PLACE_RESOURCE_UNAVAILABLE`；`LANDING_FLUID` = 任意流体内联判据，拿 `isWater` 当出处**是错的**）。⭐ **登记的决策点（本片一行没改）= 新行 `P2c`**：`FALL_NOT_ON_GROUND` 两侧一致，但规划侧查不到「起点是否落地」⇒ 浮空/水柱顶格起点的 FALL 边**会被规划出来又必然被拒**（`D-376` 家族）。两条候选改法都不干净（放宽执行侧 = 能力增加但**没有实测证据**；收紧规划侧**做不到** —— `canStandCentered(from)` 会连「浅水里站在水底」那种合法起点一起砍掉，因为脚下是源流体）⇒ 推荐**先不改** + 三条复核触发（见 `D-428` §六）。**证据**：既有 EXTRA 步 `fall_execute` PASS · CORE 逐步判决不变 · `check-all` = `pass=20 warning=2 failed=0`。| ✅ 本片完成 |
| **P2c** | ⭐ **`FALL_NOT_ON_GROUND`：起点不落地的 `FALL` 边「规划得到、执行不了」**（`D-428` §六，2026-09-24 登记，**本片一行没改**） | 判据草案 = ① 夹具量「计划里存在起点浮空/水柱顶格的 `FALL` 边」② 该边两侧准入比对（`validate` 必拒）③ 修后两侧一致；**先要用户拍方向** | `D-428` §六 | ✅ **已裁（用户 2026-09-24，`D-432`）= 先不改** ⇒ 保留守卫 + `TIMING` 分类、**代码一行不动**（复核触发：日志出现 `FALL_NOT_ON_GROUND` / 实测到浮空起点的 `FALL` 边 / 用户要求「水里也能自己掉下去」；要改则取「放宽执行侧」，与 `D-427` 对 `PILLAR` 的处置同形，**且必须先有夹具**）。⚠️ 同条纠正 `survey/32 §3`：它写「`D-430` 已按'先不改'归档 ⇒ **无需再裁**」**不成立**（`D-430 §四` 原文 = 两个开口点**仍未裁**）|
| **P2b** | `D-394` 遗留：**上升 Movement 的其他可能隐患**（用户改记口径）+ ~~段级 `resync` 重放同一条计划无次数上限~~ ⇒ ⚠️ **后半句依据过期（2026-09-24 核对：上界一直存在）**，改判为「**三处上界都没有门禁钉住**」 | 判据（不许无限重放）= 门禁钉住三处上界 + **三处注入即红**（见「P2b-进度」） | 无 | ✅ **完成（2026-09-24）**：`rule_replay_bounded`（重同步/重规划/段超时）· 提交见「P2b-进度」· **生产代码零改动**（本条是"把注释里的约定变成构建会红的事实"）|

| **P1-d** | ⭐ **`PARTIAL` 未被当作「本轮没评价完」**（`D-387` 家族；2026-09-25 登记，**用户未选路径 ⇒ 待裁**）：撞 50 ms 上限的 **502 次**搜索里 **480 次返回 `PARTIAL`**、22 次 `SEARCH_LIMIT`（480+22=502 精确闭合）；而 `MiningPlanner` **三条腿只认 `SEARCH_LIMIT`**（`:322` `selectBestApproach` / `:222` `planTunnel` / `:235` `planEnterTarget`，`exactTopK` 同形）⇒ `PARTIAL` 落到 `planned++` + `!reached()` ⇒ 报 `no_reachable` ⇒ 整体 **`found_but_unminable`（永久性理由）** ⇒ `MineJob` 的 40-tick 冷却（只认 `search_incomplete`，`:171`）**几乎不生效**（实测 `found_but_unminable` **307** : `search_incomplete` **87**）⇒ 每 tick 重烧 4 × 50 ms，而不是"烧一次 → 冷却 40 tick" | **A**（推荐）**修归因**：三条腿把 `PARTIAL` 与 `SEARCH_LIMIT` **同等**视为"本轮没评价完"，离线夹具先红后绿，**不动任何预算常量**（不触碰 `D-430`/`D-431`）；**B** 给挖矿候选链加**聚合**时间上限（= `P4″` 的机制读法，属新内核机制）；**C** 收紧毫秒轴（`D-431` 已否） | ✅ **已裁并落地（2026-09-25 用户拍板「先插 `P1-d`（路径 A）」）⇒ `D-435`**：`MiningPlanner` 新增唯一谓词 `inconclusive(SEARCH_LIMIT\|PARTIAL)` + `neverRan(...)`（区分"**没跑**"不计入 A2 与"**跑了没算完**"计入）+ `inconclusiveReason(PathPlan)` 唯一出路 + `SEARCH_INCOMPLETE` 常量单一出处；三条腿全走谓词，**并堵上两处掩蔽点**（`standableOnly` 早返回 / `planDirect` 结尾 —— 前者正是 `MiningProfile.STANDABLE_ONLY`，**鱼骨逐格 `MineTask` 走的就是它**）。夹具 `mining_search_limit_honesty` 新增 `P1-d` 臂：**与环境无关的真值表** + **条件式**行为臂（探针实测 `PARTIAL nodes=224 前缀=3`；红臂 ⇒ `failures=2` + `found_but_unminable` = 真机签名复现）；门禁 `rule_search_limit_not_unreachable` 扩写 + **六条注入臂全红**。✅ **真机已验（2026-09-25，`WINDOWS_CLIENT`，`D-435 §七`）**：`P1-d` 标记 ×18 ⇒ 新 jar 已加载；⭐ 真机撞限搜索 **144 次全是 `PARTIAL`、`SEARCH_LIMIT` 0 次**（基线 480:22）⇒ `P1-b` 那个洞在真机上是 **100%** 而不是 96%；结果：`found_but_unminable` **307 → 0** · `attempted` **50 → 0**（深层矿不再被永久拉黑）· `failed` 58 → 8 · `[Search] 超 tick 预算` 502 → 72 · **运行期 `Can't keep up` 2 → 0**（唯一那条在进世界/存档段）。⚠️ **但 `mined`/`breaks` 两轮都是 `0/64`/`0`** ⇒ 本片没改善「挖到深层矿」，其代价是**更早如实收工**；⭐⭐ **同轮触发了 `D-388 §五` 自己登记的回收条件**（「挖掘仍有进展，不是全变 `search_incomplete`」）⇒ **另立 `P4-深矿`**（见 `§11-B`）。全文 `docs/reviews/2026-09-25-mine循环198ms拆解.md` |

| **P4-深矿** | ⭐ **「矿在深层，单次搜索预算够不到」**（2026-09-25 真机复验顺带暴露；**用户当场指出该症状**）：同一 `mine here` 场景**两轮都是 `mined 0/64`、`breaks=0`**，失败**成片**为 `search_incomplete`（8 连即收工），搜索在 50 ms 处收手（`nodes=846…6541`，目标水平 20–32 格 / 下 6 格）。⇒ 本轮读数**正是 `D-388 §五` 自己登记的回收条件①**（「真机出现『原本能挖的目标现在成片 `search_incomplete`』⇒ 50 ms 太紧 ⇒ 在 50–200 之间取值并登记理由」）| ⚠️ **「50 ms 太紧」是假设不是结论**：两轮（含 50 ms 之前那轮）都 0 挖、目标 20–32 格外、**没有 200 ms 的对照轮** ⇒ 最小验证 = **`CorePathPlanner.DEFAULT_MAX_MILLIS` 临时 200** 跑同一场景看 `mined` 是否 > 0（一次客户端轮）。⚠️ 这是**内核行为常量**（`D-368`/`P4` 评审：不属于「尺子线」）⇒ **要么用户裁，要么不做**；⭐ 另一条路 = **鱼骨**（把「算不出怎么去的深处矿」换成「脚下/眼前这一格」，`D-386`/计划 §0）= 切片 2/3 | `D-388 §五` 回收条件① + `D-435 §七` | ⏸ **待裁**（2026-09-25）|

### C. 尺子线（不计能力进展；**落地必须能"注入即变红"**）

| # | 事项 | 判据 | 状态 |
|---|---|---|---|
| **P4** | **尺子 1「tick 负载预算」**：跨 tick 摊销/有界搜索/批量扫描必须声明 ms 或节点上限且 < 1 tick；并把**3 个未受门禁保护的 ms 常量**纳入（含 `SearchTickBudget.DEFAULT_MAX_MILLIS_PER_TICK=**400**`＝8 tick、`ESCAPE_MAX_MILLIS=100`、`PRECHECK_MAX_MILLIS=20`） | 挂 `kernel-predicates.py`；注入即红 | ✅ **完成（2026-09-24）**：新增门禁规则 `rule_tick_load_budget_declared`（`[P4·tick负载预算]`）—— **人口口径**：全仓毫秒常量必须 ≤`SEARCH_BUDGET_CEILING_MILLIS=60`（一个 tick 量级）或在 `TICK_BUDGET_EXEMPTIONS` 具名登记**理由 + 复核触发**；双向防漂移（登记项须存在且数值一致）。⭐ 规则当场抓到台账没点名的第 4 个常量 `EXPENSIVE_SEARCH_MILLIS=100`（分类阈值）。判据 = **人口 7（额度 4/豁免 3）可见 + 4 条注入臂全红**（未登记 / 值漂移 / 改名失配 / 缺复核触发）；取证 = `docs/reviews/2026-09-24-P4-tick负载预算门禁.md` |
| **P2b-进度** | ✅ **完成（2026-09-24）** — 门禁 `rule_replay_bounded` + **三处注入即红**；**生产代码一行未改**（核对后确认上界本来就在）。<br>① ⭐ **事实更正（先核对再动手）**：台账原句「段级 `resync` 重放同一条计划**无次数上限**」**不成立** —— `PathSession.MAX_RESYNCS = 5` 自 `d9e82c8`（2026-09-09，`D-047`）就存在，**比 `D-394`（2026-09-22）早 13 天**；`PathRetryRunner.DEFAULT_MAX_REPLANS = 2`；段超时分支走 `fail(TIMEOUT, "SEGMENT_TIMEOUT")` **结束会话**（不是清零续跑）。⇒ `D-394` 现场那串同址振荡（`ASCEND → resync → TRAVERSE → …`）里的 **"5 次 resync" 正好等于上限** = 上界当时就生效了。<br>② **但仍有一处真缺口**：这三处上界**没有任何门禁钉住** ⇒ 谁删掉一行守卫就真的变成"无限重放"（`D-394` 的振荡形态就是后果）。本条的产出 = 把它从"注释里的约定"变成"构建会红的事实"。<br>③ **门禁三条臂**：① `PathSession` 保留 `MAX_RESYNCS`，`tryResync()` 计数且在**任何状态变更之前**先判上界；② `PathRetryRunner` 的 `replans++` 处数**不得超过** `replans < maxReplans` 守卫数（多出来 = 无守卫自增）；③ 段超时分支必须是失败上报，且**不许**出现 `segmentTicks = 0`（段预算续杯 = 另一种"无限重放"写法）。<br>④ **三处注入即红**（逐条单独开火）：J1 删 `resyncs >= MAX_RESYNCS` 守卫；J2 去掉一处 `replans < maxReplans`；J3 把段超时的 `fail(...)` 换成 `segmentTicks = 0`。<br>⑤ **回归 + 门禁**：`single:place_step_descend_clearance`（`D-394` 修复的门禁臂所在步）= **PASS**（40 s）· `ALICE_MODS_DIR=$HOME/mc-client/mods tools/check-all.sh` = `pass=19 warning=2 failed=1`（唯一 FAIL = 既有红 `check-ref-integrity`）· 生产代码**零改动** ⇒ CORE 无变化风险。| ✅ 完成 |
| **P7** | `lumber_job` 在 CORE 报告里**单列一行**（防"唯一失败仍是既有 X"盖掉新失败） | CORE 汇报行改动 + 目视 | **✅ 完成（2026-09-24）**：做成「**非 PASS 步逐条单列**」并门禁化 —— 见「P7-进度」 |
| **P7-进度** | ✅ **完成（2026-09-24）** — `RegressionBatteryTask` 新增 `reportNonPassSteps()`（在 `finish()` 里紧接 `reportLedgerPopulation()` 调用）：任何 `!= PASS` 的步**自己占一行**，带 `details` 明细（失败码 / 跳过理由 / ticks），FAIL 走 `BotLog.warn`、SKIP 走 `info`；⭐ **0 条时也印计数行**（`非 PASS 步（0 条）：无 —— 本步清单内每一条都是 PASS`）——「全绿」必须来自**判据计数**，不是「我没看见那一行」（`Z4` 的同一条教训）。<br>**判据（比台账原话更硬：不只「目视」）**：① 门禁 `rule_battery_nonpass_steps_listed` 四臂 + **四处注入即红**（A 删调用 / B 把计数行藏进「非空才印」的守卫 / C 去 `details` / D 不遍历步骤表）；② ⭐ **真实非 PASS 演示**：临时撤掉 `A3` 的击杀分支（让夹具真红）跑 `single:kill_drop_provenance` ⇒ 报告出现 `非 PASS 步（1 条）：逐条如下` + `· kill_drop_provenance=FAIL（ticks=87 reason=…）`（warn 级，明细里连落物的 `delay/age/距离` 都在），还原后 ⇒ `非 PASS 步（0 条）`；佐证日志 `run/headless-logs/20260924-111753-*`（红）/ `…/20260924-111857-*`（绿）。<br>③ **CORE 复跑**：**41/41 PASS**（248 s，`run/headless-logs/20260924-112326-core.log`），报告里那行是 `非 PASS 步（0 条）`；⚠️ 该轮日志的**中文被写成 `?`**（启动器 locale，不是本改动）⇒ 显式 `LC_ALL=C.UTF-8` 复跑 `single:kill_drop_provenance` 得到逐字可读形态（`…/20260924-112518-*`）；已记进 `docs/CLOUD_MIGRATION.md` §9-41。|
| **P4′** | ⭐ **收紧项（由 P4 派生）**：把 `SearchTickBudget.DEFAULT_MAX_MILLIS_PER_TICK` 从 **400 ms（8 tick）** 收到 **≤60 ms**，复跑 CORE 逐步 diff | 先红后绿：注入 400 ⇒ 出现/加重 `Can't keep up`；收到 ≤60 ⇒ 消失。⭐ **可复现读数已经造出来了**（`D-429` 的台架 `tick_budget_bench`）：红臂 = 闸门全关 + 历史形态（单 tick **2578 ms** ⇒ 日志 `Can't keep up! Running 2198ms or 43 ticks behind`，同形于真机三次）；**闸门开着时生产形态最坏只 ~402 ms** ⇒ 400 与 60 都不产生告警（**原判据后半句在算术上不成立**）<br>⚠️ **更正（2026-09-24 核对）**：本行原写的"红臂 = 闸门全关 + 历史形态"**归属错** —— 那条 `Can't keep up` 在日志里出现在**闸门开着的 `PROD` 三格之后**、比任何 `UNGATED` 格**早 5 秒**（`:190` vs `:321`），而 vanilla 告警是**累计 2000 ms + 两次上报隔 15 s**（本轮**读字节码**核实）⇒ 该格那 2575 ms 单 tick **本该**报却被**限流压掉**。⇒ **裁定不变、`P4″` 反而更硬**（闸门管"一 tick 塞几次"，`P4″` 管"单次多长"）；⭐ **附带项** = 台架"同轮连跑三配置"与 15 s 限流相冲 ⇒ 红臂可判性要补（见 `D-429 §七`）。取证 = `docs/reviews/2026-09-24-survey32-核对.md §3` | P4（已完成）+ `D-429` | ✅ **已裁（用户 2026-09-24，`D-431`）= 选项 ①** ⇒ 新项 **`P4″`**（不动毫秒轴，改给重消费者的 `SearchBudget` 加时间上限）**待落地**。裁定依据（`docs/reviews/2026-09-24-P4′台架与读数.md`）：① ⭐ 推荐 = **不动毫秒轴，改给重消费者的 `SearchBudget` 加时间上限**（新项 `P4″`，因为最坏 tick 由**单次搜索时长**决定）；② 维持 400（最坏 tick ~400 ms 留着）；③ 收紧到 60（收益 402→100 ms，代价 = 同 tick 拒绝数 5→11，须先跑 CORE 逐步 diff） |
| **P4″** | ⭐ **`P4′` 的裁定产物（`D-431`，2026-09-24 用户拍板）**：不动 `SearchTickBudget` 毫秒兜底轴（保持 400 ms），**给重消费者自己的 `SearchBudget` 加时间上限**（今天进入生产的是挖矿候选那条链，`maxMillis = 0`） | ① 台架 `tick_budget_bench` 的 **`LEGACY` 格**在生产形态下退到 **≤50 ms/次**；② 同时 **`MINING` 格拒绝数不增**（当前 PROD 放行 8 / 拒 5）；③ 判据必须区分「单次搜索时长」（本项）与「一 tick 能塞几次」（`P4′` 的闸门轴） | `D-429` 台架读数 + `D-431` | ✅ **已关账（2026-09-25 用户拍板，`D-434`）= 读法 R1，不落地任何代码**：判据① 的主语（"无时间上限的重消费者"）**在生产侧不存在** —— 逐点核实：生产侧 **零个** `maxMillis = 0`（`WALK_BUDGET (20000, **50**)` 覆盖 `of`/`withWorldModification`/**`miningApproach`**、逃生 `(4000,100)`、维生预检 `20`；`SearchBudget.UNLIMITED` 生产侧 0 命中）⇒ **判据① 已被 `D-388` 提前满足**（`DEFAULT_MAX_MILLIS: 200 → 50` + 门禁 `SEARCH_BUDGET_CEILING_MILLIS ≤ 60`）。`D-431` 另两条照旧成立（不动毫秒轴必须保持 400 · 两把轴必须分开）。台架 `LEGACY` 负载**保留为"闸门关掉会长什么样"的历史对照臂**，但**不再充当任何判据的主语**。⭐ **症状仍在 ⇒ 已另立 `P1-d`**（见 `§11-B`）：真机 ≈200 ms/轮 = **4 次各自烧满 50 ms** 的搜索（4×50=200 < 400 ⇒ A1 闸门整场 0 次），根因不在"单次无上限" |

### D. 架构 / 产品线

| # | 事项 | 说明 | 状态 |
|---|---|---|---|
| **P6** | **搜索线程化**（`D-369 §四`）—— `D-388 §五` 自证"不等于根治"；**多 bot 并行的前提** | 需线程安全的世界视图（架构级） | 登记 |
| **产品** | **三天挖矿任务线**：③ 鱼骨（`D-386` 设计已定档，切片 1–4 未开工）→ ① 跟随 → ② 探洞 | 内核/挖掘逻辑修复完成后进入 | 登记 |

### F. 2026-09-22 新增（`Z1` 的连带 + **CORE 瘦身的副产品**）—— 明细见 `D-407`/`D-408`

| # | 事项 | 判据 / 触发 | 状态 |
|---|---|---|---|
| **CORE 瘦身** | 用户「整理下 CORE 内容，**次要的剔除**」⇒ **降级 12 步 ⇒ EXTRA**（一步没删）：CORE **53 → 41**（BASELINE 15 + MAIN 26） | `D-201` 附注一：**复跑 CORE 逐步 diff** ⇒ 除被撤 12 步外**判决逐条不变、存活步 0 位移**；唯一红项 = `lumber_job`（既有） | **✅ 完成**（`D-408`；`PROFILE=CORE baseline=15 main=26 extra_skipped=52 (passed=40/41) ticks=4552`） |
| **真缺陷①** | ⭐ `CleanupWrappedTask` 把内层 `FAILED` 也报 `DONE` ⇒ `fall_execute`/`pillar_execute` **结构上不可能红**（判据全红仍记 PASS） | 修 = 内层终态透传；**注入证明**见 `D-408` | **✅ 已修**（`D-408`） |
| **真缺陷②** | ⭐ 电池把**场景函数跑在冷区块上**（旧顺序 `scenes → provision`；编排器早是 `provision → scenes`）⇒ `/fill` 静默失败、`/setblock` 随区块卸载回滚 ⇒ 夹具拿"没有地形"的世界做判断（`craft_table` 实测） | 修 = 电池侧对齐顺序 + 场景函数**返回值日志**（`scene rc=`，`rc<=0` 告警 —— `D-251` 的教训） | **✅ 已修**（`D-408`；`single:craft_table` 由红转绿） |
| **待用户拍板 ①** | `craft_table` 保不保：它**是 CORE 里唯一的原版 3×3 `CraftingMenu` 执行覆盖**（`craft_goal` 只走随身 2×2）⇒ 本轮**保留**（与 `D-201` 名单有异议） | 用户一句确认即定 | ✅ **已裁（用户 2026-09-24，`D-432`）= 保**：它是**覆盖缺口不是次要项** ⇒ `D-201` 的「次要即剔除」**不覆盖**它（异议由用户裁定保留）。复核触发 = `craft_table` 连续红、或与 `craft_station` 覆盖面被证明重复 |
| **待用户拍板 ② → 已裁** | `survival_exit` 与 `D-398` 的冲突（逃生垫在**区外** ⇒ 按裁定不记账；`D-245` 当年要求"记账交玩家回收"）。⭐ **用户 2026-09-23 裁定：「不改，区域外逃生不记账」**（`D-420`）⇒ `D-398` 保持**无条件**，**不开例外**；`D-245` 的记账半边**收窄到保护区内**（区外只剩"不自动回收"，由世界事实守）。代码**一行不动**（今天的实现就是这个裁决）。**复核触发**：`D-245` 触发① 真的出现野外逃生垫堆积 / `outsideSkips` 明显长起来 / 出现"想一键清野外残留"的实际需求 ⇒ 那时形状 = **有界 + 不构成闭合义务 + 新 `WriteReason` 值**（`D-420 §三`） | **✅ 已裁（不改）** |
| **待办（小）** | `mine_regression` 超时可能记 `DONE`（`:202-205` vs `:595`）；`decision_contract` 的 `SUMMARY checks=10` 是硬编码（真实 14 站点）；`mine_regression` 用例级 SKIP 不上报（CORE 里 2 例 SKIP 而过 `skipped=0`）；三处预算 < 任务 `MAX_TICKS`（失败码被 `TIMEOUT` 盖掉） | 各自一条夹具/门禁判据 | 登记（来自 `docs/reviews/2026-09-22-CORE步表梳理与剔除建议.md` §4-D7） |
| **待办（中）** | `D5` 三条归因步（`mine_no_tool`/`mine_stale`/`mine_budget`）是否三合一（**不省时间、只省步位，需重做顺序论证**）· `D6` `mine_failure_visible` 的"诊断"半 | — | 登记（报告 §4-D5/D6） |
| **登记（小·覆盖缺口）** | ⭐ `survival_exit` 的 `D-245 × D-398` 判据是**一个断言两个期望值**（按运行时 `pillarProtected` 选），实测走的是**区外**那半（`inZone=0`）⇒ **区内那半从未被执行过**。而 `D-420` 把 `D-245` 的记账半边**只**留在保护区内 ⇒ **它成了唯一幸存、却没被执行过的半条**。修法 = 补一条**区内臂**（逃生竖坑放进自建认领区、断言 `TEMP` 条目存在）；⚠️ 会动 CORE 步的 tick 预算 ⇒ 做的时候要复跑 CORE 逐步 diff | 登记（零概念成本，建议并进任一次 `survival_exit` 改动） |
| **观察** | `head_blocked_route_closure`（EXTRA）在 `module:pathing` 里连红（历史 1 PASS/3 FAIL）· `mine_reach_probe`（EXTRA）自述"真机存档可能已被覆盖 ⇒ 本次结论不作数" ⇒ 两步都**依赖真机存档的具体地形**，不是 Z1/瘦身引入 | 需要时各补一条"显式自证前提" | 登记 |

### G. 2026-09-23 拉取登记：`survey/29` + `survey/30`（**只登记，未实现**）

> 来源：`survey/29-讨论记录-20260923.md`（473 行）· `survey/30-诊断与下一步表-20260923.md`（277 行）；
> 远端 `98bd354..2f10f66`（`git pull --ff-only`）。两份都**自称不是待办/不是授权**。
> ⭐ **我的逐条核对 + 复算结果 = `docs/reviews/2026-09-23-survey29-30-核对.md`**（含反证检查与诚实边界）。
> 报告基线 `d6db852` 的父提交 = `98bd354`（`Z1` 真机确认）⇒ **报告已含 `Z1`/CORE 瘦身，不是过期视角**。

| # | 事项 | 判据 / 状态 | 我的评价 |
|---|---|---|---|
| **A1** | ⭐ `LumberJob` 终态幂等（勘测侧记作"补闩锁"） | 判据 = `single:lumber_job` 期望 `idempotent=true`（修前 false），**先红后绿** | ⚠️ **措辞已更正（见 A1′）**：`LumberJob` **有**重入守卫，缺的是"守卫记状态" |
| **A1′** | ⭐⭐ **更正（2026-09-23 二次核对，当场读码 + 日志自证）**：形状 = `job/lumber/LumberJob.java:304-306` `if (terminated) return Task.Status.DONE;` ⇒ **终态是 FAILED 时再 tick 返回 DONE**（违反 `D-178`）。**同一形状全仓 6 处**：`MineJob:338-339`（**有 4 条 FAILED 出口 ⇒ 潜在同类隐患**）· `CollectJob:120-121` · `LumberJob:304-305` · `RestoreScopeTask:142-143` · `LumberFailureCheckTask:103-104` · `ClearGuardCheckTask:89-90`；**正解只有 `MineTask:87/328-329`**（`D-175`） | ✅ **已修 + 已门禁化（`D-418`，2026-09-23）**：⚠️ 台账此前写的"**待做**"是**过期状态** —— 六处**早已**在 `51a66d3`（`C5` 那批）统一成 `MineTask` 的正解形状（存 `Task.Status terminalStatus` + `tick()` 开头回放）。本轮补的是**"没人守"**：`kernel-predicates` 新增 `rule_terminal_latch_replays_status`（`[A1′·终态闩锁回放]`）= ① 不许出现硬编码 `DONE`/`FAILED` 的终态守卫 ② 声明了 `(?:Task\.)?Status terminalStatus` 的文件必须有回放行 ③ 站点数 **≥7**（人口）。**注入即红**（去掉 `ClearGuardCheckTask` 的回放 ⇒ 两条红）。⚠️ 规则第一版连踩 4 次读错（注释里的旧写法 / 同名方法 `record.terminalStatus()` / 带花括号的跨行回放 / `MineTask` 用简写 `Status`）⇒ 全部写进规则 docstring | ✅ **完成** |
| **A1″** | ⭐⭐ **更正：补闩锁"不足以让 CORE 转绿"** —— 该步 PASS 判据 = `RegressionBatteryTask:757` `status == DONE && idempotent`，而它跑真 `LumberJob` 且**无 `skipWhen`/`withDoneWhen`**（`LumberModule:83-87`），实测 `FAIL ticks=995 reason=no_reachable_candidate` ⇒ **`status` 从不是 DONE** ⇒ `idempotent` 修好也只是从 FAIL 到 FAIL | 推论：⭐ **`D-352` 缓存（只写 PASS，`headless-battery.sh:195/377-390`）不会因 A1′ 命中** ⇒ "CORE 反馈 分钟→秒"**只归 A2** | **登记**（防把它当成本解法） |
| **C4** | ⭐⭐⭐ **已查清（`D-409`）**：`lumber_job` 连红 35 轮的根因 = **夹具场景落在玩家自己的 FTB 认领里**（**假红**，不是内核缺陷/能力缺口）。取证：① 被拒破坏**全在 `z≥208`**、成功**全在 `z≤207`**（与方块/工具无关）② 母本 `run/world-pristine/ftbchunks/6ffe1112-….snbt` 有 `[{x:1,z:13}{x:2,z:14}{x:2,z:13}{x:1,z:14}]`（建于 2026-09-18）⇒ 覆盖 `x[16,47] z[208,239]`，`LumberCourseAnchor` 几乎全在内 ③ 全 `src/` 取消破坏事件 **0 处**（只能是被第三方拦）④ **归因探针**（已永久保留，`BlockBreakSession.thirdPartyNote()`）⇒ 被拒行 **7/7** `FTB=ftb_claim_denied` ⑤ ⭐ **A/B 反向对照**：移开 `ftbchunks/`（已复位）⇒ 拒绝 7+→**0**、终态 `no_reachable_candidate`→**`partial_quota`**、**砍完 3 棵橡树** | ⭐ **第二层（A/B 才露出来）**：配额 4 棵 vs 可用候选 5 棵，其中 1 棵是"期望被拒的对照"（`LumberCourseAnchor:26`）、`33,64,208` **规划期** `no_valid_standing_point` 后被 `already_attempted` **永久排除**（`LumberJob:646`）⇒ 仍 FAIL。⭐ **第三层（独立）**：`idempotent=false` = 闩锁硬编码（见 A1′）。⚠️ **诚实边界**：`33,64,208` 的站位失败**是场景几何还是内核问题未定**；`destroyBlock=false` 未逐行反汇编核 Forge | **✅ 已查清**（完整取证 = `docs/reviews/2026-09-23-lumber_job根因-FTB认领.md`） |
| **C5** | ⭐ **夹具的第三方前提**（`D-409 §五` 的教训）：世界母本 = 玩家真实存档副本 ⇒ 夹具**继承玩家的 FTB 认领**；而"夹具自带前提"这条纪律**只覆盖了我方保护区**（`FixtureZone`），第三方那一半**没人管** ⇒ 踩进去只会得到**误导性的内核失败码**（`no_reachable_candidate`）而不是"前提不成立" | **✅ 完成（`D-410`）**：新增 `task/FixtureThirdParty`（逐区块问 **FTB 自己的**裁决函数；不改世界；fail-open 同 `D-326`）+ `task/PremiseGateTask`（**首 tick 之前**求值的闸门 —— 用 `skipWhen` 不行，它是**终态之后**才求值，任务仍会打出误导码）；接线 = `LumberModule` **4 步**（`lumber_failure`/`lumber_job`/`region_maintain`/**`region_sweep_e2e`**）；判据 = `single:lumber_job` ⇒ **`SKIP ticks=0` + `DEGRADED`**、`[Premise]` **逐字点名 4 个被拦区块**（与认领文件恰好一致）、误导码 **0**、用时 **72 s→21 s**；`module:lumber` ⇒ **`failures=0 skipped=4`**（147 s→26 s）；⭐ **CORE 逐步 diff = 只此 1 条变化**（`lumber_job: FAIL→SKIP`，42 步列表不变，4552→3578 tick）。⚠️ **多抓出第 4 个受害夹具**：`region_sweep_e2e` **在代码里自建场景**（用 `LumberCourseAnchor.region()`）⇒ 先前"115 个场景函数"的扫描**漏掉它**；认领同样拦**放置**（`[WRITE-REFUSED] plant pos=23,64,211` ×113）⇒ **纪律：排查"夹具踩到别人的地"不能只扫数据包函数** |
| **C6** | ⛔ **已否决（`D-413`，2026-09-23）**：搬迁把场景搬进了"未被验证的宿主" —— `D-412` 修好后实测**每棵树都 `trunk_too_tall(unreachable=4,hard=2)`**（清空盒只到 `x17..31` 而树长到 `x35`；那棵树冠通道红线也靠宿主地形）；且**基地附近没有空位**（扫遍偏移，`dx=±32` 已是最小代价）。⇒ **改道** = 清掉**夹具世界**的第三方认领（与"清 `alice_*.dat`"同源；`break_refused` 运行期自造认领 ⇒ 无夹具依赖预存认领） | 结果：`single:lumber_job` **4/4 PASS** · `module:lumber` **6/6 PASS** · ⭐ **CORE `passed=41/41 → PASS`（35+ 轮首次全绿）** · ⭐ **CORE 缓存命中：用时 0s** | **✅ 结案（改道实现）** |
| **C7** | ⭐⭐ **真 infra 缺陷（`D-412`）**：场景数据包被**同命名空间的旧副本盖住**（`sync-windows-artifact.sh:97` 把备份放进 `datapacks/` ⇒ `alice_test.bak.*` 也是活包且后加载者胜）⇒ 跑的是旧场景，**静默**且能骗过 `scene rc=`/离线自检 | **✅ 已修**：电池清同名旧包 + **断言提供者恰好 1 个**；同步工具把备份移出 `datapacks/`。<br>⭐ **影响面已核实（不是"可能很严重"）**：`git log --since="2026-09-20 23:51" -- tools/test-scenes/` 里 `.mcfunction` 改动 = **0** ⇒ **历史损失 = 0**；被吞掉的是今天那次未提交的位移（也就是它暴露了本缺陷） | **✅ 完成** |
| **A2** | ⭐ **逐步缓存可行性验证**（先验后改机制） | ✅ **已查清（2026-09-23，`D-417`）：结论 = 放弃「跳过执行 + 复用判决」形态**。整份取证 = `docs/reviews/2026-09-23-A2-逐步缓存可行性.md`。两个方向都跑了：① **自足性** = 41 步 `single:<step>` vs 它在 CORE 的位置 ⇒ **判决 41/41 一致（0 翻转）**、ticks **36/41 逐字相同**（唯一可复现真差 = `pathing` 684 → 714/714，与"它不传送、起点由前序决定"吻合）；② **消融** = 归档 `53→41` 那一对**独立复算** ⇒ **2 步翻**（`craft_table` 是**真·隐含前置**、靠改电池顺序修好；⚠️ 该窗口另有 `Z1`+改序落地 ⇒ 不干净）。**放弃的三条支柱**：（a）**成本账**：实测**每步 wall 的 79% 是冷启动**（358 s / 451 s；`mine_menu` 1 tick 却花 24 s）⇒ 逐步缓存只能省那 21%，**给不出「分钟→秒」**；（b）**粒度不存在**：CORE 步多数要走内核 ⇒ 保守完备的逐步指纹退化成 `src/` 全量 = `D-352` 已有的全局指纹；放宽粒度 = 陈旧判决假绿；（c）**退化不可见**：`write_policy` 的判决人口 = **整轮前缀**，两条判据是 `isEmpty()` 而**全仓没有"审计了多少次写入"的人口计数**（`WritePolicyCheckTask.java:442-449` + `WritePolicyMatrix.java:888,893`）⇒ 跳过前序会静默变**空集真**，而判决表面仍"逐条一致"（矩阵第 36 步 `write_policy PASS/PASS` 就是它）。**正向产出**：41 步 solo 判决/ticks 表（`run/a2-solo-matrix.tsv` + `run/a2-solo-logs/*.json`）可当 `single:` 通道的**对照基线** | ⭐ **原评（"本轮唯一乘法级杠杆"）作废**：它承诺的东西**挂不到这个机制上**（支柱 a）。⭐ **替代杠杆 = `A2′`**（见下） |
| **A2′** | ⭐ **一次引导跑多个点名步**（`single:<步名>[,<步名>…]`）—— ✅ **完成（2026-09-24）**：`HeadlessBattery` 把 `single:` 解析成逗号/全角逗号/空白分隔的清单（**逐个**快速失败校验，任一未知 ⇒ 整轮不跑），顺序仍按**步骤表声明顺序**；`single:<步名>` 单步形态的行为逐字不变。判据 = 与逐条 solo 判决一致，实测：`single:break_refused` / `single:restore_underfoot_safety` / `single:lossy_write_accounted` 各自 **PASS**，组合 `single:restore_underfoot_safety,break_refused` **PASS**（57 s）。⭐ **组合跑第一次是 FAIL，抓出一条真缺陷**（见下「夹具卫生」）——这正是这条判据的价值。门禁同步：`tools/step-names.py` 的正则原先只吃**第一个**名字 ⇒ 清单里后面写错的步名会静默漏掉；现已逐个校验（引用 247 → 248 处）。| ✅ 完成 |
| **夹具卫生（`A2′` 抓到）** | ⭐ **收尾必须回"进来时的脚位"，不许回自己的场景原点**：`RestoreUnderfootSafetyCheckTask` / `LossyWriteAccountedCheckTask` 的 `cleanup` 原来传送到自己的 `ORIGIN`，而那时场景方块已还原成原始地形 ⇒ 那一格常常是**空中** ⇒ bot 掉下去（实测 combo 里后一步继承到 `y≈44`，全部 `BREAK_OUT_OF_REACH`）。修法：进场景**之前**记 `entryFoot`，收尾传送回它（两个夹具都已修，含失败路径）。⚠️ 两个夹具都是 **EXTRA** ⇒ 单跑/CORE 都看不见这条（只有组合点名能暴露）。| ✅ 完成 |
| **A3** | ⭐ **击杀来源归属**（`survey/29 §2.1`：击杀产物落 `FOREIGN` ⇒ 捡不起且静默） | 判据 = 新夹具：生成动物 → 击杀 → 断言 `provenance != FOREIGN`（**先红**）。**代码链我已核实**（两个 matcher 都只遍历 `recentBreaks`）+ **反证检查**：`src/` 无任何 `LivingDropsEvent`/`LivingDeathEvent` 消费者 ⇒ 无补偿路径 | 同意"**归属先行**"（先只登记、不写攻击）；端到端仍须夹具复现 ⇒ **✅ 完成（2026-09-24）**：夹具先红后绿 + 门禁六条注入臂全红，见「A3-进度」· 提交 `7cccee1` |
| **A3-进度** | ✅ **完成（2026-09-24）** — 夹具 `single:kill_drop_provenance` **PASS checks=11 failures=0**（两次复跑：ticks=62 / 53、`pickupATicks=34 / 25`）· 提交 `7cccee1`。<br>① **先红**（提交 `4c00fa5`，日志 `run/headless-logs/20260924-105859-single_kill_drop_provenance.log`）：`FAIL checks=11 failures=3 ticks=72` · `provA=[FOREIGN, FOREIGN]` · `passiveA=false` · `pickedA=0` ⇒ 勘测侧的读码推断（`survey/29 §2.1`）**逐字复现**：bot 亲手 `playerAttack(bot)` 打死牛（`died=true`、`killer=<bot>`），皮革/牛肉仍落 `FOREIGN`、被动闸门直接拦。<br>② **实现**：`ScopeBuffer` 第三条归属通道（`onLivingDeath` + `recentKills` + `matchKillOrigin`），`pendingEntry` 优先级 = **破坏直配 → 我方击杀 → 间接松窗**（更精确的证据优先；仍在**入队那一刻**解析 = `D-348` 纪律）；归因 = `getKillCredit()`（覆盖「打伤后死于火焰/坠落」）**且必须再判是不是玩家** → 否则看伤害来源实体；killer 还必须等于本作用域 owner（别人杀的不领）。新枚举 `Provenance.OURS_KILL` + 具名能力 `drop.ours_kill`（默认 `AUTO`，可单独调紧）。击杀记录同时当**来源**（= 死亡点）⇒ `liveDrops()` 看得见、收集 Job 能收（授权仍由 `mayCollect` 把关）。<br>③ **门禁** `rule_kill_drop_attributed` 四条臂 + **六处注入即红**（A 去 owner 比对 / B 去「是不是玩家」 / C 删击杀分支 / D 挪到间接松窗之后 / E 删 `DEFAULTS` 登记 / F 在 `BlockBreakSession` 伪造 `OURS_KILL`）。⚠️ **B 第一版没红**：判据只看「文件里有没有 `instanceof Player`」，而伤害来源那一处还在 ⇒ 收紧成「**第一处** instanceof 必须落在 `getKillCredit()` 与 `getSource()` 之间」才红（同 `RC4` 的 I3 教训）。<br>④ ⭐ **夹具陷阱（新的，已写进夹具 javadoc）**：第一版用**固定 30 tick** 判「捡起来了没」⇒ **同一份代码两次判决不同**（`pickedA=0` / `pickedA=4`）。临时探针的原始读数给出机制：落物生成后 `Age=0`/`PickupDelay=10` **冻住约 10 tick**（`pt=1..10` 全 `age=0 delay=10`，`pt=15` 才 `age=3 delay=7`）⇒ `击杀 → 能捡` 实测 **≈40 tick**，30 tick 正好卡在边界 ⇒ 判据改成**「等条件 + 上限」**（正向等「背包真的多了」、反向等「闸门真的被撞到」且至少 20 tick，防「还没轮到它就宣布拦住了」）。<br>⑤ **回归 + 门禁**：CORE **41/41 PASS**（`ALICE_CLIENT_MODS=$HOME/mc-client/mods`，249 s，`…/20260924-111254-core.log`）· `ALICE_MODS_DIR=$HOME/mc-client/mods tools/check-all.sh` = `pass=20 warning=2 failed=0` · `docs/CAPABILITY_LIST.md` 重生成（步 94 → **95**，CORE 仍 41）。<br>⑥ **诚实边界**：本项**不写攻击**（`survey/30` 的「归属先行」）· 「我方宠物/召唤物击杀」未做区分（今天不存在宠物）· 投射物击杀由 `getSource().getEntity()` 覆盖但**未单独夹具** · 模组自定义掉落（`LivingDropsEvent` 改产物/延迟生成）未覆盖（复核触发写在 `D-422`）。|
| **A4** | 假绿夹具重做（`restore_underfoot_safety`） | ✅ **完成（`D-418`，2026-09-23）**：⭐ **根因不是几何** —— 是夹具**把"作用域 / 任务区"的顺序写反**（先 `FixtureZone.protect`（任务区挂外层步作用域）→ 再 `openScope`（当前作用域换人）），而 `TaskZoneRegistry.zoneOf` **按"当前作用域"**取任务区 ⇒ 回收期写入命中 `ZoneAuthority: protected_area` ⇒ `TARGET_NOT_BREAKABLE` ⇒ `restored=0`、真破坏 **0** 次（"不许摔"因此**空跑**）。修 = 交换成**先 `openScope` 后 `protect`**（参照 `LedgerZoneScopeCheckTask`）；判据从 3 条变 **4 条**：新增**正向对照**（世界事实 `pendingTemporary==1`）并**收紧**脚下守卫那条为**无条件点名** `underfoot_unsafe`。**证据链**：先红（`remaining=3`）→ 加镐**仍红**（证伪"没工具"假设）→ 分层探针得 `refusal=protected_area` → 修 → 绿（`restored=2 skipped=1 remaining=1`、真破坏 **2** 次）→ **双向注入**（关守卫 / 改回旧顺序）各红。⭐ 两处**旧注释是误诊**（"几何拆不到"），已就地纠正。取证 = `docs/reviews/2026-09-23-A4-脚下守卫夹具根因.md` | ✅ 完成（新观察见下） |
| **B1** | 「下一步表」最小版（五列 + 一条门禁 + `tools/next-step.sh`） | 判据 = `exit` 指向不存在的步 ⇒ 红；在飞项 scope 相交 ⇒ 红 | ⚠️ **我持保留**：它引入第五条真相源，且**不降低成本本身**（降成本的是 A1/A2）。⭐ **用户 2026-09-23 裁定：暂不做**（"先降成本"）⇒ 登记为**不做**，等成本真降下来再看 |
| **B2** | 「底线不许进配置面」门禁（+ 必选/可选守卫分界） | ✅ **完成（`D-418`，2026-09-23）**：真源 = `RiskSwitches` 的 `KNOWN`/`OPTIONAL`（每开关必须写"为什么它可选"）/`BOTTOM_LINES`；门禁 `tools/risk-surface.py`（挂进 `check-all`）五条断言 = ① `KNOWN==OPTIONAL.keySet()` 双向 ② `KNOWN∩底线=∅` ③ 三表非空 + 理由≥20 字（**人口**）④ 命令面 `riskSwitch` 名必须已分类且不许出现底线名 ⑤ `src/` 不许出现 `ForgeConfigSpec`（今天 Alice **没有配置面** —— 把事实钉住）。**注入四臂全红**、基线绿。实测：可选 3 / 命令面只暴露 `descend_overshoot` / 底线 3 | ✅ 完成 |
| **B3** | 能力清单从代码生成 + 双向防漂移门禁（记忆库的前置） | ✅ **完成（`D-419`，2026-09-23）**：`docs/CAPABILITY_LIST.md` = **生成物**（`tools/capability-list.py`，264 行），把 8 个**已有**单一出处聚合成人读清单（`GoalAction` 白名单 / `JobRequest.Kind`+`JobKindContract` / `MovementType` / `CheckModules.ALL`+模块 `CheckStep`+`CURATION` / `MACHINE_MAP.csv` / `RiskSwitches` / `ZoneAuthority`）—— **不引入第五个真相源**。门禁 `tools/check-capability-list.sh`（挂进 `check-all`）的牙齿是**跨出处**双向（`A1` 步声明↔`CURATION`、⭐`A2` 模块 `CheckProfile`↔`CURATION` 档位、`A3` 注册表↔电池成员、`A4` `Kind`↔契约表**反向**、`A5` `MovementType`↔`changesWorld()`、`A6` 开关表↔`KNOWN`）+ **人口下限** + **独立计数**（**解析崩塌即红**）+ `A9` 手写文档不许再自称清单。⭐ **首跑就绿**（A1/A2/A3/A4/A5 今天确实都成立，其中 **A2/A3 反向/A4 反向此前零断言**）；**注入 13 臂全红**。**同时消灭第二真相源**：`docs/BATTERY_CURATION.md` §2 的手写清单（标题计数漂到 15/35/15、真值 15/26/52，且 `decision_contract` 重复列了两次）**降级为"理由叙述"**，项数与清单只许在生成物里。⚠️ **未达到**：清单**还没进 prompt**（`§3.8⑥` 原文要求）⇒ **防分叉只对"表"生效、对 LLM 尚未生效**。取证 = `docs/reviews/2026-09-23-B3-能力清单生成与防漂移门禁.md` | ✅ 完成（进 prompt 另立）。**验证**：静态 `check-all` = 21/1/0 · `ALICE_HEADLESS=1` = **22/0/0**（电池真跑，CORE **41/41**、251 s）|
| **观察·新** | ⭐ `A4` 修好后暴露：`restore_underfoot_safety` 的 `notes=drops_left=2`、`recovered=0` ⇒ **拆下来的两块圆石没被收回背包**（`RestoreScopeTask` 只如实记 note、不判红） | 触发条件 = 出现一次"回收材料必须真的回到背包"的场景；与 `P3`（掉落物收集授权）同族 | 登记（不阻塞） |
| **Q-1…Q-22** | 勘测侧 19 条待裁定（`Q-20` 预算制 / `Q-21` 规范化哈希 / `Q-22` 能力清单 = **用户已同意**） | **指针 = `survey/29 §5`；本表不抄** | ⏳ 待用户逐条裁 |
| ⛔ 不做 | 猎杀功能本体 · 怪物威胁线 · 队列本体（`GoalRecord`）· 记忆库 · **任何新能力**（`survey/30 §5.1`） | — | **同意** |

### H. 2026-09-23 深夜拉取登记：`survey/31`（云端迁移方案）—— **只登记，未实现**

> 来源：`survey/31-云端迁移方案-20260923.md`（474 行，远端 `88a9262` + `2ae2256`；含 `§14 全搬方案（16G 服务器）`）。
> 报告自称「**不是待办、不是排期、不是实现授权，不改任何项目契约**」。
> ⭐ **我的逐条复算 + 当场补验 = `docs/reviews/2026-09-23-survey31-核对.md`**（可复算项 9 条：**7 条全对**、
> 1 条数量略有出入（耦合脚本 2→3 个、`AGENTS.md` 7→5 行，**方向对**）、1 条**行号漂 ~25 行**（原因可复算：
> 报告基线 `2f10f66` 之后 `aec21fc` 改过 `headless-battery.sh`））。

| # | 事项 | 事实 / 判据 | 状态 |
|---|---|---|---|
| **S31-1** | 方案本身（**控制面上云、测试面留本地**） | 报告 §0 的核心判断成立；报告自己给了「先跑 §12 Codespaces 零期」的验法 | ⭐ **同意**（用户拍板才动；不进本项目排期） |
| **S31-2** | ⭐ 我**当场补验**了报告标「⚠️未核实」的第 2 项：`dsh web` 的绑定与鉴权 | `--help` 实测：**有 `--host`**；当前进程实测**只绑 `127.0.0.1:3081`**（回环）⇒ 反代同机**不用**改 host；⭐ **报告没写的坑** = `--trusted-host <authority>`（`/api` 浏览器信任围栏）⇒ 用域名访问时**页面能开、功能可能坏**，应进部署清单 + 验收判据（验收 = 「能真的发一条消息」） | ✅ 已补验（写进核对 doc §2） |
| **S31-3** | ⚠️ **一处结论方向需修正**：报告 §14.5-2「云上跑 CORE 成本 ≈ 几乎为零」 | `D-352` 指纹含 `src/`+`tools/` ⇒ **每次真改动之后仍是 251 s 真跑**；缓存只救「没改动的重复跑」——而「改一行→验一轮」才是主循环。⇒ 全搬对 CORE 的真收益是「**上游 jar 齐 ⇒ Tier B 不再是 WARN**」+「随时能跑、不占本地」+「16 G 能并行」，**不是便宜**。⚠️ 另：指纹含 `unix_args.txt`/`java -version` ⇒ **缓存不跨机器**（云上第一次必然是真跑） | ⚠️ 登记（口径修正） |
| **S31-4** | ⭐⭐ **报告没覆盖的真风险**：世界母本该是「**输入**」（脚本 `:167` 自述逐字），却仍是「**本地一次性快照**」 | 实测：母本只在不存在时建一次（`:137`）、**不进 git**、来源（玩家客户端存档）**永远在本地**（该世界目录 mtime = 今天 14:08，还在被玩）⇒「唯一母本在云上」+「来源在本地」= 一条报告没写明的耦合。**建议形状**：母本**版本化发布**（sha256 + 该版本期望的 CORE 判决），设备只下载固定版本；换版本 = 显式动作 + 复跑一轮对比 | ⭐ 登记（**建议作为二期的前置**，不是二期内容） |
| **S31-5** | 报告 §8-5「别在云上装认 `AGENTS.md` 的其它 harness」 | 方向对，但理由要补强：撞车的实质是**两个 agent 同时写同一份工作树**（§7.2 单写者纪律）⇒ 应升级为纪律：「任何时刻只允许**一个** agent 持有写权限的检出」 | 登记 |
| **S31-6** | 报告 §14.6-4「真人验收仍在本地」 | ⭐ **必须同时钉进验收等级口径**：**云上的绿 = `SERVER_TESTED`，永远不等于 `WINDOWS_CLIENT`** | 登记（口径） |
| **S31-7** | ⭐ **我方可执行版本已落地** = `docs/CLOUD_MIGRATION.md`（修补版 v2）：保留报告骨架，修三处（① 端口转发必须 `--host 0.0.0.0` + `--trusted-host`；②「云上跑 CORE≈免费」口径；③ 世界母本版本化=**二期前置**），并写进**实测数字**（`~/.dsh`=1.2 G 构成、**密钥不能走环境变量**、`dsh web` 默认只绑回环、会话 slug=cwd）与**能力分工表**（我能搬行李/不能开机器；装 `gh` 后可代跑零期） | ✅ 已落地（手册） |


### I. 2026-09-24 云端接管后登记（**只登记事实**）

| # | 事项 | 事实 / 判据 | 状态 |
|---|---|---|---|
| **P4-环境①** | 云端容器的 `python3.12` **物理缺标准库**（`/usr/lib/python3.12/json` 不存在 ⇒ `import json/html/shutil/zipfile` 全失败）⇒ `check-item-models` / `check-authz-registry` / `check-machine-map` / `check-scene-connectivity` 四项在云端**必红**（与代码无关）| ✅ **已修 + 已固化**：`sudo apt-get update && sudo apt-get install -y libpython3.12-stdlib`（实测四项由红转绿）＋ 写进 `.devcontainer/devcontainer.json` 的 `postCreateCommand`（重建容器也带上）|
| **P4-环境②** | `check-machine-map` 的 mods 目录**硬编码本地 Windows 路径** `/mnt/d/JAVA_projects/...` ⇒ 云端/其它机器跑必然 `INCOMPLETE`（未复核 6）| ✅ **已加开关**：`tools/check-machine-map.sh` 支持 `ALICE_MODS_DIR`（与无头电池同变量名）⇒ 云端指到 `~/mc-client/mods` 后 **未复核 6→1**（剩 `oreexcavation`：云端确实没有该 jar，如实保留，不许说成 PASS）|
| **既有红（非本轮引入）** | ✅ **已清（2026-09-24）** —— ⚠️ **其中 26 条是"云端的假阳性"，不是过期引用**：`tools/ref-integrity.py` 把 Baritone 参照仓路径**写死**成 `/home/fb486/projects/reference/baritone`（本地 WSL），而云端在 `$HOME/reference/baritone-1.20.1` ⇒ 索引不到参照仓 ⇒ 两边同名的 `MovementHelper.java` 只剩**我们那份 474 行** ⇒ 26 条**引用 Baritone 行号**（565…843，Baritone 实为 **863 行**，逐行核对命中）被误判成"超界"。**修法（两处，方向都是"更严"）**：① 参照仓路径改为 `ALICE_BARITONE_DIR` + 本地固定路径 + `$HOME/reference/baritone*` 兜底；② 裸基名引用改为**候选全取、候选都装不下才红**（不再依赖"基名唯一"，也不再依赖参照仓在不在）。⇒ 校验量 **836 → 1082 处**（多查了 246 处）、提示 232 → 156；同时**真的抓出 2 条真过期**（`ToolSet.java` 当时写的 `239-239` **越界**（真值 236 行，已改 `207-236`））。现在：`ALICE_MODS_DIR=$HOME/mc-client/mods tools/check-all.sh` = **`pass=20 warning=2 failed=0`（首次无 FAIL）**；⚠️ 两个 warning 仍是"断言未执行"（无头电池未跑 + `check-machine-map` 缺上游 jar），**不等于通过**。取证：`tools/ref-integrity.py` 文件头 + 本行。<br>⭐ **2026-09-24 补记（`A3` 轮抓到的第二个教训）**：上一轮的**落盘发生在门禁跑完之后** ⇒ 本轮门禁一跑就抓到 **2 处文档自身**的问题（① `CLOUD_MIGRATION.md` 与本表把**历史行号**原样写成 `文件:行` 前缀的引用 ⇒ 被当活引用判超界（⚠️ 修第一遍时**又**把它写成了一次 —— 描述过期引用要用不带前缀的写法）；② 本表的**单步形态示例**（`single:` 后面跟一个假步名）被当成真步名引用）⇒ 两处都已修。**纪律**：写完台账/文档必须**再跑一次门禁**再收工，否则文档侧的红会留给下一轮当「既有红」。|
| **云端门禁口径** | 云端跑全套门禁的正确姿势：`ALICE_MODS_DIR=$HOME/mc-client/mods tools/check-all.sh` ⇒ 实测 `pass=19 warning=2 failed=1`（两项 WARN = 无头电池未跑 + machine-map 缺上游 jar；唯一 FAIL = 上面那条既有红）| 登记（**云端绿 ≠ 客户端绿**，`S31-6` 不变）|

### J. ⭐ 工作排期表（2026-09-24 用户要求「先仔细规划一下工作排期表」，**先规划再开工**）

> **为什么有这张表（用户原话）**：「现在修复内核花了很多时间了」。⇒ 本表要回答三件事：
> ① **内核什么时候关门**；② **主力什么时候转到"能干活"**；③ 每条各花多少轮、需要谁参与。

**成本单位 = 「我方一轮」**：一次上下文窗口 + 2–4 次无头电池跑 + 1 次 CORE（实测：`P2` 任一片、`RC1` 那种规模都≈1 轮）。
**客户端轮次**单列（要用户操作 + 镜像/同步 jar；**云端环境下 AI 无法 mirror**，必须用户在自己机器上跑）。

#### J-0 收口（**先做，1 轮**）：把开口的决策点一次性裁掉，并给内核线设关门线

| # | 事项 | 目标 | 判据 | 成本 | 依赖 |
|---|---|---|---|---|---|
| 0.1 | `P2c` 裁定（`D-428` §六） | 决定"起点不落地的 `FALL` 边"怎么办 | 一句话即可：**先不改**（推荐，0 成本）/ 放宽执行侧（须先做夹具） | 0 或 1 轮 | ✅ **已裁**（用户 2026-09-24，`D-432`）= **先不改**（代码一行不动）|
| 0.2 | `P4′` 裁定（`D-429` §五） | 决定最坏 tick 怎么压 | ①`P4″`＝给重消费者 `SearchBudget` 加时间上限（**推荐**，1 轮 + CORE diff）②维持 400（0）③收紧到 60（1 轮 + CORE diff） | 0–1 轮 | ✅ **已裁**（用户 2026-09-24，`D-431`）= **①** ⇒ 新项 `P4″` |
| 0.3 | ⭐ **内核关门线**（本表生效的核心） | 内核**只接受三种输入**：真机实测红 · 用户报障 · CORE 回归 | ✅ **用户 2026-09-24 裁定「同意关门」** ⇒ 已记 **`D-430`**（含解除条件四条）+ 四件自证工作移入 §11-E | ✅ 0 |
| 0.4 | 暂停清单登记 | 把"自证型"内核工作全部转入观察项 | 四件套：**名词登记**（剩 10 个码）· **门禁人口/对称性** · **死码/同源化审计** · **Baritone 逐行对照切片**；各自写**复活条件** | 0（登记） | 0.3 |

**为什么可以关门（依据，不是感觉）**：`AGENTS.md` 给内核定的两个目标指标**都已达到** ——
①「从改一行到知道对不对」＝ **1 条命令**（`tools/check-all.sh` 20 道门禁 + `headless-battery.sh core` 41 步）✓；
②「规则被违反时」＝ **构建红**（20 道门禁全部"注入即红"验证过）✓。⇒ 仪表够用了；之后往内核里投的都是
**对称性/自证**（名词、人口、死码、逐行对照），边际收益递减而成本恒定。

#### J-1 能力线主战场（**6–8 轮 + 1 客户端轮**）：三天挖矿任务线 ③ 鱼骨 → ① 跟随 → ② 探洞

> ⭐ **2026-09-24 定档（原 ⏸ 挂起解除）**：`J-0` 两个开口点已由用户收口（`D-431` = `P4″` · `D-432` = `P2c` 先不改 + `craft_table` 保），勘测报告
> `survey/32`+`survey/33` 已核对（`docs/reviews/2026-09-24-survey32-核对.md`）。相对候选池的**四处改动**：
> ① 单列 `P4″` 落地（已裁；⭐ **判据是台架口径 ⇒ 不需要客户端轮** —— 勘测报告那半的等待理由已被作废）
> ② 鱼骨切片 3 单列成轮（它是客户端验收的**入口**）③ 落物/几何在切片 2 **设计期先定**（`PL-1` 实测"每格 ≈130 tick 白等"；
> ⚠️ 改几何会**作废 `D-389`/`D-391` 的历史读数**）④ `tools/client-agent/` 补登记。⇒ 成本由 **3–6 轮** 改为 **6–8 轮**。

| # | 事项 | 目标（用户可见） | 判据 | 成本 |
|---|---|---|---|---|
| 1.1 | ⭐ **鱼骨切片 1（主巷 + 返回）= 开工第一项**（前提已核：计划 `:350`「方案层面已无待拍板项」） | bot 按鱼骨模板开挖，看得见推进 | 判据 **C1/C3/C4/C6**（计划 §6）；夹具**先红后绿**（关掉模板生成 ⇒ 红）+ ⭐ **新增两条读数**：每轮 `nodesExpanded`/`elapsedMillis` + 每格 tick 成本（`PathingStats` 现成、零成本 ⇒ 顺带验 `survey/32` 发现二） | ✅ **完成（2026-09-25，`D-433`）**：`FishboneTemplate`（只主巷 4 字段）+ `FishboneJob`（`PREPARE/EXCAVATE/RETURN/DONE`，逐格 `MineTask`、返回纯通行）+ 夹具步 `fishbone_slice1`（EXTRA，三臂）。⭐ **夹具 PASS `checks=28 failures=0`**（`run/headless-logs/20260924-235730-…`）；**红臂**（`cells()` 返回空）`FAIL failures=6`（`…20260925-000003-…`），还原后复跑 PASS（`…000147-…`）；**CORE 判决 41/41 逐字不变**（`…20260925-000600-core.log`，唯一变化 = `extra_skipped 56→57`）· `check-all pass=22 warning=1 failed=0`。<br>⭐ **顺带把计划 §5 的"极廉价"从假设变成读数**：每轮规划 **0.55 ms / 1.55 节点**、**41 tick/格**、`SEARCH_LIMIT=0`（⇒ `C3` 常数按实测定 6/40）。⚠️ **未做**：支巷/顺手挖/追簇/收集/`JunkPolicy`/搭地板/真机入口（= 切片 2–4）。⭐ **顺带修掉一条判据读错数的真缺陷**：`PL-1` 臂⑥ 的正则会跨步边界（抓到新步的 4000 ⇒ 假红）—— 已修成"本步内第一个 `NNNN)`" |
| 1.2 | **复评（0 轮）**：用 1.1 的读数定**切片 2 的落物/几何** | —— | ✅ **已定档（2026-09-25，`D-436` §一）**：① 切片 2 **自建带实心地板的新场景** ⇒ `C2` 可断言；② 「落物可能掉落」写成 `C2` 的**例外条款 + 独立负例臂**（脚位悬空且不搭地板 ⇒ 如实 `product_not_collected`，= `PL-1` 真机形态）；③ **不动 `mine_vein_propagation`** ⇒ **`D-389`/`D-391` 的历史读数不作废**（台账原把这一步写成「二选一的代价」，实测**不必付**：切片 2 用自己的场景） | 0 |
| 1.4a | ⭐ **切片 2 第一步：支巷几何**（纯几何层） | 鱼骨的支巷形状可复算、可红 | ✅ **完成（2026-09-25，`D-436` §二/§三）**：`FishboneTemplate` 加 `spurSpacing`/`spurLength`/`SpurSide`（嵌套 enum）+ `unitFoots()`/`spurCount()`/`hasSpurs()`；`cells()`/`advanceCells()`/`scopeRadius()` 按支巷重算。判据 = **纯几何 14 条**（放进**已有的** `fishbone_slice1` 步 ⇒ 不新增注册面）· 绿 **`checks=42 failures=0`** · **红臂（`LEFT`/`RIGHT` 互换）`failures=4`** 且正好是那 4 条顺序/侧向判据。⚠️ 计划 §2 的另几个字段（`maxGapLength`/`bridgeBlockBudget`/`oreQuota`/`oreTarget`）**仍不声明**（消费者在第二步/切片 4 才出现） | 0.5 轮 |
| 1.3 | ~~⭐ **`P4″` 落地**（`D-431`：不动毫秒兜底轴，给重消费者 `SearchBudget` 加时间上限）~~ ⇒ ✅ **关账（**0 轮**，2026-09-25，`D-434`）** | ~~真机那个 `Can't keep up` 的病根被治~~ ⇒ **改由 `P1-d` 承接** | 判据①（台架 `LEGACY` 格 ≤50 ms/次）**已被 `D-388` 提前满足** —— 它的主语"无时间上限的重消费者"**在生产侧零命中** ⇒ **不落地任何代码**。⭐ 真机病根实测拆解 = **4 次各自烧满 50 ms 的搜索**（≈200 ms/轮）+ `PARTIAL` 归因洞（480/502）⇒ **另立 `P1-d`**（`§11-B`，待裁） | **0**（原 1 轮） |
| 1.4b | ⭐ **切片 2 第二步：行为臂**（支巷退路 + 支巷放弃 + 露头矿顺手挖 + 收集） | 鱼骨能开子巷、子巷挖不动时**只放弃那一条**、路过的矿顺手带走 | ✅ **完成（2026-09-25，`D-437`）**：`FishboneJob` 加 `SPUR_RETURN`/`IN_PLACE`/`COLLECT` 三相位 + **单元化记账**（`FishboneTemplate.units()`/`record Unit` 成几何唯一真源）；**新步 `fishbone_slice2`**（EXTRA，`FishboneSlice2CheckTask`，三臂，**一条老绿臂都没碰**）· 绿 **`checks=46 failures=0`**（① `SPUR_TUNNEL`：24 格全空 + 每条支巷退回主巷一次 ② `SPUR_ABANDONED`：⭐**新终态 `template_complete_spurs_abandoned=1` + DONE**，基岩格零改动、主巷与第 2 条支巷照常挖完 ③ `ORE_IN_PLACE`：`oreFound=3/oreMined=3/oreWalkedAway=0` + 夹具**独立清点**原铁 3 ≥ 3 + 诱饵矿原封不动 + 破坏数**精确** 12+3）· **两条红臂**：A 两档合并 ⇒ 臂② **7 条红**（`main_blocked:` + FAILED + 第 2 条支巷没挖）；B 去掉显式支巷退路 ⇒ 臂①② 各 1 条红（`spurReturns`）。⚠️ **诚实边界**：条件②③ 在"只扫刚挖完单元的 6 邻域"这个设计下**结构性成立**（真正筛的是①），靠**逐条隔离判据**钉住；显式退路对"能完成"**不是必需**（B 臂读数证明）。⚠️ **仍未做**：追簇/`oreQuota`、大矿洞三条上限、`C10` 满包、`main_floor_missing`、`C2` 负例臂 | 1 轮 |
| 1.4c | ⭐ **切片 3：真机入口**（`alice:fishbone_job` 零参数 + `[Fishbone]` 结构化日志） | 你能自己按一下就跑：走到哪、朝哪，就挖到哪（**不输坐标、不搭场景**） | ✅ **完成（2026-09-25，`D-438`）**：`FishboneJobItem`（模板 = **玩家脚位 + 朝向**；默认 主巷 20 / 间距 5 / 支巷 5 / `ALTERNATE` / 净高 2 ⇒ 4 条支巷、40 单元、80 格；`MAX_TICKS=8000`）+ `BotManager.assignFishboneJob`（**唯一**建会话处）+ 物品/模型/双语 lang。⭐ 日志形状 = 计划 §8 逐字落地（`start template=dir=E …` / `advance=1/6 cell=… spurs=0/2 …` / `SUMMARY dir=E main=6/6 spurs=2/2 abandoned=1 … outside=0 → PASS`），**`SUMMARY` 由唯一终态出口打一次**（成功失败都有）。判据 = **夹具 8 条环境无关**（四个方向 × 真机默认模板：调**生产工厂** `FishboneJobItem.templateFor`，不是照常量另抄）· 绿 **`checks=54 failures=0`** · **新门禁 `[D-438·鱼骨真机日志形状]`**（注入 `outside=` 改名 ⇒ 红）。⚠️ **本片没跑真机**（`SERVER_TESTED`）⇒ 真机新增风险面 = 真实地形/收集半径/观感，**只能由你看** | 0.5 轮 |
| 1.4d | ⭐ **切片 4：形状可配置（左右对称 / 中心距 3 / 支巷 ≥32）+ 矿簇追挖**（真机缺陷的根因修复） | 你右键一次得到**对的形状**，且**洞壁洞顶的矿簇被挖干净** | ✅ **完成（2026-09-25，`D-439`）**：① `config/FishboneConfig`（`config/alice-fishbone.toml`：主巷 / 中心距 / 支巷长 / 侧向 / 净高 / 每单元矿上限）；真机默认 = **`BOTH` + 中心距 3 + 支巷 32** ⇒ 12 条肋 / 404 单元 / 808 格（真机 ≈16 分钟，可随时终止）；`MAX_TICKS` 改成由模板推导（依据 = 真机实测 **47 tick/单元**）。② **追簇**：暴露面从「模板格」扩成「**本次作业挖出来的面**」（`dugCells`）+ **每破一颗矿就扫它的 6 邻域**（传递闭包）+ **③ 够不着不永久否**（`chasePending`）+ ⭐ **`CHASE_APPROACH` 游走段**（站在身后一格时矿脉第二层被巷道壁挡视线 ⇒ 先纯通行走进刚挖完的那一格）。记账拆成 `oreMined`（破坏那一刻）/ `oreUncollected`（捡不回），`SUMMARY` 加 `uncollected=`。③ 夹具 `fishbone_slice2` **3 臂 → 4 臂**、`checks` **54 → 76 failures=0**：⭐ 臂④ `ORE_VEIN_CHASE` 矿脉 **5/5 格变空气**（`oreMined=5 oreFound=5 oreUncollected=0 collectedProducts=5 auditOutsideExpected=0`）。④ **红臂两条**：J1（暴露面退回只有模板格）⇒ 臂④ **2/5、failures=5**（= 真机缺陷的离线复现）；J2（拿掉破矿后扫邻域）⇒ 同样红。⑤ 回归：`fishbone_slice1` PASS · **CORE PASS 243 s** · kernel-predicates PASS。⚠️ 仍是 `SERVER_TESTED`（本片没跑真机）；触及范围 ≈4.5 格是追簇的自然上界（>4 格的深脉追不完，如实记账）| 1 轮 |
| 1.4e | ⭐ **切片 5：追簇挖空地板之后由规划器自己补回来**（用户 2026-09-25：「有办法自然衔接搭桥的 Movement 吗，而不是专门把流程写死」）| bot 挖进矿簇（含地板）之后**自己把路补回来**，支巷不再被放弃 | ✅ **完成（2026-09-25，`D-440`）**：① 新增 `PathRequest.withPlacement`（**只放不拆**：`PLACE_STEP_AND_TRAVERSE` + `PILLAR`，不含 `BREAK_*`/`DOWNWARD`）并登记 `WORLD_WRITE_AUTHORIZATION` **A14**；② 鱼骨的「回作业面 / 支巷退回 / 回家」三处走位改用它，放置额度 = `max(8, 单元数/10)`（404 单元 ⇒ 40 块，用完**如实退回**纯通行）；③ 走位触发条件放宽到「**作业面自己站不住了也要走**」。判据：夹具臂④ 加**地板矿脉** + 4 条新判据（`spursAbandoned=0` / 支巷挖到底 / 地板矿没了 / `places>=1` + 放置白名单）⇒ 绿 **`checks=81 failures=0`**，日志 `[Pillar] placed pos=3763,79,2398`（补的正是被挖掉的地板格）；**红臂 J3**（强制退回纯通行）⇒ `places=0 FAIL`。回归：`fishbone_slice1` PASS · CORE PASS · kernel-predicates PASS。⚠️ **未决边界**：往下追 **2 格**时最底那一格**物理上挖不掉**（挖掘器拒绝站在正在挖的那格上）+ 坑里掉落物可能收不回（`product_not_collected`）⇒ 夹具退回 1 格深，边界写在 `D-440 §五`，**待用户拍两条**（允许挖脚下 / 还是把'坑里够不着'降级为如实上报）| 1 轮 |
| 1.4f | ⭐ **真机入口的起点改用 bot 自己的脚位**（用户 2026-09-25：「就以 bot 位置为起点，之前 bot 想到我这个位置，又推不动我」）| 你站哪都不影响它；右键一次仍然是"**从 bot 站的地方**按你朝的方向挖" | ✅ **完成（2026-09-25，`D-441`）**：① `FishboneJobItem` **不再传送 bot**，起点 = `MovementHelper.footCell(bot)`（`D-105` 唯一定义）；起点**站不住 ⇒ 当场拒绝开工**（`originFor` 返回 `null`，判据 = 与 `PREPARE` 同一个 `canStandCentered` ⇒ 不再"开出去 5 tick 后 `start_unreachable`"）；② 选**离你最近**的那只 bot（与 `bot_remote_control` 同口径）；③ 一只都没有时才生成，且**优先不生成在你那一格**（4 邻居 → 4 斜角 → 最后才轮到你脚下）；④ 你若与 bot 抢同格（脚位/头位）⇒ 拒绝 + 提示让开一格；⑤ 方向仍然 = 你的朝向（零参数契约不变）。判据：夹具 `fishbone_slice2` **+2 条**（`originFor == bot 脚位`；反证：抽掉地板 ⇒ 必须 `null`）⇒ 绿 **`checks=83 failures=0`**；**红臂 R1**（去掉"站不住就拒绝"）⇒ 实测 `FAIL 失败项=[…悬空…必须返回 null…]` ✓；新门禁 `[D-441·鱼骨入口起点=bot脚位]`（4 条，注入 3 臂全红）。⚠️ 门禁**第一版被"长得像的东西"满足**（全文件找 `footCell(`/`canStandCentered(` ⇒ 被报错文案和 `spawnCellNear` 满足，注入臂全绿）⇒ 改成方法体**位置化**（`D-425` ⑤ 第 5 例）| 0.5 轮 |
| 1.4g | ⭐ **爬升必须可逆**：鱼骨补路走位补上 `FALL`（用户 2026-09-25：「建议放开 Movement，因为 bot **给自己垫方块困住了**」）| bot 自己垫方块爬上去之后**下得来**；作业不再因为「卡在高处」整单失败 | ✅ **完成（2026-09-25，`D-442`）**：① 真机逐字取证 —— 追簇把矿脉从**天花板**挖出来，`CollectDrops` 用 `PILLAR`+`ASCEND` 爬上走廊上方 2 格（`[Pillar] placed pos=-22,49,197`），够不到掉落物（退役）后鱼骨 `SPUR_RETURN … PLAN_UNREACHABLE`（`descend_precondition=25`）⇒ `spur_return_failed` ⇒ **`return_failed` FAIL**；② 根因 = `withPlacement`（`A14`）**有 `PILLAR` 没 `FALL`** ⇒ 爬升单向（`DESCEND` 只降 1 格且要求落点本来就站得住）；③ 修法 = 给该集合**补 `FALL`**（纯通行 ⇒ **不扩大 `A14` 写入权限**；`FALL` 的 `fallRecoverable` 守卫本来就要求「能用 `PILLAR` 返回」⇒ 本集合本来就有 `PILLAR`）；`of`/`climbApproach`/`scaffoldRemoval` **不动**（后两者是特意配对的两阶段设计）。判据：夹具 `+1 条`（把处境造出来：走廊上方 2 格必须能回到起点且含 `FALL` 边）⇒ 绿 **`checks=84 failures=0`**；**红臂 R2**（拿掉 `FALL`）⇒ 实测 `status=UNREACHABLE movements=0 FALL=0` ⇒ `FAIL failures=1`（= 真机的离线复现）；新门禁 `[D-442·补路走位可逆]`（**第一版写成全仓普适断言 ⇒ 误红 `scaffoldRemoval`**，已收窄到 `withPlacement`）。⚠️ 本轮同时发现**未修**三条：`SUMMARY collected=0/17` 在没进 COLLECT 阶段时是**幻影 0**（建议三态 `n/a`）· `CollectDrops` 为够不到的掉落物白爬 2 格（模型/执行期 `inPickupRange` 口径不一致）· `survivalEscape` 同形状待复核 —— 见 `D-442 §五` | 0.5 轮 |
| 1.4h | ⭐ **片 A：`P0.5` 能力注入 + `C8` 三件 + `A14`↔`C8` 对账**（`D-443` 裁定 1a/1b/1c/7a/7b） | 支巷不再因"**挖掘站位走不回来**"被放弃（真机 `spur_abandoned` 次数下降）；通道**不许被搭成一座长桥**（大矿洞该放弃就放弃） | ✅ **完成（2026-09-25，`D-443` 裁定 1a/1b/7a/7b）**：① `P0.5` = `MiningProfile` 加 `Approach approach` 字段（默认 `PURE_PASSAGE` = 精确现状）+ `MiningPlanner` 加 6 参重载（`approach` + `requester`；归因串随 `grant.requester()` ⇒ 作业侧额度看得见挖掘接近的放置）+ `MineTask` 两处调用点 + 鱼骨逐格用 `CELL_PROFILE`（`cellProfile()` 在额度用尽时退回 `STANDABLE_ONLY`）。② ⭐ **真机根因不止一处**：夹具实测发现模式 A 的**候选排名**也假设纯通行（`StandingCostEstimator` 口径"不可达候选不在 map 中" ⇒ 「只有补一块才到得了」的候选被整个丢掉排名、永不被精算）⇒ `exactTopK` 加 `includeUnestimated`（仅 `PLACEMENT_ALLOWED` 时为真 ⇒ 其余消费者零变化）。③ `C8` 三件 = `maxGapLength`(4) 前瞻门 + `bridge_budget_exhausted` + 「额度用尽 ⇒ 如实放弃」（取代 `A14` 的"退回纯通行、坑留着"）；预算合成一个名字 `bridgeBlockBudget()` = `max(16, 单元数/10)`。判据 = 夹具 `fishbone_slice2` **87 条 failures=0 PASS**（新增 3 条：前置"候选必须存在" + 片 A①两种能力给出不同答案 + 片 A②单段悬空）；**红臂三条**：R-A（接近工厂退回纯通行）⇒ `fishbone_slice2=FAIL` 命中片 A① · R-B（前瞻恒真 = 上限调成无限搭）⇒ FAIL 命中片 A② · 门禁注入（删预算档 / 退回旧公式）⇒ `[D-443·C8搭路上限]` 红。回归：`fishbone_slice1=PASS` · `kernel-predicates PASS`。⚠️ **仍未做**：真机客户端轮（本片是 `SERVER_TESTED`） | 1 轮 |
| 1.4i | ⭐ **片 B：`P0` 失败升档 + 归因四分类 + 矿上限"有界延后"**（`D-443` 裁定 3/6） | 一次**瞬时**失败不再吃掉整条支巷（真机那两次放弃都能救回）；"矿簇不干净"里"上限丢弃"这一项消失 | ⏳ **待开工**。内容：① `abandonSpur` 语义从"任何理由即放弃"改成 **本格重试 → 本站位重定位 → 才分段放弃**（`FishboneJob:540-543` 的注释**自己预登记过**这个触发条件）；② 每次放弃必须**归因四分类**（① 横向缺格 ② 能力不对称 ③ 判据误判 ④ 真挖不动）——① 才是重开 `parkour-place` 的入场券；③ `ore_budget_exhausted` 从"**丢弃**剩余候选"改成"**有界延后**"（复用 `ore_reach_deferred` + 队列上限）。判据 = 瞬时失败夹具 ⇒ 支巷仍完成；**红臂** = 退回"一次失败即放弃" ⇒ 红 · 退回"丢弃" ⇒ 红 | 1 轮 |
| 1.4j | ⭐ **片 C：`P1` 判据唯一化 + `I5` 通道保护**（`D-443` 裁定 5/8） | A1 类"半成品格"不再出现（`-65,52,182` 那种"空气+头位实心+无地板"）；本作业自己的追簇/捡东西**不再挖穿通道必需格** | ⏳ **待开工**。内容：① "这格算不算通"**唯一一份判据**（脚位可穿 ∧ 头位可穿 ∧ 地板在），`FishboneJob:527 bodyPassable` 与 `:647 canWalkThrough` 两处合一（`D-443` #5：判据就地放 `job/fishbone/`，接口/包等第二个消费者）；② `I5` = **作业形状驱动**的通道必需格集合（行走层 + 净空 + 支撑格），在**已有的候选过滤形态**处消费（对照 `LumberCandidateSource:71`/`MineCandidateSource:449`），共享任务按作业作用域取用（`D-443` #8，**不挂 `ZoneAuthority`**）。判据 = 把本轮破坏序列（`break -66,51,182` / `-65,51,182` / `-65,52,182`）造成场景 ⇒ 绿；**红臂** = 退回 `bodyPassable` ⇒ 红 · 删保护 ⇒ 破坏序列复现 ⇒ 红。⚠️ **2026-09-25 真机第三轮把①的理由钉死了**（取证见 `1.4k` / `docs/reviews/2026-09-25-真机第三轮-根因取证.md` §2）：`bodyPassable` 问单格 ⇒ 3/3 弃巷；**建议①提到片 B 开头**（纯谓词，不动包/接口） | 1 轮 |
| 1.4k | ⭐ **真机第三轮（片 A jar）根因取证 + `maxGapLength` 旋钮翻倍** | 三链根因**有据**、旋钮落地；并把 `1.4j①` **提前**的理由钉住 | ✅ **取证完成（2026-09-25）**：`docs/reviews/2026-09-25-真机第三轮-根因取证.md`。三条链：① 弃巷 3/3 = `isAlreadyPassable` 用**两格** `bodyPassable` 问**单格** ⇒ 已是空气的通道格不跳过 ⇒ 给空气块建 `MineTask` ⇒ 视线判据定义上永远看不见 ⇒ `no_valid_standing_point` ⇒ `abandonSpur`（签名 `footPassable=true headPassable=false` = 3/3 vs 真挖不动 2 条的 `footPassable=false`）；② 矿 53 找到/43 挖到/10 没挖 = 8（`ore_budget_exhausted` 丢弃，已裁定待修）+ 2（`ore_deferred` 硬丢）；掉落物 18 条 `retire`（21+ 件）根因 = **离心 ~0.49 击穿拾取模型**（`goal_excluded` 逐字）；③ 段超时 2 条 = 静止容忍度 **0.3 = AABB 半宽** ⇒ AABB 压邻列角 0.095/0.03 ⇒ `onGround=true` 永不下落，且执行期指令 `stopMovement()`（`forward=0.00`）**缺 Baritone `MovementDownward:86-94`**「没到中心就走过去」那一支。**⚠️ 与 `D-376` 的 `forward=1.00` 事故不是同一类**。⚠️ **建议把 `1.4j①`（谓词唯一化里的 `isAlreadyPassable` 单格口径）提前到片 B 开头**：它是 ① 的直接原因，一行谓词换掉 3/3 弃巷（每次 32/31/18 个单元 + 整条矿队列）。旋钮（用户裁定）：`maxGapLength` 默认 4→8 + 判据 `cavernChecks` 改成**自己铺地板**（原来借场景盒东边界 ⇒ 窗口 8 时判据会红而生产没坏）；真机与无头两侧 toml 的**值**同步为 8（⚠️ Forge 只改注释不改已有值 ⇒ 无头侧第一次复跑用的还是 4）。证据：`fishbone_slice1 42/0` · `fishbone_slice2 87/0` · `kernel-predicates PASS` · 红臂 R-B 命中片 A② 后还原（sha 一致） | 0.5 轮 |
| 1.4l | ⭐ **真机第三轮后续 A：主巷前瞻档降为提示 + 矿预算 16→32**（`D-445`） | bot 在矿洞/空腔里开工时**不再因为"方向是空气"整体失败**；正常大小矿脉不再被每单元上限截断 | ✅ **落地（2026-09-25）**：① `big_cavern_ahead` 是**预测** ⇒ 抽 `FishboneJob.advanceRefusalIsHard(isSpur, refusal)`（判据 `advanceRefusal` 不变、处置新的一处）：**支巷两档都停 · 主巷只有 `bridge_budget_exhausted`（计数事实）停**；主巷吃到提示只打一行 `前瞻提示 … ⇒ 继续推进`（下一轮可看出是"一步落差"还是"真空洞"）。理由与 `D-329`（`SEARCH_LIMIT ≠ UNREACHABLE`：预测不许当判决）同源。⚠️ **配套未做**：`1.4j①`（两格谓词问单格）——不修的话主巷走到那些空气格仍会以 `no_reachable_standing_point` 收场。② `oreBudgetPerUnit` 16 → **32**（实测那个触顶单元实际有 24 个候选，16 砍掉三分之一；32 > 24 够用，触发率 1/117≈1%）——**真正的修法仍是 `D-443` 裁定 6「丢弃 ⇒ 有界延后」（片 B）**；两侧 toml 的值已同步。证据：`fishbone_slice1 42/0` · `fishbone_slice2` **88/0**（新增第 88 条 = 处置判据）· `kernel-predicates PASS` · 红臂（处置恒 `true`）`checks=88 failures=1` 且唯一红的就是新增那条，还原后 sha 一致。③ 用户目视确认「停在洞沿上像悬空、刚好蹭在边缘上」⇒ 机制升到 `WINDOWS_CLIENT`；**修法待裁定**（A = Baritone 式输入居中 / B = 结算点瞬移校准；建议先 A） | 0.5 轮 |
| 1.4 | 鱼骨切片 2（支巷 + 顺手挖 + 收集）→ 切片 3（零参数真机入口 + `[Fishbone]` 日志） | 鱼骨在真实矿洞里跑通；你能自己按一下就跑 | 切片 2 = **C1（含支巷）/C2/C5** + CORE 逐步 diff；切片 3 = 入口零参数 + 日志形状（计划 §8） | ✅ **全部落盘**（`D-436` 几何 + `D-437` 行为 + `D-438` 真机入口 + `D-439` 形状/追簇 + `D-440` 补路 + `D-441` 起点=bot 脚位）⇒ **交棒给 `1.5`（客户端验收轮：真机 = 新形状 + 矿簇是否挖干净 + 支巷不再被放弃 + 从 bot 自己脚下开挖）** |
| 1.5 | ⭐ **客户端验收轮** | 你在真实存档里看 bot 挖一段鱼骨 | `latest.log` 的 `SUMMARY`（`mined`/用时/拒绝归因）+ 你的肉眼结论。⚠️ **只做鱼骨验收**（`survey/33 §5` 已作废"替 `P4′` 顺带采集"那半） | **1 客户端轮** |
| 1.6 | 鱼骨切片 4（`Kind.FISHBONE` + `JobKindContract` + 门禁） | 生产入口（表单/派活） | 契约表 + 门禁一条 + CORE 逐步 diff | 0.5 轮 |
| 1.7 | ① 跟随 / ② 探洞（按 1.5 的结论取舍） | 另外两条挖矿任务线 | 同 1.1 的夹具口径。⚠️ **两处前提已更正**：判据主语 = **bot 自己那套成本场**在"含玩家的连通分量"内找矿（**不是**"玩家可达"）；探洞"四判据全可离线"**存疑** ⇒ 标**待实测** | 各 1 轮 |
| 1.8 | `tools/client-agent/`（本地客户端管家）**补登记**（`survey/33 §6-1`；台账此前 `client-agent` **0 命中**） | 10/2–10/7 在家做客户端测试的通道 | 就位/待验 | 0 |

**纪律（照搬 `P5` 的教训）**：同一条线**连续两次失败 ⇒ 停手重估**，不许打第三个补丁（`P5-重估`/`P5 段收口补记`）。

#### J-2 可用性 / 产品面（**2–3 轮**）

> ⚠️ **前置（`survey/32 §3.3`，我方同意）**：**`Q-1`（出口之争）必须在进入本节之前裁**，否则"产品面"没有方向盘；`J-1` 不受它影响（挖矿在两个出口里都存在）。
> `Q-1…Q-22` **实为 19 条**（`Q-20/21/22` 已裁）⇒ 批裁本身 0–1 轮。

| # | 事项 | 目标 | 判据 | 成本 |
|---|---|---|---|---|
| 2.1 | **能力清单进 prompt**（`D-419` 明写未达到的那半句） | LLM 不再"猜自己会什么" | 预算 + 「知识段不得挤掉世界事实段」判据 | 1 轮 |
| 2.2 | `/alice` 只读面收口（进度/账本/任务状态） | 你能一眼看到 bot 在干嘛、欠了什么 | 命令输出字段固定 + 一条门禁 | 1 轮 |
| 2.3 | 上游模组适配（**需求驱动**，`D-219`） | 你实际玩到的机器能用 | 按需，一机一轮 | 按需 |

#### J-3 基础设施（**按需，可延后**）

| # | 事项 | 触发条件 | 成本 |
|---|---|---|---|
| 3.1 | 云端迁移二期（`S31-4` **世界母本版本化** = 报告没覆盖的真风险；`S31-3` 云上 CORE 不是免费） | ⚠️ **触发条件已更新（2026-09-24 回迁完成后）**：**回迁已收口、云端 `Shutdown`** ⇒ 本项触发 = **你真要再上云**（尤其 **10/2–10/7 在家远程干活**那趟）。⚠️ 额度重置日 = **10/1** ⇒ 之前别烧；⚠️ 再上云的操作面：`gh` 2.45 **无 `codespace start`**（走 `gh api -X POST /user/codespaces/<名>/start`）、**`stop → start` 会清 `/tmp`**、`gh codespace cp` 弱网要 ssh 管道兜底 | 1–2 轮 |
| 3.2 | `P6` 搜索线程化（多 bot 并行前提，架构级） | 出现"要多假人同时干活"的真实需求 | 3+ 轮 |
| 3.3 | 勘测侧 `Q-1…Q-22` 一次性批裁 | 或不裁 = 不做（登记即处置） | 0–1 轮 |

#### J-4 ⛔ 明确不做 / 已作废（**别再开**）

`A2` 闭环逐步缓存（`D-417` 已判放弃）· `P5-a` 走廊执行器（`P5 段收口补记` 作废）· `RC2` 显式回收入口（`D-403` 判冗余）·
内核四件套自证工作（见 J-0.4，进观察项）· `Xaero` 专属头像/右键菜单（`D-324` 随时可续，非待办）。

### E. 观察项（**遇到再记录，不主动修**）

> ⭐ **2026-09-24（`D-430` 内核关门）转入的四件自证型工作**（不是删除；各自写了复活条件）：
> · **O5 名词登记**：`CAPABILITY_UNRESOLVED_BUDGET` 剩 10 个码未指名（现读数 13/13，含 3 个有意留债）。**复活条件** = 能力线要新增/退役能力类码时，顺手把同族的补上（由能力线驱动）。
> · **O6 门禁人口/对称性扩写**：现 20 道门禁已够用。**复活条件** = 出现一次「违反却没被门禁拦住」的真机事实。
> · **O7 死码/同源化审计**（`D-425`/`D-426`/`D-427`/`D-428` 那一族）。**复活条件** = 该族任一判据在真机上被证明漏了（有实测红）。
> · **O8 Baritone 逐行对照切片**（`P2` 余下：`Descend`/`Downward` 等）。**复活条件** = 某个 Movement 出现真机异常、且对照能定位根因。

| # | 事项 | 说明 |
|---|---|---|
| O1 | 「跑到目标下面垫石头」 | `D-393` 已收紧到"下方悬空 ≥8 格"；用户：**遇到再记录** |
| O2 | 客户端帧延迟 / 进世界加载慢 | **与 Alice 无关**（RD 32 + 4K + 无性能 mod）；已给建议（RD→12~16 + Embeddium 等） |
| O3 | `survey/27` 队列残项：#2 A1 真机回归（**用户侧**）、#4 候选排序、（#6「破掉自己唯一落脚点」⇒ **已并入 C**） | 见 `survey/27 §3` |
| O4 | 水位逃生**层 2/3**；水下鱼骨**明确不做** | 见 `D-236`/`D-251` 登记 |
