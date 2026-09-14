# 模组机器适配实验协议 v1（2026-09-13，阶段 3-B 起点）

> **目的**：把"适配一个模组"从**一次性手艺**变成**可重复的实验**。
> 每做完一个模组，产出物不是"支持了它"，而是三样可复用资产：
> ① 该模组的**能力事实表**；② **通用骨架的缺口清单**（哪些判据被证明是通用的）；③ 下一个模组的**检查单**。
> 用户 2026-09-13 裁定：**本次适配 = 今后适配其它模组/附属模组的实验**。

## 0. 不可动摇的前提（与项目红线一致）

- **未知模组能力默认只读**：先读出事实，再谈执行；
- **不猜槽位、配方、写入语义**：认不出就如实报码（`*_unsupported`），不"取第一个像的"；
- **服务端是世界/库存/进度的真相**；LLM 只做目标级选择；
- **通用骨架优先**：能由"上游自述"或"至少两个上游共享的判据"驱动的，才进骨架；否则留在模组专属适配器里；
- 执行（真消耗真产物）**永远排在只读之后**，且要显式授权 + 预算。

## 1. 六步流水线（每步一个独立验收点，可随时停下）

| 步 | 做什么 | 产出物 | 验收（判据） | 允许的写入 |
|---|---|---|---|---|
| **S0 枚举** | 运行时配方表按**类型**聚合：数量、产出、输入形态 | `docs/<mod>_FACTS.md`（含"读不懂多少"） | 数字可复算（脚本/`RecipeDump` 口径一致） | 零 |
| **S1 只读发现** | 把该模组类型读成 `输入 → 输出 + 机器类型` 的事实；接进查询层（`RecipeQuery` 的 `machine_route`） | 适配器类 + 查询层正/负例 | 探针 `SUMMARY`：类型计数 + 代表类型样例 + 零写入 | 零 |
| **S2 站点发现** | 用**已有骨架**（"问上游自述"套路）认**机器方块/菜单/槽位/进度数据**；认不出就报码 | 站点描述符 + `SUMMARY` 事实表 | 槽位/容器/进度来源逐项有出处；认不出时**如实拒绝** | 零 |
| **S3 能力闸门** | 新类型进**单一映射处** = `decision/MachineMap.java`（**唯一真源**：`typeId → 方块/菜单/能力`；`RecipeDump.stationFor` 只判"需不需要机器"，**不回答"哪台"**），让"查询"知道去哪台机器；无站点/未登记**如实回落**、缺料仍如实拒绝 | `MachineMap` + 生成视图 `docs/MACHINE_MAP.csv` + 双向防漂移 `tools/check-machine-map.sh` | 表 == 上游全部类型（**按命名空间分别双向断言**：Mekanism 27 = 23 有站点 + 4 无单方块站点；Thermal 32 = **32 + 0**，其中 **6 行是 `SiteKind.SHARED` 共享站点**（站点方块问宿主行）；共 59 行。T3 / 2026-09-14 起，`SHARED` 还被断言「宿主存在且自己是 `SINGLE`」且「共享行恒 `READ_ONLY`」）；写错 id / 删行 ⇒ 报红（已反向验证）；机器路线 `station` = 方块 id | 零（今天 0 行 `EXECUTABLE`） |
| **S4 单机最小闭环** | 一台机器 + 一个配方：放料 → 等 → 取产物（**3-B 的第一次写入**） | 夹具 `MachineCycleCheckTask`（**S5 起唯一入口 = 电池步 `machine_cycle`**；临时物品 `alice:machine_cycle_check` 已回收）+ 场景加**真实电源**（`mekanism:creative_energy_cube`，纯数据） | **只看世界事实**（产物 +N 进背包、机器里不再有产物、料被消耗） | 容器写入维度 `WriteBudget` + 理由 `CONTAINER_TRANSFER` + requester `machine-cycle`（矩阵登记为 `CONTAINER`）。**S4 v2（D-216，第十一轮实测）**：起点改到平台远角 ⇒ 闭环**自己走 8 段**到机器旁（`walk_state=DONE walk_ticks=41`，内核自述 `finalFoot=66, 64, 305`），该段 `WriteBudget breaks=0 places=0` = 零写入 |
| **S5 收口回收** ✅ **2026-09-14 已完成（Mekanism）** | 回收临时探针；登记决策/电池（D-197）；写"通用 vs 专属"对照表 | 决策条目 + 对照表（见 §6） | 探针零残留（物品类 + 注册 + 模型 + 两份 lang + 无调用点的 `assign*` 全删，`grep machine_cycle_check` 源码零命中）；`CURATION` 与文档一致（39 项 = 步定义 39 条，CORE 29 / FULL 39） | 零 |
| **(c) 机器路线接进生产路径** ✅ **2026-09-14 已完成并客户端验证（D-217，第十二轮）** | `CraftJob` 的 `MACHINE_ROUTE` 真的驱动机器 —— 闭环执行器 `task/craft/MachineCycle` **与夹具同一份实现**；准入 = `MachineMap` 的 `Capability.EXECUTABLE`（**数据驱动**，本轮只升 `mekanism:enriching` 一行）；**不补电**（没电 ⇒ `machine_no_energy`） | 电池步 `craft_machine`（复用 `machine_course` 场景；夹具只传送/挑目标/备料）+ 门禁 `tools/check-precharge-containment.sh` | 生产终态 `COMPLETED` + 目标物真的 +1 + `machineFacts` 里 `walk_state=DONE`/`product_landed=true`/`machine_emptied=true`；`CORE=30 / FULL=40`；**第十二轮 `(30/30) ticks=3370 → PASS`**（`latest.log:3899`）：`craft_machine=PASS ticks=255`、`m_walk_state=DONE walk_ticks=41`、`m_energy_source=present`、`api_precharge` 全日志零命中 | 容器写入维度 `CONTAINER_TRANSFER` + requester `craft`（矩阵已登记）；`docs/authz/CONTAINER_WRITE_SITES.csv` 的写入点已从夹具改为 `task/craft/MachineCycle` |

**"读不懂多少"必须一直可见**：每个模组的实验记录都要保留"被跳过的类型/条数"这一栏——它是下一轮的输入，
不是失败指标（D-182/D-183 的做法）。

## 2. 实验记录模板（每个模组一份，写在 `docs/<mod>_ADAPTER.md`）

```text
## 1. 类型清单与读法        （哪些类型、多少条、输入/输出形态、谁读得懂）
## 2. 站点与槽位            （机器方块/菜单/槽位/进度数据：逐项出处；认不出的报什么码）
## 3. 能力闸门              （映射到哪台机器；缺料/无站点的拒绝码）
## 4. 闭环证据              （世界事实原文：SUMMARY 行 / 日志行）
## 5. 通用骨架改动          （改了哪几处、为什么这是"通用"的）
## 6. 模组专属代码          （留在适配器里的部分，以及为什么不能通用）
## 7. 回收清单              （本轮加了哪些临时探针、何时回收）
## 8. 未做与风险            （不假装完成）
```

## 3. "通用 vs 专属"的分离判据（这是实验的核心产出）

进**通用骨架**必须满足其一：
- **上游自述**驱动（例如 `getCookingSlots()` / `getRecipeSlots()`：问对象自己，而不是认类名）；
- **至少两个上游共享**同一判据（例：原版熔炉与精妙熔炼页签共用"3 格 + 进度自述"）；
- 纯**形态学**且可自校验（例：`mayPlace` 行为探针判定输入/燃料/输出）。

否则：留在**模组专属适配器**里，并在记录 §6 写清"为什么不能通用"。

## 4. 反模式（禁止）

- ❌ 按类名/硬编码槽位下标（vanilla 成员在生产环境是 SRG 名；模组槽位会随版本搬）；
- ❌ 把"读不懂"当"做不到"（跳过 ≠ 不可用，要分开报码）；
- ❌ 为适配某模组**放宽红线**（要放宽 ⇒ 单独登记为"有名字的例外"，见 D-200 附注二的做法）；
- ❌ 只报"跑通了"，不报"读不懂多少"；
- ❌ 探针留在生产代码里（验证通过即回收）；
- ❌ 先做执行再做只读（顺序反了会把"猜语义"固化进代码）；
- ❌ **依赖"上一步顺便清场"**（2026-09-13 实测事故：CORE 少跑 8 步 ⇒ 三步失去隐含前置而变红；
  而 `capability_gate` 又在 FULL 顺序下因**前置被污染**而在第一 tick 断言失败）。
  **夹具/探针必须自己显式自证前提**（菜单身份 / 位置 / 方块实体状态 / 账本与归因的时间窗），做到**顺序无关**；
  删步骤时要"**逐条删 + 每条复跑**"，不允许一次砍掉一批。

## 5. 第一个实验：Mekanism（按 D-183 排序第 1）

**已知事实**（`docs/STAGE2_MODS_READABILITY.md` §4，2026-09-13）：被跳过 **1171** 条 / **26** 个类型；
代表类型：`crushing` 210、`enriching` 142、`injecting` 76、`combining` 62、`purifying` 28。
**第一最小闭环 = S0 + S1（全只读）**：产出 `docs/MEKANISM_FACTS.md` + 查询层"机器路线"事实 +
零参数探针 `alice:machine_probe`（我自决形态；**验证通过后按 S5 回收或按 D-197 转成电池步**）。
**S2 站点发现**留到 S1 的读数出来之后再定范围（先看类型形态，再决定认哪些机器）。

**S0→S5 已走完（2026-09-13 ~ 09-14）**：S1/S2/S4 的临时入口全部转为电池步
（`machine_route` / `machine_station` / `machine_cycle`，见 `docs/BATTERY_CURATION.md`），
证据链在 `docs/AI_DECISIONS.md`（D-204 S1 / D-206+D-209 S2S3 / D-210+D-213 S4 / D-197 分档）。

## 6. Mekanism 实测：**通用 vs 专属**对照表（S5 产出，2026-09-14）

判据见 §3。证据 = 各类里 `mekanism` 字面量出现次数（`grep -c`，只读、可复算）+ 上表各步判据。

| 部件 | 归属 | 证据 / 为什么 |
|---|---|---|
| `decision/MachineMap`（`typeId → 方块/菜单/能力`） | **多模组**（每个命名空间全量；唯一真源） | 它**就是**这张表，**每行带自己的取证件**（`SRC` / `SRC_THERMAL`），所以加一个模组 = 加一段行 + `tools/machine-map.py` 的 `UPSTREAMS` 一条。防漂移靠 `tools/check-machine-map.sh`：**按命名空间分别双向断言**（Mekanism 27 = 23 有站点 + 4 无单方块站点；Thermal 32 = 32 + 0，其中 6 行为 `SHARED`），并断言「`SHARED` 的宿主存在且为 `SINGLE`」「共享行恒 `READ_ONLY`」。**多模组后不允许"只登记几行"** —— 表里出现 `thermal:` 就要求 thermal 全量，否则这一族永远不被复核。⚠️ 有的模组把内容藏在**内嵌 jar（JiJ）**里（Thermal 的 `thermal_core`）⇒ 工具必须显式解出来放进 `javap` 的 classpath |
| `task/craft/RecipeQuery`（`machine_route` 判定） | **通用** | 只含 1 处 `mekanism`（注释里的实测例子）；判定走"上游自述读得出输入/输出"这一**形态学**判据，不认类名 |
| `task/craft/MachineRecipeFacts`（配方 I/O 反射读法） | **通用** | **0** 处 `mekanism` 代码字面量：**逐字段先原版语义**（`getIngredients()` / `getResultItem(access)`）、读不出**才**退模组名族（T3/B3a，2026-09-14）—— 原版接口是**跨模组契约**，名族是**对上游访问器名字的断言**（改名只静默读不出）；且**逐字段记出处**（`inputOrigin`/`outputOrigin`/`chanceOrigin`）+ `divergent` + `readNotes`（**"调用失败"与"名字不存在"分开**：`NoSuchMethodException` 是名族常态不入 notes）⇒ 旧 `catch (Throwable ignored)` 让"读不懂"与"没有"不可区分的坑已堵。**实测值不变**：`vanilla_input=0`（机器配方不实现原版 `getIngredients()` 的物品语义 ⇒ 名族对输入**不是可选项**）、`vanilla_output=20`（全部 Mekanism，与名族给出**相等**列表）、`divergent=0`、`read_notes=0`；证据 `docs/reviews/2026-09-14-T3-B3a-读取器vanilla优先与探针确定性.md`。名族覆盖 **两族访问器**（Mekanism `getInput`/`getOutputDefinition` + Thermal `getInputItems`/`getOutputItems`），按**返回值形态**自校验；`itemReadable()` 把"读不懂"与"没有"分开报码（D-204 的 `mats=[]` 教训）；`chanceDeclared()` **只声明"上游给了概率信息"、不解释数值**（Thermal 实测 146/670 条声明 `chance`，取值 0.05~12.5 **不在 [0,1]** ⇒ 不许当概率读，语义见台账⑯）。**S1 探针的私有反射读取器已删除，改调本类**⇒ 只有一个读取器，"探针读得出 / 生产读不出"的口径漂移不可能再现 |
| `task/MachineStationProbeTask`（站点/槽位/进度探针） | **通用** | **0** 处 `mekanism`；全部"问对象自己"（槽位表、`ContainerData`、进度方法名） |
| `task/craft/StationProvision`（菜单写入协议） | **通用** | **0** 处 `mekanism`；shift-click 让菜单决定落点 + **按结果验证**，不猜槽位语义 —— S4 的写入路径直接复用，一行没新造 |
| `action/WritePolicyMatrix` + `WriteBudget`（容器写入闸门） | **通用** | 与模组无关；只登记 `requester × 理由 × 区/任务`（`machine-cycle` 一行即接入） |
| `task/MachineProbeTask`（S1 探针） | **通用** | **0** 处 `mekanism` 代码字面量（只留 1 处注释说明历史）：**枚举来源 = 配方注册表**（不是本表）：`unmapped` 覆盖**全部非原版配方类型**，并对**表里 0 行的命名空间**逐条出声（实测 `ns=create types=15 type_recipes=506`、`ns=extendedcrafting types=4 type_recipes=25`；旧实现枚举来源就是表本身 ⇒ **未登记模组连枚举都进不去**，「表里没有」与「模组不存在」不可区分）；**采样仍只限已登记命名空间**（但**形状**会另做一步纯只读定点采样：`unregistered_sampled=37`，`create 29/29`、`extendedcrafting 8/8` 产出可读，0 条留痕）。⚠️ 承重事实：`unregistered_vanilla_only_out=37/37` ⇒ **模组名族对 Create/EC 读不出产出**，是「先原版语义」在读它们 ⇒ 改前那两个模组的物品会被判 `MACHINE_RECIPE_UNSUPPORTED`。**抽样是确定性的**（内层按 recipe id、外层按 type key、自检选样按 item id 排序 —— 2026-09-14 实测：`RecipeManager.getRecipes()` 的迭代序**跨轮不稳定**，修前同 jar 两轮读数就不同，`recipe_order_hash` 5 轮 5 值可作证）；采样哪些命名空间**按 `MachineMap` 表推导**，并**逐命名空间**各打一行覆盖计数 ⇒ **加模组不用改它**（台账⑭ 的修法：写死单命名空间时，第二个模组的站点行会被静默归进"上游零配方"） |
| `task/MachineCycleCheckTask`（S4 夹具，(c) 增量 2 起 = **薄壳**） | **模组专属** | `TARGET_BLOCK = "mekanism:enrichment_chamber"`（1 行）+ 反射上游 `mekanism.api.math.FloatingLong` **补电兜底**；闭环本体已抽到下面那行。换机器 = 换这一行；换模组的能量访问 = 换 `precharge` 那一段 |
| `task/craft/MachineCycle`（**闭环执行器，夹具与生产同一份**） | **通用**（模组无关） | **0** 处 `mekanism` 字面量：走位用内核 `PathRequest.of`（纯通行）/`PathRetryRunner`、开菜单用 `MenuSession`、写入用 `StationProvision`、能量"读得出就读、读不出就不据此判红"全走**反射问对象**（`getEnergyContainers`/`getEnergy`），**造能量那条通道由调用方注入、只有夹具实现**（门禁 `tools/check-precharge-containment.sh`）。换模组 = 一行都不用改 |
| 场景电源（`mekanism:creative_energy_cube` + `/data merge block … EnergyContainers`） | **模组专属（且依赖上游语义）** | 创造方块放下是 **0 J**、且 creative 侧 insert/extract 强制 SIMULATE ⇒ 必须用 `/data merge` 直接写方块实体；这是**上游实现细节**，别的模组的"创造电源"未必同构（D-213） |
| 机器容量 20 kJ / 耗电 50 J/t | **模组专属数值** | 只用于夹具的**兜底补电**与"别把机器顶过容量"的提醒；不参与任何生产判据 |

**结论（本次实验的核心产出）**：**执行侧（菜单协议 + 写入闸门 + 闭环执行器）与发现侧（问上游自述）都是通用的，
真正专属的只有两处半** —— ① `MachineMap` 这一张表（含 `EXECUTABLE` 执行准入那一列）；② 夹具里的目标方块
（1 行）与能量访问（1 段反射，且**只有"按前提补电"那一下**是夹具专属）。⇒ 下一个模组的边际成本 ≈
"填表 + 换 1 行目标 + 换 1 个电源造法"，**不需要新内核**。
**(c) 增量 2 的加强（D-217）**：机器闭环现在**夹具与生产同一份实现**（`task/craft/MachineCycle`），
于是"生产能不能做某台机器"变成**表里的一列**（`Capability.EXECUTABLE`，静态检查 `tools/machine-map.py` 强制
"有站点 + 有实测 `menuClass`"），而不是代码里的一个分支 ⇒ 换模组/加机器 = **改数据**，不改执行路径。
