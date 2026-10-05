#!/usr/bin/env python3
"""门禁：**「提取」漏斗的形状与数量**（2026-10-05 立 · **同日按开发者重裁改写**）。

⭐⭐ **为什么有它** —— 本工序（`W7′-8`）是**唯一一个"做完了没有"不由件数决定**的工序：
`W7′-4` 的进度可以数「56 件里判了几件」，而**提取**的进度是
「**188 件里筛出多少 / 每一遍剩多少 / 落地多少条**」—— ⛔ 这三样**没有任何东西盯着**。
⚠️ 而「提不完」会**静默地**发生（⭐ 本仓最贵那族错：扫不到就报绿）。

⭐⭐ **2026-10-05 开发者重裁（逐字）**：「我选择不这么划线，**有价值就留下**，**丙不是初筛的问题**，
我考虑了下，不仅筛选方案是要**先粗筛一遍，再细筛一遍，还要再筛一遍重复和冲突的，特别是和新
文档体系重复冲突的，还有已经落地不会丢的，可能还不止这几遍**，最后才拿给我自己审批」
⇒ ⭐ **它不是"三阶段"，是一个多遍筛的漏斗，而且遍数未冻**。
⇒ ⭐⭐ **本门禁因此刻意"遍数无关"** —— ⛔ **不写死遍数**，只判**漏斗的形状与数量**：
   ① **结构**：遍号**从 1 起、连续、不重**（`### 第 N 遍 …`）；`## … 落地 …` 节**恰好一个**
      （⭐ **落地是目的地，⛔ 不是一遍**）⇒ ⛔ 改名／跳号／删节 ⇒ 红（**解析不到就响亮失败**）。
   ② **漏斗单调不增**：⭐ **第 N+1 遍的条目数 ≤ 第 N 遍** —— 这是"漏斗"两个字的**全部含义**
      （⛔ 筛着筛着反而变多 ⇒ 那不是筛，是又攒了一堆）。
   ③ **上限**：第 1 遍 ≤ `TARGET1`（**188**）⇒ ⭐ 靶子**冻结**（回执 `003` 生效规则 9「⛔ 不追加」）。
   ④ **只许变长**：每一遍（含落地）的条目数 **各自 ≥ 基线** ⇒ ⛔ 删条目／回退即红。
      ⭐ `--write` **拒绝任何下降**（除非 `--force`，那一步 = 显式批准）。

⚠️ **它判不了什么**（诚实边界，⛔ 别把它读成验收）：
  ⛔ 判不了「提得对不对」· ⛔ 判不了「这段**是不是裁定**」（只有开发者能判）·
  ⛔ 判不了「开发者**看过没有**」—— ⭐ 开发者逐字「**每过一次我还是要自己大概检查一遍**」，
  那一遍**不在门禁的判据里**（⛔ 门禁判不了人的动作，假装能判就是伪造完成感）。
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TARGET = ROOT / "docs" / "EXTRACTED_KNOWLEDGE.md"
BASELINE = ROOT / "tools" / "extraction-status-baseline.tsv"

#: ⭐ 第 1 遍的**冻结靶子**（回执 `003`：188 件；口径「三目录之和 − 1（本工序的落点件）」见 `W7′-8`）。
TARGET1 = 188

#: 一遍的标题记号（⭐ **遍数刻意不写死** —— 见文件头）。
PASS_RE = re.compile(r"^#{2,4}\s*第\s*(\d+)\s*遍")
#: ⚠️⭐ 落地节的标题记号 —— **必须锚在标题的开头**（`## 七 · 落地…`），⛔ 不含"正文里提到落地"。
#:   起因（2026-10-05 落地当天当场抓到）：`^##\s.*落地` 把
#:   `## 二 · 每条的形状（⭐ **落地时**照这个写）` **也算成了落地节** ⇒ ⭐ **假阳性**。
LANDING_RE = re.compile(r"^##\s*(?:[〇一二三四五六七八九十百]+\s*·\s*)?落地")
#: 条目的记号。
ITEM_RE = re.compile(r"^####\s")
#: ⭐ 代码围栏开关 —— ⛔ **围栏内的行一律不参与解析**。
#:   起因（同一次）：`§二` 的**模板示例** `#### [主题]：[一句话标题]` 被**数成了一条真条目**
#:   ⇒ ⭐ 本仓最贵那族错的近距离样本：**门禁数错了，而它读起来完全正常**。
FENCE_RE = re.compile(r"^\s*(?:```|~~~)")

#: ⚠️ 人口下限：遍数少于此 ⇒ 响亮失败（节被删空也算通过，不放过）。
PASS_FLOOR = 1

#: 基线的键：`p<N>` = 第 N 遍 · `landing` = 落地节。
LANDING_KEY = "landing"


def parse(text: str) -> tuple[dict[str, int], list[str]]:
    """→ (各遍／落地的 `####` 条数, 结构问题)。⭐ ⛔ 不读任何**手写的数字** —— 一律**现数**。

    ⚠️⭐ **代码围栏内的行一律跳过**（⭐ 见 `FENCE_RE` 的注释：`§二` 的模板示例曾被数成真条目）。
    """
    problems: list[str] = []
    raw = text.splitlines()

    #: ⭐ 先按围栏切：围栏内的行**完全不出现在下面任何一步里**。
    lines: list[str] = []
    index: list[int] = []          # lines[i] 在原文里的行号
    in_fence = False
    for i, l in enumerate(raw):
        if FENCE_RE.match(l):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        lines.append(l)
        index.append(i)

    #: ⭐ 再找全部"边界行"（遍头 ＋ 落地头），按出现顺序切段。
    bounds: list[tuple[int, str, int | None]] = []      # (lines 下标, 种类, 遍号)
    landings: list[int] = []
    for i, l in enumerate(lines):
        m = PASS_RE.match(l)
        if m:
            bounds.append((i, "pass", int(m.group(1))))
            continue
        if LANDING_RE.match(l):
            bounds.append((i, "landing", None))
            landings.append(i)

    counts: dict[str, int] = {}
    passes: list[int] = []

    if len(landings) != 1:
        problems.append(f"⛔ **判据①**：`## … 落地 …` 的二级标题有 **{len(landings)}** 个"
                        f"（⭐ 必须**恰好 1 个**：**落地是目的地，⛔ 不是一遍**）")

    for k, (idx, kind, num) in enumerate(bounds):
        end = bounds[k + 1][0] if k + 1 < len(bounds) else len(lines)
        n = sum(1 for l in lines[idx + 1:end] if ITEM_RE.match(l))
        if kind == "pass":
            assert num is not None
            passes.append(num)
            counts[f"p{num}"] = counts.get(f"p{num}", 0) + n     # ⭐ 重号会在这里累加，由判据①抓
        else:
            counts[LANDING_KEY] = counts.get(LANDING_KEY, 0) + n

    if len(passes) < PASS_FLOOR:
        problems.append(f"⛔ **判据①**：一遍都没有（`### 第 N 遍 …`）⇒ "
                        f"人口下限 {PASS_FLOOR} —— ⭐ 节被删空也算通过，⛔ 不放过")
    if passes:
        dup = sorted({n for n in passes if passes.count(n) > 1})
        if dup:
            problems.append(f"⛔ **判据①**：遍号重复：{dup} ⇒ ⭐ 遍号必须**不重**")
        if passes[0] != 1:
            problems.append(f"⛔ **判据①**：第一遍的遍号是 **{passes[0]}** ⇒ ⭐ 必须**从 1 起**")
        gaps = [n for n in range(1, len(set(passes)) + 1) if n not in passes]
        if gaps:
            problems.append(f"⛔ **判据①**：遍号不连续，缺 {gaps} ⇒ ⭐ 遍号必须**连续**"
                            f"（⭐ 新的一遍往后加，⛔ 不许跳号）")
    return counts, problems


def load_baseline() -> dict[str, int]:
    """读基线。⭐ 文件读不到 ⇒ `{}`（由 `check()` 响亮失败，⛔ 不静默当成「全是 0」）。"""
    if not BASELINE.exists():
        return {}
    out: dict[str, int] = {}
    for line in BASELINE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) == 2 and parts[0]:
            try:
                out[parts[0]] = int(parts[1])
            except ValueError:
                continue
    return out


def _pass_keys(counts: dict[str, int]) -> list[str]:
    """有值的遍键，按遍号升序（⭐ 供判据②用）。"""
    nums = sorted(int(k[1:]) for k in counts if k.startswith("p") and k[1:].isdigit())
    return [f"p{n}" for n in nums]


def check(text: str | None = None, baseline: dict[str, int] | None = None) -> list[str]:
    """→ 不成立的判据（空列表 = 全绿）。⭐ 参数可注入 ⇒ 自证臂能真的跑判据。"""
    if text is None:
        if not TARGET.exists():
            return [f"⛔ 载体不存在：`{TARGET.relative_to(ROOT)}` ⇒ 本门禁无从判起"]
        text = TARGET.read_text(encoding="utf-8", errors="replace")
    if baseline is None:
        baseline = load_baseline()
    counts, problems = parse(text)

    if not baseline:
        problems.append(f"⛔ 基线文件读不到或为空：`{BASELINE.relative_to(ROOT)}` ⇒ "
                        f"跑 `python3 tools/check-extraction-status.py --write`（⭐ ⛔ 不许静默当成「全是 0」）")

    keys = _pass_keys(counts)

    #: ⭐ **判据②**：漏斗单调不增 —— 这是"漏斗"两个字的全部含义
    for a, b in zip(keys, keys[1:]):
        if counts[b] > counts[a]:
            problems.append(f"⛔ **判据②**：漏斗**反了** —— 第 {b[1:]} 遍 **{counts[b]}** 条 > "
                            f"第 {a[1:]} 遍 **{counts[a]}** 条 ⇒ ⭐ 每一遍**只许变少或不增**"
                            f"（⛔ 筛着筛着变多 ⇒ 那不是筛，是又攒了一堆）")

    #: ⭐ **判据③**：靶子冻结 —— 第 1 遍 ⛔ 不许超 188
    if counts.get("p1", 0) > TARGET1:
        problems.append(f"⛔ **判据③**：第 1 遍 **{counts['p1']}** 条 > 靶子 **{TARGET1}** ⇒ "
                        f"⭐ 靶子**冻结**（回执 `003` 生效规则 9「⛔ 不追加」）—— "
                        f"⛔ 追加件数等于把「提不完」写进流程")

    #: ⭐ **判据④**：⛔ 不回退（删条目／把已有条目缩回去）
    for k, now in counts.items():
        was = baseline.get(k)
        if was is not None and now < was:
            label = f"第 {k[1:]} 遍" if k.startswith("p") else "落地节"
            problems.append(f"⛔ **判据④**：{label}从 **{was}** 退到 **{now}** ⇒ "
                            f"⛔ **只许变长**（删条目／回退 = 红）；⭐ 确实要撤 ⇒ `--write --force`（＝显式批准）")
    return problems


def write_baseline(counts: dict[str, int]) -> None:
    keys = _pass_keys(counts) + ([LANDING_KEY] if LANDING_KEY in counts else [])
    lines = ["# 键\t已产出条数（⭐ 由 tools/check-extraction-status.py --write 维护 · ⛔ 只许变长）",
             "# 键 = `p<N>`（第 N 遍）或 `landing`（落地节）"]
    lines += [f"{k}\t{counts[k]}" for k in keys]
    BASELINE.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _doc(counts: list[int], landing: int = 0, *,
         start_at: int = 1, skip: int | None = None, dup_landing: bool = False) -> str:
    """造一份**形状合法**的假载体（⭐ 自证臂用它，⛔ 不碰真文件）。"""
    out = ["# 提取册（设计与经验）", "", "## 三 · 怎么算跑完了", ""]
    n = start_at
    for i, cnt in enumerate(counts):
        if i == 1 and skip is not None:
            n = skip
        out += [f"### 第 {n} 遍 · 假的一遍", ""]
        out += [f"#### 条目 {k}" for k in range(cnt)]
        out.append("")
        n += 1
    out += ["## 七 · 落地（⭐ 目的地）", ""]
    out += [f"#### 落地条目 {k}" for k in range(landing)]
    out.append("")
    if dup_landing:
        out += ["## 八 · 落地（⭐ 又一个落地节）", ""]
    return "\n".join(out)


def selftest() -> int:
    """**十二臂**注入自证。⭐ 含**反臂**（合法形状必须安静）—— 那是本门禁能不能用的底线。"""
    arms: list[tuple[str, bool]] = []
    base = {"p1": 0, LANDING_KEY: 0}

    #: ⭐ **反臂**：空册（今天真实的样子）⇒ 必须安静
    arms.append(("A 空册（一遍 0 条）⇒ 安静", check(_doc([0]), base) == []))
    #: 反臂：第 1 遍满 ⇒ 安静
    arms.append(("B 第 1 遍 = 188 ⇒ 安静", check(_doc([188]), base) == []))
    #: 反臂：漏斗递减（188 → 60 → 12）⇒ 安静
    arms.append(("C 漏斗 188/60/12 ⇒ 安静", check(_doc([188, 60, 12]), base) == []))
    #: ⭐ 反臂：**遍数随便加**（188/60/12/4/1）⇒ 必须**安静**（⭐ 这正是"遍数未冻"）
    arms.append(("D 五遍 188/60/12/4/1 ⇒ 安静（遍数未冻）", check(_doc([188, 60, 12, 4, 1]), base) == []))
    #: 判据①：遍号不从 1 起 ⇒ 红
    arms.append(("E 第一遍的遍号是 2 ⇒ 红（判据①）",
                 any("判据①" in p for p in check(_doc([10], start_at=2), base))))
    #: 判据①：跳号（1,3）⇒ 红
    arms.append(("F 遍号跳号 1,3 ⇒ 红（判据①）",
                 any("判据①" in p for p in check(_doc([10, 5], skip=3), base))))
    #: 判据①：落地节两个 ⇒ 红
    arms.append(("G 两个落地节 ⇒ 红（判据①）",
                 any("判据①" in p for p in check(_doc([10], dup_landing=True), base))))
    #: 判据②：漏斗反了 ⇒ 红
    arms.append(("H 第 2 遍(20) > 第 1 遍(10) ⇒ 红（判据②）",
                 any("判据②" in p for p in check(_doc([10, 20]), base))))
    #: 判据③：超过靶子 ⇒ 红
    arms.append(("I 第 1 遍 = 189 > 188 ⇒ 红（判据③）",
                 any("判据③" in p for p in check(_doc([189]), base))))
    #: 判据④：回退 ⇒ 红
    arms.append(("J 第 1 遍从 10 退回 4 ⇒ 红（判据④）",
                 any("判据④" in p for p in check(_doc([4]), {"p1": 10, LANDING_KEY: 0}))))
    #: ⭐⭐ **反臂（数对了没有）**：代码围栏里的 `#### ` ⇒ **⛔ 不计入条数**
    #:   （⭐ 真实起因：`§二` 的**模板示例**曾被数成一条真条目 —— 门禁当场抓到自己）
    fc = _doc([0]).replace("## 七 · 落地", "```markdown\n#### 模板示例：不算条目\n```\n\n## 七 · 落地")
    arms.append(("K 围栏里的 `#### ` ⇒ ⛔ 不计入条数", parse(fc)[0].get("p1", 0) == 0))
    #: ⭐⭐ **反臂（认对了没有）**：标题里**提到**「落地」的**别的节** ⇒ ⛔ 不算落地节
    #:   （⭐ 真实起因：`## 二 · 每条的形状（⭐ **落地时**照这个写）` 曾被当成第二个落地节）
    lc = _doc([1]).replace("### 第 1 遍", "## 二 · 每条的形状（⭐ 落地时照这个写）\n\n### 第 1 遍")
    arms.append(("L 标题含「落地时」的别的节 ⇒ ⛔ 不算落地节",
                 not any("判据①" in p for p in check(lc, base))))

    bad = 0
    for name, ok in arms:
        bad += not ok
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}")
    print(f"EXTRACTION_STATUS_SELFTEST {'PASS' if not bad else 'FAIL'}: arms={len(arms)} failed={bad}")
    return 1 if bad else 0


def _summary(counts: dict[str, int]) -> str:
    keys = _pass_keys(counts)
    chain = "／".join(str(counts[k]) for k in keys) if keys else "—"
    return (f"漏斗 {' → '.join(str(counts[k]) for k in keys) if keys else '（空）'}"
            f"（共 {len(keys)} 遍 · {chain}）· 落地 **{counts.get(LANDING_KEY, 0)}**"
            f"（⭐ 第 1 遍靶子 {TARGET1} · ⛔ 只许变长 · 遍数**未冻**）")


def main() -> int:
    ap = argparse.ArgumentParser(description="「提取」漏斗的形状与数量门禁")
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
        down = [(k, base[k], counts.get(k, 0)) for k in counts
                if k in base and counts.get(k, 0) < base[k]]
        if down and not a.force:
            for k, was, now in down:
                print(f"  [FAIL] {k}：{was} → {now}（**下降**）")
            print("EXTRACTION_STATUS_RESULT REFUSED: ⛔ 基线只许变长 ⇒ 要下降必须 `--write --force`"
                  "（⭐ 那一步 = 你对「撤条目」的**显式批准**）")
            return 1
        write_baseline(counts)
        print(f"EXTRACTION_STATUS_RESULT WROTE: {_summary(counts)}"
              f"{'（⚠️ --force 下降）' if down else ''}")
        return 0

    problems = check()
    if problems:
        for p in problems[:12]:
            print(f"  [FAIL] {p}")
        print(f"EXTRACTION_STATUS_RESULT FAIL: {len(problems)} 条不成立")
        return 1
    counts, _ = parse(TARGET.read_text(encoding="utf-8", errors="replace"))
    print(f"EXTRACTION_STATUS_RESULT PASS: {_summary(counts)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
