#!/usr/bin/env python3
"""`J-★` 第 6 段 **step 5a**（2026-09-27）结构门禁：**原子与编排分家之后，不许再长回去**。

## 为什么这条规则该存在

`step 5a`（`D-466` 十拍 / `D-469` 落地）把 `task/MineTask.java` 里"**跑一格**"的那一段切出来，
放进新原语 `task/mining/MineStep.java`：

    原语（MineStep） = 计划段 + 走位/破坏 + **单格结论**（单一成功判据 + 单一失败归因 + 不自带额度）
    编排（MineTask） = 相位机 + 两个分叉 + 清障候选循环 + 加高 + 连锁 + 收集 + 建拆 + 重规划/升档

`plan §5.2` 的原语三条判据**没有一条能靠"读代码感觉"守住**：只要有人顺手在 `MineStep` 里加一个
相位字段、在方法体里为子任务造一个额度、或者把"什么时候开一步"也搬进去，**构建照样是绿的**
（本仓已经有过同族教训：`D-178` 的"终态硬编码"曾同时存在 6 处而无人发现）。
⇒ 把边界写成**可失败的断言**。

## 断言（五条；任一不成立 ⇒ 非零退出）

| # | 判据 | 形状 |
|---|---|---|
| **A** | 原语里**没有相位机** | `task/mining/MineStep.java` 存在，且**剥掉注释与字符串之后** `\\bPhase\\b` 与 `\\bphase\\b` 各 **0** 处 |
| **B** | 原语只有**一个成功出口** | `Conclusion.success()` 产出点**恰好 1**，且 4 个结论工厂**各有且仅有 1 个定义**（正向人口：改名/删工厂必须红，不许静默变成 0） |
| **C** | **原语不造子任务，编排器真在委托** | ① `MineStep` 里 `new MineTask(` **0** 处；② `MineTask` 里对原语的调用 `step.` **≥ `MIN_DELEGATIONS`** |
| **D1** | 原语的额度**只来自构造参数** | `MineStep` 里 `MiningBudget.forTarget|collecting(` / `new MiningBudget(` **0** 处；且类内**额度词命名**的 `static final` 常量 **0** 条 |
| **D2** | 原语消费额度的点**恰好 1 个、且是具名的那个** | 全部被识别的"额度消费"形态合计**恰好 1** 处，且它必须是 `NAMED_BUDGET_SITES` 里那一处（今天 = `miningPlanner.plan(`）；`WriteBudget.` **0** 处 |

⚠️ **D1 比 `check-primitive-budget-injection.py` 更严**：那条门禁只红"构造器/字段初始化/静态块里造额度"
（"调用方不说也能用"的入口），并把"方法体里为子任务派生额度"**显式列为绿**；本条的靶子是**原语自己**，
而原语**根本不造子任务**（判据 C）⇒ 它里面**任何**制造额度的写法都是红。

## 边界：本门禁**不**覆盖什么（防"绿 = 合规"的误读）

- **不覆盖"两个类内额度常量"**（`MineTask.MAX_RECOVERY_ATTEMPTS` / `CHAIN_TIMEOUT_TICKS`）——
  `D-466` 第 4 条裁定它们**随编排留下、不注入**（`D1` 的范围**只管原语**）。
  它们的"词汇漏洞"由读数门禁**只打印**（`check-primitive-readings.py` 的 `static final` 清单）。
  ⚠️ **已知覆盖缺口**：那个清单的 `PRIMITIVES` 今天仍只含 `MineTask` / `CollectDropsTask`
  （路径写死 `task/{name}.java`）⇒ **新原语 `MineStep` 的常量清单还没被打印**，记在台账待办里。
- **不覆盖"编排器有没有资格造编排器"** —— `D-466` §四.1 已把设计原稿的
  「`task/**` 不得 import 编排器」**作废**：编排器造编排器是**合法**的，判据 C 因此只约束原语那一侧。
- **不覆盖"行为没变"** —— 静态门禁只证明**形状**；行为由无头电池的逐步 diff（`D-201` 附注一）守。
- **不覆盖 `MineStep` 的终态闩锁形状** —— 那条归 `tools/kernel-predicates.py` 的 `A1′`（`D-410`/`D-178`）。

## 红臂（`--selftest`，每次运行都跑）

每条判据各带**红/绿两臂**，且每臂**只打一个判据函数** ⇒ "红"必然红在**那一条**上
（本仓"臂打错地方"已复发 3 次，所以臂不许跨判据）。

## `step 5b`（`D-493`）的对应物：同一个规则，第二个原语

`D-466` 判 3（D1/D2）把"**额度来源 = 构造参数 · 消费点 = 具名清单且恰好 1**"立成规则，
而 `D-493` 的硬约束逐字要求「`5b` 必须给出它的对应物（`CollectStep` 里的那一处消费点）」
⇒ 本文件在原有五条（`MineStep`）之外，为 `task/collecting/CollectStep` 加**四条**：

| # | 判据 | 形状 |
|---|---|---|
| **C①** | 原语**不造**编排器 | `CollectStep` 里 `new CollectDropsTask(` **0** 处 |
| **C②** | 编排器**真在委托** | `CollectDropsTask` 里对原语的调用 `step.` **≥ `MIN_DELEGATIONS_5B`** |
| **D1** | 原语的额度**只来自构造参数** | `CollectStep` 里额度制造 **0** 处；类内**额度词命名**的 `static final` **0** 条 |
| **D2** | 原语消费额度的点**恰好 1 个、且是具名的那个** | `sweepTicks > sweepBudgetTicks` 这类比较**恰好 1** 处，且它必须**在具名方法** `sweepBudgetExhausted()` 体内；`WriteBudget.` **0** 处 |

⚠️ **为什么 `5b` 只有四条**（不套 5a 的 A/B）：`A`（无相位机）与 `B`（单一成功出口）的落点是
`MineStep` 的 `Conclusion` 工厂 —— `CollectStep` 的对应物是 `Outcome` + `Reading`，形状不同，
硬套会把"判据"变成"抄格式"（判据要跟着**设计**走，不是跟着上一个原语的模板走）。

跑法：`python3 tools/check-task-orchestration-split.py`（已挂在 `tools/check-all.sh`）。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STEP = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "task" / "mining" / "MineStep.java"
ORCH = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "task" / "MineTask.java"

#: 判据 C ②：编排器对原语的调用点下限（实测 **8**，2026-09-27；留余量）。
#: ⚠️ 判据是**下限**，不是这个数本身 —— 这个数会随原语接口变化。
MIN_DELEGATIONS = 5

#: 判据 D2 的**具名清单**：唯一允许出现的"额度消费"点（今天原语里只有这一处）。
NAMED_BUDGET_SITES = ("miningPlanner.plan(",)

#: 相位机的两种写法（判据 A）：类型名与字段/局部名。
PHASE_TYPE = re.compile(r"\bPhase\b")
PHASE_WORD = re.compile(r"\bphase\b")
#: 判据 B。
SUCCESS_EXIT = re.compile(r"Conclusion\s*\.\s*success\s*\(\s*\)")
SUCCESS_FACTORY = re.compile(r"static\s+Conclusion\s+success\s*\(\s*\)")
FACTORY_DEF = re.compile(r"static\s+Conclusion\s+(\w+)\s*\(")
CONCLUSION_SITE = re.compile(r"Conclusion\s*\.\s*(\w+)\s*\(")
EXPECTED_FACTORIES = ("success", "running", "planningFailure", "executionFailure")
#: 判据 C。
NEW_ORCHESTRATOR = re.compile(r"new\s+MineTask\s*\(")
DELEGATION = re.compile(r"\bstep\s*\.")
#: 判据 D1。
BUDGET_MANUFACTURE = re.compile(
    r"MiningBudget\s*\.\s*(?:forTarget|collecting)\s*\(|new\s+MiningBudget\s*\(")
STATIC_FINAL = re.compile(r"^ {4}[^\n]*\bstatic\s+final\b[^\n]*=", re.M)
QUOTA_WORD = re.compile(r"(?i)(budget|quota|grant|额度)")
#: 判据 D2 的"额度消费"识别面（**全部**形态都要数，不只数具名那一处）。
BUDGET_CONSUMPTION = (
    r"miningPlanner\s*\.\s*plan\s*\(",
    r"WriteBudget\s*\.\s*consume\w*\s*\(",
    r"\.\s*consumeBreak\s*\(",
    r"\.\s*consumePlace\s*\(",
)
WRITE_BUDGET = re.compile(r"WriteBudget\s*\.")

# ==================== `step 5b`（`D-493`）：收集侧的第二个原语 ====================

STEP_5B = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice"
           / "task" / "collecting" / "CollectStep.java")
ORCH_5B = (ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice"
           / "task" / "CollectDropsTask.java")

#: 判据 C② 的下限（实测 **16**，2026-09-28；判据是**下限**，不是这个数本身）。
MIN_DELEGATIONS_5B = 3

#: 判据 D2 的**具名清单**：`CollectStep` 里唯一允许的"额度消费"点（簇预算）。
NAMED_BUDGET_SITES_5B = ("sweepBudgetExhausted()",)
#: 判据 D2 的"额度消费"识别面（**全部**形态都要数，不只数具名那一处）。
SWEEP_BUDGET_CONSUMPTION = (
    r"sweepTicks\s*[><]=?\s*sweepBudgetTicks",
    r"sweepBudgetTicks\s*[><]=?\s*sweepTicks",
)
NEW_ORCHESTRATOR_5B = re.compile(r"new\s+CollectDropsTask\s*\(")
DELEGATION_5B = re.compile(r"\bstep\s*\.")


# ==================== 柱② 「注册口」（`4a`；用户 2026-09-29 裁 `N3`） ====================
#
# 出处：`O41` §1c.3 柱②（目标形态 = 「新功能 = 新执行器 ＋ 新 step」）· `O59` §3（实测：**没有**通用
# `Step` 接口，唯一的 step 门禁**逐类写死路径**）· `O63`（AI 建议：注册口就是**接口本身**）。
#
# 为什么必须有这一段：在此之前「哪些类是原语」由 `STEP` / `STEP_5B` **两个写死的路径常量**回答
# ⇒ ⚠️ **新加一个 step，本门禁不会覆盖它**（同族教训已在本仓发生过：`PRIMITIVES` 清单漏掉
# `MineStep` 的常量读数）。改成 `rglob` **自动枚举全部 `implements Step`** 之后，下面这几条对
# **新 step 自动生效**。这也是横切闸门④ 第二半（「新功能必须落在内核路径之外」）能长出检查的前提：
# 没有扩展点 ⇒ 没有"实现了扩展点"这个**可检查的事实**。

ALICE_DIR = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice"
TASK_DIR = ALICE_DIR / "task"
#: 注册口**本身**（接口声明文件）——反向检查必须跳过它（它的文件名也以 `Step` 结尾）。
STEP_INTERFACE = TASK_DIR / "Step.java"
#: ⚠️ `implements … Step`（覆盖 `implements Step` / `implements Step, Xxx` / `implements A, Step`）。
IMPL_STEP = re.compile(r"\bimplements\b[^{;]*\bStep\b")
TICK_DECL = re.compile(r"\btick\s*\(")
#: 「造任务」的**通用**形态（`MineTask` / `CollectDropsTask` / 任何 `*Task`）——原语一个都不许造。
NEW_ANY_TASK = re.compile(r"new\s+\w+Task\s*\(")
#: 「制造额度」的**通用**形态（`new *Budget(` / `*Budget.for*`）——原语的额度只能来自构造参数。
BUDGET_ANY = re.compile(r"new\s+\w*Budget\s*\(|\bBudget\s*\.\s*for\w+\s*\(")
#: 反空转人口下限（**实测 2**：`MineStep` / `CollectStep`）。⛔ 别把它当"目标值"，也别调低来消红。
STEP_IMPL_MIN = 2
#: ⚠️ **同名两物豁免**（反向检查的具名登记；**双向**：条目必须仍存在、且必须**仍不实现** `Step`）。
STEP_NAMESAKE_EXEMPT = {
    "task/check/CheckStep.java":
        "**同名两物**：它是**电池/自检步的描述 record**（场景＋发料＋任务工厂＋预算＋判据），"
        "**不是**本门禁意义上的原语（`task/Step` 接口指后者）⇒ 刻意不实现它。"
        "复核触发 = 它被改名/换形状那一刀（届时删本行）",
}
#: 夹具文件名标记 —— **唯一真源 = `Task.SELF_CHECK_MARKERS`**（不在这里另写一份约定）。
FIXTURE_MARKERS = ("check", "probe", "dump", "diagnostic", "regression", "battery", "demo")


def looks_like_fixture_file(name: str) -> bool:
    """按 `Task.SELF_CHECK_MARKERS` 判断文件名是不是夹具（夹具不参与注册口的反向检查）。"""
    low = name.lower()
    return any(marker in low for marker in FIXTURE_MARKERS)


def check_reg_implements_step(code: str) -> list[str]:
    """注册口 ⑤（反向检查）：`task/**\\*Step.java` 必须 `implements Step`。"""
    if not IMPL_STEP.search(code):
        return ["名字以 `Step` 结尾、住在 `task/` 下，却**没有** `implements Step` ⇒ 注册口漏登记"
                "（要么实现它，要么进 `STEP_NAMESAKE_EXEMPT` 并写理由）"]
    return []


def check_reg_declares_tick(code: str) -> list[str]:
    """注册口 ④：注册的 step 必须**自己有 `tick(`**（原语的定义 = 逐 tick 推进到单格结论）。"""
    if not TICK_DECL.search(code):
        return ["注册的 step 里找不到 `tick(` ⇒ 它不像是「逐 tick 推进」的原语"
                "（形状真的变了？那要改本判据，⛔ 别删）"]
    return []


def check_reg_no_phase(code: str) -> list[str]:
    """注册口 ⑥（判据 A 的通用版）：注册的 step **不许有相位机**。"""
    hits = len(PHASE_TYPE.findall(code)) + len(PHASE_WORD.findall(code))
    if hits:
        return [f"注册的 step 里出现相位机痕迹 {hits} 处（`Phase` / `phase`）"
                "⇒ 相位机归编排器（`D-466` §五）"]
    return []


def check_reg_no_task_construction(code: str) -> list[str]:
    """注册口 ①（判据 C① 的通用版）：注册的 step **不许造任何任务/编排器**。"""
    hits = NEW_ANY_TASK.findall(code)
    if hits:
        return [f"注册的 step 里出现 {len(hits)} 处 `new *Task(` ⇒ 原语不许造任务"
                "（编排器造编排器是合法的，原语造任何任务都不是）"]
    return []


def check_reg_budget_injected(code: str) -> list[str]:
    """注册口 ②（判据 D1 的通用版）：注册的 step 的额度**只来自构造参数**。"""
    hits = BUDGET_ANY.findall(code)
    if hits:
        return [f"注册的 step 里**制造额度** {len(hits)} 处（`new *Budget(` / `*Budget.for*`）"
                "⇒ 额度只能来自构造参数"]
    return []


def check_reg_no_quota_constant(code: str) -> list[str]:
    """注册口 ③（判据 D1 后半的通用版）：注册的 step 里**不许有额度词命名的 `static final`**。"""
    problems = []
    for line in STATIC_FINAL.findall(code):
        if QUOTA_WORD.search(line):
            problems.append(f"注册的 step 里有**额度词命名**的 `static final`：{line.strip()[:70]}"
                            "⇒ 额度常量只能由调用方注入")
    return problems


def check_step_registration() -> tuple[list[str], dict]:
    """柱② 注册口：**自动枚举** `implements Step` 的类（= 原语集合），逐类判形状 ＋ 反向检查。

    ⚠️ 判据是**结构**，⛔ 不判"行为没变"（那归无头电池的逐步 diff）。
    """
    problems: list[str] = []
    impls: dict[str, Path] = {}
    for path in sorted(ALICE_DIR.rglob("*.java")):
        code = strip_comments_and_strings(path.read_text(encoding="utf-8"))
        if IMPL_STEP.search(code):
            impls[path.relative_to(ROOT).as_posix()] = path

    # ① 反空转：人口下限（⛔ 不许调低来消红）
    if len(impls) < STEP_IMPL_MIN:
        problems.append(f"实现了 `Step` 的类只有 {len(impls)} 个（下限 {STEP_IMPL_MIN}）"
                        "⇒ 解析崩塌、或注册口被绕过（`4a` 柱② 的登记面消失了）")

    # ② 逐类：层位置 ＋ 五条形状（⛔ 一条都不许因为"这个类特殊"而跳过）
    for rel, path in impls.items():
        code = strip_comments_and_strings(path.read_text(encoding="utf-8"))
        # ⚠️ 层位置判据用**包内相对路径**：`rel` 是仓库相对路径（前缀里带着 `src/main/java/…`），
        #    本门禁第一版就是拿它去 `startswith("task/")`，于是两个原语全被误报（已实测踩过）。
        if not path.relative_to(ALICE_DIR).as_posix().startswith("task/"):
            problems.append(f"`{rel}` 实现了 `Step` 但**不住在 `task/` 下**"
                            "⇒ 原语的层位置变了（本判据要跟着改，⛔ 别删）")
        for p in (check_reg_declares_tick(code)
                  + check_reg_no_phase(code)
                  + check_reg_no_task_construction(code)
                  + check_reg_budget_injected(code)
                  + check_reg_no_quota_constant(code)):
            problems.append(f"`{rel}`：{p}")

    # ③ 反向检查：`task/**\*Step.java` 里"名字像 step、又不是夹具"的，必须在 `impls` 或豁免表里
    for path in sorted(TASK_DIR.rglob("*Step.java")):
        if path == STEP_INTERFACE:
            continue                      # ⚠️ 注册口**本身**：它的文件名也以 `Step` 结尾
        if "interface Step" in path.read_text(encoding="utf-8"):
            continue                      # 任何"声明 `Step` 接口"的文件都不参与反向检查
        rel = path.relative_to(ROOT).as_posix()
        if rel in impls or rel in STEP_NAMESAKE_EXEMPT or looks_like_fixture_file(path.name):
            continue
        problems.append(f"`{rel}` 名字以 `Step` 结尾且住在 `task/` 下，却**既没实现 `Step`、"
                        "也没登记豁免** ⇒ 注册口漏登记（实现它，或加一条具名豁免 ＋ 理由）")

    # ④ 豁免表**双向**：条目必须仍存在 ＋ 必须**仍不实现** `Step`（陈条目 ⇒ 红）
    for rel, reason in STEP_NAMESAKE_EXEMPT.items():
        path = ALICE_DIR / rel
        if not path.exists():
            problems.append(f"豁免表里的 `{rel}` 不存在了 ⇒ 陈旧条目（理由原文：{reason[:40]}…）")
        elif rel in impls:
            problems.append(f"豁免表里的 `{rel}` 现在**已经实现** `Step` ⇒ 陈旧条目，请删掉它")
        elif not reason.strip():
            problems.append(f"豁免表里的 `{rel}` 没写理由 ⇒ 豁免必须具名说明为什么它不是原语")

    return problems, {"impls": sorted(impls), "exempt": len(STEP_NAMESAKE_EXEMPT)}


def strip_comments_and_strings(text: str) -> str:
    r"""去 `//`、`/* */`、字符串与字符字面量（**保留换行**，行号仍可算）。

    ⚠️ 必须做：判据 A 的靶子（"相位机"）在本类注释里**被反复提到**（"本类没有相位枚举"），
    判据 D1 的靶子 `MiningBudget.forTarget` 也在注释里出现 ⇒ 不剥的话，
    光靠注释就能把断言"满足"掉（`D-438`/`D-468` 实测过的教训）。
    """
    out: list[str] = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif c in "\"'":
            quote = c
            i += 1
            while i < n and text[i] != quote:
                i += 2 if text[i] == "\\" else 1
            i += 1
            out.append(" ")
        else:
            out.append(c)
            i += 1
    return "".join(out)


# ==================== 五条判据（每条一个函数 ⇒ 臂只能打在自己那条上） ====================

def check_a_no_phase_machine(code: str) -> list[str]:
    """判据 A：原语里没有相位机（`Phase` / `phase` 各 0 处）。"""
    type_hits = len(PHASE_TYPE.findall(code))
    word_hits = len(PHASE_WORD.findall(code))
    if type_hits or word_hits:
        return [f"`MineStep` 里出现相位机痕迹：`Phase` {type_hits} 处 · `phase` {word_hits} 处"
                f"⇒ 原语**不许有**相位枚举/相位字段（相位机归编排器，`D-466` §五）"]
    return []


def check_b_single_success_exit(code: str) -> list[str]:
    """判据 B：成功出口恰好 1，且四个结论工厂各有且仅有 1 个定义（正向人口）。"""
    problems: list[str] = []
    exits = len(SUCCESS_EXIT.findall(code))
    if exits != 1:
        problems.append(f"`MineStep` 里成功出口 `Conclusion.success()` 有 {exits} 处（**必须恰好 1**）"
                        f"⇒ 0 处 = 成功判据被改名/搬走，>1 处 = 出现了第二个成功出口（`D-466` §五 B）")
    if len(SUCCESS_FACTORY.findall(code)) != 1:
        problems.append("`MineStep` 里找不到 `static Conclusion success()` 的**唯一定义**"
                        "⇒ 判据 B 的人口没了（不是「通过」，是「扫不到」）")
    defined = {m.group(1) for m in FACTORY_DEF.finditer(code)}
    missing = [name for name in EXPECTED_FACTORIES if name not in defined]
    if missing:
        problems.append(f"`MineStep` 的结论工厂缺 {missing}（应有 {list(EXPECTED_FACTORIES)}）"
                        f"⇒ 原语要能给出 RUNNING/DONE/FAILED 三种结论，缺一个就没法表达")
    sites = len(CONCLUSION_SITE.findall(code))
    if sites < 3:
        problems.append(f"`MineStep` 里只有 {sites} 个结论产出点（下限 3）⇒ 解析崩塌或结论被搬走")
    return problems


def check_c_primitive_creates_no_task(code: str) -> list[str]:
    """判据 C ①：**原语不造子任务**（`new MineTask(` 0 处）。"""
    hits = len(NEW_ORCHESTRATOR.findall(code))
    if hits:
        return [f"`MineStep` 里有 {hits} 处 `new MineTask(` ⇒ **原子单元造编排器** = 编排长回原语里"
                f"（`D-466` §四.1：这条判据的落点是**原语**，编排器造编排器才合法）"]
    return []


def check_c_orchestrator_delegates(code: str) -> list[str]:
    """判据 C ②：**编排器真在委托**（对原语的调用点 ≥ `MIN_DELEGATIONS`）。

    防"把整段搬回去、或者把原语架空成死代码"——只用 `new MineStep(` 把它 new 出来不算委托。
    """
    hits = len(DELEGATION.findall(code))
    if hits < MIN_DELEGATIONS:
        return [f"`MineTask` 里对原语的调用 `step.` 只有 {hits} 处（下限 {MIN_DELEGATIONS}）"
                f"⇒ 原语被架空（搬回去了 / 变成只 new 不用的死代码）"]
    return []


def check_d1_budget_is_injected(code: str) -> list[str]:
    """判据 D1：原语的额度**只来自构造参数**（制造 0 处 + 额度词命名的类内常量 0 条）。"""
    problems: list[str] = []
    hits = len(BUDGET_MANUFACTURE.findall(code))
    if hits:
        problems.append(f"`MineStep` 里有 {hits} 处额度**制造**（`forTarget`/`collecting`/`new MiningBudget`）"
                        f"⇒ 原语的额度必须**构造注入**（`D-466` ③ D1；原语不造子任务 ⇒ 它没有任何"
                        f"「为谁派生额度」的正当理由）")
    named = [m.group(0).strip() for m in STATIC_FINAL.finditer(code)]
    quota = [line for line in named if QUOTA_WORD.search(line)]
    if quota:
        problems.append(f"`MineStep` 类内有**额度词命名**的 `static final`：{quota}"
                        f"⇒ 类内默认额度常量（`D-455` ⑧③）")
    return problems


def check_d2_named_consumption_sites(code: str) -> list[str]:
    """判据 D2：消费额度的点**恰好 1 个、且是具名的那一个**。"""
    problems: list[str] = []
    found: list[str] = []
    for pattern in BUDGET_CONSUMPTION:
        for _ in re.finditer(pattern, code):
            found.append(pattern)
    if len(found) != 1:
        problems.append(f"`MineStep` 里被识别为「额度消费」的点有 {len(found)} 处"
                        f"（**必须恰好 1**）⇒ 具名清单 = {list(NAMED_BUDGET_SITES)}"
                        f"（多了 = 额度在别处也被烧，少了 = 原语什么都不消费 / 清单过期）")
    expected = re.compile(NAMED_BUDGET_SITES[0].replace("(", r"\s*\(\s*"))
    if len(found) == 1 and not expected.search(code):
        problems.append(f"`MineStep` 里那唯一一处额度消费**不是**具名的那一个"
                        f"（`{NAMED_BUDGET_SITES[0]}`）⇒ 清单与代码对不上了")
    write_hits = len(WRITE_BUDGET.findall(code))
    if write_hits:
        problems.append(f"`MineStep` 里出现 {write_hits} 处 `WriteBudget.` ⇒ 写入额度的消费点"
                        f"属于执行机制（`MineBlockRunner`），不该长在原语里")
    return problems


# ==================== `step 5b` 的四条判据（每条一个函数 ⇒ 臂只能打在自己那条上） ====================

def body_after(code: str, decl: str) -> str:
    """`decl` 之后第一个**配对** `{...}` 体（取不到 ⇒ 空串；不抛）。"""
    idx = code.find(decl)
    if idx < 0:
        return ""
    start = code.find("{", idx)
    if start < 0:
        return ""
    depth = 0
    for i in range(start, len(code)):
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                return code[start + 1:i]
    return ""


def check_5b_c1_primitive_creates_no_orchestrator(code: str) -> list[str]:
    """判据 C①：**原语不造编排器**（`new CollectDropsTask(` 0 处）。"""
    hits = len(NEW_ORCHESTRATOR_5B.findall(code))
    if hits:
        return [f"`CollectStep` 里有 {hits} 处 `new CollectDropsTask(` ⇒ **原子单元造编排器** = 编排长回原语里"
                f"（`D-466` §四.1 的落点是**原语**：编排器造编排器才合法）"]
    return []


def check_5b_c2_orchestrator_delegates(code: str) -> list[str]:
    """判据 C②：**编排器真在委托**（对原语的调用点 ≥ `MIN_DELEGATIONS_5B`）。"""
    hits = len(DELEGATION_5B.findall(code))
    if hits < MIN_DELEGATIONS_5B:
        return [f"`CollectDropsTask` 里对原语的调用 `step.` 只有 {hits} 处（下限 {MIN_DELEGATIONS_5B}）"
                f"⇒ 原语被架空（搬回去了 / 变成只 new 不用的死代码）"]
    return []


def check_5b_d1_budget_is_injected(code: str) -> list[str]:
    """判据 D1：原语的额度**只来自构造参数**（制造 0 处 + 额度词命名的类内常量 0 条）。"""
    problems: list[str] = []
    hits = len(BUDGET_MANUFACTURE.findall(code))
    if hits:
        problems.append(f"`CollectStep` 里有 {hits} 处额度**制造**（`MiningBudget.forTarget|collecting` /"
                        f"`new MiningBudget`）⇒ 原语的额度必须**构造注入**（`D-466` 判 3 · D1）")
    named = [m.group(0).strip() for m in STATIC_FINAL.finditer(code)]
    quota = [line for line in named if QUOTA_WORD.search(line)]
    if quota:
        problems.append(f"`CollectStep` 类内有**额度词命名**的 `static final`：{quota}"
                        f"⇒ 类内默认额度常量（`D-455` ⑧③；机制档常量归**编排器**，值由构造参数下来）")
    return problems


def check_5b_d2_named_consumption_sites(code: str) -> list[str]:
    """判据 D2：消费额度的点**恰好 1 个、且是具名的那一个**（在具名方法体内）。"""
    problems: list[str] = []
    found = 0
    for pattern in SWEEP_BUDGET_CONSUMPTION:
        found += len(re.findall(pattern, code))
    if found != 1:
        problems.append(f"`CollectStep` 里被识别为「额度消费」的点有 {found} 处（**必须恰好 1**）"
                        f"⇒ 具名清单 = {list(NAMED_BUDGET_SITES_5B)}"
                        f"（多了 = 额度在别处也被烧，少了 = 原语什么都不消费 / 清单过期）")
    name = NAMED_BUDGET_SITES_5B[0]
    body = body_after(code, f"private boolean {name}")
    if not body:
        problems.append(f"`CollectStep` 里找不到具名的额度消费方法 `private boolean {name}` "
                        f"⇒ 判据 D2 的人口没了（不是「通过」，是「扫不到」）")
    elif found == 1 and not any(re.search(p, body) for p in SWEEP_BUDGET_CONSUMPTION):
        problems.append(f"`CollectStep` 里那唯一一处额度消费**不在**具名方法 `{name}` 体内"
                        f"⇒ 清单与代码对不上（消费点必须有名字，否则「恰好一处」不可读）")
    write_hits = len(WRITE_BUDGET.findall(code))
    if write_hits:
        problems.append(f"`CollectStep` 里出现 {write_hits} 处 `WriteBudget.` ⇒ 写入额度的消费点"
                        f"属于执行机制，不该长在原语里")
    return problems


def inspect_step_5b(code: str) -> list[str]:
    """`5b` 原语侧的三条（C①/ D1 / D2）。"""
    return (check_5b_c1_primitive_creates_no_orchestrator(code)
            + check_5b_d1_budget_is_injected(code)
            + check_5b_d2_named_consumption_sites(code))


def inspect_step(code: str) -> list[str]:
    """原语侧的四条（A/B/C①/D1/D2）。"""
    return (check_a_no_phase_machine(code)
            + check_b_single_success_exit(code)
            + check_c_primitive_creates_no_task(code)
            + check_d1_budget_is_injected(code)
            + check_d2_named_consumption_sites(code))


# ==================== 红臂（每次运行都跑；每臂只打一条判据） ====================

SELFTEST_CASES: list[tuple[str, str, str, bool]] = [
    # ---- A ----
    ("A 红：原语里长出相位枚举", "a",
     "    private enum Phase { EVALUATING, MINING }\n", True),
    ("A 红：原语里长出相位字段", "a",
     "    private Phase phase = Phase.MINING;\n", True),
    ("A 绿：注释里提到相位不算命中（本类注释确实提到）", "a",
     "    /** 本类没有 Phase 枚举，也没有 phase 字段。 */\n    private int step;\n", False),
    ("A 绿：`FailureReport` 的 `phase` 是**别的**类型，不写它就不命中", "a",
     "        MineBlockRunner.FailureReport r = miner.failureReport();\n", False),
    # ---- B ----
    ("B 红：两个成功出口", "b",
     "    private static Conclusion success() { return null; }\n"
     "    private static Conclusion running() { return null; }\n"
     "    private static Conclusion planningFailure(String r) { return null; }\n"
     "    private static Conclusion executionFailure(Object r) { return null; }\n"
     "    Conclusion a() { return Conclusion.success(); }\n"
     "    Conclusion b() { return Conclusion.success(); }\n", True),
    ("B 红：成功出口被改名（0 处）", "b",
     "    private static Conclusion ok() { return null; }\n"
     "    private static Conclusion running() { return null; }\n"
     "    private static Conclusion planningFailure(String r) { return null; }\n"
     "    private static Conclusion executionFailure(Object r) { return null; }\n"
     "    Conclusion a() { return Conclusion.ok(); }\n"
     "    Conclusion b() { return Conclusion.running(); }\n"
     "    Conclusion c() { return Conclusion.planningFailure(\"x\"); }\n", True),
    ("B 绿：一个成功出口 + 四个工厂", "b",
     "    private static Conclusion success() { return null; }\n"
     "    private static Conclusion running() { return null; }\n"
     "    private static Conclusion planningFailure(String r) { return null; }\n"
     "    private static Conclusion executionFailure(Object r) { return null; }\n"
     "    Conclusion a() { return Conclusion.success(); }\n"
     "    Conclusion b() { return Conclusion.running(); }\n"
     "    Conclusion c() { return Conclusion.executionFailure(null); }\n", False),
    # ---- C ① ----
    ("C① 红：原语里 `new MineTask(`", "c",
     "    void f() { clearTask = new MineTask(bot, blocker, scope, budget, profile, grant); }\n", True),
    ("C① 绿：原语不造任何任务", "c",
     "    void f() { miner = new MineBlockRunner(bot, plan, false, grant); }\n", False),
    # ---- C ② ----
    ("C② 红：编排器把原语架空（0 处委托）", "c_orch",
     "    private MineStep step;\n    void f() { step = new MineStep(a, b, c, d, e, f); }\n", True),
    ("C② 绿：编排器反复委托", "c_orch",
     "    void f() { step = step(); step.plan(); step.startExecution(false); step.tick();\n"
     "              var p = step.currentPlan(); var m = step.mineStartPos(); step.tick(); }\n", False),
    # ---- D1 ----
    ("D1 红：原语自己造额度（方法体里也不行）", "d1",
     "    void f() { budget = MiningBudget.forTarget(bot, level, target, true); }\n", True),
    ("D1 红：类内默认额度常量", "d1",
     "    private static final int DEFAULT_BUDGET_TICKS = 600;\n", True),
    ("D1 绿：额度只当构造参数", "d1",
     "    private final MiningBudget budget;\n    void f() { plan(bot, target, budget); }\n", False),
    ("D1 绿：注释里的 `forTarget` 不算命中", "d1",
     "    // 以前这里写的是 MiningBudget.forTarget(bot, level, target, true)，已删。\n"
     "    private final MiningBudget budget;\n", False),
    # ---- D2 ----
    ("D2 红：额度消费点 0 处（原语什么都不烧）", "d2",
     "    void f() { var r = planner.plan(bot, target, budget); }\n", True),
    ("D2 红：额度消费点 2 处", "d2",
     "    void f() { miningPlanner.plan(bot, target, budget); miningPlanner.plan(bot, target, budget); }\n", True),
    ("D2 红：原语里直接烧写入额度", "d2",
     "    void f() { miningPlanner.plan(bot, target, budget); WriteBudget.consumeBreak(bot, l, t, g); }\n", True),
    ("D2 绿：恰好一处、就是具名那一处", "d2",
     "    void f() { var r = miningPlanner.plan(bot, target, budget, p, a); }\n", False),
]

# ---- `step 5b` 的四条判据各带红/绿两臂（同上：每臂只打一个判据函数）----
SELFTEST_CASES_5B: list[tuple[str, str, str, bool]] = [
    # ---- C① ----
    ("C① 红：原语里 `new CollectDropsTask(`", "c1",
     "    void f() { t = new CollectDropsTask(bot, o, s, List.of(), false, 600, null); }\n", True),
    ("C① 绿：原语不造任何任务", "c1",
     "    void f() { runner = new PathRetryRunner(bot, req, 2, \"x\"); }\n", False),
    # ---- C② ----
    ("C② 红：编排器把原语架空（0 处委托）", "c2",
     "    private CollectStep step;\n    void f() { step = new CollectStep(bot, false, p, 200, host); }\n", True),
    ("C② 绿：编排器反复委托", "c2",
     "    void f() { step.begin(ids); if (step.tick(live) == FINISHED) { endCluster(); }"
     " var r = step.reading(); step.cancel(); }\n", False),
    # ---- D1 ----
    ("D1 红：原语自己造额度（方法体里也不行）", "d1b",
     "    void f() { budget = MiningBudget.collecting(bot, level, target); }\n", True),
    ("D1 红：类内默认额度常量（额度词命名）", "d1b",
     "    private static final int SWEEP_BUDGET_TICKS = 200;\n", True),
    ("D1 绿：额度只当构造参数（实例字段不算常量）", "d1b",
     "    private final int sweepBudgetTicks;\n    void f() { plan(bot, target, sweepBudgetTicks); }\n", False),
    # ---- D2 ----
    ("D2 红：额度消费点 0 处（原语什么都不烧）", "d2b",
     "    private boolean sweepBudgetExhausted() {\n        return false;\n    }\n", True),
    ("D2 红：额度消费点 2 处", "d2b",
     "    private boolean sweepBudgetExhausted() {\n        return sweepTicks > sweepBudgetTicks;\n    }\n"
     "    private boolean again() {\n        return sweepTicks > sweepBudgetTicks;\n    }\n", True),
    ("D2 红：比较在具名方法**外**（消费点没名字）", "d2b",
     "    private boolean sweepBudgetExhausted() {\n        return false;\n    }\n"
     "    private boolean used() {\n        return sweepTicks > sweepBudgetTicks;\n    }\n", True),
    ("D2 绿：恰好一处、就在具名方法体内", "d2b",
     "    private boolean sweepBudgetExhausted() {\n        return sweepTicks > sweepBudgetTicks;\n    }\n", False),
]

_CRITERIA_5B = {
    "c1": check_5b_c1_primitive_creates_no_orchestrator,
    "c2": check_5b_c2_orchestrator_delegates,
    "d1b": check_5b_d1_budget_is_injected,
    "d2b": check_5b_d2_named_consumption_sites,
}

_CRITERIA = {
    "a": check_a_no_phase_machine,
    "b": check_b_single_success_exit,
    "c": check_c_primitive_creates_no_task,
    "c_orch": check_c_orchestrator_delegates,
    "d1": check_d1_budget_is_injected,
    "d2": check_d2_named_consumption_sites,
}

# ==================== 注册口的红臂（`4a` 柱②；每臂只打一条判据） ====================

SELFTEST_CASES_REG: list[tuple[str, str, str, bool]] = [
    # ---- ⑤ 反向检查：名字像 step 必须实现 ----
    ("注册⑤ 红：`*Step` 类的声明里没有 `implements Step`", "reg_impl",
     "public final class FooStep {\n}\n", True),
    ("注册⑤ 绿：`implements Step`", "reg_impl",
     "public final class FooStep implements Step {\n}\n", False),
    # ---- ④ 必须有 tick ----
    ("注册④ 红：注册的 step 里没有 `tick(`", "reg_tick",
     "    public void advance() { }\n", True),
    ("注册④ 绿：有 `tick(`", "reg_tick",
     "    public Outcome tick(java.util.List<Object> live) { return null; }\n", False),
    # ---- ⑥ 不许有相位机 ----
    ("注册⑥ 红：注册的 step 里长出相位字段", "reg_phase",
     "    private Phase phase;\n", True),
    ("注册⑥ 绿：注释里提到相位不算命中（剥注释后）", "reg_phase",
     "/** 本类没有 Phase，也没有 phase 字段。 */\n    private int x;\n", False),
    # ---- ① 不许造任务 ----
    ("注册① 红：注册的 step 里 `new FooTask(`", "reg_task",
     "    void f() { inner = new FooTask(bot, scope); }\n", True),
    ("注册① 绿：只造执行器（`MineBlockRunner` 不以 `Task` 结尾）", "reg_task",
     "    void f() { miner = new MineBlockRunner(bot, plan, false, grant); }\n", False),
    # ---- ② 额度只来自构造参数 ----
    ("注册② 红：注册的 step 里 `new MiningBudget(`", "reg_budget",
     "    void f() { b = new MiningBudget(1, 2, true, 3); }\n", True),
    ("注册② 绿：只**读**构造注入的额度", "reg_budget",
     "    void f() { n = budget.maxExtraBreakTicks(); }\n", False),
    # ---- ③ 不许有额度词命名的常量 ----
    ("注册③ 红：额度词命名的 `static final`", "reg_const",
     "    private static final int sweepBudgetTicks = 40;\n", True),
    ("注册③ 绿：非额度词的 `static final`（`CollectStep` 实测那 4 条就是这样）", "reg_const",
     "    private static final double PICKUP_INFLATE_XZ = 1.0D;\n", False),
]

_CRITERIA_REG = {
    "reg_impl": check_reg_implements_step,
    "reg_tick": check_reg_declares_tick,
    "reg_phase": check_reg_no_phase,
    "reg_task": check_reg_no_task_construction,
    "reg_budget": check_reg_budget_injected,
    "reg_const": check_reg_no_quota_constant,
}


def selftest() -> list[str]:
    problems: list[str] = []
    for cases, criteria in ((SELFTEST_CASES, _CRITERIA), (SELFTEST_CASES_5B, _CRITERIA_5B),
                            (SELFTEST_CASES_REG, _CRITERIA_REG)):
        for label, key, snippet, expect_red in cases:
            found = criteria[key](strip_comments_and_strings(snippet))
            is_red = bool(found)
            if is_red != expect_red:
                problems.append(f"红臂失配：{label} ⇒ 期望{'红' if expect_red else '绿'}、实得"
                                f"{'红' if is_red else '绿'}（{found}）")
    return problems


def main() -> int:
    problems = [f"[红臂] {p}" for p in selftest()]

    if not STEP.exists():
        problems.append(f"找不到 {STEP.relative_to(ROOT)} ⇒ 原语被改名/搬走？"
                        f"同步本门禁（`D-466` §五 九拍的落点是 `task/mining/MineStep.java`）")
    if not ORCH.exists():
        problems.append(f"找不到 {ORCH.relative_to(ROOT)} ⇒ 编排器被改名/搬走？同步本门禁")
    if problems:
        print("TASK_ORCHESTRATION_SPLIT_RESULT FAIL")
        for problem in problems:
            print(f"  ✗ {problem}")
        return 1

    if not STEP_5B.exists():
        problems.append(f"找不到 {STEP_5B.relative_to(ROOT)} ⇒ `5b` 的原语被改名/搬走？"
                        f"同步本门禁（`D-493` 拍点 6 `6甲` 的落点是 `task/collecting/CollectStep.java`）")
    if not ORCH_5B.exists():
        problems.append(f"找不到 {ORCH_5B.relative_to(ROOT)} ⇒ `5b` 的编排器被改名/搬走？同步本门禁")
    if problems:
        print("TASK_ORCHESTRATION_SPLIT_RESULT FAIL")
        for problem in problems:
            print(f"  ✗ {problem}")
        return 1

    step_code = strip_comments_and_strings(STEP.read_text(encoding="utf-8"))
    orch_code = strip_comments_and_strings(ORCH.read_text(encoding="utf-8"))
    problems.extend(inspect_step(step_code))
    problems.extend(check_c_orchestrator_delegates(orch_code))
    step_5b = strip_comments_and_strings(STEP_5B.read_text(encoding="utf-8"))
    orch_5b = strip_comments_and_strings(ORCH_5B.read_text(encoding="utf-8"))
    problems.extend(inspect_step_5b(step_5b))
    problems.extend(check_5b_c2_orchestrator_delegates(orch_5b))

    # ⭐ `4a` 柱② 注册口（**自动枚举** `implements Step` 的全部类）
    reg_problems, reg_info = check_step_registration()
    problems.extend(reg_problems)

    if problems:
        print("TASK_ORCHESTRATION_SPLIT_RESULT FAIL")
        for problem in problems:
            print(f"  ✗ {problem}")
        print("  ⇒ 依据：`J-★` 第 6 段 step 5a（`D-466` 十拍 / `D-469` 落地）+ `plan §5.2` 原语三条判据")
        return 1

    print(f"TASK_ORCHESTRATION_SPLIT_RESULT PASS: 原语 `task/mining/MineStep.java` —— "
          f"相位痕迹 0 · 成功出口 {len(SUCCESS_EXIT.findall(step_code))} · "
          f"`new MineTask(` {len(NEW_ORCHESTRATOR.findall(step_code))} · "
          f"额度制造 {len(BUDGET_MANUFACTURE.findall(step_code))} · "
          f"具名额度消费点 {sum(len(re.findall(p, step_code)) for p in BUDGET_CONSUMPTION)}"
          f"（{NAMED_BUDGET_SITES[0]}） · "
          f"编排器委托点 {len(DELEGATION.findall(orch_code))}（下限 {MIN_DELEGATIONS}） · "
          f"红臂 {len(SELFTEST_CASES)}/{len(SELFTEST_CASES)}（A 4 + B 3 + C① 2 + C② 2 + D1 4 + D2 4）")
    print(f"  · `task/collecting/CollectStep.java`（`5b`）—— "
          f"`new CollectDropsTask(` {len(NEW_ORCHESTRATOR_5B.findall(step_5b))} · "
          f"额度制造 {len(BUDGET_MANUFACTURE.findall(step_5b))} · "
          f"具名额度消费点 "
          f"{sum(len(re.findall(p, step_5b)) for p in SWEEP_BUDGET_CONSUMPTION)}"
          f"（{NAMED_BUDGET_SITES_5B[0]}） · "
          f"编排器委托点 {len(DELEGATION_5B.findall(orch_5b))}（下限 {MIN_DELEGATIONS_5B}） · "
          f"红臂 {len(SELFTEST_CASES_5B)}/{len(SELFTEST_CASES_5B)}（C① 2 + C② 2 + D1 3 + D2 4）")
    print("  ⇒ 边界在构建里：原语不许长相位/子任务/额度，编排器不许把原语架空")
    print(f"  · ⭐ 柱② **注册口**（`4a`，用户 2026-09-29 裁 `N3`）—— 自动枚举 `implements Step`："
          f"{len(reg_info['impls'])} 个（下限 {STEP_IMPL_MIN}）："
          + ", ".join(r.split('/')[-1] for r in reg_info['impls']))
    print("    逐类判：有 `tick(` · 无相位机 · 无 `new *Task(` · 无额度制造 · 无额度词 "
          "`static final` · 住 `task/` ｜ 反向检查：`task/**/*Step.java` 未实现且未豁免 ⇒ 红"
          f"（豁免 {reg_info['exempt']} 条，**双向**核对）"
          f" ｜ 红臂 {len(SELFTEST_CASES_REG)}/{len(SELFTEST_CASES_REG)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
