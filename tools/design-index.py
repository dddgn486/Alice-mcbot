#!/usr/bin/env python3
"""**设计索引**（`docs/DESIGN_INDEX.md`）：把「**每个顶层包的设计说明在哪**」压成一张能读的表。

## 为什么有它（用户 2026-10-02 逐字）
> 「项目也确实需要**一个设计总文档**，至少要带上**散落的设计索引**吧，**索引从下往上维护**」

⭐ **"从下往上"= 索引是 ⟵ 生成物，真相在 `package-info.java`**：
设计说明住在**它描述的那个包的** `package-info.java` 里（改代码的人顺手就能改），
本索引只把它们**汇总**起来 ⇒ ⛔ **它不新增任何事实**（与 `docs/DECISIONS_INDEX.md` 同一条纪律）。

## 它解决什么（实测人口，2026-10-02）
`src/main/java/com/dddgn/alice/` 下 **30 个顶层包**，其中只有 **9 个**有 `package-info.java`
⇒ ⛔ **21 个包"没有设计说明"这件事，今天在任何地方都看不见**。
本索引把这件事**摆到台面上**（`设计说明` 列显示 `—`）⇒ 它**不假装完整**。

## 它**不是**什么（防误用）
* ⚠️ **不是设计本身**：要读设计去那个包的 `package-info.java`（表里有路径）。
* ⚠️ **不是裁定**：`受哪些 D-### 约束` 一列是**从 `package-info.java` 里扫出来的引用**，
  不是"这些决策只管这个包"的判断 —— 扫不到就可能**真的没引**，也可能是**引了别的写法**。
* ⚠️ **它不判断设计好坏**：`—` 只表示"**这个包没有 `package-info.java`**"，⛔ 不代表这个包不重要。

## 门禁（`tools/check-design-index.sh`）
* **人口下限**：顶层包数 ≥ 25（⛔ 解析崩塌不许"少读一截还绿"）；
* **一致**：索引与**实际顶层包集合**逐一对应（少一个包 ⇒ 红 · 多一个包 ⇒ 红）；
* **不陈旧**：重新生成 ⇒ 逐字节相同。
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src" / "main" / "java"
PKG_ROOT = SRC / "com" / "dddgn" / "alice"
OUTPUT = ROOT / "docs" / "DESIGN_INDEX.md"

#: ⚠️ 人口下限：2026-10-02 实测 **30** 个顶层包。留余量但拦得住崩塌
#:（写坏路径 ⇒ 扫到 0 个包 ⇒ 必须**响亮失败**，⛔ 不许"索引里只剩一个包"也照样绿）
ASSERTIONS = {"顶层包数": 25}

DNUM = re.compile(r"\bD-\d+\b")
HEADING = re.compile(r"^\s*\*\s*<h2>(.*?)</h2>\s*$")
JAVADOC_OPEN = re.compile(r"^\s*/\*\*")

#: 门禁比对到此行为止（本行以下是**易变列**：类数会随每次改动变 ⇒ 不进比对，只生成）
APPENDIX_MARKER = "## 附录：类数明细（**不计入门禁比对**）"


def strip_md(s: str) -> str:
    """把 JavaDoc 里混的 Markdown / HTML 标记压成纯文本（只用于**摘要显示**，⛔ 不改源文件）。"""
    s = re.sub(r"<[^>]+>", "", s)
    s = s.replace("{@code ", "").replace("}", "")
    s = re.sub(r"[*`]", "", s)
    return re.sub(r"\s+", " ", s).strip()


def package_dirs() -> list[Path]:
    """→ **所有含 `package-info.java` 的包**（任意深度），保证与 `find src -name package-info.java` 逐一对上。

    ⚠️ 2026-10-02 实测教训：第一版只返回 `PKG_ROOT` 的**直接子目录** ⇒
    含 `package-info.java` 的**子包**（`action/craft/` · `region/authz/`）**不在表里**，
    于是读数写成"缺 23"而实际是"缺 21" —— ⭐ **索引自己少列了两个包，却没有任何东西会红**。
    ⇒ 现在按"谁有 `package-info.java`"取，⛔ 不按目录深度取。
    """
    if not PKG_ROOT.is_dir():
        return []
    return sorted((f.parent for f in PKG_ROOT.rglob("package-info.java")), key=lambda p: p.as_posix())


def top_level_names() -> list[str]:
    """顶层包名（只用于**完整性自证**：`package-info` 的**拥有者数**不得少于顶层包数）。"""
    return sorted(d.name for d in PKG_ROOT.iterdir() if d.is_dir())


def all_package_names() -> list[str]:
    """全树所有包名（含子包）—— 与 `find src/main/java -name package-info.java` 的分母对齐。"""
    return sorted({str(f.parent.relative_to(SRC)).replace("/", ".")
                   for f in SRC.rglob("package-info.java")})


def read_package_info(pkg: Path) -> dict:
    """→ {has, summary, sections, cites}。`has=False` ⇒ 其余为空。"""
    f = pkg / "package-info.java"
    if not f.is_file():
        return {"has": False, "summary": "", "sections": [], "cites": [], "lines": 0}
    text = f.read_text(encoding="utf-8")
    lines = text.splitlines()
    body: list[str] = []
    for line in lines:
        if JAVADOC_OPEN.match(line):
            continue
        if HEADING.match(line):
            break
        body.append(line)
    summary = ""
    for raw in body:
        s = raw.strip().lstrip("*").strip()
        if not s or s.startswith("<p>") is False and s.startswith(("<", "@")):
            if not s or s.startswith("@"):
                break
            continue
        s = re.sub(r"^<p>\s*", "", s)
        if not s or s.startswith(("<", "@")):
            continue
        summary = strip_md(s)
        if summary:
            break
    sections = [strip_md(m.group(1)) for line in lines if (m := HEADING.match(line))]
    return {
        "has": True,
        "summary": summary,
        "sections": sections,
        "cites": sorted(set(DNUM.findall(text)), key=lambda s: int(s[2:])),
        "lines": len(lines),
    }


def class_counts(pkg: Path) -> tuple[int, int]:
    return (len(list(pkg.glob("*.java"))), len(list(pkg.rglob("*.java"))))


def collect() -> list[dict]:
    """→ **每个"有 `package-info.java` 的包"** ＋ **每个顶层包**（两者取并集，⛔ 一个都不许漏）。

    ⚠️ 并集是必须的：只取前者 ⇒ 21 个没设计的顶层包**看不见**；只取后者 ⇒ `action/craft/` 这类**子包看不见**
    （2026-10-02 实测：第一版只取后者 ⇒ 读数写成"缺 23"而实际是"缺 21"）。
    """
    seen: dict[str, dict] = {}
    for pkg in package_dirs():                     # 有 package-info 的（含子包）
        pi = read_package_info(pkg)
        top, allc = class_counts(pkg)
        depth = len(pkg.relative_to(PKG_ROOT).parts)
        seen[pkg.as_posix()] = {
            "name": f"{pkg.name}/",
            "path": str(pkg.relative_to(PKG_ROOT)).replace("/", "/") + "/",
            "rel": pkg.relative_to(ROOT).as_posix(), "depth": depth, **pi,
            "top_classes": top - (1 if pi["has"] else 0),
            "all_classes": allc - (1 if pi["has"] else 0),
        }
    for name in top_level_names():                 # 顶层包（不管有没有设计说明）
        pkg = PKG_ROOT / name
        if pkg.as_posix() in seen:
            continue
        top, allc = class_counts(pkg)
        seen[pkg.as_posix()] = {
            "name": f"{name}/", "path": f"{name}/", "rel": pkg.relative_to(ROOT).as_posix(), "depth": 1,
            "has": False, "summary": "", "sections": [], "cites": [], "lines": 0,
            "top_classes": top, "all_classes": allc,
        }
    return [seen[k] for k in sorted(seen)]


def esc(cell: str) -> str:
    return cell.replace("|", "\\|").replace("\n", " ").strip()


def render(pkgs: list[dict]) -> str:
    tops = [p for p in pkgs if p["depth"] == 1]
    subs = [p for p in pkgs if p["depth"] > 1]
    have = [p for p in tops if p["has"]]
    miss = [p for p in tops if not p["has"]]
    lines: list[str] = []
    lines.append("# 设计索引（**生成物，禁手改**）")
    lines.append("")
    lines.append("> ⚠️ **本文件由脚本生成** —— 手改会被门禁判红。")
    lines.append("> 重新生成：`python3 tools/design-index.py --write`　·　校验：`python3 tools/design-index.py --check`")
    lines.append("> 门禁：`tools/check-design-index.sh`（挂 `tools/check-all.sh`）。")
    lines.append("")
    lines.append("## 为什么有这张表")
    lines.append("")
    lines.append("> 用户 2026-10-02 逐字：「项目也确实需要**一个设计总文档**，至少要带上**散落的设计索引**吧，**索引从下往上维护**」")
    lines.append("")
    lines.append("⭐ **设计说明住在它描述的那个包里**（`package-info.java`）⇒ 改代码的人顺手就能改。")
    lines.append("本索引只是把它们**汇总**起来，⛔ **不新增任何事实**（与 `docs/DECISIONS_INDEX.md` 同一条纪律）。")
    lines.append("")
    lines.append("## 怎么用（三步）")
    lines.append("")
    lines.append("1. 想知道**某个包是干什么的** ⇒ 在这张表里找它，看 `首句`；")
    lines.append("2. 想知道**为什么这么切** ⇒ 读它的 `package-info.java`（`设计位置` 列有路径）；")
    lines.append("3. 想知道**它受哪些裁定约束** ⇒ 看 `受约束` 列（⚠️ 这是从 `package-info.java` **扫出来的引用**，不是判断）。")
    lines.append("")
    lines.append("## 读数（**只从 `package-info.java` 与目录结构算**）")
    lines.append("")
    lines.append("| 量 | 值 |")
    lines.append("|---|---|")
    lines.append(f"| 顶层包数 | **{len(tops)}** |")
    lines.append(f"| ⭐ **顶层包有设计说明**（`package-info.java`） | **{len(have)} / {len(tops)}**（{len(have) / len(tops) * 100:.0f}%） |")
    lines.append(f"| ⛔ **顶层包没有设计说明** | **{len(miss)} / {len(tops)}** ⇒ 见下表 `设计位置 = —` 的行 |")
    lines.append(f"| 子包有设计说明 | **{len(subs)}**（{', '.join('`' + s['name'] + '`' for s in subs) or '无'}） |")
    secs = sum(len(p["sections"]) for p in pkgs if p["has"])
    lines.append(f"| 首节（`<h2>`）总数 | **{secs}**（⚠️ 编号风格**不统一**：`一 ·` / `零 ` / 无编号 ⇒ 尚未统一，见 `D1`） |")
    lines.append("")
    lines.append("## 表（**门禁逐字节比对的就是这一段**：包 / 首句 / 设计位置 / 受约束）")
    lines.append("")
    lines.append("| 包 | 首句（`package-info` 第一段） | 设计位置 | 受约束 |")
    lines.append("|---|---|---|---|")
    for p in pkgs:
        loc = f"`{p['rel']}/package-info.java`" if p["has"] else "—"
        cites = " ".join(f"`{c}`" for c in p["cites"]) if p["cites"] else "—"
        label = p["name"] if p["depth"] == 1 else f"↳ {p['path']}"
        lines.append(f"| **`{label}`** | {esc(p['summary']) or '—'} | {loc} | {esc(cites)} |")
    lines.append("")
    lines.append("> **列义**：`首句` = `package-info.java` 里**第一个 `<h2>` 之前**的第一段文字"
                 "（⛔ 摘要，不是设计）·")
    lines.append("> `受约束` = 该文件里**扫到的** `D-###` 引用（⚠️ 扫不到 ≠ 不受约束）·")
    lines.append("> `↳` 打头的行 = **子包**（列在它自己的完整路径下）。")
    lines.append("")
    lines.append("## ⛔ 本索引**假装不了**的两件事（诚实边界）")
    lines.append("")
    lines.append(f"1. ⛔ **{len(miss)} 个顶层包没有设计说明** —— 表里显示 `—`。本索引**只暴露**这件事，"
                 "⛔ 不代替你去补；补一份 = 在那个包里加一个 `package-info.java`。")
    lines.append("2. ⛔ **首节的编号风格不统一**（`一 ·` / `零 ⭐⭐ ` / 无编号）—— "
                 "本索引**照原样抄**，⛔ 不做归一（归一是 `D1` 那一刀，改了才算）。")
    lines.append("")
    lines.append(APPENDIX_MARKER)
    lines.append("")
    lines.append("> ⚠️ **下面这张表不在门禁比对范围内** —— 类数**每次改动都变**，"
                 "算进比对会让门禁退化成「例行敲一下」（`docs/DECISIONS_INDEX.md` 的同一教训）。")
    lines.append("")
    lines.append("| 包 | 顶层 `.java` | 全树 `.java` |")
    lines.append("|---|---:|---:|")
    for p in pkgs:
        lines.append(f"| **`{p['name']}/`** | {p['top_classes']} | {p['all_classes']} |")
    lines.append("")
    return "\n".join(lines) + "\n"


def build() -> tuple[str, dict, list[dict]]:
    pkgs = collect()
    stats = {"顶层包数": len(pkgs)}
    for key, floor in ASSERTIONS.items():
        if stats[key] < floor:
            print(f"DESIGN_INDEX_RESULT FAIL: 解析崩塌 —— {key}={stats[key]} < 下限 {floor}"
                  f"（路径写坏 / 包没了 ⇒ 少读一截不许悄悄过）", file=sys.stderr)
            raise SystemExit(1)
    #: ⭐ **完整性自证**：两张人口必须对上 —— ⛔ 只要有一个包被漏出表外，就**响亮失败**。
    #: （2026-10-02 实测：第一版只取顶层目录 ⇒ `action/craft/` 与 `region/authz/` 两个**子包漏出表外**，
    #:  而**没有任何东西会红** —— 索引自己少列了包，读起来却完全正常。这正是「扫不到就报绿」那一族。）
    in_table = {p["rel"] for p in pkgs}
    dropped = [d.relative_to(ROOT).as_posix() for d in package_dirs() if d.relative_to(ROOT).as_posix() not in in_table]
    if dropped:
        print(f"DESIGN_INDEX_RESULT FAIL: **包漏出表外** —— 磁盘上有 `package-info.java` 的包 "
              f"{len(dropped)} 个不在索引里：{', '.join(dropped)}"
              f"（索引读起来正常，但它**没列出全部包** —— 这正是本门禁要拦的那族错）", file=sys.stderr)
        raise SystemExit(1)
    n_top = len(top_level_names())
    if n_top != stats["顶层包数"] - sum(1 for p in pkgs if p["depth"] > 1):
        print(f"DESIGN_INDEX_RESULT FAIL: **顶层包漏出表外** —— 磁盘上顶层包 {n_top} 个，"
              f"而索引里顶层行只有 {stats['顶层包数'] - sum(1 for p in pkgs if p['depth'] > 1)} 个", file=sys.stderr)
        raise SystemExit(1)
    return render(pkgs), stats, pkgs


def main() -> int:
    ap = argparse.ArgumentParser(description="生成/校验 docs/DESIGN_INDEX.md")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--write", action="store_true", help="写入 docs/DESIGN_INDEX.md")
    g.add_argument("--check", action="store_true", help="校验是否陈旧（陈旧 ⇒ 非零退出）")
    args = ap.parse_args()

    rendered, stats, pkgs = build()
    n_top = sum(1 for p in pkgs if p["depth"] == 1)
    have = sum(1 for p in pkgs if p["has"] and p["depth"] == 1)
    subs = sum(1 for p in pkgs if p["has"] and p["depth"] > 1)
    summary = (f"{n_top} 顶层包 · 有设计说明 {have} · 缺 {n_top - have}"
               f" · 子包有 {subs}（`package-info.java` 共 {have + subs} 份）")

    if args.write:
        OUTPUT.write_text(rendered, encoding="utf-8")
        print(f"DESIGN_INDEX_RESULT WROTE: {summary} → {OUTPUT.relative_to(ROOT)}")
        return 0

    if not OUTPUT.exists():
        print(f"DESIGN_INDEX_RESULT FAIL: {OUTPUT.relative_to(ROOT)} 不存在"
              f"（跑 `python3 tools/design-index.py --write`）", file=sys.stderr)
        return 1

    def gated(text: str) -> list[str] | None:
        if APPENDIX_MARKER not in text:
            return None
        return text.split(APPENDIX_MARKER, 1)[0].splitlines()

    on_disk = OUTPUT.read_text(encoding="utf-8")
    disk_gated, gen_gated = gated(on_disk), gated(rendered)
    if disk_gated is None:
        print(f"DESIGN_INDEX_RESULT FAIL: {OUTPUT.relative_to(ROOT)} 缺 `{APPENDIX_MARKER}` 标记"
              f"（文件被手改过？）⇒ 跑 `--write` 重生", file=sys.stderr)
        return 1
    assert gen_gated is not None
    if disk_gated != gen_gated:
        detail = "行数不同" if len(disk_gated) != len(gen_gated) else "行数相同、内容不同"
        first = next((i for i, (a, b) in enumerate(zip(disk_gated, gen_gated), 1) if a != b), None)
        print(f"DESIGN_INDEX_RESULT FAIL: 设计索引已陈旧（{detail}"
              + (f"，首个不同在第 {first} 行" if first else "") + "）"
              f" ⇒ 跑 `python3 tools/design-index.py --write` 并提交", file=sys.stderr)
        return 1
    print(f"DESIGN_INDEX_RESULT PASS: {summary} / 与 docs/DESIGN_INDEX.md 逐字节相同"
          f"（⚠️ 附录「类数明细」**不在**比对范围）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
