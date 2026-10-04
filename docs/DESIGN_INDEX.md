# 设计索引（**生成物，禁手改**）

> ⚠️ **本文件由脚本生成** —— 手改会被门禁判红。
> 重新生成：`python3 tools/design-index.py --write`　·　校验：`python3 tools/design-index.py --check`
> 门禁：`tools/check-design-index.sh`（挂 `tools/check-all.sh`）。

## 为什么有这张表

> 用户 2026-10-02 逐字：「项目也确实需要**一个设计总文档**，至少要带上**散落的设计索引**吧，**索引从下往上维护**」

⭐ **设计说明住在它描述的那个包里**（`package-info.java`）⇒ 改代码的人顺手就能改。
本索引只是把它们**汇总**起来，⛔ **不新增任何事实**（与 `docs/DECISIONS_INDEX.md` 同一条纪律）。

## 怎么用（三步）

1. 想知道**某个包是干什么的** ⇒ 在这张表里找它，看 `首句`；
2. 想知道**为什么这么切** ⇒ 读它的 `package-info.java`（`设计位置` 列有路径）；
3. 想知道**它受哪些裁定约束** ⇒ 看 `受约束` 列（⚠️ 这是从 `package-info.java` **扫出来的引用**，不是判断）。

## ⭐⭐ 第 ① 层：`docs/` 根下的**设计件**（2026-10-02 新增）

⚠️ 用户 2026-10-02 逐字（**纠正**我建议的 `docs/designs/`）：
> 「`docs/designs/` **不太适合当「施工设计书」**，**会被误认为是项目的设计文档**，
> 实际上**大型施工只有草案或者说定案，加上施工计划书**，这两个**不是「设计的载体」，是设计的结果**，
> 是要**被反复修正**的。」

⇒ ⭐ **不建 `docs/designs/`** —— **它已经有了，就是 `docs/` 根**。
⛔ 而本索引**原来一份都没收它们**（只收 `package-info.java`）⇒ 这一层就是那个缺口的补丁。

| 设计件（`docs/` 根） | 行数 | 受约束（**扫出来的** `D-###`） |
|---|---:|---|
| **`ALICE_PATHING_CORE_ARCHITECTURE.md`** —— Alice Pathing Core Architecture | 444 | `D-025` `D-026` |
| **`ALICE_PATHING_CORE_R1_CONTRACT.md`** —— Alice Pathing Core R1 Contract | 528 | — |
| **`ALICE_PATHING_CORE_R2_MOVEMENTS.md`** —— Alice Pathing Core R2：Movement 类型与能力声明 | 391 | — |
| **`DEATH_AND_REVIVAL_DESIGN_DRAFT.md`** —— 死亡与复活机制 · 设计草案（待用户审定） | 157 | `D-276` `D-284` |
| **`DECISION_LAYER_DESIGN.md`** —— L4 决策层（GoalDirector 家族）设计 —— 三条通道 + 分步骨架 | 275 | `D-076` `D-106` `D-134` `D-135` `D-137` `D-138` `D-140` `D-327` `D-341` `D-345` `D-348` |
| **`DECISION_LAYER_FINAL_FORM.md`** —— 决策层最终形态：任务队列 + 持久终态 + 历史落盘 | 272 | `D-135` `D-138` `D-139` `D-140` `D-267` `D-327` `D-338` `D-345` `D-348` `D-349` |
| **`INTERACTION_LAYERS_COMPARISON.md`** —— 方块交互三条路线对比：接口直写 vs 菜单协议 vs 视觉识别 | 82 | — |
| **`JOB_LAYER_DESIGN.md`** —— L3 目标级任务层（Job）设计 —— 伐木作为第一消费者 | 464 | `D-040` `D-048` `D-050` `D-058` `D-062` `D-070` `D-071` `D-073` `D-076` `D-107` `D-109` `D-127` `D-128` `D-129` `D-131` `D-338` `D-478` `D-516` |
| **`MINE_MIGRATION_DESIGN.md`** —— Mine 迁移设计（评审稿 · 历史） | 180 | `D-047` `D-064` `D-065` `D-076` `D-329` |
| **`MINE_TASK_DESIGN.md`** —— 挖矿任务设计（阶段 0 设计文档） | 272 | `D-024` `D-076` `D-115` `D-132` `D-138` `D-151` `D-219` `D-327` `D-329` `D-331` `D-333` `D-336` `D-341` `D-347` `D-348` `D-349` `D-353` `D-354` `D-355` `D-356` `D-357` `D-358` `D-361` |
| **`MINING_STAND_SELECTION_DESIGN.md`** —— 挖掘站位选优 + Baritone 融合：新框架设计（定稿 v5） | 225 | `D-044` `D-055` `D-061` `D-066` |
| **`MULTI_BOT_INTERFACE_RESERVATION.md`** —— 多 Bot 并行接口预留（非当前验收目标） | 12 | — |
| **`REGION_REPLANT_ASYNC_DESIGN.md`** —— 区域补种异步化 + 可配置拾取清单：设计方案 | 221 | `D-076` `D-338` `D-341` `D-342` `D-343` |
| **`RISK_SYSTEM_DESIGN_DRAFT.md`** —— Alice 风险系统 · 实现草案 | 527 | `D-001` `D-024` `D-036` `D-037` `D-040` `D-046` `D-050` `D-051` `D-058` `D-059` `D-060` `D-062` `D-076` `D-079` |

**入选口径**（⛔ 显式清单，不是模糊匹配）：文件名命中 `*_DESIGN.md` / `*_DESIGN_DRAFT.md` / `*_ARCHITECTURE.md` / `*_CONTRACT.md` / `*_FORM.md` / `*_COMPARISON.md` / `*_SELECTION_DESIGN.md` / `ALICE_PATHING_CORE_*.md` / `MULTI_BOT_INTERFACE_RESERVATION.md`。

**⛔ 与「施工域」的分界（用户 2026-10-02 划定）**：

| 层 | 住哪 | 是什么 |
|---|---|---|
| **设计件** | `docs/` 根（本表） | 系统级 / 包级设计的**结果**（⛔ 不是「载体」）|
| **刀级设计产出 ＋ 参考** | `docs/plans/` | 草案 / 讨论记录 / 设计单（用户：「**只适合当记录，施工时不适合拿来看**」）|
| ⭐ **施工依据** | `docs/DOC_REFACTOR_PLAN.md` | **施工计划书**（施工期唯一执行入口）|
| **报告 / 复核** | `docs/reviews/` · `survey/` | ⑤ 类：**可引、⛔ 不可当依据** |

## 读数（**只从 `package-info.java` 与目录结构算**）

| 量 | 值 |
|---|---|
| 顶层设计件（`docs/` 根） | **14** |
| 顶层包数 | **30** |
| ⭐ **顶层包有设计说明**（`package-info.java`） | **7 / 30**（23%） |
| ⛔ **顶层包没有设计说明** | **23 / 30** ⇒ 见下表 `设计位置 = —` 的行 |
| 子包有设计说明 | **2**（`craft/`, `authz/`） |
| 首节（`<h2>`）总数 | **36**（⚠️ 编号风格**不统一**：`一 ·` / `零 ` / 无编号 ⇒ 尚未统一，见 `D1`） |

## 表（**门禁逐字节比对的就是这一段**：包 / 首句 / 设计位置 / 受约束）

| 包 | 首句（`package-info` 第一段） | 设计位置 | 受约束 |
|---|---|---|---|
| **`action/`** | 执行面（action/） —— ⭐「只装执行件，以及这些执行件的调用器」（用户 2026-10-01 逐字裁定）。 | `src/main/java/com/dddgn/alice/action/package-info.java` | `D-455` |
| **`↳ action/craft/`** | 合成域执行件（action/craft/） —— 「动作 / 动作逻辑」里合成那一个域的执行件。 | `src/main/java/com/dddgn/alice/action/craft/package-info.java` | `D-185` `D-204` `D-290` `D-569` |
| **`bot/`** | — | — | — |
| **`capability/`** | — | — | — |
| **`client/`** | — | — | — |
| **`command/`** | — | — | — |
| **`compat/`** | 模组功能适配层（compat/） —— 一个具体模组的一条具体能力，以软依赖 ＋ 反射实现。 | `src/main/java/com/dddgn/alice/compat/package-info.java` | `D-219` `D-318` |
| **`config/`** | — | — | — |
| **`debug/`** | 调试面（D-492 规矩 R2）：发行后玩家也能用 ⇒ 它是产品面，不是开发期夹具。 | `src/main/java/com/dddgn/alice/debug/package-info.java` | `D-479` `D-491` `D-492` `D-551` `D-552` |
| **`decision/`** | — | — | — |
| **`fixture/`** | 开发期测试夹具（D-492 规矩 R2）：只用于开发时的验证，不是"调整调试"的工具。 | `src/main/java/com/dddgn/alice/fixture/package-info.java` | `D-476` `D-491` `D-492` `D-552` |
| **`gui/`** | — | — | — |
| **`headless/`** | — | — | — |
| **`item/`** | — | — | — |
| **`job/`** | — | — | — |
| **`ledger/`** | — | — | — |
| **`log/`** | — | — | — |
| **`network/`** | — | — | — |
| **`pathing/`** | — | — | — |
| **`perception/`** | — | — | — |
| **`protection/`** | — | — | — |
| **`reach/`** | — | — | — |
| **`region/`** | ⭐ 区域（region/） —— 回答四个问题：区域是什么 · 谁声明的 · 谁能覆盖谁 · 什么时候消失。 | `src/main/java/com/dddgn/alice/region/package-info.java` | `D-338` `D-565` |
| **`↳ region/authz/`** | ⭐ 区域授权（region/authz/） —— 回答「这一格现在允不允许动」。 | `src/main/java/com/dddgn/alice/region/authz/package-info.java` | `D-563` |
| **`road/`** | — | — | — |
| **`staging/`** | 过渡容器（staging/） —— ⚠️⚠️ 临时的，⛔ 不是一层，也不是"新专属包"。 | `src/main/java/com/dddgn/alice/staging/package-info.java` | `D-569` |
| **`step/`** | 原语层（step/） —— 「逐 tick 推进到单格结论」的最小执行单元。 | `src/main/java/com/dddgn/alice/step/package-info.java` | `D-455` `D-460` `D-461` `D-462` `D-466` `D-469` `D-492` `D-493` `D-539` |
| **`survival/`** | — | — | — |
| **`task/`** | — | — | — |
| **`tool/`** | — | — | — |
| **`transfer/`** | — | — | — |
| **`write/`** | — | — | — |

> **列义**：`首句` = `package-info.java` 里**第一个 `<h2>` 之前**的第一段文字（⛔ 摘要，不是设计）·
> `受约束` = 该文件里**扫到的** `D-###` 引用（⚠️ 扫不到 ≠ 不受约束）·
> `↳` 打头的行 = **子包**（列在它自己的完整路径下）。

## ⛔ 本索引**假装不了**的两件事（诚实边界）

1. ⛔ **23 个顶层包没有设计说明** —— 表里显示 `—`。本索引**只暴露**这件事，⛔ 不代替你去补；补一份 = 在那个包里加一个 `package-info.java`。
2. ⛔ **首节的编号风格不统一** —— ⚠️ **本条已在前一版之后被施工修掉**：9 份 `package-info.java` 的节名与次序已按 8 角色骨架统一（2026-10-02 `W4-2`），节号**按角色固定** ⇒ 某份文件里**会跳**（那不表示缺内容）。
3. ⛔ **`docs/` 根的设计件只有 14 份进表** —— 本索引**不判断它们是不是好的设计**，⛔ 也不判断「这个包的设计该不该写」；它只回答「**在哪**」。

## 附录：类数明细（**不计入门禁比对**）

> ⚠️ **下面这张表不在门禁比对范围内** —— 类数**每次改动都变**，算进比对会让门禁退化成「例行敲一下」（`docs/DECISIONS_INDEX.md` 的同一教训）。

| 包 | 顶层 `.java` | 全树 `.java` |
|---|---:|---:|
| **`action//`** | 5 | 18 |
| **`craft//`** | 10 | 10 |
| **`bot//`** | 17 | 17 |
| **`capability//`** | 3 | 3 |
| **`client//`** | 8 | 15 |
| **`command//`** | 1 | 1 |
| **`compat//`** | 0 | 4 |
| **`config//`** | 1 | 1 |
| **`debug//`** | 7 | 7 |
| **`decision//`** | 21 | 21 |
| **`fixture//`** | 119 | 154 |
| **`gui//`** | 5 | 5 |
| **`headless//`** | 1 | 1 |
| **`item//`** | 80 | 80 |
| **`job//`** | 13 | 40 |
| **`ledger//`** | 2 | 2 |
| **`log//`** | 1 | 1 |
| **`network//`** | 12 | 12 |
| **`pathing//`** | 2 | 71 |
| **`perception//`** | 5 | 5 |
| **`protection//`** | 5 | 5 |
| **`reach//`** | 10 | 10 |
| **`region//`** | 4 | 8 |
| **`authz//`** | 3 | 3 |
| **`road//`** | 5 | 5 |
| **`staging//`** | 0 | 0 |
| **`step//`** | 0 | 0 |
| **`survival//`** | 9 | 9 |
| **`task//`** | 13 | 20 |
| **`tool//`** | 1 | 1 |
| **`transfer//`** | 14 | 14 |
| **`write//`** | 4 | 4 |

