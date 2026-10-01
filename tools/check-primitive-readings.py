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
   `new ClassName(` 不算构造器、成员位置的 `ClassName(` 算；常量清单认得跨行值 / 带空格泛型，
   不认注释 / 字符串 / 嵌套类 `static final class`。

## 只打印、不断言的（避免假红，但必须看得见）

⭐ `D-466` 第 4 条：**两个原语里全部 `static final` 常量（名字 + 值）**。
为什么必须有这一节：`额度词数` 的定义是**按名字**匹配（标识符含 `budget|grant|quota|额度`），
所以它**数不到**名字里没有这几个词的额度常量 —— 实测 `MineTask` 的
`MAX_RECOVERY_ATTEMPTS`（卡 `tryReplan`）与 `CHAIN_TIMEOUT_TICKS`（卡 `tickChain`）**两个都数不到**，
`CollectDropsTask` 还有 9 条同族（`MAX_SETTLE_TICKS` / `MAX_REANCHORS` / `PICKUP_WAIT_TICKS` …）。
⇒ `71` 这个数看起来像"额度词总数"，实际是"**名字里带那几个词**的标识符出现次数"（`silent-measurement-failure`）。
⚠️ 打印里那三态标签（`像额度但口径漏掉` / `额度口径命中` / `与额度无关`）**是启发式**：
`MAX_SETTLE_TICKS` 确实是额度，而 `CLUSTER_LINK_DISTANCE` 只是几何 ⇒ **断言它会假红，所以一个字都不断言**。
⭐ **它自己也带一个静默失败模式**：解析崩了会印出一个**短清单**，与"这文件真的就这么点常量"长得一样
⇒ 所以打印里**并排**给出「严格条数 / 宽松候选行数」，两者不等就写 `⚠️⚠️ 解析不全`。
⛔ **但仍然不断言**（`D-466` 第 4 条逐字"不做断言"）：注解成员（`@Deprecated static final int X = 1;`）
是**合法 Java** 而严格正则解析不到 ⇒ 断言它会**假红**。解析器本身改坏 ⇒ 由**合成臂**红（那才是对的落点）。

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
#: 防"方法正则崩了"（⚠️ 这个数曾被宽正则量成 151/175，别退回宽式）。
#: ⚠️ **它会随拆类下降**（`step 5b` 刀②：`CollectDropsTask` 从 48 掉到 26 —— 方法搬进了新原语
#: `task/collecting/CollectStep`）⇒ 它是**下限**（"正则崩了会量出 ~0"），不是"这个数本身"。
#: 实测（2026-09-27，`5b` 刀② 后）：`MineTask 48` · `CollectDropsTask 26`。
MIN_METHODS = 20

#: 额度词：**标识符里含**这些子串（不分大小写）⇒ `MiningBudget` / `Quota` / `clearBudget` 都算。
#: ⚠️ 2026-10-01 刀 4：`WriteGrant` 已改名 `Attribution` ⇒ 它**不再**命中本口径（`grant` 子串没了）。
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


#: `static final` **成员**声明（4 空格缩进，与构造器/方法同一个"成员位置"口径）。
#: 只看名字与值 ⇒ 值里带表达式（`CLUSTER_BUDGET_TICKS / 2`）也照收。
#: ⚠️ 类型允许**内部空格**（`Map<String, Integer>` 这种带空格泛型）——
#: 第一版写 `[\w.<>\[\],]+` ⇒ 那种类型整条解析不到（好在下面那条命门会把它咬出来）。
#: 值用 `[^;]+`（**允许跨行**）：多行常量（`= Set.of(\n …)`）也算一条。
STATIC_FINAL = re.compile(
    r"^ {4}(?:(?:public|private|protected)[ \t]+)?static[ \t]+final[ \t]+"
    r"([\w.<>\[\],]+(?:[ \t]+[\w.<>\[\],]+)*)[ \t]+(\w+)[ \t]*=[ \t]*([^;]+);",
    re.M)
#: 命门用的**宽松候选** = 成员位置、含 `static final`、**且本行有 `=`** 的行数。
#: 为什么必须有 `=`：`static final class Foo {`（嵌套类）也在成员位置，但它**不是常量** ⇒
#: 不加这个条件就会把嵌套类算成"漏解析的常量"（**假红**）。
STATIC_FINAL_LOOSE = re.compile(r"^ {4}[^\n]*\bstatic[ \t]+final\b[^\n]*=", re.M)
#: ⭐ **额度词口径的词汇漏洞**（`D-466` 第 4 条）：这些名字**看着像**限值/额度，
#: 但**不含** `budget|grant|quota|额度` ⇒ `额度词数` 数不到它们。
#: ⚠️ 这是给**人看**的启发式（`MAX_SETTLE_TICKS` 是额度、`CLUSTER_LINK_DISTANCE` 只是几何），
#: **只打印、不断言**（断言它会假红）。
QUOTA_HINT = re.compile(r"(?:^|_)(?:MAX|MIN|LIMIT|CAP)(?:_|$)|_TICKS|_STEPS|_ATTEMPTS|_REANCHORS|_WAIT",
                        re.I)


def static_finals_in(code: str) -> list[tuple[str, str]]:
    """`(名字, 值)` 列表。入参**必须**是 `strip_comments_and_strings` 的产物。"""
    return [(m.group(2), " ".join(m.group(3).split()))
            for m in STATIC_FINAL.finditer(code)]


def quota_like(name: str) -> bool:
    """这个名字"像额度"吗（启发式）。⚠️ **只用于打印** —— 它不是判据。"""
    return bool(QUOTA_HINT.search(name)) and not BUDGET_SUBSTR.search(name)


def budget_words_in(code: str) -> int:
    """入参**必须**是 `strip_comments_and_strings` 的产物（本刀第一版在这里栽过一次：
    自检臂把**原文**传进来 ⇒ 注释里的额度词被数进去 ⇒ 红臂自己假红）。"""
    return len([t for t in IDENT.findall(code) if BUDGET_SUBSTR.search(t)])


def quota_tag(name: str) -> str:
    """打印用的**三态**标签。⚠️ 第一版只写了两态（"像额度" / "命中"）⇒
    `PICKUP_INFLATE_XZ` 这种**既不像额度、也不含额度词**的常数被打成「口径命中」——
    那是**标签说谎**（`silent-measurement-failure` 的同一族：印出来的字与事实不符）。"""
    if quota_like(name):
        return "⚠️ 像额度但口径漏掉"
    if BUDGET_SUBSTR.search(name):
        return "额度口径命中"
    return "与额度无关"


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
        "finals": static_finals_in(code),
        "finals_loose": len(STATIC_FINAL_LOOSE.findall(code)),
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
SELFTEST_CODE: list[tuple[str, str, str, object]] = [    ("注释里的额度词**不计**", "budget", "    // budget grant quota\n    int x = 1;\n", 0),
    ("字符串字面量里的额度词**不计**", "budget", '    String s = "grant budget";\n', 0),
    # ⚠️ 2026-10-01 刀 4：这里原来用 `WriteGrant` 当第二个词；它**改名成 `Attribution`** 之后
    # **不再含** `grant` 子串 ⇒ 本臂当场红（`D-462` 那类"同步表"的又一次现场）。
    # ⭐ 换成 `Quota`（`Quota` 的**下一个名字**）—— 它含 `quota` ⇒ 口径命中，且更贴将来。
    ("代码里的 `MiningBudget` / `Quota` **计**（按子串）", "budget",
     "    MiningBudget b = null;\n    Quota q = null;\n", 2),
    ("成员位置的 `MineTask(` ⇒ 算构造器", "ctors", "    public MineTask(int a) {}\n", 1),
    ("`new MineTask(` ⇒ **不算**构造器", "ctors", "        var t = new MineTask(a);\n", 0),
    ("成员签名行 ⇒ 算 1 个方法", "methods", "    public Status tick() {\n", 1),
    ("⭐ 语句行**不算**方法（本刀第一版宽正则把 `MineTask` 量成 151）", "methods",
     "    if (x) {\n    for (int i = 0; i < 1; i++) {\n    return foo(1);\n    while (a) {\n", 0),
    ("深缩进的语句（8 空格）不算成员方法", "methods", "        return helper(x);\n        if (y) {\n", 0),
]

#: `(label, 片段, 期望的名字列表)` —— 同样走**真实解析函数**。
SELFTEST_FINALS: list[tuple[str, str, list[str]]] = [
    ("`private static final int X = 1;` ⇒ 收", "    private static final int X = 1;\n", ["X"]),
    ("`public static final int X = 1;` ⇒ 收", "    public static final int X = 1;\n", ["X"]),
    ("不带访问修饰符的 `static final` ⇒ 也收", "    static final int X = 1;\n", ["X"]),
    ("⭐ 值里带表达式 / 另一个常量 ⇒ 收", "    private static final int Y = X / 2;\n", ["Y"]),
    ("⭐ **跨行**常量（多行值）⇒ 也要收（否则「解析不到」会被读成「没有常量」）",
     "    public static final Set<E> S = Set.of(\n            A, B);\n", ["S"]),
    ("⭐ **带空格的泛型**类型 ⇒ 也要收（第一版在这里整条漏掉）",
     "    private static final Map<String, Integer> M = Map.of();\n", ["M"]),
    ("⭐ 嵌套类 `static final class` ⇒ **不收**（它不是常量；宽松候选也靠 `=` 把它排除）",
     "    public static final class Foo {\n    }\n", []),
    ("非 static 的 `final` ⇒ **不收**（它不是「类内常量」）", "    private final int x = 1;\n", []),
    ("⭐ 注释里的 `static final` ⇒ **不收**（口径：只看代码）",
     "    // private static final int X = 1;\n    private int y = 0;\n", []),
    ("⭐ 字符串字面量里的 `static final` ⇒ **不收**",
     '    String s = "private static final int X = 1;";\n', []),
    ("方法体内的语句 ⇒ **不收**（不是成员位置）",
     "        if (x) { return 1; }\n", []),
]

#: `(label, 常量名, 是否"像额度"）` —— 词汇漏洞的启发式臂。
SELFTEST_QUOTA_HINT: list[tuple[str, str, bool]] = [
    ("`MAX_RECOVERY_ATTEMPTS` ⇒ 像额度（`MineTask` 实测被口径漏掉的两个之一）", "MAX_RECOVERY_ATTEMPTS", True),
    ("`CHAIN_TIMEOUT_TICKS` ⇒ 像额度（另一个）", "CHAIN_TIMEOUT_TICKS", True),
    ("`MAX_SETTLE_TICKS` ⇒ 像额度（`CollectDropsTask` 同族）", "MAX_SETTLE_TICKS", True),
    ("⭐ `CLUSTER_BUDGET_TICKS` ⇒ **不算「漏洞」**（它已被额度词口径数到）", "CLUSTER_BUDGET_TICKS", False),
    ("`PLAYER_HALF_WIDTH` ⇒ 不像额度（物理常数）", "PLAYER_HALF_WIDTH", False),
    ("`CLUSTER_LINK_DY` ⇒ 不像额度（几何常数）", "CLUSTER_LINK_DY", False),
]

#: `(label, 常量名, 期望标签)` —— 三态标签的自证（第一版两态时 `PICKUP_INFLATE_XZ` 被打成「命中」）。
SELFTEST_TAG: list[tuple[str, str, str]] = [
    ("⭐ `MAX_SETTLE_TICKS` ⇒ 像额度但漏掉", "MAX_SETTLE_TICKS", "⚠️ 像额度但口径漏掉"),
    ("`DEFAULT_TOTAL_BUDGET_TICKS` ⇒ 口径命中", "DEFAULT_TOTAL_BUDGET_TICKS", "额度口径命中"),
    ("⭐ `PICKUP_INFLATE_XZ` ⇒ **与额度无关**（第一版这里说「命中」= 标签说谎）",
     "PICKUP_INFLATE_XZ", "与额度无关"),
]

#: `(label, 片段, 期望严格条数, 期望宽松行数)` —— **命门本身的臂**：
#: 断言的是"两者相等"，所以臂要成对给（尤其"会假红"的形态必须两边都 0）。
SELFTEST_LOOSE: list[tuple[str, str, int, int]] = [
    ("普通常量 ⇒ 1 / 1", "    private static final int X = 1;\n", 1, 1),
    ("跨行值 ⇒ 1 / 1", "    public static final Set<E> S = Set.of(\n            A);\n", 1, 1),
    ("⭐ **嵌套类** ⇒ 0 / 0（宽松靠「本行有 `=`」排除 ⇒ 不许把嵌套类算成漏解析的常量）",
     "    public static final class Foo {\n    }\n", 0, 0),
    ("⭐ **带空格泛型** ⇒ 1 / 1（第一版严格 = 0、宽松 = 1 ⇒ 命门会假红）",
     "    private static final Map<String, Integer> M = Map.of();\n", 1, 1),
    ("非 static 的 `final` ⇒ 0 / 0", "    private final int x = 1;\n", 0, 0),
    ("注释 / 字符串字面量里的 ⇒ 0 / 0",
     '    // private static final int X = 1;\n    String s = "static final int Y = 2;";\n', 0, 0),
]


def selftest() -> list[str]:
    problems: list[str] = []
    for label, body, expect in SELFTEST_ENUM:
        got, _ = enum_constants(body, "Phase")
        if got != expect:
            problems.append(f"枚举臂失配：{label} ⇒ 期望 {expect}、实得 {got}")
    for label, body, expect in SELFTEST_FINALS:
        got = [n for n, _ in static_finals_in(strip_comments_and_strings(body))]
        if got != expect:
            problems.append(f"常量清单臂失配：{label} ⇒ 期望 {expect}、实得 {got}")
    for label, name, expect in SELFTEST_QUOTA_HINT:
        got = quota_like(name)
        if got != expect:
            problems.append(f"额度启发式臂失配：{label} ⇒ 期望 {expect}、实得 {got}")
    for label, name, expect in SELFTEST_TAG:
        got = quota_tag(name)
        if got != expect:
            problems.append(f"标签臂失配：{label} ⇒ 期望「{expect}」、实得「{got}」")
    for label, body, exp_strict, exp_loose in SELFTEST_LOOSE:
        code = strip_comments_and_strings(body)
        got_strict = len(static_finals_in(code))
        got_loose = len(STATIC_FINAL_LOOSE.findall(code))
        if (got_strict, got_loose) != (exp_strict, exp_loose):
            problems.append(f"命门臂失配：{label} ⇒ 期望 严格/宽松 = {exp_strict}/{exp_loose}、"
                            f"实得 {got_strict}/{got_loose}")
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
        # ⚠️ 2026-10-01 波 4（`D-569` R3）：`CollectDropsTask` 已 `task/` → `transfer/`
        # ⇒ 按**类名全树**解析，⛔ 不写死包名（写死的路径一搬就静默失效，`O113` 同族）。
        hits = sorted(SRC.rglob(f"{name}.java"))
        if len(hits) != 1:
            problems.append(f"`{name}.java` 在 alice/ 下命中 {len(hits)} 个（应为 1）"
                            f" —— 改名/搬包？同步本门禁的 `PRIMITIVES`")
            continue
        path = hits[0]
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
        # ⚠️ `D-466` 第 4 条逐字：这条读数「**不做断言**」⇒ 这里**不判红**，只把
        #    「严格条数 vs 宽松行数」并排打印（两者不等 = 有常量没被解析出来，人一眼看得见）。
        #    ⭐ 为什么不能断言：注解成员（`@Deprecated static final int X = 1;`）是**合法 Java**
        #    而严格正则解析不到 ⇒ 断言它会**假红**（用户 2026-09-27 的口径：只断言不会假红的量）。
        #    解析器本身的行为由**合成臂**钉住（见 `SELFTEST_FINALS` / `SELFTEST_LOOSE`）——
        #    正则被改坏会让那些臂红，而不是让真实文件红。

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
    arms = (len(SELFTEST_ENUM) + len(SELFTEST_CODE) + len(SELFTEST_FINALS)
            + len(SELFTEST_QUOTA_HINT) + len(SELFTEST_TAG) + len(SELFTEST_LOOSE))
    print(f"  红臂 {arms}/{arms}（枚举 {len(SELFTEST_ENUM)} + 口径 {len(SELFTEST_CODE)} + "
          f"常量清单 {len(SELFTEST_FINALS)} + 额度启发式 {len(SELFTEST_QUOTA_HINT)} + "
          f"标签 {len(SELFTEST_TAG)} + 命门 {len(SELFTEST_LOOSE)}）· "
          f"自证：**一行写完的枚举与多行同值**（本刀第一版在这里静默量成 1）")

    # ⭐ `D-466` 第 4 条：**"额度词数"是按名字匹配的 ⇒ 有词汇漏洞**。
    #    这一节把两个原语里**全部 `static final` 常量**（名字+值）打印出来，让人能看见漏洞里藏了什么。
    #    ⚠️ 只打印：`像额度` 是**启发式**（`MAX_SETTLE_TICKS` 是额度、`CLUSTER_LINK_DISTANCE` 只是几何）
    #    ⇒ 断言它会假红，所以一个字都不断言（用户 2026-09-27 的口径）。
    print("  ⭐ 只打印（`D-466` 第 4 条）：**全部 `static final` 常量** —— "
          "`额度词数` 是按**名字**匹配的，所以它**数不到**名字里没有 budget|grant|quota|额度 的额度常量")
    for r in table:
        finals = r["finals"]
        missed = [n for n, _ in finals if quota_like(n)]
        # ⭐ 严格条数 / 宽松行数**并排打印**：两者不等 ⇒ 有常量没被解析出来（**不做断言**，见上）
        flag = "" if len(finals) == r["finals_loose"] else \
            f"  ⚠️⚠️ **解析不全**（宽松 {r['finals_loose']} 行 ≠ 严格 {len(finals)} 条 ⇒ 有常量没被解析出来）"
        print(f"  · {r['name']:<17} `static final` 共 {len(finals)} 条"
              f"（宽松候选 {r['finals_loose']} 行）"
              + (f"，其中**口径数不到、但看着像额度**的 {len(missed)} 条：{missed}" if missed
                 else "，没有「看着像额度却被口径漏掉」的") + flag)
        for n, v in finals:
            print(f"      - {n} = {v if len(v) <= 48 else v[:45] + '…'}   [{quota_tag(n)}]")
    return 0


if __name__ == "__main__":
    sys.exit(main())
