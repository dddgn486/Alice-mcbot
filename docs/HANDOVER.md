# 交接文档（HANDOVER）—— 2026-09-14 会话收口

> 新会话**从本文开始读**；权威细节在 `docs/AI_DECISIONS.md`（决策与实测事实）、
> `docs/OPEN_ITEMS_LEDGER.md`（开放项与逐轮实测）、`docs/BATTERY_CURATION.md`（电池分档）、
> `docs/AI_DEVELOPMENT_PLAYBOOK.md`（协作规则，含 §5.0b/§5.0c/§5.0d 三条 2026-09-13 新增纪律）。

## 1. 今天（09-13/14）做完的

| 阶段 | 状态 | 关键证据 |
|---|---|---|
| **3-A（A1–A5）合成/熔炼接进任务层** | ✅ 收口（客户端验证 + `USER_ACCEPTED`） | A4b：`smelted=true product+1 no_half_products=true`；A5：`mode=directed → craft → 世界事实 product 0→1`；CORE 曾 `(25/25)` |
| **3-B / S0 机器类型事实表** | ✅ 完成（离线，真数据） | `docs/MEKANISM_FACTS.md`：Mekanism **26 类型 / 1171 条**（`crushing` 210 领跑），总量随会话变、已标出处 |
| **3-B / S1 机器配方只读** | ✅ 完成（客户端验证） | `MachineRecipeFacts`（问上游 `getOutputDefinition()`/`getInput().getRepresentations()` + 自校验）→ `RecipeQuery.MACHINE_ROUTE`（有出处的路线，含机器类型与材料）；`CraftJob` 如实拒绝 `not_executable` |
| **3-B / S2 机器站点只读** | ✅ 完成（客户端验证） | `machine_block=mekanism:enrichment_chamber@66,64,306`、`menu=…MekanismTileContainer slots=41`、**进度=上游自述** `getScaledProgress/getOperatingTicks/getActive` |
| **3-B / S3 机器映射单一出处** | ✅ 收口（客户端验证，`SERVER_TESTED` + `WINDOWS_CLIENT`） | `machine_map_rows=27 with_site_confirmed=22 with_site_unobserved=[mekanism:smelting] no_site=4 unmapped=[] row_block_missing=[]`；`按表找到 2 台` + `m1_binding=true m2_binding=true`；CORE `(28/28) → PASS`（`latest.log:2941`/`:2953`/`:2971`/`:3680`） |

**方向来源留档**：`docs/reviews/2026-09-14-外部质疑与工作流审查留档.md`（外部质疑三条 + 两轮工作流审查 + 设计讨论的完整来龙去脉、事实核校、裁定表、驳回项与 AI 自身教训；
想追"为什么现在这么定"就读它）。

**协议**：`docs/MOD_ADAPTER_PROTOCOL.md`（六步流水线 S0→S5；**只读先于执行**；"读不懂多少"始终可见；
进通用骨架须满足"上游自述／两上游共享／纯形态可自校验"；**反模式**：依赖上一步清场、按类名认、为适配放宽红线）。

## 2. 进行中：S3 已收口 / S4（下一次继续）

- **S3（已收口，只读，客户端验证）**：`decision/MachineMap.java` = 「**机器类型 ↔ 机器方块/菜单**」的**唯一真源**
  （D-209）；`Route.station` 由配方类型 id 换成**机器方块 id**（类型仍留在 `Route.type`）；
  探针 `MachineStationProbeTask` 改「按表认机器」（同类型取最近，每台一组 `m{i}_*`），
  并断言「菜单类 == 已实测登记值」与「**方块实体自述配方类型 == 表里的类型**」。
  勘察留档：`docs/reviews/2026-09-14-3B-S3-机器映射勘察.md`。
  **用户已拍板**：(a) `Route.station` 换方块 id ✔（若文案不合口味，回退成本 = `RecipeQuery` 一行）；
  (b) 表落在 `com.dddgn.alice.decision`（与 `RecipeDump.stationFor` 同包）✔。
  **本轮实测纠正了两件事**（都在 D-209）：① 实测类型 26 ≠ 表 27，差的是**零配方的 `mekanism:smelting`**
  ⇒ 探针改成对表行做**完整划分** + 分桶守恒自检；② **`menuClass` 不是机器身份**（两台机器实测同一个
  `MekanismTileContainer`、槽位表逐项相同）⇒ 分辨"点对了哪台"只有 `m{i}_binding`，crusher 菜单类已按观察值回填。
  **未覆盖（如实登记）**：只登记基础机，`crushing` 的 1:N 工厂变体在 `note` 里点名但未入表。
- **S4（下一步主线）**：单机最小闭环（放料→等→取产物）。**需要写入授权与预算**，按 D-076 走显式授权；
- **S5**：每次收尾都要回收临时探针（`alice:machine_probe`、`alice:machine_station_probe` 已回收 ⇒
  转为电池步 `machine_route` / `machine_station`）。

## 3. 待客户端验证

S3 的四点已在第二轮实测中全部命中（见 §1）。**只剩一条仍未有人在客户端敲过**：

- `/alice authz` 的 `L2 规划期策略表：rows=22 … zoneDiff=0 unregistered=0 undeclared=0` 行
  （电池里的 `write_policy` 步测得同口径数值，但那条**显示行本身**从未在客户端渲染过）。
- **另需一轮复跑**才能看到的两处**已改口径**（改动本身已 `COMPILES` + 闸门全绿）：
  `machine_map_rows=27 with_site_confirmed=22 with_site_unobserved=[mekanism:smelting] no_site=4 …`
  与 `m2_menu_class_matches=true`（crusher 菜单类回填后由"只观察"变成断言）。

以上两条可**并与 S4 的同一次客户端轮**一起做，不必单独开一轮。

## 4. 今天新增/变更的纪律（都在 PLAYBOOK + AGENTS.md 里）

1. **§5.0b 决策权**：你有最终决策权，但不必把每句话当最终决策；AI **允许并鼓励评价你的决策**；
   临时裁定要标 `（临时）` + 复核触发条件。
2. **§5.0c 继续/停止判据**：不需要你参与且**离线可做**就继续做，不要为"省你一轮"而停；
   **上下文量级 1,000,000 / 800,000 自动压缩** ⇒ 在那之前不得以"预算"为由停下。
3. **§5.0d 场景夹具两条硬纪律**：夹具**自带传送**到场景起点（不依赖电池 provision）+ **结束复位**
   （关菜单/停输入/回起点，失败路径同走）。
4. **active goal**：可用；范围设到"两次测试之前"，到测试点 `pause`（恢复只能由你发起）。
5. **上下文（2026-09-14 立规 + 当天再校准）**：**不估算、不主动报占比、不据此改输出或提前收尾**；
   目的是别让"估算"逼 AI 自己压缩输出（自动压缩照常工作，只要压得准）。真实数据一条命令：
   `bash tools/dsh-context-usage.sh`（窗口/压缩阈值/还差多少触发；要时才跑）。
   **数字只在 `AGENTS.md` 维护一次**，本文与其它文档只指向它（防漂移）。
   **让压缩准的正解**：事实先落盘（docs/台账/commit）+ 少灌原始日志（`head/cut`，全量写文件）。

## 5. 环境与入口速查

- 客户端：`/mnt/d/JAVA_projects/worldedit-test/versions/1.20.1-Forge_47.4.10`（日志 `logs/latest.log`）。
- 同步：`./tools/sync-windows-artifact.sh build/libs/alice-1.0.0-1.20.1.jar /mnt/d/JAVA_projects/alice "<客户端>/mods"`；
  镜像 `./tools/mirror-windows-workspace.sh`；资源自检 `bash tools/check-item-models.sh`（当前 76 项）。
- **本轮最后同步的 jar**：`ca1ec15d7547afbc`（完整 sha256 `ca1ec15d7547afbc90d76202f2f70afa5015ed817f6fc9ba52752ba585cfa55f`；
  上一版 `3ccb320b0f594366`）。变更是 **S3 机器映射单一出处 + 探针按表认机器**（D-209）：
  生产路径只改一处（`RecipeQuery` 的机器路线 `station` 文案），其余为只读探针与离线闸门。
- 场景：仓库 `tools/test-scenes/alice_test/` → 客户端存档
  `saves/新的世界/datapacks/alice_test/`（**改场景后要手动把改动的 `.mcfunction` 复制过去**，本轮已复制且 `diff -rq` 无差异）；
  `/function alice_test:machine_course`（S2+S3 机器场景：富集仓 + 粉碎机）、
  `furnace_course`、`craft_tab_course`、`craft_table_course`、`craft_station_course`。
- 电池：`alice:regression_battery`（CORE=**28**）/ `/alice battery full`（FULL=38）；
  唯一配置入口 `RegressionBatteryTask.CURATION`。
- 离线闸门（改完顺手跑）：`bash tools/check-authz-registry.sh`、`bash tools/check-policy-matrix.sh`、`bash tools/check-machine-map.sh`、
  **`bash tools/check-fixture-hygiene.sh`**（D-208 新增：夹具终态必须能传播失败）。

## 5b. 断点（2026-09-14 会话中段，上下文 ≈0.9×压缩阈值时收口）

**刚落地（已推送）**：授权/审批框架可视化 v1 —— 单一出处 `docs/authz/AUTHZ_REGISTRY.csv`（**27 道闸门 / 6 层**）
+ 生成器 `tools/authz-map.py`（零依赖，产出 `OVERVIEW.md` / `flow.svg` / 可搜索 `index.html`）
+ **防过期检查** `bash tools/check-authz-registry.sh`（断言注册表 vs 代码：拒绝码 101 / MovementType / WriteReason 全覆盖 ⇒ 当前 **PASS**）。
用户已确认"满意现在的识图"。

**下一步第一件事（新会话从这里开始）**：
1. ~~`/alice authz` 运行时命令~~ **已验证**（`BotCommand.authzSnapshot`；`SERVER_TESTED` + `WINDOWS_CLIENT`
   2026-09-14：`latest.log:202-208` 七行齐全）。零参数只读，打印 7 行——L0 当前任务 / L1 纯通行集合 /
   L3 预算余量与已拒数 / L4 账本 pending 与 scope / L4 保护区判定 / 最近终态码。用法见 `docs/TESTING_GUIDE.md` 末节。
2. **R1 集中策略表**（区域×任务类别 → `TEMP/KEEP` + Movement 集合 + 预算；默认 PROTECTED、显式降级），
   与主线 **3-B S3**（机器类型 ↔ 机器方块/菜单的单一映射）**合并成一轮离线工作**（两者同性质：建"单一出处"表）。
3. **R2/R3**（野外默认放开 `PILLAR/FALL/DOWNWARD`；`miningApproach` 改按条件放行）——**须先 A/B 客户端证据**。
4. 待用户拍板：验证等级 5→3、`AI_TEST_MATRIX` 去留、规则日落机制。

**两个已知小遗留（下次顺手处理）**：① ~~`WriteReason` 检出 14/16~~ **已查清并关闭**：`WriteReason` 真实取值就是 **14 种**（我先前数成 16，多出的 2 个来自嵌套枚举 `Policy`/`Action`）——**是检查脚本抓到我自己文档的错**，CSV 已改；顺带记下一个有用事实：`WriteReason` 每条自带分类 `Policy(EXPLICIT_TARGET/CLEARING)` + `Action(BREAK/PLACE/BOTH)`；
② `flow.svg` 无 PNG 版本（本机无 mmdc/inkscape/ImageMagick ⇒ 浏览器查看，或用时再写纯 Python 位图导出）。

**③（验证时从日志发现）** ~~`/alice authz` 在无作用域时把预算打成 `2147483647`~~ **已修（2026-09-14）**：
根因不是显示，是**口径不一致**——`remainingContainerWrites` 在无作用域时返回默认上限 32，另两桶返回 `MAX_VALUE`，
于是快照把"未生效的上限"当成真实余量打印。修法：① 该函数无作用域统一返回 `MAX_VALUE`；② 快照改为**按作用域分支**：
有作用域报三桶余量+已拒数，无作用域报"闸门未生效（不计数、不拦截，仅 `no_scope` 留痕）+ 作用域内默认上限"。
语义澄清已登记 `AI_DECISIONS.md` D-106 附注。**待客户端复验**（与下一次客户端轮一起）。
**④（验证时从日志发现，用户裁定推迟）** 回归电池结束后的自动决策选了 240 格外的 `region:saved` 做 `region_lumber`，
401 tick 后 `FAILED code=failed:outside_region`（`chopped=0 patrols=380`，`latest.log:3828`）——失败优雅且留痕（对的），
但**目标层菜单项没带可达性/距离信息**，LLM 会据此挑到够不着的活。建议：菜单项附距离或可达性标注（留到目标层菜单改造时做）。
**⑤（验证时从日志发现）** ~~DSH profile 残留 patch 条目~~ **已清（2026-09-14）**：
`~/.dsh/profiles/web/cordis.patch.yml` 引用了已不存在的 `dsh-voice-assistant-test`，每次启动都报
`patch: entry … not found`；移除后 `dsh --profile web --dump-config` 的 stderr **干净**（554 行配置照常）。

**未验证堆积**：~~`CORE 27` 尚未跑过~~ **已跑并全绿**：`PROFILE=CORE … (27/27) ticks=2845 → PASS`
（`latest.log:3801`，含新步 `machine_route` / `machine_station`）。~~③ 的新 L3 文案待复验~~
**已复验通过**（`latest.log:191`，2026-09-14 12:11：`L3 …无作用域 ⇒ 闸门未生效（不计数、不拦截，仅 no_scope 留痕）；默认上限（仅作用域内生效）破坏64/放置32/容器32`）
⇒ **当前无待验证项**。

**R1 集中策略表（2026-09-14 接线完成；A 解释 / `COMPILES`，等电池复跑）**：
- **用户拍板**：**(A)** 矩阵只管**回收义务**（`BULK_EDIT`/`MANUAL` 这类上层显式授权不受默认区约束）+
  `WORKSPACE` 来源**只认已划区域** + **规划期抛 + 执行期复验**。三项都在接线时落地。
- **术语纠正（用户质疑触发）**：区域**只有两层归属**（`EXTERNAL` / `WORKSPACE`），**保护区是独立闸门**不是第三层
  ——旧提案的 `PROTECTED` 与既有"保护区"同名反义（默认区允许破坏：`BlockBreakSafety:47` 等才拦）；
  `TRANSIT` 是维度混淆（通行是 `PathRequest` 的属性）；移动集词表改为**直接用 `PathRequest` 工厂名**（`WILD` 废弃）。
- **落地物**：`action/WritePolicyMatrix.java`（唯一真源，22 行 = 2 区 × 11 任务；`movements` 的集合**直接问工厂要**
  ⇒ 定义上不会漂移）+ 规划期闸门 `CorePathPlanner.plan:45`（越权抛 `WRITE_POLICY_MOVEMENT_DENIED`，**在规划器入口
  转成如实失败的 plan**——任务 tick 无兜底 try/catch（`BotManager:1809`），异常逃逸会打断服务端 tick）
  + 执行期复验 `WorldModLedger.recordPlacement:126` + 自检 `task/WritePolicyCheckTask`（电池新步 `write_policy`，BASELINE）
  + 视图 `docs/authz/POLICY_MATRIX.csv`（生成）+ `tools/check-policy-matrix.sh`（**当前 PASS**）+ authz 注册表新行 `L2-5`。
- **本轮不改默认行为**（A+ⓑ 的必然结果）：两区今天**逐条相同**，`zoneDiff=0` 由自检断言守着。
  **真正带上牙齿的是移动授权**：纯通行任务（`walk-to`/`follow`/`PlaceTask`）不能再规划出会写世界的移动 = D-076 红线的可执行版本。
- 登记表实测补全（接线时逐个 grep 出来的真实 requester）：`mine`（`MineJob.NAME`）、`region_lumber`（`RegionLumberJob.NAME`）、
  `PlaceTask`（`Task.taskName()` 默认 = **类名**）、`scaffold-lifecycle`、`partial_*`、`ToolMaintenance`。
- 设计背景与三处术语纠错的完整来龙去脉：`docs/authz/POLICY_MATRIX_PROPOSAL.md` §5；决策记录：`AI_DECISIONS.md` D-207 附注。

**R1 首轮客户端电池结果（2026-09-14，用户实测；AI 读 `latest.log`）—— 结果不是绿，是"两层缺陷叠出来的假绿"**：
- **表面**：`[Regression] SUMMARY … write_policy=PASS … PROFILE=CORE baseline=14 main=14 (28/28) ticks=2827 → PASS`（`latest.log:3738`）。
- **实际**：`[WritePolicy] case=grants_semantics result=FAIL … verdict=FAIL`（`:3199`,`:3206`）⇒ **电池把一步的内部 FAIL 记成了 PASS**。
1. **缺陷① 断言自相矛盾**（夹具的错，不是表的错）：`scaffoldRemoval ∩ 写原语 = ∅` 与"必须含 `DOWNWARD`"在**同一条件
   列表**里不可能同时成立（`DOWNWARD` ∈ `writePrimitives()`）⇒ **恒 FAIL、零信号**。表与工厂本身是对的：
   `PathRequest.scaffoldRemoval` 契约就是"通行 + `FALL` + `DOWNWARD`，不含 `PILLAR`/`PLACE_STEP`/`BREAK_*`"
   （`search/PathRequest.java:80-85` 注释）。修法：拆成**放置 / 挖穿**两类分别断言，并**逐项打印真实集合**
   （旧写法打印 `A||B||C` 聚合布尔，红了也定位不到是哪一个）。顺带补强：`pureTraversal` 也断言"无写原语"、
   `miningApproach` 断言"必须保留破坏进入"。
2. **缺陷② 终态不传播（静默绿）**：`WritePolicyCheckTask` 照抄 `RecoverabilityCheckTask` 的
   `return done ? Status.DONE : Status.RUNNING`，而电池**只按 `status == DONE && idempotent` 记账**
   （`RegressionBatteryTask:558`），**从不读夹具的 `verdict=`** ⇒ 假红被吞。全仓 32 个夹具里**唯二**这两处这样写，已修
   （两个夹具只被电池实例化：`RegressionBatteryTask:436,441`，无生产影响面）。
   **新静态规则** `bash tools/check-fixture-hygiene.sh`（R1 终态能表达失败 / R2 禁止"return 行 `DONE`+`RUNNING` 而无 `FAILED`"）；
   反向验证：把任一修复回退 ⇒ 立刻报红。决策登记：**D-208**。
- **其余都真绿**：27 个其余步骤全 PASS（含 `pathing` 18 课程、`recoverability`、`machine_*`、`craft_*`、`capability_gate`）、
  `guard_is_live`/`planner_refuses_and_reports`/`self_write_free`/`zone_equiv(zoneDiff=0)` 全 PASS、`K4=OK(写入类例外=43)`。
- **待复跑验证**（唯一在飞项）：重跑电池 ⇒ `grants_semantics=PASS` 且 `(28/28) → PASS`；
  顺带可选 `/alice authz` 看新 `L2` 行（**至今未经客户端验证**——本轮日志无 `/alice authz` 调用痕迹）。
- 另：`④` 旧问题在本轮日志**复现**（电池完事后自动 `start_job region_lumber`，bot 在 `8,62,66`、
  区域中心 `27,58,217` ⇒ `reason=outside_region → FAILED`，`chopped=0 patrols=380`，`:3765`）——与已记录现象一致，非新问题。

**会话摘要调查（2026-09-14，用户提问触发）**：结论 = **不必获取会话摘要，也不装第三方插件**
（摘要已原生自动产生并持久化；且 1% 量级有损 ⇒ 事实来源是原文，而压缩后原文**未丢**：1078/1078 遮蔽事件仍在磁盘、
可按 `seq` 取回）。取证 + 第三方生态清单见 `docs/reviews/2026-09-14-会话摘要调查.md`；
用户批准把取证脚本落成 **`tools/dsh-session-log.mjs`**（只读：`--list` / 默认压缩表 / `--shadowed` / `--seq a-b` / `--grep`），
纪律写进 `AGENTS.md` 固定动作⑤：**只在细节被压缩遮蔽时用，不做例行解码**。

**上下文管理成熟方案调查（子代理，2026-09-14）**：`docs/reviews/2026-09-14-上下文管理成熟方案调查.md`
（外部证据**只有标题级**——本机无外网、`web_search` 未回正文；采纳项全部由**本项目自身运行期事实**支撑）。
落地为 `AGENTS.md` 固定动作 **⑥⑦⑧**：⑥ 上下文闸门次序（自身截断 → **裁剪器已在跑，实测 32 条 `compaction/prune`** →
压缩 → `--seq` 取回）+ 不调低压缩阈值；⑦ 长期记忆只写三类（事实/决策/断点）+ 只放指针；⑧ 压缩后锚点抽查（不例行）。
复核修正：裁剪器由 **agent 预设层**挂载（`dsh-agent-presets/presets/cordis/agent.cordis.yml:133-140`），
profile 叶子上的 `disabled: true` 是"所有权在预设"而非"没启用"（报告原归因写成 `dsh-base`，已更正）。
**不采纳**：第三方插件、向量/抽取式记忆、更大窗口；O1-O4 延后。

## 6. 未做/已知边界（不假装完成）

- **机器执行（S4）未做**：`MACHINE_ROUTE` 只报路线，`CraftJob` 明确拒绝 `not_executable`；
  化学品/气体类输出如实 `machine_output_not_item` / `MACHINE_RECIPE_UNSUPPORTED`；
- **电池瘦身**：已**回退**（D-201 附注一）——撤走 8 步会暴露隐含前置；瘦身前置=**夹具自证前提**，
  目前只落地了 `FixturePremise`（ownMenu/stationMenuOpen/onGround）+ 电池级每步自证与清场，
  其余夹具待逐条接上后才能"逐条撤 + 每条复跑"；
- Refined Storage / 精妙背包站点：未做（用户此前裁定暂缓）。
