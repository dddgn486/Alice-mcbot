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
| **R1 收口：容器写入进策略表（D-211）** | ✅ 收口（客户端验证：`COMPILES` + 六闸门 PASS + `WINDOWS_CLIENT`） | 矩阵首次**经手**容器写入（挂点 `WriteBudget.consumeContainerWrite`）；`docs/authz/CONTAINER_WRITE_SITES.csv` 20 个调用点逐个命名 + `tools/policy-map.py` 断言⑦（四条负例实测都红）；顶出并补上 **`CraftJob`（生产熔炼）从没记账** 的真缺口 |
| **3-B / S3 机器映射单一出处** | ✅ 收口（客户端验证，`SERVER_TESTED` + `WINDOWS_CLIENT`） | `machine_map_rows=27 with_site_confirmed=22 with_site_unobserved=[mekanism:smelting] no_site=4 unmapped=[] row_block_missing=[]`；`按表找到 2 台` + `m1_binding=true m2_binding=true`；CORE `(28/28) → PASS`（`latest.log:2941`/`:2953`/`:2971`/`:3680`） |
| **(a) 下一模组 Thermal：S1 只读侦察 + S2 进表** | ✅ 收口（离线取证 + **客户端复核通过**） | 表 **27 → 59 行**（Thermal **32** 行，**全 `READ_ONLY`**）；`docs/THERMAL_S1_FACTS.md`（22+11 个设备方块、30 类型全分类、红线① dynamo）；**第十四轮** `(30/30) ticks=3279 → PASS`，`row_block_missing=[]` + `unmapped=[]`（⇒ 32 个方块 id 全对），`craft_machine` 逐字未变；闸门 `machine-map.py` 升级为**按命名空间双向断言 + JiJ 内嵌 jar 取证**（`Tier B OK` 两条：27/27、32/32） |

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
- **S4（场景电源已自证 ✅ `energy_source=cube` + `verdict=PASS`，`latest.log:213`）＝ 3-B 的第一次写入**：
  `MachineCycleCheckTask`（放料 → 等 → 取产物）由零参数物品 **`alice:machine_cycle_check`** 触发；
  场景在富集仓下方加**真实电源** `mekanism:creative_energy_cube`（纯数据）。
  **不新造授权**：容器写入维度 `WriteBudget` + `WriteReason.CONTAINER_TRANSFER` + requester `machine-cycle`
  （矩阵登记为 `CONTAINER`）；放料走 shift-click（**菜单自己决定落点**）、成不成**只看结果**（机器里有料 / 背包里产物 +N）。
  能量/进度/菜单全是**上游自述**；补电这种测试前提**必然留痕** `energy_source=…`（D-210）。
  **电源真因（D-213，修正 D-212）**：创造能量方块**放下就是 0 J 且永远充不进电** ⇒ 场景改用
  `data merge block … {EnergyContainers:[{Container:0,stored:"4000000000"}]}` 把电直接写进方块实体（命令 10→11 条）。
  **第七轮已重验**：`energy_at_open=20000.0`、`energy_source=cube（场景电源，未补电）`、`energy_ready=20000.0`
  （**不再是** `api_precharge` 的 4.0E6）、`progress_ticks=199 product_landed=true machine_emptied=true
  input_consumed=true container_writes=2 reset=true verdict=PASS` ⇒ **D-213 判据成立**。
  **v1 边界**：单机单配方、站位用夹具传送（**内核寻路走到机器旁 = S4 v2**）。
  **已升电池步（D-197，CORE 28→29 / FULL 38→39）**：`machine_cycle`（MAIN，`stepSkippable`，预算 1600）；
  该步的客户端绿待一轮 CORE 电池确认。
- **S5**：每次收尾都要回收临时探针（`alice:machine_probe`、`alice:machine_station_probe` 已回收 ⇒
  转为电池步 `machine_route` / `machine_station`；S4 的闭环自检**已做成物品并转电池步 `machine_cycle`**，
  临时入口 `alice:machine_cycle_check` **留到 S5 收口时回收**，回收前提 = 该电池步在 CORE 里转绿）。

## 3. 第五轮 + 第七轮客户端结果（2026-09-14）

**A. `alice:regression_battery` CORE → `(28/28) ticks=2765 → PASS`**（`latest.log:3737`）。

- **R1 容器写入闸门（D-211）真的活了**（`:3206`）：`container_gate_live=PASS container_gate_armed=PASS
  containerGate=armed unregistered=0 undeclared=0 container_checks=13 container_refused=0 verdict=PASS`；
- **13 对得上账**：= 各步 `[WriteBudget] SUMMARY … containers=N/32` 之和
  （provision 2 + craft 2 + furnace 3 + cooking 4 + transfer 2）⇒ 闸门覆盖面与预算覆盖面**逐点一致**
  （"挂点真接上了"的硬证据，不是恒 0 的死开关）；
- `container_refused=0`、每步 `refusedContainers=0` ⇒ **没有生产路径被硬停**；
- 日志里唯一那条 `denied action=container`（`:3201`，`by=walk-to:CONTAINER_TRANSFER`）是
  `container_gate_armed` 负例**故意**打的 ⇒ **WARN 出现而 `container_refused=0` 不矛盾**
  （负例跑完 `finally` 还原开关 + 快照还原观察样本）；⇒ **D-211 的两条复核触发都已解除**；
- 旁记：`K4=OK(… 写入类例外=43)`（上轮 56，交替，两次都 `K4=OK`，未取证）。

**B. `alice:machine_cycle_check`（S4）→ `verdict=PASS`**（`:3811`）：`binding=true`（表 == 方块实体自述）、
`feed_verified=true in_machine=1`、`active_seen=true progress_ticks=199`、`product_after=1
product_landed=true machine_emptied=true input_consumed=true`、`container_writes=2`（同走闸门+预算，`:3815`）、
`reset=true reset_pos=66, 64, 304` ⇒ **S4 = `WINDOWS_CLIENT`**。

**C. ✅ 电源前提已自证 —— 第七轮结果（D-213 复核通过，`WINDOWS_CLIENT`）**：`/reload` →
`/function alice_test:machine_course`（**11 条命令**，含 `data merge` 灌电，`latest.log:187`）→ 右键
`alice:machine_cycle_check` ⇒ `latest.log:213`：`energy_at_open=20000.0`、
**`energy_source=cube（场景电源，未补电）`**、`energy_ready=20000.0`（不再是 `api_precharge` 的 4.0E6）、
`progress_ticks=199 active_seen=true product_after=3 product_landed=true machine_emptied=true
input_consumed=true container_writes=2 budget_remaining_after=30 reset=true verdict=PASS`
⇒ **电来自场景本身、没走补电兜底，D-213 判据成立**；D-212 的"朝向"因果判定确认作废（`[facing=up]` 保留）。
用户侧确认"方块本体运行正常，符合预期"。
**判据语义别读强**：`energy_source=cube` 证的是"**场景把电送上了**"（开机时机器已有电 `energy_at_open > 0`），
不是"程序认出了 cube 方块"（读的是机器自己的能量容器）；场景里只有这一条供电路径 ⇒ 两者等价。

**本轮顺带落地的两件事**：① **S4 升为电池步 `machine_cycle`**（D-197）：`RegressionBatteryTask` CURATION 加
`machine_cycle`（MAIN）、步定义排在 `machine_station` 之后（`stepSkippable`，预算 1600 > 任务自身 1400，
`machine_absent` ⇒ SKIP）；电池 **CORE=29 / FULL=39**，`docs/BATTERY_CURATION.md` 同步。
② **台账 ⑥ 关闭**：`MachineCycleCheckTask` 类注释按 D-213 改写（含"能量判据的准确含义"一段）。
新 jar `sha256=b290b8b3a1276915feee509f6e7203aad7ca16ad2454e742b5f2d14dc3a7984f`
（已镜像到 `D:\JAVA_projects\alice` 并同步到客户端 `mods/`）⇒ **客户端必须重启才会加载新 jar**。

**✅ 收口复核已通过（第十轮客户端，16:20–16:23）**：`[alice] 串联回归电池已启动（**29 项**，约 2~4 分钟）`
（`latest.log:199`，文案不再写死 26 ⇒ 台账⑦ 修复生效）+ `PROFILE=CORE 实跑 29 项（跳过 EXTRA 10 项）`（`:200`）
+ `[Regression] SUMMARY … machine_route=PASS machine_station=PASS machine_cycle=PASS … (29/29) ticks=3083 → PASS`（`:3849`）。
**"探针零残留"由 Forge 自己证明**：进世界报 `[ERROR] Unidentified mapping from registry minecraft:item
alice:machine_cycle_check: 3405` + `missing registry entries`（另有 stats 一条非法统计警告）⇒ 物品已不在注册表。
⚠️ 这类警告是**删注册物品的一次性自愈副作用**（退出后 `level.dat` 里 `grep machine_cycle_check` = **0**、
`stats/<uuid>.json` 里该键已消失），**不是回归** —— 见 D-215 附注一。
旁记：`K4 写入类例外=56`（第九、十轮**连续两次 56**，"43↔56 交替"被削弱；两次都 `K4=OK`）。

**当前弧（用户 2026-09-14 裁定：(c) 起步 + (a) 并行只读）**：

- **(c) 第 1 步 = S4 v2（D-216，已 IMPLEMENTED + COMPILES）**：闭环自检**自己走到机器旁**。
  `CYCLE_START=(72,64,312)`（平台远角，到机器 ≈8.49 格）+ 新 `WALK` 相位
  （`TableCraft.standPointNear` → `PathRequest.of` **纯通行** → `PathRetryRunner` → `inReach` 断言）；
  v1 的"够不着直接判红"删掉（够不着 = 该走路）；扫描半径 6→12。**夹具仍然自己传送**（纪律不变）。
  场景文件只改了**注释**（命令一条没动）⇒ **客户端不需要 `/reload`**。
- **(c) 下一增量（没做）**：把它接进 `CraftJob` 的 `MACHINE_ROUTE`（现在仍 `not_executable`）。
  接线红线：**①`api_precharge` 兜底绝不能进生产**（没电 ⇒ 如实失败）；**②目标机器来自路由 `station`**；
  化学品/气体 I/O 继续如实拒绝。
- **(c) 增量 2 已完成（代码侧，D-217，2026-09-14）**：上面那条红线**已经落地成机制** ——
  闭环本体抽到 `task/craft/MachineCycle`（**夹具与生产同一份实现**），夹具 `MachineCycleCheckTask` 变薄壳；
  `CraftJob.MACHINE_ROUTE` 现在真的驱动机器，准入**数据驱动**（`MachineMap.executable(...)`：只有
  `mekanism:enriching` 一行是 `Capability.EXECUTABLE`，其余照旧 `not_executable`）。
  **红线①机械可查**：执行器里没有造能量的代码，`EnergyTopUp` 只有夹具实现、生产位置传 `null`；
  新门禁 `tools/check-precharge-containment.sh`（**已做反向测试**：注入一次 `precharge(` ⇒ 立刻红）。
  新增电池步 `craft_machine` ⇒ **CORE 29→30 / FULL 39→40**；六道离线门禁 PASS；jar
  `sha256=1606dc62…` 已同步客户端 `mods/`。
- **(a) 第 1 份 S0 事实表已交付**：`docs/THERMAL_FACTS.md`（Thermal 652 条 / 30 类型；前 5 = 500 条 76.7%；
  静态 jar 618 vs 运行时 652 的 +34 已算术闭合但**来源未取证**，留 S1）。两个前置缺口见台账⑩
  （**无 sources jar**、**`alice-recipes.json` 已过时**）。

**✅ 已执行（第十二轮客户端，2026-09-14 16:58–17:01，`WINDOWS_CLIENT`）**：`[alice] 串联回归电池已启动（**30 项**…）`
（`:190`，文案现算 ⇒ 30）+ `PROFILE=CORE 实跑 30 项（跳过 EXTRA 10 项）`（`:191`）⇒
`[Regression] SUMMARY … machine_cycle=PASS **craft_machine=PASS** … (30/30) ticks=3370 → PASS`（`:3899`）。两条硬判据全部拿到：
① `craft_machine=PASS ticks=255`（`:3185`）：`target=minecraft:soul_soil`（**后备逻辑真的用上了** ——
`candidates_tried` 显示首选 `clay_ball` 的路线落在 `mekanism:chemical_injection_chamber`，那台**没有执行准入**
⇒ 生产如实拒绝、夹具换下一个）、`route_station=mekanism:enrichment_chamber`、`job_terminal=DONE`、
**`m_walk_state=DONE m_walk_ticks=41 m_machine_reach=1.5`**（与夹具第十一轮**逐字同值**）、
`m_energy_source=present（…）`（**不是 `api_precharge`**）、`product_after=1 product_landed_inventory=true`、
`WriteBudget … scope=…#1596:Regression:craft_machine breaks=0 places=0 containers=2/32 refusedContainers=0`；
旁证 `[CraftJob] machine SUMMARY … machine_cleaned_before=skipped（生产路径不动机器里的存量）… verdict=PASS`（`:3178`）；
② `machine_cycle=PASS ticks=251` ⇒ **夹具换薄壳后无回归**（与第十一轮 251 **逐字相同**）。
③ **用户目视确认**：bot 从平台远角**自己走过去**（第十一轮缺的那条目视证据补上了）。
本轮 `api_precharge` **全日志零命中** ⇒ 红线①在客户端也成立。`K4=OK(… 写入类例外=56)`（上轮 58 ⇒ 又一次
"搜索次数差异"的波动，与台账结案一致）。唯一一条 `ERROR` 字样是 `write_policy` 的**预期负例**（`期望 status=ERROR`+`result=PASS`）。

**✅ 已执行（第九轮客户端，`WINDOWS_CLIENT`）**：新客户端会话（14:34:59 启动 ⇒ 新 jar 已加载 ——
启动日志 `[Regression] PROFILE=CORE 实跑 29 项（跳过 EXTRA 10 项）`）⇒
`[Regression] SUMMARY … machine_route=PASS machine_station=PASS **machine_cycle=PASS** … K4=OK(goal_not_standable=0
final_segment_not_standable=0 写入类例外=56) PROFILE=CORE baseline=14 main=15 extra_skipped=10 **(29/29) ticks=3108 → PASS**`
（`latest.log:3842`）。该步明细：`machine_cycle=PASS ticks=210 idempotent=true`（`:3130`）、
`[MachineCycle] SUMMARY … energy_at_open=20000.0 energy_source=cube（场景电源，未补电） energy_ready=20000.0
… product_landed=true machine_emptied=true input_consumed=true container_writes=2 reset=true verdict=PASS`（`:3129`）、
`[WriteBudget] … scope=…#1503:Regression:machine_cycle … containers=2/32 refusedContainers=0`（`:3131`）
⇒ **电池内走的也是场景电源，升格闭环完成，S4 收口**。
旁记：`K4 写入类例外=56`（第八轮 43 ⇒ **第三次观察，仍在 43/56 交替**，两次都 `K4=OK`，仍未取证）。
⚠️ 启动聊天文案仍写"**26 项**"（实测 `(29/29)`）⇒ 台账 ⑦：**S5 收口重编 jar 时改成从 `CURATION` 推导**。

## 4. 今天新增/变更的纪律（都在 PLAYBOOK + AGENTS.md 里）

1. **§5.0b 决策权**：你有最终决策权，但不必把每句话当最终决策；AI **允许并鼓励评价你的决策**；
   临时裁定要标 `（临时）` + 复核触发条件。
2. **§5.0c 继续/停止判据**：不需要你参与且**离线可做**就继续做，不要为"省你一轮"而停；
   **上下文窗口 512,000（阈值 409,600 = 0.8×W，保留 ≈81,920 = 0.16×W）** ⇒ 不围绕上下文思考、不主动报占比；
   只有真到 ≈0.9×409,600 才先写 HANDOVER + 提交（D-214；这三个数随窗口变，现算别背）。
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
- **本轮最后同步的 jar**：`ca42c32f4bd2e54c`（完整 sha256 `ca42c32f4bd2e54c1d86947b82ba93485f32cc605cb2cdf34af49e08a40b1b81`；
  上一版 `36d6f3e28c3eaaa8`，再上版 `ca1ec15d7547afbc`）。变更是 **R1 收口：容器写入进策略表（D-211）**
  —— 矩阵首次经手容器写入 + 调用点覆盖登记表 + 补上 `CraftJob`/`MenuProbeTask` 两个真缺口。
  **第五轮无 Java 改动 ⇒ jar 不变**（D-212 修的是场景 `.mcfunction`，纯数据）。
- 场景：仓库 `tools/test-scenes/alice_test/` → 客户端存档
  `saves/新的世界/datapacks/alice_test/`（**改场景后要手动复制改动的 `.mcfunction` 过去 + 游戏里 `/reload`**
  —— 数据包只在世界加载/`/reload` 时读盘；第七轮已复制、`diff -rq` 无差异、**已 `/reload` 并实测生效**）；
  `/function alice_test:machine_course`（S2+S3 机器场景：富集仓 + 粉碎机）、
  `furnace_course`、`craft_tab_course`、`craft_table_course`、`craft_station_course`。
- 电池：`alice:regression_battery`（CORE=**30**）/ `/alice battery full`（FULL=**40**）；
  唯一配置入口 `RegressionBatteryTask.CURATION`。
- 离线闸门（改完顺手跑，**六道**）：`bash tools/check-authz-registry.sh`、`bash tools/check-policy-matrix.sh`、
  `bash tools/check-machine-map.sh`、**`bash tools/check-fixture-hygiene.sh`**（D-208：夹具终态必须能传播失败）、
  **`bash tools/check-precharge-containment.sh`**（D-217：红线①"补电不许进生产"—— 造能量的调用只许命中夹具，
  注入点只有一个实现者，且反向断言夹具里符号还在；**改过 CraftJob/MachineCycle 就跑它**）。

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
  + 视图 `docs/authz/POLICY_MATRIX.csv`（生成）+ `tools/check-policy-matrix.sh`（**当前 PASS**）+ authz 注册表新行 `L2-5`；
  **R1 收口再加两件**（D-211）：`docs/authz/CONTAINER_WRITE_SITES.csv`（容器写入调用点登记表，20 行，**手工维护**）
  + 同一脚本的**断言⑦**（登记表 ↔ 代码双向一致；负例四条已实测都红）。
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

- ~~**机器闭环（S4）已实现但未验证**~~ **第五轮已跑通**（`verdict=PASS`，`latest.log:3811`）；
  ~~**但场景电源前提是假的**~~ **第七轮已自证**（D-213：真因是创造方块放下就是 0 J 且充不进电，
  场景改用 `data merge block … EnergyContainers=[{Container:0,stored:"4000000000"}]` 灌电）⇒
  `energy_source=cube（场景电源，未补电）` + `energy_at_open=20000.0` + `verdict=PASS`（`latest.log:213`）；
  **已升电池步 `machine_cycle`**，第九/十/十一轮 CORE **连续三次绿**（最近 `(29/29) ticks=3116 → PASS`，`latest.log:3924`）；
  **S4 v2（D-216）第十一轮已验证"闭环自己走到机器旁"**（`walk_state=DONE walk_ticks=41`，内核自述 `finalFoot` 逐字一致，
  该段零写入）⇒ **增量 2 = 把这条闭环接进 `CraftJob` 的 `MACHINE_ROUTE`**（两条红线：`api_precharge` 不进生产 +
  目标机器取路由的 `station`，见 `docs/AI_PROJECT_STATE.md` 当前弧）；
- **容器写入覆盖的已知边界（D-211）**：`InventoryCraft` 的结果槽 shift-click 在开着容器菜单时会把产物放进容器
  （实测 `product_in_container=1`）而**不过闸**（接它需要给该方法 grant 参数）；`TransferFixture` 在隔离层
  直接驱动搬运原语，**有意**不过闸（它验的就是原语自身）；
- ~~**`K4 写入类例外` 在 43 ↔ 56 之间交替**~~ **第十一轮已定性结案**：它是 `PathingStats` 的**全局搜索事件计数**
  （"经写入边到达目标格但格不可站"，见 `AStarMovementSearch:112-122`），**按构造不参与 `k4Ok` 判定**，
  43/56/56/58 的波动 = 每轮搜索次数差异 ⇒ 详见台账第四轮那条旁记（不是"脚手架遗留"）；
  化学品/气体类输出如实 `machine_output_not_item` / `MACHINE_RECIPE_UNSUPPORTED`；
- **电池瘦身**：已**回退**（D-201 附注一）——撤走 8 步会暴露隐含前置；瘦身前置=**夹具自证前提**，
  目前只落地了 `FixturePremise`（ownMenu/stationMenuOpen/onGround）+ 电池级每步自证与清场，
  其余夹具待逐条接上后才能"逐条撤 + 每条复跑"；
- Refined Storage / 精妙背包站点：未做（用户此前裁定暂缓）。
