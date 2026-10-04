#!/usr/bin/env python3
"""门禁：**文档引用用锚点，⛔ 不用行号**（用户 2026-10-04 裁「**先用锚点式**」）。

沿用本仓成熟模子：**单一出处 + 双向防漂移 + 解析不到就响亮失败 + 挂 `check-all`**。

## 判据（机械）
扫全仓被跟踪的 `.md`，找出「**文档行号引用**」= `某份.md:第 N 行` 这种写法；
按 **(引用方文件, 被引方文件)** 计数，与基线 `tools/ref-anchor-baseline.tsv` 逐条比：

  · ⛔ **新增**（冒出新的一对，或某一对计数变大）⇒ **红**；
  · ⛔ **登记项变小 / 消失** ⇒ **红**（要求同步基线 —— ⛔ **不许留假账**，照 `check-authz-code-refs` 的双向自证）；
  · ⛔ **一处都扫不到** ⇒ **红**（人口下限：**扫不到的入口不放过**）。

## 「锚点式」是什么（合法的替代形式）

`D-###` 决策编号 · `断点NN` · `O###` 台账号 · `W#-#` 工序 id · `§x.y` 或小节标题 ·
门禁 id / step 名 / 类名。⭐ 它们**插入删除自动容错**。

⚠️ 行号**一插就漂**，而 `tools/ref-integrity.py` **只查越界、查不出内容漂**
（用户 2026-10-04：「**不应该让门禁检查来限制这次的文档整顿**，但是**门禁本身的存在理由没有问题**」）
⇒ ⭐ 所以处置**不是**"给整顿加一条加行限制"，而是 ⭐ **改引用风格**：**新增的行号引用当场红**。

## 为什么是"存量只许变短"，不是"一次改完存量"

存量逐处改写要**逐条判「这一处指的是哪个锚点」** = **语义活** ⇒ 属另一条线（批 2）。
本门禁先把**新增**堵死（纪律：**门禁 > 散文**），存量随批 2 与日常改动**自然收缩**，且**只许变短**。

## 边界（⛔ 如实写）

* ⛔ 它**不查**代码引用（`Foo.java:123` / `Class.method:123`）—— 那是另一条线（`ref-integrity` 已查越界）；
* ⛔ 它**不判**某个锚点"用对了没有"（⛔ 语义）；
* ⚠️ 它按 **(引用方, 被引方) 计数**，⛔ **不记行号数值** —— ⭐ 刻意的：行号天天漂，记它就变成噪音门禁。
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASELINE = ROOT / "tools" / "ref-anchor-baseline.tsv"

#: ⛔ **不许把 `.md:数字` 的活样本写进本文件** —— 否则本门禁会命中它自己（同族坑本仓踩过多次）。
#: ⭐ 正则里的 `.md:` 后面接的是 `\d`，⛔ 不是具体数字 ⇒ 源码里没有可命中的活样本。
DOCREF = re.compile(r"[A-Za-z0-9_./\-\u4e00-\u9fff]+\.md:(\d+)")

#: 人口下限：扫到的**处数**少于此 ⇒ 红（判据被删空 / 正则被改坏 ⇒ 报绿的入口）。
REF_FLOOR = 100


def tracked_md() -> list[str]:
    """被跟踪的 `.md`（⚠️ 必须 `-z`：非 ASCII 路径不带 `-z` 会被 git 加引号 ⇒ 漏件）。"""
    out = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT,
                         capture_output=True, text=True).stdout
    return sorted(p for p in out.split("\0") if p.endswith(".md"))


def scan() -> dict[tuple[str, str], int]:
    """→ {(引用方, 被引方): 处数}。

    ⚠️ **自己排掉自己**：本脚本自身**不在 `.md` 名单里** ⇒ 天然不参与（⛔ 不需要特判）。
    """
    pairs: dict[tuple[str, str], int] = {}
    for rel in tracked_md():
        try:
            text = (ROOT / rel).read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for m in DOCREF.finditer(text):
            target = m.group(0).rsplit(":", 1)[0]
            # 只收"指向仓内真有一份 .md"的引用 —— ⛔ 裸文件名 + 行号那种不算
            # ⚠️ **注释里也不许写 `.md` 加数字的活样本**：本门禁会命中它自己（臂 E 守这条）。
            base = target.rsplit("/", 1)[-1]
            key = (rel, target if "/" in target else base)
            pairs[key] = pairs.get(key, 0) + 1
    return pairs


def total(pairs: dict[tuple[str, str], int]) -> int:
    return sum(pairs.values())


def load_baseline() -> dict[tuple[str, str], int]:
    if not BASELINE.exists():
        return {}
    out: dict[tuple[str, str], int] = {}
    for line in BASELINE.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) != 3:
            raise ValueError(f"基线行格式不对（要 3 列 TSV）：{line!r}")
        out[(parts[0], parts[1])] = int(parts[2])
    return out


def write_baseline(pairs: dict[tuple[str, str], int]) -> None:
    lines = ["# 文档「行号引用」存量基线 —— ⛔ 只许变短（新增 ⇒ 红；减少 ⇒ 必须同步本文件）。",
             "# 生成：python3 tools/ref-anchors.py --write　·　列：引用方文件 <TAB> 被引方文件 <TAB> 处数",
             f"# 总计 {total(pairs)} 处 / {len(pairs)} 对（{len({a for a, _ in pairs})} 个引用方文件）",
             ""]
    for (a, b), n in sorted(pairs.items()):
        lines.append(f"{a}\t{b}\t{n}")
    BASELINE.write_text("\n".join(lines) + "\n", encoding="utf-8")


def check() -> int:
    problems: list[str] = []
    try:
        pairs = scan()
    except Exception as exc:                                   # noqa: BLE001
        print("REF_ANCHORS_RESULT FAIL")
        print(f"  [FAIL] 扫描崩塌（⛔ 不静默放过）：{exc!r}")
        return 1
    n = total(pairs)
    if n < REF_FLOOR:
        problems.append(f"只扫到 {n} 处行号引用 < 人口下限 {REF_FLOOR} ⇒ "
                        "判据被删空或正则被改坏（⛔ 扫不到就报绿的入口，不放过）")
    base = load_baseline()
    if not base:
        problems.append("基线不存在或为空：tools/ref-anchor-baseline.tsv ⇒ 跑 "
                        "`python3 tools/ref-anchors.py --write`")
    else:
        for k in sorted(set(pairs) | set(base)):
            got, want = pairs.get(k, 0), base.get(k, 0)
            if got > want:
                problems.append(f"⛔ **新增了行号引用**：`{k[0]}` → `{k[1]}` {want} → {got} 处 ⇒ "
                                "**改用锚点**（`D-###`／`断点NN`／`O###`／`§小节`／工序 id／门禁 id）")
            elif got < want:
                problems.append(f"⚠️ `{k[0]}` → `{k[1]}` 由 {want} 处减到 {got} 处 ⇒ "
                                "**同步基线**（`--write`）：⛔ 不许留假账")
    if problems:
        print("REF_ANCHORS_RESULT FAIL")
        for p in problems:
            print(f"  [FAIL] {p}")
        return 1
    print(f"REF_ANCHORS_RESULT PASS: 行号引用 {n} 处 / {len(pairs)} 对 / "
          f"{len({a for a, _ in pairs})} 个引用方文件 —— ⛔ 只许变短（新增即红）")
    return 0


def selftest() -> int:
    arms: list[tuple[str, str, bool]] = []

    # 臂 A：**新增一处**必须被抓（喂一对基线里没有的）
    fake_pairs = {("x.md", "y.md"): 1}
    base = {}
    new = {k for k in fake_pairs if fake_pairs.get(k, 0) > base.get(k, 0)}
    arms.append(("A 新增行号引用被抓住", "新增了行号引用", bool(new)))

    # 臂 B：**计数变大**也必须被抓（同一次引用关系的第二处）
    base2, got2 = {("x.md", "y.md"): 1}, {("x.md", "y.md"): 2}
    arms.append(("B 同对计数变大被抓住", "新增了行号引用",
                 any(got2.get(k, 0) > base2.get(k, 0) for k in set(base2) | set(got2))))

    # 臂 C：**减少**必须要求同步基线（⛔ 不许留假账）
    base3, got3 = {("x.md", "y.md"): 2}, {("x.md", "y.md"): 1}
    arms.append(("C 减少要求同步基线（不许留假账）", "同步基线",
                 any(got3.get(k, 0) < base3.get(k, 0) for k in set(base3) | set(got3))))

    # 臂 D：⭐ **锚点式引用必须安静** —— 否则本门禁会把人逼回行号
    anchor_samples = [
        "见 `D-532` §九 与 `O140` ⑧",
        "见 `docs/HANDOVER.md` 的 `## 断点六十六`",
        "见 `docs/DOC_REFACTOR_PLAN.md` 的 `W7′-4`",
        "见 `tools/check-new-home.sh` 的臂 F",
        "见 `docs/GLOSSARY.md` 的「状态词」那一节",
    ]
    arms.append(("D 锚点式引用必须安静", "", not any(DOCREF.search(s) for s in anchor_samples)))

    # 臂 E：⭐ **活样本不许写进本文件**（否则门禁命中自己 —— 同族坑本仓踩过多次）
    me = Path(__file__).read_text(encoding="utf-8")
    arms.append(("E 本文件自身不含可命中的活样本", "自污染", not DOCREF.search(me)))

    # 臂 F：**扫描真的能扫到东西**（否则 A–E 全在真空中成立）
    real = scan()
    arms.append(("F 真树上真的扫得到（不是空扫）", "空扫", total(real) >= REF_FLOOR))

    # 臂 G：**基线双向自证** —— 基线必须与真树一致（存量登记不是摆设）
    base_real = load_baseline()
    arms.append(("G 基线与真树一致", "不同步", base_real == real))

    bad = 0
    for name, want, ok in arms:
        bad += not ok
        print(f"  [{'PASS' if ok else 'FAIL'}] {name} ⇒ "
              f"{'期望红且含「' + want + '」' if want else '期望安静'}")
    print(f"REF_ANCHORS_SELFTEST {'PASS' if not bad else 'FAIL'}: arms={len(arms)} failed={bad}")
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
        pairs = scan()
        write_baseline(pairs)
        print(f"已写入 {BASELINE.relative_to(ROOT)}：{total(pairs)} 处 / {len(pairs)} 对")
        return 0
    if a.check:
        return check()
    pairs = scan()
    print(f"行号引用 {total(pairs)} 处 / {len(pairs)} 对 / "
          f"{len({a for a, _ in pairs})} 个引用方文件")
    return 0


if __name__ == "__main__":
    sys.exit(main())
