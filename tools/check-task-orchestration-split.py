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

_CRITERIA = {
    "a": check_a_no_phase_machine,
    "b": check_b_single_success_exit,
    "c": check_c_primitive_creates_no_task,
    "c_orch": check_c_orchestrator_delegates,
    "d1": check_d1_budget_is_injected,
    "d2": check_d2_named_consumption_sites,
}


def selftest() -> list[str]:
    problems: list[str] = []
    for label, key, snippet, expect_red in SELFTEST_CASES:
        found = _CRITERIA[key](strip_comments_and_strings(snippet))
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

    step_code = strip_comments_and_strings(STEP.read_text(encoding="utf-8"))
    orch_code = strip_comments_and_strings(ORCH.read_text(encoding="utf-8"))
    problems.extend(inspect_step(step_code))
    problems.extend(check_c_orchestrator_delegates(orch_code))

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
    print("  ⇒ 边界在构建里：原语不许长相位/子任务/额度，编排器不许把原语架空")
    return 0


if __name__ == "__main__":
    sys.exit(main())
