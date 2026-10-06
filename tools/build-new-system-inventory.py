#!/usr/bin/env python3
"""生成物：**新文档体系 · 物理清单**（`tools/new-system-inventory.tsv`）。

出处 = 咨询回执 `004` 的后续工作 **#5**：建一份**机器可读**的新体系清单，
供第 3 遍「和新文档体系重复冲突」当**比对基线**（开发者再给核心件标 `topics`）。

⭐⭐ **单一出处**：件清单与归类**全部**来自 `tools/new-home-audit.py`
（`old_home_items()` 的普查口径 ＋ `DEST` 类表）——
⛔ **本脚本不另写一份普查口径，也⛔不另写一份类表**（那会造出第二份真相）。

用法：

    python3 tools/build-new-system-inventory.py            # 只打印摘要（⛔ 不写盘）
    python3 tools/build-new-system-inventory.py --write    # 重新生成 tsv
    python3 tools/build-new-system-inventory.py --check    # 与盘上比对（陈旧 ⇒ 退出码 1）

⚠️ **诚实边界（⛔ 读的时候请打折）**：

1. ⛔ **它不是门禁** —— ⛔ **没挂 `check-all`** ⇒ **它会漂**；漂了只能靠 `--check` 手动抓。
   ⭐ 为什么先不挂：挂门禁 = 让它在**每次 `check-all`** 都跑（443 次 `git log`）＋
   本仓「**门禁被创造出来就不能被轻易删除**」⇒ 加一道要想清楚，**留待裁定**。
2. ⭐ `class` = **落点**，取 `DEST` 的**出现顺序优先序**（⑩ 兜底优先，与 `new-home-audit` 一致）。
   ⚠️ **两轴并存的件**（`new-home-audit` 实测 **11** 件）在 `class_all` 列里**如实全列**
   ⇒ ⛔ **不藏**（本仓最贵那族错 = 「读起来完全正常」的漏）。
3. ⚠️ `title` 是**机械取的**（`.md` 取第一个一级标题；其余取第一行注释/文档串首行）
   ⇒ ⛔ **它可能不等于人读的标题**（那是人判的事，本脚本 ⛔ 不假装能判）。
4. ⚠️ `last_modified` = **git 提交日**（`--format=%cs`）—— ⛔ **不用文件系统 mtime**：
   克隆／检出／`git stash` 都会改 mtime ⇒ 那是**不可复算**的量。
   ⚠️ 未入 git 的件 ⇒ 写 `(未跟踪)`（⛔ 不编一个日期）。
5. ⭐⭐ **`topics` 列由开发者填**（回执 `004` 的阶段 2）⇒ ⭐ 本脚本 `--write` 时
   **读回盘上已填的 `topics` 并保留**（出处 = 用户令「**已经有的数据要保留**」）
   ⇒ ⛔ **谁的标注都不会被重生成洗掉**。
   ⚠️ 这是**一处真实的形状妥协**：本仓惯例是「**生成物，禁手改**」，而 `topics` 是**手改列**
   ⇒ 两条并存的后果如实写在这里，⛔ 不粉饰（活样本：`docs/CLEANUP_CLASSIFY.md` 的「打勾栏」
   是**同类形状** —— ⚠️ 而它的生成器**不保留勾**，`--write` 会把 `☑` 洗回 `☐`）。
6. ⛔ **`docs/` 根的三件白名单落点**（`EXTRACTED_KNOWLEDGE.md` / `LESSONS_LEARNED.md` /
   `CRITERIA_LIBRARY.md`，回执 `004` 的 `M7`）**不影响本清单** —— 它们照常按 `DEST` 归 ④。
"""
from __future__ import annotations

import argparse
import importlib.util
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "tools" / "new-system-inventory.tsv"

#: ⭐ 列（⭐ 前 5 列 = 回执 `004` 的 #5 逐字；末两列是本仓补的，理由见下）
COLUMNS = (
    "path",            # 件路径（相对仓根）
    "class",           # 落点类号（`DEST` 优先序；⑩ 兜底优先）
    "class_all",       # ⚠️ 本仓补：**两轴并存**时把命中的类**全列**（⛔ 不藏）
    "title",           # 机械取的标题（⛔ 不等于人读标题）
    "size_lines",      # 行数
    "last_modified",   # git 提交日（⛔ 不是 mtime）
    "topics",          # ⭐ **开发者填**（3-5 个关键词）⇒ 本脚本保留已填内容
)

HEADER_NOTE = (
    "# 新文档体系 · 物理清单（**生成物** —— 跑 `python3 tools/build-new-system-inventory.py --write`）\n"
    "# 出处 = 咨询回执 `004` 后续工作 #5；类表单一出处 = `tools/new-home-audit.py` 的 `DEST`\n"
    "# ⭐ `topics` 列**由开发者填**（阶段 2）⇒ 重生成**保留**已填内容（⛔ 不洗掉）\n"
)


def _audit_module():
    """⭐ 导入审计模块 —— 普查口径与类表的**唯一真相**在它那儿（⛔ 不复制一份）。"""
    p = ROOT / "tools" / "new-home-audit.py"
    spec = importlib.util.spec_from_file_location("_nha", p)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def git_dates(paths: list[str]) -> dict[str, str]:
    """一次性取每个件的**最后提交日**（⛔ 逐件 spawn 443 次 `git log` 太慢）。"""
    out = subprocess.run(
        ["git", "log", "--format=%x01%cs", "--name-only", "-z", "--no-renames"],
        cwd=ROOT, capture_output=True, text=True).stdout
    dates: dict[str, str] = {}
    for chunk in out.split("\x01"):
        if not chunk.strip():
            continue
        head, _, rest = chunk.partition("\n")
        #: ⚠️⚠️ **必须清 NUL**：`-z` 会把提交之间的分隔也写成 NUL，于是日期尾部带一个 `\x00`
        #:    ⇒ ⭐ 第一版没有这一步，落出来的 TSV **每行一个 NUL**（实测 **627 个**）
        #:    ⇒ `grep` 当场判它 **binary file matches**（下游解析器会静默截断）。
        #:    ⭐ 抓到它的**不是**我的复读，是**拿 grep 去查生成的产物**。
        date = head.replace("\x00", "").strip()
        for rel in rest.split("\0"):
            rel = rel.replace("\x00", "").strip()
            if rel and rel not in dates:
                dates[rel] = date
    return dates


def title_of(rel: str) -> str:
    """机械取标题：`.md` 取第一个一级标题；其余取第一行注释/文档串。"""
    p = ROOT / rel
    try:
        text = p.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return "(读不到)"
    for line in text.split("\n")[:40]:
        s = line.strip()
        if not s:
            continue
        if s.startswith("# "):
            return s[2:].strip()
        if s.startswith(("#", "//", "/*", "*", "--", "%")):
            t = s.lstrip("#/*-—% ").strip()
            if t and not t.startswith('"'):
                return t.rstrip("*/").strip()
            continue
        if s.startswith('"""') or s.startswith("'''"):
            t = s.strip("\"'").strip()
            if t:
                return t
            continue
    return "(无标题)"


def size_lines(rel: str) -> int:
    try:
        return len((ROOT / rel).read_text(encoding="utf-8", errors="replace").splitlines())
    except OSError:
        return 0


def collect(existing_topics: dict[str, str]) -> list[list[str]]:
    nha = _audit_module()
    items = nha.old_home_items(ROOT)
    order = {name: i for i, (name, _why, _pats) in enumerate(nha.DEST)}
    dates = git_dates(items)
    rows: list[list[str]] = []
    for rel in items:
        hits: list[str] = []
        for name, _why, pats in nha.DEST:
            if nha.match(rel, pats):
                hits.append(name)
        seen = sorted(set(hits), key=lambda n: order[n])
        winner = seen[-1] if seen else "(无家可归)"
        rows.append([
            rel,
            winner,
            " ／ ".join(seen),
            title_of(rel),
            str(size_lines(rel)),
            dates.get(rel, "(未跟踪)"),
            existing_topics.get(rel, ""),
        ])
    rows.sort(key=lambda r: (order.get(r[1], 99), r[0]))
    return rows


def render(rows: list[list[str]]) -> str:
    out = [HEADER_NOTE]
    out.append("\t".join(COLUMNS) + "\n")
    for r in rows:
        out.append("\t".join(c.replace("\t", " ").replace("\n", " ") for c in r) + "\n")
    return "".join(out)


def read_existing() -> dict[str, str]:
    """⭐ 读回盘上已填的 `topics`（⛔ 不洗掉开发者的标注）。"""
    if not OUT.exists():
        return {}
    keep: dict[str, str] = {}
    lines = OUT.read_text(encoding="utf-8").split("\n")
    try:
        head = next(i for i, l in enumerate(lines) if l.startswith("path\t"))
    except StopIteration:
        return {}
    cols = lines[head].split("\t")
    if "topics" not in cols:
        return {}
    ti = cols.index("topics")
    for l in lines[head + 1:]:
        if not l.strip():
            continue
        parts = l.split("\t")
        if len(parts) > ti and parts[ti].strip():
            keep[parts[0]] = parts[ti]
    return keep


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--write", action="store_true", help="重新生成 tsv")
    ap.add_argument("--check", action="store_true", help="与盘上比对（陈旧 ⇒ 退出码 1）")
    args = ap.parse_args()

    rows = collect(read_existing())
    want = render(rows)
    n_homeless = sum(1 for r in rows if r[1] == "(无家可归)")
    n_multi = sum(1 for r in rows if " ／ " in r[2])
    n_topics = sum(1 for r in rows if r[6])
    import collections
    by_class = collections.Counter(r[1] for r in rows)

    print(f"INVENTORY_RESULT 件 {len(rows)} · 无家可归 {n_homeless} · 两轴并存 {n_multi} "
          f"· 已填 topics {n_topics}")
    for name, n in sorted(by_class.items()):
        print(f"  {name}：{n}")

    if args.write:
        OUT.write_text(want, encoding="utf-8")
        print(f"  ✅ 已写 {OUT.relative_to(ROOT)}（{len(rows)} 行）")
        return 0
    if args.check:
        have = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
        if have != want:
            print("  ⛔ 清单已陈旧 ⇒ 跑 `python3 tools/build-new-system-inventory.py --write`")
            return 1
        print("  ✅ 与盘上一致")
        return 0
    print("  （默认只量不写 —— 加 `--write` 落盘）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
