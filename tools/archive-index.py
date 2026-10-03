#!/usr/bin/env python3
"""生成 / 校验**归档目录的 `README.md`**（6 个归档目录，**生成物，禁手改**）。

## ⭐ 为什么有它（用户 2026-10-02 裁 ⑦：**不统一分法**，改成两条判据）

草案 v1 的 ⑥ 类只写「**无"何时入库"规则**」，而实测**归档有两套、两种分法**：

| 归档根 | 子目录 | 分法 |
|---|---|---|
| `docs/archive/` | `legacy-design` / `legacy-testing` / `legacy-workflow` / `legacy-2026-08` | ⭐ **按内容类型**（"这是什么"） |
| `.alice-supervision/archive/` | `2024-08-2024-09` / `legacy-2026-08` | ⭐ **按日期世代**（"哪一代的"） |

⭐ **用户裁定（⑦）**：⛔ **不统一分法** —— 两种分法各有道理，一个答「这是什么」，一个答「哪一代的」。
⇒ 改成 **① 两条判据（按内容类型 / 按日期世代）② 每个归档目录写明自己属于哪一种**。
⚠️ 而实测 **6 个目录一个都没有 README**、**归档相关门禁 = 0 道** ⇒ 本生成器 ＋ 门禁补上。

## 单一出处

| 列 | 出处 |
|---|---|
| 子目录名 | ⭐ **磁盘** |
| 份数 | ⭐ **磁盘**（`rglob` 数文件，⛔ 手写会腐烂） |
| 判据 | ⭐ 由它在哪个归档根下**机械决定**（`docs/archive/` ⇒ 内容类型；`.alice-supervision/archive/` ⇒ 日期世代） |

用法：`python3 tools/archive-index.py --write`　·　`--check`　·　`--selftest`
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

#: 归档根 → (判据名, 一句话解释)
ROOTS: list[tuple[str, str, str]] = [
    ("docs/archive", "按内容类型",
     "按「**这是什么**」分（design / testing / workflow / 按月份）⇒ 找「某类东西的旧版」用这个根"),
    (".alice-supervision/archive", "按日期世代",
     "按「**哪一代的**」分（`2024-08-2024-09` = 上一代工作流 · `legacy-2026-08` = 更早一代）"
     "⇒ 找「某个时期的做法」用这个根"),
]

#: ⚠️ 人口下限：子目录数少于此 ⇒ **响亮失败**（目录搬了 ⇒ 扫不到就报绿，不放过）。实测 = 6。
DIR_FLOOR = 5

MARK = "<!-- 生成物：tools/archive-index.py -->"


def census(d: Path) -> tuple[int, int]:
    """返回 (全部文件数, `.md` 数) —— ⛔ 两个口径都报，免得"314 还是 31"这种歧义。

    ⚠️⚠️ **必须排除本生成器自己写的 `README.md`** —— 第一版没排除，于是：
    写一次 ⇒ 份数 +1 ⇒ 再读时数字与刚写的不同 ⇒ ⛔ **门禁永远判"陈旧"**。
    ⭐ 这是**生成物把自己算进自己的输入**这一族错（自指），实测当场撞出来的。
    """
    files = [p for p in d.rglob("*") if p.is_file() and p.name != "README.md"]
    return len(files), sum(1 for p in files if p.suffix == ".md")


def render(root_rel: str, kind: str, why: str, sub: Path) -> str:
    n_all, n_md = census(sub)
    L: list[str] = []
    A = L.append
    A(MARK)
    A(f"# `{root_rel}/{sub.name}/` —— 归档目录（**{kind}**）")
    A("")
    A("> ⚠️ **本文件由脚本生成** —— 手改会被门禁判红。")
    A("> 重新生成：`python3 tools/archive-index.py --write`　·　校验：`python3 tools/archive-index.py --check`")
    A("> 门禁：`tools/check-archive-index.sh`（挂 `tools/check-all.sh`）。")
    A("")
    A("## 这个目录按什么分")
    A("")
    A(f"**{kind}** —— {why}")
    A("")
    A("⭐ 本项目**两套归档、两种判据共存**（2026-10-02 用户裁：⛔ **不统一** —— "
      "两种分法各有道理，一个答「这是什么」，一个答「哪一代的」）。")
    A("")
    A("## 读数（**只从磁盘复算**）")
    A("")
    A("| 量 | 值 |")
    A("|---|---|")
    A(f"| 文件总数（含全部后缀） | **{n_all}** |")
    A(f"| 其中 `.md` | **{n_md}** |")
    A("")
    A("## ⛔ 归档铁律")
    A("")
    A("1. ⛔ **归档件不作当前规则** —— 依据一律引 `D-###` 或门禁，⛔ 别引这里的文件；")
    A("2. ⛔ **只标不删** —— 要作废就在**原位**加指针（项目惯例：原文不改，只加指针）；")
    A("3. ⚠️ **这里没有「何时入库」的判据** —— 草案 v2 `§D′-7` 只定了「按什么分」，"
      "**「谁来判、什么时候判」仍未裁**（登记在 `§D′-8`）。")
    A("")
    return "\n".join(L) + "\n"


def build(root: Path) -> tuple[dict[Path, str], list[str]]:
    out: dict[Path, str] = {}
    problems: list[str] = []
    subs: list[Path] = []
    for rel, kind, why in ROOTS:
        base = root / rel
        if not base.is_dir():
            problems.append(f"⛔ 归档根不存在：{rel} ⇒ 目录搬了 / 改名了")
            continue
        for sub in sorted(p for p in base.iterdir() if p.is_dir()):
            subs.append(sub)
            out[sub / "README.md"] = render(rel, kind, why, sub)
    if len(subs) < DIR_FLOOR:
        problems.append(f"⛔ 只扫到 {len(subs)} 个归档子目录 < 人口下限 {DIR_FLOOR} ⇒ "
                        f"扫不到就报绿的入口，不放过")
    return out, problems


def write() -> int:
    out, problems = build(ROOT)
    if problems:
        for x in problems:
            print(f"  [FAIL] {x}")
        print("ARCHIVE_INDEX_RESULT FAIL: 有结构缺陷，⛔ 不准写")
        return 1
    for p, text in out.items():
        p.write_text(text, encoding="utf-8")
    print(f"ARCHIVE_INDEX_RESULT WROTE: {len(out)} 个归档 README")
    return 0


def check() -> int:
    out, problems = build(ROOT)
    if problems:
        for x in problems:
            print(f"  [FAIL] {x}")
        print(f"ARCHIVE_INDEX_RESULT FAIL: {len(problems)} 处")
        return 1
    bad = 0
    for p, text in sorted(out.items()):
        rel = p.relative_to(ROOT)
        if not p.is_file():
            print(f"  [FAIL] ⛔ 缺 {rel} ⇒ 跑 `--write`")
            bad += 1
        elif p.read_text(encoding="utf-8") != text:
            print(f"  [FAIL] ⛔ {rel} 已陈旧（手改过 / 磁盘变了）⇒ 跑 `--write`")
            bad += 1
    if bad:
        print(f"ARCHIVE_INDEX_RESULT FAIL: {bad} 处")
        return 1
    print(f"ARCHIVE_INDEX_RESULT PASS: {len(out)} 个归档 README · 逐字节相同")
    return 0


def selftest() -> int:
    """三臂注入自证 —— ⭐ 「能过」≠「能抓」。"""
    arms: list[tuple[str, str, bool]] = []
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        for rel, _, _ in ROOTS:
            (root / rel).mkdir(parents=True)
        for rel, subs in (("docs/archive", ["a", "b"]), (".alice-supervision/archive", ["c", "d", "e"])):
            for s in subs:
                (root / rel / s).mkdir()
                (root / rel / s / "x.md").write_text("x\n", encoding="utf-8")

        out, problems = build(root)
        arms.append(("A 好结构（6 目录）", "", not problems and len(out) == 5))
        # 臂 B：人口下限
        empty = Path(td) / "empty"
        (empty / "docs" / "archive").mkdir(parents=True)
        out2, p2 = build(empty)
        arms.append(("B 人口下限", "人口下限", any("人口下限" in x for x in p2)))
        # 臂 C：归档根缺失
        (root / "docs" / "archive").rename(root / "docs" / "archive_moved")
        _, p3 = build(root)
        arms.append(("C 归档根缺失", "不存在", any("不存在" in x for x in p3)))

        bad = 0
        for name, want, ok in arms:
            bad += not ok
            print(f"  [{'PASS' if ok else 'FAIL'}] {name} ⇒ {'期望红且含「' + want + '」' if want else '期望安静'}")
        print(f"ARCHIVE_INDEX_SELFTEST {'PASS' if not bad else 'FAIL'}: arms={len(arms)} failed={bad}")
        return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        return selftest()
    if a.write:
        return write()
    return check()


if __name__ == "__main__":
    sys.exit(main())
