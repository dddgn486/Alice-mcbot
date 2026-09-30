#!/usr/bin/env python3
"""`P2` **单向阀**：`task/` 顶层**文件集**必须 ⊆ 冻结名单（`D-551` 用户裁定「乙：提前上线」）。

## 为什么要有它

`R4`（`D-492`，2026-09-27 生效：「新写的调试／测试类**直接放对包**，不再新增到 `task/` 顶层」）
**已经被静默违反 2 次**（`O76` §5：`BreakHazardCheckTask` · `K2AdjacentGoalCheckTask` 是**纯夹具**
却落在 `task/` 顶层）。静默的原因是可机械检出的：`tools/fixture-hygiene.py` 的 `SOURCE_ROOTS`
**包含** `task/`，但它只查**命名**、**不查包位**。⇒ 本条给它**一颗牙**。

`R4` 原文另有一句「门禁与迁移**必须同刀**，不得提前落」（怕的是"包边界"判据**今天就会红**）。
本阀**不是**那条判据：它**今天绿**，只在**有人往 `task/` 顶层加文件**时才红 ⇒
与 `R4` 那句话要防的东西不冲突。⚠️ **这条是用户 2026-09-29 明示裁决的**（`D-551`）。

## ⛔ 为什么**没有** `--write`

有 `--write` 就等于"一条命令洗白违规"（新文件会被静默吸收进名单）。
⇒ 例外只有两种，且都必须**手改名单**：**(a)** 该波搬走 ⇒ **同刀删行**；
**(b)** 极少数确有必要新增到 `task/` 顶层的生产类 ⇒ 手加一行 **＋ 提交信息里写理由**。
名单里每行的存在性**双向都查**：多了（现存有、名单无）红，少了（名单有、现存无）红。

用法：
    python3 tools/check-task-top-freeze.py
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC_REL = "src/main/java/"
TASK_TOP = SRC_REL + "com/dddgn/alice/task"
FREEZE = ROOT / "docs/TASK_TOP_LEVEL_FREEZE.txt"

MIN_LINES = 100          # 人口下限：今天 142；掉到 100 以下 ⇒ 名单被截断或解析器坏了


def current_top_level() -> list[str]:
    """⇒ `task/` **顶层**（不含子包）的 `.java`，全仓相对路径，已排序。"""
    r = subprocess.run(["git", "-c", "core.quotePath=false", "ls-files", "--", TASK_TOP],
                       cwd=ROOT, capture_output=True, text=True, check=True)
    prefix_depth = TASK_TOP.count("/")
    out = []
    for ln in r.stdout.splitlines():
        if not ln.endswith(".java"):
            continue
        if ln.count("/") != prefix_depth + 1:          # 顶层才收，子包不算
            continue
        out.append(ln)
    if not out:
        raise SystemExit("TASK_TOP_FREEZE_RESULT FAIL: `task/` 顶层一个类都没扫到 ⇒ 收集器坏了")
    return sorted(out)


def read_freeze() -> list[str]:
    if not FREEZE.exists():
        raise SystemExit(f"TASK_TOP_FREEZE_RESULT FAIL: 缺 `{FREEZE.relative_to(ROOT)}`")
    return [ln.strip() for ln in FREEZE.read_text(encoding="utf-8").split("\n") if ln.strip()]


def main() -> int:
    cur = current_top_level()
    frozen = read_freeze()
    problems: list[str] = []

    if len(frozen) < MIN_LINES:
        problems.append(f"名单只有 {len(frozen)} 行（下限 {MIN_LINES}）⇒ 被截断或没建好"
                        f"（⛔ 空名单不许读成「没有要检查的」）")
    if frozen != sorted(frozen):
        problems.append("名单**未排序**（`git diff` 才有意义；手改时请保持字典序）")
    if len(set(frozen)) != len(frozen):
        dup = sorted({x for x in frozen if frozen.count(x) > 1})
        problems.append(f"名单有**重复行**：{dup[:5]}")

    for path in sorted(set(cur) - set(frozen)):
        cls = Path(path).stem
        problems.append(f"⚠️ **新文件落进 `task/` 顶层**：`{path}` ⇒ 按 `R4` 它应直接落在"
                        f"目的地包里（`debug/`／`fixture/`／`step/`／生产层）；"
                        f"确有必要则**手加**名单一行 ＋ 提交信息写理由（⛔ 本工具不提供 `--write`）")
    for path in sorted(set(frozen) - set(cur)):
        problems.append(f"**名单有、实物无**（陈旧行）：`{path}` ⇒ 搬走后必须**同刀删掉这一行**")

    if problems:
        print("TASK_TOP_FREEZE_RESULT FAIL: `task/` 顶层与冻结名单不一致")
        for p in problems[:15]:
            print(f"  - {p}")
        if len(problems) > 15:
            print(f"  … 共 {len(problems)} 条")
        return 1

    print(f"TASK_TOP_FREEZE_RESULT PASS: 冻结 {len(frozen)} 行 · 现存 {len(cur)} 个 · "
          f"名单外新增 0 · 陈旧行 0 ⇒ `task/` 顶层**单调不增**（进度表：{len(cur)} → 0）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
