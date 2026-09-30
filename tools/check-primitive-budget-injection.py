#!/usr/bin/env python3
"""`J-★` 第 6 段 **step 2a**（2026-09-27）结构门禁：**任务不许自带额度**。

## 为什么这条规则该存在

`docs/plans/2026-09-27-Job框架定型.md` §2 第 6 件（用户 2026-09-27 裁定采纳）逐字：

> **额度归 Job**：原语**不自带**额度/完成度（`D-455`）
> 「原语自带额度 ⇒ 它本身就是小 Job ⇒ 编排它的 Job **看不见**额度」

实测到的形态（`task/MineTask.java` 的 4 参重载，`D-459` 落地时删掉的那一处）：

    public MineTask(bot, target, scope, grant) {                    // ← 调用方**没**声明额度
        this(bot, target, scope, MiningBudget.forTarget(            // ← 任务**自己造了一个**
                bot, (ServerLevel) bot.level(), target, true), false, grant);
    }

⇒ 两个后果：① 调用方（`RoadBuildTask:164/190`）**从不知道**自己给出去的是
`collectDrops=true` 的额度（"在目标下方放支撑块 + 收掉落物"），那是便利构造器**替它决定**的；
② `Job` 那层"额度归我"的说法**无法成立** —— 只要存在"调用方不说也能用"的入口，额度就有一份
住在原语里。

## 断言（任一不成立 ⇒ 非零退出）

1. **额度制造必须出现在某个方法体里，且那个方法**不是**构造器**（`src/main/java/com/dddgn/alice/task/*.java`，
   顶层，非递归）——
   - 构造器体内 ⇒ 红（`MineTask` 删掉的那一处就是它）；
   - 不在任何方法体里（字段初始化 / 静态初始化块）⇒ 红；
   - ⚠️ 解析必须**跳过 `if`/`for`/`while`/匿名类**等非方法帧 —— 否则
     `public MineTask(...) { if (x) { MiningBudget.forTarget(...); } }` 会把 `if` 当封闭方法 ⇒ **假绿**
     （`tools/check-protection-install-point.py` 的同款教训）。
2. **额度注入面的人口下限**（防"把额度参数整个删掉 ⇒ 门禁假绿"）：
   - ⭐ 扫到的顶层 `task/*.java` 数 **==** `docs/TASK_TOP_LEVEL_FREEZE.txt` 行数（两源对账 —— 换掉了
     原来写死的下限 `MIN_SCANNED_FILES`，因为 `task/` 顶层是**设计成走向 0** 的数）；
   - 带额度形参（`budget`/`quota` 词根）的 `public` 构造器全仓 ≥ `MIN_QUOTA_CTORS`；
   - 人口表里具名的那两个文件（`MineTask.java` · `CollectDropsTask.java`）各 ≥1 个 —— 它们今天各有 4 个
     （删一个必须显式改本文件）。
     ⚠️ `step 5a`（`D-469`）之后 `MineTask.java` **是编排器**、不再是原语，但它的 4 个构造器
     **签名逐字不动**（`D-466` §五）⇒ 它仍是额度**注入面**上的一环，留在人口表里是对的。

## ⚠️ 本门禁**不**覆盖什么（边界写在门禁里，防"绿 = 合规"的误读）

- ⚠️ **扫描根是平的**（`TASK_DIR.glob("*.java")`，只要 `task/` 顶层）⇒ **`step 5a` 切出来的新原语
  `task/mining/MineStep.java` 不在本门禁的射程内**。它的额度来源（必须构造注入 + 类内不得有额度词命名的
  常量 + 消费点恰好 1）由 `tools/check-task-orchestration-split.py` 的判据 **D1/D2** 断言（同刀落地）。
  本门禁的"具名原语"`MineTask` 现在只是**编排器**，它的额度**转发**给原语与子任务。
- **不覆盖"类内自带默认额度常量"** —— 那是同一条判据的另一半（`D-455` ⑧③"类内不得有默认额度常量"）
  ⇒ 实测今天的违规面 = `CollectDropsTask.DEFAULT_TOTAL_BUDGET_TICKS`（`public`，被两个便捷构造器当默认值）
  + 若干**内部机制档**（`CLUSTER_BUDGET_TICKS` 等 —— ⑧③ 的字面只点名"**默认**额度常量"，机制档是否在内待裁）。
  清零默认口要重设计那个 1250 行原语 ⇒ 排期在 **`step 2b`**（甲口径 = 并入 `step 5`；台账第 6 段 `2b` 有四个口径）。
  ⚠️ **不许**把本门禁的绿读成"⑧③ 已经全部合规"。
- **不覆盖"方法体内为子任务派生额度"** —— `step 5a` 之后那两个点（`MineTask.startClear` /
  `tryGainHeight` 里的 `new MineTask(...)`）的语义是"**编排器**给**子编排器**算额度"
  （`D-466` §四.2：子任务要"计划 → 破坏 → 收落物 → 加高 ≤1"，它本来就是小编排）。
  它们**是**方法体、不是"调用方不说也能用"的默认入口 ⇒ 不在本规则里；
  但它与 `MiningProfile` 的"子信封"关系**不对称**（`MiningBudget` 没有"子额度 ⊆ 父额度"这一层），
  记为观察项，未裁。

## 红臂（`--selftest`，每次运行都跑）

合成片段 6 条：构造器里造（红）· `if` 嵌在构造器里（红，**假绿形态**）· 字段初始化里造（红）·
静态块里造（红）· 方法体里造（绿）· 只在注释里出现（绿）。teeth 自带，不需要夹具也不需要真机。

跑法：`python3 tools/check-primitive-budget-injection.py`（已挂在 `tools/check-all.sh`）。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TASK_DIR = ROOT / "src" / "main" / "java" / "com" / "dddgn" / "alice" / "task"

#: 额度**制造**（"调用方不说也能用"的那类入口只可能从这里产生）。
MANUFACTURE_RE = re.compile(
    r"MiningBudget\s*\.\s*(?:forTarget|collecting)\s*\(|new\s+MiningBudget\s*\(")

#: 具名原语（`plan §5.2 ⑧` 的读数表前 5 个里**带额度**的 2 个）：各须 ≥1 个带额度形参的构造器。
EXPECTED_PRIMITIVES = {"MineTask.java", "CollectDropsTask.java"}

#: ⭐ 顶层人口的**对账源**（2026-09-30 `P1` 立家刀把写死的下限换成它）：
#: `docs/TASK_TOP_LEVEL_FREEZE.txt` **就是**「允许住在 `task/` 顶层的全集」（`P2` 单向阀，
#: 每波搬迁**同刀删行**）。⇒ 判据 = 「扫到的顶层文件数 **==** 名单行数」。
#: ⚠️ 为什么必须换：`task/` 顶层是批次 2 ① 里**设计成单调走向 0** 的那个数（`D-550` §2b：`142 → 0`），
#: 而静态下限（原 `MIN_SCANNED_FILES = 130`）在**第一波搬迁**（142 → 103）当场变假红。
#: 换后判据**更强**（两源对账：漏扫 / 绕过单向阀加文件 都报），且**没有会腐烂的手写数**。
FREEZE = ROOT / "docs" / "TASK_TOP_LEVEL_FREEZE.txt"
#: 实测 6（`MineTask` 4 · `CollectDropsTask` 2 —— `5b` 刀② 把收集器的 6 个构造器收成 2 个，
#: 2026-09-27）。⚠️ **余量已用尽**：再少一个（比如把额度形参整个删掉）**就会红** —— 这是故意的，
#: 本下限的用途正是"防把额度注入面抽空 ⇒ 门禁假绿"。
MIN_QUOTA_CTORS = 6

#: 长得像 `name(...)` 但**不是**方法声明的帧（`if (x) {` / `for (...) {` …）。
NOT_A_METHOD = {
    "if", "for", "while", "switch", "catch", "synchronized", "try", "else", "do",
    "return", "new", "assert", "throw", "yield",
}

QUOTA_PARAM_RE = re.compile(r"(?i)\w*(?:budget|quota)\w*")
PUBLIC_CTOR_RE = re.compile(r"public\s+(\w+)\s*\(([^)]*)\)")


def strip_comments_and_literals(text: str) -> str:
    r"""把 `//`、`/* */`、字符串与字符字面量换成空格（**保留换行**，行号仍可算）。

    ⚠️ 必须做：本门禁的靶子 `MiningBudget.forTarget` 在**注释里**大量出现
    （`MineTask` 的 javadoc、本文件的 docstring、`1.4z` 的注释）⇒ 不剥注释的话，
    光靠注释就能把断言"满足"掉 ⇒ **红臂不红**（`D-438` 实测过的教训）。
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


def scan_source(text: str, rel: str) -> list[str]:
    """扫一个源文件 ⇒ `problems`（额度制造不在"非构造器的方法体"里就报）。"""
    code = strip_comments_and_literals(text)
    problems: list[str] = []
    for match in MANUFACTURE_RE.finditer(code):
        line = code.count("\n", 0, match.start()) + 1
        method = enclosing_method(enclosing_frames(code, match.start()))
        where = f"{rel}:{line} `{match.group(0).strip()}`"
        if method is None:
            problems.append(f"{where} 不在任何方法体里（字段初始化 / 静态初始化块）⇒ "
                            f"这是「调用方不说也能用」的额度，必须由调用方注入")
            continue
        name, owner = method
        if owner and name == owner:
            problems.append(f"{where} 造在**构造器** `{owner}(...)` 里 ⇒ 调用方没说额度时任务自己造了一个"
                            f"（`J-★` 第 6 段 step 2a：额度归 Job，原语不自带额度）")
    return problems


def count_quota_ctors(text: str) -> int:
    """带额度形参的 `public` 构造器数（人口下限用）。"""
    total = 0
    for match in PUBLIC_CTOR_RE.finditer(strip_comments_and_literals(text)):
        if QUOTA_PARAM_RE.search(match.group(2)):
            total += 1
    return total


# ==================== 红臂（每次运行都跑） ====================

SELFTEST_CASES: list[tuple[str, str, bool]] = [
    ("构造器里造额度 ⇒ 红", """
class MineTask {
    public MineTask(bot, target, scope, grant) {
        this(bot, target, scope, MiningBudget.forTarget(bot, level, target, true), false, grant);
    }
}
""", True),
    ("`if` 嵌在构造器里 ⇒ 红（假绿形态）", """
class MineTask {
    public MineTask(bot, target, scope, grant) {
        if (grant != null) {
            budget = MiningBudget.collecting(bot, level, target);
        }
        this.budget = budget;
    }
}
""", True),
    ("字段初始化里造 ⇒ 红", """
class MineTask {
    private final MiningBudget budget = MiningBudget.forTarget(bot, level, target, true);
}
""", True),
    ("静态初始化块里造 ⇒ 红", """
class MineTask {
    static {
        FALLBACK = MiningBudget.collecting(bot, level, target);
    }
}
""", True),
    ("注释里的 `forTarget` 不算命中 ⇒ 绿", """
class MineTask {
    /** 以前这里写的是 MiningBudget.forTarget(bot, level, target, true) ⇒ 已删（step 2a）。 */
    public MineTask(bot, target, scope, MiningBudget budget, grant) {
        // MiningBudget.collecting(bot, level, target)  ← 只是说明
        this.budget = budget;
    }
}
""", False),
    # ⚠️ `step 5a`（`D-469`）之后这条臂的**含义变了**（形状没变，**谁在造**变了）：
    # 片段**逐字**取自 `MineTask.startClear`（今天仍是这样写的），而 `MineTask` 现在是**编排器** ⇒
    # 这条绿的含义 = 「**编排器**在方法体里为子编排器派生额度」合法（`D-466` §四.2）。
    # ⚠️ 本门禁**判不出"谁在造"**（它只看"是不是默认入口"）—— "原语自己造额度"由
    # `tools/check-task-orchestration-split.py` 判据 **D1** 单独管（同刀落地）。
    ("编排器在方法体里为子编排器造 ⇒ 绿（本门禁的**边界**：不是「默认入口」）", """
class MineTask {
    private boolean startClear(BlockPos blocker) {
        clearTask = new MineTask(bot, blocker, scope,
                MiningBudget.forTarget(bot, bot.serverLevel(), blocker, false),
                subProfile,
                grant.with(com.dddgn.alice.write.WriteReason.LINE_OF_SIGHT));
        return true;
    }
}
""", False),
]


def selftest() -> list[str]:
    problems: list[str] = []
    for label, source, expect_red in SELFTEST_CASES:
        found = scan_source(source, "<selftest>")
        is_red = bool(found)
        if is_red != expect_red:
            problems.append(f"红臂失配：{label} ⇒ 期望{'红' if expect_red else '绿'}、实得"
                            f"{'红' if is_red else '绿'}（{found}）")
    return problems


def main() -> int:
    problems = [f"[红臂] {p}" for p in selftest()]

    if not TASK_DIR.is_dir():
        print(f"PRIMITIVE_BUDGET_INJECTION_RESULT FAIL: 找不到 {TASK_DIR}")
        return 1

    files = sorted(TASK_DIR.glob("*.java"))
    quota_ctors: dict[str, int] = {}
    for path in files:
        rel = path.relative_to(ROOT / "src" / "main" / "java").as_posix()
        text = path.read_text(encoding="utf-8")
        problems.extend(scan_source(text, rel))
        count = count_quota_ctors(text)
        if count:
            quota_ctors[path.name] = count

    total_ctors = sum(quota_ctors.values())
    # ⭐ 2026-09-30（`P1` 立家刀）：人口下限从**写死的常数**改成**对账**。
    #   理由 —— `task/` 顶层是批次 2 ① 里**设计成要单调走向 0** 的那个数
    #   （`D-550` §2b：进度表 = 顶层计数 `142 → 0`），而静态下限 `MIN_SCANNED_FILES = 130`
    #   因此在**第一波搬迁**（`debug/` 首批 39 类 ⇒ 顶层 142 → 103）当场变成**假红发生器**。
    #   ⇒ 改判「扫到的顶层文件数 == `docs/TASK_TOP_LEVEL_FREEZE.txt` 行数」——
    #   那份名单**就是**「允许住在 `task/` 顶层的全集」（`P2` 单向阀，同刀维护）。
    #   判据**更强**（两源对账，任一被截断/解析崩塌 ⇒ 报）且**不再有会腐烂的手写数**。
    #   ⚠️ `P4` 关门（顶层 == 0）之后本条随门禁一起退役，⛔ 那时不许靠 `0 == 0` 空过。
    if not FREEZE.exists():
        problems.append(f"`{FREEZE.relative_to(ROOT)}` 不存在 ⇒ 顶层人口**没有对账源**"
                        f"（`P2` 单向阀的名单就是允许住在 `task/` 顶层的全集）")
    else:
        frozen = [x for x in FREEZE.read_text(encoding="utf-8").split("\n") if x.strip()]
        if len(files) != len(frozen):
            problems.append(f"顶层 `task/*.java` 扫到 {len(files)} 个，而冻结名单有 {len(frozen)} 行 ⇒ "
                            f"两源不一致（漏扫 / 有人绕过 `P2` 单向阀往 `task/` 顶层加文件）")
    if total_ctors < MIN_QUOTA_CTORS:
        problems.append(f"全仓只有 {total_ctors} 个带额度形参的构造器（下限 {MIN_QUOTA_CTORS}）⇒ "
                        f"额度注入面被抽空（把参数删掉就能让门禁假绿）")
    for expected in sorted(EXPECTED_PRIMITIVES):
        if expected not in quota_ctors:
            problems.append(f"`{expected}` 里一个带额度形参的构造器都没有 ⇒ 该原语的额度注入面被抽掉了"
                            f"（若确实要移除，请显式改本文件的人口表）")

    if problems:
        print("PRIMITIVE_BUDGET_INJECTION_RESULT FAIL")
        for problem in problems:
            print(f"  ✗ {problem}")
        print("  ⇒ 依据：`J-★` 第 6 段 step 2a（`D-459`）+ plan §2 第 6 件（额度归 Job）")
        return 1

    detail = " · ".join(f"{name.split('/')[-1]}×{count}" for name, count in sorted(quota_ctors.items()))
    print(f"PRIMITIVE_BUDGET_INJECTION_RESULT PASS: 顶层 `task/*.java` {len(files)} 个 · "
          f"构造器里造额度 0 处 · 带额度形参的构造器 {total_ctors} 个「{detail}」 · "
          f"红臂 {len(SELFTEST_CASES)}/{len(SELFTEST_CASES)}"
          f"（⚠️ 不含「类内默认额度常量」= step 2b / step 5）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
