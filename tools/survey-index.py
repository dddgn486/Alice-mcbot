#!/usr/bin/env python3
"""**勘测报告登记表**生成器（`survey/README.md`；草案 `E3`，2026-10-02）。

## 为什么有它
草案 §P-5 `E3` 逐字：「⭐ **`survey/README.md` 变生成式登记表**」——
⭐ 用户定的方向是「**索引从下往上维护**」⇒ 登记表里的每一行都**从报告自己的头部读出来**，
⛔ 不靠人手维护（手维护的登记表必然与 50 份报告分叉，而**没有任何东西会因此变红**）。

## ⭐ `采纳读数` 一列是这张表的**核心**（也是它存在的理由）
`survey/` 是 ⑤ 类：**可以引、不可当依据**（草案 §P-5 `E4`）。
⇒ 一张只列文件名的表**帮不了任何人**；真正要回答的是「**这份报告到底有没有落地**」。
⇒ 本列**只从磁盘复算**：报告 id 在 `docs/AI_DECISIONS.md` / `src/` / `tools/` / `docs/` 里
**出现过几次**。⚠️ **它不是"采纳了"的证明** —— 它只把可疑面缩到人能看完的规模，
真正判"采纳"要看 `D-###` 正文（⛔ 那需要语义，做不到）。
⇒ ⭐ 所以本列叫**读数**，⛔ 不叫"状态"：`0` 只说明"**这份报告在本仓里一次都没被提到**"。

## 它**不是**什么
* ⛔ **不改任何报告正文**（草案 `E1` 要求的"逐份补 `落档判定` 行"是 🟡、量大 50 份 ⇒ 不在本刀）。
* ⛔ **不是排期、不是授权** —— 它只是登记表。
"""

from __future__ import annotations

import argparse
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SURVEY = ROOT / "survey"
OUTPUT = SURVEY / "README.md"

#: ⚠️ 人口下限：2026-10-02 实测 = 50 份。扫到的份数少于此 ⇒ **响亮失败**
#:（目录搬了 / 命名变了 ⇒ "登记表只剩 3 行"读起来却完全正常 —— 这正是本仓反复踩的那族错）
ASSERTIONS = {"报告份数": 40}

#: 报告 id = 文件名开头的数字（含 `49.5` 这种小数）
#: 报告 id = 文件名开头 `N-` 或 `N.md`（⛔ `INTENT.md` 不算 —— 它不以"数字 + 分隔符"开头）
ID = re.compile(r"^(\d+(?:\.\d+)?)(?=[-.])")
TITLE = re.compile(r"^#\s+(.*)$")
FIELD = re.compile(r"^>\s*\*\*(基线|性质|起因|作者|日期)\*\*\s*[：:]\s*(.*)$")

#: 门禁比对到此行为止（本行以下是**易变列**：采纳读数会因任何一次引用而变 ⇒ 不进比对）
APPENDIX_MARKER = "## 附录：采纳读数明细（**不计入门禁比对**）"

#: 扫描范围（⛔ **排除 `survey/` 自身**：否则每份报告都会"被自己提到"一次 ⇒ 每行恒 ≥1）
SCAN: tuple[tuple[str, Path, set[str]], ...] = (
    ("裁定", ROOT / "docs" / "AI_DECISIONS.md", {".md"}),
    ("src", ROOT / "src" / "main" / "java", {".java"}),
    ("tools", ROOT / "tools", {".sh", ".py", ".mjs"}),
    ("其它文档", ROOT / "docs", {".md"}),
)


def read_head(p: Path, n: int = 30) -> list[str]:
    return p.read_text(encoding="utf-8").splitlines()[:n]


def parse_report(p: Path) -> dict:
    head = read_head(p)
    title = ""
    fields: dict[str, str] = {}
    for l in head:
        if not title and (m := TITLE.match(l)):
            title = re.sub(r"[*`]", "", m.group(1)).strip()
        if m := FIELD.match(l):
            fields.setdefault(m.group(1), re.sub(r"[*`]", "", m.group(2)).strip())
    mid = ID.match(p.name)
    return {
        "file": p.name,
        "id": mid.group(1) if mid else "—",
        "title": title or "（无标题）",
        "base": fields.get("基线", "—")[:40],
        "nature": fields.get("性质", "")[:60],
        "lines": len(p.read_text(encoding="utf-8").splitlines()),
    }


def adoption_counts() -> Counter:
    """→ {报告 id: 在 survey/ **之外**被提到几次}。⚠️ 读数，⛔ 不是"采纳"的证明。"""
    c: Counter = Counter()
    for _label, base, exts in SCAN:
        if base.is_file():
            files = [base]
        elif base.is_dir():
            files = [q for q in base.rglob("*") if q.is_file() and q.suffix in exts]
        else:
            continue
        for q in files:
            try:
                body = q.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            for m in re.finditer(r"survey/(\d+(?:\.\d+)?)", body):
                c[m.group(1)] += 1
    return c


def render(reports: list[dict], adopt: Counter, others: list[Path]) -> str:
    cited = [r for r in reports if adopt.get(r["id"], 0)]
    never = [r for r in reports if not adopt.get(r["id"], 0)]
    L: list[str] = []
    L.append("# 勘测报告登记表（**生成物，禁手改**）")
    L.append("")
    L.append("> ⚠️ **本文件由脚本生成** —— 手改会被门禁判红。")
    L.append("> 重新生成：`python3 tools/survey-index.py --write`　·　校验：`python3 tools/survey-index.py --check`")
    L.append("> 门禁：`tools/check-survey-index.sh`（挂 `tools/check-all.sh`）。")
    L.append("")
    L.append("## ⛔ 本目录的性质（先说清楚，免得被当依据）")
    L.append("")
    L.append("**本目录不是实现授权。**每一份都是**建议 / 勘测 / 参考方案**，主工作流"
             "**可以采纳、可以部分采纳、也可以不采纳**。")
    L.append("⇒ 本目录属 **⑤ 类（报告 / 构想）**：⭐ **可以引，⛔ 不可当依据**"
             "（草案 §P-5 `E4`：报告被当规则引用 ⇒ 红）。")
    L.append("")
    L.append("## 怎么用（三步）")
    L.append("")
    L.append("1. 想找**某个主题**的勘测 ⇒ 按 `标题` 扫这张表；")
    L.append("2. 想知道**这份报告到底有没有落地** ⇒ 看 `门禁读数` 列（⚠️ **读数 ≠ 采纳**，见下）；")
    L.append("3. 想**引它当依据** ⇒ ⛔ 不行。要先有一条 `D-###` 裁定**采纳**它，再引那条裁定。")
    L.append("")
    L.append("## 读数（**只从磁盘复算**）")
    L.append("")
    L.append("| 量 | 值 |")
    L.append("|---|---|")
    L.append(f"| 报告份数 | **{len(reports)}** |")
    L.append(f"| ⭐ **在本仓里被提到过**（`门禁读数 ≥ 1`） | **{len(cited)} / {len(reports)}** |")
    L.append(f"| ⛔ **一次都没被提到**（`门禁读数 = 0`） | **{len(never)} / {len(reports)}** |")
    L.append(f"| 头部带 `基线` 字段 | **{sum(1 for r in reports if r['base'] != '—')} / {len(reports)}** |")
    L.append("")
    L.append("## 表（**门禁逐字节比对的就是这一段**：id / 标题 / 基线 / 行数）")
    L.append("")
    L.append("| id | 标题 | 基线 | 行数 |")
    L.append("|---|---|---|---:|")
    for r in sorted(reports, key=lambda x: [int(y) if y.isdigit() else float(y)
                                            for y in re.split(r"\.", x["id"])]):
        L.append(f"| **`survey/{r['id']}`** | {r['title'][:78]} | {r['base']} | {r['lines']} |")
    L.append("")
    if others:
        L.append("## ⚠️ 本目录里**不是报告**的文件（⛔ 单列，不混进上表）")
        L.append("")
        L.append("| 文件 | 它自称是什么 | 行数 |")
        L.append("|---|---|---:|")
        for q in others:
            head = q.read_text(encoding="utf-8").splitlines()[:6]
            claim = next((re.sub(r"[*`>]", "", l).strip() for l in head
                          if "不是报告" in l or "活文档" in l), "（未自称）")
            L.append(f"| `{q.name}` | {claim[:76]} | {len(q.read_text(encoding='utf-8').splitlines())} |")
        L.append("")
        L.append("⭐ **为什么单列**：把它们算进『报告』会让「几份报告 / 几份被采纳」这两个读数失真；"
                 "⛔ 而把它们**漏掉**更坏（读者以为这个目录里只有报告）。")
        L.append("")

    L.append("## ⛔ 本表**假装不了**的两件事（诚实边界）")
    L.append("")
    L.append("1. ⛔ **`门禁读数` 不是『采纳了』的证明** —— 它只数『报告 id 在 `survey/` 之外出现过几次』"
             "（裁定 / `src/` / `tools/` / 其它文档）。⭐ 它把可疑面缩到人能看完的规模，"
             "⛔ **判不了『引它的人是不是把它当依据』**（那需要语义）。")
    L.append(f"2. ⛔ **{len(reports)} 份报告里没有一份带 `落档判定` 行** —— 草案 `E1` 要求"
             "「逐份补 `落档判定`（🔴需裁/🟡挂账/🟢参考/⚪数据）」，那是 🟡 档、量大 50 份 ⇒ "
             "**本刀不做**。本表只把『哪些一次都没被提到』摆出来。")
    L.append("")
    L.append(APPENDIX_MARKER)
    L.append("")
    L.append("> ⚠️ **下面这张表不在门禁比对范围内** —— 任何一次引用都会让它变，"
             "算进比对会让门禁退化成「例行敲一下」（`docs/DECISIONS_INDEX.md` 的同一教训）。")
    L.append("")
    L.append("| id | 裁定 | src | tools | 其它文档 |")
    L.append("|---|---:|---:|---:|---:|")
    for r in sorted(reports, key=lambda x: [int(y) if y.isdigit() else float(y)
                                            for y in re.split(r"\.", x["id"])]):
        n = r["id"]
        tot = adopt.get(n, 0)
        L.append(f"| **`survey/{n}`** | {'✅' if tot else '⛔'} {tot or ''} | | | |")
    L.append("")
    return "\n".join(L) + "\n"


def build() -> tuple[str, list[dict]]:
    if not SURVEY.is_dir():
        print(f"SURVEY_INDEX_RESULT FAIL: 目录不存在 {SURVEY}", file=sys.stderr)
        raise SystemExit(1)
    files = sorted(p for p in SURVEY.glob("*.md") if p.name != "README.md")
    reports = [parse_report(p) for p in files if ID.match(p.name)]
    n = len(reports)
    if n < ASSERTIONS["报告份数"]:
        print(f"SURVEY_INDEX_RESULT FAIL: 解析崩塌 —— 报告份数={n} < 下限 {ASSERTIONS['报告份数']}"
              "（目录搬了？命名变了？⇒ 登记表『只剩几行』读起来却完全正常，⛔ 不许少读一截还绿）",
              file=sys.stderr)
        raise SystemExit(1)
    #: ⚠️ **不以编号打头的文件不算报告** —— 但它们**必须在表里可见**，⛔ 不许静默漏掉。
    #: 实测活例（2026-10-02）：`survey/INTENT.md` 自称「**活文档，不是报告**，不受只增不改约束」
    #: ⇒ ⭐ 它是**③/① 类的东西住在 ⑤ 类的目录里**（又一条"分类"问题）⇒ 单列一节，⛔ 不混进报告表。
    others = sorted(p for p in SURVEY.glob("*.md") if p.name != "README.md" and not ID.match(p.name))
    noid = [r["file"] for r in reports if r["id"] == "—"]
    if noid:
        print(f"SURVEY_INDEX_RESULT FAIL: {len(noid)} 份文件以编号打头但解析不出 id：{', '.join(noid[:5])}",
              file=sys.stderr)
        raise SystemExit(1)
    return render(reports, adoption_counts(), others), reports


def main() -> int:
    ap = argparse.ArgumentParser(description="生成/校验 survey/README.md（勘测报告登记表）")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--write", action="store_true")
    g.add_argument("--check", action="store_true")
    args = ap.parse_args()

    rendered, reports = build()
    n = len(reports)
    if args.write:
        OUTPUT.write_text(rendered, encoding="utf-8")
        print(f"SURVEY_INDEX_RESULT WROTE: {n} 份报告 → {OUTPUT.relative_to(ROOT)}")
        return 0
    if not OUTPUT.exists():
        print(f"SURVEY_INDEX_RESULT FAIL: {OUTPUT.relative_to(ROOT)} 不存在", file=sys.stderr)
        return 1

    def gated(text: str) -> list[str] | None:
        return text.split(APPENDIX_MARKER, 1)[0].splitlines() if APPENDIX_MARKER in text else None

    disk, gen = gated(OUTPUT.read_text(encoding="utf-8")), gated(rendered)
    if disk is None:
        print(f"SURVEY_INDEX_RESULT FAIL: 缺 `{APPENDIX_MARKER}` 标记（被手改过？）", file=sys.stderr)
        return 1
    assert gen is not None
    if disk != gen:
        detail = "行数不同" if len(disk) != len(gen) else "行数相同、内容不同"
        first = next((i for i, (a, b) in enumerate(zip(disk, gen), 1) if a != b), None)
        print(f"SURVEY_INDEX_RESULT FAIL: 登记表已陈旧（{detail}"
              + (f"，首个不同在第 {first} 行" if first else "")
              + "）⇒ 跑 `python3 tools/survey-index.py --write` 并提交", file=sys.stderr)
        return 1
    cited = sum(1 for r in reports if adoption_counts().get(r["id"], 0))
    #: ⚠️ 每次 `--check` 重算两次 `adoption_counts()` 很浪费 ⇒ 只算一次
    
    print(f"SURVEY_INDEX_RESULT PASS: {n} 份报告 · 被提到过 {cited} · 逐字节相同"
          f"（⚠️ 附录「采纳读数」**不在**比对范围）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
