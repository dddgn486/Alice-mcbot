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

## 1. 读得懂多少（修复前，本次导出）

```
运行时配方总量 ≈ 5293     可读 772（14.6%）     被跳过 4521（85.4%）
可读类型只有 5 种：stonecutting 514 / smelting 150 / blasting 88 / campfire 10 / smoking 10
输入形态：直接物品 767 / 标签(any) 5 / unknown 0       物品标签 672
```

**被跳过类型第一名不是模组，而是原版工作台**：

| 类型 | 条数 | 归属 |
|---|---|---|
| `minecraft:crafting` | **2136** | 原版（工作台） |
| `minecraft:smithing` | 31 | 原版（锻造台） |
| `mekanism:*`（26 种） | 1171 | Mekanism |
| `thermal:*`（30 种） | 652 | Thermal |
| `create:*`（15 种） | 506 | Create |
| `extendedcrafting:*`（4 种） | 25 | Extended Crafting |

## 2. 🔴 本轮抓到的**真问题**：白名单用了**序列化器 id** 而不是**类型 id**（D-183）

`RecipeDump.STATION_BY_TYPE` 原先的键是 `minecraft:crafting_shaped` / `crafting_shapeless` /
`smithing_transform` / `smithing_trim` —— 那些是**配方序列化器（serializer）** 的 id；
而运行时 `recipe.getType()` 给的是**类型** id：`minecraft:crafting` / `minecraft:smithing`
⇒ **命中不了白名单**，2136 + 31 条原版配方被"如实跳过"。
现场证据就是这个直方图本身（"读不懂第一名 = 原版工作台"显然不合理）。

**修法**：白名单改用**类型 id**（`minecraft:crafting` / `minecraft:smithing`），
并保留序列化器 id 作为兼容入口（不同版本/数据包可能以序列化器 id 注册）。
**修复后可读率预测**：772 → **2939（55.5%）**（2136+31 直接进可读集）。

> 这正是阶段 2 那条判据（"读得懂多少"）第一次真正抓到东西 —— 而且它抓到的**不是模组适配器问题，
> 是原版配方体系的读取缺口**（符合"基层优先"的原则）。

## 3. 配方打架（当前导出，受 §2 缺口限制）

```
同一产出物 ≥2 条不同路线：48 个产出物
   （全部是"熏制/营火/熔炉"三件套：cooked_beef / baked_potato / cooked_cod … 各 3 条）
跨模组同产出（>1 个命名空间都能造）：0 个   ← **不可信**：模组的 crafting 配方还没进可读集
```
⇒ 结论：**冲突检测必须等 §2 修好后再跑**。预期修复后会大量出现跨模组同产出
（典型：铁/铜/锡的"锭 ↔ 粉 ↔ 矿"链，Mekanism `crushing/enriching` vs Thermal `pulverizer/smelter`
vs 原版熔炉），那才是 D-148 想看的"配方打架"。

## 4. 适配器候选清单（**只列清单，不写代码**）

按"类型数 × 配方量"排序（模组侧）：

| 优先级 | 模组 | 被跳过条数 | 类型数 | 代表类型（覆盖其主链即可吃下大部分） |
|---|---|---|---|---|
| 1 | **Mekanism** | 1171 | 26 | `crushing` 210 / `enriching` 142 / `injecting` 76 / `purifying` 28 / `combining` 62 |
| 2 | **Thermal** | 652 | 30 | `press` 227 / `pulverizer` 81 / `smelter` 70 / `insolator` 63 / `centrifuge` 59 |
| 3 | **Create** | 506 | 15 | `cutting` 156 / `deploying` 112 / `crushing` 77 / `milling` 47 / `splashing` 41 |
| 4 | Extended Crafting | 25 | 4 | `compressor` 19 等（量小，最后做） |

**注意**：这些都不是"配方数据读不懂"，而是"**机器加工语义**不在原版配方体系里"——
按项目原则（未知能力默认只读、不猜槽位/配方/写入语义），适配它们前需要各自的
**能力声明 + 授权 + 预算**（对齐 `CapabilityGate`/`WorldModLedger` 的既有形态）。

## 5. 待用户一步 → 复测

修好 §2 后需要**重新导出一次**：重启客户端 → `/alice recipes` → 我重跑审计，出**最终版报告**
（预期：可读 2939/5293、跨模组同产出清单首次非空、`tag(any)` 输入大幅增加——工作台配方大量用标签）。
