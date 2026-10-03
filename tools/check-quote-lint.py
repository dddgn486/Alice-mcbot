#!/usr/bin/env python3
"""裸引号检查器 —— ⛔ 不许在 Python 字符串里嵌 ASCII 双引号包中文词。

## ⭐ 为什么有这个东西（出处 = 本项目的真实损失，⛔ 不是"洁癖"）

2026-10-02 那一天，主工作流在 **同一个文件**（`tools/plan-doc-refactor.py`）上
**连续 4 次**犯同一个错：在 `A("…")` 的正文里用 ASCII `"` 去包一个中文词，例如

    A("… 判据必须**自带"做完怎么知道对了"** …")

⇒ 字符串**提前闭合** ⇒ `SyntaxError`。⚠️ 而它的恶毒之处在于：
**写的时候"看着像对的"**，⛔ 只在 `py_compile` 那一刻暴露；
而本项目里"那一刻"=`check-all`，也就是说 ⛔ **要跑整套门禁才知道自己写坏了**。

⭐ 这正是 `docs/HANDOVER.md` §G 记的那一族：**假前提/静默错写进地基**。
⇒ 把它变成**一条能让构建失败的检查**（本项目纪律：门禁 > 散文）。

## 判据（两道，都机械可判）

| 道 | 抓什么 | 为什么它是"这一族"的正解 |
|---|---|---|
| **A 语法臂** | 每个被扫的 `.py` 必须 `ast.parse` 通过 | ⭐ **4 次真错 100% 命中这一道** —— 不需要去猜"哪里该用「」" |
| **B 可疑臂** | 一行里 `A('…"…')` （单引号字面量内嵌裸 `"`） | ⚠️ 语法上侥幸通过、但**语义上几乎肯定是同一个错** ⇒ 报红 | </br>`quote-lint: example`

## ⛔ 两道都过不了的情况（诚实边界）

1. ⛔ **它抓不出"用对了引号但语义写错"** —— 它只管引号形状，⛔ 不是语义检查。
2. ⛔ **它不扫 `.sh`／`.md`** —— 那些语言的引号规则不同（本检查器只管 Python）。
3. ⚠️ **它可以被豁免标记绕过**（标记字面见 `EXEMPT` 常量）—— ⭐ 故意留的（本文件自己的示例要用它）；
   ⛔ 但它**只豁免那一行**，且**豁免本身会被计数打印**（⛔ 不静默）。

用法：`python3 tools/check-quote-lint.py`（挂 `tools/check-all.sh`）
      `python3 tools/check-quote-lint.py --selftest`（注入自证）
"""

from __future__ import annotations

import argparse
import ast
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

#: ⚠️ 人口下限：扫到的 `.py` 少于此 ⇒ **响亮失败**（目录搬了 / 命名变了 ⇒
#: 「全绿」读起来完全正常 —— 这正是本仓反复踩的那族错）。2026-10-02 实测 = 见 --drift。
PY_FLOOR = 40   # ⚠️ .py ＋ .sh 合计（2026-10-02 实测 55＋）

#: ⭐ 豁免标记：**逐行**生效。用来让本文件自己的示例能存在。
#: ⛔ 豁免会被计数并打印 —— 豁免多到失控时人能当场看见。
EXEMPT = "quote-lint: example"

#: B 道：单引号字面量里嵌裸双引号
SUSPECT = re.compile(r"""A\(\s*'[^']*"[^']*'\s*\)""")


def scan(path: Path) -> list[str]:
    """返回该文件的问题列表（空 = 过）。"""
    try:
        text = path.read_text(encoding="utf-8")
    except Exception as e:                      # pragma: no cover
        return [f"{path}: ⛔ 读不出来（{type(e).__name__}）"]

    problems: list[str] = []
    lines = text.splitlines()
    exempt = [i for i, l in enumerate(lines, 1) if EXEMPT in l]
    # ⚠️ 豁免**逐行**生效：标记所在行 ＋ 它的**紧邻下一行**。
    #    ⭐ 为什么带"下一行"：本文件是 md 文档写在 docstring 里，
    #    所以示例行（``）与标记（md 表格行）是**分开的两行**。
    #    ⛔ 这是**故意**留的最小口子 —— 且豁免会被 `run()` 计数打印（⛔ 不静默）。
    skip = set()
    for i in exempt:
        skip.add(i)
        skip.add(i + 1)

    if path.suffix == ".sh":
        # ⚠️ shell：只做 `bash -n`。⛔ **不查裸引号**（引号规则不同，见 `collect()` 的注释）。
        import subprocess
        r = subprocess.run(["bash", "-n", str(path)], capture_output=True, text=True)
        if r.returncode != 0:
            problems.append(f"{path} ⛔ **`bash -n` 过不去** —— {r.stderr.strip().splitlines()[0][:120]}")
        return problems

    try:
        ast.parse(text)
    except SyntaxError as e:
        if (e.lineno or 0) not in skip:
            problems.append(
                f"{path}:{e.lineno} ⛔ **语法过不去** —— {e.msg}。"
                f"⭐ 多半是字符串里嵌了裸 ASCII 双引号 ⇒ 换成「」"
            )
    for i, l in enumerate(lines, 1):
        if i in skip:
            continue
        if SUSPECT.search(l):
            problems.append(f"{path}:{i} ⛔ 可疑：单引号字面量里嵌了裸双引号 ⇒ 换成「」")
    return problems


def collect() -> list[Path]:
    """扫什么：`tools/` 下的 **`.py`**（语法臂）＋ **`.sh`**（`bash -n` 臂）。

    ⚠️ **范围是本检查器最该被质疑的地方**，如实写在这里：
    - ✅ `tools/*.py` —— 语法臂 `ast.parse`（⭐ 4 次真错 100% 命中这道）
    - ✅ `tools/*.sh` —— 只做 `bash -n`（⚠️ **抓不出裸引号**：shell 的引号规则是另一套，
      ⛔ 本检查器**不假装**能管它。这一条是**诚实的弱项**，不是遗漏）
    - ⛔ `tools/*.mjs` —— **没扫**（今天有；Node 语法臂需要 `node --check`，未接）
    - ⛔ `src/` 是 Java · `docs/` 是 md —— 不适用
    ⇒ 改范围 = 改判据 = 🔴 档。
    """
    return sorted(list((ROOT / "tools").glob("*.py")) + list((ROOT / "tools").glob("*.sh")))


def run() -> int:
    files = collect()
    problems: list[str] = []
    n_exempt = 0

    if len(files) < PY_FLOOR:
        print(f"QUOTE_LINT_RESULT FAIL: 只扫到 {len(files)} 个 .py/.sh < 人口下限 {PY_FLOOR} "
              f"⇒ 解析/范围崩塌（扫不到就报绿的入口，不放过）")
        return 1

    for p in files:
        problems.extend(scan(p))
        n_exempt += sum(1 for l in p.read_text(encoding="utf-8").splitlines() if EXEMPT in l)

    if problems:
        print(f"QUOTE_LINT_RESULT FAIL: {len(problems)} 处")
        for x in problems:
            print(f"  [FAIL] {x}")
        return 1

    print(f"QUOTE_LINT_RESULT PASS: 扫了 {len(files)} 个 .py/.sh · 裸引号 0 处 · "
          f"豁免行 {n_exempt}（⭐ 豁免逐行生效，⛔ 不静默）")
    return 0


def selftest() -> int:
    """注入自证 —— ⭐ 「能过」≠「能抓」。

    ⚠️ 臂 A 用的是**本会话真犯过的那 4 个错里的 2 个**（逐字），
    ⛔ 不是编的例子 —— 编的例子只能证明"我编得像"，证不了"它抓得住真的"。
    """
    import tempfile

    arms: list[tuple[str, str, bool, str]] = []
    with tempfile.TemporaryDirectory() as td:
        tdp = Path(td)
        # 臂 A：真错 #1（`W7-6` 那行，逐字）
        f1 = tdp / "a.py"
        f1.write_text('A("| 判据必须**自带"做完怎么知道对了"**；")\n', encoding="utf-8")
        arms.append(("A 真错#1（ASCII 引号包中文词）", str(f1), True, "语法过不去"))
        # 臂 B：真错 #2（`§五″` 标题那行，逐字）
        f2 = tdp / "b.py"
        f2.write_text('A("## 五″ · 但"往哪放"仍待用户裁")\n', encoding="utf-8")
        arms.append(("B 真错#2（同上，另一行）", str(f2), True, "语法过不去"))
        # 臂 C：对照片 —— 用「」的好写法，必须**安静**
        f3 = tdp / "c.py"
        f3.write_text('A("| 判据必须**自带「做完怎么知道对了」**；")\n', encoding="utf-8")
        arms.append(("C 对照（用「」）", str(f3), False, ""))
        # 臂 D：B 道（语法侥幸通过但语义可疑）
        f4 = tdp / "d.py"
        f4.write_text("A('x \" y')\n", encoding="utf-8")  # quote-lint: example
        arms.append(("D 可疑臂（表内嵌裸引号）", str(f4), True, "可疑"))

        bad = 0
        for name, path, want_red, want_text in arms:
            got = scan(Path(path))
            is_red = bool(got)
            ok = (is_red == want_red) and (want_text in " ".join(got) if want_red else True)
            bad += not ok
            show = got[0].split("：", 1)[-1][:70] if got else "（安静）"
            print(f"  [{'PASS' if ok else 'FAIL'}] {name} ⇒ "
                  f"{'期望红' if want_red else '期望安静'}，实际：{show}")

    # 臂 E：人口下限（范围崩塌必须响亮）
    global PY_FLOOR
    old = PY_FLOOR
    PY_FLOOR = 10 ** 6
    import io
    from contextlib import redirect_stdout
    buf = io.StringIO()
    with redirect_stdout(buf):
        rc = run()
    PY_FLOOR = old
    ok = rc == 1 and "人口下限" in buf.getvalue()
    bad += not ok
    print(f"  [{'PASS' if ok else 'FAIL'}] E 范围崩塌 ⇒ 期望红且含「人口下限」")

    print(f"QUOTE_LINT_SELFTEST {'PASS' if not bad else 'FAIL'}: arms=5 failed={bad}")
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true", help="五臂注入自证")
    a = ap.parse_args()
    if a.selftest:
        return selftest()
    return run()


if __name__ == "__main__":
    sys.exit(main())
