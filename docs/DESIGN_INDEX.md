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

## 读数（**只从 `package-info.java` 与目录结构算**）

| 量 | 值 |
|---|---|
| 顶层包数 | **30** |
| ⭐ **顶层包有设计说明**（`package-info.java`） | **7 / 30**（23%） |
| ⛔ **顶层包没有设计说明** | **23 / 30** ⇒ 见下表 `设计位置 = —` 的行 |
| 子包有设计说明 | **2**（`craft/`, `authz/`） |
| 首节（`<h2>`）总数 | **37**（⚠️ 编号风格**不统一**：`一 ·` / `零 ` / 无编号 ⇒ 尚未统一，见 `D1`） |

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
2. ⛔ **首节的编号风格不统一**（`一 ·` / `零 ⭐⭐ ` / 无编号）—— 本索引**照原样抄**，⛔ 不做归一（归一是 `D1` 那一刀，改了才算）。

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

