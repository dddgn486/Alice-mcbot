# 阶段 2 · 模组浅测报告（2026-09-13）

> 口径（D-147/D-148）：只验 **"运行时导出 + 读得懂多少 + 有没有配方打架"**，
> **不写任何适配器**（未知模组能力默认只读）。
> 数据源：客户端 `/alice recipes` 导出 `config/alice-recipes.json`（运行时 `RecipeManager`，权威）。
> 分析工具：`tools/recipe-readability.py`（只读离线，`--selftest` PASS）。

## 0. 安装集（D-182，AI 代装，sha1 全 OK）

| modId | 版本 | 角色 |
|---|---|---|
| create | 6.0.8 | 目标（jiJ 内嵌 flywheel 1.0.5 / Ponder 1.0.91，`mandatory=true`） |
| extendedcrafting | 6.0.10 | 目标（需 cucumber） |
| cucumber | 7.0.16 | 前置 |
| mekanism | 10.4.16.80 | 目标（只有 optional 依赖） |
| thermal_expansion | 11.0.1.29 | 目标 |
| thermal_foundation | 11.0.6.70 | 前置（jiJ 内嵌 thermal_core 11.0.6.24） |
| cofh_core | 11.0.2.56 | 前置 |

客户端实际 jar 12 个（alice + JEI + Ore Excavation + 拼音搜索 + WorldEdit + 上述 7），**无重复 modId**
⇒ "读不懂"可**干净归因**。启动正常（Create 6.0.8 initializing 已见于日志）。

## 1. 读得懂多少（**修复后**，最终导出 2923/5293 = 55.2%）

```
运行时配方总量 ≈ 5293     可读 2923（55.2%）     被跳过 2370（44.8%）
可读类型 7 种：crafting 2120 / stonecutting 514 / smelting 150 / blasting 88 / smithing 31 /
              campfire_cooking 10 / smoking 10
输入形态：直接物品 10738 / 标签(any) 262（修复前只有 5 —— 工作台配方大量引用标签）
物品标签 672（其中**跨模组成员**的 101）
仍被跳过的原版类型只剩 `minecraft:crafting(空产出) 16`（烟花/地图/旗帜这类**无具体产出物品**的特殊配方）
⇒ 原版体系**已完整覆盖**，剩下的 2370 条全部是模组机器配方。
```

产出命名空间（可读）：`minecraft 1253 / create 940 / thermal 333 / mekanism 318 / extendedcrafting 79`
⇒ 四个模组**用原版类型注册的配方**（工作台/熔炉/高炉/切石）已经全部进可读集。

## 2. ✅ 修复记录：白名单键错（D-183）

首轮导出暴露："读不懂第一名"竟是 `minecraft:crafting` 2136 条。根因是
`RecipeDump.STATION_BY_TYPE` 的键用了**序列化器 id**（`crafting_shaped`/`crafting_shapeless`/
`smithing_transform`/`smithing_trim`），而运行时 `recipe.getType()` 给的是**类型 id**
（`minecraft:crafting`/`minecraft:smithing`）⇒ 命不中。**已改用类型 id**（序列化器 id 保留兼容）。
实测：**772 → 2923**（预测 2939，差值 = 那 16 条无产出特殊配方）。

## 3. 配方打架（**最终**，这是 D-148 的核心交付）

```
同一产出物 ≥2 条路线：**575 个产出物**
其中**跨模组**（路线由 ≥2 个模组注册）：**48 个产出物**
```

典型（按路线数）：

| 产出 | 路线数 | 注册方 | 说明 |
|---|---|---|---|
| `minecraft:copper_ingot` | 11 | minecraft / create / mekanism / thermal | 原矿、粉、碎矿各自都能出锭 |
| `minecraft:iron_ingot` | 10 | minecraft / create / mekanism / thermal | 同上（`create:crushed_raw_iron` / `thermal:iron_dust` …） |
| `minecraft:gold_ingot` | 10 | 同上 | |
| `minecraft:netherite_ingot` | 7 | minecraft / mekanism / thermal | |
| `thermal:nickel_ingot` / `mekanism:ingot_lead` | 6 | create + 本体模组 | 跨模组同产出 |

**更深一层的"打架"（新发现，有数据证据）**：同一种金属在不同模组里是**不同 item id**，
靠 `#forge:*` 标签归一 —— 实测：

```
#forge:ingots/tin          = [mekanism:ingot_tin, thermal:tin_ingot]
#forge:ingots/lead         = [mekanism:ingot_lead, thermal:lead_ingot]
#forge:dusts/iron          = [mekanism:dust_iron, thermal:iron_dust]
#forge:raw_materials/tin   = [mekanism:raw_tin, thermal:raw_tin]
标签共 672，其中**跨模组成员的 101**，涉及 **1029 个物品**
```
⇒ **知识层必须"按标签归一"** 才能回答"我要一个锡锭"（否则同一种材料会被当成两种），
这正是 P1（`recipe-graph.py`）已经内建的"标签按已知成员展开"（报告里写明"任意其一"）。

**诚实边界**：机器**专属**类型（Mekanism `crushing` vs Thermal `pulverizer` 等同台竞争）仍在跳过集里，
所以"机器 vs 机器"的冲突**当前看不到**；上面 48 条跨模组冲突全部来自"**模组用原版类型注册**"的那部分
（已经足够说明问题，也是 D-148 想要的结论）。

## 4. 适配器候选清单（**只列清单，不写代码**）

| 优先级 | 模组 | 被跳过 | 类型数 | 代表类型（覆盖主链即可吃下大部分） |
|---|---|---|---|---|
| 1 | **Mekanism** | 1171 | 26 | `crushing` 210 / `enriching` 142 / `injecting` 76 / `combining` 62 / `purifying` 28 |
| 2 | **Thermal** | 652 | 30 | `press` 227 / `pulverizer` 81 / `smelter` 70 / `insolator` 63 / `centrifuge` 59 |
| 3 | **Create** | 506 | 15 | `cutting` 156 / `deploying` 112 / `crushing` 77 / `milling` 47 / `splashing` 41 |
| 4 | Extended Crafting | 25 | 4 | `compressor` 19 等（量小，最后做） |

这些不是"配方读不懂"，而是**机器加工语义不在原版配方体系里** ⇒ 适配前置 = 能力声明 + 授权 + 预算
（对齐 `CapabilityGate`/`WorldModLedger`），**本轮不写任何适配器**。

## 5. 结论

1. **原版配方体系已完整可读**（工作台/熔炉/高炉/烟熏/营火/切石/锻造），可读率 **55.2%**；
   剩余 44.8% 全是模组机器配方 —— 这就是"整合包知识"的边界线，清楚且可量化。
2. **配方打架结论成立**：575 个多路线产出、48 个跨模组；且**同名材料多 id + 标签归一**是必须处理的一层。
3. **阶段 2 的目标已达成**（读得懂多少 = 可测；有无打架 = 有清单），且**没写任何适配器**。
