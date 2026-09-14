# Thermal 适配 · S0 类型事实表（阶段 3-B）

> **出处**：客户端 `/alice recipes` 导出的**运行时**配方表 `config/alice-recipes.json`
> （文件头 `source="alice runtime RecipeManager"`、`generatedAtTick=165`、
> `note="只含原版配方体系；机器配方（JEI 类别）不在此，见 D-146 的能力边界"`；
> 文件 mtime **2026-09-13 13:23:45 +0800**、1151827 字节）。
> **这份导出是哪次会话的**：与 `docs/MEKANISM_FACTS.md` §5 里被称为"13:23 导出"的那份**同一次**
> （`可读 2923 / 跳过 2370`、76 个跳过类型逐项一致）⇒ 本文所有**总量只代表那一次会话**，不代表当前客户端（见 §4）。
> **口径**：与 `RecipeDump`/`RecipeQuery` 一致 —— **只统计原版可读类型**（可读 = 在 `recipes` 里），
> **其余如实计入 `skippedTypes`（"读不懂"）**；`skippedTypes` 只有**计数**，不含这些类型的输入/输出样例。
> **复算命令（原文，本机实际跑过）**：
> ```
> python3 tools/recipe-readability.py --recipes /mnt/d/JAVA_projects/worldedit-test/versions/1.20.1-Forge_47.4.10/config/alice-recipes.json
> python3 tools/recipe-readability.py --selftest      # SELFTEST PASS multiRoute=1 skipped={'mekanism:metallurgic_infusing': 12, 'create:mixing': 5}
> ```

- 运行时可读配方 **2923** 条；被跳过 **2370** 条 / **76** 个类型（≈5293 条总量，**某次会话的导出**）。
- **Thermal：30 个类型 / 652 条**被跳过（占本次跳过量的 **27.5%**，与 `docs/STAGE2_MODS_READABILITY.md` §4 候选清单第 2 行一致）。

## 1. 类型分布（降序，全部 30 个类型，不截断）

| # | 类型 | 条数 |
|---|---|---|
| 1 | `thermal:press` | 227 |
| 2 | `thermal:pulverizer` | 81 |
| 3 | `thermal:smelter` | 70 |
| 4 | `thermal:insolator` | 63 |
| 5 | `thermal:centrifuge` | 59 |
| 6 | `thermal:bottler` | 23 |
| 7 | `thermal:numismatic_fuel` | 15 |
| 8 | `thermal:crucible` | 14 |
| 9 | `thermal:sawmill` | 12 |
| 10 | `thermal:crystallizer` | 9 |
| 11 | `thermal:lapidary_fuel` | 8 |
| 12 | `thermal:tree_extractor` | 8 |
| 13 | `thermal:chiller` | 7 |
| 14 | `thermal:gourmand_fuel` | 6 |
| 15 | `thermal:rock_gen` | 6 |
| 16 | `thermal:smelter_catalyst` | 6 |
| 17 | `thermal:refinery` | 5 |
| 18 | `thermal:stirling_fuel` | 5 |
| 19 | `thermal:smelter_recycle` | 4 |
| 20 | `thermal:compression_fuel` | 3 |
| 21 | `thermal:fisher_boost` | 3 |
| 22 | `thermal:insolator_catalyst` | 3 |
| 23 | `thermal:pyrolyzer` | 3 |
| 24 | `thermal:tree_extractor_boost` | 3 |
| 25 | `thermal:potion_diffuser_boost` | 2 |
| 26 | `thermal:pulverizer_catalyst` | 2 |
| 27 | `thermal:pulverizer_recycle` | 2 |
| 28 | `thermal:disenchantment_fuel` | 1 |
| 29 | `thermal:furnace` | 1 |
| 30 | `thermal:magmatic_fuel` | 1 |

复算命令（原文）：
```
python3 -c "import json;d=json.load(open('/mnt/d/JAVA_projects/worldedit-test/versions/1.20.1-Forge_47.4.10/config/alice-recipes.json'));t={k:v for k,v in d['skippedTypes'].items() if k.startswith('thermal:')};print('thermal_types=%d thermal_total=%d'%(len(t),sum(t.values())));[print('%4d  %s'%(v,k)) for k,v in sorted(t.items(),key=lambda e:-e[1])]"
# => thermal_types=30 thermal_total=652
```

## 2. 本轮聚焦（覆盖主链即可吃下大部分）

按 D-183 的排序原则（**先覆盖条数最多的代表类型**）：
`thermal:press`（227）、`thermal:pulverizer`（81）、`thermal:smelter`（70）、`thermal:insolator`（63）、`thermal:centrifuge`（59）
⇒ 这 5 个类型共 **500** 条（占 Thermal 跳过量的 **76.7%**）。
再加 `bottler` 23 / `crucible` 14 / `sawmill` 12 ⇒ **8 个类型 549 条（84.2%）**。

**挑选依据 = 条数占比**（不涉及"能不能执行"的判断）：这 5 个类型与 `docs/STAGE2_MODS_READABILITY.md` §4
给的代表类型完全一致；剩下 25 个类型合计仅 152 条（23.3%），且多为燃料/催化剂/增幅（按**类型名**观察，
其语义未在本数据中验证，见 §4）。

## 3. S0 结论（事实层）

- **"读不懂多少"是明确的**：Thermal 的 30 个类型**全部**落在原版白名单之外（机器专属类型），
  这不是缺陷而是**边界如实标注**（D-182/D-183 的口径）；**"读不懂" ≠ "做不到"**（协议 §4 反模式）。
- **覆盖率高度集中**：前 5 个类型 = 76.7%，前 8 个 = 84.2%（数字见 §2，可由 §1 表逐行相加复算）。
- **可交叉验证 ✓**：静态 jar 里 `data/thermal/recipes/**.json` 的类型直方图与本次运行时**逐类型吻合**
  （`press` 227 / `pulverizer` 81 / `smelter` 70 / `insolator` 63 / `centrifuge` 59，五类逐条相等）——证据与命令见 §5。
- **形态分布（抽样自静态 jar，非运行时）**：前 5 类型 500 条配方的 JSON 形态 = `type` + `ingredient`/`ingredients` + `result`，
  可选 `energy` / `energy_mod` / `experience` / `water_mod`；输入只见 `item` / `tag`（`input_fluid=0`，无流体输入）；
  产出 643 个 `item` + **8 个 `fluid`**（`cofh_core:experience` / `cofh_core:honey` / `thermal:crude_oil` /
  `thermal:syrup` / `thermal:latex`），其中 227 个产出条目带 `chance`、14 个带 `locked:true`；
  每条配方产出条目数：1 项 379 / 2 项 93 / 3 项 26 / 4 项 2（合计 500 ✓，命令见 §5）。
  ⇒ 与 Mekanism 的"化学品"不同，Thermal 机器配方的输入/输出主要是**物品/标签**，只有 8 项涉及**流体**（超出原版配方语义）。
- **S1 要产出的是"读法"**：把上述类型读成 `输入 → 输出 + 机器类型` 的事实（只读），
  并接进查询层让 `machine_recipe_unsupported` 升级为**有出处的机器路线**。
- **S1 还需要一次运行时取证**：本次导出只带 `skippedTypes` 的**计数**，**不含**这些类型的输入/输出样例；
  §3/§5 的形态来自**静态 jar 的离线抽样**，与运行时 `RecipeManager` 的口径不同（条件/覆写在运行时生效）。

## 4. 未做 / 不知道（不假装完成）

**这份数据回答不了的问题**：
- 各类型**逐条的输入/输出样例**（`skippedTypes` 只有计数；§5 的抽样是静态 jar，不是运行时）；
- 机器方块的**站点/菜单/槽位/进度数据**（S2 的范围，本数据完全没有）；
- 这些类型中**哪些是"机器专属"、哪些其实可由原版站点点出**（需逐类型看样例后比输入输出）；
- **跨模组打架**：Thermal `pulverizer` vs Mekanism `crushing` 这类**机器类型之间**的产出冲突
  （阶段 2 已登记为开放项）—— 本导出看不到（机器类型没有产出字段）；
- **执行可行性**（协议要求"执行永远排在只读之后"；本步只回答"读不懂多少 + 形态分布"）。

**发现的数据不一致 / 需要注意的地方**：
- **（✅ 第十三轮已重导，见 §6）这份导出对当前客户端已经过时**：`alice-recipes.json` mtime 为 2026-09-13 13:23，
  而 `mods/` 里在此**之后**还装了 `refinedstorage-1.12.4.jar`（16:30）、
  `sophisticatedcore-1.20.1-1.5.1.2335.jar` / `sophisticatedstorage-1.20.1-1.4.86.2131.jar`（16:35）、
  `sophisticatedbackpacks-1.20.1-3.26.3.2157.jar`（16:50）⇒ **这些模组的配方不在本导出里**；
  Thermal/Mekanism/Create/ExtendedCrafting 的数字仍与候选清单一致（652/30 逐项吻合），故本文有效。
- **静态 vs 运行时差 34 条，来源未验证**（见 §5 的差值分解）。
- **总量会变**（先例实测：同一客户端两次会话的导出 `2923/2370` vs `3714/2354`，世界/数据包在那之后有变化
  —— `docs/MEKANISM_FACTS.md` §5）⇒
  本文**总量一律标注"某次会话的导出"**，只有"类型×条数"这种**结构事实**才可长期引用。
- **复算工具的口径缺口**：`tools/recipe-readability.py` 的 docstring 宣传
  `--recipes <...> --target <item> --routes` 模式，但 `main()` 只注册了 `--recipes/--selftest/--top`
  ⇒ 实测 `error: unrecognized arguments: --target minecraft:iron_ingot --routes`
  （按产出物查路线目前只能自己写 python，或走 §5 的等价写法）。

**顺手查的两件事实（1~3 行结论）**：
- **客户端 Thermal 文件名与版本**（`/mnt/d/JAVA_projects/worldedit-test/versions/1.20.1-Forge_47.4.10/mods/`，
  `ls -la` 实测）：`thermal_expansion-1.20.1-11.0.1.29.jar`（574141 字节，9月13 13:14）+ 前置
  `thermal_foundation-1.20.1-11.0.6.70.jar`（4549423 字节，9月13 13:14）；另有前置
  `cofh_core-1.20.1-11.0.2.56.jar`。**没有任何 jar 的名字就叫 `thermal*.jar`**；
  而 `thermal:` 这个**配方命名空间由两个 jar 共同提供**（`unzip -l`：`data/thermal/recipes/` 条目
  expansion 517 / foundation 285；`cofh_core` jar 里**没有** `data/thermal/recipes/`）。
- **上游源码来源**：**没有**。`mods/` 里没有 `-sources.jar`，在 `/mnt/d/JAVA_projects`（深度 4）与
  `$HOME`（深度 6）下也搜不到反编译产物或反编译缓存
  （`find … -iname "*thermal*source*"` 零命中；gradle 缓存里只有 `cofh_core` 的**编译产物**
  `cofh_core-1.20.1-11.0.2.56.jar`，无 `-sources`）
  ⇒ **S1 需要另行获取（或就地反编译这两个 jar）**。jar 内可见的线索只有 JEI 侧类别类
  （`cofh/thermal/expansion/compat/jei/machine/*RecipeCategory.class`，16 个含 `recipe` 的 class 名），
  即"上游自述"要从**字节码/反编译**里找，先例（Mekanism S1）也是这么做的（`unzip -l` + 反编译）。

## 5. 补充核对（静态 jar × 运行时导出，只读；本节数字全部为本次实跑）

> 目的：给 §1 的运行时数字做一次**独立路径的交叉核对**。口径不同，**不能互相替代**：
> 静态 = jar 里的 datapack JSON 文件数（不含运行时条件/覆写）；运行时 = 本次 `/alice recipes` 导出。

```
python3 -c "
import zipfile,json,collections,glob
M='/mnt/d/JAVA_projects/worldedit-test/versions/1.20.1-Forge_47.4.10/mods'
h=collections.Counter();n=0
for j in glob.glob(M+'/thermal_*.jar'):
  with zipfile.ZipFile(j) as z:
    for p in z.namelist():
      if p.startswith('data/thermal/recipes/') and p.endswith('.json'):
        n+=1; h[json.loads(z.read(p))['type']]+=1
print('static_files=%d static_total=%d'%(n,sum(h.values())))
[print('%4d  %s'%(v,k)) for k,v in sorted(h.items(),key=lambda e:-e[1])]"
# => static_files=768 static_total=768
#    （完整逐类型输出 24 行，此处只列汇总）
#    thermal 命名空间类型 618 条（768 − 150 条原版类型：crafting_shaped 71 / crafting_shapeless 31 / blasting 24 / smelting 24）
#    运行时 thermal 被跳过 652 条 ⇒ 净差 +34
```

- **吻合**：20 个静态类型中，**17 个逐类型完全相等**（合计 587 条），含 §2 的前 5 类
  → S0 数字**可复算、可交叉验证** ✓；
- **差值的完整分解**（算术闭合，可由两份直方图复算）：
  `618 + 7（numismatic_fuel 8→15）+ 7（tree_extractor 1→8）+ 38（10 个"只在运行时出现"的类型）− 18（smelter_recycle 22→4） = 652`；
  只在运行时出现的 10 个类型：`rock_gen` 6、`lapidary_fuel` 8、`gourmand_fuel` 6、`stirling_fuel` 5、
  `compression_fuel` 3、`fisher_boost` 3、`tree_extractor_boost` 3、`potion_diffuser_boost` 2、
  `disenchantment_fuel` 1、`magmatic_fuel` 1；
- **差值来源未验证**（不猜）：可能是代码注册/条件禁用/覆写，但**本次没有做取证**，留作 S1 的开放项。

形态抽样的复算命令（原文）：
```
python3 -c "
import zipfile,json,glob,collections
M='/mnt/d/JAVA_projects/worldedit-test/versions/1.20.1-Forge_47.4.10/mods'
T={'thermal:press','thermal:pulverizer','thermal:smelter','thermal:insolator','thermal:centrifuge'}
n=collections.Counter();k=collections.Counter()
for j in glob.glob(M+'/thermal_*.jar'):
  with zipfile.ZipFile(j) as z:
    for p in z.namelist():
      if p.startswith('data/thermal/recipes/') and p.endswith('.json'):
        d=json.loads(z.read(p))
        if d.get('type') not in T: continue
        r=d.get('result'); e=r if isinstance(r,list) else [r]; n['results=%d'%len(e)]+=1
        s=json.dumps(e); i=json.dumps(d.get('ingredient') or d.get('ingredients'))
        k['result_item']+=s.count('\"item\"'); k['result_fluid']+=s.count('\"fluid\"'); k['result_chance']+=s.count('\"chance\"'); k['result_locked']+=s.count('\"locked\"')
        k['input_tag']+=i.count('\"tag\"'); k['input_fluid']+=i.count('\"fluid\"')
print('top5 recipes=%d'%sum(n.values()), dict(sorted(n.items())), dict(k))"
# => top5 recipes=500 {'results=1': 379, 'results=2': 93, 'results=3': 26, 'results=4': 2}
#    {'result_item': 643, 'result_fluid': 8, 'result_chance': 227, 'result_locked': 14, 'input_tag': 278, 'input_fluid': 0}
```
⇒ 静态前 5 类型合计 **500** 条，与运行时前 5 类型合计（227+81+70+63+59=500）**相等** ✓。

## 6. 第十三轮就地重导后的复核（2026-09-14 17:15，只读）

**新导出**（同一客户端，游戏内 `/alice recipes`）：
`recipes=3689 skipped=2379 tags=693 skippedTop=thermal:press=227,mekanism:crushing=210,mekanism:pigment_extracting=178`
⇒ `config/alice-recipes.json` **1527420 字节**、mtime **2026-09-14 17:15**、`source=alice runtime RecipeManager`
⇒ **§4 的"已过时"缺口关闭：这份表现在就是"当前客户端真实分布"**（含后装的 refinedstorage / sophisticated\*）。

| 项 | 13:23 旧导出 | 17:15 新导出 | 备注 |
|---|---|---|---|
| 可读配方 | 2923 | **3689** | +766 |
| 跳过配方 | 2370 | **2379** | +9 |
| 跳过类型 | 76 | **76** | 类别数没变 |
| itemTags | — | **693** | |
| **Thermal** | 652 / **30 类型** | **652 / 30 类型** | **逐项一致**（press 227 / pulverizer 81 / smelter 70 / insolator 63 / centrifuge 59）|

⇒ 结论：**"总量"会随装模组变，"类型×条数"这类结构事实稳定**（两次导出逐项吻合）⇒ **S0 的类型结论不因重导而变**。
另外复核了一条覆盖性事实：新装的 refinedstorage / sophisticated\* 在 `skippedTypes` 里**一条都没有**
（它们的配方全落在原版可读类型内）⇒ 可读量的增量来自它们，跳过量只 +9。

**新发现（直接约束 S1 的枚举粒度）**：Thermal 的 **30 个类型 ≠ 30 台机器**。
按新导出 `skippedTypes` 逐条归类，**机器配方类型约 15 个**：
press 227 / pulverizer 81 / smelter 70 / insolator 63 / centrifuge 59 / bottler 23 / crucible 14 /
sawmill 12 / crystallizer 9 / tree_extractor 8 / chiller 7 / rock_gen 6 / refinery 5 / pyrolyzer 3 / furnace 1；
另一半是**燃料 / 催化 / 增幅类修饰类型**（没有对应方块与菜单）：
`numismatic_fuel` 15、`lapidary_fuel` 8、`gourmand_fuel` 6、`smelter_catalyst` 6、`stirling_fuel` 5、
`compression_fuel` 3、`insolator_catalyst` 3、`tree_extractor_boost` 3、`fisher_boost` 3、
`smelter_recycle` 4、`pulverizer_catalyst` 2、`pulverizer_recycle` 2、`potion_diffuser_boost` 2、
`magmatic_fuel` 1、`disenchantment_fuel` 1。
⇒ **`MachineMap` 不能按"类型数"建行**：S1 必须按 jar 里的 `Block`/`TileEntity`/容器类**逐条核实**哪些类型真的有站点。
（"约 15"目前是**按类型名与条数推断的候选**，**尚未对字节码取证** —— 不要当结论用。）

**一处对账闭合（不是异常，是口径不同）**：同轮 `[MachineProbe] SUMMARY … readable_total=3714 skipped_total=2354`（`latest.log:3087`）
与本次导出 `3689 / 2379` 各差 **25**；而新导出的 `skippedTypes` 里正有 **`minecraft:crafting(空产出) = 25`**
⇒ `3689 + 25 = 3714`、`2379 − 25 = 2354` **两侧精确闭合**：探测把"空产出的合成配方"记为可读，导出记为跳过。
解释是"同一个 6068 条配方集合、两处口径不同"，**无需改动任何一侧**。
