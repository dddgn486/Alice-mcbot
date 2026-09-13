# Mekanism 适配 · S0 类型事实表（阶段 3-B / D-202）

> **出处**：客户端 `/alice recipes` 导出的**运行时**配方表 `config/alice-recipes.json`（`generatedAtTick` 见文件头；阶段 2 / D-182 那次导出）。
> **口径**：与 `RecipeDump`/`RecipeQuery` 一致 —— **只统计原版可读类型**（可读 = 在 `recipes` 里），**其余如实计入 `skippedTypes`（"读不懂"）**；数字可用 `tools/recipe-readability.py` 复算。

- 运行时可读配方 **2923** 条；被跳过 **2370** 条（≈5293 条总量）。
- **Mekanism：26 个类型 / 1171 条**被跳过（占跳过量的 49.4%）。

## 1. 类型分布（降序）

| # | 类型 | 条数 |
|---|---|---|
| 1 | `mekanism:crushing` | 210 |
| 2 | `mekanism:pigment_extracting` | 178 |
| 3 | `mekanism:painting` | 176 |
| 4 | `mekanism:enriching` | 142 |
| 5 | `mekanism:sawing` | 124 |
| 6 | `mekanism:injecting` | 76 |
| 7 | `mekanism:combining` | 62 |
| 8 | `mekanism:purifying` | 28 |
| 9 | `mekanism:metallurgic_infusing` | 26 |
| 10 | `mekanism:dissolution` | 23 |
| 11 | `mekanism:nucleosynthesizing` | 20 |
| 12 | `mekanism:infusion_conversion` | 18 |
| 13 | `mekanism:rotary` | 17 |
| 14 | `mekanism:pigment_mixing` | 14 |
| 15 | `mekanism:reaction` | 14 |
| 16 | `mekanism:crystallizing` | 10 |
| 17 | `mekanism:washing` | 7 |
| 18 | `mekanism:oxidizing` | 6 |
| 19 | `mekanism:gas_conversion` | 5 |
| 20 | `mekanism:chemical_infusing` | 4 |
| 21 | `mekanism:centrifuging` | 2 |
| 22 | `mekanism:compressing` | 2 |
| 23 | `mekanism:energy_conversion` | 2 |
| 24 | `mekanism:evaporating` | 2 |
| 25 | `mekanism:separating` | 2 |
| 26 | `mekanism:activating` | 1 |

## 2. 本轮聚焦（覆盖主链即可吃下大部分）

按 D-183 的排序原则（**先覆盖条数最多的代表类型**）：
`mekanism:crushing`（210）、`mekanism:pigment_extracting`（178）、`mekanism:painting`（176）、`mekanism:enriching`（142）、`mekanism:sawing`（124） ⇒ 这 5 个类型共 **830** 条（占 Mekanism 跳过量的 71%）。

## 3. S0 结论（事实层）

- **"读不懂多少"是明确的**：Mekanism 的 26 个类型全部落在原版白名单之外（机器专属类型），
  这不是缺陷而是**边界如实标注**（D-182/D-183 的口径）；
- **S1 要产出的是"读法"**：把上述类型读成 `输入 → 输出 + 机器类型` 的事实（只读），
  并接进查询层让 `machine_recipe_unsupported` 升级为**有出处的机器路线**；
- **S1 还需要一次运行时取证**（本次导出只带 `skippedTypes` 的**计数**，不含这些类型的输入/输出样例）：
  用零参数探针在客户端把候选类型的**代表样例**（id/输入/输出）打出来，再决定认哪些机器（S2）。

## 4. 未做 / 不知道（不假装完成）

- 各类型的**输入/输出形态**（本次只有计数）——S1 探针补；
- 这些类型中**哪些是"机器专属"、哪些其实可由原版站点点出**（需逐类型看样例）；
- 配方之间是否存在**跨模组打架**（Mekanism `crushing` vs Thermal `pulverizer`）——阶段 2 已登记为开放项。

