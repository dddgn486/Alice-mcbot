#!/usr/bin/env python3
"""门禁：**「类标」形态**（2026-10-05 立；出处 = 咨询回执 `002` ＋ 开发者批「批 #2」）。

⭐⭐ **为什么有它**：草案 `§D′-9` 的 **9.3b** 定了「类标」形态（件头 blockquote 三字段：
`类标`／`沿革`／`裁定`），而 ⛔ **在那之前全仓 0 格式 / 0 载体 / 0 门禁** ——
⭐ 而「有类标」正是草案 `§P-8` **第一档**判据三条之一（有家 · 有引用面 · **有类标**）
⇒ ⛔ **不立它，第一档就判不动**（= 批 2 开不了工）。

判据（**三条**，⭐ 全部**静态可判**，⛔ 不需要 git 历史）：

| # | 判据 | 它防什么 |
|---|---|---|
| **① 结构性** | 件里带了 `**沿革**` ⇒ 必须同时带 `**类标**` | 防**半写**（写了理由，却不知道它是哪一类） |
| **② 取值合法** | `**类标**` 里的类号 ⇒ 必须是 `①`–`⑦` 之一 | 防手误打成 `⑧`／`0`／漏写 |
| **③ 欠账外必须有** | 被覆盖的件若**不在欠账名单**里，就必须带 `**类标**` | ⭐ 防**新增件没人管** |

⚠️ ⭐ **判据 ③ 用的是 `check-redline-gates` 那套「欠账白名单」**（`tools/doc-class-mark-baseline.tsv`）：
   存量件**全部**先登记成欠账（⛔ **存量不追溯** —— 回执 `002` 的 `Q3` 明写，合本仓「新写的东西照做」纪律），
   ⭐ 而那份名单**只许变短**（人口下限会抓"被删空"）。

⚠️ **它判不了什么**（如实记）：
  1. ⛔ 判不了「这个件的类标**对不对**」（那是人判）—— ⭐ 它只管**形态与取值**，⛔ **不管内容真伪**；
  2. ⚠️ ⭐ **实测一例假阴性**：`consult/receipt/002-…` 的**件头里有一颗"举例"**的
     `> **类标**：⑤ 报告　｜　**沿革**：…`（它是在**示范形态**，⛔ 不是声明自己）——
     ⭐ 本门禁会把它读成「已声明 ⑤」⇒ ⛔ **判据分不出"举例"与"声明"**
     （`HEAD_LINES` 只能挡掉**正文里**的讨论，挡不住**件头里的举例**）。
     ⚠️ 如实记：这类「讨论形态的散文」**只能靠人**，⛔ **本门禁不假装能判**。

⭐ `--selftest`：**七臂**注入自证（含**反臂**：合法形态必须安静）。
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASELINE = ROOT / "tools" / "doc-class-mark-baseline.tsv"

#: 被覆盖的件 = **类标适用范围内的 `.md`**。
#: ⚠️ ⭐ **`consult/` 刻意不收**（2026-10-05 开发者裁：**类标的取值范围 = `①`–`⑦`**，而
#:   `consult/` 属 **`⑪ 咨询通道`** ⇒ ⛔ **它不在类标的适用范围内**）。
#: ⭐ **这一条同时解决了一处假阳性**：`consult/receipt/002-…` 的**件头里举例**写了 `> **类标**：⑤ 报告`，
#:   而它其实**不适用类标** ⇒ 把它收进来只会误读（⛔ 或逼着人去改一份外部输入的件）。
COVERED_DIRS = ("docs", "survey")
#: ⚠️ `docs/` 是**递归**的（含 `docs/reviews/` 等子目录）；⛔ 但排除生成物所在的历史目录没意义 —— 全收。
SKIP_NAMES = {"README.md"}

#: ⭐ 形态（`§D′-9` 9.3b 逐字）：`> **类标**：⑤ 报告　｜　**沿革**：…　｜　**裁定**：…`
CLASS_MARK = re.compile(r"^\s*>?\s*\*\*类标\*\*\s*[：:]\s*(.+?)\s*$", re.MULTILINE)
HISTORY = re.compile(r"\*\*沿革\*\*")
#: ⭐ 取值合法（判据 ②）：`**类标**` 的第一个字符必须是 `①`–`⑦` 之一。
CLASS_VALUES = "①②③④⑤⑥⑦"

#: ⚠️⚠️ ⭐⭐ **只在「件头」里找**（`HEAD_LINES` 行以内）—— ⛔ **这是本门禁最容易搞错的一处**。
#: ⭐ **为什么**：`§D′-9` 9.3b 规定类标**写在件头的 blockquote 里** ⇒ ⛔ 全文件搜会把**讨论形态的散文**
#: 当成**声明**。⚠️ 本门禁**第一版就踩了**（如实记）：真树上立刻误报 2 件 ——
#: `consult/receipt/002-…`（它**举例**写了 `> **类标**：⑤ 报告`）与 `docs/DOC_REFACTOR_DRAFT.md`
#: （它的 9.3b **示范**了同一行）⇒ ⭐ **2 件都被当成"已带类标"**，而其实一件都没有。
#: ⭐ 这正是本仓那句**「提到它 ≠ 引用它」**（`§D′-9` 9.3 逐字）—— ⚠️ **同族坑，第 N 次**。
#: ⚠️ `30` 这个数**不是新定的**：它与 `tools/survey-index.py` 的 `read_head(p, n=30)` **同一个口径**。
HEAD_LINES = 30

#: ⚠️ 人口下限：被覆盖的件少于此 ⇒ 红（⛔ 不许「扫不到就报绿」）。
FILE_FLOOR = 100


def covered() -> list[str]:
    """→ 被覆盖的件的**仓库相对路径**（排好序）。"""
    out: list[str] = []
    for d in COVERED_DIRS:
        base = ROOT / d
        if not base.is_dir():
            continue
        for p in sorted(base.rglob("*.md")):
            if p.name in SKIP_NAMES:
                continue
            out.append(p.relative_to(ROOT).as_posix())
    return sorted(out)


def class_of(text: str) -> str | None:
    """→ 该件的类号（`①`–`⑦`）；⛔ 没有 `**类标**` 行 ⇒ `None`。

    ⚠️ ⭐ **只看件头**（`HEAD_LINES`）—— 见 `HEAD_LINES` 上面那段（本门禁第一版就踩了误报）。
    """
    head = "\n".join(text.splitlines()[:HEAD_LINES])
    m = CLASS_MARK.search(head)
    if not m:
        return None
    return m.group(1).strip()[:1]


def declares_history(text: str) -> bool:
    """→ 件头里**声明**了 `**沿革**` 吗（⭐ 同 `class_of`：⛔ 不看正文里的讨论）。"""
    return bool(HISTORY.search("\n".join(text.splitlines()[:HEAD_LINES])))


def load_baseline() -> set[str]:
    """→ 欠账名单（⭐ **允许暂时没有类标**的件）。"""
    if not BASELINE.exists():
        return set()
    return {l.strip() for l in BASELINE.read_text(encoding="utf-8").splitlines()
            if l.strip() and not l.startswith("#")}


def write_baseline(files: list[str]) -> None:
    lines = ["# 「类标」欠账名单 —— ⭐ **只许变短**（新增欠账 ⇒ 红；还清一处 ⇒ 删一行）",
             "# 名单里的件 = **存量件**，按回执 002 的 Q3「⛔ 存量不追溯」暂时豁免；",
             "# 生成：python3 tools/check-doc-class-mark.py --write",
             f"# 总计 {len(files)} 件", ""]
    lines += files
    BASELINE.write_text("\n".join(lines) + "\n", encoding="utf-8")


def check(files: list[str] | None = None, baseline: set[str] | None = None,
          read=None) -> list[str]:
    """→ **不成立的那几条**（空列表 = 全绿）。⭐ `files`/`baseline`/`read` 可换 ⇒ **能注入自证**。"""
    files = covered() if files is None else files
    baseline = load_baseline() if baseline is None else baseline
    read = read or (lambda rel: (ROOT / rel).read_text(encoding="utf-8", errors="replace"))
    problems: list[str] = []

    if len(files) < FILE_FLOOR:
        problems.append(f"⛔ 只覆盖到 {len(files)} 件 < 人口下限 {FILE_FLOOR} ⇒ "
                        f"扫描范围崩了（⭐ 这正是「扫不到就报绿」那族）")
        return problems

    n_have = 0
    for rel in files:
        try:
            text = read(rel)
        except OSError:
            continue
        cls = class_of(text)
        if cls is None:
            #: 判据 ③：**欠账名单之外**的件必须有类标
            if rel not in baseline:
                problems.append(f"⛔ **判据③**：`{rel}` **没有** `**类标**`，而它**不在欠账名单里**"
                                f" ⇒ ⭐ 新件必须带类标（形态见草案 `§D′-9` 9.3b）")
            #: 判据 ①：没有类标却写了沿革 ⇒ 半写
            if declares_history(text):
                problems.append(f"⛔ **判据①**：`{rel}` 带了 `**沿革**` 却**没有** `**类标**` ⇒ "
                                f"**半写**（写了为什么在这，却不知道它是哪一类）")
            continue
        n_have += 1
        #: 判据 ②：取值合法
        if cls not in CLASS_VALUES:
            problems.append(f"⛔ **判据②**：`{rel}` 的 `**类标**` 类号是 `{cls}` —— "
                            f"⛔ 不在 `{'`／`'.join(CLASS_VALUES)}` 之内（取值表见 `§D′-1` 七类表）")

    #: ⭐ 名单**只许变短**：还清了却仍留在名单里 ⇒ 也红（⛔ 不许留假账）
    stale = sorted(baseline - set(files))
    if stale:
        problems.append(f"⛔ 欠账名单里有 {len(stale)} 件**已不在覆盖范围**（被删／改名）⇒ "
                        f"跑 `--write` 同步名单（例：{stale[:3]}）")
    return problems


def selftest() -> int:
    """**七臂**注入自证。⭐ 含**反臂**（合法形态必须安静）。"""
    arms: list[tuple[str, bool]] = []
    base = [f"f{i}.md" for i in range(FILE_FLOOR + 5)]      # 满足人口下限的假件集

    def run(texts: dict[str, str], baseline: set[str]) -> list[str]:
        return check(files=list(texts), baseline=baseline, read=lambda rel: texts[rel])

    ok_text = "> **类标**：⑤ 报告　｜　**沿革**：批 1 搬入　｜　**裁定**：`O165`\n"
    no_mark = "# 标题\n\n正文。\n"
    half = "# 标题\n\n> **沿革**：批 1 搬入\n"
    bad_val = "> **类标**：⑧ 别的\n"
    gap = "> **类标**：没有类号\n"

    #: ⭐ **反臂**：形态合法 ⇒ 必须安静（这是本门禁能不能用的底线）
    t = {f: ok_text for f in base}
    arms.append(("A 全部带合法类标 ⇒ 安静", run(t, set()) == []))
    #: 存量欠账 ⇒ 安静（⛔ 不追溯）
    t2 = dict(t); t2["old.md"] = no_mark
    arms.append(("B 存量欠账（在名单里）⇒ 安静", run(t2, {"old.md"}) == []))
    #: 判据 ③：不在名单里又没类标 ⇒ 红
    arms.append(("C 新件没有类标 ⇒ 红（判据③）", any("判据③" in p for p in run(t2, set()))))
    #: 判据 ①：半写 ⇒ 红
    t3 = dict(t); t3["half.md"] = half
    arms.append(("D 有沿革没类标 ⇒ 红（判据①）", any("判据①" in p for p in run(t3, set()))))
    #: 判据 ②：类号越界 ⇒ 红
    t4 = dict(t); t4["bad.md"] = bad_val
    arms.append(("E 类号 `⑧` ⇒ 红（判据②）", any("判据②" in p for p in run(t4, set()))))
    #: 判据 ②：漏类号 ⇒ 红
    t5 = dict(t); t5["gap.md"] = gap
    arms.append(("F 漏写类号 ⇒ 红（判据②）", any("判据②" in p for p in run(t5, set()))))
    #: ⭐ 人口下限：范围崩塌 ⇒ **响亮失败**（⛔ 不许静默报绿）
    arms.append(("G 覆盖数低于人口下限 ⇒ 响亮失败", any("人口下限" in p for p in check([], set(), lambda r: ""))))

    bad = 0
    for name, ok in arms:
        bad += not ok
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}")
    print(f"DOC_CLASS_MARK_SELFTEST {'PASS' if not bad else 'FAIL'}: arms={len(arms)} failed={bad}")
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser(description="「类标」形态门禁")
    ap.add_argument("--write", action="store_true", help="把当前所有无类标的件登记成欠账")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        return selftest()
    if a.write:
        files = covered()
        left = [f for f in files if class_of((ROOT / f).read_text(encoding="utf-8", errors="replace")) is None]
        write_baseline(left)
        print(f"DOC_CLASS_MARK_RESULT WROTE: 欠账名单 {len(left)} 件（覆盖 {len(files)} 件）")
        return 0
    problems = check()
    if problems:
        for p in problems[:12]:
            print(f"  [FAIL] {p}")
        print(f"DOC_CLASS_MARK_RESULT FAIL: {len(problems)} 条不成立")
        return 1
    have = sum(1 for f in covered()
               if class_of((ROOT / f).read_text(encoding="utf-8", errors="replace")) is not None)
    print(f"DOC_CLASS_MARK_RESULT PASS: 覆盖 {len(covered())} 件 · 带类标 {have} · "
          f"欠账 {len(load_baseline())}（⛔ 只许变短）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
