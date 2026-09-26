#!/usr/bin/env python3
"""`1.4r`（2026-09-26）结构门禁：**`TaskTargetProtection.begin*` 的安装点不许在构造器里**。

## 为什么这条规则该存在

台账 `1.4r` 把缺陷钉死了（运行期逐字，不只代码级）：`MineJob` / `LumberJob` 的构造器里装了
`D-362` 的任务保护作用域，而生产路径是

    assignJob:664   create()            ← 构造器在这里跑，作用域**装上**
    assignJob:674   session.beginTask() ← BotManager:1998 里 `TaskTargetProtection.end(bot)` **清掉**
    …整条作业运行…                       ← **保护一直是空的**
    MineJob.finish:1001 end(bot)        ← 空操作

⇒ 生产侧「清障不得吃任务目标」这条护栏**从来没生效过**。而电池看不到它 —— `RegressionBatteryTask`
**直驱子任务**（`:877 current = step.factory().get()` + `:735 current.tick()`），从不过 `beginTask`
⇒ 构造器装的作用域活到作业终态、夹具照常绿。这是 `skill §6.9.1 ③` 的形态：
**任何挂在 `BotSession.beginTask` 上的初始化，电池都不会验证**。

修法 = 安装点从构造器挪到**首 tick**（`MineJob.tickOnce` 的 `scopeStarted` 块 / `LumberJob.tickOnce` 的
`protectionStarted` 块 / `FishboneJob.prepare` 本来就是同一形状）⇒ `beginTask` 的清空**天然落在装之前**，
顺序不可能再错。**这个顺序约束无法用行为夹具守住**（夹具根本不走那条路径）⇒ 判据只能是静态门禁
（`D-425`：「死码删除的判据只能是静态门禁」的同一条理由）。

## 断言（任一不成立 ⇒ 非零退出）

1. **不许在构造器里装** —— 任意 `src/main/java` 下的安装点，其**最近的封闭方法**若与封闭类型的简单名
   相同（= Java 构造器）⇒ 红。⚠️ 解析必须**跳过 `if`/`for`/`while`/匿名类**等非方法帧 ——
   否则 `public MineJob(...) { if (x) { begin(...); } }` 会把 `if` 当封闭方法 ⇒ **假绿**。
2. **作业必须在首 tick 装** —— `src/main/java/com/dddgn/alice/job/**` 下的安装点，其封闭方法名必须在
   `{tickOnce, prepare}` 里。新作业形状不同 ⇒ **必须显式来改这里**（这就是"响亮"）。
3. **人口下限**（防"抽空即假绿"）：三个生产作业各 ≥1 个安装点 · 全量安装点 ≥3 · 扫描到的 `.java` ≥400。

## 红臂（`--selftest`，每次运行都跑）

4 个合成片段：构造器里装（红）· `if` 嵌在构造器里（红，**第一版的假绿形态**）· 静态初始化块里装（红）·
`tickOnce` 里装（绿）。teeth 自带、不需要真机也不需要夹具。

跑法：`python3 tools/check-protection-install-point.py`（已挂在 `tools/check-all.sh`）。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src" / "main" / "java"

#: 安装点必须出现在这些方法体里（作业形态 = "首 tick"）。
ALLOWED_JOB_METHODS = {"tickOnce", "prepare"}

#: 人口下限：这三个生产作业**必须**各有一个安装点（删掉一个 ⇒ 必须显式改本表）。
EXPECTED_JOB_FILES = {
    "com/dddgn/alice/job/mine/MineJob.java",
    "com/dddgn/alice/job/lumber/LumberJob.java",
    "com/dddgn/alice/job/fishbone/FishboneJob.java",
}
MIN_INSTALL_POINTS = 3
#: 实测 510 个（2026-09-26）；留足余量，只用来抓"grep 根被搬空 / 解析崩塌"。
MIN_SCANNED_FILES = 400

INSTALL_RE = re.compile(r"TaskTargetProtection\s*\.\s*(begin|beginChannel)\s*\(")

#: 长得像 `name(...)` 但**不是**方法声明的帧（`if (x) {` / `for (...) {` …）。
NOT_A_METHOD = {
    "if", "for", "while", "switch", "catch", "synchronized", "try", "else", "do",
    "return", "new", "assert", "throw", "yield",
}


def strip_comments_and_literals(text: str) -> str:
    r"""把 `//`、`/* */`、字符串与字符字面量换成空格（**保留换行**，行号仍可算）。

    ⚠️ 为什么必须做（`D-438` 的实测教训，`tools/kernel-predicates.py:1662` 同款）：本门禁的靶子
    `TaskTargetProtection.begin*` **就写在注释里**（`MineJob`/`LumberJob` 的说明、本文件的 docstring）
    ⇒ 不剥注释的话，光靠注释就能把断言"满足"掉 ⇒ **红臂不红**。
    """
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                out.append(" ")
                i += 1
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "*":
            i += 2
            while i < n and not (text[i] == "*" and i + 1 < n and text[i + 1] == "/"):
                out.append("\n" if text[i] == "\n" else " ")
                i += 1
            i += 2
            continue
        if c in "\"'":
            quote = c
            out.append(" ")
            i += 1
            while i < n and text[i] != quote:
                if text[i] == "\\":
                    out.append(" ")
                    i += 1
                    if i >= n:
                        break
                out.append("\n" if text[i] == "\n" else " ")
                i += 1
            out.append(" ")
            i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


def classify_header(header: str) -> tuple[str, str]:
    """把一个 `{` 之前的文本分类成 `("type"|"method"|"other", name)`。"""
    h = " ".join(header.split())
    if not h:
        return ("other", "")
    if "new " not in h:
        m = re.search(r"\b(?:class|interface|enum|record)\s+(\w+)", h)
        if m:
            return ("type", m.group(1))
    if h.startswith("new ") or " new " in h or h.endswith("->"):
        return ("other", "")   # 匿名类 / lambda 体
    m = re.search(r"(\w+)\s*\([^;{}]*\)\s*(?:throws[\w\s.,]+)?$", h)
    if not m:
        return ("other", "")
    name = m.group(1)
    if name in NOT_A_METHOD:
        return ("other", "")
    prefix = h[:m.start()].rstrip()
    if prefix.endswith((".", "=", "->", "(")):
        return ("other", "")   # 方法调用 / 赋值右侧 / lambda 参数
    return ("method", name)


def enclosing_frames(code: str, position: int) -> list[tuple[str, str]]:
    """返回 `position` 处的封闭帧栈（从外到内），每项是 `(kind, name)`。"""
    stack: list[list] = []
    boundary = 0
    line = 1
    i = 0
    n = len(code)
    while i < n and i < position:
        c = code[i]
        if c == "\n":
            line += 1
        if c == "{":
            kind, name = classify_header(code[boundary:i])
            stack.append([kind, name, line])
            boundary = i + 1
        elif c == "}":
            if stack:
                stack.pop()
            boundary = i + 1
        elif c == ";":
            boundary = i + 1
        i += 1
    return [(frame[0], frame[1]) for frame in stack]


def enclosing_method(frames: list[tuple[str, str]]) -> tuple[str, str] | None:
    """最近的封闭**方法**帧 `(name, enclosing_type_simple_name)`；没有则 `None`。"""
    kind = name = ""
    for depth in range(len(frames) - 1, -1, -1):
        fkind, fname = frames[depth]
        if fkind == "method" and not kind:
            kind, name = fkind, fname
        if fkind == "type":
            return (name, fname) if name else None
    return (name, "") if name else None


def scan_source(text: str, rel: str) -> tuple[list[str], int]:
    """扫一个源文件 ⇒ `(problems, install_point_count)`。"""
    code = strip_comments_and_literals(text)
    problems: list[str] = []
    count = 0
    for match in INSTALL_RE.finditer(code):
        count += 1
        line = code.count("\n", 0, match.start()) + 1
        method = enclosing_method(enclosing_frames(code, match.start()))
        where = f"{rel}:{line} `TaskTargetProtection.{match.group(1)}`"
        if method is None:
            problems.append(f"{where} 不在任何方法体里（静态初始化块 / 类体）⇒ 必然被 "
                            f"`BotSession.beginTask` 清掉")
            continue
        name, owner = method
        if owner and name == owner:
            problems.append(f"{where} 装在**构造器** `{owner}(...)` 里 ⇒ `BotManager:1998` 的 "
                            f"`beginTask` 清空落在装之后 ⇒ 生产侧保护**一直是空的**（台账 `1.4r`）")
            continue
        if "/job/" in rel.replace("\\", "/") and name not in ALLOWED_JOB_METHODS:
            problems.append(f"{where} 的封闭方法是 `{name}()`，不在 {sorted(ALLOWED_JOB_METHODS)} ⇒ "
                            f"作业必须在**首 tick**装（构造器之后、`beginTask` 之后）")
    return problems, count


# ==================== 红臂（每次运行都跑） ====================

SELFTEST_CASES: list[tuple[str, str, bool]] = [
    ("构造器里装 ⇒ 红", """
class MineJob {
    MineJob() {
        TaskTargetProtection.begin(bot, jobName(), this::p);
    }
}
""", True),
    ("`if` 嵌在构造器里 ⇒ 红（第一版的假绿形态）", """
class MineJob {
    MineJob(int x) {
        if (x > 0) {
            TaskTargetProtection.begin(bot, jobName(), this::p);
        }
    }
}
""", True),
    ("静态初始化块里装 ⇒ 红", """
class MineJob {
    static {
        TaskTargetProtection.begin(bot, jobName(), this::p);
    }
}
""", True),
    ("注释里的 `begin` 不算命中 ⇒ 绿", """
class MineJob {
    // TaskTargetProtection.begin(bot, ...)  ← 只是说明
    private void tickOnce() {
        // TaskTargetProtection.beginChannel(bot, jobName(), cells::contains);
    }
}
""", False),
    ("`tickOnce` 里装 ⇒ 绿", """
class MineJob {
    private Status tickOnce() {
        if (!started) {
            TaskTargetProtection.begin(bot, jobName(), this::p);
        }
        return Status.RUNNING;
    }
}
""", False),
    ("Java 构造器是**类名**、不是 `init` ⇒ 别名不放过", """
class LumberJob {
    LumberJob(int x) {
        TaskTargetProtection.begin(bot, jobName(), p);
    }
}
""", True),
]


def selftest() -> list[str]:
    problems: list[str] = []
    for label, source, expect_red in SELFTEST_CASES:
        found, _ = scan_source(source, "<selftest>")
        is_red = bool(found)
        if is_red != expect_red:
            problems.append(f"红臂失配：{label} ⇒ 期望{'红' if expect_red else '绿'}、实得"
                            f"{'红' if is_red else '绿'}（{found}）")
    return problems


def main() -> int:
    problems = selftest()
    problems = [f"[红臂] {p}" for p in problems]

    if not SRC.exists():
        print(f"PROTECTION_INSTALL_POINT_RESULT FAIL: 找不到 {SRC}")
        return 1

    files = sorted(SRC.rglob("*.java"))
    hits_by_file: dict[str, int] = {}
    for path in files:
        rel = path.relative_to(SRC).as_posix()
        found, count = scan_source(path.read_text(encoding="utf-8"), rel)
        problems.extend(found)
        if count:
            hits_by_file[rel] = count

    total = sum(hits_by_file.values())
    if len(files) < MIN_SCANNED_FILES:
        problems.append(f"只扫到 {len(files)} 个 `.java`（下限 {MIN_SCANNED_FILES}）⇒ 扫描根被搬空 / 解析崩塌")
    if total < MIN_INSTALL_POINTS:
        problems.append(f"全仓只有 {total} 个安装点（下限 {MIN_INSTALL_POINTS}）⇒ 保护被抽空")
    for expected in sorted(EXPECTED_JOB_FILES):
        if expected not in hits_by_file:
            problems.append(f"`{expected}` 里一个安装点都没有 ⇒ 该作业的保护被抽掉了"
                            f"（若确实要移除，请显式改本文件的人口表）")

    if problems:
        print("PROTECTION_INSTALL_POINT_RESULT FAIL")
        for problem in problems:
            print(f"  ✗ {problem}")
        print("  ⇒ 依据：台账 `1.4r`（生产侧保护一直是空的）+ `D-425`（判据只能是静态门禁）")
        return 1

    detail = " · ".join(f"{name.split('/')[-1]}×{count}" for name, count in sorted(hits_by_file.items()))
    print(f"PROTECTION_INSTALL_POINT_RESULT PASS: 安装点 {total} 个全在首 tick「{detail}」"
          f" · 构造器 0 · 扫描 {len(files)} 文件 · 红臂 {len(SELFTEST_CASES)}/{len(SELFTEST_CASES)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
