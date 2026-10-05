#!/usr/bin/env python3
"""门禁：**「提取」的三阶段状态**（2026-10-05 立；出处 = 咨询回执 `003` 生效规则 8）。

⭐⭐ **为什么有它** —— 本工序（`W7′-8`）是**唯一一个"做完了没有"不由件数决定**的工序：
`W7′-4` 的进度可以数「56 件里判了几件」，而**提取**的进度是
「**188 份摘要产出多少** / 筛出多少件 / 落地多少节」—— ⛔ 这三样**没有任何东西盯着**。

⚠️⚠️ **它是被一次真实事故逼出来的形状**：本仓上一次「提取没有落脚点」（回执 `003` 的题）
⇒ 这一次落点定了，⭐ 可**落点一定就要有东西回答「离做完还有多远」**，否则
「提不完」这件事会**静默地**发生（⭐ 本仓最贵那族错：扫不到就报绿）。

判据（**四条**，⭐ 强制力在本文件，**八臂**注入自证）：

· **① 结构**：`docs/EXTRACTED_KNOWLEDGE.md` 里**必须**各有一个 `## … 阶段 N …` 二级标题
  （N = 1／2／3），⭐ **各恰好一个** ⇒ ⛔ 改名／合并／删节 ⇒ 红（**解析不到就响亮失败**）。
· **② 上限**：阶段 1 的摘要条数 ≤ `TARGET1`（**188**） ⇒ ⛔ 超了即红
  （⭐ 靶子**冻结**：回执 `003` 生效规则 9「只提取那 188 件，⛔ 不追加」）。
· **③ 不跳阶段**：阶段 2 有条目 ⇒ 阶段 1 必须**已满**（= 188）；阶段 3 有条目 ⇒ 阶段 2 必须非空。
· **④ 不回退**：三个计数**各自 ≥ 基线**（`tools/extraction-status-baseline.tsv`）
  ⇒ ⛔ 删条目／回退即红。⭐ `--write` **拒绝任何下降**（除非 `--force`，那一步 = 显式批准）。

⚠️ **它判不了什么**（诚实边界，⛔ 别把它读成验收）：
  ⛔ 判不了「摘要**提得对不对**」· ⛔ 判不了「这段**是不是裁定**」（那只有开发者能判）·
  ⛔ 判不了「开发者**看过没有**」—— ⭐ 三阶段的出口**全在开发者手里**，门禁只管**数量与阶段**。
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TARGET = ROOT / "docs" / "EXTRACTED_KNOWLEDGE.md"
BASELINE = ROOT / "tools" / "extraction-status-baseline.tsv"

#: ⭐ 阶段 1 的**冻结靶子**（回执 `003`：188 件；口径「三目录之和 − 1（本工序落点件）」见 `W7′-8`）。
TARGET1 = 188

#: 阶段的**识别记号** —— ✅ 只认二级标题（`## `），⛔ 不扫正文（正文里「阶段 1」出现在三阶段表里）。
STAGES = ((1, "阶段 1"), (2, "阶段 2"), (3, "阶段 3"))

#: ⚠️ 人口下限：阶段数少于此 ⇒ 响亮失败（表被删空也算通过，不放过）。
STAGE_FLOOR = 3


def parse(text: str) -> tuple[dict[int, int], list[str]]:
    """→ (每阶段的 `###` 条数, 结构问题)。⭐ ⛔ 不读任何**手写的数字** —— 一律**现数**。"""
    problems: list[str] = []
    lines = text.splitlines()
    heads = [(i, l) for i, l in enumerate(lines) if l.startswith("## ")]
    found: dict[int, list[int]] = {n: [] for n, _ in STAGES}
    for i, l in heads:
        for n, mark in STAGES:
            if mark in l:
                found[n].append(i)
    counts: dict[int, int] = {}
    for n, mark in STAGES:
        hit = found[n]
        if len(hit) != 1:
            problems.append(f"⛔ **判据①**：`## … {mark} …` 的二级标题有 **{len(hit)}** 个"
                            f"（⭐ 必须**恰好 1 个**）⇒ 改名／合并／删节都会走到这里 —— "
                            f"⭐ **解析不到就响亮失败**，⛔ 不静默报绿")
            counts[n] = 0
            continue
        start = hit[0]
        #: ⭐ 本节范围 = 该二级标题 ⇒ 下一个二级标题（⛔ 不含下一个 `## `）。
        end = next((j for j, _ in heads if j > start), len(lines))
        counts[n] = sum(1 for l in lines[start + 1:end] if l.startswith("### "))
    return counts, problems


def load_baseline() -> dict[int, int]:
    """读基线。⭐ 文件读不到 ⇒ `{}`（由 `check()` 响亮失败，⛔ 不静默当成「全是 0」）。"""
    if not BASELINE.exists():
        return {}
    out: dict[int, int] = {}
    for line in BASELINE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) == 2 and parts[0].isdigit():
            out[int(parts[0])] = int(parts[1])
    return out


def check(text: str | None = None, baseline: dict[int, int] | None = None) -> list[str]:
    """→ 不成立的判据（空列表 = 全绿）。⭐ 参数可注入 ⇒ 自证臂能真的跑判据。"""
    if text is None:
        if not TARGET.exists():
            return [f"⛔ 载体不存在：`{TARGET.relative_to(ROOT)}` ⇒ 本门禁无从判起"]
        text = TARGET.read_text(encoding="utf-8", errors="replace")
    if baseline is None:
        baseline = load_baseline()
    counts, problems = parse(text)

    if len(STAGES) < STAGE_FLOOR:
        problems.append(f"⛔ 阶段数 {len(STAGES)} < 人口下限 {STAGE_FLOOR}（表被删空也算通过 ⇒ 拦住）")
    if not baseline:
        problems.append(f"⛔ 基线文件读不到或为空：`{BASELINE.relative_to(ROOT)}` ⇒ "
                        f"跑 `python3 tools/check-extraction-status.py --write`（⭐ ⛔ 不许静默当成「全是 0」）")

    #: ⭐ **判据②**：靶子冻结 —— 阶段 1 ⛔ 不许超 188
    if counts.get(1, 0) > TARGET1:
        problems.append(f"⛔ **判据②**：阶段 1 摘要 **{counts[1]}** 条 > 靶子 **{TARGET1}** ⇒ "
                        f"⭐ 靶子**冻结**（回执 `003` 生效规则 9「⛔ 不追加」）—— "
                        f"⛔ 追加件数等于把「提不完」写进流程")

    #: ⭐ **判据③**：⛔ 不许跳阶段（阶段 N 有条目 ⇒ 阶段 N−1 必须已完成）
    if counts.get(2, 0) > 0 and counts.get(1, 0) != TARGET1:
        problems.append(f"⛔ **判据③**：阶段 2 已有 **{counts[2]}** 条，而阶段 1 只有 "
                        f"**{counts[1]}** / {TARGET1} ⇒ ⛔ **跳阶段**（阶段 1 没满就不许精提）")
    if counts.get(3, 0) > 0 and counts.get(2, 0) == 0:
        problems.append(f"⛔ **判据③**：阶段 3 已有 **{counts[3]}** 条，而阶段 2 **一条都没有** "
                        f"⇒ ⛔ **跳阶段**（没精提过就落地）")

    #: ⭐ **判据④**：⛔ 不回退（删条目／把已有摘要缩回去）
    for n, _ in STAGES:
        was, now = baseline.get(n), counts.get(n, 0)
        if was is not None and now < was:
            problems.append(f"⛔ **判据④**：阶段 {n} 从 **{was}** 退到 **{now}** ⇒ "
                            f"⛔ **只许变长**（删条目／回退 = 红）；⭐ 确实要撤 ⇒ `--write --force`（＝显式批准）")
    return problems


def write_baseline(counts: dict[int, int]) -> None:
    lines = ["# 阶段\t已产出条数（⭐ 由 tools/check-extraction-status.py --write 维护 · ⛔ 只许变长）"]
    lines += [f"{n}\t{counts.get(n, 0)}" for n, _ in STAGES]
    BASELINE.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _doc(c1: int, c2: int, c3: int) -> str:
    """造一份**形状合法**的假载体（⭐ 自证臂用它，⛔ 不碰真文件）。"""
    out = ["# 提取册（设计与经验）", ""]
    for n, mark, cnt in ((1, "阶段 1", c1), (2, "阶段 2", c2), (3, "阶段 3", c3)):
        out += [f"## 六 · {mark} · 节的标题", "", "正文。", ""]
        out += [f"### 条目 {i}" for i in range(cnt)]
        out.append("")
    return "\n".join(out)


def selftest() -> int:
    """**八臂**注入自证。⭐ 含**反臂**（合法形态必须安静）—— 那是本门禁能不能用的底线。"""
    arms: list[tuple[str, bool]] = []
    base = {1: 0, 2: 0, 3: 0}

    #: ⭐ **反臂**：空册（今天真实的样子）⇒ 必须安静
    arms.append(("A 空册（0/0/0）⇒ 安静", check(_doc(0, 0, 0), base) == []))
    #: 反臂：阶段 1 满了 ⇒ 安静
    arms.append(("B 阶段 1 = 188 ⇒ 安静", check(_doc(188, 0, 0), base) == []))
    #: 反臂：阶段 1 满 ＋ 阶段 2 有 ＋ 阶段 3 有 ⇒ 安静
    arms.append(("C 188/60/12 全链合法 ⇒ 安静", check(_doc(188, 60, 12), base) == []))
    #: 判据①：节标题被改名 ⇒ 红
    arms.append(("D 阶段 2 标题被改名 ⇒ 红（判据①）",
                 any("判据①" in p for p in check(_doc(0, 0, 0).replace("阶段 2", "第二阶段"), base))))
    #: 判据②：超过靶子 ⇒ 红
    arms.append(("E 阶段 1 = 189 > 188 ⇒ 红（判据②）",
                 any("判据②" in p for p in check(_doc(189, 0, 0), base))))
    #: 判据③：跳阶段（阶段 2 有而阶段 1 没满）⇒ 红
    arms.append(("F 阶段 2 有而阶段 1 未满 ⇒ 红（判据③）",
                 any("判据③" in p for p in check(_doc(5, 3, 0), base))))
    #: 判据③：跳阶段（阶段 3 有而阶段 2 空）⇒ 红
    arms.append(("G 阶段 3 有而阶段 2 空 ⇒ 红（判据③）",
                 any("判据③" in p for p in check(_doc(188, 0, 4), base))))
    #: 判据④：回退 ⇒ 红
    arms.append(("H 阶段 1 从 10 退回 4 ⇒ 红（判据④）",
                 any("判据④" in p for p in check(_doc(4, 0, 0), {1: 10, 2: 0, 3: 0}))))

    bad = 0
    for name, ok in arms:
        bad += not ok
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}")
    print(f"EXTRACTION_STATUS_SELFTEST {'PASS' if not bad else 'FAIL'}: arms={len(arms)} failed={bad}")
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser(description="「提取」三阶段状态门禁")
    #: ⚠️⭐ **`--check` 必须显式存在（哪怕是空操作）** —— 2026-10-05 落地当天当场踩到：
    #:   `argparse` 遇到**不认识的参数**会**退 2**，而 `tools/check-all.sh` 的 `run_gate`
    #:   把 **退 2 读成 WARN** ⇒ ⭐ **一个拼错的参数会被静默降级成"警告"**（⛔ 不是红）。
    #:   ⇒ 本仓的生成物型门禁（`cleanup-classify`／`plan-doc-refactor`／`ref-anchors`）都有 `--check`
    #:   ⇒ 本门禁跟上，⛔ 不给那条静默通道留口子。⚠️ **那条通道是仓级潜在洞**（任何门禁拼错参数同病），
    #:   ⛔ 本刀不扩范围去修它。
    ap.add_argument("--check", action="store_true", help="（默认行为）校验；⛔ 显式保留以免误退 2 = WARN")
    ap.add_argument("--write", action="store_true", help="把当前计数写进基线（⭐ 拒绝任何下降）")
    ap.add_argument("--force", action="store_true", help="允许基线下降（＝对「撤条目」的显式批准）")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()

    if a.selftest:
        return selftest()

    if a.write:
        if not TARGET.exists():
            print(f"⛔ 载体不存在：`{TARGET.relative_to(ROOT)}`")
            return 1
        counts, _ = parse(TARGET.read_text(encoding="utf-8", errors="replace"))
        base = load_baseline()
        down = [(n, base[n], counts.get(n, 0)) for n, _ in STAGES
                if n in base and counts.get(n, 0) < base[n]]
        if down and not a.force:
            for n, was, now in down:
                print(f"  [FAIL] 阶段 {n}：{was} → {now}（**下降**）")
            print("EXTRACTION_STATUS_RESULT REFUSED: ⛔ 基线只许变长 ⇒ 要下降必须 `--write --force`"
                  "（⭐ 那一步 = 你对「撤条目」的**显式批准**）")
            return 1
        write_baseline(counts)
        print(f"EXTRACTION_STATUS_RESULT WROTE: 阶段 1/2/3 = "
              f"{counts.get(1, 0)}/{counts.get(2, 0)}/{counts.get(3, 0)}"
              f"{'（⚠️ --force 下降）' if down else ''}")
        return 0

    problems = check()
    if problems:
        for p in problems[:12]:
            print(f"  [FAIL] {p}")
        print(f"EXTRACTION_STATUS_RESULT FAIL: {len(problems)} 条不成立")
        return 1
    counts, _ = parse(TARGET.read_text(encoding="utf-8", errors="replace"))
    done = "✅ 三个阶段都已收口" if counts.get(3, 0) > 0 else (
        "阶段 3 未开始" if counts.get(2, 0) > 0 else (
            "阶段 2 未开始" if counts.get(1, 0) == TARGET1 else "阶段 1 进行中"))
    print(f"EXTRACTION_STATUS_RESULT PASS: 阶段 1/2/3 = "
          f"{counts.get(1, 0)}/{counts.get(2, 0)}/{counts.get(3, 0)}"
          f"（⭐ 阶段 1 靶子 {TARGET1} · ⛔ 只许变长 · {done}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
