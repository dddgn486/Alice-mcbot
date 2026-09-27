#!/usr/bin/env python3
"""`J-★` 第 6 段 **step 5**（2026-09-27）读数门禁：**两个胖原语的 4 个验收读数**。

## 为什么这条规则该存在

`step 5` 要拆 `MineTask` / `CollectDropsTask`，验收面是**读数的趋势**
（`Phase 值数 / 额度词数 / 构造器数 / 行数`，`D-457` §5.2 ⑧ + 用户 2026-09-27 裁定 A）。
但 2026-09-27 实测发现：**台账里登记的那组数不可复现** ——
`MineTask 8/47/5/975` · `CollectDropsTask 0/16/6/1250`，试了 **11 种口径**都得不到 `47 / 16`；
且 `975` 行 / `5` 构造器是 `step 2a` **删构造器之前**的数。
⇒ 与 `D-460`/`D-461` 的"读数口径错"同族：**数字像真的、但没有定义**（`silent-measurement-failure`）。

⭐ 更危险的是**这个读数本身的静默失败模式**：`MineTask:43` 的枚举是**一行写完**的
（`private enum Phase { EVALUATING, CLEAR, … }`）—— 按"每个常量一行"写的正则**静默返回 1**
（本刀第一版实测就踩了，量出 `Phase 值数 = 1`）。而 `step 5` 之后**相位值数下降正是目标**，
⇒ **"正则崩了"与"相位真的没了"会印出同一个 0**。所以本门禁的核心不是"打印 4 个数"，
而是**把定义钉死 + 让解析失败与真的为零分得开**。

## 单一定义（照抄进任何后续读数；改定义 = 必须改本文件）

| 读数 | 定义 |
|---|---|
| `行数` | `wc -l` 等价（`\n` 计数） |
| `Phase 值数` | 类内嵌 `enum Phase` 的**常量数**（`{...}` 体里顶层 `,`/`;` 分隔的 `[A-Z][A-Z0-9_]*`）；⚠️ 一行写完与多行**同源** |
| `额度词数` | **代码里**（去注释 / 去字符串字面量）**标识符含** `budget|grant|quota|额度`（不分大小写）的**出现次数** |
| `构造器数` | **成员位置**（4 空格缩进 + 可带修饰符）的 `ClassName(` 声明数（`new ClassName(` 不算） |

## 断言（任一不成立 ⇒ 非零退出）

⭐ **范围（用户 2026-09-27 拍）**：只断言"**不会假红**"的量 ——
**文件存在 / 方法清点非空 / 解析与引用自洽 / 口径自证**。
⛔ **不**断言这 4 个读数的**目标值**（用户原话："值数下降是**结果**，不是判据"）。
⇒ 所以 `Phase 值数` 从 8 降到 0 **不会**让本门禁红；**但"引用非 0 而声明解析失败"会红**（那才是解析崩了）。

1. **两个命名原语存在**，且各自 `行数 ≥ MIN_LINES`、方法清点 `≥ MIN_METHODS`
   （防"文件被搬走 / 被截断 / 正则崩了 ⇒ 全 0 假绿"）。
2. **`Phase` 自洽**（本门禁的命门）：若文件里 `Phase.` 引用数 > 0，则**必须**解析出 `enum Phase` 声明；
   解析不出来 ⇒ 红（**"解析失败"不许与"真的是 0"混为一谈**）。反之引用 = 0 且无声明 = 合法（真的没有相位机）。
3. **口径自证**（合成臂，见下）：一行的枚举与多行的枚举**同值**；注释/字符串里的额度词**不计**；
   `new ClassName(` 不算构造器、成员位置的 `ClassName(` 算。

## 只打印、不断言的（避免假红，但必须看得见）

`Phase` 与 `budget|grant|quota|completed` **同行**的出现数（= `plan §2.2` 禁令② 的可 grep 形态）。
⚠️ 它今天 = **0 处**，但那是"一行一语句"这个**格式的副产品**，不是"相位不承载额度"的证明；
⚠️ 且它的假红模式 = "两个语句写在一行" ⇒ 用户 2026-09-27 拍的范围里只打印不断言。
`Phase` 的用法形态（赋值 / 比较 / 其它）同样只打印 —— 禁令①③ 要的是**设计 review**，不是正则。

跑法：`python3 tools/check-primitive-readings.py`（已挂在 `tools/check-all.sh`）。
"""

from __future__ import annotations

import hashlib
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice"

#: 本步要拆的两个"胖原语"（`D-457` §5.2 ⑧ 点名的两个）。
PRIMITIVES = ("MineTask", "CollectDropsTask")

#: 防"文件被搬走 / 被截断 / 正则崩了"（实测 970 / 1251）。
MIN_LINES = 200
#: 防"方法正则崩了"（**实测 48 / 48** —— ⚠️ 这个数曾被宽正则量成 151/175，别退回宽式）。
MIN_METHODS = 30

#: 额度词：**标识符里含**这些子串（不分大小写）⇒ `MiningBudget` / `WriteGrant` / `clearBudget` 都算。
BUDGET_SUBSTR = re.compile(r"(budget|grant|quota|额度)", re.I)
#: 禁令② 的探针（`plan §2.2`）：`Phase` 与这些词**同行**。
PHASE_WORD = re.compile(r"(budget|grant|quota|completed)", re.I)

IDENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]*|额度")
#: ⚠️ 第一版这里是宽正则（`^    ... \w+\s*\(`）⇒ 把 `if (` / `for (` / `return foo(` 全数进去，
#: 实测 `MineTask` 量成 **151** 个"方法"（真值 48）⇒ **下限断言形同虚设**（`D-460`/`D-461` 的"判据太糙"同族）。
#: 现在收紧成：**恰 4 空格缩进**（成员位置）+ 类型序列 + 名字 + `(`，且首 token 不是语句关键字。
MODS = r"(?:(?:public|private|protected|static|final|synchronized|abstract|default)\s+)*"
#: 语句关键字：成员位置上不该出现（防「一行写完的方法体 / 字段 lambda」混进来）。
STATEMENT_WORDS = {"if", "for", "while", "switch", "catch", "return", "throw", "new",
                   "assert", "else", "do", "try", "case", "yield"}
BUDGET_TOKENS = {"budget", "grant", "quota", "writegrant", "writebudget", "writeaudit"}


def strip_comments_and_strings(text: str) -> str:
    """去注释 + 去字符串/字符字面量，**保留换行**（口径：读数只看代码）。"""
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


def brace_body(code: str, decl_end: int) -> str | None:
    """从 `decl_end` 之后的第一个 `{` 起取**配对**的 `{...}` 体（支持一行写完的枚举）。"""
    start = code.find("{", decl_end)
    if start < 0:
        return None
    depth, i, n = 0, start, len(code)
    while i < n:
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                return code[start + 1:i]
        i += 1
    return None


def enum_constants(code: str, enum_name: str) -> tuple[int | None, int]:
    """返回 `(常量数, 声明位置)`；`常量数 = None` ⇒ **没有**这个枚举声明（≠ 0 个常量）。
    入参同 `budget_words_in`：**已去注释/字符串**的代码。"""
    m = re.search(r"\benum\s+" + enum_name + r"\b", code)
    if not m:
        return None, 0
    body = brace_body(code, m.end())
    if body is None:
        return None, m.start()
    head = body.split(";", 1)[0]  # 去掉 `;` 之后的构造器/方法体
    names = [n for n in re.findall(r"(?<![\w])([A-Z][A-Z0-9_]*)(?![\w])", head)]
    return len(names), m.start()


def constructors_in(code: str, class_name: str) -> int:
    """成员位置的 `ClassName(` 声明数（`new ClassName(` 与调用**不算** —— 靠 4 空格缩进 + 无 `.`）。
    入参同 `budget_words_in`：**已去注释/字符串**的代码。"""
    return len(re.findall(r"^    (?:public |private |protected )*" + class_name + r"\s*\(",
                          code, re.M))


def methods_in(code: str) -> list[str]:
    """成员位置的方法/构造器**名字**（恰 4 空格缩进 + 类型序列 + 名字 + `(`）。

    ⚠️ 过滤要看**行的第一个 token**，不是名字本身：`    return foo(1);` 的名字是 `foo`（不在关键字表里），
    但它是 `return` 语句 —— 本刀第二版就在这儿把语句数成了方法（自检臂当场抓出）。
    入参同 `budget_words_in`：**已去注释/字符串**的代码。
    """
    names: list[str] = []
    for line in code.split("\n"):
        if not line.startswith("    ") or line.startswith("     "):
            continue
        m = re.match(r"^    " + MODS + r"(?:[\w.<>\[\],?]+\s+)+(\w+)\s*\(", line)
        if not m:
            continue
        first = re.match(r"^    " + MODS + r"(\w+)", line)
        if first and first.group(1) in STATEMENT_WORDS:
            continue
        names.append(m.group(1))
    return names


def budget_words_in(code: str) -> int:
    """入参**必须**是 `strip_comments_and_strings` 的产物（本刀第一版在这里栽过一次：
    自检臂把**原文**传进来 ⇒ 注释里的额度词被数进去 ⇒ 红臂自己假红）。"""
    return len([t for t in IDENT.findall(code) if BUDGET_SUBSTR.search(t)])


def readings(path: Path) -> dict[str, object]:
    raw = path.read_text(encoding="utf-8")
    code = strip_comments_and_strings(raw)
    name = path.stem
    phase_n, _ = enum_constants(code, "Phase")
    lines = code.split("\n")
    same_line = [i + 1 for i, l in enumerate(lines) if "Phase" in l and PHASE_WORD.search(l)]
    forms = {"赋值": 0, "比较": 0, "其它": 0}
    for l in lines:
        if "Phase" not in l:
            continue
        if re.search(r"[=\s]phase\s*=\s*Phase\.", l):
            forms["赋值"] += 1
        elif re.search(r"[=!]=\s*Phase\.", l):
            forms["比较"] += 1
        else:
            forms["其它"] += 1
    return {
        "name": name,
        "lines": raw.count("\n") + (0 if raw.endswith("\n") else 1),
        "phase": phase_n,                       # None = 没有枚举声明（不是 0 个常量）
        "phase_refs": len(re.findall(r"(?<![\w.])Phase\.", code)),
        "budget": budget_words_in(code),
        "ctors": constructors_in(code, name),
        "methods": len(methods_in(code)),
        "same_line": same_line,
        "forms": forms,
        "sha": hashlib.sha256(raw.encode("utf-8")).hexdigest()[:16],
    }


# ==================== 红臂（每次运行都跑） ====================

#: `(label, 片段, 期望值)` —— 全部走**真实解析函数**，不是另写一套。
SELFTEST_ENUM: list[tuple[str, str, int | None]] = [
    ("⭐ 一行写完的枚举 ⇒ 3（本刀踩过的坑：按「每常量一行」写会静默得 1）",
     "    private enum Phase { A, B, C }\n", 3),
    ("同一组常量的**多行**写法 ⇒ 也是 3（两形态必须同源）",
     "    private enum Phase {\n        A,\n        B,\n        C;\n    }\n", 3),
    ("带构造器参数的枚举 ⇒ 只数常量",
     "    private enum Phase {\n        A(1), B(2);\n        Phase(int x) {}\n    }\n", 2),
    ("空枚举 ⇒ **0**（合法：真的是零个常量）",
     "    private enum Phase { }\n", 0),
    ("没有枚举声明 ⇒ **None**（≠ 0：这是「解析不到」，必须与零分开）",
     "    private int tick() { return 1; }\n", None),
]
SELFTEST_CODE: list[tuple[str, str, str, object]] = [
    ("注释里的额度词**不计**", "budget", "    // budget grant quota\n    int x = 1;\n", 0),
    ("字符串字面量里的额度词**不计**", "budget", '    String s = "grant budget";\n', 0),
    ("代码里的 `MiningBudget` / `WriteGrant` **计**（按子串）", "budget",
     "    MiningBudget b = null;\n    WriteGrant g = null;\n", 2),
    ("成员位置的 `MineTask(` ⇒ 算构造器", "ctors", "    public MineTask(int a) {}\n", 1),
    ("`new MineTask(` ⇒ **不算**构造器", "ctors", "        var t = new MineTask(a);\n", 0),
    ("成员签名行 ⇒ 算 1 个方法", "methods", "    public Status tick() {\n", 1),
    ("⭐ 语句行**不算**方法（本刀第一版宽正则把 `MineTask` 量成 151）", "methods",
     "    if (x) {\n    for (int i = 0; i < 1; i++) {\n    return foo(1);\n    while (a) {\n", 0),
    ("深缩进的语句（8 空格）不算成员方法", "methods", "        return helper(x);\n        if (y) {\n", 0),
]


def selftest() -> list[str]:
    problems: list[str] = []
    for label, body, expect in SELFTEST_ENUM:
        got, _ = enum_constants(body, "Phase")
        if got != expect:
            problems.append(f"枚举臂失配：{label} ⇒ 期望 {expect}、实得 {got}")
    for label, kind, body, expect in SELFTEST_CODE:
        code = strip_comments_and_strings(body)   # ⭐ 与 main() 同源：读数只看**代码**
        if kind == "budget":
            got = budget_words_in(code)
        elif kind == "ctors":
            got = constructors_in(code, "MineTask")
        else:
            got = len(methods_in(code))
        if got != expect:
            problems.append(f"口径臂失配：{label} ⇒ 期望 {expect}、实得 {got}")
    return problems


def main() -> int:
    problems = [f"[红臂] {p}" for p in selftest()]

    if not SRC.is_dir():
        print(f"PRIMITIVE_READINGS_RESULT FAIL: 找不到 {SRC}")
        return 1

    table = []
    for name in PRIMITIVES:
        path = SRC / "task" / f"{name}.java"
        if not path.exists():
            problems.append(f"`task/{name}.java` 不存在 —— 改名？同步本门禁的 `PRIMITIVES`")
            continue
        r = readings(path)
        table.append(r)
        if r["lines"] < MIN_LINES:
            problems.append(f"`{name}` 只有 {r['lines']} 行（下限 {MIN_LINES}）⇒ 文件被搬走/截断？")
        if r["methods"] < MIN_METHODS:
            problems.append(f"`{name}` 只清点到 {r['methods']} 个方法（下限 {MIN_METHODS}）⇒ "
                            f"方法正则崩了（**这会静默影响下面所有读数**）")
        # ⭐ 命门：引用非 0 而声明解析不到 ⇒ 必须红（"解析失败" ≠ "真的是 0"）
        if r["phase_refs"] > 0 and r["phase"] is None:
            problems.append(f"`{name}` 有 {r['phase_refs']} 处 `Phase.` 引用，却解析不到 `enum Phase` 声明 "
                            f"⇒ **解析失败**（不是「相位已删」；二者会印出同一个 0）")

    if problems:
        print("PRIMITIVE_READINGS_RESULT FAIL")
        for problem in problems:
            print(f"  ✗ {problem}")
        print("  ⇒ 依据：`J-★` 第 6 段 step 5（`D-457` §5.2 ⑧ + 2026-09-27 用户裁定 A：只断言不会假红的量）")
        return 1

    print("PRIMITIVE_READINGS_RESULT PASS")
    print("  定义：行数=`\\n` 计数 · Phase 值数=类内嵌 enum 的常量数（一行写完与多行同源）· "
          "额度词数=**代码里**标识符含 `budget|grant|quota|额度` 的出现次数 · "
          "构造器数=成员位置 `ClassName(`（`new` 不算）· 方法数=成员位置签名行数（首 token 非语句关键字）")
    for r in table:
        phase = "无 `Phase` 枚举（合法）" if r["phase"] is None else str(r["phase"])
        print(f"  · {r['name']:<17} 行数={r['lines']:<5} Phase 值数={phase:<12} "
              f"额度词数={r['budget']:<4} 构造器数={r['ctors']:<3} 方法数={r['methods']:<3} "
              f"`Phase.` 引用={r['phase_refs']:<3} sha256:{r['sha']}")
    print("  ⚠️ 以上**只断言「不会假红」的量**（存在/下限/解析自洽/口径自证）—— "
          "**不断言目标值**（用户 2026-09-27：值数下降是结果，不是判据）")
    for r in table:
        f = r["forms"]
        print(f"  · {r['name']:<17} 只打印（禁令② 探针）：`Phase` 与 budget|grant|quota|completed 同行 "
              f"**{len(r['same_line'])} 处**「同行 0 处是『一行一语句』格式的副产品，不是判据」· "
              f"`Phase` 形态 赋值={f['赋值']} 比较={f['比较']} 其它={f['其它']}"
              + (f" · 同行行号 {r['same_line']}" if r["same_line"] else ""))
    arms = len(SELFTEST_ENUM) + len(SELFTEST_CODE)
    print(f"  红臂 {arms}/{arms}（枚举 {len(SELFTEST_ENUM)} + 口径 {len(SELFTEST_CODE)}）· "
          f"自证：**一行写完的枚举与多行同值**（本刀第一版在这里静默量成 1）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
