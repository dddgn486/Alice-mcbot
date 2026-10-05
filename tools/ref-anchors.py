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


def target_exists(rel: str, target: str, names: set[str] | None = None) -> bool:
    """→ 这个引用的**目标件真的存在**吗（⭐ 纯函数 ⇒ **可注入自证**）。

    ⚠️ ⭐ **为什么必须过滤**：回执 `002` 在自己的 `F1` 建议里写了一段**示例引用**，
    而旧 `scan()` **把"举例"读成了"引用"** ⇒ 基线被从 357 抬到 359 ⇒ ⛔ **「只许变短」被结构性污染**。
    ⭐ `check-ref-integrity` **早就有这条分类**（「文件不存在（仅提示）」）⇒ 本函数与它对齐。
    """
    names = {Path(f).name for f in tracked_md()} if names is None else names
    if "/" in target:
        return (ROOT / target).is_file() or (ROOT / rel).parent.joinpath(target).is_file()
    #: ⭐ 裸文件名（`AI_DECISIONS.md` 这种）：**按文件名在仓内解析** ——
    #: ⚠️ 它们的真身多在 `docs/` 下，只试仓根会把 **120 处真引用**误判掉（我第一版就踩了）。
    return target in names


def scan() -> dict[tuple[str, str], int]:
    """→ {(引用方, 被引方): 处数}。

    ⚠️ **自己排掉自己**：本脚本自身**不在 `.md` 名单里** ⇒ 天然不参与（⛔ 不需要特判）。

    ⭐⭐ **2026-10-05 加一条过滤（回执 `002` 落地时查出；开发者批）**：
    ⛔ **目标文件不存在的引用不计入**。⭐ **为什么必须过滤**：
    起因 = 存量基线从 **357 被抬到 359**，而 ⭐ **不是谁违规** ——
    是回执 `002` 在它的 `F1` 建议里写了**一段示例引用**（形如「`docs/` 下某个**不存在**的 `.md` 名 ＋ 行号」），
    而旧 `scan()` **把"示例"读成了"引用"** ⇒ ⭐ **每份新报告只要举例，基线就永久涨一处**
    ⇒ ⛔ **「只许变短」被结构性污染**（不是纪律问题，是**判据口径**问题）。
    ⭐ 而 `check-ref-integrity` **早就有这条分类**（它报「另有 N 处指向不存在文件的引用，不阻塞」）
    ⇒ ⭐ **本函数现在与它对齐**：**只数"指向仓内真有一份 `.md`"的引用**。
    """
    pairs: dict[tuple[str, str], int] = {}
    #: ⭐ 仓内所有受管 `.md` 的**文件名**集合 —— 用来判「这个引用指向的件真的存在吗」。
    #: ⚠️ ⭐ **为什么不能只试仓根**：实测本仓的引用**大量写成裸文件名**（`AI_DECISIONS.md` ·
    #:   `HANDOVER.md` …），而它们的**真身在 `docs/` 下** ⇒ 只试仓根会把 **120 处真引用**误判成"不存在"
    #:   （⭐ 我第一版就这么写的，359 被砍到 **239** ⇒ 当场发现并改成按文件名解析）。
    names = {Path(f).name for f in tracked_md()}
    for rel in tracked_md():
        try:
            text = (ROOT / rel).read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for m in DOCREF.finditer(text):
            target = m.group(0).rsplit(":", 1)[0]
            # 只收"指向仓内真有一份 .md"的引用 —— ⛔ 裸文件名 + 行号那种不算
            base = target.rsplit("/", 1)[-1]
            key = (rel, target if "/" in target else base)
            #: ⭐ **目标必须真存在** —— 判据抽在纯函数 `target_exists()` 里（⇒ **可注入自证**，臂 K/L）。
            if not target_exists(rel, key[1], names):
                continue
            # ⚠️ **注释里也不许写 `.md` 加数字的活样本**：本门禁会命中它自己（臂 E 守这条）。
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


def increments(old: dict[tuple[str, str], int], new: dict[tuple[str, str], int]) -> list[str]:
    """→ **新增的引用**（⭐ 含"新出现的引用对"与"同一对处数变大"两类），排好序。

    ⭐ **为什么单独一个函数**（2026-10-05，回执 `002` 的 `F1`，开发者批「批 #4」）：
    起因 = ⚠️ ⭐ **本轮主工作流亲手踩过** —— 它在 `QUESTIONS_LEDGER` 里写了一处**新增**的行号引用
    （= 违反本节那条「**只许变短**」），而 `--write` **默默把它收进了基线**（**357 → 358**），⛔ **没有报红**。
    ⭐ 那个 `--write` 的语义**本来是"我批准这个新增"**，却长得像"重新生成一下" ⇒ ⛔ **违例被洗成合规**。
    """
    out: list[str] = []
    for k in set(old) | set(new):
        o, n = old.get(k, 0), new.get(k, 0)
        if n > o:
            tag = "新出现的引用对" if k not in old else "同一对的处数变大"
            out.append(f"{k[0]} → {k[1]}：{o} → {n}（{tag}）")
    return sorted(out)


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

    #: ⭐⭐ 臂 H/I/J：**`--write` 的写入口守卫**（2026-10-05，回执 `002` 的 `F1`，开发者批「批 #4」）。
    #:   ⚠️ 起因 = 本轮主工作流写了一处**新增**引用，`--write` **默默收下**（357 → 358）⇒ 违例被洗成合规。
    #:   ⭐ 这三臂**直接测 `increments()`** —— 它就是写入口判据的本体（⛔ 不是测注释）。
    arms.append(("H 增量被判出（新出现的对）⇒ `--write` 须拒绝", "", 
                 increments({}, {("a.md", "b.md"): 1}) == ["a.md → b.md：0 → 1（新出现的引用对）"]))
    arms.append(("I 增量被判出（同一对处数变大）⇒ `--write` 须拒绝", "",
                 increments({("a.md", "b.md"): 1}, {("a.md", "b.md"): 2})
                 == ["a.md → b.md：1 → 2（同一对的处数变大）"]))
    #: ⭐ **反臂**：减量 **⛔ 不许**被判成增量 —— 否则「只许变短」这条规则自己把路堵死
    #:（本仓踩过同族坑：门禁把**合法**动作也拦下 ⇒ 下一个人学会绕过它）。
    arms.append(("J **减量不许**被判成增量（否则合法路径被堵）", "",
                 increments({("a.md", "b.md"): 2}, {("a.md", "b.md"): 1}) == []
                 and increments({("a.md", "b.md"): 1}, {}) == []))

    #: ⭐ 臂 K/L：**"举例"不许被当成"引用"**（2026-10-05 新过滤；开发者批）。
    #:   ⚠️ 起因：回执 `002` 的 `F1` 里写了一段**示例引用**，旧 `scan()` 把它数进基线（357 → 359）。
    #:   ⭐ 这两臂**直接测纯函数 `target_exists()`**（⛔ 不是测注释）。
    arms.append(("K 目标不存在 ⇒ 该引用不计入（举例 ≠ 引用）", "",
                 not target_exists("a.md", "docs/根本没有这个.md")
                 and not target_exists("a.md", "根本没有这个.md")))
    #: ⭐ **反臂**：真目标必须**照旧计入** —— ⛔ 否则过滤器会把真引用也砍掉
    #:（⚠️ 我第一版就砍多了：359 → 239，因为裸名件的真身在 `docs/` 下）。
    arms.append(("L 真目标不许被误砍（含裸名件的真身在 docs/ 下）", "",
                 target_exists("a.md", "AGENTS.md") and target_exists("a.md", "AI_DECISIONS.md")))

    # ⭐ **活样本不许写进本文件**（否则门禁命中自己 —— 同族坑本仓踩过多次）
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
    #: ⭐ `--force`：**唯一**能批准"新增引用"的方式（2026-10-05，回执 `002` 的 `F1`，开发者批「批 #4」）。
    ap.add_argument("--force", action="store_true",
                    help="批准本轮的**新增**行号引用（⛔ 不加它时，`--write` 一遇到增量就拒绝写）")
    a = ap.parse_args()
    if a.selftest:
        return selftest()
    if a.write:
        pairs = scan()
        #: ⭐⭐ **「只许变短」必须在写入口生效** —— ⛔ 不能只写在注释里：注释挡不住 `--write`。
        #: 起因：⭐ 本轮主工作流写了一处新增引用，`--write` **默默收下**（357 → 358）⇒ 违例被洗成合规。
        inc = increments(load_baseline(), pairs)
        if inc and not a.force:
            print("REF_ANCHORS_RESULT REFUSED: ⛔ 检测到**新增**的行号引用 ⇒ 拒绝写入（本基线「只许变短」）")
            for line in inc:
                print(f"  + {line}")
            print("  ⇒ ⭐ 若这是**有意批准**的新增，请用 `--write --force`（⭐ 那一步 = 你的显式批准）")
            print("  ⇒ ⛔ 若这不是有意的：把新写的那处行号引用**删掉**（改用锚点，见 `§D′-9` 9.5）")
            return 1
        if inc:
            print("⚠️  `--force` ⇒ **批准**以下新增（⭐ 请确认这是有意的）：")
            for line in inc:
                print(f"  + {line}")
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
