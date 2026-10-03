#!/usr/bin/env python3
"""生成 / 校验 `docs/plans/README.md` 与 `docs/reviews/README.md` —— **两个参考目录的登记表**。

照 `tools/survey-index.py`（`survey/README.md`）的成熟模子：**单一出处 + 双向防漂移 + 解析不到就响亮失败 +
全挂 `check-all`**。⭐ 每行**从文件自己的头部读出来**（`标题` / `行的自称` / `行数`），⛔ 不靠手维护。

## 为什么有它
用户 2026-10-02 对这两个目录的定性（逐字）：
> 「（大型施工）实际上**只有草案或者说定案，加上施工计划书**……」
> 「之前的 `plan/` 确实是**各种讨论记录**，我觉得他们也**只适合当记录**吧，**适合当参考**，
>  **施工时不适合拿来看**」

⇒ ⭐ **两个目录都是「参考」**：`docs/plans/` = 刀级**设计产出 ＋ 参考**（讨论记录 / 草案 / 设计单）；
`docs/reviews/` = **报告 / 复核 / 审查**（⑤ 类：**可引、⛔ 不可当依据**）。
⇒ 它们的共同病是**看不出来里面是什么** ⇒ 这张表把「**每份自称是什么**」摆上台面。

## 它**不是**什么（防误用）
* ⛔ **不是效力来源** —— 表里写着"报告"不等于它可以当依据；效力由 `survey/README.md` 与本表**都说**的那条定：
  **可引、⛔ 不可当依据**。
* ⛔ **不是内容判断** —— 它**照抄文件自己的自称**，⛔ 不替文件判断它属于哪一类。

## 用法
    python3 tools/doc-registry.py --write     # 生成两个 README
    python3 tools/doc-registry.py --check     # 校验（门禁调这个）
    python3 tools/doc-registry.py --list      # 只打印分类结果
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

#: ⚠️ 人口下限：实测 **docs/plans 13** · **docs/reviews 74**（2026-10-02）。
#: ⛔ 目录搬了 / 命名变了 ⇒ **响亮失败**，不许"登记表只剩几行"也照样绿。
ASSERTIONS = {"docs/plans": 10, "docs/reviews": 60}

#: 门禁比对到此行为止（本行以下是**易变列**：引用热度会随任何一次引用变 ⇒ 不进比对）
APPENDIX_MARKER = "## 附录：不在比对范围的东西"

#: 自称的识别模式（⭐ 顺序即优先级；**扫首 N 行**，因为这两个目录的习惯是把性质写在标题下的引文块里）
SELF_PATTERNS: list[tuple[str, str]] = [
    (r"对抗性复核|复核侧|复核件", "复核"),
    (r"施工设计单|设计单|施工设计", "设计单"),
    (r"讨论件|讨论稿|讨论记录|讨论整理", "讨论记录"),
    (r"草案|草稿|待评审|待批准|评审稿", "草案"),
    (r"实测报告|实测|读数|测量|取证|勘察|勘测|根因|复现", "实测 / 根因"),
    (r"审查|审计|审核|核对|清单|梳理|建议|复盘", "审查 / 清单"),
    (r"交接|迁移|回迁", "交接 / 迁移"),
    (r"方案|计划|规划|设计|定档", "方案 / 设计"),
    (r"只读", "（只自称只读）"),
]
HEAD_LINES = 15

DECL = re.compile(r"^[>*\s]*\**\s*(性质|身份|状态|定位)\**\s*[：:]\s*(.+)$")


def read_one(f: Path) -> dict:
    lines = f.read_text(encoding="utf-8").splitlines()
    title = next((re.sub(r"^#\s*", "", l).strip() for l in lines if l.startswith("# ")), f.stem)
    head = lines[:HEAD_LINES]
    decl = ""
    for l in head:
        m = DECL.match(l)
        if m:
            decl = re.sub(r"[*`]", "", m.group(2)).strip()
            break
    kind = "（未自称）"
    for pat, name in SELF_PATTERNS:
        if any(re.search(pat, l) for l in head):
            kind = name
            break
    return {"file": f.name, "title": re.sub(r"[*`]", "", title)[:78],
            "decl": decl[:110] or "—", "kind": kind, "lines": len(lines)}


def collect(sub: str) -> list[dict]:
    d = ROOT / "docs" / sub
    if not d.is_dir():
        print(f"DOC_REGISTRY_RESULT FAIL: 目录不存在 {d}", file=sys.stderr)
        raise SystemExit(1)
    files = sorted(p for p in d.glob("*.md") if p.name != "README.md")
    rows = [read_one(p) for p in files]
    if len(rows) < ASSERTIONS[f"docs/{sub}"]:
        print(f"DOC_REGISTRY_RESULT FAIL: 解析崩塌 —— `docs/{sub}` 只有 {len(rows)} 份 "
              f"< 下限 {ASSERTIONS[f'docs/{sub}']}（目录搬了？命名变了？⇒ "
              f"登记表「只剩几行」读起来却完全正常，⛔ 不许少读一截还绿）", file=sys.stderr)
        raise SystemExit(1)
    return rows


PURPOSE = {
    "plans": ("刀级**设计产出 ＋ 参考**", "**草案 / 讨论记录 / 设计单** —— 用户：「**只适合当记录**，"
                                        "**适合当参考**，**施工时不适合拿来看**」"),
    "reviews": ("**报告 / 复核 / 审查**", "⑤ 类：**可引、⛔ 不可当依据**"),
}


def render(sub: str, rows: list[dict]) -> str:
    role, why = PURPOSE[sub]
    kinds: dict[str, int] = {}
    for r in rows:
        kinds[r["kind"]] = kinds.get(r["kind"], 0) + 1
    L: list[str] = []
    A = L.append
    A(f"# `docs/{sub}/` 登记表（**生成物，禁手改**）")
    A("")
    A("> ⚠️ **本文件由脚本生成** —— 手改会被门禁判红。")
    A(f"> 重新生成：`python3 tools/doc-registry.py --write`　·　校验：`python3 tools/doc-registry.py --check`")
    A("> 门禁：`tools/check-doc-registry.sh`（挂 `tools/check-all.sh`）。")
    A("")
    A(f"## ⛔ 本目录的性质：{role}")
    A("")
    A(why)
    A("")
    A("⭐ **两个目录的共同效力**（用户 2026-10-02 划定）：**可引、⛔ 不可当依据**。")
    A("⛔ 想拿它们当依据 ⇒ 不行。**要先有一条 `D-###` 裁定采纳它，再引那条裁定。**")
    A("")
    A("## ⛔ 本目录**不是**「施工依据」（这条最要紧）")
    A("")
    A("| 层 | 住哪 | 是什么 |")
    A("|---|---|---|")
    A("| **设计件** | `docs/` 根（见 `docs/DESIGN_INDEX.md` 第 ① 层） | 系统级 / 包级设计的**结果** |")
    A("| **刀级设计产出 ＋ 参考** | `docs/plans/` | 草案 / 讨论记录 / 设计单 |")
    A("| ⭐ **施工依据** | `docs/DOC_REFACTOR_PLAN.md` | **施工计划书**（施工期唯一执行入口） |")
    A("| **报告 / 复核** | `docs/reviews/` · `survey/` | ⑤ 类：可引、⛔ 不可当依据 |")
    A("")
    A("## 读数（**只从磁盘复算**）")
    A("")
    A("| 量 | 值 |")
    A("|---|---|")
    A(f"| 文件份数 | **{len(rows)}** |")
    A("| 自称分布 | " + " · ".join(f"**{k}** {v}" for k, v in sorted(kinds.items(), key=lambda x: -x[1])) + " |")
    A(f"| 头部有 `性质/身份/状态/定位：` 声明的 | **{sum(1 for r in rows if r['decl'] != '—')} / {len(rows)}** |")
    A("")
    A("## 表（**门禁逐字节比对的就是这一段**：文件 / 标题 / 自称类型 / 声明的性质 / 行数）")
    A("")
    A("| 文件 | 标题 | 自称类型 | 声明的性质（**逐字**） | 行数 |")
    A("|---|---|---|---|---:|")
    for r in rows:
        A(f"| `{r['file']}` | {r['title']} | **{r['kind']}** | {r['decl']} | {r['lines']} |")
    A("")
    A("> **列义**：`自称类型` = **从文件头部（首 15 行）扫到的**关键词 ⇒ ⚠️ **这是我扫的，不是文件说的** ·")
    A("> `声明的性质` = 文件自己写的 `性质：… / 身份：…` 那一行（**逐字照抄**，⛔ 不改写）。")
    A("")
    A("## ⛔ 本表**假装不了**的两件事（诚实边界）")
    A("")
    A("1. ⛔ **`自称类型` 不等于效力** —— 一份写着「设计单」的文件**仍然**是 ⑤ 类（可引不可依据）。")
    A("2. ⛔ **扫不到就是扫不到** —— 头部 15 行没写性质的，本表显示 `（未自称）`；")
    A("   ⛔ 本表**不替它猜**，也⛔ **不因此报红**（那是文件自己的事）。")
    A("")
    A(APPENDIX_MARKER)
    A("")
    A("> ⚠️ **下面这些不在门禁比对范围内**（任何一次引用/改动都会让它们变，算进比对会把门禁"
      "退化成「例行敲一下」——`docs/DECISIONS_INDEX.md` 的同一教训）。")
    A("")
    A(f"⛔ **{len(rows)} 份里没有一份带 `落档判定` 行**（🔴需裁/🟡挂账/🟢参考/⚪数据）—— "
      f"那是草案 `E1`／`E2` 的活，🟡 档、量大 ⇒ ⛔ 本刀不做。")
    A("")
    return "\n".join(L) + "\n"


def build() -> dict[str, tuple[str, list[dict]]]:
    return {sub: (render(sub, rows), rows) for sub, rows in
            ((s, collect(s)) for s in ("plans", "reviews"))}


def main() -> int:
    ap = argparse.ArgumentParser(description="生成/校验 docs/plans 与 docs/reviews 的登记表")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--write", action="store_true")
    g.add_argument("--check", action="store_true")
    g.add_argument("--list", action="store_true")
    args = ap.parse_args()

    built = build()

    if args.list:
        for sub, (_, rows) in built.items():
            print(f"── docs/{sub}/  ({len(rows)} 份)")
            for r in rows:
                print(f"   [{r['kind']:12s}] {r['file']}")
        return 0

    problems: list[str] = []
    if args.write:
        for sub, (text, rows) in built.items():
            p = ROOT / "docs" / sub / "README.md"
            p.write_text(text, encoding="utf-8")
            print(f"DOC_REGISTRY_RESULT WROTE: docs/{sub}/README.md（{len(rows)} 份）")
        return 0

    for sub, (text, rows) in built.items():
        out = ROOT / "docs" / sub / "README.md"
        if not out.exists():
            problems.append(f"`{out.relative_to(ROOT)}` 不存在 ⇒ 跑 `--write`")
            continue
        have = out.read_text(encoding="utf-8")
        if APPENDIX_MARKER not in have:
            problems.append(f"`{out.relative_to(ROOT)}` 缺 `{APPENDIX_MARKER}` 标记（被手改过？）")
            continue
        d = have.split(APPENDIX_MARKER, 1)[0].splitlines()
        w = text.split(APPENDIX_MARKER, 1)[0].splitlines()
        if d != w:
            first = next((i for i, (a, b) in enumerate(zip(d, w), 1) if a != b), None)
            detail = "行数不同" if len(d) != len(w) else "行数相同、内容不同"
            problems.append(f"⛔ `{out.relative_to(ROOT)}` 已陈旧（{detail}"
                            + (f"，首个不同在第 {first} 行" if first else "") + "）⇒ 跑 `--write`")

    for sub, (_, rows) in built.items():
        for r in rows:
            if not r["file"].endswith(".md"):
                problems.append(f"`docs/{sub}/{r['file']}` 不是 .md ⇒ 解析面写坏了？")

    summary = " · ".join(f"docs/{s} {len(rows)} 份" for s, (_, rows) in built.items())
    for p in problems:
        print(f"  [FAIL] {p}", file=sys.stderr)
    if problems:
        print(f"DOC_REGISTRY_RESULT FAIL: {len(problems)} 处（{summary}）", file=sys.stderr)
        return 1
    print(f"DOC_REGISTRY_RESULT PASS: {summary} / 两份登记表逐字节相同"
          f"（⚠️ 附录不在比对范围）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
