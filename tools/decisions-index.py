#!/usr/bin/env python3
"""**决策索引**（台账 `O12`，2026-09-28）：把 23,616 行的 `docs/AI_DECISIONS.md` 压成一张能读的表。

## 为什么有它（`O12` 实测，不是感觉）
* `AGENTS.md` 原文让每个会话「先读 `AI_DECISIONS.md`」，而它是 **23,616 行 / 1,225,967 字符**
  ⇒ 粗估 **0.6M–1.2M tokens** ⇒ **读不进 512K 窗口** ⇒ **那条启动指令按字面不可满足**。
* 而它是**唯一**的规则出处：找不到前置裁定就会**重复立法**
  （活例 = `D-455` 漏引 `D-080`，`survey/43 §10.2` —— 不是理论风险，是**已经发生**过的）。
* 它还没有索引，且**标题级别不统一**：161 个编号用 `##`、378 个用 `###`
  ⇒ ⚠️ `grep "^### D-"` **漏掉 114 个编号**（这就是"靠名字找东西静默少给一半"的同一族）。

## 两个反向纪律（照 `tools/capability-list.py` 的模子）
* **不引入第二个真相源**：本索引**不新增任何事实** —— 标题 / 状态 / 追加数全从 `AI_DECISIONS.md` 读，
  引用数从全仓扫出来。**与 `AI_DECISIONS.md` 不一致时以它为准**（生成器改的是索引，不是决策）。
* **解析崩塌必须响亮失败**：`ASSERTIONS` 带**人口下限**（决策编号数 / 标题数）——
  少读一截 ⇒ 红，不许"少一行悄悄过"。

## 它**不是**什么（防误用）
* ⚠️ 它是**索引**，不是规则的替代：要读某条决策的**正文**，去 `docs/AI_DECISIONS.md`（见文末定位命令）。
* ⚠️ 它**不是**"哪些决策已废弃"的权威 —— `状态` 字段**只覆盖约 15%**；
  显示 `—` 只表示"**条目里没写状态**"，**不代表废弃**。
"""

from __future__ import annotations

import argparse
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DECISIONS = ROOT / "docs" / "AI_DECISIONS.md"
OUTPUT = ROOT / "docs" / "DECISIONS_INDEX.md"

#: 引用扫描范围（**本索引自身排除**，否则自引用会让每个编号恒 +1）
#: ⚠️ 「引前」（某条决策引了几条别的决策）不走这里 —— 它由每条自己的**文本段**算，见 `parse_decisions`。
SCAN_SOURCES: list[tuple[str, Path, set[str], set[str]]] = [
    ("src", ROOT / "src" / "main" / "java", {".java"}, set()),
    ("tools", ROOT / "tools", {".sh", ".py", ".mjs"}, set()),
    ("其它", ROOT / "docs", {".md"}, {"docs/AI_DECISIONS.md", "docs/DECISIONS_INDEX.md"}),
    ("survey", ROOT / "survey", {".md"}, set()),
]

#: ⚠️ 人口下限：解析崩塌 ⇒ 响亮失败（2026-09-28 实测值 = 378 / 598，留足余量但拦得住崩塌）
#: ⚠️ 键必须与 `build()` 里 `stats` 的键**逐字相同**（不一致 ⇒ `KeyError` 崩溃，这比静默通过好）
ASSERTIONS = {"决策编号数": 300, "标题数": 400}

HEADING = re.compile(r"^(#{2,3})\s+(D-\d+)\s*(.*)$")
STATUS = re.compile(r"状态[：:]\s*\*{0,2}\s*([^\n*（(]+)")
DNUM = re.compile(r"\bD-\d+\b")
#: （保留：仅用于文档说明；判定追加条目现在按"同编号的第 2+ 个标题"数，不再做文字匹配）
FOLLOWUP_KEYS = ("附注", "修正", "修订", "验收", "补遗", "追加", "更正", "补充")

#: ⚠️ **门禁比对到此行为止**。本行以下（「全仓引用热度」）是**易变列**：
#: 只要**任何** doc / src / tools 里多提一次某个 `D-###`，计数就变 —— 而台账与断点几乎每把刀都在引编号。
#: 2026-09-28 实测教训：第一版把计数列放进被比对的内容 ⇒ **门禁在落地后几分钟就红了**
#:（我刚改完台账它就"陈旧"）⇒ 照那样**几乎每把刀都得跑一次 `--write`**，门禁会退化成"例行敲一下"。
#: ⇒ **结构列（编号 / 标题 / 状态 / 追加 / 引前）进比对；热度列只生成、不比对**（`--write` 顺手刷新）。
APPENDIX_MARKER = "## 附录：全仓引用热度（**不计入门禁比对**）"


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def parse_decisions(text: str) -> tuple[list[dict], int]:
    """→ ([{num,title,status,followups,cites,line}], 标题总数)。

    ⚠️ **编号总数必须把 `##` 与 `###` 两种标题都算上**（2026-09-28 实测：`##` 独有 114 ·
    `###` 独有 331 · 两者都有 47 ⇒ **并集 492**）。只数 `###` 会**漏 114 个编号** ——
    这正是 `O12` 里那个"靠名字找东西静默少给一半"的错，**我自己第一版就犯了一次**。
    """
    lines = text.splitlines()
    heads: list[tuple[int, str, str]] = []  # (行号, 编号, 标题原文)
    for i, line in enumerate(lines):
        m = HEADING.match(line)
        if m:
            heads.append((i, m.group(2), m.group(3).strip()))
    if not heads:
        return [], 0

    per_num: Counter = Counter(h[1] for h in heads)
    entries: list[dict] = []
    seen: set[str] = set()
    for pos, (line_no, num, rest) in enumerate(heads):
        if num in seen:
            continue
        seen.add(num)
        # 本编号的**整段** = 从它第一个标题，到**下一个不同编号**的标题（⇒ 追加条目算在内）
        end = len(lines)
        for j in range(pos + 1, len(heads)):
            if heads[j][1] != num:
                end = heads[j][0]
                break
        block = "\n".join(lines[line_no:end])
        title = re.sub(r"^[：:\s]+", "", rest)
        if not title:
            # 裸标题（如 `### D-374` 换行后才写标题）⇒ 取第一段非空文本
            for probe in lines[line_no + 1:end]:
                s = probe.strip().lstrip("#").strip()
                if s:
                    title = s
                    break
        title = re.sub(r"[*`]", "", title).strip()
        statuses = [s.strip() for s in STATUS.findall(block) if s.strip()]
        entries.append({
            "num": num,
            "title": title[:88] or "(无标题)",
            "status": statuses[-1] if statuses else "—",   # 追加条目会更新状态 ⇒ 取最后一条
            "followups": per_num[num] - 1,
            "cites": len({n for n in DNUM.findall(block) if n != num}),
            "line": line_no + 1,
        })
    return entries, len(heads)


def scan_refs() -> dict[str, Counter]:
    out: dict[str, Counter] = {}
    for label, base, exts, skip in SCAN_SOURCES:
        counter: Counter = Counter()
        if base.is_file():
            files = [base]
        elif base.is_dir():
            files = [p for p in base.rglob("*") if p.is_file() and p.suffix in exts]
        else:
            files = []
        for path in files:
            rel = path.relative_to(ROOT).as_posix()
            if rel in skip:
                continue
            try:
                body = path.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            for num in set(DNUM.findall(body)):
                counter[num] += 1
        out[label] = counter
    return out


def esc(cell: str) -> str:
    return cell.replace("|", "\\|").replace("\n", " ").strip()


def render(entries: list[dict], heading_total: int, refs: dict[str, Counter]) -> str:
    declared = {e["num"] for e in entries}
    src = refs["src"]
    in_force = sum(1 for n in declared if src.get(n))
    never = sorted(n for n in declared if not any(refs[k].get(n) for k in ("src", "tools", "其它", "survey")))
    followups = sum(e["followups"] for e in entries)
    with_status = sum(1 for e in entries if e["status"] != "—")
    no_cite = sorted(e["num"] for e in entries if e["cites"] == 0)
    level_note = "`### D-###` 与早期 `## D-###` 两种标题"

    lines: list[str] = []
    lines.append("# 决策索引（**生成物，禁手改**）")
    lines.append("")
    lines.append("> ⚠️ **本文件由脚本生成** —— 手改会被门禁判红。")
    lines.append("> 重新生成：`python3 tools/decisions-index.py --write`　·　校验：`python3 tools/decisions-index.py --check`")
    lines.append("> 门禁：`tools/check-decisions-index.sh`（挂 `tools/check-all.sh`）。")
    lines.append("")
    lines.append("## 为什么有这张表")
    lines.append("")
    lines.append("`AGENTS.md` 原文让每个会话「**先读 `AI_DECISIONS.md`**」，而它是")
    lines.append(f"**{len(read_text(DECISIONS).splitlines()):,} 行**、**{len(read_text(DECISIONS)):,} 字符** ⇒ 粗估 **0.6M–1.2M tokens**")
    lines.append("⇒ ⚠️ **读不进 512K 窗口** ⇒ **那条启动指令按字面不可满足**（台账 `O12` 实测）。")
    lines.append("而它又是**唯一**的规则出处：找不到前置裁定就会**重复立法**（活例 = `D-455` 漏引 `D-080`）。")
    lines.append("⇒ 所以：**先读这张索引找编号，再只读那一条正文**。")
    lines.append("")
    lines.append("## 怎么用（三步）")
    lines.append("")
    lines.append("1. 在这张表里按**关键词**找标题 ⇒ 拿到 `D-###`；")
    lines.append("2. 看 `状态` / `追加` / 引用数粗判它**还在不在生效**（`src` 有数 = 代码里指得到它）；")
    lines.append("3. 读正文：`grep -n -A 30 '^##\\{0,1\\}# D-0NN' docs/AI_DECISIONS.md`")
    lines.append("   （⚠️ 用 `grep` **不要用行号** —— 追加条目插在中间会挪行号，而 `check-ref-integrity` 只查超界、查不出错位）")
    lines.append("")
    lines.append("## 读数（**只从 `AI_DECISIONS.md` 算** ⇒ 不含易变列）")
    lines.append("")
    lines.append("| 量 | 值 |")
    lines.append("|---|---|")
    lines.append(f"| 决策**编号**数 | **{len(declared)}**（{level_note}，标题共 {heading_total} 个） |")
    lines.append(f"| 追加条目（`附注`/`修正`/`验收`…） | **{followups}** 个（占标题 {followups / heading_total * 100:.0f}%） |")
    lines.append(f"| 写了 `状态：` 的条目 | **{with_status} / {len(declared)}**（{with_status / len(declared) * 100:.0f}%）⚠️ 其余显示 `—` = **没写**，**不等于废弃** |")
    lines.append(f"| ⚠️ **一条别的决策都没引**（`引前 = 0`） | **{len(no_cite)}** 条（= 决策点 3「新落 `D` 应引前置裁定」**今天完全没有牙**的人口） |")
    lines.append("")
    lines.append("## 表（**门禁逐字节比对的就是这一段**：编号 / 标题 / 状态 / 追加 / 引前）")
    lines.append("")
    lines.append("| 编号 | 标题 | 状态 | 追加 | 引前 |")
    lines.append("|---|---|---|---:|---:|")
    for e in sorted(entries, key=lambda x: int(x["num"][2:])):
        lines.append(
            f"| **{e['num']}** | {esc(e['title'])} | {esc(e['status'])[:28]} | "
            f"{e['followups'] or ''} | {e['cites'] or ''} |"
        )
    lines.append("")
    lines.append("> **列义**：`追加` = 这条决策下面还挂了几条 `附注/修正/验收`（多 ⇒ 条目臃肿）·")
    lines.append("> `引前` = 这条决策**正文里引了几条别的决策**（⚠️ `0` = 它在孤立法）。")
    lines.append(">")
    lines.append(f"> ⚠️ **`引前` 只是一道筛子，不是判据**：它能拦「**一条别的决策都没引**」（今天 {len(no_cite)} 条），")
    lines.append("> 但**拦不住** `D-455` 那种情形 —— 它 `引前 = 4`，**照样漏引了 `D-080`**（`survey/43 §10.2`）。")
    lines.append("> ⇒ 想机械判定「有没有引到**该引的那条**」需要语义，做不到；这一列只把可疑面缩到人能看完的规模。")
    lines.append("")
    lines.append(APPENDIX_MARKER)
    lines.append("")
    lines.append("> ⚠️ **下面这张表不在门禁比对范围内**（`tools/check-decisions-index.sh` 只比到上面那张表为止）。")
    lines.append("> 原因：引用计数**易变** —— 任何 doc / src / tools 多提一次某个编号它就变，而台账与断点几乎每把刀都在引编号；")
    lines.append("> 若把它算进比对，**几乎每把刀都要跑一次 `--write`**，门禁会退化成「例行敲一下」。")
    lines.append("> ⇒ 它只是**顺手刷新**的热度参考；**要最新值就跑一次 `--write`**（或直接看 `src` 那一列的旧值当量级）。")
    lines.append("")
    lines.append("| 编号 | src | tools | 其它 |")
    lines.append("|---|---:|---:|---:|")
    for e in sorted(entries, key=lambda x: int(x["num"][2:])):
        n = e["num"]
        s, t = refs["src"].get(n, 0), refs["tools"].get(n, 0)
        o = refs["其它"].get(n, 0) + refs["survey"].get(n, 0)
        if s or t or o:
            lines.append(f"| **{n}** | {s or ''} | {t or ''} | {o or ''} |")
    lines.append("")
    lines.append(f"> ⚠️ **从未被任何地方引用**：**{len(never)}** 条"
                 f"（= 上表里连一行都没有的编号）—— {', '.join(never) if never else '无'}")
    lines.append("> ⚠️ **被 `src/` 引用**（≈**还在生效**）：**{}/{}**（{:.0f}%）".format(
        in_force, len(declared), in_force / len(declared) * 100))
    lines.append("")
    return "\n".join(lines) + "\n"


def build() -> tuple[str, dict]:
    text = read_text(DECISIONS)
    entries, heading_total = parse_decisions(text)
    stats = {"决策编号数": len(entries), "标题数": heading_total}
    for key, floor in ASSERTIONS.items():
        if stats[key] < floor:
            print(f"DECISIONS_INDEX_RESULT FAIL: 解析崩塌 —— {key}={stats[key]} < 下限 {floor}"
                  f"（少读一截不许悄悄过）", file=sys.stderr)
            raise SystemExit(1)
    refs = scan_refs()
    return render(entries, heading_total, refs), stats


def main() -> int:
    ap = argparse.ArgumentParser(description="生成/校验 docs/DECISIONS_INDEX.md")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--write", action="store_true", help="写入 docs/DECISIONS_INDEX.md")
    g.add_argument("--check", action="store_true", help="校验是否陈旧（陈旧 ⇒ 非零退出）")
    args = ap.parse_args()

    rendered, stats = build()

    if args.write:
        OUTPUT.write_text(rendered, encoding="utf-8")
        print(f"DECISIONS_INDEX_RESULT WROTE: {stats['决策编号数']} 决策 / {stats['标题数']} 标题 → {OUTPUT.relative_to(ROOT)}")
        return 0

    if not OUTPUT.exists():
        print(f"DECISIONS_INDEX_RESULT FAIL: {OUTPUT.relative_to(ROOT)} 不存在"
              f"（跑 `python3 tools/decisions-index.py --write`）", file=sys.stderr)
        return 1

    def gated(text: str) -> list[str] | None:
        """取 `APPENDIX_MARKER` **之前**的部分（= 门禁比对范围）。缺标记 ⇒ None（响亮失败）。"""
        if APPENDIX_MARKER not in text:
            return None
        return text.split(APPENDIX_MARKER, 1)[0].splitlines()

    on_disk = OUTPUT.read_text(encoding="utf-8")
    disk_gated, gen_gated = gated(on_disk), gated(rendered)
    if disk_gated is None:
        print(f"DECISIONS_INDEX_RESULT FAIL: {OUTPUT.relative_to(ROOT)} 缺 `{APPENDIX_MARKER}` 标记"
              f"（文件被手改过？）⇒ 跑 `--write` 重生", file=sys.stderr)
        return 1
    assert gen_gated is not None  # 生成器自己一定带标记
    if disk_gated != gen_gated:
        detail = "行数不同" if len(disk_gated) != len(gen_gated) else "行数相同、内容不同"
        first = next((i for i, (a, b) in enumerate(zip(disk_gated, gen_gated), 1) if a != b), None)
        print(f"DECISIONS_INDEX_RESULT FAIL: 索引**结构列**已陈旧（{detail}"
              + (f"，首个不同在第 {first} 行" if first else "") + "）"
              f" ⇒ 跑 `python3 tools/decisions-index.py --write` 并提交", file=sys.stderr)
        return 1
    print(f"DECISIONS_INDEX_RESULT PASS: {stats['决策编号数']} 决策 / {stats['标题数']} 标题"
          f" / 结构列与 {OUTPUT.relative_to(ROOT)} 逐字节相同"
          f"（⚠️ 附录「引用热度」**不在**比对范围）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
