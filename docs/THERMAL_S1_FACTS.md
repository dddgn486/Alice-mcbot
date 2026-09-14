# Thermal 适配 · S1 设备事实表（阶段 3-B (a)，**只读**）

> **出处**：客户端 `mods/` 下的三个后端（**含一个内嵌 jar**，见 §0）
> `thermal_expansion-1.20.1-11.0.1.29.jar`（574141 字节）、`thermal_foundation-1.20.1-11.0.6.70.jar`（4549423 字节）、
> 共享库 `cofh_core-1.20.1-11.0.2.56.jar`，以及 **`thermal_foundation` 内嵌的 `META-INF/jarjar/thermal_core-1.20.1-11.0.6.24.jar`（4410354 字节）**。
> 运行时数字 = `/alice recipes` 导出（2026-09-14 17:15，见 `docs/THERMAL_FACTS.md` §6）。
> **方法**：`unzip` 解包后在 **①方块状态文件 ②方块掉落表 ③`Menu` 类名 ④JEI 类别类名 ⑤字节码常量池（`javap`）** 里取证。
> **口径**：**"有设备" = 有方块 id（`blockstates` ∩ `loot_tables/blocks` 两处都在）且有 `Menu` 类**，
> **不看"类型名像不像机器"**。

## 0. ⚠️ 先说方法学的坑：内嵌 jar（JiJ）会被 `mods/*.jar` 扫描整片漏掉

**本表第一版就是在这里错的**，留档以免后人重犯：

- `ls mods/*.jar` / 对 `mods/*.jar` 逐个 `unzip` **看不到内嵌 jar** —— 在 `unzip -l` 里它只是**一行** `META-INF/jarjar/xxx.jar`。
- `thermal_foundation` 内嵌 **`thermal_core-1.20.1-11.0.6.24.jar`**，而**11 个 device 方块、22 个 devie/fuel 配方类型、52 条配方**全在里面。
- 后果：第一版把 `rock_gen` / `tree_extractor` / `fisher_boost` / `potion_diffuser_boost` 判成"**1.20.1 没有这些设备**"——
  **错**。它们有设备、有 `BlockEntity`、有 `Menu`、有 `Screen`。（当时唯一的反面线索是 `device_*` 字符串，
  而它又确实出现在 `ThermalFlags.setFlag(...)` 里 ⇒ 两种解释都自洽，只靠外层 jar **无法判**。救回来的是 §0 这一步扫描。）
- **全客户端只有两个模组带内嵌 jar**（16 个 jar 全查过）：`create`（4 个，全是库：Flywheel/Registrate/Ponder/MixinExtras，
  `data/*/recipes` **各 0 条** ⇒ Create 既有数据不受影响）与 `thermal_foundation`（1 个，**是内容**）。
  **Mekanism 没有内嵌 jar ⇒ 既有 Mekanism S0/S1 结论不受影响。**
- **纪律**：以后任何"某模组没有 X"的结论，必须先 `unzip -l <jar> | grep "META-INF/jarjar/.*\.jar$"`。

## 1. 设备清单（三个后端，22 + 11 个机器类方块）

**`thermal_expansion`：22 个方块**（`blockstates` 与 `loot_tables/blocks` **两处逐项一致**）

| 组 | 数量 | 方块 id |
|---|---|---|
| 机器 | 15 | `machine_bottler` `machine_brewer` `machine_centrifuge` `machine_chiller` `machine_crafter` `machine_crucible` `machine_crystallizer` `machine_furnace` `machine_insolator` `machine_press` `machine_pulverizer` `machine_pyrolyzer` `machine_refinery` `machine_sawmill` `machine_smelter` |
| 发电机 | 7 | `dynamo_compression` `dynamo_disenchantment` `dynamo_gourmand` `dynamo_lapidary` `dynamo_magmatic` `dynamo_numismatic` `dynamo_stirling` |

**`thermal_core`（内嵌于 thermal_foundation）：11 个 device 方块**（同样 `blockstates` ∩ `loot_tables` 一致）

| 方块 id | 对应配方类型 | 备注 |
|---|---|---|
| `device_rock_gen` | `thermal:rock_gen` | 造石 |
| `device_tree_extractor` | `thermal:tree_extractor` + `tree_extractor_boost` | 树场 |
| `device_fisher` | `thermal:fisher_boost` | 自动钓鱼 |
| `device_potion_diffuser` | `thermal:potion_diffuser_boost` | 药水扩散 |
| `device_collector` | 无 | 收集 |
| `device_composter` | 无 | 堆肥 |
| `device_hive_extractor` | 无 | 蜂箱 |
| `device_nullifier` | 无 | 销毁 |
| `device_soil_infuser` | 无 | 土壤注魔 |
| `device_water_gen` | 无 | 造水 |
| `device_xp_condenser` | 无 | 经验 |

另有 **5 个带 `Menu` 的非机器方块**（energy_cell / fluid_cell / charge_bench / tinker_bench / satchel 物品）。

⇒ **`thermal:` 命名空间里"有 Menu 的机器类方块"共 15 + 7 + 11 = 33 个**（另 5 个非机器菜单）。

## 2. 30 个配方类型 × 设备（全分类，与运行时 652 条**逐项对齐**）

| 配方类型 | 条数 | 设备（方块 id） | 归类 |
|---|---|---|---|
| `thermal:press` | 227 | `machine_press` | 机器 |
| `thermal:pulverizer` | 81 | `machine_pulverizer` | 机器 |
| `thermal:smelter` | 70 | `machine_smelter` | 机器 |
| `thermal:insolator` | 63 | `machine_insolator` | 机器 |
| `thermal:centrifuge` | 59 | `machine_centrifuge` | 机器 |
| `thermal:bottler` | 23 | `machine_bottler` | 机器 |
| `thermal:numismatic_fuel` | 15 | `dynamo_numismatic` | **发电机（只读）** |
| `thermal:crucible` | 14 | `machine_crucible` | 机器 |
| `thermal:sawmill` | 12 | `machine_sawmill` | 机器 |
| `thermal:crystallizer` | 9 | `machine_crystallizer` | 机器 |
| `thermal:tree_extractor` | 8 | `device_tree_extractor` | device |
| `thermal:lapidary_fuel` | 8 | `dynamo_lapidary` | **发电机（只读）** |
| `thermal:chiller` | 7 | `machine_chiller` | 机器 |
| `thermal:smelter_catalyst` | 6 | `machine_smelter`（增幅子类型） | 机器修饰 |
| `thermal:gourmand_fuel` | 6 | `dynamo_gourmand` | **发电机（只读）** |
| `thermal:rock_gen` | 6 | `device_rock_gen` | device |
| `thermal:refinery` | 5 | `machine_refinery` | 机器 |
| `thermal:stirling_fuel` | 5 | `dynamo_stirling` | **发电机（只读）** |
| `thermal:smelter_recycle` | 4 | `machine_smelter`（增幅子类型） | 机器修饰 |
| `thermal:compression_fuel` | 3 | `dynamo_compression` | **发电机（只读）** |
| `thermal:tree_extractor_boost` | 3 | `device_tree_extractor` | device 增幅 |
| `thermal:insolator_catalyst` | 3 | `machine_insolator` | 机器修饰 |
| `thermal:fisher_boost` | 3 | `device_fisher` | device 增幅 |
| `thermal:pyrolyzer` | 3 | `machine_pyrolyzer` | 机器 |
| `thermal:pulverizer_catalyst` | 2 | `machine_pulverizer` | 机器修饰 |
| `thermal:pulverizer_recycle` | 2 | `machine_pulverizer` | 机器修饰 |
| `thermal:potion_diffuser_boost` | 2 | `device_potion_diffuser` | device 增幅 |
| `thermal:magmatic_fuel` | 1 | `dynamo_magmatic` | **发电机（只读）** |
| `thermal:furnace` | 1 | `machine_furnace` | 机器 |
| `thermal:disenchantment_fuel` | 1 | `dynamo_disenchantment` | **发电机（只读）** |
| **合计** | **652** ✓ | 33 个机器类方块 | 13 机器 + 5 修饰 + 7 发电机 + 5 device = **30** ✓ |

**两个没有同名配方类型的机器方块**：`machine_brewer`、`machine_crafter`（15 方块 ↔ 13 机器类型）。
⇒ "它俩吃什么配方"是 **S2 的题目**（JEI 侧确实有 `BrewerRecipeCategory`，且另有 `PotionFluidRecipeManagerPlugin`），本文不猜。

## 3. 红线①：7 台 dynamo = 发电机，永不进 `EXECUTABLE`

判据：**7 个 `Dynamo*Screen` / `Dynamo*BlockEntity` / `Dynamo*Menu` 与 7 个 `<x>_fuel` 配方类型 1:1 对应**
（`stirling↔stirling_fuel`、`magmatic↔magmatic_fuel`、`compression↔compression_fuel`、`numismatic↔numismatic_fuel`、
`lapidary↔lapidary_fuel`、`gourmand↔gourmand_fuel`、`disenchantment↔disenchantment_fuel`）。

⇒ 这 7 行只能是**只读**（或不建行）。让它们进 `EXECUTABLE` 就等于把红线①（生产不造能量）捅穿。

## 4. 为什么"不能按配方类型数建行"

按 30 个类型建 30 行会得到 **17 行错行**：

- **7 行是发电机** ⇒ 红线①（§3）；
- **5 行是寄居在别的机器菜单里的子类型**（`smelter_catalyst` / `smelter_recycle` / `insolator_catalyst` /
  `pulverizer_catalyst` / `pulverizer_recycle`）⇒ 站点与菜单都**借用** `machine_smelter` / `machine_insolator` / `machine_pulverizer`，
  单独建行会出现重复站点；
- **5 行是 device 增幅/device 自身**（`tree_extractor` 与 `tree_extractor_boost` 同一台机器；`fisher_boost` / `potion_diffuser_boost` 是增幅）。

⇒ 正确做法：**先按"设备（方块）"归并，再建行**（一行 = 一台机器 + 它认得的配方类型），与既有 `MachineMap` 形态一致。

## 5. `Menu` 类清单（33 个；**是候选，不是准入证据**）

- `thermal_expansion`·`common/inventory/machine/`（15）：
  `MachineBottlerMenu` `MachineBrewerMenu` `MachineCentrifugeMenu` `MachineChillerMenu` `MachineCrafterMenu`
  `MachineCrucibleMenu` `MachineCrystallizerMenu` `MachineFurnaceMenu` `MachineInsolatorMenu` `MachinePressMenu`
  `MachinePulverizerMenu` `MachinePyrolyzerMenu` `MachineRefineryMenu` `MachineSawmillMenu` `MachineSmelterMenu`
- `thermal_expansion`·`common/inventory/dynamo/`（7）：
  `DynamoCompressionMenu` `DynamoDisenchantmentMenu` `DynamoGourmandMenu` `DynamoLapidaryMenu`
  `DynamoMagmaticMenu` `DynamoNumismaticMenu` `DynamoStirlingMenu`
- `thermal_core`（11 device）：`DeviceCollectorMenu` `DeviceComposterMenu` `DeviceFisherMenu` `DeviceHiveExtractorMenu`
  `DeviceNullifierMenu` `DevicePotionDiffuserMenu` `DeviceRockGenMenu` `DeviceSoilInfuserMenu` `DeviceTreeExtractorMenu`
  `DeviceWaterGenMenu` `DeviceXpCondenserMenu`

⚠️ `MachineMap` 的 `EXECUTABLE` 准入条件② 要的是**实测 `menuClass`**（游戏内打开菜单后打印的真实类名；先例：Mekanism 两台机器
`menuClass` **相同**，只能靠槽位表区分）⇒ 上面是**静态类名候选**，实测在 S2/S3 做。

## 6. ✅ 收口：`+34` 之谜（台账⑩③）已**完全闭合**

**静态 670 条 = 运行时 652 条 + 18 条**，两个机制各占一边：

| 项 | 条数 | 机制 | 证据 |
|---|---|---|---|
| 外层两个 jar 的 `thermal:` 配方 | 618 | — | 逐类型扫描 |
| **内嵌 `thermal_core` jar** | **+52** | **JiJ（§0）** | 22 条 device/fuel 类型全在里面（`tree_extractor` 7、`numismatic_fuel` 7、`lapidary_fuel` 8、`rock_gen` 6、`gourmand_fuel` 6、`stirling_fuel` 5、`fisher_boost` 3、`tree_extractor_boost` 3、`compression_fuel` 3、`potion_diffuser_boost` 2、`disenchantment_fuel` 1、`magmatic_fuel` 1） |
| `smelter_recycle` 被条件丢弃 | **−18** | **`cofh_core:tag_exists` 配方条件** | 22 条**全部**带 `conditions:[{type:"cofh_core:tag_exists", tag:"forge:armor/<金属>" 或 "forge:tools/<金属>"}]`；运行时只有 **4 条**存活 |

**−18 的那个"4"是可交叉验证的**：22 个条件标签里，Alice 自己的导出 `itemTags`（693 个）里**只存在这 4 个** ——
`forge:armor/gold`、`forge:armor/iron`、`forge:tools/gold`、`forge:tools/iron`；其余 18 个（bronze/constantan/copper/electrum/
invar/lead/nickel/silver/tin 的 armor 与 tools）**在运行时不存在** ⇒ 条件不通过 ⇒ 配方不加载 ⇒ **正好 4 条**。
**两侧独立吻合，`670 − 18 = 652` 逐项闭合。**

> 附带纠正：`cofh_core:tag_exists` 说明 **"静态 jar 里有多少条配方" ≠ "运行时有多少条"**（条件会在加载期筛）。
> 以后引用静态计数时必须说明这一点，不能直接当运行时候选数。

## 7. 复算命令（原文，本机实跑过；只读）

```bash
M=/mnt/d/JAVA_projects/worldedit-test/versions/1.20.1-Forge_47.4.10/mods
cd /tmp && rm -rf tf_recon && mkdir tf_recon && cd tf_recon
for j in thermal_expansion-1.20.1-11.0.1.29.jar thermal_foundation-1.20.1-11.0.6.70.jar; do
  d=$(echo "$j" | sed 's/\.jar$//'); mkdir -p "$d"; unzip -o -q "$M/$j" -d "$d"
done

# ① 先查内嵌 jar（本表第一版就是漏了这一步才判错）
for j in "$M"/*.jar; do
  n=$(unzip -l "$j" | awk '{print $4}' | grep -cE "META-INF/jarjar/.*\.jar$")
  [ "$n" -gt 0 ] && { echo "$(basename $j):"; unzip -l "$j" | awk '{print $4}' | grep -E "META-INF/jarjar/.*\.jar$"; }
done
# => create（4 个，全是库，recipes=0）与 thermal_foundation（thermal_core，是内容）—— 全客户端只有这两个

# ② 解出内嵌 jar 再扫
unzip -o -q thermal_foundation-1.20.1-11.0.6.70.jar "META-INF/jarjar/thermal_core-1.20.1-11.0.6.24.jar" -d tc
unzip -o -q tc/META-INF/jarjar/thermal_core-1.20.1-11.0.6.24.jar -d tc_core

# ③ 设备方块：三处证据（expansion 22 / core 11），blockstates 与 loot_tables 应逐项一致
ls thermal_expansion-1.20.1-11.0.1.29/assets/thermal/blockstates/ | sed 's#\.json##' | sort
ls thermal_core/assets/thermal/blockstates/ | grep device_ | sed 's#\.json##' | sort

# ④ 逐类型对账（含内嵌 jar）：应得 静态 670 / 运行时 652，唯一差 = smelter_recycle −18
python3 - <<'PY'
import json,glob,collections
jars={'exp':'thermal_expansion-1.20.1-11.0.1.29','fnd':'thermal_foundation-1.20.1-11.0.6.70','core':'tc_core'}
per=collections.defaultdict(collections.Counter)
for k,d in jars.items():
    for p in glob.glob(d+'/data/thermal/recipes/**/*.json', recursive=True):
        j=json.load(open(p)) or {}
        t=(j.get('type') or '')
        if t.startswith('thermal:'): per[k][t.split(':',1)[1]]+=1
tot=collections.Counter()
for k in jars: tot.update(per[k])
run=json.load(open('/mnt/d/JAVA_projects/worldedit-test/versions/1.20.1-Forge_47.4.10/config/alice-recipes.json'))['skippedTypes']
run={k.split(':',1)[1]:v for k,v in run.items() if k.startswith('thermal:')}
print('静态', len(tot), sum(tot.values()), '| 运行时', len(run), sum(run.values()))
for t in sorted(set(tot)|set(run)):
    if tot[t]!=run.get(t,0): print(f'  差 {t}: 静态{tot[t]} 运行时{run.get(t,0)} ({run.get(t,0)-tot[t]:+d})')
PY

# ⑤ −18 的交叉验证：22 个条件标签里，运行时只有 4 个存在（用 Alice 自己的 itemTags）
python3 - <<'PY'
import json
tags=json.load(open('/mnt/d/JAVA_projects/worldedit-test/versions/1.20.1-Forge_47.4.10/config/alice-recipes.json'))['itemTags']
metals=['bronze','constantan','copper','electrum','gold','invar','iron','lead','nickel','silver','tin']
want=[f'forge:{k}/{m}' for k in ('armor','tools') for m in metals]
print('存在:', [t for t in want if t in tags or '#'+t in tags])
PY
```

## 8. 未做 / 不知道（不假装完成）

- **`machine_brewer` / `machine_crafter` 吃什么配方**：未查（S2 题目）。
- **`menuClass` 是静态类名、不是实测值**（§5 已标注）；真实容器类名 + 槽位表要在客户端 S2/S3 取。
- **槽位 / 进度 / 能量接口**：本文完全没有（S2 范围）。
- **device 类机器的"生产语义"**：`device_rock_gen` / `device_water_gen` / `device_collector` 这类**不吃物品输入就产出**，
  与 `MachineCycle`（喂料 → 等产出 → 取回）的模型不同 ⇒ 即使它们有站点，也**不能直接当成可执行的生产路线**。
  这是 (c) 后续的边界，本文只登记，不设计。
- **无上游 sources jar**（台账⑩①）⇒ 全部结论来自**字节码 + 资源**，没有读过源码。
