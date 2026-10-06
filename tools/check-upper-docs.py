#!/usr/bin/env python3
"""门禁：`docs/upper-design/` —— **架构级／跨包设计**那一层的「**创建与修改都必须开发者逐份审核**」。

## 它在守什么（开发者 2026-10-06 逐字）

> 「不要创建 `docs/CROSS_PACKAGE_DESIGN.md`，而是创建一个 **`docs/upper-design`** 目录，这个目录用来放
> **架构级或者跨包的设计**，但是这里面的**每个文档创建必须我审核**，而且**一定要纳入当前体系管理**，
> **修改也要我审批**」

⇒ 那两句是**规则**，而本仓的纪律是「**门禁 > 散文**」：散文会过期、会被绕过、语义会分歧；
⭐ **能让构建失败的东西才算边界**。本脚本就是那两条规则的机械形式。

## 判据（全部机械 · ⛔ 不带手感）

设 `docs/upper-design/` 下**被 git 跟踪的** `.md` 为「件集 `D`」，
基线 `tools/upper-design-baseline.tsv` 为「登记集 `B`」（列：`相对路径 <TAB> sha256 <TAB> 类标`）：

  ① ⛔ **新件必须登记**（`D − B ≠ ∅` ⇒ 红）—— 这就是「**创建必须我审核**」；
  ② ⛔ **不许留假账**（`B − D ≠ ∅` ⇒ 红）—— 登记了却不在磁盘上 ⇒ 要么删了没登记，要么路径错了；⭐ **删也要他批**；
  ③ ⛔ **改一个字节也要重批**（`sha256` 不符 ⇒ 红）—— 这就是「**修改也要我审批**」；
  ④ ⛔ **必须纳入体系**（每份件头必须有 `**类标**`，且类号 = `⑧`）—— 这就是「**纳入当前体系管理**」；
  ⑤ ⚠️ **空目录是合法状态**（`|D| = 0` ⇒ 安静，⛔ 不报红）—— 但 ⛔ **不许"扫不到就报绿"**：
     臂 A′ 专证「有件时 ①–④ 真的会跑」（这是本仓最贵那族错 `§E`）。

## `--write` 是什么

⭐ **`--write` = 开发者的批准动作**，⛔ **不是"重新生成一下"** —— 它把当前磁盘状态写进基线
（新增／改动／删除都算批准）⛔ 但**必须把"批准了什么"逐条打印出来**（⛔ 不许静默吞掉）。
⚠️ 本仓有过一次教训：某个 `--write` 的语义**本来是"我批准这个新增"**，却长得像"重新生成一下"
⇒ ⛔ **违例被洗成合规**（见 `tools/ref-anchors.py` 的 `increments()` docstring）。
因此本脚本：**(a)** 打印全部批准项；**(b)** `--write` 之后**立刻自查一遍**并打印结论。

## 边界（⛔ 如实写）

* ⛔ 它**审不了内容**（「这份设计对不对」是人的判断 —— 那是开发者审核本身，不是门禁的活）；
* ⛔ 它**管不到 `docs/upper-design/` 之外的件**；
* ⚠️ 它**今天挡不住"整份删掉再补一份新的"** —— 那条形状上等价于"删除 + 新增"，两步都红，⭐ 但**两次都要他批**之后就会放行（那正是他的意图）。
"""
from __future__ import annotations

import argparse
import hashlib
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DIR = ROOT / "docs" / "upper-design"
BASELINE = ROOT / "tools" / "upper-design-baseline.tsv"

#: ⭐ 「纳入当前体系管理」的机械形式 —— 类号必须是它（`§D′-1` ⑧ 设计）。
CLASS_CODE = "⑧"
CLASS_MARK = re.compile(r"^\s*>?\s*\*\*类标\*\*\s*[：:]\s*(.+?)\s*$", re.MULTILINE)


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


def tracked_items() -> dict[str, str]:
    """→ {相对路径: 内容}（⭐ 只收 **被 git 跟踪**的 `.md`；`.gitkeep` 不算件）。

    ⚠️ 必须 `git ls-files -z`（不带 `-z` 时非 ASCII 路径会被 git 加引号 ⇒ 漏件 —— 本仓踩过）。
    """
    out = subprocess.run(["git", "ls-files", "-z", "--", "docs/upper-design"],
                         cwd=ROOT, capture_output=True, text=True).stdout
    items: dict[str, str] = {}
    for rel in out.split("\0"):
        if not rel.endswith(".md"):
            continue
        items[rel] = (ROOT / rel).read_text(encoding="utf-8", errors="replace")
    return items


def class_code(text: str) -> str | None:
    """→ 件头 `**类标**` 的类号（第一个字符）；⛔ 没有那一行 ⇒ `None`。"""
    m = CLASS_MARK.search("\n".join(text.splitlines()[:40]))
    return m.group(1)[:1] if m else None


def load_baseline() -> dict[str, tuple[str, str]]:
    if not BASELINE.exists():
        return {}
    out: dict[str, tuple[str, str]] = {}
    for line in BASELINE.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) != 3:
            raise ValueError(f"基线行格式不对（要 3 列 TSV）：{line!r}")
        out[parts[0]] = (parts[1], parts[2])
    return out


def audit(items: dict[str, str], base: dict[str, tuple[str, str]]) -> list[str]:
    """⭐ **纯函数**（⇒ 可注入自证 · 臂 A′–G 全喂它）。→ 问题清单（空 = 安静）。"""
    problems: list[str] = []
    for rel in sorted(set(items) - set(base)):
        problems.append(f"⛔ **判据①**：`{rel}` **没有登记** ⇒ 本目录的件**创建必须开发者逐份审核** "
                        f"⇒ 审核过之后跑 `python3 tools/check-upper-docs.py --write`（⭐ 那一步 = 批准）")
    for rel in sorted(set(base) - set(items)):
        problems.append(f"⛔ **判据②**：`{rel}` **登记了却不在磁盘上** ⇒ ⛔ 不许留假账"
                        f"（删也要开发者批 ⇒ 批过之后跑 `--write`）")
    for rel in sorted(set(items) & set(base)):
        #: ⚠️ 基线的第 3 列（类号）**只作登记留痕**，⛔ 不参与判定 ——
        #:    因为判据③ 是**逐字节**的：类标行改了 ⇒ `sha256` 必然变 ⇒ ③ 已经会红。
        want_sha, _want_cls = base[rel]
        got_sha = sha256(items[rel])
        if got_sha != want_sha:
            problems.append(f"⛔ **判据③**：`{rel}` **内容变了**（`sha256` {want_sha} → {got_sha}）"
                            f"⇒ 本目录的件**修改必须开发者审批** ⇒ 批过之后跑 `--write`")
        got_cls = class_code(items[rel])
        if got_cls != CLASS_CODE:
            problems.append(f"⛔ **判据④**：`{rel}` 的 `**类标**` "
                            f"{'缺失' if got_cls is None else f'类号是 `{got_cls}`'} ⇒ "
                            f"必须是 `{CLASS_CODE}`（⭐ 这就是「纳入当前体系管理」）")
    return problems


def snapshot(items: dict[str, str]) -> dict[str, tuple[str, str]]:
    return {rel: (sha256(text), class_code(text) or "") for rel, text in items.items()}


def check() -> int:
    items = tracked_items()
    base = load_baseline()
    problems = audit(items, base)
    if problems:
        print("UPPER_DOCS_RESULT FAIL")
        for p in problems:
            print(f"  [FAIL] {p}")
        return 1
    n = len(items)
    print(f"UPPER_DOCS_RESULT PASS: `docs/upper-design/` **{n} 件** —— "
          f"⛔ 每件的创建与修改都必须开发者逐份审核（`--write` = 批准）"
          + ("　⭐ 本目录**故意为空**（规则先立、门开着）" if n == 0 else ""))
    return 0


def write() -> int:
    items = tracked_items()
    old = load_baseline()
    new = snapshot(items)
    added = sorted(set(new) - set(old))
    removed = sorted(set(old) - set(new))
    changed = sorted(r for r in set(new) & set(old) if new[r][0] != old[r][0])
    if not (added or removed or changed):
        print("UPPER_DOCS_RESULT WROTE: ⚠️ 没有变化 —— 基线已与磁盘一致（⛔ 什么都没批准）")
        return 0
    print("⚠️  `--write` ⇒ **批准**以下变更（⭐ 这一步就是开发者的审核动作）：")
    for r in added:
        print(f"  + 新增 {r}（类标 {new[r][1] or '⛔ 缺'}）")
    for r in changed:
        print(f"  ~ 改动 {r}（{old[r][0]} → {new[r][0]}）")
    for r in removed:
        print(f"  - 删除 {r}")
    lines = ["# `docs/upper-design/` 登记表 —— ⛔ 每件的**创建与修改都必须开发者逐份审核**",
             "# 生成：python3 tools/check-upper-docs.py --write（⭐ 那一步 = 批准；⛔ 不是“重新生成一下”）",
             "# 列：相对路径 <TAB> sha256(前 16) <TAB> 类号", ""]
    for rel in sorted(new):
        lines.append(f"{rel}\t{new[rel][0]}\t{new[rel][1]}")
    BASELINE.write_text("\n".join(lines) + "\n", encoding="utf-8")
    rc = check()
    print(f"  ⇒ 写后自查：{'✅ 安静' if rc == 0 else '⛔ 仍不安静'}")
    return rc


def selftest() -> int:
    arms: list[tuple[str, bool]] = []
    ok_text = "> **类标**：⑧ 设计　｜　**沿革**：2026-10-06\n\n正文\n"
    good = {"docs/upper-design/a.md": ok_text}
    good_base = snapshot(good)

    # 臂 A：空目录 + 空基线 ⇒ 安静（⭐ 「空」是合法状态，⛔ 不报红）
    arms.append(("A 空目录 ⇒ 安静", audit({}, {}) == []))
    # 臂 A′：⛔ **不许"扫不到就报绿"** —— 有件且都合规时也必须安静（证明判据真的跑过、且不误报）
    arms.append(("A′ 有件且全合规 ⇒ 安静", audit(good, good_base) == []))
    # 臂 B：未登记的新件 ⇒ 红（判据① = 创建必须审核）
    arms.append(("B 未登记的新件 ⇒ 红（判据①）",
                 any("判据①" in p for p in audit(good, {}))))
    # 臂 C：登记了却不在磁盘上 ⇒ 红（判据② = 不许留假账）
    arms.append(("C 基线有、磁盘没有 ⇒ 红（判据②）",
                 any("判据②" in p for p in audit({}, good_base))))
    # 臂 D：内容改了 ⇒ 红（判据③ = 修改也要审批）
    mod = {"docs/upper-design/a.md": ok_text + "多一行\n"}
    arms.append(("D 内容改了 ⇒ 红（判据③）",
                 any("判据③" in p for p in audit(mod, good_base))))
    # 臂 D′：⭐ **逐字节相同**（只是重算了一次）⇒ 安静（证明判据③ 判的是内容，不是时间戳）
    arms.append(("D′ 内容逐字节相同 ⇒ 安静", audit(dict(good), good_base) == []))
    # 臂 E：类号不是 ⑧ ⇒ 红（判据④ = 纳入体系）
    wrong = {"docs/upper-design/a.md": "> **类标**：④ 状态 ＋ 未裁提案\n\n正文\n"}
    arms.append(("E 类号不是 ⑧ ⇒ 红（判据④）",
                 any("判据④" in p for p in audit(wrong, snapshot(wrong)))))
    # 臂 F：没有类标 ⇒ 红（判据④）
    noclass = {"docs/upper-design/a.md": "# 标题\n\n正文\n"}
    arms.append(("F 没有类标 ⇒ 红（判据④）",
                 any("判据④" in p for p in audit(noclass, snapshot(noclass)))))
    # 臂 G：⭐ **四条判据不许互相掩盖** —— 一次喂进「①未登记 + ②假账 + ③改了 + ④没类标」，
    #        四条**必须同时出现在**问题清单里（⛔ 不许"报了第一条就 return"）。
    mixed_items = {
        "docs/upper-design/a.md": "# 无类标\n",          # 在基线里 ⇒ ③（内容变了）＋ ④（没类标）
        "docs/upper-design/b.md": ok_text,                 # 不在基线里 ⇒ ①（未登记）
    }
    mixed_base = dict(good_base)
    mixed_base["docs/upper-design/c.md"] = ("deadbeefdeadbeef", "⑧")   # 磁盘上没有 ⇒ ②（假账）
    probs = audit(mixed_items, mixed_base)
    arms.append(("G 四条判据同时报（⛔ 不互相掩盖）",
                 all(any(f"判据{c}" in p for p in probs) for c in "①②③④")))

    failed = 0
    for name, ok in arms:
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}")
        failed += 0 if ok else 1
    print(f"UPPER_DOCS_SELFTEST {'PASS' if not failed else 'FAIL'}: arms={len(arms)} failed={failed}")
    return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser(description="门禁：docs/upper-design/ 的创建与修改必须开发者逐份审核")
    ap.add_argument("--write", action="store_true", help="⭐ 批准当前磁盘状态（= 开发者审核动作）")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        return selftest()
    if a.write:
        return write()
    return check()


if __name__ == "__main__":
    sys.exit(main())
