#!/usr/bin/env python3
"""门禁：**自称「提案 / 草案」的件，必须自报状态，且不许自相矛盾**。

## ⭐ 为什么有它（用户 2026-10-02 裁 ③；⚠️ 判据是**实测**出来的，不是我推的）

草案 v2 的 **④ 类**里有「**提案未裁**」这一半 —— 而它今天**没有任何东西盯着**。
⛔ 后果是一族**假边界**：把一份"还没定"的东西当依据引（`§E` 逐字：「**把未完成的构想按边界格式落档
= 制造一条假边界**」）。

⭐ **落地时当场抓到 3 处（零误报）**：

| 件 | 抓到什么 |
|---|---|
| `docs/authz/POLICY_MATRIX_PROPOSAL.md` | ⛔ **同页自相矛盾** —— 标题写「**未接线，待拍板**」，`> 状态` 写「**已拍板并接线**（2026-09-14）」⇒ **文件名骗人**，读者按文件名会以为它还没定 |
| `docs/authz/PROPOSAL_B_survival_write_authorization.md` | ⚠️ 缺统一的 `> **状态**：` 行（它自己那句在正文里，⛔ `grep` 的状态行正则抓不到） |
| `docs/RISK_SYSTEM_DESIGN_DRAFT.md` | ⚠️ 同上（名字自称 DRAFT，头部没有状态行） |

## 判据（两道，都机械可判）

1. **名字自称提案**（文件名含 `PROPOSAL` / `DRAFT` / `PROPOSED`）⇒ 头部 25 行内**必须有** `> **状态**：` 行。
   ⭐ 理由：文件名是**最先被读到的**东西，它必须先给出"这份定没定"。
2. **自称未裁**（头部含 `未接线`/`待拍板`/`未批准`/`未实现`）⇒ 头部**不许同时**含
   `已拍板`/`已接线`/`已实现`/`已批准`。⛔ 两个说法并存 = **自相矛盾**（读者按哪个都对不上）。

## ⛔ 本门禁**假装不了**的

1. ⛔ **它判不了"到底该不该接线"** —— 只判"文件有没有如实自报"。
2. ⛔ **它判不了状态行写的是不是真话**（那要读正文 ＋ 核代码）。
3. ⚠️ 它**只管 `docs/`**（⛔ 不含 `docs/archive/` —— 归档件不适用）。

用法：`python3 tools/check-proposal-status.py`　·　`--selftest`
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"

HEAD_LINES = 25
NAME_PAT = re.compile(r"PROPOSAL|DRAFT|PROPOSED", re.I)
#: ⚠️ **半角 `**状态**` 与全角 `**状态**` 都要认** —— 第一版只认半角 ⇒
#: 对 `docs/authz/POLICY_MATRIX_PROPOSAL.md` **假红**（它用的是全角）。
#: ⚠️ 三种写法都要认（实测各有一份）：半角粗体 `> **状态**：` · 全角粗体 · **无粗体** `> 状态：` ——
#: 第一版只认半角 ⇒ 假红；第二版带了粗体组但漏了 `re.M` ⇒ 也假红。**两处都是我自己造的**。
STATUS_DECL = re.compile(r"^>\s*(?:\*\*|＊＊)?\s*状态\s*(?:\*\*|＊＊)?\s*[：:]", re.M)
UNDECIDED = re.compile(r"未接线|待拍板|未批准|未实现")
DECIDED = re.compile(r"已拍板|已接线|已实现|已批准")

#: ⚠️ 人口下限：扫到的 `docs/**.md` 少于此 ⇒ **响亮失败**（范围崩塌）。
DOC_FLOOR = 60


def problems_for(paths: list[Path], docs: Path) -> tuple[list[str], int]:
    out: list[str] = []
    n = 0
    for p in sorted(paths):
        if "archive/" in str(p):
            continue
        n += 1
        head = "\n".join(p.read_text(encoding="utf-8", errors="ignore").splitlines()[:HEAD_LINES])
        rel = p.relative_to(docs.parent) if docs.parent in p.parents else p
        if NAME_PAT.search(p.name) and not STATUS_DECL.search(head):
            out.append(f"{rel}: ⛔ 文件名自称提案/草案，但头部 {HEAD_LINES} 行内**没有** `> **状态**：` 行 ⇒ "
                       f"文件名是最先被读到的，它必须先给出「定没定」")
        # ⚠️ **只看状态行本身**，⛔ 不看整段头部 —— 第二版看整段 ⇒ 对
        #    `POLICY_MATRIX_PROPOSAL.md` 假红：它的**更正说明**里逐字引用了原标题
        #    （「未接线，待拍板」）⇒ 那是**引文**，⛔ 不是两个并存的声称。
        #    ⭐ 一般纪律：**判"自相矛盾"要判同一句话的范围**，把引文也算进去必假红。
        #    （本会话我在这个门禁上前后造了**三处**假红/漏判，全部如实记在上面。）
        m = STATUS_DECL.search(head)
        if m:
            line = head[m.start():].splitlines()[0]
            if UNDECIDED.search(line) and DECIDED.search(line):
                out.append(f"{rel}: ⛔ **自相矛盾** —— 同一行状态里同时出现"
                           f"「{UNDECIDED.search(line).group(0)}」与「{DECIDED.search(line).group(0)}」⇒ "
                           f"（`§E`：把未完成的构想按边界格式落档 = **制造一条假边界**）")
    return out, n


def run(docs: Path | None = None) -> int:
    docs = docs or DOCS
    paths = list(docs.rglob("*.md"))
    if len(paths) < DOC_FLOOR:
        print(f"PROPOSAL_STATUS_RESULT FAIL: 只扫到 {len(paths)} 份 < 人口下限 {DOC_FLOOR} ⇒ "
              f"范围崩塌（扫不到就报绿的入口，不放过）")
        return 1
    problems, n = problems_for(paths, docs)
    if problems:
        print(f"PROPOSAL_STATUS_RESULT FAIL: {len(problems)} 处（扫了 {n} 份）")
        for x in problems:
            print(f"  [FAIL] {x}")
        return 1
    print(f"PROPOSAL_STATUS_RESULT PASS: 扫了 {n} 份 · 名字自称提案的都自报状态 · 无自相矛盾")
    return 0


def selftest() -> int:
    """四臂注入自证 —— ⚠️ 臂 A/B 用的是**落地时真抓到的那两处**的形状。"""
    with tempfile.TemporaryDirectory() as td:
        docs = Path(td) / "docs"
        docs.mkdir()
        arms: list[tuple[str, str, bool]] = []

        def check(files: dict[str, str]) -> tuple[list[str], int]:
            (docs / "archive").mkdir(exist_ok=True)
            for name, body in files.items():
                (docs / name).write_text(body, encoding="utf-8")
            ps, n = problems_for(list(docs.rglob("*.md")), docs)
            for name in files:
                (docs / name).unlink()
            return ps, n

        # 臂 A：名字自称 PROPOSAL ＋ 无状态行 ⇒ 红（真抓到的那处形状）
        p, _ = check({"X_PROPOSAL.md": "# 提案\n\n> 依据：D-1\n"})
        arms.append(("A 提案缺状态行", "没有** `> **状态**", any("状态" in x for x in p)))
        # 臂 B：**同一行**状态里自相矛盾 ⇒ 红
        p, _ = check({"Y.md": "# t\n\n> **状态**：未接线，待拍板，但已拍板并接线\n"})
        arms.append(("B 同一行自相矛盾", "自相矛盾", any("自相矛盾" in x for x in p)))
        # 臂 B′：引文里有旧说法 ⇒ **必须安静**（⛔ 不许把更正说明判成矛盾 —— 我为此假红过一次）
        p, _ = check({"Y2.md": "# t\n\n> **状态**：已拍板并接线\n> ⚠️ 原标题逐字是「未接线，待拍板」\n"})
        arms.append(("B′ 更正说明引旧说法 ⇒ 安静", "", not p))
        # 臂 C：提案 ＋ 有状态行 ＋ 不自相矛盾 ⇒ 安静
        p, _ = check({"Z_PROPOSAL.md": "# t\n\n> **状态**：提案（未批准、未实现）\n"})
        arms.append(("C 好件", "", not p))
        # 臂 D：归档件不适用
        (docs / "archive").mkdir(exist_ok=True)
        (docs / "archive" / "OLD_PROPOSAL.md").write_text("# t\n", encoding="utf-8")
        p, _ = problems_for(list(docs.rglob("*.md")), docs)
        arms.append(("D 归档件豁免", "", not p))
        (docs / "archive" / "OLD_PROPOSAL.md").unlink()

        bad = 0
        for name, want, ok in arms:
            bad += not ok
            print(f"  [{'PASS' if ok else 'FAIL'}] {name} ⇒ "
                  f"{'期望红且含「' + want + '」' if want else '期望安静'}")
        print(f"PROPOSAL_STATUS_SELFTEST {'PASS' if not bad else 'FAIL'}: arms={len(arms)} failed={bad}")
        return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        return selftest()
    return run()


if __name__ == "__main__":
    sys.exit(main())
