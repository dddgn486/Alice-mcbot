# 阶段 3-A · 原版合成/熔炼接进任务层 —— 最小实施计划（2026-09-13）

> 依据 `minimal-implementation-planning`：跨层功能拆成**小而完整、可观察、可回滚**的增量，
> 每个增量只证明一件关键事情；接入正式任务前先有**独立测试入口**。
> 前置结论来自阶段 2（D-183）：**原版配方体系已 100% 可读**（工作台/熔炉/高炉/烟熏/营火/切石/锻造），
> 所以本轮只做**原版**；机器适配器按 D-183 排序**另立项**。

## 0. 五行任务卡（A1，本轮唯一要做的增量）

```text
目标：实现"按物品 id + 数量，在运行时配方表里找出可做的原版合成路线"的**只读查询原语**
      （不摆料、不点菜单、不动世界、不改背包），并用零参数入口把它打印出来。
不改变：任务层/寻路/执行器/决策层词汇；不新增世界写入授权；不碰机器配方（跳过集仍是跳过集）。
最小闭环：输入(itemId,count) -> 查 RecipeManager 找路线 + 展开 ingredient 候选 -> 输出
          "工作站 / 每条材料 / 已有/缺少 / 可选路线数 / 拒绝原因"
成功条件：右键一次 `alice:craft_check` 后聊天+日志出现机器可判读的 `SUMMARY key=VALUE`：
          正例（有料可做）给出工作站与材料清单；负例（缺料/无配方）如实给出 `missing=`/`no_recipe`。
验证方式：compileJava + `alice:craft_check`（电池内一步 + 独立物品）+ Windows 客户端看聊天/日志。
```

## 1. 为什么先做 A1（而不是直接合成）

- 合成是**多步动作链**（摆料 → 取产物），任何"先写动作再补查询"的做法都会变成边写边猜；
- 查询原语本身就能**给出可执行性事实**（够不够料、要不要 3×3、有没有多路线），
  这正是决策层"只能从事实里选"所需要的输入（对齐决策层铁律：事实由确定性代码产出）；
- 它是**纯只读**，零风险、可独立验收，且与阶段 2 的导出/审计形成闭环（同一份运行时数据）。

## 2. 增量阶梯（每步一个独立验收点）

| # | 增量 | 触及 | 独立测试入口 | 关键断言 |
|---|---|---|---|---|
| **A1 ✅已实施（待客户端）** | **配方查询原语**：`itemId × count` → 可行路线（工作站/材料/候选/缺料） | 新增 1 个只读类 + 1 个夹具任务 + 1 个物品 | `alice:craft_check`（正例 + 负例） | 正例给出路线；缺料给 `missing=`；无配方给 `no_recipe`；**不改任何状态** |
| A2 | **随身 2×2 合成**（单一原语）：用玩家自带 `InventoryMenu` 的 2×2 网格 + 结果槽，`MenuSession.click` 摆料并取走 | `action/` 新增合成执行器（复用 `MenuSession`） | 同上夹具第二段 | 消耗正确、产物入包、**绝不凭空给物品**；材料不足如实失败 |
| A3 | **工作台 3×3**：优先用**附近现成**工作台（不写世界）；没有才放置（需 `WriteReason`+预算+授权），且**用完即拆**（建拆同权闭环） | `action/` + 一处授权登记（A 表） | 场景函数造一个工作台 + 夹具 | 现成工作台路径**零写入**；放置路径账本 `remaining=0` |
| A4 | **熔炉**（时间/燃料语义）：插料、加燃料、等待、取出 | 同上 + 超时/清理 | 场景函数造熔炉 + 夹具 | 燃料选择有据、超时如实失败、**失败不留半成品** |
| A5 | **决策层接线**：`GoalAction.Craft`（词汇表 + 严格解析 + 候选菜单给出"可做的合成"） | `decision/` 3 处 + 文档 | 电池 + `/alice ask` 观察 | LLM 只能从菜单选；越界/缺料被 `Refused` 并回读 |

**不在本轮**：机器适配器（D-183 排序：Mekanism 1171·26 类 > Thermal 652·30 类 > Create 506·15 类）；
跨模组材料归一（标签展开是查询层既有能力，A1 里**只用**原版配方体系，标签按已知成员展开照旧）。

## 3. 边界（不许悄悄动）

- **D-076**：寻路默认纯通行；A3 若放置工作台 = 世界写入 ⇒ 必须**显式授权 + 预算**，并进 A 表登记；
- **不许凭空给物品**（`no_tool_no_conjure` 的同族原则）：合成只能**真消耗真产物**，失败不许"补一个"；
- **LLM 只选目标**：A1–A4 都是确定性原语，A5 才让它进入词汇表，且只给它菜单里的事实；
- **未知/机器配方默认只读**：查询命中跳过类型时如实报 `machine_recipe_unsupported`，绝不猜。

## 4. 预计修改模块（A1）

```text
新增  task/craft/RecipeQuery.java          （只读查询：路线/材料/候选/缺料）
新增  task/CraftCheckTask.java             （夹具：正例 + 负例 + SUMMARY，一步右键跑完）
新增  item/CraftCheckItem + 模型 + lang    （零参数入口）
修改  AliceItems / 语言文件 / docs/TESTING_GUIDE.md / 电池（加一步 craft_check）
```

## 5. 需要的证据与验证等级

| 证据 | 等级 |
|---|---|
| `compileJava` / `build` 通过 | `COMPILES` |
| 夹具 `SUMMARY` 正例与负例都符合判据（服务端日志） | `SERVER_TESTED` |
| 聊天栏/日志在固定客户端可读、物品可 `/give` | `WINDOWS_CLIENT` |
| 用户认可判据与后续方向 | `USER_ACCEPTED` |

## 6. 通过后的下一步

- A1 绿 ⇒ 进 **A2（随身 2×2 合成）**；A2 绿 ⇒ 再讨论 A3/A4 的优先级（工作台放置引入世界写入，
  需要先把授权登记与"用完即拆"的账本闭环在夹具里验一遍）。
- 与 **B（P0 假人冻结）** 的关系：不冲突；B 仍等复现，`connTicks=` 探针已就位。

---

## 附：A1 实施记录（2026-09-13）

```
新增  task/craft/RecipeQuery.java     只读查询（Verdict: CRAFTABLE / NEEDS_TABLE /
                                      MISSING_INGREDIENTS / NO_RECIPE / MACHINE_RECIPE_UNSUPPORTED）
新增  task/CraftCheckTask.java        夹具：正例/缺料/3×3/无配方/机器专属 + **背包逐槽未变**硬断言
新增  item/CraftCheckItem + 模型 + 中英 lang（零参数入口 alice:craft_check）
修改  RecipeDump.stationFor()         把"哪些类型算原版可读"暴露成**唯一定义处**（供查询复用）
修改  AliceItems / BotManager.assignCraftCheck / 电池（23 → 24 项，新增 craft_check 步）
```
判据（一次右键）：`[CraftCheck] SUMMARY craftable_sticks=PASS missing_ingredients=PASS needs_table=PASS
no_recipe=PASS machine_only=PASS|SKIP read_only=PASS verdict=PASS`。
**未验证**：客户端实测（编译+资源自检已过）。
